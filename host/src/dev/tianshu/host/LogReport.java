package dev.tianshu.host;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行报告的**拼装**（纯逻辑 —— HostTest 直接断言，不必起 Android）。
 *
 * ## 为什么要这个东西
 * 真机上出问题时，能看到的只有屏幕上一句话（比如那句
 * `MemoryLimitException: 65640 KiB ...`）。而排查真正要的是三样东西，
 * 它们分别在不同的地方、平时谁也没法一次拿全：
 *
 *   1. **启动/运行日志**（{@link BootLog}）—— App 走了哪几步、哪一步 fail 的；
 *   2. **serve 自己的日志**（`<rootfs>/tmp/host-serve.log`）—— 之前在设备上
 *      **完全读不到**，只能靠界面转述（README 里"上一轮最大的问题是看不到 serve
 *      自己的日志，只能猜"说的就是它）；
 *   3. **环境与运行时状态** —— Android 版本 / ABI / heap 上限 / rootfs 装没装 /
 *      serve ready 没 ready。少一行"heap 上限"，就分不清 64 MiB 字典是"库的限制"
 *      还是"设备真给不出"。
 *
 * 拼成**一份纯文本**、写到共享存储的固定位置，外部就能直接读——
 * 不必再让人对着屏幕转述。
 *
 * ## 一条纪律：空段也要出现
 * 某一段没采到（例如 serve 从没起来过、日志文件不存在）**照样打出标题**，
 * 内容写「（无）」。把它整段省掉是最坏的做法：读的人分不清"这台机器没这段"
 * 和"采集代码没跑到"，而那正是排查的第一个岔路口。
 */
public final class LogReport {

    /** 固定文件名 —— 外部工具直接读它就拿最新一份，不必猜时间戳。 */
    public static final String LATEST_FILE = "latest.txt";

    private LogReport() {
    }

    /** 报告文件名（按时间戳命名，同目录可留历史）。 */
    public static String fileName(long epochMillis) {
        return "tianshu-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date(epochMillis)) + ".txt";
    }

    /** 人类可读的生成时间（带时区，省得对比时间线时还要猜）。 */
    public static String stamp(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                .format(new Date(epochMillis));
    }

    /**
     * 拼装完整报告。
     *
     * @param stamp   生成时间（{@link #stamp}）
     * @param env     环境段（Android 版本/ABI/机型/App 版本/heap）
     * @param state   运行时状态段（rootfs、配置、serve）
     * @param bootLog {@link BootLog} 全文
     * @param serveLog serve 日志全文
     */
    public static String compose(String stamp, String env, String state,
                                 String bootLog, String serveLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 天枢 运行报告\n");
        sb.append("生成时间: ").append(nz(stamp)).append('\n');

        section(sb, "环境", env);
        section(sb, "运行时状态", state);
        section(sb, "启动/运行日志（BootLog）", bootLog);
        section(sb, "serve 日志（rootfs/tmp/host-serve.log）", serveLog);

        sb.append("\n--- 报告结束 ---\n");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, String body) {
        sb.append("\n## ").append(title);
        boolean empty = body == null || body.trim().isEmpty();
        if (empty) {
            // 见类注释：空段必须显式出现，写「（无）」而不是省掉
            sb.append("\n（无 —— 这一段没采到，不是没有内容）\n");
            return;
        }
        sb.append("（").append(body.length()).append(" 字符）\n");
        sb.append(body);
        if (body.charAt(body.length() - 1) != '\n') sb.append('\n');
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "（未知）" : s;
    }
}
