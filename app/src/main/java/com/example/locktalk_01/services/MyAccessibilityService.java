package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.activities.ImagePickerProxyActivity;
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.ImageStorageHelper;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;

import java.io.File;
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
        Log.d("LT", "tryDecryptWithRetry: attempt " + tryDecryptAttempts);

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean canTry = false;
        if (root != null && root.getPackageName() != null) {
            String pkg = root.getPackageName().toString();
            canTry = pkg.contains("com.whatsapp");
        }
        if (canTry) {
            decryptChatBubbles();
            Log.d("LT", "tryDecryptWithRetry: WhatsApp window detected, decrypt called");
        } else if (tryDecryptAttempts < 10) { // נסי עד 10 פעמים (כ-3 שניות)
            tryDecryptHandler.postDelayed(this::tryDecryptInternal, 300);
        } else {
            Log.d("LT", "tryDecryptWithRetry: Gave up, window not WhatsApp");
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
                    // אין לעולם לעצור את הטיימר אם pkg == getPackageName() (כל עוד הדיאלוג שלך פתוח)
                    if (!WhatsAppUtils.isWhatsAppPackage(pkg)
                            && !pkg.equals(getPackageName())
                            && !pkg.toLowerCase().contains("systemui")
                            && !pkg.toLowerCase().contains("com.android")
                            && !pkg.toLowerCase().contains("resolver")
                            && !ImagePickerProxyActivity.isDialogOpen() // הגנה נוספת אם הדיאלוג שלך פתוח
                    ) {
                        Log.d("LT_OVERLAY", "יציאה מלאה - סגירת overlay והפסקת טיימר");
                        overlayManager.hide();
                        stopContinuousDecryption();
                    } else {
                        Log.d("LT_OVERLAY", "מסך זמני (דיאלוג/בורר/מצלמה/גלריה/האפליקציה שלך) - הסתרת בועות בלבד");
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
            n.recycle();
        }
        return largest;
    }private void decryptFullScreenImage(AccessibilityNodeInfo root) {
        // מוצא את הרקטנגל של התמונה הגדולה במסך
        Rect imageBounds = findFullscreenImageRect(root);
        if (imageBounds == null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            imageBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }

        // מנסה למצוא את הלייבל האחרון במסך (בדרך כלל בולט מאוד בצ'אט)
        List<Pair<String, Rect>> imgLabels = WhatsAppUtils.findImageLabels(root);
        String imgLabel = imgLabels.isEmpty() ? null : imgLabels.get(imgLabels.size() - 1).first; // האחרון במסך

        if (imgLabel == null) {
            Log.d("MyAccessibilityService", "No imgLabel found for fullscreen image");
            return;
        }

        SharedPreferences prefsLock = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
        SharedPreferences creds = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String origPath = prefsLock.getString("origPath_for_" + imgLabel, null);

        // --- הוספת בדיקת קוד אישי! ---
        String personalCodeForImage = prefsLock.getString("personalCode_for_" + imgLabel, null);
        String currentPersonalCode = creds.getString(PERSONAL_CODE_PREF, "");
        if (personalCodeForImage == null || !personalCodeForImage.equals(currentPersonalCode)) {
            Log.d("MyAccessibilityService", "Full image " + imgLabel + " skipped: encrypted with old personal code!");
            mainHandler.post(() -> showToast("תמונה זו הוצפנה עם קוד אישי קודם ולא ניתן לפענח אותה"));
            return;
        }

        if (origPath == null) {
            Log.d("MyAccessibilityService", "No origPath mapped for label: " + imgLabel);
            return;
        }

        Bitmap origBmp = BitmapFactory.decodeFile(origPath);
        if (origBmp == null) {
            Log.d("MyAccessibilityService", "Original image not found or corrupted");
            return;
        }

        final Rect finalBounds = imageBounds;
        final String imageId = origPath;
        mainHandler.post(() ->{
                    if (!isReallyInWhatsApp()) {
                        if (overlayManager != null) overlayManager.hide();
                        stopContinuousDecryption();
                        return;
                    }
                    overlayManager.showDecryptedImageOverlay(origBmp, finalBounds, imageId);
                }
        );
    }
    private boolean isRetryingDecrypt = false;

    private volatile boolean decryptRunning = false; // עזר נגד חפיפות

    private void decryptChatBubbles() {
        if (decryptRunning) return;
        decryptRunning = true;
        Log.d("MyAccessibilityService", "decryptChatBubbles CALLED!");

        SharedPreferences creds = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String userPhone = creds.getString("currentUserPhone", null);
        if (!isWhatsAppAccountPhoneMatches(userPhone)) {
            mainHandler.post(() -> {
                showToast("פיענוח לא אפשרי - חשבון WhatsApp במכשיר לא תואם למשתמש!");
                overlayManager.cleanupAllBubbles();
                decryptRunning = false;
            });
            return;
        }

        if (!decryptAuthenticated || System.currentTimeMillis() > decryptExpiryTimestamp) {
            mainHandler.post(() -> {
                overlayManager.cleanupAllBubbles();
                decryptRunning = false;
            });
            return;
        }
        lastDecryptTs = System.currentTimeMillis();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String className = (root != null && root.getClassName() != null) ? root.getClassName().toString() : "";
        if (shouldHideDecryptionOverlays() || !isConversationScreen(className, root)) {
            mainHandler.post(() -> {
                overlayManager.cleanupAllBubbles();
                decryptRunning = false;
            });
            if (root != null) root.recycle();
            return;
        }

        List<Pair<AccessibilityNodeInfo, Rect>> txtList = new ArrayList<>();
        List<Pair<AccessibilityNodeInfo, Rect>> imgNodes = new ArrayList<>();
        List<Pair<String, Rect>> imgLabels = new ArrayList<>();
        DisplayMetrics dm = getResources().getDisplayMetrics();

        // טקסט מוצפן
        for (AccessibilityNodeInfo n : WhatsAppUtils.findEncryptedMessages(root)) {
            if (!n.isEditable()) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.width() <= 0 || b.height() <= 0) {
                    b = new Rect(50, 300, dm.widthPixels - 50, 400);
                }
                CharSequence t = n.getText();
                if (t != null) {
                    txtList.add(new Pair<>(AccessibilityNodeInfo.obtain(n), b)); // העתק node!
                }
                n.recycle();
            }
        }

        imgNodes = WhatsAppUtils.findImageBubbleButtons(root);
        imgLabels = WhatsAppUtils.findImageLabels(root);

        if (root != null) root.recycle();

        final List<Pair<AccessibilityNodeInfo, Rect>> finalTxtList = txtList;
        final List<Pair<AccessibilityNodeInfo, Rect>> finalImgNodes = imgNodes;
        final List<Pair<String, Rect>> finalImgLabels = imgLabels;

        executor.execute(() -> {
            if (!isReallyInWhatsApp()) {
                mainHandler.post(() -> {
                    if (!isReallyInWhatsApp()) {
                        if (overlayManager != null) overlayManager.hide();
                        stopContinuousDecryption();
                        return;
                    }
                    if (overlayManager != null) overlayManager.hide();
                    stopContinuousDecryption();
                });
                return;
            }
            SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
            SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);

            Set<String> wantedIds = new HashSet<>();

            // פענוח טקסט מוצפן
            for (Pair<AccessibilityNodeInfo, Rect> p : finalTxtList) {
                final AccessibilityNodeInfo node = p.first;
                final Rect bounds = p.second;
                final CharSequence fake = node.getText();
                final boolean outgoing = isOutgoingBubble(node, bounds);
                final String actualCipher = fakeMap.getString(fake != null ? fake.toString() : "", null);

                final String id = OverlayManager.bubbleId((fake != null ? fake.toString() : ""), bounds, outgoing);
                wantedIds.add(id);

                if (actualCipher != null) {
                    try {
                        final String code = creds.getString(PERSONAL_CODE_PREF, "");
                        if (code.isEmpty()) {
                            mainHandler.post(() -> showToast("לא הוגדר קוד אישי"));
                            continue;
                        }
                        final String plain = keystorePlugin.loadDecryptedMessage(code, actualCipher);
                        if (plain != null) {
                            mainHandler.post(() -> {
                                if (!isReallyInWhatsApp()) {
                                    if (overlayManager != null) overlayManager.hide();
                                    stopContinuousDecryption();
                                    return;
                                }
                                overlayManager.showDecryptedOverlay(plain, node, bounds, outgoing, id);
                                node.recycle();
                            });
                            continue;
                        }
                    } catch (Exception e) {
                        Log.e("MyAccessibilityService", "Text decrypt error", e);
                    }
                }
                // אם לא פוענח – ננקה
                mainHandler.post(node::recycle);
            }

            // פענוח תמונות - סינון
            int n = Math.min(finalImgNodes.size(), finalImgLabels.size());
            for (int i = 0; i < n; i++) {
                final AccessibilityNodeInfo node = finalImgNodes.get(i).first;
                final Rect imgRect = finalImgNodes.get(i).second;
                final String imgLabel = finalImgLabels.get(i).first;
                final String origPath = prefs.getString("origPath_for_" + imgLabel, null);

                final String personalCodeForImage = prefs.getString("personalCode_for_" + imgLabel, null);
                final String currentPersonalCode = creds.getString(PERSONAL_CODE_PREF, "");
                if (personalCodeForImage == null || !personalCodeForImage.equals(currentPersonalCode)) {
                    Log.d("MyAccessibilityService", "Image " + imgLabel + " skipped: encrypted with old personal code!");
                    mainHandler.post(node::recycle);
                    continue;
                }

                if (origPath == null) {
                    Log.d("MyAccessibilityService", "No original path for " + imgLabel);
                    mainHandler.post(node::recycle);
                    continue;
                }
                Bitmap origBmp = BitmapFactory.decodeFile(origPath);
                origBmp = ImagePickerProxyActivity.fixImageOrientation(null, origPath, origBmp);

                if (origBmp == null) {
                    Log.d("MyAccessibilityService", "Failed to decode original image for: " + imgLabel);
                    mainHandler.post(node::recycle);
                    continue;
                }

                final String id = "img|" + imgLabel + "|" + imgRect.toShortString();
                wantedIds.add(id);

                Log.d("MyAccessibilityService", "Showing overlay for label=" + imgLabel + " bounds=" + imgRect.toShortString());
                final Bitmap finalOrigBmp = origBmp;
                mainHandler.post(() -> {
                    if (!isReallyInWhatsApp()) {
                        if (overlayManager != null) overlayManager.hide();
                        stopContinuousDecryption();
                        return;
                    }
                    overlayManager.showDecryptedImageOverlay(finalOrigBmp, imgRect, id);
                    node.recycle();
                });
            }

            mainHandler.post(() -> overlayManager.cleanupBubblesExcept(wantedIds));

            // שחרור נעילה!
            mainHandler.post(() -> decryptRunning = false);

            // ניסיון נוסף, רק פעם אחת
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
    }

    private boolean isCurrentAppWhatsApp() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);
        if (taskInfo == null || taskInfo.isEmpty()) return false;
        String topActivity = taskInfo.get(0).topActivity.getPackageName();
        return "com.whatsapp".equals(topActivity);
    }
    // ------- עזר: הוסיפי למחלקה ---------
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

        // 1. לא בוואטסאפ? לא להציג!
        if (!currentPackage.contains("com.whatsapp")) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp (" + currentPackage + ")");
            return true;
        }
        // 2. אל תסתיר במסכי תצוגת תמונה/מדיה (gallery/media/photo/image/video view), תמשיך להציג!
        if (isMediaViewerScreen(lc)) {
            Log.d("LT", "shouldHideDecryptionOverlays: ALLOW in media viewer (" + currentClass + ")");
            return false;
        }

        // 3. אם זו האפליקציה שלך או דיאלוג ב-whatsapp (כולל כל הדיאלוגים, פופאפים, בוחר קבצים, מצלמה, תפריט צף וכו')
        if (
                currentPackage.equals(getPackageName()) ||
                        lc.contains("imagepicker") ||
                        lc.contains("chooser") ||
                        lc.contains("alertdialog") ||
                        lc.contains("document") ||
                        lc.contains("file") ||
                        lc.contains("crop") ||
                        lc.contains("popup") ||
                        lc.contains("dialog") ||
                        lc.contains("menu") ||
                        lc.contains("picker") ||
                        lc.contains("camera") ||
                        lc.contains("activity") && (
                                lc.contains("media") ||
                                        lc.contains("camera") ||
                                        lc.contains("file") ||
                                        lc.contains("gallery") ||
                                        lc.contains("document") ||
                                        lc.contains("dialog")
                        )
        ) {
            Log.d("LT", "shouldHideDecryptionOverlays: dialog/media/camera/gallery (" + currentClass + ")");
            return true;
        }

        // בדיקה מול רשימת מחלקות דיאלוגים/בוחרים/דפדפנים וכו'
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
                "contactpicker", "status", "share", "settings"
        };
        for (String forbidden : forbiddenClasses) {
            if (lc.contains(forbidden)) {
                Log.d("LT", "shouldHideDecryptionOverlays: forbidden dialog/activity (" + currentClass + ")");
                return true;
            }
        }

        // 4. אם יש מקלדת פתוחה
        boolean keyboardVisible = isKeyboardProbablyVisible(root);
        if (keyboardVisible) {
            Log.d("LT", "shouldHideDecryptionOverlays: keyboardVisible");
            return true;
        }

        // 5. לא מסך שיחה? הגנה נוספת
        if (!isConversationScreen(currentClass, root)) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp conversation screen (" + currentClass + ")");
            return true;
        }

        // ברירת מחדל - לא להסתיר
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
    // מזהה אם הבועה נשלחת (outgoing) לפי ה־resourceId/שם מחלקה של ההורה, או מיקום
    private boolean isOutgoingBubble(AccessibilityNodeInfo node, Rect bounds) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        AccessibilityNodeInfo curr = node;
        for (int depth = 0; depth < 8 && curr != null; depth++) {
            String id = null, cls = null;
            try { id = curr.getViewIdResourceName(); } catch (Exception ignore) {}
            try { cls = curr.getClassName() != null ? curr.getClassName().toString() : ""; } catch (Exception ignore) {}
            if ((id != null && id.toLowerCase().contains("out")) ||
                    (cls != null && cls.toLowerCase().contains("out"))) {
                if (curr != node) curr.recycle();
                Log.d("LT_bubble", "FOUND OUTGOING id/cls: " + id + "/" + cls);
                return true;
            }
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
        // שורת הקסם: outgoing אם left קטן יחסית לרוחב המסך (RTL)
        boolean outgoing = bounds.left < (screenWidth * 0.5); // אפשר גם 0.4-0.45
        Log.d("LT_bubble", "fallback by left: " + bounds.left + ", screenWidth: " + screenWidth + ", outgoing=" + outgoing);
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
                    String userPhone = prefs.getString("currentUserPhone", null);

                    // קריאה ע"י מפתח פר משתמש
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