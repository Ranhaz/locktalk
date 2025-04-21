package com.example.locktalk_01.managers;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.util.Log;

import com.example.locktalk_01.R;

public class OverlayManager {

    private static final String TAG = "OverlayManager";

    private final WindowManager wm;
    private final Context ctx;
    private View overlay;
    private WindowManager.LayoutParams p;
    private boolean shown = false;

    private int initX, initY;  float touchX, touchY;  int lastAction;

    public OverlayManager(Context c, WindowManager w) {
        this.ctx = c.getApplicationContext(); this.wm = w;
    }

    public void show(View.OnClickListener enc, View.OnClickListener close,
                     View.OnClickListener dec) {

        if (shown) hide();

        p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;  p.y = 200;

        overlay = LayoutInflater.from(ctx).inflate(R.layout.encryption_overlay, null);

        ImageButton bEnc = overlay.findViewById(R.id.overlayEncryptButton);
        ImageButton bDec = overlay.findViewById(R.id.overlayDecryptButton);
        ImageButton bCls = overlay.findViewById(R.id.overlayCloseButton);

        bEnc.setOnClickListener(enc);
        bDec.setOnClickListener(dec);
        bCls.setOnClickListener(v->{ close.onClick(v); hide(); });

        View.OnTouchListener drag = (v,e)->{
            // אם מדובר בכפתור, הרשה את הטיפול הרגיל באירועים
            if (v instanceof ImageButton) {
                return false;
            }

            // המשך כרגיל עבור המיכל
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastAction = MotionEvent.ACTION_DOWN;
                    initX = p.x; initY = p.y;
                    touchX = e.getRawX(); touchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastAction = MotionEvent.ACTION_MOVE;
                    p.x = initX + (int)(e.getRawX()-touchX);
                    p.y = initY + (int)(e.getRawY()-touchY);
                    wm.updateViewLayout(overlay, p);
                    return true;
                case MotionEvent.ACTION_UP:
                    return lastAction != MotionEvent.ACTION_DOWN;
            }
            return false;
        };

        wm.addView(overlay, p);
        shown = true;
        Log.d(TAG,"overlay shown");
    }

    public void hide() {
        if (overlay!=null && shown) {
            try { wm.removeView(overlay); }
            catch (Exception ignore) {}
            overlay=null; shown=false;
        }
    }
    public boolean isShown(){ return shown; }
}
