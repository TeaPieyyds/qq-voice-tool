package com.slk.voicehelper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 只用来打开系统文件选择器的透明 Activity。
 *
 * overlay 里的 WebView 拿不到 onShowFileChooser 回调，只能靠 Activity 中转。
 * 选完直接把字节塞给 FloatService 里的 Panel，然后自己 finish。
 */
public class PickActivity extends Activity {

    static final String TAG = "QQVoicePick";
    static final int REQ = 2001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ);
        } catch (Exception e) {
            Log.e(TAG, "picker", e);
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ) { super.onActivityResult(req, res, data); return; }

        if (res != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "持久权限失败 " + e);
        }

        String name = queryName(uri);
        try {
            byte[] bytes = readUri(uri);
            if (bytes == null || bytes.length == 0) {
                Toast.makeText(this, "读不到这个文件", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            FloatService svc = FloatService.live;
            if (svc != null && svc.panelRef() != null) {
                svc.panelRef().setPicked(bytes, name);
                Log.i(TAG, "已交给面板：" + name + " " + bytes.length + " 字节");
            } else {
                Log.w(TAG, "服务不在，字节丢弃");
            }
        } catch (Exception e) {
            Log.e(TAG, "read", e);
            Toast.makeText(this, "读取失败：" + e, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    byte[] readUri(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    String queryName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name == null) name = "audio";
        }
        return name;
    }
}