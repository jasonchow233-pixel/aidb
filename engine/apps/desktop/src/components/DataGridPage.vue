<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  state, actions, notify, FILTER_OPS, sqlLiteral, sameValue,
  type FilterOp, type TableFilter,
} from "@/lib/store";
import { api, capabilities, compact, dangerClass, dangerIcon, type ColumnDef } from "@/lib/api";
import ColumnFilterPanel from "@/components/ColumnFilterPanel.vue";

const emit = defineEmits<{ run: [sql: string] }>();

const detail = ref<{ col: ColumnDef; row: number; text: string; total: number; hex?: string | null } | null>(null);
const detailLoading = ref(false);
const pageLimit = ref(state.rowLimit);
const searchTerm = ref("");
const selectedRows = ref<Set<number>>(new Set());
const editingCell = ref<{ row: number; col: string; value: string } | null>(null);
const showFilter = ref(false);
const filterCol = ref("");
const filterOp = ref<FilterOp>("=");
const filterVal = ref("");

// ── 列头筛选面板（A2）──
/** 当前打开筛选面板的列（null = 未打开） */
const filterPanelCol = ref<string | null>(null);
const filterPanelMeta = computed(() =>
  filterPanelCol.value
    ? state.columns.find((c) => c.name === filterPanelCol.value)
    : undefined,
);
function toggleFilterPanel(col: string) {
  filterPanelCol.value = filterPanelCol.value === col ? null : col;
}
/** 该列是否已有筛选条件 */
function hasColFilter(col: string): boolean {
  return state.filters.some((f) => f.col === col);
}

// ── 分页 ──
const currentPage = computed(() => Math.floor(state.offset / state.rowLimit) + 1);
const totalPages = computed(() =>
  state.total !== null ? Math.ceil(state.total / state.rowLimit) : null,
);
/** 跳页输入框的值 */
const jumpPage = ref("");

function doJump() {
  const n = Number(jumpPage.value);
  if (!Number.isFinite(n) || n < 1) return;
  actions.goToPage(n);
  jumpPage.value = "";
}

// ── 筛选 ──
/** ⭐ IS NULL / IS NOT NULL 不需要值输入 */
const needsValue = computed(
  () => !["IS NULL", "IS NOT NULL"].includes(filterOp.value),
);
function applyFilter() {
  if (!filterCol.value) return;
  const needs = !["IS NULL", "IS NOT NULL"].includes(filterOp.value);
  if (needs && filterVal.value === "") return;
  // ⭐ 走 store 的 action —— 它会重置 offset、重查、并把 total 标记为失效
  actions.addFilter({
    col: filterCol.value,
    op: filterOp.value,
    value: needs ? filterVal.value : null,
  });
  filterVal.value = "";
}
function removeFilter(i: number) {
  actions.removeFilter(i);
}

/** ⭐ 当前连接的传输家族（来自声明层），拿不到就不显示 —— 不猜。 */
const connFamily = computed(
  () => capabilities.byId(state.connectionKind)?.transferObjectFamily ?? null,
);

/**
 * ⭐ 页大小变化必须**重新查询**。
 *
 * 只调 `setRowLimit`（只写 localStorage）的话：offset 还停在旧页码上，
 * 用新页大小算出来的偏移量会越界，用户看到「本页 200 行」却选了 100。
 */
watch(pageLimit, (v) => {
  actions.setRowLimit(v);
  // 页大小变了，offset 必须回到起点，否则会跳到错乱的页
  state.offset = 0;
  const t = state.tableDef?.name;
  if (t) void actions.loadTable(t);
});

const total = computed(() => state.total);
const rowH = computed(() =>
  state.density === "compact"
    ? "var(--row-compact)"
    : state.density === "comfort"
      ? "var(--row-comfort)"
      : "var(--row-normal)",
);
const findingsForTable = computed(() =>
  state.findings.filter((f) => f.table === state.tableDef?.name),
);

/**
 * ⭐ 只做「当前页内」的文本搜索。
 *
 * ⭐ **范围必须写在 UI 上**（「仅搜索本页 N 行」）：
 *   让用户以为在搜全表、实际只搜了当前页，是数据库工具最常见的坑。
 *   全表搜索要走 A2 列头筛选面板（带 WHERE 下推），不是这个输入框。
 */
/**
 * ⭐ 当前页 + 本页搜索命中的行，**每行带上它在 `state.rows` 里的真实下标**。
 *
 * ⭐ 为什么必须带下标：
 *   一旦用了「搜索本页」，filteredRows 与 state.rows 就错位了。
 *   编辑 / 删除 / 批量选中若都拿 filteredRows 的下标去索引 state.rows，
 *   改到的会是**另一行** —— 页面看着一切正常，数据已经错了。
 *   （与「内存排序看着对实际错」是同一类错误。）
 *
 * ⭐ A4-8 隐藏 NULL / 空串：只作用于**当前页**，范围写在 UI 上
 *   （「隐藏 NULL · 仅本页」）。假装全表生效是数据库工具最常见的坑。
 */
const hideNull = ref(false);
const hideEmpty = ref(false);
/** ⭐ A4-8 转置：把当前**可见窗口**的行换成列。只读 —— 编辑需要行标识，
 *  转置后「第几行」不再是稳定概念，所以干脆不提供编辑入口，也不假装能编辑。 */
const transpose = ref(false);
const filteredRows = computed(() => {
  const q = searchTerm.value.trim().toLowerCase();
  let out: { row: Record<string, unknown>; ri: number }[] = [];
  state.rows.forEach((row, ri) => {
    if (!q) { out.push({ row, ri }); return; }
    if (state.columns.some((c) => String(row[c.name] ?? "").toLowerCase().includes(q))) {
      out.push({ row, ri });
    }
  });
  // ⭐ 语义写清楚：隐藏「含有 NULL 的行」/「含有空串的行」，仅当前页。
  //   含糊的「隐藏空值」会让用户以为在改查询条件 —— 那不是同一个东西。
  if (hideNull.value) out = out.filter(({ row }) => !state.columns.some((c) => row[c.name] === null));
  if (hideEmpty.value) out = out.filter(({ row }) => !state.columns.some((c) => row[c.name] === ""));
  return out;
});

// ── A4-1 窗口化渲染 ───────────────────────────────────────────
/**
 * ⭐ 只渲染可视区附近的行，上下各用一个 spacer 撑出完整高度。
 *
 * 实测（`tests/probe_ab.mjs`，orders 本页 5000 行 × 9 列）：
 *   · 全量渲染 → 91,503 个 DOM 节点（其中表格 91,130）
 *   · 窗口化   →  1,357 个 DOM 节点（其中表格    984），67 倍差距
 *
 * ⭐ 关于帧时间：headless Chromium 不做合成，滚动的帧耗时在两种策略下
 *   都是 ~16.6ms，**测不出差异**。所以这里只主张「DOM 少了 67 倍」
 *   这一条实测结论，不吹帧率。DOM 数量决定的是建表、选中态更新、
 *   每次翻页重排的常量开销，这一项已经实测到位。
 *
 * ⭐ 为什么自研而不是装 AG Grid / 现成虚拟列表：
 *   装包在本机反复超时；自研只要两个 ref + 两个 computed，
 *   而且接口按虚拟列表的通用语义写（可视区 = scrollTop/行高 ± overscan），
 *   以后换库只需替换 `win` 这一个 computed。
 *
 * ⭐ 三个必须保证的点：
 *   1. **滚动条长度正确** —— 靠上下 spacer 的高度，
 *      否则用户以为表里只有几十行
 *   2. **行号连续** —— 行号 = state.offset + 真实下标，spacer 不参与编号
 *   3. **行高必须是确定的 px** —— CSS 变量是定值才能用像素算可视区
 */
const wrapRef = ref<HTMLElement | null>(null);
const scrollTop = ref(0);
const viewportH = ref(600);
const OVERSCAN = 12; // 上下各多渲染几行，快速滚动时不露白

/** 行高（px）。必须与 tokens.css 的 --row-* 保持一致。 */
const rowPx = computed(() => {
  if (state.density === "compact") return 26;
  if (state.density === "comfort") return 40;
  return 32;
});

const win = computed(() => {
  const total = filteredRows.value.length;
  const h = rowPx.value;
  const start = Math.max(0, Math.floor(scrollTop.value / h) - OVERSCAN);
  const end = Math.min(total, start + Math.ceil(viewportH.value / h) + OVERSCAN * 2);
  return {
    start,
    end,
    /** 上方 spacer 高度：撑出「前面还有多少行」 */
    padTop: start * h,
    /** 下方 spacer 高度 */
    padBottom: Math.max(0, (total - end) * h),
  };
});

/** ⭐ 只含可视区的行；`ri` 是它在 state.rows 里的真实下标，`no` 是全局行号 */
const winRows = computed(() =>
  filteredRows.value.slice(win.value.start, win.value.end).map((r) => ({
    ...r,
    no: state.offset + r.ri + 1,
  })),
);

/**
 * ⭐ 滚动处理放在模板的 `@scroll` 上，**不要**用生命周期钩子去 addEventListener。
 *
 * 踩过的坑（很难查，且没有任何报错）：
 *   表格容器在 `v-if="state.columns.length"` 里面，组件挂载那一刻还没打开表，
 *   `wrapRef.value` 是 null → `onMounted` 里挂不上监听；
 *   而 `watch(wrapRef, ...)` 靠模板 ref 赋值触发，那个赋值发生在渲染补丁阶段，
 *   默认的 `flush: "pre"` 会在补丁**之前**跑、拿到旧值，改成 `"post"` 也仍然
 *   在某些更新路径上错过。结果就是：**页面不报错、滚动条也能拖，
 *   但列表永远停在第一屏**。
 *
 * 模板上的 `@scroll` 是 vnode 的一部分，跟着元素生灭，零生命周期风险。
 */
function onWrapScroll() {
  const el = wrapRef.value;
  if (!el) return;
  // ⭐ 右键菜单必须跟着滚动关闭：不关的话菜单会浮在别的内容上，
  //   而用户此刻已经滚到别处，点下去就是误操作。
  closeCtxMenu();
  scrollTop.value = el.scrollTop;
  // ⭐ 顺带校正视口高度：窗口缩放 / 分栏拖动都会改变它。
  //   不校正的话窗口一变，可视区里就少渲染几行、露出空白。
  //   放进 rAF 是为了不在 scroll 回调里强制同步布局。
  if (el.clientHeight !== viewportH.value) {
    requestAnimationFrame(() => { viewportH.value = el.clientHeight || 600; });
  }
}

