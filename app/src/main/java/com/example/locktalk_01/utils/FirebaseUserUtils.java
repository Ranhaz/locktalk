package com.example.locktalk_01.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class FirebaseUserUtils {
    public interface CheckCallback {
        void onResult(boolean isMatch);
    }

    // בודק שהיוזר בפיירבייס תואם למספר ב־SharedPreferences
    public static void checkUserPhoneMatch(Context context, CheckCallback callback) {
        SharedPreferences prefs = context.getSharedPreferences("UserCredentials", Context.MODE_PRIVATE);
        String myPhone = prefs.getString("myPhone", null);
        if (myPhone == null) {
            callback.onResult(false);
            return;
        }
        String docId = myPhone.replace("+972", "");
        FirebaseFirestore.getInstance().collection("users")
                .document(docId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String firebasePhone = doc.getString("phone");
                        boolean match = myPhone.equals(firebasePhone);
                        callback.onResult(match);
                    } else {
                        callback.onResult(false);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("FirebaseUserUtils", "Firestore error: " + e.getMessage());
                    callback.onResult(false);
                });
    }
}
