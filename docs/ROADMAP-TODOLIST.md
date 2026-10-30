# ROADMAP-TODOLIST — 开发 Todolist 与规划（执行视图）

> **定位**：`NAVICAT-TODO.md`（唯一真源，2026-10-29 第三次重写）的可勾选执行清单。
> 编号 N1–N34、实测值、成本、门禁改判全部继承真源 §2/§1/§5；状态变更**两处同步**。
> 本版依据：2026-10-29 用户令「忘掉旧对比、9 张 Navicat 实拍（含 2 张 10-29 新帧）逐张重比、重写规划」。
> 上一版（2026-10-28）的规划口径作废；R34 十个批次的执行日志保留在本文 §1.1（第八批已随 `46873b8` 落库）。

---

## 0. 当前基线

- 门禁 **1182 条 / 134 类 / 0 失败 / 48 环境跳过**（R34 收官批后 `./mvnw -o test` 实测 —— 比第八至二十五批的
  1180 多 2 条，即收官批新立的「重复键行」与「标准按钮走资源包」两条门禁；
  第九至二十五批只降棘轮、未立新门禁；第八批曾恰比第七批的 1179 多 1，即第八批新立的对象树菜单文字对账门禁）。
  **量法**：`grep -aoP 'Tests run: \K[0-9]+(?=, Failures: [0-9]+, Errors: \d+, Skipped: \d+, Time elapsed)'`
  对全量日志求和 → 150 组 / 1182 条；类数按 `**/target/surefire-reports/*.txt` 数 = 134
  （150 与 134 之差是 `@Nested`：一行一组、一份报告）。
  ⚠️ 全量必须 JDK 25+；`JAVA_HOME` 留在系统默认 21 会被 enforcer 直接 BUILD FAILURE
  （本机 `C:\Users\Administrator\.jdks\openjdk-27.0.1.0.2`）。
  ⚠️ 此前文档记过 1116/1119/1122/1173/1174/1175/1176 —— 都是两种口径混用；以后照上面那条命令量。
- 已落库提交：`34ffb8f`（R33 全部 + R34 首批）· `288592d`（N1-d）· `05351ce`（N2）· `87d2ddb`（窗口标题）·
  `c6ae488`/`2f85990`（第三批）· `3077924`/`bd2640b`（第四批）· `534ebe9`/`91c4084`/`e7c6543`（第五批）·
  `7e8b6f7`/`82ea100`（第六批）· `88a4576`/`ff2e7d4`（第七批）· `46873b8`（第八批 + 本文重写）·
  `f0c1044`（第九批）· `6e99d60`（第十批）· `ed41207`（第十一批）· `7def1cf`（第十二批）·
  `d20f5eb`（第十三批）· `fa23770`（第十四批）· `0f69877`（第十五批）· `94f0f23`（第十六批）·
  `4ff9ffd`（第十七批）· `a347bdf`（第十八批）· `06b2266`（第十九批）· `c2b9b2f`（第二十批）·
  `e95c7cd`（第二十一批）· `2325847`（第二十二批）· `e90f1ae`（第二十三批）· `3d802a9`（第二十四批）·
  `8db09c0`（第二十五批 · 抽取收口）· `d425b1a`（R34 收官批 · 六摆态六帧 + 两条新门禁）。
  **push 仍未授权。**
- 验收帧 **40 张**（35 亮 / 5 暗），第八批后全量重拍为英文档（19 帧变化、1280×800、md5 无重复帧）。
  第十六批新增 p2-section-indexes；第十九批重拍 p2-details 当场抓到英文截字（详情面板页脚三颗
  等分按钮压成「Open D… / Open Struct… / Open D…」）→ HBox 改 FlowPane 换行后复拍核销。
  ✅ **第二十至二十五批那 275 条文案的零帧覆盖已在收官批补齐**（六个摆态 + 六帧：
  p9-settings / p9-diagnostics / p9-favorites / p9-saved-queries / p6-rename-table / p2-filter），
  当场抓到「重复键行」与「标准按钮跟 JVM Locale」两个门禁全绿的缺陷 —— 见 §1 R34 收官批。
  ⚠️ 收官批目视另记两条未修：**D-12** 顶栏搜索框的快捷键胶囊截成「Ct…」（`appbar-search`
  定宽，归 R37 外壳那一轮）、**D-13** Navicat 的筛选是工具栏下整幅「Filter & Sort」内联带
  （多条件 + Sort By + Apply），AIDB 是列头单列浮窗 + 模态条件弹窗（入口同名，形态不同，归 R38 网格那一轮）。
- 棘轮 **8**（主尺，终态；剩余 8 条是 `CellRenderer` / `CellValueParser` 认布尔值的
  **输入 token**「是/真/否/假」，不是文案 —— 三条出路都被否掉并写进 `I18nAuditTest` javadoc）；
  模型层 **277**（第 13 条棘轮，D-9）。资源包 **1399 键** en/zh 对称（严格数 `^[^#=]+=` 行；
  收官批 +1 = `common.close`，并把重复的 `details.title` 拆成 `details.object.title` —— 行数与唯一数
  从此相等，`noDuplicateKeyLinesInEitherBundle` 守着）。
- 总账：**R34–R41 = 61–86 人日**；**+R42 = 97–142 人日**（本轮新增 N31–N34 后上调，见 §2）。

---

## 1. Todolist

### R34 · i18n 层 + 默认英文（N1 N2，估 8–12 人日）—— ✅ 2026-10-30 收官

