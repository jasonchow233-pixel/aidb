<script setup lang="ts">
/**
 * ⭐⭐ 查询页 —— Navicat 的「新建查询」对位物。
 *
 * 本轮（B1）补齐的东西：
 *   · B1-1 语法高亮 / B1-2 自动配对 / B1-3 Ctrl+Click 跳转 / B1-4 hover 注释
 *   · B1-5 结果内联在编辑器下方（可切「上下 / 左右」）
 *   · B1-6 多语句逐条执行、每条一个结果页签、⌘⇧⏎ 只跑选中的那条
 *   · B1-7 真格式化（B1-8 大文件降级在 SqlEditor 里）
 *   · B1-9 执行计划面板 + 人话解读
 *
 * ⭐ 「执行」的结果一律来自 `/api/query`（写语句在事务内 dry-run 回滚），
 *   也就是说**查询页永远改不动数据库**。真要改数据只能走数据网格的保存流程 ——
 *   那条路要展示 SQL、要确认、要按主键定位行。
 */
import { computed, onMounted, ref, watch } from "vue";
import { state, actions, notify } from "@/lib/store";
import { api, capabilities, dangerClass, dangerIcon, type QueryResult, type RowPage } from "@/lib/api";
import { splitStatements, formatSql, tokenize, type Statement, type SymbolRef } from "@/lib/sql";
import SqlEditor from "@/components/SqlEditor.vue";

const DEFAULT_SQL = "SELECT id, order_no, status, total_cents, created_at\nFROM orders\nWHERE status = 2\nORDER BY created_at DESC\nLIMIT 50;";

const sql = ref(DEFAULT_SQL);
const running = ref(false);
const history = ref<string[]>([]);
const showHistory = ref(false);
/** ⭐ 上下 / 左右。默认上下：写 JOIN 时编辑器要宽，结果又不大 */
const layout = ref<"stack" | "split">("stack");
const editor = ref<InstanceType<typeof SqlEditor> | null>(null);

// ── 结果（多语句 = 多个结果页签）──
interface ResultSlot {
  /** 来自第几条语句（0 基） */
  stmt: number;
  /** 页签标题：「结果 1」/「第 2 条」 */
  label: string;
  result: QueryResult;
  elapsed: number;
  /** 该语句在编辑器里的行号 —— 用户要能回到原处看 */
  line: number;
}
const slots = ref<ResultSlot[]>([]);
const activeSlot = ref(0);

const errPayload = ref<any>(null);
const errStmtLine = ref(1);
const elapsed = ref<number | null>(null);

// ── 执行计划（B1-9）──
const showExplain = ref(false);
const explainRows = ref<Record<string, unknown>[]>([]);
const explainMs = ref<number | null>(null);
const explainErr = ref<string | null>(null);
const explaining = ref(false);

// ── 图表 / 消息页签 ──
const auxTab = ref<"结果" | "图表" | "消息" | "执行计划">("结果");

// ─────────────────────────────────────────────────────────────
// 元数据：高亮与跳转的输入
// ─────────────────────────────────────────────────────────────

/**
 * ⭐⭐ 已知表名 —— 高亮、hover、跳转的**共同前提**。
 *
 * ⚠ 不能只读 `state.tree`：对象树是**懒加载**的，
 *   `state.tree` 里只有「已展开过的那几层」。
 *   用户 ⌘T 直接开查询页时侧栏还没展开 → `state.tree` 是空的
 *   → `tables` 为空 → `symbolAt` 认为「没有一张真实存在的表」
 *   → **Ctrl+Click 表名永远不跳转**，而且没有任何报错，
 *   表现为「点表名没反应」。
 *
 *   所以这里**直接问后端要表清单**，不依赖侧栏的展开状态。
 *   `/api/schema` 一次给全，比一层层展开侧栏快也可靠。
 */
const tableList = ref<string[]>([]);
const tables = computed(() => tableList.value);

let schemaLoaded = false;
async function ensureSchema() {
  if (schemaLoaded) return;
  schemaLoaded = true;
  try {
    const s = await api.schema();
    tableList.value = s.tables
      .filter((t) => t.kind === "table" || t.kind === "view")
      .map((t) => t.name);
  } catch {
    // ⭐ 拿不到就留空：编辑器仍然可用（只是没有跳转与 tooltip）。
    //    绝不拿一个猜出来的表名列表去凑 —— 那会让跳转打开不存在的表。
    tableList.value = [];
  }
}

