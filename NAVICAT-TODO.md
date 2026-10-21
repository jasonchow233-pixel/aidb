# NAVICAT-TODO — Java 桌面端执行清单

> **唯一执行清单。** 记录当前状态、下一步、验收门禁。
> 架构见 `docs/ARCHITECTURE.md`；UI 规则见 `docs/UI-GUIDE.md`；
> 组件选型见 `docs/COMPONENT-SELECTION.md`；**Navicat 对标差距见 `docs/NAVICAT-GAP.md`**；
> **阶段规划（批次 / 顺序 / 门禁）见 `docs/NEXT-STEPS.md`**；
> **与 Navicat 的实拍对比见 `docs/NAVICAT-COMPARE.md`**（本机装了 Navicat Premium Lite 15）。
>
> 最后更新：**2026-10-21（第五轮：UI/UX 重做 U-B1…U-B5 + S5「对象」首页标签 已落地）**
> 当前基线：**P0–P4 完成，P4.5 收尾中，P5 🟡**；
> **下一动作 = U-B6**（G2 列拖拽 / G1 冻结列 / U1 catalog 树 / U2 多连接并行 …），
> 见 §13.6 与 `docs/NAVICAT-COMPARE.md`。

---

## 0. 当前状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| P0 外壳 | ✅ 完成 | 六区、菜单、工具栏、侧栏、标签栏、状态栏、命令面板、双主题、密度档位 |
| P1 竖线 | ✅ 完成 | SQLite 会话、演示库、内省、对象树、分页数据页、三态、标签隔离 |
| P2 数据页 | ✅ 完成 | 排序/筛选/条件构造器、列布局、行内编辑、撤销重做、SQL 预览保存、批量操作、详情抽屉、搜索/转置/隐藏、CSV 导出、保存反向撤销 |
| P3 查询页 | ✅ 完成 | SQL 词法/分句/格式化、RichTextFX 编辑器、查找替换、多语句结果页签、取消、错误定位、执行计划、历史 |
| P4 结构页 | ✅ 完成 | 列/索引/外键/触发器/DDL 五页签、加列/改列/重命名列/删列、两级危险确认、单事务、结构变更联动刷新 |
| P4.5 契约修复 | 🟡 收尾中 | **B0–B4 已全部落地**（安全语义 / 数据正确性 / 注册表收口 / 并发 / 图标令牌）；剩 4.5-3 已做、4.5-14 收尾、4.5-23 已做 |
| P5 连接管理 | 🟡 进行中 | 连接管理页、三步向导、测试连接、钥匙串 / Secret Service 凭据、连接档案落盘、HikariCP 连接池（PG/MySQL）；**本轮补上：凭据脱敏 + 口令走 stdin + 孤儿会话回收** |
| P6 导入导出 | 🟡 进行中 | CSV / TSV / JSON Lines / SQL 导出 + CSV / TSV 导入、字段映射、坏行报告、导入导出向导；**本轮补上：MySQL 5.7 真库联调**（`MySqlIntegrationTest` 9 例，抓出两个真 bug） |
| P7 分发 | ⬜ 计划 | jlink / jpackage、自动更新、性能与可访问性收尾 |
| **UI/UX 专项** | ⬜ **待开工** | **§13**：实测 11 个界面问题（字号 / 间距 / 圆角 / 焦点环 / 按钮层级 / 图标 / 密度 / 列头 / 连接列表）；批次 U-B1–U-B6 |

首版明确不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务、传输同步。

> **截图验收**：`docs/screenshots/` 6 张真实窗口图（外壳 / 数据页 / 查询页 / 结构页 / 连接页 / 暗色），
> 由 `ScreenshotSession`（`ADB_SHOTS` 等环境变量）可复现生成，用法见 §12.2。
> ⚠️ **当前 6 张图已过期**（缺 `⇤/⇥/跳至页`、多一列 `注释`），需按 §12.2 重拍。

---

## 1. 门禁基线

统一命令：

```bash
./mvnw -B clean verify
```

当前结果（2026-10-21 实测）：

| 场景 | 执行 | 跳过 | 失败 |
|---|---|---|---|
| **SQLite（默认）** | **437** | 30 | 0 |
| **MySQL 5.7 真库**（环境变量接入） | **451** | 16 | 0 |

**57 个测试类**。跳过的是「本机没有的外部依赖」：Testcontainers 的 PG/MySQL 容器
（无 Docker）与未配置的 PG 真库 —— **跳过不算通过**，门禁统计里单列一行。

统计口径用工具兜底：

```bash
./mvnw -B clean verify
java tools/TestCount.java      # 按 <testcase> 元素计数，并校验下限 ≥ 300
```

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `core/adb-model` | 30 | 分页/行数上限契约 |
| `core/adb-declarations` | 40 | 声明校验、快照一致性、**classpath 与源码目录对拍** |
| `core/adb-safety` | 59 | 危险分级四档、**fail-closed**、WITH 体内扫描、PRAGMA 读写、词法切分 |
| `core/adb-sql` | 31 | 词法 / 分句 / 格式化 / **括号配对** / **参数检测** |
| `workbench/adb-jdbc` | 50 | 参数化、事务、类型映射、截断探测、**取消语义**、**方言行为** |
| `testing/adb-fixtures` | 3 | 演示库固定种子 |
| `workbench/adb-introspect` | 27 | 内省、分页排序筛选 SQL、结构变更 |
| `workbench/adb-session` | 11 | 连接档案、凭据、连接池、**敏感项脱敏** |
| `workbench/adb-editing` | 19 | 编辑缓冲、SQL 生成、反向撤销 |
| `workbench/adb-transfer` | 15 | 流式 CSV 编解码、四种导出格式、导入列匹配与坏行报告 |
| `ui/adb-ui-core` | 17 | 令牌键一致 / 亮暗一致 / 无颜色字面量 / **无未使用令牌** / **CSS 尺度审计** / **焦点环审计** |
| `ui/adb-ui-shell` | 126 | 外壳、数据页、查询页、结构页、连接页、向导、渲染、CSV、**图标语义唯一**、**图标系统审计**、**注册表收口**、**保存守卫**、**工具页**、**禁用按钮可见性**、**网格交互**、**密度覆盖** |

> ⚠️ **统计口径（4.5-28）**：用 `@Nested` 的 4 个测试类（`QueryLimitsTest`、
> `DangerClassifierTest`、`DeclarationLoaderTest`、`DeclarationValidationTest`）在 surefire XML 里
> 写成 `tests="0"`，真实用例在**内层类**条目里。按 XML `tests=` 属性统计会**少算一百多条**。
> `tools/TestCount.java` 一律按 `<testcase>` 元素计数，并断言总数 ≥ 300。

---

## 2. P0 外壳（已完成）

| 项 | 证据 |
|---|---|
| 原生窗口 + 六区布局 | `AppShell` / `MainToolBar` / `SideBar` / `TabBar` / `StatusBar` |
| 原生窗口装饰 + macOS 系统菜单栏 | `StageStyle.DECORATED` + `MenuBar.setUseSystemMenuBar(true)` |
| 统一动作注册表 | `ActionRegistry` 驱动菜单、工具栏、右键、命令面板（**4 处漏网，见 4.5-8/9/10/29**） |
| 侧栏 240 / 48px、可拖动 | `SideBar` + `AppShell` |
| 双主题 + 跟随系统 + 本地记忆 | `ThemeManager` + `tokens.json` |
| 命令面板 ⌘K | `CommandPalette` |
| 密度档位（紧凑 / 标准 / 宽松） | `AppShell.applyDensity` + `app.css`（**只影响对象树，见 A19**） |
| 旧引擎/旧入口/旧规划清理 | 旧 Rust/Vue/Python 实现、脚手架、过期文档已删除 |

---

## 3. P1 竖线（已完成）

