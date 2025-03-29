package com.example.locktalk_01.utils;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

public class WhatsAppOpener {

    // שאר המתודות (openWhatsAppViaScheme וכו') יכולות להישאר כמו שהן

    public static void shareFileToWhatsApp(Context context, File fileToShare) {
        String pkg = detectWhatsAppPackage(context);
        if (pkg == null) {
            Toast.makeText(context,
                    "לא נמצא WhatsApp (רגיל או Business) במכשיר",
                    Toast.LENGTH_SHORT).show();
            openPlayStore(context);
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/pdf");
        shareIntent.setPackage(pkg);

        // כאן משתמשים ב-FileProvider
        Uri fileUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".provider",
                fileToShare
        );

        // מוודאים שיש הרשאת קריאה
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(shareIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context,
                    "לא ניתן לפתוח את WhatsApp. ייתכן שלא הותקנה?",
                    Toast.LENGTH_SHORT).show();
            openPlayStore(context);
        }
    }

    private static String detectWhatsAppPackage(Context context) {
        String[] possible = {
                "com.whatsapp",
                "com.whatsapp.w4b"
        };
        for (String pkg : possible) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
                if (info != null) {
                    return pkg;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // לא נמצאה
            }
        }
        return null;
    }

    private static void openPlayStore(Context context) {
        try {
            context.startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.whatsapp")
            ));
        } catch (Exception e) {
            context.startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")
            ));
        }
    }
}
