# 现状与限制（STATUS）

> 这份文档承担两件事：**能力面清单**（按能力，不按类名）与**完整限制清单**。
> 它是全仓库**唯一**记「验证基线数字」的地方 —— README、ROADMAP 都不再各自记一份，
> 因为历史上同一个数字在三处不一致过（`964/123` 与 `1108/134` 并存，而后者实测是 `1107/133`）。
> 数字必须是**回量出来的**，并写明量于何日、什么平台、什么命令。

## 1. 验证基线

| 项 | 值 |
|---|---|
| 命令 | `./scripts/build.sh`（= `./mvnw -B clean verify` + `java tools/TestCount.java`） |
| 最近一次全量回量 | 2026-11-02 · macOS 15.7.9 x86_64 · Temurin JDK 25.0.4.1+1 · 本机 Docker 起满五引擎（`scripts/dbmatrix.sh`）· **带 `set -a && . scripts/.dbmatrix.env`**（环境变量在，41 条真库用例是真跑的） |
| 结果 | **1407 条 / 155 类 / 0 失败**（另有 6 条跳过，全部是**设计如此**的门控，见下）—— R52 视图契约收官后回量（`./scripts/build.sh` 全绿，18:50，16:37 分钟；较上一版 R46 基线 **+11 条 / +1 类**，逐笔对得上：`adb-jdbc` 159 → 169 = **+10**（新类 `SqlServerViewDefinitionTest` 5 条不依赖 Docker 的 D-27 门 + 契约第 8 条「视图生命周期」在五引擎上各 1 条）、`adb-ui-shell` 727 → 728 = **+1**（`ViewDesignerDialogTest` 新增「MySQL 单句不再二次确认」）、`adb-introspect` 206 → 206 = **0**（`ViewLifecycleSqlTest` 那两条是**改写**：MySQL 挪进单句组、两句那条换成 SQLite 专属，条数不变）。+1 类就是 `SqlServerViewDefinitionTest`。<br>⚠️ 跨轮对比时**别把「用例多了 11 条」读成「能力多了 11 项」** —— 5 条是同一批缺陷的门禁层，5 条是同一批契约换个引擎再跑。逐模块：`adb-ui-shell` 728 · `adb-introspect` 206 · `adb-jdbc` 169 · `adb-safety` 76 · `adb-ui-core` 62 · `adb-transfer` 50 · `adb-session` 33 · `adb-sql` 31 · `adb-model` 30 · `adb-editing` 19 · `adb-fixtures` 3。<br>⚠️ 一条**非致命异常**留在了日志里：`ToolbarDiscoverabilityTest` 有一次 Monocle 渲染线程报 `Too small int buffer size 1180x800 [944000] > 880000`，重跑即绿，属离屏缓冲尺寸而非产品缺陷 —— 本轮**照旧出现一次**，且同一个类 11 条 0 失败（异常在渲染线程上、不进用例结论）。上一版记的另一条 `Key too long: ui.query.colwidth.…`（**D-18**）本轮**已消失** —— 偏好键改走有上限的组装，且 `SettingsStoreKeyTest` 钉住。<br>✅ R52 首跑日志里那五条「`[真库契约] 清理临时视图失败：视图不存在`」（**D-30**）**本轮已归零** —— 契约末步删掉的视图，`finally` 不再补删第二次；那条假警报的害处是把「状态已经是想要的」报成故障，下一次真泄漏会淹在噪音里 |

**口径警告（改数字前必读）**：4 个测试类用 `@Nested`，surefire 的 XML 会把外层类写成 `tests="0"`
（真实用例在内层类条目里）。按 `tests=` 属性统计会少算一百多条 —— 所以计数一律走
`tools/TestCount.java`（按 `<testcase>` 元素数），并且它同时断言总数下限。
`I18nAuditTest` 的汉字棘轮同理：**别用 grep 数**，口径不同的两个数放在一起只会让人以为棘轮又涨了。

另有 **6 条跳过**，两条都**不是**「环境没配所以没验」：
`adb-session` 5 条是 `CredentialStoreTest` 里的 Windows 凭据管理器（WinCred）用例，`assumeTrue` 按平台跳过，
本机是 macOS，这一半只能在 Windows 上跑；`adb-jdbc` 1 条是 `DatabaseVersionMatrixTest`，
它要 `ADB_TEST_VERSION_MATRIX=1` 才开跑（同一引擎多版本矩阵，跑一次十几分钟，故意不并进默认门禁）。
（上一版 34 条 —— 其中 28 条是「本机没有真库」的环境门控：`scripts/dbmatrix.sh` 起满五引擎后，
`adb-introspect` 的 14 条全开跑归零，`adb-jdbc` 的 15 条只剩那条故意门控的版本矩阵。）

