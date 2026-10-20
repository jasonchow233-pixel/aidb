# NAVICAT-TODO — Java 桌面端执行清单

> **唯一执行清单。** 记录当前状态、下一步、验收门禁。
> 架构见 `docs/ARCHITECTURE.md`；UI 规则见 `docs/UI-GUIDE.md`；
> 组件选型见 `docs/COMPONENT-SELECTION.md`；**Navicat 对标差距见 `docs/NAVICAT-GAP.md`**。
>
> 最后更新：2026-10-20
> 当前基线：**P0–P4 完成，P5 进行中**；下一阶段 **P4.5 契约修复**（详见 §6）。

---

## 0. 当前状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| P0 外壳 | ✅ 完成 | 六区、菜单、工具栏、侧栏、标签栏、状态栏、命令面板、双主题、密度档位 |
| P1 竖线 | ✅ 完成 | SQLite 会话、演示库、内省、对象树、分页数据页、三态、标签隔离 |
| P2 数据页 | ✅ 完成 | 排序/筛选/条件构造器、列布局、行内编辑、撤销重做、SQL 预览保存、批量操作、详情抽屉、搜索/转置/隐藏、CSV 导出、保存反向撤销 |
| P3 查询页 | ✅ 完成 | SQL 词法/分句/格式化、RichTextFX 编辑器、查找替换、多语句结果页签、取消、错误定位、执行计划、历史 |
| P4 结构页 | ✅ 完成 | 列/索引/外键/触发器/DDL 五页签、加列/改列/重命名列/删列、两级危险确认、单事务、结构变更联动刷新 |
| P5 连接管理 | 🟡 进行中 | 连接管理页、三步向导、测试连接、钥匙串 / Secret Service 凭据、连接档案落盘、HikariCP 连接池（PG/MySQL） |
| P6 导入导出 | ⬜ 计划 | CSV / JSON / Excel 向导、字段映射、预览 |
| P7 分发 | ⬜ 计划 | jlink / jpackage、自动更新、性能与可访问性收尾 |

首版明确不做：自绘标题栏、AI 面板、插件系统、ER 图、用户权限、计划任务、传输同步。

> **提交状态提醒**：P4 结构页与 P5 连接管理的代码目前只在工作区，尚未提交。
> 提交前先跑 `./mvnw -B clean verify`。

---

## 1. 门禁基线

统一命令：

```bash
./mvnw -B clean verify
```

当前结果：**30 个测试类 / 246 条测试，0 失败**。

| 模块 | 测试数 | 覆盖 |
|---|---|---|
| `core/adb-model` | 32 | 分页/行数上限契约 |
| `core/adb-declarations` | 39 | 声明校验、快照一致性 |
| `core/adb-safety` | 38 | 危险分级四档、多语句取最危险 |
| `core/adb-sql` | 17 | 词法 / 分句 / 格式化 |
| `workbench/adb-jdbc` | 9 | 参数化、事务、类型映射 |
| `testing/adb-fixtures` | 3 | 演示库固定种子 |
| `workbench/adb-introspect` | 24 | 内省、分页排序筛选 SQL、结构变更 |
| `workbench/adb-session` | 8 | 连接档案、凭据、连接池 |
| `workbench/adb-editing` | 19 | 编辑缓冲、SQL 生成、反向撤销 |
| `ui/adb-ui-core` | 4 | 令牌键一致 / 亮暗一致 / 无颜色字面量 |
| `ui/adb-ui-shell` | 53 | 外壳、数据页、查询页、结构页、连接页、向导、渲染、CSV |

---

## 2. P0 外壳（已完成）

| 项 | 证据 |
|---|---|
| 原生窗口 + 六区布局 | `AppShell` / `MainToolBar` / `SideBar` / `TabBar` / `StatusBar` |
| 原生窗口装饰 + macOS 系统菜单栏 | `StageStyle.DECORATED` + `MenuBar.setUseSystemMenuBar(true)` |
| 统一动作注册表 | `ActionRegistry` 驱动菜单、工具栏、右键、命令面板 |
| 侧栏 240 / 48px、可拖动 | `SideBar` + `AppShell` |
| 双主题 + 跟随系统 + 本地记忆 | `ThemeManager` + `tokens.json` |
| 命令面板 ⌘K | `CommandPalette` |
| 密度档位（紧凑 / 标准 / 宽松） | `AppShell.applyDensity` + `app.css` |
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
| 三态 + 状态栏 | 空态/加载/错误；连接、范围、耗时、脏计数 |
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
- [x] 主键 `🔑`、NOT NULL `•`、类型行、Tooltip
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
      （⛔ 第三层「驱动侧复查」未实现，数据页保存路径也不分类 → 见 §10 A6）

### 5.4 P3 已知限制

- 括号匹配未做（P3.1）。
- 结果列宽不持久化；结果集不可排序。
- 结果集超过 1000 行会被截断，**当前不给提示**（→ P4.5 修）。
- 多语句逐条自动提交；失败会保留已完成语句的结果，不提供整批回滚。

