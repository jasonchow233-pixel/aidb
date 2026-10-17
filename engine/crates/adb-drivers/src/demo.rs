//! 演示数据库 —— 产品的第一印象。
//!
//! ⭐ 设计原则（见 UI-SPEC）：
//!   · 有真实中文注释           → 用户看到"这个工具有语义"
//!   · 有完整枚举值             → AI 问答能答对
//!   · 有真实的脏数据（NULL/空串/孤儿）→ 筛选和编辑有东西可玩
//!   · 大小表混合（10 → 5000）  → 让虚拟滚动物理可感
//!   · ⭐ 有一张"问题表"        → 让诊断有舞台
//!
//! ⭐ 增强目标（对比 Navicat）：
//!   · 20+ 张表，覆盖常见业务场景（电商/订单/用户/商品/支付/审计）
//!   · 数据量从 20 万扩展到 100 万+，支持性能测试
//!   · 支持复杂 SQL（JOIN/子查询/聚合/CTE/窗口函数）
//!   · 支持事务/索引/视图/触发器
//!   · 支持数据类型（NULL/空串/日期/JSON/BLOB/UUID）
//!   · 支持约束（主键/外键/唯一/检查/非空）
//!   · 支持多语言（中文/英文/日文）
//!   · 支持扩展功能（FTS5/JSON1/RTREE）

use adb_types::{ColumnDef, ColumnType, IndexDef, TableDef};
use rusqlite::Connection as Sqlite;
use std::path::PathBuf;

pub fn db_path() -> String {
    let dir = dirs_data();
    std::fs::create_dir_all(&dir).unwrap_or_default();
    format!("{dir}/demo.db")
}

fn dirs_data() -> String {
    if let Ok(p) = std::env::var("ADB_DATA_DIR") { return p; }
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    format!("{home}/.agenticdb")
}

pub fn db_display_name(path: &str) -> String {
    PathBuf::from(path)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "demo.db".into())
}

// ─────────────────────────────────────────────────────────────
// Schema
// ─────────────────────────────────────────────────────────────

const DDL: &str = r#"
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

-- ⭐ 核心表：租户/用户/商品/订单/支付/审计（覆盖常见业务场景）

CREATE TABLE tenants (
  id            INTEGER PRIMARY KEY,
  name          TEXT NOT NULL,
  plan          TEXT NOT NULL DEFAULT 'free',
  max_users     INTEGER NOT NULL DEFAULT 10,
  created_at    TEXT NOT NULL
);
CREATE UNIQUE INDEX ux_tenants_name ON tenants(name);

