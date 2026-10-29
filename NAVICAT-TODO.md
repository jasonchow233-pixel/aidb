# NAVICAT-TODO — 「和 Navicat 一模一样」真源（2026-10-28 重写）

> **本文是唯一的产品/规划真源**，替换 2026-10-26 版的全部口径。
> 上一版的主线是「对齐但保留我方刻意差异」；本版的主线是**取消一切刻意差异**。
>
> **触发**：2026-10-28 用户补发 7 张 Navicat Premium 17.3.6 实拍（其中两张是**新的 1920×1040 全窗口帧**：
> 查询编辑器、已保存查询清单），并裁定「删除原有的规划，重写规划，重写 todo，做到和 Navicat 一样，
> 布局/字体/icon/字号/icon 图案颜色/菜单/状态栏/树形目录/展开/SQL 查询/编辑，**要一模一样**」。
>
> **四项拍板**（2026-10-28，本文全部结论以此为前提）
> 1. **顶区**：完全照 Navicat —— 140px 顶区 + 90px Ribbon + 12 格磁贴。**R32-D 的精简作废**。
> 2. **图标**：自绘同风格彩色图标（不提取 Navicat 原始位图 —— 那是他人版权素材，不能进我们的产品）。
> 3. **语言**：新建 i18n 层，中英双语，**默认英文**（与实拍一致）。
> 4. **刻意差异全部拿掉**：行首复选框 → 行号+铅笔；强调色 → Navicat 原值 `#0078D7`；
>    弹窗自绘标题条 → 系统标题栏；状态栏去掉「自动提交 · 只读」安全语义段。
>
> **证据来源**
> - 官方手册：Navicat 17《用户手册》中文版 371 页。
> - 实拍帧：`docs/screenshots/navicat/` **11 张** 1:1 帧（另 2 张 `side-by-side*.png` 是 2026-10 的过时对照图，
>   按 §7 第 2 条重做）。本轮新增 **2 张 1920×1040 全窗口帧**：
>   `navicat17-query-editor-2026-10-28.png`（查询编辑器）/ `navicat17-saved-queries-2026-10-28.png`（已保存查询清单）。
> - 本方帧：`docs/screenshots/` 33 张（28 亮 / 5 暗）。
> - 代码：结论带 `文件:行号`；像素值为**实拍实测**，非估算。三个常驻量具（都是单文件、无依赖）：
  `tools/Pixel.java`（取点色值）、`tools/Find.java`（按色值/文字定位像素）、
  `tools/Crop.java`（<b>裁剪 + 整数倍放大</b>：1280 宽的原图上肉眼看不出 1px 的重叠与半截字，
  本轮「History 是不是被裁了」就是靠 8× 裁图判的 —— 结论是没裁，量到文字 37.6px 在 88px 的标签区里，
  差点凭错觉去改 CSS）；棘轮计数走 `node tools/cjk-count.mjs`（与 `I18nAuditTest` 同构，
  ⚠️ `grep -hoP` 数出来是 **1530** 而棘轮是 **1504**（第四批那次），差在注释里 —— 两个口径混用就会看起来「棘轮又涨了」。）。
  裁图产物 `docs/screenshots/*-crop-*.png` 是**临时件**，看完就删 —— 留在里面会被
  `shots.sh` 的 md5 去重当成假帧报出来。

---

## 0. 一句话结论

**这不是一轮视觉微调，是一次跨 8–9 轮的重建。**
上一版真源把「像 Navicat」判定为已基本完成（只剩图标风格与命令数），那个结论在
「要一模一样」的口径下**不成立**：实测 Navicat 顶区 140px / 我方 114px、树行 20px / 我方 21px、
Ribbon 图标是多色渐变位图 / 我方渲染层**结构上做不出多色**、界面英文 / 我方**没有任何 i18n 层**
（1 404 条 CJK 字面量硬编码在 Java 里）。

**最大的成本不在样式，在顶区那 12 格里有 6 格我方根本没有对应功能**
（View 对象页 / User 管理 / Backup / Automation / Model ER / BI）。
把顶区做成一样 = 顺手把这 6 个能力面从零建出来。见 §4。

---

## 1. 实测基线（2026-10-28 新帧，1920×1040 @100% 缩放）

### 1.1 纵向分区（x=1900 逐像素扫描）

| 区 | Navicat 实测 | AIDB 现状 | 差 |
|---|---|---|---|
| 菜单栏 | y 0–22 → **23px**，`#FFFFFF` | 24px 系统菜单，`#FFFFFF` | −1px |
| **Ribbon** | y 23–113 → **91px**，`#F0F0F0` | **64px** 单命令带 | **+27px** |
| 分隔线 | y 114 → **1px `#DBDBDB`** | 无独立分隔线 | 缺 |
| 页签行 | y 115–139 → **25px**，`#FFFFFF` | 26px（且塞了连接 pill/搜索/主题/⋯） | −1px + 内容不同 |
| 分隔线 | y 140 → **1px `#DBDBDB`** | 无 | 缺 |
| 页面工具带 | 对象清单/查询页：**30px**（帧 2 y141–170）或 **62px 双行**（帧 1 y141–202），底色 **`#F0F0F0`** | 30px **白底** | 底色不同 |
| 内容区 | y 171/203 起 `#FFFFFF` | 同 | — |
| 水平滚动条带 | 22px `#F0F0F0` | 系统默认 | 待对齐 |
| 状态栏 | **23px** `#F0F0F0`，顶边 `#D7D7D7`，段间 `#D7D7D7` 1px 竖线 | 22px，段间分隔样式不同 | +1px |

**Navicat 全局顶区 = 23 + 91 + 1 + 25 = 140px。** 我方 114px。
（上一版真源写的「AIDB 114 vs Navicat 170，同线」是把页面内工具条算进来了 —— 那个口径作废：
Navicat 的 91px Ribbon 本身就比我方 64px 命令带高 27px。）

### 1.2 Ribbon 磁贴（帧 1 实测）

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

### 1.3 导航树（帧 1 实测，x=81 图标列）

| 参数 | 实测 | AIDB | 差 |
|---|---|---|---|
| 行距 | **20px**（y=130/150/170/190/210/230/250/270/290） | **21px**（`Tokens.ROW_TREE_LIGHT`） | **−1px** |
| 表图标 | 4 色蓝：`#077ED5` 描边 + `#CDE8FF` / `#B8DFFF` / `#96D0FF` 三档填充 | 单色线性 | 完全不同 |
| 结构 | **表平铺**（无「表」分组），其下是 `Views` / `Functions` / `Queries` / `Backups` 四个分组 —— 即 Navicat 的分组集合恒为 **五个**（多库视图下表才收成 `Tables` 组）：`Tables`/`Views`/`Functions`/`Queries`/`Backups` | `表 4` / `视图 1` / `触发器 1` 分组 + 计数徽标，另有 `过程`/`函数`/`事件` 组 | 结构不同：**缺 `Queries`+`Backups`，多 `过程`/`触发器`/`事件`** |
| 计数徽标 | **树里没有计数** | 分组标题带计数 | 多余 |
| 底部 | `Search` 输入框 + 右下三个视图切换小图标 | 搜索框 + ☆ + « | 部分 |
| 树外工具 | 无 | `查询历史` / `诊断` / `导入·导出` 三个 navButton（`SideBar.java:671-681`） | Navicat 没有，要挪走 |

> 上一版真源 §1.1 写「导航树行高 21px，已对齐」—— 那是从 1219×893 帧量的**对象清单**行距。
> 树行距实测 **20px**，对象清单行距 **21px**（帧 2 x=340 文字基线 y=248/269）。两个控件不同高，此前混为一谈。

### 1.4 数据网格 / 结果网格（帧 1、帧 6）

