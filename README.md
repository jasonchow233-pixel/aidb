# AIDB (AgenticDB)

> 跨平台数据库管理桌面客户端 · **AI 原生数据库工作台**
> **Java 25 + JavaFX 25 原生桌面**，不内嵌 WebView，不启动 HTTP 端口。

![AIDB logo](ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app-icon-128.png)

目标是 Navicat 级的交互顺手程度：对象树 → 双击表 → 数据页 → 编辑/保存，全程键盘可达。

---

## 当前状态（2026-10-22）

**P0–P4 已完成、P5/P6 进行中、界面专项 U-B1–U-B7 已落地。**
门禁 **739 条 / 93 类 / 0 失败**（2026-10-23 · 第十九轮批次 C 实测；另有 44 条环境门控跳过 —— 需 Docker / MySQL 真库环境变量）。

> **第八轮之后**：D2+D4（MySQL/PG 列注释读回）已修；B5-S1（索引可编辑）已落地；
> **T1–T4（建表 / 清空 / 重命名 / 删表）已落地**（T1=弹窗，按形态裁定）；9 张验收截图完成。
> **第九轮**：新增对外汇报版 `docs/EXEC-SUMMARY.md` + GitHub 开源对标 `docs/GITHUB-COMPARE.md`。
>
> - ✅ **D3「SQLite 改列丢失 CHECK 约束」已修**（第十一轮）：CHECK 现在能内省、展示，
>   而且**改列整表重建时会搬运** —— 并且门禁断言「改列后约束**仍然拦得住违规数据**」。
> - ✅ **外键 / 触发器可增删**（第十一轮，B5-S2/S3）：结构页新增「外键」「检查」页签的增删入口 + 两个弹窗。
> - ⭐ 顺带修掉一个**潜伏很久的真 bug**：外键的 ON UPDATE/ON DELETE 动作码没翻译
>   （各驱动码表不同）→ **带外键的表改列必失败**（`ON UPDATE 3` 语法错误）。
> - 其余遗留：B5-S2/S3、B5-S4/O1/O2、U-B6、空白态 E5–E9、架构收尾 A10/A12/A15/A16。
>
> 完整清单见 `NAVICAT-TODO.md`；逐域对比见 `docs/NAVICAT-GAP.md` §7。

