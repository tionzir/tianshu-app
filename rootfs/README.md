# 运行时 rootfs（Wave 2）

App 自包含的关键一件：一个能跑 `rivet serve` 的 Linux 用户态。App 首次启动把它解压到私有目录，
用 `jniLibs` 里的 proot 以它为 `--rootfs` 启动，容器里跑 `tianshu serve`。

## 产出

| | |
|---|---|
| 文件 | `rootfs.tar.xz`（未入库，103 MB 太大；用 `build-rootfs.sh` 重建或见下「存放位置」） |
| 解压后 | **624 MB** |
| 包体积 | **107,969,640 B（103 MB）** |
| SHA256 | `3b0292cbe5fc1c93e2f9655a227f5be7435b7cd5769e5dd1328d4270727d19c0` |
| 构建环境 | vivo V2507A · aarch64 · 2026-10-02（v2，含 Python） |
| 存放位置（构建机） | `/root/rootfs-build/rootfs.tar.xz` |

> v1（不含 Python）是 557 MB / 89.2 MB。v2 加 Python 的**净增量只有 14.4 MB** ——
> 中间那个 165 MB 的版本是踩了坑：`/root/.npm` 缓存 103 MB 被一起打进包。
> 详见下面「三条实测教训」第 3 条。

## 内容

- **底子**：`ubuntu:24.04` 官方镜像层（aarch64），直接取自 proot-distro 的 OCI 缓存，未额外下载
- **运行时依赖**：`bash` `coreutils` `git 2.43.0` `ripgrep 14.1.0` `curl 8.5.0` `ca-certificates`
- **Python**：`python3` + `python3-pip` + `python3-venv`（v2 加的）。
  为什么是三件套而不是只有一个 `python3`：`AGENTS.md` 的 `/python` 工作流要 venv 与 pip，
  只给解释器等于半接 —— 装了却装不了包。
  脚本在第 2 步末尾会**自证**（`python3 -V` / `import sqlite3,ssl,zlib` / `pip3 --version`），
  因为"apt exit=0 但其实没装好"这种事本工程已经踩过一次（见下面 npm 那条教训）。
- **Node**：`v24.21.0`（官方 glibc aarch64 构建，解到 `/usr/local`）
- **harness**：`tianshu-harness 3.27.0`，全局装在 `/usr/local/lib/node_modules`

**不含**：JDK、Android SDK、`r8.jar`、apt 缓存 —— 那些是构建 APK 用的，跟运行时无关。

## 复现

```bash
sh rootfs/build-rootfs.sh          # → /root/rootfs-build/rootfs.tar.xz
```

## 两条实测教训

1. **npm 11 默认禁止 install scripts** —— 不带 `--allow-scripts` 装出来的 harness
   `--version` 能打出版本号，但 `better-sqlite3` / `esbuild` / `@ast-grep/lang-*` 的
   postinstall 全被静默跳过，原生件是坏的。这类失败**不报错**，只有真跑功能才炸。
   脚本里已固定带上白名单。

2. **Node 版本是硬要求**：`tianshu-harness@3.27.0` 的 `engines.node` 是 `>=24`。
   容器里现成的 v22 **不够用**，必须单独装 v24。

3. **npm 的下载缓存会被打进包** —— `npm i -g` 会在 `$HOME/.npm/_cacache` 留 **103 MB**，
   里面全是**已压缩**的 tarball（xz 再压不动，几乎原样进包）：实测让 tar 从 108 MB 涨到 165 MB，
   净多 57 MB 的 APK 体积，而它对运行时**毫无用处**（缺了只是重装时重新下载）。
   第 5 步的清理已加 `rm -rf "$R/root/.npm" "$R/root/.cache"`。
   教训的普遍形状：**安装器留下的缓存与"我装了什么"无关，收尾时要专门想一遍**
   —— 只看 `apt clean` 是不够的。

   同理还有一处**没清、但可以再省 67 MB**：`usr/local/include`（node 头文件）。
   留着是因为 `node-gyp` 编译原生模块要用；本工程的 harness 走预编译二进制，
   但如果将来要在 App 里 `npm i` 带原生件的包，删了它会当场崩。要不要省这 67 MB 是个取舍，
   现在选择留着。

## 已验证（不是推断）

- `tar -xJf` 还原到**干净目录**后，rootfs 内实跑：
  `node v24.21.0` / `tianshu-harness v3.27.0` / `git 2.43.0` / `ripgrep 14.1.0`，
  且 `better_sqlite3.node` 两处都在
- 在该 rootfs 内**真起了一次服务**：`rivet serve --port 18799 --host 127.0.0.1`
  19 秒就绪；`GET /health` → 200；`GET /sessions` 无 token → **401**；
  带 token → **200 `{"sessions":[]}`**  ← 即 Wave 2 验证要点里那条

## 未验证

- 只测了 `rivet serve` 能否起来与两个 GET 路由，**没跑过一次完整对话往返**（Wave 3 的事）

已解决的两条（原列在此）：

- `/health` 的 `{"ok":false}` —— 已查清：`ok = registryOk && configuredOk`，
  空 rootfs 上 `configured:false`（没配模型 provider），**不是故障**。
  真机上也得到了同样的快照
  （`"readiness":"ready","registryOk":true,"configured":false`）。
- 解压的原子可见与幂等 —— 已在宿主工程实现（`RootfsInstaller`），
  并有断言覆盖；真机第二轮实测"已存在，跳过（幂等）"。
- **断点续传** —— 已做（Wave 5 的 t8/t11 一起）。设计要点见 `host/README.md` 的
  「续传」一节：指纹必须在、残条目必须先清、目标目录仍只在全部装完后才出现。