| 参数 | 实测 | AIDB | 差 |
|---|---|---|---|
| 行高 | **23px**（发丝线 y=712/735/758/781/804/827/850） | 23px | **0** |
| 行发丝线 | `#F0F0F0` | `#F0F0F0` | 0 |
| 表头（数据页） | 双行：字段名粗 + 类型灰斜体 `#` 前缀 | 双行 | 已对齐 |
| 表头（结果页） | **单行**，`#E7E7E7` 底，高 ≈26px | 结果表头样式待核 | 需核 |
| 当前格标记 | 橙黄系 `#FFB666` / `#FFFFB6`（实测 y=700–703、746–749） | 2px 主色左槽 + 蓝描边 | 完全不同 |
| 行首 | **行号 + 铅笔编辑标记** | **复选框 + 行号** | 拍板改 |
| 工具条溢出 | 右端 **`>>` 折叠按钮**（帧 6 实测） | 整排退成图标（G19b） | 拍板改（G19b 作废） |
| 底部 | `Record 7 of 100 in page 1` + 首页/上页/页码/下页/末页 + 刷新 + `Last Refresh: 37m` | 中文措辞 + 页大小 + 跳页 | 措辞/项集 |

### 1.5 查询编辑器（帧 1，**本轮新增的关键证据**）

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

**SQL 语法配色（box 直方图实测，取glyph 主色）**：
| 角色 | 实测 |
|---|---|
| 关键字 `SELECT` / `DISTINCT` / `FROM` / `WHERE` / `GROUP BY` | **`#097BF7`**（加粗） |
| 字符串 `'2026-07-20'` / `"REPAYMENT_COMPLETE"` | **`#FF1744`** |
| 注释 `-- ...` | **`#929292`** |
| 普通标识符 `phone_number` / `STATUS` | **`#424242`** |
| 表名 `t_mkopopap_order` | `#424242` + **下划线** |
| 行号列 | 灰字，`#F9F9F9`/白底待精测 |

我方 `SqlHighlighter.java` 现走另一套配色（蓝关键字 + 无字符串红 + 无表名下划线），需整体重标定。

### 1.6 状态栏（帧 1 实测，y=1023 段间竖线）

Navicat 查询页状态栏 = **6 段**，段间 `#D7D7D7` 1px 竖线：

| 段 | 实测宽度 | 内容 |
|---|---|---|
| 1 | 0–1281 | 当前 SQL 全文（左对齐，可截断） |
| 2 | 1283–1361（79px） | `Read Only` |
| 3 | 1363–1521（159px） | `Record 1 of 20473` |
| 4 | 1523–1705（183px） | `Ln 11, Col 5` |
| 5 | 1707–1864（158px） | `Elapsed Time: 18.735s` |
| 6 | 1866–1918 | 视图切换小图标（列表/网格） |

帧 2（对象清单页）状态栏左端是 `4 Queries`，右端是 `fetch` + `loanmarket`（当前对象名 + 库名）。
我方 `StatusBar.java` 是 9 段固定语义（作用域/范围/消息/选择/耗时/会话/刷新于/脏值/SQL）——
**要按页型给出 Navicat 的段集与顺序**，并去掉「自动提交 · 只读」段（拍板 4）。

### 1.7 已保存查询（帧 2，**本轮新增**）

Navicat 把保存的查询当**对象**管：树里 `Queries` 分组 → 选中后对象页是文件清单，
工具条 `Design Query` / `New Query` / `Delete Query`，列 = `Name` / `Modified Date` / `File Size` / `Modified User`，
行高 21px，状态栏左 `4 Queries`。
我方 `SavedQueriesPage.java` 存在但列集/工具条/入口形态不同（且树里没有 `Queries` 分组）。

### 1.8 新建连接（帧 3、帧 4，900×700）

- **类型页**：左侧 `Connection Filter`（MySQL/PostgreSQL/SQL Server/Oracle/SQLite/MariaDB/MongoDB/Redis/Snowflake —— **9 个复选**）
  + `Vendor Filter`（Alibaba Cloud/Huawei/Tencent/Dameng/Kingbase/PingCAP/HighGo/OceanBase/AWS/Fujitsu/Google Cloud/Microsoft/Oracle —— **13 个复选**）；
  右侧 **5 列彩色磁贴**（图标约 48px + 名称在下）；右上搜索框；左下 `New Connection with URI…`；右下 `Next` / `Cancel`。
- **常规页**：`General/Advanced/Databases/SSL/SSH/HTTP/Remarks` 7 页签；`Navicat ⟷ Database` 示意图居中；
  右对齐标签 `Connection Name` / `Host` / `Port` / `User Name` / `Password` + `Save password` 复选；
  底部 `Test Connection` / `URI…`（左）· `Back` / `OK` / `Cancel`（右）。

> ⚠️ 与 §7.15 旧裁定（「磁贴只摆真实支持的 3 种，不摆点不动的厂商入口」）**直接冲突**。
> 本版按「一模一样」执行：磁贴全摆。对不支持的库型，沿用 G8 里 SSH/HTTP 已经立过的先例 ——
> **点进去是一个如实说明页**（"本版本不支持 SQL Server"），而不是一个填了也没用的假表单。
> 这是「布局一模一样」与「不摆假控件」两条之间唯一能同时满足的走法。若不接受，请回退到 §7.15。

---

## 2. 差距清单（N 系列）

> 每条 = 差距 · 目标实测值 · 现状 · 落点文件 · 成本（人日，单人，工程估算）。
> 编号 N1…N28，按「改一处影响一片」的顺序分组。

### A 组 · i18n（前置，卡住所有标签类条目）

| 编号 | 差距 | 目标 | 现状 | 成本 |
|---|---|---|---|---|
| **N1** | 无 i18n 层 | 两套资源包（en 默认 / zh），键名按区命名空间 | **层已建**（`I18n.java` + 两份 `.properties`，701 键对称）。抽取进度：出现 **2216 → 1145**、去重 **810** 条待抽，剩 **51 个文件**；`QueryPage`(161→0) / `AppShell`(198→0) / `DataGridPage`(413→0) / `TableListPage`(70→0) / 顶区 / 动作层已收 | 8–12 |
| **N2** | 菜单标签与项集 | `File/Edit/View/Query/Format/Favorites/Tools/Window/Help` | `文件/编辑/视图/数据库/对象/收藏夹/工具/窗口/帮助`（`MenuBarFactory.java:27-31`，9 组但项集不同） | 并入 N1 |

落点：新增 `ui/adb-ui-core/.../I18n.java` + `messages_en.properties` / `messages_zh.properties`；
`MenuBarFactory`、`ActionRegistry` 显示名、所有 `setText("中文")` 全量替换。
新门禁 `I18nAuditTest`：① 两套键集完全相同；② ui 主源码里**不允许**出现 CJK 字面量（注释除外）。

### B 组 · 图标渲染层与资产

| 编号 | 差距 | 目标 | 现状 | 成本 |
|---|---|---|---|---|
| **N3** | 渲染层做不出多色 | `ColoredIcon`：`Group<SVGPath>`，逐 path 保留 fill/stroke/渐变；`Icons.of()` 返回 `Node` 而非 `SVGPath` | `LucideConverter.java:57` 压成单 path；`Icons.java:326-327` 单 paint | 3–4 |
| **N4** | 约 50 枚彩色图标 | 按 §1.2/§1.3 实测色值自绘：Ribbon 12 枚 30px 多色渐变、树 16 枚四色、工具条 16 枚、磁贴 48 枚 | 46 枚单色 Lucide | 7–10 |

落点：`Icons.java`、`LucideConverter.java`、`icons/lucide/*.svg` → 新增 `icons/color/`；
调用点 `IconButtons.java`、`AppBar.java`、`SideBar.java`、`TabBar.java`。
**门禁改判**：`LucideIconTest` 的三条（自绘集合恰为 `{BRAND,DOT}`、源 SVG 必须 `stroke-width="2"`、
SVG↔枚举 1:1 无死资源）与 `IconSystemAuditTest.java:136`（`SIZE_RIBBON == 26`）按彩色资源重写 —— Ribbon 图标实测 30px。

