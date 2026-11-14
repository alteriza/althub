#!/usr/bin/env bash
set -euo pipefail

if [ ! -f "app/build.gradle.kts" ]; then
  echo "Error: jalankan script ini di root folder repo AltHub."
  exit 1
fi

python3 - <<'PY'
from pathlib import Path

# 1. Patch app/build.gradle.kts (RSA release signingConfig)
gradle_path = Path("app/build.gradle.kts")
gradle_text = gradle_path.read_text(encoding="utf-8")

old_build_types = """    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }"""

new_build_types = """    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "althub"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            val relSigning = signingConfigs.getByName("release")
            signingConfig = if (relSigning.storeFile != null) {
                relSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }"""

assert old_build_types in gradle_text, "buildTypes block not found in app/build.gradle.kts"
gradle_path.write_text(gradle_text.replace(old_build_types, new_build_types, 1), encoding="utf-8")

# 2. Patch .github/workflows/build.yml (RSA Keystore generation/loading + GitHub Step Summary)
wf_path = Path(".github/workflows/build.yml")
wf_text = wf_path.read_text(encoding="utf-8")

old_build_step = """      - name: Build Native Rust Core (libalthub_core.so) and Release APK
        env:
          BUILD_NUMBER: ${{ github.run_number }}
        run: |
          chmod +x gradlew || gradle wrapper --gradle-version 8.9
          ./gradlew assembleRelease --stacktrace"""

new_build_step = """      - name: Prepare RSA Signing Keystore & Summary
        env:
          SECRET_KEYSTORE_B64: ${{ secrets.KEYSTORE_BASE64 }}
          SECRET_KEYSTORE_PASS: ${{ secrets.KEYSTORE_PASSWORD }}
          SECRET_KEY_PASS: ${{ secrets.KEY_PASSWORD }}
          VAR_KEY_ALIAS: ${{ vars.KEY_ALIAS }}
        run: |
          KS_PATH="$RUNNER_TEMP/release.jks"
          ALIAS="${VAR_KEY_ALIAS:-althub}"
          if [ -n "$SECRET_KEYSTORE_B64" ]; then
            echo "$SECRET_KEYSTORE_B64" | tr -d '\\n\\r ' | base64 -d > "$KS_PATH"
            KS_PASS="$SECRET_KEYSTORE_PASS"
            KEY_PASS="${SECRET_KEY_PASS:-$SECRET_KEYSTORE_PASS}"
            SIGN_MODE="Loaded from Repository Secret (KEYSTORE_BASE64)"
          else
            KS_PASS=$(openssl rand -hex 16)
            KEY_PASS="$KS_PASS"
            keytool -genkeypair -v \\
              -keystore "$KS_PATH" \\
              -storetype PKCS12 \\
              -keyalg RSA \\
              -keysize 4096 \\
              -validity 10000 \\
              -alias "$ALIAS" \\
              -storepass "$KS_PASS" \\
              -keypass "$KEY_PASS" \\
              -dname "CN=AltHub, OU=alteriza, O=AltHub, L=ID, S=ID, C=ID"
            SIGN_MODE="Generated New 4096-bit RSA Keystore"
          fi

          echo "KEYSTORE_FILE=$KS_PATH" >> $GITHUB_ENV
          echo "KEYSTORE_PASSWORD=$KS_PASS" >> $GITHUB_ENV
          echo "KEY_ALIAS=$ALIAS" >> $GITHUB_ENV
          echo "KEY_PASSWORD=$KEY_PASS" >> $GITHUB_ENV

          keytool -exportcert -rfc -keystore "$KS_PATH" -alias "$ALIAS" -storepass "$KS_PASS" > "$RUNNER_TEMP/cert.pem"
          openssl x509 -in "$RUNNER_TEMP/cert.pem" -pubkey -noout > "$RUNNER_TEMP/rsa_pub.pem"
          FINGERPRINTS=$(keytool -list -v -keystore "$KS_PATH" -alias "$ALIAS" -storepass "$KS_PASS" | grep -E "SHA1:|SHA256:|Signature algorithm name:|Valid from:")

          {
            echo "## APK RSA Signing Summary"
            echo ""
            echo "- **Status:** $SIGN_MODE"
            echo "- **Key Algorithm:** RSA 4096-bit"
            echo "- **Key Alias (\`vars.KEY_ALIAS\`):** \`$ALIAS\`"
            echo ""
            echo "### Certificate Fingerprints"
            echo '```text'
            echo "$FINGERPRINTS"
            echo '```'
            echo ""
            echo "### RSA Public Key (PEM)"
            echo '```pem'
            cat "$RUNNER_TEMP/rsa_pub.pem"
            echo '```'
          } >> $GITHUB_STEP_SUMMARY

          if [ -z "$SECRET_KEYSTORE_B64" ]; then
            KS_B64=$(base64 -w 0 "$KS_PATH")
            {
              echo ""
              echo "### GitHub Actions Secrets & Variables (Copy to Repo Settings)"
              echo "| Type | Name | Value |"
              echo "| :--- | :--- | :--- |"
              echo "| Variable | \`KEY_ALIAS\` | \`$ALIAS\` |"
              echo "| Secret | \`KEYSTORE_PASSWORD\` | \`$KS_PASS\` |"
              echo "| Secret | \`KEY_PASSWORD\` | \`$KEY_PASS\` |"
              echo ""
              echo "<details><summary><strong>Secret: KEYSTORE_BASE64 (Click to expand)</strong></summary>"
              echo ""
              echo '```text'
              echo "$KS_B64"
              echo '```'
              echo "</details>"
            } >> $GITHUB_STEP_SUMMARY
          fi

      - name: Build Native Rust Core (libalthub_core.so) and Release APK
        env:
          BUILD_NUMBER: ${{ github.run_number }}
        run: |
          chmod +x gradlew || gradle wrapper --gradle-version 8.9
          ./gradlew assembleRelease --stacktrace"""

assert old_build_step in wf_text, "Build step not found in .github/workflows/build.yml"
wf_text = wf_text.replace(old_build_step, new_build_step, 1)

cleanup_step = """
      - name: Cleanup Temporary Keystore
        if: always()
        run: rm -f "$RUNNER_TEMP/release.jks" "$RUNNER_TEMP/cert.pem" "$RUNNER_TEMP/rsa_pub.pem"
"""
if "Cleanup Temporary Keystore" not in wf_text:
    wf_text = wf_text.rstrip() + "\n" + cleanup_step

wf_path.write_text(wf_text, encoding="utf-8")

# 3. Patch AppStorageManager.kt (local downloaded file lookup & persistent itemId->path map)
asm_path = Path("app/src/main/java/com/althub/alteriza/AppStorageManager.kt")
asm_text = asm_path.read_text(encoding="utf-8")

asm_text = asm_text.replace(
    '    private const val KEY_HISTORY = "media_history_json"',
    '    private const val KEY_HISTORY = "media_history_json"\n    private const val KEY_DOWNLOAD_MAP = "downloaded_item_map_json"',
    1
)

