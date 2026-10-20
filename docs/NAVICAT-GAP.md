# Navicat 对标分析

> 对标对象：Navicat Premium 17（桌面端，macOS/Windows）。
> 本文回答「**我们还欠缺什么**」，只列事实与差距，不重复 `NAVICAT-TODO.md` 的排期。
> 每个条目都带代码证据；没有证据的判断一律不写。
>
> 记号：✅ 已达成 · 🟡 部分达成 · ⛔ 缺失

---

## 0.1 实测对标（Navicat Premium Lite 17 / macOS）

对照真机截图逐项核过，Navicat 的窗口结构是：

```
菜单栏   文件 编辑 查看 表 收藏夹 工具 窗口 帮助
功能栏   连接  新建查询  表  视图  函数  用户  其它  查询  备份  自动运行  模型  BI   ← 大图标 + 文字
左面板   我的连接 → 连接 → schema → 表/视图        底部：搜索框 + 过滤 + ★ + 折叠
标签栏   对象(首页)  [表图标] t_haitongyun_order  [表图标] t_haitongyun_user
表工具   打开  编辑  ＋  －  …  ⟳                       右侧：视图切换 + 搜索
表清单   名称 | 行 | 数据长度 | 引擎 | 创建日期 | 修改日期 | 排序规则 | 注释   ← 带元数据列
状态栏   80 个表
```

由此得到 6 条**结构性**差距（不是功能清单，是窗口骨架的差异）：

| # | Navicat 的做法 | 我们的做法 | 差距性质 |
|---|---|---|---|
| S1 | 顶部是**大图标功能栏**（12 个带文字的功能入口） | 顶部是 36px 的**按钮行**（3 个按钮 + 1 个下拉 + 1 个伪搜索框） | 信息密度与可发现性：Navicat 的功能入口一眼可见，我们的藏进菜单 |
| S2 | **表清单是带元数据列的表格**（行数 / 数据长度 / 引擎 / 创建日期 / 注释） | 表清单**只有表名**，点开就是数据网格 | 少了「一眼看清表有多大、什么时候建的」这一层 |
| S3 | 对象树有 **表 / 视图 / 函数 / 用户 / 其它** 五类分组 | 只有 **表 / 视图** 两类 | 内省能力直接反映在树骨架上 |
| S4 | 左面板底部有**搜索框 + 过滤 + ★ + 折叠** | 搜索框在顶部，★ 在「收藏」区 | 布局与 Navicat 不一致，收藏入口位置不同 |
| S5 | 标签栏第一个是**「对象」首页标签**（不可关闭） | 首页是内容区的欢迎卡片 | 导航模型不同：Navicat 用标签，��们用卡片 |
| S6 | 菜单栏有独立的**「收藏夹」顶级菜单** | 收藏只有侧栏星标 | 收藏缺少全局入口 |

**结论**：功能清单之外，Navicat 的**窗口骨架**本身也是产品力的一部分。
我们的骨架在「数据页 / 查询页」上已经接近，但「对象浏览」这一层还差 S1–S6 这一整套。

---

## 0. 结论摘要

| 维度 | 达成度 | 一句话结论 |
|---|---|---|
| 外壳 / 窗口 | ✅ | 六区布局、命令面板、双主题、原生菜单栏都已到位 |
| 数据网格 | ✅ | 排序/筛选/编辑/批量/撤销重做/详情抽屉/导出，是目前最强的一块 |
| 查询编辑器 | ✅ | 词法内核 + 高亮 + 多语句 + 取消 + 执行计划 + 历史 |
| 结构 / DDL | 🟡 | 只读信息完整，**只能改列**，索引/外键/触发器不可编辑 |
| 连接管理 | 🟡 | 列表/向导/钥匙串都做了，**多连接并行、schema 树、连接属性页缺失** |
| 对象树 | 🟡 | 只有表/视图两类节点，**没有过程/函数/触发器/schema 分组** |
| 导入导出 | ⛔ | 只有「当前页 CSV / TSV / SQL」，**没有向导** |
| 工具 / 运维 | ⛔ | 诊断、导入导出、查询历史、已保存查询**四个入口全是空壳** |
| 分发 | ⛔ | 只有 `mvnw`，没有 jlink / jpackage / 签名 |
| 架构 | 🟡 | 分层干净，但**方言没有抽象层**，声明层（declarations）是死代码 |

