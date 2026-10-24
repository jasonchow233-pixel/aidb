# EXEC-SUMMARY — AgenticDB 对外汇报版（一句话 + 全景）

> **日期**：2026-10-22（第九轮汇总）。
> **本文是把「架构 / Todolist / 截图 / Navicat 对标 / GitHub 开源对标 / UI 六维 / 还缺什么」
> 串成的一份对外汇报**。每个章节都指向对应的细节文档，避免与之漂移：
>
> | 主题 | 权威来源 |
> |---|---|
> | 执行清单 / 当前状态 / 门禁 | **`NAVICAT-TODO.md`**（唯一执行清单） |
> | 架构、线程、门禁 | `docs/ARCHITECTURE.md` |
> | 视觉规范 / 交互 / 弹窗 | `docs/UI-GUIDE.md` |
> | 界面差距 / 批次 | `docs/UI-ROADMAP.md` |
> | **Navicat 对拍 + 差距** | `docs/NAVICAT-COMPARE.md` / `docs/NAVICAT-GAP.md` |
> | **GitHub 开源数据库管理软件** | `docs/GITHUB-COMPARE.md`（🆕 本轮新增） |
> | 截图审计 | `docs/UI-AUDIT.md` |
> | 形态 / 状态规范 | `docs/UI-FORMS-AND-STATES.md` |
> | 阶段规划 | `docs/NEXT-STEPS.md` |
> | **本篇** | `docs/EXEC-SUMMARY.md`（🆕 本轮新增：对外汇报版） |

---

## 0. TL;DR — 一句话汇报

> **AgenticDB 是一款 Java 25 + JavaFX 25 原生桌面数据库客户端，目标是「Navicat 级的交互顺手程度」
> （对象树 → 双击表 → 数据页 → 编辑/保存，全程键盘可达），首发 SQLite，PostgreSQL / MySQL 走方言抽象层。**
>
> **2026-10-22 末的结论**：功能主线（P0–P6）已基本到位，**「能看 / 能改结构 / 能安全写 / 数值不漂」这一切骨架已经成立**；
> 界面专项 U-B1…U-B5 已落地、表级生命周期 T1–T4 已落地、注释读回 / 索引编辑 / 建表 / 清空 / 重命名 / 删表
> 已在第八轮补齐；当前唯一还值得优先烧脑的，是 **「对象能力（外键/触发器/事件/函数体）」+「网格进阶（冻结列/区域选择/结果分页）」+「连接管理收尾（多连接并行/属性页）」+「架构收尾（超时/线程池/线程）」**。
>
> **对外汇报的重点**（客户 / 老板 / 自己听一遍）：
>
> 1. **已完成（证据在门禁里）**：631 条测试 / 84 类 / 0 失败；数据可信度（`22.990000000000002` → `22.99`）修掉；
>    注释写读 round-trip（MySQL/PG）修掉；D1「点函数必报错」修掉；建表 / 清空 / 重命名 / 删表 / 索引编辑全部上线；
>    一键编译启动脚本上线；9 张真实窗口截图可复现。
> 2. **最大硬伤已清零**：数值显示、注释丢失、对象打开错了——全在第八轮处理，且每项都有「能抓回退」的门禁。
> 3. **最大结构差距已补**：Navicat「右键建表 / 设计表」整块从 0 到 T1–T4（弹窗，按 `UI-FORMS-AND-STATES.md` §4 裁定）落地。
> 4. **还缺什么**（按投入产出，按优先级）：外键/触发器/事件/函数体（B5-S2/S3/O1/O2）、冻结列/区域选择/结果分页（U-B6 G1/G4/Q5）、多连接并行/连接属性页（U2/U3）、语句超时/线程池/线程收尾（A10/A12/A15/A16）、空白态 E5–E9、入口一致性 X3–X7、分发 P7。

---

## 1. 项目架构（现状）

### 1.1 模块与分层（4 层，12 个模块）

```
core/adb-model + adb-safety + adb-sql
        ↓
workbench/adb-jdbc + adb-introspect + adb-session + adb-editing + adb-transfer
        ↓
ui/adb-ui-core + adb-ui-shell
        ↓
bootstrap/adb-desktop
```

