// Wave 1 四个离线靶子 —— 全部对应计划里的三条硬不变量
// 靶子 1 → 不变量 A（答案永远在本轮最底）
// 靶子 2 → 外观系统的配色求解与可读性护栏
// 靶子 3 → 不变量 C（UI 组件与 / 命令双向覆盖）
// 靶子 4 → 不变量 B（换外观只改外观，不改结构）
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { parseTurn, visibleOnOpen } from '../src/transcript.mjs';
import {
  rgb,
  isHex,
  hex,
  normHex,
  mix,
  alpha,
  lum,
  contrast,
  isDark,
  PRESETS,
  ACCENTS,
  STYLES,
  presetById,
  accentById,
  solveCustom,
  readableSub,
  customPreset,
  onColor,
  cardAlphaOf,
  isBgPath,
  normalize,
  palette,
  tokens,
  audit,
  DEFAULT_CFG,
  DEFAULT_BG,
  CARD_ALPHA_MIN,
  CARD_ALPHA_MAX,
  renderTree,
  SESSION_SCREEN,
} from '../src/theme.mjs';
import { loadCommands, loadComponents, coverage } from '../src/coverage.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const commandsPath = join(ROOT, 'data', 'commands.txt');
const componentsPath = join(ROOT, 'data', 'components.json');

const AA = 4.5;

/** 组装一份合法配置（测试里到处要用）。 */
function cfg(patch = {}) {
  return normalize({
    v: 1,
    preset: 'clear',
    accent: 'blue',
    custom: { bg: DEFAULT_BG, bg2: '' },
    image: '',
    cardAlpha: 1,
    ...patch,
  });
}

// ---------------------------------------------------------------- 靶子 1

describe('靶子 1 · 答案永远在本轮最底（不变量 A）', () => {
  const interleaved = [
    { type: 'thinking_delta', text: '先想一下' },
    { type: 'tool_use', id: 't1', name: 'read_file', input: { path: 'a.ts' } },
    { type: 'tool_result', id: 't1', name: 'read_file', result: '...' },
    { type: 'text_delta', text: '这是个过程说明' },
    { type: 'tool_use', id: 't2', name: 'grep', input: { pattern: 'x' } },
    { type: 'tool_result', id: 't2', name: 'grep', result: '...' },
    { type: 'text_delta', text: 'B' },
    { type: 'turn_complete' },
  ];

  test('最后一个可见块是答案块，且内容为最后一段连续文本', () => {
    const { blocks } = parseTurn(interleaved);
    const last = blocks[blocks.length - 1];
    assert.equal(last.kind, 'answer', '最后一个块必须是答案块');
    assert.equal(last.text, 'B');
  });

  test('一轮里只有一个答案块（不许把过程说明也算成答案）', () => {
    const { blocks } = parseTurn(interleaved);
    assert.equal(blocks.filter((b) => b.kind === 'answer').length, 1);
  });

  test('过程说明并入活动区，不计入答案', () => {
    const { blocks } = parseTurn(interleaved);
    const activity = blocks.find((b) => b.kind === 'activity');
    assert.ok(activity, '必须存在活动区块');
    assert.equal(activity.collapsed, true, '活动区默认折叠');
    assert.equal(activity.counts.thinking, 1);
    assert.equal(activity.counts.tools, 2);
    const notes = activity.children.filter((c) => c.kind === 'note');
    assert.equal(notes.length, 1);
    assert.equal(notes[0].text, '这是个过程说明');
  });

  test('答案在初始视口内，不需要滚动（用户诉求原文）', () => {
    const { blocks } = parseTurn(interleaved);
    const visible = visibleOnOpen(blocks, 3);
    assert.ok(
      visible.some((b) => b.kind === 'answer'),
      '打开这一轮时答案必须已经在视口里',
    );
  });

  test('反例：单轮只有 thinking 没有 text → 不产生空答案块', () => {
    const { blocks } = parseTurn([
      { type: 'thinking_delta', text: '只有思考' },
      { type: 'turn_complete' },
    ]);
    assert.equal(blocks.filter((b) => b.kind === 'answer').length, 0);
  });

  test('反例：无工具的纯文本回合 → 答案就是全部文本', () => {
    const { blocks } = parseTurn([
      { type: 'text_delta', text: '直接回答' },
      { type: 'turn_complete' },
    ]);
    assert.equal(blocks[blocks.length - 1].kind, 'answer');
    assert.equal(blocks[blocks.length - 1].text, '直接回答');
  });

  test('流式下多轮连发不跳位：每轮的答案都在自己那轮的最底', () => {
    const turns = [
      { events: [{ type: 'text_delta', text: '第一轮' }, { type: 'turn_complete' }] },
      {
        events: [
          { type: 'tool_use', id: 'x', name: 'bash', input: {} },
          { type: 'tool_result', id: 'x', name: 'bash', result: 'ok' },
          { type: 'text_delta', text: '第二轮' },
          { type: 'turn_complete' },
        ],
      },
    ];
    for (const t of turns) {
      const { blocks } = parseTurn(t.events);
      assert.equal(blocks[blocks.length - 1].kind, 'answer');
    }
  });
});

