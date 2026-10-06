package dev.tianshu.host;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 真机自诊断 —— 把"现场"自动写到共享存储，让**看不到设备的人**也拿得到证据。
 *
 * ## 为什么需要它（2026-10-05 实测的结论）
 * 外部的容器看不见这台设备上 App 的内部：
 *   - `/data/user/0/dev.tianshu.host`（App 私有目录，rootfs 与 serve 日志都在里面）
 *     在容器里**不存在** —— proot 只 bind 了 Termux 自己的目录；
 *   - `/proc` 里只有容器自己的进程树，**看不到** App 起的 proot/serve；
 *   - 18799 端口也不在容器的网络命名空间里。
 *
 * 于是"App 闪退 / 某页不对"在外部只剩四个字，只能靠猜着改 —— 而猜错一次就要用户
 * 重装一轮。唯一走得通的通道是**共享存储**：它能写，容器能读。
 *
 * ## 写什么
 *   - `crash-<时间>.txt` + `crash-latest.txt`：崩溃报告（{@link CrashReport} 拼装）
 *   - `boot-latest.txt`：每次启动完成后的快照（与「设置 → 导出到文件」同一份内容，
 *     但**不必等用户手动点**）
 *   - `trace.log`：追加式轨迹 —— 页面切换与 serve 起/停/就绪，一笔一行
 *
 * ## 一条纪律：绝不因为写日志而出事
 * 所有落盘都包在 try/catch 里吞掉异常，绝不抛回调用方 —— 诊断是"顺手多带一份"，
 * 不该反过来把 App 弄崩。崩溃处理器里的那次写是**同步**的（进程马上要被系统收走，
 * 异步就来不及了），其余一律异步，不挡主线程。
 */
public final class Diagnostics {

    /** 与手动导出同一个目录（外部读一处就够了）。 */
    public static final String DIR = LogExport.DIR;

    /** 轨迹文件名。 */
    public static final String TRACE_FILE = "trace.log";

    /** 每次启动完成的快照（固定名，外部不必猜最新一份叫什么）。 */
    public static final String BOOT_FILE = "boot-latest.txt";

    /** 轨迹超过这么大就轮转一次（只留最近一半），免得它无限长下去。 */
    private static final int TRACE_MAX_BYTES = 256 * 1024;

    private static volatile boolean installed;
    private static ExecutorService io;

    private Diagnostics() {
    }

