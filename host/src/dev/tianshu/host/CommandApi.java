package dev.tianshu.host;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 已接上的 `/` 命令 → **一次 HTTP 调用**（纯逻辑）。
 *
 * 为什么要有它：harness 的命令面里，大量命令的语义是**会话级动作**（TUI 里直连
 * `ctx.agent.*` 完成）。HTTP 侧没有"执行斜杠命令"的通用入口，但**动作各有自己的端点**
 * （2026-10-02 从 dist 注册表 `chunk-DXY5P57D.js` 的 `"METHOD /path": withAuthN(handler)`
 * 逐条对上，body schema 取自 handler 源码）。
 *
 * 只放**纯映射**：给命令名、参数、当前会话 id，算出该打哪个方法 / 路径 / 体。
 * 真正发请求在 {@link ChatActivity}（走 {@link RuntimeApi}）。分开是为了让
 * "哪条命令打哪个端点"能被 HostTest 直接断言，不必起服务。
 *
 * 关于拼 JSON：`/goal` `/scout` 会夹带用户自由文本，走 {@link #json} 转义；
 * 其余参数只可能是枚举字面量或 slug（已白名单化）。
 */
public final class CommandApi {

    /** 本类接管（已从"拦住"升级为"真打接口"）的命令。 */
    private static final Set<String> HANDLED = new HashSet<String>(Arrays.asList(
            // 会话模式 / 生命周期
            "/ask", "/plan-mode", "/effort", "/compact", "/fork", "/fast", "/queue",
            // 自主目标
            "/goal", "/goal-status", "/goal-pause", "/goal-resume", "/goal-cancel",
            // 计划
            "/plan-list", "/plan-view", "/plan-approve", "/plan-reject",
            // 回退
            "/rewind", "/rollback",
            // 交接 / 编排 / 遥测
            "/handoff", "/scout", "/team-resume", "/sensorium",
            // 会话级只读面板（结果由 ApiResult 摊成人话贴进对话）
            "/cockpit", "/skill", "/prefix-budget", "/goal-criteria", "/undo",
            // 环境 / 账号 / 全局配置
            "/doctor", "/python", "/login", "/logout", "/yolo", "/yes", "/zen", "/grant"));

    private static final Set<String> EFFORT_LEVELS = new HashSet<String>(Arrays.asList(
            "off", "low", "medium", "high", "max", "auto"));

    private CommandApi() {
    }

    /** 一次 HTTP 调用。`error` 非 null 表示**不该发**，直接把这句话显示给用户。 */
    public static final class Call {
        public final String method;
        public final String path;
        public final String body;   // null = 无请求体
        public final String error;  // 非 null = 不执行

        Call(String method, String path, String body, String error) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.error = error;
        }

        static Call ok(String method, String path, String body) {
            return new Call(method, path, body, null);
        }

        static Call bad(String error) {
            return new Call(null, null, null, error);
        }
    }

    /** 这条命令是不是已经接到了接口上。 */
    public static boolean handles(String cmd) {
        return cmd != null && HANDLED.contains(cmd);
    }

    /** 命令名之后的部分（已 trim）；没有参数返回空串。 */
    public static String argOf(String text, String cmd) {
        if (text == null || cmd == null) return "";
        String t = text.trim();
        if (t.length() <= cmd.length()) return "";
        return t.substring(cmd.length()).trim();
    }

    /**
     * 算出该打哪个接口。返回 null = 本类不管这条命令。
     *
     * `sessionId` 为空的处理是不对称的：`/zen` `/doctor` `/python` `/logout` `/yolo` `/yes`
     * 都是**全局**的（不依赖会话），其余挂在会话上 —— 没有会话就直说，别打一条必 404 的请求。
     */
    public static Call call(String cmd, String arg, String sessionId) {
        if (!handles(cmd)) return null;
        String a = arg == null ? "" : arg.trim();

        // ---------- 全局（不依赖会话） ----------
        if ("/zen".equals(cmd)) {
            if ("status".equals(a)) return Call.ok("GET", "/config/zen", null);
            Boolean on = switchArg(a);
            if (on == null) return Call.bad("用法：/zen on|off|status");
            return Call.ok("PUT", "/config/zen", "{\"enabled\":" + on + "}");
        }
        if ("/doctor".equals(cmd) || "/python".equals(cmd)) {
            return Call.ok("GET", "/environment", null);
        }
        if ("/login".equals(cmd)) {
            return Call.ok("POST", "/account/device", "{}");
        }
        if ("/grant".equals(cmd)) {
            // 列出本工作区已记住的授权。服务端要求 cwd 是它已知的工作区 ——
            // App 里就是绑进来的那个 guest 工作区。
            return Call.ok("GET",
                    "/config/path-grants?cwd=" + RuntimeBinds.GUEST_WORKSPACE, null);
        }
        if ("/logout".equals(cmd)) {
            return Call.ok("POST", "/account/logout", "{}");
        }
        if ("/yolo".equals(cmd) || "/yes".equals(cmd)) {
            // TUI 语义：显式输入即视为确认；`off` 退出。off 一律回到最保守的 manual。
            boolean off = "off".equalsIgnoreCase(a);
            if (!a.isEmpty() && !off && !"on".equalsIgnoreCase(a)) {
                return Call.bad("用法：/" + cmd.substring(1) + " [on|off]");
            }
            String mode = off ? "manual" : "dangerously-skip-permissions";
            return Call.ok("PUT", "/config/approval", "{\"approval\":\"" + mode + "\"}");
        }

        // ---------- 会话级 ----------
        if (sessionId == null || sessionId.isEmpty()) {
            return Call.bad("先在对话里说一句话、建立会话，再执行 " + cmd);
        }
        String base = "/sessions/" + sessionId;

        if ("/ask".equals(cmd)) {
            Boolean on = switchArg(a);
            if (on == null) return Call.bad("用法：/ask on|off");
            return Call.ok("POST", base + "/ask-mode",
                    "{\"state\":\"" + (on ? "asking" : "off") + "\"}");
        }
        if ("/plan-mode".equals(cmd)) {
            Boolean on = switchArg(a);
            if (on == null) return Call.bad("用法：/plan-mode on|off");
            return Call.ok("POST", base + "/plan-mode",
                    "{\"state\":\"" + (on ? "planning" : "off") + "\"}");
        }
        if ("/effort".equals(cmd)) {
            String lv = a.toLowerCase();
            if (!EFFORT_LEVELS.contains(lv)) {
                return Call.bad("用法：/effort off|low|medium|high|max|auto");
            }
            return Call.ok("POST", base + "/effort", "{\"effort\":\"" + lv + "\"}");
        }
        if ("/compact".equals(cmd)) return Call.ok("POST", base + "/compact", null);
        if ("/fork".equals(cmd)) return Call.ok("POST", base + "/fork", "{}");
        if ("/fast".equals(cmd)) return Call.ok("POST", base + "/zen", "{\"action\":\"skip\"}");
        // /queue <文本>：会话正在跑时把一条消息排到下一轮。
        // 从前归 send 原样发 —— 但运行时对 `/queue` 是**恒 400**（`Unknown slash command`，
        // handler 里 resolveAppPromptInput 不认它），于是用户敲它只会拿到报错。
        // 端点一直在：POST /sessions/:id/queue，body 要 `{"text":…}`。
        if ("/queue".equals(cmd)) {
            if (a.isEmpty()) return Call.bad("用法：/queue <要排队的消息>（会话在跑时排到下一轮）");
            return Call.ok("POST", base + "/queue", "{\"text\":\"" + json(a) + "\"}");
        }

        // ---------- 自主目标（goal） ----------
        if ("/goal".equals(cmd)) {
            if (a.isEmpty()) return Call.bad("用法：/goal <目标描述>（看状态用 /goal-status）");
            return Call.ok("POST", base + "/goal", "{\"goal\":\"" + json(a) + "\"}");
        }
        if ("/goal-status".equals(cmd)) return Call.ok("GET", base + "/goal", null);
        if ("/goal-pause".equals(cmd)) return Call.ok("POST", base + "/goal/pause", "{}");
        if ("/goal-resume".equals(cmd)) return Call.ok("POST", base + "/goal/resume", "{}");
        if ("/goal-cancel".equals(cmd)) return Call.ok("POST", base + "/goal/cancel", "{}");

        // ---------- 计划（plan） ----------
        if ("/plan-list".equals(cmd)) return Call.ok("GET", base + "/plans", null);
        if ("/plan-view".equals(cmd)) {
            if (a.isEmpty()) return Call.ok("GET", base + "/plans", null);   // 无参：先列出
            return Call.ok("GET", base + "/plans/" + slug(a), null);
        }
        if ("/plan-approve".equals(cmd)) {
            if (a.isEmpty()) return Call.bad("用法：/plan-approve <计划名>（用 /plan-list 看有哪些）");
            return Call.ok("POST", base + "/plans/" + slug(a) + "/approve", "{}");
        }
        if ("/plan-reject".equals(cmd)) {
            if (a.isEmpty()) return Call.bad("用法：/plan-reject <计划名>（用 /plan-list 看有哪些）");
            return Call.ok("POST", base + "/plans/" + slug(a) + "/reject", "{}");
        }

        // ---------- 回退（只读：列点 / 预览；真正执行需二次确认，另开波） ----------
        if ("/rewind".equals(cmd)) return Call.ok("GET", base + "/rewind-points", null);
        if ("/rollback".equals(cmd)) return Call.ok("GET", base + "/rollback/preview", null);

        // ---------- 交接 / 编排 / 遥测 ----------
        if ("/handoff".equals(cmd)) return Call.ok("POST", base + "/handoff", "{}");
        if ("/scout".equals(cmd)) {
            if (a.isEmpty()) return Call.bad("用法：/scout <侦察目标>");
            return Call.ok("POST", base + "/delegate", "{\"objective\":\"" + json(a) + "\"}");
        }
        if ("/team-resume".equals(cmd)) return Call.ok("GET", base + "/team-checkpoints", null);
        if ("/sensorium".equals(cmd)) return Call.ok("GET", base + "/insights", null);
        // 会话级只读面板。从前它们标成"跳到面板页"，可面板页里并没有这两张卡 ——
        // 跳过去是空的。数据其实就在会话级端点上，直接取回来贴进对话。
        if ("/cockpit".equals(cmd)) return Call.ok("GET", base + "/cockpit", null);
        if ("/skill".equals(cmd)) return Call.ok("GET", base + "/skills", null);
        // P1：前缀预算归因 —— harness 侧新加的端点，读活 agent 的内存态预算
        // （TUI /prefix-budget 的同一数据源）。返回体里的 text 已是排好版的报告。
        if ("/prefix-budget".equals(cmd)) return Call.ok("GET", base + "/prefix-budget", null);
        // P2′：判据就在既有的 goal 快照里（successCriteria），不必新开端点。
        if ("/goal-criteria".equals(cmd)) return Call.ok("GET", base + "/goal", null);
        // P2′：文件历史快照（可撤销点）。只列不回退 —— 回退是工作区写操作，留在 TUI。
        if ("/undo".equals(cmd)) return Call.ok("GET", base + "/undo-history", null);

        return null;
    }

    /** on/off（宽容大小写）。其余返回 null。 */
    private static Boolean switchArg(String a) {
        if ("on".equalsIgnoreCase(a)) return Boolean.TRUE;
        if ("off".equalsIgnoreCase(a)) return Boolean.FALSE;
        return null;
    }

    /** slug 白名单化（计划名会进 URL 路径）。 */
    static String slug(String a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? a : sb.toString();
    }

    /** JSON 字符串转义（`/goal` `/scout` 会夹带用户文本）。 */
    static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 执行成功时给用户看的一句人话。 */
    public static String doneHint(String cmd) {
        if ("/ask".equals(cmd)) return "已切换 Ask 模式（只读问答）";
        if ("/plan-mode".equals(cmd)) return "已切换计划模式";
        if ("/effort".equals(cmd)) return "已切换推理强度";
        if ("/compact".equals(cmd)) return "已压缩上下文";
        if ("/fork".equals(cmd)) return "已从当前会话分出新分支";
        if ("/fast".equals(cmd)) return "已跳过禅专注相位";
        if ("/queue".equals(cmd)) return "已排队，等当前这轮跑完就发";
        if ("/zen".equals(cmd)) return "已更新禅模式配置（新会话生效）";
        if ("/goal".equals(cmd)) return "已设定自主目标，天枢开始迭代";
        if ("/goal-status".equals(cmd)) return "目标状态（见下）";
        if ("/goal-pause".equals(cmd)) return "已暂停目标";
        if ("/goal-resume".equals(cmd)) return "已恢复目标";
        if ("/goal-cancel".equals(cmd)) return "已终止目标";
        if ("/handoff".equals(cmd)) return "已发起交接，天枢在写 HANDOFF";
        if ("/plan-list".equals(cmd)) return "本会话的计划（见下）";
        if ("/plan-view".equals(cmd)) return "计划内容（见下）";
        if ("/plan-approve".equals(cmd)) return "已批准计划，开始执行";
        if ("/plan-reject".equals(cmd)) return "已驳回计划";
        if ("/rewind".equals(cmd)) return "可回退的消息点（见下）";
        if ("/rollback".equals(cmd)) return "回滚预览（见下）";
        if ("/scout".equals(cmd)) return "已派出侦察子代理";
        if ("/team-resume".equals(cmd)) return "可恢复的团队检查点（见下）";
        if ("/sensorium".equals(cmd)) return "天枢自感知快照（见下）";
        if ("/cockpit".equals(cmd)) return "驾驶舱：运行时状态（见下）";
        if ("/skill".equals(cmd)) return "本会话已加载的技能（见下）";
        if ("/prefix-budget".equals(cmd)) return "前缀预算归因（见下）";
        if ("/goal-criteria".equals(cmd)) return "目标验收判据（见下）";
        if ("/undo".equals(cmd)) return "文件历史快照（见下；回退动作仍在 TUI）";
        if ("/doctor".equals(cmd)) return "环境健康检查（见下）";
        if ("/python".equals(cmd)) return "Python/uv/Git 环境（见下）";
        if ("/login".equals(cmd)) return "设备码登录：按提示的地址与 code 完成授权";
        if ("/grant".equals(cmd)) return "本工作区已记住的授权（见下）";
        if ("/logout".equals(cmd)) return "已登出天枢账号";
        if ("/yolo".equals(cmd) || "/yes".equals(cmd)) return "已更新审批模式（全局配置）";
        return "已执行 " + cmd;
    }
}
