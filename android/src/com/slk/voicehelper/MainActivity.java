package com.slk.voicehelper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    static final String TAG = "QQVoiceApp";
    /** 覆盖：标准路径 / emulated/0 / 分身(999) / 国际版、极速版包名 */
    static final String[] QQ_ROOTS = {
            "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.mobileqqi/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.qqlite/Tencent/MobileQQ",
    };
    static final String BACKUP_ROOT = "/sdcard/Download/QQ语音备份";

    WebView web;
    android.content.SharedPreferences prefs;
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

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
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

        Shizuku.addBinderReceivedListenerSticky(() -> notifyJs());
        Shizuku.addBinderDeadListener(this::notifyJs);

        Shizuku.addRequestPermissionResultListener((code, result) -> {
            boolean ok = result == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "permission result=" + ok);
            runOnUiThread(() -> Toast.makeText(this, ok ? "Shizuku 已授权" : "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show());
        });

        try {
            server = new LocalServer(getAssets(), m -> Log.w(TAG, m));
            int port = server.startServer();
            web.loadUrl("http://127.0.0.1:" + port + "/index.html");
        } catch (Exception e) {
            Log.e(TAG, "server", e);
            Toast.makeText(this, "启动失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    /** Shizuku 服务上线/掉线时，让页面自己重查一次状态 */
    void notifyJs() {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.onShizukuChanged && window.onShizukuChanged()", null);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            Uri[] uris = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
                Uri u = data.getData();
                String nm = "";
                long sz = -1;
                try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        int j = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (i >= 0) nm = c.getString(i);
                        if (j >= 0) sz = c.getLong(j);
                    }
                } catch (Exception ignored) { }
                Log.i(TAG, "picked uri=" + u + " name=" + nm + " size=" + sz);
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

    /* ---------------- Shizuku 状态 / 目录 ---------------- */

    boolean shizukuReady() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    /** 从任意层级的手动路径里定位到真正含 slk 的目录 */
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
                Log.i(TAG, "manual dir -> " + pttDir);
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
                Log.i(TAG, "manual dir -> " + pttDir);
                return;
            }
        }
        Log.w(TAG, "手动目录里没找到 slk：" + p);
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
                // 标准三层布局优先，找不到就递归找
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
        if (pttDir != null) Log.i(TAG, "resolveDir -> uin=" + uin + " dir=" + pttDir);
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
                        Toast.makeText(MainActivity.this, "Shizuku 服务没在运行，请先在 Shizuku 里启动", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Shizuku.requestPermission(0);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "请求失败：" + t, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String list() {
            if (!shizukuReady()) return "{\"ok\":false,\"error\":\"未授权\"}";
            // 每次都重新定位：QQ 按天新建目录（202610/19 → 202610/20）
            pttDir = null;
            uin = null;
            resolveDir();
            if (pttDir == null) return "{\"ok\":false,\"error\":\"没找到 QQ 语音目录，确认 QQ 有语音记录\"}";

            String pttRoot = pttDir.contains("/ptt/")
                    ? pttDir.substring(0, pttDir.indexOf("/ptt/") + 4)
                    : pttDir;
            try {
                // 按层降级取，避免把根目录下 MD5 命名的"已发/已收缓存"混进来：
                // ① ptt/YYYYMM/DD/*.slk  ② ptt/YYYYMM/*.slk  ③ ptt/*.slk（平铺兜底）
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
                    if (!rows.isEmpty()) break;   // 有内容就不再往上层回退
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
                String js = sb.toString();
                Log.i(TAG, "list root=" + pttRoot + " rows=" + rows.size() + " shown=" + shown);
                return js;
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
            // 只允许写 QQ 语音目录下，防止被传入任意路径
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
                    return "{\"ok\":false,\"error\":\"写入后大小不一致：" + real + " vs " + data.length + "\"}";
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
            MainActivity.this.uin = null;
            durCache.clear();
            return true;
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
