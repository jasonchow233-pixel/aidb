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

### 2.2 7 档字号阶梯（唯一真相：`tokens.json` 的 `fs-*`）

| 令牌 | 值 | 允许出现的场景 | 禁止 |
|---|---|---|---|
| `fs-2xs` | 10px | **单字符**徽标 / 角标：排序角标、脏标记、快捷键角标 | 任何成句文字 |
| `fs-xs` | 11px | 辅助信息：状态栏、列类型行、分组标题、树副标题、提示文字、chip、统计条 | 表格正文 |
| `fs-sm` | 12px | **数据面正文**：网格 / 清单 / 树 / 结构页 / 按钮 / 菜单 | 页面标题、空态标题 |
| `fs-md` | 13px | **界面基准**（`.root`）：对话框标题、非数据面的说明文字 | 数据面正文 |
| `fs-lg` | 14px | 区块标题、对话框标题 | — |
| `fs-xl` | 16px | 应用名、对象标题、空状态标题 | — |
| `fs-3xl` | 32px | 空状态图标尺寸 | — |

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

- **9px 全面废除**（`AppCssAuditTest` 断言出现 0 次）；10px 只留给单字符徽标。
- CSS 里**不得出现字面量字号**，只能引用阶梯值（门禁：`AppCssAuditTest`）。
- 字号**不随主题变化**（暗色同值）—— 主题只换颜色。

### 2.3 角色 → 字号速查（写界面时照抄）

| 界面角色 | 字号 | 样式类 |
|---|---|---|
| 页面标题 / 对象名 | 20px bold | `.adb-page__title`、`.adb-object-header__title` |
| 分组标题（侧栏 / 表格页签区） | 11px bold | `.adb-sidebar__section-title`、`.adb-section-title` |
| 表格正文 | 12px | `.adb-grid .table-cell` |
| 清单正文（对象 / 表清单） | 12px | `.adb-object-list .table-cell`、`.adb-table-list .table-cell` |
| 清单表头 | 12px bold | `.adb-object-list .column-header .label`、`.adb-table-list .column-header .label` |
| 列名 | 12px bold | `.adb-grid__column-name` |
| 列类型行 | 11px | `.adb-grid__column-type` |
| 行号 | 11px | `.adb-grid__row-number` |
| 排序角标 | 10px bold | `.adb-grid__sort-badge` |
| 状态栏 | 11px | `.adb-statusbar .label` |
| 徽标 / chip | 10–11px | `.adb-badge`、`.adb-chip`、`.adb-filter-chip` |
| 弹窗标题 | 14px bold | `.adb-dialog > .header-panel .label` |
| 弹窗按钮 | 12px | `.adb-dialog .button` |
| 空状态标题 | 16px bold | `.adb-empty__title` |
| 空状态说明 | 12px | `.adb-muted` |
| Tooltip | 11px | `.tooltip` |
| 命令面板输入 | 14px | `.adb-palette__input` |

### 2.4 字重

只有 **bold** 与 normal 两档。bold 只用在：列名、树分组名、连接名、侧栏标题、
排序角标、危险计数、页面 / 弹窗标题、批量条计数。
⛔ 不用 bold 表达「强调」以外的东西 —— 满屏 bold 等于没有强调。

### 2.5 现状缺口

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| **F-1** | 表格**数字列不右对齐**（数据页、对象清单） | 金额/行数/大小无法竖向比较 | 右对齐 + 等宽字体（`TextAlignment.RIGHT` + `.adb-grid__cell--number`） |
| **F-2** | 一个控件里可能同时出现 3 个字号（列头：名 12 bold + 类型 11 + 角标 10） | 视觉重量碎 | 规定「一个控件最多 2 个字号」：主体 + 角标 |

---

## 3. 颜色与语义

### 3.1 令牌分层（`tokens.json` 共 **97** 个）

