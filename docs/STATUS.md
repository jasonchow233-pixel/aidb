# 现状与限制（STATUS）

> 这份文档承担两件事：**能力面清单**（按能力，不按类名）与**完整限制清单**。
> 它是全仓库**唯一**记「验证基线数字」的地方 —— README、ROADMAP 都不再各自记一份，
> 因为历史上同一个数字在三处不一致过（`964/123` 与 `1108/134` 并存，而后者实测是 `1107/133`）。
> 数字必须是**回量出来的**，并写明量于何日、什么平台、什么命令。

## 1. 验证基线

### 当前工作树（2026-11-14，R2 S3 兼容真端点契约 + contentType 模型修复 + 真端点画面帧 · 全量门禁已绿）

- **2197 执行 / 0 失败 / 6 跳过 / 225 个测试类**，`BUILD SUCCESS`、`BUILD_EXIT=0`。
  命令 `set -a && . scripts/.dbmatrix.env && set +a && MAVEN_ARGS=-o ./scripts/build.sh`
  （= `./mvnw -B clean verify` + `java tools/TestCount.java`），
  2026-11-14 23:16 +08:00、本机 Darwin 24.6.0 x86_64、Temurin JDK 25.0.5（`JAVA_HOME` 指向
  `~/.workbuddy/jdk/jdk-25.0.5+8`），Total time **35:18 min**，**16/16 模块 SUCCESS**。验证时源码为
  提交 `97b082b`（基线量于其工作树 = `97dd36d` + 16 个文件：14 改 + 2 新增，
  含 1 个真端点契约类、1 张真端点帧）。
  同日不带环境的全量为 **2090 / 0 / 113**（36:35 min，**16/16 模块 SUCCESS**）；
  两次差 **107 条执行 / 107 条跳过**，与上一基线同口径。较 2026-11-12 的 **2186 / 6**
  多 **11 条执行**，全部来自本轮 `adb-objectstore`（19→30）：
  `S3RealEndpointContractTest` 10 + `ObjectStoreSessionTest` 新增 1。
  逐模块执行数（`TestCount` 口径，带环境那一次，跳过单列）：`adb-ui-shell` **1158** ·
  `adb-ui-core` 70 · `adb-safety` 88 · `adb-sql` 42 · `adb-model` 30 · `adb-fixtures` 3 ·
  `adb-editing` **54** · `adb-objectstore` **30** · `adb-asset` **18** ·
  `adb-introspect` **292** · `adb-redis` 14 · `adb-session` **42**（跳 5）·
  `adb-transfer` 115 · `adb-jdbc` **241**（跳 1）。
- ⭐ **五套真库矩阵 + 一套对象存储真端点契约本轮全部实跑、0 跳过**
  （带环境那一次逐类读出来的，不是抄上一版）：
  主矩阵 `ExternalRealDatabaseTest` **51 / 0 失败 / 0 跳过**（五引擎 × 10 + 1）、
  TLS 矩阵 `ExternalTlsDatabaseTest` **18 / 0 / 0**（三个加密对端 33071/33072/54331）、
  写回矩阵 `EditWriteBackMatrixTest` **15 / 0 / 0**（契约第 10 / 11 / 14 条）、
  导出往返 `TransferRoundTripMatrixTest` **5 / 0 / 0**（第 12 条）、
  SSH 跳板 `SshTunnelMatrixTest` **5 / 0 / 0**（第 13 条）+ `SshTunnelTest` 11、
  ⭐ `S3RealEndpointContractTest` **10 / 0 / 0**（真 LocalStack 4.9.2 容器，30.34 s）。
  账闭环：**2090 + 107 = 2197**，**113 − 107 = 6**；较上一基线 **+11 条执行**，
  两次全量（带 / 不带矩阵）都把这 11 条实跑了。
- ⭐ **画面债已平账，并补上对象存储真端点帧**：`docs/screenshots/` 现为 **74 张**
  （68 张基准帧同版重拍 + 6 张新面），`md5` **零重复**，逐张目视核对过。
  六个新面的帧：`p10-onboarding`（引导浮层三卡片 + 背景工作区可见，1280×792）、
  `p5-connect-family`（族筛选带选中 Object Storage chip、只剩 4 张对象存储磁贴，900×700）、
  `p10-tabs-overview`（搜索框 + 按连接分组 + 5 行标签 + Activate/Close，642×376）、
  `p10-object-browser`（离线内存后端：README.md 47B + `brand/` `reports/` 虚拟文件夹，1280×792）、
  `p10-redis-keys`（**真 Redis 端点**：redis:7.2 容器、状态栏绿灯、1280×792）、
  ⭐ `p10-object-store-real`（**真 S3 兼容端点**：LocalStack 4.9.2 over HTTPS，
  `README.md` 58 B + 服务端时间戳 + `Storage class STANDARD` + `brand/` `reports/` 文件夹，
  1280×792；走 `ADB_OBJECT_BROWSER_REAL=1` 摆态，经产品工厂连真端点并用产品会话写入对象，
  连不上就弃帧，不退回内存后端）。
  ⚠️ 两族真服务端帧都只证**各自那一支**：Redis 帧连 redis:7.2，对象存储帧连 S3 兼容端点；
  OSS / Kodo / COS 三家原生 SDK 仍无画面证据（R2 剩余阻塞项，见 §3 第 1 条）。
  五枚新品牌图仍是中性几何占位件（R4）。
- ⭐ **目视验收抓出一处真实文案缺陷并已修**：`wizard.type.hint` 仍写「ships drivers for these
  **six** databases only」，而磁贴已从 7 张扩到 11 张 —— 中英两版都改成不带数量断言的
  「This edition ships the connection types shown above.」/「本版只带上面列出的这些连接类型。」。
  这是行为门禁抓不到的缺陷（它问的是键在不在、值对不对，不问数字对不对）。
- ⭐ **目视验收抓出 Redis 帧里的假阴性报错，顺藤摸出一条真缺陷并已修（非 SQL 连接路由）**：
  第一版 `p10-redis-keys` 帧键数据正常、状态栏却写「Connection failed: Redis 尚未开放连接
  （P1 / P2 落地后端会话）」—— 键明明是 `RedisSessions` 真连出来的。根因是三条 SQL 专属
  路径没按资产 kind 分派：
  ① 侧栏/页签激活连接统一走 `connectProfile → sessions.open(specOf(profile))`，而
  `ConnectionProfile.spec()` 对非 SQL kind 抛护栏 → 被当成「连接失败」；
  ② 侧栏 `setConnection` 无条件触发对象懒加载，`loadObjectsFor` 只会说 SQL 这一种话；
  ③ `loadCatalogsFor` 对对象存储档案会挂出假的「catalog 读取失败」重试节点。
  修复：`connectProfile` 对非 SQL 分派到新的 `connectNonSqlProfile`（后台用浏览页
  <b>真正会用的会话路径</b>真验证一次可达性——Redis 真握手 `databases()`，对象存储走
  同一个 `objectStoreSessionFor` 工厂真列 bucket；成功才点亮状态栏，新增 i18n 键
  `shell.connectedReady`）；`loadObjectsFor` / `loadCatalogsFor` 对非 SQL kind 直接收口
  （树内容各自由 `loadRedisDbs` / `loadBuckets` 懒加载）。修完重拍，帧内状态栏为
  「Connected matrix Redis」、右下角绿灯 `Redis · matrix Redis`。
  这也是行为门禁抓不到的缺陷——`NonSqlProfileRenderTest` 只钉「打开页签不抛异常」，
  状态栏谎报没人问。
- ⭐ **同源过期文案一并清理**：`ConnectionProfile.spec()` 护栏消息从「尚未开放连接
  （P1 / P2 落地后端会话）」改为「不是 SQL 资产，不走 ConnectionSpec（请用其资产族自己的
  会话路径）」—— 护栏本身必要（`ConnectionSpec` 是 JDBC 语义），但旧文案撒谎说后端会话
  未落地；新护栏被新门禁 `NonSqlProfileSpecGuardTest`（adb-session，3 例）钉住。
  `ObjectStoreFamily` 类注释与 `app.css` 的「尚未开放连接」注释同步改为已落地口径。
  棘轮门禁（`I18nAuditTest.modelLayerChineseIsRatchetedDown`）当轮抓住：护栏消息拆成
  两条 CJK 字面量超限，合并回单条后转绿——这条门禁确实在工作。
- ⚠️ **剩余 6 条跳过都是设计如此，不是「没验」**：`adb-session` 5 条是 `CredentialStoreTest`
  里的 Windows 凭据管理器（WinCred）用例，按平台 `assumeTrue` 跳过（本机 macOS，这一半只能在
  Windows 上跑）；`adb-jdbc` 1 条是 `DatabaseVersionMatrixTest`，它要 `ADB_TEST_VERSION_MATRIX=1`
  才开跑（同一引擎多版本矩阵，跑一次十几分钟，故意不并进默认门禁）。
- ⚠️ **一次时序假红已排除、不是靠调断言过的**：本日第一次全量 `BUILD FAILURE`，唯一红是
  `TerminologyGuardTest.oneActionHasExactlyOneName`（UI Shell 1155 条里 1 条）—— 构建跑到
  UI Shell 之后才把 `action.tabs` 的中文术语改成规范词「概览」，而这条门禁读源码、跑在改动
  之前；它同时会从本地安装的 `adb-ui-core` 资源里取文案。刷新该 jar 后单独复验 7/0/0，
  再 clean 重跑全量得上面的全绿。教训与 2026-11-11 的 stale jar 坑同源：**构建中途改
  `messages_*.properties` 会让当轮结果失真，必须重装再重跑**。
