package dev.tianshu.host;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/**
 * 编年史页 —— `/chronicle` 在 App 的落点。
 *
 * harness 侧 `/chronicle` 是里程碑编年史浮层（`src/constellation/format.ts: formatConstellationHistory`
 * 的呈现），数据同 {@code .rivet/constellation.json}。这里做成原生列表：每条一条卡，
 * 最新在前；超过 {@link #PAGE_LIMIT} 条的部分提示在 archive 里。
 */
public class ChronicleActivity extends BaseActivity {

    /** 一次最多列多少条（再多就提示去 archive；真机上长列表滚起来也累）。 */
    private static final int PAGE_LIMIT = 50;

    private LinearLayout box;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        main.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "编年史"));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        View refresh = Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                build();
            }
        });
        header.addView(refresh);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Theming.dp(this, Ui.S1);
        header.addView(StarViews.backButton(this), blp);
        main.addView(header);

        android.widget.TextView sub = StarViews.muted(this,
                "这条项目的里程碑编年史，最新在前（读自 .rivet/constellation.json）");
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        main.addView(sub);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        main.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);
        build();
    }

    private void build() {
        if (box == null) return;
        box.removeAllViews();

        Constellation.Doc d = StarViews.load(this);
        if (d == null) {
            box.addView(AppKit.emptyState(this, "还没有编年史",
                    "收尾一次会话、或在对话里发 /constellation update <摘要> 之后，"
                            + "里程碑会记到 .rivet/constellation.json。"));
            return;
        }

        long now = System.currentTimeMillis();
        box.addView(AppKit.statCard(this, String.valueOf(d.milestones.size()),
                "里程碑", d.name.length() > 0 ? ("项目 " + d.name) : ""));

        if (d.milestones.isEmpty()) {
            LinearLayout empty = Kit.card(this);
            empty.addView(StarViews.muted(this, "还没有里程碑。"));
            box.addView(empty);
            return;
        }

        int total = d.milestones.size();
        int shown = 0;
        for (int i = total - 1; i >= 0 && shown < PAGE_LIMIT; i--, shown++) {
            Constellation.Milestone m = d.milestones.get(i);
            String title = m.summary.length() > 0 ? m.summary : "（无摘要）";
            String desc = Constellation.milestoneLine(m, now);
            // 一条一张卡（与 harness 的一行一条对应）；不给点击 —— 编年史是只读的。
            box.addView(Kit.cardRow(this, title, desc, null));
        }

        if (total > PAGE_LIMIT) {
            box.addView(StarViews.muted(this,
                    "仅显示最近 " + PAGE_LIMIT + " 条，共 " + total
                            + " 条；更早的滚存在 .rivet/constellation.archive.jsonl。"));
        }
    }
}
