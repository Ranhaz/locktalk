package com.example.locktalk_01.utils;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Pair;
import android.view.accessibility.AccessibilityNodeInfo;
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
// WhatsAppUtils.java

    public static String normalizeChatTitle(String title) {
        if (title == null) return "";
        // הסרת אימוג'ים, סימני פיסוק, תווי כיוון, רווחים כפולים
        String clean = title.replaceAll("[^\\p{L}\\p{Nd}\\s]", "")
                .replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return clean;
    }

    public static String getCurrentChatTitle(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name");
        if (candidates != null && !candidates.isEmpty()) {
            CharSequence cs = candidates.get(0).getText();
            if (cs != null && cs.length() > 1 && cs.length() < 48) {
                String t = cs.toString().trim();
                return normalizeChatTitle(t);
            }
        }
        // fallback: קח טקסט ראשון בראש ה-TextView-ים
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo n = root.getChild(i);
            if (n != null && "android.widget.TextView".contentEquals(n.getClassName())
                    && n.getText() != null && n.getText().length() > 1 && n.getText().length() < 48) {
                return normalizeChatTitle(n.getText().toString().trim());
            }
        }
        return null;
    }

    public static String normalizePhone(String phone) {
        if (phone == null) return "";
        String orig = phone;
        // השלב הראשון: נורמל רק תווים של מספרים ו+
        phone = phone.replaceAll("[^0-9+]", "");
        // תיקון לכל פורמט ישראלי סטנדרטי (כולל 05X וכו')
        if (phone.startsWith("00")) {
            phone = "+" + phone.substring(2);
        }
        if (phone.startsWith("05") && phone.length() == 10) {
            phone = "+972" + phone.substring(1);
        } else if (phone.startsWith("0") && phone.length() == 10) {
            phone = "+972" + phone.substring(1);
        } else if (phone.startsWith("5") && phone.length() == 9) {
            phone = "+972" + phone;
        } else if (phone.startsWith("5") && phone.length() == 10) {
            phone = "+972" + phone;
        } else if (phone.startsWith("972") && !phone.startsWith("+972")) {
            phone = "+" + phone;
        }
        // טיפולים לסימנים מיותרים
        while (phone.startsWith("++")) phone = phone.substring(1);
        // לוג לבדיקות
        Log.d("LT_PHONE", "normalizePhone: orig=" + orig + ", norm=" + phone);
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

    // WhatsAppUtils.java
    public static String findImagesCaption(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
        findAllTextBubblesRec(root, textNodes);
        Pattern imageKeyPattern = Pattern.compile("^[A-Za-z0-9+/=]{16,}$");

        for (AccessibilityNodeInfo n : textNodes) {
            CharSequence t = n.getText();
            if (t != null) {
                String txt = t.toString();
                String[] lines = txt.split("\n");
                int goodLines = 0;
                for (String line : lines) {
                    String trim = line.trim();
                    if (imageKeyPattern.matcher(trim).matches()) goodLines++;
                }
                if (goodLines >= 2 && goodLines == lines.length) {
                    return txt;
                }
            }
        }
        return null;
    }
    // שנה מ-private ל-public
    public static List<Pair<String, Rect>> findImageKeysWithPositions(AccessibilityNodeInfo root) {
        List<Pair<String, Rect>> result = new ArrayList<>();
        Pattern imageKeyPattern = Pattern.compile("^[A-Za-z0-9+/=]{16,}$");

        findImageKeysWithPositionsRec(root, result, imageKeyPattern);

        // הוסף מיון לפי מיקום (מלמעלה למטה, משמאל לימין)
        result.sort((a, b) -> {
            Rect rectA = a.second;
            Rect rectB = b.second;

            if (Math.abs(rectA.top - rectB.top) > 100) {
                return Integer.compare(rectA.top, rectB.top);
            }
            return Integer.compare(rectA.left, rectB.left);
        });

        Log.d("LT_IMAGE_KEYS", "Found " + result.size() + " image keys with positions");
        for (Pair<String, Rect> pair : result) {
            Log.d("LT_IMAGE_KEYS", "Key: " + pair.first + " at " + pair.second);
        }

        return result;
    }


    private static void findImageKeysWithPositionsRec(AccessibilityNodeInfo node,
                                                      List<Pair<String, Rect>> result, Pattern pattern) {
        if (node == null) return;

        if ("android.widget.TextView".equals(node.getClassName())) {
            CharSequence text = node.getText();
            if (text != null) {
                String textStr = text.toString().trim();
                if (pattern.matcher(textStr).matches()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    // וידוא שהגבולות תקינים
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        result.add(new Pair<>(textStr, bounds));
                        Log.d("LT_IMAGE_KEYS", "Found key: " + textStr + " at bounds: " + bounds);
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findImageKeysWithPositionsRec(child, result, pattern);
                child.recycle();
            }
        }
    }
    public static String findClosestImageKey(List<Pair<String, Rect>> availableKeys, Rect targetBounds) {
        if (availableKeys.isEmpty()) return null;

        String closestKey = null;
        double minDistance = Double.MAX_VALUE;

        int targetCenterX = targetBounds.centerX();
        int targetCenterY = targetBounds.centerY();

        Log.d("LT_CLOSEST_KEY", "Looking for key closest to bubble at " + targetBounds);

        for (Pair<String, Rect> keyPair : availableKeys) {
            Rect keyRect = keyPair.second;
            int keyCenterX = keyRect.centerX();
            int keyCenterY = keyRect.centerY();

            // אלגוריתם מרחק משוקלל - Y חשוב יותר, עם העדפה לכיוון למעלה
            double deltaX = Math.abs(targetCenterX - keyCenterX);
            double deltaY = targetCenterY - keyCenterY; // חיובי אם הכפתור מתחת למפתח

            // העדפה למפתחות שנמצאים מעל הכפתור
            double weightedDistance;
            if (deltaY > 0 && deltaY < 500) { // מפתח מעל הכפתור ברדיוס סביר
                weightedDistance = deltaX + deltaY * 0.5; // משקל נמוך יותר ל-Y כשמעל
            } else {
                weightedDistance = deltaX + Math.abs(deltaY) * 2; // משקל גבוה יותר כשלא מעל
            }

            Log.d("LT_CLOSEST_KEY", "Key " + keyPair.first + " at " + keyRect + " has distance " + weightedDistance);

            if (weightedDistance < minDistance) {
                minDistance = weightedDistance;
                closestKey = keyPair.first;
            }
        }

        Log.d("LT_CLOSEST_KEY", "Selected closest key: " + closestKey + " with distance: " + minDistance);
        return closestKey;
    }
    public static String findImageKeyAroundButton(AccessibilityNodeInfo buttonNode) {
        if (buttonNode == null) return null;

        Pattern imageKeyPattern = Pattern.compile("^[A-Za-z0-9+/=]{16,}$");

        // חיפוש ברמות שונות של ההיירכיה
        String foundKey = searchInNodeHierarchy(buttonNode, imageKeyPattern, 3); // 3 רמות מעלה

        if (foundKey != null) {
            Log.d("LT_IMAGE_SEARCH", "Found key around button: " + foundKey);
        }

        return foundKey;
    }

    private static String searchInNodeHierarchy(AccessibilityNodeInfo node, Pattern pattern, int levelsUp) {
        if (node == null || levelsUp < 0) return null;

        // חיפוש בצמת הנוכחי ובילדים שלו
        String result = searchInNodeAndChildren(node, pattern);
        if (result != null) return result;

        // חיפוש ברמה מעלה
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            result = searchInNodeHierarchy(parent, pattern, levelsUp - 1);
            parent.recycle();
        }

        return result;
    }

    private static String searchInNodeAndChildren(AccessibilityNodeInfo node, Pattern pattern) {
        if (node == null) return null;

        // בדיקת הצמת הנוכחי
        if ("android.widget.TextView".equals(node.getClassName())) {
            CharSequence text = node.getText();
            if (text != null) {
                String textStr = text.toString().trim();
                if (pattern.matcher(textStr).matches()) {
                    return textStr;
                }
            }
        }

        // בדיקת הילדים
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = searchInNodeAndChildren(child, pattern);
                child.recycle();
                if (result != null) return result;
            }
        }

        return null;
    }

    public static List<Pair<String, Pair<AccessibilityNodeInfo, Rect>>> pairImageKeysWithBubbles(
            AccessibilityNodeInfo root) {

        List<Pair<String, Rect>> imageKeysWithPositions = findImageKeysWithPositions(root);
        List<Pair<AccessibilityNodeInfo, Rect>> imageBubbles = findImageBubbleButtons(root);

        Log.d("LT_IMAGE_PAIRING", "Found " + imageKeysWithPositions.size() + " keys and " + imageBubbles.size() + " bubbles");

        // מיון הבועות לפי מיקום (מלמעלה למטה, משמאל לימין)
        imageBubbles.sort((a, b) -> {
            Rect rectA = a.second;
            Rect rectB = b.second;

            // קודם לפי Y (מלמעלה למטה) - רווח של 100 פיקסלים
            if (Math.abs(rectA.top - rectB.top) > 100) {
                return Integer.compare(rectA.top, rectB.top);
            }
            // אם באותו גובה, אז לפי X (משמאל לימין)
            return Integer.compare(rectA.left, rectB.left);
        });

        // מיון המפתחות לפי מיקום (מלמעלה למטה, משמאל לימין)
        imageKeysWithPositions.sort((a, b) -> {
            Rect rectA = a.second;
            Rect rectB = b.second;

            if (Math.abs(rectA.top - rectB.top) > 100) {
                return Integer.compare(rectA.top, rectB.top);
            }
            return Integer.compare(rectA.left, rectB.left);
        });

        List<Pair<String, Pair<AccessibilityNodeInfo, Rect>>> pairs = new ArrayList<>();

        // שיוך באמצעות מרחק מינימלי
        List<Pair<String, Rect>> availableKeys = new ArrayList<>(imageKeysWithPositions);

        for (Pair<AccessibilityNodeInfo, Rect> bubble : imageBubbles) {
            Rect bubbleBounds = bubble.second;
            String closestKey = findAndRemoveClosestKey(availableKeys, bubbleBounds);

            if (closestKey != null) {
                pairs.add(new Pair<>(closestKey, bubble));
                Log.d("LT_IMAGE_PAIRING", "Paired key " + closestKey + " with bubble at " + bubbleBounds);
            } else {
                Log.w("LT_IMAGE_PAIRING", "No key found for bubble at " + bubbleBounds);
            }
        }

        return pairs;
    }
    private static String findAndRemoveClosestKey(List<Pair<String, Rect>> availableKeys, Rect targetBounds) {
        if (availableKeys.isEmpty()) return null;

        String closestKey = null;
        Pair<String, Rect> closestPair = null;
        double minDistance = Double.MAX_VALUE;

        int targetCenterX = targetBounds.centerX();
        int targetCenterY = targetBounds.centerY();

        for (Pair<String, Rect> keyPair : availableKeys) {
            Rect keyRect = keyPair.second;
            int keyCenterX = keyRect.centerX();
            int keyCenterY = keyRect.centerY();

            // חישוב מרחק אוקלידי משוקלל - Y חשוב יותר מ-X
            double distanceX = Math.pow(targetCenterX - keyCenterX, 2);
            double distanceY = Math.pow(targetCenterY - keyCenterY, 2) * 2; // משקל כפול ל-Y
            double distance = Math.sqrt(distanceX + distanceY);

            if (distance < minDistance) {
                minDistance = distance;
                closestKey = keyPair.first;
                closestPair = keyPair;
            }
        }

        if (closestPair != null) {
            availableKeys.remove(closestPair); // הסרה מהרשימה כדי שלא יתחלק
        }

        return closestKey;
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
        if (root == null) {
            Log.w(TAG, "findImageLabels: root is null!");
            return out;
        }
        Pattern imgPattern = Pattern.compile("^img\\d+$", Pattern.CASE_INSENSITIVE);
        findLabelsRec(root, out, imgPattern);
        for (Pair<String, Rect> p : out) {
            Log.d(TAG, "findImageLabels: Found image label: " + p.first + " rect=" + p.second);
        }
        Log.d(TAG, "findImageLabels: Total found=" + out.size());
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
        if (name == null || name.trim().isEmpty()) {
            Log.d(TAG, "getPhoneByPeerName: name is null/empty");
            return null;
        }
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
    public static String normalizeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }
    public static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return "";

        Log.d(TAG, "normalizePhoneNumber input: " + phoneNumber);

        // טפל בפורמטים ישראליים רגילים
        String cleaned = phoneNumber.replaceAll("[^0-9+]", "");

        // אם זה מספר ישראלי מתחיל ב-05
        if (cleaned.startsWith("05") && cleaned.length() == 10) {
            cleaned = "+972" + cleaned.substring(1);
        }
        // אם זה מספר ישראלי מתחיל ב-0 (לא 05)
        else if (cleaned.startsWith("0") && cleaned.length() == 10) {
            cleaned = "+972" + cleaned.substring(1);
        }
        // אם זה מספר ללא קידומת בינלאומית ומתחיל ב-5 (מספר ישראלי)
        else if (cleaned.startsWith("5") && cleaned.length() == 9) {
            Log.d(TAG, "Adding +972 prefix to: " + cleaned);
            cleaned = "+972" + cleaned;
        }
        // אם זה מספר ללא קידומת בינלאומית ומתחיל ב-5 (מספר ישראלי) באורך 10
        else if (cleaned.startsWith("5") && cleaned.length() == 10) {
            Log.d(TAG, "Adding +972 prefix to 10-digit number: " + cleaned);
            cleaned = "+972" + cleaned;
        }
        // אם זה כבר מספר עם קידומת בינלאומית
        else if (cleaned.startsWith("+")) {
            // כבר נורמל
        }
        // אם זה מספר ללא + אבל עם קוד מדינה
        else if (cleaned.startsWith("972") && cleaned.length() >= 12) {
            cleaned = "+" + cleaned;
        }

        // ולידציה סופית
        if (cleaned.matches("\\+\\d{10,}")) {
            Log.d(TAG, "normalizePhoneNumber: '" + phoneNumber + "' -> '" + cleaned + "'");
            return cleaned;
        }

        Log.w(TAG, "normalizePhoneNumber: Failed to normalize '" + phoneNumber + "', result: '" + cleaned + "'");
        return "";
    }

    public static void cachePeerNameToPhone(Context ctx, String name, String phone) {
        if (ctx == null || name == null || phone == null) return;
        ctx.getSharedPreferences("PeerNames", Context.MODE_PRIVATE)
                .edit()
                .putString(name.trim(), phone)
                .apply();
        Log.d(TAG, "cachePeerNameToPhone: '" + name.trim() + "' -> '" + phone + "'");
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

    public static boolean isWhatsAppPackage(String p) { return p != null && p.startsWith("com.whatsapp"); }

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
