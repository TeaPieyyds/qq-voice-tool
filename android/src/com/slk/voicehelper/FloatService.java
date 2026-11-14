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
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 悬浮球前台服务。
 * 球体自绘：一枚极简圆形，内部一条正弦波轨迹，代表"语音"。
 * 轻点开面板，长按拖走，松手吸附屏幕左右边缘。
 */
public class FloatService extends Service {

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
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!added) showBall();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("语音球在运行")
                .setContentText("轻点悬浮球开始替换语音")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /* ---------------- 球 ---------------- */

    private void showBall() {
        int size = dp(52);
        ball = new BallView();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(180);

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;

        ball.setOnTouchListener(this::onTouch);
        try {
            wm.addView(ball, lp);
            added = true;
        } catch (Exception e) {
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

    /** 松手后贴到最近的左/右边缘，带一点内缩 */
    private void snapToEdge() {
        int target = (lp.x + lp.width / 2 < screenW / 2) ? dp(4) : screenW - lp.width - dp(4);
        final int from = lp.x;
        final int to = target;
        final long t0 = System.currentTimeMillis();
        final int dur = 160;
        ball.post(new Runnable() {
            @Override public void run() {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                float e = 1 - (1 - p) * (1 - p);   // ease-out
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

    /** 自绘球体：外圈 + 内圈 + 正弦波 */
    private class BallView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        BallView() {
            super(FloatService.this);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(1.4f));
            ring.setColor(0x33000000);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFF1B1F24);

            wave.setStyle(Paint.Style.STROKE);
            wave.setStrokeWidth(dp(1.9f));
            wave.setStrokeCap(Paint.Cap.ROUND);
            wave.setColor(0xFFFFFFFF);
        }

        private int dp(float v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - dp(1.2f);

            c.drawCircle(cx, cy, r, fill);

            // 外圈一道极细描边，让深色球在深色背景上也有边界
            c.drawCircle(cx, cy, r, ring);

            // 正弦波：三段，中段高，两侧低
            float amp = r * 0.34f;
            float period = r * 1.15f;
            android.graphics.Path p = new android.graphics.Path();
            boolean first = true;
            for (float x = cx - r * 0.52f; x <= cx + r * 0.52f; x += 0.8f) {
                float t = (x - (cx - r * 0.52f)) / (r * 1.04f);   // 0..1
                float envelope = (float) Math.sin(Math.PI * t);     // 两端收窄
                float y = cy + (float) Math.sin((x - cx) / period * Math.PI * 2) * amp * envelope;
                if (first) { p.moveTo(x, y); first = false; } else p.lineTo(x, y);
            }
            c.drawPath(p, wave);
        }
    }
}