---

## 6. P4 结构页（已完成）+ P4.5 契约修复（⬜ 下一阶段）
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

### 6.3 P4.5 契约修复（⬜ **下一阶段，优先级最高**）

> 这一组全是**已经对外承诺、但实现缺失**或**正确性缺陷**，不是新功能。

| # | 任务 | 落点 | 验收门禁 |
|---|---|---|---|
| 4.5-1 | 数据页补 `⌘C` 复制值 / `⌥⌘C` 复制 TSV / `⇧⌘C` 复制 INSERT / `⌘A` 全选当前页 | `DataGridPage.installShortcuts` | `DataGridPageTest`：按快捷键后剪贴板内容正确、`⌘A` 后勾选列全选 |
| 4.5-2 | 结果集 / 查询页超过 1000 行时明确提示「已截断」 | `JdbcSession.readResultSet` + `RowPage` + `QueryPage` | 断言 `RowPage` 带截断标记，查询页状态栏出现提示 |
| 4.5-3 | 数据页保存路径接入 `DangerClassifier`（批量 DELETE 至少升到「写数据」级并提示） | `DataGridPage.saveChanges` + `EditSqlBuilder` | 单测断言删除行的保存会弹风险确认 |
| 4.5-4 | `DangerClassifier` 收紧 fail-open：未知语句头按「管理操作」处理；多语句切分改为字符串/注释安全 | `core/adb-safety` | 新增单测：`MERGE` / `UPSERT` / `CALL` / `COPY` / `SET` 不再判为只读；字符串内分号不切碎 |
| 4.5-5 | 图标统一：`⚙` 结构/设置拆开；刷新统一 `↻`；关闭统一 `✕`；放大镜统一一个码位 | `TabKind` / `MainToolBar` / `DataGridPage` / `SideBar` | 截图核对 + `TabKind` 图标唯一性单测 |
| 4.5-6 | 所有图标按钮补 `setAccessibleText` | 全 UI | 读屏标签单测：工具栏与标签页图标按钮均有 accessible text |
| 4.5-7 | 危险确认弹窗的默认按钮不得是破坏性动作 | `ConnectionsPage.confirmDelete` / `AppShell.confirmDeleteConnection` | 单测断言默认按钮不是「删除」 |
| 4.5-8 | 侧栏树右键菜单并入 `ActionRegistry`（可进命令面板、enabled 统一） | `SideBar.menuFor` + `AppShell.registerActions` | 命令面板能搜到「复制 SELECT」 |
| 4.5-9 | 工具栏图标按钮绑定动作 `enabled` | `MainToolBar.iconButton` | 动作禁用时按钮同步禁用 |
| 4.5-10 | `connections.manage` / `connection.reconnect` 进菜单 | `MenuBarFactory` | 菜单树单测 |
| 4.5-11 | 状态栏 `DEV` / `未选择` / `自动提交` 三个假字段接上真实数据或删除 | `StatusBar` + `AppShell` | 单测断言选中行数实时更新 |
| 4.5-12 | 结构页「新增列」非法输入时不关闭对话框 | `StructurePage.showColumnDialog` | 非法列名时对话框仍在且提示可见 |
| 4.5-13 | `⌘.` 取消要能真正取消数据页加载 / 保存 | `JdbcSession.query/update` 也记录 `activeStatement` | 单测：长查询按 `⌘.` 后 `Statement.cancel()` 被调用 |
| 4.5-14 | 令牌体系收敛：`fs-*` `sp-*` `r-*` `h-*` `z-*` `dur-*` 要么真正用起来、要么从 `tokens.json` 移除 | `tokens.json` + `GenTokens` + `app.css` | `TokenParityTest` 增加「令牌不得有未引用项」断言 |

### 6.4 P4.5 附带的窗口骨架差距（源自 Navicat 实测，详见 `docs/NAVICAT-GAP.md` §0.1）

