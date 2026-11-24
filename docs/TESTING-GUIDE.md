# TESTING-GUIDE — AIDB 测试体系与如何运行

> 本项目把“每条已完成都要有断言，改绿不算修好”作为门禁文化。测试分四层：**单元 / 数据（真库契约） / UI（TestFX + Monocle 无头） / 样式与图标（TokenParity / Contrast / IconAudit）**。统一入口：`./scripts/build.sh`（=`./mvnw -B clean verify` + `java tools/TestCount.java`）。

> **JDK 要求**：pom.xml enforcer 锁定 `[25,28)`。系统默认 JDK 若不是 25–27，`scripts/_env.sh` 会自动探测 `$HOME/.workbuddy/jdk/*` 或系统注册的 JDK；直接运行 `./mvnw` 时需自行 `export JAVA_HOME` 指向 JDK 25–27。

---

## 1. 一句话上手

```bash
# 完整门禁：编译 + 全部测试 + 用例计数（CI 跑的就是这条）
./scripts/build.sh

# 只编译安装，不跑测试（快，用于 dev.sh / shots.sh 前置）
./scripts/build.sh --no-tests

# 增量跑测试（不 clean，日常开发）
./scripts/build.sh --quick

# 只跑某个模块
./mvnw -B -pl ui/adb-ui-shell test

# 排除 adb-redis（本机无 Docker 时，Redis 测试需 Testcontainers 会失败，见 §6）：
export JAVA_HOME="$HOME/.workbuddy/jdk/jdk-25.0.5+8/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B clean verify -pl '!workbench/adb-redis'
```

> `./scripts/build.sh` 全量在本机约需 30–35 分钟（JDK 25，macOS），CI 上 90 分钟超时。不要与 `startup-bench.sh` 或第二个 Maven 并发，两者会互相 `clean target/` 造成假编译错。

---

## 2. 四层测试图谱

| 层 | 覆盖内容 | 框架 | 示例类 |
|---|---|---|---|
| **单元** | `EditBuffer`、`EditSqlBuilder`、`CellRenderer`、`CsvExporter`、`SqlLexer`、`SqlSplitter`、`SqlFormatter`、`StructureSqlBuilder`、`ConnectionStore` | JUnit 5 + AssertJ | `EditSqlBuilderTest`、`SqlSplitterTest` |
| **数据** | 演示库固定种子；分页/排序/筛选 SQL 断言；保存/反向撤销真实 SQLite；结构变更真实 SQLite；`DbSession.execute` 多语句/错误/行上限 | JUnit 5 + 嵌入式 SQLite | `TransferRoundTripTest`、`EditSqlBuilderTest` |
| **UI** | 外壳、标签、数据页、批量条、详情抽屉、查询页执行/取消/多结果、结构页、连接页、连接向导 | TestFX + Monocle Headless | `QueryPageTest`、`DataGridPageTest`、`ConnectionWizardTest` |
| **样式/图标** | `TokenParityTest`（JSON 键、`Tokens.ALL_KEYS`、亮暗 CSS 一致；禁止颜色字面量）；`ContrastTest`（正文 ≥4.5:1、焦点环 ≥3:1）；`IconAuditTest`（图标控件同时具备 Tooltip 与 accessible text） | JUnit 5 | `TokenParityTest`、`ContrastTest`、`IconAuditTest` |

### 真库集成测试（opt-in，需 Docker 矩阵）

`scripts/dbmatrix.sh` 在本机 Docker 起满五家：MySQL 8.4、PostgreSQL 16、MariaDB 11.8、SQL Server 2022、Oracle 23ai Free，有库层的四家各两把库（`shop` + `shop2`）。口令由脚本现场随机生成，只写进未跟踪的 `scripts/.dbmatrix.env`（600），仓库零密码；测试侧从 `ADB_TEST_*` 环境变量读。

```bash
# 起矩阵
scripts/dbmatrix.sh up
eval "$(scripts/dbmatrix.sh env)"

# 带矩阵环境跑全量（主矩阵 51 / TLS 18 / 写回 15 / 导出往返 5 / SSH 5）
set -a && . scripts/.dbmatrix.env && set +a
./scripts/build.sh

# 只跑 TLS 对端
scripts/dbmatrix.sh tls-up
```

缺 `ADB_TEST_*` 时相关用例**按引擎逐条跳过**（不是整类跳过），默认门禁里的跳过数反映“缺了几个库”，不是“没测”。

---

## 3. 统计口径（极其重要，改数字前必读）

4 个测试类用 `@Nested`，surefire XML 把外层类写成 `tests="0"`，真实用例在**内层类**条目里。按 `tests=` 属性统计会**少算一百多条**。

> 一律用 `tools/TestCount.java`（按 `<testcase>` 元素计数，并断言总数下限 ≥ 300）。CI 与覆盖率工具同样按 `<testcase>` 计数。

```bash
java tools/TestCount.java
```

当前基线（2026-11-24，本机 macOS 15.7.9 x86_64，Temurin JDK 25.0.5，Docker 已开启，`./scripts/build.sh` + `java tools/TestCount.java`）：**2153 执行 / 0 失败 / 133 跳过 / 235 个测试类**，16/16 模块 SUCCESS。

