# 最小宿主（Wave 2）

把 rootfs 变成"活的进程"：App 自带 89 MB 的 `rootfs.tar.xz` 与 proot，
首次启动解压到私有目录，用 proot 以它为 rootfs 起一个 Linux 用户态，在里面跑 `tianshu`。

## 结构

```
AndroidManifest.xml     包名 dev.tianshu.host，extractNativeLibs=true
res/values/strings.xml  app 名「天枢」
src/dev/tianshu/host/
  MainActivity.java     界面 + 4 步流程（自检 → 解压 → 起 proot → 试 serve）
  RootfsInstaller.java  解压 tar.xz 到私有目录；原子可见 + 幂等
  TarReader.java        最小 tar 读取器（含符号链接 / GNU 长名 / PAX）
  AndroidSink.java      android.system.Os 版文件系统操作（API 21+）
  RuntimeHost.java      拼 proot 命令行与环境变量
  RuntimeBinds.java     proot -b 清单的纯逻辑（不 import android.*，所以可测）
  AppRuntime.java       常驻 serve 的进程句柄与连接信息
  ChatActivity.java     对话页（POST /sessions → SSE）；带 session_id 时重放**并可直接续聊**
  SessionListActivity.java 历史会话列表（点一条进去；**长按**改名 / 归档 / 取消归档 / 删除）
  SessionActions.java   会话管理动作的纯逻辑：方法 / 路径 / 请求体 / 错误文案
                        （⚠ 用户说的「删除」= `/permanent`；裸 `DELETE /sessions/:id` 是**归档**）
  SessionActionMenu.java 改名/归档/删除的操作面板，列表页与侧拉菜单共用一份实现
  ActionSheet.java      自绘的底部操作面板与单行输入框（不用系统按钮栏 —— 它不在
                        Theming 遍历的 View 树里，深色主题下会瞎）
  SessionList.java      /sessions 响应的解析与相对时间（含 archived；纯逻辑）
  SseParser.java        SSE 帧解析
  ConversationState.java 会话状态累积（含"done 之后主动断流"的判据、续聊水位 lastSeq）
  MiniJson.java         极简 JSON 取值（含 longNum —— 13 位毫秒时间戳必须用 long）
  ConfigInstaller.java  把 assets 里的配置铺进 rootfs 的 ~/.rivet
test/dev/tianshu/host/
  HostTest.java         纯逻辑断言（2026-10-02 实测 passed=1031 / failed=0，不需要 Android）
  NioSink.java          java.nio 版 Sink，仅供测试
libs/xz-1.10.jar        XZ for Java（纯 Java，被打进 dex）
build.sh                出包脚本（aapt2 → javac → D8 → zipalign → apksigner）
libselftest.sh          容器内的等价预验证（见下）
probe-binds.sh          容器内实测 proot 的 -b 行为（见下）
```

## 用 Java 而不是 Kotlin

计划里写的是 Kotlin，但**容器里没有 `kotlinc`，也没有 Gradle**（只有 `javac 17`）。
在手搓构建链的前提下，Kotlin 落不了地，改用 Java。这是本波对计划的一处偏离。

## 构建与测试

```bash
sh host/build.sh          # → host/build/tianshu-host.apk，并自动拷到 Download
sh host/libselftest.sh    # 容器内预验证：用 APK 里的 proot 起 rootfs
sh host/probe-binds.sh    # 容器内实测 proot 的 -b：guest 目标要不要预建 / host 不存在会怎样

# 纯逻辑测试（不需要 Android 运行时，但编译期要 android.jar，
# 因为 AndroidSink/RuntimeHost/MainActivity 引用 android.*）
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
cd host && javac -encoding UTF-8 -cp "$AJ:libs/xz-1.10.jar" -d /tmp/t \
    src/dev/tianshu/host/*.java test/dev/tianshu/host/*.java \
  && java -cp "/tmp/t:libs/xz-1.10.jar" dev.tianshu.host.HostTest
```

### 签名密钥与「覆盖安装」（别再每次卸载了）

