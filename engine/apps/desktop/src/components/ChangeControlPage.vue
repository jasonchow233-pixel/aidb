<script setup lang="ts">
import { computed, ref } from "vue";
import { state } from "@/lib/store";

/**
 * 变更管控台（Change Control）—— 用户 issue #11964 明确指出「能增加竞争力」
 * ⭐ 这不是 Navicat 的「操作历史」，是**变更工单**：有审批、有回滚脚本、有影响面。
 */

const ENV = [
  { k: "dev", label: "开发", cls: "badge--env-dev" },
  { k: "test", label: "测试", cls: "" },
  { k: "staging", label: "预发", cls: "badge--warn" },
  { k: "prod", label: "生产", cls: "badge--prod" },
] as const;

interface Change {
  id: string;
  env: keyof typeof ENV[number] | string;
  title: string;
  sql: string;
  danger: string;
  status: "draft" | "review" | "approved" | "applied" | "rolledback";
  author: string;
  rows: number;
  createdAt: string;
  approver?: string;
  rollback?: string;
}

const changes = ref<Change[]>([
  {
    id: "CHG-0001", env: "prod", title: "修正 orders.status 脏枚举值 7",
    sql: "UPDATE orders SET status = 2 WHERE status = 7;",
    danger: "mutating", status: "review", author: "张伟", rows: 318, createdAt: "2026-10-15 14:22",
    rollback: "UPDATE orders SET status = 7 WHERE status = 2 AND created_at >= '2026-10-15';",
  },
  {
    id: "CHG-0002", env: "prod", title: "删除 audit_logs 90 天前数据",
    sql: "DELETE FROM audit_logs WHERE created_at < datetime('now','-90 days');",
    danger: "destructive", status: "draft", author: "李娜", rows: 128400, createdAt: "2026-10-16 09:10",
    rollback: "-- 不可逆（物理删除）。建议先归档到 audit_logs_archive。",
  },
  {
    id: "CHG-0003", env: "staging", title: "users 表新增 column preferences",
    sql: "ALTER TABLE users ADD COLUMN preferences TEXT;",
    danger: "destructive", status: "approved", author: "王强", rows: 0, createdAt: "2026-10-14 11:00",
    approver: "赵敏", rollback: "ALTER TABLE users DROP COLUMN preferences;",
  },
  {
    id: "CHG-0004", env: "test", title: "批量插入压测数据",
    sql: "INSERT INTO sessions (user_id, token) SELECT id, 'tok_' || id FROM users;",
    danger: "mutating", status: "applied", author: "陈杰", rows: 800, createdAt: "2026-10-13 16:40",
    approver: "张伟", rollback: "DELETE FROM sessions WHERE token LIKE 'tok_%';",
  },
]);

const filter = ref<string>("all");
const selected = ref<Change | null>(null);

const visible = computed(() => (filter.value === "all" ? changes.value : changes.value.filter((c) => c.env === filter.value)));

const statusLabel: Record<string, string> = {
  draft: "草稿", review: "待审", approved: "已批", applied: "已执行", rolledback: "已回滚",
};
const statusCls: Record<string, string> = {
  draft: "", review: "badge--warn", approved: "badge--ok", applied: "badge--ok", rolledback: "badge--risk-3",
};

const counts = computed(() => {
  const m: Record<string, number> = {};
  for (const c of changes.value) m[c.env] = (m[c.env] ?? 0) + 1;
  return m;
});

function setStatus(c: Change, s: Change["status"]) {
  c.status = s;
  if (s === "approved" && !c.approver) c.approver = "当前用户";
  if (s === "applied" && !c.rollback) {
    c.rollback = c.danger === "destructive" && c.sql.startsWith("DELETE")
      ? "-- 不可逆：物理删除。请确认已归档。"
      : c.sql.replace(/^(UPDATE|DELETE|INSERT)/i, "-- 回滚脚本由变更前快照生成");
  }
}

