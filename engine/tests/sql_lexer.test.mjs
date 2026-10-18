#!/usr/bin/env node
/**
 * ⭐ SQL 词法内核单测 —— 不需要浏览器，也不需要后端。
 *
 * ⭐ 为什么单独有一层：`sql.ts` 是高亮、跳转、格式化、多语句的**共同地基**。
 *   这四样都错的时候页面表现各不相同（颜色错 / 跳错对象 / 格式化毁 SQL /
 *   切错语句），在浏览器里逐个找太慢，而且「格式化毁 SQL」这种
 *   会直接把用户的查询改坏 —— 必须有一层能单独盯住它的断言。
 *
 * 跑法：node engine/tests/sql_lexer.test.mjs
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  tokenize, collectScope, symbolAt, splitStatements, statementAtOffset,
  formatSql, highlightHtml, applyInsert, applySkipOver, lineOf,
  shouldAutoPair, closerOf,
} from "../apps/desktop/src/lib/sql.ts";

// node 22+ 能直接 strip TS 类型；本项目用 node 25。
const TABLES = new Set(["orders", "users", "order_items", "products", "v_order_summary"]);

/**
 * ⭐ 分词不丢**非空白**字符。
 *
 * 空白**故意**不产出 token（高亮层按原文切片输出它，见 highlightHtml），
 * 所以这里断言的是「token 之间的空隙必须全是空白」——
 * 这才是真正的不变量：任何非空白字符被丢掉，缝隙里就会出现它。
 */
test("分词不丢字符：token 之间的空隙只能是空白", () => {
  const cases = [
    "SELECT id, name FROM orders WHERE status = 2;",
    "SELECT 'a;b' AS s, \"weird col\" FROM t -- 注释里有 ; 和 ' 引号\n",
    "SELECT /* 块\n注释 */ 1.5e3, 0xFF, NULL FROM t",
    "select   a\t,\n\nb   from  t",
    "SELECT '未闭合的字符串",
    "SELECT \"未闭合的标识符",
    "SELECT # 一些符号 @ \\ | ^ ~ ` `",
  ];
  for (const sql of cases) {
    const toks = tokenize(sql);
    let pos = 0;
    for (const t of toks) {
      const gap = sql.slice(pos, t.start);
      assert.equal(gap.trim(), "", `分词丢字符 ${JSON.stringify(gap)}：${JSON.stringify(sql)}`);
      assert.ok(t.end > t.start, `空 token：${JSON.stringify(sql)}`);
      assert.equal(t.text, sql.slice(t.start, t.end), `token 文本与切片不符`);
      pos = t.end;
    }
    const tail = sql.slice(pos);
    assert.equal(tail.trim(), "", `尾部丢了字符 ${JSON.stringify(tail)}：${JSON.stringify(sql)}`);
  }
});

/**
 * ⭐ 更强的不变量：高亮层渲染出来的文本 === 原文。
 *
 * ⚠ 末尾那个多出来的 `\n` 是 `highlightHtml` **故意**加的：
 *   textarea 里 `a\n` 会多显示一行空行，而 `pre` 不会 ——
 *   不补这一行，光标在末尾时高亮层比文本框矮一行，看着像少了一行内容。
 *   所以这里比的是「去掉那个补行之后是否等于原文」。
 */
test("分词 → 高亮 → 纯文本 === 原文", () => {
  const cases = [
    "SELECT id, name FROM orders WHERE status = 2;",
    "  select   a\t,\n\nb   from  t -- x\n",
    "SELECT 'a;b', \"q\" FROM t",
    "SELECT '未闭合",
  ];
  for (const sql of cases) {
    const html = highlightHtml(sql);
    assert.ok(html.endsWith("\n"), "highlightHtml 必须在末尾补一个换行（对齐 textarea 的行数）");
    const text = html.replace(/<[^>]+>/g, "")
      .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
      .replace(/&amp;/g, "&");
    assert.equal(text.slice(0, -1), sql, `高亮文本 ≠ 原文\n原文: ${JSON.stringify(sql)}\n渲染: ${JSON.stringify(text)}`);
  }
});

