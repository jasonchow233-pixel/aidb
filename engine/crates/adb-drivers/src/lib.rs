//! `adb-drivers` —— 驱动层。
//!
//! v4 架构：**facade + per-driver**（本文件是 facade，trait 定义在这里）。
//! 当前已实现 `driver-sqlite`：内嵌 SQLite，既是真实驱动也是 UI 阶段的
//! Mock 载体 —— 真 SQL、真分页、真取消，只把「网络/凭据」这两层省掉。

use adb_types::{
    ColumnDef, ConnectionType, Danger, Descriptor, QueryError, SchemaSnapshot, SqlPosition,
};

pub mod error;
pub mod sqlite;
pub mod demo;
pub mod faults;

pub use error::{AdbError, Result};
pub use sqlite::SqliteDriver;

// ─────────────────────────────────────────────────────────────
// Trait 定义（facade 集中定义，实现分散在 per-driver crate）
// ─────────────────────────────────────────────────────────────

/// ⭐ 懒加载深度 —— 侧栏性能的核心。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Depth {
    /// 只取 id / name / type / comment（侧栏第一层）
    Name,
    /// + 列、类型、null、默认值、主键
    Structure,
    /// + 索引、外键、统计
    Detail,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(transparent)]
pub struct Cursor(pub i64);

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ReadMode { Paged }

#[derive(Debug, Clone)]
pub struct QueryOptions {
    pub max_rows: usize,
    pub cursor: Option<Cursor>,
    pub mode: ReadMode,
}

impl Default for QueryOptions {
    fn default() -> Self {
        Self { max_rows: 1000, cursor: None, mode: ReadMode::Paged }
    }
}

/// ⭐ 结果永远是分页的。禁止一次性拉全表。
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RowPage {
    pub columns: Vec<ColumnDef>,
    pub rows: Vec<serde_json::Value>,
    pub next_cursor: Option<Cursor>,
    /// ⭐ 估算总行数。`None` 表示未执行 COUNT（避免大表全扫）
    pub total: Option<i64>,
    pub duration_ms: u64,
    pub truncated_columns: Vec<String>,
    /// ⭐ 写语句的结果（影响行数），读语句为 false
    pub mutated: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Health {
    Healthy,
    Stale,
    Dead,
}

#[derive(Debug, Clone)]
pub struct CancellationToken(pub tokio::sync::watch::Receiver<bool>);

#[async_trait::async_trait]
pub trait Session: Send + Sync {
    fn kind(&self) -> ConnectionType;

    /// ⭐ 同步版本。rusqlite 等同步驱动用它；web 层放进 `spawn_blocking`。
    ///    持 MutexGuard 跨 await 会破坏 axum handler 的 Send 要求。
    fn query_blocking(&self, sql: &str, opt: QueryOptions, danger: Danger) -> Result<RowPage>;
    fn cancel_blocking(&self) -> Result<()>;

    /// ⭐ 统一返回分页结果。`sql` 必须已经通过风险分析。
    async fn query(
        &self,
        sql: &str,
        opt: QueryOptions,
        danger: Danger,
    ) -> Result<RowPage>;

    /// ⭐⭐ **真实写入**（落库），返回受影响行数。
    ///
    /// 为什么与 `query` 分开，而不是给它加个开关：
    /// `query` 对所有写语句一律**事务内 dry-run 后回滚**，那是演示模式的安全网；
    /// 一旦用参数绕过，漏传一个参数就是**静默丢数据**。
    /// 拆成两个方法后，「会落库」这件事在类型签名上就是显式的，
    /// 每个调用点都必须面对它。
    ///
    /// ⚠ 调用方**必须**先经 `analyze()` 分级并向用户确认；
    ///    且不得用于 destructive 语句（该走变更管控流程）。
    fn execute_blocking(&self, sql: &str, danger: Danger) -> Result<u64> {
        // 默认实现是安全的：驱动没实现真实写入就明确报错，
        // 而不是悄悄回滚让调用方以为写成功了。
        Err(AdbError::Internal(format!(
            "{} 驱动尚未实现真实写入",
            danger.label()
        )))
    }

    /// ⭐⭐ 执行计划（EXPLAIN）—— 「这条查询会不会扫全表」的权威答案。
    ///
    /// 为什么必须单独一个方法，不能让前端拼 `EXPLAIN ` 前缀丢给 `query_blocking`：
    ///   `query_blocking` 会把语句包进 `SELECT * FROM (原句) LIMIT n OFFSET m`，
    ///   于是 `EXPLAIN QUERY PLAN SELECT ...` 变成
    ///   `SELECT * FROM (EXPLAIN QUERY PLAN SELECT ...) LIMIT ...`
    ///   → `near "PLAN": syntax error`。
    ///   即「用户点执行计划 → 报语法错」，而且**错在用户没写错的地方**，
    ///   排查成本极高。
    ///
    /// ⚠ 驱动没实现时**明确报不支持**，不返回空计划 ——
    ///    一个空的执行计划会被 UI 显示成「没有全表扫描」，那是撒谎。
    fn explain_blocking(&self, _sql: &str) -> Result<RowPage> {
        Err(AdbError::Internal("该驱动尚未实现执行计划".into()))
    }

    /// ⭐ 取消必须幂等。取消后**绝不自动重放**。
    async fn cancel(&self) -> Result<()>;

    async fn begin(&self) -> Result<()>;
    async fn commit(&self) -> Result<()>;
    async fn rollback(&self) -> Result<()>;

    async fn heartbeat(&self) -> Result<()>;
    fn health(&self) -> Health;
    fn snapshot(&self) -> std::sync::Arc<SchemaSnapshot>;
}

#[async_trait::async_trait]
pub trait Driver: Send + Sync {
    fn kind(&self) -> ConnectionType;
    fn descriptor(&self) -> &'static Descriptor;
    async fn open(&self) -> Result<Box<dyn Session>>;
}

// ─────────────────────────────────────────────────────────────
// 注册表
// ─────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error)]
pub enum RegistryError {
    #[error("未知连接类型 '{0}'（检查 declarations/connections/）")]
    UnknownConnection(String),
    #[error("连接类型 '{0}' 未编译进当前构建（feature 未开启）")]
    NotCompiled(String),
    #[error("连接类型 '{0}' 存在声明，但当前没有驱动实现")]
    NoDriver(String),
}

pub type DriverFactory = fn() -> Box<dyn Driver + 'static>;

static REGISTRY: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<ConnectionType, DriverFactory>>> =
    std::sync::OnceLock::new();

fn registry() -> &'static std::sync::Mutex<std::collections::HashMap<ConnectionType, DriverFactory>> {
    REGISTRY.get_or_init(|| {
        fn sqlite_factory() -> Box<dyn Driver + 'static> { Box::new(SqliteDriver::new()) }
        let mut m: std::collections::HashMap<ConnectionType, DriverFactory> =
            std::collections::HashMap::new();
        m.insert(ConnectionType::Sqlite, sqlite_factory as DriverFactory);
        std::sync::Mutex::new(m)
    })
}

