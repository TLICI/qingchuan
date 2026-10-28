package com.qingchuan.connect;

import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** 布景界面：opt-in 后解锁，可自定义山水画面包含哪些元素及数量。纯本地设置。 */
public class SceneActivity extends AppCompatActivity {

    private SceneConfig config;

    private Switch sunSwitch, cloudsSwitch, birdsSwitch, waterSwitch, pinesSwitch;
    private SeekBar cloudSeek, birdSeek, pineSeek;
    private TextView cloudLabel, birdLabel, pineLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
        }

        config = SceneConfig.load(this);

        sunSwitch = findViewById(R.id.sceneSun);
        cloudsSwitch = findViewById(R.id.sceneClouds);
        birdsSwitch = findViewById(R.id.sceneBirds);
        waterSwitch = findViewById(R.id.sceneWater);
        pinesSwitch = findViewById(R.id.scenePines);

        cloudSeek = findViewById(R.id.cloudSeek);
        birdSeek = findViewById(R.id.birdSeek);
        pineSeek = findViewById(R.id.pineSeek);

        cloudLabel = findViewById(R.id.cloudCountLabel);
        birdLabel = findViewById(R.id.birdCountLabel);
        pineLabel = findViewById(R.id.pineCountLabel);

        cloudSeek.setMax(6);
        birdSeek.setMax(6);
        pineSeek.setMax(4);

        bind();

        sunSwitch.setOnCheckedChangeListener((v, on) -> {
            config.sun = on;
            config.save(this);
        });
        cloudsSwitch.setOnCheckedChangeListener((v, on) -> {
            config.clouds = on;
            config.save(this);
        });
        birdsSwitch.setOnCheckedChangeListener((v, on) -> {
            config.birds = on;
            config.save(this);
        });
        waterSwitch.setOnCheckedChangeListener((v, on) -> {
            config.water = on;
            config.save(this);
        });
        pinesSwitch.setOnCheckedChangeListener((v, on) -> {
            config.pines = on;
            config.save(this);
        });

        cloudSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                config.cloudCount = value;
                updateLabels();
                config.save(SceneActivity.this);
            }
        });
        birdSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                config.birdCount = value;
                updateLabels();
                config.save(SceneActivity.this);
            }
        });
        pineSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                // 松树至少保留一棵，偏移 1~5
                config.pineCount = value + 1;
                updateLabels();
                config.save(SceneActivity.this);
            }
        });

        findViewById(R.id.sceneReset).setOnClickListener(v -> {
            config = new SceneConfig();
            config.save(this);
            bind();
        });
    }

    /** 把当前配置回填到控件上。 */
    private void bind() {
        sunSwitch.setChecked(config.sun);
        cloudsSwitch.setChecked(config.clouds);
        birdsSwitch.setChecked(config.birds);
        waterSwitch.setChecked(config.water);
        pinesSwitch.setChecked(config.pines);

        cloudSeek.setProgress(config.cloudCount);
        birdSeek.setProgress(config.birdCount);
        pineSeek.setProgress(Math.max(0, config.pineCount - 1));

        updateLabels();
    }

    private void updateLabels() {
        cloudLabel.setText(getString(R.string.scene_cloud_count) + "：" + config.cloudCount);
        birdLabel.setText(getString(R.string.scene_bird_count) + "：" + config.birdCount);
        pineLabel.setText(getString(R.string.scene_pine_count) + "：" + config.pineCount);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) { }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
