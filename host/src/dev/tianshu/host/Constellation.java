package dev.tianshu.host;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目蓝图 / 星图 / 编年史的数据源 —— 读 App 私有 rootfs 里的
 * {@code <rootfs>/root/.rivet/constellation.json}（P2，「B1 路数」：数据已落盘，直接读，不改内核）。
 *
 * 依据（天枢 harness @ huiliyi37/Tianshu-harness）：
 *   {@code src/constellation/store.ts:28}   constellationPath(cwd) = &lt;cwd&gt;/.rivet/constellation.json
 *   {@code src/constellation/store.ts:23}   archivePath      = &lt;cwd&gt;/.rivet/constellation.archive.jsonl
 *   {@code src/constellation/schema.ts}     ProjectConstellation { skeleton, milestones[], architectureShifts[] }
 *
 * 为什么读私有 rootfs 而不是共享存储：App 的 proot 以 {@code -w /root} 起（见 RuntimeHost.buildArgv），
 * 而只有 {@code /root/.rivet/sessions} 被 bind 出去（RuntimeBinds.GUEST_RIVET_SESSIONS），
 * 所以 {@code /root/.rivet/constellation.json} 留在 App 私有目录里 —— 它归 App 自己所有、直接可读。
 * 真机实测（run-as）：
 *   {@code files/rootfs/root/.rivet/constellation.json} 存在（2026-10-05）。
 *
 * 纯逻辑、不 import android.* —— HostTest 可直接断言。
 */
public final class Constellation {

    private Constellation() {
    }

    /** 项目骨架（harness `schema.ts: Skeleton`）。 */
    public static final class Skeleton {
        /** 形如 {@code src/agent} 或 {@code src/agent (角色)}。 */
        public final List<String> modules = new ArrayList<String>();
        public final List<String> entryPoints = new ArrayList<String>();
        public final List<String> keyAbstractions = new ArrayList<String>();
        public final List<String> techStack = new ArrayList<String>();

        public boolean isEmpty() {
            return modules.isEmpty() && entryPoints.isEmpty()
                    && keyAbstractions.isEmpty() && techStack.isEmpty();
        }
    }

    /** 一条里程碑（harness `schema.ts: Milestone`）。 */
    public static final class Milestone {
        public String id = "";
        public long timestamp;
        public String sessionId = "";
        public String summary = "";
        public String domain = "";
        public String symbol = "";
        public int numericId;
        public String type = "milestone";
        public String verificationStatus = "unverified";
        public final List<String> filesChanged = new ArrayList<String>();
        public final List<String> tags = new ArrayList<String>();
    }

    /** 解析后的整份蓝图。 */
    public static final class Doc {
        public String name = "";
        public String projectId = "";
        public long createdAt;
        public long lastUpdatedAt;
        public final Skeleton skeleton = new Skeleton();
        public final List<Milestone> milestones = new ArrayList<Milestone>();
        public int shiftCount;
        public String latestShift = "";
    }

    // ---------------------------------------------------------------- 路径

    /** `<rootfs>/root/.rivet/constellation.json`（guest cwd = /root）。 */
    public static File fileFor(File rootfsDir) {
        if (rootfsDir == null) return null;
        return new File(rootfsDir, "root/.rivet/constellation.json");
    }

    /** `<rootfs>/root/.rivet/constellation.archive.jsonl`（溢出滚存的历史，一行一条）。 */
    public static File archiveFor(File rootfsDir) {
        if (rootfsDir == null) return null;
        return new File(rootfsDir, "root/.rivet/constellation.archive.jsonl");
    }

    // ---------------------------------------------------------------- 解析

    /** 解析一份 constellation.json；形状不可用返回 null（不抛）。 */
    public static Doc parse(String json) {
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return null;

        Doc d = new Doc();
        d.name = nz(Json.str(m.get("name")));
        d.projectId = nz(Json.str(m.get("projectId")));
        d.createdAt = lng(m.get("createdAt"));
        d.lastUpdatedAt = lng(m.get("lastUpdatedAt"));

        Map<String, Object> sk = Json.map(m.get("skeleton"));
        if (sk != null) {
            for (Object mo : safeList(sk.get("modules"))) {
                Map<String, Object> mm = Json.map(mo);
                if (mm == null) continue;
                String path = Json.str(mm.get("path"));
                if (path == null || path.length() == 0) continue;
                String role = Json.str(mm.get("role"));
                d.skeleton.modules.add(role == null || role.length() == 0
                        ? path : path + " (" + role + ")");
            }
            addStrings(d.skeleton.entryPoints, sk.get("entryPoints"));
            addStrings(d.skeleton.keyAbstractions, sk.get("keyAbstractions"));
            addStrings(d.skeleton.techStack, sk.get("techStack"));
        }

        for (Object mo : safeList(m.get("milestones"))) {
            Milestone ms = milestone(Json.map(mo));
            if (ms != null) d.milestones.add(ms);
        }

        for (Object so : safeList(m.get("architectureShifts"))) {
            Map<String, Object> sm = Json.map(so);
            if (sm == null) continue;
            d.shiftCount++;
            d.latestShift = nz(Json.str(sm.get("summary")));
        }
        return d;
    }

