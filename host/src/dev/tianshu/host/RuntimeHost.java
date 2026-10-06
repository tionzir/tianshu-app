package dev.tianshu.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 起 proot —— 这个 App 的"进程宿主"。
 *
 * 三个必须做对的点（前两个在 Gate 探针里实测出来的）：
 *   1. 可执行体必须来自 nativeLibraryDir（Android 10+ 只允许从那里 execve ELF）
 *   2. 必须设 LD_LIBRARY_PATH=nativeLibraryDir，否则 libproot.so 找不到 libtalloc.so.2
 *   3. 必须设 PROOT_LOADER 指向打包进来的 loader —— proot 默认去
 *      /data/data/com.termux/files/usr/libexec/proot/loader 找，那个路径在独立 App 里不存在
 *
 * 不 bind /system、不 bind Termux 目录：rootfs 里跑的是 glibc 二进制，用不上 bionic。
 *
 * 但**要** bind 共享存储与工作区 —— 否则 rootfs 的 /root 是空的，App 里的天枢
 * 没有可测的对象。位置与语义见 {@link RuntimeBinds}。
 */
public final class RuntimeHost {

    /** 共享存储的真路径（Android 上 /sdcard 与 /mnt/sdcard 都是它的别名）。 */
    public static final String SHARED_STORAGE = "/storage/emulated/0";

    /** 共享存储上给 App 用的工作区目录名 —— 用户往这里放项目。 */
    public static final String WORKSPACE_DIR_NAME = "tianshu-workspace";

    private final Context ctx;
    private File workspaceHostDir;
    private File sessionsHostDir;

    public RuntimeHost(Context ctx) {
        this.ctx = ctx;
    }

    public File nativeLibDir() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public File prootBinary() {
        return new File(nativeLibDir(), "libproot.so");
    }

    public File prootLoader() {
        return new File(nativeLibDir(), "libproot-loader.so");
    }

    public File rootfsDir() {
        return new File(ctx.getFilesDir(), "rootfs");
    }

