# 更新日志

本文件记录 AIDB（AgenticDB）面向使用者的变更。

- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
- **当前状态：尚未发布任何正式版本。** 仓库版本是 `1.0.0-SNAPSHOT`，没有任何 git tag。
  首个 tag 会在 macOS 公证 / 签名与分发渠道确定后切（见 [docs/ROADMAP.md](docs/ROADMAP.md)）。
- **不回填历史。** tag 之前的 170+ 次提交请查 `git log --oneline`；本文件从「有意对外发布」那一刻开始维护。

## [Unreleased]

### Added

- **R35 彩色图标通道**：`icons/color/*.svg` 资产只声明语义层（`edge` / `tone-a` / `tone-b` / `tone-c` / `accent`），
  颜色一律来自设计令牌，由 `app.css` 的后代规则打到对应子层。
  暗色档**不需要第二套资产**——同一张 SVG 换主题自动换色。
  导航词表（侧栏 / 对象树 / 结构页 / 页签 / 标题栏）与功能区 6 条命令已全量走彩色档。
  门禁：`ColoredIconTest`、`IconKindHueTest`、`IconSystemAuditTest.theNavigationVocabularyIsFullyColored` / `theRibbonVocabularyIsFullyColored`。
- **图标分层令牌**：`-adb-icon-edge` / `-adb-icon-tone-*` 与各对象类型色 `-adb-kind-*`（明暗两档）进 `tokens.json` 唯一真相。
- 借用素材的署名义务钉成门禁：`icons/lucide/` 与 `icons/color/` 目录必须随附 `ISC-LICENSE.txt`（`ColoredIconTest.everyBorrowedAssetDirectoryShipsItsLicense`）。
- 社区与合规文件：`LICENSE`（Apache-2.0）、`NOTICE`（三方许可逐条列明）、`CONTRIBUTING.md`、`SECURITY.md`、
  `.github/` Issue / PR 模板与 CI 工作流。

### Changed

- **弹窗去掉自绘标题条，连接向导照 Navicat 17 重做成两页**（2026-11-01，R40 N26 N27）：
  所有弹窗内容区的抬头条与右上角 × 一起删除，标题（表名 / 方言 / 库的上下文拼进去）
  交给系统标题栏，关窗走系统 × 与 Esc；随之取消**弹窗尺寸记忆** —— 上次把某个弹窗拉到
  900 高，不再让下一次同类弹窗也撑成 900，每次打开都从档位默认值开始。
  连接类型页改成左栏 `Connection Filter` 9 项 + `Vendor Filter` 13 项复选、右栏**5 列彩色磁贴**
  + 搜索，两块清单与搜索共用同一套过滤；左下角是 `New Connection with URI…`，右下角 `Next / Cancel`。
  **22 张磁贴照 Navicat 全摆，但只有 6 种真有驱动**：MongoDB / Redis / Snowflake 与 13 个厂商
  点「下一步」进的是**如实说明页**（写明当前只有 6 种驱动，并给协议兼容端点与 URI 两条替代路径），
  不是一张填了也连不上的表单。常规页补 `AIDB ⟷ Database` 示意图、右对齐标签、`Save password`，
  底部五键（测试连接 / URI… / 上一步 / 取消 / 确定），整页 900×700。
  门禁：`ConnectionWizardTest`（9+13 条目 / 五列 / 复选过滤 / 无驱动说明页 / 五键顺序 / 900×700）、
  `DialogStylesTest`（`apply` 不再写 pref 尺寸）。