### 1.2 关键不变量（被门禁守着）

| 不变量 | 落点 |
|---|---|
| 设计令牌唯一真相 | `tokens.json`（120 → **60 个**，全部被引用）→ `Tokens.java` + `theme-dark/light.css`；`TokenParityTest` |
| 动作唯一真相 | 菜单/工具栏/右键/命令面板 → `ActionRegistry`；`IconSystemAuditTest` / `MenuBarFactoryTest` / `ActionRegistryConvergenceTest` |
| JDBC 参数化 | 所有值走 `?`；标识符走方言引用（`Dialect.quote`） |
| 编辑按主键定位 | 无主键/视图只读；保存前 SQL 预览 → 单事务 → 失败回滚 → Toast 反向撤销 |
| 危险分级三层 | fail-closed 分类 + UI 二次确认 + **驱动侧复查**（`DbSession.execute(sql, danger)`） |
| 线程 | 全局单线程执行器 `adb-db`；UI 更新回 JavaFX 线程 |
| SQL 内核 | 自研 `SqlLexer/Splitter/Formatter/Brackets/Parameters`（`adb-sql`，不依赖 UI/JDBC） |

### 1.3 架构层已知缺口（详见 `NAVICAT-TODO.md` §10 / `ARCHITECTURE.md`）

A10 语句超时 / A12 连接池归还 / A15 连接与内省出 UI 线程 / A16 单执行器 / A18/A22 主题探测 / A25 达梦声明 —
— 属于「架构收尾」，用户感知不强但会卡界面。

---

## 2. Todolist（`NAVICAT-TODO.md`）结论

### 2.1 基线（2026-10-24 第二十一轮实测刷新；本文其余章节仍是第九轮快照）

- **门禁**：**712 条 / 92 类 / 0 失败**（`./mvnw -B clean verify` + `java tools/TestCount.java`；另有 44 条**环境门控**跳过 —— Docker / MySQL 真库环境变量不可用时跳过，不影响「0 失败」这条硬门禁）。
  - 相比第二十轮的 755 / 95，减少的 43 条 / 3 类来自**删除 `declarations` 声明层**，属主动减面。
  - 增长轨迹：SQLite **443** → 第八轮 **486** → 索引编辑 **512** → 第十三轮 **631** → 第十五轮 **675** → 第十六轮 **691** → 第十七轮 **721** → 第十八轮 **717** → 第十九轮 **731** → 第二十轮 **755** → 第二十一轮 **712**（删 declarations -43；总数随外部真库可用性浮动，**模块内数字是准的**）。
- **真库**：SQLite（演示库）+ **MySQL 5.7.44 真库**（9 例）+ **PostgreSQL 16 / MySQL 8.4 Testcontainers**（11 例，需 Docker）。
  - 权威当前状态见 **`NAVICAT-TODO.md` 表头 + §23 变更记录**，本文不逐轮重写。

### 2.2 本轮（第六 / 七 / 八 / 九）的推进

| 轮 | 主线 | 关键结果 |
|---|---|---|
| 第六（2026-10-21） | 全量核查 + 脚本 | 4 个缺陷（D1–D4）；脚本 `scripts/`；S5「对象」标签落地 |
| 第七（2026-10-21 晚） | 截图审计 | S1 数值格式化修；S2 refresh 修；B1+B2；D1 修；空态 E1–E4/E6 修 |
| 第八（2026-10-22） | 缺陷收口 + 表级生命周期 | D2+D4 注释读回；B5-S1 索引编辑；**T1–T4 表级生命周期**；8 张截图；`UI-FORMS-AND-STATES.md` 成立 |
| **九（2026-10-22）** | **对外汇报 + GitHub 对标 + 文档补** | 本篇 + `docs/GITHUB-COMPARE.md`；TODO/README/NEXT-STEPS/memory 同步 |

### 2.3 当前优先级（合并后，第九轮）

