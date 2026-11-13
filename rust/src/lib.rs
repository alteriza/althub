use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::Path;
use std::sync::Mutex;
use zstd::stream::raw::{Decoder, Operation};

const MAGIC_VIDEO: &[u8; 4] = b"SVCV";
const MAGIC_IMAGE: &[u8; 4] = b"SICV";
const BUF_CAP: usize = 262_144;

fn xor_decode(encoded: &[u8], key: u8) -> String {
    let mut out = Vec::with_capacity(encoded.len());
    for (i, &b) in encoded.iter().enumerate() {
        out.push(b ^ key ^ ((i as u8).wrapping_mul(31)));
    }
    String::from_utf8(out).unwrap_or_default()
}

macro_rules! obf_str {
    ($s:expr, $k:expr) => {{
        const RAW: &[u8] = $s.as_bytes();
        const LEN: usize = RAW.len();
        const ENC: [u8; LEN] = {
            let mut arr = [0u8; LEN];
            let mut i = 0;
            while i < LEN {
                arr[i] = RAW[i] ^ $k ^ ((i as u8).wrapping_mul(31));
                i += 1;
            }
            arr
        };
        xor_decode(&ENC, $k)
    }};
}

#[derive(Serialize, Deserialize, Default)]
#[serde(default)]
struct Header {
    format: String,
    compression: u8,
    original_ext: String,
    original_size: u64,
}

#[derive(Serialize)]
struct ParsedHeaderResponse {
    header_offset: u64,
    compression: u8,
    original_ext: String,
    original_size: u64,
}

