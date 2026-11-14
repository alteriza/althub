package com.althub.alteriza

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object LocalStreamServer {
    private val itemsById = ConcurrentHashMap<String, RemoteMediaItem>()
    private val headersById = ConcurrentHashMap<String, ContainerHeaderInfo>()
    private val seekTablesById = ConcurrentHashMap<String, VcvSeekTable>()
    private val decompressedChunkCache = ConcurrentHashMap<String, ByteArray>()
    private val spoolJobs = ConcurrentHashMap<String, SpoolJob>()
    private val executor = Executors.newCachedThreadPool()
    private val spoolExecutor = Executors.newCachedThreadPool()

    private data class SeekChunk(
        val index: Int,
        val compOffset: Long,
        val compSize: Int,
        val decompOffset: Long,
        val decompSize: Int
    )

    private data class VcvSeekTable(
        val chunkSize: Int,
        val totalDecompressedSize: Long,
        val chunks: List<SeekChunk>
    )

    @Volatile
    private var appCacheDir: File? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    private class SpoolJob(
        val file: File,
        val bytesWritten: AtomicLong = AtomicLong(0L),
        val totalSize: AtomicLong = AtomicLong(-1L),
        val completed: AtomicBoolean = AtomicBoolean(false),
        val error: AtomicReference<Throwable?> = AtomicReference(null)
    )

    fun initCacheDir(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "stream_cache")
        dir.mkdirs()
        appCacheDir = dir
    }

    private fun getStreamCacheDir(): File {
        val existing = appCacheDir
        if (existing != null) {
            existing.mkdirs()
            return existing
        }
        val tmp = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "althub_stream_cache")
        tmp.mkdirs()
        return tmp
    }

    private fun ensureStarted(): Int {
        if (port > 0 && serverSocket?.isClosed == false) return port
        synchronized(this) {
            if (port > 0 && serverSocket?.isClosed == false) return port
            val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            val acceptThread = Thread {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        executor.execute { handleClient(client) }
                    } catch (_: Throwable) {
                        if (ss.isClosed) break
                    }
                }
            }
            acceptThread.isDaemon = true
            acceptThread.name = "AltHub-StreamServer"
            acceptThread.start()
            return port
        }
    }

    fun getStreamUrl(item: RemoteMediaItem): String {
        itemsById[item.id] = item
        val p = ensureStarted()
        return "http://127.0.0.1:$p/stream?id=${item.id}"
    }

    suspend fun preparePlayableUri(context: Context, item: RemoteMediaItem): Uri =
        withContext(Dispatchers.IO) {
            initCacheDir(context)
            val localPath = item.localPath
            if (!localPath.isNullOrBlank()) {
                val localFile = File(localPath)
                if (!localFile.exists() || !localFile.isFile) {
                    throw FileNotFoundException("File lokal tidak ditemukan: ${item.fileName}")
                }
                val isSvcContainer = FileInputStream(localFile).use { fis ->
                    val magic = ByteArray(4)
                    val n = fis.read(magic)
                    n == 4 && String(magic, Charsets.US_ASCII) == "SVCV"
                }
                if (!isSvcContainer) {
                    return@withContext Uri.fromFile(localFile)
                }

                val headerJson = NativeCore.readHeader(localFile.absolutePath)
                    ?: throw IOException("Header container .vcv tidak valid")
                val obj = JSONObject(headerJson)
                val origExt = obj.optString("original_ext", "mp4").ifBlank { "mp4" }
                val cacheKey = sha256("${localFile.absolutePath}_${localFile.lastModified()}_${localFile.length()}")
                val outFile = File(getStreamCacheDir(), "local_$cacheKey.$origExt")
                if (!outFile.exists() || outFile.length() == 0L) {
                    val tempOut = File(getStreamCacheDir(), "tmp_$cacheKey.$origExt")
                    if (tempOut.exists()) tempOut.delete()
                    val code = NativeCore.decodeToFile(localFile.absolutePath, tempOut.absolutePath)
                    if (code != 0 || !tempOut.exists() || tempOut.length() == 0L) {
                        tempOut.delete()
                        throw IOException("Gagal mengekstrak container .vcv (kode $code)")
                    }
                    if (!tempOut.renameTo(outFile)) {
                        tempOut.copyTo(outFile, overwrite = true)
                        tempOut.delete()
                    }
                }
                return@withContext Uri.fromFile(outFile)
            }

            Uri.parse(getStreamUrl(item))
        }

    fun parseContainerHeaderFromStream(input: InputStream): ContainerHeaderInfo {
        val prefix = readExactBytes(input, 8)
        val headerLen = ByteBuffer.wrap(prefix, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerLen <= 0 || headerLen > 1024 * 1024) {
            throw IOException("Invalid container header length")
        }
        val jsonBytes = readExactBytes(input, headerLen)
        val fullHeader = ByteArray(8 + headerLen)
        System.arraycopy(prefix, 0, fullHeader, 0, 8)
        System.arraycopy(jsonBytes, 0, fullHeader, 8, headerLen)

        val parsedJson = NativeCore.parseContainerHeaderBytes(fullHeader, fullHeader.size)
            ?: throw IOException("Native container header validation failed")
        val obj = JSONObject(parsedJson)
        return ContainerHeaderInfo(
            isContainer = true,
            headerOffset = obj.optLong("header_offset", (8 + headerLen).toLong()),
            compression = obj.optInt("compression", 0),
            originalExt = obj.optString("original_ext", "mp4").ifBlank { "mp4" },
            originalSize = obj.optLong("original_size", -1L)
        )
    }

    private fun probeContentLengthViaHead(item: RemoteMediaItem): Long {
        return runCatching {
            val conn = openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = null,
                extraHeaders = item.headers,
                method = "HEAD"
            )
            try {
                conn.contentLengthLong
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1L)
    }

    private fun resolveHeader(item: RemoteMediaItem): ContainerHeaderInfo {
        headersById[item.id]?.let { return it }
        val conn = try {
            openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = "bytes=0-8191",
                extraHeaders = item.headers
            )
        } catch (_: Throwable) {
            openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = null,
                extraHeaders = item.headers
            )
        }
        try {
            val contentRange = conn.getHeaderField("Content-Range")
            val totalFromRange = contentRange
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.takeIf { it != "*" }
                ?.toLongOrNull() ?: -1L
            var fallbackTotal = when {
                totalFromRange > 0L -> totalFromRange
                conn.responseCode == 200 && conn.contentLengthLong > 0L -> conn.contentLengthLong
                item.sizeBytes > 0L -> item.sizeBytes
                else -> -1L
            }
            if (fallbackTotal <= 0L) {
                val headLen = probeContentLengthViaHead(item)
                if (headLen > 0L) fallbackTotal = headLen
            }

            val ctExt = extFromMimeType(conn.contentType.orEmpty())
            val extFromUrl = item.fileName.substringAfterLast('.', "").lowercase().let {
                when {
                    it == "vcv" || it == "icv" -> "mp4"
                    it.isBlank() || it.length > 6 -> ctExt ?: "mp4"
                    else -> it
                }
            }

            BufferedInputStream(conn.inputStream, 16_384).use { bis ->
                bis.mark(16)
                val magic = ByteArray(4)
                val n = bis.read(magic)
                val mStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
                bis.reset()

                val info = if (mStr == "SVCV" || mStr == "SICV") {
                    val parsed = parseContainerHeaderFromStream(bis)
                    if (parsed.compression > 0 && fallbackTotal > parsed.headerOffset + 16L) {
                        val table = fetchAndParseSeekTable(item, fallbackTotal, parsed.headerOffset)
                        if (table != null) {
                            seekTablesById[item.id] = table
                            parsed.copy(originalSize = table.totalDecompressedSize)
                        } else {
                            parsed
                        }
                    } else if (parsed.originalSize <= 0L && parsed.compression == 0 && fallbackTotal > parsed.headerOffset) {
                        parsed.copy(originalSize = fallbackTotal - parsed.headerOffset)
                    } else {
                        parsed
                    }
                } else {
                    ContainerHeaderInfo(
                        isContainer = false,
                        headerOffset = 0L,
                        compression = 0,
                        originalExt = extFromUrl,
                        originalSize = fallbackTotal
                    )
                }
                if (info.isContainer || info.originalSize > 0L) {
                    headersById[item.id] = info
                }
                return info
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            var headersSent = false
            try {
                client.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var rangeValue: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeValue = line.substringAfter(':').trim()
                    }
                }

                val id = target.substringAfter("id=", "").substringBefore('&')
                val item = itemsById[id]
                val out = BufferedOutputStream(client.getOutputStream(), 65_536)
                if (item == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val headerInfo = resolveHeader(item)
                val mime = mimeTypeForExt(headerInfo.originalExt)
                val seekTable = seekTablesById[item.id]

                if (seekTable != null) {
                    serveFromSeekTable(item, seekTable, mime, method, rangeValue, out) {
                        headersSent = true
                    }
                } else if (headerInfo.isContainer && headerInfo.compression > 0) {
                    serveFromProgressiveSpool(item, headerInfo, mime, method, rangeValue, out) {
                        headersSent = true
                    }
                } else {
                    serveDirectRangeStream(item, headerInfo, mime, method, rangeValue, out) {
                        headersSent = true
                    }
                }
            } catch (_: Throwable) {
                if (!headersSent) {
                    runCatching {
                        val errOut = client.getOutputStream()
                        errOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        errOut.flush()
                    }
                }
            }
        }
    }

    private fun parseRangeHeader(rangeHeader: String?, totalSize: Long): Pair<Long, Long>? {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=", ignoreCase = true)) return null
        val spec = rangeHeader.substringAfter('=').substringBefore(',').trim()
        val dashIdx = spec.indexOf('-')
        if (dashIdx < 0) return null
        val startStr = spec.substring(0, dashIdx).trim()
        val endStr = spec.substring(dashIdx + 1).trim()
        if (startStr.isEmpty()) {
            val suffixLen = endStr.toLongOrNull() ?: return null
            if (totalSize <= 0L) return null
            val start = (totalSize - suffixLen).coerceAtLeast(0L)
            return start to (totalSize - 1L)
        }
        val start = startStr.toLongOrNull() ?: return null
        val end = if (endStr.isNotEmpty()) {
            endStr.toLongOrNull() ?: if (totalSize > 0L) totalSize - 1L else -1L
        } else if (totalSize > 0L) {
            totalSize - 1L
        } else {
            -1L
        }
        return start to end
    }

    private fun serveDirectRangeStream(
        item: RemoteMediaItem,
        info: ContainerHeaderInfo,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream,
        onHeadersSent: () -> Unit
    ) {
        val totalSize = info.originalSize
        val parsedRange = parseRangeHeader(rangeHeader, totalSize)
        var start = (parsedRange?.first ?: 0L).coerceAtLeast(0L)
        var end = parsedRange?.second ?: if (totalSize > 0L) totalSize - 1L else -1L
        val isPartial = parsedRange != null

        if (totalSize > 0L) {
            start = start.coerceIn(0L, totalSize - 1L)
            end = if (end < start) totalSize - 1L else end.coerceAtMost(totalSize - 1L)
        }

        val remoteStart = info.headerOffset + start
        val remoteRange = when {
            end >= start -> "bytes=$remoteStart-${info.headerOffset + end}"
            isPartial || info.headerOffset > 0L -> "bytes=$remoteStart-"
            else -> null
        }

        val conn = try {
            openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = remoteRange,
                extraHeaders = item.headers
            )
        } catch (_: Throwable) {
            openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = null,
                extraHeaders = item.headers
            )
        }

        try {
            val respCode = conn.responseCode
            val upstreamLen = conn.contentLengthLong
            val effectiveTotal = when {
                totalSize > 0L -> totalSize
                respCode == 200 && upstreamLen > info.headerOffset -> upstreamLen - info.headerOffset
                else -> conn.getHeaderField("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.trim()
                    ?.takeIf { it != "*" }
                    ?.toLongOrNull()
                    ?.let { if (it > info.headerOffset) it - info.headerOffset else -1L } ?: -1L
            }

            if (effectiveTotal > 0L && info.originalSize <= 0L) {
                headersById[item.id] = info.copy(originalSize = effectiveTotal)
            }

            val resolvedEnd = when {
                end >= start -> {
                    if (effectiveTotal > 0L) end.coerceAtMost(effectiveTotal - 1L) else end
                }
                effectiveTotal > 0L -> effectiveTotal - 1L
                respCode == 206 && upstreamLen > 0L -> start + upstreamLen - 1L
                else -> -1L
            }

            val contentLength = when {
                resolvedEnd >= start -> resolvedEnd - start + 1L
                respCode == 206 && upstreamLen > 0L -> upstreamLen
                respCode == 200 && upstreamLen > remoteStart -> upstreamLen - remoteStart
                else -> -1L
            }

            val sb = StringBuilder()
            if (isPartial) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                val rangeEndStr = when {
                    resolvedEnd >= start -> resolvedEnd.toString()
                    contentLength > 0L -> (start + contentLength - 1L).toString()
                    else -> start.toString()
                }
                val totalStr = if (effectiveTotal > 0L) effectiveTotal.toString() else "*"
                sb.append("Content-Range: bytes $start-$rangeEndStr/$totalStr\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
            }
            sb.append("Content-Type: $mime\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            if (contentLength >= 0L) {
                sb.append("Content-Length: $contentLength\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            onHeadersSent()

            if (method == "HEAD") return

            conn.inputStream.use { input ->
                if (respCode == 200 && remoteStart > 0L) {
                    skipFully(input, remoteStart)
                }
                val buf = ByteArray(65_536)
                var remaining = if (contentLength >= 0L) contentLength else Long.MAX_VALUE
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (contentLength >= 0L) remaining -= n.toLong()
                }
                out.flush()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getOrStartSpoolJob(item: RemoteMediaItem, info: ContainerHeaderInfo): SpoolJob {
        val existing = spoolJobs[item.id]
        if (existing != null && existing.error.get() == null && (existing.completed.get() || existing.file.exists())) {
            return existing
        }
        synchronized(spoolJobs) {
            val recheck = spoolJobs[item.id]
            if (recheck != null && recheck.error.get() == null && (recheck.completed.get() || recheck.file.exists())) {
                return recheck
            }
            val cacheFile = File(getStreamCacheDir(), "remote_${sha256(item.id)}.${info.originalExt}")
            if (cacheFile.exists()) cacheFile.delete()
            val job = SpoolJob(
                file = cacheFile,
                totalSize = AtomicLong(if (info.originalSize > 0L) info.originalSize else -1L)
            )
            spoolJobs[item.id] = job
            spoolExecutor.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = openRemoteConnection(
                        startUrl = item.mediaUrl,
                        rangeHeader = if (info.headerOffset > 0L) "bytes=${info.headerOffset}-" else null,
                        extraHeaders = item.headers
                    )
                    val respCode = conn.responseCode
                    val rawIn = BufferedInputStream(conn.inputStream, 65_536)
                    if (respCode == 200 && info.headerOffset > 0L) {
                        skipFully(rawIn, info.headerOffset)
                    }
                    val decodedStream: InputStream = if (info.isContainer && info.compression > 0) {
                        ZstdDecompressInputStream(rawIn)
                    } else {
                        rawIn
                    }
                    decodedStream.use { input ->
                        FileOutputStream(cacheFile).use { fos ->
                            val buf = ByteArray(65_536)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                fos.flush()
                                total += n.toLong()
                                job.bytesWritten.set(total)
                            }
                            job.totalSize.set(total)
                        }
                    }
                    job.completed.set(true)
                } catch (t: Throwable) {
                    job.error.set(t)
                    job.completed.set(true)
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }
            return job
        }
    }

    private fun serveFromProgressiveSpool(
        item: RemoteMediaItem,
        info: ContainerHeaderInfo,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream,
        onHeadersSent: () -> Unit
    ) {
        val job = getOrStartSpoolJob(item, info)

        while (job.bytesWritten.get() == 0L && !job.completed.get() && job.error.get() == null) {
            Thread.sleep(25L)
        }
        job.error.get()?.let { throw IOException("Spool failed: ${it.message}", it) }

        val knownTotal = job.totalSize.get()
        val parsedRange = parseRangeHeader(rangeHeader, knownTotal)
        val isPartial = parsedRange != null
        val start = (parsedRange?.first ?: 0L).coerceAtLeast(0L)

        while (job.bytesWritten.get() <= start && !job.completed.get() && job.error.get() == null) {
            Thread.sleep(25L)
        }
        job.error.get()?.let { throw IOException("Spool failed: ${it.message}", it) }

        val finalTotal = job.totalSize.get()
        val effectiveTotal = when {
            finalTotal > 0L -> finalTotal
            job.completed.get() -> job.bytesWritten.get()
            else -> -1L
        }

        val end = when {
            parsedRange != null && parsedRange.second >= start -> {
                if (effectiveTotal > 0L) parsedRange.second.coerceAtMost(effectiveTotal - 1L) else parsedRange.second
            }
            effectiveTotal > 0L -> effectiveTotal - 1L
            else -> -1L
        }

        val sb = StringBuilder()
        val effectiveEnd = if (isPartial) {
            if (end >= start) end else (job.bytesWritten.get() - 1L).coerceAtLeast(start)
        } else {
            end
        }

        if (isPartial) {
            val totalStr = if (effectiveTotal > 0L) effectiveTotal.toString() else "*"
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes $start-$effectiveEnd/$totalStr\r\n")
            sb.append("Content-Length: ${effectiveEnd - start + 1L}\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
            if (effectiveTotal > 0L) {
                sb.append("Content-Length: $effectiveTotal\r\n")
            }
        }
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        onHeadersSent()

        if (method == "HEAD") return

        RandomAccessFile(job.file, "r").use { raf ->
            var cursor = start
            val targetEnd = if (isPartial) effectiveEnd else if (end >= start) end else Long.MAX_VALUE
            val buf = ByteArray(65_536)
            while (cursor <= targetEnd) {
                val avail = job.bytesWritten.get()
                if (cursor >= avail) {
                    if (job.completed.get()) break
                    Thread.sleep(25L)
                    continue
                }
                val canRead = minOf(buf.size.toLong(), minOf(avail - cursor, targetEnd - cursor + 1L)).toInt()
                if (canRead <= 0) break
                raf.seek(cursor)
                val n = raf.read(buf, 0, canRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                cursor += n.toLong()
            }
            out.flush()
        }
    }

    private fun fetchAndParseSeekTable(
        item: RemoteMediaItem,
        remoteFileSize: Long,
        headerOffset: Long
    ): VcvSeekTable? {
        return runCatching {
            val tailWindow = minOf(remoteFileSize - headerOffset, 65_536L)
            if (tailWindow < 16L) return@runCatching null
            val tailStart = remoteFileSize - tailWindow
            val conn = openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = "bytes=$tailStart-${remoteFileSize - 1L}",
                extraHeaders = item.headers
            )
            try {
                val tailBytes = conn.inputStream.use { it.readBytes() }
                val json = NativeCore.parseVcvSeekTable(
                    tailBytes,
                    tailBytes.size,
                    remoteFileSize,
                    headerOffset
                ) ?: return@runCatching null
                val obj = JSONObject(json)
                val cSize = obj.optInt("chunk_size", 262_144)
                val totalDecomp = obj.optLong("total_decompressed_size", -1L)
                val arr = obj.optJSONArray("chunks") ?: return@runCatching null
                val list = ArrayList<SeekChunk>(arr.length())
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    list.add(
                        SeekChunk(
                            index = c.optInt("index", i),
                            compOffset = c.optLong("comp_offset"),
                            compSize = c.optInt("comp_size"),
                            decompOffset = c.optLong("decomp_offset"),
                            decompSize = c.optInt("decomp_size")
                        )
                    )
                }
                if (list.isEmpty() || totalDecomp <= 0L) null else VcvSeekTable(cSize, totalDecomp, list)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun serveFromSeekTable(
        item: RemoteMediaItem,
        table: VcvSeekTable,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream,
        onHeadersSent: () -> Unit
    ) {
        val totalSize = table.totalDecompressedSize
        val parsedRange = parseRangeHeader(rangeHeader, totalSize)
        val isPartial = parsedRange != null
        val start = (parsedRange?.first ?: 0L).coerceIn(0L, totalSize - 1L)
        val requestedEnd = parsedRange?.second ?: (totalSize - 1L)
        val maxWindow = 2L * 1024L * 1024L
        val end = if (requestedEnd < start) {
            minOf(totalSize - 1L, start + maxWindow - 1L)
        } else {
            minOf(totalSize - 1L, minOf(requestedEnd, start + maxWindow - 1L))
        }

        val chunkSize = table.chunkSize.toLong().coerceAtLeast(1L)
        val startIdx = (start / chunkSize).toInt().coerceIn(0, table.chunks.lastIndex)
        val endIdx = (end / chunkSize).toInt().coerceIn(startIdx, table.chunks.lastIndex)

        val sb = StringBuilder()
        if (isPartial) {
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes $start-$end/$totalSize\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
        }
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: ${end - start + 1L}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        onHeadersSent()

        if (method == "HEAD") return

        var firstUncached = -1
        var lastUncached = -1
        for (idx in startIdx..endIdx) {
            val key = "${item.id}_$idx"
            if (!decompressedChunkCache.containsKey(key)) {
                if (firstUncached == -1) firstUncached = idx
                lastUncached = idx
            }
        }

        if (firstUncached != -1) {
            val cFirst = table.chunks[firstUncached]
            val cLast = table.chunks[lastUncached]
            val fetchStart = cFirst.compOffset
            val fetchEnd = cLast.compOffset + cLast.compSize.toLong() - 1L
            val conn = openRemoteConnection(
                startUrl = item.mediaUrl,
                rangeHeader = "bytes=$fetchStart-$fetchEnd",
                extraHeaders = item.headers
            )
            try {
                BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                    var streamCursor = fetchStart
                    for (idx in firstUncached..lastUncached) {
                        val chunk = table.chunks[idx]
                        if (chunk.compOffset > streamCursor) {
                            skipFully(bis, chunk.compOffset - streamCursor)
                            streamCursor = chunk.compOffset
                        }
                        val compBuf = readExactBytes(bis, chunk.compSize)
                        streamCursor += chunk.compSize.toLong()
                        val decomp = NativeCore.decompressZstdBlock(compBuf, compBuf.size, chunk.decompSize)
                            ?: throw IOException("Failed to decompress chunk $idx")
                        if (decompressedChunkCache.size > 64) {
                            decompressedChunkCache.clear()
                        }
                        decompressedChunkCache["${item.id}_$idx"] = decomp
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        for (idx in startIdx..endIdx) {
            val chunk = table.chunks[idx]
            val data = decompressedChunkCache["${item.id}_$idx"]
                ?: throw IOException("Missing decoded chunk $idx")
            val sliceStart = maxOf(0L, start - chunk.decompOffset).toInt()
            val sliceEndExclusive = minOf(data.size.toLong(), end - chunk.decompOffset + 1L).toInt()
            if (sliceEndExclusive > sliceStart) {
                out.write(data, sliceStart, sliceEndExclusive - sliceStart)
            }
        }
        out.flush()
    }

    private fun resolveDriveVirusConfirmUrl(html: String, baseUrl: String): String? {
        val actionMatch = Regex("<form[^>]+id=[\"\']download-form[\"\'][^>]+action=[\"\']([^\"\']+)[\"\']", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("<form[^>]+action=[\"\']([^\"\']*usercontent\\.google\\.com[^\"\']*)[\"\']", RegexOption.IGNORE_CASE)
                .find(html)
            ?: return null
        val rawAction = actionMatch.groupValues[1].replace("&amp;", "&")
        val actionUrl = runCatching { URL(URL(baseUrl), rawAction).toString() }.getOrNull() ?: return null
        val params = mutableListOf<String>()
        val inputRegex = Regex("<input[^>]+type=[\"\']hidden[\"\'][^>]*>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("name=[\"\']([^\"\']+)[\"\']", RegexOption.IGNORE_CASE)
        val valRegex = Regex("value=[\"\']([^\"\']*)[\"\']", RegexOption.IGNORE_CASE)
        for (tag in inputRegex.findAll(html)) {
            val name = nameRegex.find(tag.value)?.groupValues?.get(1) ?: continue
            val value = valRegex.find(tag.value)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
            params.add("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}")
        }
        if (params.isEmpty()) return null
        val sep = if (actionUrl.contains('?')) "&" else "?"
        return actionUrl + sep + params.joinToString("&")
    }

    private fun extractCookies(conn: HttpURLConnection, existingCookie: String?): String? {
        val setCookies = conn.headerFields["Set-Cookie"] ?: return existingCookie
        val cookieMap = linkedMapOf<String, String>()
        existingCookie?.split(';')?.forEach { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cookieMap[kv[0].trim()] = kv[1].trim()
            }
        }
        for (raw in setCookies) {
            val firstPair = raw.substringBefore(';').trim()
            val kv = firstPair.split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cookieMap[kv[0].trim()] = kv[1].trim()
            }
        }
        return if (cookieMap.isEmpty()) null else cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun openRemoteConnection(
        startUrl: String,
        rangeHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        method: String = "GET",
        cookieHeader: String? = null,
        hops: Int = 0
    ): HttpURLConnection {
        if (hops > 8) throw IOException("Too many redirects")
        val url = URL(startUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 35_000
            setRequestProperty("User-Agent", NativeCore.getUserAgent())
            setRequestProperty("Accept-Encoding", "identity")
            if (!cookieHeader.isNullOrBlank()) {
                setRequestProperty("Cookie", cookieHeader)
            }
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (rangeHeader != null && method != "HEAD") {
                setRequestProperty("Range", rangeHeader)
            }
        }
        val code = conn.responseCode
        val updatedCookies = extractCookies(conn, cookieHeader)
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: throw IOException("Missing redirect location")
            val nextUrl = URL(url, location).toString()
            conn.disconnect()
            return openRemoteConnection(nextUrl, rangeHeader, extraHeaders, method, updatedCookies, hops + 1)
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        if (method != "HEAD") {
            val ct = conn.contentType.orEmpty().lowercase()
            if (ct.contains("text/html") && (startUrl.contains("google.com") || startUrl.contains("googleusercontent.com"))) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val confirmedUrl = resolveDriveVirusConfirmUrl(html, startUrl)
                    ?: throw IOException("Google Drive HTML interstitial without confirm form")
                return openRemoteConnection(confirmedUrl, rangeHeader, extraHeaders, method, updatedCookies, hops + 1)
            }
        }
        return conn
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n <= 0) throw EOFException("Unexpected EOF")
            offset += n
        }
        return buf
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(16_384)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val n = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n.toLong()
            }
        }
    }

    private fun extFromMimeType(contentType: String): String? {
        val ct = contentType.substringBefore(';').trim().lowercase()
        return when (ct) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "video/3gpp" -> "3gp"
            "video/mp2t" -> "ts"
            "video/x-flv" -> "flv"
            "video/x-ms-wmv" -> "wmv"
            "video/ogg" -> "ogv"
            "video/mpeg" -> "mpeg"
            else -> null
        }
    }

    private fun mimeTypeForExt(ext: String): String {
        return when (ext.lowercase().trimStart('.')) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "ts", "mts", "m2ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ogv" -> "video/ogg"
            "mpeg", "mpg" -> "video/mpeg"
            else -> "video/mp4"
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

class ZstdDecompressInputStream(private val upstream: InputStream) : InputStream() {
    private var handle: Long = NativeCore.zstdStreamCreate()
    private val inBuf = ByteArray(32_768)
    private var outBuf = ByteArray(0)
    private var outPos = 0
    private var eof = false

    private fun fill(): Boolean {
        if (eof || handle == 0L) return false
        while (outPos >= outBuf.size) {
            val n = upstream.read(inBuf)
            if (n <= 0) {
                eof = true
                return false
            }
            val decoded = NativeCore.zstdStreamDecompress(handle, inBuf, n)
                ?: throw IOException("Zstd stream decompression failed")
            if (decoded.isNotEmpty()) {
                outBuf = decoded
                outPos = 0
                return true
            }
        }
        return true
    }

    override fun read(): Int {
        if (outPos >= outBuf.size && !fill()) return -1
        return outBuf[outPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (outPos >= outBuf.size && !fill()) return -1
        val count = minOf(len, outBuf.size - outPos)
        System.arraycopy(outBuf, outPos, b, off, count)
        outPos += count
        return count
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            NativeCore.zstdStreamFree(h)
        }
        runCatching { upstream.close() }
    }
}