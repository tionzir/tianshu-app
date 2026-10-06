package dev.tianshu.host;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

/**
 * 首次配置页 —— **密钥不再随安装包走**。
 *
 * ## 为什么要这么改
 * 在这之前，`assets/tianshu-config/` 把 `config.json` + `provider-keys.json` +
 * `secrets.json` + `.token-key` 四件套**直接打进 APK**，`build.sh` 自己都得在
 * 第 2b 步标一句「⚠ 含 API key，只能自用、勿分发」。那个包给不了第二个人。
 *
 * 现在包是干净的，密钥由用户在这个页面上给，两条路任选：
 *   ① **粘贴 API key** —— App 调 harness 自己的 `rivet config set-key`，
 *      **加密与落盘全由 harness 负责**，App 不实现、不接触密钥格式（这条是实测出来的：
 *      在 rootfs 里跑 `rivet config set-key deepseek sk-probe-000` → `✔ API key set`，
 *      exit=0，非交互）。
 *   ② **从共享存储导入** —— 读 {@link #IMPORT_DIR} 下的四件套，走
 *      {@link ConfigInstaller.DirSource}（与 assets 注入同一套代码，只是数据源不同）。
 *
 * ## 为什么不做成底栏上的一个 tab
 * 它是**配对页**不是功能页：配好之前，别的页面打开也是"未配置"。所以它没有底栏
 * （`NavBar.currentFor()` 对它返回 -1，本来也不会高亮），配完直接进对话页。
 */
public class SetupActivity extends BaseActivity {

    /** 从共享存储导入配置时读的目录。 */
    public static final String IMPORT_DIR = "/storage/emulated/0/tianshu-config";

