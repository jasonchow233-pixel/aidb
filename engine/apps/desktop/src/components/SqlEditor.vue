<script setup lang="ts">
/**
 * ⭐⭐ SqlEditor —— 查询编辑器的内核。
 *
 * ⭐ 为什么是「透明 textarea 叠在高亮层上」而不是 contenteditable：
 *   1. **原生输入行为**（IME 中文输入、撤销栈、无障碍、选区、移动端键盘）
 *      contenteditable 全都要自己重写，而重写不好就是「中文打一半丢字」；
 *   2. 我们只缺「颜色」和「跳转」，不缺「编辑」。
 *   textarea 文字设为 transparent + caret-color 恢复，高亮层垫在底下。
 *
 * ⭐ 高亮层与 textarea 必须**逐字符对齐**，否则光标会偏。
 *   对齐靠三条铁律（改样式时必须一起改）：
 *     · 相同 font-family / font-size / line-height / letter-spacing
 *     · 相同 padding / border / tab-size
 *     · 相同 white-space 换行策略（都 pre-wrap）
 */
import { computed, nextTick, ref, watch } from "vue";
import {
  tokenize, highlightHtml, symbolAt, statementAtOffset, splitStatements,
  applyInsert, lineOf, type Statement, type SymbolRef,
} from "@/lib/sql";

const props = withDefaults(defineProps<{
  modelValue: string;
  /** 已知表名（小写）—— 只认这些，别的词不会被当成可跳转对象 */
  tables?: string[];
  /** 只读（危险语句、EXPLAIN 结果等场景） */
  readonly?: boolean;
  placeholder?: string;
  /**
   * ⭐ 表名（小写）→ 列名数组。用来认**裸列名**（`SELECT status FROM orders`）。
   *
   * ⚠ 缺了它 hover/跳转会认不出没有别名的列名 ——
   *   那是最常见的写法，缺了等于功能半残。
   */
  columns?: Record<string, string[]>;
  /**
   * ⭐ 元数据查表（父组件提供，因为只有它能调 API）。
   * 返回 `null` 表示「查不到」—— 此时提示只显示符号名与类型，**不编造**注释。
   * 返回字符串数组，第一行是标题，其余是补充（注释 / 枚举取值 / 类型）。
   */
  describe?: (ref: SymbolRef) => string[] | null;
}>(), { tables: () => [], readonly: false, placeholder: "输入 SQL…" });

const emit = defineEmits<{
  "update:modelValue": [string];
  /** ⌘⏎ 执行全部 */
  run: [];
  /** ⌘⇧⏎ 执行选中（或光标所在那条） */
  runSelection: [string];
  /** ⌘. 取消 */
  cancel: [];
  /** Ctrl+Click 命中的符号（父组件决定跳哪儿） */
  jump: [SymbolRef];
  /** 光标位置变化（父组件用来显示「当前语句」） */
  caret: [number];
}>();

const ta = ref<HTMLTextAreaElement | null>(null);
const hl = ref<HTMLElement | null>(null);
const gutter = ref<HTMLElement | null>(null);
const scrollTop = ref(0);
const focused = ref(false);
/** 当前行（1 基） */
const curLine = ref(1);
/** hover 中的符号（用于加下划线） */
const hoverRef = ref<SymbolRef | null>(null);
/** ⭐ 150ms 后才出 tooltip：鼠标扫过一串字符不该弹一堆提示 */
const tip = ref<{ ref: SymbolRef; x: number; y: number } | null>(null);
let tipTimer: number | undefined;

/** ⭐ 大文件降级阈值（UI-SPEC §QueryEditor）：超过就关掉语言解析 */
const BIG_FILE_LINES = 5000;

// ⚠ hoverRange / degraded 定义在下方，这里引用是安全的：
//   computed 的 getter 是**惰性**的，跑到它们之前不会求值。
//
// ⭐⭐ **降级必须真的不渲染**，不能只是「显示一句提示」。
//   之前只给 textarea 加了个 class，`<pre>` 里照样铺了 5000 行 × N 个 token ——
//   DOM 节点五万往上，页面直接卡住。而 UI 上写着「已关闭语法高亮」，
//   **说的和做的相反**，比不说更糟。
const tokens = computed(() => (degraded.value ? [] : tokenize(props.modelValue)));
const html = computed(() =>
  degraded.value ? "" : highlightHtml(props.modelValue, tokens.value, hoverRange.value),
);
const lines = computed(() => {
  // ⭐ 行数 = 换行数 + 1。末尾有换行时 textarea 会多显示一个空行，
  //   所以这里也算上，否则行号条会比内容短一行。
  let n = 1;
  const s = props.modelValue;
  for (let i = 0; i < s.length; i++) if (s[i] === "\n") n++;
  if (s.endsWith("\n")) n++;
  return n;
});
const degraded = computed(() => lines.value > BIG_FILE_LINES);

