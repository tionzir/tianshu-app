package dev.tianshu.host;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link Theme} 算出的 token **应用到真实的 View 树**上，并负责配置读写。
 *
 * 分工（这条边界很重要）：
 *   - 色板 token → 底色/背景图/文字色/卡片底/按钮底
 *   - 形状 token → 圆角、描边、阴影
 *   - **结构一概不动** —— 这正是硬不变量 B「换外观只改外观，不改结构」在实现层的落法：
 *     换肤走的是"给已有 View 改属性"，没有任何增删元素的分支。
 *
 * 界面侧用 `<b>角色标记</b>`（{@link #tag}）告诉本类某个 View 属于哪种语义，
 * 而不是靠猜 —— 猜错会让「次要文字」和「正文」在换肤后糊成一片。
 *
 * 诚实边界：毛玻璃那类**真模糊**在本构建链下做不了（编译目标是 android-23 的
 * android.jar，RenderEffect 是 API 31 才引入的）。所以背景图那一路用的是
 * 「渐变垫底 + 图 + 半透明底色遮罩」三层叠法，与参照物的 CSS 三层一致；
 * 卡片的"毛玻璃质感"则由 {@link StyleDecor} 用「极淡光泽 + 受光高光 + 细边」近似。
 */
public final class Theming {

    private static final String PREFS = "tianshu-appearance";
    /** 新版：整份配置的 JSON。 */
    private static final String KEY_CFG = "cfg";

    /** 语义角色标记。 */
    public static final String ROLE_MUTED = "muted";     // 次要文字
    public static final String ROLE_ACCENT = "accent";   // 强调（分区标题、活动区标题）
    public static final String ROLE_ERROR = "error";     // 错误
    public static final String ROLE_OK = "ok";           // 成功
    public static final String ROLE_WARN = "warn";       // 警告
    public static final String ROLE_CARD = "card";       // 卡片/面板底
    public static final String ROLE_FIELD = "field";     // 输入框/日志底
    public static final String ROLE_LINE = "line";       // 一条分隔线（用 View 当线）
    public static final String ROLE_PLAIN = "plain";     // 显式声明"就是正文色"
    public static final String ROLE_GHOST = "ghost";     // 次要按钮（主色浅底 + 主色字）
    public static final String ROLE_BUBBLE = "bubble";   // 用户消息气泡（主色浅底 + 主色字）
    public static final String ROLE_CHIP = "chip";       // 页头小按钮（比主按钮矮一档、更紧）
    public static final String ROLE_ITEM = "item";       // 列表里一行可选条目（浅框 + 波纹）
    public static final String ROLE_ROUND = "round";     // 圆形图标钮·次操作（浅底 + 主色图标）
    public static final String ROLE_ROUND_PRI = "roundPri"; // 圆形图标钮·主操作（主色底 + 白图标）
    public static final String ROLE_DANGER_BTN = "dangerBtn"; // 破坏性操作按钮（实色错误底 + 白字）

    // ---- 面板材料（底栏 / 输入区）----
    // 这三个角色是 2026-10-06 补的。它们原来**不在角色体系里**：页面拿 `tokens().card`
    // 现算一个裸 `GradientDrawable` 贴上去 —— 而 `walk()` 只重刷带角色标签的视图，
    // 于是换主题后「底栏」与「输入面板（对话框）」纹丝不动，停在旧色叠在新背景上。
    // 收进角色后，`refresh()` 每次都按新 tokens 重建，与其它材料走同一条路。
    /** 底栏药丸底 —— 卡片色 @0.80（参考的 NAV_PANEL_ALPHA）。 */
    public static final String ROLE_PANEL_NAV = "panelNav";
    /** 输入区面板底 —— 卡片色 @0.62，比底栏更透（参考的 InputPanel）。 */
    public static final String ROLE_PANEL_INPUT = "panelInput";
    /** 底栏的**选中格** —— 主色极浅底（叠在药丸上的一颗浅色药丸）。 */
    public static final String ROLE_PILL_SEL = "pillSel";
    /** 底栏药丸 / 选中格的不透明度与圆角（参考 0.80 / 28dp）。 */
    public static final double PANEL_ALPHA_NAV = 0.80;
    public static final int PANEL_RADIUS_NAV_DP = 28;
    /** 输入区面板的不透明度与圆角（参考 0.62 / 36dp）。 */
    public static final double PANEL_ALPHA_INPUT = 0.62;
    public static final int PANEL_RADIUS_INPUT_DP = 36;
    /** 选中格 = mix(card, pri, 0.14)。 */
    private static final double PILL_SEL_MIX = 0.14;

    /** 背景图存哪：App 私有目录，用户从相册选一张后拷进来（换一张覆盖它）。 */
    public static final String BG_DIR = "bg";
    public static final String BG_FILE = "background";

    private Theming() {
    }

    // ---------------------------------------------------------------- 配置读写

    /** 当前配置。读不到/坏掉一律回落默认（清蓝 + 蓝主色）。 */
    public static Theme.Config config(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return Theme.parse(sp.getString(KEY_CFG, null));
        } catch (Throwable t) {
            return Theme.Config.defaults();
        }
    }

    /** 存配置。存不下就本次会话内生效 —— 不该因为写偏好失败而崩界面。 */
    public static void save(Context c, Theme.Config cfg) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CFG, Theme.normalize(cfg).toJson()).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 当前生效的 token 表。 */
    public static Theme.Tokens tokens(Context c) {
        return Theme.tokens(config(c));
    }

    // ---------------------------------------------------------------- 应用

    public static void tag(View v, String role) {
        if (v != null) v.setTag(role);
    }

    /**
     * 应用整套外观：背景（渐变 / 图 / 遮罩）+ 整棵树的角色。
     *
     * 背景挂在 Activity 的 content 容器上，**不是**挂在传进来的 root 上 ——
     * root 通常是一个铺满全屏的 LinearLayout，一旦它自己有不透明背景，就会把
     * 底色/背景图整个盖住（表现成"换了主题只有文字变了，背景纹丝不动"）。
     * 从 root 往上找到 content，挂在那儿；root 保持透明，背景才透得上来。
     */
    public static void apply(Context ctx, View root) {
        Theme.Tokens t = tokens(ctx);
        mountBackdrop(ctx, root, t);
        walk(ctx, root, t);
        noteApplied(ctx, t);
    }

    /** 只应用角色（不碰背景）—— 动态重建内容的页面（如对话页）重建后调它。 */
    public static void applyTree(Context ctx, View root) {
        walk(ctx, root, tokens(ctx));
    }

    /**
     * 用**指定** tokens 渲染一棵子树 —— 外观页给每套风格各自预览要用它：
     * 那一屏里三张卡片得同时用三种风格渲染，才看得出区别（都按当前风格画就看不出来）。
     */
    public static void applyTree(Context ctx, View root, Theme.Tokens t) {
        walk(ctx, root, t);
    }

    /**
     * 每个 Activity **上一次整树贴色**用的是哪份配置。
     *
     * 为什么要记：`refresh()` 在每页 `onResume()` 里都会被调（从别的页面返回、切主题回来），
     * 而它会 `walk` **整棵树** —— 每个卡片/按钮都要重造 `StyleDecor` + `RippleDrawable` +
     * `ColorStateList`。对话页长起来是几千个视图，**每次返回都重走一遍 = 明显的一卡**（2026-10-04 性能专项）。
     * 记下"这份配置已经贴过了"，没换主题就只重挂背景（便宜）、不重走内容。
     */
    private static final java.util.WeakHashMap<Activity, String> APPLIED_CFG =
            new java.util.WeakHashMap<Activity, String>();

    /** 记下这次整树贴色用的是哪份配置（`apply` / `refresh` 走完都记一笔）。 */
    private static void noteApplied(Context ctx, Theme.Tokens t) {
        try {
            if (ctx instanceof Activity) {
                APPLIED_CFG.put((Activity) ctx, config(ctx).toJson());
            }
        } catch (Throwable ignored) {
            // 记不上顶多下次多走一遍整树，不该影响显示
        }
    }

    /**
     * 刷新整页外观 —— 从「外观」页返回时调用。
     *
     * 为什么需要：back stack 里的页面是**复用**回来的，不会重走 onCreate。
     * 不在这里重贴一遍，用户看到的就是"改了但没生效"，只能靠杀进程。
     *
     * ⚠️ 但**主题没变时不必重贴内容**（见 {@link #APPLIED_CFG}）—— 背景照旧重挂（很便宜），
     * 内容只在配置真的变了才整树重走。
     */
    public static void refresh(Activity a) {
        Theme.Config c = config(a);
        Theme.Tokens t = Theme.tokens(c);
        View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        content.setBackground(backdrop(a, t));          // 背景：每次都重挂（便宜，且要保证不残留旧图）
        applyStatusBar(a, t);                           // 状态栏图标：也每次跟主题走（C1 —— 见方法注释）
        String cfg = Theme.normalize(c).toJson();
        String last = APPLIED_CFG.get(a);
        if (cfg.equals(last)) return;                   // 主题没变 → 内容不必重走
        APPLIED_CFG.put(a, cfg);
        walk(a, content, t);
    }

    /**
     * 状态栏图标明暗跟着主题走：浅底 → 深图标（{@code LIGHT_STATUS_BAR}），深底 → 保持浅图标。
     *
     * ⚠ 为什么**必须**在 {@link #refresh(Activity)} 里也调（C1，2026-10-05 修）：
     *   这段设置以前只在 Activity 的 {@code onCreate} 里做一次。而 back stack 里的页面是**复用**回来的
     *   —— 在外观页把主题从浅切到深、再返回，页面不重走 onCreate，状态栏图标就停在旧主题的明暗上
     *   （深图标压在深背景上，读不出来）。被 {@code uiMode} 接管的启动页更明显：
     *   {@code onConfigurationChanged} 只调 refresh，连 onResume 都不走。
     *   refresh() 在每个页面 onResume / onConfigurationChanged 都会被调，挂在这里才覆盖全。
     */
    public static void applyStatusBar(Activity a, Theme.Tokens t) {
        try {
            android.view.Window w = a.getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            // 浅色主题 → 状态栏图标用深色；深色主题 → 保持浅色
            if (!Theme.isDark(t.bg)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable th) {
            // 拿不到也没关系：退回系统默认的状态栏，照样能用
        }
    }

    /**
     * 用一份**尚未落盘**的配置刷新界面 —— 卡片透明度滑块这类高频操作走它。
     *
     * 为什么需要：拖一次滑块会连发几十个事件，每个都写盘既费 IO、又会存下一串中间态。
     * 参照物的做法一样 —— 拖动时只改界面，松手（change）才落盘。
     */
    public static void preview(View root, Theme.Config cfg) {
        Context ctx = root.getContext();
        Theme.Tokens t = Theme.tokens(cfg);
        mountBackdrop(ctx, root, t);
        walk(ctx, root, t);
    }

    /** 把背景挂到 content 容器；找不到（脱离 Activity 的 View）就退回挂在 root 上。 */
    private static void mountBackdrop(Context ctx, View root, Theme.Tokens t) {
        View host = contentHost(root);
        if (host != null) host.setBackground(backdrop(ctx, t));
        else root.setBackground(backdrop(ctx, t));
    }

    /** 顺着 parent 往上找 Activity 的 content 容器（android.R.id.content）。 */
    private static View contentHost(View root) {
        View v = root;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v.getId() == android.R.id.content) return v;
        }
        return null;
    }

    private static void walk(Context ctx, View v, Theme.Tokens t) {
        String role = (v.getTag() instanceof String) ? (String) v.getTag() : null;
        boolean isButton = v instanceof Button;
        boolean isField = v instanceof EditText || ROLE_FIELD.equals(role);

        // ---- 背景：先于文字色，因为"字压在什么底上"决定它该往哪个方向推 ----
        if (v instanceof Icon) {
            // ⚠️ **字形不画角色底** —— `Icon` 只吃角色的**前程色**（见下面 `v instanceof Icon` 那段）。
            // 圆钮的底由外面那颗 `box` 负责（`Kit.roundButton` 的"两层"注释就写着这个分工）。
            //
            // 为什么必须显式挡：`cardAlpha < 1` 时主色是**半透明**的，图标自己再画一颗同色的圆，
            // 两层叠在圆心上会比外圈更实 —— 看上去就是"大圈套小圈"。
            // 复现配置：`accent=amber` + `cardAlpha=0.47`。
        } else if (ROLE_CARD.equals(role)) {
            // 卡片底交给 StyleDecor —— 它按风格画各自的"标志物"：现代化是干净的圆角面，
            // 像素风是双层硬边，古风是四角界格。GradientDrawable 只会画
            // 「一个圆角矩形 + 一层描边」，那正是"三套只是换了皮"的原因。
            Drawable card = new StyleDecor(t, ctx.getResources().getDisplayMetrics().density);
            // 卡片也可能是可点的（主题卡、会话卡）—— 给它波纹，点下去有回应才不像死图
            v.setBackground(ripple(card, RIPPLE_NEUTRAL));
            v.setElevation(t.shadow > 0 ? dp(ctx, t.shadow) : 0f);
        } else if (ROLE_BUBBLE.equals(role)) {
            // 用户消息气泡：主色浅底（字色在下面那段统一给成 priInk）
            GradientDrawable bub = new GradientDrawable();
            bub.setColor(Theme.toArgb(t.priSoft));
            bub.setCornerRadius(dp(ctx, t.bubbleRadius));   // 气泡圆角 = 参考的 BubbleCorner（18）
            v.setBackground(bub);
        } else if (ROLE_ITEM.equals(role)) {
            // 列表里一行可选条目：浅框 + 波纹（"选中"由调用方在文字上加 ✓，不改底色，
            // 免得整行变成一块高饱和色 —— 一行对一行地看才像列表）
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.fieldArgb());
            g.setCornerRadius(dp(ctx, Math.max(8, t.radiusXs)));
            g.setStroke(Math.max(1, dp(ctx, Math.max(1, t.hairWidth))), Theme.toArgb(t.line));
            v.setBackground(ripple(g, RIPPLE_NEUTRAL));
        } else if (ROLE_ROUND.equals(role) || ROLE_ROUND_PRI.equals(role)) {
            // 圆形图标钮：用 OVAL 画**真圆** —— 不依赖测量后的宽高。用 setCornerRadius(h/2)
            // 的写法在这里会踩坑：贴色时控件往往还没被测量（宽高为 0），圆形会退化成方角。
            // 主操作 = 主色底（配白图标），次操作 = 浅底（配主色图标）——靠**底色差**成形，不描边。
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ROLE_ROUND_PRI.equals(role)
                    ? followCardAlpha(Theme.toArgb(t.pri), t)   // 主操作钮也随卡片透明度透
                    : t.fieldArgb());                            // 次操作钮：fieldArgb 本就随 cardAlpha 走
            v.setBackground(ripple(g, RIPPLE_NEUTRAL));
        } else if (ROLE_PANEL_NAV.equals(role) || ROLE_PANEL_INPUT.equals(role)) {
            // 底栏药丸 / 输入区面板：**半透明卡片色**。两者材质同源、厚度不同 ——
            // 参考里输入区要透出滚动中的对话，底栏常驻在不变的背景上所以更实。
            boolean nav = ROLE_PANEL_NAV.equals(role);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Theme.withAlpha(Theme.toArgb(t.card), nav ? PANEL_ALPHA_NAV : PANEL_ALPHA_INPUT));
            g.setCornerRadius(dp(ctx, nav ? PANEL_RADIUS_NAV_DP : PANEL_RADIUS_INPUT_DP));
            v.setBackground(g);
        } else if (ROLE_PILL_SEL.equals(role)) {
            // 底栏的选中格：主色极浅底 —— 靠明度差表达"选中"，不描边
            GradientDrawable g = new GradientDrawable();
            g.setColor(Theme.toArgb(Theme.mix(t.card, t.pri, PILL_SEL_MIX)));
            g.setCornerRadius(dp(ctx, PANEL_RADIUS_NAV_DP));
            v.setBackground(g);
        } else if (isField) {
            // 输入框 / 日志底要比卡片更实一档：同样透明的话它们跟卡片一个色，
            // 就看不出"这里是个框"了（token 侧的 +0.30 就是这个意思）。
            //
            // 圆角走 **fieldRadius** 刻度（= 参考的 FieldCorner 12），不是 radiusXs ——
            // 后者是"卡片内小件"的通用档；参考里输入框是**独立的一档圆角**，
            // 混用会让输入框比同屏的其它小件更方。
            GradientDrawable g = new GradientDrawable();
            g.setColor(t.fieldArgb());
            g.setCornerRadius(dp(ctx, t.fieldRadius));
            g.setStroke(Math.max(1, dp(ctx, t.hairWidth)), Theme.toArgb(t.line));
            v.setBackground(g);
        } else if (ROLE_DANGER_BTN.equals(role)) {
            // 破坏性操作（删除这类）：**实色错误底 + 白字**。
            // 参考的原话是"删除键不该长得和确认键一样好看 —— 那本身就是个危险信号"。
            GradientDrawable g = new GradientDrawable();
            g.setColor(Theme.toArgb(t.danger));
            g.setCornerRadius(dp(ctx, t.buttonRadius));
            v.setBackground(ripple(g, RIPPLE_LIGHT));
        } else if (isButton) {
            boolean compact = ROLE_CHIP.equals(role);
            boolean ghost = ROLE_GHOST.equals(role) || compact;
            // 按钮底跟着卡片透明度一起透（用户 2026-10-04：「按钮也要随着卡片的透明度而变化」）——
            // 卡片一调透、按钮还板着一张实色脸会很突兀。cardAlpha=1（默认）时这条是恒等的，
            // 所以默认外观一个像素都没变。
            int btnFill = followCardAlpha(Theme.toArgb(ghost ? t.priSoft : t.pri), t);
            Drawable base;
            if (t.hairWidth > 0 || t.offsetDp > 0) {
                // 像素风 / 古风：**按钮也要吃这套边与装饰**。
                // 只给卡片上风格是不够的 —— 整页里按钮和底栏才是眼球落点，
                // 它们还是一块扁平蓝的话，换了风格看起来就跟没换一样。
                StyleDecor d = new StyleDecor(t, ctx.getResources().getDisplayMetrics().density);
                d.setFillOverride(btnFill);
                base = d;
            } else {
                // 现代化：主色渐变 + 大圆角（这套语言就是"柔和、没有边界"）
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{followCardAlpha(Theme.toArgb(t.pri), t),
                                  followCardAlpha(Theme.toArgb(t.pri2), t)});
                g.setCornerRadius(dp(ctx, t.buttonRadius));   // 按钮圆角 = 参考的 ButtonCorner（12）
                base = g;
            }
            // 系统 Button 自带一套最小宽度/大写/按下动画 —— 那正是"同一排按钮高矮不一"的
            // 来源。抹平它，整个 App 的按钮才有一致的个头。
            if (v instanceof Button) normalizeButton((Button) v, ctx, compact);
            // 换肤把系统按钮的按下反馈也换掉了，补一层波纹回来
            v.setBackground(ripple(base, ghost ? RIPPLE_NEUTRAL : RIPPLE_LIGHT));
            if (!ghost) v.setElevation(t.shadow > 0 ? dp(ctx, t.shadow) : 0f);
        } else if (ROLE_LINE.equals(role)) {
            v.setBackgroundColor(Theme.toArgb(t.line));
        }

        // ---- 自绘图标：**跟旁边的文字同一个色**（同一份角色解析） ----
        // 图标不在贴色体系里（walk 只认 TextView/Button），所以这条分支是它唯一的入口。
        // 千万别在调用处硬给颜色 —— 换了主题图标不跟着变，比没图标还难看。
        if (v instanceof Icon) {
            ((Icon) v).setColor(roleColor(role, t, isButton));
        }

        // ---- 文字色 ----
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(roleColor(role, t, isButton));
            if (ROLE_ACCENT.equals(role) && t.titleRule) {
                // 古风：标题前挂一条主色竖线（题签感）。用 compound drawable 而不是再加
                // 一个 View —— 后者会改布局层级，而换肤只该改属性（硬不变量 B）。
                GradientDrawable rule = new GradientDrawable();
                rule.setColor(Theme.toArgb(t.pri));
                rule.setSize(dp(ctx, 3), dp(ctx, 12));
                rule.setCornerRadius(dp(ctx, 2));
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(rule, null, null, null);
                tv.setCompoundDrawablePadding(dp(ctx, 6));
            } else {
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            }
            if (tv instanceof EditText) {
                ((EditText) tv).setHintTextColor(Theme.toArgb(t.placeholder));
            }
            applyFont(tv, t.font);
            // 字距：**中文唯一有效的"字体气质"维度** —— Typeface 的 serif/mono/sans
            // 在汉字上多半回退到同一套字库，调 family 用户看不出来，调字距才看得出来。
            tv.setLetterSpacing(t.letterSpacing);
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(ctx, g.getChildAt(i), t);
            }
        }
    }

    /**
     * 角色 → **前景色**。文字色与自绘图标色**共用这一份** —— 图标就该和它旁边的文字一个色。
     *
     * 抽出来是因为原来这段是一串 if-else 直接写在 `walk` 的文字分支里；
     * {@link Icon} 进来后需要同一份解析，复制一遍必然分叉。
     */
    private static int roleColor(String role, Theme.Tokens t, boolean isButton) {
        if (ROLE_MUTED.equals(role)) return Theme.toArgb(t.sub);
        if (ROLE_ACCENT.equals(role)) return Theme.toArgb(t.priInk);
        if (ROLE_ERROR.equals(role)) return Theme.toArgb(t.dangerInk);
        if (ROLE_OK.equals(role)) return Theme.toArgb(t.okInk);
        if (ROLE_WARN.equals(role)) return Theme.toArgb(t.warnInk);
        if (ROLE_GHOST.equals(role)) return Theme.toArgb(t.priInk);
        if (ROLE_CHIP.equals(role)) return Theme.toArgb(t.priInk);
        if (ROLE_BUBBLE.equals(role)) return Theme.toArgb(t.priInk);
        if (ROLE_ROUND.equals(role)) return Theme.toArgb(t.priInk);
        if (ROLE_ROUND_PRI.equals(role)) return Theme.toArgb(t.onPri);
        if (ROLE_DANGER_BTN.equals(role)) return Theme.toArgb(t.onDanger);
        if (isButton) return Theme.toArgb(t.onPri);
        return Theme.toArgb(t.fg);
    }

    /**
     * 字体族跟着**界面风格**走：现代化 = 无衬线，像素风 = 等宽，古风 = 衬线。
     *
     * 只换 family，**保留原有字重** —— 标题的粗体不能被换肤抹掉。
     */
    private static void applyFont(TextView t, String family) {
        Typeface old = t.getTypeface();
        int weight = old == null ? Typeface.NORMAL : old.getStyle();
        Typeface base;
        if ("mono".equals(family)) base = Typeface.MONOSPACE;
        else if ("serif".equals(family)) base = Typeface.SERIF;
        else base = Typeface.SANS_SERIF;
        t.setTypeface(Typeface.create(base, weight));
    }

    // ---------------------------------------------------------------- 背景（渐变 + 图 + 遮罩）

    private static String cachedImagePath;
    private static Bitmap cachedImage;

    /**
     * 根背景 = 「渐变垫底 → 背景图 → 半透明底色遮罩」三层，与参照物的 CSS 三层一致：
     *   ① 渐变垫底：图片没铺满/加载失败也不会露白块，同时纯色主题也有了层次；
     *   ② 图（没选图时这一层直接不加）；
     *   ③ 遮罩在最上：照片再花，字也读得清。
     *
     * LayerDrawable 的数组顺序就是绘制顺序（index 0 在最底），别写反。
     */
    private static Drawable backdrop(Context ctx, Theme.Tokens t) {
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Theme.toArgb(t.bg2), Theme.toArgb(t.bg), Theme.toArgb(t.bg2)});

        int full = ctx.getResources().getDisplayMetrics().heightPixels;
        List<Drawable> layers = new ArrayList<Drawable>();
        // 渐变垫底也**按固定全屏高**画：软键盘 resize 时容器变矮，若不固定高度，渐变会被压缩、
        // 色带位置随之变化 —— 用户把它读作"背景被抬起来/变了"（2026-10-06 报的正是这一幕；
        // 图片那层当时已有 CoverBackdrop 顶着，渐变这层漏了）。
        layers.add(new FixedHeight(grad, full));

        Bitmap bmp = loadBackground(ctx, t.image);
        if (bmp != null) {
            // 背景图**始终按全屏尺寸铺满**，不随容器高度变化（见 CoverBackdrop）。
            layers.add(new CoverBackdrop(bmp, full));
        }

        layers.add(new ColorDrawable(Theme.toArgb(t.scrim)));
        return new LayerDrawable(layers.toArray(new Drawable[0]));
    }

    /** 按屏宽采样解码 + 单张缓存：手机照片动辄 4000px 宽，整张读进来会直接 OOM。 */
    private static Bitmap loadBackground(Context ctx, String path) {
        if (path == null || path.isEmpty()) {
            cachedImagePath = null;
            cachedImage = null;
            return null;
        }
        if (path.equals(cachedImagePath) && cachedImage != null && !cachedImage.isRecycled()) {
            return cachedImage;
        }
        Bitmap bmp = decodeScaled(path, 1080);
        cachedImagePath = bmp == null ? null : path;
        cachedImage = bmp;
        return bmp;
    }

    private static Bitmap decodeScaled(String path, int maxWidth) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / sample > maxWidth) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable e) {
            return null;   // 图读不出来就当没设背景 —— 背景是装饰，不该把界面弄崩
        }
    }

    /**
     * 把一个 drawable **按固定全屏高**画（从容器顶开始），容器变矮时压缩/位移都不发生。
     *
     * 与 {@link CoverBackdrop} 同一份判据（{@link Backdrop#extentFor}），只是它包的是"别人的
     * drawable"（渐变），而 CoverBackdrop 自己画位图。两个都要有 —— 只固定图片那层时，
     * 用户看到的仍是"背景变了"（渐变被压）。
     */
    private static final class FixedHeight extends Drawable {
        private final Drawable inner;
        private final int fullHeight;

        FixedHeight(Drawable inner, int fullHeight) {
            this.inner = inner;
            this.fullHeight = fullHeight;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || inner == null) return;
            inner.setBounds(b.left, b.top, b.right, b.top + Backdrop.extentFor(fullHeight, b.height()));
            inner.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            if (inner != null) inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            if (inner != null) inner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return inner == null ? PixelFormat.TRANSLUCENT : inner.getOpacity();
        }
    }

    /**
     * 背景图那一层 —— **始终按"全屏尺寸"铺满**，不随容器高度变化。
     *
     * 为什么不用 BitmapDrawable：软键盘弹出时 content 会 resize（adjustResize），
     * 而 BitmapDrawable 的 FILL 会把图**纵向压扁**（用户：「背景图片不要向上挤压」）；
     * 改成 TOP|FILL_HORIZONTAL 又会按原图比例只铺到顶部、下方露底（用户：「背景怎么变这样了」）。
     * 两个都不对 —— 正解是"**始终按全屏尺寸铺满**"：键盘弹出时容器变矮，但图仍按全屏尺寸绘制，
     * 超出的部分被裁掉，所以既不压缩也不位移，而且**任何时候都铺满**。
     */
    private static final class CoverBackdrop extends Drawable {
        private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bmp;
        private final int fullHeight;

        CoverBackdrop(Bitmap bmp, int fullHeight) {
            this.bmp = bmp;
            this.fullHeight = fullHeight;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || bmp == null || bmp.isRecycled()) return;
            float h = Backdrop.extentFor(fullHeight, b.height());
            RectF dst = new RectF(b.left, b.top, b.right, b.top + h);
            canvas.drawBitmap(bmp, null, dst, PAINT);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 背景图落地路径（App 私有目录）。 */
    public static File backgroundFile(Context c) {
        File dir = new File(c.getFilesDir(), BG_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // 建不出来时退回 filesDir 根，路径依然是绝对的
            return new File(c.getFilesDir(), BG_FILE);
        }
        return new File(dir, BG_FILE);
    }

    /**
     * 把用户从相册选的图拷成背景图（换一张覆盖它）。
     *
     * @return 拷贝后的绝对路径；失败返回 null（调用方据此提示，不要静默）
     */
    /** 背景图字节上限：超过就拒。解码时本来就按屏宽采样，几十 MB 的原图没有意义。 */
    private static final long BG_MAX_BYTES = 32L * 1024 * 1024;

    public static String installBackground(Context c, Uri src) {
        if (src == null) return null;
        InputStream in = null;
        File dst = backgroundFile(c);
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        boolean moved = false;
        try {
            in = c.getContentResolver().openInputStream(src);
            if (in == null) return null;

            // ① 先写**临时文件** —— 原先一上来就 `new FileOutputStream(dst)` 把旧图截断，
            //    拷到一半失败旧图就永久没了（2026-10-04 深查发现），而且没有大小/类型校验。
            long total = 0;
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > BG_MAX_BYTES) {
                        throw new IOException("图片太大（上限 " + (BG_MAX_BYTES / 1024 / 1024) + "MB）");
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                closeQuietly(out);
            }
            if (total <= 0) throw new IOException("空文件");

            // ② 类型校验：能当位图解出来才收（用户可能选到一个 .txt 或坏文件）
            if (!decodesAsBitmap(tmp.getAbsolutePath())) throw new IOException("不是能识别的图片");

            // ③ 原子替换：**成功了才**覆盖旧图
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();                     // 少数文件系统上 rename 覆盖会失败
                if (!tmp.renameTo(dst)) throw new IOException("替换背景图失败");
            }
            moved = true;

            cachedImagePath = null;      // 换了图，缓存作废
            cachedImage = null;
            return dst.getAbsolutePath();
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(in);
            // 失败时清掉临时文件，**旧图保持原样**（这是这次改动的全部意义）
            if (!moved && tmp.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 能不能当成位图解出来 —— 只看头部尺寸，不整张解码（省内存）。 */
    private static boolean decodesAsBitmap(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            return o.outWidth > 0 && o.outHeight > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删掉背景图（"不用图片"）。 */
    public static void clearBackground(Context c) {
        try {
            File f = backgroundFile(c);
            if (f.isFile() && !f.delete()) {
                // 删不掉也不该崩；下次选图会覆盖它
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        cachedImagePath = null;
        cachedImage = null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 兼容旧调用点（老的 Theming.color）。 */
    public static int color(String hex) {
        return Theme.toArgb(hex);
    }

    /** "rgba(...)" / "#rrggbb" → ARGB int，界面侧统一在这里转。 */
    public static int argb(String v) {
        return Theme.toArgb(v);
    }

    public static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 让一块**按钮底**跟着卡片透明度走（用户 2026-10-04：「按钮也要随着卡片的透明度而变化」）。
     * cardAlpha=1（默认）时原样返回 —— 也就是默认外观一个像素都不变。
     */
    private static int followCardAlpha(int argb, Theme.Tokens t) {
        return t.cardAlpha < 1 ? Theme.withAlpha(argb, t.cardAlpha) : argb;
    }

    // ---------------------------------------------------------------- 触感（波纹 / 按钮统一）

    /** 压在彩色底上的波纹（浅色）。 */
    private static final int RIPPLE_LIGHT = 0x40FFFFFF;
    /** 压在浅底 / 卡片上的波纹（淡黑）。 */
    private static final int RIPPLE_NEUTRAL = 0x1A000000;

    /** 给一个底加一层波纹 —— 换肤把系统按钮的按下反馈换掉了，这里补回来。 */
    private static Drawable ripple(Drawable content, int color) {
        try {
            return new RippleDrawable(ColorStateList.valueOf(color), content, null);
        } catch (Throwable t) {
            return content;   // 万一这版机器上 RippleDrawable 用不了，也不能连底都没了
        }
    }

    /**
     * 抹平系统 Button 自带的"个性"：最小宽度 88dp、大写、按下缩放动画、多余内边距。
     * 不抹的话，一排按钮会因为文字长短而高矮/宽窄不一 —— 这正是"粗糙"的一大来源。
     *
     * @param compact 页头那种小按钮（{@link #ROLE_CHIP}）：矮一档、内边距更紧，
     *                否则页头会被两个 44dp 高的全尺寸按钮撑得很笨
     */
    private static void normalizeButton(Button b, Context ctx, boolean compact) {
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (compact) {
            b.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
            b.setMinimumHeight(dp(ctx, 36));
        } else {
            b.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
            b.setMinimumHeight(dp(ctx, 44));
        }
        try {
            b.setStateListAnimator(null);
        } catch (Throwable ignored) {
            // 忽略：没有那点按下动画，也好过崩
        }
    }
}
