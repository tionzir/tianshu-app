package dev.tianshu.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * serve 的**守护**：周期探活，发现进程真的没了就重新拉起。
 *
 * 判据全在 {@link ServeGuard}（纯逻辑、HostTest 断言），这里只负责三件事：
 *   ① 后台线程按 {@link ServeGuard#PROBE_INTERVAL_MS} 打一拍 `/health`；
 *   ② 把结果喂给判据；
 *   ③ 判据说"该拉"时，回主线程起进程。
 *
 * ## 为什么不挂在某个 Activity 上
 * 承载 serve 的是 MainActivity 起的 proot，而 MainActivity 一跳进对话页就 `finish()`。
 * 守护必须**活得比任何页面都久** —— 所以它只拿 application context
 * （{@link RuntimeHost} 只用到 `getFilesDir()` / `getApplicationInfo()`，够用）。
 *
 * ## 一条纪律：**不打断**
 * 判据里"进程还在就不重启"是刻意的。serve 可能在跑一个长任务，重启会把它连同
 * 正在进行的会话一起砍掉 —— 那比"等下一拍"糟得多。只有进程确实没了才动手，
 * 而那时已经没有什么可打断的了。
 */
public final class ServeWatch {

    private static ScheduledExecutorService exec;
    private static Handler main;
    private static Context appCtx;
    private static final ServeGuard GUARD = new ServeGuard();

    private ServeWatch() {
    }

    /** 启动守护。**幂等**：重复调用只有第一次生效（各页面重建都会调它）。 */
    public static synchronized void start(Context context) {
        if (exec != null) return;
        appCtx = context.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "serve-watch");
                t.setDaemon(true);
                return t;
            }
        });
        exec.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, ServeGuard.PROBE_INTERVAL_MS, ServeGuard.PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 探一拍。网络与判定都在这个后台线程上做，别占主线程。 */
    private static void tick() {
        boolean ok = healthOk();
        if (ok) {
            AppRuntime.markServeReady();      // 自愈之后把"就绪"还回去
        }
        ServeGuard.Action a = GUARD.onProbe(ok, AppRuntime.isServeAlive(),
                SystemClock.uptimeMillis());
        if (a != ServeGuard.Action.RESTART) return;

        AppRuntime.markError("serve 没响应，正在自动重启…");
        final Handler h = main;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    restartNow();
                }
            });
        }
    }

    /** 起一个新的常驻 serve。 */
    private static void restartNow() {
        Context ctx = appCtx;
        if (ctx == null) return;
        try {
            AppRuntime.stopServe();                       // 清掉死句柄（与旧进程的引用）
            Process p = RuntimeHost.launchServe(new RuntimeHost(ctx));
            AppRuntime.holdServe(p);
            AppRuntime.markError(null);
        } catch (IOException e) {
            AppRuntime.markError("自动重启失败: " + e);
        } catch (Throwable t) {
            AppRuntime.markError("自动重启失败: " + t);
        }
    }

    /** 打一次 `/health` —— 实现收在 {@link ServeHealth#ok()}，这里只留个名字。 */
    static boolean healthOk() {
        return ServeHealth.ok();
    }
}
