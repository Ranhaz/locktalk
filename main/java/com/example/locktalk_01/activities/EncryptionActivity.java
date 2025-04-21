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
        Log.d(TAG, "Opening WhatsApp with improved detection");

        // Use both WhatsApp package detection methods and direct intent to improve success rate
        boolean whatsAppFound = false;

        // Method 1: Check if WhatsApp is installed via package manager query
        if (isWhatsAppInstalled()) {
            whatsAppFound = true;
            boolean success = tryOpenWhatsApp();

            if (!success) {
                // Fallback to direct Play Store intent if we couldn't open WhatsApp
                openWhatsAppPlayStore();
            }
        } else {
            // Method 2: Try direct intent to open WhatsApp
            try {
                Intent whatsappIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com"));
                if (whatsappIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(whatsappIntent);
                    whatsAppFound = true;
                } else {
                    Log.d(TAG, "WhatsApp intent could not be resolved");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error with direct WhatsApp intent: " + e.getMessage());
            }

            // If both methods failed, show dialog
            if (!whatsAppFound) {
                showWhatsAppNotFoundDialog();
            }
        }
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

    private boolean isWhatsAppInstalled() {
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

        // First check with isPackageInstalled method
        for (String packageName : packages) {
            try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
                Log.d(TAG, "WhatsApp package found using getPackageInfo: " + packageName);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // Package not found, continue to next
                Log.d(TAG, "Package not found: " + packageName);
            }
        }

        // Second check - look for WhatsApp intent handling ability
        Intent whatsappIntent = new Intent(Intent.ACTION_SEND);
        whatsappIntent.setType("text/plain");
        List<ResolveInfo> whatsappInfoList = pm.queryIntentActivities(whatsappIntent, PackageManager.MATCH_DEFAULT_ONLY);

        for (ResolveInfo resolveInfo : whatsappInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName.contains("whatsapp")) {
                Log.d(TAG, "WhatsApp package found using intent resolution: " + packageName);
                return true;
            }
        }

        // Third check - specifically look for WhatsApp URI handling
        try {
            Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://"));
            if (uriIntent.resolveActivity(pm) != null) {
                Log.d(TAG, "WhatsApp found via URI scheme handling");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking WhatsApp URI handling: " + e.getMessage());
        }

        return false;
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

    private boolean tryOpenWhatsApp() {
        Log.d(TAG, "Trying all methods to open WhatsApp");

        if (tryDirectPackageOpen()) return true;
        if (tryComponentNameMethod()) return true;
        if (tryURIScheme()) return true;

        // As a last resort, try to open WhatsApp using a common action
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

    private boolean tryComponentNameMethod() {
        String[] packages = {
                "com.whatsapp",
                "com.whatsapp.w4b"
        };

        PackageManager pm = getPackageManager();

        for (String packageName : packages) {
            try {
                Intent launchIntent = new Intent(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                // Find activities that can handle this intent
                List<ResolveInfo> activities = pm.queryIntentActivities(launchIntent, 0);

                for (ResolveInfo info : activities) {
                    if (info.activityInfo.packageName.equals(packageName)) {
                        ComponentName component = new ComponentName(
                                packageName,
                                info.activityInfo.name
                        );

                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.setComponent(component);

                        startActivity(intent);
                        Log.d(TAG, "Opened WhatsApp with component: " + component);
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error with component method: " + e.getMessage());
            }
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

        // Try additional URI schemes specific to WhatsApp
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("whatsapp://chat"));
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Log.d(TAG, "Opened WhatsApp with chat URI scheme");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error with chat URI method: " + e.getMessage());
        }

        return false;
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