### C 组 · 顶区与外壳

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N5** | 顶区高度 | **140px** = 菜单 23 + Ribbon 91 + 1 + 页签 25 | 114px（R32-D） | 1–2 |
| **N5-b** | ⚠️ **令牌源已经和生成物不一致** | `tokens/tokens.json:277-280` 的 `h-appbar` 仍是 **90px**，而生成物 `Tokens.java:232-233` 是手改的 **64**（该文件头写着「⛔ 不要手改」，`tools/GenTokens.java:165` 会读尺寸令牌） | R32-D 当时只改了生成物没改源，**任何人重跑 GenTokens 都会把 64 顶回 90 并让 `TokenParityTest` 红** | 0.5 |

> **N5-b 的正解**：R36 第一步不是改 CSS，是**把 `tokens.json` 写成 Navicat 实测值、再重新生成 `Tokens.java`**，
> 让源与生成物重新同源 —— 本轮恰好要回到 90px，所以这条债顺手就能清掉，但**必须按「改源 → 重生成」的顺序做**，
> 否则下一轮又攒一条同型的债。
| **N6** | Ribbon 磁贴 | 90px 高、70px 宽、**12 格**、图标 30px 在上 + 标签在下、激活 `#C0DCF3`+`#90C8F6` 描边、组间 `#DBDBDB` 竖线 | 64px、6 格、图标 26px 单色 | 2–3 |
| **N7** | 页签行内容 | **只放页签**（+侧栏折叠箭头） | 页签 + 连接 pill + 全局搜索 + 主题 + ⋯ | 1 |
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
| Model | — | ❌ **无 ER 图**（原 G9 的 ER 项） |
| BI | — | ❌ **无图表/透视** |

→ 单列 **N28**（见 F 组），成本 34–52 人日，**是「一模一样」的真实价签**。

### D 组 · 导航树与对象清单

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N11** | 树行距 | **20px** | 21px（`Tokens.ROW_TREE_LIGHT`） | 0.5 |
| **N12** | 树结构 | 分组集合恰为 **五个**：`Tables`/`Views`/`Functions`/`Queries`/`Backups`；连接直接落在单个库上时**表平铺**（帧 1/2 实测 `t_*` 平铺 + 其余四组）；**树里没有计数徽标** | 六组 `表/视图/过程/函数/触发器/事件` + 计数（`SideBar.java:1087-1095`）；**缺 `Queries`/`Backups` 两组，多 `过程`/`触发器`/`事件` 三组** | 1–2 |
| **N13** | 树外三个 navButton | 移入菜单/工具，树里只有连接树 | `查询历史`/`诊断`/`导入·导出`（`SideBar.java:671-681`） | 1 |
| **N14** | 对象清单列 | **6 列**：`Name`/`Rows`/`Data Length`/`Engine`/`Modified Date`/`Comment` | 8 列（多 创建日期/排序规则）；**D-3：1280 宽只剩 2 列** | 1–2 |
| **N15** | 对象工具条 | 7 个带文字按钮 + 彩色图标：`Open Table`/`Design Table`/`New Table`/`New Table with AI`/`Delete Table`/`Import Wizard`/`Export Wizard`，`#F0F0F0` 底 | 5 个 + 导入导出合并，白底 | 1 |

### E 组 · 数据网格

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N16** | 行首 | **行号 + 铅笔编辑标记** | 复选框 + 行号（批量改值/删除依赖勾选） | 3–4 |
| **N17** | 当前格标记 | 橙黄 `#FFB666`/`#FFFFB6` | 2px 主色左槽 + 蓝描边 | 1 |
| **N18** | 工具条溢出 | 右端 **`>>` 折叠**到溢出菜单 | 整排退成图标（**G19b 作废**） | 2–3 |
| **N19** | 工具条项集 | `Table Profile▾`/`Begin Transaction`/`Column Filter`/`Fill with AI`/`Data Profiling` + 视图切换 | 13 个中文按钮，项集不同 | 1–2 |
| **N20** | 底部栏 | `Record 7 of 100 in page 1` + 五键导航 + 刷新 + `Last Refresh: 37m` | 中文措辞 + 页大小 + 跳页 + 刷新于 | 1 |

> **N16 的连带后果必须写清**：去掉行首复选框 = 批量改值 / 批量删除 / 批量设 NULL 的入口全没了。
> Navicat 的做法是**多选行 + 右键 / 工具条**，我方要照做，
> `DataGridPage` 的勾选模型（约 1 037 行 CJK、批量逻辑核心）要重写成行多选。这是 N16 成本 3–4 人日的主体。
> **N19 的 `Fill with AI`** 与 §7.16（Ask AI 不做）冲突 —— 按本版口径，做真按钮 + 如实说明页。

### F 组 · 查询编辑器 / 已保存查询 / 设计器 / 弹窗

| 编号 | 差距 | 目标（实测） | 现状 | 成本 |
|---|---|---|---|---|
| **N21** | 查询工具带 | **双行 62px**（§1.5 项集与顺序），`#F0F0F0` 底 | 单行 10 个中文按钮 | 2 |
| **N22** | 语法配色 | §1.5 六角色实测值 | `SqlHighlighter.java` 另一套 | 1–2 |
| **N23** | 结果区三段 | 27px `Message/Summary/Result N` + 32px `Data/Info` + 网格 | 单层「结果」页签 | 2–3 |
| **N24** | 已保存查询对象页 | 树 `Queries` 分组 + 文件清单（`Name`/`Modified Date`/`File Size`/`Modified User`）+ `Design/New/Delete Query` | `SavedQueriesPage` 形态不同，树里无分组 | 2 |
| **N25** | 状态栏段集与顺序 | §1.6 六段 + 按页型变体；**去掉「自动提交·只读」** | 9 段固定语义 | 2 |
| **N26** | 弹窗标题 | **系统标题栏**（去掉 `adb-dialog__header` 自绘条与 ×） | 原生窗口 + 内容区自绘标题条 | 2–3 |
| **N27** | 连接向导 | §1.8 两页（9+13 复选侧栏 / 5 列彩色磁贴 / 7 页签 / 900×700 / Save password / 底部五键） | 3 种库型磁贴 + 7 页签（R33 已做骨架） | 3–4 |
| **N28** | Ribbon 12 格背后的 6 个能力面 | View 对象页与增删改 / User 管理 / Backup 转储恢复 / Automation 作业 / Model ER 图 / BI 图表 | **全部为零**（grep `StageStyle`/`ErDiagram`/`Backup`/`create.*View` 无命中） | **34–52** |
| **N29** | 表设计器 | `Virtual` 列、属性面板 `Key Length`/`Binary` 两格、`Key` 合回单一下拉、`Ask AI` 键、`Number of Fields: 15` 措辞 | 九列（Key 拆三勾选）、面板三格、无 Ask AI | 2–3 |
| **N30** | 字号梯度 | 收敛到 **11/12/13** 三档 | 7 档（10/11/12/13/14/16/32） | 1–2 |

---

## 3. 推翻台账（这些已经交付、本轮要重做或作废）

