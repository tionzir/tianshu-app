#!/bin/sh
# 断言：rootfs 里预置好配置之后，起出来的 serve 应当 configured=true。
# 配置期望位置：<rootfs>/root/.rivet/{config.json,provider-keys.json,secrets.json}
# 未预置时本脚本必须 FAIL —— 这是这次改动的红灯判据。
R=${R:-/root/rootfs-build/rootfs}

echo "=== 配置是否就位 ==="
for f in config.json provider-keys.json secrets.json; do
  p="$R/root/.rivet/$f"
  if [ -f "$p" ]; then
    echo "  ok   $f  $(wc -c < "$p") B  mode=$(stat -c '%a' "$p")"
  else
    echo "  MISS $f"
  fi
done

echo
echo "=== 起一次 serve，问 /health（带 token）==="
proot -0 -r "$R" -w /root \
  -b /dev -b /proc -b /sys \
  /bin/bash -lc '
    export PATH=/usr/local/bin:/usr/bin:/bin
    export RIVET_SERVER_TOKEN=cfgcheck
    rivet serve --port 18798 --host 127.0.0.1 > /tmp/cfgcheck.log 2>&1 & SRV=$!
    ok=no
    for i in $(seq 1 90); do sleep 1; curl -s -o /dev/null http://127.0.0.1:18798/health && { ok=yes; break; }; done
    echo "ready=$ok after=${i}s"
    echo "--- /health(token) ---"
    curl -s -H "Authorization: Bearer cfgcheck" http://127.0.0.1:18798/health
    echo
    echo "--- serve.log 里有没有 setup mode ---"
    grep -i "setup mode\|No API key" /tmp/cfgcheck.log || echo "  （没有 setup mode 警告）"
    kill $SRV 2>/dev/null
  ' > /tmp/cfgcheck.out 2>&1

cat /tmp/cfgcheck.out

echo
if grep -q '"configured":true' /tmp/cfgcheck.out; then
  echo "[CONFIGCHECK] PASS —— configured:true"
  exit 0
else
  echo "[CONFIGCHECK] FAIL —— 没看到 configured:true"
  exit 1
fi
