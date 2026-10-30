//! `tv.stream.torrent.TorrentEngine`'s native methods, over the handle registry in [`crate::api`]. Errors become
//! `TorrentException(kind, message)` with [`ErrorKind`]'s numbers; a panic becomes ENGINE_FAILED instead of taking
//! the app down.

use crate::api::{create_engine, destroy, lease};
use crate::engine::{files_json, Config, EngineError, ErrorKind, Result};
use crate::swarm::Source;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString, JThrowable, JValue};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring, JNI_TRUE};
use jni::JNIEnv;
use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;

fn throw(env: &mut JNIEnv, kind: ErrorKind, message: &str) {
    if env.exception_check().unwrap_or(true) {
        return;
    }
    let Ok(text) = env.new_string(message) else { return };
    if let Ok(error) = env.new_object(
        "tv/stream/torrent/TorrentException",
        "(ILjava/lang/String;)V",
        &[JValue::Int(kind as jint), JValue::Object(&text)],
    ) {
        let _ = env.throw(JThrowable::from(error));
    }
}

/// Runs `f`, turning engine errors and panics into Java exceptions.
fn guarded<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(e)) => {
            throw(env, e.kind, &e.message);
            fallback
        }
        Err(_) => {
            throw(env, ErrorKind::EngineFailed, "The streaming engine failed");
            fallback
        }
    }
}