| 已交付 | 轮次/提交 | 命运 | 代价 |
|---|---|---|---|
| 顶区精简 114px / 64px 命令带 / 删品牌块 / 工具件并入页签行 | R32-D（`e595c1f`） | **作废** → N5/N6/N7 | 2 人日重做 |
| 工具条「整排退成图标」 | R32-G19b（`fde2d82`） | **作废** → N18 改 `>>` 溢出 | 1–2 人日 |
| 状态栏「自动提交 · 只读」安全语义段 | U8/历轮 | **删除** → N25 | 0.5 |
| 行首复选框 + 批量勾选模型 | 早期轮 | **替换**为行号+铅笔+行多选 → N16 | 3–4 人日 |
| 强调色 `#0067B8`（WCAG AA 修正，旧 §7.12） | R30-A | **作废** → `#0078D7`，`ContrastTest` 改判并写明「主动放弃 AA」 | 0.5 |
| 单色线性图标体系（旧 §1.5.1） | 全程 | **作废** → N3/N4 | 10–14 人日 |
| 「磁贴只摆真实支持的 3 种」（旧 §7.15） | R33-G20 | **作废** → N27 全摆 + 不支持项给如实说明页 | 1–2 |
| 「Ask AI / New Table with AI 不做」（旧 §7.16） | 裁定 | **作废** → 真按钮 + 说明页（N15/N19/N29） | 1 |
| 「Virtual 生成列不做」（旧 §7.17） | 裁定 | **作废** → N29 | 1–2 |
| 「弹窗自绘标题栏保留尺寸记忆」（旧 §1.5.3） | 全程 | **作废** → N26 | 2–3 |
| 导航树 21px「已对齐」 | 旧 §1.1 | **改判**：树 20px，对象清单 21px（两控件不同高） | 0.5 |
| 「AIDB 114 vs Navicat 170 同线」 | 旧 §1.1 | **改判**：Navicat 顶区实测 140px，我方 114px，差 26px 未对齐 | — |
| R30/R31/R32(G2/G3/G4)/R33 的功能深度（事务条、查找替换、表单视图、七页设计器、导入导出深度、SSL/URI） | — | **保留**，只是换外壳与措辞 | — |

**净效果**：功能资产基本保住；**视觉与语言层几乎重建**。

---

## 4. 轮次规划（R34 → R42）

> 每轮口径不变：先方案 → 确认 → 动代码 → 门禁全绿 → 重拍帧 → 逐张目视 → 分批本地提交（**push 需单独授权**）。
> 排序原则：**前置依赖优先**（i18n 卡所有标签；图标渲染层卡所有彩色资产；顶区卡页签行/工具带底色）。

| 轮 | 内容 | 条目 | 估（人日） |
|---|---|---|---|
| **R34** | i18n 层 + 双语资源 + 默认英文 + 菜单项集 | N1 N2 | 8–12 |
| **R35** | 彩色图标渲染层 + 50 枚资产 + 图标门禁改判 | N3 N4 | 10–14 |
| **R36** | 顶区 140px / 90px Ribbon / 页签行只放页签 / 工具带 `#F0F0F0` / 分隔线 / `#0078D7` | N5–N10 | 5–7 |
| **R37** | 导航树（20px / 表平铺 / 分组 / 去计数 / 挪走三 navButton）+ 对象清单（6 列 / 7 键工具条 / 修 D-3） | N11–N15 | 5–7 |
| **R38** | 数据网格（行号+铅笔 / 当前格橙黄 / `>>` 溢出 / 项集 / 底部栏） | N16–N20 | 9–12 |
| **R39** | 查询编辑器（双行工具带 / 语法配色 / 结果三段 / 状态栏六段）+ 已保存查询对象页 | N21–N25 | 9–12 |
| **R40** | 弹窗系统标题栏 + 连接向导两页全量 | N26 N27 | 5–7 |
| **R41** | 表设计器收口 + 字号梯度三档 | N29 N30 | 3–5 |
| **R42** | **Ribbon 12 格背后的 6 个能力面**（View / User / Backup / Automation / Model / BI） | N28 | **34–52** |

- **R34–R41 小计：54–74 人日**（纯「做到一样」）。
- **加 R42：88–126 人日**（「连功能面也不缺」）。
- 参考：R30–R33 四轮实际产出 1 108 条门禁 / 33 帧，估算 25–30 人日、实际每轮一个会话。
  按同样的执行密度，R34–R41 约 8 个会话，R42 约 6–10 个会话。

**R32 收尾**：G19b / D-1 已提交，本轮**就地结案**（不再补 D-3 —— D-3 并入 R37 N14，
因为列集本身要改成 6 列，单独修窄宽降级是白做）。

---

## 5. 门禁改判清单（改判要写明理由，不许「改绿」）

| 门禁 | 现断言 | 改判为 | 理由 |
|---|---|---|---|
| `ContrastTest.java:39`（`MIN_NORMAL = 4.5`）+ `:65`（`反色文字 / 主色` 配对） | `text-inverse/accent` ≥ 4.5:1 | 阈值降到 4.4:1 并注释「2026-10-28 用户裁定取 Navicat 原值 `#0078D7`，实测 4.498:1，主动放弃 AA」 | 拍板 4 |
| `TokenParityTest.java:63` | 命令带 64px | 140px 顶区 / Ribbon 91px / 磁贴 70×90 | N5/N6 |
| `AppBarTest`：`theRibbonKeepsOnlyCommandsThatHaveNoSecondHome` / `theBarHasOneFixedHeightAndDoesNotGrowWithContent` | 恰好 6 个 `ribbon-*`、高 64 | 恰好 12 格、高 90、激活态 `#C0DCF3`+`#90C8F6`、组间 `#DBDBDB` | N6 |
| `AppShellTest.java:161-182` | 顶区两行、工具件在 `#adb-chrome-row` | 顶区三行（菜单/Ribbon/页签）、页签行**只有页签** | N5/N7 |
| `BrandMarkAuditTest` | 禁 `appbar-brand` | 维持（Navicat 顶区也没有品牌块），但窗口标题格式改 Navicat 式 `<对象> - Query - <产品名>` | N26 |
| `LucideIconTest.java:129,213-233` | 自绘集恰 `{BRAND,DOT}`、源 SVG `stroke-width=2` | 新增彩色资源命名空间与校验规则（多 path、逐 path paint、色值落在实测色板内） | N3/N4 |
| `IconSystemAuditTest.java:136` | `SIZE_RIBBON == 26` | 30（实测磁贴图标 ≈30×30） | N4 |
| `ChromeMetricsTest.java:143-148` | tabbar 26 / header 40 / toolbar 30 / pager 38 / statusbar 22 | tabbar **25** / statusbar **23** / 工具带底色 `#F0F0F0` / 顶区两条 `#DBDBDB` | N5/N8/N9 |
| `Tokens.ROW_TREE_LIGHT` | 21 | **20**（树），对象清单保持 21 | N11 |
| `SelectionStateTest` | 选中行含 `-adb-bg-selected` + `-adb-accent` | 维持，另加「当前格 = 橙黄 `#FFB666`/`#FFFFB6`」一条 | N17 |
| `ToolbarDiscoverabilityTest` | 放不下整排退图标 | 放不下折进 `>>` 溢出菜单，且溢出项可键盘到达 | N18 |
| 新增 `I18nAuditTest` | — | 两套键集相等；ui 主源码零 CJK 字面量 | N1 |
| 新增 `SqlSyntaxColorTest` | — | 六角色实测色值逐一对账 | N22 |
| 新增 `RibbonCapabilityAuditTest` | — | 12 格每格必须落到一个**真实存在**的目标（页面或如实说明页），不许空 `onAction` | N6/N28 |
| `ToolbarDiscoverabilityTest`（第四批改判） | 「放不下整排退图标」= 整排一起退 | **按 Navicat 的分工切**：具名操作（`Begin Transaction` / `Filter & Sort` / `Columns` / `History`）留图标+文字，瞬时编辑键（新增/撤销/重做/保存/删除/视图/导入/导出）**只给图标 + tooltip + accessibleText** | N1-b 实拍 |
| `DataGridPagerFitTest`（第四批改判） | 满配整排文字 + 判据 `prefWidth` | 文字标签一律钉 `USE_PREF_SIZE`（压不了，只能整项收起）；**判据仍用 `prefWidth`**；补最后一档「页面」标签；跳转框宽度跟着**提示文字**量（64 → 75/69，min 一起抬） | N1-b 实拍 |
| 新增 `I18nAuditTest.filesThatClaimToBeCleanAreAlsoFreeOfFullwidthPunctuation` | 汉字棘轮只认 U+4E00–U+9FFF | **汉字清零的文件，全角标点也必须清零**（`"（NULL）"`、`"、"` 这类从旧尺子底下钻过去） | N1-b |

