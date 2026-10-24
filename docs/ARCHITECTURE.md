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
  adb-safety         危险分级、错误定位
  adb-sql            SQL 词法内核：分句、高亮、格式化（不依赖 UI）

workbench/
  adb-jdbc           统一 JDBC 会话：连接、参数化查询/更新、事务、类型映射
                     ＋ ⭐ Dialect 抽象层：连接 / 引用 / 能力 / 内省 SQL / DDL 生成
  adb-introspect     对象列表、列定义、主键、行数、分页/排序/筛选 SQL 构造
                      ＋ 结构内省（IndexDef / ForeignKeyDef / TriggerDef / TableStructure）
                      ＋ 结构变更 SQL 生成（StructureSqlBuilder / StructurePlan）
  adb-session        连接档案（ConnectionProfile / ConnectionStore）
                      ＋ 凭据（CredentialStore / Keychain / SecretTool）
                      ＋ 连接池（HikariCP 包装）
  adb-editing        RowIdentity / CellKey / EditBuffer / EditSqlBuilder
  adb-transfer       导入导出引擎：流式 CSV/TSV 编解码、四种导出格式、字段映射与坏行报告

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

### 2.1 声明层已删除（2026-10-24）

`core/adb-declarations` 与根目录 `declarations/` 已整体删除：

- 运行时**从未读取**它（不是任何模块的 Maven 依赖），方言差异已由
  `adb-jdbc` 的 `Dialect` 抽象层（`SqliteDialect` / `PostgresDialect` / `MySqlDialect`）承担；
- 同时移除 `snakeyaml` / `json-schema-validator`、父 POM 的 module 与
  dependencyManagement 条目，以及 `DeclaredDialectConsistencyTest`；
- 基线 **755 → 712 条 / 95 → 92 类**，属主动减面，不是回归。

> 若将来要做「YAML / 声明驱动方言」，应基于现有 `Dialect` 接口重新设计，
> 不再恢复旧模块（理由与决策记录见 `NAVICAT-TODO.md` §34.1）。

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
- `AppShell.connectProfile` 打开新会话时**不关闭旧会话** → 每切换一次连接就多留一条活连接。

### 3.1 线程模型的整改方向

1. 连接建立与内省移出 JavaFX 线程，改为 `adb-db` 任务 + 回主线程刷新。
2. `adb-db` 从全局单线程改为**每连接一个执行器**，避免跨连接头阻塞。
3. `query()` / `update()` 也记录 `activeStatement`，让 `⌘.` 能真正中断数据页的加载与保存。
4. `Statement.setQueryTimeout` 落到 `JdbcSession`（当前全仓 0 命中），长查询不至于挂死。
5. `connectProfile` 切换连接前显式关闭旧会话，或复用同一 `id` 的会话。

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

**已知缺口**：视图定义编辑、表选项（引擎 / 排序规则 / 自增步长）尚不可改；
分区、表空间等运维属性不在首版范围。
（索引 / 外键 / 触发器已可增删；建表 / 清空 / 重命名 / 删表已落地；
CHECK 约束已内省并随重建搬运；PG 的 DEFAULT / COMMENT 已读回。）

---

## 6. 连接层模型（P5）

| 组件 | 职责 |
|---|---|
| `ConnectionProfile` | 连接元数据。**⛔ 不含口令**；`options` 是自由 Map（当前会明文落盘，待脱敏） |
| `ConnectionStore` | `~/.agenticdb/connections.json`；0600 + 临时文件原子替换；损坏时回退为空列表 |
| `CredentialStore` | 口令接口。`platform()` 依次尝试 macOS `security` → Linux `secret-tool` → 内存 |
| `ConnectionPool` | HikariCP 包装，池大小固定 1（保持单连接串行语义）；SQLite 直连不池化 |
| `SessionManager` | 按 id 管理多个会话；SQLite → `JdbcSession`，PG/MySQL → `ConnectionPool` |

**已知缺口**：

- macOS 写钥匙串时口令经 `-w <password>` 进 argv（`ps` 可见）。
- Windows 无凭据后端（落内存）。
- ⚠️ **`ConnectionPool` 不是池**：`getConnection()` 借出后全程不归还，`idleTimeout` /
  `maxLifetime` 形同虚设；每个连接档案一个 `HikariDataSource`。
- ⚠️ **切连接泄漏会话**：`AppShell.connectProfile` 开新会话不关旧会话。
  （多连接并存已落地；孤儿会话回收与凭据脱敏、口令走 stdin 已在 P5 补齐。）

### 6.2 方言抽象层（A1，✅ 已落地）

以前加一种数据库要改 **5 个文件**（`DialectKind` / `JdbcSession` / `ConnectionProfile` /
`SchemaService` / `StructureSqlBuilder` / `SessionManager`+`ConnectionPool`），
共 6 个 `switch` + 13 个 `if` 方言分支。漏一处就是「某个方言下功能悄悄不对」。

