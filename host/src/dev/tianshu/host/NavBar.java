package dev.tianshu.host;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 底部页面栏 —— 对话 · 会话 · 设置。
 *
 * 三个页签的定义在 {@link Tabs}（纯逻辑，HostTest 断言它）；这一层只负责画，
 * 并按 Intent 切页。**格数由 {@code Tabs.ALL} 决定**：2026-10-06 外观页收进设置页后少一格，
 * 本类一行没改（格子按 weight 平分，三格自动铺满 —— 这也是当初不让页数散落在绘制代码里的原因）。
 *
 * 为什么不用 Fragment / ViewPager 做"真 tab"：本项目只依赖 android.jar（没有
 * support/androidx 依赖仓库），而且几个页面各自是独立 Activity —— 对话页还要长期
 * 持有 SSE 连接，把它塞进一个不该管网络状态的容器里只会更难查。
 * Activity 互跳 + {@code FLAG_ACTIVITY_REORDER_TO_FRONT} 最省事，栈也不会堆成山。
 */
public final class NavBar {

    /** 栏高（dp）。Material 的触控下限是 48，这里给 56 容纳图标 + 文字两行。 */
    public static final int HEIGHT_DP = 56;

    /** 悬浮药丸的左右留边（参考 NavHorizontalMargin = 16）—— 贴边就不叫悬浮了。 */
    public static final int NAV_MARGIN_DP = 16;
    /** 悬浮药丸距屏幕底部的留边（参考 NavBottomMargin = 12）。 */
    public static final int NAV_BOTTOM_DP = 12;
    // 药丸的圆角(28)与不透明度(0.80)已收进 Theming —— 它们是"主题材料"的一部分，
    // 必须跟角色一起在 refresh() 里重刷（Theming.PANEL_RADIUS_NAV_DP / PANEL_ALPHA_NAV）。
    // 值别再在这里写第二份：写两份 = 迟早只有一份会改。

    /**
     * 内容区为这条**悬浮**底栏让出的空间（dp）。
     *
     * 底栏浮在内容之上，滚动区末尾必须留出这一块，否则最后一条消息 / 最后一张卡片会被
     * 药丸盖住（用户报过"底栏悬浮后内容有没有被盖住"）。参考的 `NavSpaceForContent = 92dp`。
     *
     * ⚠️ 原先这个 92 在**四个文件**里各写了一遍 —— 收在这里，改一处就够。
     */
    public static final int SPACE_FOR_CONTENT_DP = 92;

    private NavBar() {
    }

    /**
     * 造一条底部栏。
     *
     * @param current 当前页签下标（见 {@link Tabs}）；-1 = 二级页面，谁都不高亮
     */
    public static View create(final Activity activity, int current) {
        // **悬浮药丸底栏**（照参考 App 的 FlatBottomBar）：外壳圆角 28、左右留边 16、距底 12、
        // 高 56；底是**半透明**的当前主题卡片色（参考用 0.80 的不透明白 —— 这里取主题卡片色，
        // 深浅主题都不会瞎）。它不再贴底，这正是"一眼看出换了套 UI"的地方。
        Theme.Tokens t = Theming.tokens(activity);
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);

        // 药丸底走**角色**，不在这里现算 —— 换主题时 Theming.refresh() 会按新 tokens 重建它。
        // （原先这里是 `new GradientDrawable()` + `tokens().card` 烘焙：换主题后底栏停在旧色，
        //   叠到新背景上变成一块脏灰。用户 2026-10-06 报的「底部颜色没改」就是这条。）
        Theming.tag(pill, Theming.ROLE_PANEL_NAV);
        pill.setElevation(Theming.dp(activity, 6));
        pill.setClipToPadding(false);