签名密钥在 **`host/.keystore/tianshu-host.keystore`**，**不在 `build/` 里面** ——
因为 `build.sh` 第 0 步会 `rm -rf "$OUT"`，密钥一旦放进去就**每次构建重新生成**；
而 Android 只允许**同一把密钥**签的包覆盖安装，密钥一变就报 "App not installed"，
只能先卸载 —— 而卸载会把 App 私有目录里的 rootfs（518 MB）与 `~/.rivet/sessions`
的全部会话历史一起抹掉。

自查点：构建日志末尾会打 `--- 签名指纹（下次构建应与此一致）---`。
两次构建指纹一致 ⇒ 可以直接覆盖安装；不一致 ⇒ 就会要求卸载。

`.keystore/` 已进 `.gitignore`（私钥不入库）。换机器 / 换容器重建时首次会生成新密钥，
**那一次**需要卸载，之后就一直复用。

### 用 adb 装包：必须加 `--no-incremental`

`adb install` 默认走**增量安装（Incremental）**，它**不解压命名非标准的 `.so`** ——
本 App 的 `libtalloc.so.2`（proot 依赖）因此不会出现在设备的 `nativeLibraryDir`，
启动时 `CANNOT LINK EXECUTABLE ".../libproot.so"`，**App 卡在「3/4 起常驻 serve」**
（APK 里明明有它，设备的 `nativeLibraryDir` 里却只有 3 个 so）。

正确命令：

```bash
adb install -r --no-incremental host/build/tianshu-host.apk
```

## 实测记录（2026-10-01）

- `HostTest`：**passed=36 failed=0** —— tar 解析 / 符号链接 / GNU 长名 / 路径安全 8 条 /
  原子安装与幂等 / **硬链接不可用时回退为复制**
- `build.sh`：产物签名 v1/v2/v3 通过；APK 内 `assets/rootfs.tar.xz` 是 **STORED**
  （未二次压缩），sha256 `1e0089ce…70d0c` 与源包**逐字节一致**
- `libselftest.sh`：用 APK 内的 `libproot.so` + `libproot-loader.so`（`PROOT_LOADER` 指过去）
  成功起 rootfs → `Ubuntu 24.04.5` / `node v24.21.0` / `harness v3.27.0`，
  `/bin -> usr/bin` 符号链接完好

### 真机第一轮：撞到硬链接限制（已修）

首屏报：

```
FAIL: IOException
硬链接失败: .../rootfs.partial/usr/bin/perl5.38.2 <- .../rootfs.partial/usr/bin/perl
  (link failed: EACCES (Permission denied))
```

**根因**：Android 的 app 私有目录对 `untrusted_app` 域禁 `link(2)`。
`/usr/bin/perl` 与 `perl5.38.2` 在 tar 里是一条硬链接条目，于是第 2 步炸在这里。

**修法**：`Sink.supportsHardlink()` 声明能力；`AndroidSink` 返回 `false`，
`RootfsInstaller` 回退成复制文件（语义等价，只是多占一份空间）。

副作用：失败点在 hardlink，说明**在它之前的所有符号链接与 chmod 都已经过了** ——
这反过来说明 `Os.symlink` 在 app 私有目录里是可用的（rootfs 的骨架没塌）。

### 真机第二轮：前三步全过，卡在第 4 步（已修，待复验）

```
1/4 自检    ✓
2/4 解压    ✓  12607 个文件 / 518 MB，16s
3/4 proot   ✓  Ubuntu 24.04.5 · /bin -> usr/bin · node v24.21.0 · harness v3.27.0
4/4 serve   ✗  ready=no after=60s，health [000]
```

**第三轮是大奖**：proot 在 app 沙箱里真的跑起来了，rootfs 完好、符号链接正确，
node 与 harness 都能跑。Wave 2 里最不确定的那一环（"App 里能不能 exec proot 并起 rootfs"）就此解除。

解压完整性也交叉核过：源 rootfs 里 `11958 个普通文件 + 649 个符号链接 = 12607`，
与 App 报告的 **12607 完全一致**。

第 4 步失败的两个嫌疑（都已改）：

