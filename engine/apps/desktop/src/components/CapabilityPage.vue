<script setup lang="ts">
import { computed, ref } from "vue";
import { state } from "@/lib/store";
import { capabilities, type Capabilities, type Traits } from "@/lib/api";

const view = ref<"cap" | "traits" | "families">("cap");
const current = computed(() => state.descriptor);

/** ⭐ 所有 15 项能力都渲染，包括 false —— 「缺什么」和「有什么」同样重要 */
const CAP_KEYS: [keyof Capabilities, string][] = [
  ["queryExecution", "执行查询"],
  ["metadataBrowse", "元数据浏览"],
  ["objectBrowser", "对象浏览器（过程/函数）"],
  ["objectSource", "查看对象源码"],
  ["schemaSearch", "Schema 搜索"],
  ["diagram", "ER 图"],
  ["tableDataEdit", "编辑表数据"],
  ["tableStructureEdit", "编辑表结构"],
  ["tableImport", "数据导入"],
  ["dataTransfer", "数据传输"],
  ["sqlFileExecution", "执行 SQL 文件"],
  ["databaseCreate", "创建数据库"],
  ["fieldLineage", "字段血缘"],
  ["sqlExplain", "执行计划"],
  ["userAdmin", "用户与权限管理"],
];

const TRAIT_ROWS: [keyof Traits, string, string][] = [
  ["schemaAware", "有 schema 层级", "决定对象树要不要 schema 层"],
  ["singleDatabase", "单库", "决定连接串里的 database 参数"],
  ["clearableQuerySchema", "查询时可清空 schema", "决定查询编辑器是否有 schema 选择器"],
  ["transactionalDdl", "DDL 在事务内", "false → UI 必须提示「DDL 会隐式提交」"],
  ["onlineDdlConcurrent", "支持在线 DDL", "决定结构编辑器是否给「不停机」选项"],
  ["multipleResultsets", "多结果集", "存储过程场景"],
  ["savepoints", "支持 SAVEPOINT", "事务面板的嵌套回滚点"],
  ["sequenceObjects", "有独立序列对象", "决定侧栏是否有序列分组"],
];

const buildReport = computed(() => capabilities.current?.build_report ?? "");
const registered = computed(() => capabilities.current?.registered ?? []);

