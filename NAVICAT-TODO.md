# NAVICAT-TODO — Java 桌面端执行清单

> **唯一执行清单。** 记录当前状态、下一步、验收门禁。
> 架构见 `docs/ARCHITECTURE.md`；UI 规则见 `docs/UI-GUIDE.md`；
> 组件选型见 `docs/COMPONENT-SELECTION.md`；**Navicat 对标差距见 `docs/NAVICAT-GAP.md`**；
> **阶段规划（批次 / 顺序 / 门禁）见 `docs/NEXT-STEPS.md`**；
> **与 Navicat 的实拍对比见 `docs/NAVICAT-COMPARE.md`**（本机装了 Navicat Premium Lite 15）；
> **界面规格书见 `docs/UI-VISUAL-SPEC.md`**（第十轮：字号/颜色/图标/布局/组件/弹窗/交互态/键盘）；
> **对外汇报版见 `docs/EXEC-SUMMARY.md`**；**GitHub 开源对标见 `docs/GITHUB-COMPARE.md`**。
>
> 最后更新：**2026-10-24（第二十一轮执行中：已删 declarations + UX-0 / UX-1 / UX-2 / UX-3 / UX-4 已完成，见 §34）**
> 当前基线：**P0–P4 ✅ / P5 🟡 / P6 🟡**；门禁 **754 条 / 97 类 / 0 失败**（2026-10-24 第二十二轮 Windows 实机实测，另有 44 条**环境门控**跳过 —— Docker/Testcontainers 不可用 + MySQL 真库环境变量未设，与 UI 改动无关）。
> 📌 **754 = 753 + S-7 的 1 条**（`AppBarTest` 品牌身份 +1、对比度 +1，新 `BrandMarkAuditTest` +2，删掉测不到真路径的 `WelcomeBrandTest` −3 → 净 **+1**）。
> 753 = 728 + UX-4 的 25 条（**`SelectionStateTest` 8 条（新类）** / **`CellDisplayTest` 6 条（新类）** / **`CellEditorTest` 4 条（新类）** / `GridInteractionTest` +7：列头菜单全集、冻结到此列、取消冻结、冻结禁用态、双击自适应、行操作条状态、放弃修改与只读隐藏）；728 的构成见 §34.2 UX-3 落地记录。
> 📌 **712 是删除 `declarations` 后的新口径**（-43 条 / -3 类：模块 40 + `DeclaredDialectConsistencyTest` 3），属**主动减面**不是回归。
>
> 📌 **关于门禁数字的口径（第十八轮勘误）**：第十七轮记的「721 / 29 跳过」是在 **Docker 可用**时测的；
> 本轮 `adb-ui-shell` 单模块 **278 → 285（+7 = 本轮 7 条新门禁）**、测试类 **89 → 91（+2 = 两个静态审计门禁）**，
> 这两处是**逐条对得上**的。总数从 721 降到 717、跳过从 29 升到 44，**全部来自外部真库矩阵的环境开关**
> （`adb-jdbc` 跳过 30、`adb-introspect` 跳过 14），**不是任何测试变红**。硬门禁始终是「**0 失败**」，不是总数。
>
> ⭐ **本轮（第十三轮）是「对齐 Navicat 观感」的一轮，不是功能扩张。**
> 逐像素量了 Navicat 的表清单：8 列、19px 行高、纯白行、1px 行线、灰色侧栏。
> 已落地：表清单 8 列、对象首页 9 列、密度 24/28/34、去斑马纹/空行假线、表头 12px、右键菜单、视图切换、状态栏文案、N1–N5 全部功能。
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
> **第十四轮已完成 U1 ✅ catalog 树**（MySQL 多库：虚拟连接 + 对象树库一层 + 双击切库）。
> **第十五轮已完成 Q5 ✅ 结果分页 + V-9 ✅ 表格规格统一**（服务端分页 + 翻页条 + 列类型条，与数据页 record-nav / 类型行同一套视觉）。
> **U-B6 功能补齐层已全部收官**（G1–G7 / Q5 / Q6 / U1–U6 全部 ✅）。
> **下一动作 = §34.3 / P2-1（表清单列宽记忆 + 长注释 Tooltip + `sizeText` 补 GB）**；
> UX-0 / UX-1 / UX-2 / UX-3 / UX-4 / S-7 / **UX-5** 已完成（截图可复现 + 令牌收敛 + 四档密度与 chrome 收薄 + 工具条动作可发现性 + 网格交互 + 品牌落点 + 侧栏/标签/状态栏同一套语义），`declarations` 已删除（当前基线 **773/100**，截图 **13 张**），
> P2/P5/P6 的批次已全部裁定（同意做），排在其后。
> 详见 §34 与 `docs/UI-ROADMAP.md` §12。
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
| P2 数据页 | ✅ 完成 | 排序/筛选/条件构造器、列布局、行内编辑、撤销重做、SQL 预览保存、批量操作、详情抽屉、搜索/转置/隐藏、CSV 导出、**导入向导（目标表预选，UX-3）**、保存反向撤销 |
| P3 查询页 | ✅ 完成 | SQL 词法/分句/格式化、RichTextFX 编辑器、查找替换、多语句结果页签、取消、错误定位、执行计划、历史 |
| P4 结构页 | ✅ 完成 | 列/索引/外键/触发器/DDL 五页签、加列/改列/重命名列/删列、**索引可增删（§16.2）**、**列注释读回（§16.1）**、两级危险确认、单事务、结构变更联动刷新 |
| P4.5 契约修复 | 🟡 收尾中 | **B0–B4 已全部落地**（安全语义 / 数据正确性 / 注册表收口 / 并发 / 图标令牌）；剩 4.5-3 已做、4.5-14 收尾、4.5-23 已做 |
| P5 连接管理 | 🟡 进行中 | 连接管理页、三步向导、测试连接、钥匙串 / Secret Service 凭据、连接档案落盘、HikariCP 连接池（PG/MySQL）；**本轮补上：凭据脱敏 + 口令走 stdin + 孤儿会话回收** |
| P6 导入导出 | 🟡 进行中 | CSV / TSV / JSON Lines / SQL 导出 + CSV / TSV 导入、字段映射、坏行报告、导入导出向导；**真库联调：MySQL 5.7（9 例）→ 本轮 Docker 解锁后 PostgreSQL 16 + MySQL 8.4 Testcontainers（11 例全绿），累计抓出 4 个真 bug**（`RENAME COLUMN` 版本差异 / JDBC 类型名不带长度 / `rows` 是 MySQL 8.0 保留字 / `pg_class` 无 `relcollation` 列） |
| P7 分发 | 🟡 进行中 | ✅ jpackage（`scripts/package.sh`）+ 崩溃日志（`CrashLog`）；⬜ jlink（自动模块暂不可行）/ 自动更新 / 性能 |
| **UI/UX 专项** | ✅ **U-B1–U-B6 已完成** | **§13–§16 / §22 / §27 / §28**：U-B1–U-B6 全落地；S1 数值展示 / S2 刷新按钮 / D1 函数报错 / X1–X2 空态 / **T1–T4 表级生命周期** / **D2·D4 注释读回** / **B5-S1 索引可编辑**；第十三轮 Navicat 表清单 8 列 + 密度 24/28/34；**U-B7：侧栏改 Navicat 模型（无「工作台」，单棵「我的连接」树，查询跟着连接走）+ 按钮规格统一（§27）**；**第十七轮：规格 §6 右侧对象详情面板 + §13 新建表弹窗重构 + §9.7 底部「记录/页面」标签（§28）**；**第二十轮：S-6 可读列宽 + 横向滚动 · S-9 对象首页工具条 · D-1 SQLite 主键非空 · S-8 Windows 门禁可移植性（§33）**；**第二十一轮：UX-0 截图可复现 · UX-1 令牌收敛 · UX-2 四档密度与 chrome 收薄 · UX-3 工具条主操作「图标+文字」+ 分组 + 响应式收字（§34）** |

首版明确不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务、传输同步。

> **截图验收**：`docs/screenshots/` **12 张**真实窗口图（外壳 / 数据页 / **数据页 + 右侧对象详情面板** /
> 查询页 / 结构页 / **结构页 DDL 页签** / 连接页 / 暗色 / 表清单 / 表清单网格 / **新建表弹窗** / **新建索引弹窗**），
> 由 `ScreenshotSession`（`ADB_SHOTS` 等环境变量）可复现生成，用法见 §12.2。
> 弹窗两张靠 `ADB_NEW_TABLE=1` / `ADB_INDEX=1` 启动开关（§16.3）；
> 详情面板那张靠 `ADB_DETAILS=1`、DDL 页签那张靠 `ADB_DDL=1`（第十七轮新增：
> 面板默认隐藏，启动即打开；DDL 页签启动即选中）。

---

## 1. 门禁基线

统一命令：

```bash
./mvnw -B clean verify
```

当前结果（2026-10-24 · 第二十二轮 · Windows 实机全量 实测）：

| 场景 | 执行 | 跳过 | 失败 |
|---|---|---|---|
| **默认门禁（SQLite + Testcontainers PG16/MySQL8.4）** | **754** | 44 | 0 |
| **外部 PG / MySQL 真库**（aliCloud RDS 等，需环境变量） | 单独跑，不在默认门禁 | — | — |
| **多版本矩阵**（MySQL 5.7/8.0 + PG 13/17，`ADB_TEST_VERSION_MATRIX=1`） | 默认不跑，需显式打开 | 1 | 0 |

**97 个测试类**。跳过的是「本机没有的外部依赖」：未配置的**外部** PG / MySQL 真库
（Testcontainers 容器**需 Docker**；本机 Docker 不可用时走跳过分支）—— **跳过不算通过**，门禁统计里单列一行。

> 📌 **最新口径（2026-10-24，第二十二轮 · Windows 实机）**：**754 条 / 97 类 / 0 失败**（`./mvnw -B clean verify`
> 全绿 + `java tools/TestCount.java`；另有 44 条跳过，不计入 754）。
> 相比第二十轮的 755 / 95，**减少的 43 条 / 3 类全部来自删除 `declarations`**
> （模块 40 + `DeclaredDialectConsistencyTest` 3），**不是测试变红**；UX-0 +2 条（偏好不写回 + 截图脚本钉密度）、
> UX-1 +2 条（投影收敛 + 图标尺寸档位）、UX-2 +4 条 + 1 类（`ChromeMetricsTest` 框架高度与可见行数、
> 默认密度 compact、数据面 12px 字号）、**UX-3 +8 条 + 1 类**（`ToolbarDiscoverabilityTest` 5 条、
> 清单页导入/导出 2 条、对象首页主操作 1 条）、**UX-4 +25 条 + 3 类**（`SelectionStateTest` 8、
> `CellDisplayTest` 6、`CellEditorTest` 4、`GridInteractionTest` +7）、**S-7 净 +1**（品牌 4 条新门禁 − `WelcomeBrandTest` 3 条死代码门禁）。
>
> ⚠️ **本机环境（Windows）**：`java` 默认不在 PATH；可用的是 **JDK 27**（`~/.jdks/openjdk-27.0.1.0.2`），
> pom 的 enforcer 区间已放到 `[25,28)` —— `export JAVA_HOME="$HOME/.jdks/openjdk-27.0.1.0.2"`。
> macOS 那边仍是 IntelliJ 自带的 JDK 25（见 §30.3）。

统计口径用工具兜底：

```bash
./mvnw -B clean verify
java tools/TestCount.java      # 按 <testcase> 元素计数，并校验下限 ≥ 300
```

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `core/adb-model` | 30 | 分页/行数上限契约 |
| `core/adb-safety` | 68 | 危险分级四档、**fail-closed**、WITH 体内扫描、PRAGMA 读写、词法切分 |
| `core/adb-sql` | 31 | 词法 / 分句 / 格式化 / **括号配对** / **参数检测** |
| `workbench/adb-jdbc` | 54 | 参数化、事务、类型映射、截断探测、**取消语义**、**方言行为**、**真库契约（跳过 30）** |
| `testing/adb-fixtures` | 3 | 演示库固定种子 |
| `workbench/adb-introspect` | 126 | 内省、表元数据 8 列、分页排序筛选 SQL、结构变更、**catalog 列表与系统库过滤**、**真库契约（跳过 14）** |
| `workbench/adb-session` | 20 | 连接档案、凭据、连接池、**敏感项脱敏**、**catalog 虚拟连接约定** |
| `workbench/adb-editing` | 19 | 编辑缓冲、SQL 生成、反向撤销 |
| `workbench/adb-transfer` | 15 | 流式 CSV 编解码、四种导出格式、导入列匹配与坏行报告 |
| `ui/adb-ui-core` | 24 | 令牌键一致 / 亮暗一致 / 无颜色字面量 / **无未使用令牌** / **CSS 尺度审计** / **焦点环审计** |
| `ui/adb-ui-shell` | 324 | 外壳、数据页、查询页、结构页、连接页、向导、渲染、CSV、**表清单 8 列**、**图标语义唯一**、**注册表收口**、**保存守卫**、**禁用按钮可见性**、**网格交互**、**密度覆盖**、**catalog 树与切库分派**、**侧栏工具可达性**、**右侧对象详情面板**、**新建表弹窗（宽编辑器 SQL 预览）**、**表清单选中项联动详情面板**、**结构页 DDL 只读编辑器**、**方言感知列与卡片去破折号**、**对象树表节点展开出列（懒加载 / 缓存 / 失败可重试 / 列不是对象）**、**欢迎页品牌 mark（S-7 起改为「应用栏矢量 BRAND + 死代码不复活」双门禁）**、**列网格 Tab / 方向键导航（边界不绕圈、最后一格放行 Tab）**、**对象首页级工具条 / 可读列宽 / SQLite 主键非空** |

> ⚠️ **统计口径（4.5-28）**：用 `@Nested` 的 2 个测试类（`QueryLimitsTest`、
> `DangerClassifierTest`）在 surefire XML 里
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
- [x] **schema 切换器**：✅ 已落地 —— `SchemaService.listSchemas` + 侧栏下拉（>1 个 schema 才显示）；PG 走 `SET search_path`。**catalog 树也已完成（第十四轮 U1）** —— MySQL 的 schema 就是数据库，走对象树的 catalog 层（切库 = 换虚拟连接，见 §24）
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
- [x] **`declarations/` 已删除**（第二十一轮 §34.1：模块 + 根目录 YAML + POM + 测试依赖 + 一致性门禁）

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
| A2 | `declarations/` 1500 行是死代码 | **2026-10-24 已执行删除**（模块 + 根目录 YAML + POM + 测试依赖 + `DeclaredDialectConsistencyTest`），基线 755→712 | P5 | ✅ |
| A3 | `loadFromClasspath()` 不可用（jar 索引解析错误） | **已修**：枚举目录（爆炸 classpath）/ jar 条目（打包），两条路径实测一致 | P4.5 | ✅ |
| ~~A4~~ | ~~`paramStyle` / `identifierQuote` 被硬编码~~ **标识符引用已进 `Dialect`**；**参数风格仍固定 `?`** | **2026-10-24 裁定：不做** —— JDBC 全用 `?`，`numbered` 无消费方；写进不做清单 | P5 | ❌ **不做** |
| ~~A5~~ | ~~元数据只读当前 schema~~ **schema 已落地**：`getSchemas()` 接上了，侧栏有 **schema 切换器**（>1 个才显示），PG 用 `SET search_path` 切。**catalog 也已落地（第十四轮 U1，见 §24）**：MySQL 走对象树 catalog 层（切库 = 换虚拟连接）；`getColumns()` 已接；**`getExportedKeys()` 仍未接**（2026-10-24 复核：全仓 0 引用）→ 排进 P5-4 | P5 | 🟡 |
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
| A20 | `MAX_CELL_BYTES` 声明未用 | ✅ **已删（第二十轮）**，全仓 0 引用 | P4.5 | ✅ |
| **A21** 🆕 | **切连接泄漏会话** | **已做**：回收没有标签页引用的会话 | P5 | ✅ |
| **A22** 🆕 | ~~**主题探测每次 shell out**~~ | ✅ **已完成（第十二轮）**：探测结果缓存，只探一次 | P7 | ✅ |
| **A23** 🆕 | **死代码 / 死引用** | **已做**：删 `QueryOptions`、修 `DeclarationCheckMojo` 悬空引用、`PageSize` 改为 UI 档位来源、清 `MainToolBar` 只写字段与未用 import | P4.5 | ✅ |
| **A24** 🆕 | **surefire 统计口径失真** | CI 按 `<testcase>` 计数 + 断言总数下限 | P4.5 | ⬜ **（4.5-28）** |
| **A25** 🆕 | ~~**`declarations` 与运行时方言不一致**~~ | **被 §34 取代（2026-10-24）**：不再维护声明源，直接删除模块；运行时方言由 `Dialect` + `DialectBranchAuditTest` 守住 | P5 | ✅ **改判为删除** |

---

## 11. P4.6 数据网格与查询页进阶（⬜ 新增，Navicat 对标）

> 不属于「契约修复」，是**功能差距**。按收益排序；每项一个门禁。

### 11.1 数据网格

| # | 任务 | 说明 |
|---|---|---|
| ~~G1~~ ✅ | ~~**冻结 / 固定列**~~ **已完成（第十五轮·续二）**：左表（选择列 + 行号列 + 前 N 个数据列）+ 右表并排，移动的是**同一批列实例**；滚动 / 选择双向同步，行高与 items 共享；视图菜单「冻结左侧列」1–3 档 + 取消 | ✅ | `DataGridPage.setFrozenColumnCount` + `DataGridPageTest.frozenColumnsStayPut` |
| ~~G2~~ ✅ | ~~列头拖拽重排~~ **已完成**（`f1334cf`，见 §13.6） |
| ~~G3~~ ✅ | ~~列汇总脚注行~~ **已完成**：「视图 → 本页统计」显示行数与数值列的合计/平均/最小/最大（算的是**当前页**） | ✅ | `DataGridPage.updateSummary` |
| ~~G4~~ ✅ | ~~**单元格区域选择**~~ **已完成（第十五轮·续）**：`GridRectangle` 矩形选区（按下定锚点 / 拖动扩展 / Shift 点击扩展）+ 矩形内 cell 高亮 `adb-grid__cell--rect` + ⌘C 有矩形时复制这块区域为 TSV（不含表头）+ Esc 取消 + 翻页自动清除 | ✅ | `DataGridPage.selectRectangle` / `rectangleText` + `DataGridPageTest.rectangleSelectionCopies` |
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
| ~~Q5~~ ✅ | ~~**结果集分页**~~ **已完成（第十五轮）**：`QueryPaginator` 把任意 SELECT 改写成 `SELECT * FROM (<原句>) AS _p LIMIT ? OFFSET ?` + `COUNT(*)` 套同一层；查询页可分页结果走 `PagedLoader`（首/上/下/末 + 页码 + 每页下拉 + 跳页 + 绝对行号），非分页与截断逻辑保留 | ✅ | `QueryPaginatorTest`(8) + `QueryPageTest`(+3 分页用例) |
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
`StatusBar` 的 `DEV` / `未选择` / `自动提交` 三个字段 —— 都是**只有位置没有内容**的占位。
按本项目规则：**占位要么接上真功能，要么删掉**。
（`core/adb-declarations` 已按此原则于 2026-10-24 整体删除。）

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
      （⚠️ **`fs-4xl` 40 已于第十九轮批次 C 移除** —— 它唯一的消费者是欢迎页 logo 的
      `font-size`，S-5 把 logo 换成品牌 PNG 后尺寸改由 `Icons.SIZE_BRAND` 在代码里定，
      留着就是摆设 → `TokenParityTest.everyTokenIsActuallyUsed` 会红。
      ⚠️ **`fs-2xl` 20 同理已于第二十二轮 S-7 移除**（唯一消费者是那张卡片的标题，而整页不可达）——
      **现在是 7 档**。）
- [x] `GenTokens` 生成字号令牌（暗色同值 —— 字号不随主题变）；令牌总数 60 → **80**
- [x] `app.css` 全部字面量字号改走阶梯（**79 处声明、13 种 → 9 种**（现 7 种，见上））
- [x] **9px 全面废除**（`.adb-rail__dialect` / `.adb-tab__dirty` / `.adb-tab__close`）
- [x] 10px 只允许留在**单字符徽标 / 角标**；状态栏 / 列类型行 / 功能栏文字 / 分组标题 **升到 11px**
- [x] 删掉 **12.5px 孤例**
- [x] 字体族统一 `-fx-font-family: "System"`（本来就是，已核对）
- [x] 欢迎页大标题 60px → 40px；页面标题 18px → 16px；空状态图标 34px → 32px

**门禁**：`AppCssAuditTest`（🆕）7 例 —— 字号必须落在阶梯上（当时 8 档，原 9 档，现 7 档，见上）、无 10px 以下、
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
| ~~**G1**~~ ✅ | ~~**冻结列**（两表四路同步：滚动 / 选择 / 排序 / 编辑）~~ **✅ 已完成（第十五轮·续二）**：左/右两表并排，滚动与选择双向同步 | `DataGridPageTest.frozenColumnsStayPut` | ✅ |
| ~~**G4**~~ ✅ | ~~单元格**区域选择**（拖选 / Shift 范围 / 复制矩形）~~ **✅ 已完成（第十五轮·续）**：`GridRectangle` + 拖选 / Shift 扩展 + ⌘C 复制矩形 TSV | `DataGridPageTest.rectangleSelectionCopies` | ✅ |
| ~~G6~~ ✅ | ~~自增列插入（新增行时主键留空交给数据库）~~ **已完成** | `GridInteractionTest` + `MySqlIntegrationTest`（真库） | ✅ |
| ~~**Q5**~~ ✅ | ~~查询结果**分页**（现在硬上限 1000）~~ **✅ 已完成（第十五轮）**：`QueryPaginator` 服务端分页改写 + `PagedLoader` 翻页条 | `QueryPageTest`（分页 3 例） | ✅ |
| **Q6** | 代码片段库（与已保存查询合并） | ✅ **已完成（第十二轮）**：`SavedQueries` 加 `kind`（查询/片段）· 查询页「存为片段 / 插入片段」 | `SavedQueriesTest`（+2 例） |
| ~~**U1**~~ ✅ | ~~**catalog 树**（MySQL 多库；MySQL 的 schema = 数据库，`USE` 不管用，需切后重连）~~ **✅ 已完成（第十四轮）**：`Dialect.switchesCatalogByReconnect()` + `catalogsSql()`（information_schema.SCHEMATA，过滤系统库）· `SchemaService.listCatalogs` · `ConnectionProfile.catalogVariant`（虚拟连接 `父id@库名`，不落盘）· 侧栏 catalog 层（懒加载 / 当前库加粗 / 双击或右键切库）· `TableDdl` 全部入口按连接 id 分派 | `CatalogTreeTest`(5) + `CatalogVariantProfileTest`(5) + `SideBarTreeTest`(+5) + `AppShellTest`(+1) |
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
   `Detected JDK version 21.0.12-1 ... is not in the allowed range [25,28)`。
   现在编译**之前**就说清楚，并给出修正命令：
   ```
   ✗ JDK 版本不对：当前 24，pom.xml 要求 [25,28)。
      当前 JAVA_HOME = 未设置
      修正：让 JAVA_HOME 指向 [25,28) 内的 JDK。
      macOS 示例：export JAVA_HOME=$(/usr/libexec/java_home -v 27)
   ```
   要求版本从 `pom.xml` 的 `requireJavaVersion` **解析**得来（下限和上限都查，28 也会被拦），
   不硬编码 —— 改了 pom 脚本自动跟上。
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
- **U-B6 已全部完成**：G1 冻结列 ✅（见 §25.5）· U1 catalog 树 ✅（§24）· G4 区域选择 / Q5 结果分页 ✅（§25）· U2/U3/U6/Q6 ✅
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
| **V-9** | 表格规格统一（类型行 / 记录导航条 / 结果区） | P2 | ✅ **已完成（第十五轮，随 Q5 一起做）** | 查询页结果加「列类型条」`adb-query__result-types`（与数据页类型行同一套视觉）+ 翻页条复用 `adb-record-nav` / `adb-page-size`；绝对行号与数据页对齐 | `QueryPageTest`(+3) |
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
| ~~**G1**~~ ✅ | ~~**冻结列**（前 N 列固定）~~ | — | ✅ **已完成（第十五轮·续二，U-B6 收官）**：两表并排，移动的是同一批 `TableColumn` 实例（列宽/可见性/cellFactory 不丢）；滚动条双向绑定（skin 就绪后才拿得到，headless 下安静跳过）；选择用防循环标志双向同步；**排序无需同步** —— 项目是服务端排序（`setSortable(false)` + `DataQuery` 重查），两边列头触发的是同一次重查；编辑后两边都 refresh。默认 0 列 = 不冻结，布局与旧版完全等价 |
| ~~**G4**~~ ✅ | ~~**单元格区域选择**（矩形选区 + 复制矩形）~~ | — | ✅ **已完成（第十五轮·续）**：不切换 `cellSelectionEnabled`（会破坏行级批量操作），改为**独立的矩形选区模型** —— 按下定锚点 / 拖动扩展 / Shift 点击扩展，矩形内 cell 加 `adb-grid__cell--rect`，⌘C 有矩形时复制这块区域。翻页自动清除，Esc 取消 |
| ~~**Q5**~~ ✅ | ~~**查询结果分页**~~ | — | ✅ **已完成（第十五轮）**：`QueryPaginator` 服务端分页改写 + `PagedLoader` 翻页条；查询页与数据页共用 LIMIT/OFFSET 模型 |
| ~~**Q6**~~ ✅ | ~~**代码片段库**~~ | — | ✅ **已完成（第十二轮）** |
| ~~**U1**~~ ✅ | ~~**catalog 树**（MySQL 多库）~~ | — | ✅ **已完成（第十四轮，见 §24）**：切库 = 换虚拟连接（`父id@库名`，只活在内存）；对象树多出库一层，SQLite / PG 形态不变 |
| ~~**U3**~~ ✅ | ~~**连接属性页**~~ | — | ✅ **已完成（第十二轮·续三）**：弹窗套 TabPane（常规/SSL/高级）+ `options` 映射 |
| ~~**X3**~~ ✅ | ~~状态栏版本号硬编码~~ | — | ✅ **已完成（第十轮 V-3）**：`AppVersion` + `version.properties`（Maven 资源过滤），状态栏与「关于」共用；门禁 `StatusBarTest`。**§21.1 原把它和 X4 挂在一起是漏记**，第十八轮勘误 |
| **X4** | 仍为「半孤儿」的动作 | 🟢 低 | X5 已把对象动作收进 ⌘K + 顶级「对象」菜单，孤儿大幅减少；`ActionRegistryConvergenceTest` 守着「侧栏/标签栏右键必须走注册表」「曾孤儿的动作要有菜单入口」。剩下的多为一次性动作（`tab.copy-*` 等），**建议不再为它们单独造菜单，而是把「全部动作可搜」当成口径**（已有 ⌘K） |

