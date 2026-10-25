# UI 对比与下一步开发方案 — Navicat ↔ AgenticDB

> 日期：2026-10-22 · 对照物：`docs/screenshots/navicat/navicat-tablelist.png`（Navicat Premium Lite 15 本机实拍）
> 本轮产出：`docs/screenshots/navicat/side-by-side-2026-10.png`（并排图）
>
> **比法说明**：不靠印象。所有数字都是**从截图里量出来的**（PIL 取像素带、
> 统计颜色占比），凡是"看起来"的结论都标了 ⬜ 与"量出来的"区分开。
> 参考图分辨率 1920×1050，本轮 1440×900 —— 因此**同时给出绝对值与占屏比例**。
>
> ⚠️ **本文是上一版（2026-10 早）**：并排图混用了 1920 / 1440 两种窗口宽度，
> 存在缩放不一致。最新测量、已落地改动与剩余差距见 **`docs/NAVICAT-PARITY-2026-10-V5.md`**；
> 本文保留作为历史记录，不再作为当前状态依据。

---

## 0. 一句话结论

本轮把 AgenticDB 的**信息密度**追平了 Navicat 的量级：
「第一条数据出现的高度」从 33% 屏高降到 **25.9%**（Navicat 18%），
斑马纹从 `#F1F5F9`（占屏 24.5%）降到 `#F7F9FB`（占屏 22.4%，与白底只差 3/255）。
剩下的差距集中在**对象树的层级深度**与**表清单的元数据列**上，
属于后端内省能力的缺口，不是排版问题 —— 已排进 P1。

---

## 1. 硬指标对比（像素量出）

| 指标 | Navicat | AgenticDB 本轮前 | AgenticDB 本轮后 | 判定 |
|---|---|---|---|---|
| 第一条数据行距窗口顶 | 190px / 1050 = **18.1%** | 284px / 861 = **33.0%** | 223px / 861 = **25.9%** | ⬜ 仍多 7.8 个百分点 |
| 顶部 chrome 层数 | 3（标题+工具 / 标签 / 面板工具条） | 6（应用栏/标签栏/对象头/切面条/工具栏/记录导航） | **3**（应用栏/标签栏/对象头含切面条） | ✅ 同层数 |
| 表格行高 | ~19px | 35px | 35px | ⬜ 任务书 §4.4 定 30/36，**故意不追** |
| 斑马纹 | **无**（纯白 + 1px `#DFDFDF` 分隔线） | `#F1F5F9`，占屏 **24.49%** | `#F7F9FB`，占屏 **22.36%**（Δ3 vs 白） | ✅ 观感已接近 |
| 行分隔线 | 1px `#DFDFDF` | 1px `#E2E8F0` | 1px `#E2E8F0` | ✅ 等价 |
| 表头底色 | `#FFFFFF` + 下边框 | `#F1F5F9` | `#F1F5F9` | ✅ DataGrip 风格，可保留 |
| 选中行 | `#D7D7D7` 灰条 | `#DBEAFE` 浅蓝（任务书指定） | 同 | ✅ 任务书指定色 |
| 侧栏底色 | `#E5E9EA`（占屏 13.1%） | `#FFFFFF` | `#FFFFFF` | ✅ 更接近 Navicat 的内容区 |
| 页面底色 | `#FFFFFF` | `#F8FAFC` | `#F8FAFC` | ✅ 任务书指定 |
| 面板工具条图标 | **彩色**（蓝铅笔 / 绿加号 / 红减号） | 单色描边 | 单色描边 | ⬜ 任务书 §4.5 要求统一线性图标，**不追** |

---

## 2. 分区深对比

### 2.1 顶部：应用栏 vs Navicat 的大图标工具栏

| | Navicat | AgenticDB |
|---|---|---|
| 形态 | 12 个「大图标 + 文字」入口，约 85px 高 | 50px 应用栏：品牌 + 当前连接 + 2 个全局动作 + ⌘K + 主题 + 设置 + 更多 |
| 入口分布 | 全在顶栏 | 数据库操作**下沉**到侧栏 / 对象首页 / 更多菜单 / ⌘K |
| 连接切换 | 无常驻入口 | 连接胶囊（状态点 + 名称 + 类型）→ 切换 / 重连 / 断开 / 管理 |

