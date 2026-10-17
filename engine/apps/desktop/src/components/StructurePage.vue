<script setup lang="ts">
import { computed, ref, watch, toRaw } from "vue";
import { state, actions, notify } from "@/lib/store";
import { api, capabilities, compact, type TableDef } from "@/lib/api";

const t = ref<"columns" | "indexes" | "ddl" | "diag">("columns");
const editMode = ref(false);
const rows = ref<TableDef["columns"]>([]);

const tbl = computed(() => state.tableDef);
const findings = computed(() => state.findings.filter((f) => f.table === tbl.value?.name));

// ⭐ 由 traits 推导的警告，不是硬编码 if (mysql)
const warnings = computed(() =>
  capabilities.warnings(state.connectionKind).filter((w) => w.whereToShow === "structureEditor"),
);

/**
 * ⭐ 编辑缓冲：从 `state.tableDef` 深拷贝出可编辑副本。
 *
 * ⭐ **不能用 `structuredClone`**：`state` 是 `reactive()` 包过的，
 *    `state.tableDef.columns` 是 Proxy 对象，而 `structuredClone`
 *    只接受可结构化克隆的原始值，遇到 Proxy 会抛
 *    `DataCloneError: [object Object] could not be cloned`。
 *    抛错发生在 watcher 里，整个结构页直接渲染不出来 ——
 *    而 `vite build` 与所有单测都发现不了。
 *
 *    `toRaw()` 剥掉 Proxy 后仍是 Vue 的深层 reactive，
 *    所以再走一次 `JSON.parse(JSON.stringify())` 拿到纯对象。
 */
function cloneColumns(cols: TableDef["columns"]): TableDef["columns"] {
  return JSON.parse(JSON.stringify(toRaw(cols)));
}

watch(
  tbl,
  (v) => {
    rows.value = v ? cloneColumns(v.columns) : [];
  },
  { immediate: true },
);

const ddl = computed(() => {
  if (!tbl.value) return "";
  const c = tbl.value.columns;
  const L: string[] = [];
  L.push(`CREATE TABLE "${tbl.value.name}" (`);
  c.forEach((col, i) => {
    const parts = [`  "${col.name}" ${col.ty || "TEXT"}`];
    if (col.isPk) parts.push("PRIMARY KEY");
    if (!col.nullable && !col.isPk) parts.push("NOT NULL");
    if (col.defaultExpr) parts.push(`DEFAULT ${col.defaultExpr}`);
    L.push(parts.join(" ") + (i < c.length - 1 ? "," : ""));
  });
  L.push(");");
  for (const idx of tbl.value.indexes) {
    if (idx.primary) continue;
    L.push(`${idx.unique ? "CREATE UNIQUE INDEX" : "CREATE INDEX"} "${idx.name}" ON "${tbl.value.name}" (${idx.columns.map((x) => `"${x}"`).join(", ")});`);
  }
  return L.join("\n");
});

function save() {
  notify("info", `结构保存已提交（demo 模式不落盘）：${rows.value.length} 列`);
  editMode.value = false;
}
</script>

