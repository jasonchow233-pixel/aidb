# JavaFX 组件选型

> 原则：**通用控件买，领域逻辑自己写。**
> 版本以父 POM `<dependencyManagement>` 为准；升级依赖时同步更新本文。

---

## 1. 已采用

### 1.1 UI

| 依赖 | 当前版本 | 许可 | 用途 |
|---|---|---|---|
| `org.openjfx:javafx-controls` | 25 | GPLv2 + Classpath Exception | `TableView` / `TreeView` / `TabPane` / `SplitPane` / `MenuBar` / `ContextMenu` |
| `org.controlsfx:controlsfx` | 11.2.6 | BSD-3-Clause | 对话框、增强 Tooltip、通知、进度等通用控件 |
| `org.fxmisc.richtext:richtextfx` | 0.11.7 | BSD-2-Clause | P3 SQL 编辑器底座（`CodeArea` / `StyledTextArea`） |

JavaFX 内置 `TableView` 底层是 `VirtualFlow`，只创建可见行的 Node；
百万行数据不会一次性生成 UI 节点。排序/筛选仍由我们下推数据库，不依赖控件内存排序。

### 1.2 数据 / 契约 / IO

| 依赖 | 当前版本 | 许可 | 用途 |
|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.4（annotations 2.22） | Apache-2.0 | JSON 摘要/格式化、DTO 序列化 |
| `net.java.dev.jna:jna` | 5.13.0 | LGPL-2.1 / Apache-2.0 双许可 | P5-2 Windows 凭据管理器（WinCred / advapi32） |
| `org.apache.poi:poi-ooxml` | 5.4.1 | Apache-2.0 | P6-3 xlsx 导入导出（SXSSF 流式写 / SAX 事件模型读） |
| `org.apache.sshd:sshd-core` | 2.20.1 | Apache-2.0 | G5a SSH 跳板隧道（`SshTunnel`）：只用客户端；服务端同包，只出现在测试里 |
| `org.slf4j:slf4j-api` / `slf4j-jdk14` | 2.0.17 | MIT | sshd 的日志出口。装 JUL 转发而不是 `slf4j-nop`：隧道出问题时控制台该看得见，不能静默 |

**G5a 为什么是 sshd 而不是「去调系统的 `ssh -L`」**：纯 Java 才不取决于用户装了什么 ——
Windows 上不保证有 `ssh`，而「连不上」不该由外部可执行文件的存在与否决定；子进程的生命周期
（什么时候该被杀掉）也不归我们。附带的好处是可测：`sshd-core` 里客户端与服务端同包，
契约能在本机进程内起一把真 sshd，于是「字节确实穿过了一条隧道」不需要第二台机器也能量得出来
（`SshTunnelTest` 9 条 + 契约第 13 条 `SshTunnelMatrixTest`，五家 Docker 各经跳板机连一次）。

**已知边界：私钥只吃 RSA / ECDSA，不吃 ed25519。** MINA 解析 OpenSSH 格式的 ed25519 私钥要依赖 `net.i2p.crypto:eddsa`，而这个坐标在本工程的仓库里取不到（`dependency:get` 直接找不到构件），所以没加。实测行为是 `loadKeys` **安静地返回 0 把**（不抛异常）——「读得出文件、给不出密钥」。
处理方式不是在背后偷偷换算法，而是两处点名 + 一条门禁：向导里 `wizard.ssh.key.formats` 写明支持的格式、提示词不再指向 `id_ed25519`；`SshTunnel` 那句报错写明「ed25519 不支持，请为这台跳板机另生成 RSA 密钥」；`SshTunnelTest#ed25519KeyNamesTheFormatsTheEngineActuallyLoads` 用系统 `ssh-keygen` 现场造一把真 ed25519 私钥钉住这句话（没有 `ssh-keygen` 的机器算跳过，不算验过）。

### 1.3 数据库与测试

