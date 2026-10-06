package dev.tianshu.host;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 自绘的操作面板与单行输入框（会话的改名 / 归档 / 删除都走它）。
 *
 * **为什么不用系统 AlertDialog 的按钮栏**：本项目的外观是 {@link Theming#walk} 顺着 View 树
 * 贴上去的，而系统对话框的按钮在它自己的 decor 里、**不在我们建的这棵树里** ——
 * 换到深色主题时它们仍是系统默认色。这个坑本项目已经踩过一次：`ListView` 的条目由 Adapter
 * 在 layout 时才创建、同样不在树里，结果深色主题下整列会话像没渲染
 * （见 {@link SessionListActivity} 的类注释）。所以这里把 Dialog 只当壳：
 * **里面每一个控件都自己造、自己贴色**，不挂系统按钮。
 *
 * ⚠ 不要用 `setTag(Object)` 往这个面板的控件上挂东西 —— {@link Theming#tag} 用的就是这个
 * 单槽位，占了它换肤就会失效。这里的做法是先把 Dialog 造出来，让各行的点击回调**直接闭包**它。
 */
public final class ActionSheet {

    /** 点中第几项（0 起）。 */
    public interface OnPick {
        void pick(int index);
    }

    /** 提交输入框里的文本。 */
    public interface OnSubmit {
        void submit(String text);
    }

    private ActionSheet() {
    }

    /**
     * 从底部弹出的动作列表。
     *
     * @param title 标题（可为 null）
     * @param items 动作项；点中后**先关面板再回调**，避免回调里又弹一个面板时叠在一起
     */
    public static Dialog show(Activity a, String title, String[] items, final OnPick onPick) {
        final Dialog d = base(a);
        LinearLayout box = column(a);

        if (title != null && title.length() > 0) {
            box.addView(text(a, title, Ui.TITLE, Theming.ROLE_PLAIN, true, Ui.S2));
        }

        for (int i = 0; items != null && i < items.length; i++) {
            final int idx = i;
            TextView row = text(a, items[i], Ui.BODY, Theming.ROLE_ITEM, false, 0);
            int p = Theming.dp(a, Ui.S4);
            row.setPadding(p, p, p, p);
            row.setMinimumHeight(Theming.dp(a, Ui.TOUCH_MIN));   // 手指够得着
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissQuietly(d);
                    if (onPick != null) onPick.pick(idx);
                }
            });
            Kit.pressable(row);      // 与全 App 一致的按压反馈（缩放，不消费点击）
            box.addView(row, stackLp(a));
        }

        TextView cancel = text(a, "取消", Ui.BODY, Theming.ROLE_MUTED, false, 0);
        int cp = Theming.dp(a, Ui.S4);
        cancel.setPadding(cp, cp, cp, cp);
        cancel.setMinHeight(Theming.dp(a, Ui.TOUCH_MIN));
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissQuietly(d);
            }
        });
        box.addView(cancel);

        return present(a, d, box);
    }

    /**
     * 单行文本输入（改名用）。
     *
     * @param initial 预填文本（光标停在行尾，用户直接退格或全清都顺手）
     * @param hint    输入框提示
     */
    public static Dialog promptText(Activity a, String title, String initial, String hint,
                                    final OnSubmit onSubmit) {
        final Dialog d = base(a);
        LinearLayout box = column(a);
        if (title != null && title.length() > 0) {
            box.addView(text(a, title, Ui.TITLE, Theming.ROLE_PLAIN, true, Ui.S2));
        }

        final EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setHint(hint == null ? "" : hint);
        if (initial != null) {
            field.setText(initial);
            field.setSelection(initial.length());
        }
        Theming.tag(field, Theming.ROLE_FIELD);
        box.addView(field, stackLp(a));

        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);          // 参考：按钮行**右对齐**，不是各占半宽拉满
        TextView cancel = text(a, "取消", Ui.BODY, Theming.ROLE_MUTED, false, 0);
        TextView ok = text(a, "保存", Ui.BODY, Theming.ROLE_BUBBLE, false, 0);
        int p = Theming.dp(a, Ui.S3);
        cancel.setPadding(p, p, p, p);
        ok.setPadding(p, p, p, p);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissQuietly(d);
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = field.getText() == null ? "" : field.getText().toString();
                dismissQuietly(d);
                if (onSubmit != null) onSubmit.submit(t);
            }
        });
        actions.addView(cancel);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = Theming.dp(a, Ui.S1);
        actions.addView(ok, okLp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Theming.dp(a, Ui.S5);
        box.addView(actions, alp);

        return present(a, d, box);
    }

    /**
     * 二次确认对话框 —— 删除 / 归档 / 中止这类**有后果**的操作用它。
     *
     * 照参考的 `YukiConfirmDeleteDialog`：
     *   - 破坏性操作的确认键走 {@link Theming#ROLE_DANGER_BTN}（**实色错误底**）——
     *     参考的原话是"删除键不该长得和确认键一样好看，那本身就是个危险信号"；
     *   - 正文用次要色，与标题拉开层次（标题问"做什么"，正文说"后果是什么"）；
     *   - 按钮行右对齐，取消在左（文字）、确认在右（实心）。
     *
     * @param destructive true = 确认键用错误色（删除 / 清空 / 中止）
     */
    public static Dialog confirm(Activity a, String title, String message,
                                 String confirmText, boolean destructive,
                                 final Runnable onConfirm) {
        final Dialog d = base(a);
        LinearLayout box = column(a);

        if (title != null && title.length() > 0) {
            box.addView(text(a, title, Ui.TITLE, Theming.ROLE_PLAIN, true, Ui.S2));
        }
        if (message != null && message.length() > 0) {
            TextView msg = text(a, message, Ui.CAPTION, Theming.ROLE_MUTED, false, 0);
            msg.setLineSpacing(0f, 1.35f);
            box.addView(msg);
        }

        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView cancel = text(a, "取消", Ui.BODY, Theming.ROLE_MUTED, false, 0);
        int p = Theming.dp(a, Ui.S3);
        cancel.setPadding(p, p, p, p);
        cancel.setMinimumHeight(Theming.dp(a, Ui.TOUCH_MIN));
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissQuietly(d);
            }
        });

        Button ok = new Button(a);
        ok.setText(confirmText == null || confirmText.length() == 0 ? "确认" : confirmText);
        ok.setTextSize(Ui.BODY);
        Theming.tag(ok, destructive ? Theming.ROLE_DANGER_BTN : Theming.ROLE_GHOST);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissQuietly(d);
                if (onConfirm != null) onConfirm.run();
            }
        });

        actions.addView(cancel);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = Theming.dp(a, Ui.S1);
        actions.addView(ok, okLp);

        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Theming.dp(a, Ui.S5);
        box.addView(actions, alp);

        return present(a, d, box);
    }

    // ---------------------------------------------------------------- 内部

    /** 透明底、无标题的壳。先把它造出来，各行的点击回调直接闭包它。 */
    private static Dialog base(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // 24dp 内边距（照参考对话框的 padding）。原先 16dp 在"标题 + 若干行 + 按钮行"的
        // 弹层里显得局促 —— 扁平风的留白就是它的层次，挤掉就没有了。
        int p = Theming.dp(a, Ui.S5);
        box.setPadding(p, p, p, p);
        Theming.tag(box, Theming.ROLE_CARD);
        return box;
    }

    private static TextView text(Activity a, String value, float size, String role,
                                 boolean bold, int padBottomUnits) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        if (padBottomUnits > 0) {
            t.setPadding(0, 0, 0, Theming.dp(a, padBottomUnits));
        }
        Theming.tag(t, role);
        return t;
    }

    private static LinearLayout.LayoutParams stackLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theming.dp(a, Ui.S1);
        return lp;
    }

    /** 装内容、贴色、从底部弹出。 */
    private static Dialog present(Activity a, Dialog d, LinearLayout box) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        int m = Theming.dp(a, Ui.S4);
        outer.setPadding(m, m, m, m);

        // ⚠ 长列表必须可滚 —— 2026-10-05 真机反馈：对话页切星域「滑不动，看不到其他星域」。
        // 根因是这里原先把整列 box 直接塞进 WRAP_CONTENT 的窗口：14 个星域（模型选择更甚）
        // 超出屏高的部分**既滚不动也点不到**，用户只能看到前十来个。
        // 现在套一层带上限的 ScrollView：短列表仍按内容自适应，长列表能滚。
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72f);
        CappedScroll sv = new CappedScroll(a, maxH);
        sv.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        outer.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        d.setContentView(outer);

        Theming.applyTree(a, outer);

        Window w = d.getWindow();
        if (w != null) {
            // 透明底 + 自己画的卡片，避免套两层系统背景色
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    /**
     * 带上限的 ScrollView —— 内容短时按内容高（弹层不会莫名变高），
     * 内容长时封顶到 {@code maxH} 并内部滚动（弹层不会顶穿屏幕把「取消」挤出去）。
     */
    private static final class CappedScroll extends ScrollView {
        private final int maxH;

        CappedScroll(Activity a, int maxH) {
            super(a);
            this.maxH = maxH;
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, capped);
        }
    }

    private static void dismissQuietly(Dialog d) {
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {
            // 已经没了就算了
        }
    }
}
