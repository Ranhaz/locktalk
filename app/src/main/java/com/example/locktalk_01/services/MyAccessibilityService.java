package com.example.locktalk_01.services;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PREF_FAKE_MAP = "FakeCipherMap";
    private AndroidKeystorePlugin keystorePlugin;
    public OverlayManager overlayManager;
    private Handler mainHandler;
    private AlertDialog activeDialog;
    private boolean decryptAuthenticated = false;
    private long decryptExpiryTimestamp;
    private static final int AUTO_SHARE_RETRY_DELAY_MS = 80;
    private long lastDecryptTs = 0;
    private static final long DECRYPT_THROTTLE_MS = 150;
    private boolean isReplacing = false;
    private boolean overlayHiddenByUser = false;
    private String lastWhatsAppPackage;
    private static MyAccessibilityService instance;
    private ExecutorService executor;
    private String pendingImageUri = null;
    private Handler tryDecryptHandler = new Handler(Looper.getMainLooper());
    private int tryDecryptAttempts = 0;
    private static volatile boolean imagePickerActive = false;

    private Handler overlayHidePollHandler = new Handler(Looper.getMainLooper());
    private Runnable overlayHidePollRunnable;

    private boolean decryptAlwaysOn = false;
    private Handler decryptHandler = new Handler(Looper.getMainLooper());
    private Runnable decryptRunnable;
    private boolean isRetryingDecrypt = false;
    private boolean pendingImageAutoSend = false;

    private volatile boolean decryptRunning = false;
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
    private boolean isUserLoggedIn() {
        SharedPreferences prefs = getSharedPreferences("UserCredentials", MODE_PRIVATE);
        return prefs.getBoolean("isLoggedIn", false);
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
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
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
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getPackageName() == null) return;
            String pkg = event.getPackageName().toString();
            String cls = event.getClassName() != null ? event.getClassName().toString() : "";
            int type = event.getEventType();
            AccessibilityNodeInfo root = getRootInActiveWindow();

            Log.d("LT_NODE", root == null ? "root==null – לא הצלחנו לגשת לעץ" : "יש root – ממשיכים להדפיס עץ");
            if (root != null) printAllNodes(root, 0);

            Log.d("LT_DEBUG", "onEvent: pkg=" + pkg + ", cls=" + cls + ", type=" + type + ", isChooser=" + isShareChooserScreen(cls, root));

            try {
                // Debug logging for WhatsApp events
                if (WhatsAppUtils.isWhatsAppPackage(pkg)) {
                    SharedPreferences debugPrefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                    String lastChat = debugPrefs.getString("lastGalleryChat", "NOT_FOUND");
                    long lastTime = debugPrefs.getLong("lastGalleryChatTime", 0);
                    Log.d("LT_DEBUG", "WhatsApp event - pkg: " + pkg + " cls: " + cls + " type: " + type);
                    Log.d("LT_DEBUG", "Last chat: '" + lastChat + "' time: " + (System.currentTimeMillis() - lastTime) + "ms ago");
                    Log.d("LT_DEBUG", "isShareChooserScreen: " + isShareChooserScreen(cls, root));
                }

                if (!isUserLoggedIn()) {
                    if (overlayManager != null && overlayManager.isShown()) {
                        overlayManager.hide();
                        stopContinuousDecryption();
                    }
                    return;
                }

                // שמירת שם צ'אט כשנכנסים לשיחה רגילה (לא chooser)
                if (WhatsAppUtils.isWhatsAppPackage(pkg) && isConversationScreen( root)) {
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

// אוטומציה תופעל אם זוהה chooser ע"פ אחד מהקריטריונים
                if ((isSystemChooser && isChooserLike) || isChooserByTree) {
                    Log.d("LT_SHARE", "🎯 נכנסנו למסך בחירת שיחה (chooser) לפי עץ או מזהה class!");
                    Log.d("LT_SHARE", "📱 Package: " + pkg + " Class: " + cls);
                    printAllNodes(root, 0);

                    SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
                    String chatTitle = prefs.getString("lastGalleryChat", null);
                    long chatTime = prefs.getLong("lastGalleryChatTime", 0);
                    long timeDiff = System.currentTimeMillis() - chatTime;
                    boolean isRecentChat = timeDiff < 60000;

                    Log.d("LT_SHARE", "💾 Saved chatTitle: '" + chatTitle + "'");
                    Log.d("LT_SHARE", "⏰ trySelectChatAutomaticallyTime difference: " + timeDiff + "ms (recent=" + isRecentChat + ")");

                    if (chatTitle != null && isRecentChat && root != null) {
                        Log.d("LT_SHARE", "✅ All conditions met - starting auto-select");
                        autoSelectDirectShareTarget(root, chatTitle);
                    } else {
                        Log.d("LT_SHARE", "❌ Conditions failed:");
                        Log.d("LT_SHARE", "   - chatTitle null: " + (chatTitle == null));
                        Log.d("LT_SHARE", "   - not recent: " + (!isRecentChat));
                        Log.d("LT_SHARE", "   - root null: " + (root == null));
                    }
                    return;
                }
// דומים ל-chooser אבל לא chooser אמיתי (למשל מסך פרופיל), לא להפעיל אוטומציה!
                else if (isChooserLike) {
                    Log.d("LT_SHARE", "IGNORE: found chooser-like screen inside WhatsApp package (probably profile or similar)");
                    Log.d("LT_SHARE", "IGNORE_DETAILS: pkg=" + pkg + " cls=" + cls);
                    return;
                }

                // Handle non-WhatsApp packages
                if (!WhatsAppUtils.isWhatsAppPackage(pkg)) {
                    if (overlayManager != null && overlayManager.isShown()) {
                        overlayManager.hide();
                        stopContinuousDecryption();
                    }
                    stopContinuousDecryption();
                    pollOverlayHideAfterExit();
                    return;
                }

                // Update last WhatsApp package
                lastWhatsAppPackage = pkg;

                // Check if current screen is forbidden
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

                // Handle auto-encryption for text
                if (!isReplacing
                        && WhatsAppUtils.isWhatsAppPackage(pkg)
                        && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    handleAutoEncrypt();
                }

                // Handle decryption of chat bubbles
                if (decryptOn
                        && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                        && now - lastDecryptTs > DECRYPT_THROTTLE_MS) {
                    lastDecryptTs = now;
                    boolean isScrollEvent = (type == AccessibilityEvent.TYPE_VIEW_SCROLLED);
                    decryptChatBubbles(isScrollEvent);
                }

                // Handle fullscreen image decryption
                if (decryptOn && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isFullScreenImageView(cls)) {
                    decryptFullScreenImage(root);
                    if (overlayManager.isShown()) overlayManager.hide();
                    return;
                }

                // Handle overlay show/hide based on screen state
                if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    boolean inWA = WhatsAppUtils.isWhatsAppPackage(pkg);
                    boolean isConversationScreen = inWA && isConversationScreen( root);
                    boolean isFullScreenImage = isFullScreenImageView(cls);
                    boolean shouldShow = isConversationScreen && !overlayHiddenByUser;

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
                            overlayManager.show(
                                    v -> {
                                        String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
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

                // Handle auto-send after image selection
                if ("com.whatsapp".equals(pkg)
                        && "com.whatsapp.Conversation".equals(cls)
                        && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                    if (pendingImageAutoSend && root != null) {
                        boolean sendButtonClicked = attemptAutoSend(root);
                        if (sendButtonClicked) {
                            Log.d("LT_SHARE", "Auto-clicked SEND after image selection");
                            pendingImageAutoSend = false;
                        }
                    }
                }

            } finally {
                // Always recycle root at the end
                if (root != null) root.recycle();
            }

        } catch (Exception e) {
            Log.e("MyAccessibilityService", "onAccessibilityEvent exception", e);
        }
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

    private void autoSelectDirectShareTarget(AccessibilityNodeInfo root, String chatTitle) {
        if (root == null || chatTitle == null) return;
        String normChat = normalizeChatTitle(chatTitle);

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findDirectShareTargets(root, normChat, candidates);

        for (AccessibilityNodeInfo node : candidates) {
            Log.d("LT_SHARE", "👆 FOUND and CLICKING direct share item for chatTitle=" + chatTitle);
            boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d("LT_SHARE", "ACTION_CLICK result: " + clicked);
            if (clicked) return; // יציאה אחרי קליק ראשון מוצלח
        }
        Log.d("LT_SHARE", "❌ No direct share target found for: " + chatTitle + ", candidates count=" + candidates.size());
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

        // אפשרות: כל pkg שמכיל intentresolver, chooser, resolver, או android (ולא whatsapp או facebook)
        boolean isSystem = (
                (lcPkg.contains("intentresolver") || lcPkg.contains("chooser") ||
                        lcPkg.contains("resolver") || lcPkg.equals("android"))
                        &&
                        (lcCls.contains("chooser") || lcCls.contains("resolver"))
        );
        // שלול באופן מפורש מסכי whatsapp או facebook עצמם
        if (lcPkg.contains("whatsapp") || lcPkg.contains("facebook"))
            return false;

        return isSystem;
    }

    private void findDirectShareTargets(AccessibilityNodeInfo node, String normChatTitle, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        // בדוק אם זה LinearLayout נכון, עם desc או ילד TextView מתאים
        if ("android.widget.LinearLayout".equals(node.getClassName())
                && node.isClickable()
                && "com.android.intentresolver:id/sem_chooser_grid_item_view".equals(node.getViewIdResourceName())) {

            // בדוק ב-desc
            CharSequence desc = node.getContentDescription();
            boolean found = false;
            if (desc != null && normalizeChatTitle(desc.toString()).contains(normChatTitle)) {
                found = true;
            }

            // בדוק ב-TextView
            if (!found) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null && "android.widget.TextView".equals(child.getClassName())) {
                        CharSequence text = child.getText();
                        if (text != null && normalizeChatTitle(text.toString()).contains(normChatTitle)) {
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (found) {
                result.add(node);
            }
        }

        // רקורסיה לכל הילדים
        for (int i = 0; i < node.getChildCount(); i++) {
            findDirectShareTargets(node.getChild(i), normChatTitle, result);
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
    private boolean isShareChooserScreen(String className, AccessibilityNodeInfo root) {
        if (className == null) return false;
        String lc = className.toLowerCase();

        // חובה: שם class חייב להיות chooser/resolver (לא סתם כל "picker" או "forward")
        if (lc.contains("chooser") || lc.contains("resolveractivity") || lc.contains("chooseractivity")) {
            // נוסיף תנאי של מבנה: יש ListView עם >2 שמות שהם לא טקסט הודעה אלא איש קשר/קבוצה
            if (root != null) {
                int contactNameCount = 0;
                for (int i = 0; i < root.getChildCount(); i++) {
                    AccessibilityNodeInfo child = root.getChild(i);
                    if (child != null && child.getClassName() != null &&
                            child.getClassName().toString().toLowerCase().contains("listview")) {

                        // לכל שורה ברשימה
                        for (int j = 0; j < child.getChildCount(); j++) {
                            AccessibilityNodeInfo row = child.getChild(j);
                            if (row != null) {
                                for (int k = 0; k < row.getChildCount(); k++) {
                                    AccessibilityNodeInfo nameNode = row.getChild(k);
                                    if (nameNode != null && nameNode.getText() != null) {
                                        String txt = nameNode.getText().toString();
                                        // שם קצר יחסית ולא ריק (נניח < 30 תווים, כדי לא לכלול הודעות)
                                        if (txt.length() > 0 && txt.length() < 30) contactNameCount++;
                                    }
                                }
                            }
                        }
                    }
                }
                if (contactNameCount > 2) {
                    Log.d("LT_DEBUG", "isShareChooserScreen: Detected system chooser by tree! contactNameCount=" + contactNameCount);
                    return true;
                }
            }
        }
        return false;
    }
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

    private boolean attemptAutoSend(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> sendButtons = new ArrayList<>();
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
        if (byId != null) sendButtons.addAll(byId);
        String[] texts = {"שלח", "Send"};
        for (String txt : texts) {
            List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(txt);
            if (byText != null) sendButtons.addAll(byText);
        }
        List<AccessibilityNodeInfo> allNodes = getAllNodesWithTextOrDesc(root);
        for (AccessibilityNodeInfo node : allNodes) {
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
            if (desc.contains("send") || desc.contains("שלח")) {
                sendButtons.add(node);
            }
        }
        for (AccessibilityNodeInfo node : allNodes) {
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            if ((className.contains("Button") || className.contains("ImageButton")) && node.isClickable() && node.isEnabled()) {
                sendButtons.add(node);
            }
        }
        for (AccessibilityNodeInfo btn : sendButtons) {
            if (btn != null && btn.isClickable() && btn.isEnabled()) {
                boolean success = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (success) {
                    Log.d("LT_SHARE", "Send button clicked successfully (attemptAutoSend)");
                    for (AccessibilityNodeInfo n : sendButtons) if (n != null) n.recycle();
                    for (AccessibilityNodeInfo n : allNodes) if (n != null) n.recycle();
                    return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : sendButtons) if (n != null) n.recycle();
        for (AccessibilityNodeInfo n : allNodes) if (n != null) n.recycle();
        Log.d("LT_SHARE", "No send button found or clickable");
        return false;
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
            // פרסינג של JSON entry
            FakeMapEntry entry = new com.google.gson.Gson().fromJson(jsonEntry, FakeMapEntry.class);

            // קביעת שולח ומקבל כמו בטקסט
            String senderPhone = entry.senderPhone;
            String receiverPhone = senderPhone.equals(myPhone) ? peerPhone : myPhone;

            Log.d("LT_IMAGE_DEBUG", "senderPhone=" + senderPhone + ", receiverPhone=" + receiverPhone);

            // פענוח עם הטלפונים הנכונים
            byte[] encBytes = android.util.Base64.decode(entry.encrypted, android.util.Base64.NO_WRAP);
            byte[] plainBytes = EncryptionHelper.decryptImage(encBytes, senderPhone, receiverPhone);

            Log.d("LT_IMAGE_DEBUG", "Decrypted, plainBytes.length=" + (plainBytes != null ? plainBytes.length : 0));

            if (plainBytes != null) {
                Bitmap origBmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
                Log.d("LT_IMAGE_DEBUG", "Bitmap decoded? " + (origBmp != null));

                if (origBmp != null) {
                    final Rect safeBounds = new Rect(imageBounds);
                    mainHandler.post(() -> {
                        if (!isReallyInWhatsApp()) {
                            Log.w("LT_IMAGE_DEBUG", "Not in WhatsApp, hiding overlay");
                            if (overlayManager != null) overlayManager.hide();
                            stopContinuousDecryption();
                            return;
                        }
                        // שיפור יציבות הפענוח - בדיקה שאין overlay קיים עדיין
                        if (!overlayManager.hasImageOverlay(imageId)) {
                            overlayManager.showDecryptedImageOverlay(origBmp, safeBounds, imageId);
                            Log.d("LT_IMAGE_DEBUG", "Overlay shown for imageId: " + imageId);
                        } else {
                            Log.d("LT_IMAGE_DEBUG", "Overlay already exists for imageId: " + imageId);
                        }
                    });
                } else {
                    Log.e("LT_IMAGE_DEBUG", "Failed to decode bitmap!");
                    mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - קובץ לא תקין"));
                }
            } else {
                Log.e("LT_IMAGE_DEBUG", "Failed to decrypt image - plainBytes is null!");
                mainHandler.post(() -> showToast("שגיאה בפענוח תמונה - מפתח לא תקין"));
            }
        } catch (Exception e) {
            Log.e("LT_IMAGE_DEBUG", "decryptAndShowImageFromCipher error", e);
            mainHandler.post(() -> showToast("שגיאה בפענוח תמונה (JSON)"));
        }
    }private List<Pair<String, Rect>> findImageKeysWithPositions(AccessibilityNodeInfo root) {
        List<Pair<String, Rect>> keysWithPositions = new ArrayList<>();
        if (root == null) return keysWithPositions;

        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.add(root);

        final String IMAGE_KEY_REGEX = "([a-fA-F0-9]{32,}(_\\d+)?|\\d+:[a-fA-F0-9]{32,}(_\\d+)?)";
        Pattern pattern = Pattern.compile(IMAGE_KEY_REGEX);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();

            for (CharSequence c : new CharSequence[]{text, desc}) {
                if (c != null && c.length() >= 32 && c.length() <= 300) {
                    Matcher matcher = pattern.matcher(c);
                    while (matcher.find()) {
                        String rawKey = matcher.group();
                        String normalized = normalizeImageKey(rawKey);
                        if (normalized != null && normalized.length() >= 32) {
                            Rect bounds = new Rect();
                            node.getBoundsInScreen(bounds);
                            keysWithPositions.add(new Pair<>(normalized, bounds));
                            Log.d("LT_IMAGE_KEYS", "Found imageKey: " + normalized + " at " + bounds);
                        }
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }

            if (node != root) node.recycle();
        }
        Log.d("LT_IMAGE_KEYS", "Total detected imageKeys=" + keysWithPositions.size());
        return keysWithPositions;
    }

    private String normalizeImageKey(String raw) {
        if (raw == null) return null;
        int idx = raw.indexOf(':');
        if (idx != -1 && idx < raw.length()-1) {
            return raw.substring(idx+1).trim();
        }
        return raw.trim();
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

            if (decryptRunning) {
                Log.d("LT_DECRYPT", "decryptChatBubbles: Already running, skipping");
                return;
            }

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
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                return;
            }

            lastDecryptTs = System.currentTimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String className = (root != null && root.getClassName() != null) ? root.getClassName().toString() : "";

            if (isScrollEvent) {
                mainHandler.post(() -> overlayManager.cleanupAllBubbles());
            }

            if (shouldHideDecryptionOverlays() || !isConversationScreen( root)) {
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                if (root != null) root.recycle();
                return;
            }

            decryptRunning = true;

            String chatTitle = WhatsAppUtils.getCurrentChatTitle(root);
            String peerPhone = WhatsAppUtils.getPhoneByPeerName(this, chatTitle);

            SharedPreferences peerNames = getApplicationContext().getSharedPreferences("PeerNames", MODE_PRIVATE);
            String cachedPeerPhone = peerNames.getString(chatTitle != null ? chatTitle.trim() : "", null);
            if (peerPhone == null && cachedPeerPhone != null) {
                peerPhone = cachedPeerPhone;
            }

            if ((peerPhone == null || peerPhone.length() < 7) && chatTitle != null && chatTitle.length() > 0) {
                peerPhone = findAndCachePhoneForChat(this, chatTitle);
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
                    if (altPhone != null && !altPhone.equals(myPhone)) {
                        peerPhone = altPhone;
                    }
                }
            }

            if (myPhone == null || peerPhone == null) {
                mainHandler.post(() -> {
                    overlayManager.cleanupAllBubbles();
                    decryptRunning = false;
                });
                if (root != null) root.recycle();
                return;
            }

            // טקסט
            List<Pair<AccessibilityNodeInfo, Rect>> txtList = new ArrayList<>();
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
                        txtList.add(new Pair<>(AccessibilityNodeInfo.obtain(n), b));
                    }
                    n.recycle();
                }
            }

            // תמונות
            List<Pair<AccessibilityNodeInfo, Rect>> imageButtons = WhatsAppUtils.findImageBubbleButtons(root);
            List<Pair<AccessibilityNodeInfo, Rect>> safeImageButtons = new ArrayList<>();
            for (Pair<AccessibilityNodeInfo, Rect> pair : imageButtons) {
                safeImageButtons.add(new Pair<>(AccessibilityNodeInfo.obtain(pair.first), pair.second));
                pair.first.recycle();
            }

            SharedPreferences prefsLock = getApplicationContext().getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
            Set<String> pendingImageKeySet = prefsLock.getStringSet("pending_image_keys", new HashSet<>());
            List<String> pendingImageKeys = new ArrayList<>(pendingImageKeySet);

            List<Pair<String, Rect>> imageKeysWithPositions = findImageKeysWithPositions(root);
            Log.d("LT_IMAGE_BUBBLE", "Found " + imageKeysWithPositions.size() + " imageKeys with positions: " + imageKeysWithPositions);

            // תיקון: חיפוש batch description מתוקן
            String batchDescription = findBatchDescription(safeImageButtons, imageKeysWithPositions);
            List<String> batchImageKeys = extractImageKeysFromBatchDescription(batchDescription);

            if (root != null) root.recycle();

            final List<Pair<AccessibilityNodeInfo, Rect>> finalTxtList = txtList;
            final List<Pair<AccessibilityNodeInfo, Rect>> finalImageButtons = safeImageButtons;
            final String peerPhoneFinal = peerPhone;
            final String myPhoneFinal = myPhone;
            final List<String> pendingImageKeysFinal = new ArrayList<>(pendingImageKeys);
            final List<Pair<String, Rect>> finalImageKeysWithPositions = new ArrayList<>(imageKeysWithPositions);
            final List<String> batchImageKeysFinal = new ArrayList<>(batchImageKeys);
            final String chatTitleFinal = chatTitle;

            executor.execute(() -> {
                if (!isReallyInWhatsApp()) {
                    mainHandler.post(() -> {
                        if (overlayManager != null) overlayManager.hide();
                        stopContinuousDecryption();
                    });
                    return;
                }

                SharedPreferences fakeMap = getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
                Set<String> wantedIds = new HashSet<>();

                Log.d("LT_TEXT_DEBUG", "Starting text decryption for " + finalTxtList.size() + " text bubbles");

                // ====== טקסט ======
                for (Pair<AccessibilityNodeInfo, Rect> p : finalTxtList) {
                    final AccessibilityNodeInfo node = p.first;
                    final Rect bounds = p.second;
                    final CharSequence bubbleText = node.getText();
                    final String cipherOrFake = (bubbleText != null) ? bubbleText.toString() : null;

                    if (cipherOrFake == null || cipherOrFake.length() < 10) {
                        mainHandler.post(node::recycle);
                        continue;
                    }

                    String entryJson = fakeMap.contains(cipherOrFake) ? fakeMap.getString(cipherOrFake, null) : null;
                    if (entryJson != null && entryJson.trim().startsWith("{")) {
                        try {
                            FakeMapEntry entry = new com.google.gson.Gson().fromJson(entryJson, FakeMapEntry.class);
                            boolean outgoing = entry.outgoing;
                            String senderPhone = entry.senderPhone;
                            String receiverPhone = senderPhone.equals(myPhoneFinal) ? peerPhoneFinal : myPhoneFinal;
                            String plain = EncryptionHelper.decryptFromString(entry.encrypted, senderPhone, receiverPhone);

                            String id = OverlayManager.bubbleId(cipherOrFake, bounds, outgoing);
                            wantedIds.add(id);

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
                                mainHandler.post(node::recycle);
                            }
                        } catch (Exception e) {
                            mainHandler.post(node::recycle);
                        }
                    } else {
                        // fallback - legacy format
                        final boolean outgoing = isOutgoingBubble(node, bounds);
                        final String senderPhone = outgoing ? myPhoneFinal : peerPhoneFinal;
                        final String receiverPhone = outgoing ? peerPhoneFinal : myPhoneFinal;

                        if (entryJson != null) {
                            String plain = EncryptionHelper.decryptFromString(entryJson, senderPhone, receiverPhone);
                            String id = OverlayManager.bubbleId(cipherOrFake, bounds, outgoing);
                            wantedIds.add(id);
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
                                mainHandler.post(node::recycle);
                            }
                        } else {
                            // fetch from Firebase if not cached locally
                            final AccessibilityNodeInfo nodeCopy = AccessibilityNodeInfo.obtain(node);
                            fetchFakeMappingFromFirebase(myPhoneFinal, peerPhoneFinal, cipherOrFake, new FakeMapFetchCallback() {
                                @Override
                                public void onResult(String cipherFromCloud) {
                                    if (cipherFromCloud != null) {
                                        fakeMap.edit().putString(cipherOrFake, cipherFromCloud).apply();
                                        String plain = EncryptionHelper.decryptFromString(cipherFromCloud, senderPhone, receiverPhone);
                                        if (plain != null) {
                                            mainHandler.post(() -> {
                                                if (!isReallyInWhatsApp()) {
                                                    if (overlayManager != null) overlayManager.hide();
                                                    stopContinuousDecryption();
                                                    nodeCopy.recycle();
                                                    return;
                                                }
                                                overlayManager.showDecryptedOverlay(plain, nodeCopy, bounds, outgoing,
                                                        OverlayManager.bubbleId(cipherOrFake, bounds, outgoing));
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
                            continue;
                        }
                    }
                }

                Log.d("LT_IMAGE_BUBBLE", "Starting image decryption for " + finalImageButtons.size() + " image bubbles");

                // ====== תמונות ======
                boolean useBatch = !batchImageKeysFinal.isEmpty() &&
                        batchImageKeysFinal.size() >= finalImageButtons.size();

                if (useBatch) {
                    Log.d("LT_IMAGE_BUBBLE", "USING BATCH IMAGEKEYS: " + batchImageKeysFinal);
                    handleBatchImageDecryption(finalImageButtons, batchImageKeysFinal,
                            myPhoneFinal, peerPhoneFinal, wantedIds, chatTitleFinal);
                } else {
                    Log.d("LT_IMAGE_BUBBLE", "Using fallback mapping - not enough batch keys or size mismatch");
                    handleIndividualImageDecryption(finalImageButtons, pendingImageKeysFinal,
                            finalImageKeysWithPositions, myPhoneFinal,
                            peerPhoneFinal, wantedIds, prefsLock, fakeMap);
                }

                // ניקוי מטמון ישן
                if (Math.random() < 0.1) {
                    cleanupOldBatchCache();
                }

                mainHandler.post(() -> overlayManager.cleanupBubblesExcept(wantedIds));
                mainHandler.post(() -> decryptRunning = false);

                if (!isRetryingDecrypt) {
                    isRetryingDecrypt = true;
                    mainHandler.postDelayed(() -> {
                        isRetryingDecrypt = false;
                        AccessibilityNodeInfo checkRoot = getRootInActiveWindow();
                        String checkClass = (checkRoot != null && checkRoot.getClassName() != null) ?
                                checkRoot.getClassName().toString() : "";
                        if (!shouldHideDecryptionOverlays() && isConversationScreen( checkRoot)) {
                            decryptChatBubbles();
                        }
                        if (checkRoot != null) checkRoot.recycle();
                    }, 500);
                }
            });
        });
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

    private List<AccessibilityNodeInfo> getAllNodesWithTextOrDesc(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        getAllNodesWithTextOrDescRecursive(root, result);
        return result;
    }

    private void getAllNodesWithTextOrDescRecursive(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        boolean hasText = node.getText() != null && node.getText().length() > 0;
        boolean hasDesc = node.getContentDescription() != null && node.getContentDescription().length() > 0;
        if (hasText || hasDesc) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            getAllNodesWithTextOrDescRecursive(child, result);
            if (child != null) child.recycle();
        }
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
            return doc.getString("encrypted");
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
    // 2. זיהוי כיוון קריאה RTL
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

    // 6. זיהוי timestamp של הודעה לפי מיקום
    private long estimateMessageTimestamp(List<Pair<AccessibilityNodeInfo, Rect>> buttons) {
        if (buttons.isEmpty()) return System.currentTimeMillis();

        // השתמש במיקום Y כבסיס לזיהוי הודעה
        int avgY = buttons.stream()
                .mapToInt(pair -> pair.second.centerY())
                .sum() / buttons.size();

        // יצור timestamp יחסי לפי מיקום Y
        return System.currentTimeMillis() - (avgY * 1000L);
    }

    // 7. הפונקציה הראשית המשופרת
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
    /**
     * מזהה האם המסך הנוכחי הוא שיחה רגילה בווטסאפ (ולא פרופיל איש קשר/קבוצה).
     */
    private boolean isConversationScreen(AccessibilityNodeInfo root) {
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
}