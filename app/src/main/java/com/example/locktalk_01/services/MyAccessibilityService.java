package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.activities.ImagePickerProxyActivity;
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PERSONAL_CODE_PREF = "personalCode";
    private static final String PREF_FAKE_MAP = "FakeCipherMap";

    private AndroidKeystorePlugin keystorePlugin;
    private OverlayManager overlayManager;
    private Handler mainHandler;
    private AlertDialog activeDialog;
    private boolean decryptAuthenticated = false;
    private long decryptExpiryTimestamp;

    private long lastDecryptTs = 0;
    private static final long DECRYPT_THROTTLE_MS = 1000;

    private boolean isReplacing = false;
    private boolean overlayHiddenByUser = false;
    private String lastWhatsAppPackage;
    private static MyAccessibilityService instance;
    private ExecutorService executor;

    private String pendingImageUri = null;

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        keystorePlugin = new AndroidKeystorePlugin(this);
        overlayManager = new OverlayManager(this, (WindowManager) getSystemService(WINDOW_SERVICE));
        executor = Executors.newSingleThreadExecutor();
        checkOverlayPermission();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    public void setEncryptedImageUri(String uri) {
        pendingImageUri = uri;
        getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                .edit()
                .putString("pendingImageUri", uri)
                .apply();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{
                "com.whatsapp",
                "com.whatsapp.w4b",
                "com.gbwhatsapp",
                "com.whatsapp.plus",
                "com.yowhatsapp"
        };
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        if (pendingImageUri == null) {
            pendingImageUri = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                    .getString("pendingImageUri", null);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();
        if (WhatsAppUtils.isWhatsAppPackage(pkg)) {
            lastWhatsAppPackage = pkg;
        }
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        int type = event.getEventType();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        long now = System.currentTimeMillis();
        boolean decryptOn = decryptAuthenticated && now <= decryptExpiryTimestamp;

        // 0) ResolverActivity בחירת WhatsApp אוטומטי
        if (pkg.equals("android")
                && cls.equals("com.android.internal.app.ResolverActivity")
                && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            List<AccessibilityNodeInfo> waIcons =
                    root.findAccessibilityNodeInfosByText("WhatsApp");
            if (waIcons != null) {
                for (AccessibilityNodeInfo icon : waIcons) {
                    if (icon.isClickable()) {
                        icon.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        break;
                    }
                }
            }
            root.recycle();
            return;
        }

        // 1) הצפנה אוטומטית כאשר מסתיימים ב־$
        if (!isReplacing
                && WhatsAppUtils.isWhatsAppPackage(pkg)
                && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleAutoEncrypt();
        }

        // 2) פענוח בלוני צ'אט (טקסט + תמונות)
        if (decryptOn
                && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                && now - lastDecryptTs > DECRYPT_THROTTLE_MS) {
            lastDecryptTs = now;
            decryptChatBubbles();
        }

        // 3) פענוח תמונה במסך מלא (Full-Screen Preview)
        if (decryptOn && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isFullScreenImageView(cls)) {
                decryptFullScreenImage(root); // מעביר root ולא event
            }
        }

        // 4) Overlay בחר תמונה/פענוח - ניהול הצגה/הסתרה
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean inWA = WhatsAppUtils.isWhatsAppPackage(pkg);
            boolean isFullScreenImage = isFullScreenImageView(cls);
            boolean shouldShow = ((inWA || decryptOn) && !(overlayHiddenByUser && !decryptOn));

            if (isFullScreenImage) {
                if (decryptOn) {
                    decryptFullScreenImage(root); // מציג שכבת תמונה, לא מסתיר overlay!
                    // *** לא לסגור overlay או timer ***
                }
                // שימי לב - אל תמשיכי ל-else כאן!
                return;
            }

            if (shouldShow && !overlayManager.isShown()) {
                overlayManager.show(
                        v -> {
                            Intent pick = new Intent(this, ImagePickerProxyActivity.class);
                            pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(pick);
                        },
                        v -> {
                            decryptAuthenticated = false;
                            overlayHiddenByUser = false;
                            overlayManager.stopTimer();
                            overlayManager.clearDecryptOverlays();
                            overlayManager.hide();
                        },
                        v -> showDecryptAuthDialog()
                );
                overlayManager.updatePosition(
                        dpToPx(36),
                        dpToPx(56) - mmToPx(5)
                );
            } else if ((!shouldShow || overlayHiddenByUser) && overlayManager.isShown()) {
                // *** לא לסגור אם decryptOn עדיין פעיל! ***
                if (!decryptOn) {
                    decryptAuthenticated = false;
                    overlayHiddenByUser = false;
                    overlayManager.clearDecryptOverlays();
                    overlayManager.hide();
                }
            }
            if (!WhatsAppUtils.isWhatsAppPackage(pkg) && !decryptOn) {
                decryptAuthenticated = false;
                overlayHiddenByUser = false;
            }
        }




        // 5) לחץ אוטומטית על שיחה ראשונה במסך ContactPicker
        if (WhatsAppUtils.isWhatsAppPackage(pkg)
                && cls.equals("com.whatsapp.contact.ui.picker.ContactPicker")
                && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            List<AccessibilityNodeInfo> convs =
                    root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_row");
            if (convs != null && !convs.isEmpty()) {
                convs.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        // 6) לחץ אוטומטית על Send במסך ה-Conversation
        if (WhatsAppUtils.isWhatsAppPackage(pkg)
                && cls.equals("com.whatsapp.Conversation")
                && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            List<AccessibilityNodeInfo> sendBtns =
                    root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
            if (sendBtns != null && !sendBtns.isEmpty()) {
                sendBtns.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        root.recycle();
    }

    @Override
    public void onInterrupt() {

    }

    // === חדש: פונקציה בודקת מסך תמונה מלאה (לא event, אלא class name בלבד)
    private boolean isFullScreenImageView(String cls) {
        return cls.contains("ImagePreviewActivity")
                || cls.contains("FullImageActivity")
                || cls.contains("ViewImageActivity")
                || cls.contains("GalleryActivity");
    }

    // === חדש: מוצא את ה-ImageView הכי גדול במסך
    private Rect findFullscreenImageRect(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Rect largest = null;
        int largestArea = 0;
        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            if ("android.widget.ImageView".equals(n.getClassName())) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int area = b.width() * b.height();
                if (area > largestArea && b.width() > 400 && b.height() > 400) {
                    largest = new Rect(b);
                    largestArea = area;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
            n.recycle();
        }
        return largest;
    }

    // === מתוקן: מקבל root, לא event
    private void decryptFullScreenImage(AccessibilityNodeInfo root) {
        if (pendingImageUri == null) {
            pendingImageUri = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                    .getString("pendingImageUri", null);
        }
        if (pendingImageUri == null) return;

        SharedPreferences prefsLock = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
        String key = "origPath_for_" + pendingImageUri;
        String origPath = prefsLock.getString(key, null);

        // **בדוק אם יש origPath, אחרת לא מציג כלום!**
        if (origPath == null) return;

        Bitmap bmp = BitmapFactory.decodeFile(origPath);
        if (bmp == null) return;

        Rect imageBounds = findFullscreenImageRect(root);
        if (imageBounds == null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            imageBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
        final Rect finalBounds = imageBounds;
        mainHandler.post(() ->
                overlayManager.showDecryptedImageOverlay(bmp, finalBounds)
        );
    }

// ... (כל הקוד שלך עד decryptChatBubbles)

    private void decryptChatBubbles() {
        Log.d("MyAccessibilityService", "decryptChatBubbles CALLED!");
        lastDecryptTs = System.currentTimeMillis();

        List<Pair<String, Rect>> txtList = new ArrayList<>();
        List<Pair<Uri, Rect>> imgList = new ArrayList<>();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            // --- טקסט ---
            for (AccessibilityNodeInfo n : WhatsAppUtils.findEncryptedMessages(root)) {
                if (!n.isEditable()) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    CharSequence t = n.getText();
                    if (t != null) {
                        txtList.add(new Pair<>(t.toString(), b));
                        Log.d("MyAccessibilityService", "Found encrypted text bubble: " + t + " bounds=" + b);
                    }
                }
                n.recycle();
            }

            // --- תמונות ---
            SharedPreferences prefsLock = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);

            for (Pair<AccessibilityNodeInfo, Rect> pair : WhatsAppUtils.findImageBubbleButtons(root)) {
                AccessibilityNodeInfo n = pair.first;
                Rect b = pair.second;
                Log.d("MyAccessibilityService", "Found potential imageView at: " + b.toShortString());

                if (n == null) continue;
                Uri imageUri = null;
                try {
                    imageUri = WhatsAppUtils.getImageUriFromNode(n);
                    Log.d("MyAccessibilityService", "ImageUri from node: " + imageUri);
                } catch (Exception e) {
                    Log.e("MyAccessibilityService", "Failed to get imageUri from node", e);
                }
                if (imageUri == null && pendingImageUri != null) {
                    imageUri = Uri.parse(pendingImageUri);
                    Log.d("MyAccessibilityService", "Fallback to pendingImageUri: " + imageUri);
                }
                if (imageUri != null) {
                    // **סינון: הוסף רק אם יש מיפוי לקובץ מקורי (תמונה מוצפנת)**
                    String key = "origPath_for_" + imageUri.toString();
                    String origPath = prefsLock.getString(key, null);
                    if (origPath != null) {
                        imgList.add(new Pair<>(imageUri, b));
                        Log.d("MyAccessibilityService", "Added ENCRYPTED image bubble: uri=" + imageUri + " bounds=" + b);
                    } else {
                        Log.d("MyAccessibilityService", "Skipped NON-encrypted image bubble: uri=" + imageUri);
                    }
                }
                n.recycle();
            }
            root.recycle();
        }

        mainHandler.post(overlayManager::clearDecryptOverlays);

        executor.execute(() -> {
            SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
            SharedPreferences creds = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            SharedPreferences prefsLock = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);

            // --- טקסט ---
            for (Pair<String, Rect> p : txtList) {
                String fake = p.first;
                String actualCipher = fakeMap.getString(fake, null);
                if (actualCipher != null) {
                    try {
                        String code = creds.getString(PERSONAL_CODE_PREF, "");
                        String plain = keystorePlugin.loadDecryptedMessage(code, actualCipher);
                        if (plain != null) {
                            Rect bounds = p.second;
                            mainHandler.post(() ->
                                    overlayManager.showDecryptedOverlay(plain, bounds)
                            );
                            Log.d("MyAccessibilityService", "Text decrypted: " + plain);
                        }
                    } catch (Exception e) {
                        Log.e("MyAccessibilityService", "Text decrypt error", e);
                    }
                }
            }
            // --- תמונה ---
            for (Pair<Uri, Rect> p : imgList) {
                try {
                    String logoUriString = p.first.toString();
                    String key = "origPath_for_" + logoUriString;
                    String origPath = prefsLock.getString(key, null);

                    if (origPath != null) {
                        Bitmap bmp = overlayManager.decodeFileWithOrientation(origPath);
                        if (bmp != null) {
                            Rect bounds = p.second;
                            mainHandler.post(() ->
                                    overlayManager.showDecryptedImageOverlay(bmp, bounds)
                            );
                        }
                    }
                } catch (Exception e) {
                    Log.e("MyAccessibilityService", "image decrypt error", e);
                }
            }
        });
    }

    private void handleAutoEncrypt() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String cs = WhatsAppUtils.getWhatsAppInputText(root);
        root.recycle();
        if (!cs.endsWith("$")) return;

        String orig = cs.substring(0, cs.length() - 1);
        if (orig.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String code = prefs.getString(PERSONAL_CODE_PREF, "");
        if (code.isEmpty()) {
            showToast("נא להגדיר קוד אישי");
            return;
        }

        isReplacing = true;
        mainHandler.postDelayed(() -> isReplacing = false, 500);

        executor.execute(() -> {
            try {
                String actualCipher = keystorePlugin.encryptToString(code, orig);
                String fake = generateFakeEncryptedText(actualCipher.length());
                getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE)
                        .edit().putString(fake, actualCipher).apply();

                mainHandler.post(() -> {
                    AccessibilityNodeInfo r2 = getRootInActiveWindow();
                    if (r2 != null) {
                        TextInputUtils.performTextReplacement(
                                MyAccessibilityService.this, r2, fake
                        );
                        r2.recycle();
                    }
                    showToast("הודעה מוצפנת");
                });
            } catch (Exception e) {
                Log.e(TAG, "encrypt error", e);
                mainHandler.post(() -> showToast("שגיאה בהצפנה"));
            }
        });
    }

    // שאר הפונקציות (Dialog וכו') - ללא שינוי

    private void showDecryptAuthDialog() {
        if (overlayManager.isShown()) overlayManager.hide();
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        if (decryptAuthenticated && System.currentTimeMillis() <= decryptExpiryTimestamp) {
            showDecryptDurationDialog();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("הזן קוד אימות");
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("קוד פיענוח"))
                .setView(input)
                .setPositiveButton("אישור", (d, w) -> {
                    String ent = input.getText().toString();
                    String saved = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                            .getString(PERSONAL_CODE_PREF, "");
                    if (ent.equals(saved)) {
                        decryptAuthenticated = true;
                        overlayHiddenByUser = false;
                        showToast("אומת בהצלחה");
                        showDecryptDurationDialog();
                    } else {
                        showToast("קוד שגוי");
                    }
                })
                .setNegativeButton("ביטול", null)
                .create();
        configureDialogWindow(dlg);
        dlg.show();
        activeDialog = dlg;
    }

    private void showMainOverlay() {
        overlayManager.show(
                v -> {
                    Intent pick = new Intent(this, ImagePickerProxyActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(pick);
                },
                v -> {
                    decryptAuthenticated = false;
                    overlayHiddenByUser = false;
                    overlayManager.stopTimer();
                    overlayManager.clearDecryptOverlays();
                    overlayManager.hide();
                },
                v -> showDecryptAuthDialog()
        );
        overlayManager.updatePosition(dpToPx(36), dpToPx(56) - mmToPx(5));
    }

    private void showDecryptDurationDialog() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();

        String[] items = {"1 דקה", "5 דקות", "10 דקות", "15 דקות", "30 דקות", "שעה"};
        int[] mins = {1, 5, 10, 15, 30, 60};
        final int[] sel = {-1};

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("משך פיענוח"))
                .setSingleChoiceItems(items, -1, (d, w) -> sel[0] = w)
                .setPositiveButton("הפעל", (d, w) -> {
                    if (sel[0] < 0) {
                        showToast("לא נבחר משך זמן");
                        return;
                    }
                    decryptExpiryTimestamp =
                            System.currentTimeMillis() + mins[sel[0]] * 60_000L;
                    showToast("פיענוח פעיל: " + items[sel[0]]);
                    overlayHiddenByUser = false;
                    showMainOverlay();
                    overlayManager.startTimer(
                            decryptExpiryTimestamp,
                            () -> {
                                decryptAuthenticated = false;
                                overlayManager.clearDecryptOverlays();
                                overlayManager.hide();
                            }
                    );
                })
                .setNegativeButton("ביטול", null)
                .create();
        configureDialogWindow(dlg);
        dlg.show();
        activeDialog = dlg;
    }

    private TextView makeCenteredTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dpToPx(8), 0, dpToPx(8));
        return t;
    }

    private void configureDialogWindow(AlertDialog dlg) {
        WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
        lp.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        lp.dimAmount = 0f;
        lp.gravity = Gravity.CENTER;
        dlg.getWindow().setAttributes(lp);
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            mainHandler.post(() -> {
                showToast("נא לאפשר הצגה מעל אפליקציות");
                Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            });
        }
    }

    private String generateFakeEncryptedText(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int mmToPx(float mm) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(mm * dm.xdpi / 25.4f);
    }

    public String getLastWhatsAppPackage() {
        return lastWhatsAppPackage;
    }
}
