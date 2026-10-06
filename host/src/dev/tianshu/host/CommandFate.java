package dev.tianshu.host;

import java.util.HashSet;
import java.util.Set;

/**
 * 每条 `/` 命令在 **App 里到底怎么用**（纯逻辑）—— 由 {@link CommandRouting} **派生**，不再各写一份。
 *
 * 为什么改成派生：从前这里是一张**独立维护**的显示表（NATIVE / SEND / TERMINAL），与路由表
 * {@link CommandRouting} 并行存在。两张表必然会漂移，于是界面出现「标注说能用、实际不能用」——
 * `/connect` 在命令面板里写着 `✓ 设置 → 模型页`，而路由表根本没有任何代码去执行它。
 *
 * 现在只有一份真相（`CommandRouting`），命令面板的显示从这里投影出来：
 *
 *   nav / api / local → {@link Kind#NATIVE}   App 里有原生落点，点过去就能用
 *   send              → {@link Kind#SEND}     本质是"一句话交给天枢"（运行时解析成工作流）
 *   none / tui        → {@link Kind#TERMINAL} 终端交互产物，App 里没有对应语义
 */
public final class CommandFate {

    private CommandFate() {
    }

    public enum Kind {
        NATIVE, SEND, TERMINAL
    }

    /** 这条命令在 App 里属于哪一类。 */
    public static Kind of(String cmd) {
        CommandRouting.Row r = CommandRouting.of(cmd);
        if (r == null) return Kind.SEND;
        if (CommandRouting.NAV.equals(r.action)
                || CommandRouting.API.equals(r.action)
                || CommandRouting.LOCAL.equals(r.action)
                || CommandRouting.READ.equals(r.action)) {
            return Kind.NATIVE;
        }
        if (CommandRouting.SEND.equals(r.action)) return Kind.SEND;
        return Kind.TERMINAL;   // none / tui
    }

    /** 落点说明（NATIVE 时是去哪一页 / 打哪个动作；其余说明为什么）。 */
    public static String where(String cmd) {
        CommandRouting.Row r = CommandRouting.of(cmd);
        if (r == null) return "发出去只是给天枢的一句话（App 里没有原生入口）";
        if (CommandRouting.SEND.equals(r.action)) return "发给天枢（运行时解析成工作流）";
        return r.target;
    }

    /** 界面上的短标签。 */
    public static String badge(String cmd) {
        switch (of(cmd)) {
            case NATIVE:
                return "→ " + where(cmd);
            case TERMINAL:
                return "终端专用";
            default:
                return "发给天枢";
        }
    }

    /** NATIVE 的命令是否**真的**指向可用落点（HostTest 用它防"指向不存在的页"）。 */
    public static boolean whereIsRealPage(String cmd) {
        CommandRouting.Row r = CommandRouting.of(cmd);
        if (r == null) return false;
        if (CommandRouting.NAV.equals(r.action)) {
            String w = r.target;
            return w.contains("设置") || w.contains("模型") || w.contains("面板")
                    || w.contains("会话页") || w.contains("外观页")
                    || w.contains("命令面板页") || w.contains("对话页")
                    || w.contains("星图页") || w.contains("编年史页") || w.contains("蓝图页")
                    || w.contains("账号页");
        }
        // api / local / read：不是页面，但有真实落点（一个接口 / 一个本地动作 / 一次本地读盘）
        return CommandRouting.API.equals(r.action) || CommandRouting.LOCAL.equals(r.action)
                || CommandRouting.READ.equals(r.action);
    }

    /** 明确归为 NATIVE 的命令集合（派生自路由表，供测试逐个核对）。 */
    public static Set<String> nativeCommands() {
        return pick(Kind.NATIVE);
    }

    /** 明确归为 TERMINAL 的命令集合。 */
    public static Set<String> terminalCommands() {
        return pick(Kind.TERMINAL);
    }

    private static Set<String> pick(Kind k) {
        Set<String> out = new HashSet<String>();
        for (String c : CommandRouting.commands()) {
            if (of(c) == k) out.add(c);
        }
        return out;
    }
}
