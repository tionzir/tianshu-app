package dev.tianshu.host;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 模型页 —— 终端里 `/connect`、`/model` 在 App 里的落点。
 *
 * 为什么必须单独一页：`/connect` 在终端是**交互式命令**（选服务商 → 填密钥 → 选模型），
 * 由 TUI 本地拦截执行；而 App 走的是 HTTP API，**没有"执行斜杠命令"这个入口**
 * （268 条候选路由里搜 command/palette/slash/execute 命中 0）—— 所以那条命令发出去
 * 只会变成一句普通对话。底层能力其实是暴露的，这一页就是把它接出来：
 *
 *   GET /config/providers       → 服务商与模型（密钥只显示服务端掩码后的 ref）
 *   GET /config/default-model   → 当前默认（真机上可以是 null = 跟随服务商默认）
 *   PUT /config/default-model   → {"defaultModel":"provider:modelId"}
 *
 * 形状与写入 schema 都来自真机 API（fixtures/api/），不是猜的。
 *
 * ## 写密钥为什么不走 HTTP（2026-10-06④ 更正）
 * 上面那句「harness 没有写密钥的 HTTP 端点」**是 3.27 时代的老结论，现在不成立了**。
 * 在运行中的 **3.28.0** 上实测：
 * <pre>
 *   POST /config/providers/:name/keys            → 400 {"error":"apiKey is required"}   ← 路由**在**
 *   PUT  /config/providers/:name/keys/:keyId/key → 400 {"error":"apiKey, apiKeyEnv or label is required"}
 * </pre>
 * （400 = 参数校验失败，不是 404 —— 拿空 body 探的。）
 *
 * **但本页仍然走 CLI**（{@link GuestCli} + `rivet config set-key`）：那条路是**真机端到端
 * 验过**的（写进去 → 重启 serve → `/health` 立刻 `ok:true`），而 HTTP 写密钥这条路
 * 还没在真机上验过「写完 keyStatus 真的变了」。**换路要先在设备上验，不是看路由存在就切**
 *（本项目踩过"路由存在 ≠ 行为对"的坑）。留作后续可选简化。
 *
 * 与首次配置页（{@link SetupActivity}）同一条路、同一份实现。
 *
 * 在这之前，换 key 的入口**只存在于首次配置页**，而那一页只在"还没配好"时才会出现
 *（{@code MainActivity} 按 {@code ConfigInstaller.hasConfig} 二选一）—— 配好之后
 * App 里就再没有地方能改它。用户问「为什么不可以改 key」问的就是这个缺口。
 */
public class ModelsActivity extends BaseActivity {

    private LinearLayout content;
    private TextView statusLine;
    private TextView currentLine;
    private List<Providers.Provider> providers;
    private String defaultModel;

    /**
     * 请求序号 —— 只认**最后发起**的那个请求的结果。
     *
     * 为什么需要：`load()` 连点重试、`pick()` 连点两个模型时都会并发，先发的慢响应后到
     * 就覆盖新的（2026-10-04 深查）。
     *
     * ⚠️ 2026-10-05 走查核出：这个字段当时**只声明、从未被读过** —— 上面那句"已修"是空头支票，
     * 竞态一直都在（`load()` 里连读两次接口、`pick()` 直接改 `defaultModel`）。现在 `load()`
     * 与 `pick()` 各自在发起时 `incrementAndGet()`、在每个 ok/fail 回调里比对。
     * 接线方式与 `SessionListActivity.reqGen` 一致 —— 那边一直是正确样例。
     */
    private final java.util.concurrent.atomic.AtomicInteger reqGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        main.setPadding(pad, pad, pad, 0);

        // 页头：二级页给一个明确的返回口，别让人只能靠系统返回键
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View title = AppKit.pageTitle(this, "模型");
        header.addView(title);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        // 返回口与其它页头统一成**圆钮**（`‹` U+2039 实测 74 个字体覆盖）——
        // 六个页头刚统一成「纯标题 + 圆钮」，这一页不能是例外。
        header.addView(Kit.roundButton(this, Icon.BACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        main.addView(header);

        statusLine = new TextView(this);
        statusLine.setTextSize(Ui.CAPTION);
        statusLine.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        Theming.tag(statusLine, Theming.ROLE_MUTED);
        main.addView(statusLine);

        currentLine = new TextView(this);
        currentLine.setTextSize(Ui.BODY);
        currentLine.setTextIsSelectable(true);
        int sp = Theming.dp(this, Ui.S4);
        currentLine.setPadding(sp, sp, sp, sp);
        Theming.tag(currentLine, Theming.ROLE_CARD);
        main.addView(currentLine);

        // 「怎么配置」——用户问的原话就是这句（「模型与服务商我怎么配置呢？」）。
        // 把能照做的三步写在这一屏上，而不是让他去猜 provider / modelId / key 分别在哪填。
        main.addView(AppKit.emptyState(this,
                "怎么配",
                "· 换默认模型：点下面任意一行 → 设为默认（对新会话生效）\n"
                        + "· 只影响当前会话：在对话页点最左边那枚模型 chip\n"
                        + "· 换密钥：每个服务商那一组里有「重新填写密钥」—— "
                        + "密钥由天枢自己加密存进设备，这里只显示掩码后的引用"));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Theming.dp(this, Ui.STACK);
        main.addView(sv, slp);

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);

        load();
    }

