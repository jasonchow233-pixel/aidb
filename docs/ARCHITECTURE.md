# AgenticDB 架构（Java 25 + JavaFX 25）

> 当前唯一技术路线。旧 Rust / Vue / Python 实现与构建产物已删除，不再作为运行时依赖。
> 执行清单见 `NAVICAT-TODO.md`；组件选型见 `docs/COMPONENT-SELECTION.md`；
> UI 规则见 `docs/UI-GUIDE.md`；Navicat 对标差距见 `docs/NAVICAT-GAP.md`。

---

## 1. 约束

| 约束 | 决定 |
|---|---|
| UI | JavaFX 25 原生控件，原生窗口装饰；不内嵌 WebView |
| 运行时 | 不启动 HTTP 端口，不做本地 Web 服务 |
| JDBC | 所有 SQL 参数绑定；只有标识符（表/列名）走方言引用 |
| 线程 | 所有 JDBC 调用串行跑在 `adb-db` 单线程执行器；UI 更新回 JavaFX 线程 |
| 编辑 | 只有含主键的表可编辑；按主键定位；保存前预览 SQL；单事务提交 |
| 主题 | 默认跟随系统；亮色是验收基准；颜色只来自 `tokens.json` |
| 范围 | SQLite 已通；PostgreSQL / MySQL 在 P5 收尾 |

---

## 2. 模块

```
core/
  adb-model          领域模型：ColumnDef / ColumnType / RowPage / QueryLimits / Danger
  adb-declarations   连接与传输声明加载（YAML + JSON Schema）—— ⛔ 当前运行时未引用
  adb-safety         危险分级、错误定位
  adb-sql            SQL 词法内核：分句、高亮、格式化（不依赖 UI）

workbench/
  adb-jdbc           统一 JDBC 会话：连接、参数化查询/更新、事务、方言、类型映射
  adb-introspect     对象列表、列定义、主键、行数、分页/排序/筛选 SQL 构造
                      ＋ 结构内省（IndexDef / ForeignKeyDef / TriggerDef / TableStructure）
                      ＋ 结构变更 SQL 生成（StructureSqlBuilder / StructurePlan）
  adb-session        连接档案（ConnectionProfile / ConnectionStore）
                      ＋ 凭据（CredentialStore / Keychain / SecretTool）
                      ＋ 连接池（HikariCP 包装）
  adb-editing        RowIdentity / CellKey / EditBuffer / EditSqlBuilder

ui/
  adb-ui-core        tokens.json → Tokens.java + theme-dark/light.css；主题管理
  adb-ui-shell       六区外壳、菜单、工具栏、侧栏、标签栏、命令面板
                     ＋ 数据页 / 查询页 / 结构页 / 连接页 / 连接向导

testing/
  adb-fixtures       固定种子演示库与测试夹具

bootstrap/
  adb-desktop        唯一启动入口；后续 jlink / jpackage
```

模块依赖只向下：`ui-shell → workbench → core`，`bootstrap → ui-shell`。
`adb-sql` 保持独立：只把 SQL 切成 token / 语句 / 格式化文本，不反向依赖 UI 或 JDBC。
RichTextFX 的样式映射在 `ui-shell` 的 `SqlHighlighter` 中完成。

### 2.1 声明层的现状（⚠️ 需要决策）

`core/adb-declarations` 提供「加一种数据库 = 1 个 YAML，0 行代码」的能力：
4 个连接声明、4 个方言词表、5 个传输家族、1 份 JSON Schema。

**但当前运行时完全不引用它**：

- 全仓库 `io.agenticdb.declarations` 的引用只出现在该模块内部；
- `adb-declarations` **不是任何模块的 Maven 依赖**；
- `Dialect.keywordSet()` / `TransferFamily.typeMapping()` / `ConnectionDescriptor.Capabilities` 从未被读取；
- `SqlLexer` 用的是硬编码关键字表，不是 YAML 里的词表；
- `loadFromClasspath()` 的 jar 读取路径也不可用（把目录内容当换行分隔索引解析）。

结论：**要么在 P5 把它接进运行时（驱动解析、方言词表、能力矩阵驱动 UI 显隐），要么整模块删除。**
当前状态下它会误导后续开发。详见 `NAVICAT-TODO.md` §7.2 与 §10 A2/A3。

---

## 3. 启动与数据流

```
Launcher
  └─ AppShell
       ├─ ThemeManager（SettingsStore / java.util.prefs）
       ├─ SessionManager → JdbcSession（SQLite）/ ConnectionPool（PG、MySQL）
       ├─ SchemaService.listObjects() → SideBar
       └─ TabBar / WorkspaceView
            ├─ DataGridPage（按 tab id 缓存）
            │    ├─ SchemaService.describe()
            │    ├─ DataSqlBuilder.page() → DbSession.query()
            │    ├─ EditBuffer（内存中的单元格/删除/草稿）
            │    └─ EditSqlBuilder.plan() → 预览 → 事务执行
            ├─ QueryPage（按 tab id 缓存）
            │    ├─ SqlLexer / SqlSplitter / SqlFormatter
            │    ├─ DangerClassifier → 写操作确认
            │    ├─ DbSession.execute() → ExecutionResult（单条语句）
            │    └─ 结果 / 消息 / 错误页签 → CellRenderer / CsvExporter
            ├─ StructurePage
            │    ├─ SchemaService.structure() → TableStructure
            │    ├─ StructureSqlBuilder.plan() → 两级确认 → 单事务执行
            │    └─ 变更回调 → AppShell.schemaChanged → 刷新对象树与所有已开页面
            └─ ConnectionsPage / ConnectionWizard
                 ├─ ConnectionStore（~/.agenticdb/connections.json）
                 ├─ CredentialStore（Keychain / Secret Service / 内存）
                 └─ ConnectionPool.open(spec) → 测试连接
```

