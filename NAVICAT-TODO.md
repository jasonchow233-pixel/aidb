# NAVICAT-TODO — Java 桌面端执行清单

> **唯一执行清单。** 记录当前状态、下一步、验收门禁。
> 架构见 `docs/ARCHITECTURE.md`；UI 规则见 `docs/UI-GUIDE.md`；
> 组件选型见 `docs/COMPONENT-SELECTION.md`；**Navicat 对标差距见 `docs/NAVICAT-GAP.md`**；
> **阶段规划（批次 / 顺序 / 门禁）见 `docs/NEXT-STEPS.md`**。
>
> 最后更新：**2026-10-21（第四轮：逐文件代码复核 + B0–B5 全部落地）**
> 当前基线：**P0–P4 完成，P4.5 收尾中，P5 🟡**；下一动作见 `docs/NEXT-STEPS.md` §1 批次 B2。

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
| P6 导入导出 | ⬜ 计划 | CSV / JSON / Excel 向导、字段映射、预览 |
| P7 分发 | ⬜ 计划 | jlink / jpackage、自动更新、性能与可访问性收尾 |

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

当前结果：**38 个测试类 / 341 条测试用例，0 失败 / 0 错误 / 0 跳过**（2026-10-21 实测）。

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
| `core/adb-sql` | 17 | 词法 / 分句 / 格式化 |
| `workbench/adb-jdbc` | 42 | 参数化、事务、类型映射、截断探测、**取消语义**、**方言行为** |
| `testing/adb-fixtures` | 3 | 演示库固定种子 |
| `workbench/adb-introspect` | 24 | 内省、分页排序筛选 SQL、结构变更 |
| `workbench/adb-session` | 11 | 连接档案、凭据、连接池、**敏感项脱敏** |
| `workbench/adb-editing` | 19 | 编辑缓冲、SQL 生成、反向撤销 |
| `ui/adb-ui-core` | 4 | 令牌键一致 / 亮暗一致 / 无颜色字面量 |
| `ui/adb-ui-shell` | 92 | 外壳、数据页、查询页、结构页、连接页、向导、渲染、CSV、**图标语义唯一**、**注册表收口**、**保存守卫**、**工具页** |

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
      （⛔ 第三层「驱动侧复查」未实现 → 见 §10 A6；⛔ **分类器本身仍是 fail-open → 见 4.5-24**）

### 5.4 P3 已知限制

- 括号匹配 / 代码折叠未做（→ P4.6 Q1）。
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
| S1 | 顶部改成**大图标 + 文字的功能栏**（连接 / 新建查询 / 表 / 视图 / 查询 / 导入导出 / 工具） | ⬜ | `MainToolBar` | 截图核对；功能入口不再藏在二级菜单里 |
| S2 | **表清单元数据列**：行数 / 数据长度 / 引擎 / 创建日期 / 注释 | ⬜ | `SchemaService` + `SideBar` | 表清单能显示行数与大小 |
| S3 | 对象树补 **视图 / 函数 / 其它** 分组节点 | ⬜ | `SideBar.buildTreeModel` + `SchemaService.listObjects` | 树上有三类分组，节点可展开 |
| S4 | 侧栏搜索框移到**底部**，与 ★ 收藏、折叠按钮同一行 | ⬜ | `SideBar.buildExpanded` | 截图核对 |
| S5 | 标签栏第一个是**「对象」首页标签**（不可关闭） | ⬜ | `TabBar` + `WorkspaceView` | 打开对象首页后可关闭其它标签 |
| S6 | 菜单栏加 **「收藏夹」顶级菜单** | ⬜ | `MenuBarFactory` | 菜单树单测 |
| S7 | **窗口状态记忆补齐剩余项**：宽高 / 侧栏宽度 / 折叠态**已实现**；还缺 **窗口位置 x/y、上次使用的连接、最近打开的对象** | ⬜ | `AppShell.saveWindowState` + `restoreWindow` | 单测：写入后重启动复现上次位置与连接 |
| S8 | **标签栏右键菜单补齐**：关闭 / 关闭其他 / 全部关闭**已实现**；还缺 **复制表名 / 复制 SELECT** | ⬜ | `TabBar.buildTab` | 单测：右键菜单能复制表名到剪贴板 |

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

