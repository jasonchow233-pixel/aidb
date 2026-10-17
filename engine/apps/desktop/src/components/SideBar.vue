<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { state, actions } from "@/lib/store";
import { api, capabilities, type SearchHit } from "@/lib/api";

const filter = ref("");
const hits = ref<SearchHit[]>([]);
const searching = ref(false);
const showTools = ref(true);

// ⭐ 折叠态只保留环境色点 + 角标数字
const criticalCount = computed(() => state.findings.filter((f) => f.level === "critical").length);
const dbId = computed(() => [...state.tree.keys()].find((k) => k.startsWith("db:")) ?? "");
const tables = computed(() => actions.childrenOf(dbId.value || undefined));

// ⭐ 按风险等级分组
const tablesByRisk = computed(() => {
  const high = tables.value.filter((t) => (t.riskBadge ?? 0) >= 2);
  const medium = tables.value.filter((t) => (t.riskBadge ?? 0) === 1);
  const low = tables.value.filter((t) => !t.riskBadge);
  return { high, medium, low, total: tables.value.length };
});

// ⭐ 首次加载自动展开到表一级
//
// ⭐ 两个分区都要展开：`__conn__`（数据库分组标题）与 db 节点本身。
// 之前只 toggle 了 db 节点，而模板要求**两者同时**展开才渲染表 ——
// 于是首次加载侧栏是一棵空树，用户以为坏了，得连点两次才看到表。
onMounted(() => {
  watch(
    () => dbId.value,
    (id) => {
      if (!id) return;
      if (!state.expanded.has("__conn__")) state.expanded.add("__conn__");
      if (!state.expanded.has(id)) actions.toggle(id);
    },
    { immediate: true },
  );
});

let searchTimer: number | undefined;
watch(filter, (q) => {
  clearTimeout(searchTimer);
  if (!q.trim()) { hits.value = []; return; }
  searching.value = true;
  searchTimer = window.setTimeout(async () => {
    try { hits.value = await api.search(q); } finally { searching.value = false; }
  }, 180);
});

function columnsOf(tableId: string) { return actions.childrenOf(tableId); }
function hasComment(nodes: ReturnType<typeof columnsOf>) { return nodes.some((n) => n.comment); }
function tabFor(tableId: string) { return `t:${tableId.replace("tbl:", "")}`; }

function riskClass(badge?: number | null) {
  if (!badge) return "";
  if (badge >= 3) return "risk-high";
  if (badge >= 1) return "risk-medium";
  return "risk-low";
}
</script>

