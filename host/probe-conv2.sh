#!/bin/sh
# 补验：POST /sessions 只创建会话，回答在后台跑 —— 那怎么取回来？
R=/root/rootfs-build/rootfs

proot -0 -r "$R" -w /root \
  -b /dev -b /proc -b /sys \
  /bin/bash -lc '
    export PATH=/usr/local/bin:/usr/bin:/bin
    export RIVET_SERVER_TOKEN=host_probe
    rivet serve --port 18799 --host 127.0.0.1 > /tmp/serve.log 2>&1 & SRV=$!
    for i in $(seq 1 90); do sleep 1; curl -s -o /dev/null http://127.0.0.1:18799/health && break; done
    echo "serve ready after=${i}s"

    ID=$(curl -s -X POST -H "Authorization: Bearer host_probe" -H "Content-Type: application/json" \
      -d "{\"prompt\":\"用一句话回答：2+2 等于几？\"}" \
      http://127.0.0.1:18799/sessions | sed -n "s/.*\"id\":\"\([^\"]*\)\".*/\1/p")
    echo "session id = $ID"

    for t in 10 30 60; do
      sleep 10
      echo
      echo "--- t≈${t}s: GET /sessions/$ID ---"
      curl -s -H "Authorization: Bearer host_probe" -w "\n[HTTP %{http_code}]\n" \
        "http://127.0.0.1:18799/sessions/$ID" | head -c 1500
    done
    echo
    echo "--- serve.log 尾部 ---"
    tail -6 /tmp/serve.log
    kill $SRV 2>/dev/null
  '
echo "[probe-conv2 done]"
