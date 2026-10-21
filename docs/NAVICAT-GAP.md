# Navicat 对标分析

> 对标对象：Navicat Premium 17（桌面端，macOS/Windows）。
> 本文回答「**我们还欠缺什么**」，只列事实与差距，不重复 `NAVICAT-TODO.md` 的排期。
> 每个条目都带代码证据；没有证据的判断一律不写。
>
> 记号：✅ 已达成 · 🟡 部分达成 · ⛔ 缺失 · ⚠️ 契约破损（承诺过但实现缺失/实现错）
>
> 最后更新：**2026-10-21 第四轮 —— 逐文件代码复核 + B0–B4 全部落地**。
> 本轮把上一版文档里**已经与代码对不上的 16 处结论**全部改正（见 §0.2），
> 新增 **38 项**此前未记录的差距，并**关闭了其中 21 项**（见 §0.3）。

---

## 0. 结论摘要

| 维度 | 达成度 | 一句话结论 |
|---|---|---|
| 外壳 / 窗口 | 🟡 | 六区布局、命令面板、双主题、原生菜单栏都已到位；但**没有 Navicat 的大图标功能栏 / 对象首页标签 / 收藏夹菜单**（骨架 S1/S5/S6 全缺） |
| 数据网格 | ✅ | 排序/筛选/编辑/批量/撤销重做/详情抽屉/导出，是目前最强的一块；**进阶三件套（冻结列 / 汇总脚注 / 区域选择）缺失** |
| 查询编辑器 | ✅ | 词法内核 + 高亮 + 多语句 + 取消 + 执行计划 + 历史；**变量替换 / 代码折叠 / 括号匹配 / 结果排序缺失** |
| 结构 / DDL | 🟡 | 只读信息完整，**只能改列**，索引/外键/触发器不可编辑，也不能建表 |
| 连接管理 | 🟡 | 列表/向导/钥匙串都做了，**凭据已加固**（脱敏 + stdin + 不被 options 覆盖）；**多连接并行、schema 树、连接属性页缺失** |
| 对象树 | 🟡 | 只有表/视图两类节点，**没有过程/函数/触发器/schema 分组**；**收藏不落盘** |
| 导入导出 | ⛔ | 只有「当前页 CSV / TSV / SQL」，**没有向导**；侧栏入口是空壳 |
| 工具 / 运维 | 🟡 | **查询历史、诊断已接真功能**；导入导出、已保存查询仍是空壳（P6） |
| 分发 | ⛔ | 只有 `mvnw`，没有 jlink / jpackage / 签名 |
| 架构 | 🟡 | 分层干净，**危险分级已 fail-closed**、数据页保存已分级；但**方言没有抽象层**、声明层仍未驱动运行时 |
| **契约一致性** | ✅ | **文档里 16 处「已完成」与代码对不上**已全部纠正；本轮新增 38 项、关闭 21 项（见 §0.2 / §0.3） |

**本轮（第四轮）之后，最该先补的三件事**：
① **导入/导出向导**（Navicat 的招牌能力，目前完全是空壳入口）；
② **对象树补过程/函数/触发器节点 + 表清单元数据列**（Navicat 的对象浏览层）；
③ **真方言抽象层 + 多连接并行**（P5 的根，见 §6.2 A1 / §7.2）。

> 上一版把「危险分级 fail-open」排在第一位 —— **本轮已经关掉了**：
> `WITH … DELETE` 不再绕过确认框，数据页保存路径已接入分级，
> 批量操作不再能改主键列。安全语义现在**站得住**了。

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

| # | Navicat 的做法 | 我们的做法 | 差距性质 | 证据 |
|---|---|---|---|---|
| S1 | 顶部是**大图标功能栏**（12 个带文字的功能入口） | 顶部是 36px 的**按钮行**（4 个按钮 + 1 个下拉 + 1 个伪搜索框 + 2 个图标） | 信息密度与可发现性：Navicat 的功能入口一眼可见，我们的藏进菜单 | `MainToolBar.java:33-35, 79-82` |
| S2 | **表清单是带元数据列的表格**（行数 / 数据长度 / 引擎 / 创建日期 / 注释） | 表清单**只有表名**，点开就是数据网格 | 少了「一眼看清表有多大、什么时候建的」这一层 | `SchemaService.java:31-33, 76` |
| S3 | 对象树有 **表 / 视图 / 函数 / 用户 / 其它** 五类分组 | 只有 **表 / 视图** 两类 | 内省能力直接反映在树骨架上 | `SchemaService.ObjectKind` 只有 2 个值 |
| S4 | 左面板底部有**搜索框 + 过滤 + ★ + 折叠** | 搜索框在**顶部**（第 2 块），★ 在「收藏」区 | 布局与 Navicat 不一致，收藏入口位置不同 | `SideBar.java:288-289` |
| S5 | 标签栏第一个是**「对象」首页标签**（不可关闭） | 首页是内容区的欢迎卡片 | 导航模型不同：Navicat 用标签，我们用卡片 | `TabKind` 只有 6 个常量，无 HOME |
| S6 | 菜单栏有独立的**「收藏夹」顶级菜单** | 收藏只有侧栏星标（**且不落盘**） | 收藏缺少全局入口 | 全仓 `收藏夹` 只出现在文档里，0 处 Java 代码 |

**结论**：功能清单之外，Navicat 的**窗口骨架**本身也是产品力的一部分。
我们的骨架在「数据页 / 查询页」上已经接近，但「对象浏览」这一层还差 S1–S6 这一整套。

---

## 0.2 ⚠️ 文档与代码的 16 处对不上（本轮逐文件复核发现）

上一版文档里有 16 处结论**已经不成立**。其中 4 处是**安全语义**问题，
如果继续按文档推进，会以为已经修好了。

### 0.2.1 安全语义（必须优先纠正）

| # | 文档声称 | 代码实际 | 证据 | 影响 |
|---|---|---|---|---|
| **D1** | `NAVICAT-TODO.md` 4.5-4 ✅「未知语句头按**管理操作**处理（**fail-closed**）、复用 `SqlSplitter` 做字符串/注释安全切分、`WITH` 体内写语句扫描」 | **完全没做**：仍是 `return Danger.READ_ONLY`（fail-open）；仍用 `sql.split(";")` 裸切分；`WITH` 仍在只读白名单 | `DangerClassifier.java:39, 65, 104-105`；`git diff HEAD -- core/adb-safety` **无改动**；测试方法名就叫 `未知语句failOpen`（`DangerClassifierTest.java:139`） | **「WITH cte AS (…) DELETE FROM t」被判 READ_ONLY → 不弹确认框直接执行**。这是绕过写操作的路径。 |
| **D2** | 同上，声称复用 `SqlSplitter` 做「字符串/注释安全切分」 | 未复用。`core/adb-safety` **不依赖** `adb-sql`，用裸 `String.split(";")` | `DangerClassifier.java:65` | 字符串里的分号会把语句切碎，误判等级 |
| **D3** | `Danger.java:9-14` 与 `NAVICAT-TODO.md` §10 A6 写「**三层**写门禁」，第三层 = 驱动侧复查 | 当时**第三层不存在** —— `DbSession.execute(String)` 没有 `danger` 参数 | `DbSession.java:56`、`JdbcSession.java:135` | ✅ **本轮已补**（见 §0.3）：`DbSession.execute(sql, danger)` 强制声明等级，驱动侧再判一次 |
| **D4** | `docs/NAVICAT-GAP.md` §1.3 / `NAVICAT-TODO.md` A11「结果截断静默 ✅ 已修」 | **只修了查询页**。`JdbcSession.query()` 算出了 `truncated` 却**丢掉了**：`new RowPage(..., List.of(), false)`；`RowPage` **没有** truncated 字段 | `JdbcSession.java:119-121`、`RowPage.java:17-30` | 数据页走 `query()` 路径仍**静默截断**；`RowPage.truncatedColumns` 永远是空列表 |

