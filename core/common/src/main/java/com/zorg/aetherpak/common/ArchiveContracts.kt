package com.zorg.aetherpak.common

import java.io.Closeable

/**
 * Header for one streamed archive entry. Payload bytes (for files) are written/read separately
 * so GB-scale game blobs never have to be materialized in memory.
 */
data class ArchiveEntryHeader(
    val path: String,
    val size: Long,
    val mode: Int,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null
)

/**
 * Streaming writer over a native (.ark) archive. Usage:
 *   writer.putEntry(header); writer.write(buf, n)...; writer.closeEntry()  // repeat
 *   writer.close()
 * Implemented in :core:compress backed by the Rust aetherpak-core JNI library.
 */
interface ArchiveWriter : Closeable {
    fun putEntry(header: ArchiveEntryHeader)
    fun write(buf: ByteArray, len: Int)
    fun closeEntry()
}

/**
 * Streaming reader over a native (.ark) archive. Usage:
 *   while (reader.nextEntry()?.also { h -> ... } != null) {
 *       var n = reader.read(buf); while (n >= 0) { ...; n = reader.read(buf) }
 *   }
 */
interface ArchiveReader : Closeable {
    /** Advance to the next entry; returns its header, or null at end of archive. */
    fun nextEntry(): ArchiveEntryHeader?
    /** Read up to buf.size bytes of the current entry's payload; returns -1 when entry exhausted. */
    fun read(buf: ByteArray): Int
}

/**
 * Factory over the native compression core. The default compression [level] is codec-specific;
 * pass 0 to let the implementation choose a sensible default.
 */
interface NativeArchive {
    fun isAvailable(): Boolean
    fun openWriter(outPath: String, codec: CodecType, level: Int = 0): ArchiveWriter
    fun openReader(inPath: String, codec: CodecType): ArchiveReader
}