⚠️ 133 条跳过是**设计如此**：`adb-session` 5 条是 Windows 凭据管理器（WinCred，本机 macOS）；`adb-jdbc` 74 条含真库矩阵与版本矩阵（需 `ADB_TEST_*` / `ADB_TEST_VERSION_MATRIX=1`）；`adb-editing` 15 条是真库写回矩阵；`adb-introspect` 19 条是真库内省；`adb-objectstore` 20 条是 OSS/Kodo 真账号契约（需 `ADB_TEST_OSS_*` / `ADB_TEST_KODO_*`）。缺环境时按引擎逐条跳过，不是「没测」。

---

## 4. 门禁列表（`docs/ARCHITECTURE.md` §8）

| 测试 | 守什么 |
|---|---|
| `TokenParityTest` | JSON 键 = `Tokens.ALL_KEYS`；亮暗 CSS 一致；业务 CSS 禁止颜色字面量 |
| `ContrastTest` | 正文 ≥4.5:1、焦点环 ≥3:1、厂商品牌色对页面底 ≥3:1 |
| `IconAuditTest` / `IconSystemAuditTest` | 每个图标控件同时有 Tooltip 与 accessible text；同一符号只表一种语义；纯图标按钮走矢量图 |
| `DialectBranchAuditTest` | 方言层之外禁止出现 `DialectKind.SQLITE/POSTGRESQL/MYSQL` 等方言专属比较 |
| `VendorGlyphTest` / `BrandIconTest` | 商标资产形状、官方色不被 CSS 覆盖、目录↔登记双向双射 |
| `ViewBodyDependenciesTest` / `SchemaDependencyGraphTest` / `DependencyLayoutTest` | 视图依赖图模型层（R16） |
| `I18nAuditTest` / `TerminologyGuardTest` | i18n 资源键与代码用量精确匹配；中文棘轮不超上限 |
| `DocLedgerAuditTest` | 公开文档里的“未实现/待启动”能力名必须与代码标记一致（文档棘轮） |

---

## 5. 如何写新测试（给贡献者）

1. **新能力先写门禁，再写实现**；变异验证（把断言改成错误期望）必须红。
2. **UI 测试用 `openTableTab(...)` 等“等节点出现再继续”**，不要用 TestFX `doubleClickOn`（机器压满时连击窗口会假红 / 假绿）。
3. **读计算色，不读 CSS 文本**：`IconKindHueTest`、`VendorGlyphTest` 断言的是渲染后的 `getFill()` / `getStroke()`；测试 `@Start` 时要把两套样式表挂上场景，否则 `getStroke()` 只是默认色，断言等于没测。
4. **不要为过测保留错误重复显示**：同步调整旧门禁，故意把“已实现写未实现”“WIP 写完成”对应契约测试应红。
5. **跨方言过五引擎真库矩阵**：涉写路径（保存、结构变更、导出往返、SSH）必须在真库上验证，单测不算真库验收。

---

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `The forked VM terminated without properly saying goodbye`，退出码 0、无 dump | TestFX 无头渲染的偶发 JVM 崩溃 | 单独重跑该类；全量加 `MAVEN_OPTS=-Xmx3g` |
| Monocle `Too small int buffer size` | 无头渲染告警，不影响结果 | 忽略 |
| `adb-jdbc` 223 对 222、`adb-session` 44 对 39 | surefire `Tests run=` 含跳过，TestCount 不含 | 以 `tools/TestCount.java` 为准，两口径别互相抄 |
| MySQL 8.4 `caching_sha2_password` 冷缓存首登报 `Public Key Retrieval is not allowed` | 驱动默认 `allowPublicKeyRetrieval=false` | `dbmatrix.sh tls-up` 就绪后多跑一次 `mysql_tls_warm_cache`（焐环境，不改断言） |
| `SHOW SESSION STATUS LIKE 'Ssl_cipher'` 回两列，取第 1 列是变量名 | 导致“假绿灯” | 取第 2 列，见 `docs/STATUS.md` §测试环境 |
| Oracle `getDdl` 把“读不到”与“连不上”压成同一 `null` | 产品侧缺陷 D-49 | 契约侧已改为取 `OracleDialect.READ_TIMEOUT_MS`，产品侧待修 |
| `adb-redis` 的 `RedisMutateTest` / `RedisScanTest` / `RedisSshTunnelTest` 报 `Could not find a valid Docker environment` | 本机未安装 Docker，Testcontainers 无法启动 Redis 容器；这三个测试在没有 Docker 时会**报错**而非跳过 | 安装 Docker 并启动守护进程后，`adb-redis` 会正常运行（Docker Desktop 或 `docker context` 可用）；排除该模块运行见 §1 |

---

## 7. CI 与本地保持一致

`.github/workflows/ci.yml` 只做一件事：跑 `./scripts/build.sh`。不在 CI 里另写 `mvn test -Dexclude…`，否则把红线挪到没人看的地方。

已知**不在 CI 里做**的事：
- 验收帧（`scripts/shots.sh`）需要真实窗口与显示环境，Monocle 无头跑不出可信帧；
- 真库集成测试由环境变量门控，没有配就没有跑（会显示为 skipped）；
- `adb-redis` 的 3 个测试需 Docker（Testcontainers 启动 `redis:7.2`），本机无 Docker 时会报错而非跳过，需排除该模块或安装 Docker；
- 少数按平台构造的断言（如 Windows 按键序）在 Linux runner 上环境门控跳过，不是失败。

首次在 GitHub Actions 上跑：Linux runner 上的 JavaFX 无头（Monocle + `prism.order=sw`）是本地未验证组合，若在加载原生图形库时红（`UnsatisfiedLinkError` / `libglass`），补 runner 缓存依赖并写进 CHANGELOG，别改门禁期望值。
