package dev.tianshu.host;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃报告的**拼装**（纯逻辑 —— HostTest 直接断言，不必起 Android）。
 *
 * ## 为什么需要它
 * 外部（容器/工具）看不到这台设备上 App 的进程与端口，也进不去 App 的私有目录
 * —— 2026-10-05 逐条实测过：`/data/user/0/dev.tianshu.host` 在容器里不存在，
 * `/proc` 里也只有容器自己的进程树。于是"App 闪退"在外部看来只剩四个字：
 * 崩在哪一行、当时什么状态，全得靠猜。
 *
 * 这份报告就是补那个缺口：崩溃那一刻把**能定位的信息一次写全**，落到共享存储
 *（{@link Diagnostics} 负责写盘），外部用文件管理器就能取走。
 *
 * 与 {@link LogReport} 同一分工：这里只拼文本（可测），碰 Android 的事在那边。
 */
public final class CrashReport {

    /** 固定文件名 —— 外部不必猜"最新一次崩在哪个时间戳"。 */
    public static final String LATEST_FILE = "crash-latest.txt";

    private CrashReport() {
    }

    /**
     * 拼一份崩溃报告。
     *
     * @param stamp    人类可读的时间（{@link LogReport#stamp}）
     * @param env      设备与版本（{@link LogExport#environment}）
     * @param runtime  崩溃那一刻的运行时状态（{@link LogExport#runtimeState}）——
     *                 "serve 还活着吗、配置配好了吗"常常直接解释这次崩溃
     * @param bootTail 启动日志尾部（走到哪一步崩的）
     * @param thread   崩在哪个线程
     * @param t        异常
     */
    public static String compose(String stamp, String env, String runtime, String bootTail,
                                 String thread, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("天枢 App 崩溃报告\n");
        sb.append("================\n\n");
        sb.append("时间 : ").append(nz(stamp)).append('\n');
        sb.append("线程 : ").append(nz(thread)).append('\n');
        if (t != null) {
            sb.append("异常 : ").append(t.getClass().getName()).append('\n');
        }
        sb.append('\n');

        sb.append("## 设备与版本\n").append(nz(env)).append('\n');
        sb.append("## 崩溃时的运行时状态\n").append(nz(runtime)).append('\n');
        sb.append("## 调用栈\n```\n").append(stackTrace(t)).append("```\n\n");
        sb.append("## 启动日志尾部\n").append(nz(bootTail)).append('\n');
        return sb.toString();
    }

    /**
     * 完整调用栈 —— 含 cause 链与 suppressed。
     *
     * 用 {@link PrintWriter} 而不是自己遍历 {@code getStackTrace()}：`printStackTrace`
     * 会连 **cause 链**（"Caused by"）与 suppressed 异常（try-with-resources 里那些）
     * 一起打出来 —— 而真正的根因经常压在第二层，只印最外层那句话会把人指错方向。
     */
    public static String stackTrace(Throwable t) {
        if (t == null) return "（没有异常对象）\n";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * 崩溃报告的文件名 —— 与 {@link LogReport#fileName} 同风格（同目录可留历史），
     * 但**不带空格与冒号**（{@link LogReport#stamp} 那份是给人看的，不能直接当文件名）。
     */
    public static String fileName(long epochMillis) {
        return "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date(epochMillis)) + ".txt";
    }

    private static String nz(String s) {
        return (s == null || s.isEmpty()) ? "（无）\n" : s;
    }
}
