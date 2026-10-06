package dev.tianshu.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行控制台的**页面目录**（纯逻辑）—— 终端里 `settings` 那一面 25 条命令的 App 落点。
 *
 * 为什么不手写：`/config/*` 有 **82 条路由、27 个族**。手写 27 个界面既累又必然分叉，
 * 而且每加一族都要再改一次界面。这里只声明「哪一族、什么标题、拉哪个路径」，
 * 渲染交给 {@link ConfigView} 一份通用代码。
 *
 * ⚠ 这里的每个 path 都**必须在真机 API 上存在** —— HostTest 会拿
 * `test/fixtures/api/` 里抓自真 serve 的夹具逐个核对（夹具 = 那条路由真返回过 200）。
 * 这样就不会出现"界面里有个按钮，打过去 404"的情况。
 */
public final class ConsoleCatalog {

    private ConsoleCatalog() {
    }

    /** 一个配置族：标题 + 拉取路径。 */
    public static final class Fam {
        public final String title;
        public final String path;
        public final String hint;
        public final int depth;      // 往下摊几层

        Fam(String title, String path, String hint, int depth) {
            this.title = title;
            this.path = path;
            this.hint = hint == null ? "" : hint;
            this.depth = depth;
        }
    }

    /** 运行配置族（都是 `/config/*`，顶层布尔可就地开关）。 */
    public static final List<Fam> CONFIG = new ArrayList<Fam>();

    /** 运行环境/状态（只读展示）。 */
    public static final List<Fam> INFO = new ArrayList<Fam>();

    /**
     * 这个族是不是**只读**的（运行环境 / 状态，即 {@link #INFO} 那一批）。
     *
     * 2026-10-05 走查核出：设置页把 INFO 与 CONFIG 送进同一个 addSection，而那里
     * **无条件**接上写回调 —— 于是 `account/status` 的 `loggedIn` 渲染成一个能点的
     * 开关、`environment` 的 `platform` 渲染成能点的编辑项。点下去对一个只读端点发
     * PUT，用户看到的只有一句「改不动」。判据收在这儿，界面只负责问。
     */
    public static boolean isReadOnly(String path) {
        if (path == null) return false;
        for (Fam f : INFO) {
            if (path.equals(f.path)) return true;
        }
        return false;
    }

    /**
     * 族的总数 —— 界面上那句「N 个族」**从这里取**。
     *
     * 原先那个数字是手写的（写的是 27），而列表早就长到 30 了：手写的数迟早与实际
     * 漂开，且漂了没人会知道（2026-10-05 核出）。
     */
    public static int totalCount() {
        return CONFIG.size() + INFO.size();
    }

    static {
        // ---- 配置族：顺序即页面顺序（常用的靠前）----
        CONFIG.add(new Fam("镜像与回退", "/config/mirrors",
                "国内镜像源：GitHub / npm / pip / go / rust。开了下载依赖明显快。", 1));
        CONFIG.add(new Fam("模型路由（子代理）", "/config/routing",
                "审查子代理、写工档案各自用哪个 provider:model。", 2));
        // 2026-10-06③：官方 `/config` 一共 31 个族，App 原先只声明 29 —— 这是漏掉的那个。
        //（另一个 `fix-autocrlf` **只有 POST**，不是 GET 族，不算缺口 —— 实测 GET 返 404。）
        CONFIG.add(new Fam("模型调用记录", "/config/provider-calls",
                "各 provider 的调用次数与耗时。", 2));
        CONFIG.add(new Fam("默认星域", "/config/default-domain",
                "改变方法论与决策阈值，不改工具面。", 1));
        CONFIG.add(new Fam("工具预设", "/config/tool-preset", "工具面档位。", 1));
        CONFIG.add(new Fam("审批", "/config/approval", "权限模式：监督 / 自动 / 全自动。", 1));
        CONFIG.add(new Fam("交付", "/config/delivery", "交付行为。", 1));
        CONFIG.add(new Fam("PR 默认", "/config/pr-defaults", "合并方式 / 自动修 / 自动合 / CI 轮询。", 1));
        CONFIG.add(new Fam("检查点", "/config/checkpoint", "每几轮落一个检查点（回滚用）。", 1));
        CONFIG.add(new Fam("权限目录", "/config/permission-dirs", "额外的可读/可写目录。", 1));
        CONFIG.add(new Fam("bash 权限", "/config/bash-permissions",
                "bash 工具的允许/拒绝清单。", 1));
        CONFIG.add(new Fam("工作区", "/config/workspace", "默认目录与暂存根。", 1));
        CONFIG.add(new Fam("网络", "/config/network", "代理与 noProxy。", 1));
        CONFIG.add(new Fam("抓取", "/config/fetch", "网页抓取的超时 / 体积 / 重定向。", 1));
        CONFIG.add(new Fam("搜索", "/config/search", "搜索后端与 key 状态。", 2));
        CONFIG.add(new Fam("编辑器", "/config/editor", "换行符/平台。", 1));
        CONFIG.add(new Fam("Shell", "/config/shell", "git / git-bash 路径探测。", 1));
        CONFIG.add(new Fam("视觉自动桥", "/config/vision-auto-bridge", "把图自动转给视觉模型。", 1));
        CONFIG.add(new Fam("问候语", "/config/greeting", "启动问候。", 2));
        CONFIG.add(new Fam("禅模式", "/config/zen", "收敛工具面做深度专注（新会话生效）。", 1));
        CONFIG.add(new Fam("精简运行时", "/config/runtime-lean", "降内存占用。", 1));
        CONFIG.add(new Fam("Pro 特性", "/config/pro-features", "已授权/可用的进阶能力。", 2));
        // —— 2026-10-02 审计补：服务端有 31 个 /config 族，这里原本只列了 21 个。
        // 少掉的两个正是命令的落点：/vision 说"去模型页"，可模型页是服务商配置；
        // /grant 的授权列表也一直没地方看。
        CONFIG.add(new Fam("视觉模型", "/config/vision-model",
                "独立识图模型（把图交给它看，而非当前对话模型）。", 2));
        CONFIG.add(new Fam("图像生成模型", "/config/image-gen-model", "文生图模型。", 2));
        CONFIG.add(new Fam("电脑操作", "/config/computer-use",
                "屏幕操作能力与授权（Pro 特性）。", 2));
        CONFIG.add(new Fam("DeepSeek 授权", "/config/deepseek/auth",
                "DeepSeek 账号登录状态（官方账单与用量要用它）。", 1));

        // ---- 环境/状态（只读）----
        // 注意：`/storage` 与 `/remote/info` 归**面板页**（它们更像运行时状态），
        // 两个目录不许声明同一个路径 —— HostTest 有一条防重复断言。
        INFO.add(new Fam("运行环境自检", "/environment",
                "Node / git / Python / Java 各自的版本与路径。", 2));
        INFO.add(new Fam("账号", "/account/status", "登录状态与星籍。", 1));
        INFO.add(new Fam("账户余额", "/config/balance", "服务商账户余额。", 2));
        INFO.add(new Fam("DeepSeek 账单", "/config/deepseek/cost",
                "官方账单（没登录 DeepSeek 时为空）。", 2));
        INFO.add(new Fam("DeepSeek 用量", "/config/deepseek/summary",
                "官方用量汇总（同上，需登录）。", 2));
    }