**最该先补的三件事**：① 导入/导出向导（P6，Navicat 的招牌功能）；② 对象树补过程/函数/触发器节点 + **表清单元数据列**（Navicat 的对象浏览层）；③ 把 `declarations/` 声明层接进运行时，或者删掉它。

---

## 1. 功能矩阵

### 1.1 连接管理

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 连接列表、搜索、收藏 | ✅ | `ConnectionsPage`：搜索框 + 「只看收藏」+ ★ 标记 |
| 新建连接向导 | ✅ | `ConnectionWizard` 三步：类型与名称 → 参数 → 测试与保存 |
| 测试连接 | ✅ | `AppShell.testConnection` 跑 `SELECT 1` |
| 口令存钥匙串 | 🟡 | `KeychainCredentialStore`（macOS `security`）/ `SecretToolCredentialStore`（Linux `secret-tool`）；**Windows 落到内存**（`CredentialStore.platform()` 无 WinCred 分支） |
| 编辑 / 复制 / 删除连接 | ✅ | `ConnectionsPage` 工具条 |
| **多连接同时打开** | 🟡 | `SessionManager` 按 id 存多会话，`TabItem.connectionId` 也支持，但 `AppShell` 只有**一个 `activeProfile` / 一个 `session` 字段**，切标签会切当前连接 |
| **连接属性页**（读超时/SSL/字符集/连接池） | ⛔ | `ConnectionProfile.options` 是自由 Map，向导里没有 UI 编辑它 |
| **schema / catalog 树** | ⛔ | `SchemaService` 只读当前 schema；`getSchemas()` / `getCatalogs()` 一次都没调用 |
| **连接分组 / 标签** | ⛔ | — |
| **最近连接排序** | ⛔ | `ConnectionProfile.lastUsedAt` 字段存在，但列表不按它排序 |

### 1.2 对象树

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 连接 → 表 / 视图分组 | ✅ | `SideBar.buildTreeModel` |
| 双击打开 | ✅ | `SideBar` 树 + 单元格双击（**两个处理器都会触发，靠 `TabBar.open` 去重兜底**） |
| 右键菜单 | 🟡 | 表/视图节点 7 项（打开数据 / 打开结构 / 新建查询 / 收藏 / 复制名称 / 复制 SELECT / 诊断）；连接节点 3 项；**没有重命名、没有新建表、没有导出、没有表属性** |
| 收藏 | 🟡 | `SideBar.starred` **只在内存**，退出即丢；`ConnectionProfile.favorite` 才落盘 |
| 过滤搜索 | ✅ | 侧栏搜索框，子串匹配 |
| **过程 / 函数 / 触发器 / 序列 / 事件节点** | ⛔ | `SchemaService.listObjects` 只查 `{"TABLE","VIEW"}`；`ObjectKind` 只有 TABLE/VIEW |
| **拖拽建表 / 拖拽改字段类型** | ⛔ | 无任何拖放处理器 |
| **多选 + 批量操作** | ⛔ | `TreeView` 保持默认 `SelectionMode.SINGLE` |
| **对象树按 schema 展开** | ⛔ | — |

