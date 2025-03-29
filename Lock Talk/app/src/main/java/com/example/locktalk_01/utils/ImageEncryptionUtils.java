package com.example.locktalk_01.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * קורא תמונה מ-Uri -> מצפין ב-AES/CBC/PKCS5 -> שומר בקובץ בינארי:
 * [IV (16 bytes)] + [CipherText].
 */
public class ImageEncryptionUtils {
    private static final String TAG = "ImageEncryptionUtils";

    public static File encryptImageToFile(Context context,
                                          Uri imageUri,
                                          String encryptionKey) {
        try {
            byte[] imageBytes = readBytesFromUri(context, imageUri);
            if (imageBytes == null) {
                Log.e(TAG, "Failed to read image bytes");
                return null;
            }

            // ממירים Base64 -> keyBytes
            byte[] keyBytes = Base64.decode(encryptionKey, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            // IV אקראי (16 bytes)
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

            byte[] encryptedBytes = cipher.doFinal(imageBytes);

            // יצירת קובץ זמני
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(iv);
            bos.write(encryptedBytes);
            byte[] finalData = bos.toByteArray();

            File outputDir = context.getCacheDir();
            File outputFile = File.createTempFile("enc_", ".lock", outputDir);

            FileOutputStream fos = new FileOutputStream(outputFile);
            fos.write(finalData);
            fos.close();

            return outputFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] readBytesFromUri(Context context, Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            InputStream inputStream = resolver.openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            inputStream.close();
            return bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