现在：**加一种数据库 = 写一个 `Dialect` 实现 + 在 `DialectKind` 里登记一项**。

```
DialectKind（枚举 = 持久化的判别式：connections.json 的 kind 字段）
   │  kind.dialect()
   ▼
Dialect（接口 = 行为）
   ├─ 连接：quote / jdbcUrl / driverClassName / defaultPort / pooled / usesFilePath
   ├─ 能力：supportsColumnComment / readsColumnsViaJdbcMetadata /
   │        readsIndexesViaJdbcMetadata / hasCatalog / hasSchema /
   │        rebuildsTableOnColumnChange / requiresDefaultForNotNullColumn
   ├─ 内省：columnsSql / indexesSql / indexColumnsSql / triggersSql /
   │        readDdl / readViewDdl / dependentViews / currentCatalog / currentSchema
   ├─ DDL：columnDefinition / addColumnSql / renameColumnSql / dropColumnSql /
   │        alterColumnStatements / appendColumnComment
   └─ 计划：explainSql
        ▲
   ┌────┴────┬──────────────┬───────────────┐
SqliteDialect PostgresDialect MySqlDialect
```

**三个实现承担了全部方言差异**：
SQLite 没有 catalog/schema、结果集元数据不带声明类型（要读 PRAGMA）、
`getIndexInfo` 不可用、没有列注释、改列要整表重建；
MySQL 标识符用反引号、注释写在列定义里、改列要一条 `MODIFY`；
PostgreSQL 注释用独立的 `COMMENT ON`、DDL 只能由内省生成。

`SchemaService` 与 `StructureSqlBuilder` 现在**一行方言专属的 SQL 都没有**。

**门禁**：`DialectBranchAuditTest` 扫源码，
不允许在方言层之外出现 `DialectKind.SQLITE/POSTGRESQL/MYSQL`、方言相等比较或 `switch (dialect())`。
这条门禁把「加一种库要改五个文件」的老问题**挡在门外**。

---

## 7. UI 外壳

六区：菜单栏、主工具栏、侧栏、标签栏、内容区、状态栏。

- `ActionRegistry` 是菜单、工具栏、右键、命令面板的唯一动作来源，统一 enabled/快捷键。
  **已知例外（4 处）**：侧栏树的右键菜单、侧栏导航按钮、工具栏图标按钮、
  标签栏右键菜单仍走独立路径（→ `NAVICAT-TODO.md` 4.5-8 / 4.5-9 / 4.5-10 / 4.5-29）。
- `AppShell` 负责窗口、会话、标签、状态栏、Toast；页面只通过 `Feedback` 回报。
- 设计令牌：`ui/adb-ui-core/src/main/resources/tokens/tokens.json` 是唯一真相。
  生成器：`java tools/GenTokens.java`。
  **已知缺口**：生成器只导出颜色类型令牌，**60 个非颜色令牌永远不会进 CSS**
  （JavaFX 的 looked-up color 只支持颜色，数值令牌只能走 Java 常量 ——
  `row-*` 已经这么用起来了，见 `DataGridPage.setDensity`）；
  112 个令牌里只有 **41 个**被 `app.css` 引用。
- 业务 CSS 只允许引用 `-adb-*` looked-up colors；`TokenParityTest` 会扫描颜色字面量。
- 原生窗口：`StageStyle.DECORATED`；macOS 使用系统菜单栏。

### 7.0 危险分级的三层门禁 —— 三层都落地了

`Danger` 的 javadoc 宣称「三层写门禁」，现在**三层都是真的**：

| 层 | 承诺 | 实现 |
|---|---|---|
| 1 | 语句分类 | `DangerClassifier.classify` ✅ **fail-closed**（2026-10-21 修） |
| 2 | UI 二次确认 | `QueryPage.confirmDanger` + `DataGridPage.planDanger` ✅（查询页 + 数据页保存都有） |
| 3 | **驱动侧复查** | ✅ `DbSession.execute(sql, danger)` / `update(sql, params, danger)` |

**第 1 层以前是 fail-open**，已收紧（4.5-24 / 4.5-25）：

| 以前 | 现在 |
|---|---|
| 未知语句头 → `READ_ONLY`（不拦） | 未知语句头 → `ADMIN`（最重的确认） |
| 裸 `String.split(";")` 切分 | 复用 `adb-sql` 的 `SqlSplitter`（跳过字符串 / 注释 / `BEGIN…END`） |
| `WITH` 在只读白名单 | `WITH` 体内扫写语句：`WITH … DELETE` 按 `DESTRUCTIVE` 处理 |
| `EXPLAIN ANALYZE` 判只读 | `EXPLAIN ANALYZE` 会**真的执行**语句，按内层语句分级 |
| `PRAGMA x = y` 判只读 | 赋值型 `PRAGMA` 判 `MUTATING`（它会改库的持久化设置） |

**第 3 层（驱动侧复查）是这一轮补上的**（A6）：

