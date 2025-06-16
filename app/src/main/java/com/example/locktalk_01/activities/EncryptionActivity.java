package com.example.locktalk_01.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;

import java.util.Arrays;
import java.util.List;
public class EncryptionActivity extends AppCompatActivity {

    private static final String TAG = "EncryptionActivity";
    private static final String PREF_NAME = "UserCredentials";
    private static final String PERSONAL_CODE_PREFIX = "personalCode_";

    private EditText personalCodeInput;
    private Button savePersonalCodeButton, openWhatsAppButton, logoutButton, resetPersonalCodeButton;
    private AccessibilityManager accessibilityManager;

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
        initViews();
        loadPersonalCode();
    }

    private void initViews() {
        personalCodeInput = findViewById(R.id.personalCodeInput);
        savePersonalCodeButton = findViewById(R.id.savePersonalCodeButton);
        openWhatsAppButton = findViewById(R.id.openWhatsAppButton);
        logoutButton = findViewById(R.id.logoutButton);

        // הוסף כפתור איפוס קוד אישי (אופציונלי, אם אין לך ב-xml)
        resetPersonalCodeButton = new Button(this);
        resetPersonalCodeButton.setText("איפוס קוד אישי");
        ((LinearLayout) personalCodeInput.getParent()).addView(resetPersonalCodeButton, 3);

        savePersonalCodeButton.setOnClickListener(v -> savePersonalCode());
        openWhatsAppButton.setOnClickListener(v -> openWhatsApp());
        logoutButton.setOnClickListener(v -> doLogout());
        resetPersonalCodeButton.setOnClickListener(v -> resetPersonalCodeFlow());
    }

    private void loadPersonalCode() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String userPhone = prefs.getString("currentUserPhone", "");
        String saved = prefs.getString(PERSONAL_CODE_PREFIX + userPhone, "");

        if (!saved.isEmpty()) {
            personalCodeInput.setText(saved);
            personalCodeInput.setEnabled(false);
            savePersonalCodeButton.setEnabled(false);
            savePersonalCodeButton.setAlpha(0.5f);
            resetPersonalCodeButton.setVisibility(View.VISIBLE);
        } else {
            personalCodeInput.setEnabled(true);
            savePersonalCodeButton.setEnabled(true);
            savePersonalCodeButton.setAlpha(1f);
            resetPersonalCodeButton.setVisibility(View.GONE);
        }
    }

    private void savePersonalCode() {
        String code = personalCodeInput.getText().toString();
        if (!code.matches("\\d{4}")) {
            toast("הקוד האישי חייב להיות 4 ספרות");
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String userPhone = prefs.getString("currentUserPhone", "");
        String existing = prefs.getString(PERSONAL_CODE_PREFIX + userPhone, "");
        if (!existing.isEmpty()) {
            toast("לא ניתן לשנות קוד אישי. יש לבצע איפוס קודם.");
            return;
        }
        prefs.edit()
                .putString(PERSONAL_CODE_PREFIX + userPhone, code)
                .putString("personalCode", code)
                .apply();
        personalCodeInput.setEnabled(false);
        savePersonalCodeButton.setEnabled(false);
        savePersonalCodeButton.setAlpha(0.5f);
        resetPersonalCodeButton.setVisibility(View.VISIBLE);
        toast("הקוד האישי נשמר בהצלחה");
    }
    private void resetPersonalCodeFlow() {
        new AlertDialog.Builder(this)
                .setTitle("איפוס קוד אישי")
                .setMessage("איפוס הקוד ימנע פיענוח של הודעות שהוצפנו עם הקוד הקודם. הודעות חדשות יפוענחו עם הקוד החדש בלבד.\n\nלהמשיך?")
                .setPositiveButton("איפוס", (d, w) -> showNewCodeDialog())
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void showNewCodeDialog() {
        final EditText codeInput = new EditText(this);
        codeInput.setHint("הכנס קוד אישי חדש (4 ספרות)");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle("הגדרת קוד אישי חדש")
                .setView(codeInput)
                .setPositiveButton("שמור", (d, w) -> {
                    String newCode = codeInput.getText().toString();
                    if (!newCode.matches("\\d{4}")) {
                        toast("הקוד האישי חייב להיות 4 ספרות");
                        return;
                    }
                    SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                    String userPhone = prefs.getString("currentUserPhone", "");
                    // מחיקה של הישן והגדרת קוד חדש
                    prefs.edit()
                            .putString(PERSONAL_CODE_PREFIX + userPhone, newCode)
                            .putString("personalCode", newCode)
                            .apply();
                    personalCodeInput.setText(newCode);
                    personalCodeInput.setEnabled(false);
                    savePersonalCodeButton.setEnabled(false);
                    savePersonalCodeButton.setAlpha(0.5f);
                    resetPersonalCodeButton.setVisibility(View.VISIBLE);
                    toast("הקוד אופס והוגדר קוד חדש. שימי לב: הודעות ישנות לא יתפענחו יותר.");
                })
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void doLogout() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().clear().apply();
        accessibilityManager.setLoggedIn(false);
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /* --------------- WhatsApp launcher --------------- */

    private static final List<String> WA_PACKAGES = Arrays.asList(
            "com.whatsapp",          // official
            "com.whatsapp.w4b",      // business
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
                .setPositiveButton("כן", (d,w)->
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.whatsapp"))))
                .setNegativeButton("לא", null)
                .show();
    }

    /* --------------- misc --------------- */

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
        accessibilityManager.setLoggedIn(false);
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }
}