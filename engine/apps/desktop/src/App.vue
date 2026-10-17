<script setup lang="ts">
import { computed, onMounted } from "vue";
import { state, actions } from "@/lib/store";
import { capabilities } from "@/lib/api";

import SideBar from "@/components/SideBar.vue";
import DataGridPage from "@/components/DataGridPage.vue";
import QueryPage from "@/components/QueryPage.vue";
import StructurePage from "@/components/StructurePage.vue";
import DiagnosisPage from "@/components/DiagnosisPage.vue";
import TransferPage from "@/components/TransferPage.vue";
import CapabilityPage from "@/components/CapabilityPage.vue";
import MaskPage from "@/components/MaskPage.vue";
import ChangeControlPage from "@/components/ChangeControlPage.vue";
import AiPanel from "@/components/AiPanel.vue";
import StatusBar from "@/components/StatusBar.vue";

const tab = computed(() => actions.activeTab());

onMounted(() => {
  void actions.boot();
  document.addEventListener("keydown", onKey);
});

/** ⭐ 全局快捷键（UI-SPEC §5） */
function onKey(e: KeyboardEvent) {
  const meta = e.metaKey || e.ctrlKey;
  if (!meta) return;

  if (e.key === "\\") { e.preventDefault(); actions.toggleSidebar(); }
  else if (e.key.toLowerCase() === "k") { e.preventDefault(); actions.toggleAi(); }
  else if (e.key.toLowerCase() === "t") { e.preventDefault(); actions.openQuery(); }
  else if (e.key.toLowerCase() === "w") { e.preventDefault(); if (state.activeTabId) actions.closeTab(state.activeTabId); }
  else if (e.key.toLowerCase() === "r") { e.preventDefault(); state.tableDef && void actions.loadTable(state.tableDef.name); }
  else if (e.key.toLowerCase() === "f" && state.tableDef) {
    e.preventDefault();
    actions.openQuery(`SELECT * FROM "${state.tableDef.name}" LIMIT ${state.rowLimit}`);
  }
}

function runFromGrid(sql: string) { actions.openQuery(sql); }

/** 侧栏拖拽调宽：实时跟随，写入 localStorage */
function startDrag(ev: MouseEvent) {
  const startX = ev.clientX;
  const startW = state.sidebarWidth;
  const move = (e: MouseEvent) => actions.setSidebarWidth(startW + (e.clientX - startX));
  const up = () => {
    window.removeEventListener("mousemove", move);
    window.removeEventListener("mouseup", up);
    document.body.style.cursor = "";
  };
  document.body.style.cursor = "col-resize";
  window.addEventListener("mousemove", move);
  window.addEventListener("mouseup", up);
}
</script>

