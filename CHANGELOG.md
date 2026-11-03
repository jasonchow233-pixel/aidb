# 更新日志

本文件记录 AIDB（AgenticDB）面向使用者的变更。

- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
- **当前状态：尚未发布任何正式版本。** 仓库版本是 `1.0.0-SNAPSHOT`，没有任何 git tag。
  首个 tag 会在 macOS 公证 / 签名与分发渠道确定后切（见 [docs/ROADMAP.md](docs/ROADMAP.md)）。
- **不回填历史。** tag 之前的 170+ 次提交请查 `git log --oneline`；本文件从「有意对外发布」那一刻开始维护。

## [Unreleased]

### Added

- **连接可以先经跳板机再落到数据库：SSH 隧道做成引擎层**（2026-11-03，R55-c · G5a）：
  这一项在路线图上挂了很久，之前连接向导里那句「本版没有内置 SSH 隧道引擎」配一段
  `ssh -L` 的手工替代 —— 那是把界面功能推给用户的手工操作。选型 Apache MINA `sshd-core`
  2.20.1（Apache-2.0；评估与「为什么不 shell 出系统的 `ssh`」记在 `docs/COMPONENT-SELECTION.md`）：
  纯 Java，Windows 上那份 `ssh.exe` 不保证有，「连不上」不该取决于用户装了什么；同一个 jar
  自带服务端，所以契约能在本机起一把真 sshd 量「字节确实过了隧道」。三条红线是结构性的：
  **秘密按类型分家** —— `SshConfig`（无秘密，进档案 JSON）/ `SshSecrets`（口令与口令短语，
  只进系统凭据库）/ `SshEndpoint`（驱动层，`toString` 打码），拼装口只有 `ConnectionProfile.spec`
  一处，并有源码审计钉「界面里 `.spec(` 只许出现在 `specOf` 一处且必须同时递两份秘密」；
  **失败分类不猜文本** —— 隧道起不来与主机密钥变更各有独立一档（驱动原文里三种失败都写着
  `communications link failure`，D-32 同形不再复现）；**验收不写用户目录** —— `known_hosts`
  落点可重定向，测试与帧都指向临时目录。`JdbcSession.open` 先起隧道、再把 host/port 换成
  `127.0.0.1:临时端口`，连库失败即关掉隧道（不留一个还挂着 SSH 会话与本机端口的「失败连接」）。
  界面侧：向导 SSH 页从「说明牌」换成真表单（跳板地址 / 端口 / 用户 / 口令 ⟷ 私钥二选一 /
  主机密钥策略；开关没勾时字段灰掉但**保留已填的值**；SQLite 那一档不摆隧道字段并写明为什么：
  本机文件没有「另一台机器」可跳）。**HTTP 页仍如实标注未实现。**
  门禁：`SshTunnelTest` 8 条 + `ConnectionStoreTest` +5 + 向导 +7（并删掉那条钉「这一页没有字段」
  的旧断言 —— 它钉的是已经被撤掉的谎）+ 真库契约**第 13 条** `SshTunnelMatrixTest`
  （5 引擎 × 1 = 5 例，五台 Docker 各经进程内跳板机连一次，量的是驱动那一侧在隧道对面连不连得上）。
  画面：`p5-connect-ssh.png`（口令分支）与 `p5-connect-ssh-key.png`（私钥分支）—— ⚠️ 这两张证的是
  **向导表单**（填了真的进档案、格式限制真的写在字段旁），**不是**「一条经隧道连上的会话长什么样」；
  后者的证据只有契约第 13 条，会话画面仍欠（要拍得连一台真跳板机）。

- **真库契约第 12 条：整表导出 → 导回 → 逐行相等，五台 Docker 各跑一遍**（2026-11-03，R55-b）：
  导出/导入的往返此前只有四条内存 SQLite 门禁 —— 它证明编解码与导入器的约定咬得上，证明不了
  各家把 `rs.getObject` 交成什么 Java 类型、那个类型写进文件后再导回来吃不吃得下。更贵的是
  失败形状：导入器对坏行的策略是**跳过并记进报告**，真出问题时症状不是异常而是**报告里少一行** ——
  界面全绿、用户拿到的文件少数据。所以这条契约钉的不是「没抛异常」，是行数与逐格文本导回后
  逐行相等（NULL 经 CSV 落成空串、Oracle 是它自己的 NULL，两边都是量出来的事实）。
  ⚠️ 这条契约住在 `adb-introspect` 而不是 `adb-transfer`：reactor 里已有
  `adb-introspect(test) → adb-transfer(main)`，反向再加一条依赖就成环 —— Maven 直接拒绝构建。
  三条变异各自验证过（导出忽略列选择 / 字段不加密 / 导入静默丢一行，各自五家全红）；
  第一次跑变异时因为没先 `install` 变异后的模块而**假绿**过一次，纪律补进台账。

