// File: ImagePickerProxyActivity.java
package com.example.locktalk_01.activities;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;
import com.example.locktalk_01.utils.EncryptionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ImagePickerProxyActivity extends Activity {
    private static final int REQ_IMG = com.example.locktalk_01.managers.OverlayManager.REQ_IMG;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // מיד מפעילים בורר תמונות
        Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pick.setType("image/*");
        startActivityForResult(pick, REQ_IMG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                Bitmap orig = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);

                // 1) Base64
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                orig.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

                // 2) "הצפנה" ו-save
                Bitmap encrypted = EncryptionUtils.applyEncryptionFilter(orig);
                Uri savedUri = EncryptionUtils.saveBitmap(this, encrypted);

                // 3) שיגור ה־Share Intent מתוך ה-Activity
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, savedUri)
                        // אם היה Package אחר אחרון, קחו אותו...
                        .setPackage(MyAccessibilityService.getInstance().getLastWhatsAppPackage())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                startActivity(share);

            } catch (IOException e) {
                Log.e("ImagePickerProxy", "failed to pick/encrypt", e);
            }
        }
        // מסיימים כדי לחזור ישר ל־WhatsApp
        finish();
    }
}
