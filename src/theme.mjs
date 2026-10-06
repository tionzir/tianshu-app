/**
 * 外观系统的**纯逻辑**层 —— 离线靶子，不碰 DOM / Android，能在 node 里直接测。
 *
 * 参照物是「售后助手」那套外观系统（用户指定：
 * /storage/emulated/0/oa-hot/www/theme-core.js）。分工与那边一致：
 *   本文件只管**算**（配色求解 + 护栏）；贴到界面是 Theming.java 的事。
 *
 * 为什么配色要"算"而不是写死：用户一旦能自定义背景，卡片色、次要文字色、
 * 分隔线、主色上的字色就全都得跟着推 —— 写死的那份在深色底上会变成
 * "深字压深底"（看不见）或"白卡贴白底"（没层次）。这些计算必须可断言，
 * 否则只能靠肉眼在真机上一个个试。
 *
 * 与 Java 侧 Theme.java 是**逐条对应**的关系（这是本项目的惯例：靶子先 GREEN，
 * 再移植），所以两边的函数名、阈值、遍历顺序都要保持一致。
 */

// ---------------------------------------------------------------- 色值

const HEX3 = /^#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$/;
const HEX6 = /^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$/;

/** "#abc" / "#aabbcc" → [r,g,b]；不认的一律 null（不猜）。 */
export function rgb(h) {
  if (typeof h !== 'string') return null;
  const s = h.trim();
  let m = HEX6.exec(s);
  if (m) return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
  m = HEX3.exec(s);
  if (m) return [parseInt(m[1] + m[1], 16), parseInt(m[2] + m[2], 16), parseInt(m[3] + m[3], 16)];
  return null;
}

export function isHex(h) {
  return rgb(h) !== null;
}

function clamp(n) {
  return n < 0 ? 0 : n > 255 ? 255 : n;
}

export function hex(r, g, b) {
  const p = (v) => {
    const s = clamp(Math.round(v)).toString(16);
    return s.length < 2 ? '0' + s : s;
  };
  return '#' + p(r) + p(g) + p(b);
}

/** 统一成小写 6 位色（"#ABC" → "#aabbcc"）；非法输入原样返回。 */
export function normHex(h) {
  const c = rgb(h);
  return c ? hex(c[0], c[1], c[2]) : h;
}

/** 线性混色：t=0 得 a，t=1 得 b。整套派生色（soft/ink/line）的基石。 */
export function mix(a, b, t) {
  const A = rgb(a) || [0, 0, 0];
  const B = rgb(b) || [0, 0, 0];
  return hex(A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t);
}

/** 带透明度的同色（半透明卡片 / 遮罩用），返回 CSS 的 rgba() 文本。 */
export function alpha(h, a) {
  const c = rgb(h) || [0, 0, 0];
  return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a + ')';
}

