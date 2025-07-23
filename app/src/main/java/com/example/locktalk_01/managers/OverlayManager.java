
package com.example.locktalk_01.managers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // שימוש ב-ConcurrentHashMap לבטיחות thread
    private final Map<String, View> bubbleOverlays = new ConcurrentHashMap<>();
    private final Set<String> imageOverlayIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Rect> overlayBounds = new ConcurrentHashMap<>(); // מעקב אחר bounds
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable timerTask;
    private int touchStartX, touchStartY;
    private View fullScreenImageOverlay = null;

    private static OverlayManager instance;
    private volatile boolean isUpdatingVisibility = false; // מניעת עדכונים מקבילים

    public OverlayManager(@NonNull Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        instance = this;
    }

    private boolean isDecryptionActive() {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        return svc != null && svc.isDecryptionTimerActive();
    }

    public boolean hasImageOverlay(String imageKey) {
        return imageOverlayIds.contains(imageKey) && bubbleOverlays.containsKey(imageKey);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show(View.OnClickListener pick, View.OnClickListener close, View.OnClickListener decrypt) {
        if (overlay != null) return;

        try {
            overlay = LayoutInflater.from(ctx).inflate(R.layout.overlay_controls, null);
            bPick = overlay.findViewById(R.id.overlaySelectImageButton);
            bDec = overlay.findViewById(R.id.overlayDecryptButton);
            bCls = overlay.findViewById(R.id.overlayCloseButton);
            timerView = overlay.findViewById(R.id.timerTextView);
            originalClose = close;

            bPick.setOnClickListener(v -> {
                updateOverlaysVisibility();
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
                        try {
                            wm.updateViewLayout(overlay, lp);
                        } catch (Exception ignored) {}
                        touchStartX = (int) e.getRawX();
                        touchStartY = (int) e.getRawY();
                        return true;
                }
                return false;
            });

            wm.addView(overlay, lp);
            Log.d(TAG, "Overlay controls shown");
        } catch (Exception e) {
            Log.e(TAG, "Error showing overlay controls", e);
            overlay = null;
        }
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
        String uniqueId = "default_id_" + System.currentTimeMillis();
        showDecryptedImageOverlay(bmp, bounds, uniqueId);
    }

    public void startTimer(long expiryTs, Runnable onExpire) {
        stopTimer();
        if (timerView != null) {
            timerView.setVisibility(View.VISIBLE);
            bDec.setVisibility(View.GONE);
            bCls.setVisibility(View.VISIBLE);
            bCls.setOnClickListener(v -> {
                onExpire.run();
                stopTimer();
            });

            timerTask = new Runnable() {
                @Override public void run() {
                    long ms = expiryTs - System.currentTimeMillis();
                    if (ms > 0) {
                        int s = (int) (ms / 1000);
                        if (timerView != null) {
                            timerView.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
                        }
                        ui.postDelayed(this, 1_000);
                    } else {
                        onExpire.run();
                        stopTimer();
                    }
                }
            };
            ui.post(timerTask);
            Log.d(TAG, "Timer started until: " + expiryTs);
        }
    }

    public void stopTimer() {
        if (timerTask != null) {
            ui.removeCallbacks(timerTask);
            timerTask = null;
        }

        if (timerView != null) {
            timerView.setVisibility(View.GONE);
            bDec.setVisibility(View.VISIBLE);
            bCls.setVisibility(View.GONE);
            bCls.setOnClickListener(originalClose);
        }
        clearDecryptOverlays();
        Log.d(TAG, "Timer stopped");
    }

    private boolean shouldShowOverlays() {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null) {
            try {
                return !svc.shouldHideDecryptionOverlays();
            } catch (Exception e) {
                Log.w(TAG, "Error checking shouldShowOverlays", e);
                return false;
            }
        }
        return true;
    }

    public void showDecryptedOverlay(@NonNull String txt,
                                     @NonNull AccessibilityNodeInfo bubbleNode,
                                     @NonNull Rect bounds,
                                     boolean outgoing,
                                     @NonNull String id) {
        Log.d("LT_BUBBLE", "showDecryptedOverlay called: id=" + id + ", text=" + txt.substring(0, Math.min(50, txt.length())) + "..., outgoing=" + outgoing);

        // בדיקות יציבות מוקדמות
        if (!MyAccessibilityService.getInstance().isReallyInWhatsApp()) {
            Log.d("LT_BUBBLE", "Not in WhatsApp, hiding bubbles");
            hideBubblesOnly();
            return;
        }

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w("LT_BUBBLE", "Invalid bounds for text bubble: " + bounds.toShortString());
            return;
        }

        if (bubbleOverlays.containsKey(id)) {
            Log.d("LT_BUBBLE", "Text overlay already exists for id=" + id);
            return;
        }

        try {
            int padPx = dp(4);

            FrameLayout frame = new FrameLayout(ctx);
            frame.setBackgroundColor(0x00000000);

            ScrollView scrollView = new ScrollView(ctx);
            scrollView.setFillViewport(true);

            TextView tv = new TextView(ctx);
            tv.setText(txt);
            tv.setTextIsSelectable(true);
            tv.setTextSize(16f);
            tv.setTextColor(0xFF202020);
            tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));

            if (outgoing) {
                tv.setBackgroundResource(R.drawable.bg_bubble_out);
            } else {
                tv.setBackgroundResource(R.drawable.bg_bubble_in);
            }

            scrollView.addView(tv, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            frame.addView(scrollView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            Rect adjustedBounds = new Rect(
                    bounds.left - padPx,
                    bounds.top - padPx,
                    bounds.right + padPx,
                    bounds.bottom + padPx
            );

            addOverlay(frame, translucentLp(
                    adjustedBounds.width(),
                    adjustedBounds.height(),
                    adjustedBounds.left,
                    adjustedBounds.top
            ), id);

            overlayBounds.put(id, new Rect(bounds));
            Log.d("LT_BUBBLE", "Text overlay created successfully: " + id);

        } catch (Exception e) {
            Log.e("LT_BUBBLE", "Error creating text overlay: " + id, e);
        }
    }

    public void showDecryptedImageOverlay(@NonNull Bitmap src, @NonNull Rect r, @NonNull String imageUniqueId) {
        Log.d("LT_IMG_DECRYPT", "showDecryptedImageOverlay called: id=" + imageUniqueId + ", rect=" + r.toShortString());

        // בדיקות יציבות
        if (!MyAccessibilityService.getInstance().isReallyInWhatsApp()) {
            Log.d("LT_IMG_DECRYPT", "Not in WhatsApp, skipping overlay");
            hideBubblesOnly();
            return;
        }

        if (r.width() <= 0 || r.height() <= 0) {
            Log.e("LT_IMG_DECRYPT", "Invalid bounds for image overlay: " + r.toShortString());
            return;
        }

        // בדיקה כפולה למניעת שכפולים
        if (hasImageOverlay(imageUniqueId)) {
            Log.d("LT_IMG_DECRYPT", "Image overlay already exists for id=" + imageUniqueId);
            return;
        }

        try {
            // רישום המפתח
            imageOverlayIds.add(imageUniqueId);

            // יצירת תמונה מוקטנת/מותאמת
            Bitmap scaledBitmap = createScaledBitmap(src, r);

            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(scaledBitmap);
            iv.setScaleType(ImageView.ScaleType.MATRIX);

            // יצירת matrix למיקום מדויק
            Matrix matrix = new Matrix();
            matrix.setScale(
                    (float) r.width() / scaledBitmap.getWidth(),
                    (float) r.height() / scaledBitmap.getHeight()
            );
            iv.setImageMatrix(matrix);
            iv.setAdjustViewBounds(false);

            // פתיחה במסך מלא
            iv.setOnClickListener(v -> showFullScreenImageOverlay(src));

            addOverlay(iv, translucentLp(r.width(), r.height(), r.left, r.top), imageUniqueId);
            overlayBounds.put(imageUniqueId, new Rect(r));

            Log.d("LT_IMG_DECRYPT", "Image overlay created successfully: " + imageUniqueId);
            updateOverlaysVisibility();

        } catch (Exception e) {
            Log.e("LT_IMG_DECRYPT", "Error creating image overlay: " + imageUniqueId, e);
            imageOverlayIds.remove(imageUniqueId);
        }
    }

    private Bitmap createScaledBitmap(Bitmap src, Rect targetRect) {
        try {
            // חישוב יחס התמונה והמסגרת
            float srcRatio = (float) src.getWidth() / src.getHeight();
            float targetRatio = (float) targetRect.width() / targetRect.height();

            int newWidth, newHeight;

            if (srcRatio > targetRatio) {
                // התמונה רחבה יותר - התאם לפי רוחב
                newWidth = targetRect.width();
                newHeight = Math.round(targetRect.width() / srcRatio);
            } else {
                // התמונה גבוהה יותר - התאם לפי גובה
                newHeight = targetRect.height();
                newWidth = Math.round(targetRect.height() * srcRatio);
            }

            // ודא שהגדלים חיוביים ולא גדולים מדי
            newWidth = Math.max(1, Math.min(newWidth, targetRect.width()));
            newHeight = Math.max(1, Math.min(newHeight, targetRect.height()));

            return Bitmap.createScaledBitmap(src, newWidth, newHeight, true);

        } catch (Exception e) {
            Log.e("LT_IMG_DECRYPT", "Error scaling bitmap", e);
            return src;
        }
    }

    public void showFullScreenImageOverlay(@NonNull Bitmap bmp) {
        hideFullScreenImage();

        try {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xCC000000);

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

            wm.addView(iv, lp);
            fullScreenImageOverlay = iv;
            Log.d(TAG, "Full screen image overlay shown");

        } catch (Exception e) {
            Log.e(TAG, "Error showing full screen image overlay", e);
        }
    }

    public void hideFullScreenImage() {
        if (fullScreenImageOverlay != null) {
            try {
                wm.removeViewImmediate(fullScreenImageOverlay);
                Log.d(TAG, "Full screen image overlay hidden");
            } catch (Exception e) {
                Log.w(TAG, "Error hiding full screen image", e);
            }
            fullScreenImageOverlay = null;
        }
    }

    private void addOverlay(View v, WindowManager.LayoutParams p, String id) {
        try {
            wm.addView(v, p);
            bubbleOverlays.put(id, v);
            Log.d(TAG, "Overlay added: " + id);
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay: " + id, e);
        }
    }

    public void cleanupBubblesExcept(Set<String> keep) {
        List<String> toRemove = new ArrayList<>();

        for (String id : bubbleOverlays.keySet()) {
            if (!keep.contains(id)) {
                toRemove.add(id);
            }
        }

        for (String id : toRemove) {
            removeOverlay(id);
        }

        // ניקוי רשימות המעקב
        imageOverlayIds.retainAll(keep);
        overlayBounds.keySet().retainAll(keep);

        Log.d(TAG, "Cleaned up " + toRemove.size() + " overlays, kept " + keep.size());
    }

    public void cleanupAllBubbles() {
        List<String> allIds = new ArrayList<>(bubbleOverlays.keySet());
        for (String id : allIds) {
            removeOverlay(id);
        }

        imageOverlayIds.clear();
        overlayBounds.clear();
        Log.d(TAG, "All bubbles cleaned up");
    }

    private void removeOverlay(String id) {
        View v = bubbleOverlays.remove(id);
        if (v != null) {
            try {
                wm.removeView(v);
                Log.d(TAG, "Overlay removed: " + id);
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay: " + id, e);
            }
        }

        imageOverlayIds.remove(id);
        overlayBounds.remove(id);
    }

    public void clearDecryptOverlays() {
        cleanupBubblesExcept(new HashSet<>());
    }

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
        p.x = x;
        p.y = y;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    public boolean isShown() {
        return overlay != null;
    }

    public void updateToTopCenter() {
        if (overlay == null || lp == null) return;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(24);
        try {
            wm.updateViewLayout(overlay, lp);
        } catch (Exception e) {
            Log.w(TAG, "Error updating overlay position", e);
        }
    }

    public void hideBubblesOnly() {
        for (Map.Entry<String, View> entry : bubbleOverlays.entrySet()) {
            try {
                entry.getValue().setVisibility(View.GONE);
            } catch (Exception e) {
                Log.w(TAG, "Error hiding bubble: " + entry.getKey(), e);
            }
        }
        Log.d(TAG, "All bubbles hidden TEMPORARILY");
    }

    public void hide() {
        cleanupAllBubbles();
        hideFullScreenImage();

        if (overlay != null) {
            try {
                wm.removeViewImmediate(overlay);
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay controls", e);
            }
            overlay = null;
            lp = null;
        }

        stopTimer();
        Log.d(TAG, "Overlay manager hidden completely");
    }

    public void updateOverlaysVisibility() {
        if (isUpdatingVisibility) return;

        isUpdatingVisibility = true;
        try {
            boolean shouldShow = shouldShowOverlays() && isDecryptionActive();
            int visibleCount = 0;
            int hiddenCount = 0;

            for (Map.Entry<String, View> entry : bubbleOverlays.entrySet()) {
                try {
                    View view = entry.getValue();
                    if (view != null) {
                        view.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
                        if (shouldShow) visibleCount++;
                        else hiddenCount++;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error updating visibility for: " + entry.getKey(), e);
                }
            }

            Log.d(TAG, "updateOverlaysVisibility: " + (shouldShow ? "VISIBLE" : "GONE") +
                    " - visible: " + visibleCount + ", hidden: " + hiddenCount);

        } finally {
            isUpdatingVisibility = false;
        }
    }
}