| # | 任务 | 落点 | 验收门禁 |
|---|---|---|---|
| S1 | 顶部改成**大图标 + 文字的功能栏**（连接 / 新建查询 / 表 / 视图 / 查询 / 导入导出 / 工具） | `MainToolBar` | 截图核对；功能入口不再藏在二级菜单里 |
| S2 | **表清单元数据列**：行数 / 数据长度 / 引擎 / 创建日期 / 注释 | `SchemaService` + `SideBar` | 表清单能显示行数与大小 |
| S3 | 对象树补 **视图 / 函数 / 其它** 分组节点 | `SideBar.buildTreeModel` + `SchemaService.listObjects` | 树上有三类分组，节点可展开 |
| S4 | 侧栏搜索框移到**底部**，与 ★ 收藏、折叠按钮同一行 | `SideBar.buildExpanded` | 截图核对 |
| S5 | 标签栏第一个是**「对象」首页标签**（不可关闭） | `TabBar` + `WorkspaceView` | 打开对象首页后可关闭其它标签 |
| S6 | 菜单栏加 **「收藏夹」顶级菜单** | `MenuBarFactory` | 菜单树单测 |

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
- [ ] **Windows 凭据**：WinCred / DPAPI 后端（`CredentialStore.platform()` 补分支）
- [ ] **连接属性页**：读超时、SSL、字符集、连接池大小、附加参数（`options` 要有 UI）
- [ ] **口令不落 argv**：macOS 改用 `security -w` 从 stdin 读，或换 `security` 的 `-X` 交互模式
- [ ] **`options` 敏感字段脱敏**：`~/.agenticdb/connections.json` 落盘前剔除口令类键
- [ ] **schema / catalog 树**：对象树按 schema 分组，可切换
- [ ] **连接重命名 / 分组 / 标签**
- [ ] **最近使用排序**：列表按 `lastUsedAt` 倒序
- [ ] **连接属性测试**：连接超时、取消、重连、错误归类（连接断开 / 权限不足 / 死锁 / 超时）
- [ ] **PG / MySQL 真实联调**：Testcontainers 集成测试（`postgresql` + `mysql` 模块进父 POM）
- [ ] **PG / MySQL 内省补齐**：DEFAULT、COMMENT、序列、CHECK、`getExportedKeys`
- [ ] **方言抽象层**：抽 `Dialect` 接口，消灭 6 个文件里的 `switch (kind)`
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

---

## 10. 架构整改清单

> 来源：`docs/NAVICAT-GAP.md` §5.2。每条都要有单测或门禁。

| # | 问题 | 整改方向 | 阶段 |
|---|---|---|---|
| A1 | 无 Dialect 抽象层，SQL 生成散落 6 处 `switch` | 抽 `Dialect` 接口：quote / limitOffset / explain / catalogSchema / typeMap | P5 |
| A2 | `declarations/` 1500 行是死代码 | 接到运行时（驱动、方言词表、能力矩阵驱动 UI 显隐）**或**整模块删除 | P5 |
| A3 | `loadFromClasspath()` 不可用（jar 索引解析错误） | 修 jar 资源读取，或随 A2 一起删 | P5 |
| A4 | `paramStyle` / `identifierQuote` 被硬编码 | 随 A1 统一到 `Dialect` | P5 |
| A5 | 元数据只读当前 schema | 引入 `getSchemas()` / `getCatalogs()` / `getExportedKeys()` / `getColumns()` | P5 |
| A6 | 危险分级第三层门禁不存在；数据页保存不分类 | `DbSession.execute(sql, danger)` 驱动侧复查 | P4.5 |
| A7 | 危险分级 fail-open（未知 → 只读） | 未知语句头按管理操作处理 | P4.5 |
| A8 | 多语句切分不看字符串/注释 | 复用 `adb-sql` 的 `SqlSplitter` | P4.5 |
| A9 | 取消只覆盖查询页 | `query()` / `update()` 也记录 `activeStatement` | P4.5 |
| A10 | 没有语句超时 | `Statement.setQueryTimeout` | P5 |
| A11 | 结果截断静默 | `RowPage` 带截断标记 + UI 提示 | P4.5 |
| A12 | 连接池是 1 槽装饰器，连接不归还 | 真正归还连接，或明确改名 `SingleConnectionHolder` | P5 |
| A13 | macOS 钥匙串口令经 argv 泄漏 | 改 stdin 传入 | P5 |
| A14 | `options` 明文落盘 | 落盘前脱敏 | P5 |
| A15 | 连接与内省跑在 UI 线程 | 移到 `adb-db` 执行器 | P5 |
| A16 | 全局单线程执行器 | 每连接一个执行器 | P5 |
| A17 | 79/120 令牌未被引用 | 收敛令牌集或真正用起来 | P4.5 |
| A18 | 「跟随系统」只探测 macOS | 跨平台探测 | P7 |
| A19 | 密度档位只影响对象树 | 覆盖网格行高 | P7 |
| A20 | `MAX_CELL_BYTES` 声明未用 | 真正限制单元格体积，或删常量 | P4.5 |

---

## 11. 门禁与完成定义

统一命令：

```bash
./mvnw -B clean verify
```

| 门禁 | 要求 |
|---|---|
| 编译 | Java 25，父 POM 统一版本，子模块不带 `<version>` |
| 单元 | 编辑、SQL、渲染、CSV、词法器、结构变更、连接档案必须有断言 |
| 数据 | 演示库固定种子；SQL 下推与事务保存有真实 SQLite 断言 |
| UI | TestFX + Monocle；外壳、数据页、批量条、详情抽屉、查询页、结构页、连接页、连接向导 |
| 样式 | `TokenParityTest`：键一致、亮暗一致、无颜色字面量、令牌无未引用项 |
| 文档 | README、本清单、架构、UI 指南、对标文档与实际实现同步 |
| 对标 | `docs/NAVICAT-GAP.md` 的差距项要么进清单，要么写明「明确不做」 |

**完成定义**：功能可用 + 门禁断言 + 文档同步 + 无旧架构残留。
写不出断言的功能视为未完成。
