package dev.tianshu.host;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页 —— 运行状态 + **运行控制台** + 日志。
 *
 * 终端里 `settings` 那一面有 25 条命令（`/config` `/settings` `/mirror` `/permission`
 * `/tools` `/trust` `/grant` `/domain` `/update` …）。这些命令**在 App 里发出去是无效的**
 * （HTTP API 没有"执行斜杠命令"的入口，268 条候选路由里搜 command/palette/slash 命中 0），
 * 但它们背后的**能力**都暴露着（`/config/*` 82 条路由）。这一页就是把那批能力接成界面。
 *
 * 关键取舍：**不为每一族手写界面**。27 个族的返回形状一致（顶层标量 + 少量嵌套），
 * 所以用 {@link ConfigView} 一份通用摊平器渲染，族目录在 {@link ConsoleCatalog} 里声明，
 * 卡片本身是 {@link ConsoleSection}（与面板页共用）。加一族只要往清单加一行。
 *
 * 只做"能证明是对的操作"：顶层布尔就地开关（写入 schema 与真机逐条对过）；其余只读 ——
 * 盲写嵌套配置会把用户的运行时改坏，宁可少一个开关。
 */
public class SettingsActivity extends BaseActivity {

    private TextView statusView;
    private TextView logBody;
    private LinearLayout consoleBox;

    // 「高级：原始配置」的内容容器（**默认收起** —— 普通用户不该一进来就面对那三十来个 /config 族）
    private LinearLayout advancedBox;
    private SwipeNav swipe;
    private final List<ConsoleSection> sections = new ArrayList<ConsoleSection>();