test("关键字 / 类型 / 函数分得开", () => {
  const sql = "SELECT COUNT(*) FROM orders WHERE total > 100";
  const by = Object.fromEntries(tokenize(sql).map((t) => [t.value, t.kind]));
  assert.equal(by.SELECT, "keyword");
  assert.equal(by.COUNT, "func", "后面跟 ( 的标识符应判为函数");
  assert.equal(by.FROM, "keyword");
  assert.equal(by.orders, "ident");
  assert.equal(by.WHERE, "keyword");
  assert.equal(by[">"], "operator");
});

test("类型名与关键字区分开", () => {
  const by = Object.fromEntries(
    tokenize("CREATE TABLE t (id INTEGER, name VARCHAR(20))").map((t) => [t.value.toUpperCase(), t.kind]),
  );
  assert.equal(by.INTEGER, "type");
  assert.equal(by.CREATE, "keyword");
});

test("注释与字符串里的分号不当分隔符", () => {
  assert.equal(splitStatements("SELECT 'a;b' FROM t").length, 1,
    "字符串里的分号不该切语句");
  assert.equal(splitStatements("SELECT 1 -- ; 不是分隔\n; SELECT 2").length, 2,
    "注释里的分号不该切语句");
  assert.equal(splitStatements("SELECT 1; SELECT 2;").length, 2);
  assert.equal(splitStatements("SELECT 1; SELECT 2").length, 2, "末尾无分号也要算一条");
  assert.equal(splitStatements("SELECT 1;;;  -- 只有一条\n").length, 1, "空语句不计");
});

test("语句的行号对得上原文", () => {
  const sql = "SELECT 1;\n\nSELECT 2;\nSELECT 3";
  const stmts = splitStatements(sql);
  assert.equal(stmts.length, 3);
  assert.equal(stmts[0].line, 1);
  assert.equal(stmts[1].line, 3);
  assert.equal(stmts[2].line, 4);
  assert.equal(lineOf(sql, 0), 1);
  assert.equal(lineOf(sql, sql.indexOf("SELECT 3")), 4);
});

test("statementAtOffset 取到光标所在那条", () => {
  const sql = "SELECT 1;\nSELECT 2;\nSELECT 3";
  const s = statementAtOffset(sql, sql.indexOf("SELECT 2") + 2);
  assert.ok(s);
  assert.match(s.text, /SELECT 2/);
  // 光标落在末尾空白处 → 归到最后一条
  const tail = statementAtOffset(sql, sql.length + 1);
  assert.match(tail.text, /SELECT 3/);
});

test("别名收集：AS / 裸别名 / schema 限定", () => {
  const scope = collectScope("SELECT 1 FROM orders o JOIN users AS u ON u.id = o.user_id", TABLES);
  assert.ok(scope.tables.has("orders"));
  assert.ok(scope.tables.has("users"));
  assert.equal(scope.aliases.get("o"), "orders");
  assert.equal(scope.aliases.get("u"), "users");

  const s2 = collectScope("SELECT * FROM main.orders", TABLES);
  assert.ok(s2.tables.has("orders"), "schema.orders 要认成 orders");
});

test("⭐ 不把普通词当成表（否则跳转会打开不存在的表）", () => {
  const scope = collectScope("SELECT count FROM t", TABLES);
  assert.equal(scope.tables.size, 0, "FROM 后面必须是真的表名才算引用");
  const scope2 = collectScope("SELECT count FROM orders", TABLES);
  assert.equal(scope2.tables.size, 1);
});

test("符号定位：表 / 列 / 别名", () => {
  const sql = "SELECT o.id, u.email FROM orders o JOIN users u ON u.id = o.user_id";
  const at = (needle) => sql.indexOf(needle) + 1;

  const t1 = symbolAt(sql, at("orders"), TABLES);
  assert.equal(t1.kind, "table");
  assert.equal(t1.name, "orders");

  const a1 = symbolAt(sql, at(" o."), TABLES);
  assert.equal(a1.kind, "table", "别名 o 指回 orders");
  assert.equal(a1.name, "orders");

  const c1 = symbolAt(sql, at("o.id") + 3, TABLES);
  assert.equal(c1.kind, "column");
  assert.equal(c1.name, "id");
  // ⭐ `table` 必须是**真实表名**，不是别名 ——
  //   给别名的话 UI 会去打开一张叫 `o` 的表（不存在）。
  assert.equal(c1.table, "orders", "o.id 的归属要解析成 orders");
  assert.equal(c1.alias, "o", "原始别名另存一份，提示里要显示");

  const c2 = symbolAt(sql, at("u.email") + 3, TABLES);
  assert.equal(c2.kind, "column");
  assert.equal(c2.table, "users", "u.email 的归属要解析成 users");
});