CREATE TABLE users (
  id            INTEGER PRIMARY KEY,
  tenant_id     INTEGER NOT NULL REFERENCES tenants(id),
  email         TEXT,
  name          TEXT NOT NULL,
  phone         TEXT,
  level         INTEGER NOT NULL DEFAULT 1,
  status        INTEGER NOT NULL DEFAULT 1,
  balance_cents INTEGER NOT NULL DEFAULT 0,
  avatar_url    TEXT,
  profile       TEXT,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_created ON users(created_at);

CREATE TABLE user_profiles (
  user_id       INTEGER PRIMARY KEY REFERENCES users(id),
  nickname      TEXT,
  bio           TEXT,
  preferences   TEXT
);

CREATE TABLE categories (
  id            INTEGER PRIMARY KEY,
  name          TEXT NOT NULL,
  parent_id     INTEGER REFERENCES categories(id),
  sort_order    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE products (
  id            INTEGER PRIMARY KEY,
  sku           TEXT NOT NULL,
  name          TEXT NOT NULL,
  category_id   INTEGER REFERENCES categories(id),
  price_cents   INTEGER NOT NULL,
  stock         INTEGER NOT NULL DEFAULT 0,
  attributes    TEXT,
  image_url     TEXT,
  is_active     INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL
);
CREATE UNIQUE INDEX ux_products_sku ON products(sku);
CREATE INDEX idx_products_category ON products(category_id);

CREATE TABLE addresses (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id),
  receiver     TEXT NOT NULL,
  phone        TEXT NOT NULL,
  province     TEXT,
  city         TEXT,
  detail       TEXT,
  is_default   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_addresses_user ON addresses(user_id);

CREATE TABLE orders (
  id            INTEGER PRIMARY KEY,
  tenant_id     INTEGER NOT NULL REFERENCES tenants(id),
  user_id       INTEGER NOT NULL REFERENCES users(id),
  order_no      TEXT NOT NULL,
  status        INTEGER NOT NULL DEFAULT 1,
  total_cents   INTEGER NOT NULL DEFAULT 0,
  remark        TEXT,
  created_at    TEXT NOT NULL,
  paid_at       TEXT
);
CREATE UNIQUE INDEX ux_orders_no ON orders(order_no);
CREATE INDEX idx_orders_user_status ON orders(user_id, status);
CREATE INDEX idx_orders_created ON orders(created_at);

CREATE TABLE order_items (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  product_id    INTEGER NOT NULL REFERENCES products(id),
  quantity      INTEGER NOT NULL DEFAULT 1,
  price_cents   INTEGER NOT NULL,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_items_order ON order_items(order_id);
CREATE INDEX idx_items_product ON order_items(product_id);

CREATE TABLE payments (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  method        INTEGER NOT NULL,
  status        INTEGER NOT NULL DEFAULT 1,
  amount_cents  INTEGER NOT NULL,
  txn_id        TEXT,
  error_msg     TEXT,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_status_created ON payments(status, created_at);

CREATE TABLE sessions (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id),
  token         TEXT NOT NULL,
  device        TEXT,
  ip            TEXT,
  expires_at    TEXT NOT NULL,
  created_at    TEXT NOT NULL
);
CREATE UNIQUE INDEX ux_sessions_token ON sessions(token);
CREATE INDEX idx_sessions_user ON sessions(user_id);

-- ⭐ 问题表：无主键 + 大表 —— 诊断的舞台
CREATE TABLE audit_logs (
  tenant_id     INTEGER,
  actor         TEXT,
  action        TEXT,
  target        TEXT,
  payload       TEXT,
  created_at    TEXT
);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

-- ⭐ 扩展表：支持更多数据类型和场景

CREATE TABLE tags (
  id            INTEGER PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,
  color         TEXT NOT NULL DEFAULT '#1890ff',
  sort_order    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE product_tags (
  product_id    INTEGER NOT NULL REFERENCES products(id),
  tag_id        INTEGER NOT NULL REFERENCES tags(id),
  PRIMARY KEY (product_id, tag_id)
);

CREATE TABLE reviews (
  id            INTEGER PRIMARY KEY,
  product_id    INTEGER NOT NULL REFERENCES products(id),
  user_id       INTEGER NOT NULL REFERENCES users(id),
  rating        INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
  content       TEXT,
  is_anonymous  INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_reviews_product ON reviews(product_id);
CREATE INDEX idx_reviews_user ON reviews(user_id);

CREATE TABLE coupons (
  id            INTEGER PRIMARY KEY,
  code          TEXT NOT NULL UNIQUE,
  type          INTEGER NOT NULL DEFAULT 1,
  discount      INTEGER NOT NULL,
  min_amount    INTEGER NOT NULL DEFAULT 0,
  max_uses      INTEGER NOT NULL DEFAULT 100,
  used_count    INTEGER NOT NULL DEFAULT 0,
  starts_at     TEXT NOT NULL,
  expires_at    TEXT NOT NULL,
  is_active     INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE order_coupons (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  coupon_id     INTEGER NOT NULL REFERENCES coupons(id),
  discount_cents INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT NOT NULL
);

CREATE TABLE shipping (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  method        INTEGER NOT NULL DEFAULT 1,
  status        INTEGER NOT NULL DEFAULT 1,
  tracking_no   TEXT,
  receiver      TEXT NOT NULL,
  phone         TEXT NOT NULL,
  address       TEXT NOT NULL,
  shipped_at    TEXT,
  delivered_at  TEXT,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_shipping_order ON shipping(order_id);

CREATE TABLE invoices (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  type          INTEGER NOT NULL DEFAULT 1,
  title         TEXT NOT NULL,
  tax_no        TEXT,
  amount_cents  INTEGER NOT NULL,
  status        INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL
);

CREATE TABLE refunds (
  id            INTEGER PRIMARY KEY,
  order_id      INTEGER NOT NULL REFERENCES orders(id),
  payment_id    INTEGER NOT NULL REFERENCES payments(id),
  amount_cents  INTEGER NOT NULL,
  reason        TEXT,
  status        INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_refunds_order ON refunds(order_id);

CREATE TABLE notifications (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id),
  type          INTEGER NOT NULL DEFAULT 1,
  title         TEXT NOT NULL,
  content       TEXT,
  is_read       INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_notifications_user ON notifications(user_id);

CREATE TABLE user_favorites (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users(id),
  product_id    INTEGER NOT NULL REFERENCES products(id),
  created_at    TEXT NOT NULL,
  UNIQUE (user_id, product_id)
);

CREATE TABLE system_logs (
  id            INTEGER PRIMARY KEY,
  level         INTEGER NOT NULL DEFAULT 1,
  module        TEXT,
  action        TEXT,
  user_id       INTEGER,
  ip            TEXT,
  user_agent    TEXT,
  detail        TEXT,
  created_at    TEXT NOT NULL
);
CREATE INDEX idx_system_logs_level ON system_logs(level);
CREATE INDEX idx_system_logs_created ON system_logs(created_at);

-- ⭐ 视图：复杂查询的演示
CREATE VIEW v_order_summary AS
SELECT
  o.id,
  o.order_no,
  o.status,
  o.total_cents,
  o.created_at,
  u.name AS user_name,
  u.email AS user_email,
  COUNT(oi.id) AS item_count,
  SUM(oi.quantity) AS total_quantity
FROM orders o
JOIN users u ON o.user_id = u.id
LEFT JOIN order_items oi ON oi.order_id = o.id
GROUP BY o.id;

CREATE VIEW v_product_sales AS
SELECT
  p.id,
  p.name,
  p.sku,
  c.name AS category_name,
  p.price_cents,
  COUNT(DISTINCT oi.order_id) AS order_count,
  SUM(oi.quantity) AS total_sold,
  SUM(oi.quantity * oi.price_cents) AS total_revenue
FROM products p
LEFT JOIN categories c ON p.category_id = c.id
LEFT JOIN order_items oi ON oi.product_id = p.id
GROUP BY p.id;

CREATE VIEW v_user_stats AS
SELECT
  u.id,
  u.name,
  u.email,
  u.level,
  COUNT(DISTINCT o.id) AS order_count,
  COALESCE(SUM(o.total_cents), 0) AS total_spent,
  MAX(o.created_at) AS last_order_at
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
GROUP BY u.id;

-- ⭐ 触发器：审计日志
CREATE TRIGGER trg_orders_insert
AFTER INSERT ON orders
BEGIN
  INSERT INTO audit_logs (tenant_id, actor, action, target, payload, created_at)
  VALUES (NEW.tenant_id, 'system', 'create_order', NEW.order_no,
          json_object('order_id', NEW.id, 'total', NEW.total_cents, 'status', NEW.status),
          datetime('now'));
END;

CREATE TRIGGER trg_orders_update
AFTER UPDATE ON orders
BEGIN
  INSERT INTO audit_logs (tenant_id, actor, action, target, payload, created_at)
  VALUES (NEW.tenant_id, 'system', 'update_order', NEW.order_no,
          json_object('old_status', OLD.status, 'new_status', NEW.status),
          datetime('now'));
END;

-- ⭐ FTS5 全文搜索
--
-- ⭐ **tokenize 必须是默认的 unicode61，不要改成 trigram**：
--    unicode61 按空白/标点切词。演示数据里商品名是「无线 充电器」这种
--    带空格的形式，于是 `MATCH '无线'` 能命中。
--    trigram 看起来更适合中文，但它要求查询串**至少 3 个字符** ——
--    实测 `MATCH '无线'`、`'耳机'`、`'键盘'` 在 trigram 下**全部返回 0 条**，
--    而中文检索里两字词占绝大多数。trigram 反而更差。
--    代价：若商品名不带空格（整句一个 token），unicode61 就检索不到。
--    这是分词器的固有取舍，不是 bug；真要支持无空格中文需要 ICU 扩展。
CREATE VIRTUAL TABLE product_search USING fts5(
  name,
  sku,
  content='products',
  content_rowid='id'
);

-- ⭐ 常见查询索引优化
CREATE INDEX idx_orders_tenant ON orders(tenant_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_products_active ON products(is_active) WHERE is_active = 1;
CREATE INDEX idx_payments_method ON payments(method);
CREATE INDEX idx_reviews_rating ON reviews(rating);
CREATE INDEX idx_notifications_unread ON notifications(user_id, is_read) WHERE is_read = 0;
"#;

// ─────────────────────────────────────────────────────────────
// 数据生成（确定性 seed）
// ─────────────────────────────────────────────────────────────

pub fn materialize(path: &str) {
    let Ok(mut c) = Sqlite::open(path) else { return };
    if c.execute_batch(DDL).is_err() { return; }
    let _ = c.execute_batch("BEGIN");
    seed(&mut c);
    let _ = c.execute_batch("COMMIT");

    // ⭐ FTS 索引必须在 seed **之后**建。
    //    之前它写在 DDL 批次里，而那时 `products` 还是空表 ——
    //    结果索引里有 0 个词，`MATCH '无线'` 永远返回空，
    //    看起来像「全文搜索坏了」。
    if c.execute_batch(FTS_INDEX_SQL).is_err() {
        // 索引建不起来不该让整个演示库失败，但要让问题可见
        eprintln!("[agenticdb] 警告：全文索引构建失败，中文/英文搜索将返回空结果");
    }

    let _ = c.execute_batch("ANALYZE");
}

/// ⭐ FTS 索引的填充 + 同步触发器。
///
/// 为什么要有触发器：`content='products'` 是**外部内容** FTS 表，
/// SQLite 不会自动跟随 `products` 的增删改。不挂触发器的话，
/// 演示库建好之后任何写操作都会让索引悄悄过期。
const FTS_INDEX_SQL: &str = r#"
INSERT INTO product_search(rowid, name, sku)
SELECT id, name, sku FROM products;

CREATE TRIGGER trg_products_fts_ai AFTER INSERT ON products BEGIN
  INSERT INTO product_search(rowid, name, sku) VALUES (NEW.id, NEW.name, NEW.sku);
END;

CREATE TRIGGER trg_products_fts_ad AFTER DELETE ON products BEGIN
  INSERT INTO product_search(product_search, rowid, name, sku)
  VALUES('delete', OLD.id, OLD.name, OLD.sku);
END;

CREATE TRIGGER trg_products_fts_au AFTER UPDATE ON products BEGIN
  INSERT INTO product_search(product_search, rowid, name, sku)
  VALUES('delete', OLD.id, OLD.name, OLD.sku);
  INSERT INTO product_search(rowid, name, sku) VALUES (NEW.id, NEW.name, NEW.sku);
END;
"#;

const TENANTS: usize = 6;
const USERS: usize = 800;
const PRODUCTS: usize = 1200;
const ADDRESSES: usize = 1400;
const ORDERS: usize = 5000;
const ITEMS_PER_ORDER: usize = 3;
const AUDIT_LOGS: usize = 200_000;
const SESSIONS: usize = 3000;

// ⭐ 扩展数据量（对比 Navicat 性能测试）
const TAGS: usize = 50;
const REVIEWS: usize = 3000;
const COUPONS: usize = 100;
const SHIPPING: usize = 5000;
const INVOICES: usize = 4500;
const REFUNDS: usize = 500;
const NOTIFICATIONS: usize = 5000;
const FAVORITES: usize = 2000;
const SYSTEM_LOGS: usize = 100_000;

fn seed(c: &mut Sqlite) {
    let mut rnd = Rng(0x5EED_1234_ABCD_9001);

    // tenants
    let plans = ["free", "pro", "enterprise"];
    for i in 1..=TENANTS {
        let _ = c.execute(
            "INSERT INTO tenants (id,name,plan,max_users,created_at) VALUES (?1,?2,?3,?4,?5)",
            rusqlite::params![
                i as i64,
                format!("租户{}", ["一", "二", "三", "四", "五", "六"][i - 1]),
                plans[(i - 1) % 3],
                [10, 100, 1000][(i - 1) % 3] as i64,
                ts(&mut rnd, 400)
            ],
        );
    }

    // users
    let mut emails: Vec<Option<String>> = Vec::with_capacity(USERS);
    let mut phones: Vec<Option<String>> = Vec::with_capacity(USERS);
    for i in 1..=USERS {
        // ⭐ 真实的"脏"：约 6% 缺邮箱、约 3% 邮箱为空串、约 4% 缺手机号
        let email = match rnd.below(100) {
            0..=5 => None,
            6..=8 => Some(String::new()),
            _ => Some(format!("user{i}@example.com")),
        };
        let phone = match rnd.below(100) {
            0..=3 => None,
            _ => Some(format!("1{}{:08}", [3, 5, 7, 8, 9][rnd.below(5) as usize], rnd.below(100_000_000))),
        };
        emails.push(email.clone());
        phones.push(phone.clone());

        let _ = c.execute(
            "INSERT INTO users (id,tenant_id,email,name,phone,level,status,balance_cents,avatar_url,profile,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            rusqlite::params![
                i as i64,
                (rnd.below(TENANTS as u64) + 1) as i64,
                email,
                format!("{}{}", ["张","李","王","赵","陈","刘","杨","黄"][rnd.below(8) as usize], surname_or_num(&mut rnd)),
                phone,
                [1i64, 1, 1, 2, 3][rnd.below(5) as usize],     // 1=普通 2=VIP 3=SVIP
                if rnd.below(100) < 92 { 1 } else { 0 },        // 90% 正常
                rnd.below(500_000) as i64,
                if rnd.below(100) < 40 { format!("https://cdn.example.com/avatar/{i}.png") } else { String::new() },
                if rnd.below(100) < 30 {
                    Some(format!("{{\"theme\":\"{}\",\"lang\":\"zh-CN\"}}",
                        if rnd.below(2) == 0 { "dark" } else { "light" }))
                } else {
                    None::<String>
                },
                ts(&mut rnd, 365)
            ],
        );
    }

    // user_profiles —— ⭐ 与 users.name 类型语义不一致（跨表类型诊断的素材）
    for i in 1..=USERS {
        if rnd.below(100) < 85 {
            let _ = c.execute(
                "INSERT INTO user_profiles (user_id,nickname,bio,preferences) VALUES (?1,?2,?3,?4)",
                rusqlite::params![
                    i as i64,
                    if rnd.below(100) < 90 { format!("user_{i}") } else { String::new() },
                    if rnd.below(100) < 40 { Some("这个人很懒，什么都没写。".to_string()) } else { None::<String> },
                    None::<String>
                ],
            );
        }
    }

    // categories
    for i in 1..=12 {
        let _ = c.execute(
            "INSERT INTO categories (id,name,parent_id,sort_order) VALUES (?1,?2,?3,?4)",
            rusqlite::params![
                i as i64,
                ["电子数码","家用电器","服饰鞋包","食品生鲜","母婴用品","美妆护肤",
                 "手机配件","电脑外设","厨房用具","床上用品","运动户外","图书文娱"][i-1],
                if i > 6 { Some((i - 6) as i64) } else { None },
                i as i64
            ],
        );
    }

    // products
    for i in 1..=PRODUCTS {
        let _ = c.execute(
            "INSERT INTO products (id,sku,name,category_id,price_cents,stock,attributes,image_url,is_active,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            rusqlite::params![
                i as i64,
                format!("SKU-{:06}", i),
                format!("{} {}", ["无线","便携","智能","高颜值","入门","专业"][rnd.below(6) as usize],
                                   ["充电器","耳机","水杯","键盘","鼠标","背包","台灯","榨汁机"][rnd.below(8) as usize]),
                (rnd.below(12) + 1) as i64,
                (rnd.below(200_000) + 100) as i64,
                rnd.below(1000) as i64,
                // ⭐ 部分 JSON 列为 NULL（脏数据，让筛选有东西可玩）
                if rnd.below(100) < 75 { Some(format!("{{\"color\":\"{}\",\"warranty\":{}}}", ["黑","白","银"][rnd.below(3) as usize], 1 + rnd.below(3))) } else { None },
                if rnd.below(100) < 80 { format!("https://cdn.example.com/p/{i}.jpg") } else { String::new() },
                if rnd.below(100) < 90 { 1 } else { 0 },
                ts(&mut rnd, 300)
            ],
        );
    }

    // addresses
    let provs = ["广东省","浙江省","江苏省","北京市","上海市","四川省"];
    for i in 1..=ADDRESSES {
        let uid = (rnd.below(USERS as u64) + 1) as i64;
        let _ = c.execute(
            "INSERT INTO addresses (id,user_id,receiver,phone,province,city,detail,is_default) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
            rusqlite::params![
                i as i64, uid,
                format!("{}_{}", uid, i),
                phones[uid as usize - 1].clone().unwrap_or_default(),
                provs[rnd.below(provs.len() as u64) as usize],
                format!("{}市", ["深圳","杭州","南京","朝阳","浦东","成都"][rnd.below(6) as usize]),
                format!("{}区 {}路 {}号 {}室", ["南山","西湖","鼓楼","海淀","浦东","武侯"][rnd.below(6) as usize], (rnd.below(500)+1), (rnd.below(30)+1), (rnd.below(2500)+1)),
                if i == 1 { 1 } else { 0 }
            ],
        );
    }

    // orders —— ⭐ status 分布刻意做成 43% 待支付/已支付（查询热点）
    for i in 1..=ORDERS {
        let uid = (rnd.below(USERS as u64) + 1) as i64;
        let status = match rnd.below(100) {
            0..=42 => 1,  // 待支付 43%
            43..=69 => 2, // 已支付 27%
            70..=84 => 3, // 已发货
            85..=95 => 4, // 已完成
            _ => 9,      // 已取消
        };
        let created = ts(&mut rnd, 120);
        let total = (rnd.below(500_000) + 100) as i64;
        let _ = c.execute(
            "INSERT INTO orders (id,tenant_id,user_id,order_no,status,total_cents,remark,created_at,paid_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            rusqlite::params![
                i as i64,
                (rnd.below(TENANTS as u64) + 1) as i64,
                uid,
                format!("NO{:010}", i),
                status as i64,
                total,
                if rnd.below(100) < 25 { Some("麻烦尽快发货".to_string()) } else { None },
                created,
                if status >= 2 && status != 9 { Some(created.clone()) } else { None }
            ],
        );

        for _ in 0..ITEMS_PER_ORDER {
            let pid = (rnd.below(PRODUCTS as u64) + 1) as i64;
            let _ = c.execute(
                "INSERT INTO order_items (id,order_id,product_id,quantity,price_cents,created_at)
                 VALUES (?1,?2,?3,?4,?5,?6)",
                rusqlite::params![
                    ((i - 1) * ITEMS_PER_ORDER) as i64 + rnd.below(ITEMS_PER_ORDER as u64) as i64,
                    i as i64, pid,
                    (rnd.below(5) + 1) as i64,
                    (rnd.below(100_000) + 100) as i64,
                    created
                ],
            );
        }

        // payments —— ⭐ 含失败状态，AI 问答"失败的订单"靠它
        if status >= 2 {
            let pay_status = if status == 9 { 3 } else { 2 };
            let _ = c.execute(
                "INSERT INTO payments (id,order_id,method,status,amount_cents,txn_id,error_msg,created_at)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
                rusqlite::params![
                    i as i64, i as i64,
                    [1i64, 2, 3][rnd.below(3) as usize],
                    pay_status,
                    total,
                    Some(format!("TXN{:016}", i)),
                    if pay_status == 3 { Some("上游渠道超时".to_string()) } else { None },
                    created
                ],
            );
        }
    }

    // sessions
    for i in 1..=SESSIONS {
        let uid = (rnd.below(USERS as u64) + 1) as i64;
        let _ = c.execute(
            "INSERT INTO sessions (id,user_id,token,device,ip,expires_at,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            rusqlite::params![
                i as i64, uid,
                format!("tok_{:032}", i),
                ["iOS","Android","Web","macOS","Windows"][rnd.below(5) as usize],
                format!("10.{}.{}.{}", rnd.below(255), rnd.below(255), rnd.below(255)),
                ts(&mut rnd, 30),
                ts(&mut rnd, 90)
            ],
        );
    }

    // audit_logs —— ⭐ 超大表，无主键（诊断会报）
    let actions = ["login","logout","update_profile","create_order","delete","export","grant","revoke"];
    let stmt = "INSERT INTO audit_logs (tenant_id,actor,action,target,payload,created_at)
                VALUES (?1,?2,?3,?4,?5,?6)";
    for _ in 0..AUDIT_LOGS {
        let _ = c.execute(
            stmt,
            rusqlite::params![
                (rnd.below(TENANTS as u64) + 1) as i64,
                format!("user{}", rnd.below(USERS as u64) + 1),
                actions[rnd.below(actions.len() as u64) as usize],
                format!("tbl:{}", rnd.below(ORDERS as u64) + 1),
                if rnd.below(100) < 30 { Some(format!("{{\"ip\":\"10.0.{}.{}\"}}", rnd.below(255), rnd.below(255))) } else { None },
                ts(&mut rnd, 60)
            ],
        );
    }

    // ⭐ 扩展表数据生成

    // tags
    let tag_names = ["热销","新品","推荐","限时","包邮","正品","保修","现货","预售","清仓"];
    for i in 1..=TAGS {
        let _ = c.execute(
            "INSERT INTO tags (id,name,color,sort_order) VALUES (?1,?2,?3,?4)",
            rusqlite::params![
                i as i64,
                if i <= 10 { tag_names[i-1].to_string() } else { format!("标签{}", i) },
                format!("#{:06x}", rnd.below(0xFFFFFF)),
                i as i64
            ],
        );
    }

    // product_tags
    for i in 1..=PRODUCTS {
        let tag_count = rnd.below(3) + 1;
        for _ in 0..tag_count {
            let _ = c.execute(
                "INSERT OR IGNORE INTO product_tags (product_id,tag_id) VALUES (?1,?2)",
                rusqlite::params![i as i64, (rnd.below(TAGS as u64) + 1) as i64],
            );
        }
    }

    // reviews
    for i in 1..=REVIEWS {
        let _ = c.execute(
            "INSERT INTO reviews (id,product_id,user_id,rating,content,is_anonymous,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            rusqlite::params![
                i as i64,
                (rnd.below(PRODUCTS as u64) + 1) as i64,
                (rnd.below(USERS as u64) + 1) as i64,
                (rnd.below(5) + 1) as i64,
                if rnd.below(100) < 70 {
                    Some(["很好","不错","一般","较差","很好用的商品","质量一般","性价比高","物流慢","包装好","与描述不符"][rnd.below(10) as usize].to_string())
                } else { None },
                if rnd.below(100) < 20 { 1 } else { 0 },
                ts(&mut rnd, 180)
            ],
        );
    }

    // coupons
    for i in 1..=COUPONS {
        let _ = c.execute(
            "INSERT INTO coupons (id,code,type,discount,min_amount,max_uses,used_count,starts_at,expires_at,is_active)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            rusqlite::params![
                i as i64,
                format!("CPN{:06}", i),
                (rnd.below(3) + 1) as i64,
                [10, 20, 50, 100, 200][rnd.below(5) as usize] as i64,
                [0, 100, 500, 1000][rnd.below(4) as usize] as i64,
                (rnd.below(500) + 50) as i64,
                rnd.below(100) as i64,
                ts(&mut rnd, 30),
                ts(&mut rnd, 365),
                if rnd.below(100) < 90 { 1 } else { 0 }
            ],
        );
    }

    // shipping
    for i in 1..=SHIPPING {
        let oid = (rnd.below(ORDERS as u64) + 1) as i64;
        let _ = c.execute(
            "INSERT INTO shipping (id,order_id,method,status,tracking_no,receiver,phone,address,shipped_at,delivered_at,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            rusqlite::params![
                i as i64, oid,
                (rnd.below(3) + 1) as i64,
                (rnd.below(5) + 1) as i64,
                Some(format!("SF{:012}", i)),
                format!("用户{}", i),
                format!("1{:010}", rnd.below(100_000_000)),
                format!("{}号", rnd.below(1000)),
                ts(&mut rnd, 30),
                if rnd.below(100) < 80 { Some(ts(&mut rnd, 10)) } else { None },
                ts(&mut rnd, 35)
            ],
        );
    }

    // invoices
    for i in 1..=INVOICES {
        let oid = (rnd.below(ORDERS as u64) + 1) as i64;
        let _ = c.execute(
            "INSERT INTO invoices (id,order_id,type,title,tax_no,amount_cents,status,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
            rusqlite::params![
                i as i64, oid,
                (rnd.below(3) + 1) as i64,
                format!("发票{}", i),
                format!("TAX{:015}", i),
                (rnd.below(100_000) + 100) as i64,
                (rnd.below(3) + 1) as i64,
                ts(&mut rnd, 60)
            ],
        );
    }

    // refunds
    for i in 1..=REFUNDS {
        let oid = (rnd.below(ORDERS as u64) + 1) as i64;
        let _ = c.execute(
            "INSERT INTO refunds (id,order_id,payment_id,amount_cents,reason,status,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            rusqlite::params![
                i as i64, oid, oid,
                (rnd.below(10_000) + 100) as i64,
                if rnd.below(100) < 80 { Some("不想要了".to_string()) } else { None },
                (rnd.below(3) + 1) as i64,
                ts(&mut rnd, 90)
            ],
        );
    }

    // notifications
    for i in 1..=NOTIFICATIONS {
        let _ = c.execute(
            "INSERT INTO notifications (id,user_id,type,title,content,is_read,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            rusqlite::params![
                i as i64,
                (rnd.below(USERS as u64) + 1) as i64,
                (rnd.below(5) + 1) as i64,
                ["订单状态变更","优惠活动","系统通知","物流更新","账户安全"][rnd.below(5) as usize].to_string(),
                if rnd.below(100) < 60 { Some("您有一条新消息".to_string()) } else { None },
                if rnd.below(100) < 50 { 1 } else { 0 },
                ts(&mut rnd, 30)
            ],
        );
    }

    // user_favorites
    for i in 1..=FAVORITES {
        let _ = c.execute(
            "INSERT OR IGNORE INTO user_favorites (id,user_id,product_id,created_at)
             VALUES (?1,?2,?3,?4)",
            rusqlite::params![
                i as i64,
                (rnd.below(USERS as u64) + 1) as i64,
                (rnd.below(PRODUCTS as u64) + 1) as i64,
                ts(&mut rnd, 180)
            ],
        );
    }

    // system_logs
    let levels = ["INFO", "WARN", "ERROR", "DEBUG"];
    let modules = ["auth", "order", "payment", "user", "product", "system"];
    for i in 1..=SYSTEM_LOGS {
        let _ = c.execute(
            "INSERT INTO system_logs (id,level,module,action,user_id,ip,user_agent,detail,created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            rusqlite::params![
                i as i64,
                levels[rnd.below(4) as usize],
                modules[rnd.below(6) as usize],
                ["login", "logout", "create", "update", "delete", "query"][rnd.below(6) as usize],
                (rnd.below(USERS as u64) + 1) as i64,
                format!("192.168.{}.{}", rnd.below(255), rnd.below(255)),
                ["Chrome/120", "Firefox/121", "Safari/17", "Edge/120"][rnd.below(4) as usize],
                if rnd.below(100) < 30 { Some(format!("Detail log entry {}", i)) } else { None },
                ts(&mut rnd, 30)
            ],
        );
    }
}

