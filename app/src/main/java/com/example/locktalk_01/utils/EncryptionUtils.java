
package com.example.locktalk_01.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * מחלקה שתומכת בשמירת תמונה “לוגו” עם EXIF המכיל את ה‐Base64
 * של התמונה המקורית, ושליפתה חזרה (פענוח EXIF).
 */
public class EncryptionUtils {

    private static final String TAG = "EncryptionUtils";

    /**
     * Phase 2: קורא מתוך EXIF (TAG_USER_COMMENT) את ה‐Base64 שהכנסנו בעת השמירה,
     * ומחזיר אותו כמחרוזת. אם אין, מחזיר null.
     *
     * @param ctx   הקונטקסט
     * @param uri   URI של התמונה שהעלינו
     * @return      מחרוזת Base64 של התמונה המקורית, או null אם לא נמצא
     */
    public static String readExifBase64(Context ctx, Uri uri) {
        InputStream input = null;
        try {
            ContentResolver resolver = ctx.getContentResolver();
            input = resolver.openInputStream(uri);
            if (input == null) return null;

            ExifInterface exif = new ExifInterface(input);
            String userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
            closeSilently(input);

            if (userComment != null && !userComment.trim().isEmpty()) {
                return userComment.trim();
            }
        } catch (IOException e) {
            Log.e(TAG, "readExifBase64: שגיאה בקריאה מ־EXIF", e);
        } finally {
            closeSilently(input);
        }
        return null;
    }

    /**
     * סוגר כל משאב שהוא Closeable (OutputStream, InputStream, Cursor, ParcelFileDescriptor וכו').
     */
    private static void closeSilently(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) { }
        }
    }
}