- [ ] **多连接并行**：去掉 `AppShell` 单一 `activeProfile` / `session`，改为按连接 id 取会话
- [x] **切连接不泄漏会话**（✅ A21）：`AppShell.closeOrphanSessions` 回收没有标签页引用的会话
- [ ] **Windows 凭据**：WinCred / DPAPI 后端（`CredentialStore.platform()` 补分支）
- [ ] **连接属性页**：读超时、SSL、字符集、连接池大小、附加参数（`options` 要有 UI）
- [x] **口令不落 argv**：macOS 改用 `security -w` 从 stdin 读（`KeychainCredentialStore.runWithInput`）
- [x] **`options` 敏感字段脱敏**：落盘前剔除口令类键（`ConnectionProfile.sanitizedOptions`）
- [x] **`options` 不得覆盖钥匙串口令**（✅ R4）：`JdbcSession` / `ConnectionPool` 改成 options 先应用、user/password 后设
- [ ] **schema / catalog 树**：对象树按 schema 分组，可切换
- [ ] **连接重命名 / 分组 / 标签**
- [ ] **最近使用排序**：列表按 `lastUsedAt` 倒序
- [ ] **连接属性测试**：连接超时、取消、重连、错误归类（连接断开 / 权限不足 / 死锁 / 超时）
- [ ] **PG / MySQL 真实联调**：Testcontainers 集成测试（`postgresql` + `mysql` 模块进父 POM）
- [ ] **PG / MySQL 内省补齐**：DEFAULT、COMMENT、序列、CHECK、`getExportedKeys`
- [x] **方言抽象层**：✅ 已做 —— `Dialect` 接口 + 三个实现；`SchemaService` / `StructureSqlBuilder` 里没有方言专属 SQL；`DialectBranchAuditTest` 扫源码守着
- [ ] **`declarations/` 接入运行时或删除**（见 §9 A2/A3）

---

## 8. P6 导入导出（⬜）

- [ ] 导入向导：文件 → 目标表 → 字段映射 → 预览 → 执行
- [ ] 导出向导：当前结果 / 整表 / SQL 查询结果
- [ ] 导出格式：CSV / JSON / xlsx（Apache POI，进父 POM `dependencyManagement`）
- [ ] 类型转换与错误行报告
- [ ] 批量提交大小、失败策略（跳过 / 终止）
- [ ] 大文件流式处理，不一次性进内存
- [ ] 导出前提示行数上限（`QueryLimits.exportRequiresConfirmation`）
- [ ] 侧栏「导入 / 导出」空壳入口接上真实向导
- [ ] **整表导出**（🆕）：数据页导出现在只有「当前页」，没有「整表 / 条件结果」
- [ ] **已保存查询库**（🆕）：侧栏「已保存查询」空壳要接真实功能（命名 + 落盘 + 载入）
- [ ] **查询历史侧栏入口**（🆕）：侧栏「查询历史」现在开的是空壳页，而真正的 `QueryHistory` 已有且可用 —— 接线即可

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
| A5 | 元数据只读当前 schema | 引入 `getSchemas()` / `getCatalogs()` / `getExportedKeys()` / `getColumns()` | P5 | ⬜ |
| A6 | 危险分级第三层门禁不存在；数据页保存不分类 | `DbSession.execute(sql, danger)` 驱动侧复查 | P4.5 | 🟡 |
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
| A17 | 79/120 令牌未被引用（其中 60 个非颜色令牌从未进 CSS） | 收敛令牌集或真正用起来 | P4.5 | 🟡 **（删了 8 个死令牌；`row-*` 已用起来）** |
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
| G3 | **列汇总脚注行** | SUM / AVG / COUNT / MIN / MAX |
| G4 | **单元格区域选择** | 拖选矩形 / Shift 范围选 / 复制矩形区 |
| G5 | **编辑后跳到下一格** | Tab → 右一格，↓ → 下一行同列 |
| G6 | **自增列插入** | 新增行时主键列留空交给数据库自增 |
| G7 | **行级导航** | 首行 / 末行 / 跳到第 N 行（现在只有页级） |
| G8 | **批量条加「导出选中行」** | 现在批量条只有复制 |

### 11.2 查询页

| # | 任务 | 说明 |
|---|---|---|
| Q1 | **括号匹配 / 代码折叠** | RichTextFX 已有能力，未接 |
| Q2 | **变量 / 参数替换** | `?` / `:name` 绑定面板 |
| Q3 | **结果集排序** | 现在 `column.setSortable(false)` |
| Q4 | **结果列宽持久化** | 现在固定 140px |
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
| 图标 / 无障碍 | `IconAuditTest`：图标控件同时具备 Tooltip 与 accessible text + **符号语义唯一** |
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
ADB_THEME_FORCE=light ADB_SHOTS=$PWD/docs/screenshots \
  ADB_SHOTS_NAME=p2-users ADB_OPEN=users ADB_SHOTS_WAIT=5000 \
  ./mvnw -B -pl bootstrap/adb-desktop javafx:run
```

**已知坑（都踩过）**：
1. **主题热切换后 `scene.snapshot()` 会拿到空白帧**（4.5-23，未修）——截图一律用
   `ADB_THEME_FORCE` 在启动时定主题，不要靠点击切换。
2. 主题偏好会持久化，**暗色截图要第一个拍**，否则后续全是暗色。
3. macOS 没有 `timeout` 命令，长跑用工具自带超时。
4. 拍完用像素密度自检（PIL：非背景像素占比），空白帧 / 主题错误一眼可见。
5. **⚠️ 现有 6 张图已过期**（缺 `⇤/⇥/跳至页`、多一列 `注释`）—— 按 4.5-31 重拍。