- **每家数据库顶自己那枚图形，十个表面一次铺齐**（2026-11-03，R54 · 用户点名「每个类型数据库
  都改为用他们自己特定的 icon，一眼看出是 mysql、pg 等」）：先量现状再动手，量出来的结论是
  **这一轮不需要画任何图标** —— 六枚自绘字形（`Icons.Icon.DB_*`）、彩色分层资产
  （`icons/color/db_*.svg`）、亮暗两档品牌色（`Tokens.VENDOR_*`）、CSS 作用域类
  （`.adb-db--<id>`）和「六家两两不同形」的门禁（`VendorGlyphTest`）在 R43-a 就全建好了，
  缺的是**它们只铺在两个表面上**：向导磁贴、左树的连接节点。其余每一个说得出「是哪家」的地方
  —— 顶栏那颗常驻胶囊、它的下拉、左树的**库**节点、页签、连接管理器的 Type 列、状态栏、
  查询页的库▾、折叠侧栏的连接钮、向导右边那张预览图 —— 全是通用圆柱 / 通用插头 + 一行字。
  修的方式不是挨个表面各画一遍（那是 D-38 的形状：同一条规则十份实现），而是把
  「取字形 → 上彩色分层 → 挂品牌作用域类」收成**一个出处** `ConnectionCatalog.graphic(kind, size)`，
  十个表面全调它；再加一条静态审计 `onlyTheRegistrationRowPicksVendorGlyphs`：
  整个 `ui/` 的源码里除了登记行本身，出现 `Icons.Icon.DB_MYSQL` 这类**常量分支选图形**就红
  —— 将来加第七家，只需要在登记行加一行。
  三条口径本轮没有松动：**认不出方言就交回调用方原本的兜底图形**（中性点 / 插头 / 圆柱），
  画成某一家就是编造；**继续不打包任何厂商商标图形**（可辨识度靠「形状互不相同 + 各自品牌色」
  两条腿，竞品的位图进产品就是他人版权素材）；状态栏那格**保留**活线点、图形另起一槽 ——
  「连着没连着」和「连的是哪家」是两件事，不该挤进同一个线索。
  画面：新增 `p0-tree-vendors.png`（**55 张既有帧里没有一张拍到左树**，这是第一张多引擎同框的树）
  与 `p5-connections-vendors.png`（六家同框的连接管理器），重拍 `p5-connections.png` 与
  `p5-connect-general.png`。这一路走新的 `ADB_TREE_VENDORS=1` 通道：只从
  `ADB_TEST_<方言>_{HOST,PORT,DB,USER}` 造**连接档案**（口令仍然零入库、不进凭据存储），
  环境变量完全不在时直接 `FRAME ABORTED` → 退出码 3、一个字节都不写。
  ⚠️ 这一路的帧**故意不声明有活会话** —— 图形来自档案登记的 kind，不来自 session，
  所以它不需要 D-37/D-39 那套「必须真落到那台服务器」的落库门；反过来它也**不能**被当成
  「五家同时连上了」的证据，那张画面说的是「树认得这六家」。
  **门禁**：本轮 +13 条（十个表面各钉一条「图形上的作用域类就是那一家」+ 上面那条源码审计 +
  无连接时那一格必须整格收起）。变异台账见 `docs/internal/GAP-TODOLIST.md` 的 R54 收官记录。


