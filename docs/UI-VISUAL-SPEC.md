# UI-VISUAL-SPEC — 界面视觉与交互规格书

> **定位**：`docs/UI-GUIDE.md` 是「规则摘要」（一页能读完），**本文是「可照着实现的规格」**——
> 每个字号用在哪儿、每个颜色表示什么、每个弹窗长什么样、每个交互态怎么画，
> 以及**现在还差什么**。
>
> **数据来源**：源码 + `tokens.json` + `app.css` + 样式类审计（2026-10-22 实测）。
> **一切数值以 `ui/adb-ui-core/src/main/resources/tokens/tokens.json` 与
> `ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app.css` 为唯一真相**；
> 本文只解释「为什么这么定」与「哪些还没定」。
>
> 配套：`docs/UI-GUIDE.md`（规则）· `docs/UI-FORMS-AND-STATES.md`（形态 / 空态裁定）·
> `docs/ROADMAP.md`（公开路线图）· `docs/screenshots/`（本项目界面实拍帧，`p*.png`）。

---

## 0. 怎么用这份文档

| 你要做什么 | 看哪节 |
|---|---|
| 加一个控件 / 改一个控件 | §5 尺寸 · §7 组件 · §9 交互态 |
| 加一段文字 / 改字号 | §2 字体 |
| 选颜色 | §3 颜色 |
| 加图标 | §4 图标 |
| 做弹窗 | §8 弹窗 |
| 排一个页面的布局 | §6 布局 |
| 想知道「现在缺什么」 | **§13 缺口清单**（带 ID，可直接进 TODO） |
| 想知道「怎么验收」 | §14 门禁 |

---

## 1. 设计基线（三条，冲突时按此判）

1. **密度优先于气派**。数据库客户端 90% 的时间在看列表：正文 **12px**、网格行高 **21/22/23/28**
   （密集/紧凑/标准/宽松）、对象树行高 **21px**（2026-10-26 用户裁定：保持和 Navicat 一样）。
   13px 界面基准会让「表名 + 类型 + 注释」三列在 1366 宽的笔记本上挤不下。
2. **颜色只来自令牌**。业务 CSS 里出现 `#rrggbb` / `rgba(...)` 即违规
   （`TokenParityTest` 扫描 `app.css`）。新增颜色必须先进 `tokens.json` 再 `java tools/GenTokens.java`。
3. **一切都要键盘可达**。可点击控件必须能被 Tab 到达、焦点必须可见（`:focused` 规则 30 条）、
   图标按钮必须有 Tooltip（鼠标）+ accessible text（读屏）。

---

## 2. 字体与排版

### 2.1 字体族

| 用途 | 字体 | 落点 |
|---|---|---|
| 界面文字 | `"System"`（JavaFX 解析成系统 UI 字体） | `.root { -fx-font-family: "System" }` |
| 数字 / 日期 / JSON / BLOB / SQL 预览 | `"Menlo"` | `.adb-grid__cell--number/--binary/--json/--date`、`.adb-sql-preview`、`.adb-details__text` |
| SQL 编辑器 | `"SF Mono", "Menlo", "Consolas", monospace` | `.adb-sql-editor`、`.adb-query__message` |

> ⛔ 不写死 `"SF Pro"` / `"Segoe UI"`：换平台就炸。
> ⚠️ **等宽字体的边界**：只给「需要按位对齐 / 需要看原始内容」的地方用。
> 表格正文、按钮、菜单一律用界面字体 —— 全表格等宽会让「表名 + 说明」这类中文文案显得稀疏。

### 2.2 3 档字号阶梯（唯一真相：`tokens.json` 的 `fs-*`）

| 令牌 | 值 | 允许出现的场景 | 禁止 |
|---|---|---|---|
| `fs-xs` | 11px | 辅助信息：状态栏、列类型行、分组标题、树副标题、提示文字、chip、角标、快捷键键帽 | 表格正文 |
| `fs-sm` | 12px | **数据面正文**：网格 / 清单 / 树 / 结构页 / 按钮 / 菜单 | 页面标题、空态标题 |
| `fs-md` | 13px | **界面基准**（`.root`）：页面 / 对象 / 空态 / 对话框标题、命令面板输入、非数据面的说明文字 | 数据面正文 |

> ⚠️ **10px（`fs-2xs`）、14px（`fs-lg`）、16px（`fs-xl`）、32px（`fs-3xl`）已于 2026-11-01
> 随 R41 · N30 移除**：10px 的角标贴着可读下限，14 / 16px 与 12 / 13px 只差 1–2px，
> 却让同一屏出现四五个「层级」—— 视觉重量碎掉。现在只有 **11 / 12 / 13** 三档：
> 层次由 **bold + 颜色** 拉开，而不是靠每级加 1px。
> 32px 是空状态图标的尺寸，由 `Icons.SIZE_EMPTY` 在 Java 侧给定，字号阶梯不再保留这一档。
> **删令牌，不给令牌硬造消费者**。

> ⚠️ **40px（`fs-4xl`）已于 2026-10-23 移除**（第十九轮批次 C · S-5）：它唯一的用处是
> 欢迎页 logo 的 `-fx-font-size`，而 S-5 把 logo 换成了**品牌 PNG** —— 尺寸改由代码定，
> 字号阶梯里不再留一个没有文字会用到的档（`TokenParityTest.everyTokenIsActuallyUsed` 会红）。
>
> ⚠️ **20px（`fs-2xl`）已于 2026-10-24 随 S-7 一起移除**：它唯一的消费者是那张欢迎页卡片的
> 标题，而欢迎页**根本不可达**（`AppShell` 构造器无条件打开常驻的「对象」首页）。
> 页面、样式类、`Icons.SIZE_BRAND` 档位、`fs-2xl` 令牌四处一起走 ——
> **删令牌，不给令牌硬造消费者**。品牌 mark 现在只出现在「关于」弹窗图形（`Icons.Icon.BRAND`）；
> 2026-10-28 顶区精简后，顶栏的品牌方块与字标一并删除。

> ⭐ 2026-10 UX-2 重构：**数据面正文统一 12px** —— 网格、清单、树、结构页、
> 按钮与输入框都落到 `fs-sm`。表格正文 13px 会让 21px 的 dense 行发挤，
> 而 Navicat 的运维密度就是 12px。13px 仍保留为 `.root` 的界面基准
> （对话框标题等非数据面）。
> 需要更密时用「视图 → 密度 → 密集」（21px 行），而不是继续压字号。

**硬规则**：

- **11px 是下限**：9px / 10px 全部废除（`AppCssAuditTest` 断言出现 0 次）。
- CSS 里**不得出现字面量字号**，只能引用阶梯值（门禁：`AppCssAuditTest`）。
- 字号**不随主题变化**（暗色同值）—— 主题只换颜色。

### 2.3 角色 → 字号速查（写界面时照抄）

| 界面角色 | 字号 | 样式类 |
|---|---|---|
| 页面标题 / 对象名 | 13px bold | `.adb-page__title`、`.adb-object-header__title` |
| 分组标题（侧栏 / 表格页签区） | 11px bold | `.adb-sidebar__section-title`、`.adb-section-title` |
| 表格正文 | 12px | `.adb-grid .table-cell` |
| 清单正文（对象 / 表清单） | 12px | `.adb-object-list .table-cell`、`.adb-table-list .table-cell` |
| 清单表头 | 12px bold | `.adb-object-list .column-header .label`、`.adb-table-list .column-header .label` |
| 列名 | 12px bold | `.adb-grid__column-name` |
| 列类型行 | 11px | `.adb-grid__column-type` |
| 行号 | 11px | `.adb-grid__row-number` |
| 排序角标 | 11px bold | `.adb-grid__sort-badge` |
| 状态栏 | 11px | `.adb-statusbar .label` |
| 徽标 / chip | 11px | `.adb-badge`、`.adb-chip`、`.adb-filter-chip` |
| 快捷键键帽 | 11px | `.adb-appbar__search-kbd`、`.adb-palette__accelerator` |
| 弹窗标题 | 13px bold | `.adb-dialog > .header-panel .label` |
| 弹窗按钮 | 12px | `.adb-dialog .button` |
| 空状态标题 | 13px bold | `.adb-empty__title` |
| 空状态说明 | 12px | `.adb-muted` |
| 空状态图标 | 32px（`Icons.SIZE_EMPTY`，不是字号） | `.adb-empty__icon` |
| Tooltip | 11px | `.tooltip` |
| 命令面板输入 | 13px | `.adb-palette__input` |

### 2.4 字重

只有 **bold** 与 normal 两档。bold 只用在：列名、树分组名、连接名、侧栏标题、
排序角标、危险计数、页面 / 弹窗标题、批量条计数。
⛔ 不用 bold 表达「强调」以外的东西 —— 满屏 bold 等于没有强调。

### 2.5 现状缺口

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| **F-1** | 表格**数字列不右对齐**（数据页、对象清单） | 金额/行数/大小无法竖向比较 | 右对齐 + 等宽字体（`TextAlignment.RIGHT` + `.adb-grid__cell--number`） |
| **F-2** | ~~一个控件里可能同时出现 3 个字号（列头：名 12 bold + 类型 11 + 角标 10）~~ | 视觉重量碎 | ✅ R41 · N30 已收敛：角标 10 → 11，列头只剩 **12 bold 主体 + 11 辅助** 两个字号；规则保留「一个控件最多 2 个字号」 |

---

## 3. 颜色与语义

### 3.1 令牌分层（`tokens.json` 共 **110** 个：**76 颜色 / 31 尺寸 / 3 时长**）

> 下表的数量与名字是从 `tokens.json` 现量出来的（不是回忆）；
> `TokenParityTest` 钉住 `meta.count`，任何一处对不上就红。

| 层 | 数量 | 令牌 | 语义 |
|---|---|---|---|
| 背景 | 15 | `bg-canvas` `bg-chrome` `bg-sidebar` `bg-surface` `bg-subtle` `bg-elevated` `bg-inset` `bg-hover` `bg-active` `bg-selected` `bg-selected-grid` `bg-header` `bg-header-current` `bg-stripe` `bg-disabled` | canvas=页面底 · chrome=顶区外壳 · surface=条带/面板 · subtle=分区底(比 surface 低一档) · elevated=浮层(弹窗/toast/命令面板) · inset=凹陷(输入框/表头/代码区) · header=表头底 · header-current=网格光标列表头 tint（Navicat 实测 `#AED4F2`） |
| 边框 | 3 | `border` `border-subtle` `border-focus` | 普通分隔 / 更淡的行线 / 焦点环 |
| 文字 | 6 | `text-primary` `text-secondary` `text-tertiary` `text-muted` `text-disabled` `text-inverse` | 正文 / 次要 / 三级(提示) / 弱化装饰(图标·分隔线) / 禁用 / 反色(主按钮上) |
| 强调 | 2 | `accent` `accent-hover` | 主色、悬停 |
| 风险 | 12 | `risk-0..3-{read/write/structure/admin}` + `-bg` + `-border` | **四级风险**（本项目强项） |
| 状态 | 7 | `success` `warning` `error` `info` + `success-bg` `warning-bg` `error-bg` | 成功/警告/错误/信息（`info` 没有 `-bg`） |
| 未保存 | 2 | `dirty` `dirty-bg` | 页签脏标记 |
| 语法高亮 | 9 | `syn-keyword/string/number/comment/function/type/ident/param/error` | SQL 编辑器 |
| 对象类型 | 7 | `kind-table` `kind-view` `kind-function` `kind-trigger` `kind-procedure` `kind-index` `kind-event` | **对象树 / 结构页的类型色**（§3.4） |
| 图标分层 | 4 | `icon-edge` `icon-tone-a` `icon-tone-b` `icon-tone-c` | 彩色图标的**角色色**（§4.5）：轮廓 + 三层填充 |
| 厂商品牌 | 6 | `vendor-mysql` `vendor-postgresql` `vendor-sqlserver` `vendor-oracle` `vendor-sqlite` `vendor-mariadb` | ⭐ R43-a：六家**真有驱动**的数据库在向导磁贴与左侧树里的品牌色，只落图标的 `tone-c` 层（§4.6）。两档各一个值（亮档压深、暗档提亮），必须在两档下对页面底 ≥3:1（`ContrastTest.vendorBrandColorsStandOutAgainstTheBackground`） |
| 尺寸 | 31 | `h-*`(6) `w-*`(4) `row-*`(5) `fs-*`(3) `sp-*`(8) `r-*`(5) | 高度 / 宽度 / 行高 / 字号 / 间距 / 圆角 |
| 时长 | 3 | `dur-toast` `dur-toast-action` `dur-toast-result` | toast 动画 |
| 遮罩 / 投影 | 3 | `overlay` `shadow-1` `shadow-2` | 弹窗遮罩与两级浮层投影 |

> ⚠️ **`text-tertiary` 与 `text-muted` 的区别**（2026-10 重构）：
> `text-muted`（#94A3B8 / #64748B）是**弱化装饰色**（图标描边、分隔线、占位），
> 只在 2.5:1 上下 —— 它**不是**可读文字色。
> `text-tertiary` 是**可读**的三级文字（提示、辅助说明），必须过 WCAG AA 4.5:1
> （浅色主题取 #5F6E82）。门禁：`ContrastTest` 按 `text-tertiary` 断言。

### 3.2 语义映射（选色时查这张表）

| 场景 | 背景 | 文字 | 边框 |
|---|---|---|---|
| 页面底 | `bg-canvas` | — | — |
| 面板 / 工具条 / 表头 / 分页条 | `bg-surface` | `text-primary` | `border`（下边框） |
| 浮层：弹窗 / toast / 命令面板 | `bg-elevated` | `text-primary` | `border` |
| 输入框 / 代码区 | `bg-inset` | `text-primary` | `border-subtle`（focus → `border-focus`） |
| hover | `bg-hover` | `text-primary` | — |
| 按下 | `bg-active` | `text-primary` | — |
| 选中（树 / 表格 / 命令面板） | `bg-selected` | `text-primary` | — |
| 禁用 | `bg-disabled` | `text-disabled` | `border`（**保留**） |
| 主按钮 | `accent` | `text-inverse` | 无 |
| 危险按钮 | 透明 / `error-bg`(hover) | `error` | `border` |

> ⚠️ **选中态文字不要变蓝**：蓝字压蓝底对比度约 2.1:1。
> 规则是「**浅蓝底 + 正文色字**」（`.adb-object-tree .tree-cell:selected .adb-tree__name`）。

### 3.3 风险四级（★ 本项目比 Navicat 强的地方）

| 级别 | 语句 | 令牌 | UI 表现 |
|---|---|---|---|
| 0 只读 | `SELECT` / `SHOW` / `DESCRIBE` | `risk-0-*`（蓝） | 徽标「只读」，**不弹确认** |
| 1 写入 | `INSERT/UPDATE/DELETE` | `risk-1-*`（黄） | 徽标 + 一次确认 |
| 2 结构 | `ALTER/CREATE/DROP` | `risk-2-*`（红） | 徽标 + 两级确认（展示 SQL） |
| 3 管理 | 未知 / `GRANT/ATTACH` | `risk-3-*`（紫） | 徽标 + 两级确认 |

徽标样式：`.adb-risk--read/write/structure/admin`（pill 形，`r-full`）。

### 3.4 对象类型色（`kind-*`）

> 数值 2026-10-30 现量自 `tokens.json`（**别信回忆**，这张表以前整列是过期的）。
> 真相仍是 `tokens.json`；本表只是给人查语义。

| 类型 | 令牌 | 暗色 / 亮色 | 用在哪 |
|---|---|---|---|
| 表 | `kind-table` | `#2DD4BF` / `#0F766E` | 对象树表节点图标、表清单行首、表清单卡片 |
| 视图 | `kind-view` | `#C4B5FD` / `#7C3AED` | 对象树视图节点图标 |
| 函数 | `kind-function` | `#FBBF24` / `#B45309` | 对象树函数节点图标 |
| 触发器 | `kind-trigger` | `#FDBA74` / `#C2410C` | 对象树触发器节点图标 |
| 过程 | `kind-procedure` | `#7DD3FC` / `#0369A1` | 对象树过程节点图标 |
| 索引 | `kind-index` | `#94A3B8` / `#64748B` | **结构页「索引」页签行首图标** |
| 事件 | `kind-event` | `#F9A8D4` / `#BE185D` | 对象树事件节点图标 |

> 规则一：**颜色只上在图标上，文字保持正文色**。彩色文字会让整棵树变成调色盘。
> 规则二（R35 N4-b）：彩色档里类型色**只走轮廓层**（`.adb-icon-edge`），
> 三层 `icon-tone-*` 保持中性浅蓝 —— 一个图形只能有一个语义色道，
> 开两个道早晚改一处漏一处。门禁：`IconKindHueTest` 读**计算色**。

### 3.5 硬规则

1. 颜色**不得**是唯一的区分手段（禁用态同时用底色 + 文字色 + 保留边框）。
2. 危险必须**双通道**：颜色 + 文案（风险徽标 + 确认框里的后果说明）。
3. 暗色主题下焦点环必须仍可见（`border-focus` 两套主题都是亮蓝）。
4. 任何新颜色先进 `tokens.json` → `java tools/GenTokens.java` → 再写 CSS。

### 3.6 现状缺口

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| **C-1** | ~~缺 `overlay` / `shadow-1` / `shadow-2` 三个令牌~~ | — | ✅ **已补**（V-2）；UX-1 删 `shadow-2`、只留 `shadow-1` 一级浮层投影 |
| **C-2** | ~~对比度**无自动检查**~~ | — | ✅ **已补**（V-8）：`ContrastTest` 门禁（2 例），并据此改了 6 个色值 |
| **C-3** | ~~`text-tertiary` 在亮色下偏弱~~ | — | ✅ **已修**：亮色 #8b95a3（3.03:1）→ #6A717C（4.92:1）；暗色 #6e7681 → #86909D |

---

## 4. 图标系统

### 4.1 规格

- 视框 **16×16**，描边 **1.5px**，**圆头圆角**（`StrokeLineCap.ROUND`）。
- 尺寸只从 `Icons.SIZE_*` 取：**14 标签 / 16 工具条 / 20 主工具条 / 26 Ribbon**；
  另有语义尺寸 8（状态点）/ 12（徽标）/ 32（空状态）。
  ⛔ 不许在调用点写数字（门禁：`IconSystemAuditTest.iconSizesComeFromTheNamedScale`，
  它同时匹配 `Icons.of(...)` 与 `Icons.colored(...)` —— 彩色通道上线后只盯 `of` 等于把棘轮松开）。
  ⛔ 40（`SIZE_BRAND`）已随 S-7 删除 —— 它唯一的消费者是不可达的欢迎页；品牌 mark 走 `SIZE_TOOLBAR`。
- 缩放时**反向补偿描边**（`STROKE / scale`）——放大后视觉粗细不变。
- 全部**描边**图标（不填充），少数状态点例外（`STAR_FILLED` / `DOT` 用 `.adb-icon--filled`）。
  彩色档（§4.5）是唯一的例外：它按 `data-paint` 分层**填充 + 描边**。