    /**
     * 装上崩溃处理器、接上 {@link AppRuntime} 的事件钩子。**幂等**（各页面重建都会调它）。
     *
     * @param appCtx 必须是 application context —— 处理器活得比任何页面久
     */
    public static synchronized void install(final Context appCtx) {
        if (installed) return;
        // ⚠ 置位必须在判空**之后**：否则一次 appCtx==null 的调用就把闸门永久关死
        //（崩溃处理器与事件钩子从此永不安装），且不留任何异常或日志痕迹 —— 是"静默失效"。
        // 当前唯一调用点 MainActivity 传的是 getApplicationContext()（不会 null），
        // 所以这是个"现在打不着"的洞；但把顺序摆正代价为零，值得钉住。
        if (appCtx == null) return;
        installed = true;
        final Context ctx = appCtx.getApplicationContext();

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    writeCrashSync(ctx, t, e);      // 同步：进程随时会被收走
                } catch (Throwable ignored) {
                    // 写不下来也不能因此吞掉崩溃本身
                }
                // 交回原来的处理器（系统的那个会走正常的崩溃流程）—— 我们只**多带一份**，
                // 不改变崩溃本身的行为：吞掉它会让"闪退"变成"静默死"。
                if (prev != null) prev.uncaughtException(t, e);
            }
        });

        AppRuntime.setSink(new AppRuntime.Sink() {
            @Override
            public void onEvent(String line) {
                note(line);
            }
        });
        note("diagnostics on —— 版本见 " + BOOT_FILE);
    }

    /** 追加一行轨迹（页面切换 / serve 起停 / 错误）。异步写，绝不挡主线程。 */
    public static void note(String line) {
        final long now = System.currentTimeMillis();
        final String s = LogReport.stamp(now) + "  " + (line == null ? "" : line) + "\n";
        try {
            io().execute(new Runnable() {
                @Override
                public void run() {
                    appendTrace(s);
                }
            });
        } catch (Throwable ignored) {
            // 线程池都起不来就算了，不能让日志把功能拖住
        }
    }

    /**
     * 记一次页面进入 —— <b>这一行是"卡在哪一页"的直接答案</b>。
     * 闪退时 trace.log 的最后一行就是崩之前停留的页面。
     */
    public static void notePage(String page) {
        note("page   " + page);
    }

    /**
     * 写一份启动快照（自动版；内容与「设置 → 导出到文件」一致）。
     * 不必等用户手动导出 —— 这正是"下次真机出问题，外部能直接读"的前提。
     */
    public static void snapshot(final Context ctx, final RuntimeHost host, final String tag) {
        if (ctx == null || host == null) return;
        final Context appCtx = ctx.getApplicationContext();
        final long now = System.currentTimeMillis();
        try {
            io().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String report = LogReport.compose(
                                LogReport.stamp(now),
                                LogExport.environment(appCtx),
                                LogExport.runtimeState(appCtx, host),
                                BootLog.text(),
                                LogExport.readServeLog(host));
                        File dir = ensureDir();
                        if (dir == null) return;
                        write(new File(dir, BOOT_FILE), report);
                        note("boot snapshot (" + tag + ") -> " + BOOT_FILE);
                    } catch (Throwable ignored) {
                        // 快照写不下来不影响任何功能
                    }
                }
            });
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ---------------------------------------------------------------- 落盘

    /** 崩溃：**同步**写。进程随时会被系统收走，异步就来不及了。 */
    private static void writeCrashSync(Context ctx, Thread t, Throwable e) {
        try {
            File dir = ensureDir();
            if (dir == null) return;
            long now = System.currentTimeMillis();
            RuntimeHost host = new RuntimeHost(ctx);
            String text = CrashReport.compose(
                    LogReport.stamp(now),
                    LogExport.environment(ctx),
                    LogExport.runtimeState(ctx, host),
                    tail(BootLog.text(), 4000),
                    t == null ? "?" : t.getName(),
                    e);
            write(new File(dir, CrashReport.fileName(now)), text);
            write(new File(dir, CrashReport.LATEST_FILE), text);
        } catch (Throwable ignored) {
            // 崩溃处理里再抛异常会盖掉原始崩溃，绝对不行
        }
    }

    private static void appendTrace(String line) {
        try {
            File dir = ensureDir();
            if (dir == null) return;
            File f = new File(dir, TRACE_FILE);
            if (f.length() > TRACE_MAX_BYTES) rotate(f);
            FileOutputStream o = new FileOutputStream(f, true);
            try {
                o.write(line.getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
            // 轨迹写不进去不影响功能
        }
    }

    /** 轮转：只留尾部一半（无历史包袱，但也不会无限长）。 */
    private static void rotate(File f) {
        RandomAccessFile raf = null;
        try {
            long len = f.length();
            long from = len / 2;
            raf = new RandomAccessFile(f, "r");
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            raf.close();
            raf = null;
            write(f, "…（轨迹轮转，前面已截断）\n" + new String(buf, "UTF-8"));
        } catch (Throwable ignored) {
            // 轮转失败就让它继续长，总比丢东西强
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    // 关不上就算了
                }
            }
        }
    }

    private static void write(File f, String text) throws java.io.IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(text.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    private static File ensureDir() {
        try {
            File dir = new File(DIR);
            if (dir.isDirectory()) return dir;
            return dir.mkdirs() ? dir : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取尾部至多 max 个字符（启动日志可能很长，报告不该被它拖爆）。 */
    static String tail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…（仅尾部 " + max + " 字符）\n" + s.substring(s.length() - max);
    }

    private static synchronized ExecutorService io() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tianshu-diag");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return io;
    }
}
