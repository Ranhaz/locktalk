// UriPathUtils.java

package com.example.locktalk_01.utils;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class UriPathUtils {
    /**
     * אם ה-URI הוא content://, ניתן לקרוא ממנו InputStream
     * ולשמור לטמפ או לבצע decode ישירות. בדוגמה זו נפיק מסלול קובץ זמני ב־cache.
     */
    public static String getPathFromUri(Context ctx, Uri uri) throws Exception {
        // ננסה decode ישירות אם מדובר ב-file://
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        // אחרת – ניצור קובץ זמני בתיקיית ה-cache, ונכתב אליו את תוכן ה־InputStream
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new Exception("לא מצליח לפתוח InputStream מה־URI");
        }

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
    }

    /**
     * עוזר למצוא שם קובץ מה־URI (מתוך האינדקס)
     */
    private static String queryFileName(Context ctx, Uri uri) {
        String result = null;
        Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    result = cursor.getString(idx);
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }
}