**第四批三条改判的实测依据**（改判不写数就是「改绿」）：

- 工具条：中文档九颗文字按钮全开刚好 1026px（贴着 1036px 可用宽），换英文同样九颗要 **1153px** ——
  超宽 117px。按 Navicat 的分工切完，需求降到 **899px**（可用 1036），代价是
  「保存 3」这个待保存计数从按钮上消失，改由状态栏 `status.pending` 承担。
  门禁 `namedLabelsActuallyFitTheWideToolbar` 用反射读 `labelRequirement()` / `availableToolbarWidth()`，
  并断言可用宽 > 0（防止量不到宽度而空转）；真正的溢出折叠留给 R38 N18 的 `>>`。
- 分页器：中途把判据从 `prefWidth` 换成过 `minWidth`，**实测判它是错的** ——
  布局恒保证「盒子实际宽 ≥ 自己的 min」，`min <= width` 恒真，阶梯一项都不撤
  （900 档 pref=953 > w=900，六个可收项全挂着）；更糟的是钉死的标签把 min 顶到 891px 往上传，
  外壳里侧栏 + 抽屉一占位就把整页顶出窗口，工具条整排溢出（三条门禁红）。
  1280 窗口 + 侧栏实测：分页器实得 1016px、满配 pref=1073 / min=1027 → 第一档收「Go to row」，
  收起后 pref 落到 996，「Go to page」保住。**少一个输入框，而不是把两个词各裁一半。**
- 三次变异验证（各破坏一次，还原即绿）：撤掉三个标签的 `USE_PREF_SIZE` → 新那条真实布局门禁红，报
  「Record 实得 17px < 需要 41px」「3 row(s) 实得 23px < 需要 46px」（760 档）；
  判据退回 `minWidth` → 阶梯门禁 + 三条溢出门禁共 **4 红**；
  往「已清零」文件里塞回 `"（NULL）"` → 全角那条红，报「残留 1 条全角标点字面量」。
- ⭐ 棘轮**当场抓到了本轮自己新写的一条中文**：给截图会话加诊断日志时写了
  `println("[screenshot] 窗口被系统还原成最大化，已取消")` → `ScreenshotSession` 从 8 涨到 9、
  总数 1504 → 1505、门禁红。改成 ASCII 日志（解释留在注释里，注释会被剥掉）后回到 1504。
  这条正是「只许降不许升」存在的意义：**顺手写的诊断也算字面量**。

**第五批（`AppShell` 198 → 0）的三条纪律**（都来自"合并键"与"数据不是文案"这两个坑）：

- **英文术语一律取自本地 Navicat 帧**，帧里没有的在资源包里标了 inferred：
  `navicat17-object-list` 给 Name / **Rows** / **Data Length** / **Engine** / **Modified Date** /
  Comment 与页脚 "22 Tables"；`navicat17-designer-fields` 给页签 **Indexes / Foreign Keys / Triggers**、
  工具条 **Primary Key**、页脚 **"Number of Fields: 15"**、属性区 **Collation**。
  顺手证伪了两个我原本会写的词：不是 "Row Count" 也不是 "Creation Time"。
- **复用键的前提是两侧中文/英文都和原文一样**，否则就是偷改文案：
  `column.rows` 中文是「行」而详情面板原文是「行数」、`column.created` 是「创建日期」而原文是
  「创建时间」—— 这两条（加 `detail.updatedAt`）**另立键**，只复用 `column.name/dataLength/engine/
  collation/comment` 这五条两侧完全一致的。同理标签页标题用 `window.pageType.object`（"Objects"）
  而不是 `category.object`（单数 "Object"，当页面标题读着不对），中文两侧本来就都是「对象」。
- **快捷键表 37 行里 21 行复用 `action.*` / `menu.*`** —— 表里写的就是同一个动作，
  跟着动作改名才对。但 "撤销上一处编辑" / "全选当前页" 两行虽然与 `grid.tip.undo`、
  `grid.batch.selectAllTip` **文字相同**，那是别的界面上的 tooltip，借过来就成了
  「改一句网格提示词，顺手改掉 F1 快捷键表」，所以另立 `shortcut.*`。
- ⭐ **数据不是文案，但也不许留在 Java 字面量里**：`writeDemoImportFile()` 那三行
  张三/李四/王五 是**故意用 GBK 写盘**的演示 CSV —— 中文姓名正是让导入向导的「编码」选择器
  在帧里真起作用的理由，翻成英文这张帧就没意义了，所以**不能进语言包**。
  但留在源码里会让 `CJK_CEILING` 永远降不到 0，只能开注释豁免口子，
  而豁免口子一旦开就有人拿它躲真文案。落点是资源文件
  `demo-import-utf8.csv`（源文件 UTF-8 可读可 diff），**写盘时才转 GBK** —— 转码这一步本来就是
  那张帧要证明的东西。
- ⭐ **新立第 11 条门禁** `callSitesPassAsManyArgumentsAsTheBundlesHavePlaceholders`：
  第五批一次引入约 40 个带 `{0}/{1}` 的键，而「键存在」那条证明不了「键用得对」——
  少写一个占位符就少一段话、多写一个就在状态栏上长出字面 `{2}`，**两种都不报错**。
  这条按顶层逗号解析每个 `I18n.t(...)` 的实参数，与 en / zh **两侧**的占位符数各比一次
  （两侧不一样正是要抓的东西），并要求占位符从 `{0}` 起连续。
  两次变异验证各红一次：调用点少递一个实参 → 报
  「`shell.connectedObjects` 递了 1 个实参，en 侧有 2 个占位符」；
  资源里多写一个 `{2}` → 报「`shell.deleteBody` 递了 0 个实参，en 侧有 3 个占位符」。
  非空转由「至少扫到 20 个带参调用点」这条断言兜住（实测扫到 >20）。
- ⚠️ codemod 85 条**先干跑再落盘**：4 条多行模式因缩进猜错而 MISS（干跑报出来，改成 16 空格后
  85/85 各命中一次才 apply）。落盘后编译又抓出一条干跑看不见的：`shell.density` 把
  `"密度：" + switch (value) {…}` 改成嵌套实参后**少了一个右括号** —— 结构改动光靠字符串比对
  验不出来，必须编译。
- ⚠️ 顺手抓到的两个非文案缺陷：① `IconButtons` 没关助记符解析，资源里的 `"Filter & Sort"`
  被 JavaFX 画成「Filter  Sort」（`&S` 下划线），而 `getText()` 仍返回原串 ——
  所有按字符串比对的门禁都看不见它。已在工厂里 `setMnemonicParsing(false)` 并新立
  `IconAuditTest.ampersandsFromTheLanguagePackAreNotEatenAsMnemonics`。
  ② 阶梯门禁把「喂宽度」和「读可见性」分成两次跨线程调用，中间允许插入宽度监听器的
  `runLater`，实测到过自相矛盾的一帧（`pref=960` 像全展开、`jumpRow.isVisible()=false` 像已收起），
  单跑永远复现不了 —— 改成**同一条 FX 任务里喂完立刻读**。

