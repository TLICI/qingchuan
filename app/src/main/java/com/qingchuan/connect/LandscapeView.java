package com.qingchuan.connect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
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

    private static final int[] NIGHT = {0xFF25394B, 0xFF1B2C3B, 0xFF13232F, 0xFF0B1822};

    /** 春、夏、秋、冬 四套山色，按收集到的光点数解锁。 */
    private static final int[][] SEASON_MOUNTAINS = {
            {0xFFB7CDB0, 0xFF8FAF86, 0xFF6B8F63, 0xFF4A6B45},
            {0xFFA8C6B0, 0xFF7FA68A, 0xFF568063, 0xFF345A45},
            {0xFFD8C3A8, 0xFFC09A72, 0xFF9A6B45, 0xFF6E4528},
            {0xFFD3DEE6, 0xFFB0C2CE, 0xFF8AA0B0, 0xFF62788A}
    };
    private static final int[] SEASON_SCORE = {0, 8, 20, 36};
    private static final String[] SEASON_NAMES = {"春", "夏", "秋", "冬"};

    /** 进度回调：收集数变化或季节解锁时通知界面。 */
    public interface Listener {
        void onProgress(int score, int season);
    }

    private static final class Glow {
        float x, y, r, phase;
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
    private final Random random = new Random(20261026L);

    private Shader skyShader, waterShader, glowShader;
    private boolean night = false;
    private long startTime = -1L;

    private Listener listener;
    private int score = 0;
    private int season = 0;
    private long lastSpawn = 0L;

    public LandscapeView(Context c) { super(c); }
    public LandscapeView(Context c, @Nullable AttributeSet a) { super(c, a); }
    public LandscapeView(Context c, @Nullable AttributeSet a, int d) { super(c, a, d); }

    public void toggleDayNight() {
        night = !night;
        rebuildShaders(getWidth(), getHeight());
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onProgress(score, season);
        }
    }

    public String seasonName() {
        return SEASON_NAMES[season];
    }

    public int getScore() {
        return score;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < mountains.length; i++) {
            mountains[i] = buildMountain(w, h, h * (0.42f + i * 0.10f),
                    h * (0.055f + i * 0.018f), 1.6f + i * 0.35f, i * 1.3f);
        }
        clouds.clear();
        for (int i = 0; i < 5; i++) {
            Cloud c = new Cloud();
            c.x = random.nextFloat();
            c.y = 0.08f + random.nextFloat() * 0.26f;
            c.speed = 0.006f + random.nextFloat() * 0.012f;
            c.scale = 0.7f + random.nextFloat() * 0.8f;
            clouds.add(c);
        }
        birds.clear();
        for (int i = 0; i < 4; i++) {
            Bird b = new Bird();
            b.x = random.nextFloat();
            b.y = 0.16f + random.nextFloat() * 0.26f;
            b.speed = 0.02f + random.nextFloat() * 0.03f;
            b.phase = random.nextFloat() * 10f;
            birds.add(b);
        }
        rebuildShaders(w, h);
    }

    private void rebuildShaders(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float horizon = h * 0.72f;
        skyShader = new LinearGradient(0, 0, 0, horizon,
                night ? 0xFF0B1626 : 0xFFC9E2EE,
                night ? 0xFF223A52 : 0xFFEEF6F6, Shader.TileMode.CLAMP);
        waterShader = new LinearGradient(0, horizon, 0, h,
                night ? 0xFF12212E : 0xFFD7E8EA,
                night ? 0xFF09141D : 0xFFAFC9CF, Shader.TileMode.CLAMP);
        float cx = night ? w * 0.24f : w * 0.78f;
        float cy = night ? h * 0.16f : h * 0.18f;
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
        float t = (SystemClock.uptimeMillis() - startTime) / 1000f;
        float horizon = h * 0.72f;

        skyPaint.setShader(skyShader);
        canvas.drawRect(0, 0, w, h, skyPaint);

        // 日月
        float cx = night ? w * 0.24f : w * 0.78f;
        float cy = night ? h * 0.16f : h * 0.18f;
        float r = w * (night ? 0.045f : 0.055f);
        glowPaint.setShader(glowShader);
        canvas.drawCircle(cx, cy, r * 3.4f, glowPaint);
        fill.setShader(null);
        fill.setColor(night ? 0xFFE9F0F8 : 0xFFFFF3C4);
        canvas.drawCircle(cx, cy, r, fill);

        // 云
        fill.setShader(null);
        fill.setColor(night ? 0x33AFC3D6 : 0xCCFFFFFF);
        for (Cloud c : clouds) {
            float px = ((c.x + t * c.speed) % 1.2f - 0.1f) * w;
            float py = c.y * horizon;
            float cr = c.scale * w * 0.06f;
            canvas.drawCircle(px, py, cr, fill);
            canvas.drawCircle(px + cr * 0.9f, py + cr * 0.15f, cr * 0.8f, fill);
            canvas.drawCircle(px - cr * 0.9f, py + cr * 0.2f, cr * 0.7f, fill);
            canvas.drawCircle(px + cr * 0.2f, py - cr * 0.35f, cr * 0.7f, fill);
        }

        // 远近山峦：夜晚统一用冷色，白天随季节变化
        int[] colors = night ? NIGHT : SEASON_MOUNTAINS[season];
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(null);
        for (int i = 0; i < mountains.length; i++) {
            fill.setColor(colors[i]);
            canvas.drawPath(mountains[i], fill);
        }

        // 水面
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

        // 飞鸟
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

        // 山间光点
        updateGlows(w, h);
        drawGlows(canvas, t);

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
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(night ? 0xFF081018 : 0xFF1E2A2E);
        drawPine(canvas, w * 0.10f, h * 0.99f, h * 0.24f);
        drawPine(canvas, w * 0.17f, h * 1.00f, h * 0.17f);
        drawPine(canvas, w * 0.90f, h * 0.99f, h * 0.28f);

        postInvalidateOnAnimation();
    }

    private void updateGlows(int w, int h) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSpawn > 1500 && glows.size() < 3) {
            lastSpawn = now;
            Glow g = new Glow();
            g.x = w * (0.10f + random.nextFloat() * 0.80f);
            g.y = h * (0.24f + random.nextFloat() * 0.40f);
            g.r = dp(5f + random.nextFloat() * 3f);
            g.phase = random.nextFloat() * 6.28f;
            g.born = now;
            glows.add(g);
        }
        for (int i = glows.size() - 1; i >= 0; i--) {
            if (now - glows.get(i).born > 6000L) {
                glows.remove(i);
            }
        }
    }

    private void drawGlows(Canvas canvas, float t) {
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        for (Glow g : glows) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(t * 3f + g.phase);
            fill.setColor(0x33FFE9A0);
            canvas.drawCircle(g.x, g.y, g.r * 3.2f * pulse, fill);
            fill.setColor(0x88FFF0B8);
            canvas.drawCircle(g.x, g.y, g.r * 1.8f, fill);
            fill.setColor(0xFFFFFBE8);
            canvas.drawCircle(g.x, g.y, g.r, fill);
        }
    }

    /** 命中光点则收集并返回 true；未命中返回 false，交给涟漪处理。 */
    private boolean collectGlow(float x, float y) {
        float hit = dp(34f);
        for (int i = glows.size() - 1; i >= 0; i--) {
            Glow g = glows.get(i);
            float dx = g.x - x;
            float dy = g.y - y;
            if (dx * dx + dy * dy <= hit * hit) {
                glows.remove(i);
                score++;
                for (int s = SEASON_SCORE.length - 1; s >= 0; s--) {
                    if (score >= SEASON_SCORE[s]) {
                        season = s;
                        break;
                    }
                }
                if (listener != null) {
                    listener.onProgress(score, season);
                }
                return true;
            }
        }
        return false;
    }

    private void drawPine(Canvas canvas, float x, float baseY, float size) {
        float trunk = size * 0.06f;
        scratch.reset();
        scratch.addRect(x - trunk / 2, baseY - size * 0.25f, x + trunk / 2, baseY, Path.Direction.CW);
        for (int i = 0; i < 3; i++) {
            float cy = baseY - size * 0.25f - i * size * 0.24f;
            float half = size * (0.30f - i * 0.07f);
            scratch.moveTo(x, cy - size * 0.30f);
            scratch.lineTo(x - half, cy);
            scratch.lineTo(x + half, cy);
            scratch.close();
        }
        canvas.drawPath(scratch, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (collectGlow(e.getX(), e.getY())) {
                invalidate();
                return true;
            }
            Ripple rp = new Ripple();
            rp.x = e.getX();
            rp.y = e.getY();
            rp.born = SystemClock.uptimeMillis();
            ripples.add(rp);
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }
}