/// ⭐ 后注册接口：上层 crate 可在启动时追加驱动实现。
///    这样 facade 不依赖 per-driver crate，避免循环依赖。
pub fn register(t: ConnectionType, factory: DriverFactory) {
    registry().lock().unwrap_or_else(|e| e.into_inner()).insert(t, factory);
}

pub fn create(t: ConnectionType) -> std::result::Result<Box<dyn Driver>, RegistryError> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    match reg.get(&t) {
        Some(f) => Ok(f()),
        None => {
            let declared = adb_types::all_descriptors().iter().any(|d| d.id == t.as_str());
            Err(if declared {
                RegistryError::NotCompiled(t.as_str().into())
            } else {
                RegistryError::UnknownConnection(t.as_str().into())
            })
        }
    }
}

pub fn registered() -> Vec<ConnectionType> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let mut v: Vec<_> = reg.keys().copied().collect();
    v.sort_by_key(|t| t.as_str());
    v
}

// ─────────────────────────────────────────────────────────────
// ⭐ SQL 风险分析 —— 危险分级的数据来源
// ─────────────────────────────────────────────────────────────

/// ⭐ 这是 `Danger` 的唯一来源。所有写操作必须先过这里。
pub fn analyze(sql: &str) -> Danger {
    let up = sql.trim().to_uppercase();

    // ⭐ 多语句优先：取所有语句里最危险的那条。
    //    必须放在单语句 head 匹配之前，否则 "SELECT 1; DROP TABLE t" 会被
    //    第一条 SELECT 判成 ReadOnly（L2 安全漏洞）。
    if up.contains(';') {
        let mut worst = Danger::ReadOnly;
        for stmt in sql.split(';') {
            if stmt.trim().is_empty() { continue; }
            let d = analyze_single(stmt);
            if (d as u8) > (worst as u8) { worst = d; }
        }
        return worst;
    }

    analyze_single(sql)
}

