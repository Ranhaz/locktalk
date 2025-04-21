package com.example.locktalk_01.utils;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WhatsAppUtils {
    private static final String TAG = "WhatsAppUtils";
    public static final String WHATSAPP_PACKAGE = "com.whatsapp";
    // רשימת כל חבילות WhatsApp האפשריות
    private static final List<String> WHATSAPP_PACKAGES = Arrays.asList(
            "com.whatsapp",       // WhatsApp רגיל
            "com.whatsapp.w4b",   // WhatsApp עסקי
            "com.gbwhatsapp",     // GB WhatsApp
            "com.whatsapp.plus",  // WhatsApp Plus
            "com.yowhatsapp",     // YoWhatsApp
            "com.fmwhatsapp",     // FM WhatsApp
            "io.fouad.whatsapp",  // גרסאות חלופיות
            "com.whatsapp.gold"   // WhatsApp Gold
    );

    // בדיקה אם חבילה היא אחת מגרסאות WhatsApp
    public static boolean isWhatsAppPackage(String packageName) {
        if (packageName == null) return false;

        // בדיקה מדויקת אם החבילה נמצאת ברשימה
        for (String whatsappPackage : WHATSAPP_PACKAGES) {
            if (packageName.equals(whatsappPackage) || packageName.contains("whatsapp")) {
                return true;
            }
        }
        return false;
    }

    public static String getWhatsAppInputText(AccessibilityNodeInfo rootNode) {
        // בדיקה עבור תיבת הקלט בכל גרסאות WhatsApp הידועות
        List<String> inputIds = Arrays.asList(
                "com.whatsapp:id/entry",
                "com.whatsapp:id/message_text",
                "com.whatsapp.w4b:id/entry",
                "com.whatsapp.w4b:id/message_text",
                "com.gbwhatsapp:id/entry",
                "com.gbwhatsapp:id/message_text",
                "com.whatsapp.plus:id/entry",
                "com.whatsapp.plus:id/message_text",
                "com.yowhatsapp:id/entry",
                "com.yowhatsapp:id/message_text"
        );

        // חיפוש בכל המזהים האפשריים
        for (String inputId : inputIds) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByViewId(inputId);
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo inputNode = nodes.get(0);
                if (inputNode != null && inputNode.getText() != null) {
                    return inputNode.getText().toString();
                }
            }
        }

        // אם לא מצאנו, חפש EditText כלשהו
        return findEditTextContent(rootNode);
    }

    public static boolean isInWhatsAppChat(AccessibilityNodeInfo rootNode) {
        // בדיקה עבור כפתורי שליחה וצ'אט בכל גרסאות WhatsApp הידועות
        List<String> chatElementIds = Arrays.asList(
                // WhatsApp רגיל
                "com.whatsapp:id/send",
                "com.whatsapp:id/voice_note_btn",
                "com.whatsapp:id/attach_camera_button",
                "com.whatsapp:id/conversation_contact_name",

                // WhatsApp עסקי
                "com.whatsapp.w4b:id/send",
                "com.whatsapp.w4b:id/voice_note_btn",
                "com.whatsapp.w4b:id/attach_camera_button",
                "com.whatsapp.w4b:id/conversation_contact_name",

                // GB WhatsApp
                "com.gbwhatsapp:id/send",
                "com.gbwhatsapp:id/voice_note_btn",
                "com.gbwhatsapp:id/attach_camera_button",
                "com.gbwhatsapp:id/conversation_contact_name",

                // WhatsApp Plus
                "com.whatsapp.plus:id/send",
                "com.whatsapp.plus:id/voice_note_btn",
                "com.whatsapp.plus:id/attach_camera_button",
                "com.whatsapp.plus:id/conversation_contact_name",

                // YoWhatsApp
                "com.yowhatsapp:id/send",
                "com.yowhatsapp:id/voice_note_btn",
                "com.yowhatsapp:id/attach_camera_button",
                "com.yowhatsapp:id/conversation_contact_name"
        );

        // בדיקה אם אחד מהאלמנטים נמצא
        for (String elementId : chatElementIds) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByViewId(elementId);
            if (nodes != null && !nodes.isEmpty()) {
                return true;
            }
        }

        // בדיקות נוספות בהתבסס על מבנה המסך
        // בדיקה אם יש קלט טקסט ברקע
        if (findEditTextContent(rootNode) != null) {
            // חיפוש אלמנטים נפוצים בממשק צ'אט
            List<AccessibilityNodeInfo> textViews = findNodesByClassName(rootNode, "android.widget.TextView");
            for (AccessibilityNodeInfo textView : textViews) {
                CharSequence text = textView.getText();
                if (text != null) {
                    // חיפוש טקסט מאפיין לממשק צ'אט
                    String textContent = text.toString().toLowerCase();
                    if (textContent.contains("הקלד הודעה") ||
                            textContent.contains("type a message") ||
                            textContent.contains("message") ||
                            textContent.contains("הודעה")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static String getSelectedMessage(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return null;

        Log.d(TAG, "Searching for selected message");

        // חיפוש ב-IDs רלוונטיים מכל גרסאות WhatsApp
        List<String> messageTextIds = new ArrayList<>();
        for (String packageName : WHATSAPP_PACKAGES) {
            messageTextIds.add(packageName + ":id/message_text");
            messageTextIds.add(packageName + ":id/conversation_text");
            messageTextIds.add(packageName + ":id/text_content");
            messageTextIds.add(packageName + ":id/text");
        }

        // חיפוש הודעות בכל המזהים האפשריים
        for (String messageTextId : messageTextIds) {
            List<AccessibilityNodeInfo> messageTextNodes = rootNode.findAccessibilityNodeInfosByViewId(messageTextId);
            if (messageTextNodes != null && !messageTextNodes.isEmpty()) {
                Log.d(TAG, "Found " + messageTextNodes.size() + " message nodes with ID: " + messageTextId);

                // First check for selected/focused messages
                for (AccessibilityNodeInfo node : messageTextNodes) {
                    if (node != null) {
                        if (node.isSelected() || node.isAccessibilityFocused() || node.isFocused()) {
                            CharSequence text = node.getText();
                            if (text != null) {
                                Log.d(TAG, "Found explicitly selected message: " + text);
                                return text.toString();
                            }
                        }

                        // Also check the direct parent for selection state
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null) {
                            if (parent.isSelected() || parent.isAccessibilityFocused() || parent.isFocused()) {
                                CharSequence text = node.getText();
                                if (text != null) {
                                    Log.d(TAG, "Found message with selected parent: " + text);
                                    return text.toString();
                                }
                            }
                        }
                    }
                }
            }
        }

        // חיפוש מיכלי הודעות בכל הגרסאות
        List<String> messageContainerIds = new ArrayList<>();
        for (String packageName : WHATSAPP_PACKAGES) {
            messageContainerIds.add(packageName + ":id/message_container");
            messageContainerIds.add(packageName + ":id/bubble_layout");
            messageContainerIds.add(packageName + ":id/message_layout");
        }

        // If no explicitly selected message, check for clicked/long-pressed messages
        for (String containerId : messageContainerIds) {
            List<AccessibilityNodeInfo> messageContainers = rootNode.findAccessibilityNodeInfosByViewId(containerId);
            if (messageContainers != null && !messageContainers.isEmpty()) {
                for (AccessibilityNodeInfo container : messageContainers) {
                    if (container.isSelected() || container.isAccessibilityFocused()) {
                        // Find text nodes within this container
                        for (int i = 0; i < container.getChildCount(); i++) {
                            AccessibilityNodeInfo child = container.getChild(i);
                            if (child != null) {
                                if (child.getText() != null) {
                                    String text = child.getText().toString();
                                    Log.d(TAG, "Found text in selected container: " + text);
                                    return text;
                                }

                                // Check deeper for text
                                for (int j = 0; j < child.getChildCount(); j++) {
                                    AccessibilityNodeInfo grandchild = child.getChild(j);
                                    if (grandchild != null && grandchild.getText() != null) {
                                        String text = grandchild.getText().toString();
                                        Log.d(TAG, "Found text in selected container grandchild: " + text);
                                        return text;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // בדיקת פוקוס נגישות
        AccessibilityNodeInfo focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focusedNode != null) {
            Log.d(TAG, "Found accessibility focused node: " + focusedNode.getClassName());

            // First check if the focused node itself has text
            if (focusedNode.getText() != null && !"".equals(focusedNode.getText().toString().trim())) {
                String text = focusedNode.getText().toString();
                Log.d(TAG, "Found accessibility focused message direct text: " + text);
                return text;
            }

            // Check for focused parent that might be a message container
            AccessibilityNodeInfo messageParent = findParentMessageContainer(focusedNode);
            if (messageParent != null) {
                String textFromContainer = extractTextFromMessageContainer(messageParent);
                if (textFromContainer != null) {
                    Log.d(TAG, "Found text from focused message container: " + textFromContainer);
                    return textFromContainer;
                }
            }

            // Check immediate children of focused node
            for (int i = 0; i < focusedNode.getChildCount(); i++) {
                AccessibilityNodeInfo child = focusedNode.getChild(i);
                if (child != null && child.getText() != null && !"".equals(child.getText().toString().trim())) {
                    String text = child.getText().toString();
                    Log.d(TAG, "Found text in focused node child: " + text);
                    return text;
                }

                // Also check grandchildren of focused node
                for (int j = 0; child != null && j < child.getChildCount(); j++) {
                    AccessibilityNodeInfo grandchild = child.getChild(j);
                    if (grandchild != null && grandchild.getText() != null &&
                            !"".equals(grandchild.getText().toString().trim())) {
                        String text = grandchild.getText().toString();
                        Log.d(TAG, "Found text in focused node grandchild: " + text);
                        return text;
                    }
                }
            }

            // If focused node doesn't have text but has a parent, check if parent has selected status
            AccessibilityNodeInfo parent = focusedNode.getParent();
            if (parent != null) {
                // Check all child nodes of the parent for text (which includes the focused node's siblings)
                for (int i = 0; i < parent.getChildCount(); i++) {
                    AccessibilityNodeInfo sibling = parent.getChild(i);
                    if (sibling != null && sibling.getText() != null &&
                            !"".equals(sibling.getText().toString().trim())) {
                        String text = sibling.getText().toString();
                        Log.d(TAG, "Found text in sibling of focused node: " + text);
                        return text;
                    }
                }
            }
        }

        // Also check for input focus specifically
        AccessibilityNodeInfo inputFocusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (inputFocusedNode != null) {
            CharSequence text = inputFocusedNode.getText();
            if (text != null && !"".equals(text.toString().trim())) {
                Log.d(TAG, "Found input focused message: " + text);
                return text.toString();
            }
        }

        // Try to find ticked/checked messages (for multi-select in WhatsApp)
        List<AccessibilityNodeInfo> tickNodes = findNodesByClassName(rootNode, "android.widget.ImageView");
        for (AccessibilityNodeInfo tickNode : tickNodes) {
            if (tickNode.isSelected() || tickNode.isChecked()) {
                // Find the associated message text
                AccessibilityNodeInfo parent = tickNode.getParent();
                if (parent != null) {
                    String textFromContainer = extractTextFromMessageContainer(parent);
                    if (textFromContainer != null) {
                        Log.d(TAG, "Found text from ticked message: " + textFromContainer);
                        return textFromContainer;
                    }
                }
            }
        }

        // Check for clicked/touched TextView nodes
        String selectedText = findSelectedTextInHierarchy(rootNode);
        if (selectedText != null) {
            return selectedText;
        }

        // Try to find any text in clicked areas
        List<AccessibilityNodeInfo> textViews = findTextViewsByType(rootNode);
        for (AccessibilityNodeInfo textView : textViews) {
            if (textView.isSelected() || textView.isAccessibilityFocused() || textView.isFocused()) {
                if (textView.getText() != null && !"".equals(textView.getText().toString().trim())) {
                    String text = textView.getText().toString();
                    Log.d(TAG, "Found text in selected TextView: " + text);
                    return text;
                }
            }
        }

        // תפריט הקשרי - מופיע כאשר לוחצים לחיצה ארוכה על הודעה
        List<String> contextMenuIds = new ArrayList<>();
        for (String packageName : WHATSAPP_PACKAGES) {
            contextMenuIds.add(packageName + ":id/message_actions_menu");
            contextMenuIds.add(packageName + ":id/popup_menu");
            contextMenuIds.add(packageName + ":id/context_menu");
        }

        for (String menuId : contextMenuIds) {
            List<AccessibilityNodeInfo> messageActionMenus = rootNode.findAccessibilityNodeInfosByViewId(menuId);
            if (messageActionMenus != null && !messageActionMenus.isEmpty()) {
                Log.d(TAG, "Found message action menu - message is selected");

                // If menu is open, try to find the last clicked message text
                List<AccessibilityNodeInfo> allTextViews = findTextViewsByType(rootNode);
                AccessibilityNodeInfo bestMatch = null;

                // Find first selected text view
                for (AccessibilityNodeInfo textView : allTextViews) {
                    if ((textView.isSelected() || textView.isAccessibilityFocused()) &&
                            textView.getText() != null &&
                            !"".equals(textView.getText().toString().trim())) {
                        bestMatch = textView;
                        break;
                    }
                }

                // If found, use it
                if (bestMatch != null && bestMatch.getText() != null) {
                    String text = bestMatch.getText().toString();
                    Log.d(TAG, "Found best match text when menu is open: " + text);
                    return text;
                }
            }
        }

        return null;
    }

    private static AccessibilityNodeInfo findParentMessageContainer(AccessibilityNodeInfo node) {
        if (node == null) return null;

        // Try to find parent that might be a message container
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) { // Check up to 5 levels up
            if (isLikelyMessageContainer(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isLikelyMessageContainer(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // בדיקה מקיפה יותר עבור מיכלי הודעות בכל הגרסאות
        String nodeId = node.getViewIdResourceName();
        if (nodeId != null) {
            for (String packageName : WHATSAPP_PACKAGES) {
                if (nodeId.contains(packageName + ":id/message_container") ||
                        nodeId.contains(packageName + ":id/bubble") ||
                        nodeId.contains(packageName + ":id/text_content") ||
                        nodeId.contains(packageName + ":id/message_text")) {
                    return true;
                }
            }
        }

        // Check class name for common container types
        CharSequence className = node.getClassName();
        if (className != null && (
                className.toString().contains("FrameLayout") ||
                        className.toString().contains("LinearLayout"))) {

            // Check if it has TextView children, which might indicate it's a message container
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null && "android.widget.TextView".equals(child.getClassName())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<AccessibilityNodeInfo> findTextViewsByType(AccessibilityNodeInfo root) {
        return findNodesByClassName(root, "android.widget.TextView");
    }

    private static List<AccessibilityNodeInfo> findNodesByClassName(AccessibilityNodeInfo root, String className) {
        if (root == null) return java.util.Collections.emptyList();

        List<AccessibilityNodeInfo> result = new java.util.ArrayList<>();
        findNodesByClassNameRecursive(root, className, result);
        return result;
    }

    private static void findNodesByClassNameRecursive(AccessibilityNodeInfo node, String className, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        if (className.equals(node.getClassName())) {
            result.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByClassNameRecursive(child, className, result);
            }
        }
    }

    private static String extractTextFromMessageContainer(AccessibilityNodeInfo container) {
        if (container == null) return null;

        // Try to find message_text within this container
        for (int i = 0; i < container.getChildCount(); i++) {
            AccessibilityNodeInfo child = container.getChild(i);
            if (child != null) {
                if (child.getText() != null && !"".equals(child.getText().toString().trim())) {
                    String text = child.getText().toString();
                    Log.d(TAG, "Extracted text from container child: " + text);
                    return text;
                }

                // Check grandchildren too
                for (int j = 0; j < child.getChildCount(); j++) {
                    AccessibilityNodeInfo grandchild = child.getChild(j);
                    if (grandchild != null && grandchild.getText() != null &&
                            !"".equals(grandchild.getText().toString().trim())) {
                        String text = grandchild.getText().toString();
                        Log.d(TAG, "Extracted text from container grandchild: " + text);
                        return text;
                    }
                }
            }
        }

        return null;
    }

    private static AccessibilityNodeInfo findParentNodeWithClass(AccessibilityNodeInfo node, String className) {
        if (node == null) return null;

        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (className.equals(current.getClassName())) {
                return current;
            }

            AccessibilityNodeInfo parent = current.getParent();
            current = parent;
        }

        return null;
    }

    private static String findSelectedTextInHierarchy(AccessibilityNodeInfo node) {
        if (node == null) return null;

        // Check if this node is selected and contains text
        if ((node.isSelected() || node.isAccessibilityFocused() || node.isFocused()) &&
                node.getText() != null &&
                !"".equals(node.getText().toString().trim()) &&
                ("android.widget.TextView".equals(node.getClassName()) ||
                        "android.widget.EditText".equals(node.getClassName()))) {
            String text = node.getText().toString();
            Log.d(TAG, "Found selected text in hierarchy: " + text);
            return text;
        }

        // Recursively check all children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = findSelectedTextInHierarchy(child);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    private static String findEditTextContent(AccessibilityNodeInfo node) {
        if (node == null) return null;

        if ("android.widget.EditText".equals(node.getClassName())) {
            if (node.getText() != null) {
                return node.getText().toString();
            }
            return "";
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = findEditTextContent(child);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }
}