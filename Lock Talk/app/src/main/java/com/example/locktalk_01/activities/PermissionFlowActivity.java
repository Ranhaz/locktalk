package com.example.locktalk_01.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.locktalk_01.R;

import java.util.ArrayList;
import java.util.List;

public class PermissionFlowActivity extends AppCompatActivity {

    private static final int REQ_CODE_OVERLAY = 101;

    private TextView textDescription; // נוסיף כאן כדי לאפשר לך לעדכן / לשנות הסבר אם תרצה

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_flow);

        textDescription = findViewById(R.id.permissionFlowDescription);

        Button buttonGrantAll = findViewById(R.id.buttonGrantAll);
        buttonGrantAll.setOnClickListener(v -> requestAllPermissionsFlow());
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        navigateToLogin();
    }

    /**
     * מבקש תחילה Dangerous Permissions; אחר כך Overlay; אחר כך נגישות.
     */
    private void requestAllPermissionsFlow() {
        List<String> missing = new ArrayList<>();
        for (String perm : getDangerousPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        if (!missing.isEmpty()) {
            multiplePermissionLauncher.launch(missing.toArray(new String[0]));
        } else {
            checkOverlayPermission();
        }
    }

    /**
     * מתודה שמחזירה את הרשאות ה”מסוכנות” (Dangerous) המתאימות לגירסת האנדרואיד
     */
    private String[] getDangerousPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ב-Android 13 ומעלה
            return new String[] {
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            return new String[] {
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private final ActivityResultLauncher<String[]> multiplePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean allGranted = true;
                        for (String perm : getDangerousPermissions()) {
                            Boolean granted = result.getOrDefault(perm, false);
                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }
                        if (allGranted) {
                            checkOverlayPermission();
                        } else {
                            Toast.makeText(this,
                                    "לא התקבלו כל ההרשאות המסוכנות",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
            );

    /**
     * הרשאת Overlay (SYSTEM_ALERT_WINDOW)
     */
    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQ_CODE_OVERLAY);
        } else {
            checkAccessibilityService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                checkAccessibilityService();
            } else {
                Toast.makeText(this,
                        "לא אושרה הצגה מעל אפליקציות",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * בדיקה אם שירות נגישות פעיל; אם לא - פותחים הגדרות
     */
    private void checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this,
                    "אנא אשר/י את שירות הנגישות",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this,
                    "כל ההרשאות + Overlay + נגישות אושרו בהצלחה!",
                    Toast.LENGTH_SHORT).show();
            navigateToLogin();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        String serviceName = getPackageName()
                + "/com.example.locktalk_01.services.MessageAccessibilityService";
        return (enabledServices != null && enabledServices.contains(serviceName));
    }

    private void navigateToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