### 1.3 数据网格

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 服务端分页 / 排序 / 筛选 | ✅ | `DataSqlBuilder`；`OFFSET` 上限 500000 |
| 列头值筛选（候选值 + 计数 + 级联） | ✅ | `ColumnFilterPanel`，高基数（>1000）降级为精确匹配 |
| 条件构造器 | ✅ | `ConditionDialog`，12 个操作符 |
| 行内编辑 + 类型转换 | ✅ | `ValueCell` + `CellValueParser` |
| 撤销 / 重做 | ✅ | `EditBuffer`，批量操作只占一个栈位 |
| 保存前 SQL 预览 + 单事务 | ✅ | `DataGridPage.confirmSqlPreview` + `EditSqlBuilder.execute` |
| 保存后反向撤销 | 🟡 | `EditSqlBuilder.inversePlan`；**主键为空的新增行无法反删**，只覆盖最近一次保存 |
| 批量：TSV / INSERT / 克隆 / 设 NULL / 空串 / 删除 | ✅ | `DataGridPage.buildBatchBar` |
| 单元格详情抽屉（文本/JSON/Hex/图片/元数据） | ✅ | `CellDetailsDrawer`，大字段懒加载 |
| CSV 导出 / TSV 复制 / SQL 复制 | ✅ | `CsvExporter`，UTF-8 BOM |
| 布尔列点击切换 | ✅ | `ValueCell` CheckBox |
| **冻结 / 固定列** | ⛔ | 全仓库 grep `冻结` / `freeze` / `pin` = 0 命中 |
| **列汇总（SUM / AVG / COUNT 脚注行）** | ⛔ | 没有脚注行 |
| **列头拖拽重排** | ⛔ | 顺序只能在「列」菜单里用勾选控制，没有拖拽 |
| **单元格区域选择（拖选 / Shift 范围选）** | ⛔ | 只有行选择（`SelectionMode.MULTIPLE`）+ 勾选列 |
| **编辑后跳到下一格（Tab / ↓）** | ⛔ | 只有 Enter 提交、ESC 取消 |
| **⌘C / ⌥⌘C / ⇧⌘C / ⌘A 快捷键** | ⛔ | `docs/UI-GUIDE.md` 写了，代码里 `KeyCode.C` / `KeyCode.A` **一次都没出现** |
| **整表导出（不限当前页）** | ⛔ | 导出菜单只有「当前页」 |
| **主键值批量改 / 自增列插入** | ⛔ | — |
| **列注释显示** | 🟡 | UI 有位置（列头 Tooltip、详情抽屉、���构页），但 `ColumnDef.comment` **永远是 null**（`TypeMapper.toColumnDef` 与 `SchemaService.sqliteColumns` 都传 null） |

### 1.4 查询编辑器

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 语法高亮 | ✅ | `SqlHighlighter` + `adb-sql` 词法内核 |
| 多语句、逐条结果页签 | ✅ | `SqlSplitter` 跳过字符串/注释/`BEGIN…END` 里的分号 |
| 执行当前 / 执行全部 / 取消 | ✅ | ⌘R / ⇧⌘R / ⌘. |
| 危险分级 + 二次确认 | ✅ | `DangerClassifier` 四级 + `QueryPage.confirmDanger` |
| 执行计划 | ✅ | SQLite `EXPLAIN QUERY PLAN`，PG/MySQL `EXPLAIN` |
| 错误定位到编辑器 | ✅ | `ErrorLocator`（PG/MySQL/Oracle 三种格式） |
| 查询历史 | ✅ | 最近 50 条，去重，落 `java.util.prefs` |
| 查找替换 / 格式化 / 注释 | ✅ | `⌘F` / `⌥⇧F` / `⌘/` |
| **括号匹配 / 代码折叠** | ⛔ | `NAVICAT-TODO.md` 记为 P3.1，未做 |
| **结果集排序 / 列宽持久化** | ⛔ | `column.setSortable(false)`；列宽不落盘 |
| **结果集分页** | ⛔ | 一律截断到 1000 行，**且不告诉用户被截断了**（`JdbcSession.readResultSet` 读满 1000 就停，`RowPage.nextCursor` 永远是 null，`truncatedColumns` 永远空） |
| **多个结果集并存（同一语句返回多结果集）** | ⛔ | MySQL 声明里就写了 `multipleResultsets: false` |
| **变量 / 参数替换** | ⛔ | — |
| **代码片段 / 模板库** | ⛔ | 侧栏「已保存查询」是空壳 |

