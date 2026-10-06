package dev.tianshu.host;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.tukaani.xz.XZInputStream;

/**
 * 把 assets 里的 rootfs.tar.xz 装到私有目录。
 *
 * 两条硬不变量：
 *   1. 只往目标目录里写 —— 归档里出现 ../ 或绝对路径一律丢弃（外部输入不可信）
 *   2. 原子可见 —— 解压全程写 staging，成功后一次性改名；目标已存在即跳过
 *      （App 被中途杀掉不会留下"半个 rootfs 被当成好的"）
 *
 * 文件系统副作用走 {@link Sink} 抽象：Android 端用 android.system.Os（API 21+），
 * 测试/桌面端用 java.nio。这样解压逻辑本身可以在没有 Android 的机器上跑测试。
 *
 * 本类刻意不引用 java.nio.file.* —— 那要 API 26，与本 App 的 minSdk 不符。
 */
public final class RootfsInstaller {

    public static final class Result {
        public boolean installed;
        public long files;
        public long bytes;

        /** 这次是从第几个条目**接着装**的；0 = 从头装的。给启动日志用，真机上一眼可判。 */
        public long resumedFrom;
    }

    public interface Sink {
        void mkdirs(File dir) throws IOException;

        OutputStream create(File file) throws IOException;

        void symlink(String target, File link) throws IOException;

        void hardlink(File existing, File link) throws IOException;

        void setMode(File file, int mode) throws IOException;

        boolean isSymlink(File file);

        /**
         * 该环境是否允许建硬链接。
         * Android 的 app 私有目录对 untrusted_app 域禁 link(2)（返回 EACCES，实机已撞到），
         * 所以 AndroidSink 返回 false，由本类回退成复制 —— 语义等价，只是多占一份空间。
         */
        boolean supportsHardlink();
    }

    public interface Progress {
        void onEntry(String name, long filesDone, long bytesDone);
    }

    /**
     * 解压时允许 xz 用的**内存上限**（KiB）—— 必须容得下 `pack-lean.sh` 打出来的包。
     *
     * 那个脚本用 `xz -9e` 打包，LZMA2 字典 64 MiB，实测解压需要 **65640 KiB**；
     * 这里原先写的是 `1 << 16`（65536 KiB）—— **差 104 KiB**。于是卸载重装
     * （首次真正解压）时直接失败：
     *
     *   MemoryLimitException: 65640 KiB of memory would be needed; limit was 65536 KiB
     *
     * 之所以一直没暴露：覆盖安装时 `if (destDir.isDirectory()) return` 幂等跳过了解压，
     * 只有卸载之后才会第一次走这条路（2026-10-04 真机踩到）。
     *
     * 给 256 MiB，留 4 倍余量。**上限只是"允许"，并不预占内存**。
     * ⚠️ 想调小之前先看 {@code HostTest} 的 Z′ 节 —— 它用同档压缩造包，会立刻变红。
     */
    static final int XZ_MEMORY_LIMIT_KIB = 1 << 18;      // 256 MiB

    /**
     * 安装的**全局互斥锁** —— 防"两条线程同时写同一个 staging、各自 renameTo 同一个 dest"。
     *
     * 为什么需要：安装可能被两条线程同时驱动 —— MainActivity 的 boot 跑在后台线程，而它
     * "只跑一次"靠的是**实例字段** bootStarted；Activity 一旦被重建就会再起一条 boot 线程。
     * 两条线程并发写、最后各自 `renameTo`：轻则第二次 rename 失败报"启动没成功"（rootfs
     * 其实好的），重则目录写坏。manifest 的 configChanges 是第一道防线，这把锁是第二道。
     *
     * 为什么是**一把**静态锁，而不是"按 destDir 分锁"：
     *   这个 App 只会装**一个** rootfs（`<filesDir>/rootfs`），按目标目录分锁换来的是用不上的
     *   并发度，代价却是一张**永不清理**的映射表。而"用完就删"是错的 —— 线程 B 可能已经拿到了
     *   那个 monitor 对象，此时删掉条目、线程 C 再建一个新的，B 与 C 就各持一把锁，互斥当场失效。
     *   一把静态锁没有这个两难，也不需要任何簿记。
     *
     * 锁的范围是**整个 install**（含解压与最后的 rename）。拿到锁的第二条线程会看到 destDir
     * 已就位，install 开头那句幂等判断直接返回 —— 不会重复解压。
     */
    private static final Object INSTALL_LOCK = new Object();

