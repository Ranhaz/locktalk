package com.example.locktalk_01.managers;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import android.widget.Toast;

public class DialogManager {
    private final Context context;
    private final Handler mainHandler;
    private AlertDialog activeDialog = null;

    public DialogManager(Context ctx, MessageEncryptionHelper helper) {
        this.context = ctx;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
    }

    public void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }

}
