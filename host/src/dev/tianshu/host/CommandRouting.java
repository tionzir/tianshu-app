package dev.tianshu.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 104 条 `/` 命令在 App 里的**正确行为**表（纯逻辑）。
 *
 * 关键事实（2026-10-02 实测）：
 * harness 的命令面是**隐式二分**的 —— 本地执行 100 条（TUI 直接调 `ctx.agent.*`）、
 * 交给模型 3 条（`/panel /plan-close /queue`）。而 HTTP 侧只有很窄的解析入口
 * （`POST /sessions/:id/prompt` 经 `resolveAppPromptInput`）。
 *
 * 于是有了用户看到的那件事：在 App 里敲 `/connect` **进了对话** —— App 侧从前不做判断，
 * 原样把 "/connect" 当 prompt 发出去，模型把这行字当请求回答。
 *
 * 这张表的职责：**发送前先判类**。每条命令都必须落到下面六类之一，不留悬案：
 *   {@link #NAV}   跳到对应页面（页面本身就是该命令的落点）
 *   {@link #LOCAL} 本地动作（清屏 / 新会话）
 *   {@link #API}   已接到具体 HTTP 接口 —— 执行细节见 {@link CommandApi}
 *   {@link #SEND}  交给天枢。分两种：
 *                    (a) 服务端自己能解析的（`/plan` `/review` `/council` `/galaxy` `/team` …）；
 *                    (b) **指令桥**（{@link CommandPrompt}）—— 终端本地命令改写成等价 agent 指令
 *                        （`/interview` `/diagram` `/remember` …）。两条都原样发出去即可。
 *   {@link #NONE}  触屏无对应语义，明确说明
 *   {@link #TUI}   终端本地能力且**真的没有移动端等价物**（如 vim 键位、终端滚屏分页）
 */
public final class CommandRouting {

    public static final String NAV = "nav";
    public static final String LOCAL = "local";
    public static final String API = "api";
    public static final String SEND = "send";
    public static final String NONE = "none";
    public static final String TUI = "tui";
    /**
     * 本地读盘（B1）：harness 里读"进程内内存"的命令中，**数据其实已落盘**的那批
     * —— App 作为宿主直接读会话目录，不必改内核。读取与文本化见 {@link LocalData}。
     */
    public static final String READ = "read";

    private CommandRouting() {
    }

    /** 一条命令在 App 里的落点。 */
    public static final class Row {
        public final String cmd;
        public final String action;
        public final String target;
        public final boolean available;

        Row(String cmd, String action, String target, boolean available) {
            this.cmd = cmd;
            this.action = action;
            this.target = target;
            this.available = available;
        }
    }

    // 终端本地能力的统一说明（target 里不许出现 "|"，它是分隔符）
    private static final String TUI_REASON =
            "终端本地能力（agent 进程内完成），App 没有 HTTP 对端";

    // 指令桥的统一说明
    private static final String BRIDGE =
            "指令桥：App 改写成等价 agent 指令再发（见 CommandPrompt）";

    // 格式：命令|动作|落点或理由|是否已落定（1/0）
    private static final String[] DATA = {
            "/ask|api|会话 · Ask 模式（只读问答）|1",
            "/branch|read|会话分支树（本地读盘：扫会话目录各 *.meta.json 的 parentSessionId）|1",
            "/btw|tui|侧问 —— " + TUI_REASON + "|1",
            "/cache|nav|面板页 · 缓存用量|1",
            "/capsule|send|" + BRIDGE + "：星域胶囊注入|1",
            "/cd|none|工作目录由会话创建时决定|1",
            "/chat|none|模式已由消息内容自动检测，无需手动切换。任务脚手架在有明确意图时自动开启。|1",
            "/chronicle|nav|编年史页 · 里程碑编年史（本地读盘：.rivet/constellation.json）|1",
            "/clear|local|清空当前对话显示|1",
            "/cockpit|api|会话 · 驾驶舱（运行时状态；面板页里没有这张卡）|1",
            "/compact|api|会话 · 压缩上下文|1",
            "/config|nav|设置页 · 运行控制台|1",
            "/connect|nav|模型页 · 服务商向导（填密钥/测连通/选模型）|1",
            "/constellation|nav|蓝图页 · 项目骨架与里程碑（本地读盘：.rivet/constellation.json）|1",
            "/context|read|上下文账本（本地读盘：meta.json + 会话 frames.jsonl）|1",
            "/council|send|交给天枢：议事会（运行时解析成工作流 prompt）|1",
            "/debug|read|调试指纹（本地读盘：meta.json 关键字段）|1",
            "/diagram|send|" + BRIDGE + "：生成 Mermaid 图|1",
            "/disconnect|nav|模型页|1",
            "/doctor|api|运行环境 · 健康检查（Node/git/Python）|1",
            "/domain|nav|设置页 · 默认星域|1",
            "/dream|send|" + BRIDGE + "：把决策蒸馏进项目记忆|1",
            "/effort|api|会话 · 推理强度|1",
            "/enter|tui|恢复子代理会话 —— " + TUI_REASON + "|1",
            "/evidence|send|" + BRIDGE + "：显示上一轮证据链|1",
            "/exit|none|触屏没有「退出进程」—— 用系统返回键|1",
            "/fast|api|会话 · 跳过禅专注相位（等价 /fast）|1",
            "/fork|api|会话 · 从当前会话分出新分支|1",
            "/galaxy|send|交给天枢：星河集群（运行时解析成工作流 prompt）|1",
            "/glance|none|概览栏密度是终端行内排版|1",
            "/goal|api|会话 · 设定自主目标|1",
            "/goal-cancel|api|会话 · 终止目标|1",
            "/goal-criteria|api|会话 · 目标验收判据（GET /sessions/:id/goal 的 successCriteria）|1",
            "/goal-pause|api|会话 · 暂停目标|1",
            "/goal-resume|api|会话 · 恢复目标|1",
            "/goal-status|api|会话 · 目标状态|1",
            "/grant|api|会话 · 本工作区已记住的授权（工作区外目录）|1",
            "/handoff|api|会话 · 写结构化交接文档|1",
            "/help|nav|命令面板页|1",
            "/index|send|" + BRIDGE + "：重建代码库索引|1",
            "/init|send|" + BRIDGE + "：项目初始化脚手架|1",
            "/interview|send|" + BRIDGE + "：需求访谈|1",
            "/jobs|nav|面板页 · 子代理任务|1",
            "/leave|send|" + BRIDGE + "：离开仪式|1",
            "/login|nav|账号页 · 设备码登录（登录是三步：要码 → 给人码和链接 → **轮询**）|1",
            "/logout|api|账号 · 登出天枢账号（清本机凭据）|1",
            "/logs|tui|日志落点（会话 / 缓存 / 六维 / 桌面）—— " + TUI_REASON + "|1",
            "/mcp|nav|面板页 · MCP|1",
            "/memory|read|会话记忆（本地读盘：<id>.memory.json）|1",
            "/mirror|nav|设置页 · 镜像与回退|1",
            "/mission|nav|面板页 · 天契|1",
            "/mode|none|模式已由消息内容自动检测，无需手动切换。|1",
            "/model|nav|模型页|1",
            "/new|local|开一条新会话|1",
            "/pager|none|滚屏是触屏原生行为|1",
            "/palette|nav|命令面板页|1",
            "/panel|nav|面板页|1",
            "/permission|nav|设置页 · 审批|1",
            "/plan|send|交给天枢：规划工作流（运行时解析成 prompt）|1",
            "/plan-approve|api|会话 · 批准计划并开始执行|1",
            "/plan-close|send||1",
            "/plan-list|api|会话 · 列出本会话的计划|1",
            "/plan-mode|api|会话 · 计划模式（只读，只写计划文件）|1",
            "/plan-reject|api|会话 · 驳回计划|1",
            "/plan-template|send|" + BRIDGE + "：计划模板|1",
            "/plan-view|api|会话 · 预览计划全文（无参先列计划）|1",
            "/plugin|nav|面板页 · 插件|1",
            "/prefix-budget|api|会话 · 前缀预算归因（harness 端点 /sessions/:id/prefix-budget）|1",
            "/python|api|运行环境 · Python/uv/Git 检查|1",
            "/queue|api|会话 · 运行中排队一条消息|1",
            "/remember|send|" + BRIDGE + "：写入项目长期记忆|1",
            "/resume|nav|会话页|1",
            "/review|send|交给天枢：对抗审查（运行时解析成 prompt）|1",
            "/rewind|api|会话 · 可回退的消息点（只读列出）|1",
            "/rollback|api|会话 · 回滚预览（只读；执行需二次确认，另开波）|1",
            "/scout|api|会话 · 派只读子代理侦察|1",
            "/scroll|none|滚屏是触屏原生行为|1",
            "/sensorium|api|会话 · 六维遥测自感知快照|1",
            "/sessions|nav|会话页|1",
            "/settings|nav|设置页 · 运行控制台|1",
            "/setup|nav|设置页 · 运行控制台|1",
            "/skill|api|会话 · 已加载的技能（面板页里没有这张卡）|1",
            "/starflow|send|" + BRIDGE + "：星流编排|1",
            "/starmap|nav|星图页 · 星域与最近里程碑（本地读盘：.rivet/constellation.json）|1",
            "/status|nav|面板页 · 运行时状态|1",
            "/task|none|模式已由消息内容自动检测，无需手动切换。任务脚手架在有明确意图时自动开启。子代理任务面板（查看/管理 worker）请用 /tasks。|1",
            "/tasks|nav|面板页 · 子代理任务|1",
            "/team|send|交给天枢：团队分波（运行时解析成工作流 prompt）|1",
            "/team-resume|api|会话 · 列出可恢复的团队检查点|1",
            "/theme|nav|外观页|1",
            "/todo|read|任务清单（本地读盘：<id>.todos.json）|1",
            "/tools|nav|面板页 · 工具面|1",
            "/trust|nav|面板页 · 项目授信|1",
            "/undo|api|会话 · 文件历史快照（可撤销点；真正回退仍在 TUI）|1",
            "/update|none|更新走装包，不在这里自更新|1",
            "/verbose|tui|详细工具输出开关 —— " + TUI_REASON + "|1",
            "/verify|read|改动验证状态（本地读盘：meta.json）|1",
            "/vim|none|触屏没有键位映射|1",
            "/vision|nav|设置页 · 视觉模型（独立识图模型；从前指向模型页，那里是服务商配置）|1",
            "/workflow|send|" + BRIDGE + "：YAML 工作流编排|1",
            "/write-plan|send|交给天枢：撰写计划（同 /plan 工作流）|1",
            "/yes|api|全局 · 跳过所有权限确认（⚠ 谨慎，off 退出）|1",
            "/yolo|api|全局 · 跳过所有权限确认（⚠ 谨慎，off 退出）|1",
            "/zen|api|运行时 · 禅模式（全局配置，新会话生效）|1",
            // ---- harness 3.28.0 新增（官方更新补缺，2026-10-06）----
            // 8 条在 harness 里都是**终端本端能力**：/tui /keybindings /stash /editor /thinking
            // /paste 由 FrontendWorkflow 前端注册（main.js），/cvm 改进程内 cvmNoticeGate，
            // /metrics 读进程内 metricsGlanceController —— 都没有 HTTP 对端，故归 tui。
            // ⚠ target 里不许出现 "|"（它是分隔符），参数提示写成 " / "。
            "/cvm|tui|CVM 拦截提示级别开关（off / intercept / warn / all）—— " + TUI_REASON + "|1",
            "/editor|tui|用外部编辑器编辑草稿（返回后不自动发送）—— " + TUI_REASON + "|1",
            "/keybindings|tui|查看 / 切换标准或旧版兼容键位—— " + TUI_REASON + "|1",
            "/metrics|tui|会话缓存 / 上下文 / 计价来源 / 分支 / 推理档位—— " + TUI_REASON + "|1",
            "/paste|tui|展开 / 折叠 / 清除草稿里的长粘贴—— " + TUI_REASON + "|1",
            "/stash|tui|暂存 / 恢复 / 交换当前草稿（本进程内）—— " + TUI_REASON + "|1",
            "/thinking|tui|查看当前会话已有的思考详情—— " + TUI_REASON + "|1",
            "/tui|tui|查看 / 切换终端绘制模式—— " + TUI_REASON + "|1",
    };

    private static final Map<String, Row> TABLE = new HashMap<String, Row>();

    static {
        for (String line : DATA) {
            String[] p = line.split("\\|", 4);
            if (p.length < 4) continue;
            TABLE.put(p[0].trim(), new Row(p[0].trim(), p[1].trim(), p[2].trim(), "1".equals(p[3].trim())));
        }
    }

    /** 查一条命令的落点；不在表里返回 null。 */
    public static Row of(String cmd) {
        if (cmd == null) return null;
        return TABLE.get(cmd.trim());
    }

    /** 已登记行数（HostTest 用它盯住"104 条无遗漏"）。 */
    public static int size() {
        return TABLE.size();
    }

    public static List<String> commands() {
        return new ArrayList<String>(TABLE.keySet());
    }

    /** 取出输入里的命令名（第一个空格前那段）；不是斜杠开头返回 null。 */
    public static String cmdOf(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (!t.startsWith("/")) return null;
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    /**
     * 未落定时给用户的一句人话。**必须拦住不发** —— 静默发出去正是
     * 「敲 /connect 却进了对话」的成因。
     */
    public static String blockedHint(Row r) {
        if (r == null) return "这条命令在 App 里还没落定，已拦住没有发给天枢。";
        if (NONE.equals(r.action) || TUI.equals(r.action)) return r.target;
        return "「" + r.cmd + "」在 App 里还没落定（落点：" + r.target + "）—— 已拦住，没有发给天枢。";
    }
}
