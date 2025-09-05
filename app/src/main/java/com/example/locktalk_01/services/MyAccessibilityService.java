package com.example.locktalk_01.services;

import static com.example.locktalk_01.utils.WhatsAppUtils.findImageKeysWithPositions;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import java.util.Objects;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.example.locktalk_01.R;
import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.activities.ImagePickerProxyActivity;
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.EncryptionHelper;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;
import com.google.firebase.database.annotations.Nullable;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MyAccessibilityService extends AccessibilityService {

    private Handler mainHandler;
    private AlertDialog activeDialog;
    private boolean decryptAuthenticated = false;
    private long decryptExpiryTimestamp;
    private long lastDecryptTs = 0;
    private static final long DECRYPT_THROTTLE_MS = 50;
    private boolean isReplacing = false;
    private boolean overlayHiddenByUser = false;
    private String lastWhatsAppPackage;
    private static MyAccessibilityService instance;
    private ExecutorService executor;
    private String pendingImageUri = null;
    private static volatile boolean imagePickerActive = false;

    private Handler overlayHidePollHandler = new Handler(Looper.getMainLooper());
    private Runnable overlayHidePollRunnable;
    public static boolean pendingImageAutoSend = false;

    private boolean decryptAlwaysOn = false;
    private Handler decryptHandler = new Handler(Looper.getMainLooper());
    private Runnable decryptRunnable;
    private boolean isRetryingDecrypt = false;
    private Handler globalPollHandler = new Handler(Looper.getMainLooper());
    private Runnable globalPollRunnable;
    private volatile boolean decryptRunning = false;
    private static final String TAG = "MyAccessibilityService";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PREF_FAKE_MAP = "FakeCipherMap";
    private AndroidKeystorePlugin keystorePlugin;
    public OverlayManager overlayManager;
    private int sendButtonRetryCount = 0;

    private Queue<ImageToSend> pendingImages = new LinkedList<>();
    private boolean isProcessingImageQueue = false;
    private Handler imageQueueHandler = new Handler(Looper.getMainLooper());
    private boolean isWaitingForImageSent = false;
    private String lastSentImageKey = "";
    private long lastImageSentTime = 0;
    private static final int IMAGE_TIMEOUT = 400;
    private static final int NEXT_IMAGE_DELAY = 0;
    private static final int SEND_BTN_POLL_MS = 25;  // פולינג מהיר לכפתור הירוק
    private static final int MAX_SEND_ATTEMPTS = 60; // ~2 שניות מקס' פולינג
    private Runnable currentTimeoutRunnable = null;
    // מזהי כפתור "שליחה" פוטנציאליים בווטסאפ (גרסאות/סקינים שונים)
    private static final String[] SEND_BUTTON_IDS = new String[]{
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/submit",
            "com.whatsapp:id/menuitem_send"
    };

    private static final String[] SEND_BUTTON_DESCS = new String[]{
            "Send", "send", "שליחה", "שלח"
    };
    // === FAST SEND: phone -> jid helper ===
    private String phoneToJid(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("972")) {
            // כבר בפורמט בינ"ל ללא '+'
        } else if (digits.startsWith("0") && digits.length() > 1) {
            // המרה ל־+972
            digits = "972" + digits.substring(1);
        } else if (!digits.startsWith("972")) {
            // אם אין קידומת - נניח ישראל
            digits = "972" + digits;
        }
        return digits + "@s.whatsapp.net";
    }

    // === FAST SEND: direct WA intent (skips chooser) ===
    private Intent buildDirectWhatsAppSendIntent(Uri imageUri, String imageKey, String peerPhone) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, imageUri);
        i.putExtra(Intent.EXTRA_TEXT, imageKey);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.whatsapp"); // או com.whatsapp.w4b אם את בעסקי — ננסה קודם הרגיל

        String jid = phoneToJid(peerPhone);
        if (jid != null) {
            i.putExtra("jid", jid);
        }
        return i;
    }

    private static class ImageToSend {
        Uri imageUri;
        String imageKey;
        long timestamp;

        ImageToSend(Uri uri, String key) {
            this.imageUri = uri;
            this.imageKey = key;
            this.timestamp = System.currentTimeMillis();
        }
    }
    public void queueImagesForSequentialSending(ArrayList<Uri> imageUris, ArrayList<String> imageKeys) {
        Log.d("LT_QUEUE", "📋 הוספת " + imageUris.size() + " תמונות לתור שליחה");

        // איפוס מלא במקום clearImageQueue בלבד
        resetImageQueueState();

        // הוסף תמונות חדשות לתור
        for (int i = 0; i < imageUris.size(); i++) {
            pendingImages.offer(new ImageToSend(imageUris.get(i), imageKeys.get(i)));
        }

        Log.d("LT_QUEUE", "📊 התור מכיל עכשיו " + pendingImages.size() + " תמונות");

        // התחל עם התמונה הראשונה
        if (!pendingImages.isEmpty()) {
            startSequentialImageSending();
        }
    } // 5. תיקון startSequentialImageSending - הוסף בדיקות בטיחות נוספות
    private void startSequentialImageSending() {
        if (pendingImages.isEmpty()) {
            Log.d("LT_QUEUE", "✅ תור ריק - סיימנו לשלוח את כל התמונות");
            clearImageQueue(); // ודא ניקוי מלא
            return;
        }

        if (isWaitingForImageSent) {
            Log.d("LT_QUEUE", "⏳ כבר מחכים לשליחת תמונה, מדלגים");
            return;
        }

        // וודא שאנחנו לא באמצע timeout של תמונה קודמת
        if (currentTimeoutRunnable != null) {
            Log.d("LT_QUEUE", "⏰ יש timeout רץ, מבטל אותו");
            imageQueueHandler.removeCallbacks(currentTimeoutRunnable);
            currentTimeoutRunnable = null;
        }

        ImageToSend nextImage = pendingImages.poll();
        if (nextImage != null) {
            Log.d("LT_QUEUE", "📤 שולח תמונה: " + nextImage.imageKey +
                    " (נותרו " + pendingImages.size() + " תמונות)");

            isProcessingImageQueue = true;
            sendSingleImageToWhatsApp(nextImage.imageUri, nextImage.imageKey);
        } else {
            Log.w("LT_QUEUE", "⚠️ התמונה הבאה בתור היא null");
            isProcessingImageQueue = false;
        }
    }
    public void resetImageQueueState() {
        Log.d("LT_QUEUE", "🔄 איפוס מלא של מצב תור התמונות");

        // נקה את כל ה-handlers והמשימות
        imageQueueHandler.removeCallbacksAndMessages(null);

        // איפוס כל הדגלים
        isProcessingImageQueue = false;
        isWaitingForImageSent = false;
        pendingImageAutoSend = false;

        // איפוס נתונים
        pendingImages.clear();
        lastSentImageKey = "";
        lastImageSentTime = 0;
        currentTimeoutRunnable = null;
        sendButtonRetryCount = 0;
    }
    // === FAST SEND: replace sendSingleImageToWhatsApp ===
    private void sendSingleImageToWhatsApp(Uri imageUri, String imageKey) {
        try {
            isWaitingForImageSent = true;
            lastSentImageKey = imageKey;
            lastImageSentTime = System.currentTimeMillis();
            pendingImageAutoSend = true;
            fastSendLoopCancelled = false; // ר' סעיף 6

            // ננסה לקבל את איש הקשר האחרון כדי לירות ישירות ל־jid
            SharedPreferences lp = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            String chatTitle = lp.getString("lastGalleryChat", lp.getString("lastChatTitle", null));

            String peerPhone = null;
            if (chatTitle != null && !chatTitle.isEmpty()) {
                peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
                if (peerPhone == null) {
                    peerPhone = findAndCachePhoneForChat(this, chatTitle);
                }
            }

            Intent wa = buildDirectWhatsAppSendIntent(imageUri, imageKey, peerPhone);

            boolean started = false;
            try {
                startActivity(wa);
                started = true;
            } catch (Exception primary) {
                // fallback: נסה Business
                try {
                    wa.setPackage("com.whatsapp.w4b");
                    startActivity(wa);
                    started = true;
                } catch (Exception business) {
                    // fallback אחרון: chooser (כבר קיים אצלך)
                    Log.w("LT_QUEUE", "Direct WA intent failed, falling back to chooser", business);
                    Intent chooserIntent = new Intent(Intent.ACTION_SEND);
                    chooserIntent.setType("image/jpeg");
                    chooserIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
                    chooserIntent.putExtra(Intent.EXTRA_TEXT, imageKey);
                    chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(Intent.createChooser(chooserIntent, "שתף תמונה"));
                }
            }

            // מרגע שפתחנו את מסך ה־MediaComposer, קפוץ לפולינג המהיר על הכפתור הירוק
            mainHandler.postDelayed(() -> tryAutoClickSendRecurring(0), started ? 120 : 250);

            // timeout קצר לכל תמונה
            if (currentTimeoutRunnable != null) imageQueueHandler.removeCallbacks(currentTimeoutRunnable);
            currentTimeoutRunnable = () -> {
                Log.w("LT_QUEUE", "⏰ TIMEOUT: תמונה " + imageKey + " לא נשלחה בזמן");
                handleImageTimeout();
            };
            imageQueueHandler.postDelayed(currentTimeoutRunnable, IMAGE_TIMEOUT);

        } catch (Exception e) {
            Log.e("LT_QUEUE", "❌ שגיאה בשליחת תמונה: " + imageKey, e);
            handleImageSendError();
        }
    }
    private void handleImageTimeout() {
        Log.w("LT_QUEUE", "⏰ טיפול ב-timeout של תמונה: " + lastSentImageKey);

        // נקה מצב נוכחי
        isWaitingForImageSent = false;
        pendingImageAutoSend = false;

        // בטל timeout רץ
        if (currentTimeoutRunnable != null) {
            imageQueueHandler.removeCallbacks(currentTimeoutRunnable);
            currentTimeoutRunnable = null;
        }

        // בדוק אם יש עוד תמונות לשלוח
        if (pendingImages.isEmpty()) {
            Log.w("LT_QUEUE", "⏰ אין עוד תמונות בתור לאחר timeout");
            isProcessingImageQueue = false;
            return;
        }

        // המשך לתמונה הבאה
        scheduleNextImageSending();
    }

    private void handleImageSendError() {
        Log.e("LT_QUEUE", "❌ טיפול בשגיאת שליחה");
        handleImageTimeout(); // אותה לוגיקה כמו timeout
    }
    private void onImageSentSuccessfully() {
        Log.d("LT_QUEUE", "✅ תמונה " + lastSentImageKey + " נשלחה בהצלחה!");

        // בטל timeout
        if (currentTimeoutRunnable != null) {
            imageQueueHandler.removeCallbacks(currentTimeoutRunnable);
            currentTimeoutRunnable = null;
        }

        // נקה מצב נוכחי
        isWaitingForImageSent = false;
        pendingImageAutoSend = false;
        lastSentImageKey = "";

        // בדוק אם יש עוד תמונות בתור
        if (pendingImages.isEmpty()) {
            Log.d("LT_QUEUE", "✅ סיימנו את כל התמונות בתור!");
            isProcessingImageQueue = false; // איפוס החשוב הזה
            return;
        }

        // המשך לתמונה הבאה
        scheduleNextImageSending();
    }

    private void scheduleNextImageSending() {
        // וודא שאנחנו לא מתזמנים שליחה כשהתור ריק
        if (pendingImages.isEmpty()) {
            Log.d("LT_QUEUE", "⏭️ תור ריק, לא מתזמן תמונה הבאה");
            isProcessingImageQueue = false;
            return;
        }

        Log.d("LT_QUEUE", "⏭️ מתזמן תמונה הבאה בעוד " + (NEXT_IMAGE_DELAY/1000) + " שניות");

        imageQueueHandler.postDelayed(() -> {
            Log.d("LT_QUEUE", "🔄 מתחיל שליחת תמונה הבאה");
            startSequentialImageSending();
        }, NEXT_IMAGE_DELAY);
    }
    private void checkForSentImage(AccessibilityNodeInfo root) {
        if (!isWaitingForImageSent || lastSentImageKey.isEmpty() || root == null) {
            return;
        }

        try {
            // בדוק אם חזרנו למסך הצ'אט
            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
            if (WhatsAppUtils.isWhatsAppPackage(pkg) && isConversationScreen(root)) {

                // חפש את המפתח בהודעות האחרונות
                if (findKeyInRecentMessages(root, lastSentImageKey)) {
                    Log.d("LT_QUEUE", "✅ מצאנו את המפתח בהודעות - תמונה נשלחה!");
                    onImageSentSuccessfully();
                    return;
                }

                // אם עברו יותר מ-3 שניות מאז השליחה, נחשב כהצלחה
                long timeSinceSent = System.currentTimeMillis() - lastImageSentTime;
                if (timeSinceSent > 3000) {
                    Log.d("LT_QUEUE", "⏰ עברו 3+ שניות מאז השליחה, מניח שהצליחה");
                    onImageSentSuccessfully();
                }
            }

        } catch (Exception e) {
            Log.e("LT_QUEUE", "שגיאה בבדיקת שליחה מוצלחת", e);
        }
    }

    private boolean findKeyInRecentMessages(AccessibilityNodeInfo root, String key) {
        try {
            // חפש nodes עם טקסט
            List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
            findAllTextNodes(root, textNodes);

            // בדוק את ה-nodes האחרונים (ההודעות החדשות ביותר)
            int nodesToCheck = Math.min(10, textNodes.size());
            for (int i = textNodes.size() - nodesToCheck; i < textNodes.size(); i++) {
                AccessibilityNodeInfo node = textNodes.get(i);
                String text = getNodeText(node);

                if (text != null && text.equals(key)) {
                    Log.d("LT_QUEUE", "🎯 מצא מפתח בהודעה: " + key);
                    return true;
                }
            }

            // נקה את הרשימה
            for (AccessibilityNodeInfo node : textNodes) {
                if (node != null) {
                    try {
                        node.recycle();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

        } catch (Exception e) {
            Log.w("LT_QUEUE", "שגיאה בחיפוש מפתח בהודעות", e);
        }

        return false;
    }

    private void findAllTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        if (node.getText() != null && !node.getText().toString().trim().isEmpty()) {
            result.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAllTextNodes(child, result);
            }
        }
    }
    private String getNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        return text != null ? text.toString() : null;
    }
    public static MyAccessibilityService getInstance() {
        return instance;
    }
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
        startGlobalOverlayPoll();
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
    public void onServiceConnected() {

        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{
                "com.whatsapp", "com.whatsapp.w4b",
                "com.gbwhatsapp", "com.whatsapp.plus", "com.yowhatsapp",
                "com.android.intentresolver"
        };
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        SharedPreferences prefs = getSharedPreferences("UserCredentials", MODE_PRIVATE);
        long lastAuthTime = prefs.getLong("lastAuthTime", 0);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAuthTime < 24 * 60 * 60 * 1000) {
            decryptAuthenticated = true;
            decryptExpiryTimestamp = lastAuthTime + 24 * 60 * 60 * 1000;
            Log.d(TAG, "Auto-authenticated from saved session");
        }
    }
    private static final boolean DEBUG_TREES = false;




    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo root = null;

        try {
            if (event.getPackageName() == null) return;

            String pkg = event.getPackageName().toString();
            String cls = event.getClassName() != null ? event.getClassName().toString() : "";
            int type = event.getEventType();
            root = getRootInActiveWindow();

            Log.d("LT_NODE", root == null ? "root==null – לא הצלחנו לגשת לעץ" : "יש root – ממשיכים להדפיס עץ");
            if (DEBUG_TREES && root != null) printAllNodes(root, 0);

            Log.d("LT_DEBUG", "onEvent: pkg=" + pkg + ", cls=" + cls + ", type=" + type + ", isChooser=" + isShareChooserScreen(cls, root));

            // עדכון: אם אנחנו במהלך עיבוד תור תמונות, נעדכן את הזמן
            if (isProcessingImageQueue && WhatsAppUtils.isWhatsAppPackage(pkg)) {
                SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                String lastChat = prefs.getString("lastChatTitle", null);
                if (lastChat != null && !lastChat.isEmpty()) {
                    // עדכן את הזמן כדי שהבחירה האוטומטית תעבוד
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("lastGalleryChat", lastChat);
                    editor.putLong("lastGalleryChatTime", System.currentTimeMillis());
                    editor.apply();
                    Log.d("LT_SHARE", "📝 עדכון זמן שיחה בגלל תור תמונות פעיל: " + lastChat);
                }
            }

            if (decryptAuthenticated && WhatsAppUtils.isWhatsAppPackage(pkg)) {
                boolean shouldUpdatePositions = false;

                switch (type) {
                    case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                        Log.d("LT_SCROLL", "📜 זוהתה גלילה - מעדכן מיקומים");
                        shouldUpdatePositions = true;
                        if (overlayManager != null) {
                            overlayManager.cleanupAllBubbles();
                            overlayManager.cleanupImageOverlays();
                        }
                        break;

                    case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                        if (isConversationScreen(root)) {
                            shouldUpdatePositions = true;
                        }
                        break;

                    case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                        shouldUpdatePositions = true;
                        break;
                }

                if (shouldUpdatePositions) {
                    requestImmediatePositionUpdate();
                }
            }

            if (WhatsAppUtils.isWhatsAppPackage(pkg)) {
                SharedPreferences debugPrefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                String lastChat = debugPrefs.getString("lastGalleryChat", "NOT_FOUND");
                long lastTime = debugPrefs.getLong("lastGalleryChatTime", 0);
                Log.d("LT_DEBUG", "WhatsApp event - pkg: " + pkg + " cls: " + cls + " type: " + type);
                Log.d("LT_DEBUG", "Last chat: '" + lastChat + "' time: " + (System.currentTimeMillis() - lastTime) + "ms ago");
                Log.d("LT_DEBUG", "isShareChooserScreen: " + isShareChooserScreen(cls, root));
            }

            if (WhatsAppUtils.isWhatsAppPackage(pkg) && cls.toLowerCase().contains("mediacomposer")) {
                // גם אם לא בתור, אם pendingImageAutoSend – טפל
                if (isProcessingImageQueue || pendingImageAutoSend || isWaitingForImageSent) {
                    tryAutoClickSendRecurring(0);
                }
            }


            if (isWaitingForImageSent && !lastSentImageKey.isEmpty() && root != null) {
                checkForSentImage(root);
            }

            if (WhatsAppUtils.isWhatsAppPackage(pkg) && isConversationScreen(root)) {
                String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
                Log.d("LT_CHAT_TITLE", "found chatTitle from screen: " + chatTitle);
                if (chatTitle != null && !chatTitle.isEmpty()) {
                    SharedPreferences.Editor editor = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE).edit();
                    editor.putString("lastChatTitle", chatTitle);
                    editor.putString("lastGalleryChat", chatTitle);
                    editor.putLong("lastGalleryChatTime", System.currentTimeMillis());
                    editor.apply();
                    Log.d("LT_CHAT_TITLE", "lastChatTitle & lastGalleryChat updated: " + chatTitle);
                }
            }

            boolean isSystemChooser = isSystemChooserEvent(pkg, cls);
            boolean isChooserLike = isShareChooserScreen(cls, root);
            boolean isChooserByTree = isChooserWithContacts(root);

            // תיקון: טפל במסך בחירת שיחות גם אם הוא בתוך WhatsApp
            boolean isWhatsAppChooser = WhatsAppUtils.isWhatsAppPackage(pkg) && isChooserByTree;

            if ((isSystemChooser && isChooserLike) || isChooserByTree || isWhatsAppChooser) {
                Log.d("LT_SHARE", "🎯 נכנסנו למסך בחירת שיחה (chooser)!");
                Log.d("LT_SHARE", "📱 Package: " + pkg + " Class: " + cls);
                Log.d("LT_SHARE", "🔍 isSystemChooser: " + isSystemChooser + ", isChooserLike: " + isChooserLike);
                Log.d("LT_SHARE", "🔍 isChooserByTree: " + isChooserByTree + ", isWhatsAppChooser: " + isWhatsAppChooser);

                // עדכן זמן אם אנחנו בתהליך שליחה
                if (isProcessingImageQueue || pendingImageAutoSend) {
                    SharedPreferences.Editor editor = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE).edit();
                    editor.putLong("lastGalleryChatTime", System.currentTimeMillis());
                    editor.apply();
                    Log.d("LT_SHARE", "📝 עדכון זמן שיחה בגלל תור תמונות פעיל");
                }

                printAllNodes(root, 0);

                SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                String chatTitle = prefs.getString("lastGalleryChat", null);
                long chatTime = prefs.getLong("lastGalleryChatTime", 0);
                long timeDiff = System.currentTimeMillis() - chatTime;

                // תיקון: הגדל את הזמן המותר ל-30 דקות, או בדוק אם אנחנו בתהליך שליחה
                boolean isRecentChat = timeDiff < 1800000; // 30 דקות
                boolean isInImageProcess = isProcessingImageQueue || pendingImageAutoSend;

                Log.d("LT_SHARE", "💾 Saved chatTitle: '" + chatTitle + "'");
                Log.d("LT_SHARE", "⏰ Time difference: " + timeDiff + "ms (recent=" + isRecentChat + ")");
                Log.d("LT_SHARE", "🖼️ In image process: " + isInImageProcess);

                // אם אנחנו בתהליך שליחת תמונות, תמיד נסה לבחור אוטומטית
                if (chatTitle != null && (isRecentChat || isInImageProcess) && root != null) {
                    Log.d("LT_SHARE", "✅ All conditions met - starting auto-select");
                    // המתן מעט לפני הבחירה האוטומטית
                    mainHandler.postDelayed(() -> {
                        AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                        if (newRoot != null) {
                            autoSelectDirectShareTarget(newRoot, chatTitle);
                            newRoot.recycle();
                        }
                    }, 500); // המתן 500ms לטעינת המסך
                } else {
                    Log.d("LT_SHARE", "❌ Conditions failed:");
                    Log.d("LT_SHARE", "   - chatTitle null: " + (chatTitle == null));
                    Log.d("LT_SHARE", "   - not recent and not in process: " + (!isRecentChat && !isInImageProcess));
                    Log.d("LT_SHARE", "   - root null: " + (root == null));
                }
                return;
            }

            if (!WhatsAppUtils.isWhatsAppPackage(pkg)) {
                if (overlayManager != null && overlayManager.isShown()) {
                    overlayManager.hide();
                    stopContinuousDecryption();
                }
                stopContinuousDecryption();
                pollOverlayHideAfterExit();
                return;
            }

            lastWhatsAppPackage = pkg;

            boolean forbidden = pkg.equals(getPackageName()) || shouldHideDecryptionOverlays();
            if (forbidden) {
                if (overlayManager.isShown()) {
                    if (!pkg.toLowerCase().contains("systemui")
                            && !pkg.toLowerCase().contains("com.android")
                            && !pkg.toLowerCase().contains("resolver")
                            && !ImagePickerProxyActivity.isDialogOpen()) {
                        overlayManager.hide();
                        stopContinuousDecryption();
                    } else {
                        overlayManager.hideBubblesOnly();
                    }
                }
                return;
            }

            long now = System.currentTimeMillis();
            boolean decryptOn = decryptAuthenticated && now <= decryptExpiryTimestamp;

            if (!isReplacing
                    && WhatsAppUtils.isWhatsAppPackage(pkg)
                    && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                handleAutoEncrypt();
            }

            if (decryptOn
                    && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                    && now - lastDecryptTs > DECRYPT_THROTTLE_MS) {
                requestDebouncedScan();
            }

            if (decryptOn && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isFullScreenImageView(cls)) {
                decryptFullScreenImage(root);
                if (overlayManager.isShown()) overlayManager.hide();
                return;
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                boolean inWA = WhatsAppUtils.isWhatsAppPackage(pkg);
                boolean isConversationScreen = inWA && isConversationScreen(root);
                boolean isFullScreenImage = isFullScreenImageView(cls);
                boolean shouldShow = false;

                if (isConversationScreen && !overlayHiddenByUser) {
                    String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
                    String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
                    if (peerPhone != null && peerPhone.length() > 7) {
                        shouldShow = true;
                    } else {
                        peerPhone = findAndCachePhoneForChat(this, chatTitle);
                        if (peerPhone != null && peerPhone.length() > 7) {
                            shouldShow = true;
                        }
                    }
                }

                Log.d("LT_OVERLAY", "inWA=" + inWA +
                        " isConv=" + isConversationScreen +
                        " shouldShow=" + shouldShow +
                        " overlayShown=" + overlayManager.isShown());

                if (isFullScreenImage) {
                    if (decryptOn) decryptFullScreenImage(root);
                    if (overlayManager.isShown()) overlayManager.hide();
                    return;
                }

                if (!isReallyInWhatsApp()) {
                    if (overlayManager != null) overlayManager.hide();
                    stopContinuousDecryption();
                    return;
                }

                if (shouldShow) {
                    if (!overlayManager.isShown()) {
                        AccessibilityNodeInfo finalRoot = root;
                        overlayManager.show(
                                v -> {
                                    String chatTitle = WhatsAppUtils.getCurrentChatTitle(finalRoot);
                                    Intent pick = new Intent(this, ImagePickerProxyActivity.class);
                                    pick.putExtra("chat_title", chatTitle);
                                    pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(pick);
                                },
                                v -> {
                                    decryptAuthenticated = false;
                                    overlayHiddenByUser = false;
                                    overlayManager.stopTimer();
                                    overlayManager.clearDecryptOverlays();
                                    overlayManager.hide();
                                    resetToDefaultOverlay();
                                },
                                v -> showDecryptAuthDialog()
                        );
                        overlayManager.updateToTopCenter();
                    }
                    overlayManager.updateOverlaysVisibility();
                } else if (overlayManager.isShown()) {
                    overlayManager.hide();
                }
            }

            if (decryptAuthenticated && System.currentTimeMillis() < decryptExpiryTimestamp) {
                if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    requestDebouncedScan();
                    return;
                }
            }

            if (WhatsAppUtils.isWhatsAppPackage(pkg) &&
                    type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    isConversationScreen(root)) {

                if (isProcessingImageQueue) {
                    Log.d("LT_QUEUE", "🔙 זוהה חזרה לשיחה במהלך עיבוד תור תמונות");

                    if (!isWaitingForImageSent && !pendingImages.isEmpty()) {
                        Log.d("LT_QUEUE", "📤 יש עוד " + pendingImages.size() + " תמונות לשלוח");
                        scheduleNextImageSending();
                    }
                    else if (!isWaitingForImageSent && pendingImages.isEmpty()) {
                        Log.d("LT_QUEUE", "✅ סיימנו את כל התמונות!");
                        resetImageQueueState();
                    }
                }
            }

        } catch (Exception e) {
            Log.e("MyAccessibilityService", "onAccessibilityEvent exception", e);
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Exception e) {
                    Log.w("LT_CLEANUP", "Error recycling root", e);
                }
            }
        }
    }

    // הוסף גם את המתודה הזו כדי לעדכן את הזמן לפני שליחת תמונות:

    public void autoSelectDirectShareTarget(AccessibilityNodeInfo root, String chatTitle) {
        if (root == null || chatTitle == null || chatTitle.isEmpty()) {
            Log.d("LT_SHARE", "❌ Cannot auto-select: root=" + (root == null ? "null" : "exists") + ", chatTitle='" + chatTitle + "'");
            return;
        }

        String normChatTitle = chatTitle.trim().toLowerCase();
        Log.d("LT_SHARE", "🔍 Starting auto-select for chat: '" + chatTitle + "' (normalized: '" + normChatTitle + "')");

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();

        // שיטה 1: חיפוש לפי מבנה של Direct Share (אנדרואיד חדש)
        Log.d("LT_SHARE", "📱 Method 1: Looking for Direct Share structure...");
        findDirectShareTargets(root, normChatTitle, candidates);

        // שיטה 2: חיפוש ברשימת אפליקציות רגילה (אנדרואיד ישן)
        if (candidates.isEmpty()) {
            Log.d("LT_SHARE", "📱 Method 2: Looking for app list items...");
            findAppListItems(root, normChatTitle, candidates);
        }

        // שיטה 3: חיפוש כללי של כל node שמכיל WhatsApp ואולי את שם השיחה
        if (candidates.isEmpty()) {
            Log.d("LT_SHARE", "📱 Method 3: General WhatsApp search...");
            findAnyWhatsAppNode(root, candidates);
        }

        // שיטה 4: חיפוש לפי ViewGroup שמכיל טקסט רלוונטי
        if (candidates.isEmpty()) {
            Log.d("LT_SHARE", "📱 Method 4: ViewGroup search...");
            findViewGroupWithText(root, normChatTitle, candidates);
        }

        Log.d("LT_SHARE", "📊 Found " + candidates.size() + " candidates");

        // נסה ללחוץ על המועמדים
        for (int i = 0; i < candidates.size(); i++) {
            AccessibilityNodeInfo node = candidates.get(i);
            Log.d("LT_SHARE", "🎯 Attempting to click candidate " + (i + 1) + "/" + candidates.size());

            // הדפס מידע על הnode
            printNodeInfo(node, i);

            if (tryClickNode(node)) {
                Log.d("LT_SHARE", "✅ Successfully clicked on target!");
                break;
            } else {
                Log.d("LT_SHARE", "❌ Failed to click candidate " + (i + 1));
            }

            node.recycle();
        }

        if (candidates.isEmpty()) {
            Log.d("LT_SHARE", "⚠️ No candidates found - printing tree for debugging:");
            printAllNodes(root, 0);
        }
    }

    // חיפוש Direct Share targets (אנדרואיד חדש)
    private void findDirectShareTargets(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        // חפש לפי ID של Direct Share
        List<AccessibilityNodeInfo> byId = node.findAccessibilityNodeInfosByViewId("android:id/chooser_row_text_option");
        for (AccessibilityNodeInfo item : byId) {
            String text = getNodeTextRecursive(item).toLowerCase();
            Log.d("LT_SHARE", "  Found direct share item: '" + text + "'");
            if (text.contains("whatsapp") && (normChatTitle.isEmpty() || text.contains(normChatTitle))) {
                result.add(AccessibilityNodeInfo.obtain(item));
            }
        }

        // חפש גם לפי ID אחר
        byId = node.findAccessibilityNodeInfosByViewId("android:id/text1");
        for (AccessibilityNodeInfo item : byId) {
            String text = getNodeTextRecursive(item).toLowerCase();
            if (text.contains("whatsapp")) {
                AccessibilityNodeInfo parent = item.getParent();
                if (parent != null && parent.isClickable()) {
                    Log.d("LT_SHARE", "  Found clickable parent with WhatsApp text");
                    result.add(AccessibilityNodeInfo.obtain(parent));
                } else if (item.isClickable()) {
                    Log.d("LT_SHARE", "  Found clickable WhatsApp item");
                    result.add(AccessibilityNodeInfo.obtain(item));
                }
            }
        }
    }

    // חיפוש ברשימת אפליקציות
    private void findAppListItems(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        // חפש ListView או RecyclerView
        findListItems(node, normChatTitle, result);
    }

    private void findListItems(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";

        // אם זה ListView או RecyclerView
        if (className.contains("ListView") || className.contains("RecyclerView") || className.contains("GridView")) {
            Log.d("LT_SHARE", "  Found list view: " + className);

            // עבור על כל הילדים
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    String childText = getNodeTextRecursive(child).toLowerCase();

                    // בדוק אם זה WhatsApp
                    if (childText.contains("whatsapp")) {
                        Log.d("LT_SHARE", "    Found WhatsApp item at position " + i + ": '" + childText + "'");

                        // בדוק אם יש את שם השיחה או שזה הפריט הראשון
                        if (normChatTitle.isEmpty() || childText.contains(normChatTitle) || i == 0) {
                            if (child.isClickable()) {
                                Log.d("LT_SHARE", "      Item is clickable - adding to candidates");
                                result.add(AccessibilityNodeInfo.obtain(child));
                            } else {
                                // חפש parent שניתן ללחוץ עליו
                                AccessibilityNodeInfo clickableParent = findClickableParent(child);
                                if (clickableParent != null) {
                                    Log.d("LT_SHARE", "      Found clickable parent - adding to candidates");
                                    result.add(AccessibilityNodeInfo.obtain(clickableParent));
                                }
                            }
                        }
                    }
                    child.recycle();
                }
            }
        }

        // חיפוש רקורסיבי
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findListItems(child, normChatTitle, result);
                child.recycle();
            }
        }
    }

    // חיפוש ViewGroup עם טקסט
    private void findViewGroupWithText(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String nodeText = getNodeTextRecursive(node).toLowerCase();

        // אם זה ViewGroup שמכיל WhatsApp
        if (className.contains("ViewGroup") || className.contains("LinearLayout") || className.contains("RelativeLayout")) {
            if (nodeText.contains("whatsapp")) {
                Log.d("LT_SHARE", "  Found ViewGroup with WhatsApp: '" + nodeText + "'");

                if (node.isClickable()) {
                    // אם השיחה הרצויה נמצאת בטקסט או אין שיחה ספציפית
                    if (normChatTitle.isEmpty() || nodeText.contains(normChatTitle)) {
                        Log.d("LT_SHARE", "    ViewGroup is clickable and matches - adding");
                        result.add(AccessibilityNodeInfo.obtain(node));
                    }
                }
            }
        }

        // חיפוש רקורסיבי
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findViewGroupWithText(child, normChatTitle, result);
                child.recycle();
            }
        }
    }

    // מציאת parent שניתן ללחוץ עליו
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        while (current != null) {
            if (current.isClickable()) {
                return current;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return null;
    }

    // חיפוש כללי של WhatsApp
    private void findAnyWhatsAppNode(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        String text = getNodeTextRecursive(node).toLowerCase();

        if (text.contains("whatsapp") && node.isClickable()) {
            Log.d("LT_SHARE", "  Found general clickable WhatsApp node: '" + text + "'");
            result.add(AccessibilityNodeInfo.obtain(node));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAnyWhatsAppNode(child, result);
                child.recycle();
            }
        }
    }

    // קבלת טקסט מnode כולל כל הילדים
    private String getNodeTextRecursive(AccessibilityNodeInfo node) {
        if (node == null) return "";

        StringBuilder sb = new StringBuilder();

        // הוסף טקסט של הnode עצמו
        if (node.getText() != null) {
            sb.append(node.getText().toString()).append(" ");
        }
        if (node.getContentDescription() != null) {
            sb.append(node.getContentDescription().toString()).append(" ");
        }

        // הוסף טקסט של הילדים
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                sb.append(getNodeTextRecursive(child)).append(" ");
                child.recycle();
            }
        }

        return sb.toString().trim();
    }

    // הדפסת מידע על node
    private void printNodeInfo(AccessibilityNodeInfo node, int index) {
        if (node == null) return;

        Log.d("LT_SHARE", "  📋 Candidate " + (index + 1) + " info:");
        Log.d("LT_SHARE", "    Class: " + node.getClassName());
        Log.d("LT_SHARE", "    Text: " + node.getText());
        Log.d("LT_SHARE", "    Description: " + node.getContentDescription());
        Log.d("LT_SHARE", "    Clickable: " + node.isClickable());
        Log.d("LT_SHARE", "    Bounds: " + getBoundsString(node));
    }

    // קבלת מחרוזת של הגבולות
    private String getBoundsString(AccessibilityNodeInfo node) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        return bounds.toString();
    }

    // ניסיון ללחוץ על node
    private boolean tryClickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // נסה ACTION_CLICK
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d("LT_SHARE", "    ✓ ACTION_CLICK succeeded");
            return true;
        }

        // נסה ACTION_SELECT ואז ACTION_CLICK
        if (node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
            Log.d("LT_SHARE", "    ACTION_SELECT succeeded, trying ACTION_CLICK...");
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d("LT_SHARE", "    ✓ ACTION_CLICK after SELECT succeeded");
                return true;
            }
        }

        // נסה לחץ על הparent
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null && parent.isClickable()) {
            Log.d("LT_SHARE", "    Trying parent click...");
            boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            parent.recycle();
            if (success) {
                Log.d("LT_SHARE", "    ✓ Parent click succeeded");
                return true;
            }
        }

        Log.d("LT_SHARE", "    ✗ All click attempts failed");
        return false;
    }
    private void logNodeDetails(AccessibilityNodeInfo node, String prefix) {
        if (node == null) {
            Log.d("LT_SHARE", prefix + ": node is null");
            return;
        }

        String id = node.getViewIdResourceName();
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        Log.d("LT_SHARE", prefix + " Details:");
        Log.d("LT_SHARE", "  ID: " + id);
        Log.d("LT_SHARE", "  Text: '" + text + "'");
        Log.d("LT_SHARE", "  Desc: '" + desc + "'");
        Log.d("LT_SHARE", "  Class: " + cls);
        Log.d("LT_SHARE", "  Bounds: " + bounds);
        Log.d("LT_SHARE", "  Clickable: " + node.isClickable());
        Log.d("LT_SHARE", "  Enabled: " + node.isEnabled());
        Log.d("LT_SHARE", "  Visible: " + node.isVisibleToUser());
    }
    private void requestImmediatePositionUpdate() {
        overlayRefresh.removeCallbacks(scanRunnable);

        mainHandler.post(() -> {
            if (decryptAuthenticated && isReallyInWhatsApp()) {
                decryptChatBubbles();
            }
        });
    }


    private boolean isValidShareTarget(AccessibilityNodeInfo node, String normChatTitle) {
        if (node == null || !node.isClickable() || !node.isEnabled()) {
            return false;
        }

        // בדוק ID
        String id = node.getViewIdResourceName();
        if (id != null && id.contains("sem_chooser_grid_item_view")) {

            // בדוק תיאור
            String desc = node.getContentDescription() != null ?
                    node.getContentDescription().toString() : "";
            if (!desc.isEmpty() && normalizeChatTitle(desc).contains(normChatTitle)) {
                return true;
            }

            // בדוק טקסט ילדים
            return checkChildrenForChatName(node, normChatTitle);
        }

        return false;
    }

    private boolean checkChildrenForChatName(AccessibilityNodeInfo node, String normChatTitle) {
        if (node == null) return false;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String text = child.getText() != null ? child.getText().toString() : "";
                if (!text.isEmpty() && normalizeChatTitle(text).contains(normChatTitle)) {
                    return true;
                }

                // בדוק גם ילדים של הילד
                if (checkChildrenForChatName(child, normChatTitle)) {
                    return true;
                }
            }
        }
        return false;
    }
    private List<AccessibilityNodeInfo> getAllClickableNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        collectClickableNodes(root, result);
        return result;
    }
    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node == null) return;

        if (node.isClickable() && node.isEnabled()) {
            list.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableNodes(node.getChild(i), list);
        }
    }
    private void recycleList(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) {
            if (node != null) {
                try {
                    node.recycle();
                } catch (Exception e) {
                    Log.w("LT_SHARE", "Error recycling node: " + e.getMessage());
                }
            }
        }
    }
    private void startGlobalOverlayPoll() {
        if (globalPollRunnable != null) globalPollHandler.removeCallbacks(globalPollRunnable);
        globalPollRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";
                    boolean isWA = WhatsAppUtils.isWhatsAppPackage(pkg);
                    boolean isMedia = false;
                    String cls = root != null && root.getClassName() != null ? root.getClassName().toString() : "";
                    if (cls != null) {
                        String lc = cls.toLowerCase();
                        isMedia = isMediaViewerScreen(lc);
                    }

                    if (!isWA && !isMedia) {
                        if (overlayManager != null && overlayManager.isShown()) {
                            overlayManager.hide();
                            stopContinuousDecryption();
                        }
                    }
                    if (root != null) root.recycle();
                } catch (Exception e) {
                    Log.e("LT_GLOBALPOLL", "error", e);
                }
                globalPollHandler.postDelayed(this, 500);
            }
        };
        globalPollHandler.post(globalPollRunnable);
    }
    private boolean isChooserWithContacts(AccessibilityNodeInfo root) {
        if (root == null) return false;
        // רקורסיבי: חפש node עם id של chooser grid ו-descr של וואטסאפ
        if ("com.android.intentresolver:id/sem_chooser_grid_item_view".equals(root.getViewIdResourceName())
                && root.isClickable()
                && root.getContentDescription() != null
                && root.getContentDescription().toString().toLowerCase().contains("whatsapp")) {
            return true;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (isChooserWithContacts(root.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean tryRegularClick(AccessibilityNodeInfo node, String chatTitle) {
        try {
            boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d("LT_SHARE", "🔘 Regular click result: " + clicked);

            if (clicked) {
                // בדוק אם הקליק הצליח על ידי המתנה ובדיקת מעבר למסך הבא
                return verifyClickSuccess(chatTitle, "regular");
            }
        } catch (Exception e) {
            Log.w("LT_SHARE", "Regular click failed: " + e.getMessage());
        }
        return false;
    }

    private boolean tryDelayedClick(AccessibilityNodeInfo node, String chatTitle) {
        try {
            // המתן 200ms ונסה שוב
            Thread.sleep(80);
            boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d("LT_SHARE", "⏰ Delayed click result: " + clicked);

            if (clicked) {
                return verifyClickSuccess(chatTitle, "delayed");
            }
        } catch (Exception e) {
            Log.w("LT_SHARE", "Delayed click failed: " + e.getMessage());
        }
        return false;
    }

    private boolean tryParentClick(AccessibilityNodeInfo node, String chatTitle) {
        try {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                Log.d("LT_SHARE", "👨‍👦 מנסה קליק על ההורה");
                logNodeDetails(parent, "PARENT");

                boolean clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d("LT_SHARE", "👨‍👦 Parent click result: " + clicked);

                if (clicked) {
                    boolean success = verifyClickSuccess(chatTitle, "parent");
                    parent.recycle();
                    return success;
                }
                parent.recycle();
            }
        } catch (Exception e) {
            Log.w("LT_SHARE", "Parent click failed: " + e.getMessage());
        }
        return false;
    }

    private boolean tryGestureClick(AccessibilityNodeInfo node, String chatTitle) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false;
        }

        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            Log.d("LT_SHARE", "✋ מנסה gesture click ב-" + bounds.centerX() + "," + bounds.centerY());

            Path clickPath = new Path();
            clickPath.moveTo(bounds.centerX(), bounds.centerY());

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(clickPath, 0, 50);

            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d("LT_SHARE", "✋ Gesture completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.d("LT_SHARE", "✋ Gesture cancelled");
                }
            }, null);

            Log.d("LT_SHARE", "✋ Gesture dispatch result: " + dispatched);

            if (dispatched) {
                // המתן לביצוע הגסצ'ר ובדוק הצלחה
                Thread.sleep(500);
                return verifyClickSuccess(chatTitle, "gesture");
            }

        } catch (Exception e) {
            Log.w("LT_SHARE", "Gesture click failed: " + e.getMessage());
        }
        return false;
    }

    private boolean verifyClickSuccess(String chatTitle, String method) {
        try {
            Thread.sleep(50);

            AccessibilityNodeInfo newRoot = getRootInActiveWindow();
            if (newRoot != null) {
                String pkg = newRoot.getPackageName() != null ? newRoot.getPackageName().toString() : "";
                String cls = newRoot.getClassName() != null ? newRoot.getClassName().toString() : "";

                Log.d("LT_SHARE", "🔍 אחרי " + method + " click - pkg: " + pkg + ", cls: " + cls);
                boolean success = WhatsAppUtils.isWhatsAppPackage(pkg) ||
                        cls.contains("MediaComposer") ||
                        cls.contains("mediacomposer");

                if (success) {
                    Log.d("LT_SHARE", "✅ " + method + " click הצליח! עברנו ל-" + pkg);
                    pendingImageAutoSend = true;
                    newRoot.recycle();
                    return true;
                } else {
                    Log.d("LT_SHARE", "❌ " + method + " click לא הוביל למסך הנכון");
                }

                newRoot.recycle();
            }
        } catch (Exception e) {
            Log.w("LT_SHARE", "Error verifying click success: " + e.getMessage());
        }
        return false;
    }
    private void tryBroadSearch(AccessibilityNodeInfo root, String chatTitle) {
        Log.d("LT_SHARE", "🔍 מתחיל חיפוש רחב עבור: " + chatTitle);

        try {
            // חפש כל הכפתורים/אלמנטים הניתנים ללחיצה
            List<AccessibilityNodeInfo> allClickable = getAllClickableNodes(root);

            Log.d("LT_SHARE", "📊 נמצאו " + allClickable.size() + " אלמנטים ניתנים ללחיצה");

            String normChat = normalizeChatTitle(chatTitle);

            for (AccessibilityNodeInfo node : allClickable) {
                if (node == null) continue;

                // בדוק אם התוכן או התיאור מתאימים
                String text = node.getText() != null ? node.getText().toString() : "";
                String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
                String combinedText = (text + " " + desc).toLowerCase();

                if (normalizeChatTitle(combinedText).contains(normChat)) {
                    Log.d("LT_SHARE", "🎯 נמצא התאמה בחיפוש רחב: " + combinedText);

                    if (tryRegularClick(node, chatTitle) ||
                            tryGestureClick(node, chatTitle)) {
                        recycleList(allClickable);
                        return;
                    }
                }
            }

            recycleList(allClickable);
            Log.w("LT_SHARE", "❌ גם החיפוש הרחב לא הצליח");

        } catch (Exception e) {
            Log.e("LT_SHARE", "Error in broad search: " + e.getMessage(), e);
        }
    }
    private String normalizeChatTitle(String title) {
        if (title == null) return "";
        // הסר אמוג'ים, רווחים מיותרים, שים lower case, הסר WhatsApp מהמחרוזת
        return title.replaceAll("(?i)whatsapp", "") // הסר WhatsApp
                .replaceAll("[^\\p{L}\\p{Nd}]+", "") // הסר תווים לא אותיות/ספרות
                .trim()
                .toLowerCase();
    }

    private boolean isSystemChooserEvent(String pkg, String cls) {
        if (pkg == null || cls == null) return false;

        String lcPkg = pkg.toLowerCase();
        String lcCls = cls.toLowerCase();

        // בדיקה מורחבת עבור כל סוגי ה-Chooser
        boolean isSystemPackage = (
                lcPkg.equals("android") ||
                        lcPkg.contains("com.android.internal") ||
                        lcPkg.contains("com.android.systemui") ||
                        lcPkg.contains("intentresolver") ||
                        lcPkg.contains("resolver")
        );

        boolean isChooserClass = (
                lcCls.contains("chooser") ||
                        lcCls.contains("resolver") ||
                        lcCls.contains("shareactivity") ||
                        lcCls.equals("com.android.internal.app.chooseractivity")
        );

        // וודא שזה לא וואטסאפ או פייסבוק
        if (lcPkg.contains("whatsapp") || lcPkg.contains("facebook")) {
            return false;
        }

        Log.d("LT_SHARE", "isSystemChooserEvent check: pkg=" + pkg +
                " cls=" + cls +
                " isSystemPackage=" + isSystemPackage +
                " isChooserClass=" + isChooserClass);

        return isSystemPackage && isChooserClass;
    }

    // שיפור המתודה isShareChooserScreen:
    private boolean isShareChooserScreen(String className, AccessibilityNodeInfo root) {
        if (className == null) return false;
        String lc = className.toLowerCase();

        // בדיקה מורחבת של class names
        boolean isChooserClass = (
                lc.contains("chooser") ||
                        lc.contains("resolveractivity") ||
                        lc.contains("chooseractivity") ||
                        lc.contains("shareactivity") ||
                        lc.equals("com.android.internal.app.chooseractivity")
        );

        if (isChooserClass) {
            Log.d("LT_SHARE", "Found chooser by class name: " + className);
            return true;
        }

        // בדיקה נוספת לפי מבנה העץ
        if (root != null) {
            // חפש ListView או RecyclerView עם אפליקציות
            boolean hasAppList = findAppListInTree(root);
            if (hasAppList) {
                Log.d("LT_SHARE", "Found chooser by tree structure");
                return true;
            }
        }

        return false;
    }

    // הוסף מתודת עזר לחיפוש רשימת אפליקציות:
    private boolean findAppListInTree(AccessibilityNodeInfo node) {
        if (node == null) return false;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";

        // בדוק אם זה ListView או RecyclerView
        if (className.contains("ListView") || className.contains("RecyclerView") ||
                className.contains("GridView")) {

            // ספור כמה items יש ברשימה
            int childCount = node.getChildCount();
            if (childCount > 1) {
                // בדוק אם לפחות אחד מהם מכיל WhatsApp
                for (int i = 0; i < Math.min(childCount, 5); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        String text = getNodeTextRecursive(child);
                        if (text.toLowerCase().contains("whatsapp")) {
                            Log.d("LT_SHARE", "Found WhatsApp in app list");
                            return true;
                        }
                    }
                }
            }
        }

        // חיפוש רקורסיבי
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findAppListInTree(child)) {
                return true;
            }
        }

        return false;
    }



    // מתודה חדשה לחיפוש באנדרואיד ישן:
    private void findOldAndroidTargets(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        // בדוק אם זה node שניתן ללחיצה
        if (node.isClickable() && node.isEnabled()) {
            String text = getNodeTextRecursive(node);
            String normText = normalizeChatTitle(text);

            // בדוק אם מכיל WhatsApp
            if (normText.contains("whatsapp")) {
                // בדוק אם מכיל גם את שם הצ'אט או שזה סתם WhatsApp
                if (normText.contains(normChatTitle) || normChatTitle.isEmpty()) {
                    Log.d("LT_SHARE", "מצאתי WhatsApp node באנדרואיד ישן: " + text);
                    result.add(node);
                }
            }
        }

        // המשך רקורסיבית
        for (int i = 0; i < node.getChildCount(); i++) {
            findOldAndroidTargets(node.getChild(i), normChatTitle, result);
        }
    }



    void printAllNodes(AccessibilityNodeInfo node, int depth) {
        if (node == null) return;
        String pad = new String(new char[depth]).replace("\0", "  ");
        Log.d("LT_NODE", pad +
                "cls=" + node.getClassName() +
                " id=" + node.getViewIdResourceName() +
                " txt='" + node.getText() + "'" +
                " desc='" + node.getContentDescription() + "'" +
                " clickable=" + node.isClickable());
        for (int i = 0; i < node.getChildCount(); i++) {
            printAllNodes(node.getChild(i), depth + 1);
        }
    }
    public void startContinuousDecryption() {
        Log.d("LT_DECRYPT", "🟢 מפעיל פענוח רציף");
        decryptAlwaysOn = true;
        mainHandler.post(() -> {
            decryptChatBubbles();
            startDecryptionLoop();
        });
    }

    private void startDecryptionLoop() {
        if (decryptRunnable == null) {
            decryptRunnable = new Runnable() {
                @Override
                public void run() {
                    if (decryptAlwaysOn && decryptAuthenticated) {
                        decryptChatBubbles();
                        decryptHandler.postDelayed(this, 200);
                    }
                }
            };
        }
        decryptHandler.post(decryptRunnable);
    }

    public void stopContinuousDecryption() {
        Log.d("LT_DECRYPT", "עוצר פיענוח רציף");

        decryptAlwaysOn = false;
        decryptAuthenticated = false;

        if (decryptHandler != null) {
            decryptHandler.removeCallbacksAndMessages(null);
        }
        if (overlayRefresh != null) {
            overlayRefresh.removeCallbacksAndMessages(null);
        }

        if (overlayManager != null) {
            overlayManager.clearDecryptOverlays();
            overlayManager.cleanupAllBubbles();
            overlayManager.cleanupImageOverlays();
        }
    }

    @Override
    public void onInterrupt() {}
    private boolean isFullScreenImageView(String cls) {
        return cls.contains("ImagePreviewActivity")
                || cls.contains("FullImageActivity")
                || cls.contains("ViewImageActivity")
                || cls.contains("GalleryActivity")
                || cls.toLowerCase().contains("photo")
                || cls.toLowerCase().contains("image")
                || cls.toLowerCase().contains("media");
    }
    private boolean gestureClickNode(AccessibilityNodeInfo node) {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
        try { Thread.sleep(400); } catch (Exception ignored) {}
        return true;
    }
    private void clearImageQueue() {
        Log.d("LT_QUEUE", "🧹 מנקה תור תמונות");

        // בטל timeout רץ
        if (currentTimeoutRunnable != null) {
            imageQueueHandler.removeCallbacks(currentTimeoutRunnable);
            currentTimeoutRunnable = null;
        }

        // נקה תור
        pendingImages.clear();

        // אפס מצב - הוסף את השורה החסרה הזו:
        isProcessingImageQueue = false;
        isWaitingForImageSent = false;
        lastSentImageKey = "";
        lastImageSentTime = 0;
        pendingImageAutoSend = false;
    }
    // === FAST SEND: stronger polling for the green send button ===
    private void tryAutoClickSendRecurring(int attemptCount) {
        if (fastSendLoopCancelled) return;
        if (attemptCount > MAX_SEND_ATTEMPTS) {
            Log.d("LT_FASTSEND", "⛔ ניסיון לשליחה נכשל אחרי " + attemptCount + " ניסיונות");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            retryDelayed(attemptCount);
            return;
        }

        boolean clicked = false;

        // 1) חיפוש לפי IDs מוכרים
        for (String id : SEND_BUTTON_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n != null && n.isVisibleToUser() && n.isEnabled()) {
                        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (!ok) ok = gestureClickNode(n);
                        if (ok) {
                            clicked = true;
                            break;
                        }
                    }
                }
            }
            if (clicked) break;
        }

        // 2) חיפוש לפי contentDescription (למקרה של שפה/סקין)
        if (!clicked) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            collectClickableNodes(root, all);
            for (AccessibilityNodeInfo n : all) {
                CharSequence d = n.getContentDescription();
                CharSequence t = n.getText();
                String s = ((d != null ? d.toString() : "") + " " + (t != null ? t.toString() : "")).toLowerCase();
                for (String key : SEND_BUTTON_DESCS) {
                    if (s.contains(key.toLowerCase())) {
                        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (!ok) ok = gestureClickNode(n);
                        clicked = ok;
                        break;
                    }
                }
                if (clicked) break;
            }
            recycleList(all);
        }

        // 3) אם לחצנו — אמת חזרה לצ'אט מהר
        if (clicked) {
            if (root != null) root.recycle();
            waitUntilConversationOrAssumeSuccess(0);
            return;
        }

        if (root != null) root.recycle();
        retryDelayed(attemptCount);
    }

    private void retryDelayed(int attemptCount) {
        mainHandler.postDelayed(() -> tryAutoClickSendRecurring(attemptCount + 1), SEND_BTN_POLL_MS);
    }

    // אחרי לחיצה על הכפתור הירוק, נחכה שה־MediaComposer ייעלם/נחזור לשיחה
    private void waitUntilConversationOrAssumeSuccess(int attempt) {
        if (!isWaitingForImageSent) return;

        AccessibilityNodeInfo r = getRootInActiveWindow();
        boolean inConv = false;
        if (r != null) {
            inConv = isConversationScreen(r) && WhatsAppUtils.isWhatsAppPackage(
                    r.getPackageName() != null ? r.getPackageName().toString() : ""
            );
            try { r.recycle(); } catch (Exception ignore) {}
        }

        if (inConv) {
            onImageSentSuccessfully();
            return;
        }

        // חכה מעט – עד ~1.5 שניות
        if (attempt < 60) {
            mainHandler.postDelayed(() -> waitUntilConversationOrAssumeSuccess(attempt + 1), 25);
        } else {
            // אם לא תפסנו – תני הנחה שזה הצליח (לטובת קצב) וה־timeout יחפה אם לא
            Log.d("LT_FASTSEND", "Assuming success after waiting");
            onImageSentSuccessfully();
        }
    }
    // === FAST SEND: cancellation hook ===
    private volatile boolean fastSendLoopCancelled = false;

    private void stopFastSendLoop() {
        fastSendLoopCancelled = true;
    }


    public void fetchImageCipherFromFirebase(String myPhone, String peerPhone, String imgLabel, FakeMapFetchCallback callback) {
        String docId1 = myPhone.replace("+972", "");
        String docId2 = peerPhone.replace("+972", "");
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users").document(docId1).collection("imageMap")
                .document(imgLabel).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String encrypted = doc.getString("encrypted");
                        Boolean outgoing = doc.getBoolean("outgoing");
                        String senderPhone = doc.getString("senderPhone");

                        if (encrypted != null && outgoing != null && senderPhone != null) {
                            FakeMapEntry entry = new FakeMapEntry();
                            entry.encrypted = encrypted;
                            entry.outgoing = outgoing;
                            entry.senderPhone = senderPhone;
                            String jsonEntry = new com.google.gson.Gson().toJson(entry);
                            callback.onResult(jsonEntry);
                            return;
                        }
                    }

                    // לא נמצא - חפש אצל הצד השני
                    db.collection("users").document(docId2).collection("imageMap")
                            .document(imgLabel).get()
                            .addOnSuccessListener(doc2 -> {
                                if (doc2 != null && doc2.exists()) {
                                    String encrypted = doc2.getString("encrypted");
                                    Boolean outgoing = doc2.getBoolean("outgoing");
                                    String senderPhone = doc2.getString("senderPhone");

                                    if (encrypted != null && outgoing != null && senderPhone != null) {
                                        FakeMapEntry entry = new FakeMapEntry();
                                        entry.encrypted = encrypted;
                                        entry.outgoing = outgoing;
                                        entry.senderPhone = senderPhone;
                                        String jsonEntry = new com.google.gson.Gson().toJson(entry);
                                        callback.onResult(jsonEntry);
                                        return;
                                    }
                                }
                                callback.onResult(null);
                            })
                            .addOnFailureListener(e -> callback.onResult(null));
                })
                .addOnFailureListener(e -> callback.onResult(null));
    }

    private void decryptAndShowImageFromCipher(String jsonEntry, String myPhone, String peerPhone, Rect imageBounds, String imageId) {
        Log.d("LT_IMAGE_DEBUG", "decryptAndShowImageFromCipher called with JSON entry for imageId: " + imageId);

        try {
            FakeMapEntry entry = new com.google.gson.Gson().fromJson(jsonEntry, FakeMapEntry.class);
            String senderPhone = entry.senderPhone;
            String receiverPhone = senderPhone.equals(myPhone) ? peerPhone : myPhone;

            byte[] encBytes = Base64.decode(entry.encrypted, Base64.NO_WRAP);
            byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, senderPhone, receiverPhone);

            if (plainBytes != null) {
                Bitmap origBmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                if (origBmp != null) {
                    final Rect safeBounds = new Rect(imageBounds);

                    // ✅ תיקון קריטי: המתן לרגע שבו התמונה כבר התייצבה במסך
                    mainHandler.postDelayed(() -> {
                        if (!isReallyInWhatsApp()) {
                            Log.w("LT_IMAGE_DEBUG", "Not in WhatsApp, hiding overlay");
                            if (overlayManager != null) overlayManager.hide();
                            stopContinuousDecryption();
                            return;
                        }

                        // בדיקה אם התמונה עדיין במסך
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        if (root == null || !isImageStillVisible(root, safeBounds)) {
                            Log.w("LT_IMAGE_DEBUG", "Image bounds not valid anymore, skipping overlay");
                            return;
                        }

                        if (!overlayManager.hasImageOverlay(imageId)) {
                            overlayManager.showDecryptedImageOverlay(origBmp, safeBounds, imageId);
                            Log.d("LT_IMAGE_DEBUG", "Overlay shown for imageId: " + imageId);
                        } else {
                            Log.d("LT_IMAGE_DEBUG", "Overlay already exists for imageId: " + imageId);
                        }

                        if (root != null) root.recycle();
                    }, 100); // 100ms = מספיק כדי לחכות ליציבות המסך
                }
            }
        } catch (Exception e) {
            Log.e("LT_IMAGE_DEBUG", "decryptAndShowImageFromCipher error", e);
            mainHandler.post(() -> showToast("שגיאה בפענוח תמונה"));
        }
    }
    private boolean isImageStillVisible(AccessibilityNodeInfo root, Rect originalBounds) {
        if (originalBounds == null || originalBounds.width() < 100 || originalBounds.height() < 100) return false;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenHeight = dm.heightPixels;
        int screenWidth = dm.widthPixels;

        // לא להציג אם נמצא מתחת למקלדת או בצד קצה
        return originalBounds.top >= 0 &&
                originalBounds.bottom <= screenHeight &&
                originalBounds.left >= 0 &&
                originalBounds.right <= screenWidth;
    }

    public void fetchFakeMappingFromFirebase(String myPhone, String peerPhone, String fake, FakeMapFetchCallback callback) {
        String docId1 = EncryptionHelper.normalizePhone(myPhone).replace("+972", "");
        String docId2 = EncryptionHelper.normalizePhone(peerPhone).replace("+972", "");
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        String fakeHash = EncryptionHelper.sha256(fake);

        db.collection("users").document(docId1).collection("fakeMap")
                .document(fakeHash).get()
                .addOnSuccessListener(doc -> {
                    String cipher = extractCipherFromDoc(doc);
                    if (cipher != null && !cipher.isEmpty()) {
                        Log.d("LT_FIREBASE", "Found mapping in myPhone: " + docId1 + ", fake: " + fakeHash);
                        callback.onResult(cipher);
                    } else {
                        db.collection("users").document(docId2).collection("fakeMap")
                                .document(fakeHash).get()
                                .addOnSuccessListener(doc2 -> {
                                    String cipher2 = extractCipherFromDoc(doc2);
                                    if (cipher2 != null && !cipher2.isEmpty()) {
                                        Log.d("LT_FIREBASE", "Found mapping in peerPhone: " + docId2 + ", fake: " + fakeHash);
                                        callback.onResult(cipher2);
                                    } else {
                                        Log.w("LT_FIREBASE", "No mapping found in Firebase for: " + fakeHash);
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

    // ===== helpers =====
    private static String normPhone(String p) {
        return p == null ? "" : p.replaceAll("[^0-9]", "");
    }

    private @Nullable Rect tightenBubbleBounds(AccessibilityNodeInfo node, DisplayMetrics dm) {
        // מחפש הורה עם רוחב < 90% מסך ועם מיקום שאינו צמוד לשוליים
        Rect best = null;
        int screenW = dm.widthPixels;
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            try {
                Rect b = new Rect();
                cur.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0 && b.width() < (int)(screenW * 0.90f)) {
                    best = new Rect(b);
                    break;
                }
                AccessibilityNodeInfo p = cur.getParent();
                if (cur != node) cur.recycle();
                cur = p;
            } catch (Throwable t) {
                break;
            }
        }
        if (cur != null && cur != node) try { cur.recycle(); } catch (Throwable ignore) {}
        return best;
    }

    private boolean computeOutgoingForMe(@Nullable String jsonOrNull,
                                         AccessibilityNodeInfo node,
                                         Rect bounds,
                                         String myPhone) {
        if (jsonOrNull != null && jsonOrNull.trim().startsWith("{")) {
            try {
                FakeMapEntry e = new com.google.gson.Gson().fromJson(jsonOrNull, FakeMapEntry.class);
                boolean out = normPhone(e.senderPhone).equals(normPhone(myPhone));
                Log.d("LT_COLOR", "via JSON sender=" + e.senderPhone + " my=" + myPhone + " => outgoing=" + out);
                return out;
            } catch (Throwable t) { /* נפלג לפולבק */ }
        }
        // Fallback יציב: לפי מרכז ה־Rect (לא right) ומבלי להשתמש ב־Rect-ים ברוחב מלא
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean out = bounds.centerX() > (screenW / 2);
        Log.d("LT_COLOR", "via Fallback centerX=" + bounds.centerX() + "/" + screenW + " => outgoing=" + out);
        return out;
    }
// ===== end helpers =====


    private void decryptChatBubbles(boolean isScrollEvent) {
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
            if (decryptRunning) { Log.d("LT_DECRYPT", "Already running"); return; }

            SharedPreferences creds = getApplicationContext().getSharedPreferences("UserCredentials", MODE_PRIVATE);
            String myPhone = creds.getString("myPhone", null);

            if (!isWhatsAppAccountPhoneMatches(myPhone)) {
                mainHandler.post(() -> {
                    showToast("פיענוח לא אפשרי - חשבון WhatsApp במכשיר לא תואם למשתמש!");
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                return;
            }
            if (!decryptAuthenticated || System.currentTimeMillis() > decryptExpiryTimestamp) {
                mainHandler.post(() -> { overlayManager.cleanupAllBubbles(); decryptRunning = false; });
                return;
            }

            lastDecryptTs = System.currentTimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();

            if (isScrollEvent) {
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    overlayManager.cleanupImageOverlays();
                });
            }

            if (shouldHideDecryptionOverlays() || !isConversationScreen(root)) {
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    overlayManager.cleanupImageOverlays();
                    decryptRunning = false;
                });
                if (root != null) root.recycle();
                return;
            }

            decryptRunning = true;
            lastDecryptTs = System.currentTimeMillis();

            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
            String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
            final Map<String, Rect> latestBounds = new HashMap<>();

            SharedPreferences peerNames = getApplicationContext().getSharedPreferences("Peer Names", MODE_PRIVATE);
            String cachedPeerPhone = peerNames.getString(chatTitle != null ? chatTitle.trim() : "", null);
            DisplayMetrics dm = getResources().getDisplayMetrics();

            if (peerPhone == null && cachedPeerPhone != null) peerPhone = cachedPeerPhone;

            if ((peerPhone == null || peerPhone.length() < 7) && chatTitle != null && chatTitle.length() > 0) {
                peerPhone = findAndCachePhoneForChat(this, chatTitle);
                if (peerPhone == null) {
                    final String ct = chatTitle;
                    mainHandler.post(() -> {
                        showToast("לא נמצא מספר טלפון עבור '" + ct + "' באנשי קשר.");
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
                    if (altPhone != null && !altPhone.equals(myPhone)) peerPhone = altPhone;
                }
            }

            if (myPhone == null || peerPhone == null) {
                mainHandler.post(() -> { overlayManager.cleanupAllBubbles(); decryptRunning = false; });
                if (root != null) root.recycle();
                return;
            }

            // ----- איסוף טקסטים -----
            List<Pair<String, Pair<AccessibilityNodeInfo, Rect>>> txtList = new ArrayList<>();
            for (AccessibilityNodeInfo n : WhatsAppUtils.findEncryptedMessages(root)) {
                if (!n.isEditable()) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (b.width() <= 0 || b.height() <= 0 || b.width() >= (int)(dm.widthPixels * 0.95f)) {
                        Rect tight = tightenBubbleBounds(n, dm);
                        if (tight != null) b = tight;
                    }
                    if (b.width() > 0 && b.height() > 0 && isValidBounds(b, dm)) {
                        String fullText = getFullTextFromBubble(n);
                        if (fullText != null && !fullText.isEmpty()) {
                            txtList.add(new Pair<>(fullText, new Pair<>(AccessibilityNodeInfo.obtain(n), b)));
                        }
                    }
                    n.recycle();
                }
            }

            // ----- תמונות -----
            List<Pair<AccessibilityNodeInfo, Rect>> imageButtons = WhatsAppUtils.findImageBubbleButtons(root);
            List<Pair<AccessibilityNodeInfo, Rect>> safeImageButtons = new ArrayList<>();
            for (Pair<AccessibilityNodeInfo, Rect> pair : imageButtons) {
                Rect b = new Rect(pair.second);
                if (b.width() <= 0 || b.height() <= 0 || b.width() >= (int)(dm.widthPixels * 0.95f)) {
                    Rect tight = tightenBubbleBounds(pair.first, dm);
                    if (tight != null) b = tight;
                }
                if (isValidBounds(b, dm)) {
                    safeImageButtons.add(new Pair<>(AccessibilityNodeInfo.obtain(pair.first), b));
                }
                pair.first.recycle();
            }

            SharedPreferences prefsLock = getApplicationContext().getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            Set<String> pendingImageKeySet = prefsLock.getStringSet("pending_image_keys", new HashSet<>());
            List<String> pendingImageKeys = new ArrayList<>(pendingImageKeySet);

            List<Pair<String, Rect>> imageKeysWithPositions = findImageKeysWithPositions(root);
            String batchDescription = findBatchDescription(safeImageButtons, imageKeysWithPositions);
            List<String> batchImageKeys = extractImageKeysFromBatchDescription(batchDescription);

            if (root != null) root.recycle();

            final List<Pair<String, Pair<AccessibilityNodeInfo, Rect>>> finalTxtList = txtList;
            final List<Pair<AccessibilityNodeInfo, Rect>> finalImageButtons = safeImageButtons;
            final String peerPhoneFinal = peerPhone;
            final String myPhoneFinal = myPhone;
            final List<String> pendingImageKeysFinal = new ArrayList<>(pendingImageKeys);
            final List<Pair<String, Rect>> finalImageKeysWithPositions = new ArrayList<>(imageKeysWithPositions);
            final List<String> batchImageKeysFinal = new ArrayList<>(batchImageKeys);
            final String chatTitleFinal = chatTitle;

            executor.execute(() -> {
                if (!isReallyInWhatsApp()) {
                    mainHandler.post(() -> { if (overlayManager != null) overlayManager.hide(); stopContinuousDecryption(); });
                    return;
                }

                SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
                Set<String> wantedIds = new HashSet<>();

                // ===== טקסט =====
                for (Pair<String, Pair<AccessibilityNodeInfo, Rect>> p : finalTxtList) {
                    final String fakeOrCipher = p.first;
                    final AccessibilityNodeInfo node = p.second.first;
                    final Rect bounds = p.second.second;

                    if (fakeOrCipher == null || fakeOrCipher.length() < 10) {
                        mainHandler.post(node::recycle);
                        continue;
                    }

                    String local = fakeMap.contains(fakeOrCipher) ? fakeMap.getString(fakeOrCipher, null) : null;

                    // קבע כיוון עבורי (JSON → senderPhone, אחרת fallback)
                    final boolean outgoingForMe = computeOutgoingForMe(local, node, bounds, myPhoneFinal);
                    final String senderPhone = outgoingForMe ? myPhoneFinal : peerPhoneFinal;
                    final String receiverPhone = outgoingForMe ? peerPhoneFinal : myPhoneFinal;
                    final String id = OverlayManager.stableTextId(fakeOrCipher, outgoingForMe);
                    latestBounds.put(id, new Rect(bounds));
                    wantedIds.add(id);

                    if (local != null) {
                        // local יכול להיות JSON מלא או צופן בלבד – decrypt בהתאם
                        String cipherStr = local.trim().startsWith("{")
                                ? new com.google.gson.Gson().fromJson(local, FakeMapEntry.class).encrypted
                                : local;

                        String plain = EncryptionHelper.decryptFromString(cipherStr, senderPhone, receiverPhone);
                        if (plain != null) {
                            final AccessibilityNodeInfo nodeCopy = AccessibilityNodeInfo.obtain(node);
                            mainHandler.post(() -> {
                                if (!isReallyInWhatsApp()) { if (overlayManager != null) overlayManager.hide(); stopContinuousDecryption(); nodeCopy.recycle(); return; }
                                overlayManager.showDecryptedOverlay(plain, nodeCopy, bounds, outgoingForMe, id);
                                nodeCopy.recycle();
                            });
                        } else {
                            mainHandler.post(node::recycle);
                        }
                    } else {
                        // שליפה מהענן
                        final AccessibilityNodeInfo nodeCopy = AccessibilityNodeInfo.obtain(node);
                        fetchFakeMappingFromFirebase(myPhoneFinal, peerPhoneFinal, fakeOrCipher, new FakeMapFetchCallback() {
                            @Override public void onResult(String cloudValue) {
                                if (cloudValue != null) fakeMap.edit().putString(fakeOrCipher, cloudValue).apply();

                                String cipherStr = null;
                                boolean outForMe = outgoingForMe;

                                if (cloudValue != null && cloudValue.trim().startsWith("{")) {
                                    try {
                                        FakeMapEntry e = new com.google.gson.Gson().fromJson(cloudValue, FakeMapEntry.class);
                                        outForMe = normPhone(e.senderPhone).equals(normPhone(myPhoneFinal));
                                        cipherStr = e.encrypted;
                                    } catch (Throwable ignore) {}
                                } else if (cloudValue != null) {
                                    cipherStr = cloudValue; // צופן בלבד
                                }

                                final boolean finalOut = outForMe;
                                final String sp = finalOut ? myPhoneFinal : peerPhoneFinal;
                                final String rp = finalOut ? peerPhoneFinal : myPhoneFinal;
                                final String nid = OverlayManager.stableTextId(fakeOrCipher, finalOut);
                                latestBounds.put(nid, new Rect(bounds));
                                wantedIds.add(nid);

                                if (cipherStr != null) {
                                    String plain = EncryptionHelper.decryptFromString(cipherStr, sp, rp);
                                    if (plain != null) {
                                        mainHandler.post(() -> {
                                            if (!isReallyInWhatsApp()) { if (overlayManager != null) overlayManager.hide(); stopContinuousDecryption(); nodeCopy.recycle(); return; }
                                            overlayManager.showDecryptedOverlay(plain, nodeCopy, bounds, finalOut, nid);
                                            nodeCopy.recycle();
                                        });
                                    } else {
                                        mainHandler.post(nodeCopy::recycle);
                                    }
                                } else {
                                    mainHandler.post(nodeCopy::recycle);
                                }
                            }
                        });
                        mainHandler.post(node::recycle);
                    }
                }

                // ===== תמונות =====
                boolean useBatch = !batchImageKeysFinal.isEmpty() && batchImageKeysFinal.size() >= finalImageButtons.size();
                if (useBatch) {
                    handleBatchImageDecryption(finalImageButtons, batchImageKeysFinal, myPhoneFinal, peerPhoneFinal, wantedIds, chatTitleFinal);
                } else {
                    handleIndividualImageDecryption(finalImageButtons, pendingImageKeysFinal,
                            finalImageKeysWithPositions, myPhoneFinal, peerPhoneFinal, wantedIds, prefsLock, fakeMap);
                }

                if (Math.random() < 0.1) cleanupOldBatchCache();

                mainHandler.post(() -> overlayManager.cleanupBubblesExcept(wantedIds));
                mainHandler.post(() -> decryptRunning = false);

                if (!isRetryingDecrypt) {
                    isRetryingDecrypt = true;
                    mainHandler.postDelayed(() -> {
                        isRetryingDecrypt = false;
                        AccessibilityNodeInfo checkRoot = getRootInActiveWindow();
                        if (!shouldHideDecryptionOverlays() && isConversationScreen(checkRoot)) {
                            decryptChatBubbles();
                        }
                        if (checkRoot != null) checkRoot.recycle();
                    }, 500);
                }
            });
        });
    }

    private boolean isValidBounds(Rect bounds, DisplayMetrics displayMetrics) {
        return bounds != null &&
                bounds.width() > 50 &&
                bounds.height() > 30 &&
                bounds.left >= 0 &&
                bounds.right <= displayMetrics.widthPixels &&
                bounds.top >= 0 &&
                bounds.bottom <= displayMetrics.heightPixels;
    }
    private void decryptAndShowImageForKey(String imageKey, Rect bounds, String myPhone, String peerPhone) {
        SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
        String entryJson = fakeMap.getString(imageKey, null);
        if (entryJson == null && imageKey.contains("_")) {
            String baseKey = imageKey.replaceAll("(_\\d+)$", "");
            entryJson = fakeMap.getString(baseKey, null);
            Log.d("LT_IMAGE_BUBBLE", "Trying base key: " + baseKey + " for imageKey: " + imageKey);
        }

        Log.d("LT_IMAGE_BUBBLE", "decryptAndShowImageForKey: imageKey=" + imageKey +
                ", hasLocalEntry=" + (entryJson != null && !entryJson.isEmpty()) +
                ", entryStartsWithJson=" + (entryJson != null && entryJson.trim().startsWith("{")));

        if (entryJson != null && entryJson.trim().startsWith("{")) {
            try {
                FakeMapEntry entry = new com.google.gson.Gson().fromJson(entryJson, FakeMapEntry.class);
                String senderPhone = entry.senderPhone;
                String receiverPhone = senderPhone.equals(myPhone) ? peerPhone : myPhone;
                byte[] cipherData = android.util.Base64.decode(entry.encrypted, android.util.Base64.NO_WRAP);

                Log.d("LT_IMAGE_BUBBLE", "Attempting decryption with JSON entry, cipherData.length=" + cipherData.length);

                byte[] plainBytes = EncryptionHelper.decryptImage(cipherData, senderPhone, receiverPhone);
                if (plainBytes != null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                    if (bmp != null) {
                        mainHandler.post(() -> overlayManager.showDecryptedImageOverlay(bmp, bounds, imageKey));
                        return;
                    }
                } else {
                    Log.e("LT_IMAGE_BUBBLE", "Failed to decrypt image with JSON entry!");
                }
            } catch (Exception e) {
                Log.e("LT_IMAGE_BUBBLE", "שגיאה בפענוח תמונה מ-JSON", e);
            }
        } else {
            SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            String origPath = prefs.getString("origPath_for_" + imageKey, null);
            if (origPath == null && imageKey.contains("_")) {
                String baseKey = imageKey.replaceAll("(_\\d+)$", "");
                origPath = prefs.getString("origPath_for_" + baseKey, null);
            }

            Log.d("LT_IMAGE_BUBBLE", "Local file fallback: origPath=" + origPath);

            if (origPath != null) {
                try {
                    byte[] encBytes = com.example.locktalk_01.utils.FileUtils.readBytesFromFile(origPath);
                    Log.d("LT_IMAGE_BUBBLE", "Read local encrypted file, size=" + encBytes.length);

                    byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, myPhone, peerPhone);
                    if (plainBytes != null) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                        if (bmp != null) {
                            mainHandler.post(() -> overlayManager.showDecryptedImageOverlay(bmp, bounds, imageKey));
                            return;
                        }
                    } else {
                        Log.e("LT_IMAGE_BUBBLE", "Failed to decrypt local image file!");
                    }
                } catch (Exception e) {
                    Log.e("LT_IMAGE_BUBBLE", "שגיאה בפענוח תמונה מקובץ", e);
                }
            }
        }



        Log.d("LT_IMAGE_BUBBLE", "No local data found, attempting Firebase fetch for imageKey: " + imageKey);
        fetchImageCipherFromFirebase(myPhone, peerPhone, imageKey, new FakeMapFetchCallback() {
            @Override
            public void onResult(String jsonEntry) {
                if (jsonEntry != null) {
                    // מומלץ לשמור ל־fakeMap למטמון future calls
                    SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
                    fakeMap.edit().putString(imageKey, jsonEntry).apply();

                    // **קרא כאן לפונקציה שמפענחת JSON שהגיע מהענן**
                    decryptAndShowImageFromCipher(jsonEntry, myPhone, peerPhone, bounds, imageKey);
                } else {
                    Log.e("LT_IMAGE_BUBBLE", "No mapping found in Firebase for: " + imageKey);
                    mainHandler.post(() -> showToast("שגיאה: לא נמצא פיענוח לתמונה בענן"));
                }
            }
        });
    }

    public String getFullTextFromBubble(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        // קח את הטקסט של ה-node אם יש
        if (node.getText() != null && node.getText().length() > 0) {
            sb.append(node.getText().toString());
        }
        // עבור על כל הילדים ואסוף טקסט מהם
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String childText = getFullTextFromBubble(child);
                if (!childText.isEmpty()) {
                    sb.append(childText);
                }
                child.recycle();
            }
        }
        return sb.toString();
    }

    private String findBatchDescription(List<Pair<AccessibilityNodeInfo, Rect>> imageButtons, List<Pair<String, Rect>> imageKeysWithPositions) {
        for (Pair<AccessibilityNodeInfo, Rect> btnPair : imageButtons) {
            AccessibilityNodeInfo n = btnPair.first;
            if (n.getContentDescription() != null && n.getContentDescription().length() > 40) {
                String desc = n.getContentDescription().toString();
                if (isBatchDescription(desc)) {
                    return desc;
                }
            }
            if (n.getText() != null && n.getText().length() > 40) {
                String text = n.getText().toString();
                if (isBatchDescription(text)) {
                    return text;
                }
            }
        }
        for (Pair<String, Rect> pair : imageKeysWithPositions) {
            String key = pair.first;
            if (key.contains(",") && key.length() > 60) {
                return key;
            }
        }

        return null;
    }

    private boolean isBatchDescription(String desc) {
        return desc.matches(".*\\d+\\s*:.*") || desc.split("[,\\s]+").length > 1;
    }

    private List<String> extractImageKeysFromBatchDescription(String batchDescription) {
        List<String> keys = new ArrayList<>();
        if (batchDescription == null) return keys;

        // אם יש מספור בתיאור
        if (batchDescription.matches(".*\\d+\\s*:.*")) {
            Map<Integer, String> numberedKeys = extractNumberedImageKeys(batchDescription);
            for (int i = 1; i <= numberedKeys.size(); i++) {
                if (numberedKeys.containsKey(i)) {
                    keys.add(numberedKeys.get(i));
                }
            }
            Log.d("LT_IMAGE_BUBBLE", "Found batch description with " + keys.size() + " numbered keys");
        } else {
            // fallback - פסיקים
            String[] arr = batchDescription.split("[,\\s]+");
            for (String k : arr) {
                k = k.trim();
                if (k.length() >= 32 && k.matches("^[a-fA-F0-9]{32,}(_\\d+)?$")) {
                    keys.add(k);
                }
            }
            if (!keys.isEmpty()) {
                Log.d("LT_IMAGE_BUBBLE", "Found batch keys in position description: " + keys.size());
            }
        }

        return keys;
    }

    private Map<Integer, String> extractNumberedImageKeys(String desc) {
        Map<Integer, String> numberedKeys = new HashMap<>();
        Pattern p = Pattern.compile("(\\d+)\\s*:\\s*([a-fA-F0-9]{32,})");
        Matcher m = p.matcher(desc);
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            String key = m.group(2);
            numberedKeys.put(num, key);
        }
        return numberedKeys;
    }

    private void handleBatchImageDecryption(List<Pair<AccessibilityNodeInfo, Rect>> imageButtons,
                                            List<String> batchImageKeys, String myPhone, String peerPhone,
                                            Set<String> wantedIds, String chatTitle) {
        List<Pair<String, Rect>> mapped = mapBatchKeysToButtons(batchImageKeys, imageButtons);

        // שמירת הסדר לעתיד
        String chatId = chatTitle != null ? chatTitle : "unknown";
        saveImageOrder(batchImageKeys, chatId);

        for (Pair<String, Rect> pair : mapped) {
            String imageKey = pair.first;
            Rect bounds = pair.second;
            wantedIds.add(imageKey);
            decryptAndShowImageForKey(imageKey, bounds, myPhone, peerPhone);
        }

        // מחזר את כל ה-AccessibilityNodeInfo
        for (Pair<AccessibilityNodeInfo, Rect> btnPair : imageButtons) {
            btnPair.first.recycle();
        }
    }

    private void handleIndividualImageDecryption(List<Pair<AccessibilityNodeInfo, Rect>> imageButtons,
                                                 List<String> pendingImageKeys,
                                                 List<Pair<String, Rect>> imageKeysWithPositions,
                                                 String myPhone, String peerPhone, Set<String> wantedIds,
                                                 SharedPreferences prefsLock, SharedPreferences fakeMap) {
        List<String> workingPendingKeys = new ArrayList<>(pendingImageKeys);
        List<Pair<String, Rect>> availableImageKeysWithPos = new ArrayList<>(imageKeysWithPositions);

        for (int bubbleIndex = 0; bubbleIndex < imageButtons.size(); bubbleIndex++) {
            Pair<AccessibilityNodeInfo, Rect> btnPair = imageButtons.get(bubbleIndex);
            AccessibilityNodeInfo btnNode = btnPair.first;
            Rect bounds = btnPair.second;
            String imageKey = null;

            // 1: בסביבת הכפתור
            imageKey = findImageKeyAroundButton(btnNode);

            if (imageKey != null) {
                String finalImageKey = imageKey;
                availableImageKeysWithPos.removeIf(pair -> pair.first.equals(finalImageKey));
            } else {
                // 2: לפי קרבה
                imageKey = findClosestImageKey(availableImageKeysWithPos, bounds);
                if (imageKey != null) {
                    String finalImageKey = imageKey;
                    availableImageKeysWithPos.removeIf(pair -> pair.first.equals(finalImageKey));
                }
            }

            // 3: FIFO מ־pending
            if (imageKey == null && !workingPendingKeys.isEmpty()) {
                imageKey = workingPendingKeys.remove(0);
            }

            if (imageKey == null) {
                btnNode.recycle();
                continue;
            }

            String id = imageKey;
            wantedIds.add(id);
            decryptAndShowImageForKey(imageKey, bounds, myPhone, peerPhone);
            btnNode.recycle();
        }

        // עדכון pending
        prefsLock.edit().putStringSet("pending_image_keys", new HashSet<>(workingPendingKeys)).apply();
    }

    private void saveImageOrder(List<String> imageKeys, String chatId) {
        SharedPreferences orderPrefs = getSharedPreferences("ImageOrder", MODE_PRIVATE);
        SharedPreferences.Editor editor = orderPrefs.edit();

        for (int i = 0; i < imageKeys.size(); i++) {
            editor.putInt(imageKeys.get(i) + "_order", i);
            editor.putString(imageKeys.get(i) + "_chat", chatId);
        }

        editor.apply();
        Log.d("LT_IMAGE_ORDER", "Saved order for " + imageKeys.size() + " images in chat: " + chatId);
    }

    private void cleanupOldBatchCache() {
        // ניקוי מטמון ישן מדי פעם
        SharedPreferences orderPrefs = getSharedPreferences("ImageOrder", MODE_PRIVATE);
        SharedPreferences.Editor editor = orderPrefs.edit();
        editor.clear();
        editor.apply();
        Log.d("LT_IMAGE_ORDER", "Cleaned up old batch cache");
    }

    private String findImageKeyAroundButton(AccessibilityNodeInfo btnNode) {
        if (btnNode == null) return null;
        final String IMAGE_KEY_REGEX = "^[a-fA-F0-9]{32,}(_\\d+)?$";
        try {
            // בדיקה של הכפתור עצמו
            CharSequence btnText = btnNode.getText();
            CharSequence btnDesc = btnNode.getContentDescription();

            if (btnText != null && btnText.length() >= 32 && btnText.length() <= 128
                    && btnText.toString().matches(IMAGE_KEY_REGEX)) {
                return btnText.toString().trim();
            }
            if (btnDesc != null && btnDesc.length() >= 32 && btnDesc.length() <= 128
                    && btnDesc.toString().matches(IMAGE_KEY_REGEX)) {
                return btnDesc.toString().trim();
            }

            // חיפוש בהורה ובאחים
            AccessibilityNodeInfo parent = null;
            try {
                parent = btnNode.getParent();
                if (parent != null) {
                    String result = searchImageKeyInNode(parent, 0, 3); // עומק 3
                    parent.recycle();
                    if (result != null) return result;
                }
            } catch (IllegalStateException e) {
                Log.e("LT_ACCESS", "Tried to access a recycled or not sealed node! (parent)", e);
            }
            return searchImageKeyInNode(btnNode, 0, 2); // עומק 2

        } catch (IllegalStateException e) {
            Log.e("LT_ACCESS", "Tried to access a recycled or not sealed node!", e);
            return null;
        }
    }

    private String searchImageKeyInNode(AccessibilityNodeInfo node, int currentDepth, int maxDepth) {
        final String IMAGE_KEY_REGEX = "^[a-fA-F0-9]{32,}(_\\d+)?$";
        if (node == null || currentDepth > maxDepth) return null;

        // בדיקת הצומת הנוכחי
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (text != null && text.length() >= 32 && text.length() <= 128
                && text.toString().matches(IMAGE_KEY_REGEX)) {
            return text.toString().trim();
        }

        if (desc != null && desc.length() >= 32 && desc.length() <= 128
                && desc.toString().matches(IMAGE_KEY_REGEX)) {
            return desc.toString().trim();
        }

        // חיפוש בילדים
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = searchImageKeyInNode(child, currentDepth + 1, maxDepth);
                child.recycle();
                if (result != null) return result;
            }
        }
        return null;
    }
    private String findClosestImageKey(List<Pair<String, Rect>> imageKeysWithPositions, Rect imageBounds) {
        String closestKey = null;
        double minDistance = Double.MAX_VALUE;

        for (Pair<String, Rect> keyPos : imageKeysWithPositions) {
            Rect keyBounds = keyPos.second;

            // חישוב מרחק בין המרכזים
            int imageCenterX = imageBounds.centerX();
            int imageCenterY = imageBounds.centerY();
            int keyCenterX = keyBounds.centerX();
            int keyCenterY = keyBounds.centerY();

            double distance = Math.sqrt(Math.pow(imageCenterX - keyCenterX, 2) + Math.pow(imageCenterY - keyCenterY, 2));

            // מעדיף מפתחות שנמצאים באותו אזור Y (אותה בועה)
            boolean sameYRegion = Math.abs(imageCenterY - keyCenterY) < 100;
            if (sameYRegion) {
                distance *= 0.1; // נתן עדיפות למפתחות באותו גובה
            }

            Log.d("LT_IMAGE_MATCHING", "Key " + keyPos.first.substring(0, 8) + "... distance to image: " + distance +
                    " (sameYRegion: " + sameYRegion + ")");

            if (distance < minDistance) {
                minDistance = distance;
                closestKey = keyPos.first;
            }
        }

        if (closestKey != null) {
            Log.d("LT_IMAGE_MATCHING", "Selected closest key: " + closestKey.substring(0, 8) + "... with distance: " + minDistance);
        }

        return closestKey;
    }
    private void decryptChatBubbles() {
        decryptChatBubbles(false);
    }
    private void decryptFullScreenImage(AccessibilityNodeInfo root) {
        Log.d("LT_IMAGE_DEBUG", "== decryptFullScreenImage called ==");

        com.example.locktalk_01.utils.FirebaseUserUtils.checkUserPhoneMatch(this, isMatch -> {
            if (!isMatch) {
                Log.e("LT_IMAGE_DEBUG", "User phone does not match!");
                mainHandler.post(() -> showToast("פיענוח לא אפשרי – היוזר לא תואם לחשבון!"));
                if (overlayManager != null) overlayManager.hide();
                stopContinuousDecryption();
                return;
            }

            Rect imageBounds = findFullscreenImageRect(root);
            if (imageBounds == null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                imageBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
            } else {
                imageBounds = new Rect(imageBounds);
            }
            String imageKey = null;
            List<Pair<String, Rect>> imgLabels = WhatsAppUtils.findImageLabels(root);
            if (imgLabels != null && !imgLabels.isEmpty()) {
                imageKey = imgLabels.get(imgLabels.size() - 1).first;
            }

            if (imageKey == null || imageKey.length() < 10) {
                SharedPreferences prefs = getApplicationContext().getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                Set<String> pendingKeys = new HashSet<>(prefs.getStringSet("pending_image_keys", new HashSet<>()));
                if (!pendingKeys.isEmpty()) {
                    imageKey = pendingKeys.iterator().next();
                    pendingKeys.remove(imageKey);
                    prefs.edit().putStringSet("pending_image_keys", pendingKeys).apply();
                }
            }

            if (imageKey == null || imageKey.length() < 10) {
                mainHandler.post(() -> showToast("לא נמצא מזהה תמונה (imageKey) בתצוגה!"));
                return;
            }

            // השגת פרטי שיחה
            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
            if (chatTitle == null || chatTitle.isEmpty()) {
                mainHandler.post(() -> showToast("לא נמצא שם שיחה נוכחית!"));
                return;
            }

            SharedPreferences creds = getApplicationContext().getSharedPreferences("UserCredentials", MODE_PRIVATE);
            String myPhone = creds.getString("myPhone", null);
            String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);

            if (peerPhone == null || peerPhone.length() < 7) {
                peerPhone = WhatsAppUtils.findPhoneInContacts(this, chatTitle);
                if (peerPhone != null) {
                    getApplicationContext().getSharedPreferences("PeerNames", MODE_PRIVATE)
                            .edit().putString(WhatsAppUtils.normalizeChatTitle(chatTitle), peerPhone).apply();
                    peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
                }
            }

            if (myPhone == null || peerPhone == null || peerPhone.length() < 7) {
                mainHandler.post(() -> showToast("לא נמצאו מספרים לפענוח תמונה"));
                return;
            }
            SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
            String entryJson = fakeMap.getString(imageKey, null);

            if (entryJson != null && entryJson.trim().startsWith("{")) {
                Log.d("LT_IMAGE_DEBUG", "Found JSON entry in fakeMap for imageKey: " + imageKey);
                decryptAndShowImageFromCipher(entryJson, myPhone, peerPhone, imageBounds, imageKey);
                return;
            }

            // בדיקת קובץ מקומי
            SharedPreferences prefsLock = getApplicationContext().getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            String origPath = prefsLock.getString("origPath_for_" + imageKey, null);
            if (origPath == null && imageKey.contains("_")) {
                String baseKey = imageKey.replaceAll("(_\\d+)$", "");
                origPath = prefsLock.getString("origPath_for_" + baseKey, null);
            }
            File file = origPath != null ? new File(origPath) : null;

            if (file != null && file.exists()) {
                // פיענוח מקומי
                try {
                    byte[] encBytes = com.example.locktalk_01.utils.FileUtils.readBytesFromFile(origPath);
                    byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, myPhone, peerPhone);
                    if (plainBytes != null) {
                        Bitmap origBmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                        if (origBmp != null) {
                            final Rect finalImageBounds = new Rect(imageBounds);
                            String finalImageKey = imageKey;
                            mainHandler.post(() -> {
                                if (!isReallyInWhatsApp()) {
                                    if (overlayManager != null) overlayManager.hide();
                                    stopContinuousDecryption();
                                    return;
                                }
                                overlayManager.showDecryptedImageOverlay(origBmp, finalImageBounds, finalImageKey);
                            });
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e("LT_IMAGE_DEBUG", "Exception during local decrypt", e);
                }
            }

            // שליפה מהענן
            String finalImageKey = imageKey;
            Rect finalImageBounds = new Rect(imageBounds);
            String finalPeerPhone = peerPhone;
            fetchImageCipherFromFirebase(myPhone, peerPhone, imageKey, jsonFromCloud -> {
                if (jsonFromCloud != null) {
                    // שמירה ב-fakeMap לשימוש עתידי
                    fakeMap.edit().putString(finalImageKey, jsonFromCloud).apply();
                    decryptAndShowImageFromCipher(jsonFromCloud, myPhone, finalPeerPhone, finalImageBounds, finalImageKey);
                } else {
                    mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - לא נמצא בענן"));
                }
            });
        });
    }
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
            if (n != root) n.recycle();
        }
        return largest;
    }

    private String findAndCachePhoneForChat(Context context, String chatTitle) {
        if (chatTitle == null) return null;
        String normalizedTitle = WhatsAppUtils.normalizeChatTitle(chatTitle);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            mainHandler.post(() -> showToast("יש לאשר הרשאת אנשי קשר לפיענוח"));
            return null;
        }

        String phone = context.getSharedPreferences("PeerNames", MODE_PRIVATE)
                .getString(normalizedTitle, null);
        if (phone != null) return WhatsAppUtils.normalizePhone(phone);

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
    public boolean isDecryptionTimerActive() {
        return decryptAuthenticated && System.currentTimeMillis() <= decryptExpiryTimestamp;
    }
    public boolean shouldHideDecryptionOverlays() {
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
        if (!currentPackage.startsWith("com.whatsapp")) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp (currentPackage=" + currentPackage + ")");
            return true;
        }
        if (isMediaViewerScreen(lc)) {
            Log.d("LT", "shouldHideDecryptionOverlays: ALLOW in media viewer (" + currentClass + ")");
            return false;
        }
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
        if (isKeyboardProbablyVisible(root)) {
            Log.d("LT", "shouldHideDecryptionOverlays: keyboardVisible");
            return true;
        }
        if (!isConversationScreen( root)) {
            Log.d("LT", "shouldHideDecryptionOverlays: not WhatsApp conversation screen (" + currentClass + ")");
            return true;
        }
        Log.d("LT", "shouldHideDecryptionOverlays: overlay allowed (" + currentClass + ")");
        return false;
    }
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
    private String extractCipherFromDoc(DocumentSnapshot doc) {
        if (doc != null && doc.exists()) {
            String encrypted   = doc.getString("encrypted");
            Boolean outgoing   = doc.getBoolean("outgoing");
            String senderPhone = doc.getString("senderPhone");

            if (encrypted != null && senderPhone != null) {
                FakeMapEntry entry = new FakeMapEntry();
                entry.encrypted   = encrypted;
                entry.outgoing    = (outgoing != null) ? outgoing : false; // לא קריטי לנו, בכל מקרה נסמוך על senderPhone
                entry.senderPhone = senderPhone;
                return new com.google.gson.Gson().toJson(entry);
            }
        }
        return null;
    }

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
    private boolean isOutgoingForMeFromEntry(FakeMapEntry entry, String myPhone) {
        if (entry == null || myPhone == null) return false;
        String me     = EncryptionHelper.normalizePhone(myPhone);
        String sender = EncryptionHelper.normalizePhone(entry.senderPhone);
        return me.equals(sender);
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
                boolean outgoing = true; // כי זה תמיד הודעה שנשלחת ממך!
                FakeMapEntry entry = new FakeMapEntry(fake, actualCipher, outgoing, myPhone);

                String entryJson = new com.google.gson.Gson().toJson(entry);

                // שמירה ב-SharedPreferences
                getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE)
                        .edit().putString(fake, entryJson).apply();

                // שמירה ב-Firebase
                String docIdSender = myPhone.replace("+972", "");
                String docIdReceiver = peerPhone.replace("+972", "");
                FirebaseFirestore db = FirebaseFirestore.getInstance();

                String fakeHash = EncryptionHelper.sha256(fake);

                db.collection("users").document(docIdSender).collection("fakeMap")
                        .document(fakeHash).set(entry);
                db.collection("users").document(docIdReceiver).collection("fakeMap")
                        .document(fakeHash).set(entry);

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
        public boolean outgoing;       // האם הודעה יוצאת
        public String senderPhone;     // מזהה השולח (מספר טלפון)

        public FakeMapEntry() {}
        public FakeMapEntry(String fake, String encrypted, boolean outgoing, String senderPhone) {
            this.fake = fake;
            this.encrypted = encrypted;
            this.outgoing = outgoing;
            this.senderPhone = senderPhone;
        }
    }
    private boolean isRightToLeftLayout() {
        try {
            return getResources().getConfiguration().getLayoutDirection() ==
                    android.view.View.LAYOUT_DIRECTION_RTL;
        } catch (Exception e) {
            // fallback - בדיקה לפי שפה
            String language = getResources().getConfiguration().locale.getLanguage();
            return "he".equals(language) || "ar".equals(language);
        }
    }

    private List<List<Pair<AccessibilityNodeInfo, Rect>>> groupButtonsIntoGrid(
            List<Pair<AccessibilityNodeInfo, Rect>> buttons) {

        if (buttons.isEmpty()) return new ArrayList<>();

        // מיון ראשוני לפי Y
        buttons.sort(Comparator.comparingInt(pair -> pair.second.top));

        List<List<Pair<AccessibilityNodeInfo, Rect>>> rows = new ArrayList<>();
        List<Pair<AccessibilityNodeInfo, Rect>> currentRow = new ArrayList<>();

        int lastY = buttons.get(0).second.centerY();
        final int ROW_THRESHOLD = 80; // מרחק מקסימלי בין שורות

        for (Pair<AccessibilityNodeInfo, Rect> button : buttons) {
            int currentY = button.second.centerY();

            // אם המרחק גדול מדי, התחל שורה חדשה
            if (Math.abs(currentY - lastY) > ROW_THRESHOLD && !currentRow.isEmpty()) {
                rows.add(new ArrayList<>(currentRow));
                currentRow.clear();
            }

            currentRow.add(button);
            lastY = currentY;
        }

        // הוסף את השורה האחרונה
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        // מיין כל שורה לפי כיוון קריאה
        boolean isRTL = isRightToLeftLayout();
        for (List<Pair<AccessibilityNodeInfo, Rect>> row : rows) {
            if (isRTL) {
                row.sort((a, b) -> Integer.compare(b.second.left, a.second.left)); // מימין לשמאל
            } else {
                row.sort((a, b) -> Integer.compare(a.second.left, b.second.left)); // משמאל לימין
            }
        }

        Log.d("LT_GRID_LAYOUT", "Grouped " + buttons.size() + " buttons into " +
                rows.size() + " rows (RTL: " + isRTL + ")");

        return rows;
    }

    private List<Pair<String, Rect>> mapBatchKeysToButtonsImproved(
            Map<Integer, String> numberedKeys,
            List<Pair<AccessibilityNodeInfo, Rect>> buttons) {

        List<Pair<String, Rect>> result = new ArrayList<>();

        if (numberedKeys.isEmpty() || buttons.isEmpty()) {
            Log.w("LT_BATCH_MAPPING", "Empty keys or buttons");
            return result;
        }

        // קבל רשימה מסודרת של המפתחות לפי המספור
        List<String> orderedKeys = new ArrayList<>();
        for (int i = 1; i <= numberedKeys.size(); i++) {
            if (numberedKeys.containsKey(i)) {
                orderedKeys.add(numberedKeys.get(i));
            }
        }

        // קבץ כפתורים לשורות
        List<List<Pair<AccessibilityNodeInfo, Rect>>> grid = groupButtonsIntoGrid(buttons);

        // מיפוי לפי סדר הקריאה הטבעי
        List<Pair<AccessibilityNodeInfo, Rect>> orderedButtons = new ArrayList<>();
        for (List<Pair<AccessibilityNodeInfo, Rect>> row : grid) {
            orderedButtons.addAll(row);
        }

        // יצירת המיפוי הסופי
        int count = Math.min(orderedKeys.size(), orderedButtons.size());
        for (int i = 0; i < count; i++) {
            String key = orderedKeys.get(i);
            Rect bounds = orderedButtons.get(i).second;
            result.add(new Pair<>(key, bounds));

            Log.d("LT_BATCH_MAPPING", "Mapped key " + (i + 1) + ": " +
                    key.substring(0, 8) + "... to bounds: " + bounds);
        }

        return result;
    }

    private void saveBatchMappingCache(String chatId, long messageTimestamp,
                                       Map<String, Rect> keyToPosition) {
        try {
            SharedPreferences prefs = getSharedPreferences("BatchMappingCache", MODE_PRIVATE);
            String cacheKey = chatId + "_" + messageTimestamp;

            Gson gson = new Gson();
            String json = gson.toJson(keyToPosition);
            prefs.edit().putString(cacheKey, json).apply();

            Log.d("LT_BATCH_CACHE", "Saved mapping cache for: " + cacheKey);
        } catch (Exception e) {
            Log.e("LT_BATCH_CACHE", "Failed to save mapping cache", e);
        }
    }

    private Map<String, Rect> loadBatchMappingCache(String chatId, long messageTimestamp) {
        try {
            SharedPreferences prefs = getSharedPreferences("BatchMappingCache", MODE_PRIVATE);
            String cacheKey = chatId + "_" + messageTimestamp;
            String json = prefs.getString(cacheKey, null);

            if (json != null) {
                Gson gson = new Gson();
                Type type = new com.google.gson.reflect.TypeToken<Map<String, Rect>>(){}.getType();
                Map<String, Rect> cached = gson.fromJson(json, type);
                Log.d("LT_BATCH_CACHE", "Loaded mapping cache for: " + cacheKey);
                return cached;
            }
        } catch (Exception e) {
            Log.e("LT_BATCH_CACHE", "Failed to load mapping cache", e);
        }
        return new HashMap<>();
    }

    private long estimateMessageTimestamp(List<Pair<AccessibilityNodeInfo, Rect>> buttons) {
        if (buttons.isEmpty()) return System.currentTimeMillis();

        // השתמש במיקום Y כבסיס לזיהוי הודעה
        int avgY = buttons.stream()
                .mapToInt(pair -> pair.second.centerY())
                .sum() / buttons.size();

        // יצור timestamp יחסי לפי מיקום Y
        return System.currentTimeMillis() - (avgY * 1000L);
    }

    private List<Pair<String, Rect>> mapBatchKeysToButtons(List<String> batchKeys,
                                                           List<Pair<AccessibilityNodeInfo, Rect>> buttons) {

        Log.d("LT_BATCH_MAPPING", "Starting improved batch mapping: " +
                batchKeys.size() + " keys, " + buttons.size() + " buttons");

        if (batchKeys.isEmpty() || buttons.isEmpty()) {
            return new ArrayList<>();
        }

        // נסה למצוא תיאור עם מספור
        String batchDescription = null;
        for (Pair<AccessibilityNodeInfo, Rect> btnPair : buttons) {
            AccessibilityNodeInfo n = btnPair.first;
            if (n.getContentDescription() != null && n.getContentDescription().length() > 40) {
                batchDescription = n.getContentDescription().toString();
                break;
            }
            if (n.getText() != null && n.getText().length() > 40) {
                batchDescription = n.getText().toString();
                break;
            }
        }

        // חלץ מפתחות עם מספור
        Map<Integer, String> numberedKeys;
        if (batchDescription != null) {
            numberedKeys = extractNumberedImageKeys(batchDescription);
        } else {
            // fallback - צור מספור מהרשימה הקיימת
            numberedKeys = new TreeMap<>();
            for (int i = 0; i < batchKeys.size(); i++) {
                numberedKeys.put(i + 1, batchKeys.get(i));
            }
        }

        // בדוק אם יש מיפוי שמור במטמון
        String chatTitle = "current_chat"; // החלף עם הערך האמיתי
        long messageTime = estimateMessageTimestamp(buttons);
        Map<String, Rect> cachedMapping = loadBatchMappingCache(chatTitle, messageTime);

        if (!cachedMapping.isEmpty()) {
            Log.d("LT_BATCH_MAPPING", "Using cached mapping");
            List<Pair<String, Rect>> result = new ArrayList<>();
            for (Map.Entry<String, Rect> entry : cachedMapping.entrySet()) {
                result.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
            return result;
        }

        // יצור מיפוי חדש
        List<Pair<String, Rect>> result = mapBatchKeysToButtonsImproved(numberedKeys, buttons);

        // שמור במטמון
        Map<String, Rect> mappingToCache = new HashMap<>();
        for (Pair<String, Rect> pair : result) {
            mappingToCache.put(pair.first, pair.second);
        }
        saveBatchMappingCache(chatTitle, messageTime, mappingToCache);

        Log.d("LT_BATCH_MAPPING", "Created new mapping with " + result.size() + " pairs");
        return result;
    }
    public boolean isReallyInWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        boolean res = WhatsAppUtils.isWhatsAppPackage(pkg);
        if (root != null) root.recycle();
        return res;
    }
    private final Handler overlayRefresh = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = () -> {
        try {
            decryptChatBubbles(true);
        } catch (Throwable t) {
            Log.w("LT_REFRESH", "scanRunnable error", t);
        }
    };
    private static final int SCAN_DEBOUNCE_MS = 20;
    private void requestDebouncedScan() {
        overlayRefresh.removeCallbacks(scanRunnable);
        overlayRefresh.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS);
    }

    private void showDecryptAuthDialog() {
        if (overlayManager.isShown()) overlayManager.hide();
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        if (decryptAuthenticated && System.currentTimeMillis() <= decryptExpiryTimestamp) {
            showDecryptDurationDialog();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String userPhone = prefs.getString("myPhone", null);

        String failCountKey = "decrypt_fail_count_" + userPhone;
        String failLockKey = "decrypt_fail_lock_time_" + userPhone;

        int failCount = prefs.getInt(failCountKey, 0);
        long failLockTimestamp = prefs.getLong(failLockKey, 0);
        long now = System.currentTimeMillis();

        // חסימה אקטיבית?
        if (failCount >= 3 && now - failLockTimestamp < 60 * 60 * 1000) {
            long left = ((failLockTimestamp + 60 * 60 * 1000) - now) / 1000 / 60;
            showToast("נחסמת לשעה בשל נסיונות מרובים. נסה שוב בעוד " + left + " דקות");
            return;
        } else if (failCount >= 3 && now - failLockTimestamp >= 60 * 60 * 1000) {
            prefs.edit().putInt(failCountKey, 0).apply();
            prefs.edit().putLong(failLockKey, 0).apply();
            failCount = 0;
            failLockTimestamp = 0;
        }

        // שימוש ב-layout שלך
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.personal_code_dialog, null);
        EditText input = dialogView.findViewById(R.id.personalCodeDialogInput);
        TextView errorText = dialogView.findViewById(R.id.decryptedMessageText);
        Button confirmBtn = dialogView.findViewById(R.id.personalCodeConfirmButton);
        Button cancelBtn = dialogView.findViewById(R.id.personalCodeCancelButton);

        // אפס את הודעת השגיאה בדיפולט
        errorText.setVisibility(View.GONE);
        errorText.setBackgroundResource(0);
        errorText.setText("");
        input.setEnabled(true);
        confirmBtn.setEnabled(true);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("קוד פיענוח"))
                .setView(dialogView)
                .setCancelable(false)
                .create();

        confirmBtn.setOnClickListener(v -> {
            errorText.setVisibility(View.GONE); // הסתרת הודעות קודמות
            errorText.setText("");
            errorText.setBackgroundResource(0);

            String enteredCode = input.getText().toString().trim();

            String realCode = prefs.getString("personalCode_" + userPhone, null);
            if (realCode == null) realCode = prefs.getString("personalCode", null);

            boolean valid = enteredCode.equals(realCode);
            boolean isWhatsAppPhoneValid = isWhatsAppAccountPhoneMatches(userPhone);

            if (valid && isWhatsAppPhoneValid) {
                decryptAuthenticated = true;
                overlayHiddenByUser = false;
                // הצגת הצלחה ברקע ירוק
                errorText.setText("הזדהית בהצלחה!");
                errorText.setTextColor(Color.parseColor("#2E7D32")); // ירוק כהה
                errorText.setBackgroundColor(Color.parseColor("#E8F5E9")); // רקע ירוק בהיר (כמו ב־XML)
                errorText.setVisibility(View.VISIBLE);
                prefs.edit().putInt(failCountKey, 0).apply();
                prefs.edit().putLong(failLockKey, 0).apply();
                input.setEnabled(false);
                confirmBtn.setEnabled(false);
                // סוגר אחרי 1 שניה (UX)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    dlg.dismiss();
                    showDecryptDurationDialog();
                }, 1000);
            } else if (!valid) {
                int newFailCount = prefs.getInt(failCountKey, 0) + 1;
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(failCountKey, newFailCount);

                int triesLeft = 3 - newFailCount;
                errorText.setVisibility(View.VISIBLE);
                errorText.setTextColor(Color.RED);
                errorText.setBackgroundColor(Color.TRANSPARENT);

                if (newFailCount >= 3) {
                    editor.putLong(failLockKey, System.currentTimeMillis());
                    errorText.setText("הוזן קוד שגוי 3 פעמים.\nהגישה נחסמה לשעה.");
                    input.setEnabled(false);
                    confirmBtn.setEnabled(false);
                } else {
                    String msg = "קוד אישי שגוי!";
                    if (triesLeft == 1) {
                        msg += " נותר ניסיון אחרון לפני חסימה.";
                    } else {
                        msg += " נותרו " + triesLeft + " ניסיונות.";
                    }
                    errorText.setText(msg);
                }
                editor.apply();
            } else if (!isWhatsAppPhoneValid) {
                errorText.setText("חשבון הוואטסאפ במכשיר לא תואם את המשתמש שמחובר לאפליקציה");
                errorText.setTextColor(Color.RED);
                errorText.setBackgroundColor(Color.TRANSPARENT);
                errorText.setVisibility(View.VISIBLE);
            }
        });

        cancelBtn.setOnClickListener(v -> dlg.dismiss());

        configureDialogWindow(dlg);
        dlg.show();
        activeDialog = dlg;
    }

    private boolean isWhatsAppAccountPhoneMatches(String userPhone) {
        Log.d(TAG, "isWhatsAppAccountPhoneMatches: always returning true (no access to WhatsApp profile)");
        return true;
    }

    public boolean isConversationScreen(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // חפש שדה EditText של שליחת הודעה (id קבוע בווטסאפ)
        List<AccessibilityNodeInfo> entryFields = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (entryFields != null && !entryFields.isEmpty()) {
            for (AccessibilityNodeInfo node : entryFields) {
                if (node != null && "android.widget.EditText".equals(node.getClassName())) {
                    // בדיקה אופציונלית: לוודא שהשדה זמין לשליחה (לא disable)
                    if (node.isVisibleToUser() && node.isEnabled()) {
                        return true;
                    }
                }
            }
        }

        // לא נמצא שדה הודעה – ככל הנראה לא מסך שיחה רגילה
        return false;
    }

    private void showMainOverlay() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);

            overlayManager.show(
                    v -> {
                        Intent pick = new Intent(this, ImagePickerProxyActivity.class);
                        pick.putExtra("chat_title", chatTitle); // הוסף את שם הצ'אט
                        pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(pick);
                    },
                    v -> {
                        // כפתור X - עצור הכל וחזור לאוברליי רגיל
                        stopContinuousDecryption();
                        overlayManager.stopTimer();
                        resetToDefaultOverlay();
                    },
                    v -> showDecryptAuthDialog()
            );
            overlayManager.updateToTopCenter();
            root.recycle();
        } else {
            // fallback אם אין root
            overlayManager.show(
                    v -> {
                        Intent pick = new Intent(this, ImagePickerProxyActivity.class);
                        pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(pick);
                    },
                    v -> {
                        stopContinuousDecryption();
                        overlayManager.stopTimer();
                        resetToDefaultOverlay();
                    },
                    v -> showDecryptAuthDialog()
            );
            overlayManager.updateToTopCenter();
        }
    }

    private void resetToDefaultOverlay() {
        Log.d("LT_RESET", "מאפס לאוברליי ברירת מחדל");

        // עצור את כל הפענוח
        decryptAlwaysOn = false;
        decryptAuthenticated = false;
        overlayHiddenByUser = false;

        // נקה handlers
        if (decryptHandler != null) {
            decryptHandler.removeCallbacks(decryptRunnable);
        }
        if (overlayRefresh != null) {
            overlayRefresh.removeCallbacksAndMessages(null);
        }

        // נקה אוברליי פענוח
        if (overlayManager != null) {
            overlayManager.clearDecryptOverlays();
            overlayManager.cleanupAllBubbles();
            overlayManager.cleanupImageOverlays();
            overlayManager.stopTimer();
        }

        // בדוק אם צריך להציג אוברליי רגיל
        mainHandler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";

                if (WhatsAppUtils.isWhatsAppPackage(pkg) && isConversationScreen(root)) {
                    String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
                    String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);

                    // רק אם יש איש קשר תקין
                    if (peerPhone != null && peerPhone.length() > 7) {
                        if (!overlayManager.isShown()) {
                            showMainOverlay();
                        }
                    } else {
                        // אין איש קשר - הסתר אוברליי
                        overlayManager.hide();
                    }
                } else {
                    // לא בשיחה - הסתר אוברליי
                    overlayManager.hide();
                }
                root.recycle();
            } else {
                overlayManager.hide();
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

                    // הגדרת פרמטרים בסיסיים
                    overlayHiddenByUser = false;
                    decryptAuthenticated = true;

                    if (mins[sel[0]] == -1) {
                        // פיענוח קבוע
                        decryptExpiryTimestamp = Long.MAX_VALUE;
                        showToast("פיענוח פעיל: עד עצירה ידנית");

                        Log.d("LT_TIMER", "מפעיל פיענוח קבוע");

                        showMainOverlay();
                        startContinuousDecryption();
                    } else {
                        // פיענוח מוגבל בזמן
                        decryptExpiryTimestamp = System.currentTimeMillis() + mins[sel[0]] * 60_000L;
                        showToast("פיענוח פעיל: " + items[sel[0]]);

                        Log.d("LT_TIMER", "מפעיל פיענוח ל-" + mins[sel[0]] + " דקות");
                        showMainOverlay();
                        startContinuousDecryption(); // זה במקום tryDecryptWithRetry()

                        // הפעל טיימר
                        overlayManager.startTimer(
                                decryptExpiryTimestamp,
                                () -> {
                                    Log.d("LT_TIMER", "טיימר פג - עוצר פיענוח");
                                    stopContinuousDecryption();
                                    resetToDefaultOverlay();
                                    showToast("פיענוח הסתיים");
                                }
                        );
                    }

                    // הפעלה מיידית
                    startContinuousDecryption();

                    // פענוח ראשון מיד
                    mainHandler.postDelayed(() -> {
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        if (root != null) {
                            if (isReallyInWhatsApp() && isConversationScreen(root)) {
                                Log.d("LT_DECRYPT", "מפעיל פיענוח ראשון");
                                decryptChatBubbles();
                            }
                            root.recycle();
                        }
                    }, 100); // דיליי קטן כדי לוודא שהכל מוכן

                    d.dismiss();
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
}