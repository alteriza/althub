package com.althub.alteriza

enum class SourceType {
    GITHUB,
    GDRIVE
}

enum class MediaKind {
    VIDEO,
    IMAGE
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
    val folderPath: String
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