fn analyze_single(sql: &str) -> Danger {
    let up = sql.trim().to_uppercase();
    let head = up
        .split(|c: char| c.is_whitespace() || c == '(' || c == ';')
        .find(|t| !t.is_empty())
        .unwrap_or("");

    match head {
        "SELECT" | "PRAGMA" | "EXPLAIN" | "WITH" | "VALUES" | "SHOW" | "DESC" | "DESCRIBE" => {
            Danger::ReadOnly
        }
        "INSERT" | "UPDATE" | "REPLACE" => Danger::Mutating,
        "DELETE" => {
            // ⭐ DELETE 无 WHERE 视为破坏性
            if up.contains(" WHERE ") {
                Danger::Mutating
            } else {
                Danger::Destructive
            }
        }
        "CREATE" | "ALTER" | "DROP" | "TRUNCATE" | "RENAME" | "VACUUM" | "REINDEX" => {
            if up.contains(" TEMP ") || up.contains("TEMPORARY") {
                Danger::Mutating
            } else {
                Danger::Destructive
            }
        }
        "GRANT" | "REVOKE" | "ATTACH" | "DETACH" => Danger::Admin,
        _ => Danger::ReadOnly,
    }
}

// ─────────────────────────────────────────────────────────────
// ⭐ 错误定位 —— UI 点击错误跳转的来源
// ─────────────────────────────────────────────────────────────

/// ⭐⭐ 从 SQLite 错误里提取位置 → 第 N 行第 M 列。
///
/// SQLite 的错误文本有三种形态，**每一种都要单独处理**：
/// ```text
/// 1. no such table: oder
/// 2. no such column: x near "FROM oder"
/// 3. no such column: nope in SELECT nope FROM orders at offset 7   ← 实际最常见
/// ```
///
/// ⭐⭐ 形态 3 之前被解析错了：代码把 `strip_prefix("no such column: ")`
///   之后的**整段**当成了标识符，于是
///   ```text
///   ident = "nope in SELECT nope FROM orders at offset 7"
///   sql.find(ident) → None → unwrap_or(0)
///   ```
///   结果是 **`line:1 column:1 length:75`** —— 前端点「跳转到错误位置」
///   跳到句首，用户完全不知道自己在看哪里。
///
///   而 `no such table` 是能正确定位的（形态 1 没有后缀），
///   所以这个 bug 只在**写错列名**时出现 —— 恰好是最常见的一类手误。
pub fn locate(sql: &str, err_msg: &str) -> Option<SqlPosition> {
    let ident = if let Some(rest) = err_msg.split("near \"").nth(1) {
        rest.split('"').next()?.trim()
    } else if let Some(rest) = err_msg.strip_prefix("no such table: ") {
        // 表名后面不带任何东西
        rest.trim()
    } else if let Some(rest) = err_msg.strip_prefix("no such column: ") {
        // ⭐⭐ 形态 3：截到 ` in ` / ` at offset ` 为止。
        //   列名本身不含这两个分隔符（SQL 标识符允许它们出现在引号里，
        //   而那种写法 SQLite 报的是形态 2）。
        let head = rest.split(" in ").next().unwrap_or(rest);
        head.split(" at offset ").next().unwrap_or(head).trim()
    } else {
        return None;
    };

    if ident.is_empty() {
        return None;
    }

    // ⭐ 找不到就**不给位置**，而不是给 (1,1)。
    //   (1,1) 是个看起来合法、实际完全错误的答案：
    //   前端会照着跳，用户会以为「定位不准」而放弃这个功能。
    //   没有位置时前端显示「无位置信息」，那才是诚实的。
    let offset = sql.find(ident)?;

    let prefix = &sql[..offset];
    let line = prefix.matches('\n').count() as u32 + 1;
    let column = prefix
        .rsplit('\n')
        .next()
        .map(|l| l.chars().count() as u32 + 1)
        .unwrap_or(1);

    Some(SqlPosition {
        line,
        column,
        offset,
        length: ident.chars().count(),
    })
}

/// ⭐ 相似对象建议 —— `#5581` 的做法比只跳转值钱
pub fn similar_names(input: &str, candidates: &[String], limit: usize) -> Vec<String> {
    let target: Vec<char> = input.to_lowercase().chars().collect();
    let mut scored: Vec<(usize, &String)> = candidates
        .iter()
        .filter_map(|c| {
            let lc: Vec<char> = c.to_lowercase().chars().collect();
            let d = levenshtein(&target, &lc);
            let close = lc.starts_with(&target) || target.starts_with(&lc);
            if d <= 2.max(target.len() / 2) || close {
                Some((d, c))
            } else {
                None
            }
        })
        .collect();
    scored.sort_by_key(|(d, _)| *d);
    scored.into_iter().take(limit).map(|(_, s)| s.clone()).collect()
}

