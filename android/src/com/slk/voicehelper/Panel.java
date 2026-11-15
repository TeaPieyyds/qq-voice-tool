package com.slk.voicehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
 * 面板窗口：和悬浮球一样的 overlay 窗口（不是 Activity）。
 *
 * 结构：
 *   WindowManager(APPLICATION_OVERLAY)
 *     └ FrameLayout  ← 卡片
 *         ├ LinearLayout 顶栏（HTML 画的）…… 其实整块都是 WebView
 *         └ WebView      ← 全部 UI
 *
 * 拖动和缩放由顶部 dragBar / 右下角 手势区处理，都在 WebView 里用 JS 派发。
 */
final class Panel {

    static final String TAG = "QQVoicePanel";

    static final String[] QQ_ROOTS = {
            "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.mobileqqi/Tencent/MobileQQ",
            "/sdcard/Android/data/com.tencent.qqlite/Tencent/MobileQQ",
    };
    static final String BACKUP_ROOT = "/sdcard/Download/QQ语音备份";

    static final int MIN_W_DP = 240;
    static final int MIN_H_DP = 200;
    static final int DEF_W_DP = 340;
    static final int DEF_H_DP = 420;

    private final FloatService svc;
    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;

    private FrameLayout card;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private boolean shown;

    int screenW, screenH;
    int cardW, cardH;

    LocalServer server;
    String pttDir;
    String uin;
    String cfgUin = "", cfgDir = "";

    final Map<String, Double> durCache = new HashMap<>();
    final java.util.Set<String> deadRoots = new java.util.HashSet<>();

    Panel(FloatService svc) {
        this.svc = svc;
        this.ctx = svc.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        cfgUin = prefs.getString("uin", "");
        cfgDir = prefs.getString("dir", "");
        cardW = prefs.getInt("w", 0);
        cardH = prefs.getInt("h", 0);
    }

    /* ================= 显示 / 隐藏 ================= */

    boolean isShown() { return shown; }

    void toggle() { if (shown) hide(); else show(); }

    void show() {
        if (shown) return;

        if (card == null) build();

        if (lp == null || !shown) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;

            int w = cardW > 0 ? cardW : Math.min(dp(DEF_W_DP), screenW - dp(24));
            int h = cardH > 0 ? cardH : Math.min(dp(DEF_H_DP), (int) (screenH * 0.7));
            clampSize(w, h);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(w, h, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // 关键：用 TOP|START + 绝对 x/y，而不是 CENTER
            // CENTER 会让缩放时原点漂移，导致越拉越小
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = prefs.getInt("x", dp(12));
            lp.y = prefs.getInt("y", dp(120));
            clampPos();
        }

        try {
            wm.addView(card, lp);
            shown = true;
            Log.i(TAG, "面板已显示 " + lp.width + "x" + lp.height + " @ " + lp.x + "," + lp.y);
            pushState();
            drainPending();
        } catch (Exception e) {
            Log.e(TAG, "addView 失败", e);
            Toast.makeText(ctx, "面板打不开：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void hide() {
        if (!shown || card == null) return;
        try { wm.removeView(card); } catch (Exception ignored) { }
        shown = false;
        saveGeom();
        Log.i(TAG, "面板已隐藏");
    }

    void destroy() {
        hide();
        if (server != null) { server.stopServer(); server = null; }
        if (web != null) {
            try { web.destroy(); } catch (Exception ignored) { }
            web = null;
        }
        card = null;
    }

    /* ================= 构建 ================= */

    private void build() {
        card = new FrameLayout(ctx);
        card.setBackgroundColor(Color.TRANSPARENT);

        web = new WebView(ctx);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);      // 音频走 /picked 同源路由，不需要 content://

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String u, String m, android.webkit.JsResult r) {
                Toast.makeText(ctx, m, Toast.LENGTH_LONG).show();
                r.confirm();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView v, String u, String m, android.webkit.JsResult r) {
                r.confirm();
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        card.addView(web, flp);

        // 原生拖拽条：只用屏幕绝对坐标，不受窗口移动影响（不走 JS，避免漂移）
        View dragBar = new View(ctx);
        dragBar.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22));
        dlp.gravity = Gravity.TOP;
        dragBar.setLayoutParams(dlp);
        dragBar.setOnTouchListener(this::onDragTouch);
        card.addView(dragBar);

