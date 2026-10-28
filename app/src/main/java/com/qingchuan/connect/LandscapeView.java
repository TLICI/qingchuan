package com.qingchuan.connect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 二维山水视图：纯 Canvas 绘制，完全离线，不发起任何网络请求。 */
public class LandscapeView extends View {

    private static final class Cloud { float x, y, speed, scale; }
    private static final class Bird { float x, y, speed, phase; }
    private static final class Ripple { float x, y; long born; }
    /** 长风卷起的落叶与花瓣。 */
    private static final class Leaf { float x, y, vx, vy, size, rot, spin; }

    private static final int[] NIGHT = {0xFF25394B, 0xFF1B2C3B, 0xFF13232F, 0xFF0B1822};

    /** 春、夏、秋、冬 四套山色，按收集到的光点数解锁。 */
    private static final int[][] SEASON_MOUNTAINS = {
            {0xFFB7CDB0, 0xFF8FAF86, 0xFF6B8F63, 0xFF4A6B45},
            {0xFFA8C6B0, 0xFF7FA68A, 0xFF568063, 0xFF345A45},
            {0xFFD8C3A8, 0xFFC09A72, 0xFF9A6B45, 0xFF6E4528},
            {0xFFD3DEE6, 0xFFB0C2CE, 0xFF8AA0B0, 0xFF62788A}
    };
    /** 特殊场景的固定山色：雪岭、潮生、深谷。索引 0（晴川）随季节变化。 */
    private static final int[][] SCENE_MOUNTAINS = {
            null,
            {0xFFDCE7EF, 0xFFB9CBD8, 0xFF94A9B8, 0xFF6E8496},
            {0xFFB7D3DC, 0xFF8FB6C4, 0xFF62929F, 0xFF3E6A78},
            {0xFF9DB0A0, 0xFF75897A, 0xFF4F6455, 0xFF2E4038}
    };

    private static final int[] SEASON_SCORE = {0, 8, 20, 36};
    private static final String[] SEASON_NAMES = {"春", "夏", "秋", "冬"};

    /** 松树可选位置：x 比例、底部 y 比例、尺寸比例。 */
    private static final float[][] PINE_POS = {
            {0.10f, 0.99f, 0.24f},
            {0.17f, 1.00f, 0.17f},
            {0.90f, 0.99f, 0.28f},
            {0.72f, 1.00f, 0.20f},
            {0.35f, 1.00f, 0.15f}
    };

    /** 进度回调：收集数变化、季节解锁与连击时通知界面。 */
    public interface Listener {
        void onProgress(int score, int season);

        /** 连击达到 2 以上时回调，界面据此短暂提示。 */
        default void onCombo(int combo) { }
    }

    private static final class Glow {
        float x, y, r, phase;
        float vx, vy;
        long born;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] mountains = new Path[4];
    private final Path scratch = new Path();

    private final List<Cloud> clouds = new ArrayList<>();
    private final List<Bird> birds = new ArrayList<>();
    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Glow> glows = new ArrayList<>();
    private final List<Leaf> leaves = new ArrayList<>();
    private final List<Ripple> trail = new ArrayList<>();
    private final RectF oval = new RectF();
    private final Random random = new Random(20261026L);

    // 触摸与玩法状态
    private boolean touching = false;
    private boolean movedFar = false;
    private float touchX, touchY, downX, downY;
    private long downTime = 0L;
    private long windUntil = 0L;
    private float windOffset = 0f;
    private int combo = 0;
    private long lastCollectAt = 0L;

    private Shader skyShader, waterShader, glowShader;
    private boolean night = false;
    private long startTime = -1L;

    private Listener listener;
    private int score = 0;
    private int season = 0;
    private long lastSpawn = 0L;
    private boolean premium = false;
    private SceneConfig config = new SceneConfig();
    private Studio studio;
    private float pinchBaseDist = 0f;
    private float pinchBaseScale = 1f;
    private boolean seasonSeeded = false;
    private int lastSeasonScore = 0;
    private boolean suppressMove = false;

    private boolean editable = false;
    private int dragIndex = -1;
    private Runnable sceneChanged;
    private boolean ripplesEnabled = true;
    private boolean glowsEnabled = true;

    public LandscapeView(Context c) { super(c); }
    public LandscapeView(Context c, @Nullable AttributeSet a) { super(c, a); }
    public LandscapeView(Context c, @Nullable AttributeSet a, int d) { super(c, a, d); }

    public void toggleDayNight() {
        setNight(!night);
    }

    /** 由设置页决定的昼夜状态，可直接指定而不是切换。 */
    public void setNight(boolean value) {
        if (night == value) {
            return;
        }
        night = value;
        rebuildShaders(getWidth(), getHeight());
        invalidate();
    }

    public boolean isNight() {
        return night;
    }

