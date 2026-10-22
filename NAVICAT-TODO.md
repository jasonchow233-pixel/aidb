# NAVICAT-TODO — Java 桌面端执行清单

> **唯一执行清单。** 记录当前状态、下一步、验收门禁。
> 架构见 `docs/ARCHITECTURE.md`；UI 规则见 `docs/UI-GUIDE.md`；
> 组件选型见 `docs/COMPONENT-SELECTION.md`；**Navicat 对标差距见 `docs/NAVICAT-GAP.md`**；
> **阶段规划（批次 / 顺序 / 门禁）见 `docs/NEXT-STEPS.md`**；
> **与 Navicat 的实拍对比见 `docs/NAVICAT-COMPARE.md`**（本机装了 Navicat Premium Lite 15）；
> **界面规格书见 `docs/UI-VISUAL-SPEC.md`**（第十轮：字号/颜色/图标/布局/组件/弹窗/交互态/键盘）；
> **对外汇报版见 `docs/EXEC-SUMMARY.md`**；**GitHub 开源对标见 `docs/GITHUB-COMPARE.md`**。
>
> 最后更新：**2026-10-22（第十三轮：表清单 8 列 + 密度 24/28/34 + 去斑马纹/假行线 + 等比例并排图）**
> 当前基线：**P0–P4 ✅ / P5 🟡 / P6 🟡**；门禁 **631 条 / 84 类 / 0 失败**（2026-10-22 末实测，35 条跳过）。
>
> ⭐ **本轮（第十三轮）是「对齐 Navicat 观感」的一轮，不是功能扩张。**
> 逐像素量了 Navicat 的表清单：8 列、19px 行高、纯白行、1px 行线、灰色侧栏。
> 已落地：表清单 8 列、对象首页 9 列、密度 24/28/34、去斑马纹/空行假线、表头 12px。
> 完整报告见 **`docs/NAVICAT-PARITY-2026-10-V5.md`**。
>
> 📌 第六轮（历史）做过一次全量核查：4 处文档失真 + 4 个真实缺陷（含 2 个丢数据）+ 1 整块能力空白，
> 完整清单见 **§14**；与 Navicat 的逐域对比见 `docs/NAVICAT-GAP.md`。
>
> **第十一轮已完成：D3（CHECK 约束）✅ · B5-S2（外键可增删）✅ · B5-S3（触发器可增删）✅ · B5-S4（表注释可读可改）✅**，
> 并顺带修掉一个**潜伏很久的真 bug**（带外键的表改列必失败）。
> **O2 ✅（函数/过程定义体，只读标签页）**；
> **第十二轮已完成**：Q6 代码片段 ✅ · A25 声明/运行时一致（白名单 + 门禁）✅ ·
> A15/A16 线程模型 ✅ · P7（jpackage + 崩溃日志）✅ · 并修掉「同一连接被两个线程共用」
> 与「窗口尺寸不夹到屏幕内」两个真实缺陷。
> **第十二轮·续三已完成 U3 ✅ 连接属性页**（弹窗套 TabPane）。
> **下一动作 = N1–N3（表清单工具条 / 右键菜单 / 多选批量）→ U1 catalog 树 → Q5+V-9 → G4 → G1**。
> 详见 §22 与 `docs/NAVICAT-PARITY-2026-10-V5.md` §5。
>
> 📦 **新增 `scripts/`**：`dev.sh`（一键编译启动）· `build.sh`（门禁）· `shots.sh`（重拍截图）。
> 它们把 §12.2 记录的三个坑（不 install 拍到旧界面 / JDK 版本错埋在日志深处 / macOS 无 timeout）
> 固化进了脚本。见 §12.3。

---

## 0. 当前状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| P0 外壳 | ✅ 完成 | 六区、菜单、工具栏、侧栏、标签栏、状态栏、命令面板、双主题、密度档位 |
| P1 竖线 | ✅ 完成 | SQLite 会话、演示库、内省、对象树、分页数据页、三态、标签隔离 |
| P2 数据页 | ✅ 完成 | 排序/筛选/条件构造器、列布局、行内编辑、撤销重做、SQL 预览保存、批量操作、详情抽屉、搜索/转置/隐藏、CSV 导出、保存反向撤销 |
| P3 查询页 | ✅ 完成 | SQL 词法/分句/格式化、RichTextFX 编辑器、查找替换、多语句结果页签、取消、错误定位、执行计划、历史 |
| P4 结构页 | ✅ 完成 | 列/索引/外键/触发器/DDL 五页签、加列/改列/重命名列/删列、**索引可增删（§16.2）**、**列注释读回（§16.1）**、两级危险确认、单事务、结构变更联动刷新 |
| P4.5 契约修复 | 🟡 收尾中 | **B0–B4 已全部落地**（安全语义 / 数据正确性 / 注册表收口 / 并发 / 图标令牌）；剩 4.5-3 已做、4.5-14 收尾、4.5-23 已做 |
| P5 连接管理 | 🟡 进行中 | 连接管理页、三步向导、测试连接、钥匙串 / Secret Service 凭据、连接档案落盘、HikariCP 连接池（PG/MySQL）；**本轮补上：凭据脱敏 + 口令走 stdin + 孤儿会话回收** |
| P6 导入导出 | 🟡 进行中 | CSV / TSV / JSON Lines / SQL 导出 + CSV / TSV 导入、字段映射、坏行报告、导入导出向导；**本轮补上：MySQL 5.7 真库联调**（`MySqlIntegrationTest` 9 例，抓出两个真 bug） |
| P7 分发 | 🟡 进行中 | ✅ jpackage（`scripts/package.sh`）+ 崩溃日志（`CrashLog`）；⬜ jlink（自动模块暂不可行）/ 自动更新 / 性能 |
| **UI/UX 专项** | ✅ **U-B1–U-B5 已完成，U-B6 进行中** | **§13–§16 / §22**：U-B1–U-B5 全落地；S1 数值展示 / S2 刷新按钮 / D1 函数报错 / X1–X2 空态 / **T1–T4 表级生命周期** / **D2·D4 注释读回** / **B5-S1 索引可编辑**；**第十三轮 Navicat 表清单 8 列 + 密度 24/28/34**；U-B6（G1/G4/Q5/…）进行中 |

首版明确不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务、传输同步。

> **截图验收**：`docs/screenshots/` 9 张真实窗口图（外壳 / 数据页 / 查询页 / 结构页 /
> 连接页 / 暗色 / 表清单 / **新建表弹窗** / **新建索引弹窗**），
> 由 `ScreenshotSession`（`ADB_SHOTS` 等环境变量）可复现生成，用法见 §12.2。
> 弹窗两张靠 `ADB_NEW_TABLE=1` / `ADB_INDEX=1` 启动开关（§16.3）。

---

## 1. 门禁基线

统一命令：

```bash
./mvnw -B clean verify
```

当前结果（2026-10-22 末实测）：

| 场景 | 执行 | 跳过 | 失败 |
|---|---|---|---|
| **SQLite（默认，2026-10-22 第十三轮后）** | **631** | 35 | 0 |
| **MySQL 5.7 真库**（需环境变量） | 单独跑，不在默认门禁 | — | — |

**84 个测试类**。跳过的是「本机没有的外部依赖」：Testcontainers 的 PG/MySQL 容器
（无 Docker）与未配置的 PG / MySQL 真库 —— **跳过不算通过**，门禁统计里单列一行。

> 📌 **最新口径（2026-10-22，第十三轮后）**：**631 条 / 84 类 / 0 失败**（`./mvnw -B clean verify`
> 全绿 + `java tools/TestCount.java`；另有 35 条跳过，不计入 631）。

统计口径用工具兜底：

```bash
./mvnw -B clean verify
java tools/TestCount.java      # 按 <testcase> 元素计数，并校验下限 ≥ 300
```

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `core/adb-model` | 30 | 分页/行数上限契约 |
| `core/adb-declarations` | 40 | 声明校验、快照一致性、**classpath 与源码目录对拍** |
| `core/adb-safety` | 68 | 危险分级四档、**fail-closed**、WITH 体内扫描、PRAGMA 读写、词法切分 |
| `core/adb-sql` | 31 | 词法 / 分句 / 格式化 / **括号配对** / **参数检测** |
| `workbench/adb-jdbc` | 57 | 参数化、事务、类型映射、截断探测、**取消语义**、**方言行为** |
| `testing/adb-fixtures` | 3 | 演示库固定种子 |
| `workbench/adb-introspect` | 109 | 内省、表元数据 8 列、分页排序筛选 SQL、结构变更、**真库契约（跳过 14）** |
| `workbench/adb-session` | 14 | 连接档案、凭据、连接池、**敏感项脱敏** |
| `workbench/adb-editing` | 19 | 编辑缓冲、SQL 生成、反向撤销 |
| `workbench/adb-transfer` | 15 | 流式 CSV 编解码、四种导出格式、导入列匹配与坏行报告 |
| `ui/adb-ui-core` | 24 | 令牌键一致 / 亮暗一致 / 无颜色字面量 / **无未使用令牌** / **CSS 尺度审计** / **焦点环审计** |
| `ui/adb-ui-shell` | 221 | 外壳、数据页、查询页、结构页、连接页、向导、渲染、CSV、**表清单 8 列**、**图标语义唯一**、**注册表收口**、**保存守卫**、**禁用按钮可见性**、**网格交互**、**密度覆盖** |

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
- ✅ CHECK 约束已能**内省 + 展示 + 重建搬运**（第十一轮）。
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

- [x] jpackage：**`scripts/package.sh`**（一键出包：app-image / dmg / msi / deb）
      —— **实测通过**：产出 `AgenticDB.app`（167 MB，含运行时），**能真正启动**（进程存活、无启动崩溃）
- [x] 崩溃日志：`CrashLog` → `~/.agenticdb/logs/crash-*.log`
- [ ] jlink 自定义运行时 —— ⛔ **暂不可行**：RichTextFX / ControlFX 是**自动模块**，jlink 收不进去；
      等它们模块化（或换成不带自动模块的编辑器）再加 jlink profile