/** 换页 / 换筛选 / 换表后必须把滚动位置拉回顶部。
 *  不拉回去的话 `scrollTop` 还停在新结果的「中段」，
 *  页面会显示一张空表、行号从几百开始 —— 看起来像加载失败。 */
watch(
  [() => state.offset, () => state.filters.length, () => state.tableDef?.name],
  () => {
    const el = wrapRef.value;
    if (el) el.scrollTop = 0;
    scrollTop.value = 0;
  },
);

/** 数据到位后重新量一次视口：首次打开表时元素才刚存在 */
watch(
  () => state.rows,
  () => {
    const el = wrapRef.value;
    if (!el) return;
    el.scrollTop = 0;
    scrollTop.value = 0;
    viewportH.value = el.clientHeight || 600;
  },
);

// ── A4-2~A4-5 列布局：顺序 / 宽度 / 显隐 ─────────────────────
/**
 * ⭐ 列布局三件事，全部按「连接 + 表名」分键持久化：
 *   · 顺序（colOrder）  · 宽度（colWidths）  · 显隐（hiddenCols）
 *
 * ⭐ 为什么必须持久化：宽表每次重开都要重新拖一遍列宽，
 *   用户会直接把工具判定为「不好用」。Navicat 同样有这个行为。
 *
 * ⚠ 持久化的 key 必须带连接标识：不同库的同名表布局不一定一样。
 * ⚠ 读到的布局可能引用已不存在的列（表结构变了）——
 *   以当前列清单为准合并、**丢弃失效项**，绝不让旧布局把新表搞坏。
 */
const LAYOUT_KEY_PREFIX = "adb.gridlayout:";
interface GridLayout { order: string[]; widths: Record<string, number>; hidden: string[] }

function layoutKey(): string {
  return `${LAYOUT_KEY_PREFIX}${state.connectionKind}:${state.tableDef?.name ?? ""}`;
}

const colWidths = ref<Record<string, number>>({});
const colOrder = ref<string[]>([]);
const hiddenCols = ref<Set<string>>(new Set());

function loadLayout() {
  colWidths.value = {};
  colOrder.value = [];
  hiddenCols.value = new Set();
  if (!state.tableDef) return;
  try {
    const raw = localStorage.getItem(layoutKey());
    if (!raw) return;
    const l = JSON.parse(raw) as Partial<GridLayout>;
    const names = new Set(state.columns.map((c) => c.name));
    if (Array.isArray(l.order)) colOrder.value = l.order.filter((n) => names.has(n));
    if (l.widths && typeof l.widths === "object") {
      const w: Record<string, number> = {};
      for (const [k, v] of Object.entries(l.widths)) {
        if (names.has(k) && typeof v === "number") w[k] = Math.max(56, Math.min(800, v));
      }
      colWidths.value = w;
    }
    if (Array.isArray(l.hidden)) hiddenCols.value = new Set(l.hidden.filter((n) => names.has(n)));
  } catch {
    // ⭐ 布局损坏不影响主流程：当成没有布局，而不是让页面打不开
  }
}

let layoutSaveTimer = 0;
function saveLayout() {
  if (!state.tableDef) return;
  window.clearTimeout(layoutSaveTimer);
  layoutSaveTimer = window.setTimeout(() => {
    try {
      const l: GridLayout = {
        order: visibleColumns.value.map((c) => c.name),
        widths: colWidths.value,
        hidden: [...hiddenCols.value],
      };
      localStorage.setItem(layoutKey(), JSON.stringify(l));
    } catch {
      // 存储写满：个别布局丢就丢了，不该打断用户
    }
  }, 300);
}

watch(() => state.tableDef?.name, loadLayout);
watch([colOrder, colWidths, hiddenCols], saveLayout, { deep: true });

/** ⭐ 当前实际渲染的列：顺序 = 持久化顺序（新列追加在末尾），再扣掉隐藏的 */
const visibleColumns = computed<ColumnDef[]>(() => {
  const byName = new Map(state.columns.map((c) => [c.name, c]));
  const ordered: ColumnDef[] = [];
  const seen = new Set<string>();
  for (const n of colOrder.value) {
    const c = byName.get(n);
    if (c && !seen.has(n)) { ordered.push(c); seen.add(n); }
  }
  // ⭐ 结构变了（新增列）→ 追加在末尾，不动用户排好的前段
  for (const c of state.columns) if (!seen.has(c.name)) { ordered.push(c); seen.add(c.name); }
  return ordered.filter((c) => !hiddenCols.value.has(c.name));
});

function colWidth(c: ColumnDef): number {
  return colWidths.value[c.name] ?? 140;
}

// ── A4-3 列宽拖拽 + 双击自适应 + 列序拖拽 ─────────────────────
const resizing = ref<{ col: string; startX: number; startW: number } | null>(null);
const reordering = ref<{ col: string; over: string | null } | null>(null);
/** ⭐ 区分「点击表头排序」与「拖拽列宽/列序」—— 拖拽结束的那一次 click 不能触发排序 */
const suppressSortClick = ref(false);

function onResizeStart(e: MouseEvent, col: string) {
  e.preventDefault();
  e.stopPropagation();
  resizing.value = { col, startX: e.clientX, startW: colWidths.value[col] ?? 140 };
  window.addEventListener("mousemove", onResizeMove);
  window.addEventListener("mouseup", onResizeEnd);
}
function onResizeMove(e: MouseEvent) {
  const r = resizing.value;
  if (!r) return;
  const w = Math.max(56, Math.min(800, r.startW + e.clientX - r.startX));
  colWidths.value = { ...colWidths.value, [r.col]: w };
}
function onResizeEnd() {
  resizing.value = null;
  window.removeEventListener("mousemove", onResizeMove);
  window.removeEventListener("mouseup", onResizeEnd);
}

/** ⭐ 双击列宽手柄：量**已渲染单元格**的真实内容宽度，不猜字符数 */
function autosizeCol(col: string) {
  const cells = [...document.querySelectorAll(`td[data-col="${CSS.escape(col)}"]`)] as HTMLElement[];
  let w = 56;
  for (const td of cells) w = Math.max(w, td.scrollWidth);
  const th = document.querySelector(`th[data-col="${CSS.escape(col)}"]`) as HTMLElement | null;
  if (th) w = Math.max(w, th.scrollWidth);
  colWidths.value = { ...colWidths.value, [col]: Math.min(800, Math.ceil(w) + 12) };
}

function onThMouseDown(e: MouseEvent, col: string) {
  if (e.button !== 0) return;
  // 筛选按钮与列宽手柄有自己的语义，不启动列序拖拽
  if ((e.target as HTMLElement | null)?.closest(".th-filter, .col-resizer")) return;
  const startX = e.clientX;
  let started = false;
  const move = (ev: MouseEvent) => {
    if (!started && Math.abs(ev.clientX - startX) > 6) {
      started = true;
      reordering.value = { col, over: null };
    }
    if (!started) return;
    const el = document.elementFromPoint(ev.clientX, ev.clientY);
    const th = el?.closest("th[data-col]") as HTMLElement | null;
    if (reordering.value) reordering.value = { col, over: th?.dataset.col ?? null };
  };
  const up = () => {
    window.removeEventListener("mousemove", move);
    window.removeEventListener("mouseup", up);
    const r = reordering.value;
    reordering.value = null;
    if (r && r.over && r.over !== r.col) {
      moveColumn(r.col, r.over);
      // click 会在 mouseup 之后同步派发，setTimeout(0) 之前flag还在
      suppressSortClick.value = true;
      window.setTimeout(() => { suppressSortClick.value = false; }, 0);
    }
  };
  window.addEventListener("mousemove", move);
  window.addEventListener("mouseup", up);
}

function moveColumn(from: string, to: string) {
  const order = visibleColumns.value.map((c) => c.name);
  const i = order.indexOf(from);
  const j = order.indexOf(to);
  if (i < 0 || j < 0) return;
  order.splice(i, 1);
  order.splice(j, 0, from);
  colOrder.value = order;
}

// ── A4-4 列显示 / 隐藏 ────────────────────────────────────────
const showColPanel = ref(false);
function toggleColVisible(name: string) {
  const next = new Set(hiddenCols.value);
  if (next.has(name)) next.delete(name);
  else next.add(name);
  hiddenCols.value = next;
}
function showAllCols() { hiddenCols.value = new Set(); }
function resetColLayout() {
  hiddenCols.value = new Set();
  colOrder.value = [];
  colWidths.value = {};
  try { localStorage.removeItem(layoutKey()); } catch { /* 无所谓 */ }
  showColPanel.value = false;
}

// ── A3 批量操作 ──────────────────────────────────────────────
/** ⭐ 全选 —— 只作用于**当前窗口内**的行。
 *  假装「全选」= 选了整页是不可见的，用户点完全选却只处理了几十行。 */
const allSelected = computed(
  () => winRows.value.length > 0 && winRows.value.every((w) => selectedRows.value.has(w.ri)),
);
/** ⭐ 部分选中 —— 表头复选框显示半选方块 */
const someSelected = computed(() => {
  const ws = winRows.value;
  return ws.some((w) => selectedRows.value.has(w.ri)) && !allSelected.value;
});
function toggleSelectAll() {
  const next = new Set(selectedRows.value);
  if (allSelected.value) winRows.value.forEach((w) => next.delete(w.ri));
  else winRows.value.forEach((w) => next.add(w.ri));
  selectedRows.value = next;
}
function toggleRowSelection(ri: number) {
  const next = new Set(selectedRows.value);
  if (next.has(ri)) next.delete(ri);
  else next.add(ri);
  selectedRows.value = next;
}
function clearSelection() {
  selectedRows.value = new Set();
}
/** ⭐ 选中行按自然顺序排好 —— 否则复制出来的 TSV 顺序是哈希序，没法用 */
const selectedRowList = computed(() =>
  [...selectedRows.value].sort((a, b) => a - b).map((ri) => state.rows[ri]).filter(Boolean),
);

