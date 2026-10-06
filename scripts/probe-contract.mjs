#!/usr/bin/env node
/**
 * Runtime API 契约探针 —— 把「实测过的客户端契约」固化成可重复执行的靶子。
 *
 * 为什么需要它：计划里从 bundle 抽出 268 条候选路由，但**候选 ≠ 已注册**
 * （实测 GET /models 直接 404）。App 的每个接口调用都必须先打真靶。
 *
 * 用法：node scripts/probe-contract.mjs [--port 8799]
 * 退出码：0 全绿；1 有红点。
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const PORT = (() => {
  const i = process.argv.indexOf('--port');
  return i >= 0 ? Number(process.argv[i + 1]) : 8799;
})();
const TOKEN = `probe_${randomBytes(8).toString('hex')}`;
const BASE = `http://127.0.0.1:${PORT}`;
const EXTRA_PATH = '/data/data/com.termux/files/usr/bin';
const BIN = process.env.TIANSHU_BIN ?? 'tianshu';

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

const auth = { Authorization: `Bearer ${TOKEN}` };

async function req(path, init = {}) {
  const r = await fetch(BASE + path, init);
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON（SSE / 空体） */
  }
  return { status: r.status, headers: r.headers, text, json };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitReady(timeoutMs = 30_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(`${BASE}/health`);
      if (r.ok) return true;
    } catch {
      /* 还没起来 */
    }
    await sleep(400);
  }
  return false;
}

function startServer() {
  const child = spawn(BIN, ['serve', '--port', String(PORT), '--host', '127.0.0.1'], {
    env: {
      ...process.env,
      PATH: `${process.env.PATH ?? ''}:${EXTRA_PATH}`,
      RIVET_SERVER_TOKEN: TOKEN,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stdout.on('data', (d) => (log += d.toString()));
  child.stderr.on('data', (d) => (log += d.toString()));
  return { child, log: () => log };
}

async function main() {
  const srv = startServer();
  let createdId = null;

  try {
    if (!(await waitReady())) {
      record('服务启动', false, '30s 内 /health 未就绪');
      console.error(srv.log().split('\n').slice(-8).join('\n'));
      return;
    }
    record('服务启动', true, `:${PORT}`);

    // 1. /health 免鉴权
    {
      const r = await req('/health');
      record('GET /health 免鉴权', r.status === 200 && r.json?.ok === true, `HTTP ${r.status} ${r.text.slice(0, 80)}`);
    }

    // 2. 无 token → 401
    {
      const r = await req('/sessions');
      record('GET /sessions 无 token → 401', r.status === 401, `HTTP ${r.status}`);
    }

    // 3. 有 token → 200 且形状为 {sessions:[...]}
    {
      const r = await req('/sessions', { headers: auth });
      const shaped = r.status === 200 && Array.isArray(r.json?.sessions);
      record('GET /sessions 有 token → {sessions:[]}', shaped, `HTTP ${r.status}`);
    }

    // 4. 已知 404 对照：证明「候选路由 ≠ 已注册」
    {
      const r = await req('/models', { headers: auth });
      record('GET /models（已知未注册）→ 404', r.status === 404, `HTTP ${r.status} ${r.text.slice(0, 60)}`);
    }

    // 5. POST /sessions {} → 201 且带 UI 提示字段
    {
      const r = await req('/sessions', {
        method: 'POST',
        headers: { ...auth, 'Content-Type': 'application/json' },
        body: '{}',
      });
      const s = r.json;
      createdId = s?.id ?? null;
      const ok =
        r.status === 201 &&
        typeof s?.id === 'string' &&
        typeof s?.status === 'string' &&
        typeof s?.cwd === 'string' &&
        typeof s?.domain === 'string' &&
        'pendingApprovals' in (s ?? {}) &&
        'lastSeq' in (s ?? {});
      record('POST /sessions {} → 201 + 会话对象', ok, `HTTP ${r.status} id=${createdId}`);
      // 注意：POST 的创建响应**不含** domainGlyph/domainAccent，
      // 这两个 UI 提示字段只在 GET /sessions 的列表项里出现。
      // 创建响应缺字段是本轮实测到的不对称，App 必须两边都处理 —— 见下一条断言。
      record(
        '创建响应不含 UI 提示字段（已知不对称，App 需容错）',
        s?.domainGlyph === undefined && s?.domainAccent === undefined,
        `glyph=${s?.domainGlyph ?? '(无)'} accent=${s?.domainAccent ?? '(无)'}`,
      );
      record(
        '协议版本头 x-tianshu-protocol',
        r.headers.get('x-tianshu-protocol') === '1',
        `x-tianshu-protocol=${r.headers.get('x-tianshu-protocol')}`,
      );
    }

    // 5b. 列表项才带 UI 呈现提示（App 的域徽记用它渲染，不自己编）
    if (createdId) {
      const r = await req('/sessions', { headers: auth });
      const item = (r.json?.sessions ?? []).find((s) => s.id === createdId);
      record(
        '列表项自带 UI 呈现提示（domainGlyph / domainAccent）',
        typeof item?.domainGlyph === 'string' && typeof item?.domainAccent === 'string',
        `glyph=${item?.domainGlyph ?? '-'} accent=${item?.domainAccent ?? '-'}`,
      );
    }

    // 6. events 分页端点
    if (createdId) {
      const r = await req(`/sessions/${createdId}/events?limit=3`, { headers: auth });
      const ok = r.status === 200 && Array.isArray(r.json?.events) && 'lastSeq' in (r.json ?? {});
      record('GET /sessions/:id/events?limit=N → {events,lastSeq}', ok, `HTTP ${r.status}`);
    }

    // 7. SSE 流：首帧应为 replay_window
    if (createdId) {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 6000);
      try {
        const r = await fetch(`${BASE}/sessions/${createdId}/stream`, {
          headers: auth,
          signal: ctl.signal,
        });
        const ct = r.headers.get('content-type') ?? '';
        let buf = '';
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        while (buf.length < 400) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          if (buf.includes('\n\n')) break;
        }
        reader.cancel().catch(() => {});
        record('GET /sessions/:id/stream → text/event-stream', ct.includes('text/event-stream'), ct);
        record('SSE 首帧含 replay_window', buf.includes('replay_window'), JSON.stringify(buf.slice(0, 90)));
      } catch (e) {
        record('GET /sessions/:id/stream → text/event-stream', false, String(e?.message ?? e));
      } finally {
        clearTimeout(timer);
      }
    }

    // 8. 清理：删掉探针建的会话
    if (createdId) {
      const r = await req(`/sessions/${createdId}`, { method: 'DELETE', headers: auth });
      record('DELETE /sessions/:id → 200', r.status === 200, `HTTP ${r.status}`);
      const after = await req('/sessions', { headers: auth });
      const gone = !(after.json?.sessions ?? []).some((s) => s.id === createdId);
      record('删除后会话确实从列表消失', gone, `剩余 ${after.json?.sessions?.length ?? '?'} 个`);
    }
  } finally {
    srv.child.kill('SIGTERM');
    await sleep(1200);
    if (srv.child.exitCode === null) srv.child.kill('SIGKILL');
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n# 契约探针：${results.length - failed.length}/${results.length} 通过`);
  if (failed.length) {
    console.log('# 未通过：');
    for (const f of failed) console.log(`#   - ${f.name} (${f.detail})`);
  }
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('探针异常：', e);
  process.exit(1);
});
