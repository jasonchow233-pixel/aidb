# AgenticDB 架构（Java 25 + JavaFX 25）

> 当前唯一技术路线。旧 Rust / Vue / Python 实现与构建产物已删除，不再作为运行时依赖。
> 执行清单见 `NAVICAT-TODO.md`；组件选型见 `docs/COMPONENT-SELECTION.md`；UI 规则见 `docs/UI-GUIDE.md`。

---

## 1. 约束

| 约束 | 决定 |
|---|---|
| UI | JavaFX 25 原生控件，原生窗口装饰；不内嵌 WebView |
| 运行时 | 不启动 HTTP 端口，不做本地 Web 服务 |
| JDBC | 所有 SQL 参数绑定；只有标识符（表/列名）走方言引用 |
| 线程 | 所有 JDBC 调用串行跑在 `adb-db` 单线程执行器；UI 更新回 JavaFX 线程 |
| 编辑 | 只有含主键的表可编辑；按主键定位；保存前预览 SQL；单事务提交 |
| 主题 | 默认跟随系统；亮色是验收基准；颜色只来自 `tokens.json` |
| 范围 | 第一阶段 SQLite；PostgreSQL / MySQL 在 P5 后接入 |

---

## 2. 模块

```
core/
  adb-model          领域模型：ColumnDef / ColumnType / RowPage / QueryLimits / Danger
  adb-declarations   连接与传输声明加载（YAML + JSON Schema）
  adb-safety         危险分级、错误归类、SQL 风险提示
  adb-sql            SQL 词法内核：分句、高亮、格式化（不依赖 UI）

workbench/
  adb-jdbc           统一 JDBC 会话：连接、参数化查询/更新、事务、方言、类型映射
  adb-introspect     对象列表、列定义、主键、行数、分页/排序/筛选 SQL 构造
  adb-session        连接注册表与生命周期
  adb-editing        RowIdentity / CellKey / EditBuffer / EditSqlBuilder

ui/
  adb-ui-core        tokens.json → Tokens.java + theme-dark/light.css；主题管理
  adb-ui-shell       六区外壳、菜单、工具栏、侧栏、标签栏、命令面板、数据页

testing/
  adb-fixtures       固定种子演示库与测试夹具

bootstrap/
  adb-desktop        唯一启动入口；后续 jlink / jpackage
```

模块依赖只向下：`ui-shell → workbench → core`，`bootstrap → ui-shell`。
`adb-sql` 保持独立：只把 SQL 切成 token / 语句 / 格式化文本，不反向依赖 UI 或 JDBC。
RichTextFX 的样式映射在 `ui-shell` 的 `SqlHighlighter` 中完成。

---

## 3. 启动与数据流

```
Launcher
  └─ AppShell
       ├─ ThemeManager（SettingsStore / java.util.prefs）
       ├─ SessionManager → JdbcSession（SQLite）
       ├─ SchemaService.listObjects() → SideBar
       └─ TabBar / WorkspaceView
            ├─ DataGridPage（按 tab id 缓存）
            │    ├─ SchemaService.describe()
            │    ├─ DataSqlBuilder.page() → DbSession.query()
            │    ├─ EditBuffer（内存中的单元格/删除/草稿）
            │    └─ EditSqlBuilder.plan() → 预览 → 事务执行
            └─ QueryPage（按 tab id 缓存）
                 ├─ SqlLexer / SqlSplitter / SqlFormatter
                 ├─ DangerClassifier → 写操作确认
                 ├─ DbSession.execute() → ExecutionResult（单条语句）
                 └─ 结果 / 消息 / 错误页签 → CellRenderer / CsvExporter
```

### 线程模型

1. `DataGridPage` 的每次加载创建 `Task`，提交到共享单线程执行器 `adb-db`。
2. 请求带递增序号；切页/刷新后旧结果直接丢弃，不覆盖新页。
3. JDBC 结果只在 `Task` 内读取，组装成不可变 `RowPage` / `ExecutionResult` 后回 JavaFX 线程。
4. 同一连接上的查询、保存、撤销保存严格串行，避免 SQLite 锁竞争。
5. 查询页先用 `SqlSplitter` 在 UI 线程分句，再把每条语句依次提交到 `adb-db`；任务带请求序号，取消或重跑后迟到回调直接丢弃。
6. `DbSession.cancelCurrent()` 调用当前 `Statement.cancel()`；SQLite 驱动不支持时，任务仍会在当前语句结束后退出。