**第五批重拍帧目视时新抓到的一个缺陷（记为 D-5，已修）**：命令带六颗格子里有两颗被裁成省略号 ——
`Import / …` 与 `Saved Qu…`。根因和第四批那三处同源但**在另一个文件里**：
`app.css` 的 `.adb-ribbon__button` 写死 `-fx-min-width: -fx-pref-width: 62`，
62px 是**按中文量的**（"已保存查询" 五个字装得下），换英文后 "Import / Export" 要 74px、
"Saved Queries" 要 69px，而标签实得只有 56px（62 减去左右各 2px 填充）。
⚠️ 这个缺陷**不是第五批引入的**：`git show HEAD:docs/screenshots/p0-shell.png` 裁出顶区一看，
第四批那版一模一样地裁着 —— 它是首批（顶区四件套）就埋下的，只是此前 33 帧的目视没往顶区看，
而且**没有任何一条门禁量过标签宽度**（`theBarHasOneFixedHeight...` 量的是高度）。
修法：`-fx-pref-width: -1`（跟着内容算）+ 填充 2px→6px，`min-width: 62` 留着当中文档的下限。
新立 `AppBarTest.ribbonLabelsAreNeverEllipsised`：钉的是**结果**不是手段 ——
逐颗格子比 `label.getWidth()` 与 `label.prefWidth(-1)`，同时反向钉「六格合计不得顶出 1280 外壳」。
先红后绿：改 CSS 前它报出上面那两个数（这就是变异验证），改完 13 条全绿。

**第六批（`QueryPage` 161 → 0）抓到的边界问题：文案可以在 `ui/` 之外**

- 抽完查询页去重钉断言时，`AppShellTest` 报 `expected "Read Only" but was "只读"` ——
  那颗风险徽章的文字来自 `Danger.label()`，而这个枚举在 **`core/adb-model`** 里，
  `CJK_LITERAL` 只扫 `ui/**/src/main`，所以**默认英文的界面上钉着一颗中文、棘轮一条都不红**。
  更糟的是 `DataGridTransactionTest` 早就把这条缺陷**编进了期望值**
  （`I18n.t("grid.save.headPlain", n, Danger.MUTATING.label())` —— 测试自己调中文 label 去拼 expected，
  于是它一直是绿的，绿的还是「界面上就该显示中文」）。
- 修法是把边界划清，不是把枚举搬过来：新增 `DangerLabels.t(Danger)` 做键映射
  （`READ_ONLY` 复用 `status.readOnly`，其余三个新铸 `danger.*`），
  模型层管语义（要不要确认 / 要不要审批），界面层管文案。7 个调用点全换。
- ⭐ 新立第 12 条门禁 `modelLayerDangerLabelsNeverReachTheUiDirectly`：
  ui 主源码里不许出现 `danger.label()` / `Danger.X.label()`。
  它补的不是"又一条尺"，是**扫描范围的边界** —— 文案只要能绕开 `ui/` 进界面，棘轮就是漏的。
  变异验证：把 `QueryPage:612` 改回 `danger.label()` → 这条红并点名文件；还原 → 12 条全绿。
- ⚠️ 一次工具坑值得记：本仓库工作副本是 **CRLF**，codemod 的多行模式里写 `\n` 会**整批 MISS**
  （第五批踩的是缩进猜错，这次踩的是行尾）。而且**干跑看不出来** ——
  dry 模式不改 `src`，被前一条模式吃掉的字面量在 dry 下"还在"、在 apply 下才报 MISS，
  所以 dry/apply 各跑一次才算数（本轮 apply 第一次就抓到 5 条漏网 + 1 条重复）。
- 断言一律改成按资源比（`contains(I18n.t("query.resultTab", 1))`，不抄中文原句），
  换语言就不用改测试。`query.doneStatus` 因此把「执行完成」抬头做成 `{0}` 传参 ——
  门禁只比抬头那一小段，不必知道后面跟了几个字段，也不必跟着模板改。

**D-8：同一批重拍帧又抓到查询页分页条整排挤成半截字**（"Previous Pa…" / "Per p…" / "Copy This Pa…" / "Exp…" / "Go to pa"）

- 两条根因，都不是"英文太长"这么笼统：
  ① 查询页的翻页键用 `IconButtons.of`（**图标 + 文字**），而数据页同一排用的是 `IconButtons.icon`
    （**纯图标** + tooltip/accessibleText）。中文「第一页」三个字装得下，英文 "Previous Page" 装不下。
    Navicat 的纪录导航本来就是纯图标 —— 所以改回图标不是"挤不下才退让"，是本来就该长这样。
  ② 文字项没钉 `USE_PREF_SIZE`，HBox 一挤就往 min 压，于是切成半截而不是整项收起。
- 跳页框的"宽度跟着提示文字量"从 `DataGridPage` 抽成 **`FieldFits.prompt(TextField)`** 两页共用：
  第四批在网格上踩过一次（64px 装不下 "Go to page"），第六批在查询页**又踩一次** ——
  同一条规则写两遍就一定会漏一遍，这就是抽出来的理由。
- ⭐ 新门禁 `QueryPageTest.queryPagerItemsAreNeverSqueezedIntoEllipsis` 有两个坑值得记：
  ① **不去改窗口尺寸**。第一版把 `stage.setWidth()` 拉到 1280 再量，实测它**不会同步落到场景上** ——
    全靠顺手加的那条"先确认场景真的到了 1280"才发现自己静默量在 900 宽（那就是下一条假绿）。
    现在改成**推导预算**：此刻「场景宽 − 分页条实得宽」就是分页条以外的固定开销，
    1280 下的预算 = 1280 − 那个开销，再比 `bar.prefWidth(-1)`。
  ② 逐项比的是**计算后的** `minWidth(-1)`，不是 `getMinWidth()` 属性 ——
    后者默认 `-1` 是 `USE_COMPUTED_SIZE`（意思是"按 pref 算"），拿属性值去比 pref
    会把每一颗都报成"可压缩"，第一版因此**假红了 9 条**。
    与第四批那条 `minWidth` 恒真的坑正好是同一个属性的两个方向。
- 变异验证按自然顺序走了一遍：加门禁 → 红 3 条并点名（Per page / Copy This Page / Export）
  → 钉 `USE_PREF_SIZE` → 18 条全绿；shell 模块 619 条全绿。

每条改判配一次变异验证（破坏 → 恰好 N 红 → 还原 → 绿），口径见本文 §7。

---

## 6. 待拍板（本文不替你决定）

| 编号 | 问题 | 我的建议 |
|---|---|---|
| **P1** | R42 的 6 个能力面（View/User/Backup/Automation/Model/BI）做不做？不做的话 12 格顶区只能有 6 格是真页面，另外 6 格点进「本版本未提供」说明页。 | **分两半**：View / Backup / Model(ER) 有明确用户价值，先做（约 14–21 人日）；Automation / BI / User 管理是独立产品线，暂缓，格子上保留但给如实说明页。 |
| **P2** | 默认英文后，中文资源是否要同步维护？ | 要。两套同时生成，`I18nAuditTest` 键集相等这条就是防止中文侧漂移；不做机器翻译，中文按现有 1 404 条字面量反向抽取。 |
| **P3** | 暗色主题在彩色图标下要不要重做一套？ | 要。Navicat 暗色的图标是同一批位图加亮边；R35 一并出暗色档，否则暗色帧会出现黑底黑标。 |
| **P4** | 冷启动（实测 3.5–3.9s）与 i18n 资源加载冲突吗？ | 不冲突，但 R34 期间要复测一次，防止 ResourceBundle 走磁盘 IO 拖慢首屏。 |
| **P5** | 旧 §8 边界（jlink ⛔ / 自动更新 🚫 / 代码签名 ⛔）维持吗？ | 维持 —— 这三条与「像不像 Navicat」无关，是发布工程问题。 |

---

## 7. 验收口径（每轮固定，沿用）

1. **门禁**：全量 0 失败；新能力必须有行为门禁（不是文本 grep）+ 一次变异验证。
   CSS 门禁必须连**权重与声明顺序**一起比（R31 的 `:filled:odd` 教训）。
2. **帧**：涉视觉的轮次全量重拍 + **逐张目视** + md5 两两去重。
   ⭐ 本版加一条：**每张 AIDB 帧必须并排一张同场景 Navicat 帧**，
   在 `docs/screenshots/side-by-side/` 生成对照图，目视看的是**并排**而不是单张。
   （旧 `side-by-side.png` 是 2026-10 的过时产物，重做。）
