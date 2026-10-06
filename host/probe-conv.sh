#!/bin/sh
# 在容器里预演「真发一轮对话」，摸清 POST /sessions 带 prompt 的真实形状。
# 容器里的 rootfs 与 App 里那份同源、同配置，所以结论可直接用。
R=/root/rootfs-build/rootfs

proot -0 -r "$R" -w /root \
  -b /dev -b /proc -b /sys \
  /bin/bash -lc '
    export PATH=/usr/local/bin:/usr/bin:/bin
    export RIVET_SERVER_TOKEN=host_probe
    rivet serve --port 18799 --host 127.0.0.1 > /tmp/serve.log 2>&1 & SRV=$!
    for i in $(seq 1 90); do sleep 1; curl -s -o /dev/null http://127.0.0.1:18799/health && break; done
    echo "serve ready after=${i}s"
    echo
    echo "=== (b) HTTP: POST /sessions 带 prompt ==="
    echo "--- 发出去，最长等 240s ---"
    curl -s -X POST -H "Authorization: Bearer host_probe" -H "Content-Type: application/json" \
      -d "{\"prompt\":\"用一句话回答：2+2 等于几？\"}" \
      -w "\n[HTTP %{http_code}]  [耗时 %{time_total}s]\n" --max-time 240 \
      http://127.0.0.1:18799/sessions 2>&1 | head -c 2000
    echo
    echo "--- 会话列表 ---"
    curl -s -H "Authorization: Bearer host_probe" http://127.0.0.1:18799/sessions 2>&1 | head -c 900
    echo
    echo "--- serve.log 里与 provider 有关的行 ---"
    grep -iE "provider|api key|error|fail" /tmp/serve.log | tail -8
    kill $SRV 2>/dev/null
  '
echo "[probe-conv done]"