## 2. 能力面

| 能力面 | 状态 | 边界（哪些是真的，哪些不是） |
|---|---|---|
| 外壳与布局 | ✅ | 七区（菜单 / 功能区 / 侧栏 / 页签 / 对象工具条 / 内容区 / 状态栏）；侧栏 250px、折叠 48px、可拖 200–360；macOS 用系统菜单栏；状态栏按页型换段（查询 / 数据 / 对象三套），不再摆「自动提交 · 只读」 |
| 连接与凭据 | ✅ | 六种方言**全部在真服务器上验过**：SQLite（本机）+ MySQL 8.4 / PostgreSQL 16 / MariaDB 11.8 / SQL Server 2022 / Oracle 23ai Free（`scripts/dbmatrix.sh` 五容器，`ExternalRealDatabaseTest` 5 引擎 × **8** 契约 = 40 例 + 1 例口令不外泄门 = 41 条）。⭐ R46 那一跑抓出四条只有真服务器才会报的缺陷（D-20 编造的 `sys.tables.collation_name` · D-22 Oracle 目录只认大写导致加引号对象全隐形 · D-24 `sys.triggers` 没有事件列 · D-25 `SHOW CREATE EVENT` 的定义体在第 4 列，按位置猜读到 `"SYSTEM"`）；R52 加第 8 条「视图生命周期」又抓出两条（D-27 · D-29，见 §2「视图生命周期」）。**仍未在真库验证**：TLS 证书链档位（要一套 PKI，矩阵容器只起非加密服务）。连接分组、收藏、复制、URI（含 Oracle EZConnect 与 SQL Server 分号参数串）；口令进系统凭据服务，**不落盘**；`connections.json` 落盘前剔除敏感键；HikariCP 池化（除 SQLite），SQLite 直连 |
| 连接向导 | ✅ | 两页 900×700：类型页 **一行 6 张彩色磁贴**（R43-b：只摆真有驱动的六家，各顶自绘厂商图形 + 品牌色；块居中，不留下半屏空白）+ 一行「其它数据库走兼容协议 / URI」的提示，属性页 7 页签 + `AIDB ⟷ Database` 示意图 + 底部五键。⭐ 原来照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，其中 16 张点进去是「本版不支持」—— 那不是它的形态（它摆这些家是因为真连得上去），我们摆出来只是让用户多点十六次空；复选栏、搜索框、滚动容器与「无驱动说明页」随死磁贴一起删，「其它怎么办」改成页面上明写的一行。SSL 页的档位与根证书参数**由方言自报**（PG `sslmode` / MySQL `sslMode` / MariaDB `sslMode`+`serverSslCert`，MariaDB 没有 prefer 档）；SQL Server（encrypt + trustServerCertificate 配对）与 Oracle（钱包 / sqlnet.ora）收起档位并写明去哪里填；SSH / HTTP 页如实标注「未实现」，不是空壳 |
| 对象树 | ✅ | 真实内省（表 / 视图 / 函数·过程；触发器按表归属在结构页，事件本版本不上树）；多连接同树；表节点懒加载展开列，失败可重试；**搜索框的作用域是整棵树**（表 / 视图 / 函数·过程 / 已保存查询），所以提示词只写中性的「Search」，不再点名「表、视图」把自己说窄（R44-G2-a）；搜索框右侧另有**清除一键**，空框时连位置都不占；**右键菜单按对象类型分家**（表节点：清空 / 重命名 / 删除表；视图节点：设计视图 / 删除视图 —— 不再在视图上摆只能失败的表级动作）；**连接节点顶自己那家的厂商图形 + 品牌色**（与向导磁贴读同一登记行，所以两处的 MySQL 同形同色；认不出方言时回落中性插头，不挑一家画上去） |
| 数据网格 | ✅ | 服务端分页（100/200/500/1000）、行首 16px 状态槽、类型行、列宽/顺序/显隐持久化、冻结列、矩形选择、⌘F 本页搜索、转置只读视图；工具条 13 具名键 + 视图/溢出两小键（放不下折进 `»`；R43-c：视图那颗 `≡▾` 不再钉 30px 宽，箭头不再压住图标）；底栏五键 + 页码框 + 齿轮（页大小/跳至记录）+ 放大镜 + 视图切换；**内嵌 Filter & Sort 生成器**（平铺 AND、Builder/Text 双模式、显式 Apply、Sort By 芯片） |
| Filter & Sort 面板 | ✅ | 网格内嵌（非弹窗），只改草稿不即输即查；`Criteria Edited` 标脏，`Apply Filter & Sort` 才生效。**边界**：底层 `DataQuery` 只有平铺 AND —— `and` 固定，`[Ω]`（OR 组）给如实说明页；Text 模式只读（自由 SQL 请去查询页）；`IN` 条件无法在行内表达，列头漏斗的按值筛选保留并原样带入 |
| 数据页写路径 | ✅ | 行内编辑 + 类型转换 + 按主键定位；NULL / 空串独立操作；批量操作走行多选 + 右键菜单 + 底部行操作条（TSV / INSERT / 克隆 / 置 NULL / 置空串 / 删除）；SQL 预览 → 单事务 → 失败回滚 → 保存后一次反向撤销 |
| 结构编辑 | ✅ | 加列 / 改列 / 重命名 / 删列 + 两级危险确认；索引、外键、触发器、CHECK 可增删；表注释可读可改（SQLite 无 → 按钮禁用；⭐ 2026-11-02 真库契约补了「写进去再原样读回来」，同时修掉一条**表注释在 MySQL/MariaDB/PG 上从未生效**的缺陷 —— 一行没闭合的块注释把方言的读取钩子整段吃掉，接口默认返回 null 悄悄生效）；改列整表重建时搬运 CHECK；⭐ R46 D-23：**加列对话框的类型候选与默认值改成跟着方言走**（原先是一份硬编码的 SQLite 清单，套在 Oracle 上会摆出 `UUID`/`TEXT`/`REAL` 这些它没有的类型，SQL Server 的默认值还会落进 `VARCHAR` —— 实测那一家的 `VARCHAR` 走库默认单字节代码页，中文不报错但折成问号，所以默认改成 `NVARCHAR`）。类型清单与默认值都由方言自报（`Dialect#columnTypes` / `#defaultColumnType`），帧只拍得到合上时那个默认值（`p7-new-column`），候选清单跟着库变那一半由 `StructureColumnTypeTest` + 真库矩阵守 |
| 建表 / 表级生命周期 | ✅ | 建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，三种方言各走自己的语法。字段网格八列 + `Virtual` 生成列 / `Key` 单下拉 / `Key Length` 前缀索引 / `Binary` / 属性面板自增；R43-c：字段页三段（工具条 / 网格 / 属性面板）在内容区整体垂直居中，列少时留白回到上下两端，不再在网格与面板之间留一条死空白；方言不支持的属性直接拦下并说明原因，不编假 DDL。`Ask AI` 一击开**一份**如实说明页（本版本没有 AI 服务；R43-b 前是三项下拉，三项开的是同一段文字）。⚠️ 建表弹窗里还不能录外键 / 索引 / CHECK；建表后不自动打开该表 |
| 视图生命周期 | ✅ | 新建 / 设计 / 删除视图走**弹窗设计器**（视图名 + `SELECT` 查询体 + 实时 SQL 预览，与建表同一条「预览即执行」：预览几句就跑几句）。重建写法由方言自报，⭐ R52 在真库上逐个量过：MySQL / MariaDB / PostgreSQL / Oracle 一句 `CREATE OR REPLACE VIEW`，SQL Server `CREATE OR ALTER VIEW`，**只剩 SQLite 没有单句形式** → 两句「先删再建」，此时预览显出两句并**追加第二次确认**（原先把 MySQL 也算进「没有」那一侧，判反了 = D-29：两句之间视图真的不存在，还白要一道危险确认）。查询体只收 `SELECT` / `WITH` / 括号开头的组合查询：粘整条 DDL、塞第二句 SQL 都当场拦下并写明原因。「设计视图」先在后台读回库里定义，**读不到就不开窗**（不用空 body 盖掉真定义）—— SQL Server 上这条路原本是 `return null` 的空壳，等于那个库上设计器从来没开过窗（D-27，R52 修：真查 `sys.sql_modules JOIN sys.views`）。✅ **已在真库跑过**（R52 · 契约第 8 条）：五引擎各跑一遍「建 → 重建 → 按产品读路径读回 → 删后必须报错」，三种重建写法都被执行过一次。⚠️ 边界：本版本**没有视图重命名**（名字格置灰并说明替代路径）、没有视图依赖图（`dependentViews` 只用于删表前那句警告，SQL Server 上返回空集 = 那句警告在那个库不出现）；视图数据页仍只读。⚠️ D-28 待裁定：PostgreSQL 的 `CREATE OR REPLACE VIEW` 只许在末尾**加列**，改名/删列服务端报错（实测 `cannot change name of view column "id" to "adb_marker"`）—— 要不要在失败前给一句主动提示还没拍。 |
| 查询页 | ✅ | 工具带双行 62px（保存 / 查询构造器 / 美化 / 代码片段 / 问 AI · 查询名▾ / **库▾** / Run▾ / Stop / Explain）；⭐ R44-G1：行 2 那颗是**真库选择器** —— 列出当前连接能切的库（方言自报 `listCatalogs`，后台读、读不到就退回只展示），当前库打勾，选中即把这条 SQL 复制进**新页签并绑到该库**（走左树同一条 catalog 虚拟连接路径，会话没开先连再开）；**不自动重跑**（重跑有写风险），状态栏明写「已切到数据库 X · 查询已复制到新页（未执行）」。SQLite 等无库层方言保持只展示、不假装可切。RichTextFX 编辑器（语法配色、行号、当前行、查找替换）；`⌘R` 当前语句、`⇧⌘R` 全部、`⌘.` 取消；结果区 `Message/Summary/Result N` 三段 + `Data/Info` 切面、服务端分页、执行计划、错误定位、查询历史（50 条，带对象上下文）、代码片段。执行计划按方言能力开放：SQL Server / Oracle 需要两步协议，按钮禁用并说明原因 |
| 已保存查询 | ✅ | 工作区对象页（名称 / 类型 / 修改时间 / SQL 四列 + `设计` / `新建` / `删除查询`）；侧栏 `Queries` 组双击或 Enter 打开；删除走危险确认（默认按钮是取消）；查询页保存后列表即时刷新 |
| 导入导出 | ✅ | 流式 CSV / TSV / JSON Lines / SQL / xlsx（POI SXSSF 写 + SAX 读）；字段映射、按目标列类型转换（含中文日期格式）、坏行报告带列名与原文；查询结果三档导出（本页 / 选中 / 全部） |
| 错误与超时 | ✅ | 错误归类（断连 / 权限 / 不存在 / 超时 / 锁 / 约束 / 语法）；语句超时 30s 并给建议；崩溃日志 `~/.agenticdb/logs/crash-*.log` |
| 外观 | ✅ | 暗亮双主题 + 跟随系统 + 本地记忆；四档密度；110 个设计令牌唯一真相（字号只有 11/12/13 三档）；图标走语义彩色层（同一套资产换主题自动换色）；六家**真有驱动**的数据库各有自绘图形与品牌色令牌（`vendor-*`，同风格自绘、非商标图标） |
| 键盘与可访问性 | ✅ | ⌘K 命令面板；六区焦点顺序；页签 / 切面键盘切换；逐页可朗读名称（`AccessibilityAuditTest` 断言） |
| 界面语言 | ✅ | 英文（默认）· 简体中文；主源码里的硬编码汉字由棘轮压到 8 条（且那 8 条是**输入 token**，不是文案） |
| 可观测性 | ✅ | `StartupTrace` 分段打点 → `~/.agenticdb/logs/startup.log`；诊断页 |
| 打包 | 🟡 | `scripts/package.sh` 出 app-image / dmg / msi / deb，实测可启动；**未签名、未公证、无自动更新、无发布渠道** |

