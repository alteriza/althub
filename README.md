# AltHub

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026)-0A0A0A?style=flat-square&logo=android&logoColor=FF6B00)](https://github.com/alteriza/althub)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-0A0A0A?style=flat-square)](https://github.com/alteriza/althub)
[![Core](https://img.shields.io/badge/Native%20Core-Rust%20%2B%20Zstd-0A0A0A?style=flat-square&logo=rust&logoColor=FF6B00)](https://github.com/alteriza/althub)
[![License](https://img.shields.io/badge/License-Hybrid%20EULA-FF6B00?style=flat-square)](LICENSE)

**[English](README.md)** | **[Bahasa Indonesia](README.id.md)**

AltHub is a high-performance Android media streaming client and archiving hub powered by a native Rust JNI core (`libalthub_core.so`) and a modern Jetpack Compose interface. It introduces the custom **`.vcv`** (Streamable Video Container) and **`.icv`** (Streamable Image Container) formats backed by chunked Zstandard (`zstd`) compression and random-access seek tables.

---

## Key Features

- **Native Rust Core (`libalthub_core.so`)**: High-speed binary container encoding/decoding, streaming Zstd decompression, and seek-table indexing compiled natively for `arm64-v8a` and `armeabi-v7a`.
- **Streamable `.vcv` & `.icv` Containers**:
  - **`.vcv` (`SVCV`)**: Splits video streams into 256 KiB independent Zstd-compressed blocks with an appended footer seek table, enabling instant HTTP `206 Partial Content` range seeking without downloading the entire file.
  - **`.icv` (`SICV`)**: Compact Zstd-compressed image container with progressive stream decoding.
- **Loopback HTTP Range Proxy (`LocalStreamServer`)**: Built-in `127.0.0.1` streaming server bridging remote hosts and `.vcv`/`.icv` containers directly into AndroidX Media3 (ExoPlayer).
- **Multi-Source Auto-Discovery**: Automatically indexes media streams, thumbnails, and BBCode announcements from GitHub repositories, Google Drive folders/files, Pixeldrain, Gofile, Acefile, Filedom, and direct HTTP endpoints.
- **Custom Media3 Video Player**: Supports hardware/software decoder switching, swipe gestures (brightness & volume), double-tap `±10s` seeking, playback speeds (`0.5x`–`2.0x`), aspect ratio modes (`FIT`, `FILL`, `16:9`), screen lock, and auto-orientation.
- **Offline Storage & On-Device Conversion**: Download remote media either in its original format or transcoded into `.vcv`/`.icv` containers directly on-device.

---

## `.vcv` & `.icv` Container Specification

The `.vcv` and `.icv` container formats are open for independent implementation under the terms of the [LICENSE](LICENSE) EULA.

### Binary Layout

```text
+-------------------+--------------------+------------------------+-----------------------------+---------------------------+
| Magic Bytes (4 B) | Header Length (4B) | JSON Header (N Bytes)  | Compressed Payload          | Seek Table Footer (Opt.)  |
| "SVCV" or "SICV"  | u32 Little-Endian  | UTF-8 Metadata JSON    | Raw or Zstd Chunks          | Present when chunked > 0  |
+-------------------+--------------------+------------------------+-----------------------------+---------------------------+
```

### 1. File Header
- **Magic Bytes (`4 bytes`)**:
  - `0x53 0x56 0x43 0x56` (`"SVCV"`) for video containers.
  - `0x53 0x49 0x43 0x56` (`"SICV"`) for image containers.
- **Header Length (`4 bytes`)**: Unsigned 32-bit little-endian integer (`u32`) specifying the byte length of the JSON metadata block (`1 <= len <= 1,048,576`).
- **JSON Metadata (`N bytes`)**:
  ```json
  {
    "format": "video",
    "compression": 2,
    "original_ext": "mp4",
    "original_size": 10485760,
    "chunk_size": 262144
  }
  ```
  - `compression`: `0` = uncompressed passthrough; `1..5` = Zstd compression presets mapped to Zstd levels `1, 3, 6, 12, 19`.
  - `chunk_size`: `262144` (`256 KiB`) for seekable `.vcv` streams; `0` for single-stream `.icv` or unchunked payloads.

### 2. Chunked Seek Table Footer (`.vcv` with `compression > 0`)
Appended at the end of the file to allow `O(1)` byte-offset translation from decompressed timestamps/offsets to compressed byte ranges:

| Field | Size | Type | Description |
| :--- | :--- | :--- | :--- |
| `SEEK_FRAME_MAGIC` | `4 bytes` | `u32 LE` | Constant `0x184D2A5E` |
| `frame_size` | `4 bytes` | `u32 LE` | Equal to `(num_chunks * 8) + 8` |
| `entries[0..N]` | `8 * N bytes` | `(u32 LE, u32 LE)` | Pairs of `(compressed_size, decompressed_size)` per chunk |
| `num_chunks` | `4 bytes` | `u32 LE` | Total number of compressed chunks (`N`) |
| `SEEK_TABLE_MAGIC` | `4 bytes` | `u32 LE` | Constant `0x8F92EAB1` |

---

## Project Structure

```text
althub/
├── rust/
│   ├── Cargo.toml                 # Rust crate & NDK release profile configuration
│   └── src/lib.rs                 # JNI exports, .vcv/.icv encoder/decoder & seek parser
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/althub/alteriza/
│       ├── NativeCore.kt          # JNI declarations for libalthub_core.so
│       ├── Models.kt              # Data models & enums
│       ├── LocalStreamServer.kt   # Loopback HTTP Range & Zstd streaming server
│       ├── AutoDiscoveryRepository.kt # Multi-host crawler & thumbnail resolver
│       ├── AppStorageManager.kt   # On-device downloader, converter & history manager
│       ├── BBCodeRenderer.kt      # Native Compose BBCode parser & renderer
│       ├── AltHubApp.kt           # Main Compose UI, media feed & custom video player
│       ├── MainActivity.kt        # Edge-to-edge immersive activity host
│       └── ui/theme/Theme.kt      # Industrial dark palette & Material 3 theme
└── LICENSE                        # Dual-Scope End User License Agreement (EULA)
```

---

## License & Usage Terms

This project is governed by a strict **Dual-Scope End User License Agreement** ([LICENSE](LICENSE)):

1. **`.vcv` & `.icv` Format Technology & Logic (OPEN)**: You are freely permitted to use, study, implement, modify, and distribute independent encoders, decoders, players, and tools utilizing the `.vcv` and `.icv` container specifications and compression/seek logic, **provided you include the full English [LICENSE](LICENSE) text** in all copies and distributions.
2. **AltHub Android Application Source Code (STRICTLY RESTRICTED)**: The AltHub APK source code, UI, architecture, scripts, assets, and compiled binaries are **strictly proprietary** and **may not be copied, modified, forked, decompiled, reverse-engineered, redistributed, or touched in any form whatsoever**.