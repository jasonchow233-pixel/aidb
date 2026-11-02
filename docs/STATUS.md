# 现状与限制（STATUS）

> 这份文档承担两件事：**能力面清单**（按能力，不按类名）与**完整限制清单**。
> 它是全仓库**唯一**记「验证基线数字」的地方 —— README、ROADMAP 都不再各自记一份，
> 因为历史上同一个数字在三处不一致过（`964/123` 与 `1108/134` 并存，而后者实测是 `1107/133`）。
> 数字必须是**回量出来的**，并写明量于何日、什么平台、什么命令。

## 1. 验证基线

| 项 | 值 |
|---|---|
| 命令 | `./scripts/build.sh`（= `./mvnw -B clean verify` + `java tools/TestCount.java`） |
| 最近一次全量回量 | 2026-11-02 · macOS 15.7.9 x86_64 · Temurin JDK 25.0.4.1+1 · 本机 Docker 起满五引擎 + 每家两把库（`scripts/dbmatrix.sh`）**+ 三家加密对端**（`dbmatrix.sh tls-up`）· **带 `set -a && . scripts/.dbmatrix.env`**（环境变量在，46 条主矩阵 + 18 条 TLS 矩阵都是真跑的：两类分别 `Tests run: 46, Failures: 0, Skipped: 0` 与 `Tests run: 18, Failures: 0, Skipped: 0`）。同一轮还有 **4 张验收帧实拍在这套矩阵的真库上**（`ADB_MATRIX=mysql` 走 `scripts/shots.sh`，`shop` 里的 `users` / `orders` / `v_active_users` 由 `dbmatrix.sh` 的 `seed_frames` 幂等铺好 —— 种子补 `orders` 是在这次全量之后，随后带环境重跑 `adb-jdbc` + `adb-introspect`：194 执行 / 1 跳过 与 206 / 0，与下表逐家一致，全量数不变） |
| 结果 | **1447 条 / 156 类 / 0 失败**（另有 6 条跳过，全部是**设计如此**的门控，见下）—— R47-b 真库验收帧 + D-36 收官后回量（`./scripts/build.sh` 全绿，2026-11-02 23:32，10:58 分钟；较 R47-a 基线 1444 / 156 = **+3 条 / 0 类**，逐笔对得上：三条全在 `adb-ui-shell` 732 → 735 —— `AppShellTest` +1（D-36「换库要把 SQL 带进新页」的外壳级门禁）、`ScreenshotScriptAuditTest` +2（摆态名必须在 pose 分派里有分支 / 矩阵帧必须被门控且零口令字面量）；其余逐家一字不变。<br>逐模块：`adb-ui-shell` 735 · `adb-introspect` 206 · `adb-jdbc` 194 · `adb-safety` 84 · `adb-ui-core` 62 · `adb-transfer` 50 · `adb-session` 33 · `adb-sql` 31 · `adb-model` 30 · `adb-editing` 19 · `adb-fixtures` 3（合计 1447）。<br>⚠️ **口径（本轮实测出来的）**：同一轮先跑了一次**不带**矩阵环境的回量，出来是 **1370 执行 / 83 跳过**（`adb-jdbc` 64、`adb-introspect` 14、WinCred 5）—— 环境不在时数字会自己掉 77 条，所以基线一律**带环境量**，这两个数不可比、也不许互相抄。<br>⚠️ **本轮三处红都是新代码自己踩的，不是文案**：① **D-36**（产品缺陷，自 R44-G1 起就在）：查询页换库后新页是**空的**，而状态栏说「SQL 已复制到新查询页」—— 外壳问的是 `TabBar.contentOf(id)`，那里存的是标签条上的**胶囊** `HBox`，`instanceof QueryPage` 永不成立；行为门禁看不见它（`QueryCatalogSelectorTest` 把回调桩掉了，从不走外壳回读），是帧拍到真 MySQL 上才露馅的。变异验证：读取口退回胶囊 → 红 **1**。② 首版在 `AppShell` 里按方言 id `switch` 出 `DialectKind.MYSQL/…`，被 `DialectBranchAuditTest`（A1：方言分支不许散在外壳）当场拦下 —— 全量首跑红 **1**；改成 `DialectKind.of(engine)`，环境变量前缀只做名字对齐。③ 那几条 `[screenshot]` 控制台诊断一开始用中文写，被 `I18nAuditTest.hardcodedChineseIsRatchetedDown` 拦下 —— 红 **8 条**（棘轮上限 8 全留给 `CellValueParser`/`CellRenderer` 的历史残留）；定下口径：**外壳的控制台诊断一律 ASCII，界面文案才走 `I18n.t(...)`**。<br>⚠️ **测试侧纪律（本轮新增）**：外壳级门禁留下脏页签时，`@BeforeEach` 的 `resetUi` 一点关闭钮就撞上模态「未保存」确认（`showAndWait` 卡在 FX 线程上）—— 后果不是报红而是**把后面整类测试拖成超时**。断言前先 `markClean`（同类的 `opensQueryPageRunsSqlAndShowsResultTab` 早就是这个手法）。<br>⚠️ 日志里的非致命异常（回量时 `grep -c` 数出来的，不是照上一版抄）：Monocle 离屏缓冲异常（`Too small int buffer size 1180x800 [944000] > 880000`，落在 `ToolbarDiscoverabilityTest` 的渲染线程，该类仍 `Tests run: 11, Failures: 0`）**本轮 1 次**（上一轮为 0，重跑即绿、不进用例结论）；`Key too long: ui.query.colwidth.…`（**D-18**）**0 次**（连续第四轮）；`[真库契约] 清理临时视图失败`（**D-30**）**0 次**（连续第四轮）。<br>上一轮（R47-a）那两处**假门禁**的翻案 —— `SHOW SESSION STATUS` 回两列导致 ②③⑤ 绿灯是假的、以及中文文案漂回旧话而门禁红 0 条 —— 连同 **D-35**（`onFx` 吞 FX 线程 NPE，把失败指错方向）记在 CHANGELOG 与 `docs/UI-VISUAL-SPEC.md` 的 R47-a 行里，不在本行的数字里。 |



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
| 连接与凭据 | ✅ | 六种方言**全部在真服务器上验过**：SQLite（本机）+ MySQL 8.4 / PostgreSQL 16 / MariaDB 11.8 / SQL Server 2022 / Oracle 23ai Free（`scripts/dbmatrix.sh` 五容器 + 每家两把库，`ExternalRealDatabaseTest` 5 引擎 × **9** 契约 = 45 例 + 1 例口令不外泄门 = 46 条）。⭐ R46 那一跑抓出四条只有真服务器才会报的缺陷（D-20 编造的 `sys.tables.collation_name` · D-22 Oracle 目录只认大写导致加引号对象全隐形 · D-24 `sys.triggers` 没有事件列 · D-25 `SHOW CREATE EVENT` 的定义体在第 4 列，按位置猜读到 `"SYSTEM"`）；R52 加第 8 条「视图生命周期」又抓出两条（D-27 · D-29，见 §2「视图生命周期」）；第 9 条「切库落点与隔离」把查询页那颗库选择器从「只由单测钉住」补成**真库证据**（同一台服务器两把库、同一个表名、字面相同的 SQL，两边各读各的行 —— 见 §2「查询页」）。⭐ R47-a 又起一圈**加密对端**（`dbmatrix.sh tls-up`：自签 CA + 一把冒名 CA，MySQL 33071 / MariaDB 33072 / PostgreSQL 54331），3 引擎 × 6 契约 = **18 例**：不加密真是明文、require 真把会话加密（`Ssl_cipher` / `pg_stat_ssl` 各读各的）、给了签发方链校验真通过、换一把没签过服务器的 CA 真被拒、证书里写着的名字（`localhost`）连得上、证书里没写的（`127.0.0.1`）真被挡 —— 这一跑抓出 **D-33**（选「不加密」时一个参数都不写，而三家驱动的默认都是「能加密就加密」：那个标签一直在骗人）、**D-32**（证书失败被归成「连接已断开」，建议把人支去查网络）、**D-34**（连接根本没建起来被归成「查询超时」）。**仍未在真库验证**：SNI（没有按 SNI 选多域名的对端形状，不写「已验」）与 **MySQL 5.7**（版本矩阵是 opt-in）。连接分组、收藏、复制、URI（含 Oracle EZConnect 与 SQL Server 分号参数串）；口令进系统凭据服务，**不落盘**；`connections.json` 落盘前剔除敏感键；HikariCP 池化（除 SQLite），SQLite 直连 |
| 连接向导 | ✅ | 两页 900×700：类型页 **一行 6 张彩色磁贴**（R43-b：只摆真有驱动的六家，各顶自绘厂商图形 + 品牌色；块居中，不留下半屏空白）+ 一行「其它数据库走兼容协议 / URI」的提示，属性页 7 页签 + `AIDB ⟷ Database` 示意图 + 底部五键。⭐ 原来照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，其中 16 张点进去是「本版不支持」—— 那不是它的形态（它摆这些家是因为真连得上去），我们摆出来只是让用户多点十六次空；复选栏、搜索框、滚动容器与「无驱动说明页」随死磁贴一起删，「其它怎么办」改成页面上明写的一行。SSL 页的档位与根证书参数**由方言自报**（PG `sslmode` / MySQL `sslMode` / MariaDB `sslMode`+`serverSslCert`，MariaDB 没有 prefer 档）；⭐ R47-a 在加密对端上跑完之后翻两处：① 「不加密」**真的写** `DISABLED` / `DISABLE` / `disable`（原先不写参数，而三家默认都是「能加密就加密」—— 见 D-33）；② MySQL 的「根证书」格在 VERIFY_CA 以上**禁用**并把说明写成 `trustCertificateKeyStoreUrl`（Connector/J 8.2 既没有 `sslCA` 也没有 `sslCapath`，未知属性被静默忽略 —— 收一个填了不生效的路径比不给这个格子更糟）；SQL Server（encrypt + trustServerCertificate 配对）与 Oracle（钱包 / sqlnet.ora）收起档位并写明去哪里填；SSH / HTTP 页如实标注「未实现」，不是空壳 |
| 对象树 | ✅ | 真实内省（表 / 视图 / 函数·过程；触发器按表归属在结构页，事件本版本不上树）；多连接同树；表节点懒加载展开列，失败可重试；**搜索框的作用域是整棵树**（表 / 视图 / 函数·过程 / 已保存查询），所以提示词只写中性的「Search」，不再点名「表、视图」把自己说窄（R44-G2-a）；搜索框右侧另有**清除一键**，空框时连位置都不占；**右键菜单按对象类型分家**（表节点：清空 / 重命名 / 删除表；视图节点：设计视图 / 删除视图 —— 不再在视图上摆只能失败的表级动作）；**连接节点顶自己那家的厂商图形 + 品牌色**（与向导磁贴读同一登记行，所以两处的 MySQL 同形同色；认不出方言时回落中性插头，不挑一家画上去） |
| 数据网格 | ✅ | 服务端分页（100/200/500/1000）、行首 16px 状态槽、类型行、列宽/顺序/显隐持久化、冻结列、矩形选择、⌘F 本页搜索、转置只读视图；工具条 13 具名键 + 视图/溢出两小键（放不下折进 `»`；R43-c：视图那颗 `≡▾` 不再钉 30px 宽，箭头不再压住图标）；底栏五键 + 页码框 + 齿轮（页大小/跳至记录）+ 放大镜 + 视图切换；**内嵌 Filter & Sort 生成器**（平铺 AND、Builder/Text 双模式、显式 Apply、Sort By 芯片） |
| Filter & Sort 面板 | ✅ | 网格内嵌（非弹窗），只改草稿不即输即查；`Criteria Edited` 标脏，`Apply Filter & Sort` 才生效。**边界**：底层 `DataQuery` 只有平铺 AND —— `and` 固定，`[Ω]`（OR 组）给如实说明页；Text 模式只读（自由 SQL 请去查询页）；`IN` 条件无法在行内表达，列头漏斗的按值筛选保留并原样带入 |
| 数据页写路径 | ✅ | 行内编辑 + 类型转换 + 按主键定位；NULL / 空串独立操作；批量操作走行多选 + 右键菜单 + 底部行操作条（TSV / INSERT / 克隆 / 置 NULL / 置空串 / 删除）；SQL 预览 → 单事务 → 失败回滚 → 保存后一次反向撤销 |
| 结构编辑 | ✅ | 加列 / 改列 / 重命名 / 删列 + 两级危险确认；索引、外键、触发器、CHECK 可增删；表注释可读可改（SQLite 无 → 按钮禁用；⭐ 2026-11-02 真库契约补了「写进去再原样读回来」，同时修掉一条**表注释在 MySQL/MariaDB/PG 上从未生效**的缺陷 —— 一行没闭合的块注释把方言的读取钩子整段吃掉，接口默认返回 null 悄悄生效）；改列整表重建时搬运 CHECK；⭐ R46 D-23：**加列对话框的类型候选与默认值改成跟着方言走**（原先是一份硬编码的 SQLite 清单，套在 Oracle 上会摆出 `UUID`/`TEXT`/`REAL` 这些它没有的类型，SQL Server 的默认值还会落进 `VARCHAR` —— 实测那一家的 `VARCHAR` 走库默认单字节代码页，中文不报错但折成问号，所以默认改成 `NVARCHAR`）。类型清单与默认值都由方言自报（`Dialect#columnTypes` / `#defaultColumnType`），帧只拍得到合上时那个默认值（`p7-new-column`），候选清单跟着库变那一半由 `StructureColumnTypeTest` + 真库矩阵守 |
| 建表 / 表级生命周期 | ✅ | 建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，三种方言各走自己的语法。字段网格八列 + `Virtual` 生成列 / `Key` 单下拉 / `Key Length` 前缀索引 / `Binary` / 属性面板自增；R43-c：字段页三段（工具条 / 网格 / 属性面板）在内容区整体垂直居中，列少时留白回到上下两端，不再在网格与面板之间留一条死空白；方言不支持的属性直接拦下并说明原因，不编假 DDL。`Ask AI` 一击开**一份**如实说明页（本版本没有 AI 服务；R43-b 前是三项下拉，三项开的是同一段文字）。⚠️ 建表弹窗里还不能录外键 / 索引 / CHECK；建表后不自动打开该表 |
| 视图生命周期 | ✅ | 新建 / 设计 / 删除视图走**弹窗设计器**（视图名 + `SELECT` 查询体 + 实时 SQL 预览，与建表同一条「预览即执行」：预览几句就跑几句）。重建写法由方言自报，⭐ R52 在真库上逐个量过：MySQL / MariaDB / PostgreSQL / Oracle 一句 `CREATE OR REPLACE VIEW`，SQL Server `CREATE OR ALTER VIEW`，**只剩 SQLite 没有单句形式** → 两句「先删再建」，此时预览显出两句并**追加第二次确认**（原先把 MySQL 也算进「没有」那一侧，判反了 = D-29：两句之间视图真的不存在，还白要一道危险确认）。查询体只收 `SELECT` / `WITH` / 括号开头的组合查询：粘整条 DDL、塞第二句 SQL 都当场拦下并写明原因。「设计视图」先在后台读回库里定义，**读不到就不开窗**（不用空 body 盖掉真定义）—— SQL Server 上这条路原本是 `return null` 的空壳，等于那个库上设计器从来没开过窗（D-27，R52 修：真查 `sys.sql_modules JOIN sys.views`）。✅ **已在真库跑过**（R52 · 契约第 8 条）：五引擎各跑一遍「建 → 重建 → 按产品读路径读回 → 删后必须报错」，三种重建写法都被执行过一次。⭐ R47-b 补上**画面证据**（此前只有行为证据）：`docs/screenshots/p6-new-view-mysql.png`（`CREATE VIEW \`v_paid_orders\`` 的方言预览）、`p6-design-view-mysql.png`（真 MySQL 上读回 `v_active_users` 的定义、预览就一句 `CREATE OR REPLACE VIEW`、没有两句、没有多余的危险确认 —— D-29 的那一格）。⚠️ 如实记：MySQL 规范化后的读回是**一行长句**，720px 弹窗要看全得横向滚动 —— 那是 MySQL 自己的形态。SQL Server / Oracle / PG 三家的视图形态帧**还没拍**。⚠️ 边界：本版本**没有视图重命名**（名字格置灰并说明替代路径）、没有视图依赖图（`dependentViews` 只用于删表前那句警告，SQL Server 上返回空集 = 那句警告在那个库不出现）；视图数据页仍只读。⚠️ D-28 待裁定：PostgreSQL 的 `CREATE OR REPLACE VIEW` 只许在末尾**加列**，改名/删列服务端报错（实测 `cannot change name of view column "id" to "adb_marker"`）—— 要不要在失败前给一句主动提示还没拍。 |
| 查询页 | ✅ | 工具带双行 62px（保存 / 查询构造器 / 美化 / 代码片段 / 问 AI · 查询名▾ / **库▾** / Run▾ / Stop / Explain）；⭐ R44-G1：行 2 那颗是**真库选择器** —— 列出当前连接能切的库（方言自报 `listCatalogs`，后台读、读不到就退回只展示），当前库打勾，选中即把这条 SQL 复制进**新页签并绑到该库**（走左树同一条 catalog 虚拟连接路径，会话没开先连再开）；**不自动重跑**（重跑有写风险），状态栏明写「已切到数据库 X · 查询已复制到新页（未执行）」。⭐ R52 之前这一条只有两条断字符串的单测（id = 父id@库名、database 字段被换掉），**没人在真库上问过「语句到底落在哪儿」** —— 现由契约第 9 条钉住：同主机同口令的两把库、同一个表名、字面完全相同的 `CREATE` / `SELECT`，两边各读各的行（没切过去会撞「表已存在」，切过去但隔离不干净则两边行数相同），且两条会话的「当前库」各自读对（SQL Server 的库名走 `databaseName` 属性、PG / MySQL 走 URL 路径段，写错的症状都是「连得上、落错库」）。SQLite 等无库层方言保持只展示、不假装可切；Oracle 那一支仍钉「没有库层 ≠ 连现在在哪儿都读不出」（实测 `SYS_CONTEXT('USERENV','DB_NAME')` 回 `FREEPDB1`）。⭐ R47-b：这颗控件**第一次有画面证据** —— `docs/screenshots/p3-query-catalog.png`（真 MySQL 上 `库▾` 展开，列出 `shop` / `shop2`、勾在当前库）与 `p3-query-catalog-bound.png`（切到第二把库后的页面态）；正是后一帧抓出 **D-36**（新页其实是空的，状态栏那句「SQL 已复制到新查询页」在骗人 —— 见 §1 基线行与 CHANGELOG）。RichTextFX 编辑器（语法配色、行号、当前行、查找替换）；`⌘R` 当前语句、`⇧⌘R` 全部、`⌘.` 取消；结果区 `Message/Summary/Result N` 三段 + `Data/Info` 切面、服务端分页、执行计划、错误定位、查询历史（50 条，带对象上下文）、代码片段。执行计划按方言能力开放：SQL Server / Oracle 需要两步协议，按钮禁用并说明原因 |
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
- 验收帧目前全部是 **macOS** 拍的（2026-10-30 起）。⭐ R47-b 起其中 **4 张拍在本机 Docker 的真服务器上**
  （`ADB_MATRIX` 那条通道：`库▾` 展开态 / 切到第二把库后的页面态 / MySQL 侧新建与改视图的方言形态）；
  此前 47 张**全部**拍在 SQLite 演示库上，而 SQLite 没有「库」这一层，
  所以「下拉里有两把库」这类只有真服务器才有的形状一直没有画面证据。
