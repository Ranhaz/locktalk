package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.example.locktalk_01.managers.OverlayManager;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.utils.WhatsAppUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG                = "MyAccessibilityService";
    private static final String PREF_NAME          = "UserCredentials";
    private static final String PERSONAL_CODE_PREF = "personalCode";
    private static final String PREF_FAKE_MAP      = "FakeCipherMap";

    private AndroidKeystorePlugin keystorePlugin;
    private OverlayManager overlayManager;
    private Handler mainHandler;
    private AlertDialog activeDialog;

    private String lastEncryptedOrig    = "";
    private boolean decryptAuthenticated = false;
    private long decryptExpiryTimestamp;

    private long lastDecryptTs = 0;
    private static final long DECRYPT_THROTTLE_MS = 1000;

    private boolean isReplacing = false;
    private boolean overlayHiddenByUser = false;

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler    = new Handler(Looper.getMainLooper());
        keystorePlugin = new AndroidKeystorePlugin(this);
        overlayManager = new OverlayManager(this,
                (WindowManager)getSystemService(WINDOW_SERVICE));
        executor       = Executors.newSingleThreadExecutor();
        checkOverlayPermission();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{
                "com.whatsapp","com.whatsapp.w4b",
                "com.gbwhatsapp","com.whatsapp.plus","com.yowhatsapp"
        };
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;

        long now  = System.currentTimeMillis();
        String pkg = event.getPackageName().toString();
        int type   = event.getEventType();

        boolean inWhatsApp    = WhatsAppUtils.isWhatsAppPackage(pkg);
        boolean decryptActive = decryptAuthenticated && now <= decryptExpiryTimestamp;

        // 1) הצפנה אוטומטית כשמסיימים ב־$
        if (!isReplacing
                && inWhatsApp
                && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence cs = WhatsAppUtils.getWhatsAppInputText(root);
                root.recycle();
                if (cs != null && cs.toString().endsWith("$")) {
                    final String orig = cs.toString().substring(0, cs.length()-1);
                    if (!orig.equals(lastEncryptedOrig)) {
                        SharedPreferences prefs =
                                getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                        final String code = prefs.getString(PERSONAL_CODE_PREF,"");
                        if (code.isEmpty()) {
                            showToast("נא להגדיר קוד אישי");
                        } else {
                            isReplacing = true;
                            mainHandler.postDelayed(() -> isReplacing = false, 500);
                            executor.execute(() -> {
                                try {
                                    String actualCipher =
                                            keystorePlugin.encryptToString(code, orig);
                                    String fake = generateFakeEncryptedText(actualCipher.length());
                                    getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE)
                                            .edit().putString(fake, actualCipher).apply();
                                    mainHandler.post(() -> {
                                        AccessibilityNodeInfo r2 = getRootInActiveWindow();
                                        if (r2 != null) {
                                            TextInputUtils.performTextReplacement(this, r2, fake);
                                            r2.recycle();
                                        }
                                        showToast("הודעה מוצפנת");
                                        lastEncryptedOrig = orig;
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG,"encrypt error",e);
                                    mainHandler.post(() -> showToast("שגיאה בהצפנה"));
                                }
                            });
                        }
                    }
                }
            }
        }

        // 2) פענוח רציף עם throttle
        if (decryptActive
                && (type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type==AccessibilityEvent.TYPE_VIEW_SCROLLED)
                && now - lastDecryptTs > DECRYPT_THROTTLE_MS) {
            lastDecryptTs = now;
            final List<Pair<String,Rect>> toDecrypt = new ArrayList<>();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> bubbles = WhatsAppUtils.findEncryptedMessages(root);
                for (AccessibilityNodeInfo n : bubbles) {
                    CharSequence t = n.getText();
                    if (t != null) {
                        Rect b = new Rect();
                        n.getBoundsInScreen(b);
                        toDecrypt.add(new Pair<>(t.toString(), b));
                    }
                    n.recycle();
                }
                root.recycle();
            }
            executor.execute(() -> {
                SharedPreferences fakeMap =
                        getSharedPreferences(PREF_FAKE_MAP, MODE_PRIVATE);
                SharedPreferences prefs   =
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                for (Pair<String,Rect> p : toDecrypt) {
                    String fake = p.first;
                    String actual = fakeMap.getString(fake,null);
                    if (actual!=null) {
                        String code = prefs.getString(PERSONAL_CODE_PREF,"");
                        try {
                            String plain = keystorePlugin.loadDecryptedMessage(code, actual);
                            if (plain!=null) {
                                Rect b = p.second;
                                mainHandler.post(() ->
                                        overlayManager.showDecryptedOverlay(plain, b)
                                );
                            }
                        } catch(Exception e) {
                            Log.w(TAG,"decrypt failed",e);
                        }
                    }
                }
            });
        }

        // 3) ניהול ה־Overlay רק על שינוי חלון
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            manageOverlay(inWhatsApp, decryptActive);
        }
    }

    @Override public void onInterrupt() {}

    private void manageOverlay(boolean inWhatsApp, boolean decryptActive) {
        boolean shouldShow =
                (inWhatsApp || decryptActive) &&
                        !(overlayHiddenByUser && !decryptActive);

        if (shouldShow && !overlayManager.isShown()) {
            showMainOverlay();
        } else if ((!shouldShow || overlayHiddenByUser) && overlayManager.isShown()) {
            decryptAuthenticated  = false;
            overlayHiddenByUser   = false;
            overlayManager.clearDecryptOverlays();
            overlayManager.hide();
        }

        if (!inWhatsApp) {
            decryptAuthenticated  = false;
            overlayHiddenByUser   = false;
        }
    }

    /** מציג את ה־overlay הראשי במיקום קבוע + כפתור X שמבטל גם טיימר */
    private void showMainOverlay() {
        overlayManager.show(
                v-> {},
                v-> {
                    decryptAuthenticated = false;
                    overlayHiddenByUser  = false;     // כדי שלא נחסום show
                    overlayManager.stopTimer();           // עוצר ומחזיר את הכפתור הירוק
                    overlayManager.clearDecryptOverlays(); // מנקה את הבועות
                },
                v-> showDecryptAuthDialog()
        );
        int x = dpToPx(36);
        int y = dpToPx(56) - mmToPx(5);
        overlayManager.updatePosition(x, y);
    }

    private void showDecryptAuthDialog() {
        if (overlayManager.isShown()) overlayManager.hide();
        if (activeDialog!=null && activeDialog.isShowing()) activeDialog.dismiss();
        if (decryptAuthenticated && System.currentTimeMillis()<=decryptExpiryTimestamp) {
            showDecryptDurationDialog();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("הזן קוד אימות");
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("קוד פיענוח"))
                .setView(input)
                .setPositiveButton("אישור",(d,w)->{
                    String ent = input.getText().toString();
                    String saved = getSharedPreferences(PREF_NAME,MODE_PRIVATE)
                            .getString(PERSONAL_CODE_PREF,"");
                    if (ent.equals(saved)) {
                        decryptAuthenticated = true;
                        overlayHiddenByUser  = false;
                        showToast("אומת בהצלחה");
                        showDecryptDurationDialog();
                    } else {
                        showToast("קוד שגוי");
                    }
                })
                .setNegativeButton("ביטול",null)
                .create();
        configureDialogWindow(dlg);
        dlg.show();
        activeDialog = dlg;
    }

    private void showDecryptDurationDialog() {
        if (activeDialog!=null && activeDialog.isShowing()) activeDialog.dismiss();

        String[] items = {"1 דקה","5 דקות","10 דקות","15 דקות","30 דקות","שעה"};
        int[] mins     = {1,5,10,15,30,60};
        final int[] sel = {-1};

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(makeCenteredTitle("משך פיענוח"))
                .setSingleChoiceItems(items,-1,(d,w)-> sel[0]=w)
                .setPositiveButton("הפעל",(d,w)->{
                    if (sel[0]<0) {
                        showToast("לא נבחר משך זמן");
                        return;
                    }
                    decryptExpiryTimestamp =
                            System.currentTimeMillis() + mins[sel[0]]*60_000L;
                    showToast("פיענוח פעיל: "+items[sel[0]]);
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
                .setNegativeButton("ביטול",null)
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
        lp.type    = Build.VERSION.SDK_INT>=Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.flags  &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        lp.dimAmount = 0f;
        lp.gravity = Gravity.CENTER;
        dlg.getWindow().setAttributes(lp);
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            mainHandler.post(() -> {
                showToast("נא לאפשר הצגה מעל אפליקציות");
                Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:"+getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            });
        }
    }

    private String generateFakeEncryptedText(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i=0; i<length; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
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
        return Math.round(mm * dm.xdpi/25.4f);
    }
}