- [ ] 自动更新与版本检查（可选）
- [ ] 启动性能：冷启动 < 2s，首屏不阻塞
- [ ] 可访问性：焦点顺序、屏幕阅读器标签、对比度
- [x] 崩溃日志与用户可读诊断（`CrashLog`）
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
| A10 | ~~没有语句超时~~ | ✅ **已完成（第十二轮）**：`QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS=30`（上限 600）+ 每条语句 `setQueryTimeout` + 超时转人话 | P5 | ✅ |
| A11 | 结果截断静默 | **已做**：`RowPage` 带 `rowsTruncated` + UI 提示 | P4.5 | ✅ |
| A12 | ~~连接池是 1 槽装饰器，连接不归还~~ | ✅ **已完成（第十二轮）**：`PooledSession.close()` 先归还连接再关池 | P5 | ✅ |
| A13 | macOS 钥匙串口令经 argv 泄漏 | **已做**：改 stdin 传入 | P5 | ✅ |
| A14 | `options` 明文落盘 | **已做**：落盘前剔除敏感键；且不得覆盖钥匙串口令 | P5 | ✅ |
| A15 | ~~连接与内省跑在 UI 线程~~ | ✅ **已完成（第十二轮）**：`connectProfile` 改为后台连接 + `Platform.runLater` 刷新 | P5 | ✅ |
| A16 | ~~全局单线程执行器~~ | ✅ **已完成（第十二轮）**：`DbExecutors` 每连接一条执行器 | P5 | ✅ |
| ~~A17~~ | ~~79/120 令牌未被引用~~ **已做**：120 → **60 个，全部被用到**（app.css 引用或 Java 常量消费），并有门禁守着 | P4.5 | ✅ |
| A18 | ~~「跟随系统」只探测 macOS~~ | ✅ **已完成（第十二轮）**：Windows 注册表 / Linux gsettings·kdeglobals | P7 | ✅ |
| A19 | 密度档位只影响对象树 | **已做**：覆盖网格行高（行高取自 `row-*` 令牌） | P4.5 | ✅ |
| A20 | `MAX_CELL_BYTES` 声明未用 | 真正限制单元格体积，或删常量 | P4.5 | ⬜ |
| **A21** 🆕 | **切连接泄漏会话** | **已做**：回收没有标签页引用的会话 | P5 | ✅ |
| **A22** 🆕 | ~~**主题探测每次 shell out**~~ | ✅ **已完成（第十二轮）**：探测结果缓存，只探一次 | P7 | ✅ |
| **A23** 🆕 | **死代码 / 死引用** | **已做**：删 `QueryOptions`、修 `DeclarationCheckMojo` 悬空引用、`PageSize` 改为 UI 档位来源、清 `MainToolBar` 只写字段与未用 import | P4.5 | ✅ |
| **A24** 🆕 | **surefire 统计口径失真** | CI 按 `<testcase>` 计数 + 断言总数下限 | P4.5 | ⬜ **（4.5-28）** |
| **A25** 🆕 | ~~**`declarations` 与运行时方言不一致**~~ | ✅ **已完成（第十二轮）**：不删声明，改为**显式白名单 + 门禁**（`DeclaredDialectConsistencyTest`） | P5 | ✅ |

---

## 11. P4.6 数据网格与查询页进阶（⬜ 新增，Navicat 对标）

> 不属于「契约修复」，是**功能差距**。按收益排序；每项一个门禁。

### 11.1 数据网格

| # | 任务 | 说明 |
|---|---|---|
| G1 | **冻结 / 固定列** | 前 N 列固定，不随横向滚动移走 |
| ~~G2~~ ✅ | ~~列头拖拽重排~~ **已完成**（`f1334cf`，见 §13.6） |
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

- [x] focus 规则 **3 条 → 30 条**：按钮 / 图标钮 / 搜索触发器 / 功能项 / 侧栏导航 / 收藏 /
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

- [x] ✅ **数据网格工具栏按语义分 3 组**（浏览 / 编辑 / 视图），组间加分隔线
- [x] ✅ **记录导航条**（首 / 上 / 下 / 末 + 记录号输入），放在网格**上方左侧**
- [x] ✅ **列头筛选按钮 `▽` 悬停列头才浮现**（常态只有列名 + 排序角标；被筛选的列常显）
- [x] ✅ **`ConnectionsPage` 改 **`TableView`**（名称 / 主机 / 端口 / 数据库 / 用户 / 状态），可排序、可拖列宽
- [x] ✅ **密度全局生效**：数据网格 / 查询结果网格 / 表清单 / 结构页四张表同步行高
- [ ] ⬜ **工具栏「最多 7 个常显，其余收进更多」** — 关键规则 ①，未逐项验收
- [ ] ⬜ **空状态**补「明确的下一步按钮（primary）+ 说明」 — E1–E6 已用 `EmptyState` 五参版补齐主按钮；仅剩 E5–E9 待补
- [ ] ⬜ **应用图标**（标签栏 / 窗口标题）— 未做（`UI-ROADMAP.md` §1.12 「附带发现」）

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
| ~~G2~~ ✅ | ~~列头**拖拽重排**（顺序已能持久化，只缺运行时入口）~~ **已完成**（commit `f1334cf`；`DataGridPage` 按下/拖动/松开三段 + 右键 nudge + 落盘。**上一版文档误标为 ⬜，本轮核查纠正**） | `GridInteractionTest` | ✅ |
| **G1** | **冻结列**（两表四路同步：滚动 / 选择 / 排序 / 编辑） | `DataGridPageTest.frozenColumnsStayPut` | ⬜ |
| **G4** | 单元格**区域选择**（拖选 / Shift 范围 / 复制矩形） | `DataGridPageTest.rectangleSelectionCopies` | ⬜ |
| ~~G6~~ ✅ | ~~自增列插入（新增行时主键留空交给数据库）~~ **已完成** | `GridInteractionTest` + `MySqlIntegrationTest`（真库） | ✅ |
| **Q5** | 查询结果**分页**（现在硬上限 1000） | `QueryPageTest.resultPagination` | ⬜ |
| **Q6** | 代码片段库（与已保存查询合并） | ✅ **已完成（第十二轮）**：`SavedQueries` 加 `kind`（查询/片段）· 查询页「存为片段 / 插入片段」 | `SavedQueriesTest`（+2 例） |
| **U1** | **catalog 树**（MySQL 多库；MySQL 的 schema = 数据库，`USE` 不管用，需切后重连） | `SchemaServiceTest.catalogSwitch` | ⬜ |
| **U2** | **多连接并行** | ✅ **已完成（第十二轮）**：页面早就按标签取会话；**真正的堵点**是 `SessionManager.open` 会「关掉重开」→ 改成**复用**（新增 `reopen` 给「重新连接」） | `SessionManagerTest`（3 例：复用 / 重开 / 两连接并存） |
| **U3** | 连接属性页（超时 / SSL / 字符集 / 附加参数） | ✅ **已完成（第十二轮·续三）**：`ConnectionPropertiesDialog` = **弹窗里套 TabPane**（常规/SSL/高级，形态依 §4 裁定）；值进 `ConnectionProfile.options`（`queryTimeout` 由 JdbcSession 消费，其余作 JDBC 属性） | `ConnectionPropertiesDialogTest`（4 例） |
| **U4** | 索引 / 外键 / 触发器**可编辑**（当前只读） | `StructurePageTest.indexEditor` | ⬜ |
| ~~U5~~ ✅ | ~~对象首页标签（S5，见 §6.4）~~ **已完成** | `ObjectHomeTabTest`（4 例） | ✅ |
| **U6** | 状态栏补：当前用户 / 服务器版本 / 延迟 | ✅ **已完成（第十二轮）**：`Dialect.serverInfoSql()`（三方言）+ 状态栏「服务器」段（版本 · 用户），后台读不阻塞 | `StatusBarTest` |

### 13.7 本节新增门禁汇总

| 门禁 | 类 | 断言 |
|---|---|---|
| `AppCssAuditTest` | 🆕 | `app.css` 内无字面量字号 / padding / border-radius / 颜色；字号只 7 档；圆角只 3 档；间距只 7 档；`9px` 出现 0 次 |
| `FocusRingAuditTest` | 🆕 | 每个可聚焦组件类都有 `:focused` 规则；`-fx-opacity` 只出现 1 处 |
| `DensityCoverageTest` | 🆕 | 密度同时改 4 张表的行高 |
| `IconAuditTest`（扩展） | 改 | 无中文词图标；图标均来自 `Icons`；`Icons` 无死常量 |
| `TokenParityTest`（扩展） | 改 | 字号 / 间距 / 圆角令牌全部被引用 |

---

## 14. 第六轮全量核查（2026-10-21）— 缺陷、空白与文档失真

> ⭐ **本节的来源**：逐文件核对「文档说了什么」与「代码实际做了什么」，
> 覆盖对象能力、入口、页面、空态、状态栏、脚本六条线。
> 门禁基线当时是 **443 条 / 57 类 / 0 失败** —— **全绿不代表没洞**，
> 下面 D1–D4 四个缺陷全都活在「测试没覆盖的路径」上。
>
> 逐域的 Navicat 对比见 `docs/NAVICAT-GAP.md` §7。
>
> **编号约定**：`D` = 真实缺陷（会报错或丢数据）· `T` = 表级生命周期 ·
> `O` = 对象类型 · `S` = 结构页对象 · `X` = 空态与入口。

### 14.1 先纠正 4 处文档失真

文档本身在骗人，这比功能缺失更危险 —— 它让人以为做过了。

| # | 失真 | 真相 | 证据 |
|---|---|---|---|
| **F1** | §11.1 / §13.6 把 **G2 列头拖拽重排**标为 ⬜ 未完成 | **已完成**：按下 / 拖动 / 松开三段 + 右键 nudge + 落盘全在 | commit `f1334cf`；`DataGridPage` 按下 1867-1874、拖动 1875-1885、松开 1886-1893、`moveColumnTo` 1707、`reorderColumn` 1747、落盘 1604。**已在本轮改回 ✅** |
| **F2** | §6.2 说「不能新建/重命名/删除表」只是「已知限制」 | 实际是**整块能力空白**，且底层生成器是现成的 | `StructureSqlBuilder.createTableSql`（:178-224）已处理单/复合主键、UNIQUE、DEFAULT、FK、ON UPDATE/DELETE —— **只差 UI 入口和一个 `StructurePlan`**。见 §14.3 |
| **F3** | §9 P7 与 §7 未提**任何脚本** | 启动要 4 步手工敲，且有两个必踩的坑 | 见 §14.7（本轮已补 `scripts/`） |
| **F4** | §13.6 把 **U4 索引可编辑**列为「待办」 | 不只是「没做」—— `createIndexSql` 是 **private**，唯一调用方是 SQLite 重建的索引恢复，**MySQL/PG 用户永远碰不到** | `StructureSqlBuilder.java:226`（private）· 唯一调用点在 `:136` 的 `rebuildPlan()` 内，而 `rebuildPlan()` 仅 `dialect.rebuildsTableOnColumnChange()` 为真时进入（:69），**只 SQLite** |

---

### 14.2 🔴 D 类：四个真实缺陷（**优先于任何新功能**）

> 这四个的共同点：**用户点一下就会出错或丢东西**，而且没有任何提示。

| # | 缺陷 | 触发路径（用户视角） | 根因 | 验收门禁 |
|---|---|---|---|---|
| **D1** | 点对象首页的「函数 / 过程 / 触发器」**必然报错** | 对象首页 → 点 `函数` 分组里任意一个 chip → 开出一个数据页 → `SELECT * FROM <函数名>` → 「对象不存在」 | `ObjectHomePage.java:177` 的 `onOpen.accept(object.name())` **只传名字、不传 kind**；`AppShell.openObject()`（:222-228）无条件开 `TabKind.GRID`，注释里还写着「（表 / 视图）」 | `ObjectHomePageTest.openingACallableDoesNotOpenAGrid`（🆕） |
| **D2** | ⭐ MySQL 上「修改列」会**静默抹掉该列的注释** | 结构页 → 选中一列 → 「修改」→ **不动任何输入框** → 确定 | ① `TypeMapper.java:50` 构造 `ColumnDef` 时 comment 硬编码 `null`，**从不读 `REMARKS`** → 结构页「注释」列恒空、对话框恒预填空；② `MySqlDialect.columnDefinition`（:211）因 `comment == null` 不拼 `COMMENT '...'` → 生成的 `MODIFY COLUMN` 直接抹掉原注释 | `ColumnCommentRoundTripTest`（🆕）：MySQL 上 `写注释 → 读回 → 改列 → 再读回`，断言注释仍在 |
| ~~**D3**~~ ✅ | ~~SQLite 改一次列，**该表所有 CHECK 约束静默消失**~~ **已完成（第十一轮）** | — | **已修**：`CheckDef` + `CheckParser`（从 CREATE TABLE 原文解析，SQLite 只有这一条路）+ `Dialect.parsesChecksFromDdl/checksSql` + 重建时搬运 CHECK + 结构页「检查」页签 | `CheckConstraintTest`（🆕 7 例，含「改列后约束**仍然拦得住违规数据**」） |
| **D4** | PostgreSQL 上**无法清空列注释** | 结构页 → 改列 → 把注释框清空 → 确定 | `PostgresDialect.java:220` 在 `comment == null` 时**直接 return**，不生成 `COMMENT ON COLUMN ... IS NULL` | 并入 `ColumnCommentRoundTripTest` |

