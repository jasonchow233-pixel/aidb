use adb_types::QueryError;

#[derive(Debug, thiserror::Error)]
pub enum AdbError {
    #[error("连接失败: {0}")]
    Connection(String),

    #[error("查询失败")]
    Query(QueryError),

    #[error("操作已取消")]
    Cancelled,

    #[error("操作超时（阶段: {0}）")]
    Timeout(&'static str),

    #[error("权限不足: {0}")]
    PermissionDenied(String),

    #[error("不支持: {0}")]
    Unsupported(String),

    #[error("内部错误: {0}")]
    Internal(String),
}

pub type Result<T> = std::result::Result<T, AdbError>;

impl From<QueryError> for AdbError {
    fn from(e: QueryError) -> Self { Self::Query(e) }
}

impl AdbError {
    /// ⭐ 前端拿这个结构渲染错误弹层
    pub fn to_payload(&self) -> serde_json::Value {
        match self {
            Self::Query(q) => serde_json::json!({
                "kind": "query", "message": q.message,
                "position": q.position, "hints": q.hints, "suggestions": q.suggestions,
            }),
            Self::Connection(m) => serde_json::json!({ "kind": "connection", "message": m }),
            Self::Cancelled => serde_json::json!({ "kind": "cancelled", "message": "操作已取消" }),
            Self::Timeout(s) => serde_json::json!({ "kind": "timeout", "message": format!("操作超时（{s}）") }),
            Self::PermissionDenied(r) => serde_json::json!({ "kind": "permission", "message": r }),
            Self::Unsupported(m) => serde_json::json!({ "kind": "unsupported", "message": m }),
            Self::Internal(m) => serde_json::json!({ "kind": "internal", "message": m }),
        }
    }
}