- **格子改完落库这条路第一次打到真服务器上**（2026-11-03，R53）：真库矩阵此前九条契约量的全是
  **读**（目录、DDL、视图、切库）；界面用得最多的那条 **写** —— 双击一格 → 改 → 保存 →
  按主键打回去 —— 只在内存替身（SQLite）上跑过。生成的 SQL 形状钉得住，
  「那条 SQL 落到真服务器上变成什么」从来没量过。补两条契约，摊成
  **5 引擎 × 2 契约 = 10 例**（`EditWriteBackMatrixTest`，凭据仍只从 `ADB_TEST_*` 环境变量读、零入库）：
  - `writeBackRoundTrip`（第 10 条）按 `DataGridPage.saveChanges` 的**同一个顺序**走
    `plan → inversePlan → execute`（不另写平行路径 —— 那是 D-38 的形状）：两行新增落库后读回，
    中文不变问号、`10.50` 不被折成 `10.5` 或 `11`、`true` 在 TINYINT(1)/BOOLEAN/BIT/NUMBER(1)
    四种形状下都读得回成真、交 `null` 的那格仍是 NULL 而不是空串；同行两格改动翻成**一条** UPDATE
    且 `WHERE` 只命中主键那一行（**没动的两列不能被这次保存改写**）；撤销打回原值；
    删一行只少一行。
  - `emptyCellShapeIsWhatTheServerSays`（第 11 条）钉「双击一格、把内容删干净、点保存」在五家落成的形状：
    数字与布尔列**全部落成真 NULL**，文本列落成空串（Oracle 把 `''` 存成 NULL 是它自己的形状）。
  ⚠️ **这一条第一次端出来的是五个假缺陷**，值得记：契约照「界面交空串」这个**猜测**往
  `DECIMAL` / 布尔列里绑 `""`，结果 MySQL / MariaDB / PG / Oracle 四家服务端整批拒
  （`Incorrect decimal value: ''`、`column "amount" is of type numeric but expression is of type
  character varying`），SQL Server 的 `BIT` 更糟 —— `''` **静默折成 0**，不报错。
  看着像四个缺陷加一个数据损坏，实际上**产品根本不交这个形状**：界面那个私有分支把非文本档折成
  `null`，另有「设为 NULL / 设为空串」两个各带门禁的动作（D-33）。规则是猜的，量出来的东西界面上就不存在。
  修法是把规则搬出界面做成唯一出处：`ColumnType.textual()`（哪一档吃得下空串）+
  `EditValues.emptiedCell(ColumnDef)`（清空一格绑什么），`DataGridPage.parseCellValue` 改为调它，
  契约调**同一个方法** —— 界面或模型单方面改掉，契约与单测一起红。落库结果从「`null`/0/`0.00`/报错
  四档任挑」的假门禁换成硬钉。
  门禁：新增 `EditValuesTest`（3，含「非文本档逐档点名」清单 —— 模型新增一档漏出清单就报红）、
  `CellValueParserTest` +1（量的是界面**接线**，不是规则本身），`adb-editing` 通过 test-jar 复用
  `ExternalDatabase`（环境变量名不再写第二遍）。
  **五条变异验证**：① `emptiedCell` 永远交空串 → 7 红（契约五家 + `EditValuesTest` + `CellValueParserTest`）；
  ② `textual()` 把 NUMBER 也算成文本档 → 6 红（档位清单钉 + 契约五家）；
  ③ `parseCellValue` 退回「空格一律交空串」 → 2 红（新加的界面接线门 + D-33 那颗
  「NULL 和空串在网格里是两件事」的老门）；④⑤ `EditSqlBuilder.plan` 的 SET 子句扩到全部列 →
  `EditSqlBuilderTest` 4 红（含 D-10「UPDATE 只 SET 改动的格子」）；同一改动在真库矩阵上是
  **10 例全红**（五家 × 两条契约）—— SET 里带上了主键，五家一律拒绝 `SET id = NULL`，
  报的是服务端原话（PG 的 not-null violation、Oracle 的 ORA-01407）。
- **视图形态帧补上另外三家方言，种子铺满五家**（2026-11-03，R47-d）：R47-b 打通真库通道之后，
  画面证据仍然只有 MySQL 一家。这一轮把 `dbmatrix.sh seed` 扩到**五家**（MySQL / MariaDB / PostgreSQL /
  SQL Server / Oracle 各一份随方言变的幂等种子：`INSERT IGNORE` / `ON CONFLICT DO NOTHING` /
  `IF NOT EXISTS` + `MERGE`；SQL Server 的 `CREATE OR ALTER VIEW` 必须**独占一个批次**，
  和前面的建表拼成一段会在解析期整批失败；Oracle 容器里 `sqlplus` 默认连的是 CDB$ROOT，
  得先 `ALTER SESSION SET CONTAINER`），并新增四张帧（`docs/screenshots/` 51 → 55）：
  `p6-new-view-sqlserver`（`CREATE VIEW [v_paid_orders]` —— 方括号只有 SQL Server 给得出）、
  `p6-design-view-pg`（读回 `pg_get_viewdef` 的**多行**正文 + 单句 `CREATE OR REPLACE VIEW "v_active_users"`）、
  `p6-design-view-sqlserver`（`CREATE OR ALTER VIEW [v_active_users]`，正文是当初写的原句一行）、
  `p6-design-view-oracle`（`CREATE OR REPLACE VIEW "v_active_users"`，正文带 `adb.users` 的 schema 前缀）。
  ⚠️ 同两格在 PG / Oracle 上的「新建视图」预览**没有**进仓库：实拍回来与演示库那张**逐字节相同**
  （这三家都用双引号包标识符，形状差异为 0）。同一张画面存三份不是证据变多，是把下一次的真重复
  埋进噪音里 —— 帧去重门（同画面即假帧）报出来的正是这一类，这次判它是冗余、不是假。
