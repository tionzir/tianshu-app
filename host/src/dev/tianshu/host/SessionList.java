package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;

/**
 * `GET /sessions` 响应的解析与展示。纯逻辑，不碰 Android —— 因此能拿**真实响应**当测试基准。
 *
 * 为什么不用 JSON 库：包体已经 89 MB，而这里只需要"从一个数组里切出若干对象、
 * 再各取几个字段"。为它引依赖不划算，所以自己切。
 *
 * 三个必须做对的点（都有断言）：
 *   1. **切对象要认字符串边界** —— title 里出现 `}` 或 `\"` 不能把对象切错（真实数据里
 *      有中文标题，将来也完全可能有符号）。所以扫描时要跳过字符串内部。
 *   2. **`createdAt` 是 13 位毫秒** —— 用 {@link MiniJson#num}（Integer）取会溢出成 null，
 *      必须用 {@link MiniJson#longNum}。这是本项目里最容易静默出错的一处。
 *   3. 脏输入一律返回空表，不抛异常 —— 列表页宁可空着，也不能因为一条坏数据崩掉。
 */
public final class SessionList {

    private SessionList() {
    }

    /** 列表页要显示的字段。只取用得上的，别把整条响应搬进内存。 */
    public static final class Item {
        public String id;
        public String title;
        public String status;
        public long createdAt;
        public int lastSeq;
        public String domainGlyph;
        public int contextTokens;
        // —— 对话页顶栏状态栏要用的五项（起补解析）——
        // 这些字段**一直都在** GET /sessions 的响应里（见 fixtures/sessions-real.json，
        // 从真机 serve 抓的原始字节），只是从前 Item 没接 —— 于是顶栏显示不出
        // 模型 / 档位 / 上下文，而数据其实已经到手。
        public String model;            // 当前模型 id，如 deepseek-v4-flash
        public String domain;           // 星域 id，如 qiming
        public String domainAccent;     // 星域强调色名（primary…）
        public String reasoningEffort;  // 推理档位：off/low/medium/high/max/auto
        public int contextWindow;       // 上下文窗口（与 contextTokens 一起算占用比）
        /**
         * 是否已归档。实测（2026-10-02，真实 serve）：只有 `GET /sessions?includeArchived=true`
         * 返回的条目带 `"archived":true`，默认列表条目**根本没有这个键** ——
         * 所以缺键时取 false 正好等于「未归档」。
         */
        public boolean archived;
        /**
         * 还有几项在等批准。服务端**一直**在响应里给这个字段（真夹具 sessions-real.json
         * 里就有），只是 App 从来没解析过 —— 于是"天枢卡在等批准"这件事在界面上
         * 一点痕迹都没有（2026-10-05 核出）。
         */
        public int pendingApprovals;
    }

    /** 解析 `{"sessions":[{…},{…}]}`。取不到就返回空表（不抛）。 */
    /**
     * 从列表里挑出 id 精确匹配的那一条；**匹配不到返回 null**。
     *
     * 为什么不退回"最新一条"：2026-10-05 核出对话页原先正是那么回退的，而新建会话时
     * {@code liveSessionId} 还是 null（id 要等发消息的后台线程拿到），于是状态行会拿
     * **别人的会话**来显示 —— 上下文占用、星域、档位全是那一条的，用户看着莫名其妙。
     * 返回 null 时各 chip 走各自的默认值（档位「自动」、星域取服务端默认那个），
     * 比"显示别人的数"诚实。
     */
    public static Item pickById(List<Item> items, String id) {
        if (items == null || items.isEmpty()) return null;
        if (id == null || id.isEmpty()) return null;
        for (Item it : items) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    public static List<Item> parse(String json) {
        List<Item> out = new ArrayList<Item>();
        if (json == null) return out;

        int arr = arrayStart(json, "sessions");
        if (arr < 0) return out;

        int i = arr + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else if (c == ']') {
                break;
            } else if (c != '{') {
                i++;                                  // 容错：数组里混了非对象元素就跳过
            } else {
                int end = matchBrace(json, i);
                if (end < 0) break;                   // 响应被截断：丢掉残条并收工
                out.add(item(json.substring(i, end + 1)));
                i = end + 1;
            }
        }
        return out;
    }

