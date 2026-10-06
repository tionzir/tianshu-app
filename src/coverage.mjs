/**
 * 硬不变量 C：UI 组件与 `/` 命令双向覆盖。
 *
 * 天枢有 104 条斜杠命令。App 的每一个 UI 组件都必须能指到其中一条：
 *   - 正向：组件的 `cmd` 必须存在于命令注册表 —— 否则就是「凭空发明的按钮」
 *   - 反向：每条命令必须至少有一个落点，或显式登记为「移动端不适用」并给理由
 *   - 禁止孤儿：两个方向都不许有漏网
 *
 * 信息架构因此不是拍脑袋想的，而是从命令面推导出来的。
 */
import { readFileSync } from 'node:fs';

const CMD_RE = /^(\/[a-z0-9-]+)\s+(.*)$/;

/** 解析 `data/commands.txt`（由 bundle 抽取的 104 条命令） */
export function loadCommands(path) {
  const out = [];
  for (const raw of readFileSync(path, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line.startsWith('/')) continue;
    const m = CMD_RE.exec(line);
    if (!m) continue;
    const cmd = m[1];
    if (cmd.length <= 2) continue;
    out.push({ cmd, desc: m[2].trim() });
  }
  return out;
}

/** 解析组件注册表（surfaces + notApplicable） */
export function loadComponents(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

/** 展平成组件列表，便于 App 侧生成真实控件 */
export function expandComponents(components) {
  const out = [];
  for (const [surface, def] of Object.entries(components.surfaces ?? {})) {
    for (const cmd of def.items ?? []) {
      out.push({ surface, cmd, label: def.label ?? surface });
    }
  }
  return out;
}

/**
 * 双向覆盖检查。
 * @returns {{ok: boolean, orphansInUi: string[], orphansInCommands: string[],
 *            orphanNotApplicable: string[], covered: number, commandCount: number}}
 */
export function coverage(commands, components) {
  const known = new Set(commands.map((c) => c.cmd));
  const declared = new Set();
  const orphansInUi = [];

  for (const [, def] of Object.entries(components.surfaces ?? {})) {
    for (const cmd of def.items ?? []) {
      if (!known.has(cmd)) orphansInUi.push(cmd);
      declared.add(cmd);
    }
  }

  const na = components.notApplicable ?? {};
  const orphanNotApplicable = Object.keys(na).filter((c) => !known.has(c));

  const orphansInCommands = commands
    .map((c) => c.cmd)
    .filter((cmd) => !declared.has(cmd) && !(cmd in na));

  const uniqueInUi = [...new Set(orphansInUi)].sort();
  const ok = uniqueInUi.length === 0 && orphansInCommands.length === 0;

  return {
    ok,
    orphansInUi: uniqueInUi,
    orphansInCommands,
    orphanNotApplicable,
    covered: declared.size,
    commandCount: commands.length,
  };
}

/** 人类可读的覆盖报告，给 CI 和交付报告用 */
export function coverageReport(commands, components) {
  const r = coverage(commands, components);
  const lines = [];
  lines.push(`命令总数: ${r.commandCount}`);
  lines.push(`已落点: ${r.covered}`);
  lines.push(`不适用: ${Object.keys(components.notApplicable ?? {}).length}`);
  lines.push(`孤儿组件(有按钮无命令): ${r.orphansInUi.length ? r.orphansInUi.join(', ') : '无'}`);
  lines.push(`孤儿命令(有命令无落点): ${r.orphansInCommands.length ? r.orphansInCommands.join(', ') : '无'}`);
  lines.push(`结论: ${r.ok ? 'GREEN' : 'RED'}`);
  return lines.join('\n');
}

/**
 * 导出「命令名 → 命令说明」映射，给命令面板分组用。
 * tier 来自命令注册表的 `[core]` / `{F5}` 标注。
 */
export function commandIndex(commands) {
  return Object.fromEntries(commands.map((c) => [c.cmd, c.desc]));
}
