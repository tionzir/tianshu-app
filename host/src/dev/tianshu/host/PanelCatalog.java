package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板面的**页面目录**（纯逻辑）—— 终端里 `panels` 那一面 22 条命令的 App 落点。
 *
 * 这一面里的命令分两种，处理方式完全不同：
 *   - **有只读路由的**（`/tasks` `/mcp` `/plugin` `/cache` `/mission` …）→ 做成面板页，
 *     就是下面这份清单；
 *   - **编排类**（`/council` `/galaxy` `/team` `/scout` `/starflow` `/workflow` …）→
 *     它们本来就是"一句话交给 agent 去跑"，**App 直接发出去是对的**，不该做界面。
 *     见 {@link CommandSurfaces} 的分类。
 *
 * ⚠ 每个 path 都必须在真机 API 上存在 —— HostTest 拿 `test/fixtures/api/` 逐个核对。
 */
public final class PanelCatalog {

    private PanelCatalog() {
    }

    public static final class Panel {
        public final String title;
        public final String hint;
        public final String path;
        public final int depth;

        Panel(String title, String hint, String path, int depth) {
            this.title = title;
            this.hint = hint == null ? "" : hint;
            this.path = path;
            this.depth = depth;
        }
    }

    public static final List<Panel> PANELS = new ArrayList<Panel>();

    static {
        PANELS.add(new Panel("子代理任务", "运行中 / 已完成的子代理。终端里是 /tasks。",
                "/tasks", 1));
        PANELS.add(new Panel("天契（任务契约）", "当前任务契约。终端里是 /mission。",
                "/missions", 2));
        PANELS.add(new Panel("MCP 服务器", "外部工具接入状态。终端里是 /mcp。",
                "/mcp/status", 2));
        PANELS.add(new Panel("MCP 预设", "可一键接入的 MCP。",
                "/mcp/presets", 1));
        PANELS.add(new Panel("插件", "已安装的插件。终端里是 /plugin。",
                "/plugins/installed", 1));
        PANELS.add(new Panel("插件预设", "可安装的插件。",
                "/plugins/presets", 1));
        PANELS.add(new Panel("缓存用量", "token 消耗 / 命中率 / 省钱。终端里是 /cache。",
                "/cache/usage", 2));
        PANELS.add(new Panel("存储", "会话占用与归档。",
                "/storage", 1));
        PANELS.add(new Panel("工作树", "git worktree 列表。",
                "/worktrees", 1));
        PANELS.add(new Panel("计划任务", "定时任务。",
                "/schedule", 1));
        PANELS.add(new Panel("暂存区", "scratch 目录占用。",
                "/scratch", 1));
        PANELS.add(new Panel("工具面", "被关掉的工具。终端里是 /tools。",
                "/tools/disabled", 1));
        PANELS.add(new Panel("项目授信", "项目级 hooks / 安全键合并状态。终端里是 /trust。",
                "/project/trust", 1));
        PANELS.add(new Panel("已授信项目", "",
                "/project/trust/list", 1));
        PANELS.add(new Panel("远端访问", "监听地址与局域网入口。",
                "/remote/info", 1));
        PANELS.add(new Panel("语音模型", "",
                "/speech/model/status", 1));
        PANELS.add(new Panel("运行时状态", "版本 / 内存 / 运行时长。终端里是 /status。",
                "/status", 1));
        PANELS.add(new Panel("运行时健康", "",
                "/health", 1));
        // ---- harness 3.28.0 对账补缺（2026-10-06②）----
        // 逐条在**运行中的 3.28.0 serve** 上实测过 200，并抓了夹具（声明即有夹具，见 HostTest：
        // 「ConsoleCatalog 里声明的每个路径都必须有夹具」——夹具 = 那条路由真返回过）。
        // ⚠ 刻意**不**收 `/git/graph`：README 记过它会让整个 Runtime 进程退出（真复现过）。
        PANELS.add(new Panel("使用画像", "登录时长 / 仓库 / token 用量。终端里是 /profile。",
                "/profile/overview", 2));
        PANELS.add(new Panel("星域用量", "近 30 天各星域跑了多少轮。",
                "/profile/domain-usage", 2));
        PANELS.add(new Panel("调度器", "定时任务调度状态与锁归属。",
                "/schedule/status", 2));
        PANELS.add(new Panel("技能清单", "已装技能与描述。终端里是 /skill list。",
                "/skills", 1));
        PANELS.add(new Panel("GitHub PR", "PR 列表（需要 gh CLI；没装就是空表）。",
                "/github/prs", 1));
        PANELS.add(new Panel("仓库改动", "工作树改动预览（不在 git 仓库里时 isRepo=false）。",
                "/git/working-tree", 2));
    }

    public static List<String> allPaths() {
        List<String> out = new ArrayList<String>();
        for (Panel p : PANELS) out.add(p.path);
        return out;
    }
}
