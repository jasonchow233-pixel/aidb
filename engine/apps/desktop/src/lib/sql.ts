/**
 * ⭐⭐ SQL 词法分析 —— 高亮 / 跳转 / 格式化 / 多语句 全部从这里出发。
 *
 * ⭐ 为什么自研（约 260 行）而不是装 CodeMirror：
 *   1. 装包在本机反复超时（`pnpm add` 连不上），而项目已有 4 处「绕开装包」的记录；
 *   2. 真正需要的就是「分词 + 着色 + 找符号 + 切语句」这四件事，
 *      CodeMirror 带来的折叠、多光标、语言服务器一个都用不上；
 *   3. 零依赖 = 构建产物不膨胀，也不会有「升级踩坑」这种长期债。
 *
 * ⭐ 这里的每个函数都是**纯函数**（字符串进、结构出），因此：
 *   · 可以在 `node --test` 里直接跑，不需要浏览器（见 tests/sql_lexer.test.mjs）
 *   · 可以被高亮层、跳转、格式化共用，不会出现两套词法规则打架
 */

// ─────────────────────────────────────────────────────────────
// 类型
// ─────────────────────────────────────────────────────────────

/** 词法单元的种类。名字直接对应 CSS 里的 `--syn-*` 令牌。 */
export type TokenKind =
  | "keyword"     // SELECT / FROM / WHERE …
  | "type"        // INTEGER / TEXT …
  | "func"        // count / ifnull / json_extract …
  | "string"      // 'abc'
  | "number"      // 42 / 3.14 / 0xFF
  | "comment"     // -- 行注释 / 块注释
  | "param"       // ? / :name / $1
  | "operator"    // = + - LIKE
  | "punct"       // , ( ) ;
  | "ident"       // 裸标识符（表名/列名/别名）
  | "quoted"      // "orders" / `orders` / [orders]
  | "unknown";    // 认不出来的字符序列（不当成合法 token，宁可标出来）

export interface Token {
  kind: TokenKind;
  /** 原文（含引号） */
  text: string;
  /** 剥掉引号后的值 */
  value: string;
  /** 在原文里的起止偏移 */
  start: number;
  end: number;
  /** 是否是被引号包起来的（决定跳转时能不能直接当对象名） */
  quoted: boolean;
}

// ─────────────────────────────────────────────────────────────
// 词表
// ─────────────────────────────────────────────────────────────

/**
 * ⭐ 关键字表按「通用 SQL + SQLite 扩展」组织。
 *
 * ⚠ **不追求完整**。列进来的每个词都会影响高亮，多列几个无关紧要的词
 *   （比如把某个表的专有名词当关键字）会把用户的表名染成蓝色 ——
 *   那是**撒谎**：颜色在暗示「这是语言元素」，实际不是。
 *   宁可漏几个关键字（显示成普通标识符，不影响正确性），
 *   也不能误染真表名。
 */
const KEYWORDS = new Set([
  "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET",
  "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL",
  "ON", "USING", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE",
  "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
  "DISTINCT", "ALL", "UNION", "INTERSECT", "EXCEPT",
  "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TRUNCATE",
  "CREATE", "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "TRIGGER",
  "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT",
  "CONSTRAINT", "CASCADE", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
  "WITH", "RECURSIVE", "ASC", "DESC", "IF", "REPLACE", "CONFLICT",
  "PRAGMA", "EXPLAIN", "QUERY", "PLAN", "ANALYZE", "VACUUM",
  "RETURNING", "AUTOINCREMENT", "WITHOUT", "ROWID", "TEMP", "TEMPORARY",
  "ATTACH", "DETACH", "INSTEAD", "OF",
]);

/** 类型名（渲染成 `--syn-type`，与关键字区分开） */
const TYPES = new Set([
  "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "TEXT", "VARCHAR",
  "CHAR", "CLOB", "BLOB", "REAL", "DOUBLE", "FLOAT", "NUMERIC", "DECIMAL",
  "BOOLEAN", "BOOL", "DATE", "TIME", "TIMESTAMP", "DATETIME", "JSON",
  "UUID", "SERIAL", "BYTEA", "MONEY",
]);