fn levenshtein(a: &[char], b: &[char]) -> usize {
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    let mut cur = vec![0usize; b.len() + 1];
    for i in 1..=a.len() {
        cur[0] = i;
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            cur[j] = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[b.len()]
}

/// ⭐ 构造带位置和相似名建议的查询错误
pub fn query_error(sql: &str, raw: &str, known_tables: &[String]) -> AdbError {
    let position = locate(sql, raw);
    let mut hints: Vec<adb_types::Hint> = Vec::new();
    let mut suggestions: Vec<String> = Vec::new();

    // "no such table: oder" → 建议相似表名
    if let Some(tbl) = raw.strip_prefix("no such table: ") {
        for name in similar_names(tbl, known_tables, 3) {
            hints.push(adb_types::Hint {
                kind: "similar_object".into(),
                label: name.clone(),
                detail: "数据库中有一张非常相似的表".into(),
            });
            suggestions.push(format!("替换为 `{name}` 并重新执行"));
        }
    }
    if let Some(rest) = raw.strip_prefix("no such column: ") {
        // SQLite 的原文是 `no such column: NAME in <整条 SQL> at offset N`。
        // 提示里只放**列名** —— 把整条 SQL 再抄一遍对用户毫无帮助。
        let col = rest.split(" in ").next().unwrap_or(rest).trim().to_string();
        suggestions.push("先在表结构页确认列名".into());
        hints.push(adb_types::Hint {
            kind: "column".into(),
            label: if col.is_empty() { "未知列".into() } else { col },
            detail: "该列不存在".into(),
        });
    }

    // ⭐ 约束错误翻译：SQLite 的裸报错对用户毫无意义。
    //    "FOREIGN KEY constraint failed" 用户看不出是哪张表、哪一列、
    //    该怎么办 —— 而这恰恰是最常见的写操作失败原因。
    if let Some((msg, hint_detail, advice)) = translate_constraint(raw) {
        hints.push(adb_types::Hint {
            kind: "constraint".into(),
            label: msg.to_string(),
            detail: hint_detail.to_string(),
        });
        suggestions.push(advice.to_string());
        return AdbError::Query(QueryError {
            message: msg.to_string(),
            position,
            sql_state: None,
            hints,
            suggestions,
        });
    }

    AdbError::Query(QueryError {
        message: human_message(raw),
        position,
        sql_state: None,
        hints,
        suggestions,
    })
}

/// ⭐⭐ 把 SQLite 的裸报错整理成一句人话。
///
/// SQLite 的原文会把**整条 SQL 再抄一遍**：
/// ```text
/// no such column: nope in SELECT nope FROM orders at offset 7
/// ```
/// 用户面前已经摆着这条 SQL 了，再抄一遍毫无帮助；
/// 真正有信息量的是「哪一列不存在」，那句应该单独说清楚。
///
/// ⚠ 只**裁剪**，不改写语义：`no such table: oder` 这类本来就干净的
///   原样返回。裁掉后若内容不完整，宁可保留原文也不能说错话。
fn human_message(raw: &str) -> String {
    if let Some(rest) = raw.strip_prefix("no such column: ") {
        let head = rest.split(" in ").next().unwrap_or(rest);
        let col = head.split(" at offset ").next().unwrap_or(head).trim();
        if !col.is_empty() {
            return format!("列 {col} 不存在");
        }
    }
    if let Some(rest) = raw.strip_prefix("no such table: ") {
        let tbl = rest.trim();
        if !tbl.is_empty() && !tbl.contains(char::is_whitespace) {
            return format!("表 {tbl} 不存在");
        }
    }
    raw.to_string()
}

/// ⭐ 把 SQLite 的裸约束报错翻译成「发生了什么 + 该怎么办」。
///
/// 返回 `(可读消息, 补充说明, 下一步建议)`。
///
/// 注意：SQLite 的约束报错**几乎不带上下文** —— 只有 UNIQUE / NOT NULL /
/// CHECK 会在末尾附上 `表.列`，外键则完全不说是哪张表。所以这里给方向，
/// 不能假装知道细节；真正的定位靠结构页 + 外键检查。
///
/// ⭐ 一律用 `starts_with` 前缀匹配：SQLite 会给这几类报错追加
///    `: 表名.列名` 后缀，写成精确匹配就会漏掉最常见的那种。
///
/// `detail` 是 `String` 而非 `&'static str` —— 因为要把真实的表.列
/// 插进去。消息与建议是常量，detail 不是。
fn translate_constraint(raw: &str) -> Option<(&'static str, String, &'static str)> {
    let m = raw.trim();
    // SQLite 的报错可能被包一层前缀
    let m = m.strip_prefix("SQLITE_CONSTRAINT: ").unwrap_or(m);

    // 表/列不存在由相似名逻辑处理，不走这里
    if m.starts_with("no such table") || m.starts_with("no such column") {
        return None;
    }

    // 取出 SQLite 附在末尾的 `表.列`（UNIQUE / NOT NULL / CHECK 才有）
    let viol = || m.split_once(": ").map(|(_, c)| c.to_string());

    let (msg, detail, advice) = if m.starts_with("FOREIGN KEY constraint failed") {
        (
            "违反外键约束：引用的记录不存在，或这条记录正被别的表引用",
            "SQLite 不会告诉你是哪张表 —— 在结构页查看外键关系".to_string(),
            "在结构页查看外键关系，确认被引用/引用的记录是否存在",
        )
    } else if m.starts_with("UNIQUE constraint failed") {
        (
            "违反唯一约束：已有相同值的记录",
            match viol() {
                Some(c) => format!("冲突位置：{c}"),
                None => "该列要求值唯一".to_string(),
            },
            "改用其他值，或先删除已存在的重复记录",
        )
    } else if m.starts_with("NOT NULL constraint failed") {
        (
            "违反非空约束：必填字段是空的",
            match viol() {
                Some(c) => format!("不能为空的字段：{c}"),
                None => "该列不允许 NULL".to_string(),
            },
            "补上该字段的值；确实可空时检查是否误设了 NOT NULL",
        )
    } else if m.starts_with("CHECK constraint failed") {
        (
            "违反检查约束：值不满足该列的取值规则",
            match viol() {
                Some(c) => format!("受约束的字段：{c}"),
                None => "例如评分必须在 1-5 之间".to_string(),
            },
            "按约束规则调整取值，或先放宽该列的 CHECK 约束",
        )
    } else if m.starts_with("datatype mismatch") {
        (
            "类型不匹配：该列不接受这种值",
            "例如往整数列里塞了非数字文本".to_string(),
            "检查字面量与列类型的对应关系，必要时加类型转换",
        )
    } else if m.starts_with("attempt to write a readonly database") {
        (
            "数据库是只读的，写操作被拒绝",
            "文件权限或数据库本身处于只读模式".to_string(),
            "检查数据库文件权限；演示模式下写操作会在事务内验证后回滚",
        )
    } else {
        return None; // 认不出来就原样透传，绝不瞎猜
    };

    Some((msg, detail, advice))
}

// ─────────────────────────────────────────────────────────────
// 测试
// ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn danger_classification() {
        assert_eq!(analyze("SELECT 1"), Danger::ReadOnly);
        assert_eq!(analyze("  select * from t "), Danger::ReadOnly);
        assert_eq!(analyze("WITH x AS (SELECT 1) SELECT * FROM x"), Danger::ReadOnly);
        assert_eq!(analyze("INSERT INTO t VALUES(1)"), Danger::Mutating);
        assert_eq!(analyze("UPDATE t SET a=1 WHERE id=2"), Danger::Mutating);
        // ⭐ 无 WHERE 的 DELETE 是破坏性
        assert_eq!(analyze("DELETE FROM t"), Danger::Destructive);
        assert_eq!(analyze("DELETE FROM t WHERE id=1"), Danger::Mutating);
        assert_eq!(analyze("DROP TABLE t"), Danger::Destructive);
        assert_eq!(analyze("CREATE TABLE t(a int)"), Danger::Destructive);
        // 多语句取最危险
        assert_eq!(analyze("SELECT 1; DROP TABLE t"), Danger::Destructive);
    }

    #[test]
    fn locate_finds_line_and_column() {
        let sql = "SELECT user_id,\n  name\nFROM oder";
        let err = "no such table: oder";
        let p = locate(sql, err).expect("should locate");
        assert_eq!(p.line, 3);
        assert_eq!(p.column, 6);
    }

    /// ⭐⭐ 回归：`no such column` 的**实际最常见形态**是
    /// ```text
    /// no such column: nope in SELECT nope FROM orders at offset 7
    /// ```
    /// 之前把 ` in ` 之后的整段当成了标识符，于是定位结果是 `(1, 1)`、
    /// 长度 75 —— 点「跳转到错误位置」跳到句首。
    ///
    /// ⚠ 这个 bug **只影响写错列名**，不影响写错表名（表名错误没有后缀），
    ///   所以在只测表名的老用例里完全看不出来。
    #[test]
    fn locate_handles_no_such_column_with_in_clause() {
        let sql = "SELECT nope FROM orders";
        let err = "no such column: nope in SELECT nope FROM orders at offset 7";
        let p = locate(sql, err).expect("should locate");
        assert_eq!(p.line, 1);
        assert_eq!(p.column, 8, "nope 在第 8 列（`SELECT ` 共 7 字符）");
        assert_eq!(p.offset, 7);
        assert_eq!(p.length, 4, "长度应是列名本身 4 个字符，而不是整条报错的长��");
    }

    /// ⭐ 跨行的列名错误也要定位到正确行
    #[test]
    fn locate_column_error_across_lines() {
        let sql = "SELECT id,\n       nope\nFROM orders";
        let err = "no such column: nope in SELECT id,\n       nope\nFROM orders at offset 16";
        let p = locate(sql, err).expect("should locate");
        assert_eq!(p.line, 2, "应落在第 2 行：{p:?}");
        assert_eq!(p.column, 8);
        assert_eq!(p.length, 4);
    }

    /// ⭐ 认不出标识符时**不给位置**，而不是给 (1,1)。
    ///
    /// (1,1) 是个「看起来合法、实际全错」的答案：
    /// 前端会照着跳，用户以为定位不准，从此不再信任这个功能。
    #[test]
    fn locate_returns_none_when_identifier_absent() {
        let sql = "SELECT 1";
        // 报错里的标识符在 SQL 里根本不存在
        assert!(locate(sql, "no such column: ghost in SELECT 1 at offset 3").is_none());
        // 空标识符也不该给位置
        assert!(locate(sql, "no such column: ").is_none());
        // 完全认不出的错误类型
        assert!(locate(sql, "something went totally wrong").is_none());
    }

    /// ⭐ 错误文案不该把整条 SQL 再抄一遍 ——
    /// 用户面前已经摆着这条 SQL 了，重复只会淹没真正有用的那半句。
    #[test]
    fn error_message_does_not_echo_whole_sql() {
        assert_eq!(
            human_message("no such column: nope in SELECT nope FROM orders at offset 7"),
            "列 nope 不存在"
        );
        assert_eq!(human_message("no such table: oder"), "表 oder 不存在");
        // ⭐ 认不出来的错误**原样透传**，不硬套模板（会说出错的话）
        assert_eq!(
            human_message("FOREIGN KEY constraint failed"),
            "FOREIGN KEY constraint failed"
        );
        // ⭐ 内容不完整时不裁剪：宁可给原文也不能说「列  不存在」
        assert_eq!(human_message("no such column: "), "no such column: ");
        // 表名里带空格（那本来就是别的错误）→ 不套模板
        assert_eq!(
            human_message("no such table: SELECT * FROM orders"),
            "no such table: SELECT * FROM orders"
        );
    }

    /// ⭐⭐ 端到端：位置与文案必须**同时**正确。
    ///
    /// 这两条分开测都可能过，合起来才是用户在屏幕上看到的：
    /// 「列 nope 不存在 · 第 1 行第 8 列」，点跳转正好落在 nope 上。
    #[test]
    fn column_error_position_and_message_agree() {
        let sql = "SELECT nope FROM orders";
        let raw = "no such column: nope in SELECT nope FROM orders at offset 7";
        let e = query_error(sql, raw, &["orders".to_string()]);
        let p = e.to_payload();
        assert_eq!(p["message"], "列 nope 不存在");
        let off = p["position"]["offset"].as_u64().unwrap() as usize;
        assert_eq!(&sql[off..off + 4], "nope", "跳转位置要落在出错列名上");
        assert_eq!(p["position"]["length"].as_u64(), Some(4));
        assert_eq!(p["position"]["column"].as_u64(), Some(8));
    }

    #[test]
    fn similar_names_finds_typo() {
        let cands = vec![
            "orders".into(),
            "order_items".into(),
            "users".into(),
        ];
        let r = similar_names("oder", &cands, 3);
        assert!(r.contains(&"orders".to_string()), "got {r:?}");
    }

    // ── total 估算：宁可不给，不能给错 ────────────────────────────

    /// 构造一个最小快照（带行数的假表），只为驱动纯函数 `estimate_total`。
    fn snap_with(tables: &[(&str, &str, Option<i64>)]) -> SchemaSnapshot {
        SchemaSnapshot {
            db_id: "t".into(),
            taken_at: "0".into(),
            tables: tables
                .iter()
                .map(|(name, kind, rows)| adb_types::TableDef {
                    id: format!("t:{name}"),
                    name: (*name).into(),
                    comment: None,
                    kind: (*kind).into(),
                    row_estimate: *rows,
                    size_bytes: None,
                    columns: Vec::new(),
                    indexes: Vec::new(),
                    foreign_keys: Vec::new(),
                })
                .collect(),
        }
    }

    #[test]
    fn total_is_reported_only_for_bare_single_table_select() {
        use crate::sqlite::estimate_total;
        let s = snap_with(&[("orders", "table", Some(5000)), ("v_sum", "view", None)]);

        // 裸单表扫描 —— 这是唯一能给权威值的形状
        assert_eq!(estimate_total("SELECT * FROM orders", &s), Some(5000));
        assert_eq!(estimate_total("select id, name from orders", &s), Some(5000));
        assert_eq!(estimate_total("SELECT * FROM \"orders\";", &s), Some(5000));
        assert_eq!(estimate_total("SELECT * FROM main.orders", &s), Some(5000));
    }

    /// ⭐ 这条是**回归测试**：CTE 之前会被算成 `orders` 的 5000 行，
    ///    而它实际只返回 1 行。
    #[test]
    fn cte_must_not_borrow_the_inner_tables_row_count() {
        use crate::sqlite::estimate_total;
        let s = snap_with(&[("orders", "table", Some(5000))]);
        assert_eq!(
            estimate_total(
                "WITH paid AS (SELECT * FROM orders WHERE status=2) SELECT COUNT(*) FROM paid",
                &s
            ),
            None,
            "CTE 的行数与 orders 无关，必须拒绝估算而不是报错值"
        );
    }

    #[test]
    fn filtered_or_joined_queries_are_never_estimated() {
        use crate::sqlite::estimate_total;
        let s = snap_with(&[("orders", "table", Some(5000)), ("users", "table", Some(800))]);
        // 这些查询的真实行数都 ≠ 基表行数
        for q in [
            "SELECT * FROM orders WHERE status=1",
            "SELECT * FROM orders ORDER BY id",
            "SELECT * FROM orders LIMIT 10",
            "SELECT * FROM orders o JOIN users u ON o.user_id=u.id",
            "SELECT * FROM orders UNION SELECT * FROM orders",
            "SELECT COUNT(*) FROM orders",
            "SELECT DISTINCT status FROM orders",
            "SELECT * FROM (SELECT * FROM orders)",
            "INSERT INTO orders VALUES(1)",
            "SELECT * FROM unknown_table",
        ] {
            assert_eq!(estimate_total(q, &s), None, "不该给 total: {q}");
        }
    }

    /// ⭐ ⭐ JOIN 结果的列必须归属到**正确的表**。