### 21.2 架构类

| # | 项 | 难度 | 建议 |
|---|---|---|---|
| ~~**A15 / A16**~~ ✅ | ~~连接建立与内省移出 UI 线程 + 每连接一个执行器~~ | — | ✅ **已完成（第十二轮）**：`DbExecutors` + 后台连接 |
| ~~**A25**~~ ✅ | ~~`declarations/` 与运行时方言不一致（达梦）~~ | — | ✅ **已完成（第十二轮）**：显式白名单 + 门禁；**第二十一轮随声明层整体删除而移除** |
| **A20** | `MAX_CELL_BYTES` 声明未用 | 🟢 低 | 详情抽屉已按需取数，单元格不再全量传输 —— 那就把常量删掉 |

### 21.3 界面类

| # | 项 | 难度 | 建议 |
|---|---|---|---|
| **V-5** | 工具栏按宽度折叠（≤7 常显 + 更多） | 🟡 中 | **实测 1280 宽下 12 个图标按钮放得下** —— 现在做是过度设计。建议等真出现窄屏问题再做 |
| **V-6** | 应用图标 | 🟢 低 | 需要出图资源（PNG / ICNS）。工程上没问题，缺的是设计稿 |
| ~~**V-9**~~ ✅ | ~~表格规格统一（类型行 / 记录导航条）~~ | — | ✅ **已完成（第十五轮，随 Q5 一起做）**：查询结果加列类型条 `adb-query__result-types` + 翻页条复用 `adb-record-nav`/`adb-page-size`，与数据页同一套视觉 |
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

## 24. 第十四轮：U1 catalog 树 —— MySQL 多数据库浏览与切换（2026-10-22）

> 触发：U1 是 U-B6 剩余清单里**唯一一个「用户每天都会撞上」**的功能项 ——
> MySQL 实例上通常不止一个库，而旧版只能看到连接时指定的那一个，
> 看另一个库必须去改连接档案、重连。Navicat 的对象树是「连接 → 数据库 → 表」。

### 24.1 设计：为什么切库 = 换「虚拟连接」

MySQL 的 schema 就是数据库：一条连接绑定一个库。`USE other_db` 对**单条**
连接有效，但服务端有连接池（HikariCP），随时可能换一条连接还回来，
「当前库」这个会话状态管不住 —— 所以切库必须**重连**。

落点选在第十二轮已经铺好的「多连接并存」模型上，不新造机制：

```
连接档案 srv-1（database=orders）
├── catalog 节点 orders   → 由 srv-1 自己 Serve（复用现成会话）
└── catalog 节点 audit    → 虚拟连接 srv-1@audit（同主机/同口令/不同库）
                             只活在内存（AppShell.catalogVariants），不进 connections.json
```

- `ConnectionProfile.catalogVariant(catalog)` 派生虚拟档案；
  `catalogVariantId / isCatalogVariantId / parentOfCatalogVariant / catalogOfCatalogVariant`
  五个静态工具收口 id 约定（分隔符 `@`）。
- 凭据仍走**父连接**的钥匙串条目（`specOf` 按 variant id 反查父 id）——
  不会因为切库重新问一次口令。
- 「切换到此数据库」= 激活虚拟连接（双击 / 右键 / 菜单同一个动作），
  顶栏下拉里能看到它，但连接管理页不出现（不是用户档案）。

### 24.2 分层落地

| 层 | 改动 |
|---|---|
| 方言 | `Dialect.switchesCatalogByReconnect()`（默认 false）+ `catalogsSql()`（默认 null）；MySQL 覆写为 `information_schema.SCHEMATA`（要 `SHOW DATABASES` 权限更大，前者只要看得见 information_schema） |
| 内省 | `SchemaService.listCatalogs`（第 1 列 = 库名，**过滤 `mysql`/`information_schema`/`performance_schema`/`sys`**，排序） |
| 会话 | `ConnectionProfile.catalogVariant(...)` + 五个 id 工具（§24.1） |
| 侧栏 | `catalogsByConnection` + `CatalogEntry(catalog, servingConnectionId)`：有 catalog 的连接节点下先出**库一层**，对象分组挂库下；没有的连接（SQLite/PG）**形态与以前完全一样**；当前库加粗 + 淡底；库节点永远保留（懒加载不断链） |
| 外壳 | `loadCatalogsFor`（选中未开连接时不标记「已读」，由 `loadObjectsFor` 开好会话后补触发——修掉竞态）· `switchCatalog` · `connectCatalog`（不落盘）· `refreshProfiles` 登记虚拟连接 · schema 切换器在 MySQL 上让位（两个入口管同一件事必然打架） |
| 表级 DDL | `TableDdl` 全部入口带 `connectionId`：会话 / 执行器 / 重名校验全按它取 —— **修掉「对非当前连接的节点做 DDL 会落到当前库」**（等于把 db2 的表建进 db1，用户还看不出来） |
| 刷新 | 侧栏「刷新对象」回调改为带连接 id —— 在非活跃库节点上右键刷新，刷的是**那个库** |

### 24.3 门禁

| 测试 | 例数 | 守的规则 |
|---|---|---|
| `CatalogTreeTest`（introspect） | 5 | 库列表 SQL 三方言（只有 MySQL 有）· 系统库过滤 · 排序 · 空方言不假装有 |
| `CatalogVariantProfileTest`（session） | 5 | variant id 约定（父 id 原样 / `@库名` / 反解 / SQLite 不假装） |
| `SideBarTreeTest`（ui-shell） | +5 | 库层在连接与对象之间 · 无 catalog 连接保持老形态 · **选中库节点懒加载那个库** · 切库动作带 Serve 连接 · 切库动作只在库节点放行 |
| `AppShellTest` | +1 | 演示库（SQLite）树里**不许出现**库节点 |
| `TableDdlTest` | 12 | 按连接分派后全部原规则仍成立（取消不执行 / 方言 SQL / 重名拒绝 / 逐表删） |

修掉的两个真 bug：① 没加载对象的库节点被 `removeIf(isLeaf)` 当空分组摘掉
（懒加载链断掉，用户永远点不到那个库）；② `treeOf()` 在 FX 线程内二次走
队列往返返回 null（测试侧，但也说明这个 helper 不能嵌套用）。

门禁：**662 条 / 86 类 / 0 失败**（`./mvnw -B clean verify` 全绿，第十四轮 U1 时）。

> 第十五轮 Q5+V-9 后门禁 **673 条**；同轮续做 G4 后 **674 条 / 87 类 / 0 失败**（见 §25）。

---

## 25. 第十五轮：Q5 结果分页 + V-9 表格规格统一（2026-10-22）

> 触发：Q5 与 V-9 排在一起是因为「两者都要动结果区」——
> 查询页以前把结果一次性塞进 `ObservableList`（硬上限 1000 行），
> 而数据页早就是服务端分页，两套网格规格还不一样。分页做出来，顺手统一规格。

### 25.1 Q5：查询结果服务端分页

| 层 | 改动 |
|---|---|
| SQL 改写 | 新增 `QueryPaginator`（introspect）：`isPaginable` 保守判定（单条 / select·with 开头 / 无 `for update`·`into`）· `pageSql` → `SELECT * FROM (<原句>) AS _p LIMIT ? OFFSET ?` · `countSql` → `SELECT COUNT(*) AS adb_count FROM (<原句>) AS _p`。**不分方言** —— 三种方言都支持派生表 + `LIMIT/OFFSET`，与 `DataSqlBuilder` 写法一致 |
| 末尾分号 | ⭐ `SqlSplitter` 保留语句末尾 `;`，判定与改写都**剥离单个末尾分号**；中间含分号仍判不可分页。**这是本轮踩到的真坑**：不剥离则 `isPaginable` 恒 false，所有查询都进不了分页路径 |
| 参数顺序 | `pageSql` 参数 = 原句占位符在前、limit / offset 在后（与驱动按出现顺序绑定一致）；offset 显式转 `int`，避免与 limit 的 `Integer` 装箱类型不一致 |
| UI | `QueryPage.PagedLoader`：首/上/下/末 + 页码 + 每页下拉 + 跳页 + 「N 行」范围；`#` 列改为**绝对行号**；翻页/跳页受 `QueryLimits.MAX_OFFSET`（500 000）约束；每个结果页签各自独立翻页，页签标题带总行数 |
| 保留原逻辑 | DML/DDL、`for update`、含 `into`、0 行结果仍走原「整段取回 + 截断提示」，不强行分页 |

### 25.2 V-9：表格规格统一（类型行 / 记录导航条 / 结果区）

- **列类型条**：JavaFX 21 的 `TableColumn` **不是 `Control`，没有 `setTooltip`**，
  列头挂不了 tooltip —— 改为表格下方一条 `adb-query__result-types` 类型行
  （`列名 类型` 用 `·` 连接），与数据页「类型行」同一套视觉。
- **翻页条复用数据页规格**：`adb-record-nav` + `adb-page-size`，
  不另起一套样式，避免查询页与数据页看起来不是同一款产品。
- **绝对行号**：翻到第 N 页时行号仍是全局序号，而不是每页从 1 重来。

### 25.3 门禁

| 测试 | 例数 | 守的规则 |
|---|---|---|
| `QueryPaginatorTest`（introspect） | 8 | select/with 可分页 · 非 select / `for update` / `into` / 多语句不可分页 · **末尾分号剥离后仍可分页且改写正确** · count/page 参数顺序 · limit·offset 夹取 |
| `QueryPageTest`（ui-shell） | +3 | 250 行 SELECT 出分页条且默认 200 → `第 1 / 2 页` + `250 行` · 点「下页」加载第 2 页 50 行且首行是 201 · 单行 SELECT 也走分页路径（1 页） |

修掉的三个问题：① `isPaginable` 不认末尾分号导致分页整条路径**永不生效**（真 bug）；
② `pageSql` 的 offset 装箱成 `Long` 与 limit 的 `Integer` 类型不一致（真 bug）；
③ 重复列断言硬编码 `n_2`，而 SQLite 驱动把第二列命名成 `n:1` ——
键名由驱动决定，改成断言「两列值都能取到」，不绑死具体键名。

顺带补齐两个 CSS 规则（`.adb-query__result-pager` / `.adb-query__result-types`），
被 `StyleClassCoverageTest` 抓出来 —— 这正是该门禁的价值：新写的 `adb-*` 类必须同时落地 CSS。

门禁：**675 条 / 87 类 / 0 失败**（`./mvnw -B clean verify` 全绿；G1 落地后 +1）。

### 25.5 同轮续二：G1 冻结列（U-B6 收官）

| 层 | 改动 |
|---|---|
| 结构 | 左表 `frozenGrid`（选择列 + 行号列 + 前 N 个数据列）+ 右表 `grid` 并排放进 `HBox`；**N=0 时左表 managed=false，布局与旧版完全等价**（这是「不冻结就零风险」的关键） |
| 列归属 | 移动的是**同一批 `TableColumn` 实例**，不是复制 —— 列宽 / 可见性 / cellFactory / userData 全部跟着走，不会「左表改了右表没改」。`applyFrozenColumns()` 先把两表的列合并再重分，**幂等**，反复冻结/解冻不丢列、不乱序 |
| 滚动同步 | 两表垂直滚动条双向绑定。滚动条在 skin 里，所以挂在 `skinProperty` 上等就绪；**headless 下拿不到就安静跳过**（拿不到 ≠ 坏了，只是这个环境渲染不了） |
| 选择同步 | 双向跟随 + `syncingSelection` 防循环标志（两表互相监听，不加会无限来回） |
| 排序 | **不需要同步** —— 项目是服务端排序（`setSortable(false)` + `DataQuery` 重查），两边列头触发的是同一次重查，结果一致 |
| 编辑 | 编辑后两表都 refresh；行高两表同设 `setFixedCellSize`（不一致的话同一行左右会错开半格）；`items` 翻页时两表一起换（否则左右指向不同页的行） |
| 入口 | 视图菜单「冻结左侧列」1/2/3 档 + 取消冻结。做成子菜单而不是循环按钮 —— 「现在冻了几列」要一眼看得到 |

门禁：`DataGridPageTest.frozenColumnsStayPut`（默认不冻结时左表隐藏 · 冻结后两表列数之和不变（不丢列）·
左表正是 id/name、右表不再有它们 · 两表 **共享同一 items 实例** · 行高一致 ·
选择双向同步 · 解冻后列数与原顺序完全复原）。

### 25.4 同轮续做：G4 单元格区域选择

| 层 | 改动 |
|---|---|
| 模型 | `GridRectangle`（两个对角，取 min/max 消方向）+ `GridCell` 锚点。列下标取「**可见数据列**」—— 行号列/勾选列没有 `userData` 天然排除；用可见列而非全部列，隐藏一列也不会让矩形错位 |
| 交互 | 在 `ValueCell` 上按下定锚点 → 拖动扩展 → 松开收尾；**Shift + 点击**从锚点扩展（Excel 选区语义）。每个 cell 自己知道 (行, 可见列下标)，不需要算像素坐标 |
| 高亮 | 矩形内 cell 加 `adb-grid__cell--rect`（accent 淡底 `-adb-bg-active` + accent 描边）。样式加在 `updateItem` **所有 return 分支之前** —— 布尔列会渲染成 CheckBox 并提前 return，晚加就漏掉那一格 |
| 复制 | ⌘C：**有矩形就复制这块区域为 TSV（不含表头）**，没有才复制当前单元格（旧行为不回归）。Esc 取消矩形；翻页/重载自动清除（否则行下标会指向新页的另一批行） |
| 关键取舍 | **没有打开 `cellSelectionEnabled`**。打开它 `getSelectedItems()` 会给同一行返回重复项，批量删除 / 设 NULL / 导出选中行会重复处理 —— 那是拿「行级批量操作」换「区域选择」，不划算。所以矩形是**独立于行选择的一套模型**，两者并存互不干扰 |

门禁：`DataGridPageTest.rectangleSelectionCopies`（框 3×2 → 断言复制出 3 行 × 2 列、
值与表格一致、**不含表头**、矩形外的列不出现、真的进了系统剪贴板；
Esc 能取消；无矩形时 ⌘C 仍是单单元格）。

---

## 26. 第十六轮（2026-10-23）：Docker 解锁 + PostgreSQL / MySQL 真库联调

> 上游条件：本机装好 Docker Desktop。`PostgresMySqlIntegrationTest` 由
> `DockerAvailability.isAvailable()` 把门，容器一到位就自动跑，**不需要改任何代码**。

| 项 | 结果 |
|---|---|
| 容器 | `postgres:16-alpine` + `mysql:8.4`（Testcontainers） |
| 契约 | `RealDatabaseContract` 共享断言，两方言都过同一套表元数据 / 结构变更 / 类型往返 |
| 用例 | 集成 **11 例全绿** |

**又抓到两个 SQLite 上永远看不到的跨方言 bug**（延续 MySQL 5.7 那轮的价值）：

| # | 方言 | 现象 | 根因 | 修法 |
|---|---|---|---|---|
| 1 | MySQL | `tableMetaSql()` 语法错误（`near 'rows, ...'`） | `rows` 是 **MySQL 8.0 保留字**（窗口帧 `ROWS BETWEEN …`），当别名必须转义 | `MySqlDialect` 里写成 `` table_rows AS `rows` ``（反引号只在 MySQL 合法，故只在该方言内加） |
| 2 | PostgreSQL | `column c.relcollation does not exist` | `pg_class` **没有** `relcollation` 列 —— 排序规则是**每列**落在 `pg_attribute`，不是每表 | 改取所在库的默认排序规则：`(SELECT datcollate FROM pg_database WHERE datname = current_database()) AS collation_name` |

> ⚠️ 两处都**保住了列名契约**（`rows` / `collation_name`）—— `SchemaService.listTableMeta()`
> 是按列名读的，改名会静默变成 null。

**aliCloud RDS 怎么接**：协议层与容器完全一致，**无需改代码**，用环境变量指向真实例即可 ——
`ADB_TEST_PG_HOST / _PORT / _DB / _USER / _PASSWORD`（`ExternalRealDatabaseTest`）。
拿到凭据后同一批契约直接跑在阿里云 RDS 上。

---

## 27. 第十六轮·续（2026-10-23）：U-B7 侧栏 Navicat 模型 + 按钮规格统一

### 27.1 侧栏改 Navicat 模型（不再是「工作台」）

用户对照 Navicat 后指出的核心差异：**Navicat 没有「工作台」，查询是跟着数据库走的**。
`SideBar` 已重构为单棵「我的连接」树：

- 去掉「工作台」分段、「收藏」独立区、顶部连接下拉（`adb-connection-picker` 已删）；
- 标题改「我的连接」+ 搜索 + 折叠，头部右侧是「新建查询」按钮；
- ⭐ **查询跟着连接走** —— `queryTargetConnectionId()`：落在当前选中的连接节点上，
  没选中具体连接时落到当前激活连接（而不是一个全局「工作台」）；
- 收藏筛选改用「★ 只看收藏」图标按钮（`adb-icon-button--active`），不再是独立区；
- 底部保留一条**平铺的工具行**（查询历史 / 诊断 / 导入导出，**不加分段标题**）——
  这三个都是真功能，去掉分段 ≠ 删掉入口（详见 §27.4）。

### 27.2 按钮 / 交互态规格统一（「很多不一样」的根因）

用户反馈 hover、字体、灰度不统一。核查后确认 **hover 底色早已统一走 `-adb-bg-hover`**、
文字色全部令牌化；真正不一致的是**三套按钮规格**：

| 类 | 修前 | 修后 |
|---|---|---|
| `.adb-button` | 32px / 6px / 13px / `-adb-border` | （作为基准，不变） |
| `.adb-dialog .button` | **无固定高** / **4px** / **12px** / `-adb-border-strong` | **32px / 6px / 13px / `-adb-border`** |
| `.adb-icon-button` | **28px** / 6px / 14px | **32px** / 6px / 14px |

- 弹窗按钮此前没有 `min/pref-height`，靠 JavaFX 默认高度，和页面按钮并排时**矮一截**、圆角也更方；
- 图标按钮 28px 与同工具栏文字按钮 32px **基线不齐**，视觉上就是「不一样」。
- 侧栏 / 状态栏早已共用 `-adb-bg-sidebar` 灰底，标签栏保持白色（贴合 Navicat：内容区白、侧栏灰），**无需改**。

### 27.3 顺带修掉门禁抓出的样式类漂移

`StyleClassCoverageTest` 抓到重构遗留的 4 处 Java ⇄ CSS 不一致（**这正是「样式类对拍」的价值**）：

| 类型 | 类 | 处理 |
|---|---|---|
| Java 用了、CSS 没有 | `adb-sidebar__header` | **补规则**（对齐 + 间距，防以后被改成 VBox 跑位） |
| CSS 定义了、Java 不用 | `adb-connection-picker` | **删规则**（旧连接下拉） |
| CSS 定义了、Java 不用 | `adb-favorite` | **删规则** + 从 `FocusRingAuditTest.FOCUSABLE` 摘掉 |
| CSS 定义了、Java 不用 | `adb-sidebar__connection` | **删规则** |

门禁：`StyleClassCoverageTest`(4) + `FocusRingAuditTest`(3) + `AppCssAuditTest`(7) 全绿。

### 27.4 门禁抓出的三处**功能回归**（重构改过头了）

重构把「工作台 / 工具」分段整个删掉时，**连入口一起删了**，被三个既有门禁当场抓住 ——
这正是「占位要么接要么删」与门禁的价值：

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | `WorkspaceToolsPageTest` 3 例 `EmptyNodeQuery` | 侧栏不再有「查询历史」「诊断」按钮，测试点不到 | 底部补回平铺工具行（`adb-sidebar__tools`），复用 `navButton` |
| 2 | 🔴 **查询历史变成不可达功能** | 全项目**没有任何地方**调 `openTool("查询历史")`，但 `WorkspaceView` 仍会渲染它 | 同上，补回入口；页面是真功能，不能只留渲染分支 |
| 3 | 🔴 折叠栏「工具」按钮**必崩** | 它 `openTool("诊断")`，而 `WorkspaceView` 解析诊断页会做 `title.substring("诊断 · ".length())` —— 标题只有「诊断」两字 → **越界异常** | 改走 `actions.run("object.diagnostics")`，由它按当前选中对象拼出「诊断 · `<表名>`」；没选中只给提示 |
| 4 | `IconSystemAuditTest` 报 `TRANSFER` 是死代码 | 删掉「导入 / 导出」按钮后没人用这个图标了 | 工具行补回「导入 / 导出」（菜单与工具栏也有入口，这里补齐让三个工具成一行） |

> 📌 **教训**：结构性重构（合并/删除分段）很容易**连带删掉入口**。
> 门禁里凡是「点侧栏按钮」的用例，都是在保护可达性 —— 这次三条一起报警，
> 说明「删分段」和「删功能」必须分开判断。

---

## 28. 第十七轮（2026-10-23）：规格 §6 右侧对象详情面板 + §13 新建表弹窗重构 + §9.7 底部导航标签

> 触发：用户给出 **23 部分 UI/UX 重构规格书**（对标 Navicat Premium / Premium Lite）。
> 本轮实施其中三个**可直接验收**的条目，全部接真实数据、零占位页面。

### 28.1 §6 右侧详情面板（`ObjectDetailsPanel`，新建）

- 新增 `ui/adb-ui-shell/.../shell/ObjectDetailsPanel.java`：**纯展示组件**，
  不依赖 `DbSession` / `SchemaService`，数据由外壳通过 `Loader` 回调喂入
  （公开类型：`Subject` / `Fact` / `Snapshot` / `Loader` / `Actions`；
  宽度默认 300，可拖 220–420）；
- `AppShell` 接线：`details.setLoader(this::loadObjectDetails)` +
  `sideBar.setOnInspect(this::inspectTarget)`；`SideBar` 新增
  `InspectTarget(connectionId, name, schema, topic)` 上报（ROOT/GROUP 容器节点不上报）；
- `ui.details.visible` 默认 **false**，`ui.details.width` 记忆宽度；
  动作 `details.toggle`（⌥⌘D）进「视图」菜单；
- 连接级快照：主机 / 端口 / 驱动 / 用户 / 版本；表级快照：
  字段摘要（名称 · 类型 · 主键 · 非空）+ 行数 / 索引数 / 触发器数等 section；
- 新门禁 `ObjectDetailsPanelTest`（14 例）+ `AppShellTest`（+3）。

### 28.2 §13 新建表弹窗重构（`NewTableDialog`）