> 前置轮，卡住后面每一条带标签的条目。设计定式与批次教训全文见真源 §5 与 git 历史。

- [x] **N1-a** `I18n.java` + `messages_en.properties`（默认）/ `messages_zh.properties`
      （不走 locale 级联；占位符字面替换不用 MessageFormat；properties 一律 UTF-8 Reader）
- [x] **N1-b 第一批** 顶区四件套（MenuBarFactory/AppBar/StatusBar/TabBar）：2216 → **2154**，资源包 51 键
- [x] **N1-b 第二批** 动作层 71 个动作（最高杠杆点）：→ **2011**，134 键
- [x] **N1-b 第三批** `TableListPage`（70→0）：→ **1917**；顺带补动作门禁盲区（`localActions()` 走 `new Action` 绕过注册表对账）
- [x] **N1-b 第四批** `DataGridPage`（413→0，R34 最大一块）：→ **1504**，474 键。三处改判
      （工具条按 Navicat 分工切、分页器钉 `USE_PREF_SIZE` 判据用 `prefWidth`、跳转框宽随提示文字），
      三个真缺陷顺手修（`&S` 助记符吞噬、全角标点漏检、喂宽/读可见性跨线程竞态）
- [x] **N1-b 第五批** `AppShell`（198→0）：→ **1306**，598 键。英文逐条回查本地帧
      （证伪 "Row Count"/"Creation Time"）；演示 CSV 是数据不是文案（资源文件落盘转 GBK）；
      新立第 11 条门禁（实参个数 = 两侧占位符数）；⭐ 目视抓到 **D-5**（命令带 62px 定宽裁英文标签）已修
- [x] **N1-b 第六批** `QueryPage`（161→0）：→ **1145**，701 键。⭐ 抓到 **D-7**（模型层 `Danger.label()`
      中文越过棘轮扫描范围上屏，`DataGridTransactionTest` 还把它编成契约）已修 + 第 12 条门禁；
      ⭐ 目视抓到 **D-8**（查询页分页条挤成半截字）已修；工具坑：CRLF 让 codemod 多行模式整批 MISS，dry/apply 各跑一次
- [x] **N1-b 第七批** `StructurePage`（156→0）：→ **989**，795 键。⭐ 量出模型层 277 条 → 第 13 条棘轮
      （`MODEL_CJK_CEILING`）；`kindLabel()` 零消费者直接删（能删生产者就别加映射）；
      **D-9 记账未修**（`StructurePlan.title()` 17 个中文标题，104 条）；新立结构页宽度门禁
      （自证救场一次 + TabPane 只挂选中页签的坑）
- [x] **N1-b 第八批** `SideBar`（96→0）：→ **890**，846 键。⭐ 两个结构性缺陷：
      ① 21 处菜单标签二次覆盖中文 override 全删 + `ActionRegistryConvergenceTest` 走真菜单树对账；
      ② 「标题当路由键」改 `TabItem.target()`（`TOOL_HISTORY`/`TOOL_DIAGNOSTICS` 常量），
      删 `AppShell.openTool(String title)` 残骸。33 帧重拍目视（19 变化），本批无新裁字
- [x] **N1-b 第九批** `ConnectionWizard`（89→0）：→ **801**，929 键。SslMode 枚举改「存键、
      渲染时解析」（TableListPage 教训第二次落地）；SSH/HTTP 四段 concat 并单键、错误文案
      {0} 传参；英文按本地帧实证（页签/按钮/占位符），SSL 模式名 inferred；7 处测试文案
      断言重钉为 `I18n.t()`。p5-connect 4 帧全英无裁字；p5-connections 的中文属
      `ConnectionsPage` 未抽（与第八批帧同态，非本批回归）
- [x] **N1-b 第十批** `ObjectHomePage`（83→0）：→ **718**，975 键。复用 20 个既有键
      （打开/刷新/视图切换/新建表/清空搜索/重试/未连接/概览/五个列头/类型/行数/表/视图）——
      双侧全等才许复用；`labelOf()` 零消费者直接删（第七批判据）；拼接文案全改 {0} 占位符。
      ⭐ 顺带修截图基建：`targetScene` 浮窗扫描把 Tooltip 当目标（光标停在按钮上时
      p0-shell 拍出 163×48 悬浮条且 glass.dll 崩 JVM）—— 浮窗扫描跳过 Tooltip。
      p0-shell 重拍全英逐项目视；`commentColumn()` 辅助方法里的标题比对漏扫两轮才抓到
- [x] **N1-b 第十一批** `NewTableDialog`（75→0）：→ **643**，1024 键（新增 49 键 `newtable.*`，
      复用 15 个既有键 —— 双侧全等才许复用）。⭐ 建表计划报错路由改 `StructureSqlBuilder.DesignStepException`
      （FOREIGN_KEY/INDEX/TRIGGER 按类型选页签，替掉 `contains("索引")` 消息匹配，为 D-9 铺路）；
      ⭐ codemod 教训：concat 对的 needle 必须取**整个表达式**（`"字段数：" + rows.size()`），
      只取引号字面量会留下 `+ x` 尾巴变双实参、`contains` 断言还测不出来 —— 9 处事后全修；
      顺带修自增列宽 46→72px（英文「Auto Incr」被截，目视抓到）；p6 7 帧重拍逐张目视全英
