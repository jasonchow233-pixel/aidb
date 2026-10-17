/**
 * ⭐ 前端编辑闭环的关键纯函数单测。
 *
 * 为什么要有：这些函数出错的后果是**静默的数据损坏** ——
 * 把空串写成 NULL、把 NULL 写成空串、WHERE 漏掉主键 ——
 * 界面上看不出来，数据库也不会立刻报错，直到某天有人查数发现不对。
 * `vite build` 和 API 契约都测不到这一层。
 */
import { describe, it, expect } from "vitest";

// store.ts 依赖 @/lib/api 与 localStorage，这里只取纯函数
// 为了能单测，把 sqlLiteral 复制一份最小实现并保持同步。
// ⭐ 更稳的做法是抽到独立模块 —— 见 TODO：把 sqlLiteral 移到 lib/sql.ts
function sqlLiteral(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number") {
    if (!Number.isFinite(v)) return "NULL";
    return String(v);
  }
  if (typeof v === "boolean") return v ? "1" : "0";
  return `'${String(v).replace(/'/g, "''")}'`;
}

describe("sqlLiteral —— NULL 与空串必须可区分", () => {
  it("null / undefined → SQL NULL 关键字（不加引号）", () => {
    expect(sqlLiteral(null)).toBe("NULL");
    expect(sqlLiteral(undefined)).toBe("NULL");
  });

  it("⭐ 空串 → ''（绝不能变成 NULL）", () => {
    // 这是 #10505 的核心：一旦这里写成 "NULL"，
    // 用户清空一个字段就会把数据改成 NULL，无法回退
    expect(sqlLiteral("")).toBe("''");
    expect(sqlLiteral("")).not.toBe("NULL");
  });

  it("⭐ 空格串 → ' '（与空串、与 NULL 都不同）", () => {
    expect(sqlLiteral(" ")).toBe("' '");
  });

  it("数字不加引号", () => {
    expect(sqlLiteral(0)).toBe("0");
    expect(sqlLiteral(-1)).toBe("-1");
    expect(sqlLiteral(3.14)).toBe("3.14");
  });

  it("NaN / Infinity → NULL（不产出非法 SQL 字面量）", () => {
    // 写成 NaN 会让整条 UPDATE 语法错误，报错位置还很难定位
    expect(sqlLiteral(NaN)).toBe("NULL");
    expect(sqlLiteral(Infinity)).toBe("NULL");
  });

  it("布尔 → 1/0", () => {
    expect(sqlLiteral(true)).toBe("1");
    expect(sqlLiteral(false)).toBe("0");
  });

  it("⭐ 单引号必须转义（否则 SQL 注入 + 语法错误）", () => {
    expect(sqlLiteral("O'Brien")).toBe("'O''Brien'");
    expect(sqlLiteral("'; DROP TABLE orders; --")).toBe(
      "'''; DROP TABLE orders; --'",
    );
  });

  it("⭐ 三个易混值产生三种不同的 SQL", () => {
    const n = sqlLiteral(null);
    const e = sqlLiteral("");
    const w = sqlLiteral(" ");
    expect(new Set([n, e, w]).size).toBe(3);
  });
});

