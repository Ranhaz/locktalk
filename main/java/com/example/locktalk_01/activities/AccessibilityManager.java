package com.example.locktalk_01.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.example.locktalk_01.services.MyAccessibilityService;

public class AccessibilityManager {
    private static final String PREF_NAME = "UserCredentials";
    private final Context context;

    public AccessibilityManager(Context context) {
        this.context = context;
    }

    public boolean isAccessibilityServiceEnabled() {
        String serviceName = context.getPackageName() + "/" + MyAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(serviceName);
    }

    public void saveAccessibilityEnabled() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("accessibilityEnabled", true);
        editor.apply();
    }

    public boolean isUserLoggedIn() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("isLoggedIn", false);
    }

    // Add this new method to reset the login status for testing
    public void clearLoginStatus() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("isLoggedIn", false);
        editor.apply();
    }
}