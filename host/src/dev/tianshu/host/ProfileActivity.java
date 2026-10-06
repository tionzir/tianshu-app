package dev.tianshu.host;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 「个人」页 —— 官方 **profile** 面的 App 落点（底栏第 4 格）。
 *
 * <p>用户 2026-10-06 点的名：「这个 app 还差一个人页面」→「是个人页面」。
 * 在此之前，这一面在 App 里**没有任何页面**：`/profile/overview` 与 `/profile/domain-usage`
 * 只作为两条只读条目躲在「设置 → 面板」里，星籍也只在「设置 → 账号与登录」里露一行。
 * 于是"我是谁、用了多少、跑在哪些星域"这三件事，在 App 里没有一个地方能一眼看全。
 *
 * <h3>四块（数据源见 {@link ProfileData#PATHS}）</h3>
 * <ol>
 *   <li><b>身份</b> —— `GET /account/status`：星籍 / 主星域 / 称号。</li>
 *   <li><b>用量</b> —— `GET /profile/overview`：token 总量（页面焦点，大号数字）、
 *       单日峰值、活跃天数、扫描文件数、累计登录时长。</li>
 *   <li><b>星域用量</b> —— `GET /profile/domain-usage`：近 30 天各星域跑了几轮。</li>
 *   <li><b>仓库</b> —— 同一份 overview 里的 `repositories[]`（最多 6 条，只读）。</li>
 * </ol>
 *
 * <h3>为什么"账号与登录"仍然单独一页</h3>
 * 这一页是**看**（我是谁、用了多少），{@link AccountActivity} 是**操作**（设备码登录、
 * 登出、服务商 OAuth 授权）。两者合并会让"登录"这件事藏在一堆统计数字下面，
 * 而它恰恰是新用户进来第一件要做的事 —— 所以这一页底部留一行入口把它送过去。
 *
 * <h3>只读，不做写</h3>
 * 官方 profile 面还有两个写路由（`POST /profile/presence`、`PUT /profile/repositories`）。
 * 这一版**都不接**：presence 是运行时自己上报的在线心跳（App 代填等于伪造在线），
 * repositories 的写入要过 3.28.0 那套 URL 白名单校验（只收 github.com/owner/repo、
 * 最多 6 条、标题 ≤80、描述 ≤240），盲写只会把用户的档案改坏 ——
 * 与设置页那条"只做能证明是对的操作"同一条纪律（见 {@link SettingsActivity} 类注释）。
 */
public class ProfileActivity extends BaseActivity {

    private LinearLayout box;
    private LinearLayout idBody;
    private LinearLayout usageBody;
    private LinearLayout domainBody;
    private LinearLayout repoBody;
    /** 「官方仓库」卡的内容容器（数据来自 harness 自己的 package.json，详见 {@link ProfileData.OfficialRepo}）。 */
    private LinearLayout officialBody;
    private SwipeNav swipe;

    /** 最后一次拿到的数据 —— 任一路回来后重画时，别去动还没到的那几块。 */
    private Account.Status status;
    private ProfileData.Overview overview;
    private ProfileData.Usage usage;
    /** `/config/default-domain` 的原文：星域 id → 中文名要靠它（复用 StatusBar 那份映射）。 */
    private String domainJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        // 底部给**悬浮**底栏让位（同设置页：底栏浮在内容之上，不留位最后一张卡会被药丸盖住）
        main.setPadding(pad, pad, pad, Theming.dp(this, NavBar.SPACE_FOR_CONTENT_DP));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "个人"), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        }));
        main.addView(header);

        TextView sub = new TextView(this);
        sub.setText("你是谁 · 用了多少 · 跑在哪些星域");
        sub.setTextSize(Ui.CAPTION);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S4));
        Theming.tag(sub, Theming.ROLE_MUTED);
        main.addView(sub);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 身份卡**可点**：点它去账号页（登录 / 换账号）。这是这一页唯一与"我是谁"直接相关的动作，
        // 所以并进这张卡 —— 不再单开一张入口卡（2026-10-06 用户要求，见审计 §25）。
        idBody = card(Icon.PERSON, "身份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccount();
            }
        });
        usageBody = card(Icon.BOLT, "用量");
        domainBody = card(Icon.BRAND, "星域用量");
        repoBody = card(Icon.DOC, "仓库");
        officialBody = card(Icon.REPO, "官方仓库");

        // 这里曾有一张**独立的**「账号与登录」入口卡（`Kit.group` + `menuRow` + `Icon.SHIELD`）。
        // 2026-10-06 晚删掉 —— 它的功能并进了上面的「身份」卡（用户：「个人页面的账号与登录的
        // 功能放到身份那个卡里，然后把登录与账号这个卡片删掉」）。
        // ⚠️ 别再加回来：`HostTest.testAccountEntry` 盯着"这一页不许再出现 Icon.SHIELD"。

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box);
        main.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(NavBar.create(this, NavBar.currentFor(this)), NavBar.params(this));

        setContentView(root);
        Theming.apply(this, root);
        swipe = new SwipeNav(this);

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题可能在别的页面被改过（外观页 / 设置页），回来先按当前 tokens 重贴一遍
        Theming.refresh(this);
        load();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ---------------------------------------------------------------- 骨架

    /** 建一张卡：标题 + 一个内容容器（内容由各 load 回调填，四块互不阻塞）。 */
    private LinearLayout card(int icon, String title) {
        return card(icon, title, null);
    }

    /**
     * 建一张**可点**的卡 —— 点整张卡就走 {@code onClick}。
     *
     * 用在「身份」卡上：它的下一个动作（管理账号与登录）与这张卡说的是同一件事
     * ——「我是谁」下一步就是「去登录 / 换账号」——所以不再单开一张入口卡。
     * 用户 2026-10-06：「个人页面的账号与登录的功能放到身份那个卡里，然后把登录与账号这个卡片删掉」。
     */
    private LinearLayout card(int icon, String title, View.OnClickListener onClick) {
        LinearLayout c = onClick == null ? Kit.card(this) : Kit.card(this, onClick);
        c.addView(AppKit.cardTitle(this, icon, title));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(StarViews.muted(this, "读取中…"));
        c.addView(body);
        box.addView(c);
        return body;
    }

    /** 换掉一张卡的内容（整块重建 —— 卡片里没有可编辑控件，重建最省心）。 */
    private void reset(LinearLayout body) {
        body.removeAllViews();
    }

    // ---------------------------------------------------------------- 取数

    private void load() {
        if (box == null) return;

        RuntimeApi.get("/account/status", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                status = Account.parseStatus(body);
                if (alive()) renderIdentity();
            }

            @Override
            public void fail(String message) {
                if (alive()) failInto(idBody, "身份读不到", message);
            }
        });

        RuntimeApi.get("/profile/overview", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                overview = ProfileData.parseOverview(body);
                if (alive()) {
                    renderUsage();
                    renderRepos();
                }
            }

            @Override
            public void fail(String message) {
                if (alive()) {
                    failInto(usageBody, "用量读不到", message);
                    failInto(repoBody, "仓库读不到", message);
                }
            }
        });

        RuntimeApi.get("/profile/domain-usage", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                usage = ProfileData.parseUsage(body);
                if (alive()) renderDomains();
            }

            @Override
            public void fail(String message) {
                if (alive()) failInto(domainBody, "星域用量读不到", message);
            }
        });

        // 星域 id → 中文名的那份映射（与对话页状态行同一份数据源，不另建对照表）
        RuntimeApi.get("/config/default-domain", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                domainJson = body;
                if (alive() && usage != null) renderDomains();
            }

            @Override
            public void fail(String message) {
                // 拿不到就退回显示 id —— 不值得为它把整块星域卡标成错误
            }
        });

        // 官方仓库：读本地 harness 的 package.json（不走网络，也不写死 URL）
        loadOfficial();
    }

    private void failInto(LinearLayout body, String title, String message) {
        reset(body);
        body.addView(AppKit.error(this, title, message, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        }));
        Theming.applyTree(this, body);
    }

    // ---------------------------------------------------------------- 渲染

    /** ① 身份：头像 + 星籍一行（`Account.Status.line()` 已有那句人话）。 */
    private void renderIdentity() {
        reset(idBody);
        if (status == null) {
            idBody.addView(StarViews.muted(this, "读不到账号状态"));
            Theming.applyTree(this, idBody);
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Theming.dp(this, Ui.S3);
        row.addView(AppKit.badge(this, Icon.PERSON, 56), blp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView who = new TextView(this);
        who.setText(status.line());
        who.setTextSize(Ui.BODY);
        if (status.loggedIn) Theming.tag(who, Theming.ROLE_ACCENT);
        texts.addView(who);

        TextView hint = new TextView(this);
        hint.setTextSize(Ui.CAPTION);
        hint.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        Theming.tag(hint, Theming.ROLE_MUTED);
        if (status.loggedIn) {
            String detail = status.email.length() > 0 ? status.email : status.userId;
            hint.setText(detail.length() > 0 ? detail : "已登录");
        } else {
            hint.setText("还没登录 —— 点这张卡拿设备码登入（码在天枢网页里输）");
        }
        texts.addView(hint);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧一枚 `›` —— 整张卡是可点的，但可点性得看得见：这一枚就是那个提示
        Icon chev = new Icon(this, Icon.CHEVRON).size(18);
        Theming.tag(chev, Theming.ROLE_MUTED);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Theming.dp(this, Ui.S2);
        row.addView(chev, clp);

        idBody.addView(row);
        Theming.applyTree(this, idBody);
    }

    /** ② 用量：token 总量是这一页的焦点（大号数字），其余压成两行小字。 */
    private void renderUsage() {
        reset(usageBody);
        ProfileData.Overview o = overview;
        if (o == null) {
            usageBody.addView(StarViews.muted(this, "读不到用量"));
            Theming.applyTree(this, usageBody);
            return;
        }

        // 焦点数字：样式对齐 AppKit.statCard（28sp / 粗体 / 强调色），但**不再套一层卡片底** ——
        // statCard 自带 ROLE_CARD，塞进卡里会两层底色叠成一坨。
        TextView big = new TextView(this);
        big.setText(o.hasTokens ? ProfileData.humanCount(o.tokenTotal) : "—");
        big.setTextSize(28f);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        Theming.tag(big, Theming.ROLE_ACCENT);
        usageBody.addView(big);

        TextView cap = new TextView(this);
        cap.setTextSize(Ui.CAPTION);
        cap.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        Theming.tag(cap, Theming.ROLE_MUTED);
        cap.setText(o.hasTokens
                ? "累计 token · 单日峰值 " + ProfileData.humanCount(o.tokenPeak)
                  + " · 活跃 " + o.activeDays + " 天 · 扫描 " + o.scannedFiles + " 个文件"
                : "服务端暂时没给出用量 —— 点右上角刷新再试");
        usageBody.addView(cap);

        usageBody.addView(Kit.divider(this, 0));
        usageBody.addView(Kit.infoRow(this, "累计登录时长", ProfileData.humanSpan(o.loginMs)));
        Theming.applyTree(this, usageBody);
    }

    /** ③ 星域用量：每行一枚星域徽记 + 中文名 + 轮数；有漏记就如实说一句。 */
    private void renderDomains() {
        reset(domainBody);
        ProfileData.Usage u = usage;
        if (u == null) {
            domainBody.addView(StarViews.muted(this, "读不到星域用量"));
            Theming.applyTree(this, domainBody);
            return;
        }

        TextView head = new TextView(this);
        head.setTextSize(Ui.CAPTION);
        Theming.tag(head, Theming.ROLE_MUTED);
        head.setText("近 " + u.days + " 天 · 共 " + u.totalRuns + " 轮");
        domainBody.addView(head);

        if (u.domains.isEmpty()) {
            domainBody.addView(StarViews.muted(this,
                    "这段时间没有星域记录 —— 在对话里发一句话就会记上。"));
            Theming.applyTree(this, domainBody);
            return;
        }

        int step = Theming.dp(this, Ui.S2);
        boolean first = true;
        for (ProfileData.Domain d : u.domains) {
            if (!first) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(1, step));
                domainBody.addView(gap);
            }
            first = false;
            domainBody.addView(domainRow(d));
        }

        if (u.missingRuns > 0) {
            TextView miss = new TextView(this);
            miss.setTextSize(Ui.CAPTION);
            miss.setPadding(0, Theming.dp(this, Ui.S2), 0, 0);
            Theming.tag(miss, Theming.ROLE_WARN);
            miss.setText("另有 " + u.missingRuns + " 轮没记到星域（早期会话没写这条事件）");
            domainBody.addView(miss);
        }
        Theming.applyTree(this, domainBody);
    }

    private View domainRow(ProfileData.Domain d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Theming.dp(this, Ui.S2);
        row.addView(AppKit.badge(this, Icon.BRAND, 26), blp);

        TextView name = new TextView(this);
        // 中文名走既有那份映射：查不到就原样显示 id，不静默留空
        name.setText(StatusBar.domainName(domainJson, d.key));
        name.setTextSize(Ui.BODY);
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(d.count + " 轮");
        n.setTextSize(Ui.CAPTION);
        Theming.tag(n, Theming.ROLE_MUTED);
        row.addView(n);
        return row;
    }

    /** ④ 仓库：官方档案里的 GitHub 链接（最多 6 条）。只读 —— 写入口见类注释。 */
    private void renderRepos() {
        reset(repoBody);
        ProfileData.Overview o = overview;
        if (o == null) {
            repoBody.addView(StarViews.muted(this, "读不到仓库"));
            Theming.applyTree(this, repoBody);
            return;
        }
        if (o.repos.isEmpty()) {
            repoBody.addView(StarViews.muted(this,
                    "还没有登记仓库 —— 最多 6 条，在终端里用 /profile 登记。"));
            Theming.applyTree(this, repoBody);
            return;
        }

        for (ProfileData.Repo r : o.repos) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S1));

            TextView t = new TextView(this);
            t.setText(r.label());
            t.setTextSize(Ui.BODY);
            row.addView(t);

            TextView u = new TextView(this);
            u.setText(r.url);
            u.setTextSize(Ui.CAPTION);
            Theming.tag(u, Theming.ROLE_MUTED);
            row.addView(u);

            if (r.description.length() > 0) {
                TextView d = new TextView(this);
                d.setText(r.description);
                d.setTextSize(Ui.CAPTION);
                Theming.tag(d, Theming.ROLE_MUTED);
                row.addView(d);
            }
            repoBody.addView(row);
        }
        Theming.applyTree(this, repoBody);
    }

    /**
     * ⑤ 官方仓库 —— 读 harness 自己的 `package.json`（{@link ProfileData.HARNESS_PACKAGE_REL}）。
     *
     * <p>几条刻意的选择：
     *   - **不写死 URL**：写死的链接没人记得回来改；harness 的 `repository` 字段才是权威声明
     *     （它也是 harness 自己做更新检查时的取值来源）。
     *   - **读不到就不画这张行**：宁可显示一句"运行环境还没就位"，也不要画一个点不动的按钮
     *     —— 与{@link PanelCatalog}那条"有夹具才声明"是同一条纪律。
     *   - 同步读盘：文件 4.6 KB，与 {@link StarViews} 读 `constellation.json` 同一量级
     *     （那里也是同步读），不值得为它起线程。
     */
    private void loadOfficial() {
        if (officialBody == null) return;
        reset(officialBody);

        ProfileData.OfficialRepo repo;
        try {
            File pkg = new File(new RuntimeHost(this).rootfsDir(), ProfileData.HARNESS_PACKAGE_REL);
            repo = ProfileData.parseOfficialRepo(LocalData.readText(pkg));
        } catch (Throwable t) {
            repo = ProfileData.parseOfficialRepo(null);
        }

        if (!repo.ok()) {
            officialBody.addView(StarViews.muted(this,
                    "读不到官方仓库信息 —— 运行环境还没就位，稍后再刷新。"));
            Theming.applyTree(this, officialBody);
            return;
        }

        // 行里不再放图标：卡标题已经带了仓库图标，行上再来一枚就重复了（menuRow 的 iconKind=0）
        final String url = repo.url;
        Kit.Group g = Kit.group(this, null);
        g.row(Kit.menuRow(this, 0, repo.slug(),
                repo.version.length() > 0
                        ? "天枢的源码、文档与更新 · 已装 " + repo.version
                        : "天枢的源码、文档与更新",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openUrl(url);
                    }
                }));
        officialBody.addView(g.root);
        Theming.applyTree(this, officialBody);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开浏览器，地址是：" + url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 进账号页 —— 「身份」卡的点击落点。
     *
     * 单开一个方法而不是在 lambda 里直接写 `open(AccountActivity.class)`：这行是
     * "入口还在"的**源码锚点**，HostTest 的 `testAccountEntry` 拿它当凭据（名字改了测试就红）。
     */
    private void openAccount() {
        open(AccountActivity.class);
    }

    private void open(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
