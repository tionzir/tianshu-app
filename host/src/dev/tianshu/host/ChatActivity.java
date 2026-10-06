package dev.tianshu.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话详情页 —— 承载**硬不变量 A：答案永远在本轮最底**。
 *
 * 链路：
 *   1. POST /sessions {"prompt":…}  → 201 + id（**响应里没有回答**）
 *   2. GET  /sessions/:id/stream    → SSE，事件喂给 {@link Transcript} 分桶后渲染
 *
 * 与旧版的区别（这是本页存在的全部意义）：
 *   旧版把事件平铺成一大坨日志 —— 中间调了一堆工具，回答被埋在最后，用户得往上找。
 *   新版按不变量 A 分块：**过程（思考/工具/相位）折进活动区，答案永远追加在这轮最底**。
 *
 * 另外两条必须做对的：
 *   - 网络不能在主线程（NetworkOnMainThreadException）
 *   - SSE 是长连接，服务端发完 `done` 不关连接 → 靠 {@link ConversationState#isFinished()} 主动断流
 */
public class ChatActivity extends BaseActivity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_CHILD_CHARS = 4000;      // 单个子项最长显示多少字

    // 输入区面板的圆角(36)与不透明度(0.62)已收进 Theming —— 它是"主题材料"的一部分，
    // 必须跟角色一起在 refresh() 里重刷（Theming.PANEL_RADIUS_INPUT_DP / PANEL_ALPHA_INPUT）。
    /**
     * 内容区为**悬浮**底栏让出的空间（参考的 NavSpaceForContent = 92dp）。
     *
     * ⚠️ 它必须加在**滚动内容的末尾 padding**上，不是内容区的整体 padding ——
     * 后者会让内容"在胶囊上方就结束"，胶囊底下露出一块背景色：既难看，
     * 半透明面板也没东西可透（参考里专门写了这条）。
     */
    private static final int NAV_SPACE_DP = NavBar.SPACE_FOR_CONTENT_DP;

    private TextView statusLine;
    private LinearLayout content;
    /** 空态：没有任何轮次与提示时显示 —— 不是"一句话"，而是**三个能点的下一步**。 */
    private View emptyBox;
    private ScrollView scroller;
    private EditText input;
    private View sendButton;
    private Button jumpButton;

    // ---- 页头标题（随用户第一句话更新，用户 2026-10-04）----
    /** 页头那个标题 TextView —— onCreate 里建好，之后由消息/会话改写。 */
    private TextView pageTitle;
    /** 标题是否已被"第一句 / 已有会话"定过（定过就不再被后续消息覆盖）。 */
    private boolean titleCustomized;
    /** 最近一次拉到的会话列表 —— 切进历史会话时用它找到那条的标题填页头。 */
    private java.util.List<SessionList.Item> lastSessions;

    // ---- 侧拉菜单（历史会话）----
    private SidePanel sidePanel;
    private LinearLayout sessionBox;

    // ---- 命令抽屉 / 输入建议 ----
    private View cmdToggle;
    private ScrollView cmdScroll;
    private LinearLayout cmdList;
    /** 那块区域现在是谁在用：没人 / 手动展开的完整目录 / 输入 "/" 弹出的建议。 */
    private int cmdMode = CMD_NONE;
    private CommandCatalog.Catalog cmdCatalog;
    /** 刚点了建议，别让随后的 setText 又把它弹回来。 */
    private boolean muteSuggest;

    private static final int CMD_NONE = 0;
    private static final int CMD_DRAWER = 1;
    private static final int CMD_SUGGEST = 2;

    /** 当前页面的全部事件（跨多轮累积）。后台线程写、UI 线程读，按对象锁同步。 */
    private final List<Transcript.Event> events = new ArrayList<Transcript.Event>();
    /** 非会话正文的系统提示行（连接信息、错误等）。 */
    private final List<String> notices = new ArrayList<String>();
    /** 用户手动展开过的活动区（按轮次下标）。默认折叠。 */
    private final Set<Integer> expandedTurns = new HashSet<Integer>();

    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * 当前**持有 busy 闸的那个会话世代**（见 {@link #sessionGen}）。
     *
     * 为什么需要：`busy` 原本是个裸布尔 —— 谁都能放。切会话后旧流迟到一步，它的 `finally`
     * 会把**新会话**正在持有的闸清掉，于是可以再发一条 → 两条发送线程并发、重复 POST、
     * 两段流灌进同一页。把"闸属于哪个世代"记下来、释放时核对，就不会误放别人。
     * `Integer.MIN_VALUE` = 没人持有。
     */
    private final AtomicInteger busyGen = new AtomicInteger(Integer.MIN_VALUE);

    /**
     * SSE 流的**空闲上限**（毫秒）—— 超过这么久一个字节都没来，就认为这条流僵住了。
     *
     * 服务端带 `: ping` 心跳，正常思考期间也不会静默这么久；一旦静默就是"既不发 done、
     * 也不发心跳"。原先 `setReadTimeout(0)`（永不超时）会让 `readLine` 永久阻塞 →
     * `stream()` 不返回 → `busy` 永不释放 → 用户第二句话都发不出去（2026-10-04 深查）。
     * 写成常量、不内联：真机上要调就调这一处。
     */
    private static final int STREAM_IDLE_TIMEOUT_MS = 180_000;
    private boolean atBottom = true;
    private long lastPublish;

    /** 左右滑动切页（与点底栏同一个动作）。 */
    private SwipeNav swipe;

    // ---- 增量渲染的账本 ----
    /** 系统提示那几行的容器（与轮次分开：改一个不必动另一个）。 */
    private LinearLayout notesBox;
    /** 「它在等你批准」那一条 —— 只在待审批 > 0 时可见（平时 GONE，不占地方）。 */
    private TextView approvalBar;
    /** 对话轮次的容器 —— 增量的主战场。 */
    private LinearLayout turnsBox;
    /** 已经画好的每一轮的视图（下标 = 轮次）。只重画"还在长"的最后一轮，别整页重来。 */
    private final List<List<View>> renderedTurns = new ArrayList<List<View>>();
    /** 已经画好的系统提示条数（提示只增不减，条数没变就整块跳过）。 */
    private int renderedNotes = -1;

    /** 非空 = 这是从历史列表点进来的既有会话（可重放、可续聊），不是新会话。 */
    private String historySessionId;

    // ---- 输入框上方的常驻状态行（对齐 Termux TUI 的一行状态）----
    /**
     * 状态 chip 那一行。**常驻**：没有数据时给占位 chip，不整行消失
     * （用户反馈：新对话的时候也要在，不能进行对话的时候才出来）。自动换行，不用横滑。
     */
    private LinearLayout chipsRow;

    /**
     * 当前页面所属的会话 id（新会话建好后回填）。
     *
     * 为什么要单独存一份：顶栏要按 id 从 `GET /sessions` 里精确挑出**当前这条**，
     * 才能显示它的模型 / 档位 / 上下文。新会话的 id 是 send() 的后台线程里拿到的，
     * 所以这个字段由后台线程写、UI 线程读 → volatile。
     */
    private volatile String liveSessionId;

    // ---- 状态栏 chip 可点（真机反馈 #15：模型/缓存/峰闲/上下文/审批/星域 都该点得动）----
    // 后台线程在 refreshStatusBar 里写、UI 线程在点击时读 → volatile。
    private volatile List<Providers.Provider> lastProviders;
    private volatile String lastModelId = "";
    private volatile int lastCtxTokens;
    private volatile int lastCtxWindow;
    // 弹层标「（当前）」用 —— 与状态行**同源**（都在 refreshStatusBar 里赋值），
    // 所以切换成功后 refreshStatusBar() 一跑，下次打开弹层标的就对。
    // 模型弹层早就这么标了，审批模式 / 星域两处漏了，用户看不出自己当前选的是哪个。
    private volatile String lastApprovalMode = "";
    private volatile String lastDomainId = "";

    /**
     * 本轮开始时刻（毫秒）；0 = 还没开始过。
     *
     * 状态栏那个"用时"取它 —— TUI 的 `0s` 是**本轮已用时**，不是会话总时长。
     * 跑完保留最后的值，chip 就显示"最近一轮用了多久"。
     */
    private volatile long turnStartAt;

    /**
     * 会话世代。**切换会话（开新会话 / 打开一条历史会话）时 +1** ——
     * 还在跑的旧流每次回调都拿自己的世代号对一下，对不上就自行退场。
     *
     * 没有它会发生什么：在 A 会话还在跑的时候切到 B，A 的事件会继续灌进 B 的页面 ——
     * 两段对话混在一起，而且 busy 闸也被 A 占着，B 的第一句话发不出去。
     */
    private final AtomicInteger sessionGen = new AtomicInteger(0);

    /** 计时 chip 的视图 —— 每秒就地改文本，不重建整行、也不发网络请求。 */
    private TextView elapsedChipView;

    /** 每秒 tick 一次，让「用时」自己走字（用户反馈：那个时间要实时更新）。 */
    private final Handler ticker = new Handler(Looper.getMainLooper());

    /**
     * 本页已吃到的最大事件序号。续聊时拿它当 `since`，只取新轮。
     *
     * 为什么不能用 0：服务端会从序号 1 把整段历史重放一遍，而这段历史**本页已经有了**，
     * 叠加后按 `turn_complete` 分轮就会翻倍。后台线程写、后台线程读，故 volatile。
     */
    private volatile int loadedSeq;

    /** 从命令面板长按进来时，预填到输入框的命令文本。 */
    public static final String EXTRA_PREFILL = "prefill";

    @Override
    protected void onResume() {
        super.onResume();
        // 从「外观」页返回时本页是复用回来的 —— 背景重贴一遍，内容区的角色由
        // renderConversation() 里的 applyTree 负责。
        Theming.refresh(this);
        // 顶栏状态也跟着回来刷新一次（切去设置改完档位/权限，回来就该看到新值）
        refreshStatusBar();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 只"顺便听一下"手势，返回 super 照常分发 —— 点按钮、滚列表都不受影响。
        // 侧拉菜单开着时不切页：那会儿的横向滑动是在跟面板较劲。
        if (swipe != null && (sidePanel == null || !sidePanel.isOpen())) swipe.feed(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 主体放在 FrameLayout 里 —— 侧拉菜单要叠在它上面。底栏留在它外面，永远贴底。
        FrameLayout host = new FrameLayout(this);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        // 页边距走 Ui.S6，与「会话/设置/外观」三页一致 —— 这页原先写死 14dp，
        // 四页里只有它窄一截，加左右滑动之后一眼就看得出内容宽度不齐。
        int pad = Theming.dp(this, Ui.S6);
        // 与另三页同一套页边距：左右 S6、顶 S6、底 0（底栏自带间距）
        main.setPadding(pad, Theming.dp(this, Ui.S6), pad, 0);

        // ---- 页头：[☰] 对话 …… [+] ----
        // 两个动作从**带文字的系统按钮**（"＋ 新会话" / "☰ 会话"）收成**圆钮**：
        // ① 标题两侧各一个圆钮，是一行里最自然的排法；
        // ② 两个文字按钮会把标题挤到左边、整行看着像一排工具条 —— 而这是"页头"，不是工具栏。
        // 另外把"打开会话侧栏"从右侧挪到**左侧**：抽屉就该在左边，与手势方向也一致。
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        topBar.addView(Kit.roundButton(this, Icon.MENU, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sidePanel == null) return;
                sidePanel.toggle();
                // **每次打开都重拉**。原先只在第一次打开时拉一次（sessionsLoaded 一次性门），
                // 于是「在会话页里删掉的会话，回到对话页侧栏还挂在列表上」—— 用户反馈的正是这条。
                if (sidePanel.isOpen()) loadSessions();
            }
        }));

        // 标题改走**共享构件** —— 此前本页是全工程唯一自建标题的页面（见审计 §3.2），
        // 自建版本没跟上页头的固定行高，这一页标题基线比别页低 6px。
        pageTitle = (TextView) AppKit.pageTitle(this, ChatTitle.DEFAULT);
        // 宽度驱动省略：标题占满余量、超长才 ellipsize（不再按 char 截 —— 见审计 §2.4）。
        // 原来用「标题 WRAP_CONTENT + 一根 weight=1 撑杆」，于是长标题会被自己的字数上限截掉，
        // 而不是按**真正能放多少**截。
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 高度**显式**给满 TOUCH_MIN：`setSingleLine` 之后 TextView 的 minHeight 不再参与测量
        // （实测标题只有 90px 高 → 在 48dp 行里垂直居中后顶边落 205px，比别页低 28px）。
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(
                0, Theming.dp(this, Ui.TOUCH_MIN), 1f);
        ptlp.leftMargin = Theming.dp(this, Ui.S3);
        topBar.addView(pageTitle, ptlp);

        // 「新会话」：用户真机反馈「新会话怎么开」—— 从前只能敲 /new，页面上没有入口。
        // 走的是和 /new 完全相同的一条路：开一个新的 ChatActivity（不带 session id）。
        topBar.addView(Kit.roundButton(this, Icon.PLUS, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSessionInPlace();
            }
        }));

        main.addView(topBar);

        // 状态行：原先是顶栏里的一列，现在挪到标题下当这一页的副标题（与另三页一致）
        statusLine = new TextView(this);
        statusLine.setTextSize(Ui.CAPTION);
        statusLine.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S2));
        Theming.tag(statusLine, Theming.ROLE_MUTED);
        main.addView(statusLine);

        // ---- 常驻状态行：模型 · 缓存命中 · 峰/闲 · 上下文 · 用时 · 审批 · 星域 ----
        //
        // **这一排已经改了四轮，四条约束是硬冲突的，别再重来一遍**（每一步都被用户否过）：
        //   ① 自动换行 → 「我说过只要一行，现在是两行」；
        //   ② 压内边距 + 缩字号 + 截模型名 → 「又太挤了，保留 deepseek-v4-flash，不要删减」；
        //   ③ 横滑 → 「不要让我左右滑动才能看，就一行完整」。
        //   → 只剩一条路：**在固定一行里靠"少占宽度"和"自适应字号"把它塞下**：
        //      · chip 不再画小图标（6 枚图标 + 间距 ≈ 102dp，是唯一能整块让出来的空间）；
        //      · 字号由 {@link #fitChipSize} 按**真实字体度量**逐档试算（上限 Ui.LABEL，下限 9sp）。
        //
        // ⚠️ **位置改过两次，别再挪**：原本在输入框上方 → 我一度按自己的判断挪到页头下方 →
        //   用户明确要求挪回**输入框上方**（原话「放到对话框的上面一点点」）—— **以用户为准**
        //   挂载点在下面「输入区」之前，这里只创建。
        //
        // ⚠️ 参考 App 是把这些数字收进弹窗的（它的原话："每轮都变的数字常驻顶栏 = 在她名字旁边
        //    挂一块仪表盘"），但用户明确要过常驻 —— 那是**用户的另一个选择**，别再提这件事。
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);

        // content 分两层：系统提示条 / 对话轮次。分开是为了**增量**渲染 ——
        // 流式时只有轮次区在变，提示区不用跟着重建。
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        notesBox = new LinearLayout(this);
        notesBox.setOrientation(LinearLayout.VERTICAL);
        turnsBox = new LinearLayout(this);
        turnsBox.setOrientation(LinearLayout.VERTICAL);
        // 待审批提示条放在最上面 —— 它是"整件事卡住了"，比什么都该先看见。
        // 平时 GONE，不占地方（2026-10-05：App 里没有批准入口，得把话说清楚）。
        approvalBar = new TextView(this);
        approvalBar.setTextSize(Ui.LABEL);
        approvalBar.setLineSpacing(0f, 1.3f);
        int abPad = Theming.dp(this, Ui.S3);
        approvalBar.setPadding(abPad, abPad, abPad, abPad);
        Theming.tag(approvalBar, Theming.ROLE_WARN);
        approvalBar.setVisibility(View.GONE);
        // 点一下直接进「会话详情」去批准 —— 从前这条只说"卡住了"，**没有任何批准入口**
        //（此前已知缺口）。2026-10-06 补上：详情页有批准 / 驳回。
        approvalBar.setClickable(true);
        approvalBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (liveSessionId == null || liveSessionId.isEmpty()) return;
                Intent i = new Intent(ChatActivity.this, SessionDetailActivity.class);
                i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, liveSessionId);
                startActivity(i);
            }
        });
        content.addView(approvalBar);
        content.addView(turnsBox);
        // 提示条放在**轮次之后**（而不是之前）—— 2026-10-06 修的一个真机复现缺陷：
        // 用户在底部输入框敲命令（/todo /debug /metrics…），结果却渲染在滚动区最顶上，
        // 视线在底部、结果在顶部 ⇒ 看上去"命令敲了没反应"（要手动滑到顶才看得见）。
        // 挪到轮次之后：它在底部视口里，且 `if (atBottom) scrollToBottom()` 会把它带进视野。
        // 会话级提示（"这是之前的一条对话 —— 往下可以接着聊" / "更早的记录不在回放窗口里"）
        // 也落在这里 —— 它紧贴输入框上方，"往下"指的就是下面那条 composer，语义仍成立。
        content.addView(notesBox);

        // 空态：原先是一片空白 + 一句话。**空态唯一的功能就是告诉用户能做什么** ——
        // 所以它现在是一个大标题 + 一张「从这里开始」的卡（三个入口，点了就替用户做那件事）。
        // 新用户第一眼看到的东西，从"一句灰字"变成了"三条路"。
        emptyBox = buildEmptyState();
        content.addView(emptyBox);

        scroller = new ScrollView(this);
        // 为**悬浮**底栏让出空间（参考的 NavSpaceForContent = 92dp）。
        // 加在滚动容器上 = 内容末尾留白：内容能滚到胶囊下面去，半透明底才有东西可透；
        // 加在页面整体 padding 上则是"截断"——内容在胶囊上方就结束，底下露一块背景色。
        scroller.setPadding(0, 0, 0, Theming.dp(this, NAV_SPACE_DP));
        scroller.setClipToPadding(false);
        scroller.addView(content);
        scroller.getViewTreeObserver().addOnScrollChangedListener(
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        updateAtBottom();
                    }
                });
        main.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        jumpButton = new Button(this);
        jumpButton.setText("↓ 回到最新");
        // 小胶囊，不占整宽 —— 占满整行时它把对话区压得很闷（用户真机反馈「未免太大了吧」）
        jumpButton.setTextSize(Ui.CAPTION);
        Theming.tag(jumpButton, Theming.ROLE_CHIP);
        jumpButton.setVisibility(View.GONE);
        jumpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                atBottom = true;
                scrollToBottom();
            }
        });
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        jlp.gravity = Gravity.CENTER_HORIZONTAL;
        jlp.topMargin = Theming.dp(this, Ui.S1);
        main.addView(jumpButton, jlp);

        // ---- 命令抽屉的开关：**收进输入行左侧**（[⌘] [输入框] [↑]）----
        // 原先它是输入框上方独立的一行按钮（"⌘ 命令"），占一整行、把输入区顶下去；
        // 收进输入行之后，输入区从"一行按钮 + 一个面板"变成"一个面板"。
        // ⚠️ 圆钮没有文字，所以**展开态用主色底表达**（见 setCommandToggle）。
        cmdToggle = Kit.roundButton(this, Icon.COMMAND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCommands();
            }
        });

        cmdList = new LinearLayout(this);
        cmdList.setOrientation(LinearLayout.VERTICAL);
        cmdScroll = new ScrollView(this);
        cmdScroll.addView(cmdList);
        cmdScroll.setVisibility(View.GONE);
        // 高度**按屏高算**，不写死：原先固定 210dp —— 小屏上能占掉半个屏幕、大屏上又显得空。
        // 0.34 是"够看十来条命令、又不把输入区顶出视野"的比例；320dp 是上限。
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.34),
                        Theming.dp(this, 320)));
        cslp.topMargin = Theming.dp(this, Ui.S1);
        main.addView(cmdScroll, cslp);

        // ---- 输入行 ----
        // 输入框走控件层（Kit.field）：圆角 / 底色 / 描边由 Theming 按 ROLE_FIELD 统一给，
        // 这里只管结构与内边距 —— 于是它和搜索框、设置页那些输入框是**同一份外观**。
        input = Kit.field(this, "跟天枢说点什么…");
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 打出 "/" 就弹命令、边打边筛（见 onInputChanged）
                onInputChanged(s == null ? "" : s.toString());
            }
        });

        // 发送键 = **圆形主色钮 + ↑**（参考的 RoundIconButton：40dp 圆底、靠底色差成形）。
        // 原先是一个写着"发送"的系统 Button —— 又宽又像表单提交，不像聊天。
        // ↑ 的字形覆盖实测 34 个字体（新符号必须核验）。
        sendButton = Kit.roundButton(this, Icon.SEND, true, null);

        // 输入区 = **半透明面板**（照参考 App 的 InputPanelCorner = 36 / PANEL_ALPHA = 0.62）。
        // 与底栏**同一材质、厚度不同**（底栏 28 / 0.80）——参考里这是刻意的差异化：
        // 输入区要透出滚动中的对话，底栏常驻在不变的背景上所以更实。
        // 底色取当前主题的卡片色而不是纯白：暗夜/深海主题下白底会瞎。
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        {
            // 面板底走**角色**（不在这里现算）—— 换主题时 Theming.refresh() 会按新 tokens 重建它。
            // （原先这里烘焙 `tokens().card`：换主题后输入区停在旧色叠到新背景上，
            //   用户 2026-10-06 报的「对话框颜色没改」就是这条。）
            Theming.tag(bottom, Theming.ROLE_PANEL_INPUT);
            int hp = Theming.dp(this, Ui.S3);
            int vp = Theming.dp(this, Ui.S2);
            bottom.setPadding(hp, vp, hp, vp);
        }
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cmdLp.rightMargin = Theming.dp(this, Ui.S2);
        cmdLp.gravity = Gravity.CENTER_VERTICAL;
        bottom.addView(cmdToggle, cmdLp);

        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bottom.addView(input, grow);
        // 输入框与发送键之间留间距 —— 原先两个控件是贴着的，一眼就"挤"
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = Theming.dp(this, Ui.INLINE);
        bottom.addView(sendButton, sendLp);

        // ---- 「当前会话用时」居中卡片：在状态行**上方**（用户 2026-10-04 要求）----
        // 计时那枚从状态行里拿了出来，单独做一张居中的小卡 —— 它每秒都在变，
        // 独立摆出来更醒目，也让状态行只放"稳态"信息。
        LinearLayout elapsedRow = new LinearLayout(this);
        elapsedRow.setGravity(Gravity.CENTER_HORIZONTAL);
        {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int p = Theming.dp(this, Ui.S2);
            card.setPadding(p, Theming.dp(this, Ui.S1), p, Theming.dp(this, Ui.S1));
            Theming.tag(card, Theming.ROLE_CARD);

            Icon pulse = new Icon(this, Icon.PULSE).size(13);
            Theming.tag(pulse, Theming.ROLE_MUTED);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ilp.rightMargin = Theming.dp(this, Ui.S1);
            card.addView(pulse, ilp);

            elapsedChipView = new TextView(this);
            elapsedChipView.setTextSize(Ui.CAPTION);
            Theming.tag(elapsedChipView, Theming.ROLE_MUTED);
            elapsedChipView.setText("当前会话用时：0s");
            card.addView(elapsedChipView);

            elapsedRow.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        erLp.bottomMargin = Theming.dp(this, Ui.S1);
        main.addView(elapsedRow, erLp);

        // ---- 状态 chips：**贴在输入区正上方**（用户 2026-10-04：「放到对话框的上面一点点」）----
        // 六枚恒常驻（计时那枚已挪到上面的卡片里，见 renderChips）；间距收得很紧 ——
        // 用户这一轮又说「再稍微向下靠近对话框」，于是把与输入面板之间的缝收到最小。
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = Theming.dp(this, Ui.S2);
        chipLp.bottomMargin = 0;
        main.addView(chipsRow, chipLp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Theming.dp(this, Ui.S1);   // 紧贴状态行（原来是 S2，用户要求"再靠近一点"）
        main.addView(bottom, blp);

        host.addView(main, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        sidePanel = SidePanel.attach(this, host, panelHeader(), panelBody());

        root.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(NavBar.create(this, NavBar.currentFor(this)), NavBar.params(this));

        setContentView(root);
        Theming.apply(this, root);
        // 键盘弹出时**只抬输入行**（用户 2026-10-06：「对话框要和键盘抬起来，但背景图片不要抬起来」）。
        // manifest 里这一页已是 `adjustNothing` —— 窗口不缩不位移，挂在 android.R.id.content 上的
        // 背景图因此**结构性不动**；输入框要抬多少由 KeyboardLift 算（取舍见 KeyboardWatcher 注释）。
        KeyboardWatcher.attach(this, root, scroller, bottom);
        swipe = new SwipeNav(this);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });

        historySessionId = getIntent().getStringExtra(SessionListActivity.EXTRA_SESSION_ID);
        liveSessionId = historySessionId;      // 历史会话：顶栏直接按这个 id 取状态

        if (historySessionId != null) {
            // 历史会话：**可读可写**。
            //
            // 这里曾经禁用输入框，理由写在注释里是「Runtime 没有续聊接口
            // （POST /sessions/:id/prompt 等全是 404）」。那个结论是错的 ——
            // 旧探针用 GET 去打了 POST 路由（routes-probe.json 里这条记着
            // probedWith:"GET" / verdict:"unprobed"），404 是必然结果，却被
            // 下游读成了"路由不存在"。2026-10-02 在真实 serve 上复验：
            // POST /sessions/:id/prompt 返 200（成功）或 409（会话正在跑）。
            input.setHint("继续这个话题…");
            notice("这是之前的一条对话 —— 往下可以接着聊");
            status("载入中…");
            renderConversation();
            final int myGen = sessionGen.get();     // 这一份历史属于哪个世代
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 首次加载：从 0 重放整段历史，末尾把水位记下来，续聊据此走增量
                        int seq = stream(historySessionId, 0);
                        // 世代对不上（用户已经切走了）就**不要**写水位 —— 写下去会把新会话的
                        // 续聊起点污染成旧会话的。2026-10-05 走查核出：这里与 openSessionInPlace
                        // 不对称，那边逐处都套了 gen 检查，这里一处都没有。
                        if (myGen == sessionGen.get()) loadedSeq = seq;
                    } catch (Throwable e) {
                        if (myGen == sessionGen.get()) {
                            notice("!! 重放出错：" + e);
                            status("重放出错：" + e.getClass().getSimpleName());
                        }
                    } finally {
                        if (myGen == sessionGen.get()) renderConversation();
                    }
                }
            }, "tianshu-replay");
            t.setDaemon(true);
            t.start();
        } else if (!AppRuntime.isServeReady()) {
            notice("运行环境还没准备好 —— 先回上一页等启动流程跑完");
            status("还没就绪");
            sendButton.setEnabled(false);
        } else {
            // 这里原先贴的是「已连上 http://127.0.0.1:18799（token 走 loopback，不出本机）」——
            // 那是调试信息，不该占着首页正文第一行。连接详情在「设置」页看。
            status("就绪");
        }

        // 从命令面板长按进来：把命令预填进输入框，用户补完参数按发送即可。
        if (historySessionId == null) {
            String prefill = getIntent().getStringExtra(EXTRA_PREFILL);
            if (prefill != null && prefill.length() > 0) {
                input.setText(prefill);
                input.setSelection(prefill.length());
                status("已带入命令 —— 补完参数后按发送");
            }
        }
        renderConversation();
        refreshStatusBar();          // 首屏就把顶栏状态拉起来
        startTicker();               // 「用时」那枚 chip 每秒自己走字
    }

    /**
     * 每秒把「用时」那枚 chip **就地**更新一次（用户反馈：那个时间要实时更新）。
     *
     * 只改一个 TextView 的文本，不重拉 chips —— 状态栏那一整套要打 5 个 HTTP 请求，
     * 每秒来一遍既浪费、又会让整行 chip 闪。
     */
    private void startTicker() {
        ticker.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!alive()) return;          // 页面没了 / 正在销毁，就不再排下一次
                if (turnStartAt != 0) {
                    String el = StatusBar.elapsedText(System.currentTimeMillis() - turnStartAt);
                    if (el.length() > 0) setElapsedText(el);
                }
                ticker.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     * 页面销毁时把 ticker 摘掉。
     *
     * 为什么必须显式摘：{@code ticker} 是 Handler，消息队列里排着的那个 Runnable 持有本
     * Activity —— 不摘就是**真泄漏**。而且旧写法用 {@code isFinishing()} 判活，配置变更
     * 那一类销毁时它返回 false（那不是 finish，是重建），拦不住：旧实例每秒继续排帧，
     * 一直给一棵已经 detach 的视图树 setText。判据换成 {@code alive()}，再加这里兜底。
     */
    @Override
    protected void onDestroy() {
        if (ticker != null) ticker.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 页内切换会话（无感）

    /**
     * 开一条新会话 —— **页内重置**，不重开 Activity。
     *
     * 用户反馈「新会话的切换要做到无感」：原先走的是 `startActivity(new ChatActivity())`，
     * 屏幕会闪一下、上一页的实例还留在返回栈里；现在和 `/new` 同一条路，原地换掉。
     * 切换后会立刻重拉一次 chips，所以「对话框上面那些」不会还挂着上一条会话的数。
     */
    private void newSessionInPlace() {
        switchTo(null);
        status("新会话 —— 说一句就开始");
    }

    /** 打开一条历史会话（侧栏点进来）。同样页内切换，然后从头把它的转录重放一遍。 */
    private void openSessionInPlace(final String id) {
        switchTo(id);
        status("载入中…");
        final int gen = sessionGen.get();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int seq = stream(id, 0);
                    if (gen == sessionGen.get()) loadedSeq = seq;
                } catch (Throwable e) {
                    if (gen == sessionGen.get()) {
                        notice("!! 重放出错：" + e);
                        status("重放出错：" + e.getClass().getSimpleName());
                    }
                } finally {
                    if (gen == sessionGen.get()) {
                        renderConversation();
                        refreshStatusBar();
                    }
                }
            }
        }, "tianshu-replay");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 页内换会话的公共部分：让旧流作废 → 清空页面状态 → 指向新会话 → 重画 + 刷新 chips。
     *
     * @param id 历史会话 id；**null = 开一条全新会话**
     */
    private void switchTo(final String id) {
        sessionGen.incrementAndGet();     // 在跑的旧流下一次回调就会自行退场（见 stream）
        busy.set(false);                  // 旧流不会再释放这个闸，这里替它放掉
        busyGen.set(Integer.MIN_VALUE);   // 闸已归还，别再让旧线程"核对后释放"一次

        synchronized (events) { events.clear(); }
        synchronized (notices) { notices.clear(); }
        expandedTurns.clear();
        turnsBox.removeAllViews();
        notesBox.removeAllViews();
        renderedTurns.clear();
        renderedNotes = -1;
        setElapsedText("0s");     // 计时卡是常驻视图，只重置文字（别再把它置空）
        turnStartAt = 0;
        loadedSeq = 0;
        liveSessionId = id;
        historySessionId = id;
        input.setText("");
        input.setHint(id == null ? "跟天枢说点什么…" : "继续这个话题…");

        // 页头标题跟着会话走：新会话回默认；历史会话用它的标题（列表还没到就先留默认，
        // 等 renderSessions 拿到列表再填 —— 见那里）
        String knownTitle = id == null ? null : titleFor(id);
        applyChatTitle(knownTitle);
        titleCustomized = knownTitle != null && !knownTitle.trim().isEmpty();
        if (id != null) notice("这是之前的一条对话 —— 往下可以接着聊");
        renderConversation();
        refreshStatusBar();               // chips 立刻反映新会话（模型 / 上下文 / 星域）
    }

    // ---------------------------------------------------------------- 侧拉菜单（历史会话）

    /**
     * 侧栏页头：标题 + 关闭圆钮 —— 与全 App 的「纯标题 + 圆钮」同一语言。
     *
     * 2026-10-04 改：原先只是一行 16f 粗体字，是全 App 最后一处没跟上控件语言的地方
     * （用户这轮点名"包括编辑框，所有的东西"）。关闭符号用 `×`（U+00D7）——
     * 实测 123 个字体覆盖，是最保险的一个（`✕` 只有 1 个字体有，不能用）。
     */
    private View panelHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Theming.dp(this, Ui.S4);
        int vp = Theming.dp(this, Ui.S3);
        row.setPadding(hp, vp, hp, vp);

        TextView h = new TextView(this);
        h.setText("历史会话");
        h.setTextSize(Ui.TITLE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(h, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(Kit.roundButton(this, Icon.CLOSE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sidePanel != null) sidePanel.close();
            }
        }));
        return row;
    }

    private View panelBody() {
        sessionBox = new LinearLayout(this);
        sessionBox.setOrientation(LinearLayout.VERTICAL);
        int hp = Theming.dp(this, Ui.S3);
        int vp = Theming.dp(this, Ui.S3);
        sessionBox.setPadding(hp, 0, hp, vp);
        ScrollView sc = new ScrollView(this);
        sc.addView(sessionBox);
        return sc;
    }

    /** 拉一次 GET /sessions 填进侧拉菜单。点一条进会话页 —— 进去可以接着聊。 */
    private void loadSessions() {
        sessionBox.removeAllViews();
        sessionBox.addView(AppKit.loading(ChatActivity.this, "读取会话…"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String body;
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL(AppRuntime.baseUrl() + "/sessions").openConnection();
                    try {
                        c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        int code = c.getResponseCode();
                        body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                        if (code != 200) body = null;
                    } finally {
                        c.disconnect();
                    }
                } catch (Throwable e) {
                    body = null;
                }
                final String got = body;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (got == null) {
                            sessionBox.removeAllViews();
                            sessionBox.addView(AppKit.error(ChatActivity.this,
                                    "读不到会话列表",
                                    "serve 可能还没就绪，稍等一两秒再试。",
                                    new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            loadSessions();
                                        }
                                    }));
                            Theming.applyTree(ChatActivity.this, sessionBox);
                        } else {
                            renderSessions(SessionList.parse(got));
                        }
                    }
                });
            }
        }, "tianshu-drawer-sessions");
        t.setDaemon(true);
        t.start();
    }

    /** 按会话 id 在最近一次拉到的列表里找标题；找不到返回 null。 */
    private String titleFor(String id) {
        if (lastSessions == null || id == null) return null;
        for (SessionList.Item it : lastSessions) {
            if (id.equals(it.id)) return it.title;
        }
        return null;
    }

    /** 设置页头标题（空 → 默认「对话」）。 */
    private void applyChatTitle(String t) {
        if (pageTitle == null) return;
        pageTitle.setText(t == null || t.trim().isEmpty() ? ChatTitle.DEFAULT : ChatTitle.clean(t));
    }

    private void renderSessions(List<SessionList.Item> items) {
        lastSessions = items;
        // 历史会话：列表到手后把页头换成它的标题（拿不到就维持默认）
        if (historySessionId != null && !titleCustomized) {
            String t = titleFor(historySessionId);
            if (t != null && !t.trim().isEmpty()) {
                applyChatTitle(t);
                titleCustomized = true;
            }
        }
        sessionBox.removeAllViews();
        if (items.isEmpty()) {
            sessionBox.addView(AppKit.emptyState(this, "还没有历史会话", "在对话页发第一句话就有了。"));
        } else {
            long now = System.currentTimeMillis();
            for (SessionList.Item it : items) sessionBox.addView(sessionRow(it, now));
        }
        // 条目是刚建的，必须再走一遍角色贴色（apply 那次跑的时候它们还不存在）
        Theming.applyTree(this, sessionBox);
    }

    /**
     * 侧栏里的一条会话 —— 卡片本身与「会话」页**共用一份**（{@link SessionCard}），
     * 这里只接线：点 = 页内切会话，长按 = 管理面板。
     */
    private View sessionRow(final SessionList.Item it, long now) {
        return SessionCard.build(this, it, now, false, new SessionCard.Cb() {
            @Override
            public void open(SessionList.Item s) {
                sidePanel.close();
                // 页内切换，**不重开 Activity**（用户反馈「新会话的切换要做到无感」）
                openSessionInPlace(s.id);
            }

            @Override
            public void archive(SessionList.Item s) {
                SessionActionMenu.archive(ChatActivity.this, s, drawerDone());
            }

            @Override
            public void delete(SessionList.Item s) {
                SessionActionMenu.delete(ChatActivity.this, s, drawerDone());
            }

            @Override
            public void longPress(SessionList.Item s) {
                SessionActionMenu.open(ChatActivity.this, s, drawerDone());
            }
        });
    }

    /** 侧栏里做完归档/删除后的回执：写状态、当场重拉列表、收起抽屉。 */
    private SessionActionMenu.OnDone drawerDone() {
        return new SessionActionMenu.OnDone() {
            @Override
            public void done(String message) {
                status(message);
                // 列表变了（归档/删除）：当场重拉，用户再打开侧栏看到的就是最新的
                loadSessions();
                if (sidePanel != null) sidePanel.close();
            }
        };
    }

    private TextView panelNote(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12f);
        Theming.tag(t, Theming.ROLE_MUTED);
        return t;
    }

    // ---------------------------------------------------------------- 命令抽屉 / 输入建议

    /** 手动展开完整目录；已展开则收起。 */
    private void toggleCommands() {
        if (cmdMode == CMD_DRAWER) {
            hideCmdArea();
            return;
        }
        cmdMode = CMD_DRAWER;
        loadCommandDirectory();
        cmdScroll.setVisibility(View.VISIBLE);
        setCommandToggle(true);
    }

    /** 命令目录来自 assets（build.sh 从 data/ 打进去）—— 与命令面板页同一份数据。 */
    private void loadCommandDirectory() {
        CommandCatalog.Catalog cat = catalog();
        cmdList.removeAllViews();
        if (cat.commands.isEmpty()) {
            cmdList.addView(panelNote("命令目录读不到（assets/tianshu-cmd 缺失）"));
            return;
        }
        for (CommandCatalog.Surface s : cat.surfaces) {
            cmdList.addView(commandGroup(CommandCatalog.surfaceZh(s.key), s.items));
        }

        // 抽屉里只列"有落点"的命令；「移动端不适用」那 5 条、分组统计、
        // 点按复制/长按发送那些用法在完整面板里。留个入口 —— 否则删掉启动页入口之后，
        // 那个页面就变成谁也到不了的孤儿了。
        Button all = new Button(this);
        all.setText("完整命令面板（含不适用清单）");
        Theming.tag(all, Theming.ROLE_GHOST);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ChatActivity.this, CommandPanelActivity.class));
            }
        });
        cmdList.addView(all);

        Theming.applyTree(this, cmdList);
    }

    /**
     * 输入变化 → 要不要弹命令建议。
     *
     * 只在**行首**是 "/" 时弹（那才是命令语境）；一旦不是就把建议收掉。
     * 手动展开的完整目录不受影响 —— 但建议一出现就接管那块区域（两者共用一个容器）。
     */
    private void onInputChanged(String text) {
        if (muteSuggest) {
            // 这次变化是我们自己写进去的（点了建议），别立刻又弹回来
            muteSuggest = false;
            return;
        }
        if (text.startsWith("/")) {
            showSuggestions(text);
        } else if (cmdMode == CMD_SUGGEST) {
            hideCmdArea();
        }
    }

    /** 边打边筛："/se" 先给前缀命中的（/sessions），再给词内命中的。 */
    private void showSuggestions(String text) {
        List<String> hits = CommandCatalog.suggest(text, catalog().commands, 8);
        if (hits.isEmpty()) {
            hideCmdArea();
            return;
        }
        cmdMode = CMD_SUGGEST;
        cmdList.removeAllViews();
        cmdList.addView(commandGroup(null, hits));
        Theming.applyTree(this, cmdList);
        cmdScroll.setVisibility(View.VISIBLE);
        setCommandToggle(true);
    }

    private void hideCmdArea() {
        cmdMode = CMD_NONE;
        cmdScroll.setVisibility(View.GONE);
        setCommandToggle(false);
    }

    /** 命令目录只解析一次（命令 + 分组，没必要每敲一个字就重来一遍）。 */
    private CommandCatalog.Catalog catalog() {
        if (cmdCatalog == null) {
            cmdCatalog = CommandCatalog.load(
                    readAsset("tianshu-cmd/commands.txt"), readAsset("tianshu-cmd/components.json"));
        }
        return cmdCatalog;
    }

    /**
     * 一组命令行 —— 抽屉按落点分组、建议候选一锅端，都走它。
     *
     * 2026-10-04 第三轮：原先这里是自造的「12f 粗体小标题 + 每行一张卡」，
     * 与命令面板页是两套行语言（那一页已经走 `Kit.group` + `Kit.menuRow`）。
     * 现在两边**共用同一份行**，包括"这条命令在 App 里到底能不能用"那行状态
     *（`CommandPanelActivity.fateLine`）。
     */
    private View commandGroup(String title, List<String> cmds) {
        Kit.Group g = Kit.group(this, title);
        for (String cmd : cmds) {
            g.row(cmdRow(cmd), 16);
        }
        return g.root;
    }

    /** 一行命令：点一下填进输入框 —— 参数留给用户自己补，不替用户按发送。 */
    private View cmdRow(final String cmd) {
        return Kit.menuRow(this, 0, cmd, catalog().descOf(cmd),
                CommandPanelActivity.fateLine(this, cmd),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 先把闸门关上：下面的 setText 会触发 afterTextChanged，
                        // 不拦一下建议会立刻又弹回来 —— 用户看到的就是"点了没反应"
                        muteSuggest = true;
                        input.setText(cmd + " ");
                        input.setSelection(input.getText().length());
                        input.requestFocus();
                        hideCmdArea();
                    }
                });
    }

    private String readAsset(String path) {
        try {
            InputStream in = getAssets().open(path);
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        // 抽屉开着时返回先收抽屉 —— 否则一按就退出整个 App，用户会以为崩了
        if (sidePanel != null && sidePanel.isOpen()) {
            sidePanel.close();
            return;
        }
        if (cmdMode != CMD_NONE) {
            hideCmdArea();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 发送
    /**
     * 跑动中再发消息 → **排队**（与 TUI 一致，真机反馈 #11）。
     *
     * 活体核过：会话在跑时 `POST /sessions/:id/prompt` 返 409 `{"code":"busy"}`，
     * 而 `POST /sessions/:id/queue` 返 `{"queued":true,"laneId":…}` —— 后者才是终端的规则。
     */
    private void queueMessage(final String text) {
        final String sid = liveSessionId;
        if (sid == null || sid.isEmpty()) {
            status("还没有会话 —— 这一轮跑完再发");
            return;
        }
        status("排队中…");
        final int gen = sessionGen.get();     // 这笔排队确认属于哪个世代
        RuntimeApi.post("/sessions/" + sid + "/queue",
                "{\"text\":\"" + CommandApi.json(text) + "\"}", new RuntimeApi.Cb() {
                    @Override
                    public void ok(String body) {
                        // 已经切到别的会话了，就别把这条排队确认挂到那一页上 ——
                        // 那会让用户以为这句话排在**当前**会话里（2026-10-05 走查核出：
                        // 这里与 send() / openSessionInPlace 不对称，两处都套了 gen 检查）。
                        if (gen != sessionGen.get()) return;
                        input.setText("");
                        Transcript.Event mine = new Transcript.Event();
                        mine.type = "user";
                        mine.text = text;
                        addEvent(mine);
                        renderConversation();
                        status("已排队 —— 这一轮跑完就发");
                    }

                    @Override
                    public void fail(String message) {
                        status("排队失败：" + message);
                    }
                });
    }

    private void send() {
        final String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return;

        // ---- 命令先判类，再决定发不发 ----
        // 从前这里不做任何判断：/connect 被原样 POST 出去，模型把它当成一句请求来回答。
        // harness 侧没有"执行斜杠命令"的通用入口（只有 /sessions/:id/prompt 会解析一个很窄的
        // 子集），所以本地执行类的命令必须 App 自己接；没接上的**拦住并说明**，绝不静默发出去。
        if (raw.startsWith("/") && handleCommand(raw)) return;

        // 走到这里 = 不是命令，或判为「交给天枢」。**指令桥**：有一批命令在终端是进程内执行、
        // HTTP 侧没有斜杠入口（原样发会被 400 Unknown slash command），但它们的语义就是
        // "让 agent 去做某件事" —— 换成等价的一条自然语言指令发出去。见 CommandPrompt。
        final String text = CommandPrompt.rewrite(raw);

        if (!busy.compareAndSet(false, true)) {
            // TUI 的规则是「跑动中再发一条 = 排队」（真机反馈 #11），不是丢掉。
            // 从前这里只提示「等它结束」—— 消息被默默扔掉，与终端行为不一致。
            // 活体实测：会话在跑时 POST /sessions/:id/prompt 返 409 {"code":"busy"}，
            // 而 POST /sessions/:id/queue 返 {"queued":true,"laneId":…}。
            queueMessage(text);
            return;
        }
        // 这一轮归属哪个"会话世代" —— 释放闸时必须核对（见 busyGen 的注释）
        final int gen = sessionGen.get();
        busyGen.set(gen);

        turnStartAt = System.currentTimeMillis();   // 状态栏「用时」从这一刻起算
        input.setText("");

        // 用户第一句话 → 页头标题（用户 2026-10-04：「对话那两个字…从第一句开始更新」）。
        // 只在**全新会话**且标题还没被定过时改一次；历史会话用它的会话标题（见 renderSessions）。
        if (!titleCustomized && historySessionId == null) {
            applyChatTitle(ChatTitle.fromMessage(text));
        }

        // 立刻把用户那句挂上去（服务端也会回显 `user`，取的是最后一个，不会重复显示）。
        Transcript.Event mine = new Transcript.Event();
        mine.type = "user";
        mine.text = text;
        addEvent(mine);
        renderConversation();
        status("发送中…");

        final String history = historySessionId;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (history != null) {
                        if (!resumeSend(history, text)) {
                            // 服务端没收下这句（多半 409 busy）—— 把乐观气泡撤掉，别让页面说谎。
                            // 失败原因已由 resumeSend 写进状态行。
                            if (gen == sessionGen.get()) {
                                removeEvent(mine);
                                renderConversation();
                            }
                            return;                       // finally 会放闸，也会再刷一次
                        }
                    } else {
                        String sessionId = createSession(text);
                        // 页面已经被切走 → **不要**把这条会话的 id / 水位写进当前页。
                        // （openSessionInPlace 对同一组回写逐个套了 gen 检查，这里原先漏了 —— 2026-10-04 深查发现）
                        if (gen != sessionGen.get()) return;
                        liveSessionId = sessionId;          // 顶栏据此挑出当前这条会话
                        // ⚠️ 必须**同时**把它记成"当前历史会话"。
                        // 上面那句 `if (history != null)` 判的就是 historySessionId，
                        // 它一直为 null 的话，**下一句话又会 createSession** —— 于是每发一句
                        // 就新建一条会话，用户看到的是"它完全不记得上一句"。
                        // 真机日志（2026-10-05）里 session id 一条一换，就是漏了这一行。
                        historySessionId = sessionId;
                        loadedSeq = stream(sessionId, 0);
                    }
                } catch (Throwable e) {
                    if (gen == sessionGen.get()) {         // 旧世代的错，别弹到新页面上
                        // 那句**并没有发出去** —— 把乐观气泡撤掉。
                        // 否则页面上会留一条看着已发送、实际从没离开设备的用户消息，
                        // 而输入框早已清空，用户连重打一遍的原文都找不回来
                        //（2026-10-05 走查核出）。
                        removeEvent(mine);
                        notice("!! 这句没发出去：" + e);
                        status("没发出去：" + e.getClass().getSimpleName());
                    }
                } finally {
                    // 只放**本世代**持有的闸（否则会把新会话正在用的闸误放掉）
                    if (busyGen.compareAndSet(gen, Integer.MIN_VALUE)) busy.set(false);
                    if (gen == sessionGen.get()) {
                        renderConversation();
                        refreshStatusBar();     // 一轮跑完，档位 / 上下文 / 缓存都可能变了
                    }
                }
            }
        }, "tianshu-chat");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 按 {@link CommandRouting} 判类并执行。返回 true = 已消费（**不要**发出去）。
     *
     * 这一步的存在就是为了堵住「敲 /connect 却进了对话」：本地执行类的命令
     * 服务端根本不会执行，静默发出去等于把命令名当请求交给模型。
     */
    private boolean handleCommand(String text) {
        String cmd = CommandRouting.cmdOf(text);
        CommandRouting.Row row = CommandRouting.of(cmd);
        if (row == null) return false;                            // 不在表里：按普通消息走
        if (CommandRouting.SEND.equals(row.action)) return false;  // 本来就该发给模型

        input.setText("");

        if (CommandRouting.NONE.equals(row.action)) {
            noticeNow(row.target);
            status("这条命令在触屏没有对应操作");
            return true;
        }
        if (CommandRouting.TUI.equals(row.action)) {              // 终端本地能力：App 无对端
            noticeNow(row.target);
            status("这条命令在 App 里没有对应入口");
            return true;
        }
        if (!row.available) {                                     // 还没接上：拦住，别发
            noticeNow(CommandRouting.blockedHint(row));
            status("还没接上 —— 已拦住，没有发出去");
            return true;
        }
        if (CommandRouting.NAV.equals(row.action)) {
            Class<?> page = pageFor(row.target);
            if (page == null) {
                noticeNow("「" + cmd + "」的落点还没建：" + row.target);
                status("落点未建");
                return true;
            }
            notice("「" + cmd + "」→ " + row.target);
            startActivity(new Intent(this, page));
            return true;
        }
        if (CommandRouting.LOCAL.equals(row.action)) {
            if ("/clear".equals(cmd)) {
                synchronized (events) { events.clear(); }
                synchronized (notices) { notices.clear(); }
                turnsBox.removeAllViews();
                notesBox.removeAllViews();
                renderedTurns.clear();
                renderedNotes = -1;
                notice("已清空当前显示（服务端仍留着这条会话的历史）");
                renderConversation();
                status("已清屏");
                return true;
            }
            if ("/new".equals(cmd)) {
                newSessionInPlace();
                return true;
            }
        }
        if (CommandRouting.READ.equals(row.action)) {
            // B1：harness 的"内存态"命令里，数据其实已落盘的那批 —— App 作为宿主
            // 直接读会话目录重建，不必改内核（见 LocalData）。
            String localText = readLocalCommand(cmd);
            if (localText == null) {
                noticeNow("读不到这条会话的落盘数据（sessions 目录不可用）");
                status("读取失败");
            } else {
                notice(localText);
                renderConversation();   // notice 只入列表；不主动渲染就不会出现在对话里
                status("已执行 " + cmd);
            }
            return true;
        }
        if (CommandRouting.API.equals(row.action)) {
            final CommandApi.Call call =
                    CommandApi.call(cmd, CommandApi.argOf(text, cmd), historySessionId);
            if (call == null) {
                noticeNow(CommandRouting.blockedHint(row));
                status("还没接上 —— 已拦住，没有发出去");
                return true;
            }
            if (call.error != null) {                             // 参数不对：只提示，别发
                noticeNow(call.error);
                status("没有执行");
                return true;
            }
            noticeNow("执行 " + cmd + " …");
            status("执行中…");
            runApiCommand(cmd, call);
            return true;
        }
        noticeNow(CommandRouting.blockedHint(row));
        status("还没接上 —— 已拦住，没有发出去");
        return true;
    }

    /**
     * B1：本地读盘命令 —— 把 harness 里读"进程内内存"、但数据其实已落盘的命令，
     * 在 App 侧直接用会话目录重建。数据源与文本化见 {@link LocalData}。
     */
    private String readLocalCommand(String cmd) {
        String sid = historySessionId;
        if (sid == null || sid.length() == 0) return null;
        File sessions = new RuntimeHost(this).sessionsHostDir();
        if (sessions == null) return null;
        String cwd = "/root";                       // App 的 proot 以 -w /root 启动
        if ("/todo".equals(cmd)) return LocalData.todos(sessions, cwd, sid);
        if ("/context".equals(cmd)) return LocalData.context(sessions, cwd, sid);
        if ("/debug".equals(cmd)) return LocalData.debug(sessions, cwd, sid);
        if ("/memory".equals(cmd)) return LocalData.memory(sessions, cwd, sid);
        if ("/verify".equals(cmd)) return LocalData.verify(sessions, cwd, sid);
        if ("/branch".equals(cmd)) return LocalData.branch(sessions, cwd, sid);
        return null;
    }

    /** 把 {@link CommandApi} 算出的调用打出去，结果落回对话里。 */
    private void runApiCommand(final String cmd, final CommandApi.Call call) {
        RuntimeApi.Cb cb = new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                notice(CommandApi.doneHint(cmd));
                // GET 类命令（/plan-list /goal-status /doctor /sensorium /rewind /rollback…）
                // **结果就是数据本身** —— 只回一句"已执行"等于没说。摊成可读文本贴出来。
                String shown = ApiResult.readable(body);
                if (shown.length() > 0) notice(shown);
                status("已执行 " + cmd);
                // notice 只把文本入列表、不触发渲染 —— 不主动渲染，命令结果就永远看不见
                renderConversation();
                // 有些命令改的正是状态栏要看的东西（/yes /yolo 改审批模式、/effort 改强度…）——
                // 不立刻重拉一次，用户会以为"关了没生效"。
                refreshStatusBar();
            }

            @Override
            public void fail(String message) {
                notice(cmd + " 失败：" + message);
                status("执行失败");
                renderConversation();
            }
        };
        if ("PUT".equals(call.method)) RuntimeApi.put(call.path, call.body, cb);
        else if ("GET".equals(call.method)) RuntimeApi.get(call.path, cb);
        else if ("PATCH".equals(call.method)) RuntimeApi.patch(call.path, call.body, cb);
        else if ("DELETE".equals(call.method)) RuntimeApi.delete(call.path, cb);
        else RuntimeApi.post(call.path, call.body, cb);
    }

    /** 落点文字 → 页面。页面就是该命令在 App 里的归属地。 */
    private Class<?> pageFor(String target) {
        if (target.contains("会话页")) return SessionListActivity.class;
        if (target.contains("外观页")) return AppearanceActivity.class;
        if (target.contains("模型页")) return ModelsActivity.class;
        if (target.contains("设置页")) return SettingsActivity.class;
        if (target.contains("命令面板页")) return CommandPanelActivity.class;
        if (target.contains("面板页")) return PanelsActivity.class;
        if (target.contains("星图页")) return StarmapActivity.class;
        if (target.contains("编年史页")) return ChronicleActivity.class;
        if (target.contains("蓝图页")) return ConstellationActivity.class;
        if (target.contains("账号页")) return AccountActivity.class;
        return null;
    }

    /**
     * 续聊：把这句话送进**已有**会话，再从当前水位接增量流。
     *
     * 增量（`since=loadedSeq`）不是优化，是正确性要求：`since=0` 会把整段历史重放一遍，
     * 而页面上的轮次本来就是那些历史 —— 叠加后按 turn_complete 分轮就会翻倍。
     */
    /**
     * @return true = 这句真的送出去了；false = 服务端没收（如 409 busy），调用方须把乐观气泡撤掉。
     */
    private boolean resumeSend(String sessionId, String text) throws Exception {
        HttpResult r = postPrompt(sessionId, text);
        if (r.code >= 400) {
            // 409 busy 是常态（上一轮还在跑），不是崩溃 —— 把服务端那句人话带给用户。
            //
            // ⚠️ 这里**必须**把"没送出去"告诉调用方：send() 已经先把用户那句乐观地挂上了，
            // 不撤的话页面上会留一条看着已发送、实际从没离开设备的用户消息 —— 而输入框早已清空，
            // 用户连重打一遍的原文都找不回来。新建会话那条路径（见 send() 的 catch）专门修过这点，
            // 续聊这条一直漏着（2026-10-05 走查核出）。
            status(SessionActions.humanError(r.code, r.body));
            return false;
        }
        notice("   继续这条对话…");
        renderConversation();
        loadedSeq = stream(sessionId, loadedSeq);
        return true;
    }

    /** 一次 HTTP 调用的结果：状态码 + 响应体（错误体要原样带给用户看）。 */
    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** 续聊请求：`POST /sessions/:id/prompt {prompt}`。200 正常；409 = 会话正在跑。 */
    private HttpResult postPrompt(String sessionId, String prompt) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL(AppRuntime.baseUrl() + "/sessions/" + sessionId + "/prompt").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);

            JSONObject body = new JSONObject();
            body.put("prompt", prompt);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }

            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            return new HttpResult(code, resp);
        } finally {
            c.disconnect();
        }
    }

    /** 第一步：创建会话并把话递进去。注意响应里**没有回答**。 */
    private String createSession(String prompt) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(AppRuntime.baseUrl() + "/sessions").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);

            JSONObject body = new JSONObject();
            body.put("prompt", prompt);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }

            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 201 && code != 200) {
                throw new IllegalStateException("POST /sessions -> HTTP " + code + " " + head(resp, 300));
            }
            JSONObject o = new JSONObject(resp);
            String id = o.optString("id", null);
            if (id == null || id.isEmpty()) throw new IllegalStateException("响应里没有 id：" + head(resp, 300));
            return id;
        } finally {
            c.disconnect();
        }
    }

    /**
     * 第二步：接 SSE，事件喂给分桶器与状态机。返回吃到的**最大序号**（续聊拿它当水位）。
     *
     * @param since 只取 seq &gt; since 的事件；0 = 从头发（首次加载历史 / 新会话）。
     */
    private int stream(String sessionId, int since) throws Exception {
        final ConversationState st = new ConversationState();
        final SseParser parser = new SseParser();
        final HttpURLConnection[] conn = new HttpURLConnection[1];
        // 本次流的世代号：用户中途切了会话就作废，免得把旧会话的事件灌进新页面
        final int myGen = sessionGen.get();

        SseParser.Handler handler = new SseParser.Handler() {
            @Override
            public void onEvent(String event, String data) {
                if (myGen != sessionGen.get()) {
                    HttpURLConnection c = conn[0];
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                            // 断不干净也无所谓，外层循环还有一道世代检查
                        }
                    }
                    return;
                }
                st.apply(event, data);
                addEvent(Transcript.fromSse(event, data));

                if ("phase".equals(event)) {
                    String p = MiniJson.str(data, "phase");
                    if (p != null) status("进行中：" + p);
                } else if ("done".equals(event)) {
                    String s = MiniJson.str(data, "status");
                    status("结束：" + s);
                }
                // SSE 是长连接：服务端发完 done 并不关连接（之后还在发 : ping）。
                // 不主动断开，readLine 就永远不返回 null，界面会卡死在「流式中」，
                // busy 也永远不释放 —— 第二句话都发不出去。
                // ⚠ 但**回放**里有 N 个 done（每条历史轮一个）：这里也必须等回放读完才断，
                // 否则第 1 个 done 就把 socket 拆了 —— 老会话只显示前一两轮（2026-10-05 真机复现）。
                // 与读循环的收流条件同一道闸门（ConversationState.replayFinished）。
                if (st.isFinished() && st.replayFinished()) {
                    HttpURLConnection c = conn[0];
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                            // 断不干净也无所谓，下面还有 isFinished() 兜底
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastPublish > 250) {      // 节流：别把 UI 淹了
                    lastPublish = now;
                    renderConversation();
                }
            }
        };

        HttpURLConnection c = (HttpURLConnection) new URL(AppRuntime.streamUrl(sessionId, since)).openConnection();
        conn[0] = c;
        try {
            c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
            c.setRequestProperty("Accept", "text/event-stream");
            c.setConnectTimeout(15000);
            c.setReadTimeout(STREAM_IDLE_TIMEOUT_MS);   // 空闲上限，不是"总时长"——见常量注释

            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IllegalStateException("GET stream -> HTTP " + code);
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                parser.feed(line, handler);
                // ⚠ 不能只看 isFinished()（= 收到过任何 done）：**回放一条老会话时，
                // 服务端把每一轮历史都发一遍，流里躺着 N 个 done** —— 一看到就 break
                // 会让老会话只渲染第 1 轮（2026-10-05 真机复现，14 轮的会话只显示 1 轮，
                // 发消息后视图又跳到第 2 轮）。必须等**回放读完**再收流。
                if (st.isFinished() && st.replayFinished()) break;
                if (myGen != sessionGen.get()) break;   // 会话被切走：立刻收流，别拖着一个死连接
            }
            parser.flush(handler);
        } catch (java.net.SocketTimeoutException stalled) {
            // 流僵住了：不是网络错，是"它不说话了"。收尾放行 busy，并如实告诉用户怎么继续 ——
            // 会话在服务端照旧跑着，历史不会丢。
            if (!st.isFinished()) {
                notice("这条流 " + (STREAM_IDLE_TIMEOUT_MS / 1000) + " 秒没有动静，已收掉 —— "
                        + "再发一句就能接着聊（历史都在服务端）");
            }
        } catch (IOException e) {
            // done 之后我们主动断连接，读端报错属正常 —— 只有「还没结束就断」才是真故障
            if (!st.isFinished()) throw e;
        } finally {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
                // 忽略
            }
        }

        // 只在**整段重放**（since==0，即打开一条会话）时判定：增量续聊虽然也会收到
        // replay_window，但那时更早的历史本就已经在页面上了。
        // 截断必须**说出来** —— 否则用户看到的是"老会话少了半截记录"却毫无提示
        //（2026-10-05 真机反馈）。判定见 ConversationState.hasEarlierHistory。
        if (since == 0 && st.hasEarlierHistory()) {
            notice("这条会话更早的记录不在本次回放窗口里 —— 本机缓存的是 seq "
                    + st.floorSeq + " 之后的部分，上面显示的就是这一段（完整的在会话文件里）。");
        }
        renderConversation();
        // 这一行原本把「事件 25 · lastSeq 38 · 思考 293 字 · completed」直接摊在对话末尾 ——
        // 那是给自己排障看的，正常用户看完一轮回答不需要知道事件序号。
        return st.lastSeq;
    }

    // ---------------------------------------------------------------- 渲染
    /** 按不变量 A 重建整页：系统提示 → 每轮〔用户 → 活动（折叠）→ 答案（置底）〕。 */
    private void renderConversation() {
        postUi(new Runnable() {
            @Override
            public void run() {
                List<Transcript.Event> snapshot;
                List<String> notes;
                synchronized (events) {
                    snapshot = new ArrayList<Transcript.Event>(events);
                }
                synchronized (notices) {
                    notes = new ArrayList<String>(notices);
                }

                frameTokens = Theming.tokens(ChatActivity.this);   // 本帧只算一次（见 frameTokens 注释）
                renderNotes(notes);

                // 轮次区走**增量**：只有最后一轮在长，前面的直接复用上一帧的视图
                List<Transcript.TurnView> turns = Transcript.parseConversation(snapshot);
                renderTurns(turns);

                // 空态提示的可见性取决于这一页到底有没有内容
                boolean blank = turns.isEmpty() && notes.isEmpty();
                if (emptyBox != null) emptyBox.setVisibility(blank ? View.VISIBLE : View.GONE);

                if (atBottom) scrollToBottom();
            }
        });
    }

    /** 系统提示条：只增不减，条数没变就整块跳过 —— 不必每帧重建。 */
    private void renderNotes(List<String> notes) {
        if (notes.size() == renderedNotes) return;
        renderedNotes = notes.size();
        notesBox.removeAllViews();
        for (String n : notes) {
            TextView t = new TextView(this);
            t.setText(n);
            t.setTextSize(Ui.LABEL);
            t.setLineSpacing(0f, 1.25f);
            Theming.tag(t, Theming.ROLE_MUTED);
            tint(t);                          // 新造的才贴色，不动老的
            notesBox.addView(t);
        }
    }

    /**
     * 轮次区：**增量**渲染 —— 每帧只重画最后一轮（以及新冒出来的轮），前面的复用。
     *
     * 这是"对话时卡"的主治：原先每帧 `content.removeAllViews()` 把整段对话重建一遍，
     * 对话越长、每帧要造的 View 越多，而流式期间每 250ms 就是一帧。
     */
    private void renderTurns(List<Transcript.TurnView> turns) {
        int from = RenderPlan.firstDirtyTurn(renderedTurns.size(), turns.size());
        if (from == RenderPlan.FULL) {
            turnsBox.removeAllViews();
            renderedTurns.clear();
            from = 0;
        } else {
            for (int i = renderedTurns.size() - 1; i >= from; i--) {
                for (View v : renderedTurns.get(i)) turnsBox.removeView(v);
                renderedTurns.remove(i);
            }
        }
        for (int i = from; i < turns.size(); i++) {
            List<View> made = new ArrayList<View>();
            addTurnViews(i, turns.get(i), made);
            renderedTurns.add(made);
        }
    }

    /**
     * 本帧的 **token 快照** —— 一次算好，整帧里新造的视图共用。
     *
     * 为什么必须这么做：`Theme.tokens()` 实测 **63.8µs/次**（要解对比度），而 `emit()` 原先
     * 对**每个新视图**都单独贴一次色，每次都会重算一遍（还多带一次 SharedPreferences 读取 +
     * JSON 解析）。一帧造 20 个视图就是 **1.2ms+** 白烧在主线程上（2026-10-04 性能专项实测）。
     * 现在一帧只算一次；下一帧会重算，所以换肤最多差一帧、不会残留旧色。
     */
    private Theme.Tokens frameTokens;

    /** 给一个新造的视图贴色：优先用本帧快照，没有就现算一次。 */
    private void tint(View v) {
        if (frameTokens == null) frameTokens = Theming.tokens(this);
        Theming.applyTree(this, v, frameTokens);
    }

    /** 把一个新造的视图挂进轮次区：登记（下次要拆时按名单拆）+ **只给它**贴一遍外观。 */
    private void emit(List<View> made, View v) {
        turnsBox.addView(v);
        made.add(v);
        tint(v);
    }

    private void addTurnViews(final int index, final Transcript.TurnView turn, List<View> made) {
        if (turn.userText != null) {
            // 用户那侧做成**右对齐的主色气泡 + 右侧头像** —— 一眼分得出"我说的"和"它答的"。
            // 气泡宽度留出边距，长句换行而不是顶满整行（头像占了 28+8dp，所以 0.82 → 0.74）。
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);

            TextView u = new TextView(this);
            u.setText(turn.userText);
            u.setTextSize(Ui.BODY);
            u.setLineSpacing(0f, 1.2f);
            u.setPadding(Theming.dp(this, Ui.S3), Theming.dp(this, 10),
                    Theming.dp(this, Ui.S3), Theming.dp(this, 10));
            u.setTextIsSelectable(true);
            u.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.74));
            Theming.tag(u, Theming.ROLE_BUBBLE);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Theming.dp(this, Ui.S4);
            lp.bottomMargin = Theming.dp(this, Ui.S1);
            row.addView(u, lp);

            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            avLp.leftMargin = Theming.dp(this, Ui.S2);
            avLp.topMargin = Theming.dp(this, Ui.S1);
            row.addView(avatar(Icon.PERSON), avLp);
            emit(made, row);
        }

        final Transcript.Block act = turn.activity();
        if (act != null) {
            // ⚠ 折叠时**不建子项**：思考动辄几千字，先造一堆大段文本的 TextView 再 GONE 掉，
            //    纯属白烧主线程（这是"对话时卡"的另一大块）。展开时才真的建。
            final LinearLayout children = new LinearLayout(this);
            children.setOrientation(LinearLayout.VERTICAL);
            children.setPadding(Theming.dp(this, Ui.S4), Theming.dp(this, Ui.S1),
                    0, Theming.dp(this, Ui.S1));
            final boolean[] built = {false};
            final boolean[] expanded = {expandedTurns.contains(Integer.valueOf(index))};
            if (expanded[0]) {
                fillChildren(children, act);
                built[0] = true;
            }
            children.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);

            final TextView header = new TextView(this);
            header.setTextSize(Ui.CAPTION);
            header.setLineSpacing(0f, 1.25f);
            Theming.tag(header, Theming.ROLE_ACCENT);
            header.setPadding(0, Theming.dp(this, Ui.S3), 0, Theming.dp(this, Ui.S1));
            header.setText(activityText(act, expanded[0]));
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    expanded[0] = !expanded[0];
                    if (expanded[0] && !built[0]) {       // 首次展开才真的建子项
                        fillChildren(children, act);
                        tint(children);
                        built[0] = true;
                    }
                    children.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                    if (expanded[0]) expandedTurns.add(Integer.valueOf(index));
                    else expandedTurns.remove(Integer.valueOf(index));
                    header.setText(activityText(act, expanded[0]));
                }
            });

            emit(made, header);
            emit(made, children);
        }

        // 答案永远在这一轮最底 —— 这就是整个页面的不变量。
        Transcript.Block ans = turn.answer();
        if (ans != null) {
            // 助手侧：**左侧头像 + 卡片式气泡**。用户贴右、这侧贴左 —— 谁说的看一眼就知道。
            // 原先只有用户侧有气泡，助手侧是一大片没有归属的正文。
            // 用 ROLE_CARD 当底：与用户气泡（主色浅底）是同一套圆角语言，只是材质不同
            //（参考里也是这么分的：用户侧主色浅档、助手侧表面色）。
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            avLp.rightMargin = Theming.dp(this, Ui.S2);
            avLp.topMargin = Theming.dp(this, Ui.S1);
            row.addView(avatar(Icon.BRAND), avLp);

            TextView a = new TextView(this);
            a.setText(ans.text);
            a.setTextSize(Ui.BODY);
            a.setLineSpacing(0f, 1.25f);              // 中文长段落：行距 1.25 比 1.0 好读得多
            a.setTextIsSelectable(true);
            a.setPadding(Theming.dp(this, Ui.S3), Theming.dp(this, Ui.S2),
                    Theming.dp(this, Ui.S3), Theming.dp(this, Ui.S2));
            Theming.tag(a, Theming.ROLE_CARD);
            row.addView(a, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            emit(made, row);
        } else if (act == null) {
            TextView a = new TextView(this);
            a.setText("（等待回答…）");
            a.setTextSize(Ui.CAPTION);
            Theming.tag(a, Theming.ROLE_MUTED);
            a.setPadding(0, Theming.dp(this, Ui.S2), 0, Theming.dp(this, Ui.S2));
            emit(made, a);
        }
    }

    /** 活动区的子项 —— 折叠时**不建**，展开时才调它（见 addTurnViews 里的说明）。 */
    private void fillChildren(LinearLayout children, Transcript.Block act) {
        for (Transcript.Child child : act.children) {
            children.addView(childView(child));
        }
    }

    private TextView childView(Transcript.Child child) {
        TextView t = new TextView(this);
        String body = child.text == null ? "" : child.text;
        if (body.length() > MAX_CHILD_CHARS) body = body.substring(0, MAX_CHILD_CHARS) + "…";
        t.setText(labelOf(child.kind) + body);
        t.setTextSize(Ui.CAPTION);
        t.setLineSpacing(0f, 1.25f);
        Theming.tag(t, child.isError ? Theming.ROLE_ERROR : Theming.ROLE_MUTED);
        t.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S1));
        t.setTextIsSelectable(true);
        return t;
    }

    /**
     * 活动条的文案 —— 收起/展开两态 + **子项计数**。
     *
     * 计数是这一轮加的：原先只写「活动 · &lt;摘要&gt;」，用户不知道点开里面有多少东西
     *（思考动辄几千字、展开是有代价的）；写上"· N 项"才让人能判断值不值得点。
     */
    private static String activityText(Transcript.Block act, boolean expanded) {
        int n = act.children == null ? 0 : act.children.size();
        StringBuilder sb = new StringBuilder();
        sb.append("活动 · ").append(act.summary());
        if (n > 0) sb.append(" · ").append(n).append(" 项");
        sb.append(expanded ? "（点按收起）" : "（点按展开）");
        return sb.toString();
    }

    /**
     * 命令圆钮的展开态：**开着 = 主色底，收起 = 浅底**。
     *
     * 圆钮上没有文字（那是它收进输入行的代价），所以状态只能靠底色表达 ——
     * 好在"主色点亮"比在按钮上写"（收起）"更干净，也省掉半行字。
     */
    private void setCommandToggle(boolean open) {
        if (cmdToggle == null) return;
        Theming.tag(cmdToggle, open ? Theming.ROLE_ROUND_PRI : Theming.ROLE_ROUND);
        Theming.applyTree(this, cmdToggle);
    }

    /**
     * 空态 —— 新会话第一眼看到的东西。
     *
     * 它**唯一的功能**是"告诉用户能做什么"，所以里面放的是三个**能点**的下一步，
     * 而不是一句"这里是空的"式的说明（那种文案只是把空白重述了一遍）。
     *
     * ⚠️ 别在这里写"输入 / 可以看全部命令"那种要靠敲命令的技巧 —— 引导就该是能点的。
     */
    private View buildEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Theming.dp(this, Ui.S5), 0, 0);

        TextView greet = new TextView(this);
        greet.setText("说一句话开始");
        greet.setTextSize(Ui.DISPLAY);
        greet.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(greet);

        TextView sub = new TextView(this);
        sub.setText("天枢会在这儿接下去 —— 也可以从下面任一条起步");
        sub.setTextSize(Ui.CAPTION);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S4));
        Theming.tag(sub, Theming.ROLE_MUTED);
        box.addView(sub);

        Kit.Group g = Kit.group(this, "从这里开始");
        g.row(Kit.menuRow(this, Icon.STAR, "问一个问题",
                "光标放进输入框，直接打字", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        input.requestFocus();   // 用户主动点的，所以弹键盘是对的
                    }
                }), 56);
        // 命令条数**从目录派生**，不写死 —— 2026-10-06 装机核出：harness 升到 3.28.0
        //（命令 104 → 112）之后，这一行还硬写着「104 条命令」。写死就会再漂一次。
        final int cmdCount = catalog().commands.size();
        g.row(Kit.menuRow(this, Icon.COMMAND, "看看能做什么",
                cmdCount + " 条命令，分门别类列出来", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleCommands();
                    }
                }), 56);
        g.row(Kit.menuRow(this, Icon.MENU, "翻上次的对话",
                "历史会话都在左边侧栏里", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (sidePanel != null) {
                            sidePanel.toggle();
                            if (sidePanel.isOpen()) loadSessions();
                        }
                    }
                }), 56);
        box.addView(g.root);
        return box;
    }

    /**
     * 消息头像 —— 圆形 + 主色浅底 + 主色字（复用 `ROLE_ROUND` 那套视觉，不另开角色）。
     *
     * 天枢不引图片资源（构建链与包体都不允许），所以头像用**一个字/符号**代替：
     * 「我」是自己、`☥` 是星域徽记（天枢那一侧）。参考的 `YukiAvatar` 是同一个思路 ——
     * 它的默认态是"自绘雪晶"，理由写在注释里："一个灰色人形轮廓会立刻把人从「她」拉回「一个 App」"。
     *
     * ⚠️ 两侧**必须长得不一样**（一个字、一个符号）：聊天页里左右两个头像长一样是最糟的情况，
     * 参考为此把「她」和「我」拆成了两个组件。
     */
    private View avatar(int iconKind) {
        FrameLayout box = new FrameLayout(this);
        int s = Theming.dp(this, 28);
        box.setMinimumWidth(s);
        box.setMinimumHeight(s);
        Theming.tag(box, Theming.ROLE_ROUND);

        Icon ic = new Icon(this, iconKind).size(15);
        Theming.tag(ic, Theming.ROLE_ROUND);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.CENTER;
        box.addView(ic, ilp);
        return box;
    }

    private static String labelOf(String kind) {
        if ("thinking".equals(kind)) return "思考： ";
        if ("note".equals(kind)) return "说明： ";
        if ("tool".equals(kind)) return "工具： ";
        if ("phase".equals(kind)) return "阶段： ";
        if ("worker".equals(kind)) return "子代理： ";
        if ("error".equals(kind)) return "错误： ";
        return "· ";
    }

    // ---------------------------------------------------------------- 吸底
    private void updateAtBottom() {
        View child = scroller.getChildAt(0);
        if (child == null) return;
        int diff = child.getBottom() - (scroller.getHeight() + scroller.getScrollY());
        boolean now = diff <= 40;
        if (now != atBottom) {
            atBottom = now;
            jumpButton.setVisibility(atBottom ? View.GONE : View.VISIBLE);
        }
    }

    private void scrollToBottom() {
        scroller.post(new Runnable() {
            @Override
            public void run() {
                View child = scroller.getChildAt(0);
                if (child != null) {
                    // ⚠ 不能用 fullScroll(FOCUS_DOWN)：它滚到的是**被聚焦的那个子视图**，
                    // 一轮里子视图一多，它就只挪一小段（2026-10-05 真机复现：点「回到最新」
                    // 只挪约一行、按钮一直在，用户报"点了又弹回去"）。直接滚到内容底端，
                    // ScrollView 会自己夹到最大滚动量。
                    scroller.scrollTo(0, child.getHeight());
                }
                jumpButton.setVisibility(View.GONE);
                updateAtBottom();          // 用真实滚动位置复算一次，别靠假设
            }
        });
    }

    // ---------------------------------------------------------------- 小工具
    private void addEvent(Transcript.Event e) {
        synchronized (events) {
            events.add(e);
        }
    }

    /**
     * 撤掉一条事件 —— 发送失败时把那条乐观气泡收回来。
     *
     * 乐观气泡（先把用户那句挂上去、不等服务端确认）本身是对的：不发就看不见自己说了什么。
     * 但失败时必须收回 —— 留着它就是"界面说发出去了、其实没发"。
     */
    private void removeEvent(Transcript.Event e) {
        if (e == null) return;
        synchronized (events) {
            events.remove(e);
        }
    }

    private void notice(String s) {
        synchronized (notices) {
            notices.add(s);
        }
    }

    /**
     * notice 之后**立刻**把提示渲染出来。
     *
     * {@link #notice(String)} 只把文本入列表、**不触发渲染**（见 renderConversation 注释）——
     * 于是凡"用户点一下 / 敲一条命令就指望当场看到结果"的路径，都必须走这个变体；
     * 否则提示会一直积压到下一次渲染（可能很久）才冒出来，用户看到的就是"没反应"。
     * 这正是 2026-10-05 修过的那个坑（runApiCommand 结果从不显示）的同类 —— 本次把
     * handleCommand 各分支与四张 chip 弹层的回调一并补齐，别再有下一个。
     */
    private void noticeNow(String s) {
        notice(s);
        renderConversation();
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF8));
        String l;
        while ((l = br.readLine()) != null) sb.append(l).append('\n');
        br.close();
        return sb.toString();
    }

    private static String head(String s, int n) {
        return s == null ? "(null)" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    // ---------------------------------------------------------------- 顶栏常驻状态栏

    /**
     * 拉取六项状态并重画顶栏 chips。
     *
     * 数据源（都已在真夹具上验过）：
     *   - 当前会话项（GET /sessions 里按 {@link #liveSessionId} 挑）：model / reasoningEffort /
     *     contextTokens / contextWindow / domainGlyph / domain
     *   - GET /cache/usage → totals.hitRate（缓存命中率）
     *   - GET /config/approval → approval（权限模式）
     *   - GET /config/default-domain → domains[]（星域 id → 中文名）
     *
     * 全都拉不到时 chips 为空、整行收起 —— 不显示 "null"，也不显示假的 0%。
     */
    private void refreshStatusBar() {
        if (!AppRuntime.isServeReady()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String sessions = httpGetBody("/sessions");
                String approvalBody = httpGetBody("/config/approval");
                String domains = httpGetBody("/config/default-domain");
                String defaultModel = httpGetBody("/config/default-model");
                String providers = httpGetBody("/config/providers");

                SessionList.Item cur = pickCurrent(SessionList.parse(sessions));

                // 模型名取**当前生效模型**（切完模型立刻反映），不是会话项里那个 ——
                // 会话项的 model 是会话创建时定下的，永远不会跟着切。
                // 只显示 model id（deepseek-v4-flash），不带 provider 前缀 —— 状态栏一行放不下。
                List<Providers.Provider> ps = Providers.parse(providers);
                Providers.Model cm = Providers.currentModel(ps, Providers.defaultModel(defaultModel));
                final String model = cm == null ? "" : cm.id;

                // 缓存命中率取**最近 3 轮**（会话级 cockpit 快照），
                // 不是 /cache/usage 的项目累计 —— 后者反映不到"当前这轮"。
                String cockpit = cur == null
                        ? null : httpGetBody("/sessions/" + cur.id + "/cockpit");
                final String hitRate = StatusBar.recentHitRate(cockpit);

                final int ctxTokens = cur == null ? 0 : cur.contextTokens;
                final int ctxWindow = cur == null ? 0 : cur.contextWindow;

                // 留给 chip 点击用（换模型 / 看上下文剩余）
                lastProviders = ps;
                lastModelId = model;
                lastCtxTokens = ctxTokens;
                lastCtxWindow = ctxWindow;
                // 「还没有会话」与「有会话但没匹配上」是两回事 —— 2026-10-05 分开：
                //   · 前者（还没发第一句、没有会话 id）→ 用默认值（档位「自动」、星域取
                //     服务端默认），这是用户要的"默认"；
                //   · 后者（有 id 却在 /sessions 里找不到）→ 如实显示 `—`。
                // 以前两种都显示成默认值 —— 于是"改了档位没生效"与"没读到"在界面上一模一样，
                // 用户分不清，看日志的人也分不清。
                final boolean noSession = liveSessionId == null || liveSessionId.isEmpty();

                final String glyph = cur == null ? "" : cur.domainGlyph;
                final String domId = StatusBar.domainIdFor(noSession,
                        cur == null ? "" : cur.domain, domains);
                final String domName = StatusBar.domainName(domains, domId);
                final String approval =
                        approvalBody == null ? "" : MiniJson.str(approvalBody, "approval");
                // 留给弹层标「（当前）」用（见 lastApprovalMode / lastDomainId 的字段注释）
                lastApprovalMode = approval;
                lastDomainId = domId;
                // 档位就在当前会话项里（SessionList 早就解析好了，只是从前没往状态行传）。
                // 判据与星域一致：没有会话才用默认「自动」，有会话却读不到就显示 `—`。
                final String effort = StatusBar.effortFor(noSession,
                        cur == null ? "" : cur.reasoningEffort);
                final long elapsed = currentElapsedMs();

                // 把「界面接下来会显示什么」记一笔 —— 这是回答"我改了怎么没变"的关键：
                // 它同时给出界面显示值与读到的会话项，两者一对就能分清"没生效"还是"没读到"
                //（2026-10-05 就是卡在这个分不清上，只能靠来回猜）。
                Diagnostics.note("statusbar: effort=" + effort + " domain=" + domName
                        + " session=" + (cur == null ? "（没匹配到）" : cur.id)
                        + " liveId=" + (noSession ? "（还没有）" : liveSessionId));

                final List<StatusBar.Chip> chips = StatusBar.build(
                        model, hitRate, ctxTokens, ctxWindow,
                        elapsed, approval, effort, glyph, domName);

                final int pending = cur == null ? 0 : cur.pendingApprovals;

                postUi(new Runnable() {
                    @Override
                    public void run() {
                        renderChips(chips);
                        renderApprovalBar(pending);
                    }
                });
            }
        }, "tianshu-statusbar");
        t.setDaemon(true);
        t.start();
    }

    /** 本轮已用时（毫秒）。空闲时返回上一轮的用时，让 chip 不至于闪成 0。 */
    private long currentElapsedMs() {
        long start = turnStartAt;
        return start == 0 ? 0 : System.currentTimeMillis() - start;
    }

    /**
     * 从会话列表里挑出**当前这条**。
     *
     * 判据收在 {@link SessionList#pickById}（可测）：**匹配不到就 null，不退回最新一条**。
     * 退回的代价是新建会话（id 还没拿到）时状态行显示**别人那条会话**的上下文/星域/档位。
     */
    private SessionList.Item pickCurrent(List<SessionList.Item> items) {
        return SessionList.pickById(items, liveSessionId);
    }

    /** 把一组 chip 贴上去（**自动换行**）；没有数据时给占位 —— 整行**永不消失**。 */
    private void renderChips(List<StatusBar.Chip> chips) {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();

        // 常驻：还没有数据 / 还没建会话时给一枚占位，而不是把整行收起来
        // （用户反馈：新对话的时候也要在，不能进行对话的时候才出来）
        List<StatusBar.Chip> show = (chips == null || chips.isEmpty())
                ? StatusBar.placeholder(AppRuntime.isServeReady())
                : chips;

        // 2026-10-04 用户第 7 次反馈这一排（原话：「给我…加个图标…我怎么看着那一排是一个圈套
        //   一个圈呢，只要里面那个圈就可以了…这样就可以完全显示一排…不空不挤不少元素」）。
        // 定案（用户选了 A+B）：
        //   A) **去掉 chip 外层那个圆角"框"** —— 之前每枚是一圈浅框，一排看过去像"圈套圈"；
        //      现在只留「圆形图标 + 文字」，框没了，整排更干净、也更省地方。
        //   B) **每枚都补上图标**（缓存=闪电、上下文=仪表盘，其余照旧），没数据时不再只剩一个 `—`。
        // 七枚仍然恒常驻、仍然固定一行、仍靠自适应字号塞下（前几轮的结论一个字没推翻）。
        int hPad = Theming.dp(this, Ui.S1);         // 没有框了，内边距收到最小
        int vPad = Theming.dp(this, Ui.S1);
        int iconDp = 14;
        int iconGap = Theming.dp(this, Ui.S1);
        int chipGap = Theming.dp(this, Ui.S1);      // 弹性空隙收成 0 时的最小枚间距

        // 字号**自适应**：按真实字体度量算出"能装进一行"的字号（上限 Ui.LABEL，下限 9sp）。
        int available = getResources().getDisplayMetrics().widthPixels - Theming.dp(this, Ui.S6) * 2;
        float size = fitChipSize(show, available, hPad, iconDp, iconGap, chipGap);

        for (StatusBar.Chip c : show) {
            // 计时那枚**不在这排里** —— 它在上面那张居中卡片上（用户 2026-10-04 要求挪出去）
            if ("elapsed".equals(c.kind)) {
                setElapsedText(c.label());
                continue;
            }
            // 一枚 chip =（可选）圆形图标 + 文字。**不再铺浅框**（那个"外圈"正是"圈套圈"的来源）。
            // 图标由自绘 Icon 画（不是字符）—— 字符靠系统字体渲染，覆盖只有 1 个字体（见坑 58）。
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(hPad, vPad, hPad, vPad);

            if (c.icon != 0) {
                Icon ic = new Icon(this, c.icon).size(iconDp);
                Theming.tag(ic, Theming.ROLE_ACCENT);   // 图标走主色，跟文字拉开层次
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ilp.rightMargin = iconGap;
                chip.addView(ic, ilp);
            }

            TextView tv = new TextView(this);
            tv.setText(c.label());
            tv.setTextSize(size);
            tv.setMaxLines(1);
            tv.setSingleLine(true);
            Theming.tag(tv, Theming.ROLE_PLAIN);
            chip.addView(tv);

            // 点得动：换模型 / 看缓存 / 看峰闲 / 看上下文 / 换审批 / 换星域
            chip.setOnClickListener(chipClick(c));
            // 点得动的前提是**手指够得着**：显式 clickable + 给足最小高度。
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setMinimumHeight(Theming.dp(this, Ui.TOUCH_MIN));
            chip.setBackground(chipRipple());   // 框没了，按下仍要有回应

            LinearLayout.LayoutParams chipLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp2.leftMargin = chipGap;   // 空隙收成 0 时留一道最小的缝，多枚才不至于糊成一团
            // chip 之间的间距 —— 用一个**弹性空隙**（weight=1）而不是固定 margin：
            //   · 内容不足一行时，空隙把剩余宽度**均分掉** → 第一枚贴左、最后一枚贴右，
            //     整排铺满（用户：「不觉得右边有点空吗…做到不空」）；
            //   · 内容刚好或超出时，空隙自动收成 0，chip 不被挤压（「不挤」）；
            //   · 全程没有横滑（「不用滑动」）。
            //   ⚠️ chip 自身的内边距一个字没动 —— 只是把"右侧那块白"分给了每个空隙。
            if (chipsRow.getChildCount() > 0) {
                View spacer = new View(this);
                chipsRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
            chipsRow.addView(chip, chipLp2);
        }
        Theming.applyTree(this, chipsRow);          // 刚建的 view 要再走一遍角色贴色
    }

    /**
     * 「天枢在等你批准」那一条：有待审批才显示，没有就收起。
     *
     * 文案在 {@link StatusBar#pendingApprovalNote}（纯逻辑、有断言）—— 那句里必须带上
     * 「App 里没法批，去终端处理」，否则用户只会看到界面不动、不知道下一步该干什么。
     */
    private void renderApprovalBar(int pending) {
        if (approvalBar == null) return;
        String note = StatusBar.pendingApprovalNote(pending);
        if (note.isEmpty()) {
            approvalBar.setVisibility(View.GONE);
            return;
        }
        approvalBar.setText(note);
        approvalBar.setVisibility(View.VISIBLE);
    }

    /**
     * 这一排 chip 该用多大字号，才**装得进一行**。
     *
     * 用 `Paint.measureText` 走**真实字体度量** —— 前几轮我按"11sp 约 5.5dp/字符"估过两次，
     * 两次都偏了（一次判"放不下"其实未必，一次算出"288dp 够"却被用户说挤）。这次不再拍脑袋。
     *
     * 逐 0.5sp 降档试算：上限 {@link Ui#LABEL}、下限 9sp。到下限还装不下就认了 ——
     * 宁可略微溢出，也不再折行 / 横滑（那两条用户都明确否过）。
     *
     * @param availablePx 这一行的可用宽（屏宽 − 页面左右边距）
     */
    private float fitChipSize(List<StatusBar.Chip> chips, int availablePx,
                              int hPad, int iconDp, int iconGap, int chipGap) {
        if (chips == null || chips.isEmpty() || availablePx <= 0) return Ui.LABEL;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float iconPx = Theming.dp(this, iconDp) + iconGap;   // 图标 + 它与文字之间的缝
        float sp = Ui.LABEL;
        while (sp > 9f) {
            p.setTextSize(sp * scaled);
            float total = 0;
            for (int i = 0; i < chips.size(); i++) {
                StatusBar.Chip c = chips.get(i);
                total += hPad * 2f + (i > 0 ? chipGap : 0)
                        + (c.icon != 0 ? iconPx : 0)
                        + p.measureText(c.label());
            }
            if (total <= availablePx) return sp;
            sp -= 0.5f;
        }
        return 9f;
    }

    /** 更新「当前会话用时」卡片上的文字（统一加前缀，见两个调用点）。 */
    private void setElapsedText(String el) {
        if (elapsedChipView != null) elapsedChipView.setText("当前会话用时：" + el);
    }

    /** chip 的按下波纹（透明底，只有波纹）—— 外框拿掉了，但按下仍需有回应。 */
    private android.graphics.drawable.Drawable chipRipple() {
        try {
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x1A000000), null, null);
        } catch (Throwable t) {
            return null;   // 这版机器用不了也不影响功能
        }
    }

    // ---------------------------------------------------------------- chip 点击（真机反馈 #15）

    private View.OnClickListener chipClick(final StatusBar.Chip c) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 记一笔"用户点了哪一枚"。2026-10-05 的教训：用户报"改了思考强度没变"，
                // 而 trace.log 里只有页面切换 —— 完全看不出他点过什么、点了之后发生了什么。
                // 这类时间线只能事先埋，事后补不出来。
                Diagnostics.note("chip tap: " + c.kind + " (" + c.label() + ")");
                if ("model".equals(c.kind)) showModelSheet();
                else if ("effort".equals(c.kind)) showEffortSheet();
                else if ("cache".equals(c.kind)) showCacheInfo(c);
                else if ("context".equals(c.kind)) showContextInfo();
                else if ("permission".equals(c.kind)) showApprovalSheet();
                else if ("domain".equals(c.kind)) showDomainSheet();
                else if ("hint".equals(c.kind)) {
                    // 占位那枚（还没数据时唯一的 chip）点一下说清这行是干什么的 ——
                    // 否则用户点它同样"有涟漪、没反应"，和刚才那个档位问题一模一样。
                    noticeNow("这一行是运行状态；有数据后每一枚都能点开看详情。");
                }
                // 其余 kind（如 elapsed 已挪到上方卡片）没有动作 —— 但这不该发生：
                // 每加一种 chip 都得同时想清楚"点了做什么"，否则就是一枚假按钮。
            }
        };
    }

    /**
     * ⚡：**当前会话**的缓存命中率（最近 3 轮的窗口，不是项目累计）。
     *
     * 用弹层而不是往对话里插一条提示 —— 提示条插在正文区，刚点完 chip 往往看不到
     * （用户要的是「点一下就能看到」）。
     */
    private void showCacheInfo(StatusBar.Chip c) {
        ActionSheet.show(this,
                "缓存命中率 " + c.text + "\n\n"
                        + "这是**当前会话**最近 3 轮的命中率：上一轮的提示词前缀被缓存命中，那部分输入 token 不重复计费。\n"
                        + "越高越省。切模型、切星域都会重建前缀 —— 下一轮会掉一次。",
                new String[]{"知道了"}, null);
    }

    /** ◧：这个对话的上下文用了多少、还剩多少，以及该不该 /handoff 再 /new。 */
    private void showContextInfo() {
        String body;
        if (lastCtxWindow <= 0) {
            body = "上下文占用\n\n这条会话还没跑过一轮，暂时没有数。";
        } else {
            int pct = StatusBar.contextPercent(lastCtxTokens, lastCtxWindow);
            int left = Math.max(0, lastCtxWindow - lastCtxTokens);
            body = "上下文 " + pct + "%\n\n已用 " + lastCtxTokens + " / " + lastCtxWindow
                    + " tokens\n还剩约 " + left + " tokens\n\n"
                    + (pct >= 80
                    ? "已经偏满：建议先 /handoff 把交接写下来，再 /new 开一条新会话 —— 下一轮不用背着整段历史。"
                    : "还宽裕，不用急着开新会话。");
        }
        ActionSheet.show(this, body, new String[]{"知道了"}, null);
    }

    /** 模型 chip：换模型，并通向「思考强度」。 */
    private void showModelSheet() {
        final List<Providers.Provider> ps = lastProviders;
        if (ps == null || ps.isEmpty()) {
            noticeNow("还没拿到模型清单 —— 稍后再点，或去「设置 → 模型与服务商」看");
            return;
        }
        // ids 存 **provider:modelId**（不是裸 modelId）—— 无会话时要把这个值写给
        // `/config/default-model`，而那个接口认的就是这个完整形状（模型页也这么写）。
        final List<String> ids = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        for (Providers.Provider p : ps) {
            for (Providers.Model m : p.models) {
                ids.add(p.name + ":" + m.id);
                labels.add(m.id + (m.id.equals(lastModelId) ? "   （当前）" : ""));
            }
        }
        final int effortAt = labels.size();
        labels.add("思考强度…");
        ActionSheet.show(this, "切换模型", labels.toArray(new String[labels.size()]),
                new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        if (index == effortAt) {
                            showEffortSheet();
                            return;
                        }
                        final String full = ids.get(index);           // provider:modelId
                        final String modelId = full.substring(full.indexOf(':') + 1);
                        if (modelId.equals(lastModelId)) {
                            noticeNow("已经是 " + modelId + " 了");
                            return;
                        }
                        String sid = liveSessionId;
                        if (sid == null || sid.isEmpty()) {
                            // 还没建会话 —— 改**新对话的默认**。实测（容器里真 harness）：
                            // PUT /config/default-model 返 200。原先这里只会回一句
                            // 「先发一句话建立会话」，用户看到的就是「切不了模型」——
                            // 而刚打开 App 时 liveSessionId 必然是 null，等于这枚永远点不动。
                            RuntimeApi.put("/config/default-model",
                                    "{\"defaultModel\":\"" + CommandApi.json(full) + "\"}",
                                    new RuntimeApi.Cb() {
                                        @Override
                                        public void ok(String b) {
                                            noticeNow("已把 " + modelId + " 设为新对话的默认");
                                            refreshStatusBar();
                                        }

                                        @Override
                                        public void fail(String m) {
                                            noticeNow("改不动：" + m);
                                        }
                                    });
                            return;
                        }
                        RuntimeApi.post("/sessions/" + sid + "/model",
                                "{\"modelId\":\"" + CommandApi.json(modelId) + "\"}", new RuntimeApi.Cb() {
                                    @Override
                                    public void ok(String b) {
                                        noticeNow("已切到 " + modelId);
                                        refreshStatusBar();
                                    }

                                    @Override
                                    public void fail(String m) {
                                        noticeNow("切换失败：" + m);
                                    }
                                });
                    }
                });
    }

    /** 思考强度 —— 用户问的「在哪切思考强度」的答案就是这个入口。 */
    private void showEffortSheet() {
        final String[] levels = {"off", "low", "medium", "high", "max", "auto"};
        final String[] labels = {"关", "低", "中", "高", "最高", "自动"};
        ActionSheet.show(this, "思考强度（推理档位）", labels, new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        String sid = liveSessionId;
                        if (sid == null || sid.isEmpty()) {
                            // 档位**没有**全局默认 —— 实测：/config/default-effort 是 404，
                            // 往 default-model 里塞 defaultEffort 也会被忽略（回读仍是 null）。
                            // 它天生是**每条对话自己的**设置，所以这里只能把话说准，
                            // 不能像模型/星域那样改默认值，更不能假装改成功了。
                            noticeNow("思考强度是每条对话自己的 —— 发一句话建立会话后就能改");
                            Diagnostics.note("effort：被拦下（还没有会话）");
                            return;
                        }
                        final String lv = levels[index];
                        RuntimeApi.post("/sessions/" + sid + "/effort",
                                "{\"effort\":\"" + lv + "\"}", new RuntimeApi.Cb() {
                                    @Override
                                    public void ok(String b) {
                                        Diagnostics.note("effort -> " + lv + "：成功");
                                        noticeNow("思考强度已设为「" + labels[index] + "」");
                                        refreshStatusBar();
                                    }

                                    @Override
                                    public void fail(String m) {
                                        Diagnostics.note("effort -> " + lv + "：失败 " + m);
                                        noticeNow("改不动：" + m);
                                    }
                                });
                    }
                });
    }

    /**
     * 审批模式 —— 用户问的「监督 / 全自动」在这里切。
     *
     * 选项取值**必须**是 harness 认的那些。2026-10-05 核出这里原先写了一个不存在的
     * {@code "auto"}（服务端只认 manual / suggest / auto-safe / auto-accept /
     * dangerously-skip-permissions），「监督」那一项还实际发的是 {@code manual} ——
     * 点完状态行显示「手动」，跟刚点的字对不上。
     *
     * 现在取值与显示都来自 {@link StatusBar#APPROVAL_MODES} 一处 —— 不会再分道，
     * 且由 HostTest 盯着「可选值里不许有 harness 不认的」。
     */
    private void showApprovalSheet() {
        final String[] modes = StatusBar.APPROVAL_MODES;
        final String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = StatusBar.approvalLabel(modes[i])
                    + (modes[i].equals(lastApprovalMode) ? "   （当前）" : "");
        }
        ActionSheet.show(this, "审批模式 —— 天枢动手前要不要问你", labels,
                new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        final String mode = modes[index];
                        RuntimeApi.put("/config/approval", "{\"approval\":\"" + mode + "\"}",
                                new RuntimeApi.Cb() {
                                    @Override
                                    public void ok(String b) {
                                        noticeNow("审批模式已设为「" + StatusBar.approvalLabel(mode)
                                                + "」（全局，新会话也生效）");
                                        refreshStatusBar();
                                    }

                                    @Override
                                    public void fail(String m) {
                                        noticeNow("改不动：" + m);
                                    }
                                });
                    }
                });
    }

    /** 星域：清单来自 `/config/default-domain`，切的是**当前会话**，并先讲清代价。 */
    private void showDomainSheet() {
        // 不再要求"先有会话" —— 没有会话时改的是**新对话的默认**（实测：这个接口返 200）。
        // 原先这里直接回一句「先发一句话建立会话，再切星域」，而刚打开 App 时
        // liveSessionId 必然是 null —— 这枚 chip 于是永远点不动，用户看到的就是「切不了星域」。
        final String sid = liveSessionId;
        RuntimeApi.get("/config/default-domain", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                final List<String> ids = new ArrayList<String>();
                final List<String> labels = new ArrayList<String>();   // 干净的名字（确认回话里要用）
                final List<String> display = new ArrayList<String>();  // 弹层里显示的名字（可带「（当前）」）
                for (String pair : StatusBar.domainPairs(body)) {
                    int bar = pair.indexOf('|');
                    String did = bar < 0 ? pair : pair.substring(0, bar);
                    String dname = bar < 0 ? pair : pair.substring(bar + 1);
                    ids.add(did);
                    labels.add(dname);
                    // 标出当前星域 —— 16 项里当前项常排在首屏之外，不标就得靠猜
                    display.add(dname + (did.equals(lastDomainId) ? "   （当前）" : ""));
                }
                if (ids.isEmpty()) {
                    noticeNow("星域清单是空的 —— 读不到就不猜");
                    return;
                }
                final boolean hasSession = sid != null && !sid.isEmpty();
                ActionSheet.show(ChatActivity.this,
                        hasSession ? "切换星域（对当前会话生效）"
                                : "切换星域（设为新对话的默认）",
                        display.toArray(new String[display.size()]), new ActionSheet.OnPick() {
                            @Override
                            public void pick(int index) {
                                final String id = ids.get(index);
                                final String name = labels.get(index);
                                // 代价先讲清楚：切星域会重建提示词前缀，本轮缓存吃不到
                                ActionSheet.show(ChatActivity.this,
                                        "切到「" + name + "」？\n"
                                                + "星域决定天枢的思考姿态。切换后本会话的提示词前缀会重建 —— "
                                                + "下一轮的缓存命中率会掉一次（token 会多花一点）。",
                                        new String[]{"确认切换"}, new ActionSheet.OnPick() {
                                            @Override
                                            public void pick(int i) {
                                                if (sid != null && !sid.isEmpty()) {
                                                    RuntimeApi.post("/sessions/" + sid + "/domain",
                                                            "{\"key\":\"" + CommandApi.json(id) + "\"}",
                                                            new RuntimeApi.Cb() {
                                                                @Override
                                                                public void ok(String b) {
                                                                    noticeNow("已切到「" + name + "」");
                                                                    refreshStatusBar();
                                                                }

                                                                @Override
                                                                public void fail(String m) {
                                                                    noticeNow("切换失败：" + m);
                                                                }
                                                            });
                                                    return;
                                                }
                                                // 还没建会话 → 设为**新对话的默认**星域
                                                RuntimeApi.put("/config/default-domain",
                                                        "{\"defaultDomain\":\"" + CommandApi.json(id) + "\"}",
                                                        new RuntimeApi.Cb() {
                                                            @Override
                                                            public void ok(String b) {
                                                                noticeNow("已把「" + name + "」设为新对话的默认星域");
                                                                refreshStatusBar();
                                                            }

                                                            @Override
                                                            public void fail(String m) {
                                                                noticeNow("改不动：" + m);
                                                            }
                                                        });
                                            }
                                        });
                            }
                        });
            }

            @Override
            public void fail(String m) {
                noticeNow("读不到星域清单：" + m);
            }
        });
    }

    /**
     * 打一次 GET，返回响应体；非 200 或异常返回 null。
     *
     * 与 {@link RuntimeApi} 的区别：这里是**顶栏装饰性**的一次拉取，失败就当"没有这项"，
     * 不该弹错、也不该打断别的流程 —— 所以吞掉异常返回 null，而不是把错误抛给界面。
     */
    private String httpGetBody(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(AppRuntime.baseUrl() + path).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            int code = c.getResponseCode();
            if (code != 200) return null;
            return readAll(c.getInputStream());
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void status(final String s) {
        postUi(new Runnable() {
            @Override
            public void run() {
                statusLine.setText(s);
            }
        });
    }
}
