# AgenticDB

跨平台数据库管理桌面客户端。**Java 25 + JavaFX 25 原生桌面**，不内嵌 WebView，不启动 HTTP 端口。

目标是 Navicat 级的交互顺手程度：对象树 → 双击表 → 数据页 → 编辑/保存，全程键盘可达。

---

## 当前状态（2026-10-20）

**P0 外壳 + P1 竖线 + P2 数据页 + P3 查询页 + P4 结构页已完成。P5 连接管理进行中。下一阶段是 P4.5 契约修复。**

| 能力 | 状态 | 证据 |
|---|---|---|
| 六区布局（菜单/工具栏/侧栏/标签栏/内容区/状态栏） | ✅ | `AppShell` |
| 原生窗口装饰 + macOS 系统菜单栏 | ✅ | `StageStyle.DECORATED` + `MenuBar.setUseSystemMenuBar(true)` |
| 统一动作注册表（菜单/工具栏/右键/命令面板） | ✅ | `ActionRegistry` |
| 侧栏 240px / 折叠 48px / 拖动改宽 | ✅ | `SideBar` + `AppShell` |
| 真实 SQLite 对象树（表/视图） | ✅ | `SchemaService` + `SideBar` |
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
| 执行计划、错误定位、查询历史（最近 50 条） | ✅ | `QueryPage` / `ErrorLocator` / `QueryHistory` |
| 查询结果只读表格、复制 TSV/SQL、CSV 导出 | ✅ | `CellRenderer` / `CsvExporter` |
| 结构页五页签：列 / 索引 / 外键 / 触发器 / DDL | ✅ | `StructurePage` + `TableStructure` |
| 结构安全编辑：加列 / 改列 / 重命名列 / 删列 + 两级危险确认 | ✅ | `StructureSqlBuilder` + `StructurePlan` |
| 结构变更单事务 + 失败回滚 + 全页面联动刷新 | ✅ | `StructurePage.applyPlan` |
| 连接管理页：列表 / 搜索 / 收藏 / 打开 / 编辑 / 复制 / 删除 | ✅ | `ConnectionsPage` |
| 三步连接向导：类型与名称 → 参数 → 测试与保存 | ✅ | `ConnectionWizard` |
| 凭据存系统钥匙串 / Secret Service（不落盘） | ✅ | `KeychainCredentialStore` / `SecretToolCredentialStore` |
| 连接档案落盘 `~/.agenticdb/connections.json`（0600） | ✅ | `ConnectionStore` |
| HikariCP 连接池（PG/MySQL），SQLite 直连 | ✅ | `ConnectionPool` |
| TestFX 冒烟 + 数据页/编辑/查询页/结构页/连接页门禁 | ✅ | `AppShellTest` / `DataGridPageTest` / `QueryPageTest` / `StructurePageTest` / `ConnectionsPageTest` / `ConnectionWizardTest` |

当前门禁：**30 个测试类、246 条测试，0 失败**。统一命令：`./mvnw -B clean verify`。

演示库首次启动自动生成：4 张表（`users` 200 行、`orders` 500 行、`products` 80 行、`notes` 空表）+ 1 个视图。
已有有效演示库不会被覆盖；`-Dadb.demo.db=/path/to/file.db` 可指定其他文件。

真实窗口截图：`docs/screenshots/p0-shell.png`、`docs/screenshots/p2-users.png`、`docs/screenshots/p3-query.png`。

---

## 快速开始

要求：JDK 25、Maven 3.9+（或直接用仓库自带 `./mvnw`）。

```bash
# 1. 编译 + 跑全部测试
./mvnw -B clean verify

# 2. 安装模块到本地仓库（javafx:run 需要）
./mvnw -B -DskipTests install

# 3. 启动桌面端
./mvnw -pl bootstrap/adb-desktop javafx:run

# 4. 验收时直接打开某张表
ADB_OPEN=users ./mvnw -pl bootstrap/adb-desktop javafx:run

# 5. 验收查询页：打开示例 SQL 并自动执行
ADB_QUERY=1 ./mvnw -pl bootstrap/adb-desktop javafx:run
```

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
| **`NAVICAT-TODO.md`** | ⭐ **唯一执行清单**：当前状态、P0–P4 完成项、P4.5 契约修复、P5–P7 路线与门禁 |
| **`docs/NAVICAT-GAP.md`** | ⭐ **对标分析**：与 Navicat 逐域对比，列出功能/交互/按钮/弹窗/图标/架构的差距与优先级 |
| `docs/ARCHITECTURE.md` | 模块、数据流、线程模型、编辑/SQL/撤销不变量、结构页与连接层模型 |
| `docs/UI-GUIDE.md` | 视觉基线、六区、组件与交互规则、令牌生成、图标约定 |
| `docs/COMPONENT-SELECTION.md` | 开源组件选型、版本、许可证、禁入清单 |

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
  adb-jdbc           统一 JDBC 会话、方言、类型映射
  adb-introspect     对象列表、列定义、主键、行数、分页/筛选/排序 SQL、结构内省与结构变更 SQL
  adb-session        连接档案、凭据存储、连接池、会话生命周期
  adb-editing        编辑缓冲、撤销/重做、SQL 计划、反向撤销

ui/
  adb-ui-core        设计令牌（tokens.json → Tokens.java + theme CSS）
  adb-ui-shell       JavaFX 六区外壳、菜单、命令面板、数据页、查询页、结构页、连接页

testing/
  adb-fixtures       演示库与测试夹具（固定种子）

bootstrap/
  adb-desktop        唯一启动入口；后续 jlink/jpackage
```

---

## 工程约束

1. **设计令牌唯一真相**：`ui/adb-ui-core/src/main/resources/tokens/tokens.json`（120 个，60 个颜色）。
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

- 保存反向撤销对“无主键/主键为空的新增行”不完整；UI 会明确提示。
- 反向撤销只覆盖最近一次保存；发生新编辑后不再提供。
- 转置视图只读，不参与批量选择。
- 本页搜索/隐藏只作用于当前页，不改变服务端分页。
- 查询结果超过 1000 行会被静默截断（正在补提示，见 `NAVICAT-TODO.md` 4.5-2）。
- 数据页加载 / 保存无法真正取消 JDBC 语句（`⌘.` 只作用于查询页，见 4.5-13）。
- 结构页只能改列；索引、外键、触发器只读；不能建表 / 改表名。
- 导入 / 导出向导尚未实现，侧栏入口目前是空壳（P6）。
- 诊断、查询历史、已保存查询三个侧栏入口目前是空壳。
- PostgreSQL / MySQL 尚未真实联调（无 Testcontainers），PG 的 DEFAULT / COMMENT 不内省。
- `core/adb-declarations` 声明层当前是死代码，待 P5 决定接入或删除。
- 主题“跟随系统”目前只探测 macOS；Windows/Linux 默认亮色。
- 令牌体系里 79/120 个令牌未被 CSS 引用；密度档位只影响对象树。
- 首版不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务。
