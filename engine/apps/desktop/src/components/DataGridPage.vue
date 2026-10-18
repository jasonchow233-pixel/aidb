<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  state, actions, notify, FILTER_OPS, sqlLiteral,
  type FilterOp, type TableFilter,
} from "@/lib/store";
import { api, capabilities, compact, dangerClass, dangerIcon, type ColumnDef } from "@/lib/api";
import ColumnFilterPanel from "@/components/ColumnFilterPanel.vue";

const emit = defineEmits<{ run: [sql: string] }>();

const detail = ref<{ col: ColumnDef; row: number; text: string; total: number } | null>(null);
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
 */
const filteredRows = computed(() => {
  const q = searchTerm.value.trim().toLowerCase();
  const out: { row: Record<string, unknown>; ri: number }[] = [];
  state.rows.forEach((row, ri) => {
    if (!q) { out.push({ row, ri }); return; }
    if (state.columns.some((c) => String(row[c.name] ?? "").toLowerCase().includes(q))) {
      out.push({ row, ri });
    }
  });
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
  detail.value = { col, row: rowIdx, text: "", total: 0 };
  try {
    const r = await api.cell(state.tableDef.name, rowIdx, col.name, 0, 20000);
    detail.value = { col, row: rowIdx, text: r.text, total: r.totalLen };
  } catch (e) {
    notify("error", e instanceof Error ? e.message : String(e));
    detail.value = null;
  } finally {
    detailLoading.value = false;
  }
}

// ── 表头操作 ────────────────────────────────────────────────
/** ⭐ 排序交给 store：它会重置 offset 并重查（内存排序只对当前页有效） */
function toggleSort(col: string) {
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

function startEdit(rowIdx: number, col: string, value: unknown) {
  editingCell.value = { row: rowIdx, col, value: formatEditable(value) };
}

/** ⭐ 编辑框的初始值：NULL 显示为空文本框，但不能变成字符串 "null" */
function formatEditable(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

/** ⭐ 编辑框的值 → JS 值。空文本框一律视为「设成空串」，而不是 NULL。 */
function parseEditable(text: string): unknown {
  return text; // ⭐ 空串就是空串；要设 NULL 必须走显式的「设 NULL」操作
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
  editingCell.value = null;
  if (!table || raw === undefined) return;

  // ⭐ `edits` 在 **state** 上，不在 actions 上 —— 写成 actions.edits 会是
//    undefined，报错还很难联想到真正原因。
  const original = state.edits.byTable.get(table)?.find(
    (e) => e.row === rowIdx && e.col === col,
  );
  // ⭐ 已进入缓冲的改动，用缓冲里的当前值判断「是否真的改了」
  const current = original ? original.newValue : state.rows[rowIdx]?.[col];
  const next = parseEditable(raw);
  if (String(current ?? "") === String(next ?? "")) return;

  actions.editCell(table, rowIdx, col, next);
}

/** ⭐ 显式「设成 NULL」—— 与「设成空串」是两个不同操作 */
function setNull(rowIdx: number, col: string) {
  const table = state.tableDef?.name;
  if (!table) return;
  actions.editCell(table, rowIdx, col, null);
}

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
        <input class="input input--mono searchbox" style="width: 170px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
               v-model="searchTerm" placeholder="搜索本页"
               aria-label="搜索本页（仅本页）" />
        <span class="muted small">仅本页 {{ filteredRows.length }} 行</span>

        <div class="grow"></div>
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
      <table class="grid">
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
            <th v-for="(c, ci) in state.columns" :key="c.id"
                :class="{ 'is-pk': c.isPk, 'is-fk': c.isFk, 'is-sorted': state.sortCol === c.name }"
                :title="c.comment ? `${c.comment}\n点击表头排序；点 ▼ 筛选` : '点击表头排序；点 ▼ 筛选'"
                :style="{ minWidth: '140px' }"
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
                <!-- ⭐ 列头筛选入口：Navicat 的核心操作。
                     用 ci（真实列下标）而不是 DOM 顺序，避免与行号列错位。 -->
                <button class="th-filter" :class="{ 'is-on': hasColFilter(c.name) }"
                        :title="hasColFilter(c.name) ? '已设置筛选条件' : `按 ${c.name} 筛选（候选值 + 计数）`"
                        @click.stop="toggleFilterPanel(c.name)">▼</button>
              </div>
              <div class="th-type">{{ c.ty }}{{ c.defaultExpr ? ` = ${c.defaultExpr}` : "" }}</div>
              <div v-if="c.comment" class="th-comment">{{ c.comment }}</div>
            </th>
            <th style="width: 100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <!-- ⭐ 窗口化：上下 spacer 撑出完整高度，保证滚动条长度与真实行数一致。
               ⚠ 缺了它，滚动条只反映「渲染出来的几十行」，
                 用户会以为整张表就这么点数据。 -->
          <tr v-if="win.padTop > 0" class="win-spacer" aria-hidden="true">
            <td :colspan="state.columns.length + 2" :style="{ height: win.padTop + 'px', padding: 0 }"></td>
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
            <td v-for="c in state.columns" :key="c.id"
                :class="{
                  num: c.category === 'number',
                  null: w.row[c.name] === null,
                  'bool-yes': w.row[c.name] === true || w.row[c.name] === 1,
                  'bool-no': w.row[c.name] === false || w.row[c.name] === 0,
                  link: isUrl(w.row[c.name]),
                  json: c.category === 'json',
                  large: c.isLarge && String(w.row[c.name] ?? '').length > 40,
                  'is-dirty': isDirty(w.ri, c.name),
                }"
                :title="c.isLarge ? '点击查看完整内容' : undefined"
                @dblclick.stop="openDetail(c, w.ri)"
                @click.stop="startEdit(w.ri, c.name, w.row[c.name])">
              <template v-if="editingCell?.row === w.ri && editingCell?.col === c.name">
                <input class="input cell-editor" style="height: 20px; font-size: 11px; padding: 0 4px"
                       :aria-label="`编辑 ${c.name}`"
                       v-model="editingCell.value"
                       @blur="commitEdit(w.ri, c.name)"
                       @keydown.enter.prevent="commitEdit(w.ri, c.name)"
                       @keydown.escape="editingCell = null"
                       @click.stop />
              </template>
              <template v-else-if="w.row[c.name] === null"><em>NULL</em></template>
              <template v-else-if="isEnum(c)">{{ enumText(c, w.row[c.name]) }}</template>
              <template v-else-if="typeof w.row[c.name] === 'object'">{{ cellText(w.row[c.name]) }}</template>
              <template v-else>{{ cellText(w.row[c.name]) }}</template>
            </td>
            <td @click.stop>
              <button class="btn btn--sm btn--ghost" @click="copyRow(w.row)" title="复制整行">复制</button>
              <button class="btn btn--sm btn--danger" @click="deleteRow(w.ri)" title="删除行">删除</button>
            </td>
          </tr>

          <tr v-if="win.padBottom > 0" class="win-spacer" aria-hidden="true">
            <td :colspan="state.columns.length + 2" :style="{ height: win.padBottom + 'px', padding: 0 }"></td>
          </tr>
        </tbody>
      </table>

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
</style>