# RetroForge GBA (Multi-Language GBA Vietnamese Localizer)

Ung dung Android Viet hoa ROM Game Boy Advance (.gba) ho tro **5 ngon ngu goc**:
- **Tieng Anh (EN)**: Pokemon Gen 3, ASCII/Latin-1, UTF-8, `.tbl`.
- **Tieng Nhat (JA)**: Pokemon Gen 3 Nhat (Hiragana/Katakana), Shift-JIS, `.tbl`.
- **Tieng Phap (FR)**: Pokemon Gen 3 Phap, Latin-1 co dau Phap, `.tbl`.
- **Tieng Trung (ZH)**: GBK / GB2312, UTF-8, `.tbl`.
- **Tieng Nga (RU)**: Windows-1251 (Cyrillic), UTF-8, `.tbl`.

## Tinh nang noi bat
- Giao dien sang Material 3 phong cach Retro Crimson chia **4 tab tren cung** (`ROM`, `Goi Offline`, `Dich`, `Sua`).
- Quan ly tai **goi ngon ngu Offline ML Kit** cho tung ngon ngu (Anh, Nhat, Phap, Trung, Nga -> Viet).
- 4 che do dich: **Ket hop Offline + Gemini AI (Song song)**, **Offline ML Kit**, **Online Wi-Fi Turbo (6 luong song song)**, **Gemini AI Toan bo (3 luong song song)**.
- Mac dinh giu **Tieng Viet co dau**.

## Cach build file .APK tren GitHub Actions
1. Tao 1 repo moi tren https://github.com.
2. Tai toan bo file cua du an len repo (workflow `.github/workflows/build-retroforge.yml` tu dong sap xep thu muc neu ban tai phang).
3. Vao tab **Actions** -> tai artifact **RetroForge-GBA-APK** de lay `app-debug.apk`.