    /** 全部路径（供测试逐个核对"真机上存在"）。 */
    public static List<String> allPaths() {
        List<String> out = new ArrayList<String>();
        for (Fam f : CONFIG) out.add(f.path);
        for (Fam f : INFO) out.add(f.path);
        return out;
    }

    // ---------------------------------------------------------------- 分组（显示层）

    /**
     * 分组顺序 —— 页面上按这个次序出组，空组不画。
     *
     * 用户反馈「高级：原始配置展开后感觉很劣质、很难懂」：27 个族平铺着，找一样东西要
     * 从头扫到尾。分组之后，"星域在哪"这类问题变成"看图找组名"。
     */
    public static final List<String> GROUP_ORDER = new ArrayList<String>(Arrays.asList(
            "常用", "模型与能力", "运行环境", "交付与账户", "其它"));

    /** 路径 → 组名。**没登记的一律归「其它」**（不丢、不崩）。 */
    private static final Map<String, String> GROUPS = new HashMap<String, String>();

    private static void g(String path, String group) {
        GROUPS.put(path, group);
    }

    static {
        // 常用：天天会动的几项
        g("/config/default-domain", "常用");
        g("/config/approval", "常用");
        g("/config/routing", "常用");
        g("/config/mirrors", "常用");
        g("/config/tool-preset", "常用");
        g("/config/zen", "常用");
        g("/config/runtime-lean", "常用");

        // 模型与能力
        g("/config/vision-model", "模型与能力");
        g("/config/image-gen-model", "模型与能力");
        g("/config/vision-auto-bridge", "模型与能力");
        g("/config/computer-use", "模型与能力");
        g("/config/search", "模型与能力");
        g("/config/fetch", "模型与能力");
        g("/config/pro-features", "模型与能力");

        // 运行环境
        g("/config/workspace", "运行环境");
        g("/config/network", "运行环境");
        g("/config/editor", "运行环境");
        g("/config/shell", "运行环境");
        g("/config/bash-permissions", "运行环境");
        g("/config/permission-dirs", "运行环境");
        g("/config/checkpoint", "运行环境");
        g("/config/greeting", "运行环境");

        // 交付与账户
        g("/config/delivery", "交付与账户");
        g("/config/pr-defaults", "交付与账户");
        g("/config/deepseek/auth", "交付与账户");
        g("/config/balance", "交付与账户");
        g("/config/deepseek/cost", "交付与账户");
        g("/config/deepseek/summary", "交付与账户");
        g("/account/status", "交付与账户");
        g("/environment", "交付与账户");
    }

    /** 某一族归哪一组。没登记过的返回「其它」——**绝不返回 null**（那会让整族从页面上消失）。 */
    public static String groupOf(String path) {
        String hit = GROUPS.get(path);
        return hit == null ? "其它" : hit;
    }
}
