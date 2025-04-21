package com.example.locktalk_01.activities;


import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.example.locktalk_01.R;

public class SplashActivity extends AppCompatActivity {
    private static final int SPLASH_TIMEOUT = 2000;
    private static final String TAG = "SplashActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        Log.d(TAG, "SplashActivity created");

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Starting AccessibilityActivity from SplashActivity");
                Intent intent = new Intent(SplashActivity.this, AccessibilityActivity.class);
                startActivity(intent);
                finish();
            }
        }, SPLASH_TIMEOUT);
    }
}