### 1.5 结构 / DDL

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 列 / 索引 / 外键 / 触发器列表 | ✅ | `StructurePage` 五个 Tab |
| DDL 查看 | ✅ | SQLite 取 `sqlite_master.sql`；MySQL `SHOW CREATE TABLE`；**PG 只能生成等价 DDL**（UI 已标注） |
| 复制 DDL / 复制 SELECT | ✅ | — |
| 新增列 / 改可空 / 改默认值 / 重命名列 | ✅ | `StructureSqlBuilder`；SQLite 走整表重建 |
| 危险操作二次确认 + 展示 SQL | ✅ | `StructurePage.defaultConfirm` 两级确认 |
| **新建 / 删除索引** | ⛔ | — |
| **新建 / 删除外键** | ⛔ | — |
| **新建 / 删除触发器** | ⛔ | — |
| **新建 / 重命名 / 删除表** | ⛔ | — |
| **CHECK 约束** | ⛔ | 内省根本没读 |
| **PostgreSQL 注释、默认值、序列** | ⛔ | `TypeMapper` 不读 `getColumns()`，PG 的 DEFAULT / COMMENT 全丢 |

### 1.6 导入导出

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 当前页 CSV / TSV / SQL | ✅ | — |
| **导入向导（文件 → 目标表 → 字段映射 → 预览 → 执行）** | ⛔ | 侧栏「导入 / 导出」是空壳（`WorkspaceView` 里只有一句 TODO 文案） |
| **导出向导（整表 / 查询结果 / JSON / Excel）** | ⛔ | 同上 |
| **大文件流式** | ⛔ | — |
| **失败行报告 / 跳过策略** | ⛔ | — |

### 1.7 工具与运维

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 命令面板 | ✅ | `CommandPalette` ⌘K |
| 诊断（表/索引健康） | ⛔ | 空壳 |
| 计划任务 / 自动运行 | ⛔ | 明确不做 |
| 事务管理（显式 BEGIN/COMMIT） | 🟡 | 状态栏 `setTransaction` 方法存在但**从没被调用**，永远显示「自动提交」 |
| 表 / 字段级权限 | ⛔ | 明确不做 |
| ER 图 | ⛔ | 明确不做 |
| 数据同步 | ⛔ | 明确不做 |

### 1.8 分发

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| jlink / jpackage | ⛔ | P7 |
| 自动更新 | ⛔ | P7 |
| 崩溃日志 / 诊断包 | ⛔ | P7 |
| 代码签名 / 公证 | ⛔ | P7 |
| 可访问性（读屏标签、对比度） | 🟡 | `UI-GUIDE.md` 写了规则，但 `TabKind` 图标缺 `aria` 类文本；对比度未做自动检查 |

---

## 2. 交互审计

### 2.1 快捷键：文档与实现不一致

`docs/UI-GUIDE.md` 承诺了下面 4 个数据页快捷键，**代码里完全没有**：

| 文档承诺 | 实现 | 修法 |
|---|---|---|
| `⌘C` 复制值 | ⛔ | `DataGridPage.installShortcuts` 补 |
| `⌥⌘C` 复制 TSV | ⛔ | 同上 |
| `⇧⌘C` 复制 INSERT | ⛔ | 同上 |
| `⌘A` 全选当前页 | ⛔ | 同上 |

已实现的只有：`⌘Z` / `⇧⌘Z` / `⌘S` / `⌘F` / `ESC`。

### 2.2 动作注册表的漏网之鱼

`ActionRegistry` 是「菜单 / 工具栏 / 右键 / 命令面板」的唯一真相，但实际有 3 处破例：

| 破例 | 位置 | 后果 |
|---|---|---|
| 侧栏树的右键菜单全部直接调私有方法 | `SideBar.menuFor` | 表/视图节点 7 个菜单项**都不在命令面板里**，也没有统一 enabled 判断 |
| 侧栏 5 个导航按钮直接开标签页 | `SideBar.buildExpanded` 「查询历史 / 已保存查询 / 诊断 / 导入导出 / 设置」 | 与工具栏同名动作走两条代码路径 |
| 工具栏 3 个图标按钮不走 `enabled` 绑定 | `MainToolBar.iconButton` | `↻` `◐` `⚙` 永远可点，即使动作已禁用 |

另外 `connections.manage` 和 `connection.reconnect` **只注册、没有任何菜单入口**，只能靠 ⌘K 找。

### 2.3 状态栏有 3 个永远不变的字段

