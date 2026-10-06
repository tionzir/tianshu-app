#!/bin/sh
# 探针：宿主要给 rootfs 加的 -b（共享存储 bind）到底怎么行为。
#
# 用 APK 里打进去的那个 proot 实测，不靠推断。回答三个问题：
#   A. -b <host>:<guest>，guest 目标目录**已存在**时，ls guest 能不能看到 host 的内容？
#   B. guest 目标目录**不存在**时，proot 是自动建、还是报错、还是静默失效？
#   C. 多层 guest 路径（/root/workspace）与顶层路径（/mnt/sdcard）行为是否一致？
#
# 场景与真机一致的地方：同一个 proot 二进制、同一份 rootfs 发行版、同样的 -b 语法。
# 不一致的地方：这里的 uid 有 Termux 的存储权限，真机上的 App 要靠自己的权限
# —— 权限那一层这个探针测不到，只能真机截图看。
set -e

APK="$(dirname "$0")/build/tianshu-host.apk"
ROOTFS=/root/rootfs-build/rootfs
W=/tmp/bindprobe

for f in "$APK" "$ROOTFS"; do
  [ -e "$f" ] || { echo "缺 $f"; exit 1; }
done

rm -rf "$W"
mkdir -p "$W/lib" "$W/fake-sdcard/proj"
echo "hello from shared storage" > "$W/fake-sdcard/proj/a.txt"
mkdir -p "$W/fake-sdcard/proj/sub"
echo "nested" > "$W/fake-sdcard/proj/sub/b.txt"

python3 - "$APK" "$W/lib" <<'PY'
import sys, zipfile, os
apk, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk)
for n in z.namelist():
    if n.startswith('lib/arm64-v8a/'):
        p = os.path.join(out, os.path.basename(n))
        with open(p, 'wb') as f:
            f.write(z.read(n))
        os.chmod(p, 0o755)
print('解出', sorted(os.listdir(out)))
PY

run() {
  # $1 = 说明；其余 = proot 参数
  desc=$1; shift
  printf '\n=== %s ===\n' "$desc"
  LD_LIBRARY_PATH="$W/lib" PROOT_LOADER="$W/lib/libproot-loader.so" \
    timeout 60 "$W/lib/libproot.so" --kill-on-exit -0 -r "$ROOTFS" -w /root \
    -b /dev -b /proc -b /sys "$@" \
    /bin/bash -lc 'echo "  rootfs 里看到的:"; ls -la /root/workspace /mnt/sdcard 2>&1 | sed "s/^/    /"' \
    || echo "  [proot exit=$?]"
}

# 造 guest 目标（模拟 App 启动时 Java 侧 mkdirs 这一步）
mkdir -p "$ROOTFS/mnt/sdcard" "$ROOTFS/root/workspace"

run "A. guest 目标已存在 —— /mnt/sdcard 与 /root/workspace 都预建" \
  -b "$W/fake-sdcard:/mnt/sdcard" \
  -b "$W/fake-sdcard:/root/workspace"

# 清掉 guest 目标，验证 proot 会不会自己建
rmdir "$ROOTFS/mnt/sdcard" "$ROOTFS/root/workspace" 2>/dev/null || true

run "B. guest 目标不存在 —— proot 是否自动创建？" \
  -b "$W/fake-sdcard:/mnt/sdcard" \
  -b "$W/fake-sdcard:/root/workspace"

# E. glue rootfs —— PROOT_TMP_DIR 不可写时会发生什么（真机 2026-10-02 踩过）
#
# proot 要在自己的临时目录里补出 guest 路径缺失的中间目录（"glue rootfs"）。
# 补不出来时，这条 bind 会被**静默丢掉**，只留一行 warning。后果不对称，所以难查：
# 父目录已存在的 /sdcard、/mnt/sdcard 照常工作，只有要补两层的 /storage/emulated/0 没了
# —— 看起来像"真路径不能用"，不像"proot 坏了"。
# 真机原文：`can't create temporary directory: Permission denied` + `can't create glue rootfs`
#          + `sanitizing the guest path (binding) "/storage/emulated/0": No such file or directory`
run_tmp() {
  desc=$1; tmpdir=$2
  printf '\n=== %s ===\n' "$desc"
  LD_LIBRARY_PATH="$W/lib" PROOT_LOADER="$W/lib/libproot-loader.so" PROOT_TMP_DIR="$tmpdir" \
    timeout 60 "$W/lib/libproot.so" --kill-on-exit -0 -r "$ROOTFS" -w /root \
    -b /dev -b /proc -b /sys \
    -b "$W/fake-sdcard:/mnt/sdcard" -b "$W/fake-sdcard:/sdcard" -b "$W/fake-sdcard:/storage/emulated/0" \
    /bin/bash -lc 'for d in /sdcard /mnt/sdcard /storage/emulated/0; do echo -n "    $d -> "; ls "$d" 2>&1 | head -1; done' 2>&1 \
    | grep -E '^    /|proot (error|warning)' | sed 's/^/  /'
}

run_tmp "E1. PROOT_TMP_DIR 不可写（= 真机状况：/tmp 建不出来）" /proc/nonexistent-probe
mkdir -p "$W/tmp"
run_tmp "E2. PROOT_TMP_DIR 存在且可写（修根因：改为 App 私有目录）" "$W/tmp"

echo
echo "  E3. PROOT_TMP_DIR 仍不可写，但把 guest 中间目录预建进 rootfs（修兜底）"
mkdir -p "$ROOTFS/storage/emulated" "$ROOTFS/sdcard" "$ROOTFS/mnt/sdcard"
run_tmp "    预建之后" /proc/nonexistent-probe
rmdir "$ROOTFS/storage/emulated" "$ROOTFS/storage" "$ROOTFS/sdcard" "$ROOTFS/mnt/sdcard" 2>/dev/null || true

echo
echo "[probe-binds done] guest 目标现状:"
ls -ld "$ROOTFS/mnt/sdcard" "$ROOTFS/root/workspace" 2>&1 | sed 's/^/  /'