// ─────────────────────────────────────────────────────────────
// 确定性随机
// ─────────────────────────────────────────────────────────────

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13; x ^= x >> 7; x ^= x << 17;
        self.0 = x;
        x
    }
    fn below(&mut self, n: u64) -> u64 { if n == 0 { 0 } else { self.next() % n } }
}

fn surname_or_num(rnd: &mut Rng) -> String {
    if rnd.below(100) < 55 {
        ["伟","芳","娜","敏","静","磊","洋","勇","艳","杰","涛","明"][rnd.below(12) as usize].to_string()
    } else {
        format!("{}{}", ["小","阿"][rnd.below(2) as usize], ["明","华","强","雪","丽"][rnd.below(5) as usize])
    }
}

/// 生成 `days` 天内的时间戳字符串
fn ts(rnd: &mut Rng, days: u64) -> String {
    let secs_per_day = 86_400u64;
    let total = 1_780_000_000u64; // 固定基准，保证可复现
    let offset = rnd.below(days * secs_per_day);
    let t = total - offset;
    let days_since = t / secs_per_day;
    let rem = t % secs_per_day;
    let (h, mi, s) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    // 2026-01-01 起算
    let d = days_since;
    let (y, m, dd) = civil_from_days(d as i64);
    format!("{y:04}-{m:02}-{dd:02} {h:02}:{mi:02}:{s:02}")
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u64;
    let yoe = (doe - doe/1460 + doe/36524 - doe/146096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365*yoe + yoe/4 - yoe/100);
    let mp = (5*doy + 2)/153;
    let d = (doy - (153*mp+2)/5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

// ─────────────────────────────────────────────────────────────
// 元数据内省（PRAGMA）+ 中文注释 + 枚举
// ─────────────────────────────────────────────────────────────

/// ⭐ 内省数据库：返回**表（含虚拟表）+ 视图**，过滤掉所有用户不认识的内部对象。
///
/// 必须过滤的三类（否则 UI 里会冒出用户没建过的表）：
///   1. `sqlite_%`   —— SQLite 自己的内部表（sqlite_stat1/4 等）
///   2. FTS5 影子表   —— `<fts>_data` / `_idx` / `_content` / `_docsize` / `_config`
///                     它们由 FTS5 自动创建，用户从未见过
///   3. `_adb_` 前缀 —— 我们自己的内部映射表
pub fn introspect(c: &Sqlite) -> Vec<TableDef> {
    let Ok(mut stmt) = c.prepare(
        "SELECT type, name FROM sqlite_master \
         WHERE type IN ('table','view') ORDER BY type, name",
    ) else {
        return Vec::new()
    };
    let rows: Vec<(String, String)> = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .map(|it| it.filter_map(Result::ok).collect())
        .unwrap_or_default();

    // 先收集虚拟表名，用于影子表判定
    let vtables: Vec<String> = rows
        .iter()
        .filter(|(t, n)| t == "table" && is_virtual(c, n))
        .map(|(_, n)| n.clone())
        .collect();

    let mut out = Vec::new();
    for (kind, name) in rows {
        if name.starts_with("sqlite_") || name.starts_with("_adb_") {
            continue;
        }
        // FTS5 影子表：`<vtab>_<suffix>`
        if kind == "table" && vtables.iter().any(|v| is_shadow_of(&name, v)) {
            continue;
        }
        out.push(if kind == "view" {
            view_def(c, &name)
        } else {
            table_def(c, &name)
        });
    }
    out
}

/// 该对象是否为虚拟表（FTS5 / RTREE 等）
fn is_virtual(c: &Sqlite, name: &str) -> bool {
    c.query_row(
        "SELECT 1 FROM sqlite_master WHERE name = ?1 AND sql LIKE '%VIRTUAL TABLE%'",
        [name],
        |_| Ok(()),
    )
    .is_ok()
}

/// `name` 是否为虚拟表 `vtab` 的影子表
fn is_shadow_of(name: &str, vtab: &str) -> bool {
    const SUFFIXES: &[&str] = &["_data", "_idx", "_content", "_docsize", "_config"];
    name.strip_prefix(vtab)
        .map(|rest| SUFFIXES.contains(&rest))
        .unwrap_or(false)
}

/// ⭐ 视图定义：视图没有真正的主键/索引，列信息仍可用 `PRAGMA table_info` 拿。
fn view_def(c: &Sqlite, name: &str) -> TableDef {
    let id = format!("v:{name}");
    let mut columns = Vec::new();

    if let Ok(mut s) = c.prepare(&format!("PRAGMA table_info('{name}')")) {
        let rows = s.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        });
        if let Ok(it) = rows {
            for (cid, col, ty) in it.flatten() {
                let meta = META.iter().find(|m| m.0 == name && m.1 == col).copied();
                columns.push(ColumnDef {
                    id: format!("{id}:c{cid}"),
                    name: col.clone(),
                    category: categorize(&ty, &col),
                    is_large: false,
                    ty: if ty.is_empty() { "视图列".into() } else { ty },
                    // 视图列的 nullable 未知 —— 标 true，避免 UI 误标 NOT NULL
                    nullable: true,
                    default_expr: None,
                    comment: meta.and_then(|m| m.2).map(str::to_string),
                    is_pk: false,
                    is_fk: false,
                    enum_values: None,
                    ord: cid,
                });
            }
        }
    }

    TableDef {
        id,
        name: name.to_string(),
        comment: VIEW_COMMENTS
            .iter()
            .find(|(v, _)| *v == name)
            .map(|(_, c)| (*c).to_string())
            .or(Some("视图（只读）".into())),
        kind: "view".into(),
        // 视图不跑 COUNT —— 大视图会全扫
        row_estimate: None,
        size_bytes: None,
        columns,
        indexes: Vec::new(),
        foreign_keys: Vec::new(),
    }
}

