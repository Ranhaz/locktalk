package com.example.locktalk_01.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

public class EncryptionUtils {
    private static final String TAG = "EncryptionUtils";

    /** יוצר אפקט פיקסלים עם כיתוב באמצע */
    public static Bitmap applyEncryptionFilter(Bitmap orig) {
        if (orig == null) return null;
        int w = orig.getWidth(), h = orig.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setAntiAlias(false);

        int bs = Math.max(w, h) / 30;
        Random rnd = new Random();
        for (int x = 0; x < w; x += bs) {
            for (int y = 0; y < h; y += bs) {
                int px = Math.min(x + bs/2, w-1);
                int py = Math.min(y + bs/2, h-1);
                int col = orig.getPixel(px, py);
                int r = clamp(Color.red(col)   + rnd.nextInt(61) - 30);
                int g = clamp(Color.green(col) + rnd.nextInt(61) - 30);
                int b = clamp(Color.blue(col)  + rnd.nextInt(61) - 30);
                p.setColor(Color.rgb(r, g, b));
                c.drawRect(x, y, Math.min(x+bs,w), Math.min(y+bs,h), p);
            }
        }

        Paint tp = new Paint();
        tp.setColor(Color.WHITE);
        tp.setTextSize(w / 20f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setShadowLayer(5, 2, 2, Color.BLACK);
        c.drawText("תמונה מוצפנת", w/2f, h/2f, tp);

        return bmp;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * שומר את ה-Bitmap ב-cache/images ומחזיר URI בטוח דרך FileProvider.
     */
    public static Uri saveBitmap(Context ctx, Bitmap bmp) throws IOException {
        // 1) יצירת תיקיית cache/images
        File imagesDir = new File(ctx.getCacheDir(), "images");
        if (!imagesDir.exists()) imagesDir.mkdirs();

        // 2) שם קובץ
        String filename = "enc_" + System.currentTimeMillis() + ".jpg";
        File outFile = new File(imagesDir, filename);

        // 3) כתיבה לדיסק
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
        }

        // 4) יצירת URI ל-FileProvider
        Uri contentUri = FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                outFile
        );
        Log.d(TAG, "saved image uri=" + contentUri);
        return contentUri;
    }
}