///
/// 回归测试：之前 `build_columns` 在**全库**按列名找第一个匹配，于是
/// ```text
/// SELECT o.order_no, u.name FROM orders o JOIN users u ON o.user_id = u.id
/// ```
/// 里的 `name` 被安成 `categories.name`（注释「分类名」），
/// 因为 categories 在字母序上排在 users 前面。
/// 错误信息不只是注释 —— 类型、nullable、isPk 全都是错的，用户看不出来。
#[test]
fn joined_columns_are_attributed_to_the_right_table() {
    use crate::sqlite::{build_columns, referenced_tables};
    use adb_types::SchemaSnapshot;

    let mk_col = |t: &str, c: &str, comment: &str| adb_types::ColumnDef {
        id: format!("t:{t}:{c}"),
        name: c.into(),
        category: adb_types::ColumnType::Text,
        is_large: false,
        ty: "TEXT".into(),
        nullable: true,
        default_expr: None,
        comment: Some(comment.into()),
        is_pk: false,
        is_fk: false,
        enum_values: None,
        ord: 0,
    };

    // 三张表里都有 `name` 列，注释各不相同 —— 最容易认错的那种情况
    let snap = SchemaSnapshot {
        db_id: "d".into(),
        taken_at: "0".into(),
        tables: vec![
            adb_types::TableDef {
                id: "t:categories".into(),
                name: "categories".into(),
                comment: None,
                kind: "table".into(),
                row_estimate: Some(12),
                size_bytes: None,
                columns: vec![mk_col("categories", "name", "分类名")],
                indexes: Vec::new(),
                foreign_keys: Vec::new(),
            },
            adb_types::TableDef {
                id: "t:orders".into(),
                name: "orders".into(),
                comment: None,
                kind: "table".into(),
                row_estimate: Some(5000),
                size_bytes: None,
                columns: vec![mk_col("orders", "order_no", "订单号（业务唯一）")],
                indexes: Vec::new(),
                foreign_keys: Vec::new(),
            },
            adb_types::TableDef {
                id: "t:users".into(),
                name: "users".into(),
                comment: None,
                kind: "table".into(),
                row_estimate: Some(800),
                size_bytes: None,
                columns: vec![mk_col("users", "name", "姓名（敏感字段）")],
                indexes: Vec::new(),
                foreign_keys: Vec::new(),
            },
        ],
    };

    let sql = "SELECT o.order_no, u.name FROM orders o JOIN users u ON o.user_id = u.id";
    let referenced = referenced_tables(sql, &snap);
    let cols = build_columns(&["order_no".into(), "name".into()], &referenced);

    assert_eq!(cols[0].comment.as_deref(), Some("订单号（业务唯一）"));
    assert_eq!(
        cols[1].comment.as_deref(),
        Some("姓名（敏感字段）"),
        "`name` 被安到了错误的表上 —— 只在被引用的表里找列"
    );
}

