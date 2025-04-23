package com.example.locktalk_01.activities;

import com.example.locktalk_01.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText confirmPasswordInput;
    private TextView passwordRequirementsText;
    private Button loginButton;
    private Button registerButton;
    private TextView switchModeText;

    private static final String PREF_NAME = "UserCredentials";
    private boolean isLoginMode = true;

    // Password validation pattern - requires at least 8 chars, one uppercase letter and one special character
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Z])(?=.*[^A-Za-z0-9])(?=.{8,}).+$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Initialize UI elements
        usernameInput = findViewById(R.id.usernameInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        passwordRequirementsText = findViewById(R.id.passwordRequirementsText);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        switchModeText = findViewById(R.id.switchModeText);

        // Check if user is already logged in
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean("isLoggedIn", false);

        if (isLoggedIn) {
            startActivity(new Intent(LoginActivity.this, EncryptionActivity.class));
            finish();
        }

        updateUI();

        switchModeText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLoginMode = !isLoginMode;
                updateUI();
            }
        });

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = usernameInput.getText().toString();
                String password = passwordInput.getText().toString();

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(LoginActivity.this, "נא להזין שם משתמש וסיסמה", Toast.LENGTH_SHORT).show();
                    return;
                }

                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                String savedUsername = prefs.getString("username", "");
                String savedPasswordHash = prefs.getString("passwordHash", "");
                String savedSalt = prefs.getString("salt", "");

                String hashedPassword = hashPassword(password, savedSalt);

                if (username.equals(savedUsername) && hashedPassword.equals(savedPasswordHash)) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("isLoggedIn", true);
                    editor.apply();

                    Intent intent = new Intent(LoginActivity.this, EncryptionActivity.class);
                    startActivity(intent);
                    finish();
                } else {
                    Toast.makeText(LoginActivity.this, "שם משתמש או סיסמה שגויים", Toast.LENGTH_SHORT).show();
                }
            }
        });

        registerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = usernameInput.getText().toString();
                String password = passwordInput.getText().toString();
                String confirmPassword = confirmPasswordInput.getText().toString();

                if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                    Toast.makeText(LoginActivity.this, "נא להזין את כל השדות", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!password.equals(confirmPassword)) {
                    Toast.makeText(LoginActivity.this, "הסיסמאות אינן תואמות", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Validate password complexity
                if (!PASSWORD_PATTERN.matcher(password).find()) {
                    Toast.makeText(LoginActivity.this,
                            "הסיסמה חייבת להכיל לפחות 8 תווים, אות גדולה אחת ותו מיוחד אחד",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                // Generate salt and hash password
                String salt = generateSalt();
                String hashedPassword = hashPassword(password, salt);

                // Save credentials
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("username", username);
                editor.putString("passwordHash", hashedPassword);
                editor.putString("salt", salt);
                editor.putBoolean("isLoggedIn", true);
                editor.apply();

                Toast.makeText(LoginActivity.this, "הרישום הושלם בהצלחה", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(LoginActivity.this, EncryptionActivity.class);
                startActivity(intent);
                finish();
            }
        });
    }

    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return bytesToHex(salt);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hexToBytes(salt));
            byte[] hashedPassword = md.digest(password.getBytes());
            return bytesToHex(hashedPassword);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error hashing password", e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private void updateUI() {
        if (isLoginMode) {
            loginButton.setVisibility(View.VISIBLE);
            registerButton.setVisibility(View.GONE);
            confirmPasswordInput.setVisibility(View.GONE);
            passwordRequirementsText.setVisibility(View.GONE);
            switchModeText.setText("אין לך חשבון? לחץ כאן להרשמה");
        } else {
            loginButton.setVisibility(View.GONE);
            registerButton.setVisibility(View.VISIBLE);
            confirmPasswordInput.setVisibility(View.VISIBLE);
            passwordRequirementsText.setVisibility(View.VISIBLE);
            switchModeText.setText("יש לך חשבון? לחץ כאן להתחברות");
        }
    }
}