## 3. 已知限制（完整清单）

### 性能

- **冷启动未达标**：目标 <2 s，实测 **3.5–3.9 s**（2026-10-26 · Windows 10 + JDK 27，`StartupTrace` 打点）。
  地板构成：JVM/Toolkit ≈0.95 s + 第一个 JavaFX 控件（Modena 样式表解析）≈0.5 s +
  `stage.show()` 首趟 CSS/布局 ≈0.86 s。**AppCDS 在开发启动路径上试过，不通。**
- 冷启动耗时**没有**门禁：`StartupTraceTest` 里原先那条「测试里首屏 <2s」删掉了 ——
  它量的是测试运行器的进程年龄（详见 `CHANGELOG.md` 的改判记录）。

### 平台

- 「跟随系统主题」只探测 macOS；Windows / Linux 一律默认亮色。
- 验收帧目前全部是 **macOS** 拍的（2026-10-30 起）。
- 弹窗按钮序的门禁原先只在 Windows 上绿：那条断言把**宿主平台的** `ButtonBar` 预设当成
  Windows 预设去比，而生产代码对不认识的预设故意不动（另一条门禁钉着这个「不动」）——
  于是 macOS 上必然假红。2026-10-30 改为**显式喂 Windows 预设**，跨平台一致；
  macOS 上按钮的真实位置由同类的坐标断言与实拍帧守。详见 [CHANGELOG.md](../CHANGELOG.md)。

