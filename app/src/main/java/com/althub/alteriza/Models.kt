package com.althub.alteriza

enum class MediaKind {
    VIDEO,
    IMAGE
}

enum class SourceType {
    GITHUB,
    GDRIVE,
    PIXELDRAIN,
    GOFILE,
    ACEFILE,
    FILEDOM,
    YOUTUBE,
    DIRECT
}

enum class DownloadFormatPref {
    ASK,
    CONTAINER,
    ORIGINAL
}

data class RemoteMediaItem(
    val id: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val sourceType: SourceType,
    val mediaUrl: String,
    val thumbnailUrl: String? = null,
    val isContainer: Boolean = false,
    val sizeBytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList()
)

data class HistoryRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val sourceType: SourceType,
    val mediaUrl: String,
    val thumbnailUrl: String? = null,
    val isContainer: Boolean = false,
    val timestampMs: Long
)

data class DownloadedFileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isVideo: Boolean
)

data class CdnDiscoveryResult(
    val items: List<RemoteMediaItem>,
    val announcements: List<String>,
    val activeSources: Int
)
