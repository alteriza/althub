use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};
use zstd::bulk;
use zstd::stream::raw::{Decoder, Operation};

const MAGIC_VIDEO: &[u8; 4] = b"SVCV";
const MAGIC_IMAGE: &[u8; 4] = b"SICV";
const BUF_CAP: usize = 262_144;
const VIDEO_CHUNK_SIZE: usize = 262_144;
const SEEK_FRAME_MAGIC: u32 = 0x184D2A5E;
const SEEK_TABLE_MAGIC: u32 = 0x8F92EAB1;
const CORN_REQUIRED_TAPS: jint = 10;

struct CaptchaSession {
    answer: String,
    expires_at_sec: u64,
}

static CAPTCHA_STORE: OnceLock<Mutex<HashMap<String, CaptchaSession>>> = OnceLock::new();
static RNG_SEED: OnceLock<Mutex<u64>> = OnceLock::new();

fn captcha_map() -> &'static Mutex<HashMap<String, CaptchaSession>> {
    CAPTCHA_STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_rand_u64() -> u64 {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E3779B97F4A7C15);
    let state_mutex = RNG_SEED.get_or_init(|| Mutex::new(now ^ 0xA5A5A5A55A5A5A5A));
    let mut s = state_mutex.lock().unwrap_or_else(|e| e.into_inner());
    *s ^= now.rotate_left(17);
    *s ^= *s << 13;
    *s ^= *s >> 7;
    *s ^= *s << 17;
    *s
}

fn now_epoch_sec() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

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
    chunk_size: u32,
}

#[derive(Serialize)]
struct ParsedHeaderResponse {
    header_offset: u64,
    compression: u8,
    original_ext: String,
    original_size: u64,
    chunk_size: u32,
}

#[derive(Serialize)]
struct SeekChunkEntry {
    index: usize,
    comp_offset: u64,
    comp_size: u32,
    decomp_offset: u64,
    decomp_size: u32,
}

#[derive(Serialize)]
struct SeekTableResponse {
    chunk_size: u32,
    total_decompressed_size: u64,
    chunks: Vec<SeekChunkEntry>,
}

#[derive(Serialize)]
struct HostResolution {
    host_type: String,
    resolved_url: String,
    file_id: String,
}

#[derive(Serialize)]
struct CaptchaChallengeResponse {
    challenge_id: String,
    code: String,
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
    let use_chunked = is_video && compression > 0;
    let header = Header {
        format: if is_video { "video".into() } else { "image".into() },
        compression,
        original_ext: ext,
        original_size: metadata.len(),
        chunk_size: if use_chunked { VIDEO_CHUNK_SIZE as u32 } else { 0 },
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
        if use_chunked {
            let mut seek_entries: Vec<(u32, u32)> = Vec::new();
            let mut chunk_buf = vec![0u8; VIDEO_CHUNK_SIZE];
            loop {
                let mut filled = 0usize;
                while filled < VIDEO_CHUNK_SIZE {
                    match reader.read(&mut chunk_buf[filled..]) {
                        Ok(0) => break,
                        Ok(n) => filled += n,
                        Err(_) => return -16,
                    }
                }
                if filled == 0 {
                    break;
                }
                let compressed = match bulk::compress(&chunk_buf[..filled], level) {
                    Ok(c) => c,
                    Err(_) => return -12,
                };
                if writer.write_all(&compressed).is_err() {
                    return -16;
                }
                seek_entries.push((compressed.len() as u32, filled as u32));
            }
            let num_chunks = seek_entries.len() as u32;
            let frame_size = num_chunks * 8 + 8;
            if writer.write_all(&SEEK_FRAME_MAGIC.to_le_bytes()).is_err() {
                return -16;
            }
            if writer.write_all(&frame_size.to_le_bytes()).is_err() {
                return -16;
            }
            for (c_sz, d_sz) in &seek_entries {
                if writer.write_all(&c_sz.to_le_bytes()).is_err() {
                    return -16;
                }
                if writer.write_all(&d_sz.to_le_bytes()).is_err() {
                    return -16;
                }
            }
            if writer.write_all(&num_chunks.to_le_bytes()).is_err() {
                return -16;
            }
            if writer.write_all(&SEEK_TABLE_MAGIC.to_le_bytes()).is_err() {
                return -16;
            }
        } else if zstd::stream::copy_encode(&mut reader, &mut writer, level).is_err() {
            return -12;
        }
    }
    if writer.flush().is_err() {
        return -16;
    }
    0
}

