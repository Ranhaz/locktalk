package com.example.locktalk_01.activities;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;

public class AccessibilityActivity extends AppCompatActivity {
    private static final String TAG = "AccessibilityActivity";

    private AccessibilityManager accessibilityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accessibilityManager = new AccessibilityManager(this);

        // אם השירות כבר פעיל, נשמור את הדגל וננקה את הסטטוס של ההתחברות
        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            accessibilityManager.clearLoginStatus();    // <<< שורה זו הוסרה
            Log.d(TAG, "Accessibility already enabled, proceeding to LoginActivity");
            navigateToLogin();
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

        // אחרי חזרה מ־Settings בודקים שוב
        if (accessibilityManager.isAccessibilityServiceEnabled()) {
            accessibilityManager.saveAccessibilityEnabled();
            accessibilityManager.clearLoginStatus();    // <<< ופה גם
            navigateToLogin();
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
