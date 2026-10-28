package com.qingchuan.connect;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.android.eapx.BrightApi;

/**
 * 设置界面：画面偏好、布景入口、关于信息，以及 Web Indexing 的开关与撤销授权。
 * 除 Web Indexing 由 Bright SDK 管理外，其余设置都只写本机。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "BrightSdk";

    private SwitchCompat webIndexingSwitch;
    private TextView statusText;
    private TextView valueText;
    private TextView nightModeValue;

    private AppPrefs prefs;
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = AppPrefs.load(this);
        // 设置页是浅色背景，状态栏图标需要深色才看得见
        useDarkStatusBarIcons(true);

        bindParticipationSection();
        bindDisplaySection();
        bindSceneSection();
        bindAboutSection();

        applyImmersive(prefs.immersive);
    }

    // ---------------------------------------------------------------- 参与

    private void bindParticipationSection() {
        View webRow = findViewById(R.id.rowWebIndexing);
        setRowText(webRow, R.string.web_indexing, 0);

        webIndexingSwitch = setupSwitchRow(
                R.id.rowWebIndexing, R.string.web_indexing, 0, false, null);
        statusText = findViewById(R.id.settingsStatusText);
        valueText = findViewById(R.id.settingsValueText);
        findViewById(R.id.settingsLearnMore).setOnClickListener(v -> openLearnMore());

        BrightSdkInitializer.setStatusListener(this::syncState);
        BrightSdkInitializer.ensureInitialized(this);
        syncState();

        webIndexingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitch) {
                return;
            }

            Boolean current = getCurrentChoice();

            if (isChecked) {
                if (!Boolean.TRUE.equals(current)) {
                    setSwitchWithoutCallback(false);
                    // 勾选开关本身就是明确同意，交给统一的授权入口处理
                    if (!BrightSdkInitializer.requestOptIn(this)) {
                        Toast.makeText(this, R.string.consent_failed, Toast.LENGTH_SHORT).show();
                        syncState();
                    }
                }
            } else {
                disableWebIndexing();
            }
        });

        findViewById(R.id.disableWebIndexingButton).setOnClickListener(v -> disableWebIndexing());
    }

    private void disableWebIndexing() {
        if (Boolean.TRUE.equals(getCurrentChoice())) {
            try {
                BrightApi.optOut(this);
                Toast.makeText(this, R.string.optout_done, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Log.w(TAG, "optOut from settings failed", t);
                Toast.makeText(this, R.string.optout_failed, Toast.LENGTH_SHORT).show();
            }
        }
        syncState();
    }

    /** 了解详情：跳到 Bright SDK 官方说明页。 */
    private void openLearnMore() {
        openUrl(getString(R.string.learn_more_url));
    }

    /** 常见问题：跳到本应用的 FAQ 页面。 */
    private void openFaq() {
        openUrl(getString(R.string.faq_url));
    }

    /** 用系统浏览器打开外部说明页，打不开时提示而不崩溃。 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Log.w(TAG, "open url failed: " + url, t);
            Toast.makeText(this, R.string.learn_more_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private Boolean getCurrentChoice() {
        try {
            return BrightApi.getConsentChoice(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void syncState() {
        Boolean choice = getCurrentChoice();

        if (Boolean.TRUE.equals(choice)) {
            setSwitchWithoutCallback(true);
            statusText.setText(R.string.status_enabled);
            valueText.setText(R.string.value_enabled);

        } else if (Boolean.FALSE.equals(choice)) {
            setSwitchWithoutCallback(false);
            statusText.setText(R.string.status_disabled);
            valueText.setText(R.string.value_disabled);

        } else {
            setSwitchWithoutCallback(false);
            statusText.setText(R.string.status_not_selected);
            valueText.setText(R.string.value_not_selected);
        }

        // 同意状态变化会影响「自定义布景」是否可用
        refreshSceneRow();
    }

    private void setSwitchWithoutCallback(boolean checked) {
        updatingSwitch = true;
        webIndexingSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    // ---------------------------------------------------------------- 画面

    private void bindDisplaySection() {
        nightModeValue = setupLinkRow(R.id.rowNightMode, R.string.night_mode,
                R.string.night_mode_summary, nightModeLabel(prefs.nightMode),
                v -> showNightModeDialog());

        setupSwitchRow(R.id.rowHud, R.string.pref_show_hud, R.string.pref_show_hud_summary,
                prefs.showHud, (button, checked) -> {
                    prefs.showHud = checked;
                    prefs.save(this);
                });

        setupSwitchRow(R.id.rowRipples, R.string.pref_ripples, R.string.pref_ripples_summary,
                prefs.ripples, (button, checked) -> {
                    prefs.ripples = checked;
                    prefs.save(this);
                });

        setupSwitchRow(R.id.rowGlows, R.string.pref_glows, R.string.pref_glows_summary,
                prefs.glows, (button, checked) -> {
                    prefs.glows = checked;
                    prefs.save(this);
                });

        setupSwitchRow(R.id.rowImmersive, R.string.pref_immersive, R.string.pref_immersive_summary,
                prefs.immersive, (button, checked) -> {
                    prefs.immersive = checked;
                    prefs.save(this);
                    applyImmersive(checked);
                });
    }

    private void showNightModeDialog() {
        CharSequence[] items = {
                getString(R.string.night_mode_follow),
                getString(R.string.night_mode_day),
                getString(R.string.night_mode_night)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.night_mode)
                .setSingleChoiceItems(items, prefs.nightMode, (dialog, which) -> {
                    prefs.nightMode = which;
                    prefs.save(this);
                    nightModeValue.setText(nightModeLabel(which));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String nightModeLabel(int mode) {
        if (mode == AppPrefs.MODE_DAY) {
            return getString(R.string.night_mode_day);
        }
        if (mode == AppPrefs.MODE_NIGHT) {
            return getString(R.string.night_mode_night);
        }
        return getString(R.string.night_mode_follow);
    }

    /** 浅色页面用深色状态栏图标，深色山水画面用浅色图标。 */
    private void useDarkStatusBarIcons(boolean dark) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(dark);
        }
    }

    private void applyImmersive(boolean on) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        if (on) {
            controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars());
        }
    }

    // ---------------------------------------------------------------- 布景

    private void bindSceneSection() {
        setupLinkRow(R.id.rowScene, R.string.scene_custom, 0, null, v -> {
            if (!isPremium()) {
                showUnlockDialog();
                return;
            }
            startActivity(new Intent(this, SceneActivity.class));
        });
        refreshSceneRow();

        setupLinkRow(R.id.rowResetScene, R.string.scene_reset_row, R.string.scene_reset_summary,
                null, v -> confirmResetScene());
    }

    /** 授权状态变化时只改布景行的说明文字。 */
    private void refreshSceneRow() {
        View row = findViewById(R.id.rowScene);
        if (row == null) {
            return;
        }
        TextView summary = row.findViewById(R.id.rowSummary);
        summary.setText(isPremium() ? R.string.scene_custom_summary : R.string.scene_locked_summary);
        summary.setVisibility(View.VISIBLE);
    }

    private void confirmResetScene() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_reset_scene_title)
                .setMessage(R.string.confirm_reset_scene_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    new SceneConfig().save(this);
                    Toast.makeText(this, R.string.scene_reset_done, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private boolean isPremium() {
        return Boolean.TRUE.equals(getCurrentChoice());
    }

    /** 未解锁入口的引导：说明奖励，再打开 Bright 官方同意界面。 */
    private void showUnlockDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unlock_dialog_title)
                .setMessage(R.string.unlock_dialog_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.unlock_dialog_positive, (dialog, which) -> {
                    if (!BrightSdkInitializer.requestOptIn(this)) {
                        Toast.makeText(this, R.string.consent_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- 关于

    private void bindAboutSection() {
        setupLinkRow(R.id.rowVersion, R.string.about_version, 0, BuildConfig.VERSION_NAME, null);

        setupLinkRow(R.id.rowPrivacy, R.string.about_privacy, R.string.about_privacy_summary,
                null, v -> new AlertDialog.Builder(this)
                        .setTitle(R.string.privacy_dialog_title)
                        .setMessage(R.string.privacy_dialog_body)
                        .setPositiveButton(R.string.action_confirm, null)
                        .show());

        setupLinkRow(R.id.rowFaq, R.string.about_faq, R.string.about_faq_summary,
                null, v -> openFaq());

        setupLinkRow(R.id.rowSdk, R.string.about_sdk, 0, getString(R.string.about_sdk_value), null);
    }

    // ---------------------------------------------------------------- 行工具

    private void setRowText(View row, int titleRes, int summaryRes) {
        TextView title = row.findViewById(R.id.rowTitle);
        TextView summary = row.findViewById(R.id.rowSummary);

        title.setText(titleRes);
        if (summaryRes != 0) {
            summary.setText(summaryRes);
            summary.setVisibility(View.VISIBLE);
        } else {
            summary.setVisibility(View.GONE);
        }
    }

    /** 开关行：整行点击也能切换。 */
    private SwitchCompat setupSwitchRow(int rowId, int titleRes, int summaryRes,
                                        boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        View row = findViewById(rowId);
        setRowText(row, titleRes, summaryRes);

        SwitchCompat toggle = row.findViewById(R.id.rowSwitch);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);

        row.setOnClickListener(v -> toggle.toggle());
        return toggle;
    }

    /** 跳转行：listener 为空时仅展示，不显示右侧箭头。 */
    private TextView setupLinkRow(int rowId, int titleRes, int summaryRes,
                                  CharSequence value, View.OnClickListener listener) {
        View row = findViewById(rowId);
        setRowText(row, titleRes, summaryRes);

        TextView valueView = row.findViewById(R.id.rowValue);
        TextView chevron = row.findViewById(R.id.rowChevron);

        if (value != null && value.length() > 0) {
            valueView.setText(value);
            valueView.setVisibility(View.VISIBLE);
        } else {
            valueView.setVisibility(View.GONE);
        }

        if (listener != null) {
            row.setOnClickListener(listener);
        } else {
            row.setClickable(false);
            row.setFocusable(false);
            chevron.setVisibility(View.GONE);
        }

        return valueView;
    }

    @Override
    protected void onResume() {
        super.onResume();

        BrightSdkInitializer.setStatusListener(this::syncState);

        if (webIndexingSwitch != null) {
            syncState();
        }

        // 从同意界面或布景页返回后，刷新入口文案
        if (prefs != null) {
            bindSceneSection();
        }
    }
}