| 能力 | 状态 | 证据 |
|---|---|---|
| 五区布局（应用栏/标签栏/侧栏/内容区/状态栏） | ✅ | `AppShell` + `AppBar` |
| 原生窗口装饰 + macOS 系统菜单栏 | ✅ | `StageStyle.DECORATED` + `MenuBar.setUseSystemMenuBar(true)` |
| 统一动作注册表（菜单/工具栏/右键/命令面板） | ✅ | `ActionRegistry` |
| 侧栏 250px / 折叠 48px / 拖动改宽 200–360 | ✅ | `SideBar` + `AppShell` |
| 对象切面条（数据/结构/索引/外键/触发器/SQL，真实内省） | ✅ | `ObjectTabBar` + `ObjectSectionsPanel` |
| 对象标题栏（类型图标 / 名称 / 类型 / 真实行数 / 动作） | ✅ | `ObjectHeader` |
| 弹窗统一主题（`-adb-*` 令牌在 DialogPane 上真正生效） | ✅ | `DialogStyles` |
| 真实 SQLite 对象树（表/视图，**多连接同树**） | ✅ | `SchemaService` + `SideBar` |
| 对象树表节点可展开出列（**懒加载** / 失败可重试） | ✅ | `SideBar.objectNode` + `AppShell.loadColumnsFor` |
| 演示库（固定种子，可重建） | ✅ | `~/.agenticdb/agenticdb-demo.db`，`DemoDatabase` |
| 数据页：服务端分页、选择列、行号、类型行 | ✅ | `DataGridPage` |
| 底部分页条：100/200/500/1000 | ✅ | `DataGridPage` |
| 状态栏：连接、行范围、耗时、脏计数 | ✅ | `StatusBar` + `DataGridPage.Feedback` |
| 空态 / 加载 / 错误三态 | ✅ | 演示库 `notes` 空表 + 失败重试 |
| 标签独立数据（切表不串、分页位置保留） | ✅ | `WorkspaceView` 按 tab id 缓存 |
| 单列/最多 3 级排序，下推 `ORDER BY` | ✅ | `SortSpec` + `DataSqlBuilder` |
| 列头值筛选：候选值、计数、级联、NULL/空串、高基数降级 | ✅ | `ColumnFilterPanel` |
| 条件构造器 + 可删除 chip + 全部清除 | ✅ | `ConditionDialog` + `DataGridPage` |
| 列宽/顺序/显隐 + `java.util.prefs` 持久化 | ✅ | `DataGridPage` 列布局 |
| 行内编辑、类型转换、按主键定位 | ✅ | `ValueCell` + `RowIdentity` |
| NULL / 空串独立操作 | ✅ | 单元格右键 + 批量条 |
| `EditBuffer`、撤销/重做、脏标记、关闭确认 | ✅ | `adb-editing` |
| 导入导出向导（流式 CSV/TSV/JSON/SQL，字段映射，坏行报告） | ✅ | `adb-transfer` |
| 新增行 / 删除 / 克隆（草稿行、一次撤销） | ✅ | 淡绿新增、淡红删除、`●` 脏行 |
| 多选 + 批量条：TSV / INSERT / 克隆 / 设 NULL / 设空串 / 删除 | ✅ | 表头全选仅当前页 |
| SQL 预览 → 单事务保存 → 刷新 → Toast 反向撤销 | ✅ | `EditSqlBuilder` + `DataGridPage` |
| 单元格详情抽屉：文本 / JSON / Hex / 图片 / 元数据 | ✅ | `CellDetailsDrawer` |
| 本页搜索（⌘F）、隐藏 NULL/空串、只读转置 | ✅ | `DataGridPage` 视图菜单 |
| 当前页 CSV 导出 / TSV / INSERT 复制 | ✅ | `CsvExporter` + `DataGridPage` |
| 双主题（暗/亮，跟随系统，本地记忆）+ 密度档位 | ✅ | `ThemeManager` + `theme-*.css` |
| 命令面板 ⌘K | ✅ | `CommandPalette` |
| SQLite JDBC 会话（参数化查询/事务/类型映射/限流） | ✅ | `JdbcSession` |
| SQL 词法/分句/格式化内核 | ✅ | `adb-sql`：`SqlLexer` / `SqlSplitter` / `SqlFormatter` |
| RichTextFX SQL 编辑器：高亮、行号、当前行、查找替换 | ✅ | `SqlEditor` / `SqlHighlighter` / `QueryPage` |
| 查询页：`⌘R` 当前语句、`⇧⌘R` 全部、`⌘.` 取消 | ✅ | `QueryPage` + `AppShell` 动作 |
| 多语句结果页签：结果集 / 消息 / 错误 | ✅ | `QueryPage` |
| 查询结果服务端分页：翻页条 + 每页下拉 + 跳页 + 绝对行号 | ✅ | `QueryPaginator` + `QueryPage.PagedLoader` |
| 查询结果列类型条（与数据页同一套视觉规格） | ✅ | `adb-query__result-types` |
| 数据网格**单元格区域选择**：拖选 / Shift 扩展 / 复制矩形 | ✅ | `DataGridPage.selectRectangle` + `adb-grid__cell--rect` |
| 数据网格**冻结列**（左/右两表并排，滚动 + 选择同步） | ✅ | `DataGridPage.setFrozenColumnCount` |
| 执行计划、错误定位、查询历史（最近 50 条） | ✅ | `QueryPage` / `ErrorLocator` / `QueryHistory` |
| 查询结果只读表格、复制 TSV/SQL、CSV 导出 | ✅ | `CellRenderer` / `CsvExporter` |
| 结构页六页签：列 / 索引 / 外键 / 检查 / 触发器 / DDL | ✅ | `StructurePage` + `TableStructure` |
| **CHECK 约束**：内省 + 展示 + **整表重建时搬运** | ✅ | `CheckDef` / `CheckParser` / `CheckConstraintTest` |
| **外键增删**（SQLite 重建 / PG·MySQL ALTER） | ✅ | `ForeignKeyDialog` / `ForeignKeyTriggerDdlTest` |
| **触发器增删**（PG = 函数 + 触发器两条） | ✅ | `TriggerDialog` / `ForeignKeyTriggerDdlTest` |
| **表注释**：读得回 / 改得了 / 清得掉（SQLite 无 → 按钮禁用） | ✅ | `TableCommentDialog` / `TableCommentDdlTest` |
| **函数 / 过程定义体**（只读标签页，三方言来源不同） | ✅ | `RoutinePage` / `Dialect.readRoutineDefinition` / `RoutineDefinitionTest` |
| **MySQL 调度事件**（对象树「事件」分组 + 定义体） | ✅ | `ObjectKind.EVENT` / `Dialect.eventsListSql` |
| **多连接并存**（切连接不关掉另一个的会话） | ✅ | `SessionManager.open` 复用 / `reopen` |
| **语句超时**（30s，超时给建议）+ **连接池归还** | ✅ | `QueryLimits` / `JdbcSession` / `ConnectionPool` |
| **错误归类**（连接断开/权限/不存在/超时/锁/约束/语法） | ✅ | `ErrorClassifier` / `ErrorClassifierTest` |
| **代码片段库**（存选中的 SQL → 插入到光标处） | ✅ | `SavedQueries`（查询/片段两种类型） |
| **多连接并存**（每连接一条执行器，同连接串行、跨连接不互堵） | ✅ | `DbExecutors` / `SessionManager` |
| **连接建立移出 UI 线程**（连接慢不再冻窗口） | ✅ | `AppShell.connectProfile` |
| **崩溃日志**（`~/.agenticdb/logs/crash-*.log`） | ✅ | `CrashLog` |
| **一键出包**（jpackage：app-image / dmg / msi / deb） | ✅ | `scripts/package.sh` |
| **连接属性页**（弹窗里套 TabPane：常规/SSL/高级） | ✅ | `ConnectionPropertiesDialog` |
| 结构安全编辑：加列 / 改列 / 重命名列 / 删列 + 两级危险确认 | ✅ | `StructureSqlBuilder` + `StructurePlan` |
| 结构变更单事务 + 失败回滚 + 全页面联动刷新 | ✅ | `StructurePage.applyPlan` |
| 连接管理页：列表 / 搜索 / 收藏 / 打开 / 编辑 / 复制 / 删除 | ✅ | `ConnectionsPage` |
| 三步连接向导：类型与名称 → 参数 → 测试与保存 | ✅ | `ConnectionWizard` |
| 凭据存系统钥匙串 / Secret Service（不落盘） | ✅ | `KeychainCredentialStore` / `SecretToolCredentialStore` |
| 连接档案落盘 `~/.agenticdb/connections.json`（0600） | ✅ | `ConnectionStore` |
| HikariCP 连接池（PG/MySQL），SQLite 直连 | ✅ | `ConnectionPool` |
| TestFX 冒烟 + 数据页/编辑/查询页/结构页/连接页门禁 | ✅ | `AppShellTest` / `DataGridPageTest` / `QueryPageTest` / `StructurePageTest` / `ConnectionsPageTest` / `ConnectionWizardTest` |

