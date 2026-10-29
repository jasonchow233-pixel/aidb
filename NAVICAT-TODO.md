# NAVICAT-TODO — 「和 Navicat 一模一样」真源（2026-10-29 第三次重写）

> **本文是唯一的产品/规划真源**，替换 2026-10-28 版的全部规划口径（§1 的像素实测仍被引用的按帧保留并复核）。
> 批次执行日志与 todolist 在 `docs/ROADMAP-TODOLIST.md`（执行视图），历史轮次记录在 `docs/ARCHIVE-R30-R33.md`。
>
> **触发**：2026-10-29 用户令「忘掉旧的对比规划，用 screen 里的突破 + 9 张 Navicat 实拍重新逐张对比，
> 每一个细节都要对比，重写升级修改方案」。9 张实拍已全部 **md5 逐张对齐**归档到 `docs/screenshots/navicat/`
> （零新增拷贝工作，两张 2026-10-29 新帧此前只入库未进规划）。
>
> **四项拍板（2026-10-28，全部继承，本轮无一推翻）**
> 1. **顶区**：完全照 Navicat —— 140px 顶区 + 90px Ribbon + 12 格磁贴。**R32-D 的精简作废**。
> 2. **图标**：自绘同风格彩色图标（不提取 Navicat 原始位图 —— 他人版权素材，不能进我们的产品）。
> 3. **语言**：i18n 层，中英双语，**默认英文**（与实拍一致）。
> 4. **刻意差异全部拿掉**：行首复选框 → 行号+铅笔；强调色 → Navicat 原值 `#0078D7`；
>    弹窗自绘标题条 → 系统标题栏；状态栏去掉「自动提交 · 只读」安全语义段。
>
> **量具**（单文件、无依赖，结论必须可复现）：`tools/Pixel.java`（取点色值）、`tools/Find.java`（按色值/文字定位）、
> `tools/Crop.java`（裁剪 + 整数倍放大 —— 1280 帧上肉眼看不出的 1px 重叠与半截字靠它判）；
> 棘轮计数 `node tools/cjk-count.mjs`（与 `I18nAuditTest` 同构；⚠️ `grep -hoP` 口径会把注释里的中文数进去，两口径不能混）。

---

## 0. 一句话结论

**上一版（10-28）的骨架全部成立、本轮无一推翻**：三大前置（i18n / 彩色图标渲染层 / 顶区 140px）、
R34–R42 轮次结构、6 个缺失能力面的价签判断，在 9 张帧逐张重比后依然站得住。动的是四件事：

1. **新增 4 条结构性差距（N31–N34）** —— 全部来自 2026-10-29 两张新帧与代码核对：
   Filter & Sort 在 Navicat 是**网格内嵌生成器面板**（我方是弹窗 + 每列漏斗，`ConditionDialog` 186 行 /
   `ColumnFilterPanel` 370 行）；连接树要**库级**（连接 → 库 → 五组，`SideBar.java` 现在没有任何 database 概念）；
   AIDB 的 **Objects 主页与 Table List 两页并存**而 Navicat 只有一个对象清单页；
   数据页工具条实测 **13 个具名键**（旧规划只写 5 个），其中 `Data Generation` / `Data Masking` /
   `Create BI Workspace` 三个能力我方为零。
2. **多处实测修正**：数据页工具条项集（N19 重写）、状态栏要按**三种页型**给段集（N25 扩充）、
   底部记录操作小图标条、当前**列表头着色**与**行内编辑盒**样式（N17 扩充）、页签标题格式（§1.10）。
3. **成本上调**：R34–R41 从 54–74 → **61–86 人日**；含 R42 从 88–126 → **97–142 人日**（§4 逐轮可加）。
4. **流程红线**：R34 第八至十四批（棘轮 890 → 479）与本文档重写**已落库**
   （`46873b8`/`f0c1044`/`6e99d60`/`ed41207`/`7def1cf`/`d20f5eb`/`fa23770` + 随批 docs）——
   「验收 ≠ 落库」这条 R33 教训这次没有过夜。

---

## 1. 实测基线（9 张帧逐张，2026-10-27/28/29 @100% 缩放）

### 1.0 证据台账（md5 已对齐，两两去重后 13 张有效 + 2 张过时对照图）

| # | 帧 | 文件 | 尺寸 | 主要产出 |
|---|---|---|---|---|
| 1 | 查询编辑器全窗 | `navicat17-query-editor-2026-10-28.png` | 1920×1040 | §1.1 §1.2 §1.5 §1.6(查询页) |
| 2 | 已保存查询全窗 | `navicat17-saved-queries-2026-10-28.png` | 1920×1040 | §1.3 §1.6(对象页) §1.7 |
| 3 | **数据页 + Filter 面板** | `navicat17-filter-sort-2026-10-29.png` **本轮新进规划** | 1617×903 | §1.4 §1.9 |
| 4 | **数据页全窗 + 多库树** | `navicat17-tree-multidb-2026-10-29.png` **本轮新进规划** | 1920×1040 | §1.1复核 §1.3(库级) §1.4复核 §1.6(数据页) §1.10 |
| 5 | 对象清单 | `navicat17-object-list-2026-10-27.png` | 1219×893 | §1.3 §1.6(对象页) N14 N15 |
| 6 | 数据网格 | `navicat17-data-grid-2026-10-27.png` | 1219×893 | §1.4(行首实测) |
| 7 | 表设计器 | `navicat17-designer-fields-2026-10-27.png` | 1219×893 | N29 |
| 8 | 连接类型页 | `navicat17-conn-type-2026-10-27.png` | 900×700 | §1.8 N27-a |
| 9 | 连接常规页 | `navicat17-conn-general-2026-10-27.png` | 900×700 | §1.8 N27-b |

（`navicat-data-grid-2026-10-24` / `navicat-table-list-2026-10-24` / `navicat-tablelist` / `navicat-toolbar`
是 10-24 的早期帧，仅备查；`side-by-side*.png` 是 2026-10 的过时对照图，§7 第 2 条要求重做。）

### 1.1 纵向分区（帧 1、帧 4 两张全窗 x=1900 逐像素扫描，两帧复核一致）

| 区 | Navicat 实测 | AIDB 现状 | 差 |
|---|---|---|---|
| 菜单栏 | y 0–22 → **23px**，`#FFFFFF` | 24px 系统菜单，`#FFFFFF` | −1px |
| **Ribbon** | y 23–113 → **91px**，`#F0F0F0` | **64px** 单命令带 | **+27px** |
| 分隔线 | y 114 → **1px `#DBDBDB`** | 无独立分隔线 | 缺 |
| 页签行 | y 115–139 → **25px**，`#FFFFFF` | 26px（且塞了连接 pill/搜索/主题/⋯） | −1px + 内容不同 |
| 分隔线 | y 140 → **1px `#DBDBDB`** | 无 | 缺 |
| 页面工具带 | 对象清单/数据页：**30px**（帧 4 y141–170）；查询页：**62px 双行**（帧 1 y141–202），底色 **`#F0F0F0`** | 30px **白底** | 底色不同 |
| 内容区 | y 171/203 起 `#FFFFFF` | 同 | — |
| 水平滚动条带 | 22px `#F0F0F0` | 系统默认 | 待对齐 |
| 状态栏 | **23px** `#F0F0F0`，顶边 `#D7D7D7`，段间 `#D7D7D7` 1px 竖线 | 22px，段间分隔样式不同 | +1px |

