package com.example.locktalk_01.managers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.locktalk_01.activities.AndroidKeystorePlugin;

/**
 * מחלקה מסייעת לעבודה עם AndroidKeystorePlugin:
 * - שמירת/שליפת הודעות מוצפנות
 * - ניהול קוד אישי
 * - מחיקת הודעות
 */
public class MessageEncryptionHelper {
    private static final String TAG = "MessageEncryptionHelper";

    private final Context context;
    private final AndroidKeystorePlugin keystorePlugin;
    private String currentPersonalCode;

    public MessageEncryptionHelper(Context context) {
        this.context = context.getApplicationContext();
        this.keystorePlugin = new AndroidKeystorePlugin(this.context);

        // טען את הקוד האישי הנוכחי מ-SharedPreferences
        SharedPreferences prefs = this.context.getSharedPreferences(
                AndroidKeystorePlugin.USER_CREDENTIALS,
                Context.MODE_PRIVATE
        );
        this.currentPersonalCode = prefs.getString(
                AndroidKeystorePlugin.PERSONAL_CODE_KEY,
                ""
        );
    }

    /**
     * שומר הודעה מוצפנת באמצעות הקוד האישי הנוכחי.
     * @param message הטקסט הגולמי להצפנה
     * @return true אם נשמר בהצלחה, false אחרת
     */
    public boolean saveEncryptedMessage(String message) {
        if (currentPersonalCode.isEmpty()) {
            Log.e(TAG, "Cannot save: personal code not set");
            return false;
        }
        try {
            return keystorePlugin.saveEncryptedMessage(currentPersonalCode, message);
        } catch (Exception e) {
            Log.e(TAG, "Error saving encrypted message", e);
            return false;
        }
    }

    /**
     * מנסה לפענח את ההודעה האחרונה שהוצפנה באמצעות הקוד הנתון.
     * @param personalCode הקוד להזדהות
     * @return הטקסט המפוענח, או null במקרה של שגיאה/קוד שגוי
     */
    public String getDecryptedMessage(String personalCode) {
        if (personalCode == null || personalCode.isEmpty()) {
            Log.e(TAG, "Cannot decrypt: personal code not set");
            return null;
        }
        try {
            return keystorePlugin.getDecryptedMessage(personalCode);
        } catch (Exception e) {
            Log.e(TAG, "Error getting decrypted message", e);
            return null;
        }
    }

    /**
     * עדכון הקוד האישי, כולל שמירת הקוד הישן
     * ל-usedPersonalCodes ולהגדרתו כ–currentPersonalCode.
     */
    public boolean updatePersonalCode(String newCode) {
        if (newCode == null || newCode.isEmpty()) {
            Log.e(TAG, "Cannot update: new code is empty");
            return false;
        }
        try {
            if (!currentPersonalCode.isEmpty()) {
                keystorePlugin.addUsedPersonalCode(currentPersonalCode);
            }
            boolean pluginOk = keystorePlugin.updatePersonalCode(newCode);

            SharedPreferences prefs = context.getSharedPreferences(
                    AndroidKeystorePlugin.USER_CREDENTIALS,
                    Context.MODE_PRIVATE
            );
            prefs.edit()
                    .putString(AndroidKeystorePlugin.PERSONAL_CODE_KEY, newCode)
                    .apply();

            if (pluginOk) {
                currentPersonalCode = newCode;
            }
            return pluginOk;
        } catch (Exception e) {
            Log.e(TAG, "Error updating personal code", e);
            return false;
        }
    }

    /**
     * מוחק את כל ההודעות המוצפנות
     */
    public boolean deleteAllMessages() {
        return keystorePlugin.deleteAllMessages();
    }
}
