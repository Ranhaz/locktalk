package com.example.locktalk_01.services;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import android.app.AlertDialog;
import android.view.WindowManager;
import android.widget.EditText;

/*
 * המלצה: בטל/הסר Service זה מה-Manifest, כי הוא מתנגש עם MessageAccessibilityService.
 * רק אחד מהם צריך לרוץ במקביל.
 */
public class LockTalkAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // הצעתי: אל תפעיל שירות זה במקביל ל-MessageAccessibilityService
        if (event == null || event.getSource() == null) return;

        AccessibilityNodeInfo source = event.getSource();
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {

            CharSequence text = source.getText();
            if (text != null) {
                String content = text.toString();

                if (content.endsWith(":LOCK")) {
                    Log.d("LockTalk", "הודעה להצפנה זוהתה: " + content);
                    Toast.makeText(this, "הודעה תוצפן: " + content, Toast.LENGTH_SHORT).show();
                    // כאן ניתן להוסיף לוגיקה להצפנה אמיתית
                } else if (content.endsWith(":TALK")) {
                    Log.d("LockTalk", "בקשה לפענוח הודעה: " + content);
                    showDecryptionDialog();
                }
            }
        }
    }

    private void showDecryptionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
        builder.setTitle("הכנס מפתח לפענוח");

        EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("פענח", (dialog, which) -> {
            String key = input.getText().toString();
            Toast.makeText(this, "פענוח עם מפתח: " + key, Toast.LENGTH_SHORT).show();
            // כאן תתבצע הלוגיקה לפענוח ההודעה בעזרת המפתח
        });

        builder.setNegativeButton("ביטול", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    @Override
    public void onInterrupt() {
    }
}
