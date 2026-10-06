package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 对话页状态栏 —— 对齐 Termux TUI 的状态行。
 *
 * 每一项都对着 harness 的**实现**做（dist/main.js），不是照符号猜：
 *
 * <pre>
 *   deepseek-v4-flash  自动  ⚡83%  ◧3%  0s      ▸ 全自动
 * </pre>
 *
 * <ul>
 *   <li>模型名 —— **当前生效模型**（/config/default-model + /config/providers）。
 *       从前取的是会话项的 model，那是会话创建时定下的，切模型不会变。</li>
 *   <li>⚡N% —— **最近 3 轮**的缓存命中率（cockpit 的 recentTurnHitRate，比例 0..1）；
 *       不是 /cache/usage 的项目累计。</li>
 *   <li>◧N% —— 上下文占用（已用 / 上限）。</li>
 *   <li>Ns —— 本轮已用时。</li>
 *   <li>▸ 全自动 —— 审批模式（/config/approval）。</li>
 *   <li>☥ 启明 —— 星域徽记（会话项 domainGlyph）+ 中文名（/config/default-domain）。</li>
 * </ul>
 *
 * 纯逻辑、不碰 Android —— 所以能拿真夹具、固定时钟当基准跑断言。
 * 缺数据的项**直接跳过**，绝不产出 "null" 或假的 0%。
 */
public final class StatusBar {

    private StatusBar() {
    }

    /**
     * 一个 chip：语义 + 图标 + 主体文字。
     *
     * 第三轮改：`glyph`（一个字符）换成了 `icon`（{@link Icon} 常量）。原来那套符号
     *（`⚡`/`◷`/`◧`/`▸`）每个都只有 **1 个**系统字体覆盖 —— 在用的这台机器上画得出来，
     * 换个 ROM 就可能集体变成豆腐块。自绘图标没有这个依赖。
     *
     * 注：{@code Icon.XXX} 是 `static final int`，**编译期就内联**了，所以本类不会因此去加载
     * android 类 —— 它得能被 HostTest 直接断言（见类注释）。
     */
    public static final class Chip {
        public final String kind;    // model/cache/peak/context/elapsed/permission/domain
        /** {@link Icon} 常量；0 = 没有图标。 */
        public final int icon;
        public final String text;    // 主体文字

        Chip(String kind, int icon, String text) {
            this.kind = kind;
            this.icon = icon;
            this.text = text == null ? "" : text;
        }

        /** 文字部分 —— 图标由界面侧单独画（不再拼进字符串里）。 */
        public String label() {
            return text;
        }
    }

    // ---------------------------------------------------------------- 上下文

    /**
     * 上下文占用百分比：contextTokens / contextWindow，四舍五入到整数。
     *
     * 算不出时返回 -1（不是 0，也不抛除零）—— 界面据此**跳过该 chip**，
     * 免得把一个假的 "0%" 当成"上下文是空的"。
     */
    public static int contextPercent(int contextTokens, int contextWindow) {
        if (contextWindow <= 0 || contextTokens < 0) return -1;
        return (int) Math.round(contextTokens * 100.0 / contextWindow);
    }

    /** 上下文占用的显示文本（"3%"）；算不出返回空串。 */
    public static String contextText(int contextTokens, int contextWindow) {
        int p = contextPercent(contextTokens, contextWindow);
        return p < 0 ? "" : p + "%";
    }

    // ---------------------------------------------------------------- 缓存命中率

    /**
     * 命中率原文（**比例**，如 "0.83"）→ 显示文本（"83%"）。
     *
     * harness 里 `hitRate = hits / total`，是比例不是百分数；TUI 取的是**最近 3 轮**
     * （`getRecentTurnHitRate(3)`）。取不到 / null / 负数一律返回空串（界面跳过该项）。
     */
    public static String cacheText(String rawRatio) {
        if (rawRatio == null) return "";
        String raw = rawRatio.trim();
        if (raw.isEmpty() || "null".equals(raw)) return "";
        double v;
        try {
            v = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return "";
        }
        if (v < 0) return "";
        return Math.round(v * 100.0) + "%";
    }

