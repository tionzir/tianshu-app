package dev.tianshu.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史会话列表。
 *
 * 解决的是「每次打开 App 都是全新会话，之前的对话看不到」。
 *
 * 三条事实（都是实测的）：
 *   1. `GET /sessions` 返回的每条带 `title` / `status` / `createdAt` —— 列表页要的都有；
 *   2. **可以接着聊**：`POST /sessions/:id/prompt` 返 200 / 409（409 = 会话正在跑）。
 *      这里原先写着「没有任何续聊路由（`/prompt`、`/messages` 全 404），所以点进去是只读重放」——
 *      那是个错误结论：旧探针用 **GET** 去打了 POST 路由（routes-probe.json 里这条记着
 *      `probedWith:"GET"` / `verdict:"unprobed"`），404 是必然结果却被读成了"不存在"。
 *      2026-10-02 在真实 serve 上复验后改正；
 *   3. 重放靠 `GET /sessions/:id/stream?since=0` —— 它会从头把整段对话再放一遍，
 *      并且**最后会发 `done`**，所以阅读器可以直接沿用 ChatActivity 那套断流逻辑。
 *
 * 除点按进入会话外，**长按卡片**可以改名 / 归档 / 取消归档 / 删除
 * （动作与路径的映射收在 {@link SessionActions}，其中「删除」= `/permanent` 不是软删）。
 *
 * ⚠ 列表**必须手搓**，不能用 `ListView` / `ArrayAdapter`：
 *   外观是由 {@link Theming#walk} 顺着 View 树贴上去的，而 AdapterView 的条目由 Adapter
 *   在 layout 时才创建，**不在那棵树里** —— 换肤换到深色主题时，条目文字仍是系统默认色
 *   （浅色模式下就是黑的），压在深底上几乎看不见。真机踩过：深海主题下整列会话像没渲染。
 *   本项目另外三个页面都是手搓 LinearLayout，这一页也一样。
 */
public class SessionListActivity extends BaseActivity {

    /** ChatActivity 用来判断"这是历史会话"的 extra。 */
    public static final String EXTRA_SESSION_ID = "session_id";

    private TextView statusLine;
    private LinearLayout listBox;
    private Button archivedToggle;
    private EditText searchBox;
    /** 非 null = 当前显示的是搜索结果而非会话列表。UI 线程写、后台线程读。 */
    private volatile String activeQuery;
    /** 是否显示「已归档」那一段（默认显示 —— 用户要的分类是三段都看得到）。 */
    private volatile boolean showArchived = true;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<SessionList.Item> items = new ArrayList<SessionList.Item>();

    /** 左右滑动切页（与点底栏同一个动作）。 */
    private SwipeNav swipe;

    @Override
    protected void onResume() {
        super.onResume();
        // 从「外观」页返回时本页是复用回来的（不会重走 onCreate）—— 重贴一遍
        Theming.refresh(this);
        // 顺手**静默刷新**一次：这一页的数据会被别处改（对话页删 / 改名 / 归档），
        // 而切页走的是 FLAG_ACTIVITY_REORDER_TO_FRONT（不重走 onCreate）—— onResume 是唯一的时机。
        // 2026-10-06 实测撞上：在别处删完会话切回来，列表还列着**已经删掉的条目**，
        // 要手点页头刷新钮才更新。
        // ⚠️ 用 reload(true) 而不是裸 reload()：后者会立刻清空列表并显示「读取中…」，
        // 每次切页都闪一下；失败时还会把用户正看着的列表顶成错误态。
        reload(true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 只"顺便听一下"手势，返回 super 照常分发 —— 点卡片、滚列表都不受影响
        if (swipe != null) swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 内容列：页面 padding 落在这里、**不落在 root** ——
        // 底栏是加在 root 上的，root 一带 padding，底栏就被一起缩窄（四页会一大一小）。
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        content.setPadding(pad, pad, pad, 0);

        // 页头：图标徽章 + 标题 + 右侧「刷新」。
        // 「刷新」统一放页头右侧 —— 原先三个页面的刷新落在三个不同地方（页头 / 工具行 / 卡片内）。
        View refresh = Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reload();
            }
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(AppKit.pageTitle(this, "会话"), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(refresh);
        header.setPadding(0, 0, 0, Theming.dp(this, Ui.S2));

        statusLine = new TextView(this);
        statusLine.setTextSize(Ui.CAPTION);
        statusLine.setPadding(0, 0, 0, Theming.dp(this, Ui.S3));
        Theming.tag(statusLine, Theming.ROLE_MUTED);

        // 「显示已归档」：归档是软删（从默认列表收起、磁盘保留、可恢复），
        // 要看它们得显式打开开关 —— 这也正是服务端 includeArchived 的语义。
        archivedToggle = new Button(this);
        Theming.tag(archivedToggle, Theming.ROLE_CHIP);
        syncToggle();
        archivedToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showArchived = !showArchived;
                syncToggle();
                reload();
            }
        });

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Theming.dp(this, Ui.INLINE);
        tools.addView(archivedToggle, tlp);

        // 搜索行：会话一多，"翻列表找上次聊过的那句"就成了刚需。
        // 走 `GET /sessions/search?q=`（服务端全量扫会话转录内容，q 至少 2 个字符）。
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchBox = Kit.singleLineField(this, "搜索会话内容（至少 2 个字）");
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                boolean enter = e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
                boolean searchAction = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;
                if (enter || searchAction) {
                    startSearch(searchBox.getText().toString());
                    return true;
                }
                return false;
            }
        });
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button doSearch = new Button(this);
        doSearch.setText("搜索");
        Theming.tag(doSearch, Theming.ROLE_CHIP);
        doSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSearch(searchBox.getText().toString());
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Theming.dp(this, Ui.INLINE);
        searchRow.addView(doSearch, dlp);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(this);
        // 为悬浮底栏让出空间（参考的 NavSpaceForContent）——加在滚动容器上，不是页面 padding
        scroller.setPadding(0, 0, 0, Theming.dp(this, NavBar.SPACE_FOR_CONTENT_DP));
        scroller.setClipToPadding(false);
        scroller.addView(listBox);

        content.addView(header);
        content.addView(statusLine);
        content.addView(tools);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Theming.dp(this, Ui.INLINE);
        content.addView(searchRow, rlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Theming.dp(this, Ui.STACK);   // 按钮行与列表之间留缝
        content.addView(scroller, slp);

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(NavBar.create(this, NavBar.currentFor(this)), NavBar.params(this));

        setContentView(root);
        Theming.apply(this, root);
        // 键盘弹起时窗口一概不动（manifest: adjustNothing）⇒ 挂在 content 上的背景图不动。
        // 本页的搜索框在**页面顶部**（键盘够不着），所以不位移任何视图 ——
        // 只把列表的底部内边距补上，否则最后一条会话会被键盘盖住且滚不上来。
        KeyboardWatcher.attachScrollOnly(this, root, scroller);
        swipe = new SwipeNav(this);

        reload();
    }

    private void syncToggle() {
        archivedToggle.setText(showArchived ? "隐藏已归档" : "显示已归档");
    }

    /**
     * 列表请求的**序号** —— 只认最后发起的那一个。
     *
     * 原先 reload / search 各起线程、各自 `ui.post(render…)`，谁后到谁覆盖：
     * 网速慢时先点搜索再点刷新，用户看到的可能是搜索之前那次的结果（2026-10-04 深查发现）。
     */
    private final java.util.concurrent.atomic.AtomicInteger reqGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 前台刷新：清空列表 + 显示「读取中…」—— 用户主动点刷新、或首次进来时用。 */
    private void reload() {
        reload(false);
    }

    /**
     * @param silent true = **静默刷新**（切页回来时用）：不动已有列表、失败也不顶成错误态。
     *               理由见 {@link #onResume()} —— 切页是高频动作，每次都闪一下很吵；
     *               而把用户正看着的列表换成「读取失败」比不刷新更糟。
     */
    private void reload(final boolean silent) {
        if (!AppRuntime.isServeReady()) {
            status("serve 未就绪 —— 先回上一页跑完启动流程");
            return;
        }
        activeQuery = null;
        final int gen = reqGen.incrementAndGet();     // 本次请求的序号
        if (silent) {
            status("刷新中…");                        // 只换状态行，列表先留着
        } else {
            status("读取中…");
            listBox.removeAllViews();
            listBox.addView(AppKit.loading(this, "读取会话…"));
            Theming.applyTree(this, listBox);
            items.clear();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                load(gen, silent);
            }
        }, "tianshu-sessions");
        t.setDaemon(true);
        t.start();
    }

    private void load(final int gen, final boolean silent) {
        try {
            // 一律带上归档 —— 分段（会话中/未归档/已归档）需要它们都在手上；
            // 「显示已归档」开关只决定渲染时那一段要不要画出来。
            String path = "/sessions?includeArchived=true";
            HttpURLConnection c = (HttpURLConnection)
                    new URL(AppRuntime.baseUrl() + path).openConnection();
            try {
                c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                String body = ChatActivity.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code != 200) {
                    throw new IllegalStateException("GET /sessions -> HTTP " + code + " " + head(body, 200));
                }
                final List<SessionList.Item> got = SessionList.parse(body);
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != reqGen.get()) return;   // 慢响应迟到：别覆盖更新的那一份
                        render(got);
                    }
                });
            } finally {
                c.disconnect();
            }
        } catch (final Throwable e) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (gen != reqGen.get()) return;
                    if (silent) {
                        // 静默刷新失败：**列表还是上次的**，只把状态行说清楚 ——
                        // 不把用户正看着的内容顶成错误态（要退化成错误态，让他自己点刷新）
                        status("刷新失败 —— 列表还是上次的");
                        return;
                    }
                    status("读取失败");
                    listBox.removeAllViews();
                    listBox.addView(AppKit.error(SessionListActivity.this, "读取会话列表失败",
                            String.valueOf(e) + "\n检查一下 serve 是不是还在跑。",
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    reload();
                                }
                            }));
                    Theming.applyTree(SessionListActivity.this, listBox);
                }
            });
        }
    }

    private void render(final List<SessionList.Item> got) {
        listBox.removeAllViews();
        items.clear();
        items.addAll(got);

        long now = System.currentTimeMillis();

        // 分组：会话中 / 未归档 / 已归档（用户要的分类）。
        // 用 LinkedHashMap 保证按 SECTION_ORDER 的次序出段，空段直接跳过。
        java.util.Map<String, List<SessionList.Item>> bySec =
                new java.util.LinkedHashMap<String, List<SessionList.Item>>();
        for (String sec : SessionList.SECTION_ORDER) {
            bySec.put(sec, new ArrayList<SessionList.Item>());
        }
        for (SessionList.Item it : got) {
            bySec.get(SessionList.sectionOf(it)).add(it);
        }

        int shown = 0;
        for (String sec : SessionList.SECTION_ORDER) {
            List<SessionList.Item> in = bySec.get(sec);
            // 开关只影响「已归档」这一段；另外两段永远在
            if (SessionList.SEC_ARCHIVED.equals(sec) && !showArchived) continue;
            if (in.isEmpty()) continue;
            // **一段 = 一张卡**（`Kit.Group`：小标题 + 卡片 + 行间缩进分隔线）。
            // 原先每条会话各占一张卡、彼此隔 14dp —— 一屏只放得下五六条；
            // 现在同段的行共享一张卡、靠分隔线断行：密度翻倍，也更像"列表"而不是"卡片堆"。
            Kit.Group g = Kit.group(this, sec + "  " + in.size());
            for (SessionList.Item it : in) {
                g.row(itemCard(it, now), 16);      // 分隔线缩进 16dp：与行内文字左对齐
                shown++;
            }
            listBox.addView(g.root);
        }
        // 条目是刚建的，必须**再走一遍**角色贴色 —— apply() 那次跑的时候这些还不存在。
        Theming.applyTree(this, listBox);

        if (got.isEmpty()) {
            // 用法说明只在"还没有内容"时讲一次；有内容以后常驻讲同一句话是噪音
            status("还没有历史对话 —— 去「对话」说一句就有了");
        } else {
            status("共 " + shown + " 条"
                    + (showArchived ? "" : "（已归档已隐藏）"));
        }
    }

    /**
     * 一条会话 = **分组里的一行**（不是一张卡）。构建收在 {@link SessionCard} ——
     * **与对话页侧栏共用同一份**（原先两处各写一份：标题字号、元信息拼法、有没有按钮全不一样，
     * 用户反馈「界面有些重复的地方，很突兀」）。
     */
    private View itemCard(final SessionList.Item it, long now) {
        return SessionCard.build(this, it, now, true, cardCb());
    }

    /** 卡片上的动作：点开进会话、一键归档/删除、长按出管理面板（与会话页共用同一套实现）。 */
    private SessionCard.Cb cardCb() {
        return new SessionCard.Cb() {
            @Override
            public void open(SessionList.Item it) {
                Intent i = new Intent(SessionListActivity.this, ChatActivity.class);
                i.putExtra(EXTRA_SESSION_ID, it.id);
                startActivity(i);
            }

            @Override
            public void archive(SessionList.Item it) {
                if (it.archived) SessionActionMenu.unarchive(SessionListActivity.this, it, doneCb());
                else SessionActionMenu.archive(SessionListActivity.this, it, doneCb());
            }

            @Override
            public void delete(SessionList.Item it) {
                SessionActionMenu.delete(SessionListActivity.this, it, doneCb());
            }

            @Override
            public void longPress(SessionList.Item it) {
                openActions(it);
            }
        };
    }
    // ---------------------------------------------------------------- 搜索

    /**
     * 执行搜索。少于 2 个字符 = 回到普通列表（服务端会 400，本地先拦，省一次往返）。
     */
    private void startSearch(String q) {
        if (SessionSearch.tooShort(q)) {
            status("搜索至少 2 个字 —— 已回到会话列表");
            reload();
            return;
        }
        if (!AppRuntime.isServeReady()) {
            status("serve 未就绪 —— 先回上一页跑完启动流程");
            return;
        }
        activeQuery = q.trim();
        status("搜索中…");
        listBox.removeAllViews();
        listBox.addView(AppKit.loading(this, "搜索中…"));
        Theming.applyTree(this, listBox);
        final int gen = reqGen.incrementAndGet();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runSearch(gen);
            }
        }, "tianshu-search");
        t.setDaemon(true);
        t.start();
    }

    private void runSearch(final int gen) {
        final String q = activeQuery;
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(AppRuntime.baseUrl() + SessionSearch.pathFor(q)).openConnection();
            try {
                c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);                   // 全量扫转录，比列列表慢
                int code = c.getResponseCode();
                String body = ChatActivity.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code != 200) {
                    throw new IllegalStateException("GET /sessions/search -> HTTP " + code + " " + head(body, 200));
                }
                final SessionSearch.Result r = SessionSearch.parse(body);
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != reqGen.get()) return;   // 慢响应迟到：别覆盖更新的那一份
                        renderSearch(q, r);
                    }
                });
            } finally {
                c.disconnect();
            }
        } catch (final Throwable e) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (gen != reqGen.get()) return;
                    status("搜索失败");
                    listBox.removeAllViews();
                    listBox.addView(AppKit.error(SessionListActivity.this, "搜索失败",
                            String.valueOf(e) + "\n服务端要全量扫转录，可能只是慢，重试一次看看。",
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    startSearch(searchBox.getText().toString());
                                }
                            }));
                    Theming.applyTree(SessionListActivity.this, listBox);
                }
            });
        }
    }

    private void renderSearch(String q, SessionSearch.Result r) {
        listBox.removeAllViews();
        for (SessionSearch.Hit h : r.hits) {
            listBox.addView(hitCard(h));
        }
        Theming.applyTree(this, listBox);                  // 卡片是刚建的，要再走一遍角色贴色
        status("「" + q + "」" + SessionSearch.summary(r));
    }

    /** 一条命中 = 一张卡一行（{@link Kit#cardRow}）：会话标题 +「角色 · 片段」；点进去就是那条会话。 */
    private View hitCard(final SessionSearch.Hit h) {
        String t = h.title;
        if (t == null || t.length() == 0) {
            t = h.sessionId == null ? "（未知会话）"
                    : h.sessionId.substring(0, Math.min(8, h.sessionId.length()));
        }
        String meta = SessionSearch.roleLabel(h.role)
                + (h.snippet != null && h.snippet.length() > 0 ? " · " + h.snippet : "");
        return Kit.cardRow(this, t, meta, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (h.sessionId == null) return;
                Intent i = new Intent(SessionListActivity.this, ChatActivity.class);
                i.putExtra(EXTRA_SESSION_ID, h.sessionId);
                startActivity(i);
            }
        });
    }

    // ---------------------------------------------------------------- 会话管理（长按）

    /**
     * 长按一张卡片 → 操作面板（改名 / 归档 / 取消归档 / 删除）。
     * 动作与路径的映射收在 {@link SessionActionMenu} / {@link SessionActions}，两个界面共用一份。
     */
    private void openActions(final SessionList.Item it) {
        SessionActionMenu.open(this, it, doneCb());
    }

    /** 会话动作的回执：把服务端那句人话显示出来，并重拉列表（结果确实变了）。 */
    private SessionActionMenu.OnDone doneCb() {
        return new SessionActionMenu.OnDone() {
            @Override
            public void done(String message) {
                status(message);
                reload();                    // 归档/删除之后列表确实变了，无条件重拉一次最省心
            }
        };
    }

    private void status(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusLine.setText(s);
            }
        });
    }

    private static String head(String s, int n) {
        if (s == null) return "(null)";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
