<script setup lang="ts">
import { computed, ref } from "vue";
import { state, actions } from "@/lib/store";
import type { Finding } from "@/lib/api";
import FindingCard from "./FindingCard.vue";

const level = ref<"all" | "critical" | "warning" | "info">("all");
const scope = ref<"all" | "sensitive" | "pk" | "fk" | "enum" | "comment">("all");

// ⭐ 「缺什么」比「有什么」更能被用户感知 —— 这是 DBX 诊断的差异化
const byKind = (f: Finding) => {
  const t = f.title;
  if (t.includes("没有主键")) return "pk";
  if (t.includes("无索引")) return "fk";
  if (t.includes("枚举")) return "enum";
  if (t.includes("敏感")) return "sensitive";
  if (t.includes("注释")) return "comment";
  return "other";
};

const filtered = computed(() =>
  state.findings.filter((f) => {
    if (level.value !== "all" && f.level !== level.value) return false;
    if (scope.value !== "all" && byKind(f) !== scope.value) return false;
    return true;
  }),
);

const counts = computed(() => ({
  critical: state.findings.filter((f) => f.level === "critical").length,
  warning: state.findings.filter((f) => f.level === "warning").length,
  info: state.findings.filter((f) => f.level === "info").length,
  all: state.findings.length,
}));

const scopes = [
  { k: "all", label: "全部" },
  { k: "pk", label: "缺主键" },
  { k: "fk", label: "外键无索引" },
  { k: "enum", label: "枚举未标注" },
  { k: "sensitive", label: "敏感字段" },
  { k: "comment", label: "缺注释" },
] as const;

const tables = computed(() => {
  const m = new Map<string, number>();
  for (const f of state.findings) m.set(f.table ?? "—", (m.get(f.table ?? "—") ?? 0) + 1);
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
});
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">◆</span>
        <span class="page-head__name">诊断</span>
        <span class="page-head__stats">
          {{ counts.all }} 个问题 · {{ counts.critical }} 严重 · {{ counts.warning }} 警告
        </span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="actions.boot()">重新扫描</button>
      </div>
    </div>

    <div class="toolbar pad" style="padding-bottom:0">
      <button v-for="s in scopes" :key="s.k" class="btn btn--sm"
              :class="{ 'btn--primary': scope === s.k }"
              @click="scope = s.k as any">{{ s.label }}</button>
      <div class="toolbar__sep"></div>
      <button v-for="l in (['all','critical','warning','info'] as const)" :key="l" class="btn btn--sm"
              :class="{ 'btn--primary': level === l }"
              @click="level = l">
        {{ l === 'all' ? '不限' : l === 'critical' ? '严重' : l === 'warning' ? '警告' : '提示' }}
      </button>
    </div>

    <div class="scroll pad">
      <div v-if="!filtered.length" class="empty">
        <div class="empty__icon">✓</div>
        <div class="empty__title">没有匹配的问题</div>
      </div>
      <FindingCard v-for="(f, i) in filtered" :key="i" :f="f" />
    </div>

    <div class="grid-foot">
      <span class="muted">问题最集中的表：</span>
      <span v-for="[t, n] in tables.slice(0, 5)" :key="t" class="badge">{{ t }} × {{ n }}</span>
    </div>
  </div>
</template>

<style scoped>
.ico { color: var(--warning); }
</style>