| 依赖 | 当前版本 | 许可 | 用途 |
|---|---|---|---|
| `org.xerial:sqlite-jdbc` | 3.45.1.0 | Apache-2.0 | 第一阶段数据库 |
| `org.postgresql:postgresql` | 42.7.14 | BSD-2-Clause | P5 PostgreSQL |
| `com.mysql:mysql-connector-j` | 8.2.0 | GPLv2 + FOSS Exception | P5 MySQL |
| `org.mariadb.jdbc:mariadb-java-client` | 3.3.3 | **LGPL-2.1** | D-1 MariaDB（见下方许可说明） |
| `com.microsoft.sqlserver:mssql-jdbc` | 12.8.1.jre11 | MIT | D-3 SQL Server |
| `com.oracle.database.jdbc:ojdbc11` | 23.5.0.24.07 | **Oracle Free Use Terms and Conditions (FUTC)** | D-3 Oracle（见下方许可说明） |
| `org.junit.jupiter:junit-jupiter` | 5.10.2 | EPL-2.0 | 单元/集成测试 |
| `org.assertj:assertj-core` | 3.27.6 | Apache-2.0 | 断言 |
| `org.testfx:testfx-junit5` | 4.0.18 | BSD-2-Clause | UI 门禁 |
| `org.testfx:openjfx-monocle` | 21.0.2 | GPL-2.0 | CI 无头渲染，`test` scope |

### 1.4 计划采用（未进当前 POM）

| 依赖 | 阶段 | 用途 |
|---|---|---|
| `org.testcontainers:postgresql` / `mysql` | P5 | 真数据库集成测试 |

CSV 首版由 `CsvExporter` 自写（RFC 4180 引号/换行转义），暂不引入 commons-csv。
POI 已在 P6-3 落地（`XlsxCodec` / `XlsxStreamWriter`），不再属于「计划采用」。

**D-3 三条新增驱动的许可结论**（许可字样取自各 POM 的 `<licenses>`，版本同左表）：

| 驱动 | 结论 | 依据与约束 |
|---|---|---|
| MariaDB Connector/J 3.3.3（LGPL-2.1） | 可用，按**动态链接**方式使用 | 只在运行时放进 classpath、不修改、不静态内嵌；随包分发时附 LGPL 原文与「获取源码」说明。若将来要做单文件 fat-jar 发行，需要重新评估（内嵌 = 可能触发 LGPL 的整包开源义务） |
| mssql-jdbc（MIT） | 可用，无附加义务 | 保留版权与许可声明 |
| ojdbc11（Oracle FUTC） | 可用，**仅限本机/内部使用** | FUTC 允许随应用分发但不允许单独再分发驱动；商业再发行前须逐条复核 FUTC 原文。本项目的「自带驱动」安装包策略需在发布清单里单独标注 |


### 1.5 已在 POM 里但运行时未使用的模块

无。原 `core/adb-declarations` 已于 2026-10-24 删除（`snakeyaml` /
`json-schema-validator` 同步移除），见 `docs/ARCHITECTURE.md` §2.1。

### 1.6 非 POM 素材（不进依赖管理，但受许可约束）