### 0.2.2 交互 / 图标（文档说没做，其实在做）

| # | 文档声称 | 代码实际 | 证据 |
|---|---|---|---|
| **D5** | `NAVICAT-GAP.md` §1.3「`⌘C`/`⌥⌘C`/`⇧⌘C`/`⌘A` ⛔ 代码里 `KeyCode.C`/`KeyCode.A` **一次都没出现**」 | **四个都已实现**，还有门禁 `DataGridPageTest.copyShortcutsAndSelectAllWork` | `DataGridPage.java:273-292` |
| **D6** | `NAVICAT-GAP.md` §1.4「结果集截断到 1000 行，**且不告诉用户被截断了**」 | 查询页**已提示**「⚠ 结果已截断，上限 1000 行」 | `QueryPage.java:807-814` |
| **D7** | `NAVICAT-GAP.md` §2.4「Toast 时长**没有**按是否带撤销区分」 | **已分三档**：1.8s / 6s（带撤销）/ 4s（不可撤销） | `AppShell.java:1055, 1065`；`AppShellTest.toastDurationsComeInThreeTiers` |
| **D8** | `NAVICAT-GAP.md` §2.4「`ConnectionsPage.confirmDelete` 的 OK 按钮会成默认按钮 → 回车 = 删除」 | **已修**：改用 `DestructiveConfirm`，破坏性按钮 `OTHER`，默认按钮钉在「取消」 | `ConnectionsPage.java:190-203`、`DestructiveConfirm.java:42-52` |

### 0.2.3 计数与统计口径

| # | 文档声称 | 代码实际 | 证据 |
|---|---|---|---|
| **D9** | README「30 个测试类 / 246 条测试」 | **已纠正**（当前 38 类 / 345 条，0 失败） | 见 §7.1 |
| **D10** | `NAVICAT-TODO.md`「32 个测试类 / 291 条测试」 | **已纠正**（291 从不存在） | 见 §7.1 |
| **D11** | `docs/NEXT-STEPS.md`「32 个测试类 / 275 条测试」 | **已纠正**（275 从不存在） | 见 §7.1 |
| **D12** | `docs/ARCHITECTURE.md`「65 个非颜色令牌永远不会进 CSS」 | 是 **60** 个（`size` 40 + `number` 11 + `duration` 4 + `font` 3 + `easing` 2） | `tokens.json` `meta` |
| **D13** | `NAVICAT-GAP.md` §3.1「工具栏（`MainToolBar`，10 个元素）」，表里只列了 8 个 | 代码是 **10 个直接子节点**（8 个可见 + 2 个 `Region` 弹性占位） | `MainToolBar.java:62-63, 71-72, 79-82` |

### 0.2.4 「✅ 已完成」但只做了一半

| # | 文档声称 | 代码实际 | 证据 |
|---|---|---|---|
| **D14** | 4.5-5 ✅「图标统一：`TabKind.STRUCTURE` 由 `⚙` 改 `☰`（拆开与 SETTINGS 的碰撞）」 | 标签栏图标改了，但**数据页工具栏的「结构」按钮仍是 `⚙ 结构`**，与 `TabKind.SETTINGS` 的 `⚙` 仍然撞车 | `DataGridPage.java:533` vs `TabKind.java` |
| **D15** | `docs/UI-GUIDE.md` §3.1「表格内快捷键 …… **⛔ 后四个尚未实现**」 | 已实现（D5） | `docs/UI-GUIDE.md:94` |
| **D16** | `docs/UI-GUIDE.md` §3.2「**分页**：只有 `‹ 上一页` / `下一页 ›`，没有首末页与跳页」 | 4.5-20 已补 `⇤`/`⇥`/`跳至页` | `DataGridPage.java:766-795` |

### 0.2.5 其它

| # | 问题 | 证据 |
|---|---|---|
| **D17** | `docs/screenshots/` 的 6 张图**已过期**：p2-users.png 的分页条**没有** `⇤`/`⇥`/`跳至页`；p4-structure.png 的列清单**显示 `注释` 列**（4.5-22 已按方言隐藏） | 截图时间 `Oct 21 00:43`，早于 4.5-20 / 4.5-22 |
| **D18** | `core/adb-declarations` 的 `DeclarationLoader.java:36` javadoc 写「两者的解析结果必须完全相同，这本身要有一条测试守着」——**没有这条测试**，且 `loadFromClasspath()` 实际是坏的 | `DeclarationLoader.java:119-151` |
| **D19** | `DeclarationLoader.java:27` 的 `{@link DeclarationCheckMojo}` **指向一个不存在的类** | 全仓 0 命中 |

### 0.3 本轮关闭的差距（26 项）

第四轮复核后，**B0–B4 批次全部落地**，下列差距已经关掉：