/**
 * ⭐⭐ 行号条：降级时**只渲染可视区那一段**，不是全部行。
 *
 * 5000 行就要渲染 5000 个 `<div>`；十万行的 SQL 文件会直接把页面拖死 ——
 * 而这恰恰是「打开一个大文件」时用户最需要编辑器还能用的时刻。
 *
 * 判据用「字符数」而不是行数：十万行 × 每行 80 字符 = 800 万字符，
 * 而 5000 行短注释只有 20 万字符。用字符数卡住，两种都拦得住，
 * 而且判定是 O(1) 的（不需要遍历全文数行）。
 */
const BIG_FILE_CHARS = 400_000;
const bigFile = computed(() => degraded.value || props.modelValue.length > BIG_FILE_CHARS);

const lineNo = computed(() => {
  const total = lines.value;
  // ⭐ 可视区大约能显示 wrap 高 / 行高 行；多给一屏做缓冲
  const visible = Math.ceil(600 / LINE_H) + 20;
  if (total <= visible) return Array.from({ length: total }, (_, i) => i + 1);
  // 降级：只给前 visible 行，并标出后面还有多少
  const out = Array.from({ length: visible }, (_, i) => i + 1);
  // ⭐ 末尾给一个「省略 N 行」的标记，而不是静默截断 ——
  //   静默截断会让用户以为文件就这么长。
  out.push(-(total - visible + 1));
  return out;
});

const statements = computed<Statement[]>(() =>
  props.readonly ? [] : splitStatements(props.modelValue),
);
const curStatement = computed(() => {
  const s = statementAtOffset(props.modelValue, ta.value?.selectionStart ?? 0);
  return s ? s.index + 1 : null;
});

const tableSet = computed(() => new Set(props.tables.map((t) => t.toLowerCase())));

/**
 * ⭐ 表名 → 列名集合（小写）。
 *
 * 必须**缓存**：hover 每次鼠标移动都会问一遍，
 * 而列名来自后端 —— 不缓存的话一次 hover 会打出一串 `/api/table` 请求。
 *
 * ⚠ 查不到该表时返回 `null`（=「不知道」）而不是空集（=「没有列」）。
 *   两者在「不认成列名」这件事上结果一样，但空集会让 UI 误以为
 *   这张表真的没有列 —— 那时提示会写「该表 0 列」，是撒谎。
 */
const colCache = new Map<string, Set<string> | null>();
function columnsOf(table: string): Set<string> | null {
  const key = table.toLowerCase();
  if (colCache.has(key)) return colCache.get(key)!;
  const meta = props.columns?.[key];
  const set = meta ? new Set(meta.map((c) => c.toLowerCase())) : null;
  if (colCache.size > 200) colCache.clear();
  colCache.set(key, set);
  return set;
}

// ─────────────────────────────────────────────────────────────
// 输入
// ─────────────────────────────────────────────────────────────

function onInput(e: Event) {
  const t = e.target as HTMLTextAreaElement;
  emit("update:modelValue", t.value);
  syncScroll();
  updateCaret();
}

/**
 * ⭐ 自动配对 / Tab 缩进 / 换行继承缩进。
 *
 * ⚠ 全部走 `preventDefault` + 手动改 `value`：
 *   让 textarea 自己处理会拿到「插入前的选区」，
 *   而我们要在插入后把光标放到配对符中间 —— 那必须手动算。
 */
