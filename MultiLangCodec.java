package com.retroforge.gba.engine;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class MultiLangCodec {

    public enum SourceLang {
        AUTO("Tu dong (Theo ROM)", "auto", TranslateLanguage.ENGLISH, "tu dong"),
        EN("Tieng Anh (EN)", "en", TranslateLanguage.ENGLISH, "Tieng Anh"),
        JA("Tieng Nhat (JA)", "ja", TranslateLanguage.JAPANESE, "Tieng Nhat"),
        FR("Tieng Phap (FR)", "fr", TranslateLanguage.FRENCH, "Tieng Phap"),
        ZH("Tieng Trung (ZH)", "zh-CN", TranslateLanguage.CHINESE, "Tieng Trung"),
        RU("Tieng Nga (RU)", "ru", TranslateLanguage.RUSSIAN, "Tieng Nga");

        public final String label;
        public final String googleCode;
        public final String mlKitCode;
        public final String promptName;

        SourceLang(String label, String googleCode, String mlKitCode, String promptName) {
            this.label = label;
            this.googleCode = googleCode;
            this.mlKitCode = mlKitCode;
            this.promptName = promptName;
        }
    }

    public enum CharsetProfile {
        AUTO("Tu dong nhan dien (Moi game GBA)"),
        GEN3_EN("Pokemon Gen 3 Tieng Anh (An toan cao)"),
        GEN3_FR("Pokemon Gen 3 Tieng Phap (BPRF/BPEF...)"),
        GEN3_JP("Pokemon Gen 3 Tieng Nhat (BPRJ/BPEJ...)"),
        LATIN1("ASCII / Latin-1 (Game GBA Anh, Phap)"),
        SHIFT_JIS("Shift-JIS (Game GBA Tieng Nhat)"),
        GBK_CN("GBK / GB2312 (Game GBA Tieng Trung)"),
        CP1251_RU("Windows-1251 (Game GBA Tieng Nga)"),
        UTF8("UTF-8 (Game Homebrew / Da ngon ngu)"),
        CUSTOM_TBL("Bang ma .tbl tuy chinh (Font rieng)");

        public final String label;

        CharsetProfile(String label) {
            this.label = label;
        }
    }

    public static class CustomTbl {
        public final Map<Integer, String> dec1 = new HashMap<>();
        public final Map<String, byte[]> enc = new LinkedHashMap<>();
        public int terminator = 0x00;
    }

    public static class DecodedChunk {
        public final String text;
        public final int bytes;
        public final int asciiLetters;
        public final int vowelCount;
        public final int meaningfulChars;

        public DecodedChunk(String text, int bytes, int asciiLetters, int vowelCount, int meaningfulChars) {
            this.text = text;
            this.bytes = bytes;
            this.asciiLetters = asciiLetters;
            this.vowelCount = vowelCount;
            this.meaningfulChars = meaningfulChars;
        }
    }

    private static final Map<Integer, String> GEN3_EN_DEC = new HashMap<>();
    private static final Map<Integer, String> GEN3_FR_DEC = new HashMap<>();
    private static final Map<Integer, String> GEN3_JP_DEC = new HashMap<>();
    private static final Map<Character, Integer> GEN3_VN_ENC = new HashMap<>();
    private static final Map<Character, Character> VN_APPROX = new HashMap<>();
    private static final Map<String, Integer> GEN3_MULTI = new LinkedHashMap<>();
    private static final Map<Integer, Integer> FC_ARGS = new HashMap<>();
    public static final Set<String> GEN3_PREFIXES = new HashSet<>();

    private static final Pattern STRIP_CTRL = Pattern.compile("\\{[^}]+\\}|\\\\[nlp]|\\[[^\\]]+\\]");
    private static final Pattern WEIRD_CASE = Pattern.compile("[a-z][A-Z]");
    private static final Pattern QUAD_CHAR = Pattern.compile("(.)\\1\\1\\1");

    static {
        GEN3_PREFIXES.add("BPR");
        GEN3_PREFIXES.add("BPG");
        GEN3_PREFIXES.add("BPE");
        GEN3_PREFIXES.add("AXV");
        GEN3_PREFIXES.add("AXP");

        // STRICT English Gen 3 decoder (NO 0x01..0x28 low bytes -> 0% false positives in ARM code!)
        GEN3_EN_DEC.put(0x00, " ");
        GEN3_EN_DEC.put(0x1B, "\u00E9"); // é (Pokemon)
        GEN3_EN_DEC.put(0x2D, "&"); GEN3_EN_DEC.put(0x2E, "+"); GEN3_EN_DEC.put(0x34, "[Lv]");
        GEN3_EN_DEC.put(0x35, "="); GEN3_EN_DEC.put(0x36, ";"); GEN3_EN_DEC.put(0x53, "[PK]");
        GEN3_EN_DEC.put(0x54, "[MN]"); GEN3_EN_DEC.put(0x5B, "%"); GEN3_EN_DEC.put(0x5C, "(");
        GEN3_EN_DEC.put(0x5D, ")"); GEN3_EN_DEC.put(0xAB, "!"); GEN3_EN_DEC.put(0xAC, "?");
        GEN3_EN_DEC.put(0xAD, "."); GEN3_EN_DEC.put(0xAE, "-"); GEN3_EN_DEC.put(0xAF, ".");
        GEN3_EN_DEC.put(0xB0, "..."); GEN3_EN_DEC.put(0xB1, "\""); GEN3_EN_DEC.put(0xB2, "\"");
        GEN3_EN_DEC.put(0xB3, "'"); GEN3_EN_DEC.put(0xB4, "'"); GEN3_EN_DEC.put(0xB5, "[M]");
        GEN3_EN_DEC.put(0xB6, "[F]"); GEN3_EN_DEC.put(0xB7, "$"); GEN3_EN_DEC.put(0xB8, ",");
        GEN3_EN_DEC.put(0xB9, "x"); GEN3_EN_DEC.put(0xBA, "/"); GEN3_EN_DEC.put(0xF0, ":");
        GEN3_EN_DEC.put(0xFA, "\\l"); GEN3_EN_DEC.put(0xFB, "\\p"); GEN3_EN_DEC.put(0xFE, "\\n");
        GEN3_EN_DEC.put(0xFF, "");

        for (int d = 0; d < 10; d++) {
            GEN3_EN_DEC.put(0xA1 + d, String.valueOf(d));
            GEN3_VN_ENC.put((char) ('0' + d), 0xA1 + d);
        }
        for (int i = 0; i < 26; i++) {
            char up = (char) ('A' + i);
            char lo = (char) ('a' + i);
            GEN3_EN_DEC.put(0xBB + i, String.valueOf(up));
            GEN3_EN_DEC.put(0xD5 + i, String.valueOf(lo));
            GEN3_VN_ENC.put(up, 0xBB + i);
            GEN3_VN_ENC.put(lo, 0xD5 + i);
        }

        // French Gen 3 decoder (only used when ROM is French)
        GEN3_FR_DEC.putAll(GEN3_EN_DEC);
        GEN3_FR_DEC.put(0x01, "\u00C0"); GEN3_FR_DEC.put(0x02, "\u00C1"); GEN3_FR_DEC.put(0x03, "\u00C2");
        GEN3_FR_DEC.put(0x04, "\u00C7"); GEN3_FR_DEC.put(0x05, "\u00C8"); GEN3_FR_DEC.put(0x06, "\u00C9");
        GEN3_FR_DEC.put(0x07, "\u00CA"); GEN3_FR_DEC.put(0x08, "\u00CB"); GEN3_FR_DEC.put(0x09, "\u00CC");
        GEN3_FR_DEC.put(0x0B, "\u00CE"); GEN3_FR_DEC.put(0x0C, "\u00CF"); GEN3_FR_DEC.put(0x0D, "\u00D2");
        GEN3_FR_DEC.put(0x0E, "\u00D3"); GEN3_FR_DEC.put(0x0F, "\u00D4"); GEN3_FR_DEC.put(0x10, "\u0152");
        GEN3_FR_DEC.put(0x11, "\u00D9"); GEN3_FR_DEC.put(0x12, "\u00DA"); GEN3_FR_DEC.put(0x13, "\u00DB");
        GEN3_FR_DEC.put(0x16, "\u00E0"); GEN3_FR_DEC.put(0x17, "\u00E1"); GEN3_FR_DEC.put(0x19, "\u00E7");
        GEN3_FR_DEC.put(0x1A, "\u00E8"); GEN3_FR_DEC.put(0x1C, "\u00EA"); GEN3_FR_DEC.put(0x1D, "\u00EB");
        GEN3_FR_DEC.put(0x1E, "\u00EC"); GEN3_FR_DEC.put(0x20, "\u00EE"); GEN3_FR_DEC.put(0x21, "\u00EF");
        GEN3_FR_DEC.put(0x22, "\u00F2"); GEN3_FR_DEC.put(0x23, "\u00F3"); GEN3_FR_DEC.put(0x24, "\u00F4");
        GEN3_FR_DEC.put(0x25, "\u0153"); GEN3_FR_DEC.put(0x26, "\u00F9"); GEN3_FR_DEC.put(0x27, "\u00FA");
        GEN3_FR_DEC.put(0x28, "\u00FB"); GEN3_FR_DEC.put(0x5A, "\u00CD"); GEN3_FR_DEC.put(0x68, "\u00E2");
        GEN3_FR_DEC.put(0x6F, "\u00ED");

        // Japanese Gen 3 decoder
        GEN3_JP_DEC.putAll(GEN3_EN_DEC);
        String jpKana =
                "あいうえおかきくけこさしすせそ"
                + "たちつてとなにぬねのはひふへほま"
                + "みむめもやゆよらりるれろわをんぁ"
                + "ぃぅぇぉゃゅょがぎぐげござじずぜ"
                + "ぞだぢづでどばびぶべぼぱぴぷぺぽ"
                + "っアイウエオカキクケコサシスセソ"
                + "タチツテトナニヌネノハヒフヘホマ"
                + "ミムメモヤユヨラリルレロワヲンァ"
                + "ィゥェォャュョガギグゲゴザジズゼ"
                + "ゾダヂヅデドバビブベボパピプペポッ";
        for (int i = 0; i < jpKana.length(); i++) {
            GEN3_JP_DEC.put(0x01 + i, String.valueOf(jpKana.charAt(i)));
        }

        // Encoder ALWAYS supports all 26 native Gen 3 accented characters!
        for (Map.Entry<Integer, String> e : GEN3_FR_DEC.entrySet()) {
            if (e.getValue().length() == 1 && !GEN3_VN_ENC.containsKey(e.getValue().charAt(0))) {
                GEN3_VN_ENC.put(e.getValue().charAt(0), e.getKey());
            }
        }
        GEN3_VN_ENC.put('_', 0xAE);
        GEN3_VN_ENC.put('*', 0xB9);

        mapApprox("ấầẩẫậ", 'â'); mapApprox("ẤẦẨẪẬ", 'Â');
        mapApprox("ếềểễệ", 'ê'); mapApprox("ẾỀỂỄỆ", 'Ê');
        mapApprox("ốồổỗộ", 'ô'); mapApprox("ỐỒỔỖỘ", 'Ô');
        VN_APPROX.put('ắ', 'á'); VN_APPROX.put('ằ', 'à'); VN_APPROX.put('Ắ', 'Á'); VN_APPROX.put('Ằ', 'À');
        VN_APPROX.put('ớ', 'ó'); VN_APPROX.put('ờ', 'ò'); VN_APPROX.put('Ớ', 'Ó'); VN_APPROX.put('Ờ', 'Ò');
        VN_APPROX.put('ứ', 'ú'); VN_APPROX.put('ừ', 'ù'); VN_APPROX.put('Ứ', 'Ú'); VN_APPROX.put('Ừ', 'Ù');
        VN_APPROX.put('đ', 'd'); VN_APPROX.put('Đ', 'D');

        GEN3_MULTI.put("\\l", 0xFA); GEN3_MULTI.put("\\p", 0xFB); GEN3_MULTI.put("\\n", 0xFE);
        GEN3_MULTI.put("[Lv]", 0x34); GEN3_MULTI.put("[PK]", 0x53); GEN3_MULTI.put("[MN]", 0x54);
        GEN3_MULTI.put("[M]", 0xB5); GEN3_MULTI.put("[F]", 0xB6); GEN3_MULTI.put("...", 0xB0);

        int[] fc = {
            0x01, 1, 0x02, 1, 0x03, 1, 0x04, 3, 0x05, 1, 0x06, 1, 0x07, 0,
            0x08, 1, 0x09, 0, 0x0A, 0, 0x0B, 2, 0x0C, 1, 0x0D, 1, 0x0E, 1,
            0x0F, 0, 0x10, 2, 0x11, 1, 0x12, 1, 0x13, 1, 0x14, 1, 0x15, 0,
            0x16, 0, 0x17, 0, 0x18, 0
        };
        for (int i = 0; i < fc.length; i += 2) FC_ARGS.put(fc[i], fc[i + 1]);
    }

    private static void mapApprox(String s, char t) {
        for (int i = 0; i < s.length(); i++) VN_APPROX.put(s.charAt(i), t);
    }

    private static boolean isVowel(char c) {
        return "aeiouyAEIOUY".indexOf(c) >= 0;
    }

    public static boolean isGen3LatinByte(int b) {
        return (b >= 0xBB && b <= 0xEE) || b == 0x1B;
    }

    public static CustomTbl parseTbl(String content) {
        CustomTbl tbl = new CustomTbl();
        boolean hasEnd = false;
        for (String line : content.split("\\r?\\n")) {
            if (line == null || line.isEmpty()) continue;
            String tr = line.trim();
            if (tr.isEmpty() || tr.startsWith("#") || tr.startsWith(";")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String hex = line.substring(0, eq).trim();
            String val = line.substring(eq + 1);
            if (hex.isEmpty() || (hex.length() % 2) != 0 || hex.length() > 4) continue;
            try {
                if (val.equals("[END]") || val.equals("{END}") || val.equals("\\0")) {
                    tbl.terminator = Integer.parseInt(hex, 16) & 0xFF;
                    hasEnd = true;
                } else {
                    if (val.equals("[NL]")) val = "\\n";
                    byte[] raw = hexToBytes(hex);
                    if (raw == null) continue;
                    if (raw.length == 1) tbl.dec1.put(raw[0] & 0xFF, val);
                    if (!tbl.enc.containsKey(val)) tbl.enc.put(val, raw);
                }
            } catch (NumberFormatException ignored) {}
        }
        if (!hasEnd) tbl.terminator = "A".equals(tbl.dec1.get(0xBB)) ? 0xFF : 0x00;
        return tbl;
    }

    public static DecodedChunk decode(byte[] rom, int off, int maxLen, CharsetProfile profile, CustomTbl tbl) {
        switch (profile) {
            case GEN3_EN: return decodeGen3(rom, off, maxLen, GEN3_EN_DEC, false);
            case GEN3_FR: return decodeGen3(rom, off, maxLen, GEN3_FR_DEC, false);
            case GEN3_JP: return decodeGen3(rom, off, maxLen, GEN3_JP_DEC, true);
            case SHIFT_JIS: return decodeCStringCharset(rom, off, maxLen, Charset.forName("Shift_JIS"), SourceLang.JA);
            case GBK_CN: return decodeCStringCharset(rom, off, maxLen, Charset.forName("GBK"), SourceLang.ZH);
            case CP1251_RU: return decodeCStringCharset(rom, off, maxLen, Charset.forName("windows-1251"), SourceLang.RU);
            case UTF8: return decodeCStringCharset(rom, off, maxLen, StandardCharsets.UTF_8, SourceLang.AUTO);
            case CUSTOM_TBL: return decodeTbl(rom, off, maxLen, tbl);
            default: return decodeCStringCharset(rom, off, maxLen, StandardCharsets.ISO_8859_1, SourceLang.EN);
        }
    }

    private static DecodedChunk decodeGen3(byte[] rom, int off, int maxLen, Map<Integer, String> map, boolean isJp) {
        int b0 = rom[off] & 0xFF;
        if (b0 == 0xFF || b0 == 0x00) return null;
        if (!isJp && !map.containsKey(b0) && b0 != 0xFC && b0 != 0xFD) return null;

        int i = off;
        int end = Math.min(rom.length, off + maxLen);
        StringBuilder sb = new StringBuilder();
        int asciiLetters = 0;
        int vowels = 0;
        int meaningful = 0;

        while (i < end) {
            int b = rom[i] & 0xFF;
            if (b == 0xFF) return new DecodedChunk(sb.toString(), i - off + 1, asciiLetters, vowels, meaningful);
            if (b == 0xFC) {
                if (i + 1 >= end) return null;
                Integer arg = FC_ARGS.get(rom[i + 1] & 0xFF);
                if (arg == null || i + 2 + arg > end) return null;
                sb.append("{FC:");
                for (int k = 1; k < 2 + arg; k++) sb.append(String.format("%02X", rom[i + k] & 0xFF));
                sb.append('}');
                i += 2 + arg;
            } else if (b == 0xFD) {
                if (i + 1 >= end) return null;
                int v = rom[i + 1] & 0xFF;
                if (v > 0x20) return null;
                sb.append(String.format("{FD:%02X}", v));
                i += 2;
            } else {
                String ch = map.get(b);
                if (ch == null) return null;
                sb.append(ch);
                if (isJp) {
                    if (b >= 0x01 && b <= 0xA0) meaningful++;
                } else {
                    if (b >= 0xBB && b <= 0xEE) {
                        asciiLetters++;
                        meaningful++;
                        if (isVowel(ch.charAt(0))) vowels++;
                    } else if ((b >= 0x01 && b <= 0x28) || b == 0x5A || b == 0x68 || b == 0x6F) {
                        meaningful++;
                        vowels++;
                    }
                }
                i++;
            }
        }
        return null;
    }

    private static DecodedChunk decodeCStringCharset(byte[] rom, int off, int maxLen, Charset cs, SourceLang expected) {
        int i = off;
        int end = Math.min(rom.length, off + maxLen);
        while (i < end && rom[i] != 0x00) {
            int b = rom[i] & 0xFF;
            if (b < 0x20 && b != 0x0A && b != 0x0D && b != 0x09) return null;
            i++;
        }
        if (i >= end || i == off) return null;
        byte[] slice = new byte[i - off];
        System.arraycopy(rom, off, slice, 0, slice.length);
        String text = new String(slice, cs).replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "");
        if (text.contains("\uFFFD")) return null;

        int ascii = 0, vowels = 0, meaningful = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                ascii++;
                if (isVowel(c)) vowels++;
            }
            if (expected == SourceLang.JA) {
                if ((c >= '\u3040' && c <= '\u30FF') || (c >= '\u4E00' && c <= '\u9FFF')) meaningful++;
            } else if (expected == SourceLang.ZH) {
                if (c >= '\u4E00' && c <= '\u9FFF') meaningful++;
            } else if (expected == SourceLang.RU) {
                if (c >= '\u0400' && c <= '\u04FF') meaningful++;
            } else {
                if (Character.isLetter(c)) meaningful++;
            }
        }
        return new DecodedChunk(text, (i - off) + 1, ascii, vowels, meaningful);
    }

    private static DecodedChunk decodeTbl(byte[] rom, int off, int maxLen, CustomTbl tbl) {
        if (tbl == null) return null;
        int i = off;
        int end = Math.min(rom.length, off + maxLen);
        StringBuilder sb = new StringBuilder();
        int ascii = 0, vowels = 0, meaningful = 0;
        while (i < end) {
            int b = rom[i] & 0xFF;
            if (b == tbl.terminator) return new DecodedChunk(sb.toString(), i - off + 1, ascii, vowels, meaningful);
            String s = tbl.dec1.get(b);
            if (s == null) return null;
            sb.append(s);
            if (s.length() == 1) {
                char c = s.charAt(0);
                if (Character.isLetter(c)) meaningful++;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    ascii++;
                    if (isVowel(c)) vowels++;
                }
            }
            i++;
        }
        return null;
    }

    public static boolean isPlausibleSourceText(DecodedChunk dec, CharsetProfile profile) {
        if (dec == null || dec.bytes < 5 || dec.meaningfulChars < 3) return false;
        if (dec.text.contains("    ")) return false;
        String plain = STRIP_CTRL.matcher(dec.text).replaceAll(" ").trim();
        if (plain.length() < 4) return false;

        if (profile == CharsetProfile.GEN3_JP || profile == CharsetProfile.SHIFT_JIS || profile == CharsetProfile.GBK_CN) {
            return dec.meaningfulChars >= 3 && ((double) dec.meaningfulChars / plain.length()) >= 0.45;
        }
        if (profile == CharsetProfile.CP1251_RU) {
            return dec.meaningfulChars >= 4 && ((double) dec.meaningfulChars / plain.length()) >= 0.50;
        }

        // Strict Latin (English / French) filter — prevents corrupting ARM/Thumb executable code!
        if (dec.bytes < 6 || dec.asciiLetters < 4 || dec.vowelCount < 1) return false;
        if (((double) dec.meaningfulChars / plain.length()) < 0.50) return false;
        if (dec.asciiLetters >= 8 && ((double) dec.vowelCount / dec.meaningfulChars) < 0.20) return false;
        // For French, require >= 65% standard ASCII letters so binary 0x01..0x28 garbage is rejected
        if (profile == CharsetProfile.GEN3_FR && ((double) dec.asciiLetters / dec.meaningfulChars) < 0.65) {
            return false;
        }

        String[] words = plain.split("[^\\p{L}]+");
        int valid = 0, bad = 0;
        for (String w : words) {
            if (w.length() < 2) continue;
            valid++;
            if (WEIRD_CASE.matcher(w).find() && !w.equals("PokeNav") && !w.equals("PokéNav")) bad++;
            else if (QUAD_CHAR.matcher(w.toLowerCase()).find()) bad++;
            else if (w.length() >= 4 && !hasAnyVowel(w)) bad++;
        }
        return valid > 0 && (bad == 0 || bad < valid * 0.35);
    }

    private static boolean hasAnyVowel(String w) {
        String s = MultiTranslator.stripAccents(w);
        for (int i = 0; i < s.length(); i++) {
            if (isVowel(s.charAt(i))) return true;
        }
        return false;
    }

    public static byte[] encodeVietnamese(String text, CharsetProfile profile, CustomTbl tbl) {
        if (profile == CharsetProfile.GEN3_EN || profile == CharsetProfile.GEN3_FR || profile == CharsetProfile.GEN3_JP) {
            return encodeGen3(text);
        }
        if (profile == CharsetProfile.UTF8) {
            String s = Normalizer.normalize(text, Normalizer.Form.NFC)
                    .replace("\\n", "\n").replace("\\l", "\n").replace("\\p", "\n");
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[raw.length + 1];
            System.arraycopy(raw, 0, out, 0, raw.length);
            return out;
        }
        if (profile == CharsetProfile.CUSTOM_TBL && tbl != null) {
            return encodeTbl(text, tbl);
        }
        return encodeLatin1(text);
    }

    private static byte[] encodeGen3(String text) {
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int i = 0;
        boolean inQ = false;
        while (i < norm.length()) {
            if (norm.charAt(i) == '{') {
                int cl = norm.indexOf('}', i);
                if (cl != -1) {
                    String tag = norm.substring(i + 1, cl);
                    if (tag.startsWith("FC:") || tag.startsWith("FD:")) {
                        byte[] raw = hexToBytes(tag.substring(3));
                        if (raw != null) {
                            bos.write(tag.startsWith("FC:") ? 0xFC : 0xFD);
                            bos.write(raw, 0, raw.length);
                            i = cl + 1;
                            continue;
                        }
                    }
                }
            }
            boolean multi = false;
            for (Map.Entry<String, Integer> e : GEN3_MULTI.entrySet()) {
                if (norm.startsWith(e.getKey(), i)) {
                    bos.write(e.getValue());
                    i += e.getKey().length();
                    multi = true;
                    break;
                }
            }
            if (multi) continue;

            char c = norm.charAt(i);
            if (c == '"' || c == '\u201C' || c == '\u201D') {
                bos.write(inQ ? 0xB2 : 0xB1);
                inQ = !inQ;
            } else if (c == '\u2018' || c == '\u2019') {
                bos.write(0xB4);
            } else {
                Integer code = GEN3_VN_ENC.get(c);
                if (code == null) {
                    Character ap = VN_APPROX.get(c);
                    if (ap != null) code = GEN3_VN_ENC.get(ap);
                }
                if (code == null) {
                    String st = MultiTranslator.stripAccents(String.valueOf(c));
                    if (!st.isEmpty()) code = GEN3_VN_ENC.get(st.charAt(0));
                }
                if (code != null) bos.write(code);
                else if (c == '\n' || c == '\r' || c == '\t') bos.write(0x00);
            }
            i++;
        }
        bos.write(0xFF);
        return bos.toByteArray();
    }

    private static byte[] encodeLatin1(String text) {
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int i = 0;
        while (i < norm.length()) {
            if (norm.startsWith("\\n", i) || norm.startsWith("\\l", i) || norm.startsWith("\\p", i)) {
                bos.write(0x0A);
                i += 2;
                continue;
            }
            char c = norm.charAt(i);
            if (c == '\n') bos.write(0x0A);
            else if ((c >= 0x20 && c <= 0x7E) || (c >= 0xC0 && c <= 0xFF)) bos.write(c);
            else {
                Character ap = VN_APPROX.get(c);
                if (ap != null && ap <= 0xFF) bos.write(ap);
                else {
                    String st = MultiTranslator.stripAccents(String.valueOf(c));
                    if (!st.isEmpty()) bos.write(st.charAt(0));
                }
            }
            i++;
        }
        bos.write(0x00);
        return bos.toByteArray();
    }

    private static byte[] encodeTbl(String text, CustomTbl tbl) {
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int i = 0;
        while (i < norm.length()) {
            if (norm.startsWith("\\n", i) && tbl.enc.containsKey("\\n")) {
                byte[] nl = tbl.enc.get("\\n");
                bos.write(nl, 0, nl.length);
                i += 2;
                continue;
            }
            String ch = String.valueOf(norm.charAt(i));
            byte[] raw = tbl.enc.get(ch);
            if (raw == null) {
                Character ap = VN_APPROX.get(norm.charAt(i));
                if (ap != null) raw = tbl.enc.get(String.valueOf(ap));
            }
            if (raw == null) {
                String st = MultiTranslator.stripAccents(ch);
                if (!st.isEmpty()) raw = tbl.enc.get(String.valueOf(st.charAt(0)));
            }
            if (raw != null) bos.write(raw, 0, raw.length);
            i++;
        }
        bos.write(tbl.terminator);
        return bos.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() % 2) != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