- **查询页按 Navicat 17 重排，状态栏改成按页型换段**（2026-10-31，R39 N21–N23 N25）：
  工具带改为**双行 62px**（第一行 保存 / 查询构建器 / 格式化 / 代码片段 / AI 助手▾，
  右端 风险提示 · 历史▾；第二行 查询名▾ / 连接▾ / `Run▾` / 停止 / 执行计划），
  「全部执行 / 带参数执行」收进 Run 的菜单，「清空」收进编辑器右键菜单；
  语法配色重标定（关键字 `#097BF7` 粗 / 字符串 `#FF1744` / 注释 `#929292` / 正文 `#424242` / 表名下划线）；
  结果区拆成 27px 的 `Message / Summary / Result N` 与 32px 的 `Data / Info`，网格表头单行。
  状态栏不再是一套固定十段：**查询页** 左「来源 · SQL · 消息」/ 中「选中 · 耗时」/ 右「行范围 · 刷新时间」，
  **数据页** 左「SQL · 消息」/ 中「选中」/ 右「行范围 · 刷新时间」，
  **对象页** 左「选中 · 消息」/ 右「来源」；「自动提交 · 只读」两段连同其 CSS、文案与调用点一起删除。
  门禁：`StatusBarTest.statusBarRegroupsSegmentsByPageType`、`SqlSyntaxColorTest`。
- **已保存查询从弹窗改成工作区对象页**（2026-10-31，R39 N24）：
  侧栏 `Queries` 组双击 / Enter 打开页签，页面是四列表（名称 / 类型 / 修改时间 / SQL）+ `设计` / `新建` / `删除查询`；
  双击行或 Enter = 设计，Delete = 删除（危险确认默认按钮为取消）；查询页保存命名查询后列表即时刷新。
  空态给「新建」下一步，不再是裸提示。门禁：`SavedQueriesPageTest`。
- **Filter & Sort 从弹窗改成网格内嵌生成器面板**（2026-10-31，R38 N31）：
  工具条 `Filter & Sort` 按钮按下即开（内容嵌在工具条与网格之间，把网格往下推，不遮挡数据）；
  条件行 = 启用勾选 · 字段 · 操作符 · 值 / `<?>` 占位 · `and` 连接词 · 删除，`[+]` 加条件、`[Ω]` 加组；
  `Sort By` 用字段 / 升降序 / 删除芯片；底部 `Apply Filter & Sort` **显式应用**，草稿与已生效条件不一致时亮
  `Criteria Edited`。旧的 `ConditionDialog`（条件弹窗）删除 —— 能力并进面板；列头漏斗的按值筛选保留
  （`IN` 无法用平铺 AND 生成器表达，应用面板时原样带过去）。
  **边界照实**：`[Ω]`（OR 组）本版本给如实说明页，Text 模式是只读的生成表达式。
  门禁：新增 `FilterPanelAuditTest`（开合与按下态 / 未 Apply 不动查询 / 排序芯片生效）。
- **数据页工具条与底栏按 Navicat 17 重排**（2026-10-31，R38 N18/N19/N20）：
  工具条收敛为 **13 具名键 + 视图 `≡▾` / 溢出 `»` 两小键**（`Table Profile▾` · `Begin Transaction` ·
  `Cell Editor` · `Filter & Sort` · `Columns` · `History` · `Fill with AI▾` · `Data Profiling` ·
  `Import` · `Export` · `Data Generation` · `Data Masking` · `Create BI Workspace`），
  放不下时从右往左折进 `»`（Tab / Enter 可达）；能力面未建的五颗键点开是**如实说明页**。
  底栏 = 左行操作条 + 右「首/上/页码框/下/末 · 齿轮（页大小 + 跳至记录）· 放大镜 · 网格/表单切换」；
  **记录计数移到状态栏**，底栏不再有行数 / 页大小 / 「跳至页」文字控件。
  窄宽降级按**可看见**的宽度从右往左收「表单 → 网格 → 放大镜」，五键 / 页码框 / 齿轮永不收；
  可用宽度会让出详情浮层压住的那一截（D-1：旧账 ~90px）。
- **数据网格行首与编辑态对齐 Navicat 17 实测**（2026-10-31，R38 N16/N17）：
  行号列改为 16px **空白状态槽**（只在草稿 / 脏行 / 待删行放 `＋` / 铅笔 / `×`），批量入口改成
  行多选 + 右键菜单（撤掉常驻批量条）；光标所在列头整格着 `#AED4F2` tint，行内编辑盒改为
  白底 + 2px `#0078D7` 方框、进入编辑即全选。