| 层 | 数量 | 令牌 | 语义 |
|---|---|---|---|
| 背景 | 11 | `bg-canvas` `bg-sidebar` `bg-surface` `bg-subtle` `bg-elevated` `bg-inset` `bg-hover` `bg-active` `bg-selected` `bg-stripe` `bg-disabled` | canvas=页面底 · surface=条带/面板 · subtle=分区底(比 surface 低一档) · elevated=浮层(弹窗/toast/命令面板) · inset=凹陷(输入框/表头/代码区) |
| 边框 | 4 | `border` `border-subtle` `border-strong` `border-focus` | 普通分隔 / 更淡的行线 / 控件描边 / 焦点环 |
| 文字 | 6 | `text-primary` `text-secondary` `text-tertiary` `text-muted` `text-disabled` `text-inverse` | 正文 / 次要 / 三级(提示) / 弱化装饰(图标·分隔线) / 禁用 / 反色(主按钮上) |
| 强调 | 2 | `accent` `accent-hover` | 主色、悬停 |
| 风险 | 12 | `risk-0..3-{read/write/structure/admin}` + `-bg` + `-border` | **四级风险**（本项目强项） |
| 状态 | 7 | `success` `warning` `error` `info` + 各自 `-bg` | 成功/警告/错误/信息 |
| 语法高亮 | 9 | `syn-keyword/string/number/comment/function/type/ident/param/error` | SQL 编辑器 |
| 对象类型 | 7 | `kind-table` `kind-view` `kind-function` `kind-trigger` `kind-procedure` `kind-index` `kind-event` | **对象树 / 结构页的类型色** |
| 尺寸 | 34 | `h-*` `w-*` `row-*` `fs-*` `sp-*` `r-*` `dur-*` | 高度 / 宽度 / 行高 / 字号 / 间距 / 圆角 / 时长 |
| 遮罩 / 投影 | 2 | `overlay` `shadow-1` | 弹窗遮罩与**唯一一级**浮层投影（UX-1 删 `shadow-2`） |

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
| 浮层：弹窗 / toast / 命令面板 | `bg-elevated` | `text-primary` | `border` / `border-strong` |
| 输入框 / 代码区 | `bg-inset` | `text-primary` | `border-strong` |
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

| 类型 | 令牌 | 暗色 / 亮色 | 用在哪 |
|---|---|---|---|
| 表 | `kind-table` | `#4ec89a` / `#2e8b6a` | 对象树表节点图标 |
| 视图 | `kind-view` | `#a98bf0` / `#7454c8` | 对象树视图节点图标 |
| 函数 | `kind-function` | `#e0a24a` / `#9a6700` | 对象树函数节点图标 |
| 触发器 | `kind-trigger` | `#e8875f` / `#c4552e` | 对象树触发器节点图标 |
| 过程 | `kind-procedure` | `#6fb3d9` / `#2f6f9e` | 对象树过程节点图标 |
| 索引 | `kind-index` | `#9aa4b2` / `#6b7280` | **结构页「索引」页签行首图标** |

> 规则：**颜色只上在图标上，文字保持正文色**。彩色文字会让整棵树变成调色盘。

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
- 尺寸只从 `Icons.SIZE_*` 取：**14 标签 / 16 工具条 / 20 主工具条**；
  另有语义尺寸 8（状态点）/ 12（徽标）/ 32（空状态）。
  ⛔ 不许在调用点写数字（门禁：`IconSystemAuditTest.iconSizesComeFromTheNamedScale`）。
  ⛔ 40（`SIZE_BRAND`）已随 S-7 删除 —— 它唯一的消费者是不可达的欢迎页；品牌 mark 走 `SIZE_TOOLBAR`。
- 缩放时**反向补偿描边**（`STROKE / scale`）——放大后视觉粗细不变。
- 全部**描边**图标（不填充），少数状态点例外（`STAR_FILLED` / `DOT` 用 `.adb-icon--filled`）。
- 颜色由 CSS 接管（`.adb-icon` → `-adb-text-secondary`；hover → `text-primary`；
  主按钮 → `text-inverse`；禁用 → `text-disabled`），**切主题自动跟随**。
  ⚠️ `.adb-icon` 这条通用规则排在 `app.css` **后半段**：任何想脱离 `text-secondary` 的字形
  （品牌方块里的 `text-inverse` 就是第一个）**必须写两类选择器**提高特异性，单类会被后写的通用规则顶掉 ——
  见 §4.3 第 6 条。

