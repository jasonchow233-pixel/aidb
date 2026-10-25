# NEXT-STEPS — 下一阶段开发规划

> 定位：**「接下来做什么、按什么顺序、做到什么程度算完」**。
> `NAVICAT-TODO.md` 是唯一执行清单（逐条勾选）；本文件是**阶段规划**（批次 + 依赖 + 门禁）。
> **界面专项的详细分析与规范见 `docs/UI-ROADMAP.md`**。
>
> 最后更新：**2026-10-25（第二十七轮：任务书 V1.0 落地 —— U-ICON（Lucide 矢量图标 + 对象/状态语义彩色）、U-CTRL（五档按钮 × 五态与控件统一）、U-IDX（§六 新建索引弹窗重建）、U-POLISH（弹窗骨架统一 + 6 处漏套主题的弹窗，基线 913/116）**

---

## 0. 当前位置（2026-10-25 · 第二十七轮 · Windows 10 + JDK 27）

| 指标 | 值 |
|---|---|
| 门禁 | **913 条 / 116 类 / 0 失败 / 48 跳过**（`./scripts/build.sh`）。**口径说明**：871 → 913 的 **+42 / +3 类** 全部来自本轮任务书 V1.0（U-ICON `LucideIconTest` + U-CTRL `UnifiedControlTest` 13 + U-IDX `IndexDialogTest` 13 + `DialectTest` / `IndexDdlTest` 新例 + U-POLISH `DialogStylesTest` 弹窗覆盖审计）；**48 条环境门控跳过**（本机无 Docker）。**硬门禁是「0 失败」，不是总数**。 |
| 真库验证 | ✅ SQLite（演示库）· ✅ **MySQL 5.7.44 真库**（9 例全绿）· ✅ **PostgreSQL 16 + MySQL 8.4 Testcontainers**（11 例全绿；抓出 2 个跨方言 bug，见 `NAVICAT-TODO.md` §26）—— ⚠️ 本机 Docker 不可用，后两类**本轮未执行**（跳过），改回环境即可复跑 |
| 截图 | `docs/screenshots/` **15 张**（本轮全量重拍，1280×800 窗口）；`p7-new-index` 现在带 `ADB_INDEX_POSE`，拍到的是「已选两列 + 序号 + 预览 SQL + 移动钮真实态」那一帧 |
| 工作区 | declarations 删除（`c7375d4`）、UX-0…UX-5 与 S-7 均已提交；**P2 / P5 / P6 / UX-6 / P7 / 本轮任务书 V1.0 全部在工作区，未提交** |
| **下一动作** | **代码主线与 P7 均可交付的部分已做完**。剩下的全是「需要外部资源或跨大改」的长期项：**冷启动 < 2s**（地板 2.4 s：Toolkit + Modena + 首趟 CSS pass，见 `NAVICAT-TODO.md` §35.1）、**jlink**（等 RichTextFX/ControlFX 模块化）、**签名与公证**（要证书）、**自动更新**（要先有发布渠道） |

> 🔴 **第六轮全量核查改变了优先级排序**：门禁 443 条全绿，但逐文件核对发现
> **4 个真实缺陷**（其中 D2「改列抹注释」与 D3「改列丢 CHECK」会**丢数据且无撤销**），
> 全部活在测试没有覆盖的路径上。**先修缺陷，再谈新功能。**

### 最近几轮做完了什么