    private TextView status;
    private RuntimeHost host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new RuntimeHost(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        main.setPadding(pad, pad, pad, pad);

        main.addView(AppKit.pageTitle(this, "配置天枢"));

        TextView sub = new TextView(this);
        sub.setText("密钥不再随安装包走 —— 它只该在你自己手里。两种给法，任选其一：");
        sub.setTextSize(Ui.CAPTION);
        sub.setLineSpacing(0f, 1.35f);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S4));
        Theming.tag(sub, Theming.ROLE_MUTED);
        main.addView(sub);

        LinearLayout card = Kit.bareCard(this);
        card.addView(Kit.menuRow(this, Icon.GEAR, "粘贴 API key",
                "填服务商的 key（如 deepseek），加密与落盘交给天枢自己做",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askProvider();
                    }
                }));
        card.addView(Kit.divider(this, 56));
        card.addView(Kit.menuRow(this, Icon.DOC, "从共享存储导入",
                "读 " + IMPORT_DIR + " 里的配置四件套",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importFromStorage();
                    }
                }));
        main.addView(card);

        status = new TextView(this);
        status.setTextSize(Ui.LABEL);
        status.setLineSpacing(0f, 1.3f);
        status.setPadding(0, Theming.dp(this, Ui.S3), 0, 0);
        Theming.tag(status, Theming.ROLE_MUTED);
        main.addView(status);

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);
    }

    private void say(String line) {
        status.setText(line);
    }

    // ---------------------------------------------------------------- ① 粘贴 key

    private void askProvider() {
        ActionSheet.promptText(this, "服务商名", "deepseek", "provider",
                new ActionSheet.OnSubmit() {
                    @Override
                    public void submit(String provider) {
                        if (provider == null || provider.trim().isEmpty()) return;
                        askKey(provider.trim());
                    }
                });
    }

    private void askKey(final String provider) {
        ActionSheet.promptText(this, provider + " 的 API key", "", "key",
                new ActionSheet.OnSubmit() {
                    @Override
                    public void submit(String key) {
                        if (key == null || key.trim().isEmpty()) return;
                        applyKey(provider, key.trim());
                    }
                });
    }

    private void applyKey(final String provider, final String key) {
        say("正在写入（天枢自己加密）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = GuestCli.run(host, SetupScript.configureProvider(provider, key));
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        finishSetup(error);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- ② 导入配置

    private void importFromStorage() {
        final File dir = new File(IMPORT_DIR);
        if (!dir.isDirectory()) {
            say("没找到 " + IMPORT_DIR + "\n把那四个文件放进去再回来：\n"
                    + "config.json · provider-keys.json · secrets.json · .token-key");
            return;
        }
        say("正在导入…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    File rivetHome = new File(host.rootfsDir(), "root/.rivet");
                    java.util.List<String> written = ConfigInstaller.install(
                            new ConfigInstaller.DirSource(dir), rivetHome, new AndroidSink());
                    if (written.isEmpty()) {
                        error = "那个目录里一个配置文件都没有（四个都缺）。";
                    }
                } catch (IOException e) {
                    error = "导入失败：" + e;
                } catch (Throwable t) {
                    error = "导入失败：" + t;
                }
                final String err = error;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        finishSetup(err);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 收尾

    /** 配好了就重启运行环境（serve 只在启动时读一次 RIVET_HOME）并进对话页。 */
    private void finishSetup(String error) {
        if (error != null) {
            say(error + "\n\n可以重试，或者换另一种方式。");
            return;
        }
        say("配置好了，正在重启运行环境…");
        // ⚠️ 重启这一步**整体放后台线程**：里面的 waitGone(2s) + exitedWithin(0.6s) 合计最长
        //    2.6 秒，而本方法是从 postUi(...) 回主线程后调起的 —— 留在主线程就是一次明显的
        //    卡顿（与下面 waitReady 同一个道理，只是短些）。UI 更新一律 postUi 回主线程。
        new Thread(new Runnable() {
            @Override
            public void run() {
                restartServeOnWorker();
            }
        }, "setup-restart-serve").start();
    }

    /**
     * 重启 serve 并等它就绪 —— **跑在后台线程上**（入口见 {@link #finishSetup}）。
     *
     * 三步：① 收掉旧的，并等它真的退出（不等的话新进程会撞 EADDRINUSE 秒退）；
     *       ② 起新的，立刻问一句"它是不是秒退了"；③ 等 `/health` 就绪，再回主线程跳页。
     */
    private void restartServeOnWorker() {
        try {
            Process old = AppRuntime.serveProcess();
            AppRuntime.stopServe();
            RuntimeHost.waitGone(old, 2000);
            Process p = RuntimeHost.launchServe(host);

            // ⚠️ 早退**不等于**"端口上挂着残留 serve"—— 也可能只是**旧进程还没把端口放开**
            //（destroy 之后收尾慢了一拍）。这两种情况的处置完全不同：前者今晚都不会自己好，
            // 后者再等一下就成。所以别急着下结论 —— 先等一拍、再起一次。
            if (RuntimeHost.exitedWithin(p, 600)) {
                Thread.sleep(1500);
                p = RuntimeHost.launchServe(host);
            }

            // ⚠️ 两次都秒退，才判成"真被占着"。判据要问这一句的整个理由：
            //
            // 18799 被一个**残留的** serve 占着时（App 上次被杀、那条 proot 还活着），新进程会
            // 以 EADDRINUSE 秒退，而旧进程照旧答 `/health` 200。少判这一句就会走成**假就绪**：
            // 后面的 waitReady 看到的是**旧进程**，界面报"配好了"，而刚落盘的密钥根本没生效
            //（serve 只在启动时读一次 RIVET_HOME）。
            //
            // 主启动流程（MainActivity.startPersistentServe）与模型页换密钥（ModelsActivity.writeKey）
            // 都有这道自检，唯独这里漏了 —— 见 RuntimeHost.exitedWithin 的注释。
            if (RuntimeHost.exitedWithin(p, 600)) {
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        say("运行环境没起来：18799 端口上还挂着一个**更早的** serve（多半是上次 App 被杀后"
                                + "残留的），它仍在用旧配置。\n"
                                + "刚写的密钥已经落盘、没有丢 —— 但要让新配置生效，得先把那个旧进程清掉：\n"
                                + "把手机重启一次（或在系统设置里「强制停止」天枢），再打开 App 就会用上新密钥。\n"
                                + "现场：serve 自己的日志在 <rootfs>/tmp/host-serve.log —— 设置页「导出到文件」"
                                + "会把它一起带上。");
                    }
                });
                return;
            }
            AppRuntime.holdServe(p);
        } catch (Throwable t) {
            // launchServe 除了 IOException 还可能冒出别的（路径取不到、进程建不出…）。
            // 注意这一步发生在**密钥已经写好之后**：真出意外也不能崩 ——
            // 崩掉只会让用户以为白填了（真机表现正是"闪退，再进去却好了"）。
            final Throwable cause = t;
            postUi(new Runnable() {
                @Override
                public void run() {
                    say("重启运行环境失败：" + cause);
                }
            });
            return;
        }

        // **必须等它就绪再跳**：上面那句 stopServe() 会把 AppRuntime 的 ready 标志
        // 清掉，而对话页渲染时只看那个标志 —— 不等就跳过去，用户看到的是
        // 「运行环境还没准备好」外加发送键变灰（真机踩过）。
        //
        // 这一段最长 45 秒，本来就必须在后台线程上等（本方法已在后台线程）。
        // say() 碰的是 TextView，所以即使已经在线程上，回主线程这一步也不能省。
        postUi(new Runnable() {
            @Override
            public void run() {
                say("等它就绪…（最多 45 秒）");
            }
        });
        final boolean ready = ServeHealth.waitReady(45);
        postUi(new Runnable() {
            @Override
            public void run() {
                afterReady(ready);
            }
        });
    }

    /** 等出结果之后：就绪就进对话页，没就绪就说清是"还在起"。 */
    private void afterReady(boolean ready) {
        if (!ready) {
            // 顺带修：原先这句写的是"用上方「导出」把日志带出来"，可**本页压根没有导出按钮**
            //（导出在「设置」页，见 SettingsActivity 的日志一节）—— 把用户指去一个不存在的入口。
            say("等了 45 秒还没就绪 —— 可稍后回对话页重试；要看现场就去「设置」页点「导出到文件」，"
                    + "日志会落到共享存储，外面直接读得到。");
            return;
        }
        startActivity(new Intent(this, ChatActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }
}