| # | 改动 | 证据 |
|---|---|---|
| 1 | 宽度 640 → **900**（`DialogSizes` 新增第四档 `TABLE`），最小高 600，去掉膨胀头 | `DialogSizes` · `NewTableDialog` |
| 2 | 标题栏带**真实库名**（`session.label()`）+ 方言 + 关闭按钮 | `headerNamesTheRealDatabaseAndOffersClose` |
| 3 | SQL 预览从 `TextArea` 换成 **`SqlEditor`（只读 `CodeArea`：等宽 + 行号 + 高亮）**，可折叠 + 复制到剪贴板 | `sqlPreviewIsARealReadOnlyEditor` / `sqlPreviewCollapses` |
| 4 | 列网格高度按行数自适应（`34 + 行数×30`，夹在 [120, 300]）；列宽 200/160/52×3/140/120 | `columnGridShrinksWhenThereAreFewColumns` |
| 5 | 表名 / 表注释标签复用 `formLabel()` | `StyleClassCoverageTest` |

### 28.3 §9.7 数据页底部「记录 / 页面」常驻标签

数据页底部有两组图标完全相同的 ◀◀ ◀ ▶ ▶▶（翻行 vs 翻页），
工具提示只在悬停时出现，而这两组是连起来看的 —— **点错不报错，只是跳到了没想到的地方**。
解法：两组各配一个常驻标签（`buildRecordNav()` 加「记录」，`buildPager()` 加「页面」），
新门禁 `DataGridPageTest.recordAndPageNavigationAreDistinguishable` 钉住
（同时断言两组按钮都还在 —— 是「分辨」而不是「删掉一组」）。

### 28.4 门禁抓出的三个真 bug + 一处回归

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 详情面板**字段摘要永远是空的** | `facts` VBox 从未加入场景图（只加了 `sections`） | `new ScrollPane(new VBox(facts, sections))` |
| 2 | 选中表后详情面板内容始终为空 | `loadObjectDetails` 在数据库执行器线程直接渲染 UI，违反 JavaFX 单线程规则 | `Platform.runLater(() -> onLoaded.accept(snapshot))` |
| 3 | `postgresRenameColumnPreservesDefinition` 真库契约失败 | PG 的 `information_schema.data_type` 回报 `character varying`，断言写死 `varchar` | 加 `canonicalTypeName()` 归一（varchar / char / timestamp / int 同族） |
| 4 | `StyleClassCoverageTest` 报 `adb-form__label` 死样式 | 重构删掉了唯一使用处 | 表名 / 表注释标签复用 `formLabel()` |

### 28.5 本轮验收

```bash
./mvnw -B clean verify
java tools/TestCount.java
```

**实测：721 条 / 89 类 / 0 失败，29 条跳过**（2026-10-23，全模块绿）。
本 UI 轮新增 `ObjectDetailsPanelTest` 14 例、`NewTableDialogTest` +6、`AppShellTest` +3、
`DataGridPageTest` +1、`TableListPageTest` +2、`StructurePageTest` +1
（另 +5 条来自工作区里未提交的多版本矩阵 WIP，见 §28.7；另 +3 条为 §28.8 补的两项）。

### 28.6 规格书仍未做（如实记录，不假装完成）

> ⚠️ **编号勘误（第十八轮实测）**：下表原有的「§6.1 / §6.2 / §6.3 / §11.2 / §13.3」**在仓库里
> 没有对应的规格小节** —— grep 全部 `docs/*.md`，这几条只出现在本清单与 `docs/NEXT-STEPS.md`，
> 任何规格书里都搜不到同名条目（`docs/UI-VISUAL-SPEC.md` 的 §6 是「布局」、§13.3 是「本轮已顺手修掉的」，
> 都不是这里说的东西）。**所以这里的「§」号是悬空引用**：条目本身有效（内容写得清楚），
> 但别指望点进去。第十八轮起**按条目名记录，不再挂假章节号**。

| 条目（按名称，不挂假号） | 状态 | 备注 / 落点 |
|---|---|---|
| 表详情「最近执行的相关操作」 | ⬜ 未做 | 需要把查询历史与当前对象关联（`QueryHistory` 已有，缺「按对象筛」） |
| 连接详情文件路径展示 | ⬜ 未做 | 连接档案落盘路径回显（`~/.agenticdb/connections.json`） |
| 详情面板与 `TableListPage` 选中联动 | ✅ **已完成（§28.8）** | — |
| **列网格键盘导航（Tab / 方向键在格间移动）** | 🟡 **部分完成** | ✅ 布尔三态列已可键盘切换（§28.10 `BooleanCell`）；⬜ 剩「Tab / 方向键在**整张列网格**内移动 + 单元格级编辑焦点」，见 **§29.1 S-4** |
| 结构页 DDL 页复用 `SqlEditor` | ✅ **已完成（§28.8）** | — |

### 28.7 顺带发现：工作区里有一份未提交的「多版本真库矩阵」WIP

本轮开工时 `./mvnw -B clean verify` 是**红的**，根因不在 UI 改动，而在工作区里
一份**未 commit** 的 WIP（`DatabaseVersionMatrixTest` / `VersionMatrixGate` /
`Dialect` / `MySqlDialect` / `StructureSqlBuilder` / `PostgresMySqlIntegrationTest` /
`DialectSqlConformanceTest` / `RealDatabaseContract`）：

| 内容 | 说明 |
|---|---|
| 目标 | MySQL 5.7 / 8.0 / 8.4 + PostgreSQL 13 / 16 / 17 六版本矩阵，`ADB_TEST_VERSION_MATRIX=1` 才跑（默认门禁不额外起 4 个容器） |
| ⭐ 抓出的真 bug | **MySQL 8.0 之前默认字符集是 latin1** —— `createTableSql` 不带 `DEFAULT CHARSET=utf8mb4`，建出来的表**存不了中文**；已在 `StructureSqlBuilder` 钉死（`DialectSqlConformanceTest.createTablePinsTheCharsetOnMySql` 守着） |
| 我做的修复 | ① `RealDatabaseContract.postgresRenameColumnPreservesDefinition` 断言写死 `varchar`，而 PG 回报 `character varying` → 加 `canonicalTypeName()` 归一；② 矩阵门禁默认关闭（不拖慢默认门禁） |
| 现状 | WIP 全绿但**仍未 commit**；是否收档由用户决定（本清单不改动它） |

### 28.8 同轮补两项：§6.3 详情面板接表清单 + §11.2 DDL 页换编辑器

| 规格条目 | 改动 | 门禁 |
|---|---|---|
| **§6.3** | `TableListPage.Host` 新增 `inspectTable(String name)`（多选 / 空选传 `null`），在 `reportSelection()` 里按**值去重**后上报；`AppShell` 的 Host 实现把它接到 `inspectTarget(...)`，面板关着时直接跳过 | `TableListPageTest`(+2)：`selectingATableFeedsTheDetailsPanel` / `theSameSelectionIsNotReportedTwice` |
| **§11.2** | 结构页 DDL 页签从 `TextArea` 换成 **`SqlEditor`**（只读 `CodeArea`：等宽 + 行号 + 高亮），与查询页、新建表预览同一套编辑器；`SqlEditor` 新增 `setHint(...)`（只读视图不能沿用「⌘↵ 执行」那句提示）；`.adb-structure__ddl` 从 TextArea 规则改成容器规则 | `StructurePageTest`(+1)：`ddlTabIsAReadOnlyEditorWithLineNumbers`（并把原 `loadsColumnsIndexesAndDdl` 的 TextArea 断言改成 CodeArea） |

> 📌 **去重是必须的**：刷新、切视图、标签切回来都会重跑 `updateSelectionState()`；
> 不去重就会拿同一个表名反复触发一次元数据查询 + 一次面板重绘。

### 28.9 AIDB 品牌改造 + Logo 落地（同轮续三）

| # | 改动 | 证据 |
|---|---|---|
| 1 | **Logo 设计**：数据库三层圆柱 + AI 四角星芒。语义 = DB + AI，16px 下仍可辨 | `brand/aidb-mark.svg`（纯图标）· `brand/aidb-logo-horizontal.svg`（横版字标） |
| 2 | **PNG 生成器**：`tools/GenLogo.java`（Java2D，4× 超采样抗锯齿）。**与 SVG 几何一致**，改一个必须改两个；JDK 自带，无新依赖 | `java tools/GenLogo.java` → `app-icon-{16..1024}.png` |
| 3 | **多尺寸图标注册**：`App.java` 注册 1024/512/256/128/64 六张，系统按位置自动选最合适的一张（避免 Dock/标签页糊边） | `App.java` 图标加载循环 |
| 4 | **用户可见文案统一为 AIDB**：窗口标题、应用栏字标、欢迎页标题、`退出`/`关于` 菜单、Toast、崩溃日志、SQL 导出注释头、secret-tool label、jpackage `--name`/`--vendor`、14 个 pom `<name>` | `App.java` `APP_TITLE` · `AppBar` · `WorkspaceView` · `AppShell` · `CrashLog` · `TableExporter` · `SecretToolCredentialStore` · `scripts/package.sh` · 各 `pom.xml` |
| 5 | **品牌约束写进文档**：包名 `io.agenticdb` 与 groupId **不改**（会波及 200+ 文件、14 个模块坐标、`~/.agenticdb` 历史数据，收益为零风险极高） | `brand/README.md`「命名空间约束」 |
| 6 | **测试同步**：`AppBarTest.theBrandShowsTheProductName` 断言改为 `"AIDB"` | `AppBarTest`（10 例仍全绿） |

**本轮验收**：`./mvnw -B clean verify` → **721 条 / 89 类 / 0 失败**（品牌改造零回归）。

### 28.10 规格 §13.3：新建表布尔列键盘可达（同轮续四）

| 问题 | 改动 | 门禁 |
|---|---|---|
| **「主键 / 可空 / 唯一」三列用 `CheckBoxTableCell`，复选框 `focusTraversable=false` 且只响应鼠标点 —— 键盘根本勾不上**，违反本项目「一切都要键盘可达」硬规则（Navicat 的同类弹窗三态约束可全键盘操作） | `NewTableDialog` 新增包级 `BooleanCell`（`TableCell<ColumnRow,Boolean>`）：焦点留在**单元格**上（复选框只作图形、不吃焦点，读屏不读两遍）· 把可测逻辑抽成三个纯方法：**`isToggleKey(KeyCode)`**（只有空格/回车是切换键）、**`toggle(ColumnRow)`**（翻转属性 + `refresh()`，不依赖场景图）、**`handleKey(KeyCode)`**（只吃空格/回车，其余交还 TableView 导航）· `booleanColumn()` 改走 `column.setCellFactory(view -> new BooleanCell(property))` · 新样式 `.adb-table__bool-cell` / `.adb-table__bool-box`（焦点态用 `-adb-bg-active` / `-adb-border-focus` 令牌，无硬编码色） | `NewTableDialogTest`(+4)：`booleanCellToggleFlipsTheUnderlyingModelProperty`（切换必须写回模型，SQL 预览才跟着变）· `booleanCellOnlyConsumesSpaceAndEnterNotNavigationKeys`（空格/回车吃、方向键/Tab/字母键不吃）· `toggleKeyClassifierMatchesOnlySpaceAndEnter` · `booleanCellUsesTheCustomCellFactoryNotTheDefaultOne`（原有，保留） |

> 📌 **测试为什么不发 `fireEvent`**：headless 下 `fireEvent` 送不到没有真实焦点的节点，靠事件派发写测试会**假绿或假红**。所以把「哪些键该吃」与「切换要写回模型」抽成不依赖场景图的纯方法，门禁直接驱动同一段生产逻辑。
>
> 📌 **早期实现踩的坑（记录以免重犯）**：一开始试图在测试里手工把 `TableCell` 挂上 `TableRow`（`row.getChildrenUnmodifiable().add(cell)` + `updateTableColumn/updateItem`）—— `getChildrenUnmodifiable()` 是只读列表，`getTableRow()` 只由 VirtualFlow 填充，且 `getCellFactory().call(column)` 在 `TableColumn<ColumnRow,?>` 通配符下有 capture 转换编译错。正解是让单元格持有可直接注入的 `ColumnRow` 引用，把可测逻辑与场景图解耦。

**本轮验收**：`./mvnw -B clean verify` → **278 条 / 0 失败**（`adb-ui-shell` 模块，全 14 模块 SUCCESS；另 29 条跳过为外部真库矩阵）。

---

## 28.11 第十八轮：把 12 张截图当证据逐张核对（2026-10-23）

> ⭐ **本轮方法变了**：不再是「对着 Navicat 想还能做什么」，而是**把 `docs/screenshots/` 的 12 张实拍图
> 与 `docs/screenshots/navicat/` 的 4 张参考图逐张并排看**，只记录「图上能直接证伪的东西」。
> 结果：**6 个此前 721 条门禁全绿也漏掉的真缺陷** —— 它们全活在「代码能跑、断言能过、但用户看到的是错的」这条缝里，
> 且**五个是同一类病：失败路径被设计成静默降级**（找不到节点就跳过、缺资源就跳过、选不中就没值、清计时就写占位、没配就留空白）。

### 28.11.1 六个真缺陷（截图作证）

| # | 缺陷 | 图上证据 | 根因（代码级） | 修复 |
|---|---|---|---|---|
| **R18-1** | **`p5-connections.png` 拍的根本不是连接页** —— 文件名叫连接，图上是对象总览 | `docs/screenshots/p5-connections.png`（左上「对象 / 总览 / 共 6 个对象」） | `shots.sh` 点 `#sidebar-connections`，但该 id 在源码里**已不存在**：U-B7 重构 `a353dc1` 删掉了那颗按钮与 `onAction` 接线，却把 `SideBar.openConnections()` 留成了**零调用者死代码**；`ScreenshotSession.click()` 找不到节点只往 stderr 打一行「找不到节点…跳过点击」，然后**照常截图**，脚本退出码 0、字节数正常 | ① `shots.sh` 两行（`p5-dark` / `p5-connections`）改点真实存在的 `#appbar-manage-connections`；② 删掉 `SideBar.openConnections()` 死方法（连接管理按 Navicat 模型走树右键菜单 + 应用栏，符合「占位要么接要么删」） |
| **R18-2** | **「每页」下拉永远是空框** —— 查询页与数据页的分页条上，当前档位不显示 | `p3-query.png`、`p2-details.png`：`每页` 右侧是一个只有箭头、没有数字的白框 | `pageSizeBox.getSelectionModel().select(PageSize.nearest(pageSize).value())` —— `value()` 返回原生 `int`，Java 重载决议选中的是 **`select(int index)`（按下标选）** 而非 `select(Integer)`（按值选）。`javap -c` 实锤：`invokevirtual SingleSelectionModel.select:(I)V`。items 只有 `[100,200,500,1000]`（下标 0–3），`select(200)` 越界静默无效 → `getValue()==null` → 渲染空白。**既有 `DataGridPageTest` 第 471 行甚至写了 `if (!Integer.valueOf(100).equals(sizeBox.getValue()))` 去绕过它** —— 门禁在给 bug 打掩护 | `QueryPage` / `DataGridPage` 两处改 `select(Integer.valueOf(...))`，装箱把重载切回「按值选」 |
| **R18-3** | **只读 SQL 预览里教用户「⌘↵ 执行」** —— 新建表弹窗的预览区提示是编辑器的执行提示 | `p6-new-table.png`：「将要执行的 SQL」区第一行就是「输入 SQL，⌘↵ 执行当前语句，⇧⌘↵ 执行全部」 | 预览区是 `previewEditor = new SqlEditor()` + `setEditable(false)`，空表名时 `createTable` 抛 `IllegalArgumentException` → `setText("")` → `SqlEditor` 默认 hint 露出。§28.8 已经为只读视图加了 `SqlEditor.setHint(...)`，但**只在 `StructurePage` 用了一处**，`NewTableDialog` 漏了 —— 用户会去敲键盘、按 ⌘↵，然后什么都不会发生 | `previewEditor.setHint("填写表名后，这里会显示将要执行的建表语句")` |
| **R18-4** | **`App` 点名要的 `app-icon-512.png` 从来没被生成过** —— 注册 6 个图标，实际只加载 5 个 | 品牌改造（§28.9）后的资源目录只有 16/32/64/128/256/1024 + `app-icon.png`；`brand/README.md` 明写「`app-icon.png` = 512（主入口）」 | `App.java` 的图标列表同时写了 `app-icon-512.png` 与 `app-icon.png`，而 `tools/GenLogo.java` 对 512 这一档输出的正是 `app-icon.png`（`size == 512 ? "app-icon.png" : ...`）。`getResource` 返回 null 后被 `if (url != null)` **静默跳过** —— 少一张图零报错 | 从 `App.java` 列表删掉 `app-icon-512.png`（512 这档按约定就叫 `app-icon.png`），并在注释里写明这个坑 |
| **R18-5** | **状态栏悬空「— ms」** —— 没跑过任何语句的页面上，选中段旁边挂着一个破折号计时 | `p6-tables.png` / `p5-connections.png` / `p0-shell.png` 底部状态栏：「未选择 **— ms**」 | `StatusBar.setDuration(-1)` 的写法是 `millis < 0 ? "— ms" : ...`，而 `AppShell` 在**未连接 / 非查询页**三处调 `-1` 的意图都是「清掉计时」。文字占位代替了「不显示」，Navicat 在此只说「已选 1 个项目」 | `setDuration` 负数时**整段连同它前面的分隔符一起 `setVisible/setManaged(false)`**（分隔符必须同进退，否则剩一个孤零零的「·」）；构造时默认即隐藏 |
| **R18-6** | **查询页跳页框是个「三无」空白框** —— 没占位、没 tooltip、没读屏名 | `p3-query.png`：`每页` 后面跟一个纯白空框再接「跳转」按钮 | 同一个控件两页两套实现：`DataGridPage.#data-jump` 有 `setPromptText("跳至页")` + tooltip + `accessibleText` + 样式类 `adb-grid__jump`；`QueryPage.#query-result-jump` **只有 id + 宽度 + onAction** | 查询页补齐四样（prompt / tooltip / accessibleText / `adb-grid__jump`），宽度 56→64 与数据页一致 |

> 📌 **R18-2 最值得记**：一个「静默失败的 API 误用」在代码评审里极难被看见（`select(...)` 拼写完全合理），
> 单元测试还主动绕过它，**只有把界面拍下来放大看，才能发现框是空的**。
> 这正是本轮「截图当证据」方法的价值 —— 也说明**门禁必须覆盖「渲染结果」而不只是「模型状态」**。
>
> 🔁 **R18-2 的续集（同轮返工）**：修完 `select(Integer)` 后重拍，框**确实选中了 200 —— 但渲染成「2…」**。
> 逐像素量了截图：框宽 74px、文字在 x=909 就断了、**右侧还剩 23px 空白**才到箭头 —— 不是框太窄，
> 是 **ComboBox 的按钮单元格吃掉了宽度**。`prefWidth` 从 78 提到 **96**（够渲染最宽档位「1000」）才完整。
> 教训：**「选中了」不等于「显示得出来」** —— 模型断言（`getValue()==200`）抓不到省略号，
> 所以两个门禁各补了一条 `prefWidth >= 90` 的**渲染底线**（变异验证：改回 78 必红）。

### 28.11.2 本轮新增门禁（7 条，**全部做过变异验证**：改坏必红、改回必绿）

| 门禁 | 守什么 | 变异验证 |
|---|---|---|
| `DataGridPageTest.pageSizeSelectorShowsTheConfiguredValueInsteadOfAnEmptyBox` | 数据页「每页」必须显示 200 且下标落在 items 内 | 回退 `select(Integer.valueOf(...))` → 失败：**「每页」必须显示当前档位（DEFAULT_PAGE_SIZE=200），不能是空框** ✅ |
| `QueryPageTest.resultPagerShowsThePageSizeInsteadOfAnEmptyBox` | 查询结果分页条同上 | 同一变异 → 失败 ✅ |
| `NewTableDialogTest.readOnlySqlPreviewDoesNotTellTheUserToExecute` | 只读预览的 hint 不许含「执行当前语句 / 执行全部」，且 `previewArea().isEditable()==false` | 注释掉 `setHint(...)` → 失败：**预览是只读、不可执行的 —— 沿用「⌘↵ 执行当前语句」是误导** ✅ |
| `ScreenshotScriptAuditTest.everyClickTargetInShotScriptExistsInTheUiSource`（新文件） | 解析 `scripts/shots.sh` 每行 `名称\|点击id\|…`，逐个断言 id 在 UI 源码里出现过 `setId("...")` | 把 `appbar-manage-connections` 改成不存在的 id → 失败：**shots.sh 引用的节点在 UI 源码里不存在 —— 这些截图会拍到错误的页面** ✅ |
| `AppIconResourceAuditTest.everyReferencedIconLiteralResolvesToARealResourceFile`（新文件） | 主源码里每个 `app-icon*.png` 字面量都要在 `src/main/resources` 找得到 | 把 `app-icon-512.png` 加回列表 → 失败：**代码点名要的图标在资源目录里不存在 —— getResource 返回 null 后会被 `if (url != null)` 静默跳过** ✅ |
| `StatusBarTest.durationSegmentHidesEntirelyWhenThereIsNoTiming` | 无计时时耗时段不可见**且**中格分隔符归零；有计时能回来（隐藏不能变成永久消失） | 把 `setDuration` 退回「写占位文字但不隐藏」+ 删掉构造时的隐藏 → 失败：**还没跑过语句时，耗时段不该占位（表清单页实测显示「未选择 — ms」）** ✅ |
| `QueryPageTest.resultPagerJumpFieldCarriesTheSameAffordancesAsTheDataPage` | 跳页框必须有非空 prompt + accessibleText + `adb-grid__jump` 样式类（与数据页同规格） | 删掉 `setPromptText("跳至页")` → 失败：**跳页框必须有占位提示（数据页 #data-jump 是「跳至页」，两页要一致）** ✅ |

> 📌 **两条静态审计门禁（脚本点击目标 / 图标资源名）解决的是同一类问题**：
> `ScreenshotSession.click()` 与 `App` 的 `if (url != null)` 都把失败设计成「跳过并继续」，
> 这类**静默降级不会让任何运行时测试变红**。要堵住它，只能在**静态侧**断言「引用必须存在」。

**本轮验收**：`./mvnw -B clean verify` → 全 14 模块 SUCCESS、0 失败（见 §23 变更记录行）；**7 条新门禁逐一做过变异**（改坏必红、改回必绿）。

---

## 29. 第十九轮起：合并后的待办与建议顺序（2026-10-23 · 第十八轮末）

> 本节把散在 **§16.4 / §19.3 / §21 / §22.4 / §28.6 / §28.11 / `AIDB-UIUX-ANALYSIS.md` §10** 的剩余项
> **合并成一份带优先级、带证据、带建议门禁的清单**。历史小节原样保留（它们是当时的记录），
> **冲突时以本节为准**。每条都回答三件事：*证据在哪 / 做完拿什么验收 / 为什么是这个顺序*。

### 29.1 S 级 —— 第十九轮就该做（有截图证据 + 有明确落点）

