//! MySQL 驱动 —— 声明模式（D 阶段将替换为真实驱动）。
//!
//! ⭐ **声明驱动**：在 UI 中展示 MySQL 能力矩阵（`traits` / `capabilities`），
//!    由 YAML 声明生成。真实连接由用户在设置中通过连接字符串建立。
//!
//! D 阶段（W13-W14）将实现：
//!   · 真实 `mysql` crate 异步连接
//!   · memo 表模拟 OID（MySQL 无系统 OID）
//!   · `KILL QUERY` 取消 + 幂等
//!   · DDL 隐式提交提示（由 `traits.transactionalDdl: false` 驱动）

use adb_drivers::{Driver, Health, QueryOptions, Result, RowPage, Session};
use adb_types::{ConnectionType, Descriptor, SchemaSnapshot, Danger};
use std::sync::Arc;

/// ⭐ MySQL 驱动（声明模式：无真实连接）
pub struct MySqlDriver {
    /// ⭐ 驱动标识
    pub id: String,
    /// ⭐ 配置描述（UI 展示用）
    pub config: String,
}

impl Default for MySqlDriver {
    fn default() -> Self {
        Self {
            id: "mysql".into(),
            config: "host=127.0.0.1 port=3306 user=root database=agenticdb_demo".into(),
        }
    }
}

impl MySqlDriver {
    pub fn new() -> Self {
        Self::default()
    }
}

#[async_trait::async_trait]
impl Driver for MySqlDriver {
    fn kind(&self) -> ConnectionType {
        ConnectionType::MySQL
    }

    fn descriptor(&self) -> &'static Descriptor {
        adb_types::descriptor(ConnectionType::MySQL)
    }

    async fn open(&self) -> Result<Box<dyn Session>> {
        Ok(Box::new(MySqlSession::new(&self.config)))
    }
}

/// ⭐ MySQL 会话（声明模式）
pub struct MySqlSession {
    pub config: String,
}

impl MySqlSession {
    pub fn new(config: &str) -> Self {
        Self {
            config: config.to_string(),
        }
    }
}

#[async_trait::async_trait]
impl Session for MySqlSession {
    fn kind(&self) -> ConnectionType {
        ConnectionType::MySQL
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
            db_id: "mysql".to_string(),
            taken_at: String::new(),
            tables: vec![],
        })
    }
}