<template>
  <!-- ══ 折叠态 48px ══ -->
  <div v-if="state.sidebarCollapsed" class="collapsed">
    <button class="ci" title="展开侧栏 (⌘\)" @click="actions.toggleSidebar()">☰</button>
    <button class="ci" title="数据库" @click="actions.toggleSidebar()">🗄</button>
    <button class="ci ci--badge" title="诊断" @click="actions.openTool('diagnosis', '诊断')">
      ◆<template v-if="criticalCount">{{ criticalCount }}</template>
    </button>
    <button class="ci" title="数据工厂" @click="actions.openTool('mask', '数据工厂')">🎭</button>
    <button class="ci" title="审计" @click="actions.openTool('audit', '审计')">📋</button>
  </div>

  <!-- ══ 展开态 ══ -->
  <template v-else>
    <div class="search">
      <input class="input input--mono" v-model="filter" placeholder="搜索表 / 列 / 注释…" />
      <div v-if="filter" class="search__meta">
        {{ searching ? "搜索中…" : `${hits.length} 条结果` }}
      </div>
    </div>

    <!-- 搜索结果 -->
    <div v-if="filter" class="tree">
      <div v-if="!hits.length && !searching" class="empty" style="padding: var(--sp-6)">
        <div class="empty__title">没有匹配「{{ filter }}」的对象</div>
        <div class="small">试试搜注释（如「订单」）或拼音首字母（如「dd」）</div>
      </div>
      <div
        v-for="h in hits"
        :key="h.id"
        class="hit"
        @click="h.kind === 'table' && (actions.openTable(h.label), filter = '')"
      >
        <span class="hit__kind">{{ h.kind === 'table' ? '表' : '列' }}</span>
        <span class="grow truncate">
          <span class="mono">{{ h.label }}</span>
          <span v-if="h.table" class="muted small"> · {{ h.table }}</span>
          <div v-if="h.comment" class="small muted truncate">{{ h.comment }}</div>
        </span>
      </div>
    </div>

    <!-- 对象树（懒加载） -->
    <div v-else class="tree">
      <button class="sec" @click="actions.toggle('__conn__')">
        <span class="caret" :class="{ on: state.expanded.has('__conn__') }">▶</span>数据库
        <span class="count">{{ tablesByRisk.total }}</span>
      </button>

      <template v-if="state.expanded.has('__conn__')">
        <button v-for="n in actions.childrenOf()" :key="n.id" class="node node--prod"
                :aria-expanded="state.expanded.has(n.id)" @click="actions.toggle(n.id)">
          <span class="caret" :class="{ on: state.expanded.has(n.id) }">▶</span>
          <span class="ico">🗄</span>
          <span class="grow truncate">{{ n.label }}</span>
          <span class="meta">{{ n.meta }}</span>
        </button>

        <template v-if="state.expanded.has(dbId)">
          <!-- ⭐ 按风险分组显示 -->
          <template v-if="tablesByRisk.high.length">
            <button class="sec sec--risk" @click="showTools = !showTools">
              <span class="caret" :class="{ on: showTools }">▶</span>高风险表
              <span class="count" style="color: var(--risk-2-structure)">{{ tablesByRisk.high.length }}</span>
            </button>
            <template v-if="showTools">
              <button v-for="t in tablesByRisk.high" :key="t.id"
                  class="node node--risk-high"
                  :class="{ sel: state.activeTabId === tabFor(t.id) }"
                  :title="t.comment ?? undefined"
                  :aria-expanded="state.expanded.has(t.id)"
                  @click="actions.pickTable(t.id, t.label)">
                <span class="caret" :class="{ on: state.expanded.has(t.id) }">▶</span>
                <span class="ico">▦</span>
                <span class="row grow">
                  <span class="truncate">{{ t.label }}</span>
                  <span v-if="!state.expanded.has(t.id)" class="comment truncate">{{ t.comment }}</span>
                  <span class="meta">{{ t.meta }}</span>
                  <span v-if="t.riskBadge" class="badge-warn" :title="`${t.riskBadge} 个问题`">⚠{{ t.riskBadge }}</span>
                </span>
              </button>
            </template>
          </template>

          <template v-for="t in tablesByRisk.medium" :key="t.id">
            <button class="node node--risk-medium"
                :class="{ sel: state.activeTabId === tabFor(t.id) }"
                :title="t.comment ?? undefined"
                :aria-expanded="state.expanded.has(t.id)"
                @click="actions.pickTable(t.id, t.label)">
              <span class="caret" :class="{ on: state.expanded.has(t.id) }">▶</span>
              <span class="ico">▦</span>
              <span class="row grow">
                <span class="truncate">{{ t.label }}</span>
                <span v-if="!state.expanded.has(t.id)" class="comment truncate">{{ t.comment }}</span>
                <span class="meta">{{ t.meta }}</span>
                <span v-if="t.riskBadge" class="badge-warn" :title="`${t.riskBadge} 个问题`">⚠{{ t.riskBadge }}</span>
              </span>
            </button>
          </template>

          <template v-for="t in tablesByRisk.low" :key="t.id">
            <button class="node"
                :class="{ sel: state.activeTabId === tabFor(t.id) }"
                :title="t.comment ?? undefined"
                :aria-expanded="state.expanded.has(t.id)"
                @click="actions.pickTable(t.id, t.label)">
              <span class="caret" :class="{ on: state.expanded.has(t.id) }">▶</span>
              <span class="ico">▦</span>
              <span class="row grow">
                <span class="truncate">{{ t.label }}</span>
                <span v-if="!state.expanded.has(t.id)" class="comment truncate">{{ t.comment }}</span>
                <span class="meta">{{ t.meta }}</span>
              </span>
            </button>
          </template>

          <!-- 展开表的列 -->
          <template v-for="t in tables" :key="`cols:${t.id}`">
            <template v-if="state.expanded.has(t.id)">
              <div v-for="c in columnsOf(t.id)" :key="c.id" class="node node--col" :title="c.comment ?? undefined">
                <span class="caret"></span>
                <span class="ico">·</span>
                <span class="grow truncate">
                  <span class="mono">{{ c.label }}</span>
                  <span class="meta"> {{ c.meta }}</span>
                </span>
              </div>
            </template>
          </template>
        </template>
      </template>

      <button class="sec" style="margin-top: var(--sp-3)" @click="actions.toggle('__tools__')">
        <span class="caret" :class="{ on: state.expanded.has('__tools__') }">▶</span>工具
        <span class="count">{{ state.findings.length }}</span>
      </button>
      <template v-if="state.expanded.has('__tools__')">
        <button class="node" @click="actions.openTool('diagnosis', '诊断')">
          <span class="caret"></span><span class="ico">◆</span>
          <span class="grow">诊断</span>
          <span class="meta" style="color: var(--warning)">{{ state.findings.length }} 个问题</span>
        </button>
        <button class="node" @click="actions.openTool('transfer', '数据传输')">
          <span class="caret"></span><span class="ico">⇄</span><span class="grow">数据传输</span>
        </button>
        <button class="node" @click="actions.openTool('mask', '数据工厂')">
          <span class="caret"></span><span class="ico">🎭</span><span class="grow">数据工厂</span>
        </button>
        <button class="node" @click="actions.openTool('capability', '能力矩阵')">
          <span class="caret"></span><span class="ico">▤</span><span class="grow">能力矩阵</span>
        </button>
        <button class="node" @click="actions.openTool('audit', '变更管控')">
          <span class="caret"></span><span class="ico">📋</span><span class="grow">变更管控</span>
        </button>
      </template>
    </div>
  </template>
