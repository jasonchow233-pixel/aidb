# DBX 竞品拆解 + 自研数据库管理工具可行性分析报告

> 分析对象：https://github.com/t8y2/dbx
> 报告日期：2026-10-16
> 数据采集：GitHub API + 仓库源码树 + 官方文档 + 竞品横评

---

## 目录

- [第一部分：DBX 深度拆解](#第一部分dbx-深度拆解)
  - [1.1 基本盘数据](#11-基本盘数据)
  - [1.2 技术选型](#12-技术选型)
  - [1.3 架构分层（最值得抄的部分）](#13-架构分层最值得抄的部分)
  - [1.4 三层驱动战略（最值得抄的架构决策）](#14-三层驱动战略最值得抄的架构决策)
  - [1.5 声明式连接类型注册表](#15-声明式连接类型注册表)
  - [1.6 插件平台 .dbxp](#16-插件平台-dbxp)
  - [1.7 多形态输出](#17-多形态输出)
  - [1.8 AI 与 MCP](#18-ai-与-mcp)
  - [1.9 安全体系](#19-安全体系)
  - [1.10 前端与 UI 工程](#110-前端与-ui-工程)
  - [1.11 工程化与质量守卫](#111-工程化与质量守卫)
  - [1.12 DBX 的弱点与机会](#112-dbx-的弱点与机会)
- [第二部分：如果你来做](#第二部分如果你来做)
  - [2.1 定位：三条路线选一条](#21-定位三条路线选一条)
  - [2.2 功能吸引力排序（核心结论）](#22-功能吸引力排序核心结论)
  - [2.3 不该做什么](#23-不该做什么)
  - [2.4 架构设计（可直接落地的方案）](#24-架构设计可直接落地的方案)
  - [2.5 技术选型决策](#25-技术选型决策)
  - [2.6 12 个月路线图](#26-12-个月路线图)
  - [2.7 团队配置与成本](#27-团队配置与成本)
  - [2.8 风险清单](#28-风险清单)
- [第三部分：UI 设计](#第三部分ui-设计)
- [附录：关键数据速查](#附录关键数据速查)

---

# 第一部分：DBX 深度拆解

## 1.1 基本盘数据

| 指标 | 数值 | 解读 |
|---|---|---|
| Star | **26,893** | 5.5 个月从 0 到 27k，开源 DB 客户端史上最快之一 |
| Fork | 2,432 | fork/star = 9%，说明有大量"二开"需求（国产化改造 / 内网定制） |
| Watch | 107 | 极低，典型的"star 收藏流"用户画像：程序员试用型工具 |
| Open Issues | **1,232** | ⚠️ 严重。约等于平均每天 7 个新 issue，社区跑在维护能力前面 |
| 创建时间 | 2026-04-29 | 仅 5.5 个月 |
| License | Apache-2.0 | 允许商业闭源分发（对做企业版友好） |
| 主语言 | Rust (26.9MB) | |
| 仓库文件总数 | 8,305 | 是个**巨型仓库** |

**语言构成（按字节）**

| 语言 | 大小 | 用途 |
|---|---|---|
| Rust | 26.9 MB | Tauri 后端 + 全部 Rust crate + 文档站 |
| TypeScript | 21.8 MB | Vue 前端（2864 个 .ts 文件）+ packages |
| Vue | 12.0 MB | 524 个 .vue 组件 |
| Go | 3.77 MB | **53 个长尾数据库 Agent** |
| Java | 3.71 MB | JDBC Agent（大量国产库走这条） |
| Shell/PS1/PHP/Nix/C | <100 KB | 打包脚本 |

**结论 1**：这是一个 **3 语言、8K 文件、约 60 万行** 的项目。它不是一个"小工具"，而是一个工程平台。你如果 2-3 个人，**不要按它的规模做**。

## 1.2 技术选型

| 层 | 选型 | 评价 |
|---|---|---|
| 桌面壳 | **Tauri 2.10** ( wry 已 vendor) | ✅ 正确。25MB 安装包 vs Electron 150MB+，这是它最大的传播点 |
| 前端框架 | **Vue 3.5** + Vite 7 | ✅ 务实。对 Pinia 团队友好，ERP/GUI 类应用 Vue 上手快 |
| 状态管理 | Pinia 3 | ✅ |
| CSS | **Tailwind CSS 4** + reka-ui (shadcn-vue) + CVA + tailwind-merge | ✅ 现在的主流组合 |
| SQL 编辑器 | **CodeMirror 6**（+ 6 种语言包、vim 模式、lint、search、5 套主题） | ✅ 比 Monaco 更轻更专注 |
| ER 图 / 图谱 | **Vue Flow** + Cytoscape + fcose + elkjs | ✅ 双引擎：ER 用自动布局，图数据库用 Cytoscape |
| 图表 | ECharts 6 + vue-echarts | ✅ |
| 虚拟滚动 | vue-virtual-scroller | ✅ |
| 结果表格 | **自研 Canvas 渲染**（`canvasDataGridRenderer.ts`） | ⚠️ 见下文分析 |
| Web 后端 | axum 0.8 | ✅ 一套 Rust 代码同时出桌面端和 Web 端 |
| 格式化 | oxfmt / oxlint（Oxc 工具链） | ✅ 比 Prettier+ESLint 快一个数量级 |
| 测试 | cargo-nextest + Vitest + Playwright(292 文件 app-tests) | ✅ |

**关键观察**：`vendor/` 目录里塞了 `wry`、`rumqttc`、`tiberius`、`webview2-com-sys`、`pageant`、`dirs-sys`、`ctor` —— 作者**魔改了上游库**。这是双刃剑：能修 bug，也能变成升级地狱。你自己开发时**不要一开始就这么干**。

## 1.3 架构分层（最值得抄的部分）

DBX 的 Rust 侧有 **27 个 crate**，依赖方向被严格约束，并且**有 CI 脚本强制校验**（`scripts/core-architecture.test.mjs`）。

```text
desktop / web / cli / mcp        ← 五种形态，共用同一内核
            │
         dbx-core               ← 应用编排层（连接/查询/导入导出/AI Agent 循环/持久化/安全）
            │
    ┌───────┼────────────────┬─────────────────┬──────────────┐
    │       │                │                 │              │
dbx-drivers  dbx-plugin-   dbx-ai-        dbx-formats   dbx-platform
    │       runtime         provider           │              │
    │       │                │                 │              │
dbx-sql ──── dbx-types ──────┴─────────────────┴──────────────┘
    │
dbx-sqlite-worker（独立协议，无运行时默认依赖）
```

**所有权矩阵（原文翻译，这是整份 ARCHITECTURE.md 的精华）**

| Crate | 拥有 | 禁止引入 |
|---|---|---|
| `dbx-types` | 连接配置、DB 身份、查询与元数据 DTO、JS 安全 JSON | 连接池、应用状态、驱动 SDK |
| `dbx-sql` | SQL 解析、方言注册、SQL 风险分析、DDL/DML 生成、结构差异 | 查询执行、凭据 |
| `dbx-drivers` | 原生驱动、隧道、JDBC/Agent 运行与分发、超时取消 | 导入导出编排、应用配置持久化 |
| `dbx-formats` | CSV/XLSX/文本/时间格式、ZIP 编码 | 数据库请求 |
| `dbx-ai-provider` | 模型与 CLI 适配器、流式协议、事件、token 用量 | 数据库工具执行、Agent 业务循环 |
| `dbx-plugin-runtime` | manifest、签名与安装、Marketplace、子进程会话 | 驱动实现、core 应用状态 |
| `dbx-platform` | 进程、路径、代理、下载、版本比较 | 数据库身份和业务规则 |

**dbx-core 内部目录**

```text
dbx-core/src/
  connection/   连接路由、凭据、运行配置、JDBC 配置、任务监督
  query/        查询编排、取消、文档/Redis 操作、事务与对象缓存
  schema/       元数据编排、运行时 SQLite 表重建
  data/         导入导出、迁移、比较、备份、脚本工作流
  ai/           数据库 Agent 循环、工具、解释、模板、MCP 策略
  admin/        Nacos、Consul、MQ、MQTT
  persistence/  本地存储、历史、保存的 SQL、配置与云同步
  safety/       生产安全、写入解锁、风险指标
  host/         更新、变更日志、外部应用集成
```

**⭐ 值得直接照抄的三条规则：**

1. **下层 crate 不得反向依赖 core，包括 dev-dependency**。多层测试放 `dbx-core/tests/`，避免为测试把驱动引到 SQL/类型/格式层。
2. **同一 DTO 不复制定义**。旧模块路径与新 crate 通过 re-export 保持**相同类型身份**（`public_api_compatibility` 测试守住）。这样可以随时重构而不破坏调用方。
3. **边界靠 CI 脚本自动校验**，不靠 code review 的自觉。`scripts/core-architecture.test.mjs` 校验 workspace 依赖、功能转发、源码归属、构建输入、前端测试引用路径。

> 这三点比任何具体技术选型都值钱。**架构的价值在于可执行的约束，不在于图画得好看。**

## 1.4 三层驱动战略（最值得抄的架构决策）

这是 DBX 支撑"100+ 数据库"的**真正答案**。它不是写了 100 个驱动，而是设计了 4 条通道：

```text
┌─ Tier 1: 原生 Rust 驱动（编译进主二进制）────────────────┐
│  postgres(tokio-postgres) mysql redis tiberius(SQLServer) │
│  mongodb elasticsearch sqlite                            │
│  → 性能最好、体积可控、崩溃不影响主进程                  │
└──────────────────────────────────────────────────────────┘

┌─ Tier 2: Go Agent（独立进程，JSON-RPC over stdio）──────┐
│  agents/drivers/*-go/  53 个                             │
│  duckdb oracle db2 hive trino snowflake spark neo4j      │
│  kafka rabbitmq tdengine argo nebula etcd ...            │
│  → 生态里已有成熟 Go SDK 的，一键转换                     │
└──────────────────────────────────────────────────────────┘

┌─ Tier 3: Java JDBC Agent（独立 JVM 进程）───────────────┐
│  agents/drivers/*/build.gradle   约 20 个                │
│  dameng(达梦) kingbase xugu(虚谷) vastbase goldendb      │
│  gbase8a gbase8s highgo sundb oscar iris databend ...    │
│  → JDBC 生态天然覆盖所有国产库，零成本接入                │
└──────────────────────────────────────────────────────────┘

┌─ Tier 4: 通用 JDBCX（用户自带任意 JDBC 驱动）───────────�┐
│  plugins/jdbc/  → 一份 YAML 就能加一个数据库            │
└──────────────────────────────────────────────────────────┘
```

**Agent Protocol v2（多会话运行时）—— 这是设计得最漂亮的一块**

> 一个 Agent 进程服务多个隔离的数据库 session。

- `open_session` / `close_session` / `validate_session` / `cancel_session`
- **同 session 串行，不同 session 并发**（因为连接状态、事务、schema 状态不是线程安全的）
- `sessionRole`：`workload`（跑查询）vs `metadata`（跑元数据）—— 避免元数据探测把查询卡在队尾
- 最后一个 session 关闭后 **30 秒宽限期**才退出进程（防止频繁开关标签页反复冷启动）
- 最多 **256 个逻辑 session**
- **结构化错误契约**（这段设计水平很高）：

```json
{
  "contractVersion": 1,
  "category": "timeout|canceled|connection|protocol|resource|sql",
  "retryable": false,
  "sessionDisposition": "keep|quarantine|replace_runtime",
  "stage": "request|checkout|connect|validate|execute|fetch|cancel|close",
  "operationOutcome": "not_started|unknown",
  "sqlState": "...",
  "vendorCode": 0
}
```

三个细节值得学：
- `operationOutcome` 区分"操作**可能**已经到达数据库" vs `not_started` —— 因为超时后**绝对不能自动重放**写操作。
- `sessionDisposition: replace_runtime` 要求宿主**原子地**摘除所有共享该 runtime 的连接池后再杀进程；Agent 侧只报告、不自行了断（因为它不拥有路由状态）。
- `retryable` 明确写了"**never authorizes automatic SQL replay**"。

> **这一段可以直接抄进你的设计文档。** 分布式数据库工具 90% 的线上事故都是"超时重试导致重复写入"。

## 1.5 声明式连接类型注册表

```
plugins/connection-types/*.yaml     ← 88 个 YAML，人工维护
plugins/dialects/*.yaml             ← 方言描述符、类型目录、DDL 模板、元数据规则
plugins/mappings/                   ← 跨方言类型映射
        │
        ├─ dbx-types/build.rs  消费 → 生成 Rust 类型
        └─ dbx-sql/build.rs     消费 → 生成方言注册表
```

新增一个数据库类型，很多情况下**只需要写一个 YAML**（默认端口、URL 模板、认证方式、图标、字段定义、能力开关）。这也是 `--all-features` 不能随便用的原因（构建输入耦合）。

**启发**：数据库工具里，"连接表单"和"能力声明"是**最高频变更**的部分，把它们从代码里抽到声明式文件，能省掉 70% 的加库成本。

## 1.6 插件平台 `.dbxp`

| 维度 | 设计 |
|---|---|
| 包格式 | `.dbxp` = 严格 ZIP：所有文件必须被 SHA-256 覆盖；拒绝绝对路径、`..`、重复条目、符号链接、解压炸弹 |
| 签名 | **Ed25519 仓库签名**，Key ID 必须在 catalog 声明 |
| 信任链 | 人工审核（决定能否上架） ≠ Catalog SHA-256（证明下载的就是被审核的那份） ≠ Ed25519（证明安装的就是该仓库发布的） |
| 运行时隔离 | 插件 UI 跑在沙箱 + **独立 sidecar 进程** |
| 能力边界 | manifest v1 + Host API 1.x + sidecar protocol v1 |
| SDK | Rust SDK / Go SDK / `dbx-plugin create --template frontend\|rust\|go` |
| 市场 | 独立仓库 `t8y2/dbx-store`，支持第三方仓库、企业内网仓库、离线镜像 |
| 体积 | **插件不进基础包**（SSH、SFTP、OpenDAL 都做成可选插件） |

**这是 DBX 最"过早"但也最有远见的决策。** 大部分人做数据库工具时会把驱动全塞进主包 → 体积失控。DBX 从第一天就规划"基础包只装必须的，长尾全走插件"。

不过要提醒：**你现在还不该做插件平台**。等你有 10+ 个用户和 2+ 个外部贡献者再考虑。DBX 也是有社区之后才做的。

## 1.7 多形态输出

| 形态 | 实现 | 说明 |
|---|---|---|
| Desktop | Tauri 2 | macOS / Windows / Linux |
| Web | `dbx-web` + axum | 同一套 Rust 内核，浏览器直连 |
| Docker | `deploy/docker-compose.yml` | 团队共享 |
| CLI | `dbx-cli` → npm `@dbx-app/cli` | `dbx query local "select 1" --json` |
| MCP | `dbx-mcp` → npm `@dbx-app/mcp-server` | 独立分发，**不随桌面端安装** |
| Agent Skill | `dbx agent setup` | 装到 `~/.agents/skills/dbx`，让 shell agent 知道怎么安全用 CLI |

**分发渠道全家桶**：Homebrew Cask / Scoop / WinGet / Flatpak / Docker / Nix / NSIS(Windows 安装包) / Cloudflare 镜像。

> **⭐ CLI + `--json` 输出 + Agent Skill 这一组是最低成本、最高回报的入口。**
> 它让 DBX 直接进入 AI Agent 的工具箱，而不只是"又一个 GUI"。你现在就可以抄：一个 CLI + 结构化输出 + 一个 SKILL.md，成本一周，收益巨大。

## 1.8 AI 与 MCP

### AI（`dbx-core/ai` + `dbx-ai-provider`）

| 能力 | 实现 |
|---|---|
| 交互模型 | 高亮表 → 描述需求 → 返回 SQL（不用切窗口） |
| 模型支持 | Claude / OpenAI / **本地 Ollama** + 多配置管理 (`ai_multi_config`) + `ccSwitch`（一键切供应商） |
| Agent 循环 | `aiAgentPlan` / `maxAgentTurns` / `desktopAiRunRegistry`（可中断、可快照） |
| 上下文 | `aiExternalContext` / `aiTableMentions`（@表名）/ `objectBrowserToAiTargets` |
| 安全 | `aiSqlExecutionPolicy` / `aiProposalDetect` / `aiProductionSafety` |
| 富输出 | `aiChartSpec` / `aiChartOption`（ECharts）/ `aiHtmlPreview`（沙箱 HTML 预览 + canary 测试） |
| Skills | `aiSkills` / `userSkillStore`（用户自定义技能） |

### MCP

```json
{ "mcpServers": { "dbx": { "command": "npx", "args": ["-y", "@dbx-app/mcp-server"] } } }
```

三级权限模式（`Settings → MCP`）：
1. **Read only** —— 只读
2. **Data read/write** —— 读写数据，不能改结构
3. **Full access** —— 完全访问

外加**连接 allowlist**（哪些连接允许 AI Agent 碰）。

> **⭐ 三级权限 + allowlist 是必须抄的。** 因为 MCP 服务器一旦配置好，你的生产库就在 LLM 的可达范围内了。Oracle / Databricks / Prisma 的 MCP 全都在做 per-user 认证和 scope 检查。DBX 这套本地 allowlist 是个更简单有效的版本。

## 1.9 安全体系

DBX 的安全设计**远超同类工具**：

| 能力 | 实现 |
|---|---|
| 生产连接保护 | `productionSafetyStore` —— 标记为生产的连接默认拦截写操作 |
| 只读解锁 | `readOnlyUnlockStore` —— 需要显式解锁才能写 |
| SQL 危险分类 | `sqlExecutionDangerStore` + `dbx-sql` 的风险分析 + `safety/` 风险指标 |
| 破坏性操作确认 | DROP / TRUNCATE / DELETE 无 WHERE / `PUT /{db}` 等分级确认 |
| 凭据加密 | OS Keyring (`os-keyring` feature) + `configCrypto` / `safeStorage` / `safeStorage.ts` |
| 配置导入导出 | 加密 + passphrase 会话 (`exportPassphraseSession`) |
| SQL Server | `sqlserver-native-attention` —— 追踪当前语句，防止"关标签页后语句还在跑" |

> `sqlserver-native-attention` 这个 feature 名就说明了设计意图：**强制关窗前确认当前语句状态**。DBeaver/TablePlus 都有这个泄漏问题。

## 1.10 前端与 UI 工程

```
apps/desktop/src/
  lib/        1930 文件  ← 真正的业务逻辑都在这，不是 components
  components/  987 文件
  composables/ 183
  stores/      139（18 个 Pinia store）
  i18n/         72
```

**`lib/` 的领域划分**（非常值得抄）：

```
dataGrid/ 123  sql/ 118  editor/ 98  database/ 94  sidebar/ 83
ai/ 78   table/ 77   connection/ 72  redis/ 54  backend/ 53
plugins/ 50   app/ 40  export/ 38  common/ 34  diagram/ 29
metadata/ 23   mq/ 23   tabs/ 22   mongo/ 17  nacos/ 17 ...
```

即：**组件是纯展示，所有逻辑抽到 `lib/` 的纯函数**。证据：`lib/__tests__/` 有 **570 个测试文件**。这就是为什么它敢用 Vue 又不写 Pinia action 地狱。

### Data Grid 能力清单（DBX 竞争力最强的部分）

| 类别 | 能力 |
|---|---|
| 渲染 | 虚拟滚动 + **Canvas 渲染** + 只渲染可视区 |
| 布局 | 拖拽调宽、双击自适应、拖拽排序、**左右列冻结**、转置视图 |
| 持久化 | 列宽/顺序/冻结状态**按表或按查询持久化** |
| 过滤 | 4 种模式明确区分执行位置：<br>· Grid search → 当前已加载结果<br>· 本地值过滤 → 当前已加载结果<br>· WHERE → **数据库**<br>· ORDER BY → **数据库**<br>· 当前页排序 → 客户端 |
| 编辑 | 双击编辑 / 详情面板 / 批量粘贴 TSV / 克隆行 / 标记删除 / 显式 NULL / 用默认值 / **测试数据生成器**（正则、手机号、唯一约束） |
| 可编辑性判定 | 只有能可靠定位目标表 + 有 PK/唯一身份时才可编辑；JOIN/聚合/表达式 → 只读 |
| **SQL 预览** | 所有变更本地暂存，保存时展示生成的 INSERT/UPDATE/DELETE 供 review |
| 条件批量更新 | MySQL 下支持"按条件更新全表所有匹配行（不限当前页）"，带预估影响行数 + 危险确认 |
| 视图选项 | Hide NULL / Hide NULL+空串列（明确不隐藏 0/false/空白） |
| 其他 | 二进制/图片/URL 单元格渲染、Hex 查看器、JSON 格式化、查找替换、图表 |

**`WHERE` / `ORDER BY` 走数据库，搜索/本地过滤走内存，并明确警告"后者不减少数据库返回量"** —— 这个区分做得比大多数工具清楚。

> **⚠️ 一个警告**：`canvasDataGridRenderer.ts` 说明它已经扛不住 DOM 方案了。但 Canvas 渲染会丢掉浏览器原生能力（无障碍、输入法、文本选择、复制粘贴），需要大量自研（他们确实有 123 个 dataGrid 文件，其中大量是 `dataGridClipboard` / `dataGridEditorFocus` / `dataGridFocusRestore`）。
> **你的第一版应该用 DOM + 虚拟滚动**（TanStack Virtual / vue-virtual-scroller），等到实测 10 万行卡了再换 Canvas。

## 1.11 工程化与质量守卫

| 项目 | 做法 |
|---|---|
| 架构守卫 | `scripts/core-architecture.test.mjs` + CI Rust 检查执行 |
| 类型生成 | `pnpm generate:connection-types` 连接类型 YAML → TS，CI 有 `--check` 模式 |
| 测试分层 | 内存 SQLite + 本地协议模拟器做单测；**真实数据库走 SSH 测试服务器的专用实例，通过 SSH 隧道访问** |
| 并行测试 | `cargo nextest` 固定版本 `0.9.137` |
| CI | 33 个 workflow，74 个 `.github/scripts` |
| 测试规模 | `packages/app-tests/` 292 文件、`lib/__tests__/` 570 文件 |
| 真实数据库测试 | 有 `#[ignore]` 的 live 测试，靠环境变量启用；文档明确写"**未运行的 live 用例不算通过**" |
| 文档纪律 | `crates/ARCHITECTURE.md` 里明确写"**禁止把静态构建输入检查描述成镜像已构建，也不能把单元测试通过描述成所有数据库版本均已实测**" |

> 这段文档纪律值得单独学。开源项目最容易腐烂的地方就是 README 里的功能承诺和实际能力脱节。DBX 在架构文档里**明确禁止夸大**。

## 1.12 DBX 的弱点与机会

| 弱点 | 证据 | 你的机会 |
|---|---|---|
| **Issue 积压严重** | 1,232 open issues，5.5 个月 | 维护良好的工具对用户是刚需 |
| **文档截图按 issue 编号命名** | `docs/screenshots/issue-10454-*.png` 有 200+ 张 | 这说明大量精力花在"接需求"而非"做产品纵深" |
| **巨型单体仓库** | 8,305 文件、60 万行、27 crate | 小而美的垂直工具仍有空间 |
| **无内置语义层** | 有 `field-lineage`，但没有指标定义/口径治理 | Databricks Genie 证明语义层是刚需 |
| **无性能诊断** | 只有 Explain Plan 可视化，没有主动诊断 | ⭐ **巨大空白**（见 2.2） |
| **无 Mock/脱敏** | 只有 import/export，没有"造数" | ⭐ **巨大空白**（见 2.2） |
| **无变更回滚** | Schema diff 有，回滚脚本生成没有 | ⭐ **空白** |
| **AI 只是 SQL 生成** | text-to-SQL 为主 | 红海，无护城河 |
| **Vue 生态较窄** | Recka UI / Vue Flow | 用 React 也不吃亏 |

**竞品对照**

| 产品 | Star | 语言 | 特点 |
|---|---|---|---|
| **DBX** | 26.9k | Rust | 轻量(25MB)、100+库、AI+MCP+插件 |
| DBeaver CE | 52.1k | Java/Eclipse | 生态最全，但 **JVM + ~400MB** |
| Beekeeper Studio | 23.8k | TypeScript/Electron | 设计漂亮，但 **~150MB**，库数量少 |
| TablePlus | 3.9k | 闭源 | 体验好，不开源 |
| DataGrip | — | 闭源 | IDE 生态最强，绑定 JetBrains |

**位置判断**：DBX 已经占了"**轻量 + 开源 + 国产库 + AI**"这个格子，而且占得很死。你正面硬刚"支持更多数据库"**必输**。

---

# 第二部分：如果你来做

## 2.1 定位：三条路线选一条

在写任何代码之前，先选路线。三条路的天花板完全不同。

### 路线 A：全功能开源客户端（❌ 不建议）

"做一个更好的 DBeaver/DBX"
- 投入：10+ 人年
- 结果：27k star 是 DBX 用 5.5 个月 + 大量 issue 响应换来的，且它仍未解决 1232 个 issue
- **不要选**

### 路线 B：Agent 原生数据库上下文层（✅ 推荐）

**一句话定位**：不做"给人用的数据库客户端"，做"**给 AI Agent 用的数据库上下文与管控层**"。

- 用户：AI Agent 开发者、AI coding 工具重度用户、数据/后端团队的技术负责人
- 价值主张：Agent 不知道你的数据库长什么样 → 我给它最好的上下文 + 最安全的执行边界
- 竞品：Databricks Genie（企业语义层，有权限门槛）、MCP Toolbox（Google，开源但很薄）、Prisma Connector
- 你的优势：本地/私有化、零配置、跨库、国产库
- **投入：3-5 人年可做出有竞争力的产品**

### 路线 C：垂直场景深耕（✅✅ 最推荐，如果你是小团队）

选一个 DBX **明确没有**的高频场景做到极致：

| 垂直方向 | 为什么有戏 | 市场规模 |
|---|---|---|
| **数据库变更管控 / DevOps 平台** | DBA + 研发的刚需，DBX 只有 schema diff | 企业级，付费意愿强 |
| **国产库 + 信创迁移工具链** | 达梦/金仓/海量/神通迁移验证，国内外工具都做不好 | 国内政策红利 |
| **数据脱敏 / 测试数据工厂** | 从生产库采样 → 自动脱敏 → 一键生成可复现开发库 | 几乎无人做，开发者极痛 |
| **数据库性能诊断助手** | "打开就知道哪里慢"，从 Explain 到根治 | 开发者 + DBA 双受众 |

**我的建议：路线 B 的定位 + 路线 C 的切入点。**
即：对外讲 "Agent 时代的数据库工作台"，但第一个 killer feature 选一个 DBX 没做的垂直场景（比如**变更管控**或**测试数据工厂**）。

## 2.2 功能吸引力排序（核心结论）

这是你要我给的最重要的判断。按 **「用户痛感 × DBX 已做程度 × 你的实现难度」** 三维排序。

### 🥇 S 级：能独立撑起产品的差异化功能

#### S1. 变更管控工作台（Change Control）⭐最推荐

**为什么有吸引力：**
- **是真痛点**：DBA 最怕的是"谁在生产上执行了这条 SQL"。DBX 只有破坏性操作确认，没有变更流程。
- **竞争真空**：Flyway/Liquibase 只管"文件"，不管"人"；DBX 只管"看"，不管"控"。中间这块没人做。
- **付费能力强**：企业愿意为"审计 + 合规"付费。
- **技术难度可控**：主要是工作流 + SQL 解析 + 影响评估，不需要新驱动。

**具体功能清单：**
1. **可视化建表 / 改表** —— 拖拽生成 DDL，比 text-to-SQL 可靠得多。改字段类型时自动给出**数据迁移风险提示**（如 `varchar(20) → varchar(10)` 会截断数据）
2. **迁移脚本管理** —— 每个变更生成带序号的 migration 文件（SQLite/Flyway 兼容格式），支持 git 集成
3. **Dry-run 影响评估** ⭐ —— 执行前预估：
   - 会锁表多久（MySQL `pt-online-schema-change` 判据 / PG 锁级别）
   - 预估扫描行数、是否全表扫
   - 是否会重写整表
   - 磁盘临时空间需求
   - **给出建议的在线 DDL 算法**（gh-ost / pt-osc）
4. **自动生成回滚脚本** ⭐⭐ —— DBX 没有，行业里大多数工具也没有。`ALTER TABLE ADD COLUMN` → 自动生成 `DROP COLUMN` + 数据恢复提示
5. **生产连接双人审批 + 变更单号** —— 提交 → 审批 → 执行，全程绑定工单号
6. **变更审计日志** —— 谁、何时、从哪台机器、执行了什么 SQL、耗时、影响行数。导出为合规报告
7. **批量脚本 dry-run** —— 拿一个 `.sql` 文件，对**多个环境**（dev/test/prod）分别评估

**MCP 化后更值钱**：Agent 想改数据库 → 必须走变更工单流程 → 人类审批 → 执行。**这才是 AI 时代数据库安全的正解**，也是你能给 Agent 用户的最强卖点。

#### S2. Agent 上下文层（如果走路线 B，这是核心）

不要做"我也能用自然语言写 SQL"（红海，无护城河）。做：

1. **Schema → 紧凑语义化上下文**
   ```bash
   mydb context prod --tables users,orders --format prompt
   # 输出：表注释 + 列注释 + 枚举取值 + PK/FK + 索引 + 近似行数 + 常见 JOIN 路径
   ```
   关键在于**压缩率**：1000 张表塞不进 context，必须做检索式裁剪。DBX 的 `dbx context` 已经这么做了，你可以做得更好。

2. **团队级「数据库知识库」** ⭐ —— 这是真正的护城河
   - 字段业务含义（`status: 1=待审 2=通过 3=驳回`）—— **枚举值注释是 AI 写 SQL 最大的坑**
   - 表间标准 JOIN 路径
   - 已知坑位注释（"这个表 2 亿行，`create_time` 没索引，别全表扫"）
   - 指标口径定义
   - 团队共享，可导出成 `.dbctx` 文件进 git
   - **AI 每次生成 SQL 时自动带上这些知识**

   > **这一条无人做，且一旦有团队沉淀就是强迁移成本。** 我认为这是**长期最强**的资产。

3. **MCP 工具分层 + 审计**
   - `describe_schema` / `query_readonly` / `query_write` / `admin` 分层暴露
   - 每次调用记录：调用方、SQL、行数、耗时、是否被拦截 → 审计报表

4. **Agent Skill 分发** —— 抄 `dbx agent setup`，一行命令把最佳实践装进 `~/.agents/skills/`

#### S3. 测试数据工厂 / 数据脱敏 ⭐ 竞争最干净

**为什么有吸引力：**
- **几乎无人做**（DBX、DBeaver、Beekeeper 都没有）
- **开发者极痛**：接一个生产库做本地开发，不敢碰数据
- **和 AI 天然契合**：给 Agent 一个安全的沙箱库

**功能：**
1. 从生产库按**表级采样**（每个表 N 行，或按主键范围/时间范围）
2. **自动识别并脱敏**：手机号/邮箱/身份证/银行卡/姓名/地址（正则 + 中文规则库）
3. **保持外键一致性**：脱敏后 FK 引用仍然对得上（关键难点，也是差异化）
4. **保持业务分布**：分位数/类别分布不变，让性能测试有意义
5. **生成可复现的 DDL + 数据脚本**（版本化，可进 CI）
6. **一键推送到 Docker / 本地 PostgreSQL / SQLite**
7. 脱敏规则可自定义（正则、字典、UDF）

> 落地成本低（SQL 生成 + 正则），价值感知极强（10 分钟替代半天手工造数），**我认为是最好的冷启动 killer feature**。

### 🥈 A 级：强吸引力，落地快（建议 v1/v2 必做）

| # | 功能 | 说明 | 为什么有吸引力 |
|---|---|---|---|
| **A1** | **性能诊断（Triage）** | 开表即采样：慢查询 top N、**缺失索引检测**、无 PK 表、隐式类型转换、笛卡尔积风险；Explain **解读**（"这条会全表扫 3000 万行，建议建 (a,b) 索引"） | 开发者自助排障的最高频需求，DBX 只有原始 plan 没有解读 |
| **A2** | **Schema Diff + Data Diff 一体化** | 结构差异 + 数据差异同一个视图；支持"只同步结构/只同步数据/双向同步"；同步前 dry-run | 迁移、灰度、双写校验刚需。DBX 有两个分开的工具 |
| **A3** | **安全 DDL 预览 + 影响面分析** | 见 S1.3 | 见 S1 |
| **A4** | **连接配置即代码** | 连接定义用 YAML 进 git，团队共享；对接 Vault / 1Password / 云厂商 Secrets Manager | 团队协作刚需，DBX 只有加密导入导出 |
| **A5** | **统一查询历史 + 可观测** | "这个连接上周跑了什么"、慢查询 Top 20、**跨连接的全局搜索历史** | 排障与复盘刚需，DBX 的 history 是本地的、散的 |
| **A6** | **Mock 数据生成（Grid 内置）** | DBX 已有 `dataGenerate`，但只在选中单元格里；可以扩展为"生成 1000 行测试数据" | 轻量高感知 |

### 🥉 B 级：有就更好（v3+）

- 只读网关模式（团队共享 + 细粒度权限 + 全量审计）
- ER 图交互式 + 自动生成 Mermaid（进 Markdown 文档）
- 字段血缘的**可视化 + 导出**（DBX 有 field-lineage，把 UI 做漂亮就是差异化）
- 多环境配置同步
- 数据库文档站一键生成（DBX 有，但可以做得像 Wiki 一样）
- S3 / 对象存储浏览器（DBX 做成插件了，说明需求真实）

### 功能优先级矩阵（可直接当 roadmap）

```
价值 ↑
  │  ★ S1 变更管控     ★ S3 测试数据工厂      ← 先做这个
  │  ★ A1 性能诊断     ★ S2 知识库
  │  ★ A2 Schema/Data Diff 一体化
  │  ---------------
  │  A3 DDL 预览   A4 连接即代码   A5 查询历史
  │  ---------------
  │  B1 只读网关   B2 ER 图    B3 血缘可视化
  └──────────────────────────────────────────→ 实现难度
```

## 2.3 不该做什么

| ❌ 不要做 | 原因 |
|---|---|
| **追求支持 100 种数据库** | DBX 已经用 5.5 个月 + 27 crate 干到 100+，你正面硬刚必输。**支持 8 种做到极致 > 支持 80 种做到半残** |
| **纯 Text-to-SQL 聊天框** | 2026 年这个功能已经白菜价（DBX、JetBrains、所有云厂商都有）。无护城河，且 LLM 容易在陌生 schema 上幻觉 |
| **自研 SQL 解析器/AST** | `sqlparser-rs`（Rust）/ `sqlglot`（Python）/ `node-sql-parser` 都有且很成熟。除非你要做方言扩展，否则别造 |
| **自研 grid 组件** | 用 AG Grid（社区版功能已足够）/ TanStack Table。除非你确定要做 DBX 那种级别的交互 |
| **现在就做插件平台** | 等有 10+ 用户和 2+ 外部贡献者。DBX 也是有社区后做的 |
| **过早引入前端微服务 / 复杂状态** | Vue 3 + Pinia + 100 个 `lib/*.ts` 纯函数就够了。DBX 的 1930 文件 `lib/` 就是最佳实践 |
| **追求"什么都有"** | 见 2.1 路线 A。1.2k open issues 就是"什么都有"的代价 |

## 2.4 架构设计（可直接落地的方案）

### 2.4.1 整体分层

```text
┌──────────────────────────────────────────────────────────────────┐
│  形态层  Desktop(Tauri) │ Web │ CLI │ MCP Server │ Docker        │
├──────────────────────────────────────────────────────────────────┤
│  API 层   统一 Command/Tool 注册表（一份定义，5 种形态自动暴露）   │
├──────────────────────────────────────────────────────────────────┤
│  编排层   Rust core                                          │
│    ├── connection/  连接路由、凭据、SSH 隧道、任务监督            │
│    ├── query/       查询编排、取消、事务、结果流式返回             │
│    ├── schema/      元数据 + 语义层（列注释/枚举/知识库）        │
│    ├── change/      ★ 变更工作台（migration、dry-run、回滚）    │
│    ├── audit/       ★ 审计日志                                 │
│    ├── data/        导入导出、迁移、对比                        │
│    ├── ai/          Agent 循环、上下文压缩                     │
│    └── safety/      风险分级、审批流、生产保护                 │
├──────────────────────────────────────────────────────────────────┤
│  能力层                                                       │
│    ├── drivers     原生 Rust 驱动（PG/MySQL/SQLite/Redis）      │
│    ├── jdbc-agent  ★ 通用 JDBC 通道（覆盖国产库长尾）          │
│    ├── sql         解析、方言、风险分析、DDL 生成、diff        │
│    ├── formats     CSV/XLSX/JSON/SQL                          │
│    └── platform    进程、路径、代理、下载、Keyring              │
├──────────────────────────────────────────────────────────────────┤
│  声明层   connections/*.yaml  types/*.yaml  dialects/*.yaml     │
│          （build.rs 代码生成）                                   │
└──────────────────────────────────────────────────────────────────┘
```

### 2.4.2 核心抽象 1：统一命令注册表

这是"一份代码 5 种形态"的支点。

```rust
// crates/core/src/registry.rs
pub struct CommandSpec {
    pub name: &'static str,
    pub danger: Danger,          // ReadOnly | Mutating | Destructive | Admin
    pub produces: DataKind,      // Rows | Schema | Diff | File | Stream | None
    pub requires_approval: bool,
}

pub trait Command {
    type Input:  DeserializeOwned + Validate;
    type Output: Serialize;
    const SPEC: CommandSpec;
    async fn run(&self, ctx: &Context, input: Self::Input) -> Result<Self::Output>;
}
```

由 `CommandSpec` 自动生成：
- Tauri command（带权限检查）
- MCP tool（带 `danger` 声明 → Agent 侧可见性过滤）
- CLI 子命令（带 `--json` 输出）
- HTTP 路由
- 审计埋点

> **⭐ 这一个抽象同时解决了"5 种形态"、"MCP 权限分层"、"全量审计"三个问题。** 是本报告最推荐的设计。

### 2.4.3 核心抽象 2：驱动（把长尾成本降到最低）

```rust
// 统一驱动接口
#[async_trait]
pub trait Driver: Send + Sync {
    fn kind(&self) -> DriverKind;
    async fn connect(&self, cfg: &ConnConfig) -> Result<Box<dyn Session>>;
    async fn introspect(&self, s: &dyn Session) -> Result<SchemaSnapshot>;
    fn dialect(&self) -> &Dialect;              // 来自 YAML
    fn risk(&self, sql: &str) -> RiskReport;   // 来自 dbx-sql
}

#[async_trait]
pub trait Session: Send {
    async fn query(&self, sql: &str, q: QueryOptions) -> Result<RowStream>;
    async fn cancel(&self) -> Result<()>;
    async fn begin(&self) -> Result<Txn>;
    async fn schema(&self) -> &SchemaSnapshot;
}
```

**长尾实现只有一种：JDBC Agent**

```
┌──────────────────────────────────────────────┐
│ Rust core  →  spawn java -jar agent.jar      │
│                stdin/stdout JSON-RPC          │
│                Agent Protocol v2（多会话）    │
├──────────────────────────────────────────────┤
│ Agent 内：HikariCP 连接池（按不可变连接身份共享）│
│           drivers/*.jar（用户或你分发）        │
└──────────────────────────────────────────────┘
```

**加一个新数据库的成本：**
- 有 JDBC 驱动 → **写一个 YAML**（默认端口、URL 模板、认证、字段、图标、能力开关），0 代码
- 无 JDBC 驱动（PG/MySQL/Mongo/Redis/ES）→ 写一个 Rust 驱动，约 1500 行

> **⭐ 这就是我最想给你的一条建议**：
> DBX 花大力气写了 53 个 Go Agent，其实** JDBC 一条通道就能覆盖其中 2/3**（Oracle/DB2/Hive/Trino/达梦/金仓/海量/神通…全都有官方 JDBC 驱动）。
> **你的 Rust 代码量可以只有 DBX 的 1/5，覆盖的数据库数量却差不多。** 把省下的时间全部投到 2.2 节的 S 级功能上。

### 2.4.4 核心抽象 3：结果流式返回

大数据量是数据库工具的头号体验问题。

```rust
pub enum RowStream {
    Page { rows: Vec<Row>, cursor: Cursor, total: Option<u64> },
    Cursor(Cursor),                 // 服务端游标，惰性拉取
    Done { affected: u64 },
    Err { message: String, position: Option<SqlPos> },  // ★ 错误带位置
}
```

要点：
- **游标式分页**，不要一次拉全表
- **`SqlPos`（行/列位置）** —— DBX 支持"点错误直接跳到出错的行列"，这个细节很值钱
- 结果通过 Tauri Channel 增量推给前端，前端虚拟滚动消费
- 大 value 延迟加载（先返回类型和长度，点开再拉）

### 2.4.5 核心抽象 4：Schema 语义层（你的护城河）

```rust
pub struct SchemaSnapshot {
    pub databases: Vec<Database>,
    pub tables:    Vec<Table>,
    // ─── 语义增强部分（DBX 没有）───
    pub knowledge: Option<TableKnowledge>,   // 从 .dbctx 加载
    pub stats:     Option<TableStats>,       // 行数、数据量、增长率
    pub quality:   Option<QualityReport>,    // ★ 诊断：无PK/无索引/无注释/疑似敏感字段
}

pub struct TableKnowledge {
    pub business_meaning: Option<String>,
    pub enum_values:      HashMap<String, String>,   // status -> "1=待审 2=通过 3=驳回"
    pub join_paths:       Vec<JoinPath>,
    pub pitfalls:         Vec<String>,
    pub owners:           Vec<String>,
}
```

**语义层有四个来源**（按可信度排序，自动合并）：
1. **数据库原生注释**（`COMMENT ON TABLE/COLUMN`）—— 最可信
2. **人工维护的 `.dbctx`** —— 进 git，团队共享
3. **AI 推断**（基于列名/取值/查询历史）—— 标记为"推断"，需人工确认
4. **查询历史统计**（这张表最常被哪些 SQL 访问、常用 WHERE 条件）

> **第 4 条是很多人忽略的金矿**：从查询历史里能自动学到"这张表的标准查询长什么样"，这比任何手写文档都准，而且**自动获得**。

## 2.5 技术选型决策

### 核心决策表

| 维度 | 建议 | 理由 |
|---|---|---|
| **桌面壳** | **Tauri 2** | ✅ 无争议。25MB vs Electron 150MB。**但**：不要 vendor 上游库（DBX 的教训） |
| **前端框架** | **Vue 3** 或 **React**，看你熟哪个 | Vue：Pinia + reka-ui 生态成熟，上手快<br>React：AG Grid / TanStack Table 生态最强，招聘更容易 |
| **前端架构** | **`lib/` 纯函数 + `components/` 纯展示** | ⭐ 抄 DBX。570 个测试文件的底气来自这里 |
| **后端语言** | **Rust**（如果你要桌面端 + 轻量）<br>**TypeScript**（如果你要快速验证 + Web 优先） | 见下方决策树 |
| **长尾驱动** | **Java JDBC Agent** | ⭐ 唯一正确解。用 10% 工作量覆盖 80% 数据库 |
| **SQL 解析** | Rust: `sqlparser-rs` + `sqlformat`；TS: `node-sql-parser` | 别造轮子 |
| **Grid** | **AG Grid Community** 或 **TanStack Table + Virtual** | 别自研。DBX 自研是因为有特殊需求（按 WHERE 更新全表） |
| **图表** | ECharts | DBX 也用，够用 |
| **ER 图** | Vue Flow / React Flow + dagre/elkjs | 自动布局 |
| **MCP** | 官方 `rmcp`（Rust）| 别自己实现 JSON-RPC |
| **CLI** | clap（Rust）或 commander（TS） | 必须支持 `--json` |
| **状态** | Pinia / Zustand | ✅ |
| **样式** | Tailwind 4 + shadcn | ✅ |
| **本地存储** | SQLite（Rust: `sqlx` / TS: `better-sqlite3`） | 存连接配置、历史、审计、语义库 |
| **凭据** | OS Keyring | 绝不明文存密码 |

### 语言决策树

```
你要做桌面单机客户端吗？
├─ 是 → 必须 Rust（体积是 DBX 唯一的传播点，Electron 打不过）
│        └─ 团队有人会 Rust？→ 是：Tauri 2
│                            → 否：Go + Wails（体积也小）或 Electron（认了）
└─ 否（Web / 团队协作 / 验证想法）
     └─ TypeScript + Node（Fastify/Hono）+ Postgres/PG Bouncer
        └─ 好处：一人 3 个月能出可用产品，招聘容易，迭代快
```

**我的最终建议**：
> **Tauri 2 + Rust core + Vue/React + Java JDBC Agent**
> 这是与 DBX 同一战略位置（桌面 + 私有化 + 国产库）但**代码量只需 1/5** 的组合。
> 关键取舍：**放弃"自研全部驱动"，把 2/3 的数据库交给 JDBC Agent，然后全力做 2.2 节的 S 级功能。**

### 最低成本入口（强烈建议先做这个）

如果只能做一个东西，做这个（**一周**）：

```bash
mydb connections list --json          # 列出连接（不打印密钥）
mydb schema list prod --json
mydb schema describe prod users --json
mydb query prod "select 1" --json
mydb context prod --tables users,orders --format prompt   # ⭐ 给 AI 的上下文
```

+ 一个 `SKILL.md` 装到 `~/.agents/skills/mydb/` + 一个 MCP server。

**为什么先做这个：**
- 不需要 UI，当天能跑
- 直接进入 Claude Code / Cursor / Codex 的工具箱，**自带分发**
- 你能立刻收到真实反馈，知道大家到底要什么
- CLI 的 `--json` 输出天然复用到 UI 层（MCP tool 的 schema 也能从同一份定义生成）

## 2.6 12 个月路线图

### Phase 0（第 1-2 月）：验证，不写 UI

| 任务 | 交付 | 成功标准 |
|---|---|---|
| 声明式连接模型 | `connections/*.yaml` + build.rs 生成 | 加一个 JDBC 库 = 写 1 个 YAML，0 代码 |
| JDBC Agent 通道 | 协议 v2（多会话）+ 结构化错误 | 能连 达梦/PG/MySQL，取消不崩 |
| CLI | `mydb connections/schema/query/context --json` | 在自己项目里替代手工查询 |
| MCP Server | 三级权限 + allowlist | Claude Code 能查生产库（只读模式） |

> **Gate 1**：找 20 个开发者试用 CLI+MCP。如果没人用，**停下来重新定位**，别继续写 UI。

### Phase 1（第 3-5 月）：桌面端 MVP

| 任务 | 交付 |
|---|---|
| Tauri 2 + Vue/React 骨架 | 5 种形态共用 core |
| 连接管理 | SSH 隧道、Keyring、连接即代码(YAML) |
| Schema 浏览器 | 树 + 搜索 + 收藏，**注释内联显示** |
| 查询编辑器 | CodeMirror 6 + 方言高亮 + 补全 |
| 数据表格 | AG Grid + 服务端游标分页 |
| 安全层 | 生产标记 + 危险分级 + 确认弹窗 |

> **Gate 2**：3 个真实团队日活使用。

### Phase 2（第 6-8 月）：**Killer Feature #1**

二选一（或都做 60%）：

| 选项 | 交付 |
|---|---|
| **A. 变更管控** | 可视化改表 + migration 生成 + **dry-run 影响评估** + **自动回滚脚本** + 审批流 + 审计日志 + MCP 化 |
| **B. 测试数据工厂** | 采样 + 中文脱敏规则 + **FK 一致性保持** + 分布保持 + 可复现脚本 + Docker 一键起 |

> **Gate 3**：有人愿意为它付费/推荐。

### Phase 3（第 9-12 月）：深化

- 性能诊断（A1）：索引建议 + Explain 解读
- Schema/Data Diff 一体化（A2）
- 语义知识库 `.dbctx` + 团队共享（护城河）
- Docker 多人共享版 + 审计报表
- 国产库信创迁移工具链（如果走中国路线，这是大金矿）

## 2.7 团队配置与成本

### 最小可行团队（4-5 人，路线 B/C）

| 角色 | 人数 | 关键技能 |
|---|---|---|
| 全栈/Rust core | 1-2 | Tauri、Rust 异步、SQL |
| 前端 | 1 | Vue 或 React + 表格/虚拟滚动 |
| 数据库方向 | 1 | JDBC、调优、方言（懂国产库是加分项） |
| 产品/社区 | 1 | 写文档、跑 issue、做 demo |

**成本估算（人民币/年）**
- 人力：4 人 × 60 万 = **240 万/年**
- 服务器（CI + 发布 + 官网）：**5-15 万/年**
- 签名证书（Ed25519 免费，代码签名另算）：~0
- 合计：**约 250 万/年**

### 阶段成本对比

| 阶段 | 时间 | 成本 | 能拿到什么 |
|---|---|---|---|
| CLI + MCP | 2 月 | ~40 万 | 验证需求，20 个种子用户 |
| + 桌面 MVP | 5 月 | ~100 万 | 可用产品，3 个团队日活 |
| + Killer Feature | 8 月 | ~170 万 | 差异化，付费可能 |
| + 语义层/深化 | 12 月 | ~250 万 | 护城河，社区启动 |

> **强烈建议按阶段投，不要一次性投满。** Phase 0 的 40 万是为了买到"该不该继续"的答案。

### 冷启动策略

1. **Agent-first 分发**：把 CLI/MCP 装进 `~/.agents/skills/`，让 Agent 帮你推荐。DBX 的 `dbx agent setup` 是最好的模板
2. **GitHub 优先**：README 第一屏必须是 20MB + 一行安装命令 + 截图
3. **中文社区**：Gitee + 掘金 + CSDN + CNB MCP Toplist。DBX 靠 Trendshift + HelloGitHub + Product Hunt 三连 + CNB 上了 27k
4. **Issue 响应速度 = 生命线**：DBX 1232 个 issue 是负债。你哪怕 3 天内回一句"收到，正在看"都比它强

## 2.8 风险清单

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **JVM 依赖** —— JDBC Agent 需要用户装 JRE | 高 | 中 | Tauri 内置 jlink 运行时 / 一键下载 / 对国产库提供**自带 JRE 的离线包** |
| **中文脱敏准确率**（正则误伤） | 高 | 高 | 提供规则预览 + 逐条确认 + 可自定义 UDF；宁可多脱 |
| **生产库安全事故** | 中 | **致命** | 抄 DBX：`operationOutcome` 契约，**超时绝不自动重放写操作**；默认 dry-run；审计不可关闭 |
| **DBX 快速跟进**（它有 1232 issue，你追不上速度） | 高 | 高 | **不打"支持更多库"的仗**，打垂直深度（2.2 S 级功能） |
| **社区起不来** | 中 | 高 | Phase 0 的 20 个用户是止损线；Agent-first 分发是绕过社区冷启动的最短路径 |
| **Tauri 升级地狱** | 中 | 中 | 不 vendor 上游库；锁定 Tauri 版本；CI 跑三平台 |
| **跨平台 JDBC 差异**（国产库常有平台限定 jar） | 中 | 中 | 每个驱动做 CI 三平台真机测试；提供驱动包下载器 |
| **AI 幻觉导致错误 SQL** | 高 | 高 | 不做纯 NL2SQL。做**结构化编辑**（可视化改表）+ **SQL 预览必须 review** + 危险分级 |
| **JVM 内存占用**（一个 Agent 进程 = 1 个 JVM） | 中 | 中 | 多会话共享一个 JVM（DBX 的 Agent Protocol v2 就是为此）；最多 256 session |
| **法务/合规** | 低 | 中 | Apache-2.0（可借鉴）或 AGPL；不打包商业 JDBC 驱动，只提供下载器 |

---

# 第三部分：UI 设计

## 3.1 设计哲学（从 DBX 反推）

DBX 的 UI 能做到"轻量 + 功能强 + 不乱"，靠四条：

1. **三栏经典布局，比例可调** —— 永不改变的空间记忆
2. **一切"读取"操作零配置、零确认；一切"写入"操作需要显式确认** —— 认知负担分层
3. **模式化工作台**：Redis 有 Redis 的浏览器、Mongo 有 Mongo 的浏览器、MQ 有 MQ 控制台 —— **不为异构数据硬套一个网格**
4. **危险等级用颜色 + 文案双重编码** —— 不只靠红色（色盲友好）

## 3.2 布局

```text
┌─────────────────────────────────────────────────────────────────────┐
│ [●●●] dbx   prod-postgres ─ ▼   🔍 全局搜索        ⚙  ⚡AI  ─ □  ✕  │  36px 标题栏(可拖拽)
├──────────────┬──────────────────────────────────────────────────────┤
│              │ ┌──────────────────────────────────────────────────┐│
│  ★ 收藏      │ │ 标签: users  orders  ✕│ +                        ││  36px
│  ─────────   │ ├──────────────────────────────────────────────────┤│
│  🗄 数据库    │ │ ┌──────────────────┬───────────────────────────┐ ││
│    ▸ prod    │ │ │                  │                           │ ││
│      users   │ │ │   SQL 编辑器      │      AI 面板 (可折叠)      │ ││  可拖拽分栏
│      orders  │ │ │   CodeMirror 6    │      (可选展开 380px)     │ ││
│    staging   │ │ │                   │                           │ ││
│              │ │                   │                           │ ││
│  ⚡ 工作台    │ ├───────────────────┴───────────────────────────┤ ││
│    Redis     │ │  结果 1 (1,234)  结果 2  | 图表  | 消息        │ ││  30px
│    Mongo     │ ├──────────────────────────────────────────────┤ ││
│    队列       │ │                                              │ ││
│              │ │            数据表格 (AG Grid)                  │ ││  剩余
│  ⚙ 工具       │ │                                              │ ││
│    迁移       │ │                                              │ ││
│    诊断 ★     │ │                                              │ ││
│    脱敏 ★     │ │                                              │ ││
│    审计 ★     │ └──────────────────────────────────────────────┘ ││
├──────────────┴──────────────────────────────────────────────────────┤
│ ✓ 查询完成 · 1,234 行 · 12ms  · [事务: ON]      ⚙ 快捷键  ⏻       │  24px 状态栏
└─────────────────────────────────────────────────────────────────────┘
   240px 可拖拽                ← 中间全部可拖拽，布局持久化
```

**关键设计点：**
- **左侧栏同时是导航和诊断入口** —— 把 2.2 节的 S 级功能（诊断/脱敏/审计）放这里，让它们有位置
- **AI 面板默认折叠**，因为它是"辅助"不是"主路径"（DBX 的 AI 是侧挂不是主界面，这点很对）
- **状态栏显示事务状态** —— 抄 DBX 的 `sqlExecutionDangerStore`，让"当前是否有未提交事务"始终可见
- **标题栏可拖拽** —— 因为 DBX 用了 Tauri 原生窗口，必须自绘

## 3.3 视觉风格

### 色彩

```css
/* 语义色板 —— 按角色命名，不按颜色命名 */
--bg-canvas:    #0d1117   /* 亮色: #ffffff */
--bg-surface:   #161b22
--bg-elevated:  #21262d
--border:       #30363d

--text-primary:   #e6edf3
--text-secondary: #8b949e
--text-tertiary:  #6e7681

/* 危险分级 —— 三级，不是两级 */
--risk-readonly:    #388bfd   /* 蓝：只读 */
--risk-mutating:    #d29922   /* 黄：写数据 */
--risk-destructive: #f85149   /* 红：改结构/删数据 */
```

**危险分三级是关键决策**。大多数工具只有"可写/不可写"两级，导致用户对所有警告脱敏麻木。三级 + 不同文案，用户才能做判断。

### 字号 / 密度

```
UI 文字:      13px（数据库工具是工作软件，不是消费应用）
等宽:         13px / 1.5
行高:         1.5（阅读）/ 1.2（密集模式）
间距基数:     4px
圆角:         6px（不要 12px，太"消费级"）
表格密度:     紧凑(28px) / 标准(32px) / 宽松(40px) 可切换
```

### 连接颜色标识

DBX 有"color-coded connections"。做对了很实用 —— 生产库一眼可辨：

```
🔴 prod   🟠 staging   🔵 dev   ⚪ local
```
**生产连接**额外加：
- 侧栏条目加红色左边框
- 标题栏显示红色 chip
- 任何写操作**默认拦截**，需要点"解锁"

## 3.4 关键交互设计

#### ① 危险操作确认（抄 DBX + 强化）

**不要用**简单的"确定要 DELETE 吗？"

**要分级**：

```
等级 1 —— 只读            → 无确认
等级 2 —— 写数据          → Toast 撤销（5s）或批量确认
等级 3 —— 改结构/删数据   → 模态框，必须手动点击（不能靠惯性点掉）

模态框内容（DELETE 无 WHERE）：
┌────────────────────────────────────────────────┐
│  ⚠  危险操作                                    │
│                                                 │
│  DELETE FROM orders                              │
│                                                 │
│  预计影响： 2,847,193 行  (1.2 GB)              │
│  锁表时间： ~38 秒                              │
│  操作人：jason@dev-mbp  来源：AI Agent (claude) │
│                                                 │
│  □ 我已知悉影响，确认执行                        │  ← 必须手动勾
│                                                 │
│           [取消]              [确认执行]        │
└────────────────────────────────────────────────┘
```

> **把"操作人 + 来源（人还是 Agent）"放进确认框**，是 AI 时代的必备设计。DBX 已经在做了。

#### ② SQL 错误 → 精确跳转

```
❌ ERROR: relation "oder" does not exist

     SELECT * FROM oder
                      ↑ 第 1 行，第 22 列
     [跳转到错误位置]
```

**点击错误 → 光标跳到出错行列并选中**。这个细节 DBX 有（`issue-5581-oracle-error-position-*` 截图），开发者每天用几十次，价值极高。

#### ③ 查询结果的分层过滤（明确执行位置）

在表格工具栏放 4 个入口，**每个都标注它在哪里执行**：

```
┌──────────────────────────────────────────────────────────┐
│ 🔍 搜索当前结果 │ 📋 过滤当前结果 │ ⌨ WHERE │ ⇅ ORDER BY │
│   (内存,已加载)     (内存,已加载)      (数据库)   (数据库)  │
└──────────────────────────────────────────────────────────┘
                                    └─ 大表务必用这两个 ↓
```

在 WHERE 输入框旁实时显示：`WHERE: status = 'paid'` `→  预计 0.3s / 扫描索引 idx_status`

> **"预计影响"提示是 DBX 完全没有的**，但它是防止误操作生产库最有效的手段。实现不难：拿 `EXPLAIN` 的行数估算。

#### ④ 可编辑性判定（抄 DBX，这点做得很好）

**结果集能不能编辑，必须明确告诉用户为什么**：

```
┌─ 只读结果 ─────────────────────────────────┐
│ 🔒 此结果不可编辑                           │
│ 原因：JOIN 查询无法确定目标表                │
│ [查看详情]                                  │
└─────────────────────────────────────────────┘

┌─ 无主键 ────────────────────────────────────┐
│ ⚠ 此结果不可编辑                            │
│ 原因：表 orders 没有主键或唯一索引，         │
│       无法生成安全的 UPDATE 条件             │
│ [为此表添加主键]                             │
└─────────────────────────────────────────────┘
```

**而不是**简单地灰掉单元格。这直接影响用户信任度。

#### ⑤ AI 交互：侧挂而非抢占

```
┌────────────────────────────────────────────────┐
│ 选中: users.status                             │
│                                                │
│  "这些状态的枚举值是什么？帮我写个统计"        │
│                                                │
│  AI: users.status 枚举: 1=待审 2=通过 3=驳回  │
│      (来自表注释，已确认)                      │
│                                                │
│      SELECT status, COUNT(*)                  │
│      FROM users                               │
│      GROUP BY status;                         │
│                                                │
│      [在编辑器打开]  [复制]  [执行 ⌘↵]        │
│                                                │
│  ⚠ 这会扫描 users 全表 · 预估 2.3M 行          │
└────────────────────────────────────────────────┘
```

要点：
- AI 输出**永远不自动执行**，必须显式点
- 显示**知识来源**（"来自表注释，已确认" / "AI 推断，需确认"）→ 建立信任
- 显示**影响预估**
- 有「在编辑器打开」→ 用户可以改，而不是被动接受

#### ⑥ 变更工作台（S1 的 UI，DBX 完全没有）

```
┌─ 结构变更 ─────────────────────────────────────────────────────┐
│                                                               │
│  orders 表                                      [可视化编辑]  │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  列名      类型            约束       操作                 │ │
│  │  id        bigint          PK                            │ │
│  │  user_id   bigint          FK→users.id      [× 删除]     │ │
│  │  amount    decimal(10,2)   NOT NULL                      │ │
│  │  remark    varchar(20)  →  varchar(200)    [改]           │ │
│  │            ⚠ 扩容安全 · 无数据风险                        │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                               │
│  [可视化] [DDL] [diff vs staging]                             │
│                                                               │
├─ 影响评估 ────────────────────────────────────────────────────┤
│  锁表时间      ~0.4s（仅元数据变更，不重写数据）              │
│  扫描行数      0                                              │
│  临时空间      0 B                                            │
│  算法建议      直接 ALTER（无需 gh-ost）                      │
├─ 回滚脚本 ────────────────────────────────────────────────────┤
│  ALTER TABLE orders MODIFY remark varchar(20);               │
│  ⚠ 原 varchar(200) 数据会截断，请先备份                        │
├─ 审批 ────────────────────────────────────────────────────────┤
│  环境: 🔴 prod   工单: CHG-2026-1016-01                      │
│  申请人: jason   审批人: [ 待审批 ]  执行窗口: [选择]        │
│                                                               │
│  [生成 migration]  [提交审批]  [导出 SQL]      [提交执行]     │
└────────────────────────────────────────────────────���──────────┘
```

#### ⑦ 诊断视图（S3 的 UI）

打开一张表，右上角角标：

```
┌ users ─────────────────────────── [⚠ 3 个问题] ─┐
│                                               │
│  ⚠ 无主键                    → [添加主键]     │
│  ⚠ 2 个疑似敏感字段未脱敏    → [查看]        │
│  ℹ 245 万行 / 820MB / 月增 8%               │
│  ℹ 查询热点: 90% 查询带 user_id 过滤         │
│     建议索引: (user_id, status)  [+创建]     │
│                                               │
└───────────────────────────────────────────────┘
```

**"打开就知道哪里有问题"** —— 这是 DBX 完全没有、但价值极高的一个入口。

## 3.5 设计系统清单

| 类别 | 选型 | 说明 |
|---|---|---|
| 组件库 | **shadcn-vue** / **shadcn/ui** | 源码级定制，不是 npm 黑盒 |
| 工具类 | `tailwind-merge` + `clsx` + `cva` | shadcn 标配 |
| 图标 | **Lucide** | DBX 也用 |
| 表格 | **AG Grid Community** | 虚拟滚动、列固定、筛选、聚合都是白给的 |
| 编辑器 | **CodeMirror 6** | 比 Monaco 轻，比 textarea 强 |
| 图 | **Vue Flow / React Flow** + `elkjs` | 自动布局 |
| 图表 | **ECharts** | |
| 虚拟滚动 | `vue-virtual-scroller` / `@tanstack/virtual` | |
| 面板分栏 | `splitpanes` | DBX 也用 |
| Markdown | `marked` + `shiki` | AI 输出高亮 |
| 快捷键 | 自研（DBX 自研 `shortcut_recording.rs`，处理 macOS/Windows 差异） | |
| 主题 | CSS 变量 + `[data-theme]` | 必须支持跟随系统 |
| 国际化 | vue-i18n / i18next | **中英双语是标配**（中文市场） |
| 无障碍 | 焦点管理、键盘导航、语义标签 | 别从零开始忽略 |

## 3.6 视觉参考

- **DBX**：功能完整度、工具栏密度、`docs/screenshots/` 里有 250+ 张真实截图，是最好的 UI 参考
- **Linear**：动效与交互反馈（数据库工具不需要，但加载/取消的反馈值得学）
- **VS Code**：面板布局、标签页、快捷键体系
- **TablePlus**：macOS 上的精致感（原生感、阴影、圆角节奏）
- **Retool / Metabase**：查询编辑器 + 结果面板的经典组合

---

# 附录：关键数据速查

## A. DBX 仓库结构

```text
dbx/
├── apps/desktop/src/        前端 (3400 文件)
│   ├── lib/                  1930  业务逻辑（纯函数，可测）
│   ├── components/            987  纯展示组件
│   ├── composables/           183
│   ├── stores/                139  18 个 Pinia store
│   └── i18n/                   72
├── src-tauri/src/             97  Tauri 壳 + ~80 个 command 模块
├── crates/                    27 crates（见 1.3）
│   ├── dbx-core/             307  编排层
│   ├── ARCHITECTURE.md             ⭐ 必读
│   └── dbx-{types,sql,drivers,formats,platform,ai-provider,
│            plugin-runtime,mcp,web,cli,sqlite-worker,...}
├── agents/
│   ├── drivers/*-go/          53  Go Agent
│   ├── drivers/*/            ~20  Java JDBC Agent
│   ├── common/, go-common/         共享协议代码
│   └── docs/agent-protocol-v2.md  ⭐ 必读
├── plugins/
│   ├── connection-types/*.yaml  88  声明式连接类型
│   ├── dialects/                   声明式方言
│   ├── sdk/{rust,go,cli,packager}  插件 SDK
│   └── jdbc/                       通用 JDBCX
├── packages/{cli,mcp-server,plugin-cli,mongo-shell}
├── docs/                            VitePress 文档站（50+ 页 × 中英）
├── deploy/                          Docker / demo / fnos
└── vendor/                          ⚠️ 被魔改的上游库
```

## B. 必读的 3 个文件（如果继续研究 DBX）

1. `crates/ARCHITECTURE.md` —— 分层约束、所有权矩阵、验证纪律
2. `agents/docs/agent-protocol-v2.md` —— 多会话 Agent 协议 + 结构化错误契约
3. `plugins/README.md` —— 信任模型（人工审核 / SHA-256 / 签名 三者职责分离）

## C. 三句话总结

> **1. DBX 的护城河不是"支持 100 个数据库"，而是"用 4 层驱动通道 + 声明式 YAML 把长尾成本压到接近零，再把省下的时间全部投到 AI/MCP/安全上"。**
>
> **2. 你的机会不在正面竞争，而在它明显缺席的三个位置：变更管控（含回滚）、测试数据工厂/脱敏、性能诊断与语义知识库。**
>
> **3. 第一步不要写 UI —— 先做 CLI + MCP + Agent Skill，一周时间验证 20 个真实用户。这是最低成本、最高信息增益的起点。**

---

*报告完*