当前门禁：**739 条 / 93 类 / 0 失败**（2026-10-23 · 第十九轮批次 C 实测；`./mvnw -B clean verify` + `java tools/TestCount.java`；另有 44 条环境门控跳过 —— Docker / MySQL 真库不可用时会跳过，**不影响 0 失败这条硬门禁**）。统一命令：`./mvnw -B clean verify`。

> ⚠️ **本机环境**：`java` 默认指向 **JDK 11**，门禁起不来。可用的 JDK 25 是 IntelliJ 自带那份：
> `export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"`。

统计口径用工具兜底（`@Nested` 的测试类在 surefire XML 里会写成 `tests="0"`）：

```bash
java tools/TestCount.java      # 按 <testcase> 计数，并断言总数 ≥ 300
```

> ⚠️ **统计口径**：4 个测试类用 `@Nested`，surefire XML 里写成 `tests="0"`（真实用例在内层类条目里）。
> 按 XML 的 `tests=` 属性统计只有 159，**少算 109 条**。CI 与覆盖率工具按 `<testcase>` 计数。

演示库首次启动自动生成：4 张表（`users` 200 行、`orders` 500 行、`products` 80 行、`notes` 空表）+ 1 个视图。
已有有效演示库不会被覆盖；`-Dadb.demo.db=/path/to/file.db` 可指定其他文件。

真实窗口截图：`docs/screenshots/p0-shell.png`、`p2-users.png`、`p3-query.png`、`p4-structure.png`、
`p5-connections.png`、`p5-dark.png`、`p6-tables.png`、`p6-new-table.png`、`p7-new-index.png`
（共 **9 张**；`docs/screenshots/navicat/` 另存 Navicat Premium Lite 15 实拍 3 张 + 等比例并排图：
`navicat-tablelist.png` / `navicat-toolbar.png` / `side-by-side-2026-10.png`），
由 `ScreenshotSession`（`ADB_SHOTS` 等环境变量）可复现生成，用法见 §12.2。
弹窗两张靠 `ADB_NEW_TABLE=1` / `ADB_INDEX=1` 启动开关（§16.3）。

