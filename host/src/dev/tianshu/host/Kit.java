package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 控件层 —— 面向用户的东西从这里出来。
 *
 * ## 为什么要在 {@link AppKit} 之外再开一层
 * `AppKit` 管的是**页面级零件**（页头、统计卡、空态）；这里管的是**控件级零件**
 * （卡片、输入框、圆形图标钮、菜单行、分隔线、分组）。两者的分界是"它是这一页的一部分，
 * 还是所有页都会用的一个控件"。
 *
 * 参考 App 的 `ui/components/` 有 16 个组件、2174 行 —— 天枢原先只有 AppKit 的 4 个 helper，
 * 于是每个页面都在自己造控件：同一件事各写一遍，改一次要翻 8 个文件，而**必然分叉**
 * （`ChatActivity` 与 `SettingsActivity` 的标题就是两套写法，见 AppKit 的类注释）。
 *
 * ## 一条硬边界：**本类只做结构，贴色仍归 {@link Theming}**
 * 这不只是分工问题。`Theming.walk` 顺着 View 树按**角色**贴色的机制，是项目"换肤只改外观、
 * 不改结构"（硬不变量 B）的实现。控件如果自己 `setBackground`，走一次 `Theming.refresh()`
 * 就被覆盖掉 —— 表现成"换个主题，这个控件没跟着变"。
 *
 * 所以这里的规矩是：
 *   ① 控件只声明**角色**（`Theming.tag`），不自己设颜色、圆角、描边；
 *   ② 需要新形态时，**在 {@link Theming} 里加角色分支**（例如圆形底走 `ROLE_ROUND`），
 *      而不是在控件里硬画；
 *   ③ 唯一在这里做的是**结构**（怎么排、多高、多宽）与**按压缩放**。
 *
 * ## 按压反馈：为什么用缩放而不是涟漪
 * 参考的结论（`PressEffect.kt`）：浅色卡片上扩散的半透明涟漪会糊出一个方块，按压感交给缩放。
 * 天枢的 `NavBar.java:148-166` 已经这么做过（scale 0.96）——这里把它推行到全部控件。
 *
 * ⚠️ 三条实现纪律（对抗复核指出的坑，改动时别丢）：
 *   ① 触摸监听**必须 `return false`** —— 返回 true 会吞掉 `OnClickListener` 与 `OnLongClickListener`
 *      （会话卡的长按管理面板、命令行的长按复制都会失灵）；
 *   ② `setOnTouchListener` 与 `setTag` 一样是**单槽位**，所以**只在 Kit 建的控件上挂**，
 *      绝不放进 `Theming.walk`（那里每次 refresh/preview 都会跑一遍，会覆盖先设的监听）；
 *   ③ 只有**真正可点**的控件才挂缩放。给一个非可点的容器挂上，会变成"点面板空白处整块缩动"。
 */
public final class Kit {

    private Kit() {
    }

    /** 按压时缩到多少。参考 0.965；0.94 在宽卡片上会缩出明显的边缘抖动。 */
    public static final float PRESS_SCALE = 0.965f;
    private static final long PRESS_DOWN_MS = 90;
    private static final long PRESS_UP_MS = 140;

    /** 圆形图标钮的直径（dp）—— 参考的 `RoundIconButton` 就是 40。 */
    public static final int ROUND_DP = 40;

    // ══════════════════════════════ 卡片 ══════════════════════════════

    /** 一张卡片（不可点）：`ROLE_CARD` 底 + 16dp 内边距 + 下方 `CARD_GAP` 间距。 */
    public static LinearLayout card(Activity a) {
        return card(a, null);
    }