| 项 | 证据 |
|---|---|
| 演示库固定种子 | `DemoDatabase`：200 users / 500 orders / 80 products / 空表 notes + 视图 |
| 真实内省 | `SchemaService`：表/视图、列定义、主键、精确行数 |
| SQLite 会话 | `JdbcSession`：参数化查询、事务、类型映射、NULL 绑定 |
| 服务端分页/排序/筛选 | `DataSqlBuilder` + `DataQuery` |
| 对象树 → 标签 → 数据页 | `SideBar` + `TabBar` + `WorkspaceView` |
| 三态 + 状态栏 | 空态/加载/错误（错误态带「重试」）；连接、范围、耗时、脏计数 |
| 标签独立数据 | `WorkspaceView` 按 tab id 缓存页面 |
| TestFX 冒烟 | `AppShellTest`（Monocle Headless） |

---

## 4. P2 数据页（已完成）

### 4.1 查询与列

- [x] 单列/最多 3 级排序，下推 `ORDER BY`
- [x] 列头值筛选：候选值、计数、级联、NULL/空串、高基数降级
- [x] 条件构造器 + 可删除 chip + 全部清除
- [x] 列宽/顺序/显隐 + `java.util.prefs` 持久化
- [x] 首次打开按当前页内容估算列宽；列数变化时重新自适应
- [x] 主键 `[PK]`、NOT NULL `•`、类型行、Tooltip
- [x] 列头双击自适应宽度

### 4.2 编辑

- [x] 行内编辑、类型转换、空串与 NULL 分开
- [x] 只有含主键的表可编辑；视图/无主键表只读
- [x] `EditBuffer`：单元格、删除、草稿、草稿单元格、批量，统一撤销/重做/脏计数
- [x] 新增行内联草稿；克隆排除主键列
- [x] 删除/克隆/批量删除；混合「已有行 + 草稿行」只占一个撤销栈位
- [x] SQL 预览 → 单事务保存 → 刷新 → 状态栏/Toast
- [x] 保存后反向撤销：UPDATE 还原、DELETE 重新 INSERT、显式主键 INSERT 反删
- [x] 布尔列点击切换；枚举 label、日期时间、JSON 摘要、BLOB 大小、长文本截断

### 4.3 批量与视图

- [x] 行选择列、全选当前页、批量条
- [x] 批量复制 TSV、复制 INSERT、克隆、删除
- [x] 批量设 NULL / 设空串（整批一次撤销）
- [x] 本页搜索（⌘F）
- [x] 隐藏含 NULL 行、隐藏含空串行
- [x] 只读转置视图
- [x] 当前页 CSV 导出、当前页 TSV/SQL 复制
- [x] 单元格右键：编辑、复制值/TSV/INSERT/行、设为 NULL/空串、筛选、排除、查看详情

### 4.4 详情抽屉

- [x] 文本 / JSON / Hex / 图片 / 元数据五个 Tab
- [x] 大字段按 Tab 首次选中懒加载
- [x] Esc 关闭；复制当前 Tab 内容
- [x] BLOB 图片识别失败时给出明确提示

### 4.5 P2 已知限制

- 保存反向撤销对「无主键/主键为空的新增行」不完整；UI 会明确提示。
- 反向撤销只覆盖最近一次保存；发生新编辑后不再提供。
- 转置视图只读，不参与批量选择。
- 本页搜索/隐藏只作用于当前页，不改变服务端分页。

---

## 5. P3 查询页（已完成）

### 5.1 SQL 词法内核（`adb-sql`）

- [x] `SqlLexer`：关键字、标识符、字符串、数字、注释、参数、运算符、分号
- [x] 保留每个 token 的起止 offset，供高亮与错误定位
- [x] 标准 SQL / SQLite 优先：双引号、反引号、方括号标识符；`--`、`/* */` 嵌套注释；字符串按双写单引号转义
- [x] `SqlSplitter`：跳过字符串/注释/`BEGIN…END` 触发器体里的分号
- [x] 单元测试覆盖空输入、未闭合字符串、未闭合块注释、嵌套注释、Dollar quote、多语句、触发器体与 `CASE…END`
- [x] `SqlFormatter`：关键字大写、主要子句换行、字符串/注释原样保留

### 5.2 编辑器

- [x] RichTextFX `CodeArea` 接入，行号 + 当前行背景
- [x] 关键字/字符串/数字/注释/参数/运算符/标识符高亮
- [x] 查找/替换栏；`⌘F` 打开、`⌘G` 下一个、`⌃H` 聚焦替换
- [x] 格式化（`⌥⇧F`）
- [x] 注释/取消注释（`⌘/`）

### 5.3 执行

- [x] 查询页标签：连接标识、执行/取消/解释/格式化/保存按钮
- [x] `⌘R` 执行当前语句，`⇧⌘R` 执行全部；`⌘↵` / `⇧⌘↵` 同效
- [x] 多语句结果页签：结果集 / 消息 / 错误分别成页，失败后保留已完成结果
- [x] 执行取消：`Statement.cancel()` + 请求序号丢弃迟到回调
- [x] 错误定位：`ErrorLocator` 解析位置并选中编辑器 offset；约束错误给出可读说明
- [x] 执行计划：按方言生成 `EXPLAIN` / `EXPLAIN QUERY PLAN`
- [x] 查询历史：最近 50 条，去重，落 `java.util.prefs`
- [x] 结果表格复用 P2 单元格渲染器；只读、支持复制 TSV/SQL、导出 CSV
- [x] 查询写操作沿用 `DangerClassifier` 危险分级 + 执行前二次确认；`EXPLAIN` 不触发确认
      （✅ 三层门禁全部落地：分类 fail-closed + UI 确认 + **驱动侧复查**
      `DbSession.execute(sql, danger)`，见 §10 A6）

### 5.4 P3 已知限制

- 代码折叠未做；**括号匹配已做**（→ P4.6 Q1）。
- 结果列宽不持久化；结果集不可排序。
- 结果集超过 1000 行**会**提示「已截断」（4.5-2 已修，仅查询页路径）。
- 多语句逐条自动提交；失败会保留已完成语句的结果，不提供整批回滚。

---

## 6. P4 结构页（已完成）+ P4.5 契约修复（🟡 下一阶段）

### 6.1 P4 已完成项

- [x] 五个页签：列 / 索引 / 外键 / 触发器 / DDL
- [x] 真实内省：`IndexDef` `ForeignKeyDef` `TriggerDef`（SQLite PRAGMA + JDBC 元数据 + PG/MySQL 触发器查询）
- [x] 概览摘要：列 / 主键 / 索引 / 外键 / 触发器计数
- [x] DDL 只读查看 + 复制；复制 SELECT
- [x] 安全编辑：新增列、修改列（可空/默认值/注释/唯一）、重命名列、删除列
- [x] 危险操作分级：改列 / 删列两级确认并展示将要执行的 SQL
- [x] 结构变更单事务执行；失败回滚；SQLite 走整表重建（临时关外键）
- [x] 主键列禁止直接删除；列名合法性校验（`[A-Za-z_][A-Za-z0-9_$]*`）
- [x] 结构变更后联动刷新：对象树 + 所有已打开的数据页 / 结构页
- [x] 视图只读；`StructurePageTest` 门禁（加载 / 新增列 / 并发保护）

### 6.2 P4 已知限制

- 索引、外键、触发器**只读**，不能新建 / 修改 / 删除。
- 不能新建 / 重命名 / 删除表。
- CHECK 约束不内省、不展示。
- PostgreSQL 的 DEFAULT / COMMENT 不内省（`TypeMapper` 不读 `getColumns()`）。
- PostgreSQL 无 `SHOW CREATE TABLE`，DDL 由内省生成，UI 已标注「等价 DDL」。

### 6.3 P4.5 契约修复（🟡 收尾中）

> 这一组全是**已经对外承诺、但实现缺失**或**正确性缺陷**，不是新功能。
>
> ✅ = 已实现且有门禁断言 · 🟡 = 部分完成 · ⬜ = 未做
>
> **2026-10-21 第四轮：B0–B4 批次已全部落地**（安全语义 / 数据正确性 /
> 注册表收口 / 并发 / 图标令牌）。下面是逐条的实际状态。