1. **manifest 一条 `uses-permission` 都没声明** —— Android 用 `AID_INET` 补充 gid
   管制 `socket(AF_INET, ...)`，没有 `INTERNET` 权限时**连 loopback 都会被 EACCES 拒**。
   serve 绑不上端口、curl 也连不上，正好是这个症状。已补 `INTERNET` + `ACCESS_NETWORK_STATE`。
2. **rootfs 里没有 `/etc/resolv.conf`** —— 打包时被删（保持镜像干净），
   而容器里一直靠 proot 的 `-b /etc/resolv.conf` 绕过。独立 App 没有这个 bind，
   任何域名解析都会失败或长时间挂起。已改为启动时从 `ConnectivityManager` 取 DNS
   写进 rootfs，取不到就兜底 `8.8.8.8` / `1.1.1.1`。

顺带修掉两处噪音：`PROOT_TMP_DIR`（Termux 版 proot 默认去 `$PREFIX/tmp` 找临时目录，
独立 App 里没有那个路径）；以及 `pkill -f 'rivet serve'` 会匹配到 bash 自己的命令行、
把脚本自身杀掉（日志里的 `vpid 1: terminated with signal 15` 就是它），改为按 `$!` 杀。

第 4 步的脚本这轮也补了诊断：外部网络自检、`kill -0` 判 serve 是否还活着、
以及 `tail /tmp/serve.log` —— 上一轮最大的问题是**看不到 serve 自己的日志**，只能猜。

### 真机第三轮：Wave 2 达成

```
4/4 serve   ready=yes  dead=no  after=1s
  外网 nodejs.org            -> 307
  health(no token)           -> 200 {"ok":false,"version":"3.27.0"}
  health(token)              -> 200 {"readiness":"ready","registryOk":true,
                                     "configured":false,"sessionCount":0,"runningCount":0, ...}
  readyz(token)              -> 200
  sessions(no token)         -> 401
  sessions(token)            -> 200 {"sessions":[]}
  serve.log: Rivet Runtime API listening on http://127.0.0.1:18799
```

补 `INTERNET` 权限 + 写 `resolv.conf` 之后，serve 从"60 秒起不来"变成 **1 秒就绪**。

Wave 2 计划的验证要点逐条对账：

| 要点 | 状态 |
|---|---|
| 真机 ①②③ 三条门槛全绿 | ✅ Gate 探针（v1 / v2 两轮） |
| 解压中断后重进不重复解压 | ✅ 第二轮显示"已存在，跳过（幂等）" |
| `GET /sessions` 返回 200 | ✅ 真机 200 `{"sessions":[]}` |
| `adb logcat` 无 `avc: denied` | ❌ 没有 adb 通道，**未验** |

**遗留噪音**：`proot warning: Unable to create temp directory for f2fs bug probe: Permission denied`
—— 即使设了 `PROOT_TMP_DIR=/tmp` 仍在。proot 的 f2fs 探测要在**宿主**侧建临时目录，
而 App 里宿主可写的只有自己的私有目录。仅是 warning，不影响功能，待消音。

**下一步的前提条件**：`configured:false`，serve.log 里写着
`No API key configured for provider "deepseek". Server started in setup mode — configure via desktop Settings or 'rivet config setup'`。
也就是说：**服务能起，但还不能真正对话**。Wave 3 必须先把 provider 配置这条路解决掉，
否则做出来的界面打开就是"未配置"。

### provider 配置：踩坑与做法（A 方案 · 配置随包携带）

服务能跑不等于能用。根因不在服务，在配置 —— rootfs 是从 `ubuntu:24.04` 基础层重建的，
里面没有任何配置；而容器的 proot 只 bind 了 `com.termux`、够不到 App 的私有目录，
Termux 侧又受 Android 沙箱限制，**外部注入不了**，只能由 App 自己铺进去。

**踩到的坑：只拷三个文件不够。** `secrets.json` 是**密文**，解密走
`cipherFor()` → `createSecretCipher()`；Linux/Android 上 `resolveSecretBackend()`
返回 `local-key`，密钥来自 **`~/.rivet/.token-key`**。少了它，harness 会**新生成一个随机 key**
（`localKey()` 的兜底分支），旧密文就永远解不开 —— 仍然停在 setup mode。