- 弹窗按钮序的门禁原先只在 Windows 上绿：那条断言把**宿主平台的** `ButtonBar` 预设当成
  Windows 预设去比，而生产代码对不认识的预设故意不动（另一条门禁钉着这个「不动」）——
  于是 macOS 上必然假红。2026-10-30 改为**显式喂 Windows 预设**，跨平台一致；
  macOS 上按钮的真实位置由同类的坐标断言与实拍帧守。详见 [CHANGELOG.md](../CHANGELOG.md)。

### 能力缺口

- **Filter & Sort 面板只支持平铺 AND**：行间连接词固定 `and`，`[Ω]`（OR 条件组）本版本点开是如实说明页；
  `IN` 条件仍由列头漏斗的候选值多选提供，面板内原样保留、不可行内编辑；Text 模式是**只读**的生成表达式。
- 危险分级**第三层（驱动侧复查）不存在**：`DbSession.execute(sql)` 没有 `danger` 参数，
  绕过 UI 直接调会话层不会被拦。前两层（fail-closed 分类 + UI 确认）已落地。
- **SSH 隧道未实现**（连接向导里明说）。SSL/TLS 这一面 R47-a 已经踩过实土：档位取值、会话是否真加密、
  链校验是否真生效、主机名不匹配是否真被拒都在本机加密对端上跑过（18 条契约）；
  ⚠️ **SNI 没有单独证据** —— 三家对端都不是「按 SNI 选多域名证书」的形状，所以这一条不写「已验」。
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
  SQL Server 2022 · Oracle 23ai Free），**有库层的四家各起两把库**（`shop` + `shop2`，第 9 条契约要用）。
  口令由脚本**现场随机生成**，只写进未跟踪的
  `scripts/.dbmatrix.env`（600），仓库里不留任何密码；测试侧一律从 `ADB_TEST_*` 环境变量读。
