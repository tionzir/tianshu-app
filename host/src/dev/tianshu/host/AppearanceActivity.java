package dev.tianshu.host;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 外观页 —— 6 套背景主题 × 7 款主色 × 自定义底色/渐变 × 背景图 × 卡片透明度。
 *
 * 结构照参照物（售后助手的 appearance.html）：预览在最上（改一下就能看到，不用来回翻页
 * 比对），然后是背景主题、自定义背景、背景图片、卡片透明度、主色，底部一个「恢复默认」。
 *
 * ## 它在 App 里的位置（2026-10-06 变了）
 * 原先是**底栏第 4 格**。用户 2026-10-06：「把外观页面放到设置页面里做成一个卡片，点进卡片才是
 * 外观页面」—— 于是底栏那格删了（见 {@link Tabs}），入口变成设置页里的一张卡
 *（{@code SettingsActivity.appearanceCard()}），本页降成**二级页**：
 * 不画底栏、页头带返回圆钮、返回键回设置页（{@code BaseActivity.onBackPressed} 按"在不在
 * Tabs 里"分流）。**别把底栏再加回来** —— 同一页留两个门，用户会以为走错了地方。
 *
 * 一个刻意的取舍：**点了就生效、也立刻落盘** —— 没有「保存」按钮。界面上立刻变了就是
 * 反馈，比弹一句「已保存」直接。两个例外：
 *   - 色号输入框要按「用这个背景」才提交（边打字边存会存下一串中间态）；
 *   - 透明度滑块拖动时只改界面，松手才落盘。
 *
 * 颜色怎么算、token 怎么贴都在 {@link Theme} / {@link Theming} 里，这一页不重复实现。
 */
public class AppearanceActivity extends BaseActivity {

    private static final int REQ_PICK_IMAGE = 0x5702;

    /**
     * 自定义底色的色板。挑的都是**能用**的底色：要么够亮、要么够深 ——
     * 中间的灰不在配色板里（中灰底上任何字色都到不了 4.5:1；真有人要填，
     * 输入框也认，界面上会如实提示）。
     */
    private static final String[] SWATCHES = {
            "#f4f6fa", "#faf5ed", "#eef4ef", "#f5f4fb", "#ffffff", "#eef3fb",
            "#f7f1e8", "#eaeff2", "#dfe9f5", "#12151b", "#0d1a22", "#1b1f2a",
    };