/** ⭐ 表结构缓存。hover 与跳转都需要列注释/枚举，不能每次都打后端 */
const metaCache = ref(new Map<string, any>());
let metaInflight = 0;

async function ensureMeta(name: string): Promise<any | null> {
  const key = name.toLowerCase();
  if (metaCache.value.has(key)) return metaCache.value.get(key);
  if (metaInflight > 4) return null; // 并发上限：hover 不该打爆后端
  metaInflight++;
  try {
    const r = await api.table(name);
    metaCache.value.set(key, r.table);
    return r.table;
  } catch {
    return null;
  } finally {
    metaInflight--;
  }
}

/** ⭐ tooltip 内容：类型 / 注释 / 枚举 / 索引 */
function describe(sym: SymbolRef): string[] | null {
  if (sym.kind === "table") {
    const t = metaCache.value.get(sym.name.toLowerCase());
    if (!t) return null;
    const out = [`${t.columns.length} 列`];
    if (t.comment) out.push(t.comment);
    if (t.rowEstimate != null) out.push(`约 ${t.rowEstimate.toLocaleString()} 行`);
    return out;
  }
  // ⭐ 同名列跨多张表：把候选都列出来，别只显示其中一张的注释 ——
  //   只显示一张的话，用户会以为那就是唯一的定义。
  if (!sym.table && sym.owners?.length) {
    return [`${sym.owners.length} 张表都有此列`, `候选：${sym.owners.join(" / ")}`];
  }
  // ⭐ 列：只在**归属确定的那张表**里找。
  //   找不到就明说「元数据未加载」，不去别处猜 ——
  //   全库按列名找会把 JOIN 出来的 name 认成别的表的同名列
  //   （后端 `build_columns` 踩过同一个坑，注释写得很清楚）。
  const t = metaCache.value.get((sym.table ?? "").toLowerCase());
  const col = t?.columns?.find((c: any) => c.name === sym.name);
  if (!col) return null;
  const out = [col.ty];
  if (col.comment) out.push(col.comment);
  const flags = [
    col.isPk ? "PK" : "",
    col.isFk ? "FK" : "",
    col.nullable ? "" : "NOT NULL",
  ].filter(Boolean);
  if (flags.length) out.push(flags.join(" · "));
  if (col.enumValues) {
    const pairs = Object.entries(col.enumValues).map(([k, v]) => `${k}=${v}`);
    out.push(pairs.join("  "));
  }
  return out;
}

/**
 * ⭐ 预取当前语句引用到的表 —— hover 前就把注释备好。
 *
 * ⭐ 认表名不靠正则，靠**分词器**：正则 `\bfrom\s+x` 会把
 *   `select 'from x'`（字符串里的话）、`from_x` 都算进去，
 *   于是预取了一堆不存在的表。分词器已经知道字符串与注释的边界。
 */
watch(sql, () => {
  window.clearTimeout(prefetchTimer);
  prefetchTimer = window.setTimeout(async () => {
    await ensureSchema();
    for (const n of referencedTables(sql.value)) await ensureMeta(n);
  }, 400);
});
let prefetchTimer = 0;

/** ⭐ 从 SQL 里取出引用到的表名（用分词器，跳过字符串/注释里的假匹配） */
function referencedTables(text: string): string[] {
  const out = new Set<string>();
  const known = new Set(tables.value.map((x) => x.toLowerCase()));
  const toks = tokenize(text);
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (t.kind !== "keyword") continue;
    if (!["FROM", "JOIN", "INTO", "UPDATE"].includes(t.value.toUpperCase())) continue;
    // ⭐ `schema.table` 取最后一段
    let name = t.value;
    let k = i + 1;
    if (toks[i + 2]?.value === "." && toks[i + 3]) { name = toks[i + 3].value; k = i + 4; }
    else if (toks[i + 1]?.value === "." && toks[i + 2]) { name = toks[i + 2].value; k = i + 3; }
    if (known.has(name.toLowerCase())) out.add(name);
    // 别名后面可能还跟着 FROM/JOIN（`from a x join b y`），继续往下扫
    i = k - 1;
  }
  return [...out];
}

