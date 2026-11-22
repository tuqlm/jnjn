package com.retroforge.gba.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RomBuilder {

    public static class PatchSummary {
        public int inPlace;
        public int repointed;
        public int skipped;
        public int noSpace;
        public boolean expandedTo32MB;
    }

    private static final int MAX_GBA_SIZE = 32 * 1024 * 1024; // 32 MB

    public static byte[] build(
            byte[] originalRom,
            List<LineEntry> lines,
            MultiLangCodec.CharsetProfile profile,
            MultiLangCodec.CustomTbl tbl,
            PatchSummary summary
    ) {
        byte[] rom = new byte[originalRom.length];
        System.arraycopy(originalRom, 0, rom, 0, originalRom.length);

        // ONLY use safe 0xFF free space (never overwrite 0x00 zero-initialized buffers!)
        List<RomAnalyzer.SpaceChunk> free = RomAnalyzer.findFreeSpace(rom, (byte) 0xFF, 4096, 128);

        // If ROM < 32MB and low on 0xFF free space, safely expand ROM up to 32MB filled with 0xFF
        if (RomAnalyzer.sumChunks(free) < 262144 && rom.length < MAX_GBA_SIZE) {
            int oldLen = rom.length;
            int newLen = Math.min(MAX_GBA_SIZE, Math.max(oldLen + 4 * 1024 * 1024, 16 * 1024 * 1024));
            byte[] expanded = new byte[newLen];
            System.arraycopy(rom, 0, expanded, 0, oldLen);
            Arrays.fill(expanded, oldLen, newLen, (byte) 0xFF);
            rom = expanded;
            summary.expandedTo32MB = true;
            free.add(new RomAnalyzer.SpaceChunk((oldLen + 128 + 3) & ~3, newLen - 128));
        }

        byte pad = (profile == MultiLangCodec.CharsetProfile.GEN3_EN
                || profile == MultiLangCodec.CharsetProfile.GEN3_FR
                || profile == MultiLangCodec.CharsetProfile.GEN3_JP)
                ? (byte) 0xFF
                : (profile == MultiLangCodec.CharsetProfile.CUSTOM_TBL && tbl != null ? (byte) tbl.terminator : (byte) 0x00);

        for (LineEntry e : lines) {
            if (!e.hasTranslation() || e.offset < 0x1000) {
                summary.skipped++;
                continue;
            }
            byte[] enc = MultiLangCodec.encodeVietnamese(e.vietnamese.trim(), profile, tbl);
            if (enc.length <= e.byteLen) {
                System.arraycopy(enc, 0, rom, e.offset, enc.length);
                for (int k = enc.length; k < e.byteLen; k++) rom[e.offset + k] = pad;
                summary.inPlace++;
            } else if (!e.repointable || e.pointers.isEmpty()) {
                int max = Math.max(0, e.byteLen - 1);
                if (max > 0) {
                    System.arraycopy(enc, 0, rom, e.offset, max);
                    rom[e.offset + max] = pad;
                    summary.inPlace++;
                } else {
                    summary.noSpace++;
                }
            } else {
                int newOff = alloc4(free, enc.length);
                if (newOff < 0) {
                    summary.noSpace++;
                    continue;
                }
                System.arraycopy(enc, 0, rom, newOff, enc.length);
                int oldAddr = 0x08000000 + e.offset;
                int newAddr = 0x08000000 + newOff;
                byte o0 = (byte) (oldAddr & 0xFF), o1 = (byte) ((oldAddr >>> 8) & 0xFF),
                        o2 = (byte) ((oldAddr >>> 16) & 0xFF), o3 = (byte) ((oldAddr >>> 24) & 0xFF);
                byte n0 = (byte) (newAddr & 0xFF), n1 = (byte) ((newAddr >>> 8) & 0xFF),
                        n2 = (byte) ((newAddr >>> 16) & 0xFF), n3 = (byte) ((newAddr >>> 24) & 0xFF);
                for (int p : e.pointers) {
                    if (p >= 0x1000 && p + 4 <= rom.length
                            && rom[p] == o0 && rom[p + 1] == o1 && rom[p + 2] == o2 && rom[p + 3] == o3) {
                        rom[p] = n0; rom[p + 1] = n1; rom[p + 2] = n2; rom[p + 3] = n3;
                    }
                }
                summary.repointed++;
            }
        }

        // Recalculate GBA header complement checksum at 0xBD
        if (rom.length >= 0xBE) {
            int chk = 0;
            for (int i = 0xA0; i <= 0xBC; i++) {
                chk = (chk - (rom[i] & 0xFF)) & 0xFF;
            }
            rom[0xBD] = (byte) ((chk - 0x19) & 0xFF);
        }
        return rom;
    }

    private static int alloc4(List<RomAnalyzer.SpaceChunk> chunks, int needed) {
        for (RomAnalyzer.SpaceChunk c : chunks) {
            int cur = c.start;
            int rem = cur % 4;
            if (rem != 0) cur += (4 - rem);
            if (cur + needed <= c.end) {
                c.start = cur + needed;
                return cur;
            }
        }
        return -1;
    }

    public static String toJson(String romName, MultiLangCodec.SourceLang lang, List<LineEntry> lines) throws Exception {
        JSONObject root = new JSONObject();
        root.put("rom", romName);
        root.put("source_lang", lang.name());
        JSONArray arr = new JSONArray();
        for (LineEntry e : lines) {
            arr.put(new JSONObject()
                    .put("id", e.id)
                    .put("offset", "0x" + Integer.toHexString(e.offset))
                    .put("src", e.original)
                    .put("vi", e.vietnamese));
        }
        root.put("lines", arr);
        return root.toString(2);
    }

    public static int fromJson(String json, List<LineEntry> targets) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.has("lines") ? root.getJSONArray("lines") : root.getJSONArray("entries");
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String vi = o.optString("vi", "");
            if (!vi.isEmpty()) {
                String off = o.getString("offset").trim();
                int v = (off.startsWith("0x") || off.startsWith("0X"))
                        ? Integer.parseInt(off.substring(2), 16) : Integer.parseInt(off);
                map.put(v, vi);
            }
        }
        int cnt = 0;
        for (LineEntry e : targets) {
            String vi = map.get(e.offset);
            if (vi != null && !vi.isEmpty()) {
                e.vietnamese = vi;
                cnt++;
            }
        }
        return cnt;
    }
}
