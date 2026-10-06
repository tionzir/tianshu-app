package dev.tianshu.host;

import java.util.HashMap;
import java.util.Map;

/**
 * 指令桥 —— 把"终端本地命令"改写成**等价的一条 agent 指令**（纯逻辑）。
 *
 * 背景：harness 的命令里有一批在 TUI 是**进程内执行**的（handler 直接调 `ctx.agent.*`），
 * HTTP 侧没有对应的斜杠命令入口 —— 直接发出去会被 `resolveAppPromptInput` 判成
 * `Unknown slash command` 而 400。上一版把它们归为"终端本地能力，App 无对端"，
 * **那个结论过于悲观**：它们的语义本来就是"让 agent 去做某件事"，而"让 agent 做事"
 * 在 App 里有一条现成的路 —— `POST /sessions/:id/prompt` 发一句**自然语言指令**。
 *
 * 所以这里做的不是"复刻 TUI 的本地实现"，而是**换一条到达同一效果的路径**：
 * 用户敲 `/interview`，App 发出去的是"请对我做一次深度访谈……"。
 * 指令开头写明"用户输入了 /xxx"，这样对话里能看出因果，不是凭空冒出一段话。
 *
 * ⚠ 这一条路径**依赖模型配合**（指令是给 agent 的，不是本地 API 调用）。
 * 它与 TUI 的本地执行**不等价**：TUI 那一侧改的是进程状态，这里改的是对话内容。
 * 界面上如实说明，不假装完全一致。
 */
public final class CommandPrompt {

    /** 命令 → 指令模板。`$ARG` 会被替换成命令后的参数（没有参数时保留为空串）。 */
    private static final Map<String, String> TEMPLATES = new HashMap<String, String>();

    static {
        TEMPLATES.put("/interview",
                "用户输入了 /interview。请对我做一次深度访谈：你反过来问我问题，"
                        + "把我的模糊想法逼成清晰规格，最后给出结构化结论。$ARG");
        TEMPLATES.put("/diagram",
                "用户输入了 /diagram。请为当前工作生成一张 Mermaid 图 —— 先判断该用"
                        + "架构图 / 数据流图 / 时序图 / 流程图，再给出完整的 Mermaid 源码。$ARG");
        TEMPLATES.put("/remember",
                "用户输入了 /remember。如果下面有内容，请把它写进项目长期记忆（跨会话生效）；"
                        + "如果为空，请显示项目记忆里最近的用户记忆条目。$ARG");
        TEMPLATES.put("/dream",
                "用户输入了 /dream。请把本会话的关键决策蒸馏进项目记忆（跨会话生效）。$ARG");
        TEMPLATES.put("/evidence",
                "用户输入了 /evidence。请显示上一轮结论的证据链：它是基于哪些文件 / 命令 / "
                        + "工具输出得出的，逐条列出。$ARG");
        TEMPLATES.put("/index",
                "用户输入了 /index。请重建代码库索引（模块图 + CLI 入口）。$ARG");
        TEMPLATES.put("/init",
                "用户输入了 /init。请做一次项目初始化：确认 verify 声明、skills 与 hooks 脚手架。$ARG");
        TEMPLATES.put("/leave",
                "用户输入了 /leave。请做一次离开仪式：为本次工作留下一个自选符号和一句"
                        + "「做了什么」，写进项目里。$ARG");
        TEMPLATES.put("/workflow",
                "用户输入了 /workflow。请按 YAML 工作流编排处理下面的请求：$ARG");
        TEMPLATES.put("/starflow",
                "用户输入了 /starflow。请走一遍星流编排（评审 → 波次执行 → 攻坚），"
                        + "阶段间用硬门禁兜底。目标：$ARG");
        TEMPLATES.put("/capsule",
                "用户输入了 /capsule。请把下面点名的星域方法论完整注入本轮思考（作为方法指引，"
                        + "不改工具面）：$ARG");
        TEMPLATES.put("/plan-template",
                "用户输入了 /plan-template。请列出可用的计划模板；若后面点名了模板名，"
                        + "就按该模板撰写计划。$ARG");
    }

    private CommandPrompt() {
    }

    /** 这条命令在 App 里走"指令桥"吗。 */
    public static boolean handles(String cmd) {
        return cmd != null && TEMPLATES.containsKey(cmd);
    }

    /**
     * 把一条斜杠命令改写成要发出去的指令；不改写就**原样返回**。
     *
     * 注意：只对桥上的命令改写。`send` 类里那些（/plan /review /council /galaxy …）
     * 服务端自己能解析，原样发才是对的。
     */
    public static String rewrite(String text) {
        String cmd = CommandRouting.cmdOf(text);
        if (cmd == null || !handles(cmd)) return text;
        String tpl = TEMPLATES.get(cmd);
        String arg = CommandApi.argOf(text, cmd);
        String out = tpl.replace("$ARG", arg);
        return out.trim();
    }
}