| # | 项 | 证据 / 来源 | 难度 | 建议门禁 |
|---|---|---|---|---|
| **S-1** ✅ **DONE (R19 批次 A)** | **C1 方言感知列**：SQLite 下表清单 8 列里 **6 列恒为「—」= 首选宽度 810/1120px（72%）**；对象首页 9 列里 6 列同样 | `p6-tables.png` / `p5-connections.png`；`TableListPage:239-250` 无条件建全列（`ObjectHomePage:218` 还把这写成了既定策略） | 🟡 中 | ✅ **落地为「看数据不看方言」的规则**（不是方言白名单，故无需给页面塞 `Dialect`）：新共享纯助手 `MetadataColumns.hasAnyValue(rows, text)` —— 只有**每一行都渲染成占位符**时整列隐藏，有一行有真值就留着（混合场景：MySQL 表有引擎、视图没有 → 引擎列保留）；核心列「名称 / 行 / 类型」永不隐藏。判定必须基于**整批已加载 `rows`**（不是 `filtered`），否则搜索会让列凭空出现/消失。门禁：`MetadataColumnsTest`(6) + `TableListPageTest`+3 + `ObjectHomePageTest`+2，**全部做过变异验证** |
| **S-2** ✅ **DONE (R19 批次 A)** | **C2 卡片视图去破折号**：`notes / 0行 · — / — · — / —`，220px 卡片 2/3 是标点 | `p6-tables-grid.png` | 🟢 低 | ✅ 新 `cardLine(...)` 只拼**非占位符**片段；整行全占位符则**不渲染这一行**（不留空占位）。注意 `rowsText()` 为「—」时不能拼成「— 行」（那就不是占位符了）。门禁：`TableListPageTest.cardViewOmitsPlaceholderLinesOnSqliteLikeData`（变异验证）+ MySQL 形态仍显示真值的反向门禁 |
| **S-3** ✅ **DONE (R19 批次 B)** | **C5 对象树表节点可展开出列**：Navicat 每个表节点都有 `>`（裁图核对），AIDB 对象节点是叶子 | `SideBar.kindGroup()` 建的子 `TreeItem` 无 children | 🟠 中高 | ✅ **5 条新门禁，5 条全部做过变异验证**（MUTATION-1 去掉「没接回调就是叶子」→ `tableIsALeafUntilAColumnLoaderIsWired` 红；MUTATION-2 去掉「读过的不再读」闸 → `expandingATableReadsItsColumnsLazily` 红；MUTATION-3 失败后不摘「已尝试」标记 → `aFailedColumnReadIsSaidOutLoudAndCanBeRetried` 红；MUTATION-4 列的「复制名称」不放行 → `columnNodesCannotOpenDataButCanCopyTheirName` 红；MUTATION-5 重建树不认缓存 → `columnsSurviveATreeRebuild` 红，失败原文逐条记录）· 见 **§30** |
| **S-4** ✅ **DONE (R19 批次 D)** | **列网格键盘导航**（Tab / 方向键在整张网格内移动 + 单元格编辑焦点） | §28.6 遗留；硬规则「一切键盘可达」；§28.10 已把**布尔三态一格**做成可键盘切换 | 🟡 中 | ✅ `NewTableDialogTest` **+6**（`NewTableDialog` 抽出四个静态纯方法 `move` / `step` / `next` / `previous` + record `CellPos`，headless 下不靠 `fireEvent`）· **4 个变异全部验证**（D1 不挂处理器 / D2 最后一格绕回 / D3 `step` 抢 Tab / D4 出界夹坐标，失败原文逐条记录）· 见 **§32** |
| **S-5** ✅ **DONE (R19 批次 C)**（**但请看下面的 ⚠️**） | **C7 欢迎页用 AIDB mark**：现在还是通用 `Icons.Icon.DATABASE`；品牌改造只注册了 OS 窗口图标 | `WorkspaceView:137`；全仓无 `ImageView(app-icon-*)` | 🟢 低 | ✅ `WelcomeBrandTest`(3) 断言门面是品牌 PNG · **4 个变异全部验证通过**（含「坏 PNG」这一档）· ⚠️ **改完发现欢迎页当前不可达**（见 §31.2 / 新增 **S-7**）—— 所以这个改动**用户暂时看不到**，`p0-shell` 也拍不到它 |
| **S-6** ✅ **DONE (R20)** | **C3 详情面板挤压网格**：列头截成 `na… em… acti… sco…` | `p2-details.png`；`ObjectDetailsPanel.WIDTH_DEFAULT=300` | 🟡 中 | ✅ **落地为「可读下限 + 约束策略」**（推挤式保留，覆盖式不做）：三张表统一 `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`（测过：宽屏填满、挤窄卡在 `minWidth` 并出横向滚动条）；新增纯助手 `ColumnWidths`（西文 7.2px/字符、CJK 算 2 个单位、列头装饰占位 60px，夹在 96–260px）。**列头装饰也算进下限**（`[PK]` / `·` / `· 自增`），否则又回到 72px 老问题。门禁 `DataGridPageTest` / `TableListPageTest` / `ObjectHomePageTest` 各 +1，断言「策略是 CONSTRAINED_FLEX_LAST + 每列 minWidth ≥ 96 + 总下限 ≥ 800」；`ColumnResizePolicyTest` +2 守策略后果（宽屏填满 / 窄屏卡住下限）· 见 **§33** |
| **S-8** ✅ **DONE (R20)** | **Windows 上门禁假绿**：5 个静态审计用 `/` 过滤路径，Windows 的 `Path.toString()` 是 `\` → `contains("src/main/java")` 全不命中 | 本轮首次在 Windows 跑门禁时发现：`DialectBranchAuditTest` **把全部合法文件都报成违规**（红），另外 4 个审计**一个文件都没扫却显示绿**（更危险） | 🔴 高 | ✅ 5 处统一 `.replace('\\','/')`；并给每份审计补「**扫到 0 个文件必须红**」的下限断言（`scanned > N` / `hasSizeGreaterThan(N)`）。以后路径过滤再失效会当场炸，不会再静默通过 · 见 **§33.2** |
| **S-9** ✅ **DONE (R20)** | **对象首页缺对象级工具条**：`p0-shell.png` 上只有右上角 `+` | Navicat 的对象清单顶部固定「打开 / 设计 / 新建 / 删除 / 刷新」 | 🟠 中 | ✅ `ObjectHomePage.Host`（建表 / 设计 / 删表 / 同步详情面板）+ 图标工具条；启用态按选择类型：打开=单选任意、设计=单选表/视图、删除=多选且**全是表**、未连接全灰。门禁 `ObjectHomeToolbarTest`(4)：按钮在场景图里 / 选择驱动启停 / 点下去真的到宿主 / 详情面板跟随选择 · 见 **§33.3** |
| **D-1** ✅ **DONE (R20)** | **SQLite 主键显示成「可空 YES」**：`p4-structure.png` 的 `id INTEGER` | 主键永远非空 | 🟡 中 | ✅ `INTEGER PRIMARY KEY` 是 rowid 别名 → 非空；**TEXT PRIMARY KEY 仍如实显示可空**（SQLite 真的允许 NULL，不能一并改）。门禁 `StructureServiceTest.integerPrimaryKeyIsReportedAsNotNullable` 两向断言 · 见 **§33.4** |
| **Q-1** ✅ **DONE (R20)** | **查询页快捷键门禁在 Windows 上必红**：测试把 `meta=true` 写死成快捷键 | — | 🟢 低 | ✅ 测试改用平台快捷键（mac ⌘ / 其它 Ctrl）；生产代码 `isShortcutDown()` 本来就对 · 见 **§33.5** |

### 29.2 P2 —— 排期内（来源清楚、收益中等）

| # | 项 | 来源 | 难度 / 前置 |
|---|---|---|---|
| **N6** | 列宽记忆 / 长注释 Tooltip / MySQL 数据长度 `B·KB·MB·GB` 联调 | §22.4 | 🟡 **需要真库**（不与 N1–N5 混做） |
| **V-12 剩余** | 错误**详情弹窗** + 批量确认**汇总页**（错误**归类**已做） | §19.3 / §21.3 | 🟠 单独一轮 |
| **表详情「最近执行的相关操作」** | 查询历史按当前对象筛（`QueryHistory` 已有，缺关联） | §28.6 | 🟡 |
| **连接档案路径回显** ✅ **DONE (R20)** | `~/.agenticdb/connections.json` 路径展示 | §28.6 | 🟢 低 | ✅ 接在**连接级详情面板**的 facts 里（显示 `ConnectionStore.file()` 实际路径，不是硬编码 `~/.agenticdb` —— 测试与自定义启动会覆盖它） |
| **C4 密度第四档** | 实测 AIDB 网格 **28px** vs Navicat **19–22px**；现有最紧档 24px 仍松 | `AIDB-UIUX-ANALYSIS.md` §10.1 | ✅ **已裁定：做**（2026-10-24）—— 新增 `dense 22`，默认改 `compact 24`，见 §34 / UX-2 |
| **V-5** 工具栏按宽度折叠 / **V-11** 弹窗宽度三档 | §19.3 | ⏸ **暂缓有理由**：1280 宽实测 12个图标放得下 |
| **S-7** ✅ **DONE (R22 · 裁定＝接 + 删)** | **欢迎页不可达**（2026-10-23 由要做 S-5 顺带挖出）：`AppShell` 构造器无条件 `openPermanent(object-home)` ⇒ `WorkspaceView.showWelcome()` 永远被盖掉 ⇒ 30 行欢迎页是**死代码**，S-5 的品牌改动用户看不到 | §31.2 / §34.2 | ✅ **裁定＝接 + 删**（2026-10-24）：新增矢量 `Icons.Icon.BRAND`（圆柱 + AI 星芒）接进应用栏 26px 方块，`showWelcome()` / `brandMark()` / `.adb-welcome*` / `Icons.SIZE_BRAND` / `fs-2xl` 五处死代码连带删净 —— 落地记录见 **§34.2 S-7** |

### 29.3 P3 —— 结构性收尾

| # | 项 | 来源 | 说明 |
|---|---|---|---|
| **A20** ✅ **DONE (R20)** | `QueryLimits.MAX_CELL_BYTES` 声明后**零引用** → 删掉 | §21.2（第十八轮复核仍成立：全仓仅 `QueryLimits.java:78` 一处） | ✅ 已删（`grep -r MAX_CELL_BYTES` 全仓 0 命中） |
| **V-6 应用图标** | 实际**已大半完成**：`stage.getIcons()` 6 档 PNG 已注册（§28.9），R18-4 又修了死引用；**剩「界面内使用」= S-5** | §19.3 | 🟢 |
| **X4 收口** | 不再为半孤儿动作单独造菜单；口径改成「**⌘K 能搜到即可达**」（`ActionRegistryConvergenceTest` 已守右键走注册表） | §21.1（第十八轮勘误：X3 早已完成，别再列它） | 🟢 |
| **导入 / 导出向导** | Navicat 的强项之一，AIDB 只有入口没有分步引导 | `AIDB-UIUX-ANALYSIS.md` §9 | 🟠 大，值得单独一轮 |
| **P7 剩余** | 自动更新；jlink 仍不可行（自动模块） | §21.4 | 🟠 |

### 29.4 明确**不建议**现在做的（防过度设计）

- **工具栏按宽度折叠（V-5）** —— 1280 宽实测放得下，先做响应式是倒着来。
- **为每个半孤儿动作造菜单项** —— ⌘K 已经是「全动作可达」的口径，再造一遍菜单是双份维护。
- **jlink** —— 自动模块阻塞，§21.4 已记录，别再排期。
- **改包名 / groupId `io.agenticdb`** —— 会动 200+ 文件并打断 `~/.agenticdb`，`brand/README.md` 已裁定**永久不做**。

### 29.5 建议的下一轮批次（每批都自带验收）

| 批 | 内容 | 验收 |
|---|---|---|
| **A（先做，同一根因）** ✅ **DONE (R19)** | S-1 + S-2：清单类页面把「没有数据」渲染成标点的问题一次清掉 | ✅ 新门禁 6+3+2 条（`MetadataColumnsTest` / `TableListPageTest` / `ObjectHomePageTest`）**各做过变异**（改坏必红：MUTATION-1 强制 `hasAnyValue` 恒真 → 6 门禁红；MUTATION-2 让 `cardLine` 全渲染 → 卡片门禁红；MUTATION-3 空转 `ObjectHomePage.applyColumnVisibility` → 该页门禁红，失败原文逐条记录）+ **重拍 `p6-tables` / `p6-tables-grid` 并逐张核对** |
| **B（交互补强）** ✅ **DONE (R19)** | S-3 树展开出列 | ✅ `SideBarTreeTest` **+5**（`tableIsALeafUntilAColumnLoaderIsWired` / `expandingATableReadsItsColumnsLazily` / `columnsSurviveATreeRebuild` / `columnNodesCannotOpenDataButCanCopyTheirName` / `aFailedColumnReadIsSaidOutLoudAndCanBeRetried`）+ 展开不触发多余查询的断言 · **5 条全部变异验证通过**（改坏必红、改回必绿）· 顺带修掉 `AppShellTest.openTable` 的**选错节点**隐患（`contains("users")` 会同时命中 `v_active_users` / `trg_users_audit`，命中触发器时压根不开页面，失败信息却像分页 bug）与**读旧标签标题**的偶发红（改为等标题真的变成目标名字） |
| **C（品牌闭环）** ✅ **DONE (R19)** | S-5 欢迎页 mark | ✅ `WelcomeBrandTest`(3) + 4 变异验证（C1 换回通用图标 / C2 关等比 / C3 指非图片 / C4 坏 PNG，失败原文逐条记录）· ⚠️ 截图只能证到 S-3（`p0-shell` 六节点带 `▶`）—— **欢迎页不可达，见 §31.2 与新增的 S-7** |
| **D（a11y 硬规则）** ✅ **DONE (R19)** | S-4 列网格键盘导航 | ✅ `NewTableDialogTest` **+6**，沿用 §28.10 的「纯方法抽取」做法（不靠 `fireEvent`）· **4 个变异全部验证**（失败原文逐条记录）·额外补了一条 `gridNavigationIsActuallyWiredToTheColumnsTable`（纯方法全绿但忘了 `setOnKeyPressed` 是最容易溜过去的假绿） |
| **E（需先裁定）** ✅ **DONE (R20)** | S-6 详情面板布局 | ✅ 裁定为「**可读下限 + 约束策略**」（推挤式保留、覆盖式不做）：三张表统一 `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` + `ColumnWidths` 可读下限 · 3 条页面门禁 + `ColumnResizePolicyTest`(2) + **M2 / M5 变异验证**（下限改 60 → 3 条红；策略换回 UNCONSTRAINED → 2 条红）· 连带修正拖拽用例（Q-2：改拖前两列，末列在窄视口外是预期行为）· 见 §33 |
| **F（R20 实机复跑）** ✅ **DONE** | Windows 上门禁假绿（S-8）+ 对象工具条（S-9）+ 主键可空性（D-1）+ 快捷键门禁（Q-1） | ✅ 5 处路径归一化 + 「扫到 0 个文件必须红」下限 · `ObjectHomeToolbarTest`(4) · `StructureServiceTest` 两向断言 · `QueryPageTest` 平台感知快捷键 · **M1/M3/M4 变异各自验证** · 见 §33 |

**统一验收口径**：`./mvnw -B clean verify` 0 失败 · **新增门禁必须做过变异验证**（改坏必红、改回必绿）· 截图重拍后**逐张与 Navicat 参考图并排核对**（第十八轮的方法，不是只看文件生成了）。

---

## 30. 第十九轮批次 B：S-3 对象树表节点可展开出列（2026-10-23）

> 来源：§29.1 S-3 / §29.5 批次 B。Navicat 实拍里每个表节点都有 `>`，点开是列；
> AIDB 的表节点是叶子，想看一列有什么只能开结构页。

### 30.1 落地

| # | 改动 | 落点 |
|---|---|---|
| 1 | 新增 `NodeKind.COLUMN` + `SideBar.objectNode(...)`：**接了读列回调**的表 / 视图节点挂一个「读取列…」占位子节点（JavaFX 只在**有子节点**时才画展开箭头，所以这是箭头的由头），**展开时**才真正读列 | `SideBar.objectNode` |
| 2 | **懒加载**：`loadColumnsIfNeeded` 只在 `expandedProperty()` 变 true 时触发；`columnsByTable` 缓存 + `attemptedColumns` 去重 → 连上几千张表的库不会一上来就查每张表的列 | `SideBar.loadColumnsIfNeeded` |
| 3 | `AppShell.loadColumnsFor(...)`：读列跑在**数据库执行器**上（`schemaService.describe`，表与视图共用），结果 `Platform.runLater` 回填 | `AppShell` + `SideBar.setOnLoadColumns` |
| 4 | **不重建整棵树**：`setColumns` 用 `findTableNode` 就地换掉占位节点 —— 重建会把用户刚展开的节点合上、丢掉选中态 | `SideBar.setColumns` |
| 5 | **对象重新内省后作废列缓存**（`dropColumnsOf`）：表刚被 ALTER 过（加列 / 删列），留着旧列比没有列更糟 | `SideBar.setObjects` |
| 6 | **失败要说出来**：读到空清单只可能是读失败（表不可能没有列）→ 换成「列读取失败（展开重试）」占位节点并**摘掉「已尝试」标记**，再展开一次真去重试；不静默降级成叶子 | `SideBar.setColumns` |
| 7 | 列不是对象：右键菜单只有「复制名称」；`object.open-data` / `open-structure` / `copy-select` / `favorite` 在列上一律灰掉；不进「有多少个对象」的计数（否则 50 列的表会让搜索空态判断失真） | `SideBar.updateObjectActionStates` / `isObjectNode` |
| 8 | 样式 `.adb-tree__icon--column` 用**正文次色**（已有令牌）—— 列是「细节叶子」不是对象类型，不给新的语义色 | `app.css` |

### 30.2 顺带修掉的两个测试隐患（`AppShellTest.openTable`）

| 隐患 | 症状 | 修法 |
|---|---|---|
| **选错节点** | `contains(name)` 匹配树节点 → `users` 同时命中 `v_active_users` 和 `trg_users_audit`；取第一个时若命中**触发器**，压根不会打开页面，失败信息却是「标题还是 orders」，看上去像分页 bug | 按 `name=xxx,` **整字段**精确匹配 + 只认 `kind=TABLE` / `kind=VIEW`（`DemoNode` 是侧栏私有记录，测试取不到 `name()`，只能从 `toString()` 认） |
| **读到旧标签标题** | 双击后立刻断言标题，偶发读到**上一个标签**（新标签还没渲染完）→ 全模块跑时红、单跑绿 | 等 8 秒直到标题真的变成目标名字；超时抛 `AssertionError` 并说清「双击了谁、没打开谁」 |

### 30.3 门禁与变异验证

`SideBarTreeTest` **9 → 14**（+5）。**5 条全部做过变异验证**（改坏必红、改回必绿，失败原文逐条记录）：

| 变异 | 预期红的用例 | 实测失败原文 |
|---|---|---|
| MUTATION-1：去掉「没接回调就是叶子」 | `tableIsALeafUntilAColumnLoaderIsWired` | `:300 [没接回调 —— 表节点是叶子，不摆点开没反应的箭头]` |
| MUTATION-2：去掉「读过的不再读」闸 | `expandingATableReadsItsColumnsLazily` | `:349 [列已经在手 —— 再展开一次不该重复查库]` |
| MUTATION-3：失败后不摘「已尝试」标记 | `aFailedColumnReadIsSaidOutLoudAndCanBeRetried` | `:462 [失败不缓存 —— 再展开一次真去重试]` |
| MUTATION-4：列的「复制名称」不放行 | `columnNodesCannotOpenDataButCanCopyTheirName` | `:423 [复制列名是真需求 —— 这一项要放行]` |
| MUTATION-5：重建树不认已缓存的列 | `columnsSurviveATreeRebuild` | `:385 [重建之后列还在（不用再查一次）]` |

门禁：**736 条 / 92 类 / 0 失败**（`adb-ui-shell` 299→**304** +5；44 跳过全为环境门控 ——
本机 **Docker 不可用**，Testcontainers 的 PG16 / MySQL8.4 真库契约走的是跳过分支，不计入 736）。

> ⚠️ **环境提示**：本机默认 `java` 是 **JDK 11**，`/usr/libexec/java_home -V` 里也只有 11。
> 能跑起来的是 **IntelliJ 自带的 JBR 25.0.4**：
> `export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"`。
> `scripts/_env.sh` 会用它做预检，但不会帮你找 —— JDK 25 没装的话门禁直接起不来。

---

## 31. 第十九轮批次 C：S-5 品牌闭环（2026-10-23）

> 来源：§29.1 S-5 / §29.5 批次 C。原文是「欢迎页用 AIDB mark」——
> **做完了才发现那页你现在根本看不到**，详见 §31.2。发现本身就是本轮最大的收获。

### 31.1 落地

| # | 改动 | 落点 |
|---|---|---|
| 1 | 欢迎页 logo 从通用矢量圆柱 `Icons.Icon.DATABASE` 换成**品牌 PNG**（`app-icon-128.png`，40px） | `WorkspaceView.brandMark()` |
| 2 | **缺图不静默降级**：找不到资源就抛 `IllegalStateException` 说清缺的是哪个文件 —— 第十八轮数过五个「失败被设计成静默降级」的坑，这里是同一个坑的又一个入口 | `WorkspaceView.brandMark()` |
| 3 | `app.css` 清掉对图像没意义的 `-fx-text-fill` / `-fx-font-size` 并写明原因 | `.adb-welcome__logo` |
| 4 | ⚠️ **连带**：`fs-4xl`（40px）唯一的消费者就是上面那条 `-fx-font-size` → 令牌变摆设 → `TokenParityTest.everyTokenIsActuallyUsed` 红。按项目规则**删令牌**，不是把无用样式塞回去：tokens.json 97→**96**（59 色 / 34 尺寸 / 3 时长），`GenTokens` 重生成，`AppCssAuditTest.FONT_SIZES` 同步删档（9 档 → **8 档**），四份规格文档同步 | `tokens.json` / `Tokens.java` / `AppCssAuditTest` / `UI-VISUAL-SPEC` / `UI-ROADMAP` / `UI-GUIDE` / `AIDB-UIUX-ANALYSIS` |

### 31.2 ⭐ 本轮真正的发现：欢迎页是一条**不可达**的路径

改完欢迎页想去截图验收，才发现 `p0-shell.png` 拍的一直是「对象」首页 —— 欢迎页从来没露过面。
查下去是这样：

| 事实 | 证据 |
|---|---|
| `AppShell` 构造器**无条件**执行 `tabBar.openPermanent(object-home)`（X7「恢复后落回对象首页」） | `AppShell:246` |
| `WorkspaceView` 构造器里那次 `showWelcome()` 随即被对象首页盖掉 | `WorkspaceView:108` |
| 唯一的另一个调用点是「最后一个标签关闭时」，且有 permanent 标签时**优先回 permanent** | `AppShell:2073-2080` |
| ⇒ permanent 标签永远存在 ⇒ **`showWelcome()` 永远不会被用户看到** | — |

所以：**「欢迎页用 AIDB mark」这件事用户看不到**。按 §12.1「要么接要么删」的原则，这是欠账，
但本轮不越权替产品做决定 —— 记成 **S-7（待裁定）**，三个选项见 §29.2 末尾：

| 选项 | 代价 | 备注 |
|---|---|---|
| **A 接：给品牌找一个用户真看得见的落点** —— 比如应用栏那个 26px 圆角方块里的 `Icons.Icon.DATABASE` 换成 `Icons.Icon.BRAND`（圆柱 + AI 星芒，**矢量**，随主题换色） | 🟡 需要新增 `Icons.Icon.BRAND` 的路径数据 | ⚠️ 不能直接用 PNG 顶替：`AppBarTest.theBrandMarkUsesTheVectorIconSystem` 明确要求**矢量、不用位图**（这是有意的既有决定，不是随手写的门禁） |
| **B 删：删掉 `showWelcome()`** | 🟢 顺带消掉 30 行死代码 | 对象首页已经是启动落点，欢迎页没地方退回来 |
| **C 留：先原样放着** | 🟢 零改动 | 保持现状，等哪天真要「无连接时的引导页」再说 |

> 📌 本轮**不做**应用栏替换：那会推翻一个带门禁的既有设计决定，属于产品判断，先问再做
> （§29.4 的一贯做法）。
>
> ✅ **2026-10-24 已裁定并落地：接 + 删**（用户选的，见 §34.1 与 §34.2 的 S-7 落地记录）——
> 应用栏 26px 方块换成矢量 `Icons.Icon.BRAND`，不可达的欢迎页连同它的样式类、
> `Icons.SIZE_BRAND`、`fs-2xl` 令牌一起删。上面 A/B 两个选项**同时执行**，不是二选一。

### 31.3 门禁与变异验证

`WelcomeBrandTest`（新建，**3 例**）：①欢迎页那张图是品牌 PNG（`ImageView` + `app-icon-*`）而不是
`SVGPath` 通用图标 ②资源在 classpath 上 ③按 `Icons.SIZE_BRAND` 定成正方且等比。

| 变异 | 红的用例 | 实测失败原文 |
|---|---|---|
| C1：换回通用圆柱 `Icons.Icon.DATABASE` | `welcomePageShowsTheBrandMarkNotAGenericDatabaseIcon` | `:51 [门面要用品牌 PNG —— 通用矢量圆柱（SVGPath）看一眼认不出这是 AIDB]` |
| C2：`setPreserveRatio(false)` | `theBrandMarkIsSquareAtTheBrandSize` | `:86 [品牌 mark 是方的 —— 关掉等比会被拉变形]` |
| C3：指着非图片资源 | `welcomePageShowsTheBrandMarkNotAGenericDatabaseIcon` | `:60 [用的是品牌 PNG，不是随便一张图]` |
| C4：指着一个**坏掉的** PNG | `welcomePageShowsTheBrandMarkNotAGenericDatabaseIcon` | `:64 [品牌 PNG 解码失败（文件在，但是坏的）—— 界面上只是一块空白]` |

> C4 是特意补的：只断言「URL 对」「资源存在」抓不到「文件在但是坏的」——
> JavaFX 那时不报错，界面上只是一块空白，和第十八轮那五个坑同一个形状。

**截图**：`p0-shell.png` 已重拍，但**拍不到**改动（§31.2）。图确实变了 ——
变的是 S-3 的树：六对象节点（notes/orders/products/users/v_active_users/trg_users_audit）
现在每个左边都有 `▶`，与 Navicat 一致；这条是这次唯一能用图证实的改动。