```
P0  ① D3：SQLite 改列丢失 CHECK（丢约束，数据层面损坏感知）   ← 待做
P1  ② B5-S2/B3：外键 / 触发器可编辑                               ← 待做
P1  ③ B5-S4/O1/O2：表注释 / 事件 / 函数定义体                        ← 待做
P1  ④ E5/E7/E8/E9：查询 0 行 / 侧栏搜不到 / 历史空 / 收藏空           ← 待做
P2  ⑤ U6：状态栏补充（用户 / 服务器版本 / 延迟）                  ← 待做
P2  ⑥ U-B6：G1 冻结列 / G4 区域选择 / Q5 结果分页 / Q6 代码片段     ← 待做
P2  ⑦ U1/U2/U3：catalog 树 ✅（第十四轮）/ 多连接并行 ✅（第十二轮）/ 连接属性页 ✅（第十二轮·续三）
P3  ⑧ X3–X7：入口与一致性打磨 / A10/A12/A15/A16 / P7 分发           ← 待做
```

---

## 3. 截图结论（`docs/screenshots/`）

### 3.1 现状：9 张 AgenticDB 截图 + 3 张 Navicat 实拍

```
docs/screenshots/           AgenticDB（TestFX 实拍，可复现）
  p0-shell.png              外壳
  p2-users.png              数据页（users）
  p3-query.png              查询页
  p4-structure.png          结构页
  p5-connections.png        连接管理
  p5-dark.png               暗色主题
  p6-tables.png             表清单（8 列；ADB_TABLES=1）
  p6-new-table.png          新建表弹窗（ADB_NEW_TABLE=1）
  p7-new-index.png          新建索引弹窗（ADB_INDEX=1）
docs/screenshots/navicat/  Navicat Premium Lite 15 实拍
  navicat-tablelist.png / navicat-toolbar.png / side-by-side-2026-10.png
```

### 3.2 审计结论（`docs/UI-AUDIT.md`，基于 4 张图 + 代码定位）

| 批 | 内容 | 状态 |
|---|---|---|
| B1（P0） | **S1**：`amount` 显示 `22.990000000000002` | ✅ **已修**（`NumberFormattingTest` 6 例） |
| B2（P0） | **S2** refresh 按钮死 UI；**D1** 对象首页函数必报错；**X1/X2/E1–E4/E6** 空态 | ✅ **已修**（`ObjectHomePageTest` 5 例 + `EmptyState`） |
| B3（P1） | **M1–M12** 一致性打磨（版本号 / 耗时重复 / 连接表空值 / 措辞 / 每页下拉 / 类型行…） | ⬜ **待做** |
| B4（P1） | **T1–T4** 表级生命周期（新建表/清空/重命名/删表） | ✅ **已修**（`NewTableDialog`/`TableDdl`/`StructurePlanRunner`，T1=弹窗裁定） |
| B5（P2） | 索引/外键/触发器/表注释/事件/函数体 | 🟡（**S1 索引已修**，S2/S3 待） |

### 3.3 ⚠️ 方法学声明（诚实项）

> 第八轮的截图结论来自 `docs/UI-AUDIT.md` 的四张图审计 + 代码定位。
> **2026-10-22 第十三轮已补上「直接看图」**：对 `navicat-tablelist.png` 与
> `p0-shell.png` / `p6-tables.png` 做了像素采样（侧栏宽度、行高、表头高度、色值、
> 空行假线），结论与改动见 **`docs/NAVICAT-PARITY-2026-10-V5.md`**。
> 重拍流程在 `scripts/shots.sh`（`ADB_SHOTS*`、`ADB_OPEN`、`ADB_TABLES`、`ADB_NEW_TABLE`/`ADB_INDEX`）。
> **对外汇报前仍建议用户肉眼复核 9 张图**。

---

## 4. Navicat 对标结论（`docs/NAVICAT-COMPARE.md` / `NAVICAT-GAP.md`）

### 4.1 第一轮（2026-10-21 实拍）关掉的差距

