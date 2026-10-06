package dev.tianshu.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把任意 `/config/*` 的返回**摊平成「字段 = 值」的行**（纯逻辑）。
 *
 * 为什么要有它：运行时的配置面有 **82 条路由、27 个族**。为每一族手写一个界面，
 * 既抄得累、又必然分叉。而它们的返回形状是一致的（顶层标量 + 少量嵌套对象），
 * 所以**一份通用摊平器就能把整个配置面渲染出来**。
 *
 * 一条与真机 API 逐条对过的约定（它决定「能不能改」）：
 *   **顶层标量字段可以直接原样写回去** ——
 *     GET /config/zen          → {"enabled":false}      PUT 同一路径 {"enabled":true}
 *     GET /config/runtime-lean → {"lean":false}         PUT {"lean":true}
 *     GET /config/delivery     → {"autoCommit":false}   PUT {"autoCommit":true}
 *     GET /config/approval     → {"approval":"…"}        PUT {"approval":"…"}
 *     GET /config/tool-preset  → {"preset":"…"}          PUT {"preset":"…"}
 * 所以**顶层布尔可以安全地做成开关**；嵌套字段只读展示，不冒险猜写入 schema
 * （盲写配置是会把用户的运行时改坏的 —— 宁可少一个开关）。
 */
public final class ConfigView {

    private ConfigView() {
    }

    /** 一行。 */
    public static final class Row {
        /** 顶层键名 —— 可写回的就是它。 */
        public final String key;
        /** 展示名：嵌套时是 `"a.b"`。 */
        public final String label;
        /** 展示值。 */
        public final String value;
        /**
         * **原始值**（未经本地化的文本）。写回去用它 —— `value` 可能已经翻成中文名
         * （例如 `defaultDomain` 的 `qiming` 显示成「启明」），拿显示值去写会把配置写坏。
         */
        public final String raw;
        /** 顶层布尔 → 可以做开关。 */
        public final boolean isBool;
        public final boolean boolValue;
        /**
         * 这一行能不能**就地改**。只有**顶层标量**算数（布尔 / 字符串 / 数字）。
         * 嵌套字段一律只读 —— 它们的写入 schema 没人验过，盲写会把用户的运行时改坏。
         */
        public final boolean editable;
        /** 值类型："bool" / "string" / "number" / "other"（写回去时决定加不加引号）。 */
        public final String kind;