---

## 32. 第十九轮批次 D：S-4 列网格键盘导航（2026-10-23）

> 来源：§29.1 S-4 / §29.5 批次 D。硬规则「一切键盘可达」。

### 32.1 之前是什么样

| 动作 | 之前 | 之后 |
|---|---|---|
| ↑ ↓ | 只换行，**不选列**（默认 row selection） | 一行一列地走（`getFocusModel().focus(row, column)`） |
| ← → | 只会被 TableView 吃掉用来滚列/换行 | 左右走列 |
| Tab | **直接跳出整张表格** —— 想把一列的 7 个属性挨个填完得来回点鼠标 | 按阅读顺序走：右一格，行尾换行首；落到新格**直接进编辑态** |
| 最后一格 | — | **把 Tab 让出去**，焦点按系统默认离开网格 |

7 列里有 4 列要手填（字段名 / 类型 / 默认值 / 注释），没有这套导航等于强制用鼠标。

### 32.2 落地与「为什么是纯方法」

`NewTableDialog` 里四个**静态纯方法**（给坐标、返回坐标，不碰 JavaFX）：
`move(from, dRow, dColumn, rowCount, columnCount)` / `step(from, KeyCode, …)` /
`next(...)` / `previous(...)`，加上 record `CellPos(row, column)`。

沿用 §28.10 的教训：headless 门禁里 `fireEvent` 送不到没有真实焦点的单元格 ——
靠事件派发写的用例会假绿或假红。所以**情景判断全在纯方法里**，测试直接驱动它们；
另外补了一条 `gridNavigationIsActuallyWiredToTheColumnsTable` 守「确实挂上了处理器」
（纯方法全绿但忘了 `setOnKeyPressed` 是最容易溜过去的假绿）。

两个刻意的选择：

1. **出界返回 `null` 而不是夹住坐标** —— 走到头就该停在原处，
   「按 ↓ 从最后一行绕回第一行」会让用户在网格里迷路。
2. **最后一格不吃 Tab** —— 能进去却出不来是「键盘可达」的反面，
   所以 `next()` 在最后一行最后一格返回 `null`，让系统默认的焦点遍历接手。

`step()` 也只认四个方向键：空格 / 回车留给 §28.10 的 `BooleanCell` 切换，Tab 走 `next/previous`。

### 32.3 门禁与变异验证

`NewTableDialogTest` **17 → 23**（+6）：`arrowKeysMoveOneCellAtATimeInBothDirections` ·
`movingStopsAtTheEdgesInsteadOfWrappingAround` · `onlyArrowKeysDoNavigation` ·
`tabWalksTheWholeGridInReadingOrder` · `theLastCellLetsTabOutOfTheGrid` ·
`gridNavigationIsActuallyWiredToTheColumnsTable`。

| 变异 | 红的用例 | 实测失败原文 |
|---|---|---|
| D1：注释掉 `installGridNavigation(table)` | `gridNavigationIsActuallyWiredToTheColumnsTable` | `:338 [字段网格必须挂着按键处理器，否则前面几条门禁测的是没人调用的逻辑]` |
| D2：最后一格绕回第一格 | `theLastCellLetsTabOutOfTheGrid` + `tabWalksTheWholeGridInReadingOrder` | `:321 [最后一行最后一格再按 Tab 就没有下一格了]` · `:301 [行尾换行首]` |
| D3：`step` 里多接一个 `TAB` | `onlyArrowKeysDoNavigation` | `:291 [Tab 走的是 next/previous，不是 step]` |
| D4：出界时夹住坐标 | `movingStopsAtTheEdgesInsteadOfWrappingAround` + 上面两条 | `:269`（边界该返回 null） |

---

## 33. 第二十轮（2026-10-24）：Windows 实机复跑 —— 5 个真问题

> 来源：§29 合并待办。前十九轮的门禁都在 macOS 上跑；这一轮第一次在 **Windows 10 + JDK 27**
> 上跑完整 `clean verify`，并对照 `docs/screenshots/` 的实拍与 Navicat 逐张核。
> 结果：**1 类「门禁假绿」（S-8，最危险）+ 3 个用户看得见的差异（S-6 / S-9 / D-1）+ 2 条测试自身的平台假设（Q-1 / Q-2）**。

### 33.1 这一轮怎么发现的

| 入口 | 发现 |
|---|---|
| 第一次跑 Windows 门禁 | `DialectBranchAuditTest` **把所有合法文件都报成违规**；另外 4 个静态审计**一个文件都没扫到却显示绿** |
| `p2-details.png` | 详情面板 300px 一开，列头被压成 `na… em… acti… sco…`（S-6） |
| `p0-shell.png` | 对象清单只有右上角一个 `+`，没有 Navicat 那种对象级工具条（S-9） |
| `p4-structure.png` | SQLite 的 `id INTEGER PRIMARY KEY` 显示「可空 YES」（D-1） |
| 全量跑 | `QueryPageTest` 的快捷键用例在 Windows 必红（Q-1）；S-6 改完后 `GridInteractionTest` 拖拽用例红（Q-2） |

### 33.2 S-8：Windows 上门禁「假绿」（最严重的一类）

Java 的 `Path.toString()` 在 Windows 用 `\`，而 5 个静态审计的白名单 / 排除规则都写的是 `/`：

| 审计 | 失效表现 |
|---|---|
| `DialectBranchAuditTest` | 白名单一个都命中不了 + `/target/` 过滤不掉 → **把所有合法文件都报成违规**（红得离谱） |
| `TokenParityTest` | `contains("src/main/java")` 全不命中 → **0 个文件，绿** |
| `AppIconResourceAuditTest` | 同上 |
| `ScreenshotScriptAuditTest` | 同上（这条正是第十八轮「拍错页」的守门人） |
| `IconSystemAuditTest` | 同上（图标字符 / 纯图标按钮 / 图标未使用三条全部空转） |

修法两层：

1. **路径归一化**：`root.relativize(path).toString().replace('\\','/')`，且 `relative()` 输出也归一化；
2. **「扫到 0 个文件必须红」的下限断言**：`scanned > 100`（方言审计）、`scanned > 50`（令牌审计）、
   `uiSources()` `hasSizeGreaterThan(30)`。**第 2 条比第 1 条重要** —— 路径规则将来再写错，
   门禁会当场炸，而不是变成一条永远绿的摆设。

变异验证 **M4**：去掉 `DialectBranchAuditTest` 的归一化 → 红，失败原文列出
`ui\adb-ui-shell\src\main\java\io\agenticdb\ui\shell\ConnectionWizard.java`、
`ui\adb-ui-shell\src\test\java\...\AppBarTest.java` 等全部合法文件 → 改回必绿。

### 33.3 S-9：对象首页对象级工具条

Navicat 的对象清单顶部固定有「打开 / 设计 / 新建 / 删除（/ 刷新）」；AIDB 只有右上角 `+`。
这一轮补上 `ObjectHomePage.Host`（**页面不直接依赖 AppShell**，四个回调各管一件事）：

```java
interface Host {
    default void newTable() { }
    default void design(SchemaService.SchemaObject object) { }
    default void dropTables(List<String> tables) { }
    default void inspect(SchemaService.SchemaObject object) { }
}
```

启用态由**选择本身**决定（`updateToolbarState()` 在加载完、过滤后、选择变化时都调）：

| 动作 | 启用条件 | 理由 |
|---|---|---|
| 打开 | 已连接 + **单选任意对象** | 函数/过程有定义体页 |
| 设计 | 已连接 + 单选**表或视图** | 结构页对视图只读 |
| 新建 | 已连接即可 | 不需要选对象 |
| 删除 | 已连接 + 非空 + **每一行都是表** | 删视图/函数走别的路径，不能混进同一次 DROP |
| 未连接 | 全灰 | — |

同时把详情面板接上选择（`host.inspect`）：选一行看一行，取消选择清空面板。
按钮用 `Icons` 矢量图 + Tooltip + accessible text（`IconSystemAuditTest` 守着）。

门禁 `ObjectHomeToolbarTest`(4)：窗口里真的有工具条 / 选择驱动四个按钮启停 / 点下去真的到宿主 /
详情面板跟随选择。变异 **M1**：去掉选择监听 → **3 条红**
（`buttonsReachTheHost` 期望 `["users"]` 实际 `[]`；`detailsPanelFollowsSelection` 面板为空；
`selectionDecidesWhichActionsAreAvailable` 触发器行上「设计」仍可点）→ 改回必绿。

### 33.4 D-1：SQLite 主键被显示成「可空 YES」

SQLite 的 `INTEGER PRIMARY KEY` 是 **rowid 的别名**：插入 NULL 会自动分配，语义上永远非空；
但 `PRAGMA table_info` 的 `notnull` 仍是 0。照抄就会在结构页显示「可空 YES」。

**只认 `INTEGER`**：`TEXT PRIMARY KEY` 在 SQLite 里**真的允许 NULL**（历史遗留行为），
不能为了好看一并改成非空 —— 那会教用户写出插入失败的语句。

门禁 `StructureServiceTest.integerPrimaryKeyIsReportedAsNotNullable` **两向断言**：
`users.id`（INTEGER PK）非空 **且** `codes.code`（TEXT PK）保持可空。
变异 **M3**：去掉 `&& !rowidAlias` → 红（`[INTEGER PRIMARY KEY 是 rowid 的别名，SQLite 保证非空]`
`Expecting value to be false but was true`）→ 改回必绿。

### 33.5 S-6：可读列宽 + 横向滚动（详情面板不再把列头压没）

旧实现：`CONSTRAINED_RESIZE_POLICY` 等比例压扁 + 数据列 `minWidth` 只有 72px → 列头只剩省略号。

第一版实现用的是 `UNCONSTRAINED_RESIZE_POLICY`。**实拍发现它在宽屏下不再填满** ——
`p0-shell.png` 右侧留了一大片空白（旧图是填满的）。所以先用一个探针
（`ColumnResizePolicyTest`）把两种策略的实际行为测出来：

| 场景（8 列，pref 200 / min 96） | `UNCONSTRAINED` | `CONSTRAINED_FLEX_LAST` |
|---|---|---|
| 视口 1200 | 总宽 **1600**（停在 pref，右侧空白 400px） | 每列 150，**总宽 1198 = 填满** |
| 视口 500 | 每列 96（总宽 768），横向滚动条 ✔ | 每列 **仍是 96**（不再压小），横向滚动条 ✔ |

结论：**约束策略本来就尊重 `minWidth`** —— 之前「CONSTRAINED 会把列压扁」的判断只对了一半
（压扁是因为下限只有 72px，不是因为策略不认下限）。最终方案：

- 三张表（数据网格 / 表清单 / 对象首页）统一 `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`；
- 新增纯助手 `ColumnWidths`：西文 7.2px/字符、**CJK 算 2 个单位**、列头固定装饰占位 60px，
  夹在 96–260px；清单页文本列用 `listColumnMin(title, floor)`；
- **列头装饰也算进下限**：`[PK]` / `•`（脏行/删行）/ `· 自增` —— 只算列名会回到 72px 老问题；
- 用户拖过的列宽保留，但**夹在可读下限以上**（`Math.max(minWidth, savedWidth)`）。

效果：宽屏填满、挤窄时卡在可读下限并出横向滚动条（`p2-details.png` 上六列列头完整，
底部有横向滚动条）—— **既不空白，也不省略号**。

门禁 5 条：三张页各 1 条（策略必须是 `CONSTRAINED_FLEX_LAST` + 每列 `minWidth ≥ 72` +
总下限 `≥ 800`）+ `ColumnResizePolicyTest` 2 条（宽屏填满 / 窄屏卡住下限且能横向滚）。
变异 **M2**：`dataColumnMin` / `listColumnMin` 都返回 60 → **3 条页面门禁同时红**
（`[name 的列头不能被压到读不出来]` 实际 60.0、`[引擎 列的最小宽度]` 60.0 < 72.0、
`[类型 列的最小宽度]` 60.0 < 72.0）。变异 **M5**：把探针换回 `UNCONSTRAINED` →
`wideViewportFillsTheFullWidth...` 红（总宽 **1600** vs 视口 1200）、
`narrowViewportStopsAtTheMinimum...` 红（列宽不是 96）→ 改回必绿。

**Q-2（S-6 的连带修正）**：`GridInteractionTest.draggingAHeaderReordersTheColumns` 抓的是
最右边 `score` 列头 —— 列有可读下限后它在 1200px 视口外，TestFX 报
`BoundsLocatorException: bounds are not visible in Scene`。拖拽验证的是「列头拖放 → 重排」，
与挑哪一列无关，所以改成拖**前两列**（任何宽度都可见）；不是把列宽压回去换绿。

### 33.6 Q-1 / 顺带两件

- **Q-1 快捷键门禁**：`QueryPageTest` 把 `meta=true` 写死成「快捷键」，在 Windows 上必然按不出
  Ctrl 组合 → 改用平台感知的 `shortcut(KeyCode)`（mac ⌘ / 其它 Ctrl）。生产代码
  `isShortcutDown()` 本来就是对的，**测试错了，不是功能错了**。
- **A20**：`QueryLimits.MAX_CELL_BYTES` 声明后零引用 → 已删（`grep` 全仓 0 命中）。
- **连接档案路径回显**：连接级详情面板的 facts 用 `ConnectionStore.file()` 的**实际路径**
  （测试与自定义启动会覆盖默认 `~/.agenticdb/connections.json`，不能硬编码）。

### 33.7 门禁与计数

`./mvnw -B clean verify` **BUILD SUCCESS** + `java tools/TestCount.java`：

| 指标 | 值 |
|---|---|
| 执行 | **755** |
| 测试类 | **95** |
| 失败 | **0** |
| 环境跳过 | 44（`adb-jdbc` 30 + `adb-introspect` 14 —— 与本轮改动无关） |

比第十九轮 745 多 10 条：S-6 +5、S-9 +4、D-1 +1；类 +2（`ObjectHomeToolbarTest` / `ColumnResizePolicyTest`）。
**5 个变异（M1–M5）全部验证通过**：改坏必红、改回必绿，失败原文逐条记在 33.2–33.5。

---

## 34. 第二十一轮（2026-10-24）：UIUX 升级 + P2/P5/P6 收尾（执行中）

> 触发：用户提供两张 Navicat 高保真实拍（数据网格 / 表清单），要求按
> **功能 / 交互 / 组件 / 布局 / 字体 / 颜色 / 尺寸 / 投影 / 扁平化 / 图标**重新整理升级清单。
> 证据与量差见 **`docs/NAVICAT-COMPARE.md` §9**；批次规范与目标值见 **`docs/UI-ROADMAP.md` §12**。
> 参考图已归档：`docs/screenshots/navicat/navicat-data-grid-2026-10-24.png` /
> `docs/screenshots/navicat/navicat-table-list-2026-10-24.png`。

### 34.1 本轮裁定（2026-10-24）

| 事项 | 裁定 | 备注 |
|---|---|---|
| P2 / P5 / P6 全部批次 | ✅ **同意做** | 明细见 §34.3；顺序见 §34.4 |
| `declarations/` | ✅ **已删除** | 根目录 `declarations/` + `core/adb-declarations` 模块 + POM + `adb-jdbc` 测试依赖 + `DeclaredDialectConsistencyTest`；实际 **-43 条 / -3 类**（40 + 3），基线 755→**712** |
| C4 密度第四档 | ✅ **做** | 新增 `dense 22px`；默认从 `normal 28` 改为 `compact 24`；对比截图用 `dense` |
| Windows 凭据 | ✅ **做**（JNA WinCred） | 只加在 `adb-session`；测试用唯一 target 前缀 + 清理 |
| xlsx | ✅ **做**（Apache POI） | 只进 `adb-transfer`；导出 `SXSSF` / 导入 SAX，禁止整文件读入内存 |
| 连接分组 / 标签 | 分组做，**tags 暂不做** | 一次只动一个字段，避免档案迁移风险 |
| A4 `paramStyle: numbered` | ❌ **不做** | JDBC 全用 `?`，没有消费方；写进「不做」清单 |
| 连接属性页 | 功能已完成 | 只补 `ConnectionWizardTest` 一条门禁 |
| S-7 欢迎页 | ✅ **已裁定（2026-10-24）：接 + 删** | 品牌接应用栏（矢量 `Icons.Icon.BRAND`），不可达的欢迎页与 `.adb-welcome*` / `SIZE_BRAND` / `fs-2xl` 连带删净；落地与变异验证见 §34.2 |

### 34.2 UIUX 执行清单（UX-0…UX-6）

> 目标值与门禁的完整定义见 `docs/UI-ROADMAP.md` §12.1–12.2。
> **勾选标准**：代码落地 + 门禁绿 + 变异验证 + 截图重拍，四项缺一不算完成。

#### UX-0 · 可复现（S，先做）— ✅ 已完成（2026-10-24）

- [x] `shots.sh` 每张图固定 `ADB_DENSITY`（默认 `compact`；对比图 `dense`）
- [x] `AppShell` 读 `ADB_DENSITY` 且**不写回** prefs
- [x] 测试/截图使用独立 prefs 或启动前清理 `ui.density`
- [x] `ScreenshotScriptAuditTest` 断言脚本含 `ADB_DENSITY`

**落地记录**

- `SettingsStore.openNode()`：`ADB_PREFS_NODE` 指定独立节点（`Preferences.userRoot()` 下），`ADB_PREFS_RESET=1` 在**首次构造时**清空该节点；**只有显式给了节点名才允许清理**，避免误清用户真实偏好。
- `AppShell`：`ADB_DENSITY` 优先于 prefs，且强制值时**不写回**（`applyDensity` 里 `forcedDensity().isEmpty()` 才 `settings.set`）。
- `scripts/shots.sh`：默认导出 `ADB_DENSITY=compact` / `ADB_PREFS_NODE=io/agenticdb/screenshots` / `ADB_PREFS_RESET=1` / `ADB_CONNECTION=demo`（`ADB_THEME_FORCE` 原有）。
- **实测确定性**：`./scripts/shots.sh p0-shell` 连跑两次，两次 SHA-256 均为 `2ee03edb…f03e8`、50184 字节（尺寸与像素完全一致）。
- **门禁**：`ScreenshotScriptAuditTest.shotsScriptPinsThemeDensityConnectionAndPrefs`、`AppShellTest.forcedDensityWinsAndDoesNotWriteBackToPreferences`；**两条均做过变异验证**——删 `export ADB_DENSITY=` → 脚本审计红「[截图必须用 ADB_DENSITY 钉住密度]」；去掉 `forcedDensity().isEmpty()` 守卫 → 偏好断言红「expected "comfort" but was "compact"」，改回必绿。

#### UX-1 · 视觉令牌基线（M）— ✅ 已完成（2026-10-24）

- [x] 圆角收敛 **3 / 4 / 6 / 8**；按钮与输入框 **4**，搜索框去胶囊
- [x] 新增 `border-subtle` / `bg-subtle`（亮暗各一套；`border-strong` / `bg-canvas` 原已有）
- [x] 投影只留 popover 一级；删卡片投影
- [x] 图标尺寸 14 / 16 / 20 三档，删散落尺寸
- [x] `TokenParityTest` / `AppCssAuditTest` / `ContrastTest` 全绿
- [x] 变异：`r-sm` 改 12 → 圆角审计红；删暗色 `bg-canvas` → 令牌对拍红

**落地记录**

- `tokens.json` **96 → 97**（60 色 / 34 尺寸 / 3 时长）：新增 `border-subtle`（亮 `#E3E6EA` / 暗 `#263449`）、`bg-subtle`（`#F7F8FA` / `#16213A`）、`r-xs` 3px；删除 `r-xl` 12px 与 `shadow-2`。
- `app.css`：圆角只剩 **0 / 3 / 4 / 6 / 8 / 999**（复选框 3、控件 4、卡片/面板 6、对话框 8）；连接状态从胶囊改为 4px；卡片、抽屉、对话框不再投影，只有命令面板与 toast 用 `shadow-1`。
- 行线换 `border-subtle`（表头下边/列分隔/行下边），分区底换 `bg-subtle` —— 用「面 + 淡线」分层，不靠投影。
- 图标新增 `Icons.SIZE_PRIMARY = 20`，对象主工具条四个动作使用；`Icons.of(icon)` 默认改引 `SIZE_TOOLBAR`；`AppBar` 唯一一处字面量 `16` 改常量。
- **门禁**：`AppCssAuditTest.shadowsAreLimitedToTheFloatingElevation`（新增，8 条）、`IconSystemAuditTest.iconSizesComeFromTheNamedScale`（新增，5 条）；圆角/令牌门禁同步新档位。
- **4 个变异全部验证**：对话框圆角改 12 → `everyRadiusComesFromTheScale` 红；删暗色 `bg-subtle` → `themesContainEveryColorToken` 红（`[dark theme 缺少 -adb-bg-subtle]`）；给 `.adb-card` 加投影 → 投影门禁红；`AppBar` 尺寸写 18 → 图标门禁红。改回全绿。
- **截图重拍**：`./scripts/shots.sh` 12 张全部重拍（亮 11 + 暗 1），逐张核对：卡片 / 详情 / 对话框投影已消失（`p0-shell` / `p2-details` / `p6-new-table`），对话框 8px、控件 4px 圆角正常，暗色主题同步（`p5-dark`）。

#### UX-2 · 密度、排版与 chrome（L，收益最高）— ✅ 已完成（2026-10-24）

- [x] 新增 `row-dense = 22`；四档 dense/compact/normal/comfort 全页生效（`DensityCoverageTest` 四档 × 网格/查询/清单/结构/树）
- [x] 默认密度改 `compact`；菜单四项并能切换网格 / 清单 / 树（`AppShellTest.densityDefaultsToCompactWhenNoPreferenceIsStored`）
- [x] 网格/清单/树/工具条标签字号 **12px**；状态栏/类型行 11px（`AppCssAuditTest.dataSurfacesUseTheCompactBodySize`）
- [x] 单元格内边距 dense `2 6` / normal `4 8`；列头同步（`sp-1h` 令牌 + 间距规则登记例外）
- [x] chrome：标签 28 / 对象头 36 / 页工具条 34 / 分页器 36 / 状态栏 24（`ChromeMetricsTest.chromeHeightsMatchTheDenseBudget`）
- [x] `p2-users` 在 765px 高窗口可见 **≥ 22 行**（`ChromeMetricsTest.denseDensityShowsAtLeast22RowsAt765`：1268×765 + dense 实测通过；实拍 1280×800 + compact 也已到 22 行（第 22 行贴底裁半），改前 20 行）
- [x] `DensityCoverageTest` + 新 `ChromeMetricsTest`；变异验证（5 处：字号 13 / 默认 comfort / 分页器 44 / dense→24 / 菜单删「密集」，全部红→改回绿）
- [x] 重拍 `p2-users` / `p6-tables` / `p0-shell`（实际 12 张全重拍），与 Navicat 并排核对

**落地记录**

- `tokens.json` **97 → 99**：新增 `row-dense`（22px，亮暗同值）与 `sp-1h`（6px）；
  尺寸令牌 34 → 36。`Tokens.H_TABBAR` 36→**28**、`H_TOOLBAR` 38→**34**、`H_STATUSBAR` 26→**24**、
  `H_INPUT` / `H_BUTTON` 32→**28**（`GenTokens` 重生成，`TokenParityTest` 同步钉住新值）。
- **四档密度**：`RowHeights.of` 认 `dense/compact/normal/comfort`，**未知值与 null 回落 `compact`**
  （以前回落 `normal`，与「默认紧凑」矛盾）；`AppShell.applyDensity` 白名单四档并同步
  `Workspace`（数据网格 / 查询结果 / 清单 / 结构页）· `ObjectHome` · `SideBar`（对象树 `setDensity`，以前树完全不跟密度）。
- **排版**：`app.css` 数据面正文统一 **12px**（网格单元格、清单、对象树节点名、列名、结构页表、连接表、按钮、弹窗按钮）；
  `.root` 保留 13px 作界面基准（`fs-md` 仍有使用点，否则 `TokenParityTest` 会红）。
- **chrome**：标签栏 28（`.adb-tab` 26 + 2px 活动线）、对象头 44→36、页工具条 44→34、分页器 44→36、状态栏 26→24；
  图标按钮与文字按钮统一 28 高（对话框按钮仍 32 —— 弹窗里要点准，不追密度）。
- **dense 专属**：`.density-dense` 下网格 / 结构 / 连接表单元格与列头内边距 `2px 6px`，清单 `0px 6px`
  （6px 走新令牌 `sp-1h`；`AppCssAuditTest` 的间距白名单同步放行并写明理由）。
- **门禁**：新增 `ChromeMetricsTest`（2 条：五条框的实际高度 + 765px 高可见行数）、
  `AppShellTest.densityDefaultsToCompactWhenNoPreferenceIsStored`、
  `AppCssAuditTest.dataSurfacesUseTheCompactBodySize`；`DensityCoverageTest`（4 条）与
  `MenuBarFactoryTest`（4 条）扩到四档。**+4 条 / +1 类 → 720 / 93 / 0**。