**判定**：任务书 §5.1 明确「不要把所有数据库操作都塞进顶部应用栏」，Navicat 那种
12 个入口的功能栏正是任务书点名要避免的形态。**不追，但保留入口可达性**：
`⌘K` 命令面板 + 更多菜单 + 侧栏 + 对象首页四条路都能到。

**⬜ 可补**：连接胶囊目前只有「当前连接」一个状态点，Navicat 会在树上给每个连接
单独一个状态图标。多连接时可以在树上补连接状态点（P2）。

### 2.2 工作区标签栏

两边结构一致（对象常驻标签 + 连接限定名的标签 + 关闭钮 + 脏点）。
差别在**标签配色**：Navicat 的标签图标是**实心蓝色数据库图标**，
本项目是描边类型色图标。任务书 §4.5 要求线性图标统一风格 → **保持现状**。

### 2.3 左侧对象树 —— **本轮最大的功能差距**

| 层级 | Navicat | AgenticDB |
|---|---|---|
| 1 | 我的连接 | 我的连接 |
| 2 | 连接（dianzqian / fetch） | 连接（agenticdb-demo.db） |
| 3 | **schema**（information_schema） | ❌ 无（只在多 schema 时用一个下拉） |
| 4 | **database**（loanmarket，绿圆柱） | ❌ 无 |
| 5 | 类型分组（表） | 类型分组（表/视图/触发器/过程/函数） |
| 6 | 对象（实心蓝表图标） | 对象（描边类型色图标） |

**⬜ P1-a：补 schema / database 两级树。**
内省层已经有 `listObjects(session, schemaPattern)` 与 `listSchemas(session)`，
数据是现成的 —— 缺的是树的**建模**（`DemoNode` 加 `SCHEMA` / `DATABASE` 两种 NodeKind）
与 `buildTreeModel` 的分组逻辑。预估 1.5 天。

**⬜ P1-b：类型分组按「有才显示」。**
现在表/视图/触发器/过程/函数五个分组恒定出现（SQLite 下「过程」「函数」是空的）。
Navicat 只显示真实存在的分组。空分组应该隐藏 —— 但**不能隐藏数据库不支持的类型**
（任务书 §5.3B「不要展示当前数据库引擎不支持的对象，除非标明支持状态」）。
做法：分组为空**且**该方言明确不支持 → 隐藏并标注「SQLite 不支持存储过程」。

### 2.4 数据网格

| | Navicat | AgenticDB |
|---|---|---|
| 列头 | 单行（名称） | **双行**（名称 + 类型），任务书 §6.4 要求 |
| 行号列 | ❌ | ✅ 有 |
| 选择列 | ❌ | ✅ 有 |
| 数值右对齐 | ✅ | ✅ |
| NULL | 留白 | 灰色斜体 `NULL`（任务书 §6.4） |
| 布尔 | ✅ | ✅ 复选框 |
| 对象切面 | ❌（表清单与数据是两种页） | ✅ 数据/结构/索引/外键/触发器/SQL（**新增**） |

**本轮新增**：数据页顶部多了一条切面条，
把「同一个对象的六个切面」收在一个页面里 —— 这是 Navicat **没有**的能力。

**⬜ P2：网格内列拖拽排序**已实现；**列头右键菜单**（排序/隐藏/复制列名）缺 —— Navicat 有。

### 2.5 状态栏

| | Navicat | AgenticDB |
|---|---|---|
| 内容 | 居中一行「已选择 1 个项目」 | 10 段固定位置：方言·连接·行范围·消息｜脏·选择·耗时·事务·只读·服务器·版本 |
| 位置 | 居中 | 左信息 + 右信息 |

**判定**：本项目状态栏信息量远大于 Navicat，且任务书 §6.6 要求「当前连接状态 /
数据库类型 / SQL 执行状态 / 耗时 / 后台任务」—— 保留。

**⬜ P2**：Navicat 把「已选择 N 个项目」放在**居中**位置，扫视时更容易看到。
本项目的「选择 N 行」在右半区。可以把选中数与耗时挪到中间分段。

### 2.6 弹窗 —— **本轮修掉了最严重的一个缺陷**

