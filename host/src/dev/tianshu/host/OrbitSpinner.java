package dev.tianshu.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 星轨转圈 —— 自己画的，替掉系统的不定式 ProgressBar。
 *
 * 为什么不用 ProgressBar：各 ROM 的默认样式差别极大（有的细得几乎看不见，有的是一坨
 * 方疙瘩），而且它只能"转"，表达不出"在推进"。这里画的是一圈带拖尾的点：头部最亮、
 * 尾巴渐隐，整体缓慢自转 —— 像星轨，也一眼看得出"在动"。
 *
 * 纯 Canvas，不用动画框架（ValueAnimator 也行，但那样在窗口不可见时还在烧帧，
 * 这里靠 onDraw 自调度 + detached 时停表，简单且死得干净）。
 */
public class OrbitSpinner extends View {

    private static final int DOTS = 12;
    private static final long FRAME_MS = 40;       // ~25fps：够顺滑，也不至于让启动页吃电
    private static final float STEP_DEG = 3.2f;    // 每帧转多少 → 约 4.5 秒一圈

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float angle;
    private boolean running;
    private int color = 0xFF2B6CF0;
    private static final int TAIL_ALPHA = 40;      // 拖尾最暗那一档

    public OrbitSpinner(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 跟随主色（换主题后调用）。 */
    public void setColor(int argb) {
        this.color = argb;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    public void start() {
        if (running) return;
        running = true;
        postInvalidateDelayed(FRAME_MS);
    }

    /** 停下并保持在当前角度（失败时用：不要继续假装在做事）。 */
    public void stop() {
        running = false;
    }

    /**
     * 窗口不可见就停表（2026-10-04 深查）。
     *
     * 原先只接 {@link #onDetachedFromWindow}，可 App 切到后台时视图**仍然 attached**，
     * 自调度重绘会继续按 25fps 投帧 —— 首次解压那几分钟里用户切走，它就一直烧电。
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int want = (int) (getResources().getDisplayMetrics().density * 56);
        setMeasuredDimension(resolveSize(want, widthMeasureSpec),
                resolveSize(want, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f * 0.72f;
        float dotR = Math.max(2f, Math.min(w, h) * 0.055f);

        for (int i = 0; i < DOTS; i++) {
            double rad = Math.toRadians(angle + i * (360.0 / DOTS));
            float x = cx + (float) (Math.cos(rad) * radius);
            float y = cy + (float) (Math.sin(rad) * radius);
            // i = 0 是头（最亮），越往后越淡 —— 拖尾就是"在转"的视觉证据
            int alpha = TAIL_ALPHA + (255 - TAIL_ALPHA) * (DOTS - i) / DOTS;
            if (alpha < 0) alpha = 0;
            if (alpha > 255) alpha = 255;
            paint.setColor((alpha << 24) | (color & 0x00FFFFFF));
            canvas.drawCircle(x, y, dotR, paint);
        }

        if (running) {
            angle = (angle + STEP_DEG) % 360f;
            postInvalidateDelayed(FRAME_MS);
        }
    }
}