- **验收帧第一次拍在真数据库服务器上**（2026-11-02，R47-b）：截图通道此前只能绑演示库（SQLite），
  而 SQLite 没有「库」这一层 —— 于是「`库▾` 里有两把库」「MySQL 上改视图只有一句」这类
  **只有真服务器才有形状**的画面一张都没有（47 帧全部拍在演示库上）。
  新增 `ADB_MATRIX=<方言 id>`：外壳按矩阵环境变量（`ADB_TEST_{HOST,PORT,DB,USER,PASSWORD}`）在**进程内**
  注册一把临时连接，口令只活在这个进程的字段里、**绝不进凭据存储**（macOS 那一个是钥匙串）；
  `ADB_CONNECTIONS_FILE` 把连接档案指到临时文件 —— 此前「拍一次验收图」会顺手往用户真实的
  `~/.agenticdb/connections.json` 里写一条。没配矩阵环境时那几格**跳过并说明原因**，
  不让它退回演示库拍出一张「看着像真库」的假帧。
  四张新帧（`docs/screenshots/` 47 → 51）：`p3-query-catalog`（真 MySQL 上 `库▾` 展开，列出
  `shop` / `shop2`、勾在当前库）、`p3-query-catalog-bound`（点进第二把库之后的页面态）、
  `p6-new-view-mysql`（方言驱动的 `CREATE VIEW` 预览，带反引号）、`p6-design-view-mysql`
  （**D-29 的第一张画面证据**：MySQL 上重建视图就一句 `CREATE OR REPLACE VIEW`）。
  门禁：`ScreenshotScriptAuditTest` +2 —— 摆态名必须在 `AppShell` 的 pose 分派里有分支
  （打错一个字母照样拍出默认页、返回码还是 0），矩阵帧必须被门控且脚本里不许出现
  `ADB_TEST_*_PASSWORD=<字面量>`。
  配套：`scripts/dbmatrix.sh seed` 幂等给矩阵的 `shop` 铺 `users` / `orders` / `v_active_users`
  —— 「设计视图」那帧的前提是库里真有一条定义，而**预览里写着的表必须真在库里**。
- **视图可以建、能改、能删了**（2026-11-01，R42 N28-a）：新增**视图设计器**弹窗 —— 视图名 +
  `SELECT` 查询体 + 实时 SQL 预览，和「新建表」共用同一条铁律：**预览里看到几句，库里就跑几句**。
  入口三条：功能区 `View` 格、库 / 连接 / 分组节点右键「新建视图」、视图节点右键「设计视图」「删除视图」。
  重建写法按方言给，不由界面猜：MySQL / MariaDB / PostgreSQL / Oracle 一句 `CREATE OR REPLACE VIEW`，
  SQL Server `CREATE OR ALTER VIEW`，**只有 SQLite 没有单句形式**（R42 当时把 MySQL 也算进「没有」的那一侧，
  R52 在真库上翻案，见下方 Fixed）—— 两句「先删再建」时预览显出两句、并多要一次确认
  （第二句之前那个视图已经不在了，依赖它的对象会失败）。
  查询体只收 `SELECT` / `WITH` / 以括号开头的组合查询：粘整条 DDL、塞第二句 SQL 都当场拦下并写明原因，
  不会拼出半条能执行的东西。「设计视图」不改名字 —— 那一格置灰并说明替代路径（本版本没有视图重命名）。
  ~~⚠️ 视图 DDL 现在由三方言 SQL 快照 + SQLite 契约钉住，**还没在真 MySQL / PostgreSQL 上联调**。~~
  （2026-11-02 已联调：真库契约第 8 条在五引擎上跑完建 → 改 → 读回 → 删，见 Fixed。）
  门禁：新增 `ViewLifecycleSqlTest`（11）、`ViewDefinitionTest`（8）、`ViewDesignerDialogTest`（16，含
  「预览文本 == 将要执行的语句」、形态扫描、三格标题、弹窗内错误/提示行配色），`TableDdlTest` +4、`SideBarTreeTest` +2。