// ---------------------------------------------------------------- 靶子 2

describe('靶子 2 · 外观系统的配色求解与护栏', () => {
  test('对比度公式自检：黑白 = 21:1，同色 = 1:1', () => {
    assert.ok(Math.abs(contrast('#000000', '#FFFFFF') - 21) < 0.01);
    assert.ok(Math.abs(contrast('#3a3a3a', '#3a3a3a') - 1) < 0.001);
  });

  test('相对亮度单调：白 > 灰 > 黑', () => {
    const w = lum('#FFFFFF');
    const g = lum('#808080');
    const b = lum('#000000');
    assert.ok(w > g && g > b);
    assert.ok(Math.abs(w - 1) < 1e-9, '纯白亮度为 1');
  });

  test('色值工具：解析 / 归一化 / 混色端点 / 透明度', () => {
    assert.deepEqual(rgb('#abc'), [0xaa, 0xbb, 0xcc], '#RGB 展开');
    assert.deepEqual(rgb('#58A6FF'), [0x58, 0xa6, 0xff], '#RRGGBB 解析');
    assert.equal(rgb('58A6FF'), null, '缺 # 不认（不猜）');
    assert.equal(rgb('nope'), null);
    assert.equal(isHex('#fff'), true);
    assert.equal(isHex('#ggg'), false);
    assert.equal(hex(0, 0, 0), '#000000');
    assert.equal(normHex('#ABC'), '#aabbcc', '归一化成小写 6 位');
    assert.equal(normHex('nope'), 'nope', '非法输入原样返回');
    assert.equal(mix('#000000', '#ffffff', 0), '#000000', 't=0 得 a');
    assert.equal(mix('#000000', '#ffffff', 1), '#ffffff', 't=1 得 b');
    assert.equal(mix('#000000', '#ffffff', 0.5), '#808080', 't=0.5 是中灰');
    assert.equal(alpha('#ffffff', 0.5), 'rgba(255,255,255,0.5)', '半透明同色');
    assert.equal(isDark('#000000'), true);
    assert.equal(isDark('#ffffff'), false);
  });

  test('6 预设 × 7 主色 × 3 档卡片透明度：护栏全部 GREEN', () => {
    const alphas = [CARD_ALPHA_MAX, 0.65, CARD_ALPHA_MIN];
    let count = 0;
    for (const p of PRESETS) {
      for (const a of ACCENTS) {
        for (const al of alphas) {
          const c = cfg({ preset: p.id, accent: a.id, cardAlpha: al });
          const fails = audit(c);
          assert.equal(
            fails.length,
            0,
            `外观「${p.id}/${a.id}」alpha=${al} 护栏不达标：` +
              fails.map((f) => `${f.label} ${f.ratio} < ${f.min}`).join('; '),
          );
          count++;
        }
      }
    }
    assert.equal(count, 7 * 8 * 3, '矩阵组合数');
  });

  test('预设自带的名字与明暗语义齐全（界面要靠它渲染缩略与提示）', () => {
    assert.equal(PRESETS.length, 7);
    assert.equal(ACCENTS.length, 8);
    for (const p of PRESETS) {
      assert.ok(p.id && p.name && p.hint, `预设 ${p.id} 三件套齐全`);
      assert.ok(p.mode === 'light' || p.mode === 'dark', `${p.id} 有明暗语义`);
      for (const k of ['bg', 'bg2', 'card', 'fg', 'sub', 'line']) {
        assert.ok(isHex(p[k]), `${p.id}.${k} 是合法色值：${p[k]}`);
      }
    }
    for (const a of ACCENTS) {
      assert.ok(a.id && a.name, `主色 ${a.id} 有名字`);
      assert.ok(isHex(a.pri) && isHex(a.pri2) && isHex(a.deep), `${a.id} 三个基准色合法`);
    }
    assert.equal(presetById('deep').mode, 'dark', '深海是深色');
    assert.equal(presetById('nope'), null, '未知预设返回 null（由 normalize 兜底）');
    assert.equal(accentById('nope'), null);
  });

  test('自定义底色：亮底与深底都能找到完全解', () => {
    for (const bg of ['#ffffff', '#f4f6fa', '#eaeff2', '#204060', '#12151b', '#000000']) {
      const s = solveCustom(bg);
      assert.equal(s.ok, true, `${bg} 应当有完全解`);
      assert.ok(contrast(s.fg, s.card) >= 7, `${bg}：卡片上的字 ≥ 7:1`);
      assert.ok(contrast(s.fg, bg) >= AA, `${bg}：底色上的字 ≥ 4.5:1`);
    }
  });

  test('自定义底色：卡片色确实随底色走（不是写死的一套）', () => {
    const lightCard = solveCustom('#f4f6fa').card;
    const darkCard = solveCustom('#12151b').card;
    assert.notEqual(lightCard, darkCard, '浅底与深底解出的卡片色必须不同');
    assert.equal(solveCustom('#f4f6fa').dark, false, '浅底配深字');
    assert.equal(solveCustom('#12151b').dark, true, '深底配浅字');
  });

  test('中灰底（#6f6f6f~#828282 一段）：物理上无完全解，但卡片上仍达标且如实标记', () => {
    const bg = '#787878';
    const s = solveCustom(bg);
    assert.equal(s.ok, false, '中灰底必须如实标记 ok:false，而不是假装解决');
    assert.ok(contrast(s.fg, s.card) >= 7, '即便无完全解，卡片上的字仍 ≥ 7:1');

    const c = cfg({ preset: 'custom', custom: { bg, bg2: '' } });
    const t = tokens(c);
    assert.ok(contrast(t.fg, t.cardSolid) >= AA, '卡片里的正文仍然清楚');
    const p = palette(c);
    assert.equal(p.ok, false, 'palette 把无解这件事透出来（界面据此提示用户）');
    assert.ok(p.baseContrast > 1 && p.baseContrast < AA, '次优解的实测对比度落在 1~4.5 之间');
  });

  test('反例：中灰底必须被护栏抓到（不许静默放行）', () => {
    const c = cfg({ preset: 'custom', custom: { bg: '#787878', bg2: '' } });
    const fails = audit(c);
    assert.ok(fails.length > 0, '中灰底必须有告警');
    assert.ok(
      fails.some((f) => f.label === 'noteFg on bg'),
      '告警应指向「直接落在底色上的文字」，实际：' + JSON.stringify(fails),
    );
  });

  test('主色上的字色按对比度选，不写死白色', () => {
    // 蓝、墨够深 → 白字；橙、青、绿、玫太亮 → 换近黑（白字只有 3~3.9:1）
    assert.equal(onColor('#2b6cf0', '#5b8def'), '#ffffff', '蓝上用白字');
    assert.equal(onColor('#4a5568', '#6b7787'), '#ffffff', '墨上用白字');
    assert.notEqual(onColor('#e07819', '#f09b45'), '#ffffff', '橙上不能写白字');
    assert.notEqual(onColor('#0e93a8', '#3ab4c4'), '#ffffff', '青上不能写白字');
    for (const a of ACCENTS) {
      const ink = onColor(a.pri, a.pri2);
      assert.ok(contrast(ink, a.pri) >= AA, `${a.id}：主色上的字 ≥ 4.5:1`);
    }
  });

  test('次要文字色：对比达标（前提：正文色本来就在卡片上达标）', () => {
    // readableSub 的调用方保证 fg 已在 card 上达标 —— customPreset 传的正是
    // solveCustom 解出的 card/fg 对（那边判据是 ≥7:1）。这里按同一前提组用例。
    const pairs = [
      ['#ffffff', '#1b1f27'],
      ['#fffdf9', '#2b2419'],
      ['#ffffff', '#1c2620'],
      ['#1c212a', '#e9edf4'],
      ['#4d4d4d', '#e9edf4'],
    ];
    for (const [card, fg] of pairs) {
      assert.ok(contrast(fg, card) >= AA, `前置不变量：${fg} 在 ${card} 上达标`);
      const sub = readableSub(card, fg);
      assert.ok(contrast(sub, card) >= AA, `sub(${card},${fg}) 在卡片上达标`);
    }
    // 兜底路径：输入不自洽时退回正文色，而不是返回一个更差的色
    assert.equal(readableSub('#ffffff', '#e9edf4'), '#e9edf4', '无达标解时退回正文色');
  });

  test('normalize：垃圾输入一律不抛，回落默认', () => {
    const d = normalize(null);
      assert.equal(d.preset, DEFAULT_CFG.preset);
    assert.equal(d.accent, DEFAULT_CFG.accent);
    assert.equal(d.cardAlpha, 1);
    assert.equal(d.image, '');
    assert.deepEqual(d.custom, { bg: DEFAULT_BG, bg2: '' });

      assert.equal(normalize('nonsense').preset, DEFAULT_CFG.preset, '字符串输入');
      assert.equal(normalize(42).preset, DEFAULT_CFG.preset, '数字输入');
      assert.equal(normalize({ preset: '不存在' }).preset, DEFAULT_CFG.preset, '未知预设');
    assert.equal(normalize({ accent: 123 }).accent, DEFAULT_CFG.accent, '未知主色 → 默认');
    assert.equal(normalize({ custom: 'x' }).custom.bg, DEFAULT_BG, 'custom 不是对象');
    assert.equal(normalize({ custom: { bg: '#xyz' } }).custom.bg, DEFAULT_BG, '非法底色');
  });

  test('normalize：空串是合法值（"不要渐变/不要图片"不是错误）', () => {
    const n = normalize({ custom: { bg: '#112233', bg2: '' }, image: '' });
    assert.equal(n.custom.bg2, '', '空串渐变保持空串，不塌成默认色');
    assert.equal(n.image, '', '空串图片保持空串');
      assert.equal(n.preset, DEFAULT_CFG.preset, '显式给了 custom.bg 但没写 preset → preset 仍是默认（自定义要显式选）');
    const c = normalize({ preset: 'custom', custom: { bg: '#204060', bg2: '' } });
    assert.equal(c.preset, 'custom', '显式 custom 被接受');
    assert.equal(c.custom.bg, '#204060');
  });

  test('cardAlpha：越界夹取，只有"压根不是数"才回 1', () => {
    assert.equal(cardAlphaOf(0.05), CARD_ALPHA_MIN, '低于下限夹到下限');
    assert.equal(cardAlphaOf(2), CARD_ALPHA_MAX, '高于上限夹到上限');
    assert.equal(cardAlphaOf(0.75), 0.75, '手改配置写个 0.75 要能用');
    assert.equal(cardAlphaOf('0.65'), 0.65, '字符串数字也认');
    assert.equal(cardAlphaOf(null), CARD_ALPHA_MAX, 'null → 不透明（升级前的样子）');
    assert.equal(cardAlphaOf(''), CARD_ALPHA_MAX, '空串 → 不透明');
    assert.equal(cardAlphaOf({}), CARD_ALPHA_MAX, '对象 → 不透明');
    assert.equal(cardAlphaOf('abc'), CARD_ALPHA_MAX, '乱写 → 不透明');
    assert.equal(normalize({ cardAlpha: 0.65 }).cardAlpha, 0.65);
  });

  test('isBgPath：只认绝对路径，且容不下会坏事字符', () => {
    assert.ok(isBgPath('/storage/emulated/0/tianshu-app/bg/background.jpg'), '正常绝对路径');
    assert.ok(!isBgPath('bg/background.jpg'), '相对路径不认');
    assert.ok(!isBgPath(''), '空串不认（那就是"不用图片"）');
    assert.ok(!isBgPath('a'), '太短不认');
    assert.ok(!isBgPath('/a"b.jpg'), '引号不许进来');
    assert.ok(!isBgPath("/a'b.jpg"), '单引号不许进来');
    assert.ok(!isBgPath('/a(b).jpg'), '括号不许进来');
    assert.ok(!isBgPath('/a\\b.jpg'), '反斜杠不许进来');
    assert.ok(!isBgPath('/a\nb.jpg'), '换行不许进来');
    assert.ok(!isBgPath(null), 'null 不认');
    assert.equal(normalize({ image: '/a"b.jpg' }).image, '', '非法图片路径被 normalize 清掉');
    assert.equal(
      normalize({ image: '/storage/emulated/0/bg/x.jpg' }).image,
      '/storage/emulated/0/bg/x.jpg',
      '合法路径被保留',
    );
  });

  test('卡片透明度真的会改变 token（否则滑块是摆设）', () => {
    const solid = tokens(cfg({ cardAlpha: 1 }));
    const glass = tokens(cfg({ cardAlpha: 0.35 }));
    assert.equal(solid.cardAlpha, 1);
    assert.equal(glass.cardAlpha, 0.35);
    assert.notEqual(glass.cardSolid, solid.cardSolid, '半透明卡片的合成色必须不同');
    assert.ok(
      contrast(glass.fg, glass.cardSolid) >= AA,
      '透光之后卡片里的字仍然达标（这正是"两个底一起解"要保证的）',
    );
    assert.ok(String(glass.fieldBg).startsWith('rgba('), '低透明度下输入框底走半透明');
    assert.ok(String(solid.fieldBg).startsWith('#'), '不透明时输入框底是实色');
  });

  test('渐变与遮罩：token 里带得出来', () => {
    const t = tokens(cfg({ accent: 'amber' }));
    assert.equal(t.grad, '#e07819,#f09b45', '渐变两端来自主色');
    assert.ok(String(t.scrim).startsWith('rgba('), '遮罩是半透明底色');
    const img = tokens(cfg({ image: '/storage/emulated/0/bg/a.jpg' }));
    assert.equal(img.image, '/storage/emulated/0/bg/a.jpg', '背景图路径透传给界面层');
  });

  test('DEFAULT_CFG = 初雪（扁平浅灰 + 纯蓝 + 不透明无图）', () => {
    assert.equal(DEFAULT_CFG.preset, PRESETS[0].id, '默认 = 列表首位（换默认时这里不会漏改）');
    assert.equal(DEFAULT_CFG.accent, ACCENTS[0].id);
    assert.equal(DEFAULT_CFG.style, STYLES[0].id);
    assert.equal(DEFAULT_CFG.cardAlpha, 1);
    assert.equal(DEFAULT_CFG.image, '');
    const t = tokens(DEFAULT_CFG);
    assert.equal(t.bg, '#f5f7fa', '初雪背景（照参考 App）');
    assert.equal(t.pri, '#2f6bff', '初雪纯蓝（参考里否掉了紫色）');
  });
});

