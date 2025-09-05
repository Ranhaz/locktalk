package com.example.locktalk_01.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.locktalk_01.R;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.*;
import com.google.firebase.database.*;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class LoginActivity extends AppCompatActivity {

    private EditText phoneInput, passwordInput, confirmPasswordInput, codeInput;
    private TextView passwordRequirementsText, switchModeText;
    private Button loginButton, registerButton, sendCodeBtn, verifyBtn, forgotPasswordBtn;
    private LinearLayout confirmPasswordContainer;
    private ProgressBar progressBar;
    private ImageButton showPasswordButton, showConfirmPasswordButton;

    private boolean isLoginMode = true;
    private boolean isPasswordVisible = false;
    private boolean isConfirmPasswordVisible = false;
    private boolean isWaitingForSms = false;
    private boolean isResetPasswordMode = false;

    private String verificationId;
    private String enteredPhone, enteredPassword;

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Z])(?=.*[^A-Za-z0-9])(?=.{8,}).+$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        phoneInput = findViewById(R.id.phoneInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        codeInput = findViewById(R.id.codeInput);
        confirmPasswordContainer = findViewById(R.id.confirmPasswordContainer);
        passwordRequirementsText = findViewById(R.id.passwordRequirementsText);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        switchModeText = findViewById(R.id.switchModeText);
        sendCodeBtn = findViewById(R.id.sendCodeBtn);
        verifyBtn = findViewById(R.id.verifyBtn);
        forgotPasswordBtn = findViewById(R.id.forgotPasswordBtn);
        progressBar = findViewById(R.id.progressBar);
        showPasswordButton = findViewById(R.id.showPasswordButton);
        showConfirmPasswordButton = findViewById(R.id.showConfirmPasswordButton);

        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Listeners
        loginButton.setOnClickListener(v -> loginFlow());
        registerButton.setOnClickListener(v -> sendVerificationCode());
        sendCodeBtn.setOnClickListener(v -> sendVerificationCode());
        verifyBtn.setOnClickListener(v -> verifyCode());
        forgotPasswordBtn.setOnClickListener(v -> forgotPasswordFlow());

        showPasswordButton.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;
            passwordInput.setInputType(isPasswordVisible ?
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            showPasswordButton.setImageResource(isPasswordVisible ?
                    R.drawable.ic_eye_off : R.drawable.ic_eye);
            passwordInput.setSelection(passwordInput.getText().length());
        });

        showConfirmPasswordButton.setOnClickListener(v -> {
            isConfirmPasswordVisible = !isConfirmPasswordVisible;
            confirmPasswordInput.setInputType(isConfirmPasswordVisible ?
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            showConfirmPasswordButton.setImageResource(isConfirmPasswordVisible ?
                    R.drawable.ic_eye_off : R.drawable.ic_eye);
            confirmPasswordInput.setSelection(confirmPasswordInput.getText().length());
        });

        switchModeText.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            isWaitingForSms = false;
            isResetPasswordMode = false;
            updateUI();
        });

        updateUI();
    }

    private void updateUI() {
        if (isLoginMode && !isResetPasswordMode) {
            loginButton.setVisibility(View.VISIBLE);
            registerButton.setVisibility(View.GONE);
            confirmPasswordContainer.setVisibility(View.GONE);
            passwordRequirementsText.setVisibility(View.GONE);
            sendCodeBtn.setVisibility(View.GONE);
            codeInput.setVisibility(View.GONE);
            verifyBtn.setVisibility(View.GONE);
            forgotPasswordBtn.setVisibility(View.VISIBLE);
            switchModeText.setText("אין לך חשבון? לחץ כאן להרשמה");
        } else if (isWaitingForSms) {
            loginButton.setVisibility(View.GONE);
            registerButton.setVisibility(View.GONE);
            confirmPasswordContainer.setVisibility(View.VISIBLE);
            passwordRequirementsText.setVisibility(View.VISIBLE);
            sendCodeBtn.setVisibility(View.GONE);
            codeInput.setVisibility(View.VISIBLE);
            verifyBtn.setVisibility(View.VISIBLE);
            forgotPasswordBtn.setVisibility(View.GONE);
            switchModeText.setText("יש לך חשבון? לחץ כאן להתחברות");
        } else if (isResetPasswordMode) {
            loginButton.setVisibility(View.GONE);
            registerButton.setVisibility(View.GONE);
            confirmPasswordContainer.setVisibility(View.GONE);
            passwordRequirementsText.setVisibility(View.GONE);
            sendCodeBtn.setVisibility(View.GONE);
            codeInput.setVisibility(View.VISIBLE);
            verifyBtn.setVisibility(View.VISIBLE);
            forgotPasswordBtn.setVisibility(View.GONE);
            switchModeText.setText("חזרה למסך התחברות");
        } else {
            loginButton.setVisibility(View.GONE);
            registerButton.setVisibility(View.VISIBLE);
            confirmPasswordContainer.setVisibility(View.VISIBLE);
            passwordRequirementsText.setVisibility(View.VISIBLE);
            sendCodeBtn.setVisibility(View.GONE);
            codeInput.setVisibility(View.GONE);
            verifyBtn.setVisibility(View.GONE);
            forgotPasswordBtn.setVisibility(View.GONE);
            switchModeText.setText("יש לך חשבון? לחץ כאן להתחברות");
        }
        progressBar.setVisibility(View.GONE);
    }

    // ----------- פונקציה עזר לשמירת הטלפון ב-SharedPreferences -----------
    private void saveMyPhoneToPrefs(String phone) {
        SharedPreferences prefs = getSharedPreferences("UserCredentials", MODE_PRIVATE);
        prefs.edit().putString("myPhone", phone).apply();
        Log.d("LT_DEBUG", "Saved myPhone: " + phone);
    }

    // ----------- הרשמה -----------
    private void sendVerificationCode() {
        String userInput = phoneInput.getText().toString().trim();
        if (!userInput.matches("^5\\d{8}$")) {
            phoneInput.setError("יש להזין מספר חוקי, לדוג' 541234567");
            return;
        }
        String fullPhone = "+972" + userInput;
        enteredPhone = fullPhone;
        enteredPassword = passwordInput.getText().toString();
        String confirmPassword = confirmPasswordInput.getText().toString();

        if (TextUtils.isEmpty(enteredPassword) || TextUtils.isEmpty(confirmPassword)) {
            Toast.makeText(this, "יש למלא את כל השדות", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!enteredPassword.equals(confirmPassword)) {
            Toast.makeText(this, "הסיסמאות לא תואמות", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PASSWORD_PATTERN.matcher(enteredPassword).find()) {
            Toast.makeText(this, "הסיסמה חייבת להכיל לפחות 8 תווים, אות גדולה ותו מיוחד", Toast.LENGTH_LONG).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        // קודם נבדוק אם המשתמש כבר קיים באימות (Firebase Auth)
        FirebaseAuth.getInstance().fetchSignInMethodsForEmail(fullPhone + "@locktalk.com")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        boolean userExists = task.getResult().getSignInMethods() != null &&
                                !task.getResult().getSignInMethods().isEmpty();
                        if (userExists) {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(this, "המספר כבר רשום, נסה להתחבר", Toast.LENGTH_LONG).show();
                            isLoginMode = true;
                            isWaitingForSms = false;
                            updateUI();
                        } else {
                            // שלב אימות טלפון (SMS)
                            startPhoneVerification(fullPhone);
                        }
                    } else {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "שגיאה בבדיקת משתמש: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void startPhoneVerification(String phone) {
        progressBar.setVisibility(View.VISIBLE);
        isWaitingForSms = true;
        updateUI();
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(mCallbacks)
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks mCallbacks =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                    verifyCodeWithCredential(credential, false);
                }
                @Override
                public void onVerificationFailed(@NonNull FirebaseException e) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LoginActivity.this, "אימות נכשל: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                @Override
                public void onCodeSent(@NonNull String vId, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                    verificationId = vId;
                    progressBar.setVisibility(View.GONE);
                    isWaitingForSms = true;
                    updateUI();
                    Toast.makeText(LoginActivity.this, "קוד נשלח!", Toast.LENGTH_SHORT).show();
                }
            };

    private void verifyCode() {
        String code = codeInput.getText().toString().trim();
        if (TextUtils.isEmpty(code)) {
            codeInput.setError("הכנס קוד");
            return;
        }
        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(verificationId, code);
        verifyCodeWithCredential(credential, isResetPasswordMode);
    }
    private void verifyCodeWithCredential(PhoneAuthCredential credential, boolean isReset) {
        progressBar.setVisibility(View.VISIBLE);
        FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        if (isReset) {
                            resetPasswordAfterVerification();
                        } else {
                            FirebaseAuth.getInstance().createUserWithEmailAndPassword(
                                    enteredPhone + "@locktalk.com", enteredPassword
                            ).addOnCompleteListener(createTask -> {
                                if (createTask.isSuccessful()) {
                                    saveMyPhoneToPrefs(enteredPhone);

                                    // --- שמירת יוזר ב-Firestore ---
                                    FirebaseFirestore db = FirebaseFirestore.getInstance();
                                    Map<String, Object> userData = new HashMap<>();
                                    userData.put("phone", enteredPhone);
                                    userData.put("createdAt", System.currentTimeMillis());
                                    // לא לשמור password אמיתי בפרודקשן!
                                    // userData.put("password", enteredPassword);

                                    // שמור את היוזר עם מזהה טלפון (ללא קידומת)
                                    String docId = enteredPhone.replace("+972", "");
                                    db.collection("users")
                                            .document(docId)
                                            .set(userData, SetOptions.merge())
                                            .addOnSuccessListener(unused -> {
                                                Log.d("LT_DEBUG", "Firestore: User saved successfully");

                                                isLoginMode = true;
                                                isWaitingForSms = false;
                                                updateUI();
                                                codeInput.setText("");
                                                confirmPasswordInput.setText("");
                                                passwordInput.setText("");
                                                Toast.makeText(this, "נרשמת בהצלחה! נא להתחבר", Toast.LENGTH_LONG).show();
                                                Log.d("LT_DEBUG", "Firestore: User saved successfully");
                                            }).addOnFailureListener(e -> {
                                                Toast.makeText(this, "שגיאה בשמירה במסד", Toast.LENGTH_SHORT).show();
                                                Log.e("LT_DEBUG", "Firestore save failed: " + e.getMessage());

                                            });
                                } else {
                                    Toast.makeText(this, "שגיאה ביצירת משתמש: " + createTask.getException().getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    } else {
                        Exception e = task.getException();
                        Toast.makeText(this, "שגיאה: " + (e != null ? e.getMessage() : "שגיאה לא ידועה"), Toast.LENGTH_LONG).show();
                    }
                });
    }
    // ----------- התחברות -----------
    private void loginFlow() {
        String userInput = phoneInput.getText().toString().trim();
        if (!userInput.matches("^5\\d{8}$")) {
            phoneInput.setError("יש להזין מספר חוקי, לדוג' 541234567");
            return;
        }
        String fullPhone = "+972" + userInput;
        enteredPhone = fullPhone;
        enteredPassword = passwordInput.getText().toString();
        if (TextUtils.isEmpty(enteredPassword)) {
            Toast.makeText(this, "יש למלא את כל השדות", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);

        FirebaseAuth.getInstance().signInWithEmailAndPassword(
                enteredPhone + "@locktalk.com", enteredPassword
        ).addOnCompleteListener(task -> {
            progressBar.setVisibility(View.GONE);
            if (task.isSuccessful()) {
                Toast.makeText(this, "ברוך הבא!", Toast.LENGTH_SHORT).show();

                // ---------- שמור את המספר שלך ב-SharedPreferences ----------
                saveMyPhoneToPrefs(enteredPhone);

                new AccessibilityManager(this).setLoggedIn(true);
                startActivity(new Intent(this, EncryptionActivity.class));
                finish();
            } else {
                Toast.makeText(this, "סיסמה או מספר שגויים", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ----------- איפוס סיסמה -----------
    private void forgotPasswordFlow() {
        String userInput = phoneInput.getText().toString().trim();
        if (!userInput.matches("^5\\d{8}$")) {
            phoneInput.setError("יש להזין מספר חוקי");
            return;
        }
        String fullPhone = "+972" + userInput;
        enteredPhone = fullPhone;

        progressBar.setVisibility(View.VISIBLE);

        // שליפת המשתמש מ-Firestore
        String docId = fullPhone.replace("+972", "");
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").document(docId).get().addOnSuccessListener(snapshot -> {
            progressBar.setVisibility(View.GONE);
            if (!snapshot.exists()) {
                Toast.makeText(LoginActivity.this, "המספר לא רשום במערכת", Toast.LENGTH_LONG).show();
            } else {
                isLoginMode = false;
                isResetPasswordMode = true;
                updateUI();
                startPasswordResetVerification(fullPhone);
            }
        }).addOnFailureListener(e -> {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(LoginActivity.this, "שגיאה בגישה לשרת", Toast.LENGTH_SHORT).show();
        });
    }


    private void startPasswordResetVerification(String phone) {
        progressBar.setVisibility(View.VISIBLE);
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(resetPasswordCallback)
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks resetPasswordCallback =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) { }
                @Override
                public void onVerificationFailed(@NonNull FirebaseException e) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LoginActivity.this, "שליחת קוד נכשלה: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                @Override
                public void onCodeSent(@NonNull String vId, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                    verificationId = vId;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(LoginActivity.this, "קוד לאיפוס נשלח!", Toast.LENGTH_SHORT).show();
                }
            };

    private void resetPasswordAfterVerification() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("הזן סיסמה חדשה");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("איפוס", (dialog, which) -> {
            String newPassword = input.getText().toString().trim();
            if (!PASSWORD_PATTERN.matcher(newPassword).find()) {
                Toast.makeText(this, "הסיסמה חייבת להכיל לפחות 8 תווים, אות גדולה ותו מיוחד", Toast.LENGTH_LONG).show();
                return;
            }
            // עדכון סיסמה גם ב-Auth
            FirebaseAuth.getInstance().signInWithEmailAndPassword(
                    enteredPhone + "@locktalk.com", enteredPassword
            ).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    FirebaseAuth.getInstance().getCurrentUser()
                            .updatePassword(newPassword)
                            .addOnSuccessListener(aVoid -> {
                                // עדכון שדה ב-Firestore
                                String docId = enteredPhone.replace("+972", "");
                                FirebaseFirestore db = FirebaseFirestore.getInstance();
                                Map<String, Object> update = new HashMap<>();
                                // לא לשמור סיסמה אמיתית במבנה אמיתי!
                                // update.put("password", newPassword);
                                db.collection("users").document(docId).update(update);
                                Toast.makeText(this, "הסיסמה אופסה בהצלחה!", Toast.LENGTH_SHORT).show();
                                isLoginMode = true;
                                isResetPasswordMode = false;
                                codeInput.setText("");
                                passwordInput.setText("");
                                updateUI();
                            });
                } else {
                    Toast.makeText(this, "שגיאה: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });
        builder.setNegativeButton("ביטול", (dialog, which) -> {
            isLoginMode = true;
            isResetPasswordMode = false;
            updateUI();
        });
        builder.show();
    }
}

