<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { state, actions, notify } from "@/lib/store";
import { api, capabilities, compact, dangerClass, dangerIcon, type ColumnDef } from "@/lib/api";

const emit = defineEmits<{ run: [sql: string] }>();

const detail = ref<{ col: ColumnDef; row: number; text: string; total: number } | null>(null);
const detailLoading = ref(false);
const pageLimit = ref(state.rowLimit);
const searchTerm = ref("");
const sortCol = ref<string | null>(null);
const sortDir = ref<"asc" | "desc">("asc");
const selectedRows = ref<Set<number>>(new Set());
const editingCell = ref<{ row: number; col: string; value: string } | null>(null);
const showFilter = ref(false);
const filterCol = ref("");
const filterOp = ref("=");
const filterVal = ref("");

/** ⭐ 当前连接的传输家族（来自声明层），拿不到就不显示 —— 不猜。 */
const connFamily = computed(
  () => capabilities.byId(state.connectionKind)?.transferObjectFamily ?? null,
);

watch(pageLimit, (v) => actions.setRowLimit(v));

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
 * ⭐ 客户端筛选 + 排序。
 *
 * 范围必须写死在 UI 上（「仅搜索已加载的 N 行」）——
 * 让用户以为在搜全表，实际只搜了当前页，是 Navicat 之外工具最常见的坑。
 */
