#!/bin/sh
# rootfs 瘦身打包 —— 把 rootfs 打成**更小**的 rootfs.tar.xz。
#
# 为什么单独一个脚本：`rootfs/build-rootfs.sh` 的 step5 是 `tar -cJf`（xz -6、什么都不排除）。
# 2026-10-04 用户要求「安装包变小一点」，于是有了这份：
#   ① 排除**运行时用不到**的东西：Node C++ 头文件（67M）、usr/share/{doc,man,locale}、var/{cache,log}；
#   ② 把 node 二进制 `strip` 一遍（122.9M → 102.9M，实测 `node --version` 仍正常输出 v24.21.0）；
#   ③ 用 `xz -9e`（比默认 -6 更小）。
# 实测：rootfs.tar.xz 107,969,640 → 99,731,328 B（−8.24 MB / −7.6%）；APK 108,315,872 → 100,078,816 B。
#
# 用法：
#   sh rootfs/pack-lean.sh [rootfs目录] [输出文件]
#   默认：/root/rootfs-build/rootfs → /root/rootfs-build/rootfs-lean.tar.xz
# 打完后把它拷成 assets：
#   cp /root/rootfs-build/rootfs-lean.tar.xz host/assets/rootfs.tar.xz && sh host/build.sh
#
# ⚠️ 三条前提，别踩：
#   1. **只排除、不删原文件** —— 原树保持完整，出问题重跑一遍即可，不必重建 rootfs。
#   2. **别用 `cp` 覆盖 `host/assets/rootfs.tar.xz` 之前先想清楚** —— 那份文件与
#      `/root/rootfs-build/rootfs.tar.xz` 曾经是**同一个 inode 的硬链接**，`cp` 是就地截断重写，
#      会把另一条路径也一起改掉（本脚本的作者 2026-10-04 就因此把原始档覆盖没了，
#      要留原始档就先 `cp` 成另一个**新**文件名。
#   3. **已装过的设备不会重装 rootfs** —— `RootfsInstaller` 的 `if (destDir.isDirectory()) return;`
#      是幂等跳过（RootfsInstaller.java:109）。所以瘦身只对**全新安装**生效 + 让 APK 变小；
#      老设备要真瘦下来得卸载重装，那会**连带丢掉全部会话历史**——别擅自做。
set -e

R=${1:-/root/rootfs-build/rootfs}
OUT=${2:-/root/rootfs-build/rootfs-lean.tar.xz}
# 中间 tar 放**磁盘**上，别放 /tmp（tmpfs 是内存，530MB 会压爆内存）
TMPTAR=${TMPTAR:-/root/rootfs-build/.tree.tmp.tar}
STAGE=$(mktemp -d)

echo "--- rootfs : $R"
echo "--- 输出   : $OUT"

# ① 准备一份 strip 过的 node
mkdir -p "$STAGE/usr/local/bin"
cp "$R/usr/local/bin/node" "$STAGE/usr/local/bin/node"
if command -v strip >/dev/null 2>&1; then
  strip "$STAGE/usr/local/bin/node" || echo "  (strip 失败，退回未 strip 的 node)"
else
  echo "  (没有 strip，用未 strip 的 node)"
fi
echo "--- node   : $(ls -l "$STAGE/usr/local/bin/node" | awk '{print $5}') 字节"

# ② 主树：打包，排除运行时用不到的东西 + node（node 用上面那份替换）
( cd "$R" && tar --numeric-owner \
    --exclude='./usr/local/include' \
    --exclude='./usr/share/doc' \
    --exclude='./usr/share/man' \
    --exclude='./usr/share/locale' \
    --exclude='./var/cache' \
    --exclude='./var/log' \
    --exclude='./usr/local/bin/node' \
    -cf "$TMPTAR" . )
# ③ 把 strip 过的 node 追加进去（路径与原来一致；解包时目录已存在）
tar --numeric-owner -rf "$TMPTAR" -C "$STAGE" ./usr/local/bin/node

echo "--- 压缩（xz -9e，比较慢）---"
xz -9e -T0 -c "$TMPTAR" > "$OUT"

rm -rf "$STAGE" "$TMPTAR"
ls -l "$OUT"
sha256sum "$OUT"
echo "[pack-lean done]"