function onKeydown(e: KeyboardEvent) {
  const t = ta.value;
  if (!t) return;

  const meta = e.metaKey || e.ctrlKey;

  // ⭐ 编辑中的 ⌘Z 归文本框管，不要抢
  if (meta && e.key.toLowerCase() === "z" && !e.shiftKey && !e.altKey) return;

  if (meta && e.key === "Enter") {
    e.preventDefault();
    if (e.shiftKey) emit("runSelection", selectionOrStatement());
    else emit("run");
    return;
  }
  if (meta && e.key === ".") { e.preventDefault(); emit("cancel"); return; }

  if (props.readonly) return;

  const s = t.selectionStart;
  const en = t.selectionEnd;

  // Tab / Shift+Tab：缩进与反缩进
  if (e.key === "Tab") {
    e.preventDefault();
    const multi = t.value.slice(s, en).includes("\n");
    if (!multi) {
      const r = applyInsert(t.value, s, en, "  ", {});
      set(r.text, r.caret);
      return;
    }
    // 多行：每行加/减两个空格
    const startLine = t.value.lastIndexOf("\n", s - 1) + 1;
    const block = t.value.slice(startLine, en);
    const shifted = e.shiftKey
      ? block.replace(/^ {1,2}/gm, "")
      : block.replace(/^/gm, "  ");
    t.value = t.value.slice(0, startLine) + shifted + t.value.slice(en);
    emit("update:modelValue", t.value);
    t.selectionStart = startLine;
    t.selectionEnd = startLine + shifted.length;
    return;
  }

  // 回车：继承当前行缩进；括号内多缩进两格
  if (e.key === "Enter" && s === en) {
    const lineStart = t.value.lastIndexOf("\n", s - 1) + 1;
    const indent = (t.value.slice(lineStart, s).match(/^[ \t]*/) ?? [""])[0];
    const prev = t.value[s - 1];
    const nextCh = t.value[s];
    const inner = "([{".includes(prev ?? "") && ")]}".includes(nextCh ?? "");
    const ins = "\n" + indent + (inner ? "  " : "");
    const r = applyInsert(t.value, s, en, ins, {});
    // ⭐ 配对括号中间那两格要在回车后**删掉**，否则每次回车多积两格
    let caret = r.caret;
    if (inner) {
      const at = r.caret + indent.length;
      t.value = r.text.slice(0, at) + r.text.slice(at + 2);
      caret = at;
    } else {
      t.value = r.text;
    }
    emit("update:modelValue", t.value);
    t.selectionStart = t.selectionEnd = caret;
    syncScroll();
    return;
  }

  // 引号 / 括号：自动配对与跳过
  if (e.key.length === 1 && !meta && !e.altKey) {
    // 选区非空时先删掉选区（标准编辑器行为），再决定要不要配对
    const r = applyInsert(t.value, s, en, e.key, { autoPair: true });
    if (r.text !== t.value || r.caret !== s) {
      e.preventDefault();
      t.value = r.text;
      emit("update:modelValue", r.text);
      // ⭐ 配对时把末尾的闭合符标出来，⌫ 能一次删掉一对
      t.selectionStart = t.selectionEnd = r.caret;
      if (r.replacedRange) {
        // 替换了选区：光标停在插入点即可
      } else if (r.caret + 1 <= r.text.length && r.text[r.caret] && ")]}'\"`".includes(r.text[r.caret])) {
        pairedEnd.value = r.caret;
      }
      syncScroll();
    }
  }
}

/** 最近一次自动配对插入的闭合符位置（⌫ 一次删一对） */
const pairedEnd = ref(-1);

function set(text: string, caret: number) {
  const t = ta.value;
  if (!t) return;
  t.value = text;
  emit("update:modelValue", text);
  t.selectionStart = t.selectionEnd = caret;
  syncScroll();
  updateCaret();
}

/** ⭐ Backspace：光标紧跟在自动补出的闭合符后面时，一次删掉两个 */
function onBeforeInput(e: InputEvent) {
  const t = ta.value;
  if (!t || e.inputType !== "deleteContentBackward") return;
  if (t.selectionStart !== t.selectionEnd) return;
  const i = t.selectionStart;
  if (i === 0) return;
  // 闭合符是自动补出来的（记录在 pairedEnd）且左边是配对的开头
  const closeCh = t.value[i];
  const openCh = "([{".includes(closeCh ?? "") ? closeCh : ({ ")" : "(", "]": "[", "}": "{", '"': '"', "'": "'", "`": "`" } as Record<string, string>)[closeCh ?? ""];
  if (openCh && t.value[i - 1] === openCh) {
    e.preventDefault();
    set(t.value.slice(0, i - 1) + t.value.slice(i + 1), i - 1);
  }
}

// ─────────────────────────────────────────────────────────────
// 滚动同步
// ─────────────────────────────────────────────────────────────

/**
 * ⭐ 高亮层与 textarea 共享一个滚动容器。
 *
 * ⚠ 踩过的坑：滚动监听如果挂在 `onMounted` + `addEventListener` 上，
 *   在容器还没渲染时挂不上，**页面不报错、滚动条也能拖，就是不动**。
 *   这里用模板上的 `@scroll`，它是 vnode 的一部分，跟着元素生灭，零生命周期风险。
 */
function syncScroll() {
  const t = ta.value;
  if (!t) return;
  scrollTop.value = t.scrollTop;
  if (gutter.value) gutter.value.scrollTop = t.scrollTop;
}

// ─────────────────────────────────────────────────────────────
// 光标 / 当前行
// ─────────────────────────────────────────────────────────────

