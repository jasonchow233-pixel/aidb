# 现状与限制（STATUS）

> 这份文档承担两件事：**能力面清单**（按能力，不按类名）与**完整限制清单**。
> 它是全仓库**唯一**记「验证基线数字」的地方 —— README、ROADMAP 都不再各自记一份，
> 因为历史上同一个数字在三处不一致过（`964/123` 与 `1108/134` 并存，而后者实测是 `1107/133`）。
> 数字必须是**回量出来的**，并写明量于何日、什么平台、什么命令。

## 1. 验证基线

| 项 | 值 |
|---|---|
| 命令 | `./scripts/build.sh`（= `./mvnw -B clean verify` + `java tools/TestCount.java`） |
| 最近一次全量回量 | 2026-11-03 · macOS 15.7.9 x86_64 · Temurin JDK 25.0.4.1+1 · 本机 Docker 起满五引擎 + 每家两把库（`scripts/dbmatrix.sh`）**+ 三家加密对端**。**两个数各自量过**：① **带** `set -a && . scripts/.dbmatrix.env` 那一次 14:48→**15:02**（13:52 分钟，全绿）—— 五套真库矩阵都是真跑的：主矩阵 `ExternalRealDatabaseTest` 46 / 0 失败 / 0 跳过、TLS 矩阵 `ExternalTlsDatabaseTest` 18 / 0 / 0、写回矩阵 `EditWriteBackMatrixTest` 10 / 0 / 0、⭐ 导出往返 `TransferRoundTripMatrixTest` 5 / 0 / 0（契约第 12 条）、⭐ SSH 跳板 `SshTunnelMatrixTest` 5 / 0 / 0（契约第 13 条）；② **不带**环境那一次 15:03→**15:16**（13:01 分钟，全绿）。两次跑的是同一份源码（R55-a/b/c + D-41/D-42 的全部改动 + 本轮 D-43 的判定收口都在窗口内），量完代码一行没动。⚠️ **本轮第一次跑（14:36）红了一条 Oracle 契约，是 D-43**：容器刚重启，`DBMS_METADATA.GET_DDL` 冷启动超过 socket 读超时，抛 `ORA-18730 … Socket read timed out` —— 旧判定是 6 个测试类里 7 份**大小写敏感**的文案副本，小写 `read` 对不上大写字面的 `"Read timed out"`，环境抽风被端成红色缺陷（焐热后同一语句 1.3 秒返回，定性为环境因素）。收成 `ExternalUnreachable` 唯一出处后重跑才是上面这两个数；账见 CHANGELOG 的 D-43 与 §测试环境 |
| 结果 | **1530 条 / 163 类 / 0 失败**（另有 6 条跳过，全部是**设计如此**的门控，见下）—— R56（文档债清理 + D-43 判定收口）收官后回量（`./scripts/build.sh` 全绿，2026-11-03 15:02，带矩阵环境；较 R54 基线 1481 / 159 = **+49 条 / +4 类**，逐模块对得上：`adb-jdbc` 198 → **219**（+21 = 三个此前不存在的类整建制进来：`SshTunnelTest` 9 + `SshTunnelMatrixTest` 5 + `ExternalUnreachableTest` 7）、`adb-ui-shell` 752 → **766**（+14 = R55-a 空值接线 4 条 + R55-c 向导与源码审计 + D-41/摆态门禁 —— **逐类拆分没复核，只记模块级**）、`adb-introspect` 206 → **211**（+5 = `TransferRoundTripMatrixTest`）、`adb-session` 33 → **38**（+5 = `ConnectionStoreTest` 秘密分家）、`adb-safety` 84 → **88**（+4 = SSH 失败分类档）；其余逐家一字不变。**+4 类** = R55 的三个测试类 + 本轮 `ExternalUnreachableTest`。<br>逐模块：`adb-ui-shell` 766 · `adb-ui-core` 62 · `adb-safety` 88 · `adb-sql` 31 · `adb-model` 30 · `adb-fixtures` 3 · `adb-editing` 32 · `adb-introspect` 211 · `adb-jdbc` 219 · `adb-session` 38 · `adb-transfer` 50（合计 1530）。<br>⚠️ **口径一（数字对不上时先看这条）**：`adb-jdbc` 按 surefire 的 `tests=` 属性求和比门禁数多 1 —— 差的那 1 条是 `DatabaseVersionMatrixTest` 的设计性跳过（`@Nested` 口径警告见本节下方）。两个数不是同一口径，别互相抄。<br>⚠️ **口径二（本轮两个数各自量过，不是抄上一版）**：同一轮**不带**矩阵环境的回量 = **1433 执行 / 103 跳过**（`adb-editing` 22 跳 10、`adb-jdbc` 151 跳 69、`adb-introspect` 192 跳 19、WinCred 跳 5）；带环境是 1530 执行 / 6 跳。两口径的**执行差 = 97**（上一轮是 87）—— 涨的 10 条正是本轮新立的两条吃环境的契约：第 12 条导出往返 5 例 + 第 13 条 SSH 跳板 5 例；本轮新增的门禁（`ExternalUnreachableTest` 等）全不吃环境，所以差**恰好 +10**，账闭环（1433 + 103 = 1530 + 6 = 1536）。所以基线一律**带环境量**，这两个数不可比、也不许互相抄 —— 这也解释了「为什么我本地的数比文档少 97」。<br>⚠️ **本轮的性质**：R56 不添产品功能 —— 它把 R55 欠的文档账还清（CHANGELOG 补的五条与 §2/§3 的翻转），并让回量**自己开口说话**：第一次跑就抓出一条只有带环境全量才会现形的测试侧缺陷（D-43，和 D-32 / R55-c 同族 —— 「按文案猜异常」在本仓库第三次踩，这次踩在测试侧）。<br>⚠️ **上一轮（R47-c/D）的四处红/变异不在本行数字里，账在 CHANGELOG 与 `docs/UI-VISUAL-SPEC.md` §15**：D-38（PG / SQL Server 的 `readDdl(view=true)` 是 `return null` 空壳 → 那两家的「设计视图」一次都没开过窗）、D-39（快门落在「还没连上」那一刻 → 画面是演示库、文件名写着 oracle）、D-37（方言 id 拼错却退回演示库拍完还返回 0）、D-37b（一次正确的 abort 在 CI 上长得和成功一模一样）。<br>⚠️ **测试侧纪律（R47-b 定的，本轮继续用）**：外壳级门禁留下脏页签时，`@BeforeEach` 的 `resetUi` 一点关闭钮就撞上模态「未保存」确认（`showAndWait` 卡在 FX 线程上）—— 后果不是报红而是**把后面整类测试拖成超时**。断言前先 `markClean`。<br>⚠️ **`./mvnw -pl <模块>` 不带 `-am` 会用本地仓库里已安装的旧构件**（本轮验证 D-43 变异时继续遵守）—— 变异验证一律带 `-am`。<br>⚠️ 日志里的非致命异常（**带环境那一次**数出来的）：Monocle 离屏缓冲异常（`Too small int buffer size …`）**1 次**（`ToolbarDiscoverabilityTest` 拍 1180×800 离屏缓冲，非致命、该类全绿 —— 上一轮记的 0，本轮复现 1 次，如实记）；`Key too long: ui.query.colwidth.…`（**D-18**）**0 次**（连续第八轮）；`[真库契约] 清理临时视图失败`（**D-30**）**0 次**（连续第八轮）；`[screenshot] FRAME ABORTED` **0 次**。不带环境那一次上述四项同为 1 / 0 / 0 / 0。 |
| R57-a/b/d 之后（**模块级实测；全仓仍未重跑，别把上面的数加总**） | 只量到**模块级**：`./mvnw -B test -pl :adb-ui-shell` = **815 条 / 0 失败 / 0 跳过**（2026-11-03，不带矩阵环境）。逐段对得上：R56 记的 766 → R57-a **+27**（`SqlCompletionsTest` 19 + `QueryCompletionsTest` 8）→ R57-b/d **+22**（`ResultExportTest` 13 + `QueryPageTest` 19 → 28）。这 49 条**一条都不吃矩阵环境**（纯逻辑用假 `Source`，FX 用内存 SQLite；本轮 0 跳过就是证据），所以带环境全量重跑时应当落在 1530 + 49 —— ⚠️ **那是推算，不是实测**，不写进上面那行。等 R57 批次收官（还剩 H1-g 分栏）再带环境跑 `./scripts/build.sh` 两个口径各一次，届时换掉上面两行 |



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
| 连接向导 | ✅ | 两页 900×700：类型页 **一行 6 张彩色磁贴**（R43-b：只摆真有驱动的六家，各顶自绘厂商图形 + 品牌色；块居中，不留下半屏空白）+ 一行「其它数据库走兼容协议 / URI」的提示，属性页 7 页签 + `AIDB ⟷ Database` 示意图 + 底部五键。⭐ 原来照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，其中 16 张点进去是「本版不支持」—— 那不是它的形态（它摆这些家是因为真连得上去），我们摆出来只是让用户多点十六次空；复选栏、搜索框、滚动容器与「无驱动说明页」随死磁贴一起删，「其它怎么办」改成页面上明写的一行。SSL 页的档位与根证书参数**由方言自报**（PG `sslmode` / MySQL `sslMode` / MariaDB `sslMode`+`serverSslCert`，MariaDB 没有 prefer 档）；⭐ R47-a 在加密对端上跑完之后翻两处：① 「不加密」**真的写** `DISABLED` / `DISABLE` / `disable`（原先不写参数，而三家默认都是「能加密就加密」—— 见 D-33）；② MySQL 的「根证书」格在 VERIFY_CA 以上**禁用**并把说明写成 `trustCertificateKeyStoreUrl`（Connector/J 8.2 既没有 `sslCA` 也没有 `sslCapath`，未知属性被静默忽略 —— 收一个填了不生效的路径比不给这个格子更糟）；SQL Server（encrypt + trustServerCertificate 配对）与 Oracle（钱包 / sqlnet.ora）收起档位并写明去哪里填；⭐ **R55-c 把 SSH 页从「说明牌」换成真表单**：勾上隧道后跳板地址 / 端口 / 用户 / 认证方式（口令 ⟷ 私钥 + 口令短语，二选一，切档时另一档的值不跟过去）都能填、填了真的进档案 —— 秘密按类型分家（`SshConfig` 无秘密进 JSON、`SshSecrets` 只进凭据库，拼装口只有 `ConnectionProfile.spec` 一处），主机密钥走 OpenSSH 的 `known_hosts`（落点可用 `-Dadb.ssh.known_hosts` / `ADB_SSH_KNOWN_HOSTS` 重定向，测试与截图都不碰用户目录）；私钥那一格旁边明写「支持 RSA / ECDSA，内置库不支持 ed25519」（D-41，提示词也从 `id_ed25519` 换成 `id_rsa`）。**HTTP 页仍如实标注「未实现」，不是空壳** |
| 对象树 | ✅ | 真实内省（表 / 视图 / 函数·过程；触发器按表归属在结构页，事件本版本不上树）；多连接同树；表节点懒加载展开列，失败可重试；**搜索框的作用域是整棵树**（表 / 视图 / 函数·过程 / 已保存查询），所以提示词只写中性的「Search」，不再点名「表、视图」把自己说窄（R44-G2-a）；搜索框右侧另有**清除一键**，空框时连位置都不占；**右键菜单按对象类型分家**（表节点：清空 / 重命名 / 删除表；视图节点：设计视图 / 删除视图 —— 不再在视图上摆只能失败的表级动作）；**连接节点顶自己那家的厂商图形 + 品牌色**（与向导磁贴读同一登记行，所以两处的 MySQL 同形同色；认不出方言时回落中性插头，不挑一家画上去） |
| 厂商识别 | ✅ | 六家各**一枚自绘图形 + 自己的品牌色**（不是商标位图，见下「不打包」边界），铺满界面上十个说得出「是哪家」的表面：顶栏连接胶囊与它下拉的每一行、左树的连接节点与**库**节点（库保留圆柱形状，只有颜色跟父连接走）、折叠轨道的连接钮、`OBJECT` 页签、连接管理器 Type 列、状态栏「来源」（与活线点并排 —— 两件事两个线索）、查询页的库▾、向导磁贴与向导预览图。⭐ R54：十个表面全走 `ConnectionCatalog.graphic(kind,size)` **一个出口**，静态审计禁止任何表面自己写 `Icons.Icon.DB_*` 常量选图形（加第七家只在登记行加一行）。**认不出方言时不许挑一家画上去**：回落中性插头 / 圆柱 / 点，顶栏那一格更彻底 —— 没图形就整格收起 |
| 数据网格 | ✅ | 服务端分页（100/200/500/1000）、行首 16px 状态槽、类型行、列宽/顺序/显隐持久化、冻结列、矩形选择、⌘F 本页搜索、转置只读视图；工具条 13 具名键 + 视图/溢出两小键（放不下折进 `»`；R43-c：视图那颗 `≡▾` 不再钉 30px 宽，箭头不再压住图标）；底栏五键 + 页码框 + 齿轮（页大小/跳至记录）+ 放大镜 + 视图切换；**内嵌 Filter & Sort 生成器**（平铺 AND、Builder/Text 双模式、显式 Apply、Sort By 芯片） |
| Filter & Sort 面板 | ✅ | 网格内嵌（非弹窗），只改草稿不即输即查；`Criteria Edited` 标脏，`Apply Filter & Sort` 才生效。**边界**：底层 `DataQuery` 只有平铺 AND —— `and` 固定，`[Ω]`（OR 组）给如实说明页；Text 模式只读（自由 SQL 请去查询页）；`IN` 条件无法在行内表达，列头漏斗的按值筛选保留并原样带入。⭐ R55-a：面板里**不再有第二套「什么算空值」的规则** —— `requiresNonEmpty` 改成问方言（`!type.textual()`），列头漏斗的「框里只敲了空格」与「没填」走同一条判定（`EditValues.emptiedCell`，R53 为写回立的那唯一出处），并有一条源码审计钉「`emptiedCell` 的调用点只有一个」 |
| 数据页写路径 | ✅ | 行内编辑 + 类型转换 + 按主键定位；NULL / 空串独立操作；批量操作走行多选 + 右键菜单 + 底部行操作条（TSV / INSERT / 克隆 / 置 NULL / 置空串 / 删除）；SQL 预览 → 单事务 → 失败回滚 → 保存后一次反向撤销。⭐ **R53 起这条有真库证据**（此前九条契约量的全是「读」，这条用得最多的「写」只在 SQLite 替身上跑过）：契约第 10 条 `writeBackRoundTrip` 按界面的同一个顺序调同一个 `EditSqlBuilder`，在五台真服务器上量「中文不折问号 / `10.50` 不掉标度 / `true` 在 TINYINT(1)、BOOLEAN、BIT、`NUMBER(1)` 四档形状下都读得回成真 / 同行两格改动只发**一条** UPDATE 且 `WHERE` 只命中主键那一行 / 撤销打得回原值 / 删一行只少一行」；第 11 条钉「清空一格落成什么」—— 数字与布尔列五家全部落成**真 NULL**，文本列落成空串（Oracle 把 `''` 存成 NULL 是它自己的形状，如实分支钉）。⚠️ 如实记一条边界：Oracle 的 `NUMBER(1)` 在目录里归 `ColumnType.NUMBER` 而非 `BOOLEAN`（其余四家归 BOOLEAN），**两边都是事实** —— Oracle 没有可用的布尔建表档，契约按引擎各钉自己那一档，不假装五家一样。 |
| 结构编辑 | ✅ | 加列 / 改列 / 重命名 / 删列 + 两级危险确认；索引、外键、触发器、CHECK 可增删；表注释可读可改（SQLite 无 → 按钮禁用；⭐ 2026-11-02 真库契约补了「写进去再原样读回来」，同时修掉一条**表注释在 MySQL/MariaDB/PG 上从未生效**的缺陷 —— 一行没闭合的块注释把方言的读取钩子整段吃掉，接口默认返回 null 悄悄生效）；改列整表重建时搬运 CHECK；⭐ R46 D-23：**加列对话框的类型候选与默认值改成跟着方言走**（原先是一份硬编码的 SQLite 清单，套在 Oracle 上会摆出 `UUID`/`TEXT`/`REAL` 这些它没有的类型，SQL Server 的默认值还会落进 `VARCHAR` —— 实测那一家的 `VARCHAR` 走库默认单字节代码页，中文不报错但折成问号，所以默认改成 `NVARCHAR`）。类型清单与默认值都由方言自报（`Dialect#columnTypes` / `#defaultColumnType`），帧只拍得到合上时那个默认值（`p7-new-column`），候选清单跟着库变那一半由 `StructureColumnTypeTest` + 真库矩阵守 |
| 建表 / 表级生命周期 | ✅ | 建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，三种方言各走自己的语法。字段网格八列 + `Virtual` 生成列 / `Key` 单下拉 / `Key Length` 前缀索引 / `Binary` / 属性面板自增；R43-c：字段页三段（工具条 / 网格 / 属性面板）在内容区整体垂直居中，列少时留白回到上下两端，不再在网格与面板之间留一条死空白；方言不支持的属性直接拦下并说明原因，不编假 DDL。`Ask AI` 一击开**一份**如实说明页（本版本没有 AI 服务；R43-b 前是三项下拉，三项开的是同一段文字）。⚠️ 建表弹窗**还不能录 CHECK**（外键 / 索引 / 触发器页签从第三十一轮就在，共七个页签 —— 旧口径那句「不能录外键/索引」是陈旧的，2026-11-03 核过 `NewTableDialog.java` 的页签登记后翻掉）；建表后不自动打开该表 |
| 视图生命周期 | ✅ | 新建 / 设计 / 删除视图走**弹窗设计器**（视图名 + `SELECT` 查询体 + 实时 SQL 预览，与建表同一条「预览即执行」：预览几句就跑几句）。重建写法由方言自报，⭐ R52 在真库上逐个量过：MySQL / MariaDB / PostgreSQL / Oracle 一句 `CREATE OR REPLACE VIEW`，SQL Server `CREATE OR ALTER VIEW`，**只剩 SQLite 没有单句形式** → 两句「先删再建」，此时预览显出两句并**追加第二次确认**（原先把 MySQL 也算进「没有」那一侧，判反了 = D-29：两句之间视图真的不存在，还白要一道危险确认）。查询体只收 `SELECT` / `WITH` / 括号开头的组合查询：粘整条 DDL、塞第二句 SQL 都当场拦下并写明原因。「设计视图」先在后台读回库里定义，**读不到就不开窗**（不用空 body 盖掉真定义）—— SQL Server 上这条路原本是 `return null` 的空壳，等于那个库上设计器从来没开过窗（D-27，R52 修：真查 `sys.sql_modules JOIN sys.views`）。✅ **已在真库跑过**（R52 · 契约第 8 条）：五引擎各跑一遍「建 → 重建 → 按产品读路径读回 → 删后必须报错」，三种重建写法都被执行过一次。⭐ R47-b 补上**画面证据**（此前只有行为证据）：`docs/screenshots/p6-new-view-mysql.png`（`CREATE VIEW \`v_paid_orders\`` 的方言预览）、`p6-design-view-mysql.png`（真 MySQL 上读回 `v_active_users` 的定义、预览就一句 `CREATE OR REPLACE VIEW`、没有两句、没有多余的危险确认 —— D-29 的那一格）。⚠️ 如实记：MySQL 规范化后的读回是**一行长句**，720px 弹窗要看全得横向滚动 —— 那是 MySQL 自己的形态。⭐ **R47-d 把另外三家的形态也拍到了**（此前只有 MySQL 一帧，而「引号形状」恰恰只有画面能验）：`p6-new-view-sqlserver.png`（`CREATE VIEW [v_paid_orders]` —— 方括号只有这一家给得出，加上那句 `CREATE OR ALTER VIEW`）、`p6-design-view-pg.png`（`pg_get_viewdef` 回来的**多行缩进**定义，与 MySQL 那一行长句正相反）、`p6-design-view-sqlserver.png`（`sys.sql_modules` 读回的原文 —— 正文就是当初写的那一行 `SELECT … FROM dbo.users …`，预览是那句 `CREATE OR ALTER VIEW [v_active_users]`）、`p6-design-view-oracle.png`（`DBMS_METADATA.GET_DDL` 回来带**schema 前缀**的定义）。⚠️ 一起落地的是 **D-38（产品缺陷，不是画面问题）**：PG 与 SQL Server 的 `readDdl(..., view=true)` 原本走到方言那句 `return null` 的空壳上 —— 在这两家的真库里，「设计视图」**从来没开出过窗**（这两家的首拍拍到的是主窗口而不是弹窗，才顺出这条；第三张 Oracle 首拍落点不对另有原因 = D-39，见 §1 与平台一节）。R52 契约第 8 条当初把这条路**自己内联**了一遍再断言，绕过了产品实际走的 `readDdl`，所以 46 条契约全绿也没抓到它；本轮删掉那处内联、让它调产品自己的读路径，另加 `DialectViewReadDdlTest` 4 条离屏门禁（不发真库连接，用代理 `Connection` 记录 SQL：PG 必须问 `pg_get_viewdef`、SQL Server 必须问 `sys.sql_modules`+`sys.views`、两者都要能解析出查询体、`readDdl(...,false)` 仍返回 null、视图不存在返回 null 而不是空串）。⚠️ PG / Oracle 的「新建视图」格**拍过又撤了**：画面与演示库（SQLite）那一格逐字节相同（25490 —— 三家都用 `"..."` 引号，预览文本完全一致）。那不是假帧，是**冗余证据**；`shots.sh` 里没留那两行，`docs/screenshots/` 里也没留那两张 —— 一家一格的证据只在形状真的不同时才立（SQL Server 那格 25493 不同，所以留）。⚠️ 边界：本版本**没有视图重命名**（名字格置灰并说明替代路径）、没有视图依赖图（`dependentViews` 只用于删表前那句警告，SQL Server 上返回空集 = 那句警告在那个库不出现）；视图数据页仍只读。⚠️ D-28 待裁定：PostgreSQL 的 `CREATE OR REPLACE VIEW` 只许在末尾**加列**，改名/删列服务端报错（实测 `cannot change name of view column "id" to "adb_marker"`）—— 要不要在失败前给一句主动提示还没拍。 |
| 查询页 | ✅ | 工具带双行 62px（保存 / 查询构造器 / 美化 / 代码片段 / 问 AI · 查询名▾ / **库▾** / Run▾ / Stop / Explain）；⭐ R44-G1：行 2 那颗是**真库选择器** —— 列出当前连接能切的库（方言自报 `listCatalogs`，后台读、读不到就退回只展示），当前库打勾，选中即把这条 SQL 复制进**新页签并绑到该库**（走左树同一条 catalog 虚拟连接路径，会话没开先连再开）；**不自动重跑**（重跑有写风险），状态栏明写「已切到数据库 X · 查询已复制到新页（未执行）」。⭐ R52 之前这一条只有两条断字符串的单测（id = 父id@库名、database 字段被换掉），**没人在真库上问过「语句到底落在哪儿」** —— 现由契约第 9 条钉住：同主机同口令的两把库、同一个表名、字面完全相同的 `CREATE` / `SELECT`，两边各读各的行（没切过去会撞「表已存在」，切过去但隔离不干净则两边行数相同），且两条会话的「当前库」各自读对（SQL Server 的库名走 `databaseName` 属性、PG / MySQL 走 URL 路径段，写错的症状都是「连得上、落错库」）。SQLite 等无库层方言保持只展示、不假装可切；Oracle 那一支仍钉「没有库层 ≠ 连现在在哪儿都读不出」（实测 `SYS_CONTEXT('USERENV','DB_NAME')` 回 `FREEPDB1`）。⭐ R47-b：这颗控件**第一次有画面证据** —— `docs/screenshots/p3-query-catalog.png`（真 MySQL 上 `库▾` 展开，列出 `shop` / `shop2`、勾在当前库）与 `p3-query-catalog-bound.png`（切到第二把库后的页面态）；正是后一帧抓出 **D-36**（新页其实是空的，状态栏那句「SQL 已复制到新查询页」在骗人 —— 见 §1 基线行与 CHANGELOG）。RichTextFX 编辑器（语法配色、行号、当前行、查找替换）；⭐ **R57-a：编辑器有随库上下文的自动补全**（表 / 视图 / 列 / 例程 / 关键字）。**触发口径如实收窄**：`.` 之后自动弹、`⌘空格` / `Ctrl+空格` 强弹、弹开着打字即过滤，**普通打字不自动弹**（打一半弹候选会糊住正在写的词，而弹层抢焦点是更糟的交互）。规则与呈现分两层（R53 `EditValues.emptiedCell` 那条「唯一出处」纪律的延伸）：`SqlCompletions` 是纯逻辑、`QueryCompletions` 只管弹层与键盘，两类共 27 条门禁。上下文：`FROM`/`JOIN`/`INTO`/`UPDATE`/`TABLE` 之后是**表位**（只给表和视图）、`users.` 与别名 `u.` 之后是**列**（候选带所属表），其余位置给「本条语句引用过的表的列 + 对象 + 例程 + 关键字」（上限 500）。三处刻意的不做：字符串 / 注释 / 数字 / 参数里不弹；**认不出的限定符不猜、也不倒全部**（宁可不弹）；别名不跨分号串用。对象清单与列名走后台线程懒读（不在 FX 线程发查询），**成功执行一条 DDL 即整块作废重取**（刚建的表立刻能补）。关键字读 `SqlLexer.keywords()` 同一份表，不建第二份。⭐ **R57-b 选中执行**：取句只有一个出口 `QueryPage.statementsToRun()`，Run / Explain / 带变量执行共用它 ——
有选区按**字面**切句执行（圈两段出两个结果页签），**不往两边扩到语句边界**（圈半句就拿服务端的语法错误，
把半句悄悄补成整句才更难发现）；圈空白或纯注释报单独一句「选区里没有可执行的语句」，**不退回光标那条**。
危险分级、状态栏 SQL 回显、查询历史读同一份返回值，所以确认框问的就是真正执行的文本（变异：把分级改成读整页文本 →
「只选了 SELECT 却为没选中的 DROP 要确认」那条红）。⚠️ 补全弹层的**画面证据仍欠**（帧没拍），
选中执行与保存对话框选格式这两格同样**各欠一帧**，且 `./scripts/build.sh` 全量**未重跑** —— 基线数字仍是 R56 那一跑；`⌘R` **选中执行（R57-b：有选区只跑选区，没选区跑光标所在语句）**、`⇧⌘R` 全部、`⌘.` 取消；结果区 `Message/Summary/Result N` 三段 + `Data/Info` 切面、服务端分页、执行计划、错误定位、查询历史（50 条，带对象上下文）、代码片段。执行计划按方言能力开放：SQL Server / Oracle 需要两步协议，按钮禁用并说明原因 |
| 已保存查询 | ✅ | 工作区对象页（名称 / 类型 / 修改时间 / SQL 四列 + `设计` / `新建` / `删除查询`）；侧栏 `Queries` 组双击或 Enter 打开；删除走危险确认（默认按钮是取消）；查询页保存后列表即时刷新 |
| 导入导出 | ✅ | 流式 CSV / TSV / JSON Lines / SQL / xlsx（POI SXSSF 写 + SAX 读）/ XML / HTML；字段映射、按目标列类型转换（含中文日期格式）、坏行报告带列名与原文；查询结果**三档范围 × 六种格式**（本页 / 选中行 / 全部结果 × CSV · TSV · JSON · xlsx · XML · HTML）。⭐ **R57-d**：格式挂在系统保存对话框的扩展名档上（不在界面再搭一层下拉，同一个决定不给两个入口），文件名后缀跟着同一档换、缺后缀补上；三种范围统一交 **JDBC 原始值**（旧「本页/选中」交 `String.valueOf` 后的网格文本，换 JSON 会把 42 写成 "42"、NULL 写成 ""）；BOM 只给 CSV / TSV；写盘全在后台线程。⛔ 查询结果**没有 SQL(INSERT) 这一档**（无目标表名，硬写会产出不能执行的文件 —— 要 INSERT 用导出向导）。⭐ 顺手修一条真缺陷：结果网格原是 `SelectionMode.SINGLE`，「导出选中行」最多只能导 1 行。⚠️ 这一格**欠帧**（保存对话框那六档没画面证据），且导出的 HTML 标题是 `adb-transfer` 里硬编码的中文「查询结果」—— 见内部台账 D-44。⭐ **R55-b 起这条有真库证据**（契约第 12 条，五台 Docker 各跑一遍「整表导出 → 导回 → 逐行相等」）：此前只有四条内存 SQLite 门禁，证得了编解码与导入器的约定咬得上、证不了各家把 `rs.getObject` 交成什么 Java 类型、那个类型写进文件后再导回来吃不吃得下。⚠️ 为什么这条契约值得跑：导入器对坏行的策略是**跳过并记进报告**，真出问题时没有异常，症状是报告里少一行 —— 界面全绿、用户拿到的文件少数据 |
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
- 验收帧目前全部是 **macOS** 拍的（2026-10-30 起）。⭐ 其中 **8 张拍在本机 Docker 的真服务器上**，分属四家方言
  （`ADB_MATRIX` 那条通道：R47-b 的 `库▾` 展开态 / 切到第二把库后的页面态 / MySQL 侧新建与改视图的方言形态，
  R47-d 的 SQL Server 方括号新建视图 + PG / SQL Server / Oracle 三家「设计视图」读回形态）；
  ⭐ R54 另起**第三条通道** `ADB_TREE_VENDORS=1`（3 张：`p0-tree-vendors` 一棵树里五家并排、
  `p5-connections-vendors` 连接管理器六家同框，⭐ R55-d 补 `p0-tree-vendors-dark` 同一棵树的暗色档；
  另重拍 2 张）—— 它从 `ADB_TEST_<方言>_{HOST,PORT,DB,USER}`
  造**连接档案**，**不声明有活会话**：图形取自档案的 `kind`，容器没起时五行各带一颗灰点而图形仍然是真的，
  所以它不需要 D-37/D-39 那套落点门，反过来也**不能**被读成「五家同时连上了」。环境完全没配时
  这一路同样 `abortMatrixFrame` → `exit 3` 且不写文件，不会退回「只有演示库一行」拍一张名字写着五家的图。
  其余 48 张拍在 SQLite 演示库上，而 SQLite 没有「库」这一层 —— 所以「下拉里有两把库」这类
  **只有真服务器才有的形状**在 R47-b 之前一直没有画面证据（现已还，见 §2「查询页」与「视图生命周期」两行）。
  ⚠️ 这条画面账上仍挂着的：切库**落点**帧只有 MySQL 一家（Oracle 没有库层，不算缺帧）；
  SSH 那两张（`p5-connect-ssh` 口令分支 / `p5-connect-ssh-key` 私钥分支）拍的是**向导表单**——
  证的是「填了真的进档案、格式限制真的写在字段旁边」，**不是**「一条经隧道连上的会话长什么样」，
  后者的证据只有真库契约第 13 条，画面仍欠（要拍就得连一台真跳板机，不在本轮范围）。
  ✅ 已还的一条：⭐ R54 欠的**暗色档多引擎同框**（当时两张新帧都是亮色，暗档品牌色只有
  `ContrastTest` 的 ≥3:1 数值证据、没有画面证据）—— R55-d 补了 `p0-tree-vendors-dark`。
  ⚠️ 真库这条通道本轮被加了四道「不许假帧」的门（**D-37 / D-37b / D-39**，逐道都做过变异）：脚本侧方言 id
  白名单（写错直接 `exit 1`）、没配那一家口令就**跳过并说明**、外壳没落到那把档案或会话还没起来时
  **abort 且不写文件**（`System.exit(3)`）、脚本网格没产出文件就**非零退出**。口径是
  **「宁可没图，不可有假图」** —— D-39 实拍踩过一次：Oracle 首连要 8~12 秒，7 秒的等待里快门落在
  「Connecting to matrix Oracle…」上，拍出来是演示库的画面、文件名却写着 oracle。