---

## 快速开始

要求：JDK 25、Maven 3.9+（或直接用仓库自带 `./mvnw`）。

> ⭐ **用 `scripts/` 下的脚本，不要手工敲命令** —— 启动流程里有两个坑：
> JDK 版本不对时报错埋在日志深处；**不先 `install` 会跑 `~/.m2` 里的旧 jar**
> （实测踩过：改了半天 CSS 界面一点没变）。脚本把这两个都挡在前面了。

```bash
# 1. ⭐ 一键编译 + 启动（最常用）
./scripts/dev.sh

# 2. 启动并直接打开某张表（验收时用）
./scripts/dev.sh users

# 3. 完整门禁：编译 + 全部测试 + 用例计数
./scripts/build.sh

# 4. 重拍 docs/screenshots/ 的 9 张验收截图
./scripts/shots.sh

# 5. 出包（jpackage；产物在 bootstrap/adb-desktop/target/dist/，实测可启动）
./scripts/package.sh            # 当前平台默认类型
./scripts/package.sh app-image  # 只出免安装目录
```

### 手工命令（脚本的内部实现，供排查用）

```bash
# 编译 + 跑全部测试
./mvnw -B clean verify

# 安装模块到本地仓库（javafx:run 需要）
./mvnw -B -DskipTests install

# 启动桌面端
./mvnw -pl bootstrap/adb-desktop javafx:run

# 打开某张表 / 查询页 / 结构页
ADB_OPEN=users ./mvnw -pl bootstrap/adb-desktop javafx:run
ADB_QUERY=1    ./mvnw -pl bootstrap/adb-desktop javafx:run
ADB_STRUCTURE=users ./mvnw -pl bootstrap/adb-desktop javafx:run
```

> ⚠️ 两个脚本**不要并发跑** —— 都做 `clean install`，会互相删 jar。

只跑 UI 门禁：

```bash
./mvnw -B -pl ui/adb-ui-shell -am test
```

TestFX 默认用 Monocle 无头模式；在本机想看真实窗口，去掉父 POM 里
`testfx.headless` / `glass.platform` 两个系统属性即可。

---

## 文档地图

| 文档 | 回答什么问题 |
|---|---|
| **`NAVICAT-TODO.md`** | ⭐ **唯一执行清单**：当前状态、P0–P4 完成项、P4.5 契约修复、P4.6 网格/查询进阶、P5–P7 路线与门禁、**§14 第六轮全量核查（4 个缺陷 + 表级生命周期空白）** |
| **`docs/NAVICAT-GAP.md`** | ⭐ **对标分析**：与 Navicat 逐域对比，列出功能/交互/按钮/弹窗/图标/架构的差距与优先级；含**逐文件复核发现的 16 处文档失真** |
| `docs/ARCHITECTURE.md` | 模块、数据流、线程模型、编辑/SQL/撤销不变量、结构页与连接层模型 |
| `docs/UI-GUIDE.md` | 视觉基线、五区、组件与交互规则、令牌生成、图标约定、弹窗规范 |
| **`docs/UI-VISUAL-SPEC.md`** | ⭐ **可照着实现的 UI 规格书**：8 档字号落到哪个角色 · 96 个令牌语义 · 42 图标清单 · 间距/圆角/固定尺寸 · 五区与四种页面骨架 · 组件规格 · 弹窗骨架与按钮区规则 · 交互态矩阵 · 键盘表 · 文案措辞 |
| **`docs/NAVICAT-PARITY-2026-10-V5.md`** | ⭐ **Navicat 对齐报告 V5**（2026-10-22）：8 个维度逐像素对照 · 已落地 13 项改动 · 4 处刻意不追 · P0/P1/P2 剩余清单 · 等比例并排图说明 |
| **`docs/UI-COMPARISON-2026-10.md`** | 与 Navicat 的像素级对拍（**上一版**，并排图存在缩放不一致；最新结论以 `NAVICAT-PARITY-2026-10-V5.md` 为准） |
| **`docs/UI-ROADMAP.md`** | ⭐ **界面专项**：实测 11 个界面问题（字号 / 间距 / 圆角 / 焦点环 / 按钮层级 / 图标 / 密度 / 列头 / 连接列表）+ 6 个批次计划 + 排版 / 间距 / 圆角 / 配色 / 交互规范 |
| **`docs/NAVICAT-COMPARE.md`** | ⭐ **与 Navicat 的实拍对比**（本机装了 Navicat Premium Lite 15）：逐项对照 + 6 个真差距 + 由此重排的优先级 |
| **`docs/GITHUB-COMPARE.md`** | ⭐ **GitHub 上开源数据库管理软件**扫描（DBeaver / Chat2DB / Beekeeper Studio / TablePro / sqlpad / SQLTools…）：与 AIDB 的可比性、独特定位、借鉴项 |
| **`docs/EXEC-SUMMARY.md`** | ⭐ **对外汇报版**（2026-10-22）：架构 / Todolist / 截图 / Navicat / GitHub / 六维 / 还缺什么 / 本轮交付物 / 汇报要点 |
| **`docs/UI-AUDIT.md`** | ⭐ **截图实测审计（2026-10-21）**：4 张真实窗口截图逐项挑问题 + 代码定位 + 修法 + 验收标准；含**3 个已排除的误判**与分批执行计划 |
| **`docs/UI-FORMS-AND-STATES.md`** | ⭐ **形态与状态规范**：新能力该做**弹窗还是标签页**（含待建能力的裁定表，防「新建表做成标签页」这类错误）+ 默认态 8 项 + 空白态 9 处清单 + `EmptyState` 组件约定 |
| `docs/NEXT-STEPS.md` | 阶段规划：批次划分、依赖顺序、提交策略 |
| `docs/COMPONENT-SELECTION.md` | 开源组件选型、版本、许可证、禁入清单 |