> **为什么 D2 最要紧**：它是「改了 A 却丢了 B」型缺陷，用户不会立刻察觉，
> 等发现时注释已经没了、且**没有撤销路径**（结构变更不在 `EditBuffer` 里）。
> 修法是**先把注释读回来**（`TypeMapper` 读 `REMARKS` + `SchemaService:394` 的 SQLite 路径），
> 读回来了 D2/D4 同时消失。

**修复顺序建议**：`D2+D4`（改 `TypeMapper` 一处，两个缺陷同消）→ `D1`（加 kind 分流，半天）→ `D3`（要动内省 + `TableStructure` + 重建 SQL，最大）。

---

### 14.3 🔴 T 类：表级生命周期 —— 与 Navicat 最大的一块差距

Navicat 对象树右键菜单里最高频的四个动作，我们**一个都没有**。

| # | 能力 | 状态 | 说明 | 验收门禁 |
|---|---|---|---|---|
| **T1** | **新建表** | ⬜ | ⭐ **生成器已就绪**（`StructureSqlBuilder.createTableSql`，含主键/UNIQUE/DEFAULT/FK/ON UPDATE-DELETE），**只缺 UI**：一个列编辑表格 + `StructurePlan`。这是投入产出比最高的一项 | `CreateTableWizardTest`（🆕）+ 三方言 SQL 断言 |
| **T2** | **清空表（TRUNCATE）** | ⬜ | `TRUNCATE` 目前只作为**危险等级关键字**存在于 `DangerClassifier:76,85,232` —— 能识别用户输入，但不提供功能 | `TableLifecycleTest.truncate` |
| **T3** | **重命名表** | ⬜ | 代码库里唯一的 `RENAME TO` 在 `StructureSqlBuilder:129`（SQLite 重建的内部步骤），用户不可触发 | `TableLifecycleTest.rename` |
| **T4** | **删除表** | ⬜ | 同上，只有 `StructureSqlBuilder:128` 的内部 `DROP`。用户现在只能去查询页手敲 `DROP TABLE` | `TableLifecycleTest.drop`（必须走两级危险确认） |

> ⚠️ T2/T3/T4 是**不可逆操作**，必须复用已有的两级危险确认 + SQL 预览
> （`DestructiveConfirm` + `StructurePlan`），并且默认按钮**不得**是破坏性动作
> （4.5-27 的门禁已经守着这条）。

---

### 14.4 🟠 O 类：对象类型缺失

`ObjectKind` 枚举（`SchemaService.java:31-38`）只有 **5 个值**：`TABLE` / `VIEW` / `PROCEDURE` / `FUNCTION` / `TRIGGER`。

| # | 缺失 | 现状 | 说明 |
|---|---|---|---|
| **O1** | **MySQL 事件（EVENT）** | 连枚举位都没有 | 全库 `EVENT` 只出现在触发器的 `EVENT_MANIPULATION` 列名里（`MySqlDialect:131`、`PostgresDialect:125`），与事件调度器无关。接入方式：给 `Dialect` 加 `eventsListSql()` / `eventsSql(name)`，SQLite/PG 返回 null 即可沿用现有「方言不支持就安静跳过」模式（`SchemaService:176-178`） |
| ~~**O2**~~ ✅ | ~~**函数 / 过程的定义体**~~ **已完成（第十一轮·续）** | — | **已做**：`Dialect.readRoutineDefinition()`（SQLite→`sqlite_master` / PG→`pg_get_functiondef` / MySQL→`SHOW CREATE FUNCTION`）+ `RoutinePage` **只读标签页**（形态依 §4 裁定）+ 复制按钮 |
| **O3** | **用户 / 序列 / 物化视图** | 无 | Navicat 常见节点，本首版可明确不做（见 §14.8） |
| **O4** | **视图的增删改** | 无，只能看定义 | `CREATE VIEW` / `DROP VIEW` 生产代码零命中；唯一的 `DROP VIEW` 在 `StructureSqlBuilder:119`（SQLite 重建前的内部拆依赖） |

---

### 14.5 🟠 S 类：结构页三个页签是「死表格」

`StructurePage` 只给 `columns` 装了 `setRowFactory` + 右键菜单（:230-242、:258-277）；
`indexes` / `foreignKeys` / `triggers` 三张表（:292-320）**只做 `addColumn` + `setItems`** ——
连「复制索引名」都做不到，更不用说 Navicat 的「删除索引 / 设为唯一」。

| # | 对象 | 只读 | 缺 | 门禁 |
|---|---|---|---|---|
| **S1** | 索引 | ✅ 能看（名称/唯一/列 三列） | 新建 / 删除 / 重命名；**页面连右键菜单都没有** | `StructurePageTest.indexEditor`（U4 已有编号） |
| **S2** | 外键 | ✅ 能看 | ✅ **新建 / 删除已落地**（第十一轮）：`ForeignKeyDialog` 弹窗 + 三方言 DDL（SQLite 整表重建 / PG·MySQL ALTER） | `ForeignKeyTriggerDdlTest`（🆕 11 例） |
| **S3** | 触发器 | ✅ 能看见触发器体 | ✅ **新建 / 删除已落地**（第十一轮）：`TriggerDialog` 弹窗 + 实时 SQL 预览（PG 是「函数 + 触发器」两条） | 同上 |
| **S4** | 表注释 | ⬜ **连看都看不了** | `TableStructure` record 无 comment 字段；无 `COMMENT ON TABLE` | `TableStructureTest.tableComment` |
| **S5** | CHECK 约束 | ✅ **已落地**（第十一轮） | 内省 + 展示（结构页「检查」页签）+ 重建搬运 | `CheckConstraintTest` |

---

### 14.6 🟡 X 类：空态与入口（能力已存在，只是没接上）

| # | 问题 | 现状 | 说明 |
|---|---|---|---|
| **X1** | 空表打开后只有一行「没有数据」 | `DataGridPage.java:776` → `grid.setPlaceholder(new Label("没有数据"))` | ⭐ `WorkspaceView.emptyState()`（:520-544）明明支持「图标 + 标题 + 描述 + 主按钮」四件套，欢迎页（:121-158）也确实用上了 —— **同一个方法没复用过来**。这是全应用最弱的一处空态 |
| **X2** | 对象首页「未连接」时**完全空白** | `ObjectHomePage.java:104-105`：`groups.clear()` + `summary.setText("未连接")` | **倒挂**：连上了但对象数为 0 时反而有提示（:140-144「这个连接还没有可浏览的对象」）——「没连」比「连了没对象」还空 |
| **X3** | 状态栏版本号硬编码 | `StatusBar.java:35` 「v0.1.0 · P3」，**无 setter** | 10 个字段里 9 个都被真实数据驱动，只有它永不变。随版本迭代必然失真 |
| **X4** | **13/49 个动作是「半孤儿」**（27%） | 只能靠右键菜单 / 侧栏按钮够到 | `tab.close-others` / `tab.close-all` / `tab.copy-name` / `tab.copy-select` / `saved-queries` / `object.*`（8 个） |
| ~~**X5** ⭐~~ ✅ | ~~`object.*` 这 8 个动作在未选中树节点时**连 ⌘K 都搜不到**~~ | — | ✅ **已完成（第十二轮）**：命令面板**不再过滤禁用项**（灰掉但可见，点击给解释）+ 新增顶级「**对象**」菜单 |
| ~~**X6**~~ ✅ | ~~「收藏夹」顶级菜单只有 1 项~~ | — | ✅ **已完成（第十二轮）**：新增「清空收藏」（收藏落盘） |
| ~~**X7**~~ ✅ | ~~恢复上次标签会盖掉「对象」首页~~ | — | ✅ **已完成（第十二轮）**：`TabBar.activatePermanent()` —— 恢复完落回常驻标签 |

---

### 14.7 ✅ 本轮已完成：一键脚本 `scripts/`

启动这件事原本要手工敲 4 步，且**两个坑必踩一次**。现已固化进脚本：

| 脚本 | 作用 | 用法 |
|---|---|---|
| **`scripts/dev.sh`** | ⭐ **一键编译 + 启动**（用户点名的那个） | `./scripts/dev.sh` · `./scripts/dev.sh users`（直达某表）· `--fast` 增量 |
| `scripts/build.sh` | 完整门禁（verify + 用例计数） | `./scripts/build.sh` · `--quick` · `--no-tests` |
| `scripts/shots.sh` | 重拍 6 张验收截图 | `./scripts/shots.sh` · `./scripts/shots.sh p2-users` |
| `scripts/_env.sh` | 公共：环境预检 + 工具函数 | 被上面三个 source |

**它替你绕开的坑**：

1. **JDK 版本守卫**（实测踩过）：JDK 21 直接跑只会得到埋在日志深处的一句
   `Detected JDK version 21.0.12-1 ... is not in the allowed range [25,26)`。
   现在编译**之前**就说清楚，并给出修正命令：
   ```
   ✗ JDK 版本不对：当前 21，pom.xml 要求 >= 25（区间 [25,26)）。
      修正示例：export JAVA_HOME=$(/usr/libexec/java_home -v 25)
   ```
   要求版本从 `pom.xml` 的 `requireJavaVersion` **解析**得来，不硬编码 —— 改了 pom 脚本自动跟上。
2. **必须先 install**（实测踩过，最贵的一个）：`javafx:run -pl` 不带 `-am` 会从 `~/.m2`
   取**旧 jar**，改了半天 CSS 界面一点没变。`dev.sh` / `shots.sh` 强制 install 在前。
3. **macOS 没有 `timeout`**：`shots.sh` 用 `perl -e 'alarm ...; exec @ARGV'` 兜底，
   保证单张截图异常时不会把整轮挂死。
4. `shots.sh` 固定「暗色最先拍」（主题偏好会持久化），并用 `ADB_THEME_FORCE` 钉住主题，
   绕开「主题热切换后 snapshot 拿到空白帧」。

**实测记录（2026-10-21）**：JDK 21 拦截 ✅ · JDK 25 放行 ✅ ·
`build.sh --no-tests` 3.0s BUILD SUCCESS ✅ · `dev.sh users` 参数解析 + 编译 + 启动 ✅。
> ⚠️ 两个脚本**不要并发跑** —— 都做 `clean install`，会互相删 jar
> （实测得到过 `adb-model-1.0.0-SNAPSHOT.jar -> [Help 1]`）。这是并发问题，不是脚本缺陷。

---

### 14.8 本轮明确「不做」（写下来，免得反复讨论）

沿用首版范围（§0）：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务、传输同步。
**本节新增明确不做**：

| 项 | 理由 |
|---|---|
| 用户 / 序列 / 物化视图 / 同义词（O3） | 首版对象模型聚焦表与可执行对象；方言差异大，收益低 |
| Navicat 的图标语义色（编辑=蓝、新增=绿…） | 实拍对比 C5 已判定**不追** —— 单色是更现代的做法 |
| 顶栏功能数对齐 Navicat 的 11 个（C6） | 随 P5/P6 功能自然增长，不为了对齐而凑数 |

---

### 14.9 更新后的优先级（本轮重排）