    /** 解析 archive.jsonl（一行一条里程碑）；坏行跳过。 */
    public static List<Milestone> parseJsonl(String text) {
        List<Milestone> out = new ArrayList<Milestone>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.length() == 0) continue;
            Milestone m = milestone(Json.map(Json.parse(t)));
            if (m != null) out.add(m);
        }
        return out;
    }

    static Milestone milestone(Map<String, Object> r) {
        if (r == null) return null;
        Milestone m = new Milestone();
        m.id = nz(Json.str(r.get("id")));
        if (m.id.length() == 0) return null;
        m.timestamp = lng(r.get("timestamp"));
        m.sessionId = nz(Json.str(r.get("sessionId")));
        m.domain = nz(Json.str(r.get("domain")));
        m.summary = nz(Json.str(r.get("summary")));
        m.type = nz(Json.str(r.get("type")));
        if (m.type.length() == 0) m.type = "milestone";
        m.verificationStatus = nz(Json.str(r.get("verificationStatus")));
        if (m.verificationStatus.length() == 0) m.verificationStatus = "unverified";
        Map<String, Object> mark = Json.map(r.get("agentMark"));
        if (mark != null) {
            m.symbol = nz(Json.str(mark.get("symbol")));
            m.numericId = (int) lng(mark.get("numericId"));
        }
        addStrings(m.filesChanged, r.get("filesChanged"));
        addStrings(m.tags, r.get("tags"));
        return m;
    }

    // ---------------------------------------------------------------- 文本（原生页面用，非 ANSI）

    /** 里程碑类型的 App 文案。 */
    public static String typeLabel(String t) {
        if ("feature".equals(t)) return "功能";
        if ("fix".equals(t)) return "修复";
        if ("refactor".equals(t)) return "重构";
        if ("architecture".equals(t)) return "架构";
        if ("milestone".equals(t)) return "里程碑";
        return (t == null || t.length() == 0) ? "里程碑" : t;
    }

    /** 验证状态的 App 文案（harness 用 ✓/✗ 字形，这里改中文 —— 设备字体覆盖不保证）。 */
    public static String verifyLabel(String v) {
        if ("verified".equals(v)) return "已验证";
        if ("blocked".equals(v)) return "受阻";
        if ("failed".equals(v)) return "失败";
        return "未验证";
    }

    /** 星域·编号·符号，形如 {@code qiming #6230 符号}。 */
    public static String markLabel(Milestone m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        if (m.domain != null && m.domain.length() > 0) sb.append(m.domain);
        if (m.numericId > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('#').append(m.numericId);
        }
        if (m.symbol != null && m.symbol.length() > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m.symbol);
        }
        return sb.toString();
    }

    /** 相对时间（中文）。 */
    public static String relativeTime(long ts, long now) {
        long diff = Math.max(0, now - ts);
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hr = min / 60L;
        if (hr < 24) return hr + " 小时前";
        long day = hr / 24L;
        if (day < 30) return day + " 天前";
        return (day / 30L) + " 个月前";
    }

    /** 一条里程碑的一行文字。 */
    public static String milestoneLine(Milestone m, long now) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel(m.type)).append(" · ").append(verifyLabel(m.verificationStatus));
        if (m.summary.length() > 0) sb.append(" · ").append(m.summary);
        if (!m.filesChanged.isEmpty()) sb.append("（").append(m.filesChanged.size()).append(" 文件）");
        String mark = markLabel(m);
        if (mark.length() > 0) sb.append(" — ").append(mark);
        if (m.timestamp > 0) sb.append(" · ").append(relativeTime(m.timestamp, now));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 小工具

    private static void addStrings(List<String> out, Object v) {
        for (Object o : safeList(v)) {
            String s = Json.str(o);
            if (s != null && s.length() > 0) out.add(s);
        }
    }

    private static List<Object> safeList(Object o) {
        List<Object> l = Json.list(o);
        return l == null ? new ArrayList<Object>() : l;
    }

    private static long lng(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
