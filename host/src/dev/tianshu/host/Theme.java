package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外观系统的**纯逻辑** —— 离线靶子 {@code src/theme.mjs} 的逐条移植。
 *
 * 参照物是「售后助手」那套外观系统（用户指定的参照物：
 * /storage/emulated/0/oa-hot/www/theme-core.js）：6 套背景主题 × 7 款主色 ×
 * 自定义底色 × 背景图 × 卡片透明度。分工与那边一致 ——
 *   本类只管**算**（配色求解 + 护栏）；贴到真实 View 树是 {@link Theming} 的事。
 *
 * 为什么配色要"算"而不是写死：用户一旦能自定义背景，卡片色、次要文字色、
 * 分隔线、主色上的字色就全都得跟着推 —— 写死的那份在深色底上会变成
 * "深字压深底"（看不见）或"白卡贴白底"（没层次）。
 *
 * 与 theme.mjs 是**逐条对应**的关系：函数名、阈值、遍历顺序都保持一致，
 * 两边的断言必须得出同一结论（这是本项目的惯例：靶子先 GREEN，再移植）。
 *
 * 本类**不碰任何 Android API**，所以能在容器里用 javac + java 直接跑测试。
 */
public final class Theme {

    private Theme() {
    }

    // ---------------------------------------------------------------- 常量

    /** 直接落在页面底色/卡片上的正文阈值（WCAG AA）。 */
    public static final double AA_TEXT = 4.5;
    /** 卡片上的正文：AA 是 4.5，正文留一倍余量。 */
    public static final double MIN_CARD_CONTRAST = 7;
    /** 大字/装饰性收尾。 */
    public static final double MIN_BIG_CONTRAST = 3;
    /** 卡片与底色的亮度差 —— 低于这个数「卡片」就不成形了（只在求解器内部用）。 */
    private static final double MIN_SEPARATION = 0.02;

    public static final double CARD_ALPHA_MIN = 0.1;
    public static final double CARD_ALPHA_MAX = 1;
    /**
     * 背景图遮罩的不透明度：`t.scrim = alpha(p.bg, SCRIM_ALPHA)` ⇒ 照片只剩 `1-0.76 = 24%` 透过来。
     * 它有**两个作用点**（`t.scrim` 本身 + "照片端面"的推算，见 {@link #pageFaces}），
     * 必须同源 —— 改一处漏另一处就是 C6 那个洞（2026-10-05）。
     */
    public static final double SCRIM_ALPHA = 0.76;
    public static final String DEFAULT_BG = "#f4f6fa";

    private static final String INK_DARK = "#1b1f27";
    private static final String INK_LIGHT = "#e9edf4";
    private static final String INK_ON_BRIGHT = "#101418";

    // 语义色是固定的：它们表达"成功/失败/警告"，不该跟着审美漂。
    private static final String OK = "#0fa968";
    private static final String DANGER = "#d92d33";
    private static final String WARN = "#c98a00";

    private static final Pattern HEX3 =
            Pattern.compile("^#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$");
    private static final Pattern HEX6 =
            Pattern.compile("^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$");

    // ---------------------------------------------------------------- 色值

    /** "#abc" / "#aabbcc" → [r,g,b]；不认的一律 null（不猜）。 */
    public static int[] rgb(String h) {
        if (h == null) return null;
        String s = h.trim();
        Matcher m = HEX6.matcher(s);
        if (m.matches()) {
            return new int[]{
                    Integer.parseInt(m.group(1), 16),
                    Integer.parseInt(m.group(2), 16),
                    Integer.parseInt(m.group(3), 16)};
        }
        m = HEX3.matcher(s);
        if (m.matches()) {
            return new int[]{
                    Integer.parseInt(m.group(1) + m.group(1), 16),
                    Integer.parseInt(m.group(2) + m.group(2), 16),
                    Integer.parseInt(m.group(3) + m.group(3), 16)};
        }
        return null;
    }

    public static boolean isHex(String h) {
        return rgb(h) != null;
    }

    private static int clamp(int n) {
        return n < 0 ? 0 : n > 255 ? 255 : n;
    }

    public static String hex(int r, int g, int b) {
        return "#" + p2(r) + p2(g) + p2(b);
    }

    private static String p2(int v) {
        String s = Integer.toHexString(clamp(v));
        return s.length() < 2 ? "0" + s : s;
    }

    /** 统一成小写 6 位色（"#ABC" → "#aabbcc"）；非法输入原样返回。 */
    public static String normHex(String h) {
        int[] c = rgb(h);
        return c == null ? h : hex(c[0], c[1], c[2]);
    }

    /**
     * 给一个颜色叠上 alpha（0..1，越界自动钳住）。
     *
     * 参考 App 里「同一材质、厚度不同」全靠它：底栏 0.80、聊天输入区 0.62。
     * ⚠️ 别把 alpha 写进色值常量 —— 本项目沿用一条纪律：**色板只放不透明色，
     * 半透明在组件叠加时逐处决定**（"它有多透"只有一处定义）。
     */
    public static int withAlpha(int argb, double a) {
        int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    /** 线性混色：t=0 得 a，t=1 得 b。整套派生色（soft/ink/line）的基石。 */
    public static String mix(String a, String b, double t) {
        int[] A = rgb(a);
        int[] B = rgb(b);
        if (A == null) A = new int[]{0, 0, 0};
        if (B == null) B = new int[]{0, 0, 0};
        return hex(
                (int) Math.round(A[0] + (B[0] - A[0]) * t),
                (int) Math.round(A[1] + (B[1] - A[1]) * t),
                (int) Math.round(A[2] + (B[2] - A[2]) * t));
    }

    /** 带透明度的同色，返回 CSS 的 rgba() 文本（与 theme.mjs 逐字一致，便于对照）。 */
    public static String alpha(String h, double a) {
        int[] c = rgb(h);
        if (c == null) c = new int[]{0, 0, 0};
        return "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + a + ")";
    }

