package com.example.locktalk_01.managers;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.core.content.FileProvider;
import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;
import com.example.locktalk_01.utils.EncryptionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class OverlayManager {
    public static final int REQ_IMG = 3001;

    private final Context ctx;
    private final Activity activity;
    private final WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams lp;
    private int lastX, lastY;

    public OverlayManager(Activity activity) {
        this.activity = activity;
        this.ctx = activity;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public OverlayManager(MyAccessibilityService svc) {
        this.activity = null;
        this.ctx = svc;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * מציג את כפתורי ה־Overlay.
     * מאפשר לחיצה מחוץ לחלון (למשל כפתור Send בוואטסאפ).
     */
    public void showOverlay(View.OnClickListener pickClick,
                            View.OnClickListener closeClick,
                            View.OnClickListener decryptClick) {
        if (overlay != null) return;

        // בואו נשתמש ב־AppCompat Theme
        Context themeCtx = new ContextThemeWrapper(ctx, R.style.AppTheme);
        overlay = LayoutInflater.from(themeCtx)
                .inflate(R.layout.overlay_controls, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // כאן החלפנו: FLAG_NOT_TOUCH_MODAL כדי להעביר לחיצות מחוץ לחלון הלאה
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 100;
        lp.y = 200;

        // גרירה של ה־Overlay
        overlay.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = (int) e.getRawX();
                    lastY = (int) e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x += (int) e.getRawX() - lastX;
                    lp.y += (int) e.getRawY() - lastY;
                    wm.updateViewLayout(overlay, lp);
                    lastX = (int) e.getRawX();
                    lastY = (int) e.getRawY();
                    return true;
            }
            return false;
        });

        overlay.findViewById(R.id.overlaySelectImageButton)
                .setOnClickListener(pickClick);
        overlay.findViewById(R.id.overlayDecryptButton)
                .setOnClickListener(decryptClick);
        overlay.findViewById(R.id.overlayCloseButton)
                .setOnClickListener(closeClick);

        wm.addView(overlay, lp);
    }

    /** מסתיר את ה־Overlay */
    public void hideOverlay() {
        if (overlay != null) {
            wm.removeView(overlay);
            overlay = null;
        }
    }

    /** האם כרגע מוצג Overlay? */
    public boolean isShown() {
        return overlay != null;
    }

    /**
     * מציג בועה עם תמונה מפוענחת למשך כמה שניות.
     */
    public void showOverlayImage(Bitmap bmp, Rect bounds) {
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(bmp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = bounds.left;
        p.y = bounds.top;

        wm.addView(iv, p);

        // הסרה אוטומטית אחרי 5 שניות
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(iv);
            } catch (Exception ignored) {}
        }, 5000);
    }

    /**
     * מטפל בתוצאה של בחירת תמונה:
     * 1) מפעיל פילטר הצפנה
     * 2) שומר את הקובץ ומוסיף EXIF עם ה־base64
     * 3) שולח לוואטסאפ
     */
    public void handlePickerResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMG
                || resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        try {
            // קבלת ה-Bitmap המקורי
            Uri picked = data.getData();
            Bitmap orig = MediaStore.Images.Media.getBitmap(ctx.getContentResolver(), picked);

            // הצפנה ויזואלית
            Bitmap encrypted = EncryptionUtils.applyEncryptionFilter(orig);

            // שמירה והפקת URI חוקי
            Uri shareUri = EncryptionUtils.saveBitmap(ctx, encrypted);

            // בניית Intent שיתוף
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, shareUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            // במידה ושמנו את lastWhatsAppPackage
            MyAccessibilityService svc = MyAccessibilityService.getInstance();
            String waPkg = svc != null ? svc.getLastWhatsAppPackage() : null;
            if (waPkg != null) {
                share.setPackage(waPkg);
                // תן ל־WhatsApp הרשאת קריאה ל-URI
                ctx.grantUriPermission(waPkg, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            // הפעלת ה-Activity
            ctx.startActivity(share);

        } catch (IOException e) {
            Log.e("OverlayMgr", "image pick failed", e);
        }
    }

}