| 域 | 关闭的项 | 落点 |
|---|---|---|
| **安全语义** | 危险分级 fail-open → fail-closed；`WITH … DELETE` 不再绕过确认；`CALL`/`DO`/`MERGE`/`UPSERT`/`COPY` 不再判只读；字符串/注释里的分号不切碎语句 | `DangerClassifier` + `adb-sql` 依赖 |
| **安全语义** | 数据页保存路径接入分级（以前完全不分类）；高风险额外走一级 `DestructiveConfirm` | `DataGridPage.planDanger` |
| **安全语义** | **第三层门禁（驱动侧复查）落地**：调用方必须声明危险等级，驱动侧自己再判一次，实际更危险就拒绝执行 —— 前两层都在 UI 侧，程序化执行路径现在有纵深防御 | `DbSession.requireDeclared` |
| **安全语义** | 批量「设空串」不得改主键列（VARCHAR/TEXT 主键会被清空） | `DataGridPage.applyColumnValue` |
| **安全语义** | 保存预览弹窗默认按钮是「取消」，不是「执行」（回车不再执行破坏性 SQL） | `DataGridPage.buildSavePreviewDialog` |
| **安全语义** | macOS 钥匙串口令改走 stdin，不再进 argv | `KeychainCredentialStore` |
| **安全语义** | `options` 敏感键落盘前剔除；且不得覆盖钥匙串口令 | `ConnectionProfile.sanitizedOptions` |
| **正确性** | `RowPage` 带 `rowsTruncated`，数据页不再静默截断；候选值面板的高基数判定不再失效 | `RowPage` / `ColumnFilterPanel` |
| **交互** | 侧栏树右键菜单、标签栏右键菜单并入 `ActionRegistry`（能在 ⌘K 搜到） | `SideBar` / `TabBar` |
| **交互** | 工具栏图标按钮绑定动作 `enabled`；`connections.manage` / `connection.reconnect` 进菜单 | `ActionRegistry.iconButton` / `MenuBarFactory` |
| **交互** | 动作被禁用时给出解释，不再静默 no-op | `Action.setOnDisabled` |
| **交互** | 状态栏三个假字段接真实数据：「DEV」→ 当前方言，「未选择」→ 选中行数，「自动提交」→ 真实事务状态 | `StatusBar` |
| **交互** | `⌘.` 现在能真正取消数据页的加载与保存 | `JdbcSession` + `DataGridPage.cancel()` |
| **交互** | 侧栏「查询历史」「诊断」接真功能（以前是空壳页） | `HistoryPage` / `DiagnosticsPage` |
| **架构** | 主题热切换空白帧**从根上解决**：主题 CSS 改类选择器，不再换样式表列表 | `GenTokens` / `ThemeManager` |
| **架构** | 密度档位覆盖数据网格行高（以前只影响对象树） | `DataGridPage.setDensity` |
| **架构** | 切连接泄漏会话已修（回收没有标签页引用的会话） | `AppShell.closeOrphanSessions` |
| **架构** | `loadFromClasspath()` 修好：爆炸 classpath 与打包 jar 两条路径都能加载 | `DeclarationLoader` |
| **架构** | **方言抽象层（A1）**：加一种数据库从「改 5 个文件」变成「写一个 Dialect 实现」；`SchemaService` / `StructureSqlBuilder` 里一行方言专属 SQL 都没有 | `Dialect` + 3 实现 + `DialectBranchAuditTest` |
| **图标** | `⚙` 撞车彻底拆开；新增「符号 → 语义唯一」门禁 | `IconAuditTest` |
| **方言** | **方言抽象层（A1）**：加一种数据库从「改 5 个文件」变成「写一个 Dialect 实现」；`SchemaService` / `StructureSqlBuilder` 里没有方言专属 SQL | `Dialect` + 3 实现 + `DialectBranchAuditTest` |
| **方言** | **第三层危险门禁（驱动侧复查）**：调用方必须声明危险等级，驱动侧再判一次，实际更危险就拒绝 | `DbSession.requireDeclared` |
| **统计** | surefire 统计口径失真已修（`tools/TestCount.java` 按 `<testcase>` 计数 + 下限断言） | `tools/TestCount.java` |
| **设计令牌** | **令牌体系收敛**：112 → 60 个，全部被用到（CSS 引用或 Java 常量消费）；`risk-*` 家族终于用上了（四级风险各一套配色）；侧栏宽度 / 栏高 / Toast 时长的魔数换成令牌 | `tokens.json` + `TokenParityTest.everyTokenIsActuallyUsed` |
| **文档** | 6 张截图已重拍（补上 `⇤/⇥/跳至页`、去掉多余的「注释」列） | `docs/screenshots/` |

**仍然开放**的差距见 §7.2。

---

## 1. 功能矩阵

### 1.1 连接管理

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 连接列表、搜索、收藏 | ✅ | `ConnectionsPage`：搜索框 + 「只看收藏」+ ★ 标记 |
| 新建连接向导 | ✅ | `ConnectionWizard` 三步：类型与名称 → 参数 → 测试与保存（导航按钮已挪进内容区） |
| 测试连接 | 🟡 | `AppShell.testConnection` 跑 `SELECT 1`，但**在 JavaFX 线程同步执行**（`AppShell.java:255-256`），慢库会卡住界面 |
| 口令存钥匙串 | 🟡 | `KeychainCredentialStore`（macOS `security`）/ `SecretToolCredentialStore`（Linux `secret-tool`）；**Windows 落到内存**（`CredentialStore.platform()` 无 WinCred 分支） |
| 编辑 / 复制 / 删除连接 | ✅ | `ConnectionsPage` 工具条 |
| **多连接同时打开** | 🟡 | `SessionManager` 按 id 存多会话，`TabItem.connectionId` 也支持，但 `AppShell` 只有**一个 `activeProfile` / 一个 `session` 字段**（`AppShell.java:89-91`），切标签会切当前连接 |
| **会话泄漏** | ⚠️ | `AppShell.connectProfile()` 打开新会话（`:187`）却**从不关闭旧会话** → 每切一次连接就多一条活连接 |
| **连接属性页**（读超时/SSL/字符集/连接池） | ⛔ | `ConnectionProfile.options` 是自由 Map，向导里没有 UI 编辑它 |
| **schema / catalog 树** | 🟡 | **schema 有了**：切换器 + `SET search_path`；**catalog 仍缺**（MySQL 的多库场景），`getCatalogs()` 没调用 |
| **连接分组 / 标签** | ⛔ | — |
| **最近连接排序** | ⛔ | `ConnectionProfile.lastUsedAt` 字段存在且会写，但列表不按它排序 |
| **`options` 脱敏** | ⚠️ | `ConnectionProfile.withPasswordlessOptions`（`:90`）**零调用**；`options` 明文落盘，且**能覆盖钥匙串口令**（`JdbcSession.java:60` 在设置 `password` **之后**才 apply options） |

### 1.2 对象树

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 连接 → 表 / 视图分组 | ✅ | `SideBar.buildTreeModel` |
| 双击打开 | 🟡 | 树 `setOnMouseClicked` 与单元格局部 `setOnMouseClicked` **都会触发**，靠 `TabBar.open` 去重兜底（`SideBar.java:235-248, 588-594`） |
| 右键菜单 | 🟡 | 表/视图节点 7 项、连接节点 3 项、组节点 1 项；**没有重命名、没有新建表、没有导出、没有表属性**；**且不经过 `ActionRegistry`**（→ 4.5-8） |
| 收藏 | 🟡 | `SideBar.starred` **只在内存**（`SideBar.java:56`），退出即丢；`ConnectionProfile.favorite` 才落盘 —— **两套互不相干的收藏系统** |
| 过滤搜索 | ✅ | 侧栏搜索框，子串匹配（**位置在顶部，与 Navicat 相反**，见 S4） |
| **过程 / 函数 / 触发器 / 序列 / 事件节点** | ⛔ | `SchemaService.listObjects` 只查 `{"TABLE","VIEW"}`（`:76`）；`ObjectKind` 只有 2 个值 |
| **拖拽建表 / 拖拽改字段类型** | ⛔ | 全仓 `DragDrop` 0 命中 |
| **多选 + 批量操作** | ⛔ | `TreeView` 保持默认 `SelectionMode.SINGLE` |
| **对象树按 schema 展开** | 🟡 | 侧栏有 **schema 切换器**（`Sidebar.setSchemas`，>1 个 schema 才显示）；PG 用 `SET search_path` 切。**catalog（MySQL 多库）仍未接** |
| **表清单（带元数据列的表格）** | ⛔ | 骨架 S2，见 §0.1 |