        // 原生缩放手柄：右下角
        View grip = new View(ctx);
        grip.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(28), dp(28));
        glp.gravity = Gravity.BOTTOM | Gravity.END;
        grip.setLayoutParams(glp);
        grip.setOnTouchListener(this::onGripTouch);
        card.addView(grip);

        // 不做「点外面关窗」：选文件时系统选择器会让 overlay 收到 ACTION_OUTSIDE，
        // 一关就选不到文件了。关窗统一由顶栏 X 按钮负责。

        try {
            if (server == null) {
                server = new LocalServer(ctx.getAssets(), m -> Log.w(TAG, m));
                // 音频落盘到私有目录：历史条目重启后还能读回来
                server.setAudioDir(new java.io.File(ctx.getFilesDir(), "audio"));
                int port = server.startServer();
                web.loadUrl("http://127.0.0.1:" + port + "/index.html");
                Log.i(TAG, "本地服务 127.0.0.1:" + port);
            }
        } catch (Exception e) {
            Log.e(TAG, "server", e);
        }
    }

    /* ================= 尺寸 / 位置 ================= */

    private void clampSize(int w, int h) {
        if (w < dp(MIN_W_DP)) w = dp(MIN_W_DP);
        if (h < dp(MIN_H_DP)) h = dp(MIN_H_DP);
        if (screenW > 0 && w > screenW) w = screenW;
        if (screenH > 0 && h > screenH) h = screenH;
        cardW = w; cardH = h;
    }

    private void clampPos() {
        if (lp == null) return;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
        if (screenW > 0 && lp.x + lp.width > screenW) lp.x = screenW - lp.width;
        if (screenH > 0 && lp.y + lp.height > screenH) lp.y = screenH - lp.height;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
    }

    /* ---- 原生拖拽：只用 getRawX/getRawY（屏幕绝对坐标） ---- */
    private float dragDownRawX, dragDownRawY;
    private int dragDownLpX, dragDownLpY;

    private boolean onDragTouch(View v, MotionEvent e) {
        if (lp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownRawX = e.getRawX();
                dragDownRawY = e.getRawY();
                dragDownLpX = lp.x;
                dragDownLpY = lp.y;
                return true;

            case MotionEvent.ACTION_MOVE: {
                // 关键：以「按下时的窗口位置」为基准 + 屏幕绝对位移
                // 不用 e.getX()（那是相对本 View 的，窗口一移动就漂）
                float dx = e.getRawX() - dragDownRawX;
                float dy = e.getRawY() - dragDownRawY;
                lp.x = dragDownLpX + Math.round(dx);
                lp.y = dragDownLpY + Math.round(dy);
                clampPos();
                try { wm.updateViewLayout(card, lp); } catch (Exception ignored) { }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                saveGeom();
                return true;
        }
        return false;
    }

    /* ---- 原生缩放：同样用绝对坐标 + 基准尺寸 ---- */
    private float gripDownRawX, gripDownRawY;
    private int gripDownW, gripDownH;

    private boolean onGripTouch(View v, MotionEvent e) {
        if (lp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gripDownRawX = e.getRawX();
                gripDownRawY = e.getRawY();
                gripDownW = lp.width;
                gripDownH = lp.height;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - gripDownRawX;
                float dy = e.getRawY() - gripDownRawY;
                int w = gripDownW + Math.round(dx);
                int h = gripDownH + Math.round(dy);
                clampSize(w, h);
                lp.width = cardW;
                lp.height = cardH;
                clampPos();
                try { wm.updateViewLayout(card, lp); } catch (Exception ignored) { }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                saveGeom();
                return true;
        }
        return false;
    }

    /** 拖动窗口（顶部条调用），传的是像素增量 */
    void moveBy(int dx, int dy) {
        if (lp == null || !shown) return;
        lp.x += dx;
        lp.y += dy;
        clampPos();
        try { wm.updateViewLayout(card, lp); } catch (Exception ignored) { }
    }

    /** 缩放（右下角调用），传的是像素增量，相对按下时的快照 */
    void resizeTo(int w, int h) {
        if (lp == null || !shown) return;
        clampSize(w, h);
        lp.width = cardW;
        lp.height = cardH;
        clampPos();
        try { wm.updateViewLayout(card, lp); } catch (Exception ignored) { }
    }

    /** 把 PickActivity 暂存的音频取过来（Activity/Service 时序错开时兜底） */
    void drainPending() {
        byte[] b = PickActivity.pendingBytes;
        String n = PickActivity.pendingName;
        if (b != null && b.length > 0) {
            PickActivity.pendingBytes = null;
            PickActivity.pendingName = null;
            Log.i(TAG, "取到暂存音频 " + n + " " + b.length + " 字节");
            setPicked(b, n);
        }
    }

    /** PickActivity 选完文件后调这里 */
    void setPicked(byte[] bytes, String name) {
        if (server == null) {
            Log.w(TAG, "server 未启动，丢弃音频");
            return;
        }
        server.setPicked(bytes, name);
        if (web != null) {
            final String js = "window.onAudioPicked && window.onAudioPicked("
                    + jsStr(name) + "," + bytes.length + ")";
            web.post(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) { } });
        }
    }

    void saveGeom() {
        if (lp == null) return;
        prefs.edit()
                .putInt("w", lp.width)
                .putInt("h", lp.height)
                .putInt("x", lp.x)
                .putInt("y", lp.y)
                .apply();
        cardW = lp.width;
        cardH = lp.height;
    }

    private void pushState() {
        if (web == null) return;
        final String js = "window.onPanelState && window.onPanelState("
                + lp.width + "," + lp.height + ")";
        web.post(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) { } });
    }

    int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /* ================= Shizuku / 目录 ================= */

    boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
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
                pttDir = c; uin = guessUin(c); return;
            }
            String latest = Sh.execSafe("ls -1t " + c + "/*/*.slk 2>/dev/null | head -1");
            if (latest.isEmpty()) {
                latest = Sh.execSafe("find " + c + " -type f -name '*.slk' 2>/dev/null | head -1");
            }
            if (!latest.isEmpty()) {
                String line = latest.split("\n")[0].trim();
                pttDir = line.substring(0, line.lastIndexOf('/'));
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
            if (d.length > 10 && d[0] == 0x02
                    && new String(d, 1, 9, "ISO-8859-1").equals("#!SILK_V3")) {
                int i = 10, frames = 0;
                while (i + 2 <= d.length) {
                    int n = (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8);
                    i += 2;
                    if (n == 0 || n == 0xffff || i + n > d.length) break;
                    frames++; i += n;
                }
                if (frames > 0) sec = frames * 0.02;
            }
        } catch (Exception e) {
            Log.w(TAG, "dur " + e);
        }
        durCache.put(key, sec);
        return sec;
    }

    /* ================= JS 桥 ================= */

    /* 避开转义字符 */
    private static final String Q   = String.valueOf((char) 34);
    private static final String QS  = String.valueOf((char) 92) + String.valueOf((char) 34);
    private static final String BS2 = String.valueOf((char) 92) + String.valueOf((char) 92);
    private static final String NLS = String.valueOf((char) 92) + String.valueOf((char) 110);
    private static final String CRS = String.valueOf((char) 92) + String.valueOf((char) 114);
    private static final char   NL  = 10;
    private static final char   CR  = 13;

    static String jsStr(String s) {
        if (s == null) return Q + Q;
        StringBuilder b = new StringBuilder();
        b.append(Q);
        for (char c : s.toCharArray()) {
            if (c == 34) b.append(QS);
            else if (c == 92) b.append(BS2);
            else if (c == NL) b.append(NLS);
            else if (c == CR) b.append(CRS);
            else b.append(c);
        }
        return b.append(Q).toString();
    }

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
            String shownDir = dir.isEmpty() ? "" : dir.substring(dir.lastIndexOf("/ptt/") + 5);
            return "{" + Q + "shizuku" + Q + ":" + up
                    + "," + Q + "perm" + Q + ":" + perm
                    + "," + Q + "uin" + Q + ":" + Q + esc(uin == null ? "" : uin) + Q
                    + "," + Q + "dir" + Q + ":" + Q + esc(shownDir) + Q
                    + "}";
        }

        @JavascriptInterface
        public void requestPerm() {
            svc.runOnUi(() -> {
                try {
                    if (!Shizuku.pingBinder()) {
                        Toast.makeText(ctx, "Shizuku 服务没在运行", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Shizuku.requestPermission(0);
                } catch (Throwable t) {
                    Toast.makeText(ctx, "请求失败：" + t, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String list() {
            if (!shizukuReady()) return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "未授权" + Q + "}";
            pttDir = null; uin = null;
            resolveDir();
            if (pttDir == null) {
                return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "没找到 QQ 语音目录" + Q + "}";
            }
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
                    String out = Sh.exec("for f in " + g + "; do stat -c '%n|%s|%Y' \"" + "$f" + "\" 2>/dev/null; done");
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

                StringBuilder sb = new StringBuilder();
                sb.append("{").append(Q).append("ok").append(Q).append(":true,")
                        .append(Q).append("dir").append(Q).append(":").append(Q).append(esc(pttDir)).append(Q)
                        .append(",").append(Q).append("files").append(Q).append(":[");
                int shownCount = 0;
                for (String[] r : rows) {
                    String path = r[0];
                    int sl = path.lastIndexOf('/');
                    String name = path.substring(sl + 1);
                    String dir = path.substring(0, sl);
                    long size = 0;
                    try { size = Long.parseLong(r[1].trim()); } catch (Exception ignored) { }
                    double dur = shownCount < 4 ? durationOf(path, size) : -1;
                    if (shownCount > 0) sb.append(",");
                    sb.append("{").append(Q).append("name").append(Q).append(":").append(Q).append(esc(name)).append(Q)
                            .append(",").append(Q).append("dir").append(Q).append(":").append(Q).append(esc(dir)).append(Q)
                            .append(",").append(Q).append("size").append(Q).append(":").append(size)
                            .append(",").append(Q).append("dur").append(Q).append(":").append(dur)
                            .append("}");
                    shownCount++;
                    if (shownCount >= 6) break;
                }
                sb.append("]}");
                return sb.toString();
            } catch (Exception e) {
                return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + esc(String.valueOf(e)) + Q + "}";
            }
        }

        @JavascriptInterface
        public String save(String name, String dir, String b64, boolean backup) {
            if (!shizukuReady()) return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "未授权" + Q + "}";
            resolveDir();
            if (pttDir == null) return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "目录未就绪" + Q + "}";
            if (name == null || !name.endsWith(".slk") || name.contains("/") || name.contains("..")) {
                return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "文件名不合法" + Q + "}";
            }
            boolean okDir = dir != null && dir.startsWith("/") && !dir.contains("..")
                    && (dir.contains("/ptt/") || dir.endsWith("/ptt"));
            if (!okDir) return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "目标目录不合法" + Q + "}";
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
                if (!err.isEmpty()) return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + esc(err) + Q + "}";
                String sizeOut = Sh.exec("wc -c < '" + target + "'").trim();
                long real = Long.parseLong(sizeOut.replaceAll("[^0-9]", ""));
                if (real != data.length) {
                    return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + "写入后大小不一致" + Q + "}";
                }
                durCache.clear();
                return "{" + Q + "ok" + Q + ":true," + Q + "size" + Q + ":" + real
                        + "," + Q + "backup" + Q + ":" + Q + esc(backupPath) + Q + "}";
            } catch (Exception e) {
                return "{" + Q + "ok" + Q + ":false," + Q + "error" + Q + ":" + Q + esc(String.valueOf(e)) + Q + "}";
            }
        }

        /* ---------- 持久化：用 SharedPreferences，不靠 localStorage ----------
           localStorage 按 origin 存，本地服务端口每次重启都变，origin 一变数据全没。 */

        @JavascriptInterface
        public String loadState() {
            return "{" + Q + "pick" + Q + ":" + Q + esc(prefs.getString("pick", "")) + Q
                    + "," + Q + "hist" + Q + ":" + Q + esc(prefs.getString("hist", "[]")) + Q
                    + "," + Q + "uin" + Q + ":" + Q + esc(prefs.getString("qq_uin", "3911006002")) + Q
                    + "}";
        }

        @JavascriptInterface
        public void saveState(String pickJson, String histJson, String uin) {
            prefs.edit()
                    .putString("pick", pickJson == null ? "" : pickJson)
                    .putString("hist", histJson == null ? "[]" : histJson)
                    .putString("qq_uin", uin == null ? "3911006002" : uin)
                    .apply();
        }

        @JavascriptInterface
        public void setUin(String u) {
            cfgUin = u == null ? "" : u.trim();
            cfgDir = "";
            prefs.edit().putString("uin", cfgUin).putString("dir", "").apply();
            pttDir = null; uin = null;
            durCache.clear();
        }

        /** 打开文件选择器（走 FloatService 里的中转 Activity） */
        @JavascriptInterface
        public void pickAudio() {
            svc.runOnUi(() -> {
                try {
                    android.content.Intent i = new android.content.Intent(ctx, PickActivity.class);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(ctx, "打不开选择器：" + e, Toast.LENGTH_LONG).show();
                }
            });
        }

        /* ---- 窗口拖动 ---- */
        @JavascriptInterface
        public void dragStart() {
            svc.runOnUi(() -> dragHolding = true);
        }

        @JavascriptInterface
        public void dragBy(final float dxDp, final float dyDp) {
            svc.runOnUi(() -> {
                if (!dragHolding) return;
                moveBy(Math.round(dxDp * ctx.getResources().getDisplayMetrics().density),
                        Math.round(dyDp * ctx.getResources().getDisplayMetrics().density));
            });
        }

        @JavascriptInterface
        public void dragEnd() {
            svc.runOnUi(() -> {
                dragHolding = false;
                saveGeom();
            });
        }

        /* ---- 窗口缩放 ---- */
        @JavascriptInterface
        public void resizeStart() {
            svc.runOnUi(() -> {
                resizeHolding = true;
                if (lp != null) {
                    resizeBaseW = lp.width;
                    resizeBaseH = lp.height;
                }
            });
        }

        @JavascriptInterface
        public void resizeBy(final float dwDp, final float dhDp) {
            svc.runOnUi(() -> {
                if (!resizeHolding) return;
                float d = ctx.getResources().getDisplayMetrics().density;
                resizeTo(resizeBaseW + Math.round(dwDp * d),
                        resizeBaseH + Math.round(dhDp * d));
            });
        }

        @JavascriptInterface
        public void resizeEnd() {
            svc.runOnUi(() -> {
                resizeHolding = false;
                saveGeom();
            });
        }

        /* ---- 窗口控制 ---- */
        @JavascriptInterface
        public void closePanel() {
            svc.runOnUi(Panel.this::hide);
        }

        @JavascriptInterface
        public void killBall() {
            svc.runOnUi(() -> {
                hide();
                svc.stopSelfAndBall();
            });
        }

        @JavascriptInterface
        public void toast(String m) {
            svc.runOnUi(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public float density() {
            return ctx.getResources().getDisplayMetrics().density;
        }
    }

    /* 拖动/缩放的持有状态（供 Bridge 用） */
    private boolean dragHolding, resizeHolding;
    private int resizeBaseW, resizeBaseH;

    static String esc(String s) {
        if (s == null) return "";
        return s.replace(BS2, BS2 + BS2).replace(Q, QS);
    }
}