### 线程模型

1. `DataGridPage` 的每次加载创建 `Task`，提交到共享单线程执行器 `adb-db`。
2. 请求带递增序号；切页/刷新后旧结果直接丢弃，不覆盖新页。
3. JDBC 结果只在 `Task` 内读取，组装成不可变 `RowPage` / `ExecutionResult` 后回 JavaFX 线程。
4. 同一连接上的查询、保存、撤销保存严格串行，避免 SQLite 锁竞争。
5. 查询页先用 `SqlSplitter` 在 UI 线程分句，再把每条语句依次提交到 `adb-db`；任务带请求序号，取消或重跑后迟到回调直接丢弃。
6. `DbSession.cancelCurrent()` 调用当前 `Statement.cancel()`；SQLite 驱动不支持时，任务仍会在当前语句结束后退出。

**已知例外（待 P4.5 / P5 修）**：

- `AppShell.connectProfile` 与 `AppShell.testConnection` 在 **JavaFX 线程**上同步做
  `SessionManager.open` + `SchemaService.listObjects` / `SELECT 1`。
- `JdbcSession.activeStatement` **只在 `execute()` 里赋值**，`query()` / `update()` 不赋值 ——
  因此数据页的加载与保存**无法真正取消 JDBC 语句**，只能逻辑放弃。
- 全局只有一个 `adb-db` 线程：A 连接上的慢查询会阻塞 B 连接。

---

## 4. 数据页编辑模型

### 4.1 身份与定位

- `RowIdentity` 保存主键列和值；无主键表、视图一律只读。
- `CellKey = (RowIdentity, column)`；所有单元格修改以它为键。
- 主键列不允许在网格内直接编辑；克隆行排除主键列。

### 4.2 EditBuffer

| 状态 | 含义 |
|---|---|
| `edited` | 已有行的单元格新值 |
| `originals` | 被编辑单元格的数据库原值 |
| `deleted` | 标记删除的已有行 |
| `drafts` | 尚未写库的新增/克隆行 |

- 每个操作（单元格、删除、草稿、批量）进撤销栈；批量操作只占一个栈位。
- `dirtyCount()` = 被编辑单元格 + 删除行 + 草稿行 + 草稿单元格。
- 保存成功后清空；保存前可生成反向计划，Toast 提供“撤销保存”。

### 4.3 SQL 生成

- UPDATE / DELETE 一律用主键 `WHERE`；INSERT 只包含草稿里存在的列。
- 值全部是 `?` 参数；预览只把参数替换成可读字面量，不拼进真实 SQL。
- 保存在一个事务里执行；任一条失败整体回滚。
- 反向计划：UPDATE 还原原值，DELETE 用删除时保留的整行值重新 INSERT，
  INSERT 只有主键齐全时才能反删；否则标记为不可完整撤销。

### 4.4 视图状态

- 排序/筛选下推服务端 SQL；本页搜索与隐藏 NULL/空串只在客户端过滤。
- 列宽/顺序/显隐按“连接名 + 表名”存 `java.util.prefs`。
- 转置是只读视图，不参与编辑和批量选择。

---

## 5. 结构页模型（P4）

- `SchemaService.structure()` 汇总 `TableStructure`：列、索引、外键、触发器、DDL。
  - 列：SQLite 走 `PRAGMA table_info`（含 `dflt_value`），其它库走 `SELECT * … LIMIT 0` 的 `ResultSetMetaData`。
  - 索引：SQLite `PRAGMA index_list` + `index_info`；其它库 `DatabaseMetaData.getIndexInfo`。
  - 外键：`DatabaseMetaData.getImportedKeys`，按 `FK_NAME` 合并多列外键。
  - 触发器：SQLite `sqlite_master` / PG `information_schema.triggers` / MySQL `information_schema.TRIGGERS`。
  - DDL：SQLite `sqlite_master.sql` / MySQL `SHOW CREATE TABLE` / PG 由内省生成等价 DDL。
- `StructureSqlBuilder` 生成 `StructurePlan`（有序 SQL + 是否需要高风险审批）。
- 执行路径：单事务 → 任一步失败整体回滚 → 成功后触发 `onChanged`，
  由 `AppShell.schemaChanged` 刷新对象树与所有已打开的数据页 / 结构页。
- 视图（`TableStructure.editable() == false`）整体只读。

