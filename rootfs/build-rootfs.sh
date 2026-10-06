#!/bin/sh
# 天枢独立 App —— 运行时 rootfs 预制
#
# 产出：rootfs.tar.xz（App 首次启动时解压到私有目录，proot 以此作 rootfs）
#
# 底子用 proot-distro 缓存里现成的 ubuntu:24.04 OCI layer（28.9 MB），不额外下载。
# 若换设备、缓存不在，改 LAYER 指向本机的 ubuntu:24.04 layer 即可
# （或换成 ubuntu-base-24.04-base-arm64.tar.gz，两者都是干净的 Ubuntu 24.04 树）。
#
# 实测产出（2026-10-01，vivo V2507A / aarch64）：
#   v1：解压后 557 MB · tar.xz 89.2 MB
#       sha256 1e0089cef5ff65bd61299f43b208773ebeaf2effa1519d02560a1bda58870d0c
#   v2（加 python3 + pip + venv）：见脚本末尾自己打出的体积与 sha256
#
# 为什么加 Python：App 里的天枢两次报「无 Python」，而 AGENTS.md 的 /python 工作流
# 依赖 python3 + venv + pip 三件套 —— 只装 python3 不给 pip 是半接的。
set -e

W=${WORK:-/root/rootfs-build}
R="$W/rootfs"
LAYER=${LAYER:-/data/data/com.termux/files/usr/var/lib/proot-distro/cache/oci_layers/sha256_8a38824eedc553ba80cf1eb7df278a003340f7409fd4b9002bce07db8840a9a2}
NODE_MAJOR=24

# 在 rootfs 内执行命令的统一入口：带上 /dev /proc /sys 与 DNS
in_rootfs() {
  proot -0 -r "$R" -w /root \
    -b /dev -b /proc -b /sys \
    -b /etc/resolv.conf:/etc/resolv.conf \
    /bin/bash -lc "export PATH=/usr/local/bin:/usr/bin:/bin; $1"
}

need() { command -v "$1" >/dev/null || { echo "缺命令: $1"; exit 1; }; }
for c in proot tar xz curl python3; do need "$c"; done
[ -f "$LAYER" ] || { echo "找不到 base layer: $LAYER"; exit 1; }

echo "=== 1/5 解开 ubuntu:24.04 base ==="
rm -rf "$R"
mkdir -p "$R"
tar -xzf "$LAYER" -C "$R"
cp /etc/resolv.conf "$R/etc/resolv.conf"

echo "=== 2/5 装运行时依赖（git / ripgrep / curl / ca-certificates / python3）==="
in_rootfs '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends bash coreutils git ripgrep curl ca-certificates \
    python3 python3-pip python3-venv
  apt-get clean
  rm -rf /var/lib/apt/lists/*
  # 装完立刻自证：光看 apt 的 exit code 不够，之前 npm 那次就是"不报错但其实是坏的"
  python3 -V
  python3 -c "import json, sqlite3, ssl, zlib, ctypes; print(\"stdlib ok\")"
  pip3 --version
'

echo "=== 3/5 装 Node $NODE_MAJOR ==="
VER=$(curl -sS https://nodejs.org/dist/index.json \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print([x['version'] for x in d if x['version'].startswith('v${NODE_MAJOR}.')][0])")
TB="node-$VER-linux-arm64.tar.xz"
[ -f "$W/$TB" ] || curl -L -o "$W/$TB" "https://nodejs.org/dist/$VER/$TB"
mkdir -p "$R/usr/local"
tar -xJf "$W/$TB" -C "$R/usr/local" --strip-components=1

echo "=== 4/5 装 tianshu-harness ==="
# ⚠ 必须放行 install scripts：npm 11 默认禁止，会让 better-sqlite3 / esbuild 静默变成坏的
in_rootfs '
  npm i -g tianshu-harness --no-fund --no-audit \
    --allow-scripts=tianshu-harness,@ast-grep/lang-json,@ast-grep/lang-python,better-sqlite3,esbuild
  tianshu --version
  find /usr/local/lib/node_modules/tianshu-harness -name better_sqlite3.node | head -1
'

echo "=== 5/5 清理 + 打包 ==="
rm -rf "$R/tmp"/* "$R/var/tmp"/* 2>/dev/null || true
rm -rf "$R/var/lib/apt/lists"/* 2>/dev/null || true
# npm 的下载缓存：实测 103 MB，里面是已压缩的 tarball，xz 再压不动 —— 几乎原样进包。
# 它只影响重装速度（缺了会重新下载），对运行时毫无用处。这一步能把 tar 砍掉约 50 MB。
rm -rf "$R/root/.npm" "$R/root/.cache" 2>/dev/null || true

# ⚠️ 运行期数据 —— 这一条是"对外发布"必需，别再删掉它：
# `.rivet/` 里是**用起来之后**才有的东西（会话记录、config.json、meridian.db 记忆库、
# 编译缓存、server-info.json）。本脚本从没主动铺过这个目录，所以里面装的全是**开发者自己
# 跑出来的痕迹**；不清就会被原样打进 APK，发给每一个下载者（2026-10-06 实测踩到：
# 5 个开发会话 + config + meridian.db 都在包里，见 `审计.md §27`）。
rm -rf "$R/root/.rivet"
# 同类：开发期跑 serve 会在家目录留 `serve328.log` 这样的日志；shell 历史同理。
rm -f "$R/root/.bash_history" 2>/dev/null || true
rm -f "$R/root/"*.log 2>/dev/null || true
# Node 的头文件（`/usr/local/include/node/**`，几 MB）：只有 `node-gyp` **现场编译**原生模块
# 时才用得到。harness 的原生依赖（better-sqlite3 / esbuild）走预编译分发，运行时不需要它；
# 上一版 328 包里也没有。留着只是白占体积，清掉。
rm -rf "$R/usr/local/include"
rm -f "$R/etc/resolv.conf"
cd "$R"
rm -f "$W/rootfs.tar.xz"
tar --numeric-owner -cJf "$W/rootfs.tar.xz" .
echo
echo "解压后体积: $(du -sh "$R" | cut -f1)"
echo "包体积:     $(wc -c < "$W/rootfs.tar.xz") B"
sha256sum "$W/rootfs.tar.xz"

echo
echo "自检：产物里不许有个人数据…"
if tar -tJf "$W/rootfs.tar.xz" | grep -qE "^\./root/(\.rivet|\.bash_history)"; then
  echo "  ✗ 打回 —— rootfs 里仍有 .rivet / .bash_history，不许对外发布"
  exit 1
fi
echo "  ok  干净（无 .rivet / 无 shell 历史）"
