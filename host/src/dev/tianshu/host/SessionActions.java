package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话管理动作的纯逻辑：方法 / 路径 / 请求体 / 错误文案。
 *
 * 为什么单独一个类：这些字符串是**契约**，而且有一处反直觉的映射必须钉死 ——
 * 用户说的「删除」在服务端是 `DELETE /sessions/:id/permanent`；
 * 而裸 `DELETE /sessions/:id` 其实是**归档**（软删：从默认列表隐藏，磁盘保留，可恢复）。
 * 把映射放进纯逻辑，HostTest 就能直接断言它，免得将来有人在界面上把「删除」
 * 接到软删上 —— 用户以为删干净了，其实一个字节都没释放。
 *
 * 路由形状来自 harness 路由表原文（v3.27.0，chunk-DXY5P57D.js）：
 *   "DELETE /sessions/:id"            → manager.archiveSession → 200 {archived:true}
 *   "POST   /sessions/:id/unarchive"  → 200 {archived:false}
 *   "PATCH  /sessions/:id"            → body {title} → 200 {id,title}
 *   "DELETE /sessions/:id/permanent"  → 仅对已归档生效 → 200 {deleted:true,freedBytes}
 * 以上四条均于 2026-10-02 在真实 serve 上活体复验。
 */
public final class SessionActions {

    /** 改名（PATCH，body `{title}`；空串按服务端语义 = 清除标题）。 */
    public static final String RENAME = "rename";
    /** 归档（服务端语义是软删：从默认列表隐藏，磁盘保留，可取消）。 */
    public static final String ARCHIVE = "archive";
    /** 取消归档（回到默认列表）。 */
    public static final String UNARCHIVE = "unarchive";
    /** 删除（永久、不可逆；仅对已归档会话生效，否则服务端 409）。 */
    public static final String DELETE = "delete";

    private SessionActions() {
    }

    /** 动作 → HTTP 方法。 */
    public static String methodFor(String action) {
        if (RENAME.equals(action)) return "PATCH";
        if (ARCHIVE.equals(action)) return "DELETE";
        if (UNARCHIVE.equals(action)) return "POST";
        if (DELETE.equals(action)) return "DELETE";
        return "GET";
    }

    /**
     * 动作 → 路径。改名与归档**打同一条路径**（`/sessions/:id`），靠方法区分 ——
     * 这正是最容易搞错的地方：看到 DELETE 别以为就是"删除"。
     */
    public static String pathFor(String action, String id) {
        String base = "/sessions/" + id;
        if (UNARCHIVE.equals(action)) return base + "/unarchive";
        if (DELETE.equals(action)) return base + "/permanent";
        return base;
    }

    /** 改名请求体。null / 空串都发空标题 —— 服务端语义是「清除标题」。 */
    public static String renameBody(String title) {
        return "{\"title\":\"" + escape(title == null ? "" : title) + "\"}";
    }

    /**
     * 「删除」要按什么顺序打。
     *
     * 服务端有条硬约束：`DELETE /sessions/:id/permanent` **只对已归档的会话生效** ——
     * 没归档直接删会得 409 `{"error":"…"}`。而界面上那一个「删除」按钮，点的时候
     * 用户并不知道（也不该关心）这条会话归档了没有。
     *
     * 所以：未归档 → **先归档再永久删除**（两步）；已归档 → 一步到位。
     * 这条顺序放纯逻辑里，HostTest 能直接钉住 —— 否则这个 409 只会在真机上被用户撞见。
     */
    public static List<String> deletePlan(boolean archived) {
        List<String> out = new ArrayList<String>(2);
        if (!archived) out.add(ARCHIVE);
        out.add(DELETE);
        return out;
    }

    /**
     * 这一步失败了，值不值得**再试一次**？
     *
     * 只有「删除」值得：归档是**异步中止**（Wave 0 实测 status 直接变 aborted），
     * 紧跟其后的 `DELETE /permanent` 可能在会话还没停干净时瞬时 409 —— 隔一会儿再来一次就好。
     * 「归档」本身不值得重试（重试归档没有意义，失败就是失败）。
     *
     * 纯逻辑，HostTest 直接钉住；真正的重试节奏在 {@link SessionActionMenu} 里。
     */
    public static boolean retryableStep(String action) {
        return DELETE.equals(action);
    }

    /** 把服务端的错误体翻成一句用户能照做的话（界面别显示裸 HTTP 码）。 */
    public static String humanError(int code, String body) {
        if (code == 409) {
            String e = err(body);
            if (e.indexOf("already running") >= 0) return "会话正在运行 —— 等它跑完再操作";
            return "会话未归档或仍在运行 —— 先让它停下并归档";
        }
        if (code == 404) return "会话不存在（可能已被删除）";
        if (code == 400) return "请求被拒绝：" + err(body);
        if (code == 401 || code == 403) return "没有权限（token 不对？）";
        return "操作失败（HTTP " + code + "）：" + err(body);
    }

    private static String err(String body) {
        String e = MiniJson.str(body, "error");
        return e == null ? "" : e;
    }

    /** JSON 字符串转义 —— 标题是用户输入，引号/反斜杠/换行必须处理。 */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.toString();
    }
}