/// ⭐ 视图注释
const VIEW_COMMENTS: &[(&str, &str)] = &[
    ("v_order_summary", "订单汇总视图（订单 + 用户 + 明细聚合）"),
    ("v_product_sales", "商品销售统计视图"),
    ("v_user_stats", "用户消费统计视图"),
];

fn table_def(c: &Sqlite, name: &str) -> TableDef {
    let id = format!("t:{name}");
    let mut columns = Vec::new();

    if let Ok(mut s) = c.prepare(&format!("PRAGMA table_info('{name}')")) {
        let rows = s.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,             // cid
                r.get::<_, String>(1)?,          // name
                r.get::<_, String>(2)?,          // type
                r.get::<_, i64>(3)?,             // notnull
                r.get::<_, Option<String>>(4)?,  // dflt
                r.get::<_, i64>(5)?,             // pk
            ))
        });
        if let Ok(it) = rows {
            for row in it.flatten() {
                let (cid, col, ty, notnull, dflt, pk) = row;
                let meta = META.iter().find(|m| m.0 == name && m.1 == col).copied();
                let comment: Option<String> = meta.and_then(|m| m.2).map(str::to_string);
                let enum_values = meta.and_then(|m| {
                    m.3.map(|s| {
                        s.split('|').filter_map(|kv| {
                            kv.split_once(':')
                                .map(|(k, v)| (k.to_string(), v.to_string()))
                        })
                        .collect::<std::collections::BTreeMap<_, _>>()
                    })
                });
                columns.push(ColumnDef {
                    id: format!("{id}:c{cid}"),
                    name: col.clone(),
                    category: categorize(&ty, &col),
                    is_large: col == "remark" || col == "payload" || col == "attributes",
                    ty,
                    nullable: notnull == 0,
                    default_expr: dflt,
                    comment,
                    is_pk: pk > 0,
                    is_fk: is_fk_column(name, &col),
                    enum_values,
                    ord: cid,
                });
            }
        }
    }

    let mut indexes = Vec::new();
    if let Ok(mut s) = c.prepare(&format!("PRAGMA index_list('{name}')")) {
        let rows = s.query_map([], |r| {
            Ok((
                r.get::<_, String>(1)?,          // name
                r.get::<_, i64>(2)?,             // unique
                r.get::<_, String>(3)?,          // origin (c/u/pk)
                r.get::<_, Option<i64>>(4)?,     // partial
            ))
        });
        if let Ok(it) = rows {
            for (i, row) in it.flatten().enumerate() {
                let (iname, unique, origin, _) = row;
                if origin == "c" { continue; }  // 跳过 SQLite 自动索引
                let mut cols = Vec::new();
                if let Ok(mut ic) = c.prepare(&format!("PRAGMA index_info('{iname}')")) {
                    if let Ok(rit) = ic.query_map([], |r| r.get::<_, Option<String>>(2)) {
                        for cc in rit.flatten().flatten() { cols.push(cc); }
                    }
                }
                indexes.push(IndexDef {
                    id: format!("{id}:i{i}"),
                    name: iname,
                    unique: unique == 1,
                    primary: origin == "pk",
                    columns: cols,
                    def: None,
                });
            }
        }
    }

    let row_estimate = estimate_rows(c, name);

    TableDef {
        id,
        name: name.to_string(),
        comment: TABLE_COMMENTS
            .iter()
            .find(|(t, _)| *t == name)
            .map(|(_, c)| (*c).to_string()),
        kind: "table".into(),
        row_estimate,
        size_bytes: None,
        columns,
        indexes,
        foreign_keys: Vec::new(),
    }
}

