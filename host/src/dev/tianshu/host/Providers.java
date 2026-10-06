package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型服务商目录（纯逻辑）—— 终端里 `/connect` 在 App 里的落点。
 *
 * 形状**取自真机 API**，不是猜的：夹具 `test/fixtures/api/config__providers.json`
 * 是从一个真跑起来的 `rivet serve` 上抓下来的（43 个夹具之一）。字段：
 *
 *   {"providers":[{"name","label","baseUrl","protocol","isDefault","keyless",
 *                  "keyStatus":{"source","ref"},
 *                  "models":[{"id","description","contextWindow","maxTokens",
 *                             "reasoningEffort","supportsVision","effortSupported"}]}]}
 *
 * ⚠ `keyStatus.ref` 是**服务端掩码过的**（形如 `***4dff`），不是明文密钥 ——
 * 界面上只显示它，绝不显示真 key（这个包本身就带真 key）。
 *
 * 纯逻辑、不碰 Android —— HostTest 能直接拿真夹具断言。
 */
public final class Providers {

    private Providers() {
    }

    /** 一个可选模型。 */
    public static final class Model {
        public final String id;
        public final String desc;
        public final String effort;
        public final long contextWindow;
        public final long maxTokens;
        public final boolean vision;
        public final boolean effortSupported;

        Model(String id, String desc, String effort, long ctx, long maxTok,
              boolean vision, boolean effortSupported) {
            this.id = id;
            this.desc = desc == null ? "" : desc;
            this.effort = effort == null ? "" : effort;
            this.contextWindow = ctx;
            this.maxTokens = maxTok;
            this.vision = vision;
            this.effortSupported = effortSupported;
        }