fn extract_segment_after<'a>(url: &'a str, marker: &str) -> Option<&'a str> {
    let idx = url.find(marker)?;
    let rest = &url[idx + marker.len()..];
    let end = rest.find(&['/', '?', '#', '&'][..]).unwrap_or(rest.len());
    let seg = &rest[..end];
    if seg.is_empty() {
        None
    } else {
        Some(seg)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getPrimaryCdnUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza/althub/blob/main/cdn.txt", 0x5A);
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
    let url = obf_str!("https://github.com/alteriza/althub/tree/main/post", 0x3D);
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
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getDeveloperProfileUrl(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://github.com/alteriza", 0x48);
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
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getGofileTokenEndpoint(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let url = obf_str!("https://api.gofile.io/accounts", 0x2F);
    env.new_string(url).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getCornRequiredTaps(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    CORN_REQUIRED_TAPS
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getCornDisclaimerTitle(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let title = obf_str!("CORN // ALERT", 0x69);
    env.new_string(title).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_getCornDisclaimerMessage(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let msg = obf_str!(
        "WARNING! ADULT ONLY.",
        0x51
    );
    env.new_string(msg).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_generateCornCaptcha(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
    let mut code = String::with_capacity(6);
    for _ in 0..6 {
        let idx = (next_rand_u64() as usize) % CHARSET.len();
        code.push(CHARSET[idx] as char);
    }
    let challenge_id = format!("{:016x}", next_rand_u64());
    let now = now_epoch_sec();

    let mut store = captcha_map().lock().unwrap_or_else(|e| e.into_inner());
    store.retain(|_, v| v.expires_at_sec > now);
    store.insert(
        challenge_id.clone(),
        CaptchaSession {
            answer: code.clone(),
            expires_at_sec: now + 300,
        },
    );

    let payload = CaptchaChallengeResponse { challenge_id, code };
    let json = serde_json::to_string(&payload).unwrap_or_default();
    env.new_string(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_verifyCornCaptcha(
    mut env: JNIEnv,
    _this: JObject,
    challenge_id: JString,
    user_input: JString,
) -> jboolean {
    let cid: String = env.get_string(&challenge_id).map(Into::into).unwrap_or_default();
    let ans: String = env.get_string(&user_input).map(Into::into).unwrap_or_default();
    let cleaned_ans = ans.trim().to_uppercase();
    if cid.is_empty() || cleaned_ans.is_empty() {
        return 0;
    }
    let now = now_epoch_sec();
    let mut store = captcha_map().lock().unwrap_or_else(|e| e.into_inner());
    let valid = match store.remove(&cid) {
        Some(session) => session.expires_at_sec >= now && session.answer == cleaned_ans,
        None => false,
    };
    if valid {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_resolveHostEndpoint(
    mut env: JNIEnv,
    _this: JObject,
    raw_url: JString,
) -> jstring {
    let input: String = env.get_string(&raw_url).map(Into::into).unwrap_or_default();
    let trimmed = input.trim().to_string();
    let lower = trimmed.to_lowercase();

    let pd_host = obf_str!("pixeldrain.com", 0x19);
    let gf_host = obf_str!("gofile.io", 0x22);
    let af_host = obf_str!("acefile.co", 0x31);
    let fd_host = obf_str!("filedom", 0x44);
    let gd_host = obf_str!("drive.google.com", 0x51);
    let docs_host = obf_str!("docs.google.com", 0x52);
    let gh_host = obf_str!("github.com", 0x63);
    let gh_raw = obf_str!("raw.githubusercontent.com", 0x64);

    let res = if lower.contains(&pd_host) {
        if let Some(id) = extract_segment_after(&trimmed, "/u/")
            .or_else(|| extract_segment_after(&trimmed, "/api/file/"))
        {
            let api_base = obf_str!("https://pixeldrain.com/api/file/", 0x1A);
            HostResolution {
                host_type: "pixeldrain".into(),
                resolved_url: format!("{}{}", api_base, id),
                file_id: id.into(),
            }
        } else if let Some(lid) = extract_segment_after(&trimmed, "/l/") {
            let api_list = obf_str!("https://pixeldrain.com/api/list/", 0x1B);
            HostResolution {
                host_type: "pixeldrain_list".into(),
                resolved_url: format!("{}{}", api_list, lid),
                file_id: lid.into(),
            }
        } else {
            HostResolution {
                host_type: "direct".into(),
                resolved_url: trimmed,
                file_id: String::new(),
            }
        }
    } else if lower.contains(&gf_host) {
        let id = extract_segment_after(&trimmed, "/d/").unwrap_or("").to_string();
        let api_base = obf_str!("https://api.gofile.io/contents/", 0x23);
        let wt = obf_str!("?wt=4fd6sg89d7s6", 0x24);
        HostResolution {
            host_type: "gofile".into(),
            resolved_url: if id.is_empty() {
                trimmed
            } else {
                format!("{}{}{}", api_base, id, wt)
            },
            file_id: id,
        }
    } else if lower.contains(&af_host) {
        let id = extract_segment_after(&trimmed, "/f/")
            .or_else(|| extract_segment_after(&trimmed, "/player/"))
            .unwrap_or("")
            .to_string();
        HostResolution {
            host_type: "acefile".into(),
            resolved_url: trimmed,
            file_id: id,
        }
    } else if lower.contains(&fd_host) {
        HostResolution {
            host_type: "filedom".into(),
            resolved_url: trimmed,
            file_id: String::new(),
        }
    } else if lower.contains(&gd_host) || lower.contains(&docs_host) {
        if lower.contains("/folders/") {
            HostResolution {
                host_type: "gdrive_folder".into(),
                resolved_url: trimmed,
                file_id: String::new(),
            }
        } else {
            HostResolution {
                host_type: "gdrive_file".into(),
                resolved_url: trimmed,
                file_id: String::new(),
            }
        }
    } else if lower.contains(&gh_host) || lower.contains(&gh_raw) {
        HostResolution {
            host_type: "github".into(),
            resolved_url: trimmed,
            file_id: String::new(),
        }
    } else {
        HostResolution {
            host_type: "direct".into(),
            resolved_url: trimmed,
            file_id: String::new(),
        }
    };

    let json = serde_json::to_string(&res).unwrap_or_default();
    env.new_string(json).unwrap_or_default().into_raw()
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
    env: JNIEnv,
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
        chunk_size: header.chunk_size,
    };
    let json = serde_json::to_string(&resp).unwrap_or_default();
    env.new_string(json).unwrap_or_default().into_raw()
}

fn try_parse_seek_footer(
    tail: &[u8],
    file_size: u64,
    header_offset: u64,
    footer_len: usize,
) -> Option<SeekTableResponse> {
    if tail.len() < 8 + footer_len {
        return None;
    }
    let len = tail.len();
    let magic = u32::from_le_bytes([tail[len - 4], tail[len - 3], tail[len - 2], tail[len - 1]]);
    if magic != SEEK_TABLE_MAGIC {
        return None;
    }
    let num_pos = len - footer_len;
    let num_chunks = u32::from_le_bytes([
        tail[num_pos],
        tail[num_pos + 1],
        tail[num_pos + 2],
        tail[num_pos + 3],
    ]) as usize;
    if num_chunks == 0 || num_chunks > 262_144 {
        return None;
    }
    let frame_size = num_chunks * 8 + footer_len;
    let total_frame_bytes = frame_size + 8;
    if len < total_frame_bytes {
        return None;
    }
    let start = len - total_frame_bytes;
    let frame_magic = u32::from_le_bytes([
        tail[start],
        tail[start + 1],
        tail[start + 2],
        tail[start + 3],
    ]);
    let declared_size = u32::from_le_bytes([
        tail[start + 4],
        tail[start + 5],
        tail[start + 6],
        tail[start + 7],
    ]) as usize;
    if frame_magic != SEEK_FRAME_MAGIC || declared_size != frame_size {
        return None;
    }

    let mut entries = Vec::with_capacity(num_chunks);
    let mut comp_cursor = header_offset;
    let mut decomp_cursor = 0u64;
    let mut inferred_chunk_size = 0u32;
    let entries_base = start + 8;

    for i in 0..num_chunks {
        let p = entries_base + i * 8;
        let c_sz = u32::from_le_bytes([tail[p], tail[p + 1], tail[p + 2], tail[p + 3]]);
        let d_sz = u32::from_le_bytes([tail[p + 4], tail[p + 5], tail[p + 6], tail[p + 7]]);
        if i == 0 {
            inferred_chunk_size = d_sz;
        }
        entries.push(SeekChunkEntry {
            index: i,
            comp_offset: comp_cursor,
            comp_size: c_sz,
            decomp_offset: decomp_cursor,
            decomp_size: d_sz,
        });
        comp_cursor += c_sz as u64;
        decomp_cursor += d_sz as u64;
    }

    if file_size > 0 && comp_cursor + (total_frame_bytes as u64) != file_size {
        return None;
    }

    Some(SeekTableResponse {
        chunk_size: if inferred_chunk_size > 0 {
            inferred_chunk_size
        } else {
            VIDEO_CHUNK_SIZE as u32
        },
        total_decompressed_size: decomp_cursor,
        chunks: entries,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_parseVcvSeekTable(
    env: JNIEnv,
    _this: JObject,
    tail_bytes: JByteArray,
    tail_len: jint,
    file_size: jlong,
    header_offset: jlong,
) -> jstring {
    if tail_len < 16 || header_offset < 8 {
        return std::ptr::null_mut();
    }
    let bytes = match env.convert_byte_array(tail_bytes) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let valid_len = (tail_len as usize).min(bytes.len());
    let slice = &bytes[..valid_len];
    let f_size = file_size.max(0) as u64;
    let h_off = header_offset.max(0) as u64;

    let parsed = try_parse_seek_footer(slice, f_size, h_off, 8)
        .or_else(|| try_parse_seek_footer(slice, f_size, h_off, 9));

    match parsed {
        Some(table) => {
            let json = serde_json::to_string(&table).unwrap_or_default();
            env.new_string(json).unwrap_or_default().into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_althub_alteriza_NativeCore_decompressZstdBlock(
    env: JNIEnv,
    _this: JObject,
    input: JByteArray,
    input_len: jint,
    expected_size: jint,
) -> jbyteArray {
    if input_len <= 0 {
        return std::ptr::null_mut();
    }
    let in_bytes = match env.convert_byte_array(input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let valid_len = (input_len as usize).min(in_bytes.len());
    let cap = if expected_size > 0 {
        (expected_size as usize).min(16 * 1024 * 1024)
    } else {
        VIDEO_CHUNK_SIZE * 4
    };
    match bulk::decompress(&in_bytes[..valid_len], cap) {
        Ok(out) => match env.byte_array_from_slice(&out) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
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
    env: JNIEnv,
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