- [x] **N1-b 第十二批** `ImportWizard`（61→0）：→ **569**，1085 键（新增 49 键 `import.*`
      + 12 键 `batch.*`，复用 4 键双侧全等）。⭐ 连带 `BatchSummary`/`BatchSummaryDialog`：
      headline 硬拼「已+动作」，只翻调用方实参会产出「已Import 7/10 行」混排垃圾 ——
      三个 headline 键模板化（zh 输出逐字节不变），TableDdl/ExportWizard 等未抽调用方的
      中文实参维持原状；⭐ `ReportDialogsTest` 两测是全量跑完才补抓的漏网钉子 ——
      「全量之后、提交之前」必须再对文案 grep 一轮；en 值前导空格 properties 会吞，
      `\u0020` 转义；p8-import 目视全英（Import Mode「追加」= 模型层 `ImportMode.label()`，D-9 同态非回归）
- [x] **N1-b 第十三批** `CellDetailsDrawer`（39→0）：→ **530**，1105 键（新增 20 键 `details.*`，
      复用 11 键双侧全等）。⭐ 「编辑中」标题恢复原靠 `title.getText().endsWith(" · 编辑中")`
      文案匹配 —— 翻译后必然失灵，改 `titleShowsEditState` 布尔标记
      （第八批「标题当路由键」教训第三次落地：**状态别藏在展示文案里**）；
      抽屉文案无测试钉（先 grep 补扫后跑测）；p2-cell 目视全英，
      p2-details 同步重拍（右侧中文属 `ObjectDetailsPanel`，后续批次）
- [x] **N1-b 第十四批** `ConnectionsPage`（51→0）：→ **479**，1130 键（新增 25 键 `conn.*`，
      复用 12 键双侧全等 —— 连接管理页的按钮/列头几乎全是既有键：打开/编辑/复制/重命名/
      删除/刷新/名称/类型/数据库/已连接/只看收藏/主机/端口/新建连接全复用）。
      六条 tooltip 提示键化 + `conn.hint.noneSelected={0}` 传参拼接（原字面 concat）；
      ConnectionsPageTest 八列列头断言重钉；p5-connections 目视全英
- [x] **N1-b 第十五批** `IndexDialog`（41→0）：→ **438**，1159 键（新增 29 键 `idx.*`，
      复用 5 键双侧全等）。⭐ 第十三批教训立刻兑现：方向下拉 `setDescending("降序".equals(v))`
      是「值字符串当状态」的同类病灶，改构造期取好的 `descendingValue` 常量比对；
      计数文案（索引列（N）/ 添加列（N））改 {0} 占位键，快捷键提示传 `Shortcuts.of()`；
      IndexDialogTest 12 处重钉（含 buttonData 按 common.ok/common.cancel 文本查按钮）；
      p7-new-index 亮/暗两帧目视全英，暗帧选中带正常（R31 斑马纹修复未回退）
- [x] **N1-b 第十六批** `ObjectSectionsPanel`（40→0）：→ **398**，1178 键（新增 19 键 `sections.*`，
      复用 13 键双侧全等）。⭐ **补上验收盲区**：四张只读切面表此前没有任何帧覆盖
      （抽完 i18n 只能靠单测）—— 新增 `ADB_SECTION=<切面 id>` 摆态（与 ADB_OVERVIEW 同型）
      + 第 34 帧 p2-section-indexes，脚本审计门禁当场通过。首拍即抓到 **D-10**：
      Origin 列原样上屏 `IndexDef.origin()` 的单字母码「c」「u」（模型层显示问题，非本批引入，
      修法同 D-9 家族，归 R38/R40）。⚠️ 口径纠正：本文件官方计数是 **40**，
      此前文档里的「37」来自临时脚本 —— 逐文件数字从此只认 `tools/cjk-count.mjs` 默认模式
- [x] **N1-b 第十七批** `ExportWizard`（37→0）：→ **361**，1204 键（新增 26 键 `export.*`，
      复用 5 键双侧全等）。⭐ `Scope` 枚举改「存键、渲染时解析」（第九批 SslMode 同规）——
      下拉与摘要「已导出 … （Whole table · XML …）」共用 `toString()`，不留第二份文案；
      `export.done` 拆首句+明细两键，测试钉首段不必复算六参；TransferWizardTest 4 处重钉；
      p8-export 目视全英（XML 下两个选项正确置灰，R33 诚实性修复未回退）
- [x] **N1-b 第十八批** `TableDdl`（34→0）：→ **327**，1226 键（新增 22 键 `ddl.*`，无复用 ——
      这批全是「连接没打开 / 请先选中表 / 已有变更在跑」这类协调者专属话术，逐条比对后没有一条
      与既有键双侧全等）。⭐ 三处硬拼一起收：批量删除的失败行 `name + "：" + readable(e)`、
      状态行的 `已删除 x/y 张表；失败：` + `String.join("、", …)`（顿号进键 `ddl.listSeparator`，
      英文是「, 」）、汇总弹窗 `BatchSummary.of("删除表", "删除", "张表", …)` 的三个实参
      —— 第十二批修的是 headline 组合器，调用方这三个实参是同一颗病灶的另一半。
      TableDdlTest 4 处重钉（`ddl.done`/`ddl.noSession`/`ddl.droppedPartial`/`ddl.cancelled`，
      带占位符的用 `I18n.t(key, …).trim()` 取前缀，不再钉中文子串）；
      ⚠️ 重拍 p4-ddl 只证明「DDL 切面无回归」（46775 → 46562 字节，画面一致、纯渲染噪声）：
      TableDdl 的文案全在状态栏与重命名弹窗里，**没有任何帧覆盖**，本批只有单测在钉。
      补 `ADB_RENAME` 摆态（`ScreenshotSession.targetScene` 已支持拍 Dialog 独立 Stage）
      记为收尾待办，与第十六批的盲区同型
