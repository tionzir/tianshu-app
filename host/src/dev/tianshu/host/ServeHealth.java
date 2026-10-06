package dev.tianshu.host;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * serve 的**健康检查**与**等它就绪**（纯 java —— 不碰 android，HostTest 能直接断言）。
 *
 * ## 为什么把它收在这里
 * "serve 现在答不答？"这件事此前**各写了一遍**：`MainActivity.healthOnce()` 与
 * `ServeWatch.healthOk()` —— 两份实现、两套超时、改一处忘一处。现在只有一个地方。
 *
 * ## 为什么还要有 {@link #waitReady}
 * 因为真机踩过：配对页配完 key 会**重启 serve**（密钥写进 RIVET_HOME 后必须重启
 * 才生效），而重启的第一件事是 `AppRuntime.stopServe()` —— 它会把 `serveReady`
 * 置回 false。紧接着就跳对话页，于是对话页渲染时看到的是"没就绪"：
 *
 *   「运行环境还没准备好 —— 先回上一页等启动流程跑完」+ 发送按钮禁用
 *
 * 而把标志重新立起来只能靠 {@link ServeWatch} 的下一拍（**最多晚 10 秒**），
 * 且对话页不会因为标志变化自己重绘 —— 用户就一直卡在那句话上。
 *
 * 所以"重启之后等它就绪"必须是**重启方的责任**，不能指望下游某处迟早会补上。
 */
public final class ServeHealth {

    /** 单次探活的连接超时（ms）。 */
    public static final int CONNECT_TIMEOUT_MS = 1500;
    /** 单次探活的读超时（ms）。 */
    public static final int READ_TIMEOUT_MS = 2000;
    /** {@link #waitReady} 的轮询间隔（ms）。 */
    public static final int POLL_INTERVAL_MS = 500;

    private ServeHealth() {
    }

    /** 打一次 `/health`。任何异常都当"还没好"，不往外抛。 */
    public static boolean ok() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(AppRuntime.baseUrl() + "/health").openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            // 把响应读完再断开，免得连接池里留半截（探活很频繁）
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64];
            while (in.read(buf) > 0) {
                // 读掉就好，内容不关心
            }
            in.close();
            return c.getResponseCode() == 200;
        } catch (Throwable notUpYet) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                    // 断不断无所谓
                }
            }
        }
    }

    /**
     * 等 serve 就绪，**就绪时顺手把 {@link AppRuntime#markServeReady()} 立起来** ——
     * 这样调用方只要等这一下，下游页面读到的就是"就绪"。
     *
     * @param timeoutSec 最多等多少秒；&le;0 表示只探一次
     * @return 就绪 true；超时 false
     */
    public static boolean waitReady(int timeoutSec) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutSec) * 1000L;
        do {
            if (ok()) {
                AppRuntime.markServeReady();
                return true;
            }
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
        return false;
    }

    // ---------------------------------------------------------------- 「起来了」vs「能用」

    /**
     * `/health` 响应体里那个 `ok` 字段 —— serve **真的能干活**吗。
     *
     * 2026-10-05 在容器里用同源 rootfs 实测（只差一个 API key）：没配 provider 时
     * `/health` 返回 **HTTP 200**，体是 {@code {"ok":false,"version":"3.27.0"}} ——
     * 服务在跑、端口在听，但发消息必然失败。而 {@link #ok()} 只看状态码，于是
     * 「起来了但不能用」会被判成**就绪**：配 key 失败时用户看到的恰恰是"配置好了"。
     *
     * 所以两者各司其职、不要混：
     *   - {@link #ok()} 答"进程答不答"（{@link #waitReady} 用它，判据越松越好）；
     *   - 这个方法答"能不能干活"（配好之后自检用它，宁严勿松）。
     *
     * 读不懂（null / 空 / 不是 JSON / 没有 ok 字段）一律返回 **true** ——
     * 宁可放过，也不要因为一个读不明白的响应把正常状态判成坏。
     */
    public static boolean bodyOk(String json) {
        if (json == null || json.isEmpty()) return true;
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return true;
        Object v = root.get("ok");
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String s = Json.str(v);
        if (s == null || s.isEmpty()) return true;
        return !"false".equalsIgnoreCase(s);
    }

    /**
     * serve 起来了**且**能干活吗 —— 配好 key 之后的自检用它。
     *
     * 用途见 {@link #bodyOk}：这一步能抓住「HTTP 200 但没认出 key」那种假就绪。
     * 拉不到就返回 false（那本来就不算能用）。
     */
    public static boolean usable() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(AppRuntime.baseUrl() + "/health").openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && sb.length() < 4096) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            return bodyOk(sb.toString());
        } catch (Throwable notUpYet) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                    // 断不断无所谓
                }
            }
        }
    }
}