| 档 | 项 | 理由 |
|---|---|---|
| **P0** | **D2 + D4**（`TypeMapper` 读 `REMARKS`） | 用户改一下列就丢注释，且无撤销路径。**改一处修两个** |
| **P0** | **D1**（对象首页打开函数/过程） | 点一下就报错，是 bug 不是缺口 |
| **P1** | **T1 新建表** | 生成器现成，只差 UI。**这是从「能看」到「能建」的分界线** |
| **P1** | **D3**（CHECK 约束） | 改列丢约束，属数据丢失 |
| **P1** | **S1 索引可编辑** + **X1 / X2 空态引导** | 索引建不了；两处最弱的空态复用现成 `emptyState()` 即可 |
| **P2** | T2/T3/T4（清空 / 重命名 / 删除表）、S2/S3/S4、O1、O2 | 表生命周期收口 + 对象能力补齐 |
| **P2** | G1 冻结列 · G4 区域选择 · Q5 结果分页 · U1 catalog 树 · U2 多连接并行 | 沿用 §13.6 |
| **P3** | X3 / X4 / X5 / X6 / X7 | 入口与一致性打磨 |
| **P3** | A10 语句超时 · A12 连接池归还 · A15 UI 线程 · A16 单执行器 | 架构整改，用户不直接感知但会卡界面 |

---

## 15. 第七轮：截图实测审计（2026-10-21 23:43）

> 基于 **4 张真实窗口截图**（对象首页 / 数据页 / 连接管理 / 查询页）。
> **完整审计、代码证据、修法与验收标准见 `docs/UI-AUDIT.md`** —— 本节只做索引与排期。
>
> 与 §14 的区别：§14 是**读代码**找出的缺陷（D/T/O/S/X 编号）；
> §15 是**看界面**找出的问题（S/M/L 编号）。两批有交叉，但视角不同 ——
> 有些问题只有截图才看得见（`22.990000000000002` 就是这么暴露的）。

### 15.1 新增问题索引

| 编号 | 问题 | 性质 | 来源 |
|---|---|---|---|
| **S1** | `amount` 显示成 **`22.990000000000002`**（REAL/DOUBLE 长尾小数） | 🔴 数据可信度 | `CellRenderer.numberText:216-221` |
| **S2** | 对象页「刷新」按钮**从未加入场景图** | 🔴 死 UI | `ObjectHomePage:52/81-83`（**本轮已修 + 已加门禁**） |
| **S3** | 对象页无「新建表」入口 | 🔴 同 §14.3 T1 | 截图右上只有标签栏的「+」 |
| **M1** | 状态栏 `v0.1.0 · P3` 硬编码无 setter | 🟡 | `StatusBar:35` + `AppShell:820` |
| **M2/M11** | 状态栏耗时**显示两次** | 🟡 | `range` 自带 `· 12 ms` + `duration` 独立字段 |
| **M3** | 连接表端口显示 `0`、主机/用户空白 | 🟡 | `ConnectionsPage:186-200`，只有 `状态` 列做了 `—` |
| **M4** | 连接表 1 行数据画出 12 个空行撑满 480px | 🟡 视觉 | `setFixedCellSize` + `Vgrow ALWAYS` |
| **M5** | 「对象」在标签 / 页标题 / 侧栏标题**重复三次** | 🟡 | `ObjectHomePage:69-72` |
| **M6** | 顶部「共 200 行」与底部「1-200 / 500」**措辞矛盾** | 🟡 | 本页行数 vs 总行数 |
| **M7** | 「每页」下拉看不见当前值 | 🟡 | 被挤到边缘 |
| **M8** | 表头 `id [PK] number · 自增` 三类信息挤一格 | 🟡 | 扫视困难 |
| **M9/M10** | 查询结果无记录导航条、无类型行，与数据页不一致 | 🟡 | 同一份数据两种规格 |
| **M12** | 查询页编辑器/结果区比例固定，无可拖分隔条 | 🟡 | 一行 SQL 也占 45% 高度 |
| **L2** | 「连接管理」插头图标在 16px 下**与放大镜高度相似** | 🟢 语义 | `Icons:150`（子代理与我都误读成搜索） |
| **L7** | 查询页「只读」是蓝色填充 pill，看起来像按钮 | 🟢 | 实际不可点 |

### 15.2 ⚠️ 已排除的三个误判（防止照着错的改）

| 初判 | 代码事实 |
|---|---|
| 「侧栏有两个搜索框」 | `SideBar` 的 `search` **只有一个**（:86），在底部 `bottomBar`；顶部是插头图标按钮 + 连接下拉 |
| 「记录导航条用 Unicode 符号」 | `DataGridPage:875-885` 用 `Icons.FIRST/PREVIOUS/NEXT`，**是 SVGPath** |
| 「score 显示逗号小数（locale bug）」 | `String.valueOf(7.5d)` = `"7.5"`，`numberText` 无 locale 格式化。**数字问题只有一个：S1** |

### 15.3 排期

| 批次 | 内容 | 门禁 |
|---|---|---|
| **B1（P0）** ✅ **已完成** | **S1** 数值展示（只改展示、不改值、导出走原值） | `NumberFormattingTest`（🆕 6 例，已验证能抓回退） |
| **B2（P0）** ✅ **已完成** | **S2** ✅ 已修 / **D1** 函数点开报错 / **X1·X2·E1–E4·E6** 空态 | `ObjectHomePageTest` ✅（5 例）· `AppShellTest.showsEmptyStateForEmptyTable`（升级断言） |
| **B3（P1）** | M1–M12 一致性打磨（一次做完） | `StatusBarTest`（🆕）· `ConnectionsPageTest.blankCellsUseDash`（🆕） |
| **B4（P1）** ✅ **已完成** | **T1–T4 表级生命周期**（投产比最高） | `TableLifecycleSqlTest`（🆕 17 例）· `TableDdlTest`（🆕 8 例）· `NewTableDialogTest`（🆕 7 例）—— 三处均已实测能抓回退 |
| **B5（P2）** | 索引/外键/触发器可编辑 · 表注释 · 事件 · 函数定义体 | 见 §14.5 |
| **B6（P2）** | G1 冻结列 · G4 区域选择 · Q5 结果分页 · U1 catalog · U2 多连接 | 见 §13.6 |

### 15.4 ✅ 本轮已完成的实际改动（代码 + 门禁）

| 改动 | 文件 | 门禁 |
|---|---|---|
| `refresh` 按钮进入 `header` | `ObjectHomePage.java` | `ObjectHomePageTest.refreshButtonIsActuallyInTheScene` |
| `summary` 补 id（便于呈现断言） | `ObjectHomePage.java` | 同上 |

> **门禁有效性已验证**：手动把 `refresh` 从 `header` 移除，测试立即失败并指出原因
> （`ObjectHomePageTest:40`）；恢复后转绿。这条断言不是摆设。

### 15.4b ✅ 第八轮追加：B1 + B2 落地（2026-10-22）

| 编号 | 改动 | 文件 | 门禁 |
|---|---|---|---|
| **S1** | `numberText` 对 `Double`/`Float` 走新的 `doubleText`：小数位 ≤ 12 原样，> 12 收敛并去掉尾随零；极小值不收敛成 0；tooltip 补「原值」 | `CellRenderer.java` | `NumberFormattingTest`（6 例） |
| **S1** | 编辑回填 / 导出抽成可测方法并**强制走原值** | `DataGridPage.editorText` / `csvValue` | 同上（含 `isNotEqualTo(展示值)` 反向断言） |
| **D1** | `onOpen` 从 `Consumer<String>` 改为 `Consumer<SchemaObject>`；新增 `unsupportedReason(kind,name)` 单一判据，`AppShell.openObject` 据此分派 | `ObjectHomePage.java` / `AppShell.java` | `ObjectHomePageTest`（`onlyTablesAndViewsAreBrowsableAsRows` + chip 传整对象） |
| **X2/E1–E3** | 对象首页三处空态（未连接 / 无对象 / 读取失败） | `ObjectHomePage.java` | `ObjectHomePageTest` |
| **E4/E6** | 空表 placeholder、连接列表 placeholder | `DataGridPage.java` / `ConnectionsPage.java` | `AppShellTest.showsEmptyStateForEmptyTable`（断言升级） |
| — | 🆕 共享空态组件（原本是 `WorkspaceView` 的 private 方法，别处复用不了） | `EmptyState.java` | — |

> **两个实测发现，推翻了审计时的假设**（都写进了代码注释）：
> 1. `22.990000000000002 == 22.99` 是 **false** —— 它们是两个不同的 double。
>    所以这不是"最短往返表示"能解决的：`Double.toString` 已经是最短表示了。
>    真正的修法是**按业务精度收敛小数位**（12 位分界）。
> 2. 编辑回填（:3059）与导出（:2919）**本来就走 `String.valueOf`**，不经过展示层 ——
>    所以我担心过的"格式化字符串被写回数据库"这个陷阱，**当前并不存在**。
>    但仍把它抽成方法 + 加反向断言钉死，防止将来有人图省事改回去。

### 15.4c 形态与状态规范（🆕 `docs/UI-FORMS-AND-STATES.md`）

用户提出「判断哪些是弹窗、哪些是多标签，别写错了；默认态和空白态也规划一下」，
因此新增该文档并**在动手前把形态裁定死**：

- **判定规则**：改已存在的东西→弹窗；要长期停留/并排比较/有状态→标签页；
  不确定→弹窗（弹窗做错关掉就行，标签页做错会一直杵着）。
- **待建能力裁定表**（§4，动手前先查）：
  **T1 新建表 = 弹窗**（❌ 不是新标签页，否则会留下关不掉的"空表"标签）；
  T2/T3/T4/S1/S2/S3/S4 = 弹窗；**U3 连接属性 = 弹窗里套 TabPane**；
  O1 事件 / O2 函数定义体 = 标签页。

### 15.4d ✅ 第八轮：B4 表级生命周期落地（T1–T4，2026-10-22）

> 形态严格按 §15.4c 的裁定实现：**T1 弹窗、T2/T3/T4 弹窗**，没有一个是标签页。
> 完整记录（分层图 / 方言差异表 / 默认态 / 门禁）见 `docs/UI-FORMS-AND-STATES.md` §10。

| 编号 | 改动 | 文件 | 门禁 |
|---|---|---|---|
| **T1** | 🆕 建表弹窗：列表格 + **实时 SQL 预览** + 表注释（SQLite 下禁用） | `NewTableDialog.java`（🆕） | `NewTableDialogTest`（7 例，含形态源码扫描） |
| **T2** | 清空表 —— SQLite 走 `DELETE FROM`（**没有 TRUNCATE**） | `SqliteDialect` / `PostgresDialect` / `MySqlDialect` | `TableLifecycleSqlTest` |
| **T3** | 重命名表 —— MySQL 走 `RENAME TABLE`，其余 `ALTER TABLE … RENAME TO` | 三个 Dialect + `TableDdl` | 同上 |
| **T4** | 删除表 | 三个 Dialect + `TableDdl` | 同上 |
| — | 🆕 建表 / 清空 / 重命名 / 删表计划构造器 + 表名校验 | `StructureSqlBuilder.java` | `TableLifecycleSqlTest`（17 例） |
| — | 🆕 表级 DDL 协调者（唯一知道"怎么跑完一次表级变更"的地方） | `TableDdl.java`（🆕） | `TableDdlTest`（8 例） |
| — | 🆕 计划执行/确认**抽出来与列级变更共用**（避免两份事务语义跑偏） | `StructurePlanRunner.java`（🆕） | `StructurePage` 改为委托 |
| — | 表注释（PG/MySQL 支持，SQLite 禁用） | `Dialect.supportsTableComment` / `tableCommentSql` | `TableLifecycleSqlTest` |
| — | 侧栏右键 + 数据库菜单 + ⌘K 三条入口 | `SideBar.java` / `MenuBarFactory.java` / `AppShell.java` | `MenuBarFactoryTest`（补 4 个 action id） |

