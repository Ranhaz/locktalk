package com.example.locktalk_01.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;


public class AccessibilityActivity extends AppCompatActivity {
    private static final String TAG = "AccessibilityActivity";
    private Button enableAccessibilityButton;
    private TextView accessibilityInfoText;
    private boolean isNavigatingToSettings = false;
    private AccessibilityManager accessibilityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accessibilityManager = new AccessibilityManager(this);

        // For testing - clear login status to ensure proper flow
        accessibilityManager.clearLoginStatus();

        // Check if accessibility is already enabled
        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            Log.d(TAG, "Accessibility already enabled, proceeding to LoginActivity");
            navigateToLogin();
            return;
        }

        setContentView(R.layout.activity_accessibility);

        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton);
        accessibilityInfoText = findViewById(R.id.accessibilityInfoText);

        enableAccessibilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isNavigatingToSettings = true;
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                Toast.makeText(AccessibilityActivity.this,
                        "בחר באפשרות 'שירות הצפנת הודעות' כדי להפעיל את שירות ההצפנה", Toast.LENGTH_LONG).show();
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);

        if (isNavigatingToSettings) {
            isNavigatingToSettings = false;
            checkAccessibilityAndProceed();
        }
    }

    private void checkAccessibilityAndProceed() {
        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            navigateToLogin();
        } else {
            Toast.makeText(this, "נא להפעיל את שירות הנגישות כדי להמשיך", Toast.LENGTH_LONG).show();
        }
    }

    private void navigateToLogin() {
        Log.d(TAG, "Navigating to LoginActivity");
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
    }
}
