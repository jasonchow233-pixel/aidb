<script setup lang="ts">
import { state, actions } from "@/lib/store";
import type { TreeNode } from "@/lib/api";

const props = defineProps<{ node: TreeNode; cls?: string }>();
const emit = defineEmits<{
  (e: "open", node: TreeNode): void;
  (e: "dblopen", node: TreeNode): void;
  (e: "ctx", node: TreeNode, ev: MouseEvent): void;
  (e: "toggle-star", node: TreeNode): void;
}>();

// ⭐ 按 kind 选图标 —— 不硬编码，驱动返回什么 kind 就渲染什么。
//   SQLite demo 只有 table/view，但 function/procedure/trigger/sequence
//   在有这些对象的驱动里会自动出现且各自有图标。
const ICONS: Record<string, string> = {
  table: "▦",
  view: "📊",
  function: "ƒ",
  procedure: "⚙",
  trigger: "⚡",
  sequence: "#",
  column: "·",
};
function icon(kind: string): string {
  return ICONS[kind] ?? "▦";
}
function starred(): boolean {
  return state.starred.includes(props.node.label);
}
</script>

<template>
  <button
    class="node"
    :class="[cls, { 'node--star': starred() }]"
    :title="node.comment ?? undefined"
    :aria-expanded="state.expanded.has(node.id)"
    @click="emit('open', node)"
    @dblclick="emit('dblopen', node)"
    @contextmenu.prevent="emit('ctx', node, $event)"
  >
    <span class="caret" :class="{ on: state.expanded.has(node.id) }">▶</span>
    <span class="ico">{{ icon(node.kind) }}</span>
    <span class="row grow">
      <span class="truncate">{{ node.label }}</span>
      <span v-if="!state.expanded.has(node.id)" class="comment truncate">{{ node.comment }}</span>
      <span class="meta">{{ node.meta }}</span>
      <span v-if="node.riskBadge" class="badge-warn" :title="`${node.riskBadge} 个问题`">⚠{{ node.riskBadge }}</span>
    </span>
    <!-- ⭐ 星标：click.stop 避免触发整行的 pickTable（展开+开表） -->
    <span
      v-if="starred()"
      class="star star--on"
      title="取消收藏"
      @click.stop="emit('toggle-star', node)"
    >★</span>
    <span
      v-else
      class="star star--off"
      title="收藏"
      @click.stop="emit('toggle-star', node)"
    >☆</span>
  </button>
</template>

<style scoped>
.node {
  display: flex; align-items: center; gap: 5px; width: 100%;
  height: 26px; padding: 0 var(--sp-2); text-align: left;
  font-size: var(--fs-sm); cursor: pointer; white-space: nowrap; position: relative;
  background: none; border: none; color: inherit; font-family: inherit;
}
.node:hover { background: var(--bg-hover); }
.node.sel { background: var(--bg-selected); }
.node--risk-high { border-left: 3px solid var(--risk-2-structure); }
.node--risk-medium { border-left: 3px solid var(--warning); }
.node--star { font-weight: var(--fw-medium); }
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
.star { flex: 0 0 auto; font-size: 12px; line-height: 1; padding: 0 2px; }
.star--on { color: var(--star, #f5b50a); }
.star--off { color: var(--text-tertiary); opacity: 0; }
.node:hover .star--off { opacity: 1; }
</style>
