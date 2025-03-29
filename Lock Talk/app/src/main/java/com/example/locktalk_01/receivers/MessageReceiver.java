package com.example.locktalk_01.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class MessageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            String message = intent.getStringExtra("message");

            if (action.equals("com.locktalk.NEW_MESSAGE")) {
                Log.d("MessageReceiver", "התקבלה הודעה: " + message);
                // ניתן להוסיף קוד לפענוח או לטיפול בהודעה
            }
        }
    }
}