// ─────────────────────────────────────────────────────────────
// ⭐ 执行
// ─────────────────────────────────────────────────────────────

const statements = computed<Statement[]>(() => splitStatements(sql.value));
const statementCount = computed(() => statements.value.length);

/** 当前页签显示的结果 */
const cur = computed<ResultSlot | null>(() => slots.value[activeSlot.value] ?? null);
const curPage = computed<RowPage | null>(() => cur.value?.result.page ?? null);

/** ⭐ 危险级别取**所有**结果里最高的那个 */
const worstDanger = computed(() => {
  let worst = "readOnly";
  const rank: Record<string, number> = { readOnly: 0, mutating: 1, destructive: 2, admin: 3 };
  for (const s of slots.value) {
    if ((rank[s.result.danger] ?? 0) > (rank[worst] ?? 0)) worst = s.result.danger;
  }
  return worst;
});

/**
 * ⭐ 执行一条语句。失败时把 ApiError 的 payload 留下来（位置、相似名建议）。
 *
 * ⚠ `api.*` 出错是 **reject**，不是返回 `{ok:false}`。
 *   不 try/catch 的话错误会变成一个没人接的 Promise 拒绝：
 *   页面一声不吭，用户以为执行成功了。
 */
async function execOne(text: string, stmtIndex: number, line: number): Promise<ResultSlot | null> {
  const t0 = performance.now();
  errStmtLine.value = line;
  try {
    const r = await actions.runSql(text);
    errPayload.value = null;
    return { stmt: stmtIndex, label: `结果 ${stmtIndex + 1}`, result: r, elapsed: Math.round(performance.now() - t0), line };
  } catch (e: any) {
    errPayload.value = e?.payload ?? { message: e?.message ?? String(e) };
    return null;
  }
}

/** ⭐ 执行全部语句，每条一个结果页签 */
async function run() {
  const list = statements.value;
  if (!list.length) { notify("info", "没有可执行的语句"); return; }
  running.value = true;
  errPayload.value = null;
  const t0 = performance.now();
  const made: ResultSlot[] = [];
  try {
    for (const s of list) {
      errPayload.value = null;
      const slot = await execOne(s.text, s.index, s.line);
      if (!slot) break; // ⭐ 出错就停：后面的语句基于已经失败的前提
      made.push(slot);
    }
    if (made.length) {
      slots.value = made;
      activeSlot.value = 0;
      auxTab.value = "结果";
    }
    elapsed.value = Math.round(performance.now() - t0);
    const text = sql.value.trim();
    if (text && !history.value.includes(text)) {
      history.value.unshift(text);
      if (history.value.length > 20) history.value.pop();
    }
  } finally {
    running.value = false;
  }
}

/** ⭐ 工具栏按钮与 ⌘⇧⏎ 走同一条路：选区优先，否则光标所在那条 */
function runSelectionFromButton() {
  const t = editor.value?.selectionOrStatement?.();
  return runSelection(t ?? "");
}

/** ⭐ 执行选中：没选中就跑光标所在那条（不是全部） */
async function runSelection(text: string) {
  const target = text.trim();
  if (!target) { notify("info", "没有可执行的语句"); return; }
  running.value = true;
  errPayload.value = null;
  const t0 = performance.now();
  try {
    // ⭐ 只跑这一条 → 结果**替换**当前页签，而不是追加。
    //   追加的话用户会以为「我选了第 2 条，怎么出现了 2 个结果」。
    const slot = await execOne(target, 0, 1);
    if (slot) {
      slots.value = [slot];
      activeSlot.value = 0;
      auxTab.value = "结果";
    }
    elapsed.value = Math.round(performance.now() - t0);
  } finally {
    running.value = false;
  }
}

// ─────────────────────────────────────────────────────────────
// ⭐ 跳转
// ─────────────────────────────────────────────────────────────

