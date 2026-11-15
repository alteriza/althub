package com.althub.alteriza

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object AutoDiscoveryRepository {
    private const val MAX_THUMB_DIM = 720
    private val VIDEO_EXTS = setOf(
        "vcv", "mp4", "mkv", "webm", "mov", "avi", "m4v",
        "3gp", "ts", "mts", "m2ts", "flv", "wmv", "ogv", "mpeg", "mpg"
    )
    private val IMAGE_EXTS = setOf("icv", "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")

    private val memoryThumbs = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var cachedLogoBitmap: Bitmap? = null

    @Volatile
    private var cachedGofileToken: String? = null

    private data class GitHubLocation(
        val owner: String,
        val repo: String,
        val branch: String?,
        val subPath: String
    )

    private data class CdnSourceEntry(
        val url: String,
        val customFileName: String? = null
    )

    fun getCachedThumbnail(itemId: String): Bitmap? = memoryThumbs[itemId]

    fun clearMemoryCache() {
        memoryThumbs.clear()
    }

    private fun getAltHubLogoBitmap(context: Context): Bitmap {
        cachedLogoBitmap?.let { return it }
        synchronized(this) {
            cachedLogoBitmap?.let { return it }
            val appCtx = context.applicationContext
            val resId = appCtx.resources.getIdentifier("ic_althub_logo", "drawable", appCtx.packageName)
            val fromRes = if (resId != 0) {
                runCatching { BitmapFactory.decodeResource(appCtx.resources, resId) }.getOrNull()
            } else {
                null
            }
            val resolved = fromRes ?: runCatching {
                val drawable = appCtx.packageManager.getApplicationIcon(appCtx.packageName)
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap
                } else {
                    val w = drawable.intrinsicWidth.coerceAtLeast(256)
                    val h = drawable.intrinsicHeight.coerceAtLeast(256)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                }
            }.getOrNull() ?: createFallbackCubicLogo()
            val scaled = scaleDown(resolved)
            cachedLogoBitmap = scaled
            return scaled
        }
    }

    private fun createFallbackCubicLogo(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF0D0D0D.toInt()
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(112f, 112f, 400f, 400f, paint)
        paint.color = 0xFFFF6B00.toInt()
        canvas.drawRect(196f, 196f, 316f, 316f, paint)
        return bmp
    }

    fun extractVideoTags(fileName: String): String? {
        val base = fileName.substringBeforeLast('.', fileName)
        val matches = Regex("""\[([^\[\]]+)]""").findAll(base).toList()
        if (matches.isEmpty()) return null
        return matches.joinToString("") { "[${it.groupValues[1].trim()}]" }
    }

    fun stripVideoTags(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val stripped = base.replace(Regex("""\[[^\[\]]+]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        return stripped.ifBlank { base }
    }

    fun formatVideoTitle(fileName: String): String {
        val cleaned = stripVideoTags(fileName)
        return cleaned.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().ifEmpty { fileName }
    }

    private fun extractSeriesBaseKey(fileName: String): String {
        var s = stripVideoTags(fileName)
        s = s.replace(Regex("""\([^()]*\)"""), " ")
        s = s.replace(
            Regex(
                """(?:^|[\s._-])(?:s\d{1,2}\s*e\d{1,4}|ep(?:isode|s)?\s*\d{1,4}|part\s*\d{1,3}|vol(?:ume)?\s*\d{1,3}|#\d{1,4}|\d{1,4})$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        return s.replace('_', ' ')
            .replace('-', ' ')
            .replace('.', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
    }

    private fun registerThumbnailCandidate(
        pool: MutableMap<String, String>,
        fileName: String,
        thumbUrl: String
    ) {
        val decoded = decodeUrlName(fileName)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_EXTS || ext == "icv") return
        val stem = decoded.substringBeforeLast('.', decoded)
        val lowerStem = stem.lowercase().trim()
        if (lowerStem.isNotEmpty()) {
            pool.putIfAbsent(lowerStem, thumbUrl)
        }
        val stripped = stripVideoTags(stem).lowercase().trim()
        if (stripped.isNotEmpty()) {
            pool.putIfAbsent(stripped, thumbUrl)
        }
        val norm = stripped.replace('_', ' ').replace('-', ' ').replace(Regex("""\s+"""), " ").trim()
        if (norm.isNotEmpty()) {
            pool.putIfAbsent(norm, thumbUrl)
        }
    }

    private fun resolveMultiUseThumbnail(
        videoFileName: String,
        thumbByTargetName: Map<String, String>,
        videoTitle: String? = null
    ): String? {
        if (thumbByTargetName.isEmpty()) return null

        val lowerFull = videoFileName.lowercase()
        val lowerNoExt = lowerFull.substringBeforeLast('.')
        thumbByTargetName[lowerFull]?.let { return it }
        thumbByTargetName[lowerNoExt]?.let { return it }

        if (!videoTitle.isNullOrBlank()) {
            val lowerTitle = videoTitle.lowercase().trim()
            thumbByTargetName[lowerTitle]?.let { return it }
        }

        val tagStripped = stripVideoTags(videoFileName).lowercase()
        val tagStrippedNormalized = tagStripped
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        thumbByTargetName[tagStripped]?.let { return it }
        thumbByTargetName[tagStrippedNormalized]?.let { return it }

        val seriesKey = extractSeriesBaseKey(videoFileName)
        if (seriesKey.isNotEmpty()) {
            thumbByTargetName[seriesKey]?.let { return it }
        }

        val candidates = listOfNotNull(
            tagStrippedNormalized,
            seriesKey,
            lowerNoExt,
            videoTitle?.lowercase()?.trim()
        ).filter { it.isNotEmpty() }.distinct()

        val sortedKeys = thumbByTargetName.keys.sortedByDescending { it.length }
        for (thumbKey in sortedKeys) {
            val normThumb = thumbKey
                .replace('_', ' ')
                .replace('-', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (normThumb.length < 3) continue
            for (cand in candidates) {
                if (cand == normThumb) return thumbByTargetName[thumbKey]
                if (cand.startsWith("$normThumb ") ||
                    cand.startsWith("$normThumb-") ||
                    cand.startsWith("${normThumb}_") ||
                    cand.startsWith("$normThumb.")
                ) {
                    return thumbByTargetName[thumbKey]
                }
                val wordBoundaryRegex = Regex("""(?:^|[\s._\-\[\](])${Regex.escape(normThumb)}(?:$|[\s._\-\[\])])""")
                if (wordBoundaryRegex.containsMatchIn(cand)) {
                    return thumbByTargetName[thumbKey]
                }
            }
        }
        return null
    }

    private fun parseCdnLine(line: String): CdnSourceEntry? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        if (trimmed.contains('|')) {
            val rawUrl = trimmed.substringBefore('|').trim()
            val alias = trimmed.substringAfter('|').trim().ifBlank { null }
            return CdnSourceEntry(rawUrl.substringBefore('#').trim(), alias)
        }

        if (trimmed.contains('#')) {
            val rawUrl = trimmed.substringBefore('#').trim()
            val frag = decodeUrlName(trimmed.substringAfter('#').trim()).ifBlank { null }
            val hasTagOrExt = frag != null && (frag.contains('[') || classifyMediaByName(frag) != null)
            return CdnSourceEntry(rawUrl, if (hasTagOrExt) frag else null)
        }

        return CdnSourceEntry(trimmed, null)
    }

    private fun applyCustomFileName(customName: String?, fallbackName: String): String {
        val clean = customName?.trim()?.takeIf { it.isNotEmpty() } ?: return fallbackName
        if (!clean.contains('.') && fallbackName.contains('.')) {
            return "$clean.${fallbackName.substringAfterLast('.')}"
        }
        return clean
    }

    suspend fun discoverAll(): DiscoveryResult =
        discoverFromCdn(
            cdnUrl = NativeCore.getPrimaryCdnUrl(),
            postUrl = NativeCore.getPrimaryPostUrl(),
            fallbackRepoUrl = NativeCore.getDefaultRepoUrl()
        )

    suspend fun discoverHiddenCdn(): DiscoveryResult =
        discoverFromCdn(
            cdnUrl = NativeCore.getHiddenCdnUrl(),
            postUrl = null,
            fallbackRepoUrl = null
        )

    private suspend fun discoverFromCdn(
        cdnUrl: String,
        postUrl: String?,
        fallbackRepoUrl: String?
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val mediaMap = ConcurrentHashMap<String, RemoteMediaItem>()
        val postMap = ConcurrentHashMap<String, InfoPost>()
        val globalThumbPool = ConcurrentHashMap<String, String>()
        val activeSources = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())

        val cdnRaw = fetchGitHubFileText(cdnUrl)
        val rawLines = cdnRaw
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .orEmpty()

        val parsedEntries = mutableListOf<CdnSourceEntry>()
        val bbBuffer = StringBuilder()
        var insideBbBlock = false
        var announcementIdx = 0

        for (line in rawLines) {
            when {
                line.equals("[ANNOUNCEMENT]", ignoreCase = true) ||
                    line.equals("[INFO]", ignoreCase = true) -> {
                    insideBbBlock = true
                }
                line.equals("[/ANNOUNCEMENT]", ignoreCase = true) ||
                    line.equals("[/INFO]", ignoreCase = true) -> {
                    insideBbBlock = false
                    val text = bbBuffer.toString().trim()
                    if (text.isNotEmpty()) {
                        announcementIdx++
                        val key = "cdn_announcement_$announcementIdx"
                        postMap[key] = buildInfoPost(key, "Announcement #$announcementIdx", text, cdnUrl)
                    }
                    bbBuffer.clear()
                }
                insideBbBlock -> {
                    if (bbBuffer.isNotEmpty()) bbBuffer.append('\n')
                    bbBuffer.append(line)
                }
                else -> {
                    parseCdnLine(line)?.let { parsedEntries.add(it) }
                }
            }
        }
        if (bbBuffer.isNotEmpty()) {
            announcementIdx++
            val key = "cdn_announcement_$announcementIdx"
            postMap[key] = buildInfoPost(key, "Announcement #$announcementIdx", bbBuffer.toString().trim(), cdnUrl)
        }

        val sourceEntries = when {
            parsedEntries.isNotEmpty() -> parsedEntries.distinctBy { "${it.url}|${it.customFileName.orEmpty()}" }
            fallbackRepoUrl != null -> listOf(CdnSourceEntry(fallbackRepoUrl))
            else -> emptyList()
        }

        coroutineScope {
            sourceEntries.map { entry ->
                async {
                    val src = entry.url
                    activeSources.add(src)
                    val hostJson = runCatching { NativeCore.resolveHostEndpoint(src) }.getOrNull()
                    val hostObj = hostJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val rawHostType = hostObj?.optString("host_type")?.ifBlank { null }
                        ?: hostObj?.optString("host")?.ifBlank { null }
                        ?: "direct"
                    val resolvedUrl = hostObj?.optString("resolved_url")?.ifBlank { null }
                        ?: hostObj?.optString("url")?.ifBlank { null }
                        ?: src
                    val fileId = hostObj?.optString("file_id")?.ifBlank { null }
                        ?: hostObj?.optString("id")?.ifBlank { null }
                        ?: ""
                    val lower = src.lowercase()

                    val hostType = when {
                        rawHostType == "youtube" || lower.contains("youtube.com") || lower.contains("youtu.be") -> "youtube"
                        rawHostType == "gdrive" || lower.contains("drive.google.com") || lower.contains("docs.google.com") -> {
                            if (lower.contains("/folders/")) "gdrive_folder" else "gdrive_file"
                        }
                        rawHostType == "pixeldrain" && lower.contains("/l/") -> "pixeldrain_list"
                        else -> rawHostType
                    }

                    when (hostType) {
                        "youtube" -> {
                            runCatching {
                                discoverYouTubeVideo(src, mediaMap, entry.customFileName)
                            }.onFailure {
                                errors.add("YouTube: ${it.message}")
                            }
                        }
                        "gdrive_folder" -> {
                            val folderId = extractDriveId(src)
                            if (folderId != null) {
                                runCatching {
                                    discoverGoogleDriveRoot(folderId, mediaMap, postMap, globalThumbPool)
                                }.onFailure {
                                    errors.add("Drive ($folderId): ${it.message}")
                                }
                            }
                        }
                        "gdrive_file" -> {
                            val fid = extractDriveId(src)
                            if (fid != null) {
                                runCatching {
                                    discoverSingleDriveFile(fid, mediaMap, entry.customFileName)
                                }.onFailure {
                                    errors.add("DriveFile ($fid): ${it.message}")
                                }
                            }
                        }
                        "github" -> {
                            val loc = parseGitHubUrl(src)
                            if (loc != null) {
                                runCatching {
                                    discoverGitHubSource(src, loc, mediaMap, postMap, globalThumbPool)
                                }.onFailure {
                                    errors.add("GitHub (${loc.owner}/${loc.repo}): ${it.message}")
                                }
                            } else {
                                runCatching {
                                    discoverDirectOrWebFile(src, SourceType.GITHUB, mediaMap, entry.customFileName)
                                }.onFailure {
                                    errors.add("GitHubRelease: ${it.message}")
                                }
                            }
                        }
                        "pixeldrain" -> {
                            val pid = fileId.ifBlank {
                                Regex("""pixeldrain\.com/(?:u/|api/file/)([A-Za-z0-9_-]+)""").find(src)?.groupValues?.get(1).orEmpty()
                            }
                            val streamUrl = if (pid.isNotEmpty()) "https://pixeldrain.com/api/file/$pid" else resolvedUrl.substringBefore('?')
                            runCatching {
                                discoverPixeldrainFile(pid, streamUrl, mediaMap, entry.customFileName)
                            }.onFailure {
                                errors.add("Pixeldrain ($pid): ${it.message}")
                            }
                        }
                        "pixeldrain_list" -> {
                            val lid = fileId.ifBlank {
                                Regex("""pixeldrain\.com/l/([A-Za-z0-9_-]+)""").find(src)?.groupValues?.get(1).orEmpty()
                            }
                            val listApi = if (lid.isNotEmpty()) "https://pixeldrain.com/api/list/$lid" else resolvedUrl
                            runCatching {
                                discoverPixeldrainList(listApi, mediaMap)
                            }.onFailure {
                                errors.add("PixeldrainList ($lid): ${it.message}")
                            }
                        }
                        "gofile" -> {
                            val gid = fileId.ifBlank {
                                Regex("""gofile\.io/d/([A-Za-z0-9_-]+)""").find(src)?.groupValues?.get(1).orEmpty()
                            }
                            val apiUrl = if (gid.isNotEmpty()) {
                                "https://api.gofile.io/contents/$gid?wt=4fd6sg89d7s6"
                            } else {
                                resolvedUrl
                            }
                            runCatching {
                                discoverGofileContent(apiUrl, mediaMap)
                            }.onFailure {
                                errors.add("Gofile ($gid): ${it.message}")
                            }
                        }
                        "acefile" -> {
                            runCatching {
                                discoverAcefilePage(src, fileId, mediaMap, entry.customFileName)
                            }.onFailure {
                                errors.add("Acefile ($fileId): ${it.message}")
                            }
                        }
                        "filedom" -> {
                            runCatching {
                                discoverDirectOrWebFile(src, SourceType.FILEDOM, mediaMap, entry.customFileName)
                            }.onFailure {
                                errors.add("Filedom: ${it.message}")
                            }
                        }
                        else -> {
                            runCatching {
                                discoverDirectOrWebFile(src, SourceType.DIRECT, mediaMap, entry.customFileName)
                            }.onFailure {
                                errors.add("Direct: ${it.message}")
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        if (postUrl != null) {
            val mainPostLoc = parseGitHubUrl(postUrl)
            if (mainPostLoc != null) {
                runCatching {
                    discoverGitHubPostsOnly(mainPostLoc, postMap)
                }.onFailure {
                    errors.add("Post: ${it.message}")
                }
            }
        }

        val finalMediaItems = mediaMap.values.map { item ->
            val poolThumb = resolveMultiUseThumbnail(item.fileName, globalThumbPool, item.title)
            if (poolThumb != null) {
                item.copy(thumbnailUrl = poolThumb)
            } else {
                item
            }
        }.sortedByDescending { it.fileName.lowercase() }

        DiscoveryResult(
            mediaItems = finalMediaItems,
            infoPosts = postMap.values.sortedByDescending { it.fileName.lowercase() },
            activeSources = activeSources.toList(),
            errorMessage = if (finalMediaItems.isEmpty() && postMap.isEmpty() && errors.isNotEmpty()) {
                errors.joinToString(" | ")
            } else {
                null
            }
        )
    }

    private fun discoverYouTubeVideo(
        rawUrl: String,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        customFileName: String? = null
    ) {
        val videoId = LocalStreamServer.resolveYouTubeVideoId(rawUrl) ?: return
        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val oembedUrl = "https://www.youtube.com/oembed?url=${URLEncoder.encode(canonicalUrl, "UTF-8")}&format=json"
        val oembedJson = httpGetText(oembedUrl, isGitHubApi = false)
        val rawTitle = if (!oembedJson.isNullOrBlank()) {
            runCatching { JSONObject(oembedJson).optString("title") }.getOrNull()?.ifBlank { null }
        } else {
            null
        } ?: "YouTube_$videoId"
        val defaultFileName = "${rawTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()}.mp4"
        val finalFileName = applyCustomFileName(customFileName, defaultFileName)
        val defaultThumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val itemKey = if (customFileName.isNullOrBlank()) {
            "yt_$videoId"
        } else {
            "yt_${videoId}_${sha256(customFileName).take(8)}"
        }
        mediaMap[itemKey] = buildMediaItem(
            id = itemKey,
            fileName = finalFileName,
            kind = MediaKind.VIDEO,
            sourceType = SourceType.YOUTUBE,
            mediaUrl = canonicalUrl,
            thumbnailUrl = defaultThumb,
            sizeBytes = 0L,
            folderPath = "/youtube",
            isContainer = false
        )
    }

    private fun classifyMediaByName(fileName: String, contentType: String = ""): Pair<MediaKind, Boolean>? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val ct = contentType.lowercase()
        return when {
            ext == "vcv" -> MediaKind.VIDEO to true
            ext == "icv" -> MediaKind.IMAGE to true
            ext in VIDEO_EXTS -> MediaKind.VIDEO to false
            ext in IMAGE_EXTS -> MediaKind.IMAGE to false
            ct.startsWith("video/") -> MediaKind.VIDEO to false
            ct.startsWith("image/") -> MediaKind.IMAGE to false
            else -> null
        }
    }

    private fun buildMediaItem(
        id: String,
        fileName: String,
        kind: MediaKind,
        sourceType: SourceType,
        mediaUrl: String,
        thumbnailUrl: String?,
        sizeBytes: Long,
        folderPath: String,
        isContainer: Boolean,
        headers: Map<String, String> = emptyMap()
    ): RemoteMediaItem {
        val isVideo = kind == MediaKind.VIDEO
        return RemoteMediaItem(
            id = id,
            title = if (isVideo) formatVideoTitle(fileName) else formatTitle(fileName),
            fileName = fileName,
            kind = kind,
            sourceType = sourceType,
            mediaUrl = mediaUrl,
            thumbnailUrl = thumbnailUrl,
            sizeBytes = sizeBytes,
            folderPath = folderPath,
            isContainer = isContainer,
            headers = headers,
            videoTags = if (isVideo) extractVideoTags(fileName) else null
        )
    }

    private fun discoverPixeldrainFile(
        fileId: String,
        streamUrl: String,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        customFileName: String? = null
    ) {
        val cleanStreamUrl = streamUrl.substringBefore('?')
        val infoUrl = "$cleanStreamUrl/info"
        val infoJson = httpGetText(infoUrl, isGitHubApi = false)
        val obj = infoJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val rawName = obj?.optString("name", "")?.ifBlank { null } ?: "${fileId.ifBlank { "pixeldrain" }}.mp4"
        val finalName = applyCustomFileName(customFileName, rawName)
        val size = obj?.optLong("size", 0L) ?: 0L
        val mime = obj?.optString("mime_type", "") ?: ""
        val classified = classifyMediaByName(finalName, mime) ?: (MediaKind.VIDEO to false)
        val itemKey = sha256("$cleanStreamUrl|${customFileName.orEmpty()}")
        mediaMap[itemKey] = buildMediaItem(
            id = itemKey,
            fileName = finalName,
            kind = classified.first,
            sourceType = SourceType.PIXELDRAIN,
            mediaUrl = cleanStreamUrl,
            thumbnailUrl = null,
            sizeBytes = size,
            folderPath = "/pixeldrain",
            isContainer = classified.second
        )
    }

    private fun discoverPixeldrainList(
        listApiUrl: String,
        mediaMap: MutableMap<String, RemoteMediaItem>
    ) {
        val jsonText = httpGetText(listApiUrl, isGitHubApi = false) ?: return
        val root = JSONObject(jsonText)
        val files = root.optJSONArray("files") ?: return
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val fid = f.optString("id")
            val name = f.optString("name").ifBlank { "$fid.mp4" }
            val size = f.optLong("size", 0L)
            val mime = f.optString("mime_type", "")
            val classified = classifyMediaByName(name, mime) ?: continue
            val streamUrl = "https://pixeldrain.com/api/file/$fid"
            val itemKey = sha256(streamUrl)
            mediaMap[itemKey] = buildMediaItem(
                id = itemKey,
                fileName = name,
                kind = classified.first,
                sourceType = SourceType.PIXELDRAIN,
                mediaUrl = streamUrl,
                thumbnailUrl = null,
                sizeBytes = size,
                folderPath = "/pixeldrain",
                isContainer = classified.second
            )
        }
    }

    private fun ensureGofileToken(): String? {
        cachedGofileToken?.let { return it }
        val endpoint = NativeCore.getGofileTokenEndpoint()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", NativeCore.getUserAgent())
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(ByteArray(0)) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().readText()
                val token = JSONObject(resp).optJSONObject("data")?.optString("token", "")
                if (!token.isNullOrBlank()) {
                    cachedGofileToken = token
                    token
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun discoverGofileContent(
        apiUrl: String,
        mediaMap: MutableMap<String, RemoteMediaItem>
    ) {
        val token = ensureGofileToken() ?: return
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Cookie" to "accountToken=$token"
        )
        val jsonText = httpGetText(apiUrl, isGitHubApi = false, extraHeaders = headers) ?: return
        val root = JSONObject(jsonText)
        val data = root.optJSONObject("data") ?: return
        val children = data.optJSONObject("children") ?: return
        val keys = children.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val itemObj = children.optJSONObject(k) ?: continue
            if (itemObj.optString("type") != "file") continue
            val name = itemObj.optString("name")
            val link = itemObj.optString("link")
            val size = itemObj.optLong("size", 0L)
            val mime = itemObj.optString("mimetype", "")
            if (link.isBlank()) continue
            val classified = classifyMediaByName(name, mime) ?: continue
            val itemKey = sha256(link)
            mediaMap[itemKey] = buildMediaItem(
                id = itemKey,
                fileName = name,
                kind = classified.first,
                sourceType = SourceType.GOFILE,
                mediaUrl = link,
                thumbnailUrl = null,
                sizeBytes = size,
                folderPath = "/gofile",
                isContainer = classified.second,
                headers = headers
            )
        }
    }

    private fun discoverAcefilePage(
        pageUrl: String,
        fileId: String,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        customFileName: String? = null
    ) {
        val targetUrls = buildList {
            if (fileId.isNotEmpty() && pageUrl.contains("/f/")) {
                add("https://acefile.co/player/$fileId")
            }
            add(pageUrl)
        }
        for (u in targetUrls) {
            val html = httpGetText(u, isGitHubApi = false) ?: continue
            val direct = extractMediaUrlFromHtml(html, u)
            if (direct != null) {
                val titleMatch = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
                val rawTitle = titleMatch?.groupValues?.get(1)
                    ?.replace("Acefile", "", ignoreCase = true)
                    ?.replace("-", " ")
                    ?.trim()
                    .orEmpty()
                val slugName = pageUrl.substringAfterLast('/').substringBefore('?')
                val resolvedName = when {
                    slugName.contains('.') -> decodeUrlName(slugName)
                    rawTitle.isNotBlank() -> "$rawTitle.mp4"
                    else -> "acefile_${fileId.ifEmpty { "stream" }}.mp4"
                }
                val finalName = applyCustomFileName(customFileName, resolvedName)
                val classified = classifyMediaByName(finalName) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256("$direct|${customFileName.orEmpty()}")
                mediaMap[itemKey] = buildMediaItem(
                    id = itemKey,
                    fileName = finalName,
                    kind = classified.first,
                    sourceType = SourceType.ACEFILE,
                    mediaUrl = direct,
                    thumbnailUrl = null,
                    sizeBytes = 0L,
                    folderPath = "/acefile",
                    isContainer = classified.second,
                    headers = mapOf("Referer" to u)
                )
                return
            }
        }
    }

    private fun discoverSingleDriveFile(
        fileId: String,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        customFileName: String? = null
    ) {
        val dlUrl = NativeCore.buildDriveDownloadUrl(fileId)
        val conn = LocalStreamServer.openRemoteConnection(dlUrl, rangeHeader = "bytes=0-1023")
        try {
            val ct = conn.contentType.orEmpty()
            val disp = conn.getHeaderField("Content-Disposition").orEmpty()
            val dispName = Regex("""filename\*?=(?:UTF-8''|")?([^";\r\n]+)""", RegexOption.IGNORE_CASE)
                .find(disp)
                ?.groupValues
                ?.get(1)
                ?.let { decodeUrlName(it.trim('"')) }
                .orEmpty()
            val resolvedName = dispName.ifBlank { "gdrive_$fileId.mp4" }
            val fileName = applyCustomFileName(customFileName, resolvedName)
            val classified = classifyMediaByName(fileName, ct) ?: (MediaKind.VIDEO to false)
            val totalSize = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val itemKey = if (customFileName.isNullOrBlank()) {
                "gdrive_$fileId"
            } else {
                "gdrive_${fileId}_${sha256(customFileName).take(8)}"
            }
            mediaMap[itemKey] = buildMediaItem(
                id = itemKey,
                fileName = fileName,
                kind = classified.first,
                sourceType = SourceType.GDRIVE,
                mediaUrl = dlUrl,
                thumbnailUrl = null,
                sizeBytes = totalSize,
                folderPath = "/gdrive",
                isContainer = classified.second
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun discoverDirectOrWebFile(
        rawUrl: String,
        sourceType: SourceType,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        customFileName: String? = null
    ) {
        val urlWithoutQuery = rawUrl.substringBefore('?').substringBefore('#')
        val rawName = decodeUrlName(urlWithoutQuery.substringAfterLast('/'))
        val candidateName = applyCustomFileName(customFileName, rawName)
        val directClassified = classifyMediaByName(candidateName)

        if (directClassified != null) {
            val itemKey = sha256("$rawUrl|${customFileName.orEmpty()}")
            mediaMap[itemKey] = buildMediaItem(
                id = itemKey,
                fileName = candidateName,
                kind = directClassified.first,
                sourceType = sourceType,
                mediaUrl = rawUrl,
                thumbnailUrl = null,
                sizeBytes = 0L,
                folderPath = "/web",
                isContainer = directClassified.second
            )
            return
        }

        val conn = LocalStreamServer.openRemoteConnection(rawUrl)
        try {
            val ct = conn.contentType.orEmpty().lowercase()
            val disp = conn.getHeaderField("Content-Disposition").orEmpty()
            val dispName = Regex("""filename\*?=(?:UTF-8''|")?([^";\r\n]+)""", RegexOption.IGNORE_CASE)
                .find(disp)
                ?.groupValues
                ?.get(1)
                ?.let { decodeUrlName(it.trim('"')) }
                .orEmpty()

            if (ct.contains("text/html")) {
                val html = conn.inputStream.bufferedReader().readText()
                val extracted = extractMediaUrlFromHtml(html, rawUrl) ?: return
                val resolvedExtractedName = decodeUrlName(extracted.substringBefore('?').substringAfterLast('/'))
                    .ifBlank { dispName.ifBlank { "stream_media.mp4" } }
                val extractedName = applyCustomFileName(customFileName, resolvedExtractedName)
                val classified = classifyMediaByName(extractedName) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256("$extracted|${customFileName.orEmpty()}")
                mediaMap[itemKey] = buildMediaItem(
                    id = itemKey,
                    fileName = extractedName,
                    kind = classified.first,
                    sourceType = sourceType,
                    mediaUrl = extracted,
                    thumbnailUrl = null,
                    sizeBytes = 0L,
                    folderPath = "/web",
                    isContainer = classified.second,
                    headers = mapOf("Referer" to rawUrl)
                )
            } else {
                val resolvedName = dispName.ifBlank {
                    candidateName.ifBlank {
                        if (ct.startsWith("image/")) "image_stream.jpg" else "video_stream.mp4"
                    }
                }
                val finalName = applyCustomFileName(customFileName, resolvedName)
                val classified = classifyMediaByName(finalName, ct) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256("$rawUrl|${customFileName.orEmpty()}")
                mediaMap[itemKey] = buildMediaItem(
                    id = itemKey,
                    fileName = finalName,
                    kind = classified.first,
                    sourceType = sourceType,
                    mediaUrl = rawUrl,
                    thumbnailUrl = null,
                    sizeBytes = conn.contentLengthLong.coerceAtLeast(0L),
                    folderPath = "/web",
                    isContainer = classified.second
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractMediaUrlFromHtml(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""property=["']og:video(?::url|:secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""content=["']([^"']+)["'][^>]+property=["']og:video(?::url|:secure_url)?["']""", RegexOption.IGNORE_CASE),
            Regex("""["']?(?:file|source|video_url|download_url|stream_url)["']?\s*[:=]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""href=["'](https?://[^"']+\.(?:mp4|mkv|webm|mov|avi|m4v|3gp|ts|flv|wmv|ogv|vcv|icv|jpg|jpeg|png|webp)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
            Regex("""property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(html)
            if (m != null) {
                val raw = decodeHtmlEntities(m.groupValues[1].trim()).replace("\\/", "/")
                if (raw.isNotBlank() && !raw.startsWith("data:") && !raw.startsWith("blob:")) {
                    return runCatching { URL(URL(baseUrl), raw).toString() }.getOrNull()
                }
            }
        }
        return null
    }

    private fun parseGitHubUrl(rawUrl: String): GitHubLocation? {
        val clean = rawUrl.trim().removeSuffix("/")
        if (clean.contains("/releases/")) return null
        val regex = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)(?:/(?:tree|blob)/([^/]+)(?:/(.*))?)?$""")
        val m = regex.find(clean)
        if (m != null) {
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            val branch = m.groupValues[3].ifBlank { null }
            val subPath = m.groupValues[4].trim('/')
            return GitHubLocation(owner, repo, branch, subPath)
        }
        val directSubRegex = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/(.+)$""")
        val m2 = directSubRegex.find(clean) ?: return null
        val owner = m2.groupValues[1]
        val repo = m2.groupValues[2].removeSuffix(".git")
        val sub = m2.groupValues[3].trim('/')
        return GitHubLocation(owner, repo, null, sub)
    }

    private fun resolveDefaultBranch(owner: String, repo: String): String {
        val apiUrl = NativeCore.buildGitHubRepoApi(owner, repo)
        val body = httpGetText(apiUrl, isGitHubApi = true)
        if (body != null) {
            val defaultBranch = runCatching {
                JSONObject(body).optString("default_branch", "")
            }.getOrDefault("")
            if (defaultBranch.isNotBlank()) return defaultBranch
        }
        return "main"
    }

    private fun fetchGitHubFileText(url: String): String? {
        val loc = parseGitHubUrl(url)
        if (loc != null && loc.subPath.isNotBlank()) {
            val branches = if (loc.branch != null) {
                listOf(loc.branch)
            } else {
                listOf(resolveDefaultBranch(loc.owner, loc.repo), "main", "master").distinct()
            }
            for (br in branches) {
                val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, br, loc.subPath)
                val text = httpGetText(rawUrl, isGitHubApi = false)
                if (text != null) return text
            }
        }
        return httpGetText(url, isGitHubApi = false)
    }

    private fun buildRawGitHubUrl(owner: String, repo: String, branch: String, path: String): String {
        val encodedPath = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return NativeCore.buildRawGitHubUrl(owner, repo, branch, encodedPath)
    }

    private fun isThumbnailFolderPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith(".thumbnail/") ||
            lower.startsWith(".thumbnails/") ||
            lower.startsWith("root/.thumbnail/") ||
            lower.contains("/.thumbnail/") ||
            lower.contains("/.thumbnails/")
    }

    private fun isImageExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    private fun discoverGitHubSource(
        originalUrl: String,
        loc: GitHubLocation,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>,
        globalThumbPool: MutableMap<String, String>
    ) {
        val branch = loc.branch ?: resolveDefaultBranch(loc.owner, loc.repo)
        if (originalUrl.contains("/blob/") && loc.subPath.isNotBlank()) {
            val fName = loc.subPath.substringAfterLast('/')
            val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, branch, loc.subPath)
            if (isThumbnailFolderPath(loc.subPath)) {
                registerThumbnailCandidate(globalThumbPool, fName, rawUrl)
                return
            }
            val classified = classifyMediaByName(fName)
            if (classified != null) {
                val itemKey = sha256(rawUrl)
                mediaMap[itemKey] = buildMediaItem(
                    id = itemKey,
                    fileName = fName,
                    kind = classified.first,
                    sourceType = SourceType.GITHUB,
                    mediaUrl = rawUrl,
                    thumbnailUrl = null,
                    sizeBytes = 0L,
                    folderPath = "/${loc.subPath.substringBeforeLast('/', "")}",
                    isContainer = classified.second
                )
                return
            }
        }

        val treeUrl = NativeCore.buildGitHubTreeApi(loc.owner, loc.repo, branch)
        val jsonText = httpGetText(treeUrl, isGitHubApi = true) ?: return
        val rootObj = JSONObject(jsonText)
        val treeArr = rootObj.optJSONArray("tree") ?: return

        data class BlobEntry(val path: String, val size: Long)
        val blobs = mutableListOf<BlobEntry>()
        for (i in 0 until treeArr.length()) {
            val obj = treeArr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "blob") {
                val p = obj.optString("path")
                val s = obj.optLong("size", 0L)
                if (p.isNotBlank()) blobs.add(BlobEntry(p, s))
            }
        }

        val prefix = if (loc.subPath.isBlank()) "" else "${loc.subPath.trim('/')}/"
        val filtered = blobs.filter { prefix.isEmpty() || it.path.startsWith(prefix) }

        val thumbByTargetName = mutableMapOf<String, String>()
        for (b in filtered) {
            if (isThumbnailFolderPath(b.path)) {
                val fName = b.path.substringAfterLast('/')
                if (isImageExtension(fName)) {
                    val raw = buildRawGitHubUrl(loc.owner, loc.repo, branch, b.path)
                    registerThumbnailCandidate(thumbByTargetName, fName, raw)
                    registerThumbnailCandidate(globalThumbPool, fName, raw)
                }
            }
        }

        for (b in filtered) {
            if (isThumbnailFolderPath(b.path)) continue
            if (b.path.equals("icon.png", ignoreCase = true)) continue

            val fName = b.path.substringAfterLast('/')
            val dir = b.path.substringBeforeLast('/', "")
            val lower = fName.lowercase()

            val isPostPath = b.path.startsWith("post/", true) ||
                b.path.contains("/post/", true)
            if (isPostPath && isPostFile(lower)) {
                val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, branch, b.path)
                val postKey = "${loc.owner}/${loc.repo}/${b.path.lowercase()}"
                if (!postMap.containsKey(postKey)) {
                    val content = httpGetText(rawUrl, isGitHubApi = false)
                    if (!content.isNullOrBlank()) {
                        postMap[postKey] = buildInfoPost(postKey, fName, content, rawUrl)
                    }
                }
                continue
            }

            val classified = classifyMediaByName(fName) ?: continue
            val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, branch, b.path)
            val thumbUrl = if (classified.first == MediaKind.VIDEO) {
                resolveMultiUseThumbnail(fName, thumbByTargetName)
            } else {
                null
            }
            val itemKey = sha256(rawUrl)
            mediaMap[itemKey] = buildMediaItem(
                id = itemKey,
                fileName = fName,
                kind = classified.first,
                sourceType = SourceType.GITHUB,
                mediaUrl = rawUrl,
                thumbnailUrl = thumbUrl,
                sizeBytes = b.size,
                folderPath = if (dir.isEmpty()) "/" else "/$dir",
                isContainer = classified.second
            )
        }
    }

    private fun discoverGitHubPostsOnly(
        loc: GitHubLocation,
        postMap: MutableMap<String, InfoPost>
    ) {
        val branch = loc.branch ?: resolveDefaultBranch(loc.owner, loc.repo)
        val targetPath = loc.subPath.ifBlank { "post" }
        val contentsUrl = NativeCore.buildGitHubContentsApi(loc.owner, loc.repo, targetPath, branch)
        val jsonText = httpGetText(contentsUrl, isGitHubApi = true) ?: return
        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") != "file") continue
            val name = obj.optString("name")
            val path = obj.optString("path")
            if (!isPostFile(name.lowercase())) continue
            val postKey = "${loc.owner}/${loc.repo}/${path.lowercase()}"
            if (postMap.containsKey(postKey)) continue
            val downloadUrl = obj.optString("download_url").ifBlank {
                buildRawGitHubUrl(loc.owner, loc.repo, branch, path)
            }
            val content = httpGetText(downloadUrl, isGitHubApi = false)
            if (!content.isNullOrBlank()) {
                postMap[postKey] = buildInfoPost(postKey, name, content, downloadUrl)
            }
        }
    }

    private fun extractDriveId(url: String): String? {
        val patterns = listOf(
            Regex("""/folders/([a-zA-Z0-9_-]+)"""),
            Regex("""/file/d/([a-zA-Z0-9_-]+)"""),
            Regex("""[?&]id=([a-zA-Z0-9_-]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun discoverGoogleDriveRoot(
        rootFolderId: String,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>,
        globalThumbPool: MutableMap<String, String>
    ) {
        val thumbByTargetName = mutableMapOf<String, String>()
        val pendingVideos = mutableListOf<RemoteMediaItem>()
        discoverGoogleDriveFolder(
            folderId = rootFolderId,
            folderPath = "",
            depth = 0,
            thumbByTargetName = thumbByTargetName,
            globalThumbPool = globalThumbPool,
            pendingVideos = pendingVideos,
            mediaMap = mediaMap,
            postMap = postMap
        )
        for (v in pendingVideos) {
            val resolvedThumb = resolveMultiUseThumbnail(v.fileName, thumbByTargetName, v.title)
            mediaMap[v.id] = v.copy(thumbnailUrl = resolvedThumb)
        }
    }

    private fun discoverGoogleDriveFolder(
        folderId: String,
        folderPath: String,
        depth: Int,
        thumbByTargetName: MutableMap<String, String>,
        globalThumbPool: MutableMap<String, String>,
        pendingVideos: MutableList<RemoteMediaItem>,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>
    ) {
        if (depth > 4) return
        val embedUrl = NativeCore.buildDriveEmbedUrl(folderId)
        val html = httpGetText(embedUrl, isGitHubApi = false) ?: return

        val entryRegex = Regex(
            """<div class="flip-entry" id="entry-([^"]+)"[\s\S]*?<a href="([^"]+)"[\s\S]*?<div class="flip-entry-title">([^<]+)</div>"""
        )
        val subFolders = mutableListOf<Pair<String, String>>()
        val files = mutableListOf<Pair<String, String>>()

        for (match in entryRegex.findAll(html)) {
            val entryId = match.groupValues[1].trim()
            val href = match.groupValues[2].trim()
            val title = decodeHtmlEntities(match.groupValues[3].trim())
            if (href.contains("/folders/")) {
                val subId = extractDriveId(href) ?: entryId
                subFolders.add(subId to title)
            } else {
                val fileId = extractDriveId(href) ?: entryId
                files.add(fileId to title)
            }
        }

        val inThumbFolder = folderPath.endsWith("/.thumbnail", true) ||
            folderPath.equals(".thumbnail", true) ||
            folderPath.endsWith("/.thumbnails", true) ||
            folderPath.equals(".thumbnails", true)
        val inPostFolder = folderPath.endsWith("/post", true) || folderPath.equals("post", true)

        for ((fid, name) in files) {
            val lower = name.lowercase()
            val dlUrl = NativeCore.buildDriveDownloadUrl(fid)
            if (inThumbFolder && isImageExtension(name)) {
                registerThumbnailCandidate(thumbByTargetName, name, dlUrl)
                registerThumbnailCandidate(globalThumbPool, name, dlUrl)
                continue
            }
            if (inPostFolder && isPostFile(lower)) {
                val postKey = "gdrive/$fid"
                if (!postMap.containsKey(postKey)) {
                    val content = httpGetText(dlUrl, isGitHubApi = false)
                    if (!content.isNullOrBlank()) {
                        postMap[postKey] = buildInfoPost(postKey, name, content, dlUrl)
                    }
                }
                continue
            }
            val classified = classifyMediaByName(name) ?: continue
            val itemKey = "gdrive_$fid"
            val item = buildMediaItem(
                id = itemKey,
                fileName = name,
                kind = classified.first,
                sourceType = SourceType.GDRIVE,
                mediaUrl = dlUrl,
                thumbnailUrl = null,
                sizeBytes = 0L,
                folderPath = if (folderPath.isBlank()) "/gdrive" else folderPath,
                isContainer = classified.second
            )
            if (classified.first == MediaKind.VIDEO) {
                pendingVideos.add(item)
            } else {
                mediaMap[itemKey] = item
            }
        }

        for ((subId, subName) in subFolders) {
            val nextPath = if (folderPath.isBlank()) "/$subName" else "$folderPath/$subName"
            discoverGoogleDriveFolder(
                folderId = subId,
                folderPath = nextPath,
                depth = depth + 1,
                thumbByTargetName = thumbByTargetName,
                globalThumbPool = globalThumbPool,
                pendingVideos = pendingVideos,
                mediaMap = mediaMap,
                postMap = postMap
            )
        }
    }

    private fun isPostFile(lowerName: String): Boolean {
        if (lowerName.endsWith(".vcv") || lowerName.endsWith(".icv") || isImageExtension(lowerName)) {
            return false
        }
        return lowerName.endsWith(".bbcode") ||
            lowerName.endsWith(".bb") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".post") ||
            !lowerName.contains('.')
    }

    private fun buildInfoPost(id: String, fileName: String, rawContent: String, url: String): InfoPost {
        val titleTagRegex = Regex("""\[title](.*?)\[/title]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val titleMatch = titleTagRegex.find(rawContent)
        val extractedTitle = titleMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: formatTitle(fileName)
        val cleanedBody = if (titleMatch != null) {
            rawContent.replaceRange(titleMatch.range, "").trim()
        } else {
            rawContent.trim()
        }
        return InfoPost(
            id = id,
            title = extractedTitle,
            fileName = fileName,
            bbcodeContent = cleanedBody,
            sourceUrl = url
        )
    }

    private fun formatTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return base.replace('_', ' ').replace('-', ' ').trim().ifEmpty { fileName }
    }

    private fun decodeUrlName(raw: String): String {
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    suspend fun loadThumbnailBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            memoryThumbs[item.id]?.let { return@withContext it }

            val thumbKey = item.thumbnailUrl?.let { "thumb_${sha256(it)}" } ?: sha256(item.id)
            if (item.thumbnailUrl != null) {
                memoryThumbs[thumbKey]?.let { shared ->
                    memoryThumbs[item.id] = shared
                    return@withContext shared
                }
            }

            val thumbDir = File(context.cacheDir, "althub_thumbs").apply { mkdirs() }
            val cacheFile = File(thumbDir, "$thumbKey.png")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val diskBmp = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                if (diskBmp != null) {
                    memoryThumbs[item.id] = diskBmp
                    memoryThumbs[thumbKey] = diskBmp
                    return@withContext diskBmp
                }
            }

            if (item.thumbnailUrl != null) {
                val bytes = downloadBytes(item.thumbnailUrl, extraHeaders = item.headers)
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = decodeSampledBitmap(bytes)
                    if (bmp != null) {
                        memoryThumbs[item.id] = bmp
                        memoryThumbs[thumbKey] = bmp
                        saveBitmapCache(bmp, cacheFile)
                        return@withContext bmp
                    }
                }
            }

            if (item.kind == MediaKind.IMAGE) {
                val fullBmp = streamFullImageBitmap(item) ?: return@withContext null
                val scaled = scaleDown(fullBmp)
                memoryThumbs[item.id] = scaled
                saveBitmapCache(scaled, cacheFile)
                scaled
            } else {
                val localPath = item.localPath
                if (!localPath.isNullOrBlank()) {
                    val localFrame = extractLocalVideoFrame(localPath)
                    if (localFrame != null) {
                        val scaled = scaleDown(localFrame)
                        memoryThumbs[item.id] = scaled
                        saveBitmapCache(scaled, cacheFile)
                        return@withContext scaled
                    }
                }
                val logoFallback = getAltHubLogoBitmap(context)
                memoryThumbs[item.id] = logoFallback
                logoFallback
            }
        }

    private fun extractLocalVideoFrame(localPath: String): Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(localPath)
                mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun streamFullImageBitmap(item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val localPath = item.localPath
                if (!localPath.isNullOrBlank()) {
                    val localFile = File(localPath)
                    if (!localFile.exists()) return@runCatching null
                    BufferedInputStream(localFile.inputStream(), 65_536).use { bis ->
                        bis.mark(16)
                        val magic = ByteArray(4)
                        val n = bis.read(magic)
                        val mStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
                        bis.reset()
                        return@runCatching if (mStr == "SVCV" || mStr == "SICV") {
                            val info = LocalStreamServer.parseContainerHeaderFromStream(bis)
                            if (info.compression == 0) {
                                BitmapFactory.decodeStream(bis)
                            } else {
                                ZstdDecompressInputStream(bis).use { BitmapFactory.decodeStream(it) }
                            }
                        } else {
                            BitmapFactory.decodeStream(bis)
                        }
                    }
                }

                val conn = LocalStreamServer.openRemoteConnection(
                    startUrl = item.mediaUrl,
                    extraHeaders = item.headers
                )
                try {
                    BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                        bis.mark(16)
                        val magic = ByteArray(4)
                        val n = bis.read(magic)
                        val mStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
                        bis.reset()

                        if (mStr == "SVCV" || mStr == "SICV") {
                            val info = LocalStreamServer.parseContainerHeaderFromStream(bis)
                            if (info.compression == 0) {
                                BitmapFactory.decodeStream(bis)
                            } else {
                                ZstdDecompressInputStream(bis).use { zstdStream ->
                                    BitmapFactory.decodeStream(zstdStream)
                                }
                            }
                        } else {
                            BitmapFactory.decodeStream(bis)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= MAX_THUMB_DIM &&
            opts.outHeight / (sample * 2) >= MAX_THUMB_DIM
        ) {
            sample *= 2
        }
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return null
        return scaleDown(decoded)
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= MAX_THUMB_DIM && src.height <= MAX_THUMB_DIM) return src
        val ratio = MAX_THUMB_DIM.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun saveBitmapCache(bmp: Bitmap, dest: File) {
        runCatching {
            FileOutputStream(dest).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
        }
    }

    private fun httpGetText(
        urlStr: String,
        isGitHubApi: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        val bytes = downloadBytes(urlStr, isGitHubApi, extraHeaders) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun downloadBytes(
        urlStr: String,
        isGitHubApi: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): ByteArray? {
        return try {
            val conn = openConnectionFollowingRedirects(urlStr, isGitHubApi, extraHeaders) ?: return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32_768)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun openConnectionFollowingRedirects(
        startUrl: String,
        isGitHubApi: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
        hops: Int = 0
    ): HttpURLConnection? {
        if (hops > 6) return null
        val url = URL(startUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", NativeCore.getUserAgent())
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (isGitHubApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: return null
            val nextUrl = URL(url, location).toString()
            conn.disconnect()
            return openConnectionFollowingRedirects(nextUrl, isGitHubApi, extraHeaders, hops + 1)
        }
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        return conn
    }

    private fun decodeHtmlEntities(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}