lookup_helpers = """    fun recordDownloadedItem(context: Context, itemId: String, filePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapObj = runCatching {
            JSONObject(prefs.getString(KEY_DOWNLOAD_MAP, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        mapObj.put(itemId, filePath)
        prefs.edit().putString(KEY_DOWNLOAD_MAP, mapObj.toString()).apply()
    }

    fun removeDownloadedItemRecord(context: Context, filePath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapObj = runCatching {
            JSONObject(prefs.getString(KEY_DOWNLOAD_MAP, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val toRemove = mutableListOf<String>()
        val keys = mapObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (mapObj.optString(k) == filePath) toRemove.add(k)
        }
        toRemove.forEach { mapObj.remove(it) }
        prefs.edit().putString(KEY_DOWNLOAD_MAP, mapObj.toString()).apply()
    }

    fun findDownloadedFile(context: Context, item: RemoteMediaItem): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapObj = runCatching {
            JSONObject(prefs.getString(KEY_DOWNLOAD_MAP, "{}") ?: "{}")
        }.getOrNull()
        val mappedPath = mapObj?.optString(item.id, "")?.ifBlank { null }
        if (mappedPath != null) {
            val f = File(mappedPath)
            if (f.exists() && f.isFile && f.length() > 0L) return f
        }
        val dir = getDefaultDownloadDir(context)
        val files = dir.listFiles() ?: return null
        val targetBase = sanitizeFileName(
            item.fileName.substringBeforeLast('.', item.fileName)
        ).lowercase()
        if (targetBase.isBlank()) return null
        val validExts = if (item.kind == MediaKind.VIDEO) {
            setOf("vcv", "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
        } else {
            setOf("icv", "png", "jpg", "jpeg", "webp", "gif", "bmp")
        }
        val matched = files.firstOrNull { f ->
            if (!f.isFile || f.length() <= 0L) return@firstOrNull false
            val ext = f.extension.lowercase()
            if (ext !in validExts) return@firstOrNull false
            val base = f.nameWithoutExtension.lowercase()
            base == targetBase || base.startsWith("${targetBase}_")
        }
        if (matched != null) {
            recordDownloadedItem(context, item.id, matched.absolutePath)
        }
        return matched
    }

    fun getDefaultDownloadDir(context: Context): File {"""

assert "    fun getDefaultDownloadDir(context: Context): File {" in asm_text
asm_text = asm_text.replace("    fun getDefaultDownloadDir(context: Context): File {", lookup_helpers, 1)

assert "            finalFile\n        }" in asm_text
asm_text = asm_text.replace(
    "            finalFile\n        }",
    "            recordDownloadedItem(context, item.id, finalFile.absolutePath)\n            finalFile\n        }",
    1
)

asm_path.write_text(asm_text, encoding="utf-8")

# 4. Patch LocalStreamServer.kt (serve local .vcv/media files with zero network traffic)
lss_path = Path("app/src/main/java/com/althub/alteriza/LocalStreamServer.kt")
lss_text = lss_path.read_text(encoding="utf-8")

lss_text = lss_text.replace(
    "import java.io.EOFException",
    "import java.io.EOFException\nimport java.io.File\nimport java.io.FileInputStream\nimport java.io.RandomAccessFile",
    1
)

lss_text = lss_text.replace(
    "    private val itemsById = ConcurrentHashMap<String, RemoteMediaItem>()\n    private val headersById = ConcurrentHashMap<String, ContainerHeaderInfo>()",
    "    private val itemsById = ConcurrentHashMap<String, RemoteMediaItem>()\n    private val headersById = ConcurrentHashMap<String, ContainerHeaderInfo>()\n    private val localFilesById = ConcurrentHashMap<String, File>()\n    private val localHeadersById = ConcurrentHashMap<String, ContainerHeaderInfo>()",
    1
)

old_get_url = """    fun getStreamUrl(item: RemoteMediaItem): String {
        itemsById[item.id] = item
        val p = ensureStarted()
        return "http://127.0.0.1:$p/stream?id=${item.id}"
    }"""

new_get_url = """    fun getStreamUrl(item: RemoteMediaItem): String {
        itemsById[item.id] = item
        val p = ensureStarted()
        return "http://127.0.0.1:$p/stream?id=${item.id}"
    }

    fun getLocalStreamUrl(item: RemoteMediaItem, localFile: File): String {
        itemsById[item.id] = item
        localFilesById[item.id] = localFile
        localHeadersById.remove(item.id)
        val p = ensureStarted()
        return "http://127.0.0.1:$p/stream?id=${item.id}&local=1"
    }"""

assert old_get_url in lss_text
lss_text = lss_text.replace(old_get_url, new_get_url, 1)

old_handle_lookup = """                val id = target.substringAfter("id=", "").substringBefore('&')
                val item = itemsById[id]
                val out = BufferedOutputStream(client.getOutputStream(), 65_536)
                if (item == null) {
                    out.write("HTTP/1.1 404 Not Found\\r\\nContent-Length: 0\\r\\nConnection: close\\r\\n\\r\\n".toByteArray())
                    out.flush()
                    return
                }

                val headerInfo = resolveHeader(item)
                val mime = mimeTypeForExt(headerInfo.originalExt)

                if (!headerInfo.isContainer || headerInfo.compression == 0) {
                    serveUncompressedRange(item, headerInfo, mime, method, rangeValue, out)
                } else {
                    serveCompressedStream(item, headerInfo, mime, method, rangeValue, out)
                }"""

new_handle_lookup = """                val id = target.substringAfter("id=", "").substringBefore('&')
                val isLocalReq = target.contains("local=1")
                val item = itemsById[id]
                val out = BufferedOutputStream(client.getOutputStream(), 65_536)
                if (item == null) {
                    out.write("HTTP/1.1 404 Not Found\\r\\nContent-Length: 0\\r\\nConnection: close\\r\\n\\r\\n".toByteArray())
                    out.flush()
                    return
                }

                val localFile = if (isLocalReq) {
                    localFilesById[id]?.takeIf { it.exists() && it.isFile }
                } else {
                    null
                }

                if (localFile != null) {
                    val headerInfo = resolveLocalHeader(id, localFile)
                    val mime = mimeTypeForExt(headerInfo.originalExt)
                    if (!headerInfo.isContainer || headerInfo.compression == 0) {
                        serveLocalUncompressedRange(localFile, headerInfo, mime, method, rangeValue, out)
                    } else {
                        serveLocalCompressedStream(localFile, headerInfo, mime, method, rangeValue, out)
                    }
                    return
                }

                val headerInfo = resolveHeader(item)
                val mime = mimeTypeForExt(headerInfo.originalExt)

                if (!headerInfo.isContainer || headerInfo.compression == 0) {
                    serveUncompressedRange(item, headerInfo, mime, method, rangeValue, out)
                } else {
                    serveCompressedStream(item, headerInfo, mime, method, rangeValue, out)
                }"""

assert old_handle_lookup in lss_text
lss_text = lss_text.replace(old_handle_lookup, new_handle_lookup, 1)