- 弹窗按钮序的门禁原先只在 Windows 上绿：那条断言把**宿主平台的** `ButtonBar` 预设当成
  Windows 预设去比，而生产代码对不认识的预设故意不动（另一条门禁钉着这个「不动」）——
  于是 macOS 上必然假红。2026-10-30 改为**显式喂 Windows 预设**，跨平台一致；
  macOS 上按钮的真实位置由同类的坐标断言与实拍帧守。详见 [CHANGELOG.md](../CHANGELOG.md)。

### 能力缺口

- **Filter & Sort 面板只支持平铺 AND**：行间连接词固定 `and`，`[Ω]`（OR 条件组）本版本点开是如实说明页；
  `IN` 条件仍由列头漏斗的候选值多选提供，面板内原样保留、不可行内编辑；Text 模式是**只读**的生成表达式。
- 危险分级**第三层（驱动侧复查）不存在**：`DbSession.execute(sql)` 没有 `danger` 参数，
  绕过 UI 直接调会话层不会被拦。前两层（fail-closed 分类 + UI 确认）已落地。
- **SSH 隧道已交付**（⭐ R55-c，G5a）：纯 Java 引擎（Apache MINA `sshd-core` 2.20.1，选型与
  「为什么不调系统的 `ssh`」记在 `docs/COMPONENT-SELECTION.md`），连接向导 SSH 页是真字段，
  秘密只进凭据库；`SshTunnelTest` 9 条 + 真库契约第 13 条（五家各经进程内跳板机连一次）。
  ⚠️ 两条如实边界：**私钥只吃 RSA / ECDSA**（内置库对 ed25519 交出 0 把密钥，界面上明写，D-41）；
  **HTTP 隧道仍未实现**（Q5 建议不做，尚未拍板），那一页仍是一句实话而不是一个空壳。
  SSL/TLS 这一面 R47-a 已经踩过实土：档位取值、会话是否真加密、
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
- 真库契约现在是**十三条**。第 1–9 条在 `adb-jdbc` 的 `ExternalRealDatabaseTest`，展开成
  5 引擎 × 9 = 45 例，加一条「环境读取不外泄口令」= **46 条**；⭐ R53 新加的第 10 / 11 条
  「数据写回」量的是 `adb-editing` 的 `EditSqlBuilder`，所以落在**它自己那个模块**的
  `EditWriteBackMatrixTest`（5 引擎 × 2 = **10 例**，靠 adb-jdbc 的 test-jar 复用同一个
  `ExternalDatabase`，环境变量名不写第二遍）；⭐ R55-b 第 12 条「导出→导回」=
  `TransferRoundTripMatrixTest`（5 引擎 × 1 = **5 例**），⭐ R55-c 第 13 条「SSH 跳板」=
  `SshTunnelMatrixTest`（5 引擎 × 1 = **5 例**，进程内起一把真 sshd 当跳板机，量的是驱动那一侧
  在隧道对面连不连得上）。**加一条契约只加一行**。第 9 条另需第二把库：
  `scripts/dbmatrix.sh up` 在建完 `shop` 之后再建 `shop2` 并写进 `ADB_TEST_*_DB2`（有库层的四家各一把；Oracle 没有这一层，故**故意没有** DB2 键）。
  ⚠️ 第 12 条**住在 `adb-introspect` 而不是 `adb-transfer`**：reactor 里已有
  `adb-introspect(test) → adb-transfer(main)`，反向再加一条依赖就成环，Maven 直接拒绝构建；
  而这条契约要同时用内省（目标表列定义得从界面那条路拿）和导出导入引擎，只能放在看得见两边的这一侧。
  ⚠️ 契约跑完需要容器在跑；没配 `ADB_TEST_*` 时这些用例按引擎逐条跳过（不是整类），
  所以默认门禁里看到的跳过数是**缺了几个库**，不是「有没有测」。缺 `*_DB2` 时第 9 条同样
  **按引擎跳过并说明缺什么** —— 「只有一把库」验不了落点，假通过比跳过更糟。
  ⚠️ **契约与产品必须共用同一条路**，这一条 R52/R53 各踩过一面：R52 是契约**自己内联**了产品该做的兜底
  （走得更宽 → 46 条全绿也抓不到 D-38 那种空壳）；R53 是契约**猜**界面的形状
  （走得更窄 → 往 `DECIMAL` 列绑 `""`，端出五个界面根本不存在的「假缺陷」）。
  现在第 10 条按 `DataGridPage.saveChanges` 的同一个顺序调同三个方法，第 11 条直接调界面用的
  `EditValues.emptiedCell` —— 规则只有一份，界面单方面改掉时契约跟着红。
  ⚠️ **还有一条同形的坑是 R55-b 量出来的**：导入器对坏行的策略是「跳过并记进报告」，
  真出问题时症状不是异常而是**报告里少一行** —— 界面全绿、用户拿到的文件少数据。
  所以第 12 条钉的不是「没抛异常」，是**行数与逐格文本导回后逐行相等**。
  ⚠️ **测试侧也有一条「规则只许一份」的账（R56 · D-43）**：「服务器抽风 ≠ 产品缺陷」的判定此前在
  6 个测试类里有 7 份大小写敏感的文案副本 —— Oracle 容器刚重启时 `GET_DDL` 冷启动超读超时，
  抛的 `ORA-18730 …: Socket read timed out` 小写 `read` 对不上 `"Read timed out"`，环境抽风被端成
  红色缺陷。现已收进 `ExternalUnreachable`（先看 ORA 码 / SQLState `08*`，再看文案且大小写不敏感，
  `SshTunnelException` 永不降级）；`ExternalTlsDatabaseTest` **故意不并** —— TLS 负例必须先排除
  证书失败（D-32）再判抽风，语义与这里不同。
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
  ⚠️ **这一圈有一条隐藏前置条件**（R55 实测，D-42）：MySQL 8.4 的 `caching_sha2_password` 在服务端
  按账号缓存验证结果，**冷缓存 + 明文首登**会逼驱动去服务器取 RSA 公钥，而 Connector/J 默认
  `allowPublicKeyRetrieval=false` → 契约①「不加密真是明文」报 `Public Key Retrieval is not allowed`。
  焐热的方式是先用 TLS 登一次同一账号（MariaDB 的 native password、PG 的 scram 都没有这一层）。
  `dbmatrix.sh tls_up` 因此在就绪循环后多跑一次 `mysql_tls_warm_cache` —— **焐的是环境，不是断言**：
  没有这一步时这一条会红，而红的是真的环境依赖，不该靠改参数把它蒙过去。

