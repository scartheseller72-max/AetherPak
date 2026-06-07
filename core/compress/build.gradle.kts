plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zorg.aetherpak.compress"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        // The native core is shipped per-ABI. These are the ABIs CI cross-compiles
        // with cargo-ndk; anything else gets isAvailable() == false at runtime.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Prebuilt .so files are committed/produced under src/main/jniLibs/<abi>/ and
    // packaged as-is. Keep them uncompressed so they can be mmap'd by the loader.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/libaetherpak.so"
        }
    }

    // NOTE: there is intentionally NO externalNativeBuild block here. We do NOT
    // compile Rust through Gradle/CMake. The .so is a prebuilt artifact dropped
    // into src/main/jniLibs by CI.
}

/*
 * ---------------------------------------------------------------------------
 * cargo-ndk wiring (runs in CI, NOT as a Gradle task here)
 * ---------------------------------------------------------------------------
 * The native library lives in  rust/aetherpak-core  ([lib] name = "aetherpak").
 * CI builds it for each Android ABI and places the result directly into this
 * module's jniLibs source set:
 *
 *   cd rust/aetherpak-core
 *   cargo ndk \
 *     -t aarch64-linux-android \      # -> arm64-v8a
 *     -t armv7-linux-androideabi \    # -> armeabi-v7a
 *     -t x86_64-linux-android \       # -> x86_64
 *     -o ../../core/compress/src/main/jniLibs \
 *     build --release
 *
 * cargo-ndk maps Rust targets to Android ABI directory names and emits:
 *   core/compress/src/main/jniLibs/arm64-v8a/libaetherpak.so
 *   core/compress/src/main/jniLibs/armeabi-v7a/libaetherpak.so
 *   core/compress/src/main/jniLibs/x86_64/libaetherpak.so
 *
 * AGP then packages whatever .so files exist under jniLibs into the AAR/APK.
 * If a given ABI's .so is missing, System.loadLibrary fails gracefully and
 * AetherArchive.isAvailable() returns false (see NativeBridge.loaded).
 * ---------------------------------------------------------------------------
 */

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