### 4.2 图标清单（**53** 个，`io.agenticdb.ui.Icons.Icon`）

| 分类 | 图标 | 语义 |
|---|---|---|
| 通用动作 | `CLOSE` `CHECK` `ADD` `EDIT` `COPY` `DELETE` `SAVE` `REFRESH` `UNDO` `REDO` `SEARCH` `FILTER` `CLEAR` `SETTINGS` `STAR` `STAR_FILLED` `DOT` `MORE` | 关闭 / 勾选（布尔单元格、CHECK 约束）/ 新增 / 编辑 / 复制 / 删除 / 保存 / 刷新 / 撤销 / 重做 / 搜索 / 筛选 / 清除 / 设置 / 收藏 / 收藏实心 / 状态点 / **更多（⋯，页签行的溢出菜单）** |
| 导航 | `PREVIOUS` `NEXT` `FIRST` `LAST` `CHEVRON_LEFT` `CHEVRON_RIGHT` `FOLDER` | 上/下/首页/末页/左/右 / 连接分组 |
| 传输 | `EXPORT` `IMPORT` `TRANSFER` | 导出 / 导入 / 双向 |
| 执行 | `RUN` `RUN_ALL` `STOP` `EXPLAIN` | 执行 / 执行全部 / 停止 / 执行计划 |
| 对象 | `DATABASE` `TABLE` `STRUCTURE` `COLUMNS` `VIEW` `VIEW_OBJECT` `FUNCTION` `TRIGGER` `INDEX` `OPEN` | 连接 / 表 / 结构 / 列 / 视图 / 视图对象 / 函数 / 触发器 / **索引** / 打开选中对象 |
| 其它 | `CONNECT` `QUERY` `LIST` `GRID` `THEME` `PALETTE` `HISTORY` `DIAGNOSTICS` `LOCK` `ALERT` | 连接 / 查询 / 清单 / 网格 / 主题 / 命令面板 / 历史 / 诊断 / 加密连接 / 警告提示 |
| 品牌 | `BRAND` | ⭐ **AIDB mark**（数据圆柱 + 右上 AI 四角星芒）—— 只用在**「关于」弹窗的图形**；2026-10-28 顶区精简后，品牌在界面里的落点是窗口标题 + 窗口图标 + 关于框（顶栏不再有品牌块） |

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

| # | 缺口 | 影响 | 修法 |
|---|---|---|---|
| **I-1** | ~~缺 **20px 图标档**（功能栏用 16px 顶着大字标）~~ | — | ✅ **已修**（UX-1）：新增 `Icons.SIZE_PRIMARY = 20`，对象主工具条使用 |
| **I-2** | 标签栏图标 11px、侧栏 rail 16px、功能栏 16px，**三档不统一** | 同一语义在不同处大小不一致 | 固定「标签 14 / 工具栏 16 / 空态 32」三档，其余禁用 |
| **I-3** | 无应用图标 | 标签栏 / 窗口标题无品牌标识 | 出一个 32×32 应用图标 + 窗口图标 |

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
| **命令带（Ribbon）** | **64px** 单行；2026-10-28 起全局工具件并入页签行，顶区合计 **114px**（菜单 24 + 64 + 页签行 26） | `h-appbar`（`Tokens.H_APPBAR_LIGHT/DARK`）/ `.adb-ribbon` |
| Ribbon 命令按钮 | **62×58**，图标 `Icons.SIZE_RIBBON`（26）+ 11px 标签；只摆没有第二入口的 6 条命令 | `.adb-ribbon__button` / `.adb-ribbon__label` |
| 页签行（兼全局工具行） | **26px**，白底 + 1px 下边界；右侧连接 pill / 全局搜索 220px / 主题 / ⋯ | `h-tabbar` / `.adb-tabbar` / `#adb-chrome-row` / `.adb-appbar__utility` |
| 页签行紧凑形态 | 行宽 **<780px** 触发：收掉连接副标题与搜索提示词，搜索框缩为 30px 放大镜 | `.adb-appbar__utility--compact` |
| 对象标题栏 | **40px** | `.adb-object-header` |
| 对象切面条 | 随对象标题栏（40 内） | `.adb-object-tabs` |
| 工具栏（数据页） | **30px** | `h-toolbar` |
| **状态栏** | **22px** | `h-statusbar` |
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

