package dev.tianshu.host;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * 把运行报告**落到共享存储** —— 与 {@link LogReport}（拼装，纯逻辑）分工：
 * 这里只负责"采集"和"写盘"这些碰 Android 的事。
 *
 * 落到 `/storage/emulated/0/tianshu-log/` 而不是 App 私有目录，是因为那份报告
 * 就是给**外部**看的（人也好、容器里的工具也好）：私有目录要 adb 或 root 才够得着，
 * 而共享存储用文件管理器就能打开、拷走。
 *
 * 同时写两份：带时间戳的那份留着当历史，{@code latest.txt} 固定名 ——
 * 外部不必去猜"最新一份叫什么"（这次就是这么读到你那张截图的思路）。
 */
public final class LogExport {

    /** 导出目录（共享存储）。 */
    public static final String DIR = "/storage/emulated/0/tianshu-log";

    /** serve 日志在 rootfs 里的相对位置（与 launchServe 里 redirectOutput 的一致）。 */
    public static final String SERVE_LOG_REL = "tmp/host-serve.log";

    /** serve 日志只取尾部这么多个字节 —— 它可能很长，报告不该被它拖爆。 */
    private static final int SERVE_LOG_TAIL_BYTES = 256 * 1024;

    private LogExport() {
    }

    /**
     * 采集环境与运行时状态、连同两份日志拼成报告写出。
     *
     * @return 落地的主文件名（同目录另有 {@link LogReport#LATEST_FILE} 内容相同）
     */
    public static String write(Context ctx, RuntimeHost host) throws IOException {
        long now = System.currentTimeMillis();
        String report = LogReport.compose(
                LogReport.stamp(now),
                environment(ctx),
                runtimeState(ctx, host),
                BootLog.text(),
                readServeLog(host));

        File dir = new File(DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("建不出导出目录：" + dir);
        }
        String name = LogReport.fileName(now);
        writeFile(new File(dir, name), report);
        writeFile(new File(dir, LogReport.LATEST_FILE), report);
        return name;
    }

    private static void writeFile(File f, String text) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(text.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    /** Android 版本 / ABI / 机型 / App 版本 / **heap 上限**。 */
    static String environment(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "?";
        sb.append("ABI ").append(abi)
                .append(" · 设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n');
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            sb.append("App ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable t) {
            sb.append("App 版本读不到：").append(t).append('\n');
        }
        // heap 上限是排查"内存类失败"的关键一行：分得清是库的限制还是设备真给不出
        Runtime rt = Runtime.getRuntime();
        sb.append("Java heap 上限 ").append(rt.maxMemory() / (1024 * 1024)).append(" MB")
                .append(" · 当前已用 ")
                .append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append(" MB\n");
        return sb.toString();
    }

    /** rootfs 装没装 / 配没配 / serve 什么状态。 */
    static String runtimeState(Context ctx, RuntimeHost host) {
        StringBuilder sb = new StringBuilder();
        File rootfs = host.rootfsDir();
        sb.append("rootfs 已安装: ").append(rootfs.isDirectory() ? "是" : "否").append('\n');
        File rivetHome = new File(rootfs, "root/.rivet");
        sb.append("~/.rivet 已配置: ")
                .append(ConfigInstaller.hasConfig(rivetHome) ? "是" : "否").append('\n');
        sb.append("serve: ready=").append(AppRuntime.isServeReady())
                .append(" alive=").append(AppRuntime.isServeAlive())
                .append(" url=").append(AppRuntime.baseUrl()).append('\n');
        String err = AppRuntime.lastError();
        if (err != null && !err.isEmpty()) {
            sb.append("lastError: ").append(err).append('\n');
        }
        return sb.toString();
    }

    /** 读 serve 日志的尾部；不存在/读不到都返回 null（报告里会显示「（无）」）。 */
    static String readServeLog(RuntimeHost host) {
        File f = new File(host.rootfsDir(), SERVE_LOG_REL);
        if (!f.isFile()) return null;
        RandomAccessFile raf = null;
        try {
            long len = f.length();
            if (len <= 0) return null;
            long from = Math.max(0, len - SERVE_LOG_TAIL_BYTES);
            raf = new RandomAccessFile(f, "r");
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            return from > 0 ? "…（仅尾部 " + (SERVE_LOG_TAIL_BYTES / 1024) + " KB）\n" + s : s;
        } catch (Throwable t) {
            return "（读 serve 日志失败：" + t + "）";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                    // 关不上就算了，报告已经拿到了
                }
            }
        }
    }
}