    private RootfsInstaller() {
    }

    /** 只接受归档内的相对路径；拒绝绝对路径与任何 .. 片段。 */
    public static boolean isSafeRelativePath(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) return false;
        for (String part : n.split("/")) {
            if (part.equals("..")) return false;
        }
        return true;
    }

    /** 从文件装。 */
    public static Result install(File xzFile, File destDir, File stagingDir,
                                 Sink sink, Progress cb) throws IOException {
        return install(xzFile, destDir, stagingDir, sink, cb, null);
    }

    /** 从文件装（带指纹，见下面那个重载的说明）。 */
    public static Result install(File xzFile, File destDir, File stagingDir,
                                 Sink sink, Progress cb, String fingerprint) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(xzFile), 1 << 16)) {
            return install(in, destDir, stagingDir, sink, cb, fingerprint);
        }
    }

    /** 从任意流装 —— Android 端直接喂 assets 流，不必先把 89MB 包拷一份。 */
    public static Result install(InputStream xzStream, File destDir, File stagingDir,
                                 Sink sink, Progress cb) throws IOException {
        return install(xzStream, destDir, stagingDir, sink, cb, null);
    }

    /**
     * 带**指纹**的安装 —— 指纹用于判断"上次没装完的 staging 还能不能接着用"。
     *
     * @param fingerprint 标识这份 tar 的稳定字符串（如 "versionCode:字节数"）；
     *                    传 null = 永不续传（老行为，失败就整体重来）。
     *                    只有指纹一致才续传 —— 换了包还接着用旧 staging 会得到一个
     *                    新旧混杂的 rootfs，那比重新装一遍糟得多。
     */
    public static Result install(InputStream xzStream, File destDir, File stagingDir,
                                 Sink sink, Progress cb, String fingerprint) throws IOException {
        // 全局串行化：并发驱动的两次安装绝不能交错写同一个 stagingDir（见 INSTALL_LOCK）。
        synchronized (INSTALL_LOCK) {
            return installLocked(xzStream, destDir, stagingDir, sink, cb, fingerprint);
        }
    }

    /** 真正的安装实现 —— 只在 {@link #install(InputStream, File, File, Sink, Progress, String)} 的锁内被调用。 */
    private static Result installLocked(InputStream xzStream, File destDir, File stagingDir,
                                        Sink sink, Progress cb, String fingerprint) throws IOException {
        Result r = new Result();

        // 幂等：已经装好就直接跳过
        if (destDir.isDirectory()) return r;

        File marker = markerFor(stagingDir);
        long resumeFrom = fingerprint == null ? -1L : readMarker(marker, fingerprint);
        if (resumeFrom < 0) {
            deleteRecursively(stagingDir, sink);          // 指纹不符或没有标记 → 整体重来
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
            resumeFrom = 0L;
        }
        sink.mkdirs(stagingDir);

        long entries = 0;
        long files = 0;
        long bytes = 0;
        try (XZInputStream xz = new XZInputStream(
                new BufferedInputStream(xzStream, 1 << 16), XZ_MEMORY_LIMIT_KIB)) {

            // 本次安装已经建过的**符号链**相对路径 —— 用来挡住"先建链、再往里写"的越界（见 hasSymlinkAncestor）
            java.util.Set<String> symlinkPaths = new java.util.HashSet<String>();
            int blocked = 0;                       // 被安全策略丢弃的条目数（正常归档恒为 0）
            TarReader.Entry e;
            while ((e = TarReader.readHeader(xz)) != null) {
                entries++;
                // 续传：上次装过的条目跳过 —— 数据仍要读掉（顺序流没法 seek），但不再写盘，
                // 而写盘正是这 518 MB / 12607 个文件里最慢的一段
                if (entries <= resumeFrom) {
                    // ⚠️ 跳过 ≠ 什么都不做：**符号链必须照常登记**。
                    //
                    // 续传的语义必须与"一次装完"逐条一致。第一次装时，每条符号链都会进
                    // symlinkPaths，后面写在它路径之下的条目会被 hasSymlinkAncestor 拦下；
                    // 而续传时这里直接 continue 的话，前缀里的符号链**不会进集合**，防御就只在
                    // "没中断过"时成立 —— 归档里放 `esc -> ../outside` 再放 `esc/pwned`，
                    // 首次能挡住，恰在两者之间中断后续传就挡不住了：hasSymlinkAncestor 看不见
                    // esc，`mkdirs(stagingDir/esc)` 顺着符号链把文件写到目标目录**之外**。
                    //
                    // 这些条目的 header 本来就已经解析出来了（跳过的是数据，不是头），
                    // 所以这里不额外花钱 —— 记一笔，语义就与首次安装对齐。
                    if (e.isSymlink() && isSafeRelativePath(e.name)) {
                        symlinkPaths.add(normalize(e.name));
                    }
                    TarReader.skipData(xz, e.size);
                    continue;
                }
                if (!isSafeRelativePath(e.name)) {
                    TarReader.skipData(xz, e.size);
                } else if (hasSymlinkAncestor(e.name, symlinkPaths)) {
                    // 「先建一条符号链、再往里写」是 tar 里经典的越界手法（2026-10-04 深查发现，
                    // 修前实测确实能写到目标目录之外）。祖先里有**本次刚建过的符号链** → 直接丢弃。
                    TarReader.skipData(xz, e.size);
                    blocked++;
                } else {
                    File target = new File(stagingDir, normalize(e.name));
                    // 续传时上一次"写了一半就崩"的残条目会留在盘上，先清掉再建 ——
                    // 否则符号链接/硬链接会撞 EEXIST，把整个续传打断（有断言钉住）
                    removeStaleEntry(target, sink);

                    if (e.isDirectory()) {
                        sink.mkdirs(target);
                        sink.setMode(target, e.mode);
                    } else if (e.isRegularFile()) {
                        sink.mkdirs(target.getParentFile());
                        OutputStream out = sink.create(target);
                        try {
                            copyExactly(xz, out, e.size);
                        } finally {
                            out.close();
                        }
                        sink.setMode(target, e.mode);
                        files++;
                        bytes += e.size;
                    } else if (e.isSymlink()) {
                        sink.mkdirs(target.getParentFile());
                        sink.symlink(e.linkName, target);
                        symlinkPaths.add(normalize(e.name));   // 记下来，供后面的条目判祖先
                        files++;
                    } else if (e.isHardLink()) {
                        // ⚠️ linkName 是**归档内**的路径，安全要求与条目名完全一样 ——
                        //    修前这里一个校验都没有，`hardlink → ../x` 能把 staging 之外的文件链进 rootfs。
                        if (!isSafeRelativePath(e.linkName)) {
                            blocked++;
                        } else {
                            File src = new File(stagingDir, normalize(e.linkName));
                            sink.mkdirs(target.getParentFile());
                            // 先**试**硬链接；OS 拒绝就退回复制 —— 只信 supportsHardlink() 那一次
                            // 提前探测是不够的：真机实测 app 私有目录的 link(2) 会 EACCES（坑 20），
                            // 探测与真实结果不一致时，原先整个安装会直接崩。
                            if (sink.supportsHardlink() && tryHardlink(src, target, sink)) {
                                // 链上了，什么都不用再做
                            } else {
                                copyFile(src, target);      // 语义等价：多一个 inode，占两份空间
                                sink.setMode(target, e.mode);
                            }
                            files++;
                        }
                    } else {
                        TarReader.skipData(xz, e.size);     // 设备/管道等，rootfs 用不着
                    }
                }

                // 每个条目**完全落地之后**才记进度。崩在记录之前，续传时重做这一条 —— 重做是安全的。
                if (fingerprint != null) writeMarker(marker, fingerprint, entries);
                if (cb != null) cb.onEntry(e.name, files, bytes);
            }
        } catch (IOException ex) {
            // 有指纹就留着 staging 与标记，下次接着装；没指纹维持老行为（失败不留半成品）。
            // **空间不足是例外**：半装的 staging 只会让重试继续失败，留着纯占地方，一并清掉。
            String m = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            boolean noSpace = m.contains("enospc") || m.contains("no space");
            if (fingerprint == null || noSpace) deleteRecursively(stagingDir, sink);
            throw ex;
        }

        // 原子可见：最后一步才让目标目录出现
        deleteRecursively(destDir, sink);
        if (!stagingDir.renameTo(destDir)) {
            throw new IOException("staging 改名到目标失败: " + stagingDir + " -> " + destDir);
        }
        //noinspection ResultOfMethodCallIgnored
        marker.delete();                                  // 装完了，续传标记该走
        r.installed = true;
        r.files = files;
        r.bytes = bytes;
        r.resumedFrom = resumeFrom;
        return r;
    }

    // ---------------------------------------------------------------- 工具
    static String normalize(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * 试着建硬链接；**OS 拒绝时返回 false，让上层退回复制**。
     *
     * 为什么不能只靠 {@link Sink#supportsHardlink()}：那是一次"提前问"，而链接能不能建
     * 取决于**具体目录**（Android 的 app 私有目录禁止 link(2)），
     * 探测与真实结果不一致时原先会直接崩。改成"先试、失败就退"，与
     * {@code copyFile} 那句"硬链接不可用时回退为复制"的语义对齐。
     */
    private static boolean tryHardlink(File src, File target, Sink sink) throws IOException {
        try {
            sink.hardlink(src, target);
            return true;
        } catch (IOException notSupported) {
            // ⚠ 有些文件系统在 link(2) 失败时会把**源文件**一并删掉（2026-10-04 本机实测：
            //    createLink 抛 EPERM 之后源文件消失）。那种情况下"退回复制"无源可拷 ——
            //    必须在这里显式失败，否则上层只会收到 copyFile 的一句 "no such file"，
            //    把真正的原因（硬链接把源吃掉了）藏起来，排查要多绕好几步。
            if (!src.exists()) {
                throw new IOException("硬链接失败且源文件已消失（" + src + "）："
                        + notSupported.getMessage(), notSupported);
            }
            return false;
        }
    }

    /**
     * 路径的**祖先**里有没有"本次刚建过的符号链"。
     *
     * 为什么需要：归档里可以先放一条 `esc -> ../outside`，再放 `esc/pwned` —— 后者的
     * `mkdirs`/`create` 会**跟着符号链写到目标目录之外**（2026-10-04 深查实测复现）。
     * 判祖先只用**纯字符串前缀**、不做 canonical 系统调用：安装要过一万多条，省下来的是整段时间。
     *
     * @param rel   条目名（内部会 normalize）
     * @param links 本次安装已建过的符号链相对路径集合（normalize 过）
     */
    static boolean hasSymlinkAncestor(String rel, java.util.Set<String> links) {
        if (rel == null || links == null || links.isEmpty()) return false;
        String path = normalize(rel);
        if (path.isEmpty()) return false;
        int at = path.indexOf('/');
        while (at > 0) {
            if (links.contains(path.substring(0, at))) return true;
            at = path.indexOf('/', at + 1);
        }
        return links.contains(path);   // 条目名自己就是那条链（往链上写同样是穿写）
    }

    // ---------------------------------------------------------------- 断点续传

    /**
     * 续传标记文件。**放在 staging 旁边，不放里面** ——
     * 放里面的话，最后 `renameTo(destDir)` 会把标记一起带进 rootfs，脏了用户的根目录。
     */
    static File markerFor(File stagingDir) {
        File parent = stagingDir.getAbsoluteFile().getParentFile();
        return new File(parent, stagingDir.getName() + ".resume");
    }

    /**
     * @return 上次已完成到第几个条目；-1 = 不可续传（没有标记 / 指纹不符 / 标记坏了）。
     *
     * 标记坏了就当没有：最坏是从头装一遍，不会更差 —— 所以这里一律吞掉异常返回 -1，
     * 而不是把脏标记当有效数据用。
     */
    static long readMarker(File marker, String fingerprint) {
        try {
            if (!marker.isFile()) return -1L;
            String[] lines = readSmallFile(marker).trim().split("\n");
            if (lines.length < 2) return -1L;
            if (!fingerprint.equals(lines[0].trim())) return -1L;
            long n = Long.parseLong(lines[1].trim());
            return n < 0L ? -1L : n;
        } catch (Throwable bad) {
            return -1L;
        }
    }

    /** 记进度。写不进去不算失败 —— 只是下次不能续，不该因此让安装本身崩掉。 */
    static void writeMarker(File marker, String fingerprint, long entries) {
        try {
            OutputStream out = new FileOutputStream(marker);
            try {
                out.write((fingerprint + "\n" + entries + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // 见上
        }
    }

    /**
     * 清掉续传时可能残留的"写了一半"条目。
     *
     * **只清非目录**：目录留着（下面 `mkdirs` 幂等），而且真删目录会把已解压的子文件一起带走。
     *
     * 这一步不是可选的：实测「符号链接刚建好就崩」这种中断，残下来的链接会在续传时
     * 撞 EEXIST 把整个安装打断（H 套件有断言钉住）。
     */
    private static void removeStaleEntry(File target, Sink sink) {
        if (!target.exists()) return;
        if (target.isDirectory() && !sink.isSymlink(target)) return;
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private static String readSmallFile(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > 4096) throw new IOException("标记文件大小异常: " + len);
        byte[] b = new byte[(int) len];
        InputStream in = new FileInputStream(f);
        try {
            int off = 0;
            int n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        } finally {
            in.close();
        }
        return new String(b, "UTF-8");
    }

    static void deleteRecursively(File f, Sink sink) {
        if (f == null || !f.exists()) return;
        boolean link = sink.isSymlink(f);
        if (f.isDirectory() && !link) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k, sink);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(src), 1 << 16);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("归档数据被截断，缺 " + left + " 字节");
            out.write(buf, 0, n);
            left -= n;
        }
        long pad = (512 - (size % 512)) % 512;
        long skipped = 0;
        while (skipped < pad) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, pad - skipped));
            if (n < 0) return;
            skipped += n;
        }
    }

    /** 平凡 Sink：只做普通文件，符号链接会抛异常。给"不需要链接"的场合兜底。 */
    public static final class PlainSink implements Sink {
        @Override
        public void mkdirs(File dir) throws IOException {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("建目录失败: " + dir);
            }
        }

        @Override
        public OutputStream create(File file) throws IOException {
            return new FileOutputStream(file);
        }

        @Override
        public void symlink(String target, File link) throws IOException {
            throw new IOException("当前 Sink 不支持符号链接: " + link);
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            throw new IOException("当前 Sink 不支持硬链接: " + link);
        }

        @Override
        public void setMode(File file, int mode) throws IOException {
            if ((mode & 0100) != 0) {
                //noinspection ResultOfMethodCallIgnored
                file.setExecutable(true, false);
            }
        }

        @Override
        public boolean isSymlink(File file) {
            return false;
        }

        @Override
        public boolean supportsHardlink() {
            return false;
        }
    }
}