---

## 6. 布局

### 6.1 七区骨架（2026-10-28：顶区两层版）

```
┌──────────────────────────────────────────────────────────────┐
│ 菜单栏 24px（系统原生，白底）                                   │
├──────────────────────────────────────────────────────────────┤
│ 命令带 64px：[连接][新建查询][新建表][导入/导出][已保存查询][收藏夹] │  ← chrome #F0F0F0，62×58
├────────────┬─────────────────────────────────────────────────┤
│            │ 页签行 26px：对象(常驻) · users × · 查询示例 ×     │  ← 白底 + 下边界
│            │                       ● 当前连接 ▾  [搜索 ⌘K] ◐ ⋯ │  ← 工具件靠右，同行
├────────────┼─────────────────────────────────────────────────┤
│ 侧栏 250px │ 内容区（页面自行管理加载/空/错误三态）            │  ← canvas
│ (可拖/折叠)│                                                  │
│ 收藏        │                                                  │
│ 连接（对象树）│                                                  │
│ 工作台      │                                                  │
│ 工具        │                                                  │
├────────────┴─────────────────────────────────────────────────┤
│ 状态栏 22px：方言·连接·行范围·消息 | 脏·选择·耗时·事务·只读·版本│  ← #F0F0F0 + 上边界
└──────────────────────────────────────────────────────────────┘
```

> ⭐ 2026-10-26 用户裁定「保持和 Navicat 一样」；2026-10-28 再裁定**顶区精简**（方案 B「并入标签栏」）：
> 全局顶区 **114px** = 菜单 24 + 命令带 64 + 页签行 26（原 140px = 24 + 90 双行 + 26）。
> 页面内的对象页签 26 + 对象工具条 30 仍归页面，从窗口顶到内容 ≈170px，与 Navicat 17 实测同线。
> 品牌块删除（品牌 = 窗口标题 + 窗口图标 + 关于框图形）；全局工具件（连接 / 搜索 / 主题 / ⋯）
> 与文档页签**共用一行**；命令带只摆**没有第二入口**的 6 条命令（全部经 `ActionRegistry`），
> 表 / 诊断 / 刷新 / 设置 留在菜单、侧栏、⌘K 与 ⋯ 菜单；窄窗（<780px）自动降级成图标。

| 区域 | 规则 |
|---|---|
| 菜单栏 | 9 个顶级菜单；全部项走 `ActionRegistry`；macOS 进系统菜单栏 |
| 命令带 | 6 条命令（连接 / 新建查询 / 新建表 / 导入导出 / 已保存查询 / 收藏夹），62×58；不再重复摆有第二入口的动作 |
| 页签行右侧 | 当前连接（状态点 + 切换 + 重连 / 断开）+ ⌘K 搜索 + 主题 + ⋯；行宽不足时收字收提示词 |
| 侧栏 | 搜索框在**底部**（与 ★、« 同行）；对象树满行高亮、类型色图标、右侧计数；重建后视口回顶 |
| 标签栏 | 第一个是**常驻「对象」标签**（无关闭钮）；下划线指示当前标签；脏点 `●N`；中键关闭 |
| 内容区 | 页面自己管三态；网格页与查询页各有一套骨架（见下） |
| 状态栏 | **10 段固定位置**，段间 1px 分隔线；不合并成一条长消息 |

### 6.2 网格页骨架（从上到下）

```
objectHeader（类型图标 + 表名 + 类型徽标 + 真实行数 | 刷新·结构 | 连接·可写性）
objectTabs（数据 / 结构 / 索引 / 外键 / 触发器 / SQL —— 当前切面下划线）
├ 数据切面
│   toolbar（左组：新增·撤销·重做·保存·删除 ｜ 右组：筛选·清除·列·视图·导出·搜索）
│   chips（已生效的筛选 / 排序条件，可单个删除 + 全部清除）
│   recordNav（首/上/下/末 + 记录号输入 + 「本页 N 行」）
│   grid（列头两行：列名+类型行 / 行号列 / 选择列）
│   batchBar（选中行时出现：计数 + 批量动作）
│   pager（每页档位 + 范围 + 首/上/页码/下/末 + 跳至行）
└ 其它切面
    结构 / 索引 / 外键 / 触发器 / SQL（真实内省结果，只读）
```