**红灯判据**（`host/configcheck.sh`）：预置配置后起 serve，`/health`（带 token）应报 `configured:true`。

| | `configured` | `ok` | serve.log |
|---|---|---|---|
| 改动前（rootfs 里无配置） | `false` | `false` | 有 setup mode 警告 |
| 只补三个 json | `false` | `false` | 仍有 setup mode 警告 |
| **补上 `.token-key`** | **`true`** | **`true`** | 警告消失 |

**App 侧**：`ConfigInstaller` 在每次启动、起 proot **之前**把配置铺进 `<rootfs>/root/.rivet/`，
权限 0600、覆盖写（改了配置重装即生效）。`build.sh` 从 `~/.rivet` 取配置进 assets。

> ⚠ **这个 APK 含 API key**（`assets/tianshu-config/`），**只能自用，不可分发**。
> 该目录已进 `.gitignore`，构建产物也标了警告。
>
> 另注：aapt2 会丢弃 assets 里以 `.` 开头的文件，所以 `.token-key` 在包里名为 `token-key`，
> 由 `MainActivity.installConfigFromAssets()` 注入时还原。

### 真机第五轮：解密失败 —— 目标名写错了（已修）

装上去之后 `configured` 仍是 `false`，而 serve.log 多出一行关键信息：

```
[secure-store] 凭据解密失败（密文被改 / 换了机器 / 密钥丢失）→ 视为未登录，请重新登录
```

**这条比 "文件不存在" 有价值得多**：它说明 `secrets.json` **确实注进去了**，
但 `.token-key` 没生效 —— 否则 harness 根本读不到密文，不会走到解密这一步。

回读自己的代码，找到根因：

```java
public String[] names() { return am.list("tianshu-config"); }   // ← 返回的是**包内真名**
...
File target = new File(rivetHome, name);                        // ← 却拿它当**目标名**用
```

`am.list()` 给出的是包内条目名（`.token-key` 在包里叫 `token-key`），
于是写成了 `~/.rivet/token-key` —— **少了一个点**，harness 找的是 `.token-key`，
找不到就按 `localKey()` 的兜底分支新生成一个随机 key，旧密文永远解不开。

**顺带实测证实了那个绕行的前提**（此前只是假设）：

```
assets/probe/.dotname  → 不进 APK
assets/probe/plainname → 进
```

**修法**：把「目标名 vs 包内名」变成显式、可测的规则 ——
`ConfigInstaller.CONFIG_FILES` 给出目标名清单，`assetNameFor()` 给出映射；
`Source.names()` 的契约明确为**目标名**，实现方负责在 `open()` 里走映射。
新增 7 条断言（含"清单里的 `.token-key` 必须落成带点文件"、"不会多写出无点文件"），
`HostTest` 现 **50/50**。

### 真机第六轮：配置通了

```
--- rootfs 里的 ~/.rivet ---
-rw-------. 1 root root    64 ...  .token-key        ← 带点，这次对了
-rw-------. 1 root root 25739 ...  config.json
-rw-------. 1 root root  1544 ...  provider-keys.json
-rw-------. 1 root root   205 ...  secrets.json

ready=yes  dead=no  after=1s
health(no token)   -> 200 {"ok":true,"version":"3.27.0"}
health(token)      -> 200 {… "readiness":"ready","registryOk":true,"configured":true …}
readyz(token)      -> 200
sessions(no token) -> 401
sessions(token)    -> 200 {"sessions":[]}
```

`.token-key` 带点落地、`configured:true`、`ok` 也翻成 `true`。

**教训**：那个 `ls -la` 一行探针，比前面所有推理都值 ——
上一轮就是"看不到注入结果只能猜"才让写错的名字蒙混过关。

### 真机第七轮：对话页跑通了（Wave 3 · α 达成）

用户在 App 里发「测试一波」，天枢回了完整一段 —— 而且**是真做了实测**：
写了 `.rivet/scratch/smoke.test.mjs`、跑 `node --test`（2 passed / 0 fail / exit 0）、
发现 `/root` 下只有 `.rivet`、查了 node 版本、还自己清理了探针。

**从「能起服务」到「真的能用」，这一步成了。**

