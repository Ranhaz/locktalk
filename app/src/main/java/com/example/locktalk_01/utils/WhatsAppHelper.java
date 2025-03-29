
package com.example.locktalk_01.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class WhatsAppHelper {
    public static void openChatWithContact(Context context, String phoneNumber) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://wa.me/" + phoneNumber));
        context.startActivity(intent);
    }
}