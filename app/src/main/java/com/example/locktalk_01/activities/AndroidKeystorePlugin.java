package com.example.locktalk_01.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AndroidKeystorePlugin {
    public static final String ANDROID_KEYSTORE    = "AndroidKeyStore";
    private static final String TRANSFORMATION    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH     = 12;
    private static final int    GCM_TAG_LENGTH    = 128;
    public static final String PREFS_NAME        = "EncryptedMessages";
    public static final String USER_CREDENTIALS  = "UserCredentials";
    public static final String PERSONAL_CODE_KEY = "personalCode";

    private final Context context;
    private String currentPersonalCode;

    public AndroidKeystorePlugin(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences prefs = this.context
                .getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        this.currentPersonalCode = prefs.getString(PERSONAL_CODE_KEY, "");
    }

    /**
     * 1) שומר ב־SharedPreferences את ההודעה המוצפנת תחת timestamp
     */
    public boolean saveEncryptedMessage(String personalCode,
                                        String message) throws Exception {
        if (personalCode == null || personalCode.isEmpty()) return false;
        if (!personalCode.equals(currentPersonalCode)) {
            addUsedPersonalCode(personalCode);
        }
        currentPersonalCode = personalCode;
        String key = "message_" + System.currentTimeMillis();
        return encryptAndSave(key, message);
    }

    /**
     * 2) חוזר את ה־Base64 של ההצפנה (בלי לשמור)
     */
    public String encryptToString(String personalCode,
                                  String message) throws Exception {
        if (personalCode == null || personalCode.isEmpty()) return null;
        if (!personalCode.equals(currentPersonalCode)) {
            addUsedPersonalCode(personalCode);
        }
        currentPersonalCode = personalCode;
        // ממש כמו encryptAndSave, רק שלא שומרים
        String alias = generateKeyAlias(personalCode);
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator kgen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
            );
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();
            kgen.init(spec);
            kgen.generateKey();
        }
        SecretKey secretKey = (SecretKey) keyStore.getKey(alias, null);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(
                message.getBytes(StandardCharsets.UTF_8)
        );
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.DEFAULT);
    }

    private boolean encryptAndSave(String key,
                                   String value) throws Exception {
        String encryptedString = encryptToString(currentPersonalCode, value);
        SharedPreferences prefs = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.edit().putString(key, encryptedString).commit();
    }

    /**
     * 3) פענוח של מחרוזת מוצפנת בודדת
     */
    public String loadDecryptedMessage(String personalCode,
                                       String encryptedString) throws Exception {
        if (personalCode == null || personalCode.isEmpty()
                || encryptedString == null) {
            return null;
        }
        String alias = generateKeyAlias(personalCode);
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) return null;
        SecretKey secretKey = (SecretKey) keyStore.getKey(alias, null);
        if (secretKey == null) return null;
        byte[] combined = Base64.decode(encryptedString, Base64.DEFAULT);
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 4) מחזיר את ההודעה המפוענחת האחרונה (הכי עדכנית)
     */
    public String getDecryptedMessage(String personalCode) throws Exception {
        if (personalCode == null || personalCode.isEmpty()) return null;
        SharedPreferences prefs = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
        if (latestKey == null) return null;
        String enc = prefs.getString(latestKey, null);
        return (enc != null)
                ? loadDecryptedMessage(personalCode, enc)
                : null;
    }

    /**
     * 5) עדכון personal code ב־Prefs
     */
    public boolean updatePersonalCode(String newCode) {
        if (newCode == null || newCode.isEmpty()) return false;
        if (currentPersonalCode != null && !currentPersonalCode.isEmpty()) {
            addUsedPersonalCode(currentPersonalCode);
        }
        SharedPreferences prefs = context
                .getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        boolean ok = prefs.edit()
                .putString(PERSONAL_CODE_KEY, newCode)
                .commit();
        if (ok) currentPersonalCode = newCode;
        return ok;
    }

    /**
     * 6) מחיקת כל ההודעות
     */
    public boolean deleteAllMessages() {
        SharedPreferences prefs = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.edit().clear().commit();
    }

    /**
     * 7) שמירת קוד שנעשה בו שימוש לפני
     */
    public void addUsedPersonalCode(String code) {
        if (code == null || code.isEmpty()) return;
        SharedPreferences prefs = context
                .getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        String existing = prefs.getString("usedPersonalCodes", "");
        if (!Arrays.asList(existing.split(",")).contains(code)) {
            String updated = existing.isEmpty()
                    ? code
                    : existing + "," + code;
            prefs.edit().putString("usedPersonalCodes", updated).apply();
        }
    }

    private String generateKeyAlias(String personalCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(personalCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                String h = Integer.toHexString(0xff & hash[i]);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return "encryption_key_" + sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e("AndroidKeystorePlugin", "SHA-256 not found", e);
            return "encryption_key_" + personalCode;
        }
    }
}