| # | 任务 | 状态 | 落点 | 验收门禁 |
|---|---|---|---|---|
| ~~4.5-1~~ ✅ | ~~数据页补 `⌘C` 复制值 / `⌥⌘C` 复制 TSV / `⇧⌘C` 复制 INSERT / `⌘A` 全选当前页~~ **已完成** | ✅ | `DataGridPage.installShortcuts` | `DataGridPageTest.copyShortcutsAndSelectAllWork` |
| ~~4.5-2~~ ✅ | ~~结果集超过 1000 行时提示「已截断」~~ **已完成**：`RowPage` 新增 `rowsTruncated`，`JdbcSession.query()` 不再丢弃标记；数据页与候选值面板都能看到 | ✅ | `RowPage` / `JdbcSession.query` / `ColumnFilterPanel` | `JdbcSessionTest.queryPreservesTheTruncationFlag` |
| ~~4.5-3~~ ✅ | ~~数据页保存路径接入 `DangerClassifier`~~ **已完成**：`planDanger()` 取计划里最危险的一条，保存弹窗显示风险徽标，高风险额外走一级 `DestructiveConfirm` | ✅ | `DataGridPage.planDanger` | `DataGridSaveGuardTest.savePathClassifiesThePlan` |
| **4.5-24** ✅ | **`DangerClassifier` fail-open → fail-closed**：未知语句头按「管理操作」处理；`WITH` 体内扫描写语句；`MERGE`/`UPSERT`/`CALL`/`COPY`/`SET`/`DO` 不再判只读 | ✅ | `core/adb-safety/DangerClassifier` | `DangerClassifierTest.未知语句failClosed` 等 3 例 |
| **4.5-25** ✅ | **`DangerClassifier` 复用 `SqlSplitter`** 做字符串/注释安全切分（`core/adb-safety` 依赖 `adb-sql`） | ✅ | `core/adb-safety/pom.xml` | `DangerClassifierTest.字符串字面量里的分号不切碎语句` |
| **4.5-26** ✅ | **批量「设空串 / 设 NULL」不得改主键列**（补主键守卫，按钮同步禁用） | ✅ | `DataGridPage.applyColumnValue` | `DataGridSaveGuardTest.batchEditRefusesToTouchPrimaryKeyColumn` |
| **4.5-27** ✅ | **保存预览弹窗的默认按钮不得是「执行」**（`ButtonData.OTHER` + `pinCancelAsDefault`） | ✅ | `DataGridPage.buildSavePreviewDialog` | `DataGridSaveGuardTest.executeButtonIsNeverDefault` |
| ~~4.5-5~~ ✅ | ~~图标统一~~ **已完成**：`TabKind.STRUCTURE` 与数据页「结构」按钮都改 `☰`；新增「符号 → 语义唯一」门禁 | ✅ | `TabKind` / `DataGridPage` / `IconAuditTest` | `IconAuditTest.managedSymbolsMeanExactlyOneThing` |
| ~~4.5-6~~ ✅ | ~~所有图标按钮补 `setAccessibleText`~~ **已完成**（抽屉关闭按钮本轮补上） | ✅ | `SideBar` / `MainToolBar` / `CellDetailsDrawer` | `IconAuditTest.everyIconCarriesTooltipAndAccessibleText` |
| ~~4.5-7~~ ✅ | ~~危险确认弹窗的默认按钮不得是破坏性动作~~ **已完成**（含数据页保存预览） | ✅ | `DestructiveConfirm` / `DataGridPage` | `DestructiveConfirmTest` + `DataGridSaveGuardTest` |
| 4.5-8 | 侧栏树右键菜单并入 `ActionRegistry` | ✅ | `SideBar.registerObjectActions` | `ActionRegistryConvergenceTest.objectTreeContextMenuItemsComeFromTheRegistry` |
| 4.5-9 | 工具栏图标按钮绑定动作 `enabled` | ✅ | `ActionRegistry.iconButton` | `ActionRegistryConvergenceTest.toolbarIconButtonsFollowActionEnabledState` |
| 4.5-10 | `connections.manage` / `connection.reconnect` 进菜单 | ✅ | `MenuBarFactory` | `ActionRegistryConvergenceTest.previouslyOrphanActionsNowHaveMenuEntries` |
| 4.5-11 | 状态栏 `DEV` / `未选择` / `自动提交` 三个假字段接上真实数据 | ✅ | `StatusBar.setDialect/setSelection/setTransaction` | `ActionRegistryConvergenceTest.statusBar*` 3 例 |
| ~~4.5-12~~ ✅ | ~~结构页「新增列」非法输入时不关闭对话框~~ **已完成** | ✅ | `StructurePage.showColumnDialog` | `StructurePageTest` |
| 4.5-13 | `⌘.` 取消要能真正取消数据页加载 / 保存 | ✅ | `JdbcSession.query/update` + `DataGridPage.cancel()` | `JdbcSessionTest.queryRegistersTheStatementSoCancelCanInterruptIt` |
| 4.5-14 | 令牌体系收敛 | 🟡 | 已删 8 个死令牌（`env-*`）；`row-*` 已真正用起来（密度覆盖网格行高）。**剩余 60 个非颜色令牌仍未进 CSS** —— JavaFX 的 looked-up color 只支持颜色，数值令牌只能走 Java 常量 | `TokenParityTest` 增加「令牌不得有未引用项」断言 |
| ~~4.5-15~~ ✅ | ~~图标按钮 Tooltip 全覆盖~~ **已完成** | ✅ | 全 UI | `IconAuditTest` |
| ~~4.5-16~~ ✅ | ~~结构页对话框补校验~~ **已完成** | ✅ | `StructurePage` | `StructurePageTest` |
| ~~4.5-17~~ ✅ | ~~破坏性默认按钮扩展到 `AppShell`~~ **已完成** | ✅ | `AppShell` | `DestructiveConfirmTest` |
| ~~4.5-18~~ ✅ | ~~「编辑」菜单 5 个占位项~~ **已完成** | ✅ | `MenuBarFactory.editMenu` | `MenuBarFactoryTest` |
| ~~4.5-19~~ ✅ | ~~密度三档菜单走 `ActionRegistry`~~ **已完成** | ✅ | `Action` / `ActionRegistry.radioMenuItem` | `MenuBarFactoryTest` |
| ~~4.5-20~~ ✅ | ~~分页补「首页 / 末页 / 跳页」~~ **已完成** | ✅ | `DataGridPage.buildPager` | `DataGridPageTest.firstLastAndJumpPaginationWork` |
| ~~4.5-21~~ ✅ | ~~Toast 时长分级~~ **已完成**（1.8s / 6s / 4s） | ✅ | `AppShell.startToast` | `AppShellTest.toastDurationsComeInThreeTiers` |
| ~~4.5-22~~ ✅ | ~~列注释永远不显示~~ **已完成**（按方言条件显示） | ✅ | `DialectKind.supportsColumnComment()` | `StructurePageTest` |
| 4.5-23 | **主题热切换后 `scene.snapshot()` 返回空白帧** | ✅ | **从根上解决**：主题 CSS 改成**类选择器**（`.theme-dark` / `.theme-light`），切换主题只改根节点 style class，**不再换样式表列表**，整棵场景图的 CSS 重算被消除 | `AppShellTest.themeHotSwitchKeepsSnapshotPixels` + 6 张真实窗口截图 |
| 4.5-28 | **surefire 统计口径失真** | ✅ | 新增 `tools/TestCount.java`：按 `<testcase>` 计数并断言总数 ≥ 300 | `java tools/TestCount.java` |
| 4.5-29 | **标签栏右键菜单并入 `ActionRegistry`** | ✅ | `TabBar.buildTab` | `ActionRegistryConvergenceTest.tabContextMenuItemsComeFromTheRegistry` |
| 4.5-30 | **动作禁用时给出反馈** | ✅ | `Action.setOnDisabled` + `ActionRegistry.setFeedback` | `ActionRegistryConvergenceTest.disabledActionExplainsItselfInsteadOfSilentlyDoingNothing` |
| 4.5-31 | **重拍 6 张截图** | ✅ | `ScreenshotSession`（6 张已重拍，含亮/暗两套主题） | `docs/screenshots/` |

### 6.3.1 本轮新增的架构整改（✅ 已落地）

