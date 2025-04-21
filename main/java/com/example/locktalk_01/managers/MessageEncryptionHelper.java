package com.example.locktalk_01.managers;

import android.content.Context;
import android.util.Log;

import com.example.locktalk_01.activities.AndroidKeystorePlugin;
import com.example.locktalk_01.utils.TextInputUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.security.KeyStore;
public class MessageEncryptionHelper {
    private static final String TAG = "MessageEncryptionHelper";

    private Context context;
    private AndroidKeystorePlugin keystorePlugin;
    private String currentPersonalCode;

    public MessageEncryptionHelper(Context context) {
        this.context = context;
        this.keystorePlugin = new AndroidKeystorePlugin(context);

        // Initialize currentPersonalCode from SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(AndroidKeystorePlugin.USER_CREDENTIALS, Context.MODE_PRIVATE);
        this.currentPersonalCode = prefs.getString(AndroidKeystorePlugin.PERSONAL_CODE_KEY, "");
    }

    /**
     * Save an encrypted message using the current personal code
     */
    public boolean saveEncryptedMessage(String message) {
        try {
            return keystorePlugin.saveEncryptedMessage(message);
        } catch (Exception e) {
            Log.e(TAG, "Error saving encrypted message", e);
            return false;
        }
    }

    /**
     * Get a decrypted message using the specified personal code
     */
    public String getDecryptedMessage(String personalCode) {
        try {
            return keystorePlugin.getDecryptedMessage(personalCode);
        } catch (Exception e) {
            Log.e(TAG, "Error getting decrypted message", e);
            return null;
        }
    }

    /**
     * Update the personal code
     */
    public boolean updatePersonalCode(String newCode) {
        if (newCode == null || newCode.isEmpty()) {
            return false;
        }

        try {
            // Remember the old code if it was set
            if (!currentPersonalCode.isEmpty()) {
                keystorePlugin.addUsedPersonalCode(currentPersonalCode);
            }

            // Update the current code
            currentPersonalCode = newCode;

            SharedPreferences prefs = context.getSharedPreferences(AndroidKeystorePlugin.USER_CREDENTIALS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(AndroidKeystorePlugin.PERSONAL_CODE_KEY, newCode);

            return keystorePlugin.updatePersonalCode(newCode);
        } catch (Exception e) {
            Log.e(TAG, "Error updating personal code", e);
            return false;
        }
    }

    /**
     * Delete all encrypted messages
     */
    public boolean deleteAllMessages() {
        return keystorePlugin.deleteAllMessages();
    }

    /**
     * Copy text to clipboard
     */
    public void copyToClipboard(String text) {
        TextInputUtils.copyToClipboard(context, text);
    }

    /**
     * Try to replace text in WhatsApp input field
     */
    public boolean replaceWhatsAppText(String newText) {
        return TextInputUtils.performTextReplacement(context, null, newText);
    }
}
