package com.slk.voicehelper;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    void setPicked(byte[] data, String name) {
        this.picked = data;
        this.pickedName = name == null ? "" : name;
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
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

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