function chips(d: { capabilities: Capabilities }) {
  return CAP_KEYS.filter(([k]) => d.capabilities[k]);
}
function offChips(d: { capabilities: Capabilities }) {
  return CAP_KEYS.filter(([k]) => !d.capabilities[k]);
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">▤</span>
        <span class="page-head__name">能力矩阵</span>
        <span class="page-head__comment">
          声明于 <code>declarations/connections/*.yaml</code>，编译期校验
        </span>
        <span class="grow"></span>
        <span class="badge">{{ registered.length }} 个驱动编入当前构建</span>
      </div>
      <div class="tabs" style="border:none;padding:0">
        <div v-for="x in ([['cap','功能能力'],['traits','引擎特性'],['families','传输家族']] as const)"
             :key="x[0]" class="tabs__item" :class="{ 'tabs__item--active': view === x[0] }"
             @click="view = x[0]">{{ x[1] }}</div>
      </div>
    </div>

    <div class="scroll pad">
      <!-- ══ 功能能力 ══ -->
      <template v-if="view === 'cap'">
        <table class="grid" style="min-width:100%">
          <thead><tr>
            <th style="min-width:170px">连接类型</th>
            <th style="min-width:90px">运行时</th>
            <th style="min-width:90px">支持度</th>
            <th style="min-width:90px">构建</th>
            <th>开启的能力</th>
            <th style="min-width:200px">关闭的能力</th>
          </tr></thead>
          <tbody>
            <tr v-for="d in capabilities.current?.connections ?? []" :key="d.id"
                :style="{ height: 'var(--row-comfort)', background: d.id === state.connectionKind ? 'var(--bg-selected)' : undefined }">
              <td>
                <span class="mono">{{ d.label }}</span>
                <span v-if="d.id === state.connectionKind" class="badge badge--ok">当前</span>
              </td>
              <td>
                <span class="badge" :class="d.runtimeMode === 'jdbc' ? 'badge--warn' : ''">{{ d.runtimeMode }}</span>
              </td>
              <td><span class="badge">{{ d.supportLevel }}</span></td>
              <td>
                <span class="badge" :class="registered.includes(d.id) ? 'badge--ok' : 'badge--risk-2'">
                  {{ registered.includes(d.id) ? "已编入" : "未编入" }}
                </span>
              </td>
              <td>
                <div class="caps-chips">
                  <span v-for="[k, label] in chips(d)" :key="k" class="badge badge--ok" :title="label">
                    {{ label }}
                  </span>
                </div>
              </td>
              <td>
                <div class="caps-chips">
                  <span v-for="[k, label] in offChips(d)" :key="k" class="badge"
                        style="opacity:.5;text-decoration:line-through" :title="`${label}（未开启）`">
                    {{ label }}
                  </span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- ══ 引擎特性（traits）══ -->
      <template v-else-if="view === 'traits' && current">
        <div class="banner banner--info">
          <span>ℹ</span>
          <span>
            <code>traits</code> 是引擎的物理特性，<code>capabilities</code> 是产品功能。
            UI 组件只读 <code>traits</code> 推导提示，绝不写 <code>if (conn === "mysql")</code>。
          </span>
        </div>
        <table class="grid" style="min-width:100%;margin-top:var(--sp-2)">
          <thead><tr>
            <th style="min-width:180px">特性</th>
            <th style="min-width:230px">当前连接（{{ current.label }}）</th>
            <th style="min-width:230px">PostgreSQL</th>
            <th style="min-width:230px">MySQL</th>
            <th>UI 影响</th>
          </tr></thead>
          <tbody>
            <tr v-for="[k, label, effect] in TRAIT_ROWS" :key="k" :style="{ height: 'var(--row-normal)' }">
              <td>{{ label }}<div class="small muted mono">{{ k }}</div></td>
              <td v-for="d in capabilities.current?.connections.filter(x => ['sqlite','postgresql','mysql'].includes(x.id)) ?? []"
                  :key="d.id">
                <span class="badge" :class="d.traits[k] ? 'badge--ok' : 'badge--risk-2'">
                  {{ d.traits[k] ? "是" : "否" }}
                </span>
                <span v-if="k === 'identityKind'" class="badge">{{ (d.traits as any)[k] }}</span>
              </td>
              <td class="small muted">{{ effect }}</td>
            </tr>
            <tr :style="{ height: 'var(--row-normal)' }">
              <td>标识符引用符<div class="small muted mono">identifierQuote</div></td>
              <td v-for="d in capabilities.current?.connections.filter(x => ['sqlite','postgresql','mysql'].includes(x.id)) ?? []"
                  :key="d.id" class="mono">{{ d.traits.identifierQuote }}</td>
              <td class="small muted">DDL 反向生成时的引用符</td>
            </tr>
            <tr :style="{ height: 'var(--row-normal)' }">
              <td>参数风格<div class="small muted mono">paramStyle</div></td>
              <td v-for="d in capabilities.current?.connections.filter(x => ['sqlite','postgresql','mysql'].includes(x.id)) ?? []"
                  :key="d.id" class="mono">{{ d.traits.paramStyle }}</td>
              <td class="small muted">绑定参数用 <code>$1</code> 还是 <code>:name</code></td>
            </tr>
            <tr :style="{ height: 'var(--row-normal)' }">
              <td>EXPLAIN 格式<div class="small muted mono">explainFormats</div></td>
              <td v-for="d in capabilities.current?.connections.filter(x => ['sqlite','postgresql','mysql'].includes(x.id)) ?? []"
                  :key="d.id"><span v-for="f in d.traits.explainFormats" :key="f" class="badge">{{ f }}</span></td>
              <td class="small muted">执行计划面板可切换的渲染器</td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- ══ 传输家族 ══ -->
      <template v-else>
        <div class="banner banner--info">
          <span>ℹ</span>
          <span>
            22 个数据库只需维护 <strong>{{ families.length }}</strong> 套传输规则。
            达梦 / 金仓O / OceanBase-O / GBase8s / 高斯 共用 Oracle 家族。
          </span>
        </div>
        <table class="grid" style="min-width:100%;margin-top:var(--sp-2)">
          <thead><tr>
            <th style="min-width:170px">家族</th>
            <th style="min-width:80px">事务 DDL</th>
            <th style="min-width:130px">身份关键字</th>
            <th style="min-width:70px">引用符</th>
            <th style="min-width:70px">类型规则</th>
            <th>成员</th>
          </tr></thead>
          <tbody>
            <tr v-for="f in families" :key="f.id" :style="{ height: 'var(--row-comfort)' }">
              <td><span class="mono">{{ f.label }}</span><div class="small muted mono">{{ f.id }}</div></td>
              <td>
                <span class="badge" :class="f.supportsTransactionalDdl ? 'badge--ok' : 'badge--risk-1'">
                  {{ f.supportsTransactionalDdl ? "是" : "否" }}
                </span>
              </td>
              <td class="mono small">{{ f.identityKeyword }}</td>
              <td class="mono">{{ f.quoteIdent }}</td>
              <td class="mono">{{ Object.keys(f.typeMapping).length }}</td>
              <td>
                <span v-for="m in f.members" :key="m" class="badge">{{ m }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </div>

    <!-- 编译期打印的构建报告 -->
    <div class="cell-detail" style="max-height:34%">
      <div class="cell-detail__head">
        <strong>build.rs 构建报告</strong>
        <span class="muted small">编译时由声明层生成 · 防止「忘了跑 codegen」</span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="navigator.clipboard.writeText(buildReport)">复制</button>
      </div>
      <div class="cell-detail__body mono small">{{ buildReport }}</div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from "vue";
export default defineComponent({
  computed: {
    families() { return capabilities.current?.families ?? []; },
  },
});
</script>

<style scoped>
.ico { color: var(--text-tertiary); }
</style>