路上两个坑，都是真机才暴露的：

**① cleartext 被平台拦**（第一轮截图）

```
java.io.IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted
```

targetSdk >= 28 时 Android 默认禁明文流量，连自己起的 loopback 都拦。
本想用 `networkSecurityConfig` **只对 loopback 放开**（那才精准），但：
该属性是 **API 24** 引入的，而本机构建链只有 `android-sdk-platform-23`
（实测 android-23 的 `android.jar` 里找不到 `networkSecurityConfig`，
只有 `usesCleartextTraffic`），apt 也不提供更高版本。
于是退回全局开关，把取舍与约束写进 manifest 注释：
App 里只有 `AppRuntime.baseUrl()` 一处网络出口、写死 `127.0.0.1`、流量不出设备。

**② 卡在流里出不来**（第二轮截图 —— 界面看着正常，其实没结束）

界面停在「天枢（流式中）」，底部**没有**收尾统计行。根因在本项目自己的读流循环：

```java
while ((line = br.readLine()) != null) {   // SSE 是长连接
    parser.feed(line, handler);
}
say("天枢： " + st.answer);                 // 永远到不了
```

**服务端发完 `done` 并不关闭连接**（之后还在周期发 `: ping` 心跳），
所以 `readLine()` 永远不返回 null → 读流永不结束 → 最终回答与统计行永不出现，
`busy` 标志也永不释放（**第二句话都发不出去**）。

修法：`ConversationState.isFinished()`（收到 `done` 即真）+
在 handler 里主动 `disconnect()`（立刻跳出阻塞的 `readLine`）+
循环里 `if (st.isFinished()) break` 兜底，
并把「`done` 之后断开导致的读异常」判为正常而非故障。

### 真机第八轮：收尾修复生效，且它在 App 里把自己那套纪律也跑起来了

截图确认：标题栏 `结束: completed`、末轮显示的是 `天枢：`（不再停在「天枢（流式中）」）、
**两轮对话都完整渲染**。上一轮的两个修复都到位。

同一张截图里，App 内的天枢做了这些：

- 自建最小闭环：写文件 → `npm test`（**2 passed / 0 failed**）→ git `init`/`commit`
- **主动注入一个必然失败的测试，确认链路真的会红** —— `✗ intentional RED probe (1 !== 2)`，
  界面上写的理由是「**绿非证明**」
- 自己发现收尾命令里的 `PIPESTATUS` 在 dash 下不支持、脚本中断可能留下未清理的探针，
  **主动回头核实并清理**
- 最后给出「环节 / 命令 / 结果」的验证表

**一处误报的更正**：上一轮它说「npm 和 git 都不在 PATH 里」，这一轮报出
`Node v24.21.0 · npm 11.19.0 · git 2.43.0` —— **git/npm 其实都在**，与 rootfs 实际情况一致。
所以那是模型当时的误判，不是环境缺陷。

> ⚠️ **下面这句已于 2026-10-04 核实为过期，不要再据此补装 Python**：
> ~~唯一真实缺失是 Python（`ubuntu:24.04` 基础层不带，当初也没装）~~
>
> 实测（容器内，用 APK 里那个 proot 起 rootfs）：
> ```
> $ proot -0 -r /root/rootfs-build/rootfs -w /root -b /dev -b /proc -b /sys \
>     /bin/bash -lc 'python3 --version; python3 -c "print(1+1)"'
> /usr/bin/python3
> Python 3.12.3
> 2
> ```
> 且 `rootfs.tar.xz` 里有 **3116 个** python 相关条目（`usr/bin/python3`、
> `usr/bin/python3.12`、`usr/lib/python3/`）。`ubuntu:24.04` 基础层确实不带，
> 但 step1 之后补装过。所以 `/doctor` `/python` 这两条命令在 App 里会正常显示版本，
> **不是缺失**。









## 工作区：让 App 里的天枢看得见用户的代码（机制已验，真机待复验）

背景：App 的 rootfs 是干净重建的，`/root` 下只有 `.rivet/`，App 里的天枢自己都说
「没有可测的对象：没有代码、没有测试套件、没有可运行的目标」。

