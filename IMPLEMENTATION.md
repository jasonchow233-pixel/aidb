# AgenticDB — 实施方案 v4.0

> **执行清单见 `TODO.md` v4.0**（理想排期）与 `CLIENT-TODO.md`（实际待办）。
> 本文件是**架构与理由**。📖 文档关系见 `README.md`。
>
> **v4 变更**：吸收 `DBX-ARCHITECTURE-STUDY.md` 源码研读结论
> 1. crate 16 → **24**（facade + per-driver 分层）
> 2. 连接配置 struct → **YAML 声明 + 能力矩阵**
> 3. 新增 **传输家族表**（`transferObjectFamily`）
> 4. codegen 补全 **五道保险闭环**
> 5. 补 `opt-level="s"` / `line-tables-only` / **chunk 预算**
> 6. 国产库走 **`variant.rs` 补丁层**（而非 DBX 的 fork 3 crate）
>
> 日期：2026-10-16 ｜ 配套 `PRODUCT.md` `UI-SPEC.md` `DEV-SPEC.md` `TODO.md`

---

## 目录

- [1. 技术栈与语言选型](#1-技术栈与语言选型)
- [2. ⭐ 声明层：架构的核心资产](#2--声明层架构的核心资产)
- [3. 24 crate 分层与依赖规则](#3-24-crate-分层与依赖规则)
- [4. 驱动层：facade + 补丁层](#4-驱动层facade--补丁层)
- [5. codegen 五道保险](#5-codegen-五道保险)
- [6. 构建与体积预算](#6-构建与体积预算)
- [7. AI 怎么做好用](#7-ai-怎么做好用)
- [8. 精简守则](#8-精简守则)
- [9. Navicat 级体验的 12 个原语](#9-navicat-级体验的-12-个原语)
- [10. 刻意不做的事](#10-刻意不做的事)
- [11. 阶段总览与门禁](#11-阶段总览与门禁)
- [12. 度量体系](#12-度量体系)
- [13. 风险与止损](#13-风险与止损)

---

# 1. 技术栈与语言选型

## 1.1 五种语言，四种代码 + 一种声明

```text
Rust        ⭐ 内核：驱动 / SQL / 变更 / 脱敏 / 诊断 / 审计 / MCP / CLI / Web
TypeScript  Vue 3 前端
Java        JDBC Agent（唯一用途：复用厂商驱动，不是写业务逻辑）
YAML        ⭐ 声明层：连接类型 / 方言 / 传输家族 / 风险规则 / 脱敏规则
❌ Go / Python / Kotlin / Electron
```

**声明层不是附属品，是架构资产。** 新增一个数据库 = 1 个 YAML，0 行代码。

> ⚠️ 若团队无人熟练 Rust → 改 **Go + Wails**（同样单二进制，前端仍是 Web）。
> 不要为"技术先进"硬上 Rust，也不要为"招人容易"硬上 Electron。

## 1.2 技术栈定稿

| 层 | 选型 | 理由 |
|---|---|---|
| 桌面壳 | **Tauri 2.10** | 25MB vs Electron 150MB+ |
| 前端 | **Vue 3.5 + Vite 7 + Pinia 3** | 与 Rust 内集成最顺 |
| 样式 | Tailwind 4 + reka-ui + CVA | 源码级定制 |
| 编辑器 | **CodeMirror 6** | 比 Monaco 轻 |
| 表格 | **AG Grid Community** | ⭐ 不自研（DBX 自研 Canvas 表格 = 123 文件） |
| 核心 | **Rust 1.88+ / Tokio** | 体积与并发 |
| 长尾驱动 | **Java JDBC Agent** | ⭐ 10% 工作量覆盖 80% 库 |
| Web | axum 0.8 + WebSocket | 与桌面端共用 |
| 存储 | SQLite（`sqlx` + **FTS5**） | 中文/拼音检索靠它 |
| MCP | 官方 `rmcp` | 不自研 JSON-RPC |
| CLI | clap（derive） | 必须 `--json` |

## 1.3 依赖预算

| 层 | 上限 | CI 检查 |
|---|---|---|
| Rust 直接依赖 | ≤ 45 | `cargo tree --depth 1` 计数 |
| 前端依赖 | ≤ 30（M1 只需 20） | `package.json` 计数 |
| **首屏 chunk** | ≤ 800KB gzip | `check-bundle.ts` |
| ag-grid chunk | ≤ 350KB | 同上 |
| codemirror chunk | ≤ 400KB | 同上 |
| echarts chunk | ≤ 300KB（拆 4 块） | 同上 |

### 砍掉的依赖及原因

| 依赖 | DBX 用了 | 我们 | 原因 |
|---|---|---|---|
| `cytoscape` + `fcose` | ✅ | ❌ | React Flow 的 dagre 够用 |
| `leaflet` + `proj4` | ✅ | ❌ | 地理可视化非刚需 |
| `vue-virtual-scroller` | ✅ | ❌ | AG Grid 自带 |
| `dom-to-image-more` | ✅ | ❌ | 截图导出延后 |
| `pako` | ✅ | ❌ | Rust 侧 flate2 |
| `dayjs` | ✅ | ❌ | `@internationalized/date` 够 |
| `diff` | ✅ | ❌ | diff 在 Rust 侧做（后端职责） |
| `pinyin-pro` | ✅ | ❌ | ⭐ 拼音索引 Rust 侧算好，只传结果 |
| `vue-virtual-scroller` | ✅ | ❌ | AG Grid 自带 |
| `@tauri-apps/plugin-*` 全家桶 | ✅ | ⚠️ 只留 3 个 | 其余用不到 |

---

# 2. ⭐ 声明层：架构的核心资产

> 抄自 DBX `plugins/connection-types/*.yaml` + `crates/dbx-types/build.rs`
> 完整脚手架见 `scaffold/declarations/` 与 `scaffold/crates/adb-types/build.rs`

## 2.1 目录

```text
declarations/
├── schema/connection.schema.json      JSON Schema（编辑器提示 + CI 校验）
├── connections/*.yaml                ⭐ 连接类型（连接表单 + 能力 + 引擎特性）
├── dialects/*.yaml                   SQL 方言（语法高亮 + 格式化规则）
├── transfer-families/*.yaml          ⭐⭐ 传输规则家族（类型映射复用）
├── risks/{postgres,mysql}.yaml       ⭐ DDL 风险规则
├── masks/zh-CN.yaml                  ⭐ 中文敏感字段规则
└── mappings/type-map.yaml            跨方言类型映射（细粒度补充）
```

## 2.2 能力声明矩阵（最重要的一块）

### `capabilities` —— 全部显式 true/false

```yaml
capabilities:
  queryExecution: true
  metadataBrowse: true
  objectBrowser: true
  objectSource: true
  schemaSearch: true
  diagram: true
  tableDataEdit: true
  tableStructureEdit: true
  tableImport: true
  dataTransfer: true
  sqlFileExecution: true
  databaseCreate: false      # ⭐ 明确关闭，不是"不写"
  fieldLineage: true
  sqlExplain: true
  userAdmin: false           # ⭐ 明确关闭
  specializedSurface: false
```

**为什么"显式 false"比"缺失"重要**

```yaml
# ❌ 缺失即 false：半年后有人加了这个能力 → 静默生效 → 用户在生产库建库
# ✅ 显式 false：  改动时 review 会看到 YAML diff
```

> **这是"能力必须声明、不能靠人肉发现"的落地方式。**
> 直接根治 DBX 的 `#12124`（达梦导出错误生成 `IDENTITY_INSERT`）、`#11794`（丢失主键自增）。

### 三条配套规则（抄自 DBX `connection-types/README.md`）

```text
1. specializedSurface: true 只在产品有专属管理界面时使用
   （Redis 浏览器 / MQ 控制台 / Mongo 文档浏览器）
   → 防止"什么都关掉然后自己写一套"

2. 否则至少启用一项 capability（build.rs 强制 assert）

3. formKind 是有限枚举，不是任意 YAML 表单引擎
   → 防止连接表单无限膨胀
```

### `traits` —— 引擎特性，驱动 UI 提示

```yaml
traits:
  schemaAware: true              # 是否有 schema 层级（SQLite=false）
  singleDatabase: true           # 单库引擎（达梦/Oracle 模式）
  identityKind: identity         # ⭐ identity|autoIncrement|rowguid|sequence|none
  sequenceObjects: false         # ⭐ false → UI 隐藏序列工作台
  transactionalDdl: false        # ⭐⭐ false → UI 提示「DDL 会自动提交」
  onlineDdlConcurrent: true      # CREATE INDEX CONCURRENTLY / ONLINE
  multipleResultsets: true
  savepoints: true
  explainFormats: [json, text]   # ⭐ UI 只渲染支持的格式
  maxParams: 65535
  identifierQuote: "\""
  paramStyle: numbered           # ⭐ $1 / ? / :name
```

### UI 消费方式

```ts
import { can, warnsImplicitDdlCommit, showsSequenceWorkspace, explainFormats }
  from "virtual:adb-declarations";

// MySQL 结构编辑器顶部
warnsImplicitDdlCommit("mysql")  // → true
// 显示: ⚠ MySQL 的 DDL 会隐式提交，无法通过事务回滚。

// 达梦没有序列对象
showsSequenceWorkspace("dameng")  // → false
// → 侧栏不渲染序列节点

// 达梦 EXPLAIN 只有文本格式
explainFormats("dameng")          // → ["text"]
// → 只显示「文本」选项，不显示 JSON/树
```

> **⭐ 组件不要 `if (conn === "mysql")`。一律问声明。**
> 这样新增数据库时 UI 自动正确，不需要改组件。

## 2.3 ⭐ 传输家族（`transferObjectFamily`）

### 为什么需要

达梦、人大金仓(O模式)、OceanBase(Oracle模式)、GBase8s、高斯、南大通用 ——
它们的 DDL 语法、类型映射、IDENTITY 关键字**几乎一样**。
逐个实现 = 4 份重复代码 + 4 处不一致的 bug。

### 实现

```yaml
# declarations/transfer-families/oracle.yaml
schemaVersion: 1
id: oracle
label: Oracle 兼容家族
members: [oracle, dameng, oceanbase-oracle, kingbase-oracle, gbase8s, highgo]

supportsTransactionalDdl: false
identityKeyword: IDENTITY          # ⭐ 不是 AUTO_INCREMENT
quoteIdent: '"'

typeMapping:
  NUMBER:
    lossy: true                   # ⭐ 精度可能丢失
    to: { postgres: NUMERIC(38,10), mysql: DECIMAL(38,10), oracle: NUMBER(38,10) }
  DATE:
    lossy: true                   # ⭐ Oracle DATE 含时间，PG date 不含
    to: { postgres: TIMESTAMP, mysql: DATETIME }
  CLOB:
    lossy: false
    to: { postgres: TEXT, mysql: LONGTEXT, sqlite: TEXT }
  BLOB:
    lossy: false
    to: { postgres: BYTEA, mysql: LONGBLOB, sqlite: BLOB }
```

**四份家族已产出**：`oracle`（6 成员）/ `postgres`（8）/ `mysql`（5）/ `sqlserver`（3）

### 效果

```text
新增一个 Oracle 兼容国产库
  = 1 个 connections/*.yaml（4 行元信息）
  + 往 oracle.yaml 的 members 加 1 个 id
  → 传输规则零改动
```

### ⭐ `lossy: true` 的处理

```ts
import { mapType } from "virtual:adb-declarations";

const r = mapType("oracle", "postgresql", "NUMBER");
// → { target: "NUMERIC(38,10)", lossy: true, reason: "NUMBER → NUMERIC(38,10) 可能丢失精度或语义" }
```

**UI 必须**：
```text
⚠ 3 个字段的类型映射可能丢失精度

  表.字段              源类型      目标类型          风险
  orders.amount       NUMBER      NUMERIC(38,10)   🟠 精度范围可能收窄
  orders.created      DATE        TIMESTAMP        🟠 时区语义变化
  orders.rate         NUMBER(5,2) NUMERIC(38,10)   ✅ 安全

                                      [取消]  [仍然继续]
```

> **精度丢失必须是用户决定的，不是静默发生。**
> 这是 DBX `#9667`（Oracle→OceanBase 金额精度丢失）和 `#10556`（大数转整数溢出）的根因。

---

# 3. 24 crate 分层与依赖规则

## 3.1 结构（facade 模式）

```text
应用
  adb-desktop              Tauri 壳
编排
  adb-core                 connection/query/schema/data/ai
基础（零业务）
  adb-types                ⭐ build.rs 消费 declarations → Rust enum
  adb-platform             进程/路径/代理
  adb-secret               ⭐ 凭据（唯一实现）
SQL（facade + 4）
  adb-sql ─┬─ adb-sql-core      解析器
           ├─ adb-sql-dialect   方言注册 + ⭐ 传输家族
           ├─ adb-sql-data      DML/DDL 生成
           └─ adb-sql-schema    结构差异
驱动（facade + 7）⭐
  adb-drivers ─┬─ adb-driver-postgres    ⭐ variant.rs 补丁层
               ├─ adb-driver-mysql       ⭐ memo 表模拟 OID
               ├─ adb-driver-sqlite
               ├─ adb-driver-redis
               ├─ adb-driver-mongodb
               ├─ adb-driver-clickhouse
               ├─ adb-driver-jdbc       达梦/金仓/长尾
               └─ adb-driver-support    共享工具
格式
  adb-formats              CSV/XLSX/JSON/SQL/压缩
差异化引擎
  adb-mask                 脱敏
  adb-diag                 诊断
  adb-change               变更管控
  adb-audit                审计
入口
  adb-registry             Command Registry + codegen
  adb-mcp                  ⭐ 强制依赖 adb-secret
  adb-cli
  adb-web
```

### 为什么 facade 模式（抄 DBX）

| 问题 | facade 模式的解法 |
|---|---|
| 加一个驱动要改哪些文件？ | **只加一个 crate**，facade 加一行 `pub use` |
| 编译时间太长？ | 每个驱动独立编译单元，可单独 feature gate |
| 调用方依赖混乱？ | 上层只依赖 facade，看不到内部有几种驱动 |
| 只想要 PostgreSQL？ | `default-features=false, features=["driver-postgres"]` |

> **crate 多不是问题，没有边界纪律才是。**
> DBX 用 27 个跑得好好的，因为它有 `ARCHITECTURE.md` + CI 守卫。

## 3.2 依赖方向（CI 强制）

```text
adb-types
  ↑ ↑ ↑ ↑ ↑ ↑ ↑
  │ │ │ │ │ │ └── adb-formats
  │ │ │ │ │ └──── adb-diag
  │ │ │ │ └────── adb-mask
  │ │ │ └──────── adb-change
  │ │ └────────── adb-secret
  │ └──────────── adb-platform
  ├────────────── adb-sql
  └────────────── adb-drivers (+ 7 个 per-driver)
                    ↑
        ┌───────────┼───────────┬────────────┐
   adb-core              adb-registry           adb-audit
        ↑                   ↑                     ↑
        └───────────────────┴─────────────────────┘
                            │
        adb-mcp   adb-cli   adb-web   adb-desktop
```

**三条硬规则**（`xtask/architecture.rs` 强制，含 dev-dependency）：

1. **下层不得依赖上层**
2. **`adb-secret` 只被上层依赖，且自身除 `adb-types` 外不依赖任何内部 crate**
3. **同一 DTO 不复制定义** —— 旧路径通过 `pub use` 保持相同类型身份

---

# 4. 驱动层：facade + 补丁层

## 4.1 两条通道（不做 Go 层）

```text
通道 A: native Rust（编译进主二进制）
  PostgreSQL / MySQL / SQLite / DuckDB / Redis / MongoDB / ClickHouse
  → 8 个。性能与体积最优。

通道 B: Java JDBC Agent（子进程）
  达梦 / 人大金仓 / OceanBase / 海量 / 虚谷 / 神通 / 高斯 / 中兴 …
  → ⭐ 零代码接入：写 1 个 YAML
```

> **⭐ 核心决策：不做 Go Agent 层。**
> DBX 写了 53 个 Go Agent，但 Oracle/DB2/Hive/Trino/Snowflake/达梦/金仓
> 全都有官方 JDBC 驱动。**JDBC 一条通道覆盖 Go 层 2/3 的库，代码量接近 0。**

## 4.2 ⭐ 国产库补丁层（改进 DBX 的 fork）

### DBX 的做法

```toml
tokio-postgres   = { git = "https://github.com/t8y2/tokio-postgres-gaussdb.git", rev = "ac8ad47" }
postgres-types  = { git = "同上" }
postgres-protocol = { git = "同上" }
mysql_async     = { git = "https://github.com/zipg/mysql_async.git", rev = "4a9e60a" }
```
**代价**：3 个 crate 绑 commit，上游更新要手动 rebase。

### 我们的做法：`variant.rs`

```rust
pub enum PgVariant { Standard, GaussDB, KingbaseES, HighGo }

impl PgVariant {
    /// ⭐ 只有元数据查询和方言需要变体，驱动协议不动
    pub fn meta_sql(&self) -> &'static MetaQueries;
    pub fn quote_ident(&self, s: &str) -> String;
    pub fn transactional_ddl(&self) -> bool;
    pub fn add_column_rewrites(&self, version: i32, has_default: bool) -> bool;
}
```

**收益**：0 个 fork；升级上游只改版本号；驱动协议永远跟随上游修复。

**什么时候才真需要 fork？**
- 需要改 wire protocol 层（私有扩展）
- 需要改类型映射核心（`postgres-types` 的 `FromSql`）
- 目前我们都没遇到。国产库差异 99% 在元数据 SQL 和 DDL 语法。

### ⭐ `meta_sql()` —— 集中所有元数据查询

```rust
pub struct MetaQueries {
    // ⭐⭐ 从不比较名字字符串，只用 OID
    list_tables_by_namespace_oid:      &'static str,
    columns_by_relation_oid:          &'static str,
    indexes_by_relation_oid:          &'static str,
    foreign_keys_by_relation_oid:     &'static str,
    row_estimate:                     &'static str,
    comments:                         &'static str,  // pg_description join
    cancel_statement:                 &'static str,
}
```

**⭐ 契约测试硬门禁**

```rust
#[test]
fn every_variant_queries_by_oid() {
    for id in ["postgresql", "gaussdb", "kingbase", "highgo"] {
        let b = PgBackend::new(id, 16);
        for sql in [b.list_tables_sql(), b.columns_sql(),
                    b.indexes_sql(), b.fks_sql(), b.comments_sql()] {
            assert!(!sql.contains("relname =") && !sql.contains("tablename ="),
                    "{id}: 元数据 SQL 出现按名字比较 —— 必须改成 OID 查询");
            assert!(sql.contains("$1"), "{id}: 缺少参数占位符");
        }
    }
}
```

> 这直接根治 DBX 用户在 `#12240` 提出的问题：
> "每遇到一个就打一个补丁，这是同一类问题的不同实例。"

### MySQL 的 memo 表（模拟 OID）

```sql
-- PG 有 pg_class.oid，MySQL 没有 → 自建映射表
CREATE TABLE _adb_object_id (
  connection_id TEXT NOT NULL,
  object_hash   TEXT NOT NULL,   -- hash(schema + name)
  oid           INTEGER NOT NULL,
  PRIMARY KEY (connection_id, object_hash)
);
```
**名字只解析一次，之后全程用 oid。**

## 4.3 `ui_warnings()` —— UI 提示声明化

```rust
pub fn ui_warnings(t: ConnectionType) -> Vec<UiWarning> {
    let d = descriptor(t);
    if !d.traits.transactional_ddl {
        push("ddl-implicit-commit",
             format!("{} 的 DDL 会隐式提交，无法通过事务回滚。", d.label),
             WarnSite::StructureEditor);
    }
    if d.traits.identity_kind != IdentityKind::AutoIncrement { /* → Transfer 站 */ }
    if !d.traits.sequence_objects { /* → Sidebar 站 */ }
}
```

---

# 5. codegen 五道保险

> 抄 DBX 完整闭环。我 v3 只说了"codegen"，漏了后面几道。

```
保险 1  build.rs 的 cargo::rerun-if-changed（目录级 + 文件级）
         └─ 新增/删除 YAML 也触发重建

保险 2  Vite plugin 的 watch（含 add/unlink）
         └─ 前端 dev 时改 YAML 自动重生成

保险 3  package.json 钩子
         "pretypecheck": "pnpm generate:types"
         "pretest":      "pnpm generate:types"

保险 4  生成物提交进 git
         └─ 不依赖任何人的本地状态

保险 5  ⭐ pnpm check:types（CI 只读校验）
         └─ 产物与 YAML 不一致就 fail
```

## 5.1 build.rs 的 11 条断言

```rust
① schemaVersion == 1
② id 唯一
③ rustVariant 唯一
④ order 唯一
⑤ dialect 必须存在于 declarations/dialects/
⑥ transferObjectFamily 必须存在于 declarations/transfer-families/
⑦ ⭐ 至少一个 capability（或 specializedSurface）
⑧ ⭐ runtimeMode=jdbc 必须有 driverClass
⑨ ⭐ 驱动 checksum 不能是占位符（REPLACE_ME/TODO）
⑩ traits 自洽（identityKind vs sequenceObjects；schemaAware vs singleDatabase；
              explainFormats 非空）
⑪ ⭐ mcpMode=bridge 要求 runtimeMode=jdbc
```

**在编译期 fail，不在运行时 fail。**

## 5.2 前后端校验一致

`generate-types.ts` 的校验逻辑与 `build.rs` **逐条对应**。
两边都跑 → 前端和后端对同一份声明的理解不会分叉。

## 5.3 产物

| 产物 | 消费者 |
|---|---|
| `$OUT_DIR/connection_type.rs` | Rust enum + `ALL` + `as_str` + `transfer_family` |
| `$OUT_DIR/transfer_family.rs` | Rust enum + `members()` |
| `$OUT_DIR/connection_manifest.json` | 嵌入二进制，前端可读 |
| `$OUT_DIR/transfer_families.json` | 传输引擎 |
| `⭐ $OUT_DIR/build_report.txt` | **编译日志里直接打印能力矩阵** |
| `src/types/generated/connections.ts` | 前端 `can()` / `warnsImplicitDdlCommit()` |
| `src/types/generated/transferFamilies.ts` | 前端 `familyOf()` / `mapType()` |

---

# 6. 构建与体积预算

## 6.1 ⭐ Cargo profile（抄 DBX）

```toml
# ⭐ 依赖库不需要断点调试，但保留行号表让 backtrace 可读
#    大幅缩短开发和测试构建时间 + 缩小产物
[profile.dev.package."*"]
debug = "line-tables-only"

[profile.release]
opt-level = "s"          # ⭐⭐ 体积优先，不是性能优先
lto = true
codegen-units = 1
panic = "abort"
strip = "symbols"
```

> **`opt-level = "s"` 而非常见的 `3`** —— DBX 明确选了体积。
> 与"25MB"的传播点完全自洽：**数据库工具的性能靠驱动和数据库，不靠本地编译优化。**

## 6.2 ⭐ Workspace lints（把纪律编译进 CI）

```toml
[workspace.lints.rust]
unsafe_code = "forbid"                  # 数据库工具禁止 unsafe
missing_debug_implementations = "warn"

[workspace.lints.clippy]
indexing_slicing = "deny"     # 禁止裸 [i]，强制 .get()
unwrap_used = "deny"           # ⭐ 库代码不允许 unwrap
todo = "deny"
dbg_macro = "deny"
```

## 6.3 ⭐ Vite manualChunks + chunk 预算

```ts
manualChunks(id) {
  const p = id.replaceAll("\\", "/");
  if (p.includes("/node_modules/ag-grid"))     return "ag-grid";
  if (p.includes("/node_modules/codemirror/")) return "codemirror";
  if (p.includes("/node_modules/@codemirror/"))return "codemirror";
  if (p.includes("/node_modules/echarts/"))    return echartsChunk(p);  // ⭐ 再拆 4 块
  if (p.includes("/node_modules/reka-ui/"))    return "ui";
  if (p.includes("/node_modules/marked/"))     return "marked";
  return undefined;
}
```

**为什么这样切**：CodeMirror 只在打开编辑器时需要；reka-ui 是弹层基础库；
echarts 只在点图表时需要；marked 只在 AI 输出时需要。**全部不进首屏。**

**echarts 再拆 4 块**（抄 DBX `chunkNameForEchartsModule`）：
```ts
charts.js / lib/chart/       → "echarts-charts"
components.js / lib/component/ → "echarts-components"
renderers.js / lib/renderer/   → "echarts-renderers"
其余                            → "echarts-core"
```
只用折线图的用户不需要下载 sankey/map/graph。

## 6.4 体积门禁

```text
M1 结束时：安装包 ≤ 30MB
M5 结束时：安装包 ≤ 40MB（增加 5 个原生驱动）
Rust 二进制 ≤ 18MB
JDBC Agent jar ≤ 2MB

每阶段 CI：ls -la target/release/bundle/
```

---

# 7. AI 怎么做好用

## 7.1 五个失败模式与对策

| 失败模式 | 现象 | 对策 |
|---|---|---|
| 上下文不足 | AI 猜错表名列名 | `adb_context`：180 万 token → 5,800 |
| 语义缺失 | `status=1` 到底是待审还是已付 | `.adbctx` + 查询历史自动学习 |
| 越权执行 | AI 直接跑了 DELETE | 提案式变更：AI 只能提案 |
| 不透明 | 用户不知道为什么 AI 这么写 | 来源标注 + 可信度分级 |
| 成本失控 | AI 跑了个全表扫描 | 影响预估显示在 SQL 旁 |

## 7.2 上下文装配

```rust
pub fn build(&self, intent: &Intent, user_text: &str) -> AssembledContext {
    let mut b = TokenBudget::new(6000);
    // P0 必含（不可裁剪）：用户显式指定的表 + 当前编辑的表
    // P1 相关表（从中文注释/拼音/别名提取实体）
    // P2 跨表知识（JOIN 路径 / 坑位 / 枚举）—— AI 最容易错的地方
    // P3 查询历史（自动学到的模式）
    // P4 剩余预算塞 schema 摘要
}
```

| 压缩手段 | 效果 |
|---|---|
| 紧凑格式（字段名缩写，非 JSON） | −40% token |
| 只发被提到的表 | −90% |
| 宽表折叠（>200 列只发 PK/FK/索引相关列） | −70% |
| 枚举值优先于完整类型定义 | AI 需要的是"1=待审"不是 `smallint` |
| 差分（`--since <hash>`） | 多轮对话 −80% |

## 7.3 ⭐ 查询历史学习（零成本获得知识）

```text
用户还没写任何 .adbctx，AI 已经知道：

users:
  · email 出现在 WHERE 的频率 23%，但表上无 email 索引 → 建议建索引
  · status + created_at 最常一起用 → 建议复合索引
orders:
  · 43% 查询带 status 过滤 → status 是核心筛选维度
```

## 7.4 三条铁律

| # | 规则 | 为什么 |
|---|---|---|
| 1 | **永不自动执行**，必须显式点"执行" | 一次误操作，信任归零 |
| 2 | **每条推断标注来源**（✓数据库注释 / ⚠AI推断） | 用户知道哪些能信 |
| 3 | **显示影响预估**（⏱ 0.04s · 命中索引） | 用户判断"值不值得跑" |

## 7.5 可测的验收标准

| 指标 | 目标 |
|---|---|
| 表名命中率（首次生成） | > 95% |
| 列名命中率 | > 90% |
| 枚举语义正确率（有 `.adbctx`） | > 98% |
| ⭐ **只读场景自动执行写操作** | **0（红线）** |
| 采纳率 | > 60% |
| P95 上下文装配（本地） | < 300ms |

---

# 8. 精简守则

| # | 规则 | 省下什么 |
|---|---|---|
| **R1** | 单一命令注册表，5 种形态全由它生成 | DBX `commands/` 80 个文件 |
| **R2** | 声明式连接：加库 = 1 YAML，0 行代码 | 连接表单/端口/认证/能力全声明 |
| **R3** | 只做 8 种库（JDBC 通道覆盖长尾） | 每多一种原生驱动 = +1500 行 + 3 平台 CI |
| **R4** | 不自研基础设施 | DBX 自研 Canvas 表格 = **123 个文件** |
| **R5** | 前端逻辑全在 `lib/` 纯函数 | 570 个测试文件的底气 |
| **R6** | Store ≤ 15 | 派生状态别存 store |
| **R7** | 依赖预算（Rust ≤45 / 前端 ≤30 / chunk ≤800KB） | — |
| **R8** | 行数预算按模块 | M1 3 万行（DBX 60 万的 1/20） |
| **R9** | 要么全流程一致，要么隐藏 | ⭐ **靠 YAML 声明落地，不是靠测试** |
| **R10** | 每个数据库必须有契约测试 | 不过 → 标"实验性"并隐藏 |
| **R11** | 不做多进程架构（除 JDBC Agent） | DBX 的 `.dbxp` 沙箱架构需 3 万人·天 |
| **R12** | 单仓 + 严格分层 | — |

## 8.1 ⭐ R9 的真正落地：声明而非测试

```yaml
# 达梦
traits:
  identityKind: identity     # ⭐ 不是 autoIncrement
  sequenceObjects: false     # ⭐ UI 隐藏序列工作台
  transactionalDdl: false    # ⭐ UI 提示"DDL 会自动提交"
capabilities:
  databaseCreate: false      # ⭐ 明确关闭
  userAdmin: false           # ⭐ 明确关闭
```

**改 YAML 会产生 diff，review 时能看到。改代码里的 `if` 不会。**

## 8.2 行数预算（按阶段）

| 模块 | U1 | U2 | D | F1 | F2 | F3 | F4 |
|---|---|---|---|---|---|---|---|
| `adb-types`+`platform`+`secret` | 2,600 | 2,800 | 3,200 | 3,500 | 3,700 | 4,000 | 4,200 |
| `adb-sql`（4 crate） | 2,500 | 3,500 | 5,000 | 6,500 | 7,500 | 8,500 | 9,500 |
| `adb-drivers`（8 crate） | 2,000 | 5,500 | 8,000 | 9,000 | 10,000 | 11,000 | 12,000 |
| `adb-core` | 4,000 | 5,500 | 7,500 | 9,000 | 10,500 | 12,000 | 14,000 |
| `adb-registry` | 1,200 | 1,500 | 1,800 | 2,200 | 2,500 | 2,800 | 3,000 |
| `adb-mask`/`diag`/`change`/`audit` | 600 | 1,100 | 2,300 | 3,000 | 7,000 | 10,000 | 12,000 |
| 其他 | 4,000 | 5,500 | 7,000 | 8,000 | 9,500 | 11,000 | 13,000 |
| **Rust 小计** | **16,900** | **25,400** | **34,800** | **41,200** | **51,700** | **59,300** | **67,700** |
| **前端** | **12,000** | 14,000 | 14,500 | 16,000 | 18,000 | 19,000 | 20,000 |
| **Java** | 0 | 0 | 1,500 | 1,500 | 1,500 | 1,500 | 1,500 |
| **合计** | **28,900** | **39,400** | **50,800** | **58,700** | **71,200** | **79,800** | **89,200** |

---

# 9. Navicat 级体验的 12 个原语

> Navicat 的价值在细节。逐条抄。

| # | 原语 | Navicat | 我们 |
|---|---|---|---|
| E1 | **Ctrl+Click 符号跳转** | ✅ | CodeMirror 自定义扩展 → 查 OID → 打开 |
| E2 | **SQL 错误精确定位 + 相似表名建议** | ✅ | `SqlPosition` 必填 + 相似表名推荐 |
| E3 | **表配置文件**（多套筛选/排序/列显隐一键切） | ✅ | `TableProfile` 存 SQLite |
| E4 | **三视图**（Grid/Form/JSON） | ✅ | Form 视图 = 字段-值表单 |
| E5 | **批量编辑不自动保存** | ✅（新） | `EditBuffer` + 顶部黄条 |
| E6 | **固定查询结果** | ✅ | `PinnedResult` |
| E7 | **Record Navigation 侧栏** | ✅（新） | 行号侧栏 + 虚拟滚动 |
| E8 | **外键数据选择器** | ✅ | `SELECT DISTINCT` → 下拉 + 搜索 |
| E9 | **专注模式** | ✅（新） | `⌘⇧F` 隐藏侧栏/工具栏/状态栏 |
| E10 | **连接颜色 + 虚拟组** | ✅ | 同 + 环境色 |
| E11 | **单元格多类型查看器** | ✅ | 文本/Hex/图片/URL/JSON |
| E12 | **对象 URI 分享** | ✅ | `agenticdb://open?conn=...&where=...` |

## 9.1 性能目标（体验的另一半）

| 场景 | 目标 |
|---|---|
| 冷启动到能点开一张表 | ≤ 3s |
| 1 万对象侧栏滚动 | 60fps |
| 50 万行表格滚动 | 60fps |
| 搜索候选 | < 100ms |
| 单元格编辑反馈 | < 16ms |
| 关闭重开（布局/标签/列宽） | 全部还原 |

## 9.2 反馈循环

```
1. 可用性测试：5 个真实开发者 + 5 个任务 + 录屏
2. 竞品对照：同一人用 Navicat 做同样的事，对比点击数
3. Issue 响应纪律：首次 < 24h，中位关闭 < 14 天
4. 每版"三件事"原则：只优化 3 个体验点，做到极致
```

---

# 10. 刻意不做的事

## 10.1 ⛔ 三个架构层面的"不做"

```text
1. 不 vendor 任何 Tauri/wry 官方库
   DBX vendor 了 8 个（wry/tiberius/rumqttc/ctor/dirs-sys/pageant/
   webview2-com-sys/tauri-plugin-updater），主因是 Win7 兼容性。
   我们的目标用户不需要 Win7。

   ⚠ wry 那个 patch 甚至改了 300+ 行（黑屏恢复 + 剪贴板 + 进程恢复）。

2. 不 fork tokio-postgres / mysql_async
   DBX fork 了 3 个 PG crate 锁 commit 支持高斯。
   我们用 variant.rs 的补丁层 → 0 个 fork。

3. 不为国产库改上游 crate
   真要改 wire protocol，先在 src/patch/ 用 trait 包装；
   不行才 fork，且必须写 ADR。
   xtask/guard.rs 检查 [patch.crates-io] 非空时是否有对应 ADR。
```

## 10.2 ⛔ 功能层面的"不做"

| ❌ | 原因 |
|---|---|
| 支持 100 种数据库 | 8 种做到极致 > 80 种做到半残 |
| 自研 Grid / SQL 解析器 / MCP 协议 | AG Grid / `sqlparser-rs` / `rmcp` |
| BI 仪表板 | BI 工具的活（Metabase/Superset） |
| PL/SQL 调试器 | 做一半不如不做 |
| Navicat Cloud 协作 | `.adbctx` 进 git + Docker 共享已解决 80% |
| Navicat Monitor | 服务器监控不是数据库客户端的职责 |
| 移动端 | 工作场景是桌面 |
| 社区版功能阉割 | 个人用户永远免费且好用 |

---

# 11. 阶段总览与门禁

```text
U0  设计系统 + 声明层 + 组件库   W1-2    ┐
U1  全部页面 UI（Mock 驱动）      W3-6    ├ ⭐ 6 周后是完整可点击的产品外壳
U2  UI 打磨 + Navicat 盲测        W7-8    ┘ ⛔ 盲测不过不进 D
──────────────────────────────────────────────────────
D   MySQL + PostgreSQL           W9-16   ⭐ 假数据换真数据，UI 零改动
──────────────────────────────────────────────────────
F1  编辑 / 结构 / 导入导出        W17-22
F2  诊断 + 知识层                W23-28
F3  变更管控 + 数据工厂           W29-36
F4  AI + MCP + 分发              W37-42
```

| 阶段 | 周期 | 团队 | 成本 | 产出 | 门禁 |
|---|---|---|---|---|---|
| U0 | 2 周 | 3 | 15万 | 设计系统 + 声明层 | 组件库可浏览 + YAML 校验过 |
| U1 | 4 周 | 3 | 30万 | 20 页面可点击 | Mock 演示完整流程 |
| U2 | 2 周 | 3 | 15万 | v1.0-rc | ⭐ **盲测胜过 Navicat** |
| D | 8 周 | 4 | 96万 | v1.0 多库 | PG/MySQL 体验一致 |
| F1 | 6 周 | 4 | 72万 | v1.2 | 编辑/结构完整 |
| F2 | 6 周 | 4 | 72万 | v1.4 | 诊断发现率 |
| F3 | 8 周 | 5 | 120万 | v1.6 | 有人付费 |
| F4 | 6 周 | 5 | 90万 | v2.0 | AI 采纳率 |
| **合计** | **42 周** | | **510 万** | | |

## 11.1 三条硬门禁

```text
U0 门禁：
  ⛔ 改 YAML 不跑生成 → CI fail
  ⛔ 写坏 YAML → 编译 fail
  ⛔ chunk 全部在预算内

U2 门禁（项目最关键）：
  ⛔ 5 人盲测体验 < Navicat → 不进 D 阶段，最多返工 4 周

D 门禁：
  ⛔ 前端业务代码变更 < 5%（零 UI 改动）
  ⛔ 变体契约测试 4 个 PG 变体全绿
  ⛔ 传输家族映射测试全绿
```

## 11.2 U2 盲测设计（最关键）

```text
5 个真实开发者（从没听过我们），30 分钟 5 个任务，全程录屏：

T1 找到"订单"表，双击打开，看第一页数据
T2 筛选出本月"已支付"的订单，按金额倒序
T3 写 3 表 JOIN，Ctrl+Click 表名跳转
T4 故意写错 SQL，看能否定位到具体位置
T5 改 3 行数据，review 生成的 SQL，然后撤销

对照：同一个人用 Navicat 做同样 5 个任务
外加 `UI-SPEC.md` §8.6 的 12 步 Navicat 对照

判定：
  完成率      ≥ 80%
  总耗时      ≤ Navicat
  ⭐ 主动说"比 Navicat 好"的功能  ≥ 1 个
```

---

# 12. 度量体系

## 12.1 北极星指标

> **周活跃开发者数**（一周内完成 ≥1 次"连接→浏览→查询"的人）

## 12.2 里程碑指标

| 指标 | U0 | U1 | U2 | D | F1 | F2 | F3 | F4 |
|---|---|---|---|---|---|---|---|---|
| 首屏到可浏览 | ≤ 5s | ≤ 3s | ≤ 3s | ≤ 3s | ≤ 3s | ≤ 3s | ≤ 3s | ≤ 3s |
| 10 万行滚动 | 60fps | 60fps | 60fps | 60fps | 60fps | 60fps | 60fps | 60fps |
| 盲测体验优于 Navicat | — | — | **✅硬门禁** | — | — | — | — | — |
| GitHub star | — | 300 | 1.5k | 4k | 6k | 9k | 14k | 20k |
| 周活开发者 | 20 | 200 | 1k | 3k | 5k | 8k | 12k | 18k |
| 7 日留存 | — | 30% | 40% | 45% | 48% | 50% | 52% | 55% |
| 崩溃率 | <3% | <1% | <0.5% | <0.5% | <0.4% | <0.3% | <0.2% | <0.2% |
| Issue 首次响应 | <72h | <24h | <24h | <12h | <12h | <8h | <8h | <4h |
| Issue 中位关闭 | — | <30d | <21d | <14d | <14d | <10d | <7d | <7d |
| 安装包体积 | <30MB | <30MB | <30MB | <35MB | <35MB | <38MB | <38MB | <40MB |

## 12.3 反向指标（健康度）

| 指标 | 阈值 | 含义 |
|---|---|---|
| 只用"连接+查询"不用其他 | > 70% | 差异化没价值 |
| 盲测中被卡住 > 30s | > 2 次 | 体验有问题 |
| 诊断角标点击率 | < 5% | 诊断没被发现 |
| 演示库打开率 | < 50% | 首次体验引导有问题 |

---

# 13. 风险与止损

| 风险 | 概率 | 影响 | 止损线 | 应对 |
|---|---|---|---|---|
| **U2 体验打不过 Navicat** | 中 | **致命** | **Gate 不过不进 D** | 录屏回看每个卡点 → 逐条改 → 再测。最多返工 4 周 |
| **Mock 抽象撑不住 PG/MySQL** | 中 | 高 | D 第 1 周发现就改 trait | MockDriver 实现真行为（真分页/真取消/真错误） |
| **能力声明被绕过**（有人硬编码 `if (conn==="mysql")`） | 中 | 中 | lint + review | R9 靠 YAML diff；代码 review 检查组件不读原始字符串 |
| 依赖库升级地狱 | 低 | 中 | 不 vendor + 锁版本 | `[patch.crates-io]` 保持为空 |
| 生产库事故 | 低 | **致命** | **任何 1 起 = 冻结生产功能** | 危险四级 + 强制快照 + 审计不可关 + 超时绝不重放 |
| 社区起不来 | 中 | 高 | D 结束周活 < 500 | U2 结束就运营（不等产品完整）+ AI issue 自动分级 |
| 范围蔓延 | **高** | 中高 | 每周 review TODO | R9 全流程一致；不做的写进"明确不做"清单 |

---

## 附录 A：脚手架索引

| # | 文件 |
|---|---|
| A | `scaffold/declarations/schema/connection.schema.json` |
| A | `scaffold/declarations/connections/{postgresql,mysql,dameng}.yaml` |
| A | `scaffold/crates/adb-types/build.rs` |
| B | `scaffold/apps/desktop/viteAdbTypesPlugin.ts` |
| B | `scaffold/apps/desktop/scripts/generate-types.ts` |
| B | `scaffold/apps/desktop/{package.json,vite.config.ts}` |
| C | `scaffold/declarations/transfer-families/{oracle,postgres,mysql,sqlserver}.yaml` |
| D | `scaffold/Cargo.toml` |
| D | `scaffold/crates/adb-drivers/{Cargo.toml,src/lib.rs}` |
| D | `scaffold/crates/adb-driver-postgres/{Cargo.toml,src/variant.rs}` |

---

*实施方案 v4.0 完*