**Navicat 全局顶区 = 23 + 91 + 1 + 25 = 140px。** 我方 114px。
（页内工具带不计入顶区 —— 旧口径「Navicat 170 vs 我方 114」已废。）

### 1.2 Ribbon 磁贴（帧 1 实测，帧 4 复核 12 格顺序一致）

| 参数 | 实测 |
|---|---|
| 磁贴宽 | **≈70–71px**（激活格 `#C0DCF3` 填充精确落在 x 544–613 = **70px**） |
| 磁贴高 | 90px（整带） |
| 图标 | **≈30×30px**，多色渐变位图风格，居中在上（y≈56–86） |
| 标签 | y≈95–110，约 11–12px，黑色 `#000000`，居中在下 |
| 激活格 | 填充 `#C0DCF3` + 描边 `#90C8F6`（1px，四边） |
| 分组竖线 | **1px `#DBDBDB`**（实测 x=166），组间额外约 14px 空隙 |
| 12 格顺序 | Connection · New Query · Table · View · Function · User · Others · **Query(激活)** · Backup · Automation · Model · BI |
| 图标实测色 | 橙渐变 `#FC6C45→#DA512D`；蓝渐变 `#2190DC→#0476CF`；浅蓝 `#97CDF9→#3BA3F8`；棕 `#9F571D`；灰 `#869099`；橙 `#F78129→#CB691F`；品红 `#E74FD6`；绿徽章 `#45C300`/`#8FD768` |

> ⚠️ 每个 Ribbon 图标实测含 **4–6 种颜色 + 渐变**。我方 `LucideConverter.toPath()`
> （`ui/adb-ui-core/.../LucideConverter.java:57`）把 SVG 的**所有**元素压成**一条** `d`，
> `Icons.of()`（`Icons.java:326-327`）只给这一条 path 设一个 fill **或** stroke。
> **结论：不是缺图标，是缺渲染层。** 必须先做 `ColoredIcon`（`Group<Shape>`，逐 path 保留自身 paint），
> 再谈画 50 枚彩色图标。

### 1.3 导航树（帧 4 多库形态 **本轮实测修正**，帧 1/2 单库形态复核）

| 参数 | 实测（帧 4） | AIDB | 差 |
|---|---|---|---|
| **层级** | **连接 → 库 → 分组 → 叶子** 三级：`fetch` 下挂 `information_schema` 与 `loanmarket` 两个库节点，库下才是 Tables/Views/Functions/Queries/Backups | **连接 → 分组 → 叶子** 两级（`SideBar.java` 全文件无 database 概念，连接即库） | **缺整层** → N32 |
| 行距 | **20px** | 21px（`Tokens.ROW_TREE_LIGHT`） | **−1px** |
| 分组集合 | 恰为**五个** `Tables/Views/Functions/Queries/Backups`；连接落在单库视图时表平铺（帧 1/2） | 六组 `表/视图/过程/函数/触发器/事件` + 计数徽标 | **缺 `Queries`+`Backups`，多 `过程`/`触发器`/`事件`** |
| Queries 组 | **可展开**，子节点就是保存的查询文件（帧 4 实测展开出 4 个叶子，选中 `tz - mkopo` 整行高亮） | 树里没有 Queries 组；保存查询只在 Ribbon `Saved Queries` 页 | → N24 |
| 计数徽标 | **树里没有计数** | 分组标题带计数 | 多余 |
| 多连接 | 树根下多个连接节点纵向并列（`gobee-bd` / `india - pay` / `magic-*` 等依次排） | 同（Connections 顶栏 + 连接节点） | 基本一致 |
| 表图标 | 4 色蓝：`#077ED5` 描边 + `#CDE8FF` / `#B8DFFF` / `#96D0FF` 三档填充 | 单色线性 | 完全不同 |
| 底部 | `Search` 输入框 + 右下三个视图切换小图标；**无 navButton** | 搜索框 + ☆ + «，另有 `查询历史`/`诊断`/`导入·导出` 三个 navButton（`SideBar.java:671-681`） | Navicat 没有，要挪走 |

> 「SQLite 这类单库引擎是否平铺直挂」帧上没有直接证据（9 张全是 MySQL 连接），N32 落地前按
> Navicat 手册口径核对一次，不凭推断写死。

### 1.4 数据网格 / 结果网格（帧 3 **本轮关键证据**，帧 4/6 复核）

| 参数 | 实测 | AIDB | 差 |
|---|---|---|---|
| 行高 | **23px**（发丝线 `#F0F0F0`） | 23px | **0** |
| **工具条** | **13 个具名键 + 右端 2 小键**（帧 3 逐键辨认）：`Table Profile▾` · `Begin Transaction` · `Cell Editor` · `Filter & Sort`（按下态=面板开着）· `Columns` · `History` · `Fill with AI▾` · `Data Profiling` · `Import` · `Export` · `Data Generation` · `Data Masking` · `Create BI Workspace` | 12 键：+/撤销/重做/保存/`Begin Transaction`/`Filter & Sort`/`History▾`/`Columns▾`/预览/导入/导出/`Search this page` | **项集不同**：缺 `Table Profile`/`Cell Editor`/`Data Profiling`/`Fill with AI`/`Data Generation`/`Data Masking`/`Create BI Workspace`，多 撤销/重做/保存/搜索 → N19 |
| **底部左** | **记录操作小图标条**：`+ − ✓ ✗` + 第 5 颗展开类图标（前四颗语义 = 插入/删除/更新/取消，第 5 颗 inferred） | 底部左 3 键：`+ 💾 ✕` | 键集不同 → N19 |
| **底部右** | 五键记录导航 `|◀ ◀ [页码] ▶ ▶|` + 齿轮 + 放大镜 + 2 视图切换；**无 行数/页大小/跳页 文字控件**（页大小 inferred 在齿轮里） | `1–200 / 200 Record` 文字 + 五键 + `200 row(s)` + `Page 1/1` + `Go to page` + `Per page 200▾` | → N20 |
| **选中行/编辑行** | 编辑行整行**灰带**（≈`#E0E0E0` 档），当前格是**白底蓝框的行内编辑盒、值全选**（帧 3 `repay_id` 单元格实拍） | 2px 主色左槽 + 蓝描边；编辑走抽屉 | 完全不同 → N17 |
| **当前列表头** | 当前列的表头**整格着蓝 tint**（帧 3 `repay_id` 表头实拍） | 无此语义 | 新发现 → N17 |
| 行首 | **行号 + 铅笔编辑标记**（帧 6 实测） | 复选框 + 行号 | 拍板改 → N16 |
| 双行表头 | 字段名粗 + 类型灰斜体，数值列带 `#` 前缀（`# int(11)`/`# bigint(20)`） | 类型 + 约束 + `PK · AUTO`（`number · PK · AUTO`） | 表头第二行内容不同：Navicat 只放类型 → N19 |
| 行首槽复核注记 | 帧 3/帧 4 是横向滚动的网格，**行首槽在这两帧上肉眼不可辨**；行号+铅笔以帧 6 的实测为准，R38 开工前用 `tools/Crop.java` 8× 复核帧 4 左缘一次，若实测无槽则 N16-a 当场改判 | — | 诚实注记，不静默沿用 |
| 工具条溢出 | 右端 **`>>` 折叠按钮** | 整排退成图标（G19b） | 拍板改 → N18 |
| 记录计数位置 | **在状态栏**（`Record 13 of 100 in page 1`），不在分页条 | 在分页条（`1–200 / 200 Record`） | → N20/N25 |
| 选中行批量条 | **没有**（记录操作在底部图标条 + 右键菜单） | 底部整条 `1 row(s) selected · Copy · Copy as SQL · Export Selected · Clone · Set NULL · Set Empty · Delete · Clear Selection` | Navicat 没有的元素 → 随 N16-b 重建 |

