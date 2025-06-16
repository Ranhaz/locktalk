package com.example.locktalk_01.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x00080000;
    private static Field sPrivateFlagsField;

    static {
        try {
            sPrivateFlagsField = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            sPrivateFlagsField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static void addTrustedFlag(WindowManager.LayoutParams lp) {
        if (sPrivateFlagsField == null) return;
        try {
            int v = sPrivateFlagsField.getInt(lp);
            sPrivateFlagsField.setInt(lp, v | PRIVATE_FLAG_TRUSTED_OVERLAY);
        } catch (Throwable ignored) {}
    }

    private final Context ctx;
    private final WindowManager wm;

    private View overlay;
    private WindowManager.LayoutParams lp;

    private ImageButton bPick, bDec, bCls;
    private TextView timerView;
    private View.OnClickListener originalClose;

    private final Map<String, View> bubbleOverlays = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable timerTask;
    private int touchStartX, touchStartY;
    private View fullScreenImageOverlay = null;

    private static OverlayManager instance;

    public OverlayManager(@NonNull Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        instance = this;
    }

    public static OverlayManager getInstance() { return instance; }

    // בדיקה: האם פענוח פעיל (ע"פ טיימר)
    private boolean isDecryptionActive() {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        return svc != null && svc.isDecryptionTimerActive();
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show(View.OnClickListener pick, View.OnClickListener close, View.OnClickListener decrypt) {
        if (overlay != null) return;

        overlay = LayoutInflater.from(ctx).inflate(R.layout.overlay_controls, null);
        bPick = overlay.findViewById(R.id.overlaySelectImageButton);
        bDec = overlay.findViewById(R.id.overlayDecryptButton);
        bCls = overlay.findViewById(R.id.overlayCloseButton);
        timerView = overlay.findViewById(R.id.timerTextView);
        originalClose = close;

        bPick.setOnClickListener(v -> {
            updateOverlaysVisibility(); // מסתיר זמנית, לא מאפס את הטיימר!
            if (pick != null) pick.onClick(v);
        });

        bDec.setOnClickListener(decrypt);
        bCls.setOnClickListener(close);

        timerView.setVisibility(View.GONE);
        bCls.setVisibility(View.GONE);

        lp = createControlLp(100, 200);

        overlay.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = (int) e.getRawX();
                    touchStartY = (int) e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x += (int) e.getRawX() - touchStartX;
                    lp.y += (int) e.getRawY() - touchStartY;
                    wm.updateViewLayout(overlay, lp);
                    touchStartX = (int) e.getRawX();
                    touchStartY = (int) e.getRawY();
                    return true;
            }
            return false;
        });

        wm.addView(overlay, lp);
        Log.d(TAG, "Overlay controls shown");
    }

    private WindowManager.LayoutParams createControlLp(int x, int y) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    public void showImageOverlay(Bitmap bmp, Rect bounds) {
        String uniqueId = "default_id";
        showDecryptedImageOverlay(bmp, bounds, uniqueId);
    }

    public void startTimer(long expiryTs, Runnable onExpire) {
        stopTimer();
        timerView.setVisibility(View.VISIBLE);
        bDec.setVisibility(View.GONE);
        bCls.setVisibility(View.VISIBLE);
        bCls.setOnClickListener(v -> { onExpire.run(); stopTimer(); });

        timerTask = new Runnable() {
            @Override public void run() {
                long ms = expiryTs - System.currentTimeMillis();
                if (ms > 0) {
                    int s = (int) (ms / 1000);
                    timerView.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
                    ui.postDelayed(this, 1_000);
                } else { onExpire.run(); stopTimer(); }
            }
        };
        ui.post(timerTask);
        Log.d(TAG, "Timer started until: " + expiryTs);
    }

    public void stopTimer() {
        if (timerTask != null) ui.removeCallbacks(timerTask);
        timerTask = null;

        timerView.setVisibility(View.GONE);
        bDec.setVisibility(View.VISIBLE);
        bCls.setVisibility(View.GONE);
        bCls.setOnClickListener(originalClose);
        clearDecryptOverlays();
        Log.d(TAG, "Timer stopped");
    }

    /** הגנה - מציג בועה רק בשיחה אמיתית */
    private boolean shouldShowOverlays() {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null) {
            try {
                return !svc.shouldHideDecryptionOverlays();
            } catch (Exception e) { return false; }
        }
        return true;
    }
    // מציג טקסט מפוענח על בועה
    public void showDecryptedOverlay(@NonNull String txt,
                                     @NonNull AccessibilityNodeInfo bubbleNode,
                                     @NonNull Rect bounds,
                                     boolean outgoing,
                                     @NonNull String id) {
        // הגנה: אל תציג אם לא בוואטסאפ
        if (!MyAccessibilityService.getInstance().isReallyInWhatsApp()) {
            hide();
            return;
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.e(TAG, "Invalid bounds for text overlay: " + bounds.toShortString());
            return;
        }
        if (bubbleOverlays.containsKey(id)) return;

        View bubble = LayoutInflater.from(ctx).inflate(R.layout.overlay_bubble, null);
        TextView tv = bubble.findViewById(R.id.bubbleText);
        tv.setText(txt);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        if (outgoing) {
            tv.setBackgroundResource(R.drawable.bg_bubble_out);
        } else {
            tv.setBackgroundResource(R.drawable.bg_bubble_in);
        }

        addOverlay(bubble, translucentLp(bounds.width(), bounds.height(), bounds.left, bounds.top), id);
        Log.d(TAG, "Text overlay shown: " + id);

        updateOverlaysVisibility();
    }

    // מציג תמונה מפוענחת על גבי התמונה המקורית
    public void showDecryptedImageOverlay(@NonNull Bitmap src, @NonNull Rect r, @NonNull String imageUniqueId) {
        // הגנה: אל תציג אם לא בוואטסאפ
        if (!MyAccessibilityService.getInstance().isReallyInWhatsApp()) {
            hide();
            return;
        }
        if (r.width() <= 0 || r.height() <= 0) {
            Log.e(TAG, "Invalid bounds for image overlay: " + r.toShortString());
            return;
        }
        String id = imageUniqueId;
        if (bubbleOverlays.containsKey(id)) return;

        Bitmap cropped = src;
        try {
            // חיתוך תמונה לגודל התמונה בלוגו (אם צריך)
            if (src.getWidth() > r.width() || src.getHeight() > r.height()) {
                float scaleX = (float) src.getWidth() / r.width();
                float scaleY = (float) src.getHeight() / r.height();
                int cropW = (int)(r.width() * scaleX);
                int cropH = (int)(r.height() * scaleY);

                int cropX = Math.max(0, (src.getWidth() - cropW) / 2);
                int cropY = Math.max(0, (src.getHeight() - cropH) / 2);

                if (cropX + cropW > src.getWidth()) cropW = src.getWidth() - cropX;
                if (cropY + cropH > src.getHeight()) cropH = src.getHeight() - cropY;

                cropped = Bitmap.createBitmap(src, cropX, cropY, cropW, cropH);
            }
        } catch (Exception e) {
            Log.e(TAG, "crop error", e);
            cropped = src;
        }

        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(cropped);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);

        // פתיחה במסך מלא
        iv.setOnClickListener(v -> showFullScreenImageOverlay(src));

        addOverlay(iv, translucentLp(r.width(), r.height(), r.left, r.top), id);
        Log.d(TAG, "Image overlay shown: " + id + " rect: " + r.toShortString());

        updateOverlaysVisibility();
    }

    // תמונה מפוענחת במסך מלא
    public void showFullScreenImageOverlay(@NonNull Bitmap bmp) {
        hideFullScreenImage();

        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xCC000000); // רקע כהה שקוף

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        addTrustedFlag(lp);
        lp.gravity = Gravity.CENTER;

        iv.setOnClickListener(v -> hideFullScreenImage());

        try {
            wm.addView(iv, lp);
            fullScreenImageOverlay = iv;
        } catch (Exception e) {
            Log.e(TAG, "showFullScreenImageOverlay", e);
        }
    }

    public void hideFullScreenImage() {
        if (fullScreenImageOverlay != null) {
            try { wm.removeViewImmediate(fullScreenImageOverlay); } catch (Exception ignore) {}
            fullScreenImageOverlay = null;
        }
    }

    private void addOverlay(View v, WindowManager.LayoutParams p, String id) {
        try {
            wm.addView(v, p);
            bubbleOverlays.put(id, v);
        } catch (Exception e) {
            Log.e(TAG, "addOverlay", e);
        }
    }

    public void cleanupBubblesExcept(Set<String> keep) {
        List<String> remove = new ArrayList<>();
        for (String id : bubbleOverlays.keySet()) if (!keep.contains(id)) remove.add(id);
        for (String id : remove) removeOverlay(id);
    }

    public void cleanupAllBubbles() {
        List<String> remove = new ArrayList<>(bubbleOverlays.keySet());
        for (String id : remove) removeOverlay(id);
    }

    private void removeOverlay(String id) {
        View v = bubbleOverlays.remove(id);
        if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
    }

    public void clearDecryptOverlays() { cleanupBubblesExcept(Set.of()); }

    public static String bubbleId(String txt, Rect b, boolean out) {
        return (txt + "|" + b.toShortString() + "|" + out).hashCode() + "";
    }

    private WindowManager.LayoutParams translucentLp(int w, int h, int x, int y) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        addTrustedFlag(p);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;  p.y = y;
        return p;
    }

    private int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    public static Bitmap decodeFileWithOrientation(@NonNull String path) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            int o = new ExifInterface(path)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rot = (o == ExifInterface.ORIENTATION_ROTATE_90) ? 90 :
                    (o == ExifInterface.ORIENTATION_ROTATE_180) ? 180 :
                            (o == ExifInterface.ORIENTATION_ROTATE_270) ? 270 : 0;
            if (rot != 0 && bmp != null) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (IOException e) {
            Log.e(TAG, "decodeFileWithOrientation", e);
            return null;
        }
    }

    public boolean isShown() {
        return overlay != null;
    }

    public void updateToTopCenter() {
        if (overlay == null || lp == null) return;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(24);
        try { wm.updateViewLayout(overlay, lp); } catch (Exception ignore) {}
    }

    public void updatePosition(int x, int y) {
        if (overlay == null || lp == null) return;
        lp.x = x;
        lp.y = y;
        try { wm.updateViewLayout(overlay, lp); } catch (Exception ignore) {}
    }

    // *** קריטי: מסתיר רק את הפענוחים, לא את ה־Overlay ולא עוצר טיימר! ***
    public void hideBubblesOnly() {
        for (Map.Entry<String, View> entry : bubbleOverlays.entrySet()) {
            entry.getValue().setVisibility(View.GONE);
        }
        Log.d(TAG, "All bubbles hidden TEMPORARILY");
    }

    // עוצר הכל לחלוטין (כולל טיימר ופיענוחים)
    public void hide() {
        cleanupAllBubbles();
        hideFullScreenImage();
        if (overlay != null) {
            try { wm.removeViewImmediate(overlay); } catch (Exception ignore) {}
            overlay = null;
            lp = null;
        }
        stopTimer();
        Log.d(TAG, "Overlay controls hidden (and all bubbles)");
    }

    // עדכון תצוגה לפי מצב אמיתי (מופעל כל מעבר)
    public void updateOverlaysVisibility() {
        boolean shouldShow = shouldShowOverlays() && isDecryptionActive();
        for (Map.Entry<String, View> entry : bubbleOverlays.entrySet()) {
            entry.getValue().setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        }
        Log.d(TAG, "updateOverlaysVisibility: " + (shouldShow ? "VISIBLE" : "GONE"));
    }

    public String copyOriginalImageFromUri(Uri srcUri) {
        String fname = "orig_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 9_999) + ".jpg";
        File dir = new File(ctx.getFilesDir(), "locktalk");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, fname);

        try (InputStream in = ctx.getContentResolver().openInputStream(srcUri);
             OutputStream out = new FileOutputStream(outFile)) {

            byte[] buf = new byte[8_192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            if (total == 0) return null;
        } catch (Exception e) {
            Log.e(TAG, "copyOriginalImageFromUri", e);
            return null;
        }
        return outFile.getAbsolutePath();
    }
}
