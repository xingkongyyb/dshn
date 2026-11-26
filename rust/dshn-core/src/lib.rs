mod extractor;
#[cfg(feature = "fast-chat")]
mod fast_chat;
mod frame;

use std::path::Path;

use jni::objects::{JByteArray, JByteBuffer, JClass, JLongArray, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE};
#[cfg(feature = "fast-chat")]
use jni::sys::JNI_TRUE;
use jni::JNIEnv;

#[cfg(feature = "fast-chat")]
use crate::fast_chat::ChatStreamEvent;

fn throw_io(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/io/IOException", msg);
}

#[no_mangle]
pub extern "system" fn Java_dev_dshn_app_runtime_DshnNativeCore_nativeEngineInfo<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> JString<'local> {
    let info = format!(
        "dshn-core {} (rust-{}; gzip-zlib-rs,zstd,sha2,fast-chat-tls,frame-jpeg)",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH
    );
    env.new_string(info).unwrap_or_default()
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_dshn_app_runtime_DshnNativeCore_nativeExtractTarFile<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    archive_path: JString<'local>,
    offset_bytes: jlong,
    length_bytes: jlong,
    dest_dir: JString<'local>,
    strip_components: jint,
    expected_sha256: JString<'local>,
    is_zstd: jboolean,
    callback: JObject<'local>,
) -> JString<'local> {
    let archive_str: String = match env.get_string(&archive_path) {
        Ok(s) => s.into(),
        Err(e) => {
            throw_io(&mut env, &format!("Invalid archivePath: {e}"));
            return JString::default();
        }
    };
    let dest_str: String = match env.get_string(&dest_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            throw_io(&mut env, &format!("Invalid destDir: {e}"));
            return JString::default();
        }
    };
    let expected_str: String = env
        .get_string(&expected_sha256)
        .map(Into::into)
        .unwrap_or_default();
    let expected_opt = if expected_str.trim().is_empty() {
        None
    } else {
        Some(expected_str.as_str())
    };

    let has_cb = !callback.is_null();
    let res = extractor::extract_tar_slice(
        Path::new(&archive_str),
        offset_bytes.max(0) as u64,
        length_bytes.max(0) as u64,
        Path::new(&dest_str),
        strip_components.max(0) as usize,
        expected_opt,
        is_zstd != JNI_FALSE,
        |bytes_read, entries| {
            if has_cb {
                let _ = env.call_method(
                    &callback,
                    "onProgress",
                    "(JI)V",
                    &[JValue::Long(bytes_read as jlong), JValue::Int(entries as jint)],
                );
            }
        },
    );

    match res {
        Ok(stats) => {
            let json = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
            env.new_string(json).unwrap_or_default()
        }
        Err(e) => {
            throw_io(&mut env, &format!("Rust archive extract failed: {e}"));
            JString::default()
        }
    }
}

#[cfg(feature = "fast-chat")]
#[no_mangle]
pub extern "system" fn Java_dev_dshn_app_runtime_DshnNativeCore_nativeRunFastChatStream<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    endpoint_url: JString<'local>,
    api_key: JString<'local>,
    request_body_json: JString<'local>,
    callback: JObject<'local>,
) -> jboolean {
    let url: String = match env.get_string(&endpoint_url) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let key: String = env.get_string(&api_key).map(Into::into).unwrap_or_default();
    let body: String = match env.get_string(&request_body_json) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    if callback.is_null() {
        return JNI_FALSE;
    }

    let ok = fast_chat::run_sse_stream(&url, &key, &body, |ev| match ev {
        ChatStreamEvent::TextDelta(chunk) => {
            if let Ok(s) = env.new_string(chunk) {
                let _ = env.call_method(&callback, "onTextDelta", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                let _ = env.delete_local_ref(s);
            }
        }
        ChatStreamEvent::ReasoningDelta(chunk) => {
            if let Ok(s) = env.new_string(chunk) {
                let _ = env.call_method(&callback, "onReasoningDelta", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                let _ = env.delete_local_ref(s);
            }
        }
        ChatStreamEvent::Usage { prompt, completion, total } => {
            let _ = env.call_method(
                &callback,
                "onUsage",
                "(JJJ)V",
                &[JValue::Long(prompt), JValue::Long(completion), JValue::Long(total)],
            );
        }
        ChatStreamEvent::Done(reason) => {
            if let Ok(s) = env.new_string(reason) {
                let _ = env.call_method(&callback, "onDone", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                let _ = env.delete_local_ref(s);
            }
        }
        ChatStreamEvent::Error(msg) => {
            if let Ok(s) = env.new_string(&msg) {
                let _ = env.call_method(&callback, "onError", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
                let _ = env.delete_local_ref(s);
            }
        }
    });

    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(feature = "fast-chat")]
#[no_mangle]
pub extern "system" fn Java_dev_dshn_app_runtime_DshnNativeCore_nativeCancelFastChat<'local>(
    _env: JNIEnv<'local>,
    _cls: JClass<'local>,
) {
    fast_chat::request_cancel();
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_dshn_app_runtime_DshnNativeCore_nativeEncodeVirtualFrame<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
    rgba_buffer: JByteBuffer<'local>,
    src_width: jint,
    src_height: jint,
    row_stride: jint,
    dst_width: jint,
    dst_height: jint,
    jpeg_quality: jint,
    previous_hash: jlong,
    out_hash: JLongArray<'local>,
) -> JByteArray<'local> {
    let ptr = match env.get_direct_buffer_address(&rgba_buffer) {
        Ok(p) if !p.is_null() => p,
        _ => return JByteArray::default(),
    };
    let cap = match env.get_direct_buffer_capacity(&rgba_buffer) {
        Ok(c) if c > 0 => c,
        _ => return JByteArray::default(),
    };

    // SAFETY: Android ImageReader direct ByteBuffer remains valid for the duration of the JNI call.
    let slice = unsafe { std::slice::from_raw_parts(ptr, cap) };
    let res = frame::encode_rgba_to_jpeg(
        slice,
        src_width.max(0) as usize,
        src_height.max(0) as usize,
        row_stride.max(0) as usize,
        dst_width.max(0) as usize,
        dst_height.max(0) as usize,
        jpeg_quality.clamp(30, 98) as u8,
        previous_hash as u64,
    );

    match res {
        Ok(Some((jpeg_bytes, hash))) => {
            if !out_hash.is_null() {
                let _ = env.set_long_array_region(&out_hash, 0, &[hash as jlong]);
            }
            env.byte_array_from_slice(&jpeg_bytes).unwrap_or_default()
        }
        Ok(None) => JByteArray::default(),
        Err(_) => JByteArray::default(),
    }
}
