//! SQLite 驱动 —— 既是真实驱动，也是 UI 开发阶段的 Mock 载体。
//!
//! ⭐ **Mock 必须实现真实行为，只换数据来源**：
//!   · 真 SQL（完整语法、真优化器）
//!   · 真服务端分页（`LIMIT/OFFSET`，不是全量返回后切片）
//!   · 真元数据（`PRAGMA table_info` / `index_list`）
//!   · 真取消（`AtomicBool` + `spawn_blocking` 轮询）
//!   · 真事务
//! 省掉的只有「网络 / 凭据 / 协议编解码」三层。
//!
//! ⭐ 依赖显式传递：查询核心是自由函数 `run_query(conn, snap, cancelled, sql, opt)`，
//!   这样 async 与 blocking 两条路径共用同一份实现，且**不需要 unsafe**。

use adb_types::{
    ColumnDef, ColumnType, ConnectionType, Danger, Descriptor, IndexDef, SchemaSnapshot,
    TreeNode,
};
use rusqlite::Connection as Sqlite;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use crate::{demo, Cursor, Depth, Driver, Health, QueryOptions, Result, RowPage, Session};

// ─────────────────────────────────────────────────────────────
// Driver
// ─────────────────────────────────────────────────────────────

pub struct SqliteDriver {
    path: String,
    snapshot: Mutex<Option<Arc<SchemaSnapshot>>>,
}

impl SqliteDriver {
    pub fn new() -> Self {
        let path = demo::db_path();
        // ⭐ 首次运行自动创建演示库 —— 用户零操作
        if !std::path::Path::new(&path).exists() {
            demo::materialize(&path);
        }
        Self { path, snapshot: Mutex::new(None) }
    }

    pub fn db_path(&self) -> &str { &self.path }

    pub fn invalidate_snapshot(&self) {
        *self.snapshot.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    fn open_conn(&self) -> Result<Sqlite> {
        Sqlite::open(&self.path).map_err(|e| crate::AdbError::Connection(e.to_string()))
    }

    /// ⭐ 元数据以 OID 等价物（`table:col:name`）为键，从不比较名字字符串做身份判定
    fn load_snapshot(&self) -> Result<Arc<SchemaSnapshot>> {
        if let Some(s) = self.snapshot.lock().unwrap_or_else(|e| e.into_inner()).clone() {
            return Ok(s);
        }
        let c = self.open_conn()?;
        let snap = Arc::new(SchemaSnapshot {
            db_id: self.path.clone(),
            taken_at: unix_stamp(),
            tables: demo::introspect(&c),
        });
        *self.snapshot.lock().unwrap_or_else(|e| e.into_inner()) = Some(snap.clone());
        Ok(snap)
    }
}

impl Default for SqliteDriver {
    fn default() -> Self { Self::new() }
}

fn unix_stamp() -> String {
    let s = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("unix:{s}")
}

#[async_trait::async_trait]
impl Driver for SqliteDriver {
    fn kind(&self) -> ConnectionType { ConnectionType::Sqlite }

    fn descriptor(&self) -> &'static Descriptor { adb_types::descriptor(ConnectionType::Sqlite) }

    async fn open(&self) -> Result<Box<dyn Session>> {
        Ok(Box::new(SqliteSession {
            conn: Arc::new(Mutex::new(self.open_conn()?)),
            snapshot: self.load_snapshot()?,
            cancelled: Arc::new(AtomicBool::new(false)),
            txn_open: Arc::new(AtomicBool::new(false)),
            cancel: tokio::sync::watch::channel(false).0,
        }))
    }
}

// ─────────────────────────────────────────────────────────────
// Session
// ─────────────────────────────────────────────────────────────

pub struct SqliteSession {
    conn: Arc<Mutex<Sqlite>>,
    snapshot: Arc<SchemaSnapshot>,
    cancelled: Arc<AtomicBool>,
    txn_open: Arc<AtomicBool>,
    cancel: tokio::sync::watch::Sender<bool>,
}

impl SqliteSession {
    fn lock(&self) -> MutexGuard<'_, Sqlite> {
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[async_trait::async_trait]
impl Session for SqliteSession {
    fn kind(&self) -> ConnectionType { ConnectionType::Sqlite }