> ⚠️ 切面切换**不丢数据页的状态**：编辑缓冲、分页位置、滚动位置都保留在原页面上。
> 切面内容是**真实内省结果**（`SchemaService.structure`），不是静态示例。
> 读不到时明确显示「读取结构失败：…」而不是空白表格。

### 6.3 查询页骨架

```
header（连接标识 + 风险徽标）
toolbar（执行 / 全部 / 解释 / 格式化 / 保存 / 变量 / 历史）
findBar（查找替换，内嵌在编辑器上方，不弹窗）
editor（RichTextFX：行号 + 当前行 + 高亮 + 括号配对）
split（可拖分隔条）→ results（结果集 / 消息 / 错误 三个页签）
statusbar（执行摘要）
```

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
  清单页「对象动作 | 表操作 | 数据交换 | 视图」、对象首页「查看 | 编辑」。
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
| 文本框 | `bg-elevated` 底 + `border-strong` 描边 + 3px 圆角 + `4px 8px`；focus 只换描边色（**不改宽度**，避免抖 1px） |
| 下拉 / 复选 / 数字 | 与文本框同一套（`.combo-box` / `.check-box` / `.spinner` 已在 CSS 统一） |
| 禁用 | `bg-disabled` + `text-disabled` + 保留边框 + **opacity 还原为 1** |
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
| 行号列 | 宽 52，11px；脏行号加粗变色（黄/绿/红） |
| 选择列 | 宽 36，表头全选**只作用于当前页** |
| 正文 | 12px，`4px 8px`（dense `2px 6px`）；NULL 斜体灰、空串浅灰、枚举绿粗体、JSON 蓝等宽、数字等宽 |
| **空值三态** | ⭐ **`NULL` 显示 `(NULL)`**，不是 `NULL`。带括号的原因：TEXT 列里真的会出现**内容为 "NULL" 的字符串**（CSV 导入、日志表、外部系统写的），灰斜体的 `NULL` 和一条值为 NULL 的文本在 12px 网格里分不出来，而这两种情况要做的处理完全不同。空串显示 `""`，BLOB 显示大小（补 **GB 档**，不显示 `1536.0 MB`）。三者各有 tooltip |
| 斑马纹 | 奇数行 `bg-stripe`（亮 `#F9F9F9` / 暗 `rgba(148,163,184,.035)`）—— **网格三层里最轻的一层**，必须轻于选中 tint 和 hover。⚠️ 实现上条纹档**只许带 `:odd` 一个伪类**：多带一个 `:filled` 就比 `:hover` / `:selected` / `--deleted` / `--new` 重一档，会反过来把行态底色整条压掉，且**只有奇数行错**（第三十一轮实拍抓到，门禁 `SelectionStateTest.stateRulesOutweighTheZebraStripeInEveryTableScope`） |
| 网格线 | 横线 + 竖线都是 `border-subtle` 1px（Navicat 17 实测：列边界 `#F0F0F0`）。**宽度写在基础规则里，状态规则只改颜色**（见下方「槽位常留」） |
| 选中 | `bg-selected-grid`（Navicat 实测 `#C4E0F7`）+ 正文色字（不蓝字）+ **左侧 2px `accent` 强调条**；`hover` 规则排在 `selected` **之前**，悬停已选行仍是选中色 |
| 当前格（键盘光标） | ⭐ **不是 `.table-cell:focused`** —— 行选模式下 `TableCell.updateFocus()` 会主动清掉 `:focused`，那条伪类在这张表上永远不匹配。由 `DataGridPage` 盯 `focusModel` 打样式类 `adb-grid__cell--cursor`：**内描边 `border-focus` + `bg-active` 淡底**，只有左右两条边（行高 23px，上下再吃 4px 就把正文挤没）。光标**按列名**记，不按 `TableColumn` 实例记（冻结区的列是从右表搬过去的同一批实例） |
| 脏单元格 | 左侧 2px 黄条 + `warning-bg` |
| 新增行 / 删除行 | 绿底 / 红底（删除行文字降为 tertiary） |
| 行操作条 | 网格左下 `+ / − / 应用 / 取消`，**纯图标不加文字**（贴着光标，越短越不挡视线）。可用态**绑定**工具条同一批按钮，不重写判断；只读表**整排消失**（一排永远点不动的按钮只会引人反复试） |
| 编辑器 | 格内单行输入：3px 圆角 + `border-focus` 描边，出错转 `error`。⭐ 长文本 / JSON / CLOB 走**详情抽屉文本页就地编辑**（不是弹窗）：回填**原始值**、**Ctrl+Enter 应用**（多行编辑器里回车是换行，不能拿来提交）、**Esc 只放弃本次编辑不关抽屉**、**切页签同样放弃编辑**（提交目标在进编辑态时钉死，绝不随当前页变 —— 否则「文本页敲字 → 切 JSON 页 → 应用」会把美化过的 JSON 整段写进 TEXT 列）、写进**同一个 `EditBuffer`**；主键列与只读表不出现编辑器 |