3. **回填**：基线数字、本文 §2 状态、§4 轮次结果三处同轮更新。
4. **提交**：验收后分批本地提交；**push 需单独授权**。
5. **诚实条款**：任何「看起来一样但点了没反应」的控件一律不许上；
   做不到真功能的位置放**如实说明页**（G8 的 SSH/HTTP 先例），不放假开关、不放死按钮。

---

## 8. 当前基线（2026-10-29 R34 第六批之后实测）

- 门禁 **1177 条 / 134 类 / 0 失败 / 48 环境跳过**。
  **量法（照抄，别再凭记忆写数）**：`./mvnw -o test` 之后
  `grep -aoP 'Tests run: \K[0-9]+(?=, Failures: [0-9]+, Errors: \d+, Skipped: \d+, Time elapsed)' 日志 | awk '{s+=$1;n++} END{print n,s}'`
  → **150 组 / 1177 条**，与各模块汇总行相加一致；类数按 `**/target/surefire-reports/*.txt` 计数 = **134**
  （150 与 134 的差是 `@Nested` 内部类：一行一组、一份报告）。
  ⚠️ 本文上一版写 **1116**、ROADMAP 写 **1119**、本轮中途又记过 **1122 / 1173 / 1174 / 1175 / 1176** —— 都对不上，
  差的就是「按模块汇总」与「按报告文件累加」两种口径混用。以后一律用上面那条命令。
  ⚠️ 全量必须用 JDK 25+：`JAVA_HOME` 留在系统默认的 21 上，enforcer 直接 BUILD FAILURE
  （本机可用 `C:\Users\Administrator\.jdks\openjdk-27.0.1.0.2`）。
- ✅ **R33 与 R34 首批已落库**：`34ffb8f`（R33 全部 + R34 顶区与动作层抽取，合成一笔 ——
  `AppShell.java` 同时承载两轮改动，按 hunk 拆分会产出编译不过的中间提交）、
  `288592d`（N1-d 语言偏好启动生效）。**push 仍未授权。**
  ⚠️ **上一版真源与 README 记的「1108 条 / 134 类」从未复现** —— R34 开工前先跑了一次干净全量，
  实测是 **1107 / 133**（133 个 `*Test.java` 全部出了 surefire 报告，没有静默跳过的类），
  也就是说那个数字是**记上去的，不是量出来的**。本轮起基线一律附量法。
  R34 首批之后 +6 条（`I18nAuditTest`）+1 类 = 1113 / 134。
- 验收帧 **33 张**（28 亮 / 5 暗）—— 第五批 + D-5 修复之后**再次全量重拍为英文档**，
  逐张目视 + md5 两两去重 + 逐张比尺寸。⚠️ 中英混排仍在：侧栏（`SideBar` 96 条）、
  单元格详情抽屉（`CellDetailsDrawer` 39 条）、对象详情面板的字段/索引小节（`ObjectSectionsPanel` 40 条）、
  查询页（`QueryPage` 161 条）等还没抽，帧里那些位置照旧是中文 —— **这是中间态不是验收态**。
  ⭐ 目视这一步本轮**真的抓到了东西**（前三轮的"目视"漏了）：命令带两个标签被裁成省略号 = D-5，见 §5 末。
  `scripts/shots.sh` 会把每张帧的 **宽×高** 打出来并和已入库的那版比 ——
  本轮它抓到两张：`p4-ddl`（上一轮）与 **`p2-find` 又 1920×1017**、
  以及 `p3-query-menu` 从 203×172 变成 1280×800（**弹窗没了**）。
  ⚠️ 尺寸守卫**也会指错方向**：p3-query-menu 报"和已入库不一致"，但这次是**新帧错了**、
  旧帧（真菜单）才对 —— 守卫只说"变了"，对不对还得人看。
  最大化那张的来源查过：偏好节点里没有 `ui.window.*` 两条（查了注册表），代码里也只有 Window 菜单那一项调
  `setMaximized` 而截图模式不点菜单 —— 是 Windows 把「同一个可执行文件上次的窗口摆放」还给了新窗口。
  ⭐ 本轮把它从"尽力"变成了**已证明**：`ADB_SHOTS_MAXIMIZE=1` 让 `ScreenshotSession`
  在按快门前自己最大化一次，逼兜底走真路径。第一版兜底**是坏的** —— 只取消最大化，
  帧拍回来是 **1264×761**（JavaFX 还原的是外框，场景差一圈边框 + 标题栏），
  而且把 `stage.setWidth()` 和 `snapshot()` 放同一条 FX 任务里读不到新尺寸，仍是 1264×761。
  现在是**三步**：取消最大化 → 排一拍 → 按「外框 − 场景」的差值钉回 1280×800 → 再排一拍 → 拍。
  实测日志 `forcing maximized… → maximized at capture time → restoring scene size:
  stage=1280x800 scene=1264x761 → scene=1280x800`，PNG 就是 1280×800。
  ⚠️ 加尺寸核对时先踩了一坑：写成 `git show … | od` 会被 `set -o pipefail` + `set -e`
  直接打死脚本（od 吃 8 字节就退出 → git 收 SIGPIPE 以 141 退出 → 命令替换失败 →
  日志停在「拍摄 p2-find」、rc=255，完全看不出原因）。改成先落临时文件再读。
- **N1 进度**：ui 主源码 CJK 字面量 **2216 → 2011 → 1917 → 1504 → 1306 → 1145 次出现**（去重 810 条），
  资源包 **701 键**（en/zh 各 701，对称；`grid.*` 298 / `query.*` 100 / `shell.*` 84 / `action.*` 72 /
  `detail.*` 21 / `shortcut.*` 18 / `danger.*` 3）。
  ⚠️ **上一批记的「472 键 / grid 296」是错的**：`git show HEAD:…messages_en.properties` 实测
  **474 / 298** —— 那批收尾时又补了 `grid.overview.pkSeparator`、`grid.form.nullPrompt` 两个键，
  数没回量就写进文档和提交信息了。同一个坑（数字是记上去的不是量出来的）第三次踩，
  以后**键数也一律在最后一次改动之后量**。
  **棘轮量法**：`node tools/cjk-count.mjs` —— 与 `I18nAuditTest` 的 `stripComments` + 正则同构，
  逐字一致。⚠️ 用 `grep -hoP` 数会得多出一截（第四批那次 1530 vs 1504，多出来的 26 条全在注释里，grep 剥不掉注释），
  两个口径混用就会看起来「棘轮又涨了」。
  第一批 = 顶区四件套（2216 → 2154）。第二批 = **动作层** 71 个动作（2154 → 2011）。
  第三批 = 表清单页 `TableListPage` 70 → 0（2011 → 1917）。
  **第四批 = 数据网格 `DataGridPage` 413 → 0**（1917 → **1504**），333 处 `I18n.t(...)`。
  **第五批 = 外壳 `AppShell` 198 → 0**（1504 → **1306**），85 处一次 codemod 落完。
  **第六批 = 查询页 `QueryPage` 161 → 0**（1306 → **1145**），100 个 `query.*` 键 + 3 个 `danger.*` 键，
  并顺带修掉 D-7（模型层中文标签越界）与 D-8（分页条挤成半截字）。
  这是 R34 最大的一块，也是**第一个把「文字长度」变成缺陷的页**：英文比中文宽，
  工具条与分页器两处都因此改判（见 §5 末三行）。
  棘轮 `CJK_CEILING = 1306` 只许降不许升；**还剩 52 个文件**，大头
  `QueryPage`(161) / `StructurePage`(156) / `SideBar`(96) /
  `ConnectionWizard`(89) / `ObjectHomePage`(83) / `NewTableDialog`(75) / `ImportWizard`(61)。
  ⚠️ 此前按「含 CJK 的行数」排优先级是错的 —— 那个数把注释也算进去了（`DataGridPage` 1037 行 vs 实际 415 条字面量）。
