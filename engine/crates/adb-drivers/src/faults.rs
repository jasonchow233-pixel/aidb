//! 故障注入 —— MockDriver 的核心组件。
//!
//! ⭐ **为什么需要故障注入**：
//!   UI 阶段必须在没有真实数据库的情况下测试异常场景：
//!   · 慢查询（5s / 30s / 永不返回）→ 测试加载态、取消按钮
//!   · 断线 + 重连 → 测试连接恢复 UI
//!   · 锁表 → 测试锁等待提示
//!   · 各类错误（带 SqlPosition）→ 测试错误定位跳转
//!   · 无主键表 → 测试编辑限制
//!
//! ⭐ 设计原则：
//!   · 故障是**声明式**的，不是随机发生的
//!   · 每个故障都有**可预测的行为**，便于测试断言
//!   · 故障注入不影响正常路径（默认关闭）

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use adb_types::SqlPosition;

/// ⭐ 故障类型 —— UI 阶段测试用
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fault {
    /// 无故障（默认）
    None,
    /// 慢查询：延迟 N 秒后返回
    SlowQuery(Duration),
    /// 永不返回（模拟挂死）
    Hang,
    /// 断线：第一次查询失败，之后恢复
    Disconnect,
    /// 锁表：前 N 次查询返回锁错误
    LockTable(u32),
    /// 语法错误：返回带 SqlPosition 的错误
    SyntaxError,
    /// 表不存在：返回 "no such table" + 相似表名建议
    TableNotFound,
    /// 列不存在：返回 "no such column"
    ColumnNotFound,
    /// 只读错误：写操作返回 "attempt to write a readonly database"
    ReadOnly,
}

impl Fault {
    /// 是否处于激活状态
    pub fn is_active(&self) -> bool {
        !matches!(self, Fault::None)
    }
}

/// ⭐ 故障注入器 —— 线程安全，可在 Session 间共享
#[derive(Debug, Clone)]
pub struct FaultInjector {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    /// 当前注入的故障（低 32 位 = discriminant，高 32 位 = 参数）
    fault: AtomicU64,
    /// 已触发的次数（用于 Disconnect/LockTable 的恢复逻辑）
    triggered: AtomicU64,
    /// 是否已恢复（断线后重连成功）
    recovered: AtomicBool,
}

impl Default for FaultInjector {
    fn default() -> Self {
        Self::new()
    }
}

impl FaultInjector {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Inner {
                fault: AtomicU64::new(0),
                triggered: AtomicU64::new(0),
                recovered: AtomicBool::new(false),
            }),
        }
    }

    /// ⭐ 注入故障
    pub fn inject(&self, fault: Fault) {
        let disc = fault_to_u64(fault);
        self.inner.fault.store(disc, Ordering::SeqCst);
        self.inner.triggered.store(0, Ordering::SeqCst);
        self.inner.recovered.store(false, Ordering::SeqCst);
    }

    /// 清除故障
    pub fn clear(&self) {
        self.inner.fault.store(0, Ordering::SeqCst);
        self.inner.triggered.store(0, Ordering::SeqCst);
        self.inner.recovered.store(false, Ordering::SeqCst);
    }

    /// 获取当前故障
    pub fn current(&self) -> Fault {
        u64_to_fault(self.inner.fault.load(Ordering::SeqCst))
    }

    /// ⭐ 检查是否应该触发故障。返回 Some(Duration) 表示需要延迟。
    ///    每次调用都会更新内部状态（触发次数 + 恢复标记）。
    pub fn check(&self) -> Option<Duration> {
        let fault = self.current();
        if !fault.is_active() {
            return None;
        }

        match fault {
            Fault::None => None,
            Fault::SlowQuery(d) => Some(d),
            Fault::Hang => Some(Duration::from_secs(3600)), // 模拟挂死
            Fault::Disconnect => {
                let n = self.inner.triggered.fetch_add(1, Ordering::SeqCst);
                if n == 0 && !self.inner.recovered.load(Ordering::SeqCst) {
                    // 第一次触发断线
                    self.inner.recovered.store(true, Ordering::SeqCst);
                    Some(Duration::from_millis(50)) // 短暂延迟后报错
                } else {
                    None // 已恢复
                }
            }
            Fault::LockTable(max) => {
                let n = self.inner.triggered.fetch_add(1, Ordering::SeqCst);
                if n < max as u64 {
                    Some(Duration::from_millis(100)) // 锁等待
                } else {
                    None
                }
            }
            Fault::SyntaxError | Fault::TableNotFound | Fault::ColumnNotFound | Fault::ReadOnly => {
                // 立即触发，不延迟
                None
            }
        }
    }

    /// ⭐ 获取当前故障对应的错误消息。返回 None 表示无错误。
    pub fn error_message(&self, sql: &str) -> Option<(String, Option<SqlPosition>)> {
        match self.current() {
            Fault::None => None,
            Fault::SyntaxError => {
                let pos = SqlPosition {
                    line: 1,
                    column: 1,
                    offset: 0,
                    length: 6,
                };
                Some(("near \"SELEC\": syntax error".to_string(), Some(pos)))
            }
            Fault::TableNotFound => {
                let pos = find_ident_pos(sql, "FROM");
                Some(("no such table: oder".to_string(), pos))
            }
            Fault::ColumnNotFound => {
                let pos = find_ident_pos(sql, "SELECT");
                Some(("no such column: emial".to_string(), pos))
            }
            Fault::ReadOnly => Some(("attempt to write a readonly database".to_string(), None)),
            Fault::Disconnect | Fault::LockTable(_) => {
                Some(("database is locked".to_string(), None))
            }
            Fault::SlowQuery(_) | Fault::Hang => None, // 延迟类不产生错误
        }
    }

    /// 已触发次数
    pub fn triggered_count(&self) -> u64 {
        self.inner.triggered.load(Ordering::SeqCst)
    }
}