/** 常用内建函数（后面跟 `(` 的裸标识符一律按函数处理，这里只是提前认出来） */
const FUNCTIONS = new Set([
  "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "IFNULL", "NULLIF",
  "LENGTH", "UPPER", "LOWER", "TRIM", "SUBSTR", "REPLACE", "ROUND", "ABS",
  "GROUP_CONCAT", "STRFTIME", "DATE", "JULIANDAY", "JSON_EXTRACT",
  "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD",
]);

// ─────────────────────────────────────────────────────────────
// 分词
// ─────────────────────────────────────────────────────────────

const isDigit = (c: string) => c >= "0" && c <= "9";
const isIdentStart = (c: string) =>
  (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || c === "_" || c > "\x7f";
const isIdentPart = (c: string) => isIdentStart(c) || isDigit(c) || c === "$";

/** 配对符号。类型定义放在文件末尾的 `__tests__` 里做自检。 */
const PAIRS: Record<string, string> = { "(": ")", "[": "]", "{": "}", '"': '"', "'": "'", "`": "`" };
const CLOSERS = new Set([")", "]", "}"]);

/**
 * ⭐ 把 SQL 切成 token 序列。**不丢字符**：拼接所有 token 的 `text`
 * 必须等于原文（`tests/sql_lexer.test.mjs` 里有一条断言在盯这件事）。
 *
 * 为什么要有这条不变量：高亮层是「用 token 重新渲染一份带颜色的文本」，
 * 只要漏掉一个字符，用户看到的 SQL 就和实际执行的 SQL 不一样 ——
 * 而用户是照着屏幕上的 SQL 思考的。这比高亮不漂亮严重得多。
 */
export function tokenize(sql: string): Token[] {
  const out: Token[] = [];
  let i = 0;

  const push = (kind: TokenKind, start: number, end: number, value?: string, quoted = false) => {
    if (end <= start) return;
    out.push({ kind, text: sql.slice(start, end), value: value ?? sql.slice(start, end), start, end, quoted });
  };

  while (i < sql.length) {
    const c = sql[i];

    // 空白：跳过，但**不产出 token**（拼接不变量靠「原文按 start/end 切片」保证）
    if (c === " " || c === "\t" || c === "\n" || c === "\r") { i++; continue; }

    // ── 行注释 `--`（必须与减号区分开：`a--1` 是减两次，不是注释）──
    if (c === "-" && sql[i + 1] === "-") {
      const nl = sql.indexOf("\n", i);
      const end = nl < 0 ? sql.length : nl;
      push("comment", i, end);
      i = end;
      continue;
    }

    // ── 块注释 /* … *\/ ──
    if (c === "/" && sql[i + 1] === "*") {
      const close = sql.indexOf("*/", i + 2);
      const end = close < 0 ? sql.length : close + 2;
      push("comment", i, end);
      i = end;
      continue;
    }

    // ── 字符串 '…'：SQL 用两个单引号转义（'' = 一个单引号）──
    if (c === "'") {
      let j = i + 1;
      while (j < sql.length) {
        if (sql[j] === "'") {
          if (sql[j + 1] === "'") { j += 2; continue; } // 转义的单引号
          break;
        }
        j++;
      }
      const closed = j < sql.length && sql[j] === "'";
      const end = closed ? j + 1 : Math.min(j, sql.length); // 未闭合 → 停在末尾
      push("string", i, end, sql.slice(i + 1, closed ? end - 1 : end), true);
      i = end;
      continue;
    }

    // ── 标识符 "…" / `…` / […] ──
    //    SQLite/PG 用 "，MySQL 用 `，Access/SQL Server 用 []。
    //    三种都认，才不需要在前端写 if (conn === "mysql")。
    if (c === '"' || c === "`" || c === "[") {
      const close = c === "[" ? "]" : c;
      const j = sql.indexOf(close, i + 1);
      const end = j < 0 ? sql.length : j + 1;
      const inner = sql.slice(i + 1, j < 0 ? sql.length : j);
      push("quoted", i, end, inner, true);
      i = end;
      continue;
    }

    // ── 数字：0xFF / 1.5 / 1e9 / 42 ──
    if (isDigit(c) || (c === "." && isDigit(sql[i + 1] ?? ""))) {
      let j = i;
      if (c === "0" && (sql[i + 1] === "x" || sql[i + 1] === "X")) {
        j = i + 2;
        while (j < sql.length && /[0-9a-fA-F]/.test(sql[j])) j++;
      } else {
        while (j < sql.length && isDigit(sql[j])) j++;
        if (sql[j] === ".") { j++; while (j < sql.length && isDigit(sql[j])) j++; }
        if (sql[j] === "e" || sql[j] === "E") {
          const save = j;
          j++;
          if (sql[j] === "+" || sql[j] === "-") j++;
          if (j < sql.length && isDigit(sql[j])) { while (j < sql.length && isDigit(sql[j])) j++; }
          else j = save; // `1e` 后面没有数字 → `e` 是标识符的开头
        }
      }
      push("number", i, j);
      i = j;
      continue;
    }

    // ── 绑定参数 ? / :name / @name / $1 ──
    //    ⚠ 必须能接住「只有符号没有名字」的情况（裸 `?`、裸 `:`、裸 `@`）。
    //       之前裸 `@` 走了 `i++` 就继续，结果它**没被产出成 token** ——
    //       而高亮层是按 token 切片重建文本的，这个字符就凭空消失了。
    if (c === "?" || c === "$" || c === ":" || c === "@") {
      let j = i + 1;
      if (c !== "?") while (j < sql.length && isIdentPart(sql[j])) j++;
      else while (j < sql.length && isDigit(sql[j])) j++;
      push("param", i, j);
      i = j;
      continue;
    }

    // ── 配对符 ──
    if (PAIRS[c] && c !== '"') { push("punct", i, i + 1); i++; continue; }

    // ── 标识符 / 关键字 / 类型 / 函数 ──
    if (isIdentStart(c)) {
      let j = i + 1;
      while (j < sql.length && isIdentPart(sql[j])) j++;
      const text = sql.slice(i, j);
      const up = text.toUpperCase();

      // 后面紧跟 `(` → 函数调用（COUNT / json_extract / 自定义函数）
      let k = j;
      while (k < sql.length && sql[k] === " ") k++;
      if (sql[k] === "(") {
        push("func", i, j);
      } else if (KEYWORDS.has(up)) {
        push("keyword", i, j);
      } else if (TYPES.has(up)) {
        push("type", i, j);
      } else if (FUNCTIONS.has(up)) {
        push("func", i, j);
      } else {
        push("ident", i, j);
      }
      i = j;
      continue;
    }

    // ── 运算符（多字符优先：<> != >= <= || :: 等）──
    const two = sql.slice(i, i + 2);
    if (["<>", "!=", ">=", "<=", "||", "::", "->"].includes(two)) {
      push("operator", i, i + 2);
      i += 2;
      continue;
    }
    if ("=<>+-*/%|&^~!".includes(c)) { push("operator", i, i + 1); i++; continue; }

    // ── 标点 ──
    if (",;()".includes(c)) { push("punct", i, i + 1); i++; continue; }
    if (c === ".") { push("punct", i, i + 1); i++; continue; }

    // ── 其余：原样吐出并标成 unknown ──
    //    宁可显示成「认不出来」，也不静默丢掉 —— 丢掉会让高亮文本与原文不一致。
    push("unknown", i, i + 1);
    i++;
  }

  return out;
}

// ─────────────────────────────────────────────────────────────
// ⭐ 符号定位：光标处是什么对象
// ─────────────────────────────────────────────────────────────

/** 一个被引用的数据库对象 */
export interface SymbolRef {
  /** `table` / `column` / `alias` */
  kind: "table" | "column" | "alias";
  /** 裸名（不带引号、不带 schema 前缀） */
  name: string;
  /** 所属表（列才有；`alias` 指向它代表的表） */
  table?: string;
  /** 在原文里的位置 */
  start: number;
  end: number;
}

/** 从 FROM / JOIN 收集到的表与其别名 */
export interface TableScope {
  /** 表名 → 别名列表 */
  tables: Map<string, Set<string>>;
  /** 别名 → 真实表名 */
  aliases: Map<string, string>;
}

/**
 * ⭐⭐ 收集一条 SQL 引用到的表和别名。
 *
 * 认 `FROM x`、`JOIN x`、`INTO x`、`UPDATE x` 后面的标识符，
 * 以及紧随其后的 `AS 别名` 或裸别名。
 *
 * ⚠ **只认元数据里真实存在的表** —— 传进来的 `knownTables` 是白名单，
 *    不是「凡是像表名的东西都算」。否则 `SELECT count FROM t` 里的 `count`
 *    会被当成一张表，跳转时打开一张不存在的表。
 */
export function collectScope(sql: string, knownTables: Set<string>): TableScope {
  const scope: TableScope = { tables: new Map(), aliases: new Map() };
  const toks = tokenize(sql);
  const lower = (s: string) => s.toLowerCase();

  const addAlias = (t: string, a: string) => {
    const set = scope.tables.get(t) ?? new Set<string>();
    set.add(a);
    scope.tables.set(t, set);
    scope.aliases.set(lower(a), t);
  };

  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (t.kind !== "keyword") continue;
    const up = t.value.toUpperCase();
    if (!["FROM", "JOIN", "INTO", "UPDATE"].includes(up)) continue;

    // ⭐ 下一个非空白 token 必须是**元数据里真实存在的表名**。
    //   子查询 `FROM (SELECT …)` 也在这里被排除（下一个 token 是 `(`）。
    const next = toks[i + 1];
    if (!next || (next.kind !== "ident" && next.kind !== "quoted")) continue;

    // ⭐ `schema.table` / `db.table`：表名是**最后一段**。
    //   先按「只有一段」试，不成立再按「两段」试 —— 顺序反了的话
    //   `main.orders` 会被判成「表 main」，而 main 根本不在元数据里，
    //   于是整条语句的表引用全部丢失（跳转与 hover 同时失效）。
    let tbl = next.value;
    let after = i + 2;
    if (toks[i + 2]?.value === ".") {
      const seg = toks[i + 3];
      if (seg && (seg.kind === "ident" || seg.kind === "quoted")) {
        tbl = seg.value;
        after = i + 4;
      }
    }
    if (!knownTables.has(lower(tbl))) continue;
    addAlias(tbl, tbl);

    // 别名：`FROM users u` / `FROM users AS u`
    let k = after;
    if (toks[k]?.value.toUpperCase() === "AS") k++;
    const cand = toks[k];
    if (cand && cand.kind === "ident" && !KEYWORDS.has(cand.value.toUpperCase())) {
      addAlias(tbl, cand.value);
    }
  }
  return scope;
}