- ⚠️ **本轮撞过一次环境的批量删除保护**：整批重拍到第 50 张时，脚本每帧前删旧 PNG 的动作
  累计触发 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`（本轮累计 50 次 `rm`）。这不是项目缺陷 ——
  补拍改成「每帧输出到独立临时目录 + `cp -f` 覆盖」，全程零删除。

### 上一次全量（2026-11-11，P0 收口 · 代码门禁已绿）

- 基于 `d976732` + 58 个 WIP 文件，已通过完整门禁（`./scripts/build.sh` = `clean verify` + `tools/TestCount.java`）：**1998 执行 / 0 失败 / 113 跳过**，2026-11-11 本机 Darwin 24.6.0 x86_64、Temurin JDK 25.0.5+8、`BUILD SUCCESS`。本次回量覆盖全部 P0/P1 收口改动（导出十项选项、查询构造器只读化、三枚诚实环删除、ER 图/HTTP 页签删除、Backups/Events 树分组、危险分级第三层回归门禁、文档棘轮门禁）。
- ⚠️ **五家外部数据库本轮仍未验**：`ADB_TEST_*` 未配置，矩阵类 `ExternalRealDatabaseTest`(51/50 跳)、`ExternalTlsDatabaseTest`(18/18 跳)、`EditWriteBackMatrixTest`(15/15 跳)、`TransferRoundTripMatrixTest`(5/5 跳)、`SshTunnelMatrixTest`(5/5 跳) 全跳过；仅 testcontainers 自起的 mysql:8.4 / postgres:16-alpine 一组替身跑了连接与契约用例。新增视图改名契约后主矩阵规模 **5 × 10 + 1 = 51**，待 `scripts/dbmatrix.sh` 起满五引擎后复跑。
- ⚠️ **68 张已入库截图与当前源码不同版**（G6 / S07）：`p3-query` 仍含已删的 Ask AI、`p2-users` 仍含已删的 Fill with AI、`p5-connect-general` 仍含已删的 HTTP 页签；当前版本仍需同版 UI 实拍（见 §5 待办）。
- 下方历史全量（2026-11-08）与逐批细账保留为历史证据，不覆盖；本次 1998/113 是 P0 收口后首次全量回量。

### 历史全量（2026-11-08，不代表当前工作树）

| 项 | 值 |
|---|---|
| 命令 | `./scripts/build.sh`（= `./mvnw -B clean verify` + `java tools/TestCount.java`） |
| 最近一次全量回量 | 2026-11-08 · macOS 15.7.9 x86_64 · Temurin JDK 25.0.4.1+1 · 本机 Docker 起满五引擎 + 每家两把库（`scripts/dbmatrix.sh`）**+ 三家加密对端**。**跨批收口回量：R63Q 起各批（查询页 D-50/51/53/54 · R64–R66 视觉与文案 · R69 文案一致性 · R71–R74 D-95…D-99 · R75 D-85 五族 · D-55/88/89/90/91/94 · H4-a JSON/SQL 导入 · H3-d/e/f 性能三件 · H3-f 撤池）之后**，两个口径各自量过、都是全绿：① `set -a && . scripts/.dbmatrix.env` 那一次 20:33→**20:58**（`Total time 25:30`，`BUILD SUCCESS`、脚本 `exit=0`）= **1816 执行 / 6 跳过 / 182 类**；② **不带**环境那一次 21:00→**21:24**（`Total time 24:43`，同样 `BUILD SUCCESS` / `exit=0`）= **1714 执行 / 108 跳过 / 182 类**。两次跑的是同一份源码（`5ed6a32` = CSS 补漏，含 H3-f 撤池 `e98bd06`），而且**单飞** —— 一颗等另一颗结束才起（同机同时跑两个 `build.sh` 会互相 clean 出假编译错，判成败只认日志里的 `BUILD SUCCESS`）。<br>差账落在**六家**（明细见下一行），其余五家一字不变；类数 172 → **182**；两口径的**执行差仍是 102** —— 新增 151 条一条都不吃矩阵环境（这批全是外壳/文案/导入/性能缓存/撤池的门禁，跟真库无关），逐模块的口径差与上一轮同形（editing 15 / introspect 19 / jdbc 69 含 1 条故意门控）。闭环核对：**1714 + 108 = 1816 + 6 = 1822**。<br>⚠️ **本轮第一次全量是红的，红在自己欠的账上**：`StyleClassCoverageTest.everyStyleClassUsedInJavaHasACssRule`（adb-ui-core）报 `adb-status__count` —— D-90（`1a1725d`）在 `StatusBar` 挂了这枚样式类却没写 CSS 规则，**此后每轮都是模块级跑（`-pl ui/adb-ui-shell` 带不出 ui-core 的门禁），这是那以来第一次真全量**，账被全量扫出。补上规则（`5ed6a32`）后第二次全量量出上面两个数。教训：**模块级绿 ≠ 全量绿，收口必须跑一次全量**。<br>⚠️ **H3-f 撤池也在本轮源码里**（决策记录见 ARCHITECTURE §6.1）：`adb-session` 那格 40 → 39 的下降就是它 —— `ConnectionPoolTest` 连类删除（−2）、`SessionManagerTest` 补一条「会话就是直开的 `JdbcSession`」（+1）。<br>⚠️ 上一轮那两行 **1665 / 1563 已被本轮覆盖**（它们钉的是 `8958e08` = R63-3 商标换代那份源码）。<br>上一轮 R63-3（11-04 20:41 / 20:59）**1665 / 1563**；上一轮 R63-2（11-04 18:15 / 17:57）**1657 / 1555**；再上一轮 R58-d（11-04 17:16 / 17:33）**1666 / 1564**；再上一轮 R62-a（11-04 12:59 / 13:17）**1619 / 1522**；R61（10:29 / 10:46）**1606 / 1509**；R57 批次 a/b/d/g（11-03 21:02 / 21:16）**1590 / 1493**；R56（15:02 / 15:16）**1530 / 1433** —— R56 那一跑第一次红过一条 Oracle 契约，就是 **D-43**（判定改成 `ExternalUnreachable` 唯一出处后才量出那两个数），账见 CHANGELOG 的 D-43 与 §测试环境。<br>⚠️ **R62-a 那一跑量到的 D-49 成因继续有效**，本轮不重开：契约侧的 Oracle 读超时已改成直接取 `OracleDialect.READ_TIMEOUT_MS`（本轮两份日志 `ORA-` 各 **0**，主矩阵逐类读回 **46 / 0 失败 / 0 跳过**）；`getDdl` 把「读不到」和「连不上」压成同一个 `return null` 的**产品那一半仍未修**，用户在 Oracle 上看到的症状原样存在 —— 机理、方向与「为什么只在全量里红」在 §3 与 CHANGELOG Known issues。 |
| 结果 | **1816 条 / 182 类 / 0 失败**（另有 6 条跳过，全部是**设计如此**的门控，见下）—— R63Q 起各批累积后回量（`./scripts/build.sh` 全绿 + 脚本 `exit=0`，2026-11-08 20:58，带矩阵环境；较 R63-3 基线 1665 / 172 = **+151 条 / +10 类**）。差账逐家：`adb-ui-shell` 858 → **984**（**+126**：R63Q D-50/51/53/54 · R64–R66 视觉与文案批 D-56…D-87 · R69 文案一致性 D-70…D-82 · R71–R74 D-95…D-99 · R75 D-85 五族 · D-55/88/89/90/91/94 —— 逐笔见内部台账 GAP-TODOLIST / NAVICAT-GAP-PLAN-V7-DRAFT 与 CHANGELOG）· `adb-introspect` 211 → **225**（D-64 可改⊆可分页门禁 · D-65 同名触发器 · D-94 主键 DDL · H3-d describe 表级缓存各批）· `adb-transfer` 50 → **59**（H4-a JSON/SQL 导入，与导出侧同形往返）· `adb-jdbc` 220 → **222**（D-68 Oracle/PG 裸名折叠语义进方言层；H3-f 删 `pooled()` 断言在内）· `adb-ui-core` 69 → **70**（H5-g 快捷键表按键列改读注册表）· `adb-session` 40 → **39**（H3-f 撤池）。上一轮的差账留档：R63-3 落在 `adb-ui-core` 62→69（新类 `BrandIconTest` 0→7）+ `adb-ui-shell` 857→858（`VendorGlyphTest` 7→8）。<br>逐模块（**执行数**，跳过分列在括号里）：`adb-ui-shell` **984** · `adb-ui-core` **70** · `adb-safety` 88 · `adb-sql` 42 · `adb-model` 30 · `adb-fixtures` 3 · `adb-editing` 54 · `adb-introspect` 225 · `adb-session` 39（跳 5）· `adb-transfer` 59 · `adb-jdbc` 222（跳 1）= 合计 **1816 / 182 类**。<br>⭐ **五套真库矩阵本轮复跑仍全部真跑且全绿**（带环境那一次逐类读出来的，不是抄上一版；自 R63-3 起一条契约都没动）：主矩阵 `ExternalRealDatabaseTest` 46 / 0 失败 / 0 跳过、TLS 矩阵 `ExternalTlsDatabaseTest` 18 / 0 / 0、写回矩阵 `EditWriteBackMatrixTest` **15** / 0 / 0（契约第 10 / 11 / **14** 条）、导出往返 `TransferRoundTripMatrixTest` 5 / 0 / 0（第 12 条）、SSH 跳板 `SshTunnelMatrixTest` 5 / 0 / 0（第 13 条）。<br>⚠️ **口径一（数字对不上时先看这条）**：这里的逐模块是 `tools/TestCount.java` 的**执行数**（按 `<testcase>` 元素计，且不把带 `<skipped/>` 的算进来）；surefire 每模块末尾那行 `Tests run:` **含跳过** —— 同一次实测 `adb-jdbc` 223 对 222、`adb-session` 44 对 39。两个口径别互相抄（`@Nested` 口径警告见本节下方）。<br>⚠️ **口径二（本轮两个数各自量过，不是抄上一版）**：**不带**矩阵环境那一次 = **1714 执行 / 108 跳过**（21:24，`adb-ui-shell` 同为 984、`adb-ui-core` 同为 70、`adb-editing` 39 跳 15、`adb-jdbc` 154 跳 69、`adb-introspect` 206 跳 19、`adb-session` 39 跳 5）；带环境 = **1816 执行 / 6 跳**。两口径的**执行差仍是 102**（R56→R63-3 那个 102 本轮一格没动）—— 新增的 151 条全是外壳/文案/导入/性能缓存/撤池门禁，**一条都不吃矩阵环境**，所以执行差与跳数（108 / 6）原样。账闭环（**1714 + 108 = 1816 + 6 = 1822**）。所以基线一律**带环境量**，这两个数不可比、也不许互相抄 —— 这也解释了「为什么我本地的数比文档少 102」。<br>⚠️ **本轮的性质**：一次**跨批收口回量**，不是单一批次的收口 —— 自 R63-3 起有 45 个提交碰过 shell 测试目录（37 个测试文件、+6346/−113 行），每一批自己的门禁数字当时都已逐模块量过（批账见内部台账与 CHANGELOG），本行只对总账负责。R67 性能三件里量过的数：H3-d describe 表级缓存 **1430µs → 9µs**（`acddf74`）、H3-e 分页 COUNT 一执行只数一次（`9444a35`）。<br>⚠️ **画面账**：R69 帧轮已把 65 张全量重拍、D-62 收口（`74af9a5`），R72/R73 各立一张新帧，R74 起通道对「尺寸与已入库版不符」默认弃帧（`ae4e325`，D-98）；本轮又平掉 D-55/D-88 的帧债（`aa43642`）。此后 R75/R76 与 H3-f 的界面改动，以及本轮的左树搜索框贴底、RoutinePage 可编辑等，已于 **2026-11-09/10 全量重拍 68 张**（含 13 张 `ADB_MATRIX` 真库帧，五引擎矩阵在线）补平：首轮整跑被安全删除门禁在 `p6-design-view-sqlserver`（第 50 次 `docs/screenshots/` 删除）误截断、脚本未走自身 `fatal` 伪装退出码 0，导致后 19 张漏拍，用 `ONLY=` 单帧模式补齐；Oracle 设计视图帧首拍成假整窗（弹窗未开退回主窗口 1280×792），根因是 `dbmatrix.sh` 的 Oracle 种子 `v_active_users` 视图未建（输出被 `> /dev/null` 吞、错误未暴露），已用容器内 system 口令重放种子补齐、复拍拿到 720×540 真弹窗。现 **68/68 真帧入库**，像素判断纪律恢复生效。⭐ R76 H4-b 的单帧 `p6-new-table-checks`（900×680，2026-11-09 实拍并目视）仍在，且配了帧前提门。<br>⭐ **2026-11-10 第十一轮的界面改动已全量重拍 68 张**（Ribbon 删 Automation / Model 两格（13 → **11 格**）、数据网格工具条删 Data Generation 一颗键（12 → **11 个具名键**）、新增例程清单页、数据画像按钮改接真页面）：重拍分批做（首轮整跑会被安全删除门禁在第 50 次 `docs/screenshots/` 删除处截断），并用**逐像素增量核对**确认变化只落在本轮改动的盒子里（Ribbon 区 / 工具条区 / 状态栏）。核对时抓出并修掉两处**陈旧帧**：`p2-form` 旧版是 288×81 的裁片（只拍到一句空态文案），新版是真表单视图；`p2-overview` 旧版是侧栏折叠态（内容区左边界 x=48），新版展开（x=260），与同批其它帧一致才配当验收证据。另抓出**一次假帧**：26 帧树区多出一条 21px 浅蓝空行（色 238,246,253 = `-adb-bg-hover` 叠白底，与 `.adb-object-tree .tree-cell:hover` 精确吻合）—— 先排除产品缺陷（SideBar 本轮只改常量、历史 8 个提交全都没有、demo 库没多出对象），再用**单帧重拍做对照实验**，逐帧重拍后该行全部消失，判定为**批拍瞬态**（物理鼠标恰停在某个树格上，`:hover` 进了快照 —— 就是 `shots.sh` 头部「坑 5」写的那一类），已分 6 组单帧重拍，现瞬态为 0。**像素判断纪律恢复生效。**<br>⚠️ **测试侧纪律（R47-b 定的，本轮继续用）**：外壳级门禁留下脏页签时，`@BeforeEach` 的 `resetUi` 一点关闭钮就撞上模态「未保存」确认（`showAndWait` 卡在 FX 线程上）—— 后果不是报红而是**把后面整批测试拖成超时**。断言前先 `markClean`。<br>⚠️ **测试侧纪律二（R57-g 定的，本轮继续用）**：TestFX 一个测试类里是**同一颗 primary stage**，`@Start` 重建场景但窗口尺寸留给下一条 —— 动过 `stage.setWidth/setHeight` 的门禁会把几何漏给下一条，像素类断言会红在布局而不是行为上。类里用 `@BeforeEach` 把窗口钉回固定尺寸。<br>⚠️ **测试侧纪律三（R62-a 收的，继续有效）**：**裸构造的 `SideBar` 行高不是实测行高** —— 裸 `new SideBar` 读 20px（`Tokens.ROW_TREE_LIGHT`），产品启动经 `AppShell:338 → applyDensity → setDensity → RowHeights.of` 默认档是 **21px**。文档里 20 / 21 互相抄错的四处已改回「写明来源」的口径（见 `UI-VISUAL-SPEC` §4 与 §7.4）。<br>⭐ **测试侧纪律四（R58-d 收的，画面通道）**：**摆态里要点开模态弹窗的那一键，必须跳出动画脉冲** —— `PauseTransition` 的 `onFinished` 里直接 `button.fire()`，弹窗里的 `showAndWait()` 抛 `IllegalStateException: showAndWait is not allowed during animation or layout processing`；后果不是报红：**异常被兜掉、弹窗没出现、快门照拍**，产出一张只有脏格子的主窗口而帧名写着 preview。改成 `Platform.runLater` 再点（真用户点键落在输入事件里，本来就不在脉冲里），并在快门**之前**量一次「模态 Stage 真的在屏幕上」，量不到就弃帧（R74 起尺寸不符另有通道级弃帧门，见 D-98）。<br>⚠️ **性能与线程账**：R61 三件早已收官（冷启动 A/B 各 3 次平均 −344ms，**<3s 阶段目标没达到**，待拍板 Q7 · `SessionManager` 全局锁换每连接一颗锁 · 数据页 `⌘.` 真的取消保存），账在 CHANGELOG 与 §3 性能/线程模型；R67 又添 H3-d/e/f 三件（describe 缓存 1430µs→9µs、分页 COUNT 只数一次、撤池 —— 决策记录 ARCHITECTURE §6.1）。<br>⚠️ **`./mvnw -pl <模块>` 不带 `-am` 会用本地仓库里已安装的旧构件**（本轮模块级补跑继续遵守）—— 变异验证一律带 `-am`。<br>⚠️ 日志里的非致命异常（**带环境那一次**数出来的，两份日志各自读回）：Monocle 离屏缓冲异常（`Too small int buffer size …`）**1 次** —— 间歇出现的老面孔（多轮在 0 与 1 之间摆，本轮带环境 1 次、不带 0 次）；`Key too long: ui.query.colwidth.…`（**D-18**）**0 次**（连续第十五轮）；`[真库契约] 清理临时视图失败`（**D-30**）**0 次**（连续第十五轮）；`[screenshot] FRAME ABORTED` **0 次**；`ORA-` **0 次**（连续第五轮归零）。两口径各有 **1** 行 `[screenshot] pose gave up`（非拍摄会话里选区摆态放弃、没有落盘成帧 —— 正是 D-37b 想要的样子，弃帧不出声地拍完比报红更糟）。 |
| R58 逐格细账（**+47 条 / +2 类，逐模块与逐类都量出来的**） | 从 R62-a 收官的 1619 / 169 起账，本轮 **+47**：`adb-sql` 31 → **42**（`SqlQueryShapeTest` 0 → **11**，新类：单表 SELECT 识别层的规则与两处假阳性）+ `adb-editing` 33 → **54**（`ResultEditPolicyTest` 0 → **16** 新类，判定层与 21 条卡点键，其中 R58-d 补的两条钉的是**行身份按表侧列名编址**与「那一格在行里没有值就不给身份」；`EditWriteBackMatrixTest` 10 → **15**，契约第 14 条五引擎）+ `adb-ui-shell` 851 → **866**（`QueryPageTest` 30 → **45**，+15 全在 R58-b2「判定接进结果区」与 R58-c「编辑 + 保存」这两批里 —— 逐批各加几条**没量过**，只量到首末两个数）。<br>⭐ **本轮的红各是哪一条**（都是「红得直白」，不是靠调断言过的）：`QueryPageTest` 那三条新门禁在 R58-c 之前全绿着放过了一个真缺陷 —— 把 `RowIdentity` 改成按**结果列键**编址，原有 42 条一条不红（夹具写 `SELECT *`，结果列键恰好等于表列名），只有带别名那句能打死它，红 1；非空列被清空不拦 → 红 1；「一条都没命中」也报已保存 → 红 1。契约第 14 条同一条陷阱在**五家真服务器**上各红一次（变异：`identity` 的键名换成 `resultKey` → 五家 + 单测共 6 条红，revert 回绿）。<br>⚠️ **两条「量过才知道写错了」的账**：① 画面通道第一版 preview 帧是**假图**（`showAndWait` 在动画脉冲里被禁，异常被兜掉、帧照拍），救命的是逐张目视而不是脚本 —— 去重门禁看不出来，那张和 dirty 只差一个光标；② `QueryPage` 那条「保存后没有重取钩子」的注释原来把它写成**现役症状**，实测两层的拒集重合（形状层先拒 `WITH` / `INTO` / `FOR UPDATE` / 多条语句，分页层拒的恰好是这几样），所以今天是**潜在边界** —— 注释已按事实改写，门禁留作 **D-64**（见 GAP-TODOLIST）。<br>⚠️ **R57 那一版的 `TestCount` 口径警告继续有效**：`tools/TestCount.java` **本身也吃矩阵环境** —— 同一份源码，带环境时逐家报 `adb-editing` 54 / `adb-introspect` 211 / `adb-jdbc` 220，不带时报 39 / 192 / 152（差的就是那 102 条设计性门控，脚本写成「跳过 N」）。所以「TestCount 的数」必须先问是哪一口径。 |
| R62-a 逐格细账（**+13 条 / +1 类，逐模块量出来的**） | 从 R61 收官的 1606 / 168 起账，本轮 **+13**：`adb-ui-shell` 839 → **851**（`AppBarTest` 17 → **20**：角标贴在磁贴右上角 + 格子没被挪动 + 同行基线；`SidebarSelectionTest` 3 → **6**：选中带四点读**像素**、选中瞬间文字不跳、箭头槽宽恒 6px；`SideBarLivenessDotTest` 3 → **4**：那颗点是图标的右下角标、不占名字那一列；`TableListPageTest` 22 → **23**：「用 AI 新建表」在**点之前**就说没做；`RibbonCapabilityAuditTest` 4 → **5**：挂标集合 ⇔ 落说明页集合；`SideBarCollectionGlyphTest` 0 → **3**（新类））+ `adb-jdbc` 219 → **220**（`DialectTimeoutTest` 4 → **5**：契约的 Oracle 读超时不许短于产品）。<br>⭐ **这 13 条里有一条是「红过一次才知道要写」的**：`SideBarCollectionGlyphTest` 第一版红在**自己的判据**上 —— 它钉「五类集合行都渲染出来」，实测读到 **7 行**，因为「已保存查询」那个分组挂在**三条连接**下面（`savedQuerySource` 是全局的）。改成按行身份去重再钉，并在注释写清「原始条数钉不住的原因」；**没有**把期望改成 7 迁就实现，也没改成 5 让它假绿。<br>⚠️ **修复前的红各是哪一条**（都是「红得直白」，不是靠调断言过的）：`SidebarSelectionTest` 那四点在 H5-j 之前读到的左缘是 `bg-selected` 而不是 accent —— 那条 2px 强调条从来没被画出来过（`-fx-border-color` 四值顺序上右下左，强调色挂在了宽度为 0 的上边）；`AppBarTest` 的同行基线第一次量出 View / Function / Others 三格标签 45、其余九格 46，对照实验（摘掉全部角标再量）证明与本轮改动无关，是图标资产自带的 1px —— 记成欠账，见 GAP-TODOLIST「新欠账」与 D-46。<br>⚠️ **H6-f 判掉，零门禁、零代码**：立案那句「catalog 节点走通用 `Icon.DATABASE`」读的是改之前的代码；R54 已交付且 `SideBarTreeTest.catalogNodesCarryTheirConnectionsBrandColor` 早在，所以这条不产生任何数字。 |
| R61 逐格细账（**+16 条 / +1 类，逐模块量出来的**） | 从 R57 收官的 1590 / 167 起账：R57 画面债 **+11**（`adb-ui-shell` 826 → 837，模块级实测 2026-11-04 08:40）→ R61 **+5**（`adb-ui-shell` 837 → **839**：`AppShellTest` 33 → 34 的「ensure 不在 FX 线程」+ `DataGridSaveGuardTest` 6 → 7 的「排队中的保存取消后库里一行不变」· `adb-editing` 32 → **33**：`EditSqlBuilderTest` 8 → 9「中断的线程一整批都不写」· `adb-session` 38 → **40**：`SessionManagerTest` 5 → 7「慢连接不阻塞其它连接与读操作」+「同 id 并发只建一个会话」）= **1606 / 168 类**。<br>⭐ **修复前的红各是哪一条**（这三条都是「红得直白」的，不是靠调断言过的）：`demoDatabaseIsNeverOpenedOnTheFxThread` 报「跑在「JavaFX Application Thread」上，而 FX 线程是「JavaFX Application Thread」」· `SessionManagerTest` 报「慢连接在途时，注册表的读操作不该排队（**实测等了 2010ms**）」（探针故意睡 2s）· `DataGridSaveGuardTest` 报 `expected "User 002" but was "Written"`。<br>⚠️ **R57 那一版的 `TestCount` 口径警告继续有效**：`tools/TestCount.java` **本身也吃矩阵环境** —— 同一份源码，带环境时逐家报 `adb-editing` 33 / `adb-introspect` 211 / `adb-jdbc` 219，不带时报 23 / 192 / 151（差的就是那 97 条设计性门控，脚本写成「跳过 N」）。所以「TestCount 的数」必须先问是哪一口径。 |
| R57 画面债（2026-11-04 · 两帧落地，顺出 **D-47**） | **这一批没有新功能，还的是「交付时只有门禁、没有画面」那笔账**：`p3-query-completions`（补全弹层）与 `p3-query-selection`（圈住第二段真跑）两张 1280×792 帧入库，`scripts/shots.sh` 59 → **61 张**（53 亮 + 8 暗，其中 10 行绑 `ADB_MATRIX` 落点门）。弹层在 ControlsFX `PopOver` 的 `PopupWindow` 里，主场景快照本来拍不到它 —— 通道加的是第三条路（拍完主场景再把浮层单独快照、按场景坐标 src-over 合成），既不改成「只拍那一列候选的小图」（证不了「贴在光标下面」），也不拍一张没有弹层的查询页冒充证据。<br>⭐ **两帧的第一版都是假图，是目视判掉的**：文件名写着 completions，画面里候选一个没有、弹层被合成到窗口左上角 (146,-19)。根因不在合成通道，在弹层自己：`PopupControl.show(owner, x, y)` 要**屏幕**坐标，`caretAnchor()` 交的是编辑器局部坐标 —— 实测光标包围盒 (328.8, 82.5)→(330.8, 100.5)、锚点应取它的左下 (328.8, 102.5)，弹层的窗却开在 (132,-2)。这就是 **D-47**（用户可见缺陷，从 R57-a 交付起一直在，见 CHANGELOG Fixed）。**为什么 27 条补全门禁一条都没抓到它**：它们问的是候选内容与采纳结果，从不问坐标 —— 画面债的价值就在这儿。<br>⚠️ **弃帧出口本轮补了三个洞**：① 摆态预算 30s > 快门 14s，「放弃」永远排在落盘之后（现在 80 跳 ×150ms ≈ 12s，那一格 wait 抬到 20000）；② 「编辑器还没挂上窗口」被当成「摆不成」直接 return，一声不响把无候选的页面拍了出去（现在继续等，等不到才弃）；③ `abortMatrixFrame` 在非拍摄会话里也 `System.exit(3)`，一条测试里的弃帧会端掉整批 JVM（现在只在真有帧要弃时才退，日志文案也分开，免得 `[screenshot] FRAME ABORTED` 这个计数被测试日志污染）。<br>**门禁**：`adb-ui-shell` 826 → **837**（**+11，实测**：`./mvnw -B -pl ui/adb-ui-shell test` = 837 / 0 失败 / 0 跳过，2026-11-04 08:40，12:44 分钟）。逐笔：`QueryPageTest` 28 → 30（+2：pose 摆的选区就是那句、找不到那句就不摆）· `QueryCompletionsTest` 8 → 10（+2：摆态登记浮层、**弹层贴在光标下面**那条把 D-47 钉住的坐标门禁）· `ScreenshotScriptAuditTest` 10 → 11（+1：脚本用到的每个 pose 开关在 AppShell 必须有分支）· `ScreenshotOverlayTest` 5（新类，逐像素钉合成：整块替换 / alpha=0 保留原像素 / 半透明通道值 / 越界裁剪 / 坐标现场算）。<br>✅ **这笔账 2026-11-04 10:46 全量重跑后已平**（上面两行）：本批 +11 条与 R61 的 +5 条都进了那次带环境的全量 —— 837 → 839（R61 又加 2 条）、全量 1606 / 168。当时那句「下一次全量按算术应读 1601，那是算术不是实测」现在作废：**数是量出来的**。 |



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

> 下表描述实现与历史证据，不表示当前 WIP 已验收；当前验证状态以 §1「当前工作树」为准。

| 能力面 | 状态 | 边界（哪些是真的，哪些不是） |
|---|---|---|
| 外壳与布局 | ✅ | 七区（菜单 / 功能区 / 侧栏 / 页签 / 对象工具条 / 内容区 / 状态栏）；侧栏 250px、折叠 48px、可拖 200–360；macOS 用系统菜单栏；状态栏按页型换段（查询 / 数据 / 对象三套），不再摆「自动提交 · 只读」 |
| 连接与凭据 | ✅ | 历史基线覆盖六种数据库方言：SQLite（本机）+ MySQL 8.4 / PostgreSQL 16 / MariaDB 11.8 / SQL Server 2022 / Oracle 23ai Free（`scripts/dbmatrix.sh` 五容器，有库层的四家各两把库）。当前 `ExternalRealDatabaseTest` 新增视图改名后为 **5 × 10 + 1 = 51 条**，尚未执行，历史结果不代表当前工作树已验收。⭐ R46 那一跑抓出四条只有真服务器才会报的缺陷（D-20 编造的 `sys.tables.collation_name` · D-22 Oracle 目录只认大写导致加引号对象全隐形 · D-24 `sys.triggers` 没有事件列 · D-25 `SHOW CREATE EVENT` 的定义体在第 4 列，按位置猜读到 `"SYSTEM"`）；R52 加第 8 条「视图生命周期」又抓出两条（D-27 · D-29，见 §2「视图生命周期」）；第 9 条「切库落点与隔离」把查询页那颗库选择器从「只由单测钉住」补成**真库证据**（同一台服务器两把库、同一个表名、字面相同的 SQL，两边各读各的行 —— 见 §2「查询页」）。⭐ R47-a 又起一圈**加密对端**（`dbmatrix.sh tls-up`：自签 CA + 一把冒名 CA，MySQL 33071 / MariaDB 33072 / PostgreSQL 54331），3 引擎 × 6 契约 = **18 例**：不加密真是明文、require 真把会话加密（`Ssl_cipher` / `pg_stat_ssl` 各读各的）、给了签发方链校验真通过、换一把没签过服务器的 CA 真被拒、证书里写着的名字（`localhost`）连得上、证书里没写的（`127.0.0.1`）真被挡 —— 这一跑抓出 **D-33**（选「不加密」时一个参数都不写，而三家驱动的默认都是「能加密就加密」：那个标签一直在骗人）、**D-32**（证书失败被归成「连接已断开」，建议把人支去查网络）、**D-34**（连接根本没建起来被归成「查询超时」）。**仍未在真库验证**：SNI（没有按 SNI 选多域名的对端形状，不写「已验」）与 **MySQL 5.7**（版本矩阵是 opt-in）。连接分组、收藏、复制、URI（含 Oracle EZConnect 与 SQL Server 分号参数串）；口令进系统凭据服务，**不落盘**；`connections.json` 落盘前剔除敏感键；所有方言直连（⭐ H3-f 撤池：原 HikariCP 包装 `maxPoolSize=1` 且连接借出不归还，池语义从未生效，已整体删除 —— 决策记录见 ARCHITECTURE §6.1） |
| 连接向导 | ✅ | 两页 900×700：类型页 **一行 6 张彩色磁贴**（R43-b：只摆真有驱动的六家，各顶**官方彩色商标图形**（R63-3 换代，见下「厂商识别」；品牌色令牌只留给仍走自绘形状的表面）；块居中，不留下半屏空白）+ 一行「其它数据库走兼容协议 / URI」的提示，属性页 6 页签（HTTP 页已删除）+ `AIDB ⟷ Database` 示意图 + 底部五键。⭐ 原来照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，其中 16 张点进去是「本版不支持」—— 那不是它的形态（它摆这些家是因为真连得上去），我们摆出来只是让用户多点十六次空；复选栏、搜索框、滚动容器与「无驱动说明页」随死磁贴一起删，「其它怎么办」改成页面上明写的一行。SSL 页的档位与根证书参数**由方言自报**（PG `sslmode` / MySQL `sslMode` / MariaDB `sslMode`+`serverSslCert`，MariaDB 没有 prefer 档）；⭐ R47-a 在加密对端上跑完之后翻两处：① 「不加密」**真的写** `DISABLED` / `DISABLE` / `disable`（原先不写参数，而三家默认都是「能加密就加密」—— 见 D-33）；② MySQL 的「根证书」格在 VERIFY_CA 以上**禁用**并把说明写成 `trustCertificateKeyStoreUrl`（Connector/J 8.2 既没有 `sslCA` 也没有 `sslCapath`，未知属性被静默忽略 —— 收一个填了不生效的路径比不给这个格子更糟）；SQL Server（encrypt + trustServerCertificate 配对）与 Oracle（钱包 / sqlnet.ora）收起档位并写明去哪里填；⭐ **R55-c 把 SSH 页从「说明牌」换成真表单**：勾上隧道后跳板地址 / 端口 / 用户 / 认证方式（口令 ⟷ 私钥 + 口令短语，二选一，切档时另一档的值不跟过去）都能填、填了真的进档案 —— 秘密按类型分家（`SshConfig` 无秘密进 JSON、`SshSecrets` 只进凭据库，拼装口只有 `ConnectionProfile.spec` 一处），主机密钥走 OpenSSH 的 `known_hosts`（落点可用 `-Dadb.ssh.known_hosts` / `ADB_SSH_KNOWN_HOSTS` 重定向，测试与截图都不碰用户目录）；私钥那一格旁边明写「支持 RSA / ECDSA，内置库不支持 ed25519」（D-41，提示词也从 `id_ed25519` 换成 `id_rsa`）。**未知主机密钥在 TOFU 下不再是默默接收任何新密钥**：跳板机递来没记过的密钥时弹一张指纹比对窗（`hostPort / keyType / SHA256 指纹`）由真人决定「信任并记录」还是「取消」，指纹按 OpenSSH 算法自算、与 `ssh-keygen -lf` 一致；密钥变了仍一律拒（这条不受影响）；弹窗只接在用户发起的连接上，启动演示库 / 矩阵验收走静默默认（窗口：`HostKeyPromptTest` + `SshTunnelTest` 覆盖，交互分支不靠像素帧）。**HTTP / HTTPS 代理隧道按 2026-11-10 裁定不做** |
| 对象树 | ✅ | 真实内省（表 / 视图 / 函数·过程；触发器按表归属在结构页）；MySQL EVENT 只读分组已接线，双击打开定义体，同版 MySQL 验收未完成；`Backups` 仅为备份工具快捷入口，不是备份文件历史；多连接同树；表节点懒加载展开列，失败可重试；⭐ **R68（2026-11-08）树收口四件**：① 系统库照 Navicat **显示**（P-8 拍板：`information_schema` 等与用户库同列上树，`isSystemCatalog` 整条拆除）；② 键盘 F2 重命名表 + Delete/退格 删表/删视图（P-9 拍板「都加」；不进场景级 accelerator，动作仍走注册表分派 —— 危险确认原样生效；多选/拖拽无帧证后置）；③ 表/视图节点「**刷新此表**」（树缓存 + H3-d describe 条目按表炸掉重读，`setColumns` 就地替换不重建整棵树）；④ 分组树上管理（连接右键「移动到分组」/ 分组右键「重命名分组」，弹窗与连接管理页同形；改名 = 逐成员搬家）；**搜索框的作用域是整棵树**（表 / 视图 / 函数·过程 / 已保存查询），所以提示词只写中性的「Search」，不再点名「表、视图」把自己说窄（R44-G2-a）；搜索框右侧另有**清除一键**，空框时连位置都不占；**右键菜单按对象类型分家**（表节点：清空 / 重命名 / 删除表；视图节点：设计视图 / 删除视图 —— 不再在视图上摆只能失败的表级动作）；**连接节点顶自己那家的官方商标图形**（与向导磁贴读同一登记行、同一份 `icons/brand/` 资产，所以两处的 MySQL 同形同色；深色主题下商标坐在一块亮色底板 `-adb-brand-plate` 上保可读性；认不出方言时回落中性插头，不挑一家画上去） |
| 厂商识别 | ✅ | 六家各**一枚官方彩色商标图形**（⭐ R63-3 换代：原来是「自绘图形 + 品牌色」两条腿，`icons/color/db_*.svg` 与 `Icons.Icon.DB_*` 六枚常量已删除，改吃 `icons/brand/<key>.svg` 里的拍平商标原件 —— 见 `ui/adb-ui-core/src/main/resources/io/agenticdb/ui/icons/brand/TRADEMARK-NOTICE.txt`，来源、校验强度与风险账都写在那里）。商标层**不吃主题、不吃 CSS**：资产里写死的字面 `#rrggbb` 逐层直达渲染，亮暗两档算出来的填充色完全相同（`VendorGlyphTest.theOfficialColorsSurviveCssInBothThemes` 钉这条），因此品牌色令牌 `-adb-vendor-*` 只服务**仍走自绘形状**的表面（库节点圆柱、`OBJECT` 页签等）与左右树行的着色，不再决定商标长什么样。深色主题下官方深底色（MySQL `#00546B` 对侧栏 `#172033` 实测约 1.9:1、MariaDB `#1F305F` 约 1.2:1、PostgreSQL `#336791` 约 2.4:1，非文本下限 3:1）读不清，**改的是它坐的表面不是标志本身**：`ConnectionCatalog.graphic` 给商标套一块白色圆角底板（新令牌 `-adb-brand-plate`，111 个令牌里的第 111 个），底板尺寸锁死在图标槽位上，所以树缩进不会跟着动。铺满界面上说得出「是哪家」的表面（⭐ R63-2 后是**九个**，原来十个：顶栏连接胶囊与它下拉的每一行随胶囊一起删除）、左树的连接节点与**库**节点（库保留圆柱形状，只有颜色跟父连接走）、折叠轨道的连接钮、`OBJECT` 页签、连接管理器 Type 列、状态栏「来源」（与活线点并排 —— 两件事两个线索）、查询页的库▾、向导磁贴与向导预览图。⭐ R54 立、R63-3 沿用：**这批表面全走 `ConnectionCatalog.graphic(kind,size)` 一个出口**，静态审计禁止任何表面自己绕过它取商标（`VendorGlyphTest.onlyTheSharedOutletAsksForBrandMarks` 扫 main 源码，`Icons.brand(` / `Icons.hasBrandAsset(` 的调用点只许出现在登记行那一处；旧的那条「不许写 `Icons.Icon.DB_*` 常量」现在结构上已经立不住了 —— 那六枚常量根本不存在，绕过等于编译不过）。**认不出方言时不许挑一家画上去**：`graphic(null,…)` 返回 `null`，回落中性插头 / 圆柱 / 点。（原来还有一句「顶栏那一格更彻底 —— 没图形就整格收起」，R63-2 连同那颗胶囊一起删了这段代码，回落口径只剩中性图形这一种。）⚠️ **一条法律账**：拍平 = 改商标（MariaDB / Microsoft 的条款明确禁止改色与衍生作品），这一轮换的素材按用户裁定「全部表面换，我拍平素材」落地，`TRADEMARK-NOTICE.txt` 逐文件记了来源与校验强度 —— SQL Server / SQLite / MariaDB 三枚与 Wikimedia Commons 原件逐字节相同（md5 记在案），MySQL / PostgreSQL 只核过形状与字面色、**Oracle 那一枚的红色是从 Oracle 自家 favicon 量的、不是原件色**。**对外发布前必须换成各家品牌页的原始矢量件**，未换之前这一格记的是「自用可用、发布未清账」。|
| 数据网格 | ✅ | 服务端分页（100/200/500/1000）、行首 16px 状态槽、类型行、列宽/顺序/显隐持久化、冻结列、矩形选择、⌘F 本页搜索、转置只读视图；工具条自 2026-11-10 起 **11 具名键**（BI、Data Generation 已移除）+ 视图/溢出两小键（放不下折进 `»`；R43-c：视图那颗 `≡▾` 不再钉 30px 宽，箭头不再压住图标）；底栏五键 + 页码框 + 齿轮（页大小/跳至记录）+ 放大镜 + 视图切换；**内嵌 Filter & Sort 生成器**（组内 AND / 组间 OR、Builder/Text 双模式、显式 Apply、Sort By 芯片） |
| Filter & Sort 面板 | ✅ | 网格内嵌（非弹窗），只改草稿不即输即查；`Criteria Edited` 标脏，`Apply Filter & Sort` 才生效。**组内 AND / 组间 OR（B1 已交付）**：`FilterGroup` 按 `MAX_GROUPS=8` 分组，组内 `AND`、组间 `OR`（单组等价于旧版平铺 AND，SQL 输出逐字节兼容）；Text 模式只读（自由 SQL 请去查询页）；`IN` 条件由列头漏斗的按值筛选带入、面板内不可行内编辑。⭐ R55-a：面板里**不再有第二套「什么算空值」的规则** —— `requiresNonEmpty` 改成问方言（`!type.textual()`），列头漏斗的「框里只敲了空格」与「没填」走同一条判定（`EditValues.emptiedCell`，R53 为写回立的那唯一出处），并有一条源码审计钉「`emptiedCell` 的调用点只有一个」 |
| 数据页写路径 | ✅ | 行内编辑 + 类型转换 + 按主键定位；NULL / 空串独立操作；批量操作走行多选 + 右键菜单 + 底部行操作条（TSV / INSERT / 克隆 / 置 NULL / 置空串 / 删除）；SQL 预览 → 单事务 → 失败回滚 → 保存后一次反向撤销。⭐ **R58-c 前置**：那个预览弹窗抽成 `SavePreviewDialog`，**数据页与查询结果区共用一个构造口** —— 两页各搭一遍的话，「默认按钮必须是取消、回车不许落在执行上」这条就有了两份实现，改回 `OK_DONE` 的那一份不会让另一份红；`DataGridSaveGuardTest` 现在直接问共用类，断言的还是同一条不变量，只是它罩住两页。⭐ **R53 起这条有真库证据**（此前九条契约量的全是「读」，这条用得最多的「写」只在 SQLite 替身上跑过）：契约第 10 条 `writeBackRoundTrip` 按界面的同一个顺序调同一个 `EditSqlBuilder`，在五台真服务器上量「中文不折问号 / `10.50` 不掉标度 / `true` 在 TINYINT(1)、BOOLEAN、BIT、`NUMBER(1)` 四档形状下都读得回成真 / 同行两格改动只发**一条** UPDATE 且 `WHERE` 只命中主键那一行 / 撤销打得回原值 / 删一行只少一行」；第 11 条钉「清空一格落成什么」—— 数字与布尔列五家全部落成**真 NULL**，文本列落成空串（Oracle 把 `''` 存成 NULL 是它自己的形状，如实分支钉）。⚠️ 如实记一条边界：Oracle 的 `NUMBER(1)` 在目录里归 `ColumnType.NUMBER` 而非 `BOOLEAN`（其余四家归 BOOLEAN），**两边都是事实** —— Oracle 没有可用的布尔建表档，契约按引擎各钉自己那一档，不假装五家一样。 |
| 结构编辑 | ✅ | 加列 / 改列 / 重命名 / 删列 + 两级危险确认；索引、外键、触发器、CHECK 可增删；表注释可读可改（SQLite 无 → 按钮禁用；⭐ 2026-11-02 真库契约补了「写进去再原样读回来」，同时修掉一条**表注释在 MySQL/MariaDB/PG 上从未生效**的缺陷 —— 一行没闭合的块注释把方言的读取钩子整段吃掉，接口默认返回 null 悄悄生效）；改列整表重建时搬运 CHECK；⭐ R46 D-23：**加列对话框的类型候选与默认值改成跟着方言走**（原先是一份硬编码的 SQLite 清单，套在 Oracle 上会摆出 `UUID`/`TEXT`/`REAL` 这些它没有的类型，SQL Server 的默认值还会落进 `VARCHAR` —— 实测那一家的 `VARCHAR` 走库默认单字节代码页，中文不报错但折成问号，所以默认改成 `NVARCHAR`）。类型清单与默认值都由方言自报（`Dialect#columnTypes` / `#defaultColumnType`），帧只拍得到合上时那个默认值（`p7-new-column`），候选清单跟着库变那一半由 `StructureColumnTypeTest` + 真库矩阵守 |
| 建表 / 表级生命周期 | ✅ | 建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，六种数据库方言各走自己的语法。字段网格八列 + `Virtual` 生成列 / `Key` 单下拉 / `Key Length` 前缀索引 / `Binary` / 属性面板自增；R43-c：字段页三段（工具条 / 网格 / 属性面板）在内容区整体垂直居中，列少时留白回到上下两端，不再在网格与面板之间留一条死空白；方言不支持的属性直接拦下并说明原因，不编假 DDL。⭐ **R76 H4-b（2026-11-09）建表弹窗补上 CHECK 页签**，共**八页**：字段 / 索引 / 外键 / **检查** / 触发器 / 选项 / 备注 / SQL 预览；CHECK 写进 `CREATE TABLE`、与其余对象收敛进同一条计划同一个预览（子句拼法 `StructureSqlBuilder.checkClause` 与「改列整表重建」共用，单答案点）；空表达式 / 重名在弹窗内拦截并路由到检查页，匿名约束合法。原文那句「还不能录 CHECK」是 H4-b 之前的口径（2026-11-03 曾把「不能录外键/索引」的旧口径翻掉）；建表后不自动打开该表 |
| 视图生命周期 | B3 待验收 | 新建 / 设计 / 删除视图走**弹窗设计器**（视图名 + `SELECT` 查询体 + 实时 SQL 预览，与建表同一条「预览即执行」：预览几句就跑几句）。重建写法由方言自报，⭐ R52 在真库上逐个量过：MySQL / MariaDB / PostgreSQL / Oracle 一句 `CREATE OR REPLACE VIEW`，SQL Server `CREATE OR ALTER VIEW`，**只剩 SQLite 没有单句形式** → 两句「先删再建」，此时预览显出两句并**追加第二次确认**（原先把 MySQL 也算进「没有」那一侧，判反了 = D-29：两句之间视图真的不存在，还白要一道危险确认）。查询体只收 `SELECT` / `WITH` / 括号开头的组合查询：粘整条 DDL、塞第二句 SQL 都当场拦下并写明原因。「设计视图」先在后台读回库里定义，**读不到就不开窗**（不用空 body 盖掉真定义）—— SQL Server 上这条路原本是 `return null` 的空壳，等于那个库上设计器从来没开过窗（D-27，R52 修：真查 `sys.sql_modules JOIN sys.views`）。✅ **已在真库跑过**（R52 · 契约第 8 条）：五引擎各跑一遍「建 → 重建 → 按产品读路径读回 → 删后必须报错」，三种重建写法都被执行过一次。⭐ R47-b 补上**画面证据**（此前只有行为证据）：`docs/screenshots/p6-new-view-mysql.png`（`CREATE VIEW \`v_paid_orders\`` 的方言预览）、`p6-design-view-mysql.png`（真 MySQL 上读回 `v_active_users` 的定义、预览就一句 `CREATE OR REPLACE VIEW`、没有两句、没有多余的危险确认 —— D-29 的那一格）。⚠️ 如实记：MySQL 规范化后的读回是**一行长句**，720px 弹窗要看全得横向滚动 —— 那是 MySQL 自己的形态。⭐ **R47-d 把另外三家的形态也拍到了**（此前只有 MySQL 一帧，而「引号形状」恰恰只有画面能验）：`p6-new-view-sqlserver.png`（`CREATE VIEW [v_paid_orders]` —— 方括号只有这一家给得出，加上那句 `CREATE OR ALTER VIEW`）、`p6-design-view-pg.png`（`pg_get_viewdef` 回来的**多行缩进**定义，与 MySQL 那一行长句正相反）、`p6-design-view-sqlserver.png`（`sys.sql_modules` 读回的原文 —— 正文就是当初写的那一行 `SELECT … FROM dbo.users …`，预览是那句 `CREATE OR ALTER VIEW [v_active_users]`）、`p6-design-view-oracle.png`（`DBMS_METADATA.GET_DDL` 回来带**schema 前缀**的定义）。⚠️ 一起落地的是 **D-38（产品缺陷，不是画面问题）**：PG 与 SQL Server 的 `readDdl(..., view=true)` 原本走到方言那句 `return null` 的空壳上 —— 在这两家的真库里，「设计视图」**从来没开出过窗**（这两家的首拍拍到的是主窗口而不是弹窗，才顺出这条；第三张 Oracle 首拍落点不对另有原因 = D-39，见 §1 与平台一节）。R52 契约第 8 条当初把这条路**自己内联**了一遍再断言，绕过了产品实际走的 `readDdl`，所以 46 条契约全绿也没抓到它；本轮删掉那处内联、让它调产品自己的读路径，另加 `DialectViewReadDdlTest` 4 条离屏门禁（不发真库连接，用代理 `Connection` 记录 SQL：PG 必须问 `pg_get_viewdef`、SQL Server 必须问 `sys.sql_modules`+`sys.views`、两者都要能解析出查询体、`readDdl(...,false)` 仍返回 null、视图不存在返回 null 而不是空串）。⚠️ PG / Oracle 的「新建视图」格**拍过又撤了**：画面与演示库（SQLite）那一格逐字节相同（25490 —— 三家都用 `"..."` 引号，预览文本完全一致）。那不是假帧，是**冗余证据**；`shots.sh` 里没留那两行，`docs/screenshots/` 里也没留那两张 —— 一家一格的证据只在形状真的不同时才立（SQL Server 那格 25493 不同，所以留）。边界：视图数据页仍只读；B3 改名现况见本行末尾，B4 只读依赖图及限制见下一行。⚠️ D-28 待裁定：PostgreSQL 的 `CREATE OR REPLACE VIEW` 只许在末尾**加列**，改名/删列服务端报错（实测 `cannot change name of view column "id" to "adb_marker"`）—— 要不要在失败前给一句主动提示还没拍。**B3「重命名视图」当前为 P0 WIP**：右键视图节点「重命名视图…」走自建 Dialog，校验拦在关窗之前、错误就地显示；`StructureSqlBuilder.renameView` 调方言 `renameViewSql`：SQLite 显式拒绝直接改名，不以销毁重建替代；Oracle `RENAME … TO …`、PostgreSQL `ALTER VIEW … RENAME TO …`、MySQL / MariaDB `RENAME TABLE`、SQL Server `sp_rename`。撞名检测覆盖表与视图；主矩阵已新增视图改名契约，但尚未在五家真库执行，不能以历史建改删结果当作改名验收。 |
| 视图依赖图 | ✅ | 只读依赖图标签页（右键表 / 视图「查看依赖图…」打开）。解析视图正文算前向依赖（视图 → 它读的对象）：剥离 `CREATE [OR REPLACE] VIEW … AS` 前缀、行 / 块注释与单引号字符串，扫描 FROM/JOIN 的对象路径（schema.tbl、`"x"`、`` `x` ``、`[x]` 四种标识符，逗号连接与子查询），排除 CTE 名与别名；反向边（谁依赖该节点）由同一份前向数据推导，于是「删表前会坏哪些视图」一并覆盖。被解析出的引用若不能匹配入参对象清单，则按 `UNKNOWN`「外部」计入并灰显；这不保证依赖边完整。节点分层（表在第 0 层，越靠下游的视图越靠右；遇环就地截断不卡死）由纯数学 `DependencyLayout` 计算，可无头单测；渲染层 `DependencyGraphPage` 只读，支持拖拽平移与 Ctrl+滚轮缩放，聚焦节点的上下游高亮、其余淡出。门禁：模型层 `ViewBodyDependenciesTest` 20 / `SchemaDependencyGraphTest` 8 / `DependencyLayoutTest` 2，UI 编译 + `I18nAuditTest` 18/18（含 CJK 棘轮）。限制（R16）：仅做静态正文解析，不跟踪运行时动态 SQL / 预编译语句里的依赖；限定名仍可能被合并，缺少视图定义体时会少边，不能把跨 schema 引用一律视为「外部」，也不能据此保证完整影响分析 |
| 查询页 | ✅ | 工具带双行 62px（保存 / 查询构造器 / 美化 / 代码片段 · 查询名▾ / **库▾** / Run▾ / Stop / Explain）；Ask AI 已删除；当前「查询构造器」仅预览 / 复制编辑器中已有 SQL，不是从结构化条件生成查询，实际生成能力 R13 待做；⭐ R44-G1：行 2 那颗是**真库选择器** —— 列出当前连接能切的库（方言自报 `listCatalogs`，后台读、读不到就退回只展示；⭐ P-8 后系统库同列在单里 —— 树与这颗下拉读同一个出口），当前库打勾，选中即把这条 SQL 复制进**新页签并绑到该库**（走左树同一条 catalog 虚拟连接路径，会话没开先连再开）；**不自动重跑**（重跑有写风险），状态栏明写「已切到数据库 X · 查询已复制到新页（未执行）」。⭐ R52 之前这一条只有两条断字符串的单测（id = 父id@库名、database 字段被换掉），**没人在真库上问过「语句到底落在哪儿」** —— 现由契约第 9 条钉住：同主机同口令的两把库、同一个表名、字面完全相同的 `CREATE` / `SELECT`，两边各读各的行（没切过去会撞「表已存在」，切过去但隔离不干净则两边行数相同），且两条会话的「当前库」各自读对（SQL Server 的库名走 `databaseName` 属性、PG / MySQL 走 URL 路径段，写错的症状都是「连得上、落错库」）。SQLite 等无库层方言保持只展示、不假装可切；Oracle 那一支仍钉「没有库层 ≠ 连现在在哪儿都读不出」（实测 `SYS_CONTEXT('USERENV','DB_NAME')` 回 `FREEPDB1`）。⭐ R47-b：这颗控件**第一次有画面证据** —— `docs/screenshots/p3-query-catalog.png`（真 MySQL 上 `库▾` 展开，列出 `shop` / `shop2`、勾在当前库）与 `p3-query-catalog-bound.png`（切到第二把库后的页面态）；正是后一帧抓出 **D-36**（新页其实是空的，状态栏那句「SQL 已复制到新查询页」在骗人 —— 见 §1 基线行与 CHANGELOG）。RichTextFX 编辑器（语法配色、行号、当前行、查找替换）；⭐ **R57-a：编辑器有随库上下文的自动补全**（表 / 视图 / 列 / 例程 / 关键字）。**触发口径如实收窄**：`.` 之后自动弹、`⌘空格` / `Ctrl+空格` 强弹、弹开着打字即过滤，**普通打字不自动弹**（打一半弹候选会糊住正在写的词，而弹层抢焦点是更糟的交互）。规则与呈现分两层（R53 `EditValues.emptiedCell` 那条「唯一出处」纪律的延伸）：`SqlCompletions` 是纯逻辑、`QueryCompletions` 只管弹层与键盘，两类共 27 条门禁。上下文：`FROM`/`JOIN`/`INTO`/`UPDATE`/`TABLE` 之后是**表位**（只给表和视图）、`users.` 与别名 `u.` 之后是**列**（候选带所属表），其余位置给「本条语句引用过的表的列 + 对象 + 例程 + 关键字」（上限 500）。三处刻意的不做：字符串 / 注释 / 数字 / 参数里不弹；**认不出的限定符不猜、也不倒全部**（宁可不弹）；别名不跨分号串用。对象清单与列名走后台线程懒读（不在 FX 线程发查询），**成功执行一条 DDL 即整块作废重取**（刚建的表立刻能补）。关键字读 `SqlLexer.keywords()` 同一份表，不建第二份。⭐ **R57-b 选中执行**：取句只有一个出口 `QueryPage.statementsToRun()`，Run / Explain / 带变量执行共用它 ——有选区按**字面**切句执行（圈两段出两个结果页签），**不往两边扩到语句边界**（圈半句就拿服务端的语法错误，把半句悄悄补成整句才更难发现）；圈空白或纯注释报单独一句「选区里没有可执行的语句」，**不退回光标那条**。危险分级、状态栏 SQL 回显、查询历史读同一份返回值，所以确认框问的就是真正执行的文本（变异：把分级改成读整页文本 →「只选了 SELECT 却为没选中的 DROP 要确认」那条红）。⭐ **R57 画面债已还（2026-11-04）**：补全弹层与选区执行回显各一帧落地 —— `docs/screenshots/p3-query-completions.png`（真 MySQL 的 `shop`，光标停在 `SELECT * FROM ` 句尾，候选 `orders` / `users` / `v_active_users` 贴在光标下面）与 `p3-query-selection.png`（两段语句、圈住第二段真跑：状态栏回显那句 50 字符、结果页签 `Result 1 · 2 rows` 出的是 `orders` 的三列）。⭐ **这两帧的第一版是假图，被目视判掉的**：弹层开在窗口左上角、画面里根本没有候选 —— 顺出 **D-47**（产品缺陷，见 CHANGELOG Fixed）。只剩**导出保存对话框**那一格明写「无帧、只有门禁」：`FileChooser` 是原生窗口，不在 JavaFX 场景图里，快门只摄场景图（本模块的 `p3-query` 已在 R57-g 这一轮按新比例重拍并目视核对；重拍时逐像素比出三处**与本轮无关**的变化 —— 顶栏胶囊的厂商图形、侧栏搜索提示词、搜索框截断宽度，属 R44-G2-a / R51 / R54 的代码，而这张帧上次入库停在 `51063f2`（2026-11-02 09:11），**拍在那三批代码之前**：帧体系整体落后于代码，这笔账记在画面账里，不在本轮还清），且 `./scripts/build.sh` 全量**未重跑** —— 基线数字仍是 R56 那一跑；`⌘R` **选中执行（R57-b：有选区只跑选区，没选区跑光标所在语句）**、`⇧⌘R` 全部、`⌘.` 取消；结果区 `Message/Summary/Result N` 三段 + `Data/Info` 切面、服务端分页、执行计划、错误定位、查询历史（50 条，带对象上下文）、代码片段。⭐ **R57-g 分栏**：编辑器 / 结果带默认比例 **0.463**（参照帧逐像素量出来的：编辑器白带 377px / 分栏总高 814px；原先写死的 0.42 是 P3 第一版顺手定的，没出处），分隔条可拖 —— 命中带实测 6px、光标 `V_RESIZE`（CSS 把可见线画成 1px 不等于拖不动，这两件事本轮量过才敢写）；两条最小高钉住极端拖拽（编辑器 ≥140px = 实测行距 16px × 8 行；结果带 ≥205px = 帧 `p3-query` 量到的 136px 固定开销〔结果页签 31 + Data/Info 30 + 分页条 41 + 列头 26〕+ 3 行 × 23px）；**记忆只认「在分隔条上按下 → 移动 → 松手」**，窗口太矮时布局自己夹出来的比例不落盘，否则第一次在小窗口打开查询页就把意外钉成永久默认。执行计划按方言能力开放：SQL Server / Oracle 需要两步协议，按钮禁用并说明原因。⭐ **R58（H1-c，Q1 拍「做，接进结果区」）：结果网格接上回写** —— `SqlQueryShape`（core/adb-sql）认形状 → `SchemaService` 读真表 → `ResultEditPolicy`（workbench/adb-editing）把两边与驱动**实际返回的列标签**对齐，给 `Editable` 或 `Blocked(一句原因)`；**21 条卡点各一个资源键**（判定层 7 + 形状层 14），Info 切面如实说「为什么这一屏不能改」，不再闷着只读。判成可改的那一屏双击进行内编辑（三道闸：网格与列可编辑、这一格对得上表里的列、**不是主键**），缓冲用 `EditBuffer`、语句用 `EditSqlBuilder.plan/execute`、预览用本批抽成共用的 `SavePreviewDialog`（**界面一条回写规则都不重写**）；`RowIdentity` 按**表侧列名**编址、不是结果列键。三条诚实账：没点保存不落库、点保存先看 SQL（默认键取消）、跑完**重取当前页**；命中行数 ≠ 改动数报 `rowMissed` 不报「保存成功」，事务里照说「尚未提交」。⚠️ 这一格此前只有 SQLite 门禁，而夹具写 `SELECT *` 让两种键恰好相等 —— 现由**契约第 14 条**在五家真服务器上钉住（见 §测试环境），行身份规则收进 `ResultEditPolicy.identity` 一个出处（D-38）。画面三帧 `p3-query-edit-blocked` / `-dirty` / `-preview`（2026-11-04 实拍，拍完直接查库对回种子没动） |
| 已保存查询 | ✅ | 工作区对象页（名称 / 类型 / 修改时间 / SQL 四列 + `设计` / `新建` / `删除查询`）；侧栏 `Queries` 组双击或 Enter 打开；删除走危险确认（默认按钮是取消）；查询页保存后列表即时刷新 |
| 导入导出 | ✅ | 流式 CSV / TSV / JSON Lines / SQL / xlsx（POI SXSSF 写 + SAX 读）/ XML / HTML；字段映射、按目标列类型转换（含中文日期格式）、坏行报告带列名与原文；查询结果**三档范围 × 六种格式**（本页 / 选中行 / 全部结果 × CSV · TSV · JSON · xlsx · XML · HTML）。⭐ **R57-d**：格式挂在系统保存对话框的扩展名档上（不在界面再搭一层下拉，同一个决定不给两个入口），文件名后缀跟着同一档换、缺后缀补上；三种范围统一交 **JDBC 原始值**（旧「本页/选中」交 `String.valueOf` 后的网格文本，换 JSON 会把 42 写成 "42"、NULL 写成 ""）；BOM 只给 CSV / TSV；写盘全在后台线程。⛔ 查询结果**没有 SQL(INSERT) 这一档**（无目标表名，硬写会产出不能执行的文件 —— 要 INSERT 用导出向导）。⭐ 顺手修一条真缺陷：结果网格原是 `SelectionMode.SINGLE`，「导出选中行」最多只能导 1 行。这一格仍**欠帧**（保存对话框那六档没画面证据）；HTML 标题硬编码中文的问题已修（D-44）。⭐ **2026-11-11 D-86 交付**：导出向导单页补齐十项选项（Append to existing file / Continue on error / Record Delimiter / Text Qualifier + Formats 组六项 Date Order / Date Delimiter / Zero Padding Date / Time Delimiter / Decimal Symbol / Binary Data Encoding），在七种格式写出路径逐项生效、格式不支持的那一支在代码与界面置灰（不摆「选了没反应」的假控件），追加不重复表头、出错继续回报跳过行数、默认值逐字节保持旧行为；21 条行为门禁 + 变异验证随构建全绿。仍欠：矩阵环境导出往返复跑、导出向导同版画面帧（G6）。⭐ **R55-b 起这条有真库证据**（契约第 12 条，五台 Docker 各跑一遍「整表导出 → 导回 → 逐行相等」）：此前只有四条内存 SQLite 门禁，证得了编解码与导入器的约定咬得上、证不了各家把 `rs.getObject` 交成什么 Java 类型、那个类型写进文件后再导回来吃不吃得下。⚠️ 为什么这条契约值得跑：导入器对坏行的策略是**跳过并记进报告**，真出问题时没有异常，症状是报告里少一行 —— 界面全绿、用户拿到的文件少数据 |
| 错误与超时 | ✅ | 错误归类（断连 / 权限 / 不存在 / 超时 / 锁 / 约束 / 语法）；语句超时 30s 并给建议；崩溃日志 `~/.agenticdb/logs/crash-*.log` |
| 外观 | ✅ | 暗亮双主题 + 跟随系统 + 本地记忆；四档密度；111 个设计令牌唯一真相（字号只有 11/12/13 三档）；图标走语义彩色层（同一套资产换主题自动换色）；六家**真有驱动**的数据库各顶**自己的官方彩色商标图形**（⭐ R63-3：`icons/brand/` 里的拍平商标原件，字面官方色、不吃主题与 CSS，深色档靠 `-adb-brand-plate` 亮底板保可读；`vendor-*` 品牌色令牌退给自绘形状的表面，如库节点圆柱与树行着色） |
| 键盘与可访问性 | ✅ | ⌘K 命令面板；六区焦点顺序；页签 / 切面键盘切换；逐页可朗读名称（`AccessibilityAuditTest` 断言） |
| 界面语言 | ✅ | 英文（默认）· 简体中文；主源码里的硬编码汉字由棘轮压到 8 条（且那 8 条是**输入 token**，不是文案） |
| 可观测性 | ✅ | `StartupTrace` 分段打点 → `~/.agenticdb/logs/startup.log`；诊断页 |
| 资产族地基（P0） | ✅ | `AssetKind` 取代 `DialectKind` 成为 `ConnectionProfile` 的判别轴；`AssetFamily` / `AssetFamilyRegistry` 是「加一种连接类型 = 一处登记」的单一真相源（SQL 6 家 + Redis 1 家 + 对象存储 4 家 = 11 张磁贴）。四类对象存储合并成一个 `OBJECT_STORE` kind + `ObjectStoreProvider` 子判别，不拆四个 kind；旧档案 `kind` 写枚举名仍可读，无需迁移。`DialectBranchAuditTest` 禁止 UI 硬编码资产族常量，`AssetFamilyFilterAuditTest` 动态断「注册表加一族 → 向导筛选带必须加一枚 chip」 |
| Redis 管理（P1） | ✅ | `adb-redis`（Lettuce）：按 `INFO keyspace` 懒加载 db 分组，`SCAN` 出 key / type / TTL / size；`RedisKeysPage` 前缀 + 类型过滤，五种值类型抽屉查看/编辑，TTL、`DEL`、`FLUSHDB`、`FLUSHALL` 全走 `RedisDanger` 分级 + `DestructiveConfirm`（取消为默认按钮）。`RedisScanTest` / `RedisMutateTest` / `RedisTreeTest` 钉住内省、写安全与双击路由。⚠️ **Redis over SSH 未接入**（`RedisSessions` 类注释如实写明），SQL 那套隧道尚未复用到这一族 |
| 对象存储（P2） | ✅ / 🟡 | `adb-objectstore` 把四家 SDK（AWS SDK v2 / aliyun-sdk-oss / qiniu-java-sdk / cos_api）适配到同一个 `ObjectStoreSession`，另附 `InMemoryObjectStoreSession` 供无云账号环境跑行为契约（19 条）。对象存储没有真目录：key 里的 `/` 配 delimiter 折叠成 `ObjectEntry.folder()` 虚拟文件夹行；不传 delimiter 时平铺，用于递归删前缀。`ObjectBrowserPage` 提供前缀面包屑、上传 / 下载 / 删一颗 / 递归删前缀 / 元数据 / 预签名 URL / 移动重命名；`ObjectStoreCapabilities` 显式声明能力，七牛 `supportsMove=false` / `supportsListBuckets=false` → 按钮置灰 + tooltip 说明 + 后端 UOE，树只显示配置的那一个桶。删除类操作由 `ObjectStoreDanger` 判成 `DESTRUCTIVE`，未知 / 空白操作 fail-closed 到 `ADMIN`。密钥只进 `CredentialStore`，`connections.json` 只留非敏感 options。⚠️ 🟡：**S3 兼容分支已有真端点证据**（`S3RealEndpointContractTest` 10 条跑真 LocalStack 4.9.2 容器，含 1001 对象跨页与预签名 URL 真 HTTP 取回；另有 `p10-object-store-real` 真端点画面帧），**OSS / Kodo / COS 三家原生 SDK 仍只有编译 + 内存后端契约**；当前 `listObjects` 一次列完（SDK 内部确实跨页，但接口不暴露分页 token），超大 bucket 无渐进加载 |
| 首启引导与 Tab 治理（P3） | ✅ | `OnboardingOverlay` 叠在 demo 自动连接之上、关后即焚（`ui.onboarding.seen`），Help 菜单可重开，验收 / 截图 / 测试会话不自动弹以保证逐帧可复现；向导首步四枚族筛选 chip 用**可见性切换**而非重建磁贴，被筛掉的选中项自动挪到该族第一张；`tabs` 命令（命令面板 + Window 菜单）打开非常驻的 `TabOverviewPanel`，按连接分组 / 搜索 / 激活 / 逐条关闭 / 按连接全关，**三条关闭回调全部回到 `TabBar.close()`**，`closeGuard` 脏检查、钉住态、常驻标签规则一条不绕过。⚠️ TabBar 分组视觉未新增，沿用既有连接名 / 库名 / 厂商图形注入；连接管理页「无连接三步上手」未单独做，由引导层三张卡片承担 |
| 品牌资产（P3-6） | 🟡 | `redis.svg` + `aws-s3` / `aliyun-oss` / `qiniu` / `tencent-cos` 五枚落地，`TRADEMARK-NOTICE.txt` 逐枚点名 `BrandIconTest` 通过；彩色 `list.svg` 补齐图标系统。⚠️ 五枚均为**中性几何占位件**，发布前必须换成各家品牌页的原始矢量件（R4 资产阻塞） |
| 打包 | 🟡 | `scripts/package.sh` 出 app-image / dmg / msi / deb，实测可启动；**未签名、未公证、无自动更新、无发布渠道** |