/** ⭐ 批量复制选中行（TSV，带表头） */
function batchCopy() {
  const rows = selectedRowList.value;
  if (!rows.length) return;
  const cols = state.columns.map((c) => c.name);
  const lines = [cols.join("\t")];
  for (const row of rows) {
    // ⭐ 空串与 NULL 必须区分：空串输出 `""`，NULL 输出 NULL 文本
    lines.push(cols.map((c) => {
      const v = row[c];
      return v === null || v === undefined ? "NULL" : `"${String(v).replace(/"/g, '""')}"`;
    }).join("\t"));
  }
  navigator.clipboard.writeText(lines.join("\n"));
  notify("info", `已复制 ${rows.length} 行（TSV 格式）`);
}

/** ⭐ 批量复制为 INSERT 语句 —— 转义交给 sqlLiteral，不自己拼字符串 */
function batchCopyAsSQL() {
  const table = state.tableDef?.name;
  const rows = selectedRowList.value;
  if (!table || !rows.length) return;
  const cols = state.columns.map((c) => `"${c.name}"`).join(", ");
  const lines = rows.map((row) => {
    const vals = state.columns.map((c) => sqlLiteral(row[c.name])).join(", ");
    return `INSERT INTO "${table}" (${cols}) VALUES (${vals});`;
  });
  navigator.clipboard.writeText(lines.join("\n"));
  notify("info", `已复制 ${rows.length} 条 INSERT 语句`);
}

/** ⭐ 批量删除 —— 走真实写入通道，逐条 WHERE 主键，出错立刻停并说清。
 *
 *  ⚠ `api.execute` 在出错时是 **reject**，不是返回 `{ok:false}`。
 *    不 try/catch 的话批量删到一半失败会变成一个没人接的 Promise 拒绝：
 *    控制台一条红字、用户什么都看不到，还以为删成功了。
 */
async function batchDelete() {
  const table = state.tableDef?.name;
  const rows = selectedRowList.value;
  if (!table || !rows.length) return;
  const pk = state.columns.find((c) => c.isPk);
  if (!pk) {
    // ⭐ 无主键表不给「全选删除」：没有主键就没有稳定行标识，
    //   一旦底层有变动，删掉的可能不是用户看到的那几行。
    notify("error", "该表无主键，无法保证删的是这几行 —— 请改用筛选后逐行删除");
    return;
  }
  if (!confirm(`确认删除选中的 ${rows.length} 行？此操作不可撤销。`)) return;
  let done = 0;
  for (const row of rows) {
    const sql = `DELETE FROM "${table}" WHERE "${pk.name}" = ${sqlLiteral(row[pk.name])};`;
    try {
      await api.execute(sql, true);
    } catch (e) {
      // ⭐ 说清「删到第几行停的」，而不是笼统报个错 ——
      //   部分成功才是最危险的状态，用户必须知道哪些已经没了。
      notify(
        "error",
        done > 0
          ? `已删除 ${done} 行后中断：${e instanceof Error ? e.message : String(e)}`
          : `未删除任何行：${e instanceof Error ? e.message : String(e)}`,
      );
      break;
    }
    done++;
  }
  clearSelection();
  await actions.loadTable(table);
  if (done === rows.length) notify("info", `已删除 ${done} 行`);
}

// ── 单元格取值 ──────────────────────────────────────────────
function cellText(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}
function isEnum(col: ColumnDef): boolean {
  return col.category === "enum" && !!col.enumValues && Object.keys(col.enumValues).length > 0;
}
function enumText(col: ColumnDef, v: unknown): string {
  return col.enumValues?.[String(v)] ?? String(v);
}
function isUrl(v: unknown): boolean {
  return typeof v === "string" && /^https?:\/\//.test(v);
}
function rowClass(rowIdx: number) {
  return {
    "is-current": detail.value?.row === rowIdx,
    "is-selected": selectedRows.value.has(rowIdx),
  };
}

/** ⭐ 该单元格是否有未保存的改动（用背景色标出来） */
function isDirty(rowIdx: number, col: string): boolean {
  const table = state.tableDef?.name;
  if (!table) return false;
  return (
    state.edits.byTable.get(table)?.some((e) => e.row === rowIdx && e.col === col) ?? false
  );
}

/** ⭐ 大字段：主查询已截断，双击才拉完整值 */
async function openDetail(col: ColumnDef, rowIdx: number) {
  if (!state.tableDef) return;
  detailLoading.value = true;
  detail.value = { col, row: rowIdx, text: "", total: 0, hex: null };
  try {
    const r = await api.cell(state.tableDef.name, rowIdx, col.name, 0, 20000);
    detail.value = { col, row: rowIdx, text: r.text, total: r.totalLen, hex: r.hex ?? null };
  } catch (e) {
    notify("error", e instanceof Error ? e.message : String(e));
    detail.value = null;
  } finally {
    detailLoading.value = false;
  }
}

// ── A4-7 Cell Renderer：五类补齐 ─────────────────────────────
/**
 * ⭐ 渲染规则全部依据**后端元数据 + 值形态**，不按列名猜：
 *   · date/dateTime → category 是日期类
 *   · image          → 值是 http(s) URL 且以图片扩展名结尾（与既有 isUrl 同类的值形态判断）
 *   · array          → category=json 且解析出来是数组
 *   · blob/hex       → category=binary（值形如 ⟨binary⟩ N B；hex 走详情面板）
 */
function isImageUrl(v: unknown): v is string {
  return typeof v === "string" && /^https?:\/\/\S+\.(?:png|jpe?g|gif|webp|svg|bmp|avif)(?:\?\S*)?$/i.test(v);
}
function jsonArrayOf(col: ColumnDef, v: unknown): unknown[] | null {
  if (col.category !== "json") return null;
  if (Array.isArray(v)) return v;
  if (typeof v !== "string") return null;
  const t = v.trim();
  if (!t.startsWith("[")) return null;
  try {
    const p = JSON.parse(t);
    return Array.isArray(p) ? p : null;
  } catch {
    return null;
  }
}
/** ⭐ 日期友好格式；完整值留在 title，不丢信息 */
function fmtDate(col: ColumnDef, v: unknown): string | null {
  if (col.category !== "date" && col.category !== "dateTime") return null;
  if (typeof v !== "string") return null;
  const m = v.match(/^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2})(?::\d{2})?)?/);
  if (!m) return null;
  return col.category === "date" ? m[1] : `${m[1]} ${m[2] ?? ""}`.trim();
}
/** ⭐ 二进制占位符 → 字节数。后端给的是 `⟨binary⟩ N B`（网格不铺 hex） */
function binaryBytes(v: unknown): number | null {
  if (typeof v !== "string") return null;
  const m = v.match(/^⟨binary⟩\s+(\d+)\s*B$/);
  return m ? Number(m[1]) : null;
}
/** 图片加载失败（内网/离线常见）→ 收起缩略图，露出 URL 文本 */
const failedImgs = ref(new Set<string>());
function imgFailed(url: string): boolean { return failedImgs.value.has(url); }
function markImgFailed(url: string) {
  failedImgs.value = new Set(failedImgs.value).add(url);
}

/** ⭐ hex dump 按 16 字节一行折行 —— 一长串 hex 没法看 */
function formatHex(hex: string): string[] {
  const clean = hex.replace(/[^0-9a-fA-F]/g, "");
  const out: string[] = [];
  for (let i = 0; i < clean.length; i += 32) {
    const chunk = clean.slice(i, i + 32);
    out.push((chunk.match(/../g) ?? []).join(" "));
  }
  return out;
}

// ── A4-6 单元格右键菜单 ──────────────────────────────────────
/**
 * ⭐ 右键菜单是 Navicat 的高频入口；「设 NULL」「设空串」必须是**两个独立项**
 *   （#10505：合成一个「清空」就等于没有设 NULL 的能力）。
 *
 * ⚠ 菜单用 fixed 定位（视口坐标）；关闭条件有三个：点击别处 / Esc / 滚动。
 *    漏一个就会出现「菜单跟着表格一起滚」的鬼故事。
 */
const ctxMenu = ref<{ x: number; y: number; row: number; col: ColumnDef } | null>(null);

function onCellContextMenu(e: MouseEvent, rowIdx: number, col: ColumnDef) {
  e.preventDefault();
  e.stopPropagation();
  ctxMenu.value = { x: e.clientX, y: e.clientY, row: rowIdx, col };
}
function closeCtxMenu() { ctxMenu.value = null; }

/**
 * ⭐ 菜单的「点外面关 / Esc 关」用**常驻**监听器 + 开门检查，而不是
 *    「打开时挂、关闭时摘」。后者的三种坏法全部真实发生过：
 *
 *    1. 挂在 setTimeout(0) 里 → 右键后紧接着按 Esc，第一次按键被整个吞掉
 *       （菜单开着、按了没反应、控制台一声不响）；
 *    2. 靠 watch 回调 return 的清理函数摘监听 → **Vue 根本不调它**
 *       （onCleanup 要以第三参数传），每开合一轮就漏一对监听器；
 *    3. capture 级 mousedown 不排除菜单自身 → 点菜单项时先被它卸掉菜单，
 *       click 永远不触发 —— 7 个菜单项全是摆设，页面还一切正常。
 *
 *    常驻监听器没有开关窗口期，三个问题一起消失：
 *    mousedown 一定早于 contextmenu，所以「右键按下」不会误关自己。
 */
function onGlobalKeydown(e: KeyboardEvent) {
  if (e.key === "Escape" && ctxMenu.value) closeCtxMenu();
}
function onGlobalMousedown(e: MouseEvent) {
  if (!ctxMenu.value) return;
  // 点在菜单里面：交给菜单项自己的 click，不关
  const menu = document.querySelector("[data-test=cell-ctx-menu]");
  if (menu && e.target instanceof Node && menu.contains(e.target)) return;
  closeCtxMenu();
}
onMounted(() => {
  window.addEventListener("keydown", onGlobalKeydown);
  window.addEventListener("mousedown", onGlobalMousedown, { capture: true });
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onGlobalKeydown);
  window.removeEventListener("mousedown", onGlobalMousedown, { capture: true });
});

