package com.althub.alteriza

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class ContainerHeaderInfo(
    val magic: String,
    val format: String,
    val compression: Int,
    val originalExt: String,
    val originalSize: Long,
    val chunkSize: Int,
    val payloadOffset: Long
)

private data class ResolvedYtStream(
    val streamUrl: String,
    val userAgent: String,
    val expiresAtMs: Long
)

object LocalStreamServer {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val YT_ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private val executor = Executors.newCachedThreadPool()
    private val registeredItems = ConcurrentHashMap<String, RemoteMediaItem>()
    private val youtubeStreamCache = ConcurrentHashMap<String, ResolvedYtStream>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && boundPort > 0) {
            return boundPort
        }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        boundPort = socket.localPort
        executor.execute { acceptLoop(socket) }
        return boundPort
    }

    fun registerMedia(item: RemoteMediaItem): String {
        val port = ensureStarted()
        val token = UUID.nameUUIDFromBytes("${item.id}|${item.mediaUrl}".toByteArray()).toString()
        registeredItems[token] = item
        val safeName = Uri.encode(item.fileName.ifBlank { "stream.mp4" })
        return "http://127.0.0.1:$port/stream/$token/$safeName"
    }

    fun getRegisteredItem(token: String): RemoteMediaItem? = registeredItems[token]

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
            } catch (_: Throwable) {
                if (socket.isClosed) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream(), 16_384)
            val output = BufferedOutputStream(sock.getOutputStream(), 65_536)

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            val reqHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    reqHeaders[key] = value
                }
            }

            if (method != "GET" && method != "HEAD") {
                sendSimpleStatus(output, 405, "Method Not Allowed")
                return
            }

            val pathOnly = rawPath.substringBefore('?')
            val segments = pathOnly.trim('/').split('/')
            if (segments.size < 2 || segments[0] != "stream") {
                sendSimpleStatus(output, 404, "Not Found")
                return
            }

            val token = segments[1]
            val item = registeredItems[token]
            if (item == null) {
                sendSimpleStatus(output, 404, "Stream Token Expired")
                return
            }

            try {
                serveMediaItem(
                    item = item,
                    rangeHeader = reqHeaders["range"],
                    headOnly = method == "HEAD",
                    output = output
                )
            } catch (_: Throwable) {
                runCatching { sendSimpleStatus(output, 502, "Upstream Stream Error") }
            }
        }
    }

    private fun serveMediaItem(
        item: RemoteMediaItem,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val probeConn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = "bytes=0-1048575"
        )

        val isContainer: Boolean
        val headerInfo: ContainerHeaderInfo?
        val upstreamTotalLength: Long
        val upstreamContentType: String

        try {
            val code = probeConn.responseCode
            upstreamContentType = probeConn.contentType ?: guessMimeType(item.fileName, item.kind)
            upstreamTotalLength = parseTotalSizeFromResponse(probeConn, code)

            BufferedInputStream(probeConn.inputStream, 65_536).use { bis ->
                bis.mark(16)
                val magic = ByteArray(4)
                val n = bis.read(magic)
                val magicStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
                bis.reset()

                if (magicStr == "SVCV" || magicStr == "SICV") {
                    isContainer = true
                    headerInfo = parseContainerHeaderFromStream(bis)
                } else {
                    isContainer = false
                    headerInfo = null
                }
            }
        } finally {
            probeConn.disconnect()
        }

        if (!isContainer || headerInfo == null) {
            proxyDirectRange(
                item = item,
                rangeHeader = rangeHeader,
                fallbackTotalSize = upstreamTotalLength,
                fallbackMime = upstreamContentType,
                headOnly = headOnly,
                output = output
            )
            return
        }

        val targetMime = guessMimeType("file.${headerInfo.originalExt}", item.kind)
        if (headerInfo.compression == 0) {
            proxyUncompressedContainer(
                item = item,
                headerInfo = headerInfo,
                upstreamTotalSize = upstreamTotalLength,
                rangeHeader = rangeHeader,
                mimeType = targetMime,
                headOnly = headOnly,
                output = output
            )
            return
        }

        if (headerInfo.chunkSize > 0 && headerInfo.originalSize > 0 && upstreamTotalLength > 32L) {
            serveChunkedCompressedVcv(
                item = item,
                headerInfo = headerInfo,
                upstreamTotalSize = upstreamTotalLength,
                rangeHeader = rangeHeader,
                mimeType = targetMime,
                headOnly = headOnly,
                output = output
            )
        } else {
            serveSingleStreamCompressed(
                item = item,
                headerInfo = headerInfo,
                mimeType = targetMime,
                headOnly = headOnly,
                output = output
            )
        }
    }

    private fun proxyDirectRange(
        item: RemoteMediaItem,
        rangeHeader: String?,
        fallbackTotalSize: Long,
        fallbackMime: String,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val conn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = rangeHeader
        )
        try {
            val code = conn.responseCode
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val contentRange = conn.getHeaderField("Content-Range")
            val mime = conn.contentType ?: fallbackMime

            val sb = StringBuilder()
            if (code == 206 && !contentRange.isNullOrBlank()) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                sb.append("Content-Range: ").append(contentRange).append("\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
            }
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Type: ").append(mime).append("\r\n")
            if (contentLength >= 0L) {
                sb.append("Content-Length: ").append(contentLength).append("\r\n")
            } else if (fallbackTotalSize > 0L && rangeHeader == null) {
                sb.append("Content-Length: ").append(fallbackTotalSize).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            output.write(sb.toString().toByteArray(Charsets.US_ASCII))

            if (!headOnly) {
                conn.inputStream.use { upstreamIn ->
                    copyStream(upstreamIn, output)
                }
            }
            output.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun proxyUncompressedContainer(
        item: RemoteMediaItem,
        headerInfo: ContainerHeaderInfo,
        upstreamTotalSize: Long,
        rangeHeader: String?,
        mimeType: String,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val rawPayloadSize = if (headerInfo.originalSize > 0L) {
            headerInfo.originalSize
        } else {
            (upstreamTotalSize - headerInfo.payloadOffset).coerceAtLeast(0L)
        }

        val (start, end, isPartial) = parseByteRange(rangeHeader, rawPayloadSize)
        val lengthToServe = if (rawPayloadSize > 0L) (end - start + 1L) else -1L
        val upstreamStart = headerInfo.payloadOffset + start
        val upstreamRange = if (rawPayloadSize > 0L) {
            "bytes=$upstreamStart-${headerInfo.payloadOffset + end}"
        } else {
            "bytes=$upstreamStart-"
        }

        val conn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = upstreamRange
        )
        try {
            val sb = StringBuilder()
            if (isPartial && rawPayloadSize > 0L) {
                sb.append("HTTP/1.1 206 Partial Content\r\n")
                sb.append("Content-Range: bytes $start-$end/$rawPayloadSize\r\n")
            } else {
                sb.append("HTTP/1.1 200 OK\r\n")
            }
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Type: ").append(mimeType).append("\r\n")
            if (lengthToServe >= 0L) {
                sb.append("Content-Length: ").append(lengthToServe).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            output.write(sb.toString().toByteArray(Charsets.US_ASCII))

            if (!headOnly) {
                conn.inputStream.use { upstreamIn ->
                    if (conn.responseCode == 200 && upstreamStart > 0L) {
                        skipFully(upstreamIn, upstreamStart)
                    }
                    if (lengthToServe > 0L) {
                        copyExactBytes(upstreamIn, output, lengthToServe)
                    } else {
                        copyStream(upstreamIn, output)
                    }
                }
            }
            output.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun serveChunkedCompressedVcv(
        item: RemoteMediaItem,
        headerInfo: ContainerHeaderInfo,
        upstreamTotalSize: Long,
        rangeHeader: String?,
        mimeType: String,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val totalDecompressed = headerInfo.originalSize
        val (startByte, endByte, isPartial) = parseByteRange(rangeHeader, totalDecompressed)
        val serveLength = endByte - startByte + 1L

        val footerProbeSize = minOf(upstreamTotalSize, 262_144L)
        val footerStart = (upstreamTotalSize - footerProbeSize).coerceAtLeast(0L)
        val footerBytes = fetchByteRange(
            item = item,
            rangeStart = footerStart,
            rangeEnd = upstreamTotalSize - 1L
        )

        val seekEntries = parseSeekTableFromFooter(footerBytes)
        if (seekEntries.isEmpty()) {
            serveSingleStreamCompressed(item, headerInfo, mimeType, headOnly, output)
            return
        }

        val startChunkIdx = (startByte / headerInfo.chunkSize).toInt()
            .coerceIn(0, seekEntries.lastIndex)
        val endChunkIdx = (endByte / headerInfo.chunkSize).toInt()
            .coerceIn(startChunkIdx, seekEntries.lastIndex)

        var compressedStartOffset = headerInfo.payloadOffset
        var decompressedStartOffset = 0L
        for (i in 0 until startChunkIdx) {
            compressedStartOffset += seekEntries[i].first
            decompressedStartOffset += seekEntries[i].second
        }

        var compressedEndOffset = compressedStartOffset
        for (i in startChunkIdx..endChunkIdx) {
            compressedEndOffset += seekEntries[i].first
        }

        val sb = StringBuilder()
        if (isPartial) {
            sb.append("HTTP/1.1 206 Partial Content\r\n")
            sb.append("Content-Range: bytes $startByte-$endByte/$totalDecompressed\r\n")
        } else {
            sb.append("HTTP/1.1 200 OK\r\n")
        }
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Type: ").append(mimeType).append("\r\n")
        sb.append("Content-Length: ").append(serveLength).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))

        if (headOnly) {
            output.flush()
            return
        }

        val conn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = "bytes=$compressedStartOffset-${compressedEndOffset - 1L}"
        )
        try {
            BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                if (conn.responseCode == 200 && compressedStartOffset > 0L) {
                    skipFully(bis, compressedStartOffset)
                }

                var currentDecompPos = decompressedStartOffset
                var remainingToWrite = serveLength

                for (chunkIdx in startChunkIdx..endChunkIdx) {
                    if (remainingToWrite <= 0L) break
                    val (compSize, decompSize) = seekEntries[chunkIdx]
                    val compBuf = ByteArray(compSize)
                    readFully(bis, compBuf, 0, compSize)

                    val rawChunk = NativeCore.decompressZstdChunk(compBuf, decompSize)
                        ?: throw IOException("Failed to decompress VCV chunk #$chunkIdx")

                    val chunkEndPos = currentDecompPos + rawChunk.size
                    val sliceStart = (startByte - currentDecompPos).coerceAtLeast(0L).toInt()
                    val sliceEnd = (endByte + 1L - currentDecompPos)
                        .coerceAtMost(rawChunk.size.toLong())
                        .toInt()

                    if (sliceEnd > sliceStart) {
                        val toWrite = minOf((sliceEnd - sliceStart).toLong(), remainingToWrite).toInt()
                        output.write(rawChunk, sliceStart, toWrite)
                        remainingToWrite -= toWrite
                    }
                    currentDecompPos = chunkEndPos
                }
            }
            output.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun serveSingleStreamCompressed(
        item: RemoteMediaItem,
        headerInfo: ContainerHeaderInfo,
        mimeType: String,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(mimeType).append("\r\n")
        if (headerInfo.originalSize > 0L) {
            sb.append("Content-Length: ").append(headerInfo.originalSize).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))

        if (headOnly) {
            output.flush()
            return
        }

        val conn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = "bytes=${headerInfo.payloadOffset}-"
        )
        try {
            BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                if (conn.responseCode == 200) {
                    skipFully(bis, headerInfo.payloadOffset)
                }
                ZstdDecompressInputStream(bis).use { zstdIn ->
                    copyStream(zstdIn, output)
                }
            }
            output.flush()
        } finally {
            conn.disconnect()
        }
    }

    fun parseContainerHeaderFromStream(input: InputStream): ContainerHeaderInfo {
        val magicBytes = ByteArray(4)
        readFully(input, magicBytes, 0, 4)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != "SVCV" && magic != "SICV") {
            throw IOException("Invalid container magic: $magic")
        }

        val lenBytes = ByteArray(4)
        readFully(input, lenBytes, 0, 4)
        val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerLen <= 0 || headerLen > 1_048_576) {
            throw IOException("Invalid container header length: $headerLen")
        }

        val jsonBytes = ByteArray(headerLen)
        readFully(input, jsonBytes, 0, headerLen)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

        return ContainerHeaderInfo(
            magic = magic,
            format = json.optString("format", if (magic == "SVCV") "video" else "image"),
            compression = json.optInt("compression", 0),
            originalExt = json.optString("original_ext", if (magic == "SVCV") "mp4" else "jpg"),
            originalSize = json.optLong("original_size", -1L),
            chunkSize = json.optInt("chunk_size", 0),
            payloadOffset = 8L + headerLen.toLong()
        )
    }

    private fun parseSeekTableFromFooter(footer: ByteArray): List<Pair<Int, Int>> {
        if (footer.size < 16) return emptyList()
        val buf = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
        val tableMagic = buf.getInt(footer.size - 4)
        if (tableMagic != 0x8F92EAB1.toInt()) return emptyList()

        val numChunks = buf.getInt(footer.size - 8)
        if (numChunks <= 0 || numChunks > 500_000) return emptyList()

        val entriesBytes = numChunks * 8
        val frameStart = footer.size - 8 - entriesBytes - 8
        if (frameStart < 0) return emptyList()

        val frameMagic = buf.getInt(frameStart)
        if (frameMagic != 0x184D2A5E) return emptyList()

        val result = ArrayList<Pair<Int, Int>>(numChunks)
        var cursor = frameStart + 8
        for (i in 0 until numChunks) {
            val compSize = buf.getInt(cursor)
            val decompSize = buf.getInt(cursor + 4)
            result.add(compSize to decompSize)
            cursor += 8
        }
        return result
    }

    private fun fetchByteRange(
        item: RemoteMediaItem,
        rangeStart: Long,
        rangeEnd: Long
    ): ByteArray {
        val conn = openRemoteConnection(
            startUrl = item.mediaUrl,
            extraHeaders = item.headers,
            rangeHeader = "bytes=$rangeStart-$rangeEnd"
        )
        try {
            val baos = ByteArrayOutputStream((rangeEnd - rangeStart + 1L).toInt().coerceAtLeast(1024))
            conn.inputStream.use { input ->
                if (conn.responseCode == 200 && rangeStart > 0L) {
                    skipFully(input, rangeStart)
                }
                copyExactBytes(input, baos, rangeEnd - rangeStart + 1L)
            }
            return baos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    fun openRemoteConnection(
        startUrl: String,
        extraHeaders: Map<String, String> = emptyMap(),
        rangeHeader: String? = null
    ): HttpURLConnection {
        var currentUrl = startUrl.trim()
        val effectiveHeaders = LinkedHashMap<String, String>(extraHeaders)

        val lowerStart = currentUrl.lowercase()
        if (lowerStart.contains("youtube.com") || lowerStart.contains("youtu.be")) {
            val resolvedYt = resolveYouTubeStream(currentUrl)
            currentUrl = resolvedYt.streamUrl
            effectiveHeaders.putIfAbsent("User-Agent", resolvedYt.userAgent)
        }

        var redirects = 0
        var cookieJar = effectiveHeaders["Cookie"] ?: ""

        while (redirects < 10) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", effectiveHeaders["User-Agent"] ?: DEFAULT_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                if (cookieJar.isNotBlank()) {
                    setRequestProperty("Cookie", cookieJar)
                }
                for ((k, v) in effectiveHeaders) {
                    if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Cookie", ignoreCase = true)) {
                        setRequestProperty(k, v)
                    }
                }
                if (!rangeHeader.isNullOrBlank()) {
                    setRequestProperty("Range", rangeHeader)
                }
            }

            val code = conn.responseCode
            val setCookies = conn.headerFields["Set-Cookie"]
            if (!setCookies.isNullOrEmpty()) {
                val merged = LinkedHashMap<String, String>()
                cookieJar.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                    merged[pair.substringBefore('=')] = pair.substringAfter('=')
                }
                for (sc in setCookies) {
                    val kv = sc.substringBefore(';').trim()
                    if (kv.contains('=')) {
                        merged[kv.substringBefore('=')] = kv.substringAfter('=')
                    }
                }
                cookieJar = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect without Location header from $currentUrl")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }

            if (code == 200 && currentUrl.contains("drive.google.com") &&
                (conn.contentType?.contains("text/html", ignoreCase = true) == true)
            ) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val confirmedUrl = extractGoogleDriveConfirmUrl(currentUrl, html)
                if (confirmedUrl != null && confirmedUrl != currentUrl) {
                    currentUrl = confirmedUrl
                    redirects++
                    continue
                }
                throw IOException("Google Drive returned HTML instead of media stream")
            }

            return conn
        }
        throw IOException("Too many HTTP redirects for $startUrl")
    }

    fun resolveYouTubeVideoId(url: String): String? {
        val nativeJson = runCatching { NativeCore.resolveHostEndpoint(url) }.getOrNull()
        if (!nativeJson.isNullOrBlank()) {
            val id = runCatching { JSONObject(nativeJson).optString("id") }.getOrNull()
            if (!id.isNullOrBlank()) return id
        }
        val patterns = listOf(
            Regex("""(?:youtu\.be/|/shorts/|/embed/|/live/|[?&]v=)([A-Za-z0-9_-]{6,16})""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun resolveYouTubeStream(rawUrl: String): ResolvedYtStream {
        val videoId = resolveYouTubeVideoId(rawUrl)
            ?: throw IOException("Unable to extract YouTube video ID from: $rawUrl")

        val now = System.currentTimeMillis()
        youtubeStreamCache[videoId]?.let { cached ->
            if (cached.expiresAtMs > now + 60_000L) {
                return cached
            }
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", "1.56.21")
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("html5Preference", "HTML5_PREF_WANTS")
                })
            })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()

        val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", YT_ANDROID_VR_UA)
            setRequestProperty("X-YouTube-Client-Name", "28")
            setRequestProperty("X-YouTube-Client-Version", "1.56.21")
        }

        val responseText = try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }

        val root = JSONObject(responseText)
        val streamingData = root.optJSONObject("streamingData")
            ?: throw IOException("YouTube streamingData unavailable for $videoId")

        val candidates = mutableListOf<Pair<Int, String>>()
        val progressive = streamingData.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until progressive.length()) {
            val fmt = progressive.optJSONObject(i) ?: continue
            val directUrl = fmt.optString("url")
            if (directUrl.isBlank()) continue
            val mime = fmt.optString("mimeType").lowercase()
            val height = fmt.optInt("height", 360)
            val score = if (mime.contains("video/mp4")) height + 1000 else height
            candidates.add(score to directUrl)
        }

        val bestUrl = candidates.maxByOrNull { it.first }?.second
            ?: throw IOException("No direct progressive MP4 stream found for YouTube ID: $videoId")

        val expireSec = Regex("""[?&]expire=(\d+)""").find(bestUrl)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAt = if (expireSec != null) expireSec * 1000L else (now + 3 * 3600_000L)

        val resolved = ResolvedYtStream(
            streamUrl = bestUrl,
            userAgent = YT_ANDROID_VR_UA,
            expiresAtMs = expiresAt
        )
        youtubeStreamCache[videoId] = resolved
        return resolved
    }

    private fun extractGoogleDriveConfirmUrl(originalUrl: String, html: String): String? {
        val fileId = Regex("""[?&]id=([A-Za-z0-9_-]+)|/d/([A-Za-z0-9_-]+)""")
            .find(originalUrl)
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?: return null

        val uuid = Regex("""name="uuid"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
        val confirm = Regex("""name="confirm"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1) ?: "t"

        return if (!uuid.isNullOrBlank()) {
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=$confirm&uuid=$uuid"
        } else {
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        }
    }

    private fun parseByteRange(rangeHeader: String?, totalSize: Long): Triple<Long, Long, Boolean> {
        if (totalSize <= 0L) return Triple(0L, -1L, false)
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            return Triple(0L, totalSize - 1L, false)
        }
        val spec = rangeHeader.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return Triple(0L, totalSize - 1L, false)

        val startStr = spec.substring(0, dash).trim()
        val endStr = spec.substring(dash + 1).trim()

        return if (startStr.isEmpty()) {
            val suffix = endStr.toLongOrNull() ?: return Triple(0L, totalSize - 1L, false)
            val start = (totalSize - suffix).coerceAtLeast(0L)
            Triple(start, totalSize - 1L, true)
        } else {
            val start = (startStr.toLongOrNull() ?: 0L).coerceIn(0L, totalSize - 1L)
            val end = (endStr.toLongOrNull() ?: (totalSize - 1L)).coerceIn(start, totalSize - 1L)
            Triple(start, end, true)
        }
    }

    private fun parseTotalSizeFromResponse(conn: HttpURLConnection, code: Int): Long {
        if (code == 206) {
            val cr = conn.getHeaderField("Content-Range") ?: return -1L
            val slash = cr.lastIndexOf('/')
            if (slash >= 0) {
                return cr.substring(slash + 1).trim().toLongOrNull() ?: -1L
            }
        }
        return conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
    }

    private fun guessMimeType(fileName: String, kind: MediaKind): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("US-ASCII")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("US-ASCII")
    }

    private fun sendSimpleStatus(output: OutputStream, code: Int, message: String) {
        val body = "$code $message\n".toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, len: Int) {
        var total = 0
        while (total < len) {
            val n = input.read(buf, offset + total, len - total)
            if (n <= 0) throw EOFException("Unexpected EOF while reading $len bytes")
            total += n
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(16_384)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(65_536)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
        }
    }

    private fun copyExactBytes(input: InputStream, output: OutputStream, count: Long) {
        val buf = ByteArray(65_536)
        var remaining = count
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
    }
}

class ZstdDecompressInputStream(
    private val upstream: InputStream
) : InputStream() {
    private val decoderHandle: Long = NativeCore.createZstdStreamDecoder()
    private val inBuf = ByteArray(32_768)
    private var inOffset = 0
    private var inLimit = 0
    private var outBuf = ByteArray(0)
    private var outOffset = 0
    private var closed = false
    private var eof = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n == 1) (single[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (len == 0) return 0

        while (outOffset >= outBuf.size) {
            if (eof) return -1
            if (inOffset >= inLimit) {
                val n = upstream.read(inBuf)
                if (n <= 0) {
                    eof = true
                    return -1
                }
                inOffset = 0
                inLimit = n
            }
            val slice = inBuf.copyOfRange(inOffset, inLimit)
            val result = NativeCore.decompressZstdStreamStep(decoderHandle, slice)
            if (result == null || result.size < 4) {
                throw IOException("Native Zstd stream decompression failed")
            }
            val consumed = ByteBuffer.wrap(result, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            inOffset += consumed.coerceAtLeast(0)
            if (result.size > 4) {
                outBuf = result.copyOfRange(4, result.size)
                outOffset = 0
            }
        }

        val available = outBuf.size - outOffset
        val toCopy = minOf(len, available)
        System.arraycopy(outBuf, outOffset, b, off, toCopy)
        outOffset += toCopy
        return toCopy
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (decoderHandle != 0L) {
                runCatching { NativeCore.freeZstdStreamDecoder(decoderHandle) }
            }
            upstream.close()
        }
    }
}