### 1.5 查询编辑器（帧 1）

**两行工具带**（`#F0F0F0`，共 62px）：
- 行 1：`Save` · `Query Builder` · `Beautify SQL` · `Code Snippet` · `Ask AI ▾`
- 行 2：`[查询名下拉]` · `[库下拉]` · `▶ Run ▾` · `■ Stop` · `Explain`

**结果区三段**（实测纵向分区）：
| 段 | y | 高 | 底色 |
|---|---|---|---|
| 编辑器 | 203–579 | 377 | `#FFFFFF` |
| 结果页签条 `Message / Summary / Result 1` | 580–606（+1px `#D9D9D9`） | **27px** | `#F0F0F0` |
| 子页签条 `Data / Info` | 608–639 | **32px** | `#F9F9F9` |
| 结果网格 | 640– | — | `#FFFFFF` |

**SQL 语法配色（box 直方图实测，取 glyph 主色）**：
| 角色 | 实测 |
|---|---|
| 关键字 `SELECT` / `DISTINCT` / `FROM` / `WHERE` / `GROUP BY` | **`#097BF7`**（加粗） |
| 字符串 `'2026-07-20'` / `"REPAYMENT_COMPLETE"` | **`#FF1744`** |
| 注释 `-- ...` | **`#929292`** |
| 普通标识符 `phone_number` / `STATUS` | **`#424242`** |
| 表名 `t_mkopopap_order` | `#424242` + **下划线** |
| 行号列 | 灰字，`#F9F9F9`/白底待精测 |

我方 `SqlHighlighter.java` 现走另一套配色（蓝关键字 + 无字符串红 + 无表名下划线），需整体重标定（N22）。
我方查询页另有 Navicat 没有的东西：`Run All` / `Parameters` / `Clear` / `Save as Named Query` 按钮、
`Read Only` 徽章（Navicat 的 Read Only 在状态栏）、结果区下方的列类型 chips（`id INTEGER · name TEXT …`，
Navicat 的类型在表头第二行）、页首 `SQL Query` 大标题（Navicat 无页内标题）。随 N21 一并收敛。

### 1.6 状态栏（**本轮修正：三种页型三套段集**，段间均 `#D7D7D7` 1px 竖线）

**查询页（帧 1，y=1023）= 6 段：**

| 段 | 实测宽度 | 内容 |
|---|---|---|
| 1 | 0–1281 | 当前 SQL 全文（左对齐，可截断） |
| 2 | 1283–1361（79px） | `Read Only` |
| 3 | 1363–1521（159px） | `Record 1 of 20473` |
| 4 | 1523–1705（183px） | `Ln 11, Col 5` |
| 5 | 1707–1864（158px） | `Elapsed Time: 18.735s` |
| 6 | 1866–1918 | 视图切换小图标（列表/网格） |

**数据页（帧 4，本轮新增）= 左 1 段 + 右 2 段：**
左 = 当前 SQL 预览（反引号标识符：`` SELECT * FROM `loanmarket`.`t_df_bd_huankuan` ORDER BY `self_id` DESC LIMIT 0,100 ``）；
右 = `Record 13 of 100 in page 1` + `Last Refresh: 58m`。

**对象页（帧 2/帧 5）= 左计数 + 右连接与库：**
左 = `4 Queries`（帧 2）/ `22 Tables`（帧 5）；右 = 连接名 `fetch`/`rm-0jog6…` + 库名 `loanmarket`/`shop`（带状态点）。

我方 `StatusBar.java` 是 9 段固定语义（作用域/范围/消息/选择/耗时/会话/刷新于/脏值/SQL）——
**要按页型给出 Navicat 的段集与顺序**，并去掉「自动提交 · 只读」段（拍板 4）。→ N25。

### 1.7 已保存查询（帧 2，帧 4 补树侧证据）

Navicat 把保存的查询当**对象**管：树里 `Queries` 分组**可展开出文件叶子**（帧 4）；
选中分组后对象页是文件清单，工具条 `Design Query` / `New Query` / `Delete Query`，
列 = `Name` / `Modified Date` / `File Size` / `Modified User`，行高 21px，状态栏左 `4 Queries`。
我方 `SavedQueriesPage.java` 存在但列集/工具条/入口形态不同（且树里没有 `Queries` 分组）。→ N24。

### 1.8 新建连接（帧 8、帧 9，900×700）

- **类型页**：左侧 `Connection Filter`（MySQL/PostgreSQL/SQL Server/Oracle/SQLite/MariaDB/MongoDB/Redis/Snowflake —— **9 个复选**）
  + `Vendor Filter`（Alibaba Cloud/Huawei/Tencent/Dameng/Kingbase/PingCAP/HighGo/OceanBase/AWS/Fujitsu/Google Cloud/Microsoft/Oracle —— **13 个复选**）；
  右侧 **5 列彩色磁贴**（图标约 48px + 名称在下）；右上搜索框；左下 `New Connection with URI…`；右下 `Next` / `Cancel`。
- **常规页**：`General/Advanced/Databases/SSL/SSH/HTTP/Remarks` 7 页签；`Navicat ⟷ Database` 示意图居中；
  右对齐标签 `Connection Name` / `Host` / `Port` / `User Name` / `Password` + `Save password` 复选；
  底部 `Test Connection` / `URI…`（左）· `Back` / `OK` / `Cancel`（右）。