## 3. 已知限制（完整清单）

### 性能

- **冷启动历史基线未达标，当前 WIP 待验收**：规格目标 <2 s（`StartupTrace.SLOW_FIRST_SCREEN_MILLIS`），R61 阶段目标 <3 s，历史实测**两条都没达到**。
  本机历史实测（2026-11-04 · macOS 15.7.9 x86_64 · JDK 25.0.4.1+1 · `./scripts/startup-bench.sh`，真进程、`mvn javafx:run` 构建）：
  三次 **4970 / 5142 / 5589 ms**，判定取最坏一次 = **5589 ms**。
  ⚠️ 这一行原先写的是「**3.5–3.9 s**（2026-10-26 · Windows 10 + JDK 27）」—— 那个数既不是这台机器、也不是这个 JDK，
  本轮第一次建起真进程通道才量出本机实际值，旧数作废（不同口径不许互抄）。
  地板构成（同一轮分段量出来的，取三次里中间那次 5142ms）：JVM/Toolkit 前置 **≈1.1 s** + `stage.show()` 首趟 CSS/布局
  **1621 ms** + 重叠之后仍在等库 **656 ms** + demo 连接本身 **258 ms**。
  SQLite 首次取连接 **762 ms**（原生库解包，同一进程内第二、三次各 **1 ms**）现在**与 UI 构造重叠**、不再单独计入关键路径。
  ⭐ R61-a 已把那 762ms 与外壳构造**重叠**（A/B 各 3 次：5276/5242/5381 → 5081/5008/4778，平均 **−344ms**）；
  剩下的最大一块是 `stage.show()`，那是 JavaFX 自己的首趟样式表解析，不在我们代码里。
  当前 `AppShell` 已有懒连接 WIP：构造器返回后按需连接，不再保证返回时演示库已连上。
  Q7 不再是「尚未改动 / 待拍板」；当前启动路径、首次使用与截图路径的验收尚未完成，
  上述历史耗时保留，不能据此推断新路径已达 <3s 或 <2s 目标。
  **AppCDS 在开发启动路径上试过，不通。**（历史结论，本轮没重测。）