### 1.3 数据网格

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 服务端分页 / 排序 / 筛选 | ✅ | `DataSqlBuilder`；`OFFSET` 上限 500000 |
| 列头值筛选（候选值 + 计数 + 级联） | ✅ | `ColumnFilterPanel`，高基数（>1000）降级为精确匹配 |
| 条件构造器 | ✅ | `ConditionDialog`，12 个操作符 |
| 行内编辑 + 类型转换 | ✅ | `ValueCell` + `CellValueParser` |
| 撤销 / 重做 | ✅ | `EditBuffer`，批量操作只占一个栈位 |
| 保存前 SQL 预览 + 单事务 | ✅ | `DataGridPage.confirmSqlPreview` + `EditSqlBuilder.execute` |
| **保存确认的默认按钮** | ⚠️ | `confirmSqlPreview` 用 `ButtonData.OK_DONE`（`:472`）→ **回车 = 直接执行**，违反项目自己的「默认按钮不是破坏性动作」规则。`DestructiveConfirm` **没有**用在这里 |
| 保存后反向撤销 | 🟡 | `EditSqlBuilder.inversePlan`；**主键为空的新增行无法反删**，只覆盖最近一次保存 |
| 批量：TSV / INSERT / 克隆 / 设 NULL / 空串 / 删除 | ✅ | `DataGridPage.buildBatchBar` |
| **批量改主键列** | ⚠️ | `applyColumnValue`（`:2050-2085`）**没有主键守卫**，而单元格编辑路径有（`:2362`）。VARCHAR/TEXT 主键可被批量「设空串」→ 生成 `UPDATE t SET pk='' WHERE pk=?` → **数据损坏** |
| 单元格详情抽屉（文本/JSON/Hex/图片/元数据） | ✅ | `CellDetailsDrawer` 五页签，大字段懒加载 |
| CSV 导出 / TSV 复制 / SQL 复制 | ✅ | `CsvExporter`，UTF-8 BOM |
| 布尔列点击切换 | ✅ | `ValueCell` CheckBox |
| 快捷键 `⌘C`/`⌥⌘C`/`⇧⌘C`/`⌘A` | ✅ | `DataGridPage.java:273-292`（上一版文档记为 ⛔，见 D5） |
| 分页 `⇤`/`⇥`/`跳至页` | ✅ | `DataGridPage.java:766-795`（上一版文档记为 ⛔，见 D16） |
| 三态 + 重试 | ✅ | `showLoading`/`showError`/`hideState`，错误态有「重试」按钮（`:134, 2628-2629`） |
| **冻结 / 固定列** | ⛔ | 全仓 `freeze`/`frozen`/`pinned` 0 命中 |
| **列汇总（SUM / AVG / COUNT 脚注行）** | ⛔ | 没有脚注行 |
| **列头拖拽重排** | ⛔ | 顺序**能持久化**（`layoutKey("order")`）但**没有任何运行时改序入口**，读回来恒等于写进去的顺序 |
| **单元格区域选择（拖选 / Shift 范围选）** | ⛔ | 只有行选择（`SelectionMode.MULTIPLE`）+ 勾选列 |
| **编辑后跳到下一格（Tab / ↓）** | ⛔ | `commitEditor` 只提交 + 刷新，不移动焦点 |
| **自增列插入 / 主键批量改** | ⛔ | 全仓 `autoIncrement`/`AUTO_INCREMENT` 只在死代码模块命中 |
| **整表导出（不限当前页）** | ⛔ | 导出菜单 3 项全部是「当前页」 |
| **行级导航（首行 / 末行 / 跳到第 N 行）** | ⛔ | 只有页级导航 |
| **列注释显示** | 🟡 | UI 位置都在，但 SQLite 无列注释（`DialectKind.supportsColumnComment()`）；PG 的 COMMENT 因 `TypeMapper` 不读 `getColumns()` 而恒丢 |

### 1.4 查询编辑器

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 语法高亮 | ✅ | `SqlHighlighter` + `adb-sql` 词法内核 |
| 多语句、逐条结果页签 | ✅ | `SqlSplitter` 跳过字符串/注释/`BEGIN…END` 里的分号 |
| 执行当前 / 执行全部 / 取消 | ✅ | ⌘R / ⇧⌘R / ⌘. |
| 危险分级 + 二次确认 | 🟡 | `DangerClassifier` 四级 + `QueryPage.confirmDanger`；**但分类器本身 fail-open**（见 D1） |
| 执行计划 | ✅ | SQLite `EXPLAIN QUERY PLAN`，PG/MySQL `EXPLAIN`；`EXPLAIN` 不触发确认 |
| 错误定位到编辑器 | ✅ | `ErrorLocator`（PG/MySQL/Oracle 三种格式） |
| 查询历史 | ✅ | 最近 50 条，去重，落 `java.util.prefs` |
| 查找替换 / 格式化 / 注释 | ✅ | `⌘F` / `⌥⇧F` / `⌘/` |
| 结果截断提示 | ✅ | 「⚠ 结果已截断，上限 1000 行」（`:807-814`） |
| **括号匹配 / 代码折叠** | ⛔ | `SqlEditor` 只挂了行号 + 高亮 |
| **结果集排序 / 列宽持久化** | ⛔ | `column.setSortable(false)`（`:784`）；`QueryPage` 收了 `SettingsStore` 但只用于 `QueryHistory`，列宽固定 140 |
| **结果集分页** | ⛔ | 一次性装进 `ObservableList`，硬上限 1000 |
| **变量 / 参数替换** | ⛔ | — |
| **代码片段 / 模板库 / 已保存查询** | ⛔ | 侧栏「已保存查询」是空壳；`query.save` 只存 `.sql` 文件 |
| **多结果集（一条语句返回多个结果集）** | ⛔ | MySQL 声明里就写了 `multipleResultsets: false` |

