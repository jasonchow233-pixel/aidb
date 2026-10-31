# 现状与限制（STATUS）

> 这份文档承担两件事：**能力面清单**（按能力，不按类名）与**完整限制清单**。
> 它是全仓库**唯一**记「验证基线数字」的地方 —— README、ROADMAP 都不再各自记一份，
> 因为历史上同一个数字在三处不一致过（`964/123` 与 `1108/134` 并存，而后者实测是 `1107/133`）。
> 数字必须是**回量出来的**，并写明量于何日、什么平台、什么命令。

## 1. 验证基线

| 项 | 值 |
|---|---|
| 命令 | `./scripts/build.sh`（= `./mvnw -B clean verify` + `java tools/TestCount.java`） |
| 最近一次全量回量 | 2026-10-31 · macOS 15.7 x64 · JDK 25.0.4（IntelliJ JBR） |
| 结果 | **1203 条 / 142 类 / 0 失败**（另有 53 条环境门控跳过）—— R36 顶区 + D 系列方言批收官后回量（`./scripts/build.sh` 全绿；较上一版 +37 条 / +3 类，新增 `MariaDbDialectTest` 5 / `OracleDialectTest` 9 / `SqlServerDialectTest` 11） |

**口径警告（改数字前必读）**：4 个测试类用 `@Nested`，surefire 的 XML 会把外层类写成 `tests="0"`
（真实用例在内层类条目里）。按 `tests=` 属性统计会少算一百多条 —— 所以计数一律走
`tools/TestCount.java`（按 `<testcase>` 元素数），并且它同时断言总数下限。
`I18nAuditTest` 的汉字棘轮同理：**别用 grep 数**，口径不同的两个数放在一起只会让人以为棘轮又涨了。

另有 **53 条环境门控跳过**：需要 Docker / MySQL·PostgreSQL 真库环境变量，本机没配就跳过，
不算失败（「0 失败」是硬门禁，「0 跳过」不是）。本轮实测的分布：
`adb-jdbc` 30 · `adb-introspect` 18 · `adb-session` 5。

## 2. 能力面

| 能力面 | 状态 | 边界（哪些是真的，哪些不是） |
|---|---|---|
| 外壳与布局 | ✅ | 七区（菜单 / 功能区 / 侧栏 / 页签 / 对象工具条 / 内容区 / 状态栏）；侧栏 250px、折叠 48px、可拖 200–360；macOS 用系统菜单栏 |
| 连接与凭据 | ✅ | 六种方言：SQLite / MySQL 5.7–8.x / PostgreSQL（真库验证过）；MariaDB / SQL Server / Oracle（方言 + 单测门禁已落地，**目录查询未在真库验证**，方言源码逐条标注）。连接分组、收藏、复制、URI（含 Oracle EZConnect 与 SQL Server 分号参数串）；口令进系统凭据服务，**不落盘**；`connections.json` 落盘前剔除敏感键；HikariCP 池化（除 SQLite），SQLite 直连 |
| 连接向导 | ✅ | 6 个类型磁贴 → 7 页属性 → 测试 → 保存。SSL 页的档位与根证书参数**由方言自报**（PG `sslmode` / MySQL `sslMode` / MariaDB `sslMode`+`serverSslCert`，MariaDB 没有 prefer 档）；SQL Server（encrypt + trustServerCertificate 配对）与 Oracle（钱包 / sqlnet.ora）收起档位并写明去哪里填；SSH / HTTP 页如实标注「未实现」，不是空壳 |
| 对象树 | ✅ | 真实内省（表 / 视图 / 触发器 / 事件 / 函数·过程）；多连接同树；表节点懒加载展开列，失败可重试 |
| 数据网格 | ✅ | 服务端分页（100/200/500/1000）、选择列、行号、类型行、列宽/顺序/显隐持久化、冻结列、矩形选择、⌘F 本页搜索、转置只读视图 |
| 数据页写路径 | ✅ | 行内编辑 + 类型转换 + 按主键定位；NULL / 空串独立操作；批量条（TSV / INSERT / 克隆 / 置 NULL / 置空串 / 删除）；SQL 预览 → 单事务 → 失败回滚 → 保存后一次反向撤销 |
| 结构编辑 | ✅ | 加列 / 改列 / 重命名 / 删列 + 两级危险确认；索引、外键、触发器、CHECK 可增删；表注释可读可改（SQLite 无 → 按钮禁用）；改列整表重建时搬运 CHECK |
| 建表 / 表级生命周期 | ✅ | 建表（弹窗 + 实时 SQL 预览）/ 清空 / 重命名 / 删除，三种方言各走自己的语法。⚠️ 建表弹窗里还不能录外键 / 索引 / CHECK；建表后不自动打开该表 |
| 查询页 | ✅ | RichTextFX 编辑器（高亮 / 行号 / 当前行 / 查找替换）；`⌘R` 当前语句、`⇧⌘R` 全部、`⌘.` 取消；多语句结果页签、服务端分页、执行计划、错误定位、查询历史（50 条，带对象上下文）、代码片段。执行计划按方言能力开放：SQL Server / Oracle 需要两步协议，按钮禁用并说明原因 |
| 导入导出 | ✅ | 流式 CSV / TSV / JSON Lines / SQL / xlsx（POI SXSSF 写 + SAX 读）；字段映射、按目标列类型转换（含中文日期格式）、坏行报告带列名与原文；查询结果三档导出（本页 / 选中 / 全部） |
| 错误与超时 | ✅ | 错误归类（断连 / 权限 / 不存在 / 超时 / 锁 / 约束 / 语法）；语句超时 30s 并给建议；崩溃日志 `~/.agenticdb/logs/crash-*.log` |
| 外观 | ✅ | 暗亮双主题 + 跟随系统 + 本地记忆；四档密度；106 个设计令牌唯一真相；图标走语义彩色层（同一套资产换主题自动换色） |
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

- 危险分级**第三层（驱动侧复查）不存在**：`DbSession.execute(sql)` 没有 `danger` 参数，
  绕过 UI 直接调会话层不会被拦。前两层（fail-closed 分类 + UI 确认）已落地。
- **SSH 隧道未实现**（连接向导里明说）；SSL/TLS 只做到「写驱动属性」，证书链校验与 SNI 待补。
- 视图不能增删改；函数 / 过程只读定义体（SQLite 没有 SQL 层函数）。
- 点对象首页的「函数 / 过程 / 触发器」会被告知暂不支持（不再报错，但也不能看定义体）。
- 建表弹窗不能录外键 / 索引 / CHECK（生成器支持，UI 还没有录入区）。
- 表清单工具条还缺新建 / 删除 / 设计的直达入口。

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

- PostgreSQL 缺真库（未配置即跳过）；MySQL 5.7.44 真库联调过 9 例。

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
