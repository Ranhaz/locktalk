package com.example.locktalk_01.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Map;
import javax.crypto.SecretKey;
import com.example.locktalk_01.utils.EncryptionHelper;

public class AndroidKeystorePlugin {
    private static final String TAG = "AndroidKeystorePlugin";
    public static final String PREFS_NAME = "EncryptedMessages";
    public static final String USER_CREDENTIALS = "UserCredentials";
    public static final String MY_PHONE_KEY = "myPhone";
    public static final String PEER_PHONE_KEY = "peerPhone";

    private final Context context;
    private String myPhone;
    private String peerPhone;

    public AndroidKeystorePlugin(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences prefs = this.context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        this.myPhone = prefs.getString(MY_PHONE_KEY, "");
        this.peerPhone = prefs.getString(PEER_PHONE_KEY, "");
    }

    public boolean updatePhoneNumbers(String myPhone, String peerPhone) {
        if (myPhone == null || myPhone.isEmpty() ||
                peerPhone == null || peerPhone.isEmpty()) {
            return false;
        }
        this.myPhone = myPhone.trim();
        this.peerPhone = peerPhone.trim();
        SharedPreferences prefs = context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        boolean success = prefs.edit()
                .putString(MY_PHONE_KEY, this.myPhone)
                .putString(PEER_PHONE_KEY, this.peerPhone)
                .commit();
        Log.d(TAG, "Updated phone numbers: my=" + this.myPhone + ", peer=" + this.peerPhone);
        return success;
    }

    public boolean saveEncryptedMessage(String message) throws Exception {
        if (message == null || message.isEmpty()) return false;
        if (!arePhoneNumbersSet()) {
            Log.e(TAG, "Phone numbers not set - cannot encrypt");
            return false;
        }
        String key = "message_" + System.currentTimeMillis();
        return encryptAndSave(key, message);
    }

    public String encryptToString(String message) throws Exception {
        if (message == null || message.isEmpty()) return null;
        if (!arePhoneNumbersSet()) {
            Log.e(TAG, "Phone numbers not set - cannot encrypt");
            return null;
        }
        try {
            return EncryptionHelper.encryptToString(message, myPhone, peerPhone);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            throw e;
        }
    }

    private boolean encryptAndSave(String key, String value) throws Exception {
        String encryptedString = encryptToString(value);
        if (encryptedString == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.edit().putString(key, encryptedString).commit();
    }

    public String loadDecryptedMessage(String encryptedString) throws Exception {
        if (encryptedString == null || encryptedString.isEmpty()) {
            Log.e(TAG, "Encrypted string is null or empty");
            return null;
        }
        if (!arePhoneNumbersSet()) {
            Log.e(TAG, "Phone numbers not set - cannot decrypt");
            return null;
        }
        try {
            return EncryptionHelper.decryptFromString(encryptedString, myPhone, peerPhone);
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    public String loadDecryptedMessageWithPhones(String encryptedString, String userPhone, String peerPhone) throws Exception {
        if (encryptedString == null || encryptedString.isEmpty() ||
                userPhone == null || userPhone.isEmpty() ||
                peerPhone == null || peerPhone.isEmpty()) {
            return null;
        }
        try {
            return EncryptionHelper.decryptFromString(encryptedString, userPhone.trim(), peerPhone.trim());
        } catch (Exception e) {
            Log.e(TAG, "Decryption with specific phones failed", e);
            return null;
        }
    }

    public String getDecryptedMessage() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        long latestTs = -1;
        String latestKey = null;
        for (String key : all.keySet()) {
            if (key.startsWith("message_")) {
                try {
                    long ts = Long.parseLong(key.substring("message_".length()));
                    if (ts > latestTs) {
                        latestTs = ts;
                        latestKey = key;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        if (latestKey == null) {
            Log.d(TAG, "No messages found");
            return null;
        }
        String enc = prefs.getString(latestKey, null);
        return (enc != null) ? loadDecryptedMessage(enc) : null;
    }

    public boolean deleteAllMessages() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.edit().clear().commit();
    }

    public String getMyPhone() {
        return myPhone;
    }

    public String getPeerPhone() {
        return peerPhone;
    }

    public boolean arePhoneNumbersSet() {
        return myPhone != null && !myPhone.isEmpty() &&
                peerPhone != null && !peerPhone.isEmpty();
    }

    public String getChatKeySeed() {
        if (!arePhoneNumbersSet()) return null;
        String k1 = myPhone.trim();
        String k2 = peerPhone.trim();
        return k1.compareTo(k2) < 0 ? k1 + "-" + k2 : k2 + "-" + k1;
    }
}