- 颜色由 CSS 接管（`.adb-icon` → `-adb-text-secondary`；hover → `text-primary`；
  主按钮 → `text-inverse`；禁用 → `text-disabled`），**切主题自动跟随**。
  ⚠️ `.adb-icon` 这条通用规则排在 `app.css` **后半段**：任何想脱离 `text-secondary` 的字形
  （品牌方块里的 `text-inverse` 就是第一个）**必须写两类选择器**提高特异性，单类会被后写的通用规则顶掉 ——
  见 §4.3 第 6 条。

### 4.2 图标清单（**63** 个，`io.agenticdb.ui.Icons.Icon`）

| 分类 | 图标 | 语义 |
|---|---|---|
| 通用动作 | `CLOSE` `CHECK` `ADD` `EDIT` `COPY` `DELETE` `SAVE` `REFRESH` `UNDO` `REDO` `SEARCH` `FILTER` `CLEAR` `SETTINGS` `STAR` `STAR_FILLED` `DOT` `MORE` | 关闭 / 勾选（布尔单元格、CHECK 约束）/ 新增 / 编辑 / 复制 / 删除 / 保存 / 刷新 / 撤销 / 重做 / 搜索 / 筛选 / 清除 / 设置 / 收藏 / 收藏实心 / 状态点 / **更多（⋯，页签行的溢出菜单）** |
| 导航 | `PREVIOUS` `NEXT` `FIRST` `LAST` `CHEVRON_LEFT` `CHEVRON_RIGHT` `MOVE_UP` `MOVE_DOWN` `FOLDER` | 上/下/首页/末页/左/右 / 行上下移（主键与列排序编辑器）/ 连接分组 |
| 传输 | `EXPORT` `IMPORT` `TRANSFER` | 导出 / 导入 / 双向 |
| 执行 | `RUN` `RUN_ALL` `STOP` `EXPLAIN` | 执行 / 执行全部 / 停止 / 执行计划 |
| 对象 | `DATABASE` `TABLE` `STRUCTURE` `COLUMNS` `VIEW` `VIEW_OBJECT` `FUNCTION` `TRIGGER` `INDEX` `OPEN` | 连接 / 表 / 结构 / 列 / 视图 / 视图对象 / 函数 / 触发器 / **索引** / 打开选中对象 |
| 其它 | `CONNECT` `QUERY` `LIST` `GRID` `THEME` `PALETTE` `HISTORY` `DIAGNOSTICS` `LOCK` `ALERT` | 连接 / 查询 / 清单 / 网格 / 主题 / 命令面板 / 历史 / 诊断 / 加密连接 / 警告提示 |
| 品牌 | `BRAND` | ⭐ **AIDB mark**（数据圆柱 + 右上 AI 四角星芒）—— 只用在**「关于」弹窗的图形**；2026-10-28 顶区精简后，品牌在界面里的落点是窗口标题 + 窗口图标 + 关于框（顶栏不再有品牌块） |
| 厂商图形 | `DB_MYSQL` `DB_POSTGRESQL` `DB_SQLSERVER` `DB_ORACLE` `DB_SQLITE` `DB_MARIADB` | ⭐ R43-a：**同风格自绘**的六家数据库标识（§4.6）—— 登记在 `ConnectionCatalog` 的行列上，向导磁贴与左侧树的连接节点从同一行取，两处的 MySQL 因此必然同形同色。没有 `DB_MONGODB` 这类：没驱动的厂商不给图形，给了就是承诺一个连不上的连接 |

### 4.3 硬规则

1. **一个符号只表一个意思**（`⚙` 曾同时表示「结构」「设置」，已拆为 `STRUCTURE` / `SETTINGS`）。
   门禁：`IconAuditTest.managedSymbolsMeanExactlyOneThing`。
2. **按钮里不许出现 Unicode 图标字符**；⛔ 也不许出现「中文词当图标」。
   门禁：`IconSystemAuditTest`（① 文字不许以图标字符开头 ② 不许纯符号按钮 ③ `Icons` 无死常量 ④ 领域层不许带图标）。
3. **图标按钮只放图标**，文字进 Tooltip；命中区 ≥ **28×28**（`.adb-rail__button` 32×32）。
4. 每个图标控件同时具备 **Tooltip**（鼠标可发现）与 **accessibleText**（读屏可朗读）。
   门禁：`IconAuditTest.everyIconCarriesTooltipAndAccessibleText`。
5. **什么时候用图标 / 什么时候用文字**：
   - 高频、无歧义、可并排成条 → 图标（工具栏、分页器、树节点）。
   - 有歧义、需要学习成本 → 图标 + 文字（主操作「保存」、功能栏、危险动作）。
   - 破坏性动作 → 图标 + 文字 + 红色（不许只留一个垃圾桶图标）。
6. ⭐ **覆盖 `.adb-icon` 的字形颜色必须用两类选择器**。JavaFX 的 CSS 层叠里，**同特异性**的单类规则
   按「后写胜」判定，而 `.adb-icon { -fx-stroke: -adb-text-secondary }` 位于 `app.css` 末尾 ——
   任何写在它**前面**的单类覆盖规则（历史第一个受害者是已被删除的应用栏品牌方块）**静默失效**。
   写法：给宿主容器一个类，用 `.adb-host .adb-host__glyph`（特异性 0,2,0），并保证宿主本身
   处在挂着 `theme-light` / `theme-dark` 的子树里 —— 2026-10-28 顶区精简时，工具件被搬到
   `#adb-chrome-row` 后脱离主题根，连接点直接算成纯黑（`0x000000ff`）。
   门禁：读**计算后的**颜色（如 `AppBarTest.theConnectionDotTurnsGreenWhenOnline` 读 `getFill()`），
   并要求测试 `@Start` 把两套样式表挂上场景（不挂样式表时 `getStroke()`/`getFill()` 只是控件默认色，断言等于没测）。
   教训：**这条 bug 全绿了三轮才被像素对比抓到** —— 绿门禁不等于真看得见。

### 4.4 现状缺口

| # | 缺口 | 影响 | 状态 |
|---|---|---|---|
| **I-1** | ~~缺 **20px 图标档**（功能栏用 16px 顶着大字标）~~ | — | ✅ **已修**（UX-1）：新增 `Icons.SIZE_PRIMARY = 20`，对象主工具条使用 |
| **I-2** | ~~标签栏 11px、rail 16px、功能栏 16px 三档不统一~~ | 同一语义在不同处大小不一致 | ✅ **已修**（UX-1）：命名档位 14/16/20 三个常量，调用点写数字被门禁拦下 |
| **I-3** | ~~无应用图标~~ | 窗口标题栏无品牌标识 | ✅ **已修**：`App` 把多尺寸品牌图标注册进 `stage.getIcons()`（门禁：`BrandMarkAuditTest`） |
| **I-4** | 彩色档覆盖**导航词表 + 功能区 + 页面级 16px 内联工具条**（18 枚资产，无资产的图标自动回退单色）+ **六家数据库图形** | ~~同一图标在树上和工具条上两种画法~~ —— 已消；~~连接磁贴仍是单色，等第三方 logo 的裁定~~ —— 已消 | ✅ 工具条部分（R35 N4-c，门禁 `ToolbarIconChannelTest` 双向钉 + `DataGridPageTest` 从真实页面验两区画法）；✅ 厂商部分（R43-a **自绘同风格**六枚，不取原版位图也不碰商标，§4.6；门禁 `VendorGlyphTest`） |

### 4.5 彩色档（R35）

单色档是「一个字形 + 一个 CSS 颜色」；彩色档是**同一个 SVGPath 集合按角色分层**。八条规矩，每条后面是它的门禁：

| # | 规矩 | 门禁 |
|---|---|---|
| 1 | **谁能上色**：一切「一眼要认出是什么」的图标 —— 对象树、页签、页签栏、对象头、结构页索引列、**功能区 6 条命令**、**页面级 16px 内联工具条**（上沿那一排：筛选 / 列 / 历史 / 保存 / 新增行…）。仍走单色的三处：贴着网格的**行操作条**（`+ − ✓ ✗`）、危险动作、以及一切**没有彩色资产**的图标（自动回退）。 | `IconSystemAuditTest.theNavigationVocabularyIsFullyColored` · `IconSystemAuditTest.theRibbonVocabularyIsFullyColored` · `ToolbarIconChannelTest` · `DataGridPageTest.theTopToolbarIsColoredWhileTheRowStripStaysMonochrome` |
| 2 | **资产放哪**：`ui/adb-ui-core/src/main/resources/io/agenticdb/ui/icons/color/*.svg`，文件名与单色档同源，且**不许有没人用的资产**。 | `ColoredIconTest.everyColoredAssetIsReferencedByAnIcon` |
| 3 | **资产里不许有颜色**：只写 `data-paint="edge\|tone-a\|tone-b\|tone-c\|accent"`，一个 `fill`/`stroke` 字面量都不许出现。 | `ColoredIconTest.coloredAssetsDeclareRolesInsteadOfColors` |
| 4 | **颜色从令牌来**：五个角色色是 `-adb-icon-*` looked-up color，由 `app.css` 的 `.adb-icon-*` 提供 —— 业务代码零颜色字面量。 | `ColoredIconTest.everyRoleCssRuleSetsBothFillAndStroke` · `StyleClassCoverageTest` · `TokenParityTest` |
| 5 | **暗色不出第二套资产**：同一批 SVGPath，靠主题类换 `-adb-icon-*` 的值。这样 P3（双套资产漂移）从结构上不存在。 | `IconKindHueTest.theKindHueFollowsTheThemeWithoutASecondAssetSet` |
| 6 | **分层顺序**：`tone-*` 在下、`edge` 在上 —— 轮廓永远压住填充，放大不糊。坐标一律换算进 16 视框。 | `ColoredIconTest.everyColoredIconKeepsOneEdgeLayerOnTop` · `ColoredIconTest.coloredLayersConvertIntoTheSixteenViewBox` |
| 7 | **类型色只上在 `edge` 层**：树/页签的 `kind-*` 色用 `.adb-tree__icon--table .adb-icon-edge` 这类**后代选择器**打在子节点上。容器是 `Group`，没有可绘属性，挂在容器上的 `-fx-stroke` 是**死规则**。 | `IconKindHueTest.coloredTreeIconsKeepTheirKindHue` |
| 8 | ⛔ **素材红线**：不提取 Navicat 原始位图；借用的 Lucide 路径按 ISC 署名 —— 许可原文与资产**同目录**分发（`icons/lucide/` 与 `icons/color/` 各一份）。 | `ColoredIconTest.everyBorrowedAssetDirectoryShipsItsLicense` · `docs/COMPONENT-SELECTION.md` §1.6 |

> ⚠️ 第 7 条是 R35 第一批踩出来的：单色档时代 `.adb-tree__icon--table` 直接挂在 `SVGPath` 上，
> 彩色档返回的是 `Group`，同一条规则搬到 Group 上就**静默失效**（表格图标丢了青绿、视图丢了紫）。
> 测试也因此不能只 `applyCss()` 父节点 —— JavaFX 的 `applyCss()` **不向下级联**，
> 必须父节点与每个子节点分别调用，否则读到的是控件默认色，断言等于没测。

> 📌 **第 1 条在 N4-a 改过一次判**（不是「改绿」，是原来写窄了）：本条第一版写的是
> 「功能区保持单色」。翻 `docs/screenshots/navicat/navicat17-object-list-2026-10-27.png`
> 逐像素看，Navicat 17 那一排大图标恰恰是**全界面最彩的**（蓝插头、绿加号、
> 橙用户、粉紫 BI 渐变）。C-1 拍板是「完全照 Navicat」，所以功能区必须进彩色范围；
> 16px 内联工具条与危险动作仍留单色。同批量的还有尺寸：功能区图标实测 ≈30px，
> 但那是 **90px 双行带**里的 30px；R32-D 已把带子砍到 64px 单行，等比下来 26px
> 反而比参考更宽松 —— 所以 `SIZE_RIBBON` **维持 26**，30px 等 R36 重做顶区
> （140px / 12 磁贴）时和布局一起判。

> 📌 **第 1 条在 N4-c 又改了一次判**（2026-10-30，同样不是「改绿」）：第一版把
> 「16px 内联工具条」整体划在单色区，依据是想当然的「小图标彩了会糊」。
> 逐像素翻 `docs/screenshots/navicat/navicat-data-grid-2026-10-24.png` 的两条横带，
> 结论是**Navicat 的 16px 分两区**：
> ① 上沿工具条（Table Profile / Begin Transaction / Cell Editor / Filter & Sort /
> Columns / History / Import / Export）是**蓝色实心小图形**，和树上同一族颜色；
> ② 贴在网格左下的行操作条（`+ − ✓ ✗ ↺`）是**灰的单线**。
> 于是 I-4 那条「同一图标在树上和工具条上两种画法」是**真缺陷**，按 ① 修：
> 工具条默认走 `Icons.colored`，行操作条按 ② 显式走 `IconButtons.iconMono`。
> ⚠️ 换通道带来一条必须同时补的规则：分层返回的是 `Group`，只认 `.adb-icon` 的
> 按钮态规则（hover / `--primary` / `:disabled`）**一条都命中不了子层** ——
> 不补就是「蓝底上蓝边看不见图标」与「禁用还亮着蓝边」，后者撞诚实条款。
> 补色规则见 `app.css` 的「彩色分层在按钮态里的翻色」段，门禁读**计算色**。

---

### 4.6 厂商数据库图形（R43-a）

§4.5 那八条**全部适用**（角色分层、颜色只从令牌来、暗色不出第二套资产、轮廓层收尾），
这里只补厂商这一族特有的口径。

| 家 | 图形取的是该家公开标识里**最能一眼认出的几何特征**（自绘，不是商标图标） | 品牌色 light / dark |
|---|---|---|
| MySQL | 一弯朝左下鼓的背鳍 + 右上**独立**的水滴（两笔不相连，14px 下才分得开） | `#00758F` / `#4FC8DE` |
| PostgreSQL | 两条立柱收进 U 形底（獠牙 / 象鼻的抽象化） | `#336791` / `#7FB3D9` |
| SQL Server | 六边形环：外圈 + 同心内圈，内圈按 EVEN_ODD 挖空（宝石切面） | `#8A5A00` / `#E3B23C` |
| Oracle | 横椭圆环：外椭圆 + 同芯内椭圆挖空 | `#C71000` / `#FF7168` |
| SQLite | 羽叶：一条从右上扫到左下的弧 + 背边，中间一条叶脉 | `#0F83AE` / `#67D4EF` |
| MariaDB | 圆球里挖出一条跃身海豚的剪影（球 + 一条反剪的弧） | `#003545` / `#8FC9E0` |

两个落点、同一枚图形：连接向导的类型磁贴 **44px**（`SIZE_TILE`）与左侧树的连接节点
**14px**（`SIZE_LABEL`）。14px 是这六枚的真正考验 —— 帧覆盖两档：磁贴看
`p5-connect-type` / `p5-connect-type-dark`，树看 `p0-shell` / `p5-dark`。

**四条规矩**：

1. **登记在 `ConnectionCatalog` 的行上**，不是新开一个 `DialectKind` switch 文件 ——
   图形、品牌色作用域类、显示名同一行给全，向导磁贴与左侧树的连接节点**读同一行**。
   分叉的代价是「同一个 MySQL 在两处两种画法」，正是 N4-c 已经踩过一次的坑。
   `DialectBranchAuditTest` 允许登记，禁止散落。
2. **只有真有驱动的六家有图形，也只有这六家出现在界面上**。`DialectKind.values()` 与图形集合一一对应
   （`VendorGlyphTest` 的静态块钉住）。R43-b 之前类型页还摆着 MongoDB / Redis / Snowflake 与 13 个厂商
   的中性圆柱磁贴 —— 给连不上的连接配一张卡，等于界面在承诺后端没有的能力，收成六张后那十六张连同
   「无驱动说明页」一起删了。
3. **品牌色只上 `tone-c`（最深填充面）一层**，轮廓层继续吃 `-adb-icon-edge`。
   理由是 §4.5 第 7 条的同一条：类型线索有两条（形状 + 颜色），而轮廓是「看不看得见」的那条 ——
   把它染成深品牌色，暗色档就糊。两档的品牌色各自过 ≥3:1 对页面底（`ContrastTest`）。
4. **认不出方言时不许挑一家画上去**：回落 `CONNECT`（§「诚实条款」）。
   侧栏那条 `aConnectionWithoutAProfileKeepsTheNeutralPlug` 守的就是这个。

> ⚠️ **变异验证记一条改判**：`SideBarTreeTest` 这一条最初断的是 `DemoNode` 的 `toString()`
> ——「登记行给了图形」，看起来在测接线，实际把 `SideBar` 里挂作用域类那两行删掉**照样全绿**。
> 症状是「左树六家全一种颜色」，而这恰好是这条门禁该抓的东西。
> 现在断的是**渲染出来的 cell 里的图标容器类名 + `tone-c` 层的计算色**：
> 取值对不对归取值那些用例管，这里只管**画出来**。

---

## 5. 间距、圆角与尺寸

### 5.1 间距（7 档 4px 阶梯 + 1 档例外，`sp-*`）

| 令牌 | 值 | 用途 |
|---|---|---|
| `sp-1` | 2px | 图标与文字的微调、角标内边距 |
| `sp-2` | 4px | 紧凑元素间隙、chip 内边距、树 cell 内边距 |
| `sp-3` | 8px | 控件内边距（图标按钮 4×8）、分组内元素间隙 |
| `sp-4` | 12px | 面板内边距、按钮内边距（4×12）、页面 body 横向留白 |
| `sp-5` | 16px | 区块之间、页面 body 顶部留白、卡片内边距 |
| `sp-6` | 24px | 大区块之间 |
| `sp-7` | 32px | 空状态上下留白（欢迎页随 S-7 删除后，这一档只剩空态在用） |
| `sp-1h` | 6px | **dense 档单元格水平内边距**（`2 6`；1.5×`sp-2` 的已登记例外） |

**硬规则**：所有间距是 4 的倍数，仅 `sp-1`（2px，图标微调）与
`sp-1h`（6px，dense 单元格内边距）两个已登记例外。
⛔ CSS 里不得出现阶梯外的 padding（门禁：`AppCssAuditTest`）；**padding 必须带 `px` 单位**。

### 5.2 圆角（3 档 + 浮层 + pill，`r-*`）

| 令牌 | 值 | 用途 |
|---|---|---|
| `r-sm` | 2px | 按钮、输入框、搜索框、连接状态、check-box 方框 |
| `r-md` | 3px | 列表格 |
| `r-lg` | 4px | 表格容器、卡片、抽屉 |
| `r-xl` | 6px | **浮层**：对话框、菜单、下拉弹层、命令面板、toast（最大一档） |
| `r-full` | 999px | 徽标、chip、状态栏方言角标、风险徽标 |

**硬规则**：**外层圆角 ≥ 内层圆角**（浮层/对话框 6 > 卡片 4 > 列表格 3 > 按钮/输入框 2）。
⛔ 不得出现阶梯外的 radius（2026-10-26 起按 Navicat 2–4px 语言收敛；旧 `r-xs` 3px 与 8/10px 档已删）。

### 5.3 固定尺寸表