/**
 * ⭐⭐ 取光标偏移处的符号（Ctrl+Click 跳转与 hover 都用它）。
 *
 * 四种情况：
 *   1. 光标在**被引号包起来**的标识符里 → 直接当对象名（引号是最强的意图信号）
 *   2. 光标在 `alias.` 后面 → 列（归属先经别名解析成真实表）
 *   3. 光标在 FROM/JOIN 引用的表名或别名上 → 表
 *   4. 光标在**裸列名**上，且该列属于本次引用到的表 → 列
 *
 * ⚠ 第 4 条需要**列清单**才能判断，所以要多传一个 `columnsOf`。
 *   少了它，`SELECT status FROM orders` 里的 `status` 认不出来 ——
 *   而这正是最常见的写法（没有别名）。实测过：漏了这个参数，
 *   hover 列名**完全不弹提示**，而 hover 表名一切正常，
 *   很容易误判成「hover 功能坏了」。
 *
 * ⚠ 返回 `null` 就意味着「这里没有可跳转的对象」。
 *    UI 必须对此**明说**，不能假装跳了。
 */
export function symbolAt(
  sql: string,
  offset: number,
  knownTables: Set<string>,
  /** 表名 → 列名集合（**小写**）。查不到该表时返回 null 或空集即可 */
  columnsOf?: (table: string) => Set<string> | null,
): SymbolRef | null {
  const toks = tokenize(sql);

  // 找到包含 offset 的 token
  let hit: Token | null = null;
  for (const t of toks) {
    if (offset >= t.start && offset <= t.end) { hit = t; break; }
    if (t.start > offset) break;
  }
  if (!hit) return null;
  if (hit.kind !== "ident" && hit.kind !== "quoted") return null;

  const idx = toks.indexOf(hit);
  const lower = (s: string) => s.toLowerCase();
  const scope = collectScope(sql, knownTables);

  /** ⭐ 别名 → 真实表名。认不出就返回原样（宁可跳到别名也不跳到错的表） */
  const realTable = (a: string) => scope.aliases.get(lower(a)) ?? a;

  // 1. 引号包起来的：直接当表名（只有真实存在的表才给跳转，否则是拼写错误）
  if (hit.quoted) {
    if (knownTables.has(lower(hit.value))) {
      return { kind: "table", name: hit.value, start: hit.start, end: hit.end };
    }
    return null;
  }

  // 2. 前面是 `.` → 列（`o.status` / `orders.status`）
  const prev = toks[idx - 1];
  if (prev?.value === ".") {
    const owner = toks[idx - 2];
    if (owner && (owner.kind === "ident" || owner.kind === "quoted")) {
      // ⭐⭐ `table` 必须给**真实表名**，不能给别名。
      //   给别名的话 UI 会打开一张叫 `o` 的表 —— 不存在，于是报「表 o 不存在」，
      //   而用户明明写的是 `o.status`。这是「点列跳到不存在的表」的根因。
      return {
        kind: "column",
        name: hit.value,
        table: realTable(owner.value),
        alias: owner.value === realTable(owner.value) ? undefined : owner.value,
        start: hit.start,
        end: hit.end,
      };
    }
    return null;
  }

  // 3. 是不是 FROM/JOIN 后面那个（含别名）→ 表
  for (const [tbl, aliases] of scope.tables) {
    for (const a of aliases) {
      if (lower(a) === lower(hit.value)) {
        return { kind: "table", name: tbl, start: hit.start, end: hit.end };
      }
    }
  }

  // 4. 裸列名：`SELECT status FROM orders` 里的 status
  //    ⭐ 必须拿**列清单**来判断，光看别名表是认不出来的 ——
  //    「status」既不是表名也不是别名，但它是 orders 的列。
  if (columnsOf) {
    const lowerName = lower(hit.value);
    const owners: string[] = [];
    for (const [tbl] of scope.tables) {
      const cols = columnsOf(tbl);
      if (cols?.has(lowerName)) owners.push(tbl);
    }
    if (owners.length === 1) {
      return { kind: "column", name: hit.value, table: owners[0], start: hit.start, end: hit.end };
    }
    // ⭐ 同名列出现在多张表里 → **不猜**。
    //   猜错的话用户点一下跳到另一张表的同名列，看起来完全正常。
    if (owners.length > 1) {
      return { kind: "column", name: hit.value, owners, start: hit.start, end: hit.end };
    }
  }

  return null;
}