- [x] **N1-b 第十九批** `ObjectDetailsPanel`(27) + `ObjectHeader`(2)（29→0）：→ **298**，
      1238 键（新增 11 键 `details.*`，**复用 15 个既有键** —— 这批的复用率是十八批里最高的一次：
      类型标签 `detail.kind.table|view` + `object.kind.trigger|function|event` + `category.database`、
      节标题 `detail.indexes|foreignKeys|triggers` + `newtable.tab.fields`、
      动作 `action.object.open-data|open-structure`、列标记 `detail.primaryKey` + `grid.head.notNull`、
      最近操作 `grid.overview.recent`）。⭐ 复用一律按「zh 与 en 双侧全等」判，
      只有一边相同不算（`object.kind.procedure`=存储过程 ≠ 过程，另立 `details.kind.procedure`）。
      ⭐ **实拍抓到英文截字**：详情面板 300px 窄栏里三颗等分按钮被压成
      「Open D… / Open Struct… / Open D…」—— 中文「打开数据」四字撑得住，英文撑不住；
      页脚 HBox → FlowPane（装不下换行），复拍后三颗全读得完。
      与第十一列「自增列头 46px 截成 Auto …」同族：**列宽/按钮宽是按中文量的**，
      每批抽完必须按英文再目视一次。测试重钉 8 处（ObjectDetailsPanelTest 7 + ObjectHeaderAndTabsTest 1）
- [x] **N1-b 第二十批** `WorkspaceView`(25) + `SavedQueriesPage`(17) + `FavoritesPage`(11)
      + `SavedQueries`(2)（55→0）：→ **243**，1272 键（新增 34 键 `workspace.*`/`saved.*`/`fav.*`，
      复用 11）。⭐ `SavedQueries.Kind` 第四次用「存键、渲染时解析」。
      ⚠️ 本批起进入**零帧覆盖区**：settings / favorites / saved-queries 三个面在 shots.sh 里没有帧
- [x] **N1-b 第二十一批** `DiagnosticsPage`(24) + `ErrorDetails`(12) + `ErrorDetailsDialog`(1)
      + `CrashLog`(7)（44→0）：→ **199**，1302 键（`diag.*` 15 / `error.*` 15）。
      ⭐ `CrashLog` 的 7 条**不进包、改英文直述**：它是最后防线的未捕获异常写盘器，
      让崩溃路径依赖资源包等于「语言包坏了就读不到『语言包缺失』」；输出物是给开发看的。
      ⚠️ 该批留下一条 D-9 交互：`ErrorDetails.headline()` 的「分类名与说明重复就把驱动原文顶上来」
      比的是**文本**，而 `Diagnosis.summary()` 还是模型层中文 —— 英文界面下顶不上去了
      （改前英文徽标本身就是中文，同一笔债换个露法）。两条断言改在 `setLocale(CHINESE)` 下跑，
      没有降级成断言英文的退化输出。真正的修法是把 Diagnosis 改成「键 + 参数」，归 D-9
- [x] **N1-b 第二十二批** `ForeignKeyDialog`(23) + `TriggerDialog`(14) + `VariableDialog`(9)
      + `RoutinePage`(9)（55→0）：→ **144**，1333 键（新增 31，复用 13 —— 这批复用率最高，
      三个弹窗的「确定/取消/将执行/列/类型」几乎全能对上既有键）。
      两个结构弹窗共用的预览标题收成一键 `structure.preview.execute`，草稿态另立 `…inline`
- [x] **N1-b 第二十三批** `ColumnFilterPanel`(22) + `ConditionDialog`(18) + `ColumnSettingsPanel`(4)
      （44→0）：→ **100**，1363 键（新增 30，复用 8）。
      ⭐ **清扫教训**：本批全量跑到第三遍才红 —— `ColumnSettingsPanelTest` 钉的是
      **合成后的输出**「5 / 5 列」，源里没有这条整串，按「我换掉的源字面量」grep 抓不到。
      清扫必须把拼装结果的形状（`N / M 列`、`共 N 个…`）一起列进去
- [x] **N1-b 第二十四批** 开发者面 7 个文件（28→0）：→ **72**，无新键。
      `LucideConverter`(11) / `ScreenshotSession`(8) / `StartupTrace`(3) / `I18n`(2)
      / `Icons`(1) / `SettingsStore`(1) / `ThemeManager`(1) 全改英文直述 ——
      这些串只出现在 stderr、startup.log 与 `IllegalStateException` 里。
      跟着重钉 3 处（`StartupTraceTest` 两条「首屏 」、`LucideIconTest` 两条
      `hasMessageContaining("不是数字"/"正方形")`）；另清掉一处漏网全角「）」，
      否则「零汉字必须同时零全角」那条门禁当场变红