<template>
  <div class="page" v-if="tbl">
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">⛭</span>
        <span class="page-head__name">{{ tbl.name }}</span>
        <span class="page-head__comment">{{ tbl.comment }}</span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="t = 'ddl'">查看 DDL</button>
        <button class="btn btn--sm" v-if="capabilities.can(state.connectionKind, 'tableStructureEdit')"
                @click="editMode = !editMode">{{ editMode ? "取消编辑" : "编辑结构" }}</button>
        <button class="btn btn--sm btn--primary" v-if="editMode" @click="save">保存</button>
      </div>
      <div class="page-head__stats">
        {{ tbl.columns.length }} 列 · {{ tbl.indexes.length }} 索引 ·
        {{ compact(tbl.rowEstimate) }} 行
      </div>
    </div>

    <!-- ⭐ traits 驱动警告条 -->
    <div v-for="w in warnings" :key="w.code" class="banner" :class="`banner--${w.level}`">
      <span>{{ w.level === "important" ? "⚠" : "ℹ" }}</span>
      <span>{{ w.message }}</span>
      <span class="grow"></span>
      <span class="small mono muted">{{ w.code }}</span>
    </div>

    <div class="tabs">
      <div v-for="x in ([['columns','列'],['indexes','索引'],['ddl','DDL'],['diag','诊断']] as const)"
           :key="x[0]" class="tabs__item" :class="{ 'tabs__item--active': t === x[0] }"
           @click="t = x[0]">
        {{ x[1] }}
        <span v-if="x[0] === 'diag' && findings.length" class="badge badge--warn">{{ findings.length }}</span>
      </div>
    </div>

    <div class="scroll pad" v-if="t === 'columns'">
      <table class="grid" style="min-width:100%">
        <thead><tr>
          <th style="min-width:150px">列名</th>
          <th style="min-width:110px">类型</th>
          <th style="min-width:60px">可空</th>
          <th style="min-width:110px">默认值</th>
          <th style="min-width:70px">主键</th>
          <th style="min-width:70px">外键</th>
          <th>注释</th>
          <th style="min-width:170px">枚举取值</th>
        </tr></thead>
        <tbody>
          <tr v-for="c in (editMode ? rows : tbl.columns)" :key="c.id" :style="{ height: 'var(--row-comfort)' }">
            <td class="mono"><span v-if="c.isPk" style="color:var(--warning)">🔑</span> {{ c.name }}</td>
            <td class="mono muted">
              <input v-if="editMode" class="input input--mono" style="height:22px;font-size:var(--fs-xs)"
                     v-model="(c as any).ty" />
              <template v-else>{{ c.ty }}</template>
            </td>
            <td><span :style="{ color: c.nullable ? 'var(--text-tertiary)' : 'var(--warning)' }">
              {{ c.nullable ? "YES" : "NO" }}</span></td>
            <td class="mono muted small">{{ c.defaultExpr ?? "—" }}</td>
            <td>{{ c.isPk ? "✓" : "" }}</td>
            <td>{{ c.isFk ? "🔗" : "" }}</td>
            <td class="muted small">
              <input v-if="editMode" class="input" style="height:22px;font-size:var(--fs-xs)"
                     v-model="(c as any).comment" />
              <template v-else>{{ c.comment ?? "—" }}</template>
            </td>
            <td class="small">
              <span v-for="(v, k) in (c.enumValues ?? {})" :key="k" class="badge" style="margin-right:4px">
                {{ k }}={{ v }}
              </span>
              <span v-if="!c.enumValues" class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="scroll pad" v-else-if="t === 'indexes'">
      <table class="grid" style="min-width:100%">
        <thead><tr>
          <th style="min-width:200px">索引名</th><th style="min-width:80px">类型</th><th>列</th>
        </tr></thead>
        <tbody>
          <tr v-for="i in tbl.indexes" :key="i.id" :style="{ height: 'var(--row-normal)' }">
            <td class="mono">{{ i.name }}</td>
            <td>
              <span class="badge" :class="i.primary ? 'badge--warn' : i.unique ? 'badge--ok' : ''">
                {{ i.primary ? "PRIMARY" : i.unique ? "UNIQUE" : "INDEX" }}
              </span>
            </td>
            <td class="mono muted">{{ i.columns.join(", ") }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="scroll" v-else-if="t === 'ddl'">
      <div class="row" style="padding: var(--sp-2) var(--sp-3)">
        <span class="muted small">由结构反向生成 · 未包含厂商特有语法</span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="navigator.clipboard.writeText(ddl)">复制</button>
      </div>
      <pre class="ddl mono small">{{ ddl }}</pre>
    </div>

    <div class="scroll pad" v-else>
      <div v-if="!findings.length" class="empty">
        <div class="empty__icon">✓</div>
        <div class="empty__title">这张表没有发现问题</div>
      </div>
      <FindingCard v-for="(f, i) in findings" :key="i" :f="f" />
    </div>
  </div>

  <div class="empty" v-else>
    <div class="empty__icon">⛭</div>
    <div class="empty__title">未选择表</div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from "vue";
import FindingCard from "./FindingCard.vue";
export default defineComponent({ components: { FindingCard } });
</script>

<style scoped>
.ico { color: var(--text-tertiary); }
.ddl { margin: 0 var(--sp-3) var(--sp-3); padding: var(--sp-3); background: var(--bg-inset);
  border: var(--bw) solid var(--border); border-radius: var(--r-md); overflow: auto; }
</style>