### 能力缺口

- **Filter & Sort 面板只支持平铺 AND**：行间连接词固定 `and`，`[Ω]`（OR 条件组）本版本点开是如实说明页；
  `IN` 条件仍由列头漏斗的候选值多选提供，面板内原样保留、不可行内编辑；Text 模式是**只读**的生成表达式。
- 危险分级**第三层（驱动侧复查）不存在**：`DbSession.execute(sql)` 没有 `danger` 参数，
  绕过 UI 直接调会话层不会被拦。前两层（fail-closed 分类 + UI 确认）已落地。
- **SSH 隧道未实现**（连接向导里明说）；SSL/TLS 只做到「写驱动属性」，证书链校验与 SNI 待补。
- 函数 / 过程只读定义体（SQLite 没有 SQL 层函数），入口是左侧树的双击 / Enter；**视图已经能建 / 改 / 删**
  （R42 N28-a，见 §2「视图生命周期」），但**视图不能重命名**，也没有依赖图。
- 事件（MySQL EVENT）不在树上，因此本版本看不到事件定义体；触发器按表归属，在结构页查看。
- 建表弹窗不能录外键 / 索引 / CHECK（生成器支持，UI 还没有录入区）。

### 编辑与选择

- 无主键表与视图只读；保存的反向撤销对「无主键 / 主键为空的新增行」不完整（UI 会提示）。
- 反向撤销只覆盖最近一次保存，发生新编辑后不再提供。
- 转置视图只读，不参与批量选择；本页搜索 / 隐藏只作用当前页，不改变服务端分页。
- 数据页的加载与保存**无法真正取消** JDBC 语句（`⌘.` 只作用于查询页）。
- 表头「全选」只选当前页。

