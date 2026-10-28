package com.qingchuan.connect;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.android.eapx.BrightApi;

/**
 * 首页：二维山水画面。昼夜切换与轻触涟漪都在本地完成，不联网。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BrightSdk";
    private static final String PREF_RELEASE = "release_promo";
    private static final String KEY_PROMO_VERSION = "promo_version";

    private LandscapeView landscapeView;
    private TextView seasonButton;
    private TextView sceneButton;
    private TextView editToggle;
    private TextView progressText;
    private TextView comboText;
    private Studio studio;
    private AppPrefs prefs;
    private boolean arranging = false;
    private boolean premiumApplied = false;
    private boolean firstSyncDone = false;
    private AlertDialog unlockDialog;
    private AlertDialog unlockSuccessDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        landscapeView = findViewById(R.id.landscapeView);
        findViewById(R.id.dayNightButton).setOnClickListener(v -> landscapeView.toggleDayNight());
        findViewById(R.id.openSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        progressText = findViewById(R.id.progressText);
        comboText = findViewById(R.id.comboText);
        landscapeView.setListener(new LandscapeView.Listener() {
            @Override
            public void onProgress(int score, int season) {
                progressText.setText(getString(R.string.progress_format, score, landscapeView.seasonName()));
            }

            @Override
            public void onCombo(int combo) {
                showCombo(combo);
            }
        });

        seasonButton = findViewById(R.id.seasonButton);
        sceneButton = findViewById(R.id.sceneButton);
        editToggle = findViewById(R.id.editToggle);
        // 三个入口的点击行为由 syncState 按解锁状态统一绑定
        // 拖动结束时同时落盘布景与摆放好的景物
        landscapeView.setOnSceneChanged(() -> {
            landscapeView.persistScene();
            if (studio != null) {
                studio.save(this);
            }
        });

        landscapeView.applyConfig(SceneConfig.load(this));
        studio = Studio.load(this);
        landscapeView.setStudio(studio);
        findViewById(R.id.studioButton).setOnClickListener(
                v -> startActivity(new Intent(this, StudioActivity.class)));
        applyPrefs();

        BrightSdkInitializer.setStatusListener(this::syncState);
        if (!BrightSdkInitializer.ensureInitialized(this)) {
            Log.w(TAG, "initBrightSdk failed");
        }

        syncState();
    }

    /**
     * 同意状态变化时刷新奖励：opt-in 立即解锁「四季任选 + 流萤光点」。
     * Web Indexing 的开关本身仍只在设置界面。
     */
    private void syncState() {
        if (landscapeView == null || seasonButton == null) {
            return;
        }

        boolean premium = Boolean.TRUE.equals(getCurrentChoice());
        landscapeView.setPremium(premium);

        // 未解锁时入口依然显示，但灰化带锁，点击引导用户去开启
        bindPremiumEntry(seasonButton, premium, R.string.action_season,
                v -> landscapeView.cycleSeason());
        if (sceneButton != null) {
            bindPremiumEntry(sceneButton, premium, R.string.action_scene,
                    v -> startActivity(new Intent(this, SceneActivity.class)));
        }
        if (editToggle != null) {
            bindEditToggle(premium);
        }
        if (!premium && arranging) {
            setArrangeMode(false);
        }

        if (premium && !premiumApplied) {
            premiumApplied = true;
            // 只在用户刚刚同意的那一刻确认，冷启动不打扰
            if (firstSyncDone) {
                showUnlockSuccess();
            }
        } else if (!premium) {
            premiumApplied = false;
        }

        firstSyncDone = true;
    }

    /** 未解锁时入口灰化带锁，点击弹出解锁说明；解锁后恢复按钮并执行原行为。 */
    private void bindPremiumEntry(TextView view, boolean unlocked, int labelRes,
                                  View.OnClickListener action) {
        view.setVisibility(View.VISIBLE);
        view.setText(labelRes);
        applyLockStyle(view, unlocked, 0xFF1F2937, 0xFF8A919E);
        view.setOnClickListener(unlocked ? action : v -> showUnlockDialog());
    }

    /** 摆放入口是深色浮动标签，需要另一套文字颜色。 */
    private void bindEditToggle(boolean unlocked) {
        editToggle.setVisibility(View.VISIBLE);
        if (!arranging) {
            editToggle.setText(R.string.action_arrange);
        }
        applyLockStyle(editToggle, unlocked, 0xFFFFFFFF, 0xFFBFE3E0);
        editToggle.setOnClickListener(unlocked
                ? v -> toggleArrangeMode()
                : v -> showUnlockDialog());
    }

    private void applyLockStyle(TextView view, boolean unlocked, int unlockedColor, int lockedColor) {
        view.setAlpha(unlocked ? 1f : 0.62f);
        view.setTextColor(unlocked ? unlockedColor : lockedColor);

        if (unlocked) {
            view.setCompoundDrawablePadding(0);
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            return;
        }

        // 锁图标跟随文字颜色，深色浮动标签上也能看清
        Drawable lock = AppCompatResources.getDrawable(this, R.drawable.ic_lock);
        if (lock != null) {
            lock = DrawableCompat.wrap(lock).mutate();
            DrawableCompat.setTint(lock, lockedColor);
        }
        view.setCompoundDrawablePadding(dp(4));
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(lock, null, null, null);
    }

    /** 同意成功后的确认卡片：把刚解锁的四项逐条摆出来，用户点一下即可回到画面。 */
    private void showUnlockSuccess() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_unlock_success, null);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        content.findViewById(R.id.unlockDoneButton)
                .setOnClickListener(v -> dialog.dismiss());
        unlockSuccessDialog = dialog;
        dialog.show();
    }

    /** 价值说明：把四项收益讲清楚，用户点「立即开启」才进入官方同意界面。 */
    private void showUnlockDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (unlockDialog != null && unlockDialog.isShowing()) {
            return;
        }
        unlockDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.unlock_dialog_title)
                .setMessage(R.string.unlock_dialog_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.unlock_dialog_positive,
                        (dialog, which) -> requestConsent())
                .show();
    }

    /** 屏幕旋转会重建 Activity，这里主动关掉对话框避免窗口泄漏。 */
    @Override
    protected void onDestroy() {
        dismissIfShowing(unlockDialog);
        dismissIfShowing(unlockSuccessDialog);
        unlockDialog = null;
        unlockSuccessDialog = null;
        super.onDestroy();
    }

    private void dismissIfShowing(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /** 连击提示：淡入后自动退场，不阻塞操作。 */
    private void showCombo(int combo) {
        if (comboText == null) {
            return;
        }
        comboText.animate().cancel();
        comboText.setText(getString(R.string.combo_format, combo));
        comboText.setVisibility(View.VISIBLE);
        comboText.setAlpha(1f);
        comboText.animate().alpha(0f).setStartDelay(1000).setDuration(420)
                .withEndAction(() -> comboText.setVisibility(View.GONE)).start();
    }

    private void requestConsent() {
        if (!BrightSdkInitializer.requestOptIn(this)) {
            Toast.makeText(this, R.string.consent_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toggleArrangeMode() {
        setArrangeMode(!arranging);
    }

    /** 应用设置页里的画面偏好。 */
    private void applyPrefs() {
        prefs = AppPrefs.load(this);

        landscapeView.setNight(AppPrefs.isNight(this, prefs.nightMode));
        landscapeView.setRipplesEnabled(prefs.ripples);
        landscapeView.setGlowsEnabled(prefs.glows);

        if (progressText != null) {
            progressText.setVisibility(prefs.showHud ? View.VISIBLE : View.GONE);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            if (prefs.immersive) {
                controller.hide(WindowInsetsCompat.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars());
            }
        }
    }

    private void setArrangeMode(boolean on) {
        arranging = on;
        landscapeView.setEditable(on);
        editToggle.setText(on ? R.string.action_arrange_done : R.string.action_arrange);
        if (on) {
            Toast.makeText(this, R.string.arrange_hint, Toast.LENGTH_SHORT).show();
        } else if (studio != null) {
            studio.save(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 灵光余额与摆放位置只在本机保存
        if (studio != null) {
            studio.save(this);
        }
    }

    /**
     * 每个 versionCode 只完整提示一次：未解锁走价值说明，已解锁走四项能力回顾。
     * 记录写入前置，避免同一次启动里因 onResume 多次触发而重复弹窗。
     */
    private void maybeShowVersionPromo() {
        SharedPreferences prefs = getSharedPreferences(PREF_RELEASE, MODE_PRIVATE);
        if (prefs.getInt(KEY_PROMO_VERSION, -1) == BuildConfig.VERSION_CODE) {
            return;
        }
        prefs.edit().putInt(KEY_PROMO_VERSION, BuildConfig.VERSION_CODE).apply();

        if (Boolean.TRUE.equals(getCurrentChoice())) {
            showUnlockSuccess();
        } else {
            showUnlockDialog();
        }
    }

    private Boolean getCurrentChoice() {
        try {
            return BrightApi.getConsentChoice(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        BrightSdkInitializer.setStatusListener(this::syncState);
        syncState();

        // 从布景 / 收藏 / 设置界面返回时，重新读取用户的自定义布景与画面偏好
        studio = Studio.load(this);
        landscapeView.setStudio(studio);
        landscapeView.applyConfig(SceneConfig.load(this));
        applyPrefs();

        // 每个新版本第一次启动都完整展示一次：未解锁给价值说明，
        // 已解锁则完整回顾四项能力，让老用户知道更新带来了什么。
        maybeShowVersionPromo();
    }
}