    /** 从 cockpit 快照里取「最近命中率」原文；取不到返回 null。 */
    public static String recentHitRate(String cockpitJson) {
        return Transcript.rawValue(cockpitJson, "recentTurnHitRate");
    }

    // ---------------------------------------------------------------- 用时

    /** 本轮已用时："0s" / "12s" / "1m23s" / "1h5m"。 */
    public static String elapsedText(long ms) {
        if (ms < 0) return "";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m" + (s % 60) + "s";
        return (m / 60) + "h" + (m % 60) + "m";
    }

    // ---------------------------------------------------------------- 审批模式

    /**
     * harness **真正认**的审批模式取值（数组顺序 = 界面上的呈现顺序，从松到紧）。
     *
     * 2026-10-05 核出对话页的审批选择器写过一个**不存在的** {@code "auto"}，而且
     * 「监督」那一项实际发的是 {@code manual} —— 点完状态行显示「手动」，跟刚点的
     * 字对不上。根因是当初 {@link #approvalLabel} 那张登记表只管"怎么显示"，
     * 没有反过来约束"能写什么"，两边于是各走各的。
     *
     * 现在写与显示共用这一份：选择器照它列选项、{@link #approvalLabel} 照它给中文名，
     * HostTest 断言它里面**不许**再出现 harness 不认的值。
     */
    public static final String[] APPROVAL_MODES = {
            "dangerously-skip-permissions",   // 全自动（不问）
            "auto-accept",                    // 自动·接受
            "auto-safe",                      // 自动·安全
            "suggest",                        // 监督（给建议，要你过目）
            "manual",                         // 手动（每次动手都问）
    };

    /**
     * 「待审批」那一条该说什么 —— **空串表示没有待审批**（界面据此不显示这一条）。
     *
     * 为什么需要它：**App 里没有批准入口**。2026-10-05 逐条核过：
     *   - 源码里搜 `pendingApprovals` 零命中（服务端响应明明带着这个字段，App 从没解析）；
     *   - 路由探针里没有任何 approvals 端点；
     *   - 在 App 的对话通道里发 `/permission status` 实测返回 `Unknown slash command`。
     *
     * 于是审批模式一旦落到「手动」，天枢会停在半路等批准，而用户在 App 里**没有地方点**
     * —— 界面看起来就是"它不动了"。这条提示至少把话说清楚：它在等，但得去终端处理。
     */
    public static String pendingApprovalNote(int pending) {
        if (pending <= 0) return "";
        return "天枢在等你批准 " + pending + " 项 —— App 里没法批，"
                + "请到终端（Termux 里的天枢）处理。";
    }

    /**
     * 权限模式 → 显示名。对齐 TUI（`▸ 全自动`）。
     * 服务端真值见 fixtures/api/config__approval.json：`dangerously-skip-permissions`。
     * 未知值原样回显。
     */
    /**
     * 审批模式 → 中文。
     *
     * harness 的取值一共**五个**（从 dist 里逐个抠出来的字面量）：
     * {@code manual} / {@code suggest} / {@code auto-safe} / {@code auto-accept} /
     * {@code dangerously-skip-permissions}。
     *
     * 2026-10-05 修：原先只映射了三个，而且其中一个认的是 {@code "auto"} ——
     * **harness 根本没有这个值**。于是 {@code suggest} 被原样显示成英文
     * （真机截图里那枚 `[盾牌] suggest` 就是它），{@code auto-safe} /
     * {@code auto-accept} 同理。未登记的取值一律**原样返回**是这里的老规矩
     *（宁可露出英文，也不要编一个错的中文），但前提是**登记表要跟得上 harness**。
     *
     * 2026-10-05 用户定名：{@code suggest} 那档该叫**监督**（他按终端里的叫法认的）。
     * 因此 {@code manual} 相应改叫**手动** —— 两个都是"要人过目"的档，
     * 但既然 harness 把它们列成两个取值，界面上就不该同叫一个名字，
     * 否则切到另一个时用户分不清现在到底是哪个。
     */
    public static String approvalLabel(String approval) {
        if (approval == null) return "";
        String a = approval.trim();
        if (a.isEmpty()) return "";
        if ("dangerously-skip-permissions".equals(a)) return "全自动";
        if ("suggest".equals(a)) return "监督";
        if ("manual".equals(a)) return "手动";
        if ("auto-safe".equals(a)) return "自动·安全";
        if ("auto-accept".equals(a)) return "自动·接受";
        return a;
    }

