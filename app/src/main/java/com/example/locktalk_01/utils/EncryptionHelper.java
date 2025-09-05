package com.example.locktalk_01.utils;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import android.util.Base64;

public class EncryptionHelper {
    private static final String TAG = "EncryptionHelper";
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;     // גודל IV של GCM (לפי התקן)
    private static final int GCM_TAG_LENGTH = 128;   // ביט

    // הפקת מפתח הצפנה משני מספרי טלפון (בצורה דטרמיניסטית)
    public static SecretKey deriveChatKey(String userPhone, String peerPhone) throws Exception {
        // סדר לפי השוואה כדי למנוע תלות בסדר.
        String k1 = normalizePhone(userPhone);
        String k2 = normalizePhone(peerPhone);
        String keySeed = k1.compareTo(k2) <= 0 ? k1 + "-" + k2 : k2 + "-" + k1;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(keySeed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    public static String generateImageKey(Bitmap bmp) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 98, baos);
            byte[] bytes = baos.toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString(); // fallback
        }
    }

    // נירמול מספר טלפון (המרה לפורמט אחיד: +972...)
    public static String normalizePhone(String phone) {
        if (phone == null) return "";
        String normalized = phone.trim().replaceAll("\\s+", "");
        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2);
        }
        if (!normalized.startsWith("+")) {
            if (normalized.startsWith("972")) {
                normalized = "+" + normalized;
            } else if (normalized.startsWith("05") || normalized.startsWith("02") || normalized.startsWith("03")) {
                normalized = "+972" + normalized.substring(1);
            }
        }
        // מחיקת תווים חריגים
        normalized = normalized.replaceAll("[^\\d+]", "");
        return normalized;
    }

    // הצפנת טקסט (Base64) ע"פ שני טלפונים
    public static String encryptToString(String plainText, String userPhone, String peerPhone) {
        if (plainText == null) return null;
        try {
            SecretKey key = deriveChatKey(userPhone, peerPhone);
            byte[] encrypted = encrypt(plainText.getBytes(StandardCharsets.UTF_8), key);
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encryptToString failed", e);
            return null;
        }
    }

    // פענוח טקסט (Base64) ע"פ שני טלפונים עם לוגים מדויקים
    public static String decryptFromString(String cipherText, String userPhone, String peerPhone) {
        if (cipherText == null || cipherText.trim().length() < (GCM_IV_LENGTH + 8)) {
            Log.e(TAG, "decryptFromString: cipherText too short or null | cipherText=" + cipherText);
            return null;
        }
        try {
            Log.d(TAG, "decryptFromString: called | userPhone=" + userPhone + ", peerPhone=" + peerPhone + ", cipherText=" + cipherText);

            SecretKey key = deriveChatKey(userPhone, peerPhone);
            if (key == null) {
                Log.e(TAG, "decryptFromString: failed to derive key! userPhone=" + userPhone + ", peerPhone=" + peerPhone);
                return null;
            }
            Log.d(TAG, "decryptFromString: derived key=" + Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));

            byte[] data;
            try {
                data = Base64.decode(cipherText.trim(), Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "decryptFromString: Base64 decode failed! cipherText=" + cipherText, e);
                return null;
            }
            Log.d(TAG, "decryptFromString: decoded base64 data, len=" + data.length);

            byte[] plainBytes = decrypt(data, key);

            if (plainBytes != null) {
                String result = new String(plainBytes, StandardCharsets.UTF_8);
                Log.d(TAG, "decryptFromString: decryption success! result=" + result);
                return result;
            } else {
                Log.e(TAG, "decryptFromString: decryption returned null | key=" + Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "decryptFromString: general failure", e);
            return null;
        }
    }

    // הצפנה עם מפתח מוכן מראש
    public static String encryptToString(String plainText, SecretKey key) {
        if (plainText == null || key == null) return null;
        try {
            byte[] encrypted = encrypt(plainText.getBytes(StandardCharsets.UTF_8), key);
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encryptToString (key) failed", e);
            return null;
        }
    }

    // הצפנת תמונה (או כל מערך בייטים) עם מפתח מטלפונים – ל-Firebase
    public static byte[] encryptImage(byte[] plainBytes, String userPhone, String peerPhone) throws Exception {
        if (plainBytes == null || plainBytes.length == 0) return null;
        SecretKey key = deriveChatKey(userPhone, peerPhone);
        return encrypt(plainBytes, key);
    }

    // פענוח תמונה (cipherData חייב לכלול את ה-IV בתחילתו)
    public static byte[] decryptImage(byte[] cipherData, String userPhone, String peerPhone) {
        if (cipherData == null || cipherData.length < GCM_IV_LENGTH) {
            Log.e(TAG, "decryptImage: input too short | len=" + (cipherData == null ? 0 : cipherData.length));
            return null;
        }
        try {
            SecretKey key = deriveChatKey(userPhone, peerPhone);
            Log.d(TAG, "decryptImage: derived key for userPhone=" + userPhone + ", peerPhone=" + peerPhone + ", key=" + Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
            byte[] result = decrypt(cipherData, key);
            Log.d(TAG, "decryptImage: decryption result=" + (result != null ? result.length : "null"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "decryptImage failed", e);
            return null;
        }
    }
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if(hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // הוסף ל-EncryptionHelper
    public static Bitmap scaleDownIfNeeded(Bitmap original, int maxDimPx) {
        if (original == null) return null;
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxDimPx && h <= maxDimPx) return original;
        float ratio = Math.min((float) maxDimPx / w, (float) maxDimPx / h);
        int nw = Math.round(w * ratio);
        int nh = Math.round(h * ratio);
        return Bitmap.createScaledBitmap(original, nw, nh, true);
    }


    // ליבת הצפנת בייטים עם AES-GCM ו-IV אקראי
    public static byte[] encrypt(byte[] plainBytes, SecretKey key) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom sr = new SecureRandom();
        sr.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] enc = cipher.doFinal(plainBytes);

        // השרשור: IV + ciphertext
        byte[] combined = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(enc, 0, combined, iv.length, enc.length);
        return combined;
    }

    // פענוח בייטים עם AES-GCM (IV בתחילת המערך)
    public static byte[] decrypt(byte[] cipherData, SecretKey key) throws Exception {
        if (cipherData == null || cipherData.length < GCM_IV_LENGTH)
            throw new IllegalArgumentException("cipherData too short or null");
        byte[] iv = Arrays.copyOfRange(cipherData, 0, GCM_IV_LENGTH);
        byte[] enc = Arrays.copyOfRange(cipherData, GCM_IV_LENGTH, cipherData.length);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(enc);
    }
}
