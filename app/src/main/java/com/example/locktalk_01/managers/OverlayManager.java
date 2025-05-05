package com.example.locktalk_01.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.locktalk_01.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OverlayManager {
    private static final String TAG = "OverlayManager";

    private final WindowManager wm;
    private final Context ctx;
    private View mainOverlay;
    private WindowManager.LayoutParams mainLp;
    private boolean mainShown = false;

    private ImageButton bDec, bCls;
    private TextView timerView;
    private View.OnClickListener originalCloseListener;

    private final List<View> decryptOverlays = new ArrayList<>();

    // גרירה
    private int initX, initY;
    private float touchX, touchY;
    private int lastAction;

    // טיימר
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    public OverlayManager(Context c, WindowManager w) {
        this.ctx = c.getApplicationContext();
        this.wm = w;
    }

    /** משנה מיקום של ה־overlay הראשי */
    public void updatePosition(int x, int y) {
        if (mainOverlay != null && mainLp != null) {
            mainLp.x = x;
            mainLp.y = y;
            wm.updateViewLayout(mainOverlay, mainLp);
        }
    }

    /** מציג את ה־overlay הראשי עם כפתורי Enc/Close/Dec */
    @SuppressLint("ClickableViewAccessibility")
    public void show(
            View.OnClickListener enc,
            View.OnClickListener close,
            View.OnClickListener dec
    ) {
        if (mainShown) {
            // רק מעדכנים listeners
            if (bDec != null) bDec.setOnClickListener(dec);
            originalCloseListener = close;
            if (bCls != null) bCls.setOnClickListener(close);
            return;
        }

        mainLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        mainLp.gravity = Gravity.TOP | Gravity.START;
        // ערכים התחלתיים; יתוקנו מיידית ע"י updatePosition(...)
        mainLp.x = 0;
        mainLp.y = 0;

        mainOverlay = LayoutInflater.from(ctx)
                .inflate(R.layout.encryption_overlay, null);

        bDec      = mainOverlay.findViewById(R.id.overlayDecryptButton);
        bCls      = mainOverlay.findViewById(R.id.overlayCloseButton);
        timerView = mainOverlay.findViewById(R.id.overlayTimer);

        // אתחול ויזיביליטי
        timerView.setVisibility(View.GONE);
        bCls.setVisibility(View.GONE);

        bDec.setOnClickListener(dec);
        originalCloseListener = close;
        bCls.setOnClickListener(close);

        // גרירה
        mainOverlay.setOnTouchListener((v, e) -> {
            if (v instanceof ImageButton) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastAction = e.getActionMasked();
                    initX = mainLp.x;
                    initY = mainLp.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastAction = e.getActionMasked();
                    mainLp.x = initX + (int)(e.getRawX() - touchX);
                    mainLp.y = initY + (int)(e.getRawY() - touchY);
                    wm.updateViewLayout(mainOverlay, mainLp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return lastAction != MotionEvent.ACTION_DOWN;
            }
            return false;
        });

        wm.addView(mainOverlay, mainLp);
        mainShown = true;
        Log.d(TAG, "main overlay shown");
    }

    /**
     * מפעיל טיימר עד expiryTimeMillis, מציג את השעון במקום decrypt Button,
     * וב־Close מטפל בביטול cancelCallback + stopTimer().
     */
    public void startTimer(long expiryTimeMillis, Runnable cancelCallback) {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        timerView.setVisibility(View.VISIBLE);
        bDec.setVisibility(View.GONE);
        bCls.setVisibility(View.VISIBLE);
        bCls.setOnClickListener(v -> {
            cancelCallback.run();
            stopTimer();
        });

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long rem = expiryTimeMillis - System.currentTimeMillis();
                if (rem > 0) {
                    int sec = (int)(rem / 1000);
                    int m = sec / 60, s = sec % 60;
                    timerView.setText(String.format(Locale.getDefault(),
                            "%02d:%02d", m, s));
                    timerHandler.postDelayed(this, 1000);
                } else {
                    cancelCallback.run();
                    stopTimer();
                    clearDecryptOverlays();
                }
            }
        };
        timerHandler.post(timerRunnable);
    }

    /** עוצר את הטיימר, מחביא שעון, מחזיר decrypt+ close למצבם המקורי */
    public void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
        timerView.setVisibility(View.GONE);
        bDec.setVisibility(View.VISIBLE);
        bCls.setVisibility(View.GONE);
        // משחזרים את ה־close listener המקורי
        if (originalCloseListener != null) {
            bCls.setOnClickListener(originalCloseListener);
        }
    }

    /** מוחק את כל בועות הפיענוח */
    public void clearDecryptOverlays() {
        for (View v : decryptOverlays) {
            try { wm.removeView(v); } catch(Exception ignored){}
        }
        decryptOverlays.clear();
    }

    /** סוגר את כל ה־overlay (כולל טיימר ובועות) */
    public void hide() {
        stopTimer();
        clearDecryptOverlays();
        if (mainOverlay != null && mainShown) {
            try { wm.removeView(mainOverlay); } catch(Exception ignored){}
            mainOverlay = null;
            mainShown   = false;
            Log.d(TAG, "main overlay hidden");
        }
    }

    public boolean isShown() {
        return mainShown;
    }

    /** בועת פיענוח מתחת לבועה המקורית */
    public void showDecryptedOverlay(String text, Rect bounds) {
        View bubble = LayoutInflater.from(ctx)
                .inflate(R.layout.decrypt_overlay, null);
        TextView tv = bubble.findViewById(R.id.decrypt_overlay_text);
        tv.setText(text);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                bounds.left, bounds.top,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(bubble, lp);
        decryptOverlays.add(bubble);
    }
}
