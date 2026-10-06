package dev.tianshu.host;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 手搓的侧拉菜单。
 *
 * 为什么不用 {@code DrawerLayout}：它在 support / androidx 里，而本项目只依赖
 * android.jar（构建链是手搓的 aapt2 → javac → D8，没有依赖仓库）。所以自己叠三层：
 *   主内容 → 遮罩(scrim) → 左侧面板(panel)
 *
 * 开关走 translationX 动画（ViewPropertyAnimator，API 12+）；点遮罩即关 ——
 * 那是手机上最自然的退出动作。返回键的关闭由各 Activity 的 onBackPressed 负责
 * （它们问 {@link #isOpen()}）。
 */
public final class SidePanel {

    /** 面板宽度占屏宽的比例。0.78 —— 留一条缝，让用户看得见"后面还有页面"。 */
    public static final float WIDTH_FRACTION = 0.78f;

    private static final int OPEN_MS = 200;
    private static final int CLOSE_MS = 170;

    private final View scrim;
    private final LinearLayout panel;
    private final int panelWidth;
    /** 面板里属于 header 的 child 个数 —— setContent 时要保留它们。 */
    private final int headerCount;
    private boolean open;

    private SidePanel(View scrim, LinearLayout panel, int panelWidth, int headerCount) {
        this.scrim = scrim;
        this.panel = panel;
        this.panelWidth = panelWidth;
        this.headerCount = headerCount;
    }

    /**
     * 把一个侧拉菜单挂到 host 上（主内容应已加进 host）。
     *
     * @param header  面板顶部固定区（可为 null）
     * @param content 面板主体（可为 null，之后用 {@link #setContent} 换）
     */
    public static SidePanel attach(final Activity a, FrameLayout host, View header, View content) {
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        int width = (int) (screen * WIDTH_FRACTION);

        final View scrim = new View(a);
        scrim.setBackgroundColor(0xAA000000);
        scrim.setVisibility(View.GONE);
        host.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        Theming.tag(panel, Theming.ROLE_CARD);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        host.addView(panel, plp);

        if (header != null) panel.addView(header);
        if (content != null) {
            panel.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        final SidePanel sp = new SidePanel(scrim, panel, width, header == null ? 0 : 1);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.close();
            }
        });
        return sp;
    }

    public boolean isOpen() {
        return open;
    }

    /** 换掉面板主体（比如会话列表刷新后重建）。header 保留。 */
    public void setContent(View content) {
        while (panel.getChildCount() > headerCount) {
            panel.removeViewAt(panel.getChildCount() - 1);
        }
        if (content != null) {
            panel.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    public void toggle() {
        if (open) close();
        else open();
    }

    public void open() {
        if (open) return;
        open = true;

        panel.setVisibility(View.VISIBLE);
        panel.animate().cancel();
        panel.setTranslationX(-panelWidth);
        panel.animate().translationX(0).setDuration(OPEN_MS).start();

        scrim.setVisibility(View.VISIBLE);
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(OPEN_MS).start();
    }

    public void close() {
        if (!open) return;
        open = false;

        panel.animate().cancel();
        panel.animate().translationX(-panelWidth).setDuration(CLOSE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!open) panel.setVisibility(View.GONE);
                    }
                }).start();

        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(CLOSE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!open) scrim.setVisibility(View.GONE);
                    }
                }).start();
    }
}