> **本轮唯一的隐藏坑（值得记住）**：MySQL 的 `PRIMARY KEY` 必须写在 `COMMENT` **之前**。
> `columnDefinition` 把 COMMENT 拼在末尾，直接追加 `PRIMARY KEY` 会得到
> `` `id` INTEGER COMMENT 'x' PRIMARY KEY `` —— **语法错误**。
> 修法是新增 `Dialect.createTableColumnDefinition(column, inlinePrimaryKey)`，
> 让方言自己决定插在哪（默认追加末尾，MySQL 覆写插到 COMMENT 前）。
>
> **另一个坑**：`MenuBarFactoryTest` 里菜单引用的 action 必须先在 `MENU_ACTION_IDS` 注册，
> 否则表现为 `Cannot invoke "MenuBar.getMenus()" because "bar" is null` ——
> 报错完全指不到真因（其实是 `menuItem(...)` 返回 null 让 `build()` 抛了异常）。

**门禁 486 条 / 62 类 / 0 失败**（原 454/59，+32）。
- **默认态 8 项**：已全部完备（常驻对象标签 / 演示库 / 跟随系统主题 / 200 行分页 / 示例 SQL…）。
- **空白态 9 处**：本轮修完 E1–E4、E6；剩 E5（查询 0 行）/ E7（侧栏搜不到）/
  E8（历史为空）/ E9（收藏为空）待补。

### 15.5 门禁体系的结构性缺口

本轮 4 个缺陷（S1 / S2 / D1 / M1）**全部活在 443 条全绿的门禁之外**。
说明现有门禁只覆盖「行为」，不覆盖「呈现」与「格式化」。

需补三类（成本都很低，都是几行断言）：

| 类型 | 断言 |
|---|---|
| **呈现门禁** | 控件被创建**且**在场景图里（`lookup("#id") != null`） |
| **格式化门禁** | 展示值 / 编辑回填 / 导出值三者的一致性 |
| **文案门禁** | 状态栏字段不重复；版本号来自单例、无硬编码阶段代号 |

---

## 16. 第八轮（2026-10-22）— D2/D4 注释读回 + B5 索引可编辑

### 16.1 D2 / D4：注释「只写不读」——两个丢数据路径一次消掉

| 缺陷 | 根因 | 修法 |
|---|---|---|
| **D2** MySQL 改一次列，**该列注释被静默抹掉** | `SchemaService.structure()` 从 JDBC 元数据读列，**没有任何一处读列注释**；改列时 UI 拿内省结果当基底，基底里 comment 恒为 null → 生成的 `MODIFY COLUMN` 不带 `COMMENT` 子句 | 新增 `Dialect.columnCommentsSql(table)`，`withComments()` 把注释并回基底；读不到（权限/视图）就当没有 |
| **D4** PostgreSQL 清不掉列注释 | `appendColumnComment` 无条件拼 `IS '...'`，comment 为 null 时拼出 `IS 'null'` | 拆成 `appendColumnComment`（有值才写）+ `appendColumnCommentReset`（无值发 `IS NULL`） |

> ⚠️ 修 D2 前先核实过：`TypeMapper.java:50` 那个「comment 硬编码 null」是
> **结果集元数据**（`ResultSetMetaData` 根本没有 `getRemarks`），不是结构内省路径。
> 上轮把它当成根因是**误判**，真正的根因在 `SchemaService.structure()`。

改动：`Dialect.columnCommentsSql` / `appendColumnCommentReset` ·
`SchemaService.withComments` · `StructureSqlBuilder` 改列分支。

门禁：`ColumnCommentTest`（12 例）——**实测验证过能抓回退**（把 MySQL 的
`columnCommentsSql` 改成 return null、PG 的 reset 改成空实现，3 条立刻失败）。

### 16.2 B5-S1：索引可编辑（结构页「索引」页签）

| 能力 | 实现 |
|---|---|
| 新建索引 | `IndexDialog` 弹窗：索引名 / 唯一 / 多列（顺序可调）/ **实时 SQL 预览** |
| 删除索引 | 结构页索引页签右键 + 按钮，二次确认后单事务执行 |
| 拒绝规则 | 自动索引（`sqlite_autoindex_*` / MySQL `PRIMARY`）**不许删**；索引名重名（不分大小写）拒绝；空名 / 零列 / 不存在的列拒绝 |

三方言差异（`IndexDdlTest` 14 例锁死）：

| 动作 | SQLite | PostgreSQL | MySQL |
|---|---|---|---|
| 建 | `CREATE INDEX "i" ON "t" ("c")` | 同左 | `` CREATE INDEX `i` ON `t` (`c`) `` |
| 删 | `DROP INDEX "i"` | `DROP INDEX "i"` | `` DROP INDEX `i` ON `t` `` |

> ⭐ `IndexDialog` 的预览与真正执行**共用** `StructureSqlBuilder.createIndexSql`
> （已改成 public）—— 各写一份的话，预览会慢慢变成谎言。
>
> ⚠️ 踩坑记录：`StructurePlan.sql()` 是**展示串**（会主动补分号），
> `statements()` 的单条才是真正交给 JDBC 逐条 execute 的。
> 测试必须断言后者 —— SQLite 的 execute 遇到 `;` 直接语法错误。
> 本轮第一版测试断言错了对象，6 条假失败；另外 2 条因为 `.as(...)` 夹在中间
> 没被批量替换到，是逐条看出来的。

### 16.3 截图入口（`scripts/shots.sh` 9 张）

新增 `ADB_NEW_TABLE=1` / `ADB_INDEX=1` 两个启动开关，让**模态弹窗**也能被拍到
（`ScreenshotSession` 的 click 只能点页面内的 id，点不到弹窗）。
沿用既有 `ADB_OPEN` / `ADB_STRUCTURE` / `ADB_QUERY` 的模式，抽了 `flag()` 辅助方法。
第十三轮又补了 `ADB_TABLES=1`（表清单页，`p6-tables`）。

**门禁 512 条 / 64 类 / 0 失败**（原 486/62，+26）。

### 16.4 下一步

- **S2 外键可编辑** / **S3 触发器可编辑**（同为结构页弹窗，裁定见
  `docs/UI-FORMS-AND-STATES.md` §4）
- 剩余空白态 E5（查询 0 行）/ E7（侧栏搜不到）/ E8（历史为空）/ E9（收藏为空）
- 函数 / 过程 / 事件的**定义体**标签页（O1 / O2）
- **D3**：SQLite 改列丢失 CHECK 约束（P0 —— 改列不再丢约束；内省 + 重建 SQL 搬运）
- ~~**B5-S4**：表注释~~ ✅ **已做**（第十一轮·续）：读 + 写/清空 + 结构页展示与编辑
- **U6**：状态栏补充（当前用户 / 服务器版本 / 延迟）— `NAVICAT-TODO.md` §13.6 U6
- **U-B6 剩余**：G1 冻结列 / G4 区域选择 / Q5 结果分页 / U1 catalog 树（U2/U3/U6/Q6 已完成）
- **X3–X7**：入口与一致性打磨（状态栏版本号 M1 / X4–X7）
- **A10 / A12 / A15 / A16 / A18 / A22 / A25**：架构收尾（超时 / 线程池 / 线程 / 主题探测 / 声明层）
- **P7**：分发与收尾（jlink / jpackage / 崩溃 / 可访问性）

## 17. GitHub 开源数据库管理软件对照与对外汇报（2026-10-22）

- 🆕 **`docs/GITHUB-COMPARE.md`**：GitHub 上开源数据库管理软件扫描
  （DBeaver / Chat2DB / Beekeeper Studio / TablePro / sqlpad / SQLTools / Adminer…）。
- 🆕 **`docs/EXEC-SUMMARY.md`**：对外汇报版 —— 架构 / Todolist / 截图 / Navicat /
  GitHub / 六维 / 还缺什么 / 本轮交付物 / 汇报要点。
- **AgenticDB 独特位**：开源界「Java + 纯原生桌面（JavaFX，不内嵌 WebView、不启 HTTP）」的
  竞品几乎没有（DBeaver 是 Eclipse RCP、Beekeeper/TablePro 是 Electron）——这是差异化叙事的核心。

---

## 18. 第十轮：UI/UX 细化规格（2026-10-22）

> 本轮目标：把「界面」从「做过一轮重做」推进到「**有可照着实现的规格**」。
> 产出 **`docs/UI-VISUAL-SPEC.md`** —— `ObjectHomePage` 的 javadoc 早就引用了它
> （「见 `docs/UI-VISUAL-SPEC.md` §3.4」），但这份文档一直**不存在**。
>
> 方法：源码 + `tokens.json` + `app.css` + **样式类覆盖审计**
> （Java 里用到的 `adb-*` 类 vs `app.css` 里的选择器求差集）。

### 19.1 本轮实测发现（此前没有人统计过的东西）

| # | 发现 | 数据 | 影响 |
|---|---|---|---|
| **D-1** | Java 里用到的样式类，CSS 里**没有对应规则** | **43 个** | 结构页 / 连接页 / 5 个向导 / 详情抽屉都没有专属样式；`.adb-error`（表单校验错误）也没有 → **错误文字不是红色** |
| **D-2** | 两个令牌是**摆设** | `row-tree` / `kind-index` | `TokenParityTest.everyTokenIsActuallyUsed` **直接失败**（门禁红） |
| **D-3** | 状态栏版本号仍**硬编码** | `StatusBar.java:35` = `v0.1.0 · P3` | B3 / M1 未做；与实际版本脱节 |
| **D-4** | 数字列**不右对齐** | 数据页 / 对象清单 | 金额、行数、数据长度无法竖向比较 |

### 19.2 本轮已修（3 项，随手但都是真缺陷）