    /**
     * 起一个**常驻** serve 的 proot 进程 —— 不 waitFor、不 destroy（理由见
     * {@code MainActivity.startPersistentServe} 的注释：proot 带 `--kill-on-exit`，
     * 它一退 serve 就没）。
     *
     * 抽成静态方法是为了让**守护**（{@link ServeWatch}）也能拉起它：守护跑在后台线程、
     * 手里只有一个 application context，够不到 MainActivity 那个实例。
     *
     * 调用方负责「先看有没有活的、别起第二个」那一步。
     */
    public static Process launchServe(RuntimeHost host) throws java.io.IOException {
        String script = "export PATH=/usr/local/bin:/usr/bin:/bin; "
                + "export RIVET_SERVER_TOKEN=" + AppRuntime.TOKEN + "; "
                + "exec rivet serve --port " + AppRuntime.PORT + " --host 127.0.0.1";
        File logDir = new File(host.rootfsDir(), "tmp");
        if (!logDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
        }
        ProcessBuilder pb = host.newBuilder(script);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(logDir, "host-serve.log"));
        return pb.start();
    }

    /**
     * 进程在 ms 毫秒内退出就返回 true —— 判定「刚起的 serve 是不是立刻就死了」。
     *
     * 端口被占时 serve 会**立刻**以 `EADDRINUSE` 退出，而正常启动要好几秒，
     * 所以短窗口足够区分（代价只是多等 0.6 秒 —— 本来就在等 serve，不敏感）。
     *
     * 抽到这儿是因为**每个起 serve 的地方都得问这一句**（启动、首次配置、模型页换密钥、
     * 守护重启）。漏问一次就会把一具死进程攥在手里：`isServeAlive()` 从此恒为 false，
     * 而 `/health` 还有另一个进程在答 —— 界面于是显示"就绪"，改的配置根本没生效。
     * （2026-10-04 的真机报告里就有这一幕：`serve: ready=true alive=false`。）
     */
    public static boolean exitedWithin(Process p, long ms) {
        if (p == null) return true;
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** 等一个进程真的退出（最多 ms 毫秒）—— 不等就抢端口，会撞 EADDRINUSE。 */
    public static void waitGone(Process p, long ms) {
        if (p == null) return;
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return;                        // 已经退了
            } catch (IllegalThreadStateException stillRunning) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public File stagingDir() {
        return new File(ctx.getFilesDir(), "rootfs.partial");
    }

    /**
     * 共享存储的宿主路径；读不到就返回 null。
     *
     * 判据是 {@code listFiles()} 是否返回 null —— 没拿到存储权限时读
     * /storage/emulated/0 会 EACCES，listFiles 返回 null 而不是空数组。
     * 这条判据的"反面"（有权限时真的看得到）只有真机能验，所以 boot 流程里
     * 会把两边都打到屏幕上。
     */
    public String sharedStorageHost() {
        File dir = new File(SHARED_STORAGE);
        if (dir.listFiles() == null) return null;
        return dir.getAbsolutePath();
    }

    /**
     * 工作区宿主目录 —— 用户的代码放这儿，会被 bind 成 rootfs 里的 /root/workspace。
     *
     * 三级降级，为的是 rootfs 里那个 /root/workspace 永不开天窗：
     *   1. 共享存储的 {@link #WORKSPACE_DIR_NAME}（用户最好找的位置）
     *   2. App 自己的外部媒体目录 .../Android/media/<pkg>/workspace
     *      —— 归属本 App，**不需要任何权限**，文件管理器也进得去
     *   3. 私有 filesDir（用户看不见，纯兜底）
     *
     * 为什么要降级而不是"拿不到就不挂"：实测 proot 对不存在的 host 路径只打一行
     * `can't sanitize binding` 警告就照常启动，挂载点缺席是**静默**的
     * （见 host/probe-binds.sh 场景 C）—— 静默少一个目录比换一个位置难查得多。
     */
    public File workspaceHost() {
        if (workspaceHostDir != null) return workspaceHostDir;

        File primary = new File(SHARED_STORAGE, WORKSPACE_DIR_NAME);
        if (ensureDir(primary)) {
            workspaceHostDir = primary;
            return workspaceHostDir;
        }

        File[] media = ctx.getExternalMediaDirs();
        if (media != null) {
            for (File m : media) {
                if (m == null) continue;
                File f = new File(m, "workspace");
                if (ensureDir(f)) {
                    workspaceHostDir = f;
                    return workspaceHostDir;
                }
            }
        }

        File last = new File(ctx.getFilesDir(), "workspace");
        ensureDir(last);
        workspaceHostDir = last;
        return workspaceHostDir;
    }

    /** mkdirs 对已存在的目录返回 false，所以先看 isDirectory 再建。 */
    private static boolean ensureDir(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }

    /**
     * proot 的临时目录（PROOT_TMP_DIR）—— 必须是 **App 侧已存在且可写**的宿主路径。
     *
     * 两个约束都是实测出来的：
     *   - 必须**已存在**：proot 只会往里面建子目录（glue rootfs），不会自己创建 PROOT_TMP_DIR。
     *     给的路径不存在时它直接 `can't create temporary directory`。
     *     （所以这里必须 ensureDir，不能只返回 File。）
     *   - 必须**可写**：Android 根目录下的 "/tmp" 要么不存在、要么只读，proot 因此建不出
     *     glue rootfs。真机实证见 {@code newBuilder} 里的注释。
     */
    public File prootTmpDir() {
        File dir = new File(ctx.getFilesDir(), "proot-tmp");
        ensureDir(dir);
        return dir;
    }

    /**
     * 在 rootfs 里预建 guest 挂载点的目录（含中间层），让 bind 不依赖 proot 的 glue rootfs。
     * 目录清单来自 {@link RuntimeBinds#guestDirsToCreate}（纯逻辑，有断言）。
     *
     * ⚠ 只在 rootfs **已经解压好**之后调用：`RootfsInstaller` 靠
     * `rootfsDir().isDirectory()` 判断"装过了、可以跳过解压"，在空 rootfs 上抢建
     * 一个子目录会让它误判成已安装。所以这里先看 rootfs 在不在，不在就直接返回 -1。
     *
     * @param withSharedStorage 与 buildArgv 里的判断保持一致
     * @return 实际确保存在的目录数；rootfs 未就位时返回 -1
     */
    public int prepareGuestMountPoints(boolean withSharedStorage) {
        if (!rootfsDir().isDirectory()) return -1;
        int n = 0;
        for (String guest : RuntimeBinds.guestDirsToCreate(withSharedStorage)) {
            if (ensureDir(new File(rootfsDir(), guest))) n++;
        }
        return n;
    }

    /**
     * 历史会话的**持久目录**（共享存储下）—— 卸载 App 不丢会话。
     *
     * 为什么放共享存储：本来会话在 `/root/.rivet/sessions`，那是 App 私有目录里的 rootfs，
     * 卸载 App 就连带清空。共享存储上的目录不受卸载影响，bind 进 guest 即可续用。
     *
     * **写探针**：共享存储是 FUSE，能力与内部存储不同（不支持符号链接、chmod 语义弱）。
     * 写不进去就**不挂**——否则会话连创建都失败，比"卸载即丢"更糟。
     *
     * @return 可用的持久目录；null = 这一侧不可用（降级为现状：会话留在 App 私有目录）
     */
    public File sessionsHostDir() {
        if (sessionsHostDir != null) return sessionsHostDir;
        String shared = sharedStorageHost();
        if (shared == null || shared.length() == 0) return null;
        File d = new File(shared, "tianshu-rivet/sessions");
        try {
            if (!d.isDirectory() && !d.mkdirs()) return null;
            File probe = new File(d, ".write-probe");
            if (!probe.createNewFile() && !probe.isFile()) return null;   // 能建/已在 = 可写
            if (!probe.delete()) return null;
        } catch (Throwable e) {
            return null;
        }
        if (!d.isDirectory()) return null;
        sessionsHostDir = d;
        return d;
    }

    public List<String> buildArgv(String script) {
        List<String> a = new ArrayList<String>();
        a.add(prootBinary().getAbsolutePath());
        a.add("--kill-on-exit");
        // --link2symlink（-l）：把 guest 里的 **硬链接**系统调用改写成建**符号链接**。
        //
        // 为什么必须有（2026-10-06 真机实测）：Android 的 App 私有存储**不允许创建硬链接** ——
        //   在 files/ 下 `ln a b` → `Permission denied`（EACCES），与 SELinux/文件系统策略一致。
        // harness **3.28.0** 起用 `link()` 建 sidecar 锁（`/root/.rivet/desktop/sidecar.lock`）；
        // 硬链接一被拒，serve 就打「会话库已被别的进程独占 —— 降级运行，不触碰会话库」，
        // 结果是 `sessions=0 / registryOk=false / readiness=failed`，App 等于残废。
        // 3.27.0 不走这条路，所以以前没暴露。
        //
        // Termux 的 proot-distro 一直带这个开关，正是同一个原因 —— 不是新发明。
        // 代价：guest 内所有硬链接退化成符号链接（本 App 的 rootfs 解压走 Java，不经 proot，
        // 不受影响；guest 内也不会跑 npm/apt 这类依赖硬链接的工具）。
        a.add("--link2symlink");
        a.add("-0");
        a.add("-r");
        a.add(rootfsDir().getAbsolutePath());
        a.add("-w");
        a.add("/root");
        a.add("-b");
        a.add("/dev");
        a.add("-b");
        a.add("/proc");
        a.add("-b");
        a.add("/sys");
        a.addAll(RuntimeBinds.args(sharedStorageHost(), workspaceHost().getAbsolutePath()));
        File sessions = sessionsHostDir();                       // 历史会话活过卸载
        if (sessions != null) a.addAll(RuntimeBinds.sessionArgs(sessions.getAbsolutePath()));
        a.add("/bin/bash");
        a.add("-lc");
        a.add(script);
        return a;
    }

    public ProcessBuilder newBuilder(String script) {
        ProcessBuilder pb = new ProcessBuilder(buildArgv(script));
        pb.redirectErrorStream(true);
        pb.directory(rootfsDir());

        Map<String, String> env = pb.environment();
        String nld = nativeLibDir().getAbsolutePath();
        env.put("PROOT_LOADER", prootLoader().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nld);
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("HOME", "/root");
        // TMPDIR 是给**guest 里**跑的东西看的，必须留 guest 内的路径。
        env.put("TMPDIR", "/tmp");
        // Termux 版 proot 的编译期默认临时目录是 $PREFIX/tmp，独立 App 里那个路径不存在。
        // 曾经把它设成 "/tmp" —— 那是**真机上的错**：Android 根目录下没有可写的 /tmp，
        // proot 因此建不出 glue rootfs，凡是 guest 路径中间目录不存在的 bind 会被静默丢掉。
        // 真机实证（boot 第 3 步的原文）：
        //   proot error: can't create temporary directory: Permission denied
        //   proot error: can't create glue rootfs
        //   proot warning: sanitizing the guest path (binding) "/storage/emulated/0": ...
        // 症状是不对称的：父目录已存在的 /sdcard、/mnt/sdcard 照常工作，只有
        // /storage/emulated/0 没了 —— 看起来像"真路径不能用"，不像"proot 坏了"。
        // 改用 App 私有目录下的 proot-tmp；另一道防线见 RuntimeBinds.guestDirsToCreate。
        env.put("PROOT_TMP_DIR", prootTmpDir().getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        return pb;
    }

    /** 环境自检用：把 App 侧看到的关键路径拼成一段文本。 */
    public String describeEnvironment() {
        ApplicationInfo ai = ctx.getApplicationInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("nativeLibraryDir : ").append(ai.nativeLibraryDir).append('\n');
        sb.append("dataDir          : ").append(ai.dataDir).append('\n');
        sb.append("filesDir         : ").append(ctx.getFilesDir().getAbsolutePath()).append('\n');
        sb.append("proot            : ").append(prootBinary().getAbsolutePath())
                .append("  exists=").append(prootBinary().exists())
                .append("  canExec=").append(prootBinary().canExecute()).append('\n');
        sb.append("loader           : ").append(prootLoader().getAbsolutePath())
                .append("  exists=").append(prootLoader().exists()).append('\n');
        sb.append("rootfs           : ").append(rootfsDir().getAbsolutePath())
                .append("  installed=").append(rootfsDir().isDirectory()).append('\n');
        String shared = sharedStorageHost();
        sb.append("shared storage   : ").append(shared == null
                        ? "读不到（没存储权限？见下面「存储权限」一行）→ 不挂 /mnt/sdcard"
                        : shared + "（会挂到 /mnt/sdcard 与 /sdcard）")
                .append('\n');
        sb.append("workspace(host)  : ").append(workspaceHost().getAbsolutePath())
                .append('\n');
        sb.append("workspace(guest) : ").append(RuntimeBinds.GUEST_WORKSPACE)
                .append("  ← 用户的代码放这儿\n");
        sb.append("proot tmp (host) : ").append(prootTmpDir().getAbsolutePath())
                .append("  writable=").append(prootTmpDir().canWrite())
                .append("  ← 不可写会让 proot 建不出 glue rootfs，静默丢 bind\n");
        return sb.toString();
    }
}