local_serve_methods = """    private fun resolveLocalHeader(id: String, file: File): ContainerHeaderInfo {
        localHeadersById[id]?.let { return it }
        val extFallback = file.extension.lowercase().let {
            if (it == "vcv" || it == "icv" || it.isBlank()) "mp4" else it
        }
        val info = BufferedInputStream(FileInputStream(file), 16_384).use { bis ->
            bis.mark(16)
            val magic = ByteArray(4)
            val n = bis.read(magic)
            val mStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
            bis.reset()
            if (mStr == "SVCV" || mStr == "SICV") {
                parseContainerHeaderFromStream(bis)
            } else {
                ContainerHeaderInfo(
                    isContainer = false,
                    headerOffset = 0L,
                    compression = 0,
                    originalExt = extFallback,
                    originalSize = file.length()
                )
            }
        }
        localHeadersById[id] = info
        return info
    }

    private fun serveLocalUncompressedRange(
        file: File,
        info: ContainerHeaderInfo,
        mime: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val totalSize = (file.length() - info.headerOffset).coerceAtLeast(0L)
        if (totalSize <= 0L) return
        var start = 0L
        var end = totalSize - 1L
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
                    } else {
                        totalSize - 1L
                    }
                    isPartial = true
                }
            }
        }

        start = start.coerceIn(0L, totalSize - 1L)
        end = if (end < start) totalSize - 1L else end.coerceAtMost(totalSize - 1L)
        val contentLength = end - start + 1L

        val sb = StringBuilder()
        if (isPartial) {
            sb.append("HTTP/1.1 206 Partial Content\\r\\n")
            sb.append("Content-Range: bytes $start-$end/$totalSize\\r\\n")
        } else {
            sb.append("HTTP/1.1 200 OK\\r\\n")
        }
        sb.append("Content-Type: $mime\\r\\n")
        sb.append("Accept-Ranges: bytes\\r\\n")
        sb.append("Content-Length: $contentLength\\r\\n")
        sb.append("Connection: close\\r\\n\\r\\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        if (method == "HEAD") return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.headerOffset + start)
            val buf = ByteArray(65_536)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n.toLong()
            }
            out.flush()
        }
    }

    private fun serveLocalCompressedStream(
        file: File,
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
            sb.append("HTTP/1.1 206 Partial Content\\r\\n")
            sb.append("Content-Range: bytes $skipBytes-$end/$totalSize\\r\\n")
            sb.append("Content-Length: ${totalSize - skipBytes}\\r\\n")
        } else {
            sb.append("HTTP/1.1 200 OK\\r\\n")
            if (totalSize > 0L) {
                sb.append("Content-Length: $totalSize\\r\\n")
            }
        }
        sb.append("Content-Type: $mime\\r\\n")
        sb.append("Accept-Ranges: bytes\\r\\n")
        sb.append("Connection: close\\r\\n\\r\\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        if (method == "HEAD") return

        BufferedInputStream(FileInputStream(file), 65_536).use { rawIn ->
            skipFully(rawIn, info.headerOffset)
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
        }
    }

    private fun resolveHeader(item: RemoteMediaItem): ContainerHeaderInfo {"""

assert "    private fun resolveHeader(item: RemoteMediaItem): ContainerHeaderInfo {" in lss_text
lss_text = lss_text.replace("    private fun resolveHeader(item: RemoteMediaItem): ContainerHeaderInfo {", local_serve_methods, 1)

lss_path.write_text(lss_text, encoding="utf-8")

# 5. Patch AutoDiscoveryRepository.kt (LruCache + concurrency Semaphore(2) + negative cache + local file decoder)
adr_path = Path("app/src/main/java/com/althub/alteriza/AutoDiscoveryRepository.kt")
adr_text = adr_path.read_text(encoding="utf-8")

adr_text = adr_text.replace(
    "import android.media.MediaMetadataRetriever",
    "import android.media.MediaMetadataRetriever\nimport android.util.LruCache",
    1
)
adr_text = adr_text.replace(
    "import kotlinx.coroutines.withContext",
    "import kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit\nimport kotlinx.coroutines.withContext",
    1
)
adr_text = adr_text.replace(
    "import java.io.FileOutputStream",
    "import java.io.FileInputStream\nimport java.io.FileOutputStream",
    1
)

old_cache_block = """    private val memoryThumbs = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var cachedGofileToken: String? = null

    private data class GitHubLocation(
        val owner: String,
        val repo: String,
        val branch: String?,
        val subPath: String
    )

    fun getCachedThumbnail(itemId: String): Bitmap? = memoryThumbs[itemId]

    fun clearMemoryCache() {
        memoryThumbs.clear()
    }"""

new_cache_block = """    private val memoryThumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
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
    }"""

assert old_cache_block in adr_text
adr_text = adr_text.replace(old_cache_block, new_cache_block, 1)

old_thumb_loaders = """    suspend fun loadThumbnailBitmap(context: Context, item: RemoteMediaItem): Bitmap? =
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
                val bytes = downloadBytes(item.thumbnailUrl, extraHeaders = item.headers)
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
        }"""

new_thumb_loaders = """    fun decodeLocalMediaBitmap(file: File): Bitmap? {
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
        }"""

assert old_thumb_loaders in adr_text
adr_text = adr_text.replace(old_thumb_loaders, new_thumb_loaders, 1)

old_extract_frame = """    private fun extractVideoFrameFromUrl(streamUrl: String): Bitmap? {
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
    }"""

new_extract_frame = """    private fun extractVideoFrameFromFile(file: File): Bitmap? {
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
    }"""

assert old_extract_frame in adr_text
adr_text = adr_text.replace(old_extract_frame, new_extract_frame, 1)

adr_path.write_text(adr_text, encoding="utf-8")

# 6. Patch AltHubApp.kt (Viewport lazy load, 10x tap + ms auto-reset + pre-open alert + random captcha, LOCAL/STREAM player, full resource isolation during playback/viewing, full-screen post reader)
app_path = Path("app/src/main/java/com/althub/alteriza/AltHubApp.kt")
app_text = app_path.read_text(encoding="utf-8")

app_text = app_text.replace(
    "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.LazyRow",
    "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.LazyListState\nimport androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.foundation.lazy.rememberLazyListState",
    1
)

app_text = app_text.replace(
    "import androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.getValue",
    "import androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.LaunchedEffect\nimport androidx.compose.runtime.derivedStateOf\nimport androidx.compose.runtime.getValue",
    1
)

app_text = app_text.replace(
    "import androidx.compose.ui.graphics.graphicsLayer",
    "import androidx.compose.ui.graphics.PathEffect\nimport androidx.compose.ui.graphics.StrokeCap\nimport androidx.compose.ui.graphics.drawscope.rotate\nimport androidx.compose.ui.graphics.drawscope.translate\nimport androidx.compose.ui.graphics.graphicsLayer",
    1
)

app_text = app_text.replace(
    "import androidx.compose.material.icons.filled.Pause",
    "import androidx.compose.material.icons.filled.Pause\nimport androidx.compose.material.icons.filled.Refresh",
    1
)

app_text = app_text.replace(
    "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch",
    "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch",
    1
)

app_text = app_text.replace(
    "import java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale",
    "import java.security.SecureRandom\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale",
    1
)

old_corn_consts = """private const val CORN_ALERT_TITLE = "CORN // HIDDEN SECTION"
private const val CORN_ALERT_MESSAGE = "WARNING! ADULT ONLY" """.rstrip()

new_corn_consts = """private const val CORN_ALERT_TITLE = "CORN // HIDDEN SECTION"
private const val CORN_ALERT_MESSAGE = "WARNING! ADULT ONLY — SOLVE CAPTCHA TO ENTER"
private const val CORN_TAP_TARGET = 10
private const val CORN_TAP_MAX_DELAY_MS = 450L

private fun generateRandomCaptcha(length: Int = 6): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rng = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(chars[rng.nextInt(chars.length)])
        }
    }
}"""

