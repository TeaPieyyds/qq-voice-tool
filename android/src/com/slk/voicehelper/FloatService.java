package com.slk.voicehelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 悬浮球前台服务。
 *
 * 关键：球用 ImageView + Drawable 绘制，不用自绘 View。
 * ColorOS 上自绘 View 放在 APPLICATION_OVERLAY 里 surface 会变成 0x0。
 */
public class FloatService extends Service {

    static final String TAG = "VoiceBall";
    static final String ACTION_SHOW = "show";
    static final String ACTION_HIDE = "hide";
    static final String CH_ID = "ball";

    static FloatService live;

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private boolean added;

    private int screenW, screenH;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging;
    private long downTime;
    private static final long TAP_MS = 260;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        startForeground(1, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(a)) {
            Log.i(TAG, "收到隐藏指令");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!added) showBall();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务销毁");
        live = null;
        hideBall();
        super.onDestroy();
    }

    /* ---------------- 通知 ---------------- */

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel c = new NotificationChannel(
                        CH_ID, "悬浮球", NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                c.setSound(null, null);
                c.enableVibration(false);
                nm.createNotificationChannel(c);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 通知里加一个"关闭悬浮球"按钮
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, FloatService.class).setAction(ACTION_HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("语音球在运行")
                .setContentText("轻点悬浮球开始替换语音")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        null, "关闭悬浮球", stopPi).build())
                .build();
    }

    /* ---------------- 球 ---------------- */

    /** 用 Drawable 画球：深色圆 + 一道白色正弦波 */
    private Drawable buildBallDrawable(int size) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF16191D);
        circle.setStroke(Math.max(1, size / 40), 0x22FFFFFF);

        WaveDrawable wave = new WaveDrawable(size);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{circle, wave});
        ld.setLayerInset(0, 0, 0, 0, 0);
        ld.setLayerInset(1, 0, 0, 0, 0);
        return ld;
    }

    /** 画一道正弦波（带两端包络） */
    private static class WaveDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float size;

        WaveDrawable(int size) {
            this.size = size;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(2f, size * 0.042f));
            p.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas c) {
            float cx = size / 2f, cy = size / 2f;
            float r = size / 2f;
            float amp = r * 0.30f;
            float span = r * 0.58f;

            Path path = new Path();
            boolean first = true;
            int steps = (int) (span * 2);
            for (int i = 0; i <= steps; i++) {
                float x = cx - span + (i / (float) steps) * span * 2;
                float t = i / (float) steps;
                float env = (float) Math.sin(Math.PI * t);       // 两端收窄
                float y = cy + (float) Math.sin(t * Math.PI * 4) * amp * env;
                if (first) { path.moveTo(x, y); first = false; }
                else path.lineTo(x, y);
            }
            c.drawPath(path, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void showBall() {
        int size = dp(54);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(buildBallDrawable(size));
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        ball = iv;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(200);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        ball.setOnTouchListener(this::onTouch);
        try {
            wm.addView(ball, lp);
            added = true;
            Log.i(TAG, "球已添加 x=" + lp.x + " y=" + lp.y + " size=" + size
                    + " screen=" + screenW + "x" + screenH);
        } catch (Exception e) {
            Log.e(TAG, "添加失败", e);
            Toast.makeText(this, "悬浮窗添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void hideBall() {
        if (ball != null && added) {
            try { wm.removeView(ball); } catch (Exception ignored) { }
        }
        added = false;
        ball = null;
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = lp.x;
                downY = lp.y;
                dragging = false;
                downTime = System.currentTimeMillis();
                v.setAlpha(0.72f);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > dp(6)) dragging = true;
                if (dragging) {
                    lp.x = (int) (downX + dx);
                    lp.y = (int) (downY + dy);
                    clamp();
                    try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) { }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                v.setAlpha(1f);
                boolean quick = System.currentTimeMillis() - downTime < TAP_MS;
                if (!dragging && quick) {
                    Log.i(TAG, "点击 -> 开面板");
                    openPanel();
                } else if (dragging) {
                    snapToEdge();
                }
                return true;
            }
        }
        return false;
    }

    private void clamp() {
        int w = lp.width;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
        if (lp.x > screenW - w) lp.x = screenW - w;
        if (lp.y > screenH - w) lp.y = screenH - w;
    }

    private void snapToEdge() {
        int target = (lp.x + lp.width / 2 < screenW / 2) ? dp(4) : screenW - lp.width - dp(4);
        final int from = lp.x;
        final int to = target;
        final long t0 = System.currentTimeMillis();
        final int dur = 160;
        ball.post(new Runnable() {
            @Override public void run() {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                float e = 1 - (1 - p) * (1 - p);
                lp.x = (int) (from + (to - from) * e);
                try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) { }
                if (p < 1f) ball.postDelayed(this, 16);
            }
        });
    }

    private void openPanel() {
        Intent i = new Intent(this, PanelActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}