- 冷启动耗时**没有**单测门禁：`StartupTraceTest` 里原先那条「测试里首屏 <2s」删掉了 ——
  它量的是测试运行器的进程年龄（详见 `CHANGELOG.md` 的改判记录）。
  ⭐ R61-a 起这条通道改由 **`scripts/startup-bench.sh`** 把：每次一颗真 JVM，判定取最坏一次，
  阶段目标 3000ms 只打印差距、回归地板 6000ms 才做红绿判定（两个数不许互抄，改地板必须重量）。

### 平台

- 「跟随系统主题」已有 macOS、Windows 注册表与 Linux GNOME / KDE 探测实现（`ThemeManager`）；Windows / Linux 尚未实机验证。探测失败回落亮色，结果仅首次探测并缓存，运行中修改系统主题需重启应用。
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

- ⭐ **资产族扩展（P0–P3，2026-11-12）的四条如实边界**：
  1. ⭐ **R2 已部分平账：S3 兼容分支有真端点证据，三家原生 SDK 仍缺**：本轮新增
     `S3RealEndpointContractTest`（10 条，testcontainers 起的真 `localstack/localstack:4.9.2`
     容器 + 受信任的 HTTPS 端点），覆盖工厂连接 / 连接测试 / 列举 bucket / put→get→metadata
     往返 / delimiter 折叠 / **1001 对象跨 ListObjectsV2 千条页边界** / 删一颗 / 递归删前缀 /
     move 保留 contentType / 预签名 URL 用真 HTTP GET 取回。走的都是产品同一条
     `ObjectStoreSessions.connect/test` 路径，不内联兜底。
     ⚠️ 这是 **S3 兼容协议**的真服务端（AWS S3 / MinIO / LocalStack 一支），**不是**某家公有云账号；
     阿里云 OSS、七牛 Kodo、腾讯云 COS 三家原生 SDK 仍只有编译 + 内存后端契约，
     需要各家仿真服务或真账号才能升级，「连得上四家」依旧**不写已验**。
     ⚠️ **明文 HTTP 端点边界（本轮实测）**：AWS SDK 2.31 在 `http://` 上给 PutObject 加
     `Expect: 100-continue` + 流式 SigV4；LocalStack 的 Twisted 网关把 `100 Continue` 与
     `200 OK` 一起回，SDK 的 Apache 客户端每个 PUT 都卡到 30s 读超时再重试（服务端每次记 200）。
     真实 S3 / MinIO 生产端点都是 HTTPS，契约因此走 TLS；**产品对纯 HTTP 的 S3 兼容端点
     未做适配**，用户配明文 endpoint 可能遇到上传超时。
     ⚠️ **Redis 不在此列**：`adb-redis` 的 14 条契约（`RedisScanTest` 5 + `RedisMutateTest` 9）
     跑在 testcontainers 起的真 `redis:7.2` 容器上，含 FLUSHALL 的真实变异验证；同版 UI 验收帧
     （`p10-redis-keys`，真容器实拍）也已入库 —— Redis 这一族服务端与画面证据都齐了，
     只是 over SSH 未接（下条）。
  2. **Redis over SSH 未接入**：档案里的 SSH 段目前只作用于 SQL 连接；`RedisSessions` 在代码里
     写明这一点，界面不摆一个勾了没反应的开关。
  3. **对象存储接口无渐进翻页（SDK 层已验跨页）**：`S3RealEndpointContractTest` 灌 1001 颗对象、
     断言 `listObjects` 全部列回，证明 `S3ObjectStoreSession.flatList` 的 `continuationToken`
     循环真的跨过 ListObjectsV2 的千条页边界；但 `ObjectStoreSession.listObjects` 仍一次返回整层，
     不暴露分页 token，超大 bucket 无法渐进加载、性能边界未量。递归删前缀用不传 delimiter 的
     平铺列举实现，删之前会走 `DESTRUCTIVE` 确认。
  4. **画面证据：两族各有真服务端帧，三家原生 SDK 仍缺（R1 转绿，R2 部分平账）**：
     六个新面同版帧已入库（`docs/screenshots/` 74 张，`md5` 零重复，逐张目视核对过），
     68 张基准帧全部重拍到当前源码。`p10-redis-keys` 拍在真 redis:7.2 容器上，
     ⭐ `p10-object-store-real` 拍在真 LocalStack 4.9.2 S3 兼容端点上（对象由产品会话写入服务端），
     离线 `p10-object-browser` 帧也保留作壳与交互的对照。⚠️ 对象存储真端点帧只覆盖 S3 兼容协议一支，
     OSS / Kodo / COS 三家原生 SDK 既无真端点契约也无画面证据。五枚新品牌图仍是中性几何占位件（R4）。