做法是三件事：

1. **`targetSdkVersion` 33 → 28，加 READ/WRITE_EXTERNAL_STORAGE。**
   Android 11 起 targeting ≥ 30 的 App 走分区存储，这两条权限对非媒体文件不给路径级访问。
   设备上的 Termux 就是 `targetSdkVersion:'28'` + 这两条权限，而它对共享存储读写正常
   —— 走它同一条路，用户只需点一次普通权限对话框，不必去「设置 → 所有文件访问」。
2. **bind 进 rootfs**：共享存储挂到 `/mnt/sdcard` `/sdcard` `/storage/emulated/0`，
   工作区挂到 `/root/workspace`。参数拼装在 `RuntimeBinds.args()`（纯逻辑，9 条断言）。
3. **工作区三级降级**（`RuntimeHost.workspaceHost()`）：共享存储 `tianshu-workspace/`
   → App 自己的 `Android/media/<pkg>/workspace`（**无需任何权限**）→ 私有 `filesDir`。
   为什么不"拿不到就不挂"：proot 对不存在的 host 路径只打一行
   `can't sanitize binding` 警告就照常启动，挂载点缺席是**静默**的。

已实测（容器内，用 APK 里那个 proot，见 `probe-binds.sh`）：

- guest 目标**不必预建**（前提：proot 的临时目录可写、即 glue rootfs 建得出来 —— 见下）——
  proot 靠路径翻译虚拟化，事后 rootfs 磁盘上不会多出目录
- host 路径不存在时 proot **照常启动**，只是该挂载点缺席
- 按 `RuntimeHost.buildArgv()` 的逐项同序参数跑一遍：guest 内 `ls -a /root` → `.rivet` `workspace`；
  `cat /root/workspace/myproj/main.py` → 内容正确
- 出包后的 APK 里 `targetSdkVersion:'28'` + 两条存储权限都在（`aapt2 dump badging`）

### 踩过的坑：proot 的 glue rootfs 会**静默吃掉** bind

真机 boot 日志刷过这些东西：

```
proot error: can't create temporary directory: Permission denied
proot error: can't create glue rootfs
proot warning: sanitizing the guest path (binding) "/storage/emulated/0": ...
```

`PROOT_TMP_DIR` 原来是 `/tmp`（Wave 2 `0fa2131` 写的）—— 那在真机上不可用，proot 因此建不出
glue rootfs，而 glue rootfs 正是它补 guest 路径**缺失的中间目录**用的。补不出来，这条 bind
就被**静默丢掉**（只留一行 warning）。症状不对称，所以难查：`/sdcard`、`/mnt/sdcard` 的父目录
在 rootfs 里都有、照常工作，只有要补两层的 `/storage/emulated/0` 没了 ——
看起来像"真路径不能用"，不像"proot 坏了"。

两道防线，`probe-binds.sh` 的 E1/E2/E3 三组可复现：

1. `PROOT_TMP_DIR` → App 私有目录 `<filesDir>/proot-tmp`（`RuntimeHost.prootTmpDir()`）。
   必须**已存在且可写**：实测 proot 只往里面建子目录，不会自己创建 PROOT_TMP_DIR。
   `TMPDIR` 保持 `/tmp` —— 那是给 **guest 里**跑的东西看的，不能跟着换成宿主路径。
2. `RuntimeHost.prepareGuestMountPoints()` 预建 guest 挂载点的全部中间目录
   （清单来自 `RuntimeBinds.guestDirsToCreate`）—— 让 bind 完全不依赖 glue。
   不挂共享存储时连目录都不建，否则 guest 里会多出三个**空目录**，
   看起来像"共享存储是空的"而不是"没挂上"。

真机结果：用户在对话页问「`/storage/emulated/0/NP/apks` 里有几个安装包」，
App 里的天枢列出 3 个真实包（174 MB / 41.6 MB / 8.2 MB）—— 共享存储读取已确认。