| 素材 | 来源 | 许可 | 落点 |
|---|---|---|---|
| Lucide 图标路径数据 | [lucide](https://lucide.dev) 的 SVG `d` 属性（逐条借用，不引运行时库） | **ISC** | `ui/adb-ui-core/.../ui/icons/lucide/*.svg` 与 `icons/color/*.svg`；两份目录各带一份 `ISC-LICENSE.txt`，随安装包分发 |

ISC 只要求「副本里保留版权声明与许可原文」——所以许可文件必须和资产放在**同一资源目录**，
而不是只写在文档里。（`Icons.Icon` 共 **55** 个字形：53 个取自 Lucide 路径、2 个自绘 `BRAND` / `DOT`；
彩色档 15 枚资产凡直接复用 Lucide 路径的都算借用。）

---

## 2. 明确不用

| 方案 | 结论 | 原因 |
|---|---|---|
| `JFXtras TableViewFX` | ❌ | 项目已不存在；JavaFX 内置 `TableView` 已虚拟化 |
| FlatLaf | ❌ | Swing Look&Feel，与 JavaFX 无关 |
| MigLayout | ❌ | 没有 JavaFX 版；用 `BorderPane` / `VBox` / `HBox` / `GridPane` |
| Gluon Attach / GluonFX | ❌ | 面向移动端/原生打包，不是当前桌面 UI 需求 |
| 自绘标题栏 | ❌ | 首版明确不做；用 `StageStyle.DECORATED` |
| 内嵌 WebView / JCEF | ❌ | 已确定原生 JavaFX 路线，不引入浏览器运行时 |
| 自研虚拟表格 | ❌ | 重复造轮子；内置 `TableView` 已够用 |
| 图标字体（Font Awesome / Material Icons） | ❌（暂时） | 首版用 Unicode 符号，避免字体资源与打包体积；代价是同义不同形、跨平台缺字、无 accessible text。长期方向是**内嵌 SVG 图标集**（JavaFX 原生支持，无需字体） |

TestFX 两年未大版本更新，但仍是 JavaFX 无头 UI 门禁最成熟的组合；
固定版本、`test` scope、不进入发行物。

---

## 3. 自研边界

| 组件 | 为什么自己写 |
|---|---|
| `Tokens` + 双主题 CSS | 设计令牌唯一真相；生成器 `tools/GenTokens.java` |
| `CellRenderer` | 数据库专用：NULL/空串、BLOB 大小、枚举映射、日期、JSON 摘要 |
| `EditBuffer` / `EditSqlBuilder` | 按主键编辑、撤销重做、SQL 预览、事务、反向撤销 |
| `SqlLexer` / 语句分句器 | 领域语法与错误 offset，通用编辑器不提供 |
| `DangerClassifier` | 危险 SQL 分级，安全关键 |
| 列布局持久化 | 按“连接 + 表”记忆，业务语义强 |
| `StructureSqlBuilder` | 按方言生成安全的列级 DDL；SQLite 整表重建、PG/MySQL 差异都自己兜 |
| `CsvExporter` | RFC 4180 引号/换行转义，首版不引 commons-csv |
| `ConnectionWizard` | 三步、可回退、测试连接、口令不落盘；注意 JavaFX `DialogPane` 的按钮陷阱（见架构文档 §7.1） |


---

## 4. 许可证与分发

- JavaFX：GPLv2 + Classpath Exception，可随桌面应用分发。
- ControlsFX / RichTextFX / TestFX / AssertJ：BSD/Apache 系，商用友好。
- Apache MINA SSHD（`sshd-core`）与 SLF4J：Apache-2.0 / MIT，商用友好，无额外义务。
- Monocle：GPL-2.0，仅 `test` scope，不进安装包。
- MySQL Connector/J：GPLv2 + FOSS Exception；闭源分发前需复核。
- Lucide 路径数据：ISC，许可原文随两份图标资源目录各自分发（见 §1.6）。
- 所有依赖版本只写在父 POM；子模块不写 `<version>`。

---

## 5. 结论

- 当前技术栈足够支撑 Navicat 级数据页与查询页，不需要引入 WebView 或 Eclipse RCP。
- 数据表格、对话框、编辑器底座用成熟库；编辑语义、SQL 安全、渲染规则自己掌握。
- P3 的唯一新 UI 依赖是 RichTextFX；P5 引入连接池、Testcontainers 与 JNA；P6-3 引入 POI（SXSSF / SAX 流式）。
- 图标已是内嵌 **SVGPath** 矢量（`Icons.Icon` 统一字形 + accessible text，S-7 品牌 mark 同源），不引第三方图标字体。
  R35 起同一批字形有**彩色档**：资产只声明分层角色（`data-paint`），颜色全部来自设计令牌，
  暗色主题不换资产（见 `docs/UI-VISUAL-SPEC.md` §4.5）。