    private Theme.Config cfg;
    private View rootView;
    private EditText hexBg;
    private EditText hexBg2;
    private TextView warn;
    private TextView alphaNote;
    private TextView alphaVal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Theming.config(this);
        render();
    }

    // 这里原先挂着一个 SwipeNav（左右滑动在底栏四格间切页）。
    // 2026-10-06 本页降成二级页后删掉：**二级页不参与切页** —— 滑走的语义是"换一个平级页面"，
    // 而二级页的"离开"是返回上一层（`SwipeNav.targetIndex` 对 current=-1 本来就返回 -1，
    // 留着就成了一段恒不命中的接线路，那正是本项目删过好几次的东西）。

    // ---------------------------------------------------------------- 画

    private void render() {
        Theme.Tokens t = Theming.tokens(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        rootView = root;

        // 内容列：页面 padding 落在这里、**不落在 root** ——
        // root 永不带 padding（全 App 的约定：底栏加在 root 上时，root 一带 padding 底栏就被缩窄）。
        // 本页现在没有底栏了，但约定照旧守着 —— 少一条特例，就少一处"以后加底栏时忘改"。
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        content.setPadding(pad, pad, pad, pad);   // 底：原先由「给悬浮底栏让位」补，现在自己给

        // 页头：标题 + 右侧**返回圆钮**（二级页给一个明确的返回口，别让人只能靠系统返回键）——
        // 与「模型」「面板」两页同一排面。少了它，这一页一进来就是"预览"卡片，看不出自己在哪儿、
        // 也不知道怎么退回去。
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "外观"), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Kit.roundButton(this, Icon.BACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        content.addView(header);

        TextView sub = new TextView(this);
        sub.setText("背景与配色，改完立刻生效（各页通用）");
        sub.setTextSize(Ui.CAPTION);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        Theming.tag(sub, Theming.ROLE_MUTED);
        content.addView(sub);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        // 原先这里给**悬浮底栏**留 92dp（NavBar.SPACE_FOR_CONTENT_DP）。本页没有底栏了 ——
        // 留着就是页面底部一条空带（真机上看得出来）。底部留白改由 content 的内边距给。
        sv.setClipToPadding(false);
        sv.addView(body);

        // 四段（原来 7 段）——「配色」把背景主题与主色合到一起（都是"选颜色"）；
        // 「背景与卡片」把自定义底色 / 背景图 / 卡片透明度合到一起（都是"底与透"）。
        // 段数减半 = 一屏看到更多，不用翻半天才找到想改的那一项。
        // 预览段**不套卡片底** —— 里面摆的就是卡与气泡本身，再套一层就成了"卡中卡"，
        // 层次反而看不出来（用户前两轮反馈的"没有直观感觉"正是这种"看不出差别"）。
        body.addView(AppKit.groupTitle(this, "预览"));
        body.addView(previewBlock());
        body.addView(section("配色", colorBlock()));
        body.addView(section("界面风格", stylesBlock()));
        body.addView(section("背景与卡片", backgroundBlock(t)));

        content.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button reset = new Button(this);
        reset.setText("恢复默认");
        Theming.tag(reset, Theming.ROLE_GHOST);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetToDefault();
            }
        });
        content.addView(reset);

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // 原先这一行是 `root.addView(NavBar.create(this, NavBar.currentFor(this)), NavBar.params(this))`
        // —— 2026-10-06 本页降成二级页后删掉（二级页不画底栏；`NavBar.currentFor` 对不在 Tabs 里的
        // Activity 返回 -1，会出现一条"谁都不高亮"的底栏，点了还会跳到别的平级页去）。

        setContentView(root);
        Theming.apply(this, root);
    }

    /** 一张卡片：标题（主色）+ 内容，底色是 token 里的卡片色（所以它自己会跟着主题变）。 */
    private View section(String title, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(this, Ui.S4);                  // 卡片内边距 16
        card.setPadding(p, p, p, p);
        Theming.tag(card, Theming.ROLE_CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theming.dp(this, Ui.S3);        // 卡片之间 12（原来随手写的 10）
        card.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(Ui.TITLE);
        Theming.tag(h, Theming.ROLE_ACCENT);
        card.addView(h);

        content.setPadding(0, Theming.dp(this, Ui.S3), 0, 0);
        card.addView(content);
        return card;
    }

    /**
     * 预览：一段**模拟对话片段** —— 状态药丸 + 用户气泡 + 助手卡片 + 输入行。
     *
     * 2026-10-04 改：原先只是"一张小卡 + 一个按钮"，看不出真实界面长什么样 ——
     * 用户在两轮真机反馈里反复说"没有很具体直观的感觉"，而参考 App 的预览是**摆真东西**。
     * 现在按对话页的真实布局把最典型的三件（气泡 / 助手卡 / 输入行）摆出来，
     * 换主题、换主色、换风格时一眼就能比对差别在哪。
     *
     * 这里所有控件都**不可交互**（圆钮传 null、输入框用 TextView 冒充 ROLE_FIELD）——
     * 它是镜子，不是控件。
     */
    private View previewBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // ① 状态药丸（对话页头下方那一排）
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("模型"), chipLp());
        chips.addView(chip("上下文 12%"), chipLp());
        chips.addView(chip("星域 天枢"), chipLp());
        box.addView(chips, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ② 用户气泡（右对齐）
        TextView user = new TextView(this);
        user.setText("帮我把这周的行程理一下");
        user.setTextSize(Ui.BODY);
        int bp = Theming.dp(this, Ui.S3);
        user.setPadding(bp, Theming.dp(this, Ui.S2), bp, Theming.dp(this, Ui.S2));
        Theming.tag(user, Theming.ROLE_BUBBLE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.gravity = Gravity.END;
        ulp.topMargin = Theming.dp(this, Ui.CARD_GAP);
        box.addView(user, ulp);

        // ③ 助手卡片（与对话页助手发言同一形态）
        LinearLayout card = Kit.card(this);
        TextView name = new TextView(this);
        name.setText("天枢");
        name.setTextSize(Ui.TITLE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(name, Theming.ROLE_ACCENT);
        card.addView(name);

        TextView msg = new TextView(this);
        msg.setText("好，我看看这周的记录。");
        msg.setTextSize(Ui.BODY);
        msg.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        card.addView(msg);
        box.addView(card);

        // ④ 输入行：圆形命令钮 + 输入框 + 主色发送钮
        LinearLayout input = new LinearLayout(this);
        input.setOrientation(LinearLayout.HORIZONTAL);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.addView(Kit.roundButton(this, Icon.COMMAND, null));

        TextView field = new TextView(this);
        field.setText("给天枢发消息…");
        field.setTextSize(Ui.BODY);
        int fp = Theming.dp(this, Ui.S3);
        field.setPadding(fp, Theming.dp(this, Ui.S2), fp, Theming.dp(this, Ui.S2));
        Theming.tag(field, Theming.ROLE_FIELD);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = Theming.dp(this, Ui.INLINE);
        flp.rightMargin = Theming.dp(this, Ui.INLINE);
        input.addView(field, flp);

        input.addView(Kit.roundButton(this, Icon.SEND, true, null));
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.LABEL);
        int p = Theming.dp(this, Ui.S2);
        t.setPadding(p, Theming.dp(this, Ui.S1), p, Theming.dp(this, Ui.S1));
        Theming.tag(t, Theming.ROLE_CHIP);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Theming.dp(this, Ui.S2);
        return lp;
    }

    /** 「配色」段 = 背景主题 + 主色（都是"选颜色"）。 */
    private View colorBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(presetsBlock());
        addWithTop(box, accentsBlock(), Ui.S5);
        return box;
    }

    /** 「背景与卡片」段 = 自定义底色 + 背景图 + 卡片透明度（都是"底与透"）。 */
    private View backgroundBlock(Theme.Tokens t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(customBlock(t));
        addWithTop(box, imageBlock(), Ui.S5);
        addWithTop(box, alphaBlock(), Ui.S5);
        return box;
    }

    /** 把 v 加进 box，并带一个"与上一块之间"的顶间距（用于把两个子块并进同一段）。 */
    private void addWithTop(LinearLayout box, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theming.dp(this, topDp);
        box.addView(v, lp);
    }

    /** 6 套背景主题，两列一格，一格一张卡。 */
    private View presetsBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Theme.PRESETS.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int end = Math.min(i + 2, Theme.PRESETS.size());
            for (int j = i; j < end; j++) {
                // 两列之间留一道缝 —— 原先 weight1() 不带 margin，两张卡是贴着的
                row.addView(presetCard(Theme.PRESETS.get(j)), gridCell(j == end - 1));
            }
            if (Theme.PRESETS.size() - i == 1) row.addView(new View(this), weight1());
            box.addView(row, gridRow());
        }
        return box;
    }

    private View presetCard(final Theme.Preset p) {
        final boolean on = p.id.equals(cfg.preset);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pd = Theming.dp(this, 10);
        card.setPadding(pd, pd, pd, pd);
        Theming.tag(card, Theming.ROLE_CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Theming.dp(this, 8);
        lp.bottomMargin = Theming.dp(this, 8);
        card.setLayoutParams(lp);

        // 三个小圆点就是这套配色的缩略：底色 / 卡片 / 文字。
        // 比只写名字强 —— 名字看不出深浅，颜色看得出来。
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.addView(dot(p.bg, 15));
        dots.addView(dot(p.card, 15));
        dots.addView(dot(p.fg, 15));
        card.addView(dots);

        TextView name = new TextView(this);
        name.setText(on ? p.name + "  （当前）" : p.name);
        name.setTextSize(13f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        if (on) Theming.tag(name, Theming.ROLE_ACCENT);
        card.addView(name);

        TextView hint = new TextView(this);
        hint.setText(p.hint);
        hint.setTextSize(Ui.CAPTION);
        Theming.tag(hint, Theming.ROLE_MUTED);
        card.addView(hint);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 换主题：底色/文字/边框/主色派生全都跟着重算（token 层的事），
                // 这里只负责把选择记下来
                commit(cfg.with(p.id, null));
                render();
            }
        });
        return card;
    }

    /**
     * 界面风格 —— **现在只剩「初雪」一套**（用户 2026-10-04：其他几套他都不满意，删掉）。
     *
     * 列表里只剩一条，所以这一段只会画出一张「初雪（当前）」的卡 —— 保留它是为了
     * 明确告诉用户"风格固定成这样"，而不是把这一项整个藏起来。
     */
    private View stylesBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(hint("当前固定为「初雪」一套 —— 扁平、16 圆角、极细边、轻投影。其它风格已按你的要求移除。"));

        Theme.Tokens t = Theming.tokens(this);
        for (final Theme.Style st : Theme.STYLES) {
            final boolean on = st.id.equals(cfg.style);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            int p = Theming.dp(this, 12);
            card.setPadding(p, p, p, p);
            card.setGravity(Gravity.CENTER_VERTICAL);
            Theming.tag(card, Theming.ROLE_CARD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Theming.dp(this, Ui.S2);      // 与上一张卡（或说明文字）之间留缝
            lp.bottomMargin = Theming.dp(this, Ui.S2);
            card.setLayoutParams(lp);

            View swatch = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    Theming.dp(this, 40), Theming.dp(this, 40));
            slp.rightMargin = Theming.dp(this, 12);
            swatch.setLayoutParams(slp);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Theme.toArgb(t.cardSolid));
            g.setCornerRadius(Theming.dp(this, st.radiusDp));
            if (st.borderWidthDp > 0) {
                g.setStroke(Math.max(1, Theming.dp(this, st.borderWidthDp)), Theme.toArgb(t.pri));
            }
            swatch.setBackground(g);
            card.addView(swatch);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(this);
            name.setText(on ? st.name + "  （当前）" : st.name);
            name.setTextSize(14f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            if (on) Theming.tag(name, Theming.ROLE_ACCENT);
            textCol.addView(name);

            TextView desc = new TextView(this);
            desc.setText(st.hint + " · 字体 " + st.font);
            desc.setTextSize(Ui.CAPTION);
            Theming.tag(desc, Theming.ROLE_MUTED);
            textCol.addView(desc);

            card.addView(textCol);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    commit(cfg.withStyle(st.id));
                    render();
                }
            });

            // ★ 这张卡用它**自己的**风格渲染 —— 三张并排时圆角、描边、字体、装饰
            //   各不相同，一眼看得出"像素风和古风到底差在哪"。
            //   若都按当前风格画，用户看到的还是三张一模一样的卡 —— 那正是
            //   之前"没有很具体直观的感觉"的根源。
            Theming.applyTree(this, card, Theme.tokens(cfg.withStyle(st.id)));

            box.addView(card);
        }
        return box;
    }

    /** 自定义底色：色板 + 色号输入（含渐变）。 */
    private View customBlock(Theme.Tokens t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(hint("选一个底色，或直接填色号（#204060、#204）。"
                + "卡片、文字、边框的颜色会按对比度算出来 —— 不用自己搭色，也不会出现看不清的字。"));

        for (int i = 0; i < SWATCHES.length; i += 6) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int end = Math.min(i + 6, SWATCHES.length);
            for (int j = i; j < end; j++) {
                // 色块之间留缝：贴在一起就看不出是"六个可选色"
                row.addView(swatch(SWATCHES[j]), gridCell(j == end - 1));
            }
            box.addView(row, gridRow());
        }

        // 两个色号输入框走控件层：与对话页输入框、会话页搜索框是**同一份外观**
        //（圆角 fieldRadius、实底、描边、内边距），不再各写一遍 setSingleLine / tag / 字号。
        hexBg = Kit.singleLineField(this, "#f4f6fa");
        hexBg.setText(cfg.customBg);

        hexBg2 = Kit.singleLineField(this, "渐变（可空）");
        hexBg2.setText(cfg.customBg2);

        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.addView(hexBg, weight1());
        hexRow.addView(hexBg2, weight1Gap());   // 两个输入框之间留缝
        addStacked(box, hexRow);

        warn = new TextView(this);
        warn.setTextSize(Ui.CAPTION);
        warn.setVisibility(View.GONE);
        Theming.tag(warn, Theming.ROLE_WARN);
        addStacked(box, warn, Ui.S1);

        Button apply = new Button(this);
        apply.setText("用这个背景");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyHex();
            }
        });

        Button clearGrad = new Button(this);
        clearGrad.setText("不用渐变");
        Theming.tag(clearGrad, Theming.ROLE_GHOST);
        clearGrad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hexBg2.setText("");
                applyHex();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(apply, weight1());
        row.addView(clearGrad, weight1Gap());   // 两个按钮之间留缝
        addStacked(box, row);

        showWarnIfNeeded();
        return box;
    }

    /** 背景图片：从相册选一张，拷贝成 App 私有目录里的 background（换一张覆盖它）。 */
    private View imageBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(hint("用自己的照片/图当背景。图片上会压一层底色遮罩，所以字照样看得清。只留最近一张。"));

        TextView state = new TextView(this);
        state.setTextSize(12f);
        Theming.tag(state, Theming.ROLE_MUTED);
        state.setText(cfg.image.isEmpty()
                ? "还没选图片 —— 现在用的是上面的背景色"
                : "已设背景图：" + new File(cfg.image).getName() + "（换一张会覆盖它）");
        addStacked(box, state, Ui.S2);

        Button pick = new Button(this);
        pick.setText("选一张图片");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });

        Button none = new Button(this);
        none.setText("不用图片");
        Theming.tag(none, Theming.ROLE_GHOST);
        none.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Theming.clearBackground(AppearanceActivity.this);
                commit(cfg.withImage(""));
                render();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(pick, weight1());
        row.addView(none, weight1Gap());   // 两个按钮之间留缝
        addStacked(box, row);
        return box;
    }

    /** 卡片透明度：滑块 10%–100%，拖动实时预览，松手落盘。 */
    private View alphaBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(hint("把卡片调透一点，背景（尤其是照片）就能透上来。"
                + "卡片里的字会跟着自动压深/提亮 —— 透光之后也保证看得清，不用自己权衡。"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(90);                                   // 10..100 → 0..90
        bar.setProgress((int) Math.round(cfg.cardAlpha * 100) - 10);
        // 真机反馈 #14：默认 SeekBar 的绿轨道 + 大滑块与这套换肤体系完全不搭，看着很突兀。
        // 用当前主题的主色给轨道/滑块上色，并关掉 splitTrack（默认会把已选段画出割裂感）。
        Theme.Tokens tk = Theme.tokens(cfg);
        android.content.res.ColorStateList csPri =
                android.content.res.ColorStateList.valueOf(Theme.toArgb(tk.pri));
        android.content.res.ColorStateList csLine =
                android.content.res.ColorStateList.valueOf(Theme.toArgb(tk.line));
        bar.setProgressTintList(csPri);
        bar.setThumbTintList(csPri);
        bar.setProgressBackgroundTintList(csLine);
        bar.setSplitTrack(false);
        row.addView(bar, weight1());

        alphaVal = new TextView(this);
        alphaVal.setTextSize(15f);
        alphaVal.setTypeface(Typeface.DEFAULT_BOLD);
        alphaVal.setText(Math.round(cfg.cardAlpha * 100) + "%");
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avLp.leftMargin = Theming.dp(this, Ui.INLINE);   // 数值与滑块之间留缝
        row.addView(alphaVal, avLp);
        addStacked(box, row);

        alphaNote = new TextView(this);
        alphaNote.setTextSize(Ui.CAPTION);
        Theming.tag(alphaNote, Theming.ROLE_MUTED);
        addStacked(box, alphaNote, Ui.S1);
        updateAlphaNote();

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                double a = (progress + 10) / 100.0;
                alphaVal.setText(Math.round(a * 100) + "%");
                // 拖动时只改界面 —— 拖一次会连发几十个事件，每个都写盘既费 IO
                // 又会存下一串中间态（松手才落盘）
                if (rootView != null) Theming.preview(rootView, cfg.withAlpha(a));
                updateAlphaNoteFor(a);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                commit(cfg.withAlpha((sb.getProgress() + 10) / 100.0));
            }
        });
        return box;
    }

    /** 主色：一排圆点（按钮、标签、选中态都跟着它走）。 */
    private View accentsBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final Theme.Accent a : Theme.ACCENTS) {
            boolean on = a.id.equals(cfg.accent);
            View v = new View(this);
            int s = Theming.dp(this, 36);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = Theming.dp(this, 8);
            lp.bottomMargin = Theming.dp(this, 4);
            v.setLayoutParams(lp);

            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Theme.toArgb(a.pri));
            // 选中的圈一圈正文色（跟 token 走，深色主题下也看得见）
            if (on) g.setStroke(Theming.dp(this, 3), Theme.toArgb(Theming.tokens(this).fg));
            else g.setStroke(Math.max(1, Theming.dp(this, 1)), 0x33FFFFFF);
            v.setBackground(g);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    commit(cfg.with(null, a.id));
                    render();
                }
            });
            row.addView(v);
        }
        box.addView(row);
        addStacked(box, hint("按钮、标签、选中态的配色都跟着它走。"), Ui.S2);
        return box;
    }

    // ---------------------------------------------------------------- 小部件

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.CAPTION);
        t.setLineSpacing(0f, 1.35f);        // 说明文字给点行距，密排的灰字最显廉价
        Theming.tag(t, Theming.ROLE_MUTED);
        return t;
    }

    private View dot(String hex, int sizeDp) {
        View v = new View(this);
        int s = Theming.dp(this, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = Theming.dp(this, 5);
        v.setLayoutParams(lp);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Theme.toArgb(hex));
        g.setStroke(Math.max(1, Theming.dp(this, 1)), 0x22000000);
        v.setBackground(g);
        return v;
    }

    private View swatch(final String hex) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Theming.dp(this, 30));
        lp.rightMargin = Theming.dp(this, 6);
        lp.bottomMargin = Theming.dp(this, 6);
        v.setLayoutParams(lp);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Theming.dp(this, 8));
        g.setColor(Theme.toArgb(hex));
        g.setStroke(Math.max(1, Theming.dp(this, 1)), 0x33000000);
        v.setBackground(g);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 只换底色，渐变留给用户自己决定（下面有「不用渐变」单独清）
                hexBg.setText(hex);
                commit(cfg.withCustom(hex, cfg.customBg2));
                render();
            }
        });
        return v;
    }

    private static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /**
     * 网格里的一格：等分宽度，且 **与右邻留一道缝**（最后一列不留，免得贴到页边）。
     *
     * ⚠ `addView(child, lp)` 会用传进来的 lp **顶掉** child 自己设过的 margin。
     * 原先栅格都写 `addView(card, weight1())`，于是卡片自带的 8dp 边距全被吃掉，
     * 两列**贴在一起** —— 这是"控件挤"的一大来源。
     */
    private LinearLayout.LayoutParams gridCell(boolean last) {
        LinearLayout.LayoutParams lp = weight1();
        if (!last) lp.rightMargin = Theming.dp(this, Ui.S2);
        return lp;
    }

    /** 网格的一行：与上一行之间留一道缝。 */
    private LinearLayout.LayoutParams gridRow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theming.dp(this, Ui.STACK);
        return lp;
    }

    /** 竖直排布：加一个控件并带上"与上一个控件之间"的间距（addView 默认 0 间距，就是"挤"）。 */
    private void addStacked(LinearLayout box, View v) {
        addStacked(box, v, Ui.STACK);
    }

    private void addStacked(LinearLayout box, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theming.dp(this, topDp);
        box.addView(v, lp);
    }

    /** 横排里"第二个及以后"的控件：带一个左间距，避免与左邻贴在一起。 */
    private LinearLayout.LayoutParams weight1Gap() {
        LinearLayout.LayoutParams lp = weight1();
        lp.leftMargin = Theming.dp(this, Ui.INLINE);
        return lp;
    }

    // ---------------------------------------------------------------- 动作

    private void commit(Theme.Config next) {
        cfg = Theme.normalize(next);
        Theming.save(this, cfg);
    }

    private void applyHex() {
        String bg = normHexInput(hexBg.getText().toString());
        String bg2 = normHexInput(hexBg2.getText().toString());

        if (!Theme.isHex(bg)) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("底色要写成 #f4f6fa 这样（3 位也行，如 #204）。");
            return;
        }
        if (!bg2.isEmpty() && !Theme.isHex(bg2)) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("渐变色同一种写法，或者留空。");
            return;
        }
        warn.setVisibility(View.GONE);
        commit(cfg.withCustom(bg, bg2));
        render();
    }

    /** 输入框里宽容一点：#123 / 123 都当色号收下，其余原样还回去（由 isHex 判）。 */
    private static String normHexInput(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return "";
        return v.charAt(0) == '#' ? v : "#" + v;
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (Throwable e) {
            Toast.makeText(this, "打不开图片选择器：" + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            // 取消不是错误，但也不该憋着 —— 什么都不说会让人以为按钮是坏的
            Toast.makeText(this, "没有选图片", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = data.getData();
        String path = Theming.installBackground(this, uri);
        if (path == null) {
            Toast.makeText(this, "图片没能拷进来（可能不是图片，或没有读权限）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        commit(cfg.withImage(path));
        render();
        Toast.makeText(this, "背景图已更新", Toast.LENGTH_SHORT).show();
    }

    private void resetToDefault() {
        Theming.clearBackground(this);
        commit(Theme.Config.defaults());
        render();
        Toast.makeText(this, "已恢复默认外观", Toast.LENGTH_SHORT).show();
    }

    /** 中灰底那条限制是真的（任何字色都到不了 4.5:1），与其闷着不如说清楚。 */
    private void showWarnIfNeeded() {
        if (warn == null) return;
        Theme.Preset p = Theme.palette(cfg);
        if ("custom".equals(cfg.preset) && !p.ok) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("这个底色偏中间灰：任何字色在它上面都到不了 4.5:1（实测 "
                    + p.baseContrast + ":1）。卡片里的字仍然清楚，但页面上的小字会略吃力 —— "
                    + "换成更亮或更深的底色就没事。");
        } else {
            warn.setVisibility(View.GONE);
        }
    }

    private void updateAlphaNote() {
        updateAlphaNoteFor(cfg.cardAlpha);
    }

    private void updateAlphaNoteFor(double alpha) {
        if (alphaNote == null) return;
        if (alpha >= 1) {
            alphaNote.setText("卡片不透明（默认）。设了背景图之后，往左拖就能让图透上来。");
        } else if (!cfg.image.isEmpty()) {
            alphaNote.setText("卡片正在透出背景图。透到字开始吃力就往右回一点 —— "
                    + "卡片里的字会跟着加深，但照片太花时仍会互相打架。");
        } else {
            alphaNote.setText("卡片正在透出页面底色。没有背景图时这层底色和卡片本来就很接近，"
                    + "所以变化看着不明显。");
        }
    }
}
