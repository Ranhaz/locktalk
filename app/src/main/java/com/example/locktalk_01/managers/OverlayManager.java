package com.example.locktalk_01.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.locktalk_01.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private final WindowManager wm;
    private final Context ctx;
    private View overlay;
    private WindowManager.LayoutParams lp;

    private int lastX, lastY;
    private Button bDec, bCls;
    private TextView timerView;
    private View.OnClickListener originalCloseListener;
    private final List<View> decryptOverlays = new ArrayList<>();
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private final Set<String> shownImageRects = new HashSet<>();



    public OverlayManager(Context context, WindowManager service) {
        this.ctx = context.getApplicationContext();
        this.wm = service;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show(View.OnClickListener pickClick,
                     View.OnClickListener closeClick,
                     View.OnClickListener decryptClick) {
        if (overlay != null) return;

        overlay = LayoutInflater.from(ctx).inflate(R.layout.overlay_controls, null);

        bDec = overlay.findViewById(R.id.overlayDecryptButton);
        bCls = overlay.findViewById(R.id.overlayCloseButton);
        timerView = overlay.findViewById(R.id.timerTextView);

        originalCloseListener = closeClick;
        timerView.setVisibility(View.GONE);
        bCls.setVisibility(View.GONE);

        overlay.findViewById(R.id.overlaySelectImageButton).setOnClickListener(pickClick);
        bDec.setOnClickListener(decryptClick);
        bCls.setOnClickListener(closeClick);

        lp = createLp(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, 100, 200);

        overlay.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = (int) event.getRawX();
                    lastY = (int) event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - lastX;
                    int dy = (int) event.getRawY() - lastY;
                    lp.x += dx;
                    lp.y += dy;
                    wm.updateViewLayout(overlay, lp);
                    lastX = (int) event.getRawX();
                    lastY = (int) event.getRawY();
                    return true;
            }
            return false;
        });

        wm.addView(overlay, lp);
    }

    private WindowManager.LayoutParams createLp(int w, int h, int x, int y) {
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

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
                    int sec = (int) (rem / 1000);
                    int m = sec / 60;
                    int s = sec % 60;
                    timerView.setText(String.format(Locale.getDefault(), "%02d:%02d", m, s));
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

    public void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
        timerView.setVisibility(View.GONE);
        bDec.setVisibility(View.VISIBLE);
        bCls.setVisibility(View.GONE);
        bCls.setOnClickListener(originalCloseListener);
        // מחיקת שכבות התמונות המפוענחות
        clearDecryptOverlays();
    }


    public void showDecryptedOverlay(String text, Rect bounds) {
        View bubble = LayoutInflater.from(ctx).inflate(R.layout.decrypt_overlay, null);
        TextView tv = bubble.findViewById(R.id.decrypt_overlay_text);
        tv.setText(text);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                bounds.left, bounds.top,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(bubble, p);
            decryptOverlays.add(bubble);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add text overlay", e);
        }
    }

    /** טען תמונה מהקובץ ומיישר אוריינטציה */
    public static Bitmap decodeFileWithOrientation(String path) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotate = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotate = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotate = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotate = 270; break;
            }
            if (rotate != 0 && bmp != null) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotate);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            }
            return bmp;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    public void showDecryptedImageOverlay(Bitmap bmp, Rect bounds) {
        if (bmp == null || bounds == null) return;
        String key = bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
        if (shownImageRects.contains(key)) return;
        shownImageRects.add(key);

        // המרה לביטמאפ ללא alpha
        Bitmap opaqueBmp = makeFullyOpaqueNoAlpha(bmp);
        Bitmap scaledBmp = Bitmap.createScaledBitmap(opaqueBmp, bounds.width(), bounds.height(), true);

        // יוצר ImageView ללא רקע כלל!
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(scaledBmp);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setAlpha(1.0f);

        // RelativeLayout שהוא ההורה (ללא רקע!)
        FrameLayout frame = new FrameLayout(ctx);

        frame.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // אוברליי – אטום באמת
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
        );
        params.x = bounds.left;
        params.y = bounds.top;
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(frame, params);
            decryptOverlays.add(frame);
            Log.d(TAG, "Overlay added: x=" + params.x + " y=" + params.y + " w=" + params.width + " h=" + params.height);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay", e);
        }
    }

    /** יוצר ביטמאפ חדש ללא alpha בכלל */
    public static Bitmap makeFullyOpaqueNoAlpha(Bitmap src) {
        if (src == null) return null;
        Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.RGB_565); // NO alpha
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE); // אפשר גם שחור אם בא לך לבדוק
        Paint paint = new Paint();
        paint.setAlpha(255);
        canvas.drawBitmap(src, 0, 0, paint);
        return result;
    }

    /** הפוך ביטמאפ לכלל אטום, מוחק alpha */
    public static Bitmap makeFullyOpaque(Bitmap src) {
        if (src == null) return null;
        Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE); // רקע לבן מלא
        Paint paint = new Paint();
        paint.setAlpha(255); // 100% אטימות
        canvas.drawBitmap(src, 0, 0, paint);
        result.setHasAlpha(false); // קריטי
        return result;
    }



    public void clearDecryptOverlays() {
        for (View v : decryptOverlays) {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
        decryptOverlays.clear();
        shownImageRects.clear();
    }
    public boolean isShown() {
        return overlay != null;
    }


    public void updatePosition(int x, int y) {
        if (overlay != null && lp != null) {
            lp.x = x;
            lp.y = y;
            try {
                wm.updateViewLayout(overlay, lp);
            } catch (Exception ignored) { }
        }
    }

    public void hide() {
        if (overlay != null) {
            try { wm.removeView(overlay); }
            catch (Exception ignored) { }
            overlay = null;
            lp = null;
        }
        stopTimer();
        clearDecryptOverlays();
    }
}