## 4. 明确不做

自绘标题栏、AI 面板、插件系统、多用户权限、内嵌浏览器、本地 HTTP 服务。
⭐ 另有三面在 2026-11-01 裁定**不做**（P1）：**计划任务（Automation）**、**Model ER 全量编辑器**、
**数据生成（测试数据填充）** —— 三面的入口保留、点进去是如实说明页，记为「裁定不做」而不是待办。
⚠️ 口径分清：**只读 ER 图**仍在评估、未承诺；**数据脱敏**做不做**尚未拍板**（不在 P1 那三面里，
至今仍是一张如实说明页）。理由见 [README.md](../README.md) 与 [docs/ROADMAP.md](ROADMAP.md)。

## 5. 相关文档

- [docs/ROADMAP.md](ROADMAP.md) — 对外路线（支持矩阵 / 进行中 / 接下来 / 明确不做）
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — 模块、数据流、线程模型、编辑与 SQL 不变量
- [docs/UI-VISUAL-SPEC.md](UI-VISUAL-SPEC.md) — 可照着实现的 UI 规格
- [docs/UI-FORMS-AND-STATES.md](UI-FORMS-AND-STATES.md) — 弹窗 vs 标签页裁定、空白态清单
- [docs/COMPONENT-SELECTION.md](COMPONENT-SELECTION.md) — 组件选型与许可
- [CONTRIBUTING.md](../CONTRIBUTING.md) — 环境、命令、四条硬规则