**重装修完的包之后再验一次（2026-10-02 00:26）**：`proot error` **一行都没有**（这一屏同时
暴露了上面那条 glue rootfs 缺陷）；三个挂载点各 **67 项**、内容一致；
`proot tmp (host) … writable=true`；`guest 挂载点预建 8 个目录` 与微探针实测的
`guestDirsToCreate(true)` 八条逐字对上。App 里的天枢自己用 inode 反证了
`/root/workspace` 就是 `/storage/emulated/0/tianshu-workspace`。


## 历史会话（Wave 5）

主界面现在多一个「历史会话」按钮 —— 之前每次打开 App 都是全新会话，看不到之前的对话。

- `GET /sessions` 每条带 `title`，列表页直接可用（完整形状取自 `GET /sessions` 的实测响应）
- **历史只能看、不能续**：`POST /sessions/:id/prompt` / `/messages` / `/resume` 全是 404。
  所以进历史页后输入框是**禁用**的 —— 装一个能打字却不生效的框是骗人
- 重放靠再连一次 `/stream`：先来 `replay_window`（`floorSeq` / `diskFirstSeq` / `diskLastSeq`），
  整段对话从头放一遍，**最后仍会发 `done`** ⇒ 直接复用「收到 done 主动断流」那套逻辑，
  不必为历史模式另造终止条件（不然会重演 Wave 3 卡在「流式中」的 bug）
- 两个**静默**陷阱，都钉了断言：
  1. `createdAt` 是 13 位毫秒，`MiniJson.num`（Integer）装不下会溢出成 null ——
     界面表现是整列时间凭空消失且不报错 ⇒ 补 `MiniJson.longNum`
  2. 切 JSON 对象时若不认字符串边界，`title` 里出现 `}` 就会把对象切短、字段静默取空
     ⇒ `SessionList.matchBrace` 跳过字符串内部（合成用例钉住）

**真机已验（2026-10-02 02:39 / 02:47 / 02:50 三张截图）**：

> ⚠ **下面这组记录描述的是当时的界面**。同日晚些时候已改正「历史会话只读」这个错误结论：现在列表页文案是
> `点一条继续聊，长按改名 / 归档 / 删除`，会话页输入框可用（hint `继续这个话题…`）。
> 「渲染、刷新、相对时间都对」与「lastSeq 来自流且与服务器一致」这两条**仍然成立**。

- 列表页：`共1条 —— 点一条看当时说了什么（只读）` / `刷新` / `2+2等于几 · 刚刚`
  → 之后 `共2条` / `… · 9分钟前` / `检查python3版本 · 7分钟前`。
  即**渲染、刷新（1→2）、相对时间（刚刚→9分钟前）**都对；两项都**没显示 `completed`**
  —— 这正是设计行为（只在状态不是 completed 时才追加）。
- 只读重放（**当时的形态，现已改为可续聊**）：点第一条进 `ChatActivity`，屏上是
  `历史会话20261001615e6ecc012d —— 重放当时的对话` / `你: 用一句话回答：2+2等于几？` /
  `天枢: 2 + 2 = 4。` / `[事件20 · lastSeq 26 · 思考139字 · completed]`，
  输入框显示 `历史会话（只读）` 且「发送」是灰的。
  其中 **`lastSeq 26` 与 `GET /sessions` 里该会话的 `lastSeq: 26` 一致** ——
  屏上的数字源自流，且与服务器记录对得上。

> ⚠️ **下面这条已于 2026-10-04 核实为过期**（会话早就不落在私有目录了）：
>
> ~~附带发现：**卸载重装会丢掉全部历史会话** —— 会话落在 rootfs 内的 `~/.rivet/sessions`，
> 而 rootfs 在 App 私有目录。实测旧 4 条会话按 id 直查全 404。APK 升级（不卸载）不受影响。~~
>
> 现状：`RuntimeHost.sessionsHostDir()` 把历史会话的**宿主目录**定在**共享存储**
> `<shared>/tianshu-rivet/sessions`，再由 `RuntimeBinds.sessionArgs()` 以
> `-b <host>:/root/.rivet/sessions` 挂进 guest —— 注释就写着「历史会话活过卸载」。
> 且挂之前先做**写探针**（共享存储是 FUSE，不支持符号链接、chmod 语义弱）：
> 写不进去就**不挂**，宁可退回"会话留在私有目录"，也不让会话连创建都失败。
>
> 所以：会话**不在** App 私有目录里，卸载不丢；用户用文件管理器就能在
> `/sdcard/tianshu-rivet/sessions` 看到它们，备份/换机直接拷这个目录即可。
> 只有共享存储不可用（没授权限）时才会降级回私有目录，那时才适用上面那句老话。