assert old_corn_consts in app_text
app_text = app_text.replace(old_corn_consts, new_corn_consts, 1)

old_state_block = """    var CORNUnlocked by remember { mutableStateOf(false) }
    var showCORNAlert by remember { mutableStateOf(false) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapMs by remember { mutableLongStateOf(0L) }

    var welcomePopupPost by remember { mutableStateOf<InfoPost?>(null) }"""

new_state_block = """    var CORNUnlocked by remember { mutableStateOf(false) }
    var showCORNAlert by remember { mutableStateOf(false) }
    var cornAlertIsExit by remember { mutableStateOf(false) }
    var captchaCode by remember { mutableStateOf(generateRandomCaptcha()) }
    var captchaInput by remember { mutableStateOf("") }
    var captchaError by remember { mutableStateOf(false) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapMs by remember { mutableLongStateOf(0L) }

    var welcomePopupPost by remember { mutableStateOf<InfoPost?>(null) }
    var selectedPost by remember { mutableStateOf<InfoPost?>(null) }
    var activeSyncJob by remember { mutableStateOf<Job?>(null) }

    val feedListState = rememberLazyListState()
    val videosListState = rememberLazyListState()
    val photosListState = rememberLazyListState()
    val infoListState = rememberLazyListState()
    val cornListState = rememberLazyListState()"""

assert old_state_block in app_text
app_text = app_text.replace(old_state_block, new_state_block, 1)

old_open_close = """    fun openMedia(item: RemoteMediaItem) {
        AppStorageManager.recordHistory(context, item)
        storageRefreshTick++
        val cachedThumb = AutoDiscoveryRepository.getCachedThumbnail(item.id)
        if (cachedThumb != null) {
            activity?.requestedOrientation = if (cachedThumb.width > cachedThumb.height) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            applyImmersiveSystemBars(activity)
        }
        selectedMedia = item
    }

    fun closeViewer() {
        selectedMedia = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyImmersiveSystemBars(activity)
    }"""

new_open_close = """    fun openMedia(item: RemoteMediaItem) {
        activeSyncJob?.cancel()
        activeSyncJob = null
        isRefreshing = false
        AppStorageManager.recordHistory(context, item)
        storageRefreshTick++
        val cachedThumb = AutoDiscoveryRepository.getCachedThumbnail(item.id)
        if (cachedThumb != null) {
            activity?.requestedOrientation = if (cachedThumb.width > cachedThumb.height) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            applyImmersiveSystemBars(activity)
        }
        selectedMedia = item
    }

    fun closeViewer() {
        selectedMedia = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyImmersiveSystemBars(activity)
    }"""

assert old_open_close in app_text
app_text = app_text.replace(old_open_close, new_open_close, 1)

old_sync_fn = """    suspend fun syncRepository(fromPullRefresh: Boolean = false) {
        if (fromPullRefresh) {
            isRefreshing = true
        } else {
            initialLoading = true
        }
        val mainResult = AutoDiscoveryRepository.discoverAll()"""

new_sync_fn = """    suspend fun syncRepository(fromPullRefresh: Boolean = false) {
        if (selectedMedia != null) return
        if (fromPullRefresh) {
            AutoDiscoveryRepository.clearFailedThumbCache()
            isRefreshing = true
        } else {
            initialLoading = true
        }
        val mainResult = AutoDiscoveryRepository.discoverAll()"""

assert old_sync_fn in app_text
app_text = app_text.replace(old_sync_fn, new_sync_fn, 1)

old_launched_unit = """    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        syncRepository(fromPullRefresh = false)
    }"""

new_launched_unit = """    LaunchedEffect(logoTapCount, lastLogoTapMs) {
        if (logoTapCount in 1 until CORN_TAP_TARGET) {
            delay(CORN_TAP_MAX_DELAY_MS + 20L)
            logoTapCount = 0
        }
    }

    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activeSyncJob = scope.launch { syncRepository(fromPullRefresh = false) }
    }"""

assert old_launched_unit in app_text
app_text = app_text.replace(old_launched_unit, new_launched_unit, 1)

old_root_box_start = """    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
    ) {
        TechnicalGridBackdrop()"""

new_root_box_start = """    val activeMedia = selectedMedia
    val isViewerActive = activeMedia != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
    ) {
        if (!isViewerActive) {
            TechnicalGridBackdrop()"""

assert old_root_box_start in app_text
app_text = app_text.replace(old_root_box_start, new_root_box_start, 1)

old_logo_tap = """                onLogoTap = {
                    val now = SystemClock.elapsedRealtime()
                    logoTapCount = if (now - lastLogoTapMs <= 2500L) logoTapCount + 1 else 1
                    lastLogoTapMs = now
                    if (logoTapCount >= 5) {
                        logoTapCount = 0
                        settingsOpen = false
                        if (!CORNUnlocked) {
                            CORNUnlocked = true
                            showCORNAlert = true
                            currentTab = NavDestination.CORN
                            scope.launch {
                                isRefreshing = true
                                CORNDiscovery = AutoDiscoveryRepository.discoverHiddenCdn()
                                isRefreshing = false
                            }
                        } else {
                            CORNUnlocked = false
                            showCORNAlert = false
                            CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                            if (currentTab == NavDestination.CORN) {
                                currentTab = NavDestination.FEED
                            }
                        }
                    } else if (logoTapCount == 1) {
                        settingsOpen = !settingsOpen
                    }
                },"""

new_logo_tap = """                onLogoTap = {
                    val now = SystemClock.elapsedRealtime()
                    logoTapCount = if (now - lastLogoTapMs <= CORN_TAP_MAX_DELAY_MS) logoTapCount + 1 else 1
                    lastLogoTapMs = now
                    if (logoTapCount >= CORN_TAP_TARGET) {
                        logoTapCount = 0
                        settingsOpen = false
                        cornAlertIsExit = CORNUnlocked
                        captchaCode = generateRandomCaptcha()
                        captchaInput = ""
                        captchaError = false
                        showCORNAlert = true
                    } else if (logoTapCount == 1) {
                        settingsOpen = !settingsOpen
                    } else {
                        settingsOpen = false
                    }
                },"""

assert old_logo_tap in app_text
app_text = app_text.replace(old_logo_tap, new_logo_tap, 1)

old_delete_dl = """                    onDeleteDownloadedFile = { entry ->
                        runCatching { File(entry.path).delete() }
                        storageRefreshTick++
                    },"""

new_delete_dl = """                    onDeleteDownloadedFile = { entry ->
                        runCatching { File(entry.path).delete() }
                        AppStorageManager.removeDownloadedItemRecord(context, entry.path)
                        storageRefreshTick++
                    },"""

assert old_delete_dl in app_text
app_text = app_text.replace(old_delete_dl, new_delete_dl, 1)

old_pull_refresh = """                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch { syncRepository(fromPullRefresh = true) }
                    },"""

new_pull_refresh = """                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        activeSyncJob?.cancel()
                        activeSyncJob = scope.launch { syncRepository(fromPullRefresh = true) }
                    },"""

