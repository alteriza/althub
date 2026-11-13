package com.althub.alteriza

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object AutoDiscoveryRepository {
    private const val MAX_THUMB_DIM = 720
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif")

    private val memoryThumbs = ConcurrentHashMap<String, Bitmap>()

    private data class GitHubLocation(
        val owner: String,
        val repo: String,
        val branch: String?,
        val subPath: String
    )

    fun getCachedThumbnail(itemId: String): Bitmap? = memoryThumbs[itemId]

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
            when {
                isGoogleDriveUrl(src) -> {
                    val folderId = extractDriveId(src)
                    if (folderId != null) {
                        runCatching {
                            discoverGoogleDriveRoot(folderId, mediaMap, postMap)
                        }.onFailure {
                            errors.add("Drive ($folderId): ${it.message}")
                        }
                    }
                }
                isGitHubUrl(src) -> {
                    val loc = parseGitHubUrl(src)
                    if (loc != null) {
                        runCatching {
                            discoverGitHubRepo(loc, mediaMap, postMap)
                        }.onFailure {
                            errors.add("GitHub (${loc.owner}/${loc.repo}): ${it.message}")
                        }
                    }
                }
                src.endsWith(".vcv", true) || src.endsWith(".icv", true) -> {
                    val name = src.substringAfterLast('/').substringBefore('?')
                    val isVideo = name.endsWith(".vcv", true)
                    val baseDir = src.substringBeforeLast('/')
                    val thumb = if (isVideo) "$baseDir/.thumbnail/$name.png" else null
                    mediaMap[src] = RemoteMediaItem(
                        id = sha256(src),
                        title = formatTitle(name),
                        fileName = name,
                        kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                        sourceType = SourceType.GITHUB,
                        mediaUrl = src,
                        thumbnailUrl = thumb,
                        sizeBytes = 0L,
                        folderPath = "/"
                    )
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

    private fun isGitHubUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("github.com/") || l.contains("raw.githubusercontent.com/")
    }

    private fun isGoogleDriveUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("drive.google.com/") || l.contains("docs.google.com/")
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

    private fun discoverGitHubRepo(
        loc: GitHubLocation,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>
    ) {
        val branch = loc.branch ?: resolveDefaultBranch(loc.owner, loc.repo)
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

            if (lower.endsWith(".vcv") || lower.endsWith(".icv")) {
                val isVideo = lower.endsWith(".vcv")
                val rawUrl = buildRawGitHubUrl(loc.owner, loc.repo, branch, b.path)
                val thumbUrl = if (isVideo) {
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
                    kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                    sourceType = SourceType.GITHUB,
                    mediaUrl = rawUrl,
                    thumbnailUrl = thumbUrl,
                    sizeBytes = b.size,
                    folderPath = if (dir.isEmpty()) "/" else "/$dir"
                )
            }
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
            if (lower.endsWith(".vcv") || lower.endsWith(".icv")) {
                val isVideo = lower.endsWith(".vcv")
                val itemKey = "gdrive_$fid"
                val item = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(name),
                    fileName = name,
                    kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                    sourceType = SourceType.GDRIVE,
                    mediaUrl = dlUrl,
                    thumbnailUrl = null,
                    sizeBytes = 0L,
                    folderPath = if (folderPath.isBlank()) "/gdrive" else folderPath
                )
                if (isVideo) {
                    pendingVideos.add(item)
                } else {
                    mediaMap[itemKey] = item
                }
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

    suspend fun loadThumbnailBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            memoryThumbs[item.id]?.let { return@withContext it }

            val thumbDir = File(context.cacheDir, "althub_thumbs").apply { mkdirs() }
            val cacheFile = File(thumbDir, "${sha256(item.id)}.png")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val diskBmp = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                if (diskBmp != null) {
                    memoryThumbs[item.id] = diskBmp
                    return@withContext diskBmp
                }
            }

            if (item.thumbnailUrl != null) {
                val bytes = downloadBytes(item.thumbnailUrl)
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = decodeSampledBitmap(bytes)
                    if (bmp != null) {
                        memoryThumbs[item.id] = bmp
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
                val streamUrl = LocalStreamServer.getStreamUrl(item)
                val frame = extractVideoFrameFromUrl(streamUrl) ?: return@withContext null
                val scaled = scaleDown(frame)
                memoryThumbs[item.id] = scaled
                saveBitmapCache(scaled, cacheFile)
                scaled
            }
        }

    suspend fun streamFullImageBitmap(item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = LocalStreamServer.openRemoteConnection(item.mediaUrl)
                try {
                    BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                        val info = LocalStreamServer.parseContainerHeaderFromStream(bis)
                        if (info.compression == 0) {
                            BitmapFactory.decodeStream(bis)
                        } else {
                            ZstdDecompressInputStream(bis).use { zstdStream ->
                                BitmapFactory.decodeStream(zstdStream)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
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

    private fun httpGetText(urlStr: String, isGitHubApi: Boolean): String? {
        val bytes = downloadBytes(urlStr, isGitHubApi) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun downloadBytes(urlStr: String, isGitHubApi: Boolean = false): ByteArray? {
        return try {
            val conn = openConnectionFollowingRedirects(urlStr, isGitHubApi) ?: return null
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
        hops: Int = 0
    ): HttpURLConnection? {
        if (hops > 6) return null
        val url = URL(startUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", NativeCore.getUserAgent())
            if (isGitHubApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: return null
            val nextUrl = URL(url, location).toString()
            conn.disconnect()
            return openConnectionFollowingRedirects(nextUrl, isGitHubApi, hops + 1)
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
