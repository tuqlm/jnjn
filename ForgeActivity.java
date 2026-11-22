package com.retroforge.gba.screen;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;
import com.retroforge.gba.R;
import com.retroforge.gba.engine.LineEntry;
import com.retroforge.gba.engine.MultiLangCodec;
import com.retroforge.gba.engine.MultiTranslator;
import com.retroforge.gba.engine.RomAnalyzer;
import com.retroforge.gba.engine.RomBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class ForgeActivity extends AppCompatActivity {

    private static final int RC_ROM = 301, RC_TBL = 302, RC_SAVE_ROM = 303, RC_SAVE_ZIP = 306, RC_JSON_OUT = 304, RC_JSON_IN = 305;
    private static final String PREF = "retroforge_prefs", KEY_AI = "ai_key";

    private View pageRom, pagePacks, pageTranslate, pageEditor, boxAiKey;
    private TextView tvTopSubtitle, tvLangPill, tvRomDetail, tvRomMsg, tvPackStatus;
    private TextView tvProgMain, tvProgSpeed, tvLogBox, tvEditInfo;
    private Button btnOpenRom, btnOpenTbl, btnExtractRom, btnExportRom, btnExportZip;
    private Button btnPackEn, btnPackJa, btnPackFr, btnPackZh, btnPackRu;
    private Button btnRunTrans, btnCancelTrans, btnJsonOut, btnJsonIn;
    private Spinner spnSourceLang, spnCharset, spnStatusFilter;
    private SwitchMaterial swStripAccents, swShortWords;
    private EditText etLineCols, etGeminiKey, etLimitCount, etSearch;
    private RadioButton rbHybridAi, rbOfflineOnly, rbWifiTurbo, rbAiOnly;
    private ProgressBar pbProgress;
    private LineListAdapter adapter;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MultiTranslator translator = new MultiTranslator();
    private final StringBuilder logs = new StringBuilder();

    private byte[] romBytes;
    private String romName = "game.gba";
    private MultiLangCodec.CustomTbl customTbl;
    private MultiLangCodec.SourceLang activeLang = MultiLangCodec.SourceLang.EN;
    private MultiLangCodec.CharsetProfile activeProfile = MultiLangCodec.CharsetProfile.LATIN1;
    private final List<LineEntry> lines = new ArrayList<>();

    private volatile boolean busy = false;
    private volatile boolean stopFlag = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forge);
        bind();
        setupTabsAndSpinners();
        setupClicks();
        log("RetroForge GBA san sang (Ho tro ROM Anh, Nhat, Phap, Trung, Nga).");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFlag = true;
        translator.closeAll();
        bg.shutdownNow();
    }

    private void bind() {
        pageRom = findViewById(R.id.pageRom);
        pagePacks = findViewById(R.id.pagePacks);
        pageTranslate = findViewById(R.id.pageTranslate);
        pageEditor = findViewById(R.id.pageEditor);
        boxAiKey = findViewById(R.id.boxAiKey);

        tvTopSubtitle = findViewById(R.id.tvTopSubtitle);
        tvLangPill = findViewById(R.id.tvLangPill);
        tvRomDetail = findViewById(R.id.tvRomDetail);
        tvRomMsg = findViewById(R.id.tvRomMsg);
        tvPackStatus = findViewById(R.id.tvPackStatus);
        tvProgMain = findViewById(R.id.tvProgMain);
        tvProgSpeed = findViewById(R.id.tvProgSpeed);
        tvLogBox = findViewById(R.id.tvLogBox);
        tvLogBox.setMovementMethod(new ScrollingMovementMethod());
        tvEditInfo = findViewById(R.id.tvEditInfo);

        btnOpenRom = findViewById(R.id.btnOpenRom);
        btnOpenTbl = findViewById(R.id.btnOpenTbl);
        btnExtractRom = findViewById(R.id.btnExtractRom);
        btnExportRom = findViewById(R.id.btnExportRom);
        btnExportZip = findViewById(R.id.btnExportZip);
        btnPackEn = findViewById(R.id.btnPackEn);
        btnPackJa = findViewById(R.id.btnPackJa);
        btnPackFr = findViewById(R.id.btnPackFr);
        btnPackZh = findViewById(R.id.btnPackZh);
        btnPackRu = findViewById(R.id.btnPackRu);
        btnRunTrans = findViewById(R.id.btnRunTrans);
        btnCancelTrans = findViewById(R.id.btnCancelTrans);
        btnJsonOut = findViewById(R.id.btnJsonOut);
        btnJsonIn = findViewById(R.id.btnJsonIn);

        spnSourceLang = findViewById(R.id.spnSourceLang);
        spnCharset = findViewById(R.id.spnCharset);
        spnStatusFilter = findViewById(R.id.spnStatusFilter);
        swStripAccents = findViewById(R.id.swStripAccents);
        swShortWords = findViewById(R.id.swShortWords);
        etLineCols = findViewById(R.id.etLineCols);
        etGeminiKey = findViewById(R.id.etGeminiKey);
        etLimitCount = findViewById(R.id.etLimitCount);
        etSearch = findViewById(R.id.etSearch);
        rbHybridAi = findViewById(R.id.rbHybridAi);
        rbOfflineOnly = findViewById(R.id.rbOfflineOnly);
        rbWifiTurbo = findViewById(R.id.rbWifiTurbo);
        rbAiOnly = findViewById(R.id.rbAiOnly);
        pbProgress = findViewById(R.id.pbProgress);

        etGeminiKey.setText(getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_AI, ""));

        RecyclerView rv = findViewById(R.id.rvLines);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LineListAdapter(this::showEditPopup);
        rv.setAdapter(adapter);
    }

    private void setupTabsAndSpinners() {
        TabLayout tabs = findViewById(R.id.topTabs);
        tabs.addTab(tabs.newTab().setText("ROM"));
        tabs.addTab(tabs.newTab().setText("Goi Offline"));
        tabs.addTab(tabs.newTab().setText("Dich"));
        tabs.addTab(tabs.newTab().setText("Sua"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab t) {
                int p = t.getPosition();
                pageRom.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
                pagePacks.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
                pageTranslate.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
                pageEditor.setVisibility(p == 3 ? View.VISIBLE : View.GONE);
                if (p == 2) updateProgressUi();
                if (p == 3) refreshEditor();
            }
            @Override public void onTabUnselected(TabLayout.Tab t) {}
            @Override public void onTabReselected(TabLayout.Tab t) {}
        });

        String[] langs = new String[MultiLangCodec.SourceLang.values().length];
        for (int i = 0; i < langs.length; i++) langs[i] = MultiLangCodec.SourceLang.values()[i].label;
        ArrayAdapter<String> la = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, langs);
        la.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnSourceLang.setAdapter(la);

        String[] profiles = new String[MultiLangCodec.CharsetProfile.values().length];
        for (int i = 0; i < profiles.length; i++) profiles[i] = MultiLangCodec.CharsetProfile.values()[i].label;
        ArrayAdapter<String> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profiles);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnCharset.setAdapter(pa);
        spnCharset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                btnOpenTbl.setVisibility(pos == MultiLangCodec.CharsetProfile.CUSTOM_TBL.ordinal() ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> fa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Tat ca", "Da dich", "Chua dich"});
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnStatusFilter.setAdapter(fa);
        spnStatusFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refreshEditor(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        RadioGroup rg = findViewById(R.id.rgMode);
        rg.setOnCheckedChangeListener((g, id) ->
                boxAiKey.setVisibility((id == R.id.rbHybridAi || id == R.id.rbAiOnly) ? View.VISIBLE : View.GONE));
    }

    private void setupClicks() {
        btnOpenRom.setOnClickListener(v -> openPicker(RC_ROM));
        btnOpenTbl.setOnClickListener(v -> openPicker(RC_TBL));
        btnExtractRom.setOnClickListener(v -> runScan());
        btnExportRom.setOnClickListener(v -> {
            if (romBytes == null || lines.isEmpty()) { toast("Hay nap ROM va quet text truoc."); return; }
            openSaver(RC_SAVE_ROM, baseRomName() + "_viethoa.gba", "application/octet-stream");
        });
        btnExportZip.setOnClickListener(v -> {
            if (romBytes == null || lines.isEmpty()) { toast("Hay nap ROM va quet text truoc."); return; }
            openSaver(RC_SAVE_ZIP, baseRomName() + "_viethoa.zip", "application/zip");
        });

        btnPackEn.setOnClickListener(v -> downloadPack(MultiLangCodec.SourceLang.EN));
        btnPackJa.setOnClickListener(v -> downloadPack(MultiLangCodec.SourceLang.JA));
        btnPackFr.setOnClickListener(v -> downloadPack(MultiLangCodec.SourceLang.FR));
        btnPackZh.setOnClickListener(v -> downloadPack(MultiLangCodec.SourceLang.ZH));
        btnPackRu.setOnClickListener(v -> downloadPack(MultiLangCodec.SourceLang.RU));

        btnRunTrans.setOnClickListener(v -> runTranslate());
        btnCancelTrans.setOnClickListener(v -> { stopFlag = true; tvProgSpeed.setText("Dang dung..."); });
        btnJsonOut.setOnClickListener(v -> {
            if (lines.isEmpty()) { toast("Chua co text."); return; }
            openSaver(RC_JSON_OUT, romName.replace(".gba", "") + "_vn.json", "application/json");
        });
        btnJsonIn.setOnClickListener(v -> {
            if (lines.isEmpty()) { toast("Quet ROM truoc khi nhap JSON."); return; }
            openPicker(RC_JSON_IN);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refreshEditor(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void downloadPack(MultiLangCodec.SourceLang lang) {
        setBusy(true);
        tvPackStatus.setText("Dang tai goi " + lang.label + " -> Viet (~30MB)...");
        bg.execute(() -> {
            try {
                translator.downloadPackForPair(lang);
                ui.post(() -> {
                    tvPackStatus.setText("Da tai xong goi " + lang.label + " -> Viet! San sang dich Offline.");
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvPackStatus.setText("Loi tai goi: " + e.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void openPicker(int rc) {
        if (busy) { toast("Dang xu ly..."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, rc);
    }

    private void openSaver(int rc, String name, String mime) {
        if (busy) { toast("Dang xu ly..."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, rc);
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (rc == RC_ROM) loadRom(u);
        else if (rc == RC_TBL) loadTbl(u);
        else if (rc == RC_SAVE_ROM) saveRom(u, false);
        else if (rc == RC_SAVE_ZIP) saveRom(u, true);
        else if (rc == RC_JSON_OUT) saveJson(u);
        else if (rc == RC_JSON_IN) loadJson(u);
    }

    private byte[] readAll(Uri u) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = getContentResolver().openInputStream(u)) {
            if (is == null) throw new Exception("Khong mo duoc file.");
            byte[] b = new byte[65536];
            int n;
            while ((n = is.read(b)) != -1) bos.write(b, 0, n);
        }
        return bos.toByteArray();
    }

    private String getName(Uri u) {
        String s = "rom.gba";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) s = c.getString(i);
            }
        } catch (Exception ignored) {}
        return s;
    }

    private void loadRom(Uri u) {
        setBusy(true);
        tvRomMsg.setText("Dang doc ROM...");
        bg.execute(() -> {
            try {
                String n = getName(u);
                byte[] rawBytes = readAll(u);
                byte[] b = rawBytes;
                boolean fromZip = (rawBytes.length >= 4 && rawBytes[0] == 0x50 && rawBytes[1] == 0x4B && rawBytes[2] == 0x03 && rawBytes[3] == 0x04)
                        || n.toLowerCase(Locale.US).endsWith(".zip");
                if (fromZip) {
                    byte[] extracted = null;
                    String foundGbaName = null;
                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
                        ZipEntry ze;
                        while ((ze = zis.getNextEntry()) != null) {
                            String entryName = ze.getName();
                            if (!ze.isDirectory() && !entryName.startsWith("__MACOSX/")
                                    && !entryName.contains("/._") && entryName.toLowerCase(Locale.US).endsWith(".gba")) {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[65536];
                                int r;
                                while ((r = zis.read(buf)) != -1) bos.write(buf, 0, r);
                                extracted = bos.toByteArray();
                                int slash = entryName.lastIndexOf("/");
                                foundGbaName = (slash >= 0) ? entryName.substring(slash + 1) : entryName;
                                break;
                            }
                        }
                    }
                    if (extracted == null) throw new Exception("Khong tim thay file .gba ben trong file .zip!");
                    b = extracted;
                    n = foundGbaName;
                }
                RomAnalyzer.HeaderInfo h = RomAnalyzer.inspect(b);
                romBytes = b;
                romName = n;
                lines.clear();
                activeLang = h.guessedLang;
                activeProfile = h.guessedProfile;

                String summary = String.format(Locale.US,
                        "File: %s\nHeader: %s [%s] | %.2f MB\nNhan dien: %s | %s\nVung trong 0xFF: %.2f MB",
                        n, h.title, h.gameCode, h.sizeBytes / 1048576.0,
                        h.guessedLang.label, h.guessedProfile.label, h.freeFF / 1048576.0);
                ui.post(() -> {
                    tvTopSubtitle.setText(h.title + " (" + h.gameCode + ")");
                    tvLangPill.setText(h.guessedLang.name());
                    tvRomDetail.setText(summary);
                    tvRomMsg.setText("Da nap ROM. Bam Quet & Trich xuat.");
                    log("[ROM] " + n + " -> " + h.guessedLang.label);
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { tvRomMsg.setText("Loi: " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void loadTbl(Uri u) {
        setBusy(true);
        bg.execute(() -> {
            try {
                customTbl = MultiLangCodec.parseTbl(new String(readAll(u), StandardCharsets.UTF_8));
                ui.post(() -> { tvRomMsg.setText("Da nap .tbl (" + customTbl.enc.size() + " ky tu)."); setBusy(false); });
            } catch (Exception e) {
                ui.post(() -> { tvRomMsg.setText("Loi .tbl: " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void runScan() {
        if (romBytes == null) { toast("Chon ROM .gba truoc."); return; }
        MultiLangCodec.SourceLang reqL = MultiLangCodec.SourceLang.values()[spnSourceLang.getSelectedItemPosition()];
        MultiLangCodec.CharsetProfile reqP = MultiLangCodec.CharsetProfile.values()[spnCharset.getSelectedItemPosition()];
        if (reqP == MultiLangCodec.CharsetProfile.CUSTOM_TBL && customTbl == null) { toast("Nap file .tbl truoc."); return; }
        boolean shortW = swShortWords.isChecked();

        setBusy(true);
        tvRomMsg.setText("Dang quet ROM da ngon ngu...");
        bg.execute(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RomAnalyzer.Extraction ext = RomAnalyzer.extract(romBytes, reqL, reqP, shortW, customTbl);
                lines.clear();
                lines.addAll(ext.lines);
                activeLang = ext.resolvedLang;
                activeProfile = ext.resolvedProfile;
                double sec = (System.currentTimeMillis() - t0) / 1000.0;

                ui.post(() -> {
                    tvLangPill.setText(activeLang.name());
                    tvRomMsg.setText(String.format(Locale.US,
                            "Quet xong: %,d cau (%s - %s) trong %.2fs.",
                            lines.size(), activeLang.label, activeProfile.label, sec));
                    log("[SCAN] " + lines.size() + " cau (" + activeLang.label + ")");
                    updateProgressUi();
                    refreshEditor();
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { tvRomMsg.setText("Loi quet: " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void runTranslate() {
        if (lines.isEmpty()) { toast("Quet ROM o tab 1 truoc."); return; }
        boolean hybrid = rbHybridAi.isChecked();
        boolean offline = rbOfflineOnly.isChecked();
        boolean wifi = rbWifiTurbo.isChecked();
        boolean aiOnly = rbAiOnly.isChecked();
        String apiKey = etGeminiKey.getText().toString().trim();

        if ((hybrid || aiOnly) && apiKey.isEmpty()) {
            toast("Dan Gemini API Key hoac chon Offline / Wi-Fi Turbo.");
            return;
        }
        if (!apiKey.isEmpty()) getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_AI, apiKey).apply();

        boolean noAcc = swStripAccents.isChecked();
        int cols = parseInt(etLineCols.getText().toString(), 34, 10);
        int lim = parseInt(etLimitCount.getText().toString(), 0, 0);

        List<LineEntry> todo = new ArrayList<>();
        for (LineEntry e : lines) if (!e.hasTranslation()) todo.add(e);
        if (todo.isEmpty()) { toast("Da dich het tat ca!"); return; }
        int count = (lim > 0 && lim < todo.size()) ? lim : todo.size();
        List<LineEntry> work = todo.subList(0, count);

        stopFlag = false;
        setBusy(true);
        btnCancelTrans.setEnabled(true);
        log("[DICH] " + count + " cau | Nguon: " + activeLang.label);

        bg.execute(() -> {
            AtomicInteger done = new AtomicInteger(0);
            long t0 = System.currentTimeMillis();
            try {
                if (offline || hybrid) {
                    ui.post(() -> tvProgSpeed.setText("Dang kiem tra/tai goi Offline " + activeLang.label + "..."));
                    translator.downloadPackForPair(activeLang);
                }

                if (offline) {
                    for (LineEntry e : work) {
                        if (stopFlag) break;
                        String vi = translator.translateOffline(e.original, activeLang);
                        e.vietnamese = MultiTranslator.formatOutput(vi, noAcc, e.multiline, cols, activeProfile);
                        tick(done.incrementAndGet(), count, t0, e.vietnamese);
                    }
                } else if (wifi) {
                    ExecutorService pool = Executors.newFixedThreadPool(6);
                    List<Future<?>> fs = new ArrayList<>();
                    for (int i = 0; i < work.size(); i += 25) {
                        List<LineEntry> batch = new ArrayList<>(work.subList(i, Math.min(work.size(), i + 25)));
                        fs.add(pool.submit(() -> {
                            if (stopFlag) return;
                            try {
                                List<String> srcs = new ArrayList<>(batch.size());
                                for (LineEntry it : batch) srcs.add(it.original);
                                List<String> res = translator.translateOnlineBatch(srcs, activeLang);
                                String last = "";
                                for (int k = 0; k < batch.size(); k++) {
                                    LineEntry it = batch.get(k);
                                    it.vietnamese = MultiTranslator.formatOutput(res.get(k), noAcc, it.multiline, cols, activeProfile);
                                    last = it.vietnamese;
                                }
                                tick(done.addAndGet(batch.size()), count, t0, last);
                            } catch (Exception ignored) {}
                        }));
                    }
                    for (Future<?> f : fs) { if (stopFlag) break; f.get(); }
                    pool.shutdownNow();
                } else if (aiOnly) {
                    ExecutorService pool = Executors.newFixedThreadPool(3);
                    List<Future<?>> fs = new ArrayList<>();
                    for (int i = 0; i < work.size(); i += 30) {
                        List<LineEntry> batch = new ArrayList<>(work.subList(i, Math.min(work.size(), i + 30)));
                        fs.add(pool.submit(() -> {
                            if (stopFlag) return;
                            runAiBatch(batch, apiKey, noAcc, cols, done, count, t0);
                        }));
                    }
                    for (Future<?> f : fs) { if (stopFlag) break; f.get(); }
                    pool.shutdownNow();
                } else {
                    // Hybrid Offline + Gemini AI
                    List<LineEntry> shortQ = new ArrayList<>(), longQ = new ArrayList<>();
                    for (LineEntry e : work) {
                        if (!e.multiline && e.original.length() <= 40) shortQ.add(e);
                        else longQ.add(e);
                    }
                    ExecutorService pool = Executors.newFixedThreadPool(3);
                    List<Future<?>> fs = new ArrayList<>();
                    fs.add(pool.submit(() -> {
                        for (LineEntry e : shortQ) {
                            if (stopFlag) break;
                            try {
                                String vi = translator.translateOffline(e.original, activeLang);
                                e.vietnamese = MultiTranslator.formatOutput(vi, noAcc, e.multiline, cols, activeProfile);
                                tick(done.incrementAndGet(), count, t0, e.vietnamese);
                            } catch (Exception ignored) {}
                        }
                    }));
                    for (int i = 0; i < longQ.size(); i += 30) {
                        List<LineEntry> batch = new ArrayList<>(longQ.subList(i, Math.min(longQ.size(), i + 30)));
                        fs.add(pool.submit(() -> {
                            if (stopFlag) return;
                            runAiBatch(batch, apiKey, noAcc, cols, done, count, t0);
                        }));
                    }
                    for (Future<?> f : fs) { if (stopFlag) break; f.get(); }
                    pool.shutdownNow();
                }

                int fin = done.get();
                boolean stopped = stopFlag;
                ui.post(() -> {
                    updateProgressUi();
                    refreshEditor();
                    tvProgSpeed.setText(stopped ? "Da dung tai " + fin + "/" + count : "Dich xong " + fin + " cau! Quay lai tab ROM de xuat.");
                    btnCancelTrans.setEnabled(false);
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvProgSpeed.setText("Loi: " + e.getMessage());
                    btnCancelTrans.setEnabled(false);
                    setBusy(false);
                });
            }
        });
    }

    private void runAiBatch(List<LineEntry> batch, String key, boolean noAcc, int cols,
                            AtomicInteger done, int total, long t0) {
        List<String> srcs = new ArrayList<>(batch.size());
        for (LineEntry it : batch) srcs.add(it.original);
        List<String> res;
        try {
            res = translator.translateGeminiBatch(srcs, activeLang, key);
        } catch (Exception e) {
            try { res = translator.translateOnlineBatch(srcs, activeLang); }
            catch (Exception e2) { res = srcs; }
        }
        String last = "";
        for (int k = 0; k < batch.size(); k++) {
            LineEntry it = batch.get(k);
            it.vietnamese = MultiTranslator.formatOutput(res.get(k), noAcc, it.multiline, cols, activeProfile);
            last = it.vietnamese;
        }
        tick(done.addAndGet(batch.size()), total, t0, last);
    }

    private void tick(int cur, int total, long t0, String sample) {
        if (cur <= 10 || (cur % 25) == 0 || cur >= total) {
            double sec = Math.max(0.2, (System.currentTimeMillis() - t0) / 1000.0);
            double spd = cur / sec;
            double eta = Math.max(0, total - cur) / Math.max(0.5, spd);
            ui.post(() -> {
                updateProgressUi();
                tvProgSpeed.setText(String.format(Locale.US, "%.1f cau/s | Con ~%.0fs (%d/%d)", spd, eta, cur, total));
                if (cur <= 5 || (cur % 150) == 0 || cur >= total) {
                    log("  [" + cur + "/" + total + "] " + (sample.length() > 55 ? sample.substring(0, 55) + "..." : sample));
                }
            });
        }
    }

    private String baseRomName() {
        return romName.replaceAll("(?i)\\.(gba|zip)$", "");
    }

    private void saveRom(Uri u, boolean asZip) {
        setBusy(true);
        tvRomMsg.setText(asZip ? "Dang dong goi ROM vao file .zip..." : "Dang dong goi file .gba...");
        bg.execute(() -> {
            try {
                RomBuilder.PatchSummary s = new RomBuilder.PatchSummary();
                byte[] out = RomBuilder.build(romBytes, lines, activeProfile, customTbl, s);
                try (OutputStream os = getContentResolver().openOutputStream(u)) {
                    if (os == null) throw new Exception("Khong ghi duoc file.");
                    if (asZip) {
                        try (ZipOutputStream zos = new ZipOutputStream(os)) {
                            zos.putNextEntry(new ZipEntry(baseRomName() + "_viethoa.gba"));
                            zos.write(out);
                            zos.closeEntry();
                            zos.finish();
                        }
                    } else {
                        os.write(out);
                        os.flush();
                    }
                }
                ui.post(() -> {
                    tvRomMsg.setText(String.format(Locale.US,
                            "Xuat %s xong! Tai cho: %d | Repoint: %d | Bo qua: %d",
                            asZip ? "ZIP" : "GBA", s.inPlace, s.repointed, s.skipped));
                    toast(asZip ? "Da luu file .zip Viet hoa!" : "Da luu file .gba Viet hoa!");
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { tvRomMsg.setText("Loi xuat ROM: " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void saveJson(Uri u) {
        setBusy(true);
        bg.execute(() -> {
            try {
                String j = RomBuilder.toJson(romName, activeLang, lines);
                try (OutputStream os = getContentResolver().openOutputStream(u)) {
                    if (os == null) throw new Exception("Khong ghi duoc JSON.");
                    os.write(j.getBytes(StandardCharsets.UTF_8));
                }
                ui.post(() -> { toast("Da xuat JSON!"); setBusy(false); });
            } catch (Exception e) {
                ui.post(() -> { log("[LOI JSON] " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void loadJson(Uri u) {
        setBusy(true);
        bg.execute(() -> {
            try {
                int c = RomBuilder.fromJson(new String(readAll(u), StandardCharsets.UTF_8), lines);
                ui.post(() -> {
                    updateProgressUi();
                    refreshEditor();
                    toast("Da nhap " + c + " cau dich!");
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { log("[LOI NHAP] " + e.getMessage()); setBusy(false); });
            }
        });
    }

    private void updateProgressUi() {
        int tot = lines.size(), tr = 0;
        for (LineEntry e : lines) if (e.hasTranslation()) tr++;
        int pct = tot == 0 ? 0 : (int) ((tr * 100L) / tot);
        tvProgMain.setText(String.format(Locale.US, "%,d / %,d cau (%d%%)", tr, tot, pct));
        pbProgress.setProgress(pct);
    }

    private void refreshEditor() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.US);
        int f = spnStatusFilter.getSelectedItemPosition();
        List<LineEntry> out = new ArrayList<>();
        for (LineEntry e : lines) {
            if (f == 1 && !e.hasTranslation()) continue;
            if (f == 2 && e.hasTranslation()) continue;
            if (!q.isEmpty() && !e.original.toLowerCase(Locale.US).contains(q)
                    && !e.vietnamese.toLowerCase(Locale.US).contains(q)) continue;
            out.add(e);
        }
        adapter.setItems(out);
        tvEditInfo.setText(String.format(Locale.US, "Hien thi %,d / %,d cau", out.size(), lines.size()));
    }

    private void showEditPopup(LineEntry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setText("Goc (" + activeLang.name() + " | 0x" + Integer.toHexString(e.offset).toUpperCase(Locale.US) + "):\n" + e.original);
        box.addView(tv);

        EditText et = new EditText(this);
        et.setText(e.vietnamese);
        et.setMinLines(3);
        box.addView(et);

        CheckBox chk = new CheckBox(this);
        chk.setText("Tu dong ngat dong GBA");
        chk.setChecked(true);
        box.addView(chk);

        new AlertDialog.Builder(this)
                .setTitle("Sua cau #" + e.id)
                .setView(box)
                .setPositiveButton("Luu", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (!v.isEmpty() && chk.isChecked()) {
                        v = MultiTranslator.formatOutput(v, swStripAccents.isChecked(), e.multiline,
                                parseInt(etLineCols.getText().toString(), 34, 10), activeProfile);
                    }
                    e.vietnamese = v;
                    updateProgressUi();
                    refreshEditor();
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void setBusy(boolean b) {
        busy = b;
        btnOpenRom.setEnabled(!b); btnOpenTbl.setEnabled(!b); btnExtractRom.setEnabled(!b); btnExportRom.setEnabled(!b); btnExportZip.setEnabled(!b);
        btnPackEn.setEnabled(!b); btnPackJa.setEnabled(!b); btnPackFr.setEnabled(!b); btnPackZh.setEnabled(!b); btnPackRu.setEnabled(!b);
        btnRunTrans.setEnabled(!b); btnJsonOut.setEnabled(!b); btnJsonIn.setEnabled(!b);
    }

    private int parseInt(String s, int def, int min) {
        try { return Math.max(Integer.parseInt(s.trim()), min); } catch (Exception e) { return def; }
    }

    private void log(String s) {
        if (logs.length() > 12000) logs.delete(0, 6000);
        logs.append(s).append("\n");
        tvLogBox.setText(logs.toString());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
