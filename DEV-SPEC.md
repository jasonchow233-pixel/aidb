# 开发文档 (Engineering Spec) — AgenticDB

> 版本 v1.0 ｜ 2026-10-16
> 依据：DBX 源码架构 + 356 条 issue 实测挖掘 + 竞品横评
> 配套文档：`PRODUCT.md`（产品文档）、`UI-DESIGN.md`（UI 设计文档）

---

## 目录

- [0. 从 DBX 的 1232 个 Issue 中学到的 12 条教训](#0-从-dbx-的-1232-个-issue-中学到的-12-条教训)
- [1. 架构总览](#1-架构总览)
- [2. 分层与依赖规则](#2-分层与依赖规则)
- [3. 核心抽象](#3-核心抽象)
- [4. 驱动层设计](#4-驱动层设计)
- [5. 元数据与语义层](#5-元数据与语义层)
- [6. 变更管控引擎](#6-变更管控引擎)
- [7. 脱敏引擎](#7-脱敏引擎)
- [8. 性能诊断引擎](#8-性能诊断引擎)
- [9. Agent 与 MCP 层](#9-agent-与-mcp-层)
- [10. 安全与审计](#10-安全与审计)
- [11. 前端架构](#11-前端架构)
- [12. 存储与配置](#12-存储与配置)
- [13. 错误契约](#13-错误契约)
- [14. 可观测性](#14-可观测性)
- [15. 测试策略](#15-测试策略)
- [16. CI/CD 与发布](#16-cicd-与发布)
- [17. 架构决策记录 (ADR)](#17-架构决策记录-adr)
- [18. 编码规范](#18-编码规范)
- [19. 已知风险与技术债预案](#19-已知风险与技术债预案)

---

## 0. 从 DBX 的 1232 个 Issue 中学到的 12 条教训

这部分是本文档最重要的部分。**每一条都是 DBX 实际付出代价换来的**，我们必须在设计阶段就避开。

### 数据依据

| 指标 | 数值 |
|---|---|
| 采样 issue 数 | 356（100% 开放 + 最近 1000 条全量） |
| 功能请求 / Bug / 兼容性 | 115 / 203 / 17 |
| 标记 P1 的 issue | 85 |
| Bug 根因 Top 4 | 数据传输迁移 36 · 性能卡顿 30 · MCP/AI 17 · 数据正确性 13 |
| 单条最高讨论 | #10308 达梦性能回归（11 条评论）· #12154 MCP ToolAnnotations（10 条）· #10436 数据传输卡死（8 条） |

### 教训清单

#### L1 — 凭据绝不能跨进程分叉存储 🔴 最高优先级

**证据**：`#10159` `#10248` `#10195` `#12030` `#12004` —— **5 条独立 issue，全是 MCP 相关凭据故障**：

- `#10159` MCP 调用报 `Access denied for user 'root' (using password: NO)` —— 桌面端正常，只有 MCP 失败
- `#10195` macOS MCP binary 无法读 Keychain，迁移成功却一直报 `DATA_MIGRATION_REQUIRED`
- `#12004` Windows 上 MCP 进程锁住 JDBC jar，导致更新失败

**根因**：DBX 把 MCP Server 做成**独立 npm 二进制**（`@dbx-app/mcp-server`），它有**自己的一份加解密实现**，且编译时漏掉了 `os-keyring` feature。结果是**同一个密钥、两套代码、两条存储路径**。

> **我们的设计约束**：凭据加密与解密**只有一个实现**，放在 `crates/secret-store`。所有形态（Desktop / Web / CLI / MCP / Docker）**必须链接同一个 crate**。MCP 不允许独立编译分发，只能作为 crate 被引用。
> 落地检查：`cargo tree -p dbx-mcp | grep secret-store` 必须在 CI 里断言。

---

#### L2 — MCP 工具必须声明 `ToolAnnotations`

**证据**：`#12154`（10 条评论，全项目讨论最热的功能类 issue）

> `dbx-mcp` 暴露的 **25 个工具全部没有 `annotations` 字段**。Claude Code 判断是否只读只看 `annotations.readOnlyHint`，导致只读查询也要用户点确认。

**根因**：权限等级定义在业务代码里，没进协议描述。工具描述是手写的字符串。

> **我们的设计约束**：`annotations` **不能手写**，必须由 `Danger` 枚举自动生成（见 §3.1）。在 CI 里加一个测试：启动 MCP server，遍历所有工具，断言 `readOnlyHint == (danger == ReadOnly)`。

---

#### L3 — 标识符必须用 OID，不要用字符串

**证据**：`#12240`（用户引用 DBeaver 源码提出，`#12198` `#10233` 是它的衍生 bug）

> 单表元数据用 `(schema, table)` **字符串**去 catalog 找表，面对各种坑：空 schema 传参、大小写折叠差异、引号标识符拼写。**每遇到一个就打一个补丁，这是同一类问题的不同实例。**

> **我们的设计约束**：`SchemaSnapshot` 里所有对象必须有 `ObjectId`：
> - PostgreSQL 家族 → `oid`（`pg_class.oid` / `pg_namespace.oid`）
> - MySQL → `(schema, name, engine_version)` 复合标识 + 内部 memo 表（SQLite）
> - JDBC → `DatabaseMetaData.getXXXs()` 返回的原生标识
>
> **所有元数据 SQL 拿 ID 当参数，从不比较名字字符串。** 空 schema、大小写、引号名这些语义问题从源头消失。字符串只在最后"显示给人看"时才用。

---

#### L4 — 数据传输必须可中断、可续传、有进度，且必须 round-trip

**证据**：`#10436`（8 条评论）`#10589` `#10027` `#10556` `#9667` `#12156` `#9729` `#10484` `#11533` `#11953`

典型故障：
- `#10436` 传输 100 张表 → **页面卡死，取消和关闭按钮都不可用**
- `#10589` 传输几十分钟后 `Connection not found`
- `#9667` Oracle → OceanBase **金额字段精度丢失**，全部没有小数位
- `#9729` 导出的 SQL Server 脚本有**重复外键约束名**，导入失败
- `#10484` 备份**遗漏被外键引用的独立唯一索引**，恢复后 FK 失效
- `#10505` CSV 导出**无法区分 NULL 与空字符串**，回导 `NOT NULL DEFAULT ''` 的列报错

> **我们的设计约束**：
> 1. **分片 + 断点续传**：每个表按 chunk（默认 5000 行）写，持久化 `{transfer_id, table, chunk_index, rows_done}`。重连后续传。
> 2. **类型保真**：跨引擎传输前跑 `TypeMappingPlan`，把 `decimal(20,4)` → 目标引擎支持的类型**显式列出**并让用户确认。精度丢失必须是**用户决定的**，不是静默发生。
> 3. **导出可逆**：CSV 导出默认带 `\\N` 标记 NULL；导出脚本必须包含**被 FK 引用的所有索引**；约束名必须去重。
> 4. **round-trip 测试作为硬门禁**：任何导出格式必须有"导出→导入→结构 diff 为空 + 数据 checksum 一致"的自动化测试。

---

#### L5 — 元数据树必须懒加载

**证据**：`#12299`（P0）

> 每次加载表最多 1000 个且很慢。**对于日志运维人员来说每天面对的是成千上万的表**，一次性最多只能加载 1000，就不能有一个按钮可以看到所有的表。而且**根据表名搜索表太慢了**。

相关：`#11952` iris Cache 表多时过滤图标一直转圈。

> **我们的设计约束**：
> - 元数据树**第一层只加载表名 + 类型 + 行数估算**，不加载列/索引/FK
> - 展开节点时才加载子级；用 `LRU + 磁盘缓存`（SQLite）持久化快照
> - 搜索走**本地索引**（SQLite FTS5 + 拼音首字母，见 §5.4），不是数据库查询
> - **表数量不设上限**，虚拟滚动渲染。提供"显示全部"开关而不是硬截断 1000

---

#### L6 — 大结果集的搜索/排序必须区分执行位置

**证据**：`#12260` `#11940`

> `command+f` 检索不到非本页数据，即使点了"加载全部并转到末行"。

> **我们的设计约束**：UI 必须**明确标注每个操作的执行位置**（见 `UI-DESIGN.md` §3.4）：
> - 内存操作：当前已加载行（必须明示"仅搜索已加载的 N 行"）
> - 数据库操作：`WHERE` / `ORDER BY`（必须明示"将在数据库执行"）
> 加载全部后必须显示真实行号（`#11940` 反馈行号与总数不一致）。

---

#### L7 — 编辑器性能：大型 SQL 与悬浮提示

**证据**：`#12041` `#12143` `#10472` `#12029`

- `#12041` 几百行含 MyBatis 动态标签的 SQL，**选中时 CPU 飙升卡顿数秒**
- `#12143` 悬浮表名弹窗里**输入搜索列时卡顿**
- `#10472` 长 SQL 格式化后**丢失部分内容**

> **我们的设计约束**：
> - CodeMirror 6 语法高亮对大文件降级：>5000 行关闭语言解析器，改用轻量 tokenizer
> - 悬浮提示（hover）**必须 debounce ≥ 150ms**，且结果缓存，键盘导航时不重新查询
> - 格式化器必须**有往返测试**：`format(format(x)) == format(x)` 且 `format(x)` 的 token 序列与 `x` 的 token 序列完全一致（防止丢内容）

---

#### L8 — 连接池必须有空闲回收与健康检查

**证据**：`#12108` `#12112`

> `#12108` 长时间不关连接（一两天）后执行 DDL 卡死，重连后秒过
> `#12112` [Feature] 增加数据库连接空闲超时自动关闭功能

> **我们的设计约束**：
> - 连接空闲 > N 分钟（默认 30，可配）→ 自动关闭，UI 显示"空闲已断开"
> - 每个连接带 `last_used_at` + `health_state`（Healthy / Stale / Dead）
> - 执行前若 `Stale`（超过 `stale_threshold`），先跑一次轻量心跳（`SELECT 1` / `PING`）再执行
> - **心跳失败自动重连一次**，且这次重连必须写审计

---

#### L9 — 凭据读取不能每次都解密钥

**证据**：`#10309`

> 升级后 SQL 库需等待约 1 分钟才显示：**启动路径每次读取密文都重新解析密钥，反复访问系统凭据库**。

> **我们的设计约束**：`SecretStore` 内置**进程内密钥缓存**（moka / lru），密钥只在首次访问时从 Keyring 取一次。批量读取 100 个连接时，一次取密钥、内存解密。

---

#### L10 — 文件锁与进程生命周期要显式管理

**证据**：`#12004` `#12278` `#9538` `#12046`

- `#12278` Windows 上独立 MCP 宿主残留插件进程，回滚后卸载报 `os error 5`
- `#12046` 升级到 0.6.42 后达梦报 `Agent RPC error (-1)`，回退 0.6.39 正常 —— **典型的升级期资源残留**

> **我们的设计约束**：
> - 所有子进程（JDBC Agent 等）登记在 `ProcessRegistry`，记录 pid / 启动时间 / 所属 runtime key
> - 应用启动时**清理孤儿进程**：父进程已死但子进程仍在 → 按启动时间判断后终止
> - 插件安装/卸载/回滚**共用一把文件系统锁**（`fs2::FileExt`），Windows 上必须先终止持有句柄的进程
> - 升级流程：安装新版本 → 迁移 schema → **旧进程优雅退出（最多 10s）→ 超时则 kill** → 才写新文件

---

#### L11 — 代理与网络绕过规则要正交可配

**证据**：`#12277` `#12111` `#10257`

> `#12277` Windows 配置本地模型 `http://127.0.0.1:3425/v1`，开 Clash 时返回 502，关掉正常。**即使 `proxyEnabled=false`，访问本地模型仍会连接系统 HTTP 代理**；且绕过列表中的 `127.*` 在当前依赖里匹配不到 `127.0.0.1`。

> **我们的设计约束**：
> - 代理决策函数**纯函数化**：`should_bypass(url) -> bool`，接受 `(scheme, host, port, bypass_rules, no_proxy_env)`
> - **单元测试覆盖**：IPv4/IPv6 环回、IPv4-mapped IPv6（`::ffff:127.0.0.1`）、域名匹配、`*.example.com` 通配、`NO_PROXY` 环境变量
> - DNS 反向解析超时必须有上限（`#10257` Kafka SASL 因反向 DNS 超时连接失败）

---

#### L12 — Schema diff 的表达式索引不能截断

**证据**：`#9988` `#10103` `#11533` `#11794`

> `#9988` PostgreSQL Schema Compare 生成了**被截断的表达式索引 SQL** 并回滚部署
> `#10103` 架构比较生成 SQL **重复拼接 `CREATE OR REPLACE FUNCTION`** 导致执行失败
> `#11533` SQL Server transfer "create table" **忽略目标 schema**，CREATE TABLE 打在源 schema 上

> **我们的设计约束**：
> - 生成的 DDL/DML 必须经过**自检器**：idempotent 检查（重复执行不应二次报错）、schema 限定检查（生成语句里的表名 schema 是否等于目标 schema）
> - **表达式索引、函数体、CLOB 禁止截断**。若数据库元数据本身返回截断，必须显式报错而非静默生成

---

### 附：Issue 中用户主动提出的高价值架构建议

| Issue | 建议 | 我们的采纳 |
|---|---|---|
| `#12240` | 元数据改用 OID 定位，根除标识符拼写问题 | ✅ **L3，采纳为硬性架构约束** |
| `#11964` | 集成 SQL 审核（参考 cookieY/Yearning），"增加产品竞争力" | ✅ **核心差异化功能，见 §6** |
| `#12177` `#12038` | JumpServer 堡垒机认证 | ✅ 纳入 SSH 隧道模块 |
| `#12033` | 支持用**注释**搜索表和字段 | ✅ 语义层核心能力，见 §5.3 |
| `#12238` | 备份可**跨环境**还原 | ✅ 纳入变更管控 |
| `#12117` | 跨库传输支持视图 | ✅ 纳入传输引擎 |
| `#12198` | GaussDB A 模式列注释不显示 | ✅ 列注释走 OID 路径后自然解决 |
| `#12132` | 跨表/跨连接复制粘贴表结构字段 | ✅ 纳入结构编辑器 |
| `#12301` `#12371` | PG 序列默认值、逻辑复制的增删改查 | ✅ 纳入工作台 |

---

## 1. 架构总览

### 1.1 定位

> **AgenticDB**：面向 AI 编程时代的**数据库上下文层与变更管控层**。
> 不做"又一个数据库客户端"，做 **AI Agent 的数据库记忆 + 生产的守门人**。

### 1.2 五种形态，一套内核

```text
   ┌───────────┐   ┌────────┐   ┌────────┐   ┌───────────┐   ┌──────────┐
   │  Desktop  │   │  Web   │   │  CLI   │   │ MCP Server│   │  Docker  │
   │  (Tauri)  │   │(axum)  │   │(clap)  │   │  (rmcp)   │   │  (axum)  │
   └─────┬─────┘   └───┬────┘   └───┬────┘   └─────┬─────┘   └────┬─────┘
         └─────────────┴──────┬─────┴──────────────┴───────────────┘
                               │
                    ╔══════════▼═══════════╗
                    ║   Command Registry   ║   ← 唯一的命令定义源
                    ║   (自动生成 5 种形态)  ║
                    ╚══════════╤═══════════╝
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
    ┌────▼────┐          ┌─────▼─────┐         ┌─────▼─────┐
    │  Core   │          │  Change   │         │   Audit   │
    │ 编排层  │          │ 变更管控  │         │  审计     │
    └────┬────┘          └─────┬─────┘         └─────┬─────┘
         │                     │                     │
    ═════╪═════════════════════╪═════════════════════╪══════
         │                     │                     │
    ┌────▼─────┐  ┌──────┐  ┌──▼───┐  ┌────────┐  ┌──▼──────┐
    │ Drivers  │  │  SQL │  │Masker│  │Diagnos-│  │  MCP    │
    │ 驱动层   │  │ 解析 │  │脱敏  │  │  诊断  │  │ Agent   │
    └────┬─────┘  └──┬───┘  └──┬───┘  └───┬───┘  └───┬─────┘
         │           │         │          │          │
    ═════╪═══════════╪═════════╪══════════╪══════════╪══════
         │           │         │          │          │
    ┌────▼───────────▼─────────▼──────────▼──────────▼─────┐
    │  declarations/   connections/*.yaml  dialects/*.yaml  │  声明层
    └──────────────────────────────────────────────────────┘
```

### 1.3 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| 桌面壳 | **Tauri 2.10** | 25MB vs Electron 150MB+ |
| 前端 | **Vue 3.5 + Vite 7 + Pinia 3** | 与 Rust 内集成最顺；若团队 React 更熟则换 React + Zustand，二选一 |
| 样式 | Tailwind CSS 4 + reka-ui + CVA | — |
| 编辑器 | **CodeMirror 6** | 比 Monaco 轻 |
| 表格 | **AG Grid Community** | ⭐ **不自研**（DBX 因自研 Canvas 付出了 123 个文件的代价） |
| 核心 | **Rust 1.88+ / Tokio** | 体积与性能 |
| 长尾驱动 | **Java JDBC Agent**（子进程） | ⭐ 10% 工作量覆盖 80% 数据库 |
| Web | axum 0.8 + WebSocket | 与桌面端共用 |
| 存储 | SQLite（`sqlx`） | 单文件、事务、可 FTS5 |
| MCP | 官方 `rmcp` | 不自研 JSON-RPC |
| CLI | clap（derive） | 必须 `--json` |

### 1.4 明确不做的事

| ❌ 不做 | 理由 |
|---|---|
| 100 种数据库 | 正面硬刚 DBX 必输。**支持 8 种做到极致 > 80 种做到半残** |
| 自研 SQL 解析器 | `sqlparser-rs` + `sqlformat` 够用 |
| 自研 Grid 组件 | AG Grid Community |
| 自研 JSON-RPC / MCP | `rmcp` |
| 插件市场（初期） | 等有 10+ 用户、2+ 外部贡献者 |
| 纯 Text-to-SQL 聊天 | 无护城河 |

---

## 2. 分层与依赖规则

### 2.1 Workspace 布局

```text
agenticdb/
├── crates/
│   ├── adb-types/           DTO、枚举、ID 类型          ← 零依赖
│   ├── adb-platform/        进程、路径、代理、Keyring
│   ├── adb-sql/             解析、方言、风险分析、DDL 生成
│   ├── adb-drivers/         驱动 trait + 原生实现 + JDBC 通道
│   ├── adb-formats/         CSV/XLSX/JSON/SQL/压缩
│   ├── adb-secret/          🔒 凭据加解密（唯一实现，见 L1）
│   ├── adb-registry/        Command Registry + 代码生成
│   ├── adb-core/            编排：connection/query/schema/data/ai
│   ├── adb-change/          ★ 变更管控引擎
│   ├── adb-mask/            ★ 脱敏引擎
│   ├── adb-diag/            ★ 性能诊断引擎
│   ├── adb-audit/           ★ 审计
│   ├── adb-mcp/             MCP server（必须链接 adb-secret）
│   ├── adb-cli/             CLI
│   ├── adb-web/             axum web
│   └── adb-plugin/          插件运行时（v3 以后）
├── apps/
│   ├── desktop/             Vue 前端 + Tauri 壳
│   └── agents/jdbc/         ★ 通用 JDBC Agent（Java）
├── packages/
│   ├── cli/                 npm 包 @agenticdb/cli
│   └── mcp-server/          npm 包 @agenticdb/mcp-server
├── declarations/
│   ├── connections/*.yaml   88 → 精选 20
│   ├── dialects/*.yaml
│   └── mappings/*.yaml
└── xtask/                   构建脚本、架构守卫、codegen
```

### 2.2 依赖方向（强制）

```text
adb-types
   ▲  ▲  ▲  ▲
   │  │  │  └── adb-formats
   │  │  └───── adb-mask
   │  └──────── adb-diag
   ├──────────── adb-sql
   ├──────────── adb-secret
   ├──────────── adb-platform
   └──────────── adb-drivers
                    ▲
        ┌───────────┼───────────┬─────────────┐
   adb-core    adb-change    adb-audit   adb-registry
        ▲           ▲            ▲           ▲
        └───────────┴────────────┴───────────┘
                    │
        adb-mcp   adb-cli   adb-web   desktop
```

**三条硬规则**（由 `xtask/architecture.rs` 在 CI 中强制）：

1. **下层 crate 不得反向依赖上层，包括 dev-dependency**
2. **`adb-secret` 只能被上层依赖，且它自身不依赖任何其他内部 crate**（除 `adb-types`）
3. **同一 DTO 不复制定义** —— 旧路径与新 crate 通过 `pub use` 保持相同类型身份

```rust
// xtask/architecture.rs —— CI 守卫
pub fn check_dependency_direction(workspace: &Workspace) -> Result<()> {
    const ALLOWED: &[(&str, &[&str])] = &[
        ("adb-types",    &[]),
        ("adb-secret",   &["adb-types"]),
        ("adb-platform", &["adb-types"]),
        ("adb-sql",      &["adb-types"]),
        ("adb-formats",  &["adb-types"]),
        ("adb-mask",     &["adb-types", "adb-formats", "adb-sql"]),
        // ...
    ];
    // 遍历 cargo metadata，含 dev-deps，违规即 fail
}
```

> **架构的价值在于可执行的约束，不在于图画得好看。** 这一条是从 DBX 的 `scripts/core-architecture.test.mjs` 直接学来的。

---

## 3. 核心抽象

### 3.1 `CommandSpec` — 整个系统的支点

一个抽象同时解决：**5 种形态**、**MCP 权限分层**、**全量审计**、**风险分级**。

```rust
// crates/adb-registry/src/spec.rs
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Danger {
    /// 只读查询、元数据探测。readOnlyHint = true
    ReadOnly,
    /// 写数据：INSERT/UPDATE/DELETE/DML。readOnlyHint = false, destructiveHint = false
    Mutating,
    /// 破坏性或改结构：DROP/TRUNCATE/ALTER/CREATE。两者都 false
    Destructive,
    /// 管理操作：GRANT/KILL/CREATE USER。两者都 false
    Admin,
}

impl Danger {
    /// ⭐ L2: MCP ToolAnnotations 由此生成，绝不手写
    pub fn annotations(&self) -> ToolAnnotations {
        ToolAnnotations {
            read_only_hint:     matches!(self, Danger::ReadOnly),
            destructive_hint:   matches!(self, Danger::Destructive | Danger::Admin),
            idempotent_hint:    matches!(self, Danger::ReadOnly),
            open_world_hint:    true,   // 任意 DB 都在"开放世界"
        }
    }
    /// 是否需要用户确认弹窗
    pub fn requires_confirm(&self) -> bool {
        matches!(self, Danger::Mutating | Danger::Destructive | Danger::Admin)
    }
    /// 是否需要走审批流
    pub fn requires_approval(&self) -> bool {
        matches!(self, Danger::Destructive | Danger::Admin)
    }
}

pub struct CommandSpec {
    pub name: &'static str,          // "query.execute"
    pub danger: Danger,
    pub produces: DataKind,          // Rows | Schema | Diff | Plan | File | Stream | None
    pub idempotent: bool,
    pub audit: bool,                 // 默认 true；Danger::ReadOnly 且无副作用可 false
    pub summary: fn(&ConnCtx) -> String,   // 动态摘要，用于审计与确认框
}

pub trait Command: Send + Sync + 'static {
    type Params:  DeserializeOwned + Validate + Send;
    type Returns: Serialize + Send;
    const SPEC: CommandSpec;
    async fn run(&self, ctx: &Ctx, p: Self::Params) -> Result<Self::Returns, AdbError>;
}
```

**自动生成的产物**（`xtask codegen`）：

```rust
// 生成: tauri_invoke!() 宏
tauri::generate_handler![
    adb_registry::handlers::query_execute,   // 自动带权限检查
    adb_registry::handlers::schema_introspect,
    ...
]
```

```rust
// 生成: MCP tools 列表
pub fn mcp_tools() -> Vec<rmcp::tool::Tool> {
    vec![
        tool("adb_query", QueryCmd::SPEC, |c| c.name==("adb_query")),
        // annotations 自动来自 Danger，L2 由此根治
    ]
}
```

```rust
// 生成: CLI 子命令
#[derive(Parser)]
enum Cli {
    Query { connection: String, #[arg(long)] file: Option<PathBuf>, #[arg(long)] json: bool },
    Schema { connection: String },
    Context { connection: String, #[arg(long)] tables: Vec<String>, #[arg(long, default_value="prompt")] format: OutFmt },
}
```

> **收益量化**：新增一个命令 = 写一个 `impl Command`。5 种形态、权限、审计、确认框全部自动获得。**零重复**。

### 3.2 驱动抽象

```rust
// crates/adb-drivers/src/lib.rs
#[async_trait]
pub trait Driver: Send + Sync {
    fn kind(&self) -> DriverKind;

    /// 能力声明 —— 决定 UI 出现哪些工作台、哪些按钮禁用
    fn capabilities(&self) -> Caps;

    async fn connect(&self, cfg: &ConnConfig, sink: &EventSink) -> Result<Box<dyn Session>>;

    /// 元数据快照，⚠️ 必须以 ObjectId 为键（L3）
    async fn introspect(&self, s: &dyn Session, scope: &Scope, depth: Depth) -> Result<SchemaSnapshot>;
}

#[async_trait]
pub trait Session: Send + Sync {
    fn id(&self) -> SessionId;

    /// ⭐ 统一返回流式。禁止一次性拉全表。
    async fn query(&self, sql: &str, opt: QueryOptions) -> Result<RowStream>;

    /// ⭐ 取消。必须幂等，且取消后不得自动重放（L4 + 错误契约）
    async fn cancel(&self) -> Result<()>;

    async fn begin(&self, mode: TxnMode) -> Result<Txn>;
    async fn commit(&self) -> Result<()>;
    async fn rollback(&self) -> Result<()>;

    async fn heartbeat(&self) -> Result<()>;   // L8
    async fn health(&self) -> Health;
    fn schema(&self) -> Arc<SchemaSnapshot>;
}

pub struct Caps {
    pub transactions: bool,
    pub multiple_resultsets: bool,
    pub explain: Option<ExplainLevel>,
    pub ddl_in_transaction: bool,
    pub online_ddl: Option<OnlineDdlStrategy>,
    pub sequence_objects: bool,
    pub row_identity: RowIdentityKind,   // Pk | UniqueIndex | None
    pub max_params: usize,
}
```

### 3.3 `SchemaSnapshot` — 语义层载体

```rust
// crates/adb-types/src/schema.rs
pub struct SchemaSnapshot {
    pub db_id: DatabaseId,
    pub taken_at: DateTime<Utc>,
    pub objects: Vec<ObjectId>,              // ⭐ 一切以 ID 引用
    pub tables: HashMap<ObjectId, TableDef>,
    pub indexes: HashMap<ObjectId, IndexDef>,
    pub foreign_keys: Vec<ForeignKey>,
    pub routines: HashMap<ObjectId, RoutineDef>,
    pub triggers: Vec<TriggerDef>,
    // ─── 语义增强（DBX 没有的部分）───
    pub knowledge: Option<SchemaKnowledge>,
    pub stats: Option<HashMap<ObjectId, TableStats>>,
    pub quality: Option<QualityReport>,      // §8 诊断
}

pub struct SchemaKnowledge {
    pub business_meaning: Option<String>,
    pub enum_values: BTreeMap<String, EnumDef>,
    pub join_paths: Vec<JoinPath>,
    pub pitfalls: Vec<Pitfall>,
    pub owners: Vec<String>,
    pub source: KnowledgeSource,   // DbComment | Repo | Inferred | QueryHistory
}
```

---

## 4. 驱动层设计

### 4.1 通道选型（最终方案）

```text
┌─ 通道 A: 原生 Rust（编译进主二进制）────────────────────┐
│  PostgreSQL（含 Redshift/PolarDB/GaussDB A兼容/opengauss）│
│  MySQL（含 MariaDB/Doris/StarRocks/TiDB via 协议兼容）   │
│  SQLite / DuckDB                                        │
│  Redis                                                  │
│  MongoDB                                                │
│  → 8 个。性能与体积最优。                                │
└──────────────────────────────────────────────────────────┘

┌─ 通道 B: Java JDBC Agent（子进程，覆盖长尾）───────────┐
│  达梦 / 人大金仓 / OceanBase / 海量 / 虚谷 / 神通 /       │
│  高斯 / 中兴 / 南大通用 / 星瀚 / TiDB / GBase /          │
│  以及任何用户自带的 JDBC 驱动                            │
│  → 零代码接入：写 1 个 YAML                              │
└──────────────────────────────────────────────────────────┘
```

> **⭐ 核心决策**：**不做 Go Agent 层。**
> DBX 写了 53 个 Go Agent，但其中 Oracle/DB2/Hive/Trino/Spark/Databricks/Snowflake… 全都有官方 JDBC 驱动。**JDBC 一条通道能覆盖 Go 层 2/3 的覆盖度，代码量却接近 0。**
> 省下的时间全部投入 §6 变更管控、§7 脱敏、§8 诊断。

### 4.2 JDBC Agent 通道

```
┌────────────────────────────────────────────────────────────┐
│ adb-drivers::jdbc::channel                                 │
│  ├─ RuntimeKey = hash(driver, jar_path, jvm_opts, jre_ver)  │
│  ├─ RuntimeRegistry  同一 key 共享一个 JVM 进程               │
│  ├─ ProcessRegistry  pid / started_at（孤儿清理，见 L10）    │
│  └─ SessionManager   多会话隔离                             │
└───────────────────────────┬────────────────────────────────┘
                            │ JSON-RPC 2.0 over stdio
                ┌───────────▼────────────┐
                │ java -jar adb-jdbc.jar │
                │  ├─ HikariCP 连接池      │
                │  │   (按不可变连接身份共享) │
                │  └─ driver jars (用户目录)│
                └────────────────────────┘
```

**会话协议（简化自 DBX Agent Protocol v2，但更严格）**

```jsonc
// 握手
{"method":"hello","protocolVersion":2,"capabilities":["multi_session","structured_error_v1","transaction"]}

// 开会话
{"method":"open_session","id":1,"params":{
   "agentSessionId":"s-abc","sessionRole":"workload",  // workload | metadata
   "driver":"dm.jdbc.driver.DmDriver",
   "url":"jdbc:dm://host:5236","user":"SYSDBA","password":"***",
   "schema":"DAMENG","readOnly":true}}

// 查（游标分页，L4）
{"method":"query","id":2,"params":{"agentSessionId":"s-abc",
   "sql":"SELECT ...","maxRows":5000,"cursor":null}}

// 元数据（走独立 metadata 会话，不排队尾）
{"method":"introspect","id":3,"params":{"agentSessionId":"s-meta-1",
   "depth":"columns","scope":{"catalogs":["*"],"schemas":["*"]}}}
```

**资源限制**（防雪崩）

| 限制 | 值 | 理由 |
|---|---|---|
| 单 runtime 最大 session | 256 | 超出返回 `category: resource` |
| 最后一个 session 关闭后宽限 | 30s | 防频繁开关标签页反复冷启动 |
| JDBC checkout 绝对 deadline | 15s | **贯穿**准入/建连/初始化/checkout，**不重启计时** |
| 连接归还/驱逐 deadline | 5s | 独立 executor，不得阻塞 checkout |
| stdout 单行上限 | 8 MB | 超出报 `protocol` 错误而非撑爆缓冲 |

> **⭐ 关键设计**：checkout 的**全流程共用一个绝对 deadline**（DBX 也这么做，值得抄）。否则"准入 10s + 建连 10s + checkout 10s"会累积成 30s，用户以为卡死。

**取消的正确实现**

```java
// Xugu 的做法值得注意：go-xugu-driver 无法用 context 取消网络读
// → 服务端记录 sessionId，走**共享控制连接**调 DBMS_DBA.KILL_SESSION_TRANS
public class XuguAgent {
    private final Map<String, String> activeSessions;
    private final Connection controlConn;  // 每 endpoint 一条

    public void cancel(String sessionId) throws SQLException {
        try (PreparedStatement ps = controlConn.prepareStatement(
                "CALL DBMS_DBA.KILL_SESSION_TRANS(?)")) {
            ps.setString(1, sessionId);
            ps.execute();
        }
    }
}
```

**我们的处理**：Agent 启动时上报 `cancelStrategy: native | kill_session | best_effort`。界面必须如实告知用户：
> "该驱动不支持可靠取消。点击取消后请到服务器确认会话已终止。"

### 4.3 声明式连接类型（v4 重写）

> **来源**：DBX `plugins/connection-types/*.yaml` + `crates/dbx-types/build.rs`
> **完整脚手架**：`scaffold/declarations/`、`scaffold/crates/adb-types/build.rs`

#### 4.3.1 描述符结构

```yaml
# declarations/connections/dameng.yaml
schemaVersion: 1              # ⭐ 版本，build.rs 断言
order: 260                    # ⭐ UI 排序，build.rs 断言唯一
id: dameng                    # ⭐ 稳定 ID，build.rs 断言唯一
rustVariant: Dameng          # ⭐ 生成的 Rust enum 变体，build.rs 断言唯一
label: 达梦 DM8
icon: dameng.svg

dialect: Dameng              # ⭐ 必须存在于 declarations/dialects/
runtimeMode: jdbc            # native | jdbc | http
mcpMode: bridge              # ⭐ bridge 要求 runtimeMode=jdbc
defaultPort: 5236
urlTemplate: "jdbc:dm://{host}:{port}/{database}"

driverClass: dm.jdbc.driver.DmDriver   # ⭐ runtimeMode=jdbc 必填
driverArtifact:
  key: dameng-dm8
  checksum: "sha256:..."              # ⭐ 不能是占位符
```

#### 4.3.2 ⭐ `traits` —— 引擎特性（驱动 UI 提示的唯一真相源）

```yaml
traits:
  schemaAware: false          # 是否有 schema 层级（SQLite=false）
  singleDatabase: true        # 单库引擎（达梦/Oracle 模式）
  clearableQuerySchema: true
  identityKind: identity      # ⭐ identity|autoIncrement|rowguid|sequence|none
  sequenceObjects: false      # ⭐ false → UI 隐藏序列工作台
  transactionalDdl: false     # ⭐⭐ false → UI 提示「DDL 会自动提交」
  onlineDdlConcurrent: false
  multipleResultsets: false
  savepoints: true
  explainFormats: [text]      # ⭐ UI 只渲染支持的格式
  maxParams: 32767
  identifierQuote: "\""
  paramStyle: named           # ⭐ :name
```

**`traits` 直接驱动的 UI 行为（组件不许硬编码）**

| 字段 | UI 行为 |
|---|---|
| `transactionalDdl: false` | 结构编辑器顶部 `⚠ 达梦的 DDL 会隐式提交，无法通过事务回滚。` |
| `identityKind: identity` | 导出器生成 `IDENTITY(1,1)` 而非 `AUTO_INCREMENT` |
| `sequenceObjects: false` | 侧栏不渲染序列节点；结构编辑器不显示序列默认值 |
| `explainFormats: [text]` | Explain 面板只显示「文本」选项 |
| `paramStyle: named` | 补全/高亮/参数化查询用 `:name` |
| `identifierQuote` | SQL 生成时的标识符引用 |

#### 4.3.3 ⭐ `capabilities` —— 全部显式 true/false

```yaml
capabilities:                  # ⭐⭐ 15 个字段全部必填（deny_unknown_fields）
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
  databaseCreate: false        # ⭐ 明确关闭，不是"不写"
  fieldLineage: true
  sqlExplain: true
  userAdmin: false             # ⭐ 明确关闭
  specializedSurface: false    # ⭐ 有专属管理界面才 true
```

**为什么"显式 false"比"缺失"重要**

```yaml
# ❌ 缺失即 false：半年后有人加了这个能力 → 静默生效 → 用户在生产库建库
# ✅ 显式 false：  改动时 review 会看到 YAML diff
```

> **这是 R9「能力必须声明、不能靠人肉发现」的落地方式。**
> 直接根治 DBX 的 `#12124`（达梦导出错误生成 `IDENTITY_INSERT`）、
> `#11794`（MySQL→达梦丢失主键自增属性）、`#10308`（达梦性能回归）。

**三条配套规则**（抄自 DBX `connection-types/README.md`）

```text
1. specializedSurface: true 只在产品有专属管理界面时使用
   （Redis 浏览器 / MQ 控制台 / Mongo 文档浏览器）
   → 防止"什么都关掉然后自己写一套"
2. 否则至少启用一项 capability（build.rs 强制 assert）
3. formKind 是有限枚举，不是任意 YAML 表单引擎
   → 防止连接表单无限膨胀
```

#### 4.3.4 ⭐ `build.rs` 的 11 条断言

```rust
① schemaVersion == 1
② id 唯一            ③ rustVariant 唯一      ④ order 唯一
⑤ dialect 必须存在   ⑥ transferObjectFamily 必须存在
⑦ ⭐ 至少一个 capability（或 specializedSurface）
⑧ ⭐ runtimeMode=jdbc 必须有 driverClass
⑨ ⭐ 驱动 checksum 不能是占位符（REPLACE_ME / TODO）
⑩ traits 自洽：identityKind=sequence ⟹ sequenceObjects=true
              schemaAware ∨ singleDatabase 必须成立
              explainFormats 非空
⑪ ⭐ mcpMode=bridge ⟹ runtimeMode=jdbc
```

**在编译期 fail，不在运行时 fail。**

#### 4.3.5 codegen 五道保险

```
① build.rs 的 cargo::rerun-if-changed（目录级 + 文件级）
② Vite plugin 的 watch（含 add/unlink）
③ package.json: pretypecheck / pretest 钩子
④ ⭐ 生成物提交进 git（不依赖本地状态）
⑤ ⭐ pnpm check:types（CI 只读校验，不一致就 fail）
```

前后端校验逻辑**逐条对应**（`generate-types.ts` vs `build.rs`），
两边都跑 → 前端和后端对同一份声明的理解不会分叉。

#### 4.3.6 UI 消费方式（组件不许硬编码）

```ts
import { can, warnsImplicitDdlCommit, showsSequenceWorkspace, explainFormats }
  from "virtual:adb-declarations";

warnsImplicitDdlCommit("mysql")   // true → 显示 DDL 提交警告
showsSequenceWorkspace("dameng")  // false → 不渲染序列节点
explainFormats("dameng")          // ["text"] → 只显示文本选项
can("dameng", "databaseCreate")   // false → 隐藏"新建数据库"菜单
```

### 4.4 ⭐ 传输家族（`transferObjectFamily`）

> 抄自 DBX。**我 v3 只想到 `Dialect` trait，漏了这一层。**

#### 4.4.1 为什么需要

达梦、人大金仓(O模式)、OceanBase(Oracle模式)、GBase8s、高斯、南大通用 ——
它们的 DDL 语法、类型映射、IDENTITY 关键字**几乎一样**。
逐个实现 = 4 份重复代码 + 4 处不一致的 bug。

#### 4.4.2 实现

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
```

**四份家族**

| 家族 | 成员数 | 成员 |
|---|---|---|
| `oracle` | 6 | oracle, dameng, oceanbase-oracle, kingbase-oracle, gbase8s, highgo |
| `postgres` | 8 | postgresql, redshift, cockroachdb, greenplum, opengauss, gaussdb, timescaledb |
| `mysql` | 5 | mysql, mariadb, tidb, oceanbase-mysql, polardb-mysql |
| `sqlserver` | 3 | sqlserver, azure-sql, synapse |

#### 4.4.3 效果

```text
新增一个 Oracle 兼容国产库
  = 1 个 connections/*.yaml（4 行元信息）
  + 往 oracle.yaml 的 members 加 1 个 id
  → 传输规则零改动
```

#### 4.4.4 ⭐ `lossy: true` 的处理（产品底线）

```rust
pub fn map_type(from: &str, to: &str, src_type: &str) -> Option<MappedType> {
    let f = family_of(from)?;
    let t = family_of(to)?;
    let rule = f.type_mapping.get(&src_type.to_lowercase())?;
    Some(MappedType {
        target: rule.to.get(&t.id).cloned()?,
        lossy: rule.lossy,
        reason: rule.lossy.then(|| format!("{src_type} → 目标类型可能丢失精度或语义")),
    })
}
```

**UI 必须显式列出并要求确认**：

```text
⚠ 3 个字段的类型映射可能丢失精度

  表.字段          源类型         目标类型            风险
  orders.amount   NUMBER         NUMERIC(38,10)      🟠 精度范围可能收窄
  orders.created  DATE           TIMESTAMP           🟠 时区语义变化
  orders.rate     NUMBER(5,2)    NUMERIC(38,10)      ✅ 安全

                                      [取消]  [仍然继续]
```

> **精度丢失必须是用户决定的，不是静默发生。**
> 这是 DBX `#9667`（Oracle→OceanBase 金额精度丢失）、`#10556`（19 位数转整数溢出）的根因。

#### 4.4.5 build.rs 交叉引用校验

```rust
// 家族成员必须是已声明的连接
for f in &families {
    for m in &f.members {
        assert!(connection_ids.contains(m),
            "transfer-families/{}.yaml: 成员 '{}' 在 connections/ 中不存在", f.id, m);
    }
}
```

### 4.5 ⭐ 驱动补丁层（改进 DBX 的 fork）

> DBX fork 了 `tokio-postgres` / `postgres-types` / `postgres-protocol` 三个 crate
> 锁定 commit `ac8ad47` 支持高斯。代价：上游更新要手动 rebase。

**我们的做法：`variant.rs`**

```rust
pub enum PgVariant { Standard, GaussDB, KingbaseES, HighGo }

impl PgVariant {
    /// ⭐ 只有元数据查询和方言需要变体，驱动协议不动
    pub fn meta_sql(&self) -> &'static MetaQueries;
    pub fn quote_ident(&self, s: &str) -> String;
    pub fn transactional_ddl(&self) -> bool;
    pub fn add_column_rewrites(&self, version: i32, has_default: bool) -> bool;
    pub fn supports_concurrent_index(&self, version: i32) -> bool;
}
```

**收益**：0 个 fork；升级上游只改版本号；驱动协议永远跟随上游修复。

#### 4.5.1 ⭐ `MetaQueries` —— 集中所有元数据查询

```rust
pub struct MetaQueries {
    // ⭐⭐ 从不比较名字字符串，只用 OID
    list_tables_by_namespace_oid:  &'static str,
    columns_by_relation_oid:      &'static str,
    indexes_by_relation_oid:      &'static str,
    foreign_keys_by_relation_oid: &'static str,
    row_estimate:                 &'static str,
    comments:                     &'static str,   // pg_description join (objoid+objsubid)
    current_statements:           &'static str,
    cancel_statement:             &'static str,
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

#### 4.5.2 MySQL 的 memo 表（模拟 OID）

```sql
-- PG 有 pg_class.oid；MySQL 没有 → 自建映射表
CREATE TABLE _adb_object_id (
  connection_id TEXT    NOT NULL,
  object_hash   TEXT    NOT NULL,   -- hash(schema + name)
  oid           INTEGER NOT NULL,
  PRIMARY KEY (connection_id, object_hash)
);
```

**名字只解析一次，之后全程用 oid。**

#### 4.5.3 什么时候才真需要 fork

```
- 需要改 wire protocol 层（私有扩展）
- 需要改类型映射核心（postgres-types 的 FromSql）

目前我们都没遇到。国产库差异 99% 在元数据 SQL 和 DDL 语法。
实在要 fork：必须写 ADR，且 xtask/guard.rs 检查 [patch.crates-io] 非空时是否有 ADR。
```

### 4.6 `ui_warnings()` —— UI 提示声明化

```rust
pub fn ui_warnings(t: ConnectionType) -> Vec<UiWarning> {
    let d = descriptor(t);
    let mut v = Vec::new();
    if !d.traits.transactional_ddl {
        v.push(UiWarning {
            level: WarningLevel::Important,
            code: "ddl-implicit-commit",
            message: format!("{} 的 DDL 会隐式提交，无法通过事务回滚。", d.label),
            where_to_show: WarnSite::StructureEditor,
        });
    }
    if d.traits.identity_kind != IdentityKind::AutoIncrement { /* → Transfer 站 */ }
    if !d.traits.sequence_objects { /* → Sidebar 站 */ }
    v
}
```

> **组件不要 `if (conn === "mysql")`。一律问声明。**
> 这样新增数据库时 UI 自动正确，不需要改任何组件。

---

## 5. 元数据与语义层

### 5.1 统一元数据接口

```rust
#[async_trait]
pub trait MetaProvider: Send + Sync {
    async fn databases(&self, s: &dyn Session) -> Result<Vec<DatabaseId>>;
    async fn schemas(&self, s: &dyn Session, db: DatabaseId) -> Result<Vec<SchemaId>>;
    /// ⭐ Depth 控制懒加载（L5）
    async fn objects(&self, s: &dyn Session, parent: ObjectId, depth: Depth) -> Result<Vec<ObjectSummary>>;
    async fn columns(&self, s: &dyn Session, t: ObjectId) -> Result<Vec<ColumnDef>>;
    async fn indexes(&self, s: &dyn Session, t: ObjectId) -> Result<Vec<IndexDef>>;
    async fn foreign_keys(&self, s: &dyn Session, t: ObjectId) -> Result<Vec<ForeignKey>>;
    async fn ddl(&self, s: &dyn Session, o: ObjectId) -> Result<String>;
    async fn row_estimate(&self, s: &dyn Session, t: ObjectId) -> Result<Option<u64>>;
}
```

### 5.2 深度分级（L5 的核心）

| Depth | 拉取内容 | 何时触发 |
|---|---|---|
| `Name` | `id, name, type, comment` | **树的第一层，一次全部** |
| `Structure` | + 列、类型、null、默认值、PK | 用户展开节点 / 打开表 |
| `Detail` | + 索引、FK、触发器、约束、统计 | 用户点"详细" / 结构编辑器 |
| `Source` | + DDL、存储过程源码 | 用户点"查看源码" |

```rust
pub enum Depth { Name, Structure, Detail, Source }
```

**缓存策略**

```rust
pub struct MetaCache {
    inner: RwLock<HashMap<SnapshotKey, Arc<SchemaSnapshot>>>,
    disk:  SqliteMetaStore,          // 持久化，支持离线启动
    inflight: Mutex<HashMap<SnapshotKey, Weak<Notify>>>,   // single-flight，防重复查询
    stats:  RwLock<HashMap<SnapshotKey, CacheStats>>,
}
```

**失效**：
- 连接断开 → 清该连接
- 执行 DDL 成功 → 失效受影响的子树（不是全清）
- 用户手动刷新 → 强制
- TTL：默认 5 分钟（可配）

> **⭐ single-flight 很关键**：`#12299` 反馈"根据表名搜索表太慢了"，很多情况是同一时刻发了 N 个重复的元数据查询。加 single-flight 后 N 个并发请求只落一次库。

### 5.3 语义知识（护城河）

```rust
pub struct KnowledgeStore {
    repo: KnowledgeRepo,     // .adbctx（YAML，进 git）
    db:   DbComments,        // COMMENT ON TABLE/COLUMN
    hist: QueryHistoryStats, // 从查询历史学
    ai:   InferredKnowledge, // AI 推断，需人工确认
}
```

**四个来源，按可信度排序，自动合并，冲突时高可信度覆盖**

| 来源 | 可信度 | 内容 | 如何获取 |
|---|---|---|---|
| **数据库注释** | ★★★★★ | 官方说明、枚举含义 | `COMMENT ON`，走 OID 路径 |
| **`.adbctx` 仓库文件** | ★★★★☆ | 业务含义、JOIN 路径、坑位、负责人 | 用户维护，进 git |
| **查询历史统计** | ★★★☆☆ | 这张表最常被哪些 SQL 访问、常用 WHERE 条件、最长查询时间 | 本地 SQLite，`#12260` 反映的正是这块缺失 |
| **AI 推断** | ★★☆☆☆ | 列名语义、可能的枚举值、可能的 JOIN | LLM 分析列名 + 采样值，**必须标记"待确认"** |

**为什么这是护城河**：
1. 一次投入，四种形态复用（UI 显示 / MCP 工具 / CLI context / AI 提示词）
2. 团队沉淀 → 迁移成本
3. 竞品（Databricks Genie）需要企业级语义层平台，门槛高；我们从本地/私有化切入
4. 直接解决 `#12033`（用注释搜索表）、`#12198`（列注释不显示）这类反复出现的需求

**`.adbctx` 格式**

```yaml
# knowledge/orders.adbctx
table: orders
businessMeaning: 订单主表
owners: [trade-team]
enumValues:
  status:
    "1": 待支付
    "2": 已支付
    "3": 已发货
    "4": 已完成
    "9": 已取消
pitfalls:
  - "2 亿行，create_time 无索引，禁止不带 WHERE 查询"
  - "amount 单位是分，不是元"
  - "tenant_id = 0 表示全租户，不是空值"
joinPaths:
  - from: orders.user_id → users.id
    cardinality: many-to-one
  - from: order_items.order_id → orders.id
    cardinality: many-to-one
sla:
  maxRows: 10000
  maxDurationMs: 3000
```

### 5.4 本地搜索索引（L5）

```rust
// SQLite FTS5 + 拼音首字母（#12033 + 中文用户刚需）
CREATE VIRTUAL TABLE object_search USING fts5(
    name, comment, pinyin, kind UNINDEXED,
    tokenize = "unicode61 remove_diacritics 2"
);
CREATE INDEX idx_obj_kind ON object_search(kind);
```

- 搜 `kh` → 匹配"客户"（拼音首字母 `kh`）
- 搜 `dingdan` → 匹配"订单"
- 搜注释原文 → 匹配（`#12033`）
- 搜 `"status"` → 命中所有含 status 的表

**首次建立索引是后台任务**，不阻塞 UI。已有 `QUERY` 权限即可（不需要 `SELECT`）。

---

## 6. 变更管控引擎 ⭐ 差异化核心

> **来源**：`#11964` 用户明确要求"sql 审核功能，参考 cookieY/Yearning，增加产品竞争力"。
> 这是整个 issue 池里**唯一一条用户主动指出"这能增加竞争力"**的需求。必须做成核心。

### 6.1 模块总览

```text
┌──────────────────────────────────────────────────────────────┐
│                    变更管控引擎 adb-change                     │
│                                                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐            │
│  │ 结构编辑器   │  │ 影响评估   │  │ 回滚生成   │            │
│  │ Structure  │  │ Impact    │  │ Rollback   │            │
│  │ Editor     │  │ Analysis  │  │ Generator  │            │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘            │
│        │               │               │                    │
│        └───────────────┼───────────────┘                    │
│                        ▼                                     │
│              ┌──────────────────┐                           │
│              │  Migration 生成   │  → .sql / Go / Liquibase  │
│              └────────┬─────────┘                            │
│                       ▼                                      │
│              ┌──────────────────┐                           │
│              │  审批流 Approval  │  单人 / 双人 / 工单号      │
│              └────────┬─────────┘                            │
│                       ▼                                      │
│              ┌──────────────────┐                           │
│              │  执行 Executor   │  带 dry-run + 快照         │
│              └────────┬─────────┘                            │
│                       ▼                                      │
│              ┌──────────────────┐                           │
│              │  审计 adb-audit  │                           │
│              └──────────────────┘                           │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 结构编辑器（可视化改表）

```rust
// crates/adb-change/src/model.rs
pub struct TableDraft {
    pub table: ObjectId,
    pub name: String,
    pub columns: IndexMap<ColumnId, ColumnDraft>,
    pub indexes: IndexMap<IndexId, IndexDraft>,
    pub foreign_keys: IndexMap<FkId, FkDraft>,
    pub checks: Vec<CheckDraft>,
}

pub struct ColumnDraft {
    pub name: String,
    pub ty: TypeRef,
    pub nullable: bool,
    pub default: Option<Expr>,
    pub comment: Option<String>,
    pub identity: IdentityKind,     // None | AutoIncrement | Sequence
    pub collation: Option<String>,
    pub state: FieldState,          // Unchanged | Added | Modified | Dropped
}
```

**降级风险检测**（这是最有价值的部分）

```rust
pub enum ChangeRisk {
    DataLoss,        // 🔴 数据丢失
    Truncation,      // 🔴 数据截断
    TypeChange,      // 🟠 类型变更
    LockHeavy,       // 🟠 重锁表
    IndexOnly,       // 🟢 仅元数据
}

pub fn assess(changes: &[Change]) -> Vec<RiskFinding>;
```

**规则表**（可扩展，进 `declarations/risks/*.yaml`）

| 变更 | 风险 | 检测 |
|---|---|---|
| `varchar(200) → varchar(20)` | 🔴 **数据截断** | 取长度分布：`SELECT max(length(c))` 若 > 20 则告警并给出受影响行数 |
| `varchar(N) → varchar(M)`, N>M | 🔴 **数据截断** | 同上 |
| `varchar → int` | 🔴 **转换失败** | 采样 `count(*) WHERE c IS NOT NULL AND c !~ '^[+-]?\d+$'` |
| `NOT NULL → NULL` | 🟢 安全 | — |
| `NULL → NOT NULL` | 🔴 **存在 NULL 值** | `SELECT count(*) WHERE c IS NULL` |
| `DROP COLUMN` | 🔴 **数据丢失** | 必须手输列名二次确认 |
| `int → bigint` | 🟢 安全（PG 下需重写表） | — |
| `ADD COLUMN NOT NULL DEFAULT x` | 🟠 锁表 | PG < 11 需重写；MySQL 8.0 instant |
| `ADD INDEX` | 🟡 在线 DDL 可用 | 建议算法 |

### 6.3 影响评估（Dry-run）

```rust
pub struct ImpactReport {
    pub lock:        LockAnalysis,
    pub scan:        ScanAnalysis,
    pub rewrite:     RewriteAnalysis,
    pub disk:        DiskAnalysis,
    pub recommendation: Recommendation,
    pub warnings:    Vec<Warning>,
}

pub struct LockAnalysis {
    pub level: LockLevel,          // None | Metadata | RowShare | Exclusive | AccessExclusive
    pub estimated_ms: Option<(u64, u64)>,   // (乐观, 悲观) 区间
    pub basis: String,             // "PG 14 无默认值不重写表" / "MySQL 5.6 需 COPY"
    pub algorithm: OnlineDdlStrategy,  // Direct | GhOst | PtOsc | Inplace | None
}
```

**估算逻辑**（不追求精确，追求"不误导"）

```rust
// PostgreSQL
match (add_column_with_default, pg_version) {
    (_, v) if v < 11 && add_column_with_default => LockLevel::AccessExclusive,
    (_, v) if v < 12 && set_not_null => LockLevel::AccessExclusive,  // 全表扫描
    _ => LockLevel::ShareUpdateExclusive,
}

// MySQL
match op {
    AddColumn if has_default && mysql_version >= 8.0.12 => Instant,  // INSTANT，无锁
    AddIndex => Inplace,                                           // 不阻塞 DML
    DropColumn | ChangeColumn => requires_table_rebuild(mysql_version),
}
```

> **设计原则**：**不确定就给区间，不给点值。** 给点值会误导用户。
> `estimated_ms: Some((2_000, 40_000))` + `basis: "基于 850 万行 × 120 字节/行估算"`。

**执行前预演**（真 dry-run）

```sql
-- 在事务里执行，确认能过就回滚
BEGIN;
ALTER TABLE orders ADD COLUMN test_col int;
EXPLAIN <待执行的查询>;
ROLLBACK;
```

对支持事务的引擎（PG/MySQL/SQLite），**必须做这一步**并报告结果。

### 6.4 回滚脚本生成

```rust
pub enum RollbackStrategy {
    /// 直接逆向 DDL（安全）
    Direct(String),
    /// 需要数据恢复（危险）
    RequiresDataRestore { reason: String, backup: Option<SnapshotRef> },
    /// 无法回滚
    Irreversible { reason: String },
}

pub fn generate_rollback(orig: &SchemaSnapshot, target: &SchemaSnapshot, applied: &AppliedChanges)
    -> Result<RollbackPlan>;
```

**生成规则**

| 正向变更 | 回滚 | 标注 |
|---|---|---|
| `ADD COLUMN x` | `DROP COLUMN x` | ✅ 直接 |
| `ADD COLUMN x NOT NULL DEFAULT 'v'` | `DROP COLUMN x` | ⚠️ 数据将丢失 |
| `ALTER x TYPE varchar(200)→varchar(20)` | `ALTER x TYPE varchar(200)` | 🔴 **数据已截断，需从快照恢复** |
| `DROP COLUMN x` | 无法回滚 | 🔴 **自动创建快照**（`pg_dump --column` / `mysqldump`） |
| `ADD INDEX i` | `DROP INDEX i` | ✅ |
| `ADD CONSTRAINT fk` | `DROP CONSTRAINT fk` | ✅ |
| `RENAME a → b` | `RENAME b → a` | ✅ |
| `UPDATE ... WHERE` | 生成反向 `UPDATE` | 🔴 **无法还原原值，必须用执行前快照** |

> **⭐ 执行 DDL 前自动快照**是整个模块的关键设计：
> - 结构性小改：记录 `ALTER` 前后的 schema + 影响的行数
> - 危险操作：异步 `pg_dump` / `mysqldump` 单表，落盘到 `.adbbackups/`
> - 回滚时提供"从快照恢复"按钮
>
> **没有快照的变更不允许标记为"已执行"。** 这是产品底线。

### 6.5 审批流

```rust
pub enum ApprovalPolicy {
    /// 生产连接默认单人确认
    Single,
    /// 双人：申请人 ≠ 审批人
    Dual { approvers: Vec<String> },
    /// 对接外部工单系统
    Ticket { system: TicketSystem, id: String },
}

pub struct ChangeRequest {
    pub id: RequestId,
    pub ticket: Option<String>,
    pub target: ObjectId,
    pub environment: Environment,     // Dev | Test | Staging | Production
    pub statements: Vec<SqlStatement>,
    pub impact: ImpactReport,
    pub rollback: RollbackPlan,
    pub requested_by: String,
    pub window: Option<TimeWindow>,   // 允许的执行时间窗
    pub state: RequestState,
    // Draft → Pending → Approved → Executing → Done | Failed | RolledBack | Rejected
}
```

**执行时二次校验**（防止审批后连接被换成另一个）

```rust
// 审批绑定的是 ObjectId + 环境 + 期望的 schema hash
if current_schema_hash(target) != approved.schema_hash {
    return Err(AdbError::ApprovalStale {
        reason: "目标库结构在审批后发生了变化，请重新评估影响",
    };
}
```

### 6.6 Migration 文件生成

```rust
pub enum MigrationDialect { Plain, Flyway, Liquibase, Goose }
pub struct MigrationFile {
    pub version: String,      // V20261016_1430
    pub name: String,
    pub up: String,
    pub down: Option<String>,
    pub checksum: String,     // ★ 防篡改：执行时校验，不匹配则拒绝
}
```

生成物示例：

```sql
-- V20261016_1430_add_remark_to_orders.sql
-- generated by AgenticDB 0.1.0
-- target: prod / orders
-- impact: AccessExclusive ~2-40s (ADD COLUMN NOT NULL DEFAULT → PG<11 重写)
-- rollback: ALTER TABLE orders DROP COLUMN remark;

ALTER TABLE public.orders ADD COLUMN remark varchar(200);
COMMENT ON COLUMN public.orders.remark IS '备注';
```

**自检器（L12）**

```rust
pub fn self_check(sql: &str, target: ObjectId, dialect: &Dialect) -> Result<()> {
    // 1. schema 限定：生成语句中的表名 schema 必须等于目标 schema
    // 2. 幂等：重复执行不应二次报错（ADD → IF NOT EXISTS）
    // 3. 无截断：表达式索引/函数体/CLOB 长度必须与元数据一致
    // 4. 无重复：同一批次内不得出现重复的 CREATE/DROP
}
```

---

## 7. 脱敏引擎

### 7.1 目标

从生产库采样 → 自动脱敏 → 生成**外键一致、分布保持、可复现**的开发数据库。

> **竞争最干净的模块**：DBX / DBeaver / Beekeeper 全都没有。

### 7.2 规则引擎

```rust
#[derive(Clone, Serialize, Deserialize)]
pub struct MaskRule {
    pub id: String,
    pub matchers: Vec<Matcher>,
    pub action: MaskAction,
    pub reversible: bool,     // ⭐ 是否保持可逆（用于保持分布）
    pub scope: MaskScope,
}

pub enum Matcher {
    /// 列注释/列名匹配（中文优先）
    Comment(Regex),
    /// 列名匹配（中英文，支持拼音）
    ColumnName(PinyinRegex),
    /// 内容探测（自动识别手机号/邮箱等）
    Content(Regex),
    /// 显式指定
    Exact { table: ObjectId, column: ObjectId },
}

pub enum MaskAction {
    Null,                              // → NULL
    Const(String),                     // → 常量
    Redact,                            // → ********
    /// ⭐ 保持格式与分布：手机号 138****5678，随机但格式合法
    FmtPreserve { fmt: FmtSpec },
    /// ⭐ FPE 格式保留加密：同值 → 同密文，完全保持 join/group by
    Fpe { key: KeyId },
    /// 哈希（可反向查表）
    Hash { algo: HashAlgo, salt: SaltId },
    Generalize { granularity: Granularity },  // 日期 → 2026-01，金额 → 万位
    Shuffle,                            // 保持行内一致，行间打乱
    NullifyIfRare,                     // 低频值 → NULL（保护长尾隐私）
}
```

### 7.3 中文敏感字段规则库（开箱即用）

```yaml
# declarations/masks/zh-CN.yaml
rules:
  - id: cn.idcard
    matchers: [ { content: '^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$' } ]
    action: { fpe: "身份证" }
  - id: cn.mobile
    matchers: [ { content: '^1[3-9]\d{9}$' } ]
    action: { fmtPreserve: "1XX XXXX XXXX" }
  - id: cn.bankcard
    matchers: [ { content: '^\d{16,19}$' } ]
    action: { fmtPreserve: "尾号 {last4}" }
  - id: email
    matchers: [ { columnName: "email|mail|邮箱" } ]
    action: { fpe: "email" }
  - id: cn.name
    matchers: [ { columnName: "name|姓名|real_name|user_name" } ]
    action: { generalize: person }
  - id: cn.address
    matchers: [ { columnName: "address|addr|地址" } ]
    action: { generalize: "保留到市级" }
```

### 7.4 外键一致性（核心难点）

```rust
/// ⭐ 关键：所有指向同一被脱敏列的行，必须得到相同的替换值
pub struct ConsistencyMap {
    map: HashMap<(ObjectId, ColumnId, ValueHash), Value>,  // 原值 → 脱敏值
    dirty: DashSet<(ObjectId, ColumnId)>,
}

impl ConsistencyMap {
    pub fn mask(&mut self, ref_col: (ObjectId, ColumnId), raw: &Value, rule: &MaskRule) -> Value {
        if let Some(v) = self.map.get(&(ref_col.0, ref_col.1, hash(raw))) {
            return v.clone();                          // ⭐ 复用，保证一致
        }
        let masked = apply(rule, raw);
        self.map.insert((ref_col.0, ref_col.1, hash(raw)), masked.clone());
        masked
    }
}
```

**执行流程**

```text
1. 读 schema → 构建 FK 图
2. 选"根表"（无外键被依赖，或外键已确定要一并脱敏）
3. BFS 遍历：
   ├─ 对每个被脱敏列，提取 distinct 值（有限采样）
   ├─ 规则匹配 + 应用脱敏，存入 ConsistencyMap
   └─ 外键列的值强制从父表 ConsistencyMap 取（覆盖独立推断）
4. 生成 DDL（保留结构与索引）
5. 按 chunk 写数据（外键顺序保证不违反约束）
6. 生成 manifest.json（记录规则、映射摘要、checksum）→ 支持复现
```

### 7.5 分布保持

```rust
pub struct DistGuard {
    pub min_relative_error: f64,   // 默认 0.05
    pub checks: Vec<DistCheck>,
}

pub enum DistCheck {
    /// 数值列：分位数保持
    Quantiles { column: ObjectId, points: Vec<f64> },
    /// 分类列：基数与频次保持
    CardinalityAndFrequency { column: ObjectId },
    /// 时间列：时间范围保持
    TimeRange { column: ObjectId },
}
```

脱敏后校验，超过阈值则**告警并提供调参入口**，而不是静默产出偏差数据。

### 7.6 可复现

```json
{
  "version": "0.1.0",
  "source": { "connection": "prod-mysql", "snapshot_at": "2026-10-16T10:00:00Z" },
  "seed": 20261016,
  "rules": ["cn.mobile", "cn.idcard", "email"],
  "mapping_digest": "sha256:abc...",
  "tables": { "users": 10000, "orders": 50000 },
  "checksums": { "users": "sha256:...", "orders": "sha256:..." }
}
```

同一 `seed` + 同一规则版本 → **完全相同的输出**。可直接进 CI。

---

## 8. 性能诊断引擎

### 8.1 定位

> **DBX 只有 Explain Plan 可视化，没有"告诉你哪里有问题"。**
> 目标：**打开一张表就知道它有什么毛病。**

### 8.2 静态检查（零成本，开表即跑）

```rust
pub enum Finding {
    /// 🔴 无主键 → UPDATE/DELETE 无法安全生成 WHERE
    NoPrimaryKey { table: ObjectId },
    /// 🔴 无索引且有外键被引用 → JOIN 会全表扫
    UnindexedForeignKey { table: ObjectId, referenced_by: ObjectId },
    /// 🟠 VARCHAR 主键 / 超长索引前缀
    OverlongTextIndex { table: ObjectId, index: ObjectId },
    /// 🟠 疑似敏感字段未脱敏（对接 §7 规则）
    SensitiveUnmarked { table: ObjectId, columns: Vec<ObjectId> },
    /// 🟠 表/列无注释 → AI 和人都看不懂
    NoComment { table: ObjectId, coverage: f32 },
    /// 🟡 字段类型不一致（跨表同名字段类型不同 → 迁移会出错）
    InconsistentColumnType { column: String, variants: Vec<(ObjectId, TypeRef)> },
    /// 🟡 疑似冗余索引（a,b 与 a,b,c 前缀重复）
    RedundantIndex { table: ObjectId, indexes: Vec<ObjectId> },
    /// 🟡 枚举值未标注（AI 写 SQL 的最大坑）
    UndocumentedEnum { table: ObjectId, column: ObjectId, cardinality: u64 },
    /// 🟡 软删除无索引
    SoftDeleteUnindexed { table: ObjectId, column: ObjectId },
    /// 🟡 无更新时间戳字段
    NoTimestampColumn { table: ObjectId },
    /// ℹ️ 缺列注释覆盖率
    Stats { rows: u64, size: String, growth_30d: Option<f64> },
}
```

**跨库类型一致性检查是独特价值**（`#9667` 精度丢失、`#10556` 整数溢出的根因）

```rust
pub struct TypeVariant {
    pub table: ObjectId,
    pub ty: TypeRef,
    pub source: String,
}
// users.amount = decimal(20,4)
// orders.amount = decimal(10,2)   ⚠️ 不一致 → 迁移必然精度丢失
// refund.amount = float            ⚠️ 精度风险
```

### 8.3 查询级诊断（Explain 解读）

```rust
pub struct PlanAnalysis {
    pub plan: PlanTree,
    pub findings: Vec<PlanFinding>,
    pub summary: String,            // 一句话人话
}

pub enum PlanFinding {
    SeqScanOnLargeTable { table: ObjectId, rows: u64, ratio: f64 },
    MissingIndexOnFilter { columns: Vec<ObjectId>, est_rows: u64 },
    CartesianProduct { tables: Vec<ObjectId> },
    SortOnLargeData { rows: u64, spills: bool },
    NestedLoopBadJoin { outer: u64, inner_rows: f64 },
    FilesortWithoutLimit { rows: u64 },
    EstimateOffByFactor { table: ObjectId, actual: u64, estimated: u64, factor: f64 },
    ImplicitTypeConversion { column: ObjectId, from: String, to: String },
    SelectStar { columns: usize },
}
```

**输出示例**

```
⚠ 这条查询会扫描 users 全表（285 万行）并回表 285 万次

EXPLAIN 显示：  Seq Scan on users  (cost=0.00..45892.14 rows=2847310)
过滤条件：      status = 'paid'
索引检查：      users 上 status 无索引

建议：
  CREATE INDEX idx_users_status ON users(status);        [+ 创建索引]
  或改写为  WHERE status = 'paid' LIMIT 100              [复制改写]
  预计：全表扫描 285 万行 → 索引扫描 ~12 万行，约 3.4s → 0.02s
```

> **关键**：不是贴 JSON，是**人话 + 可点击的修复动作**。

### 8.4 建议执行

```rust
pub enum SuggestionAction {
    CreateIndex { ddl: String, est_gain: String },
    RewriteSql { from: String, to: String },
    AddWhere { clause: String },
    AnalyzeTable,
    IncreaseStatTarget,
}
```

所有建议都是**可一键执行**的（走正常的安全链路：预检 → 确认 → 审计）。

---

## 9. Agent 与 MCP 层

### 9.1 架构

```text
┌────────────────────────────────────────────────────────┐
│  用户                                              │
│  ├─ UI 侧挂 AI 面板                                │
│  ├─ CLI  (agenticdb query ... --json)               │
│  └─ Skill (~/.agents/skills/agenticdb/SKILL.md)     │
└───────────────────────┬────────────────────────────┘
                        │
              ┌─────────▼──────────┐
              │  MCP Server        │  ← 只读一份配置
              │  (rmcp)            │    一份凭据实现（L1）
              └─────────┬──────────┘
                        │
              ┌─────────▼──────────┐
              │  Command Registry  │  ← 自动生成工具清单
              │                    │    自动生成 annotations（L2）
              └─────────┬──────────┘
                        │
              ┌─────────▼──────────┐
              │  Policy Layer      │  ← 三级权限 + allowlist
              │  (ReadOnly /       │    每次调用写审计
              │   DataRW / Full)   │
              └────────────────────┘
```

### 9.2 三级权限 + Allowlist

```rust
pub enum McpAccessMode {
    ReadOnly,     // 只允许 Danger::ReadOnly 的命令
    DataReadWrite,// 允许 ReadOnly + Mutating
    FullAccess,   // 允许全部（含 Destructive/Admin）
}

pub struct McpPolicy {
    pub mode: McpAccessMode,
    /// ⭐ 显式例外。⚠️ UI 必须在"所有连接"模式下也显示这些例外（见下方）
    pub per_connection: HashMap<ConnectionId, McpAccessMode>,
    /// 无论 mode 如何，永远拒绝
    pub deny: HashSet<CommandName>,
}
```

> **⚠️ 必须避免的 UI 陷阱**（来自 `#11503`）：
> 用户设了"所有连接 = 只读"，但某个连接有"允许安全写入"例外。**如果切换到"所有连接"时把例外隐藏了，用户会误以为所有连接都只读，直到最后的汇总才发现。**
>
> **我们的设计**：
> - 选择"所有连接"时，**在下方显式列出所有生效的例外**，并给出总数徽标（如 `⚠ 2 个连接存在例外`）
> - 例外永远可见、可编辑，不需要切换到"自定义范围"
> - 保存前做一次**权限汇总预览**："应用后：18 个连接只读 · 2 个可写（orders-write-test, staging-sandbox）· 0 个全权"
> - MCP 设置页默认展开"当前实际生效的权限"区块，而不是"正在编辑的模式"

### 9.3 工具集（22 个，全部由 Registry 生成）

| 分组 | 工具 | Danger |
|---|---|---|
| **元数据** | `adb_list_databases` | ReadOnly |
| | `adb_list_tables` | ReadOnly |
| | `adb_describe_table` | ReadOnly |
| | `adb_show_indexes` | ReadOnly |
| | `adb_show_constraints` | ReadOnly |
| | `adb_get_ddl` | ReadOnly |
| **查询** | `adb_query` | ReadOnly |
| | `adb_explain` | ReadOnly |
| | `adb_query_page` | ReadOnly |
| **上下文** ⭐ | `adb_context` | ReadOnly |
| | `adb_search_objects` | ReadOnly |
| | `adb_knowledge` | ReadOnly |
| **数据** | `adb_insert_row` | Mutating |
| | `adb_update_rows` | Mutating |
| | `adb_delete_rows` | Mutating |
| **结构** ⭐ | `adb_propose_change` | ReadOnly |
| | `adb_apply_change` | Destructive |
| | `adb_rollback_change` | Destructive |
| | `adb_impact_analysis` | ReadOnly |
| **运维** | `adb_health_check` | ReadOnly |
| | `adb_kill_session` | Admin |
| | `adb_process_list` | ReadOnly |

**⭐ 三个关键设计**

**① `adb_context` —— Agent 的记忆入口**

```bash
agenticdb context prod --tables users,orders --format prompt
```

输出是**压缩过的语义上下文**：

```text
# Database: prod (PostgreSQL 16.2, 12.4 GB)

## orders  订单主表  · 2.1M rows · 1.8 GB · PK: id
业务含义: 订单主表。amount 单位是分。tenant_id=0 表示全租户。
⚠ 2亿行表（orders_log 2.1亿），create_time 无索引，禁止全表扫描

  columns:
    id          bigint       PK
    tenant_id   bigint       FK→tenants.id       -- 租户，0=全部
    user_id     bigint       FK→users.id
    status      smallint     -- 1=待支付 2=已支付 3=已发货 4=已完成 9=已取消
    amount      numeric(20,4)  -- 单位：分
    created_at  timestamptz   -- idx: idx_created
    ...
  indexes: idx_status(created_at), uk_order_no(order_no), idx_user(user_id, status)
  # user_id+status 是最热查询路径 (占 41% 查询)

## users  用户表  · 320K rows · PK: id
  columns: id uuid PK, email citext, name text, created_at timestamptz
  # 无 email 索引

## JOIN paths
  orders.user_id → users.id   (many-to-one)
  orders.tenant_id → tenants.id (many-to-one)

## Constraints
  orders.status: 必须用 IN (1,2,3,4,9)，0 和 NULL 无效
```

**压缩策略**（1000 张表塞不进 context）

| 策略 | 说明 |
|---|---|
| 只输出显式请求的表 | `--tables` 参数 |
| 按"查询热度"排序 | 从查询历史统计 |
| 宽表裁剪 | >200 列时只输出 PK/FK/索引相关列 |
| 低频表折叠 | `--depth=name` 时只输出表名+注释 |
| 差异优先 | 与上次的 context 做 diff，只重发变化部分（`--since <hash>`） |

**② `adb_propose_change` —— AI 改库的强制通道**

> **这是安全设计的核心。** AI 不允许直接执行 DDL，只能提出提案。

```jsonc
// AI 调用
{"name": "adb_propose_change", "arguments": {
  "connection": "prod", "table": "orders",
  "change": {"addColumn": {"name": "remark", "type": "varchar(200)",
                            "nullable": true, "comment": "备注"}},
  "reason": "用户要求增加备注字段"}}

// 返回（人类可读）
{
  "request_id": "CR-2026-1016-0001",
  "state": "PendingApproval",
  "sql": "ALTER TABLE public.orders ADD COLUMN remark varchar(200);",
  "impact": {
    "lock": "AccessExclusive, ~2-40s",
    "basis": "ADD COLUMN 可空无默认值，PG 12+ 不重写表",
    "algorithm": "Direct",
    "recommendation": "低峰期执行"
  },
  "rollback": "ALTER TABLE public.orders DROP COLUMN remark;",
  "required_approval": true,
  "message": "该变更需要人工审批。执行前请确认 impact 评估。"
}
```

**③ 单元格读取必须分页**（见 `#9865`）

```rust
pub struct CellReadParams {
    pub sql: String,
    pub row_index: usize,
    pub col: Option<String>,
    pub offset: usize,
    pub limit: usize,          // 默认 2000，⭐ 实际生效（DBX 的 bug 是参数未生效）
    pub truncate: bool,        // 截断时必须返回 next_offset
}
pub struct CellReadResult {
    pub text: String,
    pub truncated: bool,
    pub next_offset: Option<usize>,
    pub total_len: Option<usize>,
}
```

> **教训**：DBX 的 `cell_char_limit` 参数定义了但没生效（`#9865`），用户以为被截断到 200 字符是设计如此。**所有分页/截断参数必须有测试断言实际生效。**

### 9.4 AI 上下文装配

```rust
pub struct AiContextBuilder {
    schema:      SchemaSnapshot,
    knowledge:   SchemaKnowledge,
    selection:   Option<Selection>,     // 用户在编辑器里选中的文本
    history:     QueryHistoryStats,     // 这张表最近跑过什么
    budget:      TokenBudget,
}

impl AiContextBuilder {
    /// 按优先级裁剪，保证关键信息永远在
    pub fn build(&self, ask: &str) -> String {
        let mut b = TokenBudget::new(8000);
        let mut out = String::new();
        // P0: 用户选中的内容 + 明确的表
        out += &self.must_include();              b.take(out.len());
        // P1: 直接相关的表（从 selection / ask 里提实体）
        out += &self.related_tables(&ask);        b.take(out.len());
        // P2: JOIN 路径
        out += &self.join_paths();                 b.take(out.len());
        // P3: 枚举值与坑位（AI 最容易错的地方）
        out += &self.pitfalls();                   b.take(out.len());
        // P4: 最近查询历史（提示用户习惯）
        out += &self.recent_history();             b.take(out.len());
        // 剩余预算放 schema
        out += &self.schema_fill(b.remaining());
        out
    }
}
```

**输出必须标注知识来源**（建立信任）

```text
status 枚举: 1=待支付 2=已支付 ... （来源: 数据库注释 ✓已确认）
customer_level 可能为 VIP/SVIP/NORMAL（来源: AI 推断 ⚠待确认，点击确认）
```

### 9.5 Agent Skill 分发

```bash
agenticdb agent setup        # 安装/更新 ~/.agents/skills/agenticdb/SKILL.md
agenticdb agent status       # 检查安装状态
```

`SKILL.md` 核心内容：

```markdown
# AgenticDB

## 何时使用
当用户提到具体数据库、表名，或需要查询/分析生产数据时。

## 强制规则
1. 生产连接（名字含 prod/production）一律先 `adb_propose_change`，不得直接 `adb_apply_change`。
2. 查询前先 `adb_describe_table`，不要猜列名。
3. 大表（>100万行）必须加 WHERE 或 LIMIT，用 `adb_context` 查看表注释里的警告。
4. 查询结果超过 100 行时，用 `adb_query_page` 翻页，不要一次拉全表。

## 命令
- `adb_list_tables --connection X --search keyword`
- `adb_describe_table --connection X --table users`
- `adb_context --connection X --tables a,b --format prompt`
- `adb_query --connection X --sql "..." --max-rows 100`
```

> **⭐ 这是最低成本、最高回报的分发。** 一行命令让 shell-capable Agent（Claude Code / Cursor / Codex）立刻会用你的工具，**自带分发渠道**。

---

## 10. 安全与审计

### 10.1 危险分级

| 级别 | 定义 | UI | MCP | 审批 |
|---|---|---|---|---|
| 🟢 `ReadOnly` | 查询、元数据 | 无提示 | `readOnlyHint: true` | 不需要 |
| 🟡 `Mutating` | 写数据（DML） | Toast + 撤销 | `readOnlyHint: false` | 单人 |
| 🟠 `Destructive` | 改结构、删数据 | 模态 + 手动勾选 | `destructiveHint: true` | 双人（生产） |
| 🔴 `Admin` | 授权、杀进程 | 模态 + 输连接名 | `destructiveHint: true` | 双人 + 工单号 |

**确认框必须包含**

```
⚠  危险操作

  ALTER TABLE orders ADD COLUMN remark varchar(200) NOT NULL DEFAULT ''

  影响      AccessExclusive 锁表 · 预估 12–180s · 全表重写 2.1M 行
  快照      将自动创建（orders.sql.gz, ~180 MB）
  回滚      ALTER TABLE orders DROP COLUMN remark;
  操作人    jason@dev-mbp
  来源      AI Agent (claude-code) · 工单 CHG-2026-1016-01

  □ 我已确认上述影响

              [取消]        [确认执行]
```

> **来源必须区分"人"和"Agent"**（DBX 已在做，见其 UI 截图）。AI 时代这是审计的基本要求。

### 10.2 凭据存储

```rust
// crates/adb-secret/src/lib.rs —— 全系统唯一实现（L1）
pub struct SecretStore {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    master_key: Option<[u8; 32]>,          // ⭐ L9: 进程内缓存，取一次
    keyring:  Box<dyn Keyring>,
    db:       SqliteSecretDb,
}

/// API 约束：
/// - 所有形态（Desktop/Web/CLI/MCP/Docker）必须链接此 crate
/// - MCP 不允许独立编译分发
/// - master_key 首次访问时从 Keyring 取，之后常驻内存
/// - 数据库里只存密文（nonce || ciphertext）
```

**测试门禁**

```rust
// xtask/verify_secret.rs —— CI 强制
#[test]
fn every_binary_links_adb_secret() {
    for bin in ["adb-mcp", "adb-cli", "adb-web", "desktop"] {
        let tree = cargo_tree(bin);
        assert!(tree.contains("adb-secret"),
                "{bin} 未链接 adb-secret，违反凭据单一实现约束 (L1)");
    }
}
```

**Keyring 后端**

| 平台 | 后端 | 降级 |
|---|---|---|
| macOS | Keychain (`com.agenticdb.secret-store.v1`) | 提示用户设置文件密码 |
| Windows | Credential Manager | 同上 |
| Linux | Secret Service (libsecret) | 同上 |
| Docker/无头 | `ADB_MASTER_KEY` 环境变量 | 必填 |

> **Linux 注意**（`#10245`）：Secret Service 是**阻塞 D-Bus 调用**，绝不能在 Tokio 异步运行时里直接调。必须 `spawn_blocking`。DBX 曾因此 panic（exit 139 / "Cannot start a runtime from within a runtime"）。

### 10.3 审计

```rust
pub struct AuditEvent {
    pub id: Uuid,
    pub at: DateTime<Utc>,
    pub actor: Actor,                 // User{os_user} | Agent{mcp_client, model}
    pub source: Origin,               // Desktop | Web | Cli | Mcp | Docker | ChangeWorker
    pub connection: ConnectionId,
    pub environment: Environment,     // 由连接名启发 + 可手动覆盖
    pub command: CommandName,
    pub danger: Danger,
    pub sql: Option<String>,          // 完整 SQL，不截断
    pub params: Option<serde_json::Value>,
    pub outcome: Outcome,             // Ok | Denied{reason} | Failed{err} | Cancelled
    pub rows_affected: Option<u64>,
    pub duration_ms: u64,
    pub snapshot_id: Option<String>,  // 危险操作前的快照
    pub request_id: Option<RequestId>,// 变更工单
    pub client_addr: Option<IpAddr>,  // Web/Docker 场景
}
```

**SQL 不截断、不脱敏存储在本地 SQLite。** 可导出 CSV / JSONL 供审计系统消费。

**⚠️ 审计不可关闭**：`Danger::ReadOnly` 之外的命令，`audit` 强制为 `true`，代码里无法绕过。

---

## 11. 前端架构

### 11.1 目录结构（照抄 DBX 最有价值的实践）

```text
apps/desktop/src/
├── lib/                  ⭐ 全部业务逻辑，纯函数，可单测
│   ├── command/          命令调用与权限包装（从 Registry 自动生成的 TS 类型）
│   ├── dataGrid/         表格逻辑（列布局、筛选、编辑、复制）
│   ├── sql/              SQL 解析/格式化/高亮规则
│   ├── editor/           CodeMirror 扩展
│   ├── sidebar/          对象树（虚拟滚动 + 懒加载 L5）
│   ├── knowledge/        ★ 语义层前端
│   ├── change/           ★ 变更管控 UI 逻辑
│   ├── mask/             ★ 脱敏配置
│   ├── diag/             ★ 诊断报告渲染
│   ├── ai/               AI 面板
│   ├── connection/       连接管理
│   └── __tests__/        ⭐ 与 lib 同规模
├── components/           纯展示
├── composables/
├── stores/               Pinia（控制在 15 个以内）
└── styles/
```

**规则**：
- `components/` **不含业务逻辑**，只接受 props 和 emit
- **所有可测逻辑必须在 `lib/` 的纯函数里**，不在组件里
- `lib/__tests__/` 的测试文件数应与 `lib/` 的源文件数相当

> DBX 的 `lib/` 有 1930 文件、`lib/__tests__/` 有 570 个测试文件。这是它敢用 Vue 而测试覆盖还行的原因。

### 11.2 关键组件

| 组件 | 要点 |
|---|---|
| `ConnectionTree` | 虚拟滚动 + 懒加载 + 本地 FTS 搜索 + 拼音 + 颜色标识 + 生产标记 |
| `QueryEditor` | CodeMirror 6 + 5 种方言 + LSP 式补全 + hover 延迟 ≥150ms |
| `ResultGrid` | **AG Grid Community** + 服务端游标 + 行号真实 |
| `SchemaEditor` | ★ 可视化改表 + 风险标注 + 影响预览 |
| `ImpactPanel` | ★ 锁/扫描/重写/磁盘 + 建议 |
| `RollbackPanel` | ★ 回滚脚本 + 快照恢复 |
| `ApprovalFlow` | ★ 审批 + 权限汇总预览 |
| `MaskConfigEditor` | ★ 规则编辑 + 样本预览 |
| `DiagnosisPanel` | ★ 角标 + 问题列表 + 一键修复 |
| `AiPanel` | 侧挂，默认折叠 |
| `McpSettings` | ⚠️ 例外必须始终可见 |

### 11.3 状态管理

```ts
// 只保留必要的全局状态，其余用 composable 局部状态
export const useConnectionStore = defineStore('connections', () => {
  const items = ref<Connection[]>([])
  const byId = computed(() => new Map(items.value.map(c => [c.id, c])))
  // ⚠️ 密码永不入 store，只存 connectionId；实际密码由后端 Keyring 管理
})

export const useTabStore = defineStore('tabs', () => { /* 标签页 */ })
export const useQueryStore = defineStore('query', () => { /* 执行中查询 */ })
```

**原则**：**任何 store 都不持有凭据。** 前端只有 `connectionId`。

---

## 12. 存储与配置

```text
~/.agenticdb/
├── config.db              SQLite：连接配置（密码为密文）、偏好、布局
├── history.db             SQLite：查询历史、结果指纹
├── audit.db               SQLite：审计日志（可轮转）
├── knowledge/             *.adbctx 语义知识（git 友好的纯文件）
├── plugins/               插件
├── agents/
│   ├── jre/               托管 JRE
│   └── jars/              JDBC 驱动
├── cache/
│   └── meta/              元数据快照（SQLite，按连接分文件）
├── backups/               变更前快照
└── logs/
```

**配置导出/导入**（团队分发）

```bash
agenticdb config export --out team.adbcfg          # 连接定义（无密码）
agenticdb config export --out team.adbcfg --with-secrets --passphrase   # 加密
```

### 12.1 连接即代码

```yaml
# connections.yaml —— 进 git
defaults:
  sshTunnel: bastion-01
environments:
  dev:  { host: dev-db.internal,  readOnly: true }
  test: { host: test-db.internal, readOnly: false }
  prod: { host: prod-db.internal, readOnly: true, requireApproval: Dual }
databases:
  - name: orders
    type: postgresql
    database: orders
    environments: [dev, test, prod]
    defaultRowLimit: 1000        # ⭐ 打开表默认只取 N 行（#12070 诉求）
    knowledge: knowledge/orders.adbctx
```

凭据永远**不**在这个文件里。凭据从 Keyring 或 `ADB_<NAME>_PASSWORD` 环境变量取。

### 12.2 Schema 快照与元数据缓存

```sql
-- cache/meta/<conn>.db
CREATE TABLE snapshot (
  id INTEGER PRIMARY KEY,
  taken_at TIMESTAMP,
  db_id TEXT, full_hash TEXT
);
CREATE TABLE object (
  snap_id INTEGER, oid TEXT, parent_oid TEXT,
  name TEXT, kind TEXT, comment TEXT, pinyin TEXT,
  n_rows INTEGER, size_bytes INTEGER,
  PRIMARY KEY (snap_id, oid)
);
CREATE VIRTUAL TABLE object_fts USING fts5(name, comment, pinyin, content=object, content_rowid=rowid);
CREATE TABLE column_def (
  snap_id INTEGER, oid TEXT, ord INTEGER,
  name TEXT, type TEXT, nullable INT, default_expr TEXT,
  comment TEXT, is_pk INT, is_fk INT, ordinal_pos INT,
  PRIMARY KEY (snap_id, oid)
);
```

**失效策略**：`full_hash` 变了才重建索引。未变则直接复用（离线可用）。

---

## 13. 错误契约

### 13.1 统一错误类型

```rust
#[derive(Debug, thiserror::Error)]
pub enum AdbError {
    #[error("连接失败: {0}")]
    Connection(ConnError),

    #[error("查询被取消")]
    Cancelled,

    /// ⭐ 超时。⚠️ 绝不可自动重放（见 §13.3）
    #[error("操作超时")]
    Timeout { stage: Stage },

    #[error("权限不足: {reason}")]
    PermissionDenied { reason: DenyReason },

    /// ⚠️ 必须携带位置，用于 UI 跳转
    #[error("{message}")]
    Sql { message: String, position: Option<SqlPosition>, sql_state: Option<String> },

    #[error("协议错误: {0}")]
    Protocol(String),

    #[error("资源耗尽: {0}")]
    Resource(String),

    #[error("变更已失效，需要重新评估")]
    ApprovalStale { reason: String },
}

pub struct SqlPosition {
    pub line: u32,       // 1-based
    pub column: u32,     // 1-based
    pub offset: usize,
    pub length: usize,
}
```

> **⭐ `SqlPosition` 是从 DBX 抄的高价值细节**（见其 `issue-5581-oracle-error-position-*` 截图）。开发者每天遇到几十次 SQL 错误，点击错误直接跳到出错行列，省下的是**真实的分钟级时间**。

### 13.2 错误定位

```rust
// 从数据库原生错误中提取行列
fn locate(err: &AdbError, sql: &str) -> Option<SqlPosition> {
    match err {
        AdbError::Sql { position, .. } => position.clone(),
        _ => {
            // PG: 42P01 ... at line 3, column 5
            // MySQL: You have an error ... at line 1 near 'oder'
            // Oracle: ORA-00942: ... at line:1 column:8
            parse_native_position(err).map(|p| p.resolve_against(sql))
        }
    }
}
```

### 13.3 超时的处理铁律

```rust
// ❌ 绝对禁止
async fn execute_with_retry(sql: &str) -> Result<Rows> {
    for _ in 0..3 {
        match execute(sql).await {
            Err(AdbError::Timeout { .. }) => continue,   // 💥 可能重复写入！
            r => return r,
        }
    }
}

// ✅ 正确
async fn execute_with_retry(sql: &str, danger: Danger) -> Result<Rows> {
    for attempt in 0..3 {
        match execute(sql).await {
            Err(e @ AdbError::Timeout { .. }) => {
                // ⭐ 只有只读才允许自动重试
                if danger != Danger::ReadOnly {
                    return Err(e.with_hint(
                        "操作超时，数据库可能已执行。请到服务器确认后再决定是否重试。"));
                }
                // 只读重试也要先确认连接健康（L8）
                if attempt > 0 { ensure_healthy().await?; }
            }
            r => return r,
        }
    }
}
```

> **这条铁律要写进 `CLAUDE.md` / `CONTRIBUTING.md`，并在 code review checklist 里。**
> DBX 的 Agent Protocol v2 里那句 `retryable is an internal hint and never authorizes automatic SQL replay` 是我见过最精确的表述。

---

## 14. 可观测性

```rust
// tracing spans
#[tracing::instrument(skip_all, fields(conn = %conn_id, danger = ?danger))]
async fn execute(...) {}

// 关键指标（导出 Prometheus 格式，或直接本地日志）
- query_duration_ms{database, danger, outcome}
- rows_returned{database}
- pool_active / pool_idle / pool_wait_ms
- metadata_cache_hit_ratio
- jdbc_checkout_ms{driver}
- agent_process_alive
```

**用户可读的诊断页**（`agenticdb doctor`）

```text
✅ 连接库         ~/.agenticdb/config.db (2.1 MB)
✅ 凭据           macOS Keychain (com.agenticdb.secret-store.v1)
✅ 元数据缓存     12 个连接, 平均 3.2s 冷启动 → 0.1s
⚠️ JDBC 运行时   JRE 17 已就绪 (~/Library/agenticdb/agents/jre)
⚠️ 达梦驱动      dm8-jdbc.jar 已下载 (8.4 MB) · 签名未验证
❌ 插件          2 个插件句柄被 dbx-mcp 进程占用（PID 41234）
   → 运行 agenticdb doctor --kill-orphans
```

---

## 15. 测试策略

### 15.1 分层

| 层 | 工具 | 内容 | 门禁 |
|---|---|---|---|
| **架构守卫** | `xtask` | 依赖方向、凭据唯一性、MCP annotations、L4 round-trip | **硬门禁** |
| **单元** | cargo nextest | 纯函数：SQL 生成、diff、规则匹配、脱敏、估算 | 覆盖率 ≥70% |
| **契约** | cargo nextest | 每个方言 × 每个驱动 trait 的实现一致性 | **硬门禁** |
| **集成（容器）** | testcontainers | 真实 PG/MySQL/SQLite | **硬门禁** |
| **集成（国产库）** | 专用测试机 + SSH 隧道 | 达梦/金仓/OceanBase | 手动触发 |
| **前端单测** | Vitest | `lib/` 纯函数 | ≥70% |
| **E2E** | Playwright | 核心 5 条路径 | **硬门禁** |
| **性能** | criterion + vitest bench | 启动、元数据冷/热、10万行网格 | 回归 >10% 失败 |

### 15.2 架构守卫（CI 硬门禁）

```rust
// xtask/architecture.rs —— 这些测试必须永远通过

#[test] fn dependency_direction_is_enforced();           // §2.2
#[test] fn secret_store_is_single_implementation();       // L1
#[test] fn mcp_tools_declare_annotations();               // L2
#[test] fn schema_metadata_is_oid_keyed();                // L3
#[test] fn data_transfer_roundtrip_is_lossless();         // L4
#[test] fn generated_ddl_is_schema_qualified();           // L12
#[test] fn pagination_params_take_effect();               // #9865
```

### 15.3 L4 Round-trip 硬门禁

```rust
#[tokio::test]
async fn pg_export_import_roundtrip() {
    let db = TestPg::new().await;
    db.create_fixture_schema().await;      // 含：FK 引用独立唯一索引、同名约束、NULL vs ''、decimal 精度
    let dump = db.export_all().await;

    let db2 = TestPg::new().await;
    db2.import(dump).await;

    assert_eq!(db.structure_hash().await, db2.structure_hash().await, "结构必须一致");
    assert_eq!(db.row_checksums().await,   db2.row_checksums().await,   "数据必须一致");
}
```

**fixture 必须包含**（每一项都对应一个真实 bug）

| Fixture | 对应 issue |
|---|---|
| FK 引用**独立唯一索引**（非 PK） | `#10484` |
| 同名 FK 约束（SQL Server） | `#9729` |
| `NULL` 与 `''` 混合，`NOT NULL DEFAULT ''` | `#10505` |
| `decimal(20,4)` 跨引擎 | `#9667` |
| 超长 ID（19 位）转 int | `#10556` |
| 表达式索引 `((a + b))` | `#9988` |
| 带触发器的表 | `#11837` |
| AUTO_INCREMENT / IDENTITY | `#12124` `#11794` |

### 15.4 大规模元数据测试（L5）

```rust
#[tokio::test]
async fn sidebar_handles_10000_tables() {
    let db = TestPg::new().await;
    db.create_tables(10_000).await;

    let t0 = Instant::now();
    let names = sidebar_depth_name(&db).await;      // 第一层
    assert_eq!(names.len(), 10_000);               // ⭐ 不截断
    assert!(t0.elapsed() < Duration::from_secs(3));

    let t1 = Instant::now();
    let found = search_objects(&db, "订单").await;  // 中文注释搜索
    assert!(!found.is_empty());
    assert!(t1.elapsed() < Duration::from_millis(200));
}
```

### 15.5 真实数据库测试纪律

> 抄 DBX 的一条纪律：**未运行的 live 用例不算通过。**

- `#[ignore]` + 必须的环境变量声明
- README / 文档里禁止把"静态检查通过"描述成"镜像已构建"，禁止把"单测通过"描述成"所有数据库版本已实测"
- 国产库测试在 CI 里显式标记 `flaky` 而不是简单 skip

---

## 16. CI/CD 与发布

### 16.1 流水线

```yaml
# .github/workflows/ci.yml
name: ci
on: [push, pull_request]
jobs:
  guard:       # ⭐ 最快最先跑，失败即停
    runs-on: ubuntu-latest
    steps: { - run: cargo xtask guard }          # 架构守卫，<30s

  rust:
    needs: guard
    strategy: { matrix: { os: [ubuntu, macos, windows] } }
    steps:
      - run: cargo nextest run --workspace --no-fail-fast
      - run: pnpm vitest run
      - run: pnpm typecheck

  integration:
    services: { pg, mysql, redis }
    steps: { - run: cargo nextest run -p adb-it }

  e2e:
    steps: { - run: pnpm playwright test }

  build:
    needs: [rust, e2e]
    steps:
      - run: cargo xtask build --tauri
      - uses: tauri-apps/tauri-action@v0        # 自动生成 Release + 更新
```

**`guard` 单独一个 job 且 `needs` 后续全部任务** —— 让架构违规在 30 秒内失败，而不是等 20 分钟 CI。

### 16.2 分发

| 平台 | 渠道 | 自动化 |
|---|---|---|
| macOS | Homebrew Cask | `brew test` + 自动 bump |
| macOS | 直链 `.dmg` | Release assets，**Notarization** |
| Windows | WinGet | manifest PR |
| Windows | 直链 `.exe` (NSIS) | Authenticode 签名 |
| Linux | AppImage / Flatpak / deb | Flathub |
| npm | `@agenticdb/cli`、`@agenticdb/mcp-server` | pnpm publish |

### 16.3 版本与迁移

```
升级流程（防 L10 资源残留）
  1. 下载新版本到 staging 路径
  2. 停旧进程（SIGTERM → 等 10s → kill）
  3. 运行 schema migration（adb_schema_migrations 表，带 version）
  4. 清理孤儿 Agent 进程（ProcessRegistry）
  5. 原子替换主程序
  6. 失败 → 回滚到旧版本 + 回滚 schema（migration 必须有 down）
```

---

## 16bis. ⭐ 构建 profile 与前端体积预算（v4 新增）

> 抄自 DBX workspace `Cargo.toml` 与 `vite.config.ts`

### 16bis.1 Cargo profile —— 明确的"体积优先"

```toml
# ⭐ 依赖库不需要断点调试，但保留行号表让 backtrace 可读。
#    大幅缩短开发和测试构建时间 + 缩小产物。
[profile.dev.package."*"]
debug = "line-tables-only"

[profile.release]
opt-level = "s"          # ⭐⭐⭐ 体积优先，不是性能优先
lto = true
codegen-units = 1
panic = "abort"
strip = "symbols"
```

> **DBX 用 `opt-level = "s"` 而非常见的 `3`。**
> 这与它"25MB"的传播点完全自洽 —— **数据库工具的性能靠驱动和数据库，不靠本地编译优化**。
> 我们做同样选择。

### 16bis.2 ⭐ Workspace lints —— 把纪律编译进 CI

```toml
[workspace.lints.rust]
unsafe_code = "forbid"                  # ⭐ 数据库工具禁止 unsafe
missing_debug_implementations = "warn"

[workspace.lints.clippy]
indexing_slicing = "deny"     # 禁止裸 [i]，强制 .get()
unwrap_used = "deny"           # ⭐ 库代码不允许 unwrap
todo = "deny"
dbg_macro = "deny"
```

### 16bis.3 Vite manualChunks + chunk 预算

```ts
// ⭐ 抄 DBX 的 chunk 切割
manualChunks(id: string): string | undefined {
  const p = id.replaceAll("\\", "/");
  if (p.includes("/node_modules/ag-grid"))      return "ag-grid";
  if (p.includes("/node_modules/codemirror/"))  return "codemirror";
  if (p.includes("/node_modules/@codemirror/")) return "codemirror";
  if (p.includes("/node_modules/echarts/"))     return echartsChunk(p);
  if (p.includes("/node_modules/reka-ui/"))     return "ui";
  if (p.includes("/node_modules/marked/"))      return "marked";
  return undefined;
}

/** ⭐ echarts 按内部模块再拆 4 块（抄 DBX chunkNameForEchartsModule）*/
function echartsChunk(id: string): string {
  const p = id.split("/node_modules/echarts/").pop() ?? "";
  if (p === "charts.js" || p.startsWith("lib/chart/"))       return "echarts-charts";
  if (p === "components.js" || p.startsWith("lib/component/")) return "echarts-components";
  if (p === "renderers.js" || p.startsWith("lib/renderer/"))   return "echarts-renderers";
  return "echarts-core";
}
```

**为什么这样切**：CodeMirror 只在打开编辑器时需要；reka-ui 是弹层基础库；
echarts 只在点图表时需要；marked 只在 AI 输出时需要。**全部不进首屏。**
只用折线图的用户不需要下载 sankey/map/graph。

**chunk 预算**（CI 门禁 `scripts/check-bundle.ts`）

| chunk | gzip 上限 |
|---|---|
| `index`（首屏） | 800 KB |
| `ag-grid` | 350 KB |
| `codemirror` | 400 KB |
| `echarts`（4 块合计） | 300 KB |
| `ui`（reka-ui） | 120 KB |
| `marked` | 60 KB |

**目标**：首屏 JS 越小，冷启动 ≤ 3s 越容易达到。

### 16bis.4 体积门禁

```text
Rust 二进制  ≤ 18MB
安装包(M1)   ≤ 30MB
安装包(M5)   ≤ 40MB
JDBC jar     ≤  2MB

每阶段 CI：ls -la target/release/bundle/
```

### 16bis.5 ⭐ 三个刻意不做（架构纪律）

```text
1. ⛔ 不 vendor 任何 Tauri/wry 官方库
   DBX vendor 了 8 个（wry/tiberius/rumqttc/ctor/dirs-sys/pageant/
   webview2-com-sys/tauri-plugin-updater），主因是 Win7 兼容性。
   我们的目标用户不需要 Win7。
   ⚠ wry 那个 patch 甚至改了 300+ 行（黑屏恢复 + 剪贴板 + 进程恢复）。

2. ⛔ 不 fork tokio-postgres / mysql_async
   DBX fork 了 3 个 PG crate 锁 commit ac8ad47 支持高斯。
   我们用 adb-driver-postgres/src/variant.rs 的补丁层 → 0 个 fork。

3. ⛔ 不为国产库改上游 crate
   真要改 wire protocol，先在 src/patch/ 用 trait 包装；
   不行才 fork，且必须写 ADR。
   xtask/guard.rs 检查 [patch.crates-io] 非空时是否有对应 ADR 文件。
```

### 16bis.6 ⭐ facade crate 模式（抄 DBX）

```text
adb-drivers  (facade，只 re-export + trait 定义 + 注册表)
   ├── adb-driver-postgres    ⭐ variant.rs 补丁层
   ├── adb-driver-mysql       ⭐ memo 表模拟 OID
   ├── adb-driver-sqlite
   ├── adb-driver-redis
   ├── adb-driver-mongodb
   ├── adb-driver-clickhouse
   ├── adb-driver-jdbc       达梦/金仓/长尾
   └── adb-driver-support    共享工具

adb-sql      (facade)
   ├── adb-sql-core  adb-sql-dialect  adb-sql-data  adb-sql-schema
```

| 问题 | facade 模式的解法 |
|---|---|
| 加一个驱动要改哪些文件？ | **只加一个 crate**，facade 加一行 `pub use` |
| 编译时间太长？ | 每个驱动独立编译单元，可单独 feature gate |
| 调用方依赖混乱？ | 上层只依赖 facade，看不到内部有几种驱动 |
| 只想要 PostgreSQL？ | `default-features=false, features=["driver-postgres"]` |

**注册表区分两种错误**（重要的 UX 细节）

```rust
pub enum RegistryError {
    UnknownConnection(String),   // 声明里没有这个库 → 检查 YAML
    NotCompiled(String),         // 声明里有但没编进来 → 打开 feature
    NoDriver(String),            // 有声明无实现 → 需要写驱动
}
```
> 三种错误的**修法完全不同**，必须分开报。
> 之前我设计的是"跑不起来就报错"，用户根本不知道该改什么。

---

## 17. 架构决策记录 (ADR)

| # | 决策 | 状态 | 理由 |
|---|---|---|---|
| 001 | Tauri 2 + Rust 内核 | ✅ 采纳 | 体积是唯一传播点 |
| 002 | 元数据全部以 OID 为键 | ✅ 采纳 | L3，消除一类 bug 根源 |
| 003 | **不做 Go Agent 层，只做 JDBC 通道** | ✅ 采纳 | ⭐ 10% 工作量覆盖 80% 库 |
| 004 | 凭据加解密唯一实现，MCP 必须链接 `adb-secret` | ✅ 采纳 | L1，5 个 issue 的教训 |
| 005 | `CommandSpec` 自动生成 5 种形态 | ✅ 采纳 | 消除重复 + L2 根治 |
| 006 | 不自研 Grid（用 AG Grid Community） | ✅ 采纳 | DBX 自研 Canvas 代价 123 文件 |
| 007 | `readOnlyHint` 由 `Danger` 自动生成 | ✅ 采纳 | L2 |
| 008 | 危险操作强制自动快照 | ✅ 采纳 | 无快照不算已执行 |
| 009 | 超时不自动重放写操作 | ✅ 采纳 | 铁律 |
| 010 | 元数据树懒加载 + 本地 FTS 索引 | ✅ 采纳 | L5 |
| 011 | ~~CLI/MCP 先行，UI 后置~~ **已推翻** | ❌ 作废 | 分发型产品第一定律是用户能立刻看到东西；DBX 的 27k star 是 GUI 拿的 |
| 012 | 连接空闲自动回收 + 心跳 | ✅ 采纳 | L8 |
| 013 | Linux Keyring 走 `spawn_blocking` | ✅ 采纳 | 防 zbus panic |
| 014 | 不 vendor 上游 Tauri/wry 库 | ✅ 采纳 | 避免升级地狱（DBX vendor 了 8 个） |
| 015 | 前端逻辑全在 `lib/` 纯函数 | ✅ 采纳 | 可测性 |
| 016 | 插件市场延后到 v4 | ✅ 采纳 | 过早 |
| 017 | 元数据以磁盘快照为中心 | ✅ 采纳 | 离线优先、秒开 |
| 018 | 语义知识 `.adbctx` 进 git | ✅ 采纳 | 护城河 |
| **019** | **⭐ facade + per-driver crate 模式（24 crate）** | ✅ 采纳 | 抄 DBX；独立编译单元 + feature gate |
| **020** | **⭐ 连接类型改为 YAML 声明 + 能力矩阵全显式** | ✅ 采纳 | 抄 DBX；改 YAML 产生 diff，review 可见 |
| **021** | **⭐ `traits` 独立于 `capabilities`（驱动 UI 提示）** | ✅ 采纳 | 抄 DBX；`transactionalDdl` 等直接驱动 UI |
| **022** | **⭐ 传输家族 `transferObjectFamily`** | ✅ 采纳 | 抄 DBX；6 个 Oracle 兼容库共用一套规则 |
| **023** | **⭐ codegen 五道保险闭环** | ✅ 采纳 | 抄 DBX；CI 只读校验防陈旧产物 |
| **024** | **⭐ `opt-level="s"` / `line-tables-only`** | ✅ 采纳 | 抄 DBX；明确选体积而非速度 |
| **025** | **⭐ Vite manualChunks + chunk 预算** | ✅ 采纳 | 抄 DBX；首屏 JS 决定 3s 冷启动 |
| **026** | **⭐ 国产库走 `variant.rs` 补丁层，不 fork** | ✅ 采纳 | 改进 DBX（它 fork 了 3 个 crate 锁 commit） |
| **027** | **⭐ workspace lints 把纪律编译进 CI** | ✅ 采纳 | `unsafe=forbid` / `unwrap_used=deny` |
| **028** | **⭐ `[patch.crates-io]` 保持为空 + ADR 守卫** | ✅ 采纳 | 防止无声 fork 上游 |

---

## 18. 编码规范

### 18.1 Rust

```rust
// 1. 错误必须携带足够信息（SQL 错误必带位置）
// 2. 所有 async 取消点必须是幂等的
// 3. 禁止在 async 上下文调用阻塞 API
//    ❌ keyring.get_secret()  // 阻塞 D-Bus
//    ✅ spawn_blocking(|| keyring.get_secret()).await?
// 4. 所有子进程必须登记 ProcessRegistry
// 5. 禁止 panic 在库代码（只有 main / 测试可 panic）
// 6. 所有公开 API 必须写 doc comment（含 # Errors / # Safety）
```

### 18.2 TypeScript

```ts
// 1. 严格模式，禁止 any
// 2. 组件不含业务逻辑
// 3. 所有 API 调用经过 lib/command/ 的包装（自动带权限与错误处理）
// 4. 不用 Vue 深度响应式存大数组（10万行会卡）→ 用 shallowRef + markRaw
// 5. 长列表一律虚拟滚动
```

### 18.3 必须写进 `CONTRIBUTING.md` 的 5 条

1. **新增命令必须用 `CommandRegistry`**，不许直接加 `#[tauri::command]`
2. **新增数据库类型优先写 YAML**，只有在 YAML 表达不了才写驱动
3. **SQL 错误必须填 `position`**
4. **写操作的重试必须显式判断 `danger`**
5. **凭据只能通过 `adb-secret` 访问**

---

## 19. 已知风险与技术债预案

| 风险 | 概率 | 影响 | 预案 |
|---|---|---|---|
| JDBC 需要 JRE | 高 | 中 | 内置 jlink 运行时（~45MB 单独可选包）；驱动商店一键装完整离线包（`#9538` 教训：解压失败不能中断整包导入） |
| 中文脱敏误伤 | 高 | 高 | 规则预览 + 逐条确认 + 自定义；默认宁可多脱；提供"仅脱敏已确认字段"开关 |
| **生产库事故** | 中 | **致命** | 9-10 章的全部机制；默认 dry-run；审计不可关；**超时绝不重放** |
| DBX 快速跟进 | 高 | 高 | 不打"支持更多库"的仗，打垂直深度 |
| 社区起不来 | 中 | 高 | Phase 0 的 20 人 Gate；Agent-first 分发绕过冷启动 |
| Tauri 升级 | 中 | 中 | 不 vendor；锁版本；CI 三平台 |
| 跨平台 JDBC jar 差异 | 中 | 中 | 驱动商店做三平台真机测试；jar 完整性校验 |
| **Linux 桌面市场份额** | 中 | 中 | Flatpak + AppImage + 三平台 CI；不承诺 |
| Oracle / SQL Server 协议复杂度 | 中 | 中 | 用官方 Rust crate（tiberius）；不自己实现 TDS |
| MCP 凭据路径分叉 | 低 | 高 | 架构门禁 L1 + CI 测试锁死 |

---

## 附录 A：核心代码索引

| 关注点 | 文件 |
|---|---|
| 架构守卫 | `xtask/src/architecture.rs` |
| Command 定义 | `crates/adb-registry/src/spec.rs` |
| 驱动 trait | `crates/adb-drivers/src/lib.rs` |
| JDBC 通道 | `crates/adb-drivers/src/jdbc/channel.rs` |
| JDBC Agent | `apps/agents/jdbc/src/main/java/.../MultiSessionServer.java` |
| 元数据 | `crates/adb-drivers/src/meta/` |
| 语义层 | `crates/adb-core/src/knowledge/` |
| 变更引擎 | `crates/adb-change/src/{model,impact,rollback,approval}.rs` |
| 脱敏引擎 | `crates/adb-mask/src/{rule,consistency,engine}.rs` |
| 诊断引擎 | `crates/adb-diag/src/{static_check,plan,type_consistency}.rs` |
| MCP | `crates/adb-mcp/src/{tools,policy}.rs` |
| 凭据 | `crates/adb-secret/src/lib.rs` |
| 审计 | `crates/adb-audit/src/lib.rs` |

## 附录 B：DBX 值得读的 3 个文件

1. `crates/ARCHITECTURE.md` —— 分层约束与所有权矩阵
2. `agents/docs/agent-protocol-v2.md` —— Agent 协议与结构化错误契约
3. `plugins/README.md` —— 信任模型（人工审核 / SHA-256 / 签名 三者职责分离）

---

*文档完*
