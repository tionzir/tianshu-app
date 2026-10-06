#!/bin/sh
# 用 APK 里打进去的那几个 .so（含 proot 与它的 loader），按 App 端的方式起一次。
# 这不能替代真机（少了 app 沙箱的 SELinux/exec 限制），但能排掉"配置写错"这一类。
set -e
W=/tmp/libtest
rm -rf "$W"
mkdir -p "$W"

python3 - <<'PY'
import zipfile, os
z = zipfile.ZipFile('host/build/tianshu-host.apk')
for n in z.namelist():
    if n.startswith('lib/arm64-v8a/'):
        out = '/tmp/libtest/' + os.path.basename(n)
        with open(out, 'wb') as f:
            f.write(z.read(n))
        os.chmod(out, 0o755)
        print(' 解出', out, os.path.getsize(out), 'B')
PY

cd "$W"
echo
echo "--- 用 APK 内的 proot + loader 起 rootfs ---"
LD_LIBRARY_PATH="$W" PROOT_LOADER="$W/libproot-loader.so" "$W/libproot.so" \
  --kill-on-exit -0 -r /root/rootfs-build/rootfs -w /root \
  -b /dev -b /proc -b /sys \
  /bin/bash -lc '
    echo "os:      $(head -1 /etc/os-release)"
    echo "node:    $(node -v)"
    echo "harness: $(tianshu --version)"
    echo "link:    $(ls -ld /bin | sed "s/.* \//\//") -> $(readlink /bin)"
    echo "PATH ok: $(command -v git) $(command -v rg)"
  '
echo "[libselftest done]"
