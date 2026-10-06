package dev.tianshu.host;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把随包携带的配置写进 rootfs 的 RIVET_HOME（`~/.rivet`）。
 *
 * 为什么需要它：rootfs 是从 `ubuntu:24.04` 基础层重建的，里面**没有任何配置**，
 * serve 起来会停在 setup mode（`configured:false`）。而容器的 proot 只 bind 了
 * `com.termux`，够不到 App 的私有目录，Termux 侧也受 Android 沙箱限制 ——
 * 外部注入不了，只能由 App 自己在启动时铺进去。
 *
 * 四个文件缺一不可（实测出来的）：
 *   config.json         provider 定义，里面有 `keyRef: "deepseek"`
 *   provider-keys.json  keyRef 的登记表
 *   secrets.json        **密文**，真正的 API key 在里面
 *   .token-key          **解 secrets.json 的 local-key**。少了它，harness 会新生成一个
 *                       随机的，旧的密文就永远解不开 → 仍停在 setup mode
 *
 * 权限一律 0600。每次启动覆盖写入，所以改了配置重装即可生效。
 */
public final class ConfigInstaller {

    /** 目标权限：仅属主可读写。 */
    public static final int MODE = 0600;

    /**
     * 随包携带的配置清单 —— 这里给出的是**目标文件名**（落进 `~/.rivet` 之后的名字）。
     *
     * 注意包里的名字与它**不一定相同**：aapt2 打包时会丢弃 assets 里以 `.` 开头的文件
     * （实测：`assets/probe/.dotname` 不进 APK，`plainname` 进），
     * 所以 `.token-key` 在包里叫 `token-key`，映射规则见 {@link #assetNameFor}。
     */
    public static final List<String> CONFIG_FILES = java.util.Arrays.asList(
            "config.json", "provider-keys.json", "secrets.json", ".token-key");

    /** 目标文件名 -> 包内条目名（去掉前导点）。 */
    public static String assetNameFor(String targetName) {
        return targetName.startsWith(".") ? targetName.substring(1) : targetName;
    }

    public interface Source {
        /** 要落地的**目标文件名**（扁平的，不带路径）。 */
        String[] names() throws IOException;

        /** 按目标名打开对应的包内条目（实现方负责走 {@link #assetNameFor} 映射）。 */
        InputStream open(String targetName) throws IOException;
    }

    private ConfigInstaller() {
    }

    /** 写入并返回实际落地的文件名。 */
    public static List<String> install(Source src, File rivetHome, RootfsInstaller.Sink sink)
            throws IOException {
        sink.mkdirs(rivetHome);
        List<String> written = new ArrayList<String>();
        String[] names = src.names();
        if (names == null) return written;

        for (String name : names) {
            // 只接受扁平文件名 —— 不让 assets 里的名字越出 rivetHome
            if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || name.equals(".") || name.equals("..")) {
                continue;
            }
            File target = new File(rivetHome, name);
            // config.json 是**运行时状态文件** —— App 里 /yes /yolo /model /domain 这些命令
            // 改的就是它。每次启动都覆盖的话，用户设的审批模式/默认模型一重启就没了
            // （真机踩过：/yes 开了全自动，重启变回默认的「监督」）。
            // 所以它只在**不存在**时铺一次；其余三个是密钥类、App 内不改，照旧覆盖。
            if ("config.json".equals(name) && target.isFile() && target.length() > 0) {
                continue;
            }
            InputStream in = src.open(name);
            try {
                OutputStream out = sink.create(target);   // 已存在则覆盖
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
            sink.setMode(target, MODE);                   // 每次都重设，避免被 umask 放宽
            written.add(name);
        }
        return written;
    }

    /**
     * RIVET_HOME 算不算"已经配好了"。
     *
     * 判据取 `config.json` 存在且非空：它是 provider 定义所在，harness 少了它必然停在
     * setup mode（`configured:false`）。密钥才是"能不能真正对话"的前提，而密钥可以有
     * 两种来源（{@code rivet config set-key} 写进 secrets.json，或导入的密文四件套），
     * 所以这里不用它当判据 —— 否则导入路径会被判成"没配"。
     */
    public static boolean hasConfig(File rivetHome) {
        File cfg = new File(rivetHome, "config.json");
        return cfg.isFile() && cfg.length() > 0;
    }

    /**
     * 从**一个真实目录**读配置 —— 「导入配置」那条路用它。
     *
     * 与随包 assets 的区别：assets 里以 `.` 开头的条目会被 aapt2 丢掉（所以包内叫
     * `token-key`），需要 {@link #assetNameFor} 映射；而用户给的目录里文件名就是文件名，
     * 不需要任何映射。
     *
     * 只列出**确实存在**的那几个：用户可能只导入了三个（例如他那边本来就没有
     * `.token-key` —— 那是"旧密文解不开"的经典症状），少一个就少装一个，
     * 不替它编造一个空文件（编了反而会让 harness 以为配置齐全）。
     */
    public static final class DirSource implements Source {
        private final File dir;

        public DirSource(File dir) {
            this.dir = dir;
        }

        @Override
        public String[] names() {
            List<String> out = new ArrayList<String>();
            for (String n : CONFIG_FILES) {
                if (new File(dir, n).isFile()) out.add(n);
            }
            return out.toArray(new String[out.size()]);
        }

        @Override
        public InputStream open(String targetName) throws IOException {
            return new java.io.FileInputStream(new File(dir, targetName));
        }
    }
}