/// ⭐ ⭐ 找不到归属的列必须标成未知，**不能随便挑一个同名列**。
#[test]
fn unknown_columns_are_marked_unknown_not_guessed() {
    use crate::sqlite::{build_columns, referenced_tables};
    use adb_types::SchemaSnapshot;

    let snap = SchemaSnapshot {
        db_id: "d".into(),
        taken_at: "0".into(),
        tables: vec![adb_types::TableDef {
            id: "t:orders".into(),
            name: "orders".into(),
            comment: None,
            kind: "table".into(),
            row_estimate: Some(5000),
            size_bytes: None,
            columns: vec![adb_types::ColumnDef {
                id: "t:orders:c0".into(),
                name: "total_cents".into(),
                category: adb_types::ColumnType::Number,
                is_large: false,
                ty: "INTEGER".into(),
                nullable: false,
                default_expr: None,
                comment: Some("订单总额".into()),
                is_pk: true,
                is_fk: false,
                enum_values: None,
                ord: 0,
            }],
            indexes: Vec::new(),
            foreign_keys: Vec::new(),
        }],
    };

    // 计算出来的列不属于任何表
    let sql = "SELECT total_cents / 100.0 AS yuan FROM orders";
    let referenced = referenced_tables(sql, &snap);
    let cols = build_columns(&["yuan".into()], &referenced);
    assert!(
        cols[0].id.starts_with("expr:"),
        "未知列应标记为 expr:，实得 id={}",
        cols[0].id
    );
    assert!(cols[0].comment.is_none(), "未知列不该有注释");
}

