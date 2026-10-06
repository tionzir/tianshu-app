package dev.tianshu.host;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 天枢独立 App —— 最小宿主。
 *
 * 这一版只做一件事：把"料"变成"活的进程"，并在屏幕上把每一步摊开，
 * 出问题时一眼能看出卡在哪一段。
 *
 *   1. 环境自检      —— nativeLibraryDir / proot / loader 是否都在
 *   2. 首次解压 rootfs —— assets/rootfs.tar.xz → filesDir/rootfs（原子可见）
 *   3. 起 proot      —— 在 rootfs 里跑 cat /etc/os-release / node -v / tianshu --version
 *   4. 起一次 serve  —— 短测 /health 与 /sessions（鉴权是否生效）
 *
 * 界面沿用 Gate 探针的教训：顶部一行结论、正文可选中、一键复制。
 */
public class MainActivity extends BaseActivity {

    private final StringBuilder log = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 启动态 ----
    private View rootView;            // 交棒时要整页淡出，所以得留个根
    private SplashBackdrop backdrop;  // 开屏背景（渐变 + 雪）—— 自绘
    private LinearLayout splashBox;
    private OrbitSpinner orbit;       // 自绘星轨（替掉系统 ProgressBar）
    private TextView rotateView;      // 转圈下面轮播的那句话
    private String lastRotate = "";
    private TextView statusView;      // 当前步骤（"2/4 准备运行环境"）
    private TextView detailView;      // 细节 + 已等待秒数
    private TextView failedView;      // 失败提示（默认隐藏）
    private LinearLayout stepsBox;    // 已走过的步骤清单（✓ / ●）
    /** 走过哪些步骤 —— 冷启动解压几分钟时，"走到哪一步"的唯一证据（见 renderSteps）。 */
    private final java.util.List<String> steps = new java.util.ArrayList<String>();

    // （就绪态入口页已移除 —— 启动完直接进对话页，见 showReady）

    // ---- 日志（默认收起：启动页上摊一大坨日志正是"太丑"的来源）----
    private Button logToggle;
    private ScrollView logScroll;
    private TextView logBody;
    /** 只在失败态露面的「导出日志」按钮（见 {@link #fail(String)}）。 */
    private Button logExport;

    private RuntimeHost host;
    private long lastPublish = 0;
    private long bootStartAt = 0;
    private String detailLeft = "";
    private boolean ready;

    /** 运行时权限请求码（任意非 0 值，只要回调里对得上）。 */
    private static final int REQ_STORAGE = 0x5701;
    private boolean bootStarted;