- **对象树升到「连接 → 库 → 分组」三级，页签 / 状态栏带库名**（2026-10-31，R37-c）：
  PostgreSQL（`pg_database`，模板库排除）与 SQL Server（`sys.databases`，master / tempdb / model / msdb 过滤）
  跟 MySQL / MariaDB 一样，按「一个连接绑一个库」的方式管理多库——双击库 = 切到它（换连接），
  查询页、对象清单和会话都跟着库走。对象树里当前库加粗，SQLite 单文件仍平铺直挂，Oracle 一条连接一个服务。
  页签全名改为 `<对象> @<库> (<连接>)`（SQLite 没有库名时退回 `@<连接>`），
  状态栏的当前连接同样补上库名；schema 切换器只在「切了真能影响查询」的方言上出现
  （PG 的 `SET search_path`、Oracle 的 `ALTER SESSION`），不再给 SQL Server 摆一个只改树不改查询的开关。
- **顶区与外壳按 Navicat 17 重排**（2026-10-31，R36）：顶区 140px = 菜单 24 + Ribbon 91 + 页签行 25；
  命令带按内容生长、放不下时横向滚动（不再截断英文标签），页签行只放页签；
  User / Backup / Automation / Model / BI 五格走**如实说明页**，不摆死磁贴。
  连接向导接入 MariaDB / SQL Server / Oracle：SSL 档位与根证书参数**由方言自报**（MariaDB 没有 prefer 档；
  SQL Server / Oracle 收起档位并写明去哪里填），URI 参数按方言解析（Oracle EZConnect、SQL Server 分号参数串）
  并回填到 TLS 控件或附加参数。按方言能力收起按钮：SQL Server / Oracle 的执行计划、Oracle 外键 ON UPDATE。
- **对象页二合一，表清单收敛成 Navicat 的六列 + 七键**（2026-10-31，R37-b）：
  对象首页与表清单原来是两张页面 / 两个入口，现在合并成常驻的「对象」页签（切连接只换内容，页签不动）。
  列固定为 名称 / 行 / 数据长度 / 引擎 / 修改日期 / 注释，取不到值的格显示「—」——
  SQLite 不再整列消失，同一张表在六种方言下的列位不跳。
  工具条对齐为七颗带文字的键：打开表 / 设计表 / 新建表 / 用 AI 新建表 / 删除表 / 导入向导 / 导出向导；
  **移除「刷新」键与「N 张表」计数**（Navicat 的对象工具条两者都没有，刷新留在右键菜单 / 菜单栏），
  过滤框占位符改为短文案「Search tables / 搜索表名」——原名在 120px 下会被咬掉尾巴，
  而这条工具条已经排到窗口右缘，放大过滤框会把列表 / 网格切换挤出可视区（重拍核对时实测）。
- **导航树收成「库 → 表 / 视图 / 函数·过程」**（2026-10-31，R37-a）：删掉每组后面的计数徽标、
  整组触发器节点与树底三颗导航键（查询历史 / 诊断 / 导入·导出——它们仍在菜单、⌘K 与 ⋯ 菜单里）。
  触发器按表归属在结构页查看；事件（MySQL EVENT）本版本不上树，文档里写明这条边界；
  函数 / 过程只经树的双击 / Enter 打开定义体。树里没有事件节点，原来的事件分发是不可达代码，已删。