| 字段 | 状态 |
|---|---|
| `DEV` 环境标记 | `setEnv` **零调用**，永远是 `DEV`（连测试环境都分不出来） |
| `未选择` | `setSelection` **零调用**，永远显示「未选择」 |
| `自动提交` | `setTransaction` **零调用**，永远显示「自动提交」 |

### 2.4 反馈一致性问题

- **危险操作默认按钮**：`UI-GUIDE.md` 要求「默认按钮不是删除」。`ConnectionsPage.confirmDelete` 的 OK 按钮文案是「删除」，但 `ButtonData.OK_DONE` 会让它成为默认按钮 → 回车 = 删除。**违反自己的规则。**
- **Toast 时长**：`UI-GUIDE.md` 写「1.8 秒；带撤销的延长到 6 秒」，代码里 Toast 时长没有按是否带撤销区分。
- **列头筛选图标**：`⌄`（U+2304 下箭头）当漏斗用，不是漏斗形状。

---

## 3. 按钮审计

### 3.1 工具栏（`MainToolBar`，10 个元素）

| # | 元素 | 形态 | 备注 |
|---|---|---|---|
| 1 | `＋ 连接` | 文字 | 覆盖了动作文案「新建连接…」 |
| 2 | `↻` | 图标 | Tooltip「刷新 (F5)」 |
| 3 | `SQL` | 文字 | 覆盖了动作文案「新建查询」 |
| 4 | 分隔线 | — | — |
| 5 | 连接下拉 | ComboBox | `name · KIND` |
| 6 | `⌘K 搜索对象或命令` | 伪搜索框 | — |
| 7 | `◐` | 图标 | 主题 |
| 8 | `⚙` | 图标 | 设置 |

**缺口**：没有「新建表」「保存查询」「停止」按钮；连接下拉放在分隔线**右侧**，视觉上属于右侧区而不是连接区。

### 3.2 数据页工具栏（`DataGridPage`，15 个元素）

刷新 / 结构 / 新增 ‖ 撤销 / 重做 / 保存 ‖ 筛选 / 清除条件 / 列 / 视图 / 导出 ‖ 搜索框。

**缺口**：
- 没有「首行 / 末行 / 跳页」，只有 `‹ 上一页` `下一页 ›`；总行数未知时页码显示「第 — / — 页」。
- 「保存」按钮文案会变成 `保存 3`，宽度随脏计数跳动。
- 没有批量操作的「导出选中行」入口（批量条只有复制）。

### 3.3 结构页工具栏（`StructurePage`，7 个元素）

`＋ 新增列` / `修改列` / `重命名` / `删除列` / `↻ 刷新` / `复制 DDL` / `复制 SELECT`。

**缺口**：没有「新建索引 / 外键 / 触发器 / 表」的入口。

### 3.4 连接管理页工具栏（`ConnectionsPage`，6 个元素）

`＋ 新建连接` / `打开` / `编辑` / `复制` / `删除` / `↻ 刷新`。

**缺口**：没有行内「测试连接」按钮、没有「导出 / 导入连接配置」。

### 3.5 弹窗清单

| 弹窗 | 触发 | 问题 |
|---|---|---|
| 保存修改（SQL 预览） | 数据页 `⌘S` | ✅ |
| 添加筛选条件 | 数据页「筛选」 | ✅ |
| 单元格详情抽屉 | 单元格右键 | ✅ |
| 结构变更（列） | 结构页 | 🟡 **列名非法时对话框已经关了**，用户输入丢失，错误只落在状态栏 |
| 重命名列 | 结构页 | 🟡 同上 |
| 结构变更确认（两级） | 结构页 | ✅ |
| 连接向导 | 工具栏 / 侧栏 | ✅ |
| 删除连接 | 连接页 | 🟡 OK 按钮是默认按钮（回车即删） |
| 确认执行（危险 SQL） | 查询页 | ✅ |
| 关于 / 快捷键 | 帮助 | ✅ |

**共性缺口**：没有「确认后撤销」以外的**批量确认**（例如一次删 500 行没有汇总确认页）；没有统一的「错误详情」弹窗（错误只落在状态栏一行）。