| | 修复前 | 修复后 |
|---|---|---|
| 边框 / 圆角 | ❌ 无（JavaFX 默认皮肤） | ✅ 1px 边框 + 12px 圆角 + 投影 |
| 标题区 | ❌ 与正文无分隔 | ✅ 浅底 + 下边框 |
| 按钮 | ❌ 全是默认灰钮 | ✅ 主按钮实心强调色 + 次按钮描边 |
| 主题令牌 | ❌ **全部落空**（`-adb-*` 找不到定义） | ✅ `DialogStyles` 把 `theme-light/dark` **抄**到 DialogPane |
| 高度 | ❌ 可比屏幕还高（底部按钮在屏幕外） | ✅ 按屏幕限高 |

**根因**（值得记住）：JavaFX 的 looked-up color 定义在 `.theme-light` /
`.theme-dark` **类选择器**上，而主题类挂在**主场景根节点**上。弹窗有自己的
Scene 与根节点（DialogPane），那个根节点上没有主题类 → 所有 `-adb-*` 查找落空 →
声明被丢弃 → 退回默认皮肤。**截图能看出来，行为门禁测不出来。**
已加 `DialogStylesTest`（7 项）钉住。

### 2.7 字体与字号

| 角色 | Navicat | AgenticDB | 任务书 §4.3 |
|---|---|---|---|
| 应用名 | — | 16px Bold | 16–18px ✅ |
| 页面标题 | — | 20px Bold | 18–20px ✅ |
| 表格正文 | ~13px | 13px | 13–14px ✅ |
| 列类型行 | 无（Navicat 单行表头） | 11px | 11–12px ✅ |
| 状态栏 | 11–12px | 11px | 11–12px ✅ |
| 等宽（数字/日期/SQL） | 无 | Menlo / SF Mono | 允许 ✅ |

**判定**：字体阶梯符合任务书。Navicat 更密是因为它行高 19px，
本项目 35px（任务书 §4.4 定 30/36）→ **故意不追**，
另开「视图 → 密度 → 紧凑」给需要的人。

**⬜ P2：数字列现在用等宽字体（Menlo）。** 任务书 §4.3 说「数字、时间戳、
行数、耗时应保持易于比较的排版」—— 等宽满足；但表格正文用界面字体、
数字用等宽，**混排时基线不齐**。下一轮可统一成界面字体 + `tabular-nums`
等效对齐（JavaFX 无 `font-variant-numeric`，需要按字符宽度手工配平）。

### 2.8 颜色与配色

| 角色 | Navicat | AgenticDB | 任务书 §4.1 |
|---|---|---|---|
| 主色 | 蓝（工具条图标） | `#2563EB` | `#2563EB` ✅ |
| 主色 hover | — | `#1D4ED8` | `#1D4ED8` ✅ |
| 页面底 | `#FFFFFF` | `#F8FAFC` | `#F8FAFC` ✅ |
| 面板面 | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` ✅ |
| 二级面 | `#F5F5F5` | `#F1F5F9` | `#F1F5F9` ✅ |
| 选中底 | `#D7D7D7` 灰 | `#DBEAFE` 浅蓝 | `#DBEAFE` ✅ |
| 正文 | `#262626` | `#0F172A` | `#0F172A` ✅ |
| 次要 | `#7A7A7A` | `#64748B` | `#64748B` ✅ |
| 边框 | `#E2E2E2` | `#E2E8F0` | `#E2E8F0` ✅ |
| 成功 / 警告 / 危险 | — | `#15803D` / `#B45309` / `#DC2626` | `#16A34A` / `#D97706` / `#DC2626` ⚠️ |

**两处刻意偏离任务书**（都因为仓库自带的 `ContrastTest` 要求 WCAG AA 4.5:1）：
- `success` `#16A34A` → **3.15:1**（不过）→ 改 `#15803D`（4.79:1）
- `warning` `#D97706` → **3.04:1**（不过）→ 改 `#B45309`（4.80:1）
- `error` `#DC2626` 4.62:1 ✅ 与任务书一致
- 任务书的「弱化文字」`#94A3B8` 只有 2.45:1 → 拆成两个令牌：
  `text-muted`（装饰用，图标/分隔线）与 `text-tertiary`（可读三级文字 `#5F6E82`，4.97:1）

### 2.9 边距与间隔

