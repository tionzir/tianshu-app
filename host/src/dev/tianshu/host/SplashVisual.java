package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 开屏页的视觉**纯逻辑** —— 雪花场、渐变终点、雪花色、交棒时序。
 *
 * ## 为什么单独一个类、而且不碰 Android
 * 开屏这一屏里，"好不好看"只能靠真机；但"参数对不对"必须能被机器判定。
 * 参照物（`appui参考/…/ui/SplashSnow.kt`）把这条写得很清楚：本项目的视觉缺陷
 * **从来不在参数上，都在观感上** —— 肉眼看参数恰恰是最不可靠的一环。
 *
 * 抽成纯函数之后，"雪花会不会跑到屏外 / 每次启动是不是同一片 / 雪落在底色上
 * 会不会隐形"这些交给 {@link HostTest}，人只需要判断剩下那一件事。
 * （同一条分法在本项目已用过三次：{@link StartupText}、{@link Theme}、{@link SwipeNav}。）
 *
 * ⚠️ 分工：本类只**算**，{@link SplashBackdrop} 只**画**。
 */
public final class SplashVisual {

    private SplashVisual() {
    }

    // ══════════════════════════════ 雪花场 ══════════════════════════════

    /** 一片雪花。所有量都归一化到与屏幕无关的尺度，绘制时才乘上实际像素。 */
    public static final class Flake {
        /** 横向位置，0..1（占屏宽的比例） */
        public final float x;
        /** 半径，dp */
        public final float radius;
        /** 下落速度倍率 —— 各片不同，雪花才不会"整片一起落"（那看起来像下雨） */
        public final float speed;
        /** 初始相位，0..1；决定这一片是刚从天上进来、还是已经落了一半 */
        public final float phase;
        /** 不透明度 */
        public final float alpha;

        Flake(float x, float radius, float speed, float phase, float alpha) {
            this.x = x;
            this.radius = radius;
            this.speed = speed;
            this.phase = phase;
            this.alpha = alpha;
        }
    }

    /** 默认片数。多了像暴雪，少了看不出在下雪。 */
    public static final int SNOW_COUNT = 18;

    /**
     * 随机种子 —— **固定值**，所以每次冷启动的雪花布局是同一片。
     *
     * 开屏是"这个 App 的一张脸"，不该每次长得不一样；这同时也是它能被单测钉死的前提。
     * 取值照抄参照物（0x5F3759DF，那个著名的平方根倒数魔数），不为别的，
     * 只是让两边的雪花**长得是同一场雪**。
     */
    public static final int SEED = 0x5F3759DF;

    public static final float MIN_RADIUS = 1.2f;
    public static final float MAX_RADIUS = 3.2f;
    public static final float MIN_SPEED = 0.55f;
    public static final float MAX_SPEED = 1.15f;
    public static final float MIN_ALPHA = 0.22f;
    public static final float MAX_ALPHA = 0.62f;

    /**
     * 下落一整个循环的时长（毫秒）。
     *
     * 11 秒 —— 照抄参照物的 SNOW_CYCLE_MS。这个数决定"雪下得有多慢"：
     * 快了像雨（参照物的原话），慢了变成了"屏幕上飘的点"。
     */
    public static final long SNOW_CYCLE_MS = 11_000L;

    /** 重绘间隔。25fps：雪花是慢速的，再密也看不出差别，反而白吃电。 */
    public static final long FRAME_MS = 40L;

    /** 一片默认规模的雪花场。 */
    public static List<Flake> field() {
        return field(SNOW_COUNT);
    }

    /**
     * 生成一片雪花场。
     *
     * 确定性由固定种子的 xorshift 保证：**每次调用得到同一片雪**。
     * 这不是"顺手为之"——不确定的话，开屏每次长得不一样（脸在变），
     * 而且任何断言都写不了。
     *
     * @param count 片数；≤0 返回空表（不是 null —— 调用方不必先判空）
     */
    public static List<Flake> field(int count) {
        if (count <= 0) return Collections.emptyList();
        List<Flake> out = new ArrayList<Flake>(count);
        // 用单元素数组当可变状态：xorshift 每一步都依赖上一步，必须顺着取
        int[] state = {SEED};
        for (int i = 0; i < count; i++) {
            float x = next(state);
            float radius = MIN_RADIUS + next(state) * (MAX_RADIUS - MIN_RADIUS);
            float speed = MIN_SPEED + next(state) * (MAX_SPEED - MIN_SPEED);
            float phase = next(state);
            float alpha = MIN_ALPHA + next(state) * (MAX_ALPHA - MIN_ALPHA);
            out.add(new Flake(x, radius, speed, phase, alpha));
        }
        return out;
    }

    /** xorshift32 一步 → [0,1)。与参照物逐位一致（同样的移位与取高位）。 */
    private static float next(int[] state) {
        state[0] ^= state[0] << 13;
        state[0] ^= state[0] >>> 17;     // >>> 无符号右移：state 取到负值时也能正确
        state[0] ^= state[0] << 5;
        return (state[0] >>> 8) / (float) (1 << 24);
    }