        Row(String key, String label, String value, String raw, boolean isBool, boolean boolValue,
            boolean editable, String kind) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.raw = raw == null ? value : raw;
            this.isBool = isBool;
            this.boolValue = boolValue;
            this.editable = editable;
            this.kind = kind;
        }
    }

    /** 布尔统一显示成这个（界面上的开关按钮文案也用它，别两处各写一套）。 */
    public static String boolText(boolean b) {
        return b ? "开" : "关";
    }

    public static List<Row> flatten(String json) {
        return flatten(json, 2);
    }

    /**
     * @param maxDepth 往下摊几层（0 = 只看顶层）。嵌套对象再深就只显示「对象」。
     */
    public static List<Row> flatten(String json, int maxDepth) {
        return flattenMap(Json.map(Json.parse(json)), maxDepth);
    }

    /**
     * 直接摊平一个**已解析**的对象 —— 给手里已经有 Map 的调用方用
     * （{@link ApiResult} 要摊搜索结果里的每个对象，那些不是独立 JSON 字符串）。
     * 省一次"先转回字符串再解析"的来回。
     */
    public static List<Row> flattenMap(Map<String, Object> root, int maxDepth) {
        List<Row> out = new ArrayList<Row>();
        if (root == null) return out;
        walk(out, root, "", "", maxDepth, true, selfExplainedNames(root));
        return out;
    }

    /**
     * 响应里自带的名字表：`domains:[{id,name}]` 这种。
     *
     * 为什么用它而不是在代码里写死一份 id→名字：**响应自己就能解释自己** ——
     * `defaultDomain:"qiming"` 配上一张 `{qiming:启明}`，就不必再维护第二份迟早过期的对照表。
     */
    private static Map<String, String> selfExplainedNames(Map<String, Object> root) {
        Map<String, String> m = new HashMap<String, String>();
        List<Object> ds = Json.list(root.get("domains"));
        if (ds == null) return m;
        for (Object o : ds) {
            Map<String, Object> e = Json.map(o);
            if (e == null) continue;
            String id = Json.str(e.get("id"));
            String nm = Json.str(e.get("name"));
            if (id != null && nm != null && !id.isEmpty() && !nm.isEmpty()) m.put(id, nm);
        }
        return m;
    }

    /**
     * 配置键 → 中文说明表。**只覆盖一眼读不出意思的那些**，认不得的一律原样回显。
     *
     * 这张表只在显示层用，不参与写回 —— 写回永远用 {@link Row#key}（原始英文键）。
     */
    private static final Map<String, String> LABELS = new HashMap<String, String>();

    static {
        LABELS.put("enabled", "启用");
        LABELS.put("approval", "审批模式");
        LABELS.put("unsandboxed", "跳过权限确认（不需要沙箱）");
        LABELS.put("defaultDomain", "默认星域");
        LABELS.put("domainKeywordRouting", "按关键词自动选星域");
        LABELS.put("defaultModel", "默认模型");
        LABELS.put("model", "模型");
        LABELS.put("provider", "服务商");
        LABELS.put("providers", "服务商与模型");
        LABELS.put("lean", "精简模式（少废话）");
        LABELS.put("autoCommit", "自动提交改动");
        LABELS.put("preset", "预设");
        LABELS.put("style", "界面风格");
        LABELS.put("accent", "主色");
        LABELS.put("cwd", "工作目录");
        LABELS.put("path", "路径");
        LABELS.put("trusted", "已授信");
        LABELS.put("dismissed", "已忽略过提示");
        LABELS.put("reasons", "原因");
        LABELS.put("servers", "服务端列表");
        LABELS.put("plugins", "插件");
        LABELS.put("grants", "已记住的授权");
        LABELS.put("scope", "统计范围");
        LABELS.put("hitRate", "缓存命中率");
        LABELS.put("tokens", "token 数");
        LABELS.put("available", "可用");
        LABELS.put("command", "命令");
        LABELS.put("version", "版本");
    }

    /**
     * 配置键 → 中文名。**认不得的原样回显**（宁可见英文键，也不猜错意思）。
     *
     * 用户反馈「高级：原始配置展开后感觉很劣质、很难懂」—— 一片 `domainKeywordRouting`
     * 这样的键名，普通用户读不出它管什么。翻译只影响**显示**，写回仍走 {@link Row#key}。
     */
    public static String humanLabel(String key) {
        if (key == null) return "";
        String hit = LABELS.get(key);
        return hit != null ? hit : key;
    }

    /**
     * 少数**没有自解释表**的枚举值 → 显示名。**认不得的原样回显**（不猜、更不显示成空）。
     *
     * 现在只有一套：approval（与聊天页审批 chip 的说法保持一致，
     * 见 {@code StatusBar.approvalLabel}）。
     */
    public static String localizeValue(String key, String raw) {
        if (raw == null) return "";
        if ("approval".equals(key)) {
            // 委托给 StatusBar.approvalLabel —— 这条映射**原先在这儿是第二份拷贝**：
            // 同样只登记三个值、同样认一个 harness 根本没有的 "auto"，
            // 于是 /config/approval 返回 suggest / auto-safe / auto-accept 时，
            // 设置页与对话页状态行会各显示各的（一处英文一处中文，或两处都英文）。
            // 值到中文只该有**一处**定义。
            return StatusBar.approvalLabel(raw);
        }
        return raw;
    }

    private static void walk(List<Row> out, Map<String, Object> m, String keyPrefix,
                             String labelPrefix, int depth, boolean topLevel,
                             Map<String, String> names) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            String label = labelPrefix.isEmpty() ? humanLabel(k) : labelPrefix + "." + humanLabel(k);
            String key = topLevel ? k : keyPrefix;      // 只有顶层键可写回

            if (v instanceof Boolean) {
                boolean b = ((Boolean) v).booleanValue();
                out.add(new Row(key, label, boolText(b), boolText(b), topLevel, b, topLevel, "bool"));
            } else if (v instanceof String) {
                String s = (String) v;
                // 值先查响应自带的名字表（qiming→启明），再查少量已知枚举（approval 那套）；
                // 都认不得就**原样显示**。
                String shown = names.containsKey(s) ? names.get(s) : localizeValue(key, s);
                out.add(new Row(key, label, shown, s, false, false, topLevel, "string"));
            } else if (v instanceof Number) {
                out.add(new Row(key, label, display(v), display(v), false, false, topLevel, "number"));
            } else if (v == null) {
                out.add(new Row(key, label, "（未设）", "（未设）", false, false, false, "other"));
            } else if (v instanceof List) {
                List<Object> l = Json.list(v);
                String shown = "[" + (l == null ? 0 : l.size()) + " 项]";
                out.add(new Row(key, label, shown, shown, false, false, false, "other"));
            } else if (v instanceof Map && depth > 0) {
                Map<String, Object> sub = Json.map(v);
                if (sub == null || sub.isEmpty()) {
                    out.add(new Row(key, label, "（空）", "（空）", false, false, false, "other"));
                } else {
                    walk(out, sub, key, label, depth - 1, false, names);
                }
            } else {
                out.add(new Row(key, label, "…", "…", false, false, false, "other"));
            }
        }
    }

    private static String display(Object v) {
        if (v == null) return "（未设）";
        String s = String.valueOf(v);
        if (s.isEmpty()) return "（空）";
        return s;
    }

    /** 顶层布尔键名清单 —— 界面据此决定给哪几行做开关。 */
    public static List<String> toggleKeys(List<Row> rows) {
        List<String> out = new ArrayList<String>();
        for (Row r : rows) {
            if (r.isBool && r.key != null && !r.key.isEmpty()) out.add(r.key);
        }
        return out;
    }

    /**
     * 把用户在输入框里敲的原文 → **写回去的 JSON 字面量**。
     *
     * 为什么是这个样子：写入是 `{"<原键名>": <字面量>}` 打回同一族路径（与布尔同一条规则）。
     * 数字**不加引号** —— 加了会把 `5` 变成 `"5"`，schema 校验要么拒、要么静默把类型改掉；
     * 字符串加引号并转义（用户可能输引号、反斜杠、换行）。
     */
    public static String jsonLiteral(Row row, String text) {
        String t = text == null ? "" : text;
        if ("number".equals(row.kind)) return t.trim();
        if ("bool".equals(row.kind)) return "true".equals(t.trim()) ? "true" : "false";
        return "\"" + SessionActions.escape(t) + "\"";
    }
}
