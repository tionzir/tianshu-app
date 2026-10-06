package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;

/**
 * 硬不变量 A：**答案永远在本轮最底**。
 *
 * 一轮 = 〔过程〕+〔结果〕。过程（思考、工具、相位、感知）可以长、可以折；
 * 结果（答案）必须在这一轮的最底 —— 用户不需要往上翻去找。
 *
 * 规则逐条移植自离线靶子 `src/transcript.mjs`（Wave 1 已 GREEN 的那份）：
 *   1. 有文本时，最后一块一定是 answer；
 *   2. 一轮里最多一个 answer；
 *   3. 最后一段**连续**文本才是答案，之前穿插的文本并入活动区（过程说明）；
 *   4. 全程没有文本时，不产生空 answer 块。
 *
 * 纯逻辑、不碰 Android —— 所以能拿真实 SSE 报文（fixtures/sse-real-sample.txt）当基准跑断言。
 */
public final class Transcript {

    private Transcript() {
    }

    // ---------------------------------------------------------------- 事件
    /** 一条 Stream-JSON 事件。字段来自 SSE 信封里嵌套的 `data` 对象。 */
    public static final class Event {
        public String type;
        public String text;        // text_delta / thinking_delta / user
        public String id;          // tool_use / tool_result
        public String name;        // tool_use / tool_result
        public String input;       // tool_use 的参数（原始 JSON 文本，可空）
        public String result;      // tool_result 的结果文本
        public boolean isError;
        public boolean truncated;
        public String phase;       // phase
        public String tool;        // phase
        public String reason;      // phase
        public String error;       // error
        public String raw;         // 原始载荷（worker 之类用）
    }

    /** 从 SSE 的（事件名，载荷）建一条事件。取不到的字段留 null。 */
    public static Event fromSse(String event, String payload) {
        Event e = new Event();
        e.type = event;
        e.raw = payload;
        if (payload == null) return e;

        if ("text_delta".equals(event) || "thinking_delta".equals(event)
                || "user".equals(event)) {
            e.text = MiniJson.str(payload, "text");
        } else if ("tool_use".equals(event)) {
            e.id = MiniJson.str(payload, "id");
            e.name = MiniJson.str(payload, "name");
            e.input = rawValue(payload, "input");
        } else if ("tool_result".equals(event)) {
            e.id = MiniJson.str(payload, "id");
            e.name = MiniJson.str(payload, "name");
            e.result = MiniJson.str(payload, "result");
            e.isError = boolValue(payload, "isError");
            e.truncated = boolValue(payload, "truncated");
        } else if ("phase".equals(event)) {
            e.phase = MiniJson.str(payload, "phase");
            e.tool = MiniJson.str(payload, "tool");
            e.reason = MiniJson.str(payload, "reason");
        } else if ("error".equals(event)) {
            e.error = MiniJson.str(payload, "error");
        } else if ("worker".equals(event)) {
            e.name = MiniJson.str(payload, "name");
        }
        return e;
    }

    // ---------------------------------------------------------------- 块
    /** 活动区里的一个子项。`text` 已经是可直接显示的文本。 */
    public static final class Child {
        public final String kind;   // thinking / note / tool / phase / worker / error
        public final String text;
        public final boolean isError;

        Child(String kind, String text, boolean isError) {
            this.kind = kind;
            this.text = text;
            this.isError = isError;
        }
    }

    /** 一个显示块：`activity`（过程，默认折叠）或 `answer`（结果，永远置底）。 */
    public static final class Block {
        public String kind;
        public boolean collapsed;
        public int thinking;
        public int tools;
        public int workers;
        public final List<Child> children = new ArrayList<Child>();
        public String text;         // answer 的正文