    // ---------------------------------------------------------------- 读

    private void load() {
        final int gen = reqGen.incrementAndGet();   // 本次请求的世代（见 reqGen 注释）
        status("读取中…");
        showLoading();
        RuntimeApi.get("/config/providers", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                if (gen != reqGen.get()) return;    // 慢响应迟到：别覆盖更新的那一份
                providers = Providers.parse(body);
                // 默认模型是"可以没有"的：真机上 defaultModel 就是 null（跟随服务商默认），
                // 所以它读失败不该让整页失败 —— 继续渲染，只是标题少一条信息。
                RuntimeApi.get("/config/default-model", new RuntimeApi.Cb() {
                    @Override
                    public void ok(String b2) {
                        if (gen != reqGen.get()) return;
                        defaultModel = Providers.defaultModel(b2);
                        render();
                    }

                    @Override
                    public void fail(String m) {
                        if (gen != reqGen.get()) return;
                        defaultModel = null;
                        render();
                    }
                });
            }

            @Override
            public void fail(final String message) {
                if (gen != reqGen.get()) return;
                status("读不到服务商列表");
                content.removeAllViews();
                content.addView(AppKit.error(ModelsActivity.this, "读不到服务商列表",
                        message + "\nserve 可能还没就绪，稍等一两秒再试。",
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                load();          // 重试：失败多半是 serve 刚起、还没监听上
                            }
                        }));
                Theming.applyTree(ModelsActivity.this, content);
            }
        });
    }

    // ---------------------------------------------------------------- 画

    private void render() {
        if (providers == null) return;
        status("共 " + providers.size() + " 个服务商 —— 点任意模型即切为默认");
        currentLine.setText("当前模型：" + Providers.currentLabel(providers, defaultModel)
                + (defaultModel == null ? "（跟随服务商默认）" : ""));

        content.removeAllViews();
        if (providers.isEmpty()) {
            // 空态：原先是彻底空白（只在状态行写「共 0 个服务商」），用户不知道下一步干什么
            content.addView(AppKit.emptyState(this, "还没有已登记的服务商",
                    "服务商与密钥由「配置天枢」页给：粘贴 API key，或从共享存储导入配置四件套。"));
        } else {
            for (Providers.Provider p : providers) {
                content.addView(providerGroup(p));
            }
        }
        Theming.applyTree(this, content);
    }

    /**
     * 一个服务商 = 一个分组（{@link Kit.Group}）：组标题是服务商名，组里第一行是密钥/地址，
     * 之后每个模型一行 {@link Kit#choiceRow}。
     *
     * 2026-10-04 改：原先是自造的"卡片 + 名字 + 浅框行"，与 App 其它地方的列表语言不一路
     * —— 用户要的是"整体都改"，这里统一到 {@link Kit} 的行体系（间距、分隔线、按压都在那儿）。
     */
    private View providerGroup(final Providers.Provider p) {
        Kit.Group g = Kit.group(this, p.label + (p.isDefault ? "  （默认服务商）" : ""));
        g.row(providerMeta(p), 16);

        // 换密钥 —— 从前这件事**只有首次配置页**能做（配好之后 App 里再没入口）。
        // 代价写在副标题里、点之前就看得见：密钥写进 RIVET_HOME 后必须重启 serve
        // 才生效，正在进行的对话会被打断。
        g.row(Kit.menuRow(this, Icon.SHIELD, "重新填写密钥",
                "填完会重启运行环境 —— 正在进行的对话会中断", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askKey(p);
                    }
                }), 56);

        String[] cur = Providers.effectiveSelection(providers, defaultModel);
        if (p.models.isEmpty()) {
            g.row(Kit.infoRow(this, "（这个服务商没有已登记的模型）", ""), 16);
        }
        for (final Providers.Model m : p.models) {
            boolean on = cur != null && cur[0].equals(p.name) && cur[1].equals(m.id);
            g.row(Kit.choiceRow(this, m.id,
                            m.subtitle() + (m.desc.isEmpty() ? "" : " · " + m.desc), on,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    pick(p.name, m.id);
                                }
                            }),
                    16);
        }
        return g.root;
    }

    /** 服务商元信息行：密钥是否就绪 + 协议 + 地址（不可点）。 */
    private View providerMeta(Providers.Provider p) {
        TextView t = new TextView(this);
        String line = p.keyText() + (p.protocol.isEmpty() ? "" : " · " + p.protocol);
        if (!p.baseUrl.isEmpty()) line += "\n" + p.baseUrl;
        t.setText(line);
        t.setTextSize(Ui.CAPTION);
        t.setLineSpacing(0f, 1.3f);
        int hp = Theming.dp(this, Ui.S4);
        int vp = Theming.dp(this, 12);
        t.setPadding(hp, vp, hp, vp);
        Theming.tag(t, p.keyReady() ? Theming.ROLE_MUTED : Theming.ROLE_WARN);
        return t;
    }

    private void pick(final String provider, final String model) {
        String body = "{\"defaultModel\":\"" + provider + ":" + model + "\"}";
        status("切换中… " + provider + " · " + model);
        final int gen = reqGen.incrementAndGet();   // 与 load() 共用同一个世代：谁最后发起，谁说了算
        RuntimeApi.put("/config/default-model", body, new RuntimeApi.Cb() {
            @Override
            public void ok(String b) {
                if (gen != reqGen.get()) return;    // 期间又发起了别的请求：这份结果已经过期
                defaultModel = provider + ":" + model;
                render();
                Toast.makeText(ModelsActivity.this, "已切到 " + model, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void fail(String message) {
                if (gen != reqGen.get()) return;
                status("切换失败：" + message);
                Toast.makeText(ModelsActivity.this, "切换失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 弹输入框收 key（**不预填** —— 掩码 ref 不是密钥，预填只会让人以为那就是 key）。 */
    private void askKey(final Providers.Provider p) {
        ActionSheet.promptText(this, "重新填写 " + p.label + " 的 API key", "", "key",
                new ActionSheet.OnSubmit() {
                    @Override
                    public void submit(String key) {
                        if (key == null || key.trim().isEmpty()) {
                            status("没填 key —— 没有改动。");
                            return;
                        }
                        writeKey(p.name, key.trim());
                    }
                });
    }

    /**
     * 写密钥 → 重启 serve → 等就绪。三步全在后台线程（跑 CLI 与最长 45 秒的等待）。
     *
     * 为什么三步缺一不可（真机踩出来的，与首次配置页 {@link SetupActivity} 同一套）：
     *   1. **写** —— harness 自己的 `rivet config set-key`（走 {@link GuestCli}），
     *      加密与落盘全由它负责，App 不碰密钥格式；
     *   2. **重启** —— serve **只在启动时读一次** RIVET_HOME：不重启的话界面显示"已改"、
     *      实际还是旧 key，是**静默失效**；
     *   3. **等就绪** —— `stopServe()` 会把 ready 标志清掉，不等就回来渲染，用户看到的
     *      是"运行环境没准备好"。等待最长 45 秒，所以只能在后台线程（放 UI 线程就是 ANR）。
     *
     * 只调 `setKey`（不调 `configureProvider`）—— 改密钥**不该顺带把默认服务商换掉**，
     * 那是「模型」那一列按钮的职责，用户没点它就不该动。
     */
    private void writeKey(final String provider, final String key) {
        status("正在写入（天枢自己加密）…");
        final RuntimeHost host = new RuntimeHost(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = GuestCli.run(host, SetupScript.setKey(provider, key));
                if (error == null) {
                    try {
                        AppRuntime.stopServe();
                        Process p = RuntimeHost.launchServe(host);
                        // ⚠️ 端口被占时新进程会**立刻**以 EADDRINUSE 退出，而 `/health`
                        // 仍由另一个（更早启动、可能就是 App 上次被杀后残留的）serve 在答 ——
                        // 那时 waitReady 会给我们一个**假的"就绪"**：界面说改好了，
                        // 实际用的还是旧配置。所以先看它死没死（2026-10-04 的真机报告里
                        // 就有 `serve: ready=true alive=false` 这一幕）。
                        if (RuntimeHost.exitedWithin(p, 600)) {
                            error = "密钥已经写进去了，但 18799 端口被另一个天枢进程占着 ——"
                                    + " 新配置这次没能生效。把天枢从最近任务里划掉（结束那个进程），"
                                    + "再打开重试即可。";
                        } else {
                            AppRuntime.holdServe(p);
                        }
                    } catch (Throwable t) {
                        error = "密钥写进去了，但重启运行环境失败：" + t;
                    }
                }
                if (error == null && !ServeHealth.waitReady(45)) {
                    error = "密钥已写入，但运行环境 45 秒没起来 —— 可回设置页导出日志看看。";
                }
                // 「答了 /health」不等于「能干活」：没认出 key 时它照样返 200
                //（2026-10-05 在容器里用同源 rootfs 实测，体是 {"ok":false,...}）。
                // 不查这一下，用户看到的就是"配好了"，而下一句话必然失败。
                if (error == null && !ServeHealth.usable()) {
                    error = "密钥写进去了，但天枢没能用上它（服务起来了、发消息仍会失败）—— "
                            + "请确认 key 没抄错，或换个网络重试。";
                }
                final String err = error;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (err == null) {
                            status("密钥已更新 —— 重新读取服务商…");
                            Toast.makeText(ModelsActivity.this,
                                    "已更新 " + provider + " 的密钥", Toast.LENGTH_SHORT).show();
                            load();
                        } else {
                            status(err);
                        }
                    }
                });
            }
        }, "tianshu-setkey").start();
    }

    /** 加载态：清空列表区，摆上转圈 + 说明（数据回来前别留一片空白）。 */
    private void showLoading() {
        if (content == null) return;
        content.removeAllViews();
        content.addView(AppKit.loading(this, "读取服务商…"));
        Theming.applyTree(this, content);
    }

    private void status(final String s) {
        statusLine.setText(s);
    }
}