// ─────────────────────────────────────────────────────────────
// ⭐ 语句切分（多语句执行）
// ─────────────────────────────────────────────────────────────

export interface Statement {
  /** 第几条（0 基），UI 用来命名「结果 N」 */
  index: number;
  start: number;
  end: number;
  text: string;
  /** 该语句在原文里的行号（1 基），用于「结果来自第几行」 */
  line: number;
}

/**
 * ⭐⭐ 按分号切分语句。
 *
 * ⚠ **分号出现在字符串/注释里不算分隔符**。
 *    `SELECT 'a;b' FROM t` 是一条语句，不是两条 ——
 *    按裸分号切会生成 `SELECT 'a` 这种会报语法错的半条语句，
 *    而用户写的是对的。
 */
export function splitStatements(sql: string): Statement[] {
  const out: Statement[] = [];
  let start = 0;
  const toks = tokenize(sql);

  const push = (rawStart: number, rawEnd: number) => {
    const text = sql.slice(rawStart, rawEnd);
    if (!hasExecutableContent(text)) return;
    // ⭐ 起点跳过前导空白：不跳的话第 2 条语句的行号会算成上一条的分号所在行，
    //   「结果 2 来自第 1 行」—— 用户点进去看到的是上一条语句。
    const lead = rawStart + (text.length - text.trimStart().length);
    out.push({ index: out.length, start: lead, end: rawEnd, text: text.trim(), line: lineOf(sql, lead) });
  };

  for (const t of toks) {
    if (t.kind === "punct" && t.value === ";") {
      push(start, t.end);
      start = t.end;
    }
  }
  // 末尾没有分号的那条
  push(start, sql.length);
  return out;
}

