package com.example.locktalk_01.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;
import com.example.locktalk_01.utils.SharedPrefsManager;

public class SplashActivity extends AppCompatActivity {
    private SharedPrefsManager prefsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        prefsManager = new SharedPrefsManager(this);

        new Handler().postDelayed(() -> {
            Intent nextIntent;
            if (!isAccessibilityServiceEnabled()) {
                nextIntent = new Intent(this, AccessibilityPermissionActivity.class);
            } else if (!prefsManager.isLoggedIn()) {
                nextIntent = new Intent(this, LoginActivity.class);
            } else {
                nextIntent = new Intent(this, ContactSelectionActivity.class);
            }

            startActivity(nextIntent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 2500);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String service = getPackageName() + "/com.example.locktalk_01.services.MessageAccessibilityService";
        return enabledServices != null && enabledServices.contains(service);
    }
}