- **表设计器补齐 Navicat 17 的列属性**（2026-11-01，R41 N29）：
  字段网格改为 `Name / Type / Length / Decimals / Not Null / Virtual / Key / Comment` 八列。
  新增 **`Virtual` 生成列**（表达式在属性面板录入；MySQL / SQLite / Oracle 写 `VIRTUAL`、
  PostgreSQL 写 `STORED`，SQL Server 如实不支持 —— 填了表达式也建不出来时会拦下并说明是哪一列，
  绝不悄悄降级成一列永远为空的普通列）。
  `Key` 合并回**单一下拉**（none / primary / unique），自增移入属性面板且只对整型开放，
  多列主键按网格勾选顺序编号。属性面板新增 **`Key Length`（前缀索引）** 与 **`Binary`**；
  键长把主键 / 唯一键写成表级 key part（`PRIMARY KEY (email(20))`），不支持前缀索引的方言直接拦下。
  工具栏 `Ask AI ▾` 的三项都是**真按钮**，点开是如实说明页（本版本没有 AI 服务，指向 ⌘K 与手工路径）。
  计数文案统一为 `Number of Fields`。门禁：`NewTableDialogTest` 新增 9 条（DDL 断言覆盖键序 /
  自增 / 键长 / BINARY / 生成列 / 方言拒绝 / Ask AI 说明页）。
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

- **筛选器的「没填」接进 R53 那条唯一出处，界面不再有第二套空值规则**（2026-11-03，R55-a）：
  R53 把「清空一格绑什么」收成 `EditValues.emptiedCell` 一处，但界面里还留着两条自己判断空值的
  分支 —— `CellValueParser` 对空输入返回 `""`（数字档因此能把 `""` 递给 `DECIMAL`/`BIT`，
  正是 R53 在真库里量出来的两种失败形状），`FilterSortPanel.requiresNonEmpty` 手写了一份
  「哪几档吃空串」的名单，和 `ColumnType.textual()` 是同一件事的两份真相。现在
  `requiresNonEmpty` 改为问方言（`!type.textual()`），「框里只敲了空格」与「没填」走同一条判定；
  `ColumnFilterPanel.exactCondition` 抽成可测的静态方法（此前「只输空格」一条门禁都没有）。
  门禁：`CellValueParserTest` +4，含一条源码审计钉「`emptiedCell` 的调用点只有一个」。

- **TLS 页的说明改成驱动真能收的东西，并起了第二圈加密对端来验它**（2026-11-02，R47-a · D-31）：
  `scripts/dbmatrix.sh tls-up` 另起 MySQL 33071 / MariaDB 33072 / PostgreSQL 54331 三个**加密服务**
  （一把自签 CA、再加一把从没签过服务器证书的冒名 CA；私钥只落在 gitignore 的 `scripts/.dbmatrix-tls/`，
  口令仍只进 `scripts/.dbmatrix.env`）。3 引擎 × 6 条契约 = 18 例：不加密真是明文、`require` 真加密、
  给签发方链校验真通过、换冒名 CA 真被拒、`localhost`（证书里写着）连得上、`127.0.0.1`（没写）真被挡。
  MySQL 那一档随之改口径：**Connector/J 8.2 没有 CA 文件参数**（既无 `sslCA` 也无 `sslCapath`，
  未知属性被静默忽略），所以「根证书」那一格在 VERIFY_CA 以上**禁用**，说明改写成把 CA 导入 JVM 信任库 /
  在附加参数里写 `trustCertificateKeyStoreUrl`（PKCS12）—— 考虑过干脆砍掉 MySQL 的 VERIFY_CA 档，
  否掉了：公开 CA 签发的证书在 `PREFERRED/REQUIRED` 之外确实要这条链，而且砍档会让**已存过 VERIFY_CA 的连接
  静默降级**。中英两份文案各钉了一条门禁，撤掉任何一句都会红。

- **三处能力面正式裁定不做**（2026-11-01，R42 拍板 P1）：**Automation 作业调度、Model ER 全量编辑器、
  Data Generation 造数**。功能区磁贴与数据页工具条上的入口**都保留**，点进去是如实说明页
  （写明本版本没有这个能力面、没有改动任何数据），不是死按钮，也不是填了也没用的假表单。
  `Data Masking` 不在这轮裁定的三面之内 —— 它现在同样是说明页，做不做还没拍板。
- **视图节点的右键菜单只留做得到的动作**（2026-11-01，R42 N28-a）：以前表与视图共用一份菜单，
  于是视图上挂着「清空表 / 重命名 / 删除表」——点了只会失败的三项。现在视图节点给
  「设计视图 / 删除视图」，表节点给「清空 / 重命名 / 删除表」，各自只剩能兑现的选项。
  对象清单页那条「本版本没有编辑器」的说明也一并改准：函数仍然只有浏览，**视图已经有自己的设计器**。
- **字号阶梯由 7 档收敛为 11 / 12 / 13 三档**（2026-11-01，R41 N30）：
  删除 `fs-2xs`(10) / `fs-lg`(14) / `fs-xl`(16) / `fs-3xl`(32) 四档令牌。
  10px 角标贴着可读下限；14 / 16px 与 12 / 13px 只差 1–2px，同一屏却多出三四层「层级」。
  现在层次由 **bold + 颜色** 拉开：排序角标 / 徽标 / 键帽落到 11px，页面 / 对象 / 空态 /
  对话框标题统一 13px bold，空状态图标的 32px 改由 `Icons.SIZE_EMPTY` 在 Java 侧给定。
  令牌总数 108 → **104**；`AppCssAuditTest` 允许字号集合与「11px 下限」同步收紧
  （`noFontSizeBelowElevenPixels`），`TokenParityTest` 校验每个令牌仍有消费者。
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

