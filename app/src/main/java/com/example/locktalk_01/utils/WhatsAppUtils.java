package com.example.locktalk_01.utils;

import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhatsAppUtils {
    private static final String TAG = "WhatsAppUtils";
    private static final int MIN_BUBBLE_SIZE = 200;

    // מחזיר רשימת כפתורי תמונה בוואטסאפ (ב־chat)
    public static List<Pair<AccessibilityNodeInfo, Rect>> findImageBubbleButtons(AccessibilityNodeInfo root) {
        List<Pair<AccessibilityNodeInfo, Rect>> result = new ArrayList<>();
        Set<Integer> seenNodes = new HashSet<>();
        findImageButtonsRecursive(root, result, seenNodes);
        return result;
    }

    private static void findImageButtonsRecursive(AccessibilityNodeInfo node, List<Pair<AccessibilityNodeInfo, Rect>> result, Set<Integer> seenNodes) {
        if (node == null) return;
        int hc = System.identityHashCode(node);
        if (!seenNodes.contains(hc)) {
            CharSequence id = node.getViewIdResourceName();
            CharSequence desc = node.getContentDescription();
            if ("android.widget.Button".contentEquals(node.getClassName())
                    && id != null && "com.whatsapp:id/image".contentEquals(id)
                    && (desc != null && desc.toString().contains("הגדלת התמונה"))) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() >= MIN_BUBBLE_SIZE && bounds.height() >= MIN_BUBBLE_SIZE) {
                    result.add(new Pair<>(node, bounds));
                    Log.d("WhatsAppUtils", "Found image bubble BUTTON: bounds=" + bounds);
                }
            }
            seenNodes.add(hc);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findImageButtonsRecursive(node.getChild(i), result, seenNodes);
        }
    }

    // --- שאר הקוד שלך ללא שינוי ---

    public static Uri getImageUriFromNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString();
            int idx = s.indexOf("uri:");
            if (idx >= 0) {
                String uriStr = s.substring(idx + 4).trim();
                try {
                    return Uri.parse(uriStr);
                } catch (Exception ignored) {
                    Log.e(TAG, "Failed to parse URI from ContentDescription: " + uriStr);
                }
            }
            if (s.equalsIgnoreCase("תמונה") || s.equalsIgnoreCase("Image") ||
                    s.contains("נשלחה") || s.contains("sent")) {
                return null;
            }
        }
        CharSequence text = node.getText();
        if (text != null && text.toString().startsWith("content://")) {
            try {
                return Uri.parse(text.toString());
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static boolean isWhatsAppPackage(String pkg) {
        return pkg != null && pkg.startsWith("com.whatsapp");
    }

    public static String getWhatsAppInputText(AccessibilityNodeInfo root) {
        if (root == null) return "";
        List<AccessibilityNodeInfo> inputs =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (inputs == null || inputs.isEmpty()) return "";
        String text = "";
        AccessibilityNodeInfo in = inputs.get(0);
        CharSequence cs = in.getText();
        if (cs != null) text = cs.toString();
        for (AccessibilityNodeInfo node : inputs) node.recycle();
        return text;
    }

    public static List<AccessibilityNodeInfo> findEncryptedMessages(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                CharSequence cs = node.getText();
                if (cs != null) {
                    String s = cs.toString().trim();
                    if (s.matches("^[A-Za-z0-9+/=]{8,}$")) {
                        out.add(node);
                        continue;
                    }
                }
                node.recycle();
            }
        }
        return out;
    }
}