- [x] **N1-b 第二十五批（收口）** 13 个零散文件（72→8）：→ **8**，1398 键（新增 36，复用 15）。
      `TabKind` / `ThemeMode` 两个枚举改「存键、渲染时解析」（第四次同型）；
      `ActionRegistry` 的 6 条按第二十四批同规改英文（动作 id 重复 / 未注册 / 快捷键冲突都是编程错误）。
      ⭐ **终态是 8 不是 0**：`CellRenderer` 与 `CellValueParser` 的
      `case "1","true","yes","y","on","是","真"` 里，中文那几项是**用户真会打进格子的输入 token**。
      进包 → 布尔解析随界面语言变（同一份数据两种解释，比截字更坏）；
      挪模型层 → 只是把同一笔债从主尺转到 `MODEL_CJK_CEILING`；
      写 `\u662f` → 正是这条棘轮 javadoc 警告过的「改成假通过」。三条都否，留 8 条并写清理由
- [x] **R34 收官批（补帧 + 两个门禁看不见的缺陷）** 275 条零覆盖文案全部有帧：
      新增六个摆态 + 六帧 —— `ADB_SETTINGS`→p9-settings、`ADB_DIAGNOSTICS`→p9-diagnostics
      （诊断页原本只在「树里选中 → 动作」这条路上，`SideBar.poseToolForShots` 按路由开页，
      走的仍是同一个 `openTool`）、`ADB_FAVORITES`→p9-favorites（`poseStarForShots` 先收藏一张，
      否则新 prefs 节点里恒为空表）、`ADB_SAVED`→p9-saved-queries（摆两条真条目，
      `Kind` 标签才看得见）、`ADB_RENAME`→p6-rename-table（两轮轮询：连接 → 行灌完，
      空列表时 `renameSelected()` 只报「先选中一张表」）、`ADB_FILTER`→p2-filter。
      ⚠️ 列筛选那颗漏斗的 id 是动态拼的（`data-filter-<列>`），`ScreenshotScriptAuditTest`
      只认源码里字面存在的 `setId("…")`，脚本点不到 —— 所以它也要一个开关，由数据页
      `poseFilterPanel()` 自己 `fire()` 那颗按钮（浮窗内容与真点一致）。帧数 **34 → 40**（35 亮 / 5 暗）。
      ⭐ **目视抓到两个所有门禁全绿也拦不住的缺陷**：
      ① `details.title` 在两份资源包里各写了**两行**（第十三批 Cell Details / 第十九批 Object Details），
      `.properties` 后写覆盖先写，而键集相等那条门禁比的是 `Set` —— 单元格抽屉的标题被悄悄顶掉，
      25 批无感。对象面板改 `details.object.title`，新立第 14 条门禁
      `noDuplicateKeyLinesInEitherBundle`（先证「一行都没扫到 = 假绿」再查重）。
      ② 界面全英文、弹窗按钮却是「确定 / 取消」：那三颗文字**从来不在资源包里** ——
      `ButtonType.OK` 这类单例由 JavaFX 自己的 bundle 按 **JVM 默认 Locale** 解析，
      换台日文机器就出第三种语言。`DialogStyles.localizeStandardButtons` 统一覆写
      OK / CANCEL / CLOSE（新增 `common.close`），只认这三颗单例，
      业务弹窗的 `new ButtonType("添加", OK_DONE)` 一个字不动；`DialogButtonOrderTest` 两半都钉。
      ⭐ p2-filter 首拍抓到**列筛选浮窗按中文量的宽**：320px 下底部四颗按钮全截成
      「Select … / Deselect… / Cl… / Ap…」、浮窗标题裁成「…ter · name」→ pref 320→420 / max 340→460，
      复拍全读得完（与第十九批页脚截字同族，第三次）。
      ⭐ p2-error 假帧根因：错误弹窗走 `showAndWait` 的嵌套事件循环，实测 6 次冷启 2 次
      「window count=1」（弹窗压根没进 `Window.getWindows()`）→ 摆态改 `showForShots`（`show()` 不进嵌套循环）
      并打一行 `[screenshot] error dialog shown`，此后 3/3 + 复拍全命中；
      ⚠️ 根因只定位到「嵌套循环这一类」，没定位到为什么偶发 —— 真产品路径仍是 `showAndWait`，未改
- [x] **N1-c** `I18nAuditTest` **14 条**（键集相等 / **重复键行**（收官批新增）/ 调用点对账 / 动作命名穷举 /
      占位符 / 棘轮×2 / 全角标点 / 实参个数 / 模型层标签边界 / 偏好链 / configure 先于 AppShell）
- [x] **N2** 菜单组 `File/Edit/View/Query/Format/Favorites/Tools/Window/Help` + Table 上下文菜单按页型开合
- [x] **N1-d** 语言偏好启动生效（`-Dadb.locale` > 偏好 > 默认英文；运行时切换刻意未做 —— 需重建场景，独立一轮）
- [x] 窗口标题 Navicat 式 `<连接> @<库> (<对象>) - <页型> - <产品>`
- [x] **落库**：第八批 `46873b8`（代码+资源+测试+19 帧重拍）+ 文档重写（本提交）。
- [x] **P4** 冷启动复测（2026-10-30 实测，1398 键双语包）：**ResourceBundle 不是瓶颈 —— 判定通过**，
      但绝对值差一口气。`~/.agenticdb/logs/startup.log` 取 **34 次冷启**（= 一轮全量重拍的每一帧各一次启动）：
      **首屏 min 3661 / 中位 3904 / max 4192 ms**，而 `locale=` 这一段（含两份 .properties 读取与解析）
      **min 94 / 中位 104 / max 119 ms** —— 占首屏 **2.7%**，把包删了也省不出 3.9s 以内的余量。
      真正的大头按 trace 的中位**段时长**排：进程启动到 `trace.created` ≈ 968ms（JVM + 类加载，绝对值）、
      `shell.demo-db` ≈ **1288ms**、`shell.construct` ≈ 794ms、`stage.shown` ≈ 2786ms（含前面全部）。
      ⚠️ 所以「不得拖过 3.9s」这条**按字面判是没过**（中位 3904，超 4ms ≈ 0.1%），
      但超的部分与 i18n 无关，是既有的启动开销；要真降下来得单开一轮启动性能（不在 R34 范围）。
      ⚠️ 本条最初只量了 8 次就写进文档（min 3728 / 中位 3954），34 次的样本把中位拉低 50ms ——
      以后 P4 一律按「一轮全量重拍的启动数」取样本，不取随手的前几次。
