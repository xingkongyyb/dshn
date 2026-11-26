package dev.dshn.app.runtime

import java.nio.ByteBuffer

/**
 * JNI bridge to the Rust performance core (`libdshn_core.so`).
 *
 * All entrypoints degrade gracefully (returning `isAvailable == false`) on host JVM unit tests
 * or builds where the native library has not been loaded yet, allowing transparent Kotlin fallbacks.
 */
object DshnNativeCore {

    interface ExtractProgressCallback {
        fun onProgress(compressedBytesRead: Long, entriesExtracted: Int)
    }

    interface FastChatCallback {
        fun onTextDelta(chunk: String)
        fun onReasoningDelta(chunk: String)
        fun onUsage(promptTokens: Long, completionTokens: Long, totalTokens: Long)
        fun onDone(finishReason: String)
        fun onError(message: String)
    }

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("dshn_core")
        true
    }.getOrDefault(false)

    /**
     * Returns version & compiled feature flags from `libdshn_core.so` (e.g. `dshn-core 0.1.0 (gzip,zstd,sha2,fast-chat,frame)`).
     */
    fun engineInfo(): String = if (isAvailable) runCatching { nativeEngineInfo() }.getOrDefault("unavailable") else "jvm-fallback"

    /**
     * Extracts a `.tar.gz` or `.tar.zst` archive directly from an Android Asset or file path
     * in Rust with single-pass hardware SHA-256 verification, parent-directory deduplication,
     * and direct POSIX `chmod`/`symlink` syscalls.
     *
     * Returns a JSON summary `{"entries":N,"files":N,"symlinks":N,"hardlinks":N,"bytes":N,"sha256":"..."}`
     * or throws `IOException` on error / SHA-256 mismatch.
     */
    fun extractTarFile(
        archivePath: String,
        offsetBytes: Long,
        lengthBytes: Long,
        destDir: String,
        stripComponents: Int,
        expectedSha256: String?,
        isZstd: Boolean,
        callback: ExtractProgressCallback?
    ): String = nativeExtractTarFile(
        archivePath,
        offsetBytes,
        lengthBytes,
        destDir,
        stripComponents,
        expectedSha256.orEmpty(),
        isZstd,
        callback
    )

    /**
     * Direct Rust TLS+HTTP/2 streaming chat request (OpenAI-compatible `/v1/chat/completions`),
     * supporting text + multimodal base64 image content blocks without waiting for Ubuntu/Node/DSH.
     *
     * Returns a cancellation token ID (> 0) or runs synchronously until stream completion.
     */
    fun runFastChatStream(
        endpointUrl: String,
        apiKey: String,
        requestBodyJson: String,
        callback: FastChatCallback
    ): Boolean = nativeRunFastChatStream(endpointUrl, apiKey, requestBodyJson, callback)

    /** Requests cancellation of any in-flight `runFastChatStream` call. */
    fun cancelFastChat() {
        if (isAvailable) runCatching { nativeCancelFastChat() }
    }

    /**
     * Zero-copy RGBA `DirectByteBuffer` -> downscaled JPEG byte array with dirty-frame hashing.
     * Returns `null` if the frame hash matches [previousHash] (frame unchanged, skip transmission).
     */
    fun encodeVirtualFrame(
        rgbaBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        dstWidth: Int,
        dstHeight: Int,
        jpegQuality: Int,
        previousHash: Long,
        outHash: LongArray
    ): ByteArray? = nativeEncodeVirtualFrame(
        rgbaBuffer,
        srcWidth,
        srcHeight,
        rowStride,
        dstWidth,
        dstHeight,
        jpegQuality,
        previousHash,
        outHash
    )

    @JvmStatic
    private external fun nativeEngineInfo(): String

    @JvmStatic
    private external fun nativeExtractTarFile(
        archivePath: String,
        offsetBytes: Long,
        lengthBytes: Long,
        destDir: String,
        stripComponents: Int,
        expectedSha256: String,
        isZstd: Boolean,
        callback: ExtractProgressCallback?
    ): String

    @JvmStatic
    private external fun nativeRunFastChatStream(
        endpointUrl: String,
        apiKey: String,
        requestBodyJson: String,
        callback: FastChatCallback
    ): Boolean

    @JvmStatic
    private external fun nativeCancelFastChat()

    @JvmStatic
    private external fun nativeEncodeVirtualFrame(
        rgbaBuffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        dstWidth: Int,
        dstHeight: Int,
        jpegQuality: Int,
        previousHash: Long,
        outHash: LongArray
    ): ByteArray?
}