- **16px 内联工具条进彩色档**（2026-10-30，R35 N4-c；改的是 `docs/UI-VISUAL-SPEC.md` §4.5 第 1 条规矩）。
  旧规矩把「16px 内联工具条」整体划在单色区，依据是想当然的「小图标彩了会糊」；
  逐像素翻 Navicat 数据页实拍帧（`docs/screenshots/navicat/navicat-data-grid-2026-10-24.png`）后确认
  **Navicat 的 16px 本身分两区**：上沿工具条是蓝色实心小图形（和树上同一族颜色），
  贴在网格左下的行操作条 `+ − ✓ ✗ ` 是灰的单线。
  于是「同一个图标在树上彩、在工具条上单色」按对手口径是**缺陷**（I-4），修法：
  工具条默认工厂走 `Icons.colored`（无彩色资产自动回退单色），行操作条显式走新的
  `IconButtons.iconMono`。门禁 `ToolbarIconChannelTest` 双向钉（彩的必须彩、单色的必须单色），
  `DataGridPageTest.theTopToolbarIsColoredWhileTheRowStripStaysMonochrome` 从真实页面的按钮上验两区。
  ⚠️ 连带必须补的一条：分层返回 `Group`，只认 `.adb-icon` 的按钮态规则命中不了子层 ——
  不补就是蓝底上蓝边看不见图标、禁用还亮着蓝边（后者撞诚实条款）。
  `app.css` 新增「彩色分层在按钮态里的翻色」段（`--primary` 翻白 + `:disabled` 塌灰），
  门禁读**计算色**而不是 CSS 文本。
  变异验证（三条都已实测）：工厂改回 `Icons.of` → 18 枚图标全部报「掉回单色」；
  删 `:disabled` 那条边色规则 → 禁用态门禁红在轮廓断言；删 `--primary` 那条 → 主动作门禁红。
- **启动耗时门禁改判**（2026-10-30）：`StartupTraceTest` 里那条「首屏不应超过 2s」删掉了。
  它断言的 `firstScreenMillis()` 量的是「**JVM 进程启动 → 走到断言那一行**」，
  在共享的 surefire fork 里这个差值等于测试运行器已经跑了多久 ——
  所以「全量跑必红、单跑这个类全绿」，它测的是运行器的进程年龄，不是产品的冷启动。
  真实冷启动只在真启动时才有意义，量法仍是 `~/.agenticdb/logs/startup.log`（对外记的 3.5–3.9 s 从那儿读）。
  2 秒这条规格**没有放弃**，改由新门禁 `theTwoSecondBudgetIsEnforcedInBothDirections` 钉住：
  `StartupTrace` 开一个包内构造器接收注入的「进程启动时刻」，测试据此确定性地造出
  0.5s（判快）与 2.5s（判慢，且摘要里必须喊 `SLOW`）两种首屏。
  变异验证：把 `slow()` 改成恒 `false` → 该门禁立刻红（已实测）。
- **功能区图标尺寸维持 26px**（原计划放大到 30px）。改判理由：Navicat 17 实测里，那一排大图标是全界面最彩的部分，
  需要的是彩色档而不是更大的单色图标；30px 留到 R36 重做顶区布局时一起判。
  见 [docs/UI-VISUAL-SPEC.md](docs/UI-VISUAL-SPEC.md) §4.5。
- 对象类型色回到 `edge`（轮廓）层，`tone` 三层保持中性——同一个对象只保留一处颜色来源。
- 视觉文档与实现对齐：设计令牌数校正为 106（68 色 / 35 尺寸 / 3 时长），移除不存在的 `border-strong` 记录，
  图标台账改为 55 枚（53 枚 Lucide + 2 枚自绘）。

### Fixed

- **菜单按钮的文字底部被裁掉**（2026-11-01，R40 帧目视）：查询工具带第二排的
  「agenticdb-demodb」看不到 g 的尾巴，网格工具条「History」的 y 也被削平。
  modena.css 给 `.menu-button > .label` 另有 4px 竖直内边距，叠在按钮外壳的内边距上
  之后，18px 高的 label 内容区只剩 10px —— `LabeledText` 按内容区高度裁字，
  12px 字号 15px 行框的下半截（descender）整段消失。现在竖直归零、横向保留 8px，
  左右缩进与箭头间距不变，其他工具条几何零变化。