| # | 任务 | 落点 | 验收门禁 |
|---|---|---|---|
| **A19** | **密度档位覆盖数据网格行高**（以前只影响对象树）。行高取自 `row-*` 令牌，不写死在 CSS | `DataGridPage.setDensity` + `WorkspaceView.applyDensity` | `ActionRegistryConvergenceTest.densityChangesTheGridRowHeight` |
| **A21** | **切连接不泄漏会话**：没有标签页引用的会话被回收 | `AppShell.closeOrphanSessions` + `SessionManager.openIds` | 手工核对 + 现有连接测试 |
| **A13** | **macOS 钥匙串口令走 stdin**，不再进 argv（`ps` 可见） | `KeychainCredentialStore.runWithInput` | 手工核对（`ps` 不应出现口令） |
| **A14** | **`options` 敏感键落盘前剔除**（password / token / apikey …） | `ConnectionProfile.sanitizedOptions` + `ConnectionStore.write` | `ConnectionStoreTest.sensitiveOptionsNeverReachTheDisk` |
| **R4** | **`options` 不得覆盖钥匙串口令**（options 先应用，user/password 后设） | `JdbcSession.open` + `ConnectionPool.open` | `ConnectionStoreTest.optionsCannotOverrideTheKeychainPassword` |
| **A3** | **`loadFromClasspath()` 修好**：枚举目录 / jar 条目，而不是把目录当索引文件读 | `DeclarationLoader.readFromJar` / `readFromDirectory` | `DeclarationLoaderTest.classpathAndPathAgree` + jar 内实测 |
| **A23** | **死代码清理**：删 `QueryOptions`（只有测试在用）、`MainToolBar.activeConnectionId`（只写不读）、`StructurePage` 未用的 `Danger` import；`PageSize` 改为 UI 的档位来源 | `adb-model` / `MainToolBar` / `StructurePage` / `DataGridPage` | 编译无警告 + `QueryLimitsTest` |
| — | **侧栏占位接真功能**：「查询历史」接已有的 `QueryHistory`（列表 / 载入到新查询 / 清空）；「诊断」接真实表健康概览；「已保存查询」「导入导出」（P6）从侧栏撤掉，不留死 UI | `HistoryPage` / `DiagnosticsPage` / `WorkspaceView` / `SideBar` | `WorkspaceToolsPageTest` 2 例 |


### 6.3.2 连接向导按钮重构（2026-10-21，✅ 已完成）

`ConnectionWizard` 原来把 back/next/test/save 放在 Dialog 按钮栏：DialogPane 会给每个按钮挂
`setResultAndClose`（点「下一步」曾把整个向导关掉），且按钮栏按钮无法在无头测试里稳定挂载
（JavaFX 会把 DialogPane 的场景归属修正回 Dialog 内部 Scene）。现已全部挪进每一步的**内容区**，
按钮栏只留「取消」；`stepContent()` / `goNext()` / `goBack()` / `runTest()` / `commit()` /
`dialogResult()` 供测试直接驱动。校验失败时错误写在每一步都有的常驻错误行
（`#wizard-save-error`，早期只写第 3 步才有的 `review`，第 1、2 步填错用户什么都看不到）。

门禁：`ConnectionWizardTest` 5 例（三步流转 / 服务器参数 / 不关向导 / 校验留步 / 编辑回填）。

### 6.4 P4.5 附带的窗口骨架差距（源自 Navicat 实测，详见 `docs/NAVICAT-GAP.md` §0.1）

| # | 任务 | 状态 | 落点 | 验收门禁 |
|---|---|---|---|---|
| ~~S1~~ ✅ | ~~顶部改成**大图标 + 文字的功能栏**~~ **已完成**：连接 / 查询 / 表 / 导入导出 / 工具（图标在上、文字在下，Navicat 同形） | ✅ | `MainToolBar` + 6 张截图 |
| ~~S2~~ ✅ | ~~表清单元数据列~~ **已完成**：「表清单」页有 名称 / 行数 / 数据长度 / 引擎 / 创建日期；PG 走 pg_class、MySQL 走 information_schema，SQLite 没有大小与创建时间 → 诚实显示「—」 | ✅ | `Dialect.tableMetaSql` + `TableListPage` |
| ~~S3~~ ✅ | ~~对象树补视图 / 函数 / 其它分组节点~~ **已完成**：对象树现在有 表 / 视图 / **过程 / 函数 / 触发器** 五类分组；过程与函数走 JDBC 元数据，触发器各方言给 SQL；演示库带一个触发器，演示环境里看得见 | ✅ | `SchemaService.listObjects` + `Dialect.triggersListSql` + `SideBar.buildTreeModel` |
| ~~S4~~ ✅ | ~~侧栏搜索框移到底部~~ **已完成**：与 ★（只看收藏）、« 折叠同一行 | ✅ | `SideBar.buildExpanded` |
| ~~S5~~ ✅ | ~~标签栏第一个是**「对象」首页标签**（不可关闭）~~ **已完成**：常驻标签永远排第一、**没有关闭钮**（不是藏起来，是根本不放进场景图）、右键菜单里也没有「关闭」；关掉其它标签后**落回它**而不是空白；内容是**按类型分组的对象总览**（表/视图/过程/函数/触发器），点一下就打开 | ✅ | `TabBar.openPermanent` + `ObjectHomePage` | `ObjectHomeTabTest`（4 例） |
| ~~S6~~ ✅ | ~~菜单栏加「收藏夹」顶级菜单~~ **已完成**：文件/编辑/视图/数据库/**收藏夹**/工具/窗口/帮助；收藏夹面板可打开 | ✅ | `MenuBarFactory` + `FavoritesPage` |
| ~~S7~~ ✅ | ~~窗口状态记忆补齐剩余项~~ **已完成**：窗口位置 x/y、上次用的连接、**上次打开的标签页**（重开后接着用） | ✅ | `AppShell.saveWindowState` / `restoreLastTabs` |
| ~~S8~~ ✅ | ~~标签栏右键菜单补齐~~ **已完成**：加「复制表名 / 复制 SELECT」 | ✅ | `TabBar.buildTab` |

---

## 7. P5 连接管理（🟡 进行中）

### 7.1 已完成

- [x] 连接管理页：列表、搜索（名称/主机/库/用户）、只看收藏、★ 标记、已连接标记
- [x] 新建 / 编辑连接共用三步向导：类型与名称 → 连接参数 → 测试与保存
- [x] 向导可回退、不丢输入；测试连接返回可读错误
- [x] 凭据存储：macOS Keychain（`security`）/ Linux Secret Service（`secret-tool`）/ 内存兜底
- [x] 连接档案落盘 `~/.agenticdb/connections.json`（0600，原子写）
- [x] 复制连接、删除连接（二次确认，同时删钥匙串口令）
- [x] HikariCP 连接池（池大小 1，保持单连接串行语义）；SQLite 直连不池化
- [x] 对象树 / 标签页 / 状态栏按连接隔离（`TabItem.connectionId`）
- [x] `ConnectionWizardTest` / `ConnectionsPageTest` / `ConnectionStoreTest` / `ConnectionPoolTest`

### 7.2 待办

- [x] **多连接对象树**：⭐ 对象树改成 Navicat 的「我的连接」模型 —— **所有连接在同一棵树里**，
      展开即懒加载该连接的对象；打开表时按**节点所属连接**建标签（不是当前激活的连接）
- [ ] **多连接并行**：去掉 `AppShell` 单一 `activeProfile` / `session`，改为按连接 id 取会话
- [x] **切连接不泄漏会话**（✅ A21）：`AppShell.closeOrphanSessions` 回收没有标签页引用的会话
- [ ] **Windows 凭据**：WinCred / DPAPI 后端（`CredentialStore.platform()` 补分支）
- [ ] **连接属性页**：读超时、SSL、字符集、连接池大小、附加参数（`options` 要有 UI）
- [x] **口令不落 argv**：macOS 改用 `security -w` 从 stdin 读（`KeychainCredentialStore.runWithInput`）
- [x] **`options` 敏感字段脱敏**：落盘前剔除口令类键（`ConnectionProfile.sanitizedOptions`）
- [x] **`options` 不得覆盖钥匙串口令**（✅ R4）：`JdbcSession` / `ConnectionPool` 改成 options 先应用、user/password 后设
- [x] **schema 切换器**：✅ 已落地 —— `SchemaService.listSchemas` + 侧栏下拉（>1 个 schema 才显示）；PG 走 `SET search_path`。**catalog 树仍未做**（MySQL 的多库场景）
- [ ] **连接重命名 / 分组 / 标签**
- [ ] **最近使用排序**：列表按 `lastUsedAt` 倒序
- [ ] **连接属性测试**：连接超时、取消、重连、错误归类（连接断开 / 权限不足 / 死锁 / 超时）
- [x] **PG / MySQL 真实联调**：✅ Testcontainers 集成测试 + **环境变量驱动的外部真库**
      （`MySqlIntegrationTest` 9 例 / `ExternalRealDatabaseTest` 6 例真跑）