> 📌 **对外汇报**：`docs/EXEC-SUMMARY.md`（一句话结论 + 六维全景 + 还缺什么）与
> `docs/GITHUB-COMPARE.md`（GitHub 开源数据库管理软件对照）于 2026-10-22 新增。

**脚本**（`scripts/`，2026-10-21 新增）：`dev.sh` 一键编译启动 ·
`build.sh` 门禁 · `shots.sh` 重拍截图 · `package.sh` **一键出包**（jpackage）·
`_env.sh` 公共环境预检。
用法见上文「快速开始」，设计取舍见 `NAVICAT-TODO.md` §14.7。

旧 Rust / Vue / Python 实现、构建产物和过时规划已删除；git 历史仍可追溯。

---

## 模块

```
core/
  adb-model          领域模型（ColumnDef / RowPage / Danger / QueryLimits）
  adb-declarations   声明层加载与 Schema 校验（⛔ 当前运行时未引用，见架构文档 §2.1）
  adb-safety         危险分级与错误定位
  adb-sql            SQL 词法、分句、格式化内核

workbench/
  adb-jdbc           统一 JDBC 会话、方言抽象层、类型映射
  adb-introspect     对象列表、列定义、主键、行数、分页/筛选/排序 SQL、结构内省与结构变更 SQL
  adb-session        连接档案、凭据存储、连接池、会话生命周期
  adb-editing        编辑缓冲、撤销/重做、SQL 计划、反向撤销
  adb-transfer       导入导出引擎：流式 CSV/TSV/JSON/SQL 读写、字段映射、坏行报告

ui/
  adb-ui-core        设计令牌（tokens.json → Tokens.java + theme CSS）
  adb-ui-shell       JavaFX 五区外壳、应用栏、命令面板、数据页、查询页、结构页、连接页

testing/
  adb-fixtures       演示库与测试夹具（固定种子）

bootstrap/
  adb-desktop        唯一启动入口；后续 jlink/jpackage
```

---

## 工程约束

1. **设计令牌唯一真相**：`ui/adb-ui-core/src/main/resources/tokens/tokens.json`（96 个：59 颜色 / 34 尺寸 / 3 时长）。
   改完运行 `java tools/GenTokens.java` 重新生成 `Tokens.java` 与 `theme-dark/light.css`。
2. **业务代码禁止颜色字面量**：只能引用 `-adb-*` looked-up colors。
   `TokenParityTest` 会扫描 `app.css`，出现 `#rrggbb` / `rgba(...)` 直接失败。
3. **动作唯一真相**：菜单、工具栏、右键、命令面板全部走 `ActionRegistry`；
   禁止在组件里各写一套 enabled 判断。