### 1.5 结构 / DDL

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 列 / 索引 / 外键 / 触发器列表 | ✅ | `StructurePage` 五个 Tab |
| DDL 查看 | ✅ | SQLite 取 `sqlite_master.sql`；MySQL `SHOW CREATE TABLE`；**PG 只能生成等价 DDL**（UI 已标注） |
| 复制 DDL / 复制 SELECT | ✅ | — |
| 新增列 / 改可空 / 改默认值 / 重命名列 | ✅ | `StructureSqlBuilder`；SQLite 走整表重建 |
| 危险操作二次确认 + 展示 SQL | ✅ | `StructurePage.defaultConfirm` 两级确认 |
| **新建 / 删除索引** | ⛔ | 只读 |
| **新建 / 删除外键** | ⛔ | 只读 |
| **新建 / 删除触发器** | ⛔ | 只读 |
| **新建 / 重命名 / 删除表** | ⛔ | 只读，且侧栏没有入口 |
| **CHECK 约束** | ⛔ | 内省根本没读 |
| **PostgreSQL 注释、默认值、序列** | ⛔ | `TypeMapper` 不读 `getColumns()`，PG 的 DEFAULT / COMMENT 全丢 |

### 1.6 导入导出

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 当前页 CSV / TSV / SQL | ✅ | — |
| **导入向导（文件 → 目标表 → 字段映射 → 预览 → 执行）** | ⛔ | 侧栏「导入 / 导出」是空壳（`WorkspaceView.java:371-373` 渲染「该工具只保留入口」） |
| **导出向导（整表 / 查询结果 / JSON / Excel）** | ⛔ | 同上 |
| **大文件流式** | ⛔ | — |
| **失败行报告 / 跳过策略** | ⛔ | — |

### 1.7 工具与运维

| Navicat 能力 | 我们 | 证据 / 差距 |
|---|---|---|
| 命令面板 | ✅ | `CommandPalette` ⌘K |
| 诊断（表/索引健康） | ⛔ | 空壳 |
| **侧栏「查询历史」入口** | ⚠️ | 侧栏按钮开的是**空壳 TOOL 页**；真正的 `QueryHistory`（prefs 持久化、可用）只挂在 `QueryPage` 上 —— **有能用的功能没入口，有入口不能用** |
| 计划任务 / 自动运行 | ⛔ | 明确不做 |
| 事务管理（显式 BEGIN/COMMIT） | 🟡 | `StatusBar.setTransaction` **零调用**，永远显示「自动提交」 |
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
| 可访问性（读屏标签、对比度） | 🟡 | `IconAuditTest` 守住了 Tooltip + accessible text；**对比度仍无自动检查**；图标**语义唯一性**无门禁（→ 4.5-5 只拆了一半，见 D14） |

---

## 2. 交互审计

### 2.1 动作注册表的漏网之鱼（4 处）

`ActionRegistry` 是「菜单 / 工具栏 / 右键 / 命令面板」的唯一真相，但实际有 4 处破例：

| 破例 | 位置 | 后果 |
|---|---|---|
| 侧栏树的右键菜单全部直接调私有方法 | `SideBar.menuFor`（`:435-490`） | 表/视图节点 7 个菜单项**都不在命令面板里**，也没有统一 enabled 判断 |
| 侧栏 5 个导航按钮直接开标签页 | `SideBar.buildExpanded`（`:279-286`） | 与工具栏同名动作走两条代码路径；且**全是空壳页** |
| 工具栏 3 个图标按钮不走 `enabled` 绑定 | `MainToolBar.iconButton`（`:100-109`） | `↻` `◐` `⚙` 永远看起来可点 |
| **标签栏右键菜单手写 MenuItem** | `TabBar.buildTab`（`:209-217`） | `关闭`/`关闭其他`/`全部关闭` 三项**不走注册表**；同一功能的 `tab.close` 动作在菜单里有、在标签栏右键里没有 |

补充：`Action.run()`（`Action.java:75-79`）在 `enabled == false` 时**静默 no-op**，
用户点了没反应、也没有任何提示 —— 这是「禁用态必须视觉可区分」的反面案例。

### 2.2 两个只有注册、没有入口的动作

`connections.manage`（`AppShell.java:432`）和 `connection.reconnect`（`:541`）
**只注册、没有任何菜单入口**，只能靠 ⌘K 找；而 ⌘K 又只显示 enabled 的动作。

### 2.3 状态栏有 3 个永远不变的字段

| 字段 | 状态 | 证据 |
|---|---|---|
| `DEV` 环境标记 | `setEnv` **零调用**，永远是 `DEV` | `StatusBar.java:46` |
| `未选择` | `setSelection` **零调用**，永远显示「未选择」 | `StatusBar.java:64` |
| `自动提交` | `setTransaction` **零调用**，永远显示「自动提交」 | `StatusBar.java:72` |

### 2.4 线程与取消语义

| 问题 | 证据 | 影响 |
|---|---|---|
| **连接与内省跑在 JavaFX 线程** | `AppShell.connectProfile`（`:177-211`）同步 `SessionManager.open` + `listObjects`；`testConnection`（`:255-256`）同步 `SELECT 1` | 慢库 / 大库会让**整个窗口卡住** |
| **全局单线程执行器** | `AppShell.dbExecutor`（`:69-73`）一个 `adb-db` 线程被数据页 / 查询页 / 结构页 / 对象刷新共用 | A 连接的慢查询**阻塞** B 连接的一切操作 |
| **`⌘.` 取消不到数据页** | `JdbcSession.activeStatement` **只在 `execute()` 里赋值**（`:138`），`query()`（`:106-124`）/ `update()`（`:127-132`）不赋值 | 数据页加载 / 保存**无法真正取消** JDBC 语句 |
| **没有语句超时** | 全仓 `setQueryTimeout` **0 命中** | 长查询只能等数据库或用户取消 |
| **`query()` 丢掉截断标记** | `JdbcSession.java:119-121` | 数据页仍**静默截断**（见 D4） |

### 2.5 数据正确性风险（本轮新发现）

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| **R1** | **批量「设空串」能改主键列** | `applyColumnValue`（`:2050-2085`）无主键守卫；单元格编辑路径有（`:2362`）；`batchEmpty` 对文本列启用（`:2014`） | VARCHAR/TEXT 主键被批量清空 → `UPDATE t SET pk='' WHERE pk=?` → **主键损坏 / 唯一约束冲突** |
| **R2** | **保存预览弹窗的回车 = 执行** | `confirmSqlPreview`（`:468-488`）用 `ButtonData.OK_DONE` | 与 UI-GUIDE「危险操作默认按钮不是破坏性动作」自相矛盾；**项目已有 `DestructiveConfirm` 却没用在这里** |
| **R3** | **切连接泄漏会话** | `AppShell.connectProfile`（`:187`）开新会话不关旧会话 | 每次切连接多一条活连接；长时间使用会打满数据库连接数 |
| **R4** | **`options` 可覆盖钥匙串口令** | `JdbcSession.java:57-60`：先 `setProperty("password", …)` 再 `spec.options().forEach(props::setProperty)` | 落盘的明文 options 反而能**覆盖**安全存储的口令 |

---

## 3. 按钮审计

### 3.1 主工具栏（`MainToolBar`，8 个可见元素 / 10 个子节点）

