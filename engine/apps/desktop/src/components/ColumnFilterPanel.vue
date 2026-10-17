<script setup lang="ts">
/**
 * ⭐⭐ 列头筛选面板 —— Navicat 的灵魂功能。
 *
 * 点列头图标滑出：列出该列的**去重值 + 命中行数**，勾选即筛选。
 *
 * ⭐ 三个必须做对的地方（做错了用户会被误导而不是被帮助）：
 *
 * 1. **级联候选**：已选 A 列条件后，B 列的计数要带上 A 的条件。
 *    否则用户看到「状态=已支付 下有 5 种收货地址」，
 *    实际点进去是 0 行。
 *
 * 2. **count=0 的选项禁用但显示**。隐藏会让用户以为这些值不存在。
 *
 * 3. **NULL 作为独立一项**，与空串分开显示。
 *    合成「NULL」一个选项，用户永远分不清自己筛的是哪个。
 */
import { computed, ref, watch } from "vue";
import { state, actions, notify, sqlLiteral, quoteIdent, type FilterOp } from "@/lib/store";
import { api } from "@/lib/api";

const props = defineProps<{
  column: string;
  /** 该列在 snapshot 里的元数据（用于类型判断与枚举提示） */
  category?: string;
  enumValues?: Record<string, string> | null;
}>();
const emit = defineEmits<{ close: []; applied: [] }>();

/** 候选值项 */
interface Candidate {
  value: unknown;
  /** ⭐ NULL / 空串 的显示文案，必须一眼可区分 */
  label: string;
  count: number;
  isNull: boolean;
  /** ⭐ 稳定标识：用于 picked 集合的键 */
  key: string;
  selected: boolean;
  /** count = 0 → 禁用但显示 */
  disabled: boolean;
}

/** ⭐ 候选值的稳定键。NULL 与空串必须不同，否则 Set 里会互相覆盖。 */
function keyOf(v: unknown, isNull: boolean): string {
  if (isNull) return "\u0000NULL";
  if (v === "") return "\u0000EMPTY";
  return String(v);
}

const loading = ref(false);
const items = ref<Candidate[]>([]);
/**
 * ⭐ 勾选状态**独立于** items 保存。
 *
 * 之前 `selected` 直接存在 item 上，而 `load()` 每次都会用
 * `state.filters` 重算一遍 —— 于是点一下勾选、立刻重载、
 * 勾选立刻被"没点过"的状态覆盖回去。用户看到的就是
 * 「勾不上」。
 */
const picked = ref<Set<string>>(new Set());
const search = ref("");
const distinctCount = ref<number | null>(null);
const truncated = ref(false);
const manualValue = ref("");
const showManual = ref(false);

/** ⭐ 高基数阈值：超过就不列值了（列出来也没法用） */
const HIGH_CARDINALITY = 1000;
/** ⭐ 最多显示多少个候选 */
const MAX_ITEMS = 200;

/** 该列已选中的筛选值（可能是多个） */
const selectedValues = computed(() => {
  const f = state.filters.find((x) => x.col === props.column);
  if (!f) return new Set<unknown>();
  if (Array.isArray(f.value)) return new Set(f.value);
  return new Set([f.value]);
});

/** 其它列已生效的条件（级联时带上，本列自己的除外） */
const otherFilters = computed(() =>
  state.filters.filter((f) => f.col !== props.column),
);

/** 枚举列优先用声明的枚举含义做标签 */
function labelFor(v: unknown, isNull: boolean): string {
  if (isNull) return "NULL";
  if (v === "") return "（空串）"; // ⭐ 与 NULL 明确区分
  const raw = String(v);
  if (props.enumValues && props.enumValues[raw]) return `${raw} = ${props.enumValues[raw]}`;
  return raw.length > 40 ? raw.slice(0, 40) + "…" : raw;
}

