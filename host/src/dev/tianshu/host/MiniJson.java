package dev.tianshu.host;

/**
 * 极小的 JSON 取值器。
 *
 * 只做一件事：从事件信封里取字符串/整数字段。刻意不引 JSON 依赖 ——
 * 这样解析逻辑能像其它纯逻辑一样在容器里跑测试，也不必为 89 MB 的包再加一个库。
 *
 * 不追求通用 JSON 支持：只认 `"key":"value"` 与 `"key":123` 这两种形状。
 */
public final class MiniJson {

    private MiniJson() {
    }

    /** 取第一个 `"key":"value"` 里的 value；键不存在或不是字符串则返回 null。 */
    public static String str(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\":";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) return null;
            int j = k + needle.length();
            while (j < json.length() && (json.charAt(j) == ' ' || json.charAt(j) == '\t')) j++;
            if (j >= json.length() || json.charAt(j) != '"') {
                from = k + 1;                       // 不是字符串值（数字/对象/数组），往后找
                continue;
            }
            StringBuilder sb = new StringBuilder();
            j++;
            while (j < json.length()) {
                char c = json.charAt(j);
                if (c == '\\') {
                    j++;
                    if (j >= json.length()) break;
                    char e = json.charAt(j);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (j + 4 < json.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(json.substring(j + 1, j + 5), 16));
                                    j += 4;
                                } catch (NumberFormatException ignored) {
                                    // 坏转义就当字面量处理
                                }
                            }
                            break;
                        default: sb.append(e); break;    // \" \\ \/ 等
                    }
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
                j++;
            }
            return null;                            // 字符串没闭合
        }
    }

    /** 取第一个 `"key":123` 里的整数；取不到返回 null。 */
    public static Integer num(String json, String key) {
        Long v = longNum(json, key);
        if (v == null) return null;
        if (v.longValue() > Integer.MAX_VALUE || v.longValue() < Integer.MIN_VALUE) return null;
        return Integer.valueOf(v.intValue());
    }

    /**
     * 取第一个 `"key":123` 里的**长整数**；取不到返回 null。
     *
     * 为什么必须有这个：会话响应里的 `createdAt` / `updatedAt` 是 13 位毫秒时间戳
     * （如 `1790871608499`），`Integer.valueOf` 装不下会抛 NumberFormatException，
     * 而 {@link #num} 把异常吞成 null —— 于是时间戳静默变成 null，
     * 列表页上一整列时间凭空消失，还不报错。所以这一层必须用 long。
     */
    public static Long longNum(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\":";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int j = k + needle.length();
        while (j < json.length() && (json.charAt(j) == ' ' || json.charAt(j) == '\t')) j++;
        int start = j;
        if (j < json.length() && (json.charAt(j) == '-' || json.charAt(j) == '+')) j++;
        while (j < json.length() && json.charAt(j) >= '0' && json.charAt(j) <= '9') j++;
        if (j == start) return null;
        try {
            return Long.valueOf(json.substring(start, j));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