    /** 关闭后轻触不再泛起涟漪，但仍可收集光点。 */
    public void setRipplesEnabled(boolean value) {
        ripplesEnabled = value;
        if (!value) {
            ripples.clear();
        }
        invalidate();
    }

    /** 关闭后山间不再浮现光点。 */
    public void setGlowsEnabled(boolean value) {
        glowsEnabled = value;
        if (!value) {
            glows.clear();
        }
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onProgress(spirit(), season);
        }
    }

    /** 绑定收藏数据：接管灵光余额与累计进度，并显示已摆放的景物。 */
    public void setStudio(Studio s) {
        studio = s;
        if (s != null) {
            score = s.totalCollected;
            // 只在首次绑定或收集数增长时推进季节，避免从商店返回时把手动选的季节重置
            if (!seasonSeeded || score > lastSeasonScore) {
                seasonSeeded = true;
                for (int i = SEASON_SCORE.length - 1; i >= 0; i--) {
                    if (score >= SEASON_SCORE[i]) {
                        season = i;
                        break;
                    }
                }
            }
            lastSeasonScore = score;
        }
        if (getWidth() > 0) {
            rebuildScene(getWidth(), getHeight());
        }
        invalidate();
    }

    /** 当前灵光余额，界面用它显示可兑换数量。 */
    public int spirit() {
        return studio != null ? studio.spirit : score;
    }

    /** 收藏或切换场景后调用，重新构建山形与配色。 */
    public void refreshScene() {
        if (getWidth() > 0) {
            rebuildScene(getWidth(), getHeight());
        }
        invalidate();
    }

    public String seasonName() {
        return SEASON_NAMES[season];
    }

    /** 是否解锁了「四季任选 + 流萤光点」这一 opt-in 奖励。 */
    public void setPremium(boolean value) {
        if (premium == value) {
            return;
        }
        premium = value;
        invalidate();
    }

    /** 手动切换季节，仅在 premium 解锁后由界面调用。 */
    public void cycleSeason() {
        season = (season + 1) % SEASON_NAMES.length;
        if (listener != null) {
            listener.onProgress(spirit(), season);
        }
        invalidate();
    }

    public int getScore() {
        return score;
    }

    /** 进入或退出「摆放」模式。摆放模式下可直接拖动日月与松树。 */
    public void setEditable(boolean value) {
        editable = value;
        dragIndex = -1;
        invalidate();
    }

    /** 拖动结束后回调，用于把新位置写盘。 */
    public void setOnSceneChanged(Runnable listener) {
        sceneChanged = listener;
    }

    public void persistScene() {
        config.save(getContext());
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        rebuildClouds(config.cloudCount);
        rebuildBirds(config.birdCount);
        rebuildScene(w, h);
    }

    /** 按当前场景重建山形、地平线与光影。 */
    private void rebuildScene(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float horizon = h * horizonRatio();
        int idx = studio == null ? 0 : studio.sceneIndex();
        // 深谷的山更高更密，潮生的山更低更缓
        float baseOffset = idx == 3 ? -0.06f : (idx == 2 ? 0.05f : 0f);
        for (int i = 0; i < mountains.length; i++) {
            float baseY = horizon * (0.60f + i * 0.14f) + h * baseOffset;
            float amp = h * (0.055f + i * 0.018f) * (idx == 3 ? 1.35f : 1f);
            float freq = (1.6f + i * 0.35f) * (idx == 3 ? 1.15f : 1f);
            mountains[i] = buildMountain(w, h, baseY, amp, freq, i * 1.3f);
        }
        rebuildShaders(w, h);
    }

    /** 场景决定地平线高度：潮生海面更高，深谷山体更低。 */
    private float horizonRatio() {
        int idx = studio == null ? 0 : studio.sceneIndex();
        switch (idx) {
            case 1: return 0.70f;
            case 2: return 0.60f;
            case 3: return 0.78f;
            default: return 0.72f;
        }
    }

    /** 应用用户自定义的布景配置。 */
    public void applyConfig(SceneConfig cfg) {
        config = cfg;
        rebuildClouds(cfg.cloudCount);
        rebuildBirds(cfg.birdCount);
        invalidate();
    }

    private void rebuildClouds(int count) {
        clouds.clear();
        for (int i = 0; i < count; i++) {
            Cloud c = new Cloud();
            c.x = random.nextFloat();
            c.y = 0.08f + random.nextFloat() * 0.26f;
            c.speed = 0.006f + random.nextFloat() * 0.012f;
            c.scale = 0.7f + random.nextFloat() * 0.8f;
            clouds.add(c);
        }
    }

    private void rebuildBirds(int count) {
        birds.clear();
        for (int i = 0; i < count; i++) {
            Bird b = new Bird();
            b.x = random.nextFloat();
            b.y = 0.16f + random.nextFloat() * 0.26f;
            b.speed = 0.02f + random.nextFloat() * 0.03f;
            b.phase = random.nextFloat() * 10f;
            birds.add(b);
        }
    }

    private void rebuildShaders(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float horizon = h * horizonRatio();
        skyShader = new LinearGradient(0, 0, 0, horizon,
                night ? 0xFF0B1626 : 0xFFC9E2EE,
                night ? 0xFF223A52 : 0xFFEEF6F6, Shader.TileMode.CLAMP);
        waterShader = new LinearGradient(0, horizon, 0, h,
                night ? 0xFF12212E : 0xFFD7E8EA,
                night ? 0xFF09141D : 0xFFAFC9CF, Shader.TileMode.CLAMP);
        float cx = config.sunX * w;
        float cy = config.sunY * h;
        float r = w * (night ? 0.045f : 0.055f);
        glowShader = new RadialGradient(cx, cy, r * 3.4f,
                night ? 0x338FB6D8 : 0x66FFE9A8, 0x00000000, Shader.TileMode.CLAMP);
    }

    private Path buildMountain(int w, int h, float baseY, float amp, float freq, float phase) {
        Path p = new Path();
        int steps = 56;
        p.moveTo(0, h);
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float y = baseY - amp * (0.55f * (float) Math.sin(t * Math.PI * 2 * freq + phase)
                    + 0.45f * (float) Math.sin(t * Math.PI * 2 * freq * 2.1f + phase * 1.7f));
            p.lineTo(t * w, y);
        }
        p.lineTo(w, h);
        p.close();
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || skyShader == null) return;

        if (startTime < 0) startTime = SystemClock.uptimeMillis();
        long now = SystemClock.uptimeMillis();
        float t = (now - startTime) / 1000f;
        float horizon = h * horizonRatio();
        float wind = windLevel();
        windOffset += wind * 0.0016f;

        skyPaint.setShader(skyShader);
        canvas.drawRect(0, 0, w, h, skyPaint);

        // 日月
        if (config.sun) {
            float cx = config.sunX * w;
            float cy = config.sunY * h;
            float r = w * (night ? 0.045f : 0.055f);
            glowPaint.setShader(glowShader);
            canvas.drawCircle(cx, cy, r * 3.4f, glowPaint);
            fill.setShader(null);
            fill.setColor(night ? 0xFFE9F0F8 : 0xFFFFF3C4);
            canvas.drawCircle(cx, cy, r, fill);
        }

        // 云
        if (config.clouds) {
            fill.setShader(null);
            fill.setColor(night ? 0x33AFC3D6 : 0xCCFFFFFF);
            for (Cloud c : clouds) {
                float px = ((c.x + t * c.speed + windOffset) % 1.2f - 0.1f) * w;
                float py = c.y * horizon;
                float cr = c.scale * w * 0.06f;
                canvas.drawCircle(px, py, cr, fill);
                canvas.drawCircle(px + cr * 0.9f, py + cr * 0.15f, cr * 0.8f, fill);
                canvas.drawCircle(px - cr * 0.9f, py + cr * 0.2f, cr * 0.7f, fill);
                canvas.drawCircle(px + cr * 0.2f, py - cr * 0.35f, cr * 0.7f, fill);
            }
        }

        // 远近山峦：夜晚统一用冷色，白天随季节变化；特殊场景各有固定调色板
        int sceneIdx = studio == null ? 0 : studio.sceneIndex();
        int[] colors;
        if (night) {
            colors = NIGHT;
        } else if (sceneIdx > 0) {
            colors = SCENE_MOUNTAINS[sceneIdx];
        } else {
            colors = SEASON_MOUNTAINS[season];
        }
        float reso = resonance();
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(null);
        for (int i = 0; i < mountains.length; i++) {
            fill.setColor(reso > 0f ? lift(colors[i], 0.22f * reso) : colors[i]);
            canvas.drawPath(mountains[i], fill);
        }

        // 水面
        if (config.water) {
            waterPaint.setShader(waterShader);
            canvas.drawRect(0, horizon, w, h, waterPaint);

            // 波光
            stroke.setShader(null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.2f));
            stroke.setColor(night ? 0x33DCE9F5 : 0x66FFFFFF);
            for (int i = 0; i < 7; i++) {
                float y = horizon + (i + 1) * (h - horizon) / 8f;
                float off = (float) Math.sin(t * 0.6f + i) * w * 0.02f;
                canvas.drawLine(w * 0.12f + off, y, w * 0.88f + off, y, stroke);
            }
        }

        // 飞鸟
        if (config.birds) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.4f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(night ? 0xAA9FB6CC : 0xAA4A5A66);
            for (Bird b : birds) {
                float bx = ((b.x + t * b.speed) % 1.15f - 0.05f) * w;
                float by = b.y * h;
                float flap = (float) Math.sin(t * 5f + b.phase) * dp(3f);
                float s = dp(6f);
                scratch.reset();
                scratch.moveTo(bx - s, by + flap);
                scratch.quadTo(bx - s * 0.4f, by - flap, bx, by);
                scratch.quadTo(bx + s * 0.4f, by - flap, bx + s, by + flap);
                canvas.drawPath(scratch, stroke);
            }
        }

        // 山间光点
        updateGlows(w, h);
        drawGlows(canvas, t);
        drawTrail(canvas, now);
        updateLeaves();
        drawLeaves(canvas);
        drawChargeRing(canvas, now);

        // 涟漪
        stroke.setStrokeWidth(dp(1.2f));
        for (int i = ripples.size() - 1; i >= 0; i--) {
            Ripple rp = ripples.get(i);
            float age = (SystemClock.uptimeMillis() - rp.born) / 1000f;
            if (age > 2.2f) { ripples.remove(i); continue; }
            int alpha = (int) (120 * (1f - age / 2.2f));
            stroke.setColor((alpha << 24) | 0x00FFFFFF);
            float radius = dp(8f) + age * dp(46f);
            canvas.drawCircle(rp.x, rp.y, radius, stroke);
            canvas.drawCircle(rp.x, rp.y, radius * 0.6f, stroke);
        }

        // 前景松树
        if (config.pines) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(night ? 0xFF081018 : 0xFF1E2A2E);
            int n = Math.min(config.pineCount, PINE_POS.length);
            for (int i = 0; i < n; i++) {
                drawPine(canvas, w * config.pineX[i], h * config.pineY[i], h * PINE_POS[i][2]);
            }
        }

        // 用户兑换后摆放的景物
        drawPlaced(canvas, w, h);

        // 摆放模式：给可拖动元素加提示圈
        if (editable) {
            stroke.setShader(null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.4f));
            stroke.setColor(0x99FFFFFF);
            if (config.sun) {
                canvas.drawCircle(config.sunX * w, config.sunY * h, dp(26f), stroke);
            }
            int n = Math.min(config.pineCount, PINE_POS.length);
            for (int i = 0; i < n; i++) {
                canvas.drawCircle(config.pineX[i] * w, config.pineY[i] * h - dp(10f), dp(22f), stroke);
            }
            if (studio != null) {
                List<Studio.Placed> list = studio.placed();
                for (int i = 0; i < list.size(); i++) {
                    Studio.Placed p = list.get(i);
                    float cx = p.x * w;
                    float cy = p.y * h - h * 0.07f * p.scale;
                    float r = dp(18f) + dp(10f) * p.scale;
                    boolean selected = dragIndex == 100 + i;
                    stroke.setColor(selected ? 0xFFFFE9A0 : 0x99FFFFFF);
                    canvas.drawCircle(cx, cy, r, stroke);
                    if (selected) {
                        // 选中时多一圈，提示可以双指缩放
                        canvas.drawCircle(cx, cy, r + dp(7f), stroke);
                    }
                }
            }
        }

        postInvalidateOnAnimation();
    }

    private void updateGlows(int w, int h) {
        if (!glowsEnabled) {
            glows.clear();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSpawn > 1100 && glows.size() < 5) {
            lastSpawn = now;
            spawnGlow(w, h);
        }
        for (int i = glows.size() - 1; i >= 0; i--) {
            Glow g = glows.get(i);
            // 被长风吹动时按速度漂移，随后逐渐停下
            if (g.vx != 0f || g.vy != 0f) {
                g.x += g.vx;
                g.y += g.vy;
                g.vx *= 0.93f;
                g.vy *= 0.93f;
                if (Math.abs(g.vx) < 0.06f) g.vx = 0f;
                if (Math.abs(g.vy) < 0.06f) g.vy = 0f;
            }
            if (now - g.born > 6000L) {
                glows.remove(i);
            }
        }
    }

    private void spawnGlow(int w, int h) {
        Glow g = new Glow();
        g.x = w * (0.10f + random.nextFloat() * 0.80f);
        g.y = h * (0.24f + random.nextFloat() * 0.40f);
        g.r = dp(5f + random.nextFloat() * 3f);
        g.phase = random.nextFloat() * 6.28f;
        g.born = SystemClock.uptimeMillis();
        glows.add(g);
    }

    private void drawGlows(Canvas canvas, float t) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        for (Glow g : glows) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(t * 3f + g.phase);
            // 未解锁为暖金色光点；解锁后变成青绿色的「流萤」
            fill.setColor(premium ? 0x3355FFD0 : 0x33FFE9A0);
            canvas.drawCircle(g.x, g.y, g.r * 3.2f * pulse, fill);
            fill.setColor(premium ? 0x88A8FFE0 : 0x88FFF0B8);
            canvas.drawCircle(g.x, g.y, g.r * 1.8f, fill);
            fill.setColor(premium ? 0xFFE4FFF6 : 0xFFFFFBE8);
            canvas.drawCircle(g.x, g.y, g.r, fill);
        }
    }

    /** 收集落点半径内的所有光点，返回数量。 */
    private int collectAt(float x, float y, float radiusDp) {
        float hit = dp(radiusDp);
        int count = 0;
        for (int i = glows.size() - 1; i >= 0; i--) {
            Glow g = glows.get(i);
            float dx = g.x - x;
            float dy = g.y - y;
            if (dx * dx + dy * dy <= hit * hit) {
                glows.remove(i);
                count++;
            }
        }
        if (count > 0) {
            registerCollect(count);
        }
        return count;
    }

    /** 统一结算：1.6 秒内再次收集算连击；未解锁时季节仍随总数推进。 */
    private void registerCollect(int count) {
        long now = SystemClock.uptimeMillis();
        combo = (now - lastCollectAt <= 1600L) ? combo + 1 : 1;
        lastCollectAt = now;
        score += count;

        if (studio != null) {
            studio.gain(count);
        }
        if (!premium) {
            for (int s = SEASON_SCORE.length - 1; s >= 0; s--) {
                if (score >= SEASON_SCORE[s]) {
                    season = s;
                    break;
                }
            }
        }
        if (listener != null) {
            listener.onProgress(spirit(), season);
            if (combo >= 2) {
                listener.onCombo(combo);
            }
        }
    }

    /** 指尖气场：把附近光点吸过来，一笔就能收走一串。 */
    private void attractGlows(float x, float y) {
        float reach = dp(92f);
        for (Glow g : glows) {
            float dx = x - g.x;
            float dy = y - g.y;
            float d = (float) Math.hypot(dx, dy);
            if (d > 0.001f && d < reach) {
                float pull = 0.34f * (1f - d / reach);
                g.x += dx * pull;
                g.y += dy * pull;
            }
        }
    }

    /** 长风：吹散光点、卷起落叶、惊动云与松，落在水面还荡开水纹。 */
    private void releaseWind(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        windUntil = SystemClock.uptimeMillis() + 2400L;

        for (Glow g : glows) {
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = dp(3f) + random.nextFloat() * dp(5f);
            g.vx = (float) Math.cos(angle) * speed;
            g.vy = (float) Math.sin(angle) * speed - dp(1.4f);
        }

        for (int i = 0; i < 20; i++) {
            Leaf l = new Leaf();
            l.x = x + (random.nextFloat() - 0.5f) * dp(40f);
            l.y = y + (random.nextFloat() - 0.5f) * dp(40f);
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = dp(1.6f) + random.nextFloat() * dp(3.6f);
            l.vx = (float) Math.cos(angle) * speed;
            l.vy = (float) Math.sin(angle) * speed - dp(1.2f);
            l.size = dp(3f) + random.nextFloat() * dp(3.5f);
            l.rot = random.nextFloat() * 360f;
            l.spin = (random.nextFloat() - 0.5f) * 18f;
            leaves.add(l);
        }

        if (y > h * horizonRatio()) {
            for (int i = 0; i < 3; i++) {
                Ripple rp = new Ripple();
                rp.x = x + (random.nextFloat() - 0.5f) * dp(70f);
                rp.y = y + random.nextFloat() * dp(30f);
                rp.born = SystemClock.uptimeMillis();
                ripples.add(rp);
            }
        }

        // 一阵风过后山间重新聚起光点，形成「起风—再收」的循环
        if (glowsEnabled) {
            while (glows.size() < 5) {
                spawnGlow(w, h);
            }
        }
        invalidate();
    }

    private void updateLeaves() {
        int w = getWidth();
        int h = getHeight();
        for (int i = leaves.size() - 1; i >= 0; i--) {
            Leaf l = leaves.get(i);
            l.x += l.vx;
            l.y += l.vy;
            l.vy += dp(0.05f);
            l.vx *= 0.985f;
            l.rot += l.spin;
            if (l.y > h + dp(30f) || l.x < -dp(30f) || l.x > w + dp(30f)) {
                leaves.remove(i);
            }
        }
    }

    private void drawLeaves(Canvas canvas) {
        if (leaves.isEmpty()) return;
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(seasonLeafColor());
        for (Leaf l : leaves) {
            canvas.save();
            canvas.rotate(l.rot, l.x, l.y);
            oval.set(l.x - l.size, l.y - l.size * 0.42f,
                    l.x + l.size, l.y + l.size * 0.42f);
            canvas.drawOval(oval, fill);
            canvas.restore();
        }
    }

    private int seasonLeafColor() {
        if (night) {
            return 0x77AFC6D8;
        }
        switch (season) {
            case 0: return 0xCC9FD9A8;
            case 1: return 0xCC7FC39A;
            case 2: return 0xCCD98F52;
            default: return 0xCCDCE9F2;
        }
    }

    private void spawnTrail(float x, float y) {
        Ripple tr = new Ripple();
        tr.x = x;
        tr.y = y;
        tr.born = SystemClock.uptimeMillis();
        trail.add(tr);
        while (trail.size() > 26) {
            trail.remove(0);
        }
    }

    private void drawTrail(Canvas canvas, long now) {
        if (trail.isEmpty()) return;
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        for (int i = trail.size() - 1; i >= 0; i--) {
            Ripple tr = trail.get(i);
            float age = (now - tr.born) / 1000f;
            if (age > 0.5f) {
                trail.remove(i);
                continue;
            }
            float k = 1f - age / 0.5f;
            int alpha = (int) (110 * k);
            fill.setColor((alpha << 24) | 0x007FFFE0);
            canvas.drawCircle(tr.x, tr.y, dp(4f) * k + dp(1f), fill);
        }
    }

    /** 蓄力环：按住不动会慢慢收紧，松手即起风。 */
    private void drawChargeRing(Canvas canvas, long now) {
        if (!touching || movedFar) return;
        long held = now - downTime;
        if (held < 360L) return;

        float p = Math.min(1f, (held - 360L) / 520f);
        int alpha = (int) (70 + 110 * p);
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.4f));
        stroke.setColor((alpha << 24) | 0x007FFFE0);
        canvas.drawCircle(touchX, touchY, dp(14f) + p * dp(52f), stroke);
        canvas.drawCircle(touchX, touchY, dp(6f) + p * dp(26f), stroke);
    }

    /** 0 表示无风，1 表示风头正劲。 */
    private float windLevel() {
        long now = SystemClock.uptimeMillis();
        if (now >= windUntil) return 0f;
        return (windUntil - now) / 2400f;
    }

    /** 连击带来的山色共鸣强度。 */
    private float resonance() {
        if (combo < 3) return 0f;
        if (SystemClock.uptimeMillis() - lastCollectAt > 1800L) return 0f;
        return Math.min(1f, (combo - 2) / 5f);
    }

    private int lift(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * amount);
        g = (int) (g + (255 - g) * amount);
        b = (int) (b + (255 - b) * amount);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** 绘制兑换后摆放的景物，尺寸随 scale 变化。 */
    private void drawPlaced(Canvas canvas, int w, int h) {
        if (studio == null) {
            return;
        }
        List<Studio.Placed> list = studio.placed();
        for (int i = 0; i < list.size(); i++) {
            Studio.Placed p = list.get(i);
            float x = p.x * w;
            float y = p.y * h;
            float size = h * 0.14f * p.scale;
            switch (p.id) {
                case "boat": drawBoat(canvas, x, y, size); break;
                case "lantern": drawLantern(canvas, x, y, size); break;
                case "crane": drawCrane(canvas, x, y, size); break;
                case "pavilion": drawPavilion(canvas, x, y, size); break;
                case "waterfall": drawWaterfall(canvas, x, y, size); break;
                default: break;
            }
        }
    }

    private void drawBoat(Canvas canvas, float x, float y, float size) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(night ? 0xFF12212E : 0xFF6B4F35);
        scratch.reset();
        scratch.moveTo(x - size * 0.55f, y - size * 0.08f);
        scratch.lineTo(x + size * 0.55f, y - size * 0.08f);
        scratch.lineTo(x + size * 0.34f, y + size * 0.10f);
        scratch.lineTo(x - size * 0.34f, y + size * 0.10f);
        scratch.close();
        // 船篷
        scratch.moveTo(x - size * 0.26f, y - size * 0.08f);
        scratch.quadTo(x, y - size * 0.40f, x + size * 0.26f, y - size * 0.08f);
        scratch.close();
        canvas.drawPath(scratch, fill);

        // 水面倒影
        fill.setColor(night ? 0x33122030 : 0x336B4F35);
        oval.set(x - size * 0.5f, y + size * 0.12f, x + size * 0.5f, y + size * 0.20f);
        canvas.drawOval(oval, fill);
    }

    private void drawLantern(Canvas canvas, float x, float y, float size) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(night ? 0xFF2A3844 : 0xFF8E9AA0);
        scratch.reset();
        scratch.addRect(x - size * 0.05f, y - size * 0.34f,
                x + size * 0.05f, y, Path.Direction.CW);
        scratch.addRect(x - size * 0.13f, y - size * 0.52f,
                x + size * 0.13f, y - size * 0.34f, Path.Direction.CW);
        canvas.drawPath(scratch, fill);

        // 灯芯与暖光，夜里更明显
        float glow = 0.65f + 0.35f * (float) Math.sin(SystemClock.uptimeMillis() / 420.0 + x);
        int alpha = (int) ((night ? 80 : 44) * glow);
        fill.setColor((alpha << 24) | 0x00FFD98A);
        canvas.drawCircle(x, y - size * 0.43f, size * 0.34f * glow, fill);
        fill.setColor(night ? 0xFFFFE0A0 : 0xFFFFF0C8);
        canvas.drawCircle(x, y - size * 0.43f, size * 0.07f, fill);
    }

    private void drawCrane(Canvas canvas, float x, float y, float size) {
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(size * 0.05f);
        stroke.setColor(night ? 0xFF7A8B99 : 0xFF6B6B60);

        // 腿
        canvas.drawLine(x - size * 0.04f, y - size * 0.16f, x - size * 0.08f, y, stroke);
        canvas.drawLine(x + size * 0.05f, y - size * 0.16f, x + size * 0.02f, y, stroke);
        // 颈
        canvas.drawLine(x + size * 0.10f, y - size * 0.26f,
                x + size * 0.20f, y - size * 0.52f, stroke);

        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(night ? 0xFFB9CBD8 : 0xFFF7F7F2);
        oval.set(x - size * 0.20f, y - size * 0.32f, x + size * 0.15f, y - size * 0.13f);
        canvas.drawOval(oval, fill);
        canvas.drawCircle(x + size * 0.21f, y - size * 0.54f, size * 0.055f, fill);
        // 鹤顶
        fill.setColor(night ? 0xFFB0705A : 0xFFC4685A);
        canvas.drawCircle(x + size * 0.23f, y - size * 0.56f, size * 0.024f, fill);
    }

    private void drawPavilion(Canvas canvas, float x, float y, float size) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);

        fill.setColor(night ? 0xFF3A2C22 : 0xFF8A5F3E);
        scratch.reset();
        scratch.addRect(x - size * 0.22f, y - size * 0.34f,
                x - size * 0.15f, y, Path.Direction.CW);
        scratch.addRect(x + size * 0.15f, y - size * 0.34f,
                x + size * 0.22f, y, Path.Direction.CW);
        canvas.drawPath(scratch, fill);

        // 双层屋檐
        fill.setColor(night ? 0xFF1B2A33 : 0xFF3F5548);
        scratch.reset();
        scratch.moveTo(x - size * 0.44f, y - size * 0.34f);
        scratch.lineTo(x + size * 0.44f, y - size * 0.34f);
        scratch.lineTo(x + size * 0.30f, y - size * 0.50f);
        scratch.lineTo(x - size * 0.30f, y - size * 0.50f);
        scratch.close();
        scratch.moveTo(x - size * 0.30f, y - size * 0.50f);
        scratch.lineTo(x + size * 0.30f, y - size * 0.50f);
        scratch.lineTo(x, y - size * 0.68f);
        scratch.close();
        canvas.drawPath(scratch, fill);
    }

    private void drawWaterfall(Canvas canvas, float x, float y, float size) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(night ? 0x66C8E0F0 : 0xAAFFFFFF);
        scratch.reset();
        scratch.moveTo(x - size * 0.06f, y - size * 0.86f);
        scratch.lineTo(x + size * 0.06f, y - size * 0.86f);
        scratch.lineTo(x + size * 0.16f, y);
        scratch.lineTo(x - size * 0.16f, y);
        scratch.close();
        canvas.drawPath(scratch, fill);

        // 落水处的水花
        fill.setColor(night ? 0x55DCE9F5 : 0x99FFFFFF);
        oval.set(x - size * 0.22f, y - size * 0.06f, x + size * 0.22f, y + size * 0.06f);
        canvas.drawOval(oval, fill);
    }

    private void drawPine(Canvas canvas, float x, float baseY, float size) {
        float trunk = size * 0.06f;
        float wind = windLevel();
        float phase = SystemClock.uptimeMillis() / 150f + x * 0.02f;
        scratch.reset();
        scratch.addRect(x - trunk / 2, baseY - size * 0.25f, x + trunk / 2, baseY, Path.Direction.CW);
        for (int i = 0; i < 3; i++) {
            float cy = baseY - size * 0.25f - i * size * 0.24f;
            float half = size * (0.30f - i * 0.07f);
            // 有风时树梢偏移，越靠上越明显
            float sway = wind * size * 0.06f * (float) Math.sin(phase) * (0.4f + i * 0.35f);
            scratch.moveTo(x + sway, cy - size * 0.30f);
            scratch.lineTo(x - half + sway * 0.4f, cy);
            scratch.lineTo(x + half + sway * 0.4f, cy);
            scratch.close();
        }
        canvas.drawPath(scratch, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editable) {
            return handleDrag(e);
        }

        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = e.getX();
                touchY = e.getY();
                downX = touchX;
                downY = touchY;
                downTime = now;
                touching = true;
                movedFar = false;
                if (collectAt(touchX, touchY, 34f) == 0 && ripplesEnabled) {
                    Ripple rp = new Ripple();
                    rp.x = touchX;
                    rp.y = touchY;
                    rp.born = now;
                    ripples.add(rp);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                touchX = e.getX();
                touchY = e.getY();
                if (Math.hypot(touchX - downX, touchY - downY) > dp(10f)) {
                    movedFar = true;
                }
                if (movedFar) {
                    attractGlows(touchX, touchY);
                    if (collectAt(touchX, touchY, 40f) == 0) {
                        spawnTrail(touchX, touchY);
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wind = touching && !movedFar
                        && e.getActionMasked() == MotionEvent.ACTION_UP
                        && (now - downTime) >= 620L;
                if (wind) {
                    releaseWind(touchX, touchY);
                } else if (movedFar) {
                    // 抬手时把指尖附近漏掉的光点一并收走
                    collectAt(touchX, touchY, 44f);
                }
                touching = false;
                movedFar = false;
                invalidate();
                return true;
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    /** 摆放模式下的拖动处理：0 表示日月，i+1 表示第 i 棵松树。 */
    private boolean handleDrag(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragIndex = pickElement(e.getX(), e.getY(), w, h);
                pinchBaseDist = 0f;
                suppressMove = false;
                if (dragIndex >= 0) {
                    invalidate();
                }
                return dragIndex >= 0;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指按在已选中的景物上，进入缩放
                if (dragIndex >= 100 && e.getPointerCount() >= 2) {
                    pinchBaseDist = spanOf(e);
                    pinchBaseScale = placedScale(dragIndex - 100);
                }
                return dragIndex >= 0;

            case MotionEvent.ACTION_MOVE:
                if (dragIndex >= 100 && pinchBaseDist > 0f && e.getPointerCount() >= 2) {
                    float span = spanOf(e);
                    if (span > dp(8f)) {
                        scalePlaced(dragIndex - 100, pinchBaseScale * span / pinchBaseDist);
                        invalidate();
                    }
                    return true;
                }
                if (dragIndex >= 0 && !suppressMove) {
                    moveElement(dragIndex, e.getX() / w, e.getY() / h);
                    invalidate();
                }
                return dragIndex >= 0;

            case MotionEvent.ACTION_POINTER_UP:
                // 缩放结束、剩下一根手指时不要立刻把元素拖走
                if (dragIndex >= 100) {
                    suppressMove = true;
                }
                pinchBaseDist = 0f;
                return dragIndex >= 0;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                suppressMove = false;
                if (dragIndex >= 0) {
                    dragIndex = -1;
                    pinchBaseDist = 0f;
                    if (sceneChanged != null) {
                        sceneChanged.run();
                    }
                    invalidate();
                    return true;
                }
                return false;

            default:
                return dragIndex >= 0;
        }
    }

    private int pickElement(float x, float y, int w, int h) {
        float bestDist = dp(52f);
        int best = -1;

        if (config.sun) {
            float d = (float) Math.hypot(x - config.sunX * w, y - config.sunY * h);
            if (d < bestDist) {
                bestDist = d;
                best = 0;
            }
        }

        int n = Math.min(config.pineCount, PINE_POS.length);
        for (int i = 0; i < n; i++) {
            float d = (float) Math.hypot(x - config.pineX[i] * w,
                    y - (config.pineY[i] * h - dp(10f)));
            if (d < bestDist) {
                bestDist = d;
                best = i + 1;
            }
        }

        // 兑换来的景物：编号从 100 起，避免与日月、松树冲突
        if (studio != null) {
            List<Studio.Placed> list = studio.placed();
            for (int i = 0; i < list.size(); i++) {
                Studio.Placed p = list.get(i);
                float d = (float) Math.hypot(x - p.x * w,
                        y - (p.y * h - h * 0.07f * p.scale));
                if (d < bestDist) {
                    bestDist = d;
                    best = 100 + i;
                }
            }
        }
        return best;
    }

    private float spanOf(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return 0f;
        }
        return (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
    }

    private float placedScale(int index) {
        if (studio == null || index < 0 || index >= studio.placed().size()) {
            return 1f;
        }
        return studio.placed().get(index).scale;
    }

    private void scalePlaced(int index, float scale) {
        if (studio == null || index < 0 || index >= studio.placed().size()) {
            return;
        }
        studio.placed().get(index).scale = Math.max(0.5f, Math.min(2.4f, scale));
    }

    private void moveElement(int id, float nx, float ny) {
        nx = Math.max(0.04f, Math.min(0.96f, nx));
        ny = Math.max(0.06f, Math.min(1.0f, ny));

        if (id == 0) {
            config.sunX = nx;
            config.sunY = ny;
            rebuildShaders(getWidth(), getHeight());
            return;
        }

        if (id >= 100) {
            int index = id - 100;
            if (studio != null && index < studio.placed().size()) {
                Studio.Placed p = studio.placed().get(index);
                p.x = nx;
                p.y = ny;
            }
            return;
        }

        int i = id - 1;
        if (i < PINE_POS.length) {
            config.pineX[i] = nx;
            config.pineY[i] = ny;
        }
    }
}
