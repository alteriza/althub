package com.althub.alteriza

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object AutoDiscoveryRepository {
    private const val MAX_THUMB_DIM = 720
    private val VIDEO_EXTS = setOf("vcv", "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
    private val IMAGE_EXTS = setOf("icv", "png", "jpg", "jpeg", "webp", "gif", "bmp")

    private val memoryThumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failedThumbs = ConcurrentHashMap.newKeySet<String>()
    private val thumbSemaphore = Semaphore(2)

    @Volatile
    private var cachedGofileToken: String? = null

    private data class GitHubLocation(
        val owner: String,
        val repo: String,
        val branch: String?,
        val subPath: String
    )

    fun getCachedThumbnail(itemId: String): Bitmap? = memoryThumbs.get(itemId)

    fun clearMemoryCache() {
        memoryThumbs.evictAll()
        failedThumbs.clear()
    }

    fun clearFailedThumbCache() {
        failedThumbs.clear()
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
        val mediaMap = linkedMapOf<String, RemoteMediaItem>()
        val postMap = linkedMapOf<String, InfoPost>()
        val activeSources = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val cdnRaw = fetchGitHubFileText(cdnUrl)
        val rawLines = cdnRaw
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .orEmpty()

        val sourceUrls = when {
            rawLines.isNotEmpty() -> rawLines
            fallbackRepoUrl != null -> listOf(fallbackRepoUrl)
            else -> emptyList()
        }

        for (src in sourceUrls) {
            activeSources.add(src)
            val hostJson = NativeCore.resolveHostEndpoint(src)
            val hostObj = runCatching { JSONObject(hostJson) }.getOrNull()
            val hostType = hostObj?.optString("host_type", "direct") ?: "direct"
            val resolvedUrl = hostObj?.optString("resolved_url", src)?.ifBlank { src } ?: src
            val fileId = hostObj?.optString("file_id", "") ?: ""

            when (hostType) {
                "gdrive_folder" -> {
                    val folderId = extractDriveId(src)
                    if (folderId != null) {
                        runCatching {
                            discoverGoogleDriveRoot(folderId, mediaMap, postMap)
                        }.onFailure {
                            errors.add("Drive ($folderId): ${it.message}")
                        }
                    }
                }
                "gdrive_file" -> {
                    val fid = extractDriveId(src)
                    if (fid != null) {
                        runCatching {
                            discoverSingleDriveFile(fid, mediaMap)
                        }.onFailure {
                            errors.add("DriveFile ($fid): ${it.message}")
                        }
                    }
                }
                "github" -> {
                    val loc = parseGitHubUrl(src)
                    if (loc != null) {
                        runCatching {
                            discoverGitHubSource(src, loc, mediaMap, postMap)
                        }.onFailure {
                            errors.add("GitHub (${loc.owner}/${loc.repo}): ${it.message}")
                        }
                    } else {
                        runCatching {
                            discoverDirectOrWebFile(src, SourceType.GITHUB, mediaMap)
                        }
                    }
                }
                "pixeldrain" -> {
                    runCatching {
                        discoverPixeldrainFile(fileId, resolvedUrl, mediaMap)
                    }.onFailure {
                        errors.add("Pixeldrain ($fileId): ${it.message}")
                    }
                }
                "pixeldrain_list" -> {
                    runCatching {
                        discoverPixeldrainList(resolvedUrl, mediaMap)
                    }.onFailure {
                        errors.add("PixeldrainList ($fileId): ${it.message}")
                    }
                }
                "gofile" -> {
                    runCatching {
                        discoverGofileContent(resolvedUrl, mediaMap)
                    }.onFailure {
                        errors.add("Gofile ($fileId): ${it.message}")
                    }
                }
                "acefile" -> {
                    runCatching {
                        discoverAcefilePage(src, fileId, mediaMap)
                    }.onFailure {
                        errors.add("Acefile ($fileId): ${it.message}")
                    }
                }
                "filedom" -> {
                    runCatching {
                        discoverDirectOrWebFile(src, SourceType.FILEDOM, mediaMap)
                    }.onFailure {
                        errors.add("Filedom: ${it.message}")
                    }
                }
                else -> {
                    runCatching {
                        discoverDirectOrWebFile(src, SourceType.DIRECT, mediaMap)
                    }.onFailure {
                        errors.add("Direct: ${it.message}")
                    }
                }
            }
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

        DiscoveryResult(
            mediaItems = mediaMap.values.sortedByDescending { it.fileName.lowercase() },
            infoPosts = postMap.values.sortedByDescending { it.fileName.lowercase() },
            activeSources = activeSources,
            errorMessage = if (mediaMap.isEmpty() && postMap.isEmpty() && errors.isNotEmpty()) {
                errors.joinToString(" | ")
            } else {
                null
            }
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

    private fun discoverPixeldrainFile(
        fileId: String,
        streamUrl: String,
        mediaMap: MutableMap<String, RemoteMediaItem>
    ) {
        val infoUrl = "$streamUrl/info"
        val infoJson = httpGetText(infoUrl, isGitHubApi = false)
        val obj = infoJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val name = obj?.optString("name", "")?.ifBlank { null } ?: "$fileId.mp4"
        val size = obj?.optLong("size", 0L) ?: 0L
        val mime = obj?.optString("mime_type", "") ?: ""
        val classified = classifyMediaByName(name, mime) ?: (MediaKind.VIDEO to false)
        val itemKey = sha256(streamUrl)
        val thumb = if (classified.first == MediaKind.VIDEO) "$streamUrl/thumbnail" else null
        mediaMap[itemKey] = RemoteMediaItem(
            id = itemKey,
            title = formatTitle(name),
            fileName = name,
            kind = classified.first,
            sourceType = SourceType.PIXELDRAIN,
            mediaUrl = streamUrl,
            thumbnailUrl = thumb,
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
            val thumb = if (classified.first == MediaKind.VIDEO) "$streamUrl/thumbnail" else null
            mediaMap[itemKey] = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(name),
                fileName = name,
                kind = classified.first,
                sourceType = SourceType.PIXELDRAIN,
                mediaUrl = streamUrl,
                thumbnailUrl = thumb,
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
        val headers = mapOf("Authorization" to "Bearer $token")
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
            mediaMap[itemKey] = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(name),
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
        mediaMap: MutableMap<String, RemoteMediaItem>
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
                val finalName = when {
                    slugName.contains('.') -> decodeUrlName(slugName)
                    rawTitle.isNotBlank() -> "$rawTitle.mp4"
                    else -> "acefile_${fileId.ifEmpty { "stream" }}.mp4"
                }
                val classified = classifyMediaByName(finalName) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256(direct)
                mediaMap[itemKey] = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(finalName),
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
        mediaMap: MutableMap<String, RemoteMediaItem>
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
            val fileName = dispName.ifBlank { "gdrive_$fileId.mp4" }
            val classified = classifyMediaByName(fileName, ct) ?: (MediaKind.VIDEO to false)
            val totalSize = conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val itemKey = "gdrive_$fileId"
            mediaMap[itemKey] = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(fileName),
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
        mediaMap: MutableMap<String, RemoteMediaItem>
    ) {
        val urlWithoutQuery = rawUrl.substringBefore('?').substringBefore('#')
        val candidateName = decodeUrlName(urlWithoutQuery.substringAfterLast('/'))
        val directClassified = classifyMediaByName(candidateName)

        if (directClassified != null) {
            val itemKey = sha256(rawUrl)
            mediaMap[itemKey] = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(candidateName),
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
                val extractedName = decodeUrlName(extracted.substringBefore('?').substringAfterLast('/'))
                    .ifBlank { dispName.ifBlank { "stream_media.mp4" } }
                val classified = classifyMediaByName(extractedName) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256(extracted)
                mediaMap[itemKey] = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(extractedName),
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
                val finalName = dispName.ifBlank {
                    candidateName.ifBlank {
                        if (ct.startsWith("image/")) "image_stream.jpg" else "video_stream.mp4"
                    }
                }
                val classified = classifyMediaByName(finalName, ct) ?: (MediaKind.VIDEO to false)
                val itemKey = sha256(rawUrl)
                mediaMap[itemKey] = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(finalName),
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
            Regex("""href=["'](https?://[^"']+\.(?:mp4|mkv|webm|mov|vcv|icv|jpg|jpeg|png|webp)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
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
            lower.startsWith("root/.thumbnail/") ||
            lower.contains("/.thumbnail/")
    }

    private fun isImageExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    private fun discoverGitHubSource(
        originalUrl: String,
        loc: GitHubLocation,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>
    ) {
        val branch = loc.branch ?: resolveDefaultBranch(loc.owner, loc.repo)
        if (originalUrl.contains("/blob/") && loc.subPath.isNotBlank()) {
            val fName = loc.subPath.substringAfterLast('/')
            val classified = classifyMediaByName(fName)
            if (classified != null) {
                val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, branch, loc.subPath)
                val itemKey = sha256(rawUrl)
                mediaMap[itemKey] = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(fName),
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
                    val targetKey = fName.substringBeforeLast('.').lowercase()
                    thumbByTargetName[targetKey] = raw
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
                val baseNoExt = lower.substringBeforeLast('.')
                thumbByTargetName[lower] ?: thumbByTargetName[baseNoExt]
            } else {
                null
            }
            val itemKey = sha256(rawUrl)
            mediaMap[itemKey] = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(fName),
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
        postMap: MutableMap<String, InfoPost>
    ) {
        val thumbByTargetName = mutableMapOf<String, String>()
        val pendingVideos = mutableListOf<RemoteMediaItem>()
        discoverGoogleDriveFolder(
            folderId = rootFolderId,
            folderPath = "",
            depth = 0,
            thumbByTargetName = thumbByTargetName,
            pendingVideos = pendingVideos,
            mediaMap = mediaMap,
            postMap = postMap
        )
        for (v in pendingVideos) {
            val lower = v.fileName.lowercase()
            val baseNoExt = lower.substringBeforeLast('.')
            val resolvedThumb = thumbByTargetName[lower] ?: thumbByTargetName[baseNoExt]
            mediaMap[v.id] = v.copy(thumbnailUrl = resolvedThumb)
        }
    }

    private fun discoverGoogleDriveFolder(
        folderId: String,
        folderPath: String,
        depth: Int,
        thumbByTargetName: MutableMap<String, String>,
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

        val inThumbFolder = folderPath.endsWith("/.thumbnail", true) || folderPath.equals(".thumbnail", true)
        val inPostFolder = folderPath.endsWith("/post", true) || folderPath.equals("post", true)

        for ((fid, name) in files) {
            val lower = name.lowercase()
            val dlUrl = NativeCore.buildDriveDownloadUrl(fid)
            if (inThumbFolder && isImageExtension(name)) {
                val targetKey = name.substringBeforeLast('.').lowercase()
                thumbByTargetName[targetKey] = dlUrl
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
            val item = RemoteMediaItem(
                id = itemKey,
                title = formatTitle(name),
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

    fun decodeLocalMediaBitmap(file: File): Bitmap? {
        return runCatching {
            BufferedInputStream(FileInputStream(file), 65_536).use { bis ->
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
        }.getOrNull()
    }

    suspend fun loadBestImageBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            val localFile = AppStorageManager.findDownloadedFile(context, item)
            if (localFile != null) {
                decodeLocalMediaBitmap(localFile)?.let { return@withContext it }
            }
            streamFullImageBitmap(item)
        }

    suspend fun loadThumbnailBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            memoryThumbs.get(item.id)?.let { return@withContext it }
            if (failedThumbs.contains(item.id)) return@withContext null

            val thumbDir = File(context.cacheDir, "althub_thumbs").apply { mkdirs() }
            val cacheFile = File(thumbDir, "${sha256(item.id)}.png")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val diskBmp = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                if (diskBmp != null) {
                    memoryThumbs.put(item.id, diskBmp)
                    return@withContext diskBmp
                }
            }

            thumbSemaphore.withPermit {
                memoryThumbs.get(item.id)?.let { return@withPermit it }
                if (failedThumbs.contains(item.id)) return@withPermit null
                if (cacheFile.exists() && cacheFile.length() > 0L) {
                    val diskBmp = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                    if (diskBmp != null) {
                        memoryThumbs.put(item.id, diskBmp)
                        return@withPermit diskBmp
                    }
                }

                val localFile = AppStorageManager.findDownloadedFile(context, item)
                if (localFile != null) {
                    if (item.kind == MediaKind.IMAGE) {
                        val localBmp = decodeLocalMediaBitmap(localFile)
                        if (localBmp != null) {
                            val scaled = scaleDown(localBmp)
                            memoryThumbs.put(item.id, scaled)
                            saveBitmapCache(scaled, cacheFile)
                            return@withPermit scaled
                        }
                    } else {
                        val localFrame = if (localFile.name.endsWith(".vcv", ignoreCase = true)) {
                            extractVideoFrameFromUrl(LocalStreamServer.getLocalStreamUrl(item, localFile))
                        } else {
                            extractVideoFrameFromFile(localFile)
                        }
                        if (localFrame != null) {
                            val scaled = scaleDown(localFrame)
                            memoryThumbs.put(item.id, scaled)
                            saveBitmapCache(scaled, cacheFile)
                            return@withPermit scaled
                        }
                    }
                }

                if (item.thumbnailUrl != null) {
                    val bytes = downloadBytes(item.thumbnailUrl, extraHeaders = item.headers)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = decodeSampledBitmap(bytes)
                        if (bmp != null) {
                            memoryThumbs.put(item.id, bmp)
                            saveBitmapCache(bmp, cacheFile)
                            return@withPermit bmp
                        }
                    }
                }

                if (item.kind == MediaKind.IMAGE) {
                    val fullBmp = streamFullImageBitmap(item)
                    if (fullBmp == null) {
                        failedThumbs.add(item.id)
                        return@withPermit null
                    }
                    val scaled = scaleDown(fullBmp)
                    memoryThumbs.put(item.id, scaled)
                    saveBitmapCache(scaled, cacheFile)
                    scaled
                } else {
                    val streamUrl = LocalStreamServer.getStreamUrl(item)
                    val frame = extractVideoFrameFromUrl(streamUrl)
                    if (frame == null) {
                        failedThumbs.add(item.id)
                        return@withPermit null
                    }
                    val scaled = scaleDown(frame)
                    memoryThumbs.put(item.id, scaled)
                    saveBitmapCache(scaled, cacheFile)
                    scaled
                }
            }
        }

    suspend fun streamFullImageBitmap(item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
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

    private fun extractVideoFrameFromFile(file: File): Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractVideoFrameFromUrl(streamUrl: String): Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(streamUrl, HashMap())
                mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            null
        }
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
