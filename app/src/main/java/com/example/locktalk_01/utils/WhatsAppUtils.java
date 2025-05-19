package com.example.locktalk_01.utils;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

public class WhatsAppUtils {
    private static final String TAG = "WhatsAppUtils";
    private static final String IMAGE_DESC_PREFIX = "Encrypted:";

    public static boolean isWhatsAppPackage(String pkg) {
        return pkg != null && pkg.startsWith("com.whatsapp");
    }

    public static String getWhatsAppInputText(AccessibilityNodeInfo root) {
        if (root == null) return "";
        List<AccessibilityNodeInfo> inputs =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (inputs == null || inputs.isEmpty()) return "";
        String txt = inputs.get(0).getText() == null
                ? ""
                : inputs.get(0).getText().toString();
        for (AccessibilityNodeInfo n : inputs) n.recycle();
        return txt;
    }

    public static void replaceInputText(AccessibilityService svc,
                                        AccessibilityNodeInfo root,
                                        String newText) {
        TextInputUtils.performTextReplacement(svc, root, newText);
    }

    public static List<AccessibilityNodeInfo> findEncryptedImages(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        List<AccessibilityNodeInfo> imgs =
                root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/image_thumbnail");
        if (imgs == null) return out;
        for (AccessibilityNodeInfo n : imgs) {
            CharSequence desc = n.getContentDescription();
            if (desc != null && desc.toString().startsWith(IMAGE_DESC_PREFIX)) {
                out.add(n);
            } else {
                n.recycle();
            }
        }
        return out;
    }

    public static String getImageFakeTag(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription();
        if (desc == null) return null;
        String s = desc.toString();
        return s.startsWith(IMAGE_DESC_PREFIX)
                ? s.substring(IMAGE_DESC_PREFIX.length())
                : null;
    }

}