| # | 改动 | 文件 | 门禁 |
|---|---|---|---|
| **R-1** | `tree.setFixedCellSize(22)` → `Tokens.ROW_TREE_LIGHT`（令牌才是唯一真相） | `SideBar` | `TokenParityTest` |
| **R-2** | 新增 `INDEX` 钥匙图标 + 结构页「索引」页签行首图标 + `.adb-structure__index-icon` 规则（用上 `kind-index`） | `Icons` / `StructurePage` / `app.css` | `TokenParityTest` + `IconSystemAuditTest` |
| **R-3** | 补 `.adb-error` 规则（表单校验错误显红） | `app.css` | 截图验收 |
| **R-4** | 删死样式：对象首页 chip→表格后遗留的 `.adb-object-home__group/__groups/__scroll` | `app.css` | `StyleClassCoverageTest` |
| **R-5** | `.adb-menu-item` 从死规则变成真钩子（菜单项统一加上，禁用态透明度还原真正生效） | `ActionRegistry` | 同上 |
| **R-6** | 工具栏分组分隔线改用 `.adb-toolbar__separator`（1px 竖线） | `DataGridPage` | 同上 |
| **R-7** | 对象清单数字列去内联样式 → 走 `.adb-object-list__number` 样式类 | `ObjectHomePage` | 同上 |
| **R-8** | 🆕 **新门禁 `StyleClassCoverageTest`**（4 例）：① Java 用到的 `adb-*` 必须在 CSS 有规则（已知 43 个进白名单）② CSS 死样式 ③ 白名单不得留已修条目 | `ui/adb-ui-core` | 本门禁自己 |
| **R-9** | **S-1…S-6 全部补齐**：43 个未定义样式类 → 0（结构页 / 连接页 / 5 个向导 / 表单 / 抽屉 / 状态栏 / 侧栏 / 网格 / 空态…） | `app.css` | `StyleClassCoverageTest`（白名单清空） |
| **R-10** | 状态栏**版本号**取自构建产物（Maven 资源过滤 → `version.properties` → `AppVersion`），删掉写死的 `v0.1.0 · P3` | `AppVersion` / `version.properties` / `StatusBar` / `AppShell` | `StatusBarTest`（4 例） |
| **R-11** | 状态栏**耗时只出现一次**（消息里不再带「· 12 ms」） | `DataGridPage` | `StatusBarTest` |
| **R-12** | 连接表「不适用」显示 `—`（端口 / 主机 / 用户 / 数据库），且**方言判断走方言层**（`usesFilePath`）—— 写了具体方言分支会被 `DialectBranchAuditTest` 拦下 | `ConnectionsPage` | `DialectBranchAuditTest` + 截图 |
| **R-13** | **数字 / 日期右对齐**（数据页 + 对象清单），行号列右对齐；对象清单去内联样式 | `app.css` / `ObjectHomePage` | `StyleClassCoverageTest` + 截图 |
| **R-14** | **图标尺寸统一成 6 档常量**（8/12/14/16/32/40），调用点不再写裸数字 | `Icons` | `IconSystemAuditTest` |
| **R-15** | **加载态按钮**：保存时图标换转圈且**宽度不变** | `IconButtons.loading()` / `DataGridPage` | 截图 |
| **R-16** | **空态补齐 4 处**（E5 查询 0 行 / E7 侧栏搜索无结果 / E8 查询历史 / E9 收藏夹） | `QueryPage` / `SideBar` / `HistoryPage` / `FavoritesPage` | `EmptyStateTest`（4 例） |
| **R-17** | 🆕 **对比度门禁 `ContrastTest`**（2 例）**并据此改了 6 个色值** —— 亮色三级文字原来只有 3.03:1 | `ContrastTest` / `tokens.json` | 本门禁自己 |

> **门禁实测**：修之前 `./mvnw -B clean verify` 在 `adb-ui-core` **失败**（1 条）；
> 修之后 **585 条 / 77 类 / 0 失败**（`java tools/TestCount.java`）。

### 19.3 视觉与交互缺口（`V-*`，来源：`docs/UI-VISUAL-SPEC.md` §13.2）

| # | 任务 | 优先级 | 状态 | 落点 | 验收门禁 |
|---|---|---|---|---|---|
| **V-1** | **数字列右对齐**（数据页 / 对象清单；含等宽字体） | P1 | ✅ **已完成** | `app.css`（`.adb-grid .table-cell.adb-grid__cell--number/--date`、`.adb-grid__row-number`）+ `ObjectHomePage.numberCell()` | `StyleClassCoverageTest` + 截图 |
| **V-2** | 补 `overlay` / `shadow-1` / `shadow-2` 三个令牌（弹窗遮罩与投影） | P2 | ✅ **已完成** | `tokens.json`（87 → **90**）→ `GenTokens` → `app.css`（命令面板遮罩 / 弹窗 / toast / 抽屉投影） | `TokenParityTest` + `ContrastTest` |
| **V-3** | **状态栏版本号取自构建产物**（删掉 `P3` 阶段代号） | P1 | ✅ **已完成** | 新增 `AppVersion` + `version.properties`（Maven 资源过滤）+ `StatusBar` / 「关于」共用 | `StatusBarTest`（🆕 4 例） |
| **V-4** | 加载中按钮：内嵌 spinner + 禁用 + **宽度不跳** | P2 | ✅ **已完成** | `IconButtons.loading()` + 数据页保存路径 | `IconAuditTest` + 截图 |
| **V-5** | 工具栏「最多 7 个常显，其余收进更多菜单」 | P2 | ⬜ **暂缓** | 1280 宽下 12 个图标按钮**放得下**；按宽度折叠要先做响应式，收益低于成本 | — |
| **V-6** | **应用图标**（窗口标题 / 标签栏） | P3 | ⬜ **暂缓** | 需要出图资源 | 截图 |
| **V-7** | 图标尺寸三档统一（标签 14 / 工具栏 16 / 空态 32） | P2 | ✅ **已完成** | `Icons.SIZE_DOT/BADGE/LABEL/TOOLBAR/EMPTY/BRAND` 六个常量，全部调用点改引用 | `IconSystemAuditTest` |
| **V-8** | **对比度自动检查**（正文/次要/三级 ≥ 4.5:1） | P2 | ✅ **已完成**（并**改了 6 个色值**） | 🆕 `ContrastTest`（2 例）· 亮色 `text-tertiary` #8b95a3→#6A717C、`success` #1f8a4c→#1E8549、`kind-table`、`kind-trigger`；暗色 `text-tertiary` #6e7681→#86909D、`text-secondary` | `ContrastTest` |
| **V-9** | 表格规格统一（类型行 / 记录导航条 / 结果区） | P2 | ⬜ **暂缓** | 牵动数据页与查询页两套网格，单独一轮做 | 截图 |
| **V-10** | 剩余空白态 E5 / E7 / E8 / E9 | P2 | ✅ **已完成** | E5 查询页 0 行 · E7 侧栏搜索无结果 · E8 查询历史 · E9 收藏夹 | 🆕 `EmptyStateTest`（4 例） |
| **V-11** | 弹窗宽度三档（表单 480 / 宽表 720 / 长文本 560） | P3 | ⬜ **暂缓** | 各弹窗宽度现值差异不大，先统一规范再改实现 | 截图 |
| **V-12** | 错误详情弹窗 / 批量确认汇总页 / **错误归类** | P2 | ⬜ **暂缓** | 工作量大，单独一轮（与 B5 对象能力一起排） | 新测试 |

### 19.4 样式类覆盖（43 个）—— 分批补

| 批 | 范围 | 缺失的类 | 状态 |
|---|---|---|---|
| **S-1** | 结构页 | `adb-structure` `adb-structure__table` `__tabs` `__overview` `__ddl` `__loading` | ✅ **已补**（结构页终于和其它页面同一套外观） |
| **S-2** | 连接页 | `adb-connections` `adb-connections__table` `__favorites` `__search` | ✅ **已补** |
| **S-3** | 5 个向导（导入/导出/收藏/已保存查询/传输入口） | `adb-wizard__content` | ✅ **已补** |
| **S-4** | 表单 | `adb-form` `adb-form__label`（`adb-error` ✅ 已补） | ✅ **已补** |
| **S-5** | 侧栏 / 网格 / 抽屉 / 状态栏 / 对象首页 / 空态 | 其余 22 个 | ✅ **已补** |
| **S-6** | 纯命名类（`adb-body` `adb-main` `adb-table-list` `adb-history__list` → 补样式；`adb-db` `adb-screenshot` → **线程名，不是样式类**，进 `NOT_STYLE_CLASSES`） | 逐个判定「补样式 or 删类」 | ✅ **已判定** |
| **S-7** | 🆕 **新门禁 `StyleClassCoverageTest`**（4 例） | 「Java 用到的 `adb-*` 必须在 `app.css` 有规则」＋「CSS 死样式」＋「白名单不得留已修条目」 | ✅ **已落地，白名单已清空**（43 → 0） |

> ⭐ **S-1…S-6 已在本轮全部补齐**：43 个已知缺口 → **0**，
> `StyleClassCoverageTest` 的白名单**已清空** —— 现在门禁是「**一个都不许缺**」。
> 顺带修掉的：`.adb-grid__number-column` 是挂在 `TableColumn` 上的**无效样式类**
> （Column 不在场景图里）→ 已从 Java 删掉；`adb-db` / `adb-screenshot` 是**线程名**，
> 进了 `NOT_STYLE_CLASSES`（不是样式类，别去 CSS 里找它们）。

### 19.5 本轮新增文档

| 文档 | 回答什么问题 |
|---|---|
| **`docs/UI-VISUAL-SPEC.md`** | ⭐ **可照着实现的 UI 规格书**：9 档字号落到哪个角色 · 87 个颜色的语义 · 44 个图标的清单 · 间距/圆角/固定尺寸 · 六区与四种页面骨架 · 组件规格 · 弹窗骨架与按钮区规则 · 交互态矩阵 · 键盘表 · 文案措辞 · **缺口清单（43 个样式类 + 12 条 V-\*）** |

---

---

## 19. 第十一轮：对象能力 —— CHECK / 外键 / 触发器（2026-10-22）

> 主线：把「能看」补成「**能改结构**」。D3 是 P0（丢数据），B5-S2/S3 是 Navicat 对标里
> 结构页剩下的两块只读页签。

### 19.1 D3：CHECK 约束（✅ 丢数据缺陷已修）

| 环节 | 实现 |
|---|---|
| 模型 | `CheckDef`（名字 + 表达式）· `TableStructure` 新增 `checks` 列表（带兼容构造器） |
| 解析 | `CheckParser` —— 从 `CREATE TABLE` 原文里抽 CHECK（**跳过字符串字面量**、认单词边界、认 `CONSTRAINT <名>`） |
| 方言 | `Dialect.checksSql(table)`（PG 走 `pg_constraint`、MySQL 走 `information_schema`）· `Dialect.parsesChecksFromDdl()`（SQLite = true） |
| 内省 | `SchemaService.checks(...)` —— 读不到就当没有，**不让结构页整个挂掉** |
| **重建** | ⭐ `createTableSqlWithChecks(...)` —— SQLite 改列整表重建时**搬运 CHECK**（以前只搬列/主键/UNIQUE/DEFAULT/FK） |
| 展示 | 结构页新增「**检查**」页签（名称 + 表达式），概览摘要加「检查 N」 |

> ⭐ 门禁不只断言「约束还在」，还断言「**约束仍然拦得住违规数据**」——
> 只「看得见」不算修好。

### 19.2 B5-S2/S3：外键与触发器可增删

| 能力 | 形态 | 实现 | 方言差异 |
|---|---|---|---|
| 新建/删除**外键** | 🔵 弹窗（`ForeignKeyDialog`） | 约束名 + 本表列（有序）+ 被引用表 + 被引用列（有序）+ ON UPDATE/DELETE | SQLite **整表重建** · PG/MySQL `ALTER TABLE` |
| 新建/删除**触发器** | 🔵 弹窗（`TriggerDialog`） | 名字 + 时机 + 事件 + 触发器体 + **实时 SQL 预览** | SQLite/MySQL `BEGIN … END` · PG **函数 + 触发器两条语句** |

> 形态依据 `docs/UI-FORMS-AND-STATES.md` §4：两者都是「改已存在的东西」→ **弹窗**，不是标签页。

### 19.3 ⭐ 顺带挖出的潜伏 bug：带外键的表改列必失败

| 项 | 内容 |
|---|---|
| 症状 | 任何**带外键**的表，改列时重建语句里出现 `ON UPDATE 3` —— **语法错误**，操作直接失败 |
| 根因 | `getImportedKeys()` 返回的是**数字动作码**，`ForeignKeyDef` 原样透传；而**各驱动码表不同**（JDBC 标准 3=SET NULL，SQLite 驱动 3=NO ACTION） |
| 修法 | 翻译收进**方言层**：`Dialect.foreignKeyActionName(code)`（默认 JDBC 标准码表，SQLite 覆写） |
| 为什么没被发现 | 演示库的 `orders` 正好有外键，但**从没有测试改过它** |
| 门禁 | `ForeignKeyTriggerDdlTest.alteringAColumnOnATableWithForeignKeyUsedToFail` |

