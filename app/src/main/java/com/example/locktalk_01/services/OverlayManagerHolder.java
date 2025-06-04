package com.example.locktalk_01.services;

import android.content.Context;
import android.view.WindowManager;

import com.example.locktalk_01.managers.OverlayManager;

/**
 * מחזיק מופע יחיד (singleton) של OverlayManager, כדי שנוכל לקרוא אליו ממקומות שונים.
 */
public class OverlayManagerHolder {
    private static OverlayManager instance;

    public static OverlayManager get() {
        return instance;
    }

    public static void init(Context ctx) {
        if (instance == null) {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            instance = new OverlayManager(ctx, wm);
        }
    }
}
