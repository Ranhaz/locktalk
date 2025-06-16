package com.example.locktalk_01.utils;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class UriPathUtils {

    /**
     * מחזיר נתיב קובץ מה-URI שניתן.
     * - אם זה file:// – מחזיר מיידית את הנתיב.
     * - אם זה content:// – יוצר קובץ זמני ב-cache ומחזיר את הנתיב הזמני.
     */
    public static String getPath(Context ctx, Uri uri) {
        if (uri == null) {
            // הדפסה ללוג (או תפעיל אנליטיקה)
            android.util.Log.e("UriPathUtils", "getPath called with null Uri");
            return null;
        }
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            String fileName = queryFileName(ctx, uri);
            if (fileName == null) {
                fileName = "temp_img_" + System.currentTimeMillis() + ".jpg";
            }
            File outFile = new File(ctx.getCacheDir(), fileName);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            is.close();
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * עוזר למצוא שם קובץ מה־URI (מתוך האינדקס).
     * אם אין, מחזיר null.
     */
    private static String queryFileName(Context ctx, Uri uri) {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    result = cursor.getString(idx);
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    /**
     * שמירת נתיב תמונה מקורית ומזהה ב-SharedPreferences.
     */
    public static void saveOriginalImagePath(Context ctx, String uniqueId, String path, String logoUri) {
        ctx.getSharedPreferences("LockTalkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("origPath_for_" + uniqueId, path)
                .putString("logoUri_for_" + uniqueId, logoUri)
                .apply();
    }

    /**
     * שליפת נתיב קובץ המקור לפי מזהה.
     */
    public static String getOriginalPathById(Context ctx, String uniqueId) {
        return ctx.getSharedPreferences("LockTalkPrefs", Context.MODE_PRIVATE)
                .getString("origPath_for_" + uniqueId, null);
    }
}
