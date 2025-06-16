package com.example.locktalk_01.utils;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.io.IOException;
import java.io.OutputStream;
public class ImageStorageHelper {

    // utils/ImageStorageHelper.java
    public static Bitmap addTextToBitmap(Bitmap src, String label) {
        Bitmap result = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(src.getWidth() / 7f);
        float x = src.getWidth() / 2f;
        float y = src.getHeight() - 40;
        // רקע לבן לאותיות
        Paint bg = new Paint();
        bg.setColor(Color.WHITE);
        bg.setStyle(Paint.Style.FILL);
        float padding = 12;
        Rect bounds = new Rect();
        paint.getTextBounds(label, 0, label.length(), bounds);
        canvas.drawRect(x - bounds.width()/2 - padding, y - bounds.height() - padding, x + bounds.width()/2 + padding, y + padding, bg);
        // טקסט
        canvas.drawText(label, x, y, paint);
        return result;
    }



    public static Bitmap generateQR(String text, int width, int height) throws WriterException {
        BitMatrix matrix = new MultiFormatWriter().encode(
                text, BarcodeFormat.QR_CODE, width, height);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    // קריאת QR מתמונה
    public static String readQrFromBitmap(Bitmap bmp) {
        try {
            int width = bmp.getWidth(), height = bmp.getHeight();
            int[] pixels = new int[width * height];
            bmp.getPixels(pixels, 0, width, 0, 0, width, height);

            com.google.zxing.RGBLuminanceSource source = new com.google.zxing.RGBLuminanceSource(width, height, pixels);
            com.google.zxing.BinaryBitmap binBitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
            com.google.zxing.Result result = new com.google.zxing.qrcode.QRCodeReader().decode(binBitmap);
            return result.getText();
        } catch (Exception e) { return null; }
    }
}