/** WCAG 相对亮度（0 黑，1 白）。 */
export function lum(h) {
  const c = rgb(h);
  if (!c) return 0;
  const f = (v) => {
    v = v / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
}

/** WCAG 对比度（1:1 ～ 21:1），保留两位小数 —— 断言直接比这个数。 */
export function contrast(a, b) {
  const l1 = lum(a);
  const l2 = lum(b);
  const hi = Math.max(l1, l2);
  const lo = Math.min(l1, l2);
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100;
}

/** 这个底色算不算"暗" —— 决定用浅字还是深字。阈值 0.35 落在中间调里。 */
export function isDark(h) {
  return lum(h) < 0.35;
}

// ---------------------------------------------------------------- 预设与主色

/**
 * 背景预设。每套自带一整套中性色（bg/bg2/card/fg/sub/line）。
 * `mode` 是**明暗语义**，后面的派生（soft/ink/边框）都按它分支 ——
 * 不能只看背景色的亮度：暗夜和深海都是深色，但卡片要偏各自的底色。
 */
export const PRESETS = [
  // 首位即默认。「初雪」照参考 App（Yuki 初雪）的扁平风：浅灰底 + 纯白卡 + 极浅分隔线。
  { id: 'yuki', name: '初雪', hint: '默认 · 扁平浅灰（照参考 App）', mode: 'light',
    bg: '#f5f7fa', bg2: '#e9f0fb', card: '#ffffff', fg: '#1b1d22', sub: '#8a8f99', line: '#e6e8ec' },
  { id: 'clear', name: '清蓝', hint: '冷调浅色', mode: 'light',
    bg: '#f4f6fa', bg2: '#e7eef9', card: '#ffffff', fg: '#1b1f27', sub: '#5d6675', line: '#e6e9f0' },
  { id: 'warm', name: '暖阳', hint: '米黄 · 久看不刺眼', mode: 'light',
    bg: '#faf5ed', bg2: '#f2e7d8', card: '#fffdf9', fg: '#2b2419', sub: '#6d6151', line: '#ece2d2' },
  { id: 'leaf', name: '护眼', hint: '浅绿 · 柔', mode: 'light',
    bg: '#eef4ef', bg2: '#dfeae1', card: '#ffffff', fg: '#1c2620', sub: '#5b6a60', line: '#dfe9e1' },
  { id: 'dusk', name: '暮色', hint: '浅紫灰 · 安静', mode: 'light',
    bg: '#f5f4fb', bg2: '#e9e6f8', card: '#ffffff', fg: '#23222b', sub: '#64627a', line: '#e8e6f2' },
  { id: 'night', name: '暗夜', hint: '深灰 · 关灯用', mode: 'dark',
    bg: '#12151b', bg2: '#1a2029', card: '#1c212a', fg: '#e9edf4', sub: '#98a3b3', line: '#2b313c' },
  { id: 'deep', name: '深海', hint: '深蓝 · 关灯用', mode: 'dark',
    bg: '#0d1a22', bg2: '#132936', card: '#15232d', fg: '#e6f0f6', sub: '#8fa6b3', line: '#22333f' },
];

/** 主色：只存三个基准色（主、渐变亮端、浅色模式下的深字色），其余派生。 */
export const ACCENTS = [
  // 首位即默认（参考里用户明确否掉了紫色：「别用紫色，很降档次」→ 纯蓝 #2F6BFF）。
  { id: 'yuki', name: '纯蓝', pri: '#2f6bff', pri2: '#6e9bff', deep: '#1b4fd8' },
  { id: 'blue', name: '蓝', pri: '#2b6cf0', pri2: '#5b8def', deep: '#1e52bf' },
  { id: 'cyan', name: '青', pri: '#0e93a8', pri2: '#3ab4c4', deep: '#0a7182' },
  { id: 'green', name: '绿', pri: '#12a05c', pri2: '#3cc07f', deep: '#0b7a45' },
  { id: 'violet', name: '紫', pri: '#7b5cf0', pri2: '#8a6ef0', deep: '#5b3fd0' },
  { id: 'amber', name: '橙', pri: '#e07819', pri2: '#f09b45', deep: '#b45c0e' },
  { id: 'rose', name: '玫', pri: '#e0486e', pri2: '#f07292', deep: '#b32c50' },
  { id: 'slate', name: '墨', pri: '#4a5568', pri2: '#6b7787', deep: '#333c4a' },
];

// 语义色是固定的：它们表达"成功/失败/警告"，不该跟着审美漂。
const OK = '#0fa968';
const DANGER = '#d92d33';
const WARN = '#c98a00';

const INK_DARK = '#1b1f27';   // 深字：浅底上用它
const INK_LIGHT = '#e9edf4';  // 浅字：深底上用它
const INK_ON_BRIGHT = '#101418';

const MIN_CARD_CONTRAST = 7;    // 卡片上的正文（WCAG AA 是 4.5，正文留一倍余量）
const MIN_BASE_CONTRAST = 4.5;  // 直接落在页面底色上的文字
const MIN_BIG_CONTRAST = 3;     // 大字（标题/金额一类装饰性收尾）
const MIN_SEPARATION = 0.02;    // 卡片与底色的亮度差 —— 低于这个数「卡片」就不成形了

export const CARD_ALPHA_MIN = 0.1;
export const CARD_ALPHA_MAX = 1;
export const DEFAULT_BG = '#f4f6fa';

export const DEFAULT_CFG = {
  v: 1,
  preset: 'yuki',
  accent: 'yuki',
  style: 'yuki',
  custom: { bg: DEFAULT_BG, bg2: '' },
  image: '',
  cardAlpha: 1,
};

/**
 * 界面风格 —— 与"背景主题"是**两个正交维度**：
 *   背景主题回答"什么颜色"，界面风格回答"什么形状/字体/质感"。
 * 上一版把这一维整个删掉了（只留颜色），是需求做窄了 —— 用户要的
 * "像素风 / 现代化 / 古风"属于这里。
 */
export const STYLES = [
  // 首位即默认。「初雪」= 参考那套卡片材质：16 圆角 + 极细描边 + 极轻柔和投影（不用渐变）。
  { id: 'yuki', name: '初雪', hint: '扁平 · 16 圆角 · 极细边 · 轻投影',
    radiusDp: 16, borderWidthDp: 1, font: 'sans', shadowDp: 3, offsetDp: 0,
    letterSpacing: 0, markerDp: 3, decor: 'none', titleRule: false },
  { id: 'modern', name: '现代化', hint: '大圆角 · 无边框 · 柔和投影',
    radiusDp: 20, borderWidthDp: 0, font: 'sans', shadowDp: 4, offsetDp: 0,
    letterSpacing: 0, markerDp: 3, decor: 'none', titleRule: false },
  { id: 'pixel', name: '像素风', hint: '方角 · 粗硬边 · 错位阴影',
    radiusDp: 0, borderWidthDp: 3, font: 'mono', shadowDp: 0, offsetDp: 3,
    letterSpacing: 0.08, markerDp: 6, decor: 'grid', titleRule: false },
  { id: 'ancient', name: '古风', hint: '细边 · 四角界格 · 疏朗字距',
    radiusDp: 4, borderWidthDp: 2, font: 'serif', shadowDp: 1, offsetDp: 0,
    letterSpacing: 0.05, markerDp: 4, decor: 'corner', titleRule: true },
];

export function styleById(id) {
  for (const s of STYLES) if (s.id === id) return s;
  return null;
}

export function presetById(id) {
  for (const p of PRESETS) if (p.id === id) return p;
  return null;
}

export function accentById(id) {
  for (const a of ACCENTS) if (a.id === id) return a;
  return null;
}

// ---------------------------------------------------------------- 自定义底色求解

/**
 * 给定一个自定义底色，**解**出「卡片色 + 文字色 + 明暗」。
 *
 * 为什么是解而不是定规则：中灰底是自定义配色的陷阱 —— 白卡配深字、灰卡配浅字，
 * 两边都够不着 4.5:1。按"亮底白卡 / 暗底深卡"的死规则做，用户选个中灰就是一片看不清。
 *
 * 两段式：
 *   ① 先找**完全解** —— 卡片上 ≥7:1 且底色上 ≥4.5:1 且卡与底能分开。
 *      取第一个（遍历顺序即偏好：卡比底亮 + 深字排最前，最像常规浅色界面）。
 *   ② 完全解不存在时（只有 #6f6f6f～#828282 这一小段灰阶会这样：**任何**字色在那种
 *      底色上都到不了 4.5:1，这是物理事实不是实现问题），退回**次优解** ——
 *      仍满足卡片上的判据，底色上的文字取两种字色里对比更高的那个。
 *      返回 ok:false 让界面能如实提示用户。
 */
export function solveCustom(bg) {
  const cards = [];
  for (let k = 0.06; k <= 0.99; k += 0.06) cards.push({ c: mix(bg, '#ffffff', k), above: true });
  for (let k = 0.06; k <= 0.66; k += 0.06) cards.push({ c: mix(bg, '#000000', k), above: false });

  let best = null;
  for (const entry of cards) {
    const card = entry.c;
    if (Math.abs(lum(card) - lum(bg)) < MIN_SEPARATION) continue;
    const inks = entry.above ? [INK_DARK, INK_LIGHT] : [INK_LIGHT, INK_DARK];
    for (const ink of inks) {
      if (contrast(ink, card) < MIN_CARD_CONTRAST) continue;
      const base = contrast(ink, bg);
      if (base >= MIN_BASE_CONTRAST) {
        return { card, fg: ink, dark: ink === INK_LIGHT, ok: true, baseContrast: base };
      }
      if (!best || base > best.baseContrast) {
        best = { card, fg: ink, dark: ink === INK_LIGHT, ok: false, baseContrast: base };
      }
    }
  }
  // 兜底：理论上到不了这里（卡片判据总能满足），但界面绝不能因为配色解不出来就崩
  return best || {
    card: mix(bg, '#ffffff', 0.5), fg: INK_DARK, dark: false,
    ok: false, baseContrast: contrast(INK_DARK, bg),
  };
}

/** 次要文字色：在卡片上对比达标的**最深**那个（越浅越"灰"越好看，但不达标就往下压）。 */
export function readableSub(card, fg) {
  for (let t = 0.55; t <= 0.94; t += 0.04) {
    const s = mix(card, fg, t);
    if (contrast(s, card) >= MIN_BASE_CONTRAST) return s;
  }
  return fg; // 兜底：直接用正文色，对比必然最高
}

/**
 * 用户自定义底色 → 凑出一整套中性色。
 *
 * 卡色与字色由 solveCustom 解出；次要文字与分隔线都从卡色派生
 * （跟卡色同调、且保证对比），而不是拿一套写死的灰去套 ——
 * 写死的灰在暖色底上会发脏，在中灰卡上会糊成一片。
 */
export function customPreset(c) {
  const bg = isHex(c && c.bg) ? normHex(c.bg) : DEFAULT_BG;
  const s = solveCustom(bg);
  const dark = s.dark;
  return {
    id: 'custom', name: '自定义', hint: '你选的颜色', mode: dark ? 'dark' : 'light',
    bg,
    // 没给渐变第二色时，用底色自己微调一档：纯色底也有一点层次，不至于"死平"
    bg2: isHex(c && c.bg2) ? normHex(c.bg2) : mix(bg, dark ? '#ffffff' : '#000000', dark ? 0.06 : 0.04),
    card: s.card,
    fg: s.fg,
    sub: readableSub(s.card, s.fg),
    line: dark ? mix(bg, '#ffffff', 0.12) : mix(bg, '#000000', 0.09),
    // 给界面用：完全解没找到时，底色上的小字会略吃力（中灰底的物理限制），如实提示
    ok: s.ok,
    baseContrast: s.baseContrast,
  };
}

/**
 * 把 base 朝 target 推，直到它与**每一个**底色都达到对比度要求。
 *
 * 为什么不能写死比例：派生色以前是"混 30% 黑"这样的定值 —— 在预设那几套底色上是
 * 调好了的，可底色一旦由用户给（深蓝 #204060 这种既不算亮也不算很黑的），同一个
 * 定值就掉到 3.8:1、3.3:1，按钮字和链接就发灰发虚。这里改成推到够为止：
 * 目标色（黑或白）一定能达标，所以循环必然收敛；返回最靠近 base 的那个合格色，
 * 尽量保留主色的性格。
 */
export function towards(base, target, ons, min) {
  const list = [].concat(ons);
  for (let t = 0; t <= 1.0001; t += 0.04) {
    const c = mix(base, target, t);
    let ok = true;
    for (const on of list) {
      if (contrast(c, on) < min) { ok = false; break; }
    }
    if (ok) return c;
  }
  return target;
}

/**
 * 主色上该写什么颜色的字 —— 不能一律白色。
 *
 * 橙、青、绿这几个主色上写白字只有 3～3.7:1（按钮上的文字不够看），
 * 换成近黑反而有 5～6:1。所以按对比度**选**，而不是按手感定死白色。
 */
export function onColor(pri, pri2) {
  // 渐变块上的字要照顾**两端**：主色端按正文要求，亮端是装饰性的渐变收尾，按 3 算
  const fits = (ink, min) =>
    contrast(ink, pri) >= min && (!pri2 || contrast(ink, pri2) >= Math.min(min, 3));
  if (fits('#ffffff', MIN_BASE_CONTRAST)) return '#ffffff';
  if (fits(INK_ON_BRIGHT, MIN_BASE_CONTRAST)) return INK_ON_BRIGHT;
  return '#ffffff'; // 兜底：白字最不容易出错
}

// ---------------------------------------------------------------- 配置

/**
 * 卡片不透明度的取值。
 *
 * 夹取，而不是"越界就回默认" —— 手改配置写个 0.75 要能用。
 * 只有**压根不是数**（null / 空串 / 对象 / 乱写）才回 1：那是升级前的样子，
 * 不能因为配置里多了个看不懂的值，就让所有人的界面突然变透明。
 */
export function cardAlphaOf(v) {
  const n = typeof v === 'number' ? v
    : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
  if (!isFinite(n)) return CARD_ALPHA_MAX;
  if (n < CARD_ALPHA_MIN) return CARD_ALPHA_MIN;
  if (n > CARD_ALPHA_MAX) return CARD_ALPHA_MAX;
  return n;
}

/**
 * 背景图路径的合法性。
 *
 * 只认"以 / 开头的绝对路径"，且路径里不许出现会破坏布局/存储的字符。
 * 配置文件是用户能手改的，放任引号、括号、反斜杠进来等于开了个口子。
 */
export function isBgPath(s) {
  return typeof s === 'string' && s.length > 1 && s.charAt(0) === '/' &&
    !/["'()\\\n\r]/.test(s);
}

/**
 * 任何输入 → 合法配置。**永不抛异常**（配置文件被手改坏是常态，
 * 那种时候界面必须照常打开，回落默认即可）。
 */
export function normalize(raw) {
  const r = raw && typeof raw === 'object' ? raw : {};
  const preset = r.preset === 'custom' ? 'custom'
    : presetById(r.preset) ? r.preset : DEFAULT_CFG.preset;
  const accent = accentById(r.accent) ? r.accent : DEFAULT_CFG.accent;
  const style = styleById(r.style) ? r.style : DEFAULT_CFG.style;
  const c = r.custom && typeof r.custom === 'object' ? r.custom : {};
  return {
    v: 1,
    preset,
    accent,
    style,
    custom: {
      bg: isHex(c.bg) ? normHex(c.bg) : DEFAULT_CFG.custom.bg,
      // 空串 = 用户没要渐变，这是合法值（不是错误），所以不能塌成默认色
      bg2: c.bg2 === '' || c.bg2 == null ? '' : isHex(c.bg2) ? normHex(c.bg2) : '',
    },
    image: isBgPath(r.image) ? r.image : '',   // 空串 = 不用图片（合法值）
    cardAlpha: cardAlphaOf(r.cardAlpha),
  };
}

/** 配置里生效的那套中性色（预设或自定义派生）。 */
export function palette(cfg) {
  const c = normalize(cfg);
  return c.preset === 'custom' ? customPreset(c.custom) : presetById(c.preset);
}

// ---------------------------------------------------------------- token 表

/**
 * 配置 → 整套界面 token。
 *
 * 形状那一组（圆角 / 描边 / 字体 / 阴影）由**界面风格**决定（见 STYLES）——
 * 不再是写死的一套；颜色那一组由背景主题与主色决定。两者正交。
 *
 * 卡片透明度那块是重点：卡片里的字有**两个**可能落在的底色，必须两头都站得住 ——
 *   ① cardSolid —— 卡片自己那层（字主要压在它上面）；
 *   ② bg        —— 卡片一透，底色/照片就透上来。
 * 只满足其中一头都会出事：只顾卡片，透光后字会糊在底色上；只顾底色，深色主题下
 * 字会被推得太亮，反而压不住卡片本身。所以把两个底一起交给 towards 去解。
 */
export function tokens(cfg) {
  const c = normalize(cfg);
  const p = palette(c);
  const a = accentById(c.accent);
  const dark = p.mode === 'dark';
  const cardAlpha = c.cardAlpha;
  const st = styleById(c.style) || STYLES[0];

  const t = {};
  t.styleId = st.id;
  t.styleName = st.name;
  t.font = st.font;                                    // sans / mono / serif
  t.radius = st.radiusDp;                              // 卡片与按钮的圆角
  t.radiusLg = st.radiusDp;
  t.radiusXs = Math.max(0, st.radiusDp - 6);           // 小件（输入框/色块）比卡片再收一点
  t.hairWidth = st.borderWidthDp;                      // 0 = 不描边
  t.shadow = st.shadowDp;
  t.markerDp = st.markerDp;                            // 底栏选中标记的厚度
  t.decor = st.decor;                                  // none / grid / corner
  t.titleRule = st.titleRule;                          // 古风标题前的题签竖线
  t.offsetDp = st.offsetDp;                            // 像素风错位硬阴影
  t.letterSpacing = st.letterSpacing;                  // 字距（中文唯一有效的字体气质维度）
  t.mode = p.mode;
  t.preset = c.preset;
  t.accent = c.accent;
  t.presetName = p.name;

  t.bg = p.bg;
  t.bg2 = p.bg2;
  t.line = p.line;
  t.hair = dark ? 'rgba(255,255,255,.07)' : 'rgba(16,24,40,.05)';

  // ---- 卡片透明度 ----
  const cardSolid = cardAlpha >= 1 ? p.card : mix(p.card, p.bg, 1 - cardAlpha);
  const fgTo = dark ? '#ffffff' : '#000000';
  const textBases = cardAlpha >= 1 ? p.card : [cardSolid, p.bg];

  t.card = p.card;
  t.cardSolid = cardSolid;
  t.cardAlpha = cardAlpha;
  t.fg = towards(p.fg, fgTo, textBases, MIN_BASE_CONTRAST);
  t.sub = towards(p.sub, fgTo, textBases, MIN_BASE_CONTRAST);

  // 输入框 / 日志底要比卡片更实一档：同样透明的话它们跟卡片一个色，
  // 就看不出"这里是个框"了。+0.30 封顶到 1。
  const innerAlpha = Math.min(1, cardAlpha + 0.3);
  const innerField = dark ? mix(p.card, p.bg, 0.35) : p.card;
  const innerLog = dark ? mix(p.card, '#000000', 0.18) : '#fafbfd';
  t.fieldBg = cardAlpha >= 1 ? innerField : alpha(innerField, innerAlpha);
  t.logBg = cardAlpha >= 1 ? innerLog : alpha(innerLog, innerAlpha);
  t.placeholder = dark ? mix(p.sub, p.bg, 0.35) : '#9aa3b2';

  // 直接落在页面底色上的文字（页面级提示、底部那行）：
  // 次要色够看就用次要色，不够就升到正文色 —— 底色的对比是有物理上限的（见 solveCustom）
  t.noteFg = contrast(p.sub, p.bg) >= MIN_BASE_CONTRAST ? p.sub : p.fg;

  // ---- 主色及其派生（一律现算，不写死）----
  t.pri = a.pri;
  t.pri2 = a.pri2;
  t.priSoft = dark ? mix(a.pri, p.bg, 0.78) : mix(a.pri, '#ffffff', 0.9);
  t.priInk = towards(a.pri, fgTo, t.priSoft, MIN_BASE_CONTRAST);
  t.priDeep = towards(a.pri, '#000000', '#ffffff', MIN_BASE_CONTRAST);
  t.onPri = onColor(a.pri, a.pri2);
  t.grad = a.pri + ',' + a.pri2;   // 界面侧据此画渐变

  // ---- 语义色 ----
  const sem = (base) => {
    const soft = dark ? mix(base, p.bg, 0.8) : mix(base, '#ffffff', 0.88);
    return { soft, ink: towards(base, fgTo, soft, MIN_BASE_CONTRAST) };
  };
  const ok = sem(OK);
  const dg = sem(DANGER);
  const wn = sem(WARN);
  t.ok = OK; t.okSoft = ok.soft; t.okInk = ok.ink;
  t.danger = DANGER; t.dangerSoft = dg.soft; t.dangerInk = dg.ink;
  t.warn = WARN; t.warnSoft = wn.soft; t.warnInk = wn.ink;
  t.onDanger = onColor(DANGER);

  // ---- 背景图与遮罩 ----
  t.image = c.image;
  t.scrim = alpha(p.bg, 0.76);   // 图片上压一层底色遮罩，照片再花字也读得清

  return t;
}

/**
 * 护栏：对**任意**配置检查对比度是否达标。空清单 = 通过。
 *
 * 求解器（solveCustom/towards）本身就以保证达标为目标，但"以为保证了"和
 * "确实保证了"是两回事 —— 这个函数是把它证出来的那一个，测试拿它跑遍
 * 所有预设 × 主色 × 透明度 × 一批自定义底的组合。
 */
export function audit(cfg) {
  const c = normalize(cfg);
  const t = tokens(c);
  const p = palette(c);
  const fails = [];
  const need = (label, fg, bg, min) => {
    if (!fg || !bg) { fails.push({ label, fg, bg, ratio: 0, min }); return; }
    const r = contrast(fg, bg);
    if (r + 1e-9 < min) fails.push({ label, fg, bg, ratio: r, min });
  };

  // 卡片上的正文与次要文字（这是最常见、也最该保证的一处）
  need('fg on card', t.fg, t.cardSolid, MIN_BASE_CONTRAST);
  need('sub on card', t.sub, t.cardSolid, MIN_BASE_CONTRAST);
  // 主色按钮 / 标签上的字
  need('onPri on pri', t.onPri, t.pri, MIN_BASE_CONTRAST);
  // 直接落在底色上的文字
  need('noteFg on bg', t.noteFg, p.bg, MIN_BASE_CONTRAST);
  // 语义色 ink 落在自己的 soft 底上
  need('okInk on okSoft', t.okInk, t.okSoft, MIN_BASE_CONTRAST);
  need('dangerInk on dangerSoft', t.dangerInk, t.dangerSoft, MIN_BASE_CONTRAST);

  // 注：这里**不**断言"卡片与底色要能分开"。那是美感不是可读性 ——
  // 深色主题（暗夜/深海）的卡片与底色本来就贴得很近，靠描边分界；
  // 低透明度下更是用户自己的选择。MIN_SEPARATION 只在 solveCustom
  // 内部用来筛掉"卡几乎等于底"的候选，不作为对外的硬门禁。
  return fails;
}

/** 人类可读的护栏报告。 */
export function auditReport(cfg) {
  const c = normalize(cfg);
  const f = audit(cfg);
  const head = `外观「${c.preset}/${c.accent}」alpha=${c.cardAlpha}`;
  if (f.length === 0) return head + ' 护栏 GREEN';
  return head + ' 护栏 RED\n' + f
    .map((x) => `  ${x.label} ${x.ratio.toFixed ? x.ratio.toFixed(2) : x.ratio} < ${x.min}`)
    .join('\n');
}

// ---------------------------------------------------------------- 结构不变量

/**
 * 会话详情页的槽位顺序 —— 这是**结构契约**。
 * 所有外观配置必须产出完全一样的槽位序列；换肤只允许改样式。
 */
export const SESSION_SCREEN = [
  { slot: 'topbar' },
  { slot: 'user', pin: 'top' },
  { slot: 'activity', fold: 'collapsed-by-default' },
  { slot: 'advisory', pin: 'stream' },
  { slot: 'gate', pin: 'stream' },
  { slot: 'answer', pin: 'bottom' },
  { slot: 'todo-bar', pin: 'above-composer' },
  { slot: 'composer', pin: 'bottom' },
];

/**
 * 把槽位树按配置渲染成节点列表。
 * 结构字段（slot / pin / fold）逐字透传；只有样式随配置变化。
 */
export function renderTree(screen, cfg) {
  const t = tokens(cfg);
  return screen.map((node) => ({
    slot: node.slot,
    pin: node.pin ?? null,
    fold: node.fold ?? null,
    style: {
      radius: t.radius,
      radiusLg: t.radiusLg,
      hairWidth: t.hairWidth,
      bg: t.bg,
      card: t.cardSolid,
      fg: t.fg,
      sub: t.sub,
      pri: t.pri,
      mode: t.mode,
    },
  }));
}

export const PRESETS_BY_ID = Object.fromEntries(PRESETS.map((p) => [p.id, p]));
export const ACCENTS_BY_ID = Object.fromEntries(ACCENTS.map((a) => [a.id, a]));
