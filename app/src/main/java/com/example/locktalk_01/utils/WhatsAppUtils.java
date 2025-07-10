package com.example.locktalk_01.utils;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Pair;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

public class WhatsAppUtils {
    private static final String TAG = "WhatsAppUtils";
    private static final int MIN_BUBBLE_SIZE = 150;
    private static final Pattern FAKE_TEXT_PATTERN = Pattern.compile("^[A-Za-z0-9+/=]{16,}$");

    // ----------------------------------------------------------- helpers ----
    public static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    /** נירמול שם שיחה: מסיר אימוג'ים, תווים חריגים וסימני פיסוק */
    public static String normalizeChatTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("[^\\p{L}\\p{Nd}\\s]", "")
                .replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                .trim();
    }

    /** נירמול טלפון לפורמט בינלאומי */
    public static String normalizePhone(String phone) {
        if (phone == null) return null;
        phone = phone.replaceAll("[^\\d+]", "");
        if (phone.startsWith("00")) phone = "+" + phone.substring(2);
        if (phone.startsWith("0")) phone = "+972" + phone.substring(1);
        if (phone.startsWith("972") && !phone.startsWith("+972")) phone = "+" + phone;
        while (phone.startsWith("++")) phone = phone.substring(1);
        return phone;
    }

    // ------------------------------------------------------ image bubbles ---
    public static List<Pair<AccessibilityNodeInfo, Rect>> findImageBubbleButtons(AccessibilityNodeInfo root) {
        List<Pair<AccessibilityNodeInfo, Rect>> out = new ArrayList<>();
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        findButtonsRec(root, out, seen);
        for (AccessibilityNodeInfo n : seen) {
            if (n != root) n.recycle(); // 🔥 RECYCLE
        }
        return out;
    }

    private static void findButtonsRec(AccessibilityNodeInfo n, List<Pair<AccessibilityNodeInfo, Rect>> out, Set<AccessibilityNodeInfo> seen) {
        if (n == null || seen.contains(n)) return;
        seen.add(n);
        CharSequence id = n.getViewIdResourceName();
        CharSequence desc = n.getContentDescription();
        if ("android.widget.Button".contentEquals(n.getClassName())
                && id != null && "com.whatsapp:id/image".contentEquals(id)
                && desc != null && desc.toString().contains("הגדלת התמונה")) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() >= MIN_BUBBLE_SIZE && b.height() >= MIN_BUBBLE_SIZE) {
                out.add(new Pair<>(n, b));
                Log.d(TAG, "Found image bubble BUTTON: " + b);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) findButtonsRec(n.getChild(i), out, seen);
    }

    public static List<Pair<String, Rect>> findImageLabels(AccessibilityNodeInfo root) {
        List<Pair<String, Rect>> out = new ArrayList<>();
        if (root == null) return out;
        Pattern imgPattern = Pattern.compile("^img\\d+$", Pattern.CASE_INSENSITIVE);
        findLabelsRec(root, out, imgPattern);
        for (Pair<String, Rect> p : out) {
            Log.d(TAG, "Found image label: " + p.first + " rect=" + p.second);
        }
        return out;
    }
    // תוספת סינון הודעות מוצפנות בלבד:
    public static List<AccessibilityNodeInfo> findEncryptedMessages(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Set<AccessibilityNodeInfo> vis = new HashSet<>();
        findEncRec(root, out, vis);
        return out;
    }
    private static void findEncRec(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, Set<AccessibilityNodeInfo> vis) {
        if (n == null || vis.contains(n)) return; vis.add(n);
        CharSequence id = n.getViewIdResourceName();
        if (id != null && "com.whatsapp:id/message_text".contentEquals(id)) {
            CharSequence cs = n.getText();
            if (cs != null && FAKE_TEXT_PATTERN.matcher(normalize(cs.toString())).matches()) {
                out.add(n);
                Log.d(TAG, "Encrypted text bubble: " + cs);
                return;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) findEncRec(n.getChild(i), out, vis);
    }


    private static void findLabelsRec(AccessibilityNodeInfo n, List<Pair<String, Rect>> out, Pattern imgPattern) {
        if (n == null) return;
        if ("android.widget.TextView".contentEquals(n.getClassName())) {
            CharSequence t = n.getText();
            if (t != null && imgPattern.matcher(t.toString().trim().toLowerCase()).matches()) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                out.add(new Pair<>(t.toString().trim().toLowerCase(), b));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            findLabelsRec(n.getChild(i), out, imgPattern);
        }
    }

    /** מיפוי שם שיחה למספר טלפון אוטומטי (Cache > אנשי קשר) */
    public static String getPhoneByPeerName(Context ctx, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String cleanName = normalizeChatTitle(name);

        String result = ctx.getSharedPreferences("PeerNames", Context.MODE_PRIVATE)
                .getString(cleanName, null);
        if (result != null) {
            Log.d(TAG, "getPhoneByPeerName: (CACHED) '" + cleanName + "' -> " + result);
            return normalizePhone(result);
        }

        String phoneFromContacts = findPhoneInContacts(ctx, cleanName);
        if (phoneFromContacts != null) {
            ctx.getSharedPreferences("PeerNames", Context.MODE_PRIVATE)
                    .edit()
                    .putString(cleanName, phoneFromContacts)
                    .apply();
            Log.d(TAG, "getPhoneByPeerName: (CONTACTS) '" + cleanName + "' -> " + phoneFromContacts);
            return normalizePhone(phoneFromContacts);
        }
        Log.d(TAG, "getPhoneByPeerName: '" + cleanName + "' -> null");
        return null;
    }

    public static String findPhoneInContacts(Context context, String chatTitle) {
        if (chatTitle == null) return null;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "NO PERMISSION TO READ CONTACTS!");
            return null;
        }
        String normalizedTitle = normalizeChatTitle(chatTitle);
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                @SuppressLint("Range") String contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
                @SuppressLint("Range") String displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                if (displayName != null && normalizeChatTitle(displayName).equalsIgnoreCase(normalizedTitle)) {
                    @SuppressLint("Range") int hasPhoneNumber = cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER));
                    if (hasPhoneNumber > 0) {
                        Cursor phones = cr.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                new String[]{contactId},
                                null
                        );
                        if (phones != null && phones.moveToFirst()) {
                            @SuppressLint("Range") String phoneNumber = phones.getString(phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                            phones.close();
                            return phoneNumber;
                        }
                        if (phones != null) phones.close();
                    }
                }
            }
            cursor.close();
        }
        return null;
    }

    // ------------------------------------------------------------- URI scan --
    public static Uri getImageUriFromNode(AccessibilityNodeInfo node) {
        return searchForUri(node, 0);
    }
    private static Uri searchForUri(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return null;
        String[] cand = new String[]{
                node.getContentDescription() != null ? node.getContentDescription().toString() : null,
                node.getText() != null ? node.getText().toString() : null,
                node.getExtras() != null && node.getExtras().containsKey("android.view.accessibility.AccessibilityNodeInfo.tooltipText") ?
                        String.valueOf(node.getExtras().get("android.view.accessibility.AccessibilityNodeInfo.tooltipText")) : null
        };
        for (String raw : cand) {
            if (raw == null) continue;
            int idx = raw.indexOf("content://");
            if (idx >= 0) {
                String sub = raw.substring(idx);
                int cut = 0;
                while (cut < sub.length()) {
                    char ch = sub.charAt(cut);
                    if (Character.isWhitespace(ch) || ch == '\u2028' || ch == '\u2029') break;
                    cut++;
                }
                String uriStr = sub.substring(0, cut);
                try {
                    Uri u = Uri.parse(uriStr);
                    if ("content".equals(u.getScheme())) {
                        Log.d(TAG, "URI found at depth=" + depth + ": " + u);
                        return u;
                    }
                } catch (Exception ignore) {}
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Uri u = searchForUri(node.getChild(i), depth + 1);
            if (u != null) return u;
        }
        if (depth == 0) {
            Uri u = searchForUri(node.getParent(), depth + 1);
            if (u != null) return u;
        }
        return null;
    }

    public static Bitmap extractBitmapFromNode(AccessibilityNodeInfo node, Context context) {
        try {
            Uri uri = WhatsAppUtils.getImageUriFromNode(node);
            if (uri != null) {
                InputStream input = context.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(input);
                if (input != null) input.close();
                return bmp;
            }
        } catch (Exception e) {
            Log.e("LockTalk-EXTRACT", "extractBitmapFromNode: failed", e);
        }
        return null;
    }

    public static List<AccessibilityNodeInfo> findAllTextBubbles(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        findAllTextBubblesRec(root, out);
        return out;
    }
    private static void findAllTextBubblesRec(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if ("android.widget.TextView".contentEquals(n.getClassName())) {
            CharSequence t = n.getText();
            if (t != null && t.length() > 0) out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            findAllTextBubblesRec(n.getChild(i), out);
        }
    }

    /** קבלת שם השיחה (עם נירמול, אם לא מוצא) */
    public static String getCurrentChatTitle(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name");
        if (candidates != null && !candidates.isEmpty()) {
            CharSequence cs = candidates.get(0).getText();
            if (cs != null && cs.length() > 1 && cs.length() < 48) {
                return cs.toString().trim();
            }
        }
        // fallback: קח טקסט ראשון בראש ה-TextView-ים
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo n = root.getChild(i);
            if (n != null && "android.widget.TextView".contentEquals(n.getClassName())
                    && n.getText() != null && n.getText().length() > 1 && n.getText().length() < 48) {
                return n.getText().toString().trim();
            }
        }
        return null;
    }

    public static boolean isWhatsAppPackage(String p) { return p != null && p.startsWith("com.whatsapp"); }

    public static String getImagePathFromUri(Context ctx, Uri uri) {
        if (uri == null) return null;
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            String[] proj = { android.provider.MediaStore.Images.Media.DATA };
            try (Cursor cursor = ctx.getContentResolver().query(uri, proj, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column_index = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA);
                    String path = cursor.getString(column_index);
                    if (path != null) return path;
                }
            } catch (Exception ignored) {}
        }
        return uri.getPath();
    }

    public static String getWhatsAppInputText(AccessibilityNodeInfo root) {
        if (root == null) return "";
        List<AccessibilityNodeInfo> ins = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (ins == null || ins.isEmpty()) return "";
        CharSequence cs = ins.get(0).getText();
        String t = cs != null ? cs.toString() : "";
        for (AccessibilityNodeInfo n : ins) if (n != null) n.recycle();
        return t;
    }

}
