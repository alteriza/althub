package com.althub.alteriza

object NativeCore {
    init {
        System.loadLibrary("althub_core")
    }

    external fun getPrimaryCdnUrl(): String

    external fun getHiddenCdnUrl(): String

    external fun getPrimaryPostUrl(): String

    external fun getDefaultRepoUrl(): String

    external fun getUserAgent(): String

    external fun buildGitHubRepoApi(owner: String, repo: String): String

    external fun buildGitHubTreeApi(owner: String, repo: String, branch: String): String

    external fun buildGitHubContentsApi(owner: String, repo: String, path: String, branch: String): String

    external fun buildRawGitHubUrl(owner: String, repo: String, branch: String, encodedPath: String): String

    external fun buildDriveEmbedUrl(folderId: String): String

    external fun buildDriveDownloadUrl(fileId: String): String

    external fun parseContainerHeaderBytes(input: ByteArray, inputLen: Int): String?

    external fun readHeader(inputPath: String): String?

    external fun decodeToFile(inputPath: String, outputPath: String): Int

    external fun decodeToBytes(inputPath: String): ByteArray?

    external fun encodeFromFile(
        inputPath: String,
        outputPath: String,
        compressionLevel: Int,
        isVideo: Boolean
    ): Int

    external fun zstdStreamCreate(): Long

    external fun zstdStreamDecompress(handle: Long, input: ByteArray, inputLen: Int): ByteArray?

    external fun zstdStreamFree(handle: Long)
}