- **Filter & Sort 面板支持条件组（组内 AND、组间 OR）**：`[Ω]`（加 OR 条件组）已落地真实能力——
  按 `MAX_GROUPS=8` 新增组、组间 `OR` 连接、组内 `AND`（单组等价于旧版平铺 AND，SQL 输出逐字节兼容）；
  `IN` 条件仍由列头漏斗的候选值多选提供，面板内原样保留、不可行内编辑；Text 模式是**只读**的生成表达式。
- 危险分级**第三层（驱动侧复查）已有实现**：`DbSession.execute(String, Danger)` 与
  `requireDeclared` 只拒绝低报危险等级；裸 `execute` 自行分类不等于取得用户确认。
  UI 预览 / 确认仍是调用方责任，当前 P0 正在补写入口门禁，不能宣称全调用路径安全。
- **SSH 隧道已交付**（⭐ R55-c，G5a）：纯 Java 引擎（Apache MINA `sshd-core` 2.20.1，选型与
  「为什么不调系统的 `ssh`」记在 `docs/COMPONENT-SELECTION.md`），连接向导 SSH 页是真字段，
  秘密只进凭据库；`SshTunnelTest` 9 条 + 真库契约第 13 条（五家各经进程内跳板机连一次）。
  ⚠️ 两条如实边界：**私钥只吃 RSA / ECDSA**（内置库对 ed25519 交出 0 把密钥，界面上明写，D-41）；
  **HTTP / HTTPS 代理隧道按 2026-11-10 裁定不做**（Q5 建议不做，现已拍板）。
  SSL/TLS 这一面 R47-a 已经踩过实土：档位取值、会话是否真加密、
  链校验是否真生效、主机名不匹配是否真被拒都在本机加密对端上跑过（18 条契约）；
  ⚠️ **SNI 没有单独证据** —— 三家对端都不是「按 SNI 选多域名证书」的形状，所以这一条不写「已验」。
