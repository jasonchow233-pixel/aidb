// crates/adb-drivers/src/lib.rs
//
// ⭐ 门面 crate 的唯一职责：re-export + 注册表。
//    这里不应该有任何业务逻辑 —— 逻辑要么在 per-driver crate，要么在 adb-core。

#![deny(clippy::module_inception)]

use adb_types::{Capabilities, ConnectionType, RuntimeMode, SupportLevel, Traits};

// ─────────────────────────────────────────────────────────────
// Trait 定义（在这里集中，因为所有驱动都实现它）
//
// ⭐ 抄自 DBX：trait 在门面定义，实现分散在 per-driver crate。
//    这样上层只 `use adb_drivers::{Driver, Session}` 就够了。
// ─────────────────────────────────────────────────────────────

pub mod error;

pub use error::{AdbError, SqlPosition};

use std::sync::Arc;
use async_trait::async_trait;

/// 元数据拉取深度 —— ⭐ 懒加载的核心
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Depth {
    /// 只取 id / name / type / comment（侧栏第一层）
    Name,
    /// + 列、类型、null、默认值、PK
    Structure,
    /// + 索引、FK、触发器、约束、统计
    Detail,
    /// + DDL、存储过程源码
    Source,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RowIdentity {
    PrimaryKey,
    UniqueIndex,
    None,
}

#[derive(Debug, Clone)]
pub struct DriverCaps {
    pub transaction: bool,
    /// ⭐ 驱动结构化改表（不只是执行 DDL）
    pub structure_editing: bool,
    pub explain_formats: &'static [&'static str],
    pub row_identity: RowIdentity,
    /// ⭐ DDL 是否在事务内可回滚。false → UI 提示「DDL 会自动提交」
    pub transactional_ddl: bool,
    pub online_ddl: Option<OnlineDdl>,
    pub max_params: usize,
    pub savepoints: bool,
    /// ⭐ 是否支持跨结果集分页（PG 的 FETCH FIRST vs MySQL 的 LIMIT）
    pub fetch_first: bool,
    /// ⭐ JDBC Agent 通道的取消策略（native 驱动为 None）
    pub cancel_strategy: Option<CancelStrategy>,
}

