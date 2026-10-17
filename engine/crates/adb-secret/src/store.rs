use std::collections::HashMap;
use std::sync::Mutex;

/// 凭据存储接口（运行期形态先就位，Keyring 后端后续接入）
#[derive(Debug, thiserror::Error)]
pub enum SecretError {
    #[error("连接 {0} 没有保存凭据")]
    NotFound(String),
    #[error("凭据访问失败: {0}")]
    Access(String),
}

#[derive(Default)]
pub struct SecretStore {
    /// ⭐ 进程内缓存：避免每次读都解密/访问系统凭据库
    /// （DBX #10309 的 1 分钟卡顿就是这么来的）
    cache: Mutex<HashMap<String, String>>,
}

impl SecretStore {
    pub fn new() -> Self { Self::default() }

    /// ⭐ 演示/无头环境用：直接从内存写入。
    /// 生产路径必须走 Keyring，UI 代码不得调用此方法。
    pub fn put_in_memory(&self, connection_id: &str, password: &str) {
        let mut c = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        c.insert(connection_id.to_string(), password.to_string());
    }

    pub fn get(&self, connection_id: &str) -> Result<String, SecretError> {
        let c = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        c.get(connection_id).cloned()
            .ok_or_else(|| SecretError::NotFound(connection_id.to_string()))
    }

    pub fn remove(&self, connection_id: &str) {
        let mut c = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        c.remove(connection_id);
    }

    /// ⭐ 前端永不接触密码，只拿一个短期的读取句柄。
    pub fn masked(&self, connection_id: &str) -> String {
        match self.get(connection_id) {
            Ok(_) => "••••••••".into(),
            Err(_) => String::new(),
        }
    }
}
