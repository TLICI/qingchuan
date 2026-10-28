package com.qingchuan.connect;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** 画面相关偏好：全部只保存在本机，不涉及任何网络与账号。 */
final class AppPrefs {

    private static final String PREF = "app_prefs";

    /** 昼夜：跟随系统 / 白天 / 夜晚。 */
    static final int MODE_FOLLOW_SYSTEM = 0;
    static final int MODE_DAY = 1;
    static final int MODE_NIGHT = 2;

    int nightMode = MODE_FOLLOW_SYSTEM;
    boolean showHud = true;
    boolean ripples = true;
    boolean glows = true;
    boolean immersive = false;

    static AppPrefs load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        AppPrefs prefs = new AppPrefs();
        prefs.nightMode = p.getInt("night_mode", MODE_FOLLOW_SYSTEM);
        prefs.showHud = p.getBoolean("show_hud", true);
        prefs.ripples = p.getBoolean("ripples", true);
        prefs.glows = p.getBoolean("glows", true);
        prefs.immersive = p.getBoolean("immersive", false);
        return prefs;
    }

    void save(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt("night_mode", nightMode)
                .putBoolean("show_hud", showHud)
                .putBoolean("ripples", ripples)
                .putBoolean("glows", glows)
                .putBoolean("immersive", immersive)
                .apply();
    }

    /** 结合系统深色模式解析当前应为白天还是夜晚。 */
    static boolean isNight(Context context, int mode) {
        if (mode == MODE_DAY) {
            return false;
        }
        if (mode == MODE_NIGHT) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }
}
