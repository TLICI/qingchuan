package com.qingchuan.connect;

import android.content.Context;
import android.content.SharedPreferences;

/** 布景配置：记录山水画面里各元素是否显示、数量多少。纯本地存储，不联网。 */
final class SceneConfig {

    private static final String PREF = "landscape_scene";

    boolean sun = true;
    boolean clouds = true;
    boolean birds = true;
    boolean water = true;
    boolean pines = true;

    int cloudCount = 5;
    int birdCount = 4;
    int pineCount = 3;

    /** 日月位置（相对屏幕宽高的比例），可拖动。 */
    float sunX = 0.78f;
    float sunY = 0.18f;

    /** 每棵松树的位置比例，可拖动。 */
    final float[] pineX = {0.10f, 0.17f, 0.90f, 0.72f, 0.35f};
    final float[] pineY = {0.99f, 1.00f, 0.99f, 1.00f, 1.00f};

    static SceneConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SceneConfig c = new SceneConfig();
        c.sun = p.getBoolean("sun", true);
        c.clouds = p.getBoolean("clouds", true);
        c.birds = p.getBoolean("birds", true);
        c.water = p.getBoolean("water", true);
        c.pines = p.getBoolean("pines", true);
        c.cloudCount = p.getInt("cloud_count", 5);
        c.birdCount = p.getInt("bird_count", 4);
        c.pineCount = p.getInt("pine_count", 3);
        c.sunX = p.getFloat("sun_x", 0.78f);
        c.sunY = p.getFloat("sun_y", 0.18f);
        readArray(p.getString("pine_x", null), c.pineX);
        readArray(p.getString("pine_y", null), c.pineY);
        return c;
    }

    private static void readArray(String raw, float[] target) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < Math.min(parts.length, target.length); i++) {
            try {
                target[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException ignored) {
                return;
            }
        }
    }

    private static String writeArray(float[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    void save(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("sun", sun)
                .putBoolean("clouds", clouds)
                .putBoolean("birds", birds)
                .putBoolean("water", water)
                .putBoolean("pines", pines)
                .putInt("cloud_count", cloudCount)
                .putInt("bird_count", birdCount)
                .putInt("pine_count", pineCount)
                .putFloat("sun_x", sunX)
                .putFloat("sun_y", sunY)
                .putString("pine_x", writeArray(pineX))
                .putString("pine_y", writeArray(pineY))
                .apply();
    }
}
