package dev.tianshu.host;

/**
 * 视觉体系 —— 间距节奏 与 字阶。
 *
 * 为什么要有这个类：在此之前每处 padding 都是手写的数（16/18/20/22 混用），
 * 字号也是 11/12/13/14/15/17/22 乱飞。**没有节奏就没有"商业级"的观感** ——
 * 那是任何单点美化都补不回来的（用户连着两轮说"粗糙"）。
 *
 * 规矩很简单：**不许就地写数字**，只用下面这几档。
 *   - 间距走 4 的倍数，以 8 为基本单位；
 *   - 字号只有 5 级，每一级对应一种明确的角色。
 *
 * 这样做的直接好处：改一处节奏（比如整体收紧）只要动这里，而不是翻遍所有 Activity。
 */
public final class Ui {

    private Ui() {
    }

    /**
     * 全局缩放系数 —— **当前 = 1.0，即不缩放**。
     *
     * 2026-10-04：先按要求做成 0.98（整体小 2%），用户真机看过之后要求**回退**、不用了。
     * 所以这里回到 1.0 —— {@link BaseActivity} 见值没变会直接跳过包装，等于零开销、零效果。
     * 开关留着：将来若还想要系统级缩放，改这一个数即可。
     */
    public static final float SCALE = 1.0f;

    // ---------------------------------------------------------------- 间距（dp）

    /** 紧邻：图标与其文字之间。 */
    public static final int S1 = 4;
    /** 组内：同一块内容里，行与行之间。 */
    public static final int S2 = 8;
    /** 组间：卡片内部，头与内容之间。 */
    public static final int S3 = 12;
    /** 卡片内边距。 */
    public static final int S4 = 16;
    /** 区块之间：卡片与卡片。 */
    public static final int S5 = 24;

    /**
     * 卡片与卡片之间的间距 —— **真·卡片间距**，与 S2 的"组内行距"是两件事。
     *
     * ⚠️ 扁平风靠**留白 + 阴影**分层：卡片一挤，那 2–3dp 的阴影就完全失去意义，整页糊成一片。
     * 参考 App 为此单独定义过一个常量（`CardGap = 14dp`），注释里写着"这个值值得单独拎出来"——
     * 天枢原先用的是 S2（8dp），卡片之间太紧，阴影等于白做。
     */
    public static final int CARD_GAP = 14;
    /** 页面边距。 */
    public static final int S6 = 20;

    /**
     * 竖直相邻控件之间的间距。
     *
     * 这一档专门用来治"控件挤在一起"：Android 里 addView 不带 margin 就是 **0 间距**，
     * 于是「一个按钮紧贴下一个按钮、提示文字紧贴按钮」—— 看着就是"没排版"。
     */
    public static final int STACK = S3;   // 12
    /** 横排相邻控件之间的间距（如输入框与发送键）。 */
    public static final int INLINE = S3;  // 12

    // ---------------------------------------------------------------- 字阶（sp）

    /** 品牌标题（启动页那个「天枢」）—— 比 DISPLAY 再大一档，只在开屏用。 */
    public static final float HERO = 34f;
    /** 页面标题（"外观"、"会话"那一行）。对齐参考 App 的 20sp（页头还挂着 36dp 徽章，不挤）。 */
    public static final float DISPLAY = 22f;
    /** 卡片/区块标题。 */
    public static final float TITLE = 15f;
    /** 正文（可读内容、按钮）。参考是 16sp，这里取 15 —— 屏幕上的信息密度比它高。 */
    public static final float BODY = 15f;
    /** 说明（灰字解释）。 */
    public static final float CAPTION = 12f;
    /** 标签（底栏、角标）。 */
    public static final float LABEL = 11f;

    // ---------------------------------------------------------------- 触控

    /** 可点元素的最小高度（Material 的下限是 48dp）。 */
    public static final int TOUCH_MIN = 48;
}
