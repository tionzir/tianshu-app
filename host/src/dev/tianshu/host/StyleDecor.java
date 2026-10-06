package dev.tianshu.host;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 卡片底 —— 按**界面风格**画出各自的"标志物"。
 *
 * 为什么不用 GradientDrawable：它只能画「一个圆角矩形 + 一层描边」，而三套风格的区别
 * 恰恰在描边以外的东西。只调圆角和字体的话，三套看起来就是「同一个界面换了皮」——
 * 用户连着两轮的原话是"没有很具体直观的感觉""还是不明显"。
 *
 * 所以这里刻意往**一眼可见**的维度上靠：粗细、错位、实色块 ——
 *   modern  —— 干净的大圆角面，零描边（留白与柔和投影说话）
 *   pixel   —— 方角 + **3dp 粗边** + **右下角错位的实色硬阴影**（8-bit UI 的标志）
 *   ancient —— 2dp 边 + **加粗的四角界格**（线装书版框）
 *
 * ⚠ 字体那一维（serif/mono/sans）对**中文**几乎无效 —— 汉字在多数 ROM 上只有一套字库。
 * 中文的"字体气质"只能靠字距表达，那是 {@link Theme.Style#letterSpacing} 的事。
 */
public class StyleDecor extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF tmp = new RectF();

    private float density = 2f;
    private int fillColor = 0xFFFFFFFF;
    private int strokeColor = 0x22000000;
    private int accentColor = 0xFF2B6CF0;
    private int shadowColor = 0x44000000;
    private int radiusPx;
    private int borderPx;
    private int offsetPx;
    private int fillOverride;
    private String decor = "none";

    public StyleDecor(Theme.Tokens t, float density) {
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.BUTT);
        shadow.setStyle(Paint.Style.FILL);
        apply(t, density);
    }

    /** 换主题/风格时重新配置 —— 同一个 View 会被反复贴色。 */
    public final void apply(Theme.Tokens t, float density) {
        this.fillOverride = 0;
        this.density = density <= 0 ? 2f : density;
        this.fillColor = t.cardArgb();
        this.strokeColor = Theme.toArgb(t.hair);
        this.accentColor = Theme.toArgb(t.pri);
        this.radiusPx = (int) (t.radiusLg * this.density + 0.5f);
        this.borderPx = Math.max(0, (int) (t.hairWidth * this.density + 0.5f));
        this.offsetPx = Math.max(0, (int) (t.offsetDp * this.density + 0.5f));
        this.decor = t.decor == null ? "none" : t.decor;
        // 硬阴影是**实色**（不是模糊投影）：卡色压暗两档
        this.shadowColor = Theme.toArgb(Theme.mix(t.card, "#000000", 0.55));
        invalidateSelf();
    }

    /**
     * 覆盖填充色 —— 按钮要用**主色**而不是卡片色。
     * 必须在 {@link #apply} 之后调用（apply 会把填充重置回卡片色）。
     */
    public void setFillOverride(int argb) {
        this.fillOverride = argb;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        // 像素风：先画一块**错位**的实色阴影，再把卡片本体压在上面，
        // 于是右/下各露出一条 offsetPx 的实色边 —— 8-bit UI 最标志性的东西，
        // 比任何 1px 细线都一眼可辨。
        if (offsetPx > 0) {
            shadow.setColor(shadowColor);
            c.drawRect(b.left + offsetPx, b.top + offsetPx, b.right, b.bottom, shadow);
        }

        // 本体：有硬阴影时右/下各内缩 offsetPx，空出来的那条就是阴影
        body.set(b.left, b.top, b.right - offsetPx, b.bottom - offsetPx);
        float rad = radiusPx;

        // 2026-10-04：上一版这里加了"毛玻璃光泽"（纵向渐变 + 上下受光弧线），
        // 结果两样都出问题 —— 渐变让卡面色不匀；`drawArc` 画出的两道弧线被用户一眼看到
        //（原话「怎么有弧形的线」）。**整个回退**：卡片底回到纯色 + 细边。
        // "毛玻璃"改由最可靠的那条路表达 —— **半透明**（跟卡片透明度滑块走，见 Theming）。
        fill.setColor(fillOverride != 0 ? fillOverride : fillColor);
        c.drawRoundRect(body, rad, rad, fill);

        if ("pixel".equals(decor)) {
            drawPixelEdge(c, body, rad);
        } else if ("ancient".equals(decor)) {
            drawAncientFrame(c, body, rad);
        } else if (borderPx > 0) {
            line.setColor(strokeColor);
            line.setStrokeWidth(borderPx);
            c.drawRoundRect(inset(body, borderPx / 2f), rad, rad, line);
        }
    }

    /** 像素风：粗外框（实色）+ 亮色内线，构成"双层硬边"。 */
    private void drawPixelEdge(Canvas c, RectF r, float rad) {
        if (borderPx <= 0) return;
        line.setStrokeWidth(borderPx);
        line.setColor(strokeColor);
        c.drawRoundRect(inset(r, borderPx / 2f), rad, rad, line);

        // 内层亮线：让"双层"看得出来（宽度取外框的一半，太细就等于没画）
        float inner = Math.max(2f, borderPx * 0.5f);
        line.setStrokeWidth(inner);
        line.setColor(lighten(strokeColor));
        c.drawRoundRect(inset(r, borderPx * 1.6f), rad, rad, line);
    }

    /** 古风：细边 + 四角界格。角线刻意画粗、画长 —— 细线在小卡片上等于没画。 */
    private void drawAncientFrame(Canvas c, RectF r, float rad) {
        float w = Math.max(2f, borderPx);
        line.setStrokeWidth(Math.max(1f, borderPx * 0.7f));
        line.setColor(strokeColor);
        c.drawRoundRect(inset(r, borderPx * 0.35f + 1), rad, rad, line);

        float pad = borderPx + 5f * density;
        float len = Math.min(r.width(), r.height()) * 0.22f;
        float min = 10f * density;
        if (len < min) len = min;
        line.setStrokeWidth(w);              // 角线用最粗的
        line.setColor(accentColor);

        float l = r.left + pad, t = r.top + pad;
        float rr = r.right - pad, bb = r.bottom - pad;
        c.drawLine(l, t, l + len, t, line);
        c.drawLine(l, t, l, t + len, line);
        c.drawLine(rr - len, t, rr, t, line);
        c.drawLine(rr, t, rr, t + len, line);
        c.drawLine(l, bb, l + len, bb, line);
        c.drawLine(l, bb - len, l, bb, line);
        c.drawLine(rr - len, bb, rr, bb, line);
        c.drawLine(rr, bb - len, rr, bb, line);
    }

    private RectF inset(RectF src, float d) {
        tmp.set(src.left + d, src.top + d, src.right - d, src.bottom - d);
        return tmp;
    }

    /** 把描边色提亮一档（像素风的内线用）。 */
    private static int lighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int rr = Math.min(255, ((argb >> 16) & 0xFF) + 90);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) + 90);
        int bb = Math.min(255, (argb & 0xFF) + 90);
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
        shadow.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        line.setColorFilter(cf);
        shadow.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