## 续传（Wave 5）

首次解压 557 MB / 12607 个文件。之前中断就得整体重来，现在能接着装。

三条设计要点，都有断言（H 套件 21 条）：

1. **指纹必须在**。标记里记着「这份 tar 的指纹 + 已装到第几个条目」，指纹取自
   `versionCode + asset 字节数`。换了包还接着用旧 staging 会得到**新旧混杂**的 rootfs ——
   比重新装一遍糟得多，而且坏得静默。指纹不符就整体重来（有断言）。
2. **残条目必须先清**。崩溃可能发生在「文件已落盘、但进度还没记」之间。实测最刁的一种：
   符号链接刚建好就崩，续传时重建会直接撞 `EEXIST` 把安装打断
   → `removeStaleEntry()`（只清非目录，免得把已解压的子文件一起带走）。
3. **原子可见这条不变量不能因为加了续传而破**：目标目录仍然只在**全部装完**之后
   才改名出现。中断后 `dest` 不存在、`staging` 留着 —— 这条是 H 套件里带 ★ 的那条断言。

两个顺带的决定：
- 标记文件放在 staging **旁边**，不放里面 —— 放里面的话最后 `renameTo` 会把它带进 rootfs。
- `fingerprint = null`（老调用方）维持原行为：失败就整体重来，不留半成品。

启动日志会把指纹与「从第几个条目接着装」打出来，真机上一眼可判读。

## 三个必须做对的点（前两个是 Gate 探针实测出来的）

1. 可执行体必须来自 `nativeLibraryDir`（Android 10+ 只允许从那里 execve ELF）
2. 必须设 `LD_LIBRARY_PATH=nativeLibraryDir`，否则 `libproot.so` 找不到 `libtalloc.so.2`
3. 必须设 `PROOT_LOADER` 指向打包进来的 loader —— proot 默认去
   `/data/data/com.termux/files/usr/libexec/proot/loader` 找，独立 App 里没这个路径

## 未验证（不要当成已完成）

- **App 沙箱内能否真的建出符号链接**：`AndroidSink` 用 `Os.symlink`（API 21+），
  而 rootfs 全靠符号链接撑着。容器内的预验证用的是 `java.nio`，
  **真机的 SELinux / untrusted_app 域行为没验过**
- 首次解压 557 MB 的耗时与稳定性（手机上可能十几分钟）**未实测**
- **断点续传没做**：中断后 `rootfs.partial` 会被整体丢弃重来，
  只保证"不会把半个 rootfs 当好的"
- 4 步流程里"试 serve"那步的输出形状未在真机确认
- **真机已确认（2026-10-02 00:26，重装新包后）**：boot 第 3 步三行挂载点各 **67 项**、
  内容一致（`Alarms Android BaiduNetdisk`），全屏**没有一行 `proot error`**；
  `proot tmp (host) … writable=true`；`guest 挂载点预建 8 个目录`（与微探针实测的
  `guestDirsToCreate(true)` 8 条逐字一致）。App 里的天枢随后自己用 inode 反证了
  `/root/workspace` 与 `/storage/emulated/0/tianshu-workspace` 是同一个目录。
- 共享存储**不支持符号链接**：实测 `ln -s` → `Permission denied`（FUSE 语义），
  所以"把项目软链进工作区"这条走不通，只能 `cp -r` 或移动
- 工作区落在共享存储时，App 里天枢**写**文件会不会撞到 FUSE 的权限边界（读已实测，写未测）
- ~~在 `/root/workspace` 之外写文件会不会卡在 harness 的审批上~~ —— **已实测：不会**。
  容器可以直接驱动 App 的 serve API（`127.0.0.1:18799`，token `host_local`），
  实测在 cwd 之外 `write_file` 成功、`pendingApprovals: 0`、SSE 无任何审批事件。