> 与旧 §7.15（「磁贴只摆真实支持的 3 种」）的冲突按 10-28 拍板执行：磁贴全摆，
> 不支持的库型点进去是**如实说明页**（G8 SSH/HTTP 先例），不是填了也没用的假表单。

### 1.9 Filter & Sort 生成器面板（帧 3，**本轮新增的关键证据**）

面板由工具条 `Filter & Sort` 按钮开关（按钮呈按下态），**嵌在工具带与网格之间**，把网格往下推。
自上而下：

| 部件 | 实测 |
|---|---|
| 标题行 | 粗体 `Filter` + 右侧 ↑↓ 两颗排序小键；最右 `◉ Builder / ○ Text` 单选（双模式切换） |
| 条件行 | `☑` 复选 + **蓝色链接样式的字段名**（`self_id`）+ 操作符（`=` / `contains`）+ 值（`270673`）+ 行间连接词 `and`；未填值显示 `<?>` 占位符；行 2 有浅蓝高亮（当前编辑行） |
| 增删 | 行尾 `[+]` 加条件 + `[Ω]` 加条件组（括号图标，语义 inferred） |
| Sort By 区 | 标签 `Sort By` + 排序键**芯片**（`self_id` + 升降序图标 + 下拉）+ `[+]` 加排序键 |
| 应用区 | **蓝色主按钮 `Apply Filter & Sort`**（显式应用，不是即输即查）+ 右侧灰色 **`Criteria Edited`** 脏标记 |

我方现状：`ConditionDialog`（186 行，弹窗）+ `ColumnFilterPanel`（370 行，每列漏斗的按值筛选面板）+
表头排序徽标。**能力存在、形态完全不同** → N31 重做为网格内嵌面板（Builder/Text 双模式保留我方 Text 模式
可对应现有条件表达式，属加分项不是差异）。

### 1.10 页签与窗口标题格式（帧 4，本轮新增）

- 页签标题：`<对象> @<库> (<连接>) - <页型>`，超宽截断为省略号
  （实测 `t_df_bd_huankuan @loanmarket (fet…`、`tz - mkopo @loanmarket (fetch) - Q…`）。
  我方页签现在是裸对象名（`users` / `Sample Query` / `Table List`）→ N7 顺带。
- 窗口标题同构（`<连接> @<库> (<对象>) - <页型> - <产品>`），N2 已落地 ✅。

---

## 2. 差距清单（N 系列）

> 每条 = 差距 · 目标实测值 · 现状 · 落点文件 · 成本（人日，单人，工程估算）。
> 编号 N1…N34：N1–N30 沿用 10-28 版（已交付的 8 个 i18n 批次与提交信息都引用这套编号，不改号），
> 内容按本轮证据修订；**N31–N34 本轮新增**。按「改一处影响一片」的顺序分组。

### A 组 · i18n（前置，卡住所有标签类条目）

| 编号 | 差距 | 目标 | 现状 | 成本 |
|---|---|---|---|---|
| **N1** | 无 i18n 层 | 两套资源包（en 默认 / zh），键名按区命名空间 | **层已建**（`I18n.java` + 两份 `.properties`，1130 键对称）。抽取进度：出现 **2216 → 479**、去重 388 条待抽，剩 **41 个文件**；顶区/动作层/表清单/数据网格/外壳/查询页/结构页/侧栏/连接向导/对象主页/建表对话框/导入向导+批量汇总/单元格抽屉/连接管理页 14 批已收；模型层另 277 条由第 13 条棘轮单独钉（D-9） | 8–12（剩 1–3） |
| **N2** | 菜单标签与项集 | `File/Edit/View/Query/Format/Favorites/Tools/Window/Help` | ✅ 已落地（含 Table 上下文菜单按页型开合） | 0 |

落点：`I18nAuditTest` 现 **13 条**断言（键集相等 / 调用点对账 / 双棘轮 / 全角标点 / 占位符个数 / 模型层标签边界）。

### B 组 · 图标渲染层与资产

| 编号 | 差距 | 目标 | 现状 | 成本 |
|---|---|---|---|---|
| **N3** | 渲染层做不出多色 | `ColoredIcon`：`Group<SVGPath>`，逐 path 保留 fill/stroke/渐变；`Icons.of()` 返回 `Node` 而非 `SVGPath` | `LucideConverter.java:57` 压成单 path；`Icons.java:326-327` 单 paint | 3–4 |
| **N4** | 约 50 枚彩色图标 | 按 §1.2/§1.3 实测色值自绘：Ribbon 12 枚 30px 多色渐变、树 16 枚四色、工具条 16 枚、磁贴 48 枚 | 46 枚单色 Lucide | 7–10 |

落点：`Icons.java`、`LucideConverter.java`、`icons/lucide/*.svg` → 新增 `icons/color/`；
调用点 `IconButtons.java`、`AppBar.java`、`SideBar.java`、`TabBar.java`。
**门禁改判**：`LucideIconTest` 三条 + `IconSystemAuditTest.java:136`（`SIZE_RIBBON == 26`）按彩色资源重写（Ribbon 图标实测 30px）。

### C 组 · 顶区与外壳

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N5** | 顶区高度 | **140px** = 菜单 23 + Ribbon 91 + 1 + 页签 25 | 114px（R32-D） | 1–2 |
| **N5-b** | ⚠️ **令牌源已经和生成物不一致** | `tokens/tokens.json:277-280` 的 `h-appbar` 仍是 **90px**，而生成物 `Tokens.java:232-233` 是手改的 **64**（文件头写着「⛔ 不要手改」） | R32-D 只改了生成物没改源；**必须先改 `tokens.json` → 重跑 `tools/GenTokens.java` → 再改 CSS**，顺序反了下一轮又攒一条同型债 | 0.5 |
| **N6** | Ribbon 磁贴 | 90px 高、70px 宽、**12 格**、图标 30px 在上 + 标签在下、激活 `#C0DCF3`+`#90C8F6` 描边、组间 `#DBDBDB` 竖线 | 64px、6 格、图标 26px 单色 | 2–3 |
| **N7** | 页签行内容与页签标题 | 行内**只放页签**（+侧栏折叠箭头）；页签标题改 `<对象> @<库> (<连接>)` 格式（§1.10） | 行内塞了连接 pill + 全局搜索 + 主题 + ⋯；页签是裸对象名 | 1–2 |
| **N8** | 页面工具带底色 | **`#F0F0F0`**（30px 单行 / 62px 双行） | 白底 | 1 |
| **N9** | 分隔线体系 | 顶区两条 1px `#DBDBDB`；状态栏段间 `#D7D7D7` | 无顶区分隔线 | 0.5 |
| **N10** | 强调色 | **`#0078D7`** | `#0067B8`（`ContrastTest.java:39` 的 `MIN_NORMAL = 4.5` 门禁挡住） | 0.5 |

**N6 是本文最贵的一条，且贵得不在样式上** —— 12 格里我方只有 6 格有对应功能：