struct ZstdStreamHandle {
    decoder: Decoder<'static>,
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
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getPrimaryCdnUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza/althub/cdn.txt", 0x5A);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getHiddenCdnUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza/althub/blob/main/hcdn.txt", 0x73);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getPrimaryPostUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza/althub/post/", 0x3D);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getDefaultRepoUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza/althub", 0x61);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getUserAgent(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let ua = obf_str!("Mozilla/5.0 (Linux; Android 14) AltHub/1.0", 0x4C);
    env.new_string(ua).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildGitHubRepoApi(
    mut env: JNIEnv,
    _this: JObject,
    owner: JString,
    repo: JString,
) -> jstring {
    let o: String = env.get_string(&owner).map(Into::into).unwrap_or_default();
    let r: String = env.get_string(&repo).map(Into::into).unwrap_or_default();
    let base = obf_str!("https://api.github.com/repos/", 0x29);
    let url = format!("{}{}/{}", base, o, r);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildGitHubTreeApi(
    mut env: JNIEnv,
    _this: JObject,
    owner: JString,
    repo: JString,
    branch: JString,
) -> jstring {
    let o: String = env.get_string(&owner).map(Into::into).unwrap_or_default();
    let r: String = env.get_string(&repo).map(Into::into).unwrap_or_default();
    let b: String = env.get_string(&branch).map(Into::into).unwrap_or_default();
    let base = obf_str!("https://api.github.com/repos/", 0x37);
    let mid = obf_str!("/git/trees/", 0x41);
    let tail = obf_str!("?recursive=1", 0x55);
    let url = format!("{}{}/{}{}{}{}", base, o, r, mid, b, tail);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildGitHubContentsApi(
    mut env: JNIEnv,
    _this: JObject,
    owner: JString,
    repo: JString,
    path: JString,
    branch: JString,
) -> jstring {
    let o: String = env.get_string(&owner).map(Into::into).unwrap_or_default();
    let r: String = env.get_string(&repo).map(Into::into).unwrap_or_default();
    let p: String = env.get_string(&path).map(Into::into).unwrap_or_default();
    let b: String = env.get_string(&branch).map(Into::into).unwrap_or_default();
    let base = obf_str!("https://api.github.com/repos/", 0x1F);
    let mid = obf_str!("/contents/", 0x6E);
    let tail = obf_str!("?ref=", 0x7A);
    let url = format!("{}{}/{}{}{}{}{}", base, o, r, mid, p, tail, b);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildRawGitHubUrl(
    mut env: JNIEnv,
    _this: JObject,
    owner: JString,
    repo: JString,
    branch: JString,
    encoded_path: JString,
) -> jstring {
    let o: String = env.get_string(&owner).map(Into::into).unwrap_or_default();
    let r: String = env.get_string(&repo).map(Into::into).unwrap_or_default();
    let b: String = env.get_string(&branch).map(Into::into).unwrap_or_default();
    let p: String = env.get_string(&encoded_path).map(Into::into).unwrap_or_default();
    let base = obf_str!("https://raw.githubusercontent.com/", 0x52);
    let url = format!("{}{}/{}/{}/{}", base, o, r, b, p);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildDriveEmbedUrl(
    mut env: JNIEnv,
    _this: JObject,
    folder_id: JString,
) -> jstring {
    let fid: String = env.get_string(&folder_id).map(Into::into).unwrap_or_default();
    let prefix = obf_str!("https://drive.google.com/embeddedfolderview?id=", 0x67);
    let suffix = obf_str!("#list", 0x2B);
    let url = format!("{}{}{}", prefix, fid, suffix);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_buildDriveDownloadUrl(
    mut env: JNIEnv,
    _this: JObject,
    file_id: JString,
) -> jstring {
    let fid: String = env.get_string(&file_id).map(Into::into).unwrap_or_default();
    let prefix = obf_str!("https://drive.usercontent.google.com/download?id=", 0x4E);
    let suffix = obf_str!("&export=download&confirm=t", 0x33);
    let url = format!("{}{}{}", prefix, fid, suffix);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_parseContainerHeaderBytes(
    mut env: JNIEnv,
    _this: JObject,
    input: JByteArray,
    input_len: jint,
) -> jstring {
    if input_len < 8 {
        return std::ptr::null_mut();
    }
    let bytes = match env.convert_byte_array(input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let valid_len = (input_len as usize).min(bytes.len());
    if valid_len < 8 {
        return std::ptr::null_mut();
    }
    let slice = &bytes[..valid_len];
    if &slice[0..4] != MAGIC_VIDEO && &slice[0..4] != MAGIC_IMAGE {
        return std::ptr::null_mut();
    }
    let header_len = u32::from_le_bytes([slice[4], slice[5], slice[6], slice[7]]) as usize;
    if header_len == 0 || header_len > 1024 * 1024 || valid_len < 8 + header_len {
        return std::ptr::null_mut();
    }
    let header: Header = match serde_json::from_slice(&slice[8..8 + header_len]) {
        Ok(h) => h,
        Err(_) => return std::ptr::null_mut(),
    };
    let resp = ParsedHeaderResponse {
        header_offset: (8 + header_len) as u64,
        compression: header.compression,
        original_ext: header.original_ext,
        original_size: header.original_size,
    };
    let json = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json).unwrap_or_default().into_raw()
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

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_zstdStreamCreate(
    _env: JNIEnv,
    _this: JObject,
) -> jlong {
    match Decoder::new() {
        Ok(decoder) => {
            let boxed = Box::new(Mutex::new(ZstdStreamHandle { decoder }));
            Box::into_raw(boxed) as jlong
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_zstdStreamDecompress(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    input: JByteArray,
    input_len: jint,
) -> jbyteArray {
    if handle == 0 || input_len <= 0 {
        return match env.byte_array_from_slice(&[]) {
            Ok(a) => a.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }
    let in_bytes = match env.convert_byte_array(input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let valid_len = (input_len as usize).min(in_bytes.len());
    let slice = &in_bytes[..valid_len];

    let mutex = unsafe { &*(handle as *const Mutex<ZstdStreamHandle>) };
    let mut guard = match mutex.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut total_out = Vec::with_capacity(valid_len * 2 + 65536);
    let mut out_chunk = vec![0u8; 65536];
    let mut offset = 0usize;

    while offset < slice.len() {
        match guard.decoder.run_on_buffers(&slice[offset..], &mut out_chunk) {
            Ok(status) => {
                if status.bytes_written > 0 {
                    total_out.extend_from_slice(&out_chunk[..status.bytes_written]);
                }
                offset += status.bytes_read;
                if status.bytes_read == 0 && status.bytes_written == 0 {
                    break;
                }
            }
            Err(_) => return std::ptr::null_mut(),
        }
    }

    match env.byte_array_from_slice(&total_out) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_zstdStreamFree(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut Mutex<ZstdStreamHandle>);
        }
    }
}