    fn query_blocking(&self, sql: &str, opt: QueryOptions, _danger: Danger) -> Result<RowPage> {
        let guard = self.lock();
        run_query(&guard, &self.snapshot, &self.cancelled, sql, opt)
    }

    fn cancel_blocking(&self) -> Result<()> {
        self.cancelled.store(true, Ordering::SeqCst);
        let _ = self.cancel.send(true);
        Ok(())
    }

    /// ⭐ 真实写入：`query` 对写语句一律回滚，这里才真正落库。
    ///
    /// ⚠ 只接受 **mutating**（INSERT/UPDATE/DELETE…WHERE）。
    ///    destructive（DROP/TRUNCATE/无 WHERE 的 DELETE）**一律拒绝** ——
    ///    那类操作必须走变更管控流程（备份 + 审批 + 回滚脚本），
    ///    不能从数据网格里点一下就没了。
    ///
    ///    权限判断由调用方的 `analyze()` 负责，这里再做一次：
    ///    ⭐ 纵深防御 —— 万一调用方漏判，驱动层是最后一道闸。
    fn execute_blocking(&self, sql: &str, danger: Danger) -> crate::Result<u64> {
        if danger == Danger::ReadOnly {
            return Err(crate::AdbError::Internal(
                "execute 不接受只读语句 —— 那是 query 的职责".into(),
            ));
        }
        if danger == Danger::Destructive || danger == Danger::Admin {
            return Err(crate::AdbError::Internal(format!(
                "拒绝执行 {} 级语句：这类操作必须走变更管控流程",
                danger.label()
            )));
        }

        let guard = self.lock();
        guard.execute_batch(sql).map_err(|e| {
            crate::query_error(
                sql,
                &strip_sqlite_prefix(&e.to_string()),
                &table_names(self.snapshot.as_ref()),
            )
        })?;
        Ok(guard.changes())
    }

    async fn query(&self, sql: &str, opt: QueryOptions, _d: Danger) -> Result<RowPage> {
        // ⭐ owned 数据移进 spawn_blocking：不用 unsafe，也不持锁跨 await
        let conn = Arc::clone(&self.conn);
        let snap = Arc::clone(&self.snapshot);
        let cancelled = Arc::clone(&self.cancelled);
        let sql = sql.to_string();
        tokio::task::spawn_blocking(move || {
            let g = conn.lock().unwrap_or_else(|e| e.into_inner());
            run_query(&g, &snap, &cancelled, &sql, opt)
        })
        .await
        .map_err(|_| crate::AdbError::Internal("query task panicked".into()))?
    }

    async fn cancel(&self) -> Result<()> { self.cancel_blocking() }

    async fn begin(&self) -> Result<()> {
        self.lock().execute_batch("BEGIN").map_err(|e| crate::AdbError::Internal(e.to_string()))?;
        self.txn_open.store(true, Ordering::SeqCst);
        Ok(())
    }

    async fn commit(&self) -> Result<()> {
        self.lock().execute_batch("COMMIT").map_err(|e| crate::AdbError::Internal(e.to_string()))?;
        self.txn_open.store(false, Ordering::SeqCst);
        Ok(())
    }

    async fn rollback(&self) -> Result<()> {
        let _ = self.lock().execute_batch("ROLLBACK");
        self.txn_open.store(false, Ordering::SeqCst);
        Ok(())
    }

    async fn heartbeat(&self) -> Result<()> {
        self.lock()
            .query_row("SELECT 1", [], |_| Ok(()))
            .map_err(|e| crate::AdbError::Connection(e.to_string()))?;
        Ok(())
    }