| 位置 | Navicat | AgenticDB | 判定 |
|---|---|---|---|
| 表格与页面左右留白 | ~8px | 16px | ✅ 更透气 |
| 侧栏内边距 | ~10px | 8px | ✅ |
| 工具条内元素间距 | ~4px | 4–8px | ✅ |
| 面板与面板之间 | 0（共用分隔线） | 0（共用 1px 边框） | ✅ |
| 对象树行高 | ~22px | 24px | ✅ |

### 2.10 菜单栏

两边都是原生菜单（macOS 进系统菜单栏）。本项目 9 个顶级菜单
（文件/编辑/视图/数据库/对象/收藏夹/工具/窗口/帮助），**每一项都来自
`ActionRegistry`** —— 门禁 `MenuBarFactoryTest` 断言「没有死项、没有永久禁用项」。
本轮新增「断开连接」并同步进菜单与连接胶囊下拉。

**⬜ P2**：Navicat 的「查看」按钮在右上角（面板显示模式切换），
本项目把它做成了数据页的「视图」下拉。语义接近，可不动。

### 2.11 输入框

| | Navicat | AgenticDB |
|---|---|---|
| 高度 | ~24px | 32px（任务书 §4.4：32–36）✅ |
| 圆角 | 小 | 6px ✅ |
| 描边 | 浅灰 | `#CBD5E1`（border-strong）✅ |
| 聚焦 | 蓝边 | 蓝边 + 光晕 ✅ |
| 禁用 | 灰 | 灰底 + 保留描边（不用 opacity 压暗）✅ |

**判定**：符合任务书。任务书要求 32–36px，Navicat 偏小。

### 2.12 查询 / SQL

| 能力 | 状态 |
|---|---|
| 执行 / 全部执行 / 执行计划 / 格式化 / 保存 / 存为命名查询 / 变量 / 清空 | ✅ 全部接真实后端 |
| 语法高亮 / 行号 / 括号配对 | ✅ RichTextFX |
| 执行耗时 / 错误位置（行·列）/ 错误消息 / 结果行数 | ✅ |
| 结果集复用统一网格 | ✅ |
| 执行中 / 成功 / 失败 / 已取消四态 | ✅ |
| 危险 SQL 二次确认 + 风险徽标（四级） | ✅ |
| 未保存标记 + 关闭确认 | ✅ |
| 查询历史 / 已保存查询 / 代码片段 | ✅ 落本地偏好，重启还在 |

**⬜ P2**：SQL 编辑器与结果区之间**没有可拖拽分隔条**的显式指示
（SplitPane 有，但没画出把手样式）；任务书 §7 说「必要时允许拖动分割线调整」。

### 2.13 函数 / 过程 —— **功能有，但演示库跑不到**

- 代码侧齐全：`SchemaService.ObjectKind.{PROCEDURE,FUNCTION}`、
  对象树「过程 / 函数」分组、`RoutinePage` 定义体页（读真实 DDL）。
- **但 SQLite 演示库里没有任何函数 / 过程**（实测：4 表 / 1 视图 / 1 触发器），
  SQLite 本身也没有标准意义上的存储过程 → **这条流程在默认演示环境里点不到**。

**⬜ P1-c：给演示库补一个可运行的「函数」等价物。**
两条路：
1. SQLite 用 **视图 + 触发器**模拟（已有 `v_active_users` / `trg_users_audit`，
   可以在对象树里把 VIEW 也归到「函数」组，或加一组「内建函数」）；
2. 用 **Testcontainers 起一个 PostgreSQL** 做演示库，把函数/过程的真实流程
   放进验收脚本（仓库已有 `PostgresMySqlIntegrationTest` 的底子）。

**推荐 2** —— 演示库换成 PG 容器，才能同时验证 函数/过程/索引/外键/排序规则/注释
这些 SQLite 拿不到的对象，也才能补齐 §2.3 的 schema/database 树层级。

---

## 3. 功能流程实测清单

「真实后端」= 走真库 / 真 SQL，不是写死数据。

