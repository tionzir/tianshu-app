package dev.tianshu.host;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地读盘数据源 —— 把 harness 里那些"读进程内内存"的 `/` 命令，
 * 改用**会话目录里已落盘的文件**在 App 侧重建（不必改内核）。
 *
 * 依据（天枢 harness @ huiliyi37/Tianshu-harness）：
 *   config/paths.ts        projectSlug(cwd) = &lt;末段&gt;-&lt;sha256(cwd)[:6]&gt;
 *   agent/session-persist  &lt;id&gt;.jsonl / .meta.json / .frozen.json / .handoff.md
 *   tools/todo.ts          &lt;id&gt;.todos.json
 *   session-persist:734    &lt;id&gt;.memory.json
 *   会话子目录              &lt;id&gt;/sensorium.jsonl、frames.jsonl、cache-log.jsonl
 *
 * 目录形如 &lt;sessionsHostDir&gt;/root-94a6b4/（/root 的 sha256 前 6 位正是 94a6b4）。
 *
 * 纯逻辑、不 import android.* —— HostTest 可直接断言。
 */
public final class LocalData {

    private LocalData() {
    }

    /** harness `config/paths.ts:projectSlug`：&lt;cwd 末段&gt;-&lt;sha256(canonicalCwd) 前 6 位&gt;。 */
    public static String projectSlug(String cwd) {
        if (cwd == null) return null;
        String t = cwd.trim();
        while (t.length() > 1 && (t.endsWith("/") || t.endsWith("\\"))) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.length() == 0) return null;
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        String name = i >= 0 ? t.substring(i + 1) : t;
        if (name.length() == 0) name = "unknown";
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.length() == 0) safe = "unknown";
        return safe + "-" + sha256Hex(t).substring(0, 6);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    /** 会话目录（宿主机侧）：&lt;sessionsHostDir&gt;/&lt;projectSlug(cwd)&gt;。 */
    public static File projectDir(File sessionsHostDir, String cwd) {
        if (sessionsHostDir == null) return null;
        String slug = projectSlug(cwd);
        if (slug == null) return null;
        return new File(sessionsHostDir, slug);
    }

    /** 读整份文本；不存在或失败返回 null。 */
    static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** JSONL 的最后 n 行（按行切，保留原文，跳过空行）。 */
    static List<String> tailLines(String text, int n) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.length() == 0) return out;
        String[] all = text.split("\n");
        for (int i = all.length - 1; i >= 0 && out.size() < n; i--) {
            String line = all[i].trim();
            if (line.length() > 0) out.add(0, line);
        }
        return out;
    }

    // ---------------------------------------------------------------- 各命令

    /** `/todo` —— &lt;id&gt;.todos.json。 */
    public static String todos(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String raw = readText(new File(dir, sessionId + ".todos.json"));
        if (raw == null) return "这条会话还没有待办（天枢跑起 todo 工具后才会生成 <id>.todos.json）。";
        String body = raw.trim();
        int n = countOf(body, "\"id\"");
        return "待办清单（来自 " + sessionId + ".todos.json，共 " + n + " 条）：\n" + body;
    }

    /** `/sensorium` —— &lt;id&gt;/sensorium.jsonl 末行。 */
    public static String sensorium(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        List<String> tail = tailLines(readText(new File(new File(dir, sessionId), "sensorium.jsonl")), 1);
        if (tail.isEmpty()) return "这条会话还没有六维遥测（sensorium.jsonl 未生成）。";
        String j = tail.get(0);
        return "六维遥测（末帧）：\n"
                + "  模式 mode : " + nz(MiniJson.str(j, "mode")) + "\n"
                + "  放松 relax: " + nz(MiniJson.str(j, "relax")) + "\n"
                + "  层级 lvl  : " + nz(MiniJson.str(j, "lvl")) + "\n"
                + "  指纹 fp   : " + nz(MiniJson.str(j, "fp")) + "\n"
                + "  回合 turn : " + nz(MiniJson.str(j, "turn")) + "\n"
                + "  原始：" + j;
    }

    /** `/context` —— meta.json 的 tokenUsage + frames.jsonl 末帧。 */
    public static String context(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String meta = readText(new File(dir, sessionId + ".meta.json"));
        if (meta == null) return "这条会话还没有元数据（" + sessionId + ".meta.json 不存在）。";
        int turns = nzNum(MiniJson.num(meta, "turnCount"));
        int tools = nzNum(MiniJson.num(meta, "toolCallCount"));
        List<String> tail = tailLines(readText(new File(new File(dir, sessionId), "frames.jsonl")), 1);
        String phase = tail.isEmpty() ? "（无帧）" : nz(MiniJson.str(tail.get(0), "phaseClass"));
        return "上下文账本：\n"
                + "  回合数 turnCount : " + turns + "\n"
                + "  工具调用数       : " + tools + "\n"
                + "  最近阶段 phase   : " + phase + "\n"
                + "  tokenUsage       : " + tokenUsageText(meta);
    }

    /** `/debug` —— meta.json 关键字段摘要。 */
    public static String debug(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String meta = readText(new File(dir, sessionId + ".meta.json"));
        if (meta == null) return "这条会话还没有元数据。";
        return "调试指纹：\n"
                + "  sessionId  : " + nz(MiniJson.str(meta, "sessionId")) + "\n"
                + "  模型 model : " + nz(MiniJson.str(meta, "model")) + "\n"
                + "  星域 domain: " + nz(MiniJson.str(meta, "domain")) + "\n"
                + "  cwd        : " + nz(MiniJson.str(meta, "cwd")) + "\n"
                + "  状态 status: " + nz(MiniJson.str(meta, "status")) + "\n"
                + "  plan 模式  : " + nz(MiniJson.str(meta, "planModeState")) + "\n"
                + "  ask 模式   : " + nz(MiniJson.str(meta, "askModeState")) + "\n"
                + "  正常退出   : " + nz(MiniJson.str(meta, "cleanExit"));
    }

    /** `/memory` —— &lt;id&gt;.memory.json。 */
    public static String memory(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String raw = readText(new File(dir, sessionId + ".memory.json"));
        if (raw == null) return "这条会话还没有记忆（" + sessionId + ".memory.json 不存在）。";
        return "会话记忆（来自 " + sessionId + ".memory.json）：\n" + raw.trim();
    }

    /** `/verify` —— meta.json 的 lastStopReason + 校验字段。 */
    public static String verify(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String meta = readText(new File(dir, sessionId + ".meta.json"));
        if (meta == null) return "这条会话还没有元数据，无法判断验证状态。";
        return "改动验证状态：\n"
                + "  最近收尾原因 : " + nz(MiniJson.str(meta, "lastStopReason")) + "\n"
                + "  回合数       : " + nzNum(MiniJson.num(meta, "turnCount")) + "\n"
                + "  工具调用数   : " + nzNum(MiniJson.num(meta, "toolCallCount")) + "\n"
                + "  plan 文件    : " + nz(MiniJson.str(meta, "activePlanFilePath"));
    }

    /**
     * `/branch` —— 会话分支树（当前会话的父/子关系）。
     *
     * harness 侧 `/branch`（`src/tui/slash-commands.ts:2436`）读的是
     * `<id>.meta.json` 的 `parentSessionId` / `branchName`，并用 `listBranches` 扫会话目录找
     * `parentSessionId == 当前 id` 的子会话 —— **两者都是盘上文件**，App 直接扫同一目录重建。
     * 只做「展示」这一半；`/branch back`（切换父会话）是写动作，走会话页。
     */
    public static String branch(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;

        String cur = readText(new File(dir, sessionId + ".meta.json"));
        String parent = cur == null ? null : MiniJson.str(cur, "parentSessionId");
        String branchName = cur == null ? null : MiniJson.str(cur, "branchName");

        StringBuilder sb = new StringBuilder();
        sb.append("分支树\n");
        if (parent == null || parent.length() == 0) {
            sb.append("父会话: 无（根会话）\n");
        } else {
            String label = parent;
            String pm = readText(new File(dir, parent + ".meta.json"));
            if (pm != null) {
                String t = MiniJson.str(pm, "title");
                String bn = MiniJson.str(pm, "branchName");
                if (t != null && t.length() > 0) label += " \"" + t + "\"";
                if (bn != null && bn.length() > 0) label += " (" + bn + ")";
            }
            sb.append("父会话: ").append(label).append('\n');
        }
        if (branchName != null && branchName.length() > 0) {
            sb.append("当前分支名: ").append(branchName).append('\n');
        }

        List<String> kids = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".meta.json")) continue;
                String raw = readText(f);
                if (raw == null) continue;
                String p = MiniJson.str(raw, "parentSessionId");
                if (p == null || !p.equals(sessionId)) continue;
                String kid = n.substring(0, n.length() - ".meta.json".length());
                String kn = MiniJson.str(raw, "branchName");
                Long ts = MiniJson.longNum(raw, "createdAt");
                StringBuilder k = new StringBuilder();
                k.append("  - ").append(kid)
                        .append(" \"").append(kn == null || kn.length() == 0 ? "(未命名)" : kn).append("\"");
                if (ts != null && ts.longValue() > 0) {
                    k.append(" · ").append(Constellation.relativeTime(ts.longValue(), System.currentTimeMillis()));
                }
                kids.add(k.toString());
            }
        }
        if (kids.isEmpty()) {
            sb.append("子分支: 无\n");
        } else {
            sb.append("子分支 (").append(kids.size()).append("):\n");
            int cap = Math.min(kids.size(), 20);
            for (int i = 0; i < cap; i++) sb.append(kids.get(i)).append('\n');
            if (kids.size() > cap) sb.append("  … 另有 ").append(kids.size() - cap).append(" 条\n");
        }
        sb.append("提示: /fork [名称] 创建新分支，/branch back 回到父会话。");
        return sb.toString();
    }

    /** `/plan-view`（无参） —— meta.json 的 plan 状态 + 计划文件路径。 */
    public static String planView(File sessionsHostDir, String cwd, String sessionId) {
        File dir = projectDir(sessionsHostDir, cwd);
        if (dir == null || sessionId == null) return null;
        String meta = readText(new File(dir, sessionId + ".meta.json"));
        if (meta == null) return "这条会话还没有元数据。";
        return "计划状态：\n"
                + "  计划模式 : " + nz(MiniJson.str(meta, "planModeState")) + "\n"
                + "  计划文件 : " + nz(MiniJson.str(meta, "activePlanFilePath"));
    }

    // ---------------------------------------------------------------- 小工具

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    private static int nzNum(Integer n) {
        return n == null ? 0 : n.intValue();
    }

    private static int countOf(String haystack, String needle) {
        if (haystack == null || needle == null || needle.length() == 0) return 0;
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static String tokenUsageText(String meta) {
        Integer p = MiniJson.num(meta, "prompt");
        Integer c = MiniJson.num(meta, "completion");
        Integer t = MiniJson.num(meta, "total");
        if (p == null && c == null && t == null) return "—";
        return "prompt=" + nzNum(p) + " completion=" + nzNum(c) + " total=" + nzNum(t);
    }
}
