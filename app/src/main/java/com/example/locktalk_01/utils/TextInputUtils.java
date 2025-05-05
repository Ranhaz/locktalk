package com.example.locktalk_01.utils;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

public class TextInputUtils {
    private static final String TAG = "TextInputUtils";

    /**
     * מחליף את הטקסט בתיבת ההקלדה של וואטסאפ
     */
    public static void performTextReplacement(Context context,
                                              AccessibilityNodeInfo root,
                                              String newText) {
        if (root == null) {
            Log.e(TAG, "performTextReplacement: root is null");
            return;
        }
        // השדה ב־WhatsApp:id/entry
        for (AccessibilityNodeInfo field : root.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/entry")) {
            if (field != null) {
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
    }

    /**
     * מחליף טקסט בכל NodeInfo (משמש בפענוח של הבלונים)
     */
    public static boolean replaceNodeText(AccessibilityNodeInfo node,
                                          String newText) {
        if (node == null) return false;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
            );
            return node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args
            );
        } catch (Exception e) {
            Log.e(TAG, "replaceNodeText failed", e);
            return false;
        }
    }
}
