package com.althub.alteriza

object NativeCore {
    init {
        System.loadLibrary("althub_core")
    }

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
