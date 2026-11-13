package com.althub.alteriza

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object LocalStreamServer {
    private val itemsById = ConcurrentHashMap<String, RemoteMediaItem>()
    private val headersById = ConcurrentHashMap<String, ContainerHeaderInfo>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    private fun ensureStarted(): Int {
        if (port > 0 && serverSocket?.isClosed == false) return port
        synchronized(this) {
            if (port > 0 && serverSocket?.isClosed == false) return port
            val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
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
            headerOffset = obj.optLong("header_offset", (8 + headerLen).toLong()),
            compression = obj.optInt("compression", 0),
            originalExt = obj.optString("original_ext", "mp4").ifBlank { "mp4" },
            originalSize = obj.optLong("original_size", -1L)
        )
    }

    private fun resolveHeader(item: RemoteMediaItem): ContainerHeaderInfo {
        headersById[item.id]?.let { return it }
        val conn = openRemoteConnection(item.mediaUrl, rangeHeader = "bytes=0-8191")
        try {
            BufferedInputStream(conn.inputStream, 16_384).use { bis ->
                val info = parseContainerHeaderFromStream(bis)
                headersById[item.id] = info
                return info
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
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

                if (headerInfo.compression == 0) {
                    serveUncompressedRange(item, headerInfo, mime, method, rangeValue, out)
                } else {
                    serveCompressedStream(item, headerInfo, mime, method, rangeValue, out)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun serveUncompressedRange(
        item: RemoteMediaItem,
        info: ContainerHeaderInfo,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val totalSize = info.originalSize
        var start = 0L
        var end = if (totalSize > 0L) totalSize - 1L else -1L
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val spec = rangeHeader.substringAfter('=').trim()
            val dashIdx = spec.indexOf('-')
            if (dashIdx >= 0) {
                val startStr = spec.substring(0, dashIdx).trim()
                val endStr = spec.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) {
                    start = startStr.toLongOrNull() ?: 0L
                    end = if (endStr.isNotEmpty()) {
                        endStr.toLongOrNull() ?: end
                    } else if (totalSize > 0L) {
                        totalSize - 1L
                    } else {
                        -1L
                    }
                    isPartial = true
                }
            }
        }

        if (totalSize > 0L) {
            start = start.coerceIn(0L, totalSize - 1L)
            end = if (end < start) totalSize - 1L else end.coerceAtMost(totalSize - 1L)
        }

        val remoteStart = info.headerOffset + start
        val remoteRange = if (end >= start) {
            "bytes=$remoteStart-${info.headerOffset + end}"
        } else {
            "bytes=$remoteStart-"
        }

        val conn = openRemoteConnection(item.mediaUrl, rangeHeader = remoteRange)
        try {
            val respCode = conn.responseCode
            val contentLength = if (end >= start) (end - start + 1L) else conn.contentLengthLong
            val sb = StringBuilder()
            if (isPartial && respCode == 206 && totalSize > 0L && end >= start) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                sb.append("Content-Range: bytes $start-$end/$totalSize\r\n")
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

    private fun serveCompressedStream(
        item: RemoteMediaItem,
        info: ContainerHeaderInfo,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream
    ) {
        var skipBytes = 0L
        var isPartial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val startStr = rangeHeader.substringAfter('=').substringBefore('-').trim()
            val reqStart = startStr.toLongOrNull() ?: 0L
            if (reqStart > 0L) {
                skipBytes = reqStart
                isPartial = true
            }
        }

        val totalSize = info.originalSize
        val sb = StringBuilder()
        if (isPartial && totalSize > skipBytes) {
            val end = totalSize - 1L
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes $skipBytes-$end/$totalSize\r\n")
            sb.append("Content-Length: ${totalSize - skipBytes}\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
            if (totalSize > 0L) {
                sb.append("Content-Length: $totalSize\r\n")
            }
        }
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        if (method == "HEAD") return

        val conn = openRemoteConnection(item.mediaUrl, rangeHeader = "bytes=${info.headerOffset}-")
        try {
            val respCode = conn.responseCode
            val rawIn = BufferedInputStream(conn.inputStream, 65_536)
            if (respCode == 200) {
                skipFully(rawIn, info.headerOffset)
            }
            ZstdDecompressInputStream(rawIn).use { zstdIn ->
                if (skipBytes > 0L) {
                    skipFully(zstdIn, skipBytes)
                }
                val buf = ByteArray(65_536)
                while (true) {
                    val n = zstdIn.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun openRemoteConnection(
        startUrl: String,
        rangeHeader: String? = null,
        hops: Int = 0
    ): HttpURLConnection {
        if (hops > 6) throw IOException("Too many redirects")
        val url = URL(startUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", NativeCore.getUserAgent())
            if (rangeHeader != null) {
                setRequestProperty("Range", rangeHeader)
            }
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: throw IOException("Missing redirect location")
            val nextUrl = URL(url, location).toString()
            conn.disconnect()
            return openRemoteConnection(nextUrl, rangeHeader, hops + 1)
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
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

    private fun mimeTypeForExt(ext: String): String {
        return when (ext.lowercase().trimStart('.')) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
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
