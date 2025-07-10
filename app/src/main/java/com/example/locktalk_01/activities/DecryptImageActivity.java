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
    private EditText myPhoneInput, peerPhoneInput;
    private Button pickFileBtn, decryptBtn;
    private Uri fileUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decrypt_image);

        imageView = findViewById(R.id.decryptedImageView);
        myPhoneInput = findViewById(R.id.myPhoneInput);     // EditText חדש - מספר שלך (ללא 0)
        peerPhoneInput = findViewById(R.id.peerPhoneInput); // EditText חדש - מספר של בן שיח (ללא 0)
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
            Log.d("DecryptImageActivity", "decryptImage: fileUri==null");
            return;
        }
        String myPhone = myPhoneInput.getText().toString().replaceAll("\\D", "");
        String peerPhone = peerPhoneInput.getText().toString().replaceAll("\\D", "");
        Log.d("DecryptImageActivity", "decryptImage: myPhone=" + myPhone + " peerPhone=" + peerPhone + " fileUri=" + fileUri);
        if (myPhone.length() < 7 || peerPhone.length() < 7) {
            Toast.makeText(this, "הזן את שני המספרים (ללא קידומת 0)", Toast.LENGTH_SHORT).show();
            Log.d("DecryptImageActivity", "decryptImage: invalid phone numbers");
            return;
        }
        try {
            String base64 = EncryptionUtils.readExifBase64(this, fileUri);
            Log.d("DecryptImageActivity", "decryptImage: base64 from exif = " + (base64 != null ? base64.substring(0, Math.min(30, base64.length())) + "..." : "null"));
            if (base64 == null || base64.isEmpty()) {
                Toast.makeText(this, "אין מידע מוצפן בקובץ", Toast.LENGTH_LONG).show();
                Log.e("DecryptImageActivity", "decryptImage: no base64 found in exif for fileUri=" + fileUri);
                return;
            }
            byte[] encBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            javax.crypto.SecretKey key = EncryptionHelper.deriveChatKey(myPhone, peerPhone);
            Log.d("DecryptImageActivity", "decryptImage: decrypting with key for " + myPhone + " and " + peerPhone);
            byte[] plainBytes = EncryptionHelper.decrypt(encBytes, key);
            if (plainBytes == null) {
                Toast.makeText(this, "פענוח נכשל (plainBytes==null)", Toast.LENGTH_LONG).show();
                Log.e("DecryptImageActivity", "decryptImage: plainBytes==null after decrypt");
                return;
            }
            imageView.setImageBitmap(BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length));
            Log.d("DecryptImageActivity", "decryptImage: success! setImageBitmap");
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