/** 右键菜单项公用的「这一格的值」 */
function ctxValue(): unknown {
  const m = ctxMenu.value;
  if (!m) return undefined;
  return state.rows[m.row]?.[m.col.name];
}
function ctxEdit() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  startEdit(m.row, m.col, ctxValue());
}
function ctxSetNull() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  setNull(m.row, m.col.name);
}
function ctxSetEmpty() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  setEmptyString(m.row, m.col.name);
}
function ctxCopy() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  copyCellValue(state.rows[m.row] ?? {}, m.col);
}
function ctxCopySql() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  copyAsSQL(state.rows[m.row] ?? {});
}
function ctxDetail() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  void openDetail(m.col, m.row);
}
function ctxDelete() {
  const m = ctxMenu.value;
  if (!m) return;
  closeCtxMenu();
  void deleteRow(m.row);
}

// ── 表头操作 ────────────────────────────────────────────────
/** ⭐ 排序交给 store：它会重置 offset 并重查（内存排序只对当前页有效）。
 *  ⚠ 列序拖拽结束时会派发一次 click —— 用 suppressSortClick 把它挡掉，
 *    否则用户刚排好的列顺手就被加了排序，两个意图缠在一起。 */
function toggleSort(col: string) {
  if (suppressSortClick.value) return;
  actions.toggleSort(col);
}

const gridSQL = computed(() => {
  const t = state.tableDef?.name;
  return t ? `SELECT * FROM "${t}" LIMIT ${state.rowLimit}` : "";
});
function openInEditor() {
  if (!state.tableDef) return;
  emit("run", gridSQL.value);
}
function openStructure() {
  if (!state.tableDef) return;
  const id = `s:${state.tableDef.name}`;
  if (!state.tabs.some((t) => t.id === id)) {
    state.tabs.push({
      id, title: `${state.tableDef.name} 结构`, kind: "structure", table: state.tableDef.name,
    });
  }
  state.activeTabId = id;
}

// ⭐ 复制单元格内容（多种格式）
function copyCellValue(row: Record<string, unknown>, c: ColumnDef): void {
  const v = row[c.name];
  if (v === null) {
    navigator.clipboard.writeText("NULL");
  } else if (typeof v === "number") {
    navigator.clipboard.writeText(String(v));
  } else if (typeof v === "boolean") {
    navigator.clipboard.writeText(v ? "true" : "false");
  } else if (c.category === "json") {
    navigator.clipboard.writeText(JSON.stringify(v));
  } else if (typeof v === "string" && /^https?:\/\//.test(v)) {
    navigator.clipboard.writeText(v);
  } else {
    navigator.clipboard.writeText(String(v));
  }
}

// ⭐ 复制整行为多种格式
function copyRowFormats(row: Record<string, unknown>) {
  // TSV 格式
  const tsv = state.columns.map((c) => {
    const v = row[c.name];
    if (v === null) return "NULL";
    if (typeof v === "boolean") return v ? "true" : "false";
    if (typeof v === "number") return String(v);
    if (c.category === "json") return JSON.stringify(v);
    if (typeof v === "string" && /^https?:\/\//.test(v)) return v;
    return `"${String(v).replace(/"/g, '""')}"`;
  }).join("\t");
  navigator.clipboard.writeText(tsv);
  notify("info", "已复制整行（TSV 格式）");

  // SQL INSERT 格式
  if (!state.tableDef) return;
  const cols = state.columns.map((c) => `"${c.name}"`).join(", ");
  const vals = state.columns.map((c) => {
    const v = row[c.name];
    if (v === null) return "NULL";
    if (typeof v === "number") return String(v);
    if (typeof v === "boolean") return v ? "1" : "0";
    return `'${String(v).replace(/'/g, "''")}'`;
  }).join(", ");
  const sql = `INSERT INTO "${state.tableDef.name}" (${cols}) VALUES (${vals});`;
  navigator.clipboard.writeText(sql);
  notify("info", "已复制为 INSERT 语句");
}

/** ⭐ 编辑框的初始值：NULL 显示为空文本框，但不能变成字符串 "null" */
function formatEditable(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/**
 * ⭐ 编辑框的值 → JS 值。空文本框一律视为「设成空串」，而不是 NULL。
 *
 * ⭐ 必须按**列类型**还原，而不是把输入框里的字符串原样塞进去：
 *   枚举列下拉给的是 `"2"`，数值列给的是 `"42"`。
 *   直接塞字符串会生成 `SET "status" = '2'` —— SQLite 靠隐式转换能过，
 *   换到 Postgres/MySQL 的强类型列就是类型错误，或者干脆写进去一个字符串。
 */
function parseEditable(text: string, col?: ColumnDef): unknown {
  // ⭐ 空串就是空串；要设 NULL 必须走显式的「设 NULL」按钮
  if (text === "") return "";
  // ⭐ 按**声明类型**还原，而不是按 category：
  //   category="enum" 会丢掉底层类型（INTEGER 还是 TEXT），
  //   只看 category 就只能把 "2" 当字符串写进去。
  const ty = (col?.ty ?? "").toUpperCase();
  const numeric = /INT|REAL|FLOA|DOUB|NUM|DECIMAL/.test(ty) && !/CHAR|TEXT|CLOB/.test(ty);
  if (numeric || col?.category === "number") {
    const n = Number(text);
    // ⭐ 不做隐式转换：值与列类型对不上就让数据库报错并显示位置，
    //   悄悄改成 0 或 null 比报错糟糕得多。
    return Number.isFinite(n) ? n : text;
  }
  if (col?.category === "boolean") {
    return text === "1" || text === "true" ? 1 : 0;
  }
  if (col?.category === "json") {
    try { return JSON.parse(text); } catch { return text; }
  }
  return text;
}

// ── A1-8 类型化编辑器 ────────────────────────────────────────
/**
 * ⭐ 不是所有列都配得上同一个输入框。
 *
 * 枚举列给文本框 = 让用户手打数字键，还可能打错；
 * 布尔列给文本框 = 让用户猜该填 true 还是 1；
 * JSON 列给文本框 = 括号少一个都不告诉你哪儿错。
 *
 * ⭐ 关键约束：**控件类型只依据后端给的元数据**，绝不按列名猜。
 *   加一种数据库、换一个列名，这套判断不用改一行。
 */
type EditorKind = "enum" | "bool" | "json" | "text";

const editKind = ref<EditorKind>("text");
/** JSON 编辑失败时的提示；非空则禁止提交 */
const editError = ref("");

/** 视图 / 无主键表：整表只读，且要告诉用户**为什么** */
const tableReadOnly = computed(() => {
  const t = state.tableDef;
  if (!t) return null;
  if (t.kind === "view") return "这是视图，数据来自查询，不能直接编辑";
  if (!state.columns.some((c) => c.isPk)) {
    return "该表没有主键，无法保证改到的是你看到的那一行";
  }
  return null;
});

function editorKindFor(col: ColumnDef): EditorKind {
  if (col.category === "enum" && col.enumValues && Object.keys(col.enumValues).length) {
    return "enum";
  }
  if (col.category === "boolean") return "bool";
  if (col.category === "json") return "json";
  return "text";
}

function startEdit(rowIdx: number, col: ColumnDef, value: unknown) {
  if (tableReadOnly.value) {
    notify("warn", tableReadOnly.value);
    return;
  }
  editingCell.value = { row: rowIdx, col: col.name, value: formatEditable(value) };
  editKind.value = editorKindFor(col);
  editError.value = "";
}

/** ⭐ 提交前的类型校验 —— 不合格就地拒绝，不把脏数据塞进缓冲 */
function validateEdit(kind: EditorKind, text: string): string {
  if (kind === "json" && text.trim() !== "") {
    try {
      JSON.parse(text);
    } catch (e) {
      return `JSON 格式错误：${e instanceof Error ? e.message : String(e)}`;
    }
  }
  return "";
}

/** ⭐ 勾选布尔列：先把草稿写成 "1"/"0"，再走同一条 commitEdit 通道。
 *  不另开一条写入路径 —— 否则布尔列就会绕过校验与撤销栈。 */
function setBoolDraft(e: Event, rowIdx: number, colName: string) {
  const on = (e.target as HTMLInputElement).checked;
  if (editingCell.value) editingCell.value = on ? "1" : "0";
  commitEdit(rowIdx, colName);
}

/**
 * ⭐ 提交一次单元格编辑 —— 进缓冲，**不落库**。
 *
 * ⚠ 值从 `editingCell` 内部取，**不要**从模板传参。
 *    Vue 模板会自动解包 `ref`，所以模板里的 `editingCell` 已经是对象本身，
 *    再写 `editingCell.value` 拿到的是 `undefined` ——
 *    参数悄悄变成 undefined，改动被当成「没改」而丢弃。
 *    （这与之前 `selectedRows.value.length` 白屏是同一类错误。）
 */
function commitEdit(rowIdx: number, col: string) {
  const table = state.tableDef?.name;
  const raw = editingCell.value?.value;
  if (!table || raw === undefined) return;
  const colDef = state.columns.find((c) => c.name === col);

  // ⭐ 提交前先校验：JSON 写坏了就留在编辑态并说清哪儿坏，
  //    绝不先收进缓冲再等保存时才报错 —— 那时候用户已经翻页了。
  const bad = validateEdit(editKind.value, raw);
  if (bad) {
    editError.value = bad;
    return;
  }
  editingCell.value = null;
  editError.value = "";

  // ⭐ `edits` 在 **state** 上，不在 actions 上 —— 写成 actions.edits 会是
//    undefined，报错还很难联想到真正原因。
  const original = state.edits.byTable.get(table)?.find(
    (e) => e.row === rowIdx && e.col === col,
  );
  // ⭐ 已进入缓冲的改动，用缓冲里的当前值判断「是否真的改了」
  const current = original ? original.newValue : state.rows[rowIdx]?.[col];
  const next = parseEditable(raw, colDef);
  // ⭐ 必须用 sameValue，不能用 `String(a ?? "") === String(b ?? "")`：
  //    后者把 null 和 "" 都归成 ""，于是「把 NULL 改成空串」会被**静默丢弃** ——
  //    不报错、不提示，用户以为改好了，实际什么都没发生。
  if (sameValue(current, next)) return;

  actions.editCell(table, rowIdx, col, next);
}

/** ⭐ 显式「设成 NULL」—— 与「设成空串」是两个不同操作 */
function setNull(rowIdx: number, col: string) {
  const table = state.tableDef?.name;
  if (!table) return;
  actions.editCell(table, rowIdx, col, null);
}

/** ⭐ 显式「设成空串」—— 同样是一个独立操作，不能与设 NULL 合并 */
function setEmptyString(rowIdx: number, col: string) {
  const table = state.tableDef?.name;
  if (!table) return;
  actions.editCell(table, rowIdx, col, "");
}

/** 这格现在是不是 NULL / 空串 —— 用来把对应按钮置灰 */
function isNullCell(rowIdx: number, col: string): boolean {
  const v = state.rows[rowIdx]?.[col];
  return v === null || v === undefined;
}
function isEmptyStringCell(rowIdx: number, col: string): boolean {
  return state.rows[rowIdx]?.[col] === "";
}

/**
 * ⭐ A1-7 撤销 / 重做。
 *
 * ⭐ 粒度是「一次进入编辑 → 提交」，不是每次按键 ——
 *    用户在同一个格子里改三次、按一次 ⌘Z，期望回到**进入编辑前**的原值，
 *    而不是中间某个中间态。
 *
 * ⭐ 正在编辑某个格子时不响应 ⌘Z：那时候 ⌘Z 应该归文本框管，
 *    把单元格值跳掉会让人以为打字丢了。
 */
function onKeydown(e: KeyboardEvent) {
  const meta = e.metaKey || e.ctrlKey;
  if (!meta || e.key.toLowerCase() !== "z") return;
  if (editingCell.value) return;
  e.preventDefault();
  if (e.shiftKey) {
    if (!actions.redoEdit()) notify("info", "没有可重做的改动");
  } else {
    if (!actions.undoEdit()) notify("info", "没有可撤销的改动");
  }
  const t = state.tableDef?.name;
  if (t) void actions.loadTable(t);
}
onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));