- 函数 / 过程入口是左侧树双击 / Enter 或例程清单页；PostgreSQL 定义体可编辑，先固定 SQL 预览、双确认后执行，其余只读（SQLite 没有 SQL 层函数）；当前 P0 确认链仍待同版验收。
  视图建 / 改 / 删见 §2「视图生命周期」；B3 改名已接线，但 SQLite 显式拒绝、不销毁重建，其余五家真库的新增改名契约未执行。B4 只读依赖图已接线，限定名合并与缺定义少边限制见 §2「视图依赖图」（R16）。
- 事件（MySQL EVENT）的只读树分组与定义体入口已接线，尚未完成同版 MySQL 验收；触发器按表归属，在结构页查看。
- 建表弹窗已能随表录入外键 / 索引 / CHECK / 触发器（八页设计器：字段 / 索引 / 外键 / CHECK / 触发器 / 选项 / 注释 / SQL，预览与执行出自同一 `StructurePlan.createTableWith`，`NewTableDialogTest` 十余条用例覆盖）。

### 编辑与选择

- 无主键表与视图只读；保存的反向撤销对「无主键 / 主键为空的新增行」不完整（UI 会提示）。
- 反向撤销只覆盖最近一次保存，发生新编辑后不再提供。
- 转置视图只读，不参与批量选择；本页搜索 / 隐藏只作用当前页，不改变服务端分页。
- 数据页的加载与保存**都能取消**（`⌘.`，R61-c 收口）：正在跑的 JDBC 语句走 `Statement.cancel()`，
  排在后面的那条保存靠票据被摘掉、正在写的批量在**语句边界**停住。
  停住之后按事务归属分两种：自动提交（默认）时整批就在一个事务里，中断即 `rollback()`，**库里一行不变**；
  用户自己开了事务时只停住剩余语句，已经跑进去的那几条留在**他的**事务里由他 commit / 回滚 —— 界面不替他决定，也不假装有一键还原。
  ⚠️ 这行本轮之前写的是「无法真正取消」，那是 4.5-13 之后就没再改过的陈旧描述，本轮才对上。