</template>

<style scoped>
.collapsed { display: flex; flex-direction: column; align-items: stretch; padding: var(--sp-1) 0; }
.ci {
  height: 30px; font-size: var(--fs-sm); color: var(--text-secondary);
  border-radius: 0; border-left: 3px solid transparent;
}
.ci:hover { background: var(--bg-hover); color: var(--text-primary); }
.ci--badge { color: var(--warning); font-size: 10px; }

.search { padding: var(--sp-2); border-bottom: var(--bw) solid var(--border); }
.search__meta { font-size: var(--fs-xs); color: var(--text-tertiary); margin-top: 4px; }

.tree { flex: 1; overflow: auto; padding: var(--sp-1) 0 var(--sp-4); min-height: 0; }

.sec {
  display: flex; align-items: center; gap: 6px; width: 100%;
  height: 26px; padding: 0 var(--sp-2); text-align: left;
  background: none; border: none; color: inherit; font-family: inherit;
  font-size: var(--fs-xs); font-weight: var(--fw-medium);
  color: var(--text-tertiary); text-transform: uppercase; letter-spacing: .04em;
  cursor: pointer; user-select: none;
}
.sec:hover { color: var(--text-secondary); background: var(--bg-hover); }
.sec--risk { color: var(--warning); }
.count {
  margin-left: auto; padding: 0 5px; border-radius: var(--r-full);
  background: var(--bg-elevated); font-size: 10px;
}

.node {
  display: flex; align-items: center; gap: 5px; width: 100%;
  height: 26px; padding: 0 var(--sp-2); text-align: left;
  font-size: var(--fs-sm); cursor: pointer; white-space: nowrap; position: relative;
  background: none; border: none; color: inherit; font-family: inherit;
}
.node:hover { background: var(--bg-hover); }
.node.sel { background: var(--bg-selected); }
.node--prod::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--env-prod); }
.node--risk-high { border-left: 3px solid var(--risk-2-structure); }
.node--risk-medium { border-left: 3px solid var(--warning); }
.node--col { padding-left: 34px; }
.col-comments { padding: 2px 0 4px 52px; }

.caret { width: 12px; flex: 0 0 12px; text-align: center; color: var(--text-tertiary);
  font-size: 8px; transition: transform var(--dur-fast) var(--ease-out); }
.caret.on { transform: rotate(90deg); }
.ico { flex: 0 0 14px; text-align: center; font-size: 11px; }
.row { display: flex; align-items: center; gap: 5px; min-width: 0; }
.comment { color: var(--text-tertiary); font-size: var(--fs-xs); flex: 1; min-width: 0; }
.meta { margin-left: auto; font-size: var(--fs-xs); color: var(--text-tertiary);
  font-family: var(--font-mono); flex: 0 0 auto; }
.badge-warn { margin-left: 4px; padding: 0 4px; border-radius: var(--r-sm);
  background: var(--warning-bg); color: var(--warning); font-size: 10px; flex: 0 0 auto; }

.hit {
  display: flex; align-items: center; gap: 6px;
  padding: 5px var(--sp-2); cursor: pointer; font-size: var(--fs-sm);
}
.hit:hover { background: var(--bg-hover); }
.hit__kind { font-size: 10px; padding: 0 4px; border-radius: var(--r-sm);
  background: var(--bg-elevated); color: var(--text-tertiary); flex: 0 0 auto; }
</style>