function updateCaret() {
  const t = ta.value;
  if (!t) return;
  const l = lineOf(t.value, t.selectionStart);
  curLine.value = l;
  emit("caret", t.selectionStart);
}

function onSelect() {
  updateCaret();
  syncScroll();
}

function onFocus() {
  focused.value = true;
  updateCaret();
}
function onBlur() {
  focused.value = false;
  clearTip();
}

/** ⭐ 当前行高亮背景：绝对定位一块，按行号 × 行高算 top */
const LINE_H = 19.5; // 与 CSS 的 line-height 一致（13px × 1.5）
const lineMarkStyle = computed(() => ({
  transform: `translateY(${(curLine.value - 1) * LINE_H}px)`,
}));

// ─────────────────────────────────────────────────────────────
// ⭐⭐ 鼠标坐标 → 文本偏移
// ─────────────────────────────────────────────────────────────

/**
 * ⭐⭐ textarea **没有** `caretPositionFromPoint`，
 *   `document.caretRangeFromPoint` 在 textarea 里也返回 null。
 *   所以只能自己测：用一块**字体度量完全一致**的隐藏 div，
 *   把「文本前 N 个字符 + 一个探针 span」塞进去，量探针的位置，
 *   二分找出光标落在哪。
 *
 * ⚠ 三件事必须与 textarea 完全一致，否则量出来的位置是错的：
 *   font / line-height / white-space / padding / **可用宽度**。
 *   宽度不一致 → 软换行位置不同 → 二分收敛到错误的字符。
 *
 * ⭐⭐ 探针**必须放在 `left:0; top:0`**（而不是常见的 `left:-9999px`）。
 *   放左边的话量出来的 x 是「屏幕坐标」，而我们手里的是「相对 textarea 的坐标」，
 *   两者差着 9999 —— 二分永远收敛不到，于是 Ctrl+Click 完全不响应，
 *   **而且页面不报任何错**（只是「点了没反应」）。
 *
 * ⭐ 为什么必须准确：hover 与 Ctrl+Click 都靠它。
 *   偏一个字符，用户 hover「orders」看到的是「rder」的注释，
 *   点「orders」跳到另一张表 —— 而且**看起来完全正常**。
 */
let probeEl: HTMLElement | null = null;
let probeSpan: HTMLSpanElement | null = null;
let probeRaf = 0;

function getProbe(t: HTMLTextAreaElement): HTMLElement | null {
  if (!probeEl) {
    probeEl = document.createElement("div");
    probeSpan = document.createElement("span");
    probeEl.appendChild(probeSpan);
    probeEl.setAttribute("aria-hidden", "true");
    Object.assign(probeEl.style, {
      position: "fixed",
      // ⭐ 与 textarea 同一个原点：坐标直接可比，不需要任何偏移修正
      top: "0",
      left: "0",
      zIndex: "-1",
      visibility: "hidden",
      pointerEvents: "none",
      whiteSpace: "pre-wrap",
      wordBreak: "break-word",
      overflowWrap: "break-word",
      boxSizing: "border-box",
      margin: "0",
      border: "0",
      overflow: "hidden",
    });
    document.body.appendChild(probeEl);
  }
  const cs = window.getComputedStyle(t);
  probeEl.style.fontFamily = cs.fontFamily;
  probeEl.style.fontSize = cs.fontSize;
  probeEl.style.fontWeight = cs.fontWeight;
  probeEl.style.lineHeight = cs.lineHeight;
  probeEl.style.letterSpacing = cs.letterSpacing;
  probeEl.style.tabSize = cs.tabSize;
  probeEl.style.padding = cs.padding;
  // ⭐ 宽度用 **clientWidth**（不含滚动条）：textarea 的可用宽度就是这么算的
  probeEl.style.width = `${t.clientWidth}px`;
  probeEl.style.height = "auto";
  // ⭐ 与 textarea 对齐，否则量出来的 y 差一个 top
  const r = t.getBoundingClientRect();
  probeEl.style.top = `${r.top}px`;
  probeEl.style.left = `${r.left}px`;
  return probeEl;
}

/** 量「把光标放在 offset 处时，它渲染在哪个像素点」（相对 textarea 左上角） */
function caretPoint(t: HTMLTextAreaElement, offset: number): { x: number; y: number } | null {
  const el = getProbe(t);
  if (!el || !probeSpan) return null;
  el.textContent = props.modelValue.slice(0, offset);
  el.appendChild(probeSpan);
  const r = probeSpan.getBoundingClientRect();
  const base = t.getBoundingClientRect();
  return { x: r.left - base.left, y: r.top - base.top };
}