function onJump(sym: SymbolRef) {
  if (sym.kind === "table") {
    actions.openTable(sym.name);
    return;
  }
  // ⭐ 同名列出现在多张表里 → **说清并列出候选**，不替用户选。
  //   猜一张的话，用户点一下跳到另一张表的同名列，
  //   页面完全正常，而他已经按错误的表在思考了。
  if (!sym.table && sym.owners?.length) {
    notify("info", `「${sym.name}」在 ${sym.owners.join(" / ")} 里都有 —— 加上表别名（如 o.${sym.name}）可精确跳转`);
    return;
  }
  // ⭐ 列跳转：打开**它真正所属的那张表**的结构页。
  //   拿不到归属（例如只在 SELECT 列表里出现、没 FROM）就说清，
  //   而不是随便挑一张有同名列的表 —— 那会让用户以为列认错了。
  const table = sym.table;
  if (!table) {
    notify("info", `「${sym.name}」无法确定所属表 —— 加上 FROM 后可跳转`);
    return;
  }
  actions.openStructure(table);
}

/** ⭐ 传给编辑器的列清单（小写表名 → 列名），用于认裸列名 */
const columnIndex = computed<Record<string, string[]>>(() => {
  const out: Record<string, string[]> = {};
  for (const [k, t] of metaCache.value) {
    if (t?.columns) out[k] = t.columns.map((c: any) => c.name);
  }
  return out;
});

/** ⭐ 错误位置跳转：真的把光标移过去并选中出错的词，而不是弹一句「已跳转」 */
function jumpToError() {
  const p = errPayload.value?.position;
  if (!p) { notify("info", "这次错误没有位置信息"); return; }
  const base = errStmtLine.value > 1 ? errStmtLine.value - 1 : 0;
  editor.value?.reveal(base + p.line, p.column, p.length ?? 0);
}

/**
 * ⭐⭐ 点击错误提示里的相似名 → 真的替换文本。
 *
 * ⚠ 替换**只能发生在出错的标识符上**（用 `position` 定位），
 *   不能全句正则替换 —— 全句替换会把「碰巧也含这个词」的别处一起改掉，
 *   而用户以为只改了报错那一个。
 *   拿不到位置时**宁可不替换**，只说清原因。
 */
function applyHint(label: string) {
  const text = sql.value;
  const p = errPayload.value?.position;
  const base = errStmtLine.value > 1 ? errStmtLine.value - 1 : 0;
  if (p && p.length > 0) {
    const linesArr = text.split("\n");
    let off = 0;
    for (let i = 0; i < base + p.line - 1 && i < linesArr.length; i++) off += linesArr[i].length + 1;
    off += Math.max(0, p.column - 1);
    const hit = text.slice(off, off + p.length);
    if (hit) {
      sql.value = text.slice(0, off) + label + text.slice(off + p.length);
      notify("info", `已把「${hit}」替换为「${label}」`);
      return;
    }
  }
  notify("info", `没有找到可替换的出错位置 —— 请手动改为 ${label}`);
}

// ─────────────────────────────────────────────────────────────
// B1-7 格式化
// ─────────────────────────────────────────────────────────────

function doFormat() {
  const before = sql.value;
  const after = formatSql(before);
  if (after === before.trim()) { notify("info", "已经是规范格式"); return; }
  sql.value = after;
  notify("info", "已格式化");
}

// ─────────────────────────────────────────────────────────────
// B1-9 执行计划
// ─────────────────────────────────────────────────────────────

const canExplain = computed(() => capabilities.can(state.connectionKind, "sqlExplain"));

async function explain() {
  const st = statements.value[0];
  if (!st) { notify("info", "没有可分析的语句"); return; }
  explaining.value = true;
  explainErr.value = null;
  explainRows.value = [];
  auxTab.value = "执行计划";
  showExplain.value = true;
  try {
    const r = await api.explain(st.text);
    explainRows.value = r.page.rows;
    explainMs.value = r.page.durationMs;
  } catch (e: any) {
    explainErr.value = e?.payload?.message ?? e?.message ?? String(e);
  } finally {
    explaining.value = false;
  }
}

