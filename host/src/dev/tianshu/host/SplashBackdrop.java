package dev.tianshu.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/**
 * 开屏背景 —— **自己画的**：一层极浅的垂直渐变 + 一场雪。
 *
 * 为什么不是几张图：主题可换（7 套底色 × 8 款主色 + 自定义底），开屏要给每一套
 * 都配一张图是不可能的；而渐变和雪都是**算得出来的**，算出来的东西才会跟着主题走。
 *
 * 为什么自绘而不加两层 View：底色与雪用的是同一个画布坐标系（雪要铺满底色），
 * 拆成两层只会多一次 invalidate 与一次布局，还多两个需要跟着主题刷新的对象。
 * 这里一次 onDraw 画完，跟着 {@link OrbitSpinner} 一样的自调度节奏走
 * （见它的注释：不引动画框架，靠 onDraw 自调度 + detach 时停表，简单且死得干净）。
 *
 * ⚠️ 与 {@link SplashVisual} 的分工：本类只**画**，参数一律问它要 ——
 * 这样"雪下得多慢 / 雪色够不够看得见"这些能被 HostTest 钉住，不必上真机。
 */
public class SplashBackdrop extends View {

    /** 底色（渐变垫在最下） */
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 雪花 */
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 雪花场只生成一次 —— 整段开屏里布局不变（确定性见 {@link SplashVisual#field()}） */
    private final List<SplashVisual.Flake> flakes = SplashVisual.field();

    private final float density;

    private int topArgb = 0xFFF5F7FA;      // 初雪底色；构造到第一次 setTheme 之间只有几毫秒
    private int bottomArgb = 0xFFE9EFFA;
    private int snowArgb = 0xFF9FB6DD;

    private LinearGradient gradient;
    private boolean running;
    private long startAt;

    public SplashBackdrop(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
        density = context.getResources().getDisplayMetrics().density;
    }

    /**
     * 按当前主题换色。
     *
     * 传底色与主色而不是 {@link Theme.Tokens} —— 这一屏只用得上这两个，
     * 依赖面越小越好（也免得"以后 Tokens 加字段，开屏跟着改"）。
     */
    public void setTheme(String bg, String pri) {
        String bottom = SplashVisual.gradientBottom(bg, pri);
        topArgb = Theme.toArgb(bg);
        bottomArgb = Theme.toArgb(bottom);
        snowArgb = Theme.toArgb(SplashVisual.snowInk(bottom));
        gradient = null;      // 尺寸没变、颜色变了，shader 必须重建
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradient = new LinearGradient(0, 0, 0, h, topArgb, bottomArgb, Shader.TileMode.CLAMP);
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
        if (startAt == 0) startAt = SystemClock.uptimeMillis();
        postInvalidateDelayed(SplashVisual.FRAME_MS);
    }

    /**
     * 停下并**冻结当前画面**（启动失败时用）。
     *
     * 与 {@link OrbitSpinner#stop()} 同一个理由：失败之后不该继续假装在做事 ——
     * 屏幕上还在飘雪、底下却写着"启动没成功"，那是自相矛盾的信号。
     */
    public void stop() {
        running = false;
    }

    /**
     * 窗口不可见就停表（2026-10-04 深查）—— 与 {@link OrbitSpinner} 同一个理由：
     * App 切到后台时视图仍 attached，只接 onDetachedFromWindow 的话会继续按 25fps 投帧。
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (gradient == null) {
            gradient = new LinearGradient(0, 0, 0, h, topArgb, bottomArgb, Shader.TileMode.CLAMP);
        }
        fill.setShader(gradient);
        canvas.drawRect(0, 0, w, h, fill);

        // 循环时间：喂给 yOf 的是"过了几个循环"，它自己取模 —— 时间轴无限涨也不会越界
        float t = startAt == 0 ? 0f
                : (SystemClock.uptimeMillis() - startAt) / (float) SplashVisual.SNOW_CYCLE_MS;
        for (int i = 0; i < flakes.size(); i++) {
            SplashVisual.Flake f = flakes.get(i);
            dot.setColor(Theme.withAlpha(snowArgb, f.alpha));
            canvas.drawCircle(f.x * w, SplashVisual.yOf(f, t) * h, f.radius * density, dot);
        }

        if (running) postInvalidateDelayed(SplashVisual.FRAME_MS);
    }
}