---

## 4. 图标审计

当前策略：**全部用 Unicode 符号**，无图标字体、无 SVG 资源。

### 4.1 清单

| 符号 | 码位 | 语义 | 位置 |
|---|---|---|---|
| ▦ | U+25A6 | 表 | `TabKind.GRID`、`SchemaService.ObjectKind.TABLE`、侧栏 rail |
| ▤ | U+25A4 | 表分组 | `SideBar` |
| ◇ | U+25C7 | 视图 | `TabKind`（无）、`ObjectKind.VIEW`、`SideBar` |
| ◈ | U+25C8 | 连接 | `TabKind.CONNECTIONS`、`SideBar`、欢迎页 logo |
| ◆ | U+25C6 | 工具 | `TabKind.TOOL`、`SideBar` |
| ⚙ | U+2699 | **结构 / 设置（撞车）** | `TabKind.STRUCTURE` + `TabKind.SETTINGS`、工具栏、`SideBar`、数据页「结构」按钮 |
| ⌨ | U+2328 | 查询 | `TabKind.QUERY` |
| ↻ | U+21BB | 刷新 | 工具栏、连接页、结构页 |
| ⟳ | U+27F3 | 刷新（**同义不同形**） | 数据页 |
| ◐ | U+25D0 | 主题 | 工具栏 |
| ★ | U+2605 | 收藏 | `SideBar`、`ConnectionsPage` |
| ☰ | U+2630 | 展开侧栏 | `SideBar` rail |
| ⌕ | U+2315 | 搜索（**其实是「电话录音机」**） | `SideBar` rail |
| 🔍 | U+1F50D | 搜索（真正的放大镜，但只有这里用） | 数据页搜索框 |
| ⌄ | U+2304 | 筛选（**下箭头冒充漏斗**） | 列头筛选按钮 |
| ⇅ | U+21C5 | 导入导出 | `SideBar` |
| ⌷ | U+25F7 | 查询历史 | `SideBar` |
| ✕ | U+2715 | 关闭 | 标签页、抽屉、查询页 |
| × | U+00D7 | 删除行（**同义不同形**） | 数据页行号 |
| ● | U+25CF | 脏标记 | 标签页、状态栏 |
| ⬢ | U+2B22 | BLOB | `CellRenderer` |
| ✓ ✗ | U+2713 / U+2717 | 布尔 | `CellRenderer` |
| 🔑 | U+1F50D 附近 | 主键 | 列头、结构页 |
| • | U+2022 | NOT NULL | 列头 |

### 4.2 问题

1. **`⚙` 一符两义**：结构页标签和设置页标签图标完全一样，用户分不出。
2. **`⌕` 是「电话录音机」**：Unicode 官方名 TELEPHONE RECORDER。同一个 App 里放大镜用了 `🔍`（数据页）和 `⌕`（侧栏 rail）两种。
3. **刷新两种字形**：`↻` 与 `⟳`。
4. **关闭两种字形**：`✕` 与 `×`。
5. **BMP 与 emoji 混排**：`🔍` `🔑` 是非 BMP 彩色 emoji，其余是单色符号 —— 列头的 🔑 与相邻的 `•` 视觉重量不一致。
6. **跨平台缺字风险**：`⚙ ▤ ▦ ⌕ ⌄ ⇅ ⌷ ◈ ⬢` 属于 Miscellaneous Technical / Geometric Shapes，Windows 上常见字体可能替换或缺失。
7. **无障碍**：所有图标都是纯文本 Label / Button，**没有 accessible text**。读屏软件会读出「⚙」而不是「设置」。

**结论**：图标体系需要一次统一：① 结构/设置拆字形；② 统一刷新与关闭；③ 放大镜统一一个码位；④ 补 `setAccessibleText`；⑤ 长期方向是内嵌 SVG 图标集（JavaFX 原生支持，无需图标字体）。

---

## 5. 架构审计

### 5.1 分层现状（好的一面）

```
ui/adb-ui-shell → workbench/{adb-session, adb-introspect, adb-editing, adb-jdbc} → core/{adb-model, adb-safety, adb-sql}
```