- **私钥那一格不再邀请用户填引擎读不出的 ed25519**（2026-11-03，R55 · D-41）：SSH 页做成真字段的
  同一轮，提示词写的是 `id_ed25519`，而 MINA sshd 解析 OpenSSH 格式的 ed25519 私钥需要
  `net.i2p.crypto:eddsa` 这个本工程取不到的依赖。实测后果不是报错而是**沉默**：
  `FileKeyPairProvider.loadKeys` 对 ed25519 私钥安静地返回 0 把，用户只拿到一句
  `holds no usable key pair`，会去怀疑文件权限而不是密钥类型。修法不是偷偷换算法、也不是留个
  看着能用其实必败的提示词，而是三处一起点名：① 提示词改成 `id_rsa`，字段旁新增说明
  「支持 RSA / ECDSA（OpenSSH 或 PEM），不支持 ed25519」；② 引擎那句 `loaded == 0` 的报错
  自己把支持格式说完；③ 两条门禁 —— `SshTunnelTest` 用系统 `ssh-keygen` 现场造一把真 ed25519
  私钥钉住报错（没有 `ssh-keygen` 的机器算跳过，不算验过），`ConnectionWizardTest` 双语钉提示词
  与格式说明。画面：私钥分支单独补一帧 `p5-connect-ssh-key.png`（拿「口令分支」那张帧当 D-41 的
  证据，等于帧说着 A、结论写着 B），并补两条摆态门禁钉「帧名与帧内容对上」——
  `sshkey` 必须有 key-path 与格式说明、不许有口令框，`ssh` 必须留在口令分支。

- **TLS 契约①的隐藏前置条件摆到台面上**（2026-11-03，R55 · D-42）：「不加密 → 会话真是明文」
  从 R47-a 起一直是绿的，但它绿着靠的是 MySQL 8.4 `caching_sha2_password` 的服务端验证缓存被
  上一次 TLS 登录焐热过：容器一重启（连 Docker Desktop 自己重启都算）缓存空，明文首登要向服务器
  取 RSA 公钥，而 Connector/J 默认 `allowPublicKeyRetrieval=false` →
  `Public Key Retrieval is not allowed`，契约根本连不上、也就没量到它要量的东西。
  本轮实测两次：`docker restart` 后必红，焐一次 18/18 绿。**改的是环境，不是断言** ——
  `dbmatrix.sh` 的 `tls-up` / `tls-run` 现在从容器内做一次 TCP+TLS 登录焐热缓存，契约里补注释
  说明这条真实失败模式（用户第一次连 MySQL 8 又选「不加密」，撞的就是这句）。
  MariaDB（native password）与 PG（scram）没有这一层，不受影响。

- **本轮自己引入、被实拍帧当场抓走的两个**（2026-11-03，R54）：记在这里是为了说清一件事 ——
  这两个都不是「用户曾经见过的缺陷」，而是「代码写完、测试全绿、画面上却是错的」，
  只有帧能看见它们（这也是 R54 坚持出帧的理由：「一眼看出」本身就是画面命题）。
  - **常驻的「Objects」页签仍顶通用圆柱**：第一版 `TabBar.vendorGraphic` 在
    `tab.connectionId() == null` 处短路，而那个首页页签的 id 恰恰是 `null`（它不属于某个连接，
    属于「当前连接」）—— 于是十个表面里最常驻的那一个反而没铺上。修：把 null **交给注入的函数**，
    由外壳回落到当前活动连接；顺带发现换页签节点时会把 `adb-tab__icon` 丢掉（类挂在旧节点上），
    重绘时必须重新挂回去。
  - **连接管理器 Type 列把 `POSTGRESQL` 折成 `POSTGRE…`**：那一列的 90px 是按**纯文字**量的，
    加了图形 + 5px 间距等于凭空吃掉一截（中途试到 112px 仍然折字）。修到 140px（按最长的那个名字 PostgreSQL 留）。
    ⚠️ 这条的通用账是：**给一列加图形，就得重新量那一列** —— 单元测试看的是模型里的字符串，
    它不会告诉你屏幕上少了三个字母。