| 元素 | 尺寸 | 来源 |
|---|---|---|
| 菜单栏 | 系统默认（macOS 进系统菜单栏） | — |
| **命令带（Ribbon）** | **91px**，12 格磁贴；顶区合计 **140px**（菜单 24 + 91 + 页签行 25） | `h-appbar`（`Tokens.H_APPBAR_LIGHT/DARK`）/ `.adb-ribbon` |
| Ribbon 命令按钮 | **62×58**，图标 `Icons.SIZE_RIBBON`（26，**彩色档**：命令色只打在轮廓层，见 §4.5）+ 11px 标签；12 格按 Navicat 分组（Connection / New Query / Table / View / …） | `.adb-ribbon__button` / `.adb-ribbon__label` / `.adb-ribbon__icon--*` |
| 页签行 | **25px**，白底 + 1px 下边界；只放文档页签（全局工具件在 Ribbon 行） | `h-tabbar` / `.adb-tabbar` |
| 页签行紧凑形态 | 行宽 **<780px** 触发：收掉连接副标题与搜索提示词，搜索框缩为 30px 放大镜 | `.adb-appbar__utility--compact` |
| 对象标题栏 | **40px** | `.adb-object-header` |
| 对象切面条 | 随对象标题栏（40 内） | `.adb-object-tabs` |
| 工具栏（数据页） | **30px** | `h-toolbar` |
| **状态栏** | **23px** | `h-statusbar` |
| 侧栏（展开） | **250px**，可拖 200–360 | `w-sidebar*` |
| 侧栏（折叠 rail） | **48px** | `w-sidebar-collapsed` |
| 输入框高 | **28px** | `h-input` |
| 按钮高 | **28px** | `h-button` |
| **行高（列表/树）** | 19 / 20 / **21** / 26（密集/紧凑/标准/宽松，默认标准） | `RowHeights.of()` / `row-*` |
| **行高（记录网格）** | **列表 + 2**：21 / 22 / **23** / 28（默认 23；`RowHeights.grid()`） | `RowHeights.grid()` |
| 分页器 | **38px** | `.adb-grid__pager` |
| **对象树行高** | **固定 21px**（`row-tree`；2026-10-26 Navicat 实测，不再跟密度档位放大） | `row-tree`（21） |
| 侧栏 rail 按钮 | 32×32 | `.adb-rail__button` |
| 详情抽屉宽 | 360–620（默认 480） | `CellDetailsDrawer` |
| 命令面板宽 | ≤ 620 | `CommandPalette` |
| 列筛选面板宽 | 320–340 | `ColumnFilterPanel` |
| 列设置弹层宽 | 260 | `.adb-column-panel` |

### 5.4 控件内边距表

| 控件 | padding |
|---|---|
| 普通按钮 | `4px 12px` |
| 图标按钮 | `4px 8px` |
| 输入框 / 下拉 | `4px 8px` |
| chip / 徽标 | `2px 8px` / `2px 12px` |
| 菜单项上下文菜单 | `4px` |
| 页面 body | `12px 16px 16px 16px` |
| 网格 body | `0 16px` |
| 网格 / 清单 / 结构单元格（compact 及以上） | `4px 8px`（清单 `0 8px`） |
| 网格 / 清单 / 结构单元格（dense） | `2px 6px`（清单 `0 6px`，使用 `sp-1h`） |
| 面板 / 卡片 | `12px` / `16px` |
| Filter & Sort 面板 | `8px 12px`（条件行 `4px`，行间距 `6px`） |

---

## 6. 布局

### 6.1 七区骨架（2026-10-30 R36：顶区 140px）

```
┌──────────────────────────────────────────────────────────────┐
│ 菜单栏 24px（系统原生，白底）                                   │
├──────────────────────────────────────────────────────────────┤
│ Ribbon 91px：12 格磁贴（Connection·New Query·Table·View·…·BI）  │  ← chrome #F0F0F0，62×58
│                                        ● 当前连接 ▾ [⌘K] ◐ ⋯ │  ← 全局工具件在 Ribbon 右端
├────────────┬─────────────────────────────────────────────────┤
│            │ 页签行 25px：对象(常驻) · users × · 查询示例 ×     │  ← 白底 + 下边界
├────────────┼─────────────────────────────────────────────────┤
│ 侧栏 250px │ 内容区（页面自行管理加载/空/错误三态）            │  ← canvas
│ (可拖/折叠)│                                                  │
│ 收藏        │                                                  │
│ 连接（对象树）│                                                  │
│ 工作台      │                                                  │
│ 工具        │                                                  │
├────────────┴─────────────────────────────────────────────────┤
│ 状态栏 23px：按页型换段（查询 6 段 / 数据 5 段 / 对象 3 段，见 §7.6）│  ← #F0F0F0 + 上边界
└──────────────────────────────────────────────────────────────┘
```

> ⭐ 2026-10-26 用户裁定「保持和 Navicat 一样」；2026-10-30 R36 **撤销** 2026-10-28 的「顶区精简」，
> 按 Navicat 17 实拍做回 **140px** = 菜单 24 + Ribbon 91 + 页签行 25。
> Ribbon 是 12 格磁贴（Connection · New Query · Table · View · Function · User · Others ｜
> Query · Backup · Automation · Model · BI），其中 View / User / Backup / Automation / Model / BI
> 背后能力面未建，按诚实条款落到**如实说明页**；全局工具件（连接 / 搜索 / 主题 / ⋯）放在 Ribbon 右端，
> 页签行**只放页签**（N7）。页面内的对象页签 26 + 对象工具条 30 仍归页面。

| 区域 | 规则 |
|---|---|
| 菜单栏 | 9 个顶级菜单；全部项走 `ActionRegistry`；macOS 进系统菜单栏 |
| Ribbon | 12 格磁贴，62×58；未建能力（View / User / Backup / Automation / Model / BI）落到如实说明页，不留死按钮 |
| 页签行 | 只放文档页签；全局工具件不在这里 |
| 侧栏 | 搜索框在**底部**（与 ★、« 同行）；对象树满行高亮、类型色图标、右侧计数；重建后视口回顶 |
| 标签栏 | 第一个是**常驻「对象」标签**（无关闭钮）；下划线指示当前标签；脏点 `●N`；中键关闭 |
| 内容区 | 页面自己管三态；网格页与查询页各有一套骨架（见下） |
| 状态栏 | **按页型换段**（N25）：查询页 6 段 / 数据页 5 段 / 对象页 3 段，段间 1px 分隔线；不合并成一条长消息 |

### 6.2 网格页骨架（从上到下）

```
objectHeader（类型图标 + 表名 + 类型徽标 + 真实行数 | 刷新·结构 | 连接·可写性）
objectTabs（数据 / 结构 / 索引 / 外键 / 触发器 / SQL —— 当前切面下划线）
├ 数据切面（N19 / N20 / N31）
│   数据工具条：13 具名键 —— Table Profile▾ ｜ Begin Transaction ｜ Cell Editor · Filter & Sort ｜
│                Columns · History ｜ Fill with AI · Data Profiling ｜
│                Import · Export · Data Generation · Data Masking · Create BI Workspace
│              + 右端 2 小键：视图 ≡▾ · 溢出 »（放不下的具名键从右往左折进 »，键盘可到达）
│   chips（已生效的筛选 / 排序条件，可单个删除 + 全部清除；无条件下整条隐藏）
│   Filter & Sort 面板（工具条按钮按下态打开，内嵌在工具条与网格之间，把网格往下推 —— 见 §7.3）
│   findBar（⌘F 本页搜索，内嵌在网格上方，不弹窗）
│   body（网格 / 表单视图二选一；batch 入口 = 行多选 + 右键菜单，无批量条）
│   底栏：左 = 行操作条（+ − ✓ ✗，纯图标灰单线，只读表整排消失）｜
│         右 = 首 / 上 / [页码框] / 下 / 末 · 齿轮▾（每页行数 + 跳至记录）· 放大镜 · 网格/表单切换
│         （记录计数不在这排 —— 由状态栏呈现；窄宽时从右往左收掉 表单 → 网格 → 放大镜，
│          五键 / 页码框 / 齿轮永不收，可用宽度还会让出详情浮层压住的那一截）
└ 其它切面
    结构 / 索引 / 外键 / 触发器 / SQL（真实内省结果，只读）
```

> ⚠️ 切面切换**不丢数据页的状态**：编辑缓冲、分页位置、滚动位置都保留在原页面上。
> 切面内容是**真实内省结果**（`SchemaService.structure`），不是静态示例。
> 读不到时明确显示「读取结构失败：…」而不是空白表格。

### 6.3 查询页骨架

```
toolbar 两行 62px（N21）
  row1：保存 · 查询构建器 · 美化 · 片段 · Ask AI ·（右）风险徽标 · 历史
  row2：查询名▾ · 连接▾ · ▶ Run▾（主体 = 当前语句；▾ = 当前 / 全部 / 带参数执行）·
        停止 · 解释 ·（右留白）
findBar（查找替换，内嵌在编辑器上方，不弹窗）
editor（RichTextFX：行号 + 当前行 + 高亮 + 括号配对 + 表名下划线 N22）
split（可拖分隔条）→ results
  results 页签 27px（Message / Summary / Result，N23）
  视图条 32px：Data / Info —— Info 放列类型条 + 语句原文（类型 chips 不再压结果）
  表格：单行表头 #E7E7E7
statusbar：连接 · 语句 · 回执 | 选择 · 耗时 | 行数 · 最后刷新（N25）
```

> 页标题删除（连接已由状态栏 / 连接菜单表达）；Clear 收进编辑器右键菜单；
> Save as Named Query 与 Open Saved Queries 收进查询名菜单；Run All / 带参数执行收进 ▶ 的箭头。
> 工具栏与结果区之间的「自动提交 · 只读」段删除 —— 会话设置不是页面状态。

### 6.4 结构页骨架

```
header（表名 + 类型 + 概览摘要：列/主键/索引/外键/触发器计数）
toolbar（新增列/修改/重命名/删除 | 刷新/复制DDL/复制SELECT）
tabs（列 / 索引 / 外键 / 触发器 / DDL）
```

### 6.5 布局硬规则

1. **4px 栅格**：所有内边距、外边距取 `sp-*`；不对齐的像素一律是 bug。
2. **页面 body 统一 `12px 16px 16px 16px`**；网格页左右留白 16px。
3. **内容区最小宽度 1024**（1366 笔记本为基准）；侧栏可拖到 360，拖动不影响标签页。
4. 表格/树**满行高亮**（不是圆角胶囊）——胶囊会把行与行切断，看不出层级。
5. **窄窗口下工具栏不许溢出或互相覆盖**：纯图标按钮用 `.adb-button--icon`（内边距 4×8），
   搜索框可收缩（最小 110px），文字按钮用 `USE_PREF_SIZE` 保护不被压扁。
6. **弹窗高度不得超过屏幕**（`DialogStyles` 按屏幕限高）——底部按钮必须在屏幕内。

---

## 7. 组件规格

### 7.1 按钮（四档层级）

| 层级 | 样式类 | 视觉 | 用在哪 |
|---|---|---|---|
| **primary** | `.adb-button--primary` | 实心强调色 + 反色字 | 每屏**至多一个**：保存 / 确定 / 新建表 / 空态的「下一步」 |
| **default** | `.adb-button` | elevated 底 + 1px 描边 | 次要动作 |
| **ghost** | `.adb-button--ghost` | 透明底透明边 | 工具栏次要项、关闭类 |
| **danger** | `.adb-button--danger` | 红字（图标同红）+ hover 红底 | 删除 / 清空 / 删表 |

规则：

- primary **不与 danger 同屏**（保存 vs 删除并排 = 危险）。
- 破坏性动作**必须**走 `DestructiveConfirm`，默认按钮钉在「取消」。
- 工具条按语义**分组**，组间 1px 分隔线（`.adb-toolbar__separator`）：数据页「数据动作 | 查询与视图 | 数据交换 | 搜索」、
  清单页「对象动作 | 表操作 | 数据交换 | 视图」。
- **主操作 = 图标 + 文字 + tooltip + accessibleText**（`IconButtons.labeled(...)`）：凡「改数据 / 走网络 / 不可逆」
  的动作必须有文字 —— 保存、筛选与排序、列、视图、导入、导出、删除记录；次要动作（清除条件）保持纯图标。
  旧的「只保存带文字、其余纯图标（Navicat 形态）」已作废：纯图标把「删除」和「刷新」摆成同一颗方块，靠猜。
- **窄窗口收字不收义**：窗口宽 < `1100px`（`DataGridPage.TOOLBAR_LABEL_MIN_WIDTH`）时主操作退化为
  `.adb-button--icon` + tooltip，**tooltip 与 accessibleText 任何档位都保留**。量的是**窗口宽度**而不是页面宽度 ——
  被详情面板挤窄时该缩的是搜索框，不是动作名。文字必须由登记表（`setToolbarLabel`）统一收发，
  裸 `setText` 会让收好的字在数据变脏时自己长回来。

### 7.2 输入类

| 控件 | 规格 |
|---|---|
| 文本框 | `bg-inset` 底 + `border-subtle` 描边 + 6px（`r-md`）圆角 + `4px 8px` + 高度钉 28px；focus 只换描边色（**不改宽度**，避免抖 1px） |
| 下拉 / 复选 / 数字 | 与文本框同一套（`.combo-box` / `.check-box` / `.spinner` 已在 CSS 统一） |
| 禁用 | `bg-disabled` + **opacity 还原为 1**；文本框描边转透明，复选框描边退到 `text-disabled`（U23：暗色下 `bg-disabled` 只有 8% 不透明度，描边若也透明，这格就查无此处） |
| 校验错误 | 字段旁红字（`.adb-error`）+ 边框转 `error`；**错误行常驻**（不随步骤消失） |

> ⚠️ Modena 给所有 `:disabled` 控件加 `-fx-opacity: 0.4` —— 必须显式写 `-fx-opacity: 1`，
> 否则禁用的按钮会「整个消失」（实测踩过：数据页「保存」变一片空白）。

### 7.3 数据网格

| 元素 | 规格 |
|---|---|
| 列头 | 两行：上=列名（12 bold）+ 角标（排序 10 / 筛选漏斗），下=**类型行**（11px，如 `number · 自增`）；`4px 8px` 内边距 |
| 列头右键 | 排序升/降/清除 · 移到左/右/首/尾 · 筛选 · 隐藏 · 自适应 · **冻结到此列 / 取消冻结** · 复制列名。**做不到的事必须禁用**，不能点了给一个和菜单文字不符的结果（最后一列冻结 = 右边一列不剩 → 禁用；已冻的列「冻结到此列」禁用） |
| 自适应 | 双击列头 = 按内容自适应，与菜单项同一算法（不是两条实现） |
| 筛选漏斗 | **常态 opacity 0（仍占位）**，悬停列头浮现；**该列有筛选条件时常显**（状态不是装饰） |
| 行首槽 | 宽 **16px**，空白无字（N16 实测：帧 3/4/6 8–12× 复核是一条 ~12px 空槽，没有行号也没有常显铅笔）；只在草稿 / 脏行 / 待删行放 12px 状态图标（`＋` / 铅笔 / `×`）。批量入口 = 行多选 + 右键菜单 + 底部行操作条 |
| 正文 | 12px，`4px 8px`（dense `2px 6px`）；NULL 斜体灰、空串浅灰、枚举绿粗体、JSON 蓝等宽、数字等宽 |
| **空值三态** | ⭐ **`NULL` 显示 `(NULL)`**，不是 `NULL`。带括号的原因：TEXT 列里真的会出现**内容为 "NULL" 的字符串**（CSV 导入、日志表、外部系统写的），灰斜体的 `NULL` 和一条值为 NULL 的文本在 12px 网格里分不出来，而这两种情况要做的处理完全不同。空串显示 `""`，BLOB 显示大小（补 **GB 档**，不显示 `1536.0 MB`）。三者各有 tooltip |
| 斑马纹 | 奇数行 `bg-stripe`（亮 `#F9F9F9` / 暗 `rgba(148,163,184,.035)`）—— **网格三层里最轻的一层**，必须轻于选中 tint 和 hover。⚠️ 实现上条纹档**只许带 `:odd` 一个伪类**：多带一个 `:filled` 就比 `:hover` / `:selected` / `--deleted` / `--new` 重一档，会反过来把行态底色整条压掉，且**只有奇数行错**（第三十一轮实拍抓到，门禁 `SelectionStateTest.stateRulesOutweighTheZebraStripeInEveryTableScope`） |
| 网格线 | 横线 + 竖线都是 `border-subtle` 1px（Navicat 17 实测：列边界 `#F0F0F0`）。**宽度写在基础规则里，状态规则只改颜色**（见下方「槽位常留」） |
| 选中 | `bg-selected-grid`（Navicat 实测 `#C4E0F7`）+ 正文色字（不蓝字）+ **左侧 2px `accent` 强调条**；`hover` 规则排在 `selected` **之前**，悬停已选行仍是选中色 |
| 当前格（键盘光标） | ⭐ **不是 `.table-cell:focused`** —— 行选模式下 `TableCell.updateFocus()` 会主动清掉 `:focused`，那条伪类在这张表上永远不匹配。由 `DataGridPage` 盯 `focusModel` 打样式类 `adb-grid__cell--cursor`：**内描边 `border-focus` + `bg-active` 淡底**，只有左右两条边（行高 23px，上下再吃 4px 就把正文挤没）。光标**按列名**记，不按 `TableColumn` 实例记（冻结区的列是从右表搬过去的同一批实例）。⭐ N17 改判：规划里写的「当前格橙黄 `#FFB666`/`#FFFFB6`」是整帧散落的 ClearType 子像素边缘色（文字抗锯齿），**不是**格子填充；帧 3/6 里没有橙黄当前格，本轮不实现 |
| 当前列表头 | 光标所在列的表头**整格着蓝 tint** `bg-header-current`（Navicat 帧 6 实测 `#AED4F2`）。样式类挂在 `TableColumn` 上（`adb-grid__column--current`），`TableColumnHeader` 会把它复制到表头单元格；换列即摘旧类 |
| 行内编辑盒 | 编辑中的格子 = 白底 `bg-surface` + **2px `border-focus` 方框**（帧 6 实测 `#0078D7`），方角贴满整格，进入编辑即全选；行仍是选中态（整行色带）。输入框自己透明无框，边界由格子画 |
| 脏单元格 | 左侧 2px 黄条 + `warning-bg` |
| 新增行 / 删除行 | 绿底 / 红底（删除行文字降为 tertiary） |
| 行操作条 | 网格左下 `+ / − / 应用 / 取消`，**纯图标不加文字**（贴着光标，越短越不挡视线）。可用态**绑定**工具条同一批按钮，不重写判断；只读表**整排消失**（一排永远点不动的按钮只会引人反复试） |
| 数据工具条 | **13 具名键**（视觉顺序 = 折叠顺序）：`Table Profile▾` · `Begin Transaction` · `Cell Editor` · `Filter & Sort`（开关，按下态 = 面板开着）· `Columns` · `History` · `Fill with AI` · `Data Profiling` · `Import` · `Export` · `Data Generation` · `Data Masking` · `Create BI Workspace`。右端恒留 2 小键：视图 `≡▾`、溢出 `»`。放不下时从右往左把具名键折进 `»` 菜单（按 `prefWidth` 判，不靠 `minWidth`），`»` 可 Tab / Enter 到达。能力面未建的几颗（Fill with AI / Data Profiling / Data Generation / Data Masking / Create BI Workspace）点开是**如实说明页**，不是死按钮。⭐ R43-b：`Fill with AI` 原来是「填充此列 / 解释此表」两项下拉，两项开的是同一份说明 —— 一次点击能拿到的东西不该摆成两次点击，收成一颗。⭐ R43-c：`≡▾` 那颗**不许挂 `.adb-button--icon`** —— 它把宽度钉死 30px，而 MenuButton 的内容是「内边距 16 + 图标 14.5 + 箭头 16」，装不下时 ButtonSkin 把箭头排到图标**左边**并压住它，帧上就是一颗没有身份的裸 `▾`（§11 的骗人控件形态）。新立 `.adb-button--icon-menu`：内边距照紧，宽度交给内容，label 那两层 8px 一并归零（那是文字键才需要的字-箭头间距）。属性断言抓不到这个 —— 钉宽与「有图标有箭头」各自都成立，所以门禁 `menuKeysNeverSqueezeTheirArrowOntopOfTheIcon` 量**几何** |
| 底栏 | 一行：左 = 行操作条；右 = `首 / 上 / [页码框] / 下 / 末` + 齿轮 `▾`（每页行数 100/200/500/1000 + 跳至记录）+ 放大镜 + 网格/表单切换。**没有行数 / 页大小 / 「跳至页」文字控件**，记录计数只在状态栏。页码框兼作跳页输入；窄宽降级按可**见**宽度（场景坐标 − 父容器右内边距 − 覆盖底栏的详情浮层）从右往左收 `表单 → 网格 → 放大镜`，五键 / 页码框 / 齿轮永不收 |
| **Filter & Sort 面板（N31）** | 工具条 `Filter & Sort` 按钮**按下态**打开，内嵌在工具条与网格之间（把网格往下推，不是弹窗/浮层）。面板 = 标题行（`Filter` + 升降序快捷键 + `Builder / Text` 模式切换）+ 条件行列表（每行：启用勾选 · 字段链接 · 操作符 ▾ · 值 / `<?>` 占位 · `and` 连接词 · 删除）+ `[+]` 加条件 · `[Ω]` 加组 + `Sort By` 芯片（字段 ▾ · 升降序 · 删除）+ 底部 `Apply Filter & Sort` 主按钮与 `Criteria Edited` 脏标记。**显式应用**，不是即输即查；未点 Apply 不动查询。Text 模式是**只读**的生成表达式（真要做自由 SQL 请去查询页）。`[Ω]`（OR 组）本版本不给真功能 —— 点开如实说明页；`IN` 条件无法用扁平 AND 生成器表达，保留列头漏斗按候选值筛选，应用面板时原样保留。列名变（表结构变化）时面板清空重建，避免拿旧列名拼 SQL |
| 编辑器 | 格内单行输入 = 上面「行内编辑盒」（N17：白底 + 2px `border-focus` 贴格框，方角；出错只换 `error-bg` 底色）。⭐ 长文本 / JSON / CLOB 走**详情抽屉文本页就地编辑**（不是弹窗）：回填**原始值**、**Ctrl+Enter 应用**（多行编辑器里回车是换行，不能拿来提交）、**Esc 只放弃本次编辑不关抽屉**、**切页签同样放弃编辑**（提交目标在进编辑态时钉死，绝不随当前页变 —— 否则「文本页敲字 → 切 JSON 页 → 应用」会把美化过的 JSON 整段写进 TEXT 列）、写进**同一个 `EditBuffer`**；主键列与只读表不出现编辑器 |

