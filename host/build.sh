#!/bin/sh
# 天枢独立 App —— 最小宿主的出包脚本（本机 aarch64，不依赖 Gradle/AGP）。
#
# 与 android-probe/build.sh 同一条手搓链：aapt2 → javac → D8 → zipalign → apksigner。
# 多出来的两件：把 xz 库一起打进 dex；把 89MB 的 rootfs.tar.xz 作为 assets 打进去。
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
R8_JAR=${R8_JAR:-/tmp/r8.jar}
TERMUX_USR=/data/data/com.termux/files/usr
XZ_JAR="$HERE/libs/xz-1.10.jar"
ROOTFS_TAR=${ROOTFS_TAR:-/root/rootfs-build/rootfs.tar.xz}
OUT=build

# 签名密钥**必须放在 $OUT 之外**。第 0 步会 rm -rf "$OUT"，密钥若放在里面，
# 每次构建都会重新生成一把 —— 而签名一变，Android 就拒绝覆盖安装
# （报 "App not installed"/签名冲突），只能先卸载；卸载又会连同 App 私有目录里的
# rootfs（518 MB）与 `~/.rivet/sessions` 全部会话历史一起抹掉。
# 放在 host/.keystore/ 下：构建多少次都是同一把密钥，从此不必卸载。
KEYSTORE=${KEYSTORE:-$HERE/.keystore/tianshu-host.keystore}

# 版本号：**versionName 与 versionCode 分开盯**（用户 2026-10-06：「把应用版本改为 2.8.0」）。
#
#   - versionName = 用户看得见的"应用版本"（设置页「运行状态」显示的就是它）。
#   - versionCode **必须单调递增**，否则覆盖安装会被系统拒（INSTALL_FAILED_VERSION_DOWNGRADE），
#     只能先卸载 —— 而卸载会连着 App 私有目录里的 rootfs 与全部会话历史一起抹掉（见坑 16/17/28）。
#     所以它继续用 epoch 秒（设备上现有的是 1791253731），**别改成 20800 这种"看着像版本号"的固定值**：
#     那一定低于设备上已装的，装不上。构建时间也仍能从它反推，所以不再另存 BUILD_TS。
#     （HostTest 的 `testBuildIdentity` 盯着这三条。）
BUILD_CODE=$(date +%s)

# 可调试开关：**默认不开**（对外发布版）。开发期要用 `adb run-as` 拖私有目录取证时：
#   DEBUGGABLE=1 sh build.sh
# 为什么做成开关而不是写死在 manifest 里：写死 = 每个下载者的 App 私有目录（rootfs /
# 会话 / 配置）都能被同设备上的 adb 读走 —— 那正是"对外开放"必须堵的口子。
# （HostTest 的 `testReleaseHygiene` 盯着这条。）
DEBUG_MODE=""
[ "${DEBUGGABLE:-0}" = "1" ] && DEBUG_MODE="--debug-mode"

say() { printf '\n\033[1m>>> %s\033[0m\n' "$1"; }

say "0. 前置检查"
for f in "$ANDROID_JAR" "$R8_JAR" "$XZ_JAR" "$ROOTFS_TAR"; do
  [ -f "$f" ] || { echo "  缺文件: $f"; exit 1; }
  echo "  ok  $f"
done
for c in aapt2 javac java zipalign apksigner keytool python3; do
  command -v "$c" >/dev/null || { echo "  缺命令: $c"; exit 1; }
done
echo "  ok  工具链齐全"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/jni/arm64-v8a" "$HERE/assets"

say "1. jniLibs：真 proot 与它的依赖"
cp "$TERMUX_USR/bin/proot"               "$OUT/jni/arm64-v8a/libproot.so"
cp "$TERMUX_USR/libexec/proot/loader"    "$OUT/jni/arm64-v8a/libproot-loader.so"
cp "$TERMUX_USR/lib/libandroid-shmem.so" "$OUT/jni/arm64-v8a/libandroid-shmem.so"
cp -L "$TERMUX_USR/lib/libtalloc.so.2"   "$OUT/jni/arm64-v8a/libtalloc.so.2"
ls -l "$OUT/jni/arm64-v8a/"

say "2. assets：rootfs.tar.xz（硬链接，不复制那 89MB）"
ln -f "$ROOTFS_TAR" "$HERE/assets/rootfs.tar.xz" 2>/dev/null \
  || cp "$ROOTFS_TAR" "$HERE/assets/rootfs.tar.xz"
ls -l "$HERE/assets/rootfs.tar.xz"

