package com.zorg.aetherpak.compress

/**
 * Thin JNI surface over the Rust `aetherpak-core` crate (libaetherpak.so).
 *
 * The native symbol names are generated from this exact class + method names:
 *   Java_com_zorg_aetherpak_compress_NativeBridge_<method>
 * Do NOT rename this class, its package, or these methods without updating the
 * `#[no_mangle]` functions in rust/aetherpak-core/src/jni_bridge.rs.
 *
 * All methods are 1:1 with the native entry points. Handles are opaque [Long]
 * values (Rust `Box` pointers); callers must pass them back unchanged and call
 * the matching close to release native memory.
 *
 * Codec ids (jint): 0 = ZSTD, 1 = ZIP, 2 = SEVEN_ZIP. level 0 = codec default.
 */
internal object NativeBridge {

    /** True once libaetherpak.so loaded successfully; false on UnsatisfiedLinkError. */
    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("aetherpak")
            true
        } catch (t: Throwable) {
            // Missing / incompatible .so (e.g. unsupported ABI). isAvailable() reports this.
            false
        }
    }

    // -- writer ------------------------------------------------------------
    external fun openWriter(outPath: String, codec: Int, level: Int): Long
    external fun writePutEntry(
        handle: Long,
        path: String,
        size: Long,
        mode: Int,
        isDir: Boolean,
        isSymlink: Boolean,
        linkTarget: String?
    )
    external fun writeChunk(handle: Long, buf: ByteArray, len: Int)
    external fun writeCloseEntry(handle: Long)
    external fun writeClose(handle: Long)

    // -- reader ------------------------------------------------------------
    external fun openReader(inPath: String, codec: Int): Long
    external fun readNextEntry(handle: Long): String?
    external fun readChunk(handle: Long, buf: ByteArray): Int
    external fun readClose(handle: Long)
}