assert old_pull_refresh in app_text
app_text = app_text.replace(old_pull_refresh, new_pull_refresh, 1)

old_feed_tab = """                        currentTab == NavDestination.FEED -> {
                            val featuredItem = allVideos.firstOrNull() ?: allPhotos.firstOrNull()
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (featuredItem != null && searchQuery.isBlank() && activeFeedChip == FeedChip.ALL) {
                                    item(key = "hero_${featuredItem.id}") {
                                        FeaturedMediaSpotlight(
                                            item = featuredItem,
                                            onOpen = { openMedia(featuredItem) }
                                        )
                                    }
                                }

                                item(key = "feed_chips") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(FeedChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeFeedChip == chip,
                                                onClick = { activeFeedChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (timelineEntries.isEmpty()) {
                                    item(key = "empty_feed") {
                                        EmptyFeedBox(
                                            title = "FEED IS EMPTY",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(
                                        items = timelineEntries,
                                        key = { entry ->
                                            when (entry) {
                                                is TimelineEntry.MediaEntry -> "m_${entry.item.id}"
                                                is TimelineEntry.PostEntry -> "p_${entry.post.id}"
                                            }
                                        }
                                    ) { entry ->
                                        when (entry) {
                                            is TimelineEntry.MediaEntry -> {
                                                SocialStreamPostCard(
                                                    item = entry.item,
                                                    onOpen = { openMedia(entry.item) },
                                                    onDownload = { requestDownload(entry.item) },
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                            is TimelineEntry.PostEntry -> {
                                                SocialAnnouncementCard(
                                                    post = entry.post,
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.VIDEOS -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allVideos.isEmpty()) {
                                    item(key = "empty_videos") {
                                        EmptyFeedBox(
                                            title = "NO VIDEOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    itemsIndexed(allVideos, key = { _, item -> item.id }) { idx, video ->
                                        VideoStreamCard(
                                            indexNumber = allVideos.size - idx,
                                            item = video,
                                            onWatch = { openMedia(video) },
                                            onDownload = { requestDownload(video) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.PHOTOS -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPhotos.isEmpty()) {
                                    item(key = "empty_photos") {
                                        EmptyFeedBox(
                                            title = "NO PHOTOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPhotos, key = { it.id }) { photo ->
                                        SocialStreamPostCard(
                                            item = photo,
                                            onOpen = { openMedia(photo) },
                                            onDownload = { requestDownload(photo) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.INFO -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPosts.isEmpty()) {
                                    item(key = "empty_info") {
                                        EmptyFeedBox(
                                            title = "NO INFO POSTS",
                                            subtitle = "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPosts, key = { it.id }) { post ->
                                        SocialAnnouncementCard(post = post)
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.CORN -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "CORN_header_banner") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BbSurfaceElevated)
                                            .border(1.dp, BbOrange)
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(BbOrange)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "CORN // PRIVATE STREAM",
                                            color = BbWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        CubicActionButton(
                                            text = "HIDE",
                                            primary = false,
                                            onClick = {
                                                CORNUnlocked = false
                                                CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                                                currentTab = NavDestination.FEED
                                            }
                                        )
                                    }
                                }

                                item(key = "CORN_chips") {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(CORNChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeCORNChip == chip,
                                                onClick = { activeCORNChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (CORNFilteredMedia.isEmpty() && CORNDiscovery.infoPosts.isEmpty()) {
                                    item(key = "empty_CORN") {
                                        EmptyFeedBox(
                                            title = "CORN STREAM EMPTY",
                                            subtitle = CORNDiscovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(CORNFilteredMedia, key = { "CORN_${it.id}" }) { media ->
                                        SocialStreamPostCard(
                                            item = media,
                                            onOpen = { openMedia(media) },
                                            onDownload = { requestDownload(media) }
                                        )
                                    }
                                    items(CORNDiscovery.infoPosts, key = { "CORN_post_${it.id}" }) { post ->
                                        SocialAnnouncementCard(post = post)
                                    }
                                }
                            }
                        }"""

new_feed_tab = """                        currentTab == NavDestination.FEED -> {
                            val featuredItem = allVideos.firstOrNull() ?: allPhotos.firstOrNull()
                            LazyColumn(
                                state = feedListState,
                                contentPadding = PaddingValues(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (featuredItem != null && searchQuery.isBlank() && activeFeedChip == FeedChip.ALL) {
                                    val heroKey = "hero_${featuredItem.id}"
                                    item(key = heroKey) {
                                        val isHeroVisible by remember(heroKey) {
                                            derivedStateOf {
                                                feedListState.layoutInfo.visibleItemsInfo.any { it.key == heroKey }
                                            }
                                        }
                                        FeaturedMediaSpotlight(
                                            item = featuredItem,
                                            isVisible = isHeroVisible,
                                            isViewerActive = isViewerActive,
                                            onOpen = { openMedia(featuredItem) }
                                        )
                                    }
                                }

                                item(key = "feed_chips") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(FeedChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeFeedChip == chip,
                                                onClick = { activeFeedChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (timelineEntries.isEmpty()) {
                                    item(key = "empty_feed") {
                                        EmptyFeedBox(
                                            title = "FEED IS EMPTY",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(
                                        items = timelineEntries,
                                        key = { entry ->
                                            when (entry) {
                                                is TimelineEntry.MediaEntry -> "m_${entry.item.id}"
                                                is TimelineEntry.PostEntry -> "p_${entry.post.id}"
                                            }
                                        }
                                    ) { entry ->
                                        when (entry) {
                                            is TimelineEntry.MediaEntry -> {
                                                val itemKey = "m_${entry.item.id}"
                                                val isCardVisible by remember(itemKey) {
                                                    derivedStateOf {
                                                        feedListState.layoutInfo.visibleItemsInfo.any { it.key == itemKey }
                                                    }
                                                }
                                                SocialStreamPostCard(
                                                    item = entry.item,
                                                    isVisible = isCardVisible,
                                                    isViewerActive = isViewerActive,
                                                    onOpen = { openMedia(entry.item) },
                                                    onDownload = { requestDownload(entry.item) },
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                            is TimelineEntry.PostEntry -> {
                                                SocialAnnouncementCard(
                                                    post = entry.post,
                                                    onOpenFull = { selectedPost = entry.post },
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.VIDEOS -> {
                            LazyColumn(
                                state = videosListState,
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allVideos.isEmpty()) {
                                    item(key = "empty_videos") {
                                        EmptyFeedBox(
                                            title = "NO VIDEOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    itemsIndexed(allVideos, key = { _, item -> item.id }) { idx, video ->
                                        val isCardVisible by remember(video.id) {
                                            derivedStateOf {
                                                videosListState.layoutInfo.visibleItemsInfo.any { it.key == video.id }
                                            }
                                        }
                                        VideoStreamCard(
                                            indexNumber = allVideos.size - idx,
                                            item = video,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onWatch = { openMedia(video) },
                                            onDownload = { requestDownload(video) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.PHOTOS -> {
                            LazyColumn(
                                state = photosListState,
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPhotos.isEmpty()) {
                                    item(key = "empty_photos") {
                                        EmptyFeedBox(
                                            title = "NO PHOTOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPhotos, key = { it.id }) { photo ->
                                        val isCardVisible by remember(photo.id) {
                                            derivedStateOf {
                                                photosListState.layoutInfo.visibleItemsInfo.any { it.key == photo.id }
                                            }
                                        }
                                        SocialStreamPostCard(
                                            item = photo,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onOpen = { openMedia(photo) },
                                            onDownload = { requestDownload(photo) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.INFO -> {
                            LazyColumn(
                                state = infoListState,
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPosts.isEmpty()) {
                                    item(key = "empty_info") {
                                        EmptyFeedBox(
                                            title = "NO INFO POSTS",
                                            subtitle = "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPosts, key = { it.id }) { post ->
                                        SocialAnnouncementCard(
                                            post = post,
                                            onOpenFull = { selectedPost = post }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.CORN -> {
                            LazyColumn(
                                state = cornListState,
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "CORN_header_banner") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BbSurfaceElevated)
                                            .border(1.dp, BbOrange)
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(BbOrange)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "CORN // PRIVATE STREAM",
                                            color = BbWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        CubicActionButton(
                                            text = "HIDE",
                                            primary = false,
                                            onClick = {
                                                CORNUnlocked = false
                                                CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                                                currentTab = NavDestination.FEED
                                            }
                                        )
                                    }
                                }

                                item(key = "CORN_chips") {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(CORNChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeCORNChip == chip,
                                                onClick = { activeCORNChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (CORNFilteredMedia.isEmpty() && CORNDiscovery.infoPosts.isEmpty()) {
                                    item(key = "empty_CORN") {
                                        EmptyFeedBox(
                                            title = "CORN STREAM EMPTY",
                                            subtitle = CORNDiscovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(CORNFilteredMedia, key = { "CORN_${it.id}" }) { media ->
                                        val itemKey = "CORN_${media.id}"
                                        val isCardVisible by remember(itemKey) {
                                            derivedStateOf {
                                                cornListState.layoutInfo.visibleItemsInfo.any { it.key == itemKey }
                                            }
                                        }
                                        SocialStreamPostCard(
                                            item = media,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onOpen = { openMedia(media) },
                                            onDownload = { requestDownload(media) }
                                        )
                                    }
                                    items(CORNDiscovery.infoPosts, key = { "CORN_post_${it.id}" }) { post ->
                                        SocialAnnouncementCard(
                                            post = post,
                                            onOpenFull = { selectedPost = post }
                                        )
                                    }
                                }
                            }
                        }"""

