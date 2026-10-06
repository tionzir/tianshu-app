package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 页面组件层 —— 把「页头 / 卡片标题 / 空态 / 统计卡」收成一处。
 *
 * 为什么要有它（用户反馈：「所有界面有些重复的地方，很突兀、不合理」）：
 * 七个页面各自手搓标题与卡片头，字号、间距、字重三处都不一致 —— 改一次观感要翻七个文件，
 * 而且必然继续分叉（`ChatActivity.java:214` 与 `SettingsActivity.java:92` 就是两套写法）。
 *
 * 手法取自用户指定的参照物「售后助手」（`/storage/emulated/0/oa-hot/www/style.css`）：
 *   - 卡片标题 = **图标徽章 + 文字**（那边是 `style.css:79-84` 的 `h2 .ico`：26×26、主色浅底、圆角 9px）；
 *   - 页头 = 徽章 + 主标题 + 次要副标题（那边的 `.brand`，`style.css:88-96`）；
 *   - 页面焦点 = 一张统计卡，用一个大号数字把"这一页最重要的是什么"顶出来（那边的 `.overview`）。
 *
 * 图标一律用**本项目已核验字形覆盖**的符号（真机 334 个字体里
 * U+23F5 没有字形，会渲染成豆腐块）—— 所以这里既不用 SVG、也不引图片资源。
 */
public final class AppKit {

    private AppKit() {
    }

