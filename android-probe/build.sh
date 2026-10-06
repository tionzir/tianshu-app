#!/bin/sh
# 天枢独立 App —— 在 Android（aarch64）本机出包，不依赖 Gradle/AGP。
#
# 为什么手搓构建链：Ubuntu arm64 有 aapt2/javac/apksigner/zipalign，但没有现代 Gradle，
# 而 AGP 3.x 又要 Java 8（本机只有 JDK 17）。手搓 aapt2 → javac → D8 → zipalign → apksigner
# 反而更可控，且每一步都能单独验证。
#
# 依赖（apt 装）：openjdk-17-jdk-headless android-sdk-build-tools apksigner zipalign
#                 android-sdk-platform-23
# 外加：/tmp/r8.jar（Google Maven 的 com.android.tools:r8，纯 Java 的 D8）
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
R8_JAR=${R8_JAR:-/tmp/r8.jar}
TERMUX_USR=/data/data/com.termux/files/usr
OUT=build
PKG=dev.tianshu.gateprobe

# 构建标识 —— 让「装的是哪一版」能在 App 界面上自证（探针头部会打印它）
BUILD_TS=$(date +%m%d-%H%M)
BUILD_CODE=$(date +%s)

say() { printf '\n\033[1m>>> %s\033[0m\n' "$1"; }

say "0. 前置检查"
for f in "$ANDROID_JAR" "$R8_JAR"; do
  [ -f "$f" ] || { echo "缺文件: $f"; exit 1; }
  echo "  ok  $f"
done
for c in aapt2 javac java zipalign apksigner keytool; do
  command -v "$c" >/dev/null || { echo "缺命令: $c"; exit 1; }
  echo "  ok  $c -> $(command -v $c)"
done

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/jni/arm64-v8a"

say "1. 准备 jniLibs：把真 proot 与它的依赖装进 nativeLibraryDir"
# Android 10+ 只允许从 nativeLibraryDir 执行 ELF，所以可执行文件必须以 lib*.so 命名
cp "$TERMUX_USR/bin/proot"                       "$OUT/jni/arm64-v8a/libproot.so"
cp "$TERMUX_USR/libexec/proot/loader"            "$OUT/jni/arm64-v8a/libproot-loader.so"
cp "$TERMUX_USR/lib/libandroid-shmem.so"         "$OUT/jni/arm64-v8a/libandroid-shmem.so"
# libtalloc.so.2 是符号链接，要解引用拷实体
cp -L "$TERMUX_USR/lib/libtalloc.so.2"           "$OUT/jni/arm64-v8a/libtalloc.so.2"
ls -l "$OUT/jni/arm64-v8a/"

say "2. aapt2 编译资源"
aapt2 compile --dir res -o "$OUT/res.zip"

say "3. aapt2 链接：清单 + 资源 → 基础 APK"
aapt2 link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 33 \
  --version-code "$BUILD_CODE" --version-name "0.1-$BUILD_TS"
echo "  base.apk $(wc -c < $OUT/base.apk) B"

say "4. javac 编译 Java（同时编 aapt2 生成的 R.java）"
find java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | sed 's/^/  java 源文件: /'
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" || { echo "  ✗ javac 失败"; exit 1; }
find "$OUT/classes" -name '*.class' | wc -l | sed 's/^/  class 文件: /'
# 没有 class 文件就是失败（防止管道吞掉退出码）
[ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] || { echo "  ✗ 没产出 class"; exit 1; }

say "5. D8 打 dex（纯 Java，能在 arm64 跑）"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release --min-api 21 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
ls -l "$OUT/dex/"

say "6. 把 classes.dex 与 jniLibs 塞进 APK"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/jni/arm64-v8a" <<'PY'
import sys, zipfile, os
apk, dex, jnidir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    for n in sorted(os.listdir(jnidir)):
        p = os.path.join(jnidir, n)
        z.write(p, 'lib/arm64-v8a/' + n)
        print('   + lib/arm64-v8a/' + n, os.path.getsize(p), 'B')
PY

say "7. zipalign（必须在签名前）"
zipalign -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

say "8. 生成调试签名并签名"
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" -alias tianshu \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Tianshu Probe, OU=Dev, O=Tianshu, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks "$OUT/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/gate-probe.apk" "$OUT/aligned.apk"

say "9. 验证产物"
apksigner verify --verbose "$OUT/gate-probe.apk" | head -12
echo
echo "  产物: $HERE/$OUT/gate-probe.apk  ($(wc -c < $OUT/gate-probe.apk) B)"

# 同步到「跨界交换区」：/data/data/com.termux/files/home 在 proot 容器与 Termux
# 两个文件视图里是同一个物理路径，install-apk.sh 就从这里取包。
# 之前 install-apk.sh 读到的是手工留下的旧副本，导致「装了旧包却以为是最新」。
say "10. 同步到跨界交换区（Termux home）"
SWAP=/data/data/com.termux/files/home/gate-probe.apk
cp -f "$OUT/gate-probe.apk" "$SWAP"
echo "  ✓ $SWAP  ($(wc -c < "$SWAP") B)"
echo
echo "  安装: sh $HERE/install-apk.sh   （本机无 Android root，pm install 不可用）"
