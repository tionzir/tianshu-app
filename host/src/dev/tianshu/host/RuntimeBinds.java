package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * proot {@code -b} 清单的纯逻辑部分。
 *
 * 单独一个类是为了**可测**：这里不 import 任何 android.*，所以 HostTest
 * 能在容器里用普通 JVM 直接跑（{@link RuntimeHost} 带 Context，跑不动）。
 *
 * 存在的理由：App 的 rootfs 是干净重建的，`/root` 下只有 `.rivet/`，
 * App 里的天枢自己都说「没有可测的对象」。要让用户放进去的项目被读到，
 * 就得在启动参数里把共享存储 bind 进去。
 *
 * 机制已由 {@code host/probe-binds.sh} 实测（容器内，用 APK 里那个 proot）：
 *   - guest 目标目录**不必预先存在** —— proot 靠路径翻译虚拟化它，
 *     事后 rootfs 磁盘上不会多出目录；
 *   - host 路径不存在时 proot 只打一行 `can't sanitize binding` 警告，
 *     **照常启动**，只是该挂载点缺席 —— 所以拿不到存储权限时不能靠"少给一条 -b"来保命，
 *     那样只会静默少一个目录；要降级就得换一个有内容的 host 路径。
 */
public final class RuntimeBinds {

    /** guest 侧工作区 —— App 内天枢的工作目录入口，用户的代码放这儿。 */
    public static final String GUEST_WORKSPACE = "/root/workspace";

    /**
     * guest 侧的历史会话目录。harness 把会话写在 `$RIVET_HOME/sessions`（= `/root/.rivet/sessions`），
     * 而 `/root/.rivet` 在 **App 私有目录**里 —— 卸载 App 连带 rootfs 一起没，历史会话全丢。
     */
    public static final String GUEST_RIVET_SESSIONS = "/root/.rivet/sessions";

    /**
     * 共享存储的 guest 挂载点。第一条是主挂载点，其余是别名。
     *
     * 三个都挂的理由：用户/模型嘴里说的可能是 `/sdcard`、`/mnt/sdcard`
     * 或真路径 `/storage/emulated/0`，指向同一处才不会各说各话。
     * Termux 容器里 proot-distro 就是这么挂的。
     *
     * ⚠ 每条 guest 要有它**自己**的一条 `-b`（host 相同也不能合并成一条）：
     * proot 的 bind 是按 guest 路径登记的，少一条就少一个别名。
     * 更要紧的是下面 {@link #guestDirsToCreate} 那条注释说的"中间目录"问题。
     */
    public static final List<String> GUEST_SHARED = Collections.unmodifiableList(
            Arrays.asList("/mnt/sdcard", "/sdcard", "/storage/emulated/0"));

    /**
     * guest 挂载点要在 rootfs 里**预建**的目录（含全部中间层）。
     *
     * 为什么必须预建 —— 真机实测踩出来的：proot 有个 "glue rootfs" 机制，当 bind 的
     * guest 路径中间目录不存在时，它要在自己的临时目录里把中间层补出来。临时目录
     * 建不出来时（真机 `PROOT_TMP_DIR=/tmp` 不可写），proot 会把**这条 bind 静默丢掉**，
     * 只留一行 `sanitizing the guest path (binding) "…": No such file or directory`。
     *
     * 后果是不对称的，所以特别难查：父目录已存在的 bind（`/sdcard`、`/mnt/sdcard`）照常工作，
     * 只有需要补两层的 `/storage/emulated/0` 会没 —— 看起来像"真路径不能用"，不像"proot 坏了"。
     * 把中间层预先建进 rootfs，这条路径就完全不依赖 glue。
     *
     * @param withSharedStorage 与 {@link #args} 的入参保持一致 —— 不打算挂的挂载点
     *                          连目录都不建。否则没拿到存储权限时 guest 里会多出三个
     *                          **空目录**，那看起来像"共享存储是空的"，而不是"没挂上"
     */
    public static List<String> guestDirsToCreate(boolean withSharedStorage) {
        List<String> guests = new ArrayList<String>();
        if (withSharedStorage) guests.addAll(GUEST_SHARED);
        guests.add(GUEST_WORKSPACE);

        List<String> out = new ArrayList<String>();
        for (String guest : guests) {
            StringBuilder p = new StringBuilder();
            for (String seg : guest.split("/")) {
                if (seg.length() == 0) continue;
                p.append('/').append(seg);
                if (!out.contains(p.toString())) out.add(p.toString());
            }
        }
        return out;
    }

    private RuntimeBinds() {
    }

    /**
     * 拼出 {@code -b host:guest} 参数序列（已按 proot 需要的 `-b` + 值展开）。
     *
     * @param sharedStorageHost 共享存储的宿主路径；传 {@code null} 表示"这一侧不可用"，
     *                          此时不产生任何共享存储挂载点
     * @param workspaceHost     工作区宿主路径；传 {@code null} 表示不挂工作区。
     *                          调用方必须保证它**存在且可写** —— 见上面的探针结论
     * @return 形如 {@code ["-b", "h:/mnt/sdcard", "-b", "h:/root/workspace"]} 的列表
     */
    public static List<String> args(String sharedStorageHost, String workspaceHost) {
        List<String> a = new ArrayList<String>();
        if (sharedStorageHost != null && sharedStorageHost.length() > 0) {
            for (String guest : GUEST_SHARED) {
                a.add("-b");
                a.add(sharedStorageHost + ":" + guest);
            }
        }
        if (workspaceHost != null && workspaceHost.length() > 0) {
            a.add("-b");
            a.add(workspaceHost + ":" + GUEST_WORKSPACE);
        }
        return a;
    }

    /**
     * 把宿主侧的**持久会话目录** bind 到 guest 的会话目录，让历史会话活过卸载。
     *
     * 机制同工作区：guest 目标目录不必预先存在（父目录 `/root/.rivet` 在 rootfs 里已有，
     * 所以这条 bind 不依赖 proot 的 glue rootfs）。共享存储不支持符号链接（FUSE），
     * 但会话目录里只有普通文件与子目录，故可用。
     *
     * @param sessionsHost 持久目录的宿主路径；null/空 = 这一侧不可用 → **不挂**，
     *                     会话回到 App 私有目录（即"卸载即丢"的现状），但不影响启动
     * @return 形如 {@code ["-b", "h:/root/.rivet/sessions"]} 的列表
     */
    public static List<String> sessionArgs(String sessionsHost) {
        List<String> a = new ArrayList<String>();
        if (sessionsHost != null && sessionsHost.length() > 0) {
            a.add("-b");
            a.add(sessionsHost + ":" + GUEST_RIVET_SESSIONS);
        }
        return a;
    }
}