| # | 流程 | 入口 | 真实后端 | 自动化覆盖 | 结论 |
|---|---|---|---|---|---|
| 1 | 打开表并读数据 | 树双击 / 对象首页 | ✅ 分页 SQL | `AppShellTest.loadsRealUsersPageAndStatusRange` | ✅ |
| 2 | 排序（下推 ORDER BY） | 列头 | ✅ | `DataGridPageTest.appliesFilterAndSortToServerSideSql` | ✅ |
| 3 | 筛选（条件构造器） | 工具栏漏斗 | ✅ 占位符参数 | 同上 | ✅ |
| 4 | 列头值筛选（候选值+计数） | 列头漏斗 | ✅ SQL 聚合 | `ColumnFilterPanel` | ✅ |
| 5 | 本页搜索 | 工具栏搜索框 | ⚠️ 仅当前页（明确标注「本页搜索」） | `DataGridPageTest.searchHideNullAndTransposeOnlyAffectTheCurrentPage` | ✅ 语义已声明 |
| 6 | 单元格编辑 → 撤销/重做 | 双击单元格 | ✅ 内存缓冲 | `DataGridPageTest.addRowCreatesDraftAndUndoRemovesIt` | ✅ |
| 7 | 保存修改（含 SQL 预览 + 风险） | ⌘S | ✅ 单事务 | `DataGridSaveGuardTest`（6 项） | ✅ |
| 8 | 批量删除 / 设 NULL / 克隆 | 选中行后的批量条 | ✅ 缓冲 | `DataGridPageTest.batchDeleteMarksRowsAndUndoRestoresBuffer` | ✅ |
| 9 | 分页 / 跳页 / 跳行 | 底部分页器 | ✅ 服务端 | `DataGridPageTest.firstLastAndJumpPaginationWork` | ✅ |
| 10 | 列显隐 / 顺序 / 宽度持久化 | 列设置弹层 | ✅ 偏好 | `DataGridPageTest.persistsColumnVisibilityAcrossPages` | ✅ 本轮补了**列名搜索** |
| 11 | 对象切面（结构/索引/外键/触发器/SQL） | 数据页切面条 | ✅ 真内省 | **本轮新增 `ObjectSectionsPanelTest`（5 项）** | ✅ |
| 12 | 执行 SQL（含多语句 / 错误） | 查询页 | ✅ | `QueryPageTest`（9 项） | ✅ |
| 13 | 取消执行 | 停止按钮 | ✅ 后端取消 | `QueryPageCancelTest` | ✅ |
| 14 | 建表 / 改列 / 建索引 / 外键 / 触发器 | 结构页 | ✅ 方言 SQL | `NewTableDialogTest`（7）、`StructurePageTest`（7） | ✅ |
| 15 | 导入 / 导出 | 侧栏工具 | ✅ CSV/TSV/JSON/SQL | `TransferWizardTest`（4） | ✅ |
| 16 | 连接管理（增删改 / 搜索 / 收藏） | 连接页 | ✅ 系统凭据库 | `ConnectionsPageTest`（6） | ✅ |
| 17 | 断开 / 重连 | 连接胶囊 / 数据库菜单 | ✅ | **本轮新增动作，尚无专用门禁** | ⬜ P2 |
| 18 | 检测（诊断） | 侧栏工具 | ✅ 表健康 | `WorkspaceToolsPageTest.diagnosticsPageReportsTableHealth` | ✅ |
| 19 | 查询历史 / 已保存查询 | 侧栏工作台 | ✅ 偏好 | `WorkspaceToolsPageTest`、`SavedQueriesTest`（8） | ✅ |
| 20 | 命令面板 | ⌘K | ✅ 全动作 | `AppShellTest.commandPaletteOpensAndFilters` | ✅ |
| 21 | 未保存保护 | 关标签 / 退出 | ✅ | `AppShellTest` + `confirmClose` | ✅ |
| 22 | 主题切换 / 深色 | 应用栏 ◐ | ✅ | `AppShellTest.themeHotSwitchKeepsSnapshotPixels` | ✅ |
| 23 | 函数 / 过程定义体 | 对象树「函数 / 过程」 | ✅ 真 DDL | ❌ **SQLite 演示库没有这类对象** | ⬜ P1-c |
| 24 | 应用栏 / 对象头 / 切面条 / 列设置 / 弹窗主题 | — | — | **本轮新增 4 个测试类（36 项）** | ✅ |

---