- **S5**：对象首页常驻「对象」标签 → ✅ 已落地（`TabBar.openPermanent`）。
- **S1**：顶栏大图标 + 文字 → ✅ 已落地（`MainToolBar`）。
- **S2**：表清单带元数据列 → ✅ 部分（名称/行数/数据长度/引擎/创建日期；**注释/修改日期/排序规则待**）。
- **S3**：对象树五类分组 → ✅（表/视图/过程/函数/触发器）。
- **S4**：侧栏底部搜索 + ★ + « → ✅。
- **S6**：收藏夹菜单 → ✅。
- **S7/S8**：窗口状态 / 标签右键 → ✅。
- **G-a–G-e**（第三轮）：建表 / 清空 / 重命名 / 删表 / 索引编辑 / 注释 / D1 → ✅ 已落地（T1–T4 + IndexDialog + 注释读回）。

### 4.2 仍未对上的（诚实清单）

| 项 | Navicat | AgenticDB | 处置 |
|---|---|---|---|
| **表清单元数据列**（修改日期 / 排序规则 / 注释） | 8 列 | **chip 布局，0 列** | 产品决策（chip vs 表格）：见 `NAVICAT-COMPARE.md` §6.2 D1 |
| **对象工具栏**（表列表上方图标条） | ✅ | 结构页有，对象首页没有 | 随 chip→表格决策 |
| **外键 / 触发器可编辑** | ✅ | 只读 + 无右键菜单 | B5-S2/S3 |
| **函数 / 过程 / 事件定义体** | ✅ | 名字 / 报错 | O1/O2/O3 |
| **视图增删改** | ✅ | 只读 | V1 |
| **ER / 权限 / 计划任务 / 传输同步** | ✅ Premium | 明确不做 | 维持不做 |

### 4.3 我们**更好**或**有意不同**的地方

- 危险色四级 + 危险门禁**三层**（Navicat 无第三层）✅；
- 密度三档 + 暗色主题 + 真库联调（Navicat 闭源无从验证）✅；
- 图标 38 个 SVGPath + 语义唯一门禁 ✅（比 Navicat 的位图更可访问）；
- 设计令牌 60 色全部被引用 ✅。

---

## 5. GitHub 开源数据库管理软件对照（`docs/GITHUB-COMPARE.md`）

### 5.1 调研结论（2026-10 检索）

- GitHub topic `database-management` / `database-gui` + Awesome Database Tools（mgramin）下主要候选：
  - **DBeaver**（`dbeaver/dbeaver`，Java，GPL）：**开源数据库工具事实标杆**，功能最全（ER 图 / AI / 导入导出 / BI / 仪表盘 / 驱动）。
  - **Chat2DB**（`OtterMind/Chat2DB`，Java/Spring，Apache）：AI 驱动的在线 DDL 变更与数据管理。
  - **Beekeeper Studio**（`beekeeper-studio/beekeeper-studio`，TS/React/Electron，MIT）：现代 UI、网格编辑、导入导出。
  - **TablePro**（`TableProApp/TablePro`，TS/React/Electron，MIT）：原生桌面、简洁网格。
  - **sqlpad**（`rickbergfalk/sqlpad`，TS/React）：私有云 SQL 编辑器。
  - **SQLTools**（`mtxR/vscode-sqltools`，TS/VSCode）：VSCode 插件式 DB 管理。
  - 以及 Adminer / HeidiSQL / TOra / Mathesar 等（Web / 语言 / 形态不同，不作正面比较）。
- **对 AgenticDB 的意义**：GitHub 开源里，**「Java + 纯原生桌面（JavaFX，不内嵌 WebView、不启 HTTP）」**的竞品几乎没有——
  - DBeaver 是 Java，但底座是 Eclipse RCP，且支持云部署；
  - Beekeeper / TablePro 是 Electron（内嵌 Chromium）；
  - 其余多为 Web / VSCode / PHP。
  - **AgenticDB 的差异化「三角」（原生 JavaFX × 设计令牌 × 安全三层 + 方言抽象 + 真库契约）在开源对面几乎独一份。**

### 5.2 AgenticDB 对这些候选的借鉴项（进入 TODO 视角）

