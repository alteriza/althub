package com.althub.alteriza

enum class SourceType {
    GITHUB,
    GDRIVE,
    PIXELDRAIN,
    GOFILE,
    ACEFILE,
    FILEDOM,
    DIRECT
}

enum class MediaKind {
    VIDEO,
    IMAGE
}

enum class DownloadFormatPref(val label: String) {
    ASK("ASK EVERY TIME"),
    ORIGINAL("ORIGINAL FORMAT"),
    CONTAINER("CONTAINER (.VCV / .ICV)")
}

data class RemoteMediaItem(
    val id: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val sourceType: SourceType,
    val mediaUrl: String,
    val thumbnailUrl: String?,
    val sizeBytes: Long,
    val folderPath: String,
    val isContainer: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val localPath: String? = null,
    val videoTags: String? = null
)

data class ContainerHeaderInfo(
    val isContainer: Boolean,
    val headerOffset: Long,
    val compression: Int,
    val originalExt: String,
    val originalSize: Long
)

data class InfoPost(
    val id: String,
    val title: String,
    val fileName: String,
    val bbcodeContent: String,
    val sourceUrl: String
)

data class DiscoveryResult(
    val mediaItems: List<RemoteMediaItem>,
    val infoPosts: List<InfoPost>,
    val activeSources: List<String>,
    val errorMessage: String? = null
)

data class HistoryRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val sourceType: SourceType,
    val mediaUrl: String,
    val thumbnailUrl: String?,
    val isContainer: Boolean,
    val timestampMs: Long
)

data class DownloadedFileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isVideo: Boolean
)

data class CaptchaChallenge(
    val challengeId: String,
    val code: String
)