/** 撤销栈深度 —— 拿来做按钮的可用态 */
const canUndo = computed(() => state.edits.undo.length > 0);
const canRedo = computed(() => state.edits.redo.length > 0);

/** 待保存的 SQL —— 保存前必须让用户看清楚 */
const pendingSql = computed(() => {
  const table = state.tableDef?.name;
  if (!table || !actions.hasPending(table)) return null;
  return actions.buildSaveSql(table);
});

const pendingCount = computed(() => {
  const table = state.tableDef?.name;
  return table ? actions.pendingCount(table) : 0;
});

/** ⭐ 保存：必须先弹确认框展示 SQL，再落库 */
async function saveEdits() {
  const table = state.tableDef?.name;
  if (!table || !pendingSql.value) return;
  if (pendingSql.value.error) {
    notify("error", pendingSql.value.error);
    return;
  }
  const sql = pendingSql.value.statements.join("\n");
  // ⭐ 用原生 confirm：这一版先把「看到 SQL 再落库」的闭环打通，
  //   F1 阶段换成带影响面评估的正式对话框。
  if (!confirm(`即将执行 ${pendingSql.value.statements.length} 条语句：\n\n${sql}\n\n确认落库？`)) {
    return;
  }
  await actions.saveEdits(table, true);
}

function discardEdits() {
  const table = state.tableDef?.name;
  if (!table) return;
  if (pendingCount.value && !confirm(`放弃 ${pendingCount.value} 处未保存的改动？`)) return;
  actions.discardEdits(table);
  notify("info", "已放弃未保存的改动");
}

function copyRow(row: Record<string, unknown>) {
  const text = state.columns.map((c) => String(row[c.name] ?? "")).join("\t");
  navigator.clipboard.writeText(text);
  notify("info", "已复制整行（TSV 格式）");
}

function copyAsSQL(row: Record<string, unknown>) {
  if (!state.tableDef) return;
  const cols = state.columns.map((c) => `"${c.name}"`).join(", ");
  const vals = state.columns.map((c) => {
    const v = row[c.name];
    if (v === null) return "NULL";
    if (typeof v === "number") return String(v);
    return `'${String(v).replace(/'/g, "''")}'`;
  }).join(", ");
  const sql = `INSERT INTO "${state.tableDef.name}" (${cols}) VALUES (${vals});`;
  navigator.clipboard.writeText(sql);
  notify("info", "已复制为 INSERT 语句");
}

/**
 * ⭐ 删除单行 —— 真的落库。
 *
 * 之前这里只弹一句「演示模式不真正执行」，但按钮写的是「删除」、
 * 还弹了确认框。用户以为删掉了，刷新一下行还在 —— 这是最坏的一种骗人：
 * 连报错都没有。宁可拒绝执行，也不要假装成功。
 */
async function deleteRow(rowIdx: number) {
  const table = state.tableDef?.name;
  if (!table) return;
  const row = state.rows[rowIdx];
  if (!row) return;
  const pk = state.columns.find((c) => c.isPk);
  if (!pk) {
    notify("error", "该表无主键，无法保证删的是这一行");
    return;
  }
  const sql = `DELETE FROM "${table}" WHERE "${pk.name}" = ${sqlLiteral(row[pk.name])};`;
  if (!confirm(`确认删除第 ${rowIdx + 1} 行？\n\n${sql}`)) return;
  try {
    const r = await api.execute(sql, true);
    await actions.loadTable(table);
    notify("info", `已删除 ${r.affectedRows} 行`);
  } catch (e) {
    notify("error", `未删除任何行：${e instanceof Error ? e.message : String(e)}`);
  }
}

