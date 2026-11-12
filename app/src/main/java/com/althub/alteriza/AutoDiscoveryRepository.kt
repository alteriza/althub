package com.althub.alteriza

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object AutoDiscoveryRepository {
    const val MAIN_CDN_URL = "https://github.com/alteriza/althub/cdn.txt"
    const val MAIN_POST_URL = "https://github.com/alteriza/althub/post/"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AltHub/1.0"
    private const val MAX_THUMB_DIM = 720

    private data class GitHubLocation(
        val owner: String,
        val repo: String,
        val branch: String?,
        val subPath: String
    )

    suspend fun discoverAll(): DiscoveryResult = withContext(Dispatchers.IO) {
        val mediaMap = linkedMapOf<String, RemoteMediaItem>()
        val postMap = linkedMapOf<String, InfoPost>()
        val activeSources = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val cdnRaw = fetchGitHubFileText(MAIN_CDN_URL)
        val rawLines = cdnRaw
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .orEmpty()

        val sourceUrls = if (rawLines.isNotEmpty()) {
            rawLines
        } else {
            listOf("https://github.com/alteriza/althub")
        }

        for (src in sourceUrls) {
            activeSources.add(src)
            when {
                isGoogleDriveUrl(src) -> {
                    val folderId = extractDriveId(src)
                    if (folderId != null) {
                        runCatching {
                            discoverGoogleDriveFolder(folderId, "", 0, mediaMap, postMap)
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
                    val thumb = if (isVideo) "$baseDir/thumb_$name.png" else null
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

        val mainPostLoc = parseGitHubUrl(MAIN_POST_URL)
        if (mainPostLoc != null) {
            runCatching {
                discoverGitHubPostsOnly(mainPostLoc, postMap)
            }.onFailure {
                errors.add("Post: ${it.message}")
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
        val apiUrl = "https://api.github.com/repos/$owner/$repo"
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
        return "https://raw.githubusercontent.com/$owner/$repo/$branch/$encodedPath"
    }

    private fun discoverGitHubRepo(
        loc: GitHubLocation,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>
    ) {
        val branch = loc.branch ?: resolveDefaultBranch(loc.owner, loc.repo)
        val treeUrl = "https://api.github.com/repos/${loc.owner}/${loc.repo}/git/trees/$branch?recursive=1"
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

        val thumbByFullPath = mutableMapOf<String, String>()
        val thumbByName = mutableMapOf<String, String>()
        for (b in filtered) {
            val fName = b.path.substringAfterLast('/')
            if (fName.startsWith("thumb_", true) && fName.endsWith(".png", true)) {
                val raw = buildRawGitHubUrl(loc.owner, loc.repo, branch, b.path)
                thumbByFullPath[b.path.lowercase()] = raw
                thumbByName[fName.lowercase()] = raw
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
                    val exactKey = if (dir.isEmpty()) "thumb_$lower.png" else "$dir/thumb_$lower.png".lowercase()
                    val baseNoExt = lower.substringBeforeLast('.')
                    val altKey = if (dir.isEmpty()) "thumb_$baseNoExt.png" else "$dir/thumb_$baseNoExt.png".lowercase()
                    thumbByFullPath[exactKey]
                        ?: thumbByName["thumb_$lower.png"]
                        ?: thumbByFullPath[altKey]
                        ?: thumbByName["thumb_$baseNoExt.png"]
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
        val contentsUrl = "https://api.github.com/repos/${loc.owner}/${loc.repo}/contents/$targetPath?ref=$branch"
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

    private fun buildDriveDownloadUrl(fileId: String): String {
        return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
    }

    private fun discoverGoogleDriveFolder(
        folderId: String,
        folderPath: String,
        depth: Int,
        mediaMap: MutableMap<String, RemoteMediaItem>,
        postMap: MutableMap<String, InfoPost>
    ) {
        if (depth > 4) return
        val embedUrl = "https://drive.google.com/embeddedfolderview?id=$folderId#list"
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

        val thumbByName = mutableMapOf<String, String>()
        for ((fid, name) in files) {
            if (name.startsWith("thumb_", true) && name.endsWith(".png", true)) {
                thumbByName[name.lowercase()] = buildDriveDownloadUrl(fid)
            }
        }

        val inPostFolder = folderPath.endsWith("/post", true) || folderPath.equals("post", true)
        for ((fid, name) in files) {
            val lower = name.lowercase()
            val dlUrl = buildDriveDownloadUrl(fid)
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
                val thumbUrl = if (isVideo) {
                    val baseNoExt = lower.substringBeforeLast('.')
                    thumbByName["thumb_$lower.png"] ?: thumbByName["thumb_$baseNoExt.png"]
                } else {
                    null
                }
                val itemKey = "gdrive_$fid"
                mediaMap[itemKey] = RemoteMediaItem(
                    id = itemKey,
                    title = formatTitle(name),
                    fileName = name,
                    kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                    sourceType = SourceType.GDRIVE,
                    mediaUrl = dlUrl,
                    thumbnailUrl = thumbUrl,
                    sizeBytes = 0L,
                    folderPath = if (folderPath.isBlank()) "/gdrive" else folderPath
                )
            }
        }

        for ((subId, subName) in subFolders) {
            val nextPath = if (folderPath.isBlank()) "/$subName" else "$folderPath/$subName"
            discoverGoogleDriveFolder(subId, nextPath, depth + 1, mediaMap, postMap)
        }
    }

    private fun isPostFile(lowerName: String): Boolean {
        if (lowerName.endsWith(".vcv") || lowerName.endsWith(".icv") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg")) {
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
            val thumbDir = File(context.cacheDir, "althub_thumbs").apply { mkdirs() }
            val cacheFile = File(thumbDir, "${sha256(item.id)}.png")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                return@withContext runCatching {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                }.getOrNull()
            }

            if (item.thumbnailUrl != null) {
                val bytes = downloadBytes(item.thumbnailUrl)
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = decodeSampledBitmap(bytes)
                    if (bmp != null) {
                        saveBitmapCache(bmp, cacheFile)
                        return@withContext bmp
                    }
                }
            }

            val containerFile = ensureContainerCached(context, item) ?: return@withContext null
            if (item.kind == MediaKind.IMAGE) {
                val rawBytes = NativeCore.decodeToBytes(containerFile.absolutePath) ?: return@withContext null
                val bmp = decodeSampledBitmap(rawBytes) ?: return@withContext null
                saveBitmapCache(bmp, cacheFile)
                bmp
            } else {
                val prepared = prepareVideoForPlayback(context, item) ?: return@withContext null
                val frame = extractVideoFrame(prepared.file.absolutePath) ?: return@withContext null
                val scaled = scaleDown(frame)
                saveBitmapCache(scaled, cacheFile)
                scaled
            }
        }

    suspend fun loadFullImageBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
        withContext(Dispatchers.IO) {
            val containerFile = ensureContainerCached(context, item) ?: return@withContext null
            val rawBytes = NativeCore.decodeToBytes(containerFile.absolutePath) ?: return@withContext null
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        }

    suspend fun prepareVideoForPlayback(context: Context, item: RemoteMediaItem): PreparedVideo? =
        withContext(Dispatchers.IO) {
            val containerFile = ensureContainerCached(context, item) ?: return@withContext null
            val decodedDir = File(context.cacheDir, "althub_decoded").apply { mkdirs() }
            val headerJson = NativeCore.readHeader(containerFile.absolutePath)
            val ext = runCatching {
                JSONObject(headerJson ?: "{}").optString("original_ext", "")
            }.getOrDefault("").ifBlank { "mp4" }
            val outFile = File(decodedDir, "${sha256(item.id)}.$ext")
            if (!outFile.exists() || outFile.length() == 0L) {
                val rc = NativeCore.decodeToFile(containerFile.absolutePath, outFile.absolutePath)
                if (rc != 0 || !outFile.exists() || outFile.length() == 0L) {
                    outFile.delete()
                    return@withContext null
                }
            }
            val dims = probeVideoDimensions(outFile.absolutePath)
            PreparedVideo(file = outFile, width = dims.first, height = dims.second)
        }

    private fun probeVideoDimensions(path: String): Pair<Int, Int> {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                val rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
                val rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) rawH to rawW else rawW to rawH
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            16 to 9
        }
    }

    private fun ensureContainerCached(context: Context, item: RemoteMediaItem): File? {
        val rawDir = File(context.cacheDir, "althub_containers").apply { mkdirs() }
        val ext = if (item.kind == MediaKind.VIDEO) "vcv" else "icv"
        val target = File(rawDir, "${sha256(item.mediaUrl)}.$ext")
        if (target.exists() && target.length() > 0L) {
            return target
        }
        val tmp = File(rawDir, "${sha256(item.mediaUrl)}_${System.currentTimeMillis()}.tmp")
        val ok = downloadToFile(item.mediaUrl, tmp)
        if (!ok || !tmp.exists() || tmp.length() == 0L) {
            tmp.delete()
            return null
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return target
    }

    private fun extractVideoFrame(path: String): Bitmap? {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
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

    private fun downloadToFile(urlStr: String, destFile: File): Boolean {
        return try {
            val conn = openConnectionFollowingRedirects(urlStr, false) ?: return false
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            true
        } catch (_: Throwable) {
            false
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
            setRequestProperty("User-Agent", USER_AGENT)
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
