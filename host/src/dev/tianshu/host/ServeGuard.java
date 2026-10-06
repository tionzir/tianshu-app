package dev.tianshu.host;

/**
 * serve 守护的**判据**（纯逻辑，不碰 android —— HostTest 直接断言）。
 *
 * ## 为什么必须有守护
 * harness 里有能让 Runtime **整个进程退出**的事故：`GET /git/graph` 在非 git 目录
 * 执行 git → git 退出 128 → Runtime 在 ChildProcess 回调里抛未捕获异常 → `process.exit(1)`
 * （2026-10-01 打靶实测复现，见 README「Wave 1 的核心结论」）。而承载它的 proot 带
 * `--kill-on-exit`，于是 serve 连同子孙一起没。
 *
 * 没有守护时用户看到的是设置页那句「运行环境没起来 —— 回启动页重跑一次启动流程」，
 * **不会自愈**，而且在别的页面根本看不到这句。
 *
 * ## 判据（宁可慢一拍，不要误杀）
 *   - 单次 `/health` 不通**不算**：长任务在跑时 serve 偶有忙不过来（`/config/balance`
 *     这类要连上游的路由会长时间不返回，Wave 1 已把它单列为「挂起」而不是「错误」）。
 *   - 连续 {@link #MISSES_TO_RESTART} 次不通，**且进程确实已经不在**，才拉。
 *     进程还在就不动它 —— 重启会打断正在跑的会话，那比"等一拍"糟得多。
 *   - 两次重启之间至少隔 {@link #MIN_RESTART_GAP_MS}：起不来时别疯狂重试烧电。
 *
 * ## 与 {@code supportsXxx} 式"提前问"的区别
 * 这里问的是"**现在还行不行**"，所以它必须被周期性地喂进真实探测结果，
 * 而不是启动时问一次就完事（那是 `isServeReady` 的活，不是它的）。
 */
public final class ServeGuard {

    /** 连续几次探活失败才算"真的死了"。 */
    public static final int MISSES_TO_RESTART = 2;

    /** 两次探活之间的间隔（ms）。10 秒：够快恢复，又不至于把电量磨光。 */
    public static final long PROBE_INTERVAL_MS = 10_000L;

    /** 两次**重启**之间的最小间隔（ms）。 */
    public static final long MIN_RESTART_GAP_MS = 30_000L;

    /** 该做什么。 */
    public enum Action {
        /** 什么都不做（正常 / 还没攒够失败次数 / 进程还活着 / 刚重启过）。 */
        NONE,
        /** 该拉起来了。 */
        RESTART,
    }

    private int misses;
    private long lastRestartAt = Long.MIN_VALUE;

    /**
     * 喂一次探活结果。
     *
     * @param healthOk     这一拍 `/health` 通不通
     * @param processAlive serve 进程（同进程引用）是否还在
     * @param now          {@code SystemClock.uptimeMillis()}
     */
    public Action onProbe(boolean healthOk, boolean processAlive, long now) {
        if (healthOk) {
            misses = 0;
            return Action.NONE;
        }
        misses++;
        if (misses < MISSES_TO_RESTART) return Action.NONE;
        // 连续不通，但进程还在 —— 可能在跑长任务，别打断
        if (processAlive) return Action.NONE;
        if (lastRestartAt != Long.MIN_VALUE && now - lastRestartAt < MIN_RESTART_GAP_MS) {
            return Action.NONE;
        }
        lastRestartAt = now;
        misses = 0;
        return Action.RESTART;
    }

    /** 当前连续失败计数（测试/日志用）。 */
    public int misses() {
        return misses;
    }
}