/**
 * ⭐⭐ 回归：裸列名必须认得出来。
 *
 * `SELECT status FROM orders` 里的 `status` 既不是表名也不是别名，
 * 但它是 orders 的列 —— **这是最常见的写法**（没起别名）。
 *
 * 实测踩过的坑：漏了 `columnsOf` 参数，hover 表名一切正常、
 * hover 列名完全不弹 —— 很容易误判成「hover 功能坏了」。
 */
test("裸列名要靠列清单才认得出", () => {
  const sql = "SELECT status FROM orders";
  const off = sql.indexOf("status") + 1;
  const cols = new Set(["id", "order_no", "status", "total_cents"]);

  assert.equal(symbolAt(sql, off, TABLES, () => cols)?.kind, "column",
    "给了列清单就该认成列");
  assert.equal(symbolAt(sql, off, TABLES, () => cols)?.table, "orders");
  assert.equal(symbolAt(sql, off, TABLES), null,
    "没有列清单时不能瞎认 —— 会把普通词当成列名");
});

/** ⭐ 同名列跨多张表时**不猜**，把候选都列出来 */
test("同名列跨表 → 列候选，不替用户选", () => {
  const sql = "SELECT id FROM orders o JOIN order_items i ON i.order_id = o.id";
  const off = sql.indexOf("id") + 1;
  const ordersCols = new Set(["id", "order_no", "status"]);
  const itemsCols = new Set(["id", "order_id", "product_id"]);
  const s = symbolAt(sql, off, TABLES, (t) =>
    t === "orders" ? ordersCols : t === "order_items" ? itemsCols : null);

  assert.equal(s?.kind, "column");
  assert.equal(s?.table, undefined, "跨表同名时不能替用户选一张");
  assert.deepEqual(s?.owners, ["orders", "order_items"]);
});

test("引号包起来的表名也能定位", () => {
  const sql = 'SELECT * FROM "orders"';
  const s = symbolAt(sql, sql.indexOf("orders") + 2, TABLES);
  assert.equal(s.kind, "table");
  assert.equal(s.name, "orders");
});

test("不可跳转的位置老实返回 null（UI 要据此说「不可跳转」）", () => {
  const sql = "SELECT 1";
  assert.equal(symbolAt(sql, 3, TABLES), null, "数字不是符号");
  assert.equal(symbolAt(sql, 6, TABLES), null, "空白不是符号");
  const bad = 'SELECT * FROM "orderz"';
  assert.equal(symbolAt(bad, bad.indexOf("orderz") + 2, TABLES), null,
    "引号里是不存在的表 → 不给跳转，避免打开一张不存在的表");
});

test("⭐ 格式化不改语义：结果重新分词后与原文等价", () => {
  const inputs = [
    "select id,name from orders where status=2 order by created_at desc limit 10",
    "SELECT o.id,u.email FROM orders o JOIN users u ON u.id=o.user_id WHERE o.status=2",
    "SELECT count(*) FROM orders GROUP BY status HAVING count(*)>1 ORDER BY 2",
    "SELECT 'keep  ;  me', x FROM t -- keep\n",
  ];
  for (const sql of inputs) {
    const out = formatSql(sql);
    assert.equal(norm(out), norm(sql), `格式化改了语义\n原文: ${sql}\n结果: ${out}`);
  }
});

/** ⭐ 归一化：忽略空白与关键字大小写，保留字符串字面量内容 */
function norm(s) {
  let out = "";
  for (const t of tokenize(s)) {
    if (t.kind === "keyword" || t.kind === "type") out += t.value.toUpperCase();
    else if (t.kind === "string") out += `'${t.value}'`;
    else out += t.value;
  }
  return out;
}

