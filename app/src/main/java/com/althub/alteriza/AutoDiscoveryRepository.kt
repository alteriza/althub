package com.althub.alteriza

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AutoDiscoveryRepository {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val VIDEO_EXTS = setOf(
        "vcv", "mp4", "mkv", "webm", "mov", "avi", "m4v",
        "3gp", "ts", "mts", "m2ts", "flv", "wmv", "ogv", "mpeg", "mpg"
    )
    private val IMAGE_EXTS = setOf(
        "icv", "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif"
    )
    private val CONTAINER_EXTS = setOf("vcv", "icv")

    private val memoryCache = ConcurrentHashMap<String, Pair<Long, CdnDiscoveryResult>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    fun clearMemoryCache() {
        memoryCache.clear()
    }

    suspend fun discoverFromCdn(
        cdnUrl: String,
        forceRefresh: Boolean = false
    ): Result<CdnDiscoveryResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCdnUrl = cdnUrl.trim()
            if (!forceRefresh) {
                memoryCache[cleanCdnUrl]?.let { (cachedAt, cachedResult) ->
                    if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                        return@runCatching cachedResult
                    }
                }
            }

            val rawManifest = fetchText(cleanCdnUrl)
            val lines = rawManifest.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

            val sourceUrls = mutableListOf<String>()
            val announcements = mutableListOf<String>()
            val bbBuffer = StringBuilder()
            var insideBbBlock = false

            for (line in lines) {
                when {
                    line.equals("[ANNOUNCEMENT]", ignoreCase = true) ||
                        line.equals("[INFO]", ignoreCase = true) -> {
                        insideBbBlock = true
                    }
                    line.equals("[/ANNOUNCEMENT]", ignoreCase = true) ||
                        line.equals("[/INFO]", ignoreCase = true) -> {
                        insideBbBlock = false
                        val text = bbBuffer.toString().trim()
                        if (text.isNotEmpty()) announcements.add(text)
                        bbBuffer.clear()
                    }
                    insideBbBlock -> {
                        if (bbBuffer.isNotEmpty()) bbBuffer.append('\n')
                        bbBuffer.append(line)
                    }
                    line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true) -> {
                        sourceUrls.add(line)
                    }
                    else -> {
                        announcements.add(line)
                    }
                }
            }
            if (bbBuffer.isNotEmpty()) {
                announcements.add(bbBuffer.toString().trim())
            }

            // PASS 1: Crawl semua sumber secara paralel dan kumpulkan seluruh aset
            //         dari folder .thumbnail/ (GitHub & Google Drive) ke Global Thumbnail Pool
            val globalThumbPool = ConcurrentHashMap<String, String>()
            val perSourceLists = coroutineScope {
                sourceUrls.distinct().map { srcUrl ->
                    async {
                        runCatching {
                            crawlSingleSource(srcUrl, globalThumbPool)
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }

            val mergedRaw = perSourceLists.flatten()
                .distinctBy { "${it.sourceType.name}|${it.mediaUrl}" }

            // PASS 2: Hybrid Thumbnail Matching untuk video/media non-hosted
            //         (Pixeldrain, Gofile, Filedon, Acefile, Direct, maupun custom cover YouTube)
            val finalItems = mergedRaw.map { item ->
                val customPoolThumb = resolveHybridThumbnail(item, globalThumbPool)
                when {
                    customPoolThumb != null -> item.copy(thumbnailUrl = customPoolThumb)
                    !item.thumbnailUrl.isNullOrBlank() -> item
                    else -> item
                }
            }

            val result = CdnDiscoveryResult(
                items = finalItems,
                announcements = announcements,
                activeSources = sourceUrls.distinct().size
            )
            memoryCache[cleanCdnUrl] = System.currentTimeMillis() to result
            result
        }
    }

    private fun crawlSingleSource(
        rawUrl: String,
        globalThumbPool: MutableMap<String, String>
    ): List<RemoteMediaItem> {
        val resolvedJson = runCatching { NativeCore.resolveHostEndpoint(rawUrl) }.getOrNull()
        val hostTag = if (!resolvedJson.isNullOrBlank()) {
            runCatching { JSONObject(resolvedJson).optString("host") }.getOrDefault("")
        } else {
            ""
        }
        val lower = rawUrl.lowercase()

        return when {
            hostTag == "youtube" || lower.contains("youtube.com") || lower.contains("youtu.be") -> {
                listOfNotNull(discoverYouTubeVideo(rawUrl))
            }
            hostTag == "github" || lower.contains("github.com") || lower.contains("raw.githubusercontent.com") -> {
                discoverGitHubSource(rawUrl, globalThumbPool)
            }
            hostTag == "gdrive" || lower.contains("drive.google.com") || lower.contains("docs.google.com") -> {
                discoverGoogleDriveSource(rawUrl, globalThumbPool)
            }
            hostTag == "pixeldrain" || lower.contains("pixeldrain.com") -> {
                discoverPixeldrainSource(rawUrl)
            }
            hostTag == "gofile" || lower.contains("gofile.io") -> {
                discoverGofileSource(rawUrl)
            }
            hostTag == "acefile" || lower.contains("acefile.co") -> {
                listOfNotNull(discoverGenericHostedFile(rawUrl, SourceType.ACEFILE))
            }
            hostTag == "filedom" || lower.contains("filedom.") -> {
                listOfNotNull(discoverGenericHostedFile(rawUrl, SourceType.FILEDOM))
            }
            else -> {
                listOfNotNull(discoverDirectFile(rawUrl))
            }
        }
    }

    private fun discoverGitHubSource(
        url: String,
        globalThumbPool: MutableMap<String, String>
    ): List<RemoteMediaItem> {
        if (url.contains("raw.githubusercontent.com", ignoreCase = true)) {
            val fileName = extractFileNameFromUrl(url)
            if (isInsideThumbnailDir(url)) {
                registerThumbnailCandidate(globalThumbPool, fileName, url)
                return emptyList()
            }
            return listOfNotNull(buildItemFromNameAndUrl(fileName, url, SourceType.GITHUB))
        }

        val repoMatch = Regex("""github\.com/([^/]+)/([^/#?]+)(?:/(?:tree|blob)/([^/]+)(?:/(.*))?)?""")
            .find(url) ?: return listOfNotNull(discoverDirectFile(url))

        val owner = repoMatch.groupValues[1]
        val repo = repoMatch.groupValues[2].removeSuffix(".git")
        val branchHint = repoMatch.groupValues[3].ifBlank { "" }
        val subPath = repoMatch.groupValues[4].trim('/')

        if (url.contains("/blob/")) {
            val branch = branchHint.ifBlank { "main" }
            val rawFileUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$subPath"
            val fileName = extractFileNameFromUrl(subPath)
            if (isInsideThumbnailDir(subPath)) {
                registerThumbnailCandidate(globalThumbPool, fileName, rawFileUrl)
                return emptyList()
            }
            return listOfNotNull(buildItemFromNameAndUrl(fileName, rawFileUrl, SourceType.GITHUB))
        }

        val branchesToTry = if (branchHint.isNotBlank()) listOf(branchHint) else listOf("main", "master")
        for (branch in branchesToTry) {
            val apiUrl = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
            val body = runCatching {
                fetchText(apiUrl, mapOf("Accept" to "application/vnd.github+json"))
            }.getOrNull() ?: continue

            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val tree = root.optJSONArray("tree") ?: continue

            val localThumbs = mutableMapOf<String, String>()
            val pendingMedia = mutableListOf<Pair<String, String>>()

            for (i in 0 until tree.length()) {
                val entry = tree.optJSONObject(i) ?: continue
                if (entry.optString("type") != "blob") continue
                val path = entry.optString("path")
                if (subPath.isNotEmpty() && !path.startsWith("$subPath/")) continue

                val fileName = path.substringAfterLast('/')
                val encodedPath = path.split('/').joinToString("/") { segment ->
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                }
                val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$encodedPath"

                if (isInsideThumbnailDir(path)) {
                    registerThumbnailCandidate(localThumbs, fileName, rawUrl)
                    registerThumbnailCandidate(globalThumbPool, fileName, rawUrl)
                } else {
                    pendingMedia.add(fileName to rawUrl)
                }
            }

            return pendingMedia.mapNotNull { (fileName, rawUrl) ->
                val base = buildItemFromNameAndUrl(fileName, rawUrl, SourceType.GITHUB) ?: return@mapNotNull null
                val matchedThumb = resolveHybridThumbnail(base, localThumbs)
                if (matchedThumb != null) base.copy(thumbnailUrl = matchedThumb) else base
            }
        }
        return emptyList()
    }

    private fun discoverGoogleDriveSource(
        url: String,
        globalThumbPool: MutableMap<String, String>
    ): List<RemoteMediaItem> {
        val folderId = Regex("""/folders/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        if (!folderId.isNullOrBlank()) {
            return crawlGoogleDriveFolder(folderId, globalThumbPool, depth = 0)
        }

        val fileId = Regex("""(?:/d/|[?&]id=)([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return emptyList()
        val directDownload = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        val probedName = probeRemoteFileName(directDownload) ?: "gdrive_$fileId.mp4"

        if (isInsideThumbnailDir(probedName)) {
            registerThumbnailCandidate(globalThumbPool, probedName, directDownload)
            return emptyList()
        }
        return listOfNotNull(buildItemFromNameAndUrl(probedName, directDownload, SourceType.GDRIVE))
    }

    private fun crawlGoogleDriveFolder(
        folderId: String,
        globalThumbPool: MutableMap<String, String>,
        depth: Int
    ): List<RemoteMediaItem> {
        if (depth > 2) return emptyList()
        val embedUrl = "https://drive.google.com/embeddedfolderview?id=$folderId#list"
        val html = runCatching { fetchText(embedUrl) }.getOrNull() ?: return emptyList()

        val entryRegex = Regex(
            """href="https://drive\.google\.com/(file/d/|drive/folders/)([A-Za-z0-9_-]+)[^"]*"[^>]*>([^<]+)</a>""",
            RegexOption.IGNORE_CASE
        )

        val subFolders = mutableListOf<Pair<String, String>>()
        val files = mutableListOf<Pair<String, String>>()

        for (match in entryRegex.findAll(html)) {
            val kind = match.groupValues[1].lowercase()
            val id = match.groupValues[2]
            val name = decodeHtmlEntities(match.groupValues[3]).trim()
            if (name.isEmpty()) continue
            if (kind.contains("folders")) {
                subFolders.add(id to name)
            } else {
                files.add(id to name)
            }
        }

        val localThumbs = mutableMapOf<String, String>()
        for ((subId, subName) in subFolders) {
            if (subName.equals(".thumbnail", ignoreCase = true) ||
                subName.equals(".thumbnails", ignoreCase = true) ||
                subName.equals("thumbnail", ignoreCase = true)
            ) {
                val thumbHtml = runCatching {
                    fetchText("https://drive.google.com/embeddedfolderview?id=$subId#list")
                }.getOrNull() ?: continue

                for (m in entryRegex.findAll(thumbHtml)) {
                    if (!m.groupValues[1].contains("file/d/", ignoreCase = true)) continue
                    val thumbFileId = m.groupValues[2]
                    val thumbName = decodeHtmlEntities(m.groupValues[3]).trim()
                    val thumbUrl = "https://drive.google.com/thumbnail?id=$thumbFileId&sz=w1000"
                    registerThumbnailCandidate(localThumbs, thumbName, thumbUrl)
                    registerThumbnailCandidate(globalThumbPool, thumbName, thumbUrl)
                }
            }
        }

        val results = mutableListOf<RemoteMediaItem>()
        for ((fileId, fileName) in files) {
            val dlUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            val item = buildItemFromNameAndUrl(fileName, dlUrl, SourceType.GDRIVE) ?: continue
            val thumb = resolveHybridThumbnail(item, localThumbs)
                ?: if (item.kind == MediaKind.IMAGE && !item.isContainer) {
                    "https://drive.google.com/thumbnail?id=$fileId&sz=w1000"
                } else {
                    null
                }
            results.add(if (thumb != null) item.copy(thumbnailUrl = thumb) else item)
        }

        for ((subId, subName) in subFolders) {
            if (!subName.startsWith(".")) {
                results.addAll(crawlGoogleDriveFolder(subId, globalThumbPool, depth + 1))
            }
        }
        return results
    }

    private fun discoverPixeldrainSource(url: String): List<RemoteMediaItem> {
        val listMatch = Regex("""pixeldrain\.com/l/([A-Za-z0-9_-]+)""").find(url)
        if (listMatch != null) {
            val listId = listMatch.groupValues[1]
            val jsonStr = runCatching { fetchText("https://pixeldrain.com/api/list/$listId") }.getOrNull()
                ?: return emptyList()
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return emptyList()
            val files = obj.optJSONArray("files") ?: return emptyList()
            val items = mutableListOf<RemoteMediaItem>()
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val id = f.optString("id")
                val name = f.optString("name", "$id.mp4")
                val size = f.optLong("size", -1L).takeIf { it > 0L }
                val dlUrl = "https://pixeldrain.com/api/file/$id?download"
                val base = buildItemFromNameAndUrl(name, dlUrl, SourceType.PIXELDRAIN, size) ?: continue
                items.add(base)
            }
            return items
        }

        val fileId = Regex("""pixeldrain\.com/(?:u/|api/file/)([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return emptyList()
        val infoJson = runCatching { fetchText("https://pixeldrain.com/api/file/$fileId/info") }.getOrNull()
        val infoObj = infoJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fileName = infoObj?.optString("name")?.ifBlank { null } ?: "pixeldrain_$fileId.mp4"
        val size = infoObj?.optLong("size", -1L)?.takeIf { it > 0L }
        val dlUrl = "https://pixeldrain.com/api/file/$fileId?download"

        return listOfNotNull(buildItemFromNameAndUrl(fileName, dlUrl, SourceType.PIXELDRAIN, size))
    }

    private fun discoverGofileSource(url: String): List<RemoteMediaItem> {
        val contentId = Regex("""gofile\.io/d/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return listOfNotNull(discoverGenericHostedFile(url, SourceType.GOFILE))

        val accountResp = runCatching {
            fetchText("https://api.gofile.io/accounts", method = "POST")
        }.getOrNull() ?: return emptyList()
        val token = runCatching {
            JSONObject(accountResp).optJSONObject("data")?.optString("token")
        }.getOrNull()?.ifBlank { null } ?: return emptyList()

        val contentResp = runCatching {
            fetchText(
                "https://api.gofile.io/contents/$contentId?wt=4fd6sg89d7s6",
                headers = mapOf("Authorization" to "Bearer $token")
            )
        }.getOrNull() ?: return emptyList()

        val dataObj = runCatching {
            JSONObject(contentResp).optJSONObject("data")
        }.getOrNull() ?: return emptyList()

        val children = dataObj.optJSONObject("children") ?: return emptyList()
        val items = mutableListOf<RemoteMediaItem>()
        val keys = children.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val child = children.optJSONObject(k) ?: continue
            if (child.optString("type") != "file") continue
            val name = child.optString("name", "gofile_$k.mp4")
            val link = child.optString("link").ifBlank { continue }
            val size = child.optLong("size", -1L).takeIf { it > 0L }
            val base = buildItemFromNameAndUrl(
                fileName = name,
                mediaUrl = link,
                sourceType = SourceType.GOFILE,
                sizeBytes = size,
                headers = mapOf("Cookie" to "accountToken=$token")
            ) ?: continue
            items.add(base)
        }
        return items
    }

    private fun discoverYouTubeVideo(url: String): RemoteMediaItem? {
        val videoId = LocalStreamServer.resolveYouTubeVideoId(url) ?: return null
        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val oembedUrl = "https://www.youtube.com/oembed?url=${URLEncoder.encode(canonicalUrl, "UTF-8")}&format=json"

        val oembedJson = runCatching { fetchText(oembedUrl) }.getOrNull()
        val rawTitle = if (!oembedJson.isNullOrBlank()) {
            runCatching { JSONObject(oembedJson).optString("title") }.getOrNull()?.ifBlank { null }
        } else {
            null
        } ?: "YouTube_$videoId"

        val (cleanTitle, tags) = extractTitleAndTags(rawTitle)
        val defaultThumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        return RemoteMediaItem(
            id = "yt_$videoId",
            title = cleanTitle,
            fileName = "${sanitizeFileName(cleanTitle)}.mp4",
            kind = MediaKind.VIDEO,
            sourceType = SourceType.YOUTUBE,
            mediaUrl = canonicalUrl,
            thumbnailUrl = defaultThumb,
            isContainer = false,
            tags = tags
        )
    }

    private fun discoverGenericHostedFile(url: String, sourceType: SourceType): RemoteMediaItem? {
        val probedName = probeRemoteFileName(url) ?: extractFileNameFromUrl(url)
        val normalizedName = if (probedName.contains('.')) probedName else "$probedName.mp4"
        return buildItemFromNameAndUrl(normalizedName, url, sourceType)
    }

    private fun discoverDirectFile(url: String): RemoteMediaItem? {
        val fileName = extractFileNameFromUrl(url)
        return buildItemFromNameAndUrl(fileName, url, SourceType.DIRECT)
    }

    private fun buildItemFromNameAndUrl(
        fileName: String,
        mediaUrl: String,
        sourceType: SourceType,
        sizeBytes: Long? = null,
        headers: Map<String, String> = emptyMap()
    ): RemoteMediaItem? {
        val decodedName = runCatching { URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
        val ext = decodedName.substringAfterLast('.', "").lowercase()
        val kind = when {
            ext in VIDEO_EXTS -> MediaKind.VIDEO
            ext in IMAGE_EXTS -> MediaKind.IMAGE
            else -> return null
        }
        val isContainer = ext in CONTAINER_EXTS
        val rawBaseName = decodedName.substringBeforeLast('.', decodedName)
        val (cleanTitle, tags) = extractTitleAndTags(rawBaseName)

        return RemoteMediaItem(
            id = "${sourceType.name.lowercase()}_${mediaUrl.hashCode()}",
            title = cleanTitle,
            fileName = decodedName,
            kind = kind,
            sourceType = sourceType,
            mediaUrl = mediaUrl,
            thumbnailUrl = if (kind == MediaKind.IMAGE && !isContainer) mediaUrl else null,
            isContainer = isContainer,
            sizeBytes = sizeBytes,
            headers = headers,
            tags = tags
        )
    }

    private fun isInsideThumbnailDir(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/.thumbnail/") ||
            lower.startsWith(".thumbnail/") ||
            lower.contains("/.thumbnails/") ||
            lower.startsWith(".thumbnails/")
    }

    private fun registerThumbnailCandidate(
        pool: MutableMap<String, String>,
        fileName: String,
        thumbUrl: String
    ) {
        val decoded = runCatching { URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_EXTS || ext == "icv") return

        val stem = decoded.substringBeforeLast('.', decoded)
        val normFull = normalizeKey(stem)
        if (normFull.isNotEmpty()) {
            pool.putIfAbsent(normFull, thumbUrl)
        }
        val (strippedTitle, _) = extractTitleAndTags(stem)
        val normStripped = normalizeKey(strippedTitle)
        if (normStripped.isNotEmpty()) {
            pool.putIfAbsent(normStripped, thumbUrl)
        }
    }

    private fun resolveHybridThumbnail(
        item: RemoteMediaItem,
        pool: Map<String, String>
    ): String? {
        if (pool.isEmpty()) return null

        val fileStem = item.fileName.substringBeforeLast('.', item.fileName)
        val candidates = linkedSetOf<String>()

        candidates.add(normalizeKey(fileStem))
        candidates.add(normalizeKey(item.title))

        val (strippedFileStem, _) = extractTitleAndTags(fileStem)
        candidates.add(normalizeKey(strippedFileStem))

        if (item.sourceType == SourceType.YOUTUBE) {
            LocalStreamServer.resolveYouTubeVideoId(item.mediaUrl)?.let { vid ->
                candidates.add(normalizeKey(vid))
            }
        }

        val seriesStem = normalizeKey(strippedFileStem)
            .replace(Regex("""(?:_|\s|-)*(?:ep|episode|part|vol|ch)?\d+$"""), "")
            .trim('_', '-', ' ')
        if (seriesStem.length >= 3) {
            candidates.add(seriesStem)
        }

        for (key in candidates) {
            if (key.isNotEmpty()) {
                pool[key]?.let { return it }
            }
        }
        return null
    }

    private fun extractTitleAndTags(raw: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        val tagRegex = Regex("""\[([^\]]+)]""")
        for (m in tagRegex.findAll(raw)) {
            val tagVal = m.groupValues[1].trim()
            if (tagVal.isNotEmpty()) tags.add(tagVal)
        }
        val cleaned = raw.replace(tagRegex, " ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return (cleaned.ifBlank { raw }) to tags
    }

    private fun normalizeKey(raw: String): String {
        return raw.lowercase()
            .replace(Regex("""\[[^\]]+]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
    }

    private fun extractFileNameFromUrl(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return noQuery.substringAfterLast('/').ifBlank { "media_${url.hashCode()}.mp4" }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun probeRemoteFileName(url: String): String? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val cd = conn.getHeaderField("Content-Disposition") ?: return@runCatching null
                val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
                    .find(cd)
                match?.groupValues?.get(1)?.trim()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET"
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        try {
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write("{}".toByteArray()) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
