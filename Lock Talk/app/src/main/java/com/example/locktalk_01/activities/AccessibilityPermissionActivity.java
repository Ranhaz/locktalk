package com.example.locktalk_01.activities;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;

public class AccessibilityPermissionActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_permission);

        Button enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton);
        enableAccessibilityButton.setOnClickListener(v -> {
            // מעבר להגדרות נגישות
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!isAccessibilityServiceEnabled()) return;

        // לאחר שאישרו נגישות, עוברים למסך התחברות
        startActivity(new Intent(this, PermissionFlowActivity.class));
        finish();
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String service = getPackageName() + "/com.example.locktalk_01.services.MessageAccessibilityService";
        return enabledServices != null && enabledServices.contains(service);
    }
}
