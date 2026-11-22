package com.retroforge.gba.engine;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiTranslator {

    private static final Pattern CTRL_TOK = Pattern.compile(
            "(\\{FC:[0-9A-Fa-f]+\\}|\\{FD:[0-9A-Fa-f]+\\}|\\[Lv\\]|\\[PK\\]|\\[MN\\]|\\[M\\]|\\[F\\]|\\\\p|%[0-9\\.]*[sdxufc])"
    );
    private static final Pattern SPLIT_MARK = Pattern.compile("\\s*###\\s*S\\s*###\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_SP = Pattern.compile(" {2,}");
    private static final Pattern PUNCT_SP = Pattern.compile(" ([.,!?;:])");
    private static final Pattern STRIP_FC = Pattern.compile("\\{FC:[0-9A-Fa-f]+\\}");
    private static final Pattern STRIP_FD = Pattern.compile("\\{FD:[0-9A-Fa-f]+\\}");

    private final Map<String, Translator> offlineClients = new HashMap<>();

    private synchronized Translator getOfflineClient(MultiLangCodec.SourceLang srcLang) {
        String code = srcLang.mlKitCode;
        Translator t = offlineClients.get(code);
        if (t == null) {
            TranslatorOptions opts = new TranslatorOptions.Builder()
                    .setSourceLanguage(code)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build();
            t = Translation.getClient(opts);
            offlineClients.put(code, t);
        }
        return t;
    }

    public boolean isPackDownloaded(String mlKitLangCode) throws Exception {
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        TranslateRemoteModel m = new TranslateRemoteModel.Builder(mlKitLangCode).build();
        Boolean ok = Tasks.await(mgr.isModelDownloaded(m));
        return ok != null && ok;
    }

    public void downloadPackForPair(MultiLangCodec.SourceLang srcLang) throws Exception {
        DownloadConditions cond = new DownloadConditions.Builder().build();
        Tasks.await(getOfflineClient(srcLang).downloadModelIfNeeded(cond));
    }

    public String translateOffline(String text, MultiLangCodec.SourceLang srcLang) throws Exception {
        Protected p = protect(text);
        if (p.clean.isEmpty()) return text;
        String vi = Tasks.await(getOfflineClient(srcLang).translate(p.clean));
        return restore(vi, p.tokens);
    }

    public List<String> translateOnlineBatch(List<String> texts, MultiLangCodec.SourceLang srcLang) throws Exception {
        int n = texts.size();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(null);

        List<Integer> idxList = new ArrayList<>();
        List<Protected> pList = new ArrayList<>();
        StringBuilder joined = new StringBuilder();

        for (int i = 0; i < n; i++) {
            Protected p = protect(texts.get(i));
            if (p.clean.isEmpty()) {
                out.set(i, texts.get(i));
            } else {
                if (!idxList.isEmpty()) joined.append("\n###S###\n");
                joined.append(p.clean);
                idxList.add(i);
                pList.add(p);
            }
        }
        if (idxList.isEmpty()) return out;

        if (idxList.size() > 1 && joined.length() < 3400) {
            try {
                String res = googleCall(joined.toString(), srcLang.googleCode);
                String[] parts = SPLIT_MARK.split(res, -1);
                if (parts.length == idxList.size()) {
                    for (int k = 0; k < idxList.size(); k++) {
                        out.set(idxList.get(k), restore(parts[k].trim(), pList.get(k).tokens));
                    }
                    return out;
                }
            } catch (Exception ignored) {}
        }

        for (int k = 0; k < idxList.size(); k++) {
            int orig = idxList.get(k);
            try {
                String r = googleCall(pList.get(k).clean, srcLang.googleCode);
                out.set(orig, restore(r.trim(), pList.get(k).tokens));
            } catch (Exception e) {
                out.set(orig, texts.get(orig));
            }
        }
        return out;
    }

    private String googleCall(String q, String sl) throws Exception {
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + URLEncoder.encode(sl, "UTF-8") + "&tl=vi&dt=t&q=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        if (conn.getResponseCode() != 200) {
            int c = conn.getResponseCode();
            conn.disconnect();
            throw new Exception("HTTP " + c);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        JSONArray segs = new JSONArray(sb.toString()).getJSONArray(0);
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < segs.length(); i++) {
            JSONArray s = segs.getJSONArray(i);
            if (!s.isNull(0)) res.append(s.getString(0));
        }
        return res.toString();
    }

    public List<String> translateGeminiBatch(List<String> texts, MultiLangCodec.SourceLang srcLang, String apiKey) throws Exception {
        JSONArray inArr = new JSONArray();
        for (String s : texts) {
            inArr.put(s.replace("\\n", " ").replace("\\l", " ").replaceAll(" {2,}", " ").trim());
        }
        String prompt =
                "Ban la chuyen gia Viet hoa game GBA tu " + srcLang.promptName + " sang Tieng Viet CO DAU tu nhien, gon gang.\n"
                + "QUY TAC:\n"
                + "1. Giu nguyen 100% cac ma dieu khien: {FC:...}, {FD:...}, [Lv], [PK], [MN], [M], [F], \\p, %s, %d.\n"
                + "2. Dich dung ngu canh game (RPG, hanh dong, phieu luu, Pokemon, Fire Emblem, Zelda...), giu ten rieng nhan vat/dia danh.\n"
                + "3. Chi tra ve DUY NHAT mot JSON Array chua dung " + texts.size() + " chuoi da dich theo dung thu tu.\n\n"
                + "INPUT JSON:\n" + inArr.toString();

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                + URLEncoder.encode(apiKey.trim(), "UTF-8");

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("responseMimeType", "application/json"));

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
            }
        }
        conn.disconnect();
        if (code != 200) throw new Exception("Gemini HTTP " + code);

        String raw = new JSONObject(resp.toString())
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim();
        if (raw.startsWith("```")) {
            int nl = raw.indexOf('\n');
            int end = raw.lastIndexOf("```");
            if (nl != -1 && end > nl) raw = raw.substring(nl + 1, end).trim();
        }
        JSONArray arr = new JSONArray(raw);
        if (arr.length() != texts.size()) throw new Exception("AI lech so cau");

        List<String> out = new ArrayList<>(texts.size());
        for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i).trim());
        return out;
    }

    public synchronized void closeAll() {
        for (Translator t : offlineClients.values()) t.close();
        offlineClients.clear();
    }

    private static class Protected {
        String clean;
        final List<String> tokens = new ArrayList<>();
    }

    private static Protected protect(String s) {
        Protected p = new Protected();
        String flat = s.replace("\\n", " ").replace("\\l", " ");
        Matcher m = CTRL_TOK.matcher(flat);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int idx = p.tokens.size();
            p.tokens.add(m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(" [[T" + idx + "]] "));
        }
        m.appendTail(sb);
        p.clean = MULTI_SP.matcher(sb.toString()).replaceAll(" ").trim();
        return p;
    }

    private static String restore(String tr, List<String> tokens) {
        String out = tr;
        for (int i = 0; i < tokens.size(); i++) {
            String orig = tokens.get(i);
            Pattern p = Pattern.compile("\\s*\\[\\[\\s*T\\s*" + i + "\\s*\\]\\]\\s*", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(out);
            if (m.find()) {
                String rep = (orig.equals("\\p") || orig.startsWith("{FC:")) ? orig : (" " + orig + " ");
                out = m.replaceFirst(Matcher.quoteReplacement(rep));
            }
        }
        out = MULTI_SP.matcher(out).replaceAll(" ");
        out = out.replace(" \\p", "\\p").replace("\\p ", "\\p");
        return PUNCT_SP.matcher(out).replaceAll("$1").trim();
    }

    public static String formatOutput(String vi, boolean noAccents, boolean multiline, int cols, MultiLangCodec.CharsetProfile prof) {
        String s = noAccents ? stripAccents(vi) : vi;
        if (multiline) {
            boolean gen3 = (prof == MultiLangCodec.CharsetProfile.GEN3_EN || prof == MultiLangCodec.CharsetProfile.GEN3_FR || prof == MultiLangCodec.CharsetProfile.GEN3_JP);
            s = wrap(s, cols, gen3);
        }
        return s;
    }

    public static String stripAccents(String in) {
        String s = in.replace("\u0110", "D").replace("\u0111", "d")
                .replace("\u201C", "\"").replace("\u201D", "\"")
                .replace("\u2018", "'").replace("\u2019", "'")
                .replace("\u2013", "-").replace("\u2014", "-")
                .replace("\u2026", "...");
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK && c >= 32 && c <= 126) sb.append(c);
        }
        return MULTI_SP.matcher(sb.toString()).replaceAll(" ").trim();
    }

    private static int visLen(String s) {
        String t = STRIP_FC.matcher(s).replaceAll("");
        t = STRIP_FD.matcher(t).replaceAll("XXXXXX");
        return t.replace("[Lv]", "Lv").replace("[PK]", "P").replace("[MN]", "M").length();
    }

    private static String wrap(String text, int maxCols, boolean gen3) {
        String[] pages = text.split("\\\\p", -1);
        StringBuilder out = new StringBuilder();
        for (int p = 0; p < pages.length; p++) {
            if (p > 0) out.append("\\p");
            String clean = MULTI_SP.matcher(pages[p].replace("\\n", " ").replace("\\l", " ")).replaceAll(" ").trim();
            if (clean.isEmpty()) continue;
            List<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String w : clean.split(" ")) {
                if (cur.length() == 0) cur.append(w);
                else if (visLen(cur + " " + w) <= maxCols) cur.append(' ').append(w);
                else {
                    lines.add(cur.toString());
                    cur.setLength(0);
                    cur.append(w);
                }
            }
            if (cur.length() > 0) lines.add(cur.toString());
            for (int l = 0; l < lines.size(); l++) {
                if (l == 1) out.append("\\n");
                else if (l >= 2) out.append(gen3 ? "\\l" : "\\n");
                out.append(lines.get(l));
            }
        }
        return out.toString();
    }
}