const filteredRows = computed(() => {
  let rows = state.rows;
  if (searchTerm.value) {
    const q = searchTerm.value.toLowerCase();
    rows = rows.filter((r) =>
      state.columns.some((c) => String(r[c.name] ?? "").toLowerCase().includes(q)),
    );
  }
  if (filterCol.value && filterVal.value) {
    rows = rows.filter((r) => {
      const v = String(r[filterCol.value] ?? "");
      switch (filterOp.value) {
        case "=":  return v === filterVal.value;
        case "!=": return v !== filterVal.value;
        case ">":  return v > filterVal.value;
        case "<":  return v < filterVal.value;
        case "LIKE": return v.toLowerCase().includes(filterVal.value.toLowerCase());
        default: return true;
      }
    });
  }
  if (sortCol.value) {
    const col = sortCol.value;
    const dir = sortDir.value === "asc" ? 1 : -1;
    rows = [...rows].sort((a, b) => {
      const av = a[col];
      const bv = b[col];
      if (av === bv) return 0;
      if (av === null) return 1;   // NULL 恒排最后，不随升降序翻转
      if (bv === null) return -1;
      return (av > bv ? 1 : -1) * dir;
    });
  }
  return rows;
});

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
function toggleSort(col: string) {
  if (sortCol.value === col) {
    sortDir.value = sortDir.value === "asc" ? "desc" : "asc";
  } else {
    sortCol.value = col;
    sortDir.value = "asc";
  }
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

async function deleteRow(rowIdx: number) {
  if (!state.tableDef) return;
  const row = state.rows[rowIdx];
  const pk = state.columns.find((c) => c.isPk);
  if (!pk) {
    notify("error", "该表无主键，无法安全删除");
    return;
  }
  if (!confirm(`确认删除第 ${rowIdx + 1} 行？\\nWHERE ${pk.name} = ${row[pk.name]}`)) return;
  notify("info", "删除操作已记录（演示模式不真正执行）");
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

      <!-- 工具栏：⭐ 筛选 + 排序 + 密度 -->
      <div class="toolbar">
        <span class="muted">行数</span>
        <select class="input" style="width: 84px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                v-model.number="pageLimit">
          <option :value="100">100</option>
          <option :value="200">200</option>
          <option :value="1000">1,000</option>
          <option :value="5000">5,000</option>
        </select>
        <select class="input" style="width: 78px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                :value="state.density" @change="actions.setDensity(($event.target as HTMLSelectElement).value as any)">
          <option value="compact">紧凑</option>
          <option value="normal">标准</option>
          <option value="comfort">宽松</option>
        </select>
        <div class="toolbar__sep"></div>
        <button class="btn btn--sm" :class="{ 'btn--primary': showFilter }" @click="showFilter = !showFilter">
          {{ showFilter ? "清除筛选" : "筛选" }}
        </button>
        <template v-if="showFilter">
          <select class="input" style="width: 120px; height: var(--h-btn-sm); font-size: var(--fs-xs)" v-model="filterCol">
            <option value="">选择列</option>
            <option v-for="c in state.columns" :key="c.id" :value="c.name">{{ c.name }}</option>
          </select>
          <select class="input" style="width: 60px; height: var(--h-btn-sm); font-size: var(--fs-xs)" v-model="filterOp">
            <option value="=">=</option>
            <option value="!=">≠</option>
            <option value=">">></option>
            <option value="<"><</option>
            <option value="LIKE">包含</option>
          </select>
          <input class="input" style="width: 140px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
                 v-model="filterVal" placeholder="值" />
          <button class="btn btn--sm btn--ghost" @click="clearFilter">清除</button>
        </template>
        <div class="toolbar__sep"></div>
        <input class="input input--mono" style="width: 180px; height: var(--h-btn-sm); font-size: var(--fs-xs)"
               v-model="searchTerm" placeholder="搜索当前结果" />
        <span class="muted small">仅搜索已加载的 {{ filteredRows.length }} 行</span>
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

    <!-- ══ 表格 ══ -->
    <div class="grid-wrap" v-if="state.columns.length">
      <table class="grid">
        <thead>
          <tr :style="{ height: 'var(--h-toolbar)' }">
            <th class="rownum" style="width: 62px; min-width:62px; max-width:62px">#</th>
            <th v-for="c in state.columns" :key="c.id"
                :class="{ 'is-pk': c.isPk, 'is-fk': c.isFk, 'is-sorted': sortCol === c.name }"
                :title="c.comment ?? c.name"
                :style="{ minWidth: '140px' }"
                @click="toggleSort(c.name)">
              <div class="th-main">
                <span>{{ c.name }}</span>
                <span v-if="c.isPk" style="color: var(--warning)" title="主键">🔑</span>
                <span v-if="c.isFk" style="color: var(--accent)" title="外键">🔗</span>
                <span v-if="!c.nullable" class="muted" title="NOT NULL">•</span>
                <span v-if="sortCol === c.name" class="muted">{{ sortDir === "asc" ? "↑" : "↓" }}</span>
              </div>
              <div class="th-type">{{ c.ty }}{{ c.defaultExpr ? ` = ${c.defaultExpr}` : "" }}</div>
              <div v-if="c.comment" class="th-comment">{{ c.comment }}</div>
            </th>
            <th style="width: 100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in filteredRows" :key="i" :class="rowClass(i)" :style="{ height: rowH }"
              @click="detail && (detail = null)">
            <td class="rownum">{{ state.offset + i + 1 }}</td>
            <td v-for="c in state.columns" :key="c.id"
                :class="{
                  num: c.category === 'number',
                  null: row[c.name] === null,
                  'bool-yes': row[c.name] === true || row[c.name] === 1,
                  'bool-no': row[c.name] === false || row[c.name] === 0,
                  link: isUrl(row[c.name]),
                  json: c.category === 'json',
                  large: c.isLarge && String(row[c.name] ?? '').length > 40,
                  'is-dirty': isDirty(i, c.name),
                }"
                :title="c.isLarge ? '点击查看完整内容' : undefined"
                @dblclick.stop="openDetail(c, i)"
                @click.stop="startEdit(i, c.name, row[c.name])">
              <template v-if="editingCell?.row === i && editingCell?.col === c.name">
                <input class="input" style="height: 20px; font-size: 11px; padding: 0 4px"
                       v-model="editingCell.value"
                       @blur="commitEdit(i, c.name)"
                       @keydown.enter.prevent="commitEdit(i, c.name)"
                       @keydown.escape="editingCell = null"
                       @click.stop />
              </template>
              <template v-else-if="row[c.name] === null"><em>NULL</em></template>
              <template v-else-if="isEnum(c)">{{ enumText(c, row[c.name]) }}</template>
              <template v-else-if="typeof row[c.name] === 'object'">{{ cellText(row[c.name]) }}</template>
              <template v-else>{{ cellText(row[c.name]) }}</template>
            </td>
            <td @click.stop>
              <button class="btn btn--sm btn--ghost" @click="copyRow(row)" title="复制整行">复制</button>
              <button class="btn btn--sm btn--danger" @click="deleteRow(i)" title="删除行">删除</button>
            </td>
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

    <!-- ══ 底部：行数 + 加载全部 ══ -->
    <div class="grid-foot" v-if="state.tableDef">
      <span class="mono">
        已加载 {{ filteredRows.length.toLocaleString() }} /
        <template v-if="total !== null">{{ total.toLocaleString() }}</template>
        <template v-else><span class="muted" title="复杂查询不执行 COUNT，避免全表扫描">未估算</span></template>
      </span>
      <span class="muted">{{ state.queryMs }} ms</span>
      <span class="grow"></span>
      <!-- ⭐ 模板里 ref 会被自动解包，写 `selectedRows.value` 会变成
           `undefined.value` → 渲染崩掉。script 里才用 `.value`。 -->
      <span class="muted small" v-if="selectedRows.size">
        已选 {{ selectedRows.size }} 行
      </span>
      <!-- ⭐ 传输家族来自声明层，不硬编码 `connectionKind === 'sqlite'`：
           加一种数据库时这里自动跟着 YAML 走，不用改前端。 -->
      <span class="muted small" v-if="connFamily">
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
</style>