fn jni_error(e: jni::errors::Error) -> EngineError {
    EngineError::new(ErrorKind::EngineFailed, format!("JNI: {e}"))
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String> {
    if s.is_null() {
        return Ok(String::new());
    }
    Ok(env.get_string(s).map_err(jni_error)?.into())
}

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> Result<Vec<String>> {
    if array.is_null() {
        return Ok(Vec::new());
    }
    let n = env.get_array_length(array).map_err(jni_error)?;
    let mut out = Vec::with_capacity(n as usize);
    for i in 0..n {
        let item = JString::from(env.get_object_array_element(array, i).map_err(jni_error)?);
        out.push(string(env, &item)?);
        env.delete_local_ref(item).map_err(jni_error)?;
    }
    Ok(out)
}

fn java_string(env: &mut JNIEnv, s: &str) -> Result<jstring> {
    Ok(env.new_string(s).map_err(jni_error)?.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    cache_dir: JString,
    cache_bytes: jlong,
    reserve_bytes: jlong,
    listen: JString,
    dht: jboolean,
    lsd: jboolean,
    upnp: jboolean,
    natpmp: jboolean,
    pex: jboolean,
    multiple_per_ip: jboolean,
    public_network_only: jboolean,
    ca_file: JString,
    read_ahead_seconds: jint,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let defaults = Config::default();
        let config = Config {
            cache_dir: string(env, &cache_dir)?,
            cache_bytes,
            reserve_bytes,
            listen_interfaces: string(env, &listen)?,
            dht: dht == JNI_TRUE,
            lsd: lsd == JNI_TRUE,
            upnp: upnp == JNI_TRUE,
            natpmp: natpmp == JNI_TRUE,
            pex: pex == JNI_TRUE,
            multiple_connections_per_ip: multiple_per_ip == JNI_TRUE,
            public_network_only: public_network_only == JNI_TRUE,
            ca_file: string(env, &ca_file)?,
            read_ahead_seconds: if read_ahead_seconds > 0 { read_ahead_seconds } else { defaults.read_ahead_seconds },
            ..defaults
        };
        Ok(create_engine(config)? as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeDestroy(_env: JNIEnv, _: JClass, id: jlong) {
    destroy(id as u64);
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeAdd(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    metainfo: JByteArray,
    magnet: JString,
    info_hash: JString,
    trackers: JObjectArray,
    peers: JObjectArray,
    fallback_trackers: JObjectArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let entry = lease(id as u64)?;
        let source = Source {
            metainfo: if metainfo.is_null() { Vec::new() } else { env.convert_byte_array(&metainfo).map_err(jni_error)? },
            magnet: string(env, &magnet)?,
            info_hash: string(env, &info_hash)?,
            trackers: strings(env, &trackers)?,
            fallback_trackers: strings(env, &fallback_trackers)?,
            peers: strings(env, &peers)?,
        };
        Ok(entry.engine.add(&source)? as jlong)
    })
}

/// The files as JSON, null if the wait timed out.
#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeAwaitMetadata(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    torrent: jlong,
    wait_ms: jlong,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let entry = lease(id as u64)?;
        match entry.engine.wait_metadata(torrent as u64, Duration::from_millis(wait_ms.max(0) as u64), None) {
            Ok(files) => java_string(env, &files_json(&files)),
            Err(e) if e.kind == ErrorKind::Timeout => Ok(std::ptr::null_mut()),
            Err(e) => Err(e),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeSelectFile(mut env: JNIEnv, _: JClass, id: jlong, torrent: jlong, file: jint) {
    guarded(&mut env, (), |_| lease(id as u64)?.engine.select_file(torrent as u64, file));
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeSetBitrate(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    torrent: jlong,
    bytes_per_second: jlong,
) {
    guarded(&mut env, (), |_| lease(id as u64)?.engine.set_bitrate(torrent as u64, bytes_per_second));
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeSetDemand(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    torrent: jlong,
    bytes: jlong,
    seconds: jint,
) {
    guarded(&mut env, (), |_| lease(id as u64)?.engine.set_demand(torrent as u64, bytes, seconds));
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeMetainfo(mut env: JNIEnv, _: JClass, id: jlong, torrent: jlong) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let bytes = lease(id as u64)?.engine.metainfo(torrent as u64)?;
        Ok(env.byte_array_from_slice(&bytes).map_err(jni_error)?.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeAddPeers(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    torrent: jlong,
    peers: JObjectArray,
) {
    guarded(&mut env, (), |env| {
        let entry = lease(id as u64)?;
        let peers = strings(env, &peers)?;
        entry.engine.add_peers(torrent as u64, &peers)
    });
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeStats(mut env: JNIEnv, _: JClass, id: jlong, torrent: jlong) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let stats = lease(id as u64)?.engine.stats(torrent as u64)?;
        java_string(env, &stats.to_json())
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeRemove(mut env: JNIEnv, _: JClass, id: jlong, torrent: jlong) {
    guarded(&mut env, (), |_| lease(id as u64)?.engine.remove(torrent as u64));
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeOpenReader(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    torrent: jlong,
    file: jint,
) -> jlong {
    guarded(&mut env, 0, |_| Ok(lease(id as u64)?.engine.open_reader(torrent as u64, file)? as jlong))
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeFileSize(mut env: JNIEnv, _: JClass, id: jlong, reader: jlong) -> jlong {
    guarded(&mut env, 0, |_| lease(id as u64)?.engine.file_size(reader as u64))
}

thread_local! {
    // One reused buffer per thread: the data is copied once more, into the Java array.
    static SCRATCH: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

/// Copies verified bytes into `buffer[offset, offset + length)`. Returns the count, 0 at the end of the file, or -1
/// when nothing arrived within `wait_ms`; the Kotlin side then checks for interruption and calls again.
#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeRead(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    reader: jlong,
    position: jlong,
    buffer: JByteArray,
    offset: jint,
    length: jint,
    wait_ms: jlong,
) -> jint {
    guarded(&mut env, 0, |env| {
        let entry = lease(id as u64)?;
        let capacity = env.get_array_length(&buffer).map_err(jni_error)?;
        if offset < 0 || length < 0 || offset > capacity - length {
            return Err(EngineError::new(ErrorKind::InvalidFile, "Read outside the buffer"));
        }
        let chunk = (length as usize).min(1 << 20);
        SCRATCH.with(|scratch| {
            let mut scratch = scratch.borrow_mut();
            if scratch.len() < chunk {
                scratch.resize(chunk, 0);
            }
            let n = entry.engine.read(reader as u64, position, &mut scratch[..chunk], Duration::from_millis(wait_ms.max(0) as u64))?;
            if n > 0 {
                // SAFETY: u8 and i8 (jbyte) have the same layout.
                let bytes = unsafe { std::slice::from_raw_parts(scratch.as_ptr() as *const i8, n as usize) };
                env.set_byte_array_region(&buffer, offset, bytes).map_err(jni_error)?;
            }
            Ok(n)
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeSeek(mut env: JNIEnv, _: JClass, id: jlong, reader: jlong, position: jlong) {
    guarded(&mut env, (), |_| lease(id as u64)?.engine.seek(reader as u64, position));
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeCancel(mut env: JNIEnv, _: JClass, id: jlong, reader: jlong) {
    guarded(&mut env, (), |_| {
        lease(id as u64)?.engine.cancel(reader as u64);
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeCloseReader(mut env: JNIEnv, _: JClass, id: jlong, reader: jlong) {
    guarded(&mut env, (), |_| {
        lease(id as u64)?.engine.close_reader(reader as u64);
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeDiagnostics(mut env: JNIEnv, _: JClass, id: jlong) -> jobjectArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let entry = lease(id as u64)?;
        let lines: Vec<String> = entry.log.lock().unwrap_or_else(|e| e.into_inner()).iter().cloned().collect();
        let array = env.new_object_array(lines.len() as jint, "java/lang/String", JObject::null()).map_err(jni_error)?;
        for (i, line) in lines.iter().enumerate() {
            let s = env.new_string(line).map_err(jni_error)?;
            env.set_object_array_element(&array, i as jint, &s).map_err(jni_error)?;
            env.delete_local_ref(s).map_err(jni_error)?;
        }
        Ok(array.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_tv_stream_torrent_TorrentEngine_nativeVersion(mut env: JNIEnv, _: JClass) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| java_string(env, &crate::libtorrent::version()))
}