## 4. 本轮发现并修掉的缺陷

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| D1 | **所有弹窗都没有样式**（`-adb-*` 在 DialogPane 上全部落空） | 16 个弹窗全是 JavaFX 默认皮肤 | 新增 `DialogStyles`（样式表 + 主题类 + 屏幕限高），覆盖全部弹窗与 Alert |
| D2 | `AppBar` 构造函数**吞掉了传入的连接列表** | 胶囊显示「未连接」 | 构造末尾补 `setConnections(profiles, active)`；`AppBarTest` 抓到 |
| D3 | 查询历史页空态是一张**撑满整页的白卡片** | 视觉事故 | 空态移出 ListView，收起列表单独显示卡片 |
| D4 | 已保存查询弹窗里一个**巨大的默认皮肤白框** | 同上 | 限高 240px + 套 `.adb-history__list` 外观 + 空态文案 |
| D5 | 「查询历史」页标题徽标与右侧说明**同文重复**（工具 / 工具） | 噪音 | 同文时隐藏右侧说明 |
| D6 | 连接胶囊显示 **`SQLITE`** | 像拼写错误 | 新增 `kindDisplay()`（`SQLite`），`kindLabel()` 保留给表格类型列 |
| D7 | 斑马纹 `#F1F5F9` 占屏 **24.5%**，过重 | 网格发灰 | 新增 `bg-stripe` 令牌 `#F7F9FB`（Δ3） |
| D8 | 记录导航条单独占一行，把首行数据推到 33% 屏高 | 密度损失 | 并进底部分页器（两套方向键放一起，语义更清楚） |
| D9 | 对象切面条单独占一行 | 又 29px chrome | 并进对象标题栏同一行（紧跟对象名） |
| D10 | 1024px 下分页器文字被截断（「第 1/…」「每…」） | 信息丢失 | 文字标签 `USE_PREF_SIZE` 保护 + 跳转框收窄 + 记录位置文案缩短 |
| D11 | 新增组件**零测试覆盖** | 回归无网 | 新增 4 个测试类 36 项 |

---

## 5. 任务书条款对照

| 任务书 | 状态 | 证据 |
|---|---|---|
| §4.1 颜色系统 | ✅（success/warning 两处为 WCAG 偏离，已说明） | `tokens.json` · `ContrastTest` |
| §4.2 深色主题 | ✅ 完整适配 | `p5-dark.png` |
| §4.3 字体与字号 | ✅ | `tokens.json` `fs-*` · `AppCssAuditTest` |
| §4.4 尺寸规范 | ✅ | `h-appbar/h-tabbar/h-toolbar/h-input/h-button` · `w-sidebar*` · `row-*` |
| §4.5 图标规范 | ✅ 统一 16×16 矢量 1.5px 描边 | `Icons` · `IconSystemAuditTest` |
| §5.1 应用栏 | ✅ | `AppBar` · `AppBarTest` |
| §5.2 标签栏 | ✅ | `TabBar` · `ObjectHomeTabTest` |
| §5.3 对象浏览器 | ⚠️ 缺 schema/database 两级树 | §2.3 P1-a |
| §6.1 对象标题栏 | ✅ | `ObjectHeader` · `ObjectHeaderAndTabsTest` |
| §6.2 对象功能标签 | ✅ | `ObjectTabBar` · `ObjectSectionsPanelTest` |
| §6.3 数据工具栏 | ✅ 左数据操作 / 右查询显示 | `DataGridPage` |
| §6.4 数据网格 | ✅ | `DataGridPageTest` · `CellRendererTest` · `NumberFormattingTest` |
| §6.5 列设置与筛选 | ✅（本轮补列名搜索） | `ColumnSettingsPanelTest` · `ConditionDialog` |
| §6.6 分页与状态栏 | ✅ | `DataGridPageTest` · `StatusBarTest` |
| §7 SQL 编辑器 | ✅ | `QueryPageTest`（9） |
| §8 其他页面与弹窗 | ✅（弹窗从"无样式"修到"有样式"） | `DialogStylesTest` |
| §9 交互细节 | ✅ | `FocusRingAuditTest` · `DisabledButtonVisibilityTest` · `IconAuditTest` |
| §10 响应式 | ✅ 1024 / 1280 / 1440 / 1920 实测 | `docs/screenshots/` |
| §12 功能回归 | ✅ **425 项测试全绿**，构建通过 | `mvn clean install` |
| §13 验收标准 | ⚠️ 见 §6 差距清单 | — |

---

## 6. 下一步开发方案

### P0 — 收尾（0.5 天）