test("⭐ 格式化不折行字符串与注释", () => {
  const out = formatSql("SELECT 'a  b' AS x -- 注释    有   空格\nFROM t");
  assert.ok(out.includes("'a  b'"), `字符串里的空格被吃了：${out}`);
  assert.ok(out.includes("-- 注释    有   空格"), `注释里的空格被吃了：${out}`);
  // ⭐ 行注释后必须换行：否则后面的 FROM t 会被注释掉 —— 那是语义改变
  const lines = out.split("\n");
  const cmt = lines.findIndex((l) => l.includes("--"));
  assert.ok(lines[cmt + 1]?.includes("FROM"), `行注释后必须换行：${out}`);
});

test("格式化真的分行了（不是只改大小写）", () => {
  const out = formatSql("select id,name from orders where id>1");
  const lines = out.split("\n").map((l) => l.trim().toUpperCase());
  assert.ok(lines.some((l) => l.startsWith("SELECT")), out);
  assert.ok(lines.some((l) => l.startsWith("FROM")), out);
  assert.ok(lines.some((l) => l.startsWith("WHERE")), out);
  assert.ok(lines.some((l) => l.startsWith("NAME")), `选择列表应换行对齐：${out}`);
});

test("高亮 HTML 转义且不丢字符", () => {
  const sql = "SELECT '<img src=x onerror=alert(1)>' FROM t";
  const html = highlightHtml(sql);
  assert.ok(!html.includes("<img"), `未转义的尖括号会变成真实标签：${html}`);
  assert.ok(html.includes("&lt;img"));
  // 剥掉标签后应还原出原文
  const text = html.replace(/<[^>]+>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
  assert.equal(text.trimEnd(), sql, `高亮层渲染的文本与原文不一致：\n${text}`);
});

test("高亮覆盖全部 token 类型", () => {
  const sql = "SELECT a, 's', 1, -- c\n b FROM t WHERE x >= 1";
  const kinds = new Set(tokenize(sql).map((t) => t.kind));
  for (const k of ["keyword", "string", "number", "comment", "ident", "operator", "punct"]) {
    assert.ok(kinds.has(k), `高亮应覆盖 ${k}，实得 ${[...kinds].join(",")}`);
  }
});

test("自动配对：打左括号补右括号，光标在中间", () => {
  assert.equal(shouldAutoPair("("), true);
  assert.equal(shouldAutoPair(")"), false, "右括号不配对，否则会多出括号");
  assert.equal(closerOf("("), ")");

  const r = applyInsert("count", 5, 5, "(", { autoPair: true });
  assert.equal(r.text, "count()", "应补出右括号");
  assert.equal(r.caret, 6, "光标应落在括号中间");
});

test("⭐ 自动配对：光标已在闭合符上要跳过，不能补成两个", () => {
  const r = applyInsert("count()", 6, 6, ")", { autoPair: true });
  assert.equal(r.text, "count()", `不该变成 count())：${r.text}`);
  assert.equal(r.caret, 7, "光标应跳过闭合符");

  // 引号同理
  const q = applyInsert("WHERE a=''", 8, 8, "'", { autoPair: true });
  assert.equal(q.text, "WHERE a=''", `不该变成 WHERE a=''' ：${q.text}`);
  assert.equal(q.caret, 9);

  const skip = applySkipOver("count()", 6, ")");
  assert.ok(skip);
  assert.equal(skip.caret, 7);
});

test("自动配对关掉时就是普通插入", () => {
  const r = applyInsert("count", 5, 5, "(");
  assert.equal(r.text, "count(");
  const r2 = applyInsert("count()", 6, 6, ")");
  assert.equal(r2.text, "count())", "关掉配对不该跳过 —— 用户可能真要两个");
});

test("插入替换选区", () => {
  const r = applyInsert("SELECT foo", 7, 10, "bar");
  assert.equal(r.text, "SELECT bar");
  assert.equal(r.caret, 10);
});

test("坏输入不崩（空串 / 只有空白 / 只有注释）", () => {
  for (const s of ["", "   ", "\n\n", "-- 只有注释", "/*", "'", '"', "?", ";"]) {
    assert.doesNotThrow(() => tokenize(s), `tokenize 崩了：${JSON.stringify(s)}`);
    assert.doesNotThrow(() => splitStatements(s));
    assert.doesNotThrow(() => formatSql(s));
    assert.doesNotThrow(() => highlightHtml(s));
    assert.doesNotThrow(() => collectScope(s, TABLES));
    assert.doesNotThrow(() => symbolAt(s, 0, TABLES));
  }
});