assert old_feed_tab in app_text
app_text = app_text.replace(old_feed_tab, new_feed_tab, 1)

old_modals_and_viewer = """        if (showCORNAlert) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbOrange)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = CORN_ALERT_TITLE,
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )
                    Text(
                        text = CORN_ALERT_MESSAGE,
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CubicActionButton(
                            text = "ENTER CORN",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = { showCORNAlert = false }
                        )
                    }
                }
            }
        }

        val activeWelcome = welcomePopupPost
        if (activeWelcome != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp)
                        .background(BbSurface)
                        .border(1.dp, BbBorderBright)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BbSurfaceElevated)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeWelcome.title.uppercase(),
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        CubicSquareIconButton(
                            icon = Icons.Default.Close,
                            active = false,
                            onClick = { welcomePopupPost = null }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        BBCodeView(bbcode = activeWelcome.bbcodeContent)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CubicActionButton(
                            text = "CONTINUE",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = { welcomePopupPost = null }
                        )
                    }
                }
            }
        }

        val activeMedia = selectedMedia
        if (activeMedia != null) {
            BackHandler {
                closeViewer()
            }

            DisposableEffect(activeMedia.id) {
                onDispose {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    applyImmersiveSystemBars(activity)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BbBlack)
            ) {
                if (activeMedia.kind == MediaKind.VIDEO) {
                    CubicStreamingVideoPlayer(
                        item = activeMedia,
                        hwAcceleration = hwAcceleration,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onToggleOrientation = {
                            val currentOrient = activity?.requestedOrientation
                            val nextLandscape = currentOrient != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            activity?.requestedOrientation = if (nextLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                } else {
                    CubicStreamingImageViewer(
                        item = activeMedia,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                }
            }
        }"""

new_modals_and_viewer = """        if (showCORNAlert) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbOrange)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = CORN_ALERT_TITLE,
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )
                    Text(
                        text = if (cornAlertIsExit) {
                            "HIDDEN SECTION IS ACTIVE. EXIT AND LOCK STREAM?"
                        } else {
                            CORN_ALERT_MESSAGE
                        },
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    if (!cornAlertIsExit) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CaptchaCanvas(
                                code = captchaCode,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(BbBlack)
                                    .border(1.dp, BbBorderBright)
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Refresh,
                                active = false,
                                onClick = {
                                    captchaCode = generateRandomCaptcha()
                                    captchaInput = ""
                                    captchaError = false
                                }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .background(BbBlack)
                                .border(1.dp, if (captchaError) BbOrange else BbBorderBright)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (captchaInput.isEmpty()) {
                                Text(
                                    text = "ENTER CAPTCHA CODE...",
                                    color = BbMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            BasicTextField(
                                value = captchaInput,
                                onValueChange = {
                                    captchaInput = it.uppercase().take(8)
                                    captchaError = false
                                },
                                singleLine = true,
                                cursorBrush = SolidColor(BbOrange),
                                textStyle = TextStyle(
                                    color = BbWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 2.sp
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (captchaError) {
                            Text(
                                text = "INVALID CAPTCHA — NEW CODE GENERATED",
                                color = BbOrange,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        if (cornAlertIsExit) {
                            CubicActionButton(
                                text = "CANCEL",
                                primary = false,
                                onClick = { showCORNAlert = false }
                            )
                            CubicActionButton(
                                text = "EXIT",
                                icon = Icons.Default.Close,
                                primary = true,
                                onClick = {
                                    showCORNAlert = false
                                    CORNUnlocked = false
                                    CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                                    if (currentTab == NavDestination.CORN) {
                                        currentTab = NavDestination.FEED
                                    }
                                }
                            )
                        } else {
                            CubicActionButton(
                                text = "EXIT",
                                icon = Icons.Default.Close,
                                primary = false,
                                onClick = {
                                    showCORNAlert = false
                                    captchaInput = ""
                                    captchaError = false
                                }
                            )
                            CubicActionButton(
                                text = "ENTER",
                                icon = Icons.Default.NorthEast,
                                primary = true,
                                onClick = {
                                    if (captchaInput.trim().equals(captchaCode, ignoreCase = true)) {
                                        showCORNAlert = false
                                        captchaInput = ""
                                        captchaError = false
                                        CORNUnlocked = true
                                        currentTab = NavDestination.CORN
                                        activeSyncJob?.cancel()
                                        activeSyncJob = scope.launch {
                                            isRefreshing = true
                                            CORNDiscovery = AutoDiscoveryRepository.discoverHiddenCdn()
                                            isRefreshing = false
                                        }
                                    } else {
                                        captchaError = true
                                        captchaCode = generateRandomCaptcha()
                                        captchaInput = ""
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        val activeWelcome = welcomePopupPost
        if (activeWelcome != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp)
                        .background(BbSurface)
                        .border(1.dp, BbBorderBright)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BbSurfaceElevated)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeWelcome.title.uppercase(),
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        CubicSquareIconButton(
                            icon = Icons.Default.Close,
                            active = false,
                            onClick = { welcomePopupPost = null }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        BBCodeView(bbcode = activeWelcome.bbcodeContent)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CubicActionButton(
                            text = "CONTINUE",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = { welcomePopupPost = null }
                        )
                    }
                }
            }
        }

        val activeFullPost = selectedPost
        if (activeFullPost != null) {
            BackHandler {
                selectedPost = null
            }
            FullPostReaderScreen(
                post = activeFullPost,
                onBack = { selectedPost = null }
            )
        }
        } else if (activeMedia != null) {
            BackHandler {
                closeViewer()
            }

            DisposableEffect(activeMedia.id) {
                onDispose {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    applyImmersiveSystemBars(activity)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BbBlack)
            ) {
                if (activeMedia.kind == MediaKind.VIDEO) {
                    CubicStreamingVideoPlayer(
                        item = activeMedia,
                        hwAcceleration = hwAcceleration,
                        storageRefreshTick = storageRefreshTick,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onToggleOrientation = {
                            val currentOrient = activity?.requestedOrientation
                            val nextLandscape = currentOrient != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            activity?.requestedOrientation = if (nextLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                } else {
                    CubicStreamingImageViewer(
                        item = activeMedia,
                        storageRefreshTick = storageRefreshTick,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                }

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 66.dp)
                            .background(BbSurfaceElevated)
                            .border(1.dp, BbOrange)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = BbOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "DOWNLOADING TO STORAGE...",
                                color = BbWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }"""

