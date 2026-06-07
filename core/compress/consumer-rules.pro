# Keep the JNI bridge intact: the native library resolves these by exact name.
-keepclasseswithmembernames,includedescriptorclasses class com.zorg.aetherpak.compress.NativeBridge {
    native <methods>;
}
-keep class com.zorg.aetherpak.compress.NativeBridge { *; }