say "2b. assets：不再随包携带配置（密钥只该在用户手里）"
# 2026-10-04 改：原先这里把 ~/.rivet 的四件套（config.json / provider-keys.json /
# secrets.json / .token-key）拷进 assets 打进 APK —— 那个包**含 API key，给不了第二个人**，
# 脚本自己都得标一句「只能自用、勿分发」。
# 现在密钥由用户在 App 的「配置天枢」页给：粘贴 key（App 调 harness 自己的
# `rivet config set-key`，加密落盘全由它负责）或从共享存储导入四件套。见 SetupActivity。
#
# ⚠️ 必须**显式删掉**上一次构建留下的 assets/tianshu-config/ —— 第 0 步的
#    `rm -rf build` 只管 build/，管不到 assets/；残留会被 aapt2 照打进包，
#    密钥就又进 APK 了（而且是静默的）。
rm -rf "$HERE/assets/tianshu-config"

say "2c. assets：命令目录（命令面板用，源在 data/，不复制仓库里另一份）"
CMD_OUT="$HERE/assets/tianshu-cmd"
rm -rf "$CMD_OUT"
mkdir -p "$CMD_OUT"
for f in commands.txt components.json; do
  [ -f "$HERE/../data/$f" ] || { echo "  缺数据: $HERE/../data/$f"; exit 1; }
  cp -f "$HERE/../data/$f" "$CMD_OUT/$f"
done
ls -l "$CMD_OUT"

say "3. aapt2 编译资源"
aapt2 compile --dir res -o "$OUT/res.zip"

say "4. aapt2 链接（含 assets；.xz 不再压缩，反正已是压缩格式）"
aapt2 link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  -A "$HERE/assets" \
  --no-compress-regex '\.xz$' \
  --auto-add-overlay \
  --java "$OUT/gen" \
  $DEBUG_MODE \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  --version-code "$BUILD_CODE" --version-name "2.8.0"
echo "  base.apk $(wc -c < "$OUT/base.apk") B"

say "5. javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | sed 's/^/  java 源文件: /'
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$XZ_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" || { echo "  ✗ javac 失败"; exit 1; }
find "$OUT/classes" -name '*.class' | wc -l | sed 's/^/  class 文件: /'
[ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] || { echo "  ✗ 没产出 class"; exit 1; }

say "6. D8 打 dex（本工程 classes + xz 库）"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release --min-api 21 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$XZ_JAR"
ls -l "$OUT/dex/"

say "7. 组装 APK：dex + jniLibs"
python3 - "$OUT/base.apk" "$OUT/dex" "$OUT/jni/arm64-v8a" <<'PY'
import sys, os, zipfile
apk, dexdir, jnidir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for n in sorted(os.listdir(dexdir)):
        if n.endswith('.dex'):
            z.write(os.path.join(dexdir, n), n)
            print('   +', n, os.path.getsize(os.path.join(dexdir, n)), 'B')
    for n in sorted(os.listdir(jnidir)):
        p = os.path.join(jnidir, n)
        z.write(p, 'lib/arm64-v8a/' + n)
        print('   + lib/arm64-v8a/' + n, os.path.getsize(p), 'B')
PY

say "8. zipalign"
zipalign -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

say "9. 签名（密钥在 $KEYSTORE —— build/ 之外，跨构建稳定）"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "  ⚠ 首次：生成新的签名密钥。"
  echo "    这台设备上已装的 App 若由旧密钥签的，需要**再卸载一次**；"
  echo "    此后这把密钥一直复用，后续构建都能直接覆盖安装（不会再丢 rootfs 与会话历史）。"
  keytool -genkeypair -keystore "$KEYSTORE" -alias tianshu \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Tianshu Host, OU=Dev, O=Tianshu, L=NA, ST=NA, C=CN" >/dev/null 2>&1
else
  echo "  ok  复用已有密钥 —— 签名与上次构建一致，可直接覆盖安装"
fi
apksigner sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/tianshu-host.apk" "$OUT/aligned.apk"

say "10. 验证产物"
apksigner verify --verbose "$OUT/tianshu-host.apk" | head -8
# 把这行指纹打出来：下一次构建若与它不一致，就说明密钥又变了 —— 那正是"必须卸载才能装"的成因
echo "  --- 签名指纹（下次构建应与此一致）---"
apksigner verify --print-certs "$OUT/tianshu-host.apk" | grep "SHA-256 digest"
echo
echo "  产物: $HERE/$OUT/tianshu-host.apk  ($(wc -c < "$OUT/tianshu-host.apk") B)"

say "11. 复制到共享存储 Download"
DST=/storage/emulated/0/Download
if cp -f "$OUT/tianshu-host.apk" "$DST/tianshu-host.apk" 2>/dev/null; then
  echo "  ✓ $DST/tianshu-host.apk"
else
  echo "  ✗ 复制失败（共享存储不可写？）"
fi
