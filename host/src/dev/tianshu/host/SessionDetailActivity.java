package dev.tianshu.host;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话详情页 —— 官方**会话级**路由的 App 落点（2026-10-06②：顺着 3.28.0 的 302 条路由逐条对账补的）。
 *
 * 五块，全是"对话里看不见、但官方接口早就给了"的东西：
 * <ol>
 *   <li>**待批准 / 待应答**（`GET /sessions/:id/interventions`）—— **可批准 / 驳回**
 *       （`POST .../interventions/:requestId/answer`）。这是最要紧的一块：此前 App 只有一条
 *       说明性的批准条，**没有任何批准入口**。</li>
 *   <li>**本会话改动文件**（`GET /sessions/:id/files`）—— 点一条看内容（`/file-content`）。</li>
 *   <li>后台任务（`/jobs`）、4. 运行挂钩（`/hooks`）、5. 审查门（`/review-gate`）—— 只读。</li>
 * </ol>
 *
 * 入口两个：会话列表长按 →「会话详情」；对话页批准条（有待批时才可见）。
 *
 * ⚠ 刻意**不**收 `/git/graph` —— README 记过它会让整个 Runtime 进程退出（真复现过）。
 */
public class SessionDetailActivity extends BaseActivity {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private String sid = "";
    private LinearLayout box;
    private LinearLayout viewer;
    private TextView viewerTitle;
    private TextView viewerBody;
    private final List<ConsoleSection> sections = new ArrayList<ConsoleSection>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        String extra = it == null ? null : it.getStringExtra(EXTRA_SESSION_ID);
        sid = extra == null ? "" : extra;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        main.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "会话详情"));
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
        header.addView(Kit.roundButton(this, Icon.BACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), blp);
        main.addView(header);

        TextView sub = StarViews.muted(this,
                "官方会话级接口：待批准 / 改动文件 / 后台任务 / 运行挂钩 / 审查门 —— 对话里看不到的那些");
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        main.addView(sub);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 文件内容 / 动作回执 的落点：点文件才填。放最上面 —— 点了立刻看得见。
        viewer = Kit.card(this);
        viewerTitle = new TextView(this);
        viewerTitle.setTextSize(Ui.LABEL);
        Theming.tag(viewerTitle, Theming.ROLE_ACCENT);
        viewer.addView(viewerTitle);
        viewerBody = new TextView(this);
        viewerBody.setTextSize(Ui.CAPTION);
        viewerBody.setLineSpacing(0f, 1.25f);
        viewerBody.setTextIsSelectable(true);
        Theming.tag(viewerBody, Theming.ROLE_ITEM);
        viewer.addView(viewerBody);
        viewer.setVisibility(View.GONE);
        box.addView(viewer);

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

    /** 动作回执 / 文件内容共用同一块卡（省一屏控件，也让"点了有反应"永远看得见）。 */
    private void say(String title, String body) {
        if (viewer == null) return;
        viewer.setVisibility(View.VISIBLE);
        viewerTitle.setText(title);
        viewerBody.setText(body);
        Theming.applyTree(this, viewer);
    }

    private void build() {
        if (box == null) return;
        // 清掉上一次的动态段（viewer 永远留在 0 号位）
        while (box.getChildCount() > 1) box.removeViewAt(1);
        sections.clear();

        if (sid.length() == 0) {
            box.addView(AppKit.emptyState(this, "没有会话",
                    "这条入口要从**会话**进：会话列表长按 →「会话详情」，或点对话页的批准条。"));
            return;
        }

        box.addView(AppKit.cardTitle(this, Icon.SHIELD, "待批准 / 待应答"));
        box.addView(StarViews.muted(this, "天枢卡在这儿等你点头 —— 批准之后它才会动手。"));
        loadApprovals();

        box.addView(AppKit.cardTitle(this, Icon.COMMAND, "本会话改动文件"));
        box.addView(StarViews.muted(this, "点一条看内容；.rivet/ 与 .git/ 内部件默认折叠。"));
        loadFiles();

        String[][] later = new String[][] {
                { "后台任务", "终端里是 /jobs。", "/jobs" },
                { "运行挂钩", "项目级 hooks。终端里是 /hooks。", "/hooks" },
                { "审查门", "自动审查的开关状态。", "/review-gate" },
                // 2026-10-06③ 再对账补的（同样是官方会话级只读路由，逐条实测 200 并抓了夹具）
                { "会话技能", "本会话已装的技能。终端里是 /skill。", "/skills" },
                { "可装技能", "还有哪些技能能装进来。", "/skills/installable" },
                { "星域花名册", "本会话可切的星域（含各自信念）。", "/domains" } };
        for (String[] p : later) {
            ConsoleSection s = new ConsoleSection(this, p[0], p[1], "/sessions/" + sid + p[2], 2);
            sections.add(s);
            box.addView(s.view());
        }
        for (ConsoleSection s : sections) s.load();
        Theming.applyTree(this, box);
    }

    // ---------------------------------------------------------------- 待批准

    private void loadApprovals() {
        final LinearLayout holder = Kit.card(this);
        holder.addView(StarViews.muted(this, "读取中…"));
        box.addView(holder);
        RuntimeApi.get("/sessions/" + sid + "/interventions", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                holder.removeAllViews();
                List<SessionDetail.Approval> list = SessionDetail.parseInterventions(body);
                if (list.isEmpty()) {
                    holder.addView(StarViews.muted(SessionDetailActivity.this,
                            "没有待批准的事 —— 天枢没卡着。"));
                } else {
                    for (SessionDetail.Approval a : list) holder.addView(approvalRow(a));
                }
                Theming.applyTree(SessionDetailActivity.this, holder);
            }

            @Override
            public void fail(String message) {
                holder.removeAllViews();
                holder.addView(StarViews.muted(SessionDetailActivity.this, "读不到：" + message));
                Theming.applyTree(SessionDetailActivity.this, holder);
            }
        });
    }

    private View approvalRow(final SessionDetail.Approval a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(a.title());
        t.setTextSize(Ui.LABEL);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(a.subtitle());
        s.setTextSize(Ui.CAPTION);
        s.setLineSpacing(0f, 1.25f);
        Theming.tag(s, Theming.ROLE_MUTED);
        row.addView(s);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);

        Button yes = new Button(this);
        yes.setText("批准");
        Theming.tag(yes, Theming.ROLE_CHIP);
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(a, true);
            }
        });
        btns.addView(yes);

        Button no = new Button(this);
        no.setText("驳回");
        Theming.tag(no, Theming.ROLE_CHIP);
        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer(a, false);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Theming.dp(this, Ui.S1);
        btns.addView(no, lp);

        row.addView(btns);
        return row;
    }

    private void answer(final SessionDetail.Approval a, final boolean approve) {
        RuntimeApi.post(SessionDetail.answerPath(sid, a.requestId),
                SessionDetail.answerBody(approve), new RuntimeApi.Cb() {
                    @Override
                    public void ok(String body) {
                        say(approve ? "已批准" : "已驳回",
                                a.title() + "\n" + a.subtitle());
                        loadApprovals();                     // 结果确实变了：重拉一次
                    }

                    @Override
                    public void fail(String message) {
                        say("没成功", message);
                    }
                });
    }

    // ---------------------------------------------------------------- 改动文件

    private void loadFiles() {
        final LinearLayout holder = Kit.card(this);
        holder.addView(StarViews.muted(this, "读取中…"));
        box.addView(holder);
        RuntimeApi.get("/sessions/" + sid + "/files", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                holder.removeAllViews();
                List<String> all = SessionDetail.parseFiles(body);
                List<String> user = SessionDetail.userFiles(all);
                if (user.isEmpty()) {
                    holder.addView(StarViews.muted(SessionDetailActivity.this,
                            all.isEmpty() ? "这条会话还没有动过文件。"
                                    : "只有 " + all.size() + " 个天枢自己的内部件（.rivet/），没有用户文件改动。"));
                } else {
                    for (String p : user) {
                        holder.addView(Kit.cardRow(SessionDetailActivity.this, p, "点开看内容",
                                new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        openFile(p);
                                    }
                                }));
                    }
                }
                Theming.applyTree(SessionDetailActivity.this, holder);
            }

            @Override
            public void fail(String message) {
                holder.removeAllViews();
                holder.addView(StarViews.muted(SessionDetailActivity.this, "读不到：" + message));
                Theming.applyTree(SessionDetailActivity.this, holder);
            }
        });
    }

    private void openFile(final String path) {
        say(path, "读取中…");
        RuntimeApi.get("/sessions/" + sid + SessionDetail.fileContentPath(path), new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                String content = SessionDetail.contentOf(body);
                String lang = SessionDetail.languageOf(body);
                if (content.isEmpty()) content = "（空文件）";
                final int CAP = 4000;
                if (content.length() > CAP) content = content.substring(0, CAP) + "\n…（已截断）";
                say(path + (lang.isEmpty() ? "" : "  ·  " + lang), content);
            }

            @Override
            public void fail(String message) {
                say(path, "读不到：" + message);
            }
        });
    }
}
