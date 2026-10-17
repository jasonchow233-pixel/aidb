//! `adb-secret` —— 凭据加解缺的**唯一实现**。
//!
//! ⭐ 架构纪律（来自 DBX 5 个 issue 的教训）：
//!   所有形态（Desktop / Web / CLI / MCP / Docker）**必须链接本 crate**。
//!   MCP **不允许**独立编译分发 —— 那会让同一个密钥有两套读取路径，
//!   直接导致 `Access denied (using password: NO)` 这类故障。
//!
//! CI 门禁见 `xtask/guard.rs::every_binary_links_secret`。

pub mod store;

pub use store::{SecretStore, SecretError};
