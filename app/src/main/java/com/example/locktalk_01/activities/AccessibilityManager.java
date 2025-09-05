package com.example.locktalk_01.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.example.locktalk_01.services.MyAccessibilityService;

public class AccessibilityManager {

    private static final String PREF_NAME       = "UserCredentials";
    private static final String KEY_LOGGED_IN   = "isLoggedIn";
    private static final String KEY_ACCESS_FLAG = "accessibilityEnabled";

    private final Context ctx;

    public AccessibilityManager(Context c) { this.ctx = c.getApplicationContext(); }

    /* ---------------------------------------------------- */
    /*   Accessibility service enabled?                     */
    /* ---------------------------------------------------- */
    public boolean isAccessibilityServiceEnabled() {
        String serviceName = ctx.getPackageName()+"/"+ MyAccessibilityService.class.getCanonicalName();
        String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(serviceName);
    }

    /** marker used once, e.g. after the user enabled the toggle in system settings */
    public void saveAccessibilityEnabled() {
        prefs().edit().putBoolean(KEY_ACCESS_FLAG, true).apply();
    }

    /* ---------------------------------------------------- */
    /*   Login flag handling                                */
    /* ---------------------------------------------------- */
    public boolean isUserLoggedIn() {
        boolean loggedIn = prefs().getBoolean(KEY_LOGGED_IN, false);
        Log.d("AccessibilityManager", "isUserLoggedIn = " + loggedIn);
        return loggedIn;
    }

    public void setLoggedIn(boolean v) {
        Log.d("AccessibilityManager", "setLoggedIn = " + v);
        prefs().edit().putBoolean(KEY_LOGGED_IN, v).apply();
    }
    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
