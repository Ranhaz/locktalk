package com.example.locktalk_01.activities;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;
import com.example.locktalk_01.activities.AccessibilityManager;
import com.example.locktalk_01.managers.OverlayManager;

import java.util.Arrays;
import java.util.List;

public class EncryptionActivity extends AppCompatActivity {

    private static final String TAG = "EncryptionActivity";
    private static final String PREF_NAME = "UserCredentials";

    private EditText personalCodeInput;
    private Button savePersonalCodeButton;
    private Button openWhatsAppButton;
    private Button logoutButton;

    private AccessibilityManager accessibilityManager;
    private OverlayManager overlayManager;

    public static final int REQ_IMG = OverlayManager.REQ_IMG;

    private final BroadcastReceiver pickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pick.setType("image/*");
            startActivityForResult(pick, REQ_IMG);
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accessibilityManager = new AccessibilityManager(this);

        if (!accessibilityManager.isAccessibilityServiceEnabled()) {
            startActivity(new Intent(this, AccessibilityActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        if (!accessibilityManager.isUserLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(R.layout.activity_encryption);

        registerReceiver(pickReceiver, new IntentFilter("com.example.ACTION_PICK_IMAGE"));
        initViews();
        loadPersonalCode();

        overlayManager = new OverlayManager(this);
        overlayManager.showOverlay(
                v -> Toast.makeText(this, "Encrypt clicked", Toast.LENGTH_SHORT).show(),
                v -> overlayManager.hideOverlay(),
                v -> Toast.makeText(this, "Decrypt clicked", Toast.LENGTH_SHORT).show()
        );
    }

    private void initViews() {
        personalCodeInput = findViewById(R.id.personalCodeInput);
        savePersonalCodeButton = findViewById(R.id.savePersonalCodeButton);
        openWhatsAppButton = findViewById(R.id.openWhatsAppButton);
        logoutButton = findViewById(R.id.logoutButton);

        savePersonalCodeButton.setOnClickListener(v -> savePersonalCode());
        openWhatsAppButton.setOnClickListener(v -> openWhatsApp());
        logoutButton.setOnClickListener(v -> doLogout());
    }

    private void loadPersonalCode() {
        String saved = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString("personalCode", "");
        personalCodeInput.setText(saved);
    }

    private void savePersonalCode() {
        String code = personalCodeInput.getText().toString();
        if (!code.matches("\\d{4}")) {
            toast("הקוד האישי חייב להיות 4 ספרות");
            return;
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString("personalCode", code)
                .apply();
        toast("הקוד האישי נשמר בהצלחה");
    }

    private void doLogout() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().clear().apply();
        accessibilityManager.setLoggedIn(false);
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static final List<String> WA_PACKAGES = Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.gbwhatsapp",
            "com.whatsapp.plus",
            "com.yowhatsapp",
            "com.fmwhatsapp",
            "io.fouad.whatsapp",
            "com.whatsapp.gold"
    );

    private void openWhatsApp() {
        if (tryDirectPackageOpen() || tryUriScheme() || tryShareIntent()) return;
        showInstallDialog();
    }

    private boolean tryDirectPackageOpen() {
        PackageManager pm = getPackageManager();
        for (String pkg : WA_PACKAGES) {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            }
        }
        return false;
    }

    private boolean tryUriScheme() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://"));
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return true;
            }
        } catch (Exception ignore) {}
        return false;
    }

    private boolean tryShareIntent() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        for (var ri : getPackageManager().queryIntentActivities(share, 0)) {
            if (WA_PACKAGES.contains(ri.activityInfo.packageName)) {
                share.setPackage(ri.activityInfo.packageName);
                startActivity(share);
                return true;
            }
        }
        return false;
    }

    private void showInstallDialog() {
        new AlertDialog.Builder(this)
                .setTitle("WhatsApp לא נמצא")
                .setMessage("לא נמצאה התקנת WhatsApp במכשיר. האם תרצה להתקין?")
                .setPositiveButton("כן", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.whatsapp"))))
                .setNegativeButton("לא", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!accessibilityManager.isAccessibilityServiceEnabled()) {
            startActivity(new Intent(this, AccessibilityActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pickReceiver);
        accessibilityManager.setLoggedIn(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        overlayManager.handlePickerResult(requestCode, resultCode, data);
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }
}