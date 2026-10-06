package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「账号」页的纯逻辑 —— 官方**账号 / OAuth** 路由的解析与写入体。不 import android.\*，
 * HostTest 直接拿真夹具断言。
 *
 * 为什么单开一层：App 此前只会做**第一步** —— `/login` 打一发 `POST /account/device`，
 * 把原始 JSON 往对话里一贴就完了。可设备码登录的契约是**三步**
 *（2026-10-06④ 在运行中的 3.28.0 serve 上逐条实测）：
 *
 * <pre>
 *   ① POST /account/device   → {deviceCode, userCode, verifyUrl, expiresIn, pollInterval}
 *   ② 把 userCode 显示给人、verifyUrl 给人去浏览器打开
 *   ③ **按 pollInterval 轮询** POST /account/poll {deviceCode}
 *        → {"status":"pending"} 继续；{"status":"approved"} 成功；{"status":"expired"} 作废
 * </pre>
 *
 * 缺了第 ③ 步，码给了也永远登不上 —— App 里 `account/poll` 出现 **0** 次，这就是本轮的 RED 证据。
 *
 * 服务商 OAuth 同理（`POST /config/providers/:name/oauth/login`）：它**立即返回 started**，
 * 登录态要轮 `GET /config/providers` 的 `oauthAuthenticated` 才知道 —— 不是等它返回。
 */
public final class Account {

    private Account() {
    }

    /** 设备码登录凭据（POST /account/device 的响应）。 */
    public static final class Device {
        public final String deviceCode;
        public final String userCode;
        public final String verifyUrl;
        public final int expiresIn;
        public final int pollInterval;

        Device(String deviceCode, String userCode, String verifyUrl, int expiresIn, int pollInterval) {
            this.deviceCode = deviceCode == null ? "" : deviceCode;
            this.userCode = userCode == null ? "" : userCode;
            this.verifyUrl = verifyUrl == null ? "" : verifyUrl;
            this.expiresIn = expiresIn <= 0 ? 300 : expiresIn;      // 服务端没给就用契约默认
            this.pollInterval = pollInterval <= 0 ? 5 : pollInterval;
        }

        /** 码和链接都在才算拿到。 */
        public boolean ok() {
            return deviceCode.length() > 0 && userCode.length() > 0;
        }
    }

    /** 解析设备码响应；认不出（脏输入 / 错误体）返回 null。 */
    public static Device parseDevice(String json) {
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return null;
        String code = Json.str(m.get("deviceCode"));
        if (code == null || code.isEmpty()) return null;
        Integer exp = num(m.get("expiresIn"));
        Integer iv = num(m.get("pollInterval"));
        return new Device(code, Json.str(m.get("userCode")), Json.str(m.get("verifyUrl")),
                exp == null ? 300 : exp.intValue(), iv == null ? 5 : iv.intValue());
    }

    /** 轮询体的状态字：pending / approved / expired / 其它（原样返回，不猜）。 */
    public static String pollStatus(String json) {
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return "";
        String s = Json.str(m.get("status"));
        return s == null ? "" : s;
    }

    /** 登录成功判据 —— 与 harness 一致：`body.status === "approved"`。 */
    public static boolean isApproved(String json) {
        return "approved".equals(pollStatus(json));
    }

    /** 还该不该继续轮询：只有 pending 继续；approved / expired / 认不出 都停。 */
    public static boolean keepPolling(String json) {
        return "pending".equals(pollStatus(json));
    }

    public static String pollBody(String deviceCode) {
        return "{\"deviceCode\":\"" + esc(deviceCode) + "\"}";
    }

    /** 账号状态（GET /account/status）。 */
    public static final class Status {
        public final boolean loggedIn;
        public final String userId, email, stellarId, primaryDomain, title;

        Status(boolean loggedIn, String userId, String email, String stellarId,
               String primaryDomain, String title) {
            this.loggedIn = loggedIn;
            this.userId = nz(userId);
            this.email = nz(email);
            this.stellarId = nz(stellarId);
            this.primaryDomain = nz(primaryDomain);
            this.title = nz(title);
        }

        /** 一行摘要：登录后给星籍，未登录给一句引导。 */
        public String line() {
            if (!loggedIn) return "未登录";
            StringBuilder sb = new StringBuilder();
            if (stellarId.length() > 0) sb.append("星籍 ").append(stellarId);
            if (primaryDomain.length() > 0) sb.append(sb.length() > 0 ? " · " : "").append(primaryDomain);
            if (title.length() > 0) sb.append(sb.length() > 0 ? " · " : "").append(title);
            if (sb.length() == 0) sb.append("已登录");
            return sb.toString();
        }
    }

    public static Status parseStatus(String json) {
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return new Status(false, null, null, null, null, null);
        return new Status(Boolean.TRUE.equals(m.get("loggedIn")),
                Json.str(m.get("userId")), Json.str(m.get("email")), Json.str(m.get("stellarId")),
                Json.str(m.get("primaryDomain")), Json.str(m.get("title")));
    }

    /** 一个 OAuth 型服务商（`GET /config/providers` 里 `authType == "oauth"` 的那些）。 */
    public static final class Oauth {
        public final String name, label;
        public final boolean authenticated;

        Oauth(String name, String label, boolean authenticated) {
            this.name = nz(name);
            this.label = (label == null || label.isEmpty()) ? nz(name) : label;
            this.authenticated = authenticated;
        }

        /** 按钮上该写什么。 */
        public String action() {
            return authenticated ? "登出" : "登录";
        }

        /** 状态行。 */
        public String state() {
            return authenticated ? "已授权" : "未授权";
        }
    }

    /** 从 `/config/providers` 里挑出 OAuth 型服务商 —— 非 OAuth 的不该出现在这一页。 */
    public static List<Oauth> oauthProviders(String providersJson) {
        List<Oauth> out = new ArrayList<Oauth>();
        Map<String, Object> root = Json.map(Json.parse(providersJson));
        if (root == null) return out;
        List<Object> arr = Json.list(root.get("providers"));
        if (arr == null) return out;
        for (Object o : arr) {
            Map<String, Object> p = Json.map(o);
            if (p == null) continue;
            if (!"oauth".equals(Json.str(p.get("authType")))) continue;
            out.add(new Oauth(Json.str(p.get("name")), Json.str(p.get("label")),
                    Boolean.TRUE.equals(p.get("oauthAuthenticated"))));
        }
        return out;
    }

    /** `/config/providers/:name/oauth/login|logout`。name 只做最小转义（它是配置里的键名）。 */
    public static String oauthPath(String name, boolean login) {
        return "/config/providers/" + esc(name) + (login ? "/oauth/login" : "/oauth/logout");
    }

    // ---------------------------------------------------------------- 内部

    private static Integer num(Object o) {
        if (o instanceof Number) return Integer.valueOf(((Number) o).intValue());
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