        /** 活动区标题用："思考 2 · 工具 5 · 相位 3" 之类。 */
        public String summary() {
            List<String> parts = new ArrayList<String>();
            if (thinking > 0) parts.add("思考 " + thinking);
            if (tools > 0) parts.add("工具 " + tools);
            if (workers > 0) parts.add("子代理 " + workers);
            int phases = 0, notes = 0, errors = 0;
            for (Child c : children) {
                if ("phase".equals(c.kind)) phases++;
                else if ("note".equals(c.kind)) notes++;
                else if ("error".equals(c.kind)) errors++;
            }
            if (phases > 0) parts.add("阶段 " + phases);
            if (notes > 0) parts.add("过程说明 " + notes);
            if (errors > 0) parts.add("错误 " + errors);
            if (parts.isEmpty()) return "活动";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(parts.get(i));
            }
            return sb.toString();
        }
    }

    /** 一轮对话的显示视图。 */
    public static final class TurnView {
        public String userText;                  // 可空
        public final List<Block> blocks = new ArrayList<Block>();

        public Block answer() {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                if ("answer".equals(blocks.get(i).kind)) return blocks.get(i);
            }
            return null;
        }

        public Block activity() {
            for (Block b : blocks) {
                if ("activity".equals(b.kind)) return b;
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 分桶
    /**
     * 把一轮的事件序列分桶成显示块。语义与 `src/transcript.mjs` 的 parseTurn 一致。
     */
    public static List<Block> parseTurn(List<Event> events) {
        List<StringBuilder> thinkingSegments = new ArrayList<StringBuilder>();
        List<StringBuilder> textRuns = new ArrayList<StringBuilder>();
        List<Event> tools = new ArrayList<Event>();          // 合并后的工具条目
        List<Child> phases = new ArrayList<Child>();
        List<Child> workers = new ArrayList<Child>();
        List<Child> errors = new ArrayList<Child>();

        StringBuilder openThinking = null;
        StringBuilder openText = null;

        for (Event e : events == null ? new ArrayList<Event>() : events) {
            String t = e.type == null ? "" : e.type;

            if ("text_delta".equals(t)) {
                openThinking = null;
                if (openText == null) {
                    openText = new StringBuilder();
                    textRuns.add(openText);
                }
                if (e.text != null) openText.append(e.text);
                continue;
            }
            // 任何非 text_delta 都终止当前文本段
            openText = null;

            if ("thinking_delta".equals(t)) {
                if (openThinking == null) {
                    openThinking = new StringBuilder();
                    thinkingSegments.add(openThinking);
                }
                if (e.text != null) openThinking.append(e.text);
            } else if ("tool_use".equals(t)) {
                openThinking = null;
                Event rec = new Event();
                rec.type = "tool";
                rec.id = e.id;
                rec.name = e.name;
                rec.input = e.input;
                tools.add(rec);
            } else if ("tool_result".equals(t)) {
                openThinking = null;
                Event hit = findTool(tools, e.id);
                if (hit != null) {
                    hit.result = e.result;
                    hit.isError = e.isError;
                    hit.truncated = e.truncated;
                    if (hit.name == null) hit.name = e.name;
                } else {
                    Event rec = new Event();
                    rec.type = "tool";
                    rec.id = e.id;
                    rec.name = e.name;
                    rec.result = e.result;
                    rec.isError = e.isError;
                    rec.truncated = e.truncated;
                    tools.add(rec);
                }
            } else if ("phase".equals(t)) {
                openThinking = null;
                String label = e.phase == null ? "?" : e.phase;
                if (e.tool != null && e.tool.length() > 0) label = e.tool + " " + label;
                if (e.reason != null && e.reason.length() > 0) label = label + " —— " + e.reason;
                phases.add(new Child("phase", label, false));
            } else if ("worker".equals(t)) {
                openThinking = null;
                workers.add(new Child("worker", e.name == null ? "子代理" : e.name, false));
            } else if ("error".equals(t)) {
                openThinking = null;
                errors.add(new Child("error", e.error == null ? "错误" : e.error, true));
            } else {
                // turn_complete / result / system / 未知类型：只作为分段边界
                openThinking = null;
            }
        }

        // 最后一段**非空**文本才是答案
        StringBuilder last = textRuns.isEmpty() ? null : textRuns.get(textRuns.size() - 1);
        String answerText = (last != null && last.toString().trim().length() > 0)
                ? last.toString() : null;
        List<StringBuilder> noteRuns = new ArrayList<StringBuilder>(textRuns);
        if (answerText != null && !noteRuns.isEmpty()) {
            noteRuns.remove(noteRuns.size() - 1);
        }

        List<Block> blocks = new ArrayList<Block>();

        boolean hasActivity = !thinkingSegments.isEmpty() || !tools.isEmpty()
                || !workers.isEmpty() || !phases.isEmpty() || !errors.isEmpty()
                || !noteRuns.isEmpty();

        if (hasActivity) {
            Block act = new Block();
            act.kind = "activity";
            act.collapsed = true;
            act.thinking = thinkingSegments.size();
            act.tools = tools.size();
            act.workers = workers.size();
            for (StringBuilder s : thinkingSegments) act.children.add(new Child("thinking", s.toString(), false));
            for (StringBuilder s : noteRuns) act.children.add(new Child("note", s.toString(), false));
            for (Event tool : tools) act.children.add(new Child("tool", toolText(tool), tool.isError));
            act.children.addAll(phases);
            act.children.addAll(workers);
            act.children.addAll(errors);
            blocks.add(act);
        }

        // 关键：答案永远追加在最后
        if (answerText != null) {
            Block ans = new Block();
            ans.kind = "answer";
            ans.text = answerText;
            blocks.add(ans);
        }
        return blocks;
    }

    /**
     * 把整段事件流切成若干轮（以 `turn_complete` 收尾），再各轮分桶。
     * 没有块的空轮（例如只有 done 的尾帧）会被丢掉。
     */
    public static List<TurnView> parseConversation(List<Event> events) {
        List<TurnView> out = new ArrayList<TurnView>();
        if (events == null) return out;

        List<Event> cur = new ArrayList<Event>();
        for (Event e : events) {
            if (e == null) continue;
            cur.add(e);
            if ("turn_complete".equals(e.type)) {
                addTurn(out, cur);
                cur = new ArrayList<Event>();
            }
        }
        if (!cur.isEmpty()) addTurn(out, cur);
        return out;
    }

    private static void addTurn(List<TurnView> out, List<Event> events) {
        List<Block> blocks = parseTurn(events);
        if (blocks.isEmpty()) return;                    // 空轮不入列

        TurnView tv = new TurnView();
        for (Event e : events) {
            if ("user".equals(e.type) && e.text != null && e.text.length() > 0) {
                tv.userText = e.text;                    // 取最后一个 user
            }
        }
        tv.blocks.addAll(blocks);
        out.add(tv);
    }

    // ---------------------------------------------------------------- 吸底
    /**
     * 打开这一轮时无需滚动就能看到的块 —— 视口对齐到底部，所以是**末尾** capacity 个。
     * （不变量 A 的可验证形式：答案必须在这几个里。）
     */
    public static List<Block> visibleOnOpen(List<Block> blocks, int capacity) {
        List<Block> out = new ArrayList<Block>();
        if (blocks == null || capacity <= 0) return out;
        int from = Math.max(0, blocks.size() - capacity);
        for (int i = from; i < blocks.size(); i++) out.add(blocks.get(i));
        return out;
    }

    /** 吸底决策：默认吸底；用户上滑后停止（不抢），并浮出「回到最新」。 */
    public static final class ScrollPolicy {
        public final boolean autoScroll;
        public final boolean showJumpButton;
        public final boolean highlightJump;

        ScrollPolicy(boolean a, boolean b, boolean c) {
            this.autoScroll = a;
            this.showJumpButton = b;
            this.highlightJump = c;
        }
    }

    public static ScrollPolicy scrollPolicy(boolean atBottom, boolean hasNewAnswer) {
        return new ScrollPolicy(atBottom, !atBottom, !atBottom && hasNewAnswer);
    }

    // ---------------------------------------------------------------- 内部
    private static Event findTool(List<Event> tools, String id) {
        if (id == null) return null;
        for (Event t : tools) {
            if (id.equals(t.id)) return t;
        }
        return null;
    }

    /** 工具条目的显示文本：名字 + 参数（截断）+ 结果（截断）。 */
    private static String toolText(Event t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.name == null ? "工具" : t.name);
        if (t.input != null && t.input.length() > 0 && !"null".equals(t.input)) {
            sb.append(' ').append(truncate(oneLine(t.input), 120));
        }
        if (t.result != null && t.result.length() > 0) {
            sb.append(" → ").append(truncate(oneLine(t.result), 300));
        }
        if (t.isError) sb.append("  [错误]");
        if (t.truncated) sb.append("  [已截断]");
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 取某个键的**原始 JSON 值文本**（对象/数组原样返回，字符串带引号）。
     * MiniJson 只认 `"k":"v"` 与 `"k":123`，取不了 `"input":{...}` 这种。
     */
    static String rawValue(String json, String key) {
        if (json == null || key == null) return null;
        int k = json.indexOf("\"" + key + "\":");
        if (k < 0) return null;
        int j = k + key.length() + 3;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;

        char c = json.charAt(j);
        if (c == '{' || c == '[') {
            char open = c;
            char close = c == '{' ? '}' : ']';
            int depth = 0;
            boolean inStr = false;
            for (int e = j; e < json.length(); e++) {
                char ch = json.charAt(e);
                if (inStr) {
                    if (ch == '\\') e++;
                    else if (ch == '"') inStr = false;
                } else if (ch == '"') {
                    inStr = true;
                } else if (ch == open) {
                    depth++;
                } else if (ch == close) {
                    depth--;
                    if (depth == 0) return json.substring(j, e + 1);
                }
            }
            return null;
        }
        int e = j;
        while (e < json.length() && ",}]".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(j, e).trim();
    }

    private static boolean boolValue(String json, String key) {
        String v = rawValue(json, key);
        return "true".equals(v);
    }
}
