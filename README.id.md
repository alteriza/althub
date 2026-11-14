# AltHub

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026)-0A0A0A?style=flat-square&logo=android&logoColor=FF6B00)](https://github.com/alteriza/althub)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-0A0A0A?style=flat-square)](https://github.com/alteriza/althub)
[![Core](https://img.shields.io/badge/Native%20Core-Rust%20%2B%20Zstd-0A0A0A?style=flat-square&logo=rust&logoColor=FF6B00)](https://github.com/alteriza/althub)
[![License](https://img.shields.io/badge/License-Hybrid%20EULA-FF6B00?style=flat-square)](LICENSE)

**[English](README.md)** | **[Bahasa Indonesia](README.id.md)**

AltHub adalah aplikasi klien streaming media dan pusat pengarsipan untuk Android yang ditenagai oleh native core berbasis Rust (`libalthub_core.so`) serta antarmuka modern Jetpack Compose. Aplikasi ini memperkenalkan format kontainer kustom **`.vcv`** (Streamable Video Container) dan **`.icv`** (Streamable Image Container) dengan kompresi Zstandard (`zstd`) berbasis blok dan tabel indeks *random-access seeking*.

---

## Fitur Utama

- **Native Rust Core (`libalthub_core.so`)**: Proses *encoding*/*decoding* kontainer biner berkecepatan tinggi, dekompresi aliran Zstd, dan pembacaan *seek-table* yang dikompilasi secara native untuk arsitektur `arm64-v8a` dan `armeabi-v7a`.
- **Kontainer Streamable `.vcv` & `.icv`**:
  - **`.vcv` (`SVCV`)**: Memecah aliran video menjadi blok-blok terkompresi Zstd berukuran 256 KiB yang dilengkapi *footer seek table*, memungkinkan pencarian durasi (*seeking*) instan melalui HTTP `206 Partial Content` tanpa harus mengunduh seluruh file.
  - **`.icv` (`SICV`)**: Kontainer gambar terkompresi Zstd dengan dukungan *progressive stream decoding*.
- **Loopback HTTP Range Proxy (`LocalStreamServer`)**: Server streaming lokal `127.0.0.1` yang menjembatani sumber remote serta kontainer `.vcv`/`.icv` langsung ke mesin pemutar AndroidX Media3 (ExoPlayer).
- **Auto-Discovery Multi-Sumber**: Mengindeks media, thumbnail, dan pengumuman BBCode secara otomatis dari repositori GitHub, folder/file Google Drive, Pixeldrain, Gofile, Acefile, Filedom, serta tautan langsung.
- **Pemutar Video Media3 Kustom**: Mendukung pergantian dekoder Hardware/Software, kontrol gestur usap (kecerahan & volume), ketuk ganda `±10s`, kecepatan pemutaran (`0.5x`–`2.0x`), mode rasio aspek (`FIT`, `FILL`, `16:9`), kunci layar, dan rotasi otomatis.
- **Penyimpanan Offline & Konversi Lokal**: Unduh media dalam format aslinya atau konversi langsung menjadi kontainer `.vcv`/`.icv` di perangkat.

---

## Spesifikasi Kontainer `.vcv` & `.icv`

Teknologi dan logika format kontainer `.vcv` dan `.icv` terbuka untuk diimplementasikan secara bebas sesuai ketentuan dalam dokumen [LICENSE](LICENSE) (EULA).

### Struktur Biner

```text
+-------------------+--------------------+------------------------+-----------------------------+---------------------------+
| Magic Bytes (4 B) | Header Length (4B) | JSON Header (N Bytes)  | Compressed Payload          | Seek Table Footer (Ops.)  |
| "SVCV" atau "SICV"| u32 Little-Endian  | Metadata JSON UTF-8    | Raw atau Blok Zstd          | Ada jika chunked > 0      |
+-------------------+--------------------+------------------------+-----------------------------+---------------------------+
```

### 1. Header File
- **Magic Bytes (`4 byte`)**:
  - `0x53 0x56 0x43 0x56` (`"SVCV"`) untuk kontainer video.
  - `0x53 0x49 0x43 0x56` (`"SICV"`) untuk kontainer gambar.
- **Header Length (`4 byte`)**: Bilangan bulat tak bertanda 32-bit *little-endian* (`u32`) yang menentukan panjang byte blok metadata JSON (`1 <= len <= 1.048.576`).
- **Metadata JSON (`N byte`)**:
  ```json
  {
    "format": "video",
    "compression": 2,
    "original_ext": "mp4",
    "original_size": 10485760,
    "chunk_size": 262144
  }
  ```
  - `compression`: `0` = tanpa kompresi; `1..5` = tingkat kompresi Zstd yang dipetakan ke level Zstd `1, 3, 6, 12, 19`.
  - `chunk_size`: `262144` (`256 KiB`) untuk aliran `.vcv` yang mendukung *seeking*; `0` untuk aliran `.icv` tunggal atau payload tanpa blok.

### 2. Footer Seek Table (`.vcv` dengan `compression > 0`)
Disematkan di bagian akhir file untuk menerjemahkan posisi byte hasil dekompresi ke rentang byte terkompresi secara instan (`O(1)`):

| Field | Ukuran | Tipe | Keterangan |
| :--- | :--- | :--- | :--- |
| `SEEK_FRAME_MAGIC` | `4 byte` | `u32 LE` | Konstanta `0x184D2A5E` |
| `frame_size` | `4 byte` | `u32 LE` | Bernilai `(num_chunks * 8) + 8` |
| `entries[0..N]` | `8 * N byte` | `(u32 LE, u32 LE)` | Pasangan `(compressed_size, decompressed_size)` tiap blok |
| `num_chunks` | `4 byte` | `u32 LE` | Jumlah total blok terkompresi (`N`) |
| `SEEK_TABLE_MAGIC` | `4 byte` | `u32 LE` | Konstanta `0x8F92EAB1` |

---

## Struktur Proyek

```text
althub/
├── rust/
│   ├── Cargo.toml                 # Konfigurasi crate Rust & profil rilis NDK
│   └── src/lib.rs                 # Eksposur JNI, encoder/decoder .vcv/.icv & parser seek
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/althub/alteriza/
│       ├── NativeCore.kt          # Deklarasi JNI untuk libalthub_core.so
│       ├── Models.kt              # Model data & enumerasi
│       ├── LocalStreamServer.kt   # Server streaming lokal HTTP Range & Zstd
│       ├── AutoDiscoveryRepository.kt # Pengindeks multi-host & pencocok thumbnail
│       ├── AppStorageManager.kt   # Manajer unduhan lokal, konverter & riwayat
│       ├── BBCodeRenderer.kt      # Parser & renderer BBCode native Compose
│       ├── AltHubApp.kt           # Antarmuka utama Compose, feed & pemutar video kustom
│       ├── MainActivity.kt        # Host Activity layar penuh (immersive)
│       └── ui/theme/Theme.kt      # Palet gelap industrial & tema Material 3
└── LICENSE                        # Perjanjian Lisensi Pengguna Akhir (EULA - Bahasa Inggris)
```

---

## Lisensi & Ketentuan Penggunaan

Proyek ini tunduk pada **Perjanjian Lisensi Pengguna Akhir (EULA) Dua Cakupan** ([LICENSE](LICENSE)):

1. **Teknologi & Logika Format `.vcv` dan `.icv` (TERBUKA)**: Anda diperbolehkan secara bebas menggunakan, mempelajari, mengimplementasikan, memodifikasi, dan mendistribusikan encoder, decoder, pemutar media, maupun perangkat lunak lain yang memanfaatkan spesifikasi dan logika kompresi/konversi format `.vcv` dan `.icv`, **dengan syarat wajib menyertakan teks lengkap [LICENSE](LICENSE) (dalam bahasa Inggris)** pada setiap distribusi dan dokumentasi.
2. **Source Code Aplikasi Android AltHub (DILARANG KERAS)**: Seluruh source code aplikasi APK AltHub, antarmuka pengguna, arsitektur aplikasi, skrip build, aset, maupun file biner hasil kompilasi bersifat **hak milik eksklusif (proprietary)** dan **tidak boleh disentuh, disalin, dimodifikasi, di-fork untuk didistribusikan ulang, didekompilasi, direkayasa balik, atau digunakan dalam bentuk apa pun**.