- 真库契约现在是**九条**（R52 连加两条：第 8 条「视图生命周期」、第 9 条「切库落点与隔离」）：
  `ExternalRealDatabaseTest` 展开成 5 引擎 × 9 = 45 例，加一条「环境读取不外泄口令」= **46 条**。
  **加一条契约只加一行**。第 9 条另需第二把库：`scripts/dbmatrix.sh up` 在建完 `shop` 之后再建
  `shop2` 并写进 `ADB_TEST_*_DB2`（有库层的四家各一把；Oracle 没有这一层，故**故意没有** DB2 键）。
  ⚠️ 契约跑完需要容器在跑；没配 `ADB_TEST_*` 时这些用例按引擎逐条跳过（不是整类），
  所以默认门禁里看到的跳过数是**缺了几个库**，不是「有没有测」。缺 `*_DB2` 时第 9 条同样
  **按引擎跳过并说明缺什么** —— 「只有一把库」验不了落点，假通过比跳过更糟。
  ⚠️ 老环境文件不用重建：`up` 会给已存在的 `.dbmatrix.env` **只补缺失的 `*_DB2` 键**，
  已生成的口令保持原样（重生成会让当前跑着的容器对不上号）。
- **版本矩阵默认不跑**：`DatabaseVersionMatrixTest` 要 `ADB_TEST_VERSION_MATRIX=1` 才开
  （同一家多版本，一次十几分钟）。所以「MySQL 5.7」这一档目前只有方言层的承诺，没有执行过的证据。
- WinCred（Windows 凭据管理器）那 5 条只能在 Windows 上跑，macOS 上按平台跳过。
- TLS 那一圈**有真库证据**（R47-a）：`scripts/dbmatrix.sh tls-up` 起三个加密对端（自签 CA + 一把冒名 CA，
  私钥只落在 gitignore 的 `scripts/.dbmatrix-tls/`，口令仍只进 `scripts/.dbmatrix.env`），
  `ExternalTlsDatabaseTest` 三家 × 6 条 = 18 例；没配 `ADB_TEST_*_TLS_*` 时按引擎逐条跳过。
  ⚠️ 同一句话里两个坑本轮都踩过：① `SHOW SESSION STATUS LIKE 'Ssl_cipher'` 回**两列**，取第 1 列拿到的是
  变量名 `Ssl_cipher`（非空 → 「加密了」的假绿灯）；② 负例异常里混着 `communications link failure`
  与 `Could not connect`，证书分支必须排在连接分支**前面**才归得对（D-32）。

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