### 线程模型

- 连接建立与内省跑在 JavaFX 线程上；全局单线程执行器 `adb-db` 会让一个连接的慢查询阻塞其它连接
  （多连接各自的执行器已拆分，见 `DbExecutors`，但内省路径仍共享）。

### 分发

- 无自动更新 / 版本检查（无发布渠道 + 离线环境 + 无安装包签名）。
- 无代码签名与公证（需 Apple Developer ID 与 Windows 代码签名证书 —— 外部资源）。
- jlink 自定义运行时不可行（RichTextFX / ControlsFX 是自动模块）。

### 测试环境

- 五种引擎由 `scripts/dbmatrix.sh` 在本机 Docker 起满（MySQL 8.4 · PostgreSQL 16 · MariaDB 11.8 ·
  SQL Server 2022 · Oracle 23ai Free）。口令由脚本**现场随机生成**，只写进未跟踪的
  `scripts/.dbmatrix.env`（600），仓库里不留任何密码；测试侧一律从 `ADB_TEST_*` 环境变量读。
- 真库契约现在是**八条**（R52 加第 8 条「视图生命周期」）：`ExternalRealDatabaseTest` 展开成
  5 引擎 × 8 = 40 例，加一条「环境读取不外泄口令」= 41 条。**加一条契约只加一行**。
  ⚠️ 契约跑完需要容器在跑；没配 `ADB_TEST_*` 时这些用例按引擎逐条跳过（不是整类），
  所以默认门禁里看到的跳过数是**缺了几个库**，不是「有没有测」。
- **版本矩阵默认不跑**：`DatabaseVersionMatrixTest` 要 `ADB_TEST_VERSION_MATRIX=1` 才开
  （同一家多版本，一次十几分钟）。所以「MySQL 5.7」这一档目前只有方言层的承诺，没有执行过的证据。
- WinCred（Windows 凭据管理器）那 5 条只能在 Windows 上跑，macOS 上按平台跳过。
- TLS 证书链档位没有真库证据 —— 需要一套 PKI，不是起个容器就有。

## 4. 明确不做

自绘标题栏、AI 面板、插件系统、ER 图（评估中，未承诺）、多用户权限、计划任务、
内嵌浏览器、本地 HTTP 服务。理由见 [README.md](../README.md) 与 [docs/ROADMAP.md](ROADMAP.md)。

## 5. 相关文档

- [docs/ROADMAP.md](ROADMAP.md) — 对外路线（支持矩阵 / 进行中 / 接下来 / 明确不做）
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — 模块、数据流、线程模型、编辑与 SQL 不变量
- [docs/UI-VISUAL-SPEC.md](UI-VISUAL-SPEC.md) — 可照着实现的 UI 规格
- [docs/UI-FORMS-AND-STATES.md](UI-FORMS-AND-STATES.md) — 弹窗 vs 标签页裁定、空白态清单
- [docs/COMPONENT-SELECTION.md](COMPONENT-SELECTION.md) — 组件选型与许可
- [CONTRIBUTING.md](../CONTRIBUTING.md) — 环境、命令、四条硬规则