- **PG / SQL Server 上「设计视图」一次都打不开，整表重建还会把依赖视图删掉不重建**
  （2026-11-03，R47-d · D-38）：这两家的 `readDdl(..., view=true)` 整方法 `return null`
  （没有 `SHOW CREATE TABLE`，视图原文其实存在 `pg_get_viewdef` / `sys.sql_modules` 里），
  而调用方有**两处只调 `readDdl`**：视图设计器开窗前的读回（读不到就不开窗 —— 这个行为是对的，
  于是那个入口在这两家永远不开窗），以及整表重建的「先拆依赖视图、建完再挂回去」清单
  （读到 null 就当没有依赖视图 —— `DROP TABLE` 把视图级联带走之后**没人重建它**）。
  真库契约当时是**绿的**：它自己写了「readDdl 空了就再问一次 `readViewDdl`」，
  于是契约走的路和界面走的路不是同一条 —— 兜底写在调用方头上的年代正是这条 bug 的来处。
  修：兜底收进方言（`readDdl(view=true)` 自己交回库里存的原文），契约改成直接钉 `readDdl` 这一条，
  `SchemaService` 里那份重复兜底删掉；补一条不依赖 Docker 的门禁 `DialectViewReadDdlTest`（4 条：
  替身连接断言「真的发了那条目录查询」「读回来还解析得出查询体」「表那条路仍然交 null」）。
  发现它的正是这两家的帧 —— `p6-design-view-pg` / `p6-design-view-sqlserver` 首拍拍到的是主窗口 +
  状态栏「读不出这个视图的查询体」。
  **变异**：两家方言退回 `return null` → 不依赖 Docker 的静态门禁红 **2**，真库契约在 PG 与 SQL Server
  上**各红 1**（正是这两家，报的正是界面那句「设计视图开窗要读得到查询体」）—— 同一条缺陷现在由单元、
  契约、画面三层各自守得住。
- **真库帧会在「还没连上」的那一刻按快门，而且前提没成立也照样出图**
  （2026-11-03，R47-c · D-37 / D-37b / D-39）：R47-b 那条通道有三道只写了一半的门 ——
  ① `shots.sh` 对**所有**矩阵帧只查 `ADB_TEST_MYSQL_PASSWORD`，也不校验 `ADB_MATRIX` 的取值，
  于是 `ADB_MATRIX=postgres`（拼错）跳过门、退回演示库拍完、返回码还是 0；
  ② 帧没生成时脚本打印「✓ 已产出 0 张」然后 exit 0 —— abort 的正确行为看起来和成功一模一样；
  ③ 连接是异步的：声明成立但快门时刻还没连上，拍回来的是演示库的画面、文件名却写着 oracle
  （`p6-new-view-oracle` 实拍踩过一次，Oracle 首次连接实测 8~12 秒）。
  现在：口令**按引擎**查（前缀由方言 id 映射，`postgresql→PG` / `sqlserver→MSSQL`）、
  `ADB_MATRIX` 的取值必须是 `DialectKind` 的方言 id、`ADB_CONNECTION` 必须等于 `matrix-<那个 id>`；
  外壳在快门那一刻再问一次落点（`AppShell.matrixFrameProblem`，认 `matrix-mysql@shop2` 这种库变体），
  前提不成立就 `abortMatrixFrame` —— 不写文件、退出码 3；脚本缺帧就报红并非零退出。
  门禁：`ScreenshotScriptAuditTest` +1（矩阵帧的 `ADB_MATRIX` / `ADB_CONNECTION` 成对钉死）、
  `AppShellTest` +1（D-39 的落点判定 —— 抽成静态方法，才能在没有真服务器的单测里钉它）。
  四层各自做过变异验证：拼错 id → 脚本 exit 1；连接串打错一个字母 → 应用 exit 3 且不落盘；
  静态门禁里写 `ADB_CONNECTION=matrix-mysq` → 红 1；删掉「会话没开就不拍」那一段 → 新门禁红 1。
- **查询页换库后新页是空的，状态栏却说「SQL 已复制到新查询页」**（2026-11-02，R47-b · D-36）：
  外壳取「当前页的 SQL」走的是 `TabBar.contentOf(tabId)` —— 那里存的是标签条上的**胶囊**
  （图标 + 标题 + 脏标记的 `HBox`），不是页面本体，于是 `instanceof QueryPage` 永远不成立、
  交出的永远是空串。从 R44-G1 交付那天起就是这样：换库开出的新查询页**一条 SQL 都没有**，
  而状态栏那句「SQL copied to a new query tab (not run)」是假的。
  行为门禁看不见它 —— `QueryCatalogSelectorTest` 把 `onSelectCatalog` 桩掉了，从不走外壳的回读；
  是这一轮把帧拍到真 MySQL 上才看见的（第一版 `p3-query-catalog-bound` 的编辑器全空）。
  现在从 `WorkspaceView.queryPage(tabId)` 取真正托管那一页，并补一条走**真菜单条目 onAction 链**的
  外壳级门禁（`AppShellTest.switchingCatalogCarriesTheSqlIntoTheNewQueryTab`）。
