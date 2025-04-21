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
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AndroidKeystorePlugin {
    public static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    public static final String PREFS_NAME = "EncryptedMessages";
    public static final String USER_CREDENTIALS = "UserCredentials";
    public static final String PERSONAL_CODE_KEY = "personalCode";

    private Context context;
    private String currentPersonalCode; // User's current personal code

    public AndroidKeystorePlugin(Context context) {
        this.context = context;

        // Get current personal code from user preferences
        SharedPreferences prefs = context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        this.currentPersonalCode = prefs.getString(PERSONAL_CODE_KEY, "");
    }

    /**
     * Save an encrypted message using the current personal code
     */
    public boolean saveEncryptedMessage(String message) throws Exception {
        if (currentPersonalCode.isEmpty()) {
            Log.e("AndroidKeystorePlugin", "No personal code set");
            return false;
        }

        return encryptAndSave("message_" + System.currentTimeMillis(), message);
    }

    /**
     * Save an encrypted message using the specified personal code (for backward compatibility)
     */
    public boolean saveEncryptedMessage(String personalCode, String message) throws Exception {
        if (personalCode.isEmpty()) {
            Log.e("AndroidKeystorePlugin", "No personal code provided");
            return false;
        }

        // If this is different from current code, remember it was used
        if (!personalCode.equals(currentPersonalCode)) {
            addUsedPersonalCode(personalCode);
        }

        return encryptAndSave("message_" + System.currentTimeMillis(), message);
    }

    /**
     * Helper method to encrypt and save a message
     */
    private boolean encryptAndSave(String key, String value) throws Exception {
        try {
            // Create a unique key based on the current personal code
            String encryptionKey = generateKeyAlias(currentPersonalCode);

            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            // Create the key if it doesn't exist
            if (!keyStore.containsAlias(encryptionKey)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                );

                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        encryptionKey,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build();

                keyGenerator.init(spec);
                keyGenerator.generateKey();
            }

            SecretKey secretKey = (SecretKey) keyStore.getKey(encryptionKey, null);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] iv = cipher.getIV();
            byte[] encryptedData = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);

            String encryptedString = Base64.encodeToString(combined, Base64.DEFAULT);

            // Store the encrypted data in SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(key, encryptedString);
            boolean saved = editor.commit();

            return saved;
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error in encryptAndSave", e);
            throw e;
        }
    }

    /**
     * Decrypts messages using the current personal code
     */
    public String getDecryptedMessage() throws Exception {
        if (currentPersonalCode.isEmpty()) {
            return null;
        }
        return getDecryptedMessage(currentPersonalCode);
    }

    /**
     * Attempts to decrypt the most recent message using the provided personal code
     */
    public String getDecryptedMessage(String personalCode) throws Exception {
        try {
            // First try with the provided code
            String decrypted = attemptDecryption(personalCode);
            if (decrypted != null) {
                return decrypted;
            }

            // If that fails and this is the current code, try with all previously used codes
            if (personalCode.equals(currentPersonalCode)) {
                String[] previousCodes = getUsedPersonalCodes();
                for (String code : previousCodes) {
                    if (!code.equals(currentPersonalCode)) {
                        decrypted = attemptDecryption(code);
                        if (decrypted != null) {
                            // We found a match! Update the encryption to use the current code
                            saveEncryptedMessage(decrypted);
                            return decrypted;
                        }
                    }
                }
            }

            return null;
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error in getDecryptedMessage", e);
            throw e;
        }
    }

    /**
     * Attempt to decrypt the most recent message with the given personal code
     */
    private String attemptDecryption(String personalCode) {
        try {
            String keyAlias = generateKeyAlias(personalCode);

            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(keyAlias)) {
                return null;
            }

            // Get the most recent message
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> allEntries = prefs.getAll();

            if (allEntries.isEmpty()) {
                return null;
            }

            // Find the most recent message key (highest timestamp)
            String latestKey = null;
            long latestTimestamp = 0;

            for (String key : allEntries.keySet()) {
                if (key.startsWith("message_")) {
                    try {
                        long timestamp = Long.parseLong(key.substring(8));
                        if (timestamp > latestTimestamp) {
                            latestTimestamp = timestamp;
                            latestKey = key;
                        }
                    } catch (NumberFormatException e) {
                        // Skip keys that don't have valid timestamps
                    }
                }
            }

            if (latestKey == null) {
                return null;
            }

            String encryptedString = prefs.getString(latestKey, null);
            if (encryptedString == null) {
                return null;
            }

            byte[] combined = Base64.decode(encryptedString, Base64.DEFAULT);

            SecretKey secretKey = (SecretKey) keyStore.getKey(keyAlias, null);
            if (secretKey == null) {
                return null;
            }

            // Split IV and encrypted data
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encryptedData = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encryptedData, 0, encryptedData.length);

            // Decrypt
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedData = cipher.doFinal(encryptedData);
            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error in attemptDecryption", e);
            return null;
        }
    }

    /**
     * Updates the user's personal code
     *
     * @param newCode The new personal code to set
     * @return true if the update was successful, false otherwise
     */
    public boolean updatePersonalCode(String newCode) {
        if (newCode == null || newCode.isEmpty()) {
            return false;
        }

        try {
            // Remember the old code if it was set
            if (!currentPersonalCode.isEmpty()) {
                addUsedPersonalCode(currentPersonalCode);
            }

            // Update the current code
            currentPersonalCode = newCode;

            SharedPreferences prefs = context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PERSONAL_CODE_KEY, newCode);

            return editor.commit();
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error updating personal code", e);
            return false;
        }
    }

    /**
     * Store a personal code that was used for encryption
     */
    public void addUsedPersonalCode(String code) {
        try {
            if (code.isEmpty()) return;

            SharedPreferences prefs = context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
            String existingCodes = prefs.getString("usedPersonalCodes", "");

            // Check if the code is already in the list
            String[] codes = existingCodes.split(",");
            for (String existingCode : codes) {
                if (existingCode.equals(code)) {
                    return; // Already exists
                }
            }

            // Add the new code
            String updatedCodes = existingCodes.isEmpty() ? code : existingCodes + "," + code;

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("usedPersonalCodes", updatedCodes);
            editor.apply();
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error adding used personal code", e);
        }
    }

    /**
     * Get all personal codes that have been used
     */
    private String[] getUsedPersonalCodes() {
        SharedPreferences prefs = context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        String codesString = prefs.getString("usedPersonalCodes", "");

        if (codesString.isEmpty()) {
            return new String[0];
        }

        return codesString.split(",");
    }

    /**
     * Generate a key alias based on the personal code
     */
    private String generateKeyAlias(String personalCode) {
        try {
            // Create a hash of the personal code to use as key alias
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(personalCode.getBytes(StandardCharsets.UTF_8));

            // Convert to a hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            // Return the key alias prefixed to identify its purpose
            return "encryption_key_" + hexString.toString().substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            Log.e("AndroidKeystorePlugin", "Hash algorithm not found", e);
            // Fallback
            return "encryption_key_" + personalCode;
        }
    }

    /**
     * Delete all encrypted messages and keys
     */
    public boolean deleteAllMessages() {
        try {
            // Clear all encrypted messages
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            boolean cleared = editor.commit();

            // Try to delete all keys in the keystore
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            return cleared;
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error deleting messages", e);
            return false;
        }
    }

    /**
     * Delete a specific key
     */
    public boolean deleteKey(String key) {
        try {
            String userSpecificKey = generateKeyAlias(currentPersonalCode);

            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (keyStore.containsAlias(userSpecificKey)) {
                keyStore.deleteEntry(userSpecificKey);

                // Also remove from SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.remove(userSpecificKey);
                return editor.commit();
            }
            return true;
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "Error deleting key", e);
            return false;
        }
    }
}