package com.qingchuan.connect;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.eapx.BrightApi;
import com.android.eapx.Settings;

/**
 * 全应用共享的 Bright SDK 初始化入口，保证 SDK 只初始化一次。
 * 用前台 Activity 作为上下文，SDK 才能正常打开官方同意界面。
 */
final class BrightSdkInitializer {

    private static final String TAG = "BrightSdk";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean initialized = false;
    private static Runnable statusListener;

    private BrightSdkInitializer() {
    }

    /** 设置授权状态变化回调；回调会被切到主线程执行。 */
    static void setStatusListener(Runnable listener) {
        statusListener = listener;
    }

    /**
     * 确保 SDK 已初始化。返回 false 表示初始化失败。
     */
    static synchronized boolean ensureInitialized(Activity activity) {
        if (initialized) {
            return true;
        }
        try {
            Settings settings = new Settings(activity);

            // 不让 SDK 在初始化时自动接管弹窗；由界面在合适时机主动调用 showConsent。
            settings.setSkipConsent(true);

            // 不虚构无广告、金币等并不存在的收益。
            settings.setBenefit("To support Qingchuan");

            // 避免与宿主自身 JobScheduler ID 冲突。
            settings.setMinJobId(2000);
            settings.setMaxJobId(2999);

            settings.setOnStatusChange(choice -> {
                Runnable listener = statusListener;
                if (listener != null) {
                    MAIN.post(listener);
                }
            });

            BrightApi.init(activity, settings);
            initialized = true;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "initBrightSdk failed", t);
            return false;
        }
    }
}
