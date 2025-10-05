package com.example.hackyeah;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.annotation.Nullable;

public class HeartMaskView extends View {
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pClear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();

    // Size & animation
    private float baseScale = 0.98f;        // bigger heart (0..1, fraction of min(w,h))
    private float pulseScale = 1.0f;        // animated multiplier
    private float edgeInsetPx;              // small safety inset from edges
    private @Nullable ValueAnimator pulseAnim;

    public HeartMaskView(Context c) { this(c, null); }
    public HeartMaskView(Context c, @Nullable AttributeSet a) { this(c, a, 0); }
    public HeartMaskView(Context c, @Nullable AttributeSet a, int def) {
        super(c, a, def);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        scrim.setColor(0x99000000); // 60% black
        pClear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        edgeInsetPx = dp(c, 8);     // smaller inset => larger heart
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        // 1) fill scrim
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        // 2) punch heart hole
        buildHeartPath(getWidth(), getHeight());
        c.drawPath(heart, pClear);
    }

    private void buildHeartPath(int w, int h) {
        heart.reset();

        float usableW = w - 2 * edgeInsetPx;
        float usableH = h - 2 * edgeInsetPx;
        float size = Math.min(usableW, usableH) * baseScale * pulseScale;

        float cx = w / 2f;
        float cy = h * 0.52f; // a bit above center looks nicer

        RectF r = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        float x = r.left, y = r.top, ww = r.width(), hh = r.height();

        heart.moveTo(x + ww / 2f, y + hh * 0.25f);
        heart.cubicTo(
                x + ww * 0.90f, y - hh * 0.10f,
                x + ww * 1.10f, y + hh * 0.45f,
                x + ww / 2f,    y + hh * 0.85f);
        heart.cubicTo(
                x - ww * 0.10f, y + hh * 0.45f,
                x + ww * 0.10f, y - hh * 0.10f,
                x + ww / 2f,    y + hh * 0.25f);
        heart.close();
    }

    // --- Public API ---

    /** Make the heart larger/smaller: 0..1 (default 0.98) */
    public void setHeartScale(float scale) {
        baseScale = Math.max(0.5f, Math.min(1.05f, scale));
        invalidate();
    }

    /** Start gentle pulsing (used while measuring). */
    public void startPulse() {
        if (pulseAnim != null && pulseAnim.isRunning()) return;
        pulseAnim = ValueAnimator.ofFloat(1.0f, 1.06f); // ~6% pulse
        pulseAnim.setDuration(850);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnim.addUpdateListener(a -> {
            pulseScale = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    /** Stop pulsing and reset to normal size. */
    public void stopPulse() {
        if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
        }
        pulseScale = 1.0f;
        invalidate();
    }

    /** Optional: adjust scrim color at runtime */
    public void setScrimColor(int color) {
        scrim.setColor(color);
        invalidate();
    }

    /** Optional: adjust edge inset in dp (default 8dp) */
    public void setEdgeInsetDp(float dp) {
        edgeInsetPx = dp(getContext(), dp);
        invalidate();
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }
}