assert old_modals_and_viewer in app_text
app_text = app_text.replace(old_modals_and_viewer, new_modals_and_viewer, 1)

old_spotlight = """@Composable
private fun FeaturedMediaSpotlight(
    item: RemoteMediaItem,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
        }
    }"""

new_spotlight = """@Composable
private fun FeaturedMediaSpotlight(
    item: RemoteMediaItem,
    isVisible: Boolean,
    isViewerActive: Boolean,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            delay(180L)
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
        }
    }"""

assert old_spotlight in app_text
app_text = app_text.replace(old_spotlight, new_spotlight, 1)

old_post_card = """@Composable
private fun SocialStreamPostCard(
    item: RemoteMediaItem,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(thumb == null) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        }
    }"""

new_post_card = """@Composable
private fun SocialStreamPostCard(
    item: RemoteMediaItem,
    isVisible: Boolean,
    isViewerActive: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            thumbLoading = true
            delay(180L)
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        } else if (!isVisible) {
            thumbLoading = false
        }
    }"""

assert old_post_card in app_text
app_text = app_text.replace(old_post_card, new_post_card, 1)

old_video_card = """@Composable
private fun VideoStreamCard(
    indexNumber: Int,
    item: RemoteMediaItem,
    onWatch: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(thumb == null) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        }
    }"""

new_video_card = """@Composable
private fun VideoStreamCard(
    indexNumber: Int,
    item: RemoteMediaItem,
    isVisible: Boolean,
    isViewerActive: Boolean,
    onWatch: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            thumbLoading = true
            delay(180L)
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        } else if (!isVisible) {
            thumbLoading = false
        }
    }"""

assert old_video_card in app_text
app_text = app_text.replace(old_video_card, new_video_card, 1)

old_announcement = """@Composable
private fun SocialAnnouncementCard(
    post: InfoPost,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
    ) {"""

new_announcement = """@Composable
private fun SocialAnnouncementCard(
    post: InfoPost,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .clickable(onClick = onOpenFull)
    ) {"""

assert old_announcement in app_text
app_text = app_text.replace(old_announcement, new_announcement, 1)

old_announcement_footer = """            BBCodeView(bbcode = post.bbcodeContent)
        }
    }
}"""

new_announcement_footer = """            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                BBCodeView(bbcode = post.bbcodeContent)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                CubicActionButton(
                    text = "FULL VIEW",
                    icon = Icons.Default.NorthEast,
                    primary = true,
                    onClick = onOpenFull
                )
            }
        }
    }
}

@Composable
private fun FullPostReaderScreen(
    post: InfoPost,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurfaceElevated)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CubicActionButton(
                text = "BACK",
                icon = Icons.Default.ArrowBack,
                primary = true,
                onClick = onBack
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ALTERIZA // FULL POST",
                    color = BbOrange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = post.fileName,
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BbBorder)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = post.title.uppercase(),
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 0.8.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorderBright)
            )
            BBCodeView(bbcode = post.bbcodeContent)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CaptchaCanvas(
    code: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val rng = SecureRandom()
        val w = size.width
        val h = size.height
        repeat(7) {
            drawLine(
                color = if (it % 2 == 0) BbOrange.copy(alpha = 0.45f) else BbBorderBright,
                start = Offset(rng.nextFloat() * w, rng.nextFloat() * h),
                end = Offset(rng.nextFloat() * w, rng.nextFloat() * h),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            )
        }
        val stepX = w / (code.length + 1)
        code.forEachIndexed { idx, ch ->
            val cx = stepX * (idx + 1)
            val cy = h * 0.5f + ((idx % 2) * 2 - 1) * 5f
            val angle = ((idx * 17 + code.hashCode()) % 28 - 14).toFloat()
            rotate(degrees = angle, pivot = Offset(cx, cy)) {
                translate(left = cx - 8f, top = cy - 11f) {
                    drawCaptchaGlyph(ch, if (idx % 2 == 0) BbWhite else BbOrange)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaptchaGlyph(ch: Char, color: Color) {
    val w = 16f
    val h = 22f
    val sw = 3.2f
    val cap = StrokeCap.Square
    val segs: List<Pair<Offset, Offset>> = when (ch) {
        'A' -> listOf(Offset(0f, h) to Offset(w / 2, 0f), Offset(w / 2, 0f) to Offset(w, h), Offset(3f, h * 0.6f) to Offset(w - 3f, h * 0.6f))
        'B' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, 0f) to Offset(w * 0.8f, 0f), Offset(w * 0.8f, 0f) to Offset(w * 0.8f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        'C' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        'D' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, 0f) to Offset(w * 0.75f, 0f), Offset(w * 0.75f, 0f) to Offset(w, h * 0.3f), Offset(w, h * 0.3f) to Offset(w, h * 0.7f), Offset(w, h * 0.7f) to Offset(w * 0.75f, h), Offset(0f, h) to Offset(w * 0.75f, h))
        'E' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w * 0.8f, h / 2), Offset(0f, h) to Offset(w, h))
        'F' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w * 0.8f, h / 2))
        'G' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h), Offset(w, h) to Offset(w, h / 2), Offset(w / 2, h / 2) to Offset(w, h / 2))
        'H' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(w, h), Offset(0f, h / 2) to Offset(w, h / 2))
        'J' -> listOf(Offset(w, 0f) to Offset(w, h), Offset(0f, h) to Offset(w, h), Offset(0f, h * 0.65f) to Offset(0f, h))
        'K' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h))
        'L' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        'M' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w / 2, h * 0.55f), Offset(w / 2, h * 0.55f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h))
        'N' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, h), Offset(w, h) to Offset(w, 0f))
        'P' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2))
        'Q' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h * 0.85f), Offset(w * 0.85f, h * 0.85f) to Offset(0f, h * 0.85f), Offset(0f, h * 0.85f) to Offset(0f, 0f), Offset(w * 0.55f, h * 0.6f) to Offset(w, h))
        'R' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w * 0.45f, h / 2) to Offset(w, h))
        'S' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        'T' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w / 2, 0f) to Offset(w / 2, h))
        'U' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h), Offset(w, h) to Offset(w, 0f))
        'V' -> listOf(Offset(0f, 0f) to Offset(w / 2, h), Offset(w / 2, h) to Offset(w, 0f))
        'W' -> listOf(Offset(0f, 0f) to Offset(w * 0.25f, h), Offset(w * 0.25f, h) to Offset(w / 2, h * 0.45f), Offset(w / 2, h * 0.45f) to Offset(w * 0.75f, h), Offset(w * 0.75f, h) to Offset(w, 0f))
        'X' -> listOf(Offset(0f, 0f) to Offset(w, h), Offset(w, 0f) to Offset(0f, h))
        'Y' -> listOf(Offset(0f, 0f) to Offset(w / 2, h / 2), Offset(w, 0f) to Offset(w / 2, h / 2), Offset(w / 2, h / 2) to Offset(w / 2, h))
        'Z' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        '2' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        '3' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h), Offset(w * 0.2f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h))
        '4' -> listOf(Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, 0f) to Offset(w, h))
        '5' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        '6' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        '7' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w * 0.35f, h))
        '8' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h), Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(w, h))
        else -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h), Offset(0f, 0f) to Offset(0f, h / 2), Offset(w, 0f) to Offset(w, h))
    }
    segs.forEach { (p1, p2) ->
        drawLine(color = color, start = p1, end = p2, strokeWidth = sw, cap = cap)
    }
}"""

