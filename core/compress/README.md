# :core:compress

Kotlin module that loads the AetherPak native compression core (`libaetherpak.so`,
built from `rust/aetherpak-core`) and implements the shared
`com.zorg.aetherpak.common.NativeArchive` contract.

## What's here

- `NativeBridge.kt` — `internal object` declaring the `external` JNI functions.
  Loads the library in its `init` block via `System.loadLibrary("aetherpak")`
  and exposes `NativeBridge.loaded`.
- `AetherArchive.kt` — `object AetherArchive : NativeArchive`. Wraps native
  `Long` handles in streaming `ArchiveWriter` / `ArchiveReader` implementations.
  `isAvailable()` reflects whether the `.so` loaded; reader headers are parsed
  from JSON with `kotlinx.serialization`.
- `src/main/jniLibs/<abi>/libaetherpak.so` — **prebuilt** native artifacts,
  produced by CI (see below). Not built by Gradle.

## Codec / handle contract

| Kotlin `CodecType` | jint | container |
|--------------------|------|-----------|
| `ZSTD`             | 0    | TAR + multi-frame zstd |
| `ZIP`              | 1    | native zip (deflate)   |
| `SEVEN_ZIP`        | 2    | sevenz-rust (LZMA2)    |

`level == 0` selects the codec default (zstd 9, deflate 6).

## Building the native `.so` with cargo-ndk

The Rust crate is **not** compiled through Gradle (no `externalNativeBuild`).
CI cross-compiles it with [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) and
drops the output straight into this module's `jniLibs` source set:

```bash
# one-time
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

cd rust/aetherpak-core
cargo ndk \
  -t aarch64-linux-android \    # -> arm64-v8a
  -t armv7-linux-androideabi \  # -> armeabi-v7a
  -t x86_64-linux-android \     # -> x86_64
  -o ../../core/compress/src/main/jniLibs \
  build --release
```

This produces:

```
core/compress/src/main/jniLibs/
├── arm64-v8a/libaetherpak.so
├── armeabi-v7a/libaetherpak.so
└── x86_64/libaetherpak.so
```

`[lib] name = "aetherpak"` in `Cargo.toml` is what makes the output
`libaetherpak.so`, which is why the Kotlin side calls
`System.loadLibrary("aetherpak")`.

AGP packages whatever `.so` files exist under `jniLibs` (filtered by the
`ndk.abiFilters` in `build.gradle.kts`). If an ABI's `.so` is missing,
`System.loadLibrary` throws `UnsatisfiedLinkError`, `NativeBridge.loaded`
becomes `false`, and `AetherArchive.isAvailable()` returns `false` so callers
can fall back gracefully instead of crashing.