#[derive(Debug, Clone, Copy)]
pub enum OnlineDdl {
    Concurrent,   // PG CREATE INDEX CONCURRENTLY
    Online,       // MySQL ALGORITHM=INPLACE
    Ghost,        // gh-ost
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CancelStrategy {
    /// PG CancelRequest / MySQL KILL QUERY
    Native,
    /// 调 KILL SESSION（达梦/虚谷）
    KillSession,
    /// ⚠️ 只能中断读取 —— UI 必须如实告知用户
    BestEffort,
}

#[derive(Debug, Clone)]
pub struct ConnConfig {
    pub host: String,
    pub port: u16,
    pub database: String,
    pub user: String,
    /// ⭐ 永远不进入前端。Rust 侧从 SecretStore 取。
    pub password: String,
    pub schema: Option<String>,
    pub ssl: SslMode,
    pub ssh: Option<SshTunnel>,
    pub params: Vec<(String, String)>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SslMode { Disable, Prefer, Require }

#[derive(Debug, Clone)]
pub struct SshTunnel {
    pub host: String,
    pub port: u16,
    pub user: String,
    /// ⭐ JumpServer 堡垒机格式：user1#root#token@jumpserver
    pub bastion: Option<String>,
    pub key_path: Option<String>,
}

#[derive(Debug, Clone)]
pub struct QueryOptions {
    pub max_rows: usize,
    pub cursor: Option<Cursor>,
    /// ⭐ 读模式：UI 的"搜索/过滤"与"WHERE/ORDER BY"走不同路径
    pub mode: ReadMode,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReadMode {
    /// 全表扫描后客户端切片（默认值小结果集）
    Materialized,
    /// 服务端游标惰性拉取（默认大结果集）
    Cursor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Cursor(String);

#[derive(Debug, Clone)]
pub enum RowStream {
    Page {
        rows: Vec<serde_json::Value>,
        cursor: Option<Cursor>,
        /// ⭐ 估算总行数。None 表示 COUNT 未执行（避免大表全扫）
        total: Option<u64>,
    },
    /// ⭐ 影响行数（DML/DDL 结果）
    Affected { rows: u64 },
    /// ⭐ 多语句返回多个结果集
    Results(Vec<serde_json::Value>),
    Done,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Health { Healthy, Stale, Dead }

#[async_trait]
pub trait Session: Send + Sync {
    fn connection_type(&self) -> ConnectionType;

    /// ⭐ 统一返回流式。禁止一次性拉全表。
    async fn query(&self, sql: &str, opt: QueryOptions) -> Result<RowStream, AdbError>;

    /// ⭐ 取消必须幂等。取消后**绝不自动重放**（见 DEV-SPEC §13.3）
    async fn cancel(&self) -> Result<(), AdbError>;

    async fn begin(&self) -> Result<Txn, AdbError>;
    async fn commit(&self) -> Result<(), AdbError>;
    async fn rollback(&self) -> Result<(), AdbError>;

    async fn heartbeat(&self) -> Result<(), AdbError>;
    fn health(&self) -> Health;
    fn schema_snapshot(&self) -> Arc<adb_types::SchemaSnapshot>;
}

pub struct Txn {
    _private: (),
}

#[async_trait]
pub trait Driver: Send + Sync {
    fn kind(&self) -> ConnectionType;
    fn caps(&self) -> DriverCaps;
    async fn connect(&self, cfg: ConnConfig) -> Result<Box<dyn Session>, AdbError>;
    /// ⭐ 元数据必须以 ObjectId 为键（DEV-SPEC L3）
    async fn introspect(
        &self, s: &dyn Session, scope: Scope, depth: Depth,
    ) -> Result<adb_types::SchemaSnapshot, AdbError>;
}

#[derive(Debug, Clone, Default)]
pub struct Scope {
    pub catalogs: Vec<String>,
    pub schemas: Vec<String>,
    pub include_row_estimate: bool,
}

// ─────────────────────────────────────────────────────────────
// 驱动注册表
// ─────────────────────────────────────────────────────────────

/// ⭐ 声明 → 驱动的绑定。key 必须来自生成的 ConnectionType。
#[derive(Debug, thiserror::Error)]
pub enum RegistryError {
    #[error("未知连接类型 '{0}'（检查 declarations/connections/）")]
    UnknownConnection(String),
    #[error("连接类型 '{0}' 未编译进当前构建（feature 未开启）")]
    NotCompiled(String),
    #[error("该连接类型存在描述符，但无对应驱动实现")]
    NoDriver(String),
}

/// 由各 per-driver crate 用 `inventory`/`linkme` 或显式注册
pub type DriverFactory = fn() -> Box<dyn Driver>;

static REGISTRY: std::sync::LazyLock<
    std::collections::HashMap<ConnectionType, DriverFactory>,
> = std::sync::LazyLock::new(|| {
    let mut m = std::collections::HashMap::new();
    macro_rules! reg { ($feat:literal, $mod:ident, $ty:path) => {
        #[cfg(feature = $feat)]
        m.insert(<$ty as adb_types::HasId>::ID, $mod::factory);
    };}
    reg!("driver-postgres",   postgres,   AdbDriverPostgres);
    reg!("driver-mysql",      mysql,      AdbDriverMySql);
    reg!("driver-sqlite",     sqlite,     AdbDriverSqlite);
    reg!("driver-redis",      redis,      AdbDriverRedis);
    reg!("driver-mongodb",    mongodb,    AdbDriverMongo);
    reg!("driver-clickhouse", clickhouse, AdbDriverClickHouse);
    reg!("driver-jdbc",       jdbc,       AdbDriverJdbc);
    m
});

pub fn factory_for(t: ConnectionType) -> Result<DriverFactory, RegistryError> {
    REGISTRY.get(&t).copied().ok_or_else(|| {
        // ⭐ 区分"没这个库"和"编译时没开 feature"—— 两种错误的修法完全不同
        let declared = adb_types::ALL_DESCRIPTORS.iter().any(|d| d.id == t.as_str());
        if declared {
            RegistryError::NotCompiled(t.as_str().into())
        } else {
            RegistryError::UnknownConnection(t.as_str().into())
        }
    })
}

pub fn registered() -> Vec<ConnectionType> {
    let mut v: Vec<_> = REGISTRY.keys().copied().collect();
    v.sort_by_key(|t| t.as_str());
    v
}

pub fn create(t: ConnectionType) -> Result<Box<dyn Driver>, RegistryError> {
    Ok(factory_for(t)?())
}

// ─────────────────────────────────────────────────────────────
// 声明 → 运行时 的桥接
//
// ⭐ 这个函数是"声明层驱动 UI"的入口。
//    组件不要自己判断"要不要提示 DDL 自动提交"，问这里。
// ─────────────────────────────────────────────────────────────

/// 从 YAML 描述符 + 驱动 caps 合并出运行时能力
pub fn effective_traits(desc: &adb_types::Descriptor, caps: &DriverCaps) -> Traits {
    Traits {
        transactional_ddl: desc.traits.transactional_ddl && caps.transactional_ddl,
        // 驱动不支持的格式从 explain_formats 里剔除
        explain_formats: desc.traits.explain_formats.iter()
            .copied().filter(|f| caps.explain_formats.contains(f))
            .collect(),
        ..desc.traits.clone()
    }
}

/// UI 需要的横幅警告
pub fn ui_warnings(t: ConnectionType) -> Vec<UiWarning> {
    let mut v = Vec::new();
    let d = adb_types::descriptor(t);
    if !d.traits.transactional_ddl {
        v.push(UiWarning {
            level: WarningLevel::Important,
            code: "ddl-implicit-commit",
            message: format!("{} 的 DDL 会隐式提交，无法通过事务回滚。", d.label),
            whereToShow: WarnSite::StructureEditor,
        });
    }
    if d.traits.identity_kind != adb_types::IdentityKind::AutoIncrement
        && d.runtime_mode == adb_types::RuntimeMode::Native
        && d.support_level == SupportLevel::Full
    {
        v.push(UiWarning {
            level: WarningLevel::Info,
            code: "identity-not-autoincrement",
            message: format!("{} 使用 {} 而非 AUTO_INCREMENT。", d.label,
                             d.traits.identity_kind.as_str()),
            whereToShow: WarnSite::Transfer,
        });
    }
    if !d.traits.sequence_objects && d.traits.identity_kind != adb_types::IdentityKind::Sequence {
        v.push(UiWarning {
            level: WarningLevel::Info,
            code: "no-sequence",
            message: format!("{} 没有独立序列对象。", d.label),
            whereToShow: WarnSite::Sidebar,
        });
    }
    v
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WarningLevel { Info, Important, Critical }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WarnSite { Sidebar, StructureEditor, Transfer, QueryEditor, DataGrid }

pub struct UiWarning {
    pub level: WarningLevel,
    pub code: &'static str,
    pub message: String,
    pub where_to_show: WarnSite,
}

// ─────────────────────────────────────────────────────────────
// re-export（让上层只依赖这一个 crate）
// ─────────────────────────────────────────────────────────────

#[cfg(feature = "driver-postgres")]
pub use adb_driver_postgres as postgres;
#[cfg(feature = "driver-mysql")]
pub use adb_driver_mysql as mysql;
#[cfg(feature = "driver-sqlite")]
pub use adb_driver_sqlite as sqlite;
#[cfg(feature = "driver-redis")]
pub use adb_driver_redis as redis;
#[cfg(feature = "driver-mongodb")]
pub use adb_driver_mongodb as mongodb;
#[cfg(feature = "driver-clickhouse")]
pub use adb_driver_clickhouse as clickhouse;
#[cfg(feature = "driver-jdbc")]
pub use adb_driver_jdbc as jdbc;
