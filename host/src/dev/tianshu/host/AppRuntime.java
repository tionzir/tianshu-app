package dev.tianshu.host;

/**
 * 进程级运行时：常驻的 serve 与连接信息。跨 Activity 存活。
 *
 * 为什么需要它：serve 是由一个 **proot 进程**承载的，而 proot 带着
 * `--kill-on-exit` —— 那个进程一退出，serve 立刻被杀。所以探针那种
 * 「起一次、跑完 kill」的用法只够自检，界面要连它就必须让 proot 一直活着。
 *
 * 于是：启动阶段起一个常驻 proot（不 waitFor、不 destroy），把它存在这里；
 * 界面拿 {@link #baseUrl()} 与 {@link #token()} 去连。
 */
public final class AppRuntime {

    /** 本地 serve 端口。与 MainActivity 探针里用的一致。 */
    public static final int PORT = 18799;

    /** 本地回环上的 token —— 只在本机 loopback 上用，不对外。 */
    public static final String TOKEN = "host_local";

    private static volatile Process serveProcess;
    private static volatile boolean serveReady;
    private static volatile String lastError;

    private AppRuntime() {
    }

    // ---------------------------------------------------------------- 观测钩子

    /**
     * 事件钩子 —— App 侧接一个写文件的实现（{@link Diagnostics}），把 serve 的
     * 起 / 停 / 就绪 / 出错记下来。
     *
     * 为什么收在本类：这些恰是真机排查时最想知道的事 ——「serve 到底起没起、
     * 正在用的到底是不是我刚起的那个」。2026-10-04 的设备报告里出现过
     * `ready=true alive=false` 这种矛盾状态，靠的就是这类记录才能还原。
     *
     * **不接就是空转**：本类因此仍然不碰 Android，HostTest 可以直接跑。
     */
    public interface Sink {
        void onEvent(String line);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    private static void emit(String line) {
        Sink s = sink;
        if (s == null) return;
        try {
            s.onEvent(line);
        } catch (Throwable ignored) {
            // 观测失败绝不能反过来影响主流程
        }
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + PORT;
    }

    public static String token() {
        return TOKEN;
    }

    /** 新会话的流：等价于 `since=0`（从头，实测两条等价）。 */
    public static String streamUrl(String sessionId) {
        return streamUrl(sessionId, 0);
    }

    /**
     * 带水位的流。`since=N` 只取 **seq > N** 的事件（2026-10-02 活体实测：
     * since=0 → 首事件 seq:1；since=25 → 首事件 seq:26）。
     *
     * 续聊**必须**用它而不是从 0 开始：本页已经重放过一遍历史，`since=0` 会把
     * 整段历史再放一次，与本页已有的事件叠在一起 —— 而 {@link Transcript}
     * 是按 `turn_complete` 分轮的，重复事件会让轮次翻倍。
     */
    public static String streamUrl(String sessionId, int since) {
        return baseUrl() + "/sessions/" + sessionId + "/stream?since=" + since;
    }

    public static boolean isServeReady() {
        return serveReady;
    }

    public static void markServeReady() {
        boolean was = serveReady;
        serveReady = true;
        lastError = null;
        // 只在**状态变化**时记一行 —— 守护每 10 秒探一次活，每次都记会把轨迹刷满
        if (!was) emit("serve ready");
    }

    public static String lastError() {
        return lastError;
    }

    public static void markError(String message) {
        lastError = message;
        serveReady = false;
        emit(message == null ? "serve error 清除" : "serve error: " + message);
    }

    /** 记下常驻的 proot 进程，供后续查询/收尾。 */
    public static void holdServe(Process process) {
        serveProcess = process;
        emit("serve hold（进程引用已记下）");
    }

    public static Process serveProcess() {
        return serveProcess;
    }

    public static boolean isServeAlive() {
        Process p = serveProcess;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /** 仅在明确需要收尾时调用（App 正常退出路径）。 */
    public static void stopServe() {
        Process p = serveProcess;
        serveProcess = null;
        serveReady = false;
        // 手里有没有进程引用，是"能不能杀掉它"的分界 —— 跨进程残留时这里是 null，
        // 那个野 serve 谁也杀不掉（只能等它自己退），这正是要记下来的事。
        emit(p == null ? "serve stop（手里没有进程引用 —— 杀不到任何东西）"
                : "serve stop（destroy 手里那个）");
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
                // 已经死了就算了
            }
        }
    }
}
