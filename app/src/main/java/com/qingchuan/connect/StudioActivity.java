package com.qingchuan.connect;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 灵光收藏：把收集到的光点当货币，兑换景物与场景。
 * 全部本地存储，不联网、不上传。
 */
public class StudioActivity extends AppCompatActivity {

    private Studio studio;
    private TextView spiritText;
    private LinearLayout sceneContainer;
    private LinearLayout itemContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_studio);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
        }

        studio = Studio.load(this);
        spiritText = findViewById(R.id.studioSpirit);
        sceneContainer = findViewById(R.id.studioScenes);
        itemContainer = findViewById(R.id.studioItems);
        findViewById(R.id.studioBack).setOnClickListener(v -> finish());

        render();
    }

    private void render() {
        spiritText.setText(getString(R.string.studio_spirit, studio.spirit));
        renderScenes();
        renderItems();
    }

    private void renderScenes() {
        sceneContainer.removeAllViews();
        for (Studio.Scene s : Studio.SCENES) {
            View row = getLayoutInflater().inflate(R.layout.item_studio, sceneContainer, false);
            TextView name = row.findViewById(R.id.itemName);
            TextView hint = row.findViewById(R.id.itemHint);
            TextView action = row.findViewById(R.id.itemAction);

            name.setText(s.nameRes);
            hint.setText(sceneHintOf(s.id));

            boolean unlocked = studio.isSceneUnlocked(s.id);
            boolean current = s.id.equals(studio.currentScene());

            if (current) {
                action.setText(R.string.studio_in_use);
                action.setAlpha(0.5f);
                action.setClickable(false);
            } else if (unlocked) {
                action.setText(R.string.studio_use);
                action.setOnClickListener(v -> {
                    studio.buyScene(this, s);
                    render();
                });
            } else {
                action.setText(getString(R.string.studio_buy, s.price));
                action.setAlpha(studio.spirit >= s.price ? 1f : 0.5f);
                action.setOnClickListener(v -> {
                    if (studio.buyScene(this, s)) {
                        Toast.makeText(this,
                                getString(R.string.studio_scene_bought, getString(s.nameRes)),
                                Toast.LENGTH_SHORT).show();
                        render();
                    } else {
                        Toast.makeText(this, R.string.studio_not_enough, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            sceneContainer.addView(row);
        }
    }

    private void renderItems() {
        itemContainer.removeAllViews();
        for (Studio.Item item : Studio.ITEMS) {
            View row = getLayoutInflater().inflate(R.layout.item_studio, itemContainer, false);
            TextView name = row.findViewById(R.id.itemName);
            TextView hint = row.findViewById(R.id.itemHint);
            TextView action = row.findViewById(R.id.itemAction);

            name.setText(item.nameRes);
            hint.setText(item.hintRes);

            boolean unlocked = studio.isUnlocked(item.id);
            Studio.Placed placed = studio.findPlaced(item.id);

            if (!unlocked) {
                action.setText(getString(R.string.studio_buy, item.price));
                action.setAlpha(studio.spirit >= item.price ? 1f : 0.5f);
                action.setOnClickListener(v -> {
                    if (studio.buyItem(this, item)) {
                        Toast.makeText(this,
                                getString(R.string.studio_bought, getString(item.nameRes)),
                                Toast.LENGTH_SHORT).show();
                        render();
                    } else {
                        Toast.makeText(this, R.string.studio_not_enough, Toast.LENGTH_SHORT).show();
                    }
                });
            } else if (placed == null) {
                action.setText(R.string.studio_place);
                action.setAlpha(1f);
                action.setOnClickListener(v -> {
                    if (studio.addPlaced(item.id, defaultX(item.id), defaultY(item.id))) {
                        studio.save(this);
                        Toast.makeText(this, R.string.studio_placed, Toast.LENGTH_SHORT).show();
                        render();
                    } else {
                        Toast.makeText(this,
                                getString(R.string.studio_full, Studio.MAX_PLACED),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                action.setText(R.string.studio_remove);
                action.setAlpha(0.8f);
                action.setOnClickListener(v -> {
                    studio.removePlaced(placed);
                    studio.save(this);
                    Toast.makeText(this, R.string.studio_removed, Toast.LENGTH_SHORT).show();
                    render();
                });
            }
            itemContainer.addView(row);
        }
    }

    private int sceneHintOf(String id) {
        switch (id) {
            case "xueling": return R.string.scene_xueling_hint;
            case "chaosheng": return R.string.scene_chaosheng_hint;
            case "shengu": return R.string.scene_shengu_hint;
            default: return R.string.scene_qingchuan_hint;
        }
    }

    /** 新摆放景物的初始位置：水面物件靠下，山间物件靠中。 */
    private float defaultX(String id) {
        switch (id) {
            case "boat": return 0.30f;
            case "crane": return 0.68f;
            case "lantern": return 0.13f;
            case "pavilion": return 0.56f;
            default: return 0.42f;
        }
    }

    private float defaultY(String id) {
        switch (id) {
            case "boat": return 0.87f;
            case "crane": return 0.80f;
            case "lantern": return 0.83f;
            case "pavilion": return 0.88f;
            default: return 0.55f;
        }
    }
}
