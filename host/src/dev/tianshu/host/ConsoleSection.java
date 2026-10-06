package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 控制台里的一张「族卡」—— 标题 + 状态行 + 字段行，异步拉一个路由并渲染。
 *
 * 为什么抽出来：设置页（27 个 `/config` 族）和面板页（任务 / MCP / 插件 / 缓存 …）
 * 要的是**同一个形状**。各写一份的话，改间距、改错误展示都要改两处，必然分叉。
 *
 * 它只管**读**。"就地改"由调用方给 {@link OnToggle}（设置页拿它做顶层布尔开关）；
 * 不设就是纯只读。
 */
public final class ConsoleSection {

    /** 就地改「开关」的回调（布尔）。实现者负责发请求，成功后调 {@link ConsoleSection#load()} 重拉。 */
    public interface OnToggle {
        void onToggle(String path, ConfigView.Row row, boolean next, ConsoleSection self);
    }

    /** 就地改「值」的回调（字符串 / 数字）。{@code text} 是用户在输入框里敲的原文，别在这里做猜测性转换。 */
    public interface OnEdit {
        void onEdit(String path, ConfigView.Row row, String text, ConsoleSection self);
    }

    private final Activity activity;
    private final LinearLayout card;
    private final LinearLayout body;
    private final TextView state;
    private final String path;
    private final int depth;
    private OnToggle toggleHandler;
    private OnEdit editHandler;

    /**
     * 请求序号 —— 连点刷新时只认最后一次（2026-10-04 深查：面板页「刷新」可重入、原先无序号，
     * 先发的慢响应后到会把新结果覆盖掉）。
     */
    private final java.util.concurrent.atomic.AtomicInteger reqGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public ConsoleSection(Activity a, String title, String hint, String path, int depth) {
        this.activity = a;
        this.path = path;
        this.depth = depth;

        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Theming.dp(a, Ui.S4);
        card.setPadding(p, p, p, p);
        Theming.tag(card, Theming.ROLE_CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theming.dp(a, Ui.S3);
        card.setLayoutParams(lp);

        TextView h = new TextView(a);
        h.setText(title);
        h.setTextSize(Ui.TITLE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(h, Theming.ROLE_ACCENT);
        card.addView(h);

        if (hint != null && !hint.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(hint);
            s.setTextSize(Ui.CAPTION);
            s.setLineSpacing(0f, 1.3f);
            s.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
            Theming.tag(s, Theming.ROLE_MUTED);
            card.addView(s);
        }

        state = new TextView(a);
        state.setTextSize(Ui.CAPTION);
        state.setPadding(0, Theming.dp(a, Ui.S1), 0, 0);
        Theming.tag(state, Theming.ROLE_MUTED);
        card.addView(state);

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Theming.dp(a, Ui.S1);
        card.addView(body, blp);
    }

    public View view() {
        return card;
    }

    public String path() {
        return path;
    }

    public void setToggles(OnToggle cb) {
        this.toggleHandler = cb;
    }

    public void setEdits(OnEdit cb) {
        this.editHandler = cb;
    }

    /** 拉一次并渲染。失败就把错误显示在状态行（不吞）。 */
    public void load() {
        final int gen = reqGen.incrementAndGet();     // 本次请求的序号
        state.setText("读取中…");
        RuntimeApi.get(path, new RuntimeApi.Cb() {
            @Override
            public void ok(String json) {
                if (gen != reqGen.get()) return;      // 慢响应迟到：别覆盖更新的那一份
                render(ConfigView.flatten(json, depth));
            }

            @Override
            public void fail(String message) {
                if (gen != reqGen.get()) return;
                state.setText("读不到：" + message);
                body.removeAllViews();
            }
        });
    }

    private void render(List<ConfigView.Row> rows) {
        state.setText(rows.isEmpty() ? "（无字段）" : "");
        body.removeAllViews();
        for (ConfigView.Row r : rows) body.addView(rowView(r));
        Theming.applyTree(activity, body);
    }

    private View rowView(final ConfigView.Row r) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theming.dp(activity, Ui.S1);
        row.setLayoutParams(lp);

        TextView key = new TextView(activity);
        key.setText(r.label);
        key.setTextSize(Ui.CAPTION);
        Theming.tag(key, Theming.ROLE_MUTED);
        row.addView(key, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (r.isBool && toggleHandler != null) {
            final Button t = new Button(activity);
            t.setText(ConfigView.boolText(r.boolValue));
            Theming.tag(t, Theming.ROLE_CHIP);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleHandler.onToggle(path, r, !r.boolValue, ConsoleSection.this);
                }
            });
            row.addView(t);
        } else {
            TextView val = new TextView(activity);
            val.setText(r.isBool ? ConfigView.boolText(r.boolValue) : r.value);
            val.setTextSize(Ui.CAPTION);
            val.setTextIsSelectable(true);
            val.setGravity(Gravity.END);
            // 顶层标量 → **点一下就地改**（用户反馈：Termux 里能改的，设置页里也应该能改）。
            // 长按依旧可以选中复制 —— 编辑走点击，两者不冲突。嵌套字段仍然只读（写入 schema 没验过）。
            if (r.editable && editHandler != null) {
                Theming.tag(val, Theming.ROLE_ITEM);          // 给一个"这里可以点"的外观
                val.setClickable(true);
                val.setFocusable(true);
                int h = Theming.dp(activity, Ui.S2);
                int v = Theming.dp(activity, Ui.S1);
                val.setPadding(h, v, h, v);
                val.setMinHeight(Theming.dp(activity, Ui.TOUCH_MIN));
                val.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 输入框预填**原始值**（r.raw），不是显示值：显示值可能已翻成中文名
                        // （defaultDomain 的 "启明"），拿它去写会把配置写成名字。
                        String initial = r.raw;
                        if ("（未设）".equals(initial) || "（空）".equals(initial)) initial = "";
                        ActionSheet.promptText(activity, "改 " + r.label + "（" + r.kind + "）",
                                initial, r.label, new ActionSheet.OnSubmit() {
                                    @Override
                                    public void submit(String text) {
                                        editHandler.onEdit(path, r, text, ConsoleSection.this);
                                    }
                                });
                    }
                });
            }
            row.addView(val, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));
        }
        return row;
    }
}