| Navicat 格 | AIDB 对应 | 状态 |
|---|---|---|
| Connection | `ConnectionsPage` | ✅ |
| New Query | `QueryPage` | ✅ |
| Table | `TableListPage` / `NewTableDialog` | ✅ |
| View | — | ❌ **无视图对象页，视图不能建/改/删** |
| Function | `RoutinePage` | ✅ |
| User | — | ❌ **无用户/角色管理面** |
| Others | 树里有事件分组，无对象页 | 🟡 |
| Query | `SavedQueriesPage` | 🟡 形态不对（见 N24） |
| Backup | — | ❌ **无转储/恢复** |
| Automation | — | ❌ **无作业/调度** |
| Model | — | ❌ **无 ER 图** |
| BI | — | ❌ **无图表/透视**（帧 3 的 `Create BI Workspace` 同属此面） |

→ 单列 **N28**（见 F 组）。

### D 组 · 导航树与对象清单

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N11** | 树行距 | **20px** | 21px（`Tokens.ROW_TREE_LIGHT`） | 0.5 |
| **N12** | 树分组 | 分组集合恰为**五个** `Tables/Views/Functions/Queries/Backups`；单库视图表平铺；**树里没有计数徽标**（库级另立 N32） | 六组 + 计数（`SideBar.java:1087-1095`）；缺 `Queries`/`Backups`，多 `过程`/`触发器`/`事件` | 1–2 |
| **N13** | 树外三个 navButton | 移入菜单/工具，树里只有连接树 | `查询历史`/`诊断`/`导入·导出`（`SideBar.java:671-681`） | 1 |
| **N14** | 对象清单列 | **6 列**：`Name`/`Rows`/`Data Length`/`Engine`/`Modified Date`/`Comment` | 8 列（多 创建日期/排序规则）；**D-3：1280 宽只剩 2 列** | 1–2 |
| **N15** | 对象工具条 | 7 个带文字按钮 + 彩色图标：`Open Table`/`Design Table`/`New Table`/`New Table with AI`/`Delete Table`/`Import Wizard`/`Export Wizard`，`#F0F0F0` 底 | 6 键 + 导入导出合并 + `Refresh Table List`（Navicat 无此键），白底 | 1 |

### E 组 · 数据网格

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N16** | 行首 | **行号 + 铅笔编辑标记**；行首槽有无在帧 3/4 上不可辨，开工前 Crop 复核（§1.4 注记） | 复选框 + 行号（批量改值/删除/设 NULL 依赖勾选） | 3–4 |
| **N16-b** | 批量入口重建 | 行多选（Shift/Ctrl）+ 右键 + 底部图标条；**撤掉底部「1 row(s) selected …」批量条**（Navicat 无此元素） | 勾选模型 + 底部批量条 | （含在 N16） |
| **N17** | 当前格标记 | 橙黄 `#FFB666`/`#FFFFB6`；编辑态 = **白底蓝框行内编辑盒、值全选**、所在行灰带；**当前列表头着蓝 tint** | 2px 主色左槽 + 蓝描边；无列头 tint；行内编辑样式不同 | 1–2 |
| **N18** | 工具条溢出 | 右端 **`>>` 折叠**到溢出菜单 | 整排退成图标（**G19b 作废**） | 2–3 |
| **N19** | 工具条项集 | **13 具名键 + 右端 2 小键**（§1.4 逐键清单）；表头第二行只放类型（数值 `#` 前缀）；底部左改记录操作图标条 | 12 键项集不同、表头带约束文案、批量条占位 | 2–3 |
| **N20** | 底部栏 | 五键导航 + 页码框 + 齿轮 + 放大镜 + 2 视图切换；**记录计数移到状态栏**；无 行数/页大小/Go to page 文字控件 | 中文措辞 + 页大小 + 跳页 + `1–200 / 200 Record` | 1–2 |

> **N16 的连带后果必须写清**：去掉行首复选框 = 批量改值 / 批量删除 / 批量设 NULL 的入口全没了。
> Navicat 的做法是**多选行 + 右键 / 底部图标条**，我方要照做，
> `DataGridPage`（6185 行）的勾选模型与批量条要重写成行多选。这是 N16 成本 3–4 人日的主体。
> **N19 的 `Fill with AI` / `Data Generation` / `Data Masking` / `Create BI Workspace`**
> 与「不摆假控件」的冲突，沿用同一走法：真按钮 + 如实说明页（`Data Profiling` 同理）。

### F 组 · 查询编辑器 / 已保存查询 / 状态栏 / 弹窗 / 结构性差距

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N21** | 查询工具带 | **双行 62px**（§1.5 项集与顺序），`#F0F0F0` 底 | 单行 10 键 + 页首大标题 + Read Only 徽章 + 列类型 chips | 2 |
| **N22** | 语法配色 | §1.5 六角色实测值 | `SqlHighlighter.java` 另一套 | 1–2 |
| **N23** | 结果区三段 | 27px `Message/Summary/Result N` + 32px `Data/Info` + 网格 | 单层「Result 1 · 100 rows」页签 | 2–3 |
| **N24** | 已保存查询对象页 | 树 `Queries` 分组**可展开出文件叶子**（帧 4）+ 文件清单对象页（`Name`/`Modified Date`/`File Size`/`Modified User`）+ `Design/New/Delete Query` | `SavedQueriesPage` 形态不同，树里无分组 | 2 |
| **N25** | 状态栏段集与顺序 | **三种页型三套段集**（§1.6：查询页 6 段 / 数据页 左 SQL+右 Record·Last Refresh / 对象页 左计数+右连接·库）；**去掉「自动提交·只读」** | 9 段固定语义 | 2 |
| **N26** | 弹窗标题 | **系统标题栏**（去掉 `adb-dialog__header` 自绘条与 ×） | 原生窗口 + 内容区自绘标题条 | 2–3 |
| **N27** | 连接向导 | §1.8 两页（9+13 复选侧栏 / 5 列彩色磁贴 / 7 页签 / 900×700 / Save password / 底部五键） | 3 种库型磁贴 + 7 页签（R33 已做骨架） | 3–4 |
| **N28** | Ribbon 12 格背后的 6 个能力面 | View 对象页与增删改 / User 管理 / Backup 转储恢复 / Automation 作业 / Model ER 图 / BI 图表 | **全部为零**（grep `StageStyle`/`ErDiagram`/`Backup`/`create.*View` 无命中） | **34–52** |
| **N29** | 表设计器 | `Virtual` 列、属性面板 `Key Length`/`Binary` 两格、`Key` 合回单一下拉、`Ask AI` 键、`Number of Fields: 15` 措辞 | 九列（Key 拆三勾选）、面板三格、无 Ask AI | 2–3 |
| **N30** | 字号梯度 | 收敛到 **11/12/13** 三档 | 7 档（10/11/12/13/14/16/32） | 1–2 |
| **N31** ⭐新 | **Filter & Sort 生成器面板** | §1.9 全 anatomy：网格内嵌面板、Builder/Text 双模式、条件行（字段链接 + 操作符 + 值/`<?>` + `and` 连接词）、`[+]`/`[Ω]` 加条件/组、Sort By 芯片 + 升降序、**显式 `Apply Filter & Sort` + `Criteria Edited` 脏标记**、工具条按钮按下态开关面板 | `ConditionDialog`（弹窗）+ `ColumnFilterPanel`（每列漏斗）+ 表头排序徽标；能力在、形态完全不同 | **4–6** |
| **N32** ⭐新 | **多库连接与库级树** | 连接 → **库 → 分组 → 叶子** 三级（帧 4 实测）；页签标题与状态栏带库名（§1.10/§1.6）；SQLite 等单库引擎是否平铺待手册核对（§1.3 注记） | `SideBar.java` 无库级；连接配置即单库；模型层连接定义不支持库清单 | **3–5** |
| **N33** ⭐新 | **对象页二合一** | Navicat 只有一个对象清单页（点库即开，6 列全类型各自入页）；AIDB 的 `ObjectHomePage`（1403 行，药丸总览 + 分组卡片）与 `TableListPage` 并存是**多余的一页** | 两页并存，Objects 主页带 `全部/表/视图/触发器` 药丸 —— Navicat 无此概念 | **2–3** |
| **N34** ⭐新 | **数据页三个新能力面** | `Data Generation`（造数）/ `Data Masking`（脱敏）/ `Create BI Workspace`（BI 工作区）—— 帧逐键辨认出的三个新零能力点 | 无任何对应物（BI 归 N28-f 记账，前两个独立） | **1–2**（说明页先行；真功能并 R42） |

