# 贡献指南

感谢你对天枢 App 感兴趣。

## 环境

- **Node.js ≥ 20** —— 跑离线靶子（`npm test`）。
- 想构建可安装的 APK：Android + Termux 手搓链，完整环境事实与坑见 [`android-probe/README.md`](android-probe/README.md)。

## 开发流程

1. Fork 并开分支。
2. 改动后跑离线靶子（4 个不变量 / 41 条断言，无需服务）：

   ```bash
   npm test
   ```

3. 若改了宿主逻辑（`host/`），再跑 `HostTest`（纯逻辑，编译期需 `android.jar`）：

   ```bash
   cd host
   javac -encoding UTF-8 \
     -cp "/usr/lib/android-sdk/platforms/android-23/android.jar:libs/xz-1.10.jar" \
     -d /tmp/t src/dev/tianshu/host/*.java test/dev/tianshu/host/*.java
   LC_ALL=C.UTF-8 java -cp "/tmp/t:libs/xz-1.10.jar" dev.tianshu.host.HostTest
   ```

4. 提交 PR，说明**改了什么 / 为什么 / 怎么验的**。

## 约定

- **界面纪律是可断言的测试**：答案置底、换外观不改结构、UI 组件与 `/` 命令双向覆盖、WCAG 对比度护栏。动界面之前先读 `test/all.test.mjs` —— 你的改动很可能已被某条不变量覆盖。
- **不写死宿主机绝对路径**：脚本里用 `$(dirname "$0")` 推导，别硬编码 `/root/...`。
- **新接的官方路由要有夹具**：抓一份真实响应放进 `host/test/fixtures/api/`，让界面里长不出"打过去 404"的按钮。抓完**脱敏**再提交。
- **密钥绝不入库**：它们在 `host/.keystore/`、`host/assets/tianshu-config/`（均已被 `.gitignore` 挡住）。

## 许可

贡献即代表同意以 [Apache-2.0](LICENSE) 授权。