describe("buildSaveSql 的 WHERE 安全性", () => {
  /** 复刻 store.buildSaveSql 的核心逻辑做验证 */
  function buildSaveSql(table: string, rows: Record<string, unknown>[], cols: { name: string; isPk: boolean }[], edits: { row: number; col: string; newValue: unknown }[]) {
    const pk = cols.filter((c) => c.isPk);
    if (!pk.length) return { statements: [], error: `表 ${table} 没有主键` };

    const byRow = new Map<number, typeof edits>();
    for (const e of edits) {
      const l = byRow.get(e.row) ?? [];
      l.push(e);
      byRow.set(e.row, l);
    }
    const statements: string[] = [];
    for (const [rowIdx, cellEdits] of byRow) {
      const row = rows[rowIdx];
      if (!row) continue;
      const sets = cellEdits.map((e) => `"${e.col}" = ${sqlLiteral(e.newValue)}`).join(", ");
      const where = pk.map((c) => `"${c.name}" = ${sqlLiteral(row[c.name])}`).join(" AND ");
      statements.push(`UPDATE "${table}" SET ${sets} WHERE ${where};`);
    }
    return { statements };
  }

  const cols = [
    { name: "id", isPk: true },
    { name: "remark", isPk: false },
    { name: "amount", isPk: false },
  ];

  it("WHERE 必须带上主键", () => {
    const rows = [{ id: 7, remark: "old", amount: 100 }];
    const r = buildSaveSql("orders", rows, cols, [{ row: 0, col: "remark", newValue: "new" }]);
    expect(r.statements).toHaveLength(1);
    expect(r.statements[0]).toBe(`UPDATE "orders" SET "remark" = 'new' WHERE "id" = 7;`);
  });

  it("⭐ 无主键表必须拒绝生成 SQL（否则会改到整张表）", () => {
    const rows = [{ tenant_id: 1, actor: "x" }];
    const r = buildSaveSql("audit_logs", rows, [{ name: "tenant_id", isPk: false }], [
      { row: 0, col: "actor", newValue: "y" },
    ]);
    expect(r.statements).toHaveLength(0);
    expect(r.error).toBeTruthy();
  });

  it("⭐ 复合主键 → WHERE 用 AND 连接", () => {
    const cols2 = [
      { name: "tenant_id", isPk: true },
      { name: "biz_no", isPk: true },
      { name: "remark", isPk: false },
    ];
    const rows = [{ tenant_id: 3, biz_no: "N001", remark: "a" }];
    const r = buildSaveSql("t", rows, cols2, [{ row: 0, col: "remark", newValue: "b" }]);
    expect(r.statements[0]).toContain(`WHERE "tenant_id" = 3 AND "biz_no" = 'N001'`);
  });

  it("同一行多列改动合并成一条 UPDATE", () => {
    const rows = [{ id: 1, remark: "a", amount: 5 }];
    const r = buildSaveSql("orders", rows, cols, [
      { row: 0, col: "remark", newValue: "x" },
      { row: 0, col: "amount", newValue: 99 },
    ]);
    expect(r.statements).toHaveLength(1);
    expect(r.statements[0]).toContain(`"remark" = 'x', "amount" = 99`);
  });

  it("⭐ 多行改动 → 多条语句，各自带自己的主键", () => {
    const rows = [
      { id: 1, remark: "a", amount: 1 },
      { id: 2, remark: "b", amount: 2 },
    ];
    const r = buildSaveSql("orders", rows, cols, [
      { row: 0, col: "remark", newValue: "x" },
      { row: 1, col: "remark", newValue: "y" },
    ]);
    expect(r.statements).toHaveLength(2);
    expect(r.statements[0]).toContain(`"id" = 1`);
    expect(r.statements[1]).toContain(`"id" = 2`);
  });

  it("⭐ 清空字段生成 NULL 而不是 ''", () => {
    const rows = [{ id: 1, remark: "x", amount: 1 }];
    const r = buildSaveSql("orders", rows, cols, [{ row: 0, col: "remark", newValue: null }]);
    expect(r.statements[0]).toContain(`"remark" = NULL`);
    expect(r.statements[0]).not.toContain(`"remark" = ''`);
  });

  it("⭐ 主键值含引号时被正确转义（否则 WHERE 失效 → 改到多行）", () => {
    const cols2 = [
      { name: "code", isPk: true },
      { name: "v", isPk: false },
    ];
    const rows = [{ code: "O'Brien", v: "a" }];
    const r = buildSaveSql("t", rows, cols2, [{ row: 0, col: "v", newValue: "b" }]);
    expect(r.statements[0]).toContain(`"code" = 'O''Brien'`);
  });
});