    fn health(&self) -> Health {
        if self.cancelled.load(Ordering::Relaxed) { Health::Stale } else { Health::Healthy }
    }

    fn snapshot(&self) -> Arc<SchemaSnapshot> { Arc::clone(&self.snapshot) }
}

// ─────────────────────────────────────────────────────────────
// 查询核心
// ─────────────────────────────────────────────────────────────

fn run_query(
    conn: &Sqlite,
    snap: &SchemaSnapshot,
    cancelled: &AtomicBool,
    sql: &str,
    opt: QueryOptions,
) -> Result<RowPage> {
    let t0 = std::time::Instant::now();
    let known = table_names(snap);

    if cancelled.load(Ordering::Relaxed) {
        return Err(crate::AdbError::Cancelled);
    }

    // ⭐ 危险分级由后端算，前端传什么都不作数
    let danger = crate::analyze(sql);
    if danger != Danger::ReadOnly {
        // 写语句：先在事务里验证能过，再回滚（dry-run）
        let _ = conn.execute_batch("BEGIN IMMEDIATE");
        let r = conn.execute_batch(sql);
        let _ = conn.execute_batch("ROLLBACK");
        r.map_err(|e| crate::query_error(sql, &e.to_string(), &known))?;
        return Ok(RowPage {
            columns: Vec::new(),
            rows: Vec::new(),
            next_cursor: None,
            total: Some(conn.changes() as i64),
            duration_ms: t0.elapsed().as_millis() as u64,
            truncated_columns: Vec::new(),
            mutated: true,
        });
    }

    let limit = opt.max_rows.clamp(1, 100_000) as i64;
    let offset = opt.cursor.map(|c| c.0).unwrap_or(0);

    // 探针：拿到列名，同时暴露语法/表名错误
    {
        let probe = conn.prepare(sql).map_err(|e| {
            crate::query_error(sql, &strip_sqlite_prefix(&e.to_string()), &known)
        })?;
        let _ = probe.column_count();
    }

    // ⭐ 真服务端分页：LIMIT/OFFSET 下推到 SQL 引擎
    let paged_sql = format!("SELECT * FROM ({sql}) LIMIT {limit} OFFSET {offset}");
    let mut stmt = conn
        .prepare(&paged_sql)
        .map_err(|e| crate::query_error(sql, &strip_sqlite_prefix(&e.to_string()), &known))?;

    let names: Vec<String> =
        stmt.column_names().into_iter().map(str::to_string).collect();

    let mut rows = Vec::with_capacity(names.len().min(4096));
    {
        let mut qr = stmt
            .query([])
            .map_err(|e| crate::AdbError::Internal(e.to_string()))?;
        while let Some(row) = qr.next().map_err(|e| crate::AdbError::Internal(e.to_string()))? {
            let mut o = serde_json::Map::with_capacity(names.len());
            for (i, key) in names.iter().enumerate() {
                o.insert(key.clone(), cell_value(row.get_ref(i).ok()));
            }
            rows.push(serde_json::Value::Object(o));
            if cancelled.load(Ordering::Relaxed) {
                return Err(crate::AdbError::Cancelled);
            }
        }
    }

    // 总行数：只在能证明结果集 = 整张基表时才给值，其余 None（UI 显示「未估算」）
    let total = estimate_total(sql, snap);

    let next = if rows.len() as i64 == limit { Some(Cursor(offset + limit)) } else { None };

    // ⭐ 列元数据只在本查询引用到的表里找（见 build_columns 的注释）
    let referenced = referenced_tables(sql, snap);
    let cols = build_columns(&names, &referenced);

    // ⭐ 大字段标记：UI 据此决定延迟加载。同样只在被引用的表里找 ——
    //    否则任意表里一个同名的大字段都会让当前列被标成「大字段」。
    let truncated_columns: Vec<String> = referenced
        .iter()
        .flat_map(|t| t.columns.iter())
        .filter(|c| c.is_large && names.contains(&c.name))
        .map(|c| c.name.clone())
        .collect();

    Ok(RowPage {
        columns: cols,
        rows,
        next_cursor: next,
        total,
        duration_ms: t0.elapsed().as_millis() as u64,
        truncated_columns,
        mutated: false,
    })
}

/// ⭐ 从查询里提取它真正引用到的表（`FROM` / `JOIN` 后面那个）。
///
/// 存在的理由见 [`build_columns`]：列元数据必须只在**这条查询涉及**的表里找，
/// 否则 JOIN 出来的 `name` 会被安到同名的别的表头上。
pub(crate) fn referenced_tables<'a>(sql: &str, snap: &'a SchemaSnapshot) -> Vec<&'a adb_types::TableDef> {
    let up = sql.to_uppercase();
    let mut out = Vec::new();
    for t in &snap.tables {
        // 表名在 SQL 里出现过即视为被引用。
        // 粗但够用：宁可多收，也比漏收后把列认错表强。
        let quoted = format!("\"{}\"", t.name.to_uppercase());
        let bare = t.name.to_uppercase();
        if up.contains(&quoted) || contains_word(&up, &bare) {
            out.push(t);
        }
    }
    out
}

