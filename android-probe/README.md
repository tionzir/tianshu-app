# 手机上构建 APK —— 环境事实与坑

全部为本机（Android / aarch64 / proot Ubuntu 24.04）实测。

## 结论先说

**Android 手机上可以完整构建可安装 APK，不需要电脑。** 实测产出 `gate-probe.apk`
144063 B，v1/v2/v3 签名全过，`aapt2 dump badging` 正常。

## 工具链清单（全部 apt 可装）

```bash
apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless android-sdk-build-tools apksigner zipalign android-sdk-platform-23
```

| 工具 | 来源 | 实测 |
|---|---|---|
| `aapt2` | android-sdk-build-tools | `Android Asset Packaging Tool (aapt) 2.19-debian` ✅ arm64 原生 |
| `aapt` | 同上 | ✅ |
| `aidl` / `zipalign` / `apksigner` | 同上 | ✅ |
| `android.jar` | android-sdk-platform-23 | `/usr/lib/android-sdk/platforms/android-23/android.jar`（API 23） |
| `javac` / `java` / `keytool` | openjdk-17 | ✅ |
| **D8（dexer）** | **需自己下**，见下 | ✅ |

## 坑 1：D8 不在 apt 里，要去 Google Maven 拿 r8.jar

`android-sdk-build-tools` 的 deb 包**不含** d8/dx。但 `d8` 是**纯 Java** 程序，
Google Maven 的 `com.android.tools:r8` 里就有 `com.android.tools.r8.D8`。

```bash
# maven.google.com 在本机不可达（HTTP 000），走镜像：
curl -L -o /tmp/r8.jar \
  https://maven.aliyun.com/repository/google/com/android/tools/r8/8.5.35/r8-8.5.35.jar
# 验证：unzip -l /tmp/r8.jar | grep com/android/tools/r8/D8.class
```

实测：阿里云镜像 200 / 16745967 B；腾讯云镜像也可达；`repo1.maven.org` 404。

## 坑 2：Gradle / AGP 在这台机器上是死路

- Ubuntu arm64 的 `gradle` 是 **4.4.1**（2017 年），配 AGP 3.1 才勉强；而 AGP 3.x **要求 Java 8**，本机只有 JDK 17 → 走不通
- 现代 AGP（8.x）要求 build-tools 34+，而 arm64 没有该包
- 所以：**手搓 aapt2 → javac → D8 → zipalign → apksigner**，每一步可单独验证，反而更可控

## 坑 3：javac 默认 US-ASCII，中文源码直接炸

```
error: unmappable character (0xE5) for encoding US-ASCII
```
必须加 `-encoding UTF-8`。

## 坑 4：aapt2 link 会把资源包当 overlay

```
error: resource string/app_name does not override an existing resource
```
加 `--auto-add-overlay`。

## 坑 5（最重要）：Termux 的 proot **不是静态的**

```
readelf -d proot → NEEDED: libtalloc.so.2, libandroid-shmem.so, libc.so(bionic)
```

proot 必须跑在 bionic 上（它负责把 glibc rootfs 映射进来），所以它**不能**从 glibc 容器里搬，
必须用 Termux 那份，并且**连它的依赖一起打包**：

| 文件 | 大小 | 说明 |
|---|---|---|
| `proot` | 247408 B | 重命名为 `libproot.so` |
| `libexec/proot/loader` | 18136 B | 同上 |
| `libtalloc.so.2` | 31440 B | **符号链接，须 `cp -L` 解引用** |
| `libandroid-shmem.so` | 14432 B | |

合计约 311 KB。加上 rootfs 与 node，才是完整的体积账。

## 坑 6：本机无法自装 APK

```
pm list packages  →  Error: Shell does not have permission to access user 666
                     package:com.termux        （只看得见自己）
```
Termux 的 `pm`/`cmd`/`am` wrapper 通不到系统包服务；`/system/bin/su` 不存在（无 root）。
**所以真机门槛必须由人手动点开 APK 安装**，App 把结果画在屏幕上自证。

## exec 门槛的应对

Android 10+ 禁止从 app 可写数据目录 `execve`。本探针把可执行文件放进
`jniLibs/arm64-v8a/lib*.so` 并设 `android:extractNativeLibs="true"`，
运行时从 `applicationInfo.nativeLibraryDir` 取路径执行 —— 这是通行做法，
但**是否真的放行必须真机验证**（探针 APK 就是干这个的）。

## 复现

```bash
cd android-probe
sh build.sh            # 产出 build/gate-probe.apk
```