**已知缺口**：索引 / 外键 / 触发器只读；不能建表 / 改表名 / 删表；
CHECK 约束不内省；PG 的 DEFAULT / COMMENT 丢失（`TypeMapper` 不读 `getColumns()`）。

---

## 6. 连接层模型（P5）

| 组件 | 职责 |
|---|---|
| `ConnectionProfile` | 连接元数据。**⛔ 不含口令**；`options` 是自由 Map（当前会明文落盘，待脱敏） |
| `ConnectionStore` | `~/.agenticdb/connections.json`；0600 + 临时文件原子替换；损坏时回退为空列表 |
| `CredentialStore` | 口令接口。`platform()` 依次尝试 macOS `security` → Linux `secret-tool` → 内存 |
| `ConnectionPool` | HikariCP 包装，池大小固定 1（保持单连接串行语义）；SQLite 直连不池化 |
| `SessionManager` | 按 id 管理多个会话；SQLite → `JdbcSession`，PG/MySQL → `ConnectionPool` |

**已知缺口**：macOS 写钥匙串时口令经 `-w <password>` 进 argv（`ps` 可见）；
Windows 无凭据后端；`AppShell` 只有单一 `activeProfile` / `session`，多连接尚未并行；
连接与内省跑在 UI 线程。

---

## 7. UI 外壳

六区：菜单栏、主工具栏、侧栏、标签栏、内容区、状态栏。

- `ActionRegistry` 是菜单、工具栏、右键、命令面板的唯一动作来源，统一 enabled/快捷键。
  **已知例外**：侧栏树的右键菜单、侧栏导航按钮、工具栏图标按钮三处仍走独立路径
  （→ `NAVICAT-TODO.md` 4.5-8 / 4.5-9 / 4.5-10）。
- `AppShell` 负责窗口、会话、标签、状态栏、Toast；页面只通过 `Feedback` 回报。
- 设计令牌：`ui/adb-ui-core/src/main/resources/tokens/tokens.json` 是唯一真相。
  生成器：`java tools/GenTokens.java`。
  **已知缺口**：生成器只导出颜色类型令牌，65 个非颜色令牌永远不会进 CSS；
  120 个令牌里只有 41 个被 `app.css` 引用。
- 业务 CSS 只允许引用 `-adb-*` looked-up colors；`TokenParityTest` 会扫描颜色字面量。
- 原生窗口：`StageStyle.DECORATED`；macOS 使用系统菜单栏。

### 7.1 JavaFX Dialog 的两个坑（已踩）

1. `DialogPane.createButton(ButtonType)` 会给**每一个**按钮栏按钮挂 `ACTION` 处理器，
   内部调用 `dialog.setResultAndClose(...)`。所以自定义导航按钮（“上一步 / 下一步 / 测试连接”）
   **不能**放进 `dialog.getDialogPane().getButtonTypes()`，否则点一下就把整个对话框关掉。
   `ConnectionWizard` 现在把导航按钮放在内容区，只有“保存 / 取消”用原生按钮；
   “保存”额外挂 `ActionEvent` 过滤器做校验，校验不过就 `event.consume()` 阻止关闭。
2. `HeavyweightDialog` 构造时就把 `DialogPane` 设为它内部 `Scene` 的根，
   因此测试里**不能** `new Scene(dialogPane)`（会抛 “already set as root of another scene”）。
   正确做法是复用 `dialogPane.getScene()`。

---

## 8. 门禁

| 层 | 内容 |
|---|---|
| 单元 | `EditBuffer`、`EditSqlBuilder`、`CellRenderer`、`CsvExporter`、`SqlLexer`、`SqlSplitter`、`SqlFormatter`、`StructureSqlBuilder`、`ConnectionStore` |
| 数据 | 演示库固定种子；分页/排序/筛选 SQL 断言；保存/反向撤销真实 SQLite；结构变更真实 SQLite；`DbSession.execute` 多语句/错误/行上限 |
| UI | TestFX + Monocle Headless：外壳、标签、数据页、批量条、详情抽屉、查询页执行/取消/多结果、结构页、连接页、连接向导 |
| 样式 | `TokenParityTest`：JSON 键、`Tokens.ALL_KEYS`、亮暗 CSS 一致；禁止颜色字面量 |

统一命令：

```bash
./mvnw -B clean verify
```

当前：**30 个测试类 / 246 条测试，0 失败**。

---

## 9. 后续里程碑

| 阶段 | 内容 |
|---|---|
| P0–P3 ✅ | 外壳、竖线、数据页、查询页 |
| P4 ✅ | 结构页：列/索引/外键/触发器/DDL 查看与安全的列级编辑 |
| P4.5 ⬜ | 契约修复：快捷键补齐、结果截断提示、危险分级收紧、图标统一、动作注册表收口 |
| P5 ⬜ | 连接管理收尾：多连接并行、凭据后端、属性页、方言抽象层、PG/MySQL 真联调、声明层去留 |
| P6 ⬜ | 导入/导出向导：CSV / JSON / Excel、字段映射、预览、批处理 |
| P7 ⬜ | 打包分发、jlink / jpackage、自动更新、性能与可访问性收尾 |
