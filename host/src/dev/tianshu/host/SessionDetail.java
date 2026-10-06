package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「会话详情」页的纯逻辑 —— 解析官方**会话级**路由的响应 + 拼写入体。**不 import android.\***，
 * HostTest 可以直接拿真夹具断言。
 *
 * 为什么单开一层：这些接口 App 原先**一个都没接**（2026-10-06② 顺着官方 3.28.0 的 302 条路由
 * 逐条对账查出来的）。解析与文案收在这里，Activity 只管画。
 *
 * 依据（harness 3.28.0，每条都在**运行中的 serve** 上实测过 200 或对应语义）：
 * <pre>
 *   GET  /sessions/:id/interventions → {"approvals":[{requestId,toolName,input,pathGrant?}],"lastSeq":N}
 *   POST /sessions/:id/interventions/:requestId/answer  body {"decision":…} → {"ok":true}
 *   GET  /sessions/:id/files         → {"files":["&lt;相对路径&gt;", …]}
 *   GET  /sessions/:id/file-content?path= → {"path","content","language"}
 *   GET  /sessions/:id/git/diff?path=     → {"diff":"…"}
 *   GET  /sessions/:id/jobs          → {"jobs":[…]}
 *   GET  /sessions/:id/hooks         → {"hooks":[…]}
 *   GET  /sessions/:id/review-gate   → {"id","mode"}
 * </pre>
 *
 * ⚠ 刻意**不**收 `/git/graph`：README 记过它会让整个 Runtime 进程退出（真复现过）。
 */
public final class SessionDetail {

    private SessionDetail() {
    }

    /** 一条待批准 / 待应答。 */
    public static final class Approval {
        public final String requestId;
        public final String toolName;
        public final String summary;      // input 的紧凑摘要（harness 侧已 redact）
        public final String grant;        // "目录（读/写）"；无授权诉求时为空

        Approval(String requestId, String toolName, String summary, String grant) {
            this.requestId = requestId == null ? "" : requestId;
            this.toolName = toolName == null ? "" : toolName;
            this.summary = summary == null ? "" : summary;
            this.grant = grant == null ? "" : grant;
        }

        /** 给人看的一行标题。 */
        public String title() {
            String label = toolLabel(toolName);
            return label.isEmpty() ? "待批准" : label;
        }

        /** 给人看的副标题：授权诉求 > input 摘要 > 占位。 */
        public String subtitle() {
            if (!grant.isEmpty()) return "目录 " + grant;
            if (!summary.isEmpty()) return summary;
            return "（没有更多细节）";
        }
    }

    /** GET /sessions/:id/interventions → approvals[]。脏输入不抛，返回空表。 */
    public static List<Approval> parseInterventions(String json) {
        List<Approval> out = new ArrayList<Approval>();
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return out;
        List<Object> arr = Json.list(root.get("approvals"));
        if (arr == null) return out;
        for (Object o : arr) {
            Map<String, Object> m = Json.map(o);
            if (m == null) continue;
            String rid = Json.str(m.get("requestId"));
            if (rid == null || rid.isEmpty()) continue;       // 没 id 就没法应答，跳过
            out.add(new Approval(rid, Json.str(m.get("toolName")),
                    summarize(m.get("input")), grantLine(Json.map(m.get("pathGrant")))));
        }
        return out;
    }

    /**
     * 应答体。字段名对齐 harness：`body.decision`，`"approve"` 判为批准
     *（`answerIntervention` 里 `decision === "approve" || decision === "approved"`）。
     */
    public static String answerBody(boolean approve) {
        return approve ? "{\"decision\":\"approve\"}" : "{\"decision\":\"deny\"}";
    }

    /** 应答路径（requestId 是服务端给的 opaque 串，只做最小转义）。 */
    public static String answerPath(String sessionId, String requestId) {
        return "/sessions/" + sessionId + "/interventions/" + urlPart(requestId) + "/answer";
    }

    /** GET /sessions/:id/files → 相对路径列表。 */
    public static List<String> parseFiles(String json) {
        List<String> out = new ArrayList<String>();
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return out;
        List<Object> arr = Json.list(root.get("files"));
        if (arr == null) return out;
        for (Object o : arr) {
            String s = Json.str(o);
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * 过滤掉天枢自己的运行目录（`.rivet/` `.git/`）—— `files` 返回的是**整个工作区**，
     * 不过滤的话十几条内部件里找那一两个真改动，等于没做。
     */
    public static List<String> userFiles(List<String> all) {
        List<String> out = new ArrayList<String>();
        if (all == null) return out;
        for (String p : all) if (!isInternal(p)) out.add(p);
        return out;
    }

    /** 是不是天枢自己的运行目录。 */
    public static boolean isInternal(String path) {
        return path != null && (path.startsWith(".rivet/") || path.startsWith(".git/"));
    }

    /** 工具名 → 给人看的一句话；认不出的**原样显示**，不猜。 */
    public static String toolLabel(String toolName) {
        if (toolName == null || toolName.isEmpty()) return "";
        if ("request_path_access".equals(toolName)) return "请求访问工作区外的目录";
        if ("bash".equals(toolName)) return "要跑一条命令";
        if ("write_file".equals(toolName)) return "要写一个文件";
        if ("edit_file".equals(toolName)) return "要改一个文件";
        if ("apply_patch".equals(toolName)) return "要打一个补丁";
        if ("delete_file".equals(toolName)) return "要删一个文件";
        return toolName;
    }

    /** 文件内容响应里的正文（拿不到返回空串）。 */
    public static String contentOf(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return "";
        String c = Json.str(root.get("content"));
        return c == null ? "" : c;
    }

    /** 文件内容响应里的语言标记（拿不到返回空串）。 */
    public static String languageOf(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return "";
        String c = Json.str(root.get("language"));
        return c == null ? "" : c;
    }

    /** git diff 响应里的 diff 正文（拿不到返回空串）。 */
    public static String diffOf(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return "";
        String c = Json.str(root.get("diff"));
        return c == null ? "" : c;
    }

    /** file-content 的查询串（path 必须转义 —— 值里常有 `/` 与中文）。 */
    public static String fileContentPath(String rel) {
        return "/file-content?path=" + urlPart(rel);
    }

    /** git diff 的查询串。 */
    public static String gitDiffPath(String rel) {
        return "/git/diff?path=" + urlPart(rel);
    }

    // ---------------------------------------------------------------- 内部

    /** 最小百分号转义：只放行 unreserved + `/`，其余按 UTF-8 逐字节转义。 */
    static String urlPart(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] bytes;
        try {
            bytes = s.getBytes("UTF-8");
        } catch (Exception e) {
            bytes = s.getBytes();
        }
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' || c == '/';
            if (ok) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** input 的紧凑摘要：摊成 `k=v` 一行（过长截断）。认不出返回空串。 */
    static String summarize(Object input) {
        Map<String, Object> m = Json.map(input);
        if (m == null || m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (n++ > 0) sb.append("  ");
            sb.append(e.getKey()).append('=').append(shortOf(e.getValue()));
            if (sb.length() > 160) {
                sb.append(" …");
                break;
            }
        }
        return sb.toString();
    }

    private static String shortOf(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String grantLine(Map<String, Object> g) {
        if (g == null) return "";
        String dir = Json.str(g.get("dir"));
        if (dir == null || dir.isEmpty()) return "";
        String mode = Json.str(g.get("mode"));
        return dir + "（" + ("write".equals(mode) ? "写" : "读") + "）";
    }
}
