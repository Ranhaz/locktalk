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
import android.widget.Toast;
import android.graphics.Matrix;
import android.media.ExifInterface;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.locktalk_01.R;
import com.example.locktalk_01.services.MyAccessibilityService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import android.os.Build;

public class ImagePickerProxyActivity extends AppCompatActivity {
    private static final String PREF_LOGO_SET = "logoUris";
    private static final String PREF_LABEL_COUNTER = "imgLabelCounter";
    private Uri cameraImageUri;

    private static final int REQUEST_CAMERA_PERMISSION = 111;

    // ==== Dialog state ====
    private static boolean dialogOpen = false;
    public static boolean isDialogOpen() { return dialogOpen; }
    private static void setDialogOpen(boolean open) { dialogOpen = open; }

    // תוצאה מהגלריה
    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleUrisFromGallery(result.getData());
                } else {
                    finish();
                }
            });

    // תוצאה מהמצלמה
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
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDialogOpen(true);

        // Hide overlays while dialog is open
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null && svc.overlayManager != null) {
            svc.overlayManager.hideBubblesOnly();
        }

        // אם אין הרשאת מצלמה – בקש
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        showImagePickDialog();
    }

    /** דיאלוג בחירת פעולה (גלריה/מצלמה) **/
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

        // הסתרה יזומה (במקרה שהדיאלוג נפתח אחרי ה-onCreate)
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null && svc.overlayManager != null) {
            svc.overlayManager.hideBubblesOnly();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
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
        Set<String> logos = new HashSet<>(prefs.getStringSet(PREF_LOGO_SET, new HashSet<>()));
        int labelCounter = prefs.getInt(PREF_LABEL_COUNTER, 1);

        Log.d("ImagePickerProxy", "===> handleUrisForSending: picked.size=" + picked.size());

        for (int i = 0; i < picked.size(); i++) {
            Uri src = picked.get(i);
            try {
                String imgLabel = "img" + labelCounter;
                Bitmap original = null;
                String origPath = null;
                Log.d("ImagePickerProxy", "[START] Processing image: " + src);

                // קרא את התמונה מהמצלמה או מהגלריה
                if (cameraImageUri != null && src.equals(cameraImageUri)) {
                    File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            cameraImageUri.getLastPathSegment());
                    origPath = photoFile.getAbsolutePath();
                    Log.d("ImagePickerProxy", "[CAMERA] photoFile=" + origPath);
                    original = BitmapFactory.decodeFile(origPath);
                    original = fixImageOrientation(null, origPath, original);
                } else {
                    origPath = getPath(this, src);
                    Log.d("ImagePickerProxy", "[GALLERY] origPath=" + origPath);
                    if (origPath != null) {
                        original = BitmapFactory.decodeFile(origPath);
                        original = fixImageOrientation(null, origPath, original);
                    } else {
                        InputStream in = getContentResolver().openInputStream(src);
                        original = BitmapFactory.decodeStream(in);
                        Log.d("ImagePickerProxy", "[GALLERY] origPath=null, loaded from stream");
                    }
                }
                if (original == null) {
                    Log.e("ImagePickerProxy", "[ERROR] original is null for: " + src);
                    continue;
                }

                // שמירת קובץ מקורי מוצפן בתיקיית app (לא בגלריה)
                File dir = new File(getFilesDir(), "locktalk");
                if (!dir.exists()) dir.mkdirs();
                File orig = File.createTempFile("orig_", ".jpg", dir);
                try (OutputStream out = new FileOutputStream(orig)) {
                    original.compress(Bitmap.CompressFormat.JPEG, 98, out);
                }
                Log.d("ImagePickerProxy", "[SAVE] original saved to: " + orig.getAbsolutePath());

                // יצירת לוגו בגודל התמונה
                Bitmap logoBmp = BitmapFactory.decodeResource(getResources(), R.drawable.locktalk_logo);
                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBmp, original.getWidth(), original.getHeight(), true);

                // שמירת placeholder מחוץ לגלריה
                File extDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (extDir != null && !extDir.exists()) extDir.mkdirs();
                File placeholderFile = File.createTempFile("locktalk_placeholder_", ".jpg", extDir);
                try (OutputStream out = new FileOutputStream(placeholderFile)) {
                    scaledLogo.compress(Bitmap.CompressFormat.JPEG, 98, out);
                }
                Uri placeholderUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", placeholderFile);

                Log.d("ImagePickerProxy", "[PLACEHOLDER] placeholderUri: " + placeholderUri);

                logos.add(placeholderUri.toString());
                SharedPreferences creds = getSharedPreferences("UserCredentials", MODE_PRIVATE);
                String currentPersonalCode = creds.getString("personalCode", "");

                Log.d("ImagePickerProxy", "[PREFS] imgLabel: " + imgLabel + " orig=" + orig.getAbsolutePath() + " placeholderUri=" + placeholderUri + " personalCode=" + currentPersonalCode);

                prefs.edit()
                        .putStringSet(PREF_LOGO_SET, logos)
                        .putString("origPath_for_" + imgLabel, orig.getAbsolutePath())
                        .putString("pendingImageUri", placeholderUri.toString())
                        .apply();

                MyAccessibilityService svc = MyAccessibilityService.getInstance();
                if (svc != null) svc.setEncryptedImageUri(placeholderUri.toString());

                // שליחת התמונה לווטסאפ עם קפצ'ן (תווית)
                Log.d("ImagePickerProxy", "[SEND] Sending placeholderUri=" + placeholderUri + " imgLabel=" + imgLabel);
                sendImageWithCaption(placeholderUri, imgLabel);

                labelCounter++;
            } catch (Exception ex) {
                Log.e("ImagePickerProxy", "שגיאה בשמירה ובמיפוי", ex);
                // ממשיכים הלאה, לא יוצאים מהלולאה
            }
        }
        prefs.edit().putInt(PREF_LABEL_COUNTER, labelCounter).apply();
        setDialogOpen(false);
        Log.d("ImagePickerProxy", "[END] Finished handleUrisForSending");
        finish();
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