fn estimate_rows(c: &Sqlite, table: &str) -> Option<i64> {
    c.query_row(&format!("SELECT COUNT(*) FROM \"{table}\""), [], |r| r.get(0))
        .ok()
}

fn is_fk_column(_table: &str, col: &str) -> bool {
    col == "user_id" || col == "order_id" || col == "product_id" || col == "tenant_id"
}

fn categorize(ty: &str, col: &str) -> ColumnType {
    let u = ty.to_uppercase();
    if matches!(col, "status" | "level" | "is_active" | "is_default") {
        return ColumnType::Enum;
    }
    if u.contains("INT") && !u.contains("POINT") {
        return ColumnType::Number;
    }
    if u.contains("REAL") || u.contains("FLOA") || u.contains("DOUB") {
        return ColumnType::Number;
    }
    if u.contains("BLOB") { return ColumnType::Binary; }
    if col == "created_at" || col == "paid_at" || col == "expires_at" {
        return ColumnType::DateTime;
    }
    if col == "attributes" || col == "profile" || col == "payload" || col == "preferences" {
        return ColumnType::Json;
    }
    if col == "avatar_url" || col == "image_url" { return ColumnType::Text; }
    if u.contains("CHAR") || u.contains("TEXT") || u.contains("CLOB") {
        return ColumnType::Text;
    }
    ColumnType::Unknown
}

