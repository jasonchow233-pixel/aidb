<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { state, actions } from "@/lib/store";
import { api } from "@/lib/api";
import type { TreeNode } from "@/lib/api";
import TreeRow from "./TreeRow.vue";

const filter = ref("");
const hits = ref<import("@/lib/api").SearchHit[]>([]);
const searching = ref(false);
const showTools = ref(true);

// ⭐ 折叠态只保留环境色点 + 角标数字
const criticalCount = computed(() => state.findings.filter((f) => f.level === "critical").length);
const dbId = computed(() => actions.dbNodeId());
const dbNode = computed(() => (dbId.value ? state.tree.get(dbId.value) : undefined));

// ⭐ C1-5：连接只读 → 盾牌 + 禁用一切编辑入口
const readOnly = computed(() => actions.isConnectionReadOnly());

// ⭐ C1-3：按 kind 分组（视图独立成组；表按风险分成高/中/低）
const allObjects = computed(() => actions.childrenOf(dbId.value || undefined));
const tableView = computed(() => allObjects.value.filter((n) => n.kind === "table"));
const viewNodes = computed(() => allObjects.value.filter((n) => n.kind === "view"));
const highRisk = computed(() => tableView.value.filter((t) => (t.riskBadge ?? 0) >= 2));
const medRisk = computed(() => tableView.value.filter((t) => (t.riskBadge ?? 0) === 1));
const lowRisk = computed(() => tableView.value.filter((t) => !t.riskBadge));
const starredNodes = computed(() => actions.starredNodes());

// ⭐ 首次加载自动展开到表一级
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
// ⭐ 判断某树节点是否对应当前激活页签（按 label 匹配，兼容 t:/s: 两种页签与表/视图）。
//   之前用 `tabFor(t.id)` 按 id 前缀反推，但节点 id 是 `tbl:t:<name>` 双段格式，
//   反推出来是 `t:t:users` 与 openTable 的 `t:users` 对不上 —— 选中高亮永远不亮。
function isActive(node: TreeNode): boolean {
  return state.activeTabId === `t:${node.label}` || state.activeTabId === `s:${node.label}`;
}

// ── C1-1 右键菜单 ────────────────────────────────────────────
// ⭐ 监听器常驻（onMounted 挂 / onBeforeUnmount 摘），
//   关闭逻辑用「开门检查」（点在菜单内不关）—— 避免上轮 #14/#15 那类
//   监听器泄漏 + 事件被吞的 bug。
const treeCtx = ref<{ x: number; y: number; node: TreeNode } | null>(null);

function onTreeCtx(node: TreeNode, ev: MouseEvent) {
  treeCtx.value = { x: ev.clientX, y: ev.clientY, node };
}
function closeTreeCtx() { treeCtx.value = null; }

function onGlobalMousedown(e: MouseEvent) {
  if (!treeCtx.value) return;
  const menu = document.querySelector("[data-test=tree-ctx-menu]");
  if (menu && e.target instanceof Node && menu.contains(e.target)) return;
  closeTreeCtx();
}
function onCtxKey(e: KeyboardEvent) {
  if (e.key === "Escape") closeTreeCtx();
}
onMounted(() => {
  window.addEventListener("keydown", onCtxKey);
  window.addEventListener("mousedown", onGlobalMousedown, { capture: true });
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onCtxKey);
  window.removeEventListener("mousedown", onGlobalMousedown, { capture: true });
});