| 候选 | 可借鉴项 | AgenticDB 状态 |
|---|---|---|
| DBeaver | 结构设计器 / ER / 导入导出 wizard / 命名变量 / 结果分页 | ER 不做；导入导出已有；**Q5 结果分页待** |
| Beekeeper Studio | 现代 UI 交互范式（命令面板 / 快捷键 / 空状态） | 已自研实现（U-B1..B5） |
| Chat2DB | AI 辅助（AgenticDB 明确不做 AI） | 不做 |
| TablePro / sqlpad | 桌面外壳形态 | 自研 |

---

## 6. 六维全景 — 用户关心的「UI / 功能 / 交互 / 架构 / 按钮 / 弹窗 / icon / 配色 / 图标 / 菜单 / 状态栏 / 使用流程 / 默认页面 / 默认数据 / 空白页面 / 查询/事件/函数/视图 / 索引 / 建表 / 编辑表 / 备注 / 排序 / 搜索 / 筛选 / 树形列表 / 一键编译启动脚本」

### 6.1 矩阵

| 维度 | 我们现状 | Navicat 对照 | 结论 / 欠缺 |
|---|---|---|---|
| **UI（视觉）** | 令牌 **90** 个全引用；fs/sp/r 阶梯；focus 30 条；icon **44** 个 SVG；双主题；**已有可照着实现的规格书**；**对比度有门禁** | 同形 | ✅ 规格见 `docs/UI-VISUAL-SPEC.md`；本轮补齐 **43 个未定义样式类** + 遮罩/投影令牌 + **6 个对比度不足的色值** |
| **功能（主流程）** | 连接→对象→数据 全通；编辑/保存/撤销/批量/导出 | Navicat 同级 | ✅；缺：G1/G4/Q5/Q6/U1–U3/U6 |
| **交互** | ActionRegistry 收口；⌘K；禁用反馈；Toast 三档 | Navicat | 🟡（X4/X5 半孤儿） |
| **架构** | 四层干净；方言抽象；三层门禁 | 闭源无从比 | 🟡（A10/A12/A15/A16/A18/A22/A25） |
| **按钮** | 功能栏大图标；数据页图标条（分组/记录导航/列头筛选悬停/连接表/密度） | Navicat 同形 | ✅（U-B5 已落地） |
| **弹窗** | 14 类；`DestructiveConfirm`；**新建表=弹窗**（裁定）；T2/T3/T4 弹窗 | Navicat | ✅（T1–T4 已落地） |
| **icon（图标系统）** | 38 SVGPath；`☰` 拆 `⚙`；Tooltip+accessible；语义唯一 | Navicat 位图 | ✅（U-B4 已落地） |
| **配色** | 双主题；风险四级；60 令牌 | Navicat | ✅（缺 overlay/shadow） |
| **图标（符号语义）** | SVG；Unicode 全清 | Navicat | ✅ |
| **菜单** | 8 顶级（文件/编辑/查看/数据库/收藏夹/工具/窗口/帮助）；49 动作 | Navicat | 🟡（X4/X5） |
| **状态栏** | 10 字段，9 真；**版本号仍硬编码 `v0.1.0 · P3`**（`StatusBar.java:35`，未修） | Navicat | 🟡（M1，B3 待） |
| **使用流程** | 一致；⌘K 兜 | — | ✅ |
| **默认页面** | 常驻「对象」标签；`restoreLastTabs` 会盖掉它（X7） | Navicat 对象首页 | 🟡（X7） |
| **默认数据** | 演示库 users 200 / orders 500 / products 80 / notes 空 + 视图 + 触发器 + 索引 | — | ✅（开箱可验收） |
| **空白页面** | E1–E4/E6 修；**E5/E7/E8/E9 待补** | Navicat 引导 | 🟡（E5–E9） |
| **查询/事件/函数/视图** | 查询 Q1–Q4 ✅；**事件 O1 未建模；函数定义体 O2 未做；视图只读** | Navicat | 🔴/🟡 |
| **索引** | **S1 索引编辑 ✅**（B5-S1）；外键/触发器只读 | Navicat | 🟡（S2/S3） |
| **建表** | **T1 ✅**（弹窗 + 实时 SQL 预览 + 表注释）；T2/T3/T4 ✅（清空/重命名/删表） | Navicat | ✅（B4 落地） |
| **编辑表（列）** | 加/改/重命名/删列 + 注释 + CHECK 内省缺（D3） | Navicat | 🟡（D3 待） |
| **备注（注释）** | **D2/D4 已修**（列注释读写 round-trip）；**表注释 S4 待** | Navicat | 🟡（S4 待） |
| **排序 / 搜索 / 筛选** | 3 级排序下推；列头值筛选；⌘F 本页；列拖拽（G2 ✅） | Navicat | ✅ |
| **树形列表** | 5 类分组 + 多连接同树 + 收藏 | Navicat Premium | ✅（S3/S5） |
| **一键编译启动脚本** | `scripts/dev.sh` / `build.sh` / `shots.sh` / `_env.sh` ✅ | — | ✅（第六轮） |

