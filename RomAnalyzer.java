package com.retroforge.gba.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RomAnalyzer {

    public static class HeaderInfo {
        public String title = "UNKNOWN";
        public String gameCode = "????";
        public int sizeBytes;
        public int freeFF;
        public int free00;
        public MultiLangCodec.SourceLang guessedLang = MultiLangCodec.SourceLang.EN;
        public MultiLangCodec.CharsetProfile guessedProfile = MultiLangCodec.CharsetProfile.LATIN1;
    }

    public static class Extraction {
        public MultiLangCodec.SourceLang resolvedLang;
        public MultiLangCodec.CharsetProfile resolvedProfile;
        public boolean usedDirectScan;
        public final List<LineEntry> lines = new ArrayList<>();
    }

    public static class SpaceChunk {
        public int start;
        public int end;

        public SpaceChunk(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static HeaderInfo inspect(byte[] rom) {
        HeaderInfo info = new HeaderInfo();
        info.sizeBytes = rom.length;
        if (rom.length >= 0xB2) {
            info.title = readAscii(rom, 0xA0, 12);
            info.gameCode = readAscii(rom, 0xAC, 4);
        }
        info.freeFF = sumChunks(findFreeSpace(rom, (byte) 0xFF, 4096, 128));
        info.free00 = sumChunks(findFreeSpace(rom, (byte) 0x00, 8192, 128));

        String prefix3 = info.gameCode.length() >= 3 ? info.gameCode.substring(0, 3) : "";
        char region = info.gameCode.length() >= 4 ? info.gameCode.charAt(3) : 'E';
        boolean isGen3 = MultiLangCodec.GEN3_PREFIXES.contains(prefix3);

        if (region == 'J') {
            info.guessedLang = MultiLangCodec.SourceLang.JA;
            info.guessedProfile = isGen3 ? MultiLangCodec.CharsetProfile.GEN3_JP : MultiLangCodec.CharsetProfile.SHIFT_JIS;
        } else if (region == 'F') {
            info.guessedLang = MultiLangCodec.SourceLang.FR;
            info.guessedProfile = isGen3 ? MultiLangCodec.CharsetProfile.GEN3_FR : MultiLangCodec.CharsetProfile.LATIN1;
        } else if (region == 'C') {
            info.guessedLang = MultiLangCodec.SourceLang.ZH;
            info.guessedProfile = MultiLangCodec.CharsetProfile.GBK_CN;
        } else if (region == 'R') {
            info.guessedLang = MultiLangCodec.SourceLang.RU;
            info.guessedProfile = MultiLangCodec.CharsetProfile.CP1251_RU;
        } else {
            info.guessedLang = MultiLangCodec.SourceLang.EN;
            info.guessedProfile = isGen3 ? MultiLangCodec.CharsetProfile.GEN3_EN : MultiLangCodec.CharsetProfile.LATIN1;
        }
        return info;
    }

    private static String readAscii(byte[] rom, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < rom.length; i++) {
            int b = rom[off + i] & 0xFF;
            if (b >= 32 && b <= 126) sb.append((char) b);
        }
        return sb.toString().trim();
    }

    public static List<SpaceChunk> findFreeSpace(byte[] rom, byte fill, int minLen, int margin) {
        List<SpaceChunk> out = new ArrayList<>();
        // Never allocate in the first 0x10000 bytes (boot/header/core code)
        int i = Math.min(rom.length, 0x10000);
        while (i < rom.length) {
            if (rom[i] == fill) {
                int j = i;
                while (j < rom.length && rom[j] == fill) j++;
                if (j - i >= minLen) {
                    int s = i + margin;
                    int rem = s % 4;
                    if (rem != 0) s += (4 - rem);
                    int e = j - margin;
                    if (e > s) out.add(new SpaceChunk(s, e));
                }
                i = j;
            } else {
                i++;
            }
        }
        out.sort((a, b) -> Integer.compare(b.end - b.start, a.end - a.start));
        return out;
    }

    public static int sumChunks(List<SpaceChunk> list) {
        int t = 0;
        for (SpaceChunk c : list) t += (c.end - c.start);
        return t;
    }

    public static Extraction extract(
            byte[] rom,
            MultiLangCodec.SourceLang reqLang,
            MultiLangCodec.CharsetProfile reqProfile,
            boolean includeShort,
            MultiLangCodec.CustomTbl tbl
    ) {
        HeaderInfo hdr = inspect(rom);
        Map<Integer, List<Integer>> ptrs = new HashMap<>(65536);
        int maxOff = rom.length - 2;
        for (int i = 3; i < rom.length; i++) {
            int hi = rom[i] & 0xFF;
            if (hi == 0x08 || hi == 0x09) {
                int p = i - 3;
                int off = (rom[p] & 0xFF) | ((rom[p + 1] & 0xFF) << 8)
                        | ((rom[p + 2] & 0xFF) << 16) | ((hi - 8) << 24);
                // Protect boot vectors & header (< 0x1000)
                if (off >= 0x1000 && off < maxOff) {
                    List<Integer> l = ptrs.get(off);
                    if (l == null) {
                        l = new ArrayList<>(2);
                        ptrs.put(off, l);
                    }
                    l.add(p);
                }
            }
        }

        List<Integer> offsets = new ArrayList<>(ptrs.keySet());
        Collections.sort(offsets);

        MultiLangCodec.SourceLang lang = (reqLang == MultiLangCodec.SourceLang.AUTO) ? hdr.guessedLang : reqLang;
        MultiLangCodec.CharsetProfile profile = reqProfile;
        if (profile == MultiLangCodec.CharsetProfile.AUTO) {
            profile = hdr.guessedProfile;
            if (lang == MultiLangCodec.SourceLang.JA && profile == MultiLangCodec.CharsetProfile.LATIN1) {
                profile = MultiLangCodec.CharsetProfile.SHIFT_JIS;
            } else if (lang == MultiLangCodec.SourceLang.ZH) {
                profile = MultiLangCodec.CharsetProfile.GBK_CN;
            } else if (lang == MultiLangCodec.SourceLang.RU) {
                profile = MultiLangCodec.CharsetProfile.CP1251_RU;
            }
        }

        Extraction ext = new Extraction();
        ext.resolvedLang = lang;
        ext.resolvedProfile = profile;

        List<LineEntry> raw = new ArrayList<>();
        boolean isCjkOrJp = (profile == MultiLangCodec.CharsetProfile.GEN3_JP
                || profile == MultiLangCodec.CharsetProfile.SHIFT_JIS
                || profile == MultiLangCodec.CharsetProfile.GBK_CN);
        boolean isGen3Latin = (profile == MultiLangCodec.CharsetProfile.GEN3_EN
                || profile == MultiLangCodec.CharsetProfile.GEN3_FR);

        for (int off : offsets) {
            if (isGen3Latin && off >= 2) {
                int p1 = rom[off - 1] & 0xFF;
                int p2 = rom[off - 2] & 0xFF;
                if (MultiLangCodec.isGen3LatinByte(p1) && (MultiLangCodec.isGen3LatinByte(p2) || p2 == 0x00)) {
                    continue;
                }
            }

            MultiLangCodec.DecodedChunk dec = MultiLangCodec.decode(rom, off, 1600, profile, tbl);
            if (!MultiLangCodec.isPlausibleSourceText(dec, profile)) continue;

            if (!includeShort && !isCjkOrJp) {
                boolean hasBreak = dec.text.contains(" ") || dec.text.contains("\\n")
                        || dec.text.contains("\\l") || dec.text.contains("\\p");
                if (!hasBreak) continue;
            }

            LineEntry e = new LineEntry();
            e.offset = off;
            e.byteLen = dec.bytes;
            e.multiline = dec.text.contains("\\n") || dec.text.contains("\\l") || dec.text.contains("\\p");
            e.repointable = true;
            e.original = dec.text;
            e.pointers.addAll(ptrs.get(off));
            raw.add(e);
        }

        // Direct raw scan fallback for non-Gen3 games without 0x08xxxxxx pointers
        if (raw.size() < 25 && !isGen3Latin && profile != MultiLangCodec.CharsetProfile.GEN3_JP) {
            ext.usedDirectScan = true;
            int i = Math.min(rom.length, 0x10000);
            int limit = rom.length - 6;
            while (i < limit) {
                MultiLangCodec.DecodedChunk dec = MultiLangCodec.decode(rom, i, 1000, profile, tbl);
                if (dec != null && dec.bytes >= 8 && MultiLangCodec.isPlausibleSourceText(dec, profile)
                        && (isCjkOrJp || dec.text.contains(" "))) {
                    LineEntry e = new LineEntry();
                    e.offset = i;
                    e.byteLen = dec.bytes;
                    e.multiline = dec.text.contains("\\n");
                    List<Integer> p = ptrs.get(i);
                    if (p != null && !p.isEmpty()) {
                        e.pointers.addAll(p);
                        e.repointable = true;
                    } else {
                        e.repointable = false;
                    }
                    e.original = dec.text;
                    raw.add(e);
                    i += dec.bytes;
                } else {
                    i++;
                }
            }
        }

        int busyEnd = -1;
        int seq = 1;
        for (LineEntry e : raw) {
            if (e.offset < busyEnd) continue;
            e.id = seq++;
            ext.lines.add(e);
            busyEnd = e.offset + e.byteLen;
        }
        return ext;
    }
}
