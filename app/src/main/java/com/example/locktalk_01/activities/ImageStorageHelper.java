package com.example.locktalk_01.activities;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

public class ImageStorageHelper {
    private static final String TAG = "ImageStorageHelper";

    public static Uri savePlaceholderToMediaStore(Context ctx, Bitmap placeholder) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "locktalk_placeholder_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LockTalkEncrypted");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri imageUri = ctx.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) {
                Log.e(TAG, "savePlaceholderToMediaStore: לא הצליח לקבל URI");
                return null;
            }

            // כותב את ה-placeholder כ-JPEG
            try (OutputStream out = ctx.getContentResolver().openOutputStream(imageUri)) {
                if (out == null) {
                    throw new IOException("openOutputStream returned null");
                }
                placeholder.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
            }

            // מסמן את התמונה כזמינה (Android Q+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues update = new ContentValues();
                update.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(imageUri, update, null, null);
            }

            return imageUri;
        } catch (Exception e) {
            Log.e(TAG, "savePlaceholderToMediaStore: שגיאה בשמירה", e);
            return null;
        }
    }
}
