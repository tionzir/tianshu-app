package dev.tianshu.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 测试 / 桌面端的 Sink 实现：用 java.nio.file。
 * Android 端另有一份基于 android.system.Os 的实现（API 21+ 才有 Os.symlink）。
 */
public class NioSink implements RootfsInstaller.Sink {

    @Override
    public void mkdirs(File dir) throws IOException {
        if (dir != null) Files.createDirectories(dir.toPath());
    }

    @Override
    public OutputStream create(File file) throws IOException {
        return new FileOutputStream(file);
    }

    @Override
    public void symlink(String target, File link) throws IOException {
        Files.deleteIfExists(link.toPath());
        Files.createSymbolicLink(link.toPath(), Paths.get(target));
    }

    @Override
    public void hardlink(File existing, File link) throws IOException {
        Files.deleteIfExists(link.toPath());
        Files.createLink(link.toPath(), existing.toPath());
    }

    @Override
    public void setMode(File file, int mode) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) return;
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        int m = mode & 0777;
        if ((m & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((m & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((m & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((m & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((m & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((m & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((m & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((m & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((m & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(file.toPath(), perms);
    }

    @Override
    public boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    /** 本环境能否建硬链接 —— 探测一次后缓存（见 {@link #supportsHardlink()}）。 */
    private Boolean hardlinkOk;

    /**
     * 本环境能否建硬链接 —— **实测一次，不是假设**。
     *
     * 2026-10-04 定位：原先硬编码 `true`，而本机文件系统的 `link(2)` 返回 EPERM；
     * 更糟的是它**失败时会把源文件一起删掉**（实测：`createLink` 抛 EPERM 之后
     * `perl` 消失 —— `before: perl.exists=true` → `after: perl.exists=false`），
     * 于是 RootfsInstaller 那句"失败就退回复制"退无可退，H 节 `testResume` 整节崩。
     *
     * 探测一次就能把这条路关掉；而**真正支持硬链接的机器上结果仍是 true**，
     * 依旧走硬链接分支，覆盖不变。
     */
    @Override
    public boolean supportsHardlink() {
        if (hardlinkOk == null) hardlinkOk = Boolean.valueOf(probeHardlink());
        return hardlinkOk.booleanValue();
    }

    /** 建个临时目录试一次 link，无论成败都清干净。 */
    private static boolean probeHardlink() {
        File dir = null;
        try {
            dir = Files.createTempDirectory("nio-hardlink-probe").toFile();
            File a = new File(dir, "a");
            File b = new File(dir, "b");
            Files.write(a.toPath(), new byte[] {1});
            try {
                Files.createLink(b.toPath(), a.toPath());
                return true;
            } catch (IOException notSupported) {
                return false;
            }
        } catch (IOException probeEnvBroken) {
            return false;    // 连探测环境都建不出来 —— 当不支持（退回复制总是安全的）
        } finally {
            if (dir != null) {
                File[] kids = dir.listFiles();
                if (kids != null) for (File k : kids) k.delete();
                //noinspection ResultOfMethodCallIgnored
                dir.delete();
            }
        }
    }
}
