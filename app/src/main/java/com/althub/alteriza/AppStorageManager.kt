package com.althub.alteriza

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

object AppStorageManager {
    private const val PREFS_NAME = "althub_settings_prefs"
    private const val KEY_HW_ACCEL = "hw_acceleration"
    private const val KEY_DL_FORMAT = "download_format_pref"
    private const val KEY_HISTORY = "media_history_json"

    fun isHwAccelerationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HW_ACCEL, true)
    }

    fun setHwAccelerationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HW_ACCEL, enabled)
            .apply()
    }

    fun getDownloadFormatPref(context: Context): DownloadFormatPref {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DL_FORMAT, DownloadFormatPref.ASK.name)
        return runCatching { DownloadFormatPref.valueOf(raw ?: DownloadFormatPref.ASK.name) }
            .getOrDefault(DownloadFormatPref.ASK)
    }

    fun setDownloadFormatPref(context: Context, pref: DownloadFormatPref) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DL_FORMAT, pref.name)
            .apply()
    }

    fun getDefaultDownloadDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(publicDownloads, "AltHub")
        if (dir.exists() || dir.mkdirs()) {
            if (dir.canWrite()) return dir
        }
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AltHub")
        fallback.mkdirs()
        return fallback
    }

    fun listDownloadedFiles(context: Context): List<DownloadedFileEntry> {
        val dir = getDefaultDownloadDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.length() > 0L }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val lower = f.name.lowercase()
                val isVid = lower.endsWith(".vcv") ||
                    lower.endsWith(".mp4") ||
                    lower.endsWith(".mkv") ||
                    lower.endsWith(".webm") ||
                    lower.endsWith(".mov") ||
                    lower.endsWith(".avi")
                DownloadedFileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    sizeBytes = f.length(),
                    isVideo = isVid
                )
            }
    }

    fun getCacheSizeBytes(context: Context): Long {
        fun dirSize(f: File?): Long {
            if (f == null || !f.exists()) return 0L
            if (f.isFile) return f.length()
            var total = 0L
            f.listFiles()?.forEach { child -> total += dirSize(child) }
            return total
        }
        return dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
    }

    fun clearAllCache(context: Context) {
        fun wipe(dir: File?) {
            dir?.listFiles()?.forEach { child ->
                if (child.isDirectory) wipe(child)
                runCatching { child.delete() }
            }
        }
        wipe(context.cacheDir)
        wipe(context.externalCacheDir)
        AutoDiscoveryRepository.clearMemoryCache()
    }

    fun recordHistory(context: Context, item: RemoteMediaItem) {
        val current = getHistory(context).toMutableList()
        current.removeAll { it.id == item.id }
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
        val trimmed = current.take(50)
        val arr = JSONArray()
        for (rec in trimmed) {
            val obj = JSONObject()
            obj.put("id", rec.id)
            obj.put("title", rec.title)
            obj.put("fileName", rec.fileName)
            obj.put("kind", rec.kind.name)
            obj.put("sourceType", rec.sourceType.name)
            obj.put("mediaUrl", rec.mediaUrl)
            obj.put("thumbnailUrl", rec.thumbnailUrl ?: "")
            obj.put("isContainer", rec.isContainer)
            obj.put("timestampMs", rec.timestampMs)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    fun getHistory(context: Context): List<HistoryRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = mutableListOf<HistoryRecord>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val kind = runCatching { MediaKind.valueOf(obj.optString("kind")) }.getOrNull() ?: continue
            val src = runCatching { SourceType.valueOf(obj.optString("sourceType")) }
                .getOrDefault(SourceType.DIRECT)
            val thumb = obj.optString("thumbnailUrl", "").ifBlank { null }
            list.add(
                HistoryRecord(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    fileName = obj.optString("fileName"),
                    kind = kind,
                    sourceType = src,
                    mediaUrl = obj.optString("mediaUrl"),
                    thumbnailUrl = thumb,
                    isContainer = obj.optBoolean("isContainer", false),
                    timestampMs = obj.optLong("timestampMs", 0L)
                )
            )
        }
        return list
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY)
            .apply()
    }

    suspend fun downloadMediaItem(
        context: Context,
        item: RemoteMediaItem,
        saveAsContainer: Boolean
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outDir = getDefaultDownloadDir(context)
            val tempRaw = File(context.cacheDir, "dl_tmp_${System.currentTimeMillis()}.bin")
            val conn = LocalStreamServer.openRemoteConnection(
                startUrl = item.mediaUrl,
                extraHeaders = item.headers
            )
            var detectedContainer = item.isContainer
            var originalExt = item.fileName.substringAfterLast('.', "").lowercase().ifBlank {
                if (item.kind == MediaKind.VIDEO) "mp4" else "jpg"
            }

            try {
                BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                    bis.mark(16)
                    val magic = ByteArray(4)
                    val n = bis.read(magic)
                    val mStr = if (n == 4) String(magic, Charsets.US_ASCII) else ""
                    bis.reset()

                    if (mStr == "SVCV" || mStr == "SICV") {
                        detectedContainer = true
                        val info = LocalStreamServer.parseContainerHeaderFromStream(bis)
                        originalExt = info.originalExt.ifBlank { originalExt }
                        FileOutputStream(tempRaw).use { fos ->
                            if (info.compression == 0) {
                                bis.copyTo(fos, 65_536)
                            } else {
                                ZstdDecompressInputStream(bis).use { zin ->
                                    zin.copyTo(fos, 65_536)
                                }
                            }
                        }
                    } else {
                        detectedContainer = false
                        FileOutputStream(tempRaw).use { fos ->
                            bis.copyTo(fos, 65_536)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val baseName = sanitizeFileName(
                item.fileName.substringBeforeLast('.', item.fileName)
            ).ifBlank { "media_${System.currentTimeMillis()}" }

            val finalFile = if (saveAsContainer) {
                val targetExt = if (item.kind == MediaKind.VIDEO) "vcv" else "icv"
                val dest = uniqueFile(outDir, "$baseName.$targetExt")
                val renamedInput = File(context.cacheDir, "$baseName.$originalExt")
                if (renamedInput.exists()) renamedInput.delete()
                tempRaw.renameTo(renamedInput)
                val code = NativeCore.encodeFromFile(
                    renamedInput.absolutePath,
                    dest.absolutePath,
                    1,
                    item.kind == MediaKind.VIDEO
                )
                renamedInput.delete()
                if (code != 0) {
                    throw IllegalStateException("Container encode failed ($code)")
                }
                dest
            } else {
                val cleanExt = if (originalExt == "vcv") {
                    "mp4"
                } else if (originalExt == "icv") {
                    "jpg"
                } else {
                    originalExt
                }
                val dest = uniqueFile(outDir, "$baseName.$cleanExt")
                FileOutputStream(dest).use { fos ->
                    tempRaw.inputStream().use { fis -> fis.copyTo(fos, 65_536) }
                }
                tempRaw.delete()
                dest
            }

            finalFile
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(80)
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var idx = 1
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "${base}_$idx" else "${base}_$idx.$ext"
            candidate = File(dir, nextName)
            idx++
        }
        return candidate
    }
}
