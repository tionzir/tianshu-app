#!/bin/sh
# 摸清 SSE /sessions/:id/stream 的事件形状。
# 这是对话页的前提：回答文本不在 GET /sessions/:id 里，只能从这里出来。
R=/root/rootfs-build/rootfs

# 打包时有意删掉了 rootfs 的 /etc/resolv.conf（保持镜像干净，由 App 运行时写）。
# 容器预演必须自己补一份，否则调模型时会 getaddrinfo EAI_AGAIN —— 那是预演环境的
# 缺口，不是产品缺陷（App 侧有 writeResolvConf()）。
cp /etc/resolv.conf "$R/etc/resolv.conf"
echo "已补 resolv.conf: $(head -1 "$R/etc/resolv.conf")"

proot -0 -r "$R" -w /root \
  -b /dev -b /proc -b /sys \
  /bin/bash -lc '
    export PATH=/usr/local/bin:/usr/bin:/bin
    export RIVET_SERVER_TOKEN=host_probe
    rivet serve --port 18799 --host 127.0.0.1 > /tmp/serve.log 2>&1 & SRV=$!
    for i in $(seq 1 90); do sleep 1; curl -s -o /dev/null http://127.0.0.1:18799/health && break; done
    echo "serve ready after=${i}s"

    SID=$(curl -s -X POST -H "Authorization: Bearer host_probe" -H "Content-Type: application/json" \
      -d "{\"prompt\":\"用一句话回答：2+2 等于几？\"}" http://127.0.0.1:18799/sessions \
      | sed -n "s/.*\"id\":\"\([^\"]*\)\".*/\1/p")
    echo "session id = $SID"
    echo

    echo "=== 开 SSE，最多听 100 秒 ==="
    timeout 100 curl -sN -H "Authorization: Bearer host_probe" \
      "http://127.0.0.1:18799/sessions/$SID/stream" > /tmp/sse.txt 2>&1
    echo "SSE 断开（或超时）"
    echo
    echo "--- 字节数 / 行数 ---"
    wc -c < /tmp/sse.txt | sed "s/^/  bytes: /"
    wc -l < /tmp/sse.txt | sed "s/^/  lines: /"
    echo
    echo "--- 前 30 行原始内容 ---"
    head -30 /tmp/sse.txt
    echo
    echo "--- 事件类型统计（event: xxx）---"
    grep -o "^event: .*" /tmp/sse.txt 2>/dev/null | sort | uniq -c | sort -rn | head -20
    echo "--- 若无 event: 行，列出前几个 JSON 的顶层 key ---"
    grep -o "^{.*}" /tmp/sse.txt 2>/dev/null | head -3 | cut -c1-400
    echo
    echo "--- 找答案文本（含 4 或 等于）的行 ---"
    grep -iE "等于|答案是|4" /tmp/sse.txt 2>/dev/null | head -8 | cut -c1-400

    kill $SRV 2>/dev/null
  '
echo "[probe-sse done]"
