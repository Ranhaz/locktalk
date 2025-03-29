package com.example.locktalk_01.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.locktalk_01.R;

public class MainActivity extends AppCompatActivity {

    private TextView chatTitle;
    private Button openWhatsappButton;
    private String contactName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        chatTitle = findViewById(R.id.chat_title);
        openWhatsappButton = findViewById(R.id.open_whatsapp_button);

        contactName = getIntent().getStringExtra("contact");
        chatTitle.setText("הצפנה לשיחה עם " + contactName);

        openWhatsappButton.setOnClickListener(v -> {
            // נפתח פשוט את ווטסאפ – ללא מספר. (רק כדוגמה.)
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                chatTitle.setText("לא נמצאה אפליקציית וואטסאפ במכשיר");
            }
        });
    }
}
