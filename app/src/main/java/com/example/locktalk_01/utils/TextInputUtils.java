package com.example.locktalk_01.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class TextInputUtils {
    private static final String TAG = "TextInputUtils";

    /** מחליף טקסט בתיבת ההקלדה של וואטסאפ */
    public static void performTextReplacement(Context context,
                                              AccessibilityNodeInfo root,
                                              String newText) {
        if (root == null) {
            Log.e(TAG, "performTextReplacement: root is null");
            return;
        }
        List<AccessibilityNodeInfo> fields = root.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/entry");
        if (fields == null) return;
        for (AccessibilityNodeInfo field : fields) {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
            );
            boolean ok = field.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args
            );
            Log.d(TAG, "performTextReplacement -> " + ok + " text=\"" + newText + "\"");
            field.recycle();
            return;
        }
    }

    /**
     * מדביק לתוך שדה הטקסט URI של תמונה מתוך ה־Clipboard,
     * ואז שולח ACTION_PASTE כדי להדביק את התמונה.
     */
    public static void performImageAttachment(Context context,
                                              AccessibilityNodeInfo root,
                                              Uri imageUri) {
        if (root == null || imageUri == null) {
            Log.e(TAG, "performImageAttachment: null root or uri");
            return;
        }
        // שמים את ה־URI ב־Clipboard
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newUri(context.getContentResolver(), "image", imageUri);
        cm.setPrimaryClip(clip);

        // מוצאים שוב את שדה ה-entry
        List<AccessibilityNodeInfo> fields = root.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/entry");
        if (fields == null) return;
        for (AccessibilityNodeInfo field : fields) {
            // שולחים ACTION_PASTE
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            Log.d(TAG, "performImageAttachment -> pasted? " + ok + " uri=" + imageUri);
            field.recycle();
            return;
        }
    }
}
