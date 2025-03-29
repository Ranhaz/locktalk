package com.example.locktalk_01.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.locktalk_01.R;
import com.example.locktalk_01.utils.SecurityUtils;
import com.example.locktalk_01.utils.SharedPrefsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ContactSelectionActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_READ_CONTACTS = 100;
    private ListView contactsListView;
    private SharedPrefsManager prefsManager;
    private String selectedPhoneNumber = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_selection);

        prefsManager = new SharedPrefsManager(this);
        contactsListView = findViewById(R.id.contactsListView);

        requestContactPermission();
    }

    private void requestContactPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    PERMISSIONS_REQUEST_READ_CONTACTS
            );
        } else {
            loadContacts();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_READ_CONTACTS) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadContacts();
            } else {
                Toast.makeText(this,
                        "נדרש אישור לגישה לאנשי קשר",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadContacts() {
        List<Map<String, String>> contactsList = new ArrayList<>();
        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        );
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        )
                );
                String phoneNumber = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                        )
                );

                Map<String, String> contact = new HashMap<>();
                contact.put("name", name);
                contact.put("phone", phoneNumber);
                contactsList.add(contact);
            }
            cursor.close();
        }

        SimpleAdapter adapter = new SimpleAdapter(
                this,
                contactsList,
                android.R.layout.simple_list_item_2,
                new String[]{"name", "phone"},
                new int[]{android.R.id.text1, android.R.id.text2}
        );
        contactsListView.setAdapter(adapter);

        contactsListView.setOnItemClickListener((parent, view, position, id) -> {
            Map<String, String> contact = contactsList.get(position);
            String phoneNumber = contact.get("phone");
            selectedPhoneNumber = phoneNumber;
            generateAndStoreKey(phoneNumber);
        });
    }

    private void generateAndStoreKey(String phoneNumber) {
        // יוצרים מפתח אקראי
        String encryptionKey = SecurityUtils.generateEncryptionKey();
        // שומרים אותו ב-Keystore תחת alias=phoneNumber
        prefsManager.storeKeyInKeystore(phoneNumber, encryptionKey);
        selectedPhoneNumber = phoneNumber;

        // הוספת דיאלוג לבחירה אם לשלוח טקסט או תמונה
        new AlertDialog.Builder(this)
                .setTitle("מפתח הצפנה נוצר")
                .setMessage("מפתח ההצפנה נשמר.\nבחר אופן שליחה:")
                .setPositiveButton("שליחת טקסט", (dialog, which) -> {
                    openWhatsApp(phoneNumber); // נפתח אוטומטית
                })
                .setNegativeButton("שליחת תמונה", (dialog, which) -> {
                    // מעבר ל-SendImageActivity
                    Intent intent = new Intent(ContactSelectionActivity.this,
                            SendImageActivity.class);
                    intent.putExtra("CONTACT_NUMBER", phoneNumber);
                    startActivity(intent);
                })
                .show();
    }

    /**
     * פתיחת הצ'אט ב-WhatsApp אוטומטית בעזרת whatsapp://send?phone=...
     */
    private void openWhatsApp(String phoneNumber) {
        String cleanedNumber = fixPhoneNumber(phoneNumber);

        // מחרוזת whatsapp://
        String url = "whatsapp://send?phone=" + cleanedNumber;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "נראה ש-WhatsApp לא מותקן", Toast.LENGTH_SHORT).show();
            openPlayStoreForWhatsApp();
        }
    }

    /**
     * ניקוי/התאמת מספר טלפון. הוספנו +972 אם לא מתחיל ב+.
     */
    private String fixPhoneNumber(String phoneNumber) {
        String formatted = phoneNumber.replaceAll("[^\\d+]", "");
        if (!formatted.startsWith("+")) {
            if (formatted.startsWith("0")) {
                formatted = "+972" + formatted.substring(1);
            } else {
                formatted = "+972" + formatted;
            }
        }
        return formatted;
    }

    private void openPlayStoreForWhatsApp() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.whatsapp")));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")));
        }
    }
}
