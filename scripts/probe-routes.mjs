#!/usr/bin/env node
/**
 * 候选路由打靶 —— 回答「哪些真的能用、哪些会挂」。
 *
 * 背景：路由表是从 bundle 里抽的**候选**，不是保证。实测 GET /models → 404。
 *
 * 两条实测教训（都写进这里了）：
 *   1. **必须串行**。并发 8 时前 51 条正常、之后 209 条 ECONNREFUSED —— 并发 +
 *      中途 abort 会把连接池搞脏，得到的是探针噪声而不是路由事实。串行复测
 *      同一批路由，服务进程始终存活（exitCode 保持 null）。
 *   2. **「挂起」是独立结论，不是错误**。`GET /config/balance` 4s 不返回（要连上游
 *      查余额）。对 App 而言这是关键发现：这类路由**必须设超时**。
 *
 * ⚠ 安全边界：只发 GET。非 GET 路由一律不盲打 —— `POST /config/providers`
 *    发一次就会改机器状态。它们标「未探明」，不等于「未注册」。
 *
 * 用法：node scripts/probe-routes.mjs [--port 8798] [--timeout 2500]
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, dflt) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? Number(process.argv[i + 1]) : dflt;
};
const PORT = arg('--port', 8798);
const TIMEOUT = arg('--timeout', 2500);
const TOKEN = `routes_${randomBytes(8).toString('hex')}`;
const BASE = `http://127.0.0.1:${PORT}`;
const BIN = process.env.TIANSHU_BIN ?? 'tianshu';
const EXTRA_PATH = '/data/data/com.termux/files/usr/bin';

/** 允许用真实方法探测的非 GET 路由（经确认无副作用） */
const SAFE_NON_GET = new Set(['POST /abort']);
/** 流式端点：连接不结束，打靶会永远挂着 → 单独标记 */
const STREAMING = /\/(stream|events)$/;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseRoutes() {
  const out = [];
  for (const line of readFileSync(join(ROOT, 'data', 'candidate-routes.txt'), 'utf8').split('\n')) {
    const m = /^(GET|POST|PUT|PATCH|DELETE)\s+(\/\S*)$/.exec(line.trim());
    if (m) out.push({ method: m[1], path: m[2] });
  }
  out.sort((a, b) => a.path.localeCompare(b.path));
  return out;
}

const fillParams = (p) => p.replace(/:[A-Za-z_][A-Za-z0-9_]*/g, '__probe__');

function startServer() {
  const child = spawn(BIN, ['serve', '--port', String(PORT), '--host', '127.0.0.1'], {
    env: {
      ...process.env,
      PATH: `${process.env.PATH ?? ''}:${EXTRA_PATH}`,
      RIVET_SERVER_TOKEN: TOKEN,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const logs = { out: '', err: '' };
  child.stdout.on('data', (d) => (logs.out += d.toString()));
  child.stderr.on('data', (d) => (logs.err += d.toString()));
  return { child, logs };
}

async function waitReady(timeoutMs = 30_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      if ((await fetch(`${BASE}/health`)).ok) return true;
    } catch {
      /* not up */
    }
    await sleep(400);
  }
  return false;
}

async function probeOne({ method, path }) {
  const realMethod = method === 'GET' || SAFE_NON_GET.has(`${method} ${path}`);
  const useMethod = realMethod ? method : 'GET';
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT);
  const t0 = Date.now();
  try {
    const r = await fetch(BASE + fillParams(path), {
      method: useMethod,
      headers: { Authorization: `Bearer ${TOKEN}` },
      signal: ctl.signal,
    });
    await r.text();
    return {
      method,
      path,
      probedWith: useMethod,
      truthful: realMethod,
      status: r.status,
      ms: Date.now() - t0,
      note: '',
    };
  } catch (e) {
    const aborted = e?.name === 'AbortError';
    return {
      method,
      path,
      probedWith: useMethod,
      truthful: realMethod,
      status: 0,
      ms: Date.now() - t0,
      note: aborted ? `超时 >${TIMEOUT}ms` : `${e?.name}: ${e?.cause?.code ?? e?.message}`,
    };
  } finally {
    clearTimeout(timer);
  }
}

const verdictOf = (r) => {
  if (r.status !== 0) return r.status === 404 ? (r.truthful ? 'not-registered' : 'unprobed') : 'registered';
  if (/超时/.test(r.note)) return 'hangs';
  // 实测：GET /git/graph 在非 git 仓库下会让 serve 进程 exit 1
  //（chunk-RU5ST7OA.js:6528 在 ChildProcess 回调里 throw → 未捕获异常）
  if (/ECONNREFUSED|UND_ERR_SOCKET|ECONNRESET|EPIPE/.test(r.note)) return 'crashed-server';
  return 'error';
};