| # | 元素 | 形态 | 绑定 | 备注 |
|---|---|---|---|---|
| 1 | `＋ 连接` | 文字按钮 | `new-connection` ✅ | 覆盖了动作文案「新建连接…」 |
| 2 | `↻` | 图标按钮 | `refresh` ❌ 不绑 enabled | Tooltip「刷新 (F5)」 |
| 3 | `SQL` | 文字按钮 | `new-query` ✅ | 覆盖了动作文案「新建查询」 |
| 4 | 分隔线 | — | — | — |
| 5 | 连接下拉 | ComboBox | — | `name · KIND`；**放在分隔线右侧**，视觉上像右侧区 |
| 6 | `⌘K 搜索对象或命令` | **假搜索框（Button）** | `command-palette` ❌ | 不可输入 |
| 7 | `◐` | 图标按钮 | `theme.toggle` ❌ | 主题 |
| 8 | `⚙` | 图标按钮 | `settings` ❌ | 设置 |

**缺口**：没有「新建表」「保存查询」「停止」按钮；没有 Navicat 式**大图标功能栏**（S1）；
连接下拉位置错；`activeConnectionId` 字段只写不读（`:20, 84, 89`）；
`status` 参数、`connectionName` 参数都是死参数（`:23-29`）；`setConnections` 被调用两次（`:54, 85`）。

### 3.2 数据页工具栏（`DataGridPage`，12 个元素）

`↻刷新` `⚙结构` `＋新增` ‖ `↶撤销` `↷重做` `保存` ‖ `筛选` `清除条件` `列▾` `视图▾` `导出▾` ‖ `⌕本页搜索`

- `保存` 按钮文案随脏计数变宽（`保存` → `保存 3`），按钮宽度跳动（`:369-370`）；不脏时 disabled（`:368`）✅
- 「结构」按钮仍用 `⚙`，与设置页撞车（见 D14）
- **批量条没有「导出选中行」入口**（只有复制）
- 「导出」菜单 3 项全是「当前页」

### 3.3 结构页工具栏（`StructurePage`，7 个元素）

`＋新增列` `修改列` `重命名` `删除列` ‖ `↻刷新` `复制DDL` `复制SELECT`

- **缺口**：没有「新建索引 / 外键 / 触发器 / 表」的入口
- 列清单行右键：修改列 / 重命名 / 删除列 / ‖ / 复制列名 / 复制 SELECT；双击行 = 修改列（`:219-253`）
- `StructurePage.java:12` 有个**未使用的 `Danger` import**（页面用 `StructurePlan.approvalRequired()`，不走 `DangerClassifier`）

### 3.4 查询页工具栏（`QueryPage`）

`▶执行` `▶▶全部执行` `执行计划` `格式化` `保存` `清空` `只读徽标` ‖ `历史▾`
结果页签内：`复制TSV` `导出CSV`

- 风险徽标是**普通灰底**（`:187-189`），与 Navicat 的彩色分级不够醒目
- **没有「变量」入口**，**没有「保存为已保存查询」**

### 3.5 连接管理页工具栏（`ConnectionsPage`，6 个元素）

`＋新建连接` `打开` `编辑` `复制` `删除` `↻刷新`

- **缺口**：没有行内「测试连接」按钮、没有「导出 / 导入连接配置」

### 3.6 标签栏（`TabBar`）

`＋` 新建查询按钮（`:55-59`，**裸 Button，无 enabled 绑定**）
标签：图标 + 标题 + 脏点 `●N` + `✕`；中键关闭（`:202-203`）

### 3.7 侧栏（`SideBar`，展开态 7 块）

`对象`标题+`«`折叠 → `⌕ 搜索表、视图` → `◈ 连接管理`+连接下拉 → `收藏`区 → `连接`区(树) → `工作台`区 → `工具`区

- 侧栏 rail（折叠态 48px）：`»` `◈` `★` `▦` `⌕` `◆` + `DEV` 标签（`:316-345`）
- **工作台 4 项 + 工具 1 项全是空壳**：`查询历史`/`已保存查询`/`诊断`/`导入导出` → `openTool()` → `WorkspaceView` 渲染「该工具只保留入口」
- `⚙ 设置` → `settingsSkeleton()`（`WorkspaceView.java:383-402`）

---

## 4. 弹窗清单

| 弹窗 | 触发 | 状态 | 问题 |
|---|---|---|---|
| 保存修改（SQL 预览） | 数据页 `⌘S` | ⚠️ | **默认按钮 = 「执行」（回车即执行）**，违反自己的规则（R2） |
| 添加筛选条件 | 数据页「筛选」 | ✅ | 12 个操作符 |
| 单元格详情抽屉 | 单元格右键 | ✅ | 五页签，懒加载，Esc 关闭 |
| 结构变更（列） | 结构页 | ✅ | 非法列名行内报错、对话框不关（4.5-12） |
| 结构变更确认（两级） | 结构页 | ✅ | 展示将要执行的 SQL |
| 连接向导 | 工具栏 / 侧栏 | ✅ | 三步，导航按钮在内容区，常驻错误行 |
| 删除连接 | 连接页 | ✅ | `DestructiveConfirm`，默认按钮是「取消」 |
| 确认执行（危险 SQL） | 查询页 | ✅ | `QueryPage.confirmDanger` |
| 关于 / 快捷键 | 帮助 | ✅ | — |

**共性缺口**：
1. 没有统一的「错误详情」弹窗 —— 错误只落在状态栏一行（`showErrorDialog` 只有保存失败用）。
2. 没有**批量确认汇总页**（一次删 500 行只有一个「N 条变更 · 单事务执行」的 header）。
3. 没有「同名对象」检查、没有「表不存在 / 权限不足 / 连接断开」的**错误归类**。
4. 没有**统一弹窗工厂**：`DestructiveConfirm` 只被 4 处使用，数据页保存预览、结构变更确认、连接向导都没走同一套规范。

---

## 5. 图标审计

当前策略：**全部用 Unicode 符号**，无图标字体、无 SVG 资源。

### 5.1 清单（现状）

