package com.example.locktalk_01.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREF_NAME = "UserCredentials";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Clear logged in status for testing to ensure proper flow
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("isLoggedIn", false);
        editor.apply();

        boolean isAccessibilityEnabled = prefs.getBoolean("accessibilityEnabled", false);
        boolean isLoggedIn = prefs.getBoolean("isLoggedIn", false);

        Log.d(TAG, "Accessibility enabled: " + isAccessibilityEnabled);
        Log.d(TAG, "Is logged in: " + isLoggedIn);

        Intent intent;

        // First check accessibility
        if (!isAccessibilityEnabled) {
            intent = new Intent(this, AccessibilityActivity.class);
            Log.d(TAG, "Starting AccessibilityActivity - accessibility not enabled");
        }
        // Then check login status
        else if (!isLoggedIn) {
            intent = new Intent(this, LoginActivity.class);
            Log.d(TAG, "Starting LoginActivity - user not logged in");
        }
        // Finally, if both accessibility and login are ok, go to encryption
        else {
            intent = new Intent(this, EncryptionActivity.class);
            Log.d(TAG, "Starting EncryptionActivity - both accessibility and login are enabled");
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
    }
}