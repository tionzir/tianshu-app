package dev.tianshu.host;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 在 rootfs 里跑一条**一次性**的 guest 命令（跑完即退）并取回结果。
 *
 * 与常驻的 serve 不同：这种进程要 `waitFor()` 等它结束。「换密钥」跑的就是这条 ——
 * harness 自己的 CLI：
 *
 * <pre>
 *   rivet config set-key '&lt;provider&gt;' '&lt;key&gt;'
 * </pre>
 *
 * 加密与落盘**全由 harness 负责**，App 既不实现加密、也不接触密钥格式。这条是实测出来的：
 * 在 rootfs 里跑 `rivet config set-key deepseek sk-probe-000` → `✔ API key set`，
 * exit=0，非交互。
 *
 * ## 为什么不各自写一份
 * 这段原先只长在首次配置页里（`SetupActivity.runCli`），而"配好之后换 key"在模型页
 * 也要跑同一条 CLI。两份实现迟早会分道 —— {@link ServeHealth} 的类注释记过同一种病
 *（"serve 现在答不答？"曾各写一遍，两套超时、改一处忘一处）。
 *
 * ## 为什么要超时（2026-10-06 修）
 * 原实现是「先 `while ((n = in.read(b)) > 0)` 读到底，再 `p.waitFor()`」——**两处都没有
 * deadline**，且 `InputStream` 从不 close。子进程只要不关 stdout（例如 CLI 卡在等一个
 * 永远不来的输入 / 拿不到锁），调用线程就**永久**阻塞：换密钥那条后台线程再也回不来，
 * 界面上表现为"点了重新填写密钥，然后就一直转"。
 * 现在读流走独立线程、主线程带 deadline 地 waitFor，超时 `destroyForcibly`。
 */
public final class GuestCli {

    private GuestCli() {
    }

    /**
     * 一次性 guest 命令的超时预算。
     *
     * `rivet config set-key` 实测在数秒内结束；2 分钟是"明显不正常"与"机器慢"之间的分界
     * （比 {@link ServeHealth} 的 waitReady(45s) 宽，因为这里多包了一层 npm CLI 启动）。
     */
    static final long TIMEOUT_MS = 120_000L;

    /**
     * 跑一条命令。**成功返回 null**，失败返回给人看的原因。
     *
     * ⚠️ 阻塞：要等子进程结束，所以**必须在后台线程调用**。
     *
     * @param script 要跑的命令（会被包进 `bash -lc`，见 {@link RuntimeHost#newBuilder}）
     */
    public static String run(RuntimeHost host, String script) {
        Process p = null;
        try {
            ProcessBuilder pb = host.newBuilder(
                    "export PATH=/usr/local/bin:/usr/bin:/bin; " + script);
            pb.redirectErrorStream(true);
            p = pb.start();
            Output o = collect(p, TIMEOUT_MS);
            if (o.code == 0) return null;
            if (o.code < 0) return "没写进去：" + o.text;              // 超时 / 执行失败
            return "没写进去（退出码 " + o.code + "）：" + o.text;
        } catch (Throwable t) {
            return "没写进去：" + t;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // 已经退出了就算了
                }
            }
        }
    }

    /** 一次收集的结果：退出码 + 合并输出（stderr 已并入）。{@code code < 0} = 超时 / 执行失败。 */
    static final class Output {
        final int code;
        final String text;

        Output(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    /**
     * 收完一个**已经 `start()` 过**的进程的输出，**带超时**。
     *
     * 读流放在独立线程 —— 主线程才能带 deadline 地 `waitFor`。否则 `read()` 在子进程不关
     * stdout 时永远不返回，超时根本没机会生效（那正是改前的病）。
     *
     * 包级可见且不依赖 android —— HostTest 直接拿 `sleep` 当靶子验超时语义。
     */
    static Output collect(Process p, long timeoutMs) {
        InputStream in = null;
        try {
            in = p.getInputStream();
            final InputStream fin = in;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = fin.read(b)) > 0) buf.write(b, 0, n);
                    } catch (Throwable ignored) {
                        // 读失败（多半是紧随其后的 destroy）——不算错误，交给退出码判定
                    }
                }
            }, "guest-cli-reader");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Output(-1, "超过 " + timeoutMs + " ms 没结束，已强制终止");
            }
            // 进程退了，读线程最多还剩最后一段没搬完；给它一个上限，不等出第二个死锁。
            try {
                reader.join(2000);
            } catch (Throwable ignored) {
                // InterruptedException：保持中断标记交回调用方
                Thread.currentThread().interrupt();
            }
            int code = p.waitFor();          // 已退出，立即返回
            return new Output(code, new String(buf.toByteArray(), "UTF-8").trim());
        } catch (Throwable t) {
            return new Output(-1, "执行失败：" + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关不掉也不影响结果
                }
            }
        }
    }
}
