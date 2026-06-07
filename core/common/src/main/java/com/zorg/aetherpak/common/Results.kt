package com.zorg.aetherpak.common

/** Coarse machine-readable error taxonomy used across all engines. */
enum class ErrorCode {
    NO_ACCESS,
    ACCESS_DENIED,
    PACKAGE_NOT_FOUND,
    IO_ERROR,
    COMPRESSION_FAILED,
    DECOMPRESSION_FAILED,
    INSTALL_FAILED,
    PERMISSION_FIX_FAILED,
    MANIFEST_INVALID,
    UNSUPPORTED,
    CANCELLED,
    UNKNOWN
}

data class AetherError(val code: ErrorCode, val message: String)

/** Lightweight typed result. Engines return this instead of throwing across module boundaries. */
sealed interface OpResult<out T> {
    data class Success<out T>(val value: T) : OpResult<T>
    data class Failure(val error: AetherError, val cause: Throwable? = null) : OpResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    companion object {
        fun <T> success(value: T): OpResult<T> = Success(value)
        fun fail(code: ErrorCode, message: String, cause: Throwable? = null): OpResult<Nothing> =
            Failure(AetherError(code, message), cause)
    }
}

inline fun <T, R> OpResult<T>.map(transform: (T) -> R): OpResult<R> = when (this) {
    is OpResult.Success -> OpResult.Success(transform(value))
    is OpResult.Failure -> this
}

/** Progress event streamed to the UI during long operations. */
data class OperationProgress(
    val phase: Phase,
    val currentFile: String? = null,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val message: String? = null
) {
    enum class Phase {
        PREPARING,
        SCANNING,
        EXTRACTING,
        COMPRESSING,
        INSTALLING,
        RESTORING_FILES,
        FIXING_PERMISSIONS,
        VERIFYING,
        DONE,
        FAILED
    }

    val fraction: Float
        get() = if (totalBytes > 0) (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

typealias ProgressCallback = (OperationProgress) -> Unit