- [x] ⭐ **连接 URL 必须带超时**（`connectTimeout` / `socketTimeout`）——
      没有超时的话，服务端 TLS 握手一卡，客户端会**无限期**阻塞在 socket 读上，
      界面直接冻住、连报错都没有。实测这台 MySQL 的握手就会**间歇性卡住**
      （6 次里挂 2 次），所以还加了**通信失败重试**（只重试通信类失败，
      SQL / 断言错一律立刻失败，不掩盖真问题）
- [ ] **PG / MySQL 内省补齐**：DEFAULT、COMMENT、序列、CHECK、`getExportedKeys`
- [x] **方言抽象层**：✅ 已做 —— `Dialect` 接口 + 三个实现；`SchemaService` / `StructureSqlBuilder` 里没有方言专属 SQL；`DialectBranchAuditTest` 扫源码守着
- [ ] **`declarations/` 接入运行时或删除**（见 §9 A2/A3）

---

## 8. P6 导入导出（🟡 进行中）

### 8.1 已完成

- [x] **新模块 `workbench/adb-transfer`**：导入导出引擎（纯 Java，可单测）
- [x] **流式 CSV/TSV 编解码**（`CsvCodec`，RFC 4180）：带引号的字段里可以有分隔符、
      换行、双引号；**读写都不把整个文件读进内存**
- [x] **导出**：CSV / TSV / JSON Lines / SQL 四种格式，**流式**（一次只读一批行）
      - SQL 格式导出的是**可重放的 INSERT 语句**（Navicat 的做法：导出的文件能直接灌到别的库）
      - JSON 保留类型（数字不加引号、null 写成 null）
- [x] **导入**：CSV / TSV，**批量提交**（每批 commit —— 后面某批坏了不会回滚前面几百万行）
- [x] **字段映射**：按表头名字自动匹配目标列；也可按顺序一一对应
- [x] **坏行报告**：类型不匹配这类错误是**执行批次时**才爆出来的（绑参数时不知道类型对不对），
      所以整批失败 → 回滚这一批 → **逐行重试**把坏的挑出来，坏的跳过、好的留下，
      并报告「第 N 行：原因」（绝不悄悄丢数据）
- [x] **导出向导**（`ExportWizard`）：范围（当前页 / 选中行 / 整表）+ 格式 + 目标文件
- [x] **导入向导**（`ImportWizard`）：文件 → 目标表 → 字段映射 → 预览 → 执行
- [x] **入口**（`TransferHub`）：侧栏「导入 / 导出」不再是空壳页 —— 选表 → 导入 / 导出
- [x] **数据页导出菜单**新增「导出向导（整表 / 多种格式）」

### 8.2 待办

- [ ] **xlsx（Excel）**：Apache POI（进父 POM `dependencyManagement`）
- [ ] **导出前提示行数上限**（`QueryLimits.exportRequiresConfirmation` 接进向导）
- [ ] **导入的类型转换**：现在是「全按字符串绑」，INTEGER 列塞非数字会报错（已能跳过并报告），
      但不该让用户手工保证类型
- [ ] **导出来源扩展**：不只是表，还要能导查询结果
- [x] **已保存查询库**：✅ 已落地 —— 查询页「存为命名查询」+ 侧栏「已保存查询」列表
      （打开 / 重命名 / 删除，按名覆盖），落 `java.util.prefs`

---

## 9. P7 分发与收尾（⬜）

- [ ] jlink 自定义运行时
- [ ] jpackage：macOS `.app` / `.dmg`，Windows `.msi`，Linux `.deb`
- [ ] 自动更新与版本检查（可选）
- [ ] 启动性能：冷启动 < 2s，首屏不阻塞
- [ ] 可访问性：焦点顺序、屏幕阅读器标签、对比度
- [ ] 崩溃日志与用户可读诊断
- [ ] 安装包签名与公证
- [ ] 主题「跟随系统」覆盖 Windows / Linux
- [ ] 密度档位覆盖数据网格行高（当前只影响对象树）
- [ ] **主题探测缓存**（🆕 A22）：`ThemeManager.systemPrefersDark()` 每次应用主题都 shell out 一次 `defaults`（400ms 超时）

---

## 10. 架构整改清单

> 来源：`docs/NAVICAT-GAP.md` §6.2。每条都要有单测或门禁。

| # | 问题 | 整改方向 | 阶段 | 状态 |
|---|---|---|---|---|
| ~~A1~~ | ~~无 Dialect 抽象层，SQL 生成散落 6 个 `switch` + 13 个 `if`~~ **已做**：`Dialect` 接口 + `SqliteDialect`/`PostgresDialect`/`MySqlDialect`，承担全部方言差异 | P5 | ✅ |
| A2 | `declarations/` 1500 行是死代码 | **决策**：本轮先把它**变成真功能**（loader 修好 + classpath/jar 双路径 + 对拍测试），运行时驱动它留给 A1 方言抽象层 | P5 | 🟡 |
| A3 | `loadFromClasspath()` 不可用（jar 索引解析错误） | **已修**：枚举目录（爆炸 classpath）/ jar 条目（打包），两条路径实测一致 | P4.5 | ✅ |
| ~~A4~~ | ~~`paramStyle` / `identifierQuote` 被硬编码~~ **标识符引用已进 `Dialect`**；**参数风格仍固定 `?`**（`paramStyle: numbered` 未实现） | P5 | 🟡 |
| ~~A5~~ | ~~元数据只读当前 schema~~ **schema 已落地**：`getSchemas()` 接上了，侧栏有 **schema 切换器**（>1 个才显示），PG 用 `SET search_path` 切。**catalog 仍未接**；`getExportedKeys()` / `getColumns()` 也没有 | P5 | 🟡 |
| ~~A6~~ | ~~危险分级第三层门禁不存在；数据页保存不分类~~ **已做**：数据页保存已接入分级；**第三层（驱动侧复查）已落地** —— `DbSession.execute(sql, danger)` / `update(sql, params, danger)` 强制声明等级，驱动侧再判一次，实际更危险就拒绝 | P4.5 | ✅ |
| A7 | 危险分级 fail-open（未知 → 只读） | **已做**：未知语句头按管理操作处理；`WITH` 体内扫描写语句 | P4.5 | ✅ |
| A8 | 多语句切分不看字符串/注释 | **已做**：复用 `adb-sql` 的 `SqlSplitter` | P4.5 | ✅ |
| A9 | 取消只覆盖查询页 | **已做**：`query()` / `update()` 也记录 `activeStatement` | P4.5 | ✅ |
| A10 | 没有语句超时 | `Statement.setQueryTimeout` | P5 | ⬜ |
| A11 | 结果截断静默 | **已做**：`RowPage` 带 `rowsTruncated` + UI 提示 | P4.5 | ✅ |
| A12 | 连接池是 1 槽装饰器，连接不归还 | 真正归还连接，或明确改名 `SingleConnectionHolder` | P5 | ⬜ |
| A13 | macOS 钥匙串口令经 argv 泄漏 | **已做**：改 stdin 传入 | P5 | ✅ |
| A14 | `options` 明文落盘 | **已做**：落盘前剔除敏感键；且不得覆盖钥匙串口令 | P5 | ✅ |
| A15 | 连接与内省跑在 UI 线程 | 移到 `adb-db` 执行器 | P5 | ⬜ |
| A16 | 全局单线程执行器 | 每连接一个执行器 | P5 | ⬜ |
| ~~A17~~ | ~~79/120 令牌未被引用~~ **已做**：120 → **60 个，全部被用到**（app.css 引用或 Java 常量消费），并有门禁守着 | P4.5 | ✅ |
| A18 | 「跟随系统」只探测 macOS | 跨平台探测 | P7 | ⬜ |
| A19 | 密度档位只影响对象树 | **已做**：覆盖网格行高（行高取自 `row-*` 令牌） | P4.5 | ✅ |
| A20 | `MAX_CELL_BYTES` 声明未用 | 真正限制单元格体积，或删常量 | P4.5 | ⬜ |
| **A21** 🆕 | **切连接泄漏会话** | **已做**：回收没有标签页引用的会话 | P5 | ✅ |
| **A22** 🆕 | **主题探测每次 shell out** | 缓存系统主题探测结果 | P7 | ⬜ |
| **A23** 🆕 | **死代码 / 死引用** | **已做**：删 `QueryOptions`、修 `DeclarationCheckMojo` 悬空引用、`PageSize` 改为 UI 档位来源、清 `MainToolBar` 只写字段与未用 import | P4.5 | ✅ |
| **A24** 🆕 | **surefire 统计口径失真** | CI 按 `<testcase>` 计数 + 断言总数下限 | P4.5 | ⬜ **（4.5-28）** |
| **A25** 🆕 | **`declarations` 与运行时方言不一致**（`dameng.yaml` 有达梦，`DialectKind` 没有） | 随 A1 一起处理 | P5 | ⬜ |