### 19.3b B5-S4：表注释（✅ 从「只写不读」到「读得回、改得了」）

| 环节 | 实现 |
|---|---|
| 读 | `Dialect.tableCommentQuery(table)`（PG → `obj_description`、MySQL → `information_schema.TABLES.TABLE_COMMENT`；SQLite 无 → 空串）· `SchemaService.tableComment(...)` |
| 模型 | `TableStructure` 新增 `comment`（带两个兼容构造器，老调用方不用改） |
| 写 | `StructureSqlBuilder.setTableComment(...)` —— 清空走 `Dialect.clearTableCommentSql()`（⭐ PG 是 `IS NULL`，写成 `IS ''` 等于没清） |
| 展示 | 结构页概览区显示「表注释：…」；工具栏新增「表注释」按钮（**SQLite 直接禁用**，不假装有） |
| 交互 | `TableCommentDialog` 弹窗 + **实时 SQL 预览**（形态依 `UI-FORMS-AND-STATES.md` §4） |

> 以前表注释是「**只写不读**」：建表时能填，`TableStructure` 里连字段都没有 ——
> 填了以后界面上再也看不见、也改不了。**填了等于没填。**

### 19.3c O2：函数 / 过程定义体（✅ 从「只有名字」到「能读代码」）

| 环节 | 实现 |
|---|---|
| 读 | `Dialect.readRoutineDefinition(connection, name, procedure)` —— SQLite `sqlite_master.sql` / PG `pg_get_functiondef` / MySQL `SHOW CREATE FUNCTION`（结果集第 3 列才是定义体） |
| 服务 | `SchemaService.routineDefinition(session, name, procedure)` —— 读不到返回**空串**（不抛异常、不让页面打不开） |
| 展示 | 🆕 `RoutinePage` —— **只读标签页**（形态依 `UI-FORMS-AND-STATES.md` §4：定义体要长期停留读代码 → 标签页，不是弹窗）+ 复制定义体 |
| 入口 | 对象树 / 对象首页点函数或过程 → 开「定义」标签（`TabKind.ROUTINE`） |

> ⚠️ **SQLite 没有 SQL 层函数**（用户自定义函数只能走 C API）→ SQLite 上「读不到」是**正确结果**；
> 门禁守住「读不到要优雅」。真正的函数定义体由**真库契约**（MySQL）验证。

### 19.4 本轮新增门禁

| 门禁 | 例数 | 守什么 |
|---|---|---|
| `CheckConstraintTest` | 7 | 解析器（命名/匿名/字符串里的括号/`CHECKED` 不是 CHECK）· 内省看得见 · **改列后约束还在且仍生效** · 建表语句带 CHECK |
| `ForeignKeyTriggerDdlTest` | 11 | 三方言外键/触发器 SQL 逐字 · **带外键改列不再失败** · 真库往返（外键拦得住违规行、触发器真的执行、触发器可删） |
| `TableCommentDdlTest` | 5 | 表注释三方言逐字 · **清空注释**（PG `IS NULL` / MySQL 空串）· 单引号转义 · SQLite **明确拒绝** · 建表注释不退化 |
| `MySqlIntegrationTest.tableCommentIsWrittenAndReadBack` | 1（真库） | 写 → **读回** → 改 → 读回 → 清空 → 读回空串（真 MySQL 契约） |
| `RoutineDefinitionTest` | 3 | SQLite「读不到要优雅」· 未知对象返回空串 · 空名返回空串 |
| `MySqlIntegrationTest.functionDefinitionIsReadBack` | 1（真库） | 真 MySQL 上函数定义体**读得回** |

---

## 20. 第十二轮：多连接并行 / 架构收尾 / 入口一致性（2026-10-22）

> 这一轮做的是**「地基」类改动**：多连接能不能并存、语句会不会挂死、连接会不会泄漏、
> 以及「用户根本不知道这个能力存在」的入口问题。

### 20.1 ⭐ U2 多连接并行 —— 真正的堵点在会话管理

| 项 | 现状 |
|---|---|
| 页面取会话 | 早就按 `tab.connectionId()` 取（`createGridPage` → `connectionFor(tab)` → `sessions.find(...)`）—— **页面层早就支持多连接** |
| **真正的堵点** | ⭐ `SessionManager.open(id, spec)` 是「**先关掉再重开**」→ 切到 B 连接时 **A 的会话被关掉了**，A 的标签页当场失效 |
| 修法 | `open` 改成**复用**已开的会话；新增 `reopen` 给「重新连接」用（用户点重连就是要换新的） |
| 附带 | `DbSession.isClosed()` 新增（`JdbcSession` / `PooledSession` 实现） |

> 门禁：`SessionManagerTest` 升级断言 + 新增「两个连接并存」一例。

### 20.2 架构收尾

| # | 项 | 修法 |
|---|---|---|
| **A10** | **语句超时**（以前只有连接超时，语句跑起来后没有时限） | `QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS = 30`（上限 600）+ `JdbcSession` 每条语句 `setQueryTimeout` + 超时转成**人话**（「查询超时（30 秒）—— 试试加条件筛选」） |
| **A12** | **连接池归还**（借出的连接一直没还，池子关不掉） | `PooledSession.close()` 先 `connection.close()`（归还 Hikari）再 `dataSource.close()`；门禁断言关会话后池里**没有在借的连接** |
| **A18** | 「跟随系统」跨平台 | Windows 读注册表 `AppsUseLightTheme`、Linux 读 `gsettings` / `kdeglobals` |
| **A22** | 主题探测每次 shell out（400ms 超时） | 探测结果**缓存**，只探一次 |

### 20.3 入口与一致性

| # | 项 | 修法 |
|---|---|---|
| **X5** | `object.*` 动作在 ⌘K 里**搜不到**（未选中时禁用 → 被过滤掉） | 命令面板**不再过滤禁用项**（灰掉但可见，点击给出解释）；新增顶级「**对象**」菜单（Navicat 形态） |
| **U6** | 状态栏没有**服务器版本 / 当前用户** | `Dialect.serverInfoSql()`（三方言）+ 状态栏新增「服务器」段（版本 · 用户），后台读不阻塞连接 |
| **V-11** | 弹窗宽度随手写 | 新增 `DialogSizes` 三档（表单 480 / 长文本 560 / 宽表格 720），10 个弹窗统一 |
| **X6** | 「收藏夹」顶级菜单只有 1 项（顶级菜单资源被浪费） | 新增「清空收藏」动作（`SideBar.clearStarred()`，收藏落盘）+ 菜单补项 |
| **X7** | 恢复上次标签后，活动标签是「最后恢复的那个」 | `TabBar.activatePermanent()` —— 恢复完落回**常驻的「对象」标签** |
| **V-12** | 错误只把驱动英文原文甩给用户 | 🆕 `ErrorClassifier`（8 类：连接断开/权限/不存在/超时/锁冲突/约束/语法/其它），每类给「说明 + **建议动作**」；**5 处重复的 `readable()` 统一走它** |
| **Q6** | **代码片段库**（与已保存查询同源，但用法不同：插入 vs 执行） | `SavedQueries.Entry` 加 `kind`（查询/片段）· 查询页「存为代码片段」（存**选中的文本**）·「插入代码片段…」（弹窗选片段 → 插到光标处）· 已保存查询页显示类型 |
| **A25** | 声明层有「达梦」而运行时方言没有 | **不删声明**（不可逆），而是**显式登记 + 门禁**：`DeclaredDialectConsistencyTest` 断言「声明里有的连接必须要么有运行时实现、要么进白名单并写明原因」，反向也断言「运行时有的方言声明层必须有」 |
| **A15 / A16** | 连接建立与内省跑在 **UI 线程**；所有连接共用**一条**执行器 | 🆕 `DbExecutors`：**每连接一条单线程执行器**（同连接仍串行、跨连接互不阻塞）+ 连接建立/内省**移出 UI 线程**（界面先回话，结果到了再刷新） |
| **P7** | **分发**（jlink / jpackage / 崩溃日志） | ✅ 崩溃日志：`CrashLog`（未捕获异常 → `~/.agenticdb/logs/crash-*.log`，含版本/系统/堆栈）· ✅ 一键出包：`scripts/package.sh`（jpackage：app-image / dmg / msi / deb）· ⚠️ **jlink 暂不可用**：RichTextFX / ControlFX 是**自动模块**（无 module-info），jlink 收不进去 —— 等它们模块化后再加 jlink profile |
| ⭐ 顺带修掉的**两个真实缺陷** | ① 同一连接的事务被拆到**两个线程**（服务器信息查询走了全局执行器）→ SQLite 这类「一连接一线程」的库互相卡住，数据页一直转圈；② **窗口尺寸不夹到屏幕内** → 换小屏后分页条/状态栏被顶到可视区外，**点不到「下一页」** | 全部连接相关的工作统一走 `executors.forConnection(id)`；`restoreWindow` 把宽高与位置夹到 `Screen.getVisualBounds()` |

### 20.4 O1 调度事件（MySQL EVENT）

`ObjectKind.EVENT` + `Dialect.eventsListSql()`（MySQL `information_schema.EVENTS`）+
`Dialect.readEventDefinition()`（`SHOW CREATE EVENT`）→ 对象树多一个「事件」分组（**只有 MySQL 有**，
其它方言这一组是空的 → 自动摘除，不假装有）；事件也走「定义体」只读标签页。

---

## 21. 剩余清单（2026-10-22 末 · 如实）

> 前面十二轮把「能信 / 能改结构 / 能安全写 / 多连接并存 / 界面有规格」都做到了。
> 下面这些**确实还没做** —— 每条都写清「为什么现在不做 / 做了值不值」，
> 免得它们在清单里躺着又被当成「快好了」。

### 21.1 功能类

| # | 项 | 难度 | 为什么现在不做 / 建议顺序 |
|---|---|---|---|
| **G1** | **冻结列**（前 N 列固定） | 🔴 高 | JavaFX 没有原生冻结列，要两个 TableView 并排 + 滚动/选择/排序/编辑**四路同步**。上一轮试过又整体回滚（半成品比没有更糟）。**建议最后做，且必须一次性做全** |
| **G4** | **单元格区域选择**（矩形选区 + 复制矩形） | 🟠 中 | JavaFX `TableView` 没有原生选区，要自绘 cell 渲染。价值中等（批量复制整块数据） |
| **Q5** | **查询结果分页** | 🟠 中 | 现在结果集一次性装进 `ObservableList`（上限 1000 行）。改成服务端分页要重写「结果集 → 表格」这一层的取数逻辑（数据页已经是服务端分页，查询页还没跟上） |
| ~~**Q6**~~ ✅ | ~~**代码片段库**~~ | — | ✅ **已完成（第十二轮）** |
| **U1** | **catalog 树**（MySQL 多库） | 🟠 中 | MySQL 的 schema 就是数据库，切换 catalog 要**重连**（不能像 PG 那样 `SET search_path`）。要与「多连接并存」一起设计：切库 = 换连接 |
| ~~**U3**~~ ✅ | ~~**连接属性页**~~ | — | ✅ **已完成（第十二轮·续三）**：弹窗套 TabPane（常规/SSL/高级）+ `options` 映射 |
| **X3 / X4** | 状态栏剩余项 / 仍为半孤儿的动作 | 🟢 低 | X5 已把对象动作收进顶级菜单，孤儿动作大幅减少；剩下的多为一���性动作 |

### 21.2 架构类