### 6.2 关键矛盾 / 漂移（「补充完善文档」的价值）

- `NAVICAT-TODO.md` §13.5 的 U-B5 条目曾是 `- [ ]]`（未做），实际已全落地（UI-ROADMAP / NEXT-STEPS 已标 ✅）→ **文档漂移**，已于本轮纠正思路待提交。
- `docs/NAVICAT-GAP.md` §7.3「本轮新发现的 4 个真实缺陷」中 D1/D2/D3/D4 的某些状态与代码对不上 → **已纠正**（第六轮逐文件复核）。

---

## 7. 还欠缺什么？（汇总，去重）

按「用户价值 × 实现难度」排序：

1. ~~**外键 / 触发器可编辑**（B5-S2/S3）~~ —— ✅ **第十一轮已做**（两个弹窗 + 三方言 DDL + 真库往返门禁）。
2. ~~**函数 / 过程定义体**（O2）~~ ✅ **第十一轮·续二已做**（`RoutinePage` 只读标签页，三方言来源不同）；
   **事件 EVENT（O1）** 仍待做（只有 MySQL 有，PG/SQLite 无 → 对象树要按方言显示）。
3. **冻结列 / 单元格区域选择**（G1/G4，P2）—— 两表四路同步难点。
4. **查询结果分页**（Q5，P2）；**代码片段库**（Q6，P2）。
5. ~~**多连接并行（U2）**~~ ✅ **第十二轮已完成**（会话复用）；~~**catalog 树（U1）**~~ ✅ **第十四轮已完成**（虚拟连接 + 对象树库一层）；~~**连接属性页（U3）**~~ ✅ **第十二轮·续三已完成**。
6. ~~**空白态 E5–E9**~~ ✅ **第十轮已补齐**（E5/E7/E8/E9 + `EmptyStateTest`）；**状态栏 U6**（P2，仍待做）。
7. **入口一致性**：X5/X6/X7 ✅（第十二轮）；X3/X4 仍待做。
   **架构收尾**：A10/A12/A18/A22 ✅（第十二轮）；**A15/A16（线程模型）· A25 仍待做**；**分发 P7**（P3）。
8. ~~**表注释（S4）/ CHECK（D3）**~~ —— ✅ **都已修**（第十一轮：CHECK 内省 + 展示 + 重建搬运 +
   门禁断言「约束仍生效」；表注释读得回 / 改得了 / 清得掉）。
9. **界面专项剩余**（第十轮未做，已在 §18 标「暂缓」）：V-5 工具栏按宽度折叠 · V-6 应用图标 ·
   V-9 表格规格统一（类型行 / 记录导航条）· V-11 弹窗宽度三档 · V-12 错误详情弹窗 / 批量汇总 / 错误归类。

---

## 8. 本轮（2026-10-22）交付物清单