---

## 11. P4.6 数据网格与查询页进阶（⬜ 新增，Navicat 对标）

> 不属于「契约修复」，是**功能差距**。按收益排序；每项一个门禁。

### 11.1 数据网格

| # | 任务 | 说明 |
|---|---|---|
| G1 | **冻结 / 固定列** | 前 N 列固定，不随横向滚动移走 |
| G2 | **列头拖拽重排** | 顺序**已经能持久化**，但没有任何运行时改序入口 |
| ~~G3~~ ✅ | ~~列汇总脚注行~~ **已完成**：「视图 → 本页统计」显示行数与数值列的合计/平均/最小/最大（算的是**当前页**） | ✅ | `DataGridPage.updateSummary` |
| G4 | **单元格区域选择** | 拖选矩形 / Shift 范围选 / 复制矩形区 |
| ~~G5~~ ✅ | ~~编辑后跳到下一格~~ **已完成**：Tab / Shift+Tab / ↓ / ↑，跳过去直接开始编辑下一格 | ✅ | `DataGridPage.moveAfterEdit` |
| G6 | **自增列插入** | 新增行时主键列留空交给数据库自增 |
| ~~G7~~ ✅ | ~~行级导航~~ **已完成**：分页条加「跳至行」（**跨页也能跳**，行号是绝对行号）+ `⌘↓`/`⌘↑` 跳本页首末行 | ✅ | `DataGridPage.jumpToRow` |
| ~~G8~~ ✅ | ~~批量条加「导出选中行」~~ **已完成** | ✅ | `DataGridPage.exportSelectedCsv` |

### 11.2 查询页

| # | 任务 | 说明 |
|---|---|---|
| ~~Q1~~ ✅ | ~~括号匹配~~ **已完成**（折叠未做）：新增 `SqlBrackets` —— 字符串与注释里的括号不算；SQLite 的 `[]` 是标识符引用不是括号 | ✅ | `SqlBrackets` + `SqlBracketsTest` |
| ~~Q2~~ ✅ | ~~变量 / 参数替换~~ **已完成**：查询页「变量」按钮列出语句里的 `?` 与 `:name`，填值后**参数化绑定**执行（值不进 SQL 文本） | ✅ | `SqlParameters` + `VariableDialog` + `DbSession.execute(sql, params, danger)` |
| ~~Q3~~ ✅ | ~~结果集排序~~ **已完成**：结果列可排序 | ✅ | `QueryPage.resultTab` |
| ~~Q4~~ ✅ | ~~结果列宽持久化~~ **已完成**：按「连接名 + 列名」记住 | ✅ | `QueryPage.savedColumnWidth` |
| Q5 | **结果集分页** | 现在一次性装进 `ObservableList`，硬上限 1000 |
| Q6 | **代码片段 / 模板库** | 与 P6「已保存查询库」合并做 |

---

## 12. 门禁与完成定义

统一命令：

```bash
./mvnw -B clean verify
```

| 门禁 | 要求 |
|---|---|
| 编译 | Java 25，父 POM 统一版本，子模块不带 `<version>` |
| 计数 | `java tools/TestCount.java` 按 `<testcase>` 计数并断言总数 ≥ 300 |
| 单元 | 编辑、SQL、渲染、CSV、词法器、结构变更、连接档案、菜单门禁必须有断言 |
| 数据 | 演示库固定种子；SQL 下推与事务保存有真实 SQLite 断言 |
| UI | TestFX + Monocle；外壳、数据页、批量条、详情抽屉、查询页、结构页、连接页、连接向导 |
| 样式 | `TokenParityTest`：键一致、亮暗一致、无颜色字面量、令牌无未引用项 |
| **样式审计** | `AppCssAuditTest`（🆕 §13.7）：`app.css` 内无字面量字号 / padding / border-radius；字号只 7 档、圆角只 3 档、间距只 7 档；`9px` 出现 0 次 |
| **焦点 / 交互态** | `FocusRingAuditTest`（🆕 §13.7）：每个可聚焦组件类都有 `:focused` 规则；`-fx-opacity` 只出现 1 处 |
| **密度覆盖** | `DensityCoverageTest`（🆕 §13.7）：密度同时改 4 张表的行高 |
| 图标 / 无障碍 | `IconAuditTest`：图标控件同时具备 Tooltip 与 accessible text + **符号语义唯一** + **无中文词图标** + 图标均来自 `Icons` + `Icons` 无死常量 |
| 菜单 | `MenuBarFactoryTest`：菜单项全部来自 `ActionRegistry`，无永久禁用死项 |
| 文档 | README、本清单、架构、UI 指南、对标文档、NEXT-STEPS 与实际实现同步 |
| 对标 | `docs/NAVICAT-GAP.md` 的差距项要么进清单，要么写明「明确不做」 |
| **计数** | **测试总数 ≥ 300**（防止 surefire 口径少算，见 4.5-28；工具 `tools/TestCount.java`） |

**完成定义**：功能可用 + 门禁断言 + 文档同步 + 无旧架构残留。
写不出断言的功能视为未完成。

### 12.1 「要么接要么删」原则

侧栏的「查询历史 / 已保存查询 / 诊断 / 导入导出」四个入口、
`StatusBar` 的 `DEV` / `未选择` / `自动提交` 三个字段、
`core/adb-declarations` 整个模块 —— 都是**只有位置没有内容**的占位。
按本项目规则：**占位要么接上真功能，要么删掉**。

其中「查询历史」是特例：**功能已经存在且可用**（`QueryHistory`，prefs 持久化），
只是侧栏按钮开错了页面 —— 接线即可，不需要新写。

### 12.2 真实窗口截图验收（2026-10-21 新增，✅ 可用）

TestFX 冒烟只证明「控件树正确」；**像素对不对**要真实窗口截图。`ScreenshotSession`
挂在 `App.start()` 末尾，由环境变量驱动，可脚本化复现：

| 变量 | 作用 |
|---|---|
| `ADB_SHOTS` | 输出目录（**用绝对路径**，相对路径会落到模块工作目录） |
| `ADB_SHOTS_NAME` | 文件名（小写 + `.png`） |
| `ADB_SHOTS_WAIT` | 显示后等待毫秒数（数据页 / 查询页 / 结构页要 5000–6000） |
| `ADB_SHOTS_CLICK` | 截图前要点掉的节点 id（如 `sidebar-connections`） |
| `ADB_OPEN` / `ADB_QUERY=1` / `ADB_STRUCTURE` | 预置页面 |
| `ADB_THEME_FORCE` | `light` / `dark`，启动时强制主题且不写偏好 |

