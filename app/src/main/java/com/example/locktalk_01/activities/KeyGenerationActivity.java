
package com.example.locktalk_01.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.locktalk_01.R;

public class KeyGenerationActivity extends AppCompatActivity {

    private TextView contactNameText;
    private Button generateKeyButton;
    private Button continueButton;
    private String selectedContact;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_generation);

        contactNameText = findViewById(R.id.contact_name);
        generateKeyButton = findViewById(R.id.generate_key_button);
        continueButton = findViewById(R.id.continue_button);

        selectedContact = getIntent().getStringExtra("selectedContact");
        contactNameText.setText("מפתח לשיחה עם: " + selectedContact);

        generateKeyButton.setOnClickListener(v -> {
            // לוגיקת יצירת מפתח מדומה
            contactNameText.setText("מפתח נוצר בהצלחה עבור " + selectedContact);
            continueButton.setEnabled(true);
        });

        continueButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("contact", selectedContact);
            startActivity(intent);
            finish();
        });
    }
}