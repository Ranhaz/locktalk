package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Toast;

import com.example.locktalk_01.utils.SecurityUtils;
import com.example.locktalk_01.utils.SharedPrefsManager;

import java.util.List;

/**
 * שירות נגישות שמאזין לטקסט המוקלד ב-WhatsApp.
 * אם המשתמש כותב הודעה שמסתיימת ב-":LOCK", היא תוחלף בהצפנה (AES).
 * אם המשתמש כותב ":TALK" בסוף, יוצג דיאלוג לפענוח.
 */
public class MessageAccessibilityService extends AccessibilityService {

    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private SharedPrefsManager prefsManager;
    private String currentChatPhoneNumber;
    private final Handler handler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        prefsManager = new SharedPrefsManager(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // בודקים קודם כל שהחלון הפעיל הוא אכן WhatsApp
        if (event.getPackageName() == null ||
                !WHATSAPP_PACKAGE.equals(event.getPackageName().toString())) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            return;
        }

        // נאתר את שם/מספר איש הקשר (מכותרת השיחה)
        updateCurrentChatPhoneNumber(rootNode);

        // ננסה למצוא את תיבת הטקסט:
        List<AccessibilityNodeInfo> messageInputNodes =
                rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (messageInputNodes.isEmpty()) {
            // בגרסאות חדשות - ייתכן שזהו ה-ID
            messageInputNodes =
                    rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/chat_edit_text");
        }

        if (!messageInputNodes.isEmpty()) {
            AccessibilityNodeInfo messageInput = messageInputNodes.get(0);
            CharSequence textCS = messageInput.getText();
            if (textCS != null) {
                String inputText = textCS.toString().trim();

                // אם מסתיים ב-":LOCK" => נצפין
                if (inputText.endsWith(":LOCK")) {
                    handleOutgoingMessage(messageInput, inputText);
                }
                // אם מסתיים ב-":TALK" => נציג דיאלוג פענוח
                else if (inputText.endsWith(":TALK")) {
                    showDecryptionDialog();
                }
            }
        }

        rootNode.recycle();
    }

    private void handleOutgoingMessage(AccessibilityNodeInfo messageInput, String message) {
        if (currentChatPhoneNumber == null || message.isEmpty()) {
            return;
        }

        // הסרת ":LOCK" מהסוף
        String originalText = message.replace(":LOCK", "").trim();

        // שליפת המפתח מה-Keystore
        String encryptionKey = prefsManager.getKeyFromKeystore(currentChatPhoneNumber);
        if (encryptionKey != null) {
            String encryptedMessage = SecurityUtils.encrypt(originalText, encryptionKey);
            if (encryptedMessage != null) {
                // נחליף את הטקסט בטקסט המוצפן לאחר השהיה קצרה (כדי שוואטסאפ תזהה את השינוי)
                handler.postDelayed(() -> {
                    messageInput.refresh();
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            encryptedMessage
                    );
                    // מחליפים את תיבת הטקסט
                    messageInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

                    // אופציונלי: לשלוח אוטומטית:
                    /*
                    List<AccessibilityNodeInfo> sendButtons =
                        getRootInActiveWindow().findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (!sendButtons.isEmpty()) {
                        sendButtons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    */

                    Toast.makeText(this, "הודעה הוצפנה בהצלחה", Toast.LENGTH_SHORT).show();

                }, 300);
            } else {
                Toast.makeText(this, "שגיאה בתהליך ההצפנה", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "לא נמצא מפתח הצפנה עבור איש קשר זה", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDecryptionDialog() {
        if (currentChatPhoneNumber == null) {
            Toast.makeText(this, "לא אותרה שיחה פעילה", Toast.LENGTH_SHORT).show();
            return;
        }

        // שליפת המפתח
        String encryptionKey = prefsManager.getKeyFromKeystore(currentChatPhoneNumber);
        if (encryptionKey == null) {
            Toast.makeText(this, "אין מפתח הצפנה עבור איש קשר זה", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("פענוח הודעה");

        EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("פענח", (dialog, which) -> {
            String encryptedMessage = input.getText().toString();
            String decrypted = SecurityUtils.decrypt(encryptedMessage, encryptionKey);
            if (decrypted != null) {
                Toast.makeText(this, "הודעה מפוענחת: " + decrypted, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "נכשל בפענוח", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("בטל", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        // כדי שיופיע מעל אפליקציות אחרות
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private void updateCurrentChatPhoneNumber(AccessibilityNodeInfo rootNode) {
        // זיהוי שם/מספר איש הקשר בכותרת השיחה
        List<AccessibilityNodeInfo> titleNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/conversation_contact_name");
        if (!titleNodes.isEmpty()) {
            AccessibilityNodeInfo titleNode = titleNodes.get(0);
            if (titleNode != null && titleNode.getText() != null) {
                currentChatPhoneNumber = titleNode.getText().toString();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // אם הופסק - אין מה לעשות
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Toast.makeText(this, "LockTalk service connected", Toast.LENGTH_SHORT).show();
    }
}
