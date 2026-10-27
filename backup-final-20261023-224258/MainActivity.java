package com.qingchuan.connect;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Locale;

/** Visible user controls only; the unmodified vendor SDK owns its own services. */
public final class MainActivity extends AppCompatActivity implements SdkBinding.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SwitchCompat switchView;
    private TextView statusView;
    private TextView trafficView;
    private boolean updatingSwitch;
    private boolean sdkReady;
    private long startRx;
    private long startTx;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 3000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        statusView = findViewById(R.id.status);
        trafficView = findViewById(R.id.traffic);
        switchView = findViewById(R.id.web_indexing);
        startRx = TrafficStats.getUidRxBytes(Process.myUid());
        startTx = TrafficStats.getUidTxBytes(Process.myUid());
        ((TextView) findViewById(R.id.details)).setText(
                getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + "\n"
                + BuildConfig.APPLICATION_ID + "\nBright SDK: " + SdkBinding.SDK_VERSION
                + "\nAAR SHA-256:\n" + SdkBinding.AAR_SHA256);
        try {
            SdkBinding.initialize(this, this);
            sdkReady = true;
        } catch (RuntimeException | LinkageError error) {
            showSdkError(error);
        }
        switchView.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return;
            if (!sdkReady) { refreshStatus(); return; }
            try {
                if (checked) {
                    // Never set consent ourselves. The vendor dialog makes that decision.
                    refreshStatus();
                    SdkBinding.showConsent(this);
                } else {
                    SdkBinding.optOut(this);
                }
                refreshStatus();
            } catch (RuntimeException | LinkageError error) { showSdkError(error); }
        });
        findViewById(R.id.off_now).setOnClickListener(v -> {
            try {
                SdkBinding.optOut(this);
                refreshStatus();
            } catch (RuntimeException | LinkageError error) { showSdkError(error); }
        });
        findViewById(R.id.refresh).setOnClickListener(v -> refreshStatus());
        findViewById(R.id.learn_more).setOnClickListener(v -> openLink(
                "https://bright-sdk.com/users#learn-more-about-bright-sdk-web-indexing"));
        findViewById(R.id.privacy).setOnClickListener(v -> openLink("https://bright-sdk.com/privacy-policy"));
        findViewById(R.id.terms).setOnClickListener(v -> openLink("https://bright-sdk.com/eula"));
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        SdkBinding.attach(this);
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        SdkBinding.detach(this);
        super.onPause();
    }

    @Override public void onConsentChanged() {
        if (!isFinishing() && !isDestroyed()) refreshStatus();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        Boolean consent = null;
        if (sdkReady) {
            try {
                consent = SdkBinding.getChoice(this);
                statusView.setText(consent == null ? R.string.status_none
                        : consent ? R.string.status_on : R.string.status_off);
            } catch (RuntimeException | LinkageError error) { showSdkError(error); }
        } else statusView.setText(R.string.status_error);
        updatingSwitch = true;
        switchView.setChecked(Boolean.TRUE.equals(consent));
        switchView.setEnabled(sdkReady);
        updatingSwitch = false;
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        if (rx < 0 || tx < 0 || startRx < 0 || startTx < 0) {
            trafficView.setText("UID 计数：设备不支持");
        } else {
            trafficView.setText(String.format(Locale.ROOT,
                    "本次 UID 接收：%,d 字节\n本次 UID 发送：%,d 字节",
                    Math.max(0L, rx - startRx), Math.max(0L, tx - startTx)));
        }
    }

    private void showSdkError(Throwable error) {
        sdkReady = false;
        // Do not log account material or vendor exception messages.
        Log.e("Qingchuan", "SDK operation failed: " + error.getClass().getSimpleName());
        if (statusView != null) statusView.setText(R.string.status_error);
        if (switchView != null) switchView.setEnabled(false);
    }

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