function exportTable() {
  if (!state.tableDef) return;
  const cols = state.columns.map((c) => c.name);
  const lines = [cols.join(",")];
  for (const row of state.rows) {
    lines.push(cols.map((c) => `"${String(row[c] ?? "").replace(/\"/g, '\"\"')}"`).join(","));
  }
  const blob = new Blob([lines.join("\n")], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${state.tableDef.name}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function clearFilter() {
  filterCol.value = "";
  filterVal.value = "";
  filterOp.value = "=";
  showFilter.value = false;
}
</script>

<template>
  <div class="page">
    <!-- ══ 表头 ══ -->
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">▦</span>
        <span class="page-head__name">{{ state.tableDef?.name ?? "—" }}</span>
        <span class="page-head__comment">{{ state.tableDef?.comment }}</span>
        <span class="page-head__stats" v-if="state.tableDef">
          {{ compact(state.tableDef.rowEstimate) }} 行
          <template v-if="findingsForTable.length">
            · <span style="color: var(--warning)">⚠ {{ findingsForTable.length }} 个问题</span>
          </template>
        </span>
        <span class="grow"></span>
        <span class="badge" :class="`badge--${dangerClass(state.lastDanger)}`">
          {{ dangerIcon(state.lastDanger) }} {{ capabilities.byId(state.connectionKind)?.label }}
        </span>
        <button class="btn btn--sm" @click="state.tableDef && actions.loadTable(state.tableDef.name)">刷新</button>
      </div>

      <!-- 工具栏：⭐ 分页 + 筛选 + 排序 + 密度 -->
      <div class="toolbar">
        <!-- ⭐ 分页控件：页大小 / 上一页 / 页码 / 下一页 / 跳页 -->
        <div class="pager">
          <select class="input" style="width: 84px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                  v-model.number="pageLimit">
            <option :value="100">100</option>
            <option :value="200">200</option>
            <option :value="500">500</option>
            <option :value="1000">1,000</option>
            <option :value="5000">5,000</option>
          </select>
          <button class="btn btn--sm" :disabled="state.offset <= 0" @click="actions.prevPage()"
                  title="上一页">◀</button>
          <span class="pager__pos mono">
            第 {{ currentPage }} 页<template v-if="totalPages !== null && !state.totalStale"> / {{ totalPages }}</template>
          </span>
          <button class="btn btn--sm" :disabled="!state.hasNextPage" @click="actions.nextPage()"
                  title="下一页">▶</button>
          <input class="input input--mono pager__jump" style="height: var(--h-btn-sm); font-size: var(--fs-xs)"
                 v-model="jumpPage" placeholder="跳页" @keydown.enter="doJump" />
        </div>

        <div class="toolbar__sep"></div>

        <button class="btn btn--sm" :class="{ 'btn--primary': showFilter || state.filters.length }"
                @click="showFilter = !showFilter">
          筛选<template v-if="state.filters.length"> · {{ state.filters.length }}</template>
        </button>

        <select class="input" style="width: 78px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                :value="state.density" @change="actions.setDensity(($event.target as HTMLSelectElement).value as any)">
          <option value="compact">紧凑</option>
          <option value="normal">标准</option>
          <option value="comfort">宽松</option>
        </select>

        <div class="toolbar__sep"></div>

        <!-- ⭐ A4-4 列显示/隐藏 + 布局重置 -->
        <div class="colpanel-anchor">
          <button class="btn btn--sm" :class="{ 'btn--primary': hiddenCols.size }"
                  data-test="col-panel-btn"
                  :title="hiddenCols.size ? `已隐藏 ${hiddenCols.size} 列` : '显示 / 隐藏列'"
                  @click="showColPanel = !showColPanel">
            列<template v-if="hiddenCols.size"> · {{ hiddenCols.size }}</template>
          </button>
          <div v-if="showColPanel" class="colpanel-catch" @click="showColPanel = false"></div>
          <div v-if="showColPanel" class="colpanel" data-test="col-panel">
            <div class="colpanel__head">
              <strong>列显示</strong>
              <span class="grow"></span>
              <button class="btn btn--sm btn--ghost" @click="showAllCols">全部显示</button>
              <button class="btn btn--sm btn--ghost" data-test="col-reset" @click="resetColLayout">重置布局</button>
            </div>
            <label v-for="c in state.columns" :key="c.id" class="colpanel__item">
              <input type="checkbox" :checked="!hiddenCols.has(c.name)"
                     :data-test="`col-toggle-${c.name}`"
                     @change="toggleColVisible(c.name)" />
              <span class="mono">{{ c.name }}</span>
              <span class="muted small">{{ c.ty }}</span>
            </label>
            <div class="colpanel__foot muted small">列宽 / 列序 / 显隐按「连接 + 表名」记住</div>
          </div>
        </div>

        <!-- ⭐ A4-8 转置 / 隐藏 NULL / 隐藏空串（均只作用于当前页，UI 明示） -->
        <button class="btn btn--sm" :class="{ 'btn--primary': transpose }" data-test="transpose-btn"
                title="行列互换（仅当前可见行，只读）" @click="transpose = !transpose">转置</button>
        <button class="btn btn--sm" :class="{ 'btn--primary': hideNull }" data-test="hide-null-btn"
                title="隐藏含有 NULL 的行（仅本页）" @click="hideNull = !hideNull">隐藏 NULL</button>
        <button class="btn btn--sm" :class="{ 'btn--primary': hideEmpty }" data-test="hide-empty-btn"
                title="隐藏含有空串的行（仅本页）" @click="hideEmpty = !hideEmpty">隐藏空串</button>

        <div class="toolbar__sep"></div>
        <input class="input input--mono searchbox" style="width: 170px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
               v-model="searchTerm" placeholder="搜索本页"
               aria-label="搜索本页（仅本页）" />
        <span class="muted small">
          仅本页 {{ filteredRows.length }} 行
          <template v-if="hideNull || hideEmpty">
            · 已隐藏<template v-if="hideNull"> NULL</template><template v-if="hideNull && hideEmpty"> /</template><template v-if="hideEmpty"> 空串</template>
          </template>
        </span>

        <div class="grow"></div>
        <!-- ⭐ 撤销/重做：粒度 = 一次编辑提交（⌘Z / ⌘⇧Z） -->
        <button class="btn btn--sm" :disabled="!canUndo"
                title="撤销上一次编辑（⌘Z）" @click="actions.undoEdit()">↶</button>
        <button class="btn btn--sm" :disabled="!canRedo"
                title="重做（⌘⇧Z）" @click="actions.redoEdit()">↷</button>
        <!-- ⭐ 未保存改动：条数 + 保存/放弃 -->
        <template v-if="pendingCount">
          <span class="badge badge--warn" :title="`${pendingCount} 处改动尚未落库`">
            ● {{ pendingCount }} 处未保存
          </span>
          <button class="btn btn--sm btn--primary" @click="saveEdits">保存</button>
          <button class="btn btn--sm btn--ghost" @click="discardEdits">放弃</button>
        </template>
        <button class="btn btn--sm" :disabled="!state.tableDef" @click="exportTable">导出 CSV</button>
        <button class="btn btn--sm" :disabled="!state.tableDef" @click="openStructure">表结构</button>
        <button class="btn btn--sm" :disabled="!state.tableDef" @click="openInEditor">在查询编辑器打开</button>
      </div>
    </div>

    <!-- ══ 筛选条件条：可见可清除 ══ -->
    <div v-if="state.filters.length || showFilter" class="filterbar">
      <!-- 条件构造器 -->
      <template v-if="showFilter">
        <select class="input" style="width: 140px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                v-model="filterCol">
          <option value="">选择列…</option>
          <option v-for="c in state.columns" :key="c.id" :value="c.name">
            {{ c.name }}{{ c.comment ? `（${c.comment}）` : '' }}
          </option>
        </select>
        <select class="input" style="width: 104px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                v-model="filterOp">
          <option v-for="o in FILTER_OPS" :key="o.op" :value="o.op">{{ o.label }}</option>
        </select>
        <input v-if="needsValue"
               class="input input--mono" style="width: 180px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
               v-model="filterVal" placeholder="值" @keydown.enter="applyFilter" />
        <button class="btn btn--sm btn--primary" :disabled="!filterCol" @click="applyFilter">添加</button>
        <div class="toolbar__sep"></div>
      </template>

      <!-- ⭐ 已生效的条件 -->
      <template v-for="(f, i) in state.filters" :key="i">
        <span class="filterchip">
          <span class="mono">{{ f.col }}</span>
          <span class="muted">{{ FILTER_OPS.find(o => o.op === f.op)?.label ?? f.op }}</span>
          <template v-if="f.op !== 'IS NULL' && f.op !== 'IS NOT NULL'">
            <span class="mono">{{ f.value === '' ? "''" : f.value }}</span>
          </template>
          <button class="filterchip__x" title="移除该条件" @click="removeFilter(i)">✕</button>
        </span>
      </template>
      <button v-if="state.filters.length" class="btn btn--sm btn--ghost"
              @click="actions.clearFilters()">全部清除</button>

      <!-- ⭐ 明确告知条件已下推到数据库，不是内存过滤 -->
      <span class="grow"></span>
      <span class="muted small" v-if="state.filters.length" title="筛选条件会作为 WHERE 子句发给数据库">
        ⓘ 条件已下推到数据库
      </span>
    </div>

    <!-- ══ 表格 ══ -->
    <div class="grid-wrap" v-if="state.columns.length" ref="wrapRef" @scroll.passive="onWrapScroll">
      <!-- ⭐ 列头筛选面板：挂在表格容器内（position: relative） -->
      <ColumnFilterPanel
        v-if="filterPanelCol && filterPanelMeta"
        :column="filterPanelCol"
        :category="filterPanelMeta.category"
        :enum-values="filterPanelMeta.enumValues"
        @close="filterPanelCol = null"
        @applied="filterPanelCol = null"
      />
      <table class="grid" v-if="!transpose">
        <thead>
          <tr :style="{ height: 'var(--h-toolbar)' }">
            <th class="rownum" style="width: 88px; min-width:88px; max-width:88px">
              <span class="pickcell">
                <!-- ⭐ 全选只作用于「当前窗口内」的行：窗口化之后表里的 DOM 行
                     就是用户能看到的行，说全选却只选几十行等于骗人。
                     title 里说清楚范围，避免用户以为选了整页。 -->
                <input type="checkbox"
                       :checked="allSelected"
                       :indeterminate="someSelected"
                       title="全选当前可见行"
                       aria-label="全选当前可见行"
                       @change="toggleSelectAll" />
                <span>#</span>
              </span>
            </th>
            <th v-for="c in visibleColumns" :key="c.id"
                :data-col="c.name"
                :class="{
                  'is-pk': c.isPk, 'is-fk': c.isFk, 'is-sorted': state.sortCol === c.name,
                  'frozen-pk': c.isPk,
                  'is-reorder-over': !!reordering && reordering.over === c.name && reordering.col !== c.name,
                  'is-reordering': !!reordering && reordering.col === c.name,
                }"
                :title="c.comment ? `${c.comment}\n点击表头排序；点 ▼ 筛选；拖拽改列序/列宽` : '点击表头排序；点 ▼ 筛选；拖拽改列序/列宽'"
                :style="{ width: colWidth(c) + 'px', minWidth: colWidth(c) + 'px', maxWidth: colWidth(c) + 'px' }"
                @mousedown="onThMouseDown($event, c.name)"
                @click="toggleSort(c.name)">
              <div class="th-main">
                <span class="truncate">{{ c.name }}</span>
                <span v-if="c.isPk" style="color: var(--warning)" title="主键">🔑</span>
                <span v-if="c.isFk" style="color: var(--accent)" title="外键">🔗</span>
                <span v-if="!c.nullable" class="muted" title="NOT NULL">•</span>
                <!-- ⭐ 排序标记读 store（已下推到数据库），不是本地 ref -->
                <span v-if="state.sortCol === c.name" class="muted">
                  {{ state.sortDir === "asc" ? "↑" : "↓" }}
                </span>
                <!-- ⭐ 列头筛选入口：Navicat 的核心操作。 -->
                <button class="th-filter" :class="{ 'is-on': hasColFilter(c.name) }"
                        :title="hasColFilter(c.name) ? '已设置筛选条件' : `按 ${c.name} 筛选（候选值 + 计数）`"
                        @click.stop="toggleFilterPanel(c.name)">▼</button>
              </div>
              <div class="th-type">{{ c.ty }}{{ c.defaultExpr ? ` = ${c.defaultExpr}` : "" }}</div>
              <div v-if="c.comment" class="th-comment">{{ c.comment }}</div>
              <!-- ⭐ A4-3 列宽手柄：左右拖改宽，双击按内容自适应 -->
              <span class="col-resizer" title="拖拽改宽 · 双击自适应"
                    @mousedown.stop="onResizeStart($event, c.name)"
                    @dblclick.stop="autosizeCol(c.name)"></span>
            </th>
            <th style="width: 100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <!-- ⭐ 窗口化：上下 spacer 撑出完整高度，保证滚动条长度与真实行数一致。
               ⚠ 缺了它，滚动条只反映「渲染出来的几十行」，
                 用户会以为整张表就这么点数据。 -->
          <tr v-if="win.padTop > 0" class="win-spacer" aria-hidden="true">
            <td :colspan="visibleColumns.length + 2" :style="{ height: win.padTop + 'px', padding: 0 }"></td>
          </tr>

          <!-- ⭐ 只渲染可视区附近的行。`w.ri` 是 state.rows 里的真实下标，
               搜索本页之后 filteredRows 与 state.rows 会错位，
               拿 filteredRows 的下标去编辑会改到另一行。 -->
          <tr v-for="w in winRows" :key="w.ri" :class="rowClass(w.ri)" :style="{ height: rowH }"
              @click="detail && (detail = null)">
            <td class="rownum">
              <span class="pickcell">
                <input type="checkbox"
                       :checked="selectedRows.has(w.ri)"
                       :aria-label="`选中第 ${w.no} 行`"
                       @change="toggleRowSelection(w.ri)" />
                <span>{{ w.no }}</span>
              </span>
            </td>
            <td v-for="c in visibleColumns" :key="c.id"
                :data-col="c.name"
                :class="{
                  num: c.category === 'number',
                  null: w.row[c.name] === null,
                  'bool-yes': w.row[c.name] === true || w.row[c.name] === 1,
                  'bool-no': w.row[c.name] === false || w.row[c.name] === 0,
                  link: isUrl(w.row[c.name]),
                  json: c.category === 'json',
                  large: c.isLarge && String(w.row[c.name] ?? '').length > 40,
                  'is-dirty': isDirty(w.ri, c.name),
                  'frozen-pk': c.isPk,
                }"
                :title="c.isLarge ? '点击查看完整内容' : undefined"
                @dblclick.stop="openDetail(c, w.ri)"
                @click.stop="startEdit(w.ri, c, w.row[c.name])"
                @contextmenu.stop="onCellContextMenu($event, w.ri, c)">
              <!-- ⭐ A1-8 类型化编辑器：控件由后端元数据决定，不按列名猜 -->
              <template v-if="editingCell?.row === w.ri && editingCell?.col === c.name">
                <!-- 枚举：下拉。选项来自 enumValues，写错值不可能发生 -->
                <select v-if="editKind === 'enum'" class="input cell-editor"
                        :aria-label="`编辑 ${c.name}`"
                        v-model="editingCell.value"
                        @change="commitEdit(w.ri, c.name)"
                        @keydown.escape="editingCell = null"
                        @blur="commitEdit(w.ri, c.name)"
                        @click.stop>
                  <option v-for="(label, val) in c.enumValues" :key="val" :value="String(val)">
                    {{ label }}（{{ val }}）
                  </option>
                </select>
                <!-- 布尔：复选框，勾选直接落 0/1 -->
                <label v-else-if="editKind === 'bool'" class="cell-bool" @click.stop>
                  <input type="checkbox" class="cell-editor"
                         :aria-label="`编辑 ${c.name}`"
                         :checked="editingCell.value === '1' || editingCell.value === 'true'"
                         @change="setBoolDraft($event, w.ri, c.name)" />
                  <span class="muted">{{ editingCell.value === "1" ? "是" : "否" }}</span>
                </label>
                <!-- 文本 / JSON：文本框 + 就地校验 -->
                <input v-else class="input cell-editor"
                       style="height: 20px; font-size: 11px; padding: 0 4px"
                       :aria-label="`编辑 ${c.name}`"
                       v-model="editingCell.value"
                       @blur="commitEdit(w.ri, c.name)"
                       @keydown.enter.prevent="commitEdit(w.ri, c.name)"
                       @keydown.escape="editingCell = null"
                       @click.stop />
                <!-- ⭐ 校验失败必须显示出来，且编辑态不退出 -->
                <div v-if="editError" class="cell-edit-err" @click.stop>{{ editError }}</div>

                <!-- ⭐⭐ NULL 与空串是两个**独立**按钮，不能合并成一个「清空」。
                     文本框清空只等于「设成空串」，所以要设成 NULL 必须有显式入口 ——
                     否则这个值根本改不到（之前 setNull 压根没接上 UI）。 -->
                <div class="cell-edit-ops" @click.stop>
                  <button class="btn btn--mini" :disabled="isNullCell(w.ri, c.name)"
                          title="把这一格设成 SQL NULL"
                          @click="setNull(w.ri, c.name); editingCell = null">设 NULL</button>
                  <button class="btn btn--mini" :disabled="isEmptyStringCell(w.ri, c.name)"
                          title="把这一格设成空字符串（不是 NULL）"
                          @click="setEmptyString(w.ri, c.name); editingCell = null">设空串</button>
                </div>
              </template>
              <template v-else-if="w.row[c.name] === null"><em>NULL</em></template>
              <!-- ⭐ A4-7 blob：二进制不铺 hex，只给字节数；hex 在双击详情里 -->
              <template v-else-if="binaryBytes(w.row[c.name]) !== null">
                <span class="cell-bin" :title="`二进制 ${binaryBytes(w.row[c.name])} 字节 · 双击查看 hex`">
                  ⬢ {{ binaryBytes(w.row[c.name]) }} B
                </span>
              </template>
              <template v-else-if="isEnum(c)">{{ enumText(c, w.row[c.name]) }}</template>
              <!-- ⭐ A4-7 array：JSON 数组渲染成 chips，不糊成一坨字符串 -->
              <template v-else-if="jsonArrayOf(c, w.row[c.name])">
                <span class="cell-arr">
                  <span class="cell-arr__item" v-for="(item, ai) in jsonArrayOf(c, w.row[c.name])" :key="ai">
                    {{ typeof item === "object" ? JSON.stringify(item) : item }}
                  </span>
                </span>
              </template>
              <!-- ⭐ A4-7 image：URL 形态的图片值给缩略图；加载失败回落 URL 文本 -->
              <template v-else-if="isImageUrl(w.row[c.name])">
                <span class="cell-imgbox">
                  <img v-if="!imgFailed(w.row[c.name])" class="cell-img"
                       :src="w.row[c.name]" :alt="w.row[c.name]" loading="lazy"
                       @error="markImgFailed(w.row[c.name])" />
                  <span class="cell-img-txt">{{ w.row[c.name] }}</span>
                </span>
              </template>
              <!-- ⭐ A4-7 date/dateTime：友好格式，完整值留在 title -->
              <template v-else-if="fmtDate(c, w.row[c.name])">
                <span :title="String(w.row[c.name])">{{ fmtDate(c, w.row[c.name]) }}</span>
              </template>
              <template v-else-if="typeof w.row[c.name] === 'object'">{{ cellText(w.row[c.name]) }}</template>
              <template v-else>{{ cellText(w.row[c.name]) }}</template>
            </td>
            <td @click.stop>
              <button class="btn btn--sm btn--ghost" @click="copyRow(w.row)" title="复制整行">复制</button>
              <!-- ⭐ 只读表不给删除入口：视图 / 无主键表根本不该提供写操作 -->
              <button v-if="!tableReadOnly" class="btn btn--sm btn--danger"
                      @click="deleteRow(w.ri)" title="删除行">删除</button>
              <span v-else class="muted small" :title="tableReadOnly">只读</span>
            </td>
          </tr>

          <tr v-if="win.padBottom > 0" class="win-spacer" aria-hidden="true">
            <td :colspan="visibleColumns.length + 2" :style="{ height: win.padBottom + 'px', padding: 0 }"></td>
          </tr>
        </tbody>
      </table>

      <!-- ⭐ A4-8 转置视图：仅当前可见窗口、只读。
           「第几行」在转置后不再是稳定概念，所以不提供编辑入口 ——
           给一个改了不知道会落到哪儿的编辑器，比不给更糟。 -->
      <table class="grid grid--transpose" v-else-if="winRows.length">
        <thead>
          <tr>
            <th class="rownum t-field">字段</th>
            <th v-for="w in winRows" :key="w.ri" class="t-rowhead">第 {{ w.no }} 行</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in visibleColumns" :key="c.id">
            <td class="rownum t-field">
              <span class="truncate" :title="`${c.name} · ${c.ty}`">{{ c.name }}</span>
            </td>
            <td v-for="w in winRows" :key="w.ri"
                :class="{ null: w.row[c.name] === null, num: c.category === 'number' }">
              <em v-if="w.row[c.name] === null">NULL</em>
              <template v-else>{{ isEnum(c) ? enumText(c, w.row[c.name]) : cellText(w.row[c.name]) }}</template>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="transpose" class="empty">
        <div class="empty__icon">▤</div>
        <div class="empty__title">当前窗口没有行</div>
      </div>

      <div v-if="state.loading" style="padding: var(--sp-2); text-align:center" class="muted small">加载中…</div>
    </div>

    <div v-else class="empty">
      <div class="empty__icon">▦</div>
      <div class="empty__title">未打开表</div>
      <div class="small">从左侧对象树双击一张表</div>
    </div>

    <!-- ══ 底栏：说清「当前页」与「总行数」的关系，避免误解 ══ -->
    <div class="grid-foot" v-if="state.tableDef">
      <span class="mono">
        本页 {{ filteredRows.length.toLocaleString() }} 行
        <template v-if="state.total !== null && !state.totalStale">
          · 共 {{ state.total.toLocaleString() }} 行
        </template>
        <template v-else-if="state.filters.length">
          · <span class="muted" title="有筛选时不执行 COUNT（避免全表扫），且未筛选时的总数不适用于当前条件">筛选后总数未统计</span>
        </template>
        <template v-else-if="state.hasNextPage">
          · <span class="muted">还有更多</span>
        </template>
      </span>
      <span class="muted">{{ state.queryMs }} ms</span>
      <span class="grow"></span>
      <!-- ⭐ 批量操作条：只在有选中时出现，不占平时的工作区 -->
      <template v-if="selectedRows.size">
        <span class="badge badge--warn" title="已选中的行会随窗口化保留，滚动后仍然有效">
          已选 {{ selectedRows.size }} 行
        </span>
        <button class="btn btn--sm btn--ghost" @click="batchCopy">复制</button>
        <button class="btn btn--sm btn--ghost" @click="batchCopyAsSQL">复制为 SQL</button>
        <button class="btn btn--sm btn--danger" @click="batchDelete">删除</button>
        <button class="btn btn--sm btn--ghost" @click="clearSelection">清除选择</button>
      </template>
      <!-- ⭐ 模板里 ref 会被自动解包，写 `selectedRows.value` 会变成
           `undefined.value` → 渲染崩掉。script 里才用 `.value`。 -->
      <!-- ⭐ 传输家族来自声明层，不硬编码 `connectionKind === 'sqlite'`：
           加一种数据库时这里自动跟着 YAML 走，不用改前端。 -->
      <span class="muted small" v-else-if="connFamily">
        传输家族：{{ connFamily }}
      </span>
    </div>

    <!-- ══ 单元格详情面板 ══ -->
    <div v-if="detail" class="cell-detail">
      <div class="cell-detail__head">
        <strong class="mono">{{ detail.col.name }}</strong>
        <span class="badge">{{ detail.col.ty }}</span>
        <span v-if="!detail.col.nullable" class="badge">NOT NULL</span>
        <span v-if="detail.col.isPk" class="badge badge--warn">主键</span>
        <span class="muted small" v-if="detail.col.comment">{{ detail.col.comment }}</span>
        <span class="grow"></span>
        <span class="muted small mono">第 {{ detail.row + 1 }} 行 · {{ detail.total }} 字符</span>
        <button class="btn btn--sm" @click="navigator.clipboard.writeText(detail.text)">复制</button>
        <button class="btn btn--sm" @click="detail = null">✕</button>
      </div>
      <div class="cell-detail__body">
        <span v-if="detailLoading" class="muted">加载中…</span>
        <template v-else>{{ detail.text || "（空）" }}</template>
      </div>
      <!-- ⭐ A4-7 hex：二进制列的真实字节（后端 hex() 查询），16 字节一行 -->
      <div v-if="detail.hex" class="cell-detail__hex" data-test="cell-detail-hex">
        <div class="small muted" style="margin-bottom: var(--sp-1)">
          HEX · {{ detail.hex.length / 2 }} 字节
        </div>
        <pre class="mono small">{{ formatHex(detail.hex).join("\n") }}</pre>
      </div>
    </div>

    <!-- ══ A4-6 单元格右键菜单 ══ -->
    <div v-if="ctxMenu" class="ctxmenu" data-test="cell-ctx-menu"
         :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
         @click.stop>
      <div class="ctxmenu__head muted small">
        {{ ctxMenu.col.name }} · 第 {{ ctxMenu.row + 1 }} 行
      </div>
      <button class="ctxmenu__item" data-test="ctx-edit" @click="ctxEdit">✎ 编辑</button>
      <button class="ctxmenu__item" data-test="ctx-set-null"
              :disabled="isNullCell(ctxMenu.row, ctxMenu.col.name)"
              @click="ctxSetNull">设 NULL</button>
      <button class="ctxmenu__item" data-test="ctx-set-empty"
              :disabled="isEmptyStringCell(ctxMenu.row, ctxMenu.col.name)"
              @click="ctxSetEmpty">设空串</button>
      <div class="ctxmenu__sep"></div>
      <button class="ctxmenu__item" @click="ctxCopy">复制</button>
      <button class="ctxmenu__item" @click="ctxCopySql">复制为 SQL</button>
      <button class="ctxmenu__item" @click="ctxDetail">查看详情</button>
      <template v-if="!tableReadOnly">
        <div class="ctxmenu__sep"></div>
        <button class="ctxmenu__item ctxmenu__item--danger" data-test="ctx-delete" @click="ctxDelete">删除行</button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ico { color: var(--text-tertiary); }