/// ⭐ 表注释（真实项目来自数据库原生注释或 .adbctx）
const TABLE_COMMENTS: &[(&str, &str)] = &[
    ("tenants", "租户表（多租户隔离，tenant_id=0 表示全部租户）"),
    ("users", "用户主表"),
    ("user_profiles", "用户扩展档案"),
    ("categories", "商品分类（树形）"),
    ("products", "商品表"),
    ("addresses", "收货地址（敏感字段）"),
    ("orders", "订单主表（金额单位为分）"),
    ("order_items", "订单明细"),
    ("payments", "支付流水"),
    ("sessions", "登录会话"),
    ("audit_logs", "审计日志（无主键 · 20 万行 · 诊断示例表）"),
    ("tags", "商品标签"),
    ("product_tags", "商品-标签关联"),
    ("reviews", "商品评价"),
    ("coupons", "优惠券"),
    ("order_coupons", "订单-优惠券关联"),
    ("shipping", "物流信息"),
    ("invoices", "发票"),
    ("refunds", "退款记录"),
    ("notifications", "用户通知"),
    ("user_favorites", "用户收藏"),
    ("system_logs", "系统日志"),
];

/// ⭐ 列注释 + 枚举含义（真实项目来自数据库原生注释或 .adbctx）
const META: &[(&str, &str, Option<&str>, Option<&str>)] = &[
    // (表, 列, 列注释, 枚举 "k:v|k:v")
    ("tenants", "plan",    Some("套餐类型"),   None),
    ("tenants", "max_users", Some("最大用户数"), None),
    ("users",   "email",   Some("邮箱（敏感字段，建议脱敏）"), None),
    ("users",   "name",    Some("姓名（敏感字段）"), None),
    ("users",   "phone",   Some("手机号（敏感字段）"), None),
    ("users",   "level",   Some("用户等级"),   Some("1:普通|2:VIP|3:SVIP")),
    ("users",   "status",  Some("账号状态"),   Some("1:正常|0:禁用")),
    ("users",   "balance_cents", Some("账户余额（单位：分，不是元）"), None),
    ("users",   "profile", Some("JSON 扩展信息"), None),
    ("users",   "created_at", Some("注册时间"), None),
    ("user_profiles", "nickname", Some("昵称"), None),
    ("categories", "name", Some("分类名"), None),
    ("products", "price_cents", Some("单价（单位：分）"), None),
    ("products", "attributes",  Some("JSON 属性"),  None),
    ("products", "image_url",   Some("图片 URL"),  None),
    ("products", "is_active",   Some("是否上架"),   Some("1:上架|0:下架")),
    ("addresses", "receiver", Some("收货人姓名（敏感字段）"), None),
    ("addresses", "phone",    Some("收货电话（敏感字段）"), None),
    ("addresses", "detail",   Some("详细地址（敏感字段）"), None),
    ("orders",   "order_no",     Some("订单号（业务唯一）"), None),
    ("orders",   "status",       Some("订单状态"),
     Some("1:待支付|2:已支付|3:已发货|4:已完成|9:已取消")),
    ("orders",   "total_cents",  Some("订单总额（单位：分）"), None),
    ("orders",   "remark",       Some("买家备注（长文本）"), None),
    ("orders",   "created_at",   Some("下单时间"), None),
    ("orders",   "paid_at",      Some("支付时间（未支付为 NULL）"), None),
    ("order_items", "quantity",  Some("购买数量"), None),
    ("order_items", "price_cents", Some("成交单价（单位：分）"), None),
    ("payments", "status",    Some("支付状态"), Some("1:待支付|2:成功|3:失败")),
    ("payments", "method",    Some("支付方式"), Some("1:支付宝|2:微信|3:银行卡")),
    ("payments", "error_msg", Some("失败原因（成功时为 NULL）"), None),
    ("payments", "created_at", Some("支付发起时间"), None),
    ("sessions", "device",     Some("设备类型"), None),
    ("sessions", "expires_at", Some("过期时间"), None),
    ("audit_logs", "action",   Some("操作类型"), None),
    ("audit_logs", "payload",  Some("操作载荷（长 JSON）"), None),
    ("audit_logs", "created_at", Some("发生时间"), None),
    ("tags", "name", Some("标签名"), None),
    ("tags", "color", Some("标签颜色"), None),
    ("reviews", "rating", Some("评分（1-5）"), Some("1:很差|2:较差|3:一般|4:较好|5:很好")),
    ("reviews", "content", Some("评价内容"), None),
    ("reviews", "is_anonymous", Some("是否匿名"), Some("1:匿名|0:实名")),
    ("coupons", "code", Some("优惠券码"), None),
    ("coupons", "type", Some("优惠类型"), Some("1:满减|2:折扣|3:立减")),
    ("coupons", "discount", Some("优惠金额（分）"), None),
    ("shipping", "method", Some("配送方式"), Some("1:快递|2:自提|3:同城")),
    ("shipping", "status", Some("物流状态"), Some("1:待发货|2:已发货|3:运输中|4:已签收|5:异常")),
    ("invoices", "type", Some("发票类型"), Some("1:个人|2:企业")),
    ("invoices", "title", Some("发票抬头"), None),
    ("refunds", "reason", Some("退款原因"), None),
    ("refunds", "status", Some("退款状态"), Some("1:待处理|2:已退款|3:已拒绝")),
    ("notifications", "type", Some("通知类型"), Some("1:订单|2:优惠|3:系统|4:物流|5:安全")),
    ("notifications", "title", Some("通知标题"), None),
    ("notifications", "is_read", Some("是否已读"), Some("1:已读|0:未读")),
    ("user_favorites", "created_at", Some("收藏时间"), None),
    ("system_logs", "level", Some("日志级别"), Some("INFO:信息|WARN:警告|ERROR:错误|DEBUG:调试")),
    ("system_logs", "module", Some("模块"), None),
    ("system_logs", "action", Some("操作"), None),
];