---

## 3. 推翻台账（已交付、本轮要重做或作废）

| 已交付 | 轮次/提交 | 命运 | 代价 |
|---|---|---|---|
| 顶区精简 114px / 64px 命令带 / 删品牌块 / 工具件并入页签行 | R32-D（`e595c1f`） | **作废** → N5/N6/N7 | 2 人日重做 |
| 工具条「整排退成图标」 | R32-G19b（`fde2d82`） | **作废** → N18 改 `>>` 溢出 | 1–2 人日 |
| 状态栏「自动提交 · 只读」安全语义段 | U8/历轮 | **删除** → N25 | 0.5 |
| 行首复选框 + 批量勾选模型 + 底部批量条 | 早期轮 | **替换**为行号+铅笔+行多选+底部图标条 → N16/N19 | 3–4 人日 |
| 强调色 `#0067B8`（WCAG AA 修正，旧 §7.12） | R30-A | **作废** → `#0078D7`，`ContrastTest` 改判并写明「主动放弃 AA」 | 0.5 |
| 单色线性图标体系（旧 §1.5.1） | 全程 | **作废** → N3/N4 | 10–14 人日 |
| 「磁贴只摆真实支持的 3 种」（旧 §7.15） | R33-G20 | **作废** → N27 全摆 + 不支持项给如实说明页 | 1–2 |
| 「Ask AI / New Table with AI 不做」（旧 §7.16） | 裁定 | **作废** → 真按钮 + 说明页（N15/N19/N29） | 1 |
| 「Virtual 生成列不做」（旧 §7.17） | 裁定 | **作废** → N29 | 1–2 |
| 「弹窗自绘标题栏保留尺寸记忆」（旧 §1.5.3） | 全程 | **作废** → N26 | 2–3 |
| 导航树 21px「已对齐」 | 旧 §1.1 | **改判**：树 20px，对象清单 21px（两控件不同高） | 0.5 |
| 「AIDB 114 vs Navicat 170 同线」 | 旧 §1.1 | **改判**：Navicat 顶区实测 140px，我方 114px | — |
| **数据页工具条 5 项集（Table Profile/Begin Transaction/Column Filter/Fill with AI/Data Profiling）** | 10-28 版 N19 | **作废** → 13 具名键实测清单（N19 重写） | 记账误差，不是返工 |
| **「Filter & Sort = 弹窗 + 每列漏斗」形态** | 现行 `ConditionDialog`/`ColumnFilterPanel` | **作废** → 网格内嵌生成器面板（N31） | 4–6 人日 |
| **「树 = 连接 → 分组」两级模型** | 现行 `SideBar` | **作废** → 连接 → 库 → 分组（N32） | 3–5 人日 |
| **「Objects 主页 + Table List 并存」** | 现行 | **作废** → 二合一（N33，去留方式待拍板 P6） | 2–3 人日 |
| R30/R31/R32(G2/G3/G4)/R33 的功能深度（事务条、查找替换、表单视图、七页设计器、导入导出深度、SSL/URI） | — | **保留**，只是换外壳与措辞 | — |

**净效果**：功能资产基本保住；**视觉与语言层几乎重建，另加三处结构性重做**（N31/N32/N33）。

---

## 4. 轮次规划（R34 → R42）

> 每轮口径不变：先方案 → 确认 → 动代码 → 门禁全绿 → 重拍帧 → 逐张目视 → 分批本地提交（**push 需单独授权**）。
> 排序原则：**前置依赖优先**（i18n 卡所有标签；图标渲染层卡所有彩色资产；顶区卡页签行/工具带底色）。

| 轮 | 内容 | 条目 | 估（人日） |
|---|---|---|---|
| **R34**（进行中） | i18n 层 + 双语资源 + 默认英文 + 菜单项集；**已收 14 批（棘轮 479，已落库）**，剩 41 文件（IndexDialog 41 / ObjectSectionsPanel 37 / ExportWizard 36 为大头）+ P4 冷启动复测 + 收官验收 | N1 N2 | 8–12（剩 1–3） |
| **R35** | 彩色图标渲染层 + 50 枚资产 + 图标门禁改判 | N3 N4 | 10–14 |
| **R36** | 顶区 140px / 90px Ribbon / 页签行只放页签+标题格式 / 工具带 `#F0F0F0` / 分隔线 / `#0078D7` | N5–N10 | 5–7 |
| **R37** | 导航树（20px / 五组 / 去计数 / 挪走三 navButton）+ 对象清单（6 列 / 7 键 / 修 D-3）+ **库级树（N32）** + **对象页合一（N33）** | N11–N15 N32 N33 | **8–11** |
| **R38** | 数据网格（行号+铅笔 / 行内编辑盒+列头 tint / `>>` 溢出 / 13 键项集 / 底部栏与记录操作条）+ **Filter & Sort 面板（N31）** | N16–N20 N31 | **13–18** |
| **R39** | 查询编辑器（双行工具带 / 语法配色 / 结果三段 / 状态栏三页型）+ 已保存查询对象页 | N21–N25 | 9–12 |
| **R40** | 弹窗系统标题栏 + 连接向导两页全量 | N26 N27 | 5–7 |
| **R41** | 表设计器收口 + 字号梯度三档 | N29 N30 | 3–5 |
| **R42** | **能力面收尾**：Ribbon 6 面（View/User/Backup/Automation/Model/BI）+ 数据页 3 新面（Data Generation/Data Masking/BI Workspace 归 BI） | N28 N34 | **36–56** |