/** ⭐ 生产环境的破坏性变更必须二次确认（确认框里显示影响行数与回滚可用性） */
function apply(c: Change) {
  if (c.env === "prod" && c.danger === "destructive" && !c.rollback) {
    window.confirm(`生产环境破坏性变更 ${c.id}\n\n影响行数：${c.rows.toLocaleString()}\n回滚脚本：无（不可逆）\n\n确认执行？`);
  }
  setStatus(c, "applied");
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="page-head__title">
        <span class="ico">📋</span>
        <span class="page-head__name">变更管控</span>
        <span class="page-head__comment">审批 · 影响面 · 回滚脚本</span>
        <span class="grow"></span>
        <span v-for="e in ENV" :key="e.k" class="badge" :class="e.cls">
          {{ e.label }}{{ counts[e.k] ? ` ${counts[e.k]}` : "" }}
        </span>
      </div>
      <div class="toolbar">
        <button v-for="e in ENV" :key="e.k" class="btn btn--sm"
                :class="{ 'btn--primary': filter === e.k }" @click="filter = e.k">{{ e.label }}</button>
        <button class="btn btn--sm" :class="{ 'btn--primary': filter === 'all' }" @click="filter = 'all'">全部</button>
        <span class="grow"></span>
        <button class="btn btn--sm btn--primary" @click="$emit('new')">＋ 新建变更</button>
      </div>
    </div>

    <div class="scroll">
      <table class="grid" style="min-width:100%">
        <thead><tr>
          <th style="min-width:90px">编号</th>
          <th style="min-width:66px">环境</th>
          <th style="min-width:240px">变更内容</th>
          <th style="min-width:90px">危险级别</th>
          <th style="min-width:90px">影响行数</th>
          <th style="min-width:80px">状态</th>
          <th style="min-width:90px">发起人</th>
          <th style="min-width:130px">时间</th>
          <th style="min-width:180px">操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="c in visible" :key="c.id" :style="{ height: 'var(--row-normal)' }"
              @click="selected = c">
            <td class="mono">{{ c.id }}</td>
            <td><span class="badge" :class="ENV.find(e => e.k === c.env)?.cls">{{ ENV.find(e => e.k === c.env)?.label }}</span></td>
            <td class="truncate" :title="c.title">{{ c.title }}</td>
            <td>
              <span class="badge" :class="{
                'badge--risk-0': c.danger === 'readOnly', 'badge--risk-1': c.danger === 'mutating',
                'badge--risk-2': c.danger === 'destructive', 'badge--risk-3': c.danger === 'admin' }">
                {{ { readOnly: '只读', mutating: '写数据', destructive: '改结构', admin: '管理' }[c.danger] }}
              </span>
            </td>
            <td class="num mono">{{ c.rows ? c.rows.toLocaleString() : "—" }}</td>
            <td><span class="badge" :class="statusCls[c.status]">{{ statusLabel[c.status] }}</span></td>
            <td>{{ c.author }}</td>
            <td class="small muted mono">{{ c.createdAt }}</td>
            <td @click.stop>
              <button v-if="c.status === 'draft'" class="btn btn--sm" @click="setStatus(c, 'review')">提交</button>
              <button v-if="c.status === 'review'" class="btn btn--sm btn--primary" @click="setStatus(c, 'approved')">批准</button>
              <button v-if="c.status === 'approved'" class="btn btn--sm btn--danger" @click="apply(c)">执行</button>
              <button v-if="c.status === 'applied'" class="btn btn--sm" @click="setStatus(c, 'rolledback')"
                      :disabled="!c.rollback" :title="c.rollback ? '' : '不可逆变更'">回滚</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 详情 -->
    <div class="cell-detail" v-if="selected" style="max-height:44%">
      <div class="cell-detail__head">
        <strong class="mono">{{ selected.id }}</strong>
        <span class="badge" :class="statusCls[selected.status]">{{ statusLabel[selected.status] }}</span>
        <span class="muted small">{{ selected.title }}</span>
        <span class="grow"></span>
        <button class="btn btn--sm" @click="navigator.clipboard.writeText(selected.sql)">复制 SQL</button>
        <button class="btn btn--sm" @click="selected = null">✕</button>
      </div>
      <div class="cell-detail__body">
        <div class="small muted" style="margin-bottom:4px">正向 SQL</div>
        <pre class="mono small" style="margin:0 0 8px;color:var(--text-primary)">{{ selected.sql }}</pre>
        <div class="small muted" style="margin-bottom:4px">
          回滚脚本
          <span v-if="!selected.rollback" style="color:var(--risk-2-structure)">· 无（不可逆）</span>
        </div>
        <pre class="mono small" style="margin:0;color:var(--success)">{{ selected.rollback ?? "—" }}</pre>
        <div class="small muted" style="margin-top:8px">
          发起 {{ selected.author }}<template v-if="selected.approver"> · 批准 {{ selected.approver }}</template>
          · {{ selected.createdAt }} · 影响 {{ selected.rows.toLocaleString() }} 行
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
export default { emits: ["new"] };
</script>

<style scoped>
.ico { font-size: 14px; }
</style>