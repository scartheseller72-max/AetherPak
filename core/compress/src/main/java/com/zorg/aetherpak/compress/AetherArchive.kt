package com.zorg.aetherpak.compress

import com.zorg.aetherpak.common.ArchiveEntryHeader
import com.zorg.aetherpak.common.ArchiveReader
import com.zorg.aetherpak.common.ArchiveWriter
import com.zorg.aetherpak.common.CodecType
import com.zorg.aetherpak.common.NativeArchive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The production [NativeArchive] backed by the Rust `aetherpak-core` JNI library.
 *
 * Produces / consumes single-stream `.ark` containers in one of three codecs.
 * Each [ArchiveWriter] / [ArchiveReader] wraps an opaque native handle ([Long])
 * and delegates straight to [NativeBridge]; payloads stream in chunks so GB-scale
 * blobs are never materialized in memory.
 */
object AetherArchive : NativeArchive {

    /** Header JSON emitted by the native `readNextEntry`. Field names must match. */
    @Serializable
    private data class NativeHeader(
        val path: String,
        val size: Long,
        val mode: Int,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val linkTarget: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = NativeBridge.loaded

    private fun codecId(codec: CodecType): Int = when (codec) {
        CodecType.ZSTD -> 0
        CodecType.ZIP -> 1
        CodecType.SEVEN_ZIP -> 2
    }

    override fun openWriter(outPath: String, codec: CodecType, level: Int): ArchiveWriter {
        check(NativeBridge.loaded) { "aetherpak native library is not available" }
        val handle = NativeBridge.openWriter(outPath, codecId(codec), level)
        check(handle != 0L) { "openWriter returned a null handle for $outPath" }
        return NativeArchiveWriter(handle)
    }

    override fun openReader(inPath: String, codec: CodecType): ArchiveReader {
        check(NativeBridge.loaded) { "aetherpak native library is not available" }
        val handle = NativeBridge.openReader(inPath, codecId(codec))
        check(handle != 0L) { "openReader returned a null handle for $inPath" }
        return NativeArchiveReader(handle, json)
    }

    // -- writer impl -------------------------------------------------------

    private class NativeArchiveWriter(private var handle: Long) : ArchiveWriter {
        private var closed = false

        override fun putEntry(header: ArchiveEntryHeader) {
            ensureOpen()
            NativeBridge.writePutEntry(
                handle = handle,
                path = header.path,
                size = header.size,
                mode = header.mode,
                isDir = header.isDirectory,
                isSymlink = header.isSymlink,
                linkTarget = header.linkTarget
            )
        }

        override fun write(buf: ByteArray, len: Int) {
            ensureOpen()
            require(len >= 0 && len <= buf.size) { "len $len out of bounds for buffer ${buf.size}" }
            if (len == 0) return
            NativeBridge.writeChunk(handle, buf, len)
        }

        override fun closeEntry() {
            ensureOpen()
            NativeBridge.writeCloseEntry(handle)
        }

        override fun close() {
            if (closed) return
            closed = true
            val h = handle
            handle = 0L
            if (h != 0L) NativeBridge.writeClose(h)
        }

        private fun ensureOpen() {
            check(!closed && handle != 0L) { "writer is closed" }
        }
    }

    // -- reader impl -------------------------------------------------------

    private class NativeArchiveReader(
        private var handle: Long,
        private val json: Json
    ) : ArchiveReader {
        private var closed = false

        override fun nextEntry(): ArchiveEntryHeader? {
            ensureOpen()
            val raw = NativeBridge.readNextEntry(handle) ?: return null
            val h = json.decodeFromString(NativeHeader.serializer(), raw)
            return ArchiveEntryHeader(
                path = h.path,
                size = h.size,
                mode = h.mode,
                isDirectory = h.isDirectory,
                isSymlink = h.isSymlink,
                linkTarget = h.linkTarget
            )
        }

        override fun read(buf: ByteArray): Int {
            ensureOpen()
            if (buf.isEmpty()) return 0
            return NativeBridge.readChunk(handle, buf)
        }

        override fun close() {
            if (closed) return
            closed = true
            val h = handle
            handle = 0L
            if (h != 0L) NativeBridge.readClose(h)
        }

        private fun ensureOpen() {
            check(!closed && handle != 0L) { "reader is closed" }
        }
    }
}
