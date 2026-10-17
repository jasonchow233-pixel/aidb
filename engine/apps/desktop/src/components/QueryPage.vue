<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { state, actions, notify } from "@/lib/store";
import { api, capabilities, dangerClass, dangerIcon, type SearchHit, type QueryResult } from "@/lib/api";

const sql = ref("SELECT id, order_no, status, total_cents, created_at\nFROM orders\nWHERE status = 2\nORDER BY created_at DESC\nLIMIT 50;");
const elapsed = ref<number | null>(null);
const result = ref<QueryResult | null>(null);
const err = ref<any>(null);
const errPayload = ref<any>(null);
const running = ref(false);
const resultTab = ref("结果 1");
const history = ref<string[]>([]);
const showHistory = ref(false);

// ⭐ 元数据驱动补全：WHERE 里推列，FROM 里推表
const tables = computed(() =>
  Object.values(state.tree).filter((n) => n.id.startsWith("tbl:") && n.kind === "table")
);

const columns = computed(() => state.tableDef?.columns ?? []);

function suggestions(): SearchHit[] {
  const sel = (document.querySelector(".cm-editor") as any)?.cmView?.view;
  const pos = state.tableDef ? sql.value.length : sql.value.length;
  const before = sql.value.slice(0, pos);
  const tail = before.split(/[\s(,]/).pop()?.toLowerCase() ?? "";
  if (!tail) return [];

  const isWhere = /\bwhere|and|or|on\b[^()]*$/i.test(before.replace(/\n/g, " "));
  const isFrom = /\bfrom\b[^()]*$/i.test(before.replace(/\n/g, " "));

  if (isWhere && state.tableDef) {
    return state.tableDef.columns
      .filter((c) => c.name.toLowerCase().includes(tail))
      .map((c) => ({ kind: "column", id: c.id, label: c.name, comment: c.comment }));
  }
  if (isFrom) {
    return tables.value
      .filter((t) => t.label.toLowerCase().includes(tail))
      .map((t) => ({ kind: "table", id: t.id, label: t.label, comment: t.comment, rows: t.meta }));
  }
  return [];
}

function complete(h: SearchHit) {
  const pos = sql.value.length;
  const before = sql.value.slice(0, pos);
  const tail = before.split(/[\s(,]/).pop() ?? "";
  sql.value = before.slice(0, before.length - tail.length) + h.label + sql.value.slice(pos);
}

async function run() {
  running.value = true;
  errPayload.value = null;
  err.value = null;
  const t0 = performance.now();
  try {
    result.value = await actions.runSql(sql.value);
    elapsed.value = Math.round(performance.now() - t0);
    resultTab.value = "结果 1";
    // ⭐ 历史记录
    if (!history.value.includes(sql.value)) {
      history.value.unshift(sql.value);
      if (history.value.length > 20) history.value.pop();
    }
  } catch (e: any) {
    errPayload.value = e?.payload ?? null;
  } finally {
    running.value = false;
  }
}

function jumpTo(line: number, column: number) {
  notify("info", `跳转：第 ${line} 行 第 ${column} 列`);
}

function switchToGrid() {
  if (!result.value) return;
  state.rows = result.value.page.rows;
  state.columns = result.value.page.columns;
  state.total = result.value.page.total ?? null;
  state.queryMs = result.value.page.durationMs;
  state.lastDanger = result.value.danger;
  const id = `grid:${Date.now()}`;
  state.tabs.push({ id, title: "查询结果", kind: "grid" });
  state.activeTabId = id;
}

function exportCSV() {
  if (!result.value?.page.rows.length) return;
  const cols = result.value.page.columns.map((c) => c.name);
  const lines = [cols.join(",")];
  for (const row of result.value.page.rows) {
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

function loadHistory(h: string) {
  sql.value = h;
  showHistory.value = false;
}

// SQL 格式化（简单实现）
function formatSql() {
  const keywords = ["SELECT", "FROM", "WHERE", "AND", "OR", "ORDER BY", "GROUP BY", "HAVING", "LIMIT", "JOIN", "LEFT JOIN", "INNER JOIN", "ON", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "WITH", "UNION"];
  let formatted = sql.value;
  for (const kw of keywords) {
    const re = new RegExp(`\\b${kw}\\b`, "gi");
    formatted = formatted.replace(re, kw);
  }
  sql.value = formatted;
}
</script>

<template>
  <div class="editor-page">
    <!-- 工具栏 -->
    <div class="editor-page__toolbar">
      <span class="muted small">数据库</span>
      <span class="badge">{{ capabilities.byId(state.connectionKind)?.label }}</span>
      <div class="toolbar__sep"></div>
      <button class="btn btn--sm btn--primary" :disabled="running" @click="run">
        {{ running ? "执行中…" : "▶ 执行 ⌘⏎" }}
      </button>
      <button class="btn btn--sm" @click="run">执行选中 ⌘⇧⏎</button>
      <button class="btn btn--sm" @click="api.cancel().then(() => notify('info','已请求取消'))">取消 ⌘.</button>
      <div class="toolbar__sep"></div>
      <button class="btn btn--sm" @click="formatSql">格式化</button>
      <button class="btn btn--sm" @click="showHistory = !showHistory">历史 ({{ history.length }})</button>
      <span class="grow"></span>
      <span v-if="result" class="badge" :class="`badge--${dangerClass(result.danger)}`">
        {{ dangerIcon(result.danger) }} {{ result.dangerLabel }}
        <template v-if="result.requiresConfirm"> · 需确认</template>
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

    <div class="editor-body">
      <!-- 编辑器 + 补全 -->
      <div style="position: relative; flex: 0 0 40%; display: flex; flex-direction: column">
        <textarea
          v-model="sql"
          class="editor"
          spellcheck="false"
          placeholder="输入 SQL 语句…"
          @keydown.ctrl.enter.prevent="run"
          @keydown.meta.enter.prevent="run"
        ></textarea>
        <!-- 补全浮层 -->
        <div class="cmpl" v-if="suggestions().length && sql.length > 2">
          <div v-for="h in suggestions().slice(0, 8)" :key="h.id" class="cmpl__item" @click="complete(h)">
            <span class="cmpl__kind">{{ h.kind === 'table' ? '表' : '列' }}</span>
            <span class="mono">{{ h.label }}</span>
            <span class="muted small truncate" v-if="h.comment">{{ h.comment }}</span>
          </div>
        </div>
      </div>

      <!-- 结果区 -->
      <div class="result-area">
        <!-- 错误提示 -->
        <div v-if="errPayload" class="sql-error">
          <div class="sql-error__msg">{{ errPayload.message }}</div>
          <div v-if="errPayload.position" class="sql-error__pos">
            位置: 第 {{ errPayload.position.line }} 行, 第 {{ errPayload.position.column }} 列
            <button class="btn btn--sm btn--ghost" @click="jumpTo(errPayload.position.line, errPayload.position.column)">
              跳转到错误位置
            </button>
          </div>
          <div v-if="errPayload.hints?.length" class="sql-error__hints">
            <div class="small muted">你是否想找：</div>
            <div v-for="h in errPayload.hints" :key="h.label" class="sql-error__hint" @click="notify('info', `替换为 ${h.label}`)">
              <span class="mono">{{ h.label }}</span>
              <span class="muted">{{ h.detail }}</span>
            </div>
          </div>
        </div>

        <!-- 状态条 -->
        <div class="editor-msg" v-if="result">
          <span :style="{ color: 'var(--success)' }">✓ 执行成功</span>
          · {{ result.page.rows.length.toLocaleString() }} 行
          · {{ elapsed ?? result.page.durationMs }} ms
          <span v-if="result.page.total !== null">· 共 {{ result.page.total.toLocaleString() }} 行</span>
          <span v-if="result.page.mutated">· 写操作（已在事务中验证并回滚）</span>
        </div>

        <!-- 结果页签 -->
        <div class="result-tabs" v-if="result">
          <div class="result-tabs__item result-tabs__item--active" @click="resultTab = '结果 1'">
            结果 1 ({{ result.page.rows.length }})
          </div>
          <div class="result-tabs__item" @click="resultTab = '图表'">图表</div>
          <div class="result-tabs__item" @click="resultTab = '消息'">消息</div>
          <span class="grow"></span>
          <button class="btn btn--sm" @click="switchToGrid">在网格中打开</button>
          <button class="btn btn--sm" @click="exportCSV">导出 CSV</button>
        </div>

        <div class="scroll" style="flex: 1">
          <!-- 结果表格 -->
          <template v-if="resultTab === '结果 1' && result">
            <table class="grid" v-if="result.page.columns.length">
              <thead><tr>
                <th class="rownum" style="width:56px;min-width:56px;max-width:56px">#</th>
                <th v-for="c in result.page.columns" :key="c.id" :title="c.comment ?? c.name" :style="{ minWidth: '140px' }">
                  <div class="th-main">
                    <span>{{ c.name }}</span>
                    <span v-if="c.isPk" style="color: var(--warning)">🔑</span>
                  </div>
                  <div class="th-type">{{ c.ty }}</div>
                  <div v-if="c.comment" class="th-comment">{{ c.comment }}</div>
                </th>
              </tr></thead>
              <tbody>
                <tr v-for="(row, i) in result.page.rows" :key="i" :style="{ height: 'var(--row-normal)' }">
                  <td class="rownum">{{ i + 1 }}</td>
                  <td v-for="c in result.page.columns" :key="c.id"
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
              <div class="small">写语句不返回结果集 · {{ result.dangerLabel }}</div>
            </div>
          </template>

          <!-- 图表占位 -->
          <div v-else-if="resultTab === '图表'" class="empty">
            <div class="empty__icon">📊</div>
            <div class="empty__title">图表视图</div>
            <div class="small">ECharts 集成待接入（F1 阶段）</div>
          </div>

          <!-- 消息 -->
          <div v-else class="pad mono small">
            <div>[info] 连接就绪</div>
            <div>[info] 元数据已缓存 · {{ state.tree.size }} 个节点</div>
            <div v-if="result">[info] 返回 {{ result.page.rows.length }} 行 · {{ result.page.durationMs }} ms</div>
            <div v-if="result?.page.truncatedColumns.length">
              [info] 大字段延迟加载: {{ result.page.truncatedColumns.join(", ") }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-body {
  display: flex;
  flex: 1;
  min-height: 0;
}
.result-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-left: var(--bw) solid var(--border);
  min-width: 0;
}
.history-panel {
  position: absolute;
  top: 40px;
  left: 0;
  right: 0;
  max-height: 300px;
  background: var(--bg-elevated);
  border-bottom: var(--bw) solid var(--border-strong);
  z-index: var(--z-dropdown);
  overflow: auto;
  box-shadow: var(--shadow-md);
}
.history-panel__head {
  display: flex;
  align-items: center;
  padding: var(--sp-2) var(--sp-3);
  border-bottom: var(--bw) solid var(--border);
}
.history-panel__item {
  padding: var(--sp-2) var(--sp-3);
  cursor: pointer;
  border-bottom: var(--bw) solid var(--border);
}
.history-panel__item:hover { background: var(--bg-hover); }
.cmpl {
  position: absolute;
  top: 60px;
  left: 60px;
  z-index: var(--z-dropdown);
  min-width: 320px;
  max-width: 440px;
  background: var(--bg-elevated);
  border: var(--bw) solid var(--border-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-md);
  padding: var(--sp-1);
  font-size: var(--fs-xs);
}
.cmpl__item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px var(--sp-2);
  border-radius: var(--r-sm);
  cursor: pointer;
}
.cmpl__item:hover { background: var(--bg-hover); }
.cmpl__kind {
  font-size: 10px;
  padding: 0 4px;
  border-radius: var(--r-sm);
  background: var(--bg-elevated);
  color: var(--text-tertiary);
  flex: 0 0 auto;
}
</style>
