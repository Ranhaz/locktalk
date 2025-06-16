package com.example.locktalk_01.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

public class EncryptionHelper {
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static SecretKey getKeyFromPassword(String code) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(code.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    public static byte[] decrypt(byte[] cipherText, String code) throws Exception {
        SecretKey key = getKeyFromPassword(code);
        byte[] iv = Arrays.copyOfRange(cipherText, 0, GCM_IV_LENGTH);
        byte[] enc = Arrays.copyOfRange(cipherText, GCM_IV_LENGTH, cipherText.length);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(enc);
    }

    public static byte[] encrypt(byte[] plain, String code) throws Exception {
        SecretKey key = getKeyFromPassword(code);
        Cipher cipher = Cipher.getInstance(AES_MODE);
        byte[] iv = Arrays.copyOfRange(key.getEncoded(), 0, GCM_IV_LENGTH); // עדיף IV אקראי בגרסה ייצורית
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[GCM_IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, out, 0, GCM_IV_LENGTH);
        System.arraycopy(encrypted, 0, out, GCM_IV_LENGTH, encrypted.length);
        return out;
    }
}