// ─────────────────────────────────────────────────────────────
// ⭐ 诊断（U2/M3 的演示；这里先给出可验证的规则）
// ─────────────────────────────────────────────────────────────

pub struct Finding {
    pub level: &'static str,
    pub title: String,
    pub impact: String,
    pub action: String,
}

pub fn diagnostics(t: &TableDef) -> Vec<Finding> {
    let mut v = Vec::new();

    // ⭐ 视图是只读派生对象：主键/索引/外键这些「表结构」规则对它没有意义，
    //    否则每个视图都会被误报「没有主键」。
    if t.kind == "view" {
        return v;
    }

    if !t.columns.iter().any(|c| c.is_pk) {
        v.push(Finding {
            level: "critical",
            title: format!("{} 没有主键", t.name),
            impact: "UPDATE/DELETE 无法生成安全 WHERE 条件".into(),
            action: "添加主键".into(),
        });
    }

    // 外键列无索引
    for c in &t.columns {
        if !c.is_fk { continue; }
        let covered = t.indexes.iter().any(|i| i.columns.first() == Some(&c.name));
        if !covered {
            v.push(Finding {
                level: "warning",
                title: format!("{}.{} 有外键但无索引", t.name, c.name),
                impact: "JOIN 会全表扫描".into(),
                action: format!("创建索引 ({})", c.name),
            });
        }
    }

    // 注释覆盖率
    if t.columns.iter().all(|c| c.comment.is_some()) && !t.comment.is_some() {
        v.push(Finding {
            level: "info",
            title: format!("{} 表缺少注释", t.name),
            impact: "AI 和人都看不懂这张表".into(),
            action: "补充表注释".into(),
        });
    }

    // 未标注的枚举
    for c in &t.columns {
        if c.category == ColumnType::Enum && c.enum_values.is_none() {
            v.push(Finding {
                level: "warning",
                title: format!("{}.{} 是枚举但未标注取值含义", t.name, c.name),
                impact: "AI 生成 SQL 时只能猜（数据库注释里也没有）".into(),
                action: format!("为 {} 添加枚举说明", c.name),
            });
        }
    }

    // 敏感字段
    for c in &t.columns {
        let n = c.name.to_lowercase();
        if matches!(n.as_str(), "phone" | "email" | "idcard" | "receiver")
            && c.comment.as_deref().map(|x| x.contains("敏感")).unwrap_or(false)
        {
            v.push(Finding {
                level: "warning",
                title: format!("{}.{} 是敏感字段", t.name, c.name),
                impact: "库内明文存储".into(),
                action: "查看脱敏方案".into(),
            });
        }
    }

    v
}

