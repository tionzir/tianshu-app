package dev.tianshu.host;

import java.util.List;
import java.util.Map;

/**
 * 把接口返回体压成**对话里能读的一段文本**（纯逻辑）。
 *
 * 为什么需要：`/plan-list` `/goal-status` `/doctor` `/sensorium` `/rewind` `/rollback`
 * 这些都是 **GET 类命令 —— 结果就是数据本身**。而对话里只显示一句「✓ 已执行」等于没说：
 * 提示语写着"见下"，下面却什么都没有。这个类负责把 JSON 摊成人话。
 *
 * 三条约定：
 *   - 对象 → 摊成 `label = value` 行（复用 {@link ConfigView}，与设置页同一套摊平规则）；
 *   - 数组 → 逐项编号，项是对象就再摊一层；
 *   - 非 JSON / 标量 → 原样显示，不假装解析。
 * 最后统一截断，免得一条 `/doctor` 把整屏对话顶掉。
 */
public final class ApiResult {

    /** 对话里单条结果最多显示这么多字符。 */
    public static final int DEFAULT_MAX = 900;

    private ApiResult() {
    }

    public static String readable(String body) {
        return readable(body, DEFAULT_MAX);
    }

    public static String readable(String body, int maxChars) {
        if (body == null) return "";
        String t = body.trim();
        if (t.isEmpty()) return "（服务端返回空）";

        Object parsed = null;
        try {
            parsed = Json.parse(t);
        } catch (Throwable ignored) {
            // 不是 JSON —— 下面按原文走
        }

        StringBuilder sb = new StringBuilder();
        if (parsed instanceof Map) {
            appendRows(sb, ConfigView.flattenMap(Json.map(parsed), 2), null);
        } else if (parsed instanceof List) {
            List<Object> l = Json.list(parsed);
            if (l == null || l.isEmpty()) {
                sb.append("（空列表）");
            } else {
                int i = 0;
                for (Object item : l) {
                    if (item instanceof Map) {
                        appendRows(sb, ConfigView.flattenMap(Json.map(item), 1), "[" + (i + 1) + "]");
                    } else {
                        sb.append("- ").append(String.valueOf(item)).append('\n');
                    }
                    i++;
                }
            }
        } else {
            sb.append(t);
        }

        String out = sb.toString().trim();
        if (out.isEmpty()) out = t;                 // 摊出来是空的（如全 null）也别显示空白
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars) + "…（已截断）";
        }
        return out;
    }

    private static void appendRows(StringBuilder sb, List<ConfigView.Row> rows, String prefix) {
        if (rows == null) return;
        for (ConfigView.Row r : rows) {
            if (prefix != null) sb.append(prefix).append(' ');
            sb.append(r.label).append(" = ").append(r.value).append('\n');
        }
    }
}
