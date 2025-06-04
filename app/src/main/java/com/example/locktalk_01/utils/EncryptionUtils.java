package com.example.locktalk_01.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * מחלקה שתומכת בשמירת תמונה “לוגו” עם EXIF המכיל את ה‐Base64
 * של התמונה המקורית, ושליפתה חזרה (פענוח EXIF).
 */
public class EncryptionUtils {

    private static final String TAG = "EncryptionUtils";

    /**
     * Phase 1: שומר את הביטמאפ של ה־logo (placeholder) כ‐JPEG ל‐MediaStore,
     * וב‐EXIF (TAG_USER_COMMENT) מכניס את Base64 של ה‐Bitmap המקורי.
     *
     * @param ctx       הקונטקסט
     * @param logo      הביטמאפ של הלוגו (placeholder)
     * @param original  הביטמאפ המקורי לפני ההעלאה
     * @return          URI של הקובץ שהתבצע עליו השמירה, או null במקרה של שגיאה.
     */
    public static Uri saveBitmapWithOriginalExif(Context ctx, Bitmap logo, Bitmap original) {
        Uri imageUri = null;
        OutputStream out = null;
        ParcelFileDescriptor pfd = null;

        try {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "locktalk_enc_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // נשמור בתיקיית DCIM/LockTalkEncrypted
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LockTalkEncrypted");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) {
                Log.e(TAG, "saveBitmapWithOriginalExif: לא הצליח לקבל URI");
                return null;
            }

            // 1. כותב את ה־logo (placeholder) כ‐JPEG אל ה־URI
            out = resolver.openOutputStream(imageUri);
            if (out == null) {
                throw new IOException("openOutputStream returned null");
            }
            logo.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            closeSilently(out);
            out = null;

            // 2. כותב EXIF עם Base64 של התמונה המקורית
            byte[] originalBytes = null;
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                original.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                originalBytes = baos.toByteArray();
                closeSilently(baos);
            }
            String origB64 = Base64.encodeToString(originalBytes, Base64.NO_WRAP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android Q+ : נפתח ParcelFileDescriptor לקריאה/כתיבה
                pfd = resolver.openFileDescriptor(imageUri, "rw");
                if (pfd != null) {
                    ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, origB64);
                    exif.saveAttributes();
                    closeSilently(pfd);
                    pfd = null;
                } else {
                    Log.w(TAG, "saveBitmapWithOriginalExif: לא ניתן לפתוח ParcelFileDescriptor");
                }
            } else {
                // Android 9 ומטה: משתמשים בנתיב פיזי
                String path = getRealPathFromUri(ctx, imageUri);
                if (path != null) {
                    ExifInterface exif = new ExifInterface(path);
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, origB64);
                    exif.saveAttributes();
                } else {
                    Log.w(TAG, "saveBitmapWithOriginalExif: לא מצאנו נתיב פיזי ל‐URI");
                }
            }

            // 3. מסיימים את IS_PENDING (Android Q+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues update = new ContentValues();
                update.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(imageUri, update, null, null);
            }

            return imageUri;

        } catch (Exception e) {
            Log.e(TAG, "saveBitmapWithOriginalExif: שגיאה בשמירה", e);
            if (imageUri != null) {
                // ננסה למחוק אם כבר נוצר חלקית
                try {
                    ctx.getContentResolver().delete(imageUri, null, null);
                } catch (Exception ignored) { }
            }
            return null;
        } finally {
            closeSilently(out);
            closeSilently(pfd);
        }
    }

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
     * Phase 3: ממיר מ‐Base64 (מופק מ־readExifBase64) ל‐Bitmap כדי שנוכל להציג אותו.
     */
    public static Bitmap decodeBase64ToBitmap(String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            Log.e(TAG, "decodeBase64ToBitmap: שגיאה בפענוח", e);
            return null;
        }
    }

    /**
     * פעולה נוחה: קוראת ישירות את ה־EXIF של ה־URI, מפענחת את ה־Base64 ויוצרת Bitmap.
     * אם אין תג EXIF, או שהפענוח נכשל, מחזירה null.
     *
     * @param ctx   הקונטקסט
     * @param uri   URI של התמונה שהעלינו עם EXIF
     * @return      Bitmap המקורי שהיינו רוצים לשחזר, או null
     */
    public static Bitmap loadOriginalBitmapFromExif(Context ctx, Uri uri) {
        String b64 = readExifBase64(ctx, uri);
        if (b64 != null) {
            return decodeBase64ToBitmap(b64);
        }
        return null;
    }

    /**
     * Phase 0: החזרת נתיב למערכת הקבצים מתוך URI (לשימוש ב־Android מתחת ל‐Q).
     * אם השאילתא נכשלת או אין נתיב, מחזיר null.
     */
    private static String getRealPathFromUri(Context ctx, Uri uri) {
        String path = null;
        String[] proj = { MediaStore.Images.Media.DATA };
        android.database.Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(uri, proj, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                path = cursor.getString(idx);
            }
        } catch (Exception e) {
            Log.e(TAG, "getRealPathFromUri: שגיאה בקבלת נתיב", e);
        } finally {
            closeSilently(cursor);
        }
        return path;
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