/// ⭐ 把查询结果的列名还原成元数据。
///
/// ⭐ **只在本次查询引用到的表里找列**。
/// 之前是 `snap.tables.iter().flat_map(..).find(|c| c.name == name)` ——
/// 在**全库**里按列名找第一个匹配，于是
/// ```text
/// SELECT o.order_no, u.name FROM orders o JOIN users u ...
/// ```
/// 里的 `name` 会被安成 `categories.name`（注释「分类名」），
/// 因为 categories 在字母序上排在 users 前面。
/// 这不只是注释错了 —— 类型、是否可空、是否主键全都是错的，
/// 而且用户完全看不出来。这正是 DEV-SPEC 禁止的「按名字猜身份」。
///
/// 仍然找不到时返回 `expr:`（未知列），而不是随便挑一个同名列：
/// **宁可少给信息，也不能给错信息**。
pub(crate) fn build_columns(names: &[String], referenced: &[&adb_types::TableDef]) -> Vec<ColumnDef> {
    names
        .iter()
        .enumerate()
        .map(|(i, name)| {
            let def = referenced
                .iter()
                .flat_map(|t| t.columns.iter())
                .find(|c| &c.name == name);
            match def {
                Some(d) => ColumnDef {
                    id: d.id.clone(),
                    name: d.name.clone(),
                    ty: d.ty.clone(),
                    category: d.category,
                    nullable: d.nullable,
                    default_expr: d.default_expr.clone(),
                    comment: d.comment.clone(),
                    is_pk: d.is_pk,
                    is_fk: d.is_fk,
                    is_large: d.is_large,
                    enum_values: d.enum_values.clone(),
                    ord: i as i64,
                },
                None => ColumnDef {
                    id: format!("expr:{name}"),
                    name: name.clone(),
                    ty: "—".into(),
                    category: ColumnType::Unknown,
                    nullable: true,
                    default_expr: None,
                    comment: None,
                    is_pk: false,
                    is_fk: false,
                    is_large: false,
                    enum_values: None,
                    ord: i as i64,
                },
            }
        })
        .collect()
}

