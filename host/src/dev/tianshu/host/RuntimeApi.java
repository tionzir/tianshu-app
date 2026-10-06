package dev.tianshu.host;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 运行时 API 的薄封装 —— 后台线程发请求，回调回主线程。
 *
 * 为什么单独一个类：模型页 / 设置控制台 / 面板页都要打 HTTP，各写一遍的话
 * 认证头、超时、**错误体解析**会各分叉一次（这个项目已经踩过"同一件事各抄一份"的坑）。
 *
 * 错误处理的重点：服务端会用 `{"error":"defaultModel is required (…)"}` 告诉你要什么。
 * 把这句话原样带给界面，比显示"HTTP 400"有用一个数量级 —— 界面上直接能照做。
 */
public final class RuntimeApi {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RuntimeApi() {
    }

    /** 回调 —— 都在主线程执行，界面里可以直接改 View。 */
    public interface Cb {
        void ok(String body);

        void fail(String message);
    }

    public static void get(String path, Cb cb) {
        call("GET", path, null, cb);
    }

    public static void put(String path, String jsonBody, Cb cb) {
        call("PUT", path, jsonBody, cb);
    }

    public static void post(String path, String jsonBody, Cb cb) {
        call("POST", path, jsonBody, cb);
    }

    /** 改名走这个（`PATCH /sessions/:id`，body `{title}`）。 */
    public static void patch(String path, String jsonBody, Cb cb) {
        call("PATCH", path, jsonBody, cb);
    }

    /**
     * 归档 / 删除走这个（DELETE，无请求体）。
     *
     * ⚠ 路径语义别搞反：`/sessions/:id` = **归档**（软删、可恢复），
     * `/sessions/:id/permanent` 才是**删除**（不可逆）。见 {@link SessionActions}。
     */
    public static void delete(String path, Cb cb) {
        call("DELETE", path, null, cb);
    }

    private static void call(final String method, final String path,
                             final String body, final Cb cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] res = new String[2];      // [0]=body  [1]=错误
                try {
                    res[0] = doCall(method, path, body);
                } catch (Throwable e) {
                    res[1] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb == null) return;
                        if (res[1] != null) cb.fail(res[1]);
                        else cb.ok(res[0]);
                    }
                });
            }
        }, "tianshu-api");
        t.setDaemon(true);
        t.start();
    }

    private static String doCall(String method, String path, String body) throws Exception {
        HttpURLConnection c =
                (HttpURLConnection) new URL(AppRuntime.baseUrl() + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + AppRuntime.token());
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = c.getOutputStream();
                try {
                    out.write(body.getBytes(UTF8));
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                // 把服务端那句人话原样带出去（"… is required" 这类），别吞成"HTTP 400"
                throw new IllegalStateException("HTTP " + code + "：" + ApiError.human(text));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF8));
        String l;
        while ((l = br.readLine()) != null) sb.append(l).append('\n');
        br.close();
        return sb.toString();
    }
}
