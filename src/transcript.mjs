/**
 * 硬不变量 A：答案永远在本轮最底。
 *
 * 一轮 = 〔过程〕+〔结果〕。过程（思考、工具、感知）可以长、可以折；
 * 结果（答案）必须在这一轮的最底 —— 用户不需要往回找。
 *
 * 输入是天枢的 Stream-JSON 事件序列（见 docs/headless-stream-json.md），
 * 输出是本轮的**显示块列表**，保证：
 *   1. 有文本时，最后一块一定是 answer；
 *   2. 一轮里最多一个 answer；
 *   3. 最后一段「连续」文本才是答案，之前穿插的文本并入活动区（过程说明）；
 *   4. 全程没有文本时，不产生空 answer 块。
 */

/** 归入活动区的事件类型 */
const ACTIVITY = new Set([
  'thinking_delta',
  'tool_use',
  'tool_result',
  'phase',
  'worker',
  'error',
]);

/**
 * @param {Array<{type: string} & Record<string, any>>} events
 * @returns {{ blocks: Array<object>, counts: {thinking: number, tools: number, workers: number} }}
 */
export function parseTurn(events) {
  const thinkingSegments = [];
  const tools = [];
  const phases = [];
  const workers = [];
  const errors = [];
  /** 连续文本段：被任何非 text_delta 事件打断就开新段 */
  const textRuns = [];

  let openThinking = null;
  let openText = null;

  const closeThinking = () => {
    openThinking = null;
  };
  const closeText = () => {
    openText = null;
  };
  const closeAll = () => {
    closeThinking();
    closeText();
  };

  for (const e of events ?? []) {
    const t = e?.type;
    if (t === 'text_delta') {
      closeThinking();
      if (openText === null) {
        openText = { text: '' };
        textRuns.push(openText);
      }
      openText.text += e.text ?? '';
      continue;
    }
    // 任何非 text_delta 都终止当前文本段
    closeText();

    if (t === 'thinking_delta') {
      if (openThinking === null) {
        openThinking = { text: '' };
        thinkingSegments.push(openThinking);
      }
      openThinking.text += e.text ?? '';
    } else if (t === 'tool_use') {
      closeThinking();
      tools.push({
        id: e.id,
        name: e.name,
        input: e.input ?? null,
        result: null,
        isError: false,
        truncated: false,
      });
    } else if (t === 'tool_result') {
      closeThinking();
      const hit = tools.find((x) => x.id === e.id);
      const rec = {
        id: e.id,
        name: e.name,
        input: hit?.input ?? null,
        result: e.result ?? '',
        isError: !!e.isError,
        truncated: !!e.truncated,
      };
      if (hit) Object.assign(hit, rec);
      else tools.push(rec);
    } else if (t === 'phase') {
      closeThinking();
      phases.push({ phase: e.phase, tool: e.tool ?? null, reason: e.reason ?? null });
    } else if (t === 'worker') {
      closeThinking();
      workers.push(e);
    } else if (t === 'error') {
      closeThinking();
      errors.push({ error: String(e.error ?? '') });
    } else {
      // turn_complete / result / system / 未知类型：只作为分段边界
      closeThinking();
    }
  }
  closeAll();

  // 最后一段**非空**文本才是答案
  const last = textRuns.length ? textRuns[textRuns.length - 1] : null;
  const answerText = last && last.text.trim() !== '' ? last.text : null;
  const noteRuns = answerText ? textRuns.slice(0, -1) : textRuns;

  const counts = {
    thinking: thinkingSegments.length,
    tools: tools.length,
    workers: workers.length,
  };

  const hasActivity =
    counts.thinking > 0 ||
    counts.tools > 0 ||
    counts.workers > 0 ||
    phases.length > 0 ||
    errors.length > 0 ||
    noteRuns.length > 0;

  const blocks = [];
  if (hasActivity) {
    blocks.push({
      kind: 'activity',
      collapsed: true,
      counts,
      children: [
        ...thinkingSegments.map((s) => ({ kind: 'thinking', text: s.text })),
        ...noteRuns.map((r) => ({ kind: 'note', text: r.text })),
        ...tools.map((t) => ({ kind: 'tool', ...t })),
        ...phases.map((p) => ({ kind: 'phase', ...p })),
        ...workers.map((w) => ({ kind: 'worker', ...w })),
        ...errors.map((e) => ({ kind: 'error', ...e })),
      ],
    });
  }

  // 关键：答案永远追加在最后
  if (answerText !== null) {
    blocks.push({ kind: 'answer', text: answerText });
  }

  return { blocks, counts };
}

/**
 * 打开这一轮时无需滚动就能看到的块。
 * 吸底语义：视口对齐到底部，所以能看到的是末尾 capacity 个块。
 */
export function visibleOnOpen(blocks, capacity) {
  if (!Array.isArray(blocks) || capacity <= 0) return [];
  return blocks.slice(Math.max(0, blocks.length - capacity));
}

/**
 * 吸底决策：默认吸底；用户上滑后停止吸底（不跟他抢），并浮出「↓ 回到最新」。
 * @param {{atBottom: boolean, hasNewAnswer: boolean}} s
 */
export function scrollPolicy(s) {
  const stuck = s.atBottom === true;
  return {
    autoScroll: stuck,
    showJumpButton: !stuck,
    highlightJump: !stuck && s.hasNewAnswer === true,
  };
}

export const ACTIVITY_TYPES = ACTIVITY;