| 项 | 内容 | 验收 |
|---|---|---|
| P0-1 | `docs/UI-VISUAL-SPEC.md` 同步本轮的密度与合并改动（切面条并进标题栏、记录导航并进分页器、`bg-stripe` 令牌） | 文档与代码一致 |
| P0-2 | 新增 `connection.disconnect` / `connection.reconnect` 的**专用门禁**（断开后状态栏、胶囊状态点、标签关闭行为） | `AppShellTest` 加 3 项 |
| P0-3 | `mvn clean install` 纳入 CI（现在 `tokens.json` 的资源拷贝有陈旧风险，见下） | 门禁跑在 clean 上 |

> ⚠️ 本轮踩到的坑：`mvn test`（不 clean）**不会**重新拷贝
> `ui/adb-ui-core/src/main/resources/tokens/tokens.json`，导致
> `TokenParityTest` 读到旧资源而误报。**必须 `clean`**。

### P1 — 功能对齐 Navicat（3–5 天）

| 项 | 内容 | 涉及 | 工作量 | 验收 |
|---|---|---|---|---|
| **P1-a** | 对象树补 **schema / database 两级** | `SideBar.DemoNode` 加两种 NodeKind；`buildTreeModel` 分组；`SchemaService` 已有 `listSchemas` / `listObjects(schemaPattern)` | 1.5d | 树截图出现 schema → database → 类型 → 对象 四级；`SideBarTreeTest` 加断言 |
| **P1-b** | 空类型分组隐藏 + 「该方言不支持」标注 | `SideBar.buildTreeModel`；`DialectKind` 加 `supportsProcedures()` 等能力声明 | 0.5d | SQLite 下不再出现空的「过程 / 函数」分组 |
| **P1-c** | **函数 / 过程流程可演示** | 演示库换 PostgreSQL Testcontainers（含函数、过程、外键、排序规则、注释） | 1.5d | `RoutinePage` 有端到端门禁；对象树能点开函数看真 DDL |
| **P1-d** | 表清单补 **修改日期 / 排序规则 / 注释** 三列 | `TableMeta` 加字段 + 各方言内省 SQL（PG `pg_class`/`pg_collation`，MySQL `information_schema`）；SQLite 无 → 显示「—」 | 1d | 列与 Navicat 对齐；`TableMeta` 契约测试 |

### P2 — 体验打磨（2–3 天）

| 项 | 内容 | 工作量 |
|---|---|---|
| P2-1 | 数字列统一排版（去掉 Menlo 混排，或补等宽数字对齐） | 0.5d |
| P2-2 | 状态栏「选择 N 行 / 耗时」挪到中间分段（对齐 Navicat 的居中提示） | 0.2d |
| P2-3 | 列头右键菜单（排序 / 隐藏 / 复制列名 / 列设置） | 0.5d |
| P2-4 | SQL 编辑器 ↔ 结果区的分隔条显式手柄样式 | 0.2d |
| P2-5 | 对象树给每个连接加状态点（多连接时区分在线 / 断开 / 出错） | 0.3d |
| P2-6 | 命令面板加「最近使用」分组与模糊匹配（Navicat 的 ⌘K 体验） | 0.5d |
| P2-7 | 窄窗口（1024）下分页器进「更多」菜单（现在仍偏挤） | 0.3d |

### P3 — 长期（不排期）

- 表格虚拟滚动（大表不分页，Navicat 支持）—— 当前是服务端分页（§6.6 允许）
- SQL 关键字补全 / 表名字段名提示
- 表 / 视图的**行内编辑**差异提示（视图可编辑但不可写）
- 多窗口 / 多标签拖出

---

## 7. 本轮怎么验证的

```bash
# 全量构建 + 全部测试（425 项）
mvn -o clean install

# 截图对拍（8 张验收图 + 并排图）
./scripts/shots.sh

# 单点复现
ADB_SHOTS=/tmp/x ADB_SHOTS_NAME=t ADB_OPEN=users ADB_THEME_FORCE=light \
  ./mvnw -pl bootstrap/adb-desktop javafx:run
```

测量脚本口径：PIL 读 PNG → 逐行取色 → 找等色带（chrome 分界）→
统计全图颜色占比（斑马纹权重）。参考图与本轮图**分别按自身分辨率归一化**。