- **5 个变异全部验证**：网格正文改回 13px → CSS 审计红；默认密度改 `comfort` → 默认值门禁红；
  分页器 36 改回 44 → `ChromeMetricsTest` 红；`RowHeights` 的 dense 返回 24 → `DensityCoverageTest` 红；
  视图菜单删「密集」→ `MenuBarFactoryTest` 红（1 失败 + 1 错误）。全部改回绿。
- **顺带修掉两个开发期自伤**（新门禁当场抓出，提交前已修）：`applyDensity` 白名单漏 `normal`
  导致「标准」被静默降级；`AppCssAuditTest.fontSizeOf` 未锚行首，把 `.x .adb-tree__name` 误当独立规则。
- **截图重拍**：`./scripts/shots.sh` 12 张全部重拍（`ADB_DENSITY=compact` + 独立 prefs + `ADB_CONNECTION=demo`），
  逐张核对：`p2-users` 22 行 / 12px 正文 / 两行列头不挤；`p6-tables` 清单行高与对象树一致；
  `p0-shell`、`p5-dark` 暗色同步；分页器与状态栏收薄后底部不再被顶出可视区。

#### UX-3 · 工具条与动作可发现性（L）— ✅ 已完成（2026-10-24）

- [x] `IconButtons.labeled(...)`：图标 + 文字 + tooltip + accessibleText（一次给全，id 由调用方设）
- [x] 数据页主操作带文字：保存 / 筛选与排序 / 列 / 视图 / 导入 / 导出
- [x] 数据页补「导入…」（目标表预选当前表）
- [x] 清单页补「导入 / 导出」（`TransferHub` 入口，选中表挪到第一候选）
- [x] 工具条按语义分组 + `.adb-toolbar__separator` 竖线；删除动作 `adb-button--danger`
- [x] 窗口 <1100px 收成纯图标（文字消失，**tooltip 与 accessibleText 保留**）；≥1100px 恢复文字
- [x] 新 `ToolbarDiscoverabilityTest`（5 条）+ `TableListPageTest` +2 + `ObjectHomeToolbarTest` +1；**9 个变异全部红→改回绿**
- [ ] 「历史」（按表筛）→ **推迟到 P2-3**；「单元格编辑器」→ **归 UX-4**（理由见下方落地记录）

**落地记录**

- **主操作全部带文字**：`IconButtons.labeled` 收在 `ButtonBase` 上（`Button` 与 `MenuButton` 都吃），
  文字 / 图标 / tooltip / accessibleText 四件套一次装配。数据页此前 11 颗纯图标按钮里，
  「保存」「删除记录」这种**改数据、不可逆**的动作只靠一个磁盘/垃圾桶图标 —— 用户只能靠猜。
  「清除条件」这类次要动作保持纯图标，不与主操作抢宽度。
- **语义分组**：数据页 `数据动作 | 查询与视图 | 数据交换 | 搜索框`；清单页
  `对象动作 | 表操作 | 数据交换 | 视图`；对象首页 `查看 | 编辑`。组间一条 `.adb-toolbar__separator`（样式在 CSS，不写内联色）。
- **响应式收字**：`DataGridPage.TOOLBAR_LABEL_MIN_WIDTH = 1100`，量的是**窗口宽度**而不是页面宽度
  （页面本身被详情面板挤窄时，工具条仍该显示文字 —— 挤的是搜索框，不是动作名）。
  经 `sceneProperty → window.widthProperty` 挂监听，收字时 `setText("")` 并补 `adb-button--icon`，
  展开时反向移除；**tooltip 与 accessibleText 任何档位都在**。
- **导入入口**：`#data-import` → `ImportWizard`，候选表把**当前表挪到第一位**（就是向导的预选值）。
  启用条件是 `tableSchema == null` 才灰 —— 与导出的 `!hasData` **刻意相反**：空表恰恰最需要导入。
  `createImportWizard(...)` 做成包级非模态接缝，因为 `ImportWizard#show` 是 `showAndWait`，
  在 TestFX 下会把 FX 线程锁死。清单页同理：`transferCandidates()` + `TransferHub`。
- **对象首页四颗主操作**：原来是裸 Modena 图标按钮（连 `adb-button` 类都没有），
  现在走 `toolbarOp(...)` = `adb-button` + 图标 + 文字 + tooltip，删除带 danger，`setMinWidth(USE_PREF_SIZE)` 防截断。
- **门禁 +8 条 / +1 类 → 728 / 94 / 0**。新类 `ToolbarDiscoverabilityTest` 跑**整个 App**（真挂载、真窗口），
  5 条分别守：六颗主操作有图标+文字+提示 · 窄窗口收字但提示不丢 · 分组有竖线且删除是 danger ·
  宽窗口下工具条没把搜索框挤出可视区 · 导入向导预选当前表。
- ⭐ **本轮抓出的第一个真问题是门禁自己**：`robot.lookup("#data-toolbar").tryQuery()` 在节点不可见时返回
  `Optional.empty()`，于是 `findById(null, …)` 返回 null，**每条断言都被静默跳过** —— 把「保存」的文字改成空串
  （M1）它照样绿。改成从 `.adb-grid-page` 手写场景图遍历（含 `managed=false` 分支）+ 找不到就
  `assertNotNull`，原则是「**宁可抛异常，也不放行**」；仓库里 `IconAuditTest` 早就不用 `robot.lookup`，正是这个原因。
  另：Monocle headless 的窗口有 **960px 下限**（请求 900 得到 960），所以尺寸断言写成「是否跨过阈值」而非等值。
- ⭐ **修好门禁后红出来的是生产代码**：`updateEditControls()` 与保存路径直接调 `saveButton.setText(...)`，
  绕过 `toolbarLabels` 登记表 —— 窄窗口收了字之后，只要数据变脏，「保存 3」会**自己长回来**，
  工具条重新变宽、搜索框被挤出去。统一改走 `setToolbarLabel(...)`。
- **两项推迟（不是漏做）**：
  ①「历史」按钮 —— `QueryHistory` 目前只存 SQL 字符串，没有表名与时间戳，**按表筛**无从谈起，
  依赖 **P2-3**（给历史加对象上下文）；②「单元格编辑器」—— 那是 **UX-4** 的网格交互批次，
  现有的 `CellDetailsDrawer` 已经是只读查看器，编辑器要在网格可写之上再做。

#### UX-4 · 网格交互（M）— ✅ 已完成（2026-10-24）

- [x] 行选中强 tint + **左侧 2px 强调条**；当前格（键盘光标）内描边 + 淡底
- [x] 单元格淡竖线；斑马纹再降一档（亮 `#F7F9FB→#FAFBFC`、暗 `.05→.035`）
- [x] 列头右键收齐：排序升/降/清除 · 移到左/右/首/尾 · 筛选 · 隐藏 · 自适应 · **冻结到此列 / 取消冻结** · 复制列名
- [x] 排序箭头**常显**（该列处于排序中就一直挂着 ↑/↓ 与序号，不像漏斗那样悬停才浮现 —— 状态不是装饰；无排序时不占位）。⭐ 本条**核对既有实现**（`DataGridPage` 的 `sortBadge`），本轮未改动，故不计新门禁
- [x] 双击列头 = 按内容自适应（与菜单项同一算法）
- [x] 网格左下 `+ / − / 应用 / 取消` 行操作条；只读表整排消失
- [x] `(NULL)` / `""` / BLOB 大小统一展示 + tooltip（BLOB 补 **GB 档**）
- [x] 长文本 / JSON / CLOB 单元格编辑器（抽屉文本页就地写：Esc 取消、Ctrl+Enter 应用）
- [x] 新 `SelectionStateTest`(8) / `CellDisplayTest`(6) / `CellEditorTest`(4) + `GridInteractionTest` +7；**25 条门禁逐条变异红→改回绿**

**落地记录**

- **选中态给一个行首锚点**：22–24px 行高、12px 正文里，只靠一层淡底经常要靠横向扫一遍才知道选中了谁。
  补一条 2px `accent` 左条，眼睛顺左缘就能定位。`hover` 规则**排在** `selected` 之前 ——
  悬停已选中的行时仍然是选中色，不被 hover 顶掉。
- ⭐ **槽位常留（本批最重要的排版不变量）**：状态一律**只改颜色、不改宽度**。
  基础规则把宽度写死并留出透明空槽（行 `-fx-border-width: 0px 0px 1px 2px`、格 `0px 1px 0px 2px`），
  选中条 / 脏值条 / 光标环 / 矩形选区四条状态规则都在这同一个槽里上色。
  为什么不能改宽度：行有边框会挤掉内容宽度，**「一选中文字就右移 2px」是肉眼可见的抖动**；
  行高 24px，上下再各吃 2px 就把 12px 正文挤没了。
  门禁 `selectingARowNeverMovesItsCells` / `clickingACellMarksTheCursorWithoutMovingText` 直接比**每个单元格的 x/y 与行高**。
- ⭐ **当前格不是 `.table-cell:focused`**：`TableCell.updateFocus()` 在非 cell-selection 模式下主动 `setFocused(false)`，
  本表是行选（MULTIPLE），所以 `:focused` 在这张表上**永远不匹配**（实测 `isFocused()=false`）——
  原来那条 `.adb-grid .table-cell:focused` 是一条**死选择器**，画了等于没画。
  改成 `DataGridPage.bindFocusCursor()` 盯 `focusModel.focusedCellProperty()` 打样式类 `adb-grid__cell--cursor`。
  光标**按列名记、不按 `TableColumn` 实例记**：冻结区的列是从右表**搬过去**的同一批实例，
  搬走之后 `grid.getVisibleLeafColumns()` 里就没有它了，按下标比会让冻结区的光标标不上。
  外发光也不行 —— 每格都被邻居紧贴着画，`dropshadow` 会被裁掉，实测「看起来像没有焦点」，所以用**内描边**。
- **冻结算术**：`dataColumns()` 只看得到 `grid` 的列，冻结列在 `frozenGrid` 里 ——
  于是新增 `dataColumnsOrdered()`（先冻结区后右表）做下标基准，`isColumnFrozen()` 判已冻列。
  ⭐ **禁用态必须诚实**：已冻的列「冻结到此列」禁用、右边剩不下两列的列禁用，
  否则用户点了会得到一个和菜单文字不符的结果（最后一列点冻结只会偷偷冻到倒数第二列）。
- **行操作条不重写判断**：四个按钮的 `disableProperty()` **bind** 到工具条同一批按钮，
  「删除」连 `visible/managed` 一起跟（无选中行时工具条那颗是整颗消失 ——
  摆一颗灰掉的删除在光标旁边比没有更危险）。只读表 `rowActions` 直接 `setManaged(false)`，
  一排永远点不动的按钮只会引人反复试，而这一排贴着光标，碍事程度高于工具条。
  配套新增 `discardChanges()`（放弃缓冲 + 清撤销计划 + 重建行 + 报数）。
- **`NULL` 显示成 `(NULL)`**：TEXT 列里真的会出现**内容为 "NULL" 的字符串**（CSV 导入、日志表、外部系统写的），
  灰斜体的 `NULL` 和一条值为 NULL 的文本在 12px 网格里根本分不出来，而这两种情况要做的处理完全不同。
  空串继续 `""`，三态各有 tooltip；`sizeLabel` 补 GB 档（大对象在真实业务表里就是 GB 级，
  不补就显示成 `1536.0 MB` 这种没法扫读的数字）。抽屉文本页的 NULL 说明同步对齐。
- **单元格编辑器做在抽屉里，不做弹窗**：格内那条单行输入框改不了 3KB JSON —— 看不见换行，只能左右拖。
  抽屉文本页因此变成可写的：回填**原始值**（不是渲染后的截断值）、**Ctrl+Enter 应用**
  （多行编辑器里回车是换行，不能拿来提交）、**Esc 只放弃本次编辑不关抽屉**（用户还想接着看这一格）、
  写进**同一个 `EditBuffer`**（和格子里改同一条路，脏计数一起亮）。
  排除条件：主键列（改主键等于改「这行是谁」，UPDATE 的 WHERE 会跟着失效）与只读表不出现编辑器。
- **门禁 +25 条 / +3 类 → 753 / 97 / 0**。25 条逐条变异验证（改坏→红→改回→绿）。
- ⭐ **本轮第二个假绿**：`primaryKeyAndReadOnlyTablesNeverOfferTheEditor` 第一版**删掉 `!definition.primaryKey()` 仍然全绿** ——
  演示库所有主键都是 INTEGER，`textual(type)` 在之前就短路了，那条规则**根本没被检验过**。
  修法：测试**自建一张 `codes (code TEXT PRIMARY KEY, note TEXT NOT NULL)`**，
  让「文本 + 主键」这个组合真的存在；同时补**正例**（可写表的普通文本列必须出现「编辑」）——
  只写反例的话，「编辑器永远不出现」这种改坏也能全绿。
- ⭐ **本轮第三个真问题（写缓冲会被切页污染）**：编辑器刚做完时，提交目标是由
  **「当前选中的页签」** 决定的（`commit()` 取 `activeArea()`）。于是
  「在文本页敲两下 → 切到 JSON 页 → 按 Ctrl+Enter」会把**美化过的 JSON（带换行缩进）**整段写进一个 TEXT 列，
  缓冲还只显示「1 处待保存」—— 数据损坏里最难查的那种。
  修法：编辑目标改成字段 `editingArea`（进编辑态时钉死，不再随页签变），
  且**换页即放弃这次编辑**（`stopEditing()`），不静默提交另一页的内容；
  门禁 `switchingTabsMidEditAbandonsTheEdit`（删掉换页守卫 → 红，已验证）。
- ⚠️ **测试脚手架的坑（记一次）**：`WaitForAsyncUtils.waitFor(...)` 会**阻塞调用线程**，
  放在 `@Start` 里等于阻塞 FX 线程，而加载结果恰恰要靠 FX 线程派发 → **死锁 → 超时**。等表格加载完必须放 `@BeforeEach`。
  另外变异脚本**必须先删旧报告再跑** —— 一次改坏 `sed` 没落上、编译又失败时，
  surefire 目录里留着上一轮的报告，看起来「跑了且绿」，实际什么都没测。
- ~~**已知缺口（不藏着）**：选中态、光标环、抽屉编辑器都是**交互态**，`scripts/shots.sh` 那 12 帧里没有一帧覆盖~~
  → **已在 UX-5-F 闭掉**（2026-10-24）：新增启动开关 `ADB_CELL` + 帧 `p2-cell`，
  三个交互态在同一格上叠着拍（选中强调条 / 光标左右两条蓝边 / 抽屉「编辑中」+ 应用·取消），
  并配两条门禁守「这一帧还在」与「开关真被读」。详见 §34.2 UX-5。


#### S-7 · 欢迎页裁定 —— ✅ 已完成（2026-10-24 · 裁定＝**接 + 删**）

> 裁定过程：§31.2 那三个选项是产品判断，问用户得到的答案是**「接 + 删」**——
> 品牌接到用户真看得见的地方，不可达的死代码一起清掉。A 和 B **同时执行**。

| # | 改动 | 落点 |
|---|---|---|
| 1 | 新增矢量 `Icons.Icon.BRAND`：数据圆柱（收窄到左下）+ 右上四角 AI 星芒，16 视框描边，随主题换色 | `Icons.Icon` |
| 2 | 应用栏 26px 方块里的通用 `DATABASE` 换成 `BRAND`，并给它 `#appbar-brand-glyph` 定位符 | `AppBar.brand()` |
| 3 | 删掉**不可达**的欢迎页：`showWelcome()`（30 行）、`brandMark()`、`BRAND_MARK` 常量、构造器里那次调用，以及 `AppShell.tabClosed` 的 `else` 分支 | `WorkspaceView` / `AppShell:2129` |
| 4 | 连带删净：CSS 四条 `.adb-welcome*` 规则、`Icons.SIZE_BRAND` 档位 | `app.css` / `Icons` |
| 5 | ⭐ **连带删令牌**：`fs-2xl`（20px）唯一的消费者是那张卡片的标题 —— 页面删了它就变成摆设，按 §31.1 第 4 行的规矩**删令牌**而不是硬造一个消费者；`tokens.json` 99→**98**（60 色 / **35** 尺寸 / 3 时长），`GenTokens` 重生成，`AppCssAuditTest.FONT_SIZES` 8 档→**7 档** | `tokens.json` / `Tokens.java` / `AppCssAuditTest` |
| 6 | ⭐⭐ **像素对比抓出的真 bug（品牌根本看不见）**：通用 `.adb-icon { -fx-stroke: -adb-text-secondary }` 排在 `app.css` **后半段**，和 `.adb-appbar__mark-glyph`（应用栏段）**同为单类选择器**，JavaFX「同特异性后写胜」→ 灰字色顶掉了白字。改写成两类 `.adb-appbar__mark .adb-appbar__mark-glyph`（特异性 0,2,0）。这不是新代码的问题 —— **换 mark 之前那颗 `DATABASE` 圆柱同样是灰的**，只是当时没人看得见它。规格落 §4.3 第 6 条（`UI-VISUAL-SPEC.md`） | `app.css` 应用栏段 |

**门禁**（净 **+1**：删 3 条测不到的死代码门禁，补 4 条测得到品牌的门禁 → 基线 **753 → 754 / 97 / 0**）：

| 门禁 | 内容 |
|---|---|
| `AppBarTest.theBrandMarkIsTheAidbMarkNotAGenericDatabaseIcon`（新） | 方块里那个 `SVGPath` 的 `getContent()` 必须**等于** `Icons.Icon.BRAND.path()` 且**不等于** `DATABASE.path()` |
| `AppBarTest.theBrandGlyphKeepsItsContrastAgainstTheGenericIconRule`（新） | 直接读**计算后的** `glyph.getStroke()`：必须是 `text-inverse`（亮色＝白）、**不得**是 `text-secondary` —— 这条专门守第 6 行那个层叠坑，把选择器改回单类它就红。⚠️ 前提：`@Start` 得把 `theme-light.css` + `app.css` **挂上场景**并给根节点加 `theme-light`，否则 CSS 根本没生效，这种断言**永远假绿** |
| `BrandMarkAuditTest.theUnreachableWelcomePageStaysDeleted`（新） | 主源码 + `app.css` 里 `showWelcome` / `brandMark` / `adb-welcome` / `SIZE_BRAND` 一处都不许留（**剥掉注释再扫** —— 解释「为什么删」的注释不是残留，第一版没剥注释被自己的注释判红）；带 `scanned > 30` 防空扫假绿 |
| `BrandMarkAuditTest.theBrandIsWiredIntoThePartTheUserActuallySees`（新） | 品牌必须挂在 `AppBar` 上 —— 只定义一个 `BRAND` 没人用，等于没做 |
| `WelcomeBrandTest`（**删**，3 例） | 三条全绿，测的是一张**用户永远看不到**的页面：测试直接 `new WorkspaceView(...)`，绕过了真实启动路径。这是「绿门禁 ≠ 真功能」的样本，记进 §33 那一类 |

**变异验证（5 条，逐条改坏→红→改回→绿）**：

| 变异 | 红的门禁 |
|---|---|
| M1 应用栏换回 `Icons.Icon.DATABASE` | `AppBarTest.theBrandMarkIs…` + `BrandMarkAuditTest.theBrandIsWired…` **两条同时红** |
| M2 把 `showWelcome()` 加回 `WorkspaceView` | `theUnreachableWelcomePageStaysDeleted`（`WorkspaceView.java → showWelcome`） |
| M3 把 `.adb-welcome` 样式规则加回 `app.css` | 同上（`app.css → adb-welcome`），另 `StyleClassCoverageTest` 也会抓孤儿规则 |
| M4 把 `SIZE_BRAND = 40` 加回 `Icons` | 同上（`Icons.java → SIZE_BRAND`） |
| M5 把 `.adb-appbar__mark .adb-appbar__mark-glyph` 改回单类 | `theBrandGlyphKeepsItsContrastAgainstTheGenericIconRule` **单独红**（计算色变回 `text-secondary`）—— 恰好证明这条门禁守的就是那个层叠坑，别的门禁全绿 |

> ⚠️ **本轮四个脚手架教训**（①②是「看起来红了，其实没测」，③④是反过来「看起来绿了，其实没测」）：
> ① 变异脚本第一次跑**去掉了 `-am`** → `adb-ui-shell` 从 `~/.m2` 拿**旧的** `adb-ui-core` jar，
> 编译直接失败、surefire 报告根本没生成，`exit=1` 长得像「门禁红了」。修法：**报告文件不存在＝无效运行**，
> 脚本里显式判，别拿退出码当结论。
> ② `fs-2xl` 变摆设只有**全量构建**（`TokenParityTest` 在 `adb-ui-core`）才抓得到 ——
> 单跑 `-pl ui/adb-ui-shell` 那三条门禁时一切正常。**删样式必须跑一次全量。**
> ③ ⭐ **CSS 断言必须先把样式表挂上场景**：`AppBarTest` 原本只 `new Scene(bar)`，样式表一条都没加 ——
> 那样 `getStroke()` 拿到的是 `Shape` 的默认值（`Color.BLACK`），跟任何令牌色都对不上，
> 「必须等于 `text-inverse`」只会红得莫名其妙，反过来也守不住任何东西。
> 现在 `@Start` 里显式加 `theme-light.css` + `app.css` 并给根节点 `getStyleClass().add("theme-light")`。
> 更狠的是：**这条 bug 是像素对比抓出来的，不是门禁抓出来的** —— 全绿了三轮，方块里的星一直是灰的。
> ④ `-Dtest` 里多类**必须用逗号**：写成 `-Dtest='AppBarTest+BrandMarkAuditTest'` 时 surefire 一条都没跑，
> 但配了 `-Dsurefire.failIfNoSpecifiedTests=false` → **退出码 0、报告文件不存在**。
> 又一次「看起来绿了其实没测」，判据同 ①：**报告文件不存在＝无效运行**。

**截图**：`./scripts/shots.sh` 12 张全部重拍（应用栏常驻每一帧，品牌 mark 第一次**拍得到** ——
S-5 当年拍不到的那条缺口就此闭掉）。⚠️ 第一套 12 张拍在对比度修复**之前**，那时 mark 是灰底蓝块上
一颗看不见的星，已作废重拍；裁剪放大（`/tmp/Crop.java`）在 `p0-shell` 与 `p5-dark` 上分别核对
亮/暗两套主题下字形确实是 `text-inverse`。

#### UX-5 · 侧栏 / 标签 / 状态栏 —— ✅ 已完成（2026-10-24 · 六项全落 + 一条新缺口）

> 这一批是「**同一套语义只说一次**」的收口：侧栏选中、网格选中、状态栏信息、标签标题，
> 以前各说各话（树用蓝晕、网格用强调条；顶栏说「已连接」、侧栏不说；标签重名分不出归属）。

