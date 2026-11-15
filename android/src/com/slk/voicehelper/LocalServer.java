package com.slk.voicehelper;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 极简静态服务器：把 APK 里的 assets/www 通过 http://127.0.0.1 暴露给 WebView */
final class LocalServer extends Thread {

    interface Log { void onLog(String s); }

    private final AssetManager am;
    private ServerSocket ss;
    private volatile boolean running = true;
    private final Log logger;

    /** 选中的音频字节，供 /picked 路由回给 WebView（避开 content:// 跨域） */
    private volatile byte[] picked;
    private volatile String pickedName = "";

    /** 落盘目录：历史条目重启后还要能读回来，光靠内存只留得住最后一条 */
    private volatile File audioDir;
    private static final int KEEP_FILES = 16;

    void setAudioDir(File d) {
        audioDir = d;
        try { if (d != null && !d.exists()) d.mkdirs(); } catch (Exception ignored) { }
    }

    /** 文件名转安全串：历史里存的就是这个名字，路由按它找回 */
    static String safeName(String name) {
        String n = (name == null || name.isEmpty()) ? "audio" : name;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '.' || c == '-' || c == '_';
            b.append(ok ? c : '_');
        }
        String s = b.toString();
        if (s.length() > 90) s = s.substring(s.length() - 90);
        return s;
    }

    synchronized void setPicked(byte[] data, String name) {
        this.picked = data;
        this.pickedName = name == null ? "" : name;
        if (audioDir == null || data == null || data.length == 0) return;
        File f = new File(audioDir, safeName(pickedName));
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(f);
            fo.write(data);
            fo.flush();
        } catch (Exception e) {
            logger.onLog("saveAudio: " + e);
        } finally {
            try { if (fo != null) fo.close(); } catch (IOException ignored) { }
        }
        prune();
    }

    /** 只留最近 KEEP_FILES 个，防无限膨胀 */
    private void prune() {
        File d = audioDir;
        if (d == null) return;
        File[] fs = d.listFiles();
        if (fs == null || fs.length <= KEEP_FILES) return;
        List<File> all = new ArrayList<>();
        Collections.addAll(all, fs);
        Collections.sort(all, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < all.size() - KEEP_FILES; i++) {
            try { all.get(i).delete(); } catch (Exception ignored) { }
        }
    }

    boolean hasPicked() { return picked != null && picked.length > 0; }

    private static final String Q = String.valueOf((char) 34);

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace(String.valueOf((char) 92), String.valueOf((char) 92) + String.valueOf((char) 92))
                .replace(Q, String.valueOf((char) 92) + Q);
    }

    LocalServer(AssetManager am, Log log) {
        this.am = am;
        this.logger = log;
        setDaemon(true);
    }

    int startServer() throws IOException {
        ss = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        start();
        return ss.getLocalPort();
    }

    void stopServer() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        while (running) {
            try (Socket s = ss.accept()) {
                handle(s);
            } catch (IOException e) {
                if (running) logger.onLog("accept: " + e);
            }
        }
    }

    private void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int c;
            int state = 0;
            while ((c = in.read()) != -1) {
                head.write(c);
                if (c == '\n') {
                    state++;
                    if (state >= 2) break;          // 空行结束
                } else if (c != '\r') {
                    state = 0;
                }
            }
            String first = head.toString("UTF-8").split("\r?\n")[0];
            String[] parts = first.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

            // 历史条目回读：/audio?n=文件名（从落盘目录取，重启也在）
            if (path.equals("/audio")) {
                String n = param(query, "n");
                byte[] body = readAudio(n);
                if (body == null) {
                    writeResponse(s.getOutputStream(), 404, "text/plain",
                            "no audio".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                writeResponse(s.getOutputStream(), 200, audioMime(n), body);
                return;
            }

            // 选中的音频：从本地缓存吐出，和页面同源，不会跨域
            if (path.equals("/picked")) {
                byte[] p = picked;
                if (p == null || p.length == 0) {
                    writeResponse(s.getOutputStream(), 404, "text/plain",
                            "no picked".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                writeResponse(s.getOutputStream(), 200, audioMime(pickedName), p);
                return;
            }

            // 只告诉前端：现在有没有音频、叫什么名
            if (path.equals("/pickedinfo")) {
                String j = "{" + Q + "has" + Q + ":" + hasPicked()
                        + "," + Q + "name" + Q + ":" + Q + esc(pickedName) + Q
                        + "," + Q + "size" + Q + ":" + (picked == null ? 0 : picked.length)
                        + "}";
                writeResponse(s.getOutputStream(), 200, "application/json; charset=utf-8",
                        j.getBytes(StandardCharsets.UTF_8));
                return;
            }

            String asset = "www" + path;
            byte[] body;
            try {
                body = readAsset(asset);
            } catch (IOException e) {
                byte[] msg = ("404 " + asset).getBytes(StandardCharsets.UTF_8);
                writeResponse(s.getOutputStream(), 404, "text/plain", msg);
                return;
            }
            writeResponse(s.getOutputStream(), 200, mime(path), body);
        } catch (Exception e) {
            logger.onLog("handle: " + e);
        }
    }

    private void writeResponse(OutputStream os, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Not Found") + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    private static String param(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            String k = i >= 0 ? kv.substring(0, i) : kv;
            if (!k.equals(key)) continue;
            String v = i >= 0 ? kv.substring(i + 1) : "";
            try { return URLDecoder.decode(v, "UTF-8"); } catch (Exception e) { return v; }
        }
        return "";
    }

    private byte[] readAudio(String name) {
        File d = audioDir;
        if (d == null) return null;
        File f = new File(d, safeName(name));
        if (!f.exists() || f.length() <= 0) return null;
        try {
            java.io.FileInputStream is = new java.io.FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally { is.close(); }
        } catch (Exception e) {
            logger.onLog("readAudio: " + e);
            return null;
        }
    }

    private byte[] readAsset(String path) throws IOException {
        try (InputStream is = am.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("mjs", "text/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("wasm", "application/wasm");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("svg", "image/svg+xml");
    }

    private static String audioMime(String name) {
        if (name != null) {
            String n = name.toLowerCase();
            if (n.endsWith(".mp3")) return "audio/mpeg";
            if (n.endsWith(".m4a")) return "audio/mp4";
            if (n.endsWith(".flac")) return "audio/flac";
            if (n.endsWith(".ogg")) return "audio/ogg";
            if (n.endsWith(".wav")) return "audio/wav";
            if (n.endsWith(".aac")) return "audio/aac";
        }
        return "application/octet-stream";
    }

    private static String mime(String path) {
        int i = path.lastIndexOf('.');
        String ext = i >= 0 ? path.substring(i + 1).toLowerCase() : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