/**
 * ⭐ 二分：找**光标点落在 (x, y) 之前**的最大 offset。
 *
 * ⚠⭐ 判定必须用「**落在哪一行**」，不能用「y 差不多就算同一行」。
 *   行高 19.5px 是小数，点击的 y=18 与首行的 y=10 相差 8px ——
 *   用 `|p.y - y| <= 2` 判同行的话**首行永远判成「不是同一行」**，
 *   于是全程只比 x，而 x 又按行内坐标算，结果收敛到错误的字符。
 *   实测：点 `orders`（偏移 15）会得到 29 —— 跳到 `LIMIT 1` 里去。
 *
 *   正确做法：把 y 除以行高取整得到行号，只在**同一行内**比 x。
 *   行高是浮点（19.5）也没关系，因为除完再取整是稳定的。
 */
function offsetFromPoint(t: HTMLTextAreaElement, x: number, y: number): number {
  const text = props.modelValue;
  if (!text.length) return 0;

  const lineH = parseFloat(window.getComputedStyle(t).lineHeight) || 19.5;
  const padTop = parseFloat(window.getComputedStyle(t).paddingTop) || 0;
  // ⭐ 点击的行（0 基）。容差 1px：点在行边界上时不该算到下一行去
  const targetLine = Math.max(0, Math.round((y - padTop) / lineH));

  const lineOfPoint = (off: number): { line: number; x: number } | null => {
    const p = caretPoint(t, off);
    if (!p) return null;
    return { line: Math.max(0, Math.round((p.y - padTop) / lineH)), x: p.x };
  };

  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    const p = lineOfPoint(mid);
    if (!p) break;
    // ⭐ 光标在点击位置的**左边**（严格早于）→ 继续往后找
    const past = p.line < targetLine || (p.line === targetLine && p.x <= x + 1);
    if (past) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

// ─────────────────────────────────────────────────────────────
// ⭐ Ctrl+Click 跳转
// ─────────────────────────────────────────────────────────────

function onMouseDown(e: MouseEvent) {
  if (!(e.metaKey || e.ctrlKey)) {
    // 普通点击：清掉 hover 状态与配对标记
    hoverRef.value = null;
    pairedEnd.value = -1;
  }
}

function onClick(e: MouseEvent) {
  if (!(e.metaKey || e.ctrlKey)) return;
  const t = ta.value;
  if (!t) return;
  const r = t.getBoundingClientRect();
  // ⭐ 用测出来的偏移，**不是** selectionStart ——
  //   Cmd+Click 在部分平台不移动选区（macOS 右键语义），那时 selectionStart
  //   还停在上一次点击处，跳转会跳到一个用户根本没点的地方。
  const off = offsetFromPoint(t, e.clientX - r.left, e.clientY - r.top);
  const sym = symbolAt(props.modelValue, off, tableSet.value, columnsOf);
  if (sym) {
    e.preventDefault();
    hoverRef.value = null;
    clearTip();
    emit("jump", sym);
  }
  // ⭐ 不可跳转时**什么都不做**，不发 toast 打扰用户 ——
  //   鼠标扫到关键词就弹「不可跳转」是噪音。
}

// ─────────────────────────────────────────────────────────────
// ⭐ hover 提示（注释 / 枚举）
// ─────────────────────────────────────────────────────────────

/** ⭐ hover 缓存：同一批偏移反复扫过时不必重算（符号定位要跑一遍分词） */
const hoverCache = new Map<number, SymbolRef | null>();

function onMouseMove(e: MouseEvent) {
  const t = ta.value;
  if (!t || props.readonly) return;
  // ⭐ rAF 节流：mousemove 一秒能触发上百次，
  //   每次都二分 12 次强制重排会把滚动拖到卡顿。
  if (probeRaf) return;
  const px = e.clientX;
  const py = e.clientY;
  probeRaf = requestAnimationFrame(() => {
    probeRaf = 0;
    const el = ta.value;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const off = offsetFromPoint(el, px - r.left, py - r.top);

    let sym = hoverCache.get(off);
    if (sym === undefined) {
      sym = symbolAt(props.modelValue, off, tableSet.value, columnsOf);
      // ⭐ 缓存要有上限：SQL 改一次偏移全变，无上限的 Map 会一直涨
      if (hoverCache.size > 400) hoverCache.clear();
      hoverCache.set(off, sym);
    }
    hoverRef.value = sym;
    if (!sym) { clearTip(); return; }

    window.clearTimeout(tipTimer);
    // ⭐ 150ms 才出提示（UI-SPEC）：鼠标扫过一串字符时不该闪一堆 tooltip
    tipTimer = window.setTimeout(() => {
      const rr = (ta.value as HTMLTextAreaElement | null)?.getBoundingClientRect();
      if (!rr) return;
      tip.value = { ref: sym as SymbolRef, x: px - rr.left, y: py - rr.top };
    }, 150);
  });
}

function clearTip() {
  window.clearTimeout(tipTimer);
  tip.value = null;
}

/** ⭐ hover 命中的 token 在高亮层上加下划线（UI-SPEC：hover 时标识符要有 affordance） */
const hoverRange = computed<[number, number] | null>(() => {
  const s = hoverRef.value;
  return s ? [s.start, s.end] : null;
});

/**
 * ⭐ tooltip 的元数据行。父组件可能要去查后端，所以允许是 `undefined`（查不到）。
 *
 * ⚠ **查不到就显示「元数据里没有这个对象」，而不是空提示框** ——
 *   一个空 tooltip 会让用户以为是渲染坏了。
 */
const tipMeta = computed<string[] | null>(() => {
  const t = tip.value;
  if (!t || !props.describe) return null;
  return props.describe(t.ref);
});

// ─────────────────────────────────────────────────────────────
// 选中执行
// ─────────────────────────────────────────────────────────────

/** ⭐ 有选中就跑选中的；没选中就跑光标所在那条 —— 两条路都要能用 */
function selectionOrStatement(): string {
  const t = ta.value;
  if (!t) return props.modelValue;
  const sel = t.value.slice(t.selectionStart, t.selectionEnd).trim();
  if (sel) return sel;
  const s = statementAtOffset(t.value, t.selectionStart);
  return s ? s.text : t.value.trim();
}

/**
 * ⭐⭐ 把光标移到指定行并**选中出错的标识符**。
 *
 * @param length 标识符长度。后端给了 `position.length` 就用它 ——
 *   选中范围必须**恰好是出错的词**，多选几个字用户还得自己往回拖。
 *
 * ⚠ 之前是「选中到行尾」，于是点一下 `nope` 会选中 `nope FROM orders` ——
 *   用户看到一个横跨半行的选区，完全不知道系统想指的是哪个词。
 *   拿不到长度时才退回「只放光标，不选区」——
 *   **半个提示（一个光标）远好于错误的提示（一大块选区）**。
 */
function reveal(line: number, column: number, length = 0) {
  const t = ta.value;
  if (!t) return;
  const linesArr = props.modelValue.split("\n");
  let off = 0;
  for (let i = 0; i < line - 1 && i < linesArr.length; i++) off += linesArr[i].length + 1;
  off += Math.max(0, column - 1);
  off = Math.min(off, props.modelValue.length);

  // ⭐⭐ length 是**字符数**（后端 `SqlPosition.length` 由 `chars().count()` 得出），
  //   而 textarea 的下标是 **UTF-16 编码单元**。
  //   对 ASCII 两者相等，对中文/emoji 不等 ——
  //   直接 `off + length` 会在代理对中间截断（选区出现半个乱码字符）。
  //   这里按码点数逐个推进，代理对算 1 个字符。
  let caret = off;
  for (let i = 0; i < length && caret < props.modelValue.length; i++) {
    const cp = props.modelValue.codePointAt(caret)!;
    caret += cp > 0xffff ? 2 : 1;
  }
  t.focus();
  if (length > 0 && caret <= props.modelValue.length) {
    t.setSelectionRange(off, caret);
  } else {
    t.setSelectionRange(off, off);
  }
  curLine.value = line;
  // ⭐ 滚到可见：光标设了但没滚过去 = 用户看不到自己被定位到了哪
  nextTick(() => {
    t.focus();
    const target = (line - 1) * LINE_H;
    t.scrollTop = Math.max(0, target - t.clientHeight / 2);
    syncScroll();
  });
}

function focus() {
  ta.value?.focus();
}
function insert(text: string) {
  const t = ta.value;
  if (!t) return;
  const r = applyInsert(t.value, t.selectionStart, t.selectionEnd, text, { autoPair: true });
  set(r.text, r.caret);
}

// ─────────────────────────────────────────────────────────────
// ⭐ 键盘导航：匹配当前词的跳转目标（无鼠标时也能跳）
// ─────────────────────────────────────────────────────────────

/** ⭐ 光标所在词 —— 键盘用户的第一入口 */
const wordUnderCursor = computed<SymbolRef | null>(() => {
  const t = ta.value;
  if (!t) return null;
  return symbolAt(props.modelValue, t.selectionStart, tableSet.value, columnsOf);
});

function jumpWord() {
  if (wordUnderCursor.value) emit("jump", wordUnderCursor.value);
}

// ─────────────────────────────────────────────────────────────
// 暴露
// ─────────────────────────────────────────────────────────────

defineExpose({ focus, reveal, insert, el: ta, selectionOrStatement, jumpWord });

watch(() => props.modelValue, () => {
  // 内容变了，之前算的 hover 偏移全部失效
  hoverCache.clear();
  hoverRef.value = null;
  clearTip();
  nextTick(syncScroll);
});

// ⚠ 元数据到了之后必须把列名缓存清掉 ——
//   否则「先 hover（还没元数据）→ 再元数据到了」的顺序下，
//   那个表会被永久记成「没有列」，hover 再也不出提示。
//   页面不报错、也没有 console 提示 —— 表现为「有的表能 hover，有的不能」。
watch(() => props.columns, () => {
  colCache.clear();
  hoverCache.clear();
});
</script>

<template>
  <div class="sqled" :class="{ 'sqled--focus': focused, 'sqled--readonly': readonly }">
    <!-- ⭐ 行号条 -->
    <div class="sqled__gutter" ref="gutter" aria-hidden="true">
      <div class="sqled__gutter-inner">
        <template v-for="n in lineNo" :key="n">
          <!-- ⭐ 负数 = 「后面还有 N 行」的省略标记 -->
          <div v-if="n < 0" class="sqled__ln sqled__ln--more">⋯ {{ -n - 1 }} 行</div>
          <div v-else class="sqled__ln" :class="{ 'sqled__ln--cur': n === curLine }">{{ n }}</div>
        </template>
      </div>
    </div>

    <!-- ⭐ 编辑区：高亮层 + 透明 textarea 叠在一起 -->
    <div class="sqled__wrap">
      <div class="sqled__mark" :style="lineMarkStyle" aria-hidden="true"></div>
      <pre class="sqled__hl" ref="hl" aria-hidden="true"><code v-html="html"></code></pre>
      <textarea
        ref="ta"
        class="sqled__ta"
        :class="{ 'sqled__ta--degraded': degraded }"
        wrap="soft"
        :value="modelValue"
        :readonly="readonly"
        :placeholder="placeholder"
        spellcheck="false"
        autocomplete="off"
        autocorrect="off"
        autocapitalize="off"
        @input="onInput"
        @keydown="onKeydown"
        @beforeinput="onBeforeInput"
        @scroll="syncScroll"
        @click="onClick"
        @select="onSelect"
        @focus="onFocus"
        @blur="onBlur"
        @mousedown="onMouseDown"
        @mousemove="onMouseMove"
        @mouseleave="clearTip"
      ></textarea>

      <!-- ⭐ hover 提示：注释与枚举直接进 tooltip（UI-SPEC §1.4 元数据语义内联） -->
      <div
        v-if="tip"
        class="sqled__tip"
        :style="{ left: Math.min(tip.x + 12, 300) + 'px', top: (tip.y + 20) + 'px' }"
      >
        <div class="sqled__tip-title mono">
          {{ tip.ref.name }}
          <span class="badge">{{ tip.ref.kind === "table" ? "表" : "列" }}</span>
          <span v-if="tipMeta?.length" class="badge badge--muted">{{ tipMeta[0] }}</span>
        </div>
        <div v-for="(line, i) in (tipMeta ?? []).slice(1)" :key="i" class="small muted">{{ line }}</div>
        <div v-if="!tipMeta?.length" class="small muted">
          元数据里没有这个对象
        </div>
        <div v-if="tipMeta?.length" class="small muted" style="margin-top:3px">⌘Click 跳转</div>
      </div>
    </div>

    <!-- ⭐ 状态条：语句数 / 降级提示 -->
    <div class="sqled__foot">
      <span v-if="statements.length > 1" class="small">
        {{ statements.length }} 条语句
        <template v-if="curStatement">· 光标在第 {{ curStatement }} 条</template>
      </span>
      <span class="grow"></span>
      <span v-if="degraded" class="small" style="color: var(--warning)">
        ⚠ {{ lines.toLocaleString() }} 行，已关闭语法高亮
      </span>
      <span v-else-if="bigFile" class="small" style="color: var(--warning)">
        ⚠ {{ lines.toLocaleString() }} 行，行号与高亮已降级
      </span>
      <span v-else-if="wordUnderCursor" class="small muted mono">
        ⌘⏎ 执行 · ⌘⇧⏎ 执行第 {{ curStatement }} 条 · ⌘Click 跳转
      </span>
      <span v-else class="small muted">⌘⏎ 执行 · ⌘⇧⏎ 执行选中</span>
    </div>
  </div>
</template>

<script lang="ts">
export default {};
</script>

<style scoped>
/* ══ 三条对齐铁律：font / padding / white-space 必须与 .sqled__hl 完全一致 ══ */
.sqled {
  display: flex;
  flex-direction: column;
  /* ⭐ 必须 `flex: 1` + `width: 100%`：
     父容器 `.query-editor` 是 `display:flex` 的**行**布局，
     子元素默认 `flex: 0 1 auto` 只按内容宽度收缩 ——
     于是编辑器在 1359px 的容器里只有 199px（刚好是初始那行 SQL 的宽度），
     看起来像「编辑器被压扁了」，而页面不报任何错。
     `min-width: 0` 则保证长 SQL 不会把父容器撑破。 */
  flex: 1;
  width: 100%;
  min-width: 0;
  min-height: 0;
  background: var(--bg-inset);
  border: var(--bw) solid var(--border);
  border-radius: var(--r-md);
  overflow: hidden;
}
.sqled--focus { border-color: var(--border-focus); }

.sqled__gutter {
  flex: 0 0 auto;
  width: 44px;
  overflow: hidden;
  background: var(--bg-surface);
  border-right: var(--bw) solid var(--border);
  user-select: none;
}
.sqled__gutter-inner { padding: var(--sp-2) 0; will-change: transform; }
.sqled__ln {
  height: 19.5px;
  line-height: 19.5px;
  text-align: right;
  padding-right: var(--sp-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}
.sqled__ln--cur { color: var(--text-primary); background: var(--bg-hover); }
.sqled__ln--more {
  color: var(--text-tertiary);
  font-size: 9px;
  white-space: nowrap;
}

.sqled__wrap { position: relative; flex: 1; min-height: 0; }

/* 当前行背景：垫在高亮层下面，不挡文字 */
.sqled__mark {
  position: absolute;
  left: 0; right: 0;
  height: 19.5px;
  top: var(--sp-2);
  background: var(--bg-hover);
  pointer-events: none;
  z-index: 0;
}

.sqled__hl,
.sqled__ta {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  font-family: var(--font-mono);
  font-size: var(--fs-base);
  line-height: 19.5px;
  letter-spacing: normal;
  tab-size: 2;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: break-word;
  border: 0;
}

.sqled__hl {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  color: var(--syn-ident);
  z-index: 1;
}
.sqled__hl code { font: inherit; }

.sqled__ta {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  resize: none;
  outline: none;
  background: transparent;
  /* ⭐ 文字透明、光标可见 —— 视觉由下面的高亮层负责 */
  color: transparent;
  caret-color: var(--text-primary);
  z-index: 2;
  overflow: auto;
}
.sqled__ta::placeholder { color: var(--text-tertiary); }
.sqled__ta::selection { background: var(--bg-selected); }
.sqled--readonly .sqled__ta { caret-color: transparent; }

/* ⭐ 大文件降级：没有高亮层时文字必须是正常颜色，否则用户看到一片空白 */
.sqled__ta--degraded {
  color: var(--text-primary);
  white-space: pre;
  overflow-x: auto;
}

.sqled__tip {
  position: absolute;
  z-index: var(--z-tooltip);
  max-width: 340px;
  padding: var(--sp-1) var(--sp-2);
  background: var(--bg-elevated);
  border: var(--bw) solid var(--border-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-md);
  pointer-events: none;
}
.sqled__tip-title { display: flex; align-items: center; gap: var(--sp-2); }

.sqled__foot {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 2px var(--sp-3);
  border-top: var(--bw) solid var(--border);
  background: var(--bg-surface);
  color: var(--text-secondary);
  min-height: 20px;
}
</style>

<style>
/* ⭐ token 颜色必须是**非 scoped** 的：
   v-html 渲染出来的元素不会带 scoped 的 data 属性，
   写成 scoped 的话一条颜色都不生效 —— 表现为「高亮层有内容但全是白的」。 */
.tk--keyword  { color: var(--syn-keyword); font-weight: 500; }
.tk--type     { color: var(--syn-type); }
.tk--func     { color: var(--syn-function); }
.tk--string   { color: var(--syn-string); }
.tk--number   { color: var(--syn-number); }
.tk--comment  { color: var(--syn-comment); font-style: italic; }
.tk--param    { color: var(--syn-param); }
.tk--operator { color: var(--text-secondary); }
.tk--punct    { color: var(--text-tertiary); }
.tk--ident    { color: var(--syn-ident); }
.tk--quoted   { color: var(--syn-string); }
.tk--unknown  { color: var(--syn-error); text-decoration: underline wavy var(--syn-error); }

/* ⭐ hover 中的符号：下划线 + 指针（UI-SPEC §QueryEditor：hover 要有 affordance，
   否则用户不知道这个词能点） */
.tk--mark {
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 3px;
  cursor: pointer;
  background: var(--bg-hover);
}
</style>