    /**
     * 某片雪花在时刻 [t] 的纵向位置（0..1）。
     *
     * [t] 是**已经过的循环数**（`已过毫秒 / SNOW_CYCLE_MS`），可以无限增长：
     * 取模发生在本方法内部，所以调用方不必自己管回绕。结果是取模后的正值 ——
     * 负数时间（时钟回拨）也不会算出负位置。
     *
     * ⚠️ 每片雪的**自身周期**是 `1 / speed` 个 t 单位，不是 1 —— 各片速度不同，
     * 这正是它们不会"整片一起落"的原因。参照物的 Compose 动画是 Restart 循环，
     * 每轮重启时整片雪会跳一下；天枢喂的是不回绕的时间轴，那个瑕疵天然没有。
     */
    public static float yOf(Flake f, float t) {
        float raw = t * f.speed + f.phase;
        return ((raw % 1f) + 1f) % 1f;
    }

    // ══════════════════════════════ 配色 ══════════════════════════════

    /**
     * 渐变终点 = 页面底色朝主色偏**一点点**（6%）。
     *
     * 为什么是这个系数：参照物的开屏是 `#F5F7FA → #E9F0FB`（顶部页面底色，
     * 底部极浅的蓝）。天枢的底色/主色都能被用户换掉，所以终点必须**算**出来 ——
     * 而 0.06 是实测出来的：在默认组合（初雪 `#f5f7fa` × `#2f6bff`）下，
     * 它给出 `#e9effa`，与参照物的 `#E9F0FB` 每个通道都只差 1/255。
     *
     * 换句话说：默认主题下，天枢的开屏与参照物**是同一个底色**；
     * 换了主题，它跟着换。
     */
    public static final double GRADIENT_MIX = 0.06;

    /** 开屏背景的渐变终点：顶部仍是页面底色，向下过渡到它。 */
    public static String gradientBottom(String bg, String pri) {
        return Theme.mix(bg, pri, GRADIENT_MIX);
    }

    /** 参照物的雪花色（`SplashSnowInk`）。它比纯白灰、比纯白蓝，因为纯白落在浅底上等于隐形。 */
    public static final String SNOW_INK_BASE = "#9FB6DD";

    /**
     * 雪花色相对渐变底的对比度下限。
     *
     * ⚠️ **1.75 不是拍的，是被两端夹出来的**：
     *   - 下限：固定用参考色时，实测有 43/136 个组合落到 1.16:1（`#aaaaaa` 这类中间调
     *     自定义底上几乎看不见），所以必须有派生；
     *   - 上限：参考色在参考底（`#E9F0FB`）上的实测对比是 **1.79** —— 阈值只要取到 1.8，
     *     默认主题下就会把参考色推成别的色，"照参考"当场落空。
     *
     * 取 1.75 的结果：默认组合下雪色**逐字就是 `#9FB6DD`**，而最坏组合也被兜到 1.75。
     * 1.75 与 1.79 的可见性差别没有意义；意义在于**它不会把参照物的结论改掉**。
     */
    public static final double SNOW_MIN_CONTRAST = 1.75;

    /**
     * 雪花在某个渐变底上该用什么色。
     *
     * 参考色是"起手式"：够看得见就原样用它（保住参照物的观感），
     * 不够就朝黑/白推到够为止。往哪个方向推按底色的明暗定 —— 浅底推暗、深底推亮，
     * 这样在深色主题下雪依然是浅色的（雪本来就该是浅的）。
     */
    public static String snowInk(String bottom) {
        String target = Theme.isDark(bottom) ? "#ffffff" : "#000000";
        return Theme.towards(SNOW_INK_BASE, target, bottom, SNOW_MIN_CONTRAST);
    }

    // ══════════════════════════════ 交棒时序 ══════════════════════════════

    /**
     * 开屏**至少**显示多久（毫秒）。
     *
     * 参照物的 `YukiDuration.Splash`。热启动时 serve 几秒就绪，没有这一条的话
     * 开屏会"闪一下"就没了 —— 那比多看 1.2 秒更难受（像界面抽搐了一下）。
     * 冷启动要解压几分钟，这一条根本轮不到生效。
     */
    public static final long SPLASH_MIN_MS = 1_200L;

    /** 交棒前的缩放淡出时长（毫秒），照参照物。 */
    public static final long ZOOM_OUT_MS = 420L;

    /** 淡出时整体放大到多少 —— "往里进去"的感觉，与参照物一致。 */
    public static final float ZOOM_OUT_SCALE = 1.05f;

    /** 淡出时整体上移多少 dp，与参照物一致。 */
    public static final int ZOOM_OUT_SHIFT_DP = 26;

    /**
     * 交棒该再等多久。
     *
     * 纯函数，所以"至少显示 1.2 秒"这条约束能被断言：启动用了 0.3 秒 → 再等 0.9 秒；
     * 启动用了 5 秒 → 不再等。时钟回拨（elapsed 为负）按"已经等够了"处理，
     * 宁可立刻进，也不要卡在这里。
     */
    public static long handOffDelayMs(long elapsedSinceBoot) {
        if (elapsedSinceBoot < 0) return 0;
        long left = SPLASH_MIN_MS - elapsedSinceBoot;
        return left > 0 ? left : 0;
    }
}
