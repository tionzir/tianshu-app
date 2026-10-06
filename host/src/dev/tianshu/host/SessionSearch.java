package dev.tianshu.host;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * `GET /sessions/search?q=` 响应的解析与展示（纯逻辑，不碰 Android）。
 *
 * 服务端形状是**实测 + 读源码**得来的，不是猜的：
 *   - `q` 少于 2 个字符 → 400 `{"error":"Query \"q\" must be at least 2 characters"}`（真实 serve 实测）；
 *   - 命中 → 200 `{"results":[…],"meta":{"durationMs":…,"scannedFiles":…}}`（真实 serve 实测空结果，
 *     非空项的字段取自 handler 源码 `chunk-DXY5P57D.js:7725` 的 `searchTranscript`）：
 *     `{sessionId, title, role, snippet}`，且**只扫 role 为 user / assistant 的字符串消息**。
 *
 * 为什么单开一个类：命中列表的渲染必须**可测** —— 而列表页那段 UI 在容器里跑不了。
 */
public final class SessionSearch {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SessionSearch() {
    }

    /** 一条命中。 */
    public static final class Hit {
        public String sessionId;
        public String title;
        public String role;      // user / assistant
        public String snippet;
    }

    /** 一次搜索的结果。 */
    public static final class Result {
        public final List<Hit> hits = new ArrayList<Hit>();
        public int scannedFiles;
    }

    /** 少于 2 个字符会被服务端 400 —— 先在本地拦一道，省一次往返。 */
    public static boolean tooShort(String query) {
        return query == null || query.trim().length() < 2;
    }

    /** 拼搜索路径：`/sessions/search?q=<已编码>`。 */
    public static String pathFor(String query) {
        return "/sessions/search?q=" + encode(query);
    }

    /** 解析 `{"results":[…],"meta":{…}}`。脏输入返回空结果（不抛）。 */
    public static Result parse(String json) {
        Result out = new Result();
        if (json == null) return out;

        int arr = SessionList.arrayStart(json, "results");
        if (arr < 0) return out;

        int i = arr + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else if (c == ']') {
                break;
            } else if (c != '{') {
                i++;                                  // 容错：数组里混了非对象元素就跳过
            } else {
                int end = SessionList.matchBrace(json, i);
                if (end < 0) break;                   // 响应被截断：丢掉残条并收工
                out.hits.add(hit(json.substring(i, end + 1)));
                i = end + 1;
            }
        }

        int meta = json.indexOf("\"meta\"");
        Integer scanned = MiniJson.num(meta < 0 ? json : json.substring(meta), "scannedFiles");
        out.scannedFiles = scanned == null ? 0 : scanned.intValue();
        return out;
    }

    private static Hit hit(String one) {
        Hit h = new Hit();
        h.sessionId = MiniJson.str(one, "sessionId");
        h.title = MiniJson.str(one, "title");
        h.role = MiniJson.str(one, "role");
        h.snippet = MiniJson.str(one, "snippet");
        return h;
    }

    /** 角色标签（界面上那一小行）。 */
    public static String roleLabel(String role) {
        if ("user".equals(role)) return "你";
        if ("assistant".equals(role)) return "天枢";
        return role == null ? "" : role;
    }

    /** 结果摘要行。 */
    public static String summary(Result r) {
        if (r == null) return "";
        if (r.hits.isEmpty()) return "没找到（扫了 " + r.scannedFiles + " 个会话）";
        return "命中 " + r.hits.size() + " 条（扫了 " + r.scannedFiles + " 个会话）";
    }

    /**
     * URL 编码：保留 unreserved 字符，其余按 UTF-8 字节转 %XX。
     *
     * **空格编成 %20 而不是 `+`** —— query 解析对 `+` 是否当空格各家不一，
     * 编成 %20 没有歧义。
     */
    static String encode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(UTF8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }
}
