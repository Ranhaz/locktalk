package com.example.locktalk_01.activities;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
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
        Log.d(TAG, "Opening WhatsApp with improved detection");

        // First check if WhatsApp is installed
        if (isWhatsAppInstalled()) {
            // If installed, try to open it
            if (tryOpenWhatsApp()) {
                return;
            }
        }

        // If not installed or can't open, show dialog
        showWhatsAppNotFoundDialog();
    }

    private boolean isWhatsAppInstalled() {
        PackageManager pm = getPackageManager();
        
        // Method 1: Check for specific package names
        String[] packages = {
                "com.whatsapp",          // Regular WhatsApp
                "com.whatsapp.w4b",      // WhatsApp Business
                "com.gbwhatsapp",        // GB WhatsApp
                "com.whatsapp.plus",     // WhatsApp Plus
                "com.yowhatsapp",        // YoWhatsApp
                "com.fmwhatsapp",        // FM WhatsApp
                "io.fouad.whatsapp",     // Fouad WhatsApp
                "com.whatsapp.gold"      // WhatsApp Gold
        };

        for (String packageName : packages) {
            try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
                Log.d(TAG, "WhatsApp package found: " + packageName);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "Package not found: " + packageName);
            }
        }

        // Method 2: Check for WhatsApp URI scheme
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("whatsapp://"));
            if (intent.resolveActivity(pm) != null) {
                Log.d(TAG, "WhatsApp found via URI scheme");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking WhatsApp URI: " + e.getMessage());
        }

        // Method 3: Check for WhatsApp in installed applications
        try {
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo packageInfo : packages) {
                if (packageInfo.packageName.contains("whatsapp")) {
                    Log.d(TAG, "WhatsApp found in installed apps: " + packageInfo.packageName);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking installed apps: " + e.getMessage());
        }

        return false;
    }

    private boolean tryOpenWhatsApp() {
        Log.d(TAG, "Trying to open WhatsApp");

        // Method 1: Try direct package launch
        if (tryDirectPackageOpen()) {
            return true;
        }

        // Method 2: Try URI scheme
        if (tryURIScheme()) {
            return true;
        }

        // Method 3: Try share intent
        if (tryShareIntent()) {
            return true;
        }

        // Method 4: Try main activity intent
        return tryMainActivityIntent();
    }

    private boolean tryDirectPackageOpen() {
        String[] packages = {
                "com.whatsapp",          // Regular WhatsApp
                "com.whatsapp.w4b",      // WhatsApp Business
                "com.gbwhatsapp",        // GB WhatsApp
                "com.whatsapp.plus",     // WhatsApp Plus
                "com.yowhatsapp",        // YoWhatsApp
                "com.fmwhatsapp",        // FM WhatsApp
                "io.fouad.whatsapp",     // Fouad WhatsApp
                "com.whatsapp.gold"      // WhatsApp Gold
        };

        PackageManager pm = getPackageManager();

        for (String packageName : packages) {
            try {
                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    Log.d(TAG, "Successfully opened app: " + packageName);
                    Toast.makeText(this, "פותח " + (packageName.contains("w4b") ? "WhatsApp Business" : "WhatsApp"), Toast.LENGTH_SHORT).show();
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error opening package: " + packageName, e);
            }
        }

        return false;
    }

    private boolean tryMainActivityIntent() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            
            PackageManager pm = getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
            
            for (ResolveInfo info : activities) {
                if (info.activityInfo.packageName.contains("whatsapp")) {
                    Intent launchIntent = new Intent(Intent.ACTION_MAIN);
                    launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    launchIntent.setPackage(info.activityInfo.packageName);
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    Log.d(TAG, "Opened WhatsApp using main activity: " + info.activityInfo.packageName);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error with main activity method: " + e.getMessage());
        }
        
        return false;
    }

    private boolean tryURIScheme() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("whatsapp://"));
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Log.d(TAG, "Opened WhatsApp with URI scheme");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error with URI method: " + e.getMessage());
        }

        return false;
    }

    private boolean tryShareIntent() {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");

            PackageManager pm = getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(shareIntent, 0);

            for (ResolveInfo info : activities) {
                if (info.activityInfo.packageName.contains("whatsapp")) {
                    Intent whatsappIntent = new Intent(Intent.ACTION_SEND);
                    whatsappIntent.setType("text/plain");
                    whatsappIntent.setPackage(info.activityInfo.packageName);
                    whatsappIntent.putExtra(Intent.EXTRA_TEXT, "");
                    startActivity(whatsappIntent);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error with share intent method: " + e.getMessage());
        }

        return false;
    }

    private void showWhatsAppNotFoundDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("WhatsApp לא נמצא");
        builder.setMessage("לא נמצאה התקנה של WhatsApp במכשיר. האם תרצה להתקין את WhatsApp?");
        builder.setPositiveButton("התקן", (dialog, which) -> openWhatsAppPlayStore());
        builder.setNegativeButton("ביטול", null);
        builder.show();
    }

    private void openWhatsAppPlayStore() {
        try {
            Log.d(TAG, "Opening WhatsApp on Play Store");
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