| 符号 | 码位 | 语义 | 位置 | 状态 |
|---|---|---|---|---|
| ▦ | U+25A6 | 表 | `TabKind.GRID`、`ObjectKind.TABLE`、侧栏 rail | ✅ |
| ▤ | U+25A4 | 表分组 | `SideBar` | ✅ |
| ◇ | U+25C7 | 视图 | `ObjectKind.VIEW`、`SideBar` | ✅ |
| ◈ | U+25C8 | 连接 | `TabKind.CONNECTIONS`、`SideBar`、欢迎页 logo | ✅ |
| ◆ | U+25C6 | 工具 | `TabKind.TOOL`、`SideBar` | ✅ |
| ☰ | U+2630 | 结构 | `TabKind.STRUCTURE` | ✅ 4.5-5 已拆 |
| **⚙** | U+2699 | **结构（数据页按钮）+ 设置（两处）** | `DataGridPage.java:533`、`TabKind.SETTINGS`、工具栏 | ⚠️ **仍撞车（D14）** |
| ⌨ | U+2328 | 查询 | `TabKind.QUERY` | ✅ |
| ↻ | U+21BB | 刷新 | 工具栏、连接页、结构页、数据页 | ✅ 已统一 |
| ◐ | U+25D0 | 主题 | 工具栏 | ✅ |
| ★ | U+2605 | 收藏 | `SideBar`、`ConnectionsPage` | ✅ |
| ⌕ | U+2315 | 搜索 | 侧栏 rail、数据页搜索框 | ✅ 已统一（U+2315 名义是「电话录音机」，但形状已统一） |
| ⌄ / ▽ | U+2304 / U+25BD | 筛选 | 列头筛选按钮 | ✅ 已改 `▽` |
| ⇅ | U+21C5 | 导入导出 | `SideBar` | ✅ |
| ⌷ | U+25F7 | 查询历史 | `SideBar` | ✅ |
| ✕ | U+2715 | 关闭 | 标签页、抽屉、查询页 | ✅ 已统一 |
| ● | U+25CF | 脏标记 | 标签页、状态栏 | ✅ |
| ⬢ | U+2B22 | BLOB | `CellRenderer` | ✅ |
| ✓ ✗ | U+2713 / U+2717 | 布尔 | `CellRenderer` | ✅ |
| **[PK]** | 文本 | 主键 | 列头、结构页 | ✅ 已换（不是 🔑） |
| • | U+2022 | NOT NULL | 列头 | ✅ |

### 5.2 仍然存在的问题

| # | 问题 | 证据 | 严重度 |
|---|---|---|---|
| **I1** | **`⚙` 一符两义没拆干净** | `DataGridPage.java:533` `new Button("⚙ 结构")` vs `TabKind.SETTINGS` `⚙` | 中 |
| **I2** | **图标语义唯一性没有门禁** | `IconAuditTest` 只断言「Tooltip + accessible text」，不检查「一个符号只表一个意思」 | 中 |
| **I3** | **跨平台缺字风险仍在** | `⚙ ▤ ▦ ⌕ ▇ ⬢ ⇅ ⌷ ◈` 属 Miscellaneous Technical / Geometric Shapes，Windows 常见字体可能替换或缺失 | 中 |
| **I4** | **两套字号符号混排** | `⌕`（U+2315 细线）与 `▦ ▤ ◇ ◈`（Geometric Shapes 粗线）视觉重量不一致 | 低 |
| **I5** | **无图标资源目录** | 没有 `resources/icons/`，长期方向（内嵌 SVG）没有任何起手 | 低 |

**结论**：图标体系需要**第二次**统一：① 补齐数据页「结构」按钮的字形；②
`IconAuditTest` 增加「符号 → 语义」唯一性断言（一张映射表 + 重复即失败）；
③ 长期迁移到内嵌 SVG 图标集（JavaFX 原生支持，无需图标字体、无缺字风险）。

---

## 6. 架构审计

### 6.1 分层现状（好的一面）

```
ui/adb-ui-shell → workbench/{adb-session, adb-introspect, adb-editing, adb-jdbc} → core/{adb-model, adb-safety, adb-sql}
```

依赖只向下，线程模型单一（全局 `adb-db` 单线程执行器），编辑按主键定位、
参数化 SQL、单事务保存 —— 这几条不变量守得很好。令牌唯一真相 + `TokenParityTest`
也是这个项目做得最扎实的部分之一。

### 6.2 缺口

| # | 缺口 | 影响 | 证据 |
|---|---|---|---|
| ~~A1~~ | ~~没有 Dialect 抽象层~~ **已做**：`Dialect` 接口 + 三个实现（SQLite / PostgreSQL / MySQL）承担全部方言差异；`SchemaService` 与 `StructureSqlBuilder` 里一行方言专属 SQL 都没有 | 门禁 `DialectBranchAuditTest` 扫源码，禁止方言分支散落 |
| A2 | **`declarations/` 是死代码** | 4 连接声明 + 4 方言词表 + 5 传输家族 + JSON Schema ≈ 1500 行，**运行时一次都没读**，且**不是任何模块的 Maven 依赖** | 全仓 `io.agenticdb.declarations` 只在模块内部命中 |
| A3 | **`loadFromClasspath()` 不可用** | 爆炸 classpath 下抛 `DeclarationException`；jar 下**静默返回空模型**（0 字节目录条目） | `DeclarationLoader.java:119-151` |
| ~~A4~~ | ~~参数风格 / 标识符引用被硬编码~~ **标识符引用已进方言层**（`Dialect.quote`）；**参数风格仍固定 `?`** —— 声明里的 `paramStyle: numbered` 无效 | `JdbcSession.bind` 永远用 `?` |
| ~~A5~~ | ~~元数据只读当前 schema~~ **schema 已落地**：`getSchemas()` 接上，侧栏有 **schema 切换器**（>1 个才显示），PG 走 `SET search_path` 切。**catalog 仍未接**（MySQL 多库场景）；`getExportedKeys()` / `getColumns()` 也没有 → PG 的 DEFAULT / COMMENT 仍丢 | `SchemaService.listSchemas` / `Dialect.setSchemaSql` |
| ~~A6~~ | ~~危险分级第三层门禁不存在；数据页保存不分类~~ **已做** | 三层门禁全部落地：`DbSession.requireDeclared` 做驱动侧复查；`DataGridPage.planDanger` 做保存路径分级 |
| A7 | **危险分级 fail-open** | `MERGE`/`UPSERT`/`CALL`/`COPY`/`SET`/`LOCK`/`ANALYZE`/`BEGIN`/`DO` 全部判为只读不拦；`WITH` 在只读白名单 → `WITH … DELETE` **无确认框** | `DangerClassifier.java:39, 104-105` |
| A8 | **多语句切分不看字符串/注释** | 裸 `String.split(";")` | `DangerClassifier.java:65` |
| A9 | **取消只覆盖查询页** | `query()` / `update()` 不记录 `activeStatement` | `JdbcSession.java:106-132` |
| A10 | **没有语句超时** | `setQueryTimeout` 全仓 0 命中 | — |
| A11 | **结果截断静默（数据页路径）** | `query()` 丢掉 `truncated`；`RowPage` 无该字段 | `JdbcSession.java:119-121`；`RowPage.java:17-30` |
| A12 | **连接池是 1 槽装饰器** | 池大小 1，连接**借出后从不归还**；每个连接档案一个 `HikariDataSource` | `ConnectionPool.java:38-52` |
| A13 | **macOS 钥匙串口令经 argv 泄漏** | `security add-generic-password -w <password>` → `ps` 可见 | `KeychainCredentialStore.java:40-41` |
| A14 | **`options` 明文落盘 + 可覆盖口令** | `withPasswordlessOptions` 零调用；options 在 password **之后** apply | `ConnectionProfile.java:90`；`JdbcSession.java:57-60` |
| A15 | **连接与内省跑在 UI 线程** | `connectProfile` / `testConnection` 同步 | `AppShell.java:177-211, 255-256` |
| A16 | **全局单线程执行器** | A 连接的慢查询阻塞 B 连接 | `AppShell.java:69-73` |
| A17 | **令牌体系名义与实现脱节** | 120 个令牌只有 **41** 个被 `app.css` 引用；**60 个非颜色令牌永远不会进 CSS**（生成器只导出 color） | `GenTokens.java:132-134` |
| A18 | **「跟随系统」只探测 macOS** | Windows / Linux 一律亮色 | `ThemeManager.systemPrefersDark`（`:86-115`） |
| A19 | **密度档位只影响对象树** | CSS 只改 `.adb-object-tree .tree-cell` 的 padding | `app.css` 密度段 |
| A20 | **`MAX_CELL_BYTES` 声明未用** | 20 KiB 上限常量无任何地方读 | `QueryLimits.java:78` |
| **A21** | **切连接泄漏会话** | 新会话开了不关旧的 | `AppShell.java:187` |
| **A22** | **主题探测每次都 shell out** | `defaults read -g AppleInterfaceStyle` 每次应用主题都跑一次（400ms 超时） | `ThemeManager.java:97-111` |
| **A23** | **死代码/死引用** | `DeclarationCheckMojo`（不存在的类）、`QueryOptions`、`PageSize`（只被测试引用）、`AppShell.session`（只写不读）、`MainToolBar.activeConnectionId`（只写不读）、`StructurePage` 未使用的 `Danger` import | 见各处 |
| **A24** | **surefire 统计口径失真** | 用 `@Nested` 的 4 个测试类，XML `<testsuite tests="0">`；按 `tests=` 属性统计会**少算 109 条** | `core/adb-model`、`adb-safety`、`adb-declarations` 的 XML |
| **A25** | **`declarations` 与运行时方言不一致** | `dameng.yaml` 声明了达梦，`DialectKind` 里**没有** `DAMENG` | `DialectKind.java:9-11` |

