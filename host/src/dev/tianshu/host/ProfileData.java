package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 「个人」页的纯逻辑 —— 官方 **profile** 面的解析与人话格式化。不 import android.\*，
 * 所以 HostTest 能直接拿真夹具断言（与 {@link Account}、{@link SessionList} 同一套路）。
 *
 * <h3>这一页吃的是哪三个路由</h3>
 * 三个都在官方 3.28.0 上实测过、并抓了夹具（`test/fixtures/api/`）：
 * <pre>
 *   GET /account/status         → 星籍 / 主星域 / 称号（身份卡）
 *   GET /profile/overview       → {profileKey, login:{totalMs,trackedSince},
 *                                  repositories:[{url,title,description}], tokens:{total,peak,activeDays,scannedFiles}}
 *   GET /profile/domain-usage   → {days, totalRuns, domains:[{key,count,lastUsedAt}],
 *                                  coverage:{partial,missingRuns,unreadableSessions,firstRecordedAt}}
 * </pre>
 *
 * 形状不是猜的：两份响应结构是从**设备上那份 3.28.0 的 dist** 里读出来的
 * （`aggregateDomainUsage()` 与 `GET /profile/overview` 的实现，见
 * `dist/chunk-KCNW2SCC.js`）—— 因为真夹具里 `domains` 是空数组、`repositories` 也是空的，
 * 光看夹具推不出条目字段名。
 *
 * <h3>两个必须容错的边界（真机上都会遇到）</h3>
 *   - `tokens` **可以是 `null`**：服务端在 usage 收集失败时就是返回 null。null 不是 0 ——
 *     0 会被读成"你一个 token 都没用"，那是假话，所以这里用 {@link Overview#hasTokens} 区分。
 *   - `domains` 可以是**空数组**（近 30 天没跑过任何星域），`days` 也可以是 7 或 30 两种。
 */
public final class ProfileData {

    private ProfileData() {
    }

    /**
     * 这一页吃到的三个官方路由 —— 每个都必须在 `test/fixtures/api/` 有夹具。
     *
     * HostTest 逐条核对这条清单：有夹具 = 那条路由**真返回过 200**。这样界面里就长不出
     * "打过去 404" 的卡（凭空发明路径是这个项目踩过的坑，见 `app-vs-harness-command-gap.md`）。
     */
    public static final List<String> PATHS = Arrays.asList(
            "/account/status", "/profile/overview", "/profile/domain-usage");

    // ---------------------------------------------------------------- 概览

    /** 一个登记过的仓库（`GET /profile/overview` 的 `repositories[]`）。 */
    public static final class Repo {
        public final String title;
        public final String url;
        public final String description;

        Repo(String title, String url, String description) {
            this.title = nz(title);
            this.url = nz(url);
            this.description = nz(description);
        }

        /** 一行上显示什么：有标题用标题，没有就退到网址。 */
        public String label() {
            return title.length() > 0 ? title : url;
        }
    }

    /** `GET /profile/overview` 的解析结果。 */
    public static final class Overview {
        /** 账号 key（未登录/本地就是 `local`）。 */
        public final String profileKey;
        /** 累计登录时长（毫秒）；0 = 没有记录。 */
        public final long loginMs;
        /** `tokens` 是否真的拿到了 —— **false 时别把 0 当数据画出来**。 */
        public final boolean hasTokens;
        public final long tokenTotal;
        public final long tokenPeak;
        public final int activeDays;
        public final int scannedFiles;
        public final List<Repo> repos;

        Overview(String profileKey, long loginMs, boolean hasTokens, long tokenTotal,
                 long tokenPeak, int activeDays, int scannedFiles, List<Repo> repos) {
            this.profileKey = nz(profileKey);
            this.loginMs = loginMs;
            this.hasTokens = hasTokens;
            this.tokenTotal = tokenTotal;
            this.tokenPeak = tokenPeak;
            this.activeDays = activeDays;
            this.scannedFiles = scannedFiles;
            this.repos = repos;
        }
    }

    /** 认不出（null / 空 / 脏 JSON / 数组）一律返回一份安全的空档，界面照常画空态。 */
    public static Overview parseOverview(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return new Overview(null, 0, false, 0, 0, 0, 0, new ArrayList<Repo>());

        long loginMs = 0;
        Map<String, Object> login = Json.map(root.get("login"));
        if (login != null) loginMs = num(login.get("totalMs"));

        Map<String, Object> tok = Json.map(root.get("tokens"));
        boolean hasTokens = tok != null;
        long total = 0, peak = 0;
        int days = 0, files = 0;
        if (tok != null) {
            total = num(tok.get("total"));
            peak = num(tok.get("peak"));
            days = (int) num(tok.get("activeDays"));
            files = (int) num(tok.get("scannedFiles"));
        }

        List<Repo> repos = new ArrayList<Repo>();
        List<Object> arr = Json.list(root.get("repositories"));
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> m = Json.map(o);
                if (m == null) continue;
                repos.add(new Repo(Json.str(m.get("title")), Json.str(m.get("url")),
                        Json.str(m.get("description"))));
            }
        }
        return new Overview(Json.str(root.get("profileKey")), loginMs, hasTokens,
                total, peak, days, files, repos);
    }

    // ---------------------------------------------------------------- 星域用量

    /** 一个星域在这段时间里跑了几轮。 */
    public static final class Domain {
        /** 星域 id（如 `qiming`）—— 中文名走 {@link StatusBar#domainName}，不另建对照表。 */
        public final String key;
        public final int count;
        /** 最后一次用它的时刻（毫秒）；0 = 没有记录。 */
        public final long lastUsedAt;

        Domain(String key, int count, long lastUsedAt) {
            this.key = nz(key);
            this.count = count;
            this.lastUsedAt = lastUsedAt;
        }
    }

    /** `GET /profile/domain-usage` 的解析结果。 */
    public static final class Usage {
        /** 窗口天数（官方只给 7 或 30）。 */
        public final int days;
        /** 去重后的轮数（服务端 `runs.size`）。 */
        public final int totalRuns;
        /** 有轮次没记到星域的数量 —— 大于 0 时页面要如实说一句，别让数字看着像全量。 */
        public final int missingRuns;
        /** 按轮数降序（服务端已排好，这里保序）。 */
        public final List<Domain> domains;

        Usage(int days, int totalRuns, int missingRuns, List<Domain> domains) {
            this.days = days;
            this.totalRuns = totalRuns;
            this.missingRuns = missingRuns;
            this.domains = domains;
        }
    }

    public static Usage parseUsage(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        if (root == null) return new Usage(30, 0, 0, new ArrayList<Domain>());

        int days = (int) num(root.get("days"));
        if (days <= 0) days = 30;                       // 认不出就用官方默认窗口
        int totalRuns = (int) num(root.get("totalRuns"));
        int missingRuns = 0;
        Map<String, Object> cov = Json.map(root.get("coverage"));
        if (cov != null) missingRuns = (int) num(cov.get("missingRuns"));

        List<Domain> ds = new ArrayList<Domain>();
        List<Object> arr = Json.list(root.get("domains"));
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> m = Json.map(o);
                if (m == null) continue;
                String key = Json.str(m.get("key"));
                if (key == null || key.isEmpty()) continue;   // 没有星域的条目画不出来，跳过
                ds.add(new Domain(key, (int) num(m.get("count")), num(m.get("lastUsedAt"))));
            }
        }
        return new Usage(days, totalRuns, missingRuns, ds);
    }

    /** 比例基准：最大轮数。空表返回 0（调用方据此不走比例分支，免得除零）。 */
    public static int topCount(List<Domain> ds) {
        int top = 0;
        if (ds != null) {
            for (Domain d : ds) if (d.count > top) top = d.count;
        }
        return top;
    }

    // ---------------------------------------------------------------- 官方仓库

    /**
     * 「官方仓库」卡用的那份信息 —— 取自 **harness 自己的 `package.json`**。
     *
     * <p>为什么不写死一个 URL：仓库改名 / 换组织是迟早的事，而写死的链接不会有人记得回来改。
     * harness 自己认哪个仓库是有**权威声明**的 —— {@code package.json} 的 {@code repository}
     * 字段，而它正是 harness 更新检查（`fetchGitHubLatestVersion` 那段读 `pkg.repository?.url`）
     * 用来推 owner/repo 的来源。App 读同一个文件，就永远跟它一致。
     *
     * <p>真机核对（2026-10-06，设备上那份 3.28.0）：`repository` / `homepage` / `bugs`
     * 三个字段一致指向 `huiliyi37/Tianshu-Tui` —— 所以"官方"不是猜的。
     */
    public static final class OfficialRepo {
        public final String owner;
        public final String name;
        /** 规范化后的浏览地址（能直接点开的那种）；认不出为空串。 */
        public final String url;
        /** harness 版本（如 `3.28.0`）；读不到为空串。 */
        public final String version;

        OfficialRepo(String owner, String name, String url, String version) {
            this.owner = nz(owner);
            this.name = nz(name);
            this.url = nz(url);
            this.version = nz(version);
        }

        /** `owner/name` —— 卡片上那一行显示的就是它。 */
        public String slug() {
            return owner.length() > 0 && name.length() > 0 ? owner + "/" + name : "";
        }

        /** 拿得出一份能点开的地址，才算可用。 */
        public boolean ok() {
            return url.length() > 0 && slug().length() > 0;
        }
    }

    /** harness 的 `package.json` 在 rootfs 里的相对路径。 */
    public static final String HARNESS_PACKAGE_REL =
            "usr/local/lib/node_modules/tianshu-harness/package.json";

    /**
     * 解析官方仓库。取值顺序与 harness 更新检查一致：
     * {@code repository.url} 优先，退回 {@code homepage}。
     *
     * <p>三种写法都要吃得下（真机上就这三种）：`git+https://github.com/o/r.git`、
     * `https://github.com/o/r#readme`、`https://github.com/o/r`。认不出返回空档，
     * 界面据此**不画这张卡**而不是画一个点不动的按钮。
     */
    public static OfficialRepo parseOfficialRepo(String pkgJson) {
        Map<String, Object> root = Json.map(Json.parse(pkgJson));
        if (root == null) return new OfficialRepo("", "", "", "");

        String raw = null;
        Object repoObj = root.get("repository");
        if (repoObj instanceof String) {
            raw = (String) repoObj;                     // 有的包就写成一行字符串
        } else {
            Map<String, Object> repo = Json.map(repoObj);
            if (repo != null) raw = Json.str(repo.get("url"));
        }
        if (raw == null || raw.isEmpty()) raw = Json.str(root.get("homepage"));

        String url = normalizeRepoUrl(raw);
        String owner = "", name = "";
        int cut = url.lastIndexOf('/');
        if (cut > 0) {
            String head = url.substring(0, cut);
            int cut2 = head.lastIndexOf('/');
            if (cut2 > 0) {
                owner = head.substring(cut2 + 1);
                name = url.substring(cut + 1);
            }
        }
        return new OfficialRepo(owner, name, url, Json.str(root.get("version")));
    }

    /** `git+https://github.com/o/r.git#readme` → `https://github.com/o/r`。 */
    private static String normalizeRepoUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("git+")) s = s.substring(4);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        return s;
    }

    // ---------------------------------------------------------------- 人话

    /**
     * 累计量的中文读法：`110455901` → `1.1 亿`，`12345` → `1.2 万`。
     *
     * 为什么不用 {@link Providers#humanTokens}：那一个是给**规格数字**用的（上下文窗口 256K、
     * 输出上限 8K），走 K/M 更贴；而这一页是**用量**（亿级 token、万级轮次），
     * 中文读者对「亿 / 万」的量级感更直接 —— 110455901 写成 `110455K` 谁也读不出多少。
     * 两者分工写在这儿，别再各改一半。
     *
     * 0 返回破折号而不是 "0"：这一页的 0 都是"没数据"，显示成 0 会被读成"确实为零"。
     */
    public static String humanCount(long n) {
        if (n <= 0) return "—";
        if (n >= 100000000L) return trim(n / 100000000.0) + " 亿";
        if (n >= 10000L) return trim(n / 10000.0) + " 万";
        return String.valueOf(n);
    }

    /** 一位小数，整数值不留 `.0`（`2.0` → `2`，`1.1` → `1.1`）。 */
    private static String trim(double v) {
        double r = Math.round(v * 10) / 10.0;
        if (r == Math.floor(r)) return String.valueOf((long) r);
        return String.valueOf(r);
    }

    /**
     * 时长的中文读法：`3 小时 4 分` / `2 天 3 小时` / `1 天` / `不到 1 分`。
     *
     * 只给**两级**：登录时长是给人扫一眼的量级，`2 天 3 小时 17 分 9 秒` 那种精度没有用，
     * 反而把卡片撑成一行长数字。
     */
    public static String humanSpan(long ms) {
        if (ms <= 0) return "—";
        long day = ms / 86400000L;
        long hour = (ms % 86400000L) / 3600000L;
        long min = (ms % 3600000L) / 60000L;
        if (day > 0) return hour > 0 ? day + " 天 " + hour + " 小时" : day + " 天";
        if (hour > 0) return min > 0 ? hour + " 小时 " + min + " 分" : hour + " 小时";
        if (min > 0) return min + " 分";
        return "不到 1 分";
    }

    // ---------------------------------------------------------------- 内部

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (NumberFormatException ignored) {
                // 非数字当 0 —— 脏数据不该让整页读不出来
            }
        }
        return 0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
