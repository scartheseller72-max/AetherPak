//! JNI surface for `com.zorg.aetherpak.compress.NativeBridge`.
//!
//! Handles are opaque `jlong` values produced via `Box::into_raw` and reclaimed
//! with `Box::from_raw` on close. Every entry point guards against null/garbage
//! handles. Errors are mapped to a thrown Java `RuntimeException` (and a sentinel
//! return value where one is expected).

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

use crate::archive_reader::ArchiveReaderHandle;
use crate::archive_writer::{ArchiveWriterHandle, EntryMeta};
use crate::codec::{Codec, CoreError, CoreResult};

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

fn throw(env: &mut JNIEnv, msg: &str) {
    // Best-effort; if throwing itself fails there's nothing more we can do.
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> CoreResult<String> {
    let js = env
        .get_string(s)
        .map_err(|e| CoreError::InvalidArgument(format!("jstring: {e}")))?;
    Ok(js.to_str().map_err(|_| CoreError::Utf8)?.to_owned())
}

/// Read an optional JString (may be a Java null reference).
fn opt_jstring(env: &mut JNIEnv, s: &JString) -> CoreResult<Option<String>> {
    if s.is_null() {
        return Ok(None);
    }
    Ok(Some(jstring_to_string(env, s)?))
}

// SAFETY: callers pass back a handle previously returned by the matching open*.
unsafe fn writer_ref<'a>(handle: jlong) -> Option<&'a mut ArchiveWriterHandle> {
    if handle == 0 {
        return None;
    }
    Some(&mut *(handle as *mut ArchiveWriterHandle))
}

unsafe fn reader_ref<'a>(handle: jlong) -> Option<&'a mut ArchiveReaderHandle> {
    if handle == 0 {
        return None;
    }
    Some(&mut *(handle as *mut ArchiveReaderHandle))
}

// ---------------------------------------------------------------------------
// writer
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_openWriter(
    mut env: JNIEnv,
    _class: JClass,
    out_path: JString,
    codec: jint,
    level: jint,
) -> jlong {
    let result: CoreResult<jlong> = (|| {
        let path = jstring_to_string(&mut env, &out_path)?;
        let codec = Codec::from_jint(codec).ok_or(CoreError::InvalidCodec(codec))?;
        let handle = ArchiveWriterHandle::open(&path, codec, level)?;
        Ok(Box::into_raw(Box::new(handle)) as jlong)
    })();
    match result {
        Ok(h) => h,
        Err(e) => {
            throw(&mut env, &format!("openWriter failed: {e}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_writePutEntry(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
    size: jlong,
    mode: jint,
    is_dir: jboolean,
    is_symlink: jboolean,
    link_target: JString,
) {
    let result: CoreResult<()> = (|| {
        let writer = unsafe { writer_ref(handle) }.ok_or(CoreError::InvalidHandle)?;
        let meta = EntryMeta {
            path: jstring_to_string(&mut env, &path)?,
            size,
            mode,
            is_dir: is_dir != 0,
            is_symlink: is_symlink != 0,
            link_target: opt_jstring(&mut env, &link_target)?,
        };
        writer.put_entry(meta)
    })();
    if let Err(e) = result {
        throw(&mut env, &format!("writePutEntry failed: {e}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_writeChunk(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buf: JByteArray,
    len: jint,
) {
    let result: CoreResult<()> = (|| {
        let writer = unsafe { writer_ref(handle) }.ok_or(CoreError::InvalidHandle)?;
        if len < 0 {
            return Err(CoreError::InvalidArgument("negative len".into()));
        }
        let n = len as usize;
        // Copy out of the Java heap into a Rust buffer (chunk-sized, not whole file).
        let mut data = vec![0u8; n];
        let signed: &mut [i8] =
            unsafe { std::slice::from_raw_parts_mut(data.as_mut_ptr() as *mut i8, n) };
        env.get_byte_array_region(&buf, 0, signed)
            .map_err(|e| CoreError::InvalidArgument(format!("get_byte_array_region: {e}")))?;
        writer.write_chunk(&data, n)
    })();
    if let Err(e) = result {
        throw(&mut env, &format!("writeChunk failed: {e}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_writeCloseEntry(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result: CoreResult<()> = (|| {
        let writer = unsafe { writer_ref(handle) }.ok_or(CoreError::InvalidHandle)?;
        writer.close_entry()
    })();
    if let Err(e) = result {
        throw(&mut env, &format!("writeCloseEntry failed: {e}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_writeClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        throw(&mut env, "writeClose failed: invalid or null handle");
        return;
    }
    // Reclaim ownership and finalize.
    let boxed = unsafe { Box::from_raw(handle as *mut ArchiveWriterHandle) };
    if let Err(e) = boxed.close() {
        throw(&mut env, &format!("writeClose failed: {e}"));
    }
}

// ---------------------------------------------------------------------------
// reader
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_openReader(
    mut env: JNIEnv,
    _class: JClass,
    in_path: JString,
    codec: jint,
) -> jlong {
    let result: CoreResult<jlong> = (|| {
        let path = jstring_to_string(&mut env, &in_path)?;
        let codec = Codec::from_jint(codec).ok_or(CoreError::InvalidCodec(codec))?;
        let handle = ArchiveReaderHandle::open(&path, codec)?;
        Ok(Box::into_raw(Box::new(handle)) as jlong)
    })();
    match result {
        Ok(h) => h,
        Err(e) => {
            throw(&mut env, &format!("openReader failed: {e}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_readNextEntry<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let null = std::ptr::null_mut();
    let reader = match unsafe { reader_ref(handle) } {
        Some(r) => r,
        None => {
            throw(&mut env, "readNextEntry failed: invalid or null handle");
            return null;
        }
    };
    match reader.next_entry() {
        Ok(None) => null, // end of archive => Java null
        Ok(Some(header)) => match env.new_string(header.to_json()) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                throw(&mut env, &format!("readNextEntry new_string: {e}"));
                null
            }
        },
        Err(e) => {
            throw(&mut env, &format!("readNextEntry failed: {e}"));
            null
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_readChunk(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buf: JByteArray,
) -> jint {
    let result: CoreResult<jint> = (|| {
        let reader = unsafe { reader_ref(handle) }.ok_or(CoreError::InvalidHandle)?;
        let cap = env
            .get_array_length(&buf)
            .map_err(|e| CoreError::InvalidArgument(format!("array length: {e}")))?
            as usize;
        if cap == 0 {
            return Ok(0);
        }
        let mut data = vec![0u8; cap];
        let n = reader.read_chunk(&mut data)?;
        if n > 0 {
            let signed: &[i8] =
                unsafe { std::slice::from_raw_parts(data.as_ptr() as *const i8, n as usize) };
            env.set_byte_array_region(&buf, 0, signed)
                .map_err(|e| CoreError::InvalidArgument(format!("set_byte_array_region: {e}")))?;
        }
        Ok(n)
    })();
    match result {
        Ok(n) => n,
        Err(e) => {
            throw(&mut env, &format!("readChunk failed: {e}"));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_zorg_aetherpak_compress_NativeBridge_readClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        throw(&mut env, "readClose failed: invalid or null handle");
        return;
    }
    let boxed = unsafe { Box::from_raw(handle as *mut ArchiveReaderHandle) };
    if let Err(e) = boxed.close() {
        throw(&mut env, &format!("readClose failed: {e}"));
    }
}
