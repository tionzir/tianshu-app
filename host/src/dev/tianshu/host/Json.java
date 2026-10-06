package dev.tianshu.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析器 —— 只做本项目需要的形状（对象 / 数组 / 字符串 / 数字 / 布尔 / null）。
 *
 * 为什么要自己写：
 *   1. {@link CommandCatalog} 要读 `data/components.json`（UI 落点注册表）；
 *   2. HostTest 在容器里是 `java -cp <classes>:xz.jar` 直接跑的（不带 android.jar），
 *      所以 `org.json` 在测试时不可用；
 *   3. 为一个 100 MB 的包再引一个 JSON 库也不划算。
 *
 * 刻意「宽进」：解析不出来一律返回 null，不抛异常 —— 目录读不到时界面应当空着，
 * 而不是把 Activity 崩掉。
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** 解析一段 JSON 文本；解析不出来返回 null（不抛）。 */
    public static Object parse(String text) {
        if (text == null) return null;
        Json p = new Json(text);
        p.ws();
        if (p.i >= p.s.length()) return null;
        try {
            return p.value();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    // ---------------------------------------------------------------- 内部
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;                                        // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (i < s.length()) {
            ws();
            String key = string();
            ws();
            if (i < s.length() && s.charAt(i) == ':') i++;
            ws();
            m.put(key == null ? "" : key, value());
            ws();
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; break; }
            i++;                                    // 容错：跳过意外字符
        }
        return m;
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++;                                        // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (i < s.length()) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) break;
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; break; }
            i++;                                    // 容错
        }
        return l;
    }

    private String string() {
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= s.length()) break;
                char e = s.charAt(i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                // 坏转义当字面量，不中断解析
                            }
                        }
                        break;
                    default: sb.append(e); break;    // \" \\ \/ 等
                }
                i++;
            } else if (c == '"') {
                i++;
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        String tok = s.substring(start, i);
        if (tok.isEmpty()) {
            i++;                                    // 容错：不认识的字符往前挪一格
            return null;
        }
        try {
            if (tok.indexOf('.') < 0 && tok.indexOf('e') < 0 && tok.indexOf('E') < 0) {
                return Long.valueOf(tok);
            }
            return Double.valueOf(tok);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
