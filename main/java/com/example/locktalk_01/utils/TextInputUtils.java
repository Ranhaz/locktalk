package com.example.locktalk_01.utils;

import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.ArrayList;

public class TextInputUtils {
    private static final String TAG = "TextInputUtils";

    public static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Encrypted Text", text);
        clipboard.setPrimaryClip(clip);
    }

    public static boolean replaceWhatsAppInputText(AccessibilityNodeInfo rootNode, String newText) {
        try {
            Log.d(TAG, "Attempting to replace WhatsApp input text with: " + newText);

            if (rootNode == null) {
                Log.e(TAG, "Root node is null");
                return false;
            }

            // Method 1: Direct replacement with standard WhatsApp edit text ID
            List<AccessibilityNodeInfo> entryNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
            if (entryNodes != null && !entryNodes.isEmpty() && entryNodes.get(0) != null) {
                AccessibilityNodeInfo entryNode = entryNodes.get(0);

                Bundle setArguments = new Bundle();
                setArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                boolean setSuccess = entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments);

                Log.d(TAG, "Set text to entry: " + setSuccess);
                return setSuccess;
            }

            // Method 2: Try alternative WhatsApp input field ID
            List<AccessibilityNodeInfo> messageNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
            if (messageNodes != null && !messageNodes.isEmpty() && messageNodes.get(0) != null) {
                AccessibilityNodeInfo messageNode = messageNodes.get(0);

                Bundle setArguments = new Bundle();
                setArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                boolean setSuccess = messageNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments);

                Log.d(TAG, "Set text to message_text: " + setSuccess);
                return setSuccess;
            }

            // Method 3: Generic search for any EditText in the hierarchy
            return findAndSetTextInEditText(rootNode, newText);
        } catch (Exception e) {
            Log.e(TAG, "Error replacing WhatsApp input text", e);
            return false;
        }
    }

    public static boolean clearAndSetWhatsAppInputText(AccessibilityNodeInfo rootNode, String newText) {
        try {
            // Method 4: Clear and set in sequence
            List<AccessibilityNodeInfo> entryNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
            if (entryNodes != null && !entryNodes.isEmpty() && entryNodes.get(0) != null) {
                AccessibilityNodeInfo entryNode = entryNodes.get(0);

                // First clear the field
                Bundle clearArguments = new Bundle();
                clearArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArguments);

                // Add slight delay
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {}

                // Then set the new text
                Bundle setArguments = new Bundle();
                setArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                boolean setSuccess = entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments);

                Log.d(TAG, "Clear and set text: " + setSuccess);
                return setSuccess;
            }

            // Method 5: Same with message_text
            List<AccessibilityNodeInfo> messageNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
            if (messageNodes != null && !messageNodes.isEmpty() && messageNodes.get(0) != null) {
                AccessibilityNodeInfo messageNode = messageNodes.get(0);

                Bundle clearArguments = new Bundle();
                clearArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                messageNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArguments);

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {}

                Bundle setArguments = new Bundle();
                setArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                boolean setSuccess = messageNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments);

                Log.d(TAG, "Clear and set message_text: " + setSuccess);
                return setSuccess;
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error in clearAndSetWhatsAppInputText", e);
            return false;
        }
    }

    public static boolean performTextReplacement(Context context, AccessibilityNodeInfo rootNode, String newText) {
        if (rootNode == null) return false;

        // Log attempt
        Log.d(TAG, "Attempting to perform text replacement with multiple methods");

        try {
            // Try all methods in sequence

            // Method 1: Direct replacement
            boolean directSuccess = replaceWhatsAppInputText(rootNode, newText);
            if (directSuccess) {
                Log.d(TAG, "Method 1 (direct replacement) succeeded");
                return true;
            }

            // Method 2: Clear and set
            boolean clearAndSetSuccess = clearAndSetWhatsAppInputText(rootNode, newText);
            if (clearAndSetSuccess) {
                Log.d(TAG, "Method 2 (clear and set) succeeded");
                return true;
            }

            // Method 3: Focus and paste
            List<AccessibilityNodeInfo> editTextList = new ArrayList<>();
            findAllEditableFields(rootNode, editTextList);

            if (!editTextList.isEmpty()) {
                // Copy text to clipboard first
                copyToClipboard(context, newText);

                // Small delay to ensure clipboard is updated
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {}

                // Try to paste into each editable field
                for (AccessibilityNodeInfo editText : editTextList) {
                    // First try to focus
                    boolean focusSuccess = editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Log.d(TAG, "Focus success: " + focusSuccess);

                    // Then try to paste
                    boolean pasteSuccess = editText.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    Log.d(TAG, "Paste success: " + pasteSuccess);

                    if (pasteSuccess) {
                        Log.d(TAG, "Method 3 (focus and paste) succeeded");
                        return true;
                    }
                }
            }

            // Method 4: Delayed attempt on main thread
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(() -> {
                AccessibilityNodeInfo newRootNode = null;
                try {
                    if (context instanceof android.accessibilityservice.AccessibilityService) {
                        newRootNode = ((android.accessibilityservice.AccessibilityService) context).getRootInActiveWindow();
                        if (newRootNode != null) {
                            replaceWhatsAppInputText(newRootNode, newText);
                        }
                    }
                } finally {
                    if (newRootNode != null) {
                        newRootNode.recycle();
                    }
                }
            }, 300);

            // Still return false for now since we can't know if delayed attempt will succeed
            Log.d(TAG, "All immediate methods failed, attempting delayed replacement");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error in performTextReplacement", e);
            return false;
        }
    }

    private static boolean findAndSetTextInEditText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;

        if ("android.widget.EditText".equals(node.getClassName())) {
            try {
                Bundle setArguments = new Bundle();
                setArguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments);

                Log.d(TAG, "Found EditText and set text: " + success);
                return success;
            } catch (Exception e) {
                Log.e(TAG, "Error setting text to EditText", e);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean success = findAndSetTextInEditText(child, text);
                if (success) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void findAllEditableFields(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> results) {
        if (node == null) return;

        if ("android.widget.EditText".equals(node.getClassName())) {
            results.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAllEditableFields(child, results);
            }
        }
    }
}