> ⭐ **槽位常留（UX-4 的排版不变量）**：行 `border-width: 0 0 1 2`、单元格 `0 1 0 2` ——
> 左侧 2px、右侧 1px 由**基础规则**留成透明空槽，选中条 / 脏值条 / 光标环 / 矩形选区
> 这些状态规则**只上色、不改宽度**。改宽度就会抖：键盘走一格、文字左右跳一次；
> 行高 23px 里上下各吃 2px 直接压掉一行正文。
> 门禁：`SelectionStateTest.selectingARowNeverMovesItsCells`（实测每个单元格的 x/y 与行高都不变）。


### 7.4 对象树

| 元素 | 规格 |
|---|---|
| 行高 | **20px**（`row-tree`，R37 N11 对齐 Navicat 实测；dense 19） |
| 层级 | **连接 → 库 → 分组**（R37 N32）：MySQL / MariaDB、PostgreSQL、SQL Server 一条连接绑一个库，切库 = 换连接；Oracle 一条连接一个服务、SQLite 单文件 —— 两者平铺到「连接 → 分组」 |
| 当前库 | 库节点加粗（`adb-tree-cell--current`）；双击 / Enter 切到该库，对象清单与查询跟着换会话 |
| 分组 | 库下四组 `Tables / Views / Functions（含过程）/ Queries`；**没有计数徽标**（R37 N12） |
| 选中 | **满行高亮**（`bg-selected`），不是圆角胶囊 |
| 类型色 | 图标着色（`kind-*`），文字保持正文色 |
| 收藏 | 11px 黄星（`risk-1-write`） |
| 折叠 | rail 32×32 图标按钮 |

### 7.5 标签栏

- 常驻「对象」标签：永远排第一、**没有关闭钮**（不是隐藏，是不放进场景图）、右键菜单无「关闭」。
- 普通标签：类型图标 + 标题（12px）+ 脏点（10px 黄）+ 关闭钮（10px，hover 显现）。
- 重名标签的全名格式（tooltip；R37 N7-b）：`<对象> @<库> (<连接>)`。没有库名（SQLite 单文件）时退回 `@<连接>`。
- 活动标签：底部 2px `accent` 边 + 标题转 `text-primary`。
- 中键关闭；关闭脏标签要确认。

### 7.6 状态栏（按页型换段，N25）

> ⭐ N25：底栏不再是「所有段常驻」的 10 段，而是 **三种页型三套段位** ——
> 分段节点是同一批实例，只改挂载位置；换页型不丢各段自己的隐藏 / 占位状态。
> 「事务 · 只读」整段删除：它是会话设置，不是当前页面的状态。

| 页型 | 左 | 中 | 右 |
|---|---|---|---|
| **查询页** | 连接灯 · 当前语句 · 回执（脏计数） | 选中 · 耗时 | 行数 · 最后刷新 |
| **数据页** | 当前语句 · 回执（脏计数） | 选中 | 行数 · 最后刷新 |
| **对象页**（对象清单 / 结构 / 工具 / 设置 / 已保存查询） | 计数 / 选中 · 回执 | — | 连接灯 |

| 段 | 内容 | 措辞规则 |
|---|---|---|
| 连接灯 | `方言 · 连接`（+ 库名；多库显示 `连接 · 库`） | 矢量状态点：连上绿 / 未连灰；服务器版本与用户进 tooltip |
| 当前语句 | 压平一行，宽度封顶 240px + ELLIPSIS | 点击复制**原文**；没有语句整段隐藏 |
| 消息 | 操作回执（复制成功 / 保存失败 / 结果截断） | 没话说就整段隐藏，不常驻「就绪」 |
| 脏计数 | `● 3 待提交` | 有脏才出现 |
| 选中 | `已选 12 行` / `已选 3 个项目` | 没有选中整段隐藏 |
| 耗时 | `12 ms` | 只在查询页出现 |
| 行范围 | `1-200 / 共 500` | **只说范围，不带耗时** |
| 最后刷新 | `刷新于 5 分钟前` | 常驻右侧；没取过数整段隐藏；自重算 30s |

> ⭐ N20：数据页底栏不再摆记录数 / 页大小 / 「跳至页」文字控件 —— 行范围**只在这里**呈现，
> 页大小与跳至记录收进底栏齿轮菜单。数据页也不显示语句耗时（N25 口径）。
> 版本号不占状态栏一格（属于「关于」）。

### 7.7 反馈

| 载体 | 用途 | 规则 |
|---|---|---|
| **Toast** | 操作成功 / 失败 / 可撤销 | 三档时长：普通 1.8s · 带撤销 6s · 结果类 4s（`dur-toast*`） |
| **状态栏** | 持续状态（连接、范围、刷新时间） | 不用 Toast 承载状态 |
| **内联错误** | 表单校验 | 字段旁红字，常驻 |
| **弹窗** | 阻断决策 | 见 §8 |

### 7.8 空态（`EmptyState` 组件）

```
[32px 图标]
[标题 —— 说"这是什么状态"]
[说明 —— 说"为什么会这样 / 接下来能怎样"，460px 内换行]
[主按钮 —— 动词短语]
[可选提示 —— 快捷键等]
```

**新空态一律用五参版**（带「下一步」按钮）；三参版只在确实无处可去时用。
已知 9 处：E1/E2/E3/E4/E6 已修；**E5（查询 0 行）/ E7（侧栏搜不到）/ E8（历史为空）/ E9（收藏为空）待补**。

### 7.9 对象清单页（常驻「对象」标签 = 表清单）

| 元素 | 规格 |
|---|---|
| 列 | 名称 / 行 / 数据长度 / 引擎 / 修改日期 / 注释（六列固定，取不到值的格显示「—」，不按数据整列隐藏） |
| 工具条 | 打开表 / 设计表 / 新建表 / 用 AI 新建表 / 删除表 / 导入向导 / 导出向导（带文字与图标，打开弹窗的键按惯例带省略号）＋ 表名过滤框 ＋ 列表 / 网格切换。**没有「刷新」键**（Navicat 的对象工具条也没有；刷新在右键菜单 / 菜单栏），也没有「N 张表」计数 |
| 行高 | 19 / 20 / **21** / 26（跟随密度令牌，默认标准 = Navicat 21） |
| 表体 | 12px；**纯白行、无横线、无斑马纹**（Navicat 17 实测）；列分隔由表头竖线承担 |
| 表头 | 12px bold，`text-secondary`，`bg-surface`；列间 1px `border-subtle` 竖线 |
| 空行 | **不画线、不铺色**（避免看起来像还有数据） |
| 数字列 | 右对齐 + **按数值排序**（不按字符串） |
| 缺失元数据 | 显示「—」，不编造 SQLite 拿不到的值 |
| 交互 | 双击 / Enter 打开；搜索实时过滤；刷新在后台线程；右键菜单与工具条共用同一组动作 |

> 实拍对照见 `docs/screenshots/p*.png`（由 `scripts/shots.sh` 按当前代码重拍）；像素测量的原始台账属内部材料，不随公开仓库发布。

---

## 8. 弹窗规范

### 8.1 形态判定

见 `docs/UI-FORMS-AND-STATES.md` §2：**改已存在的东西 → 弹窗**；
**要长期停留 / 并排比较 / 有状态 → 标签页**；**不确定 → 弹窗**。

### 8.2 骨架（三段式）

```
┌─ 系统标题栏（OS 绘制，标题含上下文：新建索引 · 表 users · 方言 · 库）──┐
├─ 表单区（bg-canvas）──────────────────────────────────────────────────┤
│  字段 / 表格 / 预览                                                   │
│  （常驻错误行）                                                        │
├─ 按钮区（bg-surface + 上边框）───────────────────────────────────────┤
│                                          [取消]  [确定]                │
└────────────────────────────────────────────────────────────────────────┘
```

⭐ **N26（2026-11-01）**：内容区<b>不再自绘抬头条</b>。`.adb-dialog__header / __title /
__context / __close` 已从 CSS 与代码里删掉 —— 标题与上下文（表名 / 方言 / 库）拼进
`Dialog#setTitle`，由系统标题栏承载；关窗用系统标题栏的 × 与 Esc。
绝对路径按中部截断（`DialogStyles.shortenContext`），文件名必须留住。

⚠️ 随之取消<b>弹窗尺寸记忆</b>（拍板 4）：用户把编辑连接拉到 900 高，不该让下一次
新建连接也撑成 900 的空白。每次打开都从档位默认值开始。

对应 CSS：`.adb-dialog > .content` / `.adb-dialog > .button-bar > .container`。

### 8.3 按钮区规则

1. **顺序**：JavaFX 在 Windows 上的默认序把主操作（OK_DONE）排在**左**、取消排第二，
   和 Navicat 的桌面习惯相反。所有弹窗都走 `DialogStyles.apply`，由它把序串里的
   `O` 与 `C` 换位 —— **取消在左、主动作在最右并贴右缘**，全应用一个口径。
2. **默认按钮**：`.adb-dialog .button:default` 是**实心强调色** —— 不用读字就能认出「确定」。
3. **破坏性弹窗**必须走 `DestructiveConfirm`：
   - 破坏性按钮 `ButtonData.OTHER`（**不是** `OK_DONE`）；
   - 取消按钮 `CANCEL_CLOSE`，在 `onShown` 里被钉成 default；
   - **回车 = 取消，不是删除**（早期用 `OK_DONE` 时，回车直接删连接，是键盘上可复现的数据丢失路径）。
4. **导航按钮不放按钮栏**：`DialogPane.createButton` 会给按钮栏里的每个按钮挂
   `setResultAndClose`，放进「下一步」会让整个向导当场关闭。
   → 导航按钮放**内容区**（`ConnectionWizard` 已这样改）。
5. 宽度档：表单 **480** · 长文本 **560** · 宽表（表格 / 列选择） **720** · 建表 **900**
   （`DialogSizes` 四档，V-11。原第五档「双列选择 **800**」随 U21 新建索引改单列表一并删除 ——
   宽度前提是双列传输框，前提没了就是死规格）。

### 8.4 校验与错误

- 校验失败：**不关对话框**，错误写在**常驻错误行**（每一步都要有，不是最后一步才有）。
- 错误必须**双通道**：红字 + 文案说明「哪里错、怎么改」。
- 危险弹窗的正文要写清**后果**：「表和表里的数据都会被删除，且无法撤销」。

### 8.5 主题与 owner

```java
DialogStyles.apply(dialog, owner);   // 样式表 + 主题类 + adb-dialog + 屏幕限高 + Navicat 按钮序
dialog.initOwner(window);             // 没有 owner 会跑到屏幕中心、可能被主窗盖住
```

> ⭐ **为什么必须有 `DialogStyles`**：JavaFX 的 looked-up color（`-adb-*`）定义在
> `.theme-light` / `.theme-dark` **类选择器**上，而主题类挂在**主场景根节点**上。
> 弹窗有自己的 Scene 与根节点（DialogPane），那个根节点上没有主题类 ——
> 于是弹窗里所有 `-adb-*` 查找全部落空，弹窗退回 JavaFX 默认皮肤：
> 没有边框、没有标题分隔、按钮全是默认灰钮（实测截图）。
>
> `DialogStyles.apply` 做四件事：
> 1. 把 owner 场景的样式表挂到 DialogPane；
> 2. 把主题类（`theme-light` / `theme-dark`）**抄**到 DialogPane 的 style class；
> 3. 按屏幕高度限高（弹窗比屏幕还高 → 底部按钮在屏幕外，用户点不到）；
> 4. 把按钮序串按 Navicat 口径换位（取消在左、主动作在右）。
>
> ⛔ 不许再手写 `getDialogPane().getStylesheets().addAll(...)` —— 那样只解决第 1 步。

### 8.6 弹窗清单

| 弹窗 | 触发 | 形态 | 默认按钮 |
|---|---|---|---|
| 新建连接向导（两页） | 功能栏 / 侧栏 / ⌘N | 弹窗（内容区导航）· 900×700 | 保存（校验不过 consume） |
| ↳ 类型页（N27-a → R43-b） | 向导第 1 步 | **一行 6 张彩色磁贴**（只摆真有驱动的六家，块在页面中部居中）+ 一行「其它数据库走兼容协议 / URI」的提示 + URI 入口。⭐ 原来照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，其中 16 张点进去是「本版不支持」—— 那不是它的形态，它摆这些家是因为真连得上去；我们摆出来只是让用户多点十六次空。六张一屏放完，复选栏、搜索框、滚动容器与「无驱动说明页」一起删 | 下一步 |
| ↳ 常规页（N27-b） | 向导第 2 步 | `AIDB ⟷ Database` 示意图 + 右对齐标签 + 七页签；底部五键（测试连接 / URI… / 上一步 / 取消 / 确定） | 确定 |
| 新建表（T1） | 树右键 / 数据库菜单 / ⌘K | 弹窗 + **七页设计器**（字段 / 索引 / 外键 / 触发器 / 选项 / 注释 / SQL 预览），字段与三类对象同一计划。⭐ R43-c：字段页三段（工具条 / 网格 / 属性面板）**作为整体在内容区垂直居中**，网格不再吃掉多余高度 —— 弹窗高度是为七页签里最挤的那几页定的（页签之间不许跳），四列时那条带子有 130px 没主，全落在「网格最后一行与属性面板之间」，看着像还有几行没填（正是 `fitTableHeight` 要治的毛病）。留白回两端，工具条仍贴着自己的网格 | 创建（取消为默认） |
| 新建视图（V1）/ 设计视图（V2） | 功能区 `View` 格 / 树右键 / 数据库菜单 | 弹窗 · **720 宽档**（`DialogSizes.WIDE`，**高度随内容**：查询体 184 + 预览 160，不写死 —— 写死 560 会在提示行下面留一条一百多像素的死空白）：**三格各有标题**（视图名 / 查询（SELECT）/ 将要执行的 SQL），两格编辑器挨着而没有标题时实拍里根本分不清谁是输入谁是输出；重建模式下名字格**置灰**（本版本无视图重命名）并挂 tooltip 说明替代路径；方言没有 `CREATE OR REPLACE` 时预览显出**两句**并给一句提示 | 创建 / 更新（校验不过 consume，弹窗留在原地） |
| 删除视图（V3） | 视图节点右键 / 菜单 | 弹窗 + SQL 预览（`DROP VIEW`） | **取消**（破坏性） |
| 新建索引（S1） | 结构页索引页签 | 弹窗 + 实时 SQL 预览 | 创建 |
| 清空 / 重命名 / 删除表（T2/T3/T4） | 树右键 / 菜单 | 弹窗 + SQL 预览 | **取消**（破坏性） |
| 保存修改（SQL 预览） | 数据页 ⌘S | 弹窗 | **取消** |
| 添加筛选条件 | 数据页 | 弹窗 | 应用 |
| 变量设置 | 查询页 | 弹窗 | 应用 |
| 导入 / 导出向导 | 侧栏 / 工具 | 弹窗 | 执行 |
| 执行危险 SQL 确认 | 查询页 | 弹窗 | **取消** |
| 删除连接 | 连接页 | 弹窗 | **取消** |
| 破坏性确认（通用） | 多处 | 弹窗 | **取消** |
| 单元格详情 | 单元格右键 | **抽屉（非模态）** | — |
| 快捷键 / 关于 | 帮助 | 弹窗 | — |

### 8.7 现状缺口

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| ~~**D-1**~~ | ✅ **已落地（P2-2）** | ~~没有统一的「错误详情」弹窗~~ → `ErrorDetailsDialog`：分类徽标 + 原因 / 建议 / 驱动原文 / 异常链 / SQL + 一键复制 |
| ~~**D-2**~~ | ✅ **已落地（P2-2）** | ~~没有批量汇总页~~ → `BatchSummaryDialog`：成功数 / 失败数（**与明细行数分离**）+ 失败明细 + 一键复制；已接批量删表与导入报告 |
| ~~**D-3**~~ | ✅ **已落地（V-12 + P2-2 接到界面）** | ~~没有错误归类~~ → `ErrorClassifier` 8 类 + 建议动作，详情弹窗显示分类徽标 |
| **D-4** | 弹窗宽度没有档位规范 | 各弹窗宽度随手写 | 按 §8.3 的五档统一 |