/// ⭐ 只在**能证明结果集恰好等于某张基表的全部行**时才给出 total，其余返回 `None`。
///
/// 为什么必须这么严：之前这里是「找到第一个 ` FROM `，对它做 COUNT」，
/// 于是
/// ```text
/// WITH paid AS (SELECT * FROM orders WHERE status=2) SELECT COUNT(*) FROM paid
/// ```
/// 会去数 `orders`（5000 行），而这条查询实际只返回 **1** 行。
/// UI 把它当权威值显示成「共 5000 行」—— 一个**撒谎**的总行数比没有总行数危险得多，
/// 用户会拿它做判断。宁可显示「未估算」。
///
/// 判定规则（`SELECT * FROM <单表>` 且**后面什么都没有**）：
///   · 必须以 `SELECT` 开头 → `WITH ...`（CTE）直接排除
///   · 取**最后一个** ` FROM ` 之后的标识符
///   · 标识符之后必须为空 → 有 `WHERE` / `JOIN` / `GROUP BY` / `LIMIT` 一律排除
///   · 选择列表里不能有 `(` → 聚合函数（`COUNT(*)`）与子查询一律排除
///   · 必须是快照里的**真实表** → 视图 / 虚拟表不计数（大视图会全表扫）
pub(crate) fn estimate_total(sql: &str, snap: &SchemaSnapshot) -> Option<i64> {
    let s = sql.trim().trim_end_matches(';').trim();
    let up = s.to_uppercase();
    if !up.starts_with("SELECT ") {
        return None; // CTE / INSERT / PRAGMA / EXPLAIN …
    }

    // ⭐ 集合运算：结果集 = 若干分支的并/交/差，与任一基表的行数都无关。
    //    用词边界匹配，避免把 `union_count` 这种列名误判。
    for op in ["UNION", "INTERSECT", "EXCEPT"] {
        if contains_word(&up, op) {
            return None;
        }
    }

    // ⭐ DISTINCT 会去重，行数 = 去重后的值数量，与基表行数无关
    if up.starts_with("SELECT DISTINCT ") || up.starts_with("SELECT  DISTINCT ") {
        return None;
    }

    // 取**最后一个** FROM：CTE 与嵌套子查询里的 FROM 都在更前面，会被自然跳过
    let from_at = up.rfind(" FROM ")?;
    let select_list = s[6..from_at].trim();

    // 选择列表含括号 = 聚合函数或子查询，结果集行数不由基表行数决定
    if select_list.contains('(') || select_list.contains(')') {
        return None;
    }

    let after = s[from_at + 6..].trim();
    let ident: String = after
        .chars()
        .take_while(|c| !matches!(c, ' ' | '\n' | '\t' | '\r' | '(' | ';' | ','))
        .collect();
    // 标识符后面还有东西 = 有 WHERE/JOIN/ORDER BY/LIMIT 等修饰，行数不固定
    if ident.is_empty() || ident.len() != after.len() {
        return None;
    }

    let cleaned = ident
        .trim_matches(|c| c == '"' || c == '`' || c == '[' || c == ']')
        .to_string();
    let tbl = cleaned
        .rsplit('.')
        .next()
        .unwrap_or(&cleaned)
        .trim_matches('"')
        .to_string();
    if tbl.is_empty() {
        return None;
    }

    // ⭐ 视图 / 虚拟表不计数：它们没有可信的行数，且 COUNT 会全表扫。
    //    行数直接取快照里 materialize 时算好的值，避免每次查询都重扫一遍。
    snap.tables
        .iter()
        .find(|t| t.kind == "table" && t.name.eq_ignore_ascii_case(&tbl))
        .and_then(|t| t.row_estimate)
}

/// ⭐ 在**已大写**的串里按词边界找关键字（两侧不能是标识符字符）。
///    不这样做的话 `SELECT union_count FROM t` 会被误判成集合运算。
fn contains_word(haystack_upper: &str, word: &str) -> bool {
    let bytes = haystack_upper.as_bytes();
    let w = word.as_bytes();
    let is_ident = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    bytes.windows(w.len()).enumerate().any(|(i, win)| {
        win == w
            && i.checked_sub(1).map_or(true, |p| !is_ident(bytes[p]))
            && bytes.get(i + w.len()).map_or(true, |&n| !is_ident(n))
    })
}

