package com.qingchuan.connect;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 灵光收藏：把收集到的光点当作货币，兑换画面元素与新场景。
 * 所有数据只保存在本机，不联网、不上传。
 */
final class Studio {

    private static final String PREF = "landscape_studio";

    /** 可兑换的画面元素。 */
    static final class Item {
        final String id;
        final int price;
        final int nameRes;
        final int hintRes;

        Item(String id, int price, int nameRes, int hintRes) {
            this.id = id;
            this.price = price;
            this.nameRes = nameRes;
            this.hintRes = hintRes;
        }
    }

    /** 可解锁的场景主题。qingchuan 默认拥有。 */
    static final class Scene {
        final String id;
        final int price;
        final int nameRes;

        Scene(String id, int price, int nameRes) {
            this.id = id;
            this.price = price;
            this.nameRes = nameRes;
        }
    }

    /** 已经摆在画面上的一个元素。x/y 为屏幕比例，scale 为大小倍率。 */
    static final class Placed {
        final String id;
        float x;
        float y;
        float scale;

        Placed(String id, float x, float y, float scale) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.scale = scale;
        }
    }

    static final Item[] ITEMS = {
            new Item("boat", 12, R.string.item_boat, R.string.item_boat_hint),
            new Item("lantern", 18, R.string.item_lantern, R.string.item_lantern_hint),
            new Item("crane", 24, R.string.item_crane, R.string.item_crane_hint),
            new Item("pavilion", 36, R.string.item_pavilion, R.string.item_pavilion_hint),
            new Item("waterfall", 48, R.string.item_waterfall, R.string.item_waterfall_hint)
    };

    static final Scene[] SCENES = {
            new Scene("qingchuan", 0, R.string.scene_qingchuan),
            new Scene("xueling", 40, R.string.scene_xueling),
            new Scene("chaosheng", 56, R.string.scene_chaosheng),
            new Scene("shengu", 72, R.string.scene_shengu)
    };

    /** 最多同时摆放的元素数量，避免画面过载。 */
    static final int MAX_PLACED = 8;

    int spirit = 0;
    int totalCollected = 0;
    String scene = "qingchuan";

    private final Set<String> unlocked = new LinkedHashSet<>();
    private final Set<String> scenes = new LinkedHashSet<>();
    private final List<Placed> placed = new ArrayList<>();

    static Studio load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Studio s = new Studio();
        s.spirit = p.getInt("spirit", 0);
        s.totalCollected = p.getInt("total_collected", 0);
        s.scene = p.getString("scene", "qingchuan");
        readSet(p.getString("unlocked", ""), s.unlocked);
        readSet(p.getString("scenes", ""), s.scenes);
        s.scenes.add("qingchuan");
        readPlaced(p.getString("placed", ""), s.placed);
        if (!s.isSceneUnlocked(s.scene)) {
            s.scene = "qingchuan";
        }
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt("spirit", spirit)
                .putInt("total_collected", totalCollected)
                .putString("scene", scene)
                .putString("unlocked", join(unlocked))
                .putString("scenes", join(scenes))
                .putString("placed", writePlaced(placed))
                .apply();
    }

    boolean isUnlocked(String id) {
        return unlocked.contains(id);
    }

    boolean isSceneUnlocked(String id) {
        return "qingchuan".equals(id) || scenes.contains(id);
    }

    String currentScene() {
        return scene;
    }

    /** 0=晴川 1=雪岭 2=潮生 3=深谷，供绘制层选择调色板与地平线。 */
    int sceneIndex() {
        for (int i = 0; i < SCENES.length; i++) {
            if (SCENES[i].id.equals(scene)) {
                return i;
            }
        }
        return 0;
    }

    List<Placed> placed() {
        return placed;
    }

    boolean addPlaced(String id, float x, float y) {
        if (placed.size() >= MAX_PLACED || findPlaced(id) != null) {
            return false;
        }
        placed.add(new Placed(id, x, y, 1f));
        return true;
    }

    /** 同一种景物当前只摆放一个，找到就返回，用于显示「收起」。 */
    Placed findPlaced(String id) {
        for (Placed p : placed) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    boolean removePlaced(Placed p) {
        return placed.remove(p);
    }

    /** 兑换元素，成功时扣除灵光并落盘。 */
    boolean buyItem(Context context, Item item) {
        if (isUnlocked(item.id) || spirit < item.price) {
            return false;
        }
        spirit -= item.price;
        unlocked.add(item.id);
        save(context);
        return true;
    }

    /** 兑换并立即切换到该场景。 */
    boolean buyScene(Context context, Scene target) {
        if (isSceneUnlocked(target.id)) {
            scene = target.id;
            save(context);
            return true;
        }
        if (spirit < target.price) {
            return false;
        }
        spirit -= target.price;
        scenes.add(target.id);
        scene = target.id;
        save(context);
        return true;
    }

    /** 收集到光点时累加余额与累计进度。 */
    void gain(int count) {
        if (count <= 0) {
            return;
        }
        spirit += count;
        totalCollected += count;
    }

    static Item itemOf(String id) {
        for (Item it : ITEMS) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    private static void readSet(String raw, Set<String> target) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (!part.trim().isEmpty()) {
                target.add(part.trim());
            }
        }
    }

    private static String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static void readPlaced(String raw, List<Placed> target) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String chunk : raw.split(";")) {
            String[] parts = chunk.split(",");
            if (parts.length != 4) {
                continue;
            }
            try {
                target.add(new Placed(parts[0],
                        Float.parseFloat(parts[1]),
                        Float.parseFloat(parts[2]),
                        Float.parseFloat(parts[3])));
            } catch (NumberFormatException ignored) {
                // 跳过损坏的记录，不影响其余元素
            }
        }
    }

    private static String writePlaced(List<Placed> values) {
        StringBuilder sb = new StringBuilder();
        for (Placed p : values) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(String.format(Locale.US, "%s,%.4f,%.4f,%.3f",
                    p.id, p.x, p.y, p.scale));
        }
        return sb.toString();
    }
}