### 6.3 已经做对、值得保持

- 设计令牌唯一真相 + `TokenParityTest`（键一致 / 亮暗一致 / 禁止颜色字面量）。
- 编辑按主键定位 + 单事务 + 反向撤销 + 脏标记 + 关闭确认。
- 所有 JDBC 参数化，标识符才走方言引用。
- `DestructiveConfirm` 把破坏性默认按钮钉在「取消」上（4 处已接入）。
- 连接向导的 `DialogPane` 陷阱已经踩过并写进架构文档。

---

## 7. 补齐清单（按投入产出排序）

### 7.1 门禁基线（实测）

| 项 | 值 |
|---|---|
| 测试类 | **38** |
| 测试用例 | **345**（`@Test` + `@ParameterizedTest`） |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |
| 命令 | `./mvnw -B clean verify` |

> ⚠️ **统计口径**：用 `@Nested` 的 4 个测试类（`QueryLimitsTest`、`DangerClassifierTest`、
> `DeclarationLoaderTest`、`DeclarationValidationTest`）在 surefire XML 里写成
> `tests="0"`，实际用例在**内层类**的报告条目里。按 XML 的 `tests=` 属性统计会得到 159，
> **少算 109 条**。CI 看板与覆盖率工具都要按 `<testcase>` 计数。

| 模块 | 用例数 |
|---|---|
| `core/adb-model` | 30 |
| `core/adb-declarations` | 40 |
| `core/adb-safety` | 59 |
| `core/adb-sql` | 17 |
| `workbench/adb-jdbc` | 46 |
| `workbench/adb-introspect` | 25 |
| `workbench/adb-session` | 11 |
| `workbench/adb-editing` | 19 |
| `ui/adb-ui-core` | 4 |
| `ui/adb-ui-shell` | 92 |
| `testing/adb-fixtures` | 3 |

### 7.2 优先级

| 优先级 | 事项 | 理由 |
|---|---|---|
| ~~P0~~ ✅ | ~~危险分级 fail-open 收紧~~ **已关闭**（A7/A8） | `WITH … DELETE` 不再绕过确认框 |
| ~~P0~~ ✅ | ~~数据页保存接入 `DangerClassifier`~~ **已关闭**（A6 的 UI 侧） | 保存路径现在也分级，高风险走两级确认 |
| ~~P0~~ ✅ | ~~批量改主键列的数据损坏~~ **已关闭**（R1） | 批量路径补了主键守卫 |
| ~~P0~~ ✅ | ~~保存预览弹窗默认按钮~~ **已关闭**（R2） | 默认按钮钉在「取消」上 |
| ~~P0~~ ✅ | ~~`query()` 保留截断标记~~ **已关闭**（A11/D4） | `RowPage.rowsTruncated` |
| **P1** | 导入 / 导出向导 | Navicat 的招牌能力，目前完全是空壳入口 |
| **P1** | 对象树补触发器 / 视图 / 过程节点 | 内省已有触发器数据（`TriggerDef`），只是没接到树上 |
| **P1** | 表清单元数据列（行数 / 大小 / 引擎 / 创建日期 / 注释） | Navicat 对象浏览层的核心信息密度（骨架 S2） |
| **P1** | 顶部功能栏（大图标 + 文字的入口区） | 骨架 S1，直接影响功能可发现性 |
| ~~P1~~ ✅ | ~~真方言抽象层（Dialect 接口）~~ **已做** | 方言分支全进方言层，并有源码审计门禁守着 |
| **P1** | 多连接并行（**会话泄漏已修**，A21 ✅） | 连接管理做了一半，单连接是硬天花板 |
| **P1** | 侧栏收藏落盘 + 「收藏夹」顶级菜单 | 骨架 S6；用户明确感受过的缺陷 |
| **P2** | 侧栏「已保存查询」「导入导出」空壳入口处理 | P6 未到，先从侧栏撤掉而不是留死页面 |
| **P2** | 取消 / 超时下沉到 `query()` / `update()` | 数据页加载无法取消是体验硬伤 |
| **P2** | 冻结列 / 汇总脚注 / 单元格区域选择 / 编辑后跳格 | 数据网格的进阶四件套 |
| **P2** | 标签栏「对象」首页标签 + 侧栏搜索框移底部 | 骨架 S5 / S4 |
| **P2** | 令牌体系收敛（把 60 个非颜色令牌真正用起来或删掉） | 120 个令牌里 79 个是摆设 |
| **P2** | 图标二次统一 + 语义唯一性门禁（I1/I2） | `⚙` 撞车未拆干净 |
| **P2** | 动作注册表收口（4 处漏网 + 2 个无入口动作） | 菜单 / 工具栏 / 右键 / ⌘K 应当同源 |
| **P3** | jlink / jpackage / 签名公证 / 崩溃日志 | 没有分发就没有用户 |
| **P3** | 跨平台主题探测（Windows / Linux） | 「跟随系统」目前名不副实 |
| **P3** | `declarations/` 接进运行时 **或** 删除 | 1500 行死代码会误导后续开发 |
