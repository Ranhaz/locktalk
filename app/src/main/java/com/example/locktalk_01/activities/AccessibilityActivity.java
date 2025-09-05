package com.example.locktalk_01.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.locktalk_01.R;

public class AccessibilityActivity extends AppCompatActivity {
    private static final String TAG = "AccessibilityActivity";
    private static final int CONTACTS_PERMISSION_REQUEST = 2001;
    private AccessibilityManager accessibilityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accessibilityManager = new AccessibilityManager(this);

        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            checkAndRequestContactsPermission();
            return;
        }

        setContentView(R.layout.activity_accessibility);

        Button enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton);
        TextView accessibilityInfoText = findViewById(R.id.accessibilityInfoText);

        enableAccessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            Toast.makeText(
                    AccessibilityActivity.this,
                    "בחר באפשרות 'שירות הצפנת הודעות' כדי להפעיל את שירות ההצפנה",
                    Toast.LENGTH_LONG
            ).show();
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);

        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            checkAndRequestContactsPermission();
        }
    }

    private void checkAndRequestContactsPermission() {
        // אם יש כבר הרשאה – נמשיך ל-Login
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            navigateToLogin();
        } else {
            // בקשת הרשאה
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,  String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CONTACTS_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // הרשאה ניתנה
                Toast.makeText(this, "הרשאת אנשי קשר אושרה!", Toast.LENGTH_SHORT).show();
                navigateToLogin();
            } else {
                // סירוב הרשאה
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("הרשאת אנשי קשר נחוצה")
                        .setMessage("המשתמש חייב לאשר גישה לאנשי קשר לצורך הצפנה/פענוח הודעות. ללא הרשאה זו לא ניתן להשתמש באפליקציה.\n\nלאשר?")
                        .setCancelable(false)
                        .setPositiveButton("נסה שוב", (d, w) -> checkAndRequestContactsPermission())
                        .setNegativeButton("סגור אפליקציה", (d, w) -> finish())
                        .show();
            }
        }
    }

    private void navigateToLogin() {
        Log.d(TAG, "Navigating to LoginActivity");
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
        );
        startActivity(intent);
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
    }
}