```java
// DbSession
static void requireDeclared(Danger declared, String sql) throws SQLException {
    Danger actual = DangerClassifier.classify(sql);
    if (actual.ordinal() > declared.ordinal()) {
        throw new SQLException("危险等级不符，语句已被拒绝：声明「" + declared.label()
                + "」，实际「" + actual.label() + "」");
    }
}
```

调用方**必须声明**这条语句的危险等级，驱动侧**自己再判一次**：
实际比声明的高 → 拒绝执行。

为什么只拦「实际更危险」：调用方可能保守地声明高等级
（把只读查询说成写数据），那不该被执行层拒绝；
但把 DELETE 说成只读 —— 那是**绕过确认**，必须拦。

这一层的价值在于：前两层都在 UI 侧，**程序化执行路径（未来的传输 / 计划任务）
若忘了走确认流程，第 3 层会兜住**。

### 7.1 主题机制：类选择器，不是换样式表

两套主题 CSS **一直都在**样式表列表里，切换主题只改根节点的 style class：

```
theme-dark.css   →  .theme-dark { -adb-bg-canvas: #0d1117; ... }
theme-light.css  →  .theme-light { -adb-bg-canvas: #ffffff; ... }
app.css          →  业务样式，引用 -adb-* 令牌
```

根节点同时带 `theme-dark` 或 `theme-light`，后代通过 looked-up color 继承。

**为什么不用 `scene.getStylesheets().setAll(...)` 换样式表**（旧实现）：
换掉样式表列表会让 JavaFX 对**整棵场景图**重做 CSS 计算，紧随其后的
`scene.snapshot()` 会拿到「样式表已换、像素还没重画」的**空白帧**
（真实窗口复现过：中央密度 0.000、109 色）。改成类选择器后，
切换主题只触发根节点的局部重算，**问题从根上消失**（4.5-23 已关闭）。

### 7.2 图标体系

- **矢量图标**：`ui/adb-ui-core` 的 `Icons` 维护 38 个手绘 `SVGPath`（16×16，1.5px
  描边），按钮、菜单、树、标签页统一走它；业务按钮里**没有任何 Unicode 图标字符**。
- **品牌图**：应用窗口图标与欢迎页使用 `app-icon-*.png`（由 `tools/GenLogo.java`
  从 SVG 生成 7 档，16–1024）；它只用于「图像」，不替代表情/控件图标。
- **门禁**：`IconSystemAuditTest` 断言按钮不以 Unicode 图标字符开头、纯图标按钮必须
  走矢量图、每个 `Icons.Icon` 至少被引用一次；`AppIconResourceAuditTest` 断言
  源码里出现的 `app-icon-*.png` 字面量都真的有对应资源（少一个就红，不再静默降级）。
- 纯图标按钮必须同时有 Tooltip 与 accessible text（`IconAuditTest`），
  否则鼠标用户与读屏用户都会失去入口。

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
| 图标 | `IconAuditTest`：图标控件同时具备 Tooltip 与 accessible text |

统一命令：

```bash
./mvnw -B clean verify
```

当前：**93 个测试类 / 720 条执行（SQLite，0 失败）/ 44 条跳过（需外部真库或 Docker）**
（2026-10-24 · 第二十一轮 · Windows 10 + JDK 27 全量实测）。

统计用工具兜底（口径问题见 4.5-28）：

```bash
./mvnw -B clean verify
java tools/TestCount.java      # 按 <testcase> 计数，并断言总数 ≥ 300
```

> ⚠️ **统计口径**：用 `@Nested` 的 4 个测试类（`QueryLimitsTest`、`DangerClassifierTest`、
> `DeclarationLoaderTest`、`DeclarationValidationTest`）在 surefire XML 里写成 `tests="0"`，
> 真实用例在**内层类**条目里。按 XML 的 `tests=` 属性统计会**少算一百多条**。
> CI 与覆盖率工具一律按 `<testcase>` 元素计数。

---

## 9. 后续里程碑

| 阶段 | 内容 |
|---|---|
| P0–P3 ✅ | 外壳、竖线、数据页、查询页 |
| P4 ✅ | 结构页：列/索引/外键/触发器/DDL 查看与安全的列级编辑 |
| P4.5 🟡 | 契约修复：快捷键补齐、结果截断提示、危险分级收紧、图标统一、动作注册表收口 |
| P5 ⬜ | 连接管理收尾：多连接并行、凭据后端、属性页、方言抽象层、PG/MySQL 真联调、声明层去留 |
| P6 ⬜ | 导入/导出向导：CSV / JSON / Excel、字段映射、预览、批处理 |
| P7 ⬜ | 打包分发、jlink / jpackage、自动更新、性能与可访问性收尾 |

> **P4.5 的真实状态**（2026-10-21 第四轮逐文件复核）：
> 4.5-4（危险分级 fail-closed）**从未实现**，4.5-2（截断）**只做了查询页**，
> 4.5-5（图标统一）**只拆了一半**。完整清单见 `NAVICAT-TODO.md` §6.3。