// ⭐ 菜单动作：集中在这里，模板只负责渲染
function runCtx(kind: string) {
  const node = treeCtx.value?.node;
  if (!node) return;
  const table = node.label;
  switch (kind) {
    case "open": actions.openTable(table); break;
    case "structure": actions.openStructure(table); break;
    case "refresh": void actions.refreshObject(table); break;
    case "star": actions.toggleStar(table); break;
    case "copy-name": void actions.copyName(table); break;
    case "copy-select": void actions.copySelect(table); break;
    case "query": actions.openInQuery(table); break;
    case "diagnose": actions.diagnoseTable(table); break;
  }
  closeTreeCtx();
}
function starLabel(): string {
  const node = treeCtx.value?.node;
  return node && actions.isStarred(node.label) ? "取消收藏" : "收藏";
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
        <span class="count">{{ allObjects.length }}</span>
      </button>

      <template v-if="state.expanded.has('__conn__')">
        <!-- 数据库节点（只读时显示盾牌） -->
        <button
          v-if="dbNode"
          class="node node--prod"
          :class="{ 'node--readonly': readOnly }"
          :aria-expanded="state.expanded.has(dbNode.id)"
          @click="actions.toggle(dbNode.id)"
        >
          <span class="caret" :class="{ on: state.expanded.has(dbNode.id) }">▶</span>
          <span class="ico">🗄</span>
          <span class="grow truncate">{{ dbNode.label }}</span>
          <span v-if="readOnly" class="shield" title="连接只读：编辑入口已禁用">🛡 只读</span>
          <span v-else class="meta">{{ dbNode.meta }}</span>
        </button>

        <template v-if="state.expanded.has(dbId)">
          <!-- 高风险表 -->
          <template v-if="highRisk.length">
            <button class="sec sec--risk" @click="showTools = !showTools">
              <span class="caret" :class="{ on: showTools }">▶</span>高风险表
              <span class="count" style="color: var(--risk-2-structure)">{{ highRisk.length }}</span>
            </button>
            <template v-if="showTools">
              <TreeRow
                v-for="t in highRisk" :key="t.id" :node="t" cls="node--risk-high"
                :class="{ sel: isActive(t) }"
                @open="(n) => actions.pickTable(n.id, n.label)"
                @dblopen="(n) => actions.openTable(n.label)"
                @ctx="onTreeCtx" @toggle-star="(n) => actions.toggleStar(n.label)"
              />
            </template>
          </template>

          <!-- 中风险表 -->
          <TreeRow
            v-for="t in medRisk" :key="t.id" :node="t" cls="node--risk-medium"
            :class="{ sel: isActive(t) }"
            @open="(n) => actions.pickTable(n.id, n.label)"
            @dblopen="(n) => actions.openTable(n.label)"
            @ctx="onTreeCtx" @toggle-star="(n) => actions.toggleStar(n.label)"
          />

          <!-- 低风险 / 无风险表 -->
          <TreeRow
            v-for="t in lowRisk" :key="t.id" :node="t"
            :class="{ sel: isActive(t) }"
            @open="(n) => actions.pickTable(n.id, n.label)"
            @dblopen="(n) => actions.openTable(n.label)"
            @ctx="onTreeCtx" @toggle-star="(n) => actions.toggleStar(n.label)"
          />

          <!-- ⭐ C1-3 视图独立成组（按 kind 渲染，不硬编码） -->
          <template v-if="viewNodes.length">
            <button class="sec" style="margin-top: var(--sp-2)" @click="showTools = !showTools">
              <span class="caret" :class="{ on: showTools }">▶</span>视图
              <span class="count">{{ viewNodes.length }}</span>
            </button>
            <TreeRow
              v-for="v in viewNodes" :key="v.id" :node="v"
              :class="{ sel: isActive(v) }"
              @open="(n) => actions.pickTable(n.id, n.label)"
              @dblopen="(n) => actions.openTable(n.label)"
              @ctx="onTreeCtx" @toggle-star="(n) => actions.toggleStar(n.label)"
            />
          </template>

          <!-- ⭐ C1-4 收藏组 -->
          <template v-if="starredNodes.length">
            <button class="sec" style="margin-top: var(--sp-2)" @click="showTools = !showTools">
              <span class="caret" :class="{ on: showTools }">▶</span>收藏
              <span class="count">{{ starredNodes.length }}</span>
            </button>
            <TreeRow
              v-for="s in starredNodes" :key="s.id" :node="s"
              :class="{ sel: isActive(s) }"
              @open="(n) => actions.pickTable(n.id, n.label)"
              @dblopen="(n) => actions.openTable(n.label)"
              @ctx="onTreeCtx" @toggle-star="(n) => actions.toggleStar(n.label)"
            />
          </template>

          <!-- 展开表的列 -->
          <template v-for="t in tableView" :key="`cols:${t.id}`">
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

    <!-- ⭐ C1-1 右键菜单（10 项 + 2 分隔线） -->
    <div
      v-if="treeCtx"
      class="ctxmenu"
      data-test="tree-ctx-menu"
      :style="{ left: treeCtx.x + 'px', top: treeCtx.y + 'px' }"
    >
      <button class="ctxmenu__item" data-test="ctx-tree-open" @click="runCtx('open')">打开数据</button>
      <button class="ctxmenu__item" data-test="ctx-tree-structure" @click="runCtx('structure')">打开结构</button>
      <button class="ctxmenu__item" data-test="ctx-tree-refresh" @click="runCtx('refresh')">刷新此表</button>
      <div class="ctxmenu__sep"></div>
      <button class="ctxmenu__item" data-test="ctx-tree-star" @click="runCtx('star')">{{ starLabel() }}</button>
      <button class="ctxmenu__item" data-test="ctx-tree-copy-name" @click="runCtx('copy-name')">复制表名</button>
      <button class="ctxmenu__item" data-test="ctx-tree-copy-select" @click="runCtx('copy-select')">复制 SELECT</button>
      <button class="ctxmenu__item" data-test="ctx-tree-query" @click="runCtx('query')">在查询中打开</button>
      <div class="ctxmenu__sep"></div>
      <button class="ctxmenu__item" data-test="ctx-tree-diagnose" @click="runCtx('diagnose')">诊断此表</button>
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
.node--readonly::before { background: var(--warning); }
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
.shield { margin-left: auto; font-size: var(--fs-xs); color: var(--warning); flex: 0 0 auto; }

.hit {
  display: flex; align-items: center; gap: 6px;
  padding: 5px var(--sp-2); cursor: pointer; font-size: var(--fs-sm);
}
.hit:hover { background: var(--bg-hover); }
.hit__kind { font-size: 10px; padding: 0 4px; border-radius: var(--r-sm);
  background: var(--bg-elevated); color: var(--text-tertiary); flex: 0 0 auto; }

/* ⭐ C1-1 右键菜单 */
.ctxmenu {
  position: fixed; z-index: 1000; min-width: 168px;
  background: var(--bg-surface); border: var(--bw) solid var(--border);
  border-radius: var(--r-md); box-shadow: var(--shadow-pop);
  padding: 4px; font-size: var(--fs-sm);
}
.ctxmenu__item {
  display: block; width: 100%; text-align: left; padding: 6px 10px;
  background: none; border: none; color: var(--text-primary);
  border-radius: var(--r-sm); cursor: pointer; font-size: inherit;
}
.ctxmenu__item:hover { background: var(--bg-hover); }
.ctxmenu__sep { height: 1px; background: var(--border); margin: 4px 6px; }
</style>
