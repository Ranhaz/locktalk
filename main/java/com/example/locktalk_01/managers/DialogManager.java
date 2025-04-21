package com.example.locktalk_01.managers;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

import com.example.locktalk_01.R;
import com.example.locktalk_01.managers.MessageEncryptionHelper;

public class DialogManager {
    private static final String TAG = "DialogManager";

    private final Context context;
    private final MessageEncryptionHelper encryptionHelper;
    private AlertDialog activeDialog = null;
    private Handler mainHandler;

    public DialogManager(Context context, MessageEncryptionHelper encryptionHelper) {
        this.context = context;
        this.encryptionHelper = encryptionHelper;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void showToast(final String message) {
        if (mainHandler != null) {
            mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }
    }

    public void showEncryptionDialog(String originalMessage, OverlayManager overlayManager) {
        // Dismiss any existing dialog to prevent multiple dialogs
        dismissActiveDialog();
        overlayManager.hide(); // Fix: use hide() instead of hideEncryptionOverlay()

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.encryption_dialog, null);
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
            showToast("שגיאה בהצגת החלון: " + e.getMessage());
        }
    }

    private void handleEncryption(String message, AlertDialog dialog) {
        // הוסף את הלוגיקה של הצפנת ההודעה כאן
        // דוגמא:
        if (message.isEmpty()) {
            showToast("יש להזין הודעה להצפנה");
            return;
        }

        try {
            boolean success = encryptionHelper.saveEncryptedMessage(message);
            if (success) {
                showToast("ההודעה הוצפנה בהצלחה");
            } else {
                showToast("שגיאה בהצפנה");
            }
        } catch (Exception e) {
            showToast("שגיאה בהצפנה: " + e.getMessage());
        }

        dialog.dismiss();
        activeDialog = null;
    }

    public void showDecryptionDialog(ArrayList<String> messages, OverlayManager overlayManager) {
        dismissActiveDialog();
        overlayManager.hide(); // Fix: use hide() instead of hideEncryptionOverlay()

        try {
            // עיקר לוגיקת הפענוח
            // דוגמא:
            String primaryMessage = messages.isEmpty() ? "" : messages.get(messages.size() - 1);

            AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.personal_code_dialog, null);
            builder.setView(dialogView);

            TextView encryptedMessageText = dialogView.findViewById(R.id.encryptedMessageText);
            TextView decryptedMessageText = dialogView.findViewById(R.id.decryptedMessageText);
            EditText personalCodeInput = dialogView.findViewById(R.id.personalCodeDialogInput);
            Button confirmButton = dialogView.findViewById(R.id.personalCodeConfirmButton);
            Button cancelButton = dialogView.findViewById(R.id.personalCodeCancelButton);

            String displayText = primaryMessage;
            if (messages.size() > 1) {
                displayText += " (+" + (messages.size() - 1) + " הודעות נוספות)";
            }
            encryptedMessageText.setText(displayText);

            decryptedMessageText.setText("");
            decryptedMessageText.setVisibility(View.GONE);

            personalCodeInput.setText("");
            personalCodeInput.requestFocus();

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
                    String decryptedMessage = encryptionHelper.getDecryptedMessage(personalCode);
                    if (decryptedMessage != null) {
                        decryptedMessageText.setText(decryptedMessage);
                        decryptedMessageText.setVisibility(View.VISIBLE);
                        showToast("ההודעה פוענחה בהצלחה");
                    } else {
                        showToast("לא נמצאה הודעה מוצפנת עם הקוד שהוזן");
                    }
                } catch (Exception e) {
                    showToast("שגיאה בפענוח ההודעה");
                }
            });

            cancelButton.setOnClickListener(v -> {
                dialog.dismiss();
                activeDialog = null;
            });

            dialog.show();
            activeDialog = dialog;

        } catch (Exception e) {
            showToast("שגיאה בהצגת חלון הפענוח: " + e.getMessage());
        }
    }

    public void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            try {
                activeDialog.dismiss();
            } catch (Exception e) {
                // Log the error
            }
            activeDialog = null;
        }
    }
}