- ⭐ **第四批新立的一条尺，顺带暴露了老尺子的盲区**：`CJK_LITERAL` 只认 U+4E00–U+9FFF，
  于是 `"（NULL）"`、`String.join("、", keys)`、`"SQL："` 这类**纯全角标点**的残留一路钻过去 ——
  `DataGridPage` 报「清零」之后重拍帧，才看见表单视图的空值提示仍是全角括号。
  实测全库含全角标点的字面量共 **366 条**（绝大多数与汉字棘轮重叠）。
  修法不是把 `CJK_CEILING` 从 1504 涨到 1536（那等于给同一笔债立两张账，还当场破掉「只许降」），
  而是新立 `filesThatClaimToBeCleanAreAlsoFreeOfFullwidthPunctuation`：
  **汉字清零的文件，全角也必须清零**。当前 **26 个文件**做到零汉字且零全角（第五批之后由 `node tools/cjk-count.mjs` 实测）。
- **N1-a ✅ / N1-c ✅**（`I18nAuditTest` 现 **12 条**断言）；**N1-b 进行中（第六批已收，51 个文件待抽）**；**N2 ✅**。
- ✅ **D-5 已修（第五批重拍帧目视发现）**：命令带 `Import / Export`、`Saved Queries` 两个标签被裁成省略号
  —— `.adb-ribbon__button` 写死 62px 定宽（按中文量的），英文要 74 / 69px 而实得只有 56px。
  改成内容驱动宽度 + 62px 下限，并新立 `AppBarTest.ribbonLabelsAreNeverEllipsised`（先红后绿，见 §5 末）。
  ⚠️ 缺陷本身是**首批**埋的，第四批那版帧就裁着 —— 说明「重拍了 33 帧」不等于「逐张看了 33 帧」。
- ✅ **D-6 已修：`p3-query-menu` 连续两轮拍到主窗口而不是右键菜单**。
  第一次归因为「冷启动没等到行」是**错的** —— 加了摆位日志之后实测 `menu posed, windows=2`
  说明菜单**开出来了**，是快门按下时它已经不在了。逐档试等待时长定位边界：
  1500 / 3000 / 5000 / 7000 / 8000ms 都拍得到，**9000ms 拍不到**，
  而本帧固定等 8000ms —— 正好压在菜单的寿命边界上，所以同一份代码一次成一次败。
  菜单自己收起的触发者**没查到**（主源码里没有 5–10s 的 FX 计时器，最像窗口失焦时 JavaFX 收掉 PopupWindow）。
  修法不去赌那个原因，而是**续摆**：每 1500ms 看一眼，没有浮窗了就重新投递一次真事件
  （只在「没有浮窗」时续，避免叠两层菜单），于是快门按下时菜单最多 1.5 岁。
  验证：9000ms 这一档从红变绿；重拍的帧里日志打了两次 `menu posed`（菜单确实中途收过一次、被续回来了）。
  ⚠️ 证据面没打折：拍的还是同一个 `setOnContextMenuRequested` 处理器开出来的真菜单。
- ✅ **D-7 已修（第六批重钉断言时发现）：文案可以绕开棘轮的扫描范围**。
  查询页的风险徽章渲染 `Danger.label()`，中文枚举常量长在 `core/adb-model`，
  而 `CJK_LITERAL` 只扫 `ui/**/src/main` —— 默认英文的界面上钉着「只读」，**棘轮一条都不红**。
  ⚠️ 更糟的是 `DataGridTransactionTest` 拿 `Danger.MUTATING.label()` 拼 expected，
  于是那条测试一直是绿的，绿的还是「界面上就该显示中文」—— **测试把缺陷固化成了契约**。
  修法：新增 `DangerLabels.t(Danger)` 做键映射（`READ_ONLY` 复用 `status.readOnly`，
  其余三个新铸 `danger.*`），7 个调用点全换；新立第 12 条门禁钉住这条边界。详见 §5 末。
- ✅ **D-8 已修（第六批重拍帧目视发现）**：查询页分页条整排挤成半截字
  （"Previous Pa…" / "Per p…" / "Copy This Pa…" / "Exp…" / "Go to pa"）。
  两条根因：翻页键用的是「图标 + 文字」而数据页同一排是「纯图标」（Navicat 也是纯图标），
  以及文字项没钉 `USE_PREF_SIZE` 被 HBox 压向 min。跳页框的宽度测量抽成 `FieldFits.prompt()` 两页共用。
  新立 `QueryPageTest.queryPagerItemsAreNeverSqueezedIntoEllipsis`（推导 1280 下的预算，不去改窗口尺寸）。
  两个坑写在 §5 末，其中「`getMinWidth()` 属性 vs `minWidth(-1)` 计算值」会把同一排 9 颗全报成可压缩 ——
  与第四批那条 `minWidth` 恒真是同一个属性的反方向。
- ⚠️ **D-1 未结，别当已修完**：分页器降级阶梯拿的输入是**盒子自己的宽度**，
  而对象详情面板是浮在右端的 —— p2-details 实测盒子右边缘在场景 x=1082、面板从 x≈990 起，
  **右边约 90px 被压在面板底下**，「每页」下拉框露出半截。
  分页器自己看不见浮层，得由外壳把**可用宽度**递进来（与 `availableToolbarWidth()` 同一类问题，
  工具条那边已经按场景坐标量了）。归 R38 N19/N20。
- ~~**阻塞项：R33 与 R34 叠在同一棵未提交的工作树上**~~ → **已解除**（见上条两笔提交）。
  留一条流程教训：**验收 ≠ 落库**。R33 当时写了「已收官」，但从未提交，
  于是一路压了 R34 两批改动进来，最后只能合成一笔。
  以后每轮验收的收尾动作里，**提交与回填同级**，不留"已验收未落库"的状态过夜。
- 未结：**D-3**（表清单 1280 宽只剩 2 列）→ 并入 R37 N14；**PG 真库联调**（48 条环境跳过的一部分）；
  **D-4 文档债**（README「已知限制」与能力表口径冲突 —— `README.md` 同样有 15 行未提交改动）。
- 本文替换：旧 §0/§1/§2/§5/§6/§7 的**结论与裁定**全部作废，历史轮次记录（R30–R33 做了什么）
  移入 `docs/ARCHIVE-R30-R33.md` 备查，不再作为规划依据。

### 8.1 本轮顺手修掉的两个真缺陷（都不是文案问题）

1. **`StatusBar` 拿显示文案当状态哨兵**：`isConnected()` 的实现是
   `!"未连接".equals(connectionName)`，而 `AppShell` 有两处靠**传字面量「未连接」**来表达"断开"。
   默认语言换成英文之后这个比较永远为真 —— **没连上时连接灯会亮绿**；
   反过来，谁把连接命名成「未连接」也会被判成断开。
   改法：`setConnection` 里一次性记下布尔值，调用方改传 `null`。
   抓出它的正是本轮自己改的门禁 `StatusBarTest.dialectAndConnectionMergeIntoOneLight`。
2. **`TerminologyGuardTest` 因抽取而静默失明**：它只走 `src/main/java`，
   文案一搬进 `.properties`，禁用词就从它的视野里消失了 —— 门禁全绿、界面照旧漂移。
   改法：扫范围扩到 ui 各模块的主源码 **+ 主资源**，properties 按 `key=value` 逐行取值。
   变异验证已做：往 `messages_zh.properties` 塞一行 `menu.mutantprobe=总览` →
   这条与 `I18nAuditTest.theTwoBundlesHaveExactlyTheSameKeys` **各自红一次**，还原即绿。

> ⚠️ 记一条工具坑（本轮踩过，差点得出"门禁失效"的错误结论）：
> `mvn -Dtest='A+B'` **匹配不到任何测试**，配 `-Dsurefire.failIfNoSpecifiedTests=false` 就静默
> BUILD SUCCESS，而旧的 surefire 报告文件还在 —— 看 mtime 会误以为测试跑过了。
> 多测试类必须用**逗号**分隔，并且要 `rm` 掉旧报告再判定。