- **选了「不加密」，连接其实还是加密的**（2026-11-02，R47-a · D-33）：三家的 `OFF` 档位原来**一个参数都不写**，
  而驱动的默认全是「能加密就加密」—— Connector/J 8.2 的 `sslMode` 默认 `PREFERRED`、pgjdbc 的 `sslmode`
  默认 `prefer`、MariaDB 驱动默认 `DISABLE`（这一家碰巧对）。也就是说那面下拉框里最保守的一档，
  在 MySQL 与 PostgreSQL 上**从来没按字面生效过**，而且不报错。现在 `OFF` 显式写
  `DISABLED` / `disable` / `DISABLE`，并在加密对端上量回：`SHOW SESSION STATUS LIKE 'Ssl_cipher'`
  与 `pg_stat_ssl` 的 `cipher` 都必须是空。
- **证书没通过校验时，提示把你支去查网络**（2026-11-02，R47-a · D-32）：MySQL 的驱动把 JSSE 的证书异常
  **包在 `Communications link failure` 里**，MariaDB 那句是 `Could not connect to …: (certificate_unknown)
  PKIX path building failed` —— 两种写法都撞在「连接已断开」那支探针上，于是证书问题永远归不进证书类，
  建议让你去检查网络。新增 `Kind.TLS_VERIFICATION_FAILED`（徽标「证书校验失败」），两条建议分开写：
  链认不出 → 教怎么把 CA 交给驱动（PG/MariaDB 用「根证书」格，MySQL 走 `trustCertificateKeyStoreUrl`）；
  名字对不上 → 用证书里写的名字连（例如 `localhost` 而不是 `127.0.0.1`）。证书探针必须排在连接探针**前面**，
  这个先后由一条真库异常链的单测钉住（同一条消息里既有 `Could not connect` 又有 PKIX）。
- **连接根本没建起来，却报成「查询超时」**（2026-11-02，R47-a · D-34）：超时探针原来排在连接探针前面，
  而 MySQL 连不上时最外层就是 `Communications link failure`、cause 里常带 `connect timed out` ——
  于是建议成了「缩小查询范围再试」，可用户面对的是一条没建起来的连接。超时分支下移到连接之后
  （锁等待超时仍留在最前，那是 SQL 层的事，建议不同）。

- **MySQL / MariaDB 上「设计视图」不再问「要不要先删掉再建」**（2026-11-02，R52 · D-29）：
  我们一直按「MySQL 没有 `CREATE OR REPLACE VIEW`，那是 MariaDB 的分支特性」写方言，
  于是改视图被拼成 `DROP VIEW` + `CREATE VIEW` 两句。这条说法从没在真库上验过 ——
  R52 把视图生命周期加进真库契约，MySQL 8.4 与 MariaDB 11.8 都收单句、
  而且**真的替换了定义**（`SHOW CREATE VIEW` 回读新正文）。判反的代价不是「多打一句 SQL」：
  两句之间视图**真的不存在**，依赖它的对象在那一刻会失败，弹窗还要为它多要一次危险确认。
  现在 MySQL 走单句（预览一句、无灰字提示、不二次确认），六方言里**只有 SQLite** 仍需要两句先删再建。
- **SQL Server 上的视图打不开设计器**（2026-11-02，R52 · D-27）：`SqlServerDialect.readViewDdl`
  是个 `return null` 的空壳，而「设计视图」的规则是**读不到库里那份定义就不开窗**
  （宁可拦住，也不能拿一个空正文盖掉真实定义）—— 后果是在 SQL Server 上这个命令永远只把
  状态栏写成「读不到视图定义」，弹窗一次都没出现过。注释里还一直写着「走 `sys.sql_modules`」，
  等于文档在替一个不存在的实现背书。
  现在真的发一条目录查询：`sys.sql_modules JOIN sys.views`（联 `sys.views` 而不是裸
  `OBJECT_ID(?)`，后者对函数与同义词也会给定义，而这条路只该认视图），
  读回为空或全白仍按原规则不开窗。
- **弹窗里的错误行终于红了**（2026-11-01，R42 帧目视）：`app.css` 的 `.adb-dialog .label`
  特异性 (0,2,0) 一直压着裸类 `.adb-error` / `.adb-muted` 的 (0,1,0) —— 任何弹窗里的校验错误行
  与提示行都渲染成正文黑，红字只活在单测里。补两条同特异性的后置规则，新建表弹窗一起受益。
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
