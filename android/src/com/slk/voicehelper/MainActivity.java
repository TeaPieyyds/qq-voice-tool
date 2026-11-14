package com.slk.voicehelper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 桌面入口。不放任何界面：
 *   1. 申请悬浮窗权限
 *   2. 启前台服务把球挂上去
 *   3. 自己退出
 * 已经授权过的话，点一下图标就是"球不见了 -> 叫回来"。
 */
public class MainActivity extends Activity {

    static final int REQ_OVERLAY = 2001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            try {
                startActivityForResult(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (Exception e) {
                Toast.makeText(this, "打不开权限页：" + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }

        startBall();
        finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startBall();
            } else {
                Toast.makeText(this, "没给权限，悬浮球起不来", Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private void startBall() {
        try {
            Intent i = new Intent(this, FloatService.class);
            i.setAction(FloatService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}