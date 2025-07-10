package com.example.locktalk_01.services;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import com.example.locktalk_01.utils.FirebaseUserUtils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.activities.ImagePickerProxyActivity;
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.EncryptionHelper;
import com.example.locktalk_01.utils.ImageStorageHelper;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.spec.SecretKeySpec;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PERSONAL_CODE_PREF = "personalCode";
    private static final String PREF_FAKE_MAP = "FakeCipherMap";

    private AndroidKeystorePlugin keystorePlugin;
    public OverlayManager overlayManager;
    private Handler mainHandler;
    private AlertDialog activeDialog;
    private boolean decryptAuthenticated = false;
    private long decryptExpiryTimestamp;

    private long lastDecryptTs = 0;
    private static final long DECRYPT_THROTTLE_MS = 150; // 6-7 פעמים בשנייה

    private boolean isReplacing = false;
    private boolean overlayHiddenByUser = false;
    private String lastWhatsAppPackage;
    private static MyAccessibilityService instance;
    private ExecutorService executor;

    private String pendingImageUri = null;

    // ---  פונקציית retry שמריצה decryptChatBubbles רק כשהחלון WhatsApp ---
    private Handler tryDecryptHandler = new Handler(Looper.getMainLooper());
    private int tryDecryptAttempts = 0;
    private static volatile boolean imagePickerActive = false;

    public static void setImagePickerActive(boolean active) {
        imagePickerActive = active;
    }

    public static boolean isImagePickerActive() {
        return imagePickerActive;
    }


    private void tryDecryptWithRetry() {
        tryDecryptAttempts = 0;
        tryDecryptInternal();
    }

    private void tryDecryptInternal() {
        tryDecryptAttempts++;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean canTry = false;
        if (root != null && root.getPackageName() != null) {
            String pkg = root.getPackageName().toString();
            canTry = pkg.contains("com.whatsapp");
        }
        if (canTry) {
            decryptChatBubbles();
        } else if (tryDecryptAttempts < 10) {
            tryDecryptHandler.postDelayed(this::tryDecryptInternal, 300);
        }
        if (root != null) root.recycle();
    }
    public static MyAccessibilityService getInstance() {
        return instance;
    }

    private Handler overlayHidePollHandler = new Handler(Looper.getMainLooper());
    private Runnable overlayHidePollRunnable;

    private void pollOverlayHideAfterExit() {
        if (overlayHidePollRunnable != null) {
            overlayHidePollHandler.removeCallbacks(overlayHidePollRunnable);
        }
        overlayHidePollRunnable = new Runnable() {
            int checks = 0;
            @Override
            public void run() {
                checks++;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                String pkg = null;
                if (root != null && root.getPackageName() != null)
                    pkg = root.getPackageName().toString();
                if (pkg == null || !WhatsAppUtils.isWhatsAppPackage(pkg)) {
                    if (overlayManager != null && overlayManager.isShown()) {
                        overlayManager.hide();
                        stopContinuousDecryption();
                    }
                } else if (checks < 6) { // עד 3 שניות
                    overlayHidePollHandler.postDelayed(this, 500);
                }
                if (root != null) root.recycle();
            }
        };
        overlayHidePollHandler.post(overlayHidePollRunnable);
    }


    public void setEncryptedImageUri(String uri) {
        pendingImageUri = uri;
        getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                .edit()
                .putString("pendingImageUri", uri)
                .apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        keystorePlugin = new AndroidKeystorePlugin(this);
        overlayManager = new OverlayManager(this);
        executor = Executors.newSingleThreadExecutor();
        checkOverlayPermission();
        // Restore pending image if service recreated:
        if (pendingImageUri == null) {
            pendingImageUri = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                    .getString("pendingImageUri", null);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        imagePickerActive = false;
        instance = null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{
                "com.whatsapp", "com.whatsapp.w4b", "com.gbwhatsapp", "com.whatsapp.plus", "com.yowhatsapp"
        };
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getPackageName() == null) return;

            String pkg = event.getPackageName().toString();
            // -- הוספה: יציאה מלאה מהאפליקציה -- //
            if (!WhatsAppUtils.isWhatsAppPackage(pkg)) {
                if (overlayManager != null && overlayManager.isShown()) {
                    overlayManager.hide();
                    stopContinuousDecryption();
                }
                stopContinuousDecryption();    // <-- להוסיף כאן (גם אם overlay כבר מוסתר)
                pollOverlayHideAfterExit(); // <-- הוסיפי את השורה הזו כאן!

                return;
            }
            if (WhatsAppUtils.isWhatsAppPackage(pkg)) {
                lastWhatsAppPackage = pkg;
            }
            String cls = event.getClassName() != null ? event.getClassName().toString() : "";
            int type = event.getEventType();
            Log.d("LT_CLASS_DEBUG", "pkg=" + pkg + ", cls=" + cls + ", type=" + type);

            AccessibilityNodeInfo root = getRootInActiveWindow();

            // ------------- הגנה: לא להציג פיענוחים/כפתור במסכים אסורים -------------
            boolean forbidden = !WhatsAppUtils.isWhatsAppPackage(pkg)
                    || pkg.equals(getPackageName())
                    || shouldHideDecryptionOverlays();

            if (forbidden) {
                if (overlayManager.isShown()) {
                    if (!WhatsAppUtils.isWhatsAppPackage(pkg)
                            && !pkg.equals(getPackageName())
                            && !pkg.toLowerCase().contains("systemui")
                            && !pkg.toLowerCase().contains("com.android")
                            && !pkg.toLowerCase().contains("resolver")
                            && !ImagePickerProxyActivity.isDialogOpen()) {
                        overlayManager.hide();
                        stopContinuousDecryption();
                    } else {
                        overlayManager.hideBubblesOnly();
                    }
                }
                if (root != null) root.recycle();
                return;
            }


            // ------- המשך קוד רגיל שלך, לא נוגע בזה ------

            long now = System.currentTimeMillis();
            boolean decryptOn = decryptAuthenticated && now <= decryptExpiryTimestamp;

            // [0] בוחר אפליקציה: לוחץ אוטומטית על וואטסאפ אם צריך
            if (pkg.equals("android")
                    && cls.equals("com.android.internal.app.ResolverActivity")
                    && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (root != null) {
                    List<AccessibilityNodeInfo> waIcons = root.findAccessibilityNodeInfosByText("WhatsApp");
                    if (waIcons != null) {
                        for (AccessibilityNodeInfo icon : waIcons) {
                            if (icon.isClickable()) {
                                icon.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                break;
                            }
                        }
                    }
                    root.recycle();
                }
                return;
            }

            // [1] הצפנה אוטומטית בטקסט
            if (!isReplacing
                    && WhatsAppUtils.isWhatsAppPackage(pkg)
                    && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                handleAutoEncrypt();
            }

            // [2] פיענוח בועות/תמונות (אם פיענוח פעיל)
            if (decryptOn
                    && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                    && now - lastDecryptTs > DECRYPT_THROTTLE_MS) {
                lastDecryptTs = now;
                decryptChatBubbles();
            }

            // [3] פיענוח במסך תמונה מלאה
            if (decryptOn && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isFullScreenImageView(cls)) {
                decryptFullScreenImage(root);
                if (overlayManager.isShown()) overlayManager.hide();
                if (root != null) root.recycle();
                return;
            }

            // [4] Overlay: הצגה/הסתרה של כפתורים רק בשיחת וואטסאפ רגילה
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                boolean inWA = WhatsAppUtils.isWhatsAppPackage(pkg);
                boolean isConversationScreen = inWA && isConversationScreen(cls, root);
                boolean isFullScreenImage = isFullScreenImageView(cls);
                boolean shouldShow = isConversationScreen && !overlayHiddenByUser;

                Log.d("LT_OVERLAY", "inWA=" + inWA +
                        " isConv=" + isConversationScreen +
                        " shouldShow=" + shouldShow +
                        " overlayShown=" + overlayManager.isShown());

                if (isFullScreenImage) {
                    if (decryptOn) decryptFullScreenImage(root);
                    if (overlayManager.isShown()) overlayManager.hide();
                    if (root != null) root.recycle();
                    return;
                }
                if (!isReallyInWhatsApp()) {
                    if (overlayManager != null) overlayManager.hide();
                    stopContinuousDecryption();
                    return;
                }
                if (shouldShow) {
                    if (!overlayManager.isShown()) {
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
                        overlayManager.updateToTopCenter();
                    }
                    overlayManager.updateOverlaysVisibility(); // קריטי
                } else if (overlayManager.isShown()) {
                    overlayManager.hide();

                }

            }

            // [5] אוטומציה למסך בחירת אנשי קשר
            if (WhatsAppUtils.isWhatsAppPackage(pkg)
                    && cls.equals("com.whatsapp.contact.ui.picker.ContactPicker")
                    && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (root != null) {
                    List<AccessibilityNodeInfo> convs =
                            root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_row");
                    if (convs != null && !convs.isEmpty()) {
                        convs.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }

            // [6] אוטומציה לשליחה במסך שיחה (אם רלוונטי לך)
            if (WhatsAppUtils.isWhatsAppPackage(pkg)
                    && cls.equals("com.whatsapp.Conversation")
                    && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (root != null) {
                    List<AccessibilityNodeInfo> sendBtns =
                            root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (sendBtns != null && !sendBtns.isEmpty()) {
                        sendBtns.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }

            if (root != null) root.recycle();
        } catch (Exception e) {
            Log.e("MyAccessibilityService", "onAccessibilityEvent exception", e);
        }
    }

    private boolean decryptAlwaysOn = false;
    private Handler decryptHandler = new Handler(Looper.getMainLooper());
    private Runnable decryptRunnable;
    public void startContinuousDecryption() {
        decryptAlwaysOn = true;
        if (decryptRunnable == null) {
            decryptRunnable = new Runnable() {
                @Override
                public void run() {
                    if (decryptAlwaysOn && decryptAuthenticated) {
                        tryDecryptWithRetry();
                        decryptHandler.postDelayed(this, 1000); // כל שניה
                    }
                }
            };
        }
        tryDecryptWithRetry(); // פיענוח מיידי!
        decryptHandler.post(decryptRunnable);
    }
    public void stopContinuousDecryption() {
        decryptAlwaysOn = false;
        decryptAuthenticated = false;
        decryptHandler.removeCallbacksAndMessages(null); // <-- מסיר הכל, גם runnable, גם delays עתידיים
        if (overlayManager != null) {
            overlayManager.clearDecryptOverlays();
            overlayManager.hide();
        }
    }

    @Override
    public void onInterrupt() {

    }

    // === חדש: פונקציה בודקת מסך תמונה מלאה (לא event, אלא class name בלבד)
    private boolean isFullScreenImageView(String cls) {
        return cls.contains("ImagePreviewActivity")
                || cls.contains("FullImageActivity")
                || cls.contains("ViewImageActivity")
                || cls.contains("GalleryActivity")
                || cls.toLowerCase().contains("photo")
                || cls.toLowerCase().contains("image")
                || cls.toLowerCase().contains("media");
    }


    // === מוצא את ה-ImageView הכי גדול במסך ===
    private Rect findFullscreenImageRect(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Rect largest = null;
        int largestArea = 0;
        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;

            if ("android.widget.ImageView".contentEquals(n.getClassName())) {
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
            // אין recycle על root
            if (n != root) n.recycle();
        }
        return largest;
    }

    // ============================
// השגת טלפון עבור שיחה, עם קאש והרשאה
    private String findAndCachePhoneForChat(Context context, String chatTitle) {
        if (chatTitle == null) return null;
        String normalizedTitle = WhatsAppUtils.normalizeChatTitle(chatTitle);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            mainHandler.post(() -> showToast("יש לאשר הרשאת אנשי קשר לפיענוח"));
            return null;
        }

        // בדוק קאש קודם (מנורמל)
        String phone = context.getSharedPreferences("PeerNames", MODE_PRIVATE)
                .getString(normalizedTitle, null);
        if (phone != null) return WhatsAppUtils.normalizePhone(phone);

        // נסה לשלוף מאנשי קשר
        String phoneFromContacts = WhatsAppUtils.findPhoneInContacts(context, normalizedTitle);
        if (phoneFromContacts != null) {
            String normalizedPhone = WhatsAppUtils.normalizePhone(phoneFromContacts);
            context.getSharedPreferences("PeerNames", MODE_PRIVATE)
                    .edit()
                    .putString(normalizedTitle, normalizedPhone)
                    .apply();
            return normalizedPhone;
        }
        return null;
    }

    private boolean isRetryingDecrypt = false;

    private volatile boolean decryptRunning = false;
    private void decryptFullScreenImage(AccessibilityNodeInfo root) {
        com.example.locktalk_01.utils.FirebaseUserUtils.checkUserPhoneMatch(this, isMatch -> {
            if (!isMatch) {
                mainHandler.post(() -> showToast("פיענוח לא אפשרי – היוזר לא תואם לחשבון!"));
                if (overlayManager != null) overlayManager.hide();
                stopContinuousDecryption();
                return;
            }

            Rect imageBounds = findFullscreenImageRect(root);
            if (imageBounds == null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                imageBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
            }

            List<Pair<String, Rect>> imgLabels = WhatsAppUtils.findImageLabels(root);
            String imgLabel = imgLabels.isEmpty() ? null : imgLabels.get(imgLabels.size() - 1).first;

            if (imgLabel == null) {
                Log.d(TAG, "No imgLabel found for fullscreen image");
                return;
            }

            SharedPreferences prefsLock = getApplicationContext().getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);

            String origPath = prefsLock.getString("origPath_for_" + imgLabel, null);

            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
            String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);

            SharedPreferences creds = getApplicationContext().getSharedPreferences("UserCredentials", MODE_PRIVATE);
            String myPhone = creds.getString("myPhone", null);

            Log.d(TAG, "decryptFullScreenImage: imgLabel=" + imgLabel + ", origPath=" + origPath + ", myPhone=" + myPhone + ", peerPhone=" + peerPhone);

            // חיפוש נוסף של peerPhone אם לא קיים
            if ((peerPhone == null || peerPhone.length() < 7) && chatTitle != null && chatTitle.length() > 0) {
                peerPhone = findAndCachePhoneForChat(this, chatTitle);
                if (peerPhone != null) {
                    getApplicationContext().getSharedPreferences("PeerNames", MODE_PRIVATE)
                            .edit().putString(chatTitle.trim(), peerPhone).apply();
                } else {
                    mainHandler.post(() -> showToast("לא נמצא מספר טלפון עבור '" + chatTitle + "' באנשי קשר"));
                    return;
                }
            }

            if (origPath == null) {
                Log.d(TAG, "No origPath mapped for label: " + imgLabel);
                return;
            }
            if (myPhone == null || peerPhone == null) {
                Log.e(TAG, "Missing phone numbers! myPhone=" + myPhone + ", peerPhone=" + peerPhone);
                mainHandler.post(() -> showToast("לא נמצאו מספרים לפענוח תמונה"));
                return;
            }

            // *** התמיכה בפייק: בדוק אם origPath הוא לא path לקובץ (כלומר אין קובץ כזה) ***
            java.io.File file = new java.io.File(origPath);
            if (!file.exists() || origPath.endsWith(".fakeimg")) {
                // אם הערך ב־origPath הוא "fake" – נסה קודם ב־SharedPreferences, ואם לא – תביא מה־Firebase
                String actualCipher = fakeMap.getString(origPath, null);
                if (actualCipher != null) {
                    // יש במכשיר: ננסה לפענח
                    decryptAndShowImageFromCipher(actualCipher, myPhone, peerPhone, imageBounds, origPath);
                    return;
                } else {
                    // לא נמצא לוקלית – ננסה מהענן (פונקציה ב-async)
                    String finalPeerPhone = peerPhone;
                    Rect finalImageBounds = imageBounds;
                    fetchFakeMappingFromFirebase(myPhone, peerPhone, origPath, new FakeMapFetchCallback() {
                        @Override
                        public void onResult(String cipherFromCloud) {
                            if (cipherFromCloud != null) {
                                // נשמור גם לוקלית (תמיד כדאי!)
                                fakeMap.edit().putString(origPath, cipherFromCloud).apply();
                                decryptAndShowImageFromCipher(cipherFromCloud, myPhone, finalPeerPhone, finalImageBounds, origPath);
                            } else {
                                mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - לא נמצא מוצפן בענן"));
                            }
                        }
                    });
                    return;
                }
            }

            // === תמונה רגילה (מקובץ דיסק) ===
            try {
                byte[] encBytes = com.example.locktalk_01.utils.FileUtils.readBytesFromFile(origPath);
                Log.d(TAG, "decryptFullScreenImage: read " + (encBytes != null ? encBytes.length : 0) + " bytes from " + origPath);

                byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, myPhone, peerPhone);

                if (plainBytes != null) {
                    Bitmap origBmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                    if (origBmp != null) {
                        Log.d(TAG, "decryptFullScreenImage: SUCCESS!");
                        final Rect finalBounds = imageBounds;
                        final String imageId = origPath;
                        mainHandler.post(() -> {
                            if (!isReallyInWhatsApp()) {
                                if (overlayManager != null) overlayManager.hide();
                                stopContinuousDecryption();
                                return;
                            }
                            overlayManager.showDecryptedImageOverlay(origBmp, finalBounds, imageId);
                        });
                        return;
                    } else {
                        Log.e(TAG, "decryptFullScreenImage: Failed to decode bitmap from decrypted bytes");
                        mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - קובץ לא תקין"));
                    }
                } else {
                    Log.e(TAG, "decryptFullScreenImage: Decryption failed!");
                    mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - מפתח לא תקין"));
                }

            } catch (Exception e) {
                Log.e(TAG, "Full image decryption error", e);
                mainHandler.post(() -> showToast("שגיאה בפענוח תמונה"));
            }
        });
    }

    // פונקציה עזר לפענוח תמונה ממחרוזת base64 מוצפנת (לא מקובץ פיזי)
    private void decryptAndShowImageFromCipher(String base64Cipher, String myPhone, String peerPhone, Rect imageBounds, String imageId) {
        try {
            byte[] encBytes = android.util.Base64.decode(base64Cipher, android.util.Base64.NO_WRAP);
            byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, myPhone, peerPhone);
            if (plainBytes != null) {
                Bitmap origBmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                if (origBmp != null) {
                    mainHandler.post(() -> {
                        if (!isReallyInWhatsApp()) {
                            if (overlayManager != null) overlayManager.hide();
                            stopContinuousDecryption();
                            return;
                        }
                        overlayManager.showDecryptedImageOverlay(origBmp, imageBounds, imageId);
                    });
                } else {
                    mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - קובץ לא תקין"));
                }
            } else {
                mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - מפתח לא תקין"));
            }
        } catch (Exception e) {
            Log.e(TAG, "decryptAndShowImageFromCipher error", e);
            mainHandler.post(() -> showToast("שגיאה בפענוח תמונה (base64)"));
        }
    }
    private void decryptChatBubbles() {
        com.example.locktalk_01.utils.FirebaseUserUtils.checkUserPhoneMatch(this, isMatch -> {
            if (!isMatch) {
                Log.w("LT_DECRYPT", "User phone mismatch with Firebase!");
                mainHandler.post(() -> {
                    showToast("פיענוח לא אפשרי – היוזר לא תואם לחשבון ב-Firebase!");
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                return;
            }

            if (decryptRunning) {
                Log.d("LT_DECRYPT", "decryptChatBubbles: Already running, skipping");
                return;
            }
            decryptRunning = true;
            Log.d("LT_DECRYPT", "decryptChatBubbles CALLED!");

            SharedPreferences creds = getApplicationContext().getSharedPreferences("UserCredentials", MODE_PRIVATE);
            String myPhone = creds.getString("myPhone", null);

            Log.d("LT_DECRYPT", "myPhone: " + myPhone);

            if (!isWhatsAppAccountPhoneMatches(myPhone)) {
                Log.w("LT_DECRYPT", "WhatsApp account phone does not match myPhone!");
                mainHandler.post(() -> {
                    showToast("פיענוח לא אפשרי - חשבון WhatsApp במכשיר לא תואם למשתמש!");
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                return;
            }

            if (!decryptAuthenticated || System.currentTimeMillis() > decryptExpiryTimestamp) {
                Log.d("LT_DECRYPT", "Decryption not authenticated or timer expired");
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                return;
            }

            lastDecryptTs = System.currentTimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String className = (root != null && root.getClassName() != null) ? root.getClassName().toString() : "";

            Log.d("LT_DECRYPT", "className: " + className);

            if (shouldHideDecryptionOverlays() || !isConversationScreen(className, root)) {
                Log.d("LT_DECRYPT", "Not in conversation screen or overlays should be hidden");
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                if (root != null) root.recycle();
                return;
            }

            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
            String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);

            Log.d("LT_DECRYPT", "chatTitle: " + chatTitle + " | peerPhone: " + peerPhone);

            SharedPreferences peerNames = getApplicationContext().getSharedPreferences("PeerNames", MODE_PRIVATE);
            String cachedPeerPhone = peerNames.getString(chatTitle != null ? chatTitle.trim() : "", null);

            if (peerPhone == null && cachedPeerPhone != null) {
                peerPhone = cachedPeerPhone;
            }

            if ((peerPhone == null || peerPhone.length() < 7) && chatTitle != null && chatTitle.length() > 0) {
                peerPhone = findAndCachePhoneForChat(this, chatTitle);
                Log.d("LT_DECRYPT", "After findAndCachePhoneForChat, peerPhone: " + peerPhone);
                if (peerPhone == null) {
                    mainHandler.post(() -> {
                        showToast("לא נמצא מספר טלפון עבור '" + chatTitle + "' באנשי קשר.");
                        overlayManager.cleanupAllBubbles();
                        decryptRunning = false;
                    });
                    if (root != null) root.recycle();
                    return;
                }
            }

            if (myPhone != null && peerPhone != null && myPhone.equals(peerPhone)) {
                if (chatTitle != null && !chatTitle.isEmpty()) {
                    String altPhone = findAndCachePhoneForChat(this, chatTitle);
                    Log.d("LT_DECRYPT", "Self chat detected. altPhone: " + altPhone);
                    if (altPhone != null && !altPhone.equals(myPhone)) {
                        peerPhone = altPhone;
                    }
                }
            }

            if (myPhone == null || peerPhone == null) {
                Log.w("LT_DECRYPT", "myPhone or peerPhone is null! Aborting decryption.");
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                if (root != null) root.recycle();
                return;
            }

            // שליפת בועות טקסט
            List<Pair<AccessibilityNodeInfo, Rect>> txtList = new ArrayList<>();
            List<Pair<AccessibilityNodeInfo, Rect>> imgNodes = WhatsAppUtils.findImageBubbleButtons(root);
            List<Pair<String, Rect>> imgLabels = WhatsAppUtils.findImageLabels(root);
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();

            for (AccessibilityNodeInfo n : WhatsAppUtils.findEncryptedMessages(root)) {
                if (!n.isEditable()) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (b.width() <= 0 || b.height() <= 0) {
                        b = new Rect(50, 300, displayMetrics.widthPixels - 50, 400);
                    }
                    CharSequence t = n.getText();
                    if (t != null) {
                        Log.d("LT_DECRYPT", "Found encrypted text bubble: " + t.toString());
                        txtList.add(new Pair<>(AccessibilityNodeInfo.obtain(n), b));
                    }
                    n.recycle();
                }
            }
            if (root != null) root.recycle();

            final List<Pair<AccessibilityNodeInfo, Rect>> finalTxtList = txtList;
            final List<Pair<AccessibilityNodeInfo, Rect>> finalImgNodes = imgNodes;
            final List<Pair<String, Rect>> finalImgLabels = imgLabels;
            final String peerPhoneFinal = peerPhone;

            executor.execute(() -> {
                if (!isReallyInWhatsApp()) {
                    Log.w("LT_DECRYPT", "No longer in WhatsApp. Stop decryption.");
                    mainHandler.post(() -> {
                        if (overlayManager != null) overlayManager.hide();
                        stopContinuousDecryption();
                    });
                    return;
                }

                SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
                SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);

                Set<String> wantedIds = new HashSet<>();

                // ===== פיענוח הודעות טקסט עם FakeMap =====
                for (Pair<AccessibilityNodeInfo, Rect> p : finalTxtList) {
                    final AccessibilityNodeInfo node = p.first;
                    final Rect bounds = p.second;
                    final CharSequence bubbleText = node.getText();
                    final boolean outgoing = isOutgoingBubble(node, bounds);

                    String cipherOrFake = (bubbleText != null) ? bubbleText.toString() : null;
                    if (cipherOrFake == null || cipherOrFake.length() < 10) {
                        Log.w("LT_DECRYPT", "Bubble too short or null: " + cipherOrFake);
                        mainHandler.post(node::recycle);
                        continue;
                    }

                    String actualCipher = fakeMap.contains(cipherOrFake)
                            ? fakeMap.getString(cipherOrFake, null)
                            : null;

                    Log.d("LT_DECRYPT", "TextBubble: " + cipherOrFake + " | outgoing=" + outgoing + " | actualCipher: " + actualCipher);

                    String id = OverlayManager.bubbleId(cipherOrFake, bounds, outgoing);
                    wantedIds.add(id);

                    String senderPhone = outgoing ? myPhone : peerPhoneFinal;
                    String receiverPhone = outgoing ? peerPhoneFinal : myPhone;

                    if (actualCipher == null) {
                        Log.d("LT_DECRYPT", "No mapping in fakeMap, try fetch from Firebase");
                        final AccessibilityNodeInfo nodeCopy = AccessibilityNodeInfo.obtain(node);
                        fetchFakeMappingFromFirebase(myPhone, peerPhoneFinal, cipherOrFake, new FakeMapFetchCallback(){
                            @Override
                            public void onResult(String cipherFromCloud) {
                                if (cipherFromCloud != null) {
                                    fakeMap.edit().putString(cipherOrFake, cipherFromCloud).apply();
                                    String plain = EncryptionHelper.decryptFromString(cipherFromCloud, senderPhone, receiverPhone);
                                    Log.d("LT_DECRYPT", "Result from Firebase, decrypted: " + plain);
                                    if (plain != null) {
                                        mainHandler.post(() -> {
                                            if (!isReallyInWhatsApp()) {
                                                if (overlayManager != null) overlayManager.hide();
                                                stopContinuousDecryption();
                                                nodeCopy.recycle();
                                                return;
                                            }
                                            overlayManager.showDecryptedOverlay(plain, nodeCopy, bounds, outgoing, id);
                                            nodeCopy.recycle();
                                        });
                                    } else {
                                        mainHandler.post(nodeCopy::recycle);
                                    }
                                } else {
                                    Log.w("LT_DECRYPT", "No mapping found in Firebase for: " + cipherOrFake);
                                    mainHandler.post(nodeCopy::recycle);
                                }
                            }
                        });
                        mainHandler.post(node::recycle); // משחרר את הנוד בלולאה המקורית
                        continue;
                    }

                    String plain = EncryptionHelper.decryptFromString(actualCipher, senderPhone, receiverPhone);
                    Log.d("LT_DECRYPT", "Decrypted: " + plain + " | using actualCipher: " + actualCipher + " | sender=" + senderPhone + " | receiver=" + receiverPhone);
                    if (plain != null) {
                        final AccessibilityNodeInfo nodeCopy = AccessibilityNodeInfo.obtain(node);
                        mainHandler.post(() -> {
                            if (!isReallyInWhatsApp()) {
                                if (overlayManager != null) overlayManager.hide();
                                stopContinuousDecryption();
                                nodeCopy.recycle();
                                return;
                            }
                            overlayManager.showDecryptedOverlay(plain, nodeCopy, bounds, outgoing, id);
                            nodeCopy.recycle();
                        });
                    } else {
                        Log.w("LT_DECRYPT", "Decryption failed (null) for: " + actualCipher);
                        mainHandler.post(node::recycle);
                    }
                }

                // פיענוח תמונות (שמרתי לוגים כמו בטקסט אם תרצה אוסיף)

                mainHandler.post(() -> overlayManager.cleanupBubblesExcept(wantedIds));
                mainHandler.post(() -> decryptRunning = false);

                // רפרש קטן (אם נדרש)
                if (!isRetryingDecrypt) {
                    isRetryingDecrypt = true;
                    mainHandler.postDelayed(() -> {
                        isRetryingDecrypt = false;
                        AccessibilityNodeInfo checkRoot = getRootInActiveWindow();
                        String checkClass = (checkRoot != null && checkRoot.getClassName() != null) ? checkRoot.getClassName().toString() : "";
                        if (!shouldHideDecryptionOverlays() && isConversationScreen(checkClass, checkRoot)) {
                            decryptChatBubbles();
                        }
                        if (checkRoot != null) checkRoot.recycle();
                    }, 220);
                }
            });
        });
    }


    public boolean isDecryptionTimerActive() {
        return decryptAuthenticated && System.currentTimeMillis() <= decryptExpiryTimestamp;
    }

    /** האם יש להחביא פיענוחים כרגע? (לא בשיחה, או בזמן צילום/בחירה, או בזמן הקלדה) */
    public boolean shouldHideDecryptionOverlays() {
        // אל תציג כלום אם דיאלוג הבחירה שלך פתוח!
        if (ImagePickerProxyActivity.isDialogOpen()) {
            Log.d("LT", "shouldHideDecryptionOverlays: ImagePickerProxyActivity dialog open");
            return true;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            Log.d("LT", "shouldHideDecryptionOverlays: root null");
            return true;
        }
        String currentPackage = root.getPackageName().toString();
        String currentClass = root.getClassName() != null ? root.getClassName().toString() : "";
        String lc = currentClass.toLowerCase();

        // רק בוואטסאפ (כל גרסה), אל תשתמש contains!
        if (!currentPackage.startsWith("com.whatsapp")) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp (currentPackage=" + currentPackage + ")");
            return true;
        }

        // חריג: מסך מדיה – כן להציג overlay
        if (isMediaViewerScreen(lc)) {
            Log.d("LT", "shouldHideDecryptionOverlays: ALLOW in media viewer (" + currentClass + ")");
            return false;
        }

        // מסכים/דיאלוגים אסורים (בתוך וואטסאפ)
        String[] forbiddenClasses = {
                // דיאלוגים ובוחרים:
                "chooser", "gallerypicker", "documentpicker", "filepicker", "imagepicker",
                "crop", "popup", "dialog", "menu", "picker", "alertdialog", "sheet", "fragmentdialog",
                // מחלקות בוואטסאפ:
                "mediapickeractivity", "cameraactivity", "attachmentsendactivity", "mediabrowseractivity", "videoplayeractivity",
                "profileinfoactivity", "status", "settingsactivity", "about", "help", "info", "invite", "market",
                // מקלדות:
                "inputmethod", "keyboard", "ime",
                // דיאלוג מערכת:
                "resolveractivity", "permissioncontrolleractivity",
                // מסכים לא קשורים:
                "contactpicker", "share"
        };
        for (String forbidden : forbiddenClasses) {
            if (lc.contains(forbidden)) {
                Log.d("LT", "shouldHideDecryptionOverlays: forbidden dialog/activity (" + currentClass + ")");
                return true;
            }
        }

        // בדיקת מקלדת פתוחה
        if (isKeyboardProbablyVisible(root)) {
            Log.d("LT", "shouldHideDecryptionOverlays: keyboardVisible");
            return true;
        }

        // מסך לא שיחה?
        if (!isConversationScreen(currentClass, root)) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp conversation screen (" + currentClass + ")");
            return true;
        }

        // ברירת מחדל: אל תסתיר
        Log.d("LT", "shouldHideDecryptionOverlays: overlay allowed (" + currentClass + ")");
        return false;
    }

    /** האם זה מסך תצוגת מדיה/תמונה/וידאו בוואטסאפ */
    private boolean isMediaViewerScreen(String lc) {
        return lc.contains("mediaviewactivity")
                || lc.contains("mediagalleryactivity")
                || lc.contains("galleryactivity")
                || lc.contains("mediaviewer")
                || lc.contains("imagepreviewactivity")
                || lc.contains("photoview")
                || lc.contains("fullimageactivity")
                || lc.contains("viewimageactivity")
                || lc.contains("imageview")
                || lc.contains("photo")
                || lc.contains("media")
                || lc.contains("image")
                || lc.contains("video");
    }
    public interface FakeMapFetchCallback {
        void onResult(String actualCipher);
    }
    public void fetchFakeMappingFromFirebase(String myPhone, String peerPhone, String fake, FakeMapFetchCallback callback) {
        String docId1 = EncryptionHelper.normalizePhone(myPhone).replace("+972", "");
        String docId2 = EncryptionHelper.normalizePhone(peerPhone).replace("+972", "");

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users").document(docId1).collection("fakeMap")
                .document(fake).get()
                .addOnSuccessListener(doc -> {
                    String cipher = extractCipherFromDoc(doc);
                    if (cipher != null && !cipher.isEmpty()) {
                        Log.d("LT_FIREBASE", "Found mapping in myPhone: " + docId1 + ", fake: " + fake);
                        callback.onResult(cipher);
                    } else {
                        // לא נמצא אצל היוזר – ננסה אצל הצד השני
                        db.collection("users").document(docId2).collection("fakeMap")
                                .document(fake).get()
                                .addOnSuccessListener(doc2 -> {
                                    String cipher2 = extractCipherFromDoc(doc2);
                                    if (cipher2 != null && !cipher2.isEmpty()) {
                                        Log.d("LT_FIREBASE", "Found mapping in peerPhone: " + docId2 + ", fake: " + fake);
                                        callback.onResult(cipher2);
                                    } else {
                                        Log.w("LT_FIREBASE", "No mapping found in Firebase for: " + fake);
                                        callback.onResult(null);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Log.e("LT_FIREBASE", "Firebase error (peer): " + e.getMessage());
                                    callback.onResult(null);
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("LT_FIREBASE", "Firebase error (me): " + e.getMessage());
                    callback.onResult(null);
                });
    }

    // פונקציה עזר שמחלצת את המחרוזת מהדוקומנט (תומכת גם ב-actualCipher וגם ב-cipher)
    private String extractCipherFromDoc(DocumentSnapshot doc) {
        if (doc != null && doc.exists()) {
            return doc.getString("encrypted");
        }
        return null;
    }


    /** האם יש child שמייצג מקלדת על המסך */
    private boolean isKeyboardProbablyVisible(AccessibilityNodeInfo root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            try {
                CharSequence pkg = child.getPackageName();
                if (pkg != null && pkg.toString().contains("inputmethod")) {
                    child.recycle();
                    return true;
                }
                if (isKeyboardProbablyVisible(child)) {
                    child.recycle();
                    return true;
                }
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private void logParentChain(AccessibilityNodeInfo node) {
        int depth = 0;
        AccessibilityNodeInfo curr = node;
        while (curr != null && depth < 10) {
            String viewId = null;
            try { viewId = curr.getViewIdResourceName(); } catch (Exception ignored) {}
            Log.d("LT_overlay", "depth=" + depth + ", viewId=" + viewId + ", class=" + curr.getClassName());
            AccessibilityNodeInfo parent = curr.getParent();
            if (curr != node && curr != null) curr.recycle();
            curr = parent;
            depth++;
        }
    }
    private boolean isOutgoingBubble(AccessibilityNodeInfo node, Rect bounds) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        // חיפוש במבנה ה-DOM לזיהוי כיוון על בסיס ID או class
        AccessibilityNodeInfo curr = node;
        for (int depth = 0; depth < 8 && curr != null; depth++) {
            String id = null, cls = null;
            try {
                id = curr.getViewIdResourceName();
            } catch (Exception ignore) {}

            try {
                cls = curr.getClassName() != null ? curr.getClassName().toString() : "";
            } catch (Exception ignore) {}

            // בדיקת ID או class שמכילים "out" (הודעות יוצאות)
            if ((id != null && id.toLowerCase().contains("out")) ||
                    (cls != null && cls.toLowerCase().contains("out"))) {
                if (curr != node) curr.recycle();
                Log.d("LT_bubble", "FOUND OUTGOING id/cls: " + id + "/" + cls);
                return true;
            }

            // בדיקת ID או class שמכילים "in" (הודעות נכנסות)
            if ((id != null && id.toLowerCase().contains("in")) ||
                    (cls != null && cls.toLowerCase().contains("in"))) {
                if (curr != node) curr.recycle();
                Log.d("LT_bubble", "FOUND INCOMING id/cls: " + id + "/" + cls);
                return false;
            }

            AccessibilityNodeInfo parent = curr.getParent();
            if (curr != node) curr.recycle();
            curr = parent;
        }

        // Fallback: בדיקה לפי מיקום - הודעות יוצאות בדרך כלל בצד ימין
        // תיקון הלוגיקה: אם ההודעה בצד ימין של המסך = יוצאת
        boolean outgoing = bounds.right > (screenWidth * 0.6);
        Log.d("LT_bubble", "Fallback by position: right=" + bounds.right + ", screenWidth=" + screenWidth + ", outgoing=" + outgoing);
        return outgoing;
    }
    private String readQrFromBitmap(Bitmap bmp) {
        try {
            int width = bmp.getWidth(), height = bmp.getHeight();
            int[] pixels = new int[width * height];
            bmp.getPixels(pixels, 0, width, 0, 0, width, height);
            com.google.zxing.RGBLuminanceSource source = new com.google.zxing.RGBLuminanceSource(width, height, pixels);
            com.google.zxing.BinaryBitmap binBitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
            com.google.zxing.Result result = new com.google.zxing.qrcode.QRCodeReader().decode(binBitmap);
            return result.getText();
        } catch (Exception e) { return null; }
    }

    private boolean isLockTalkLogoImage(Bitmap bmp) {
        String qr = ImageStorageHelper.readQrFromBitmap(bmp);
        return qr != null && qr.startsWith("LockTalk-ENC|");
    }


    private void handleAutoEncrypt() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String cs = WhatsAppUtils.getWhatsAppInputText(root);

        String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
        String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
        root.recycle();

        if (cs == null || !cs.endsWith("$")) return;

        String orig = cs.substring(0, cs.length() - 1);
        if (orig.isEmpty()) return;

        SharedPreferences creds = getSharedPreferences("UserCredentials", MODE_PRIVATE);
        String myPhone = creds.getString("myPhone", null);

        if (myPhone == null || peerPhone == null) {
            showToast("נא להגדיר מספרי טלפון");
            return;
        }

        isReplacing = true;
        mainHandler.postDelayed(() -> isReplacing = false, 500);

        executor.execute(() -> {
            try {
                javax.crypto.SecretKey chatKey = EncryptionHelper.deriveChatKey(myPhone, peerPhone);
                String actualCipher = EncryptionHelper.encryptToString(orig, chatKey);

                if (actualCipher == null) {
                    mainHandler.post(() -> showToast("שגיאה בהצפנה"));
                    return;
                }

                String fake = generateFakeEncryptedText(actualCipher.length());
                // שמירה ב-SharedPreferences
                getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE)
                        .edit().putString(fake, actualCipher).apply();

                // --- שמירה ב-Firebase אצל שני הצדדים --- //
                String docIdSender = myPhone.replace("+972", "");
                String docIdReceiver = peerPhone.replace("+972", "");
                FirebaseFirestore db = FirebaseFirestore.getInstance();

                FakeMapEntry entry = new FakeMapEntry(fake, actualCipher);
                db.collection("users").document(docIdSender).collection("fakeMap")
                        .document(fake).set(entry);
                db.collection("users").document(docIdReceiver).collection("fakeMap")
                        .document(fake).set(entry);

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
    public class FakeMapEntry {
        public String fake;
        public String encrypted;
        public FakeMapEntry() {}
        public FakeMapEntry(String fake, String encrypted) {
            this.fake = fake;
            this.encrypted = encrypted;
        }
    }


    public boolean isReallyInWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        boolean res = WhatsAppUtils.isWhatsAppPackage(pkg);
        if (root != null) root.recycle();
        return res;
    }

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
                    String enteredCode = input.getText().toString().trim();

                    SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                    String userPhone = prefs.getString("myPhone", null); // שינוי למפתח אחיד

                    // קריאה ע"י מפתח פר משתמש - עדיין נשמור קוד אישי לאימות
                    String realCode = prefs.getString("personalCode_" + userPhone, null);
                    if (realCode == null) realCode = prefs.getString("personalCode", null);

                    // 1. בדיקת קוד אישי
                    boolean valid = enteredCode.equals(realCode);

                    // 2. בדיקת מס' וואטסאפ פעיל == זה של המשתמש
                    boolean isWhatsAppPhoneValid = isWhatsAppAccountPhoneMatches(userPhone);

                    if (valid && isWhatsAppPhoneValid) {
                        decryptAuthenticated = true;
                        overlayHiddenByUser = false;
                        showToast("אומת בהצלחה");
                        showDecryptDurationDialog();
                    } else if (!valid) {
                        showToast("קוד אישי שגוי");
                    } else if (!isWhatsAppPhoneValid) {
                        showToast("חשבון הוואטסאפ במכשיר לא תואם את המשתמש שמחובר לאפליקציה");
                    }
                })
                .setNegativeButton("ביטול", null)
                .create();
        configureDialogWindow(dlg);
        dlg.show();
        activeDialog = dlg;
    }


    private boolean isWhatsAppAccountPhoneMatches(String userPhone) {
        try {
            Uri profileUri = Uri.parse("content://com.whatsapp.profile");
            Cursor c = getContentResolver().query(profileUri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex("number");
                if (c.moveToFirst() && idx >= 0) {
                    String waNumber = c.getString(idx);
                    c.close();
                    return (waNumber != null && waNumber.equals(userPhone));
                }
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot access WhatsApp profile: " + e.getMessage());
        }
        return true;
    }

    /** מחזיר true אם המסך הוא מסך שיחה רגילה בווטסאפ */
    private boolean isConversationScreen(String className, AccessibilityNodeInfo root) {
        if (className == null) return false;
        if (className.equals("com.whatsapp.Conversation")
                || className.equals("com.whatsapp.conversation.ConversationActivity")
                || className.toLowerCase().contains("conversation")) {
            return true;
        }
        // הגנה אם root == null:
        if (root != null) {
            List<AccessibilityNodeInfo> inputFields = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
            return inputFields != null && !inputFields.isEmpty();
        }
        return false;
    }

    private void showMainOverlay() {
        overlayManager.show(
                v -> {
                    Intent pick = new Intent(this, ImagePickerProxyActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(pick);
                },
                v -> {
                    resetToDefaultOverlay();
                },
                v -> showDecryptAuthDialog()
        );
        // מיקום קבוע TOP CENTER - להוסיף את הפונקציה הזו ל-OverlayManager (ראה שלב 2)
        overlayManager.updateToTopCenter();
    }

    // במקום stopContinuousDecryption()
    private void resetToDefaultOverlay() {
        decryptAlwaysOn = false;
        decryptAuthenticated = false;
        decryptHandler.removeCallbacks(decryptRunnable);
        overlayManager.clearDecryptOverlays();
        mainHandler.post(() -> {
            if (!overlayManager.isShown()) {
                showMainOverlay();
            }
        });
    }

    private void showDecryptDurationDialog() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();

        String[] items = {"1 דקה", "5 דקות", "10 דקות", "15 דקות", "30 דקות", "60 דקות", "פיענוח עד עצירה ידנית"};
        int[] mins = {1, 5, 10, 15, 30, 60, -1};
        final int[] sel = {-1};

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("משך פיענוח"))
                .setSingleChoiceItems(items, -1, (d, w) -> sel[0] = w)
                .setPositiveButton("הפעל", (d, w) -> {
                    if (sel[0] < 0) {
                        showToast("לא נבחר משך זמן");
                        return;
                    }
                    if (mins[sel[0]] == -1) {
                        // פיענוח קבוע
                        decryptExpiryTimestamp = Long.MAX_VALUE;
                        overlayHiddenByUser = false;
                        showToast("פיענוח פעיל: עד עצירה ידנית");
                        showMainOverlay();
                        startContinuousDecryption();
                        tryDecryptWithRetry();
                    } else {
                        decryptExpiryTimestamp = System.currentTimeMillis() + mins[sel[0]] * 60_000L;
                        showToast("פיענוח פעיל: " + items[sel[0]]);
                        overlayHiddenByUser = false;
                        showMainOverlay();
                        overlayManager.startTimer(
                                decryptExpiryTimestamp,
                                () -> {
                                    resetToDefaultOverlay();
                                }
                        );
                        tryDecryptWithRetry();
                    }
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
        WindowManager.LayoutParams lp = Objects.requireNonNull(dlg.getWindow()).getAttributes();
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

    private int mmToPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round((float) 5 * dm.xdpi / 25.4f);
    }



    public String getLastWhatsAppPackage() {
        return lastWhatsAppPackage;
    }
}