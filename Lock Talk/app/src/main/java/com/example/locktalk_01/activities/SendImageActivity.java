package com.example.locktalk_01.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.locktalk_01.utils.ImageEncryptionUtils;
import com.example.locktalk_01.utils.SharedPrefsManager;
import com.example.locktalk_01.utils.WhatsAppOpener;

import java.io.File;

public class SendImageActivity extends AppCompatActivity {

    private static final int PICK_IMAGE_REQUEST = 101;
    private static final int PERMISSION_REQUEST_STORAGE = 102;

    private String contactPhoneNumber;
    private SharedPrefsManager prefsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // אין Layout – הכל מתבצע מאחורי הקלעים

        prefsManager = new SharedPrefsManager(this);
        contactPhoneNumber = getIntent().getStringExtra("CONTACT_NUMBER");

        // בודקים הרשאות, ואם קיימת – פותחים את הגלריה
        checkStoragePermission();
    }

    private String getStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            return Manifest.permission.READ_EXTERNAL_STORAGE;
        }
    }

    private void checkStoragePermission() {
        String neededPerm = getStoragePermission();
        if (ContextCompat.checkSelfPermission(this, neededPerm)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{neededPerm},
                    PERMISSION_REQUEST_STORAGE
            );
        } else {
            pickImageFromGallery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickImageFromGallery();
            } else {
                Toast.makeText(this, "לא הוענקה הרשאת גישה לתמונות", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /**
     * פתיחת הגלריה לבחירת תמונה.
     */
    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                encryptAndSendImage(imageUri);
            } else {
                finish();
            }
        } else {
            finish();
        }
    }

    private void encryptAndSendImage(Uri imageUri) {
        // שליפת מפתח הצפנה לפי איש הקשר
        String encryptionKey = prefsManager.getKeyFromKeystore(contactPhoneNumber);
        if (encryptionKey == null) {
            Toast.makeText(this, "לא נמצא מפתח עבור איש קשר זה", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // מצפינים ושומרים את התמונה כקובץ זמני
        File encryptedFile = ImageEncryptionUtils.encryptImageToFile(this, imageUri, encryptionKey);
        if (encryptedFile == null) {
            Toast.makeText(this, "שגיאה בהצפנת התמונה", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // משנים את הסיומת ל-.pdf כדי ש-WhatsApp יזהה את הקובץ כמסמך PDF
        File pdfFile = new File(encryptedFile.getParent(), encryptedFile.getName() + ".pdf");
        if (encryptedFile.renameTo(pdfFile)) {
            shareEncryptedFileWhatsApp(pdfFile);
        } else {
            shareEncryptedFileWhatsApp(encryptedFile);
        }
    }

    /**
     * שליחת הקובץ ל-WhatsApp באמצעות WhatsAppOpener.
     */
    private void shareEncryptedFileWhatsApp(File fileToShare) {
        WhatsAppOpener.shareFileToWhatsApp(this, fileToShare);
        finish();
    }
}