```bash
# ⚠️⚠️ 必须先 install —— 否则拍到的是**旧代码**！
#    `javafx:run -pl bootstrap/adb-desktop`（不带 -am）会从 ~/.m2 取
#    adb-ui-shell 的**已安装 jar**，不重新构建它。
#    实测踩过：改了半天的 CSS，截图里一点没变 —— 因为 jar 还是两小时前的。
./mvnw -B install -DskipTests

ADB_THEME_FORCE=light ADB_SHOTS=$PWD/docs/screenshots \
  ADB_SHOTS_NAME=p2-users ADB_OPEN=users ADB_SHOTS_WAIT=5000 \
  ./mvnw -B -pl bootstrap/adb-desktop javafx:run
```

> 拍完**核对 jar 时间戳**：`ls -la ~/.m2/repository/io/agenticdb/adb-ui-shell/1.0.0-SNAPSHOT/*.jar`
> —— 必须是刚才那次 install 的时间。

**已知坑（都踩过）**：
1. **主题热切换后 `scene.snapshot()` 会拿到空白帧**（4.5-23，未修）——截图一律用
   `ADB_THEME_FORCE` 在启动时定主题，不要靠点击切换。
2. 主题偏好会持久化，**暗色截图要第一个拍**，否则后续全是暗色。
3. macOS 没有 `timeout` 命令，长跑用工具自带超时。
4. 拍完用像素密度自检（PIL：非背景像素占比），空白帧 / 主题错误一眼可见。
5. **⚠️ 现有 6 张图已过期**（缺 `⇤/⇥/跳至页`、多一列 `注释`）—— 按 4.5-31 重拍。
6. ⚠️⚠️ **拍之前必须 `./mvnw -B install -DskipTests`** —— `javafx:run -pl`（不带 `-am`）
   会用 `~/.m2` 里的**旧 jar**，拍出来的是旧界面（实测踩过，浪费了一轮排查）。
7. **环境变量要真的 export** —— 写成 shell 函数参数是不会传给 java 进程的；
   用 `env ADB_OPEN=users ./mvnw …` 或直接前置赋值。

---

## 13. UI/UX 重做（🆕 第五轮，Navicat 对标）

> **详细分析、规范与截图对比见 `docs/UI-ROADMAP.md`；批次顺序见 `docs/NEXT-STEPS.md` §2。**
>
> 本节是**界面专项的执行清单**，每条都有可执行的门禁。
> ✅ = 已实现且有门禁断言 · 🟡 = 部分完成 · ⬜ = 未做
>
> ⚠️ 上一轮的问题：界面差距一直**不在任何功能清单上**，所以一直没被发现。
> 本节把它变成可勾选的条目。

### 13.0 实测到的问题清单（问题 ≠ 任务，用来验收）

| # | 问题 | 实测数据 | 归属 |
|---|---|---|---|
| UX-01 | 字号散落 **13 种**（79 处声明），最小 9px | 12px×32 / 11px×21 / **10px×9** / **9px×3** / 12.5px×1 | U-B1 |
| UX-02 | 间距 **47 种**硬编码内边距（68 处声明） | `0` `2 4` `2 8` `3 8` `4 8` `4 10` `6 16` `12` `0 2` `0 4` `0 8` `0 16 6 16` `48` `16 18` …… | U-B2 |
| UX-03 | 圆角 **5 个**硬编码值，无规则 | **3 / 5 / 6 / 8 / 10** 写死在 26 处 | U-B2 |
| UX-04 | **字号 / 间距 / 圆角根本没有令牌** | 60 个令牌里 **40 个是颜色** | U-B1 / U-B2 |
| UX-05 | **只有 3 处 focus 环** | 18 条 `:hover` vs **3 条** `:focus` | U-B3 |
| UX-06 | 禁用态整体 opacity 压暗 | `.adb-button:disabled { -fx-opacity: 0.45 }` | U-B3 |
| UX-07 | 网格工具栏 **11 个按钮 10 个一样** | ghost = 透明底 + 透明边 | U-B5 |
| UX-08 | 图标**符号与中文词混用** | `◈ ⌨ ▦ ⇅ ◆` vs `关闭/重试/打开/复制/删除/保存` | U-B4 |
| UX-09 | 密度只覆盖 **2 处** | `setFixedCellSize` 仅在 `DataGridPage` | U-B5 |
| UX-10 | 连接管理页是 **ListView** | `ConnectionsPage.list = new ListView<>()` | U-B5 |
| UX-11 | **每列常显**筛选按钮 | `Button funnel = new Button("▽")` | U-B5 |

### 13.1 U-B1 排版层 —— ✅ 已完成

- [x] 令牌新增 **9 级字号**：`fs-2xs` 10 / `fs-xs` 11 / `fs-sm` 12 / `fs-md` 13 / `fs-lg` 14 /
      `fs-xl` 16 / `fs-2xl` 20 / `fs-3xl` 32 / `fs-4xl` 40
- [x] `GenTokens` 生成字号令牌（暗色同值 —— 字号不随主题变）；令牌总数 60 → **80**
- [x] `app.css` 全部字面量字号改走阶梯（**79 处声明、13 种 → 9 种**）
- [x] **9px 全面废除**（`.adb-rail__dialect` / `.adb-tab__dirty` / `.adb-tab__close`）
- [x] 10px 只允许留在**单字符徽标 / 角标**；状态栏 / 列类型行 / 功能栏文字 / 分组标题 **升到 11px**
- [x] 删掉 **12.5px 孤例**
- [x] 字体族统一 `-fx-font-family: "System"`（本来就是，已核对）
- [x] 欢迎页大标题 60px → 40px；页面标题 18px → 16px；空状态图标 34px → 32px

**门禁**：`AppCssAuditTest`（🆕）7 例 —— 字号必须落在 9 档阶梯上、无 10px 以下、
阶梯与 `tokens.json` 逐字一致。

### 13.2 U-B2 间距 + 圆角层 —— ✅ 已完成

- [x] 令牌新增 **7 档间距**：`sp-1` 2 / `sp-2` 4 / `sp-3` 8 / `sp-4` 12 / `sp-5` 16 / `sp-6` 24 / `sp-7` 32
- [x] 令牌新增 **3 档圆角 + pill**：`r-sm` 3 / `r-md` 5 / `r-lg` 8 / `r-full` 999
- [x] `app.css` 的 **47 种**硬编码 padding（68 处声明）→ **31 种组合、全部落在 7 档上**
- [x] `app.css` 的 **3 / 5 / 6 / 8 / 10** 圆角（26 处声明）→ **3 / 5 / 8 / 999**，
      按**角色**分派：控件 5、面板 8、小元素 3、徽标与标签片 999（药丸形）
- [x] 按钮内边距统一 `4px 12px`；图标按钮 `4px 8px`；输入框 `4px 12px`；面板 `12px`
- [x] CSS 的 padding / radius **全部补上 `px` 单位**（原来是无单位的 `4 10`），
      否则和令牌对不上、门禁也判不了

**门禁**：`AppCssAuditTest` 断言间距 / 圆角都落在阶梯上，且阶梯与 `tokens.json` 逐字一致。

### 13.3 U-B3 焦点与交互态层 —— ✅ 已完成

- [x] focus 规则 **3 条 → 26 条**：按钮 / 图标钮 / 搜索触发器 / 功能项 / 侧栏导航 / 收藏 /
      标签 / Toast 动作 / 筛选钮 / 树 / 表格 / 文本框 / 文本域 / 下拉 / 列表 / 树视图 /
      表视图 / 复选框 / 单选钮 / 滚动条 / 滑块
- [x] 焦点环 = 边框换 `-adb-border-focus` + 一圈 `dropshadow` 光晕；
      **刻意不改边框宽度**（宽度一变，获得焦点时控件会大 1px 抖一下）
- [x] 焦点环在**暗色主题**下同样可见（`-adb-border-focus` 两套主题都是亮蓝）
- [x] 禁用态改用 `text-disabled` + `bg-disabled` + **保留边框**
- [x] ⭐ **显式把禁用态的透明度还原成 1** —— Modena 给所有 `:disabled` 控件加了
      `-fx-opacity: 0.4`，不抵消的话禁用的按钮会「整个消失」
      （实测：数据页的「保存」按钮在截图里是一片空白）
- [x] `-fx-opacity` 只在命令面板遮罩用一次；别处要么不写，要么显式写 `1`
- [ ] 加载中按钮：内嵌 spinner + 禁用 + **保持原宽度**（不跳动）—— 留到 U-B5