| # | 项 | 改动 | 落点 |
|---|---|---|---|
| A | 侧栏选中 | 树格选择器从 `.adb-object-tree > .virtual-flow > .clipped-container > .sheet > .tree-cell`（**0,5,0**）降到 `.adb-object-tree .tree-cell`（0,2,0）—— 原来那条把 `:hover` / `:selected` / `--current` 全压死，实测选中格计算底色是**透明**，「点了没反应」；选中改 `bg-selected` 填充 + 左 2px `accent` 强调条（**与网格同一套令牌**），左槽由基础规则常留（只换色不改宽度）；焦点格**去掉蓝边 + 光晕**改淡底 `bg-active`（容器已有焦点环，格子里再画一圈是「框中框」），且 `:focused` 必须排在 `:selected` **之前**（同为 0,3,0，后写胜） | `app.css` 树段 / `SidebarSelectionTest` |
| B | 连接存活点 | 连接节点画一颗 `DOT` 实心点：绿 `success`＝已连上、灰 `text-muted`＝未连；判据由 `AppShell` 注入（`setConnectionLiveness`），**按连接取**且折叠 catalog 变体（MySQL 一连接多库只算一颗点）；无判据时**不画点**（不拿「没信息」冒充「未连接」）；`accessibleText` 给「已连接 / 未连接」 | `SideBar` / `AppShell` / `SideBarLivenessDotTest` |
| C | 标签重名 | 标题重名时改画 `表名 @连接名`，**每个标签的 tooltip 永远给全名**；连接名走 `setConnectionLabels`（拿档案里的显示名，不是 id）；关掉一个重名标签后**自动退回**朴素标题 | `TabBar.refreshTitles()` / `TabBarTest` |
| D | 状态栏两段 | 左段**当前语句**：压成一行显示（`replaceAll("\\s+"," ")`）+ `maxWidth 240` + `ELLIPSIS`（**渲染层截断，复制的是原文**）、tooltip 给完整语句 + 「点击复制」、点一下把**原始语句**写进系统剪贴板并回一句「已复制当前语句」、`HAND` 光标；右段**最后刷新**：相对时间「刚刚 / N 分钟前 / N 小时前 / N 天前」，自带 30s `Timeline` 自己 repaint，时钟可注入（`setClockForTest`）。⚠️ 诚实性：查询**失败**只推语句、**不推进刷新时刻**；换到非数据/查询页签两段一起收起（连分隔条），不摆「—」占位 | `StatusBar` / `DataGridPage` / `QueryPage` / `AppShell.pageFeedback()` / `StatusBarTest` |
| E | 侧栏默认宽 | `w-sidebar` 250 → **260**（200px 表名 + 图标 + 收藏星在 250 下挤掉末字）；持久化本来就有，补两条门禁钉住「**上次宽度赢过令牌默认**」与「**双击分隔条复位并落盘**」 | `tokens.json` / `Tokens.java` / `AppShellTest` |
| F | 交互态那一帧 | ⭐ 闭掉 UX-4 留的缺口：新增启动开关 `ADB_CELL=1`（`adb.cell`）→ `poseGridCellWhenReady` 轮询等行灌进来，调 `DataGridPage.poseInteractionStates(1, "name")` 把**选中行 + 光标格 + 详情抽屉**三个交互态一次摆出来，脚本再 `ADB_SHOTS_CLICK=data-details-edit` **真点**「编辑」进入编辑态 → 新帧 `p2-cell`（截图 12 → **13 张**）。⚠️ 两个刻意的选择：① 光标走 `focusModel.focus(..)` 而不是直接糊样式类 —— 摆的必须是真路径，否则「截图好看」和「键盘真有光标」会脱钩；② 列选 `name` 不选 `bio` —— 抽屉浮在右侧 480px，光标格落在它底下就**拍不到要拍的东西**（第一版实拍就是这样，已改） | `AppShell` / `DataGridPage` / `scripts/shots.sh` / `SelectionStateTest` / `ScreenshotScriptAuditTest` |

**门禁**（净 **+19 条 / +3 类** → 基线 **754 → 773 / 100 / 0 失败 / 44 跳过**）：
`SidebarSelectionTest` 3（新）· `SideBarLivenessDotTest` 3（新）· `TabBarTest` 3（新）·
`StatusBarTest` +3（语句段 / 点击复制 / 相对时间）· `AppShellTest` +3（存活点接线 / 持久化宽度 / 双击复位）·
`AppBarTest` +1（点真的绿：读**计算后**的 `-fx-fill`）· `SelectionStateTest` +1（摆帧三态落地）·
`ScreenshotScriptAuditTest` +2（有这一帧 + 帧里每个 `ADB_*` 开关主源码真读了）。

**⭐ 顺带改坏一条老门禁（必须记）**：UX-4 的
`theCursorUsesALiveSelectorAndTheTreeKeepsItsGlow` 断言「树的焦点**仍用发光**」，
而本批 A 项把发光换成了淡底 —— 该门禁**全量构建里直接红**。改成
`theGridCursorIsLiveAndTheTreeCursorIsAFill`：不只翻断言，还补上 A 项真正的不变量
（`:focused` 不得再写 `-fx-border-color` / `dropshadow`，且必须排在 `:selected` 之前）。
 **教训**：本轮先前那次「13 类批量核对」是**按我改了什么挑的类**，没跑全量 ——
`SelectionStateTest` 不在里面，所以这条红一直藏着。**改 CSS 语义 = 全量构建的活**（同 §S-7 教训②）。

**变异验证（8 条，逐条改坏→红→改回→绿）**：

| 变异 | 红的门禁 |
|---|---|
| M1 `poseInteractionStates` 里删 `focusModel.focus(..)` | `theScreenshotPoseLightsUp…`（光标类没了） |
| M2 删 `selectionModel.select(at)` | 同上（选中断言红） |
| M3 删 `showDetails(..)` | 同上（抽屉不开） |
| M4 列不存在时也 `return true` | 同上（诚实 no-op 那条红：「摆不出完整状态就不能装作摆好了」） |
| M5 `shots.sh` 删掉 `p2-cell` 那一行 | `interactionStatesHaveTheirOwnFrame` |
| M6 删掉 `AppShell` 里读 `ADB_CELL` 的那个 `if` | `everyEnvSwitchUsedByTheScriptIsActuallyReadByTheUi`（**开关写了没人读**＝这一帧静默拍成默认界面） |
| M7 树的 `:focused` 改回蓝边 + 光晕 | `theGridCursorIsLiveAndTheTreeCursorIsAFill` |
| M8 把树的 `:focused` 整块删掉 | 同上（找不到选择器 / 顺序断言红） |

> ⚠️ **本轮最贵的脚手架坑（差点交出八条假变异）**：这台机器的 `python` / `python3` 是
> **Microsoft Store 的占位壳**，`python -c "print(...)"` **什么都不打印、退出码还是 0**。
> 用它写变异脚本 → 替换根本没落上 → 门禁照常绿 → 看起来「改坏不红，门禁没用」。
> 判据：**变异必须先 `grep` 证明改到位，再跑测试**；本仓库脚本一律用 `perl -0pi -e`（`/usr/bin/perl` 是真的）。
> 同类的第二次确认：`sed` 单行改动能落，多行不行 —— M1/M2/M3/M7 用 `sed` 单行 + `grep -c` 核对后才可信。

**⚠️ 新缺口（登记，不在本批做）——「状态栏文字层级」专项**：
D 项写门禁时实测出来的：基线规则 `.adb-statusbar .label`（0,2,0）盖住**所有单类**分段规则，
于是 `.adb-status__connection / __range / __duration / __version / __transaction …` 那 10 条
`-fx-text-fill` **从未生效过**，整条状态栏一直是清一色 `text-secondary`（把新规则改回单类，
计算值立刻从三级 `#5F6E82` 退回二级 `#64748B` —— 这是**变异实测**出来的，不是推断）。
本批只保证新加的两段有效（写成 0,3,0），老的那批留专项：要么整条降档统一，要么按
「结论 / 参考」分级重排，**别顺手改**（会牵动 `ContrastTest` 的配对）。

**截图**：`./scripts/shots.sh` **13 张**全部重拍（`p2-cell` 为新增帧）。
逐张 4× 裁剪核对：`p2-cell` 里第 2 行的**选中强调条 + 光标格左右两条蓝边 + 抽屉「name · 编辑中」+ 应用/取消**
三层同时在场且互不遮挡；侧栏连接节点那颗点**是绿的**（`p2-cell` / `p5-dark` 亮暗各一次）；
状态栏左段语句等宽三级灰、右段「刷新于 刚刚」，`p5-dark`（连接管理页）两段**正确地不出现**——
没有摆「—」占位。⚠️ `p2-cell` 第一版拍的是 `bio` 列，光标格整个压在抽屉底下 → 作废重拍。

#### UX-6 · 功能缺口（L，按序）

- [ ] 对象首页列表 / 网格切换（复用 `TableListPage` 卡片与 `ui.*.view`）
- [ ] 结构页「选项」页签（MySQL：引擎 / 排序规则 / 自增 / 行格式；其它方言显示「—」）
- [ ] 数据页「概览」页签（行数 / 大小 / 引擎 / 索引数 / 最近操作）
- [ ] 侧栏「已保存查询」分组（点击载入新查询）
- [ ] 不做：备份 / 自动化 / 模型 / BI / 数据生成 / 脱敏 / AI（无真实后端）

### 34.3 P2 / P5 / P6 批次（已同意，待执行）

| 批 | 内容 | 门禁 / 验收 | 状态 |
|---|---|---|---|
| **P2-1** | 清单列宽记忆 + 长注释 Tooltip + `sizeText` 补 GB/舍入 | 两页各 +2、`TableMetaTest` +4；真 MySQL 契约 | ⬜ |
| **P2-2** | 错误详情弹窗 + 批量汇总页通用化 | 纯格式化测试 + 三处接线；截图 | ⬜ |
| **P2-3** | `QueryHistory` 带对象上下文 + 详情面板「最近操作」 | `QueryHistoryTest` +3、面板 +1 | ⬜ |
| **P2-4** | S-7 欢迎页裁定（C4 已并入 UX-2） | 按裁定 | ✅ **DONE (R22)** —— 裁定＝接 + 删，见 §34.2 |
| **P5-1** | 连接重命名 / 分组（tags 暂缓） | Store +2、连接页 +2、侧栏 +1 | ⬜ |
| **P5-2** | Windows WinCred 后端（JNA） | Windows 上真跑；其它平台跳过不计通过 | ⬜ |
| **P5-3** | 连接失败语义测试（8 类 / 超时 / 取消 / 重连） | 新 `ConnectionFailureTest` | ⬜ |
| **P5-4** | 内省补齐：PG 序列 / `getExportedKeys` / DEFAULT·COMMENT·CHECK 契约 | 契约 +4（无 Docker 跳过） | ⬜ |
| **P5-5** | 删 `declarations`；A4 记为不做；属性页补 1 条门禁 | 编译 + 基线口径更新 | ⬜ |
| **P6-1** | 导入按目标列类型转换，坏行仍走报告 | 新 `ImportTypeCoercionTest`（约 8） | ⬜ |
| **P6-2** | 查询结果导出（`exportSql` + 查询页范围选择） | `TransferExportTest` +2、`QueryPageTest` +2 | ⬜ |
| **P6-3** | xlsx 导入导出（POI，流式） | 新 `XlsxCodecTest`；向导截图 | ⬜ |

### 34.4 执行顺序

```
0) ✅ 文档对齐 + 删 declarations（已完成，基线 **712 / 92**）
1) ✅ UX-0 → UX-1 → UX-2          （密度/令牌，P0；基线 720 / 93）
2) ✅ UX-3 → ✅ UX-4 → ✅ S-7 → ⏭ UX-5（工具条/网格/品牌落点/侧栏，P1；S-7 后基线 754 / 97）
3) P2-1 → P2-2                    （清单可读性 / 错误可诊断）
4) P5-2 → P5-1 → P5-3 → P5-4     （Windows 凭据先跑真机）
5) P2-3                           （最近操作，UX-3 的「历史」依赖它）
6) P6-1 → P6-2 → P6-3            （类型转换 → 查询导出 → xlsx）
7) UX-6                           （功能缺口，独立可最后）
```

- ✅ **S-7 已裁定并落地（2026-10-24，UX-4 与 UX-5 之间）**：接 + 删，见 §34.2。
  下一动作是 UX-5（标签栏的取舍不再被它挡住）。
- 每个 UI 批次完成后**立即**重拍受影响的截图；不要攒到最后。
- `declarations` 删除后已跑完全量门禁，**712 条 / 92 类** 已成为新基线；
  后续不要拿 755 与 712 直接比。

### 34.5 统一验收

1. `./mvnw -B clean verify` **0 失败**；`java tools/TestCount.java` 记录新口径；
2. 新门禁**必须变异验证**（改坏必红、改回必绿，失败原文记录在本节或对应批次）；
3. 截图先钉 `ADB_THEME_FORCE` + `ADB_DENSITY`；
4. 与 Navicat 图**同尺寸并排**，量化指标（行数 / 行高 / chrome 高度）写进验收记录；
5. 暗色同步验收，不允许只改亮色。

---

## 23. 变更记录