fn fault_to_u64(f: Fault) -> u64 {
    let (disc, param): (u64, u64) = match f {
        Fault::None => (0, 0),
        Fault::SlowQuery(d) => (1, d.as_secs()),
        Fault::Hang => (2, 0),
        Fault::Disconnect => (3, 0),
        Fault::LockTable(max) => (4, max as u64),
        Fault::SyntaxError => (5, 0),
        Fault::TableNotFound => (6, 0),
        Fault::ColumnNotFound => (7, 0),
        Fault::ReadOnly => (8, 0),
    };
    (param << 32) | disc
}

fn u64_to_fault(v: u64) -> Fault {
    let disc = v & 0xFFFF_FFFF;
    let param = v >> 32;
    match disc {
        0 => Fault::None,
        1 => Fault::SlowQuery(Duration::from_secs(param)),
        2 => Fault::Hang,
        3 => Fault::Disconnect,
        4 => Fault::LockTable(param as u32),
        5 => Fault::SyntaxError,
        6 => Fault::TableNotFound,
        7 => Fault::ColumnNotFound,
        8 => Fault::ReadOnly,
        _ => Fault::None,
    }
}

/// 在 SQL 中找某个关键字后的标识符位置
fn find_ident_pos(sql: &str, keyword: &str) -> Option<SqlPosition> {
    let up = sql.to_uppercase();
    let kw_pos = up.find(keyword)?;
    let after = &sql[kw_pos + keyword.len()..];
    let ident = after
        .trim_start()
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_')
        .collect::<String>();
    if ident.is_empty() {
        return None;
    }
    let offset = sql.len() - after.trim_start().len();
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inject_and_clear() {
        let fi = FaultInjector::new();
        assert_eq!(fi.current(), Fault::None);

        fi.inject(Fault::SlowQuery(Duration::from_secs(10)));
        assert_eq!(fi.current(), Fault::SlowQuery(Duration::from_secs(10)));

        fi.clear();
        assert_eq!(fi.current(), Fault::None);
    }

    #[test]
    fn disconnect_recovers_after_first() {
        let fi = FaultInjector::new();
        fi.inject(Fault::Disconnect);

        // 第一次触发
        let d = fi.check();
        assert!(d.is_some());
        assert_eq!(fi.triggered_count(), 1);

        // 第二次不触发（已恢复）
        let d2 = fi.check();
        assert!(d2.is_none());
    }

    #[test]
    fn lock_table_counts_down() {
        let fi = FaultInjector::new();
        fi.inject(Fault::LockTable(2));

        assert!(fi.check().is_some());
        assert!(fi.check().is_some());
        assert!(fi.check().is_none()); // 第 3 次不再触发
    }

    #[test]
    fn syntax_error_has_position() {
        let fi = FaultInjector::new();
        fi.inject(Fault::SyntaxError);

        let (msg, pos) = fi.error_message("SELEC * FROM t").unwrap();
        assert!(msg.contains("syntax error"));
        assert!(pos.is_some());
    }

    #[test]
    fn table_not_found_has_position() {
        let fi = FaultInjector::new();
        fi.inject(Fault::TableNotFound);

        let sql = "SELECT * FROM oder";
        let (msg, pos) = fi.error_message(sql).unwrap();
        assert!(msg.contains("no such table"));
        assert!(pos.is_some());
    }
}