---

## 9. 交互状态矩阵

| 组件 | 默认 | hover | 按下 | **focus** | 禁用 | 选中 | 加载中 |
|---|---|---|---|---|---|---|---|
| 按钮 | elevated + 描边 | `bg-hover` | `bg-active` | 边框换 `border-focus` + 3–4px 光晕 | `bg-disabled` + `text-disabled` + **opacity 1** | — | spinner 内嵌 + 禁用 + **宽度不跳** |
| 图标按钮 | 透明 | `bg-hover` + 图标变 `text-primary` | — | 同按钮 | 同上 | `.adb-icon-button--active`（**当前无样式**） | — |
| 树节点 | 透明 | `bg-hover` | — | 边框 + 光晕 | — | 满行 `bg-selected` + 正文色字 | — |
| 表格行 | canvas / 斑马 | `bg-hover` | — | 边框 + 光晕 | — | `bg-selected` | — |
| 输入框 | `bg-inset` + `border-subtle` | — | — | 描边换 `border-focus`（不改宽度） | `bg-disabled`（描边转透明） | — | — |
| 标签 | 透明 | `bg-hover` | — | 边框 + 光晕 | — | 底部 2px accent 边 | — |
| chip | elevated + 描边 | `bg-hover` | — | 边框 + 光晕 | — | `--active`：accent 边 + accent 字 | — |
| 菜单项 | 透明 | `bg-hover` | — | `bg-active` | `text-disabled` | — | — |
| 滚动条 / 滑块 | — | — | — | ✅ 有 focus 规则 | — | — | — |

**统一规则**：

- 焦点环 = **换边框颜色 + 光晕**，**绝不改边框宽度**（宽度一变，获得焦点时控件抖 1px）。
- 禁用 = **颜色表达**，**不用 opacity**（opacity 会同时稀释文字与图标）。
- 加载中 = 内嵌 spinner + 禁用 + **保持原宽度**（否则整行按钮会左右跳）。

---

## 10. 键盘与快捷键

### 10.1 全局

| 快捷键 | 动作 |
|---|---|
| `⌘K` | 命令面板 |
| `⌘N` | 新建连接 |
| `⇧⌘N` | 新建查询 |
| `⌘O` | 打开 SQL 文件 |
| `⌘F` | 搜索对象（侧栏） |
| `⌘Q` | 退出 |
| `F5` | 刷新 |
| `F1` | 快捷键速查 |

### 10.2 数据页

`⌘Z` 撤销 · `⇧⌘Z` 重做 · `⌘S` 保存 · `⌘F` 本页搜索 · `⌘C` 复制值 ·
`⌥⌘C` 复制 TSV · `⇧⌘C` 复制 INSERT · `⌘A` 全选当前页 · `⌘↓`/`⌘↑` 跳本页首末行 · `⌘.` 取消

### 10.3 查询页

`⌘R` 执行当前语句 · `⇧⌘R` 执行全部 · `⌘↵`/`⇧⌘↵` 同效 · `⌘.` 取消 · `⌘E` 执行计划 ·
`⌥⇧F` 格式化 · `⌘/` 注释 · `⌘S` 保存 · `⌘F` 查找 · `⌘G` 下一个 · `⌃H` 替换

### 10.4 规则

- 文本输入框聚焦时**不抢** `⌘Z` / `⌘F`。
- 所有弹窗：`Esc` 关闭（破坏性弹窗的 Esc = 取消）；Tab 顺序按视觉顺序（左→右、上→下）。
- **焦点顺序**：菜单 → 功能栏 → 侧栏 → 标签栏 → 内容区 → 状态栏（状态栏不可聚焦）。
  ⭐ **第二十六轮起这条有门禁**：`AccessibilityAuditTest` 按场景图**前序**走出六区顺序，
  并要求**状态栏没有 tab 停靠点**、每个区域至少一个 —— 规格书里躺了四轮没人守的规矩，从此会红。
- **标签栏与对象切面必须键盘可操作**：`←/→` 在标签间移动焦点**并同步激活**、`Enter`/`Space` 激活；
  两者都是 `AccessibleRole.TAB_ITEM` 且带 `accessibleText`（重名标签带连接限定，与可见标题同一口径）。
  以前它们是**纯鼠标**的 `Label` —— 键盘用户切不了标签，读屏只听到一段孤立文字。
- **可朗读名称逐个控件给**（`setAccessibleText`）：数据网格 / 冻结网格 / 全选 / 每行复选框 /
  布尔单元 / 每页下拉 / 侧栏树 / 对象清单 / 卡片（`LIST_ITEM`）。
  ⚠️ `ComboBox` 皮肤内部的那个 `ListView`（`ComboBoxListViewSkin`）**不单独要求**名称 ——
  名称挂在 `ComboBox` 自己身上，重复要求是假缺陷。
- 图标控件：Tooltip（鼠标）+ accessibleText（读屏）两者缺一不可（`IconAuditTest`）。

---

## 11. 文案与措辞

| 场景 | ✅ 正确 | ❌ 错误 |
|---|---|---|
| 按钮 | 动词短语：「新建连接」「导出选中」 | 「点击这里」「确定?」 |
| 空态标题 | 说状态：「这张表还没有数据」 | 黑话：「暂无数据」 |
| 空态说明 | 说原因 + 下一步 | 「无」 |
| 行范围 | 「1-200 / 共 500」 | 「共 200 行」（= 本页行数，与总数矛盾） |
| 耗时 | 只出现在「耗时」字段 | range 里也带 `ms`（重复） |
| 不可用 | 说明原因 + 替代路径 | 「暂不支持」（只告知，不给路） |
| 危险确认 | 写后果：「无法撤销」 | 只写「确定吗？」 |

**术语表**：对象（Object）· 清单（表/视图的列表页）· 结构（表的设计页）· 脏行（有未保存修改的行）·
本会话（连接）· 条件（筛选条件）· 片段（Code Snippet，插进编辑器的一段 SQL）· 已保存查询（Saved Query，独立成页的 SQL 文件）。

---

## 12. 密度与主题

### 12.1 密度（四档，全局 5 处生效）

| 档 | 列表/树行高 | 记录网格行高 | cell padding | 适用 |
|---|---|---|---|---|
| 密集 | 19px | 21px | 网格/清单 `2 6`，对象清单 `0 6` | 与 Navicat 并排对标、大表扫读 |
| 紧凑 | 20px | 22px | `2 8`（对象清单 `4 8`） | 笔记本 / 日常 |
| **标准（默认）** | **21px** | **23px** | `2 8`（对象清单 `4 8`） | Navicat 同档（实测 21 / 23） |
| 宽松 | 26px | 28px | `2 8`（对象清单 `4 8`） | 演示 / 触屏 |

> ⚠️ 固定行高下 **12px 正文 + 上下 padding 必须 ≤ 行高**，否则字会被裁掉下半截
> （第二十九轮重拍实拍抓到网格 / 结构页 / 连接页 / 表清单四处的裁字）。门禁：
> `AppCssAuditTest.fixedRowCellsLeaveRoomForTheirText`。

覆盖：数据网格 · 查询结果网格 · 表清单 · 结构页四张表 · 对象树
（`DensityCoverageTest` 四档 × 各页面，`ChromeMetricsTest` 钉 chrome 高度与可见行数）。

### 12.2 主题

- 双主题 + 跟随系统 + 本地记忆（`java.util.prefs`）。
- **实现方式是类选择器**（`.theme-dark` / `.theme-light`），**不是换样式表列表** ——
  换列表会让 JavaFX 重算整棵场景图，随后的 `scene.snapshot()` 会拿到**空白帧**（实测踩过）。
- 切换主题后图标/颜色自动跟随（图标颜色走 CSS）。

---

## 13. 现状缺口清单（可直接进 TODO）

> 本轮（2026-10-22）用**源码 + 样式审计**实测得出。每条都有明确落点与验收方式。

### 13.1 🔴 样式类缺口（43 个：Java 里用了但 CSS 没定义）

实测脚本：扫描 `ui/adb-ui-shell/src/main/java/**/*.java` 里出现的 `"adb-*"` 字符串，
与 `app.css` 里的选择器求差集。**结果 43 个**。

| 页面 / 组件 | 缺失的类 | 后果 | 优先级 |
|---|---|---|---|
| **结构页** | `adb-structure` `adb-structure__table` `adb-structure__tabs` `adb-structure__overview` `adb-structure__ddl` `adb-structure__loading` | **结构页四张表完全没有样式**，是全应用唯一「没上样式」的主页面 | 🔴 P1 |
| **连接页** | `adb-connections` `adb-connections__table` `adb-connections__favorites` `adb-connections__search` | 连接表用 Modena 默认外观，与其它表格不一致 | 🔴 P1 |
| **向导 / 对话框（5 处）** | `adb-wizard__content`（导入/导出/收藏/传输/变量；R39 起「已保存查询」改为工作区对象页，不再用此类） | 内容区无统一留白与底色 | 🟡 P2 |
| **表单错误** | `adb-error` | **校验错误文字不是红色** —— ✅ 本轮已补 `.adb-error`（R-3）；`adb-form` / `adb-form__label` 仍缺 | 🔴 P1 |
| 详情抽屉 | `adb-details__meta` | 元数据区无样式 | 🟢 P3 |
| 状态栏分段 | `adb-status__connection` `__range` `__selection` `__duration` `__transaction` | 分段无独立样式（靠继承，问题不大） | 🟢 P3 |
| 数据页 | `adb-grid__jump` `adb-grid__number-column` | 跳页输入框/数字列无专属样式 | 🟡 P2 |
| 侧栏 | `adb-sidebar__bottom` `adb-sidebar__connection` `adb-tree-cell--object` `adb-icon-button--active` | **图标按钮的「激活态」没有样式**（例如筛选激活） | 🟡 P2 |
| 空态 | `adb-empty__hint` | 空态提示行无样式 | 🟢 P3 |
| 其它 | `adb-body` `adb-db` `adb-main` `adb-screenshot` `adb-history__list` `adb-form` | 命名类，无样式需求 | 🟢 P3 |

**建议门禁（新增）**：`StyleClassCoverageTest` —— 「Java 里用到的 `adb-*` 类必须在 `app.css` 里有规则，
否则失败」；白名单只允许 `adb-screenshot` 这类一次性辅助类。

### 13.2 🟡 视觉与交互缺口

| # | 缺口 | 影响 | 落点 |
|---|---|---|---|
| **V-1** | ~~数字列不右对齐~~ | — | ✅ **已修**：数据页数字/日期 + 对象清单 + 行号列全部右对齐 |
| **V-2** | ~~缺 `overlay` / `shadow-1` / `shadow-2` 令牌~~ | — | ✅ **已补**（90 个令牌）+ 遮罩与投影用上 |
| **V-3** | ~~状态栏版本号硬编码 `v0.1.0 · P3`~~ | — | ✅ **已修**：Maven 资源过滤 → `AppVersion`，「关于」共用 |
| **V-4** | ~~加载中按钮没有 spinner / 宽度会跳~~ | — | ✅ **已修**：`IconButtons.loading()`（固定宽度） |
| **V-5** | ~~工具栏未做「最多 7 个常显 + 更多菜单」~~ | 窄屏拥挤 | ✅ **已按另一条路解决**（UX-3）：语义分组 + 竖线 + **<1100px 自动收字**（提示不丢），不做「更多」菜单 —— 把主操作藏进二级菜单比图标更难发现 |
| **V-6** | 应用图标缺失 | 无品牌标识 | 32×32 图标 + 窗口图标 |
| **V-7** | ~~缺 20px 图标档；图标尺寸三档不统一~~ | — | ✅ **已修**：八个尺寸常量（8 / 12 / 14 / 16 / 20 / 26 / 32 / 44），调用点全部改引用；44 是 N27 的磁贴与示意图档 |
| **V-8** | ~~对比度无自动检查~~ | — | ✅ **已修**：`ContrastTest` + 改 6 个色值 |
| **V-9** | 表格/抽屉/结果区规格不一致（类型行、记录导航条） | 同一份数据两种长相 | 统一网格外壳组件 |
| **V-10** | ~~空态 E5/E7/E8/E9 未补~~ | — | ✅ **已修**：4 处全补 + `EmptyStateTest` 门禁 |
| **V-11** | 弹窗宽度无档位 | 各弹窗宽度随手写 | §8.3 五档 |
| **V-12** | ~~无错误详情弹窗 / 批量汇总页 / 错误归类~~ | 错误信息不完整 | ✅ **已修**（V-12 归类 + P2-2 界面）：`ErrorDetailsDialog` / `BatchSummaryDialog`，见 §8.7 |

### 13.3 ✅ 本轮已顺手修掉的

| # | 修法 | 门禁 |
|---|---|---|
| **R-1** | `row-tree` 令牌被无视（`tree.setFixedCellSize(22)` 写死）→ 改用 `Tokens.ROW_TREE_LIGHT` | `TokenParityTest.everyTokenIsActuallyUsed` |
| **R-2** | `kind-index` 令牌是摆设 → 补 `INDEX` 钥匙图标 + 结构页索引行首图标 + CSS 规则 | 同上 + `IconSystemAuditTest.everyIconIsActuallyUsed` |
| **R-3** | 表单错误文字不显红 → 补 `.adb-error` | 视觉验收（截图） |
| **R-4** | **死样式**：对象首页从 chip 改成表格后，`.adb-object-home__group/__groups/__scroll` 三条规则失效 → 删除 | `StyleClassCoverageTest.cssDoesNotDefineClassesNobodyUses` |
| **R-5** | `.adb-menu-item` 是死规则（没人给菜单项加这个类）→ `ActionRegistry.menuItem` 统一加上，禁用态透明度还原规则**真正生效** | 同上 |
| **R-6** | `.adb-toolbar__separator` 是死规则 → 工具栏三组之间的分隔线改用该类（1px 竖线，不用 JavaFX 默认那条） | 同上 |
| **R-7** | 对象清单的「行数 / 数据长度」用**内联样式**右对齐（逃过 CSS 审计）→ 改用已有的 `.adb-object-list__number` 样式类 | 同上 |
| **R-8** | **新增门禁 `StyleClassCoverageTest`**：① Java 用到的 `adb-*` 类必须在 CSS 有规则（已知 43 个进白名单）；② CSS 里没人用的类是死样式；③ 白名单里已经修好的要删掉 | 新门禁 4 例 |

> ⭐ **R-8 的门禁价值**：它把「样式写了没人用」「类加了没人样式」这两类洞**变成可回归的**。
> 白名单只允许变短 —— 每补一条样式就删一条白名单，洞只会越来越少。
>
> ⭐ **本轮收尾**：43 个已知缺口 **全部补齐（R-9）**，白名单**清空** ——
> 现在门禁是「一个都不许缺」。顺带修掉的还有：`.adb-grid__number-column`
> （挂在 `TableColumn` 上的**无效样式类**）、状态栏重复耗时（R-11）、
> 连接表「不适用」显示 `—`（R-12）、数字/日期右对齐（R-13）、图标尺寸档（R-14）、
> 加载态按钮（R-15）、4 处空态（R-16）、对比度门禁与 6 个色值（R-17）。

---

## 14. 门禁

| 门禁 | 断言 |
|---|---|
| `TokenParityTest` | 令牌键一致 / 亮暗一致 / CSS 无颜色字面量 / **每个令牌都被用到**（不许摆设） |
| `AppCssAuditTest` | CSS 内无字面量字号·padding·radius；字号 **3 档（11/12/13）**、圆角 3 档、间距 `0/2/4/6/8/12/16/24/32`；`9px` / `10px` 0 次；数据面正文必须 12px；**固定行高下 12px 正文 + 上下 padding 不得超行高** |
| `FocusRingAuditTest` | 每个可聚焦组件类都有 `:focused`；`-fx-opacity` 只 1 处；禁用态抵消 Modena 透明度 |
| **`AccessibilityAuditTest`**（✅ 第二十六轮 P7-2 新增） | ① 逐页（首页 / 数据页 / 查询页）遍历**可见交互控件**，每个都要有可朗读名称或自身文字 ② 标签与对象切面必须是 `TAB_ITEM` ③ **六区焦点顺序**（前序：菜单→功能栏→侧栏→标签栏→内容区→状态栏；状态栏**零**停靠点，每区 ≥1）④ 标签栏 `Enter` + `→` 键盘可用 ⑤ 切面标签键盘可用 |
| **`ThemeManagerTest`**（✅ 第二十六轮 P7-1 新增） | 主题切换只在**窗口已显示**时做强制 CSS/布局 pass（未显示时交给 `stage.show()`，别把整棵场景图算两遍）；两套主题 + 业务 CSS 常驻样式表；根节点必须带 `theme-light`/`theme-dark` 类 |
| `DensityCoverageTest` | 密度四档同时改数据网格 / 查询结果 / 清单 / 结构页的行高；未知档回落 compact |
| **`ChromeMetricsTest`** | 标签栏 25 / 对象头 40 / 页工具条 30 / 分页器 38 / 状态栏 23；dense 网格行高 21；1268×765 可见 ≥22 行 |
| **`ToolbarDiscoverabilityTest`**（✅ UX-3 新增） | 跑**整个 App**：六颗主操作有图标+文字+tooltip+accessibleText；窄窗口收字但提示不丢；组间有竖线且删除标 danger；宽窗口工具条不把搜索框挤出可视区；导入向导预选当前表 |
| `IconAuditTest` | 图标控件同时有 Tooltip + accessible text；符号语义唯一 |
| `IconSystemAuditTest` | 按钮无 Unicode 图标/中文词图标；`Icons` 无死常量；领域层不带图标 |
| `MenuBarFactoryTest` | 菜单项全部来自 `ActionRegistry`，无永久禁用死项 |
| `DisabledButtonVisibilityTest` | 禁用按钮文字可见、透明度为 1 |
| **`StyleClassCoverageTest`**（✅ 本轮新增，**白名单已清空**） | ① Java 用到的 `adb-*` 类在 CSS 都有规则 ② CSS 里的死样式 ③ 白名单不得留已修条目 |
| **`ContrastTest`**（✅ 本轮新增） | 正文/次要/三级/风险/类型色与各自底色的对比度 ≥ 4.5:1；焦点环与背景 ≥ 3:1 |
| **`StatusBarTest`**（✅ 本轮新增） | 版本号来自 `AppVersion` 且无阶段代号；`range` 不带耗时 |
| **`EmptyStateTest`**（✅ 本轮新增） | 空态有图标+标题+说明+下一步按钮；E8/E9 真的渲染出来 |

---

## 15. 变更记录

