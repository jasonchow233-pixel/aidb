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
| `org.yaml:snakeyaml` | 2.7 | Apache-2.0 | `declarations/` YAML 解析 |
| `com.networknt:json-schema-validator` | 2.0.8 | Apache-2.0 | 连接声明 JSON Schema 校验 |
| `com.zaxxer:HikariCP` | 6.3.1 | Apache-2.0 | P5 连接池 |

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
| `org.apache.poi:poi-ooxml` | P6 | Excel `.xlsx` 导入导出 |
| `org.testcontainers:postgresql` / `mysql` | P5 | 真数据库集成测试 |

CSV 首版由 `CsvExporter` 自写（RFC 4180 引号/换行转义），暂不引入 commons-csv。

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

---

## 4. 许可证与分发

- JavaFX：GPLv2 + Classpath Exception，可随桌面应用分发。
- ControlsFX / RichTextFX / TestFX / AssertJ：BSD/Apache 系，商用友好。
- Monocle：GPL-2.0，仅 `test` scope，不进安装包。
- MySQL Connector/J：GPLv2 + FOSS Exception；闭源分发前需复核。
- 所有依赖版本只写在父 POM；子模块不写 `<version>`。

---

## 5. 结论

- 当前技术栈足够支撑 Navicat 级数据页与查询页，不需要引入 WebView 或 Eclipse RCP。
- 数据表格、对话框、编辑器底座用成熟库；编辑语义、SQL 安全、渲染规则自己掌握。
- P3 的唯一新 UI 依赖是 RichTextFX；P5 才引入连接池与 Testcontainers；P6 才引入 POI。