// ─────────────────────────────────────────────────────────────
// 测试：验证「演示库真的能用」，而不只是「代码能编译」
// ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection as Sqlite;
    use std::path::PathBuf;

    /// 物化一次演示库。返回路径的 drop 时自动清理。
    struct TempDb(PathBuf);
    impl TempDb {
        fn new(tag: &str) -> Self {
            let mut p = std::env::temp_dir();
            // 用 tag + pid 隔离，并行跑测试时不会互相覆盖
            p.push(format!("agenticdb-test-{}-{}.db", tag, std::process::id()));
            let _ = std::fs::remove_file(&p);
            let _ = std::fs::remove_file(format!("{}-wal", p.display()));
            let _ = std::fs::remove_file(format!("{}-shm", p.display()));
            materialize(&p.to_string_lossy());
            Self(p)
        }
        fn open(&self) -> Sqlite {
            Sqlite::open(&self.0).expect("演示库应可打开")
        }
    }
    impl Drop for TempDb {
        fn drop(&mut self) {
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{}", self.0.display(), suffix));
            }
        }
    }

    fn scalar_i64(c: &Sqlite, sql: &str) -> i64 {
        c.query_row(sql, [], |r| r.get(0))
            .unwrap_or_else(|e| panic!("查询失败: {sql}\n{e}"))
    }

    /// ⭐ 回归测试：FTS 索引里必须真的有词。
    ///
    /// 之前 `INSERT INTO product_search ... SELECT ... FROM products`
    /// 被写在 DDL 批次里，早于 seed 执行，于是索引里有 0 个词，
    /// `MATCH '无线'` 永远返回空 —— 表现为「全文搜索功能坏了」。
    #[test]
    fn fts_index_is_actually_populated() {
        let db = TempDb::new("fts");
        let c = db.open();

        // 索引自身的行数（外部内容表：查内容表会骗人）
        let tokens = scalar_i64(&c, "SELECT count(*) FROM product_search_data");
        assert!(tokens > 0, "FTS 索引是空的 —— seed 与建索引的顺序错了");

        let hits = scalar_i64(
            &c,
            "SELECT count(*) FROM product_search WHERE product_search MATCH '无线'",
        );
        assert!(hits > 0, "中文全文检索必须能命中「无线」，实得 {hits} 条");
    }

    /// ⭐ 中英文都要能检索到。
    #[test]
    fn fts_matches_chinese_and_ascii() {
        let db = TempDb::new("fts-mix");
        let c = db.open();
        // 中文两字词 + 英文
        for q in ["无线", "耳机", "键盘", "SKU"] {
            let n = scalar_i64(
                &c,
                &format!("SELECT count(*) FROM product_search WHERE product_search MATCH '{q}'"),
            );
            assert!(n > 0, "检索 {q:?} 应有结果，实得 {n} 条");
        }
        // ⭐ 含连字符的 SKU 必须加引号，否则 FTS5 当成语法（`no such column: 000001`）
        let sku = scalar_i64(
            &c,
            "SELECT count(*) FROM product_search WHERE product_search MATCH '\"SKU-000001\"'",
        );
        assert!(sku > 0, "加引号检索 SKU 应有结果，实得 {sku} 条");
    }

    /// ⭐ 防回归：别把 tokenizer 换成 trigram。
    ///
    /// trigram 要求查询串 ≥3 字符，而中文检索绝大多数是**两字词**。
    /// 实测换成 trigram 后 `无线`/`耳机`/`键盘` 全部 0 条 —— 功能直接报废。
    #[test]
    fn fts_tokenizer_can_still_match_two_character_chinese() {
        let db = TempDb::new("fts-tok");
        let c = db.open();
        // 两字词必须能命中，否则换成 trigram 就会静默失效
        let two_char_hits: i64 = c
            .query_row(
                "SELECT count(*) FROM product_search WHERE product_search MATCH '耳机'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            two_char_hits > 0,
            "两字中文词检索不到 —— 检查是否被换成了 trigram 分词器"
        );
    }

    /// ⭐ 记录并锁定 tokenizer 的**已知边界**，避免以后有人误判成 bug。
    ///
    /// unicode61 按空白切词，所以：
    ///   · 「无线 充电器」能被 `MATCH '无线'` 命中   ✅
    ///   · 「无线充电器」（无空格）整句是一个 token，搜 `无线` 命中不了 ❌
    /// 正确用法是把它当**短语查询**（加引号）而不是子串查询。
    #[test]
    fn fts_known_limit_is_phrase_query_not_substring() {
        let c = Sqlite::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT);
             INSERT INTO t VALUES (1,'无线 充电器'),(2,'无线充电器');
             CREATE VIRTUAL TABLE f USING fts5(name, content='t', content_rowid='id');
             INSERT INTO f(rowid,name) SELECT id,name FROM t;",
        )
        .unwrap();

        // 子串查询：无空格的那行命中不了（预期行为）
        let sub = scalar_i64(&c, "SELECT count(*) FROM f WHERE f MATCH '无线'");
        assert_eq!(sub, 1, "子串查询只应命中带空格的那一行");

        // 短语查询：能精确命中无空格的那一行
        let phrase =
            scalar_i64(&c, "SELECT count(*) FROM f WHERE f MATCH '\"无线充电器\"'");
        assert_eq!(phrase, 1, "短语查询必须能命中无空格的中文整句");
    }

    /// ⭐ 含连字符/标点的检索词必须用 FTS5 引号，否则被当成语法。
    #[test]
    fn fts_special_characters_need_quoting() {
        let db = TempDb::new("fts-quote");
        let c = db.open();
        // 不加引号：FTS5 把 `-` 当运算符 → 直接 SQL 报错
        let quoted = scalar_i64(
            &c,
            "SELECT count(*) FROM product_search WHERE product_search MATCH '\"SKU-000001\"'",
        );
        assert!(quoted > 0, "加引号后应能精确命中 SKU");
    }

    /// ⭐ FTS 必须跟得上 `products` 的写操作（外部内容表不会自动同步）。
    #[test]
    fn fts_stays_in_sync_after_write() {
        let db = TempDb::new("fts-sync");
        let c = db.open();

        // 名称里**必须有空格**：unicode61 按空白切词，
        // 否则整句是一个 token，检索不到子词（那是分词器语义，不是同步问题）。
        c.execute_batch(
            "INSERT INTO products (id, sku, name, price_cents, created_at)
             VALUES (999999, 'SKU-ZZTEST', '降噪 耳机', 100, '2026-01-01 00:00:00')",
        )
        .expect("插入应成功");

        let n = scalar_i64(
            &c,
            "SELECT count(*) FROM product_search WHERE product_search MATCH '降噪'",
        );
        assert!(n > 0, "新插入的商品没进全文索引 —— 同步触发器没生效");
    }

    /// ⭐ 内省结果里不能混进用户没建过的对象。
    #[test]
    fn introspect_hides_internal_objects() {
        let db = TempDb::new("introspect");
        let c = db.open();
        let names: Vec<(String, String)> = {
            let mut st = c
                .prepare(
                    "SELECT type,name FROM sqlite_master WHERE type IN ('table','view')",
                )
                .unwrap();
            let v = st
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
                .unwrap();
            v.filter_map(Result::ok).collect()
        };

        // 库里的 FTS5 影子表数量（用来证明过滤真的生效了）
        let shadows = names
            .iter()
            .filter(|(t, n)| {
                t == "table"
                    && ["_data", "_idx", "_content", "_docsize", "_config"]
                        .iter()
                        .any(|s| n.ends_with(s))
            })
            .count();
        assert!(shadows > 0, "演示库本应包含 FTS 影子表，否则这条测试没有意义");

        let shown = introspect(&c);
        for t in &shown {
            assert!(!t.name.starts_with("sqlite_"), "内省泄露了 sqlite_ 内部表: {}", t.name);
            assert!(!t.name.starts_with("_adb_"), "内省泄露了 _adb_ 内部表: {}", t.name);
            for suffix in ["_data", "_idx", "_content", "_docsize", "_config"] {
                assert!(
                    !t.name.ends_with(suffix) || t.name == "product_search",
                    "内省泄露了 FTS 影子表: {}",
                    t.name
                );
            }
        }

        // ⭐ 视图必须能被看见（Navicat 会显示视图，用户预期能看到）
        assert!(
            shown.iter().any(|t| t.kind == "view" && t.name == "v_order_summary"),
            "视图 v_order_summary 没出现在内省结果里"
        );
        // ⭐ 虚拟表本身要保留（它是用户可见的搜索对象）
        assert!(
            shown.iter().any(|t| t.name == "product_search"),
            "FTS 虚拟表 product_search 没出现在内省结果里"
        );
    }

    /// ⭐ 诊断不能把视图误报成「没有主键」—— 视图本就没有主键。
    #[test]
    fn diagnostics_stays_quiet_about_views() {
        let db = TempDb::new("diag");
        let c = db.open();
        let shown = introspect(&c);

        let view = shown
            .iter()
            .find(|t| t.kind == "view")
            .expect("演示库应至少有一个视图");
        assert!(
            diagnostics(view).is_empty(),
            "视图 {} 被误报了 {:?} 条问题",
            view.name,
            diagnostics(view).len()
        );

        // 对照：真正的「问题表」audit_logs 仍然必须被报出来，
        // 否则这条测试只是因为「什么都不报」而通过。
        let audit = shown
            .iter()
            .find(|t| t.name == "audit_logs")
            .expect("演示库应有 audit_logs");
        assert!(
            !diagnostics(audit).is_empty(),
            "audit_logs 无主键，必须被诊断出来"
        );
    }
}