- [x] **N1-b 盲区补帧**：第二十至二十五批共 275 条文案的零帧覆盖**已补**（收官批）——
      `ADB_SETTINGS` / `ADB_DIAGNOSTICS` / `ADB_FAVORITES` / `ADB_SAVED` / `ADB_RENAME` / `ADB_FILTER`
      六个摆态 + 六帧；`ScreenshotSession.targetScene()` 拍 Dialog / PopupWindow 独立 Stage 这条
      在 p9-favorites(282×575) / p9-saved-queries(339×455) / p6-rename-table(228×162) /
      p2-filter(342×474) 四帧上第一次全部走通
- [x] R34 收官验收：全量门禁回量 **1182 / 150 组 / 134 类 / 0 失败 / 48 跳过**（BUILD SUCCESS 实测）
      + 40 帧 md5 无重复 + 回填真源三处（README 帧表 / 本文 §0+§1 / 真源 §8）。
      ⚠️ 目视是**抽样**不是逐张：本轮亲眼看过的 = 六张新帧全部（p9-settings / p9-diagnostics /
      p9-favorites / p9-saved-queries / p6-rename-table / p2-filter）+ p2-error 两次（修前假帧、修后真帧）
      + p0-shell + p2-users；其余 30 帧本轮未逐张重看，只有脚本的尺寸/重复/与入库版对比三道闸在守。
      抓到 D-11..D-15 五条未修缺陷（见真源 §8 未结缺陷条）

### R35 · 彩色图标渲染层 + 资产（N3 N4，估 10–14 人日）

> 结构性改造：`LucideConverter.java:57` 压单 path、`Icons.java:326-327` 单 paint —— 多色不是缺资产是缺渲染层。

- [ ] **N3-a** `Icons.of()` 返回 `Node`；新增 `ColoredIcon`（`Group<SVGPath>` 逐 path 保留 paint）
- [ ] **N3-b** 资源命名空间 `icons/color/`；`IconButtons`/`AppBar`/`SideBar`/`TabBar` 适配
- [ ] **N4-a** Ribbon 12 枚 30px 多色渐变（实测色板见真源 §1.2）
- [ ] **N4-b** 树 16 枚四色（`#077ED5` 描边 + `#CDE8FF`/`#B8DFFF`/`#96D0FF`）
- [ ] **N4-c** 工具条 16 枚 + 连接磁贴 48 枚
- [ ] **P3** 暗色档同批出（否则暗色帧黑底黑标）
- [ ] 门禁改判：`LucideIconTest` 三条 + `IconSystemAuditTest`（SIZE_RIBBON 26→30）
- [ ] R35 验收：全量门禁 + 帧目视（图标是唯一变量，不动布局）

### R36 · 顶区与外壳（N5–N10，估 5–7 人日）

> ⚠️ 撤销 R32-D（`e595c1f`）。

- [ ] **N5-b 先还令牌债**：改 `tokens/tokens.json` → 重跑 `tools/GenTokens.java` → 再改 CSS（顺序不能反）
- [ ] **N5** 顶区 114 → **140px**（菜单 23 + Ribbon 91 + 1 + 页签 25 + 1）
- [ ] **N6** Ribbon 90px / 磁贴 70×90 / **12 格** / 图标 30px 上 + 标签下 / 激活 `#C0DCF3`+`#90C8F6` / 组间 `#DBDBDB`
      顺序：Connection · New Query · Table · View · Function · User · Others · Query · Backup · Automation · Model · BI
- [ ] **N6-b** 新增 `RibbonCapabilityAuditTest`（每格必须落到真实目标）
- [ ] **N7** 页签行只放页签；页签标题 `<对象> @<库> (<连接>)`（真源 §1.10）
- [ ] **N8** 工具带底色 `#F0F0F0`；**N9** 顶区两条 1px `#DBDBDB` + 状态栏段间 `#D7D7D7`；**N10** `#0078D7`
- [ ] 门禁改判：`TokenParityTest` / `AppBarTest` / `AppShellTest` / `ChromeMetricsTest`
- [ ] R36 验收：全量门禁 + 33 帧全重拍（顶区是全局变量）

### R37 · 导航树 + 对象清单 + 结构收敛（N11–N15 N32 N33，估 8–11 人日）

- [ ] **N11** 树行距 21 → **20px**（对象清单保持 21）
- [ ] **N12** 树分组改五组 `Tables/Views/Functions/Queries/Backups`，摘掉 `过程/触发器/事件`，去计数徽标
- [ ] **N32** ⭐ **库级树**：连接 → 库 → 分组三级；页签标题/状态栏带库名；SQLite 单库平铺待手册核对（P7）
- [ ] **N33** ⭐ **对象页二合一**：删 Objects 主页（药丸总览，Navicat 无此概念），Table List 扩展为唯一对象清单页
      ⚠️ 待拍板 P6；落地时同步删除/改判 Objects 主页的旧门禁