async function main() {
  const all = parseRoutes();
  const streaming = all.filter((r) => STREAMING.test(r.path));
  const plain = all.filter((r) => !STREAMING.test(r.path));

  let srv = startServer();
  const rows = [];
  const crashes = [];
  const stop = async () => {
    srv.child.kill('SIGTERM');
    await sleep(1200);
    if (srv.child.exitCode === null) srv.child.kill('SIGKILL');
  };
  const restart = async () => {
    await stop();
    srv = startServer();
    return waitReady();
  };
  try {
    if (!(await waitReady())) {
      console.error('serve 未就绪，放弃');
      await stop();
      process.exit(1);
    }
    console.log(`串行打靶 ${plain.length} 条（超时 ${TIMEOUT}ms），流式 ${streaming.length} 条跳过…`);
    for (const r of plain) {
      const res = await probeOne(r);
      res.verdict = verdictOf(res);
      rows.push(res);
      if (res.verdict === 'crashed-server') {
        crashes.push({ route: `${r.method} ${r.path}`, note: res.note });
        console.log(`  ⚠ 打崩服务       ${r.method} ${r.path}  ${res.note} → 重启后继续`);
        if (!(await restart())) {
          console.log('  重启失败，停止打靶');
          break;
        }
        continue;
      }
      if (res.verdict !== 'registered') {
        console.log(`  ${res.verdict.padEnd(14)} ${r.method} ${r.path}  ${res.status || res.note}`);
      }
    }
  } finally {
    await stop();
  }

  const byVerdict = rows.reduce((a, r) => ((a[r.verdict] = (a[r.verdict] ?? 0) + 1), a), {});

  mkdirSync(join(ROOT, 'data'), { recursive: true });
  writeFileSync(
    join(ROOT, 'data', 'routes-probe.json'),
    JSON.stringify({ port: PORT, timeoutMs: TIMEOUT, total: all.length, byVerdict, rows }, null, 2),
  );

  const md = [];
  md.push('# 候选路由打靶结果\n');
  md.push(`候选总数 **${all.length}**（bundle 抽取，非注册保证）；串行探测，超时 ${TIMEOUT}ms。\n`);
  md.push('| 结论 | 条数 |');
  md.push('|---|---|');
  md.push(`| 已注册（非 404） | ${byVerdict.registered ?? 0} |`);
  md.push(`| 未注册（404） | ${byVerdict['not-registered'] ?? 0} |`);
  md.push(`| **会挂起**（超时无响应） | ${byVerdict.hangs ?? 0} |`);
  md.push(`| 未探明（非 GET，拒绝盲打） | ${byVerdict.unprobed ?? 0} |`);
  md.push(`| 流式端点（连接不结束，跳过） | ${streaming.length} |`);
  md.push(`| 其他异常 | ${byVerdict.error ?? 0} |`);
  md.push(`\n**打崩 serve 的路由：${crashes.length} 条**（探针已自动重启并继续）\n`);
  if (crashes.length) {
    md.push('| 路由 | 现象 |');
    md.push('|---|---|');
    for (const c of crashes) md.push(`| \`${c.route}\` | ${c.note} |`);
    md.push(
      '\n> 实测根因：handler 在**非 git 仓库**目录执行 git，git 以 128 退出后' +
        '\n> `chunk-RU5ST7OA.js:6528` 在 ChildProcess 回调里 throw → 未捕获异常 → `process.exit(1)`。' +
        '\n> 后果：**任意一条请求即可让整个 Runtime 下线**。' +
        '\n> 对 App 的要求：必须有进程守护 + 自动重启 + 屏幕 6 异常态兜底，不能假设服务一直活着。\n',
    );
  }

  const section = (title, filter) => {
    const list = rows.filter(filter);
    md.push(`\n## ${title}（${list.length}）\n`);
    if (!list.length) {
      md.push('（无）');
      return;
    }
    md.push('| 方法 | 路径 | HTTP | 耗时ms |');
    md.push('|---|---|---|---|');
    for (const r of list) md.push(`| ${r.method} | \`${r.path}\` | ${r.status || '—'} | ${r.ms} |`);
  };
  section('会挂起 —— App 必须对这些设超时', (r) => r.verdict === 'hangs');
  section('已注册', (r) => r.verdict === 'registered');
  section('未注册', (r) => r.verdict === 'not-registered');
  section('未探明（非 GET）', (r) => r.verdict === 'unprobed');
  section('流式端点', (r) => streaming.includes(r));

  md.push('\n> 未探明项**不等于**未注册：本脚本拒绝用真实方法盲打非 GET 路由，');
  md.push('> 因为 `POST /config/providers` 这类接口发一次就会改机器状态。');
  md.push('> 这些路由要在 App 侧逐个按需打靶。\n');

  writeFileSync(join(ROOT, 'data', 'routes-probe.md'), md.join('\n'));

  console.log(
    `\n候选 ${all.length} → 已注册 ${byVerdict.registered ?? 0} / 未注册 ${byVerdict['not-registered'] ?? 0} / ` +
      `挂起 ${byVerdict.hangs ?? 0} / 未探明 ${byVerdict.unprobed ?? 0} / 流式 ${streaming.length} / ` +
      `打崩服务 ${crashes.length} / 其他异常 ${byVerdict.error ?? 0}`,
  );
  console.log('报告：data/routes-probe.md');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
