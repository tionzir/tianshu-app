package dev.tianshu.host;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * 蓝图页 —— `/constellation` 在 App 的落点。
 *
 * harness 侧 `/constellation` 打开"项目蓝图"浮层（`src/constellation/format.ts: formatConstellationView`）：
 * 骨架（模块 / 入口 / 技术栈 / 关键抽象）+ 里程碑 + 架构变迁。数据同 {@code .rivet/constellation.json}。
 * 这里做成原生页；文案全中文（harness 用 ✓/✗ 字形，设备字体覆盖不保证，见 {@link Constellation#verifyLabel}）。
 */
public class ConstellationActivity extends BaseActivity {

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
        header.addView(AppKit.pageTitle(this, "项目蓝图"));
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
                "项目骨架 + 里程碑 + 架构变迁（读自 .rivet/constellation.json）");
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
            box.addView(AppKit.emptyState(this, "还没有项目蓝图",
                    "在对话里发 /constellation init —— 天枢会轻量扫一次骨架（src/* 模块、入口、"
                            + "技术栈），写到 .rivet/constellation.json。"));
            return;
        }

        long now = System.currentTimeMillis();
        String hint = d.name.length() > 0 ? ("项目 " + d.name) : "";
        if (d.lastUpdatedAt > 0) {
            hint += (hint.length() > 0 ? " · " : "") + "更新于 " + Constellation.relativeTime(d.lastUpdatedAt, now);
        }
        box.addView(AppKit.statCard(this, String.valueOf(d.milestones.size()), "里程碑", hint));

        // 骨架
        LinearLayout sk = Kit.card(this);
        sk.addView(AppKit.cardTitle(this, Icon.BRAND, "骨架 Skeleton"));
        if (d.skeleton.isEmpty()) {
            sk.addView(StarViews.muted(this, "（骨架为空 —— /constellation init 会做一次轻量扫描）"));
        } else {
            StarViews.field(this, sk, "入口", StarViews.join(d.skeleton.entryPoints));
            StarViews.field(this, sk, "模块", StarViews.join(d.skeleton.modules));
            StarViews.field(this, sk, "技术栈", StarViews.join(d.skeleton.techStack));
            StarViews.field(this, sk, "关键抽象", StarViews.join(d.skeleton.keyAbstractions));
        }
        box.addView(sk);

        // 架构变迁
        LinearLayout sh = Kit.card(this);
        sh.addView(AppKit.cardTitle(this, Icon.LEVEL, "架构变迁"));
        if (d.shiftCount == 0) {
            sh.addView(StarViews.muted(this, "还没有记录（骨架被重新 survey 且发生变化时才有）。"));
        } else {
            StarViews.field(this, sh, "次数", String.valueOf(d.shiftCount));
            StarViews.field(this, sh, "最近一次", d.latestShift);
        }
        box.addView(sh);

        // 最近里程碑
        LinearLayout ms = Kit.card(this);
        ms.addView(AppKit.cardTitle(this, Icon.CLOCK, "最近里程碑"));
        if (d.milestones.isEmpty()) {
            ms.addView(StarViews.muted(this, "还没有里程碑。"));
        } else {
            int n = Math.min(8, d.milestones.size());
            int from = d.milestones.size() - n;
            for (int i = d.milestones.size() - 1; i >= from; i--) {
                View gap = new View(this);
                gap.setMinimumHeight(Theming.dp(this, Ui.S2));
                ms.addView(gap);
                Constellation.Milestone m = d.milestones.get(i);
                ms.addView(StarViews.bodyText(this, m.summary.length() > 0 ? m.summary : "（无摘要）"));
                ms.addView(StarViews.muted(this, Constellation.milestoneLine(m, now)));
            }
        }
        box.addView(ms);

        Theming.applyTree(this, box);
    }
}
