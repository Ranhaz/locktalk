package com.example.locktalk_01.utils;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;

public class PersonalCodeManager {

    private static final String PREF_NAME = "UserCredentials";

    // שמירת קוד אישי מוצפן
    public static void savePersonalCode(Context ctx, String phone, String code) {
        String deviceId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String combinedKey = phone + "_" + deviceId;
        String encryptedCode = encryptCode(code, combinedKey);

        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("personalCode_" + phone, encryptedCode)
                .apply();
    }

    // בדיקת קוד אישי תקף
    public static boolean validatePersonalCode(Context ctx, String phone, String inputCode) {
        String deviceId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String combinedKey = phone + "_" + deviceId;
        String encrypted = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString("personalCode_" + phone, null);

        if (encrypted == null) return false;
        String realCode = decryptCode(encrypted, combinedKey);
        return realCode.equals(inputCode);
    }

    // קריאת הקוד שמור (לא לחשוף למשתמש, אלא רק ל-flow פנימי)
    public static String getPersonalCode(Context ctx, String phone) {
        String deviceId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String combinedKey = phone + "_" + deviceId;
        String encrypted = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString("personalCode_" + phone, null);
        if (encrypted == null) return null;
        return decryptCode(encrypted, combinedKey);
    }

    // --- פונקציות הצפנה בסיסיות (אפשר להחליף ל-AES בהמשך) ---
    private static String encryptCode(String code, String key) {
        return Base64.encodeToString((code + key).getBytes(), Base64.DEFAULT);
    }
    private static String decryptCode(String encrypted, String key) {
        String decoded = new String(Base64.decode(encrypted, Base64.DEFAULT));
        if (decoded.endsWith(key)) {
            return decoded.substring(0, decoded.length() - key.length());
        }
        return "";
    }
}
