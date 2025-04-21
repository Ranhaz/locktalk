package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.example.locktalk_01.R;
import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.managers.DialogManager;
import com.example.locktalk_01.managers.MessageEncryptionHelper;
import  com.example.locktalk_01.utils.WhatsAppUtils;
import com.example.locktalk_01.utils.TextInputUtils;
import com.example.locktalk_01.managers.OverlayManager;

public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "MyAccessibilityService";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PERSONAL_CODE_PREF = "personalCode";
    private static final int MAX_TRACKED_MESSAGES = 5;

    private AndroidKeystorePlugin keystorePlugin;
    private OverlayManager overlayManager;
    private Handler mainHandler;
    private long lastOverlayCheck = 0;
    private ArrayList<String> selectedMessages = new ArrayList<>();
    private long lastSelectionTimestamp = 0;
    private long lastSelectionCheckTime = 0;
    private static final long SELECTION_CHECK_INTERVAL = 300; // check every 300ms to avoid too many operations
    private AlertDialog activeDialog = null; // Keep track of active dialog to prevent multiple dialogs
    // רשימת חבילות אפשריות של WhatsApp
    private List<String> whatsappPackages = Arrays.asList(
            "com.whatsapp",       // WhatsApp רגיל
            "com.whatsapp.w4b",   // WhatsApp עסקי
            "com.gbwhatsapp",     // GB WhatsApp
            "com.whatsapp.plus",  // WhatsApp Plus
            "com.yowhatsapp"      // YoWhatsApp
    );

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            mainHandler = new Handler(Looper.getMainLooper());
            keystorePlugin = new AndroidKeystorePlugin(this);
            overlayManager = new OverlayManager(this, (WindowManager) getSystemService(WINDOW_SERVICE));
            Log.d(TAG, "Service created successfully");

            checkOverlayPermission();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: ", e);
            showToast("שגיאה באתחול השירות: " + e.getMessage());
        }
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            mainHandler.post(() -> {
                showToast("אנא הפעל הרשאת 'הצגה מעל אפליקציות אחרות'");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getPackageName() == null) {
                return;
            }

            // נבדוק אם האירוע מגיע מאחת מגרסאות WhatsApp המוכרות
            String packageName = event.getPackageName().toString();
            if (!isWhatsAppPackage(packageName)) {
                return;
            }

            int eventType = event.getEventType();
            Log.d(TAG, "Received event from WhatsApp: " + eventType + " (package: " + packageName + ")");

            // Check if we need to throttle events
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastOverlayCheck < 200) {
                return;
            }
            lastOverlayCheck = currentTime;

            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission not granted");
                return;
            }

            // Improved selection detection with better categorization
            if (currentTime - lastSelectionCheckTime > SELECTION_CHECK_INTERVAL) {
                lastSelectionCheckTime = currentTime;

                // These are events that indicate user selection or interaction
                boolean isSelectionEvent = (
                        eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                                eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
                                eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                                eventType == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
                );

                if (isSelectionEvent) {
                    Log.d(TAG, "Selection-related event detected: " + eventType);
                    captureSelectedText();
                }
            }

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) {
                Log.d(TAG, "Root node is null");
                return;
            }

            try {
                handleWhatsAppState(rootNode);
            } finally {
                rootNode.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onAccessibilityEvent: ", e);
        }
    }

    // בדיקה אם החבילה היא אחת מגרסאות WhatsApp
    private boolean isWhatsAppPackage(String packageName) {
        if (packageName == null) return false;

        for (String whatsappPackage : whatsappPackages) {
            if (packageName.equals(whatsappPackage)) {
                return true;
            }
        }
        return false;
    }

    // New method to capture selected text
    private void captureSelectedText() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            try {
                String selectedText = WhatsAppUtils.getSelectedMessage(rootNode);
                if (selectedText != null && !selectedText.isEmpty()) {
                    Log.d(TAG, "New selected text detected: " + selectedText);

                    // Add to our tracked messages if it's not already in the list
                    if (!selectedMessages.contains(selectedText)) {
                        selectedMessages.add(selectedText);

                        // Keep only the MAX_TRACKED_MESSAGES most recent messages
                        if (selectedMessages.size() > MAX_TRACKED_MESSAGES) {
                            selectedMessages.remove(0);
                        }

                        Log.d(TAG, "Added to selected messages. Current count: " + selectedMessages.size());
                    }

                    lastSelectionTimestamp = System.currentTimeMillis();
                }
            } finally {
                rootNode.recycle();
            }
        }
    }

    private void handleWhatsAppState(AccessibilityNodeInfo rootNode) {
        boolean isChatWindow = WhatsAppUtils.isInWhatsAppChat(rootNode);
        Log.d(TAG, "Is in WhatsApp chat: " + isChatWindow);

        if (isChatWindow) {
            if (!overlayManager.isShown()) {
                Log.d(TAG, "In WhatsApp chat window, showing overlay buttons");
                showEncryptAndDecryptButtons();
            }
        } else if (overlayManager.isShown()) {
            Log.d(TAG, "Not in WhatsApp chat window, hiding overlay buttons");
            overlayManager.hide();
        }
    }

    private void showEncryptAndDecryptButtons() {
        Log.d(TAG, "Showing encrypt and decrypt buttons");
        overlayManager.show(
                v -> showEncryptionDialog(getMessageText()),
                v -> overlayManager.hide(),
                v -> {
                    // Improved handling of decrypt action
                    overlayManager.hide();

                    // When decrypt button is clicked, first try to get a fresh selection
                    captureSelectedText();

                    // If we have tracked messages, use those for decryption
                    if (!selectedMessages.isEmpty()) {
                        Log.d(TAG, "Using selected messages for decrypt. Count: " + selectedMessages.size());
                        showDecryptionDialog(selectedMessages);
                    } else {
                        showToast("בחר הודעה לפיענוח");
                    }
                }
        );
    }

    private String getMessageText() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            try {
                return WhatsAppUtils.getWhatsAppInputText(rootNode);
            } finally {
                rootNode.recycle();
            }
        }
        return "";
    }

    private String getSelectedText() {
        // Improved method to get the user-selected text
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        String selectedMessage = null;

        if (rootNode != null) {
            try {
                // First try to get a fresh selection from the screen
                selectedMessage = WhatsAppUtils.getSelectedMessage(rootNode);
                if (selectedMessage != null && !selectedMessage.isEmpty()) {
                    Log.d(TAG, "Found fresh selected message: " + selectedMessage);
                    // Add to our tracked list
                    if (!selectedMessages.contains(selectedMessage)) {
                        selectedMessages.add(selectedMessage);
                        if (selectedMessages.size() > MAX_TRACKED_MESSAGES) {
                            selectedMessages.remove(0);
                        }
                    }
                    lastSelectionTimestamp = System.currentTimeMillis();
                    return selectedMessage;
                }
            } finally {
                rootNode.recycle();
            }
        }

        // If we couldn't get a fresh selection, use our tracked messages if they exist
        if (!selectedMessages.isEmpty()) {
            Log.d(TAG, "Using most recent tracked message: " + selectedMessages.get(selectedMessages.size() - 1));
            return selectedMessages.get(selectedMessages.size() - 1);
        }

        return "";
    }

    private void showEncryptionDialog(String originalMessage) {
        // Dismiss any existing dialog to prevent multiple dialogs
        dismissActiveDialog();
        overlayManager.hide();

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            View dialogView = LayoutInflater.from(this).inflate(R.layout.encryption_dialog, null);
            builder.setView(dialogView);

            final EditText messageInput = dialogView.findViewById(R.id.dialogMessageInput);
            messageInput.setText(originalMessage);

            Button encryptButton = dialogView.findViewById(R.id.dialogEncryptButton);
            Button cancelButton = dialogView.findViewById(R.id.dialogCancelButton);

            final AlertDialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialog.setCancelable(false);

            encryptButton.setOnClickListener(v -> handleEncryption(messageInput.getText().toString(), dialog));
            cancelButton.setOnClickListener(v -> {
                dialog.dismiss();
                activeDialog = null;
            });

            dialog.show();
            activeDialog = dialog;
        } catch (Exception e) {
            Log.e(TAG, "Error showing encryption dialog: ", e);
            showToast("שגיאה בהצגת החלון: " + e.getMessage());
        }
    }

    private void handleEncryption(String message, AlertDialog dialog) {
        if (message.isEmpty()) {
            showToast("יש להזין הודעה להצפנה");
            return;
        }

        try {
            String personalCode = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getString(PERSONAL_CODE_PREF, "");

            if (personalCode.isEmpty()) {
                showToast("נא להגדיר קוד אישי בהגדרות");
                return;
            }

            boolean success = keystorePlugin.saveEncryptedMessage(personalCode, message);

            if (success) {
                String encryptedText = generateFakeEncryptedText(message);
                boolean replaced = replaceWhatsAppText(encryptedText);

                if (!replaced) {
                    TextInputUtils.copyToClipboard(this, encryptedText);
                    showToast("הטקסט המוצפן הועתק ללוח, הדבק אותו בתיבת ההודעה");
                }

                showToast("ההודעה הוצפנה בהצלחה");
            } else {
                showToast("שגיאה בהצפנה");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during encryption", e);
            showToast("שגיאה בהצפנה: " + e.getMessage());
        }

        dialog.dismiss();
        activeDialog = null;
    }

    private boolean replaceWhatsAppText(String newText) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return false;

        try {
            Log.d(TAG, "Starting text replacement process with enhanced methods");

            boolean success = TextInputUtils.performTextReplacement(this, rootNode, newText);

            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> {
                AccessibilityNodeInfo delayedRootNode = getRootInActiveWindow();
                if (delayedRootNode != null) {
                    try {
                        TextInputUtils.performTextReplacement(this, delayedRootNode, newText);
                    } finally {
                        delayedRootNode.recycle();
                    }
                }
            }, 500);

            return success;
        } finally {
            rootNode.recycle();
        }
    }

    private String generateFakeEncryptedText(String originalText) {
        StringBuilder result = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        int length = originalText.length() * 2;

        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * chars.length());
            result.append(chars.charAt(index));
        }

        return result.toString();
    }

    private void showToast(final String message) {
        if (mainHandler != null) {
            mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected");

        try {
            showToast("שירות הנגישות להצפנת הודעות הופעל");

            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
            }

            // הגדרת חבילות המטרה כדי לכלול את כל גרסאות WhatsApp האפשריות
            info.packageNames = whatsappPackages.toArray(new String[0]);

            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
            info.notificationTimeout = 50;
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;

            setServiceInfo(info);
            checkOverlayPermission();

            new Handler().postDelayed(() -> {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    try {
                        handleWhatsAppState(rootNode);
                    } finally {
                        rootNode.recycle();
                    }
                }
            }, 1000);
        } catch (Exception e) {
            Log.e(TAG, "Error in onServiceConnected: ", e);
            showToast("שגיאה באתחול שירות הנגישות: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        try {
            dismissActiveDialog();
            overlayManager.hide();
            Log.d(TAG, "Service destroyed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: ", e);
        }
        super.onDestroy();
    }

    // Helper method to dismiss any active dialog
    private void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            try {
                activeDialog.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing active dialog", e);
            }
            activeDialog = null;
        }
    }

    private void showDecryptionDialog(ArrayList<String> messages) {
        try {
            // Always hide overlay when showing the dialog
            dismissActiveDialog();
            overlayManager.hide();

            Log.d(TAG, "Starting to show decryption dialog for " + messages.size() + " messages");

            // Use the first message as the primary one
            String primaryMessage = messages.isEmpty() ? "" : messages.get(messages.size() - 1);

            AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            View dialogView = LayoutInflater.from(this).inflate(R.layout.personal_code_dialog, null);
            builder.setView(dialogView);

            TextView encryptedMessageText = dialogView.findViewById(R.id.encryptedMessageText);
            TextView decryptedMessageText = dialogView.findViewById(R.id.decryptedMessageText);
            EditText personalCodeInput = dialogView.findViewById(R.id.personalCodeDialogInput);
            Button confirmButton = dialogView.findViewById(R.id.personalCodeConfirmButton);
            Button cancelButton = dialogView.findViewById(R.id.personalCodeCancelButton);

            // Set the primary selected text
            if (encryptedMessageText != null) {
                String displayText = primaryMessage;
                if (messages.size() > 1) {
                    displayText += " (+" + (messages.size() - 1) + " הודעות נוספות)";
                }
                encryptedMessageText.setText(displayText);
                Log.d(TAG, "Set encrypted text in dialog: " + displayText);
            }

            if (decryptedMessageText != null) {
                decryptedMessageText.setText("");
                decryptedMessageText.setVisibility(View.GONE);
            }

            // Do NOT pre-fill the code to force manual entry each time
            if (personalCodeInput != null) {
                personalCodeInput.setText("");
                // Request focus on the input field
                personalCodeInput.requestFocus();
            }

            final AlertDialog dialog = builder.create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialog.setCancelable(false);

            confirmButton.setOnClickListener(v -> {
                String personalCode = personalCodeInput.getText().toString();
                if (personalCode.length() != 4) {
                    showToast("הקוד האישי חייב להיות 4 ספרות");
                    return;
                }

                try {
                    String decryptedMessage = keystorePlugin.getDecryptedMessage(personalCode);
                    if (decryptedMessage != null) {
                        decryptedMessageText.setText(decryptedMessage);
                        decryptedMessageText.setVisibility(View.VISIBLE);
                        showToast("ההודעה פוענחה בהצלחה");
                    } else {
                        showToast("לא נמצאה הודעה מוצפנת עם הקוד שהוזן");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error decrypting message", e);
                    showToast("שגיאה בפענוח ההודעה");
                }
            });

            cancelButton.setOnClickListener(v -> {
                dialog.dismiss();
                activeDialog = null;
            });

            dialog.show();
            activeDialog = dialog;

            Log.d(TAG, "Decryption dialog shown for " + messages.size() + " messages");
        } catch (Exception e) {
            Log.e(TAG, "Error showing decryption dialog", e);
            showToast("שגיאה בהצגת חלון הפענוח: " + e.getMessage());
        }
    }

    private void showDecryptionDialog(String encryptedText) {
        // Convert single message to an ArrayList and use the multiple message version
        ArrayList<String> messages = new ArrayList<>();
        messages.add(encryptedText);
        showDecryptionDialog(messages);
    }
}