async function load() {
  loading.value = true;
  try {
    const table = state.tableDef?.name;
    if (!table) return;

    // ⭐ 级联：带上其它列已生效的条件
    const where = otherFilters.value
      .map((f) => `${quoteIdent(f.col)} ${f.op} ${sqlLiteral(f.value)}`)
      .join(" AND ");

    // 先探基数，决定要不要列值
    const probeSql =
      `SELECT COUNT(DISTINCT ${quoteIdent(props.column)}) AS d FROM ${quoteIdent(table)}` +
      (where ? ` WHERE ${where}` : "");
    const probe = await api.query(probeSql, 1);
    const d = Number(probe.page.rows[0]?.d ?? 0);
    distinctCount.value = d;

    if (d === 0) {
      items.value = [];
      return;
    }
    if (d > HIGH_CARDINALITY) {
      // ⭐ 高基数：列值出来也用不了，改提示用 WHERE
      truncated.value = true;
      items.value = [];
      return;
    }

    const listSql =
      `SELECT ${quoteIdent(props.column)} AS v, COUNT(*) AS n FROM ${quoteIdent(table)}` +
      (where ? ` WHERE ${where}` : "") +
      ` GROUP BY ${quoteIdent(props.column)} ORDER BY n DESC LIMIT ${MAX_ITEMS + 1}`;
    const res = await api.query(listSql, MAX_ITEMS + 1);

    const rows = res.page.rows ?? [];
    const more = rows.length > MAX_ITEMS;
    truncated.value = more;

    const seen = new Set<string>();
    const out: Candidate[] = [];
    for (const r of rows.slice(0, MAX_ITEMS)) {
      const v = r.v;
      const isNull = v === null;
      // ⭐ NULL 与空串分开显示（#10505）
      const label = labelFor(v, isNull);
      if (seen.has(label)) continue;
      seen.add(label);
      const n = Number(r.n ?? 0);
      const key = keyOf(v, isNull);
      out.push({
        value: v,
        label,
        count: n,
        isNull,
        key,
        // ⭐ 勾选状态来自 picked（不受重载影响）
        selected: picked.value.has(key),
        // ⭐ 已勾选的项即使 count=0 也要能取消，所以不禁用它
        disabled: n === 0 && !picked.value.has(key),
      });
    }
    items.value = out;
  } catch (e) {
    notify("error", e instanceof Error ? e.message : String(e));
    items.value = [];
  } finally {
    loading.value = false;
  }
}

const visible = computed(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return items.value;
  return items.value.filter((i) => i.label.toLowerCase().includes(q));
});

function toggle(it: Candidate) {
  if (it.disabled) return;
  const s = new Set(picked.value);
  if (s.has(it.key)) s.delete(it.key);
  else s.add(it.key);
  picked.value = s;
  // ⭐ 同步到 items，让复选框立刻反映
  it.selected = s.has(it.key);
  // ⭐ 重载拿新的级联计数；但 picked 不受影响，勾选不会丢
  void load();
}

function selectAll() {
  picked.value = new Set(items.value.filter((i) => !i.disabled).map((i) => i.key));
  void load();
}
function invert() {
  const s = new Set(picked.value);
  for (const i of items.value) {
    if (i.disabled) continue;
    if (s.has(i.key)) s.delete(i.key);
    else s.add(i.key);
  }
  picked.value = s;
  void load();
}
function clearAll() {
  picked.value = new Set();
  void load();
}

/** ⭐ 手动输入精确值（高基数字段的唯一出路） */
function applyManual() {
  const raw = manualValue.value;
  if (raw === "") return;
  actions.setFilter(props.column, "=", raw);
  emit("applied");
}

/** 应用勾选：多值 → 用 IN */
function apply() {
  // ⭐ 从 picked 取，而不是从 items[i].selected 取 ——
  //    items 可能因为高基数截断而不含已勾选的项。
  const sel = items.value.filter((i) => picked.value.has(i.key));
  const others = state.filters.filter((f) => f.col !== props.column);

  state.filters = others;
  if (sel.length === 0) {
    // 一个都没选 = 不筛选
  } else if (sel.length === 1) {
    state.filters.push({ col: props.column, op: "=", value: sel[0].value });
  } else {
    state.filters.push({
      col: props.column,
      op: "IN",
      value: sel.map((s) => s.value),
    });
  }
  state.offset = 0;
  state.totalStale = true;
  const t = state.tableDef?.name;
  if (t) void actions.loadTable(t);
  emit("applied");
}

