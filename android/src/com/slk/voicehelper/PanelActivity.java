package com.slk.voicehelper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * 面板。整个 Activity 是透明的，只有中间一块卡片可见；
 * 点卡片外面任何地方 = 关掉。
 */
public class PanelActivity extends Activity {

    static final String TAG = "QQVoicePanel";

    static final String[] QQ_ROOTS = {
            "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.mobileqqi/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.qqlite/Tencent/MobileQQ",
    };
    static final String BACKUP_ROOT = "/sdcard/Download/QQ语音备份";

    WebView web;
    FrameLayout root;
    SharedPreferences prefs;
    String cfgUin = "", cfgDir = "";
    LocalServer server;
    String pttDir;
    String uin;
    ValueCallback<Uri[]> fileCallback;
    static final int REQ_FILE = 1001;

    final Map<String, Double> durCache = new HashMap<>();
    final java.util.Set<String> deadRoots = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        cfgUin = prefs.getString("uin", "");
        cfgDir = prefs.getString("dir", "");

        // 透明背景
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.dimAmount = 0.32f;
        getWindow().setAttributes(wlp);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnClickListener(v -> finish());   // 点外面关掉

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        // 消费掉点击，避免穿透到 root 导致误关
        web.setOnTouchListener((v, e) -> false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            /** WebView 默认不显示 alert/confirm，必须自己接 */
            @Override
            public boolean onJsAlert(WebView v, String url, String msg,
                                     android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(PanelActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("好", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg,
                                       android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(PanelActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("确定", (d, w) -> r.confirm())
                        .setNegativeButton("取消", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择音频"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");

        Shizuku.addRequestPermissionResultListener((code, result) -> {
            boolean ok = result == PackageManager.PERMISSION_GRANTED;
            runOnUiThread(() -> Toast.makeText(this, ok ? "Shizuku 已授权" : "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show());
        });

        // 卡片：WebView 铺满整个 Activity，具体布局交给 HTML 控制
        // （WRAP_CONTENT 的 WebView 高度会塌成 0，这是之前面板空白的原因）
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        root.addView(web, lp);
        setContentView(root);

        try {
            server = new LocalServer(getAssets(), m -> Log.w(TAG, m));
            int port = server.startServer();
            web.loadUrl("http://127.0.0.1:" + port + "/index.html");
        } catch (Exception e) {
            Log.e(TAG, "server", e);
            Toast.makeText(this, "启动失败：" + e, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            Uri[] uris = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
            if (fileCallback != null) fileCallback.onReceiveValue(uris);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stopServer();
        super.onDestroy();
    }

    /* ---------------- Shizuku / 目录 ---------------- */

    boolean shizukuReady() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    void resolveFromPath(String p) {
        p = p.trim();
        if (p.isEmpty()) return;
        if (p.startsWith("primary:")) p = p.substring(8);
        if (!p.startsWith("/")) p = "/sdcard/" + p;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        String[] cands = { p, p + "/ptt" };
        for (String c : cands) {
            if (!Sh.execSafe("ls " + c + "/*.slk 2>/dev/null | head -1").isEmpty()) {
                pttDir = c;
                uin = guessUin(c);
                return;
            }
            String latest = Sh.execSafe("ls -1t " + c + "/*/*.slk 2>/dev/null | head -1");
            if (latest.isEmpty()) {
                latest = Sh.execSafe("find " + c + " -type f -name '*.slk' 2>/dev/null | head -1");
            }
            if (!latest.isEmpty()) {
                pttDir = latest.split("\n")[0].trim();
                pttDir = pttDir.substring(0, pttDir.lastIndexOf('/'));
                uin = guessUin(pttDir);
                return;
            }
        }
    }

    static String guessUin(String path) {
        for (String seg : path.split("/")) {
            if (seg.matches("\\d{4,12}")) return seg;
        }
        return "";
    }

    void resolveDir() {
        if (pttDir != null) return;
        if (cfgDir != null && !cfgDir.isEmpty()) {
            resolveFromPath(cfgDir);
            if (pttDir != null) return;
        }
        long bestT = -1;
        String bestDir = null, bestUin = null;
        for (String root : QQ_ROOTS) {
            if (deadRoots.contains(root)) continue;
            String ls = Sh.execSafe("ls -1 " + root + " 2>/dev/null");
            if (ls.isEmpty()) { deadRoots.add(root); continue; }
            for (String u : ls.split("\n")) {
                u = u.trim();
                if (!u.matches("\\d{4,12}")) continue;
                if (cfgUin != null && !cfgUin.isEmpty() && !u.equals(cfgUin)) continue;
                String ptt = root + "/" + u + "/ptt";
                if (Sh.execSafe("ls -d " + ptt + " 2>/dev/null").isEmpty()) continue;
                String latest = Sh.execSafe("ls -1t " + ptt + "/*/*/*.slk 2>/dev/null | head -1");
                if (latest.isEmpty()) {
                    latest = Sh.execSafe("find " + ptt + " -type f -name '*.slk' 2>/dev/null | head -1");
                }
                if (latest.isEmpty()) continue;
                latest = latest.split("\n")[0].trim();
                String ts = Sh.execSafe("stat -c %Y '" + latest + "' 2>/dev/null").replaceAll("[^0-9]", "");
                long t = 0;
                try { if (!ts.isEmpty()) t = Long.parseLong(ts); } catch (Exception ignored) { }
                if (t > bestT) {
                    bestT = t;
                    bestDir = latest.substring(0, latest.lastIndexOf('/'));
                    bestUin = u;
                }
            }
        }
        pttDir = bestDir;
        uin = bestUin;
    }

    double durationOf(String path, long size) {
        String key = path + "|" + size;
        if (durCache.containsKey(key)) return durCache.get(key);
        double sec = -1;
        try {
            byte[] d = Sh.read(path);
            if (d.length > 10 && d[0] == 0x02 && new String(d, 1, 9, "ISO-8859-1").equals("#!SILK_V3")) {
                int i = 10, frames = 0;
                while (i + 2 <= d.length) {
                    int n = (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8);
                    i += 2;
                    if (n == 0 || n == 0xffff || i + n > d.length) break;
                    frames++;
                    i += n;
                }
                if (frames > 0) sec = frames * 0.02;
            }
        } catch (Exception e) {
            Log.w(TAG, "dur " + e);
        }
        durCache.put(key, sec);
        return sec;
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ---------------- JS 桥 ---------------- */

    class Bridge {

        @JavascriptInterface
        public String status() {
            boolean up = false, perm = false;
            try {
                up = Shizuku.pingBinder();
                perm = up && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) { }
            if (perm) resolveDir();
            String dir = pttDir == null ? "" : pttDir;
            String shown = dir.isEmpty() ? "" : dir.substring(dir.lastIndexOf("/ptt/") + 5);
            return "{\"shizuku\":" + up + ",\"perm\":" + perm +
                    ",\"uin\":\"" + (uin == null ? "" : uin) + "\"" +
                    ",\"dir\":\"" + esc(shown) + "\"" +
                    ",\"backupRoot\":\"" + esc(BACKUP_ROOT) + "\"}";
        }

        @JavascriptInterface
        public void requestPerm() {
            runOnUiThread(() -> {
                try {
                    if (!Shizuku.pingBinder()) {
                        Toast.makeText(PanelActivity.this, "Shizuku 服务没在运行", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Shizuku.requestPermission(0);
                } catch (Throwable t) {
                    Toast.makeText(PanelActivity.this, "请求失败：" + t, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String list() {
            if (!shizukuReady()) return "{\"ok\":false,\"error\":\"未授权\"}";
            pttDir = null;
            uin = null;
            resolveDir();
            if (pttDir == null) return "{\"ok\":false,\"error\":\"没找到 QQ 语音目录\"}";

            String pttRoot = pttDir.contains("/ptt/")
                    ? pttDir.substring(0, pttDir.indexOf("/ptt/") + 4)
                    : pttDir;
            try {
                String[] globs = {
                        pttRoot + "/*/*/*.slk",
                        pttRoot + "/*/*.slk",
                        pttRoot + "/*.slk",
                };
                List<String[]> rows = new ArrayList<>();
                for (String g : globs) {
                    String out = Sh.exec("for f in " + g + "; do stat -c '%n|%s|%Y' \"$f\" 2>/dev/null; done");
                    for (String line : out.split("\n")) {
                        String[] p = line.trim().split("\\|");
                        if (p.length < 3) continue;
                        if (!p[0].endsWith(".slk")) continue;
                        rows.add(p);
                    }
                    if (!rows.isEmpty()) break;
                }
                rows.sort((a, b) -> {
                    long ta = 0, tb = 0;
                    try { ta = Long.parseLong(a[2].trim()); } catch (Exception ignored) { }
                    try { tb = Long.parseLong(b[2].trim()); } catch (Exception ignored) { }
                    return Long.compare(tb, ta);
                });

                StringBuilder sb = new StringBuilder("{\"ok\":true,\"dir\":\"" + esc(pttDir) + "\",\"files\":[");
                int shown = 0;
                for (String[] r : rows) {
                    String path = r[0];
                    int sl = path.lastIndexOf('/');
                    String name = path.substring(sl + 1);
                    String dir = path.substring(0, sl);
                    long size = 0;
                    try { size = Long.parseLong(r[1].trim()); } catch (Exception ignored) { }
                    double dur = shown < 8 ? durationOf(path, size) : -1;
                    if (shown > 0) sb.append(',');
                    sb.append("{\"name\":\"").append(esc(name)).append("\",\"dir\":\"").append(esc(dir))
                      .append("\",\"size\":").append(size).append(",\"dur\":").append(dur).append("}");
                    shown++;
                    if (shown >= 40) break;
                }
                sb.append("]}");
                return sb.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
            }
        }

        @JavascriptInterface
        public String save(String name, String dir, String b64, boolean backup) {
            if (!shizukuReady()) return "{\"ok\":false,\"error\":\"未授权\"}";
            resolveDir();
            if (pttDir == null) return "{\"ok\":false,\"error\":\"目录未就绪\"}";
            if (!name.endsWith(".slk") || name.contains("/") || name.contains(".."))
                return "{\"ok\":false,\"error\":\"文件名不合法\"}";
            boolean okDir = dir != null && dir.startsWith("/") && !dir.contains("..")
                    && (dir.contains("/ptt/") || dir.endsWith("/ptt"));
            if (!okDir) return "{\"ok\":false,\"error\":\"目标目录不合法\"}";
            try {
                byte[] data = Base64.getDecoder().decode(b64);
                String target = dir + "/" + name;
                String backupPath = "";
                if (backup) {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
                    String bkDir = BACKUP_ROOT + "/" + stamp;
                    Sh.exec("mkdir -p '" + bkDir + "' && cp -f '" + target + "' '" + bkDir + "/" + name + "' 2>/dev/null");
                    backupPath = bkDir + "/" + name;
                }
                String err = Sh.exec("cat > '" + target + "'", data);
                if (!err.isEmpty()) return "{\"ok\":false,\"error\":\"" + esc(err) + "\"}";
                String sizeOut = Sh.exec("wc -c < '" + target + "'").trim();
                long real = Long.parseLong(sizeOut.replaceAll("[^0-9]", ""));
                if (real != data.length)
                    return "{\"ok\":false,\"error\":\"写入后大小不一致\"}";
                durCache.clear();
                return "{\"ok\":true,\"size\":" + real + ",\"backup\":\"" + esc(backupPath) + "\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
            }
        }

        @JavascriptInterface
        public String getConfig() {
            return "{\"uin\":\"" + esc(cfgUin) + "\",\"dir\":\"" + esc(cfgDir) + "\",\"active\":\"" +
                    (pttDir == null ? "" : esc(pttDir)) + "\"}";
        }

        @JavascriptInterface
        public boolean setConfig(String uin, String dir) {
            cfgUin = uin == null ? "" : uin.trim();
            cfgDir = dir == null ? "" : dir.trim();
            prefs.edit().putString("uin", cfgUin).putString("dir", cfgDir).apply();
            pttDir = null;
            PanelActivity.this.uin = null;
            durCache.clear();
            return true;
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(PanelActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        /** 面板自己请求关闭 */
        @JavascriptInterface
        public void close() {
            runOnUiThread(PanelActivity.this::finish);
        }

        /** 从球里隐藏自己 */
        @JavascriptInterface
        public void hideBall() {
            runOnUiThread(() -> {
                Intent i = new Intent(PanelActivity.this, FloatService.class);
                i.setAction(FloatService.ACTION_HIDE);
                PanelActivity.this.stopService(i);
                PanelActivity.this.finish();
            });
        }
    }
}