/** ⭐ 语句里除了注释和空白，是否真有可执行内容 */
function hasExecutableContent(text: string): boolean {
  for (const t of tokenize(text)) {
    if (t.kind !== "comment" && t.kind !== "punct" && t.kind !== "operator") return true;
    if (t.kind === "punct" && t.value !== ";" && t.value !== ",") return true;
    if (t.kind === "operator" && t.value !== "*") return true;
  }
  return false;
}

/** 偏移 → 1 基行号 */
export function lineOf(sql: string, offset: number): number {
  let line = 1;
  for (let i = 0; i < offset && i < sql.length; i++) if (sql[i] === "\n") line++;
  return line;
}

/** ⭐ 取光标所在的整条语句（⌘⇧⏎「执行选中」在没有选中时用它） */
export function statementAtOffset(sql: string, offset: number): Statement | null {
  const list = splitStatements(sql);
  if (!list.length) return null;
  for (const s of list) if (offset >= s.start && offset <= s.end) return s;
  // 光标在末尾空白处 → 最后一条
  return list[list.length - 1];
}

// ─────────────────────────────────────────────────────────────
// ⭐ 编辑辅助（自动配对 / 选区处理）
// ─────────────────────────────────────────────────────────────

/** 该不该给这个字符配对。`)` 不配 —— 自动补右括号会让用户少打一个就多一个 */
export function shouldAutoPair(ch: string): boolean {
  return ch === "(" || ch === "[" || ch === "{" || ch === '"' || ch === "'";
}

