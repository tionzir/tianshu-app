package dev.tianshu.host;

/**
 * API 错误体的人话化（纯逻辑）。
 *
 * 为什么单独一个类：服务端返回 400 时，body 里那句 `{"error":"defaultModel is required
 * (\"provider:modelId\" format)"}` 比"HTTP 400"有用一个数量级 —— 界面上可以直接照做。
 * 但它必须**能离线断言**，而 {@link RuntimeApi} 里挂着 `Handler`（android 类），
 * 一碰它就 `NoClassDefFoundError`（本项目的老规矩：被测的逻辑不许挂 android 类）。
 * 所以把这段判据抽到这里，HostTest 直接断言。
 */
public final class ApiError {

    private ApiError() {
    }

    /** 从错误体里取 `{"error":"…"}`；取不到就截一段原文（去掉换行）。 */
    public static String human(String body) {
        if (body == null || body.isEmpty()) return "（无内容）";
        String e = MiniJson.str(body, "error");
        if (e != null && !e.isEmpty()) return e;
        String t = body.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
