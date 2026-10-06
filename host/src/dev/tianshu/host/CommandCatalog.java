package dev.tianshu.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令目录：把 `data/commands.txt`（104 条斜杠命令）与 `data/components.json`
 * （UI 落点注册表）读进内存，并在 Java 侧复算**硬不变量 C** —— UI 组件与命令双向覆盖。
 *
 * 不变量 C（来自已批准方案）：
 *   - 正向：每个 UI 组件的 `cmd` 必须在命令注册表里 —— 否则就是「凭空发明的按钮」
 *   - 反向：每条命令至少有一个落点，或显式登记为「移动端不适用」并给理由
 *   - 禁止孤儿：两个方向都不许漏网
 *
 * 纯逻辑，不碰 Android —— 所以能在容器里用 HostTest 拿**真实数据**当基准跑断言。
 * 不依赖 org.json（HostTest 无 android.jar），走自带的 {@link Json}。
 */
public final class CommandCatalog {

    private CommandCatalog() {
    }

    /**
     * 落点面 key → 中文名。键必须与 components.json 的 surface key 逐字一致。
     *
     * 放在这里（而不是各 Activity 里各抄一份）：对话页的命令抽屉与命令面板页
     * 都要显示这套分组名，抄两份迟早会漂；放纯逻辑类里还能被 HostTest 断言。
     */
    private static final Map<String, String> SURFACE_ZH = new LinkedHashMap<String, String>();

    static {
        SURFACE_ZH.put("topbar", "顶栏");
        SURFACE_ZH.put("composer", "输入区");
        SURFACE_ZH.put("messageActions", "消息流内动作");
        SURFACE_ZH.put("panels", "面板页");
        SURFACE_ZH.put("settings", "设置页");
        SURFACE_ZH.put("automatic", "自动行为（无 UI，系统内部触发）");
    }

    /** 落点面中文名；未知 key 原样返回（不静默丢）。 */
    public static String surfaceZh(String key) {
        String zh = SURFACE_ZH.get(key);
        return zh == null ? key : zh;
    }

    /** 已知落点面的 key 列表（按展示顺序）—— 断言与排序用。 */
    public static List<String> surfaceKeys() {
        return new ArrayList<String>(SURFACE_ZH.keySet());
    }

    /**
     * 按输入给出命令建议 —— 输入框里打出 "/" 时弹出来的那份候选。
     *
     * 匹配按优先级两轮：
     *   1. **前缀命中**（"/se" → "/sessions"）：用户明确在往下打；
     *   2. **词内命中**（"/session" 也能找到 "/sessions"）：记不清完整名字时更宽容。
     * 前缀那批整体排在词内那批之前，同一批里保持命令表原顺序（稳定，候选不跳位）。
     *
     * 输入不以 "/" 开头 → 空清单：那是普通聊天，不该弹建议。
     * 纯逻辑，可断言。
     */
    public static List<String> suggest(String input, List<Command> commands, int max) {
        List<String> out = new ArrayList<String>();
        if (input == null || commands == null || max <= 0) return out;
        String q = input.trim();
        if (q.isEmpty() || q.charAt(0) != '/') return out;
        String needle = q.substring(1).toLowerCase();

        if (needle.isEmpty()) {
            // 只打了一个 "/" —— 先把命令表原样摆出来（截到 max）
            for (Command c : commands) {
                if (out.size() >= max) break;
                out.add(c.cmd);
            }
            return out;
        }

        for (Command c : commands) {
            if (out.size() >= max) break;
            if (c.cmd.length() > 1 && c.cmd.substring(1).toLowerCase().startsWith(needle)) {
                out.add(c.cmd);
            }
        }
        for (Command c : commands) {
            if (out.size() >= max) break;
            String name = c.cmd.substring(1).toLowerCase();
            if (!name.startsWith(needle) && name.contains(needle) && !out.contains(c.cmd)) {
                out.add(c.cmd);
            }
        }
        return out;
    }

    /** 一条斜杠命令。`flags` 是描述前的前导标记（如 `{F5} [core]`），可为空串。 */
    public static final class Command {
        public final String cmd;
        public final String desc;
        public final String flags;

        Command(String cmd, String desc, String flags) {
            this.cmd = cmd;
            this.desc = desc;
            this.flags = flags;
        }
    }

    /** 一个 UI 落点面（顶栏 / 输入区 / 消息流内动作 / 面板页 / 设置页 / 自动行为）。 */
    public static final class Surface {
        public final String key;
        public final String label;
        public final List<String> items = new ArrayList<String>();