// ---------------------------------------------------------------- 靶子 3

describe('靶子 3 · UI 组件与 / 命令双向覆盖（不变量 C）', () => {
  const commands = loadCommands(commandsPath);
  const components = loadComponents(componentsPath);

  test('命令注册表解析出 112 条', () => {
    assert.equal(commands.length, 112);
    assert.ok(commands.every((c) => c.cmd.startsWith('/')));
  });

  test('正向：每个 UI 组件声明的 cmd 都能在命令注册表里找到', () => {
    const r = coverage(commands, components);
    assert.deepEqual(r.orphansInUi, [], '不允许「没有命令来源的按钮」');
  });

  test('反向：112 条命令每条都有落点，或已显式登记为移动端不适用', () => {
    const r = coverage(commands, components);
    assert.deepEqual(r.orphansInCommands, [], '不允许「没有落点的命令」');
  });

  test('不变量 C 无孤儿', () => {
    const r = coverage(commands, components);
    assert.equal(r.ok, true);
  });

  test('「移动端不适用」的 4 条显式记账且带理由（承认缺口 ≠ 掩盖）', () => {
    const na = components.notApplicable ?? {};
    // 2026-10-05：/clear 移出（它在触屏有语义，而且真的实现了）—— 那次只更新了 HostTest
    // 的断言（4），漏了本文件（仍写 5），于是这套 npm test 长期红着。2026-10-06 两处对齐。
    assert.equal(Object.keys(na).length, 4);
    for (const [cmd, reason] of Object.entries(na)) {
      assert.ok(cmd.startsWith('/'), `${cmd} 必须是命令`);
      assert.ok(typeof reason === 'string' && reason.length >= 4, `${cmd} 必须给理由`);
    }
    assert.ok(na['/pager'] && na['/scroll'] && na['/cd'] && na['/exit']);
    assert.ok(!na['/clear'], '/clear 已移出「不适用」（它在触屏有语义）');
  });

  test('反例：造一个没有命令来源的按钮 → 必须变红', () => {
    const tampered = structuredClone(components);
    tampered.surfaces.topbar.items.push('/does-not-exist');
    const r = coverage(commands, tampered);
    assert.equal(r.ok, false);
    assert.ok(r.orphansInUi.includes('/does-not-exist'));
  });

  test('反例：藏掉一条命令的落点 → 必须变红', () => {
    const tampered = structuredClone(components);
    const idx = tampered.surfaces.topbar.items.indexOf('/status');
    tampered.surfaces.topbar.items.splice(idx, 1);
    const r = coverage(commands, tampered);
    assert.equal(r.ok, false);
    assert.ok(r.orphansInCommands.includes('/status'));
  });
});