/** 配对符号的另一半；不是可配对字符返回 null */
export function closerOf(ch: string): string | null {
  return PAIRS[ch] ?? null;
}

export function isCloser(ch: string): boolean {
  return CLOSERS.has(ch) || ch === '"' || ch === "'" || ch === "`";
}

/**
 * ⭐ 插入文本并返回新的光标位置。
 *
 * 处理三件事：
 *   1. 有选区 → 替换掉
 *   2. 在词中间插入 → 不粘上一个词（`sta|tus` 插 `x` 得 `stax t us` 是错的）
 *   3. 自动配对 → 补上另一半并把光标放中间
 */
export interface EditResult {
  text: string;
  caret: number;
  /** 实际替换掉的选区（用于插入配对符后整体删除） */
  replacedRange?: [number, number];
}

export function applyInsert(
  text: string,
  selStart: number,
  selEnd: number,
  insert: string,
  opts: { autoPair?: boolean } = {},
): EditResult {
  const head = text.slice(0, selStart);
  const tail = text.slice(selEnd);
  const range = selStart === selEnd ? undefined : ([selStart, selEnd] as [number, number]);

  // ⭐⭐ 先判「跳过闭合符」，再判「插入」。
  //   顺序反了的话 `count(|)` 会变成 `count())` —— 语法错来自编辑器，
  //   用户写的是对的。**合并成一个函数**（而不是让组件自己先试 skipOver
  //   再调 insert）是因为两处的判断条件一旦漂移，就会出现
  //   「有的键跳过、有的键不跳过」这种没法复现的行为。
  if (opts.autoPair && isCloser(insert) && text[selEnd] === insert && !range) {
    return { text, caret: selEnd + 1 };
  }

  if (opts.autoPair && shouldAutoPair(insert)) {
    const close = closerOf(insert)!;
    return { text: head + insert + close + tail, caret: selStart + insert.length, replacedRange: range };
  }

  return { text: head + insert + tail, caret: selStart + insert.length, replacedRange: range };
}