    /**
     * 推理档位 → 中文。取值域见 {@code SessionList.Item.reasoningEffort}
     * （{@code off/low/medium/high/max/auto}，注释里写明的）。
     *
     * 未登记的取值同样原样返回 —— 不猜、不兜底成"中"。
     */
    public static String effortLabel(String effort) {
        if (effort == null) return "";
        String e = effort.trim();
        if (e.isEmpty()) return "";
        if ("off".equals(e)) return "关";
        if ("low".equals(e)) return "低";
        if ("medium".equals(e)) return "中";
        if ("high".equals(e)) return "高";
        if ("max".equals(e)) return "最高";
        if ("auto".equals(e)) return "自动";
        return e;
    }

    /**
     * 档位没数据时用的**默认档位**。用户 2026-10-05 定：「思考档位默认自动」。
     *
     * 会话项里 {@code reasoningEffort} 给不出值时（还没建会话 / 该字段缺），状态行显示它 ——
     * 而不是留一枚 {@code —}。{@code —} 只说明"没读到"，说不清"那本该是什么"；
     * 而这一排是**常驻**的（用户要求新对话时也在），常驻的一排更不该有一格是"不知道"。
     */
    public static final String DEFAULT_EFFORT = "auto";

    /** 会话没给档位就回退默认档位（null / 空 / 纯空白都算没给）。 */
    public static String effortOrDefault(String effort) {
        return notBlank(effort) ? effort.trim() : DEFAULT_EFFORT;
    }

    /**
     * 状态行上「档位」那枚到底该显示什么 —— **必须区分两种情况**（2026-10-05 修）：
     *
     * <ul>
     *   <li>{@code noSession=true} —— 刚打开 App、还没发第一句，**根本没有会话 id**：
     *       给默认档位（{@code auto} → 「自动」）。这是用户要的那个"默认"。</li>
     *   <li>{@code noSession=false} —— **有**会话 id，只是这一拍没在 `/sessions` 里匹配到
     *      （会话刚建、列表还没刷新、或它被归档了）：返回**空串**，界面显示 {@code —}。</li>
     * </ul>
     *
     * 为什么非分不可：原先两种情况都落到默认值，于是「改了档位却没生效」和
     * 「压根没读到」在界面上**长得一模一样** —— 用户分不清，看图的人也分不清。
     * 真机上就是这么卡住的（用户 2026-10-05：「思考强度为什么我改了之后还是没变」）。
     */
    public static String effortFor(boolean noSession, String sessionEffort) {
        if (noSession) return DEFAULT_EFFORT;
        return notBlank(sessionEffort) ? sessionEffort.trim() : "";
    }

    /**
     * 状态行上「星域」那枚该用哪个 id —— 判据与 {@link #effortFor} 完全一致：
     * 没有会话就用服务端默认星域；**有**会话却读不到就给空串（界面显示 {@code —}），
     * 不再拿默认值把那件事盖过去。
     */
    public static String domainIdFor(boolean noSession, String sessionDomain, String domainJson) {
        if (noSession) return defaultDomainId(domainJson);
        return notBlank(sessionDomain) ? sessionDomain.trim() : "";
    }

    // ---------------------------------------------------------------- 星域

    /**
     * 星域没数据时用的**默认星域 id**。用户 2026-10-05 定：「对话星域默认启明」。
     *
     * 正常走 {@link #defaultDomainId}（读服务端那份 {@code defaultDomain}，真机上是
     * {@code qiming}）；这个常量只在**那份响应也拉不到**时兜底 —— 否则运行环境刚起、
     * 配置还没读到的那几秒，状态行会留一枚 {@code —}。
     */
    public static final String DEFAULT_DOMAIN = "qiming";