4. **不内嵌浏览器、不启动 HTTP 端口**；所有数据经 JDBC。
5. **所有 JDBC 调用串行**：共享单线程执行器 `adb-db`；UI 更新回 JavaFX 线程。
6. **编辑按主键定位**：无主键表/视图只读；保存前预览 SQL，单事务执行。
7. **每条“已完成”必须有门禁断言**；写不出断言就当没做。
8. **Dialog 按钮的坑**：`DialogPane.createButton` 会给按钮栏里的每个按钮挂
   `setResultAndClose` 处理器，自定义导航按钮放进按钮栏会被一键关掉。详见架构文档 §7.1。
9. 新增三方依赖先写进父 POM 的 `<dependencyManagement>`，子模块不带 `<version>`。

---

## 已知限制

- 危险分级**第三层（驱动侧复查）仍不存在**：`DbSession.execute(sql)` 没有 `danger`
  参数。前两层（fail-closed 分类 + UI 确认）已落地。→ §10 A6
- 设计令牌已收敛到 96 个且全部被用到；`TokenParityTest` 门禁守着不会再长回摆设
  （40px 字号档 `fs-4xl` 于 2026-10-23 移除 —— 唯一消费者是欢迎页 logo 的 `font-size`，
  S-5 换成品牌 PNG 后尺寸改由 `Icons.SIZE_BRAND` 在代码里定）。
- 保存反向撤销对“无主键/主键为空的新增行”不完整；UI 会明确提示。
- 反向撤销只覆盖最近一次保存；发生新编辑后不再提供。
- 转置视图只读，不参与批量选择。
- 本页搜索/隐藏只作用于当前页，不改变服务端分页。
- 数据页加载 / 保存无法真正取消 JDBC 语句（`⌘.` 只作用于查询页，见 4.5-13）。
- 连接建立与内省跑在 JavaFX 线程；全局单线程执行器会让一个连接的慢查询阻塞其它连接。
- ✅ **表级生命周期已落地**（T1–T4）：建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，
  入口为对象树右键、数据库菜单、⌘K；三种方言各走自己的语法
  （SQLite 没有 `TRUNCATE` 走 `DELETE FROM`；MySQL 用 `RENAME TABLE`）。→ §15.4d
  - 仍缺：建表后自动打开该表；视图不能增删改。
- ✅ **列注释已能读回**（D2/D4 已修）：改列不再抹掉注释；PG 也能清空注释。→ §16.1
- ✅ **索引可增删**（S1）：结构页「索引」页签右键 + 按钮，弹窗录入、实时 SQL 预览；
  数据库自动建的索引（`sqlite_autoindex_*` / MySQL `PRIMARY`）拒绝删除。→ §16.2
- ⚠️ **建表暂不支持外键 / 索引**（生成器支持，弹窗里还没有录入区）→ §14.5 S2/S3
- ✅ **CHECK 约束已能内省 / 展示 / 重建搬运**（第十一轮）；⚠️ 但**建表 UI 里还不能录入 CHECK**。
- ✅ **表注释可读可改**（第十一轮·续）；⚠️ SQLite 没有表注释（按钮禁用）。
- ✅ **函数 / 过程定义体可看**（第十一轮·续二，只读标签页）；⚠️ SQLite 没有 SQL 层函数。
- ⚠️ **点对象首页的「函数 / 过程 / 触发器」会被告知暂不支持**（D1 已修，不再报错，
  但还不能看定义体）。
- 结构页的索引已可编辑；**外键、触发器仍只读**（且没有右键菜单）。
- 表注释 / 外键 / 触发器 / CHECK / 函数·过程定义体均已支持；MySQL EVENT 已建模；
  剩余的是视图增删改（V1）与表清单工具条的新建/删除/设计入口（N1–N3）。
- 导入导出已实现（CSV/TSV/JSON/SQL 导出 + CSV/TSV 导入）；**xlsx 还没有**；导入暂按字符串绑列，类型不匹配的行会跳过并报告。
- 侧栏的查询历史 / 诊断 / 导入导出 / 已保存查询四个入口都已接真功能。
- **MySQL 5.7.44 真库已联调**（9 例）；**PostgreSQL 仍缺测试库**（未配置，跳过）。
- `core/adb-declarations` 声明层目前只做**声明校验 + 白名单门禁**，尚未驱动运行时。
- 主题“跟随系统”目前只探测 macOS；Windows/Linux 默认亮色。
- 令牌体系 96 个全部被用到；密度档位已覆盖数据网格 / 查询结果 / 对象清单 / 结构页。
- `docs/screenshots/` 的 9 张图已按当前代码重拍。
- 首版不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务。