// ---------------------------------------------------------------- 靶子 5

describe('靶子 5 · 界面风格（与背景主题正交的那一维）', () => {
    test('四套风格齐全，顺序与名字都钉住', () => {
    assert.equal(STYLES.length, 4, '四套界面风格（含默认的初雪）');
      assert.deepEqual(STYLES.map((s) => s.id), ['yuki', 'modern', 'pixel', 'ancient']);
    assert.deepEqual(STYLES.map((s) => s.name), ['初雪', '现代化', '像素风', '古风']);
  });

  test('三套必须看得出差别，否则就是三个空名字', () => {
    const m = STYLES.find((s) => s.id === 'modern');
    const p = STYLES.find((s) => s.id === 'pixel');
    const a = STYLES.find((s) => s.id === 'ancient');
    assert.equal(p.radiusDp, 0, '像素风方角');
    assert.ok(p.borderWidthDp >= 2, '像素风硬描边');
    assert.equal(p.font, 'mono', '像素风等宽字');
    assert.equal(m.borderWidthDp, 0, '现代化不描边');
    assert.equal(m.font, 'sans', '现代化无衬线');
    assert.equal(a.font, 'serif', '古风衬线');
    assert.ok(m.radiusDp > a.radiusDp, '现代化比古风更圆');
  });

  test('风格与颜色互不干扰（正交性）', () => {
    const base = normalize({ ...DEFAULT_CFG });
    const px = normalize({ ...base, style: 'pixel' });
    assert.equal(px.style, 'pixel');
    assert.equal(px.preset, base.preset, '换风格不动背景主题');
    assert.equal(px.accent, base.accent, '换风格不动主色');
      assert.equal(normalize({ ...base, preset: 'night' }).style, DEFAULT_CFG.style, '换主题不动风格');
  });

  test('形状 token 确实随风格变', () => {
    const m = tokens({ ...DEFAULT_CFG, style: 'modern' });
    const p = tokens({ ...DEFAULT_CFG, style: 'pixel' });
    assert.equal(m.radius, 20);
    assert.equal(m.hairWidth, 0, '现代化描边宽度就是 0（真的不画线）');
    assert.equal(m.font, 'sans');
    assert.equal(p.radius, 0);
    assert.ok(p.hairWidth >= 2);
    assert.equal(p.font, 'mono');
  });

  test('未知风格回落现代化（不崩、不空）', () => {
      assert.equal(normalize({ style: 'nope' }).style, DEFAULT_CFG.style);
      assert.equal(normalize(null).style, DEFAULT_CFG.style);
  });
});