<template>
  <div class="app-shell">
    <!-- ══ 标题栏 ══ -->
    <div class="titlebar">
      <span class="titlebar__brand">
        <span class="titlebar__logo"></span>
        AgenticDB
      </span>
      <span class="badge badge--risk-2" title="构建产物是只读的：改 YAML 会产生 git diff，review 时可见">
        声明驱动
      </span>
      <span class="titlebar__spacer"></span>
      <button class="btn btn--sm" @click="actions.openQuery()">＋ 查询 <span class="kbd">⌘T</span></button>
      <button class="btn btn--sm" @click="actions.toggleAi()">
        AI 助手 <span class="kbd">⌘K</span>
      </button>
      <span class="muted small mono" style="min-width:60px;text-align:right">v0.1.0</span>
    </div>

    <!-- ══ 主体 ══ -->
    <div class="app-body">
      <!-- 侧栏（可拖拽） -->
      <div class="sidebar" :class="{ 'sidebar--collapsed': state.sidebarCollapsed }">
        <SideBar />
      </div>
      <div v-if="!state.sidebarCollapsed" class="resizer" @mousedown="startDrag"></div>

      <!-- 主区 -->
      <div class="app-main">
        <!-- 标签栏 -->
        <div class="tabbar">
          <div v-for="t in state.tabs" :key="t.id" class="tab"
               :class="{ 'tab--active': t.id === state.activeTabId, 'tab--dirty': actions.pendingCount(t.table) > 0 }"
               @click="state.activeTabId = t.id">
            <span class="truncate">{{ t.title }}</span>
            <!-- ⭐ 未保存计数：切走/关页面前用户能一眼看到有未落库的改动 -->
            <span v-if="actions.pendingCount(t.table)" class="tab__dirty"
                  :title="`${actions.pendingCount(t.table)} 处改动未保存`">
              ●{{ actions.pendingCount(t.table) }}
            </span>
            <span class="tab__x" @click.stop="actions.closeTab(t.id)">✕</span>
          </div>
          <button class="tabbar__add" title="新建查询 ⌘T" @click="actions.openQuery()">＋</button>
          <span class="grow"></span>
          <button class="tabbar__add" title="收起侧栏 ⌘\" @click="actions.toggleSidebar()">☰</button>
        </div>

        <!-- 启动错误 -->
        <div v-if="state.bootedError" class="empty">
          <div class="empty__icon">⚠</div>
          <div class="empty__title">无法连接 dev 后端</div>
          <div class="small mono">{{ state.bootedError }}</div>
          <div class="small muted">请先运行 <code>cargo run -p adb-web</code>（默认 127.0.0.1:8787）</div>
        </div>

        <!-- 启动中 -->
        <div v-else-if="!state.booted" class="empty">
          <div class="empty__icon">◐</div>
          <div class="empty__title">正在加载声明层与演示库…</div>
        </div>

        <!-- 无标签页 -->
        <div v-else-if="!tab" class="empty">
          <div class="empty__icon">◫</div>
          <div class="empty__title">AgenticDB</div>
          <div class="small">从左侧对象树选一张表，或按 <span class="kbd">⌘T</span> 新建查询</div>
          <div class="row" style="gap:var(--sp-2);margin-top:var(--sp-4)">
            <button class="btn" @click="actions.openTool('diagnosis', '诊断')">◆ 诊断</button>
            <button class="btn" @click="actions.openTool('capability', '能力矩阵')">▤ 能力矩阵</button>
            <button class="btn" @click="actions.openTool('mask', '数据工厂')">🎭 数据工厂</button>
            <button class="btn" @click="actions.openTool('audit', '变更管控')">📋 变更管控</button>
          </div>
        </div>

        <!-- 页面路由：⭐ 只按 tab.kind 分发，不要用 state.tool 二次判断。
             历史上两个字段描述同一件事，导致 mask/audit 两个页面
             被 diagnosis 分支抢先命中，点了跟没点一样。 -->
        <DataGridPage v-else-if="tab.kind === 'grid'" @run="runFromGrid" />
        <QueryPage v-else-if="tab.kind === 'query'" />
        <DiagnosisPage v-else-if="tab.kind === 'diagnosis'" />
        <TransferPage v-else-if="tab.kind === 'transfer'" />
        <CapabilityPage v-else-if="tab.kind === 'capability'" />
        <MaskPage v-else-if="tab.kind === 'mask'" />
        <ChangeControlPage v-else-if="tab.kind === 'audit'" />
        <StructurePage v-else />
      </div>

      <!-- AI 面板 -->
      <div v-if="state.aiOpen" class="ai-panel">
        <AiPanel />
      </div>
    </div>

    <!-- ══ 状态栏 ══ -->
    <StatusBar />

    <!-- ══ Toast ══ -->
    <div v-if="state.toast" class="toast" :class="state.toast.kind === 'error' ? 'toast--error' : ''">
      <span>{{ state.toast.kind === 'error' ? '⚠' : 'ℹ' }}</span>
      <span>{{ state.toast.text }}</span>
    </div>
  </div>
</template>

<script lang="ts">
export default {};
</script>

<style scoped>
.resizer {
  flex: 0 0 3px; cursor: col-resize; background: transparent;
  margin-left: -2px; z-index: 3;
}
.resizer:hover { background: var(--accent); }
</style>