package dev.tianshu.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.tukaani.xz.XZOutputStream;

/**
 * 宿主工程的不变量测试。纯逻辑，不碰 Android API，可在容器里用 javac+java 直接跑。
 *
 * 覆盖三类不变量：
 *   A. tar 格式解析（普通文件 / 目录 / 符号链接 / GNU 长名 / 结束标记）
 *   B. 路径安全（拒绝 ../ 与绝对路径 —— 这是解压外部 tar 的硬不变量）
 *   C. 原子安装（解压到 staging，成功后一次性改名；目标已存在则跳过）
 */
public final class HostTest {

    // ---------------------------------------------------------------- 断言器
    static int passed = 0, failed = 0;

    static void ok(boolean cond, String msg) {
        if (cond) { passed++; System.out.println("  ok   " + msg); }
        else { failed++; System.out.println("  FAIL " + msg); }
    }

    static void eq(Object got, Object want, String msg) {
        ok(Objects.equals(got, want), msg + "   got=" + got + " want=" + want);
    }

    // ---------------------------------------------------------------- 造 tar 字节
    private static void put(byte[] h, int off, byte[] src, int max) {
        System.arraycopy(src, 0, h, off, Math.min(src.length, max));
    }

    private static byte[] octal(long v, int digits) {
        byte[] b = new byte[digits + 1];
        Arrays.fill(b, (byte) '0');
        String s = Long.toOctalString(v);
        int start = digits - s.length();
        for (int i = 0; i < s.length(); i++) b[start + i] = (byte) s.charAt(i);
        b[digits] = 0;
        return b;
    }

    /** 造一个 POSIX ustar header（512B）。chksum 最后补算。 */
    static byte[] header(String name, int mode, long size, char type, String link) {
        byte[] h = new byte[512];
        put(h, 0, name.getBytes(StandardCharsets.UTF_8), 100);
        put(h, 100, octal(mode, 7), 8);
        put(h, 108, octal(0, 7), 8);
        put(h, 116, octal(0, 7), 8);
        put(h, 124, octal(size, 11), 12);
        put(h, 136, octal(0, 11), 12);
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        if (link != null) put(h, 157, link.getBytes(StandardCharsets.UTF_8), 100);
        put(h, 257, "ustar".getBytes(StandardCharsets.US_ASCII), 5);
        h[262] = 0; h[263] = '0'; h[264] = '0';
        put(h, 265, "root".getBytes(), 32);
        put(h, 297, "root".getBytes(), 32);
        int sum = 0;
        for (byte b : h) sum += (b & 0xff);
        put(h, 148, octal(sum, 6), 7);
        h[154] = 0; h[155] = ' ';
        return h;
    }

    private static byte[] pad(byte[] data) {
        int n = (int) ((512 - (data.length % 512)) % 512);
        byte[] out = Arrays.copyOf(data, data.length + n);
        return out;
    }

    private static byte[] fileEntry(String name, String content, int mode) {
        byte[] c = content.getBytes(StandardCharsets.UTF_8);
        return concat(header(name, mode, c.length, '0', null), pad(c));
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    /** 把若干条目拼成完整 tar（含 1024B 结束块）。 */
    static byte[] tar(byte[]... entries) {
        byte[] body = concat(entries);
        return concat(body, new byte[1024]);
    }

    static void xz(byte[] raw, File out) throws IOException {
        try (XZOutputStream xz = new XZOutputStream(new FileOutputStream(out), new org.tukaani.xz.LZMA2Options(1))) {
            xz.write(raw);
        }
    }

    // ---------------------------------------------------------------- 测试

    /**
     * Z′. 崩溃报告 —— 真机闪退时**唯一**能带回来的现场。
     *
     * 为什么值得单测：这份文本是给"看不到设备的人"读的（容器里看不到 App 的进程与
     * 端口，也进不去 App 私有目录 —— 2026-10-05 逐条实测过）。信息少了等于没写：
     * 到那时只能说"崩了"，却不知道崩在哪一行、当时是什么状态 —— 又会退化成猜。
     *
     * 三条底线：① 异常类名与消息在；② 栈帧在（定位靠它）；③ cause 链不许丢
     *（真正的原因常常压在第二层，只印最外层那句话会指错方向）。
     */
    static void testCrashReport() throws Exception {
        System.out.println("== Z′. 崩溃报告 ==");

        Exception cause = new IllegalStateException("底层原因");
        Throwable t = new RuntimeException("表面原因", cause);
        String text = CrashReport.compose(
                "2026-10-05 03:20:00",
                "Android 14 (API 34) · App 0.1-1005-0255",
                "serve: ready=true alive=false url=http://127.0.0.1:18799",
                "（boot 日志尾部）",
                "main", t);

        ok(text.indexOf("RuntimeException") >= 0, "异常类名在报告里");
        ok(text.indexOf("表面原因") >= 0, "异常消息在报告里");
        ok(text.indexOf("IllegalStateException") >= 0, "cause 链不许丢（根因常在这一层）");
        ok(text.indexOf("底层原因") >= 0, "cause 的消息也要在");
        ok(text.indexOf("at ") > 0, "栈帧在报告里（定位就靠它）");
        ok(text.indexOf("main") >= 0, "崩在哪个线程在报告里");
        ok(text.indexOf("ready=true") >= 0, "崩溃那一刻的运行时状态在报告里");
        ok(text.indexOf("2026-10-05 03:20:00") >= 0, "时间戳在报告里");

        // 没有 cause 也要能拼（最常见的那种：NPE / IllegalState）
        String simple = CrashReport.compose("t", "env", "st", "", "main",
                new NullPointerException("空指针"));
        ok(simple.indexOf("NullPointerException") >= 0, "无 cause 也能出报告");

        // 文件名要一眼认得出是什么、什么时候 —— 翻目录时不必逐个打开看
        String name = CrashReport.fileName(1700000000000L);
        ok(name.startsWith("crash-") && name.endsWith(".txt"),
                "崩溃文件名形如 crash-<时间戳>.txt（实际 " + name + "）");
    }

    /**
     * GuestCli 的超时语义 —— 2026-10-06 修：原实现「先读到底、再 waitFor」两处都没有
     * deadline，子进程不关 stdout 时调用线程**永久阻塞**（换密钥那条后台线程再也回不来）。
     *
     * 为什么不打真命令：`run()` 要 RuntimeHost（依赖 android Context 与 proot），HostTest
     * 里起不来。所以把**可测的那半**抽成了 {@link GuestCli#collect(Process, long)} ——
     * 纯 JDK，直接喂一个 Process 就能验。`run()` 内只有一处 collect 调用，行为等价。
     */
    static void testGuestCliTimeout() throws Exception {
        System.out.println("== GuestCli 超时 ==");

        // 1. 超时必须生效：sleep 30 在 400ms 预算下要被终止，而不是拖满 30 秒
        Process slow = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
        long t0 = System.currentTimeMillis();
        GuestCli.Output o = GuestCli.collect(slow, 400);
        long dt = System.currentTimeMillis() - t0;
        ok(o.code < 0, "超时被识别为失败（code=" + o.code + "）");
        ok(dt < 8000, "没有被拖满 30 秒（实测 " + dt + " ms）");
        Thread.sleep(300);
        ok(!slow.isAlive(), "超时后子进程已被终止");

        // 2. 反例：预算 0 → 立刻判超时（证明判据来自 deadline，不是"碰巧很快"）
        Process slow2 = new ProcessBuilder("sleep", "5").redirectErrorStream(true).start();
        GuestCli.Output o2 = GuestCli.collect(slow2, 0);
        ok(o2.code < 0, "预算 0 立即判超时（code=" + o2.code + "）");

        // 3. 正常路径：退出码 0 + 输出收全
        Process good = new ProcessBuilder("sh", "-c", "echo hello-guest")
                .redirectErrorStream(true).start();
        GuestCli.Output g = GuestCli.collect(good, 10000);
        eq(g.code, 0, "正常退出码 0");
        ok(g.text.contains("hello-guest"), "输出被收下：" + g.text);

        // 4. 非零退出：码与 stderr 原文都要透出去（换密钥失败靠它告诉用户为什么）
        Process bad = new ProcessBuilder("sh", "-c", "echo boom-1>&2; exit 3")
                .redirectErrorStream(true).start();
        GuestCli.Output b = GuestCli.collect(bad, 10000);
        eq(b.code, 3, "非零退出码透传");
        ok(b.text.contains("boom"), "stderr 被合并收下：" + b.text);
    }

    /**
     * U. 会话详情 —— 官方**会话级**路由的解析与写入体（2026-10-06② 顺着 3.28.0 的 302 条路由对账补的）。
     *
     * 夹具来自**运行中的 3.28.0 serve**（`test/fixtures/api/sessions__:id__*.json`）——
     * 有夹具 = 那条路由真返回过 200，界面里就不可能长出"打过去 404"的按钮。
     * 非空分支另用**文档化形状**内联构造（真机上没有待批准的会话，抓不到）。
     */
    static void testSessionDetail() throws Exception {
        System.out.println("== U. 会话详情（官方会话级路由）==");

        // 0. 每条声明都必须有真夹具
        String[] paths = { "/sessions/:id/interventions", "/sessions/:id/files",
                "/sessions/:id/jobs", "/sessions/:id/hooks", "/sessions/:id/review-gate",
                "/sessions/:id/file-content", "/sessions/:id/git/diff",
                "/sessions/:id/skills", "/sessions/:id/skills/installable",
                "/sessions/:id/domains" };
        eq(paths.length, 10, "声明了 10 条会话级路径");
        for (String p : paths) {
            ok(apiFixture(p) != null, "有真夹具（= 真返回过 200）：" + p);
        }

        // 1. interventions —— 真夹具（空表）
        eq(SessionDetail.parseInterventions(apiFixture("/sessions/:id/interventions")).size(), 0,
                "空 approvals 解析成空表");

        // 2. interventions —— 非空（文档化形状：requestId / toolName / input / pathGrant）
        String pending = "{\"approvals\":["
                + "{\"requestId\":\"req-1\",\"toolName\":\"request_path_access\","
                + "\"input\":{\"path\":\"/sdcard/x\",\"mode\":\"write\"},"
                + "\"pathGrant\":{\"dir\":\"/sdcard/x\",\"mode\":\"write\"}},"
                + "{\"requestId\":\"req-2\",\"toolName\":\"bash\",\"input\":{\"command\":\"ls\"}},"
                + "{\"toolName\":\"bash\"}],\"lastSeq\":126}";
        List<SessionDetail.Approval> list = SessionDetail.parseInterventions(pending);
        eq(list.size(), 2, "没有 requestId 的那条被跳过（没有它就无法应答）");
        eq(list.get(0).requestId, "req-1", "requestId 透传");
        eq(list.get(0).title(), "请求访问工作区外的目录", "工具名翻成人话");
        ok(list.get(0).subtitle().contains("/sdcard/x（写）"),
                "有授权诉求时优先展示它：" + list.get(0).subtitle());
        eq(list.get(1).title(), "要跑一条命令", "bash 也翻成人话");
        ok(list.get(1).subtitle().contains("command=ls"),
                "无授权时退到 input 摘要：" + list.get(1).subtitle());

        // 3. 应答体与路径 —— 字段名必须与 harness 对齐（body.decision；"approve" 判为批准）
        eq(SessionDetail.answerBody(true), "{\"decision\":\"approve\"}", "批准体");
        eq(SessionDetail.answerBody(false), "{\"decision\":\"deny\"}", "驳回体");
        eq(SessionDetail.answerPath("s1", "req-1"), "/sessions/s1/interventions/req-1/answer",
                "应答路径");

        // 4. files —— 真夹具里那两条已知文件 + 内部件折叠
        List<String> all = SessionDetail.parseFiles(apiFixture("/sessions/:id/files"));
        ok(all.contains("workspace/key.txt"), "真夹具含 workspace/key.txt（共 " + all.size() + " 条）");
        ok(all.contains(".rivet/config.json"), "真夹具含内部件");
        List<String> user = SessionDetail.userFiles(all);
        ok(user.contains("workspace/key.txt"), "用户文件保留");
        ok(!user.contains(".rivet/config.json"), "内部件被折叠");
        ok(all.size() - user.size() > 0, "确实折叠了 " + (all.size() - user.size()) + " 个内部件");
        ok(SessionDetail.isInternal(".git/HEAD"), ".git/ 也算内部件");

        // 5. 查询串转义（path 里常有 `/`、空格、中文）
        eq(SessionDetail.fileContentPath("a b/中.txt"), "/file-content?path=a%20b/%E4%B8%AD.txt",
                "file-content 查询串转义");
        eq(SessionDetail.gitDiffPath("x"), "/git/diff?path=x", "diff 查询串");

        // 6. 取值 + 脏输入不抛
        String fc = apiFixture("/sessions/:id/file-content");
        ok(SessionDetail.contentOf(fc).length() > 0, "真夹具能取出 content");
        ok(SessionDetail.languageOf(fc).length() > 0, "真夹具能取出 language");
        ok(SessionDetail.diffOf(apiFixture("/sessions/:id/git/diff")).contains("workspace/key.txt"),
                "真夹具能取出 diff");
        eq(SessionDetail.contentOf("垃圾"), "", "脏输入不抛");
        eq(SessionDetail.parseInterventions(null).size(), 0, "null 不抛");
        eq(SessionDetail.parseFiles("{}").size(), 0, "缺 files 键 → 空表");
        eq(SessionDetail.userFiles(null).size(), 0, "userFiles(null) 不抛");
    }

    /**
     * V. 账号 / OAuth —— 官方账号面路由的解析与写入体（2026-10-06④ 对账补的）。
     *
     * 夹具来自**运行中的 3.28.0 serve**。核心一条：设备码登录缺了**轮询**就永远登不上
     *（App 里 `account/poll` 原先出现 0 次），所以「什么时候该继续轮」必须钉死 ——
     * pending 继续，approved / expired / 认不出 都要停（否则会无限轮）。
     */
    static void testAccount() throws Exception {
        System.out.println("== V. 账号 / OAuth ==");

        // 0. 每条声明都有真夹具（= 那条路由真返回过 200）
        for (String p : new String[] { "/account/status", "/account/device", "/account/poll",
                "/config/providers" }) {
            ok(apiFixture(p) != null, "有真夹具：" + p);
        }

        // 1. 设备码响应
        Account.Device d = Account.parseDevice(apiFixture("/account/device"));
        ok(d != null && d.ok(), "设备码解析成功");
        ok(d.userCode.length() > 0, "拿到 userCode");
        ok(d.verifyUrl.startsWith("https://"), "拿到授权链接");
        eq(d.pollInterval, 5, "轮询间隔取服务端给的");
        eq(d.expiresIn, 300, "有效期取服务端给的");
        ok(Account.parseDevice("垃圾") == null, "脏输入返回 null");
        ok(Account.parseDevice("{}") == null, "缺 deviceCode 返回 null");

        // 2. 轮询判据 —— 本次修复的核心
        eq(Account.pollStatus("{\"status\":\"pending\"}"), "pending", "pending 透传");
        ok(Account.keepPolling("{\"status\":\"pending\"}"), "pending → 继续轮");
        ok(Account.isApproved("{\"status\":\"approved\"}"), "approved → 成功");
        ok(!Account.keepPolling("{\"status\":\"approved\"}"), "approved → 停");
        ok(!Account.keepPolling(apiFixture("/account/poll")), "expired（真夹具）→ 停");
        ok(!Account.keepPolling("垃圾"), "认不出 → 停（不许无限轮）");
        eq(Account.pollBody("ab\"c"), "{\"deviceCode\":\"ab\\\"c\"}", "轮询体转义");

        // 3. 账号状态
        Account.Status st = Account.parseStatus(apiFixture("/account/status"));
        ok(!st.loggedIn, "真夹具里是未登录");
        eq(st.line(), "未登录", "未登录给人话");
        Account.Status on = Account.parseStatus(
                "{\"loggedIn\":true,\"stellarId\":\"星-001\",\"primaryDomain\":\"启明\",\"title\":\"旅人\"}");
        ok(on.line().contains("星籍 星-001"), "登录后给星籍：" + on.line());
        ok(on.line().contains("启明"), "带主星域");
        ok(!Account.parseStatus("垃圾").loggedIn, "脏输入当未登录，不抛");

        // 4. 服务商 OAuth：只列 authType=="oauth" 的
        String prov = "{\"providers\":["
                + "{\"name\":\"deepseek\",\"label\":\"DeepSeek\"},"
                + "{\"name\":\"codex\",\"label\":\"Codex\",\"authType\":\"oauth\",\"oauthAuthenticated\":true},"
                + "{\"name\":\"codex2\",\"authType\":\"oauth\"}]}";
        List<Account.Oauth> oa = Account.oauthProviders(prov);
        eq(oa.size(), 2, "只列 OAuth 型（填密钥型不进这一页）");
        eq(oa.get(0).name, "codex", "名字透传");
        eq(oa.get(0).action(), "登出", "已授权 → 按钮写「登出」");
        eq(oa.get(1).action(), "登录", "未授权 → 按钮写「登录」");
        eq(oa.get(1).label, "codex2", "没 label 就用 name 兜底");
        // 真夹具上只断言**形状** —— 数量随用户配置变，写死 0 或 1 都会在用户改配置后变红
        //（第一版就写死了"真夹具里没有 OAuth 服务商"，而线上 codex 是 authType=oauth，
        // 一抓新夹具立刻打脸 —— 教训：夹具断言别绑用户数据的具体值）。
        java.util.List<Account.Oauth> real = Account.oauthProviders(apiFixture("/config/providers"));
        ok(real.size() >= 1, "真夹具里能挑出 OAuth 型服务商（codex）：" + real.size());
        for (Account.Oauth p : real) ok(p.name.length() > 0, "挑出来的都有名字：" + p.name);
        eq(Account.oauthProviders("{\"providers\":[{\"name\":\"a\"}]}").size(), 0,
                "全非 OAuth → 空表");
        eq(Account.oauthProviders(null).size(), 0, "null 不抛");

        // 5. 路径
        eq(Account.oauthPath("codex", true), "/config/providers/codex/oauth/login", "登录路径");
        eq(Account.oauthPath("codex", false), "/config/providers/codex/oauth/logout", "登出路径");
    }

    public static void main(String[] args) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "hosttest-" + System.nanoTime());
        tmp.mkdirs();

        String fixturePath = args.length > 0 ? args[0] : "test/fixtures/sse-real-sample.txt";
        String sessionsFixture = args.length > 1 ? args[1] : "test/fixtures/sessions-real.json";

        testHeaderParsing();
        testSymlinkAndLongName();
        testEndOfArchive();
        testPathSafety();
        testLinkSafety(tmp);
        testInstallAtomic(tmp);
        testHardlinkFallback(tmp);
        testHostileHardlink(tmp);
        testXzMemoryHeadroom(tmp);
        testServeGuard();
        testSetupWizard(tmp);
        testLogReport();
        testChipLabels();
        testConfigInstall(tmp);
        testRuntimeBinds();
        testResume(tmp);
        testResumeSymlinkEscape(tmp);
        testSessionList(sessionsFixture);
        testSessionLifecycle();
        testCommandRouting();
        testConstellation();
        testReplayWindow();
        testSheetScrollable();
        testReplayDoneGate();
        testJumpToLatest();
        testCommandApi();
        testSessionSearch();
        testApiResult();
        testCommandPrompt();
        testNavLandings();
        testSse(fixturePath);
        testCommandCatalog();
        testTranscript(fixturePath);
        testTheme();
        testAppearanceCoverage();
        testStartupText();
        testTabs();
        testProfilePage();
        testAccountEntry();
        testAppName();
        testReleaseHygiene();
        testSessionListResume();
        testAppearanceEntry();
        testRotateAndSuggest();
        testStyles();
        testChatTitleAndBackExit();
        testSwipeNav();
        testKeyboardLift();
        testRenderPlan();
        testConsoleCatalog();
        testCommandFate();
        testStatusBar();
        testSessionDeletePlan();
        testConfigEditable();
        testChipsAlwaysVisible();
        testConfigValueLocalize();
        testConfigHumanLabel();
        testConsoleGroups();
        testSessionMetaLine();
        testYukiPreset();
        testWithAlpha();
        testChipWrap();
        testSplashVisual();
        testTimeLabels();
        testUiLanguageCoverage();
        testNoGlyphIcons();
        testNoBakedThemeDrawable();
        testCrashReport();
        testFixRegressions();
        testManifestConfigChanges();
        testBuildIdentity();
        testStatusBarFollowsTheme();
        testContrastWithBackgroundImage();
        testGuestCliTimeout();
        testSessionDetail();
        testAccount();

        System.out.println("\n[HostTest] passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * 删除会话的**步骤**：服务端的 `DELETE /sessions/:id/permanent` 只对**已归档**的会话生效
     * （没归档就删 → 409）。界面上那个「删除」按钮并不知道手头这条归档了没有 ——
     * 所以未归档时必须先归档再删，否则用户点了只会看到一句"删不掉"。
     */
    static void testSessionDeletePlan() {
        System.out.println("== 会话删除的步骤 ==");

        java.util.List<String> fresh = SessionActions.deletePlan(false);
        ok(fresh.size() == 2, "未归档的会话：两步（先归档、再永久删除）");
        ok(SessionActions.ARCHIVE.equals(fresh.get(0)), "第一步是归档");
        ok(SessionActions.DELETE.equals(fresh.get(1)), "第二步才是永久删除");

        java.util.List<String> arch = SessionActions.deletePlan(true);
        ok(arch.size() == 1, "已归档的会话：一步到位");
        ok(SessionActions.DELETE.equals(arch.get(0)), "唯一一步是永久删除");

        // 2026-10-04 深查：归档是**异步中止**，紧跟其后的 /permanent 可能瞬时 409 ——
        // 只有"删除"这一步值得隔一会儿重试一次（重试归档没有意义）。
        ok(SessionActions.retryableStep(SessionActions.DELETE), "删除步骤可重试");
        ok(!SessionActions.retryableStep(SessionActions.ARCHIVE), "归档步骤不重试");

        // ---- 2026-10-04 深查新增：两处边界 ----
        // Providers.humanTokens：原条件 `% 100000` 会把 1500000 整数截成 "1M"
        ok("1M".equals(Providers.humanTokens(1000000)), "整百万 → 1M");
        ok(!"1M".equals(Providers.humanTokens(1500000)),
                "一百五十万不能被截成 1M：" + Providers.humanTokens(1500000));
        ok("256K".equals(Providers.humanTokens(256000)), "256000 → 256K");
        ok("—".equals(Providers.humanTokens(0)), "0 → 短横");
        // BootLog.tail：缓冲区恒以换行结尾，原先差一（tail(1) 返回空串）
        BootLog.clear();
        BootLog.say("a");
        BootLog.say("b");
        BootLog.say("c");
        eq(BootLog.tail(1), "c", "tail(1) 就是最后一行");
        eq(BootLog.tail(2), "b\nc", "tail(2) 是最后两行");
        eq(BootLog.tail(99), "a\nb\nc", "行数不够就给全部");
        eq(BootLog.tail(0), "", "tail(0) 空串");
        BootLog.clear();

        // Providers.effectiveSelection：**页头与列表勾选必须是同一份答案**
        // （原缺陷：defaultModel=null 时页头回退显示了服务商默认模型，列表却一行都没勾）
        String pj = "{\"providers\":["
                + "{\"name\":\"a\",\"label\":\"A\",\"isDefault\":false,\"models\":[{\"id\":\"m1\"}]},"
                + "{\"name\":\"b\",\"label\":\"B\",\"isDefault\":true,\"models\":[{\"id\":\"m2\"},{\"id\":\"m3\"}]}]}";
        java.util.List<Providers.Provider> ps = Providers.parse(pj);
        eq(ps.size(), 2, "解析出两个服务商");
        String[] ex = Providers.effectiveSelection(ps, "a:m1");
        ok(ex != null && "a".equals(ex[0]) && "m1".equals(ex[1]), "显式指定 → 就用它");
        String[] fb = Providers.effectiveSelection(ps, null);
        ok(fb != null && "b".equals(fb[0]) && "m2".equals(fb[1]),
                "defaultModel=null → 回退到默认服务商的第一个模型（列表据此勾选）");
        ok(Providers.currentLabel(ps, null).indexOf("m2") >= 0,
                "页头显示的与勾选的必须是同一项：" + Providers.currentLabel(ps, null));
        String[] keep = Providers.effectiveSelection(ps, "zz:yy");
        ok(keep != null && "zz".equals(keep[0]), "显式但不在列表里 → 原样保留（那种情况列表自然无可勾行）");
        ok(Providers.effectiveSelection(Providers.parse("{}"), null) == null, "空列表 + 无默认 → null");

        // 路径映射别搞反：删除打 /permanent，归档打裸 /sessions/:id
        ok("/sessions/s1/permanent".equals(SessionActions.pathFor(SessionActions.DELETE, "s1")),
                "删除打 /sessions/:id/permanent");
        ok("/sessions/s1".equals(SessionActions.pathFor(SessionActions.ARCHIVE, "s1")),
                "归档打裸 /sessions/:id");
    }

    /**
     * 「高级原始配置」里到底哪些能就地改：**只有顶层标量**（布尔 / 字符串 / 数字）。
     * 用户反馈「很多改不了」—— 能改的面要数得出来，边界要钉死（嵌套一律只读：
     * 它的写入 schema 没人验过，盲写会把运行时改坏）。
     */
    static void testConfigEditable() {
        System.out.println("== 配置项的可编辑面 ==");

        String json = "{\"enabled\":true,\"model\":\"deepseek-v4-flash\",\"limit\":42,"
                + "\"nested\":{\"inner\":\"x\"},\"list\":[1,2],\"nothing\":null}";
        java.util.List<ConfigView.Row> rows = ConfigView.flatten(json, 2);

        ConfigView.Row b = rowByKey(rows, "enabled");
        ok(b != null && b.editable && "bool".equals(b.kind), "顶层布尔：可改（开关）");
        ConfigView.Row s = rowByKey(rows, "model");
        ok(s != null && s.editable && "string".equals(s.kind), "顶层字符串：可改");
        ConfigView.Row n = rowOf(rows, "limit");
        ok(n != null && n.editable && "number".equals(n.kind), "顶层数字：可改");

        ConfigView.Row inner = rowOf(rows, "nested.inner");
        ok(inner != null && !inner.editable, "嵌套字符串：只读");
        ConfigView.Row list = rowOf(rows, "list");
        ok(list != null && !list.editable, "数组：只读");
        ConfigView.Row nil = rowOf(rows, "nothing");
        ok(nil != null && !nil.editable, "null：只读（不知道该写回哪种类型）");

        // 写回去的字面量：数字不加引号，字符串加引号并转义
        ok("42".equals(ConfigView.jsonLiteral(n, " 42 ")), "数字字面量不加引号、去空白");
        ok("\"abc\"".equals(ConfigView.jsonLiteral(s, "abc")), "字符串字面量加引号");
        ok("\"a\\\"b\"".equals(ConfigView.jsonLiteral(s, "a\"b")), "字符串里的引号要转义");
        ok("\"\"".equals(ConfigView.jsonLiteral(s, "")), "空串是合法值（服务端语义＝清空）");
    }

    /**
     * 状态 chip **常驻**（用户反馈 #1：「新对话的时候也要在，不能进行对话的时候才出来」）。
     *
     * 这里只钉纯逻辑那一层：**没有数据时要给什么**。控件的常驻/换行在 Activity 与 FlowRow 里。
     */
    static void testChipsAlwaysVisible() {
        System.out.println("== 状态 chip 常驻 ==");

        java.util.List<StatusBar.Chip> booting = StatusBar.placeholder(false);
        ok(booting.size() == 1, "运行环境没就绪时也有一枚 chip（整行不消失）");
        ok(booting.get(0).label().indexOf("准备") >= 0, "没就绪那枚说的是「准备中」");

        java.util.List<StatusBar.Chip> idle = StatusBar.placeholder(true);
        ok(idle.size() == 1, "就绪但还没开会话时也有一枚");
        ok(idle.get(0).label().indexOf("说一句") >= 0 || idle.get(0).label().indexOf("新会话") >= 0,
                "就绪空会话那枚告诉用户下一步做什么");
    }

    /**
     * 配置值本地化（用户反馈 #2：「设置里面的默认星域 defaultDomain 里面的值翻译成中文」）。
     *
     * 两条规矩：
     *   ① 值在**同响应里能自解释**（`domains:[{id,name}]`）→ 用那份名字；
     *   ② 认不得的值**原样回显**，绝不猜、更不显示成空。
     */
    static void testConfigValueLocalize() {
        System.out.println("== 配置值本地化 ==");

        String domainsJson = "{\"defaultDomain\":\"qiming\",\"domainKeywordRouting\":true,"
                + "\"domains\":[{\"id\":\"qiming\",\"name\":\"启明\"},{\"id\":\"pojun\",\"name\":\"破军\"}]}";
        java.util.List<ConfigView.Row> rows = ConfigView.flatten(domainsJson, 2);

        ConfigView.Row d = rowOf(rows, "默认星域");
        ok(d != null && "启明".equals(d.value), "defaultDomain 显示成中文名（启明）");
        ok(d != null && "qiming".equals(d.raw), "原始值仍留着（写回去要用它）");

        ok("全自动".equals(ConfigView.localizeValue("approval", "dangerously-skip-permissions")),
                "approval：dangerously-skip-permissions → 全自动");
        ok("手动".equals(ConfigView.localizeValue("approval", "manual")),
                "approval：manual → 手动（与 suggest 分道，不重名）");
        ok("监督".equals(ConfigView.localizeValue("approval", "suggest")),
                "approval：suggest → 监督（两处映射已统一到 StatusBar.approvalLabel）");
        // 这条原先断言的是 "auto" —— 而 harness **根本没有这个取值**（它认的是
        // auto-safe / auto-accept）。旧断言钉着一个不存在的行为，所以它绿着也说明
        // 不了什么，反倒掩盖了真正漏掉的那三个值。改成拿真实取值验。
        ok(!"auto-safe".equals(ConfigView.localizeValue("approval", "auto-safe")),
                "approval：auto-safe 不再原样返回英文");
        ok("自动·安全".equals(ConfigView.localizeValue("approval", "auto-safe")),
                "approval：auto-safe → 自动·安全");
        ok("自动·接受".equals(ConfigView.localizeValue("approval", "auto-accept")),
                "approval：auto-accept → 自动·接受");
        ok("whatever".equals(ConfigView.localizeValue("approval", "whatever")),
                "认不得的值原样回显（不猜、不显示空）");
    }

    /**
     * chip 换行（用户反馈 #1：「要全部显示出来，不用滑动」）。
     *
     * 抽成纯逻辑是因为换行测量最容易出边界错：算错就漏一枚、或者死循环。
     * 返回**每行放几枚**；单枚超宽也必须是 1（不能让容器算成 0）。
     */
    static void testChipWrap() {
        System.out.println("== chip 换行 ==");

        int[] a = StatusBar.wrapRows(new int[]{100, 100, 100}, 250, 8);
        ok(a.length == 2 && a[0] == 2 && a[1] == 1, "宽度 100/100/100 在 250 里：第一行 2 枚、第二行 1 枚");

        int[] b = StatusBar.wrapRows(new int[]{100, 100}, 250, 8);
        ok(b.length == 1 && b[0] == 2, "放得下就不换行");

        int[] c = StatusBar.wrapRows(new int[]{300}, 250, 8);
        ok(c.length == 1 && c[0] == 1, "单枚比容器还宽：仍占一行（不能算成 0）");

        int[] d = StatusBar.wrapRows(new int[]{}, 250, 8);
        ok(d.length == 0, "没有 chip 就没有行");

        int[] e = StatusBar.wrapRows(new int[]{120, 120, 120}, 250, 8);
        ok(e.length == 2 && e[0] == 2 && e[1] == 1,
                "120+8+120=248 ≤ 250 放得下两枚；第三枚换行（边界按精确值算）");
    }

    /**
     * 高级配置的**字段名中文化**（用户反馈 #5：「高级：原始配置展开后感觉很劣质、很难懂」）。
     *
     * 一片 `domainKeywordRouting` / `peakWindows` 这样的键名，普通用户读不出它管什么。
     * 规矩：认得的给中文，**认不得的原样回显**（宁可见英文，也不猜错意思）。
     * 写回用的仍是 `Row.key`（原始键），所以翻译只影响显示。
     */
    static void testConfigHumanLabel() {
        System.out.println("== 配置字段名中文化 ==");

        ok("默认星域".equals(ConfigView.humanLabel("defaultDomain")), "defaultDomain → 默认星域");
        ok("审批模式".equals(ConfigView.humanLabel("approval")), "approval → 审批模式");
        ok("启用".equals(ConfigView.humanLabel("enabled")), "enabled → 启用");
        ok("unsandboxedKey".equals(ConfigView.humanLabel("unsandboxedKey")),
                "认不得的键原样回显（不猜）");

        // 顶层 label 用中文；写回的 key 仍是英文原键
        String json = "{\"defaultDomain\":\"qiming\"}";
        ConfigView.Row d = rowOf(ConfigView.flatten(json, 2), "默认星域");
        ok(d != null && "defaultDomain".equals(d.key), "label 中文化了，但写回的 key 还是原键");
    }

    /**
     * 高级配置的**分组**（用户反馈：27 个族平铺 "很突兀、很难懂"）。
     *
     * 分组表是显示层的：每个族必须落进一个已登记的组，否则会从页面上"消失"。
     */
    static void testConsoleGroups() {
        System.out.println("== 配置族分组 ==");

        ok(ConsoleCatalog.GROUP_ORDER.size() >= 2, "至少两组");

        for (ConsoleCatalog.Fam f : ConsoleCatalog.CONFIG) {
            String g = ConsoleCatalog.groupOf(f.path);
            ok(g != null && !g.isEmpty(), f.path + " 有分组");
            ok(ConsoleCatalog.GROUP_ORDER.indexOf(g) >= 0,
                    f.path + " 的分组出现在 GROUP_ORDER 里（否则渲染时会漏掉这一族）");
        }
        for (ConsoleCatalog.Fam f : ConsoleCatalog.INFO) {
            ok(ConsoleCatalog.GROUP_ORDER.indexOf(ConsoleCatalog.groupOf(f.path)) >= 0,
                    "状态族 " + f.path + " 也有组");
        }

        ok("常用".equals(ConsoleCatalog.groupOf("/config/default-domain")), "默认星域归「常用」");
        ok("其它".equals(ConsoleCatalog.groupOf("/config/not-a-real-family")),
                "没登记过的路径归「其它」（不丢、不崩）");
    }

    /**
     * 会话卡片上那行元信息的拼法（绝对时间 + 状态 + 归档标记）。
     *
     * 抽成纯逻辑是因为它原先在会话页里就地拼、对话页侧栏又各写一份 —— 两处必然漂。
     */
    static void testSessionMetaLine() {
        System.out.println("== 会话卡片元信息行 ==");

        SessionList.Item it = new SessionList.Item();
        it.status = "completed";
        it.createdAt = 0;

        String plain = SessionList.metaLine(it, 0);
        ok(plain.indexOf("已归档") < 0, "未归档就不写「已归档」");
        ok(plain.indexOf("completed") < 0, "completed 是噪音，不上屏");

        it.status = "aborted";
        ok(SessionList.metaLine(it, 0).indexOf("已中止") >= 0, "非 completed 的状态中文化上屏");

        it.status = "completed";
        it.archived = true;
        ok(SessionList.metaLine(it, 0).indexOf("已归档") >= 0, "归档的有标记");
    }

    /**
     * **初雪预设** —— 照一款参考 App（Yuki 初雪）
     * 的扁平风色板：浅灰底 + 纯白卡 + 纯蓝主色（参考里用户明确否掉了紫色：「别用紫色，很降档次」）。
     *
     * 它同时是**默认**（用户说「就这个文件来重构」「大刀阔斧」）。
     */
    static void testYukiPreset() {
        System.out.println("== 初雪预设（照参考 App） ==");

        Theme.Preset p = Theme.presetById("yuki");
        ok(p != null && "light".equals(p.mode), "有初雪预设（浅色）");
        ok(p != null && "#f5f7fa".equalsIgnoreCase(p.bg), "背景 = 参考的 #F5F7FA");
        ok(p != null && "#ffffff".equalsIgnoreCase(p.card), "卡片纯白");

        Theme.Accent a = Theme.accentById("yuki");
        ok(a != null && "#2f6bff".equalsIgnoreCase(a.pri), "主色 = 参考的纯蓝 #2F6BFF");

        Theme.Style s = Theme.styleById("yuki");
        ok(s != null, "有初雪风格");
        ok(s != null && s.radiusDp == 16, "卡片圆角 16（参考的 CardCorner）");
        ok(s != null && s.offsetDp == 0 && "none".equals(s.decor), "不带像素风的硬阴影/装饰");

        Theme.Config def = Theme.Config.defaults();
        ok("yuki".equals(def.preset) && "yuki".equals(def.accent) && "yuki".equals(def.style),
                "默认配置 = 初雪（背景 + 主色 + 风格）");

        // 参照那套的副文字灰（#8A8F99）压在 #F5F7FA 上只有 ~3.0:1，低于 AA ——
        // 天枢的护栏会把它推到达标。**照抄色值，但不照抄"看不清"**。
        ok(Theme.audit(def).isEmpty(), "初雪配色过对比度护栏"
                + (Theme.audit(def).isEmpty() ? "" : " → " + Theme.auditReport(def)));
        Theme.Tokens t = Theme.tokens(def);
        ok(Theme.contrast(t.sub, t.bg) >= 4.5, "副文字压在底色上 ≥ 4.5:1（护栏推过的那一档）");
    }

    /**
     * 半透明合成 —— 参考里「同一材质、厚度不同」全靠它：
     * 底栏 0.80、聊天输入区 0.62（用户要求"同一个效果但要有差异化"）。
     */
    static void testWithAlpha() {
        System.out.println("== 颜色 + alpha ==");

        ok(Theme.withAlpha(0xFF2F6BFF, 1.0) == 0xFF2F6BFF, "alpha=1：原样");
        ok(Theme.withAlpha(0xFF2F6BFF, 0.0) == 0x002F6BFF, "alpha=0：全透但 RGB 保留");
        ok(Theme.withAlpha(0xFF2F6BFF, 0.5) == 0x802F6BFF, "0.5 → 0x80");
        ok(Theme.withAlpha(0xFF2F6BFF, 2.0) == 0xFF2F6BFF, "越界钳到 1（不溢出成别的通道）");
        ok(Theme.withAlpha(0xFF2F6BFF, -1) == 0x002F6BFF, "负数钳到 0");
    }

    /**
     * 开屏（渐变 + 雪）的**纯逻辑**部分。
     *
     * 参照物把这件事说得很清楚：视觉缺陷从来不在参数上，都在观感上 ——
     * 所以这里只钉"可以机器判定的部分"：雪场是不是每次同一片、会不会跑到屏外、
     * 雪落在底色上会不会隐形、开屏至少显示多久。剩下那一件（好不好看）只能交给真机。
     */
    static void testSplashVisual() {
        System.out.println("== 开屏（渐变 + 雪） ==");

        // ---- 雪花场：确定性 ----
        java.util.List<SplashVisual.Flake> a = SplashVisual.field();
        java.util.List<SplashVisual.Flake> b = SplashVisual.field();
        eq(a.size(), SplashVisual.SNOW_COUNT, "默认 " + SplashVisual.SNOW_COUNT + " 片");
        boolean same = a.size() == b.size();
        for (int i = 0; same && i < a.size(); i++) {
            SplashVisual.Flake x = a.get(i), y = b.get(i);
            same = x.x == y.x && x.radius == y.radius && x.speed == y.speed
                    && x.phase == y.phase && x.alpha == y.alpha;
        }
        ok(same, "两次生成是同一片雪（固定种子 → 开屏这张脸不会每次换）");

        // ---- 边界 ----
        ok(SplashVisual.field(0).isEmpty(), "0 片 → 空表");
        ok(SplashVisual.field(-3) != null && SplashVisual.field(-3).isEmpty(), "负数片 → 空表（不是 null）");

        // ---- 值域：雪不能跑到屏外 ----
        boolean inRange = true;
        java.util.Set<Float> speeds = new java.util.HashSet<Float>();
        for (SplashVisual.Flake f : a) {
            speeds.add(f.speed);
            inRange &= f.x >= 0f && f.x < 1f;
            inRange &= f.phase >= 0f && f.phase < 1f;
            inRange &= f.radius >= SplashVisual.MIN_RADIUS && f.radius <= SplashVisual.MAX_RADIUS;
            inRange &= f.speed >= SplashVisual.MIN_SPEED && f.speed <= SplashVisual.MAX_SPEED;
            inRange &= f.alpha >= SplashVisual.MIN_ALPHA && f.alpha <= SplashVisual.MAX_ALPHA;
        }
        ok(inRange, "横向位置与相位在 0..1，半径/速度/透明度都在各自区间内");
        ok(speeds.size() > 1, "各片下落速度不同（速度全同 = 整片一起落，看起来像下雨）");

        // ---- yOf：循环、不越界、起点即相位 ----
        boolean cyclic = true, yInRange = true, startsAtPhase = true;
        for (SplashVisual.Flake f : a) {
            // 每片**自己的**周期是 1/speed 个 t 单位 —— 不是 1：
            // yOf 里乘的是 speed（各片不同），所以"过了 1 就重来一轮"只在 speed=1 时成立。
            // 天枢喂进来的是**无限增长**的时间轴（t 不回绕），取模发生在 yOf 内部 ——
            // 也就是说参照物那个"动画每次重启、整片雪会跳一下"的瑕疵，这里天然没有。
            cyclic &= Math.abs(SplashVisual.yOf(f, 0.37f)
                    - SplashVisual.yOf(f, 0.37f + 1f / f.speed)) < 1e-5;
            startsAtPhase &= Math.abs(SplashVisual.yOf(f, 0f) - f.phase) < 1e-6;
            for (float t : new float[]{-5f, -0.3f, 0f, 0.37f, 1f, 99.5f, 1e6f}) {
                float y = SplashVisual.yOf(f, t);
                yInRange &= y >= 0f && y < 1f;
            }
        }
        ok(cyclic, "过了自己那一整圈（1/speed 个 t）回到同一位置 —— 下落是循环的");
        ok(startsAtPhase, "t=0 时位置 = 该片的初始相位");
        ok(yInRange, "任何时间（含负数与极大值）算出的位置都在 0..1，不会画到屏外");

        // 同一时刻不能所有雪片挤在一处 —— 相位就是干这个的
        java.util.Set<Integer> buckets = new java.util.HashSet<Integer>();
        for (SplashVisual.Flake f : a) buckets.add((int) (SplashVisual.yOf(f, 0.5f) * 10));
        ok(buckets.size() >= 4, "同一时刻雪片散布在至少 4 个高度段（不是一条横线）");

        // ---- 配色：默认主题下就是参照物那两色 ----
        Theme.Preset yuki = Theme.presetById("yuki");
        Theme.Accent yukiAccent = Theme.accentById("yuki");
        ok(yuki != null && yukiAccent != null, "初雪预设与主色都在（下面两条才有意义）");
        String bottom = SplashVisual.gradientBottom(yuki.bg, yukiAccent.pri);
        ok(channelDiffAtMost(bottom, "#E9F0FB", 1),
                "默认组合的渐变终点 = 参照物的 #E9F0FB（每通道 ±1/255）  got=" + bottom);
        ok(SplashVisual.snowInk(bottom).equalsIgnoreCase(SplashVisual.SNOW_INK_BASE),
                "默认组合的雪花色 = 参照物的 #9FB6DD（逐字，未被阈值推走）  got="
                        + SplashVisual.snowInk(bottom));

        // ---- 护栏：换任何主题，雪都不能隐形 ----
        java.util.List<String> bgs = new java.util.ArrayList<String>();
        for (Theme.Preset p : Theme.PRESETS) bgs.add(p.bg);
        // 中间调的自定义底是最危险的一档：固定参考雪色落在它上面只有 1.16:1（几乎看不见）
        for (String e : new String[]{"#808080", "#aaaaaa", "#cccccc", "#000000", "#ffffff",
                "#00ff00", "#ff00ff"}) {
            bgs.add(e);
        }
        double worst = 99;
        String worstAt = "";
        boolean direction = true;
        int combos = 0;
        for (String base : bgs) {
            for (Theme.Accent acc : Theme.ACCENTS) {
                String gb = SplashVisual.gradientBottom(base, acc.pri);
                String ink = SplashVisual.snowInk(gb);
                double c = Theme.contrast(ink, gb);
                combos++;
                if (c < worst) {
                    worst = c;
                    worstAt = base + "×" + acc.id + " 底=" + gb;
                }
                // 浅底上的雪必须比底暗、深底上的雪必须比底亮 —— 方向反了就是在往"隐形"推
                boolean dark = Theme.isDark(gb);
                direction &= dark ? Theme.lum(ink) > Theme.lum(gb) : Theme.lum(ink) < Theme.lum(gb);
            }
        }
        ok(worst + 1e-9 >= SplashVisual.SNOW_MIN_CONTRAST,
                combos + " 组（主题 × 主色 + 极端自定义底）：雪色对比度最低 " + worst + ":1（"
                        + worstAt + "）");
        ok(direction, "浅底上的雪偏暗、深底上的雪偏亮（方向不会反）");

        // ---- 交棒时序：开屏至少显示 1.2 秒 ----
        eq(SplashVisual.handOffDelayMs(0), SplashVisual.SPLASH_MIN_MS, "刚启动完 → 补满 1.2 秒");
        eq(SplashVisual.handOffDelayMs(300), SplashVisual.SPLASH_MIN_MS - 300,
                "启动用了 0.3 秒 → 再等 0.9 秒");
        eq(SplashVisual.handOffDelayMs(SplashVisual.SPLASH_MIN_MS), 0L, "刚好满 1.2 秒 → 立即交棒");
        eq(SplashVisual.handOffDelayMs(5000), 0L, "启动用了 5 秒 → 不再等（冷启动解压几分钟同理）");
        eq(SplashVisual.handOffDelayMs(-1), 0L, "时钟回拨 → 立即交棒，不卡在这里");
        ok(SplashVisual.FRAME_MS < SplashVisual.SNOW_CYCLE_MS,
                "重绘间隔远小于下落周期（否则雪每帧跳一整屏）");
    }

    /** 两个 #rrggbb 是否每个通道都只差 ≤ n。 */
    static boolean channelDiffAtMost(String a, String b, int n) {
        int x = Theme.toArgb(a), y = Theme.toArgb(b);
        return Math.abs(((x >> 16) & 0xFF) - ((y >> 16) & 0xFF)) <= n
                && Math.abs(((x >> 8) & 0xFF) - ((y >> 8) & 0xFF)) <= n
                && Math.abs((x & 0xFF) - (y & 0xFF)) <= n;
    }

    /** 造一个本地时刻（测试用；跟系统时区走，容器与设备都是 +07/+08 一带）。 */
    private static long at(int y, int month, int d, int h, int min) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(y, month, d, h, min, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 聊天流的**时间分割条**（纯逻辑）。
     *
     * 这一组钉的是那三条边界（对抗复核点名的）：**本地日历日**（不是"距现在 24 小时"）、
     * **手写星期名**（不是 SimpleDateFormat，它会在 LC_ALL=C.UTF-8 下输出 Wed）、
     * 以及 prev=0 的两种情形。日期对应的星期是用 `date -d` 查过的真值
     * （2026-10-04=周日、10-07=周三、09-30=周三）。
     */
    static void testTimeLabels() {
        System.out.println("== 聊天流时间分割条 ==");

        long now = at(2026, java.util.Calendar.OCTOBER, 7, 14, 30);   // 周三

        eq(TimeLabels.forDivider(at(2026, java.util.Calendar.OCTOBER, 7, 9, 15), now),
                "09:15", "今天：只显示时刻");
        eq(TimeLabels.forDivider(at(2026, java.util.Calendar.OCTOBER, 6, 20, 0), now),
                "昨天 20:00", "昨天");
        eq(TimeLabels.forDivider(at(2026, java.util.Calendar.OCTOBER, 4, 8, 0), now),
                "周日 08:00", "一周内：显示周几");
        eq(TimeLabels.forDivider(at(2026, java.util.Calendar.SEPTEMBER, 30, 12, 0), now),
                "9月30日 12:00", "一周以前：显示月日");
        eq(TimeLabels.forDivider(at(2025, java.util.Calendar.DECEMBER, 31, 23, 59), now),
                "2025年12月31日 23:59", "跨年：显示完整日期");
        eq(TimeLabels.forDivider(0, now), "", "时间未知 → 空串（不显示 1970）");

        // 时钟回拨：消息时刻落在"未来"，仍算今天，不炸也不出负数
        eq(TimeLabels.forDivider(now + 3600_000L, now), "15:30", "时钟回拨：未来时刻按今天显示");

        // ---- 分割条判据：看**间隔**，不是看跨没跨天 ----
        ok(TimeLabels.needsDivider(0, now), "第一条有时间戳的消息 → 插");
        ok(!TimeLabels.needsDivider(0, 0), "两条都没有时间戳 → 不插");
        ok(!TimeLabels.needsDivider(now, 0), "当前这条时间未知 → 不插");
        long fiveMin = 5 * 60 * 1000L;
        ok(TimeLabels.needsDivider(now, now + fiveMin), "恰好 5 分钟 → 插（>= 边界）");
        ok(!TimeLabels.needsDivider(now, now + fiveMin - 1), "差 1 毫秒不到 5 分钟 → 不插");
        ok(!TimeLabels.needsDivider(at(2026, java.util.Calendar.OCTOBER, 6, 23, 58),
                        at(2026, java.util.Calendar.OCTOBER, 7, 0, 1)),
                "跨天但只隔 3 分钟 → 不插（判据是间隔，不是跨天）");
    }

    static ConfigView.Row rowOf(java.util.List<ConfigView.Row> rows, String label) {
        for (ConfigView.Row r : rows) {
            if (label.equals(r.label)) return r;
        }
        return null;
    }

    /**
     * 按**原始键**找行。断言一律优先用它 —— `label` 会随显示层的中文化变
     * （`enabled` 显示成「启用」），`key` 才是稳定标识。找嵌套行才退回去用 label。
     */
    static ConfigView.Row rowByKey(java.util.List<ConfigView.Row> rows, String key) {
        for (ConfigView.Row r : rows) {
            if (key.equals(r.key)) return r;
        }
        return null;
    }

    /** A. header 解析：名字 / 类型 / 尺寸 / 模式 / 数据正确跨过 */
    static void testHeaderParsing() throws Exception {
        System.out.println("== A. tar header 解析 ==");
        byte[] t = tar(
                header("etc/", 0755, 0, '5', null),
                fileEntry("etc/hello.txt", "hello\n", 0644),
                fileEntry("bin/run.sh", "#!/bin/sh\n", 0755));

        ByteArrayInputStream in = new ByteArrayInputStream(t);

        TarReader.Entry dir = TarReader.readHeader(in);
        eq(dir.name, "etc/", "目录条目名");
        eq(String.valueOf(dir.type), String.valueOf('5'), "目录 typeflag");
        eq(dir.isDirectory(), true, "isDirectory()");
        TarReader.skipData(in, dir.size);

        TarReader.Entry f1 = TarReader.readHeader(in);
        eq(f1.name, "etc/hello.txt", "普通文件名");
        eq(f1.isRegularFile(), true, "isRegularFile()");
        eq(f1.size, 6L, "文件尺寸");
        eq(f1.mode & 0777, 0644, "文件 mode");
        eq(new String(TarReader.readData(in, f1.size), StandardCharsets.UTF_8), "hello\n", "文件内容");

        TarReader.Entry f2 = TarReader.readHeader(in);
        eq(f2.mode & 0777, 0755, "可执行位");
        TarReader.skipData(in, f2.size);
    }

    /** A'. 符号链接（rootfs 全靠它，解成普通文件整棵树就废了）+ GNU 长名 */
    static void testSymlinkAndLongName() throws Exception {
        System.out.println("== A'. 符号链接与 GNU 长名 ==");
        String longName = "usr/share/doc/" + String.join("", Collections.nCopies(12, "verylongsegment/")) + "README";
        byte[] ln = longName.getBytes(StandardCharsets.UTF_8);

        byte[] t = tar(
                concat(header("././@LongLink", 0, ln.length + 1, 'L', null), pad(Arrays.copyOf(ln, ln.length + 1))),
                header(longName, 0644, 0, '0', null),
                header("bin/sh", 0777, 0, '2', "dash"));

        ByteArrayInputStream in = new ByteArrayInputStream(t);

        TarReader.Entry e1 = TarReader.readHeader(in);
        eq(e1.name, longName, "GNU 长名被套用到下一条目");
        TarReader.skipData(in, e1.size);

        TarReader.Entry e2 = TarReader.readHeader(in);
        eq(e2.name, "bin/sh", "符号链接名");
        eq(String.valueOf(e2.type), String.valueOf('2'), "符号链接 typeflag");
        eq(e2.isSymlink(), true, "isSymlink()");
        eq(e2.linkName, "dash", "符号链接目标");
        TarReader.skipData(in, e2.size);
    }

    /** A''. 结束标记 */
    static void testEndOfArchive() throws Exception {
        System.out.println("== A''. 归档结束 ==");
        ByteArrayInputStream in = new ByteArrayInputStream(tar(fileEntry("a", "x", 0644)));
        TarReader.readHeader(in);
        TarReader.skipData(in, 1);
        eq(TarReader.readHeader(in), null, "读到结束块返回 null");
    }

    /** B. 路径安全 —— 外部 tar 不得把文件写到目标目录之外 */
    static void testPathSafety() {
        System.out.println("== B. 路径安全 ==");
        ok(!RootfsInstaller.isSafeRelativePath("../etc/passwd"), "拒绝 ../etc/passwd");
        ok(!RootfsInstaller.isSafeRelativePath("a/../../b"), "拒绝 a/../../b");
        ok(!RootfsInstaller.isSafeRelativePath("/etc/passwd"), "拒绝绝对路径 /etc/passwd");
        ok(!RootfsInstaller.isSafeRelativePath("./../x"), "拒绝 ./../x");
        ok(!RootfsInstaller.isSafeRelativePath(""), "拒绝空路径");
        ok(RootfsInstaller.isSafeRelativePath("usr/bin/node"), "接受 usr/bin/node");
        ok(RootfsInstaller.isSafeRelativePath("./bin/sh"), "接受 ./bin/sh");
        ok(RootfsInstaller.isSafeRelativePath("a/./b"), "接受 a/./b");
    }

    /**
     * B2'. **链接条目的越界防护** —— 2026-10-04 深查发现的真缺陷。
     *
     * `e.name` 有 {@link RootfsInstaller#isSafeRelativePath} 拦着，但 **`e.linkName` 一个校验都没有**，
     * 而 `normalize()` 又**保留 `..`** —— 归档里一条 `hardlink → ../x` 就能把 staging 之外的文件
     * 链进 rootfs；一条 `symlink → ../outside` 再加一条 `esc/pwned` 就能把文件写到目标目录之外。
     *
     * 这两条断言在**修之前是红的**（越界确实发生），修完才绿。
     */
    static void testLinkSafety(File tmp) throws Exception {
        System.out.println("== B2'. 链接条目越界防护 ==");

        // ① 硬链接的 linkName 指向 staging 之外 → 必须被拒
        File dest1 = new File(tmp, "link1");
        File stg1 = new File(tmp, "link1.partial");
        File pkg1 = new File(tmp, "link1.tar.xz");
        File victim = new File(tmp, "victim-secret");
        java.nio.file.Files.write(victim.toPath(), "SECRET".getBytes("UTF-8"));
        xz(tar(header("usr/bin/x", 0755, 0, '1', "../victim-secret")), pkg1);
        RootfsInstaller.install(pkg1, dest1, stg1, new NioSink(), null);
        eq(new File(dest1, "usr/bin/x").exists(), false, "越界硬链接不得落地");

        // ② 先建指向外面目录的符号链、再往里写 → 不许穿出去
        File dest2 = new File(tmp, "link2");
        File stg2 = new File(tmp, "link2.partial");
        File pkg2 = new File(tmp, "link2.tar.xz");
        File outsideDir = new File(tmp, "outside-dir");
        java.nio.file.Files.createDirectories(outsideDir.toPath());   // 外面那个目录先存在
        xz(tar(
                header("esc", 0777, 0, '2', "../outside-dir"),
                fileEntry("esc/pwned", "ESCAPED", 0644)), pkg2);
        RootfsInstaller.install(pkg2, dest2, stg2, new NioSink(), null);
        eq(new File(outsideDir, "pwned").exists(), false, "不得穿过符号链写到目标目录之外");

        // ③ hasSymlinkAncestor：判祖先的纯逻辑（前缀按**路径段**比，不能误伤同前缀）
        java.util.Set<String> links = new java.util.HashSet<String>();
        links.add("esc");
        ok(RootfsInstaller.hasSymlinkAncestor("esc/pwned", links), "祖先里有符号链 → 拦");
        ok(RootfsInstaller.hasSymlinkAncestor("esc", links), "自己就是那条链 → 拦");
        ok(!RootfsInstaller.hasSymlinkAncestor("escape/x", links), "同前缀不同段 → 不拦");
        ok(!RootfsInstaller.hasSymlinkAncestor("other/x", links), "无关路径 → 不拦");
        ok(!RootfsInstaller.hasSymlinkAncestor("esc/pwned", new java.util.HashSet<String>()), "没有链 → 不拦");
    }

    /** C. 原子安装：解压到 staging → 改名；目标已存在则跳过 */
    static void testInstallAtomic(File tmp) throws Exception {
        System.out.println("== C. 原子安装与幂等 ==");
        File dest = new File(tmp, "rootfs");
        File staging = new File(tmp, "rootfs.partial");
        File pkg = new File(tmp, "small.tar.xz");

        xz(tar(
                header("bin/", 0755, 0, '5', null),
                fileEntry("bin/sh", "#!/bin/sh\necho hi\n", 0755),
                header("bin/sh.link", 0777, 0, '2', "sh"),
                fileEntry("etc/os-release", "NAME=Ubuntu\n", 0644)), pkg);

        int calls = 0;
        RootfsInstaller.Result r1 = RootfsInstaller.install(pkg, dest, staging, new NioSink(), null);
        eq(r1.installed, true, "首次安装执行了");
        eq(new File(dest, "bin/sh").isFile(), true, "bin/sh 落地");
        eq(new File(dest, "etc/os-release").isFile(), true, "etc/os-release 落地");
        eq(readText(new File(dest, "etc/os-release")), "NAME=Ubuntu\n", "文件内容一致");
        eq(new File(dest, "bin/sh").canExecute(), true, "可执行位保留");
        eq(new File(dest, "bin/sh.link").exists(), true, "符号链接存在");
        eq(isSymlink(new File(dest, "bin/sh.link")), true, "bin/sh.link 是符号链接");
        ok(!staging.exists(), "成功后 staging 已被改名走");

        RootfsInstaller.Result r2 = RootfsInstaller.install(pkg, dest, staging, new NioSink(), null);
        eq(r2.installed, false, "第二次调用被跳过（幂等）");
    }

    /**
     * C'. 环境不允许硬链接时必须回退为复制。
     * 真机实测：Android 的 app 私有目录 link(2) 返回 EACCES，
     * rootfs 里 /usr/bin/perl5.38.2 <- /usr/bin/perl 这类条目会在这里炸掉。
     */
    static void testHardlinkFallback(File tmp) throws Exception {
        System.out.println("== C'. 硬链接不可用时回退为复制 ==");
        File dest = new File(tmp, "hlroot");
        File staging = new File(tmp, "hlroot.partial");
        File pkg = new File(tmp, "hl.tar.xz");

        xz(tar(
                fileEntry("usr/bin/perl", "PERL-BINARY", 0755),
                header("usr/bin/perl5.38.2", 0755, 0, '1', "usr/bin/perl")), pkg);

        RootfsInstaller.Result r =
                RootfsInstaller.install(pkg, dest, staging, new NoHardlinkSink(), null);

        eq(r.installed, true, "安装完成（未因硬链接失败而中断）");
        File linked = new File(dest, "usr/bin/perl5.38.2");
        eq(linked.isFile(), true, "硬链接目标仍然落地");
        eq(readText(linked), "PERL-BINARY", "回退复制的内容与源一致");
        eq(new File(dest, "usr/bin/perl").canExecute(), true, "源文件的可执行位保留");
    }

    /**
     * C''. **谎报**支持硬链接、且 link 失败会把源文件吃掉的环境。
     *
     * 这是 `548eda3` 那次「先试、失败就退回复制」改动的盲区：回退的前提是
     * **源文件还在**。在实测到的这种文件系统上它不成立（见 {@link HostileHardlinkSink}），
     * 于是不但没退回复制，还多丢一个文件。
     *
     * 修法不是"保证源一定在"（做不到），而是**不许静默**：失败且源没了就显式报错，
     * 把真相带出来。这条断言钉住那句真相，防止它退回成误导人的 `no such file`。
     */
    static void testHostileHardlink(File tmp) throws Exception {
        System.out.println("== C''. 谎报硬链接能力：源文件被失败的 link 吃掉 ==");

        File pkg = new File(tmp, "hostile.tar.xz");
        xz(tar(
                fileEntry("usr/bin/perl", "PERL-BINARY", 0755),
                header("usr/bin/perl5.38.2", 0755, 0, '1', "usr/bin/perl")), pkg);

        File dest = new File(tmp, "hostile-dest");
        File staging = new File(tmp, "hostile-partial");
        boolean threw = false;
        String message = null;
        try {
            RootfsInstaller.install(pkg, dest, staging,
                    new HostileHardlinkSink(), null, "HOSTILE-FP");
        } catch (IOException expected) {
            threw = true;
            message = expected.getMessage();
        }
        eq(threw, true, "谎报支持 + 源被吃掉 → 必须显式失败（不能当没事发生）");
        ok(message != null && message.contains("源文件已消失"),
                "失败原因要指向真凶（源被 link 吃掉），实际：" + message);
        eq(dest.exists(), false, "★ 中断后目标目录绝不出现");
    }

    /**
     * Z′. 解压的**内存上限**必须容得下 pack-lean 打出来的包。
     *
     * 真机踩到过（卸载重装，首次真解压）：
     *   MemoryLimitException: 65640 KiB of memory would be needed; limit was 65536 KiB
     *
     * 成因不是"包坏了"：`pack-lean.sh` 用 `xz -9e` 打包，LZMA2 字典 64 MiB，
     * 解压要 65640 KiB；而安装器给 XZInputStream 的 limit 恰好是 65536 KiB（1<<16），
     * **差 104 KiB**。以前从没暴露，是因为覆盖安装时 `if (destDir.isDirectory()) return`
     * 幂等跳过了解压 —— 只有卸载后重装才会第一次真正走这条路。
     *
     * 这条断言用 `LZMA2Options(9)`（与 xz -9 同档）造包，逼出同一量级的内存需求。
     * 上限若被调回 64 MiB，它会立刻变红。
     */
    static void testXzMemoryHeadroom(File tmp) throws Exception {
        System.out.println("== Z'. 解压内存上限要容得下 xz -9 的包 ==");

        File pkg = new File(tmp, "bigdict.tar.xz");
        byte[] raw = tar(fileEntry("etc/bigdict-probe", "x", 0644));
        java.io.OutputStream o = new java.io.FileOutputStream(pkg);
        try {
            org.tukaani.xz.XZOutputStream xz = new org.tukaani.xz.XZOutputStream(
                    o, new org.tukaani.xz.LZMA2Options(9));   // xz -9 档：字典 64 MiB
            try {
                xz.write(raw);
            } finally {
                xz.close();
            }
        } finally {
            o.close();
        }

        File dest = new File(tmp, "bigdict-dest");
        File staging = new File(tmp, "bigdict-partial");
        RootfsInstaller.Result r =
                RootfsInstaller.install(pkg, dest, staging, new NioSink(), null);
        eq(r.installed, true, "xz -9 档（字典 64 MiB）的包必须解得开");
        eq(readText(new File(dest, "etc/bigdict-probe")), "x", "内容正确");
    }

    /**
     * V′. 状态行那两枚 chip 的**取值映射** —— 审批模式与推理档位。
     *
     * 真机踩过：状态行上明晃晃写着 `[盾牌] suggest`（用户截图里红圈圈出来的）。
     * 根因是 `approvalLabel` 只登记了三个值，而且其中一个认的是 `"auto"` ——
     * **harness 根本没有这个取值**。它实际有五个（从 dist 里逐个抠出的字面量）：
     * manual / suggest / auto-safe / auto-accept / dangerously-skip-permissions。
     *
     * 同一次还发现「档位」压根没接进状态行（数据一直在会话项里）。
     * 两条都钉在这儿：**登记表必须跟得上 harness**，以及**未知值不许瞎猜中文**。
     */
    static void testChipLabels() throws Exception {
        System.out.println("== V′. 状态行 chip 的取值映射 ==");

        eq(StatusBar.approvalLabel("suggest"), "监督", "suggest（真机漏出来的那个；用户定的名）");
        eq(StatusBar.approvalLabel("manual"), "手动", "manual —— 与 suggest 分道，不重名");
        eq(StatusBar.approvalLabel("auto-safe"), "自动·安全", "auto-safe");
        eq(StatusBar.approvalLabel("auto-accept"), "自动·接受", "auto-accept");
        eq(StatusBar.approvalLabel("dangerously-skip-permissions"), "全自动", "dangerously-*");
        eq(StatusBar.approvalLabel("未来新加的某个模式"), "未来新加的某个模式",
                "未登记的值原样返回 —— 宁可露英文，也不编一个错的中文");
        eq(StatusBar.approvalLabel(""), "", "空串就是空串（由调用方补占位）");
        eq(StatusBar.approvalLabel(null), "", "null 不炸");

        eq(StatusBar.effortLabel("off"), "关", "档位 off");
        eq(StatusBar.effortLabel("low"), "低", "档位 low");
        eq(StatusBar.effortLabel("medium"), "中", "档位 medium");
        eq(StatusBar.effortLabel("high"), "高", "档位 high");
        eq(StatusBar.effortLabel("max"), "最高", "档位 max");
        eq(StatusBar.effortLabel("auto"), "自动", "档位 auto");
        eq(StatusBar.effortLabel("unknown-tier"), "unknown-tier", "未登记的档位原样返回");

        // ---- 默认值：档位「自动」、星域「启明」（用户 2026-10-05 定）----
        // 状态行是**常驻**的（还没建会话时也在），此时会话项给不出档位与星域 ——
        // 从前留一个 `—`，用户看到的是一排"不知道本该是什么"。这两条把默认钉死。
        eq(StatusBar.effortLabel(StatusBar.effortOrDefault("")), "自动",
                "会话没给档位 → 默认 auto → 显示「自动」");
        eq(StatusBar.effortLabel(StatusBar.effortOrDefault(null)), "自动", "档位 null 同样回退默认");
        eq(StatusBar.effortLabel(StatusBar.effortOrDefault("   ")), "自动", "空白也算没给");
        eq(StatusBar.effortLabel(StatusBar.effortOrDefault("high")), "高",
                "会话给了档位就用会话的（默认不覆盖真实值）");

        String domJson = apiFixture("/config/default-domain");
        eq(StatusBar.defaultDomainId(domJson), "qiming", "默认星域取服务端 defaultDomain");
        eq(StatusBar.domainIdOr("", domJson), "qiming", "会话没给星域 → 回落默认星域");
        eq(StatusBar.domainName(domJson, StatusBar.domainIdOr("", domJson)), "启明",
                "默认星域显示「启明」");
        eq(StatusBar.domainIdOr("pojun", domJson), "pojun", "会话给了星域就用会话的");
        eq(StatusBar.defaultDomainId(""), "qiming",
                "连 /config/default-domain 都拉不到 → 兜底 qiming（状态行仍显示启明）");

        // ---- 2026-10-05：「还没有会话」与「有会话但没读到」必须分开 ----
        // 真机上用户报「思考强度改了之后还是没变」。根因之一：两种情况都落到默认值，
        // 于是"改了没生效"与"根本没读到"在界面上长得一模一样。分开之后，
        // 有会话却读不到会显示 `—`（空串），界面不再替服务端圆谎。
        eq(StatusBar.effortFor(true, ""), "auto", "还没有会话 → 用默认档位（显示「自动」）");
        eq(StatusBar.effortFor(false, ""), "", "有会话却读不到 → 空串（界面显示 —）");
        eq(StatusBar.effortFor(false, "high"), "high", "有会话且读到了 → 用真实值");
        eq(StatusBar.effortLabel(StatusBar.effortFor(false, "")), "",
                "读不到时不该显示成一个像模像样的档位名");
        eq(StatusBar.domainIdFor(true, "", domJson), "qiming", "还没有会话 → 用默认星域");
        eq(StatusBar.domainIdFor(false, "", domJson), "", "有会话却读不到 → 空串（显示 —）");
        eq(StatusBar.domainIdFor(false, "pojun", domJson), "pojun", "有会话且读到了 → 用真实值");

        // ---- 2026-10-05：「待审批」那一条 ----
        // App 里没有批准入口（源码搜不到 pendingApprovals、没有 approvals 端点、
        // /permission 在对话通道里返回 Unknown slash command）。所以界面至少要说清楚
        // "它在等你、但要去终端处理"，否则用户只能对着不动的界面猜。
        eq(StatusBar.pendingApprovalNote(0), "", "没有待审批 → 空串（界面不显示这一条）");
        eq(StatusBar.pendingApprovalNote(-1), "", "负数也当没有（不显示）");
        ok(StatusBar.pendingApprovalNote(2).indexOf("2") >= 0, "待审批数写进提示里");
        ok(StatusBar.pendingApprovalNote(2).indexOf("终端") >= 0,
                "提示里必须指出下一步去哪儿处理：" + StatusBar.pendingApprovalNote(2));
        ok(StatusBar.pendingApprovalNote(1).indexOf("没法批") >= 0,
                "提示里要说清 App 里批不了，别让用户找按钮");

        // 服务端一直在给 pendingApprovals（真夹具里就有），App 从前不解析 —— 现在读了
        java.util.List<SessionList.Item> ps = SessionList.parse(
                "{\"sessions\":[{\"id\":\"s1\",\"title\":\"t\",\"pendingApprovals\":2}]}");
        eq(ps.size(), 1, "能解析出一条会话");
        eq(ps.get(0).pendingApprovals, 2, "pendingApprovals 被真的读出来了");
        eq(SessionList.parse("{\"sessions\":[{\"id\":\"s2\"}]}").get(0).pendingApprovals, 0,
                "缺键 → 0（不显示提示）");

        // ---- 审批选择器能写的值，必须都是 harness 认的（2026-10-05 核出的 bug）----
        // 原先那三个选项发的是 dangerously-skip-permissions / manual / **auto** ——
        // 最后一个 harness 根本没有，而「监督」那项实际发 manual（点完状态行显示
        // 「手动」，跟刚点的字对不上）。断言把"写"与"显示"钉在同一份登记表上。
        boolean illegal = false;
        StringBuilder modeNames = new StringBuilder();
        for (String m : StatusBar.APPROVAL_MODES) {
            String zh = StatusBar.approvalLabel(m);
            modeNames.append(zh).append(' ');
            if ("auto".equals(m)) illegal = true;     // harness 不认这个取值
            if (zh.equals(m)) illegal = true;         // 露英文 = 没登记 = 写下去也没人认
        }
        ok(!illegal, "可选审批模式全部在登记表里（不许再有那个不存在的 auto）：" + modeNames);
        eq(StatusBar.APPROVAL_MODES.length, 5, "harness 一共五个审批模式，一个都不许少");

        // ---- 挑「当前会话」不许"退回最新一条"（2026-10-05 核出的 bug）----
        // 退回的代价：新建会话（id 还没拿到）时，状态行会拿**别人的会话**显示上下文/星域/档位。
        SessionList.Item sa = new SessionList.Item();
        sa.id = "s-a";
        SessionList.Item sb = new SessionList.Item();
        sb.id = "s-b";
        java.util.List<SessionList.Item> two = new java.util.ArrayList<SessionList.Item>();
        two.add(sa);
        two.add(sb);
        ok(SessionList.pickById(two, "不存在的 id") == null,
                "id 匹配不到 → null（绝不拿别人的会话冒充当前会话）");
        ok(SessionList.pickById(two, null) == null, "还没有会话 id（新会话）→ null");
        ok(SessionList.pickById(two, "") == null, "空 id → null");
        ok(SessionList.pickById(two, "s-b") == sb, "id 对得上 → 那一条");
        ok(SessionList.pickById(null, "s-a") == null, "列表为 null 也不炸");
        eq(SessionList.parse(apiFixture("/sessions")).size(), 0, "空会话列表 → 也挑不出东西");

        // 七枚恒常驻 + 新加的档位 = 八枚；缺数据也要在（占位 —）
        java.util.List<StatusBar.Chip> chips = StatusBar.build(
                "m", "", 0, 0, 0L, "suggest", "high", "", "");
        eq(chips.size(), 7, "七枚 chip 恒常驻（含档位；峰/闲已去掉）");

        // 「峰 / 闲½」那枚 2026-10-05 已去掉（用户原话：「小功能条里面的闲峰计价时段
        // 去掉，不要了」）。钉住它不会哪天又冒回来 —— 顺带钉住可点清单也别留着它。
        boolean hasPeak = false;
        for (StatusBar.Chip c : StatusBar.build(
                "m", "", 0, 0, 0L, "suggest", "high", "", "启明")) {
            if ("peak".equals(c.kind)) hasPeak = true;
        }
        ok(!hasPeak, "状态行里不再有「峰/闲」那一枚");
        ok(!StatusBar.CLICKABLE_KINDS.contains("peak"), "可点清单里也没有 peak");
        StringBuilder kinds = new StringBuilder();
        for (StatusBar.Chip c : chips) kinds.append(c.kind).append(',');
        ok(kinds.toString().contains("effort,"), "档位那枚在行里：" + kinds);
        ok(kinds.toString().contains("permission,"), "审批那枚在行里：" + kinds);

        // ---- 每枚 chip 都得有动作 ----
        // 2026-10-05 真机：「模型档位为什么选不了」—— 档位是新加的，而 chipClick 按
        // kind 分支，新 kind 落不到任何分支：点下去有涟漪、之后什么都没有（假按钮）。
        // 这条断言把"产出的 kind 必须都在可点清单里"钉死，以后加 chip 忘接线会立刻红。
        for (StatusBar.Chip c : chips) {
            // elapsed 是例外：它**不渲染成 chip**（renderChips 把它挪到上方那张卡片里），
            // 所以"这排里每一枚都得能点"对它不适用。
            if ("elapsed".equals(c.kind)) continue;
            ok(StatusBar.CLICKABLE_KINDS.contains(c.kind),
                    "chip 有动作（否则就是一枚假按钮）：" + c.kind);
        }
        ok(StatusBar.CLICKABLE_KINDS.contains("effort"), "档位在可点清单里");
        java.util.List<StatusBar.Chip> ph = StatusBar.placeholder(true);
        for (StatusBar.Chip c : ph) {
            ok(StatusBar.CLICKABLE_KINDS.contains(c.kind),
                    "占位那枚也有动作：" + c.kind);
        }
    }

    /**
     * W′. 运行报告（{@link LogReport}）—— 「导出日志」用的拼装层。
     *
     * 最关键的一条是**空段必须显式出现**：某段没采到（serve 从没起来过、日志文件
     * 不存在）时要写「（无）」，而不是整段省掉 —— 省掉的话读的人分不清
     * "这台机器没这段"和"采集代码根本没跑到"，而那正是排查的头一个岔路口。
     */
    static void testLogReport() throws Exception {
        System.out.println("== W′. 运行报告 ==");

        String name = LogReport.fileName(0L);
        ok(name.startsWith("tianshu-log-") && name.endsWith(".txt"), "文件名形状：" + name);
        eq(LogReport.LATEST_FILE, "latest.txt", "固定名 —— 外部不必猜最新那份叫什么");

        String full = LogReport.compose("2026-10-04 15:00:00 +0800",
                "env-line", "state-line", "boot-line", "serve-line");
        ok(full.contains("env-line") && full.contains("state-line")
                && full.contains("boot-line") && full.contains("serve-line"), "四段都在");
        ok(full.contains("2026-10-04 15:00:00 +0800"), "生成时间在（带时区）");

        String empty = LogReport.compose(null, null, "", null, "   ");
        ok(empty.contains("环境"), "空的环境段：标题仍在");
        ok(empty.contains("serve 日志"), "空的 serve 段：标题仍在");
        eq(empty.split("（无", -1).length - 1, 4, "四段各自显式标「（无）」而不是被省掉");
        ok(empty.contains("（未知）"), "时间为空时说「未知」");
    }

    /**
     * Y. 首次配置 —— 密钥不再随包携带之后，App 侧那两条路的前置逻辑。
     *
     * 三块最有「静默出错」风险的地方：
     *   ① 命令拼装：key 里出现单引号、空格、美元符都是合法的（各家 token 形态不一），
     *      拼错不会报错，只会把一个被截断的 key 写进去 —— 之后表现为「配置成功但连不上」；
     *   ② 「已配置」判据：判错了要么把已配好的用户又拉回配对页，要么让没配的人直接进
     *      对话页对着「未配置」发呆；
     *   ③ 导入源：少一个文件（典型是 .token-key）时不许替它编造空文件 ——
     *      编了 harness 会以为配置齐全，反而更难查。
     */
    static void testSetupWizard(File tmp) throws Exception {
        System.out.println("== Y. 首次配置：密钥不随包 ==");

        eq(SetupScript.quote("abc"), "'abc'", "普通值原样包起来");
        eq(SetupScript.quote("a'b"), "'a'\\''b'", "内含单引号 → 拆成 '\\''");
        eq(SetupScript.setKey("deepseek", "sk-x"),
                "rivet config set-key 'deepseek' 'sk-x'", "set-key 命令形状");
        String risky = SetupScript.configureProvider("deepseek", "sk-a b$c'd");
        ok(risky.indexOf(" && ") > 0, "设 key 与设默认串成一条");
        ok(risky.indexOf("sk-a b$c'd") < 0, "危险值没有被原样放进命令行");

        // 2026-10-05：换密钥的入口从「只在首次配置页」扩到模型页（用户问的
        // 「为什么不可以改 key」）。模型页只调 setKey —— 改密钥**不该顺带把默认
        // 服务商换掉**：那是「模型」那一列按钮的职责，用户没点它就不该动。
        // 这条把两者的边界钉住（改错了不会报错，只会悄悄换掉用户的默认服务商）。
        ok(SetupScript.setKey("deepseek", "sk-x").indexOf("set-default") < 0,
                "setKey 不含 set-default（改密钥不越权换默认服务商）");
        ok(SetupScript.configureProvider("deepseek", "sk-x").indexOf("set-default") > 0,
                "configureProvider 才带 set-default（首次配置页用它）");

        File home = new File(tmp, "wizard-home");
        home.mkdirs();
        eq(ConfigInstaller.hasConfig(home), false, "空目录 → 未配置");
        writeText(new File(home, "config.json"), "{}");
        eq(ConfigInstaller.hasConfig(home), true, "有 config.json → 已配置");

        File src = new File(tmp, "wizard-src");
        src.mkdirs();
        writeText(new File(src, "config.json"), "{}");
        writeText(new File(src, ".token-key"), "k");
        ConfigInstaller.DirSource ds = new ConfigInstaller.DirSource(src);
        eq(ds.names().length, 2, "只列出存在的两个（不替缺失的编造）");

        File dest = new File(tmp, "wizard-dest");
        eq(ConfigInstaller.install(ds, dest, new NioSink()).size(), 2, "落盘两个");
        eq(new File(dest, ".token-key").isFile(), true, "带点的名字照原样落地");
        eq(ConfigInstaller.hasConfig(dest), true, "导入完就是已配置");
    }

    /**
     * R. serve 守护的判据 —— **什么时候该把 serve 拉起来、什么时候绝不能**。
     *
     * 背景：`/git/graph` 能让 Runtime 整个退出（Wave 1 打靶实测复现），而承载它的
     * proot 带 `--kill-on-exit`，于是在这之前 serve 一旦崩就没人管，用户只会看到
     * 「运行环境没起来」。后者那半句更关键：serve 可能在跑长任务，误重启会把
     * 正在进行的会话砍断 —— 那比"等下一拍"糟得多。
     */
    static void testServeGuard() throws Exception {
        System.out.println("== R. serve 守护判据 ==");

        ServeGuard g = new ServeGuard();
        long t = 1000L;
        final long dt = 10_000L;          // = PROBE_INTERVAL_MS

        eq(g.onProbe(true, true, t), ServeGuard.Action.NONE, "健康 → 不动");
        eq(g.misses(), 0, "健康时失败计数为 0");

        eq(g.onProbe(false, false, t += dt), ServeGuard.Action.NONE,
                "单次不通不算死（长任务会让 health 慢一拍）");
        eq(g.misses(), 1, "失败计数 = 1");

        eq(g.onProbe(true, false, t += dt), ServeGuard.Action.NONE, "通了 → 不动");
        eq(g.misses(), 0, "通了之后失败计数归零");

        eq(g.onProbe(false, true, t += dt), ServeGuard.Action.NONE, "第 1 拍不通（进程还在）");
        eq(g.onProbe(false, true, t += dt), ServeGuard.Action.NONE,
                "连续不通但进程还在 → 绝不重启（会砍断正在跑的会话）");
        eq(g.misses(), 2, "失败计数已够 2，但仍不拉");

        eq(g.onProbe(false, false, t += dt), ServeGuard.Action.RESTART,
                "连续不通且进程确认没了 → 拉起来");
        eq(g.misses(), 0, "拉过之后计数归零");

        eq(g.onProbe(false, false, t += dt), ServeGuard.Action.NONE, "冷却期第 1 拍");
        eq(g.onProbe(false, false, t += dt), ServeGuard.Action.NONE,
                "冷却期内不重复拉（起不来时别疯狂重试烧电）");
        eq(g.misses(), 2, "计数已够 2，但仍在冷却期");

        eq(g.onProbe(false, false, t + ServeGuard.MIN_RESTART_GAP_MS + 1),
                ServeGuard.Action.RESTART, "冷却结束且仍未起来 → 再拉一次");
    }

    /** 模拟 Android 的 app 私有目录：不允许硬链接。 */
    static final class NoHardlinkSink extends NioSink {
        @Override
        public boolean supportsHardlink() {
            return false;
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            throw new IOException("link failed: EACCES (Permission denied)");
        }
    }

    /**
     * 谎报支持硬链接、且 **link 失败时把源文件也删掉** 的 Sink。
     *
     * 不是臆造：2026-10-04 本机实测到这种文件系统行为 —— `Files.createLink` 抛
     * `Operation not permitted` 之后，源文件一起消失（`before: perl.exists=true`
     * → `after: perl.exists=false`）。当时 H 节 `testResume` 就是这么崩的：
     * `tryHardlink` 假设"失败无副作用"、退回 `copyFile`，而源已经被吃掉，
     * 于是报出一句与真相无关的 `no such file`，把真正原因藏了起来。
     *
     * 与 {@link NoHardlinkSink} 的区别：那个是"**提前承认**不支持"（走 copyFile，
     * 全绿）；这个是"**谎报**支持、真调 link 才炸"——`548eda3` 新加的回退分支，
     * 只有它能覆盖。
     */
    static final class HostileHardlinkSink extends NioSink {
        @Override
        public boolean supportsHardlink() {
            return true;                       // 谎报：探测在真机上"看起来"是支持的
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            //noinspection ResultOfMethodCallIgnored
            existing.delete();                 // 先吃掉源文件（复刻实测到的行为）
            throw new IOException("Operation not permitted");
        }
    }

    /**
     * D. 配置注入：覆盖写入 + 权限 0600 + 名字不许越界。
     * 真机背景：rootfs 里没有配置时 serve 停在 setup mode（configured:false），
     * 而配置只能由 App 自己铺进去。
     */
    static void testConfigInstall(File tmp) throws Exception {
        System.out.println("== D. 配置注入 ==");
        File home = new File(tmp, "rivet-home");
        home.mkdirs();
        File stale = new File(home, "provider-keys.json");
        writeText(stale, "STALE-CONTENT-THAT-IS-LONGER-THAN-THE-NEW-ONE");

        ConfigInstaller.Source src = new ConfigInstaller.Source() {
            @Override
            public String[] names() {
                return new String[]{"config.json", "provider-keys.json", ".token-key", "../evil", "a/b"};
            }

            @Override
            public InputStream open(String n) throws IOException {
                return new ByteArrayInputStream(("CONTENT-OF-" + n).getBytes(StandardCharsets.UTF_8));
            }
        };

        java.util.List<String> written = ConfigInstaller.install(src, home, new NioSink());

        eq(written.size(), 3, "只写扁平名字（config / provider-keys / .token-key）");
        ok(!written.contains("../evil"), "拒绝 ../evil");
        ok(!written.contains("a/b"), "拒绝带路径分隔符的名字");
        eq(readText(new File(home, "provider-keys.json")), "CONTENT-OF-provider-keys.json",
                "已存在的文件被覆盖（不是追加，旧的长内容没残留）");
        eq(readText(new File(home, ".token-key")), "CONTENT-OF-.token-key", "点开头的隐藏文件也写入");
        eq(modeOf(new File(home, ".token-key")), 0600, ".token-key 权限 0600");
        eq(modeOf(new File(home, "config.json")), 0600, "config.json 权限 0600");

        // 目标名 vs 包内名：aapt2 会丢弃 assets 里 . 开头的文件（实测），
        // 所以包里 .token-key 叫 token-key —— 这条映射写错会让 harness 一直停在 setup mode。
        eq(ConfigInstaller.assetNameFor(".token-key"), "token-key", "映射：.token-key -> token-key");
        eq(ConfigInstaller.assetNameFor("config.json"), "config.json", "映射：无点名字原样");
        eq(ConfigInstaller.CONFIG_FILES.size(), 4, "清单四项齐全");
        ok(ConfigInstaller.CONFIG_FILES.contains(".token-key"), "清单里必须含带点的 .token-key");

        // 用与 MainActivity 完全相同的规则再跑一遍：产出的必须是**带点**的 .token-key
        File home2 = new File(tmp, "rivet-home-2");
        home2.mkdirs();
        ConfigInstaller.Source bundled = new ConfigInstaller.Source() {
            @Override
            public String[] names() {
                return ConfigInstaller.CONFIG_FILES.toArray(new String[0]);
            }

            @Override
            public InputStream open(String targetName) throws IOException {
                return new ByteArrayInputStream(("BUNDLED-" + targetName).getBytes(StandardCharsets.UTF_8));
            }
        };
        ConfigInstaller.install(bundled, home2, new NioSink());
        File dotKey = new File(home2, ".token-key");
        eq(dotKey.isFile(), true, "按清单注入后落成的是 .token-key（带点）");
        eq(readText(dotKey), "BUNDLED-.token-key", "内容来自 open(目标名)");
        eq(new File(home2, "token-key").exists(), false, "不会多写出一个无点的 token-key");

        // ★ config.json 是**运行时状态文件**：已有内容时不许覆盖。
        // 真机踩过 —— /yes 开了全自动，重启被随包配置覆盖，又变回默认的「监督」。
        File home3 = new File(tmp, "rivet-home-3");
        home3.mkdirs();
        writeText(new File(home3, "config.json"), "USER-CHANGED");
        ConfigInstaller.install(bundled, home3, new NioSink());
        eq(readText(new File(home3, "config.json")), "USER-CHANGED",
                "★ config.json 已存在 → 不被覆盖（App 内改的审批模式要活过重启）");
        eq(readText(new File(home3, ".token-key")), "BUNDLED-.token-key",
                "其余三个密钥文件照旧写入（它们 App 内不改，要跟着包更新）");
    }

    private static int modeOf(File f) throws IOException {
        int m = 0;
        for (java.nio.file.attribute.PosixFilePermission p
                : java.nio.file.Files.getPosixFilePermissions(f.toPath())) {
            switch (p) {
                case OWNER_READ:    m |= 0400; break;
                case OWNER_WRITE:   m |= 0200; break;
                case OWNER_EXECUTE: m |= 0100; break;
                case GROUP_READ:    m |= 0040; break;
                case GROUP_WRITE:   m |= 0020; break;
                case GROUP_EXECUTE: m |= 0010; break;
                case OTHERS_READ:   m |= 0004; break;
                case OTHERS_WRITE:  m |= 0002; break;
                case OTHERS_EXECUTE:m |= 0001; break;
                default: break;
            }
        }
        return m;
    }

    private static void writeText(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    /**
     * E. SSE 解析 —— 用**真实抓到的报文**当基准（host/test/fixtures/sse-real-sample.txt，
     * 来自容器内跑通的一整轮对话），不是我手写的假数据。
     *
     * 要钉住的不变量：
     *   - `event:` / `data:` 成对，空行派发；`: ping` 注释行必须忽略
     *   - text_delta 增量拼接 = 完整回答
     *   - done 的 status、replay_window 的水位、phase 都能取出来
     */
    static void testSse(String fixturePath) throws Exception {
        System.out.println("== E. SSE 解析（真实报文夹具）==");
        File fx = new File(fixturePath);
        if (!fx.isFile()) {
            ok(false, "找不到夹具: " + fixturePath + "（用 cwd=" + new File(".").getAbsolutePath() + " 找过）");
            return;
        }

        // --- 最小 JSON 取值 ---
        eq(MiniJson.str("{\"text\":\"2\"}", "text"), "2", "取简单字符串");
        eq(MiniJson.str("{\"a\":1,\"text\":\"+2 等于 4。\",\"b\":2}", "text"), "+2 等于 4。", "取中间的字符串");
        eq(MiniJson.str("{\"text\":\"say \\\"hi\\\"\"}", "text"), "say \"hi\"", "处理转义引号");
        eq(MiniJson.str("{\"text\":\"a\\nb\"}", "text"), "a\nb", "处理转义换行");
        eq(MiniJson.str("{\"other\":\"x\"}", "text"), null, "键不存在返回 null");

        // --- 解析器基础行为 ---
        SseParser p = new SseParser();
        final java.util.List<String[]> got = new java.util.ArrayList<String[]>();
        SseParser.Handler h = new SseParser.Handler() {
            @Override public void onEvent(String event, String data) { got.add(new String[]{event, data}); }
        };
        p.feed("event: status", h);
        ok(got.isEmpty(), "只有 event 行还不派发（等空行）");
        p.feed("data: {\"a\":1}", h);
        ok(got.isEmpty(), "只有 data 行还不派发");
        p.feed("", h);
        eq(got.size(), 1, "空行才派发");
        eq(got.get(0)[0], "status", "事件名");
        eq(got.get(0)[1], "{\"a\":1}", "事件载荷");

        p.feed(": ping", h);
        p.feed("", h);
        eq(got.size(), 1, "注释行 : ping 被忽略，不产生事件");

        // 多行 data 按 SSE 规则用 \n 连接；没有 event: 时默认 message
        p.feed("data: line1", h);
        p.feed("data: line2", h);
        p.feed("", h);
        eq(got.size(), 2, "多行 data 派发一个事件");
        eq(got.get(1)[0], "message", "缺 event 时默认 message");
        eq(got.get(1)[1], "line1\nline2", "多行 data 用换行连接");

        // CRLF 也要吃
        p.feed("event: x\r", h);
        p.feed("data: y\r", h);
        p.feed("\r", h);
        eq(got.get(2)[1], "y", "CRLF 行尾也要处理");

        // --- 真实报文：整轮对话 ---
        SseParser real = new SseParser();
        final ConversationState st = new ConversationState();
        SseParser.Handler put = new SseParser.Handler() {
            @Override public void onEvent(String event, String data) { st.apply(event, data); }
        };
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(fx), StandardCharsets.UTF_8));
        String line;
        int raw = 0;
        while ((line = br.readLine()) != null) {
            raw++;
            real.feed(line, put);
        }
        br.close();
        real.flush(put);

        eq(st.eventCount, 25, "真实报文里 25 个事件（3 个 : ping 被丢掉，不计）");
        eq(st.answer.toString(), "2+2 等于 4。", "text_delta 拼接出完整回答");
        ok(st.thinking.length() > 100, "thinking_delta 累积到思考文本");
        eq(st.userText, "用一句话回答：2+2 等于几？", "回显用户输入");
        eq(st.doneStatus, "completed", "done 的 status");
        eq(st.lastSeq, 23, "最后事件序号");
        eq(st.floorSeq, 1, "replay_window 的 floorSeq（重连补历史用）");
        ok(st.phases.contains("working"), "phase 事件被记录");
        // 这条是界面能不能收尾的关键：SSE 是长连接，done 之后服务端不关连接，
        // 所以必须靠这个判断主动退出读流，否则界面永远停在「流式中」。
        eq(st.isFinished(), true, "收到 done 后 isFinished() 为真（读流据此收尾）");
        ok(st.turnComplete, "turn_complete 被标记");
    }

    /**
     * F. proot bind 参数拼装 —— 这一串决定 App 里的天枢看不看得见用户的代码。
     *
     * 不测"proot 认不认这条参数"（那是 host/probe-binds.sh 在容器里用真 proot 实测的），
     * 这里测的是**拼错了没有**：共享存储在时三条别名一条不少、不在时一条不留、
     * 工作区那一条永远在且指向 /root/workspace，且每个 -b 后面都跟着值。
     */
    static void testRuntimeBinds() {
        System.out.println("== F. proot bind 参数 ==");

        String shared = "/storage/emulated/0";
        String wsHost = shared + "/tianshu-workspace";

        List<String> full = RuntimeBinds.args(shared, wsHost);

        // 整体逐项相等（排序后），不做顺序假设 —— 顺序不影响 proot，只影响可读性
        List<String> got = new ArrayList<String>(full);
        Collections.sort(got);
        List<String> want = new ArrayList<String>(Arrays.asList(
                "-b", "-b", "-b", "-b",
                shared + ":/mnt/sdcard",
                shared + ":/sdcard",
                shared + ":/storage/emulated/0",
                wsHost + ":" + RuntimeBinds.GUEST_WORKSPACE));
        Collections.sort(want);
        eq(got, want, "完整参数序列逐项相等");

        eq(countOf(full, "-b"), RuntimeBinds.GUEST_SHARED.size() + 1, "-b 个数 = 挂载点个数");
        ok(!hasAdjacentFlag(full), "没有两个连续的 -b（值没漏）");
        eq(RuntimeBinds.GUEST_WORKSPACE, "/root/workspace", "工作区 guest 路径固定");
        ok(!containsValue(full, RuntimeBinds.GUEST_WORKSPACE), "guest 路径只以 host:guest 形式出现");

        // 共享存储不可用：一条共享存储挂载点都不留，工作区仍在（降级靠换 host，不靠少挂）
        List<String> noShared = RuntimeBinds.args(null, "/data/media/workspace");
        eq(noShared, Arrays.asList("-b", "/data/media/workspace:" + RuntimeBinds.GUEST_WORKSPACE),
                "共享存储不可用 → 只留工作区一条，且逐项相等");

        // null 与空串同等对待（Android 侧拿不到路径时给的是 null 或空串）
        eq(countOf(RuntimeBinds.args("", ""), "-b"), 0, "空串视为不可用");
        eq(countOf(RuntimeBinds.args(null, ""), "-b"), 0, "null 视为不可用");
        ok(RuntimeBinds.args(shared, null).contains(shared + ":" + RuntimeBinds.GUEST_SHARED.get(0)),
                "工作区为 null 不影响共享存储挂载");

        // 预建目录清单 —— 这道防线让 bind 不再依赖 proot 的 glue rootfs
        // （真机踩过：glue 建不出来时，中间目录缺失的 bind 会被静默丢掉）
        List<String> withShared = RuntimeBinds.guestDirsToCreate(true);
        for (String d : Arrays.asList("/mnt", "/mnt/sdcard", "/sdcard",
                "/storage", "/storage/emulated", "/storage/emulated/0",
                "/root", RuntimeBinds.GUEST_WORKSPACE)) {
            ok(withShared.contains(d), "预建清单含 " + d);
        }
        ok(new HashSet<String>(withShared).size() == withShared.size(), "预建清单无重复");
        for (String d : withShared) {
            ok(d.startsWith("/") && !d.endsWith("/"), "绝对路径且无尾斜杠：" + d);
        }

        // 不挂共享存储时连目录都不建 —— 否则 guest 里会多出三个空目录，
        // 看起来像"共享存储是空的"，而不是"没挂上"
        eq(RuntimeBinds.guestDirsToCreate(false),
                Arrays.asList("/root", RuntimeBinds.GUEST_WORKSPACE),
                "不挂共享存储时只预建工作区那条链");

        // 历史会话持久化：持久目录可用才挂，不可用则降级为现状（不崩）
        String sessHost = shared + "/tianshu-rivet/sessions";
        eq(RuntimeBinds.sessionArgs(sessHost),
                Arrays.asList("-b", sessHost + ":" + RuntimeBinds.GUEST_RIVET_SESSIONS),
                "持久目录可用 → 一条会话 bind，逐项相等");
        eq(countOf(RuntimeBinds.sessionArgs(null), "-b"), 0, "持久目录不可用 → 不挂（降级为现状）");
        eq(countOf(RuntimeBinds.sessionArgs(""), "-b"), 0, "空串同样视为不可用");
        eq(RuntimeBinds.GUEST_RIVET_SESSIONS, "/root/.rivet/sessions", "会话 guest 路径固定");
    }

    private static int countOf(List<String> list, String v) {
        int n = 0;
        for (String s : list) {
            if (v.equals(s)) n++;
        }
        return n;
    }

    private static boolean containsValue(List<String> list, String v) {
        for (String s : list) {
            if (v.equals(s)) return true;
        }
        return false;
    }

    /** -b 是带值的开关：出现两个连续的 -b，就说明有一条的值少了。 */
    private static boolean hasAdjacentFlag(List<String> list) {
        for (int i = 1; i < list.size(); i++) {
            if ("-b".equals(list.get(i)) && "-b".equals(list.get(i - 1))) return true;
        }
        return false;
    }

    /**
     * G. 会话列表解析 —— 跑在**真实** `GET /sessions` 响应上（fixtures/sessions-real.json，
     * 从手机上的 App serve 抓的原始字节，4 条会话、含嵌套 contextBudget 与中文标题）。
     *
     * 这个套件盯的是三个真会静默出错的点：13 位毫秒时间戳溢出、title 里的特殊字符
     * 把对象切错、脏输入把列表页搞崩。
     */
    static void testSessionList(String fixturePath) throws Exception {
        System.out.println("== G. 会话列表解析 ==");

        String json = readText(new File(fixturePath));
        List<SessionList.Item> items = SessionList.parse(json);

        eq(items.size(), 4, "真实响应里解析出 4 条");
        if (items.size() == 4) {
            eq(items.get(0).id, "20261001847bc0d16e7d", "第一条 id");
            eq(items.get(0).title, "回答 2+2 等于几", "第一条 title（中文逐字）");
            eq(items.get(0).status, "completed", "第一条 status");
            eq(items.get(0).lastSeq, 28, "第一条 lastSeq");
            eq(items.get(3).title, "摸金校尉项目只读体检报告", "最后一条 title");
            eq(items.get(0).domainGlyph, "☥", "越过嵌套对象后取值仍正确");
            for (SessionList.Item it : items) {
                ok(it.createdAt > 1000000000000L, "createdAt 是 13 位毫秒而非 null：" + it.id);
            }
        } else {
            // 不静默跳过：条数一错就明确报一条，且别让 get(i) 把整套带崩
            ok(false, "条数不是 4，逐条断言无法执行");
        }

        // 13 位毫秒装不进 Integer —— 这就是必须补 longNum 的原因，用断言把坑钉住
        eq(MiniJson.longNum(json, "createdAt"), 1790871608499L, "longNum 取得到 13 位毫秒");
        ok(MiniJson.num(json, "createdAt") == null,
                "对照：Integer 版 num 对 13 位毫秒返回 null（静默丢时间戳的根源）");
        eq(MiniJson.longNum("{\"lastSeq\":28}", "lastSeq"), 28L, "longNum 取短整数");
        eq(MiniJson.longNum("{\"a\":\"x\"}", "a"), null, "longNum 对字符串值返回 null");
        eq(MiniJson.longNum("{}", "x"), null, "longNum 缺键返回 null");

        // 空/脏输入一律空表，不抛
        eq(SessionList.parse("{\"sessions\":[]}").size(), 0, "空数组 → 0 条");
        eq(SessionList.parse("{}").size(), 0, "没有 sessions 键 → 0 条");
        eq(SessionList.parse(null).size(), 0, "null 输入 → 0 条");
        eq(SessionList.parse("not json at all").size(), 0, "垃圾输入 → 0 条，不抛异常");
        // 真正的截断：对象中途断掉（字符串都没闭合），这条残条必须被丢掉
        eq(SessionList.parse("{\"sessions\":[{\"id\":\"a\",\"title\":\"半途而").size(), 0,
                "对象中途截断 → 丢掉残条，0 条");
        // 数组没闭合、但最后一条对象本身是完整的 —— 保留它（残的才丢，好的不丢）
        eq(SessionList.parse("{\"sessions\":[{\"id\":\"a\"}").size(), 1,
                "数组未闭合但对象完整 → 保留 1 条");

        // 合成边界用例：title 里带 } 和转义引号，切对象时必须认得字符串边界
        String tricky = "{\"sessions\":[{\"id\":\"a\",\"title\":\"带 } 和 \\\" 引号\",\"lastSeq\":1},"
                + "{\"id\":\"b\",\"title\":\"二\",\"lastSeq\":2}]}";
        List<SessionList.Item> t = SessionList.parse(tricky);
        eq(t.size(), 2, "title 含 } 与转义引号时仍切对对象数");
        if (t.size() == 2) {
            eq(t.get(0).title, "带 } 和 \" 引号", "转义引号被还原");
            eq(t.get(1).id, "b", "第二条仍解析正确");
        } else {
            ok(false, "tricky 用例条数不是 2，逐条断言无法执行");
        }

        // 绝对时间：格式钉死（用户真机反馈「不要显示多久以前」）。
        // 固定 now/then 两个时刻，结果与时区无关地可预期。
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.clear();
        n.set(2026, java.util.Calendar.JUNE, 15, 12, 0, 0);
        long nowF = n.getTimeInMillis();
        java.util.Calendar ta = java.util.Calendar.getInstance();
        ta.clear();
        ta.set(2026, java.util.Calendar.JUNE, 15, 6, 21, 0);
        java.util.Calendar tb = java.util.Calendar.getInstance();
        tb.clear();
        tb.set(2026, java.util.Calendar.OCTOBER, 2, 23, 11, 0);
        java.util.Calendar tc = java.util.Calendar.getInstance();
        tc.clear();
        tc.set(2025, java.util.Calendar.DECEMBER, 31, 8, 0, 0);
        eq(SessionList.absTime(nowF, ta.getTimeInMillis()), "今天 06:21", "同一天 → 今天 HH:mm");
        eq(SessionList.absTime(nowF, tb.getTimeInMillis()), "10-02 23:11", "同年不同天 → MM-DD HH:mm");
        eq(SessionList.absTime(nowF, tc.getTimeInMillis()), "2025-12-31 08:00", "跨年 → 带年份");
        ok(SessionList.absTime(nowF, tb.getTimeInMillis()).indexOf("前") < 0,
                "不再出现「…前」这种相对说法");

        // 会话状态：harness 英文枚举 → 中文；未知值原样回显（不吞成空串）
        eq(SessionList.statusLabel("completed"), "已完成", "completed → 已完成");
        eq(SessionList.statusLabel("aborted"), "已中止", "aborted → 已中止");
        eq(SessionList.statusLabel("interrupted"), "已中断", "interrupted → 已中断");
        eq(SessionList.statusLabel("failed"), "失败", "failed → 失败");
        eq(SessionList.statusLabel(" aborted "), "已中止", "两侧空白被 trim");
        eq(SessionList.statusLabel("weird-state"), "weird-state", "未知状态原样回显");
        eq(SessionList.statusLabel(null), "", "null → 空串");
        eq(SessionList.statusLabel("  "), "", "纯空白 → 空串");

        // 列表分段（用户要的分类：会话中 / 未归档 / 已归档）
        SessionList.Item run = SessionList.parse(
                "{\"sessions\":[{\"id\":\"r\",\"status\":\"running\",\"archived\":false}]}").get(0);
        SessionList.Item idle = SessionList.parse(
                "{\"sessions\":[{\"id\":\"i\",\"status\":\"completed\"}]}").get(0);
        SessionList.Item arch = SessionList.parse(
                "{\"sessions\":[{\"id\":\"a\",\"status\":\"completed\",\"archived\":true}]}").get(0);
        eq(SessionList.sectionOf(run), SessionList.SEC_RUNNING, "running → 会话中");
        eq(SessionList.sectionOf(idle), SessionList.SEC_ACTIVE, "completed 未归档 → 未归档");
        eq(SessionList.sectionOf(arch), SessionList.SEC_ARCHIVED, "archived=true → 已归档");
        eq(SessionList.sectionOf(null), SessionList.SEC_ACTIVE, "null → 兜底未归档");
        eq(SessionList.SECTION_ORDER.length, 3, "三段：会话中 · 未归档 · 已归档");
        eq(SessionList.SECTION_ORDER[0], SessionList.SEC_RUNNING, "第一段是会话中");
        eq(SessionList.SECTION_ORDER[2], SessionList.SEC_ARCHIVED, "最后一段是已归档");
        // 已归档优先于"在跑"：归档中的会话即便 status 还是 running，也该落在已归档段
        SessionList.Item archRun = SessionList.parse(
                "{\"sessions\":[{\"id\":\"ar\",\"status\":\"running\",\"archived\":true}]}").get(0);
        eq(SessionList.sectionOf(archRun), SessionList.SEC_ARCHIVED, "已归档优先于会话中");
    }

    /**
     * H. 断点续传 —— 中断过的 staging 下次接着装，而不是整体重来。
     *
     * 三条必须成立的东西：
     *   1. ★ 目标目录只在**全部装完**后出现（"绝不把半个 rootfs 当好的"这条不变量不能因为
     *      加了续传而破掉）
     *   2. 续传后的内容与一次性装完**逐文件一致**
     *   3. 指纹不一致（换了包）必须重来 —— 新旧混装的 rootfs 比重新装一遍糟得多
     */
    static void testResume(File tmp) throws Exception {
        System.out.println("== H. 断点续传 ==");

        File pkg = new File(tmp, "resume.tar.xz");
        xz(tar(
                header("bin/", 0755, 0, '5', null),
                fileEntry("bin/sh", "#!/bin/sh\n", 0755),
                header("bin/sh.link", 0777, 0, '2', "sh"),
                fileEntry("etc/os-release", "NAME=Ubuntu\n", 0644),
                fileEntry("usr/bin/perl", "PERL", 0755),
                header("usr/bin/perl5", 0755, 0, '1', "usr/bin/perl"),
                fileEntry("etc/motd", "hi\n", 0644)), pkg);
        final String FP = "1790873298:93762627";

        // 基线：一次性装完
        File baseDest = new File(tmp, "base-dest"), baseStaging = new File(tmp, "base-partial");
        FlakySink baseSink = new FlakySink();
        eq(RootfsInstaller.install(pkg, baseDest, baseStaging, baseSink, null, FP).installed,
                true, "基线：一次性装完");
        int fullCreates = baseSink.creates;
        ok(fullCreates >= 4, "基线 create 次数 = " + fullCreates);

        // 第一次：装到一半崩
        File dest = new File(tmp, "r-dest"), staging = new File(tmp, "r-partial");
        FlakySink s1 = new FlakySink();
        s1.failAfter = 2;
        ok(installThrows(pkg, dest, staging, s1, FP), "第一次装到一半抛错");
        eq(dest.exists(), false, "★ 硬不变量：中断后目标目录绝不出现");
        eq(staging.isDirectory(), true, "staging 保留下来（可供续传）");

        // 第二次：同指纹续传
        FlakySink s2 = new FlakySink();
        eq(RootfsInstaller.install(pkg, dest, staging, s2, null, FP).installed, true, "续传后装完");
        ok(s2.creates < fullCreates,
                "★ 续传只写了剩下的（create " + s2.creates + " < 全新 " + fullCreates + "）");
        for (String p : new String[]{"bin/sh", "etc/os-release", "usr/bin/perl", "etc/motd"}) {
            eq(readText(new File(dest, p)), readText(new File(baseDest, p)), "续传后 " + p + " 与基线一致");
        }
        eq(isSymlink(new File(dest, "bin/sh.link")), true, "续传后符号链接正确");
        eq(readText(new File(dest, "usr/bin/perl5")), "PERL", "硬链接回退复制内容一致");
        ok(!new File(tmp, "r-partial.resume").exists(), "成功后续传标记已清掉");
        File[] rootEntries = dest.listFiles();
        boolean stray = false;
        if (rootEntries != null) {
            for (File f : rootEntries) if (f.getName().endsWith(".resume")) stray = true;
        }
        ok(!stray, "★ rootfs 里没有被误带进去的 .resume 文件");

        // 最刁的一条：符号链接**刚建好就崩**（文件已在盘上，但没记进续传标记）
        // —— 续传时若直接重建会撞 EEXIST，必须先把残条目清掉
        File dest4 = new File(tmp, "r4-dest"), staging4 = new File(tmp, "r4-partial");
        FlakySink sp = new FlakySink();
        sp.failAfterSymlink = 1;
        ok(installThrows(pkg, dest4, staging4, sp, FP), "符号链接刚建好就崩");
        eq(isSymlink(new File(staging4, "bin/sh.link")), true, "崩溃点确实留下了符号链接残条目");
        eq(RootfsInstaller.install(pkg, dest4, staging4, new FlakySink(), null, FP).installed,
                true, "★ 残条目在盘上也能续完（不会被 EEXIST 绊倒）");
        eq(isSymlink(new File(dest4, "bin/sh.link")), true, "续完后符号链接仍正确");

        // 指纹不一致 → 必须重来
        File dest2 = new File(tmp, "r2-dest"), staging2 = new File(tmp, "r2-partial");
        FlakySink sx = new FlakySink();
        sx.failAfter = 2;
        installThrows(pkg, dest2, staging2, sx, FP);
        FlakySink s3 = new FlakySink();
        RootfsInstaller.install(pkg, dest2, staging2, s3, null, "换了个包:999");
        eq(s3.creates, fullCreates, "★ 指纹不一致 → 重新全装（create " + s3.creates + "）");

        // 老调用方（fingerprint=null）→ 永不续传，行为与改动前一致
        File dest3 = new File(tmp, "r3-dest"), staging3 = new File(tmp, "r3-partial");
        FlakySink sn = new FlakySink();
        sn.failAfter = 2;
        installThrows(pkg, dest3, staging3, sn, null);
        FlakySink s4 = new FlakySink();
        RootfsInstaller.install(pkg, dest3, staging3, s4, null, null);
        eq(s4.creates, fullCreates, "fingerprint=null → 整体重来");
    }

    /**
     * B2''. **续传路径**也必须挡住"穿过符号链写到外面" —— B2'② 的续传版。
     *
     * 钉的是一个真实的防御缺口（2026-10-05 走查核出）：`hasSymlinkAncestor` 只看**本次运行**
     * 建过的符号链（symlinkPaths），而续传时 `entries <= resumeFrom` 的条目直接 `continue`，
     * 前缀里的符号链不会进集合 —— 于是防御只在"一次装完"时成立。归档里放
     * `esc -> ../outside` 再放 `esc/pwned`：首次能挡住，恰在两者之间中断后续传就挡不住，
     * `mkdirs(stagingDir/esc)` 会顺着符号链把文件写到目标目录**之外**。
     *
     * 构造：手工复原"第一次装到第 1 条（那条符号链）之后就崩了"的盘上状态（链已落盘、
     * 续传标记记到第 1 条），再跑一次续传 —— 第 2 条 `esc/pwned` 必须被拦。
     *
     * 这条断言在**修之前是红的**（越界确实发生），修完才绿。
     */
    static void testResumeSymlinkEscape(File tmp) throws Exception {
        System.out.println("== B2''. 续传时也不许穿过符号链越界 ==");

        File pkg = new File(tmp, "esc1.tar.xz");
        File outsideDir = new File(tmp, "esc1-outside");
        java.nio.file.Files.createDirectories(outsideDir.toPath());   // 外面那个目录先存在
        xz(tar(
                header("esc", 0777, 0, '2', "../esc1-outside"),
                fileEntry("esc/pwned", "ESCAPED", 0644)), pkg);

        File dest = new File(tmp, "esc1-dest");
        File staging = new File(tmp, "esc1-partial");
        final String FP = "2026-10-05:resume-escape";

        ok(staging.mkdirs(), "前置：建出 staging");
        new NioSink().symlink("../esc1-outside", new File(staging, "esc"));
        eq(isSymlink(new File(staging, "esc")), true, "前置：staging 里已有那条符号链");
        RootfsInstaller.writeMarker(RootfsInstaller.markerFor(staging), FP, 1);

        RootfsInstaller.Result r =
                RootfsInstaller.install(pkg, dest, staging, new NioSink(), null, FP);

        eq(r.installed, true, "续传后装完");
        eq(r.resumedFrom, 1L, "确实走了续传（从第 1 条接着装）");
        eq(new File(outsideDir, "pwned").exists(), false,
                "★ 不得穿过前缀里的符号链写到目标目录之外");
        eq(isSymlink(new File(dest, "esc")), true, "符号链本身仍被正确重建");

        // 对照：一次性装完（没有续传）本来就被挡住 —— 续传的语义必须与它一致。
        //
        // ⚠️ 必须用**独立的**归档与外部目录。第一版图省事复用了上面那份，于是撤掉修复之后
        // 这一行**跟着一起红** —— 它读到的是上一次越界已经写出去的那个文件，根本没在测
        // "一次性装完"这条路。对照断言必须是独立的观测点。
        File pkg2 = new File(tmp, "esc2.tar.xz");
        File outsideDir2 = new File(tmp, "esc2-outside");
        java.nio.file.Files.createDirectories(outsideDir2.toPath());
        xz(tar(
                header("esc", 0777, 0, '2', "../esc2-outside"),
                fileEntry("esc/pwned", "ESCAPED", 0644)), pkg2);
        RootfsInstaller.install(pkg2, new File(tmp, "esc2-dest"),
                new File(tmp, "esc2-partial"), new NioSink(), null, "esc2-fp");
        eq(new File(outsideDir2, "pwned").exists(), false,
                "对照：一次性装完就拦得住（续传必须与它一致）");
    }

    /**
     * 修复回归守卫 —— 源码**结构**断言。
     *
     * 为什么只能钉结构：这一批修的都是"Android 运行时里才会发生"的行为（Activity 生命周期、
     * 配置变更重建、HTTP 409、跨进程残留 serve），而本测试跑在**普通 JVM** 上、没有 Android
     * 运行时 —— 它们的行为复现只能在真机上做。
     *
     * 这不是行为证明，但能保证"修过的地方不被无声改回去"；与既有的 testJumpToLatest /
     * testSheetScrollable 同一路数（都是 sourceOf + contains）。真机行为另记在交付说明里。
     */
    static void testFixRegressions() throws Exception {
        System.out.println("== 修复回归守卫（源码结构）==");

        String mainCode = codeOf("MainActivity");
        ok(mainCode != null && mainCode.contains("protected void onDestroy()"),
                "启动页有 onDestroy（每秒自续的心跳要在这里摘）");
        // 钉**方法体**，不钉「文件里出现过这几个字」—— 同 C1 那轮的教训：
        // 一个字符串在文件里出现，证明不了它会被调到（也可能落在别的方法里，或干脆是死代码）。
        // V2 的行为（退出后不崩/不发热）只能在真机复现（见清单 §V2），结构这层就把它锚到调用链上。
        String onDestroyBody = mainCode == null ? null : methodBody(mainCode, "protected void onDestroy()");
        ok(onDestroyBody != null && onDestroyBody.contains("removeCallbacks(ticker)"),
                "onDestroy 的**方法体**里摘掉 ticker（不只是文件里出现过）");
        String tickerBody = mainCode == null ? null : methodBody(mainCode, "private final Runnable ticker");
        ok(tickerBody != null && tickerBody.contains("destroyed"),
                "ticker 的**方法体**里认 destroyed 标志（堵住 onDestroy 之后才 post 出的那一拍）");
        ok(mainCode != null && mainCode.contains("if (firstRun) {"),
                "空间预检收在 firstRun 之内（热启动不该被它挡下）");
        ok(mainCode != null && mainCode.contains("onConfigurationChanged"),
                "接管 configChanges 后补了 onConfigurationChanged（外观仍会重贴）");
        // 遗留①：onDestroy 之后 boot 线程还在跑，任何**一次性** UI 投递都不能裸投 ——
        // 裸 ui.post 会把回调排进主线程队列后照常去改一棵已经没了的视图树。
        //（ticker 的自续 postDelayed 不在此列：它由 onDestroy 的 removeCallbacks 摘掉，
        //  且包成 postUi 反而会让 removeCallbacks(ticker) 摘不干净 —— 见 BaseActivity.postUi。）
        ok(mainCode != null && !mainCode.contains("ui.post("),
                "MainActivity 不再裸投 ui.post（一次性投递一律走带存活守卫的 postUi）");
        int postUiN = occur(mainCode, "postUi(");
        ok(postUiN >= 6, "postUi 覆盖了各条后台回调（实际 " + postUiN + " 处）");
        ok(mainCode != null && mainCode.contains("if (!alive()) return;"),
                "延迟交棒也补了存活判断（postUi 没有 delay 变体，这条只能自己守）");

        String modelsCode = codeOf("ModelsActivity");
        ok(modelsCode != null && modelsCode.contains("reqGen.incrementAndGet()"),
                "reqGen 真的被 increment —— 不再是只声明不用的死字段");
        ok(modelsCode != null && modelsCode.contains("reqGen.get()"),
                "ok/fail 回调里真的比对了 reqGen（慢响应不覆盖新结果）");

        String chatCode = codeOf("ChatActivity");
        ok(chatCode != null && chatCode.contains("private boolean resumeSend("),
                "resumeSend 返回「送出去没有」（续聊失败要能撤气泡）");
        ok(chatCode != null && chatCode.contains("if (!resumeSend("),
                "send() 按返回值决定是否撤回乐观气泡");

        String setupCode = codeOf("SetupActivity");
        ok(setupCode != null && setupCode.contains("exitedWithin(p, 600)"),
                "首次配置页起 serve 后也做早退自检（消除假就绪）");
        ok(setupCode != null && setupCode.contains("\"setup-restart-serve\""),
                "重启 serve（含 2s + 0.6s 两处等待）跑在后台线程上，不占 UI 线程");
        // 遗留②：旧进程还没放开端口时，新 serve 会以 EADDRINUSE 秒退 —— 那不是"残留 serve"，
        // 只是慢了一拍。判据必须能再试一次，而不是直接报失败。
        int launchN = occur(setupCode, "RuntimeHost.launchServe(host)");
        ok(launchN >= 2, "重启 serve 在早退后会再试一次（实际 launchServe " + launchN + " 次）");

        String rootfsCode = codeOf("RootfsInstaller");
        ok(rootfsCode != null && rootfsCode.contains("synchronized (INSTALL_LOCK)"),
                "安装全局互斥（并发 boot 不交错写盘）");
        ok(rootfsCode != null && !rootfsCode.contains("INSTALL_LOCKS"),
                "不留「永不清理」的锁映射表（一把静态锁就够；删条目反而会破坏互斥）");

        String manifest = readText(new File("AndroidManifest.xml"));
        ok(manifest != null && manifest.contains("android:configChanges="),
                "启动页声明 configChanges（不被配置变更重建）");
    }

    /**
     * **构建契约**：manifest 里 `configChanges` 的每个 flag，都必须是 API 23 白名单内的。
     *
     * 为什么必须有这一条：`javac` 全绿 + `HostTest` 全绿**证明不了能出包**。
     * `build.sh` 第 4 步用 android-23 的 android.jar 做 `aapt2 link -I`，manifest 里只要出现
     * 一个 API 23 还不认识的 flag，aapt2 会**拒掉整份 manifest**、构建死在 link 阶段。
     *
     * 2026-10-05 就是这么翻的车：给 MainActivity 加 `density`（API 24 才加入的 flag），
     * 编译 exit 0、2116 条断言全绿，而 `sh host/build.sh` 直接挂。
     * 那一轮我在容器里只跑了 javac + HostTest，**没跑 build.sh** —— 这条断言把那个盲区补上。
     *
     * 白名单取自 API 23 平台原文：`<platform>/data/res/values/attrs_manifest.xml` 里
     * `configChanges` 的 `<flag>` 列表，实测共 14 个。换 platform jar 时要同步改这份表。
     */
    static void testManifestConfigChanges() throws Exception {
        System.out.println("== 构建契约：configChanges 必须是 API 23 白名单内的 flag ==");

        final java.util.Set<String> API23 = new java.util.HashSet<String>(java.util.Arrays.asList(
                "mcc", "mnc", "locale", "touchscreen", "keyboard", "keyboardHidden",
                "navigation", "orientation", "screenLayout", "uiMode", "screenSize",
                "smallestScreenSize", "layoutDirection", "fontScale"));

        String manifest = readText(new File("AndroidManifest.xml"));
        ok(manifest != null, "读得到 AndroidManifest.xml（cwd=" + new File(".").getAbsolutePath() + "）");
        if (manifest == null) return;

        java.util.List<String> values = new java.util.ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("android:configChanges=\"([^\"]*)\"").matcher(manifest);
        while (m.find()) values.add(m.group(1));

        ok(!values.isEmpty(), "启动页声明了 configChanges（这是「启动页不被重建」的载体）");
        for (String v : values) {
            for (String raw : v.split("\\|")) {
                String flag = raw.trim();
                if (flag.isEmpty()) continue;
                ok(API23.contains(flag), "flag「" + flag + "」在 API 23 白名单内"
                        + "（不在 ⇒ aapt2 拒整份 manifest，是**构建**失败而不是运行时问题）");
            }
        }
        // 反面钉一条：这个 flag 最容易"顺手加回来"，它正是那次构建阻断的元凶
        ok(values.get(0) == null || !values.get(0).contains("density"),
                "不含 density —— 它是 API 24 才加入的 flag，加了 build.sh 必挂");
    }

    /**
     * P′. 出包身份：版本号与启动图标。
     *
     * 用户 2026-10-06：「把应用版本改为 2.8.0，把图标换成 …tianshu_icon.jpg」。
     *
     * 这两样都**不在 Java 代码里**（版本号是 `aapt2 link` 的参数，图标是 manifest 的属性 + res 里的位图），
     * 所以它们坏起来是**静默**的：版本名写回去只是显示旧号，`android:icon` 被删掉就退回系统默认图标
     * —— 构建照样成功、其余测试照样全绿。这条把它钉成机器可查的。
     */
    static void testBuildIdentity() throws Exception {
        System.out.println("== P′. 出包身份（版本号 / 启动图标）==");

        String sh = readText(new File("build.sh"));
        ok(sh != null && !sh.isEmpty(), "读得到 build.sh");
        if (sh != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("--version-name\\s+\"?([^\"\\s]+)\"?").matcher(sh);
            String vname = m.find() ? m.group(1) : null;
            eq(vname, "2.8.0", "版本名（aapt2 link --version-name）");

            // versionCode 必须**单调递增**才能覆盖安装：设备上现装的是 1791253731（epoch 秒）。
            // 谁把它改成 20800 这种"看着更像版本号"的固定值，覆盖安装就会被系统拒
            //（INSTALL_FAILED_VERSION_DOWNGRADE），只能卸载重装 —— 而卸载会抹掉 App 私有目录里的
            // rootfs 与全部会话历史。所以 versionCode 与 versionName 分开盯。
            ok(sh.contains("--version-code \"$BUILD_CODE\""), "versionCode 仍由 $BUILD_CODE 给（不写死）");
            ok(sh.contains("BUILD_CODE=$(date +%s)"), "BUILD_CODE = epoch 秒（恒增 ⇒ 永远装得上）");
        }

        String manifest = readText(new File("AndroidManifest.xml"));
        ok(manifest != null, "读得到 AndroidManifest.xml");
        if (manifest != null) {
            ok(manifest.contains("android:icon=\"@drawable/app_icon\""),
                    "application 带启动图标 @drawable/app_icon");
            // roundIcon 是 API 25 才有的属性，而本工程用 android-23 的 android.jar 做 aapt2 link ——
            // 多一个它不认识的属性会**拒掉整份 manifest**（density 那次就是这样）。
            ok(!manifest.contains("roundIcon"), "不写 roundIcon（android-23 的 aapt2 不认，会拒整份 manifest）");
        }

        // 图标资源本身：真在 res 里、后缀与**实际格式**一致、别小得糊。
        // 后缀那条是踩出来的：用户给的文件叫 `tianshu_icon.jpg`，内容其实是 PNG（外加 APNG 的
        // acTL/fcTL chunk）—— aapt2 按**后缀**决定怎么处理，名实不符是隐患，所以入库时按真实格式改名。
        File icon = new File("res/drawable-nodpi/app_icon.png");
        ok(icon.isFile(), "res/drawable-nodpi/app_icon.png 在（启动图标入库）");
        int[] wh = icon.isFile() ? pngSize(icon) : null;
        ok(wh != null, "图标是标准 PNG（签名 89 50 4E 47 对得上 —— 后缀与真实格式一致）");
        if (wh != null) {
            ok(wh[0] >= 192 && wh[1] >= 192,
                    "图标不小于 192×192（实测 " + wh[0] + "×" + wh[1] + "）");
        }
    }

    /** 读 PNG 的宽高（只认标准 PNG：8 字节签名 + IHDR）；不是 PNG / 读不了返回 null。 */
    private static int[] pngSize(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[24];
            int n = in.read(h);
            if (n < 24) return null;
            if ((h[0] & 0xFF) != 0x89 || h[1] != 'P' || h[2] != 'N' || h[3] != 'G') return null;
            int w = ((h[16] & 0xFF) << 24) | ((h[17] & 0xFF) << 16) | ((h[18] & 0xFF) << 8) | (h[19] & 0xFF);
            int hh = ((h[20] & 0xFF) << 24) | ((h[21] & 0xFF) << 16) | ((h[22] & 0xFF) << 8) | (h[23] & 0xFF);
            return new int[]{w, hh};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * C1 的结构守卫 —— 钉的是**链路**，不是"某处存在 `applyStatusBar`"。
     *
     * 为什么不能只钉存在：C3 那轮就吃过这个亏 —— 断言只证明"代码里有这一段"，
     * 证明不了"它会被调到"。C1 的病根恰恰是：**实现一直在，但只在 `onCreate` 调一次**，
     * 于是切主题/被 configChanges 拦下重建之后，状态栏图标就再也不跟着变了。
     * 所以这里把 `Theming.refresh` 的**方法体**取出来，要求 body 里出现该调用 ——
     * 只要有人把调用挪回 onCreate、或搬出 refresh，这条就红。
     */
    static void testStatusBarFollowsTheme() throws Exception {
        System.out.println("== 状态栏图标必须跟着主题刷新（钉链路）==");

        String theming = codeOf("Theming");
        ok(theming != null, "读得到 Theming.java");
        if (theming == null) return;

        String body = methodBody(theming, "static void refresh(");
        ok(body != null, "取到 Theming.refresh 的方法体");
        if (body != null) {
            ok(body.contains("applyStatusBar("),
                    "Theming.refresh 的方法体里**必须**调 applyStatusBar —— "
                            + "否则切主题（或启动页被 configChanges 拦下）之后状态栏图标不跟着变");
        }

        // 反向两条：实现只能有一份，且不留在 BaseActivity（C1 之前正是 BaseActivity 私自持有、
        // 只在 onCreate 调一次 —— 两份实现也是"改一处忘一处"的老病根）
        ok(theming.contains("setSystemUiVisibility"),
                "实现确实落在 Theming.applyStatusBar 里");
        String base = codeOf("BaseActivity");
        ok(base != null && !base.contains("setSystemUiVisibility"),
                "BaseActivity 不再自己写一份状态栏实现（只转发，避免两处各写一遍又漂）");
    }

    /**
     * 取某个方法的方法体 —— 从签名之后的第一个 `{` 到与它配对的 `}`。
     *
     * 够结构守卫用即可：**不处理字符串/字符字面量里的花括号**（被检查的方法体里没有），
     * 也不处理嵌套类型。传入的 `src` 应当已 `stripComments`（注释里的旧写法不算数）。
     */
    /**
     * **C6 回归** —— 设了背景图时，护栏必须按「scrim over 照片」的两端求解 / 判定。
     *
     * 病根：`Theme.audit()` 一律以 `p.bg` 为底，等于假设"照片不存在"；而 `Theming.backdrop()`
     * 实际叠了三层（渐变 → `CoverBackdrop(照片)` → `scrim`），`scrim = alpha(p.bg, 0.76)`
     * 只把照片衰减到 **24%** ⇒ 页面底随照片在两端之间漂移。
     * 实测：7 预设 × 8 主色 × 4 档 α = **224 组合**，
     * 护栏**全绿**、而真实最坏**全部 < 4.5**（最差 2.468）。
     *
     * 本测试**自己重算两端**（0.76 写死在这里，不引用 Theme 的常量），所以验的是物理事实，
     * 不是"实现和它自己一致"。
     */
    static void testContrastWithBackgroundImage() {
        System.out.println("== C6：设背景图后，文字必须对「照片两端」也达标 ==");

        final double SCRIM = 0.76;
        final String IMG = "/probe/bg.jpg";
        final double[] ALPHAS = {1.0, 0.65, 0.35, 0.1};
        final String[] NAMES = {"fg@页-黑端", "fg@页-白端", "sub@页-黑端", "sub@页-白端",
                "priInk@页-黑端", "priInk@页-白端", "noteFg@页-黑端", "noteFg@页-白端",
                "fg@卡-黑端", "fg@卡-白端", "sub@卡-黑端", "sub@卡-白端",
                "priInk@卡-黑端", "priInk@卡-白端"};
        int[] bad = new int[NAMES.length];
        String[] first = new String[NAMES.length];
        int auditBad = 0, combos = 0;
        String auditFirst = "";

        for (Theme.Preset p : Theme.PRESETS) {
            for (Theme.Accent a : Theme.ACCENTS) {
                for (double al : ALPHAS) {
                    combos++;
                    Theme.Config cfg = Theme.normalize(
                            new Theme.Config(p.id, a.id, Theme.DEFAULT_BG, "", IMG, al));
                    Theme.Tokens t = Theme.tokens(cfg);

                    String[] page = {
                            Theme.mix(p.bg, "#000000", 1 - SCRIM),
                            Theme.mix(p.bg, "#ffffff", 1 - SCRIM)};
                    String[] card = {
                            al >= 1 ? p.card : Theme.mix(p.card, page[0], 1 - al),
                            al >= 1 ? p.card : Theme.mix(p.card, page[1], 1 - al)};

                    String who = p.id + "/" + a.id + " α=" + al;
                    String[] fs = {t.fg, t.sub, t.priInk, t.noteFg};
                    for (int i = 0; i < 2; i++) {
                        for (int k = 0; k < 4; k++) {
                            int idx = k * 2 + i;
                            double r = Theme.contrast(fs[k], page[i]);
                            if (r + Theme.CONTRAST_EPS < Theme.AA_TEXT) {
                                bad[idx]++;
                                if (first[idx] == null) {
                                    first[idx] = who + " 实际 " + String.format("%.3f", r) + "（底 " + page[i] + "）";
                                }
                            }
                        }
                        for (int k = 0; k < 3; k++) {
                            int idx = 8 + k * 2 + i;
                            double r = Theme.contrast(fs[k], card[i]);
                            if (r + Theme.CONTRAST_EPS < Theme.AA_TEXT) {
                                bad[idx]++;
                                if (first[idx] == null) {
                                    first[idx] = who + " 实际 " + String.format("%.3f", r) + "（底 " + card[i] + "）";
                                }
                            }
                        }
                    }
                    if (!Theme.audit(cfg).isEmpty()) {
                        auditBad++;
                        if (auditFirst.isEmpty()) auditFirst = who + " -> " + Theme.auditReport(cfg);
                    }
                }
            }
        }

        for (int i = 0; i < NAMES.length; i++) {
            ok(bad[i] == 0, NAMES[i] + " 不达标 " + bad[i] + " 格"
                    + (first[i] == null ? "" : "；首个：" + first[i]));
        }
        ok(auditBad == 0, "带图配置的护栏必须 GREEN（违例 " + auditBad + " 个）"
                + (auditFirst.isEmpty() ? "" : "；首个：" + auditFirst));
        ok(combos == Theme.PRESETS.size() * Theme.ACCENTS.size() * ALPHAS.length,
                "覆盖全部带图组合（" + combos + " = " + Theme.PRESETS.size() + "×"
                        + Theme.ACCENTS.size() + "×" + ALPHAS.length + "）");
    }

    /**
     * 抠出一个方法**的方法体**（花括号配平）；找不到返回 null。
     *
     * 为什么需要它：源码级守卫常要问"这个方法**自己**有没有干某事"。直接在整份源码上 `contains`，
     * 会被**别处**的同类调用绊倒 —— 真踩过：设置页那个 `onBackPressed` 覆盖的 catch 分支里也有一句
     * `super.onBackPressed()`，于是"覆盖了就必须交回 super"这条族级断言放过了它（见 `审计.md §11.6`）。
     *
     * ⚠️ 用法与局限：
     *   - 传进来的源码**先 `stripComments`** —— 否则 `{@code …}` 里的花括号会把配平带偏；
     *   - `sig` 取**第一处**出现（即声明处），所以 `sig` 要写得能唯一定位到那个签名；
     *   - 配平只看花括号：方法体里若有**含花括号的字符串字面量**（`"{"`）会算偏。
     *     目前调用它的三个方法都没有字符串字面量；真要查那种方法，先按需加强（或补个 stripLiterals）。
     */
    private static String methodBody(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0) return null;
        int b = src.indexOf('{', i);
        if (b < 0) return null;
        int depth = 0;
        for (int j = b; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(b + 1, j);
            }
        }
        return null;
    }

    /** 读一个 src 文件的**去注释**正文；读不到返回 null。 */
    private static String codeOf(String simpleName) {
        String src = sourceOf(simpleName);
        return src == null ? null : stripComments(src);
    }

    /** 子串出现次数 —— 结构守卫用它数"这件事做了几遍"。 */
    private static int occur(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static boolean installThrows(File pkg, File dest, File staging, FlakySink sink, String fp) {
        try {
            RootfsInstaller.install(pkg, dest, staging, sink, null, fp);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    /** 记数 + 能在指定位置模拟崩溃的 Sink。 */
    static final class FlakySink extends NioSink {
        int creates = 0;
        int failAfter = Integer.MAX_VALUE;      // 第 N 个文件之后不再继续
        int failAfterSymlink = -1;              // 第 N 个符号链接「成功之后」抛错
        private int symlinks = 0;

        @Override
        public OutputStream create(File file) throws IOException {
            creates++;
            if (creates > failAfter) throw new IOException("模拟崩溃：第 " + creates + " 个文件");
            return super.create(file);
        }

        @Override
        public void symlink(String target, File link) throws IOException {
            symlinks++;
            super.symlink(target, link);
            if (symlinks == failAfterSymlink) throw new IOException("模拟崩溃：符号链接刚建好");
        }
    }

    /**
     * I. 命令目录与**硬不变量 C** —— 拿真实数据（data/commands.txt + data/components.json）跑。
     *
     * 这是把 Node 侧 coverage.mjs 的校验在 Java 侧复算一遍：命令面板的分组直接来自
     * components.json，任何一条命令没落点、或某个按钮引用了不存在的命令，这里就该红。
     */
    static void testCommandCatalog() throws Exception {
        System.out.println("== I. 命令目录与不变量 C ==");

        File cmdFile = firstExisting(new File("../data/commands.txt"), new File("data/commands.txt"));
        File compFile = firstExisting(new File("../data/components.json"), new File("data/components.json"));
        if (cmdFile == null || compFile == null) {
            ok(false, "找不到 data/commands.txt 或 data/components.json（cwd="
                    + new File(".").getAbsolutePath() + "）");
            return;
        }

        // --- Json 解析器：本项目要的形状都得认 ---
        java.util.Map<String, Object> m = Json.map(Json.parse(
                "{\"a\":[\"x\",\"y\"],\"b\":{\"c\":3},\"d\":true,\"e\":null}"));
        ok(m != null, "Json 解析对象");
        eq(Json.list(m.get("a")).size(), 2, "Json 解析数组");
        eq(Json.str(Json.list(m.get("a")).get(1)), "y", "Json 取数组元素");
        eq(Json.map(m.get("b")).get("c"), 3L, "Json 嵌套对象里的数字");
        eq(m.get("d"), Boolean.TRUE, "Json 布尔");
        eq(Json.parse("not json at all"), null, "垃圾输入返回 null 不抛");
        java.util.Map<String, Object> tricky =
                Json.map(Json.parse("{\"s\":\"带 } 和 \\\" 引号\"}"));
        eq(Json.str(tricky.get("s")), "带 } 和 \" 引号", "字符串里的 } 与转义引号不切错");

        // --- 命令解析 ---
        String commandsText = readText(cmdFile);
        List<CommandCatalog.Command> cmds = CommandCatalog.parseCommands(commandsText);
        eq(cmds.size(), 112, "commands.txt 解析出 112 条命令（harness 3.28.0 起 112）");
        eq(cmds.get(0).cmd, "/ask", "第一条命令是 /ask");
        ok(cmds.get(0).desc.length() > 0, "第一条有说明");

        CommandCatalog.Command clear = find(cmds, "/clear");
        ok(clear != null && clear.flags.indexOf("core") >= 0, "/clear 的 [core] 标记被摘进 flags");
        ok(clear != null && !clear.desc.startsWith("["), "/clear 的说明不含前导标记");
        CommandCatalog.Command help = find(cmds, "/help");
        ok(help != null && help.flags.indexOf("F1") >= 0 && help.flags.indexOf("core") >= 0,
                "/help 同时摘出 {F1} 与 [core] 两个标记");

        // --- 落点解析 ---
        String compsJson = readText(compFile);
        List<CommandCatalog.Surface> surfaces = CommandCatalog.parseSurfaces(compsJson);
        eq(surfaces.size(), 6, "components.json 里有 6 个落点面");
        java.util.Map<String, String> na = CommandCatalog.parseNotApplicable(compsJson);
        eq(na.size(), 4, "4 条移动端不适用（/clear 已移出：它有落点，也真的能用）");
        for (String k : na.keySet()) {
            ok(na.get(k).length() > 0, k + " 带了不适用理由");
        }

        java.util.Map<String, Integer> want = new java.util.HashMap<String, Integer>();
        want.put("topbar", 8);
        want.put("composer", 21);        // +3：/editor /paste /stash（harness 3.28.0 新增）
        want.put("messageActions", 15);
        want.put("panels", 24);          // +2：/metrics /thinking（harness 3.28.0 新增）
        want.put("settings", 28);        // +3：/cvm /keybindings /tui（harness 3.28.0 新增）
        want.put("automatic", 13);
        for (CommandCatalog.Surface s : surfaces) {
            Integer w = want.get(s.key);
            ok(w != null, "落点面在已知集合里：" + s.key);
            if (w != null) eq(s.items.size(), w, s.key + " 的落点条数");
        }

        // --- 不变量 C ---
        CommandCatalog.Catalog cat = CommandCatalog.load(commandsText, compsJson);
        ok(cat.coverage.ok, "双向覆盖 GREEN（0 孤儿）\n" + CommandCatalog.report(cat.coverage));
        eq(cat.coverage.orphansInUi.size(), 0, "没有「有按钮无命令」");
        eq(cat.coverage.orphansInCommands.size(), 0, "没有「有命令无落点」");
        eq(cat.coverage.unknownNotApplicable.size(), 0, "不适用清单里没有不存在的命令");
        eq(cat.coverage.commandCount, 112, "命令总数");
        eq(cat.coverage.notApplicableCount, 4,
                "不适用数（/clear 已移出 —— 它在触屏有语义，而且真的实现了）");
        // 「不适用」的理由必须站得住：列进去的命令，在 App 里**真的没有落点**
        //（action 只能是 none —— 那是"触屏没有对应操作"）。
        // /clear 曾同时出现在两边：一节写「触屏无清屏语义」，而它其实是 local、
        // 对话页输入就能用 —— 同一屏上两句话互相打架（2026-10-05 走查核出）。
        // 现在它有了落点（composer），不适用那一节也把它去掉了。
        for (String c : cat.notApplicable.keySet()) {
            CommandRouting.Row r = CommandRouting.of(c);
            ok(r == null || CommandRouting.NONE.equals(r.action),
                    "标为「不适用」的命令在 App 里不该有落点（action 应为 none）：" + c
                            + (r == null ? "（路由表里没有）" : " act=" + r.action));
        }
        for (CommandCatalog.Command c : cmds) {
            ok(cat.descOf(c.cmd).length() > 0, c.cmd + " 有说明可取（面板不会显示「无说明」）");
        }

        // 反例 1：把 /new 同时从落点与不适用里拿掉 → 必须变红
        List<CommandCatalog.Surface> broken = new java.util.ArrayList<CommandCatalog.Surface>();
        for (CommandCatalog.Surface s : surfaces) {
            CommandCatalog.Surface c2 = new CommandCatalog.Surface(s.key, s.label);
            for (String it : s.items) {
                if (!"/new".equals(it)) c2.items.add(it);
            }
            broken.add(c2);
        }
        java.util.Map<String, String> na2 = new java.util.HashMap<String, String>(na);
        na2.remove("/new");
        ok(!CommandCatalog.verify(cmds, broken, na2).ok, "反例：/new 丢掉落点后必须红");

        // 反例 2：组件引用一条不存在的命令 → 必须红
        CommandCatalog.Surface bogus = new CommandCatalog.Surface("topbar", "topbar");
        bogus.items.add("/not-a-real-command");
        List<CommandCatalog.Surface> broken2 =
                new java.util.ArrayList<CommandCatalog.Surface>(surfaces);
        broken2.add(bogus);
        ok(!CommandCatalog.verify(cmds, broken2, na).ok, "反例：凭空发明的按钮必须红");
    }

    /**
     * J. 硬不变量 A：**答案永远在本轮最底** —— 合成事件序列 + 真实 SSE 报文双基准。
     *
     * 合成部分逐条对应离线靶子 `src/transcript.mjs`（Wave 1 已 GREEN 的那份）；
     * 真实部分拿 fixtures/sse-real-sample.txt（一整轮真实对话）跑，确认 Java 侧
     * 分桶出来的答案与 `text_delta` 拼接结果逐字一致、且答案块确实在最后。
     */
    static void testTranscript(String fixturePath) throws Exception {
        System.out.println("== J. 答案置底（不变量 A）==");

        // --- 合成：思考 / 工具 / 过程说明 / 工具 / 答案 交错 ---
        List<Transcript.Event> interleaved = Arrays.asList(
                ev("thinking_delta", "{\"text\":\"先想一下\"}"),
                ev("tool_use", "{\"id\":\"t1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.ts\"}}"),
                ev("tool_result", "{\"id\":\"t1\",\"name\":\"read_file\",\"result\":\"...\"}"),
                ev("text_delta", "{\"text\":\"这是个过程说明\"}"),
                ev("tool_use", "{\"id\":\"t2\",\"name\":\"grep\",\"input\":{\"pattern\":\"x\"}}"),
                ev("tool_result", "{\"id\":\"t2\",\"name\":\"grep\",\"result\":\"...\"}"),
                ev("text_delta", "{\"text\":\"B\"}"),
                ev("turn_complete", "{}"));

        List<Transcript.Block> blocks = Transcript.parseTurn(interleaved);
        ok(!blocks.isEmpty(), "交错序列产出显示块");
        Transcript.Block last = blocks.get(blocks.size() - 1);
        eq(last.kind, "answer", "最后一个块必须是答案块");
        eq(last.text, "B", "答案是最后一段连续文本（不含过程说明）");

        int answers = 0;
        for (Transcript.Block b : blocks) {
            if ("answer".equals(b.kind)) answers++;
        }
        eq(answers, 1, "一轮里只有一个答案块");

        Transcript.Block act = null;
        for (Transcript.Block b : blocks) {
            if ("activity".equals(b.kind)) act = b;
        }
        ok(act != null, "必须存在活动区块");
        if (act != null) {
            eq(act.collapsed, true, "活动区默认折叠");
            eq(act.thinking, 1, "思考段数 = 1");
            eq(act.tools, 2, "工具条数 = 2（use/result 合并）");
            int notes = 0;
            String noteText = null;
            for (Transcript.Child c : act.children) {
                if ("note".equals(c.kind)) { notes++; noteText = c.text; }
            }
            eq(notes, 1, "过程说明并入活动区");
            eq(noteText, "这是个过程说明", "过程说明文本逐字");
        }

        List<Transcript.Block> visible = Transcript.visibleOnOpen(blocks, 3);
        boolean answerVisible = false;
        for (Transcript.Block b : visible) {
            if ("answer".equals(b.kind)) answerVisible = true;
        }
        ok(answerVisible, "打开这一轮时答案已经在初始视口里（不需要滚动）");

        // --- 反例：只有思考没有文本 → 不产生空答案块 ---
        List<Transcript.Block> onlyThinking = Transcript.parseTurn(Arrays.asList(
                ev("thinking_delta", "{\"text\":\"只有思考\"}"),
                ev("turn_complete", "{}")));
        int emptyAnswers = 0;
        for (Transcript.Block b : onlyThinking) {
            if ("answer".equals(b.kind)) emptyAnswers++;
        }
        eq(emptyAnswers, 0, "反例：无文本 → 不产生空答案块");

        // --- 反例：纯文本回合 → 答案就是全部文本 ---
        List<Transcript.Block> pure = Transcript.parseTurn(Arrays.asList(
                ev("text_delta", "{\"text\":\"直接回答\"}"),
                ev("turn_complete", "{}")));
        eq(pure.size(), 1, "纯文本回合只有一块");
        eq(pure.get(pure.size() - 1).kind, "answer", "纯文本回合的最后一块是答案");
        eq(pure.get(pure.size() - 1).text, "直接回答", "答案文本");

        // --- 多轮：每轮的答案都在自己那轮最底 ---
        List<Transcript.Event> twoTurns = Arrays.asList(
                ev("user", "{\"text\":\"hi\"}"),
                ev("text_delta", "{\"text\":\"第一轮\"}"),
                ev("turn_complete", "{}"),
                ev("user", "{\"text\":\"again\"}"),
                ev("tool_use", "{\"id\":\"x\",\"name\":\"bash\",\"input\":{}}"),
                ev("tool_result", "{\"id\":\"x\",\"name\":\"bash\",\"result\":\"ok\"}"),
                ev("text_delta", "{\"text\":\"第二轮\"}"),
                ev("turn_complete", "{}"));
        List<Transcript.TurnView> turns = Transcript.parseConversation(twoTurns);
        eq(turns.size(), 2, "两轮对话切成 2 个视图");
        for (int i = 0; i < turns.size(); i++) {
            List<Transcript.Block> bs = turns.get(i).blocks;
            eq(bs.get(bs.size() - 1).kind, "answer", "第 " + (i + 1) + " 轮答案在其最底");
        }
        eq(turns.get(0).userText, "hi", "第一轮用户文本");
        eq(turns.get(0).answer().text, "第一轮", "第一轮答案");
        eq(turns.get(1).answer().text, "第二轮", "第二轮答案");

        // --- 吸底策略 ---
        Transcript.ScrollPolicy stuck = Transcript.scrollPolicy(true, false);
        ok(stuck.autoScroll && !stuck.showJumpButton, "在底部 → 自动吸底、不显示回到最新");
        Transcript.ScrollPolicy up = Transcript.scrollPolicy(false, true);
        ok(!up.autoScroll && up.showJumpButton && up.highlightJump,
                "上滑 → 停止吸底 + 显示回到最新 + 新答案时高亮");

        // --- rawValue / fromSse 取字段 ---
        eq(Transcript.rawValue("{\"input\":{\"a\":1}}", "input"), "{\"a\":1}", "rawValue 取对象原文");
        eq(Transcript.rawValue("{\"n\":\"x\"}", "n"), "\"x\"", "rawValue 取字符串原文");
        Transcript.Event terr = Transcript.fromSse("tool_result",
                "{\"id\":\"t\",\"name\":\"bash\",\"result\":\"boom\",\"isError\":true}");
        eq(terr.name, "bash", "fromSse 取工具名");
        ok(terr.isError, "fromSse 认出 isError");

        // --- 真实报文：一整轮真实对话（夹具来自容器实跑）---
        File fx = new File(fixturePath);
        if (!fx.isFile()) {
            ok(false, "找不到真实报文夹具: " + fixturePath);
            return;
        }
        SseParser p = new SseParser();
        final List<Transcript.Event> real = new ArrayList<Transcript.Event>();
        SseParser.Handler h = new SseParser.Handler() {
            @Override
            public void onEvent(String event, String data) {
                real.add(Transcript.fromSse(event, data));
            }
        };
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(fx), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) p.feed(line, h);
        br.close();
        p.flush(h);

        List<Transcript.TurnView> realTurns = Transcript.parseConversation(real);
        eq(realTurns.size(), 1, "真实报文切成 1 轮（末尾那个只有 done 的尾帧被丢掉）");
        if (realTurns.size() == 1) {
            Transcript.TurnView t0 = realTurns.get(0);
            eq(t0.userText, "用一句话回答：2+2 等于几？", "真实报文里的用户文本");
            Transcript.Block a0 = t0.answer();
            ok(a0 != null, "真实报文里有答案块");
            if (a0 != null) {
                eq(a0.text, "2+2 等于 4。", "答案文本 = text_delta 拼接结果（逐字）");
                ok(t0.blocks.get(t0.blocks.size() - 1) == a0, "答案块在这一轮的最后一块");
            }
            Transcript.Block act0 = t0.activity();
            ok(act0 != null, "真实报文里有活动区块（思考/相位）");
            if (act0 != null) {
                eq(act0.thinking, 1, "5 段连续 thinking_delta 合成 1 个思考段");
                int phases = 0;
                for (Transcript.Child c : act0.children) {
                    if ("phase".equals(c.kind)) phases++;
                }
                ok(phases >= 3, "相位事件进活动区（实测 " + phases + " 条）");
            }
        }
    }

    /**
     * K. 外观系统：风格包 + WCAG 对比度护栏 —— 与离线靶子 `src/theme.mjs` 同源。
     *
     * 护栏是硬需求：毛玻璃 + 花壁纸最容易**悄悄**打穿文字可读性（用户只觉得"看着累"），
     * 所以低对比度必须报错，而不是静默放行。
     */
    static void testTheme() {
        System.out.println("== K. 外观：主题/主色/自定义底色与对比度护栏 ==");

        // --- 清单形状 ---
        eq(Theme.PRESETS.size(), 7, "7 套背景主题（含默认的初雪）");
        eq(Theme.ACCENTS.size(), 8, "8 款主色（含默认的初雪纯蓝）");
        StringBuilder ids = new StringBuilder();
        for (Theme.Preset p : Theme.PRESETS) {
            if (ids.length() > 0) ids.append(',');
            ids.append(p.id);
        }
        eq(ids.toString(), "yuki,clear,warm,leaf,dusk,night,deep", "主题 id 与 theme.mjs 逐字一致");
        for (Theme.Preset p : Theme.PRESETS) {
            ok(p.id != null && p.name != null && p.hint != null, p.id + " 的三件套齐全");
            ok("light".equals(p.mode) || "dark".equals(p.mode), p.id + " 有明暗语义");
        }
        eq(Theme.presetById("deep").mode, "dark", "深海是深色主题");
        eq(Theme.presetById("nope"), null, "未知主题 → null（由 normalize 兜底）");
        eq(Theme.accentById("nope"), null, "未知主色 → null");

        // --- 颜色数学自检 ---
        ok(Math.abs(Theme.contrast("#000000", "#FFFFFF") - 21) < 0.01, "黑白对比度 = 21:1");
        ok(Math.abs(Theme.contrast("#3a3a3a", "#3a3a3a") - 1) < 0.001, "同色对比度 = 1:1");
        ok(Theme.lum("#FFFFFF") > Theme.lum("#808080") && Theme.lum("#808080") > Theme.lum("#000000"),
                "相对亮度单调：白 > 灰 > 黑");
        eq(Theme.rgb("#abc")[0], 0xAA, "#RGB 展开");
        eq(Theme.rgb("#58A6FF")[2], 0xFF, "#RRGGBB 解析");
        eq(Theme.rgb("58A6FF"), null, "缺 # 不认（不猜）");
        eq(Theme.normHex("#ABC"), "#aabbcc", "归一化成小写 6 位");
        eq(Theme.normHex("nope"), "nope", "非法输入原样返回");
        eq(Theme.mix("#000000", "#ffffff", 0.0), "#000000", "混色 t=0 得 a");
        eq(Theme.mix("#000000", "#ffffff", 1.0), "#ffffff", "混色 t=1 得 b");
        eq(Theme.mix("#000000", "#ffffff", 0.5), "#808080", "混色 t=0.5 是中灰");
        eq(Theme.alpha("#ffffff", 0.5), "rgba(255,255,255,0.5)", "半透明同色");
        eq(Theme.toArgb("#ffffff"), 0xFFFFFFFF, "ARGB 转换（不透明）");
        eq(Theme.toArgb("rgba(0,0,0,0.5)"), 0x80000000, "ARGB 转换（半透明）");
        eq(Theme.toArgb("nope"), 0xFF808080, "非法色值 → 中灰（不崩）");

        // --- 护栏矩阵：7 主题 × 8 主色 × 3 档透明度 ---
        // 逐项跑：任何一组不达标都会打印出具体是哪一组、哪一条
        double[] alphas = {Theme.CARD_ALPHA_MAX, 0.65, Theme.CARD_ALPHA_MIN};
        int combos = 0;
        for (Theme.Preset p : Theme.PRESETS) {
            for (Theme.Accent a : Theme.ACCENTS) {
                for (double al : alphas) {
                    Theme.Config c = new Theme.Config(p.id, a.id, Theme.DEFAULT_BG, "", "", al);
                    List<Theme.Failure> f = Theme.audit(c);
                    ok(f.isEmpty(), "外观「" + p.id + "/" + a.id + "」alpha=" + al + " 护栏达标"
                            + (f.isEmpty() ? "" : " → " + Theme.auditReport(c)));
                    combos++;
                }
            }
        }
        eq(combos, 168, "矩阵组合数 = 7×8×3（主题 × 主色 × 透明度档）");

        // --- 自定义底色：亮底与深底都有完全解 ---
        for (String bg : new String[]{"#ffffff", "#f4f6fa", "#eaeff2", "#204060", "#12151b", "#000000"}) {
            Theme.Solved s = Theme.solveCustom(bg);
            ok(s.ok, bg + " 有完全解");
            ok(Theme.contrast(s.fg, s.card) >= 7, bg + "：卡片上的字 ≥ 7:1");
            ok(Theme.contrast(s.fg, bg) >= 4.5, bg + "：底色上的字 ≥ 4.5:1");
        }
        ok(!Theme.solveCustom("#f4f6fa").card.equals(Theme.solveCustom("#12151b").card),
                "自定义底色确实改变卡片色（不是写死的一套）");
        eq(Theme.solveCustom("#f4f6fa").dark, false, "浅底配深字");
        eq(Theme.solveCustom("#12151b").dark, true, "深底配浅字");

        // --- 中灰底：物理上无完全解，但卡片上仍达标、且如实标记 ---
        Theme.Solved mid = Theme.solveCustom("#787878");
        eq(mid.ok, false, "中灰底必须如实标记 ok:false，而不是假装解决");
        ok(Theme.contrast(mid.fg, mid.card) >= 7, "即便无完全解，卡片上的字仍 ≥ 7:1");
        Theme.Config midCfg = new Theme.Config("custom", "blue", "#787878", "", "", 1);
        Theme.Tokens midTok = Theme.tokens(midCfg);
        ok(Theme.contrast(midTok.fg, midTok.cardSolid) >= 4.5, "中灰底：卡片里的正文仍然清楚");
        ok(!Theme.palette(midCfg).ok, "palette 把「无解」透出来（界面据此提示用户）");
        ok(!Theme.audit(midCfg).isEmpty(), "反例：中灰底必须被护栏抓到，不许静默放行");

        // --- 文字角色 ×「它实际出现的每个底面」都要达标 ---
        // 回归：从前 alpha=1 时只约束卡片，于是**直接压在页面底色上**的字
        // （对话页副标题「就绪」、启动页标题、各级说明）在自定义底色下掉到 3.x:1
        //（#204060 → sub 3.59、#808080 → priInk 1.36），而外观页承诺「不会出现看不清的字」。
        for (String bg : new String[]{"#f4f6fa", "#204060", "#204", "#e8e0d0", "#c0c0c0", "#ffd700", "#12151b"}) {
            Theme.Config cc = new Theme.Config("custom", "blue", bg, "", "", 1);
            Theme.Tokens tk = Theme.tokens(cc);
            ok(Theme.contrast(tk.fg, tk.bg) >= 4.5, bg + "：正文压在底色上 ≥ 4.5:1");
            ok(Theme.contrast(tk.sub, tk.bg) >= 4.5, bg + "：次要文字压在底色上 ≥ 4.5:1");
            ok(Theme.contrast(tk.priInk, tk.bg) >= 4.5, bg + "：强调标题色压在底色上 ≥ 4.5:1");
            if (Theme.solveCustom(bg).ok) {
                ok(Theme.audit(cc).isEmpty(), bg + " 有完全解 → 护栏须 GREEN"
                        + (Theme.audit(cc).isEmpty() ? "" : " → " + Theme.auditReport(cc)));
            }
        }

        // --- 主色上的字色按对比度选 ---
        eq(Theme.onColor("#2b6cf0", "#5b8def"), "#ffffff", "蓝上用白字");
        eq(Theme.onColor("#4a5568", "#6b7787"), "#ffffff", "墨上用白字");
        ok(!"#ffffff".equals(Theme.onColor("#e07819", "#f09b45")), "橙上不能写白字");
        ok(!"#ffffff".equals(Theme.onColor("#0e93a8", "#3ab4c4")), "青上不能写白字");
        // 判据与 Theme.audit 统一口径：AA_TEXT **减一个量化地板容差**
        // （颜色是 8 位量化的：预设主色 `#2f6bff` 上的白字真值 4.4987671，距 4.5 差 1.23e-3，
        //  而相邻可调色阶的对比度步长约 0.06 —— 造不出更优解。见 Theme.CONTRAST_EPS 的注释。）
        for (Theme.Accent a : Theme.ACCENTS) {
            ok(Theme.contrast(Theme.onColor(a.pri, a.pri2), a.pri) >= Theme.AA_TEXT - Theme.CONTRAST_EPS,
                    a.id + "：主色上的字 ≥ 4.5:1（含量化地板）");
        }

        // --- C4 · 对比度护栏不能自己四舍五入（边界断言，T1/T3 要求） ---
        // 根因（2026-10-05）：contrast() 旧实现末尾 `round(x*100)/100`，而判据是 `contrast(...) < AA_TEXT`(4.5)
        //   → 真值落在 [4.495, 4.5) 的颜色被 round 成 4.50、`4.50 < 4.5` 为假、于是**放行**。
        // 这里用真实数据点钉住"函数必须返回原始比值"（比人工构造色对更扎实）：
        double onPri4 = Theme.contrast("#ffffff", "#2f6bff");   // 主题预设主色上的白字
        ok(onPri4 < Theme.AA_TEXT,
                "#2f6bff 上的白字真值 " + onPri4 + " < 4.5 —— round 到两位会变成 4.50 而被放行");
        double sample4 = Theme.contrast("#ffffff", "#767676");
        ok(Math.abs(sample4 * 100 - Math.round(sample4 * 100)) > 1e-9,
                "contrast 返回**原始比值**、不 round（白/#767676 = " + sample4 + "）");
        ok(Math.abs(Theme.contrast("#000000", "#ffffff") - 21.0) < 1e-9,
                "白/黑 = 21.0（精确，未受去 round 影响）");

        // --- normalize：垃圾输入一律不抛 ---
        Theme.Config d = Theme.normalize(null);
        eq(d.preset, "yuki", "null → 默认主题（初雪）");
        eq(d.accent, "yuki", "null → 默认主色（初雪纯蓝）");
        eq(d.cardAlpha, 1.0, "null → 不透明");
        eq(d.customBg, Theme.DEFAULT_BG, "null → 默认底色");
        eq(Theme.normalize(new Theme.Config("不存在", "blue", Theme.DEFAULT_BG, "", "", 1)).preset,
                "yuki", "未知主题 → 默认");
        eq(Theme.normalize(new Theme.Config("clear", "不存在", Theme.DEFAULT_BG, "", "", 1)).accent,
                "yuki", "未知主色 → 默认（初雪纯蓝）");
        eq(Theme.normalize(new Theme.Config("clear", "blue", "#xyz", "", "", 1)).customBg,
                Theme.DEFAULT_BG, "非法底色 → 默认");
        // 空串是合法值（不要渐变 / 不要图片不是错误）
        Theme.Config e1 = Theme.normalize(new Theme.Config("custom", "blue", "#204060", "", "", 1));
        eq(e1.customBg2, "", "空串渐变保持空串，不塌成默认色");
        eq(e1.image, "", "空串图片保持空串");
        eq(e1.preset, "custom", "显式 custom 被接受");
        eq(e1.customBg, "#204060", "自定义底色被保留");

        // --- cardAlpha：越界夹取，只有压根不是数才回 1 ---
        eq(Theme.cardAlphaOf(0.05), 0.1, "低于下限夹到下限");
        eq(Theme.cardAlphaOf(2.0), 1.0, "高于上限夹到上限");
        eq(Theme.cardAlphaOf(0.75), 0.75, "手改配置写个 0.75 要能用");
        eq(Theme.cardAlphaOf("0.65"), 0.65, "字符串数字也认");
        eq(Theme.cardAlphaOf(null), 1.0, "null → 不透明（升级前的样子）");
        eq(Theme.cardAlphaOf(""), 1.0, "空串 → 不透明");
        eq(Theme.cardAlphaOf("abc"), 1.0, "乱写 → 不透明");

        // --- isBgPath ---
        ok(Theme.isBgPath("/data/data/dev.tianshu.host/files/bg/background"), "正常绝对路径");
        ok(!Theme.isBgPath("bg/background"), "相对路径不认");
        ok(!Theme.isBgPath(""), "空串不认");
        ok(!Theme.isBgPath("/a\"b"), "引号不许进来");
        ok(!Theme.isBgPath("/a(b)"), "括号不许进来");
        ok(!Theme.isBgPath("/a\\b"), "反斜杠不许进来");
        ok(!Theme.isBgPath(null), "null 不认");
        eq(Theme.normalize(new Theme.Config("clear", "blue", Theme.DEFAULT_BG, "", "/a\"b", 1)).image,
                "", "非法图片路径被清掉");

        // --- Config JSON 往返 ---
        Theme.Config src = new Theme.Config("custom", "amber", "#204060", "#102030",
                "/data/data/x/files/bg/background", 0.65);
        Theme.Config back = Theme.parse(src.toJson());
        eq(back.preset, "custom", "JSON 往返：主题");
        eq(back.accent, "amber", "JSON 往返：主色");
        eq(back.customBg, "#204060", "JSON 往返：底色");
        eq(back.customBg2, "#102030", "JSON 往返：渐变");
        eq(back.image, "/data/data/x/files/bg/background", "JSON 往返：背景图");
        eq(back.cardAlpha, 0.65, "JSON 往返：透明度");
        eq(Theme.parse("not json at all").preset, "yuki", "坏 JSON → 默认，不抛");
        eq(Theme.parse(null).preset, "yuki", "null → 默认");
        eq(Theme.parse("").preset, "yuki", "空串 → 默认");
        eq(Theme.parse("{\"preset\":\"night\"}").preset, "night", "只给一个字段也能用");
        eq(Theme.parse("{\"preset\":\"night\"}").cardAlpha, 1.0, "缺字段走默认");

        // --- token 表：透明度真的改变颜色 ---
        Theme.Tokens solid = Theme.tokens(new Theme.Config("clear", "blue", Theme.DEFAULT_BG, "", "", 1));
        Theme.Tokens glass = Theme.tokens(new Theme.Config("clear", "blue", Theme.DEFAULT_BG, "", "", 0.35));
        eq(solid.cardAlpha, 1.0, "不透明");
        eq(glass.cardAlpha, 0.35, "半透明");
        ok(!glass.cardSolid.equals(solid.cardSolid), "半透明卡片的合成色必须不同");
        ok(Theme.contrast(glass.fg, glass.cardSolid) >= 4.5,
                "透光之后卡片里的字仍然达标（这正是两个底一起解要保证的）");
        ok(glass.fieldBg.startsWith("rgba("), "低透明度下输入框底走半透明");
        ok(solid.fieldBg.startsWith("#"), "不透明时输入框底是实色");
        eq(solid.grad, "#2b6cf0,#5b8def", "渐变两端来自主色");
        ok(solid.scrim.startsWith("rgba("), "遮罩是半透明底色");
        eq(Theme.tokens(new Theme.Config("clear", "blue", Theme.DEFAULT_BG, "", "/x/y.jpg", 1)).image,
                "/x/y.jpg", "背景图路径透传给界面层");
        ok(solid.cardArgb() != glass.cardArgb(), "卡片 ARGB 随透明度变化");
        ok((glass.cardArgb() >>> 24) < 255, "半透明卡片的 alpha 通道不满");

        // --- 默认配置就是清蓝 + 蓝主色 + 不透明无图（与参照物一致）---
        Theme.Tokens def = Theme.tokens(Theme.Config.defaults());
        eq(def.bg, "#f5f7fa", "默认底色 = 初雪浅灰（照参考 App）");
        eq(def.pri, "#2f6bff", "默认主色 = 初雪纯蓝");
        eq(def.preset, "yuki", "默认主题 = 初雪");
        ok(Theme.audit(Theme.Config.defaults()).isEmpty(), "默认配置过护栏");
    }

    /**
     * L. 外观覆盖纪律：不许用 AdapterView。
     *
     * 外观是 Theming.walk 顺着 View 树贴上去的，而 AdapterView（ListView / GridView /
     * Spinner）的条目由 Adapter 在 layout 时才创建，**不在那棵树里** —— 换到深色主题时，
     * 条目文字仍是系统默认色（设备处于浅色模式时就是黑的），压在深底上几乎看不见。
     *
     * 真机踩过：把主题换成「深海」后，历史会话那一列每条都像没渲染（背景深蓝、文字黑）。
     * 这条断言盯的是"将来又有人顺手用 ListView" —— 它不会崩、不会报错，
     * 只会静默地让那一块不跟着换肤，正是最难发现的那类缺陷。
     */
    static void testAppearanceCoverage() throws Exception {
        System.out.println("== L. 外观覆盖纪律（禁 AdapterView）==");

        File dir = new File("src/dev/tianshu/host");
        if (!dir.isDirectory()) {
            ok(false, "找不到源码目录（cwd=" + new File(".").getAbsolutePath() + "）");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            ok(false, "列不出源码文件");
            return;
        }

        // 只认"真的在用"的形态（new / extends / import）。
        // 注释里提到 ListView 这些名字是**故意**的 —— 那正是在说明为什么不能用，
        // 不能把说明文字本身当成违规（第一版就是这么误报的）。
        String[] banned = {"new ListView", "new GridView", "new Spinner",
                "new ArrayAdapter", "extends AdapterView", "extends ListView",
                "import android.widget.ListView", "import android.widget.ArrayAdapter",
                "import android.widget.GridView", "androidx.recyclerview"};
        int checked = 0;
        int violations = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            String src = readText(f);
            checked++;
            for (String b : banned) {
                if (src.indexOf(b) >= 0) {
                    violations++;
                    ok(false, f.getName() + " 里出现了 " + b
                            + " —— AdapterView 的条目不受 Theming 覆盖，换深色主题会看不见");
                }
            }
        }
        ok(violations == 0, "没有任何 Activity 用 AdapterView（扫了 " + checked + " 个文件）");
        ok(checked >= 10, "扫过的源码文件数 = " + checked + "（少于 10 说明路径不对）");
    }

    /**
     * M. 启动页文案。
     *
     * 首次启动要解压约 600 MB，可能好几分钟 —— 屏幕上"已等 N 秒"是用户判断
     * "还在动"还是"卡死了"的唯一证据。这两条断言钉住它的格式，防止哪天改坏了
     * 秒数不再刷新（那看起来跟卡死一模一样）。
     */
    static void testStartupText() {
        System.out.println("== M. 启动页文案 ==");

        eq(StartupText.detailLine("", 0), "已等 0 秒", "没有细节时只显示等待时长");
        eq(StartupText.detailLine(null, 7), "已等 7 秒", "null 细节不炸");
        eq(StartupText.detailLine("已解压 128 MB · 3241 个文件", 42),
                "已解压 128 MB · 3241 个文件 · 已等 42 秒", "细节 + 等待时长拼接");
        eq(StartupText.detailLine("首次启动要解压约 600 MB，请耐心等一会儿", 5),
                "首次启动要解压约 600 MB，请耐心等一会儿 · 已等 5 秒", "首次启动提示");
        // 秒数必须真的随时间变 —— 不然那一行就是一句静态摆设
        ok(!StartupText.detailLine("x", 1).equals(StartupText.detailLine("x", 2)),
                "秒数变化时文案随之变化（否则看起来像卡死）");
    }

    /**
     * N. 底部页面栏的页签定义。
     *
     * 这几条断言钉住「页面栏是哪几页、按什么顺序」—— 它被几个页面共用，
     * 改错了不会崩，只会让某页底栏悄悄少一格、或者点下去跳到别处。
     *
     * 底栏格数改过两次，都记在这儿：2026-10-06 早先外观页收进设置页（四格 → 三格），
     * 同日又按用户要求补了「个人」页（三格 → 四格）。「外观页不在底栏里」与
     * 「设置页里有它的入口」两条在 {@link #testAppearanceEntry()}。
     */
    static void testTabs() {
        System.out.println("== N. 底部页面栏 ==");

        eq(Tabs.ALL.length, 4, "底部栏四格");
        eq(Tabs.ALL[0].label, "对话", "第一格：对话");
        eq(Tabs.ALL[1].label, "会话", "第二格：会话");
        eq(Tabs.ALL[2].label, "设置", "第三格：设置");
        eq(Tabs.ALL[3].label, "个人", "第四格：个人");
        eq(Tabs.ALL[0].id, Tabs.CHAT, "对话的 id");
        eq(Tabs.ALL[2].id, Tabs.SETTINGS, "设置仍在第三格（外观那格 2026-10-06 已移走）");
        eq(Tabs.ALL[3].id, Tabs.PROFILE, "末格是个人");
        for (Tabs.Tab t : Tabs.ALL) {
            ok(t.icon != 0, t.label + " 有图标（Icon 种类 " + t.icon + "）");
            ok(t.activity != null && t.activity.startsWith("dev.tianshu.host."),
                    t.label + " 指向本包的 Activity：" + t.activity);
        }

        // 页签名 → 源码文件必须真的存在：改了类名忘改 Tabs，这条会红。
        // 顺带钉住**页面骨架纪律**：底栏是加在页面 root 上的，root 一带 padding，
        // 底栏就被一起缩窄 —— 原先两个页面 padding 落 root、两个落内层，切换时底栏
        // 就会"一大一小"（真机踩过）。约定：root 无 padding，底栏统一走 NavBar.params。
        for (Tabs.Tab t : Tabs.ALL) {
            String simple = t.activity.substring(t.activity.lastIndexOf('.') + 1);
            ok(new File("src/dev/tianshu/host", simple + ".java").isFile(),
                    t.label + " 对应的 " + simple + ".java 存在");
            String src = sourceOf(simple);
            if (src == null) continue;
            ok(!src.contains("root.setPadding"),
                    simple + " 的 root 不带 padding（底栏贴着 root 才四页同宽）");
            ok(src.contains("NavBar.params("),
                    simple + " 底栏用共用的 NavBar.params 摆放");
        }

        eq(Tabs.indexOfActivity("dev.tianshu.host.ChatActivity"), Tabs.CHAT, "按类名认出对话页");
        eq(Tabs.indexOfActivity("dev.tianshu.host.SettingsActivity"), Tabs.SETTINGS, "按类名认出设置页");
        eq(Tabs.indexOfActivity("dev.tianshu.host.CommandPanelActivity"), -1,
                "命令面板是二级页面（从命令抽屉进入），不高亮任何一格");
        eq(Tabs.indexOfActivity(null), -1, "null 不炸");
        eq(Tabs.byId(Tabs.SESSIONS).label, "会话", "按 id 取页签");
        eq(Tabs.byId(99), null, "未知 id 返回 null");

        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Tabs.Tab t : Tabs.ALL) seen.add(t.activity);
        eq(seen.size(), 4, "四个页签指向四个不同的 Activity");

        // 落点面中文名：命令抽屉与命令面板页共用同一份（早先各抄一份，这里钉住不许再分叉）
        eq(CommandCatalog.surfaceZh("composer"), "输入区", "落点面中文名");
        eq(CommandCatalog.surfaceZh("nope"), "nope", "未知 key 原样返回（不静默丢）");
        eq(CommandCatalog.surfaceKeys().size(), 6, "6 个已知落点面");
        ok(CommandCatalog.surfaceKeys().contains("topbar"), "清单里有 topbar");

        // ---- 返回键语义：只归 BaseActivity 一处 ----
        // 契约（用户 2026-10-04 的原话，抄在 BaseActivity.onBackPressed 上）：tab 之间是平级、不该有返回栈，
        // 主页面按返回 = 两次返回退出 App。于是底栏每一页**要么不覆盖** onBackPressed，
        // **要么覆盖了也只能"先把页面内的浮层收起来、再把决定交回 super"**（对话页正是这种：
        // 抽屉 / 命令区开着时先收起来，否则一按就以为崩了）。
        //
        // 2026-10-06 收掉的是第三种答案：设置页曾自己 `startActivity(对话页) + finish()` ——
        // 把"返回"改写成"跳页"，与上面的契约相反（详见 `审计.md §11.6`，真机上复现过）。
        for (Tabs.Tab t : Tabs.ALL) {
            String simple = t.activity.substring(t.activity.lastIndexOf('.') + 1);
            String body = methodBody(stripComments(sourceOf(simple)), "onBackPressed()");
            if (body == null) continue;                 // 没覆盖 = 最省事的那种，本就符合契约
            ok(body.indexOf("startActivity") < 0 && body.indexOf("finish(") < 0,
                    simple + " 的 onBackPressed 不借返回键跳页 / 关页（只许先收浮层，再交回 super）");
        }
        // 点级：设置页那个覆盖已经删掉，别再写回来 —— 它就是上面那条族级断言抓到的样本
        String settingsSrc = stripComments(sourceOf("SettingsActivity"));
        ok(settingsSrc != null && settingsSrc.indexOf("onBackPressed") < 0,
                "设置页不再自己覆盖 onBackPressed（返回键 = BaseActivity 的两次退出）");
    }

    /**
     * N′. 外观页的入口（用户 2026-10-06 的原话：「把外观页面放到设置页面里做成一个卡片，
     * 点进卡片才是外观页面」）。
     *
     * 三条契约，各钉一个会「悄悄坏掉」的地方：
     *   ① 外观页**不再**是底栏一格 —— 留着就是同一页两个门（用户要的是"点进卡片才是"）；
     *   ② 设置页里**有**它的入口 —— 删了那格又没接上卡片，这一页就再也进不去了
     *      （真机上只能走 UI 导航：`am start` 打不开非 exported 的 Activity）；
     *   ③ 外观页是**二级页**：不画底栏、不挂切页手势、页头有返回圆钮。
     *
     * ③ 必须先 stripComments 再查：源码里那几句注释**故意写着** `NavBar.create(...)` /
     * `SwipeNav` 的来历（说明为什么删）—— 不剥注释会被自己绊倒（本项目踩过：注释里提到
     * 「104 条命令」曾让计数类断言失去意义）。
     */
    static void testAppearanceEntry() {
        System.out.println("== N′. 外观页的入口（设置页那张卡）==");

        // ① 底栏里没有外观页了
        eq(Tabs.indexOfActivity("dev.tianshu.host.AppearanceActivity"), -1,
                "外观页不再是底栏一格（已降成二级页）");

        // ② 设置页里有它的入口，且那张卡真的挂进了页面（只定义不 addView = 死卡）
        String settings = sourceOf("SettingsActivity");
        ok(settings != null, "读得到 SettingsActivity 源码");
        if (settings != null) {
            String code = stripComments(settings);
            ok(code.contains("addView(appearanceCard())"),
                    "设置页把「外观」卡挂进了页面（不是只定义不挂）");
            ok(code.contains("AppearanceActivity.class"),
                    "设置页点得进外观页（源码里有 AppearanceActivity.class）");
            ok(code.contains("Icon.HALF"),
                    "那张卡用的是原来底栏那枚「外观」图标（Icon.HALF）");
        }

        // ③ 外观页是二级页
        String appear = sourceOf("AppearanceActivity");
        ok(appear != null, "读得到 AppearanceActivity 源码");
        if (appear != null) {
            String code = stripComments(appear);
            ok(!code.contains("NavBar"), "外观页不画底栏（二级页）");
            ok(!code.contains("SwipeNav"), "外观页不挂左右滑动切页（二级页不参与切页）");
            ok(code.contains("Icon.BACK"), "外观页页头有返回圆钮");
        }
    }

    /**
     * O. 启动页轮播 与 输入框里的命令筛选。
     *
     * 轮播：第一句必须是最实在的那句（"首次启动…请耐心等待"）—— 用户头 6 秒看到的就是它；
     * 顺序、周期、循环全部钉住。
     * 筛选："/" 才触发；前缀命中排在词内命中之前；条数有上限且不重复。
     */
    static void testRotateAndSuggest() throws Exception {
        System.out.println("== O. 启动页轮播 / 命令筛选 ==");

        // --- 轮播 ---
        eq(StartupText.ROTATING.length, 3, "轮播三句");
        eq(StartupText.ROTATING[0], "首次启动可能需要一点时间，请耐心等待", "第一句是最实在的那句");
        eq(StartupText.ROTATING[1], "天机推演，稍候则明", "第二句");
        eq(StartupText.ROTATING[2], "乾坤运转，请君稍待", "第三句");
        ok(StartupText.ROTATE_MS >= 5000 && StartupText.ROTATE_MS <= 8000,
                "轮播间隔落在 5～8 秒区间：" + StartupText.ROTATE_MS + "ms");

        eq(StartupText.rotateAt(0), StartupText.ROTATING[0], "0 秒 → 第一句");
        eq(StartupText.rotateAt(StartupText.ROTATE_MS - 1), StartupText.ROTATING[0],
                "一个周期内仍是第一句");
        eq(StartupText.rotateAt(StartupText.ROTATE_MS), StartupText.ROTATING[1], "到点换第二句");
        eq(StartupText.rotateAt(StartupText.ROTATE_MS * 2), StartupText.ROTATING[2], "再换第三句");
        eq(StartupText.rotateAt(StartupText.ROTATE_MS * 3), StartupText.ROTATING[0], "一轮后回到第一句");
        eq(StartupText.rotateAt(StartupText.ROTATE_MS * 7 + 123), StartupText.ROTATING[1], "任意时刻对得上");
        eq(StartupText.rotateAt(-5000), StartupText.ROTATING[0], "时钟回拨按 0 处理，不抛不空");

        // --- 命令筛选（拿真实命令表当基准）---
        File cmdFile = firstExisting(new File("../data/commands.txt"), new File("data/commands.txt"));
        if (cmdFile == null) {
            ok(false, "找不到 data/commands.txt（cwd=" + new File(".").getAbsolutePath() + "）");
            return;
        }
        List<CommandCatalog.Command> cmds = CommandCatalog.parseCommands(readText(cmdFile));
        eq(cmds.size(), 112, "真实命令表 112 条");

        eq(CommandCatalog.suggest("hello", cmds, 8).size(), 0, "不以 / 开头 → 不弹建议");
        eq(CommandCatalog.suggest("/", cmds, 5).size(), 5, "只打 / → 按原序给前几条（截到上限）");
        eq(CommandCatalog.suggest("/", cmds, 5000).size(), 112, "上限够大时给全部");

        List<String> se = CommandCatalog.suggest("/se", cmds, 8);
        ok(se.size() > 0, "/se 有命中，实际：" + se);
        ok(se.size() <= 8, "条数不超过上限");

        // 前缀命中的必须整体排在前 —— 否则"往下打"时候选会跳位
        List<String> st = CommandCatalog.suggest("/st", cmds, 20);
        if (st.size() >= 2) {
            ok(st.get(0).substring(1).toLowerCase().startsWith("st"),
                    "前缀命中的排在最前，实际第一条：" + st.get(0));
        }

        // 无重复：同一条命令不该因为既前缀命中又词内命中而出现两次
        List<String> many = CommandCatalog.suggest("/s", cmds, 50);
        java.util.Set<String> uniq = new java.util.HashSet<String>(many);
        eq(uniq.size(), many.size(), "建议清单无重复");

        // 边界输入不炸
        eq(CommandCatalog.suggest(null, cmds, 8).size(), 0, "null 输入 → 空");
        eq(CommandCatalog.suggest("/x", null, 8).size(), 0, "null 命令表 → 空");
        eq(CommandCatalog.suggest("/x", cmds, 0).size(), 0, "上限 0 → 空");

        // 每一条建议都必须是真命令（不能凭空冒出来）
        for (String s : many) {
            CommandCatalog.Command c = null;
            for (CommandCatalog.Command x : cmds) {
                if (x.cmd.equals(s)) c = x;
            }
            ok(c != null, "建议来自命令表：" + s);
        }
    }

    /**
     * P. 界面风格 —— 与「背景主题」正交的那一维。
     *
     * 2026-10-04：用户明确说除「初雪」外的几套他都不满意，**删到只剩一套**。
     * 所以这里改成钉住"只剩初雪、其余查不到"，并用初雪的四个形状刻度锚住参考 App。
     */
    static void testStyles() {
        System.out.println("== P. 界面风格 ==");

        eq(Theme.STYLES.size(), 1, "只剩一套界面风格（初雪）");
        eq(Theme.STYLES.get(0).id, "yuki", "唯一一套 id = 初雪");
        eq(Theme.STYLES.get(0).name, "初雪", "名字");
        // 用户点名删掉的三套 —— 现在必须查不到（"别的风格真的消失了"的根据）
        eq(Theme.styleById("modern"), null, "现代化已移除");
        eq(Theme.styleById("pixel"), null, "像素风已移除");
        eq(Theme.styleById("ancient"), null, "古风已移除");
        eq(Theme.styleById("nope"), null, "未知风格返回 null");

        for (Theme.Style s : Theme.STYLES) {
            ok(s.radiusDp >= 0, s.id + " 圆角非负");
            ok(s.borderWidthDp >= 0, s.id + " 描边非负");
            ok("sans".equals(s.font) || "mono".equals(s.font) || "serif".equals(s.font),
                    s.id + " 字体族合法：" + s.font);
            ok(s.hint != null && s.hint.length() > 0, s.id + " 有说明");
        }

        // 初雪本身的形状：16 圆角 + 极细边 + 不加装饰（留白与阴影说话）
        eq(Theme.styleById("yuki").radiusDp, 16, "初雪圆角 16");
        eq(Theme.styleById("yuki").borderWidthDp, 1, "初雪极细边");
        eq(Theme.styleById("yuki").decor, "none", "初雪不加装饰");
        eq(Theme.styleById("yuki").titleRule, false, "初雪不带题签");
        // token 必须带出来，否则 Theming 画的时候拿不到
        eq(Theme.tokens(Theme.Config.defaults()).decor, "none", "token 带出装饰");

        // 兜底：未知风格回落现代化（不是崩、也不是空）
        eq(Theme.normalize(new Theme.Config("clear", "blue", "nope",
                Theme.DEFAULT_BG, "", "", 1)).style, "yuki", "未知风格 → 回落默认（初雪）");
        eq(Theme.Config.defaults().style, "yuki", "默认风格是初雪");

        // ---- 风格与颜色互不干扰（正交性的核心断言）----
        // 现在只有初雪一套，所以"换风格"退化成恒等 —— 但**正交性仍要钉住**：
        // 换风格不能牵连颜色，且未知/已删风格要兜回初雪而不是崩。
        Theme.Config base = Theme.Config.defaults();
        Theme.Config unknown = base.withStyle("pixel");   // pixel 已删 → 应兜回初雪
        eq(unknown.style, "yuki", "未知/已删风格 → 兜回初雪");
        eq(unknown.preset, base.preset, "换风格不动背景主题");
        eq(unknown.accent, base.accent, "换风格不动主色");
        eq(unknown.cardAlpha, base.cardAlpha, "换风格不动透明度");
        eq(base.with("night", "rose").style, "yuki", "换背景主题不动风格");

        // token 的形状字段来自初雪
        Theme.Tokens m = Theme.tokens(base);
        eq(m.styleId, "yuki", "token 带出风格 id");
        eq(m.radius, Theme.styleById("yuki").radiusDp, "初雪圆角");
        eq(m.hairWidth, Theme.styleById("yuki").borderWidthDp, "初雪描边宽度");
        eq(m.font, "sans", "初雪字体 token");

        // ---- 形状刻度：初雪下必须正好等于参考 App 的 Shape.kt ----
        // （FieldCorner 12 / ButtonCorner 12 / BubbleCorner 18 / SheetCorner 24）——
        // 值写死是有意的：它们锚的是**参考**，参考改了这里要跟着改，不能悄悄漂。
        Theme.Tokens yk = Theme.tokens(Theme.Config.defaults());
        eq(yk.fieldRadius, 12, "初雪：输入框圆角 = 参考的 FieldCorner");
        eq(yk.buttonRadius, 12, "初雪：按钮圆角 = 参考的 ButtonCorner");
        eq(yk.bubbleRadius, 18, "初雪：气泡圆角 = 参考的 BubbleCorner");
        eq(yk.sheetRadius, 24, "初雪：弹层圆角 = 参考的 SheetCorner");
        // 间距语义断言：卡片间距必须比"组内行距"大 —— 挤在一起那点阴影就白做了
        ok(Ui.CARD_GAP > Ui.S2, "卡片间距（" + Ui.CARD_GAP + "）> 组内行距（" + Ui.S2 + "）");

        // JSON 往返要带上风格，否则重启就丢
        Theme.Config round = Theme.parse(base.toJson());
        eq(round.style, "yuki", "JSON 往返：风格");
        eq(round.preset, base.preset, "JSON 往返：主题");
        eq(Theme.parse("{\"preset\":\"night\",\"accent\":\"green\"}").style, "yuki",
                "老配置没有 style 字段 → 落到默认（初雪），不是崩、也不是空白");
    }

    /**
     * O2. 页头标题（{@link ChatTitle}）与「再按一次退出」（{@link BackExit}）—— 两段纯逻辑。
     *
     * 两者都是"用户 2026-10-04 新提的行为"，且都是**边界易错**：标题的换行/超长截断、
     * 返回的时间窗口。纯逻辑就该被钉住，别等真机才发现。
     */
    static void testChatTitleAndBackExit() {
        System.out.println("== O2. 标题与返回键 ==");

        // 标题：默认、单行、多行压空白、超长截断
        eq(ChatTitle.fromMessage(null), "对话", "null → 默认");
        eq(ChatTitle.fromMessage("   "), "对话", "全空白 → 默认");
        eq(ChatTitle.fromMessage("帮我看看这周行程"), "帮我看看这周行程", "短句原样");
        eq(ChatTitle.fromMessage("第一行\n第二行"), "第一行 第二行", "换行压成一个空格");
        eq(ChatTitle.fromMessage("a\t\tb"), "a b", "连续空白压成一个");
        // 2026-10-06（UI 审计 §2.4）：**不再按 char 截断** —— 能显示多少交给控件宽度
        //（页头走 singleLine + ellipsize=END）。所以长句原样保留，只做空白归一。
        String longMsg = "这是一句特别特别长的第一句话用来测试标题会不会被正确地截断掉";
        eq(ChatTitle.fromMessage(longMsg), longMsg, "不再按字数截断：长句原样保留（宽度交给控件）");
        ok(!ChatTitle.fromMessage(longMsg).endsWith("…"), "不再自己补省略号");
        eq(ChatTitle.clean(""), "对话", "空标题回默认");
        // 不截断 ⇒ 代理对不可能被劈开；这里守住"空白归一不破坏 emoji"
        String emoji = "\ud83d\ude00";
        StringBuilder sb = new StringBuilder("a");
        for (int i = 0; i < 12; i++) sb.append(emoji);
        eq(ChatTitle.fromMessage(sb.toString()), "a" + sb.substring(1), "空白归一无损 emoji");
        eq(ChatTitle.fromMessage(longMsg + "\n" + longMsg), longMsg + " " + longMsg,
                "多行压成一个空格后仍不截断");

        // 返回键：窗口内第二下才退
        BackExit.reset();
        ok(!BackExit.shouldExit(1000), "第一下不退（只提示）");
        ok(BackExit.shouldExit(1000 + BackExit.WINDOW_MS), "窗口内第二下 → 退");
        BackExit.reset();
        BackExit.shouldExit(5000);
        ok(!BackExit.shouldExit(5000 + BackExit.WINDOW_MS + 1), "超窗口 → 重新计时，不退");
        BackExit.reset();
    }

    private static Transcript.Event ev(String type, String payload) {
        return Transcript.fromSse(type, payload);
    }

    private static CommandCatalog.Command find(List<CommandCatalog.Command> cmds, String cmd) {
        for (CommandCatalog.Command c : cmds) {
            if (cmd.equals(c.cmd)) return c;
        }
        return null;
    }

    private static File firstExisting(File... cands) {
        for (File f : cands) {
            if (f.isFile()) return f;
        }
        return null;
    }

    private static String readText(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 读 `test/fixtures/api/` 下某个路由的夹具（路径 → 文件名）；没有返回 null。 */
    private static String apiFixture(String path) {
        String name = path.startsWith("/") ? path.substring(1) : path;
        name = name.replace("/", "__") + ".json";
        // ⚠ `:` 不许进文件名 —— Android 共享存储（sdcardfs）直接拒绝建这种名字，
        // 于是带 `:id` 的会话级夹具一同步就 `tar: Cannot open: Operation not permitted`
        //（实测踩到：源仓库里能建，复制到共享存储/副本目录就整条失败）。
        name = name.replace(":", "_");
        File f = new File("test/fixtures/api", name);
        if (!f.isFile()) return null;
        try {
            return readText(f);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * S. 运行控制台（settings 那一面命令的 App 落点）+ 模型页。
     *
     * 最重要的一条：**{@link ConsoleCatalog} 里声明的每个路径都必须有夹具**。
     * 夹具是从一个真跑起来的 `rivet serve` 上抓下来的 —— 有夹具 = 那条路由真返回过 200。
     * 这样界面里就不可能长出"打过去 404"的按钮（凭空发明路径是本项目踩过的坑）。
     */
    static void testConsoleCatalog() throws Exception {
        System.out.println("== S. 运行控制台 / 模型页 ==");

        // ---- 1. 路径真实性：每个声明都必须有真机夹具 ----
        java.util.List<String> paths = ConsoleCatalog.allPaths();
        ok(paths.size() >= 20, "控制台声明了 >= 20 个族（实际 " + paths.size() + "）");
        for (String p : paths) {
            ok(apiFixture(p) != null, "有真机夹具（= 路由真的存在）：" + p);
        }
        ok(ConsoleCatalog.CONFIG.size() > 0 && ConsoleCatalog.INFO.size() > 0,
                "配置族与信息族都非空");

        // ---- 1b. 2026-10-05 走查核出的两处 ----
        // 界面副标题原先是手写的「27 个族」，而列表早长到 30 —— 手写的数迟早漂开。
        // 现在数字从目录算出来，这条断言把它钉在"实际族数"上。
        ok(ConsoleCatalog.totalCount() == paths.size(),
                "「N 个族」= 实际声明数（实际 " + ConsoleCatalog.totalCount()
                        + " vs " + paths.size() + "）");
        // INFO 那批（运行环境 / 状态）是**只读**的：界面上不给写入口 ——
        // 给了的话会冒出能点的开关（account/status 的 loggedIn、environment 的 platform…），
        // 点下去对着只读端点 PUT，用户只看到一句「改不动」。
        ok(ConsoleCatalog.isReadOnly("/environment"), "INFO 族判为只读：/environment");
        ok(!ConsoleCatalog.isReadOnly("/config/approval"), "CONFIG 族可写：/config/approval");
        ok(!ConsoleCatalog.isReadOnly(null), "null 进去也不炸");

        // ---- 1d. /health：HTTP 200 也可能「不能用」（2026-10-05 容器里真打出来的）----
        // 没配 key 时 serve 照样返 200，体是 {"ok":false,"version":"3.27.0"} ——
        // 只看状态码就会把这种"起来了但不能干活"判成就绪，配 key 失败时用户看到的
        // 恰恰是"配置好了"。这条把两个判据的分工钉住。
        ok(ServeHealth.bodyOk("{\"ok\":true,\"version\":\"3.27.0\"}"), "ok:true → 能干活");
        ok(!ServeHealth.bodyOk("{\"ok\":false,\"version\":\"3.27.0\"}"),
                "ok:false → 服务在跑但没配 key（真机实测就是这个体）");
        ok(ServeHealth.bodyOk(""), "空体 → 不妄判（宁放过）");
        ok(ServeHealth.bodyOk("这不是 JSON"), "读不懂 → 不妄判（宁放过）");
        ok(ServeHealth.bodyOk(null), "null → 不妄判");

        // ---- 1c. 新补的族拿到**真实响应**后确实会渲染出内容（不是空卡）----
        // 这是"用户打开设置页会看到东西"这一层能在容器里做的最接近的验证：
        // 夹具是真 serve 抓的原始字节，ConfigView 是设置页实际用的那个渲染器。
        for (String p : new String[]{"/config/vision-model", "/config/image-gen-model",
                "/config/computer-use", "/config/deepseek/auth", "/config/deepseek/cost",
                "/config/deepseek/summary", "/config/balance"}) {
            String body = apiFixture(p);
            ok(body != null, "2026-10-02 补的族有真机夹具：" + p);
            java.util.List<ConfigView.Row> rows = body == null
                    ? null : ConfigView.flatten(body, 2);
            ok(rows != null && !rows.isEmpty(), "会渲染出内容，不是空卡：" + p
                    + "（" + (rows == null ? 0 : rows.size()) + " 行）");
        }

        // ---- 1b. 面板面的路径同样必须存在（同一条防发明断言）----
        java.util.List<String> panels = PanelCatalog.allPaths();
        ok(panels.size() >= 15, "面板面声明了 >= 15 个面板（实际 " + panels.size() + "）");
        for (String p : panels) {
            ok(apiFixture(p) != null, "面板路径有真机夹具：" + p);
        }
        // 两个目录不许撞车（同一张卡出现两次）
        java.util.Set<String> overlap = new java.util.HashSet<String>(paths);
        overlap.retainAll(new java.util.HashSet<String>(panels));
        eq(overlap.size(), 0, "控制台与面板面没有重复路径：" + overlap);

        // ---- 2. 模型页解析：对真夹具 ----
        String provJson = apiFixture("/config/providers");
        java.util.List<Providers.Provider> ps = Providers.parse(provJson);
        ok(ps.size() >= 10, "解析出 >= 10 个服务商（实际 " + ps.size() + "）");

        Providers.Provider first = ps.isEmpty() ? null : ps.get(0);
        ok(first != null && "deepseek".equals(first.name), "第一个服务商是 deepseek");
        eq(first == null ? -1 : first.models.size(), 3, "deepseek 下有 3 个模型");
        ok(first != null && first.keyReady(), "deepseek 的密钥视为已配（source=inline）");
        // 密钥只允许显示服务端掩码 —— 界面绝不显示真 key
        ok(first != null && first.keyRef.startsWith("***"),
                "密钥引用是服务端掩码（*** 开头）：" + (first == null ? "" : first.keyRef));

        int totalModels = 0;
        for (Providers.Provider p : ps) totalModels += p.models.size();
        ok(totalModels >= 30, "所有服务商合计 >= 30 个模型（实际 " + totalModels + "）");

        // ---- 3. 默认模型：null 时回落到 isDefault 服务商 ----
        eq(Providers.defaultModel(apiFixture("/config/default-model")), null,
                "真机上 defaultModel 就是 null（跟随服务商默认）");
        eq(Providers.currentLabel(ps, null), "DeepSeek · deepseek-v4-flash",
                "null 时回落到 isDefault 服务商的首个模型");

        // ---- 4. splitDefault 正反例 ----
        eq(Providers.splitDefault("deepseek:deepseek-v4-pro")[1], "deepseek-v4-pro", "拆 provider:model");
        eq(Providers.splitDefault("nope"), null, "没有冒号 → null");
        eq(Providers.splitDefault(":x"), null, "冒号在开头 → null");
        eq(Providers.splitDefault("x:"), null, "冒号在结尾 → null");
        eq(Providers.splitDefault(null), null, "null 不炸");

        // ---- 5. 量级人话 ----
        eq(Providers.humanTokens(1000000), "1M", "1M");
        eq(Providers.humanTokens(256000), "256K", "256K");
        eq(Providers.humanTokens(32768), "32K", "32K");

        // ---- 6. ConfigView：能改的只有顶层标量 ----
        java.util.List<ConfigView.Row> zen = ConfigView.flatten(apiFixture("/config/zen"), 1);
        eq(zen.size(), 1, "/config/zen 摊平出 1 行");
        ok(zen.get(0).isBool, "enabled 是布尔 → 可做开关");
        eq(zen.get(0).key, "enabled", "可写回的键名就是 enabled");
        eq(zen.get(0).value, ConfigView.boolText(zen.get(0).boolValue), "布尔显示与 boolText 一致");

        java.util.List<ConfigView.Row> mir = ConfigView.flatten(apiFixture("/config/mirrors"), 1);
        boolean sawPreset = false, presetWritable = false;
        for (ConfigView.Row r : mir) {
            if ("preset".equals(r.key)) {
                sawPreset = true;
                presetWritable = r.isBool;      // 字符串不该被当成可写开关
            }
        }
        ok(sawPreset, "mirrors 里有 preset 字段");
        ok(!presetWritable, "preset 是字符串 → 不做成开关（只读展示）");

        // 嵌套字段一律不可写回（key 必须是顶层键）——盲写嵌套 schema 会改坏配置
        java.util.List<ConfigView.Row> routing = ConfigView.flatten(apiFixture("/config/routing"), 2);
        boolean nestedSeen = false, nestedWritable = false;
        for (ConfigView.Row r : routing) {
            if (r.label.indexOf('.') > 0) {
                nestedSeen = true;
                if (r.isBool && r.key != null && r.key.indexOf('.') > 0) nestedWritable = true;
            }
        }
        ok(nestedSeen, "routing 摊出了嵌套字段（review.* / workers.*）");
        ok(!nestedWritable, "嵌套字段的 key 不含点 —— 不会被误当成可写回");

        // ---- 7. 错误体：把服务端那句人话带出来，而不是"HTTP 400" ----
        eq(ApiError.human("{\"error\":\"defaultModel is required (\\\"p:m\\\" format)\"}"),
                "defaultModel is required (\"p:m\" format)", "从 {\"error\":…} 里取人话");
        eq(ApiError.human(""), "（无内容）", "空错误体不炸");
    }

    /**
     * T. 每条命令在 App 里的处置分类。
     *
     * 这是"104 条命令长得一模一样地列着"这个问题的正面回答：界面必须说清楚哪条有原生界面、
     * 哪条本质是发给天枢、哪条是终端产物用不了。断言保证**104 条无遗漏**，且列表里
     * 不出现写错名字的命令、不指向不存在的页。
     */
    static void testCommandFate() throws Exception {
        System.out.println("== T. 每条命令的 App 处置 ==");

        File cmdFile = firstExisting(new File("../data/commands.txt"), new File("data/commands.txt"));
        if (cmdFile == null) {
            ok(false, "找不到 data/commands.txt");
            return;
        }
        CommandCatalog.Catalog cat = CommandCatalog.load(readText(cmdFile), "{}");
        eq(cat.commands.size(), 112, "命令清单 112 条");

        java.util.Set<String> all = new java.util.HashSet<String>();
        for (CommandCatalog.Command c : cat.commands) all.add(c.cmd);
        eq(all.size(), 112, "命令名 112 个不重复");

        int nativeN = 0, sendN = 0, termN = 0;
        for (CommandCatalog.Command c : cat.commands) {
            CommandFate.Kind k = CommandFate.of(c.cmd);
            if (k == CommandFate.Kind.NATIVE) nativeN++;
            else if (k == CommandFate.Kind.TERMINAL) termN++;
            else sendN++;
            String w = CommandFate.where(c.cmd);
            ok(w != null && !w.isEmpty(), c.cmd + " 有处置说明");
        }
        eq(nativeN + sendN + termN, 112, "三类合计 = 112（无遗漏）");
        ok(nativeN > 0 && termN > 0 && sendN > 0,
                "三类都非空：原生 " + nativeN + " · 发给天枢 " + sendN + " · 终端 " + termN);

        // 列表里不许出现命令清单没有的名字（防写错）
        for (String c : CommandFate.nativeCommands()) {
            ok(all.contains(c), "NATIVE 里是真命令：" + c);
            ok(CommandFate.whereIsRealPage(c), c + " 指向真实页面：" + CommandFate.where(c));
        }
        for (String c : CommandFate.terminalCommands()) {
            ok(all.contains(c), "TERMINAL 里是真命令：" + c);
        }
        java.util.Set<String> both = new java.util.HashSet<String>(CommandFate.nativeCommands());
        both.retainAll(CommandFate.terminalCommands());
        eq(both.size(), 0, "NATIVE 与 TERMINAL 不重叠");

        // 抽查关键几条
        eq(CommandFate.of("/connect"), CommandFate.Kind.NATIVE, "/connect 有原生界面（模型页）");
        eq(CommandFate.of("/config"), CommandFate.Kind.NATIVE, "/config 有原生界面（设置页）");
        eq(CommandFate.of("/cache"), CommandFate.Kind.NATIVE, "/cache 有原生界面（面板页）");
        eq(CommandFate.of("/vim"), CommandFate.Kind.TERMINAL, "/vim 是终端产物");
        eq(CommandFate.of("/council"), CommandFate.Kind.SEND, "/council 本质是发给天枢");
        eq(CommandFate.of(null), CommandFate.Kind.SEND, "null 不炸，按 SEND 处理");

        // 派生一致性：显示分类必须由路由表投影出来 —— 两张表不许再各写一份（防漂移）
        boolean derived = true;
        for (String c : all) {
            CommandRouting.Row r = CommandRouting.of(c);
            if (r == null) { derived = false; continue; }
            CommandFate.Kind want = (CommandRouting.NAV.equals(r.action)
                    || CommandRouting.API.equals(r.action)
                    || CommandRouting.LOCAL.equals(r.action)
                    || CommandRouting.READ.equals(r.action)) ? CommandFate.Kind.NATIVE
                    : CommandRouting.SEND.equals(r.action) ? CommandFate.Kind.SEND
                    : CommandFate.Kind.TERMINAL;
            if (CommandFate.of(c) != want) derived = false;
        }
        ok(derived, "104 条显示分类全部由路由表派生（两张表不再各写一份）");
    }

    /**
     * P. UI 行语言一致性（2026-10-04「整体重做」那一轮的护栏）。
     *
     * 这一轮把模型页 / 命令面板 / 对话页侧栏的自造行控件统一到了 {@link Kit}，
     * 并给异步页补了加载态 / 错误态。这类改动**不会崩、不会报错** —— 有人再手搓一个卡片行、
     * 或把 loading 删回去，只有在真机上才看得出来（本环境对屏幕是盲的）。
     * 所以照 {@code testAppearanceCoverage} 的老办法，扫源码把它钉住。
     */
    static void testUiLanguageCoverage() throws Exception {
        System.out.println("== P. UI 行语言一致性 ==");

        String models = sourceOf("ModelsActivity");
        ok(models != null && models.contains("Kit.group("), "模型页用 Kit.group 分组");
        ok(models != null && models.contains("Kit.choiceRow("), "模型页用 Kit.choiceRow 选默认模型");
        ok(models != null && models.indexOf("providerCard(") < 0, "模型页不再自造 providerCard");

        String cmd = sourceOf("CommandPanelActivity");
        ok(cmd != null && cmd.contains("Kit.group("), "命令面板用 Kit.group 分组");
        ok(cmd != null && cmd.contains("Kit.menuRow("), "命令面板用 Kit.menuRow 画命令行");
        ok(cmd != null && cmd.indexOf("sectionHeader(") < 0, "命令面板不再自造 sectionHeader");

        String chat = sourceOf("ChatActivity");
        ok(chat != null && chat.indexOf("Kit.roundButton(this, Icon.CLOSE") >= 0,
                "对话页侧栏有关闭圆钮（自绘图标）");
        // 用户 2026-10-04："不要让我左右滑动才能看，就一行完整" —— 状态 chips 一律不许用横滑容器
        ok(chat != null && chat.indexOf("HorizontalScrollView") < 0,
                "状态 chips 不再是横滑（不许让用户左右滑动）");
        ok(chat != null && chat.indexOf("chipsRow.addView(spacer") >= 0,
                "状态 chips 之间有弹性空隙（铺满一行，右边不留白）");
        // 用户 2026-10-04："怎么有弧形的线" —— 卡片/按钮的装饰一律不许画弧线。
        // 上一版在 StyleDecor 里用 drawArc 画"毛玻璃受光"，那两道椭圆弧正是用户看到的东西。
        String decorSrc = sourceOf("StyleDecor");
        ok(decorSrc != null && stripComments(decorSrc).indexOf("drawArc") < 0,
                "卡片装饰不画弧线（用户反馈「怎么有弧形的线」）");
        // 全局缩放已按用户要求回退（2026-10-04："界面缩放回退到上一版，不用了"）
        ok(Ui.SCALE == 1.0f, "全局缩放已回退到 1.0（不缩放）");
        // 2026-10-04：计时从状态行挪到上方居中卡片
        ok(chat != null && chat.indexOf("当前会话用时：") >= 0,
                "对话页有「当前会话用时」卡片");
        // 2026-10-04：系统状态栏要透出 app 背景（edge-to-edge）
        // 2026-10-05（C1）：实现搬进 Theming.applyStatusBar —— 好让 Theming.refresh() 也能调它
        //   （治"外观页切深色后返回、状态栏图标不跟着变"）。契约跟着实现走，且收紧成两条：
        //   ① 实现（LAYOUT_FULLSCREEN）在 Theming；② BaseActivity 只转发、不自己再写一份。
        String baseSrc = sourceOf("BaseActivity");
        String themingSrc = sourceOf("Theming");
        ok(themingSrc != null && themingSrc.indexOf("SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN") >= 0,
                "状态栏走 edge-to-edge（背景铺到屏幕顶端）—— 实现在 Theming.applyStatusBar");
        ok(baseSrc != null && baseSrc.indexOf("Theming.applyStatusBar") >= 0,
                "BaseActivity 只转发同一条实现（不再各写一份）");
        ok(chat != null && chat.indexOf("cmdGroupHeader(") < 0,
                "对话页命令抽屉不再自造 cmdGroupHeader");
        ok(chat != null && chat.contains("commandGroup("),
                "对话页命令抽屉走 Kit.group 分组（与命令面板同一行语言）");

        String panels = sourceOf("PanelsActivity");
        ok(panels != null && panels.indexOf("Kit.roundButton(") >= 0,
                "面板页返回口是圆钮（不再是「‹ 返回」文字钮）");

        String appkit = sourceOf("AppKit");
        ok(appkit != null && appkit.contains("public static View loading("), "AppKit 有统一加载态");
        ok(appkit != null && appkit.contains("public static View error("), "AppKit 有统一错误态");

        String appear = sourceOf("AppearanceActivity");
        if (appear == null) {
            ok(false, "读不到 AppearanceActivity");
        } else {
            int sections = countOf(appear, "section(\"");
            ok(sections <= 4, "外观页分组数 <= 4（实际 " + sections + "）");
            ok(appear.contains("Kit.roundButton("), "外观页预览用圆钮（模拟对话片段，不再只是小卡 + 按钮）");
        }

        // 命令条数不许写死在源码里 —— 2026-10-06 **装机核出**：空态卡硬写着「104 条命令」，
        // 而 harness 已升到 3.28.0（112 条）。RED 证据就是真机 dump 里那行字。
        // 写死 = 下次升级再漂一次；现在由 catalog() 派生。
        ok(chat != null && stripComments(chat).indexOf("104 条命令") < 0,
                "ChatActivity 不写死命令条数（会随 harness 升级漂）—— 注释里提到不算");
        ok(chat != null && chat.contains("catalog().commands.size()"),
                "空态卡的命令条数由目录派生");
    }

    /** needle 在 haystack 里出现几次（源码级护栏计数用）。 */
    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * Q. 界面**不用字符当图标**（2026-10-04 第四轮的护栏）。
     *
     * 在此之前全 App 的图标都是"拿一个 Unicode 符号当字打印"（`☰` 菜单、`☥` 星域、
     * `⚡` 缓存、`▸` 审批 …）。问题不是好不好看 —— 是**字形覆盖全靠设备字体碰运气**：
     * 334 个系统字体里这些符号基本只有 1 个覆盖（`⏵` 更是 0 个，真机直接豆腐块）。
     * 现在一律走自绘 {@link Icon}，几何图形没有这个依赖。
     *
     * 扫的是**去掉注释后的代码**：注释里提到 `☰`（比如解释为什么不许用）是应该的，
     * 不该被这条规则误伤 —— 上一版的 `testAppearanceCoverage` 就在这上面误报过一次。
     */
    static void testNoGlyphIcons() throws Exception {
        System.out.println("== Q. 界面不用字符当图标 ==");

        String[] banned = {
                "\u2630", "\u2625", "\u25E7", "\u25F7", "\u26A1", "\u2726", "\u2699", "\u25D0",
                "\u25C8", "\u25B8", "\u25BE", "\u25B4", "\u2318", "\u22EE", "\u22EF",
                "\u2039", "\u203A", "\u2713", "\u2717", "\u00D7", "\u2191", "\u25CF", "\u25A0",
        };
        String[] names = {
                "☰ U+2630", "☥ U+2625", "◧ U+25E7", "◷ U+25F7", "⚡ U+26A1", "✦ U+2726",
                "⚙ U+2699", "◐ U+25D0", "◈ U+25C8", "▸ U+25B8", "▾ U+25BE", "▴ U+25B4",
                "⌘ U+2318", "⋮ U+22EE", "⋯ U+22EF", "‹ U+2039", "› U+203A", "✓ U+2713",
                "✗ U+2717", "× U+00D7", "↑ U+2191", "● U+25CF", "■ U+25A0",
        };
        eq(banned.length, names.length, "禁止清单与名字表一一对应");

        File dir = new File("src/dev/tianshu/host");
        File[] files = dir.listFiles();
        if (files == null) {
            ok(false, "列不出源码目录");
            return;
        }
        int violations = 0;
        int checked = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            String code = stripComments(readText(f));
            checked++;
            for (int i = 0; i < banned.length; i++) {
                if (code.indexOf(banned[i]) >= 0) {
                    violations++;
                    ok(false, f.getName() + " 的代码里还有 " + names[i]
                            + " —— 图标一律用 Icon 自绘，别再拿字符当图标");
                }
            }
        }
        ok(violations == 0, "没有源码再用字符当图标（扫了 " + checked + " 个文件）");
        ok(checked >= 20, "扫过的源码文件数 = " + checked + "（少于 20 说明路径不对）");
    }

    /**
     * S. 主题材料**不得把 token 烘焙进裸 Drawable**。
     *
     * 踩过的坑（2026-10-06 用户报「外观页调完配色再点对话页，底部颜色没改，对话框颜色也没改」）：
     * 「底栏药丸 + 选中格 + 输入面板（对话框）」的底是页面里
     * `new GradientDrawable()` + `Theming.tokens(...).card` **当场算出来**的；
     * 而 {@code Theming.refresh()} → {@code walk()} 只重刷**带角色标签**的视图（每次重建 Drawable），
     * 烘焙的那层没有任何人管 —— 换主题后它停在旧色，叠到新背景上就成了一块脏灰。
     *
     * 不变量：**底色一律走 Theming 角色**。允许例外只有两处，各有理由：
     *   - `Theming.java`：角色机件本身就在造这些 Drawable；
     *   - `AppearanceActivity.java`：色板 / 主色圆点画的是**被预览的那个颜色本身**，不是主题 token。
     */
    static void testNoBakedThemeDrawable() throws Exception {
        System.out.println("== S. 主题材料不得烘焙 Drawable ==");

        String[] allow = { "Theming.java", "AppearanceActivity.java" };
        String needle = "new GradientDrawable";
        File dir = new File("src/dev/tianshu/host");
        File[] files = dir.listFiles();
        if (files == null) {
            ok(false, "列不出源码目录");
            return;
        }
        int checked = 0;
        int violations = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            boolean allowed = false;
            for (String a : allow) {
                if (a.equals(f.getName())) allowed = true;
            }
            if (allowed) continue;
            checked++;
            if (stripComments(readText(f)).indexOf(needle) >= 0) {
                violations++;
                ok(false, f.getName() + " 把主题底色烘焙进了裸 Drawable —— 换主题时它不会跟着变；"
                        + "改用 Theming.tag(v, Theming.ROLE_PANEL_NAV / ROLE_PANEL_INPUT / ROLE_PILL_SEL)");
            }
        }
        ok(violations == 0, "没有页面再把主题底色烘焙进 Drawable（扫了 " + checked + " 个文件）");
        ok(checked >= 15, "扫过的源码文件数 = " + checked + "（少于 15 说明路径不对）");
    }

    /** 去掉 Java 源码里的注释（行注释 + 块注释），保留字符串字面量。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        boolean line = false;
        boolean block = false;
        boolean str = false;
        boolean chr = false;
        while (i < n) {
            char c = src.charAt(i);
            char d = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                }
                i++;
                continue;
            }
            if (block) {
                if (c == '*' && d == '/') {
                    block = false;
                    i += 2;
                    continue;
                }
                if (c == '\n') out.append(c);
                i++;
                continue;
            }
            if (str || chr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(d);
                    i += 2;
                    continue;
                }
                if (str && c == '"') str = false;
                if (chr && c == '\'') chr = false;
                i++;
                continue;
            }
            if (c == '/' && d == '/') {
                line = true;
                i += 2;
                continue;
            }
            if (c == '/' && d == '*') {
                block = true;
                i += 2;
                continue;
            }
            if (c == '"') str = true;
            if (c == '\'') chr = true;
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 读 src 下某个 host 类的源码；读不到返回 null（由调用方判，不静默当"没问题"）。 */
    private static String sourceOf(String simpleName) {
        try {
            return readText(new File("src/dev/tianshu/host", simpleName + ".java"));
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * R. 增量渲染调度 —— 对话页"卡"的主治。
     *
     * 核心断言：**不管对话多长，每帧只重画最后一轮**。这是"别再整页重建"的可断言形式：
     * 原先每帧 `content.removeAllViews()` 把整段对话重造一遍，对话越长每帧越重，
     * 而流式期间每 250ms 就一帧 —— 于是"用着好卡，尤其对话时"。
     */
    /**
     * S. 会话生命周期 —— 续聊水位、增量流 URL、改名/归档/取消归档/删除的动作映射。
     *
     * 为什么要有这一节：App 曾因为「用 GET 探 POST 路由」把续聊与全部会话管理能力
     * 误判成「不存在」，于是历史会话被做成只读。这一节把每条路由的形状钉死在断言里，
     * 以后谁再改错，红灯会先响。
     *
     * 三条真会静默出错的东西：
     *   1. 续聊不带 `since` → 服务端重放整段历史 → 轮次翻倍；
     *   2. `replay_window` 的 `seq:0` 把水位打回 0 → 下次续聊退化成全量重放；
     *   3. 「删除」写成裸 `DELETE /sessions/:id` → 那其实是**归档**（软删），用户以为删了、磁盘还占着。
     */
    static void testSessionLifecycle() {
        System.out.println("== S. 会话生命周期（续聊 / 改名 / 归档 / 删除）==");

        // 1) 增量流 URL：续聊必须带 since，否则重放整段历史
        eq(AppRuntime.streamUrl("abc", 0),
                "http://127.0.0.1:18799/sessions/abc/stream?since=0", "since=0 的重放 URL");
        eq(AppRuntime.streamUrl("abc", 37),
                "http://127.0.0.1:18799/sessions/abc/stream?since=37", "since=37 的增量 URL");
        eq(AppRuntime.streamUrl("abc"),
                "http://127.0.0.1:18799/sessions/abc/stream?since=0", "旧签名等价于 since=0（新会话）");

        // 2) 水位单调：头部事件带 seq:0，不能把水位打回 0
        ConversationState st = new ConversationState();
        st.apply("status", "{\"seq\":1,\"type\":\"status\"}");
        st.apply("text_delta", "{\"seq\":2,\"type\":\"text_delta\",\"data\":{\"text\":\"a\"}}");
        st.apply("turn_complete", "{\"seq\":3,\"type\":\"turn_complete\"}");
        eq(st.lastSeq, 3, "正常事件把水位推到 3");
        st.apply("replay_window",
                "{\"seq\":0,\"type\":\"replay_window\",\"data\":{\"floorSeq\":1,\"diskLastSeq\":9}}");
        eq(st.lastSeq, 3, "replay_window 的 seq:0 不回退水位（回退会让续聊退化成全量重放）");
        st.apply("job_snapshot", "{\"seq\":0,\"type\":\"job_snapshot\",\"data\":{\"jobs\":[]}}");
        eq(st.lastSeq, 3, "job_snapshot 的 seq:0 同样不回退");

        // 3) 动作映射：用户说的「删除」= 永久删除，不是软删的裸 DELETE
        eq(SessionActions.methodFor(SessionActions.RENAME), "PATCH", "改名走 PATCH");
        eq(SessionActions.pathFor(SessionActions.RENAME, "s1"), "/sessions/s1", "改名路径");
        eq(SessionActions.methodFor(SessionActions.ARCHIVE), "DELETE", "归档走 DELETE");
        eq(SessionActions.pathFor(SessionActions.ARCHIVE, "s1"), "/sessions/s1",
                "归档 = 裸 session 路径（服务端语义：软删，磁盘保留）");
        eq(SessionActions.methodFor(SessionActions.UNARCHIVE), "POST", "取消归档走 POST");
        eq(SessionActions.pathFor(SessionActions.UNARCHIVE, "s1"), "/sessions/s1/unarchive", "取消归档路径");
        eq(SessionActions.methodFor(SessionActions.DELETE), "DELETE", "删除走 DELETE");
        eq(SessionActions.pathFor(SessionActions.DELETE, "s1"), "/sessions/s1/permanent",
                "删除 = /permanent（不可逆；未归档时服务端 409）");

        eq(SessionActions.renameBody("你好"), "{\"title\":\"你好\"}", "改名 body");
        eq(SessionActions.renameBody("a\"b"), "{\"title\":\"a\\\"b\"}", "改名 body 转义引号");
        eq(SessionActions.renameBody(null), "{\"title\":\"\"}", "null 标题按空串（服务端语义：清除标题）");

        // 4) 错误文案：界面要能照做，而不是显示裸 HTTP 码
        ok(SessionActions.humanError(409, "{\"error\":\"Session is already running\"}").indexOf("运行") >= 0,
                "409 → 说人话（会话正在运行 / 未归档）");
        ok(SessionActions.humanError(404, "").indexOf("不存在") >= 0, "404 → 会话不存在");
        ok(SessionActions.humanError(500, "").length() > 0, "其它码也要给一句话，不返回空串");

        // 5) 归档字段解析（Wave 0 实测：归档条目才有 archived:true，默认列表条目没有该键）
        List<SessionList.Item> arch = SessionList.parse(
                "{\"sessions\":[{\"id\":\"a\",\"title\":\"t\",\"archived\":true}]}");
        eq(arch.size(), 1, "解析归档条目");
        eq(arch.get(0).archived, true, "读出 archived:true");
        List<SessionList.Item> live = SessionList.parse(
                "{\"sessions\":[{\"id\":\"b\",\"title\":\"t\"}]}");
        eq(live.get(0).archived, false, "缺 archived 键 → false（默认列表条目不归档）");
    }

    /**
     * T. 命令路由表 —— 104 条 `/` 命令在 App 里各归哪一类。
     *
     * 盯住的是那个真实 bug：本地执行类的命令（服务端根本不会执行）从前会被原样发出去，
     * 于是 `/connect` 变成了一句对话。这里把"表必须覆盖全部命令、必须判类正确"钉死。
     */
    static void testCommandRouting() {
        System.out.println("== T. 命令路由表 ==");

        eq(CommandRouting.size(), 112, "112 条命令全部登记（与 data/commands.txt 一致）");

        // harness 3.28.0 新增的 8 条（官方更新补缺）—— 缺了它们，handleCommand 会
        // row==null → return false，把命令当普通消息发给模型（同「敲 /connect 却进了对话」那一类）。
        // 8 条在 harness 里都是终端本端能力（FrontendWorkflow 前端命令 / 进程内 gate），App 归 tui。
        for (String c : new String[] { "/cvm", "/editor", "/keybindings", "/metrics",
                "/paste", "/stash", "/thinking", "/tui" }) {
            CommandRouting.Row r = CommandRouting.of(c);
            ok(r != null, "harness 3.28.0 新命令已登记：" + c);
            if (r != null) {
                eq(r.action, CommandRouting.TUI, c + " 归 tui（终端本端能力，无 HTTP 对端）");
                ok(CommandRouting.blockedHint(r).length() > 0, c + " 有可展示说明");
            }
        }

        CommandRouting.Row connect = CommandRouting.of("/connect");
        ok(connect != null, "/connect 在表里");
        if (connect != null) {
            eq(connect.action, CommandRouting.NAV, "/connect 是跳页面而非发给模型");
            eq(connect.available, true, "/connect 已接上");
            ok(connect.target.indexOf("模型") >= 0, "/connect 落到模型页：" + connect.target);
        }

        // 交运行时的 19 条 = 7 条由运行时解析（/plan /plan-close /write-plan /review
        // /council /galaxy /team —— 经 POST /sessions/:id/prompt 的 resolveAppPromptInput）
        // + 12 条指令桥。/new 由 App 自己开新会话。
        // 2026-10-03 修正：`/panel` `/queue` 从前被塞进 send 却**恒被运行时 400**
        //（`Unknown slash command`）—— 它们不是"终端本地能力"，而是有对端没接。
        int send = 0;
        for (String c : CommandRouting.commands()) {
            if (CommandRouting.SEND.equals(CommandRouting.of(c).action)) send++;
        }
        eq(send, 19, "19 条交运行时（运行时解析 7 + 指令桥 12）");
        eq(CommandRouting.of("/panel").action, CommandRouting.NAV,
                "/panel 跳面板页（从前 send → 运行时恒 400）");
        eq(CommandRouting.of("/queue").action, CommandRouting.API,
                "/queue 走 queue 端点（从前 send → 运行时恒 400）");
        eq(CommandRouting.of("/plan-close").action, CommandRouting.SEND,
                "对照：/plan-close 仍交运行时（2026-10-03 活体实测 200）");
        eq(CommandRouting.of("/new").action, CommandRouting.LOCAL, "/new 是 App 本地动作，不发模型");
        eq(CommandRouting.of("/connect").action.equals(CommandRouting.SEND), false,
                "对照：/connect 绝不是 SEND");

        // 没有悬案：104 条全部落到六类之一（不再有"还没落定"的命令）
        int blocked = 0;
        java.util.Set<String> actions = new java.util.HashSet<String>();
        for (String c : CommandRouting.commands()) {
            CommandRouting.Row r = CommandRouting.of(c);
            if (!r.available) blocked++;
            actions.add(r.action);
            ok(CommandRouting.blockedHint(r).length() > 0, "每条都有可展示的说明：" + c);
        }
        eq(blocked, 0, "没有悬而未决的命令（104 条全部落定）");
        eq(actions.size(), 7, "动作集合恰为七类：nav/local/api/send/none/tui/read");
        // B1：这 5 条的数据在会话目录里已落盘，App 侧直接读（见 LocalData）——不再标 tui
        for (String c : new String[] { "/todo", "/context", "/debug", "/memory", "/verify" }) {
            eq(CommandRouting.of(c).action, "read", "已落盘命令走本地读盘：" + c);
        }
        // P2：星图 / 编年史 / 蓝图 三条从 tui 改 nav —— 数据在 .rivet/constellation.json，App 直接读
        for (String c : new String[] { "/starmap", "/chronicle", "/constellation" }) {
            CommandRouting.Row r = CommandRouting.of(c);
            eq(r.action, CommandRouting.NAV, "星图类命令跳原生页：" + c);
            ok(CommandFate.whereIsRealPage(c), c + " 落点是真页面：" + r.target);
        }
        // P1：前缀预算归因改走 harness 新端点（不再标 tui）
        eq(CommandRouting.of("/prefix-budget").action, CommandRouting.API,
                "/prefix-budget 走 harness 端点（P1）");
        // 对照：/verbose 是 TUI 自身的显示开关（展开工具卡片），没有 agent 侧对端 —— 仍归 tui
        eq(CommandRouting.of("/verbose").action, CommandRouting.TUI,
                "/verbose 是 TUI 本地显示开关，无可移植端点");
        // P1′：/branch 的父/子关系全在 meta.json（本地可扫）；/mode 在 harness 里只打印一句说明
        eq(CommandRouting.of("/branch").action, "read", "/branch 走本地读盘重建分支树");
        eq(CommandRouting.of("/mode").action, CommandRouting.NONE,
                "/mode 无功能（harness 只打印一句）→ none，App 显示同句文案");
        // B1 回归：read 类必须归 NATIVE（曾漏掉 READ，命令面板把 /todo 等误标「终端专用」）
        eq(CommandFate.of("/todo"), CommandFate.Kind.NATIVE, "read 类归 NATIVE（B1 漏网修复）");
        eq(CommandFate.of("/branch"), CommandFate.Kind.NATIVE, "read 类归 NATIVE：/branch");
        // P2′：/chat 与 /mode 同类（harness 里只打印一句、无功能）；/goal-criteria 与 /undo 接端点
        eq(CommandRouting.of("/chat").action, CommandRouting.NONE,
                "/chat 无功能（harness 只打印一句）→ none");
        eq(CommandRouting.of("/goal-criteria").action, CommandRouting.API,
                "/goal-criteria 走既有 goal 端点");
        eq(CommandRouting.of("/undo").action, CommandRouting.API, "/undo 走文件历史端点（P2′）");
        eq(CommandRouting.of("/task").action, CommandRouting.NONE,
                "/task 并非真废弃 —— harness 里同样只打印一句说明 → none");

        // none 类的理由必须写清楚（不能只写"不适用"）
        CommandRouting.Row exit = CommandRouting.of("/exit");
        eq(exit.action, CommandRouting.NONE, "/exit 归 none");
        ok(exit.target.indexOf("返回键") >= 0, "/exit 说清替代方案：" + exit.target);

        // 命令名提取
        eq(CommandRouting.cmdOf("/connect"), "/connect", "无参命令名");
        eq(CommandRouting.cmdOf("  /plan 做个计划  "), "/plan", "带参命令名取第一段");
        eq(CommandRouting.cmdOf("你好"), null, "普通文字不是命令");
        eq(CommandRouting.cmdOf(""), null, "空串不是命令");
        eq(CommandRouting.of("/不存在的命令"), null, "表外命令返回 null");
    }

    /**
     * U. 命令 → HTTP 调用映射 —— 已接上的会话级命令各打哪个端点。
     *
     * 盯住的是「标了能用、实际没接线」那类错：表里写成 api 的命令，必须有执行映射；
     * 端点 / 方法 / 请求体逐条钉死（schema 来自 harness dist 的 handler）。
     */
    /**
     * U. 星图 / 编年史 / 蓝图 的数据源 —— 解析 `.rivet/constellation.json`（P2）。
     *
     * 与 B1 同路数：数据早已落盘，App 直接读，不必改 harness 内核。这里钉住解析、
     * 中文文案（harness 用 ✓/✗ 字形，App 改中文——设备字体覆盖不保证）与路径。
     */
    static void testConstellation() {
        System.out.println("== U. 星图数据（constellation.json）==");

        String json = "{"
                + "\"version\":1,\"projectId\":\"94a6b4475803\",\"name\":\"root\","
                + "\"createdAt\":1790951623787,\"lastUpdatedAt\":1790951623787,"
                + "\"skeleton\":{\"modules\":[{\"path\":\"src/agent\",\"role\":\"loop\"}],"
                + "\"entryPoints\":[\"src/main.ts\"],\"keyAbstractions\":[\"Agent\"],\"techStack\":[\"typescript\"]},"
                + "\"milestones\":[{\"id\":\"e88d1a37c9e1\",\"timestamp\":1790951623787,"
                + "\"sessionId\":\"s1\",\"agentMark\":{\"numericId\":6230,\"symbol\":\"x\",\"domain\":\"qiming\"},"
                + "\"domain\":\"qiming\",\"summary\":\"plan closed\",\"filesChanged\":[\"a.ts\",\"b.ts\"],"
                + "\"type\":\"fix\",\"verificationStatus\":\"verified\",\"cycleClose\":\"\",\"tags\":[\"plan-close\"]}],"
                + "\"architectureShifts\":[{\"id\":\"sh1\",\"summary\":\"split core\"}]"
                + "}";

        Constellation.Doc d = Constellation.parse(json);
        ok(d != null, "解析 constellation.json");
        if (d == null) return;
        eq(d.name, "root", "项目名");
        eq(d.milestones.size(), 1, "里程碑 1 条");
        eq(d.shiftCount, 1, "架构变迁 1 次");
        eq(d.latestShift, "split core", "最近一次变迁摘要");
        eq(d.skeleton.entryPoints.size(), 1, "入口 1 个");
        eq(d.skeleton.modules.get(0), "src/agent (loop)", "模块带 role");

        Constellation.Milestone m = d.milestones.get(0);
        eq(m.type, "fix", "类型 fix");
        eq(m.verificationStatus, "verified", "验证 verified");
        eq(m.filesChanged.size(), 2, "改了 2 个文件");
        eq(Constellation.markLabel(m), "qiming #6230 x", "星域·编号·符号");
        eq(Constellation.typeLabel("fix"), "修复", "类型中文");
        eq(Constellation.typeLabel("nope"), "nope", "未知类型原样返回（不静默）");
        eq(Constellation.verifyLabel("verified"), "已验证", "验证中文");
        eq(Constellation.verifyLabel("?"), "未验证", "未知验证态归未验证");
        ok(Constellation.milestoneLine(m, m.timestamp + 3600000L)
                        .indexOf("修复 · 已验证 · plan closed") == 0,
                "里程碑行以「类型 · 验证 · 摘要」起头：" + Constellation.milestoneLine(m, m.timestamp));
        eq(Constellation.relativeTime(1000L, 1000L + 5 * 60 * 1000L), "5 分钟前", "相对时间：分钟");
        eq(Constellation.relativeTime(0L, 3 * 3600L * 1000L), "3 小时前", "相对时间：小时");

        // 形状不可用 → null（不抛）
        eq(Constellation.parse("not json"), null, "坏 JSON 返回 null（不抛）");
        eq(Constellation.parse(null), null, "null 输入返回 null");

        // archive.jsonl：一行一条（跳过空行）
        String jsonl = "{\"id\":\"a\",\"summary\":\"older\",\"type\":\"feature\",\"verificationStatus\":\"unverified\"}\n"
                + "\n{\"id\":\"b\",\"summary\":\"newer\"}";
        eq(Constellation.parseJsonl(jsonl).size(), 2, "archive.jsonl 解析（跳过空行）");
        eq(Constellation.parseJsonl(jsonl).get(0).summary, "older", "archive 第一条摘要");

        // 路径：App 私有 rootfs（guest cwd=/root）
        File rf = new File("/data/data/dev.tianshu.host/files/rootfs");
        eq(Constellation.fileFor(rf).getPath(),
                "/data/data/dev.tianshu.host/files/rootfs/root/.rivet/constellation.json",
                "constellation.json 路径");
        eq(Constellation.archiveFor(rf).getName(), "constellation.archive.jsonl", "archive 文件名");
    }

    static void testCommandApi() {
        System.out.println("== U. 命令 → HTTP 调用 ==");

        // 表里标成 api 的，必须都被 CommandApi 认下 —— 两处不许漂移
        int api = 0;
        for (String c : CommandRouting.commands()) {
            CommandRouting.Row r = CommandRouting.of(c);
            if (CommandRouting.API.equals(r.action)) {
                api++;
                ok(r.available, "标成 api 的必须已接：" + c);
                ok(CommandApi.handles(c), "标成 api 的必须有执行映射：" + c);
            }
        }
        // 2026-10-06④：/login 从 api 改判 nav（原先只打一发 POST /account/device 就完事，
        // 而设备码登录的第三歩「轮询」根本没做）→ 35 变成 34。判据不是"少了就改数"，
        // 而是「登录这件事在 App 里得走完」—— 见 testAccount 的 keepPolling 断言。
        eq(api, 34, "已接到接口的命令 34 条（/login 已改判 nav，去账号页走完整三步）");

        String sid = "s-123";

        CommandApi.Call ask = CommandApi.call("/ask", "on", sid);
        eq(ask.method, "POST", "/ask on → POST");
        eq(ask.path, "/sessions/s-123/ask-mode", "/ask 端点");
        eq(ask.body, "{\"state\":\"asking\"}", "/ask on → asking");
        eq(CommandApi.call("/ask", "off", sid).body, "{\"state\":\"off\"}", "/ask off → off");
        ok(CommandApi.call("/ask", "", sid).error != null, "/ask 无参 → 提示用法，不发");
        ok(CommandApi.call("/ask", "maybe", sid).error != null, "/ask 非法参 → 提示，不发");

        eq(CommandApi.call("/plan-mode", "on", sid).body, "{\"state\":\"planning\"}",
                "/plan-mode on → planning");
        eq(CommandApi.call("/effort", "MAX", sid).body, "{\"effort\":\"max\"}", "/effort 大小写宽容");
        ok(CommandApi.call("/effort", "turbo", sid).error != null, "/effort 非法档位 → 提示，不发");

        CommandApi.Call compact = CommandApi.call("/compact", "", sid);
        eq(compact.path, "/sessions/s-123/compact", "/compact 端点");
        eq(compact.body, null, "/compact 无请求体");

        eq(CommandApi.call("/fork", "", sid).path, "/sessions/s-123/fork", "/fork 端点");
        eq(CommandApi.call("/fast", "", sid).body, "{\"action\":\"skip\"}", "/fast → zen skip");
        eq(CommandApi.call("/queue", "补一句", sid).path, "/sessions/s-123/queue", "/queue 端点");
        eq(CommandApi.call("/queue", "补一句", sid).body, "{\"text\":\"补一句\"}", "/queue 体");
        eq(CommandApi.call("/queue", "带\"引号\"", sid).body,
                "{\"text\":\"带\\\"引号\\\"\"}", "/queue 体做 JSON 转义");
        ok(CommandApi.call("/queue", "", sid).error != null, "/queue 无文本 → 提示，不发");

        // /zen 是全局配置：不依赖会话，没有会话也能执行
        CommandApi.Call zen = CommandApi.call("/zen", "on", null);
        eq(zen.method, "PUT", "/zen on → PUT");
        eq(zen.path, "/config/zen", "/zen 端点");
        eq(zen.body, "{\"enabled\":true}", "/zen on → {enabled:true}");
        eq(CommandApi.call("/zen", "status", null).method, "GET", "/zen status → GET");
        ok(CommandApi.call("/zen", "", null).error != null, "/zen 无参 → 提示用法");

        // 没有会话时，会话级命令只提示、不打一条必 404 的请求
        ok(CommandApi.call("/compact", "", null).error != null, "没有会话 → /compact 只提示");
        ok(CommandApi.call("/fork", "", "").error != null, "会话 id 为空 → /fork 只提示");

        // 本类只管自己接的那批
        eq(CommandApi.call("/theme", "", sid), null, "nav 类命令不归本类");
        eq(CommandApi.handles("/theme"), false, "/theme 不由本类接");
        eq(CommandApi.handles("/galaxy"), false, "/galaxy 尚未接上（无 HTTP 对端）");

        // 参数切分
        eq(CommandApi.argOf("/ask on", "/ask"), "on", "参数切分");
        eq(CommandApi.argOf("/ask", "/ask"), "", "无参数 → 空串");
        eq(CommandApi.argOf("  /effort  max  ", "/effort"), "max", "前后空白与参数");

        // ---- 第二波：goal / plan / 回退 / 环境 / 账号 ----
        eq(CommandApi.call("/goal", "做个原型", sid).path, "/sessions/s-123/goal", "/goal 端点");
        eq(CommandApi.call("/goal", "做个原型", sid).body, "{\"goal\":\"做个原型\"}", "/goal 体");
        eq(CommandApi.call("/goal", "带\"引号\"\n换行", sid).body,
                "{\"goal\":\"带\\\"引号\\\"\\n换行\"}", "/goal 体做 JSON 转义");
        ok(CommandApi.call("/goal", "", sid).error != null, "/goal 无目标 → 提示，不发");

        eq(CommandApi.call("/goal-status", "", sid).method, "GET", "/goal-status → GET");
        eq(CommandApi.call("/goal-pause", "", sid).path, "/sessions/s-123/goal/pause", "/goal-pause 端点");
        eq(CommandApi.call("/goal-cancel", "", sid).path, "/sessions/s-123/goal/cancel", "/goal-cancel 端点");

        eq(CommandApi.call("/plan-list", "", sid).path, "/sessions/s-123/plans", "/plan-list 端点");
        eq(CommandApi.call("/plan-view", "my-plan", sid).path,
                "/sessions/s-123/plans/my-plan", "/plan-view 端点");
        eq(CommandApi.call("/plan-view", "", sid).path, "/sessions/s-123/plans", "/plan-view 无参 → 先列计划");
        eq(CommandApi.call("/plan-approve", "my-plan", sid).path,
                "/sessions/s-123/plans/my-plan/approve", "/plan-approve 端点");
        ok(CommandApi.call("/plan-approve", "", sid).error != null, "/plan-approve 无计划名 → 提示");
        eq(CommandApi.slug("my plan/../x"), "myplan..x", "slug 白名单化（去空格与斜杠）");

        eq(CommandApi.call("/rewind", "", sid).path, "/sessions/s-123/rewind-points", "/rewind 端点");
        eq(CommandApi.call("/rollback", "", sid).path, "/sessions/s-123/rollback/preview", "/rollback 端点");
        eq(CommandApi.call("/scout", "查依赖", sid).path, "/sessions/s-123/delegate", "/scout 端点");
        eq(CommandApi.call("/scout", "查依赖", sid).body, "{\"objective\":\"查依赖\"}", "/scout 体");
        ok(CommandApi.call("/scout", "", sid).error != null, "/scout 无目标 → 提示，不发");
        eq(CommandApi.call("/team-resume", "", sid).path,
                "/sessions/s-123/team-checkpoints", "/team-resume 端点");
        eq(CommandApi.call("/sensorium", "", sid).path, "/sessions/s-123/insights", "/sensorium 端点");
        eq(CommandApi.call("/cockpit", "", sid).path, "/sessions/s-123/cockpit", "/cockpit 端点");
        eq(CommandApi.call("/skill", "", sid).path, "/sessions/s-123/skills", "/skill 端点（会话级）");
        // P1：harness 新加的端点（读活 agent 的内存态前缀预算）
        eq(CommandApi.call("/prefix-budget", "", sid).path,
                "/sessions/s-123/prefix-budget", "/prefix-budget 端点（P1 新增）");
        eq(CommandApi.call("/prefix-budget", "", sid).method, "GET", "/prefix-budget 是 GET");
        // P2′
        eq(CommandApi.call("/goal-criteria", "", sid).path, "/sessions/s-123/goal",
                "/goal-criteria 复用既有 goal 快照（判据在 successCriteria 里）");
        eq(CommandApi.call("/undo", "", sid).path, "/sessions/s-123/undo-history",
                "/undo 打新的文件历史端点（P2′ 新增）");
        eq(CommandApi.call("/handoff", "", sid).path, "/sessions/s-123/handoff", "/handoff 端点");

        // 全局命令：不依赖会话
        eq(CommandApi.call("/doctor", "", null).path, "/environment", "/doctor 不需要会话");
        eq(CommandApi.call("/python", "", null).path, "/environment", "/python 不需要会话");
        eq(CommandApi.call("/logout", "", null).path, "/account/logout", "/logout 不需要会话");
        eq(CommandApi.call("/yolo", "", null).body,
                "{\"approval\":\"dangerously-skip-permissions\"}", "/yolo → 跳过权限确认");
        eq(CommandApi.call("/yolo", "off", null).body, "{\"approval\":\"manual\"}", "/yolo off → 回到 manual");
        eq(CommandApi.call("/yes", "", null).path, "/config/approval", "/yes 与 /yolo 同端点");

        eq(CommandApi.call("/login", "", null).path, "/account/device", "/login 不需要会话");

        // 归 send 的命令不归本类（交给运行时解析）
        eq(CommandApi.handles("/galaxy"), false, "/galaxy 走 SEND，不归本类");
        eq(CommandApi.handles("/council"), false, "/council 走 SEND，不归本类");
        eq(CommandApi.handles("/plan"), false, "/plan 走 SEND，不归本类");
        eq(CommandApi.handles("/todo"), false, "/todo 无 HTTP 对端，不归本类");
    }

    /**
     * V. 会话搜索 —— `GET /sessions/search?q=` 的查询串构造与结果解析。
     *
     * 形状来自实测（真实 serve 的 400 分支 `q must be at least 2 characters`）与 handler 源码
     * （`chunk-DXY5P57D.js:7725` 的 hit 字段 `{sessionId,title,role,snippet}`）。
     * 本节钉住三件事：URL 编码正确、命中字段取得对（含标题里的花括号）、脏输入不崩。
     */
    static void testSessionSearch() {
        System.out.println("== V. 会话搜索 ==");

        ok(SessionSearch.tooShort(""), "空串太短");
        ok(SessionSearch.tooShort("a"), "1 个字太短（服务端会 400）");
        ok(!SessionSearch.tooShort("ab"), "2 个字够");
        ok(!SessionSearch.tooShort("  两个字  "), "两端空白不计入长度");

        eq(SessionSearch.pathFor("ab"), "/sessions/search?q=ab", "纯 ASCII 查询不编码");
        eq(SessionSearch.pathFor("a b"), "/sessions/search?q=a%20b", "空格编成 %20（不是 +）");
        eq(SessionSearch.pathFor("天枢"), "/sessions/search?q=%E5%A4%A9%E6%9E%A2", "中文按 UTF-8 编码");
        eq(SessionSearch.encode("a+b&c=d"), "a%2Bb%26c%3Dd", "保留字被编码");

        String json = "{\"results\":["
                + "{\"sessionId\":\"s-1\",\"title\":\"做个原型\",\"role\":\"user\",\"snippet\":\"…天枢…\"},"
                + "{\"sessionId\":\"s-2\",\"title\":\"a}b{含花括号\",\"role\":\"assistant\","
                + "\"snippet\":\"答案里有 \\\"引号\\\"\"}"
                + "],\"meta\":{\"durationMs\":19.1,\"scannedFiles\":7}}";
        SessionSearch.Result r = SessionSearch.parse(json);
        eq(r.hits.size(), 2, "两条命中都切出来了（标题里的花括号不影响切对象）");
        eq(r.hits.get(0).sessionId, "s-1", "sessionId");
        eq(r.hits.get(0).title, "做个原型", "title");
        eq(r.hits.get(0).role, "user", "role");
        eq(r.hits.get(1).title, "a}b{含花括号", "标题含花括号也切对");
        eq(r.hits.get(1).snippet, "答案里有 \"引号\"", "片段里的转义引号解出来");
        eq(r.scannedFiles, 7, "meta.scannedFiles");

        eq(SessionSearch.roleLabel("user"), "你", "角色标签 user");
        eq(SessionSearch.roleLabel("assistant"), "天枢", "角色标签 assistant");

        SessionSearch.Result empty = SessionSearch.parse("{\"results\":[],\"meta\":{\"scannedFiles\":1}}");
        eq(empty.hits.size(), 0, "空结果");
        eq(empty.scannedFiles, 1, "空结果也带扫描数");
        ok(SessionSearch.summary(empty).indexOf("没找到") >= 0, "空结果摘要说人话：" + SessionSearch.summary(empty));
        eq(SessionSearch.parse(null).hits.size(), 0, "null 不崩");
        eq(SessionSearch.parse("not json").hits.size(), 0, "非 JSON 不崩");
        eq(SessionSearch.parse("{\"results\":[{\"sessionId\":\"x\"").hits.size(), 0, "截断响应丢掉残条");
    }

    /**
     * W. 接口结果渲染 —— GET 类命令（/plan-list /doctor /sensorium …）的返回体要能读。
     *
     * 盯住的是「提示语写着『见下』、下面什么都没有」那个缺陷：命令执行成功只回一句
     * "✓ 已执行"，把 body 丢了。
     */
    static void testApiResult() {
        System.out.println("== W. 接口结果渲染 ==");

        eq(ApiResult.readable(null), "", "null → 空串（调用方据此决定不显示）");
        eq(ApiResult.readable("   "), "（服务端返回空）", "空白 → 明说返回空");
        ok(ApiResult.readable("{\"a\":1}").indexOf("a = 1") >= 0, "对象摊成 a = 1");
        // 键名也走中文化（同 ConfigView.humanLabel）——「启用 = 关」比「enabled = 关」好读
        ok(ApiResult.readable("{\"enabled\":false}").indexOf("启用 = 关") >= 0, "布尔说人话（键名中文化）");
        ok(ApiResult.readable("{\"nested\":{\"x\":\"y\"}}").indexOf("nested.x = y") >= 0, "嵌套摊平");
        eq(ApiResult.readable("[]"), "（空列表）", "空数组明说，不留空白");
        ok(ApiResult.readable("[{\"slug\":\"p1\"},{\"slug\":\"p2\"}]").indexOf("[1] slug = p1") >= 0,
                "数组逐项编号");
        ok(ApiResult.readable("[{\"slug\":\"p1\"},{\"slug\":\"p2\"}]").indexOf("[2] slug = p2") >= 0,
                "第二项也编号");
        eq(ApiResult.readable("42"), "42", "标量原样");
        eq(ApiResult.readable("not json"), "not json", "非 JSON 原样，不假装解析");

        StringBuilder lb = new StringBuilder("{\"s\":\"");
        for (int i = 0; i < 2000; i++) lb.append('x');
        lb.append("\"}");
        String longBody = lb.toString();
        String shown = ApiResult.readable(longBody);
        ok(shown.length() <= ApiResult.DEFAULT_MAX + 20, "超长结果被截断：" + shown.length());
        ok(shown.indexOf("已截断") >= 0, "截断有标记");
    }

    /**
     * X. 指令桥 —— 终端本地命令改写成等价 agent 指令。
     *
     * 盯住的是"把没有 HTTP 对端的命令一律判死"这个过于悲观的结论：它们的语义是
     * "让 agent 去做某件事"，而发一句自然语言指令是 App 侧现成的路。
     */
    static void testCommandPrompt() {
        System.out.println("== X. 指令桥 ==");

        // 桥上与桥外
        ok(CommandPrompt.handles("/interview"), "/interview 在桥上");
        ok(CommandPrompt.handles("/diagram"), "/diagram 在桥上");
        ok(!CommandPrompt.handles("/plan"), "/plan 不在桥上（服务端自己能解析，原样发才对）");
        ok(!CommandPrompt.handles("/connect"), "/connect 不在桥上");

        // 改写：保留因果（"用户输入了 /xxx"），带上参数
        String interview = CommandPrompt.rewrite("/interview");
        ok(interview.indexOf("/interview") >= 0, "改写后仍看得出是哪条命令：" + interview);
        ok(interview.length() > 20, "改写后是可执行的指令，不是光秃秃的命令名");
        ok(interview.indexOf("/interview") == 0 || interview.startsWith("用户输入了"), "开头点明因果");

        String remember = CommandPrompt.rewrite("/remember 记住我喜欢简洁");
        ok(remember.indexOf("记住我喜欢简洁") >= 0, "/remember 的参数带进指令");
        ok(remember.indexOf("$ARG") < 0, "$ARG 占位符被替换掉");

        String noArg = CommandPrompt.rewrite("/remember");
        ok(noArg.indexOf("$ARG") < 0, "无参数时占位符也不残留");

        // 不改写的必须原样
        eq(CommandPrompt.rewrite("/plan 做个计划"), "/plan 做个计划", "/plan 原样通过");
        eq(CommandPrompt.rewrite("你好"), "你好", "普通消息原样通过");
        eq(CommandPrompt.rewrite(""), "", "空串原样");

        // 判定对齐：桥上每条命令都必须是表里的 send 类，否则用户敲了会被拦
        for (String c : new String[]{"/interview", "/diagram", "/remember", "/dream", "/evidence",
                "/index", "/init", "/leave", "/workflow", "/starflow", "/capsule", "/plan-template"}) {
            ok(CommandPrompt.handles(c), c + " 在桥上");
            CommandRouting.Row r = CommandRouting.of(c);
            eq(r == null ? null : r.action, CommandRouting.SEND, c + " 在表里归 send（否则会被拦住）");
        }
    }

    /**
     * Z. nav 落点护栏 —— "说跳去哪一页的哪张卡"，那页里就得**真有那张卡**。
     *
     * 盯住的是两次真实踩坑：`/cockpit` `/skill` 写着"面板页 · 驾驶舱 / 技能（待接）"，
     * 可面板页里根本没有这两张卡 —— 用户点进去是空的；`/vision` 说去"模型页"，
     * 而模型页是服务商配置，跟视觉模型无关。
     * 这条断言把"落点必须兑现"变成机器可查的，而不是靠人记得。
     */
    static void testNavLandings() {
        System.out.println("== Z. nav 落点护栏 ==");

        java.util.List<String> panelTitles = new java.util.ArrayList<String>();
        for (PanelCatalog.Panel p : PanelCatalog.PANELS) panelTitles.add(p.title);
        java.util.List<String> consoleTitles = new java.util.ArrayList<String>();
        for (ConsoleCatalog.Fam f : ConsoleCatalog.CONFIG) consoleTitles.add(f.title);
        for (ConsoleCatalog.Fam f : ConsoleCatalog.INFO) consoleTitles.add(f.title);

        int checked = 0;
        for (String c : CommandRouting.commands()) {
            CommandRouting.Row r = CommandRouting.of(c);
            if (r == null || !CommandRouting.NAV.equals(r.action)) continue;

            String t = r.target;
            boolean panel = t.startsWith("面板页 · ");
            boolean settings = t.startsWith("设置页 · ");
            if (!panel && !settings) continue;        // 会话页 / 模型页 / 外观页 / 命令面板页：整页，无卡可查

            String want = t.substring(t.indexOf("· ") + 2).trim();
            int paren = want.indexOf('（');
            if (paren > 0) want = want.substring(0, paren).trim();
            if ("运行控制台".equals(want)) continue;   // 那是设置页整页的名字，不是某张卡

            checked++;
            java.util.List<String> pool = panel ? panelTitles : consoleTitles;
            boolean found = false;
            for (String title : pool) {
                if (title.indexOf(want) >= 0 || want.indexOf(title) >= 0) {
                    found = true;
                    break;
                }
            }
            ok(found, c + " 的落点在那页里真有这张卡：" + want);
        }
        ok(checked >= 10, "至少查了 10 条 nav 落点（实际 " + checked + "）");
    }

    static void testRenderPlan() {
        System.out.println("== R. 增量渲染调度 ==");

        eq(RenderPlan.firstDirtyTurn(0, 0), 0, "还没画过、也没有轮次");
        eq(RenderPlan.firstDirtyTurn(0, 1), 0, "第一轮：从 0 画起");
        eq(RenderPlan.firstDirtyTurn(1, 1), 0, "只有一轮：重画它");
        eq(RenderPlan.firstDirtyTurn(2, 2), 1, "两轮：只重画下标 1（前一轮复用）");
        eq(RenderPlan.firstDirtyTurn(5, 5), 4, "五轮：只重画最后一轮");
        eq(RenderPlan.firstDirtyTurn(5, 6), 4, "新起一轮：从旧的最后一轮起重画");
        eq(RenderPlan.firstDirtyTurn(5, 2), RenderPlan.FULL, "轮次变少：整体重画");
        eq(RenderPlan.firstDirtyTurn(-1, 3), RenderPlan.FULL, "账本没初始化：整体重画");

        // 本体：轮数再多，重画起点也贴着末尾 —— 每帧的代价与对话长度**无关**
        boolean alwaysLast = true;
        for (int n = 1; n <= 200; n++) {
            if (RenderPlan.firstDirtyTurn(n, n) != n - 1) alwaysLast = false;
        }
        ok(alwaysLast, "1..200 轮：起点恒为「最后一轮」（每帧只重画一轮，与对话长度无关）");
    }

    /**
     * Q′. 键盘弹出：**只抬输入行，页面（含背景图）一点都不动**。
     *
     * 用户 2026-10-06：「对话的时候对话框要和键盘抬起来，但是背景图片不要抬起来」。
     *
     * 实现 = manifest 里 `adjustNothing`（窗口恒定：不缩放、不位移 ⇒ 挂在 `android.R.id.content` 上的
     * 背景图**结构性**不动）+ {@link KeyboardLift} 自己把输入行抬到键盘上沿。
     * 所以这里两条都要钉住：**系统被明确要求别动窗口**，以及**抬的只是输入行、不是 root/content**。
     */
    static void testKeyboardLift() throws Exception {
        System.out.println("== Q′. 键盘：只抬输入行 ==");

        // 纯逻辑：键盘高 × 输入行底距页底的空隙 → 要抬多少
        eq(KeyboardLift.liftFor(0, 490, 24), 0, "键盘没弹 → 不抬");
        eq(KeyboardLift.liftFor(300, 490, 24), 0, "键盘比空隙还矮 → 本来就没盖住，不抬");
        // 临界点 = gap − spacing：到了它，输入行与键盘上沿正好留足那道缝
        eq(KeyboardLift.liftFor(466, 490, 24), 0, "键盘高 = 空隙 − spacing → 恰好留足缝，不抬");
        eq(KeyboardLift.liftFor(467, 490, 24), 1, "再多 1px 就盖进来 → 抬 1px");
        eq(KeyboardLift.liftFor(490, 490, 24), 24, "拱到输入行底 → 抬出 spacing 那道缝");
        eq(KeyboardLift.liftFor(1000, 490, 24), 534, "键盘 1000 / 空隙 490 / 留 24 → 抬 534");
        eq(KeyboardLift.liftFor(-1, 490, 24), 0, "异常输入（负键盘高）→ 不抬，别把界面推飞");
        eq(KeyboardLift.liftFor(1000, -5, 24), 0, "异常输入（负空隙）→ 不抬");

        // 单调性：键盘越高抬得越多（不变量，不是特例）
        ok(KeyboardLift.liftFor(2000, 100, 24) > KeyboardLift.liftFor(1000, 100, 24),
                "键盘越高抬得越多");

        // **用户可见结果**的数值形式：抬完之后，输入行底必须落在键盘上沿之上（留 spacing）。
        // 这条才是"对话框真的没被键盘盖住"的判据 —— 上面那些都只是它的分解。
        int screenH = 2800, gap = 490, sp = 24;
        for (int kb : new int[]{0, 200, 489, 490, 700, 1200, 2000}) {
            int lift = KeyboardLift.liftFor(kb, gap, sp);
            int composerBottomAfter = (screenH - gap) - lift;
            int keyboardTop = screenH - kb;
            ok(kb <= gap - sp || composerBottomAfter <= keyboardTop - sp + 1,
                    "kb=" + kb + "：抬完输入行底 " + composerBottomAfter
                            + " 在键盘上沿 " + keyboardTop + " 之上（留 " + sp + "）");
        }

        // manifest：ChatActivity 不得声明 `adjustPan`。
        // 为什么判据是"不得 pan"而不是"必须某个值"：pan 会把**整个窗口**推走 —— 背景、内容一起动，
        // 这正是用户报的"背景图片被抬起来"。`resize`（系统缩窗口 ⇒ 输入框自动抬起）与
        // `nothing`（窗口不动 ⇒ 靠 KeyboardWatcher 自抬）都能满足"输入框抬起且背景不动"，
        // 区别只在谁让位；不写死某一种，是给设备行为留余地：
        // 2026-10-06 的现场教训 —— 先选了 nothing，结果该设备在 adjustNothing 下**不告诉**窗口键盘信息
        //（可见区不更新），自抬算不出高度、输入框被键盘盖住（用户第二次报："我打字都不知道我打了什么"）。
        String manifest = readText(new File("AndroidManifest.xml"));
        java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("ChatActivity\"[\\s\\S]{0,200}?windowSoftInputMode=\"([^\"]*)\"")
                .matcher(manifest);
        String mode = sm.find() ? sm.group(1) : null;
        ok(mode != null && !mode.contains("adjustPan"),
                "对话页不声明 adjustPan（pan 会把窗口连背景一起推走）：" + mode);
        ok(mode != null && (mode.contains("adjustResize") || mode.contains("adjustNothing")),
                "对话页明说让位方式（resize = 系统抬 / nothing = 自己抬）：" + mode);

        // 接线：抬的是**输入行**，不是整页
        String chat = sourceOf("ChatActivity");
        ok(chat != null && chat.contains("KeyboardWatcher.attach("),
                "对话页接了键盘监听（自己把输入行抬起来）");
        ok(chat != null && !chat.contains("root.setTranslationY"),
                "不整体位移 root —— 那会把背景图一起抬走");
        ok(chat != null && !chat.contains("content.setTranslationY"),
                "不整体位移 content —— 同上");
        // 被测的纯逻辑必须**不碰 android 类**：挤在一起时 HostTest 一引用就
        // `NoClassDefFoundError: android/content/Context`（本次真踩到，同名类里放了 attach 就炸）。
        String liftSrc = stripComments(sourceOf("KeyboardLift"));   // 注释里当然会提 android，剥掉再问
        ok(liftSrc != null && liftSrc.indexOf("import android") < 0,
                "KeyboardLift 不 import android（纯逻辑才能被这里断言）");
        ok(liftSrc != null && liftSrc.indexOf("Activity") < 0 && liftSrc.indexOf("View") < 0,
                "KeyboardLift 不出现 Activity / View（同上）");

        // ---- 会话页（用户 2026-10-06：「会话页也改吧」）----
        // 它的**搜索框在页面顶部**（header 之下、列表之上）—— 键盘够不着它，
        // 所以那一页不需要位移任何视图，只要让**列表**能滚到键盘上方。
        eq(KeyboardLift.scrollPadFor(0), 0, "键盘没弹 → 滚动区不补");
        eq(KeyboardLift.scrollPadFor(-5), 0, "异常输入（负键盘高）→ 不补");
        eq(KeyboardLift.scrollPadFor(800), 800, "键盘 800 → 滚动区补 800");

        // ---- 兜底自抬的**闸门**（用户 2026-10-06：「对话框到是弹起来了，但是一直会闪」）----
        // 闪的机制：`adjustResize` 期间系统在缩窗口（把输入框抬起来），而我们的兜底也在按可见区差
        // 推/撤输入框 —— 两边抢着摆同一个东西 ⇒ 输入框上下抖。所以门的判据是三条**与**：
        //   ① 键盘确实弹了；② **系统没有在缩窗口**（缩了 = 它在管，我们必须完全不动手）；③ 键盘已稳定。
        ok(KeyboardLift.shouldSelfLift(0, 0, 5000, 250) == false, "键盘没弹 → 自抬不许动手");
        ok(KeyboardLift.shouldSelfLift(-3, 0, 5000, 250) == false, "键盘高异常 → 不动手");
        ok(KeyboardLift.shouldSelfLift(1000, 1000, 5000, 250) == false,
                "**系统正在 resize（窗口被缩）→ 彻底不动手**（抢着摆就是闪的来源）");
        ok(KeyboardLift.shouldSelfLift(1000, 1, 5000, 250) == false, "窗口缩了一点点也算 resize → 不动手");
        ok(KeyboardLift.shouldSelfLift(1000, 0, 50, 250) == false,
                "键盘刚出现（抖动窗口内）→ 先等稳定，别在动画里插一脚");
        ok(KeyboardLift.shouldSelfLift(1000, 0, -1, 250) == false, "键盘不可见 → 不动手");
        ok(KeyboardLift.shouldSelfLift(1000, 0, 250, 250) == true,
                "键盘稳定 + 窗口没缩（resize 没生效）→ 这才是兜底该上的时候");
        ok(KeyboardLift.shouldSelfLift(1000, 0, 4000, 250) == true, "同上，久一点仍然该上");
        ok(KeyboardLift.shouldSelfLift(1000, 0, 249, 250) == false, "差 1ms 没到稳定阈值 → 再等等");

        // ---- 用**事件序列**复现"闪"（用户第三次报：「对话框到是弹起来了，但是一直会闪」）----
        // 判据不是"某一步算得对不对"，而是**整段序列里的动作次数**：
        // 闪 = 输入框被反复推上去又撤回来。所以断言两条 —— ① resize 生效时**零动作**；
        // ② 任何序列里"非 0 → 0 → 非 0"这种来回都不许出现（≤2 次动作 = 抬起 + 复位）。
        {
            // 场景 A：**resize 生效**（这台设备的实际行为）——窗口在动画中才缩下去，可见区先变
            KeyboardGate g = new KeyboardGate(250);
            int[][] frames = {{2800, 2800}, {2800, 1800}, {2800, 1800}, {2300, 1800}, {1800, 1800},
                              {1800, 1800}, {2800, 2800}};
            long[] when = {0, 16, 122, 150, 200, 900, 1500};
            int actions = 0, transitions = 0, last = 0;
            for (int i = 0; i < frames.length; i++) {
                int pad = g.feed(frames[i][0] - frames[i][1], 2800 - frames[i][0], when[i], 534);
                if (pad < 0) continue;                       // -1 = 本次无动作
                actions++;
                if ((pad == 0) != (last == 0)) transitions++;
                last = pad;
            }
            eq(actions, 0, "resize 生效：整段序列**零动作**（不抢系统的活 ⇒ 不抖）");
            eq(g.applied(), 0, "……而且没在身上留下位移");
            eq(transitions, 0, "……连一次来回都没有");
        }
        {
            // 场景 B：**resize 不生效**（窗口恒高）——兜底该在键盘稳定后抬一次，收起时复位一次
            KeyboardGate g = new KeyboardGate(250);
            int[][] frames = {{2800, 2800}, {2800, 1800}, {2800, 1800}, {2800, 1800}, {2800, 1800},
                              {2800, 2800}, {2800, 2800}};
            long[] when = {0, 16, 100, 300, 800, 1200, 2000};
            int actions = 0, transitions = 0, last = 0;
            for (int i = 0; i < frames.length; i++) {
                int pad = g.feed(frames[i][0] - frames[i][1], 2800 - frames[i][0], when[i], 534);
                if (pad < 0) continue;
                actions++;
                if ((pad == 0) != (last == 0)) transitions++;
                last = pad;
            }
            eq(actions, 2, "resize 没生效：**只抬一次、复位一次**（动画里的过渡帧不许动手）");
            eq(transitions, 2, "……正好一次抬起 + 一次复位，没有多余来回");
            eq(g.applied(), 0, "键盘收起后归位");
        }
        {
            // 场景 C：键盘已稳定后**高度有小幅变化**（切输入法 / 浮动键盘）——跟随是对的，但**不许回到 0 再回来**
            KeyboardGate g = new KeyboardGate(250);
            int[][] frames = {{2800, 1800}, {2800, 1800}, {2800, 1750}, {2800, 1750}, {2800, 1800}};
            long[] when = {1000, 1200, 1400, 1600, 1800};
            int zeroBacks = 0;
            int prev = -1;
            for (int i = 0; i < frames.length; i++) {
                int pad = g.feed(frames[i][0] - frames[i][1], 0, when[i], 534);
                if (pad < 0) continue;
                if (prev > 0 && pad == 0) zeroBacks++;       // 抬着抬着又回到 0 = 闪
                prev = pad;
            }
            eq(zeroBacks, 0, "键盘高度小幅变化时不许出现『抬着又放下』（那正是闪）");
        }

        // ---- 真机日志复现：`抬=676` 与 `抬=0` 逐帧翻转（用户："还在闪"）----
        // 日志（`/storage/emulated/0/tianshu-log/latest.txt`，App 2.8.0 (1791259504)）实况：
        //   键盘 页底=2800 可见底=1915 键盘=885 抬=676 补=676 窗高变化=0 已可见=560ms
        //   键盘 页底=2800 可见底=1915 键盘=885 抬=0   补=0   窗高变化=0 已可见=562ms   ← 逐帧翻转
        // 读数说明：① 系统**没有** resize（窗高变化恒 0）⇒ "输入框抬起来"是我这段自抬做的；
        //          ② 键盘信息拿得到（键盘=885）；③ 振荡 = 闪。
        {
            int h = 200, y0 = 2383, spacing2 = 24;
            int bottom0 = KeyboardLift.composerBottomFrom(y0, h, 0);
            int bottom1 = KeyboardLift.composerBottomFrom(y0 - 676, h, 676);
            eq(bottom1, bottom0,
                    "抬起后再量底边应回到原位 —— 符号写反就会『一帧抬、一帧放』");

            int c1 = KeyboardLift.candidateLift(2800, 1915, y0, h, 0, spacing2);
            ok(c1 > 0, "第一帧确实要抬：" + c1);
            int c2 = KeyboardLift.candidateLift(2800, 1915, y0 - c1, h, c1, spacing2);
            eq(c2, c1, "第二帧的候选值与第一帧相同 ⇒ 状态机不会再动手（日志里是 676→0）");

            // 序列级：连续 20 帧（屏幕位置随位移一起变），只许动作 **1** 次
            KeyboardGate g = new KeyboardGate(250);
            int applied = 0, y = y0, actions = 0, flips = 0, lastPad = 0, seen = 0;
            for (int f = 0; f < 20; f++) {
                int cand = KeyboardLift.candidateLift(2800, 1915, y, h, applied, spacing2);
                int pad = g.feed(885, 0, 1000 + f * 16, cand);
                if (pad < 0) continue;
                actions++;
                if (seen++ > 0 && (pad == 0) != (lastPad == 0)) flips++;
                applied = pad;
                y = y0 - pad;
                lastPad = pad;
            }
            eq(actions, 1, "连续 20 帧只许动作 1 次（日志里是每帧一次 ⇒ 闪）");
            eq(flips, 0, "不许出现『抬起 ↔ 放下』的翻转");
        }

        // ---- 触发源的**完备性**（用户：「刚进软件可以抬起来，然后收回去又抬不起来了」）----
        // 真机日志（`审计.md §19.1`）已证明：键盘弹出**既不引起窗口 resize**（`窗高变化=0`），
        // 也可能**不引起焦点变化**（第二次点时输入框已经聚焦）。
        // ⇒ 只挂 `OnGlobalLayout` + `onFocusChange` 的话，**第二次没有任何人叫醒重算**，
        //    输入框就一直躺着不动 —— 这正是用户报的"收回去又抬不起来了"。
        // 所以触发源必须**不依赖系统事件**也有一条（轮询），外加"点输入框本身"这一条。
        String kw = stripComments(sourceOf("KeyboardWatcher"));
        ok(kw != null && kw.contains("setOnClickListener"),
                "点输入框本身也触发重算（已聚焦时再点，焦点事件不会再来）");
        ok(kw != null && kw.contains("POLL_MS") && kw.contains("postDelayed"),
                "聚焦期间轮询兜底 —— 键盘弹出不引起布局变化时的唯一可靠触发");
        ok(kw != null && kw.contains("hasWindowFocus"),
                "轮询在页面失焦 / 退到后台时自行停止（不空转）");
        ok(kw != null && kw.contains("OnGlobalLayoutListener"), "布局事件仍保留（廉价，多数设备靠它）");
        ok(kw != null && kw.contains("setOnFocusChangeListener"), "首次聚焦仍是触发源之一");

        java.util.regex.Matcher sm2 = java.util.regex.Pattern
                .compile("SessionListActivity\"[\\s\\S]{0,200}?windowSoftInputMode=\"([^\"]*)\"")
                .matcher(manifest);
        String mode2 = sm2.find() ? sm2.group(1) : null;
        ok(mode2 != null && !mode2.contains("adjustPan"),
                "会话页也不声明 adjustPan：" + mode2);
        ok(mode2 != null && (mode2.contains("adjustResize") || mode2.contains("adjustNothing")),
                "会话页明说让位方式：" + mode2);

        String sessions = sourceOf("SessionListActivity");
        ok(sessions != null && sessions.contains("KeyboardWatcher.attachScrollOnly("),
                "会话页接了滚动区监听（只补列表底部）");
        ok(sessions != null && !sessions.contains("searchBox.setTranslationY"),
                "会话页不位移搜索框 —— 它在页面顶部，键盘本来够不着它");

        // 族级不变量：**任何**页都不得声明 `adjustPan` —— 它把整个窗口推走（背景、内容一起动）。
        // 注意判据**不**禁止 `adjustResize`：resize 让系统缩窗口（输入框自动被抬起），
        // 而"背景不动"由绘制层负责（见下面 Theming 那两条 + Backdrop.extentFor），与让位方式解耦。
        java.util.regex.Matcher all = java.util.regex.Pattern
                .compile("windowSoftInputMode=\"([^\"]*)\"").matcher(manifest);
        int declared = 0;
        while (all.find()) {
            declared++;
            String v = all.group(1);
            ok(!v.contains("adjustPan"),
                    "声明的软键盘调整位里没有 pan（会把窗口连背景一起推走）：" + v);
        }
        ok(declared >= 2, "至少两页声明了软键盘模式（实际 " + declared + "）");

        // ---- 背景绘制**不依赖容器高度**（这才是"背景不动"的真保证）----
        // resize 会让容器变矮：若绘制按容器高度来，渐变会被压缩、图会被挤扁 —— 用户第一次报的
        //"背景图片会抬起来"就是这类观感。判据 = 绘制高度取 max(全屏高, 容器高)，两种让位方式下都成立。
        eq(Backdrop.extentFor(2800, 2800), 2800, "容器没变矮 → 按容器高画");
        eq(Backdrop.extentFor(2800, 1800), 2800, "容器变矮（键盘 resize）→ 仍按全屏高画，不压缩");
        eq(Backdrop.extentFor(2800, 3000), 3000, "容器比全屏还高 → 取大的，别露白");
        eq(Backdrop.extentFor(0, 1800), 1800, "全屏高异常（0）→ 退回容器高");
        eq(Backdrop.extentFor(-5, 1800), 1800, "全屏高异常（负数）→ 同上");

        String themingSrc = stripComments(sourceOf("Theming"));
        ok(themingSrc != null && themingSrc.contains("Backdrop.extentFor("),
                "背景绘制走 Backdrop.extentFor（与 CoverBackdrop 同一份判据）");
        ok(themingSrc != null && themingSrc.contains("new FixedHeight(grad"),
                "**渐变层**也按固定全屏高绘制（此前只有图片那层有，渐变在 resize 下被压缩）");
    }

    /**
     * N′. 「个人」页（2026-10-06 用户点名补的底栏第四格）。
     *
     * 三条底线，缺一条这页就只是「看起来有个页面」：
     *   ① **数据源必须真存在** —— 三个路径各自要有真机夹具（夹具 = 那条路由真返回过 200），
     *      界面里因此长不出"打过去 404"的卡；
     *   ② **解析跑在真夹具上**，不是拿手写 JSON 自说自话；
     *   ③ **边界不许炸** —— `tokens` 可以是 `null`（3.28.0 的 `GET /profile/overview` 在
     *      usage 收集失败时就是返回 null），`domains` 也可以是空数组（近 30 天没跑过星域）。
     *      这两件事在真夹具上就会遇到。
     */
    static void testProfilePage() throws Exception {
        System.out.println("== N′. 「个人」页 ==");

        // ---- 1. 数据源：三个路径都得有真夹具 ----
        for (String p : ProfileData.PATHS) {
            ok(apiFixture(p) != null, "有真机夹具（= 路由真的存在）：" + p);
        }

        // ---- 2. 概览：跑在真夹具上 ----
        ProfileData.Overview o = ProfileData.parseOverview(apiFixture("/profile/overview"));
        eq(o.profileKey, "local", "账号 key（真夹具值）");
        eq(o.tokenTotal, 110455901L, "token 总量（真夹具值）");
        eq(o.tokenPeak, 110182807L, "单日峰值（真夹具值）");
        eq(o.activeDays, 3, "活跃天数（真夹具值）");
        eq(o.scannedFiles, 21, "扫描文件数（真夹具值）");
        ok(o.hasTokens, "夹具里 tokens 非 null");
        eq(o.repos.size(), 0, "夹具里没登记仓库");
        eq(ProfileData.humanCount(o.tokenTotal), "1.1 亿", "真夹具的 token 总量给人看的样子");

        // ---- 2b. `tokens` 为 null：服务端的真实分支（别把 null 当成 0 显示） ----
        ProfileData.Overview noTok = ProfileData.parseOverview(
                "{\"profileKey\":\"local\",\"login\":{\"totalMs\":0,\"trackedSince\":null},"
                        + "\"repositories\":[],\"tokens\":null}");
        ok(!noTok.hasTokens, "tokens:null → hasTokens=false");
        eq(noTok.tokenTotal, 0L, "null 的数值兜 0，显示与否由 hasTokens 决定");

        // ---- 2c. 仓库形状照 3.28.0 的 parseRepositories（url / title / description） ----
        ProfileData.Overview repo = ProfileData.parseOverview(
                "{\"profileKey\":\"local\",\"tokens\":null,\"repositories\":["
                        + "{\"url\":\"https://github.com/huiliyi37/Tianshu-Tui\",\"title\":\"天枢 TUI\","
                        + "\"description\":\"认知运行时\"}]}");
        eq(repo.repos.size(), 1, "解析出一个仓库");
        eq(repo.repos.get(0).title, "天枢 TUI", "仓库标题");
        eq(repo.repos.get(0).url, "https://github.com/huiliyi37/Tianshu-Tui", "仓库网址");

        // ---- 3. 脏输入一律不炸 ----
        for (String bad : new String[]{null, "", "  ", "not json", "[]", "{}", "{\"tokens\":\"x\"}"}) {
            ProfileData.Overview b = ProfileData.parseOverview(bad);
            ok(b != null && b.repos.size() == 0, "脏输入不炸、仓库为空：「" + bad + "」");
            ProfileData.Usage u = ProfileData.parseUsage(bad);
            ok(u != null && u.domains.size() == 0, "脏输入不炸、星域为空：「" + bad + "」");
        }

        // ---- 4. 星域用量：真夹具（空表）+ 合成样例（字段名照 3.28.0 的
        //         aggregateDomainUsage：key / count / lastUsedAt） ----
        ProfileData.Usage real = ProfileData.parseUsage(apiFixture("/profile/domain-usage"));
        eq(real.days, 30, "窗口天数（真夹具值）");
        eq(real.totalRuns, 0, "近 30 天轮次（真夹具值）");
        eq(real.domains.size(), 0, "夹具里星域为空");
        eq(real.missingRuns, 1, "有 1 轮没记到星域（真夹具 coverage.missingRuns）");

        ProfileData.Usage syn = ProfileData.parseUsage(
                "{\"days\":30,\"totalRuns\":9,\"domains\":["
                        + "{\"key\":\"qiming\",\"count\":6,\"lastUsedAt\":1759700000000},"
                        + "{\"key\":\"tianquan\",\"count\":3,\"lastUsedAt\":1759600000000}],"
                        + "\"coverage\":{\"partial\":false,\"missingRuns\":0,\"unreadableSessions\":0,"
                        + "\"firstRecordedAt\":1759500000000}}");
        eq(syn.totalRuns, 9, "总数");
        eq(syn.domains.size(), 2, "两条星域");
        eq(syn.domains.get(0).key, "qiming", "第一条是启明（服务端已按 count 降序）");
        eq(syn.domains.get(0).count, 6, "启明 6 轮");
        eq(syn.domains.get(1).key, "tianquan", "第二条是天权");
        eq(syn.domains.get(1).count, 3, "天权 3 轮");
        eq(ProfileData.topCount(syn.domains), 6, "比例基准 = 最大轮数");

        // ---- 5. 数字与时长的人话 ----
        eq(ProfileData.humanCount(110455901L), "1.1 亿", "亿级：一位小数");
        eq(ProfileData.humanCount(200000000L), "2 亿", "整亿不留 .0");
        eq(ProfileData.humanCount(12345L), "1.2 万", "万级");
        eq(ProfileData.humanCount(900L), "900", "不足一万原样");
        eq(ProfileData.humanCount(0L), "—", "0 显示成破折号（那是「没数据」，不是「0 个」）");
        eq(ProfileData.humanSpan(3 * 3600_000L + 4 * 60_000L), "3 小时 4 分", "时分");
        eq(ProfileData.humanSpan(2 * 86400_000L + 3 * 3600_000L), "2 天 3 小时", "天 + 小时");
        eq(ProfileData.humanSpan(86400_000L), "1 天", "整天的尾巴不留空小时");
        eq(ProfileData.humanSpan(90_000L), "1 分", "不足一小时只给分钟");
        eq(ProfileData.humanSpan(30_000L), "不到 1 分", "不足一分钟如实说");
        eq(ProfileData.humanSpan(0L), "—", "没登录过");

        // ---- 6. 星域名走既有那份映射（不另建一张迟早过期的对照表） ----
        String domJson = apiFixture("/config/default-domain");
        ok(domJson != null, "有 /config/default-domain 夹具");
        eq(StatusBar.domainName(domJson, "qiming"), "启明", "星域 id → 中文名（复用 StatusBar）");
        eq(StatusBar.domainName(domJson, "no-such-domain"), "no-such-domain",
                "查不到的 id 原样返回，不静默变空");

        // ---- 7. 官方仓库：跑在 harness 的**真 package.json**上（夹具 4.6 KB，从设备上那份 3.28.0 取的） ----
        String pkg = readText(new File("test/fixtures/harness-package.json"));
        ok(pkg.contains("\"tianshu-harness\""), "夹具确实是 harness 的 package.json");
        ProfileData.OfficialRepo off = ProfileData.parseOfficialRepo(pkg);
        ok(off.ok(), "官方仓库解析成功");
        eq(off.slug(), "huiliyi37/Tianshu-Tui", "owner/name（= harness repository 字段里的那个）");
        eq(off.url, "https://github.com/huiliyi37/Tianshu-Tui", "规范化地址：去掉 git+ 与 .git");
        eq(off.version, "3.28.0", "连版本一起取出来（卡片上显示「已装 x.y.z」）");

        // 三种写法都要认（真机上就这三种），外加 homepage 回退
        eq(ProfileData.parseOfficialRepo("{\"repository\":{\"url\":\"git+https://github.com/o/r.git\"}}").url,
                "https://github.com/o/r", "写法一：git+…​.git");
        eq(ProfileData.parseOfficialRepo("{\"repository\":{\"url\":\"https://github.com/o/r#readme\"}}").url,
                "https://github.com/o/r", "写法二：…#readme");
        eq(ProfileData.parseOfficialRepo("{\"repository\":\"https://github.com/o/r\"}").url,
                "https://github.com/o/r", "写法三：repository 直接写成字符串");
        eq(ProfileData.parseOfficialRepo("{\"homepage\":\"https://github.com/o/r\"}").url,
                "https://github.com/o/r", "没有 repository 时退回 homepage（与 harness 更新检查同一顺序）");

        // 认不出 → 空档：界面据此**不画那一行**，而不是画个点不动的按钮
        for (String bad : new String[]{null, "", "not json", "{}", "[]"}) {
            ok(!ProfileData.parseOfficialRepo(bad).ok(), "认不出 → ok()=false：「" + bad + "」");
        }
    }

    /**
     * N″. 「账号与登录」的入口只留一个。
     *
     * 用户 2026-10-06：「设置页面里面的账号与登录在个人页面重复了，保留个人页面的，设置页面的去掉」。
     *
     * 与「外观」那次（{@link #testAppearanceEntry()}）是**反方向**的操作 —— 那次把二级页收进设置页、
     * 在设置里多开一个门；这次是同一个门出现了两次、要拆掉一个。但纪律是同一条：
     * **同一个去处不许有两个门**（{@link SettingsActivity} 类注释里那句"别把两个门都留"）。
     *
     * 三条断言分别钉住：设置页不许再有它 / 个人页必须还有它 / **命令面板那条落点不许被误删**
     *（那条按命令名分流，是另一条路，不是"门"）。
     */
    static void testAccountEntry() throws Exception {
        System.out.println("== N″. 「账号与登录」入口不再重复 ==");

        String settings = sourceOf("SettingsActivity");
        ok(settings != null, "读得到 SettingsActivity 源码");
        if (settings != null) {
            String code = stripComments(settings);
            ok(!code.contains("AccountActivity.class"),
                    "设置页不再有「账号与登录」入口（用户要求只留个人页那个）");
            ok(!code.contains("账号与登录"), "设置页连那行文案也不留（免得留个死标题）");
        }

        String profile = sourceOf("ProfileActivity");
        ok(profile != null, "读得到 ProfileActivity 源码");
        if (profile != null) {
            String code = stripComments(profile);
            // 2026-10-06 晚再改一次：入口并进「身份」卡、独立那张卡删掉。
            // 用户原话：「个人页面的账号与登录的功能放到身份那个卡里，然后把登录与账号这个卡片删掉」。
            // 四条钉住：跳转还在（挂在身份卡上）/ 独立那张卡没了 / 图标不再出现 / 引导文案不指向不存在的卡。
            ok(code.contains("AccountActivity.class"), "个人页仍能进账号页");
            ok(code.contains("openAccount()"),
                    "入口现在是身份卡上的点击（openAccount 是它的锚点）");
            ok(!code.contains("Icon.SHIELD"),
                    "独立的「账号与登录」入口卡已删（那行用的 SHIELD 图标不再出现）");
            ok(!code.contains("用下面的「账号与登录」"),
                    "身份卡里的引导文案不再指向一张已经不存在的卡");
        }

        // 命令面板的落点**不是**门：它按命令名（"账号页"）分流到 AccountActivity。
        // 删设置页入口时最容易连它一起删掉 —— 那会让 `/login` 突然失联。
        String chat = sourceOf("ChatActivity");
        ok(chat != null && stripComments(chat).contains("AccountActivity.class"),
                "命令面板的「账号页」落点仍在（那是另一条路，不属于重复入口）");
    }

    /**
     * N‴. 应用名。
     *
     * 用户 2026-10-06：「把天枢host名称改为天枢」。桌面图标底下、最近任务、安装界面显示的都是
     * `res/values/strings.xml` 里那个 `app_name`（manifest 的 `android:label` 全指向它）——
     * 所以断言必须落在**那个文件**上，而不是某个常量上。
     *
     * 顺带钉住另外两处会被人看见的同名文案（运行报告标题、README 里那句），
     * 免得只改一处、回头又分叉成两个名字。
     */
    static void testAppName() throws Exception {
        System.out.println("== N‴. 应用名 ==");

        String xml = readText(new File("res/values/strings.xml"));
        ok(xml.contains("<string name=\"app_name\">天枢</string>"),
                "应用名就是「天枢」（用户要求去掉 host 那截尾巴）");
        ok(!xml.contains("app_name\">天枢 Host") && !xml.contains("app_name\">天枢Host"),
                "app_name 的取值里不再带 host");

        // ⚠️ 这条**故意不扫全文**：文件里的注释正当地写了"原来叫「天枢 Host」"（改名来龙去脉），
        // 扫全文会把那段历史一并判红。判据要落在**被断言的那个值**上，而不是整个文件。

        String report = sourceOf("LogReport");
        ok(report != null && !report.contains("天枢 Host"),
                "运行报告的标题不再写「天枢 Host」");
    }

    /**
     * N⁗. 发布卫生 —— 发出去的包不许带开发者的东西。
     *
     * 用户 2026-10-06：「这个APP我要对外开放，保证app无开发痕迹，包括但不限于：测试会话，key等隐私」。
     *
     * 两条守卫分别钉住两个**已经踩到**的漏子（都是先取证才发现的，见 `审计.md §27`）：
     *   ① `rootfs.tar.xz` 里带着 `./root/.rivet/` —— 5 个开发会话、`config.json`、`meridian.db`。
     *      起因：`build-rootfs.sh` 的清理清单里没有它（脚本从没主动铺过 `.rivet`，
     *      里头的全是**运行期**攒下的），于是原样打进 APK 发给每一个下载者。
     *   ② `AndroidManifest.xml` 写着 `android:debuggable="true"` —— 任何人 `adb run-as`
     *      就能读走 App 私有目录（本会话一直在用这条路取证，正好说明它对别人也一样好使）。
     *
     * 这两条都落在"源码里的一行"上，所以能用源码守卫钉死；**产物**那一层靠打包脚本自带的自检
     * （`build-rootfs.sh` 里"打回"那段）。
     */
    static void testReleaseHygiene() throws Exception {
        System.out.println("== N⁗. 发布卫生（无开发痕迹）==");

        // ① 包必须不可调试（默认即 false，谁都不许在 manifest 里写死 true）
        String manifest = readText(new File("AndroidManifest.xml"));
        ok(!manifest.contains("android:debuggable=\"true\""),
                "manifest 不带 debuggable=true（否则 adb run-as 能读走用户私有数据）");
        ok(!manifest.contains("android:debuggable="),
                "manifest 不写 debuggable **属性** —— 要开只由构建开关注入");
        // ⚠️ 判据卡在"属性写法"（含等号）上，不扫全文：文件里有一段注释正当地解释了
        // "这里为什么不写 android:debuggable"（以及 `DEBUGGABLE=1 sh build.sh` 怎么开），
        // 扫全文会把那段说明一并判红 —— 与 `testAppName` 那次是同一个坑，别再犯。
        // 判据仍有效：谁把 `android:debuggable="true"` 写回来，这条就红。

        // ② 可调试由构建开关控制，而不是写死在包里
        String build = readText(new File("build.sh"));
        ok(build.contains("--debug-mode"),
                "可调试走构建开关（aapt2 --debug-mode），默认发布版不开");

        // ③ rootfs 打包前必须清掉运行期数据，且打包后要自检
        File brFile = new File("../rootfs/build-rootfs.sh");
        ok(brFile.isFile(), "找得到 ../rootfs/build-rootfs.sh（HostTest 的 cwd 是 host/）");
        if (brFile.isFile()) {
            String br = readText(brFile);
            ok(br.contains("rm -rf \"$R/root/.rivet\""),
                    "打包前删掉 /root/.rivet（会话 / 配置 / 记忆 / 编译缓存都在这儿）");
            ok(br.contains("打回"),
                    "打包后有自检：产物里再出现个人数据就报错退出");
        }
    }

    /**
     * N⁵. 会话页**切回来要自己刷新**。
     *
     * 背景（2026-10-06 实测撞上）：在别处删过 / 改名过 / 归档过会话后切回会话页，
     * 列表还列着**已经删掉的条目** —— API 删完 6 条、页面仍显示 5 条，点页头刷新钮才变空态。
     * 根因：`SessionListActivity.onResume()` 只重贴主题、不重新取数，而切页走的是
     * `FLAG_ACTIVITY_REORDER_TO_FRONT`（不重走 onCreate，所以 onResume 是唯一的时机）。
     *
     * ⚠️ 但不能直接调 `reload()`：它会**立刻清空列表并显示 loading**，失败时还把列表顶成
     * 错误态 —— 每次切页都闪一下、甚至把好数据盖掉。所以走 `reload(true)` 静默刷新。
     * 三条断言把这三件事钉住（改回裸 reload() 会红）。
     */
    static void testSessionListResume() throws Exception {
        System.out.println("== N⁵. 会话页切回来要刷新 ==");

        String src = sourceOf("SessionListActivity");
        ok(src != null, "读得到 SessionListActivity 源码");
        if (src == null) return;
        String code = stripComments(src);

        ok(code.contains("reload(true)"),
                "onResume 走静默刷新（reload(true)）—— 别改回裸 reload()，那会每次切页都闪");
        // 判据落在**语义**上：「静默只换状态行；非静默才清空列表并显示 loading」。
        // 别绑死某种写法（第一版写成 `if (!silent)`，而实现用的是 `if (silent) … else …`，
        // 白白红了一次 —— 与 testAppName / testReleaseHygiene 是同一类毛病）。
        int iSilent = code.indexOf("if (silent)");
        int iLoading = code.indexOf("AppKit.loading");
        ok(iSilent >= 0 && iLoading > iSilent,
                "静默刷新不清空列表（loading 只在非静默那条路里，位于 if (silent) 之后）");
        ok(code.contains("刷新失败") || code.contains("还是上次"),
                "静默刷新失败时只换一行状态，不把列表顶成错误态");
    }

    /**
     * Q. 左右滑动切页。
     *
     * 判据三条各钉一条：够不够长（阈值 60dp）、是不是横滑（竖滑滚列表不许切）、
     * 到没到边界（第一页不能再往右、最后一页不能再往左）。方向也钉死：**左滑 = 下一格**。
     * 二级页（命令面板，current=-1）一律不切。
     */
    static void testSwipeNav() throws Exception {
        System.out.println("== Q. 左右滑动切页 ==");

        // 方向：左滑（dx<0）前进一格，右滑（dx>0）后退一格 —— 与底栏顺序一致
        eq(SwipeNav.targetIndex(Tabs.CHAT, -100f, 0f), Tabs.SESSIONS, "左滑：对话 → 会话");
        eq(SwipeNav.targetIndex(Tabs.SESSIONS, -100f, 0f), Tabs.SETTINGS, "左滑：会话 → 设置");
        eq(SwipeNav.targetIndex(Tabs.SETTINGS, 100f, 0f), Tabs.SESSIONS, "右滑：设置 → 会话");
        eq(SwipeNav.targetIndex(Tabs.SESSIONS, 100f, 0f), Tabs.CHAT, "右滑：会话 → 对话");

        // 边界：到头了不动（-1）—— 不能绕回去
        eq(SwipeNav.targetIndex(Tabs.CHAT, 200f, 0f), -1, "头一页再右滑 —— 不切");
        // 2026-10-06：末格先是「设置」（外观页收进设置后），同日补了「个人」后末格是它
        eq(SwipeNav.targetIndex(Tabs.SETTINGS, -100f, 0f), Tabs.PROFILE, "左滑：设置 → 个人");
        eq(SwipeNav.targetIndex(Tabs.PROFILE, 100f, 0f), Tabs.SETTINGS, "右滑：个人 → 设置");
        eq(SwipeNav.targetIndex(Tabs.PROFILE, -200f, 0f), -1, "末一页（个人）再左滑 —— 不切");

        // 位移阈值：刚好够就切，差一点不切
        eq(SwipeNav.targetIndex(Tabs.CHAT, -SwipeNav.MIN_DISTANCE_DP, 0f), Tabs.SESSIONS,
                "刚够阈值就切（≥60dp）");
        eq(SwipeNav.targetIndex(Tabs.CHAT, -(SwipeNav.MIN_DISTANCE_DP - 1), 0f), -1,
                "差一点不切（<60dp）");

        // 横竖判别：竖滑（滚列表）不许切页 —— 这是"滑列表滑出一半跳页了"的防线
        eq(SwipeNav.targetIndex(Tabs.CHAT, -100f, 100f), -1, "斜着多为竖滑 —— 不切");
        eq(SwipeNav.targetIndex(Tabs.CHAT, -150f, 100f), Tabs.SESSIONS, "横得够多、竖也不小 —— 仍切");

        // 二级页（命令面板 current=-1）不参与切页
        eq(SwipeNav.targetIndex(-1, -200f, 0f), -1, "二级页不切页");

        // 能滑到的目标必须在 Tabs 里认得出（改了页签定义忘改这边，这条会红）
        ok(Tabs.byId(SwipeNav.targetIndex(Tabs.CHAT, -100f, 0f)) != null, "目标页在 Tabs 里（左滑）");
        ok(Tabs.byId(SwipeNav.targetIndex(Tabs.SETTINGS, 100f, 0f)) != null, "目标页在 Tabs 里（右滑）");

        // 真机踩过：对话页输入框上方那行状态 chip 曾经是 HorizontalScrollView，用户横滑它想看完，
        // 结果被当成切页手势、一滑就跳到「会话」页去了。
        // 2026-10-05：chip 行早已改成 FlowRow（不横滑）、全项目 `new HorizontalScrollView` 为 0，
        // 那条守卫恒不命中 → 按决定移除（死代码看起来像防护，比没有防护更危险）。
        //
        // 契约写成**等价式**（T1 修正）：
        //   守卫该不该在 ⟺ 全项目有没有真的横滑容器。
        //   现在 0 个容器 ⇒ 等价于"不许有守卫"（防无声复活）；
        //   将来真引入容器 ⇒ 断言自动**反过来要求恢复守卫** —— 不会与 SwipeNav 的注释打架。
        // 判定只看**代码**（注释里提到旧写法不算，同 testJumpToLatest 的口径）；
        // 只有"知识留在注释里"那条**故意**用原文。
        String src = sourceOf("SwipeNav");
        ok(src != null, "读得到 SwipeNav 源码");
        if (src != null) {
            boolean guardInCode = stripComments(src).contains("startsOnHorizontalScroll");
            boolean newHScroll = srcHasNewHorizontalScrollView();
            // ⚠ 消息只报**探测器的发现**，不报"容器不存在"：探测器是代理信号，看不见
            //   inflate / 工厂方法 / 自绘的横向滚动视图（后者连 SwipeNav 注释都列进了复加条件）。
            //   说"容器不在"就是在撒谎 —— T3 探针 B 抓到的正是"代理被当成真值"。
            ok(newHScroll == guardInCode,
                    "守卫的存在与『src/ 里有 new HorizontalScrollView』一致（探测器"
                            + (newHScroll ? "发现" : "未发现") + " ⇒ 守卫"
                            + (newHScroll ? "必须在" : "必须不在") + "）");
            ok(src.contains("什么条件下必须加回来"),
                    "移除理由与『何时必须加回来』留在注释里（知识不丢）");
        }
    }

    /**
     * **探测器**：`src/` 里有没有 `new HorizontalScrollView`（简单名或全限定名）——
     * 决定 {@link SwipeNav} 该不该带那条"起手在横滑控件上就不切页"的守卫。
     *
     * 为什么是**等价式**而不是单向禁令：守卫的存在与否本来就该跟着**容器在不在**走。
     * 写成"永远不许出现"会和 `SwipeNav.java` 的注释自相矛盾（那边说"将来必须恢复"），
     * 后人照注释办正事反而被测试拦住。判据只看**代码**（`stripComments`），注释不算数。
     *
     * ⚠ **只扫 `src/`，故意不含 `test/`** —— 名字里的 `src` 是有意的：契约护的是**生产行为**，
     *   测试里 `new` 一个横滑容器不影响 App。（T3 请求 3 的判定。）
     * ⚠ 它是**代理信号**，不是"容器存在性"：`inflate` 出来的、工厂方法建的、自绘的横向滚动视图
     *   它都看不见。所以断言消息只说"探测器未发现"，不断言"容器不存在"。（T3 请求 2。）
     * ⚠ 必须匹配**全限定名**：`new android.widget.HorizontalScrollView(c)` 是合法且常见的写法，
     *   旧版 `contains("new HorizontalScrollView")` 对它假阴性、还会让消息撒谎。（T3 探针 B 的教训。）
     */
    private static boolean srcHasNewHorizontalScrollView() throws Exception {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "new\\s+(android\\.widget\\.)?HorizontalScrollView\\b");
        File dir = new File("src/dev/tianshu/host");
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            if (p.matcher(stripComments(readText(f))).find()) return true;
        }
        return false;
    }

    /**
     * AA. 对话页状态栏 —— 对齐 Termux TUI 的状态行。
     *
     * 每一项都对着 harness 的**实现**核对过（dist/main.js），不是照符号猜：
     *   模型名（当前生效模型）· ⚡最近 3 轮命中率 · ◷峰时/闲时（本地时钟）
     *   · ◧上下文占用 · 本轮用时 · ▸审批 · ☥星域
     */
    static void testStatusBar() throws Exception {
        System.out.println("== AA. 对话页状态栏 ==");

        // ---- 1. 会话项（SessionList.Item）解析 ----
        String json = readText(new File("test/fixtures/sessions-real.json"));
        List<SessionList.Item> items = SessionList.parse(json);
        eq(items.size(), 4, "真实会话响应 4 条");
        SessionList.Item it = items.get(0);
        eq(it.model, "deepseek-v4-flash", "会话项 model");
        eq(it.domain, "qiming", "会话项 domain");
        eq(it.domainGlyph, "☥", "会话项 domainGlyph");
        eq(it.reasoningEffort, "medium", "会话项 reasoningEffort");
        eq(it.contextWindow, 1000000, "会话项 contextWindow");
        eq(it.contextTokens, 27882, "会话项 contextTokens");
        eq(SessionList.parse("{\"sessions\":[{\"id\":\"x\"}]}").get(0).model, "",
                "缺 model → 空串（不是 null）");

        // ---- 2. 上下文百分比 ----
        eq(StatusBar.contextPercent(27882, 1000000), 3, "27882/1000000 四舍五入 = 3");
        eq(StatusBar.contextPercent(1000000, 1000000), 100, "满 = 100");
        eq(StatusBar.contextPercent(1, 0), -1, "分母 0 → -1（不抛除零）");
        eq(StatusBar.contextPercent(-5, 100), -1, "负 tokens → -1");
        eq(StatusBar.contextText(27882, 1000000), "3%", "算得出 → '3%'");
        eq(StatusBar.contextText(1, 0), "", "算不出 → 空（跳过该 chip）");
        eq(StatusBar.contextText(0, 1000000), "0%", "真 0 显示 0%（与『算不出』区分）");

        // ---- 3. 缓存命中率：**比例**（最近 3 轮），不是项目累计 ----
        eq(StatusBar.cacheText("0.83"), "83%", "比例 0.83 → 83%");
        eq(StatusBar.cacheText("0"), "0%", "0 → 0%");
        eq(StatusBar.cacheText("1"), "100%", "1 → 100%");
        eq(StatusBar.cacheText("null"), "", "null → 空（跳过该 chip）");
        eq(StatusBar.cacheText(null), "", "null 入参 → 空");
        eq(StatusBar.cacheText("abc"), "", "非数字 → 空");
        eq(StatusBar.cacheText("-1"), "", "负数 → 空");
        eq(StatusBar.recentHitRate("{\"cache\":{\"recentTurnHitRate\":0.62}}"), "0.62",
                "从 cockpit 快照取最近命中率");
        eq(StatusBar.recentHitRate("{}"), null, "cockpit 里没有该字段 → null");

        // ---- 4.（原「峰时 / 闲时」一整段已随那枚 chip 一起去掉 ——
        //          用户 2026-10-05 原话：「小功能条里面的闲峰计价时段去掉，不要了」）----

        // ---- 5. 用时 ----
        eq(StatusBar.elapsedText(0), "0s", "0 毫秒 → 0s");
        eq(StatusBar.elapsedText(999), "0s", "不足 1 秒 → 0s");
        eq(StatusBar.elapsedText(12000), "12s", "12 秒");
        eq(StatusBar.elapsedText(83000), "1m23s", "1 分 23 秒");
        eq(StatusBar.elapsedText(3600000), "1h0m", "1 小时");
        eq(StatusBar.elapsedText(-1), "", "负数 → 空");

        // ---- 6. 审批模式 ----
        eq(StatusBar.approvalLabel("dangerously-skip-permissions"), "全自动", "真夹具值 → 全自动");
        eq(StatusBar.approvalLabel("manual"), "手动", "manual → 手动");
        eq(StatusBar.approvalLabel("weird"), "weird", "未知原样回显");
        eq(StatusBar.approvalLabel(null), "", "null → 空");

        // ---- 7. 星域 ----
        String dom = apiFixture("/config/default-domain");
        eq(StatusBar.domainName(dom, "qiming"), "启明", "qiming → 启明");
        eq(StatusBar.domainName(dom, "yaoguang"), "瑶光", "yaoguang → 瑶光");
        eq(StatusBar.domainName(dom, "nope"), "nope", "找不到 → 原样返回 id");
        eq(StatusBar.domainName(null, "qiming"), "qiming", "无 JSON → 原样");

        // 星域清单（对话页「点星域 chip 换星域」用的那份，界面只负责画）
        List<String> pairs = StatusBar.domainPairs(dom);
        ok(pairs.size() >= 2, "星域清单至少 2 条（实际 " + pairs.size() + "）");
        ok(pairs.contains("qiming|启明"), "qiming → 「qiming|启明」：" + pairs);
        ok(StatusBar.domainPairs(null).isEmpty(), "null → 空清单");
        ok(StatusBar.domainPairs("{}").isEmpty(), "没有 domains 键 → 空清单");
        ok(StatusBar.domainPairs("not json").isEmpty(), "垃圾输入 → 空清单，不抛");

        // ---- 8. build：顺序与 TUI 一致，缺数据不跳过（2026-10-05 起「峰/闲」已去掉，共 7 枚）----
        List<StatusBar.Chip> all = StatusBar.build(
                "deepseek-v4-flash", "0.83", 27882, 1000000, 0L,
                "dangerously-skip-permissions", "high", "☥", "启明");
        eq(all.size(), 7, "七项齐全 → 7 个 chip");
        eq(all.get(0).kind, "model", "顺序 1：模型");
        eq(all.get(1).kind, "effort", "顺序 2：档位");
        eq(all.get(2).kind, "cache", "顺序 3：缓存");
        eq(all.get(3).kind, "context", "顺序 4：上下文");
        eq(all.get(4).kind, "elapsed", "顺序 5：用时");
        eq(all.get(5).kind, "permission", "顺序 6：审批");
        eq(all.get(6).kind, "domain", "顺序 7：星域");
        eq(all.get(0).label(), "deepseek-v4-flash", "模型 chip 显示**完整**名（用户：「不要删减」）");
        eq(all.get(0).icon, Icon.STAR, "模型 chip 带自绘星标（用户 2026-10-04：每枚都要有图标）");
        eq(all.get(1).label(), "高", "档位 chip 的文字");
        eq(all.get(1).icon, Icon.LEVEL, "档位 chip 用自绘强度条");
        eq(all.get(2).label(), "83%", "缓存 chip 的文字");
        eq(all.get(2).icon, Icon.BOLT, "缓存 chip 用自绘闪电");
        eq(all.get(3).label(), "3%", "上下文 chip 的文字");
        eq(all.get(3).icon, Icon.GAUGE, "上下文 chip 用自绘仪表");
        eq(all.get(4).label(), "0s", "用时 chip");
        eq(all.get(4).icon, Icon.PULSE, "用时 chip 用自绘心跳线（每枚都要有图标）");
        eq(all.get(5).label(), "全自动", "审批 chip 的文字");
        eq(all.get(5).icon, Icon.SHIELD, "审批 chip 用自绘盾牌");
        eq(all.get(6).label(), "启明", "星域 chip 的文字");
        eq(all.get(6).icon, Icon.BRAND, "星域 chip 用自绘徽记");

        // ---- 8b. 没数据也**七枚齐全**（用户 2026-10-04 要常驻；缺的用 `—` 占位，不留假数字）----
        List<StatusBar.Chip> empty =
                StatusBar.build(null, null, 0, 0, -1, null, null, null, null);
        eq(empty.size(), 7, "全空也出满 7 枚（用户明确要「就算没对话也要显示」）");
        eq(empty.get(0).label(), "未选模型", "没选模型时的占位");
        eq(empty.get(1).label(), "—", "没有档位数据时的占位");
        eq(empty.get(1).icon, Icon.LEVEL, "档位占位那枚也带图标（自绘强度条）");
        eq(empty.get(2).label(), "—", "没有缓存数据时的占位");
        eq(empty.get(2).icon, Icon.BOLT, "占位那枚也带图标");
        eq(empty.get(3).label(), "—", "没有上下文时的占位");
        eq(empty.get(4).label(), "0s", "没跑过时用时显示 0s");
        eq(empty.get(5).label(), "—", "没有审批数据时的占位");
        eq(empty.get(6).label(), "—", "没有星域时的占位");
        for (StatusBar.Chip c : empty) {
            ok(c.label().indexOf("null") < 0, "占位文本不含 'null'：" + c.label());
        }
        for (StatusBar.Chip c : all) {
            ok(c.label().indexOf("null") < 0, "chip 文本不含 'null'：" + c.label());
        }

        // 有 glyph 但没名字：星域那枚仍是一枚（图标在、文字用占位）
        List<StatusBar.Chip> glyphOnly =
                StatusBar.build(null, null, 0, 0, -1, null, null, "☥", null);
        eq(glyphOnly.size(), 7, "只有 domainGlyph 时也是七枚");
        eq(glyphOnly.get(6).icon, Icon.BRAND, "星域 chip 带自绘徽记");
        eq(glyphOnly.get(6).label(), "—", "没有名字时用占位");

        // ---- 9. 端到端：真夹具 + 固定时钟 → chip 行（不 mock 中间层）----
        String apprJson = apiFixture("/config/approval");
        String cockpitJson = "{\"cache\":{\"recentTurnHitRate\":0.62}}";
        Providers.Model cm = Providers.currentModel(
                Providers.parse(apiFixture("/config/providers")),
                Providers.defaultModel(apiFixture("/config/default-model")));
        String curModel = cm == null ? "" : cm.id;
        List<StatusBar.Chip> e2e = StatusBar.build(
                curModel,
                StatusBar.recentHitRate(cockpitJson),
                it.contextTokens, it.contextWindow,
                0L,
                MiniJson.str(apprJson, "approval"),
                it.reasoningEffort,
                it.domainGlyph, StatusBar.domainName(dom, it.domain));
        eq(e2e.size(), 7, "端到端：七项齐全");
        eq(e2e.get(0).label(), "deepseek-v4-flash", "端到端：模型名完整显示（不删减）");
        eq(e2e.get(1).kind, "effort", "端到端：档位那枚排在模型之后");
        eq(e2e.get(2).label(), "62%", "端到端：缓存来自 cockpit 最近命中率");
        eq(e2e.get(3).label(), "3%", "端到端：上下文 27882/1000000");
        eq(e2e.get(4).label(), "0s", "端到端：用时");
        eq(e2e.get(5).label(), "全自动", "端到端：审批");
        eq(e2e.get(6).label(), "启明", "端到端：星域");
        eq(e2e.get(6).icon, Icon.BRAND, "端到端：星域带自绘徽记");

        // ---- LocalData：App 侧本地读盘数据源（B1：不改内核重建"内存态"命令）----
        eq(LocalData.projectSlug("/root"), "root-94a6b4",
                "LocalData：projectSlug(/root) 与真机目录名逐字一致");
        eq(LocalData.projectSlug("/root/"), "root-94a6b4",
                "LocalData：尾斜杠归一化后仍一致");
        eq(LocalData.projectSlug(null), null, "LocalData：null → null");
        eq(LocalData.sha256Hex("/root").substring(0, 6), "94a6b4",
                "LocalData：sha256(/root) 前 6 位 == 94a6b4");
        ok(LocalData.tailLines("a\nb\nc\n", 2).size() == 2, "LocalData：tailLines 取末 2 行");
        eq(LocalData.tailLines("a\nb\nc\n", 2).get(0), "b", "LocalData：tailLines 顺序保持");
        ok(LocalData.tailLines(null, 3).isEmpty(), "LocalData：null 文本 → 空");

        // 用临时目录构造一份"落盘现场"，验证各命令的读取与文本化
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"), "ld-" + System.nanoTime());
            File proj = new File(tmp, "root-94a6b4");
            ok(proj.mkdirs(), "LocalData：临时会话目录可建");
            writeText(new File(proj, "s1.todos.json"),
                    "[{\"id\":\"a1\",\"content\":\"fix bug\",\"status\":\"pending\"}]");
            writeText(new File(proj, "s1.meta.json"),
                    "{\"sessionId\":\"s1\",\"model\":\"deepseek-v4-flash\",\"domain\":\"tianshu\","
                            + "\"cwd\":\"/root\",\"status\":\"active\",\"planModeState\":\"off\","
                            + "\"askModeState\":\"off\",\"cleanExit\":false,\"turnCount\":3,"
                            + "\"toolCallCount\":7,\"lastStopReason\":\"stop\"}");
            ok(LocalData.todos(tmp, "/root", "s1").contains("fix bug"),
                    "LocalData：todos 从 <id>.todos.json 读出条目");
            ok(LocalData.todos(tmp, "/root", "missing").contains("还没有待办"),
                    "LocalData：无 todos 文件时给人话而不是报错");
            ok(LocalData.debug(tmp, "/root", "s1").contains("deepseek-v4-flash"),
                    "LocalData：debug 从 meta.json 读出模型名");
            ok(LocalData.verify(tmp, "/root", "s1").contains("stop"),
                    "LocalData：verify 读出 lastStopReason");
            ok(LocalData.context(tmp, "/root", "s1").contains("回合数"),
                    "LocalData：context 给出账本文本");
            ok(LocalData.memory(tmp, "/root", "s1").contains("还没有记忆"),
                    "LocalData：无 memory 文件时给人话");
            ok(LocalData.sensorium(tmp, "/root", "s1").contains("还没有六维遥测"),
                    "LocalData：无 sensorium 时给人话");

            // P1′：/branch —— 父/子关系全在 meta.json 里（harness slash-commands.ts:2436 同源）
            writeText(new File(proj, "s0.meta.json"),
                    "{\"sessionId\":\"s0\",\"title\":\"根会话\",\"createdAt\":1791036120641}");
            writeText(new File(proj, "s1.meta.json"),
                    "{\"sessionId\":\"s1\",\"parentSessionId\":\"s0\",\"branchName\":\"try-idea\","
                            + "\"createdAt\":1791036120641}");
            writeText(new File(proj, "s2.meta.json"),
                    "{\"sessionId\":\"s2\",\"parentSessionId\":\"s1\",\"branchName\":\"child-a\","
                            + "\"createdAt\":1791036120641}");
            String br = LocalData.branch(tmp, "/root", "s1");
            ok(br != null && br.contains("父会话: s0"), "LocalData：branch 读出父会话 id");
            ok(br.contains("根会话") && br.contains("try-idea"), "LocalData：branch 带父标题与分支名");
            ok(br.contains("子分支 (1)") && br.contains("child-a"), "LocalData：branch 扫出子分支");
            ok(LocalData.branch(tmp, "/root", "s2").contains("父会话: s1"),
                    "LocalData：branch 对子会话也能给出父");
            ok(LocalData.branch(tmp, "/root", "s-nope").contains("父会话: 无"),
                    "LocalData：没有 meta 的会话按根会话处理（不抛）");
        } catch (Throwable t) {
            ok(false, "LocalData：临时现场测试抛异常 " + t);
        }
    }

    /**
     * V. 回放窗口截断 —— 「老会话少了半截记录」要有据可依。
     *
     * `/stream` 只回放**内存环**内的事件；服务端在流开头发 `replay_window`
     * （`diskFirstSeq` / `floorSeq`）。`floorSeq > diskFirstSeq` ⇒ 前面还有没送来的事件，
     * 界面必须据此如实告知，而不是静默少一段（2026-10-05 真机反馈）。
     */
    static void testReplayWindow() {
        System.out.println("== 回放窗口截断 ==");

        ConversationState fresh = new ConversationState();
        ok(!fresh.hasEarlierHistory(), "没收到 replay_window 时不算截断（不能凭空报警）");

        ConversationState whole = new ConversationState();
        whole.apply("replay_window", "{\"seq\":0,\"type\":\"replay_window\","
                + "\"data\":{\"floorSeq\":1,\"diskFirstSeq\":1,\"diskLastSeq\":120}}");
        ok(!whole.hasEarlierHistory(), "窗口与磁盘齐平 ⇒ 不算截断");

        ConversationState cut = new ConversationState();
        cut.apply("replay_window", "{\"seq\":0,\"type\":\"replay_window\","
                + "\"data\":{\"floorSeq\":401,\"diskFirstSeq\":1,\"diskLastSeq\":520}}");
        ok(cut.hasEarlierHistory(), "floorSeq(401) > diskFirstSeq(1) ⇒ 有更早的历史没送来");
        eq(cut.floorSeq, 401, "floorSeq 记下来了");
        eq(cut.diskFirstSeq, 1, "diskFirstSeq 记下来了");
        eq(cut.diskLastSeq, 520, "diskLastSeq 记下来了");
    }

    /**
     * W. 弹层长列表必须可滚动 —— 2026-10-05 真机反馈「切星域滑不动，看不到其他星域」。
     * 根因：ActionSheet 把整列直接塞进 WRAP_CONTENT 的窗口，超出屏高的行既滚不动也点不到
     * （模型选择列表更长，一样会撞上）。这条钉住结构，防止再退回去。
     */
    static void testSheetScrollable() throws Exception {
        System.out.println("== 弹层长列表可滚动 ==");
        String src = sourceOf("ActionSheet");
        ok(src != null && src.contains("ScrollView"), "ActionSheet 用 ScrollView 容纳长列表");
        ok(src != null && src.contains("AT_MOST"), "弹层高度有上限（长列表不顶穿屏幕）");
        ok(src != null && src.contains("onMeasure"), "上限由 onMeasure 实施（短列表仍按内容自适应）");
    }

    /**
     * 回放读完的判据 —— 「老会话只显示第 1 轮」的根因。
     *
     * 回放一条老会话时，服务端把**每一轮历史**都发一遍，流里躺着 N 个 `done`。
     * 读流循环原先一看到 `done` 就 break → 只渲染出第 1 轮（2026-10-05 真机复现：
     * 14 轮的会话 App 只显示 1 轮，发消息后视图跳到第 2 轮，用户报"记录不在了 + 对不上"）。
     */
    static void testReplayDoneGate() {
        System.out.println("== 回放读完判据 ==");

        ConversationState st = new ConversationState();
        st.apply("replay_window", "{\"seq\":0,\"data\":{\"floorSeq\":1,\"diskFirstSeq\":1,\"diskLastSeq\":807}}");
        st.apply("user", "{\"seq\":1,\"data\":{\"text\":\"你好天枢\"}}");
        st.apply("done", "{\"seq\":34,\"data\":{\"status\":\"completed\"}}");
        ok(st.isFinished(), "第 1 轮的 done 到了（isFinished 为真）");
        ok(!st.replayFinished(), "回放没读完（34 < 807）→ **不能**收流，否则只显示第 1 轮");
        st.apply("user", "{\"seq\":37,\"data\":{\"text\":\"你现在在什么星域\"}}");
        st.apply("done", "{\"seq\":81,\"data\":{\"status\":\"completed\"}}");
        ok(!st.replayFinished(), "第 2 轮的 done 也不能收流");
        st.apply("status", "{\"seq\":807,\"data\":{\"status\":\"idle\"}}");
        ok(st.replayFinished(), "读完回放且没有在跑的轮 → 收流");

        ConversationState live = new ConversationState();
        live.apply("replay_window", "{\"seq\":0,\"data\":{\"floorSeq\":1,\"diskFirstSeq\":1,\"diskLastSeq\":100}}");
        live.apply("status", "{\"seq\":100,\"data\":{\"status\":\"idle\"}}");
        live.apply("user", "{\"seq\":101,\"data\":{\"text\":\"hi\"}}");
        live.apply("text_delta", "{\"seq\":102,\"data\":{\"text\":\"a\"}}");
        ok(!live.replayFinished(), "回放后新起的活轮还在流 → 别收流");
        live.apply("done", "{\"seq\":120,\"data\":{\"status\":\"completed\"}}");
        ok(live.replayFinished(), "活轮的 done 到了 → 收流");

        ConversationState nowin = new ConversationState();
        nowin.apply("done", "{\"seq\":5,\"data\":{\"status\":\"completed\"}}");
        ok(nowin.replayFinished(), "拿不到 replay_window → 退回旧行为（第一个 done 即收流）");
    }

    /**
     * 「回到最新」必须真的滚到底 —— 2026-10-05 真机复现：点一下只挪约一行、按钮一直在
     * （用户报"点了又弹回去"）。根因：`fullScroll(FOCUS_DOWN)` 滚到的是**被聚焦的那个
     * 子视图**，一轮里子视图一多就只挪一小段。
     */
    static void testJumpToLatest() throws Exception {
        System.out.println("== 回到最新 ==");
        String src = sourceOf("ChatActivity");
        String code = src == null ? null : stripComments(src);   // 注释里提到旧写法不算
        ok(code != null && !code.contains("fullScroll("), "不再用 fullScroll(FOCUS_DOWN)（只露聚焦项）");
        ok(code != null && code.contains("scrollTo(0, child.getHeight())"), "直接滚到内容底端");
    }
}