        Surface(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** 双向覆盖检查结果。 */
    public static final class Coverage {
        public final boolean ok;
        public final List<String> orphansInUi;       // 组件引用了不存在的命令
        public final List<String> orphansInCommands; // 命令没有任何落点
        public final List<String> unknownNotApplicable; // 登记不适用但命令不存在
        public final int covered;                    // 有落点的唯一命令数
        public final int commandCount;
        public final int notApplicableCount;

        Coverage(boolean ok, List<String> orphansInUi, List<String> orphansInCommands,
                 List<String> unknownNotApplicable, int covered, int commandCount,
                 int notApplicableCount) {
            this.ok = ok;
            this.orphansInUi = orphansInUi;
            this.orphansInCommands = orphansInCommands;
            this.unknownNotApplicable = unknownNotApplicable;
            this.covered = covered;
            this.commandCount = commandCount;
            this.notApplicableCount = notApplicableCount;
        }
    }

    /** 载入后的完整目录。 */
    public static final class Catalog {
        public final List<Command> commands;
        public final Map<String, Command> byCmd;
        public final List<Surface> surfaces;
        public final Map<String, String> notApplicable;
        public final Coverage coverage;

        Catalog(List<Command> commands, List<Surface> surfaces, Map<String, String> na) {
            this.commands = commands;
            this.surfaces = surfaces;
            this.notApplicable = na;
            this.byCmd = new LinkedHashMap<String, Command>();
            for (Command c : commands) byCmd.put(c.cmd, c);
            this.coverage = verify(commands, surfaces, na);
        }

        /** 取某条命令的说明；没有就返回空串（界面别去 NPE）。 */
        public String descOf(String cmd) {
            Command c = byCmd.get(cmd);
            return c == null || c.desc == null ? "" : c.desc;
        }
    }

    /** 从两份文本载入目录。任一为空都能降级成空目录，不抛。 */
    public static Catalog load(String commandsText, String componentsJson) {
        return new Catalog(parseCommands(commandsText), parseSurfaces(componentsJson),
                parseNotApplicable(componentsJson));
    }

    // ---------------------------------------------------------------- 解析
    /**
     * 解析 commands.txt。格式（实测）：
     *     /cmd  {F5}  [core]  说明文字
     * 描述前可以有 0..n 个 `{…}` / `[…]` 前导标记，这里把它们摘出来单独存。
     */
    public static List<Command> parseCommands(String text) {
        List<Command> out = new ArrayList<Command>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("/")) continue;             // 跳过表头与空行

            int sp = 0;
            while (sp < line.length() && !isSpace(line.charAt(sp))) sp++;
            String cmd = line.substring(0, sp);
            if (cmd.length() <= 2) continue;                 // 与 coverage.mjs 同规则

            String rest = line.substring(sp).trim();
            String flags = "";
            while (rest.startsWith("{") || rest.startsWith("[")) {
                char close = rest.charAt(0) == '{' ? '}' : ']';
                int e = rest.indexOf(close);
                if (e < 0) break;
                String tok = rest.substring(0, e + 1);
                flags = flags.isEmpty() ? tok : flags + " " + tok;
                rest = rest.substring(e + 1).trim();
            }
            out.add(new Command(cmd, rest, flags));
        }
        return out;
    }

    /** 解析 components.json 的 `surfaces`（保持文件里的出现顺序）。 */
    public static List<Surface> parseSurfaces(String json) {
        List<Surface> out = new ArrayList<Surface>();
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return out;
        Map<String, Object> surfaces = Json.map(root.get("surfaces"));
        if (surfaces == null) return out;

        for (Map.Entry<String, Object> e : surfaces.entrySet()) {
            Map<String, Object> def = Json.map(e.getValue());
            if (def == null) continue;
            String label = Json.str(def.get("label"));
            Surface s = new Surface(e.getKey(), label == null ? e.getKey() : label);
            List<Object> items = Json.list(def.get("items"));
            if (items != null) {
                for (Object o : items) {
                    String cmd = Json.str(o);
                    if (cmd != null) s.items.add(cmd);
                }
            }
            out.add(s);
        }
        return out;
    }

    /** 解析 components.json 的 `notApplicable`（命令 → 理由）。 */
    public static Map<String, String> parseNotApplicable(String json) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return out;
        Map<String, Object> na = Json.map(root.get("notApplicable"));
        if (na == null) return out;
        for (Map.Entry<String, Object> e : na.entrySet()) {
            String reason = Json.str(e.getValue());
            out.put(e.getKey(), reason == null ? "" : reason);
        }
        return out;
    }

    // ---------------------------------------------------------------- 校验
    /** 不变量 C：双向覆盖。任何一处漏网即 ok=false（带清单，不吞）。 */
    public static Coverage verify(List<Command> commands, List<Surface> surfaces,
                                  Map<String, String> notApplicable) {
        Set<String> known = new LinkedHashSet<String>();
        for (Command c : commands) known.add(c.cmd);

        Set<String> declared = new LinkedHashSet<String>();
        List<String> orphansInUi = new ArrayList<String>();
        for (Surface s : surfaces) {
            for (String cmd : s.items) {
                if (!known.contains(cmd) && !orphansInUi.contains(cmd)) orphansInUi.add(cmd);
                declared.add(cmd);
            }
        }

        List<String> orphansInCommands = new ArrayList<String>();
        for (Command c : commands) {
            if (!declared.contains(c.cmd)
                    && !(notApplicable != null && notApplicable.containsKey(c.cmd))) {
                orphansInCommands.add(c.cmd);
            }
        }

        List<String> unknownNa = new ArrayList<String>();
        if (notApplicable != null) {
            for (String k : notApplicable.keySet()) {
                if (!known.contains(k)) unknownNa.add(k);
            }
        }

        boolean ok = orphansInUi.isEmpty() && orphansInCommands.isEmpty() && unknownNa.isEmpty();
        return new Coverage(ok, orphansInUi, orphansInCommands, unknownNa,
                declared.size(), commands.size(),
                notApplicable == null ? 0 : notApplicable.size());
    }

    /** 给界面/日志用的人类可读覆盖报告。 */
    public static String report(Coverage r) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令总数: ").append(r.commandCount).append('\n');
        sb.append("已落点  : ").append(r.covered).append('\n');
        sb.append("不适用  : ").append(r.notApplicableCount).append('\n');
        sb.append("孤儿组件: ").append(r.orphansInUi.isEmpty() ? "无" : join(r.orphansInUi)).append('\n');
        sb.append("孤儿命令: ").append(r.orphansInCommands.isEmpty() ? "无" : join(r.orphansInCommands)).append('\n');
        sb.append("结论    : ").append(r.ok ? "GREEN" : "RED");
        return sb.toString();
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