- **表清单的列宽防抖会活过页面**（2026-10-31，R37-b）：列宽记忆用 400ms 防抖，
  页面关掉 / 切换连接之后计时器照样触发，往已拆卸的场景里读列、往可能已被回收的偏好节点写值，
  抛 `IllegalStateException: Node has been removed`。
  现在页面从场景摘下时先把这一拖补写完；没挂到场景上的宽度变化（恢复列宽 / 换主题引起的布局抖动）
  不进防抖；偏好节点已被回收时静默放弃——列宽只是尽力而为的记忆，不该反过来把应用弄崩。
- **关掉数据页签之后，列宽防抖还会照点一次落盘**（2026-10-30）：`scheduleLayoutSave()` 用 400ms 的
  `PauseTransition` 去抖，`close()` 只取消了在跑的查询、没摘这个定时器。页签没了它照样触发一次
  `persistLayout()` —— 往已拆卸的场景里读列、往可能已被回收的 prefs 节点写值，
  抛 `IllegalStateException: Node has been removed`。
  症状很误导人：崩的是**跟在这页之后跑的别的用例**，崩溃日志里也长期躺着这条噪音。
  现在 `close()` 显式 `stop()` 定时器（只停不补写：最多丢一次「拖完 400ms 内就关页签」的宽度，
  换来关页之后不再有任何写）。门禁：`closingThePageCancelsThePendingLayoutSave`
  断言的是**防抖还在不在跑**，不是等 400ms 看有没有异常。
  变异验证：删掉那三行 `stop()` → 本类两条用例同时红（已实测）。
- **弹窗按钮序门禁在 macOS 上假红**（长期红，2026-10-30 修）：
  `DialogButtonOrderTest.theOrderStringOnlySwapsThoseTwoSlots` 拿 `DialogStyles.apply` 之后的
  order 串去比 **Windows 预设**，但 `ButtonBar` 的初始串是**按宿主平台**选的
  （macOS 那份是 `L_HE+U+FBIX_NYA_R`，既没有 `O` 也没有 `C`），而生产代码对不认识的预设
  故意不动（那是另一条门禁钉住的规则）。结果 Windows 全绿、macOS 必红，且与产品无关。
  现在测试**显式喂 Windows 预设**再断言「只换 O / C 两槽」，跨平台一致。
  变异验证：把 `DialogStyles` 的换槽分支短路 → 该门禁在 macOS 上立刻红在第 214 行
  「取消 C 要在主动作 O 左边」（已实测，不是推测）。
- `scripts/shots.sh` 的**帧去重门禁在本机（macOS）从来没真正跑过**：脚本用了 bash 4 的 `declare -A` 和 GNU `md5sum`，
  在系统自带 bash 3.2 上直接失败，但脚本仍然打印成功——等于「验收帧里有两张一模一样」这种缺陷不会被拦住。
  现在改成可移植实现（`md5` / `md5sum` 二选一 + 明文列表比对）。
- 帧尺寸核对不再写死期望值：与已入库版本比对，避免窗口被最大化时拍出的宽屏帧把「1280 下不溢出」这类结论带偏。
- 验收帧整套在 macOS 重拍，不再「Windows 旧帧 + macOS 新帧」混着 —— 混平台的帧会让
  「1280 宽度下不溢出」这类按尺寸的结论失去意义（主窗口 1280×800 与 1280×792 差的就是平台窗口度量）。

### Known issues（已记录，待裁定）

- 冷启动实测 3.5–3.9 s，未达 <2 s 目标（当前地板约 2.35 s）。
- jlink 打包 ⛔（自动模块限制）、自动更新 🚫（无发布渠道）、签名/公证 ⛔（缺证书）。
- ~~两条长期红已归因并修好~~（2026-10-30）：`StartupTraceTest` 断言的是测试运行器的进程年龄，
  `DialogButtonOrderTest` 把宿主平台的按钮预设当成 Windows 预设 —— 见上面两段改判与修复。

### Not yet released

本条不是变更，是一句实话：**上面所有能力都还没有面向使用者的发布产物**（无安装包、无 tag、无更新通道）。
现在能用的安装方式是「拉源码本地跑」，命令见 [CONTRIBUTING.md](CONTRIBUTING.md) §1–2。
