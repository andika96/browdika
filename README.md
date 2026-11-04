# Browdika

Browser Android pribadi berbasis WebView.

## Fitur MVP

- Address bar dengan pencarian Google otomatis
- Tombol kembali, maju, beranda, muat ulang
- Unduhan lewat DownloadManager
- Dukungan unggah file dari situs
- Cookie, DOM storage, dan User-Agent yang kompatibel
- Edge-to-edge untuk Android 15

## Build

APK dibangun otomatis oleh GitHub Actions pada setiap push ke `main`.

1. Buka tab **Actions** di repo ini.
2. Pilih workflow **Build Browdika APK** yang paling baru.
3. Unduh artifact **browdika-debug**.
4. Ekstrak, lalu install `app-debug.apk` di HP (izinkan "install unknown apps").

## Spesifikasi

- Package: `com.browdika.browser`
- minSdk 26, targetSdk 35
- Kotlin 1.9.25, AGP 8.6.1, Gradle 8.7

## Catatan

- Login Google di WebView biasanya diblokir oleh Google. Pencarian Google tetap berfungsi.
- Login Facebook diupayakan lewat User-Agent Chrome-like, tetapi bisa berubah tergantung kebijakan Facebook.
- Aplikasi ini untuk penggunaan pribadi, bukan untuk distribusi di Play Store.