    /**
     * {@code /config/default-domain} 里的 {@code defaultDomain}（如 {@code qiming}）。
     * 拉不到 / 解析不出 / 为空 → {@link #DEFAULT_DOMAIN}。
     */
    public static String defaultDomainId(String domainJson) {
        if (domainJson != null && !domainJson.isEmpty()) {
            Map<String, Object> root = Json.map(Json.parse(domainJson));
            if (root != null) {
                String d = Json.str(root.get("defaultDomain"));
                if (notBlank(d)) return d.trim();
            }
        }
        return DEFAULT_DOMAIN;
    }

    /**
     * 状态行该显示哪个星域：**当前会话有就用会话的**，没有就用默认星域。
     *
     * 抽成纯逻辑，是因为这条"会话优先、否则回落默认"的规则要能在 HostTest 里钉住 ——
     * 它决定那一排星域 chip 到底显示什么（用户要的默认是「启明」）。
     */
    public static String domainIdOr(String sessionDomain, String domainJson) {
        if (notBlank(sessionDomain)) return sessionDomain.trim();
        return defaultDomainId(domainJson);
    }

    /**
     * 星域 id → 中文名。映射来自 `GET /config/default-domain` 的 `domains[]`
     * （`{"id":"qiming","name":"启明",…}`）。找不到就**原样返回 id**。
     *
     * 注意：该响应里**没有** glyph —— 徽记（☥）只从当前会话项的 `domainGlyph` 取。
     */
    public static String domainName(String domainJson, String id) {
        if (id == null || id.isEmpty()) return "";
        if (domainJson == null || domainJson.isEmpty()) return id;
        Map<String, Object> root = Json.map(Json.parse(domainJson));
        if (root == null) return id;
        List<Object> ds = Json.list(root.get("domains"));
        if (ds != null) {
            for (Object o : ds) {
                Map<String, Object> m = Json.map(o);
                if (m == null) continue;
                if (id.equals(Json.str(m.get("id")))) {
                    String n = Json.str(m.get("name"));
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        }
        return id;
    }

    /**
     * 从 `/config/default-domain` 的响应里取出全部星域，元素形如 `"qiming|启明"`（id|名字）。
     *
     * 抽成纯逻辑是为了可测：对话页的「点星域 chip 换星域」要用这份清单，
     * 而那段代码在 Activity 里（HostTest 碰不到）。解析放这儿，界面只负责画。
     */
    public static List<String> domainPairs(String domainJson) {
        List<String> out = new ArrayList<String>();
        if (domainJson == null || domainJson.isEmpty()) return out;
        Map<String, Object> root = Json.map(Json.parse(domainJson));
        if (root == null) return out;
        List<Object> ds = Json.list(root.get("domains"));
        if (ds == null) return out;
        for (Object o : ds) {
            Map<String, Object> m = Json.map(o);
            if (m == null) continue;
            String id = Json.str(m.get("id"));
            if (id == null || id.isEmpty()) continue;
            String n = Json.str(m.get("name"));
            out.add(id + "|" + (n == null || n.isEmpty() ? id : n));
        }
        return out;
    }

    // ---------------------------------------------------------------- 拼装

    /**
     * 拼成 chips，顺序对齐 TUI：模型 · 缓存 · 峰/闲 · 上下文 · 用时 · 审批 · 星域。
     *
     * 任一数据缺失（null / 空串 / 算不出）就**跳过该 chip** —— 界面宁可少一个 chip，
     * 也不能出现 "null" 或假的 "0%"。
     */
    public static List<Chip> build(String model,
                                   String cacheRatioRaw,
                                   int contextTokens, int contextWindow,
                                   long elapsedMs,
                                   String approval,
                                   String effort,
                                   String domainGlyph, String domainName) {
        List<Chip> out = new ArrayList<Chip>();

        // 2026-10-04：**七枚恒常驻**（用户原话：「就算没对话也要显示对话后才显示的东西」）。
        // 原先是"任一数据缺失就跳过该 chip"，于是新会话进来只剩零零几枚 ——
        // 用户既不知道这一行本该有什么，也看不到自己关心的那一项。
        // 缺数据一律用 `—` 占位：**不用假数字**，`0%` 会被读成"真的是 0"，那比缺一项更误导
        //（本类开头那条老注释说的就是这个；"有值就用值、没值就明说没有"是这两者的分界）。
        out.add(new Chip("model", Icon.STAR, notBlank(model) ? model : "未选模型"));

        // 档位（推理强度）—— 数据**一直在** GET /sessions 的会话项里（reasoningEffort），
        // 只是没接过来。用户反馈「也没有什么档位呀」说的就是这枚缺失的 chip。
        out.add(new Chip("effort", Icon.LEVEL, notBlank(effort) ? effortLabel(effort) : "—"));

        String cache = cacheText(cacheRatioRaw);
        out.add(new Chip("cache", Icon.BOLT, cache.isEmpty() ? "—" : cache));

        String ctx = contextText(contextTokens, contextWindow);
        out.add(new Chip("context", Icon.GAUGE, ctx.isEmpty() ? "—" : ctx));

        String el = elapsedText(elapsedMs);
        out.add(new Chip("elapsed", Icon.PULSE, el.isEmpty() ? "0s" : el));

        String ap = approvalLabel(approval);
        out.add(new Chip("permission", Icon.SHIELD, ap.isEmpty() ? "—" : ap));

        // 星域 chip 的图标**固定用自绘徽记** —— 服务端那份 `domainGlyph` 是个字符，
        // 正是上一轮要消灭的东西（同样是"靠字体碰运气"）；缺名字时文字用占位。
        out.add(new Chip("domain", Icon.BRAND, notBlank(domainName) ? domainName : "—"));

        return out;
    }

    /**
     * 状态行上**有动作**的 chip 类型（点下去会开对应的面板 / 说一句）。
     *
     * 为什么把它列成常量并要求断言：2026-10-05 真机问「模型档位为什么选不了」——
     * 档位那枚是新加的，而 `chipClick` 是**按 kind 分支**的，新 kind 落不到任何分支，
     * 于是点下去有涟漪、之后什么都没有 —— 一枚"假按钮"。
     *
     * 有了这份清单，HostTest 就能盯住「{@link #build} 产出的每个 kind 都得在这儿」，
     * 以后再加 chip 忘了接线会立刻红，而不是等用户去点。
     */
    public static final java.util.List<String> CLICKABLE_KINDS = java.util.Arrays.asList(
            "model", "effort", "cache", "context", "permission", "domain", "hint");

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ---------------------------------------------------------------- 常驻与换行

    /**
     * 没有任何数据时也要给一枚 chip —— 状态行**常驻**（用户反馈：新对话的时候也要在，
     * 不能进行对话的时候才出来）。
     *
     * 为什么不是「没数据就整行收起」：那样新会话进来是一片空，用户既不知道这行是干什么的，
     * 也不知道它什么时候会冒出来。占位文案顺手告诉他下一步做什么。
     */
    public static List<Chip> placeholder(boolean ready) {
        List<Chip> out = new ArrayList<Chip>(1);
        out.add(new Chip("hint", 0,
                ready ? "说一句就开始 · 这行是运行状态" : "运行环境准备中…"));
        return out;
    }

    /**
     * 一排 chip 按容器宽度换行，返回**每行放几枚**。
     *
     * 抽成纯逻辑是因为换行测量最容易出边界错：算漏一枚、或者「单枚比容器还宽」时
     * 算成 0 会死循环。规则：第一个不计 gap；放不下就换行；单枚超宽也独占一行。
     */
    public static int[] wrapRows(int[] itemWidths, int maxWidth, int gap) {
        if (itemWidths == null || itemWidths.length == 0) return new int[0];
        List<Integer> rows = new ArrayList<Integer>();
        int count = 0;
        int used = 0;
        for (int w : itemWidths) {
            int need = (count == 0) ? w : gap + w;
            if (count > 0 && used + need > maxWidth) {
                rows.add(count);
                count = 0;
                used = 0;
                need = w;                 // 换行后第一个不计 gap
            }
            used += need;
            count++;
        }
        if (count > 0) rows.add(count);
        int[] out = new int[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i);
        return out;
    }
}