    /** WCAG 相对亮度（0 黑，1 白）。 */
    public static double lum(String h) {
        int[] c = rgb(h);
        if (c == null) return 0;
        return 0.2126 * toLinear(c[0]) + 0.7152 * toLinear(c[1]) + 0.0722 * toLinear(c[2]);
    }

    private static double toLinear(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG 对比度（1:1 ～ 21:1），保留两位小数 —— 断言直接比这个数。 */
    public static double contrast(String a, String b) {
        double l1 = lum(a);
        double l2 = lum(b);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        // ⚠ 返回**原始比值**，不 round 到两位小数（C4，2026-10-05 修）。
        //   真值落在 [4.495, 4.5) 的颜色会被 round 成 4.50，而判据是 `contrast(...) < AA_TEXT`（4.5）
        //   —— "差一点点"的颜色于是被**放行**。护栏的意义就是精确，不能自己四舍五入。
        //   要显示两位小数的调用点自行 String.format（审计输出见 audit()）。
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 这个底色算不算"暗" —— 决定用浅字还是深字。阈值 0.35 落在中间调里。 */
    public static boolean isDark(String h) {
        return lum(h) < 0.35;
    }

    // ---------------------------------------------------------------- 界面侧转换

    /** "#rrggbb" / "rgba(r,g,b,a)" / "#rgb" → Android 的 ARGB int。非法 → 中灰。 */
    public static int toArgb(String v) {
        if (v == null) return 0xFF808080;
        String s = v.trim();
        if (s.startsWith("rgba(") || s.startsWith("rgb(")) {
            try {
                String body = s.substring(s.indexOf('(') + 1, s.lastIndexOf(')'));
                String[] parts = body.split(",");
                int r = (int) Double.parseDouble(parts[0].trim());
                int g = (int) Double.parseDouble(parts[1].trim());
                int b = (int) Double.parseDouble(parts[2].trim());
                double a = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 1.0;
                return ((int) Math.round(Math.max(0, Math.min(1, a)) * 255) << 24)
                        | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            } catch (RuntimeException e) {
                return 0xFF808080;
            }
        }
        int[] c = rgb(s);
        if (c == null) return 0xFF808080;
        return 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /** 带指定不透明度的 ARGB int（卡片半透明用）。 */
    public static int argb(String hexColor, double a01) {
        return toArgb(alpha(hexColor, a01));
    }

    // ---------------------------------------------------------------- 预设与主色

    /** 一套背景主题。预设自带给全的中性色；自定义底由 {@link #customPreset} 解出来。 */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String hint;
        public final String mode;      // light / dark —— 后面所有派生都按它分支
        public final String bg;
        public final String bg2;
        public final String card;
        public final String fg;
        public final String sub;
        public final String line;
        /** 只有自定义底才有意义：完全解找到了吗。预设恒为 true。 */
        public final boolean ok;
        /** 只有自定义底才有意义：次优解在底色上的实测对比度。 */
        public final double baseContrast;

        Preset(String id, String name, String hint, String mode,
               String bg, String bg2, String card, String fg, String sub, String line) {
            this(id, name, hint, mode, bg, bg2, card, fg, sub, line, true, 21);
        }

        Preset(String id, String name, String hint, String mode,
               String bg, String bg2, String card, String fg, String sub, String line,
               boolean ok, double baseContrast) {
            this.id = id;
            this.name = name;
            this.hint = hint;
            this.mode = mode;
            this.bg = bg;
            this.bg2 = bg2;
            this.card = card;
            this.fg = fg;
            this.sub = sub;
            this.line = line;
            this.ok = ok;
            this.baseContrast = baseContrast;
        }
    }

    /** 一款主色：只存三个基准色（主、渐变亮端、浅色模式下的深字色），其余派生。 */
    public static final class Accent {
        public final String id;
        public final String name;
        public final String pri;
        public final String pri2;
        public final String deep;

        Accent(String id, String name, String pri, String pri2, String deep) {
            this.id = id;
            this.name = name;
            this.pri = pri;
            this.pri2 = pri2;
            this.deep = deep;
        }
    }

    public static final List<Preset> PRESETS = new ArrayList<Preset>(Arrays.asList(
            // 首位即默认。「初雪」照参考 App（Yuki 初雪）的扁平风色板：
            // 浅灰底 + 纯白卡 + 极浅分隔线。
            new Preset("yuki", "初雪", "默认 · 扁平浅灰（照参考 App）", "light",
                    "#f5f7fa", "#e9f0fb", "#ffffff", "#1b1d22", "#8a8f99", "#e6e8ec"),
            new Preset("clear", "清蓝", "冷调浅色", "light",
                    "#f4f6fa", "#e7eef9", "#ffffff", "#1b1f27", "#5d6675", "#e6e9f0"),
            new Preset("warm", "暖阳", "米黄 · 久看不刺眼", "light",
                    "#faf5ed", "#f2e7d8", "#fffdf9", "#2b2419", "#6d6151", "#ece2d2"),
            new Preset("leaf", "护眼", "浅绿 · 柔", "light",
                    "#eef4ef", "#dfeae1", "#ffffff", "#1c2620", "#5b6a60", "#dfe9e1"),
            new Preset("dusk", "暮色", "浅紫灰 · 安静", "light",
                    "#f5f4fb", "#e9e6f8", "#ffffff", "#23222b", "#64627a", "#e8e6f2"),
            new Preset("night", "暗夜", "深灰 · 关灯用", "dark",
                    "#12151b", "#1a2029", "#1c212a", "#e9edf4", "#98a3b3", "#2b313c"),
            new Preset("deep", "深海", "深蓝 · 关灯用", "dark",
                    "#0d1a22", "#132936", "#15232d", "#e6f0f6", "#8fa6b3", "#22333f")));

    public static final List<Accent> ACCENTS = new ArrayList<Accent>(Arrays.asList(
            // 首位即默认。参考里用户明确否掉了紫色（「别用紫色，很降档次」）→ 纯蓝 #2F6BFF。
            new Accent("yuki", "纯蓝", "#2f6bff", "#6e9bff", "#1b4fd8"),
            new Accent("blue", "蓝", "#2b6cf0", "#5b8def", "#1e52bf"),
            new Accent("cyan", "青", "#0e93a8", "#3ab4c4", "#0a7182"),
            new Accent("green", "绿", "#12a05c", "#3cc07f", "#0b7a45"),
            new Accent("violet", "紫", "#7b5cf0", "#8a6ef0", "#5b3fd0"),
            new Accent("amber", "橙", "#e07819", "#f09b45", "#b45c0e"),
            new Accent("rose", "玫", "#e0486e", "#f07292", "#b32c50"),
            new Accent("slate", "墨", "#4a5568", "#6b7787", "#333c4a")));

    /**
     * 一套**界面风格** —— 与「背景主题」是正交的两维：
     * 主题回答"什么颜色"，风格回答"什么形状 / 字体 / 质感"。
     *
     * 上一版把这一维整个删掉了（只留颜色），是需求做窄了 ——
     * 用户要的「像素风 / 现代化 / 古风」属于这里。
     */
    public static final class Style {
        public final String id;
        public final String name;
        public final String hint;
        public final int radiusDp;
        public final int borderWidthDp;   // 0 = 不描边
        public final String font;         // sans / mono / serif（⚠ 对**中文**几乎无效）
        public final int shadowDp;         // Android 的 elevation（柔和阴影）
        /**
         * 像素风的**硬阴影**：卡片右下角错位一个实色块（8-bit UI 最标志性的东西）。
         * 0 = 不用。柔和阴影（elevation）和它是两种语言，不能互相代替。
         */
        public final int offsetDp;
        /**
         * 字距（em）。**这是中文唯一有效的"字体气质"维度** ——
         * Typeface 的 serif/mono/sans 在汉字上多半回退到同一套字库，
         * 调 family 用户根本看不出来，调字距才看得出来。
         */
        public final float letterSpacing;
        /**
         * 卡片的**标志性装饰**：none = 纯色卡片；grid = 双层硬边（像素风）；
         * corner = 四角界格（古风的"界格/角饰"）。
         */
        public final String decor;
        /** 区块标题前是否画一条主色竖线（古风的"题签"感）。 */
        public final boolean titleRule;

        /**
         * 四个**按风格派生**的形状刻度 —— 对应参考 App `Shape.kt` 里的
         * `FieldCorner 12` / `ButtonCorner 12` / `BubbleCorner 18` / `SheetCorner 24`。
         *
         * 参考把它们写成固定值（它只有浅色一套）；天枢有 4 套风格，所以这里按 `radiusDp` 派生。
         * **初雪（radiusDp=16）下这四条正好得出参考的那四个值**（12/12/18/24）——
         * 这就是"照参考"与"风格正交"能同时成立的那条线。
         *
         * ⚠️ 别把它们改成全局常量：像素风 `radiusDp=0`，全局常量会让它的输入框变成圆角 12，
         * 而断言还在断言"像素风 radius == 0" —— **断言全绿，界面已经不是像素风了**。
         */
        public int fieldRadius() {
            return Math.max(0, radiusDp - 4);
        }

        public int buttonRadius() {
            return Math.max(0, radiusDp - 4);
        }

        /** 气泡比卡片更圆一档（Card 16 → Bubble 18）；方角风格保持方角。 */
        public int bubbleRadius() {
            return radiusDp <= 0 ? 0 : radiusDp + 2;
        }

        /** 弹层/对话框最圆（Card 16 → Sheet 24）；方角风格保持方角。 */
        public int sheetRadius() {
            return radiusDp <= 0 ? 0 : radiusDp + 8;
        }

        Style(String id, String name, String hint, int radiusDp, int borderWidthDp,
              String font, int shadowDp, int offsetDp, float letterSpacing,
              String decor, boolean titleRule) {
            this.id = id;
            this.name = name;
            this.hint = hint;
            this.radiusDp = radiusDp;
            this.borderWidthDp = borderWidthDp;
            this.font = font;
            this.shadowDp = shadowDp;
            this.offsetDp = offsetDp;
            this.letterSpacing = letterSpacing;
            this.decor = decor;
            this.titleRule = titleRule;
        }
    }

    /**
     * 界面风格 —— **当前只保留「初雪」一套**（用户 2026-10-04 明确：其他几套他都不满意，删掉）。
     *
     * 「初雪」= 参考那套卡片的材质：16 圆角 + 极细描边 + 极轻柔和投影。
     * 参考里那条路是试出来的 —— 对角渐变在真机上表现为「卡片右边一条浅色带」，
     * 用户当噪声否掉了；所以这里只用圆角 + 描边 + 阴影，不用渐变。
     *
     * ⚠ 只删了"选项"，没删"能力"：{@link Style} 的结构、按 radiusDp 派生的四档圆角、
     * {@link StyleDecor} 的多风格分支都留着 —— 将来想再加一套，往这个列表补一条即可，
     * 形状刻度全按 radiusDp 派生，不会牵动别处（见坑 54）。
     * 唯一入口是这一份列表，所以"别的风格消失"= 这里只剩一条，界面自然只剩一张卡。
     */
    public static final List<Style> STYLES = new ArrayList<Style>(Arrays.asList(
            // 首位即默认。
            new Style("yuki", "初雪", "扁平 · 16 圆角 · 极细边 · 轻投影",
                    16, 1, "sans", 3, 0, 0f, "none", false)));

    public static Style styleById(String id) {
        if (id == null) return null;
        for (Style s : STYLES) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    /** 取不到返回 null（由 {@link Config#normalize} 兜底到默认）。 */
    public static Preset presetById(String id) {
        if (id == null) return null;
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    public static Accent accentById(String id) {
        if (id == null) return null;
        for (Accent a : ACCENTS) if (a.id.equals(id)) return a;
        return null;
    }

    // ---------------------------------------------------------------- 自定义底色求解

    /** 求解结果。 */
    public static final class Solved {
        public final String card;
        public final String fg;
        public final boolean dark;
        /** 完全解找到了吗（中灰底这一段物理上无解，必须如实为 false）。 */
        public final boolean ok;
        public final double baseContrast;

        Solved(String card, String fg, boolean dark, boolean ok, double baseContrast) {
            this.card = card;
            this.fg = fg;
            this.dark = dark;
            this.ok = ok;
            this.baseContrast = baseContrast;
        }
    }

    /**
     * 给定一个自定义底色，**解**出「卡片色 + 文字色 + 明暗」。
     *
     * 为什么是解而不是定规则：中灰底是自定义配色的陷阱 —— 白卡配深字、灰卡配浅字，
     * 两边都够不着 4.5:1。按"亮底白卡 / 暗底深卡"的死规则做，用户选个中灰就是一片看不清。
     *
     * 两段式：
     *   ① 先找**完全解** —— 卡片上 ≥7:1 且底色上 ≥4.5:1 且卡与底能分开。
     *      取第一个（遍历顺序即偏好：卡比底亮 + 深字排最前，最像常规浅色界面）。
     *   ② 完全解不存在时（只有 #6f6f6f～#828282 这一段灰阶会这样：**任何**字色在那种
     *      底色上都到不了 4.5:1，这是物理事实不是实现问题），退回**次优解** ——
     *      仍满足卡片上的判据，底色上的文字取两种字色里对比更高的那个。
     *      返回 ok=false 让界面能如实提示用户。
     */
    public static Solved solveCustom(String bg) {
        List<String> cards = new ArrayList<String>();
        List<Boolean> above = new ArrayList<Boolean>();
        for (double k = 0.06; k <= 0.99; k += 0.06) {
            cards.add(mix(bg, "#ffffff", k));
            above.add(Boolean.TRUE);
        }
        for (double k = 0.06; k <= 0.66; k += 0.06) {
            cards.add(mix(bg, "#000000", k));
            above.add(Boolean.FALSE);
        }

        Solved best = null;
        for (int i = 0; i < cards.size(); i++) {
            String card = cards.get(i);
            if (Math.abs(lum(card) - lum(bg)) < MIN_SEPARATION) continue;
            String[] inks = above.get(i).booleanValue()
                    ? new String[]{INK_DARK, INK_LIGHT}
                    : new String[]{INK_LIGHT, INK_DARK};
            for (String ink : inks) {
                if (contrast(ink, card) < MIN_CARD_CONTRAST) continue;
                double base = contrast(ink, bg);
                if (base >= AA_TEXT) {
                    return new Solved(card, ink, ink.equals(INK_LIGHT), true, base);
                }
                if (best == null || base > best.baseContrast) {
                    best = new Solved(card, ink, ink.equals(INK_LIGHT), false, base);
                }
            }
        }
        // 兜底：理论上到不了这里（卡片判据总能满足），但界面绝不能因为配色解不出来就崩
        if (best != null) return best;
        return new Solved(mix(bg, "#ffffff", 0.5), INK_DARK, false, false, contrast(INK_DARK, bg));
    }

    /** 自定义底色 → 一整套中性色。 */
    public static Preset customPreset(String bgRaw, String bg2Raw) {
        String bg = isHex(bgRaw) ? normHex(bgRaw) : DEFAULT_BG;
        Solved s = solveCustom(bg);
        boolean dark = s.dark;
        String bg2 = isHex(bg2Raw)
                ? normHex(bg2Raw)
                // 没给渐变第二色时，用底色自己微调一档：纯色底也有一点层次，不至于"死平"
                : mix(bg, dark ? "#ffffff" : "#000000", dark ? 0.06 : 0.04);
        return new Preset("custom", "自定义", "你选的颜色", dark ? "dark" : "light",
                bg, bg2, s.card, s.fg, readableSub(s.card, s.fg),
                dark ? mix(bg, "#ffffff", 0.12) : mix(bg, "#000000", 0.09),
                s.ok, s.baseContrast);
    }

    /**
     * 次要文字色：在卡片上对比达标的**最深**那个（越浅越"灰"越好看，但不达标就往下压）。
     *
     * 前提：fg 本来就在 card 上达标（调用方传的是 solveCustom 解出的 card/fg 对）。
     * 输入不自洽时退回 fg —— 那是两种字色里对比最高的，不会再差。
     */
    public static String readableSub(String card, String fg) {
        for (double t = 0.55; t <= 0.94; t += 0.04) {
            String s = mix(card, fg, t);
            if (contrast(s, card) >= AA_TEXT) return s;
        }
        return fg;
    }

    /**
     * 把 base 朝 target 推，直到它与**每一个**底色都达到对比度要求。
     *
     * 为什么不能写死比例：派生色以前是"混 30% 黑"这样的定值 —— 在预设那几套底色上
     * 是调好了的，可底色一旦由用户给（深蓝 #204060 这种既不算亮也不算很黑的），
     * 同一个定值就掉到 3.8:1、3.3:1，按钮字就发灰发虚。这里改成推到够为止：
     * 目标色（黑或白）一定能达标，所以循环必然收敛；返回最靠近 base 的那个合格色，
     * 尽量保留主色的性格。
     */
    public static String towards(String base, String target, String on, double min) {
        return towards(base, target, Arrays.asList(on), min);
    }

    public static String towards(String base, String target, List<String> ons, double min) {
        for (double t = 0; t <= 1.0001; t += 0.04) {
            String c = mix(base, target, t);
            boolean ok = true;
            for (String on : ons) {
                if (contrast(c, on) < min) {
                    ok = false;
                    break;
                }
            }
            if (ok) return c;
        }
        return target;
    }

    /**
     * 主色上该写什么颜色的字 —— 不能一律白色。
     *
     * 橙、青、绿这几个主色上写白字只有 3～3.7:1（按钮上的文字不够看），
     * 换成近黑反而有 5～6:1。所以按对比度**选**，而不是按手感定死白色。
     */
    public static String onColor(String pri, String pri2) {
        // 渐变块上的字要照顾**两端**：主色端按正文要求，亮端是装饰性的渐变收尾，按 3 算
        return fits(pri, pri2, "#ffffff") ? "#ffffff"
                : fits(pri, pri2, INK_ON_BRIGHT) ? INK_ON_BRIGHT
                : "#ffffff";
    }

    private static boolean fits(String pri, String pri2, String ink) {
        if (contrast(ink, pri) < AA_TEXT) return false;
        return pri2 == null || contrast(ink, pri2) >= Math.min(AA_TEXT, MIN_BIG_CONTRAST);
    }

    // ---------------------------------------------------------------- 配置

    /**
     * 卡片不透明度的取值。
     *
     * 夹取，而不是"越界就回默认" —— 手改配置写个 0.75 要能用。
     * 只有**压根不是数**（null / 空串 / 对象 / 乱写）才回 1：那是升级前的样子，
     * 不能因为配置里多了个看不懂的值，就让所有人的界面突然变透明。
     */
    public static double cardAlphaOf(Object v) {
        double n;
        if (v instanceof Number) {
            n = ((Number) v).doubleValue();
        } else if (v instanceof String && !((String) v).trim().isEmpty()) {
            try {
                n = Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return CARD_ALPHA_MAX;
            }
        } else {
            return CARD_ALPHA_MAX;
        }
        if (Double.isNaN(n) || Double.isInfinite(n)) return CARD_ALPHA_MAX;
        if (n < CARD_ALPHA_MIN) return CARD_ALPHA_MIN;
        if (n > CARD_ALPHA_MAX) return CARD_ALPHA_MAX;
        return n;
    }

    /**
     * 背景图路径的合法性。
     *
     * 只认"以 / 开头的绝对路径"，且路径里不许出现会破坏布局/存储的字符。
     * 配置文件是用户能手改的，放任引号、括号、反斜杠进来等于开了个口子。
     */
    public static boolean isBgPath(String s) {
        if (s == null || s.length() <= 1 || s.charAt(0) != '/') return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '(' || c == ')' || c == '\\' || c == '\n' || c == '\r') {
                return false;
            }
        }
        return true;
    }

    /** 一份外观配置。不可变；换配置请走 {@link #normalize}。 */
    public static final class Config {
        public final String preset;      // 背景主题（颜色）
        public final String accent;      // 主色
        public final String style;       // 界面风格（形状/字体）—— 与前两维正交
        public final String customBg;
        public final String customBg2;
        public final String image;
        public final double cardAlpha;

        /** 旧签名（不带风格）：风格取默认「现代化」—— 老调用点与既有断言都不用改。 */
        public Config(String preset, String accent, String customBg, String customBg2,
                      String image, double cardAlpha) {
            this(preset, accent, "modern", customBg, customBg2, image, cardAlpha);
        }

        public Config(String preset, String accent, String style, String customBg,
                      String customBg2, String image, double cardAlpha) {
            this.preset = preset;
            this.accent = accent;
            this.style = style;
            this.customBg = customBg;
            this.customBg2 = customBg2;
            this.image = image;
            this.cardAlpha = cardAlpha;
        }

        /** 默认：**初雪**（照参考 App 的扁平风）+ 不透明无图。 */
        public static Config defaults() {
            return new Config("yuki", "yuki", "yuki", DEFAULT_BG, "", "", CARD_ALPHA_MAX);
        }

        /** 就地归一化：任何输入都变成合法配置。 */
        public Config normalized() {
            return normalize(this);
        }

        /** 转个 preset 或 accent，其余不动。 */
        public Config with(String newPreset, String newAccent) {
            return normalize(new Config(
                    newPreset == null ? preset : newPreset,
                    newAccent == null ? accent : newAccent,
                    style, customBg, customBg2, image, cardAlpha));
        }

        /** 换界面风格（形状 / 字体 / 质感），颜色一概不动。 */
        public Config withStyle(String newStyle) {
            return normalize(new Config(preset, accent,
                    newStyle == null ? style : newStyle,
                    customBg, customBg2, image, cardAlpha));
        }

        /** 换自定义底色/渐变。 */
        public Config withCustom(String bg, String bg2) {
            return normalize(new Config("custom", accent, style, bg, bg2, image, cardAlpha));
        }

        /** 换卡片透明度。 */
        public Config withAlpha(double a) {
            return normalize(new Config(preset, accent, style, customBg, customBg2, image, a));
        }

        /** 换背景图（空串 = 不用图片，合法值）。 */
        public Config withImage(String path) {
            return normalize(new Config(preset, accent, style, customBg, customBg2, path, cardAlpha));
        }

        public String toJson() {
            return "{\"v\":1"
                    + ",\"preset\":" + q(preset)
                    + ",\"accent\":" + q(accent)
                    + ",\"style\":" + q(style)
                    + ",\"custom\":{\"bg\":" + q(customBg) + ",\"bg2\":" + q(customBg2) + "}"
                    + ",\"image\":" + q(image)
                    + ",\"cardAlpha\":" + trim(cardAlpha)
                    + "}";
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String trim(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /**
     * 任何输入 → 合法配置。**永不抛异常**（配置文件被手改坏是常态，
     * 那种时候界面必须照常打开，回落默认即可）。
     */
    public static Config normalize(Config raw) {
        Config r = raw == null ? Config.defaults() : raw;
        // 兜底回落到**列表首位**（= 默认），不写死某个 id ——
        // 写死的话，换默认主题时极易漏改（这一轮从「清蓝」切到「初雪」时就踩到了）。
        String preset = "custom".equals(r.preset) ? "custom"
                : presetById(r.preset) != null ? r.preset : PRESETS.get(0).id;
        String accent = accentById(r.accent) != null ? r.accent : ACCENTS.get(0).id;
        String style = styleById(r.style) != null ? r.style : STYLES.get(0).id;
        // 空串 = 用户没要渐变，这是合法值（不是错误），所以不能塌成默认色
        String bg2 = r.customBg2 == null || r.customBg2.isEmpty() ? ""
                : isHex(r.customBg2) ? normHex(r.customBg2) : "";
        return new Config(
                preset,
                accent,
                style,
                isHex(r.customBg) ? normHex(r.customBg) : DEFAULT_BG,
                bg2,
                isBgPath(r.image) ? r.image : "",
                cardAlphaOf(Double.valueOf(r.cardAlpha)));
    }

    /** 从磁盘上那份 JSON 读配置；读不出来/坏掉一律回落默认（绝不抛）。 */
    public static Config parse(String json) {
        if (json == null || json.trim().isEmpty()) return Config.defaults();
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return Config.defaults();
        String customBg = null;
        String customBg2 = null;
        Map<String, Object> c = Json.map(m.get("custom"));
        if (c != null) {
            customBg = Json.str(c.get("bg"));
            customBg2 = Json.str(c.get("bg2"));
        }
        return normalize(new Config(
                Json.str(m.get("preset")),
                Json.str(m.get("accent")),
                Json.str(m.get("style")),
                customBg,
                customBg2,
                Json.str(m.get("image")),
                cardAlphaOf(m.get("cardAlpha"))));
    }

    /** 配置里生效的那套中性色（预设或自定义派生）。 */
    public static Preset palette(Config raw) {
        Config c = normalize(raw);
        if ("custom".equals(c.preset)) return customPreset(c.customBg, c.customBg2);
        Preset p = presetById(c.preset);
        return p != null ? p : presetById("clear");
    }

    // ---------------------------------------------------------------- token 表

    /**
     * 配置 → 整套界面 token（颜色一律是 hex 或 rgba() 文本，与 theme.mjs 逐字一致）。
     *
     * 用 {@link #toArgb} 转成 Android 的 int；卡片带透明度时用 {@link #argb}。
     *
     * 卡片透明度那块是重点：卡片里的字有**两个**可能落在的底色，必须两头都站得住 ——
     *   ① cardSolid —— 卡片自己那层（字主要压在它上面）；
     *   ② bg        —— 卡片一透，底色/照片就透上来。
     * 只满足其中一头都会出事：只顾卡片，透光后字会糊在底色上；只顾底色，深色主题下
     * 字会被推得太亮，反而压不住卡片本身。所以把两个底一起交给 towards 去解。
     */
    public static final class Tokens {
        // ---- 形状：由「界面风格」决定 ----
        public int radiusXs, radius, radiusLg, hairWidth, shadow;
        // 形状刻度（按风格派生，见 Style.fieldRadius 等）—— 对应参考 App 的
        // FieldCorner / ButtonCorner / BubbleCorner / SheetCorner。
        public int fieldRadius, buttonRadius, bubbleRadius, sheetRadius;
        public String font;                  // sans / mono / serif
        public String styleId, styleName;
        public String decor;                 // none / grid / corner
        public boolean titleRule;
        public int offsetDp;                 // 像素风硬阴影的错位量
        public float letterSpacing;          // 字距（em）—— 中文唯一有效的字体气质维度

        // ---- 颜色：由「背景主题」与「主色」决定 ----
        public String mode, preset, accent, presetName;
        public String bg, bg2, line, hair;
        public String card, cardSolid;
        public double cardAlpha;
        public String fg, sub;
        public String fieldBg, logBg, placeholder, noteFg;
        public String pri, pri2, priSoft, priInk, priDeep, onPri, grad;
        public String ok, okSoft, okInk;
        public String danger, dangerSoft, dangerInk;
        public String warn, warnSoft, warnInk;
        public String onDanger;
        public String image, scrim;

        /** 卡片背景的 ARGB（含透明度）—— 界面侧直接用这个。 */
        public int cardArgb() {
            return cardAlpha >= 1 ? Theme.toArgb(card) : Theme.argb(card, cardAlpha);
        }

        /** 输入框/日志底的 ARGB（比卡片更实一档，否则看不出"这里是个框"）。 */
        public int fieldArgb() {
            return Theme.toArgb(fieldBg);
        }

        public int logArgb() {
            return Theme.toArgb(logBg);
        }
    }

    public static Tokens tokens(Config raw) {
        Config c = normalize(raw);
        Preset p = palette(c);
        Accent a = accentById(c.accent);
        if (a == null) a = ACCENTS.get(0);
        Style st = styleById(c.style);
        if (st == null) st = STYLES.get(0);
        boolean dark = "dark".equals(p.mode);
        double cardAlpha = c.cardAlpha;

        Tokens t = new Tokens();
        // 形状那一组全部来自界面风格 —— 不再是写死的一套
        t.styleId = st.id;
        t.styleName = st.name;
        t.font = st.font;
        t.radius = st.radiusDp;
        t.radiusLg = st.radiusDp;
        t.radiusXs = Math.max(0, st.radiusDp - 6);   // 小件（输入框/色块）比卡片再收一点
        t.hairWidth = st.borderWidthDp;              // 0 = 不描边
        t.shadow = st.shadowDp;
        t.decor = st.decor;
        // 形状刻度一律**按风格派生**，不是全局常量 —— 像素风 radiusDp=0 时必须全部归零
        t.fieldRadius = st.fieldRadius();
        t.buttonRadius = st.buttonRadius();
        t.bubbleRadius = st.bubbleRadius();
        t.sheetRadius = st.sheetRadius();
        t.titleRule = st.titleRule;
        t.offsetDp = st.offsetDp;
        t.letterSpacing = st.letterSpacing;

        t.mode = p.mode;
        t.preset = c.preset;
        t.accent = c.accent;
        t.presetName = p.name;

        t.bg = p.bg;
        t.bg2 = p.bg2;
        t.line = p.line;
        t.hair = dark ? "rgba(255,255,255,.07)" : "rgba(16,24,40,.05)";

        // ---- 卡片透明度 ----
        String cardSolid = cardAlpha >= 1 ? p.card : mix(p.card, p.bg, 1 - cardAlpha);
        String fgTo = dark ? "#ffffff" : "#000000";
        // 文字既在卡片上，也**直接压在页面底色上**（对话页副标题「就绪」、启动页状态行、
        // 各级说明文字…）。所以两个底面都得达标 —— 原先 alpha=1 时只约束卡片，
        // 于是用户给的深色底（外观页自己举的例子 #204060）让次要文字掉到 3.59:1，
        // 而那一页明明承诺「不会出现看不清的字」。预设主题本来就达标，加不加 p.bg 结果一样。
        // 设了背景图时，页面底**不是** `p.bg`，而是「scrim over 照片」——照片任一像素 ∈ [0,255]，
        // 所以可达面就是两端：`scrim over #000000` 与 `scrim over #ffffff`（C6，2026-10-05）。
        // 不设图时两端退化成单个 `p.bg`，与改动前逐字一致。
        List<String> pageBases = pageFaces(p.bg, c.image);
        List<String> cardBases = cardFaces(p.card, p.bg, c.image, cardAlpha);
        List<String> textBases = new ArrayList<String>(cardBases);
        textBases.addAll(pageBases);

        t.card = p.card;
        t.cardSolid = cardSolid;
        t.cardAlpha = cardAlpha;
        t.fg = towards(p.fg, fgTo, textBases, AA_TEXT);
        t.sub = towards(p.sub, fgTo, textBases, AA_TEXT);

        // 输入框 / 日志底要比卡片更实一档：同样透明的话它们跟卡片一个色，
        // 就看不出"这里是个框"了。+0.30 封顶到 1。
        double innerAlpha = Math.min(1, cardAlpha + 0.30);
        String innerField = dark ? mix(p.card, p.bg, 0.35) : p.card;
        String innerLog = dark ? mix(p.card, "#000000", 0.18) : "#fafbfd";
        t.fieldBg = cardAlpha >= 1 ? innerField : alpha(innerField, innerAlpha);
        t.logBg = cardAlpha >= 1 ? innerLog : alpha(innerLog, innerAlpha);
        t.placeholder = dark ? mix(p.sub, p.bg, 0.35) : "#9aa3b2";

        // 直接落在页面底色上的文字：次要色够看就用次要色，不够就升到正文色 ——
        // 底色的对比是有物理上限的（见 solveCustom）
        // 回退用**已按对比度调过的** t.fg，不是原色 p.fg —— 原色在中间调底色（#808080）
        // 上只有 4.18:1，回退反而比它要救的那个色更差。
        t.noteFg = allAtLeast(p.sub, pageBases, AA_TEXT) ? p.sub : t.fg;

        // ---- 主色及其派生（一律现算，不写死）----
        t.pri = a.pri;
        t.pri2 = a.pri2;
        t.priSoft = dark ? mix(a.pri, p.bg, 0.78) : mix(a.pri, "#ffffff", 0.90);
        // priInk 有两副面孔：既当「浅底上的主色字」（ghost 按钮 / chip / 气泡），
        // 也直接当强调标题色压在卡片和**页面底色**上（启动页「天枢」、各分区标题）。
        // 原先只对 priSoft 约束，于是自定义底色 #808080 时它掉到 1.36:1（几乎看不见）。
        List<String> priBases = new ArrayList<String>();
        priBases.add(t.priSoft);
        priBases.addAll(cardBases);
        priBases.addAll(pageBases);
        t.priInk = towards(a.pri, fgTo, priBases, AA_TEXT);
        t.priDeep = towards(a.pri, "#000000", "#ffffff", AA_TEXT);
        t.onPri = onColor(a.pri, a.pri2);
        t.grad = a.pri + "," + a.pri2;

        // ---- 语义色 ----
        t.ok = OK;
        t.okSoft = dark ? mix(OK, p.bg, 0.80) : mix(OK, "#ffffff", 0.88);
        t.okInk = towards(OK, fgTo, t.okSoft, AA_TEXT);
        t.danger = DANGER;
        t.dangerSoft = dark ? mix(DANGER, p.bg, 0.80) : mix(DANGER, "#ffffff", 0.88);
        t.dangerInk = towards(DANGER, fgTo, t.dangerSoft, AA_TEXT);
        t.warn = WARN;
        t.warnSoft = dark ? mix(WARN, p.bg, 0.80) : mix(WARN, "#ffffff", 0.88);
        t.warnInk = towards(WARN, fgTo, t.warnSoft, AA_TEXT);
        t.onDanger = onColor(DANGER, null);

        // ---- 背景图与遮罩 ----
        t.image = c.image;
        t.scrim = alpha(p.bg, SCRIM_ALPHA);   // 图片上压一层底色遮罩，照片再花字也读得清

        return t;
    }

    // ---------------------------------------------------------------- 护栏

    /** 一条不达标的对比度。 */
    public static final class Failure {
        public final String label;
        public final String fg;
        public final String bg;
        public final double ratio;
        public final double min;

        Failure(String label, String fg, String bg, double ratio, double min) {
            this.label = label;
            this.fg = fg;
            this.bg = bg;
            this.ratio = ratio;
            this.min = min;
        }

        @Override
        public String toString() {
            return label + " " + fmt(ratio) + " < " + min + "  (" + fg + " on " + bg + ")";
        }
    }

    /**
     * 护栏：对**任意**配置检查对比度是否达标。空清单 = 通过。
     *
     * 求解器（solveCustom/towards）本身就以保证达标为目标，但"以为保证了"和
     * "确实保证了"是两回事 —— 这个函数是把它证出来的那一个。
     *
     * 注：这里**不**断言"卡片与底色要能分开"。那是美感不是可读性 ——
     * 深色主题的卡片与底色本来就贴得很近，靠描边分界；低透明度更是用户自己的选择。
     * MIN_SEPARATION 只在 solveCustom 内部筛候选，不作为对外的硬门禁。
     */
    public static List<Failure> audit(Config cfg) {
        Config c = normalize(cfg);
        Tokens t = tokens(c);
        Preset p = palette(c);
        List<Failure> fails = new ArrayList<Failure>();

        need(fails, "fg on card", t.fg, t.cardSolid, AA_TEXT);
        need(fails, "fg on bg", t.fg, p.bg, AA_TEXT);
        need(fails, "sub on card", t.sub, t.cardSolid, AA_TEXT);
        // 次要文字与强调标题色都**直接压在页面底色上**，不只是在卡片上 ——
        // 漏查这一格时，用户自定义的底色（外观页举的例 #204060）会让它们掉到 3.6:1。
        need(fails, "sub on bg", t.sub, p.bg, AA_TEXT);
        need(fails, "priInk on bg", t.priInk, p.bg, AA_TEXT);
        need(fails, "priInk on card", t.priInk, t.cardSolid, AA_TEXT);
        need(fails, "onPri on pri", t.onPri, t.pri, AA_TEXT);
        need(fails, "noteFg on bg", t.noteFg, p.bg, AA_TEXT);
        need(fails, "okInk on okSoft", t.okInk, t.okSoft, AA_TEXT);
        need(fails, "dangerInk on dangerSoft", t.dangerInk, t.dangerSoft, AA_TEXT);

        // 设了背景图：上面那些以 `p.bg` / `cardSolid` 为底的检查，只代表「照片恰好等于底色」那一面。
        // 照片可达的**两端**必须逐面再查一遍 —— 否则护栏会**漏判**：
        // 实测 224/224 组合曾全绿、而真实最坏全部 < 4.5（C6，2026-10-05）。
        // 不设图时下面两个循环一个都不进（face 恒等于 p.bg / cardSolid），行为与改动前一致。
        for (String face : pageFaces(p.bg, c.image)) {
            if (face.equals(p.bg)) continue;
            need(fails, "fg on bg(照片端 " + face + ")", t.fg, face, AA_TEXT);
            need(fails, "sub on bg(照片端 " + face + ")", t.sub, face, AA_TEXT);
            need(fails, "priInk on bg(照片端 " + face + ")", t.priInk, face, AA_TEXT);
            need(fails, "noteFg on bg(照片端 " + face + ")", t.noteFg, face, AA_TEXT);
        }
        for (String face : cardFaces(p.card, p.bg, c.image, c.cardAlpha)) {
            if (face.equals(t.cardSolid)) continue;
            need(fails, "fg on card(照片端 " + face + ")", t.fg, face, AA_TEXT);
            need(fails, "sub on card(照片端 " + face + ")", t.sub, face, AA_TEXT);
            need(fails, "priInk on card(照片端 " + face + ")", t.priInk, face, AA_TEXT);
        }
        return fails;
    }

    /**
     * 设了背景图时，**页面底**（`scrim over 照片`）的可达面。
     *
     * 照片任一像素 ∈ [0,255] ⇒ 两端 `scrim over #000000` 与 `scrim over #ffffff` 都真实可达
     * （照片里总能有纯黑 / 纯白区），按它们判就是按最坏判。
     * 不设图时根背景是渐变、`bg` 是它的中值近似 ⇒ 返回单元素，调用方行为不变。
     */
    static List<String> pageFaces(String bg, String image) {
        if (image == null || image.isEmpty()) return Arrays.asList(bg);
        double w = 1 - SCRIM_ALPHA;
        return Arrays.asList(bg, mix(bg, "#000000", w), mix(bg, "#ffffff", w));
    }

    /**
     * **卡片面**的可达集合（卡片叠在页面底之上）。
     * 不透明卡片（α ≥ 1）照片透不上来 ⇒ 只有一个面 `card`。
     */
    static List<String> cardFaces(String card, String bg, String image, double cardAlpha) {
        if (cardAlpha >= 1) return Arrays.asList(card);
        List<String> out = new ArrayList<String>();
        for (String surf : pageFaces(bg, image)) out.add(mix(card, surf, 1 - cardAlpha));
        return out;
    }

    /** `fg` 是否对列表里**每一个**底面都达标（用于决定次要色能不能用）。 */
    static boolean allAtLeast(String fg, List<String> bases, double min) {
        if (fg == null) return false;
        for (String b : bases) if (contrast(fg, b) < min) return false;
        return true;
    }

    /**
     * 护栏判据的**量化地板容差**（C4 附带发现，2026-10-05 实测）。
     *
     * 颜色是 8 位量化的：预设主色 `#2f6bff` 上的白字真值 = **4.4987671**，距 4.5 差 1.23e-3，
     * 而"相邻可调色阶"的对比度步长约 **0.06**（大两个数量级）—— 在那个色附近**造不出更优解**。
     * 所以判据允许这点地板误差：2e-3 覆盖实测的 1.23e-3，且**远小于** C4 那个洞
     * （round 放行 `[4.495, 4.5)`，宽 5e-3）—— 洞是"函数的错"，地板是"色空间的限"，别混为一谈。
     *
     * ⓘ 另一条更纯的路：把 `#2f6bff` 这类预设主色微调到真值达标（外观会有极小变化）——
     *   那属于色板/外观决策，留给容器侧定；本轮先取容差（不动外观）。
     */
    public static final double CONTRAST_EPS = 2e-3;

    private static void need(List<Failure> out, String label, String fg, String bg, double min) {
        if (fg == null || bg == null) {
            out.add(new Failure(label, fg, bg, 0, min));
            return;
        }
        double r = contrast(fg, bg);
        if (r + CONTRAST_EPS < min) out.add(new Failure(label, fg, bg, r, min));
    }

    /** 人类可读的护栏报告。 */
    public static String auditReport(Config cfg) {
        Config c = normalize(cfg);
        List<Failure> f = audit(c);
        StringBuilder sb = new StringBuilder();
        sb.append("外观「").append(c.preset).append('/').append(c.accent)
                .append("」alpha=").append(fmt(c.cardAlpha));
        if (f.isEmpty()) return sb.append(" 护栏 GREEN").toString();
        sb.append(" 护栏 RED");
        for (Failure x : f) sb.append("\n  ").append(x);
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