    /**
     * 本页已销毁 —— 心跳与交棒都要就此打住（见 {@link #onDestroy()}）。
     *
     * 为什么需要：boot 跑在后台线程，而它开头就 `postUi(ticker)`；若 onDestroy 先跑、
     * 那句 post 后到，ticker 仍会在一个已销毁的页面上开跑并每秒自续。
     *
     * 读写**都在主线程**（onDestroy 写；ticker 与 handOff 读，两者都是主线程 Handler 上的
     * Runnable）—— 所以 volatile 严格说不必需，留着是防御性的：将来若有人从 boot 线程
     * 来问"还在不在"，不至于踩可见性。
     */
    private volatile boolean destroyed;

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能刚在「外观」页被改过，而本页是从 back stack 复用回来的（不会重走
        // onCreate）—— 不在这里重贴，用户看到的就是「改了但没生效」，只能靠杀进程。
        Theming.refresh(this);
        tintSpinner();
        tintBackdrop();
    }

    /**
     * 接管了那些配置变更（见 manifest 的 configChanges），它们就**不会再走 onResume** ——
     * 外观的重贴得在这里补一次，否则旋转 / 切深色时开屏会留着旧配色。
     *
     * 换句话说：声明 configChanges 换来"页不被重建"（启动不被重复触发），代价就是
     * 原来挂在 onResume 上的事要在这里再做一遍。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Theming.refresh(this);
        tintSpinner();
        tintBackdrop();
    }

    /**
     * 摘掉心跳与背景动画 —— 否则它们会把**已销毁**的整个 Activity 吊住。
     *
     * 坑长这样：ticker 是主线程 Handler 上排着的 Runnable，每秒 `ui.postDelayed(this, 1000)`
     * 自续，只在 `ready` 变真时才停。而冷启动要解压约 600 MB、可能好几分钟 —— 用户中途
     * 按返回退出本页时 `ready` 还是 false，ticker 于是**一直**每秒去 `renderRotate` /
     * `renderDetail` 改已经销毁的视图树，整个 Activity 泄漏（Runnable 持有 this）。
     *
     * {@link ChatActivity} 早就在自己的 onDestroy 里这么做（摘掉 ticker），本页此前漏了。
     * 再配合 {@link #destroyed} 标志，堵住"onDestroy 之后后台线程才 post 出 ticker"那条缝。
     */
    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(ticker);
        if (backdrop != null) backdrop.stop();     // 停掉动画，别让一个不可见的 View 一直重绘
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = new RuntimeHost(this);

        // 自诊断先装上 —— 从这里往后**任何**崩溃都会被写进共享存储。设置页那个
        // 「导出到文件」要用户手动点，而闪退**根本来不及点**；装在本页是因为它是
        // launcher，而崩溃处理器是全局的、只装一次（Diagnostics.install 幂等）。
        Diagnostics.install(getApplicationContext());

        // 根容器用 FrameLayout（不是 LinearLayout）：开屏背景要**铺满全屏**，
        // 而内容区带 22dp 边距 —— 背景若与内容共用一个带 padding 的容器，
        // 四周会露出一圈没画到的底，正是"背景没铺满"那种廉价感。
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        backdrop = new SplashBackdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 内容层：启动态与就绪态互斥（GONE 的那个不占位），好了之后原地切换 ——
        // 不新开 Activity，back 键行为与以前一致。
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, 22);
        content.setPadding(pad, pad, pad, pad);

        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        splashBox = buildSplash();
        stage.addView(splashBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // 日志收起来：启动页上摊一大坨滚动日志就是"太丑"的来源。
        // 但它不能消失 —— 出问题时要能一眼看到、一键复制。
        logToggle = new Button(this);
        logToggle.setText("查看日志");
        Theming.tag(logToggle, Theming.ROLE_GHOST);
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLog();
            }
        });

        logBody = new TextView(this);
        logBody.setTextSize(Ui.LABEL);
        logBody.setTextIsSelectable(true);
        Theming.tag(logBody, Theming.ROLE_MUTED);

        // 「导出日志」默认藏着 —— 它只在**启动失败**时才露面。启动页摊两个按钮同样
        // 属于"太丑"，而它要服务的场景只有一个：没起来的时候，把日志落到共享存储，
        // 让外面的人（不用 adb、够不着私有目录）也能直接读到。
        logExport = new Button(this);
        logExport.setText("导出日志到文件");
        Theming.tag(logExport, Theming.ROLE_GHOST);
        logExport.setVisibility(View.GONE);
        logExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogAndSay();
            }
        });

        logScroll = new ScrollView(this);
        logScroll.addView(logBody);
        logScroll.setVisibility(View.GONE);

        // 两个 weight=1：日志收起时舞台占满，展开时上下平分
        content.addView(stage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(logToggle);
        content.addView(logExport);
        content.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        Theming.apply(this, root);
        tintSpinner();
        tintBackdrop();

        // 存储权限必须先问：boot 里 buildArgv() 要按"读不读得到共享存储"决定挂什么，
        // 而权限对话框是异步的 —— 先问再 boot，否则 bind 永远落在"没权限"那一支。
        if (ensureStoragePermission()) {
            startBoot();
        }
    }

    /**
     * 存储权限。要读用户放进共享存储的代码就必须要，而且**必须配合 targetSdk ≤ 29**：
     * Android 11 起 targetSdk ≥ 30 的 App 走分区存储，READ/WRITE_EXTERNAL_STORAGE
     * 对非媒体文件不再给路径级访问（见 AndroidManifest.xml 里的说明）。
     *
     * @return true = 已有权限（或系统 < M，无需运行时申请）
     */
    private boolean ensureStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_STORAGE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        say("");
        say("=== 存储权限结果 ===");
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            say("  " + permissions[i] + " -> "
                    + (grantResults[i] == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        }
        say("  拒绝也能跑：工作区会退到 App 自己的 Android/media 目录（无需权限）");
        startBoot();
    }

    /** boot 只跑一次 —— 权限回调可能和已有权限的路径撞上。 */
    private void startBoot() {
        if (bootStarted) return;
        bootStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boot();
            }
        }, "tianshu-boot");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 主流程
    private void boot() {
        bootStartAt = System.currentTimeMillis();
        postUi(ticker);
        try {
            step("1/4", "环境自检");
            say(host.describeEnvironment());
            publish();

            if (!host.prootBinary().exists()) {
                fail("libproot.so 不在 nativeLibraryDir");
                return;
            }
            if (!host.prootLoader().exists()) {
                fail("loader 缺失（PROOT_LOADER 将指向空文件）");
                return;
            }

            boolean firstRun = !host.rootfsDir().isDirectory();
            step("2/4", firstRun ? "首次启动：正在解压运行环境" : "准备运行环境");
            setDetail(firstRun ? "首次启动要解压约 600 MB，请耐心等一会儿" : "");
            long t0 = System.currentTimeMillis();
            RootfsInstaller.Result r;
            String fp = rootfsFingerprint();
            say("断点续传指纹：" + (fp == null
                    ? "拿不到 → 失败就整体重来（不冒险续装）"
                    : fp + "（上次的缺口可以接着装）"));
            // 空间预检（2026-10-04 深查）：解压后约是压缩包的 6 倍（实测 108MB → 624MB），
            // 留两成余量。不预检的话会装到一半才 ENOSPC —— 用户拿到的是半装 staging +
            // 一句看不懂的英文错，而且重试还是失败。
            //
            // ⚠️ 只在 **firstRun** 时判。rootfs 已经装好时，RootfsInstaller.install 第一行就
            // 因 `destDir.isDirectory()` 直接返回、**根本不读流** —— 那时还拿"要解压 600 MB"
            // 去比空间，就会在磁盘偏低时把一次**不需要任何空间**的热启动判成"存储空间不足"
            // 并拒绝启动（2026-10-05 走查核出）。
            if (firstRun) {
                long needBytes = rootfsNeedBytes();
                long freeBytes = getFilesDir().getUsableSpace();
                if (needBytes > 0 && freeBytes > 0 && freeBytes < needBytes) {
                    fail("存储空间不足：解压运行环境约需 " + (needBytes >> 20) + " MB，现在只剩 "
                            + (freeBytes >> 20) + " MB。清出空间后重新打开 App 就能继续");
                    return;
                }
            }

            InputStream asset = getAssets().open("rootfs.tar.xz");            try {
                r = RootfsInstaller.install(asset, host.rootfsDir(), host.stagingDir(),
                        new AndroidSink(), new RootfsInstaller.Progress() {
                            @Override
                            public void onEntry(String name, long filesDone, long bytesDone) {
                                long now = System.currentTimeMillis();
                                if (now - lastPublish < 500) return;
                                lastPublish = now;
                                setDetail("已解压 " + (bytesDone / (1024 * 1024)) + " MB · "
                                        + filesDone + " 个文件");
                            }
                        }, fp);
            } finally {
                asset.close();
            }
            long secs = (System.currentTimeMillis() - t0) / 1000;
            if (r.installed) {
                say("解压完成：" + r.files + " 个文件 / " + (r.bytes / (1024 * 1024))
                        + " MB，用时 " + secs + "s"
                        + (r.resumedFrom > 0 ? "（从第 " + r.resumedFrom + " 个条目接着装）" : ""));
            } else {
                say("rootfs 已存在，跳过解压（幂等）");
            }
            writeResolvConf();
            installConfigFromAssets();
            int mountDirs = host.prepareGuestMountPoints(host.sharedStorageHost() != null);
            say(mountDirs < 0
                    ? "guest 挂载点预建：跳过（rootfs 还没就位）"
                    : "guest 挂载点预建 " + mountDirs + " 个目录 —— 让 bind 不依赖 proot 的 glue rootfs");
            publish();

            // ---- 3/4 起 serve、4/4 等它就绪：这两步才是"让 App 能用"的必要动作 ----
            step("3/4", "起常驻 serve");
            startPersistentServe();

            step("4/4", "等它就绪");
            long t4 = System.currentTimeMillis();
            boolean up = waitForServe(SERVE_WAIT_SEC);
            say("等 serve 就绪：" + (up ? "通了" : "超时") + "，用了 "
                    + ((System.currentTimeMillis() - t4) / 1000) + "s");
            publish();

            // ⚠️ 判据只能是 `up`（/health 真的答了）——**不能**再要求 `isServeAlive()`：
            // 跨进程时（App 被杀、旧 proot 还活着）静态引用是 null、`isServeAlive()` 恒为 false，
            // 于是明明能用的 serve 会被判成"没起来"，退进下面那条慢诊断路径。2026-10-04 深查发现。
            if (up) {
                AppRuntime.markServeReady();
                say("");
                say("启动完成 —— 界面连 " + AppRuntime.baseUrl());
                // serve 能在 rootfs 里跑起来，本身就证明了 proot / node / tianshu / 工作区 bind
                // 都是好的 —— 所以下面那两段诊断在正常路径上**没有存在的必要**。
                // 它们曾经每次都跑：多起两个 proot、curl 一次外网、还**真发一轮"2+2"对话**
                // 再硬等 30 秒 —— 那就是"每次启动要等半分钟"的全部来源。
                say("（正常启动不再多起两个 proot，也不再每次真发一轮测试对话）");
                showReady();
                return;
            }

            // ---- 只有没起来才跑诊断：排查能力一点没少，但不再让每次都付这个钱 ----
            say("");
            say("!! 常驻 serve 没起来 —— 跑一次完整诊断（正常启动不走这里）");

            step("诊断 1", "rootfs 里到底有什么");
            String out3 = runProot(
                    "cat /etc/os-release | head -2; echo ---; "
                            + "ls -ld /bin /usr/bin | head -2; echo ---; "
                            + "node -v; echo ---; tianshu --version; echo ---; "
                            // 工作区就是这次改动的全部意义：/root 里到底看不看得见用户的代码。
                            // 下面每一行都是真机截图里能一眼判读的观察点，不靠"应该没问题"。
                            + "echo '--- 工作区 ---'; "
                            + "echo -n '/root/workspace -> '; ls -ld /root/workspace 2>&1; "
                            + "echo -n '  里面有 '; ls /root/workspace 2>/dev/null | wc -l; echo ' 项'; "
                            + "echo '  前 5 项：'; ls /root/workspace 2>&1 | head -5 | sed 's/^/    /'; "
                            // 条目数必须打：挂载点预建之后，"bind 被丢掉"表现成**空目录**而不是报错，
                            // 0 项与真有内容长得不一样，一眼可判。
                            + "echo '--- 共享存储挂载点（条目数 / 前 3 项）---'; "
                            + "for d in /mnt/sdcard /sdcard /storage/emulated/0; do "
                            + "  n=$(ls -A \"$d\" 2>/dev/null | wc -l); "
                            + "  echo \"  $d -> ${n} 项: $(ls \"$d\" 2>&1 | head -3 | tr '\\n' ' ')\"; done; "
                            + "echo '--- /root 顶层（天枢一睁眼看到的）---'; ls -a /root",
                    180);
            say(out3);
            publish();

            step("诊断 2", "serve 到底起没起");

            String out4 = runProot(
                    "export PATH=/usr/local/bin:/usr/bin:/bin; "
                            + "export RIVET_SERVER_TOKEN=" + AppRuntime.TOKEN + "; "
                            + "EP=http://127.0.0.1:" + AppRuntime.PORT + "; "
                            + "echo '--- 等常驻 serve 就绪 ---'; "
                            + "ok=no; i=0; while [ $i -lt 90 ]; do i=$((i+1)); sleep 1; "
                            + "  curl -s -o /dev/null $EP/health && { ok=yes; break; }; done; "
                            + "echo \"ready=$ok after=${i}s\"; "
                            + "echo '--- 网络自检 ---'; "
                            + "curl -sS -o /dev/null -w '外网 nodejs.org -> %{http_code}\\n' --max-time 10 https://nodejs.org/ 2>&1; "
                            // 注入结果一眼可判：.token-key 少那个点就解不开密文
                            + "echo '--- rootfs 里的 ~/.rivet ---'; "
                            + "ls -la /root/.rivet/ 2>&1 | grep -vE '^\\.|total' | head -12; "
                            + "echo -n 'health(token): '; "
                            + "curl -s -w ' [%{http_code}]' -H \"Authorization: Bearer $RIVET_SERVER_TOKEN\" $EP/health; echo; "
                            + "echo -n 'sessions(no token): '; curl -s -o /dev/null -w '%{http_code}' $EP/sessions; echo; "
                            + "echo '--- 常驻 serve 的日志（前 12 行）---'; head -12 /tmp/host-serve.log 2>&1; "
                            // 真发一轮：POST /sessions 只创建会话（201 + id），回答在后台跑，
                            // 这里轮询 lastSeq/currentPhase 看它到底有没有动起来。
                            + "echo; echo '=== 真发一轮对话 ==='; "
                            + "SID=$(curl -s -X POST -H \"Authorization: Bearer $RIVET_SERVER_TOKEN\" "
                            + "  -H 'Content-Type: application/json' "
                            + "  -d '{\"prompt\":\"用一句话回答：2+2 等于几？\"}' --max-time 60 $EP/sessions "
                            + "  | sed -n 's/.*\"id\":\"\\([^\"]*\\)\".*/\\1/p'); "
                            + "echo \"session id = $SID\"; "
                            + "sleep 30; "
                            + "echo '--- 30s 后 GET /sessions/$SID（看 lastSeq 有没有涨）---'; "
                            + "curl -s -H \"Authorization: Bearer $RIVET_SERVER_TOKEN\" --max-time 30 "
                            + "  \"$EP/sessions/$SID\" | head -c 900; echo; "
                            + "echo done",
                    420);
            say(out4);
            publish();

            AppRuntime.markError("常驻 serve 未响应");
            say("!! 常驻 serve 自始至终没起来（proot 带 --kill-on-exit，退一次就全没）");
            fail("常驻 serve 没起来（诊断已展开在日志里）");
        } catch (Throwable t) {
            say("!! 异常：" + t);
            say(stack(t));
            publish();
            fail(t.getClass().getSimpleName() + "：" + t);
        }
    }

    /**
     * 往 rootfs 写 /etc/resolv.conf。
     *
     * 打包时把它删掉了（保持镜像干净），而容器里我们一直靠 proot 的
     * -b /etc/resolv.conf 绕过 —— 独立 App 里没有这个 bind，
     * rootfs 内任何域名解析都会失败或长时间挂起，正是 serve 起不来的嫌疑之一。
     */
    /**
     * 续传指纹 = versionCode + asset 字节数。
     *
     * 为什么必须要有：只有「同一份 tar」才允许接着上次没装完的 staging 继续装。
     * 换了包还接着用旧 staging 会得到一个**新旧混杂**的 rootfs —— 那比重新装一遍糟得多，
     * 而且坏得静默。
     *
     * asset 是未压缩存进 APK 的（build.sh 用 --no-compress-regex 放行 .xz），
     * 所以 openFd() 拿得到长度；万一拿不到就返回 null，退回「失败整体重来」，不冒险。
     */
    /**
     * 解压这份 rootfs 大约需要多少字节。
     *
     * 按压缩包的 **7.2 倍**估：实测 108MB 的 xz → 624MB 落盘，比值约 5.8，再留两成余量。
     * 取不到 asset 长度时返回 0 = **不预检**（宁可不拦，也不能因为估不出来就把启动挡住）。
     */
    private long rootfsNeedBytes() {
        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd("rootfs.tar.xz");
            long len = fd.getLength();
            fd.close();
            return Math.round(len * 7.2);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String rootfsFingerprint() {
        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd("rootfs.tar.xz");
            long len = fd.getLength();
            fd.close();
            int code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            return code + ":" + len;
        } catch (Throwable t) {
            say("  （读 asset 指纹失败：" + t.getClass().getSimpleName() + " → 本次不续传）");
            return null;
        }
    }

    private void writeResolvConf() {
        File etc = new File(host.rootfsDir(), "etc");
        if (!etc.isDirectory()) return;

        java.util.List<String> servers = new java.util.ArrayList<String>();
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network n = cm.getActiveNetwork();
                android.net.LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        if (a != null) servers.add(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            say("  （读系统 DNS 失败：" + t.getClass().getSimpleName() + "，改兜底）");
        }
        if (servers.isEmpty()) {
            servers.add("8.8.8.8");
            servers.add("1.1.1.1");
        }

        StringBuilder sb = new StringBuilder();
        for (String s : servers) sb.append("nameserver ").append(s).append('\n');
        try {
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(etc, "resolv.conf"));
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            say("resolv.conf -> " + servers);
        } catch (IOException e) {
            say("  （写 resolv.conf 失败：" + e + "）");
        }
    }

    /**
     * 把随包携带的配置铺进 rootfs 的 `~/.rivet`。
     *
     * 四个文件缺一不可，其中 `.token-key` 是最容易漏的 ——
     * 它是解 `secrets.json` 的 local-key，少了它 harness 会新生成一个随机的，
     * 旧密文永远解不开，serve 就一直停在 setup mode。
     *
     * 每次都覆盖写，所以改了配置重装一次即可生效。
     */
    private void installConfigFromAssets() {
        final android.content.res.AssetManager am = getAssets();
        ConfigInstaller.Source src = new ConfigInstaller.Source() {
            @Override
            public String[] names() {
                // 目标名来自显式清单，**不要用 am.list() 的结果** ——
                // 那里返回的是包内真名（.token-key 在包里叫 token-key），
                // 拿它当目标名会写成 ~/.rivet/token-key，而 harness 找的是 .token-key。
                //
                // 2026-10-04：包**不再携带**配置（密钥只在用户手里，见 SetupActivity），
                // 所以先探一下包内到底有没有这个目录：没有就返回空表，让 install 干净地
                // 返回空 —— 否则 am.open 抛 FileNotFoundException，被下面 catch 成一句
                // 「配置注入失败」，把"本该没有"误导成"出错了"（真机日志里就是这么写的）。
                try {
                    String[] entries = am.list("tianshu-config");
                    if (entries == null || entries.length == 0) return new String[0];
                } catch (IOException e) {
                    return new String[0];
                }
                return ConfigInstaller.CONFIG_FILES.toArray(new String[0]);
            }

            @Override
            public InputStream open(String targetName) throws IOException {
                return am.open("tianshu-config/" + ConfigInstaller.assetNameFor(targetName));
            }
        };
        File rivetHome = new File(host.rootfsDir(), "root/.rivet");
        try {
            java.util.List<String> written =
                    ConfigInstaller.install(src, rivetHome, new AndroidSink());
            if (written.isEmpty()) {
                say("配置注入：包内没有 tianshu-config/ —— 预期如此（密钥由用户在配对页给）");
            } else {
                say("配置注入 -> " + written);
            }
        } catch (IOException e) {
            say("  （配置注入失败：" + e + "）");
        }
    }

    /**
     * 起一个**常驻**的 proot 进程来承载 serve。
     *
     * 三个「不能」：
     *   - 不能 waitFor —— 那会把启动流程卡死在这儿
     *   - 不能 destroy / kill —— 界面还要连它
     *   - 更不能让承载它的 proot 退出 —— proot 带 `--kill-on-exit`，
     *     它一退，serve 连同所有子孙一起没
     *
     * 所以这里只是 start() 然后把 Process 交给 AppRuntime 一直拿着。
     */
    private void startPersistentServe() {
        // 守护先挂上 —— 它活得比本页久（本页一跳进对话页就 finish），
        // 之后 serve 哪天崩了（如 `/git/graph` 让 Runtime 直接 exit(1)）它能自己拉起来。
        ServeWatch.start(this);

        // **先看有没有活的** —— 2026-10-04 深查发现：原先这里无条件 `pb.start()`，
        // 而"boot 只跑一次"靠的是 MainActivity 的**实例字段** `bootStarted`；Activity 一重建
        // （App 进程被杀后从桌面再打开）就失效 → 再起一个 proot+serve，覆盖 AppRuntime 里
        // 旧进程的引用，旧的那个从此没人管。
        //
        // 判据有两条，缺一不可：
        //   ① 同进程：静态引用还活着（isServeAlive）；
        //   ② 跨进程：App 被杀后静态引用没了，但旧的 proot 可能仍在 18799 上答 /health。
        if (AppRuntime.isServeAlive() || healthOnce()) {
            if (AppRuntime.isServeAlive() && !AppRuntime.isServeReady()) {
                // 活着但没就绪：多半是上一次留下的半死进程占着端口，先收掉再起
                say("旧 serve 还活着但未就绪 —— 先收掉再起");
                Process old = AppRuntime.serveProcess();
                AppRuntime.stopServe();
                RuntimeHost.waitGone(old, 2000);
            } else {
                say("常驻 serve 已经在跑 —— 跳过重启");
                return;
            }
        } else {
            AppRuntime.stopServe();   // 清掉死句柄，别留脏引用
        }

        try {
            // 起进程那段抽到了 RuntimeHost.launchServe —— 守护（ServeWatch）也要用它，
            // 而守护跑在后台线程、够不到本 Activity。
            Process p = RuntimeHost.launchServe(host);

            // 端口被占时新进程会**立刻**退出（serve 自己把 EADDRINUSE 写进日志）。
            // 真机日志（2026-10-04）见过这一幕：上面 healthOnce() 那一拍旧 serve 恰好在忙、
            // 没答上来，于是这里又起了一个 —— 白起一次，日志里还多一行吓人的 EADDRINUSE。
            // 所以先短看一眼：真退出了就说明"已经有一个在跑"，别把死进程攥在手里。
            if (RuntimeHost.exitedWithin(p, 600)) {
                say("端口已被占用 —— 已经有一个 serve 在跑，沿用那个（没起新的）");
            } else {
                AppRuntime.holdServe(p);
                say("常驻 serve 已起：proot 进程由 App 持有，不 waitFor、不 kill");
                say("  日志将落在 <rootfs>/tmp/host-serve.log");
            }
        } catch (IOException e) {
            AppRuntime.markError("起常驻 serve 失败: " + e);
            say("!! 起常驻 serve 失败：" + e);
        }
    }

    // exitedWithin / waitGone 已移到 RuntimeHost —— 每个起 serve 的地方都要问
    // 「它是不是立刻就死了」，各写一份迟早分道（模型页换密钥那次就漏了）。

    // ---------------------------------------------------------------- 等 serve 就绪

    /**
     * 等 serve 起来的超时（秒）。
     *
     * 45 秒是给**冷启动**留的余量（rootfs 刚解开、node 首次加载）；热启动通常几秒就通，
     * 这个上限只在真出问题时才被摸到。
     */
    private static final int SERVE_WAIT_SEC = 45;

    /**
     * 从 App 侧直接探 `/health` —— 不再为了探一次而多起一个 proot。
     *
     * 原先这步是 `proot … bash -lc "curl …"`：多起一个 proot 本身就要好几秒，
     * 而那个脚本里还夹着外网自检（最多 10 秒）和 30 秒硬等。这里用 Java 直连
     * 127.0.0.1（见 {@link AppRuntime#baseUrl()}），每 500ms 一次，serve 一好就往下走。
     */
    private boolean waitForServe(int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        int tried = 0;
        while (System.currentTimeMillis() < deadline) {
            tried++;
            if (healthOnce()) {
                say("  /health 通了（第 " + tried + " 次探测）");
                return true;
            }
            if (!AppRuntime.isServeAlive()) {
                say("  serve 进程已退出（第 " + tried + " 次探测）");
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        say("  探了 " + tried + " 次仍未通");
        return false;
    }

    /** 打一次 /health。任何异常都当"还没好"，不打断等待。 */
    /** 打一次 /health。任何异常都当"还没好"，不打断等待。实现收在 {@link ServeHealth#ok()}。 */
    private boolean healthOnce() {
        return ServeHealth.ok();
    }

    // ---------------------------------------------------------------- proot
    private String runProot(String script, int timeoutSec) throws IOException {
        say("$ proot … /bin/bash -lc \"" + preview(script) + "\"");
        ProcessBuilder pb = host.newBuilder(script);
        final Process p = pb.start();

        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append("    ").append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被杀时读端会断，属正常
                }
            }
        }, "proot-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        boolean exited = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                exited = true;
                break;
            } catch (IllegalThreadStateException stillRunning) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!exited) {
            p.destroy();
            synchronized (out) {
                out.append("    [超时 ").append(timeoutSec).append("s，已杀]\n");
            }
        }
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String head = exited ? ("    exit=" + safeExit(p) + "\n") : "";
        synchronized (out) {
            return head + out;
        }
    }

    private static int safeExit(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static String preview(String s) {
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("    at ").append(e).append('\n');
            if (sb.length() > 1200) break;
        }
        Throwable c = t.getCause();
        if (c != null) sb.append("    caused by ").append(c).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- UI

    /** 启动态：居中标题 + 转圈 + **步骤清单** + 状态/细节 + 失败提示。 */
    private LinearLayout buildSplash() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("天枢");
        title.setTextSize(Ui.HERO);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        Theming.tag(title, Theming.ROLE_ACCENT);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("独立版");
        sub.setTextSize(Ui.CAPTION);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        Theming.tag(sub, Theming.ROLE_MUTED);
        box.addView(sub);

        orbit = new OrbitSpinner(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Theming.dp(this, 96), Theming.dp(this, 96));
        slp.topMargin = Theming.dp(this, Ui.S5);
        slp.bottomMargin = Theming.dp(this, Ui.S4);
        orbit.setLayoutParams(slp);
        box.addView(orbit);

        // 转圈下面第一行：轮播的三句话（每 6 秒一换）。头 6 秒看到的是
        // "首次启动可能需要一点时间，请耐心等待" —— 先让人知道要等，再谈别的。
        rotateView = new TextView(this);
        rotateView.setTextSize(Ui.BODY);
        rotateView.setGravity(Gravity.CENTER);
        rotateView.setText(StartupText.rotateAt(0));
        lastRotate = StartupText.rotateAt(0);
        box.addView(rotateView);

        // **步骤清单**（第三轮新加）：轮播是气氛、状态行是"现在在做什么"，
        // 而"总共几步、走到哪一步"原先**没有任何地方回答** —— 冷启动解压几分钟时，
        // 那一行「3/4 起常驻 serve」既不告诉你前面几步过没过，也不告诉你还剩几步。
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        stepsBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = Theming.dp(this, Ui.S4);
        stepsBox.setLayoutParams(stlp);
        box.addView(stepsBox);

        // 状态行：真正在做什么 —— 只说当下那一步，总进度交给上面的清单
        statusView = new TextView(this);
        statusView.setTextSize(Ui.CAPTION);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        Theming.tag(statusView, Theming.ROLE_MUTED);
        statusView.setText("正在准备运行环境…");
        box.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(Ui.CAPTION);
        detailView.setGravity(Gravity.CENTER);
        detailView.setPadding(0, Theming.dp(this, Ui.S1), 0, 0);
        Theming.tag(detailView, Theming.ROLE_MUTED);
        box.addView(detailView);

        failedView = new TextView(this);
        failedView.setTextSize(Ui.CAPTION);
        failedView.setGravity(Gravity.CENTER);
        failedView.setPadding(0, Theming.dp(this, Ui.S4), 0, 0);
        failedView.setVisibility(View.GONE);
        Theming.tag(failedView, Theming.ROLE_ERROR);
        box.addView(failedView);

        renderSteps();      // 若已经有走过的步骤（正常不会，防一手），立刻补上
        return box;
    }

    // 原先这里还有一个「就绪态」入口页（2×2 按钮 + 复制日志）。启动完直接进对话页之后，
    // 它被两侧取代了：入口 → 底部页面栏（NavBar），日志 → 设置页（读 BootLog）。
    // 死代码留着比删掉更糟 —— 后来的人会以为它还在用。

    /** 转圈的颜色跟着主色走（自绘的 View，Theming 不认识它，所以单独上色）。 */
    private void tintSpinner() {
        if (orbit == null) return;
        orbit.setColor(Theme.toArgb(Theming.tokens(this).pri));
    }

    /**
     * 开屏背景（渐变 + 雪）跟着主题走。
     *
     * 与 {@link #tintSpinner()} 同理：自绘的 View 不在 Theming 的角色体系里，
     * 换肤后必须显式重贴 —— 否则用户改了主题，开屏还是上一套底色。
     */
    private void tintBackdrop() {
        if (backdrop == null) return;
        Theme.Tokens t = Theming.tokens(this);
        backdrop.setTheme(t.bg, t.pri);
    }

    private void toggleLog() {
        boolean show = logScroll.getVisibility() != View.VISIBLE;
        logScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        logToggle.setText(show ? "收起日志" : "查看日志");
        if (show) publish();
    }

    /** 主状态一行（"正在准备运行环境…"）。 */
    private void setStatus(final String s) {
        postUi(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) statusView.setText(s);
            }
        });
    }

    /** 细节行左侧文字；右边固定跟一个"已等 N 秒"，让人知道没卡死。 */
    private void setDetail(final String left) {
        detailLeft = left == null ? "" : left;
        postUi(new Runnable() {
            @Override
            public void run() {
                renderDetail();
            }
        });
    }

    private void renderDetail() {
        if (detailView == null) return;
        long sec = bootStartAt == 0 ? 0 : (System.currentTimeMillis() - bootStartAt) / 1000;
        detailView.setText(StartupText.detailLine(detailLeft, sec));
    }

    /** 心跳：每秒推一次"已等 N 秒" —— 解压要好几分钟时，这是"没卡死"的唯一证据。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (ready || destroyed) return;
            long ms = bootStartAt == 0 ? 0 : System.currentTimeMillis() - bootStartAt;
            renderRotate(ms);
            renderDetail();
            ui.postDelayed(this, 1000);
        }
    };

    /**
     * 轮播那句话：**文案变了才**做淡入淡出。
     * 每秒都动一次的话整屏一直在闪，反而看不出"它在稳定地转"。
     */
    private void renderRotate(long elapsedMs) {
        if (rotateView == null) return;
        final String s = StartupText.rotateAt(elapsedMs);
        if (s.equals(lastRotate)) return;
        lastRotate = s;
        rotateView.animate().cancel();
        rotateView.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                rotateView.setText(s);
                rotateView.animate().alpha(1f).setDuration(320).start();
            }
        }).start();
    }

    /**
     * 启动成功：**直接进对话页**（转完圈就进去，不在中间停一层入口页）。
     *
     * 启动日志不会因此丢掉 —— 它同时写进 {@link BootLog}（进程内共享），
     * 「设置」页（底部栏第三格）读它显示与复制。
     *
     * 「至少显示 1.2 秒」（{@link SplashVisual#handOffDelayMs}）：热启动时 serve 几秒就绪，
     * 没有这一条，开屏会**闪一下**就没了 —— 那比多看 1.2 秒更难受，像界面抽搐了一下。
     * 冷启动要解压几分钟，这一条根本轮不到生效。
     */
    private void showReady() {
        ready = true;
        long elapsed = bootStartAt == 0 ? 0 : System.currentTimeMillis() - bootStartAt;
        long delay = SplashVisual.handOffDelayMs(elapsed);
        if (delay > 0) {
            say("启动完成 —— 开屏满 " + (SplashVisual.SPLASH_MIN_MS / 1000) + " 秒再交棒");
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                // postUi 没有 delay 变体（它走 runOnUiThread），所以这条延迟交棒只能自己守：
                // 页面已经走了就别再交棒 —— 否则会在一棵没了的视图树上做缩放淡出动画。
                if (!alive()) return;
                handOff();
            }
        }, delay);
    }

    /**
     * 交棒：**先缩放淡出，再进对话页**。
     *
     * 光靠 {@code overridePendingTransition(0, 0)} 是"这边还在、那边硬切"的错位；
     * 参照物的开屏专门收了这一手（整体放大到 1.05 + 上移 + 淡出），
     * 两端都动才像"平滑地进去了"。系统动画仍设成 0 —— 两层动画叠起来反而是糊的。
     */
    private void handOff() {
        final Runnable enter = new Runnable() {
            @Override
            public void run() {
                // 用户已经离开本页（启动中途按了返回）—— 别再从一个销毁了的 Activity 起对话页。
                // serve 早已起来并被 AppRuntime 持有着，下次打开照常连得上。
                if (destroyed) return;
                // 交棒前落一份启动快照 —— 「这次启动走到哪一步、serve 什么状态」的完整答案，
                // 而且**不必等用户在设置页手动导出**（那正是外部一直拿不到真机数据的原因）。
                Diagnostics.snapshot(MainActivity.this, host, "entered");
                try {
                    // 没配置就先去配对页 —— 包不再自带密钥（见 SetupActivity 的类注释），
                    // 配好之前进对话页只会看到"未配置"，不如直接把该做的事摆到面前。
                    File rivetHome = new File(host.rootfsDir(), "root/.rivet");
                    Class<?> next = ConfigInstaller.hasConfig(rivetHome)
                            ? ChatActivity.class : SetupActivity.class;
                    startActivity(new Intent(MainActivity.this, next));
                    // 启动页到对话页不留系统动画：转完圈"直接就在里面了"比滑进来更顺
                    overridePendingTransition(0, 0);
                } catch (Throwable t) {
                    // 进不去就把启动页留着并说清楚，别让用户对着一个转完圈的空白页发呆
                    fail("打不开对话页：" + t.getClass().getSimpleName());
                    return;
                }
                // 启动页不再兼入口页：从对话页按返回应该退出 App，而不是回到这里看一遍日志
                finish();
            }
        };

        if (rootView == null) {
            enter.run();
            return;
        }
        if (backdrop != null) backdrop.stop();   // 冻住雪：淡出的该是"一张画"，不是还在动的画面
        rootView.animate()
                .alpha(0f)
                .scaleX(SplashVisual.ZOOM_OUT_SCALE)
                .scaleY(SplashVisual.ZOOM_OUT_SCALE)
                .translationY(-Theming.dp(this, SplashVisual.ZOOM_OUT_SHIFT_DP))
                .setDuration(SplashVisual.ZOOM_OUT_MS)
                .withEndAction(enter)
                .start();
    }

    /** 启动失败：停下转圈、说明白、并把日志展开（藏起来等于让用户没法报错）。 */
    private void fail(final String msg) {
        ready = true;                // 停掉心跳
        postUi(new Runnable() {
            @Override
            public void run() {
                if (orbit != null) orbit.stop();   // 停下：失败之后不该继续假装在做事
                // 雪也冻住 —— 底下写着"启动没成功"、雪还在飘，那是自相矛盾的信号
                if (backdrop != null) backdrop.stop();
                statusView.setText("启动没成功");
                failedView.setVisibility(View.VISIBLE);
                failedView.setText(msg + "\n已把日志展开在下面，可复制发给开发者");
                logScroll.setVisibility(View.VISIBLE);
                logToggle.setText("收起日志");
                logExport.setVisibility(View.VISIBLE);   // 出问题了 —— 给一条把日志带出去的路
                publish();
            }
        });
    }

    /**
     * 把运行报告（启动日志 + serve 日志 + 环境/状态）落到共享存储，并说清落在哪。
     *
     * 失败态专用入口：用户不必知道 adb、也不必去找 App 的私有目录 ——
     * 点一下，文件就在共享存储上，外面直接读得到。
     */
    private void exportLogAndSay() {
        try {
            String name = LogExport.write(this, host);
            say("已导出运行报告 → " + LogExport.DIR + "/" + name);
            say("（同目录的 " + LogReport.LATEST_FILE + " 是同一份，固定名，方便直接读最新）");
        } catch (Throwable t) {
            say("导出失败：" + t);
        }
        publish();
    }

    private void step(String n, String what) {
        say("");
        say("=== " + n + " " + what + " ===");
        setStatus(what);                  // 状态行只说当下这一步（总进度交给上面的清单）
        steps.add(n + " " + what);
        renderSteps();
        publish();
    }

    /**
     * 步骤清单 —— 逐条累加：走过的打 `✓`、当前这条用主色 `●`。
     *
     * 冷启动要解压约 600 MB、可能好几分钟；而**App 一切到后台，Android 就会冻结它**
     *用户能依靠的只有这一屏。原先只显示「3/4 起常驻 serve」一行，
     * 看不出总共几步、也看不出前面几步过没过。这条清单把"走到哪了"摊开。
     *
     * 符号 `✓`(U+2713) 与 `●`(U+25CF) 都在字体覆盖核验过的白名单里（16 / 27 个字体）。
     */
    private void renderSteps() {
        postUi(new Runnable() {
            @Override
            public void run() {
                if (stepsBox == null) return;
                stepsBox.removeAllViews();
                for (int i = 0; i < steps.size(); i++) {
                    boolean last = i == steps.size() - 1;
                    // 每步一行：**图标 + 文字**。原来是把 `●` / `✓` 拼进字符串里 ——
                    // 那两个字符同样是"靠字体碰运气"，现在是自绘图标。
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER);

                    Icon ic = new Icon(MainActivity.this, last ? Icon.DOT : Icon.CHECK).size(12);
                    Theming.tag(ic, last ? Theming.ROLE_ACCENT : Theming.ROLE_MUTED);
                    row.addView(ic);

                    TextView t = new TextView(MainActivity.this);
                    t.setText(steps.get(i));
                    t.setTextSize(Ui.CAPTION);
                    t.setPadding(Theming.dp(MainActivity.this, Ui.S2), 0, 0, 0);
                    Theming.tag(t, last ? Theming.ROLE_ACCENT : Theming.ROLE_MUTED);
                    row.addView(t);
                    stepsBox.addView(row);
                }
                Theming.applyTree(MainActivity.this, stepsBox);
            }
        });
    }

    private void say(final String s) {
        synchronized (log) {
            log.append(s).append('\n');
        }
        // 同时写进进程内日志 —— 启动完就跳对话页了，这些字只能靠「设置」页回看
        BootLog.say(s);
    }

    /** 把累积的日志刷到日志区（收着的时候也刷，一展开就是最新的）。 */
    private void publish() {
        postUi(new Runnable() {
            @Override
            public void run() {
                if (logBody == null) return;
                synchronized (log) {
                    logBody.setText(log.toString());
                }
                if (logScroll.getVisibility() == View.VISIBLE) {
                    logScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            logScroll.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }
}