/* ⭐ 未保存的单元格：底色提示 + 左侧竖线，一眼能看出改了哪些 */
.is-dirty {
  background: var(--warning-bg);
  box-shadow: inset 2px 0 0 var(--warning);
}

/* ── A4-1 窗口化 spacer ── */
/* ⭐ spacer 只负责撑高度，不参与行号/选中/编辑。
   给 `visibility: hidden` 是为了不让它的边框在视觉上多出一条线。 */
.win-spacer td {
  padding: 0 !important;
  border: none !important;
  background: transparent;
}

/* ── A1-8 类型化编辑器 ── */
.cell-bool {
  display: inline-flex; align-items: center; gap: 4px;
  cursor: pointer; height: 100%;
}
.cell-bool input[type="checkbox"] { accent-color: var(--accent); cursor: pointer; }
/* ⭐ NULL / 空串 两个动作并排放，让「它们是两件事」一眼可见 */
.cell-edit-ops {
  position: absolute; z-index: 5;
  left: 8px; top: 100%;
  display: inline-flex; gap: 4px;
}
.btn--mini {
  height: 18px; padding: 0 6px;
  font-size: 10px; line-height: 1;
}
/* ⭐ 校验错误必须显眼：它挡住了提交，不提示的话用户只会以为没点上 */
.cell-edit-err {
  position: absolute; z-index: 5;
  left: 8px; top: calc(100% - 2px);
  max-width: 320px; padding: 4px 8px;
  border: var(--bw) solid var(--danger);
  border-radius: var(--r-sm);
  background: var(--danger-bg, var(--bg-elevated));
  color: var(--danger);
  font-size: var(--fs-xs);
  white-space: normal;
  box-shadow: var(--shadow-2);
}