/**
 * ⭐ 跳过一个闭合符（独立入口，供只关心「跳过的键」的场景使用）。
 *
 * 实际编辑走 `applyInsert` —— 那里已经把跳过合并进去了，
 * 组件不应该需要判断「这个键该走哪条路」。
 */
export function applySkipOver(text: string, caret: number, ch: string): EditResult | null {
  if (!isCloser(ch)) return null;
  if (text[caret] !== ch) return null;
  return { text, caret: caret + 1 };
}

// ─────────────────────────────────────────────────────────────
// ⭐ 格式化
// ─────────────────────────────────────────────────────────────

/** 顶层子句：这些之前要换行 */
const CLAUSE_KEYWORDS = new Set([
  "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
  "VALUES", "SET", "UNION", "INTERSECT", "EXCEPT", "RETURNING",
]);

/** 连接类：这些之前要换行 */
const JOIN_KEYWORDS = new Set(["JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "OUTER"]);

/**
 * ⭐⭐ 真正的 SQL 格式化（不是「关键字大小写归一」）。
 *
 * 做四件事：
 *   1. 关键字统一大写
 *   2. 顶层子句各自一行：`SELECT …` / `FROM …` / `WHERE …`
 *   3. `FROM/JOIN` 的表与别名换行对齐：
 *      ```sql
 *      SELECT o.id, u.email
 *      FROM orders o
 *           JOIN users u ON u.id = o.user_id
 *      ```
 *   4. 括号内不换行（子查询保留用户原有换行）
 *
 * ⚠ **必须保留字符串与注释原文**：`--` 注释里如果被折行，语义就变了；
 *    字符串里的换行也是数据。两者一律原样搬运。
 */
export function formatSql(sql: string, indent = "  "): string {
  const toks = tokenize(sql);
  const lines: string[] = [];
  let cur = "";
  let depth = 0;
  let inSelectList = false;

  const flush = (extraIndent = 0) => {
    if (cur.trim()) lines.push(cur.replace(/\s+$/, ""));
    cur = "";
    void extraIndent;
  };
  const pad = (n: number) => indent.repeat(Math.max(0, n));

  /** ⭐ 子句关键字之前换行；`GROUP BY`/`ORDER BY` 的 BY 跟着上一行 */
  const breakBefore = (up: string, nextUp: string | undefined) => {
    if (depth > 0) return false;
    if (up === "BY" && (nextUp === undefined || true)) {
      // `GROUP BY` / `ORDER BY`：BY 紧跟前面的词，不换行
      return false;
    }
    if (CLAUSE_KEYWORDS.has(up) || JOIN_KEYWORDS.has(up)) return true;
    return false;
  };

  const isEndOfToken = (up: string, nextUp: string) =>
    // `GROUP BY` / `ORDER BY` 里，BY 前面的词也算子句起点
    (up === "GROUP" || up === "ORDER") && nextUp === "BY";

  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    const up = t.value.toUpperCase();
    const next = toks[i + 1];
    const nextUp = next?.value.toUpperCase();

    // ── 注释：整段搬过去，前面留一个空格 ──
    if (t.kind === "comment") {
      if (cur.trim()) cur += " ";
      cur += t.text;
      // 行注释后必须换行，否则后面的内容会被注释掉 —— 这是**语义改变**
      if (t.text.startsWith("--")) flush();
      continue;
    }

    if (t.kind === "keyword" && breakBefore(up, nextUp)) {
      flush();
      cur = pad(0);
      // `SELECT` 之后的选择列表单独缩进
      inSelectList = up === "SELECT";
      if (t.text !== up) cur = "";
      cur += up;
      continue;
    }

    if (t.kind === "keyword" && isEndOfToken(up, nextUp ?? "")) {
      cur += " ";
      cur += "BY";
      i++; // 吃掉 BY
      continue;
    }

    if (t.kind === "keyword" || t.kind === "type") {
      const word = up;
      if (breakBefore(up, nextUp)) { flush(); cur = pad(0); }
      else cur += " ";
      cur += word;
      continue;
    }

    // ── 括号 ──
    if (t.value === "(") {
      depth++;
      cur += "(";
      continue;
    }
    if (t.value === ")") {
      depth = Math.max(0, depth - 1);
      cur += ")";
      continue;
    }

    // ── 逗号：选择列表里换行，其它地方留空格 ──
    if (t.value === ",") {
      if (depth === 0 && inSelectList) {
        cur += ",";
        flush(pad(1));
        cur = pad(1);
      } else {
        cur += ", ";
      }
      continue;
    }

    if (t.value === ";") {
      cur += ";";
      flush();
      continue;
    }

    if (t.value === ".") { cur += "."; continue; }

    if (t.kind === "operator") {
      cur += ` ${t.text} `;
      continue;
    }

    // 标识符 / 字符串 / 数字 / 函数 / 引号标识符
    if (cur && !cur.endsWith(" ")) cur += " ";
    cur += t.kind === "string" || t.kind === "quoted" ? t.text : t.text;
  }
  flush();

  return lines.join("\n").replace(/[ \t]+$/gm, "");
}

// ─────────────────────────────────────────────────────────────
// HTML 转义（高亮层拼 innerHTML 用）
// ─────────────────────────────────────────────────────────────

const ESC: Record<string, string> = {
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
};

/** ⭐ 转义后再拼 HTML —— 用户输入的 SQL 绝不能直接进 innerHTML */
export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ESC[c]);
}