        for (Tabs.Tab tab : Tabs.ALL) {
            pill.addView(cell(activity, tab, tab.id == current, t));
        }
        return pill;
    }

    /** 当前所在页（按 Activity 类名对号）。二级页面返回 -1。 */
    public static int currentFor(Activity a) {
        return Tabs.indexOfActivity(a.getClass().getName());
    }

    /**
     * 底栏在页面根布局里的摆放参数 —— **四个页面共用同一份**。
     *
     * 为什么必须集中：底栏是加在页面 root 上的，root 一旦带 padding，底栏就被一起缩窄。
     * 原先「会话」「外观」两页把 padding 落在 root、「对话」「设置」两页落在内层，
     * 于是切换页面时底栏会**一大一小**地跳（真机踩过）。约定：
     *   **root 永不带 padding；padding 一律给内层的 content。** 底栏因此四页同宽同距。
     */
    public static LinearLayout.LayoutParams params(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // 悬浮：左右各留 NAV_MARGIN_DP、距底 NAV_BOTTOM_DP（参考 App 的 16 / 12）
        lp.setMargins(Theming.dp(a, NAV_MARGIN_DP), Theming.dp(a, Ui.S2),
                Theming.dp(a, NAV_MARGIN_DP), Theming.dp(a, NAV_BOTTOM_DP));
        return lp;
    }

    /** 透明的按下波纹（静态外观为零）—— RippleDrawable 用不了时退回 null（没反馈也照常能用）。 */
    private static Drawable touchRipple() {
        try {
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x1A000000), null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static View cell(final Activity activity, final Tabs.Tab tab, boolean on, Theme.Tokens t) {
        // 选中态是一颗**浅色药丸**（主色极浅底 + 主色图标与文字），未选中**只显示图标** ——
        // 参考里就是这么做的：全带文字会挤，也不像药丸。
        // （旧的"选中条 marker"整套删掉了：参考里没有那条，选中靠药丸表达。）
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                Theming.dp(activity, HEIGHT_DP), 1f));

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = Theming.dp(activity, on ? Ui.S3 : Ui.S2);
        int vPad = Theming.dp(activity, Ui.S1);
        pill.setPadding(hPad, vPad, hPad, vPad);
        if (on) {
            // 选中格同样走角色（同上）—— 它就是换主题时"看着没变"的那第二块
            Theming.tag(pill, Theming.ROLE_PILL_SEL);
        }

        Icon glyph = new Icon(activity, tab.icon).size(22);
        Theming.tag(glyph, on ? Theming.ROLE_ACCENT : Theming.ROLE_MUTED);
        pill.addView(glyph);

        if (on) {
            TextView label = new TextView(activity);
            label.setText(tab.label);
            label.setTextSize(12f);
            label.setPadding(Theming.dp(activity, Ui.S1), 0, 0, 0);
            Theming.tag(label, Theming.ROLE_ACCENT);
            pill.addView(label);
        }
        cell.addView(pill);

        // 按下反馈：整颗缩放（参考的 PRESS_SCALE = 0.96）。参考里**关掉了涟漪** ——
        // 浅色药丸上扩散的半透明涟漪会糊出一个方块，缩放更干净。
        cell.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        break;
                    default:
                        break;
                }
                return false;               // 不消费：点击仍交给 onClickListener
            }
        });

        if (!on) {
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    jump(activity, tab);
                }
            });
        }
        return cell;
    }

    /** 切页：栈里已有就提到最前，没有就新建。 */
    static void jump(Activity activity, Tabs.Tab tab) {        try {
            Intent i = new Intent();
            i.setClassName(activity, tab.activity);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(i);
            // 去掉系统默认的切页动画（通常是"从右侧滑入"）。
            // 底部栏是 tab 语义 —— tab 之间该是"瞬间就到了"；有滑动动画反而像
            // "又打开了一个新页面"，那种层级感正是切页不适的来源。
            activity.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            // 目标页不存在（比如 SettingsActivity 还没注册进 manifest）时不要崩，
            // 也不要点下去毫无反应 —— 说清楚是哪一步断了
            android.widget.Toast.makeText(activity,
                    "打不开「" + tab.label + "」：" + t.getClass().getSimpleName(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // （旧的 cellBackground 已删：选中态改由 cell 内那条固定高度的 marker 表达，
    //   不再需要给选中格单独上背景 —— 那正是"框在跳"的来源。）
}