    @Override
    protected void onResume() {
        super.onResume();
        Theming.refresh(this);
        sync();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    // 这里**曾**有一个 onBackPressed 覆盖：`startActivity(对话页) + finish()` —— 注释写着
    // 「设置是底部栏的一页，"返回"该回到对话页，而不是退出 App」。
    //
    // 2026-10-06 删掉（用户拍板，见 `审计.md §11.6`）。它来自 2026-10-02 的页面结构重做（`66bc306`），
    // 而两天后（`f43b0a0`）定下的契约是：**tab 之间是平级、不该有返回栈** —— 主页面按返回 =
    // 两次返回退出 App（原话抄在 {@link BaseActivity#onBackPressed} 上）。两处从引入起就分叉，
    // 而设置页这边是漏网的那一条：真机实测「设置页按返回 → 跳回对话页」，与契约相反。
    //
    // 删掉之后设置页走基类：两次返回退出（`moveTaskToBack`，serve 仍常驻）。别把它写回来 ——
    // HostTest 的 `testTabs` 里有一条族级断言（底栏各页不得借返回键跳页 / 关页）+ 一条点级断言
    // （本页不再出现 onBackPressed）钉着这件事。

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        // 底部给**悬浮**底栏留位（参考的 NavSpaceForContent = 92dp）：
        // 底栏不再是贴底的条、而是浮在内容上方，不留位最后一项会被胶囊盖住。
        main.setPadding(pad, pad, pad, Theming.dp(this, NavBar.SPACE_FOR_CONTENT_DP));

        // 页头：图标徽章 + 标题 + 右侧「刷新」（统一放页头 —— 与「会话」页同一个位置）
        View headerRefresh = Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sync();
                loadConsole();
            }
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "设置"), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(headerRefresh);
        main.addView(header);

        TextView sub = new TextView(this);
        sub.setText("天枢在手机上跑得怎么样 · 模型与目录在哪配 · 出问题去哪儿看日志");
        sub.setTextSize(Ui.CAPTION);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S4));
        Theming.tag(sub, Theming.ROLE_MUTED);
        main.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(Ui.BODY);
        statusView.setLineSpacing(0f, 1.35f);
        // 无底：它现在装在「运行状态」那一行的展开区里（卡里再套卡会多一层色）
        Theming.tag(statusView, Theming.ROLE_PLAIN);
        // 不要在这里 addView 到 main —— statusView 由下面的 attachExpandable 装进
        // 「运行状态」那一行的展开区（见 L136）。两处都 add 会让它同时挂两个父，
        // ViewGroup.addView 直接抛 IllegalStateException（"already has a parent"）→ 进页面即闪退。

        // ---- 主列表：一行一个去处，点开**就地展开** ----
        // 参考 App 的设置首页是"一张卡装若干入口行"；天枢原先把「状态卡 + 操作卡 + 日志卡 + 高级折叠」
        // 四块直接堆在页面上 —— 一进来就是四张卡，得先读一遍才知道哪块是干什么的。
        // 现在主列表只有三行，内容点开才出来：页面短了，也一眼看得出有几个去处。
        consoleBox = new LinearLayout(this);
        consoleBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout listCard = Kit.bareCard(this);
        consoleBox.addView(listCard);

        // ① 运行状态（**默认展开** —— 它是这一页最该先看到的一句话）
        attachExpandable(listCard, Icon.PULSE, "运行状态", "天枢在手机上跑得怎么样", statusView, null);
        statusView.setVisibility(View.VISIBLE);

        // ② 运行日志（正文 + 复制/清空两行，展开才出现）
        attachExpandable(listCard, Icon.DOC, "运行日志", "启动与运行过程；出问题整段复制发给开发者",
                logBlock(), null);

        // 那 27 个 /config 族对普通用户就是噪音（真机反馈 #6：「意义不明，不知道能干什么」）——
        // 收进「高级」并**默认折叠**。展开时才去拉数据：不展开就别发 27 个请求。
        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        // 按用途分组 —— 二十多个族平铺着，找一样东西要从头扫到尾（用户反馈「很劣质、很难懂」）。
        // 分组规则是纯逻辑（`ConsoleCatalog.groupOf`）：没登记过的路径落进「其它」，
        // 所以**以后加一族就算忘了登记，也不会从页面上消失**。
        for (String group : ConsoleCatalog.GROUP_ORDER) {
            List<ConsoleCatalog.Fam> inGroup = new ArrayList<ConsoleCatalog.Fam>();
            for (ConsoleCatalog.Fam f : ConsoleCatalog.CONFIG) {
                if (group.equals(ConsoleCatalog.groupOf(f.path))) inGroup.add(f);
            }
            for (ConsoleCatalog.Fam f : ConsoleCatalog.INFO) {
                if (group.equals(ConsoleCatalog.groupOf(f.path))) inGroup.add(f);
            }
            if (inGroup.isEmpty()) continue;
            advancedBox.addView(AppKit.groupTitle(this, group));
            for (ConsoleCatalog.Fam f : inGroup) addSection(f);
        }

        // ③ 高级原始配置（**第一次展开才去拉数据** —— attachExpandable 保证 onOpen 只跑一次）
        attachExpandable(listCard, Icon.GEAR, "高级原始配置",
                ConsoleCatalog.totalCount() + " 个族 —— 分不清就别动",
                advancedBox, new Runnable() {
                    @Override
                    public void run() {
                        // **每次展开都重拉**。原先靠一个"展开过"的布尔只拉一次，而上次
                        // 可能正赶上 serve 没起来 —— 那样这些卡会一直停在「读不到」，
                        // 收起再展开也没用，只能去点页头那个「刷新」（2026-10-05 走查核出）。
                        loadConsole();
                    }
                });

        // ---- 原生入口（模型页 / 面板页）----
        consoleBox.addView(appearanceCard());
        consoleBox.addView(entryCard());

        ScrollView scroller = new ScrollView(this);
        scroller.addView(consoleBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Theming.dp(this, Ui.STACK);
        main.addView(scroller, slp);

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(NavBar.create(this, NavBar.currentFor(this)), NavBar.params(this));

        setContentView(root);
        Theming.apply(this, root);
        swipe = new SwipeNav(this);

        sync();
        // 高级那三十来个族**不在这里拉** —— 折叠着就没人看，等展开时再拉（见 onOpen）。
    }

    // ---------------------------------------------------------------- 入口卡

    /**
     * 「外观」入口卡 —— 用户 2026-10-06 的原话：「把外观页面放到设置页面里做成一个卡片，
     * 点进卡片才是外观页面」。
     *
     * 为什么单独成卡、而没并进下面的「原生入口」：要的就是"设置页里**一张卡**"。
     * 而且外观是这一页唯一"改的是 App 自己长什么样"的入口 —— 模型 / 面板 / 账号那几个是
     * 配置与运行时视角，跟它不是一类东西。卡里只有一行，整张卡就是那个门。
     *
     * 与底栏的关系：外观页原先是底栏第 4 格（`Tabs` 里那格 2026-10-06 已删）。**别把两个门都留**
     * —— 外观页现在是二级页，返回键回设置页（`BaseActivity.onBackPressed` 按"在不在 Tabs 里"分流）。
     * HostTest 的 `testTabs` 与 `testAppearanceEntry` 钉着这两条。
     */
    private View appearanceCard() {
        // 无组标题：行自己的标题就是这张卡的名字（再加一行「外观」小标题是重复）
        Kit.Group g = Kit.group(this, null);
        g.row(Kit.menuRow(this, Icon.HALF, "外观",
                "背景、配色与卡片透明度 —— 改完立刻生效", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(AppearanceActivity.class);
                    }
                }), 56);
        return g.root;
    }

    /** 两个原生入口：模型页（`/connect`）/ 面板页（`/tasks` `/mcp` `/cache` …）。 */
    private View entryCard() {
        // 做成**菜单行**（图标 + 标题 + 副标题 + ›），而不是两个并排的系统按钮：
        // ①「模型与服务商  →」这种自带箭头的按钮，本质上就是一个菜单行；
        // ②并排会让两个入口看起来像"二选一"，而它们其实是两条独立的路。
        // 结构走 Kit.group（一卡一组 + 行间自动分隔线），与参考的设置页同构。
        Kit.Group g = Kit.group(this, "原生入口");

        g.row(Kit.menuRow(this, Icon.STAR, "模型与服务商",
                "密钥、地址和模型分组 —— 配一次就好", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(ModelsActivity.class);
                    }
                }), 56);
        g.row(Kit.menuRow(this, Icon.MENU, "面板",
                "任务、缓存、MCP 这些运行时视角", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(PanelsActivity.class);
                    }
                }), 56);
        // 2026-10-06④ 这里曾加过一行「账号与登录」（官方账号 / OAuth 面当时没有任何落点）。
        // **2026-10-06 晚删掉** —— 用户：「设置页面里面的账号与登录在个人页面重复了，
        // 保留个人页面的，设置页面的去掉」。入口现在只有**个人页底部**那一个（{@link ProfileActivity}）。
        // ⚠️ 别把它加回来：同一个去处开两个门正是这次要拆的东西，跟「外观」那次（把二级页收进设置、
        // 多开一个门）方向相反但纪律相同。{@code HostTest.testAccountEntry} 钉着这条。
        // 命令面板那条落点（`/login` → 账号页）**不是**门，别一起删。
        return g.root;
    }

    private void open(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- 控制台

    private void addSection(final ConsoleCatalog.Fam fam) {
        final ConsoleSection s = new ConsoleSection(this, fam.title, fam.hint, fam.path, fam.depth);
        // 只读族（INFO：运行环境 / 状态）**不接写回调** —— 它们没有对应的写接口。
        // 接了的话界面上会冒出能点的开关与编辑项（account/status 的 loggedIn、
        // environment 的 platform …），点下去对着只读端点 PUT，用户只看到「改不动」。
        if (!ConsoleCatalog.isReadOnly(fam.path)) {
            s.setToggles(new ConsoleSection.OnToggle() {
                @Override
                public void onToggle(String path, ConfigView.Row row, boolean next, ConsoleSection self) {
                    putBool(path, row, next, self);
                }
            });
            s.setEdits(new ConsoleSection.OnEdit() {
                @Override
                public void onEdit(String path, ConfigView.Row row, String text, ConsoleSection self) {
                    putScalar(path, row, text, self);
                }
            });
        }
        sections.add(s);
        advancedBox.addView(s.view());       // 全部进「高级」容器（默认折叠）
    }

    private void loadConsole() {
        for (ConsoleSection s : sections) s.load();
    }

    /**
     * 就地改一个顶层布尔。
     *
     * 写入用 `{"<原键名>": <新值>}` —— 这条规则和真机逐条对过
     * （zen.enabled / runtime-lean.lean / delivery.autoCommit / mirrors.enabled …）。
     * 写完**重拉这一族**，而不是就地翻按钮文字：界面显示的必须是服务端的真实状态。
     */
    private void putBool(String path, ConfigView.Row r, boolean next, final ConsoleSection self) {
        String body = "{\"" + r.key + "\":" + next + "}";
        RuntimeApi.put(path, body, new RuntimeApi.Cb() {
            @Override
            public void ok(String b) {
                Toast.makeText(SettingsActivity.this, "已改，重新读取…", Toast.LENGTH_SHORT).show();
                self.load();
            }

            @Override
            public void fail(String message) {
                Toast.makeText(SettingsActivity.this,
                        "改不动：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 就地改一个**顶层标量**（字符串 / 数字）：`{"<原键名>": <新值>}` 打回同一族路径 ——
     * 与布尔同一条规则（`PUT /config/approval {"approval":"manual"}` 已在真机 serve 上实测 200）。
     *
     * 数字**不加引号**：加了会把 5 变成 "5"，schema 校验要么拒、要么静默改类型。
     * 值合不合法交给服务端判 —— 它回的那句人话比本地猜的准（RuntimeApi 会带上来）。
     */
    private void putScalar(String path, ConfigView.Row r, String text, final ConsoleSection self) {
        if ("number".equals(r.kind) && (text == null || text.trim().isEmpty())) {
            Toast.makeText(this, "数字不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String body = "{\"" + r.key + "\":" + ConfigView.jsonLiteral(r, text) + "}";
        RuntimeApi.put(path, body, new RuntimeApi.Cb() {
            @Override
            public void ok(String b) {
                Toast.makeText(SettingsActivity.this, "已改，重新读取…", Toast.LENGTH_SHORT).show();
                self.load();
            }

            @Override
            public void fail(String message) {
                Toast.makeText(SettingsActivity.this, "改不动：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------- 卡（非控制台）

    /**
     * 把一块内容做成**可展开的入口行**：一行（图标 + 标题 + 副标题 + `›`）+ 点开后出现的内容。
     *
     * 为什么用"就地展开"而不是"各开一个页面"：设置里这几块（运行状态 / 日志 / 高级配置）
     * 都是"看一眼、或抄一段就走"的东西，为它们各建一个 Activity（还要动 manifest）不值当；
     * 就地展开既让主列表保持短，又不用用户来回跳 —— 而主列表短，正是"一眼看得出有几个去处"的前提。
     *
     * @param content 展开时出现的内容（调用方负责它的样式；这里只给它套上左右内边距）
     * @param onOpen   **第一次**展开时调一次（高级配置用它做"展开才拉数据"）
     */
    private void attachExpandable(LinearLayout card, int iconKind, String title, String desc,
                                  final View content, final Runnable onOpen) {
        if (card.getChildCount() > 0) card.addView(Kit.divider(this, 56));

        // 初始**收起**（2026-10-04 深查）：原先这里不设 GONE，于是"运行日志""高级原始配置"
        // 一进页面就是展开的，与注释里"点开就地展开""第一次展开才去拉数据"的设计相反。
        // 运行状态那一处调用方紧接着会自己 setVisibility(VISIBLE) —— 它本来就该常显。
        content.setVisibility(View.GONE);

        final boolean[] opened = {false};
        card.addView(Kit.menuRow(this, iconKind, title, desc, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = content.getVisibility() != View.VISIBLE;
                content.setVisibility(open ? View.VISIBLE : View.GONE);
                if (open && !opened[0]) {
                    opened[0] = true;
                    if (onOpen != null) onOpen.run();
                }
            }
        }));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int hp = Theming.dp(this, Ui.S4);
        wrap.setPadding(hp, 0, hp, Theming.dp(this, Ui.S4));
        wrap.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(wrap);
    }

    /**
     * 日志区的内容块 —— **无卡片底**（它装在「运行日志」那一行的展开区里）。
     *
     * 两个动作（复制 / 清空）也做成**行**，与全 App 的列表语言一致；
     * 原先它们是并排的系统按钮，和"入口行"是两种观感。
     */
    private View logBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        logBody = new TextView(this);
        logBody.setTextSize(Ui.LABEL);
        logBody.setLineSpacing(0f, 1.3f);
        logBody.setTextIsSelectable(true);
        Theming.tag(logBody, Theming.ROLE_MUTED);
        box.addView(logBody);

        box.addView(Kit.divider(this, 0));
        box.addView(Kit.menuRow(this, 0, "复制日志", "整段拷到剪贴板，发给开发者",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("tianshu-log", BootLog.text()));
                            Toast.makeText(SettingsActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
                        }
                    }
                }));
        box.addView(Kit.divider(this, 0));
        box.addView(Kit.menuRow(this, 0, "导出到文件", "写到 " + LogExport.DIR + "（含 serve 日志与环境）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportLog();
                    }
                }));
        box.addView(Kit.divider(this, 0));
        box.addView(Kit.menuRow(this, 0, "清空日志", "只清这一份运行记录",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 有后果的操作先问一句。App 早就备好了这个组件（ActionSheet.confirm，
                        // 破坏性确认键走错误色），但它此前**零调用** —— 清空日志一直是一点就没，
                        // 而这份日志正是出问题时唯一能带走的东西（2026-10-05 走查核出）。
                        ActionSheet.confirm(SettingsActivity.this,
                                "清空运行日志？", "这份记录会消失 —— 出问题时就没了。",
                                "清空", true, new Runnable() {
                                    @Override
                                    public void run() {
                                        BootLog.clear();
                                        sync();
                                    }
                                });
                    }
                }));
        return box;
    }

    /**
     * 把运行报告落到共享存储。
     *
     * 放后台线程：要读 serve 日志（最多 256 KB）再写盘，虽只几十毫秒级，
     * 但设置页跑在主线程，不该在这儿做 IO。
     */
    private void exportLog() {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    String name = LogExport.write(SettingsActivity.this,
                            new RuntimeHost(SettingsActivity.this));
                    msg = "已导出：" + name;
                } catch (Throwable t) {
                    msg = "导出失败：" + t;
                }
                final String m = msg;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 状态与日志

    private void sync() {
        statusView.setText(statusText());
        logBody.setText(BootLog.length() == 0
                ? "（这次进程还没有日志 —— 回启动页重跑一次就会有了）"
                : BootLog.text());
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        if (AppRuntime.isServeReady()) {
            // 「serve 已就绪」是内部说法；对用户就是"能用"。
            // 地址留着——设置页本来就是看细节的地方，但要跟人话在一行里说得清。
            sb.append("运行环境正常 · ").append(AppRuntime.baseUrl());
        } else if (AppRuntime.isServeAlive()) {
            sb.append("还在启动，稍等一会儿");
        } else {
            sb.append("运行环境没起来 —— 回启动页重跑一次启动流程");
        }
        sb.append("\n应用 ").append(versionText());
        sb.append("\n启动日志 ").append(BootLog.length()).append(" 字符");
        return sb.toString();
    }

    private String versionText() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "读不到";
        }
    }
}