依赖只向下，线程模型单一（全局 `adb-db` 单线程执行器），编辑按主键定位、参数化 SQL、单事务保存 —— 这几条不变量守得很好。

### 5.2 缺口

| # | 缺口 | 影响 | 证据 |
|---|---|---|---|
| A1 | **没有 Dialect 抽象层** | SQL 生成散落在 6 个文件的 `switch (kind)` 里；加一种数据库要改 6+ 处 | `DataSqlBuilder`（无分支，硬编码 `LIMIT/OFFSET`）、`SchemaService`（4 处）、`StructureSqlBuilder`（4 处）、`QueryPage.explainSql`（3 处）、`TypeMapper`（不感知方言） |
| A2 | **`declarations/` 是死代码** | 4 个连接声明 + 4 个方言词表 + 5 个传输家族 + JSON Schema，共约 1500 行，**运行时一次都没读** | 全仓库 `io.agenticdb.declarations` 的引用只出现在 `core/adb-declarations` 内部；`adb-declarations` **不是任何模块的依赖** |
| A3 | **`loadFromClasspath()` 不可用** | 打包后 jar 里的 `declarations/` 读不出来（`readAllFromJar` 把目录内容当换行分隔的索引文件解析） | `DeclarationLoader.readAllFromJar` |
| A4 | **参数风格 / 标识符引用被硬编码** | YAML 里声明的 `paramStyle: numbered`（PG）、`identifierQuote` 全部无效 | `JdbcSession.bind` 永远用 `?`；`DialectKind.quote` 硬编码 |
| A5 | **元数据只读当前 schema** | `getSchemas()` / `getCatalogs()` / `getExportedKeys()` / `getColumns()` 一次都没调用 | `SchemaService` 只用 `getTables` `getPrimaryKeys` `getIndexInfo` `getImportedKeys` |
| A6 | **SQL 危险分级第三层门禁不存在** | `Danger` 文档写「三层门禁」，第三层「驱动侧复查」没有实现；数据页保存路径**完全不分类** | `DbSession.execute(String)` 没有 `danger` 参数；`DataGridPage.saveChanges` 不调 `DangerClassifier` |
| A7 | **危险分级 fail-open** | 未知语句头一律判为 `READ_ONLY`：`MERGE` `UPSERT` `CALL` `EXECUTE` `COPY` `SET` `LOCK` `ANALYZE` `BEGIN` 全部不拦 | `DangerClassifier.classify` 未知 → `READ_ONLY` |
| A8 | **多语句切分不看字符串** | `DangerClassifier` 用裸 `String.split(";")`，字符串里的分号会切碎语句 | `DangerClassifier.classify` |
| A9 | **取消只覆盖查询页** | `activeStatement` 只在 `execute()` 里赋值，`query()` / `update()` 不赋值 → **数据页加载与保存无法真正取消 JDBC 语句** | `JdbcSession` 第 110/126/136 行 |
| A10 | **没有语句超时** | `Statement.setQueryTimeout` 一次都没调用；长查询只能等数据库或用户取消 | — |
| A11 | **结果截断静默** | 超过 1000 行直接停，用户看不到任何提示 | `JdbcSession.readResultSet` 第 183 行 |
| A12 | **连接池是 1 槽装饰器** | `ConnectionPool` 池大小 1，且 `PooledSession` 打开时就占住一条连接不归还；`idleTimeout` / `maxLifetime` 形同虚设 | `ConnectionPool` 第 38–52 行 |
| A13 | **口令可能通过 argv 泄漏** | macOS `security add-generic-password -w <password>`，口令出现在进程参数里，`ps` 可见 | `KeychainCredentialStore` 第 40 行 |
| A14 | **`ConnectionProfile.options` 明文落盘** | `options` 会被写进 `~/.agenticdb/connections.json`，同时又透传给 JDBC Properties | `ConnectionStore.write` |
| A15 | **连接与内省跑在 UI 线程** | `AppShell.connectProfile` 在 JavaFX 线程上同步 `SessionManager.open` + `listObjects`；`testConnection` 同样 | `AppShell` 第 177–211 行 |
| A16 | **全局单线程执行器** | A 连接上的慢查询会阻塞 B 连接的查询 | `AppShell.dbExecutor` |
| A17 | **令牌体系名义与实现脱节** | 120 个令牌里只有 41 个被 `app.css` 引用；65 个非颜色令牌**永远不会进 CSS**（生成器只导出颜色）；`fs-*` `sp-*` `r-*` `h-*` `z-*` `dur-*` 等整组闲置 | `GenTokens.emitCss` 只输出 color 类型 |
| A18 | **主题「跟随系统」只探测 macOS** | Windows / Linux 一律亮色 | `ThemeManager.systemPrefersDark` |
| A19 | **密度档位只影响对象树** | `density-compact` / `density-comfort` 的 CSS 只改了 `.adb-object-tree .tree-cell` 的 padding；数据网格行高不变 | `app.css` 第 1179–1185 行 |
| A20 | **`MAX_CELL_BYTES` 声明未用** | 20 KiB 的单元格上限常量没有任何地方读 | `QueryLimits` 第 78 行 |