- **R34–R41 小计：61–86 人日**（纯「做到一样」）。
- **加 R42：97–142 人日**（「连功能面也不缺」）。
- 参考：R30–R33 四轮实际产出 1 179 条门禁 / 33 帧，估算 25–30 人日、实际每轮一个会话。
  按同样的执行密度，R34–R41 约 9–11 个会话，R42 约 7–11 个会话。

**R34 收尾清单**（当前轮，先于一切新轮次）：
① 落库：第八/九/十批 + 本文档重写（**已完成**：`46873b8`/`f0c1044`/`6e99d60` + 随批 docs）；② 剩余抽取按字面量数排：
`NewTableDialog`(75) / `ImportWizard`(61) / `CellDetailsDrawer`(39) + 44 个小文件；
③ P4 冷启动复测（ResourceBundle 不得把 3.5–3.9s 首屏拖过 3.9s）；
④ 全量门禁 + 33 帧全英文重拍逐张目视 + md5 去重 + 回填三处。

---

## 5. 门禁改判清单（改判要写明理由，不许「改绿」）

| 门禁 | 现断言 | 改判为 | 理由 |
|---|---|---|---|
| `ContrastTest.java:39`（`MIN_NORMAL = 4.5`）+ `:65` | `text-inverse/accent` ≥ 4.5:1 | 阈值 4.4:1 + 注释「2026-10-28 用户裁定取 Navicat 原值 `#0078D7`，实测 4.498:1，主动放弃 AA」 | 拍板 4 |
| `TokenParityTest.java:63` | 命令带 64px | 140px 顶区 / Ribbon 91px / 磁贴 70×90 | N5/N6 |
| `AppBarTest`（6 格 / 高 64 / 标签不裁） | 恰好 6 个 `ribbon-*` | 恰好 **12 格**、高 90、激活态 `#C0DCF3`+`#90C8F6`、组间 `#DBDBDB`；宽度门禁保留 | N6 |
| `AppShellTest.java:161-182` | 顶区两行、工具件在 `#adb-chrome-row` | 顶区三行（菜单/Ribbon/页签）、页签行**只有页签**、页签标题带 `@库 (连接)` | N5/N7 |
| `BrandMarkAuditTest` | 禁 `appbar-brand` | 维持（Navicat 顶区也没有品牌块） | N26 |
| `LucideIconTest.java:129,213-233` | 自绘集恰 `{BRAND,DOT}`、源 SVG `stroke-width=2` | 彩色资源规则（多 path、逐 path paint、色值落在实测色板内） | N3/N4 |
| `IconSystemAuditTest.java:136` | `SIZE_RIBBON == 26` | **30**（实测磁贴图标 ≈30×30） | N4 |
| `ChromeMetricsTest.java:143-148` | tabbar 26 / header 40 / toolbar 30 / pager 38 / statusbar 22 | tabbar **25** / statusbar **23** / 工具带底色 `#F0F0F0` / 顶区两条 `#DBDBDB` | N5/N8/N9 |
| `Tokens.ROW_TREE_LIGHT` | 21 | **20**（树），对象清单保持 21 | N11 |
| `SelectionStateTest` | 选中行 `-adb-bg-selected` + `-adb-accent` | 维持，另加「当前格 = 橙黄 + 编辑盒 + 当前列头 tint」 | N17 |
| `ToolbarDiscoverabilityTest` | 具名操作留文字 / 瞬时编辑键纯图标（第四批改判） | **再改判**：13 具名键项集对账（§1.4 清单），放不下折进 `>>` 且可键盘到达 | N18/N19 |
| `DataGridPagerFitTest` | 按 `prefWidth` 逐档收起 + `USE_PREF_SIZE` | 维持判据；项集改五键导航 + 页码 + 齿轮 + 放大镜 + 视图切换；记录计数移状态栏 | N20/N25 |
| `SideBarTreeTest` | 六组 + 计数 | 五组、无计数、**Queries/Backups 存在**、（N32 后）库级层级 | N12/N32 |
| `I18nAuditTest`（13 条） | 两套键集相等 / 棘轮 479 只许降 / 模型层 277 只许降等 | 维持；随抽取批次棘轮数字只降不升 | N1 |
| 新增 `SqlSyntaxColorTest` | — | 六角色实测色值逐一对账 | N22 |
| 新增 `RibbonCapabilityAuditTest` | — | 12 格每格必须落到真实目标（页面或如实说明页），不许空 `onAction` | N6/N28 |
| 新增 `FilterPanelAuditTest` | — | 面板开关态与工具条按钮按下态联动；`Criteria Edited` 只在条件变更后出现；`Apply` 前不执行 | N31 |
| 新增（N33 落地时） | — | Objects 主页删除后其旧门禁同步删除或改判，不留孤儿断言 | N33 |

**已沉淀的口径教训**（完整叙事在执行视图与 git 历史，此处只留会影响写门的规则的）：

- CSS 门禁必须连**权重与声明顺序**一起比（R31 `:filled:odd` 教训：静默压死所有行态规则）。
- 门禁断言**结果**（渲染宽度 vs prefWidth），不是手段；每条改判配一次变异验证（破坏 → 恰好 N 红 → 还原 → 绿）。
- 自证断言数**扫描人口**（`seen.size() >= N`），不是违规数非空；TabPane 只挂选中页签，`lookupAll` 前逐页签 select。
- 棘轮抓过自己人：诊断日志里的中文也计数（`ScreenshotSession` 8→9 红过一次）；ASCII 日志 + 注释解释。
- 「重拍 33 帧」≠「逐张看 33 帧」：D-5 裁字在帧库里活了两轮才被目视抓到 —— 目视必须看并排帧。
- 验收 ≠ 落库：每轮收官动作里**提交与回填同级**，不留「已验收未落库」过夜（R33 的教训；
  第八、九、十批这次当天落了，但这条红线永远生效）。

---

## 6. 待拍板（本文不替你决定）

