//! JNI bridge to karukan's neural kana-kanji converter.
//!
//! Deliberately thin. Everything about how conversion is used — when to ask, what to do with the
//! answers, whether the feature is on at all — lives on the Kotlin side; this only moves strings
//! across the boundary and owns the model's lifetime.
//!
//! The model is opened from paths the caller supplies. karukan's own constructors fetch the
//! weights from HuggingFace, which this build has no use for: the app declares no INTERNET
//! permission, so the model is either bundled into the APK at build time or the feature stays off.

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobject};
use jni::JNIEnv;
use karukan_engine::{Backend, KanaKanjiConverter};

/// Opens a model. Returns a handle, or 0 if it could not be loaded.
///
/// Loading is slow — it maps a GGUF file and builds a tokenizer — so this is called once, off the
/// main thread.
#[no_mangle]
pub extern "system" fn Java_dev_oss_ime_karukan_KarukanNative_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    gguf_path: JString,
    tokenizer_path: JString,
) -> jlong {
    let gguf: String = match env.get_string(&gguf_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let tokenizer: String = match env.get_string(&tokenizer_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match KanaKanjiConverter::new(Backend::from_paths(&gguf, &tokenizer)) {
        Ok(converter) => Box::into_raw(Box::new(converter)) as jlong,
        Err(_) => 0,
    }
}

/// Converts `reading` (hiragana) into up to `count` candidates, best first.
///
/// `context` is the text already committed to the left, which the model conditions on.
///
/// Returns an empty array on any failure rather than throwing. A conversion that did not work is
/// not something the caller can act on, and mozc's candidates are still there either way.
#[no_mangle]
pub extern "system" fn Java_dev_oss_ime_karukan_KarukanNative_nativeConvert(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    reading: JString,
    context: JString,
    count: jint,
) -> jobject {
    if handle == 0 {
        return empty_array(&mut env);
    }
    // Safety: the handle is one this module produced and Kotlin has not closed. KanaKanjiConverter
    // is only borrowed here, and convert() takes &self.
    let converter = unsafe { &*(handle as *const KanaKanjiConverter) };

    let reading: String = match env.get_string(&reading) {
        Ok(s) => s.into(),
        Err(_) => return empty_array(&mut env),
    };
    let context: String = match env.get_string(&context) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let candidates = match converter.convert(&reading, &context, count.max(1) as usize) {
        Ok(c) => c,
        Err(_) => return empty_array(&mut env),
    };

    let array: JObjectArray = match env.new_object_array(
        candidates.len() as jint,
        "java/lang/String",
        JObject::null(),
    ) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, text) in candidates.iter().enumerate() {
        let Ok(value) = env.new_string(text) else {
            break;
        };
        if env.set_object_array_element(&array, i as jint, value).is_err() {
            break;
        }
    }
    array.into_raw()
}

/// Releases a handle from [`Java_dev_oss_ime_karukan_KarukanNative_nativeOpen`].
#[no_mangle]
pub extern "system" fn Java_dev_oss_ime_karukan_KarukanNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // Reconstituting the Box is what frees it, along with the mapped weights.
        unsafe { drop(Box::from_raw(handle as *mut KanaKanjiConverter)) };
    }
}

fn empty_array(env: &mut JNIEnv) -> jobject {
    match env.new_object_array(0, "java/lang/String", JObject::null()) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
