# ROADMAP-TODOLIST — 开发 Todolist 与规划（执行视图）

> **定位**：`NAVICAT-TODO.md`（唯一真源，2026-10-29 第三次重写）的可勾选执行清单。
> 编号 N1–N34、实测值、成本、门禁改判全部继承真源 §2/§1/§5；状态变更**两处同步**。
> 本版依据：2026-10-29 用户令「忘掉旧对比、9 张 Navicat 实拍（含 2 张 10-29 新帧）逐张重比、重写规划」。
> 上一版（2026-10-28）的规划口径作废；R34 十个批次的执行日志保留在本文 §1.1（第八批已随 `46873b8` 落库）。

---

## 0. 当前基线

- 门禁 **1180 条 / 134 类 / 0 失败 / 48 环境跳过**（第十批后 `./mvnw -o test` 实测 —— 与第八/九批持平：
  第九、十批只降棘轮、未立新门禁；第八批曾恰比第七批的 1179 多 1，即第八批新立的对象树菜单文字对账门禁）。
  **量法**：`grep -aoP 'Tests run: \K[0-9]+(?=, Failures: [0-9]+, Errors: \d+, Skipped: \d+, Time elapsed)'`
  对全量日志求和 → 150 组 / 1180 条；类数按 `**/target/surefire-reports/*.txt` 数 = 134
  （150 与 134 之差是 `@Nested`：一行一组、一份报告）。
  ⚠️ 全量必须 JDK 25+；`JAVA_HOME` 留在系统默认 21 会被 enforcer 直接 BUILD FAILURE
  （本机 `C:\Users\Administrator\.jdks\openjdk-27.0.1.0.2`）。
  ⚠️ 此前文档记过 1116/1119/1122/1173/1174/1175/1176 —— 都是两种口径混用；以后照上面那条命令量。
- 已落库提交：`34ffb8f`（R33 全部 + R34 首批）· `288592d`（N1-d）· `05351ce`（N2）· `87d2ddb`（窗口标题）·
  `c6ae488`/`2f85990`（第三批）· `3077924`/`bd2640b`（第四批）· `534ebe9`/`91c4084`/`e7c6543`（第五批）·
  `7e8b6f7`/`82ea100`（第六批）· `88a4576`/`ff2e7d4`（第七批）· `46873b8`（第八批 + 本文重写）·
  `f0c1044`（第九批）· `6e99d60`（第十批）。**push 仍未授权。**
- 验收帧 33 张（28 亮 / 5 暗），第八批后全量重拍为英文档（19 帧变化、1280×800、md5 无重复帧）。
  中英混排仍是中间态：`NewTableDialog`(75) / `ImportWizard`(61) / `CellDetailsDrawer`(39)
  等 47 个文件未抽（第十批重拍 p0-shell，对象首页已全英）。
- 棘轮 **718**（主尺，只许降）；模型层 **277**（第 13 条棘轮，D-9）。资源包 **975 键** en/zh 对称。
- 总账：**R34–R41 = 61–86 人日**；**+R42 = 97–142 人日**（本轮新增 N31–N34 后上调，见 §2）。

---

## 1. Todolist

### R34 · i18n 层 + 默认英文（N1 N2，估 8–12 人日）—— 进行中

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
- [x] **N1-c** `I18nAuditTest` **13 条**（键集相等 / 调用点对账 / 动作命名穷举 / 占位符 / 棘轮×2 /
      全角标点 / 实参个数 / 模型层标签边界 / 偏好链 / configure 先于 AppShell）
- [x] **N2** 菜单组 `File/Edit/View/Query/Format/Favorites/Tools/Window/Help` + Table 上下文菜单按页型开合
- [x] **N1-d** 语言偏好启动生效（`-Dadb.locale` > 偏好 > 默认英文；运行时切换刻意未做 —— 需重建场景，独立一轮）
- [x] 窗口标题 Navicat 式 `<连接> @<库> (<对象>) - <页型> - <产品>`
- [x] **落库**：第八批 `46873b8`（代码+资源+测试+19 帧重拍）+ 文档重写（本提交）。
- [ ] **P4** 冷启动复测：ResourceBundle 不得把首屏拖过 3.9s
- [ ] R34 收官验收：全量门禁回量 + 33 帧全英文逐张目视 + md5 去重 + 回填真源三处

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
R34     i18n 十批已收（棘轮 718，已落库）→ 收尾 1–3          ← 当前
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
