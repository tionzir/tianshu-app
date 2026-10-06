package dev.tianshu.host;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/**
 * 星图页 —— `/starmap` 在 App 的落点。
 *
 * harness 侧 `/starmap` 是一个 TUI 浮层：列出**星域花名册** + 最近走过的里程碑
 * （`src/main.ts:1130 starmapEntries`：`starDomainRegistry.list()` + `loadConstellation(cwd).milestones`）。
 * 这里不搬 TUI 的文本，而是做成原生页：星域来自已有的 `/config/default-domain`，
 * 里程碑直接读 `.rivet/constellation.json`（见 {@link Constellation}）。
 */
public class StarmapActivity extends BaseActivity {

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
        header.addView(AppKit.pageTitle(this, "星图"));
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
                "星域花名册 + 最近走过的里程碑（读自 .rivet/constellation.json）");
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
            box.addView(AppKit.emptyState(this, "还没有星图数据",
                    "天枢跑过一次会话收尾、或在对话里发 /constellation 之后，"
                            + "会把蓝图写到 .rivet/constellation.json。"));
            return;
        }

        box.addView(AppKit.statCard(this, String.valueOf(d.milestones.size()),
                "里程碑", d.name.length() > 0 ? ("项目 " + d.name) : ""));

        // 星域花名册 —— 走已有端点（App 里没有星域注册表的落盘副本）。
        LinearLayout dom = Kit.card(this);
        dom.addView(AppKit.cardTitle(this, Icon.STAR, "星域"));
        final LinearLayout domBody = new LinearLayout(this);
        domBody.setOrientation(LinearLayout.VERTICAL);
        domBody.addView(StarViews.muted(this, "读取中…"));
        dom.addView(domBody);
        box.addView(dom);

        RuntimeApi.get("/config/default-domain", new RuntimeApi.Cb() {
            @Override
            public void ok(final String body) {
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        domBody.removeAllViews();
                        List<String> pairs = StatusBar.domainPairs(body);
                        if (pairs.isEmpty()) {
                            domBody.addView(StarViews.muted(StarmapActivity.this, "（没有星域）"));
                            return;
                        }
                        for (String p : pairs) {
                            int bar = p.indexOf('|');
                            String id = bar < 0 ? p : p.substring(0, bar);
                            String name = bar < 0 ? p : p.substring(bar + 1);
                            domBody.addView(Kit.choiceRow(StarmapActivity.this,
                                    name, id, false, null));
                        }
                    }
                });
            }

            @Override
            public void fail(final String message) {
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        domBody.removeAllViews();
                        domBody.addView(StarViews.muted(StarmapActivity.this,
                                "读不到星域名册：" + message + "（serve 未就绪时属正常）"));
                    }
                });
            }
        });

        // 最近里程碑 —— 与 harness 浮层一样取最后 5 条（倒序）。
        LinearLayout ms = Kit.card(this);
        ms.addView(AppKit.cardTitle(this, Icon.CLOCK, "最近里程碑"));
        long now = System.currentTimeMillis();
        if (d.milestones.isEmpty()) {
            ms.addView(StarViews.muted(this, "还没有里程碑（收尾一次会话或发 /constellation update 后出现）。"));
        } else {
            int n = Math.min(5, d.milestones.size());
            int from = d.milestones.size() - n;
            for (int i = d.milestones.size() - 1; i >= from; i--) {
                Constellation.Milestone m = d.milestones.get(i);
                LinearLayout one = new LinearLayout(this);
                one.setOrientation(LinearLayout.VERTICAL);
                one.addView(StarViews.bodyText(this, m.summary.length() > 0 ? m.summary : "（无摘要）"));
                one.addView(StarViews.muted(this, Constellation.milestoneLine(m, now)));
                one.setPadding(0, Theming.dp(this, Ui.S2), 0, Theming.dp(this, Ui.S2));
                ms.addView(one);
            }
        }
        box.addView(ms);

        Theming.applyTree(this, box);
    }
}