    /**
     * 一张可点的卡片。
     *
     * @param onClick 非空时顺带挂**按压缩放**（`onClick` 为空则不挂 —— 见类注释纪律 ③）
     */
    public static LinearLayout card(Activity a, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(a, Ui.S4);
        box.setPadding(p, p, p, p);
        Theming.tag(box, Theming.ROLE_CARD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theming.dp(a, Ui.CARD_GAP);   // 卡片间距收在这里，各页不必自己写
        box.setLayoutParams(lp);

        if (onClick != null) {
            box.setOnClickListener(onClick);
            pressable(box);
        }
        return box;
    }

    /** 一张**无内边距**的卡片底（给分组用：行自带内边距，卡片不能再加一层）。 */
    public static LinearLayout bareCard(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        Theming.tag(box, Theming.ROLE_CARD);
        return box;
    }

    // ══════════════════════════════ 输入框 ══════════════════════════════

    /**
     * 输入框。
     *
     * 底/圆角/描边**由 Theming 按 `ROLE_FIELD` 统一给**（圆角走新的 `fieldRadius` 刻度 = 参考的
     * `FieldCorner 12`）；这里只管结构与内边距。
     *
     * ⚠️ 曾经它坐在**半透明面板**里、自己也带半透明底，两层叠起来输入区几乎看不出来 ——
     * 所以别在这里再 `setBackground`，也别把面板的不透明度调得和输入框一样。
     */
    public static EditText field(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint == null ? "" : hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(1);
        e.setMaxLines(4);
        Theming.tag(e, Theming.ROLE_FIELD);
        int hp = Theming.dp(a, Ui.S3);
        int vp = Theming.dp(a, Ui.S2);
        e.setPadding(hp, vp, hp, vp);
        return e;
    }

    /** 单行输入框（改名 / 搜索这类）。 */
    public static EditText singleLineField(Activity a, String hint) {
        EditText e = field(a, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setSingleLine(true);
        e.setMaxLines(1);
        return e;
    }

    /**
     * 带标签的输入框：**标签在框的上方**，不在框里悬浮。
     *
     * 参考 `YukiTextField` 的原话：Material 那套"悬浮标签"是"标准 Android 应用"的脸，
     * 而天枢的字段常常要显示当前值（配置行），悬浮标签会和值打架 —— 放上面就没事。
     *
     * @return 一个竖排容器（标签 + 输入框）；输入框本身用 {@link Field#input} 取
     */
    public static final class Field {
        public final LinearLayout root;
        public final EditText input;
        /** 标签文字（可空 —— 没有标签时不占那一行）。 */
        public final TextView label;

        Field(LinearLayout root, EditText input, TextView label) {
            this.root = root;
            this.input = input;
            this.label = label;
        }
    }

    public static Field labeledField(Activity a, String label, String hint) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView l = null;
        if (label != null && !label.isEmpty()) {
            l = new TextView(a);
            l.setText(label);
            l.setTextSize(Ui.CAPTION);
            Theming.tag(l, Theming.ROLE_MUTED);
            l.setPadding(0, 0, 0, Theming.dp(a, 6));   // 参考：标签与框之间 6dp
            root.addView(l);
        }
        EditText e = field(a, hint);
        root.addView(e);
        return new Field(root, e, l);
    }

    // ══════════════════════════════ 圆形图标钮 ══════════════════════════════

    /**
     * 次操作圆钮（浅底 + 主色图标）—— 命令开关这类。
     *
     * 底走 `ROLE_ROUND`：Theming 里用 `GradientDrawable(OVAL)` 画**真圆**（不依赖尺寸），
     * 所以这里给 40dp 只是让它看起来是那个大小，形状不会因为测量顺序变方。
     */
    public static View roundButton(Activity a, int iconKind, View.OnClickListener l) {
        return roundButton(a, iconKind, false, l);
    }

    /**
     * @param primary true = 主操作（**主色底 + 白图标**，发送键那种），false = 次操作（浅底 + 主色图标）
     */
    public static View roundButton(Activity a, int iconKind, boolean primary,
                                   View.OnClickListener l) {
        // 圆底与图标是**两层**：底走 ROLE_ROUND(_PRI) 角色背景、图标走同一角色的前程色。
        // 用 FrameLayout 而不是让 Icon 自己画底 —— 图标只该管那几笔几何，底色归 Theming。
        FrameLayout box = new FrameLayout(a);
        int s = Theming.dp(a, ROUND_DP);
        box.setMinimumWidth(s);
        box.setMinimumHeight(s);
        Theming.tag(box, primary ? Theming.ROLE_ROUND_PRI : Theming.ROLE_ROUND);

        Icon ic = new Icon(a, iconKind).size(22);
        Theming.tag(ic, primary ? Theming.ROLE_ROUND_PRI : Theming.ROLE_ROUND);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.CENTER;
        box.addView(ic, ilp);

        // 可点区域补到 Ui.TOUCH_MIN(48dp)：视觉圆仍是 ROUND_DP(40)（与参考 App 一致），
        // 但项目自己在 Ui 里定了「可点元素最小高度 48dp（Material 的下限）」——
        // 实测 40dp 圆钮只有 40.2×40.2dp 的可点面积。
        // 这里两者都满足：外面套一层透明命中区，圆本身既不缩小也不放大。
        FrameLayout hit = new FrameLayout(a);
        int min = Theming.dp(a, Ui.TOUCH_MIN);
        hit.setMinimumWidth(min);
        hit.setMinimumHeight(min);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(s, s);
        hlp.gravity = Gravity.CENTER;
        hit.addView(box, hlp);
        if (l != null) {
            hit.setOnClickListener(l);
            // 按下缩放仍作用在**看得见的圆**上
            pressable(box);
        }
        return hit;
    }

    /**
     * 页头「刷新」动作 —— 统一成**圆钮**。
     *
     * 为什么要有这个入口：{@link AppKit#pageTitle} 的注释白纸黑字写着本工程的页头约定是
     * 「**标题 + 圆钮动作**」（徽章已让位给能点的按钮）。但此前 8 个页面各自
     * `new Button(this); setText("刷新")`，于是同一个「刷新」在别页是文字胶囊、在对话页是圆钮，
     * 尺寸也分裂成 48.3×35.9dp 与 40.2×40.2dp 两种（见审计 §3.1）。
     * 收在这里之后，再要改页头动作只需动一处。
     */
    public static View headerRefresh(Activity a, View.OnClickListener l) {
        return roundButton(a, Icon.REFRESH, l);
    }

    // ══════════════════════════════ 菜单行 ══════════════════════════════

    /**
     * 菜单行：图标徽章 + 标题（+ 可选副标题）+ `›`。
     *
     * 结构照参考 `SettingsScreen` 的 `MenuRow`。**不给行画底** —— 参考的原话是
     * "组内不画行背景，只用分隔线断行；层次来自「卡片浮在纯色背景上」"。
     * 可点性由 `›` 箭头与按压缩放表达。
     *
     * @param desc    副标题（可空）。参考的纪律：它只回答"**点进去能做什么**"，不复述标题
     * @param onClick 可空（不可点时不挂缩放、不显示 `›`）
     */
    public static View menuRow(Activity a, int iconKind, String title, String desc,
                               View.OnClickListener onClick) {
        return menuRow(a, iconKind, title, desc, null, onClick);
    }

    /**
     * 菜单行的扩展版：允许在说明下面再挂一行 {@code extra}（命令面板的"落点状态"就挂在这）。
     *
     * 为什么用组合而不是把状态并进 {@code desc}：那一行是**带颜色**的（可用 / 不存在 / 一般），
     * 而 desc 统一是次要色 —— 揉成一段纯文本就把这个区分丢了。extra 由调用方自己建、自己 tag。
     *
     * @param iconKind {@link Icon} 的种类；**0 = 不要图标**（命令行、日志行这类没有合适图标的行）
     */
    public static View menuRow(Activity a, int iconKind, String title, String desc,
                               View extra, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Theming.dp(a, Ui.S4);
        int vp = Theming.dp(a, 14);            // 参考的行内边距就是 14
        row.setPadding(hp, vp, hp, vp);
        row.setMinimumHeight(Theming.dp(a, Ui.TOUCH_MIN));

        if (iconKind != 0) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = Theming.dp(a, 14);
            row.addView(AppKit.badge(a, iconKind, 30), blp);
        }

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(Ui.BODY);
        texts.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(a);
            d.setText(desc);
            d.setTextSize(Ui.CAPTION);
            d.setPadding(0, Theming.dp(a, 2), 0, 0);
            Theming.tag(d, Theming.ROLE_MUTED);
            texts.addView(d);
        }
        if (extra != null) texts.addView(extra);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (onClick != null) {
            Icon chev = new Icon(a, Icon.CHEVRON).size(18);
            Theming.tag(chev, Theming.ROLE_MUTED);
            row.addView(chev);

            row.setOnClickListener(onClick);
            pressable(row);
        }
        return row;
    }

    /**
     * 可选项行（"选哪个模型当默认"这类）：标题（+ 副标题）+ 选中时的右侧 `✓`。
     *
     * 与 {@link #menuRow} 的分界：菜单行的 `›` 表示"点进去还有一页"，而这里点一下是**就地选中**，
     * 所以右侧改用 `✓`、标题走强调色。✓（U+2713）实测 16 个字体覆盖。
     */
    public static View choiceRow(Activity a, String title, String desc, boolean selected,
                                 View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Theming.dp(a, Ui.S4);
        int vp = Theming.dp(a, 14);
        row.setPadding(hp, vp, hp, vp);
        row.setMinimumHeight(Theming.dp(a, Ui.TOUCH_MIN));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText(title == null ? "" : title);
        t.setTextSize(Ui.BODY);
        if (selected) Theming.tag(t, Theming.ROLE_ACCENT);
        texts.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(a);
            d.setText(desc);
            d.setTextSize(Ui.CAPTION);
            d.setPadding(0, Theming.dp(a, 2), 0, 0);
            Theming.tag(d, Theming.ROLE_MUTED);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            Icon ck = new Icon(a, Icon.CHECK).size(16);
            Theming.tag(ck, Theming.ROLE_ACCENT);
            row.addView(ck);
        }
        if (onClick != null) {
            row.setOnClickListener(onClick);
            pressable(row);
        }
        return row;
    }

    /** 不可点的信息行（只有标题 + 值，右对齐）—— 设置页里"只读配置项"用。 */
    public static View infoRow(Activity a, String title, String value) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Theming.dp(a, Ui.S4);
        int vp = Theming.dp(a, 12);
        row.setPadding(hp, vp, hp, vp);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(Ui.BODY);
        row.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(a);
        v.setText(value == null ? "" : value);
        v.setTextSize(Ui.CAPTION);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);   // 溢出兜底（超长值不撑破行）
        Theming.tag(v, Theming.ROLE_MUTED);
        row.addView(v);
        return row;
    }

    /**
     * 卡片式的一行（会话列表那种）：**一张卡装一行**，内容是「标题 + 副标题 + `›`」。
     *
     * 为什么不是"一张大卡装多行 + 分隔线"（设置页那种）：会话列表里**每条的下一个动作都不同**
     * （点开的是这一条），独立成卡在视觉上更准确；而设置页里那些行同属一个分组，才该装在一起。
     */
    public static LinearLayout cardRow(Activity a, String title, String desc,
                                       View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Theming.dp(a, Ui.S4);
        int vp = Theming.dp(a, 14);
        card.setPadding(hp, vp, hp, vp);
        Theming.tag(card, Theming.ROLE_CARD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theming.dp(a, Ui.CARD_GAP);      // 卡片间距收在这里
        card.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText(title == null ? "" : title);
        t.setTextSize(Ui.TITLE);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);   // 溢出兜底：超长标题不撑破卡片
        texts.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(a);
            d.setText(desc);
            d.setTextSize(Ui.CAPTION);
            d.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
            Theming.tag(d, Theming.ROLE_MUTED);
            texts.addView(d);
        }
        card.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (onClick != null) {
            Icon chev = new Icon(a, Icon.CHEVRON).size(18);
            Theming.tag(chev, Theming.ROLE_MUTED);
            card.addView(chev);

            card.setOnClickListener(onClick);
            pressable(card);
        }
        return card;
    }

    // ══════════════════════════════ 分隔线与分组 ══════════════════════════════

    /**
     * 分隔线：**左缩进对齐行内文字**，不切到卡片边缘。
     *
     * 缩进是刻意的（参考 `LineDivider` 的原话）：一条从卡片最左画到最右的线会把卡片切成两半，
     * 缩进后它才读作"同一个容器里的两行"。
     *
     * @param indentDp 缩进；菜单行用 16（与行内文字左对齐），带图标的行用 56（对齐到文字那一列）
     */
    public static View divider(Activity a, int indentDp) {
        View v = new View(a);
        Theming.tag(v, Theming.ROLE_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Theming.dp(a, 1)));
        lp.leftMargin = Theming.dp(a, indentDp);
        v.setLayoutParams(lp);
        return v;
    }

    /** 一个分组：小标题 + 一张**无内边距**的卡片。 */
    public static final class Group {
        /** 加进页面的那一整块（标题 + 卡片）。 */
        public final View root;
        /** 卡片本身 —— 菜单行 / 分隔线往这里加。 */
        public final LinearLayout body;
        /** 建分隔线要用（存着比重算 parent 可靠）。 */
        private final Activity a;

        Group(Activity a, View root, LinearLayout body) {
            this.a = a;
            this.root = root;
            this.body = body;
        }

        /** 加一行，并自动在**行之间**插分隔线（第一行之前不插）。 */
        public Group row(View v) {
            return row(v, 16);
        }

        public Group row(View v, int dividerIndentDp) {
            if (body.getChildCount() > 0) {
                body.addView(divider(a, dividerIndentDp));
            }
            body.addView(v);
            return this;
        }
    }

    public static Group group(Activity a, String title) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(Ui.CAPTION);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            Theming.tag(t, Theming.ROLE_ACCENT);
            // 缩进 4dp：让标题的左边缘略靠左于卡片里的文字，读起来是"这一组的标题"
            t.setPadding(Theming.dp(a, Ui.S1), 0, 0, Theming.dp(a, Ui.S2));
            root.addView(t);
        }

        LinearLayout body = bareCard(a);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = Theming.dp(a, Ui.CARD_GAP);    // 组与组之间
        root.addView(body, blp);

        return new Group(a, root, body);
    }

    // ══════════════════════════════ 按压反馈 ══════════════════════════════

    /**
     * 给一个**可点**的控件挂按压缩放。
     *
     * ⚠️ `return false` 是硬要求 —— 返回 true 会吞掉点击与长按（见类注释纪律 ①）。
     * 这个范式来自 `NavBar.java:148-166`，那里已经在真机上跑过。
     */
    public static void pressable(final View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                                .setDuration(PRESS_DOWN_MS).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(PRESS_UP_MS).start();
                        break;
                    default:
                        break;
                }
                return false;              // 不消费：点击 / 长按仍交给各自的 listener
            }
        });
    }
}