    /**
     * 毫秒时间戳 → **具体时间**（不做相对化）。
     *
     * `今天 06:21` / `10-02 23:11` / `2025-12-31 08:00`（跨年才带年份）。
     *
     * 用户真机反馈：列表里「5 分钟前 / 12 小时前」看不出到底是**什么时候**聊的，
     * 要的是具体时间 —— 所以把相对时间整个换掉（`relTime` 随之删除）。
     * 时钟回拨（then > now）不特殊处理：仍然照实显示那个时刻。
     */
    public static String absTime(long nowMs, long thenMs) {
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.setTimeInMillis(nowMs);
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.setTimeInMillis(thenMs);
        String hm = String.format(java.util.Locale.US, "%02d:%02d",
                t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE));
        boolean sameYear = n.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR);
        boolean sameDay = sameYear
                && n.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return "今天 " + hm;
        if (sameYear) {
            return String.format(java.util.Locale.US, "%02d-%02d %s",
                    t.get(java.util.Calendar.MONTH) + 1, t.get(java.util.Calendar.DAY_OF_MONTH), hm);
        }
        return String.format(java.util.Locale.US, "%04d-%02d-%02d %s",
                t.get(java.util.Calendar.YEAR), t.get(java.util.Calendar.MONTH) + 1,
                t.get(java.util.Calendar.DAY_OF_MONTH), hm);
    }

    /**
     * harness 会话状态 → 界面用中文。**未知值原样回显**（不吞成空串、不编造）。
     *
     * 为什么需要：`GET /sessions` 的 `status` 是 harness 的英文枚举
     * （`completed` / `aborted` / `interrupted` …）。App 界面全中文，而列表条目原来
     * 直接回显这个原文 —— 同一行里 `已归档` 是中文、状态却是英文，读起来自相矛盾。
     * 映射与 {@link StatusBar#approvalLabel} 同一套做法：认得的翻中文，认不得的照抄。
     */
    /**
     * 会话卡片上那行元信息：**绝对时间 + （非 completed 时的）状态 + 归档标记**。
     *
     * 抽成纯逻辑是因为它原先在会话页里就地拼、对话页侧栏又各写一份 —— 两处必然漂
     * （用户反馈「界面有些重复的地方」）。
     */
    public static String metaLine(Item it, long nowMs) {
        StringBuilder sb = new StringBuilder(absTime(nowMs, it.createdAt));
        if (!"completed".equals(it.status)) {
            String st = statusLabel(it.status);      // 英文枚举 → 中文；认不得的原样
            if (st != null && !st.isEmpty()) sb.append("  ·  ").append(st);
        }
        if (it.archived) sb.append("  ·  已归档");
        return sb.toString();
    }

    public static String statusLabel(String status) {
        if (status == null) return "";
        String s = status.trim();
        if (s.isEmpty()) return "";
        if ("completed".equals(s)) return "已完成";
        if ("aborted".equals(s)) return "已中止";
        if ("interrupted".equals(s)) return "已中断";
        if ("failed".equals(s)) return "失败";
        if ("running".equals(s) || "active".equals(s)) return "进行中";
        if ("pending".equals(s)) return "待处理";
        return s;
    }

    // ---------------------------------------------------------------- 列表分段

    /** 会话列表分三段（用户要的分类）。顺序即显示顺序。 */
    public static final String SEC_RUNNING = "会话中";
    public static final String SEC_ACTIVE = "未归档";
    public static final String SEC_ARCHIVED = "已归档";
    public static final String[] SECTION_ORDER = {SEC_RUNNING, SEC_ACTIVE, SEC_ARCHIVED};

    /**
     * 一条会话落在哪一段。
     *
     * **「已归档」优先于「会话中」**：归档中的会话即便 status 还是 running，
     * 也该落在已归档段 —— 否则用户会去「未归档」里找它，找不到。
     */
    public static String sectionOf(Item it) {
        if (it == null) return SEC_ACTIVE;
        if (it.archived) return SEC_ARCHIVED;
        if ("running".equals(it.status) || "active".equals(it.status)) return SEC_RUNNING;
        return SEC_ACTIVE;
    }

    // ---------------------------------------------------------------- 内部
    /** 找 `"key":[` 里 `[` 的下标；键不存在或不是数组返回 -1。包内共用（{@link SessionSearch} 也用它）。 */
    static int arrayStart(String json, String key) {
        int k = json.indexOf("\"" + key + "\":");
        if (k < 0) return -1;
        int j = k + key.length() + 3;
        while (j < json.length() && isSpace(json.charAt(j))) j++;
        return (j < json.length() && json.charAt(j) == '[') ? j : -1;
    }

    /**
     * 从 `{` 出发找配对的 `}`，**跳过字符串内部**。
     *
     * 这一步必须认字符串边界：title 里出现 `}` 或 `\"` 时，天真的"数到下一个 }"会把
     * 对象切短，随后所有字段静默取空 —— 而列表页只会显示成空白，不报错。
     */
    static int matchBrace(String json, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;                              // 跳过被转义的那个字符
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 从单个对象里取字段。缺字段给安全默认值，别留 null 让界面去 NPE。 */
    private static Item item(String one) {
        Item it = new Item();
        it.id = MiniJson.str(one, "id");
        it.title = MiniJson.str(one, "title");
        it.status = MiniJson.str(one, "status");
        it.domainGlyph = MiniJson.str(one, "domainGlyph");
        it.archived = boolVal(one, "archived");
        Long created = MiniJson.longNum(one, "createdAt");
        it.createdAt = created == null ? 0L : created.longValue();
        Integer seq = MiniJson.num(one, "lastSeq");
        it.lastSeq = seq == null ? 0 : seq.intValue();
        Integer tok = MiniJson.num(one, "contextTokens");
        it.contextTokens = tok == null ? 0 : tok.intValue();
        it.model = orEmpty(MiniJson.str(one, "model"));
        it.domain = orEmpty(MiniJson.str(one, "domain"));
        it.domainAccent = orEmpty(MiniJson.str(one, "domainAccent"));
        it.reasoningEffort = orEmpty(MiniJson.str(one, "reasoningEffort"));
        Integer cw = MiniJson.num(one, "contextWindow");
        it.contextWindow = cw == null ? 0 : cw.intValue();
        Integer pa = MiniJson.num(one, "pendingApprovals");
        it.pendingApprovals = pa == null ? 0 : pa.intValue();
        return it;
    }

    /** 缺字段 → 空串（不留 null 让界面 NPE）。 */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 只有字面量 `true` 才算真。缺键（默认列表条目）时 rawValue 给 null → false。
     * 复用 {@link Transcript#rawValue}：它认字符串边界，不会把 `"archived":true` 误判。
     */
    private static boolean boolVal(String json, String key) {
        return "true".equals(Transcript.rawValue(json, key));
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
