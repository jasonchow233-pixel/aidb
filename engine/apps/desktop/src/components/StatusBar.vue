<script setup lang="ts">
import { computed } from "vue";
import { state, actions } from "@/lib/store";
import { capabilities, compact } from "@/lib/api";

const d = computed(() => state.descriptor);
</script>

<template>
  <div class="statusbar">
    <!-- 环境 -->
    <span class="badge badge--prod" title="演示环境固定为生产标识：这是我们要防的第一类事故">
      ● PRODUCTION
    </span>
    <span class="sep-v"></span>

    <!-- 连接 -->
    <span class="mono">{{ d?.label ?? "未连接" }}</span>
    <span class="muted truncate" style="max-width:200px">{{ state.dbFile }}</span>
    <span class="muted">· {{ compact(state.tableDef?.rowEstimate ?? null) }} 行</span>

    <span class="grow"></span>

    <!-- 危险分级提示（⭐ 声明驱动） -->
    <span v-if="capabilities.warnsImplicitDdlCommit(state.connectionKind)" class="badge badge--risk-1"
          title="该引擎的 DDL 会隐式提交，无法通过事务回滚">
      ⚠ DDL 隐式提交
    </span>
    <span v-if="!capabilities.showsSequenceWorkspace(state.connectionKind)" class="badge" title="无独立序列对象">
      无序列
    </span>

    <!-- 会话状态 -->
    <span class="sep-v"></span>
    <span class="muted">诊断 <strong :style="{ color: state.findings.length ? 'var(--warning)' : undefined }">{{ state.findings.length }}</strong></span>
    <span class="muted">标签 {{ state.tabs.length }}</span>

    <span class="sep-v"></span>
    <button class="btn btn--sm btn--ghost" :title="state.theme === 'dark' ? '切到亮色' : '切到暗色'"
            @click="actions.toggleTheme()">{{ state.theme === "dark" ? "◑" : "◐" }}</button>
    <button class="btn btn--sm btn--ghost" title="切换 AI 面板 (⌘K)" @click="actions.toggleAi()">AI</button>
  </div>
</template>

<style scoped>
.sep-v { width: 1px; height: 12px; background: var(--border); }
</style>