/**
 * ⭐⭐ 把原文渲染成带颜色的 HTML。
 *
 * ⚠ 做法是「**按原文顺序切片**」，而不是「拼接 token.text」——
 *    前者天然保证空白和未知字符一个不丢。
 *    空白全部原样输出，所以渲染出来的文本与用户写的一模一样。
 *
 * @param mark 可选的 `[start, end)` 区间（hover 中的符号），会被加上
 *   `.tk--mark`。⚠ **只允许用来高亮 token 内部**；
 *    区间若跨过 token 边界就整段忽略 —— 拆 token 会在词中间插标签，
 *   破坏「高亮文本 === 原文」这条不变量。
 */
export function highlightHtml(sql: string, tokens?: Token[], mark?: [number, number] | null): string {
  const toks = tokens ?? tokenize(sql);
  let out = "";
  let pos = 0;
  for (const t of toks) {
    if (t.start > pos) out += escapeHtml(sql.slice(pos, t.start));
    const marked = mark && t.start >= mark[0] && t.end <= mark[1];
    const cls = marked ? `tk tk--${t.kind} tk--mark` : `tk tk--${t.kind}`;
    out += `<span class="${cls}">${escapeHtml(t.text)}</span>`;
    pos = t.end;
  }
  if (pos < sql.length) out += escapeHtml(sql.slice(pos));
  // ⭐ 末尾补一个换行：textarea 里 `a\n` 会多显示一个空行，pre 不会
  return out + "\n";
}