/**
 * ⭐⭐ 把执行计划翻译成人话。
 *
 * 这条规则的价值在于「说清代价」：
 * `SCAN orders` 不是「一个步骤」，是「把 5000 行全读一遍」。
 * 只把原始计划抄一遍给用户，用户看不懂，也就不会因此改查询。
 *
 * ⚠⭐ 规则必须覆盖 SQLite 的**全部**主要形态。实测踩过的坑：
 *   最初只写了 `SEARCH … USING INDEX …`，
 *   而 status 上有**覆盖索引**时输出是
 *   `SEARCH orders USING COVERING INDEX idx_orders_status (status=?)`
 *   —— 匹配不上就落进「认不出来」分支，于是最该被表扬的
 *   「走了覆盖索引」反而只显示一行英文原文。
 *
 * ⚠ 认不出来的行**原样显示**，绝不猜。
 */
interface Advice { level: "ok" | "warn" | "bad"; text: string }

/** ⭐ 抽掉 `USING …` 修饰语，拿到索引名（COVERING / INTEGER PRIMARY KEY 都在里） */
function indexOf(detail: string): { name: string; covering: boolean } | null {
  const m = detail.match(/\bUSING\s+(?:COVERING\s+)?(?:INTEGER\s+PRIMARY\s+KEY|INDEX)\s+(\S+)/i);
  if (!m) return null;
  return { name: m[1], covering: /COVERING/i.test(detail) };
}

const advice = computed<Advice[]>(() => {
  const out: Advice[] = [];
  for (const row of explainRows.value) {
    const detail = String(row["detail"] ?? "");
    let m: RegExpMatchArray | null;
    const idx = indexOf(detail);

    if (/TEMP B-TREE/i.test(detail)) {
      out.push({
        level: "warn",
        text: `⚠ 需要临时 B 树${/ORDER BY/i.test(detail) ? "排序" : ""}：排序/去重会额外占用内存与磁盘`,
      });
    } else if (/BLOOM FILTER/i.test(detail)) {
      out.push({ level: "ok", text: "先用布隆过滤器缩小候选，再逐行判定" });
    } else if (/CORRELATED/i.test(detail)) {
      out.push({ level: "warn", text: "⚠ 相关子查询：外层每行都会执行一次" });
    } else if (/^LIST SUBQUERY/i.test(detail)) {
      out.push({ level: "ok", text: "子查询被逐行驱动" });
    } else if ((m = detail.match(/^SEARCH\s+(\S+)/i)) && idx) {
      const cond = detail.match(/\((.+)\)\s*$/)?.[1];
      out.push({
        level: "ok",
        text: idx.covering
          ? `覆盖索引 ${idx.name} 定位 ${m[1]}（${cond ?? ""}）—— 不用回表读数据行`
          : `走索引 ${idx.name} 在 ${m[1]} 里定位（${cond ?? ""}）`,
      });
    } else if ((m = detail.match(/^SCAN\s+(\S+)/i)) && idx) {
      out.push({
        level: "ok",
        text: idx.covering
          ? `覆盖索引扫描 ${m[1]}（${idx.name}）—— 不用回表，但仍是逐行扫`
          : `走索引 ${idx.name} 逐行扫描 ${m[1]}`,
      });
    } else if ((m = detail.match(/^SCAN\s+(\S+)/i))) {
      out.push({ level: "bad", text: `⚠ 全表扫描 ${m[1]}：读遍这张表的每一行，数据量大时会明显变慢` });
    } else if (/^SEARCH\b/i.test(detail)) {
      out.push({ level: "warn", text: `按条件定位（${detail}）` });
    } else {
      // ⭐ 认不出来就原样给出，并标明「未识别」——
      //   假装翻译过 = 用户以为看懂了，其实没有。
      out.push({ level: "ok", text: `未识别：${detail}` });
    }
  }
  return out;
});

const worstLevel = computed<Advice["level"]>(() => {
  if (advice.value.some((a) => a.level === "bad")) return "bad";
  if (advice.value.some((a) => a.level === "warn")) return "warn";
  return "ok";
});

// ─────────────────────────────────────────────────────────────
// 其它
// ─────────────────────────────────────────────────────────────

function loadHistory(h: string) {
  sql.value = h;
  showHistory.value = false;
}

