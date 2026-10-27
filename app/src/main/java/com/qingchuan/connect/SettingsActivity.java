package com.qingchuan.connect;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.android.eapx.BrightApi;

/**
 * 应用设置界面：用户可以在这里查看并关闭 Web Indexing（撤销授权）。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "BrightSdk";

    private SwitchCompat webIndexingSwitch;
    private TextView statusText;

    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        webIndexingSwitch = findViewById(R.id.settingsWebIndexingSwitch);
        statusText = findViewById(R.id.settingsStatusText);

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
                    try {
                        BrightApi.showConsent(this);
                    } catch (Throwable t) {
                        Log.w(TAG, "showConsent from settings failed", t);
                        showError("无法打开授权页面");
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
            } catch (Throwable t) {
                Log.w(TAG, "optOut from settings failed", t);
                showError("暂时无法关闭 Web Indexing");
            }
        }
        syncState();
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

        } else if (Boolean.FALSE.equals(choice)) {
            setSwitchWithoutCallback(false);
            statusText.setText(R.string.status_disabled);

        } else {
            setSwitchWithoutCallback(false);
            statusText.setText(R.string.status_not_selected);
        }
    }

    private void setSwitchWithoutCallback(boolean checked) {
        updatingSwitch = true;
        webIndexingSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        BrightSdkInitializer.setStatusListener(this::syncState);

        if (webIndexingSwitch != null) {
            syncState();
        }
    }
}