> ⭐ **槽位常留（UX-4 的排版不变量）**：行 `border-width: 0 0 1 2`、单元格 `0 1 0 2` ——
> 左侧 2px、右侧 1px 由**基础规则**留成透明空槽，选中条 / 脏值条 / 光标环 / 矩形选区
> 这些状态规则**只上色、不改宽度**。改宽度就会抖：键盘走一格、文字左右跳一次；
> 行高 23px 里上下各吃 2px 直接压掉一行正文。
> 门禁：`SelectionStateTest.selectingARowNeverMovesItsCells`（实测每个单元格的 x/y 与行高都不变）。


### 7.4 对象树

| 元素 | 规格 |
|---|---|
| 行高 | 21px（`row-tree` / `RowHeights.of(normal)`；dense 19） |
| 选中 | **满行高亮**（`bg-selected`），不是圆角胶囊 |
| 类型色 | 图标着色（`kind-*`），文字保持正文色 |
| 计数 | 分组右侧 11px tertiary（「表 12」） |
| 收藏 | 11px 黄星（`risk-1-write`） |
| 折叠 | rail 32×32 图标按钮 |

### 7.5 标签栏

- 常驻「对象」标签：永远排第一、**没有关闭钮**（不是隐藏，是不放进场景图）、右键菜单无「关闭」。
- 普通标签：类型图标 + 标题（12px）+ 脏点（10px 黄）+ 关闭钮（10px，hover 显现）。
- 活动标签：底部 2px `accent` 边 + 标题转 `text-primary`。
- 中键关闭；关闭脏标签要确认。

### 7.6 状态栏（10 段）

`方言 · 连接 · 行范围 · 消息 ‖ 脏计数 · 选中 · 耗时 · 事务 · 只读 · 版本`

> **三区版式（2026-10）**：左 = 方言 / 连接 / 行范围 / 消息；中 = 选中 / 耗时；
> 右 = 事务 / 只读 / 服务器 / 版本。左右各一个弹性空隙，中间段始终落在窗口正中。

| 段 | 内容 | 措辞规则 |
|---|---|---|
| 方言 | SQLite / PostgreSQL / MySQL | pill 形（`r-full` + 描边） |
| 连接 | 连接名（+ 对象数） | — |
| 行范围 | `1-200 / 共 500` | **只说范围，不带耗时**（耗时另有字段，否则重复） |
| 消息 | 「就绪」/ 操作结果 | 占 message 位 |
| 脏计数 | `● 3 待提交` | 有脏才出现 |
| 选中 | `已选 12 行` / `未选择` | — |
| 耗时 | `12 ms` | **唯一出现耗时的地方** |
| 事务 | `事务中` / `自动提交` | 真实事务状态 |
| 只读 | `只读` / `可写` | — |
| 版本 | `v1.0.0` | **来自构建产物，不硬编码**（当前仍是硬编码 → 缺口 X-1） |

