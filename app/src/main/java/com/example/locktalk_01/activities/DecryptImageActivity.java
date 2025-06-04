package com.example.locktalk_01.activities;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;
import com.example.locktalk_01.utils.EncryptionHelper;
import com.example.locktalk_01.utils.EncryptionUtils;

public class DecryptImageActivity extends AppCompatActivity {
    private static final int REQ_FILE = 1111;

    private ImageView imageView;
    private EditText codeInput;
    private Button pickFileBtn, decryptBtn;
    private Uri fileUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decrypt_image);

        imageView = findViewById(R.id.decryptedImageView);
        codeInput = findViewById(R.id.decryptCodeInput);
        pickFileBtn = findViewById(R.id.pickEncryptedFileBtn);
        decryptBtn = findViewById(R.id.decryptBtn);

        pickFileBtn.setOnClickListener(v -> pickFile());
        decryptBtn.setOnClickListener(v -> decryptImage());
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && resultCode == RESULT_OK && data != null) {
            fileUri = data.getData();
            Toast.makeText(this, getFileName(fileUri), Toast.LENGTH_SHORT).show();
        }
    }

    private void decryptImage() {
        if (fileUri == null) {
            Toast.makeText(this, "בחר קובץ קודם", Toast.LENGTH_SHORT).show();
            return;
        }
        String code = codeInput.getText().toString();
        if (code.length() < 4) {
            Toast.makeText(this, "הזן קוד אישי", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // קורא את המידע המוצפן ישירות מה-EXIF ב-Uri
            String base64 = EncryptionUtils.readExifBase64(this, fileUri);
            if (base64 == null || base64.isEmpty()) {
                Toast.makeText(this, "אין מידע מוצפן בקובץ", Toast.LENGTH_LONG).show();
                return;
            }
            byte[] encBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            byte[] plainBytes = EncryptionHelper.decrypt(encBytes, code);
            imageView.setImageBitmap(BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length));
        } catch (Exception e) {
            Toast.makeText(this, "שגיאה בפיענוח", Toast.LENGTH_LONG).show();
            Log.e("DecryptImageActivity", "decrypt error", e);
        }
    }

    private String getFileName(Uri uri) {
        String name = "";
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int colIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (colIdx >= 0) {
                    name = c.getString(colIdx);
                }
            }
        } catch (Exception ignore) {}
        return name;
    }
}