        /** 一行副标题："1M 上下文 · 256K 输出 · 支持图像 · 强度 high"。 */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (contextWindow > 0) sb.append(humanTokens(contextWindow)).append(" 上下文");
            if (maxTokens > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(humanTokens(maxTokens)).append(" 输出");
            }
            if (vision) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("支持图像");
            }
            if (effort.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("强度 ").append(effort);
            }
            return sb.toString();
        }
    }

    /** 一个服务商。 */
    public static final class Provider {
        public final String name;
        public final String label;
        public final String baseUrl;
        public final String protocol;
        public final boolean isDefault;
        public final boolean keyless;
        public final String keySource;
        public final String keyRef;
        public final List<Model> models = new ArrayList<Model>();

        Provider(String name, String label, String baseUrl, String protocol,
                 boolean isDefault, boolean keyless, String keySource, String keyRef) {
            this.name = name == null ? "" : name;
            this.label = label == null || label.isEmpty() ? this.name : label;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.protocol = protocol == null ? "" : protocol;
            this.isDefault = isDefault;
            this.keyless = keyless;
            this.keySource = keySource == null ? "" : keySource;
            this.keyRef = keyRef == null ? "" : keyRef;
        }

        /** 密钥状态：keyless 算"不需要"；source 非 none/空 算"已配"。 */
        public boolean keyReady() {
            if (keyless) return true;
            return keySource.length() > 0 && !"none".equals(keySource);
        }

        /** 界面上那行："密钥 ***4dff" / "密钥未配" / "无需密钥"。 */
        public String keyText() {
            if (keyless) return "无需密钥";
            if (!keyReady()) return "密钥未配";
            return "密钥 " + (keyRef.isEmpty() ? "已配" : keyRef);
        }
    }

    /** `/config/providers` → 服务商列表。解析不出来返回空表（界面空着，不崩）。 */
    public static List<Provider> parse(String json) {
        List<Provider> out = new ArrayList<Provider>();
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return out;
        List<Object> arr = Json.list(root.get("providers"));
        if (arr == null) return out;
        for (Object o : arr) {
            Map<String, Object> m = Json.map(o);
            if (m == null) continue;
            Map<String, Object> ks = Json.map(m.get("keyStatus"));
            Provider p = new Provider(
                    Json.str(m.get("name")),
                    Json.str(m.get("label")),
                    Json.str(m.get("baseUrl")),
                    Json.str(m.get("protocol")),
                    truthy(m.get("isDefault")),
                    truthy(m.get("keyless")),
                    ks == null ? null : Json.str(ks.get("source")),
                    ks == null ? null : Json.str(ks.get("ref")));
            List<Object> ms = Json.list(m.get("models"));
            if (ms != null) {
                for (Object mo : ms) {
                    Map<String, Object> mm = Json.map(mo);
                    if (mm == null) continue;
                    String id = Json.str(mm.get("id"));
                    if (id == null || id.isEmpty()) continue;
                    p.models.add(new Model(
                            id,
                            Json.str(mm.get("description")),
                            Json.str(mm.get("reasoningEffort")),
                            num(mm.get("contextWindow")),
                            num(mm.get("maxTokens")),
                            truthy(mm.get("supportsVision")),
                            truthy(mm.get("effortSupported"))));
                }
            }
            out.add(p);
        }
        return out;
    }

    /**
     * `/config/default-model` → `"provider:modelId"`；没设过返回 null。
     *
     * 注意"没设过"是真会发生的：真机上这个字段就是 `null`（那时用的是服务商自带的
     * isDefault）。所以界面必须能表达"跟随服务商默认"，不能把 null 当成空字符串。
     */
    public static String defaultModel(String json) {
        Map<String, Object> m = Json.map(Json.parse(json));
        if (m == null) return null;
        String v = Json.str(m.get("defaultModel"));
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** `"deepseek:deepseek-v4-pro"` → `["deepseek","deepseek-v4-pro"]`；不合法返回 null。 */
    public static String[] splitDefault(String defaultModel) {
        if (defaultModel == null) return null;
        int i = defaultModel.indexOf(':');
        if (i <= 0 || i >= defaultModel.length() - 1) return null;
        return new String[]{defaultModel.substring(0, i), defaultModel.substring(i + 1)};
    }

    /** 当前生效的模型 id —— 显式设过就用它，否则用 isDefault 服务商的第一个模型。 */
    public static Model currentModel(List<Provider> ps, String defaultModel) {
        String[] d = splitDefault(defaultModel);
        if (d != null) {
            for (Provider p : ps) {
                if (!p.name.equals(d[0])) continue;
                for (Model m : p.models) if (m.id.equals(d[1])) return m;
            }
        }
        for (Provider p : ps) {
            if (p.isDefault && !p.models.isEmpty()) return p.models.get(0);
        }
        for (Provider p : ps) if (!p.models.isEmpty()) return p.models.get(0);
        return null;
    }

    /** 当前生效的模型名字，界面顶栏用。 */
    public static String currentLabel(List<Provider> ps, String defaultModel) {
        String[] d = splitDefault(defaultModel);
        if (d != null) return d[0] + " · " + d[1];
        for (Provider p : ps) {
            if (p.isDefault && !p.models.isEmpty()) return p.label + " · " + p.models.get(0).id;
        }
        return "（未知）";
    }

    /**
     * **实际生效**的那一项，返回 `{providerName, modelId}`；没有可选的就 null。
     *
     * 为什么要有它：`currentLabel` 在 `defaultModel == null` 时会回退显示"默认服务商的第一个模型"，
     * 而模型页的**勾选**原先只认显式 defaultModel —— 于是页头写着"当前模型：X"、列表却一行都没勾
     *（2026-10-04 深查）。把"到底哪一项在生效"收成一个纯函数，页头与勾选引用同一份答案。
     *
     * 显式指定但不在列表里时**原样返回**（用户确实那么配了）—— 那种情况下列表自然没有可勾的行，
     * 页头与列表仍然一致。
     */
    public static String[] effectiveSelection(List<Provider> ps, String defaultModel) {
        String[] d = splitDefault(defaultModel);
        if (d != null) return d;
        if (ps != null) {
            for (Provider p : ps) {
                if (p.isDefault && !p.models.isEmpty()) return new String[]{p.name, p.models.get(0).id};
            }
        }
        return null;
    }

    /** 上下文/输出的量级人话："1000000" → "1M"，"256000" → "256K"。 */
    public static String humanTokens(long n) {
        if (n <= 0) return "—";
        // 2026-10-04 深查：原条件是 `n % 100000 == 0`（十万），于是 1500000 也满足，
        // 再被整数除法截成 "1M" —— 必须是**整百万**才走这一支，其余落到 K。
        if (n >= 1000000 && n % 1000000 == 0) return (n / 1000000) + "M";
        if (n >= 1000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o)
                || (o instanceof Number && ((Number) o).doubleValue() != 0)
                || "true".equals(o);
    }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (NumberFormatException ignored) {
                // 非数字当 0
            }
        }
        return 0;
    }
}