---

## 4. 数据页编辑模型

### 4.1 身份与定位

- `RowIdentity` 保存主键列和值；无主键表、视图一律只读。
- `CellKey = (RowIdentity, column)`；所有单元格修改以它为键。
- 主键列不允许在网格内直接编辑；克隆行排除主键列。

### 4.2 EditBuffer

| 状态 | 含义 |
|---|---|
| `edited` | 已有行的单元格新值 |
| `originals` | 被编辑单元格的数据库原值 |
| `deleted` | 标记删除的已有行 |
| `drafts` | 尚未写库的新增/克隆行 |

- 每个操作（单元格、删除、草稿、批量）进撤销栈；批量操作只占一个栈位。
- `dirtyCount()` = 被编辑单元格 + 删除行 + 草稿行 + 草稿单元格。
- 保存成功后清空；保存前可生成反向计划，Toast 提供“撤销保存”。

### 4.3 SQL 生成

- UPDATE / DELETE 一律用主键 `WHERE`；INSERT 只包含草稿里存在的列。
- 值全部是 `?` 参数；预览只把参数替换成可读字面量，不拼进真实 SQL。
- 保存在一个事务里执行；任一条失败整体回滚。
- 反向计划：UPDATE 还原原值，DELETE 用删除时保留的整行值重新 INSERT，
  INSERT 只有主键齐全时才能反删；否则标记为不可完整撤销。

### 4.4 视图状态

- 排序/筛选下推服务端 SQL；本页搜索与隐藏 NULL/空串只在客户端过滤。
- 列宽/顺序/显隐按“连接名 + 表名”存 `java.util.prefs`。
- 转置是只读视图，不参与编辑和批量选择。

---

## 5. UI 外壳

六区：菜单栏、主工具栏、侧栏、标签栏、内容区、状态栏。

- `ActionRegistry` 是菜单、工具栏、右键、命令面板的唯一动作来源，统一 enabled/快捷键。
- `AppShell` 负责窗口、会话、标签、状态栏、Toast；页面只通过 `Feedback` 回报。
- 设计令牌：`ui/adb-ui-core/src/main/resources/tokens/tokens.json` 是唯一真相。
  生成器：`java tools/GenTokens.java`。
- 业务 CSS 只允许引用 `-adb-*` looked-up colors；`TokenParityTest` 会扫描颜色字面量。
- 原生窗口：`StageStyle.DECORATED`；macOS 使用系统菜单栏。

---

## 6. 门禁

| 层 | 内容 |
|---|---|
| 单元 | `EditBuffer`、`EditSqlBuilder`、`CellRenderer`、`CsvExporter`、`SqlLexer`、`SqlSplitter`、`SqlFormatter` |
| 数据 | 演示库固定种子；分页/排序/筛选 SQL 断言；保存/反向撤销真实 SQLite；`DbSession.execute` 多语句/错误/行上限 |
| UI | TestFX + Monocle Headless：外壳、标签、数据页、批量条、详情抽屉、查询页执行/取消/多结果 |
| 样式 | `TokenParityTest`：JSON 键、`Tokens.ALL_KEYS`、亮暗 CSS 一致；禁止颜色字面量 |

统一命令：

```bash
./mvnw -B clean verify
```

---

## 7. 后续里程碑

| 阶段 | 内容 |
|---|---|
| P3 ✅ | SQL 词法内核、RichTextFX 编辑器、多语句结果页签、取消执行、错误定位、执行计划、查询历史 |
| P4 | 结构页：列/索引/外键/DDL 查看与安全的表结构编辑 |
| P5 | 连接管理、PostgreSQL / MySQL、连接池、凭据存储 |
| P6 | 导入/导出向导：CSV / JSON / Excel、字段映射、预览、批处理 |
| P7 | 打包分发、jlink / jpackage、自动更新、性能与可访问性收尾 |
