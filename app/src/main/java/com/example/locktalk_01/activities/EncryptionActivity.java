package com.example.locktalk_01.activities;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;

import java.util.ArrayList;
import java.util.List;

public class EncryptionActivity extends AppCompatActivity {
    private static final String TAG = "EncryptionActivity";
    private EditText personalCodeInput;
    private Button savePersonalCodeButton;
    private Button openWhatsAppButton;
    private Button logoutButton;
    private static final String PREF_NAME = "UserCredentials";
    private AccessibilityManager accessibilityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accessibilityManager = new AccessibilityManager(this);

        // Check if user is logged in and accessibility is enabled
        if (!accessibilityManager.isAccessibilityServiceEnabled()) {
            Log.d(TAG, "Accessibility not enabled, redirecting to AccessibilityActivity");
            Intent intent = new Intent(this, AccessibilityActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // If user is not logged in, redirect to LoginActivity
        if (!accessibilityManager.isUserLoggedIn()) {
            Log.d(TAG, "User not logged in, redirecting to LoginActivity");
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_encryption);

        personalCodeInput = findViewById(R.id.personalCodeInput);
        savePersonalCodeButton = findViewById(R.id.savePersonalCodeButton);
        openWhatsAppButton = findViewById(R.id.openWhatsAppButton);
        logoutButton = findViewById(R.id.logoutButton);

        // Load saved personal code
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedPersonalCode = prefs.getString("personalCode", "");
        personalCodeInput.setText(savedPersonalCode);

        savePersonalCodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String personalCode = personalCodeInput.getText().toString();
                if (personalCode.length() != 4 || !personalCode.matches("\\d{4}")) {
                    Toast.makeText(EncryptionActivity.this, "הקוד האישי חייב להיות 4 ספרות", Toast.LENGTH_SHORT).show();
                    return;
                }

                SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
                editor.putString("personalCode", personalCode);
                editor.apply();

                Toast.makeText(EncryptionActivity.this, "הקוד האישי נשמר בהצלחה", Toast.LENGTH_SHORT).show();
            }
        });

        openWhatsAppButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "WhatsApp button clicked");
                openWhatsApp();
            }
        });

        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Clear user credentials
                SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
                editor.clear();
                editor.apply();

                // Redirect to login activity
                Intent intent = new Intent(EncryptionActivity.this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
        });
    }

    private void openWhatsApp() {
        Log.d(TAG, "Opening WhatsApp");
        
        // Only check for official WhatsApp packages
        String[] officialPackages = {
                "com.whatsapp",          // Regular WhatsApp
                "com.whatsapp.w4b"       // WhatsApp Business
        };

        PackageManager pm = getPackageManager();
        boolean whatsAppFound = false;

        // Check for official WhatsApp packages
        for (String packageName : officialPackages) {
            try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
                whatsAppFound = true;
                break;
            } catch (PackageManager.NameNotFoundException e) {
                // Continue to next package
            }
        }

        if (whatsAppFound) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setPackage("com.whatsapp");
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening WhatsApp", e);
                showWhatsAppNotFoundDialog();
            }
        } else {
            showWhatsAppNotFoundDialog();
        }
    }

    private void showWhatsAppNotFoundDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("WhatsApp לא נמצא");
        builder.setMessage("לא נמצא WhatsApp במכשיר. האם ברצונך להתקין את WhatsApp?");
        builder.setPositiveButton("כן", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openWhatsAppPlayStore();
            }
        });
        builder.setNegativeButton("לא", null);
        builder.show();
    }

    private void openWhatsAppPlayStore() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("market://details?id=com.whatsapp"));
            startActivity(intent);
        } catch (Exception e) {
            // If Play Store not available, open browser
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp"));
            startActivity(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Double-check accessibility and login status on resume
        if (!accessibilityManager.isAccessibilityServiceEnabled()) {
            Log.d(TAG, "Accessibility not enabled on resume, redirecting");
            Intent intent = new Intent(this, AccessibilityActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else if (!accessibilityManager.isUserLoggedIn()) {
            Log.d(TAG, "User not logged in on resume, redirecting");
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }
}
