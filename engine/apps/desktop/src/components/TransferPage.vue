<script setup lang="ts">
import { computed, ref } from "vue";
import { state, notify } from "@/lib/store";
import { api, capabilities, type TypeMapItem } from "@/lib/api";

const from = ref("dameng");
const to = ref("postgresql");
const result = ref<{ fromFamily?: string; toFamily?: string; mappings: TypeMapItem[] } | null>(null);
const loading = ref(false);
const acknowledged = ref<Set<string>>(new Set());

const families = capabilities.current?.families ?? [];
const conns = capabilities.current?.connections ?? [];

const fromFamily = computed(() => families.find((f) => f.members.includes(from.value)));
const toFamily = computed(() => families.find((f) => f.members.includes(to.value)));
const sameFamily = computed(() => fromFamily.value && fromFamily.value.id === toFamily.value.id);

/** ⭐ 类型来源：从演示库快照取真实类型，不硬编码 */
const sourceTypes = computed(() => {
  const set = new Set<string>();
  for (const t of state.tableDef ? [state.tableDef] : []) for (const c of t.columns) set.add(c.ty);
  if (!set.size) {
    // 无选中表时用家族声明里的类型全集
    const m = fromFamily.value?.typeMapping ?? {};
    return Object.keys(m);
  }
  return [...set];
});

const lossy = computed(() => result.value?.mappings.filter((m) => m.lossy && !m.mapped) ?? []);
const unmapped = computed(() => result.value?.mappings.filter((m) => !m.mapped) ?? []);
const unacked = computed(() => lossy.value.filter((m) => !acknowledged.value.has(m.source)));

async function run() {
  loading.value = true;
  try {
    result.value = await api.typeMap(from.value, to.value, sourceTypes.value);
    acknowledged.value = new Set();
  } catch (e) {
    notify("error", e instanceof Error ? e.message : String(e));
  } finally {
    loading.value = false;
  }
}

function ack(m: TypeMapItem) {
  const s = new Set(acknowledged.value);
  s.add(m.source);
  acknowledged.value = s;
}

function start() {
  if (unacked.value.length) {
    notify("error", `还有 ${unacked.value.length} 个有损映射未确认`);
    return;
  }
  notify("info", "数据传输已排队（演示模式不真正执行）");
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">⇄</span>
        <span class="page-head__name">数据传输</span>
        <span class="grow"></span>
        <button class="btn btn--sm btn--primary" :disabled="loading" @click="run">生成迁移计划</button>
        <button class="btn btn--sm btn--danger" :disabled="!result" @click="start">开始传输</button>
      </div>
    </div>

    <div class="pad row" style="gap: var(--sp-3); flex-wrap: wrap">
      <div class="col grow" style="min-width: 240px">
        <label class="small muted">源</label>
        <select class="input" v-model="from">
          <option v-for="c in conns" :key="c.id" :value="c.id">
            {{ c.label }} — {{ families.find(f => f.members.includes(c.id))?.label }}
          </option>
        </select>
      </div>
      <div class="col" style="width: 32px"><span class="ico" style="margin-top:22px">→</span></div>
      <div class="col grow" style="min-width: 240px">
        <label class="small muted">目标</label>
        <select class="input" v-model="to">
          <option v-for="c in conns" :key="c.id" :value="c.id">
            {{ c.label }} — {{ families.find(f => f.members.includes(c.id))?.label }}
          </option>
        </select>
      </div>
      <div class="col" style="min-width: 180px">
        <label class="small muted">对象范围</label>
        <select class="input">
          <option>表结构 + 数据 + 索引</option>
          <option>仅表结构</option>
          <option>仅数据</option>
        </select>
      </div>
    </div>

    <!-- ⭐ 同家族 vs 跨家族 -->
    <div class="pad" style="padding-top:0">
      <div v-if="sameFamily" class="banner banner--info">
        <span>ℹ</span>
        <span>
          源与目标同属 <strong>{{ fromFamily?.label }}</strong>，
          语法高度兼容，转换规则由家族声明统一提供。
        </span>
      </div>
      <div v-else-if="fromFamily && toFamily" class="banner banner--important">
        <span>⚠</span>
        <span>
          跨家族迁移：<strong>{{ fromFamily.label }}</strong> → <strong>{{ toFamily.label }}</strong>。
          {{ fromFamily.supportsTransactionalDdl ? "" : "源引擎 DDL 会隐式提交。" }}
          目标身份关键字为 <code>{{ toFamily.identityKeyword }}</code>，标识符引用符为 <code>{{ toFamily.quoteIdent }}</code>。
        </span>
      </div>
    </div>

    <div class="scroll pad" v-if="result">
      <div class="row small muted" style="margin-bottom: var(--sp-2)">
        <span>{{ result.mappings.length }} 个类型 ·</span>
        <span style="color: var(--warning)">{{ lossy.length }} 个有损</span>
        <span style="color: var(--risk-2-structure)">{{ lossy.filter(m => acknowledged.has(m.source)).length }} 已确认</span>
        <span v-if="unmapped.length" style="color: var(--risk-2-structure)">{{ unmapped.length }} 个无映射</span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="acknowledged = new Set()">全部取消确认</button>
      </div>

      <table class="grid" style="min-width:100%">
        <thead><tr>
          <th style="min-width:130px">源类型</th>
          <th style="min-width:170px">目标类型</th>
          <th style="min-width:90px">精度</th>
          <th style="min-width:110px">确认</th>
          <th>说明</th>
        </tr></thead>
        <tbody>
          <tr v-for="m in result.mappings" :key="m.source" :style="{ height: 'var(--row-normal)' }">
            <td class="mono">{{ m.source }}</td>
            <td class="mono" :style="{ color: m.mapped ? 'var(--text-primary)' : 'var(--risk-2-structure)' }">
              {{ m.target ?? "无映射" }}
            </td>
            <td>
              <span v-if="!m.mapped" class="badge badge--risk-2">不兼容</span>
              <span v-else-if="m.lossy" class="badge badge--risk-1">有损</span>
              <span v-else class="badge badge--ok">无损</span>
            </td>
            <td>
              <label v-if="m.lossy && m.mapped" class="small row" style="cursor:pointer">
                <input type="checkbox" :checked="acknowledged.has(m.source)" @change="ack(m)" />
                已确认
              </label>
              <span v-else class="muted small">—</span>
            </td>
            <td class="small muted">{{ m.reason ?? "精确映射" }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="empty" v-else>
      <div class="empty__icon">⇄</div>
      <div class="empty__title">选择源与目标后生成迁移计划</div>
      <div class="small">类型映射由 <code>declarations/transfer-families/*.yaml</code> 声明驱动</div>
    </div>
  </div>
</template>

<style scoped>
.ico { color: var(--text-tertiary); }
</style>