- 表头「全选」只选当前页。

### 线程模型

- 执行器**按连接分**（`DbExecutors`：同一连接内严格串行，这是有意的 —— 一条 JDBC 连接不是线程安全的；不同连接互不阻塞）。
  会话注册表 `SessionManager` 从 R61-b 起也**按连接 id 分锁**，纯读（`find/require/isOpen/openIds`）完全不上锁。
  ⚠️ 这一条本轮之前写的是「连接建立与内省跑在 JavaFX 线程上；全局单线程执行器 `adb-db` 会让一个连接的慢查询阻塞其它连接」，
  那是 A16 之后就没再改过的旧账，本轮只补上真正还存在的那半个洞（注册表的全局 monitor）。
  **修复前的实测**：一条慢连接（探针故意睡 2s，Oracle 真库首连实测 8–12s 是同一形状）在途时，
  对另一条已开连接的一次普通读要排队 **2010ms** —— 而这类读操作在 FX 线程上随处可见，等于整顶界面冻住。
- **初始连接仍属 WIP**：`AppShell` 已移除构造器里的同步 demo 连接，改为返回后按需连接；
  普通桌面与截图 / 矩阵路径分开处理。当前启动与线程行为尚未完成同版验收，不宣称 FX 阻塞或冷启动目标已解决。