**门禁**：`FocusRingAuditTest`（🆕）6 例 —— 每个可聚焦组件类都有 `:focused`、
输入类与表格 / 树也有、opacity 不得用来压暗内容、禁用控件必须抵消 Modena 的透明度、
hover 规则不得被删、focus 规则不得少于 10 条；
`DisabledButtonVisibilityTest`（🆕）3 例 —— 禁用的主按钮 / ghost 按钮文字不能是白色、
透明度必须是 1。**已验证能抓到回归**（故意删掉抵消规则 → 门禁失败）。

### 13.4 U-B4 图标层 —— ✅ 已完成

- [x] 新建 `ui/adb-ui-core` 的 `Icons` 类：**38 个手绘 SVGPath 矢量图标**
      （16×16 视框、1.5px 描边、圆头圆角、缩放时描边反向补偿）
- [x] 图标颜色交给 CSS（`.adb-icon` / `.adb-icon--filled`）→ **切主题自动跟着变**
- [x] **所有「中文词 / Unicode 符号」按钮**改为矢量图 + Tooltip + accessibleText
      （关闭 / 重试 / 打开 / 复制 / 删除 / 保存 / 筛选 / 编辑 / 清除 / 刷新 / 撤销 / 重做 /
      结构 / 列 / 视图 / 导出 / 导入 / 执行 / 停止 / 分页 / 主题 / 设置 / 星标 / 历史 /
      诊断 / 查询 / 数据库 …）
- [x] 功能栏（`◈ ⌨ ▦ ⇅ ◆`）、标签栏、对象树、分页器、状态栏脏标记 —— **全部换成矢量图标**
- [x] 领域层（`ObjectKind`）**不再带图标** —— 图标是 UI 的映射，领域不该知道图标长什么样
- [x] 数据页工具栏改成 **Navicat 形态的图标条**（纯图标 + Tooltip + accessibleText）
- [x] 图标按钮**只放图标**，文字进 Tooltip；命中区 ≥ 28×28

**门禁**：`IconSystemAuditTest`（🆕 4 例）——
① 按钮文字不许以 Unicode 图标字符开头；② 按钮不许只由图标字符组成；
③ `Icons` 每个常量都必须被用到（无死图标）；④ 领域层不许带图标字面量。

> ⭐ **顺带修掉一个真问题**：加了图标后，工具栏 11 个「图标+文字」按钮的总宽超过了可用宽度，
> JavaFX 把按钮文字压成「…」。
> 根因不是图标太大，而是**工具栏本来就塞不下 11 个带文字的按钮** ——
> 解法是改成图标条（Navicat 形态），而不是把图标画小。

### 13.5 U-B5 布局与组件层

- [ ] 数据网格工具栏**按语义分 3 组**（浏览 / 编辑 / 视图），组间加分隔线
- [ ] 工具栏**最多 7 个常显**，其余收进「更多」菜单
- [ ] 新增**记录导航条**（首 / 上 / 下 / 末 记录 + 记录号输入），放在网格**上方左侧**
- [ ] 列头筛选按钮 `▽` 改为**悬停列头才浮现**（常态只有列名 + 排序角标）
- [ ] `ConnectionsPage` 由 `ListView` 改 **`TableView`**（名称 / 主机 / 端口 / 数据库 / 用户 / 状态），可排序、可拖列宽
- [ ] **密度全局生效**：数据网格 / 查询结果网格 / 表清单 / 结构页三张表 **4 处**同步行高
- [ ] 空状态补**一个明确的下一步按钮**（primary）+ 说明
- [ ] 应用图标（标签栏 / 窗口标题）

**门禁**：`DataGridPageTest`（工具栏分组 / 记录导航条 / 列头筛选悬停才可见）·
`ConnectionsPageTest`（表格列与排序）· `DensityCoverageTest`（🆕，断言密度同时改 4 张表的行高）。

### 13.5.1 S5「对象」首页标签 —— ✅ 已完成（Navicat 实拍推上来的 P0）

- [x] 标签栏第一个是**常驻的「对象」标签**，永远排第一
- [x] **不可关闭**：`close()` 直接拒绝；标签节点**根本没有关闭钮**
      （不是 `setVisible(false)` —— 留在场景图里会干扰「查找可见节点」这类操作）
- [x] 右键菜单里**不出现**「关闭」项（不是「点了没反应」）
- [x] 关掉其它标签后**落回对象首页**，不是一片空白
- [x] 停在它上面时，「关闭标签」动作**禁用**
- [x] 内容页 `ObjectHomePage`：按类型分组列出当前连接的**全部对象**
      （表 / 视图 / 过程 / 函数 / 触发器），点一下打开对应数据页
- [x] 启动时它就是**默认活动标签**（Navicat 启动时也停在「对象」上）

**门禁**：`ObjectHomeTabTest`（🆕 4 例：永远排第一 / 不可关闭 / 没有关闭钮 /
关掉其它的后它还在 / 右键菜单无「关闭」）。

> 💡 顺带修掉三个被新特性**改变预期**的旧测试（`AppShellTest`）：
> 标签栏现在永远可见、关掉数据页后落回对象首页、脏标记要**按标签**找
> （对象标签排在前面，它的脏标记是不可见的）。

### 13.6 U-B6 功能补齐层（与前 5 批并行）

| # | 任务 | 门禁 | 状态 |
|---|---|---|---|
| **G2** | 列头**拖拽重排**（顺序已能持久化，只缺运行时入口） | `DataGridPageTest.columnDragReorderPersists` | ⬜ |
| **G1** | **冻结列**（两表四路同步：滚动 / 选择 / 排序 / 编辑） | `DataGridPageTest.frozenColumnsStayPut` | ⬜ |
| **G4** | 单元格**区域选择**（拖选 / Shift 范围 / 复制矩形） | `DataGridPageTest.rectangleSelectionCopies` | ⬜ |
| **G6** | 自增列插入（新增行时主键留空交给数据库） | `DataGridSaveGuardTest.autoIncrementColumnStaysEmpty` | ⬜ |
| **Q5** | 查询结果**分页**（现在硬上限 1000） | `QueryPageTest.resultPagination` | ⬜ |
| **Q6** | 代码片段库（与已保存查询合并） | `SavedQueriesPageTest.snippetLibrary` | ⬜ |
| **U1** | **catalog 树**（MySQL 多库；MySQL 的 schema = 数据库，`USE` 不管用，需切后重连） | `SchemaServiceTest.catalogSwitch` | ⬜ |
| **U2** | **多连接并行**（`AppShell` 单 `activeProfile`/单 `session` → 按连接 id 取） | `SessionManagerTest` 扩用例 | ⬜ |
| **U3** | 连接属性页（超时 / SSL / 字符集 / 连接池 / 附加参数） | `ConnectionWizardTest.propertiesStep` | ⬜ |
| **U4** | 索引 / 外键 / 触发器**可编辑**（当前只读） | `StructurePageTest.indexEditor` | ⬜ |
| ~~U5~~ ✅ | ~~对象首页标签（S5，见 §6.4）~~ **已完成** | `ObjectHomeTabTest`（4 例） | ✅ |
| **U6** | 状态栏补：当前用户 / 服务器版本 / 延迟 | `ActionRegistryConvergenceTest.statusBar*` | ⬜ |

### 13.7 本节新增门禁汇总

| 门禁 | 类 | 断言 |
|---|---|---|
| `AppCssAuditTest` | 🆕 | `app.css` 内无字面量字号 / padding / border-radius / 颜色；字号只 7 档；圆角只 3 档；间距只 7 档；`9px` 出现 0 次 |
| `FocusRingAuditTest` | 🆕 | 每个可聚焦组件类都有 `:focused` 规则；`-fx-opacity` 只出现 1 处 |
| `DensityCoverageTest` | 🆕 | 密度同时改 4 张表的行高 |
| `IconAuditTest`（扩展） | 改 | 无中文词图标；图标均来自 `Icons`；`Icons` 无死常量 |
| `TokenParityTest`（扩展） | 改 | 字号 / 间距 / 圆角令牌全部被引用 |
