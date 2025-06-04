package com.example.locktalk_01.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ImagePickerProxyActivity extends AppCompatActivity {
    private static final int REQ_IMG = 3001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pick.setType("image/*");
        startActivityForResult(pick, REQ_IMG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_IMG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri picked = data.getData();
            try {
                // שמירת הקובץ המקורי כמו שהוא
                File dir = new File(getFilesDir(), "locktalk");
                if (!dir.exists()) dir.mkdirs();
                String filename = "orig_" + System.currentTimeMillis() + ".jpg";
                File origFile = new File(dir, filename);

                // שמירת הקובץ המקורי כולל EXIF
                try (InputStream in = getContentResolver().openInputStream(picked);
                     OutputStream out = new FileOutputStream(origFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }

                Bitmap logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.locktalk_logo);
                if (logoBitmap == null) {
                    Toast.makeText(this, "שגיאה בטעינת לוגו האפליקציה", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                // שמירת הלוגו במדיה
                Uri savedLogoUri = ImageStorageHelper.savePlaceholderToMediaStore(this, logoBitmap);
                if (savedLogoUri == null) {
                    Toast.makeText(this, "שגיאה בשמירת הלוגו", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                // שמירת מיפוי URI לנתיב קובץ
                String logoUriString = savedLogoUri.toString();
                getSharedPreferences("LockTalkPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("origPath_for_" + logoUriString, origFile.getAbsolutePath())
                        .putString("pendingImageUri", logoUriString)
                        .apply();
                Log.d("ImagePickerProxy", "Saved mapping: origPath_for_" + logoUriString + " -> " + origFile.getAbsolutePath());

                // עדכון השירות (לצורך פיענוח)
                MyAccessibilityService svc = MyAccessibilityService.getInstance();
                if (svc != null) {
                    svc.setEncryptedImageUri(logoUriString);
                }

                // שליחת הלוגו לוואטסאפ
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("image/jpeg");
                share.putExtra(Intent.EXTRA_STREAM, savedLogoUri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                startActivity(Intent.createChooser(share, "שלח תמונה בוואטסאפ"));
                Log.d("ImagePickerProxy", "Sent logoUriString=" + logoUriString);

            } catch (IOException e) {
                Log.e("ImagePickerProxy", "Failed to prepare/send image", e);
                Toast.makeText(this, "שגיאה בהכנת התמונה: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                finish();
            }
        } else {
            finish();
        }
    }

}