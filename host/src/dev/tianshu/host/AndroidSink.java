package dev.tianshu.host;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Android 端的 Sink：用 android.system.Os（API 21+），不用 java.nio.file（那是 API 26+）。
 *
 * rootfs 里遍布符号链接（/bin -> usr/bin 之类），所以 symlink 必须真建出来 ——
 * 这是整棵树能不能跑起来的前提。
 */
public final class AndroidSink implements RootfsInstaller.Sink {

    @Override
    public void mkdirs(File dir) throws IOException {
        if (dir == null) return;
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("建目录失败: " + dir);
    }

    @Override
    public OutputStream create(File file) throws IOException {
        return new FileOutputStream(file);
    }

    @Override
    public void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("symlink 失败: " + link + " -> " + target + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public void hardlink(File existing, File link) throws IOException {
        try {
            Os.link(existing.getAbsolutePath(), link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("硬链接失败: " + link + " <- " + existing + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public void setMode(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (ErrnoException e) {
            throw new IOException("chmod 失败: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public boolean isSymlink(File file) {
        try {
            StructStat st = Os.lstat(file.getAbsolutePath());
            return (st.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /**
     * Android 的 app 私有目录对 untrusted_app 域禁 link(2) —— 真机实测 EACCES
     * （在 rootfs 解压到 /usr/bin/perl5.38.2 时撞上）。所以这里直接声明不支持，
     * 由 RootfsInstaller 回退成复制文件。
     */
    @Override
    public boolean supportsHardlink() {
        return false;
    }
}