| 日期 | 轮次 | 内容 |
|---|---|---|
| 2026-10-24 | **第二十二轮·S-7** | **品牌落点 + 死代码清理**（裁定＝**接 + 删**，§34.2 / §31.2）：**接** —— 新增矢量 `Icons.Icon.BRAND`（数据圆柱 + 右上 AI 四角星芒，16 视框描边，随主题换色），应用栏 26px 方块里那个「谁都在画的」通用 `DATABASE` 换成它，并给字形加 `#appbar-brand-glyph` 定位符；**删** —— 不可达的欢迎页整条路径清掉：`WorkspaceView.showWelcome()`（30 行）+ `brandMark()` + `BRAND_MARK` 常量 + 构造器那次调用 + `AppShell.tabClosed` 的 `else` 分支（`AppShell` 无条件 `openPermanent(object-home)`，那条分支永远走不到）+ CSS 四条 `.adb-welcome*` + `Icons.SIZE_BRAND`。⭐ **连带删令牌**：`fs-2xl`（20px）唯一的消费者就是那张卡片的标题 → `TokenParityTest.everyTokenIsActuallyUsed` 在全量构建里抓到它变摆设，按 §31.1 第 4 行的规矩**删令牌、不给令牌硬造消费者**：`tokens.json` 99→**98**（60 色 / **35** 尺寸 / 3 时长），`GenTokens` 重生成，`AppCssAuditTest.FONT_SIZES` 8 档→**7 档**，规格文档同步。门禁**净 +1**（删 `WelcomeBrandTest` 3 条 —— 它三条全绿却测着一张用户永远看不到的页，因为测试直接 `new WorkspaceView(...)` 绕过启动路径；补 `AppBarTest` 2 条：方块里 `SVGPath` 的 `getContent()` 必须**等于** `BRAND.path()` 且**不等于** `DATABASE.path()`、字形计算后的 `getStroke()` 必须是 `text-inverse` 而非 `text-secondary`，新 `BrandMarkAuditTest` 2 条守「死代码不许回来」与「品牌必须挂在用户看得见的地方」）。⭐⭐ **像素对比抓出一个真生产 bug**：品牌 mark 挂上去以后方块里的星芒**几乎看不见** —— 通用 `.adb-icon { -fx-stroke: -adb-text-secondary }` 排在 `app.css` **后半段**，和 `.adb-appbar__mark-glyph`（应用栏段）**同为单类选择器**，JavaFX 按「同特异性后写胜」把灰字色盖在白字上；换成两颗 `DATABASE` 圆柱时代就是这么灰着的，只是那时没人盯着这个位。修法是提到两类 `.adb-appbar__mark .adb-appbar__mark-glyph`（0,2,0），并补上那条读**计算值**的门禁；不变量写进 `UI-VISUAL-SPEC.md` §4.3 第 6 条。**5 个变异逐条红→改回绿**（应用栏换回 `DATABASE` → 两条门禁同时红；`showWelcome` / `.adb-welcome` / `SIZE_BRAND` 各自复活 → 审计红，审计脚本**剥掉注释再扫**，否则解释「为什么删」的注释会被自己判红；选择器改回单类 → **只有**对比度门禁红，证明它守的正是这个坑）。⭐ **三条脚手架教训**：① 变异脚本去掉 `-am` 会拿 `~/.m2` 里的旧 `adb-ui-core` jar → 编译失败、surefire 报告根本没生成，`exit=1` 长得像「红了」其实是**没测**，现在脚本显式判「报告不存在＝无效运行」；② 删样式后**必须跑全量**，`TokenParityTest` 在 `adb-ui-core`，单跑 UI 门禁看不见；③ **CSS 断言必须先把样式表挂上场景**（`AppBarTest` 原来只 `new Scene(bar)`，`getStroke()` 拿到的是 `Shape` 默认色），否则这类门禁要么红得莫名其妙、要么根本守不住东西 —— 而这条 bug 全绿了三轮才被像素对比抓到：**绿门禁 ≠ 真看得见**。基线 **754 条 / 97 类 / 0 失败 / 44 跳过**（净 +1），**12 张截图全部重拍**（对比度修复前的那一批已作废 —— mark 是灰的，拍出来也看不出问题）—— 应用栏常驻每一帧，品牌 mark 第一次**拍得到且看得清**（S-5 当年拍不到正是这事的起因） |
| 2026-10-24 | **第二十一轮·UX-4** | **网格交互**（§34.2）：选中行 = 强 tint + **左侧 2px `accent` 强调条**（`hover` 规则排在 `selected` 前，悬停已选行不被顶掉）；⭐ **槽位常留** —— 行 `-fx-border-width: 0 0 1 2`、格 `0 1 0 2` 由基础规则留透明空槽，选中条 / 脏值条 / 光标环 / 矩形选区**只上色不改宽度**（改宽度就抖：键盘走一格文字左右跳一次；行高 24px 上下各吃 2px 直接压掉 12px 正文）；单元格补**淡竖线**（同 `border-subtle`）、斑马纹 `bg-stripe` 再降一档（亮 `#F7F9FB→#FAFBFC`、暗 `.05→.035`，令牌值改动重生成 `Tokens.java` + 两套主题 CSS）；⭐ **当前格换成应用驱动**：`.table-cell:focused` 在行选模式下是**死选择器**（`TableCell.updateFocus()` 非 cell-selection 时主动 `setFocused(false)`，实测 `isFocused()=false`），改由 `bindFocusCursor()` 盯 `focusModel.focusedCellProperty()` 打样式类 `adb-grid__cell--cursor`（内描边 + `bg-active` 淡底，只有左右两条边），光标**按列名记不按 `TableColumn` 实例记**（冻结列是从右表搬过去的同一批实例，按下标比会标不上）；列头右键收齐排序升/降/清除、移到左/右/首/尾、筛选、隐藏、自适应、**冻结到此列 / 取消冻结**、复制列名，冻结算术走新 `dataColumnsOrdered()`（`dataColumns()` 只看得到 `grid`，冻结列在 `frozenGrid`）+ `isColumnFrozen()`，**做不到就禁用**（已冻列、右边剩不下两列的列，否则最后一列点冻结会偷偷少冻一列）；双击列头 = 自适应（与菜单项同一算法）；网格左下新增**行操作条** `+ / − / 应用 / 取消`（`#data-row-actions`，四个 `disableProperty` **bind** 工具条同一批、删除连 `visible/managed` 一起跟，只读表 `setManaged(false)` 整排消失）+ 新 `discardChanges()`（清缓冲 + 清撤销 + 重建 + 报数）；`NULL` 显示 **`(NULL)`**（灰斜体的 `NULL` 与内容为 "NULL" 的 TEXT 值分不开，两者处理方式完全不同）、抽屉文本页 NULL 说明对齐、`sizeLabel` 补 **GB 档**（不再 `1536.0 MB`）；长文本走**详情抽屉文本页就地编辑**（回填原始值、**Ctrl+Enter 应用**、**Esc 只退编辑不关抽屉**、写**同一个 `EditBuffer`**、主键列与只读表不出现编辑器，门禁走 `canEditTextCell`）。新门禁 **25 条 / 3 类**（`SelectionStateTest` 8 + `CellDisplayTest` 6 + `CellEditorTest` 4 + `GridInteractionTest` 7）逐条**变异红→改回绿**。⭐ 本轮第二个假绿：`primaryKeyAndReadOnlyTablesNeverOfferTheEditor` 删掉 `!definition.primaryKey()` 仍全绿 —— 演示库主键全是 INTEGER，`textual(type)` 先短路，规则根本没被检验 → 测试**自建 `codes (code TEXT PRIMARY KEY, note TEXT NOT NULL)`** 并补**正例**（可写文本列必须出现「编辑」，否则「编辑器永远不出现」这种改坏也能全绿）。另记两个脚手架坑：`WaitForAsyncUtils.waitFor` 放 `@Start` 会**阻塞 FX 线程导致死锁超时**（等加载要放 `@BeforeEach`）；变异脚本**不先删旧报告**会在「编译失败」时读到上一轮 green 报告。⚠️ **已知缺口**：选中态 / 光标环 / 抽屉编辑器是交互态，12 帧截图无一覆盖（靠 `SelectionStateTest` 算色与 x/y 断言兜底），补帧需给 `AppShell` 加 `ADB_CELL` 类启动开关，归 UX-5；门禁 **753 条 / 97 类 / 0 失败**（+25 / +3 类） |
| 2026-10-24 | **第二十一轮·UX-3** | **工具条与动作可发现性**（§34.2）：新增 `IconButtons.labeled(...)`（图标 + 文字 + tooltip + accessibleText 一次装配，参数收在 `ButtonBase` 上，`Button`/`MenuButton` 通吃）；数据页六颗主操作**由纯图标改为带文字**（保存 / 筛选与排序 / 列 / 视图 / 导入 / 导出），「删除记录」标 `adb-button--danger`，「清除条件」保持纯图标；三页工具条按语义分组并插 `.adb-toolbar__separator`（数据页 4 组 / 清单页 4 组 / 对象首页 2 组）；**响应式收字** `TOOLBAR_LABEL_MIN_WIDTH = 1100`，量**窗口宽度**，窄档 `setText("")` + `adb-button--icon`、**提示与 accessibleText 任何档位都不丢**；补数据页导入入口 `#data-import`（候选表把当前表挪第一位 = 向导预选；启用条件与导出**刻意相反** —— 空表才最需要导入）、清单页 `#table-list-transfer`（`TransferHub`）；对象首页四颗主操作从裸 Modena 按钮升级为 `adb-button` + 文字 + danger；**9 个变异逐条验证全红→改回绿**（保存空白 / 筛选空白 / 删 accessibleText / 去分隔线 / 导入不预选 / 删除不 danger / 清单不预选 / 去掉响应式 / 对象首页改纯图标）。⭐ **两个真问题**：①新门禁第一版是**假绿** —— `robot.lookup(...).tryQuery()` 对不可见节点返回 empty，`findById(null,…)` 一路 null 让每条断言被跳过，M1 改坏仍绿 → 改从 `.adb-grid-page` 手写场景图遍历 + 找不到即 `assertNotNull`（「宁可抛异常，也不放行」；Monocle headless 窗口有 960px 下限，尺寸断言改「是否跨过阈值」）；②修好门禁后红的是**生产代码** —— `updateEditControls()`/保存路径直接 `saveButton.setText(...)` 绕过登记表，窄档收字后一变脏「保存 3」自己长回来、搜索框被挤 → 统一走 `setToolbarLabel(...)`。「历史」推迟到 **P2-3**（`QueryHistory` 只存 SQL，无表名/时间戳）、「单元格编辑器」归 **UX-4**；门禁 **728 条 / 94 类 / 0 失败**（+8 = `ToolbarDiscoverabilityTest` 5 + `TableListPageTest` 2 + `ObjectHomeToolbarTest` 1；类 +1） |
| 2026-10-24 | **第二十一轮·UX-2** | **密度、排版与 chrome**（§34.2）：密度**三档 → 四档**，新增 `row-dense = 22`（令牌 **97 → 99**：`row-dense` + `sp-1h 6px`），**默认档位由 `normal` 改 `compact`**；`RowHeights.of` / `AppShell.applyDensity` / `SideBar.setDensity` / `DataGridPage.setDensity` 全部认四档
（**新门禁在开发过程中就抓到一次自伤**：改白名单时漏写 `normal`，「标准」被静默降级成 compact，`AppShellTest` 立刻红 —— 这正是四档白名单要显式列全的理由）；数据面正文统一 **12px**（网格 / 清单 / 对象树 / 结构页 / 按钮 / 弹窗按钮；`.root` 保留 13px 作界面基准）；chrome 收薄——标签栏 36→**28**、对象头 44→**36**、页工具条 44→**34**、分页器 44→**36**、状态栏 26→**24**、按钮/输入 32→**28**；dense 单元格内边距 `2 6`（`sp-1h`，间距「4 倍数」规则登记第二个例外）；新增 `ChromeMetricsTest`（框架实际高度 + **1268×765 可见 ≥22 行**）、`AppShellTest.densityDefaultsToCompactWhenNoPreferenceIsStored`、`AppCssAuditTest.dataSurfacesUseTheCompactBodySize`，`DensityCoverageTest` / `MenuBarFactoryTest` 扩到四档；**5 个变异全部验证**（字号 13 / 默认 comfort / 分页器 44 / dense 返回 24 / 菜单删「密集」均红，改回绿）；门禁 **720 条 / 93 类 / 0 失败** |
| 2026-10-24 | **第二十一轮·UX-1** | **视觉令牌基线**（§34.2）：令牌 **96 → 97**（新增 `bg-subtle` / `border-subtle` / `r-xs`，删除 `r-xl` / `shadow-2`）→ `GenTokens` 重生成；`app.css` 圆角只剩 **0/3/4/6/8/999**（控件 4、卡片 6、对话框 8、连接状态去胶囊）；卡片/详情/对话框投影删除，仅命令面板与 toast 用 `shadow-1`；网格线与分区底改 subtle 层；图标尺寸收敛 `14/16/20`（新增 `SIZE_PRIMARY`，对象工具条使用），`AppBar` 字面量 `16` 改常量；新增 `AppCssAuditTest.shadowsAreLimitedToTheFloatingElevation` + `IconSystemAuditTest.iconSizesComeFromTheNamedScale`，**4 个变异全部验证**（圆角 12 / 缺暗色 `bg-subtle` / 卡片加投影 / 图标写 18 均红，改回绿）；门禁 **716 条 / 92 类 / 0 失败** |
| 2026-10-24 | **第二十一轮·UX-0** | **截图与偏好可复现**（§34.2）：`SettingsStore` 支持 `ADB_PREFS_NODE`（独立 `Preferences.userRoot()` 节点）+ `ADB_PREFS_RESET`（仅显式给节点时才清空，避免误清真实用户偏好）；`AppShell` 的 `ADB_DENSITY` 优先于 prefs 且**强制值不写回**（原来每次启动光打开就写一次偏好）；`scripts/shots.sh` 默认钉住 `ADB_DENSITY=compact` / 独立 prefs 节点 / `ADB_PREFS_RESET=1` / `ADB_CONNECTION=demo`；**实测连跑两次 `p0-shell` SHA-256 完全一致**（`2ee03edb…f03e8`，50184 字节）；新增 `AppShellTest.forcedDensityWinsAndDoesNotWriteBackToPreferences` + 脚本审计断言，**两条均变异验证**（删 export → 红；去掉不写回守卫 → 红）；门禁 **714 条 / 92 类 / 0 失败** |
| 2026-10-24 | **第二十一轮·执行** | **删除 `declarations` 声明层，基线 755→712**：移除 `core/adb-declarations` 模块、根目录 `declarations/` YAML（4 连接 + 4 方言 + 5 传输家族 + Schema）、父 POM 的 module / dependencyManagement、`snakeyaml` / `json-schema-validator`、`adb-jdbc` 测试依赖与 `DeclaredDialectConsistencyTest`；全量门禁 **712 条 / 92 类 / 0 失败**（-43 = 模块 40 + 一致性门禁 3，属主动减面）；同步更新 README / ARCHITECTURE / COMPONENT-SELECTION / NEXT-STEPS / EXEC-SUMMARY / NAVICAT-GAP 的模块表与计数口径；顺带修正文档里「当前圆角 3/5/8」的失真（实际令牌为 4/6/8/12 + 999） |
| 2026-10-24 | **第二十一轮·规划** | **UIUX 升级 + P2/P5/P6 收尾裁定**（§34）：归档两张 Navicat 高保真实拍（数据网格 / 表清单），逐张量差 → 行高 22 vs 34、正文 12 vs 13、工具条图标+文字 vs 纯图标、单元格无竖线、选中态弱、按钮圆角 3–4 vs 6；新增 `docs/UI-ROADMAP.md` §12（UX-0…UX-6）与 `docs/NAVICAT-COMPARE.md` §9；裁定 P2/P5/P6 全做、`declarations` 删除（-43 条测试，基线 755→712）、C4 密度做（`dense 22`，默认 `compact`）、Windows 凭据用 JNA、xlsx 用 POI；**S-7 欢迎页仍待三选一**；本轮只改文档与参考图，未改代码 |
| 2026-10-24 | **第二十轮** | **⭐ Windows 实机复跑：5 个真问题**（§33）—— **S-8 门禁假绿（最严重）**：5 个静态审计用 `/` 过滤路径，Windows 的 `Path.toString()` 是 `\` → 方言审计把全部合法文件报成违规，另外 4 个**一个文件都没扫却显示绿** → 统一 `.replace('\\','/')` + **「扫到 0 个文件必须红」的下限断言**（`scanned>100` / `>50` / `hasSizeGreaterThan(30)`）· **S-9 对象首页工具条**：`ObjectHomePage.Host`（新建/设计/删表/详情联动）+ 4 个矢量图标按钮，启用态按选择类型（打开=单选任意、设计=单选表/视图、删除=多选且全是表、未连接全灰）· **S-6 可读列宽**：三张表统一 `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` + 新纯助手 `ColumnWidths`（CJK 算 2 单位、列头装饰 `[PK]`/`•`/`· 自增` 计入下限、96–260px）；**先用探针测出约束策略本来就尊重 minWidth**（宽屏填满 / 窄屏卡住下限 + 横向滚动），因此没有选 `UNCONSTRAINED`（实拍发现宽屏会留一大片空白）· **D-1**：SQLite `INTEGER PRIMARY KEY` 是 rowid 别名 → 非空；`TEXT PRIMARY KEY` 仍如实可空（两向断言）· **Q-1** `QueryPageTest` 平台感知快捷键（测试错、功能对）· **Q-2** 拖拽用例改拖前两列（末列在窄视口外是预期行为）· 顺带 A20 删 `MAX_CELL_BYTES`、连接档案路径回显 · **5 个变异全部验证**（M1 去掉选择监听 → 3 红；M2 列宽下限改 60 → 3 红；M3 去掉 rowid 判定 → 1 红；M4 去掉路径归一化 → 1 红；M5 策略换回 UNCONSTRAINED → 2 红）· 门禁 **755 条 / 95 类 / 0 失败**（+10：S-6 3 + S-9 4 + D-1 1 + `ColumnResizePolicyTest` 2；类 +2） |
| 2026-10-23 | **第十九轮·批次 D** | **⭐ S-4 列网格键盘导航**（§32）：新建表弹窗的 7 列网格以前只有鼠标能填 —— 方向键只换行不选列、Tab 直接跳出整张表格（7 列里 4 列要手填，等于强制用鼠标）· `NewTableDialog` 抽出四个静态纯方法 `move` / `step` / `next` / `previous` + record `CellPos`（**给坐标返回坐标**，headless 下不靠 `fireEvent` —— §28.10 的教训），`installGridNavigation` 挂到表上：方向键一格一格走、Tab 按阅读顺序走右一格换行首且**落到新格进编辑态** · 两个刻意的选择：**出界返回 null 而不是夹坐标**（走到头就该停住，绕圈会让用户迷路）、**最后一格不吃 Tab**（能进去却出不来是「键盘可达」的反面）· `step` 只认四个方向键（空格/回车留给 §28.10 的 `BooleanCell`，Tab 走 `next/previous`）· `NewTableDialogTest` **17→23**（+6），**4 个变异全部验证**（D1 不挂处理器 / D2 最后一格绕回 / D3 `step` 抢 Tab / D4 出界夹坐标，失败原文逐条记录）· 额外一条 `gridNavigationIsActuallyWiredToTheColumnsTable` 守「确实挂上了处理器」（纯方法全绿但忘了 `setOnKeyPressed` 是最容易溜过去的假绿）· 门禁 **745 条 / 93 类 / 0 失败** |
| 2026-10-23 | **第十九轮·批次 C** | **⭐ S-5 品牌闭环**（§31）：欢迎页 logo 从通用矢量圆柱换成**品牌 PNG**（`app-icon-128.png` @ `Icons.SIZE_BRAND`），**缺图抛异常而不是静默换回通用图标**（第十八轮那五个「失败被设计成静默降级」的坑，同名）· **连带**：`fs-4xl`（40px）唯一的消费者就是那条 `-fx-font-size` → 令牌变摆设 → `TokenParityTest` 红 → 按项目规则**删令牌**：tokens.json **97→96**，并重生成 `Tokens.java` + 同步 `AppCssAuditTest`（字号 **9→8 档**）与四份规格文档 · 新门禁 `WelcomeBrandTest`(3)，**4 个变异全部验证**（C1 换回通用图标 / C2 关等比 / C3 指非图片 / C4 **坏 PNG** —— 这档是特意补的：文件在但坏了时 JavaFX 不报错，界面只是一块空白）· ⭐ **本轮真正的发现**：**欢迎页是一条不可达路径**（`AppShell:246` 无条件 `openPermanent(object-home)` ⇒ `showWelcome()` 永远被盖掉）→ S-5 的改动**用户暂时看不到**，`p0-shell` 也拍不到它 → 记成 **S-7 待裁定**（接 / 删 / 留三选项写在 §29.2 末），本轮不越权改应用栏（那会推翻 `AppBarTest` 守着的「品牌标识必须矢量」既有决定）· `p0-shell.png` 已重拍，图上能证实的是 S-3（六对象节点每个都带 `▶`） |
| 2026-10-23 | **第十九轮·批次 B** | **⭐ S-3 对象树表节点可展开出列**（§30）：表 / 视图节点接上「读列回调」后挂占位子节点（JavaFX 只在有子节点时才画箭头），**展开才读列**（懒加载 + 缓存去重，几千张表的库不会一上来就查爆）· `AppShell.loadColumnsFor` 走数据库执行器 + `Platform.runLater` 回填，`setColumns` **就地换节点不重建整棵树**（不丢展开态 / 选中态）· **重新内省后作废列缓存**（表刚被 ALTER 过，旧列比没有列更糟）· **空清单 = 读失败**，换成「列读取失败（展开重试）」并摘掉「已尝试」标记以便重试，不静默降级成叶子 · 列不是对象：右键只留「复制名称」，打开数据 / 结构 / 复制 SELECT / 收藏一律灰掉，且不进对象计数 · **顺带修 `AppShellTest.openTable` 两个隐患**：`contains("users")` 会同时命中 `v_active_users` / `trg_users_audit`（命中触发器时压根不开页面，报错却像分页 bug）→ 改按 `name=xxx,` 整字段 + 只认表/视图；双击后立刻断言标题会偶发读到**上一个标签** → 改成等标题真的变成目标名字（超时抛 `AssertionError` 说清双击了谁）· **5 条新门禁全部做过变异验证**（MUTATION-1…5 各红一条、失败原文逐条记录，改回必绿）· 门禁 **736 条 / 92 类 / 0 失败**（`adb-ui-shell` 299→**304** +5；44 跳过为环境门控 —— 本机 Docker 不可用）· ⚠️ 本机默认 JDK 是 11，**JDK 25 只有 IntelliJ 自带那份**（`JAVA_HOME=/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`） |
| 2026-10-23 | **第十九轮** | **⭐ §29 批次 A：清单类页面「没有数据」不再渲染成标点**（S-1 方言感知列 + S-2 卡片去破折号，同一根因）：新增共享纯助手 `MetadataColumns`（`isPlaceholder` / `hasAnyValue`）——**判定「看数据不看方言」**，只有**每一行都渲染成占位符**时整列隐藏，有一行有真值就留着（混合场景：MySQL 表有引擎、视图没有 → 引擎列保留），核心列「名称/行/类型」永不隐藏；判定基于**整批已加载 `rows`**（不是 `filtered`），故搜索不会让列凭空出现/消失 · `TableListPage` 6 个可选列留引用 + `applyColumnVisibility()`，`ObjectHomePage` 同规则复用（无需给页面塞 `Dialect`，避免动构造器与一堆测试）· **S-2**：新 `cardLine(...)` 只拼非占位符片段，整行全占位符则**不渲染这一行**（并修掉「`rowsText()` 为「—」会拼成「— 行」就不算占位符了」的坑）· **14 条新门禁全部做过变异验证**（MUTATION-1 强制 `hasAnyValue` 恒真 → 6 门禁红；MUTATION-2 让 `cardLine` 全渲染 → 卡片门禁红；MUTATION-3 空转 `ObjectHomePage.applyColumnVisibility` → 该页门禁红，失败原文逐条记录）· **4 张截图重拍并逐张核对**：表清单 8→**2 列**、对象首页 9→**3 列**、卡片只剩「图标+名字+N 行」· 门禁 **731 条 / 92 类 / 0 失败**（`adb-ui-shell` 285→**299** +14 = `MetadataColumnsTest`6 + `TableListPageTest`6 + `ObjectHomePageTest`2；测试类 91→**92** +1 = `MetadataColumnsTest`；44 跳过全为环境门控） |
| 2026-10-23 | **第十八轮** | **⭐ 把截图当证据的第二轮 Navicat 对标**（§28.11）+ **合并待办 §29**：12 张实拍 + 4 张 Navicat 参考**逐张并排核对**（不再「对着规格想」），只记录图上能直接证伪的东西 → **6 个真缺陷**：①`p5-connections` 拍的是对象总览（`shots.sh` 点了个已被重构删掉的 id，`ScreenshotSession` 找不到节点**只打 stderr 就照常截图**）②「每页」下拉**永远空框**（`select(int index)` vs `select(Integer)` 重载决议选错，`javap -c` 实锤，**既有测试还写了绕过它的断言**）+ **返工续集**：修完选中后截图放大看仍是「2…」（逐像素量出右侧还剩 23px 空白，是 ComboBox 按钮单元格吃宽度）→ `prefWidth` 78→**96**，两门禁各补 `prefWidth>=90` 渲染底线③只读 SQL 预览教用户「⌘↵ 执行」④`App` 要的 `app-icon-512.png` **从未生成**（`if (url != null)` 静默跳过）⑤状态栏悬空「— ms」⑥查询页跳页框「三无」空白框 · **五个同根：失败被设计成静默降级** → 新增**两个静态审计门禁**（`ScreenshotScriptAuditTest` 校验脚本点击目标 / `AppIconResourceAuditTest` 校验图标资源名）· **7 条新门禁全部做过变异验证**（改坏必红、改回必绿，失败原文逐条记录）· **逐像素实测**：AIDB 网格 **28px** vs Navicat **19px**（侧栏树 24 vs 22 持平）· 勘误：§28.6 的 §6.1/§6.2/§13.3 是**悬空引用**（grep 全 `docs/` 无对应小节）→ 改按条目名记录；§21.1 的 **X3 早已完成**（第十轮 V-3）· 门禁 **717 条 / 91 类 / 0 失败**（`adb-ui-shell` 278→**285** +7 逐条对得上；44 跳过全为环境门控） |
| 2026-10-23 | 第十七轮·续四 | **规格 §13.3 新建表布尔列键盘可达**（§28.10）：`CheckBoxTableCell` 复选框 `focusTraversable=false` 只认鼠标 → 新增包级 `BooleanCell`（焦点留单元格 / 复选框只作图形）· 可测逻辑抽成纯方法 `isToggleKey` / `toggle(ColumnRow)` / `handleKey`（headless 下不靠 `fireEvent`，门禁直接驱动生产逻辑）· 新样式 `.adb-table__bool-cell`·`__bool-box`（令牌无硬编码色）· `NewTableDialogTest` +4；门禁 **278 条 / 0 失败**（全 14 模块 SUCCESS） |
| 2026-10-23 | 第十七轮·续三 | **AIDB 品牌改造 + Logo 落地**（§28.9）：`brand/aidb-mark.svg` + `aidb-logo-horizontal.svg`（数据库三层圆柱 + AI 四角星芒）· `tools/GenLogo.java`（Java2D 4× 超采样，产出 16–1024 七档 PNG）· `App.java` 注册 6 张多尺寸图标 · 用户可见文案全量改 **AIDB**（窗口标题 / 应用栏字标 / 欢迎页 / `退出`·`关于` 菜单 / 崩溃日志 / SQL 导出头 / secret-tool label / jpackage / 14 个 pom `<name>`）· **包名 `io.agenticdb` 与 groupId 刻意不改**（写进 `brand/README.md`）· `AppBarTest` 断言同步；门禁 **721 条 / 89 类 / 0 失败** |
| 2026-10-23 | 第十七轮 | **规格书 §6/§13/§9.7/§6.3/§11.2 五项落地**（§28）：新增 `ObjectDetailsPanel` 右侧对象详情面板（纯展示 + `Loader` 回调喂数据，宽 300 可拖 220–420，`details.toggle` ⌥⌘D，默认隐藏）· `NewTableDialog` 重构（宽 640→**900**，标题带真实库名/方言/关闭钮，SQL 预览 `TextArea`→**只读 `SqlEditor`**（等宽+行号+高亮）可折叠可复制，列网格高度自适应）· 数据页底部「记录/页面」常驻标签（两组同图标方向键不再靠猜）· **§6.3** 详情面板接表清单选中项（`inspectTable` 按值去重）· **§11.2** 结构页 DDL 页 `TextArea`→只读 `SqlEditor`（`SqlEditor.setHint`）· **门禁抓出 3 个真 bug**（§28.4：`facts` VBox 从未挂场景图 / 详情面板跨线程渲染违反 JavaFX 单线程规则 / PG 真库回报 `character varying` 断言写死 `varchar`）+ 1 处样式类回归 · 新门禁 `ObjectDetailsPanelTest`(14) + `NewTableDialogTest`(+6) + `AppShellTest`(+3) + `DataGridPageTest`(+1) + `TableListPageTest`(+2) + `StructurePageTest`(+1)；截图 9→**11 张**（新增 `p2-details`，`ADB_DETAILS=1`）；门禁 **721 条 / 89 类 / 0 失败**（另 29 跳过，其中 +5 条来自未提交的多版本矩阵 WIP，§28.7） |
| 2026-10-23 | 第十六轮 | **U-B7 侧栏 Navicat 模型 + 按钮规格统一**（§27）：侧栏去「工作台」分段、单棵「我的连接」树、**查询跟着连接走**（`queryTargetConnectionId()`）、★ 收藏改图标筛选 · **按钮规格统一**：`.adb-dialog .button` 对齐 `.adb-button`（6px/13px/**32px**/`-adb-border`）、`.adb-icon-button` 28→32px · **修 4 处门禁抓出的功能回归**（§27.4：查询历史不可达 / 折叠栏诊断按钮越界崩溃 / TRANSFER 死图标 / WorkspaceToolsPageTest 3 例）+ 4 处样式类漂移；**Docker 解锁 + PG16/MySQL8.4 真库 11 例**（§26，抓出 `rows` 保留字 / `pg_class.relcollation` 两个跨方言 bug）；门禁 **691 条 / 87 类 / 0 失败**（比上轮 +16 全部来自容器契约真正执行） |
| 2026-10-22 | 第十五轮·续二 | **G1 ✅ 冻结列**（§25.5，**U-B6 功能补齐层收官**）：左表 `frozenGrid` + 右表 `grid` 并排，移动**同一批 `TableColumn` 实例**（列宽/可见性/cellFactory 不丢，`applyFrozenColumns` 幂等）· 垂直滚动条双向绑定（等 skin 就绪，headless 安静跳过）· 选择双向同步 + 防循环标志 · **排序无需同步**（服务端排序，两边列头触发同一次重查）· 两表共享 items、同设 `setFixedCellSize` · 视图菜单「冻结左侧列」1/2/3 档 + 取消 · **N=0 时布局与旧版完全等价** · 新门禁 `DataGridPageTest.frozenColumnsStayPut`(1)；门禁 **675 条 / 87 类 / 0 失败** |
| 2026-10-22 | 第十五轮·续 | **G4 ✅ 单元格区域选择**（§25.4）：`GridRectangle` + `GridCell` 锚点（列下标按**可见数据列**算）· 按下定锚点 / 拖动扩展 / **Shift + 点击扩展** · 矩形内 cell 高亮 `adb-grid__cell--rect`（accent 淡底 + 描边） · ⌘C 有矩形时复制这块区域为 TSV（**不含表头**），无矩形仍复制单元格（旧行为不回归） · Esc 取消 · 翻页自动清除（行下标会指向新页的另一批行） · **关键取舍：不开 `cellSelectionEnabled`**（会让 `getSelectedItems()` 出重复行，破坏批量删除/设 NULL/导出），矩形是独立于行选择的模型 · 新门禁 `DataGridPageTest.rectangleSelectionCopies`(1)；门禁 **675 条 / 87 类 / 0 失败** |
| 2026-10-22 | 第十五轮 | **Q5 ✅ 结果分页**（§25）：新增 `QueryPaginator`（`isPaginable` / `pageSql` / `countSql`，不分方言，`SELECT * FROM (<原句>) AS _p LIMIT ? OFFSET ?` + `COUNT(*)` 套同一层）· `QueryPage.PagedLoader` 翻页条（首/上/下/末 + 页码 + 每页下拉 + 跳页 + 绝对行号 + `MAX_OFFSET` 约束）· **V-9 ✅ 表格规格统一**：列类型条 `adb-query__result-types`（JavaFX `TableColumn` 无 `setTooltip`）+ 翻页条复用 `adb-record-nav`/`adb-page-size` · 修三个问题（`isPaginable` 不认末尾分号致分页路径永不生效 / `pageSql` offset 装箱类型不一致 / 重复列断言硬编码 `n_2` 而 SQLite 驱动命名 `n:1`）· 补 2 条 CSS 规则（被 `StyleClassCoverageTest` 抓出）· 新门禁 `QueryPaginatorTest`(8) + `QueryPageTest`(+3)；门禁 **675 条 / 87 类 / 0 失败** |
| 2026-10-22 | 第十四轮 | **U1 ✅ catalog 树**（§24）：`Dialect.switchesCatalogByReconnect()` + `catalogsSql()` · `SchemaService.listCatalogs`（过滤系统库）· `ConnectionProfile.catalogVariant`（虚拟连接 `父id@库名`，不落盘、凭据走父连接）· 侧栏 catalog 层（懒加载 / 当前库加粗 / 双击或右键切库）· `TableDdl` 全部入口按连接 id 分派（修掉「DDL 落到当前库」）· 修两个真 bug（库节点被当空分组摘掉 / 选中未开连接的 catalog 竞态）· 新门禁 `CatalogTreeTest`(5) + `CatalogVariantProfileTest`(5) + `SideBarTreeTest`(+5) + `AppShellTest`(+1)；门禁 **662 条 / 86 类 / 0 失败** |
| 2026-10-22 | 第十三轮 | **Navicat 表清单/观感对齐**：`TableMeta` 8 字段（+修改时间/排序规则/注释）· 表清单 8 列 · 对象首页 9 列 · 密度 **24/28/34** · 应用栏 46 / 标签栏 36 / 工具栏 38 · `bg-sidebar` 灰底 · 去斑马纹/空行假线 · 表头 12px · `ADB_TABLES` + 9 张截图 · 等比例并排图 · 新增 **`docs/NAVICAT-PARITY-2026-10-V5.md`** |
| 2026-10-22 | 第九轮 | 新增 `docs/EXEC-SUMMARY.md`、`docs/GITHUB-COMPARE.md`；`NAVICAT-TODO.md` 状态基线（512/64）、§13.5 U-B5 漂移纠正、§16.4 下一步补齐；`README.md` / `docs/NEXT-STEPS.md` / 记忆同步 |
| 2026-10-22 | 第十轮 | 新增 **`docs/UI-VISUAL-SPEC.md`**（UI 规格书）；修 `row-tree` / `kind-index` 两个摆设令牌 + `.adb-error` 缺样式；实测发现 **43 个未定义样式类**；`NAVICAT-TODO.md` 新增 §18（V-1…V-12 + S-1…S-7）；门禁 **585 条 / 77 类 / 0 失败** |
| 2026-10-22 | 第十二轮 | **U2 ✅ 多连接并存**（`SessionManager.open` 改为复用会话 + `reopen`）· **A10 ✅ 语句超时**（30s + 人话提示）· **A12 ✅ 连接池归还** · **A18/A22 ✅ 跨平台主题探测 + 缓存** · **O1 ✅ MySQL 事件**（对象树 + 定义体）· **X5 ✅ ⌘K 能搜到对象动作 + 顶级「对象」菜单** · **X6 ✅ 清空收藏** · **X7 ✅ 恢复后落回对象首页** · **U6 ✅ 状态栏服务器信息** · **V-11 ✅ 弹窗宽度三档** · **V-12 ✅ 错误归类**（`ErrorClassifier`，5 处 `readable()` 统一）；门禁 **585 条 / 77 类 / 0 失败**；并新增 **§21 剩余清单**（如实列出未做项与理由） |
| 2026-10-22 | 第十一轮·续二 | **O2 ✅ 函数/过程定义体**：`Dialect.readRoutineDefinition`（SQLite/PG/MySQL 三种来源）+ `RoutinePage` **只读标签页** + 复制按钮 + 对象树入口；新门禁 `RoutineDefinitionTest`(3) + MySQL 真库函数定义体契约(1) |
| 2026-10-22 | 第十一轮·续 | **B5-S4 ✅ 表注释**：读（`tableCommentQuery` + `SchemaService`）· 写/清空（`setTableComment` + `clearTableCommentSql`）· 结构页展示 + 弹窗编辑（SQLite 禁用）· 新门禁 `TableCommentDdlTest`(5) + MySQL 真库往返契约(1) |
| 2026-10-22 | 第十一轮 | **D3 ✅**（CHECK：模型+解析器+方言+内省+重建搬运+结构页「检查」页签）· **B5-S2 ✅**（外键可增删，弹窗）· **B5-S3 ✅**（触发器可增删，弹窗）· ⭐ 修掉**带外键的表改列必失败**的潜伏 bug（动作码没翻译，翻译收进方言层）· 新门禁 `CheckConstraintTest`(7) + `ForeignKeyTriggerDdlTest`(11) |
| 2026-10-22 | 第十轮·收尾 | **S-1…S-6 全部补齐**（43 → 0，白名单清空）；**V-1/V-2/V-3/V-4/V-7/V-8/V-10 完成**；新门禁 `ContrastTest` / `StatusBarTest` / `EmptyStateTest`（+10 条 / +3 类）；对比度不足的 **6 个色值**已调；门禁 **585 条 / 77 类 / 0 失败** |

---