- [ ] **N13** 三个 navButton（查询历史/诊断/导入导出）移出树
- [ ] **N14** 对象清单 8 → **6 列**（`Name/Rows/Data Length/Engine/Modified Date/Comment`）⋆ 顺带收 D-3
- [ ] **N15** 对象工具条 7 键（`Open Table/Design Table/New Table/New Table with AI/Delete Table/Import Wizard/Export Wizard`）
- [ ] R37 验收：全量门禁 + `p0-shell` / `p6-tables` / `p5-connections` 并排帧目视

### R38 · 数据网格（N16–N20 N31，估 13–18 人日）

> ⚠️ 撤销 R32-G19b 的「整排退成图标」。

- [ ] **N16** 行首 → 行号+铅笔（开工前 `tools/Crop.java` 8× 复核帧 4 左缘行首槽，真源 §1.4 注记）；
      批量入口重建为行多选 + 右键 + 底部图标条，撤掉「1 row(s) selected…」批量条
- [ ] **N17** 当前格橙黄 `#FFB666`/`#FFFFB6` + 编辑盒（白底蓝框值全选）+ 行灰带 + **当前列表头 tint**
- [ ] **N18** 工具条溢出 → 右端 `>>` 折叠（可键盘到达）
- [ ] **N19** 工具条 → **13 具名键 + 右端 2 小键**（真源 §1.4 逐键清单）；表头第二行只放类型；
      `Fill with AI`/`Data Profiling`/`Data Generation`/`Data Masking`/`Create BI Workspace` 按诚实条款给说明页或真功能
- [ ] **N20** 底部栏 → 五键导航 + 页码框 + 齿轮 + 放大镜 + 视图切换；记录计数移状态栏
- [ ] **N31** ⭐ **Filter & Sort 生成器面板**：网格内嵌（替代 `ConditionDialog`+`ColumnFilterPanel` 弹窗形态）、
      Builder/Text 双模式、条件行（字段链接 + 操作符 + `<?>` 占位 + `and` 连接词）、`[+]`/`[Ω]`、
      Sort By 芯片、显式 `Apply Filter & Sort` + `Criteria Edited` 脏标记、工具条按钮按下态联动
      新增 `FilterPanelAuditTest`
- [ ] D-1 一并收：分页器可用宽度由外壳递入（详情浮层压住 90px 的老账）
- [ ] R38 验收：全量门禁 + `p2-*` 全族并排帧目视（新增带 Filter 面板帧）

### R39 · 查询编辑器 + 已保存查询 + 状态栏（N21–N25，估 9–12 人日）

- [ ] **N21** 查询工具带双行 62px `#F0F0F0`（行1 Save/Query Builder/Beautify SQL/Code Snippet/Ask AI▾；
      行2 查询名▾/库▾/▶Run▾/■Stop/Explain）；收敛我方多出的 Run All/Parameters/Clear/Save as Named Query/页首大标题/列类型 chips
- [ ] **N22** 语法配色重标定：关键字 `#097BF7` 粗 · 字符串 `#FF1744` · 注释 `#929292` · 普通 `#424242` · 表名下划线
      新增 `SqlSyntaxColorTest`
- [ ] **N23** 结果区三段：27px `Message/Summary/Result N` + 32px `Data/Info` + 网格（表头单行 `#E7E7E7`）
- [ ] **N24** 已保存查询：树 `Queries` 分组可展开出文件叶子 + 文件清单对象页（4 列 + `Design/New/Delete Query`）
- [ ] **N25** 状态栏三页型（查询页 6 段 / 数据页 左 SQL+右 Record·Last Refresh / 对象页 左计数+右连接·库）；
      删「自动提交·只读」段
- [ ] R39 验收：全量门禁 + `p3-*` 并排帧目视

### R40 · 弹窗 + 连接向导（N26 N27，估 5–7 人日）

- [ ] **N26** 弹窗去自绘标题条 → 系统标题栏（尺寸记忆随之取消，拍板 4 已接受）
- [ ] **N27-a** 类型页：9+13 复选侧栏 / 5 列彩色磁贴 / 搜索 / `New Connection with URI…` / Next·Cancel
- [ ] **N27-b** 常规页：900×700 / 示意图 / 右对齐标签 / Save password / 底部五键
- [ ] R40 验收：全量门禁 + `p5-*` / `p6-*` 弹窗族帧目视

### R41 · 设计器收口 + 字号梯度（N29 N30，估 3–5 人日）

- [ ] **N29** `Virtual` 列 / `Key Length`+`Binary` / `Key` 单下拉 / `Ask AI` 键 / `Number of Fields: 15`
- [ ] **N30** 字号 7 档 → **11/12/13 三档**；失去消费者的令牌删除
- [ ] R41 验收：全量门禁 + `p6-new-table-*` 族帧目视

### R42 · 能力面收尾（N28 N34，估 36–56 人日）—— 待拍板 P1

- [ ] **N28-a** View 对象页 + 视图增删改（2–3）
- [ ] **N28-b** Backup 转储恢复（4–6）
- [ ] **N28-c** Model ER 图（8–12）
- [ ] **N28-d** User 管理面（3–4）
- [ ] **N28-e** Automation 作业调度（6–10）
- [ ] **N28-f** BI 图表透视（10–15）
- [ ] **N34** 数据页 3 新面：Data Generation / Data Masking（真功能或说明页，1–2）；Create BI Workspace 并入 N28-f
- [ ] 暂缓期：对应格/键保留，点击给如实说明页（`RibbonCapabilityAuditTest` 兜底）

