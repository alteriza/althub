package com.althub.alteriza

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AppStorageManager {
    private const val PREFS_STORAGE = "althub_storage_prefs"
    private const val KEY_HW_ACCEL = "hw_acceleration"
    private const val KEY_DL_FORMAT_PREF = "download_format_pref"
    private const val KEY_HISTORY_JSON = "watch_history_json"
    private const val MAX_HISTORY_ITEMS = 120

    private val VIDEO_EXTENSIONS = setOf(
        "vcv", "mp4", "mkv", "webm", "mov", "avi", "m4v",
        "3gp", "ts", "mts", "m2ts", "flv", "wmv", "ogv", "mpeg", "mpg"
    )

    fun isHwAccelerationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HW_ACCEL, true)
    }

    fun setHwAccelerationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HW_ACCEL, enabled).apply()
    }

    fun getDownloadFormatPref(context: Context): DownloadFormatPref {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DL_FORMAT_PREF, DownloadFormatPref.ASK.name)
        return runCatching { DownloadFormatPref.valueOf(raw ?: DownloadFormatPref.ASK.name) }
            .getOrDefault(DownloadFormatPref.ASK)
    }

    fun setDownloadFormatPref(context: Context, pref: DownloadFormatPref) {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DL_FORMAT_PREF, pref.name).apply()
    }

    fun getDefaultDownloadDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicAltHub = File(publicDownloads, "AltHub")
        if (publicAltHub.exists() || publicAltHub.mkdirs()) {
            if (publicAltHub.canWrite()) {
                return publicAltHub
            }
        }
        val appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(appDownloads, "AltHub").apply { mkdirs() }
    }

    fun recordHistory(context: Context, item: RemoteMediaItem) {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        val current = getHistory(context).filterNot { it.id == item.id }.toMutableList()
        current.add(
            0,
            HistoryRecord(
                id = item.id,
                title = item.title,
                fileName = item.fileName,
                kind = item.kind,
                sourceType = item.sourceType,
                mediaUrl = item.mediaUrl,
                thumbnailUrl = item.thumbnailUrl,
                isContainer = item.isContainer,
                timestampMs = System.currentTimeMillis()
            )
        )
        val trimmed = current.take(MAX_HISTORY_ITEMS)
        val arr = JSONArray()
        for (rec in trimmed) {
            val obj = JSONObject().apply {
                put("id", rec.id)
                put("title", rec.title)
                put("fileName", rec.fileName)
                put("kind", rec.kind.name)
                put("sourceType", rec.sourceType.name)
                put("mediaUrl", rec.mediaUrl)
                put("thumbnailUrl", rec.thumbnailUrl ?: JSONObject.NULL)
                put("isContainer", rec.isContainer)
                put("timestampMs", rec.timestampMs)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
    }

    fun getHistory(context: Context): List<HistoryRecord> {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<HistoryRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val kind = runCatching { MediaKind.valueOf(obj.optString("kind")) }
                .getOrDefault(MediaKind.VIDEO)
            val source = runCatching { SourceType.valueOf(obj.optString("sourceType")) }
                .getOrDefault(SourceType.DIRECT)
            val thumb = if (obj.isNull("thumbnailUrl")) null else obj.optString("thumbnailUrl").ifBlank { null }
            result.add(
                HistoryRecord(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    fileName = obj.optString("fileName"),
                    kind = kind,
                    sourceType = source,
                    mediaUrl = obj.optString("mediaUrl"),
                    thumbnailUrl = thumb,
                    isContainer = obj.optBoolean("isContainer", false),
                    timestampMs = obj.optLong("timestampMs", 0L)
                )
            )
        }
        return result
    }

    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_STORAGE, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }

    fun getCacheSizeBytes(context: Context): Long {
        var total = dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        return total
    }

    fun clearAllCache(context: Context) {
        AutoDiscoveryRepository.clearMemoryCache()
        deleteChildren(context.cacheDir)
        context.externalCacheDir?.let { deleteChildren(it) }
    }

    fun listDownloadedFiles(context: Context): List<DownloadedFileEntry> {
        val dir = getDefaultDownloadDir(context)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0L && !it.name.startsWith(".tmp_") }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val ext = f.extension.lowercase()
                DownloadedFileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    sizeBytes = f.length(),
                    isVideo = ext in VIDEO_EXTENSIONS
                )
            }
    }

    suspend fun downloadMediaItem(
        context: Context,
        item: RemoteMediaItem,
        saveAsContainer: Boolean
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = getDefaultDownloadDir(context)
            val isVideo = item.kind == MediaKind.VIDEO
            val baseName = sanitizeFileName(item.fileName.substringBeforeLast('.', item.fileName))
                .ifBlank { "althub_${System.currentTimeMillis()}" }
            val currentExt = item.fileName.substringAfterLast('.', "").lowercase()

            val conn = LocalStreamServer.openRemoteConnection(
                startUrl = item.mediaUrl,
                extraHeaders = item.headers
            )
            try {
                BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                    bis.mark(16)
                    val magic = ByteArray(4)
                    val readCount = bis.read(magic)
                    val magicStr = if (readCount == 4) String(magic, Charsets.US_ASCII) else ""
                    val upstreamIsContainer = magicStr == "SVCV" || magicStr == "SICV"
                    bis.reset()

                    val finalFile = if (saveAsContainer) {
                        val targetExt = if (isVideo) "vcv" else "icv"
                        val outTarget = uniqueTargetFile(destDir, baseName, targetExt)
                        if (upstreamIsContainer) {
                            writeStreamToFile(bis, outTarget)
                            outTarget
                        } else {
                            val rawExt = if (currentExt.isNotBlank() && currentExt != "vcv" && currentExt != "icv") {
                                currentExt
                            } else if (isVideo) {
                                "mp4"
                            } else {
                                "jpg"
                            }
                            val tempRaw = File(context.cacheDir, ".tmp_enc_${System.currentTimeMillis()}.$rawExt")
                            try {
                                writeStreamToFile(bis, tempRaw)
                                val encodeCode = NativeCore.encodeFromFile(
                                    tempRaw.absolutePath,
                                    outTarget.absolutePath,
                                    2,
                                    isVideo
                                )
                                if (encodeCode != 0 || !outTarget.exists() || outTarget.length() == 0L) {
                                    outTarget.delete()
                                    throw IOException("Container encoding failed (code $encodeCode)")
                                }
                                outTarget
                            } finally {
                                tempRaw.delete()
                            }
                        }
                    } else {
                        if (upstreamIsContainer) {
                            val headerInfo = LocalStreamServer.parseContainerHeaderFromStream(bis)
                            val origExt = headerInfo.originalExt
                                .ifBlank { if (isVideo) "mp4" else "jpg" }
                                .lowercase()
                            val outTarget = uniqueTargetFile(destDir, baseName, origExt)
                            if (headerInfo.compression == 0) {
                                writeStreamToFile(bis, outTarget)
                            } else {
                                ZstdDecompressInputStream(bis).use { zstdIn ->
                                    writeStreamToFile(zstdIn, outTarget)
                                }
                            }
                            outTarget
                        } else {
                            val origExt = if (currentExt.isNotBlank() && currentExt != "vcv" && currentExt != "icv") {
                                currentExt
                            } else if (isVideo) {
                                "mp4"
                            } else {
                                "jpg"
                            }
                            val outTarget = uniqueTargetFile(destDir, baseName, origExt)
                            writeStreamToFile(bis, outTarget)
                            outTarget
                        }
                    }

                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(finalFile.absolutePath),
                        null,
                        null
                    )
                    finalFile
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun writeStreamToFile(input: java.io.InputStream, dest: File) {
        val tempFile = File(dest.parentFile, ".tmp_${dest.name}")
        if (tempFile.exists()) tempFile.delete()
        try {
            FileOutputStream(tempFile).use { fos ->
                val buf = ByteArray(65_536)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                }
                fos.flush()
            }
            if (!tempFile.renameTo(dest)) {
                tempFile.copyTo(dest, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private fun uniqueTargetFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { child ->
            sum += dirSize(child)
        }
        return sum
    }

    private fun deleteChildren(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            runCatching { child.delete() }
        }
    }
}