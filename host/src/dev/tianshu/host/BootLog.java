package dev.tianshu.host;

/**
 * 进程内共享的运行日志 —— 纯逻辑，不碰 Android。
 *
 * 启动流程由 {@link MainActivity} 写，设置页读它显示、复制。
 * 用进程内静态而不是 Intent 传大字符串：启动日志动辄几万字，塞进 Intent 会撞
 * Binder 的 1MB 事务上限（真出事时还偏偏是日志最长的时候）。
 *
 * 容量上限只是个兜底 —— 启动日志正常几 KB，超过 20 万字符说明有东西在刷屏，
 * 保留尾部比让它无限涨更安全（也留着足够上下文定位问题）。
 */
public final class BootLog {

    private static final StringBuilder SB = new StringBuilder();
    private static final int MAX_CHARS = 200000;

    private BootLog() {
    }

    public static void say(String s) {
        synchronized (SB) {
            SB.append(s == null ? "" : s).append('\n');
            if (SB.length() > MAX_CHARS) {
                SB.delete(0, SB.length() - MAX_CHARS / 2);
                SB.insert(0, "…（早期日志已截断）\n");
            }
        }
    }

    public static String text() {
        synchronized (SB) {
            return SB.toString();
        }
    }

    public static int length() {
        synchronized (SB) {
            return SB.length();
        }
    }

    public static void clear() {
        synchronized (SB) {
            SB.setLength(0);
        }
    }

    /** 最后 n 行（设置页顶部给个摘要用）。 */
    public static String tail(int lines) {
        String all = text();
        if (lines <= 0) return "";
        // 2026-10-04 深查：缓冲区每条都带 '\n'（恒以换行结尾），所以得**先退掉末尾那个换行**
        // 再往回数 —— 否则 tail(1) 返回空串、tail(n) 只回 n-1 行（差一）。
        int end = all.length();
        if (end > 0 && all.charAt(end - 1) == '\n') end--;
        int idx = end;
        for (int i = 0; i < lines; i++) {
            int prev = all.lastIndexOf('\n', idx - 1);
            if (prev < 0) return all.substring(0, end);   // 不够 n 行，全给
            idx = prev;
        }
        return all.substring(idx + 1, end);
    }
}
