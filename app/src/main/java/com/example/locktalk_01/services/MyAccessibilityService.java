package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import android.accessibilityservice.AccessibilityService;
import com.example.locktalk_01.activities.ImagePickerProxyActivity;
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;
import com.example.locktalk_01.utils.EncryptionUtils;
import com.example.locktalk_01.activities.AndroidKeystorePlugin;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static MyAccessibilityService instance;

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    private AndroidKeystorePlugin keystore;
    private OverlayManager overlayMgr;
    private Handler uiHandler;
    private ExecutorService executor;
    private String lastWhatsAppPackage;

    private long lastEncryptTs = 0;
    private static final long THROTTLE_MS = 500;
    private boolean isReplacing = false;


    @Override public void onCreate() {
        super.onCreate();
        instance = this;             // ← הוספה
        uiHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        keystore = new AndroidKeystorePlugin(this);
        overlayMgr = new OverlayManager(this);
        lastWhatsAppPackage = null;
    }


    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{
                "com.whatsapp","com.whatsapp.w4b","com.gbwhatsapp"
        };
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = String.valueOf(event.getPackageName());
        boolean inWA = WhatsAppUtils.isWhatsAppPackage(pkg);
        int type = event.getEventType();
        long now = System.currentTimeMillis();

        if (inWA) lastWhatsAppPackage = pkg;

        // 1) הצפנה על '$'
        if (!isReplacing && inWA
                && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && now - lastEncryptTs > THROTTLE_MS) {
            lastEncryptTs = now;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                String text = WhatsAppUtils.getWhatsAppInputText(root);
                if (text.endsWith("$")) {
                    String orig = text.substring(0, text.length()-1);
                    String code = getSharedPreferences("UserCredentials", MODE_PRIVATE)
                            .getString("personalCode", "");
                    if (code.isEmpty()) {
                        showToast("נא להגדיר קוד אישי");
                    } else {
                        isReplacing = true;
                        executor.execute(() -> {
                            try {
                                String actual = keystore.encryptToString(code, orig);
                                String fake = randomFake(actual.length());
                                getSharedPreferences("FakeCipherMap", MODE_PRIVATE)
                                        .edit().putString(fake, actual).apply();
                                uiHandler.post(() -> {
                                    AccessibilityNodeInfo root2 = getRootInActiveWindow();
                                    if (root2 != null) {
                                        TextInputUtils.performTextReplacement(
                                                MyAccessibilityService.this,
                                                root2, fake);
                                        root2.recycle();
                                    }
                                    showToast("הודעה מוצפנת");
                                    isReplacing = false;
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "encrypt failed", e);
                                uiHandler.post(() -> {
                                    showToast("שגיאה בהצפנה");
                                    isReplacing = false;
                                });
                            }
                        });
                    }
                }
                root.recycle();
            }
        }

        // 3) הצגת האוברליי כאשר נכנסים ל־WA
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (inWA && !overlayMgr.isShown()) {
                overlayMgr.showOverlay(
                        // כפתור תמונה
                        v -> {
                            Intent proxy = new Intent(this, ImagePickerProxyActivity.class);
                            proxy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(proxy);
                        },
                        // סגירה
                        v -> overlayMgr.hideOverlay(),
                        // (לצורך פענוח מאוחר)
                        v -> {/* … */}
                );
            } else if (!inWA && overlayMgr.isShown()) {
                overlayMgr.hideOverlay();
            }
        }
    }

    /** נקרא מ־ImagePickerProxyActivity */
    /** נקרא מ־ImagePickerProxyActivity */
    public void handlePickerResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != OverlayManager.REQ_IMG
                || resultCode   != Activity.RESULT_OK
                || data == null
                || data.getData() == null) return;

        // 1) הסרת ה-overlay מיד!
        overlayMgr.hideOverlay();

        try {
            Uri uri = data.getData();
            Bitmap orig = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);

            // 2) המרת הביטמפ לבייס64 (EXIF)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            orig.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

            // 3) הפעלת "פילטר הצפנה" לדוגמה
            Bitmap encrypted = EncryptionUtils.applyEncryptionFilter(orig);

            // 4) שמירת הקובץ מחדש עם EXIF
            Uri savedUri = EncryptionUtils.saveBitmap(this, encrypted);

            // 5) בניית ה-share Intent ל־WhatsApp
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, savedUri)
                    // חשוב לתת קריאה חדשה מה-Service
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            // אם יש לנו את ה-package האחרון
            if (lastWhatsAppPackage != null) {
                share.setPackage(lastWhatsAppPackage);
            }

            // 6) הפעלת האינטנט מתוך ה-Service
            startActivity(share);

        } catch (Exception e) {
            Log.e(TAG, "handlePickerResult failed", e);
        }
    }


    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private String randomFake(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        Random rnd = new Random();
        while (sb.length() < len) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }
    public String getLastWhatsAppPackage() {
        return lastWhatsAppPackage;
    }
    public OverlayManager getOverlayManager() {
        return overlayMgr;
    }
    private void showToast(String msg) {
        uiHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
