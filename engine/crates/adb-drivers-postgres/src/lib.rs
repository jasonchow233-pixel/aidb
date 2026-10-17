//! PostgreSQL 驱动 —— 声明模式（D 阶段将替换为真实驱动）。
//!
//! ⭐ **声明驱动**：在 UI 中展示 PostgreSQL 能力矩阵（`traits` / `capabilities`），
//!    由 YAML 声明生成。真实连接由用户在设置中通过连接字符串建立。
//!
//! D 阶段（W11-W12）将实现：
//!   · `variant.rs` 补丁层（Standard / GaussDB / KingbaseES / HighGo）
//!   · `meta_sql()` 集中元数据查询（OID 路径，禁止名字比较）
//!   · `CancelRequest` 取消 + 幂等
//!   · 变体契约测试（4 个变体跑同一套）

use adb_drivers::{Driver, Health, QueryOptions, Result, RowPage, Session};
use adb_types::{ConnectionType, Descriptor, SchemaSnapshot, Danger};
use std::sync::Arc;

/// ⭐ PostgreSQL 驱动（声明模式：无真实连接）
pub struct PostgresDriver {
    /// ⭐ 驱动标识
    pub id: String,
    /// ⭐ 配置描述（UI 展示用）
    pub config: String,
}

impl Default for PostgresDriver {
    fn default() -> Self {
        Self {
            id: "postgres".into(),
            config: "host=127.0.0.1 dbname=agenticdb_demo user=root password=".into(),
        }
    }
}

impl PostgresDriver {
    pub fn new() -> Self {
        Self::default()
    }
}

#[async_trait::async_trait]
impl Driver for PostgresDriver {
    fn kind(&self) -> ConnectionType {
        ConnectionType::Postgres
    }

    fn descriptor(&self) -> &'static Descriptor {
        adb_types::descriptor(ConnectionType::Postgres)
    }

    async fn open(&self) -> Result<Box<dyn Session>> {
        Ok(Box::new(PostgresSession::new(&self.config)))
    }
}

/// ⭐ PostgreSQL 会话（声明模式）
pub struct PostgresSession {
    pub config: String,
}

impl PostgresSession {
    pub fn new(config: &str) -> Self {
        Self {
            config: config.to_string(),
        }
    }
}

#[async_trait::async_trait]
impl Session for PostgresSession {
    fn kind(&self) -> ConnectionType {
        ConnectionType::Postgres
    }

    fn query_blocking(
        &self,
        _sql: &str,
        _opt: QueryOptions,
        _danger: Danger,
    ) -> Result<RowPage> {
        Ok(RowPage {
            columns: Vec::new(),
            rows: Vec::new(),
            next_cursor: None,
            total: None,
            duration_ms: 0,
            truncated_columns: Vec::new(),
            mutated: false,
        })
    }

    fn cancel_blocking(&self) -> Result<()> {
        Ok(())
    }

    async fn query(
        &self,
        sql: &str,
        opt: QueryOptions,
        danger: Danger,
    ) -> Result<RowPage> {
        self.query_blocking(sql, opt, danger)
    }

    async fn cancel(&self) -> Result<()> {
        Ok(())
    }

    async fn begin(&self) -> Result<()> {
        Ok(())
    }

    async fn commit(&self) -> Result<()> {
        Ok(())
    }

    async fn rollback(&self) -> Result<()> {
        Ok(())
    }

    async fn heartbeat(&self) -> Result<()> {
        Ok(())
    }

    fn health(&self) -> Health {
        Health::Healthy
    }

    fn snapshot(&self) -> Arc<SchemaSnapshot> {
        Arc::new(SchemaSnapshot {
            db_id: "postgres".to_string(),
            taken_at: String::new(),
            tables: vec![],
        })
    }
}