| 编号 | 问题 | 我的建议 |
|---|---|---|
| **P1** | R42 的能力面（View/User/Backup/Automation/Model/BI + Data Generation/Data Masking）做不做？不做则对应格/键给「本版本未提供」如实说明页。 | **分两半**：View / Backup / Model(ER) 有明确用户价值先做（约 14–21 人日）；Automation / BI / User / Data Generation / Data Masking 暂缓，控件保留但给如实说明页。 |
| **P2** | 默认英文后，中文资源是否同步维护？ | 要。两套同时维护，`I18nAuditTest` 键集相等兜底；不机器翻译，按现有中文原文反向抽取（前 8 批即此做法）。 |
| **P3** | 暗色主题在彩色图标下要不要重做一套？ | 要。R35 一并出暗色档，否则暗色帧黑底黑标。 |
| **P4** | 冷启动（实测 3.5–3.9s）与 i18n 资源加载冲突吗？ | 不冲突，但 R34 收官前复测一次。 |
| **P5** | 旧 §8 边界（jlink ⛔ / 自动更新 🚫 / 代码签名 ⛔）维持吗？ | 维持 —— 与「像不像」无关，是发布工程问题。 |
| **P6** ⭐新 | **对象页二合一（N33）往哪边合？** AIDB 的 Objects 主页（药丸总览）在 Navicat 没有对应物。 | **删 Objects 主页，保留并扩展 Table List 为唯一对象清单页**（6 列、7 键、点库即开）。风险：Objects 主页是现在的落库页，删除后首开体验要靠树选中态补位。 |
| **P7** ⭐新 | **库级树（N32）的范围**：MySQL/PG 连接显式挂库级；SQLite 单库是否平铺直挂（9 张帧全是 MySQL，无直接证据）。 | 照 Navicat 行为：多库引擎出库级，单库引擎平铺。落地前用 Navicat 手册 + 本地 SQLite 连接实测核对一次。 |

---

## 7. 验收口径（每轮固定，沿用）

1. **门禁**：全量 0 失败；新能力必须有行为门禁（不是文本 grep）+ 一次变异验证。
   CSS 门禁必须连**权重与声明顺序**一起比（R31 的 `:filled:odd` 教训）。
2. **帧**：涉视觉的轮次全量重拍 + **逐张目视** + md5 两两去重；`shots.sh` 的宽×高守卫保留（只报变化、对错人看）。
   ⭐ 每张 AIDB 帧必须并排一张同场景 Navicat 帧，在 `docs/screenshots/side-by-side/` 生成对照图，
   目视看的是**并排**而不是单张（旧 `side-by-side*.png` 过时，重做）。
3. **回填**：基线数字、本文 §2 状态、§4 轮次结果三处同轮更新；**所有数字必须回量**（量法见 §8）。
4. **提交**：验收后分批本地提交；**push 需单独授权**；每轮收官**提交与回填同级**，不过夜。
5. **诚实条款**：任何「看起来一样但点了没反应」的控件一律不许上；
   做不到真功能的位置放**如实说明页**（G8 的 SSH/HTTP 先例），不放假开关、不放死按钮。

---

## 8. 当前基线（2026-10-29，R34 第十四批之后）

- 门禁 **1180 条 / 134 类 / 0 失败 / 48 环境跳过**（第十四批后 `./mvnw -o test` 实测 —— 与第八至十三批持平：
  第九至十四批只降棘轮、未立新门禁；第八批曾恰比第七批的 1179 多 1，即第八批新立的对象树菜单文字对账门禁）。
  **量法（照抄，别再凭记忆写数）**：`./mvnw -o test` 后
  `grep -aoP 'Tests run: \K[0-9]+(?=, Failures: [0-9]+, Errors: \d+, Skipped: \d+, Time elapsed)' 日志 | awk '{s+=$1;n++} END{print n,s}'`
  → **150 组 / 1180 条**；类数按 `**/target/surefire-reports/*.txt` 计数 = **134**（150 与 134 之差是 `@Nested`）。
  ⚠️ 全量必须 JDK 25+：`JAVA_HOME` 留在系统默认 21 会被 enforcer 直接 BUILD FAILURE
  （本机 `C:\Users\Administrator\.jdks\openjdk-27.0.1.0.2`）。
- 已落库：`34ffb8f`（R33 全部 + R34 首批）· `288592d`（N1-d）· `05351ce`（N2 菜单）· `87d2ddb`（窗口标题）·
  `c6ae488`（第三批）· `3077924`（第四批）· `91c4084`（第五批）· `7e8b6f7`（第六批）· `88a4576`（第七批）·
  `46873b8`（第八批）· `f0c1044`（第九批）· `6e99d60`（第十批）· `ed41207`（第十一批）·
  `7def1cf`（第十二批）· `d20f5eb`（第十三批）· `fa23770`（第十四批）+ 各批 docs 回填。**push 仍未授权。**
- 验收帧 **33 张**（28 亮 / 5 暗），第八批后全量重拍为英文档（19 帧变化、1280×800、无重复帧）；
  第九批重拍连接向导 4 帧 + p5-connections（全英无裁字，p5-connections 中文属 `ConnectionsPage` 未抽）；
  第十批重拍 p0-shell（对象首页全英；顺带修掉 ScreenshotSession 把 Tooltip 误当拍摄目标导致玻璃层崩溃的采集缺陷）；
  第十一批重拍 p6 建表对话框 7 帧（全英；目视抓到自增列头在 46px 里被截成「Auto …」，列宽 46 → 72px 后重拍核销）；
  第十一批顺带把建表计划报错路由改为 `DesignStepException`（按 FOREIGN_KEY/INDEX/TRIGGER 类型选页签，
  不再 `contains("索引")` 消息匹配，为 D-9 文案双语化铺路）；
  第十二批重拍 p8-import（目视全英；Import Mode 下拉的「追加」属模型层 `ImportMode.label()`，
  第 13 条棘轮范围，原帧即有、非回归）；
  第十三批重拍 p2-cell（目视全英：「name · Editing」标题、Text/JSON/Hex/Image/Metadata
  页签、Apply/Cancel/Copy）+ p2-details 同步重拍（右侧面板中文属 `ObjectDetailsPanel`，后续批次）；
  第十四批重拍 p5-connections（目视全英：Manage Connections / 八列 / Connected /
  「1 connections · 1 shown」；搜索框 220px 定宽提示省略号属既定裁定非缺陷）。
  ⚠️ 中英混排仍在：索引弹窗（41 条）、对象概况面板（37 条）、导出向导（36 条）等未抽 ——
  **中间态不是验收态**。
- **N1 进度**：ui 主源码 CJK 字面量 **2216 → 479 次出现**（14 批；去重 388 条待抽，剩 **41 个文件**），
  资源包 **1130 键**（en/zh 对称）。模型层另有 **277** 条（`MODEL_CJK_CEILING`，D-9）。
  棘轮量法：`node tools/cjk-count.mjs`（主尺）/ `--model`（模型尺）。
- 未结缺陷：**D-1**（分页器被详情浮层压住 90px，归 R38 N19/N20）、**D-3**（表清单 1280 只剩 2 列，归 R37 N14）、
  **D-4**（README「已知限制」口径冲突）、**D-9**（`StructureSqlBuilder` 17 个中文标题，104 条模型层字面量，
  修法 = `StructurePlan` 改「键+参数」由界面渲染，独立一批）。
- 本文替换：10-28 版的 §0/§2/§3/§4/§6 结论与裁定全部作废（§1 像素实测沿用并复核）；
  10-28 版全文在 git 历史（`ff2e7d4` 及此前）可查。