### 5.3 已经做对、值得保持的

- 设计令牌唯一真相 + `TokenParityTest`（键一致 / 亮暗一致 / 禁止颜色字面量）。
- 编辑按主键定位 + 单事务 + 反向撤销 + 脏标记 + 关闭确认。
- 所有 JDBC 参数化，标识符才走方言引用。
- 每条「已完成」都有门禁断言（当前 30 个测试类 / 246 条测试）。

---

## 6. 按「投入产出」排序的补齐清单

> 排期见 `NAVICAT-TODO.md`；这里只给优先级判断。

| 优先级 | 事项 | 理由 |
|---|---|---|
| **P0** | 数据页补 `⌘C` / `⌥⌘C` / `⇧⌘C` / `⌘A` | 文档已承诺，实现缺失，属于**契约破损** |
| **P0** | 结果集 >1000 行给出「已截断」提示 | 静默丢数据是**正确性问题**，比缺功能严重 |
| **P0** | 数据页保存路径接入 `DangerClassifier` | 危险 SQL 在查询页拦住了，在保存路径没拦，门禁是空的 |
| **P0** | `⚙` 图标撞车 + 图标统一 + 补 accessible text | 一符两义 + 读屏不可用是可感知缺陷 |
| **P1** | 导入 / 导出向导 | Navicat 的招牌能力，目前完全是空壳入口 |
| **P1** | 对象树补触发器 / 视图 / 过程节点 | 内省已有触发器数据（`TriggerDef`），只是没接到树上 |
| **P1** | 表清单元数据列（行数 / 大小 / 引擎 / 创建日期 / 注释） | Navicat 对象浏览层的核心信息密度（骨架差距 S2） |
| **P1** | 顶部功能栏（大图标 + 文字的入口区） | 骨架差距 S1，直接影响功能可发现性 |
| **P1** | 危险分级 fail-open 收紧 + 字符串安全切分 | 安全语义不能靠「未知即只读」 |
| **P1** | 真方言抽象层（Dialect 接口） | 再加两种数据库前必须做，否则 `switch` 会失控 |
| **P1** | 多连接并行（去掉单一 `activeProfile`） | 连接管理做了一半，单连接是硬天花板 |
| **P2** | `declarations/` 接进运行时 **或** 删除 | 1500 行死代码会误导后续开发 |
| **P2** | 取消 / 超时下沉到 `query()` / `update()` | 数据页加载无法取消是体验硬伤 |
| **P2** | 冻结列 / 汇总脚注 / 单元格区域选择 | 数据网格的进阶三件套 |
| **P2** | 标签栏「对象」首页标签 + 收藏夹顶级菜单 | 骨架差距 S5 / S6 |
| **P2** | 令牌体系收敛（把 `fs-*` `sp-*` `r-*` 真正用起来或删掉） | 120 个令牌里 79 个是摆设，维护成本 |
| **P3** | jlink / jpackage / 签名公证 / 崩溃日志 | 没有分发就没有用户 |
| **P3** | 跨平台主题探测（Windows / Linux） | 「跟随系统」目前名不副实 |
