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
     * 用户在应用内的解锁对话框里明确点了「立即开启」。
     * 从未选择过 → 打开 Bright 官方同意界面；曾经明确拒绝过 → 用 SDK 的 externalOptIn
     * （该接口正是为「用户已在应用内表达同意」的场景准备的，官方界面不会再重复弹出）。
     *
     * @return false 表示调用失败
     */
    static boolean requestOptIn(Activity activity) {
        try {
            Boolean choice = BrightApi.getConsentChoice(activity);
            if (Boolean.FALSE.equals(choice)) {
                BrightApi.externalOptIn(activity);
            } else {
                BrightApi.showConsent(activity);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestOptIn failed", t);
            return false;
        }
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

            // 这个文案会显示在 Bright 官方同意界面上，必须与实际奖励一致：
            // 用户同意后立即解锁「四季任选」「流萤光点」「自定义布景」「自由摆放」四项。
            settings.setBenefit("Unlock the Four Seasons selector, exclusive firefly light points, "
                    + "custom scene and free arrangement");

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