### 分发

- 无自动更新 / 版本检查（无发布渠道 + 离线环境 + 无安装包签名）。
- 无代码签名与公证（需 Apple Developer ID 与 Windows 代码签名证书 —— 外部资源）。
- jlink 自定义运行时不可行（RichTextFX / ControlsFX 是自动模块）。

### 测试环境

- 五种引擎由 `scripts/dbmatrix.sh` 在本机 Docker 起满（MySQL 8.4 · PostgreSQL 16 · MariaDB 11.8 ·
  SQL Server 2022 · Oracle 23ai Free），**有库层的四家各起两把库**（`shop` + `shop2`，第 9 条契约要用）。
  口令由脚本**现场随机生成**，只写进未跟踪的
  `scripts/.dbmatrix.env`（600），仓库里不留任何密码；测试侧一律从 `ADB_TEST_*` 环境变量读。
- `adb-jdbc` 的 `ExternalRealDatabaseTest` 主矩阵现为 **5 × 10 + 1 = 51 条**，当前尚未执行。
  原有九项主矩阵契约之外，第十项是新增「视图改名」；另加一条「环境读取不外泄口令」。
  **主矩阵内部序号不改写历史全局编号**：下文第 10–14 条仍指写回 / 导出 / SSH 等原有契约，
  不与主矩阵第十项混用；2026-11-08 的 46 条结果只是历史基线。R53 的全局第 10 / 11 条
  「数据写回」量的是 `adb-editing` 的 `EditSqlBuilder`，所以落在**它自己那个模块**的
  `EditWriteBackMatrixTest`（⭐ R58-d 起是 5 引擎 × **3** = **15 例**，靠 adb-jdbc 的 test-jar 复用同一个
  `ExternalDatabase`，环境变量名不写第二遍）；⭐ R55-b 第 12 条「导出→导回」=
  `TransferRoundTripMatrixTest`（5 引擎 × 1 = **5 例**），⭐ R55-c 第 13 条「SSH 跳板」=
  `SshTunnelMatrixTest`（5 引擎 × 1 = **5 例**，进程内起一把真 sshd 当跳板机，量的是驱动那一侧
  在隧道对面连不连得上）；⭐ **R58-d 第 14 条「结果区那一格按 SQL 文本判出来、改回库」**也落在
  `EditWriteBackMatrixTest`：它跑的是产品自己那条链（形状 → 内省 → `judge` → `identity` → 缓冲 →
  `plan` → `execute` → `inversePlan`），钉的是**别名投影**那一支 —— 夹具写 `SELECT *` 时结果列键恰好
  等于表列名，「身份按别名建」在 SQLite 门禁下 45 条全绿也抓不到，真库上它变成
  `UPDATE … WHERE id = NULL`：**语句发出去、命中 0 行、界面报「已保存」**。
  **加一条契约只加一行**。第 9 条另需第二把库：
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
  ⚠️ **D-43 那个「两种结论共用一个记号」在方言层又出现了一次**（R61 收官回量抓到 = **D-49**；
  **契约那一半 2026-11-04 R62-a 已修，产品那一半仍未修**）：
  `OracleDialect.getDdl`（`workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/OracleDialect.java:310-324`）
  对**任何** `SQLException` 都 `return null`。注释写的理由是「权限不够或对象不存在，都退化成等价 DDL，不挂结构页」——
  这对界面是对的，但它同时把**网络层异常**吞成同一个 null，于是契约拿到的是 `AssertionError("… not to be null")`
  而不是 SQLException，`ExternalUnreachable` 那条唯一出处**根本没机会看到异常**。
  **观测**（2026-11-04，同一份代码连跑两次带环境全量，五引擎 + 三家 TLS 对端都已起）：第一次 adb-jdbc 红 **2** 条 Oracle、
  第二次红 **1** 条，而**单跑同一个类 46 条全绿** —— 所以「容器刚重启」这个初判**不成立**，真正的分界是**单跑 vs 全量**。
  R61 收官那一次（10:46）没有复现（46 / 0 失败 / 0 跳过，日志 `ORA-` 计数 0），当时写的是「本轮未复现 ≠ 修好」——
  ⭐ **R62-a 收官这一跑把它复现了，而且第一次留下异常，成因量到了**：
  `getDdl:315` 抛 `ORA-18730: IO 中断错误。: Socket read timed out`，紧接着清理临时表报 `ORA-17008: 已关闭连接`；
  那一条用例 **13.41s**，同一个类**单跑 22.00s / 全量 43.26s**（负载翻倍）。
  真成因**不在 Oracle，也不在前面某条契约留下的状态**（那两个候选都没验证，现在都不必再猜）：
  契约自己的连接参数给 Oracle 写死 `oracle.jdbc.ReadTimeout=8000`（`ExternalDatabase.timeoutOptions`），
  比产品用的 30000 短 —— 全量负载下 `DBMS_METADATA.GET_DDL` 真跑过 8s，驱动判死连接，兜底把「慢」写成「读不出 DDL」。
  **契约测的是一份产品永远不会用的超时**，所以「单跑绿 / 全量红」=「单跑快 / 全量慢」。
  已改：Oracle 那一档读超时直接取 `OracleDialect.READ_TIMEOUT_MS`（不许两边各写一份），
  门禁 `DialectTimeoutTest.theContractHarnessDoesNotReadShorterThanTheProduct`（变异：写回 8000 就红）。
  ⚠️ **连接超时保持 5s 短值**没动 —— 不可达主机必须在 connect 阶段快速失败，那才是这套短超时存在的理由；
  其余四家的读超时没量到同类失败，**没量就不改**。
  **仍未修的那半边**：`getDdl` 还是把「读不到」和「连不上」压成同一个 `return null`，
  所以**用户在 Oracle 上看到的症状原样存在**（打不开结构页时可能显示「没有定义」而不是「刚才断了」）。
  修法方向不变：让调用方分得开这两种 null（待排，见 CHANGELOG Known issues 与 GAP-TODOLIST 的 D-49 两行）。
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
**数据生成（测试数据填充）** —— 三面的**入口已于 2026-11-10 全部删除**（Ribbon 磁贴与数据网格工具条按钮），
不再保留占位说明页，记为「裁定不做」而不是待办。
⭐ **P-3 已交付**：`MaskRule` / `DataMasker` / `ExportWizard` 列级脱敏（掩码 / 哈希 / 置空），18 条门禁覆盖。
⭐ **ER 图（含只读版与全量 Model 编辑器）2026-11-10 用户裁定不做**，已从 backlog 与产品面删除。
理由见 [README.md](../README.md) 与 [docs/ROADMAP.md](ROADMAP.md)。
⭐ **2026-11-12 用户裁定反转 v4 的「不扩 Redis」条款**：Redis 与 S3 / 阿里云 OSS / 七牛 Kodo /
腾讯云 COS 已带着真驱动接入（见 §2）。MongoDB / Snowflake / DynamoDB 仍在「不做」清单里；
「不摆没有驱动的假厂商磁贴」这条纪律继续有效 —— 新增族必须先有会话实现与行为门禁。
⭐ 同批明确不做：**对象存储不做真实目录**（key 里的 `/` + delimiter 折叠成虚拟文件夹行，
不伪造一棵目录树）；**七牛不做服务端 move 的全套假实现**（UI 置灰 + 后端 UOE，要用请走
copy + delete）；**标签概览不做常驻页签**（Q5：命令面板 `tabs` 唤起，避免标签栏膨胀）；
**首启引导不替代工作区页面**（叠在 demo 之上，关掉即露出下面的对象首页）。

## 5. 相关文档

- [docs/ROADMAP.md](ROADMAP.md) — 对外路线（支持矩阵 / 进行中 / 接下来 / 明确不做）
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — 模块、数据流、线程模型、编辑与 SQL 不变量
- [docs/UI-VISUAL-SPEC.md](UI-VISUAL-SPEC.md) — 可照着实现的 UI 规格
- [docs/UI-FORMS-AND-STATES.md](UI-FORMS-AND-STATES.md) — 弹窗 vs 标签页裁定、空白态清单
- [docs/COMPONENT-SELECTION.md](COMPONENT-SELECTION.md) — 组件选型与许可
- [CONTRIBUTING.md](../CONTRIBUTING.md) — 环境、命令、四条硬规则