/* ── A3 选择列 ── */
/* ⭐ 复选框 + 行号挤在一格里，所以行号列变宽；它本来就是 sticky 冻结列，
   横向滚动时选择框一直可见。 */
.pickcell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-1);
  height: 100%;
}
.pickcell input[type="checkbox"] {
  margin: 0;
  cursor: pointer;
  accent-color: var(--accent);
}
table.grid tbody tr.is-selected td.rownum { color: var(--accent); }

/* ── 列头筛选入口 ── */
.th-filter {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  font-size: 8px;
  line-height: 1;
  padding: 2px 3px;
  border-radius: var(--r-sm);
  flex: 0 0 auto;
}
.th-filter:hover { background: var(--bg-hover); color: var(--text-primary); }
/* ⭐ 有筛选条件的列高亮 —— 让人一眼看到"哪些列被筛过" */
.th-filter.is-on { color: var(--accent); background: var(--accent-bg); }

/* ── 分页控件 ── */
.pager { display: flex; align-items: center; gap: var(--sp-1); }
.pager__pos { font-size: var(--fs-xs); color: var(--text-secondary); white-space: nowrap; }
.pager__jump { width: 64px; text-align: center; }

/* ── 筛选条件条 ── */
.filterbar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding: var(--sp-2) var(--sp-3);
  border-bottom: var(--bw) solid var(--border);
  background: var(--bg-inset);
}
.filterchip {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  padding: 2px var(--sp-1) 2px var(--sp-2);
  border-radius: var(--r-sm);
  background: var(--accent-bg);
  border: var(--bw) solid var(--accent);
  font-size: var(--fs-xs);
  white-space: nowrap;
}
.filterchip__x {
  border: none;
  background: none;
  color: var(--text-tertiary);
  cursor: pointer;
  padding: 0 2px;
  font-size: 10px;
  line-height: 1;
}
.filterchip__x:hover { color: var(--danger); }

/* ── A4-2 冻结列（PK）── */
/* ⭐ sticky 单元格必须自带**不透明**背景，否则横向滚动时下面的内容会透出来。
   斑马纹的 even 行是半透明的，这里用「surface 55% over canvas」算出等效不透明色。 */
table.grid th.frozen-pk, table.grid td.frozen-pk {
  position: sticky; left: 88px; z-index: 1;
  background: var(--bg-surface);
  border-right: var(--bw) solid var(--border-strong);
}
table.grid thead th.frozen-pk { z-index: 3; background: var(--bg-elevated); }
table.grid tbody tr:nth-child(even) td.frozen-pk { background: color-mix(in srgb, var(--bg-surface) 55%, var(--bg-canvas)); }
table.grid tbody tr:hover td.frozen-pk { background: var(--bg-hover); }
table.grid tbody tr.is-current td.frozen-pk { background: var(--bg-selected); }
table.grid tbody tr.is-selected td.frozen-pk { background: var(--bg-active); }

/* ── A4-3 列宽手柄 / 列序拖拽 ── */
.col-resizer {
  position: absolute; top: 0; right: 0; bottom: 0;
  width: 6px; cursor: col-resize; z-index: 4;
}
.col-resizer:hover { background: var(--accent); }
/* ⭐ 拖拽中的列半透明 + 目标列左侧亮线 —— 用户必须看得见「松手会插到哪儿」 */
table.grid th.is-reordering { opacity: .5; }
table.grid th.is-reorder-over { box-shadow: inset 3px 0 0 var(--accent); }

/* ── A4-4 列显示面板 ── */
.colpanel-anchor { position: relative; }
.colpanel-catch { position: fixed; inset: 0; z-index: calc(var(--z-dropdown) - 1); }
.colpanel {
  position: absolute; top: calc(100% + 4px); left: 0; z-index: var(--z-dropdown);
  min-width: 220px; max-height: 420px; overflow: auto;
  background: var(--bg-elevated);
  border: var(--bw) solid var(--border-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-md);
  padding: var(--sp-1) 0;
}
.colpanel__head {
  display: flex; align-items: center; gap: var(--sp-2);
  padding: var(--sp-1) var(--sp-3);
  border-bottom: var(--bw) solid var(--border);
}
.colpanel__item {
  display: flex; align-items: center; gap: var(--sp-2);
  padding: var(--sp-1) var(--sp-3);
  cursor: pointer; font-size: var(--fs-xs);
}
.colpanel__item:hover { background: var(--bg-hover); }
.colpanel__item input { margin: 0; cursor: pointer; accent-color: var(--accent); }
.colpanel__foot { padding: var(--sp-2) var(--sp-3); border-top: var(--bw) solid var(--border); }

/* ── A4-6 单元格右键菜单 ── */
.ctxmenu {
  position: fixed; z-index: var(--z-dropdown);
  min-width: 168px; padding: var(--sp-1) 0;
  background: var(--bg-elevated);
  border: var(--bw) solid var(--border-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-md);
}
.ctxmenu__head { padding: var(--sp-1) var(--sp-3); border-bottom: var(--bw) solid var(--border); }
.ctxmenu__item {
  display: block; width: 100%; text-align: left;
  padding: var(--sp-1) var(--sp-3);
  font-size: var(--fs-xs); color: var(--text-primary);
  background: none; border: none; cursor: pointer;
}
.ctxmenu__item:hover:not(:disabled) { background: var(--bg-hover); }
.ctxmenu__item:disabled { color: var(--text-disabled); cursor: default; }
.ctxmenu__item--danger { color: var(--danger); }
.ctxmenu__sep { height: var(--bw); background: var(--border); margin: var(--sp-1) 0; }

/* ── A4-7 Cell Renderer ── */
/* blob：二进制只给字节数，不铺 hex */
.cell-bin {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 1px 6px; border-radius: var(--r-sm);
  background: var(--bg-active); color: var(--text-link);
  font-family: var(--font-mono); font-size: var(--fs-xs);
}
/* array：JSON 数组渲染成 chips */
.cell-arr { display: inline-flex; gap: 3px; flex-wrap: wrap; }
.cell-arr__item {
  padding: 0 5px; border-radius: var(--r-sm);
  background: var(--accent-subtle); color: var(--text-link);
  font-size: var(--fs-xs); white-space: nowrap;
}
/* image：缩略图 + URL 文本（加载失败时只剩文本） */
.cell-imgbox { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.cell-img {
  width: 18px; height: 18px; flex: 0 0 auto;
  object-fit: cover; border-radius: var(--r-sm);
  background: var(--bg-hover); border: var(--bw) solid var(--border);
}
.cell-img-txt {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--text-link); font-size: var(--fs-xs);
}

/* ── A4-8 转置视图 ── */
.grid--transpose .t-field { width: 160px; min-width: 160px; max-width: 160px; text-align: left; }
.grid--transpose .t-rowhead { min-width: 120px; }
.grid--transpose td { white-space: nowrap; }

/* ── 详情面板：hex 区 ── */
.cell-detail__hex {
  border-top: var(--bw) solid var(--border);
  padding: var(--sp-2) var(--sp-3);
  max-height: 200px; overflow: auto;
}
.cell-detail__hex pre { margin: 0; white-space: pre; color: var(--syn-number); }
</style>