| 日期 | 轮次 | 内容 |
|---|---|---|
| 2026-11-02 | 第四十七轮 · R47-b（时间线在 R47-a 之后；G5 的**画面**半边，SSH 半边 G5a 仍未开工） | **47 张帧全部拍在演示库（SQLite）上，而这颗产品有几样形状只有真服务器才有 —— 这一轮把截图通道绑上了 Docker 矩阵，四张真库帧落地，其中一帧把外壳的一个谎抓了出来**。通道形状：`ADB_MATRIX=<方言 id>` → `AppShell.registerMatrixProfileFromEnv()` 按 `ADB_TEST_{HOST,PORT,DB,USER,PASSWORD}` 在**进程内**注册一把 `matrix-<engine>` 临时档案；两条红线是这轮定的 —— ① 口令只进 `ephemeralPassword` 字段、只由新的 `passwordFor()` 交出，**绝不**进凭据存储（macOS 那一个是钥匙串，一次截图不该往里塞真容器口令）；② 档案不落 `connections.json`（`ConnectionStore.defaultStore()` 新增读 `ADB_CONNECTIONS_FILE`，shots.sh 把它指到临时文件）——**顺带翻掉一条老毛病：此前「拍一次验收图」就会往用户真实的连接清单里写一条**。不静默冒充：没配矩阵时 shots.sh **跳过并说明**，绝不让它退回演示库拍一张「看着像真库」的假帧（那正是这颗控件欠帧的原因）。**四张新帧（47 → 51）**：`p3-query-catalog`（190×166 弹出层裁图：New Query / Switch to database / ✓ shop / shop2 —— `库▾` 自 R44-G1 交付至今**第一张**帧）、`p3-query-catalog-bound`（1280×792 页面态：行 2 = shop2、侧栏两把库、状态栏 `Switched to database shop2 · SQL copied to a new query tab (not run)`）、`p6-new-view-mysql`（`CREATE VIEW \`v_paid_orders\`` —— 方言驱动 DDL 预览第一次有画面）、`p6-design-view-mysql`（**D-29 的画面证据**：一句 `CREATE OR REPLACE VIEW \`v_active_users\``、无灰字提示、按钮 Update View；⚠️ 如实记：MySQL 规范化后的读回是**一行长句**，720px 弹窗里要看全得横向滚动 —— 那是 MySQL 自己的形态，不是我们截的）。**这一帧抓出 D-36（R44-G1 起就坏的产品缺陷，不是画面问题）**：`bound` 首拍的编辑器是**空的**，而状态栏那句「SQL 已复制到新查询页」是假的。探针定住链条 `openQueryOn conn=matrix-mysql sqlLen=22` → `pageSqlLen=22` → `currentQuerySql tab=… node=HBox` → `openQueryOn conn=matrix-mysql@shop2 sqlLen=0`：外壳取「当前页 SQL」问的是 `TabBar.contentOf(id)`，那里存的是**标签胶囊** `HBox(6, icon, title, dirty)`，`instanceof QueryPage` 永远 false → 换库开出的新页永远空串。行为门禁看不见它，因为 `QueryCatalogSelectorTest` 把 `onSelectCatalog` 桩掉了、从不走外壳回读。修：`WorkspaceView.queryPage(tabId)` 读真正托管那一页，`TabBar.contentOf`（零调用者 + 注释本身在说谎「取已建的页节点」）删掉；新门禁 `AppShellTest.switchingCatalogCarriesTheSqlIntoTheNewQueryTab` 走**真菜单条目的 onAction 链**（⚠️ 它走「目标库＝母连接默认库」那条分支，演示库是 SQLite、造不出第二把真库；缺陷在两条分支共用的读取口上）。**变异**：`currentQuerySql` 退回 `tabBar.contentOf` → 红 **1**。**另一条红是新代码自己踩的**：首版在 AppShell 里 `switch (engine) → DialectKind.MYSQL/…`，被 `DialectBranchAuditTest`（A1：方言分支不许散在外壳）当场拦下 —— 全量首跑红 **1**；改成 `DialectKind.of(engine)`（取值即方言 id），环境变量前缀只做名字对齐（`postgresql→PG` / `sqlserver→MSSQL`）。**第三条红同样是新代码自己踩的**：那几条 `[screenshot]` 控制台诊断我一开始用中文写，被 `I18nAuditTest.hardcodedChineseIsRatchetedDown` 拦下 —— **红 8 条**（AppShell 原本 0，棘轮上限 8 全留给 CellValueParser/CellRenderer 那 8 条历史残留），同一条还有把配套的尺：汉字清零的文件**也不许留全角标点**。定下的口径：**外壳里 `[screenshot]` 这类控制台诊断一律 ASCII**（既有的 harness 打印本来就是英文），界面文案才走 `I18n.t(...)`。**种子**：`dbmatrix.sh seed_frames` 给 MySQL / MariaDB / PG 的 `shop` 幂等种 `users` + `orders` + `v_active_users`（「设计视图」那帧的前提是读回一条真定义；`orders` 是回量之后补的 —— `p6-new-view-mysql` 预览里写的是 `FROM orders o JOIN users u`，**预览里出现的表必须真在库里**，否则那帧「SQL to be executed」在 `shop` 上根本跑不通；帧本身不受影响，弹窗拍的是静态预览文本，重拍四张逐字节一致 = 通道可复现）。全部幂等（`IF NOT EXISTS` / `INSERT IGNORE` / `ON CONFLICT DO NOTHING` —— D-30 那条「每次都留一串已存在报错」的教训），并加了 `dbmatrix.sh seed` 子命令，好让「只重铺内容」不必重起容器。**排掉一个假象**：`库▾` 首拍里多出第三把库 `vr`，查过没有任何测试/脚本建库（`grep CREATE DATABASE` 只命中 SQL Server 的 shop/shop2），是我早前手工 `CREATE OR REPLACE VIEW` 探针的残留，已删 —— 不写进结论。**门禁**：`ScreenshotScriptAuditTest` +2（摆态名必须在 AppShell 的 pose 分派里有分支；矩阵帧必须被门控、脚本里不许出现 `ADB_TEST_*_PASSWORD=<字面量>`、档案必须指到临时文件）+ `AppShellTest` +1（D-36）= 全量回量 **1447 / 156 / 0 失败 / 6 跳过**（2026-11-02 23:32，10:58 分钟，带矩阵环境；`adb-ui-shell` 732 → 735，其余逐家不变）。**重拍核对**：全量回量之后按最终代码把这四张各重拍一次 —— 逐字节与首拍一致（4400 / 57203 / 25429 / 20101），说明这条通道拍的是**可复现**的画面而不是碰运气抓到的一帧（`shots.sh` 每格先 `rm -f` 旧帧再拍，所以「这一格没拍出来」不会伪装成「旧帧还在」）。**画面账至此还挂的**：SQL Server / Oracle / PG 上的视图形态帧（本轮只排了 MySQL 那一侧），以及 SSH 半边（G5a）的全部画面 —— 那条要等选型先拍。 |
| 2026-11-02 | 第四十七轮 · R47-a（时间线在 R52-b 之后；G5b 的 SSL 半边，G5a SSH 未开工） | **TLS 档位从「界面写着没验过」变成「服务端逐条对质」—— 这一轮改的是文案与归类，抓出的却是三条产品缺陷**。R46 收官时全仓库只剩这一条真库欠账，因为证书链校验要的不是容器而是一套 PKI。形状：`scripts/dbmatrix.sh tls-up` 起**第二圈独立对端**（`adb-mysql-tls` 33071 / `adb-mariadb-tls` 33072 / `adb-pg-tls` 54331，主矩阵一行不动），自签一把签发 CA + 一把**从没签过服务器证书的冒名 CA**，服务器证书 `CN=localhost` + `subjectAltName=DNS:localhost`、**没有 IP SAN** —— 同一个容器天然给出「名字在证书里」和「不在」两个地址，不必再起第二个对端；私钥只落在 `scripts/.dbmatrix-tls/`（gitignore，与口令同级敏感），**证书内容与私钥不进文档、不进提交**。**18 条契约（3 引擎 × 6）全绿**，判据一律是**服务端侧读回来的会话密码套件**而不是「连接成功」（`SHOW SESSION STATUS LIKE 'Ssl_cipher'` / `pg_stat_ssl` 配 `pg_backend_pid()`）：证书校验失败之前连接可能已经建起来，`require` 这类档位连上了也不代表加密过。**画面变的两处**：① 连接向导 SSL 页那两行提示（`wizard.ssl.mysqlHint` / `mariadbHint`，双语）—— 旧文案写「本版不改根证书这一格 / 加密连接没在真库跑过」，量到的驱动事实是 **Connector/J 8.2 参数表里既没有 `sslCA` 也没有 `sslCapath`，且未知属性被静默忽略**（带 `sslCA=/does/not/exist.pem` 连 `sslMode=REQUIRED` 照样成功），所以 MySQL 这一档要的是 `trustCertificateKeyStoreUrl` 三件套而不是 PEM 路径，新文案直接给出能用的那条通道，并写明「都在本机 Docker 的 TLS 对端上跑过」；② 新增错误档 **`TLS_VERIFICATION_FAILED`**（**D-32**）：`ErrorDetails.kindLabel()` 有对应徽标文案、`badgeSaysItAll()` 对它返回 false（证书失败必须展开看细节），双语各加 5 个键（kind / summary ×2 / advice ×2）。**三条缺陷全部由真对端抓出**：D-33 「不加密」从来不加密（三家方言 `OFF` 原来不写任何参数，而 Connector/J 默认 `PREFERRED`、pgjdbc 默认 `prefer` —— 首跑红两家；现在显式写 `DISABLED`/`disable`/`DISABLE` 且 `sslLevel()` 反向认得）；D-32 证书失败被说成网络断了（MySQL 把 JSSE 异常**包进** `Communications link failure`、MariaDB 同一句里两样都有 → 证书分支必须排在连接分支**之前**）；D-34 连不上被说成查询超时（超时分支下移到连接之后，锁等待超时仍留最前：那是 SQL 层的事，建议不同）。**D-31 裁定**：不砍 MySQL 的 VERIFY_CA 档 —— 砍掉等于在界面上少一个真能用的档位，改法是如实文案 + 双语门禁 + 归类翻转。**变异验证**：OFF 退回 `null` → `DialectTest` 红 **1**；证书分支挪回连接之后 → `ErrorClassifierTest` 红 **4**（含「同句并存时谁赢」那条顺序即结论的门禁）；超时分支挪回连接之前 → 红 **1**。⚠️ **本轮翻掉两处假门禁，都不在数字里**：① `SHOW` 返回 Variable_name + Value **两列**，首版两处都取第一列，于是 ②③⑤ 在 MySQL / MariaDB 上把字符串「Ssl_cipher」当成了密码套件 —— **绿灯是假的**，列号现按方言给（2/2/1）并写进注释；② 把中文 `mysqlHint` 退回旧文案「未在真库验证」，双语那条测试**红 0 条** —— 界面默认语言是英文，原来只钉得住英文那份，现已补正面断言 + 旧话黑名单（三种文案变异各红 1 条，基线 27/0）。**D-35（测试侧）**：`densityChangesTheGridRowHeight` 报「Expecting actual not to be null」是误导 —— 真因是 `onFx` 吞了 FX 线程上 `activeGrid()==null` 的 NPE，而 NPE 来自 TestFX 在虚拟化 `TreeCell`（`localToScreen` 为 NaN）上双击没被识别；改用本仓库既有形态（重取单元格重试 + 等页签出现，3 次仍不开则抛硬断言），已排除的假因记一笔：Java `Preferences` 污染（挪开 plist 仍红）。⚠️ **不写「已验」的部分**：SNI 没有单独证据（三家里没有一家需要靠 SNI 区分多域名证书），MySQL 5.7 仍只有方言层承诺。全量 **1444 / 156 / 0 失败 / 6 跳过**（2026-11-02 21:50 回量，11:42 分钟；较 R52-b 基线 1412/155 = +32 条 / +1 类，逐笔对得上：`adb-jdbc` 174 → 194 = +20（TLS 矩阵 18 例 + `DialectTest` 26→28 的 OFF 档位那 2 例）、`adb-safety` 76 → 84 = +8（`ErrorClassifierTest` 13→21）、`adb-ui-shell` 728 → 732 = +4（`ErrorDetailsTest` 7→9 + `ConnectionWizardTest` 25→27））。~~两张欠帧（`库▾` 展开态、MySQL 单语句形态）仍挂在 SSH 半边之后。~~（✅ 2026-11-02 R47-b 已还，不必等 SSH：截图通道新增 `ADB_MATRIX`，能把外壳绑到矩阵容器里的真服务器上 —— 四张真库帧见下一行。） |
| 2026-11-02 | 第五十二轮 · R52-b | **切库落点进真库矩阵（契约第 9 条）—— 画面一个像素没动，动的是「我们凭什么说它可用」**。查询页那颗 `库▾`（R44-G1）此前只有两条断字符串的单测：`CatalogVariantProfileTest` 验虚拟连接 id = 父id@库名、验 `database` 字段被换掉；`CatalogTreeTest` 验下拉有哪些项。**没有一条问过「把 SQL 交给绑到新库的会话，它落在哪儿」** —— 而这恰好是这颗控件唯一会伤人的失败方式：状态栏写「已切到数据库 shop2」，语句打在 `shop`，用户在一个他没在场的库里改了数据，而且**不报错**。补的证据形状：① `scripts/dbmatrix.sh` 每家多建一把 `shop2`（同主机同端口同用户同口令，只差库名 —— 就是 `catalogVariant` 给产品的形状；**Oracle 故意没有 DB2**，它没有这一层）；② 契约三段：`catalogsSql` 第 1 列要同时含两把库（库里存在但查不出来 = 用户点不到）、两条会话 `currentCatalog` 各自对（SQL Server 的库名走 `databaseName` 属性、PG / MySQL 走 URL 路径段，**写错的症状都是「连得上、落错库」**）、**同一个表名 + 字面完全相同的 `CREATE` / `SELECT`** 两边各读各的行（没切过去会撞「表已存在」，切过去但隔离不干净则两边行数一样）；③ 缺 `*_DB2` 时**按引擎跳过并写明缺什么**，不假通过。矩阵由 41 例变 **5 × 9 = 45 + 1 条口令门 = 46 例**，五引擎全绿；清理走两条会话各自的 `dropTableSql`，本轮实测四家 + `shop`/`shop2` 零残留对象（顺手复查了 R52 契约 8 的临时表 / 视图也没留）。**反向验证两处**：把 `away` 会话改成也开在原库（=「界面说切了、其实没切」）→ 46 例红 **4** 条，全红在「切库后的会话应落在 shop2」那一句；`MariaDbDialect.jdbcUrl` 去掉路径段的库名 → `CatalogVariantProfileTest` 红 1 条（**MariaDB 原本漏在「换库要改连接串」那个循环外面**，而它有自己那份 `jdbcUrl` 实现 —— 矩阵在 CI 上按环境跳过，漏掉的那家在 CI 上等于切库零证据，已补进循环）。Oracle 那一支仍钉「没有库层 ≠ 连现在在哪儿都读不出」，实测 `SYS_CONTEXT('USERENV','DB_NAME')` 回 `FREEPDB1`（同会话 `SERVICE_NAME` 是小写 `freepdb1`，断言按忽略大小写比）。⚠️ **帧这条要说全**：`库▾` 这颗控件**从来没有过验收帧** —— R44-G1 交付时用的 `p3-query` / `p3-query-menu` 两张拍的是菜单整体，下拉展开态里没有第二把库可显示（演示库是 SQLite，本来就没有库层），`scripts/shots.sh` 里也没有 catalog 这一格挂钩。本轮补的是**行为证据**（真库落点），不是画面证据；「下拉里两把库、选中后状态栏写已切到 shop2」这张帧仍欠，得先让 shots 通道能绑矩阵容器（凭据仍只走环境变量），与 R52-a 欠的那张同一件事，挂在 R47 尾部。⚠️ 顺带修一处**同文件两种口径**：README 数据库表里 MariaDB / SQL Server / Oracle 三行早写了「confirmed against a real server」，紧邻的 MySQL / PostgreSQL 却还停在「✅ 5.7 and 8.x」「✅」—— 补成各自实测版本时把**没有执行证据的那一档写明**（MySQL 5.7 只有方言层承诺，版本矩阵是 opt-in、本机没跑过），与 STATUS §测试环境同口径。 |
| 2026-11-02 | 第五十二轮 · R52-a | **视图生命周期进真库矩阵（契约第 8 条）—— 有一处画面变了，而且是因为我们此前判错**。5 引擎 × 8 契约 = 40 例 + 1 条口令门 = 41 条全绿：建视图 → 重建 → **按产品读路径读回**（`SchemaService.ddl` → `readDdl` / `readViewDdl` → `ViewDefinition.selectBody`）→ 删除后必须报 `SQLException`。三个结论：① **D-29** MySQL 8.4 与 MariaDB 11.8 都收 `CREATE OR REPLACE VIEW`（旧说法「MySQL 没有、那是 MariaDB 的分支特性」来自没验过的文档传承）→ 重建视图弹窗在 MySQL 上现在显**一句**、不再挂灰字提示、`approvalRequired()` 不再为它多弹一次危险确认。⚠️ 两句「先删再建」只剩 SQLite。判反的代价不是「多打一句 SQL」：两句之间视图**真的不存在**，用户在一个单句就够的服务器上被问「要不要先删再建」= 把我们的知识错误转嫁成他的操作风险。② **D-27** SQL Server 的 `readViewDdl` 是 `return null` 的空壳 —— 「设计视图」在那个库上**从来没开过窗**
（读不到定义就不开窗这条规则把它挡在门外，界面只把状态栏写成「读不到视图定义」），而 `ViewDefinition` 的注释一直声称走 `sys.sql_modules`。改成真查 `sys.sql_modules JOIN sys.views`（联 `sys.views` 而不是裸 `OBJECT_ID(?)`：后者对函数/同义词也给定义）。③ **D-28** PG 16 的 `CREATE OR REPLACE VIEW` 只许**在末尾加列**，改名/删列服务端报错（`cannot change name of view column "id" to "adb_marker"`）→ 契约改用六方言都支持的加列形态，「要不要主动提示」留给负责人裁定。**帧不变**：`p6-new-view` / `p6-design-view` 用的是演示库 = SQLite，两句形态照旧，D-29 只改了 MySQL 那一侧。⚠️ 代价记一笔：「MySQL 上预览一句、无灰字提示、不二次确认」这个新形态**只有单测门、没有实拍帧** —— 截图挂钩 `ADB_DESIGN_VIEW` 绑的是演示库（SQLite），要给它拍一张得先让 shots 通道能连矩阵容器（凭据仍只走环境变量，不落仓库）；挂在 R47 尾部或单开半小时。**反向验证三处**（每条门禁改动都撤一次修复看红在哪）：删 `MySqlDialect.viewReplace()` → `ViewLifecycleSqlTest` 红 2 条；`SqlServerDialect.readViewDdl` 退回 `return null` → 单元门 `SqlServerViewDefinitionTest` 红 2 条 **且** 41 例矩阵在真 SQL Server 上红 1 条（同一缺陷两层都拦得住，说明契约不是摆设）。⚠️ 排坑：`./mvnw -pl ui/adb-ui-shell -Dtest=…` **不带 `-am`** 会用本地仓库里**已安装的旧 adb-jdbc 构件** —— 第一次变异跑出的「MySQL 仍两句」是假红，源码里根本没有那段代码。门禁：新增 `SqlServerViewDefinitionTest`（5）、`RealDatabaseContract.viewLifecycleRuns`（第 8 条，五引擎 ×1 + SQLite 侧由既有契约覆盖）、`ExternalRealDatabaseTest` 契约表 +1；`ViewLifecycleSqlTest` 改 2 条（MySQL 进单句组、两句那条改成 SQLite 专属）、`ViewDesignerDialogTest` +1（MySQL 单句不要二次确认）与 1 条换方言（SQLite 两句并要有提示）。文档翻转：`ViewReplace` 枚举注释、`MariaDbDialect` 唯一的视图 override 删除、`StructureSqlBuilder#viewStatements` 注释、UI-FORMS §11.2 方言表与 §11.7「未跑真库」标注。 |
| 2026-11-01 | 第四十三轮 · R43-c | **收尾 R43-b 帧目视留下的两处「看着不对」** —— 都不是新能力，是画面自己骗人。**① 工具条右端那颗孤立的 `▾`**：探针量出来的是「视图菜单 `≡▾` 宽 31.5px，箭头落在 x=5、图标落在 x=9」—— 箭头在图标**左边**并把图标压在下面，1280px 帧上只看得到一颗没有主人箭头。根因在 CSS：`.adb-button--icon` 把 `-fx-min-width/-fx-pref-width` 钉成 30px，那是给**纯图标键**定的；这颗是 `MenuButton`，内容要「内边距 8+8 + 图标 14.5 + 箭头 16」≈46px，装不下时 `ButtonSkin` 的处置是把 16px 的 `.arrow-button` 移到 graphic 左侧重叠，而不是把字挤扁。修法是新增一档 `.adb-button--icon-menu`（内边距照 `--icon` 紧，但**宽度交给内容**）+ Java 侧 `setMinWidth(USE_PREF_SIZE)` 防被挤回去；label 的 8px 横向内边距归零（图标与箭头之间 `.arrow-button` 自带 8px），⚠️ 这条必须写成 `.adb-button.adb-button--icon-menu > .label` —— 后面还有一条同级 `.menu-button > .label`，specificity 打平时**后发制人**。变宽 15px 吃掉的是工具条弹簧里现成的 36px 余量，折叠集不变。**为什么属性断言抓不到**：`.arrow-button` 是 skin 子节点、不是 `Pane`，`instanceof Pane` 的树遍历看不见它；门禁因此走 `menu.lookup(".arrow-button")`，并在断言前强制 `root.applyCss(); root.layout()`（等布局会引时序假红，量几何必须先把布局逼出来）。**② 建表弹窗四列时那条 130px 死空白**：`VBox.setVgrow(table, ALWAYS)` 把页签内容区多出来的高度全交给网格，于是「网格最后一行」与「属性面板」之间撑开一段带子，看着像还有几行没填 —— 正是 `fitTableHeight` 要治的毛病被这句 vgrow 放回来。改成三段（工具条 / 网格 / 属性面板）作为一个**整体**在内容区垂直居中（`fieldPage.setAlignment(Pos.CENTER)`，不需要 vgrow：`TabPane` 本来就把 content 拉满）。⚠️ 与 R43-b 磁贴那次相反，这次**不能**照 grow 链断言：这里的撑高来自 TabPane 的拉伸而不是 VBox 的 growable 分配。考虑过并否掉的三种：让属性面板长高（灰底会变成一个空洞，内嵌样式在内层 `GridPane` 上）、弹窗高度随列数变（页签之间会跳）、把空行网格线画回来（和 R31 的定调对着干）。**弹窗单测只断结构**——没 show 出来的 `Dialog` 没有 scene，任何几何断言都会读到 0 而**静默通过**，居中这件事由重拍的 `p6-new-table-fields` 帧证明。**两处都做了反向验证**：临时撤掉修复后，`ToolbarDiscoverabilityTest.menuKeysNeverSqueezeTheirArrowOntopOfTheIcon` 红在「data-view 是下拉键，挂 `adb-button--icon` 会把箭头挤到图标左边」，`NewTableDialogTest.spareHeightGoesToTheEndsNotBetweenGridAndPanel` 红在 vgrow 那条。Navicat 对手帧只有参照价值、没有断言价值：`navicat17-designer-fields` 那张是 15 个字段带滚动条，网格边界被滚动条标着，**不能**据它断言「列少时 Navicat 的留白形态」。ROADMAP 接下来第 8 项随之销账。门禁：`ToolbarDiscoverabilityTest` +1（下拉键的箭头/图标各在其位、整排不出窗）、`NewTableDialogTest` +1（无 vgrow + 三段居中 + 页子里第一子是工具条 `HBox`）；全量 **1262 / 147 / 0**（53 条环境跳过）。⚠️ **重拍这一轮又踩到「连拍拍到瞬态」**：一次连拍 12 张，逐张与 HEAD 做像素对照才发现六张有问题 —— p2-users / p2-find / p2-transaction / p2-users-dark 各多出一条 22px 的**行高亮带**（物理鼠标恰好压在窗口那一行上，`:hover` 进了快照；单帧重拍即消失，且换到的行各不相同 → 是环境不是代码），p2-details 多一条侧栏树行的，p2-form 干脆落在 Structure 切面上（pose 没就位）。这些帧**尺寸对、字节数正常、彼此不重复**，§15 原有的「帧去重」门禁一条都抓不到 → 判据补进 `scripts/shots.sh` 坑 5：**目视之外再加一道逐像素对照**，变化必须只落在本轮改动的那块盒子里（本轮的合法盒子是 `(1187,165)..(1226,176)` 40×12），多一处就单帧重拍。 |
| 2026-11-01 | 第四十三轮 · R43-b | **去臃肿：AI 子项收成一次点击 + 连接磁贴 22→6**（用户点名先做）。判据不是「比 Navicat 少几颗」，而是**一个控件承载几个真承诺**。**① AI**：四处 AI 入口全部是 Navicat 有的，所以**键留着**；多余的是下拉叶子 —— `Ask AI`（新建表）三项、`AI 助手`（查询页）两项、`Fill with AI`（数据网格）两项，**七项打开的是同一份说明**。三项承诺三种能力（解释 / 生成 / 优化），点哪个都只有一段「缺什么」，那是装饰不是功能。收成**一次点击 = 一份如实说明**，删掉 7 条子项文案键（`newtable.askAi.*` ×3、`query.askAi.*` ×2、`grid.ai.*` ×2）；`#table-list-new-ai` 本来就是一击开说明，不动。**② 磁贴**：类型页照 Navicat 摆 22 张（9 类型 + 13 厂商）+ 22 个复选 + 搜索框，其中 **16 张点进去是一份「本版不支持」**。这不是 Navicat 的形态 —— 它摆这些家是因为它真连得上去；我们摆出来只是让用户多点十六次空。收成**只带真驱动的六家**（一行六张），连带删掉左栏复选、搜索框、滚动容器、分隔线、`unsupportedStep()` 与 `wizard.unsupported.*`；「其它数据库怎么办」从一张**点得进去**的页面改成页面上**明写的一行**提示（§11：告知要铺路，不能藏在一次空点击后面）。四条 `adb-db--{document,redis,snowflake,cloud}` 作用域规则随死磁贴删除。**留下的判据写进门禁**：`askAiButtonExplainsInsteadOfPretending` 断的是 `isNotInstanceOf(MenuButton)` —— 不许再长出子菜单；`typePageShowsOnlyTheSixDriversItActuallyShips` 断复选栏与搜索框 `isEmpty()`。⚠️ 弹窗宽度 900×700 **保留**：现在撑它的是属性页七页签（库清单与 TLS 表单要用横向空间），不再由磁贴决定，注释已改口径。**帧目视改的一处**：首拍的类型页内容止于 y≈255，下半屏 445px 全空（`TilePane.prefColumns` 只是偏好，900px 宽把 3 列展成了 6 列一行），看着像没做完 —— 于是磁贴块改 `Pos.CENTER` 并把 `prefColumns` 写成六，让代码和画面一致。**第一次改完重拍没变**：`outer` 里没有任何 `vgrow`，多出来的高度根本没交到 `body`/`tiles` 手上，`alignment` 于是在自己的 pref 框里居中 = 空转。补上 `VBox.setVgrow(body, ALWAYS)`（grow 链：outer → body → tiles）才真的把留白分成上下两段。⚠️ 教训：**改对齐/留白必须回拍一张**，布局属性在没有 growable 链路时是静默失效的，单测看不出来（`getAlignment()==CENTER` 照样绿）。门禁同一条里除了 `getAlignment()==CENTER`，还断**整条 grow 链**（`VBox.getVgrow(tiles)` 与 `VBox.getVgrow(tiles.getParent())` 都是 `ALWAYS`）—— 量高度要等布局、会引进时序假红，断属性不会。门禁：`ConnectionWizardTest` 删 3 条（守的控件已不存在）加 2 条、`NewTableDialogTest` 1 条改形态断言、`I18nAuditTest` 两套 bundle 同步；全量 **1260 / 147 / 0**（53 条环境跳过）。帧目视另留两处「看着不对」记为 R43-c（见 ROADMAP 接下来第 8 项）：网格工具条右端一颗只有箭头的 `▾`、建表弹窗四列时字段区与属性面板之间的空白。 |
| 2026-11-01 | 第四十三轮 · R43-a | **六种数据库的厂商图形**。六家（MySQL / PostgreSQL / SQL Server / Oracle / SQLite / MariaDB）各一枚**自绘**填充图形（不提取 Navicat 原版位图，也不碰厂商商标），形态取各自标志的骨架：MySQL 海豚鳍 + 水滴、PostgreSQL 象头 U 与两牙、SQL Server 六边形环、Oracle 椭圆环、SQLite 叶、MariaDB 球 + 跃弧。图形注册在 `ConnectionCatalog` 的行上（`DialectBranchAuditTest` 的白名单落点），向导磁贴与侧栏连接节点**同一份取值、同一个作用域类** —— 同一个 MySQL 在两处不会一个青一个蓝；没有 profile 的连接退回中性插头 `CONNECT`，不硬造品牌色。品牌色**只上 `adb-icon-tone-c` 这一层**，轮廓层跟随前景，所以深色底上不会糊成一片。六家各一对 `vendor-*` 令牌（亮档深、暗档亮，是**不同**字面值），对比度门禁要求两档对 `bg-canvas` 都 ≥3:1。️ **变异验证抓到一处假门禁**：`connectionNodesWearTheirVendorGlyph` 原本读 `DemoNode.toString()`，把单元格工厂的挂类整段删掉照样全绿 —— 改成断**渲染出来的 cell 里的图标容器**（类在不在）+ **`tone-c` 的计算填充**（色到没到），后者才真正断「后代规则匹配到了节点」，光有类不等于 CSS 命中。同轮 `SideBarLivenessDotTest` 被新字段红过一次（record 加第 6 个分量后 `connectionId=demo]` 的字符串形状不再成立，R37 去尾部 count 时红过同一处），改成按值匹配、边界认 `,` 或 `]`。新增暗色帧 `p5-connect-type-dark`（品牌色两档不同值，只拍亮档等于六家在暗色档一眼没看过），整套 **46 帧（亮 39 + 暗 7）**。门禁：新增 `VendorGlyphTest`（5：六家互不相同 / 作用域类各带各的令牌 / 两档品牌色真的落到填充层 / 脱离作用域保持中性 / 磁贴自带作用域类）、`ContrastTest` +1、`LucideIconTest` 自绘白名单 9 项、`SideBarTreeTest` 重写为渲染层断言；全量 **1262 / 147 / 0**（53 条环境跳过）。 |
| 2026-11-01 | 第四十二轮 · R42-a | **视图生命周期 V1–V3（N28-a）**。新建 / 设计视图做成 **720 宽档弹窗**（不是标签页：视图没有数据页可停留，做成页签等于留一张关不掉的空页），三格各有标题 —— 视图名 / `查询（SELECT）` / `将要执行的 SQL`；下面那格**只读**，且摘掉「⌘↵ 执行当前语句」那句提示（它执行不了）。**预览即执行**铁律沿用：预览文本与 `StructurePlan.sql()` 逐字同源，一次计算两处用。方言差异摆到台面上：MariaDB / PostgreSQL / Oracle 走 `CREATE OR REPLACE`（一句）、SQL Server 走 `CREATE OR ALTER`、**MySQL 与 SQLite 没有单句形式** → 预览显出 `DROP` + `CREATE` **两句**并给一句灰字提示，同时多要一次确认（第二句之前视图已经不在了）。查询体只收 `SELECT` / `WITH` / 括号开头的组合查询，粘整条 DDL 或塞第二句当场拦下并写明原因。「设计视图」那一格名字**置灰 + tooltip** 说明替代路径（本版本没有视图重命名）。表节点与视图节点的右键菜单**拆开**：视图不再摆「清空表 / 重命名表」这种必失败项。**三处能力面正式裁定不做**（P1）：Automation / Model ER 全量编辑器 / Data Generation —— 磁贴与工具条入口都留着，点进去是如实说明页。⚠️ 视图 DDL 由三方言 SQL 快照 + SQLite 契约钉住，**未在真 MySQL / PostgreSQL 联调**（D-5 需要 Docker）。**四张新帧（`p6-new-view` / `-dark` / `-invalid` / `p6-design-view`）目视后修的三处**：① 预览格没有标题，实拍里两格编辑器长得一模一样，看不出谁是输入谁是输出 → 补 `newview.preview.title`，原挂在格内的 `newview.preview` 改成空态该说的话；② `DialogPane#setPrefHeight(560)` 写死导致正文下方一条一百多像素死空白（`DialogPane` 按各区**首选高度**自上而下排，多出来的高度落在正文区之外，提示行出现时也吃不到）→ 改成高度随内容，由两格编辑器（184 / 160）撑出来；③ **弹窗里的错误行一直是黑的** —— `.adb-dialog .label` (0,2,0) 压过裸 `.adb-error` / `.adb-muted` (0,1,0)，红字只活在单测里，新建表弹窗同病（它的帧没拍到错误态）→ 补两条同特异性后置规则（整套 45 帧重拍、无重复帧，弹窗族帧里的灰字提示这才真的是灰的）。门禁：新增 `ViewLifecycleSqlTest`（11）、`ViewDefinitionTest`（8）、`ViewDesignerDialogTest`（16），`TableDdlTest` +4、`SideBarTreeTest` +2；全量 **1254 / 146 / 0**（53 条环境跳过）。同轮修 `AccessibilityAuditTest` 的开页时序：`doubleClickOn` 在满载时越过连击窗口 → 页签没开，既假红（`lookup` 拿 null）又**假绿**（整棵树的焦点审计在没开页时照样通过），改为重取单元格重试 + 等待页签出现。 |
| 2026-11-01 | 第四十轮 · R40 | **弹窗系统标题栏 + 连接向导两页（N26 N27）**。六个自绘抬头条（索引 / 新建表 / 表注释 / 外键 / 触发器 / 变量）连同右上 × 全部删除，标题与「表名 · 方言 · 库」拼进 `Dialog#setTitle` 由系统标题栏承载（路径按中段截断，`DialogStyles.shortenContext`），关窗走系统 × 与 Esc；`DialogStyles.apply` 只剩四件事（样式表 / 主题类 / 限高 / 按钮序），**弹窗尺寸记忆随之取消**（拍板 4）。连接向导改两页 **900×700**：类型页左栏 `Connection Filter` 9 + `Vendor Filter` 13 复选、右栏 **5 列 112×108 彩色磁贴** + 搜索（复选与搜索共用一套过滤，两块都滚），左下 `New Connection with URI…`、右下 `Next / Cancel`；**22 张磁贴照 Navicat 全摆，但只有 6 种真有驱动** —— MongoDB / Redis / Snowflake 与 13 个厂商点下一步进**如实说明页**（写明只有 6 种驱动、给协议兼容与 URI 两条替代路径），不摆填了也连不上的表单。常规页补 `AIDB ⟷ Database` 示意图（44px 图标 + 120px 连线，右侧库型名随选择变）、标签右对齐（110px）、`Save password`，底部五键（测试连接 / URI… / 弹簧 / 上一步 / 取消 / 确定）。**帧目视后修的三处**：① `Icons.of/colored` 返回的是带 `scaleX/scaleY` 的 SVGPath，布局边界仍是 16×16 —— 大图标直接叠进 VBox 时文字会压到图形上（p5-connect-type 实拍「MySQL」浮在圆柱体底边），改为把图标 Label 钉成 44×44 并把 `alignment` 设成居中；磁贴名 13→12px、宽度按内容区 88px 算（`Microsoft Azure` 从两行折回一行）。② 属性页七个页签的内容补 16px 内边距（此前 SSL 说明 / 高级 / 备注的文字贴在弹窗左缘 x=0），SSL 表单与常规页同样居中，页脚状态行与五键统一缩进 16px。③ 菜单按钮的文字底部被裁（p3-query 第二排 `agenticdb-demodb` 的 g 没有尾巴、网格工具条 `History` 的 y 被削平）：modena 的 `.menu-button > .label` 另有 4px 竖直内边距，叠在外壳内边距上后 label 内容区只剩 10px，`LabeledText` 按内容区高度裁字；竖直归零、横向保留 8px（左右缩进与箭头间距不变，其他工具条几何零变化）。新增具名图标档 `Icons.SIZE_TILE = 44`（磁贴与示意图），图标尺寸阶梯 8 / 12 / 14 / 16 / 20 / 26 / 32 / 44。门禁：`ConnectionWizardTest` +6（9+13 条目 / 五列 / 复选过滤 / 无驱动说明页 / 五键顺序 / 示意图 / 900×700）、`DialogStylesTest`（apply 不写 pref 尺寸）、`IndexDialogTest` / `NewTableDialogTest`（标题进系统栏、内容区无 `.adb-dialog__context`）；全量 **1204 / 143 / 0**（53 条环境跳过）。同轮修掉门禁自身的一处时序假红：`AppShellTest` 的两条「双击进单元格编辑」在机器满载时两次单击间隔越过连击窗口，改为重取单元格后重试（同一份测试在 R39 的 HEAD 上也偶发）。 |
| 2026-10-31 | 第三十九轮 · R39 | **查询页 + 已保存查询 + 状态栏（N21–N25）**。查询工具带改**双行 62px**：第一行 保存 / 查询构建器 / 格式化 / 代码片段 / AI 助手▾（右端 风险提示 · 历史▾），第二行 查询名▾ / 连接▾ / `Run▾` / 停止 / 执行计划；「全部执行 / 带参数执行」进 Run 菜单，「清空」进编辑器右键菜单。语法配色落成令牌（关键字 `#097BF7` 粗 / 字符串 `#FF1744` / 注释 `#929292` / 正文 `#424242` / 表名下划线）。结果区拆 **27px `Message / Summary / Result N`** + **32px `Data / Info`**，网格表头单行 `#E7E7E7`。已保存查询从弹窗改成**工作区对象页**（四列 + 设计 / 新建 / 删除查询，删除默认按钮为取消），侧栏 `Queries` 组双击 / Enter 打开。状态栏改**按页型换段**（查询 / 数据 / 对象三套），「自动提交 · 只读」段连同 CSS、文案、调用点删除。同轮把 §5.3 与 §6.1 遗留的「顶区 114px / Ribbon 64 / 页签行 26 / 状态栏 22」更正为 R36 实拍值 **140 / 91 / 25 / 23**。门禁：`StatusBarTest`（三页型布局）、`SavedQueriesPageTest`、`SqlSyntaxColorTest`；全量 **1199 / 143 / 0**（53 条环境跳过）。 |
| 2026-10-31 | 第三十七轮 · R37-c | **库级树（N32）+ 页签全名带库（N7-b）**。MySQL/MariaDB 已有的「虚拟连接 = 一个库」路径扩到 **PostgreSQL**（`pg_database`，排除模板库）与 **SQL Server**（`sys.databases`，过滤 master / tempdb / model / msdb）：连接 → 库 → 分组三级，双击库 = 换连接，会话、查询页、对象清单一起跟着库走；父连接所在的库即使被系统过滤（SQL Server 常连 master）也补进树，否则当前连接的对象在树上没有归属。**SQLite 保持单库平铺**（手册口径未核，不硬造），**Oracle 保持 schema 切换器**（一条连接绑一个服务）。schema 切换器改判为「会话内真能改解析路径」才出现：新增 `Dialect.switchesSchemaInSession()`（PG / Oracle true），不再给 SQL Server 摆一个只改树、不改查询的开关。系统库判据下放方言（`Dialect.isSystemCatalog`），不做跨库黑名单。页签全名 `<对象> @<库> (<连接>)`：可见标题仍只在重名时补后缀，tooltip 恒为全名；catalog 虚拟连接用父连接名，库名不重复写两遍；SQLite 无库名退回 `@<连接>`。状态栏当前连接显示 `连接 · 库`。**刻意偏离**：Queries 组仍挂连接层（已保存查询本来就是连接级，不在每个库下重复同一份）。门禁 `CatalogTreeTest`（四种方言的库清单 / 系统库过滤 / schema 切换判据）、`CatalogVariantProfileTest`（PG / SQL Server 的 URL 真的换库）、`TabBarTest`（含无库名退回）；全量 **1180 / 140 / 0**（53 条环境跳过）。 |
| 2026-10-31 | 第三十七轮 · R37-a/b | **对象页二合一 + 导航树收敛**。R37-a：树收成「库 → 表 / 视图 / 函数·过程」，删掉每组计数徽标、整组触发器节点与树底三颗导航键（查询历史 / 诊断 / 导入·导出，仍在菜单、⌘K 与 ⋯ 菜单）；事件不上树，函数 / 过程只经双击 / Enter 打开定义体 —— 树里没有事件节点，原事件分发是不可达代码，已删。R37-b：对象首页与表清单合并成**常驻「对象」页签**（`TabKind.OBJECT`，切连接只换内容）；清单固定**六列**（名称 / 行 / 数据长度 / 引擎 / 修改日期 / 注释，缺值一律「—」，SQLite 不再整列消失）；工具条对齐 Navicat 的**七颗带文字键**（打开 / 设计 / 新建 / 用 AI 新建 / 删除 / 导入向导 / 导出向导），**去掉刷新键与「N 张表」计数**（Navicat 两样都没有；刷新留右键菜单）。⭐ **帧核对抓到的两条**：① 七键的省略号是文案本身（打开弹窗的惯例），不是挤压，`minWidth = USE_PREF_SIZE` 把「挤不下就缩文字」这条堵死了；② 过滤框 120px 下英文占位符被咬成 `Search table n`，但放宽到 150px 会把右侧列表 / 网格切换挤出可视区（这条工具条已排到窗口右缘）——改成短文案 `Search tables` / `搜索表名`，宽度不动。 |
| 2026-10-28 | 第三十二轮 · R32-D | **顶区精简（用户拍板方案 B「并入标签栏」）**：删掉整条 26px 全局工具行与品牌块，顶区 **140 → 114px** = 菜单 24 + 命令带 64 + 页签行 26。命令带由双行 90px 收到 **64px 单行**，同时裁掉 4 个有第二入口的重复命令（表 / 诊断 / 刷新 / 设置 —— 仍在菜单、侧栏、⌘K、⋯ 菜单），只留 6 条；连接 pill / 全局搜索 / 主题 / ⋯ 搬进页签行右侧，与文档页签共用 26px；品牌改为**窗口标题 + 窗口图标 + 关于框图形**。新增窄窗降级 `AppBar.fitChromeWidth`（行宽 <780px 收掉连接副标题与搜索提示词，搜索框缩为 30px 放大镜；`p2-details` 在侧栏 + 抽屉同开、内容区 ~420px 时实证）。⭐ **两条新教训**：① CSS 变量靠继承，工具件搬出 AppBar 后脱离挂 `theme-light` / `theme-dark` 的子树 → 连接点算成纯黑 `0x000000ff`，**主题类必须挂在场景根**；② 节点 id 里带 `.`（`ribbon-object.new-table`）会让 `lookup("#…")` 解析失败 —— 改用遍历 `.adb-ribbon__button` 比对 `getId()`。门禁：`AppBarTest` 12 条（品牌块禁止回归 / 命令带只剩 6 条 / 紧凑形态往返 / ⋯ 含刷新）、`AppShellTest` 顶区两行断言、`BrandMarkAuditTest`（品牌只在窗口身份与关于框）、`TokenParityTest`（64px）；两次变异（工具行拆回独立一行 → `AppShellTest` 红；`compact = false` → `AppBarTest` 红）均还原。27 张帧重拍并逐张目视（`ADB_CONNECTIONS` 摆态补齐被删图标的截图入口）。 |
| 2026-10-28 | 第三十二轮 · R32-G4 | **表单视图（单记录纵向表单）**。一行一个字段：列名 12px 粗（130px）/ 类型 11px 灰（150px）/ 值控件（inset 底 + 6px 圆角，聚焦描 `border-focus`）；行线沿用网格的 `border-subtle`，只读字段用 `bg-subtle` 底 + `text-secondary` 字、`-fx-opacity: 1` 保持可读。八个 `.adb-grid__form*` 样式类先落在 Java 里没有规则，被 `StyleClassCoverageTest` 拦下后补齐；类型栏 110 → 150px 是因为 `BOOLEAN · NOT NULL` 在 110px 下被截成 `BOOLEAN · NOT …`（`p2-form` 实拍）。⭐ **工具条收字判据补两条**：① 按钮个数变化（选中行多出「删除记录」）也要重判，不只盯工具条宽度；② 收起状态下量需求宽必须先把文字装回、摘掉 `adb-button--icon` 并**当场 `applyCss()`** —— 否则读到的还是 30px 图标档（实测 556 / 1093），下一次重判会把文字涨回来、把搜索框裁成「本」。新增帧 `p2-form`（27 张：22 亮 + 5 暗）。 |
| 2026-10-27 | 第三十一轮 · R31 | **表设计器补成 Navicat 的七页 + 字段页三段**。字段页 = 工具条（添加 / 插入 / 删除 / 上移 / 下移）+ 网格 + 常驻属性面板（默认值 / 字符集 / 排序规则），默认值从网格挪进面板换取长度/小数/自增的列宽；网格行高 30 → **21px**（`RowHeights.of("normal")`，Navicat 实测 22，差 1px 换来与自家密度同档）；九列按 Navicat 顺序排（名称 / 类型 / 长度 / 小数 / 不为空 / 主键 / 唯一 / 自增 / 注释），类型是可编辑下拉、候选来自 `Dialect.columnTypes()`；`字段数：N` 常驻工具条右端。⭐ **置灰一律用颜色，不用 `-fx-opacity`**：新写的 `.adb-dialog .table-view .table-cell:disabled` 本想用 `0.45` 半透明，被 `FocusRingAuditTest.opacityIsNeverUsedToDimContent` 拦下（全文只允许命令面板遮罩一处，半透明会把文字和图标一起稀释），改成 `-fx-opacity: 1` + `text-disabled` 色 —— 与本文件「禁用感完全由颜色表达」这条既有规矩一致。⭐ **方言知识不下 UI**：字符集/排序规则的「支持与否 + 常用候选」全部做成 `Dialect` 默认方法（`supportsColumnCharset` / `columnCharsetPresets` / `columnCollationPresets`），UI 里写 `kind() == MYSQL` 会被 `DialectBranchAuditTest` 判红（本轮真的红过一次）；候选表与建表器校验之间加了 `everyCharsetAndCollationPresetIsAcceptedByTheBuilder` 对账门 —— 变异验证：往 PG 候选里塞一个带点号的 `en_US.UTF-8`（过不了注入防线）只红这条。⭐ **行态档集体栽在权重上**：字段网格选中的那一行实拍只剩一圈单元格描边、底色还是白的，第一版补的裸 `.adb-dialog .table-view .table-row-cell:selected` 门禁全绿、帧照样没色 —— 真根因是四处表格作用域的条纹档写成 `:filled:odd`（比所有行态档多一个伪类 = 重 10），把 `:hover` / `:selected` / `--deleted` / `--new` 整档压掉，表现成**只有奇数行掉色**（`p2-cell` 里选中的 `User 002` 同样是白行，只是此前没有任何一帧选中过奇数行）。修法是把**条纹降回一个伪类** `:odd`（空行不条纹交给同权重、声明在后的 `:empty`），而不是给每条行态档补 `:filled` —— 前者一次修完四个作用域，后者每加一条新态都要记住补。门禁 `SelectionStateTest.stateRulesOutweighTheZebraStripeInEveryTableScope` 随之从「规则在不在」升级成「逐作用域把行态档全捞出、比权重 ≥ 条纹档且声明在后」，变异两处各红一次（条纹写回 `:filled:odd`、选中档挪到条纹档前面）。新增帧 `p6-new-table-fields` / `-fields-dark` / `-comment`（**24 张：19 亮 + 5 暗**）。 |
| 2026-10-27 | 第三十轮 · R30-D | **弹窗内第一次出现 TabPane**（新建表的六页设计器：字段 / 索引 / 外键 / 触发器 / 选项 / SQL 预览），规则落在 `.adb-dialog .tab-pane` 之下，不影响工作台自己的标签栏：页签栏铺**画布底色** + 底部 1px `border-subtle`，选中页与正文同用 **surface**、文字 `accent` 加粗，页签内边距 `4px 12px`（12px 字，命中区 ~24px），圆角只取阶梯里的 `3px`（`AppCssAuditTest` 拦四值写法），选中页焦点环清空。⚠️ 底色方向不能反：暗色下画布 `#0B1220` 比 surface `#1E293B` 更暗，反写会让选中页看着像按下去/失效。对象页 = 列表 + 两颗按钮 + 一行说明，行选中用全应用统一的 `bg-selected`。**预览空态改成「说原因」**：表名撞上已有表时不再只清空预览（演示库本来就有 `orders`，实拍帧一片空白），填了表名就照实显示 `表「x」已经存在`，只有表名为空才保留「填写表名后…」。新增帧 `p6-new-table-index` / `p6-new-table-sql` / `p6-new-table-dark`（21 张：17 亮 + 4 暗）。 |
| 2026-10-26 | 第三十轮 · R30-A/B/C | **按 Navicat 17 重定视觉基线**（用户裁定「保持和 Navicat 一样」，第二十一轮的无边框 / 低饱和靛蓝 / 28px 密度 / 22px 行高下限 / 大圆角全部作废）：列表/树 21、网格 23、外壳与细线 `#F0F0F0`、白底导航树、树/网格选中 `#CDE8FF`/`#C4E0F7`、斑马 `#F9F9F9`、强调 `#0067B8`（守 WCAG AA）、圆角 2/3/4/6；顶区 **170px** = 菜单 24 + 功能区 90（26 工具行 + 64 Ribbon）+ 标签栏 26 + 对象工具条 30；Ribbon 10 条真命令、26px 图标。**重拍抓到并修掉 4 处裁字**（网格 / 结构页 / 连接页 / 表清单：21/23px 行高仍按 6px 垂直内边距画，12px 字被切掉下半截）→ cell padding 改 `2px 8px`，新增门禁 `AppCssAuditTest.fixedRowCellsLeaveRoomForTheirText`；表清单表体去掉横线、表头保留列间竖线（`SelectionStateTest.theTableListBodyStaysPlainLikeNavicat`）。图标清单 48 → **53**（补 CHECK / OPEN / FOLDER / LOCK / ALERT）。索引弹窗 SQL 预览在 560px 高下被挤成一行（D 区失效）→ 弹窗高度 620（min 仍 560）、预览最小 72px、索引列区 220 → 200。 |
| 2026-10-25 | 第二十六轮 · P7-2 | **把 §10.4 的键盘与读屏规矩变成会红的门禁**：`TabBar` / `ObjectTabBar` 的标签原来是**纯鼠标**的 `Label`（无 `focusTraversable`、无键处理、`AccessibleRole` 用默认值）→ 补 `TAB_ITEM` + `accessibleText`（重名标签带连接限定，与可见标题同一口径）+ `←/→` 移动焦点并同步激活 + `Enter`/`Space` 激活；对象首页 / 表清单**卡片**补 `LIST_ITEM` + `requestFocus`（原来挂的是空 lambda）；网格 / 冻结网格 / 全选 / 每行复选框 / 布尔单元 / 每页下拉 / 侧栏树 / 对象清单补可朗读名称。新门禁 `AccessibilityAuditTest`(5)：逐页控件名称、`TAB_ITEM` 角色、**六区前序焦点顺序 + 状态栏零停靠点**、标签栏与切面键盘可操作。⚠️ 第一版把 `ComboBoxListViewSkin` 里的 `ListView` 判成违规（名称挂在 `ComboBox` 自己身上）——**改的是测试不是生产代码**，并把父链打进失败信息，避免下次靠猜。另加 `ThemeManagerTest`(4)：主题切换的强制 CSS/布局 pass **只在窗口已显示时**做（启动那一趟交给 `stage.show()`，实测省掉一次整场景图重算 706 ms）。**2 个变异红→改回绿**（去掉条件 → `expected: 0 but was: 1`；`shouldForcePass` 恒 false → 2 条红）；基线 **871 / 113 / 0** |
| 2026-10-24 | 第二十二轮 · S-7 | **品牌落点 + 死代码清理**：应用栏 26px 方块由通用圆柱 `Icons.Icon.DATABASE` 换成矢量 **`Icons.Icon.BRAND`**（圆柱 + 右上 AI 四角星芒，随主题换色，`#appbar-brand-glyph`）；⭐ **不可达的欢迎页删除** —— `AppShell` 构造器无条件 `openPermanent(object-home)`，那张卡片永远不会露面，连带删掉 `showWelcome()` / `brandMark()` / `BRAND_MARK` / `.adb-welcome*` 四条规则 / `Icons.SIZE_BRAND`；令牌 **99 → 98**（`fs-2xl` 20px 唯一的消费者就是这张卡的标题，**删令牌不给它硬造消费者**），字号阶梯 8 档 → **7 档**（§2.2）；⭐⭐ **像素对比抓出层叠 bug**：`app.css` 后半段的 `.adb-icon` 与应用栏那段 `.adb-appbar__mark-glyph` **同为单类**，JavaFX「同特异性后写胜」→ 品牌字形被 `text-secondary` 顶成灰字，蓝底方块里几乎看不见；规则提到两类 `.adb-appbar__mark .adb-appbar__mark-glyph`（0,2,0）才拿到 `text-inverse`。**这条不变量已写进 §4.3 第 6 条**。门禁净 **+1**（`AppBarTest` +2：品牌身份 + 计算值对比度；新 `BrandMarkAuditTest` +2；删掉测不到真路径的 `WelcomeBrandTest` −3），**5 个变异逐条红→改回绿**（M5 把选择器改回单类 → 只有对比度门禁红）；基线 **754 / 97 / 0** |
| 2026-10-24 | 第二十一轮 · UX-4 | **网格交互**：§7.3 重写选中/光标/网格线/行操作条/编辑器五排。⭐ 三条新不变量：① **`NULL` 显示 `(NULL)`**（灰斜体的 `NULL` 和一条内容为 "NULL" 的 TEXT 值在 12px 里分不出来，而两者要做的处理完全不同），空串 `""`、BLOB 补 **GB 档**，三态各有 tooltip；② **槽位常留** —— 行 `border-width: 0 0 1 2`、格 `0 1 0 2` 由基础规则留透明空槽，选中条/脏值条/光标环/矩形选区**只上色不改宽度**（改宽度就抖：键盘走一格文字左右跳一次，行高 24px 上下各吃 2px 直接压掉正文）；③ **当前格不是 `.table-cell:focused`** —— 行选模式下 `TableCell.updateFocus()` 主动清 `:focused`，该伪类在这张表上永不匹配，改由 `focusModel` 打样式类 `adb-grid__cell--cursor`、**按列名**记光标（冻结列是搬过去的同一批实例，按下标比会标不上）。列头右键收齐排序/移动/筛选/隐藏/自适应/**冻结到此列与取消冻结**，且**做不到就禁用**（最后一列冻结会偷偷少冻一列）；双击列头 = 自适应；网格左下**行操作条** `+ / − / 应用 / 取消`（状态 `bind` 工具条、只读表整排消失）；长文本走**抽屉文本页就地编辑**（Ctrl+Enter 应用、Esc 只退编辑不关抽屉、写同一个 `EditBuffer`、主键与只读表不给编辑器）。新门禁 `SelectionStateTest`(8) / `CellDisplayTest`(6) / `CellEditorTest`(4) + `GridInteractionTest` +7，**25 条门禁逐条变异全红→改回绿**（其中 `CellEditorTest` 第三条第一版是假绿：演示库主键全是 INTEGER，删掉主键规则仍绿 → 自建 `TEXT PRIMARY KEY` 表并补正例）；基线 **753 / 97 / 0** |
| 2026-10-24 | 第二十一轮 · UX-3 | **工具条与动作可发现性**：§7.1 作废「只有保存带文字，其余纯图标（Navicat 形态）」，改为**主操作 = 图标 + 文字 + tooltip + accessibleText**（`IconButtons.labeled`）+ 次要动作纯图标；工具条**语义分组 + `.adb-toolbar__separator`**（数据页 4 组 / 清单页 4 组 / 对象首页 2 组），删除动作用 `adb-button--danger`；**<1100px 自动收字、提示不丢**（量窗口宽度不是页面宽度，文字统一走登记表，裸 `setText` 会让收好的字自己长回来）；补数据页导入（预选当前表）与清单页导入/导出；§13 **V-5 结案**（用分组+收字，不做「更多」菜单）；新门禁 `ToolbarDiscoverabilityTest`(5)，**9 个变异全部红→改回绿**，其中两条抓到真问题（门禁自身空端口径、`setText` 绕过登记表）；基线 **728 / 94 / 0** |
| 2026-10-24 | 第二十一轮 · UX-2 | **密度/排版/chrome**：新增 dense 22（默认 compact）；数据面正文统一 12px；标签栏 28 / 对象头 36 / 工具栏 34 / 分页器 36 / 状态栏 24；按钮与输入 28；令牌 99（`row-dense` + `sp-1h`）；新增 `ChromeMetricsTest`（765px 高 ≥22 行） |
| 2026-10-22 | 第十三轮 | **表清单 / 密度对齐**：尺寸更新为应用栏 46 / 标签栏 36 / 工具栏 38 / 网格 24·28·34 / 树 24；新增 §7.9 清单页规格（8 列 / 白行 / 12px 表头）；§7.6 补状态栏三区版式；§2.3 补清单字号 |
| 2026-10-22 | 第十轮 | **建立本文**（`docs/UI-VISUAL-SPEC.md`）：字体 / 颜色 / 图标 / 间距 / 布局 / 组件 / 弹窗 / 交互态 / 键盘 / 文案规格 + 43 个未定义样式类等缺口清单；同时修掉 `row-tree` / `kind-index` 两个摆设令牌与 `.adb-error` 缺样式 |
