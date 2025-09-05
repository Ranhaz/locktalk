package com.example.locktalk_01.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import android.graphics.Matrix;
import android.media.ExifInterface;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ImagePickerProxyActivity extends AppCompatActivity {
    private static final String PREF_LOGO_SET = "logoUris";
    private Uri cameraImageUri;
    private static final int REQUEST_CAMERA_PERMISSION = 111;
    private static boolean dialogOpen = false;
    public static boolean isDialogOpen() { return dialogOpen; }
    private static void setDialogOpen(boolean open) { dialogOpen = open; }
    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleUrisFromGallery(result.getData());
                } else {
                    finish();
                }
            });
    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && cameraImageUri != null) {
                    ArrayList<Uri> uris = new ArrayList<>();
                    uris.add(cameraImageUri);
                    handleUrisForSending(uris);
                } else {
                    finish();
                }
            });

    @Override
    protected void onCreate( Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDialogOpen(true);
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null && svc.overlayManager != null) {
            svc.overlayManager.hideBubblesOnly();
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        showImagePickDialog();
    }
    private void showImagePickDialog() {
        boolean hasCameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        String[] options = hasCameraPerm ? new String[]{"בחר מהגלריה", "צלם תמונה"} : new String[]{"בחר מהגלריה"};

        new AlertDialog.Builder(this)
                .setTitle("בחר אפשרות")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) pickFromGallery();
                    else if (which == 1) takePhoto();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null && svc.overlayManager != null) {
            svc.overlayManager.hideBubblesOnly();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode,  String[] permissions,  int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showImagePickDialog();
            } else {
                Toast.makeText(this, "בלי הרשאת מצלמה לא ניתן לצלם", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        setDialogOpen(false);
    }
    @Override
    public void finish() {
        setDialogOpen(false);
        super.finish();
    }
    private void pickFromGallery() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        galleryLauncher.launch(Intent.createChooser(pick, "בחר תמונות"));
    }

    private void takePhoto() {
        boolean hasCameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasCameraPerm) {
            Toast.makeText(this, "אין הרשאת מצלמה", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent takePicture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePicture.resolveActivity(getPackageManager()) != null) {
            File photoFile;
            try {
                String name = "locktalk_" + System.currentTimeMillis();
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir != null && !dir.exists()) dir.mkdirs();
                photoFile = File.createTempFile(name, ".jpg", dir);
            } catch (Exception e) {
                Toast.makeText(this, "שגיאה בהכנת הקובץ", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (photoFile != null) {
                cameraImageUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".provider", photoFile);
                takePicture.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                takePicture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                cameraLauncher.launch(takePicture);
            }
        } else {
            Toast.makeText(this, "מצלמה לא זמינה", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void handleUrisFromGallery(Intent data) {
        ArrayList<Uri> list = new ArrayList<>();
        if (data.getClipData() != null) {
            int cnt = data.getClipData().getItemCount();
            for (int i = 0; i < cnt; i++) {
                list.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            list.add(data.getData());
        }
        if (!list.isEmpty()) {
            handleUrisForSending(list);
        } else {
            finish();
        }
    }

    private void handleUrisForSending(ArrayList<Uri> picked) {
        SharedPreferences prefs = getSharedPreferences("LockTalkPrefs", MODE_PRIVATE);
        Set<String> pendingKeys = new HashSet<>(prefs.getStringSet("pending_image_keys", new HashSet<>()));
        Set<String> logos = new HashSet<>(prefs.getStringSet(PREF_LOGO_SET, new HashSet<>()));

        SharedPreferences creds = getSharedPreferences("UserCredentials", MODE_PRIVATE);
        String myPhone = creds.getString("myPhone", null);

        String chatTitle = prefs.getString("lastChatTitle", null);
        if (chatTitle == null || chatTitle.isEmpty()) {
            MyAccessibilityService svc = MyAccessibilityService.getInstance();
            AccessibilityNodeInfo root = svc != null ? svc.getRootInActiveWindow() : null;
            if (root != null) {
                chatTitle = com.example.locktalk_01.utils.WhatsAppUtils.getCurrentChatTitle(root);
                if (chatTitle != null && !chatTitle.isEmpty()) {
                    prefs.edit().putString("lastChatTitle", chatTitle).apply();
                }
                root.recycle();
            }
        }
        if (chatTitle == null || chatTitle.isEmpty()) {
            Toast.makeText(this, "לא נמצא שם שיחה נוכחית. פתח/י שיחה בוואטסאפ לפני שליחת תמונה.", Toast.LENGTH_LONG).show();
            Log.e("ImagePickerProxy", "No chat title found. Exiting.");
            finish();
            return;
        }

        String peerPhone = com.example.locktalk_01.utils.WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
        if (peerPhone == null || peerPhone.isEmpty()) {
            peerPhone = com.example.locktalk_01.utils.WhatsAppUtils.findPhoneInContacts(this, chatTitle);
            if (peerPhone != null) {
                getSharedPreferences("PeerNames", MODE_PRIVATE)
                        .edit()
                        .putString(com.example.locktalk_01.utils.WhatsAppUtils.normalizeChatTitle(chatTitle), peerPhone)
                        .apply();
                peerPhone = com.example.locktalk_01.utils.WhatsAppUtils.getPhoneByPeerName(this, chatTitle);
            }
        }
        if (peerPhone == null || peerPhone.isEmpty()) {
            Toast.makeText(this, "לא נמצא מספר טלפון של הצד השני בשיחה: " + chatTitle, Toast.LENGTH_LONG).show();
            Log.e("ImagePickerProxy", "No peer phone found for chat: " + chatTitle);
            finish();
            return;
        }

        ArrayList<Uri> allPlaceholders = new ArrayList<>();
        ArrayList<String> allImageKeys = new ArrayList<>();

        final int MAX_IMAGE_DIM = 1280;
        final int MAX_FIRESTORE_ENC_IMAGE = 950_000; // אל תחרוג ממיליון בייט

        for (Uri src : picked) {
            try {
                Bitmap original = null;
                String origPath = null;
                if (cameraImageUri != null && src.equals(cameraImageUri)) {
                    File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            cameraImageUri.getLastPathSegment());
                    origPath = photoFile.getAbsolutePath();
                    original = BitmapFactory.decodeFile(origPath);
                    original = fixImageOrientation(null, origPath, original);
                } else {
                    origPath = getPath(this, src);
                    if (origPath != null) {
                        original = BitmapFactory.decodeFile(origPath);
                        original = fixImageOrientation(null, origPath, original);
                    } else {
                        try (InputStream in = getContentResolver().openInputStream(src)) {
                            if (in != null) {
                                original = BitmapFactory.decodeStream(in);
                            }
                        }
                    }
                }
                if (original == null) {
                    Log.e("ImagePickerProxy", "original bitmap is null for uri: " + src);
                    continue;
                }

                // 1. הקטנה אוטומטית (אם צריך)
                original = com.example.locktalk_01.utils.EncryptionHelper.scaleDownIfNeeded(original, MAX_IMAGE_DIM);

                String imageKey = com.example.locktalk_01.utils.EncryptionHelper.generateImageKey(original);

                // 2. שמירה מקורית (compressed 94 במקום 98)
                File dir = new File(getFilesDir(), "locktalk");
                if (!dir.exists()) dir.mkdirs();
                File orig = File.createTempFile("orig_", ".jpg", dir);
                try (OutputStream out = new FileOutputStream(orig)) {
                    original.compress(Bitmap.CompressFormat.JPEG, 94, out);
                }
                pendingKeys.add(imageKey);
                prefs.edit()
                        .putStringSet("pending_image_keys", pendingKeys)
                        .putString("origPath_for_" + imageKey, orig.getAbsolutePath())
                        .apply();

                // 3. הכנה להצפנה (compressed 94 במקום 98)
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                original.compress(Bitmap.CompressFormat.JPEG, 94, baos);
                byte[] plainBytes = baos.toByteArray();

                // 4. הצפנה
                byte[] encBytes = com.example.locktalk_01.utils.EncryptionHelper.encryptImage(plainBytes, myPhone, peerPhone);

                // 5. בדיקת מגבלת גודל - אם חריג, לא לשלוח!
                if (encBytes.length > MAX_FIRESTORE_ENC_IMAGE) {
                    Toast.makeText(this, "תמונה גדולה מדי לשליחה מוצפנת. יש לבחור תמונה קטנה/להקטין רזולוציה.", Toast.LENGTH_LONG).show();
                    Log.e("ImagePickerProxy", "Encrypted image too large (" + encBytes.length + ")");
                    continue; // דלג על התמונה הזאת
                }

                String base64Cipher = android.util.Base64.encodeToString(encBytes, android.util.Base64.NO_WRAP);
                Map<String, Object> entry = new HashMap<>();
                entry.put("encrypted", base64Cipher);
                entry.put("outgoing", true);
                entry.put("senderPhone", myPhone);

                String docIdSender = myPhone.replace("+972", "");
                String docIdReceiver = peerPhone.replace("+972", "");

                FirebaseFirestore db = FirebaseFirestore.getInstance();
                db.collection("users").document(docIdSender).collection("imageMap")
                        .document(imageKey).set(entry);
                db.collection("users").document(docIdReceiver).collection("imageMap")
                        .document(imageKey).set(entry);

                Bitmap logoBmp = BitmapFactory.decodeResource(getResources(), R.drawable.fakeimg);
                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBmp, original.getWidth(), original.getHeight(), true);
                File extDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (extDir != null && !extDir.exists()) extDir.mkdirs();
                File placeholderFile = File.createTempFile("locktalk_placeholder_", ".jpg", extDir);
                try (OutputStream out = new FileOutputStream(placeholderFile)) {
                    scaledLogo.compress(Bitmap.CompressFormat.JPEG, 94, out);
                }
                writeKeyToExif(placeholderFile, imageKey);

                Uri placeholderUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", placeholderFile);
                logos.add(placeholderUri.toString());
                prefs.edit()
                        .putStringSet(PREF_LOGO_SET, logos)
                        .putString("pendingImageUri", placeholderUri.toString())
                        .apply();

                MyAccessibilityService svc = MyAccessibilityService.getInstance();
                if (svc != null) svc.setEncryptedImageUri(placeholderUri.toString());

                allPlaceholders.add(placeholderUri);
                allImageKeys.add(imageKey);

            } catch (Exception ex) {
                Log.e("ImagePickerProxy", "שגיאה בשמירה ובמיפוי", ex);
            }
        }
        // שליחה מרוכזת
        if (!allPlaceholders.isEmpty()) {
            prefs.edit().putString("last_batch_image_keys", String.join(",", allImageKeys)).apply();
            if (allPlaceholders.size() == 1) {
                sendSingleImageWithCaption(allPlaceholders.get(0), allImageKeys.get(0));
            } else {
                sendMultipleImagesWithCaptions(allPlaceholders, allImageKeys);
            }
        } else {
            setDialogOpen(false);
            finish();
        }
    }
    private void sendSingleImageWithCaption(Uri imageUri, String imageKey) {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null) {
            ArrayList<Uri> singleUri = new ArrayList<>();
            ArrayList<String> singleKey = new ArrayList<>();
            singleUri.add(imageUri);
            singleKey.add(imageKey);
            svc.queueImagesForSequentialSending(singleUri, singleKey);
        }

        setDialogOpen(false);
        finish();
    }

    private void sendMultipleImagesWithCaptions(ArrayList<Uri> imageUris, ArrayList<String> imageKeys) {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null) {
            svc.queueImagesForSequentialSending(imageUris, imageKeys);
        }

        setDialogOpen(false);
        finish();
    }

    public static void writeKeyToExif(File imageFile, String imageKey) {
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, imageKey); // אפשר גם TAG_IMAGE_DESCRIPTION
            exif.saveAttributes();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static Bitmap fixImageOrientation(InputStream imageStream, String imagePath, Bitmap bitmap) {
        try {
            ExifInterface exif;
            if (imagePath != null) {
                exif = new ExifInterface(imagePath);
            } else if (imageStream != null) {
                exif = new ExifInterface(imageStream);
            } else {
                return bitmap;
            }
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                default:
                    return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            e.printStackTrace();
            return bitmap;
        }
    }
    private void sendImageWithCaption(Uri imageUri, String caption) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
        sendIntent.putExtra(Intent.EXTRA_TEXT, caption);
        sendIntent.setType("image/jpeg");
        sendIntent.setPackage("com.whatsapp");
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(sendIntent);
        } catch (Exception e) {
            sendIntent.setPackage(null);
            startActivity(Intent.createChooser(sendIntent, "שתף תמונה"));
        }
        setDialogOpen(false);
        finish();
    }
    public static String getPath(Context context, Uri uri) {
        String[] projection = {MediaStore.Images.Media.DATA};
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return null;
    }
}