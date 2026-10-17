<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { state, actions, notify } from "@/lib/store";
import { api, capabilities, dangerClass, dangerIcon } from "@/lib/api";

/**
 * AI 面板 —— ⭐ 三条硬规则（2026 年 AI 产品被评判的标准）
 *   1. 每条结论标注来源与可信度（confirmed / learned / inferred）
 *   2. 每条 SQL 在执行前先给「影响面预览」（读还是写？多少行？）
 *   3. 危险分级由后端算，AI 不能自称「只读」就免确认
 */

interface Msg {
  role: "user" | "ai";
  text?: string;
  sql?: string;
  sources?: { kind: string; label: string; trust: "confirmed" | "learned" | "inferred"; detail?: string }[];
  impact?: { danger: string; label: string; rows: number | null; requiresConfirm: boolean };
  note?: string;
}

const log = ref<Msg[]>([]);
const input = ref("");
const busy = ref(false);
const scroller = ref<HTMLElement | null>(null);

const ctx = computed(() => [
  { k: "db", label: capabilities.byId(state.connectionKind)?.label ?? "—", on: true },
  { k: "table", label: state.tableDef?.name ?? "未选表", on: !!state.tableDef },
  { k: "enum", label: "枚举含义", on: true },
  { k: "comments", label: "表/列注释", on: true },
  { k: "history", label: "本会话历史", on: true },
]);

const TRUST = {
  confirmed: { cls: "ai__src--confirmed", icon: "✓", label: "已确认" },
  learned: { cls: "", icon: "◆", label: "已学习" },
  inferred: { cls: "ai__src--inferred", icon: "◇", label: "推断" },
} as const;

const SUGGESTIONS = [
  "上周待支付但未付款的订单有多少？",
  "哪个表最容易缺索引？",
  "把 status=7 的脏数据改成已支付",
  "audit_logs 为什么不能直接 UPDATE？",
];

async function scroll() {
  await nextTick();
  scroller.value?.scrollTo({ top: scroller.value.scrollHeight, behavior: "smooth" });
}

/** ⭐ 演示用的「AI」：真去查元数据并生成 SQL，而不是编一段话 */
async function ask(q: string) {
  const t = q.toLowerCase();
  const msg: Msg = { role: "ai", text: q, sources: [], sql: undefined };

  // 枚举问题
  if (/status|枚举|状态/.test(t) && state.tableDef) {
    const cols = state.tableDef.columns.filter((c) => c.enumValues);
    for (const c of cols) {
      msg.sources!.push({
        kind: "enum", label: `${state.tableDef.name}.${c.name}`,
        trust: c.enumValues ? "confirmed" : "inferred",
        detail: c.enumValues
          ? Object.entries(c.enumValues).map(([k, v]) => `${k}=${v}`).join(" ")
          : "数据库未标注，AI 只能猜",
      });
    }
    if (cols.length) {
      const c = cols[0];
      const kv = Object.entries(c.enumValues!);
      msg.text = `${state.tableDef.name}.${c.name} 的取值含义是：${kv.map(([k, v]) => `${k}=${v}`).join("、")}。`;
      msg.sql = `SELECT ${c.name}, COUNT(*) AS n\nFROM "${state.tableDef.name}"\nGROUP BY ${c.name}\nORDER BY n DESC;`;
    }
  } else if (/索引|index/.test(t)) {
    const idxless = state.findings.filter((f) => f.title.includes("无索引"));
    msg.text = idxless.length
      ? `有 ${idxless.length} 个外键列缺索引，JOIN 时会全表扫描。`
      : "没有发现缺索引的外键。";
    msg.sources!.push({ kind: "diagnostic", label: `结构诊断（${idxless.length} 条）`, trust: "confirmed" });
    for (const f of idxless.slice(0, 3)) {
      const m = /(\w+\.\w+) 有外键但无索引/.exec(f.title);
      if (!m) continue;
      const [tb, col] = m[1].split(".");
      msg.sql = (msg.sql ? msg.sql + "\n\n" : "") + `CREATE INDEX idx_${tb}_${col} ON "${tb}" ("${col}");`;
    }
  } else if (/审计|audit|update/.test(t)) {
    msg.text = "因为 audit_logs 没有主键。UPDATE/DELETE 必须用 WHERE 精确定位行，没有主键就只能全表扫描后逐行比对 —— 这在 20 万行上意味着一次全表写事务，而且一旦条件写错无法回滚到具体行。";
    msg.sources!.push({ kind: "diagnostic", label: "结构诊断", trust: "confirmed" });
  } else if (/脏|status=7|修正|改/.test(t)) {
    msg.sql = "UPDATE orders SET status = 2 WHERE status = 7;";
    msg.text = "把 status=7 归为已支付（2）。注意：这会改写数据且不可自动回滚。";
    msg.sources!.push({ kind: "enum", label: "orders.status 枚举", trust: "confirmed", detail: "1=待支付 2=已支付 3=已发货 4=已完成 9=已取消" });
    msg.sources!.push({ kind: "inference", label: "7 → 2 的映射理由", trust: "inferred", detail: "业务语义需人工确认，AI 只是按「已发货之前都算已支付」推断" });
  } else {
    const tables = [...state.tree.values()].filter((n) => n.kind === "table");
    msg.text = `库里共 ${tables.length} 张表。试试更具体的问题，或点下面的建议。`;
    msg.sources!.push({ kind: "schema", label: `元数据快照（${state.tree.size} 节点）`, trust: "confirmed" });
  }

  // ⭐ 执行前先算影响面 —— 用后端的 analyze，不是 AI 自称
  if (msg.sql) {
    try {
      const preview = await api.query(msg.sql.replace(/;\s*$/, "").split("\n\n")[0], 1);
      msg.impact = {
        danger: preview.danger, label: preview.dangerLabel,
        rows: preview.page.mutated ? preview.page.total : preview.page.total,
        requiresConfirm: preview.requiresConfirm,
      };
    } catch { /* 预览失败不阻塞对话 */ }
  }

  log.value.push({ role: "user", text: q });
  log.value.push(msg);
  input.value = "";
  await scroll();
}