    /**
     * 页头：左侧图标徽章 + 主标题 + 副标题，右侧可挂一个动作控件。
     *
     * @param glyph    图标（可空 —— 空则不出徽章）
     * @param subtitle 副标题（可空 —— 空则不占那一行）
     * @param action   右侧动作（可空）
     */
    public static View pageHeader(Activity a, int iconKind, String title, String subtitle, View action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (iconKind != 0) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = Theming.dp(a, Ui.S3);
            row.addView(badge(a, iconKind, 44), blp);
        }

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(Ui.DISPLAY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextSize(Ui.CAPTION);
            s.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
            Theming.tag(s, Theming.ROLE_MUTED);
            texts.addView(s);
        }

        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) row.addView(action);
        return row;
    }

    /** 页头（无图标版）。 */
    public static View pageHeader(Activity a, String title, String subtitle, View action) {
        return pageHeader(a, 0, title, subtitle, action);
    }

    /**
     * 页头标题 —— **纯标题，不带徽章**。
     *
     * 2026-10-04：四个页面的页头原本是「主色徽章 + 大标题 + 右侧文字按钮」，而对话页改成了
     * `[圆钮] 标题 … [圆钮]`。两种语言并存会显得"只改了一半"，所以统一成后者：
     * **标题 + 圆钮动作**，徽章让位给真正能做事的按钮（它原本也不承载任何信息）。
     *
     * ⚠️ 想给某个页面单独加识别符号时，别退回徽章 —— 用左侧的圆钮（那里能点）。
     */
    public static View pageTitle(Activity a, String title) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(Ui.DISPLAY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        // 固定行高 + 垂直居中：页头行高原本由**最高的兄弟**决定（有圆钮→123px、只有文字钮→110px、
        // 没动作→90px），标题随之居中，于是同一「页标题」角色跨页上下跳 16px（见审计 §3.2）。
        // 把标题自己钉到 TOUCH_MIN 高之后，三种页头的标题顶边一律对齐。
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setMinHeight(Theming.dp(a, Ui.TOUCH_MIN));
        return t;
    }

    /**
     * 图标徽章：主色浅底 + 主色字 + 圆角（用既有的 `ROLE_GHOST` 角色 —— 换肤自动跟）。
     *
     * 尺寸走 `minWidth/minHeight` 而不是 `LayoutParams`：调用方 `addView` 时会带自己的
     * LayoutParams，那样会把这里的尺寸覆盖掉（踩过一次）。
     */
    /**
     * 图标徽章：主色浅底 + 主色图标（`ROLE_GHOST` 角色，换肤自动跟）。
     *
     * 第三轮改：原来是"拿一个符号当字打印"，现在是自绘 {@link Icon} ——
     * 符号的字形覆盖全靠设备字体，几何图形不会变成豆腐块。
     *
     * 尺寸走 `minWidth/minHeight` 而不是 `LayoutParams`：调用方 `addView` 时会带自己的
     * LayoutParams，那样会把这里的尺寸覆盖掉（踩过一次）。
     */
    public static View badge(Activity a, int iconKind, int sizeDp) {
        FrameLayout box = new FrameLayout(a);
        int s = Theming.dp(a, sizeDp);
        box.setMinimumWidth(s);
        box.setMinimumHeight(s);
        Theming.tag(box, Theming.ROLE_GHOST);          // 浅底

        Icon ic = new Icon(a, iconKind).size(Math.round(sizeDp * 0.55f));
        Theming.tag(ic, Theming.ROLE_GHOST);           // 主色图标
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.CENTER;
        box.addView(ic, ilp);
        return box;
    }

    /** 卡片标题：徽章 + 文字（对齐参照物的 `h2`，比卡片正文高一档、用次要色）。 */
    public static View cardTitle(Activity a, int iconKind, String text) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (iconKind != 0) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = Theming.dp(a, Ui.S2);
            row.addView(badge(a, iconKind, 26), blp);
        }
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(Ui.TITLE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(t, Theming.ROLE_ACCENT);
        row.addView(t);
        return row;
    }

    /** 分组小标题（高级配置按用途分组时用）：小字 + 强调色，起分隔作用。 */
    public static View groupTitle(Activity a, String text) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Theming.dp(a, Ui.S4), 0, Theming.dp(a, Ui.S1));

        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(Ui.CAPTION);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(t, Theming.ROLE_ACCENT);
        box.addView(t);
        return box;
    }

    /** 空态：一句话 + 可选"下一步做什么"。 */
    public static View emptyState(Activity a, String line, String hint) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(a, Ui.S4);
        box.setPadding(p, Theming.dp(a, Ui.S5), p, Theming.dp(a, Ui.S5));

        TextView t = new TextView(a);
        t.setText(line);
        t.setTextSize(Ui.BODY);
        Theming.tag(t, Theming.ROLE_MUTED);
        box.addView(t);

        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(a);
            h.setText(hint);
            h.setTextSize(Ui.CAPTION);
            h.setPadding(0, Theming.dp(a, Ui.S2), 0, 0);
            h.setLineSpacing(0f, 1.3f);
            Theming.tag(h, Theming.ROLE_MUTED);
            box.addView(h);
        }
        return box;
    }

    /**
     * 统计卡：一个大号数字 + 一行说明 —— 给页面一个**视觉焦点**
     * （参照物里 `.overview` 就是干这个的：金额 32px、tabular 数字）。
     */
    public static View statCard(Activity a, String value, String label, String hint) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(a, Ui.S4);
        box.setPadding(p, p, p, p);
        Theming.tag(box, Theming.ROLE_CARD);

        if (label != null && !label.isEmpty()) {
            TextView l = new TextView(a);
            l.setText(label);
            l.setTextSize(Ui.CAPTION);
            Theming.tag(l, Theming.ROLE_MUTED);
            box.addView(l);
        }

        TextView v = new TextView(a);
        v.setText(value);
        v.setTextSize(28f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(v, Theming.ROLE_ACCENT);
        v.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
        box.addView(v);

        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(a);
            h.setText(hint);
            h.setTextSize(Ui.CAPTION);
            Theming.tag(h, Theming.ROLE_MUTED);
            h.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
            box.addView(h);
        }
        return box;
    }

    /**
     * 加载态：星轨转圈 + 一行说明。
     *
     * 2026-10-04 补「四态」时新加的 —— 在此之前，异步读数据的页面（模型页、侧栏、会话页）
     * 在等待期间**什么都不显示**：空白的页面看起来和"坏了"没有区别。
     * 转圈走 {@link OrbitSpinner}（自己画的，不依赖 ROM 上的 ProgressBar 样式），
     * 颜色跟当前主色走。
     *
     * @param text 可空
     */
    public static View loading(Activity a, String text) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Theming.dp(a, Ui.S6), 0, Theming.dp(a, Ui.S6));

        OrbitSpinner sp = new OrbitSpinner(a);
        sp.setColor(Theme.toArgb(Theming.tokens(a).pri));
        box.addView(sp, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (text != null && !text.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(text);
            t.setTextSize(Ui.CAPTION);
            t.setPadding(0, Theming.dp(a, Ui.S2), 0, 0);
            Theming.tag(t, Theming.ROLE_MUTED);
            box.addView(t);
        }
        return box;
    }

    /**
     * 错误态：一句"哪里错了" + 可选的下一步说明 + **重试按钮**。
     *
     * 与 {@link #emptyState} 的分界：空态是"正常地没有东西"（还没会话），错误态是"该有的没拿到"。
     * 后者必须给出口 —— 只写一句"读不到"，用户除了退出去再进来没有别的办法，
     * 而多数失败（serve 还没就绪、网络抖一下）重试一次就好了。
     *
     * @param detail 可空；回答"那我现在能做什么"
     * @param retry  可空（非空才出「重试」钮）
     */
    public static View error(Activity a, String title, String detail, View.OnClickListener retry) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(a, Ui.S4);
        box.setPadding(p, Theming.dp(a, Ui.S5), p, Theming.dp(a, Ui.S5));

        TextView t = new TextView(a);
        t.setText(title == null || title.isEmpty() ? "出错了" : title);
        t.setTextSize(Ui.BODY);
        Theming.tag(t, Theming.ROLE_WARN);
        box.addView(t);

        if (detail != null && !detail.isEmpty()) {
            TextView h = new TextView(a);
            h.setText(detail);
            h.setTextSize(Ui.CAPTION);
            h.setPadding(0, Theming.dp(a, Ui.S2), 0, 0);
            h.setLineSpacing(0f, 1.3f);
            Theming.tag(h, Theming.ROLE_MUTED);
            box.addView(h);
        }

        if (retry != null) {
            Button b = new Button(a);
            b.setText("重试");
            Theming.tag(b, Theming.ROLE_GHOST);
            b.setOnClickListener(retry);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Theming.dp(a, Ui.S3);
            box.addView(b, blp);
        }
        return box;
    }
}