function switchToGrid() {
  const r = cur.value;
  if (!r) return;
  state.rows = r.result.page.rows;
  state.columns = r.result.page.columns;
  state.total = r.result.page.total ?? null;
  state.queryMs = r.result.page.durationMs;
  state.lastDanger = r.result.danger;
  const id = `grid:${Date.now()}`;
  state.tabs.push({ id, title: "查询结果", kind: "grid" });
  state.activeTabId = id;
}

function exportCSV() {
  const p = curPage.value;
  if (!p?.rows.length) return;
  const cols = p.columns.map((c) => c.name);
  const lines = [cols.join(",")];
  for (const row of p.rows) {
    lines.push(cols.map((c) => `"${String(row[c] ?? "").replace(/"/g, '""')}"`).join(","));
  }
  const blob = new Blob([lines.join("\n")], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "query_result.csv";
  a.click();
  URL.revokeObjectURL(url);
}

onMounted(async () => {
  // ⭐ 表清单与初始 SQL 里的 orders 都要预取：
  //   表清单不加载 → 没有任何表名被认成可跳转对象；
  //   元数据不预取 → 第一次 hover 要干等一次网络往返。
  await ensureSchema();
  await ensureMeta("orders");
});
</script>

<template>
  <div class="editor-page">
    <!-- ══ 工具栏 ══ -->
    <div class="editor-page__toolbar">
      <span class="muted small">数据库</span>
      <span class="badge">{{ capabilities.byId(state.connectionKind)?.label }}</span>
      <div class="toolbar__sep"></div>
      <button class="btn btn--sm btn--primary" :disabled="running" data-test="run" @click="run">
        {{ running ? "执行中…" : "▶ 执行 ⌘⏎" }}
      </button>
      <button class="btn btn--sm" data-test="run-selection" @click="runSelectionFromButton">
        执行选中 ⌘⇧⏎
      </button>
      <button class="btn btn--sm" @click="api.cancel().then(() => notify('info', '已请求取消'))">取消 ⌘.</button>
      <div class="toolbar__sep"></div>
      <button class="btn btn--sm" data-test="format" @click="doFormat">格式化</button>
      <button
        class="btn btn--sm"
        data-test="explain"
        :disabled="!canExplain"
        :title="canExplain ? '看这条查询会不会扫全表' : '当前连接不支持执行计划'"
        @click="explain"
      >执行计划</button>
      <button class="btn btn--sm" @click="showHistory = !showHistory">历史 ({{ history.length }})</button>
      <button
        class="btn btn--sm"
        :title="layout === 'stack' ? '切换为左右分栏' : '切换为上下布局'"
        @click="layout = layout === 'stack' ? 'split' : 'stack'"
      >{{ layout === 'stack' ? "⇅ 上下" : "⇄ 左右" }}</button>
      <span class="grow"></span>
      <span v-if="statementCount > 1" class="badge" data-test="stmt-count">{{ statementCount }} 条语句</span>
      <span v-if="slots.length" class="badge" :class="`badge--${dangerClass(worstDanger)}`" data-test="danger">
        {{ dangerIcon(worstDanger) }} {{ cur?.result.dangerLabel }}
        <template v-if="cur?.result.requiresConfirm"> · 需确认</template>
      </span>
    </div>

    <!-- 历史面板 -->
    <div v-if="showHistory" class="history-panel">
      <div class="history-panel__head">
        <strong>查询历史</strong>
        <span class="grow"></span>
        <button class="btn btn--sm btn--ghost" @click="showHistory = false">✕</button>
      </div>
      <div v-if="!history.length" class="muted small" style="padding: var(--sp-3)">暂无历史记录</div>
      <div v-for="(h, i) in history" :key="i" class="history-panel__item" @click="loadHistory(h)">
        <span class="mono small">{{ h.slice(0, 80) }}{{ h.length > 80 ? "…" : "" }}</span>
      </div>
    </div>

    <!-- ══ 主体 ══ -->
    <div class="query-body" :class="`query-body--${layout}`">
      <!-- 编辑器 -->
      <div class="query-editor">
        <SqlEditor
          ref="editor"
          v-model="sql"
          :tables="tables"
          :columns="columnIndex"
          :describe="describe"
          placeholder="输入 SQL…"
          @run="run"
          @run-selection="runSelection"
          @cancel="api.cancel().then(() => notify('info', '已请求取消'))"
          @jump="onJump"
        />
      </div>

      <!-- 结果区 -->
      <div class="result-area">
        <!-- ⭐ 错误 -->
        <div v-if="errPayload" class="sql-error" data-test="sql-error">
          <div class="sql-error__msg">{{ errPayload.message }}</div>
          <div v-if="errPayload.position" class="sql-error__pos">
            位置: 第 {{ errPayload.position.line }} 行, 第 {{ errPayload.position.column }} 列
            <button class="btn btn--sm btn--ghost" data-test="jump-error" @click="jumpToError">
              跳转到错误位置
            </button>
          </div>
          <div v-if="errPayload.hints?.length" class="sql-error__hints">
            <div class="small muted">你是否想找：</div>
            <div
              v-for="h in errPayload.hints"
              :key="h.label"
              class="sql-error__hint"
              @click="applyHint(h.label)"
            >
              <span class="mono">{{ h.label }}</span>
              <span class="muted">{{ h.detail }}</span>
            </div>
          </div>
        </div>

        <!-- ⭐ 执行计划解读 -->
        <div v-if="auxTab === '执行计划'" class="pad" data-test="explain-panel">
          <div v-if="explaining" class="muted small">正在分析…</div>
          <div v-else-if="explainErr" class="banner banner--important">
            <span>⚠</span><span>{{ explainErr }}</span>
          </div>
          <div v-else-if="!explainRows.length" class="muted small">没有执行计划结果</div>
          <template v-else>
            <!-- ⭐ 人话解读 -->
            <div class="finding" :class="`finding--${worstLevel === 'ok' ? 'info' : worstLevel === 'warn' ? 'warning' : 'critical'}`">
              <div class="finding__title">
                {{ worstLevel === 'bad' ? '⚠ 这条查询代价很高' : worstLevel === 'warn' ? '⚠ 有可优化空间' : '✓ 执行代价可接受' }}
              </div>
              <div v-for="(a, i) in advice" :key="i" class="finding__impact">{{ a.text }}</div>
            </div>
            <table class="grid" style="margin-top: var(--sp-3)">
              <thead><tr>
                <th style="width:60px">节点</th>
                <th>计划步骤（原文）</th>
              </tr></thead>
              <tbody>
                <tr v-for="(r, i) in explainRows" :key="i" :style="{ height: 'var(--row-normal)' }">
                  <td class="rownum">{{ r["id"] }}</td>
                  <td class="mono small">{{ r["detail"] }}</td>
                </tr>
              </tbody>
            </table>
          </template>
        </div>

        <!-- 结果 -->
        <template v-else>
          <!-- 状态条 -->
          <div class="editor-msg" v-if="cur">
            <span :style="{ color: 'var(--success)' }">✓ 执行成功</span>
            · {{ curPage?.rows.length.toLocaleString() }} 行
            · {{ cur.elapsed }} ms
            <span v-if="curPage?.total !== null && curPage?.total !== undefined">· 共 {{ curPage.total.toLocaleString() }} 行</span>
            <span v-if="curPage?.mutated">· 写语句（已在事务内验证并回滚，未落库）</span>
          </div>

          <!-- 结果页签：一条语句一个 -->
          <div class="result-tabs" v-if="slots.length">
            <div
              v-for="(s, i) in slots"
              :key="i"
              class="result-tabs__item"
              :class="{ 'result-tabs__item--active': i === activeSlot && auxTab === '结果' }"
              data-test="result-tab"
              @click="activeSlot = i; auxTab = '结果'"
            >
              {{ s.label }} ({{ s.result.page.rows.length }})
            </div>
            <div class="result-tabs__item" :class="{ 'result-tabs__item--active': auxTab === '图表' }" @click="auxTab = '图表'">图表</div>
            <div class="result-tabs__item" :class="{ 'result-tabs__item--active': auxTab === '消息' }" @click="auxTab = '消息'">消息</div>
            <div
              v-if="canExplain"
              class="result-tabs__item"
              :class="{ 'result-tabs__item--active': auxTab === '执行计划' }"
              data-test="explain-tab"
              @click="auxTab = '执行计划'"
            >执行计划</div>
            <span class="grow"></span>
            <button class="btn btn--sm" @click="switchToGrid">在网格中打开</button>
            <button class="btn btn--sm" @click="exportCSV">导出 CSV</button>
          </div>

          <div class="scroll" style="flex: 1">
            <template v-if="auxTab === '结果' && cur">
              <table class="grid" v-if="curPage?.columns.length" data-test="result-grid">
                <thead><tr>
                  <th class="rownum" style="width:56px;min-width:56px;max-width:56px">#</th>
                  <th v-for="c in curPage.columns" :key="c.id" :title="c.comment ?? c.name" :style="{ minWidth: '140px' }">
                    <div class="th-main">
                      <span>{{ c.name }}</span>
                      <span v-if="c.isPk" style="color: var(--warning)">🔑</span>
                    </div>
                    <div class="th-type">{{ c.ty }}</div>
                    <div v-if="c.comment" class="th-comment">{{ c.comment }}</div>
                  </th>
                </tr></thead>
                <tbody>
                  <tr v-for="(row, i) in curPage.rows" :key="i" :style="{ height: 'var(--row-normal)' }">
                    <td class="rownum">{{ i + 1 }}</td>
                    <td v-for="c in curPage.columns" :key="c.id"
                        :class="{ num: c.category === 'number', null: row[c.name] === null }">
                      <em v-if="row[c.name] === null">NULL</em>
                      <template v-else>{{ typeof row[c.name] === 'object' ? JSON.stringify(row[c.name]) : row[c.name] }}</template>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="empty">
                <div class="empty__icon">▤</div>
                <div class="empty__title">无结果集</div>
                <div class="small">写语句不返回结果集 · {{ cur.result.dangerLabel }}</div>
              </div>
            </template>

            <!-- 图表占位 -->
            <div v-else-if="auxTab === '图表'" class="empty">
              <div class="empty__icon">📊</div>
              <div class="empty__title">图表视图</div>
              <div class="small">ECharts 集成待接入（F1 阶段）</div>
            </div>

            <!-- 消息 -->
            <div v-else class="pad mono small">
              <div>[info] 连接就绪</div>
              <div>[info] 元数据已缓存 · {{ state.tree.size }} 个节点</div>
              <div v-if="cur">[info] 返回 {{ curPage?.rows.length }} 行 · {{ cur.result.page.durationMs }} ms</div>
              <div v-if="curPage?.truncatedColumns.length">
                [info] 大字段延迟加载: {{ curPage.truncatedColumns.join(", ") }}
              </div>
              <div v-if="slots.length > 1">[info] 本次执行 {{ slots.length }} 条语句，各自有结果页签</div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
export default {};
</script>

<style scoped>
.query-body { flex: 1; display: flex; min-height: 0; }
/* ⭐ 上下布局：编辑器在上、结果在下。写长 JOIN 时编辑器要占大头 */
.query-body--stack { flex-direction: column; }
.query-body--stack .query-editor { flex: 1 1 55%; min-height: 140px; padding: var(--sp-2) var(--sp-3) var(--sp-1); }
.query-body--stack .result-area { flex: 1 1 45%; border-left: none; border-top: var(--bw) solid var(--border); min-height: 0; }
.query-body--split .query-editor { flex: 0 0 44%; min-width: 0; padding: var(--sp-2) var(--sp-3); }
.query-body--split .result-area { flex: 1; }

.query-editor { display: flex; min-width: 0; min-height: 0; }
.result-area {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.history-panel {
  position: absolute;
  top: 40px; left: 0; right: 0;
  max-height: 300px;
  background: var(--bg-elevated);
  border-bottom: var(--bw) solid var(--border-strong);
  z-index: var(--z-dropdown);
  overflow: auto;
  box-shadow: var(--shadow-md);
}
.history-panel__head {
  display: flex; align-items: center;
  padding: var(--sp-2) var(--sp-3);
  border-bottom: var(--bw) solid var(--border);
}
.history-panel__item {
  padding: var(--sp-2) var(--sp-3);
  cursor: pointer;
  border-bottom: var(--bw) solid var(--border);
}
.history-panel__item:hover { background: var(--bg-hover); }
</style>