### 7.7 反馈

| 载体 | 用途 | 规则 |
|---|---|---|
| **Toast** | 操作成功 / 失败 / 可撤销 | 三档时长：普通 1.8s · 带撤销 6s · 结果类 4s（`dur-toast*`） |
| **状态栏** | 持续状态（连接、范围、事务） | 不用 Toast 承载状态 |
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

### 7.9 清单页（对象首页 / 表清单）

| 元素 | 规格 |
|---|---|
| 列（表清单） | 名称 / 行 / 数据长度 / 引擎 / 创建日期 / 修改日期 / 排序规则 / 注释 |
| 列（对象首页） | 名称 / **类型** / 行数 / 数据长度 / 引擎 / 创建时间 / 修改时间 / 排序规则 / 注释 |
| 行高 | 19 / 20 / **21** / 26（跟随密度令牌，默认标准 = Navicat 21） |
| 表体 | 12px；**纯白行、无横线、无斑马纹**（Navicat 17 实测）；列分隔由表头竖线承担 |
| 表头 | 12px bold，`text-secondary`，`bg-surface`；列间 1px `border-subtle` 竖线 |
| 空行 | **不画线、不铺色**（避免看起来像还有数据） |
| 数字列 | 右对齐 + **按数值排序**（不按字符串） |
| 缺失元数据 | 显示「—」，不编造 SQLite 拿不到的值 |
| 交互 | 双击 / Enter 打开；搜索实时过滤；刷新在后台线程 |

> 实拍对照见 `docs/screenshots/p*.png`（由 `scripts/shots.sh` 按当前代码重拍）；像素测量的原始台账属内部材料，不随公开仓库发布。

---

## 8. 弹窗规范

### 8.1 形态判定

见 `docs/UI-FORMS-AND-STATES.md` §2：**改已存在的东西 → 弹窗**；
**要长期停留 / 并排比较 / 有状态 → 标签页**；**不确定 → 弹窗**。

### 8.2 骨架（三段式）

```
┌─ 标题区（bg-surface + 下边框，14px bold）──────────────────┐
│  标题                                                  [×] │
├─ 表单区（bg-canvas）──────────────────────────────────────┤
│  字段 / 表格 / 预览                                     │
│  （常驻错误行）                                          │
├─ 按钮区（bg-surface + 上边框）───────────────────────────┤
│                              [取消]  [确定]                │
└──────────────────────────────────────────────────────────┘
```

对应 CSS：`.adb-dialog > .header-panel` / `.adb-dialog > .content` / `.adb-dialog > .button-bar > .container`。

### 8.3 按钮区规则