/// ⭐ 「列不存在」的提示只放列名，不把整条 SQL 抄一遍。
#[test]
    fn missing_column_hint_names_only_the_column() {
        let raw = "no such column: nonexistent_col in SELECT nonexistent_col FROM orders at offset 7";
        let e = query_error("SELECT nonexistent_col FROM orders", raw, &["orders".into()]);
        let AdbError::Query(q) = e else { panic!() };
        let h = q.hints.iter().find(|h| h.kind == "column").expect("应有列提示");
        assert_eq!(h.label, "nonexistent_col");
        assert!(
            !h.label.contains("SELECT"),
            "提示里不该重复整条 SQL: {}",
            h.label
        );
    }

    /// ⭐ 视图不计数：COUNT 会让大视图全表扫，且视图行数不可信。
    #[test]
    fn views_are_never_counted() {
        use crate::sqlite::estimate_total;
        let s = snap_with(&[("v_sum", "view", None)]);
        assert_eq!(estimate_total("SELECT * FROM v_sum", &s), None);
    }

    // ── 约束报错翻译 ─────────────────────────────────────────────

    #[test]
    fn constraint_errors_become_actionable_chinese() {
        let known = vec!["orders".into()];
        for raw in [
            "FOREIGN KEY constraint failed",
            "UNIQUE constraint failed: tags.name",
            "NOT NULL constraint failed: tags.name",
            "CHECK constraint failed: reviews",
        ] {
            let e = query_error("DELETE FROM orders", raw, &known);
            let AdbError::Query(q) = e else { panic!("应为 query 错误") };
            assert!(
                !q.message.contains("constraint failed"),
                "裸 SQLite 报错泄漏给了用户: {}",
                q.message
            );
            assert!(
                q.message.chars().any(|c| ('一'..='鿿').contains(&c)),
                "翻译后应是中文: {}",
                q.message
            );
            assert!(
                !q.suggestions.is_empty(),
                "{raw} 必须给出下一步建议"
            );
        }
    }

    /// ⭐ 表/列不存在**不能**被约束翻译吃掉 —— 相似名建议比约束提示有用。
    #[test]
    fn missing_table_still_gets_similar_name_hints() {
        let known = vec!["orders".into(), "order_items".into()];
        let e = query_error("SELECT * FROM oder", "no such table: oder", &known);
        let AdbError::Query(q) = e else { panic!() };
        // ⚠ 这里断言的是「**信息没丢**」，不是「原文一字不动」。
        //   消息已改为 `表 oder 不存在`（`human_message`），
        //   但用户要能从这句话看出**缺的是表**、**叫什么名字** —— 断言这两点。
        assert!(
            q.message.contains("oder"),
            "必须指出是哪个对象出的问题：{}",
            q.message
        );
        assert!(
            q.message.contains("表"),
            "必须说清缺的是表（而不是列）：{}",
            q.message
        );
        assert!(
            q.hints.iter().any(|h| h.label == "orders"),
            "相似表名建议被约束翻译吃掉了"
        );
    }

    /// ⭐ 无法识别的错误必须**原样透传**，不能被猜成某个已知约束。
    #[test]
    fn unknown_errors_pass_through_untouched() {
        let e = query_error("SELECT 1", "something totally unexpected", &[]);
        let AdbError::Query(q) = e else { panic!() };
        assert_eq!(q.message, "something totally unexpected");
        assert!(q.hints.is_empty());
    }
}