| # | 项 | 难度 | 建议 |
|---|---|---|---|
| ~~**A15 / A16**~~ ✅ | ~~连接建立与内省移出 UI 线程 + 每连接一个执行器~~ | — | ✅ **已完成（第十二轮）**：`DbExecutors` + 后台连接 |
| ~~**A25**~~ ✅ | ~~`declarations/` 与运行时方言不一致（达梦）~~ | — | ✅ **已完成（第十二轮）**：显式白名单 + 门禁（不删声明） |
| **A20** | `MAX_CELL_BYTES` 声明未用 | 🟢 低 | 详情抽屉已按需取数，单元格不再全量传输 —— 那就把常量删掉 |

### 21.3 界面类

| # | 项 | 难度 | 建议 |
|---|---|---|---|
| **V-5** | 工具栏按宽度折叠（≤7 常显 + 更多） | 🟡 中 | **实测 1280 宽下 12 个图标按钮放得下** —— 现在做是过度设计。建议等真出现窄屏问题再做 |
| **V-6** | 应用图标 | 🟢 低 | 需要出图资源（PNG / ICNS）。工程上没问题，缺的是设计稿 |
| **V-9** | 表格规格统一（类型行 / 记录导航条） | 🟠 中 | 数据页与查询页两套网格规格不同。与 Q5 一起做最省事（都要动结果区） |
| **V-12 剩余** | 错误**详情弹窗** / 批量确认汇总页 | 🟠 中 | 错误**归类**已做（第十二轮 `ErrorClassifier`）；剩下的是「详情弹窗」与「批量汇总页」 |

### 21.4 分发类

| # | 项 | 难度 | 建议 |
|---|---|---|---|
| ~~**P7**~~ ✅ | ~~jlink / jpackage / 崩溃日志~~ | — | ✅ **jpackage + 崩溃日志已完成**（第十二轮）；**jlink 暂不可行**（自动模块）；剩：自动更新、可访问性对比度检查（V-8 已做对比度门禁） |

---

## 22. 第十三轮：Navicat 表清单与观感对齐（2026-10-22）

> 触发：用户给出 `docs/screenshots/navicat/side-by-side-2026-10.png`，要求按
> **功能 / 字体 / 布局 / 颜色 / 数据 / 字号 / 菜单 / 状态栏** 八个维度逐项对比，
> 并「做到和 Navicat 一样的体验」。
>
> 完整像素测量、刻意不追的四项、下一批优先级见
> **`docs/NAVICAT-PARITY-2026-10-V5.md`**。

### 22.1 已完成

| # | 改动 | 证据 |
|---|---|---|
| **N0-1** | `TableMeta` **5 → 8 字段**：补修改时间 / 排序规则 / 注释；保留 5 参兼容构造器 | `TableMeta.java` · `TableMetaTest`（6 例） |
| **N0-2** | MySQL / PostgreSQL 补 3 列内省；SQLite 保持 `null` → 「—」 | `SchemaService` · `Dialect.tableMetaSql()` · `MySqlIntegrationTest` |
| **N0-3** | 重建**表清单页**：8 列、类型图标、数值右对齐/数值排序、搜索、打开、刷新、防重入 | `TableListPage` · `TableListPageTest`（9 例） |
| **N0-4** | **对象首页 9 列**（多「类型」）+ 3 列元数据 + 密度 + 搜索 | `ObjectHomePage` · `ObjectHomePageTest` |
| **N0-5** | 菜单补 `object.tables`（对象 → 表清单） | `MenuBarFactory` · `MenuBarFactoryTest` |
| **N0-6** | 密度收紧为 **24 / 28 / 34**；应用栏 46 / 标签栏 36 / 工具栏 38 | `tokens.json` · `Tokens.java` · `RowHeights` · `DensityCoverageTest` |
| **N0-7** | 新增 `bg-sidebar`（亮 `#F1F3F5` / 暗 `#172033`），侧栏/状态栏使用；内容区纯白 | `tokens.json` · `app.css` · `TokenParityTest` |
| **N0-8** | 表清单/对象首页**去斑马纹**；四张表**空行不画线、不铺色**（显式覆盖 Modena） | `app.css` · 重拍截图 |
| **N0-9** | 表头 11 → **12px bold**，与 Navicat 一致；弹窗列编辑器同样白底 | `app.css` |
| **N0-10** | 空收藏区不再显示「右键对象 → 收藏」占位 | `SideBar` |
| **N0-11** | 关闭标签时回收表清单页缓存 | `WorkspaceView` |
| **N0-12** | 新增 `ADB_TABLES=1` 截图开关；`shots.sh` 扩到 **10 张**（含 `p6-tables-grid`） | `AppShell` · `scripts/shots.sh` |
| **N0-13** | 重做**等比例**并排图（两边 1×，不再把 1280 拉伸到 1920） | `docs/screenshots/navicat/side-by-side-2026-10.png` |

### 22.2 本轮验收

```bash
./mvnw -B clean verify
java tools/TestCount.java
./scripts/shots.sh
```

**实测：644 条 / 84 类 / 0 失败，35 条跳过**（2026-10-22，含 N1–N5）。

| 门禁 | 覆盖 |
|---|---|
| `TableMetaTest` | 8 字段格式、5 参兼容、方言 SQL 别名 |
| `TableListPageTest` | 8 列、数值排序、搜索、打开、禁用态、多选批量删除、视图切换、选中文案 |
| `ObjectHomePageTest` | 9 列、类型筛选、密度 |
| `DensityCoverageTest` | 4 张表跟随 24/28/34 |
| `TokenParityTest` / `AppCssAuditTest` | 令牌亮暗一致、无颜色字面量、无阶梯外字号 |
| `AppShellTest` / `MenuBarFactoryTest` | 新入口、截图开关、对象菜单 |

### 22.3 ✅ N1–N5 已完成（2026-10-22）

| # | 改动 | 证据 |
|---|---|---|
| **N1** | 表清单工具条补「新建表 / 删除表 / 设计表」；删除与设计按选择启用，危险动作仍走 SQL 预览 + 二次确认 | `TableListPage` · `TableDdl` · `TableListPageTest`（禁用态 / 设计动作） |
| **N2** | 表清单右键菜单：打开 / 设计 / 新建 / 重命名 / 清空 / 删除 / 刷新；菜单项与工具条共用 `ActionRegistry` 里的同一组 `Action` | `ActionRegistry.menuItemFor` · `TableListPageTest.contextMenuComesFromTheSameActionSet` |
| **N3** | 表格 `MULTIPLE` 多选；删除 N 张表时给一张汇总确认页，逐表执行并报告失败行（成功的不回滚） | `StructureSqlBuilder.dropTables` · `TableDdl.dropTables` · `TableDdlTest`（2 例） |
| **N4** | 工具条右侧列表 / 网格两个图标钮；网格为卡片（行数 / 大小 / 引擎 / 修改时间 / 注释）；双向同步选择；`ui.tableList.view` 记忆 | `TableListPage` · `Icons.LIST/GRID` · `app.css` · `TableListPageTest`（2 例） |
| **N5** | 状态栏选中项文案：单表「已选 users · 200 行」，多选「已选 3 个项目」，清空「未选择」 | `StatusBar.setSelectionText` · `StatusBarTest` · `AppShellTest`（状态栏接线） |

截图新增 **`p6-tables-grid`**（`ADB_TABLE_VIEW=grid`），`shots.sh` 现为 **10 张**。

### 22.4 剩余（P2）

| # | 项 | 说明 |
|---|---|---|
| **N6** | 列宽记忆 / 长注释 Tooltip / MySQL 数据长度 B/KB/MB/GB 联调 | 需要真库验证；不与 N1–N5 混做 |
| — | 表头文字颜色是否升到 `text-primary` | 对比度已达标，先观察一轮 |

---

## 23. 变更记录

| 日期 | 轮次 | 内容 |
|---|---|---|
| 2026-10-22 | 第十三轮 | **Navicat 表清单/观感对齐**：`TableMeta` 8 字段（+修改时间/排序规则/注释）· 表清单 8 列 · 对象首页 9 列 · 密度 **24/28/34** · 应用栏 46 / 标签栏 36 / 工具栏 38 · `bg-sidebar` 灰底 · 去斑马纹/空行假线 · 表头 12px · `ADB_TABLES` + 9 张截图 · 等比例并排图 · 新增 **`docs/NAVICAT-PARITY-2026-10-V5.md`** |
| 2026-10-22 | 第九轮 | 新增 `docs/EXEC-SUMMARY.md`、`docs/GITHUB-COMPARE.md`；`NAVICAT-TODO.md` 状态基线（512/64）、§13.5 U-B5 漂移纠正、§16.4 下一步补齐；`README.md` / `docs/NEXT-STEPS.md` / 记忆同步 |
| 2026-10-22 | 第十轮 | 新增 **`docs/UI-VISUAL-SPEC.md`**（UI 规格书）；修 `row-tree` / `kind-index` 两个摆设令牌 + `.adb-error` 缺样式；实测发现 **43 个未定义样式类**；`NAVICAT-TODO.md` 新增 §18（V-1…V-12 + S-1…S-7）；门禁 **585 条 / 77 类 / 0 失败** |
| 2026-10-22 | 第十二轮 | **U2 ✅ 多连接并存**（`SessionManager.open` 改为复用会话 + `reopen`）· **A10 ✅ 语句超时**（30s + 人话提示）· **A12 ✅ 连接池归还** · **A18/A22 ✅ 跨平台主题探测 + 缓存** · **O1 ✅ MySQL 事件**（对象树 + 定义体）· **X5 ✅ ⌘K 能搜到对象动作 + 顶级「对象」菜单** · **X6 ✅ 清空收藏** · **X7 ✅ 恢复后落回对象首页** · **U6 ✅ 状态栏服务器信息** · **V-11 ✅ 弹窗宽度三档** · **V-12 ✅ 错误归类**（`ErrorClassifier`，5 处 `readable()` 统一）；门禁 **585 条 / 77 类 / 0 失败**；并新增 **§21 剩余清单**（如实列出未做项与理由） |
| 2026-10-22 | 第十一轮·续二 | **O2 ✅ 函数/过程定义体**：`Dialect.readRoutineDefinition`（SQLite/PG/MySQL 三种来源）+ `RoutinePage` **只读标签页** + 复制按钮 + 对象树入口；新门禁 `RoutineDefinitionTest`(3) + MySQL 真库函数定义体契约(1) |
| 2026-10-22 | 第十一轮·续 | **B5-S4 ✅ 表注释**：读（`tableCommentQuery` + `SchemaService`）· 写/清空（`setTableComment` + `clearTableCommentSql`）· 结构页展示 + 弹窗编辑（SQLite 禁用）· 新门禁 `TableCommentDdlTest`(5) + MySQL 真库往返契约(1) |
| 2026-10-22 | 第十一轮 | **D3 ✅**（CHECK：模型+解析器+方言+内省+重建搬运+结构页「检查」页签）· **B5-S2 ✅**（外键可增删，弹窗）· **B5-S3 ✅**（触发器可增删，弹窗）· ⭐ 修掉**带外键的表改列必失败**的潜伏 bug（动作码没翻译，翻译收进方言层）· 新门禁 `CheckConstraintTest`(7) + `ForeignKeyTriggerDdlTest`(11) |
| 2026-10-22 | 第十轮·收尾 | **S-1…S-6 全部补齐**（43 → 0，白名单清空）；**V-1/V-2/V-3/V-4/V-7/V-8/V-10 完成**；新门禁 `ContrastTest` / `StatusBarTest` / `EmptyStateTest`（+10 条 / +3 类）；对比度不足的 **6 个色值**已调；门禁 **585 条 / 77 类 / 0 失败** |

---