1. **顺序**：JavaFX `ButtonBar` 的默认顺序是「取消在左、主操作在右」（macOS 习惯是主操作在左，
   本项目统一用 JavaFX 默认，**全应用一致**比跟随平台更重要）。
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
DialogStyles.apply(dialog, owner);   // 样式表 + 主题类 + adb-dialog + 屏幕限高
dialog.initOwner(window);             // 没有 owner 会跑到屏幕中心、可能被主窗盖住
```

> ⭐ **为什么必须有 `DialogStyles`**：JavaFX 的 looked-up color（`-adb-*`）定义在
> `.theme-light` / `.theme-dark` **类选择器**上，而主题类挂在**主场景根节点**上。
> 弹窗有自己的 Scene 与根节点（DialogPane），那个根节点上没有主题类 ——
> 于是弹窗里所有 `-adb-*` 查找全部落空，弹窗退回 JavaFX 默认皮肤：
> 没有边框、没有标题分隔、按钮全是默认灰钮（实测截图）。
>
> `DialogStyles.apply` 做三件事：
> 1. 把 owner 场景的样式表挂到 DialogPane；
> 2. 把主题类（`theme-light` / `theme-dark`）**抄**到 DialogPane 的 style class；
> 3. 按屏幕高度限高（弹窗比屏幕还高 → 底部按钮在屏幕外，用户点不到）。
>
> ⛔ 不许再手写 `getDialogPane().getStylesheets().addAll(...)` —— 那样只解决第 1 步。

### 8.6 弹窗清单

| 弹窗 | 触发 | 形态 | 默认按钮 |
|---|---|---|---|
| 新建连接向导（三步） | 功能栏 / 侧栏 / ⌘N | 弹窗（内容区导航） | 保存（校验不过 consume） |
| 新建表（T1） | 树右键 / 数据库菜单 / ⌘K | 弹窗 + **六页设计器**（字段 / 索引 / 外键 / 触发器 / 选项 / SQL 预览），字段与三类对象同一计划 | 创建（取消为默认） |
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
| 输入框 | elevated + `border-strong` | — | — | 描边换 `border-focus`（不改宽度） | `bg-disabled` | — | — |
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
本会话（连接）· 条件（筛选条件）· 片段（已保存查询）。

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

覆盖：数据网格 · 查询结果网格 · 表清单 · 对象首页 · 结构页四张表 · 对象树
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
| **向导（5 个）** | `adb-wizard__content`（导入/导出/收藏/已保存查询/传输入口） | 五个向导内容区无统一留白与底色 | 🟡 P2 |
| **表单错误** | `adb-error` | **校验错误文字不是红色** —— ✅ 本轮已补 `.adb-error`（R-3）；`adb-form` / `adb-form__label` 仍缺 | 🔴 P1 |
| 详情抽屉 | `adb-details__meta` | 元数据区无样式 | 🟢 P3 |
| 状态栏分段 | `adb-status__connection` `__range` `__selection` `__duration` `__transaction` | 分段无独立样式（靠继承，问题不大） | 🟢 P3 |
| 数据页 | `adb-grid__jump` `adb-grid__number-column` | 跳页输入框/数字列无专属样式 | 🟡 P2 |
| 侧栏 | `adb-sidebar__bottom` `adb-sidebar__connection` `adb-tree-cell--object` `adb-icon-button--active` | **图标按钮的「激活态」没有样式**（例如筛选激活） | 🟡 P2 |
| 对象首页 | `adb-object-home` `adb-table-list` | 容器类无样式 | 🟢 P3 |
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
| **V-7** | ~~缺 20px 图标档；图标尺寸三档不统一~~ | — | ✅ **已修**：六个尺寸常量（8/12/14/16/32/40），调用点全部改引用 |
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
| `AppCssAuditTest` | CSS 内无字面量字号·padding·radius；字号 7 档、圆角 3 档、间距 `0/2/4/6/8/12/16/24/32`；`9px` 0 次；数据面正文必须 12px；**固定行高下 12px 正文 + 上下 padding 不得超行高** |
| `FocusRingAuditTest` | 每个可聚焦组件类都有 `:focused`；`-fx-opacity` 只 1 处；禁用态抵消 Modena 透明度 |
| **`AccessibilityAuditTest`**（✅ 第二十六轮 P7-2 新增） | ① 逐页（首页 / 数据页 / 查询页）遍历**可见交互控件**，每个都要有可朗读名称或自身文字 ② 标签与对象切面必须是 `TAB_ITEM` ③ **六区焦点顺序**（前序：菜单→功能栏→侧栏→标签栏→内容区→状态栏；状态栏**零**停靠点，每区 ≥1）④ 标签栏 `Enter` + `→` 键盘可用 ⑤ 切面标签键盘可用 |
| **`ThemeManagerTest`**（✅ 第二十六轮 P7-1 新增） | 主题切换只在**窗口已显示**时做强制 CSS/布局 pass（未显示时交给 `stage.show()`，别把整棵场景图算两遍）；两套主题 + 业务 CSS 常驻样式表；根节点必须带 `theme-light`/`theme-dark` 类 |
| `DensityCoverageTest` | 密度四档同时改数据网格 / 查询结果 / 清单 / 结构页的行高；未知档回落 compact |
| **`ChromeMetricsTest`** | 标签栏 26 / 对象头 40 / 页工具条 30 / 分页器 38 / 状态栏 22；dense 网格行高 21；1268×765 可见 ≥22 行 |
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
