package com.example.locktalk_01;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public class AndroidKeystorePlugin {

    /* ─────────────────────────  CONSTANTS  ───────────────────────── */
    public static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH   = 12;
    private static final int    GCM_TAG_LENGTH  = 128;

    public static final String PREFS_NAME       = "EncryptedMessages";   // SharedPreferences
    public static final String USER_CREDENTIALS = "UserCredentials";
    public static final String PERSONAL_CODE_KEY = "personalCode";

    /* ────────────────────────  STATE FIELDS  ─────────────────────── */
    private final Context context;
    private String currentPersonalCode;   // טעון מתוך SharedPreferences

    /* ──────────────────────────  CTOR  ───────────────────────────── */
    public AndroidKeystorePlugin(Context ctx) {
        this.context = ctx.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE);
        this.currentPersonalCode = prefs.getString(PERSONAL_CODE_KEY, "");
    }

    /* ───────────────────  HIGH‑LEVEL PUBLIC API  ─────────────────── */

    /**
     * Encrypts <code>plainText</code> with the **current** personal‑code,
     * stores it under a unique key and returns the cipher‑text (Base64).
     */
    public String encryptAndSave(String plainText) throws Exception {
        if (currentPersonalCode.isEmpty()) {
            throw new IllegalStateException("Personal code is not set");
        }
        return encryptAndSave(currentPersonalCode, plainText);
    }

    /** Encrypt & save using an **explicit** personal‑code (backward compatibility). */
    public String encryptAndSave(String personalCode, String plainText) throws Exception {
        if (personalCode == null || personalCode.isEmpty()) {
            throw new IllegalArgumentException("personalCode is empty");
        }
        // If the code differs from the current one, remember it for later decryption attempts
        if (!personalCode.equals(currentPersonalCode)) {
            addUsedPersonalCode(personalCode);
        }

        String cipher = encryptMessage(personalCode, plainText);
        saveCipherText(generateMessageKey(), cipher);
        return cipher;
    }

    /** Returns the latest decrypted message with the current personal code (or previous codes). */
    public String decryptLatest() throws Exception {
        return getDecryptedMessage(currentPersonalCode);
    }

    /** Try to decrypt the latest message with <code>personalCode</code>. */
    public String getDecryptedMessage(String personalCode) throws Exception {
        // 1️⃣  Attempt direct decryption with the supplied code
        String result = attemptDecryption(personalCode);
        if (result != null) return result;

        // 2️⃣  If failed AND we used the **current** code, iterate over older codes
        if (personalCode.equals(currentPersonalCode)) {
            for (String code : getUsedPersonalCodes()) {
                if (code.isEmpty() || code.equals(currentPersonalCode)) continue;
                result = attemptDecryption(code);
                if (result != null) {
                    // migrate cipher‑text so that it is now encrypted with the current code
                    encryptAndSave(result);
                    return result;
                }
            }
        }
        return null; // nothing matched
    }

    /* ───────────────────── PERSONAL‑CODE MANAGEMENT ────────────────── */

    public boolean updatePersonalCode(String newCode) {
        if (newCode == null || newCode.isEmpty()) return false;
        try {
            if (!currentPersonalCode.isEmpty()) {
                addUsedPersonalCode(currentPersonalCode);
            }
            currentPersonalCode = newCode;
            SharedPreferences.Editor ed = prefsUser().edit();
            ed.putString(PERSONAL_CODE_KEY, newCode);
            return ed.commit();
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "updatePersonalCode failed", e);
            return false;
        }
    }

    /* ───────────────────────────  CLIPBOARD  ───────────────────────── */
    //  (החלק הזה נשאר אצל MessageEncryptionHelper)

    /* ───────────────────  INTERNAL  HELPERS  ─────────────────────── */

    /** Low‑level encryption with AES‑GCM. Returns Base64 cipher‑text. */
    private String encryptMessage(String personalCode, String plainText) throws Exception {
        String alias = generateKeyAlias(personalCode);

        // Generate key if not exists
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(alias)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }

        SecretKey sk = (SecretKey) ks.getKey(alias, null);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, sk);

        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    /** Store cipher‑text in SharedPreferences under the given key. */
    private void saveCipherText(String key, String cipherText) {
        SharedPreferences.Editor ed = prefsCipher().edit();
        ed.putString(key, cipherText);
        ed.apply();
    }

    /** Attempt to decrypt the most recent message with <code>personalCode</code>. */
    private String attemptDecryption(String personalCode) {
        try {
            String alias = generateKeyAlias(personalCode);
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(alias)) return null;

            // Load latest cipher‑text
            Map<String, ?> all = prefsCipher().getAll();
            long latestTs = 0L; String latestKey = null;
            for (String k : all.keySet()) {
                if (k.startsWith("message_")) {
                    try {
                        long ts = Long.parseLong(k.substring(8));
                        if (ts > latestTs) { latestTs = ts; latestKey = k; }
                    } catch (NumberFormatException ignore) {}
                }
            }
            if (latestKey == null) return null;
            String b64 = prefsCipher().getString(latestKey, null);
            if (b64 == null) return null;

            byte[] combined = Base64.decode(b64, Base64.NO_WRAP);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] enc = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, enc, 0, enc.length);

            SecretKey sk = (SecretKey) ks.getKey(alias, null);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plain = cipher.doFinal(enc);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "attemptDecryption failed", e);
            return null;
        }
    }

    /* ──────────────────────  UTILITY METHODS  ─────────────────────── */

    private SharedPreferences prefsCipher() { return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
    private SharedPreferences prefsUser()   { return context.getSharedPreferences(USER_CREDENTIALS, Context.MODE_PRIVATE); }

    private String[] getUsedPersonalCodes() {
        String csv = prefsUser().getString("usedPersonalCodes", "");
        return csv.isEmpty() ? new String[0] : csv.split(",");
    }

    public void addUsedPersonalCode(String code) {
        if (code == null || code.isEmpty()) return;
        String[] arr = getUsedPersonalCodes();
        for (String s : arr) if (s.equals(code)) return; // already exists
        String csv = String.join(",", arr) + (arr.length == 0 ? "" : ",") + code;
        prefsUser().edit().putString("usedPersonalCodes", csv).apply();
    }

    private String generateMessageKey() { return "message_" + System.currentTimeMillis(); }

    private String generateKeyAlias(String personalCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(personalCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return "enc_key_" + sb.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            Log.e("AndroidKeystorePlugin", "SHA-256 unavailable", e);
            return "enc_key_" + personalCode.hashCode();
        }
    }

    /* ───────────────────  CLEAN‑UP / HOUSE‑KEEPING  ────────────────── */

    /** Deletes **all** cipher‑texts and keystore entries generated by this plugin. */
    public boolean deleteAllMessages() {
        prefsCipher().edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                if (a.startsWith("enc_key_")) ks.deleteEntry(a);
            }
            return true;
        } catch (Exception e) {
            Log.e("AndroidKeystorePlugin", "deleteAllMessages failed", e);
            return false;
        }
    }
}