package com.qingchuan.connect;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.eapx.BrightApi;

/**
 * 首页：二维山水画面。昼夜切换与轻触涟漪都在本地完成，不联网。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BrightSdk";

    private LandscapeView landscapeView;
    private boolean autoConsentAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        landscapeView = findViewById(R.id.landscapeView);
        findViewById(R.id.dayNightButton).setOnClickListener(v -> landscapeView.toggleDayNight());
        findViewById(R.id.openSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        TextView progressText = findViewById(R.id.progressText);
        landscapeView.setListener((score, season) ->
                progressText.setText(getString(R.string.progress_format, score, landscapeView.seasonName())));

        BrightSdkInitializer.setStatusListener(this::syncState);
        if (!BrightSdkInitializer.ensureInitialized(this)) {
            Log.w(TAG, "initBrightSdk failed");
        }
    }

    private void syncState() {
        // 首页展示山水画面，Web Indexing 的状态与开关统一放在设置界面。
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

        // 首次进入且设备上还没有同意选择时，主动显示 Bright 官方同意界面，
        // 这是 Bright Implementation self-check 的要求。
        if (!autoConsentAttempted && getCurrentChoice() == null) {
            autoConsentAttempted = true;
            try {
                Log.i(TAG, "showConsent on start");
                BrightApi.showConsent(this);
            } catch (Throwable t) {
                Log.w(TAG, "showConsent on start failed", t);
            }
        }
    }
}