/// SQLite 的错误前缀对用户没意义，去掉
fn strip_sqlite_prefix(msg: &str) -> String {
    msg.strip_prefix("SQLITE_ERROR: ").unwrap_or(msg).to_string()
}

fn table_names(snap: &SchemaSnapshot) -> Vec<String> {
    snap.tables.iter().map(|t| t.name.clone()).collect()
}

fn cell_value(v: Option<rusqlite::types::ValueRef<'_>>) -> serde_json::Value {
    use rusqlite::types::ValueRef as V;
    match v {
        None | Some(V::Null) => serde_json::Value::Null,
        Some(V::Integer(i)) => serde_json::Value::from(i),
        Some(V::Real(f)) => serde_json::Value::from(f),
        Some(V::Text(t)) => serde_json::Value::from(String::from_utf8_lossy(t).to_string()),
        Some(V::Blob(b)) => serde_json::Value::from(format!("⟨binary⟩ {} B", b.len())),
    }
}

// ─────────────────────────────────────────────────────────────
// 对象树（懒加载）
// ─────────────────────────────────────────────────────────────

/// ⭐ 只在需要时构造节点，绝不预构建全树
pub fn tree_nodes(snap: &SchemaSnapshot, parent: Option<&str>, depth: Depth) -> Vec<TreeNode> {
    match parent {
        None => vec![TreeNode {
            id: format!("db:{}", snap.db_id),
            parent: None,
            kind: "database".into(),
            label: demo::db_display_name(&snap.db_id),
            comment: Some("AgenticDB 演示数据库 · 电商场景".into()),
            meta: Some(format!("{} 张表", snap.tables.len())),
            has_children: true,
            loaded: true,
            risk_badge: None,
            enum_values: None,
        }],
        Some(pid) if pid.starts_with("db:") => snap
            .tables
            .iter()
            .map(|t| {
                let risk = demo::diagnostics(t).len() as u32;
                TreeNode {
                    id: format!("tbl:{}", t.id),
                    parent: Some(pid.to_string()),
                    kind: t.kind.clone(),
                    label: t.name.clone(),
                    comment: t.comment.clone(),
                    meta: t.row_estimate.map(compact),
                    has_children: true,
                    loaded: false,
                    risk_badge: (risk > 0).then_some(risk),
                    enum_values: None,
                }
            })
            .collect(),
        Some(pid) if pid.starts_with("tbl:") => {
            let tid = pid.trim_start_matches("tbl:");
            let Some(t) = snap.tables.iter().find(|t| t.id == tid) else {
                return Vec::new();
            };
            let mut out: Vec<TreeNode> = t
                .columns
                .iter()
                .map(|c| TreeNode {
                    id: format!("colnode:{}", c.id),
                    parent: Some(pid.to_string()),
                    kind: "column".into(),
                    label: c.name.clone(),
                    comment: c.comment.clone(),
                    meta: Some(c.ty.clone()),
                    has_children: false,
                    loaded: true,
                    risk_badge: None,
                    enum_values: c.enum_values.clone(),
                })
                .collect();
            if depth == Depth::Detail {
                out.extend(t.indexes.iter().map(|i: &IndexDef| TreeNode {
                    id: format!("idx:{}", i.id),
                    parent: Some(pid.to_string()),
                    kind: "index".into(),
                    label: i.name.clone(),
                    comment: None,
                    meta: Some(
                        if i.primary { "PK".into() }
                        else if i.unique { "UNIQUE".into() }
                        else { String::new() },
                    ),
                    has_children: false,
                    loaded: true,
                    risk_badge: None,
                    enum_values: None,
                }));
            }
            out
        }
        _ => Vec::new(),
    }
}

pub fn compact(n: i64) -> String {
    let n = n as f64;
    if n >= 1e8 { format!("{:.1}亿", n / 1e8) }
    else if n >= 1e4 { format!("{:.1}万", n / 1e4) }
    else { format!("{n}") }
}