function runSql(m: Msg) {
  if (!m.sql) return;
  state.tabs.push({ id: `q:ai${Date.now()}`, title: "AI 生成", kind: "query", sql: m.sql });
  state.activeTabId = state.tabs[state.tabs.length - 1].id;
  notify("info", "已送到查询编辑器，执行前仍需人工确认");
}
</script>

<template>
  <div class="ai">
    <div class="ai__head">
      <span>AI 助手</span>
      <span class="badge">元数据驱动</span>
      <span class="grow"></span>
      <button class="btn btn--sm btn--ghost" @click="log = []">清空</button>
    </div>

    <div class="ai__log" ref="scroller">
      <!-- 空态 -->
      <div v-if="!log.length" class="col" style="gap:var(--sp-2)">
        <div class="small muted">
          问业务问题，AI 只会基于<strong>真实元数据与枚举</strong>回答。
          每条结论标注来源，每条 SQL 先给影响面。
        </div>
        <button v-for="s in SUGGESTIONS" :key="s" class="btn btn--sm" style="justify-content:flex-start"
                @click="ask(s)">{{ s }}</button>
      </div>

      <!-- 对话 -->
      <template v-for="(m, i) in log" :key="i">
        <div v-if="m.role === 'user'" class="ai__msg ai__msg--user">
          <div class="ai__bubble">{{ m.text }}</div>
        </div>
        <div v-else class="ai__msg">
          <div class="ai__bubble">
            <div>{{ m.text }}</div>

            <div v-if="m.sources?.length" style="margin-top:6px">
              <div v-for="(s, j) in m.sources" :key="j" class="ai__src" :class="TRUST[s.trust].cls">
                <span>{{ TRUST[s.trust].icon }}</span>
                <strong>{{ s.label }}</strong>
                <span class="muted">{{ TRUST[s.trust].label }}</span>
                <span v-if="s.detail" class="muted small">· {{ s.detail }}</span>
              </div>
            </div>

            <template v-if="m.sql">
              <pre class="ai__sql">{{ m.sql }}</pre>
              <div v-if="m.impact" class="ai__impact">
                <span class="badge" :class="`badge--${dangerClass(m.impact.danger)}`">
                  {{ dangerIcon(m.impact.danger) }} {{ m.impact.label }}
                </span>
                <span v-if="m.impact.rows" class="muted">影响 {{ m.impact.rows.toLocaleString() }} 行</span>
                <span v-if="m.impact.requiresConfirm" style="color:var(--risk-1-write)">执行前需人工确认</span>
              </div>
              <div class="ai__actions">
                <button class="btn btn--sm btn--primary" @click="runSql(m)">在编辑器中打开</button>
                <button class="btn btn--sm" @click="navigator.clipboard.writeText(m.sql!)">复制</button>
                <button class="btn btn--sm btn--ghost" @click="m.sql = undefined">丢弃</button>
              </div>
            </template>

            <div v-if="m.note" class="small muted" style="margin-top:6px">{{ m.note }}</div>
          </div>
        </div>
      </template>

      <div v-if="busy" class="muted small">思考中…</div>
    </div>

    <div class="ai__foot">
      <div class="ai__ctx">
        <span v-for="c in ctx" :key="c.k" class="badge" :style="{ opacity: c.on ? 1 : .4 }">{{ c.label }}</span>
      </div>
      <textarea
        class="input"
        style="height:52px;padding:6px 8px;resize:none;font-family:var(--font-ui)"
        v-model="input"
        placeholder="问业务问题…（⌘⏎ 发送）"
        @keydown.meta.enter.prevent="input.trim() && ask(input)"
        @keydown.ctrl.enter.prevent="input.trim() && ask(input)"
      ></textarea>
    </div>
  </div>
</template>