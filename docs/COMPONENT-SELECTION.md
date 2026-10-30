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
| `com.zaxxer:HikariCP` | 6.3.1 | Apache-2.0 | P5 连接池 |
| `net.java.dev.jna:jna` | 5.13.0 | LGPL-2.1 / Apache-2.0 双许可 | P5-2 Windows 凭据管理器（WinCred / advapi32） |
| `org.apache.poi:poi-ooxml` | 5.4.1 | Apache-2.0 | P6-3 xlsx 导入导出（SXSSF 流式写 / SAX 事件模型读） |

### 1.3 数据库与测试

| 依赖 | 当前版本 | 许可 | 用途 |
|---|---|---|---|
| `org.xerial:sqlite-jdbc` | 3.45.1.0 | Apache-2.0 | 第一阶段数据库 |
| `org.postgresql:postgresql` | 42.7.14 | BSD-2-Clause | P5 PostgreSQL |
| `com.mysql:mysql-connector-j` | 8.2.0 | GPLv2 + FOSS Exception | P5 MySQL |
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
