use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::Path;

const MAGIC_VIDEO: &[u8; 4] = b"SVCV";
const MAGIC_IMAGE: &[u8; 4] = b"SICV";
const BUF_CAP: usize = 262_144;

#[derive(Serialize, Deserialize, Default)]
#[serde(default)]
struct Header {
    format: String,
    compression: u8,
    original_ext: String,
    original_size: u64,
}

fn read_header_from_reader<R: Read>(reader: &mut R) -> Result<Header, i32> {
    let mut magic = [0u8; 4];
    reader.read_exact(&mut magic).map_err(|_| -4)?;
    if &magic != MAGIC_VIDEO && &magic != MAGIC_IMAGE {
        return Err(-3);
    }
    let mut len_buf = [0u8; 4];
    reader.read_exact(&mut len_buf).map_err(|_| -4)?;
    let header_len = u32::from_le_bytes(len_buf) as usize;
    if header_len == 0 || header_len > 1024 * 1024 {
        return Err(-3);
    }
    let mut header_buf = vec![0u8; header_len];
    reader.read_exact(&mut header_buf).map_err(|_| -4)?;
    serde_json::from_slice(&header_buf).map_err(|_| -3)
}

fn read_header(input_path: &str) -> Result<Header, i32> {
    let file = File::open(input_path).map_err(|_| -3)?;
    let mut reader = BufReader::with_capacity(BUF_CAP, file);
    read_header_from_reader(&mut reader)
}

fn decode_to_file(input_path: &str, output_path: &str) -> i32 {
    let file = match File::open(input_path) {
        Ok(f) => f,
        Err(_) => return -4,
    };
    let mut reader = BufReader::with_capacity(BUF_CAP, file);
    let header = match read_header_from_reader(&mut reader) {
        Ok(h) => h,
        Err(c) => return c,
    };
    let out_file = match File::create(output_path) {
        Ok(f) => f,
        Err(_) => return -7,
    };
    let mut writer = BufWriter::with_capacity(BUF_CAP, out_file);
    if header.compression == 0 {
        if io::copy(&mut reader, &mut writer).is_err() {
            return -5;
        }
    } else if zstd::stream::copy_decode(&mut reader, &mut writer).is_err() {
        return -6;
    }
    if writer.flush().is_err() {
        return -7;
    }
    0
}

fn decode_to_vec(input_path: &str) -> Result<Vec<u8>, i32> {
    let file = File::open(input_path).map_err(|_| -4)?;
    let mut reader = BufReader::with_capacity(BUF_CAP, file);
    let header = read_header_from_reader(&mut reader)?;
    let cap = (header.original_size as usize).min(64 * 1024 * 1024);
    let mut out = Vec::with_capacity(cap);
    if header.compression == 0 {
        reader.read_to_end(&mut out).map_err(|_| -5)?;
    } else {
        zstd::stream::copy_decode(&mut reader, &mut out).map_err(|_| -6)?;
    }
    Ok(out)
}

fn encode_from_file(
    input_path: &str,
    output_path: &str,
    compression: u8,
    is_video: bool,
) -> i32 {
    let metadata = match fs::metadata(input_path) {
        Ok(m) => m,
        Err(_) => return -10,
    };
    if metadata.len() == 0 {
        return -10;
    }
    let input_file = match File::open(input_path) {
        Ok(f) => f,
        Err(_) => return -10,
    };
    let mut reader = BufReader::with_capacity(BUF_CAP, input_file);
    let magic = if is_video { MAGIC_VIDEO } else { MAGIC_IMAGE };
    let ext = Path::new(input_path)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("bin")
        .to_string();
    let header = Header {
        format: if is_video { "video".into() } else { "image".into() },
        compression,
        original_ext: ext,
        original_size: metadata.len(),
    };
    let header_json = match serde_json::to_vec(&header) {
        Ok(j) => j,
        Err(_) => return -11,
    };
    let out_file = match File::create(output_path) {
        Ok(f) => f,
        Err(_) => return -13,
    };
    let mut writer = BufWriter::with_capacity(BUF_CAP, out_file);
    if writer.write_all(magic).is_err() {
        return -14;
    }
    if writer.write_all(&(header_json.len() as u32).to_le_bytes()).is_err() {
        return -15;
    }
    if writer.write_all(&header_json).is_err() {
        return -15;
    }
    if compression == 0 {
        if io::copy(&mut reader, &mut writer).is_err() {
            return -16;
        }
    } else {
        let level = match compression {
            1 => 1,
            2 => 3,
            3 => 6,
            4 => 12,
            _ => 19,
        };
        if zstd::stream::copy_encode(&mut reader, &mut writer, level).is_err() {
            return -12;
        }
    }
    if writer.flush().is_err() {
        return -16;
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_readHeader(
    mut env: JNIEnv,
    _this: JObject,
    input: JString,
) -> jstring {
    let input_s: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match read_header(&input_s) {
        Ok(h) => {
            let json = serde_json::to_string(&h).unwrap_or_default();
            env.new_string(json).unwrap_or_default().into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_decodeToFile(
    mut env: JNIEnv,
    _this: JObject,
    input: JString,
    output: JString,
) -> jint {
    let input_s: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let output_s: String = match env.get_string(&output) {
        Ok(s) => s.into(),
        Err(_) => return -2,
    };
    decode_to_file(&input_s, &output_s)
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_decodeToBytes(
    mut env: JNIEnv,
    _this: JObject,
    input: JString,
) -> jbyteArray {
    let input_s: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match decode_to_vec(&input_s) {
        Ok(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_encodeFromFile(
    mut env: JNIEnv,
    _this: JObject,
    input: JString,
    output: JString,
    compression: jint,
    is_video: jboolean,
) -> jint {
    let input_s: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let output_s: String = match env.get_string(&output) {
        Ok(s) => s.into(),
        Err(_) => return -2,
    };
    let level: u8 = if !(0..=5).contains(&compression) {
        1
    } else {
        compression as u8
    };
    encode_from_file(&input_s, &output_s, level, is_video != 0)
}