/** ⭐ 面板打开时，从已生效的条件恢复勾选 */
watch(
  () => props.column,
  () => {
    const sel = selectedValues.value;
    picked.value = new Set([...sel].map((v) => keyOf(v, v === null)));
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <div class="fpanel">
    <div class="fpanel__head">
      <span class="mono">{{ column }}</span>
      <span class="grow"></span>
      <button class="btn btn--sm btn--ghost" title="关闭" @click="emit('close')">✕</button>
    </div>

    <div v-if="loading" class="fpanel__body fpanel__body--center muted small">加载候选值…</div>

    <div v-else-if="truncated" class="fpanel__body">
      <div class="banner banner--important small">
        <span>⚠</span>
        <span>
          该列有 <strong>{{ distinctCount?.toLocaleString() }}</strong> 个不同值，
          列出来无法使用。<br />
          请用下方精确匹配。
        </span>
      </div>
      <div class="fpanel__manual">
        <input class="input input--mono" v-model="manualValue" placeholder="输入精确值"
               @keydown.enter="applyManual" />
        <button class="btn btn--sm btn--primary" :disabled="manualValue === ''"
                @click="applyManual">匹配</button>
      </div>
    </div>

    <template v-else>
      <!-- 批量操作 -->
      <div class="fpanel__tools">
        <button class="btn btn--sm btn--ghost" @click="selectAll">全选</button>
        <button class="btn btn--sm btn--ghost" @click="invert">反选</button>
        <button class="btn btn--sm btn--ghost" @click="clearAll">清除</button>
        <span class="grow"></span>
        <span class="muted small">{{ visible.length }} 项</span>
      </div>

      <!-- 搜索候选值 -->
      <div class="fpanel__search">
        <input class="input input--mono" v-model="search" placeholder="搜索候选值…" />
      </div>

      <!-- 候选列表 -->
      <div class="fpanel__list">
        <label v-for="(it, i) in visible" :key="i" class="fopt"
               :class="{ 'is-disabled': it.disabled }">
          <input type="checkbox" :checked="it.selected" :disabled="it.disabled"
                 @change="toggle(it)" />
          <span class="fopt__label" :title="it.label">{{ it.label }}</span>
          <!-- ⭐ count=0 也要显示（禁用但可见），隐藏会让用户以为不存在 -->
          <span class="fopt__count" :class="{ 'is-zero': it.count === 0 }">{{
            it.count.toLocaleString()
          }}</span>
        </label>
        <div v-if="!visible.length" class="muted small fpanel__empty">没有候选值</div>
      </div>

      <!-- 底部动作 -->
      <div class="fpanel__foot">
        <button class="btn btn--sm" @click="showManual = !showManual">
          {{ showManual ? "收起精确匹配" : "精确匹配" }}
        </button>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="emit('close')">取消</button>
        <button class="btn btn--sm btn--primary" @click="apply">应用筛选</button>
      </div>

      <div v-if="showManual" class="fpanel__manual">
        <input class="input input--mono" v-model="manualValue" placeholder="输入精确值"
               @keydown.enter="applyManual" />
        <button class="btn btn--sm btn--primary" :disabled="manualValue === ''"
                @click="applyManual">匹配</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fpanel {
  position: absolute;
  top: var(--h-toolbar);
  right: 0;
  width: 300px;
  max-height: 460px;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border: var(--bw) solid var(--border-strong);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-lg);
  z-index: var(--z-dropdown);
  font-size: var(--fs-xs);
}
.fpanel__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2);
  border-bottom: var(--bw) solid var(--border);
  font-weight: var(--fw-semibold);
}
.fpanel__tools {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  padding: var(--sp-1) var(--sp-2);
  border-bottom: var(--bw) solid var(--border);
}
.fpanel__search { padding: var(--sp-1) var(--sp-2); }
.fpanel__list { flex: 1; overflow-y: auto; padding: 0 var(--sp-1); }
.fpanel__body--center { padding: var(--sp-4); text-align: center; }
.fpanel__body { padding: var(--sp-2); }
.fpanel__empty { padding: var(--sp-3); text-align: center; }
.fpanel__foot {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  padding: var(--sp-2);
  border-top: var(--bw) solid var(--border);
}
.fpanel__manual {
  display: flex;
  gap: var(--sp-1);
  padding: var(--sp-2);
  border-top: var(--bw) solid var(--border);
}

.fopt {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px var(--sp-1);
  border-radius: var(--r-sm);
  cursor: pointer;
}
.fopt:hover { background: var(--bg-hover); }
.fopt.is-disabled { opacity: 0.45; cursor: not-allowed; }
.fopt__label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
}
.fopt__count {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-tertiary);
  flex: 0 0 auto;
}
/* ⭐ count=0 但仍可见 —— 隐藏会让用户以为这个值不存在 */
.fopt__count.is-zero { color: var(--danger); }
</style>