1. `docs/EXEC-SUMMARY.md`（🆕 本文件）：对外汇报版，串联架构 / Todolist / 截图 / Navicat / GitHub / 六维 / 还缺什么。
2. `docs/GITHUB-COMPARE.md`（🆕）：GitHub 开源数据库管理软件对照 + AgenticDB 独特定位 + 借鉴清单。
3. `NAVICAT-TODO.md`：**状态基线更新（527/68）**、U-B5 漂移纠正、第七/八轮结论固化、`§17` 新增「GitHub 对标 + 对外汇报」、`§18` 新增「UI/UX 细化规格」（V-1…V-12 + S-1…S-7）。
4. `README.md`：文档地图补 `EXEC-SUMMARY.md` / `GITHUB-COMPARE.md`，截图清单更新（9 张 + navicat/ 3 张），门禁基线更新。
5. `docs/NEXT-STEPS.md`：基线与下一动作同步更新。
6. `.workbuddy/memory/2026-10-22.md`：第九轮记忆补记。
7. **第十轮（2026-10-22）**：
   - 新增 **`docs/UI-VISUAL-SPEC.md`** —— 可照着实现的 UI 规格书
     （9 档字号 → 角色映射 · 90 色语义 · 44 图标清单 · 间距/圆角/固定尺寸 · 六区与四种页面骨架 ·
     组件规格 · 弹窗骨架与按钮区规则 · 交互态矩阵 · 键盘表 · 文案措辞 · 缺口清单）。
   - **实测发现 43 个「Java 用了但 CSS 没定义」的样式类**（结构页 / 连接页 / 5 个向导 / 详情抽屉…），
     以及 2 个**摆设令牌**（`row-tree` / `kind-index`）—— 后者让 `TokenParityTest` **直接失败**（门禁红）。
   - 已修（R-1…R-7）：`row-tree` 令牌化、`INDEX` 钥匙图标 + 结构页索引行首图标、
     `.adb-error`（表单错误显红）、删 3 条 chip 时代死样式、`.adb-menu-item` / `.adb-toolbar__separator`
     / `.adb-object-list__number` 从死规则变成真使用、对象清单数字列去内联样式。
   - **新门禁 `StyleClassCoverageTest`（4 例）**：Java 用到的 `adb-*` 类必须在 CSS 有规则
     （43 个已知缺口进白名单，只拦新增）；CSS 死样式；白名单不得留已修条目。
   - **第十轮收尾**：S-1…S-6 **全部补齐**（43 → **0**，白名单清空）——
     结构页 / 连接页 / 5 个向导 / 表单 / 抽屉 / 状态栏 / 侧栏 / 网格 / 空态全部有样式了；
     **V-1/V-2/V-3/V-4/V-7/V-8/V-10 完成**（数字右对齐 · 遮罩投影令牌 · 版本号取自构建产物 ·
     加载态按钮 · 图标尺寸档 · 对比度门禁 · 4 处空态）；
     新门禁 **`ContrastTest` / `StatusBarTest` / `EmptyStateTest`**（+10 条 / +3 类），
     并据此调了 **6 个对比度不足的色值**（亮色三级文字 3.03:1 → 4.92:1）。
     门禁：**585 条 / 77 类 / 0 失败**。
   - 门禁：**585 条 / 77 类 / 0 失败**（新增门禁后 +4 条）。

---

## 9. 对外汇报要点（可直接用于汇报 / 邮件 / PPT）

- **一句话**：「AgenticDB 已从『能跑』跨到『能信』+『能改结构』；Navicat 对标的最后一块功能骨架（建表/设计表）补齐，数值显示与注释丢失这类丢数据缺陷全部清零。」
- **证据**：517 测试 / 65 类 / 0 失败；`NumberFormattingTest` / `ColumnCommentTest` / `IndexDdlTest` / `TableLifecycleSqlTest` / `NewTableDialogTest` / `StyleClassCoverageTest` 等「回退注入验证」门禁。
- **当前最大风险**：架构层（A10/A12/A15/A16）与对象能力（B5-S2/S3/O1/O2）——前者影响体验，后者影响产品完整度。
- **下一步（建议顺序）**：D3（CHECK）→ B5-S2/S3 → B5-S4/O1/O2 → E5–E9 → U-B6 G1/G4/Q5 → U2/U3 → X3–X7 → A10/A12/A15/A16 → P7。