| 轮次 / 批次 | 内容 | 关键结果 |
|---|---|---|
| **第二十七轮 · 任务书 V1.0（2026-10-25）** | **U-ICON / U-CTRL / U-IDX / U-POLISH**（`UI-ROADMAP.md` §13） | **U-ICON**：Lucide 官方 SVG 48 枚内嵌（v1.61.0 / ISC），新 `LucideConverter` 把弧命令与 `circle/rect/polygon` 展开成 JavaFX `SVGPath` 的绝对 `M L C Z`（24→16 视框、描边统一 1.5px），升级图标＝换 SVG 文件；**动作图标单色、只有对象类型与连接状态语义彩色**。**U-CTRL**：五档按钮 × 五态一次配齐（⭐ 实心变体补**独立 `:pressed` 且必须排在 `:hover` 之后**，否则按住看到的还是悬停色、primary 白字压浅蓝底直接糊），复选框 16px 方框四态、下拉/列表/输入统一 `h-input=28` + 12px 正文，**变体必须同时挂基础类**（抓出连接页孤儿主按钮）。**U-IDX**：按 §6.2 五区重建「新建索引」弹窗（真实计数 `可用列 N / 索引列 N`、序号 `1. 2.`、双击与 ↵/Delete/Alt+↑↓ 与按钮走同一批方法、可折叠等宽预览与执行同源），新 `DialogSizes.PICKER=800` × 560 可缩放；索引类型与每列 `DESC` **只给 PostgreSQL**（`Dialect` 三个 default 能力方法 + `indexTypeClause` 白名单，`btree; DROP TABLE users` 返回空串），`IndexDef` 加两分量但**保留 4 参构造器**，默认路径 SQL **逐字节不变**。⭐ **U-POLISH**：`adb-new-table__*` 提升为共享 `.adb-dialog__*` 骨架，巡查抓出 **6 处弹窗没套主题**（含 `AppShell.showInfo` 手写「半套」—— 挂样式表加 `adb-dialog` 却漏抄主题类），补一条按文件计数的静态门禁（**先剥行注释**，否则注释掉的 apply 照样算数：第一版就是这么假绿的）；标题栏去掉重复方言（实拍 `SQLite · 文件 · SQLite`）、`FakeDbSession.label()` 改与真实会话同型；`p7-new-index` 加 `ADB_INDEX_POSE` 拍到真实交互帧。基线 **913 / 116 / 0 / 48 跳过**（+42 / +3 类）；**变异验证**：注掉 `TableDdl` 一处 apply → 红、还原 → 绿 |
|---|---|---|
| **第二十六轮 · P7 收尾（2026-10-25）** | **启动可观测性 + 可访问性 + 分发裁定**（`NAVICAT-TODO.md` §35） | ① 新 `StartupTrace`：进程启动 → 首屏分段打点，一行摘要落 **`~/.agenticdb/logs/startup.log`** +「关于」回显本次耗时（测试 JVM `-Dadb.startup.log=false` 不落盘）。⚠️ 首版漏 `scene` 打点，中段 676 ms 无法归因 → 补标记 + **一次性探针实测**：第一个 JavaFX 控件 **515 ms**、第二个 **2 ms**（Modena 样式表 + 隐式窗口的一次性成本）；`stage.show()` 首趟 CSS/布局 **860 ms**；`DemoDatabase.ensure` 468 ms。`ThemeManager` 的强制 CSS/布局 pass 改为**只在窗口已显示时**做（`theme` 718→2 ms，总时长不变、不再重复算）。**❌ 冷启动 <2s 未达标（实测 3.5–3.9 s），地板 ≈2.4 s**；**AppCDS 试过不通**（缺归档即报错退出、`-Xlog:cds+dyn` 本 JDK 无效、dump 报 `archived full module graph is not used`，失败那几次首屏 15 s）。② 可访问性：标签栏 / 对象切面从**纯鼠标**升级为 `TAB_ITEM` + `←/→` + `Enter/Space`，卡片补 `LIST_ITEM` 与 `requestFocus`，网格 / 复选框 / 下拉 / 树 / 清单补可朗读名称；新 `AccessibilityAuditTest`(5) 守六区焦点顺序（状态栏不设停靠点）。③ 裁定：jlink ⛔（自动模块）· 自动更新 🚫 不做（无发布渠道 + 离线环境 + 无签名）· 签名/公证 ⛔ 缺证书 · §9 里 A18/A19/A22 三行**早已落地却挂着未勾** → 回填。门禁 **871 / 113 / 0 / 48 跳过**（+14 / +3 类）；**变异验证**：去掉 `shouldForcePass` 条件 → `expected: 0 but was: 1`；恒 false → 2 条红；改回 63 条全绿 |
| **第二十五轮 · 收官（2026-10-25）** | **P2-3 / P5-1 / P5-3 / P5-4 / P6-1 / P6-2 / P6-3 / UX-6**（`NAVICAT-TODO.md` §34.2 / §34.3） | **P5-1** 连接分组（`group` 不进连接参数）+ 重命名只改显示名 + 分组列/搜索/侧栏文件夹；**P5-3** 新 `ConnectionFailureTest` 7 条守会话边界（打开失败不注册 / 可重试 / 错误分类 / 超时 / 取消 / 断线重开 / 未知失败保留驱动原文）；**P5-4** 序列 + `getExportedKeys`（复合外键保序）+ DEFAULT/COMMENT/CHECK，⭐ 修掉 PG **改列静默 DROP DEFAULT**；**P2-3** 历史带连接/命中表/时间（旧格式兼容），数据页 / 详情面板 / 概览三处共用；**P6-1** 导入按目标列类型转换（中文日期格式、空值→NULL、文本逐字保留）；**P6-2** `exportSql` + 查询页三档导出（本页/选中/全部）；**P6-3** POI SXSSF 写 + SAX 读，CSV/xlsx 共用 `Ingest`；**UX-6** 对象首页网格、结构页「选项」、数据页「概览」、侧栏「已保存查询」分组。门禁 **857 / 110 / 0 / 48 跳过**（+50 = 本批 49 + 最终布局守卫 1）；4 个 UX-6 变异红→绿 + 详情布局变异红→绿；⚠️ 概览初版 15px 被 `AppCssAuditTest` 拦下 → 16px；截图收尾修掉筛选标签截断 / 概览动态 id / `p2-details` chrome 挤出三处 |
| **第二十四轮 · P5-2（2026-10-25）** | **Windows 系统凭据**（`NAVICAT-TODO.md` §34.3） | 新增 `WindowsCredentialStore`，**JNA core** 直连 `advapi32!CredWriteW/CredReadW/CredDeleteW/CredFree`（`jna-platform` 无 WinCred 绑定）；`CREDENTIAL` 按 x64 偏移声明，条目 `io.agenticdb.connection/<profileId>`，blob **UTF-16LE**、1280 字符上限**前置拒绝**、写入 `Memory` finally `clear()`；`platform()` 先判 Windows、DLL 失败 `LinkageError` 降级内存，`supported()` 不在非 Windows 触发 JNA 加载。文案去掉「钥匙串 / Secret Service」（UI-AUDIT L5）。门禁 **+9 / 类 +1**，**4 个变异红→绿**（分派 / 编码 → 4 红 / 上限 / target 前缀）；Windows 10 + JDK 27 **8/8 真跑**；截图复核另修 UX-3 遗留（清单工具条文字被压成省略号 → 五个文字按钮 `minWidth(USE_PREF_SIZE)`、搜索框 200→160，`TableListPageTest` +1，变异红→绿）；基线 **807 / 104 / 0** |
| **第二十三轮 · P2-2（2026-10-25）** | **错误可诊断 + 批量汇总通用化**（`NAVICAT-TODO.md` §34.3） | 新增纯模型 `ErrorDetails`（8 类分类 / 原因 / 建议 / **整条异常链的驱动原文** / SQL）与 `BatchSummary`（**失败数与明细行数分离** —— 导入只留前 50 条坏行，标题仍报真实失败数），配套两个弹窗（分类徽标 / 可复制正文 / 无 owner 静默跳过）；接进**四处**：数据页保存·撤销·导出、查询错误页签（`#query-error-details`）、批量删表（替换临时 Alert）、导入结果（仅有坏行/跳过时弹）。门禁 **+17 / 类 +3**，**5 个变异红→绿**；新增 `p2-error` 截图（14 张）；⚠️ `StyleClassCoverageTest` 全量构建抓到 `adb-error-details` 缺 CSS，TextArea 背景须走 `-fx-control-inner-background`；基线 **798 / 103 / 0** |
| **第二十二轮 · P2-1（2026-10-25）** | **清单可读性**（`NAVICAT-TODO.md` §34.3） | `TableMeta.sizeText` 补 **GB 档** + 1 位小数 + `Locale.ROOT`（GB 级表不再显示 `1536.0 MB`），真 MySQL 补格式正则；新增共享 `ColumnLayoutMemory` —— 表清单 / 对象首页按**连接**记住列宽（列身份用 `userData`、400ms 防抖、上限 1600px、无记忆的连接回默认宽，对象首页在 `render()` 按当前连接恢复）；注释列长文本 **tooltip**（`—` 不挂、cell 复用清旧值）。门禁 **+8**（`TableMetaTest` 6→10、`TableListPageTest` 20→22、`ObjectHomePageTest` 10→12），**3 个变异红→绿**；⚠️ headless 下拉宽 stage 会 `BufferOverflowException` 并污染同 JVM 后续用例 → 改 `scrollToColumn`；基线 **781 / 100 / 0** |
| **第二十二轮 · UX-5（2026-10-24）** | **侧栏 / 标签 / 状态栏**（`NAVICAT-TODO.md` §34.2） | 三处同一套语义（连接限定与状态回显）；补启动开关 `ADB_CELL` + 唯一一帧交互态 `p2-cell`（选中行 + 光标格 + 抽屉编辑器），并配门禁守「帧还在 / 开关真被读」；基线 **773 / 100 / 0** |
| **第二十二轮 · S-7（2026-10-24）** | **品牌落点 + 死代码清理**（裁定＝**接 + 删**，`NAVICAT-TODO.md` §34.2） | 应用栏 26px 方块换成新矢量 `Icons.Icon.BRAND`（圆柱 + AI 四角星芒）；不可达的欢迎页整条路径删净（`showWelcome()` / `brandMark()` / `BRAND_MARK` / `AppShell.tabClosed` 的 `else` / `.adb-welcome*` / `Icons.SIZE_BRAND`），连带删掉失去消费者的 `fs-2xl` 令牌（99→**98**、字号 8→**7 档**）。⭐⭐ **像素对比抓出真 bug**：`.adb-icon`（`app.css` 后半段）与 `.adb-appbar__mark-glyph` 同为单类，JavaFX「同特异性后写胜」把白字顶成灰字 —— **品牌方块里的 mark 一直几乎看不见，而门禁全绿了三轮**；提到两类选择器 + 一条读**计算值**的门禁（`theBrandGlyphKeepsItsContrastAgainstTheGenericIconRule`）。⚠️ 该门禁成立的前提是测试 `@Start` **把两套样式表挂上场景**，否则 `getStroke()` 只是 `Shape` 默认色，断言等于没测。**5 个变异逐条红→改回绿**；基线 **754 / 97 / 0** |
| **第二十一轮 · UX-4（2026-10-24）** | **网格交互**（`NAVICAT-TODO.md` §34.2） | 选中行 = 强 tint + **左侧 2px 强调条**；当前格 = **应用驱动的样式类** `adb-grid__cell--cursor`（⭐ 实测 `.table-cell:focused` 在行选模式下**永不匹配**，`TableCell.updateFocus()` 自己把焦点清了），且**按列名**记光标（冻结列是搬过去的同一批实例）；⭐ **槽位常留**——行 `0 0 1 2` / 格 `0 1 0 2` 由基础规则留透明空槽，选中/脏值/光标/矩形选区**只上色不改宽度**，改宽度会让文字随键盘左右跳；单元格补淡竖线、斑马纹再降一档；列头右键收齐排序/移动/筛选/隐藏/自适应/**冻结到此列与取消冻结**（跨冻结区按 `dataColumnsOrdered()` 算下标，**做不到就禁用**）；双击列头 = 自适应；网格左下**行操作条** `+ / − / 应用 / 取消`（状态 `bind` 工具条这一批，只读表整排消失）+ 新增 `discardChanges()`；`NULL` 显示 **`(NULL)`**（与内容为 "NULL" 的文本分开）、BLOB 补 **GB 档**；长文本走**抽屉文本页就地编辑**（Ctrl+Enter 应用 / Esc 只退编辑 / 写同一个 `EditBuffer` / 主键与只读表不给编辑器）；**25 条新门禁逐条变异全红→改回绿**，其中 `CellEditorTest` 第三条**第一版假绿**（演示库主键全是 INTEGER，删掉主键规则仍绿）→ 自建 `TEXT PRIMARY KEY` 表 + 补正例；基线 **753 / 97 / 0** |
| **第二十一轮 · UX-3（2026-10-24）** | **工具条与动作可发现性**（`NAVICAT-TODO.md` §34.2） | `IconButtons.labeled(...)` 让主操作**图标 + 文字 + tooltip + accessibleText** 一次给全；数据页 6 颗主操作带文字、删除标 danger、三页工具条**语义分组 + 竖线**；**窗口 <1100px 自动收字**（提示不丢）；补数据页**导入**（预选当前表）与清单页**导入/导出**；**9 个变异全部红→改回绿**；新门禁 `ToolbarDiscoverabilityTest`(5) + 清单/对象首页 +3；基线 **728 / 94 / 0** |
| **第二十一轮 · UX-2（2026-10-24）** | **密度、排版与 chrome**（`NAVICAT-TODO.md` §34.2） | 密度从三档变**四档**：新增 `dense = 22px`，**默认改 `compact`**（24px）；数据面正文统一 **12px**（网格 / 清单 / 树 / 结构页 / 按钮 / 弹窗按钮）；chrome 收薄——标签栏 36→**28**、对象头 44→**36**、页工具条 44→**34**、分页器 44→**36**、状态栏 26→**24**、按钮与输入 32→**28**；dense 单元格内边距 `2 6`（新增 `sp-1h` 令牌，间距规则登记例外）；令牌 **97→99**；`DensityCoverageTest` 改四档 × 五处，新增 `ChromeMetricsTest`（框架高度 + **1268×765 可见 ≥22 行**）；**5 个变异全部验证**；基线 **720 / 93 / 0** |
| **第二十一轮 · UX-1（2026-10-24）** | **视觉令牌基线**（`NAVICAT-TODO.md` §34.2） | 令牌 96→97：新增 `bg-subtle` / `border-subtle` / `r-xs`，删 `r-xl` / `shadow-2`；圆角收敛 3/4/6/8（连接状态去胶囊）；卡片/详情/对话框去投影，只留 `shadow-1`；网格线与分区底改用 subtle 层；图标尺寸三档 14/16/20（`SIZE_PRIMARY`）；两条新门禁 + **4 个变异全部验证**；基线 **716 / 92 / 0** |
| **第二十一轮 · UX-0（2026-10-24）** | **截图与偏好可复现**（`NAVICAT-TODO.md` §34.2） | `ADB_PREFS_NODE` / `ADB_PREFS_RESET` 隔离并清理截图偏好（仅显式节点名才清）；`ADB_DENSITY` 优先且**不写回**；`shots.sh` 钉住密度/节点/重置/演示连接；**`p0-shell` 连跑两次同 SHA-256**（50184 字节）；两条门禁均变异验证；基线 **714 / 92 / 0** |
| **第二十一轮 · 执行（2026-10-24）** | **删除 `declarations` 声明层** | 模块 + 根目录 YAML + 父 POM module/dependencyManagement + `snakeyaml`/`json-schema-validator` + `adb-jdbc` 测试依赖 + `DeclaredDialectConsistencyTest` 全部移除；基线 **755→712 / 95→92**，属主动减面 |
| **第二十一轮 · 规划（2026-10-24）** | **UIUX 升级（UX-0…UX-6）+ P2/P5/P6 收尾**（`NAVICAT-TODO.md` §34 / `UI-ROADMAP.md` §12） | 两张新 Navicat 实拍逐张量差：行高 22 vs 34、正文 12 vs 13、工具条图标+文字 vs 纯图标、无单元格竖线、选中态弱、圆角 3–4 vs 6。裁定：P2/P5/P6 全做、`declarations` 删除（-43 条测试）、C4 密度做、WinCred 用 JNA、xlsx 用 POI。**先 UX-0 钉密度/主题，否则截图不可复现** |
| **第二十轮 · Windows 实机** | **S-8 门禁假绿 + S-9 对象工具条 + S-6 可读列宽 + D-1 主键可空性 + Q-1/Q-2 测试平台假设**（`NAVICAT-TODO.md` §33） | ⭐ 第一次在 Windows 跑全量门禁，发现 **5 个静态审计用 `/` 过滤 `\` 路径**：方言审计把全部合法文件报成违规，另外 4 个**一个文件没扫却显示绿** → 路径归一化 + **「扫到 0 个文件必须红」下限断言**（这条比归一化更重要）；对象首页补「打开/设计/新建/删除」工具条与详情联动；三张表 `CONSTRAINED_FLEX_LAST` + `ColumnWidths` 可读下限（CJK 算 2 单位、列头装饰计入）—— **先用探针测出约束策略本来就尊重 minWidth**（宽屏填满 / 窄屏卡住下限 + 横向滚动），因此否决了会留白屏的 `UNCONSTRAINED`；SQLite `INTEGER PRIMARY KEY` 按 rowid 别名判非空（TEXT PK 仍可空）；**5 个变异 M1–M5 全部验证**，门禁 **755 / 95 类 / 0 失败** |
| **第十九轮 · §29 批次 A** | **S-1 方言感知列 + S-2 卡片去破折号**（同一根因：清单页把「没有数据」渲染成标点）；新增共享纯助手 `MetadataColumns` | ⭐ 判定**看数据不看方言**（无需给页面塞 `Dialect`）：只有**每一行都是占位符**时整列隐藏，有一行有真值就留着（混合场景 MySQL 表有引擎/视图没有 → 引擎列保留）；核心列永不隐藏；判定基于**整批已加载数据**故搜索不动列。**14 条新门禁全部变异验证**（3 处变异各抓出对应门禁红，失败原文记录）· 截图重拍逐张核对：**表清单 8→2 列、对象首页 9→3 列、卡片只剩「图标+名字+N 行」** · 门禁 **731 / 92 类 / 0 失败** |
| **第十八轮 · 截图当证据** | 12 张实拍 + 4 张 Navicat 参考**逐张并排核对**；修复 **6 个真缺陷**（`NAVICAT-TODO.md` §28.11）；合并待办 **§29** | ⭐ 721 条门禁全绿也漏掉的 6 个问题，**5 个同根：失败被设计成静默降级**（找不到节点就跳过、缺资源就跳过、选不中就没值、清计时写占位、没配就留空白）→ 新增**两个静态审计门禁**（脚本点击目标 / 图标资源名）；**逐像素实测** AIDB 网格 28px vs Navicat 19px（侧栏树 24 vs 22 持平）；**7 条新门禁全部做过变异验证** |
| **第十七轮 · 规格书落地** | §6 右侧详情面板 + §13 新建表弹窗重构 + §9.7 底部导航标签 + §6.3 表清单联动 + §11.2 DDL 编辑器 + **AIDB 品牌/Logo** + **§13.3 布尔列键盘可达** | 新增 `ObjectDetailsPanel`、SQL 预览换只读 `SqlEditor`、Logo SVG+PNG 全套（`tools/GenLogo.java`）、`BooleanCell` 让三态约束全键盘可达；门禁 691 → 721 |
| **MySQL 真库联调** | `MySqlIntegrationTest`（凭据只从环境变量读） | ⭐ 抓出两个 **SQLite 上永远看不到**的真 bug：`RENAME COLUMN` 是 8.0 才有的（5.7 要用 `CHANGE COLUMN`）；JDBC 类型名不带长度（`MODIFY COLUMN c VARCHAR` 是语法错误）→ 新增 `ColumnDef.ddlType()` |
| **A1 方言抽象层** | `Dialect` + 3 实现 | 加一种数据库从「改 5 个文件」变成「写一个实现」；源码审计门禁守着 |
| **多连接对象树** | `SideBar` | Navicat 的「我的连接」模型：所有连接在同一棵树里，展开懒加载 |
| **P6 导入导出** | 新模块 `adb-transfer` | 流式 CSV/TSV/JSON/SQL 导出 + CSV/TSV 导入（批量提交、字段映射、坏行报告） |
| **A6 / A17 / A19 / A21 / A23** | 安全 / 令牌 / 密度 / 会话 / 死代码 | 三层危险门禁全真；令牌 120→60 且全部被用到；密度覆盖网格行高；切连接不泄漏会话 |

---

## 1. 当前的核心判断

**这一轮的重点从「补功能」转向「补品质」。**

前四轮把「能不能用」的问题基本解决了（P0–P4 全部完成，P6 落地，P5 剩连接管理尾巴）。
剩下的差距分三类，按**收益/成本**排：

| 类别 | 代表项 | 收益 | 成本 |
|---|---|---|---|
| **A. 界面品质** | 字号阶梯、焦点环、图标统一、工具栏层级、密度全局生效 | **高**（每一次使用都在看） | **低–中**（大部分是 CSS + 少量 Java） |
| **B. 网格/查询进阶** | 冻结列、列拖拽、区域选择、结果分页 | 中高 | 中 |
| **C. 连接管理收尾** | ~~多连接并行~~ ✅ · ~~catalog 树~~ ✅（第十四轮）· ~~连接属性页~~ ✅ | 中 | 中高（要动外壳状态模型） |

> ⚠️ **A 类被长期低估**：实测发现界面有 **11 个具体问题**（见 `docs/UI-ROADMAP.md` §1），
> 其中「只有 3 处 focus 环」「9px 小字」「10 个按钮 9 个一样」这类问题**每次打开都在发生**，
> 但不会体现在任何功能清单上 —— 所以之前一直没进 TODO。

---

## 2. 阶段 U：界面专项重做（第五至十九轮，✅ 收官；第二十一轮延续见 §4.5）

> 完整分析与规范见 **`docs/UI-ROADMAP.md`**。批次之间**无依赖**，可按收益重排。

| 批次 | 内容 | 门禁 | 状态 |
|---|---|---|---|
| **U-B1** | **排版层**：9 级字号阶梯，消灭 9px 与 12.5px 孤例 | `AppCssAuditTest`（7 例） | ✅ **已完成** |
| **U-B2** | **间距 + 圆角层**：4px 阶梯 7 档 + 圆角 3 档 + pill | `AppCssAuditTest` | ✅ **已完成** |
| **U-B3** | **焦点与交互态层**：26 条 focus 规则；禁用态不再被整体压暗 | `FocusRingAuditTest`（6 例）+ `DisabledButtonVisibilityTest`（3 例） | ✅ **已完成** |
| **U-B4** | **图标层**：38 个 SVGPath 矢量图标；按钮里一个 Unicode 符号都不剩 | `IconSystemAuditTest`（4 例） | ✅ **已完成** |
| **U-B5** | **布局与组件**：工具栏 3 组 / 记录导航条 / 列头筛选悬停浮现 / **连接列表改 7 列表格** / **密度全局 4 处** / 空状态按钮 / 应用图标 | `GridInteractionTest`（3）+ `DensityCoverageTest`（4）+ `ConnectionsPageTest`（+3） | ✅ **已完成** |
| **U-B6** | **功能补齐层**：G1/G2/G4/G6/Q5/Q6/U1–U6（见 `NAVICAT-TODO.md` §13） | 各项目标单测 | 🟡 **进行中**（G2 ✅ / G6 ✅ / G5 ✅ / **U1 ✅ catalog 树（第十四轮）** / **Q5 ✅ + V-9 ✅ + G4 ✅ + G1 ✅（第十五轮）**；**U-B6 已全部完成**） |
| **—** | 🆕 **第六轮全量核查**：4 个真实缺陷 + 表级生命周期空白 + 一键脚本 | 见 `NAVICAT-TODO.md` §14 | ✅ **核查完成** |

> ⚠️ 本表上一版把 U-B4 / U-B5 各写了两遍（一遍标工期、一遍标完成），本轮已去重。
> 📦 **另新增 `scripts/`**（`dev.sh` / `build.sh` / `shots.sh`）——
> 启动流程原本要手工敲 4 步且两个坑必踩，见 `NAVICAT-TODO.md` §14.7。

**进度**：U-B1 / U-B2 / U-B3 / U-B4 / U-B5 **已完成**（界面立刻「齐」了：
字号从 13 种收到 9 档、间距从 47 种收到 7 档、圆角从 5 种收到 3 档、focus 规则 3 → 26 条、
禁用按钮不再消失、**按钮里一个 Unicode 符号都不剩** —— 38 个手绘 SVGPath 顶上、
工具栏变成 Navicat 形态的图标条、连接列表成了 7 列表格、密度全局 4 处生效）。
**S5 也已落地**（Navicat 实拍证实：它的标签栏最左边就是常驻的「对象」标签）——
常驻标签永远排第一、没有关闭钮、关掉其它标签后落回它、内容是按类型分组的对象总览。
**U-B6 / U-B7 已全部完成**（功能补齐收官 + 侧栏 Navicat 模型 + 规格书落地 + 品牌改造）——
**S 级全部结案**（S-1…S-9 已完成；S-7 于 2026-10-24 裁定＝接 + 删并落地）；
**下一批见 §4.5 / `NAVICAT-TODO.md` §34 的 UX-0…UX-6**。

> ⚠️ **踩过的两个坑（已写进 `NAVICAT-TODO.md` §12.2 与本文 §6）**：
> ⓪ ⭐ **连接 URL 必须带超时** —— 服务端 TLS 握手一卡，客户端会**无限期**阻塞，
> 界面直接冻住。实测这台 MySQL 的握手就会**间歇性卡住**（6 次挂 2 次），
> 所以除了加 `connectTimeout`/`socketTimeout`，真库契约还加了**通信失败重试**
> （只重试通信类失败，SQL / 断言错一律立刻失败）。
> ① Modena 给**所有** `:disabled` 控件加 `-fx-opacity: 0.4` —— 删掉我们自己的
> `opacity` 之后，禁用的按钮反而**更**看不见（数据页「保存」按钮整个消失）。
> 正确做法是显式把透明度**还原成 1**。
> ② 拍截图前必须 `./mvnw -B install -DskipTests` ——
> `javafx:run -pl`（不带 `-am`）会用 `~/.m2` 里的**旧 jar**，拍出来的是旧界面。

> **每批完成后必须重拍 6 张截图**（`NAVICAT-TODO.md` §12.2），
> 并肉眼核对「状态栏读得清吗 / 按钮有层级吗 / 图标对齐吗」。

---

## 3. 阶段 C：连接管理收尾（P5 尾巴）

| 顺序 | 项 | 说明 | 门禁 |
|---|---|---|---|
| 1 | **多连接并行** | `AppShell` 现在是单 `activeProfile` / 单 `session`，切标签即切连接。改为按连接 id 取会话，工具栏显示**当前标签**的连接 | `SessionManagerTest` 扩用例 | ✅ **已完成（第十二轮）** |
| 2 | ~~**catalog 树**~~ | ~~schema 那一半已做；**catalog 那一半还缺**~~ | `CatalogTreeTest`(5) + `CatalogVariantProfileTest`(5) + `SideBarTreeTest`(+5) | ✅ **已完成（第十四轮 U1，见 `NAVICAT-TODO.md` §24）** |
| 3 | **连接属性页** | `ConnectionProfile.options` 自由 Map 向导化（读超时 / SSL / 字符集 / 连接池） | `ConnectionWizardTest.propertiesStep` | ✅ **已完成（第十二轮·续三）** |
| 4 | **Windows 凭据** | WinCred 后端（JNA）；见 `NAVICAT-TODO.md` §34.3 P5-2 | `CredentialStoreTest`（唯一 target 前缀 + 清理） | ✅ **已完成（第二十四轮 P5-2）** |

---

## 4. 阶段 D：网格与查询页进阶

见 `docs/UI-ROADMAP.md` §3 B6 与 `NAVICAT-TODO.md` §11。
**G1 冻结列**需要两表四路同步（滚动 / 选择 / 排序 / 编辑），
上一轮试过「双 `TableView`」方案后**完全回滚**（半成品比没有更糟），
本轮换思路：优先做 **G2 列拖拽重排**（顺序已能持久化，只缺运行时入口）与
**G4 区域选择**，冻结列排在其后。

---

## 4.5 阶段 UX：UIUX 升级（第二十一轮 · **当前主线**）

> 触发：两张新的 Navicat 高保真实拍（数据网格 / 表清单）逐张量差。
> 证据：`docs/NAVICAT-COMPARE.md` §9；目标值与门禁：`docs/UI-ROADMAP.md` §12；
> 执行勾选：`NAVICAT-TODO.md` §34。参考图已归档到 `docs/screenshots/navicat/*-2026-10-24.png`。

**一句话结论**：功能不是最大差距，**密度与可发现性**才是 ——
网格行高约 22 vs 34、正文 12 vs 13、工具条「图标+文字」vs 纯图标、单元格无竖线、
选中态太弱、圆角 3–4 vs 8。先做这五类，观感立刻从「网页卡片」回到「桌面工具」。

| 批次 | 内容 | 量化验收 | 优先级 |
|---|---|---|---|
| **UX-0** ✅ | 截图可复现：`ADB_DENSITY` + prefs 隔离 | 同一脚本连跑两次同尺寸（**已实测同 SHA-256**） | P0 |
| **UX-1** ✅ | 令牌：圆角 3/4/6/8、边框/表面分层、投影收敛、图标 14/16/20 | 令牌对拍 + 对比度 + 审计（**已落地，4 个变异验证**） | P0 |
| **UX-2** ✅ | 密度/排版/chrome：新增 dense 22，默认 compact；字号 12；对象头/工具条/状态栏收薄 | 765px 高可见 ≥22 行（**已落地：`ChromeMetricsTest` 实测通过，5 个变异验证**） | P0 |
| **UX-3** ✅ | 工具条主操作「图标+文字」+ 分组 + 补导入 | 可发现性门禁（主操作必须有文字）——**已落地：`ToolbarDiscoverabilityTest` 5 条 + 清单/对象首页 3 条，9 个变异验证**；「历史」推迟到 P2-3、「单元格编辑器」归 UX-4 | P1 |
| **UX-4** ✅ | 网格：强选中、竖线、列头菜单、双击自适应、行操作条、NULL 显示、单元格编辑器 | **已落地：25 条新门禁 / 3 个新类，逐条变异验证**（`SelectionStateTest` 8 + `CellDisplayTest` 6 + `CellEditorTest` 4 + `GridInteractionTest` 7）；⭐ 顺带修掉编辑器「切页签提交错目标」的写缓冲污染 | P1 |
| **UX-5** ✅ | 侧栏选中/连接状态、标签连接限定、状态栏 SQL 回显 | 侧栏/标签/状态栏各 +1~3（**已落地：含唯一一帧交互态 `p2-cell`；基线 773/100**） | P1 |
| **UX-6** | 功能缺口：对象首页列表/网格、结构「选项」、数据「概览」、已保存查询分组 | 各页 +2；真库项按环境跳过 | P2 |

**依赖**：UX-0 → UX-1 → UX-2 →（UX-3 ✅ → UX-4 ✅ → UX-5 ✅）；**P2-1 ✅**；UX-6 独立。
**完成定义**：代码 + 门禁（UI 类改动必须新增或扩展一条**可执行**门禁）+ **变异验证**（每处改坏 → 跑门禁 → 必须红 → 立刻还原）+ 截图（钉主题与密度）+ 与 Navicat 同尺寸并排。

> ⚠️ 旧的「顶栏 11 个大功能条」仍然**不做**（没有备份/模型/BI 等真实后端，不做空壳）；
> 这次把「可发现性」做在**页面上下文工具条**上，成本更低、收益相同。
> 旧结论「纯图标条更干净」被本轮截图推翻一半：**主操作**要文字，**次操作**继续纯图标。

---

## 5. 阶段 P：分发与收尾（P7 · 第二十六轮收官）

| 项 | 状态 | 说明 |
|---|---|---|
| jpackage | ✅ | `scripts/package.sh` 出 app-image / dmg / msi / deb，含运行时、真能启动 |
| 崩溃日志 | ✅ | `CrashLog` → `~/.agenticdb/logs/crash-*.log` |
| **启动可观测性** | ✅ **本轮** | `StartupTrace` → `~/.agenticdb/logs/startup.log` +「关于」回显；`StartupTraceTest`(4) + 外壳打点覆盖(1) |
| **可访问性** | ✅ **本轮** | 六区焦点顺序、标签/切面键盘可达、逐页可朗读名称；`AccessibilityAuditTest`(5)；对比度与焦点环沿用既有 `ContrastTest` / `FocusRingAuditTest` |
| **冷启动 < 2s** | ❌ **未达标（3.5–3.9 s）** | 成本已归因，地板 ≈2.4 s（Toolkit 0.95 + Modena 0.5 + 首趟 CSS/布局 0.9）；**AppCDS 在开发启动路径上试过不通**；渐进渲染要重排整条装配链 —— 均为长期项（`NAVICAT-TODO.md` §35.1） |
| jlink 自定义运行时 | ⛔ 维持不可行 | RichTextFX / ControlFX 是自动模块；jpackage 已能出可启动 app-image |
| 自动更新 / 版本检查 | 🚫 裁定不做 | 无发布渠道 + 离线/内网 + 无签名；将来若有 Release，只做「提示不下载」 |
| 签名与公证 | ⛔ 阻塞在外部资源 | 需要 Apple Developer ID + Windows 代码签名证书 |

---

## 6. 已知的阻塞项

| 项 | 状态 | 解除条件 |
|---|---|---|
| **PostgreSQL 真库联调** | ✅ **已解除**（第十六轮）：Docker 解锁后用 `postgres:16-alpine` 跑通 11 例；外部实例走 `ADB_TEST_PG_*` 环境变量 | 已完成；aliCloud RDS 只需填环境变量凭据，**无需改代码** |
| **Windows 凭据** | ✅ **已完成**（第二十四轮 P5-2） | JNA WinCred 后端；Windows 实机 8/8 真跑，非 Windows 跳过不计通过 |
| **Windows / Linux 分发** | 🟡 jpackage 出包 ✅（`scripts/package.sh`）；**签名 / 公证 ⛔** | 需要 Apple Developer ID 与 Windows 代码签名证书（外部资源），不是代码问题 |
| **冷启动 < 2s** | ❌ **未达标**（实测 3.5–3.9 s，成本已归因） | 需要 AppCDS（开发启动路径试过不通）或首屏渐进渲染（要重排 `AppShell` 装配链）—— 长期项 |

> ⚠️ MySQL 真库联调已经证明了这条路的价值：**一次真库就抓出两个 SQLite 上永远看不到的 bug**
> （`RENAME COLUMN` 版本差异、JDBC 类型名不带长度）。
> **PostgreSQL 16 + MySQL 8.4 这一轮又抓到两个**（`rows` 是 MySQL 8.0 保留字；
> `pg_class` 没有 `relcollation` 列 —— 排序规则是每列的，不是每表的）。
> 结论不变：**真库联调是抓跨方言 bug 唯一有效的手段**，SQLite 覆盖不到这一层。

---

## 7. 提交策略

上一轮 12 个 commit **全部只在本地，未推送**。本轮开工前先确认是否要推。

每个批次**独立成 commit**，描述必须包含：改了什么 / 为什么 / 门禁名称。
不允许出现「已完成但没写门禁」的条目 —— 上一版文档把 4.5-4 误记成「已完成」，
教训已写进 `NAVICAT-TODO.md` §6.3。

---

## 8. 立即开工项（第二十一轮重排）

前几轮的主线是「补功能 → 补品质 → 实拍对标」；第二十一轮的主线是
**「先把截图与偏好钉死，再做密度和可发现性」**。
`declarations` 删除单独放在最前：它只减面、不改行为，先做完才能把新基线说清楚。

```
P0  0) ✅ §34.1 文档对齐 + 删除 declarations（已完成；基线 712 / 92）
P0  1) ✅ UX-0 截图可复现（ADB_DENSITY + prefs 隔离；基线 714 / 92）
P0  2) ✅ UX-1 令牌（圆角/边框/表面/投影/图标尺寸；基线 716 / 92）
P0  3) ✅ UX-2 密度与 chrome（dense 22 / 默认 compact / 12px / ≥22 行；基线 720 / 93）
P1  4) ✅ UX-3 工具条图标+文字 + 分组 + 补导入（728 / 94；历史推迟到 P2-3、单元格编辑器归 UX-4）
P1  5) ✅ UX-4 网格交互（选中/竖线/列头菜单/行操作条/NULL/单元格编辑器；753 / 97）
P1  6) ✅ S-7 品牌落点（应用栏矢量 BRAND + 删不可达欢迎页与 fs-2xl 令牌；754 / 97）
P1  7) ✅ UX-5 侧栏/标签/状态栏（773 / 100）
P2  8) ✅ P2-1 清单列宽/长注释/数据长度单位（781 / 100）
P2  9) ✅ P2-2 错误详情弹窗 + 批量汇总（798 / 103）
P2 10) ✅ P5-2 Windows WinCred（807 / 104）
P2 11) ✅ P5-1 分组/重命名 → ✅ P5-3 失败语义 → ✅ P5-4 内省
P2 12) ✅ P2-3 最近操作 → ✅ P6-1 类型转换 → ✅ P6-2 查询结果导出 → ✅ P6-3 xlsx
P2 13) ✅ UX-6 功能缺口（对象首页视图切换 / 结构选项 / 数据概览 / 已保存查询分组）
      → 代码主线收官：**857 / 110 / 0**；15 张截图已重拍，最终对比见 `NAVICAT-COMPARE.md` §11
P2 14) ✅ P7-1 启动可观测性（StartupTrace + 日志 + 关于回显 + 强制 CSS pass 收口）
P2 15) ✅ P7-2 可访问性（六区焦点顺序 / 标签与切面键盘可达 / 逐页可朗读名称）
P2 16) ✅ P7-3 分发裁定（jlink ⛔ · 自动更新 🚫 · 签名 ⛔ · A18/A19/A22 回填）
      → **P7 能自动化的部分收官：871 / 113 / 0 / 48 跳过**；冷启动 <2s **如实记为未达标**（§35.1）
P2 17) ✅ 任务书 V1.0（U-ICON Lucide + 语义彩色 → U-CTRL 五档按钮×五态 → U-IDX §六 索引弹窗 → U-POLISH 弹窗骨架统一）
      → **913 / 116 / 0 / 48 跳过**；15 张截图全量重拍；与任务书字面值的 6 处偏差逐条记录（`docs/UI-ROADMAP.md` §13.3）
```

**已裁定**（2026-10-24）：P2/P5/P6 全部同意做；`declarations` 删除；C4 做；
Windows 凭据用 JNA；xlsx 用 POI；连接分组做、tags 暂缓；`paramStyle: numbered` 不做。
**S-7 已裁定并落地**（2026-10-24，＝**接 + 删**）：品牌接应用栏（矢量 `Icons.Icon.BRAND`），
不可达的欢迎页与 `.adb-welcome*` / `Icons.SIZE_BRAND` / `fs-2xl` 令牌连带删净 —— 见 `NAVICAT-TODO.md` §34.2。

每项做完跑一次 `./scripts/build.sh`（= clean verify + 用例计数）+ `./scripts/shots.sh` 重拍截图，
全绿再进下一项。

> **删 `declarations` 的顺序**（已完成）：先删 `adb-jdbc` 的测试依赖与 `DeclaredDialectConsistencyTest`，
> 再删模块与根目录 YAML，最后删父 POM 的 module / dependencyManagement / `snakeyaml` 版本。
> 全量门禁已确认 **712 条 / 92 类 / 0 失败**；不要与 755 直接比较。
>
> **截图纪律**：从 UX-0 起，`shots.sh` 必须钉 `ADB_THEME_FORCE` + `ADB_DENSITY`，
> 否则同一份脚本在不同 prefs 下会拍出不同行高，后面所有并排核对都失真。
