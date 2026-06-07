//! AetherPak native compression core.
//!
//! A streaming `.ark` archive writer/reader exposed to Android over JNI. Three
//! container codecs sit behind a uniform enum-dispatched API:
//!
//!   * ZSTD      — a TAR stream wrapped in a single multi-frame zstd encoder.
//!   * ZIP       — native zip container, deflate per entry.
//!   * SEVEN_ZIP — sevenz-rust (LZMA2) container.
//!
//! GB-scale payloads are NEVER fully buffered in memory: the ZIP path streams
//! entry bytes directly, while the ZSTD and 7z paths spill each entry to a temp
//! file on disk (constant RAM) before adding/decoding it.
//!
//! The JNI entry points live in [`jni_bridge`] and are named for the Kotlin
//! class `com.zorg.aetherpak.compress.NativeBridge`. The compiled artifact is
//! `libaetherpak.so` (see `[lib] name = "aetherpak"` in Cargo.toml), loaded on
//! the Kotlin side with `System.loadLibrary("aetherpak")`.

pub mod archive_reader;
pub mod archive_writer;
pub mod codec;
pub mod jni_bridge;

// Re-export the core types for any in-crate integration tests.
pub use archive_reader::{ArchiveReaderHandle, EntryHeader};
pub use archive_writer::{ArchiveWriterHandle, EntryMeta};
pub use codec::{Codec, CoreError, CoreResult};