// ---------------------------------------------------------------- 靶子 4

describe('靶子 4 · 换外观只改外观，不改结构（不变量 B）', () => {
  test('6 套预设渲染出的槽位顺序完全一致', () => {
    const base = renderTree(SESSION_SCREEN, cfg()).map((n) => n.slot);
    for (const p of PRESETS) {
      const slots = renderTree(SESSION_SCREEN, cfg({ preset: p.id })).map((n) => n.slot);
      assert.deepEqual(slots, base, `预设「${p.name}」改变了结构`);
    }
  });

  test('结构字段（pin / fold）逐字透传', () => {
    const base = renderTree(SESSION_SCREEN, cfg());
    for (const p of PRESETS) {
      const nodes = renderTree(SESSION_SCREEN, cfg({ preset: p.id, accent: 'rose' }));
      assert.equal(nodes.length, base.length);
      nodes.forEach((n, i) => {
        assert.equal(n.slot, base[i].slot);
        assert.equal(n.pin, base[i].pin);
        assert.equal(n.fold, base[i].fold);
      });
    }
  });

  test('换外观确实改变了样式（否则外观系统是摆设）', () => {
    const a = JSON.stringify(renderTree(SESSION_SCREEN, cfg({ preset: 'clear' })));
    const b = JSON.stringify(renderTree(SESSION_SCREEN, cfg({ preset: 'night' })));
    const c = JSON.stringify(renderTree(SESSION_SCREEN, cfg({ preset: 'clear', accent: 'amber' })));
    assert.notEqual(a, b, '不同预设必须产出不同样式');
    assert.notEqual(a, c, '不同主色必须产出不同样式');
  });

  test('反例：任何外观都不许悄悄丢掉答案槽位（保护不变量 A）', () => {
    for (const p of PRESETS) {
      for (const a of ACCENTS) {
        const slots = renderTree(SESSION_SCREEN, cfg({ preset: p.id, accent: a.id })).map((n) => n.slot);
        assert.ok(slots.includes('answer'), `外观「${p.id}/${a.id}」弄丢了答案槽位`);
      }
    }
  });
});