### 未结与文档债

- [ ] **D-1** 分页器被详情浮层压 90px → R38 N19/N20（外壳递可用宽度）
- [ ] **D-3** 表清单 1280 只剩 2 列 → 并入 R37 N14
- [ ] **D-4** README「已知限制」与能力表口径冲突
- [ ] **D-9** `StructurePlan.title()` 17 个中文标题（模型层 104 条）→ 独立一批：改「键+参数」由界面渲染
- [ ] **PG 真库联调**：补测试库，消环境跳过
- [ ] **并排帧体系**：`docs/screenshots/side-by-side/` 重做（旧两张过时）

### 边界外（本版仍不做）

- ⛔ jlink、自动更新、代码签名 —— 发布工程，与「像不像」无关
- ⛔ 提取 Navicat 原始图标位图（版权素材；拍板 2 选自绘）
- 🚫 Navicat Cloud / 协作 / 项目

---

## 2. 规划（轮次视图）

```
R30–R33 ✅ 已提交。功能资产保住；视觉裁定多数被推翻（真源 §3 台账）
R34     i18n 二十五批已收（棘轮 8 = 终态，已落库）→ 收官验收     ← 当前
R35     彩色图标渲染层 + 50 枚资产 + 暗色档             （10–14） 前置卡所有视觉
R36     顶区 140px / 12 格 Ribbon / 页签行 / #F0F0F0   （5–7）   ⚠️ 撤销 R32-D
R37     树 20px 五组 / 库级树 / 对象页合一 / 6 列 7 键  （8–11）  ⭐ N32 N33 本轮新增
R38     行号+铅笔 / 编辑盒+列头tint / >> / 13 键 / Filter 面板（13–18）⭐ N31 本轮新增
R39     查询双行+配色+结果三段 / 状态栏三页型 / Queries 页（9–12）
R40     弹窗系统标题栏 / 连接向导两页                   （5–7）
R41     设计器 Virtual+KeyLength+Binary+Key下拉 / 字号三档（3–5）
R42     能力面：Ribbon 6 面 + Data Generation/Masking   （36–56） 待拍板 P1
─────────────────────────────────────────────────────────────
R34–R41 小计 61–86 人日        含 R42 合计 97–142 人日
```

**排序理由**
1. **R34 收尾先行**：i18n 卡所有标签；「验收 ≠ 落库」红线生效中（第八批已当天落库）。
2. **R35 图标第二**：`Icons.of()` 返回类型一改四个调用点全要适配；在顶区前做，12 格直接用彩色图标。
3. **R36 顶区第三**：全局变量，改完之前拍的帧只作阶段目视。
4. **R37–R41 页面级**，按打开频率排：树/清单（含两处结构性收敛 N32/N33）→ 数据网格（含 N31 面板）→
   查询 → 弹窗 → 设计器。
5. **R42 独立在最后**：功能面不是视觉轮，可整体砍掉而不影响「看起来一样」。

**每轮固定验收口径**（真源 §7）：全量门禁 0 失败 + 新能力带行为门禁与变异验证 +
CSS 门禁连权重与声明顺序 + 涉视觉必重拍帧并**逐张目视并排帧** + md5 去重 + 回填真源三处 +
分批本地提交（**push 需单独授权**）+ 诚实条款。

---

## 3. 证据速查（9 张帧 → 条目）

| Navicat 帧 | AIDB 对照帧 | 关键差 | 条目 |
|---|---|---|---|
| `navicat17-query-editor-2026-10-28`（1920×1040） | `p3-query` | 双行工具带 / 语法配色 / 结果三段 / 状态栏 6 段 / 顶区 140 vs 114 | N5 N21–N25 |
| `navicat17-saved-queries-2026-10-28`（1920×1040） | `p6-tables` | 树 Queries 分组 / 文件清单 4 列 / `Design New Delete Query` / 对象页状态栏 | N7 N8 N12 N24 N25 |
| `navicat17-filter-sort-2026-10-29` **新**（1617×903） | `p2-users` | **Filter & Sort 内嵌面板** / 13 具名键 / 底部记录操作条 / 编辑盒+列头 tint | N17 N19 N20 N31 |
| `navicat17-tree-multidb-2026-10-29` **新**（1920×1040） | `p0-shell` | **连接→库→分组三级** / Queries 树叶子 / 数据页状态栏 / 页签标题 `@库 (连接)` | N7 N12 N24 N32 |
| `navicat17-object-list-2026-10-27`（1219×893） | `p6-tables` | 6 列 / 7 键带文字 / `New Table with AI` / `22 Tables` 页脚 | N14 N15 N33 |
| `navicat17-data-grid-2026-10-27`（1219×893） | `p2-users` | 行号+铅笔（行首槽实测源）| N16 |
| `navicat17-designer-fields-2026-10-27`（1219×893） | `p6-new-table-fields` | `Virtual` 列 / `Key Length` `Binary` / `Key` 单下拉 / 8 键 | N29 |
| `navicat17-conn-type-2026-10-27`（900×700） | `p5-connect-type` | 9+13 复选侧栏 / 5 列彩色磁贴 | N27-a |
| `navicat17-conn-general-2026-10-27`（900×700） | `p5-connect-general` | 示意图 / 右对齐标签 / 底部五键 | N27-b |
