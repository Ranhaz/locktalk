package com.example.locktalk_01.utils;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class WhatsAppUtils {
    private static final String TAG = "WhatsAppUtils";

    public static boolean isWhatsAppPackage(String pkg) {
        return pkg != null && pkg.startsWith("com.whatsapp");
    }

    public static boolean isInWhatsAppChat(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> sendBtns =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
        boolean inChat = sendBtns != null && !sendBtns.isEmpty();
        if (sendBtns != null) {
            for (AccessibilityNodeInfo b : sendBtns) b.recycle();
        }
        return inChat;
    }


    public static String getWhatsAppInputText(AccessibilityNodeInfo root) {
        if (root == null) return "";
        List<AccessibilityNodeInfo> inputs =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (inputs == null || inputs.isEmpty()) return "";
        CharSequence cs = inputs.get(0).getText();
        String txt = cs == null ? "" : cs.toString();
        for (AccessibilityNodeInfo n : inputs) n.recycle();
        return txt;
    }

    /**
     * מוצא את כל הבלונים (TextView) עם id=message_text
     * שבהם הטקסט נראה Base64-like (כולל +,/=) ואורכו >=8 תווים
     */
    public static List<AccessibilityNodeInfo> findEncryptedMessages(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
        if (nodes == null) return out;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence cs = node.getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (s.matches("^[A-Za-z0-9+/=]{8,}$")) {
                    Log.d(TAG, "Encrypted candidate: " + s);
                    out.add(node);
                    continue;
                }
            }
            node.recycle();
        }
        return out;
    }
}
