package com.slk.voicehelper;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** 通过 Shizuku 以 shell 身份执行命令 / 读写文件 */
final class Sh {

    static IShizukuService svc() {
        return IShizukuService.Stub.asInterface(Shizuku.getBinder());
    }

    static String exec(String cmd) throws Exception {
        IRemoteProcess rp = svc().newProcess(new String[]{"sh", "-c", cmd}, null, null);
        String out = drain(rp.getInputStream());
        String err = drain(rp.getErrorStream());
        int code = rp.waitFor();
        if (code != 0 && err.trim().length() > 0) out = out + "\n[err] " + err.trim();
        return out;
    }

    /** 出错返回空串，用于批量探测路径 */
    static String execSafe(String cmd) {
        try { return exec(cmd).trim(); } catch (Exception e) { return ""; }
    }

    static String exec(String cmd, byte[] stdin) throws Exception {
        IRemoteProcess rp = svc().newProcess(new String[]{"sh", "-c", cmd}, null, null);
        ParcelFileDescriptor out = rp.getOutputStream();
        try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(out)) {
            os.write(stdin);
            os.flush();
        }
        String err = drain(rp.getErrorStream());
        int code = rp.waitFor();
        return code == 0 ? "" : ("exit " + code + " " + err.trim());
    }

    private static String drain(ParcelFileDescriptor pfd) throws Exception {
        if (pfd == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static byte[] read(String path) throws Exception {
        IRemoteProcess rp = svc().newProcess(new String[]{"sh", "-c", "cat '" + esc(path) + "'"}, null, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(rp.getInputStream().getFileDescriptor())) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        rp.waitFor();
        return bos.toByteArray();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }
}