assert old_announcement_footer in app_text
app_text = app_text.replace(old_announcement_footer, new_announcement_footer, 1)

old_player_sig = """@Composable
private fun CubicStreamingVideoPlayer(
    item: RemoteMediaItem,
    hwAcceleration: Boolean,
    onOrientationResolved: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val streamUrl = remember(item.id) { LocalStreamServer.getStreamUrl(item) }"""

new_player_sig = """@Composable
private fun CubicStreamingVideoPlayer(
    item: RemoteMediaItem,
    hwAcceleration: Boolean,
    storageRefreshTick: Int,
    onOrientationResolved: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val localFile = remember(item.id, storageRefreshTick) {
        AppStorageManager.findDownloadedFile(context, item)
    }
    val isLocalSource = localFile != null
    val playbackUri = remember(item.id, localFile?.absolutePath) {
        if (localFile != null) {
            if (localFile.name.endsWith(".vcv", ignoreCase = true)) {
                Uri.parse(LocalStreamServer.getLocalStreamUrl(item, localFile))
            } else {
                Uri.fromFile(localFile)
            }
        } else {
            Uri.parse(LocalStreamServer.getStreamUrl(item))
        }
    }"""

assert old_player_sig in app_text
app_text = app_text.replace(old_player_sig, new_player_sig, 1)

app_text = app_text.replace(
    "setVideoURI(Uri.parse(streamUrl))",
    "setVideoURI(playbackUri)",
    1
)

old_player_save_btn = """                            CubicActionButton(
                                text = "SAVE",
                                icon = Icons.Default.Download,
                                primary = false,
                                onClick = {
                                    interactionTick++
                                    onDownload()
                                }
                            )"""

new_player_save_btn = """                            CubicActionButton(
                                text = if (isLocalSource) "LOCAL" else "STREAM",
                                icon = if (isLocalSource) Icons.Default.Verified else Icons.Default.Download,
                                primary = isLocalSource,
                                onClick = {
                                    interactionTick++
                                    if (isLocalSource) {
                                        Toast.makeText(
                                            context,
                                            "Playing local file: ${localFile?.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onDownload()
                                    }
                                }
                            )"""

assert old_player_save_btn in app_text
app_text = app_text.replace(old_player_save_btn, new_player_save_btn, 1)

old_player_box_start = """    LaunchedEffect(videoView) {
        while (true) {
            val vv = videoView
            if (vv != null && ready && !isSeeking) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (positionMs >= durationMs - 400 && !vv.isPlaying && !isLooping) {
                    isPlaying = false
                }
            }
            delay(300L)
        }
    }"""

new_player_box_start = """    DisposableEffect(lifecycleOwner, videoView) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching {
                    videoView?.pause()
                    isPlaying = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { videoView?.stopPlayback() }
        }
    }

    LaunchedEffect(videoView) {
        while (true) {
            val vv = videoView
            if (vv != null && ready && !isSeeking) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (positionMs >= durationMs - 400 && !vv.isPlaying && !isLooping) {
                    isPlaying = false
                }
            }
            delay(300L)
        }
    }"""

assert old_player_box_start in app_text
app_text = app_text.replace(old_player_box_start, new_player_box_start, 1)

old_image_viewer = """@Composable
private fun CubicStreamingImageViewer(
    item: RemoteMediaItem,
    onOrientationResolved: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    var bitmap by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var isFullLoaded by remember(item.id) { mutableStateOf(false) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.id) {
        val fullBmp = AutoDiscoveryRepository.streamFullImageBitmap(item)
        if (fullBmp != null) {
            bitmap = fullBmp
            isFullLoaded = true
            onOrientationResolved(fullBmp.width > fullBmp.height)
        } else if (bitmap == null) {
            loadFailed = true
        }
    }"""

new_image_viewer = """@Composable
private fun CubicStreamingImageViewer(
    item: RemoteMediaItem,
    storageRefreshTick: Int,
    onOrientationResolved: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val localFile = remember(item.id, storageRefreshTick) {
        AppStorageManager.findDownloadedFile(context, item)
    }
    val isLocalSource = localFile != null
    var bitmap by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var isFullLoaded by remember(item.id) { mutableStateOf(false) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.id, localFile?.absolutePath) {
        val fullBmp = AutoDiscoveryRepository.loadBestImageBitmap(context, item)
        if (fullBmp != null) {
            bitmap = fullBmp
            isFullLoaded = true
            onOrientationResolved(fullBmp.width > fullBmp.height)
        } else if (bitmap == null) {
            loadFailed = true
        }
    }"""

assert old_image_viewer in app_text
app_text = app_text.replace(old_image_viewer, new_image_viewer, 1)

old_image_save_btn = """                CubicActionButton(
                    text = "SAVE",
                    icon = Icons.Default.Download,
                    primary = false,
                    onClick = {
                        interactionTick++
                        onDownload()
                    }
                )"""

new_image_save_btn = """                CubicActionButton(
                    text = if (isLocalSource) "LOCAL" else "STREAM",
                    icon = if (isLocalSource) Icons.Default.Verified else Icons.Default.Download,
                    primary = isLocalSource,
                    onClick = {
                        interactionTick++
                        if (isLocalSource) {
                            Toast.makeText(
                                context,
                                "Viewing local file: ${localFile?.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            onDownload()
                        }
                    }
                )"""

assert old_image_save_btn in app_text
app_text = app_text.replace(old_image_save_btn, new_image_save_btn, 1)

app_path.write_text(app_text, encoding="utf-8")
print("All 6 target files patched successfully.")
PY