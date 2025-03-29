package com.example.locktalk_01.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SharedPrefsManager {
    private static final String PREF_NAME = "LockTalkPrefs";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;
    private final Context context;

    public SharedPrefsManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setLoggedIn(boolean isLoggedIn) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, isLoggedIn).apply();
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public void setPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply(); // אופציונלי – אפשר גם להצפין
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, null);
    }

    public void storeKeyInKeystore(String alias, String keyToEncrypt) {
        try {
            if (!keyExists(alias)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                keyGenerator.init(new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                keyGenerator.generateKey();
            }

            SecretKey secretKey = getSecretKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(keyToEncrypt.getBytes(StandardCharsets.UTF_8));

            String encrypted = Base64.encodeToString(iv, Base64.DEFAULT) + ":" + Base64.encodeToString(ciphertext, Base64.DEFAULT);
            prefs.edit().putString("key_" + alias, encrypted).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getKeyFromKeystore(String alias) {
        try {
            String encryptedData = prefs.getString("key_" + alias, null);
            if (encryptedData == null) return null;

            String[] parts = encryptedData.split(":");
            byte[] iv = Base64.decode(parts[0], Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(parts[1], Base64.DEFAULT);

            SecretKey secretKey = getSecretKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private SecretKey getSecretKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return ((SecretKey) keyStore.getKey(alias, null));
    }

    private boolean keyExists(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return keyStore.containsAlias(alias);
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
