<script setup lang="ts">
import type { Finding } from "@/lib/api";
const props = defineProps<{ f: Finding }>();

const icon = { critical: "⚠", warning: "⚠", info: "ℹ" } as Record<string, string>;
const label = { critical: "严重", warning: "警告", info: "提示" } as Record<string, string>;
</script>

<template>
  <div class="finding" :class="`finding--${props.f.level}`">
    <div class="finding__title">
      <span>{{ icon[props.f.level] }} {{ props.f.title }}</span>
      <span class="badge" :class="props.f.level === 'critical' ? 'badge--risk-2' : props.f.level === 'warning' ? 'badge--risk-1' : ''">
        {{ label[props.f.level] }}
      </span>
      <span v-if="props.f.table" class="badge">{{ props.f.table }}</span>
    </div>
    <div class="finding__impact">{{ props.f.impact }}</div>
    <div class="finding__action">
      <span class="muted small">建议：</span>
      <span class="mono small">{{ props.f.action }}</span>
    </div>
  </div>
</template>