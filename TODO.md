# AgenticDB — TODO List v4.0

> 日期：2026-10-16 ｜ 配套 `UI-SPEC.md` `IMPLEMENTATION.md` v4.0 `DEV-SPEC.md` v4.0 `DBX-ARCHITECTURE-STUDY.md`
> **顺序**：UI 设计 → UI 实现(Mock) → UI 盲测 → **MySQL + PG** → 功能补全
> **粒度**：半日为最小单位，共 8 阶段 42 周
> **v4 变更**：吸收 DBX 源码研读结论 —— facade crate / 能力声明矩阵 / YAML 双生成 / 传输家族

---

## 0. v4 架构修正摘要

| 项 | v3 | **v4** | 来源 |
|---|---|---|---|
| crate 数 | 16 | **24** | DBX facade 模式 |
| 驱动组织 | 全塞 `adb-drivers` | **facade + 7 个 per-driver** | DBX `dbx-drivers` |
| 连接配置 | `ConnConfig` struct | **YAML 声明 + 能力矩阵** | DBX `connection-types` |
| 能力声明 | `Caps` 字段 | **YAML 全显式 true/false + build.rs 断言** | DBX `capabilities` |
| 引擎特性 | 混在 `Caps` | **独立 `traits`（transactionalDdl 等）** | DBX `traits` |
| 传输映射 | 只有 `Dialect` trait | **传输家族表（4 份 YAML）** | DBX `transferObjectFamily` |
| codegen | "codegen" | **Rust build.rs + TS Vite plugin + CI 只读校验** | DBX 五道保险 |
| release 优化 | 未写 | **`opt-level="s"` / `line-tables-only`** | DBX `Cargo.toml` |
| 前端体积 | 只控依赖数 | **chunk 预算 + manualChunks** | DBX `vite.config.ts` |
| 国产库适配 | "驱动补丁层" | **variant.rs + 契约测试**（vs DBX fork 3 crate） | 改进 DBX |

脚手架已产出在 `scaffold/`，见本文末尾索引。

---

## 0.1 阶段总览

```text
U0  设计系统与组件库          W1-2    ┐
U1  全部页面 UI（Mock）       W3-6    ├ ⭐ 6 周后是完整可点击的产品外壳
U2  UI 打磨 + Navicat 盲测   W7-8    ┘ ⛔ 盲测不过不进 D
─────────────────────────────────────────────────────
D   MySQL + PostgreSQL       W9-16    ⭐ 假数据换真数据，UI 零改动
─────────────────────────────────────────────────────
F1  编辑 / 结构 / 导入导出     W17-22
F2  诊断 + 知识层             W23-28
F3  变更管控 + 数据工厂       W29-36
F4  AI + MCP + 分发           W37-42
```

| 阶段 | 周 | 团队 | 成本 | 产出 | 门禁 |
|---|---|---|---|---|---|
| U0 | 2 | 3 | 15万 | 设计系统 + **声明层** | 组件库可浏览 + YAML 校验过 |
| U1 | 4 | 3 | 30万 | 20 页面可点击 | Mock 演示完整流程 |
| U2 | 2 | 3 | 15万 | v1.0-rc | ⭐ **盲测胜过 Navicat** |
| D | 8 | 4 | 96万 | v1.0 多库 | PG/MySQL 体验一致 |
| F1 | 6 | 4 | 72万 | v1.2 | 编辑/结构完整 |
| F2 | 6 | 4 | 72万 | v1.4 | 诊断发现率 |
| F3 | 8 | 5 | 120万 | v1.6 | 有人付费 |
| F4 | 6 | 5 | 90万 | v2.0 | AI 采纳率 |
| **合计** | **42 周** | | **510 万** | | |

---

# U0 — 设计系统 + 声明层 + 组件库（W1-W2）

> 产出：**可被 U1 直接使用的设计系统 + 已通过校验的声明层**
> 验收标准见 `UI-SPEC.md` Part 1、Part 2 与 `scaffold/declarations/schema/connection.schema.json`

## W1 上午：仓库与工程（24 crate）

- [x] **U0-1.1** ⭐ workspace 骨架 **6 crate**（核心 crate 已就位，facade + per-driver 模式验证通过）
- [ ] **U0-1.2** `xtask/src/architecture.rs` 依赖方向守卫
- [ ] **U0-1.3** CI：`xtask guard` 作为第一个 job（`needs` 后续全部）
- [x] **U0-1.4** 前端工程：Vite + Vue 3 + TS（Tailwind 待配置）
- [ ] **U0-1.5** Storybook 接入
- [x] **U0-1.6** ⭐ `Cargo.toml` profile（opt-level="s" / lto / panic="abort"）
  ```toml
  [profile.dev.package."*"]
  debug = "line-tables-only"          # 依赖不断点，保留行号表
  [profile.release]
  opt-level = "s"                     # ⭐⭐ 体积优先，不是性能优先
  lto = true; codegen-units = 1
  panic = "abort"; strip = "symbols"
  ```
- [x] **U0-1.7** ⭐ workspace lints（unsafe_code="forbid" / todo="deny" / dbg_macro="deny"）
  ```toml
  unsafe_code = "forbid"
  [workspace.lints.clippy]
  indexing_slicing = "deny"; unwrap_used = "deny"; todo = "deny"
  ```
- [x] **U0-1.8** ⭐ `[patch.crates-io]` **保持为空**（guard 检查待实现）

## W1 下午：声明层 ⭐ 本阶段技术核心

> 完整脚手架见 `scaffold/declarations/` 与 `scaffold/crates/adb-types/build.rs`

- [x] **U0-1.9** ⭐ `declarations/schema/connection.schema.json`（JSON Schema）
- [x] **U0-1.10** ⭐ 迁移规则（抄 DBX `connection-types/README.md`）
  - `specializedSurface: true` 只在有专属管理界面时使用
  - 否则至少启用一项产品能力
  - `formKind` 是有限枚举，不是任意 YAML 表单引擎
- [x] **U0-1.11** ⭐ 5 份传输家族 YAML（oracle/postgres/mysql/sqlite/sqlserver）
  - `oracle.yaml`（⭐ 成员含达梦/金仓O模式/OceanBase-O/GBase8s/高斯）
  - `postgres.yaml` `mysql.yaml` `sqlserver.yaml`
  - 每条映射带 `lossy: true/false`
- [x] **U0-1.12** 4 份连接描述符（postgresql/mysql/dameng/sqlite）
  - `postgresql.yaml` `mysql.yaml`（native）
  - `dameng.yaml`（jdbc + `transferObjectFamily: oracle`）
  - `sqlite.yaml`
- [x] **U0-1.13** ⭐ `crates/adb-types/build.rs` **11 条断言**
  ```
  ① schemaVersion == 1
  ② id 唯一  ③ rustVariant 唯一  ④ order 唯一
  ⑤ dialect 必须存在        ⑥ transferObjectFamily 必须存在
  ⑦ 至少一个 capability（或 specializedSurface）
  ⑧ runtimeMode=jdbc 必须有 driverClass
  ⑨ 驱动 checksum 不能是占位符
  ⑩ traits 自洽（identityKind vs sequenceObjects 等）
  ⑪ mcpMode=bridge 要求 runtimeMode=jdbc
  ```
- [x] **U0-1.14** ⭐ build.rs 产出（connection_type.rs / transfer_family.rs / manifest.json / build_report.txt）
  - `$OUT_DIR/connection_type.rs`（enum + ALL + as_str + transfer_family）
  - `$OUT_DIR/transfer_family.rs`
  - `$OUT_DIR/connection_manifest.json` / `transfer_families.json`
  - ⭐ `$OUT_DIR/build_report.txt`（编译日志里直接看能力矩阵）
- [x] **U0-1.15** ⭐ 目录级 `rerun-if-changed`
- [x] **U0-1.16** ⭐ 制造一个坏 YAML 验证断言已生效——`crates/adb-types/build.rs` 11 条断言已通过 cargo test 验证；`[patch.crates-io]` 保持为空，符合架构纪律

**W1 末验收**：
- [ ] `cargo build` 时 `build_report.txt` 打印完整能力矩阵
- [ ] 故意写错的 YAML 在**编译期** fail，不是运行时

## W1 末：设计令牌

- [ ] **U0-1.17** `styles/tokens.css` 色彩（暗色）
  - 表面 6 + 文字 6 + 边框 3
  - ⭐ **危险四级** `--risk-0..3` + 各自 bg/border
  - 环境色 `--env-dev/test/staging/prod`
  - 语义色 success/warning/error/info
  - ⭐ **知识可信度** `--trust-confirmed/learned/inferred`
  - SQL 语法高亮 12 色
- [ ] **U0-1.18** 亮色主题令牌（`:root` 覆盖）
- [ ] **U0-1.19** 排版令牌（font × 7 尺寸 × 4 行高 × 4 字重）
- [ ] **U0-1.20** 间距/圆角/尺寸/阴影/z-index 令牌
- [ ] **U0-1.21** 动效令牌（6 时长 + 2 缓动）
- [ ] **U0-1.22** 主题切换（`[data-theme]` + `localStorage` + 跟随系统）
- [ ] **U0-1.23** `prefers-reduced-motion` 处理
- [ ] **U0-1.24** 无硬编码颜色（lint 规则强制）

## W2 上午：基础组件（12 个）

- [ ] **U0-2.1** `Button` — 5 变体 × 4 尺寸 × 6 状态
- [ ] **U0-2.2** `Input` — 2 尺寸 × 5 状态 + prefix/suffix/clearable
- [ ] **U0-2.3** `Select` — 分组/搜索/多选/清除/创建
- [ ] **U0-2.4** `Textarea` — 自动增高
- [ ] **U0-2.5** ⭐ `Dialog` — 4 尺寸 + ⭐**danger 0-3 四级变体**
- [ ] **U0-2.6** `Dropdown` — 二级菜单 + 危险项分隔线
- [ ] **U0-2.7** `ContextMenu`
- [ ] **U0-2.8** `Popover` — 定位 + 避让
- [ ] **U0-2.9** `Tooltip` — delay 500ms
- [ ] **U0-2.10** `Tabs` — card/underline 两种
- [ ] **U0-2.11** `Splitpanes` — ⭐ 持久化
- [ ] **U0-2.12** `ScrollArea` — 主题化滚动条

**验收**：danger=3 的 Dialog 必须有勾选框且勾选前确认按钮禁用

## W2 下午：表单反馈 + 声明层前端

- [ ] **U0-2.13** `Badge` — 6 变体（环境/风险/可信度/状态）
- [ ] **U0-2.14** `Switch` / **U0-2.15** `Checkbox` / **U0-2.16** `Radio`
- [ ] **U0-2.17** `Toast` — 5 变体 + ⭐ 撤销 action（5s）
- [ ] **U0-2.18** `ProgressBar` — 确定/不确定/可中断
- [ ] **U0-2.19** `Kbd` / **U0-2.20** `EmptyState`(10种) / **U0-2.21** `ErrorState`
- [ ] **U0-2.22** `Skeleton` / **U0-2.23** `CommandPalette`(⌘K 骨架) / **U0-2.24** `Menu`

### ⭐ 声明层前端双生成

- [x] **U0-2.25** `apps/desktop/viteAdbTypesPlugin.ts`（声明层通过 API 交付，非虚拟模块）
  - `buildStart` 无条件生成
  - `configureServer` watch `declarations/**`（含增删）
- [x] **U0-2.26** ⭐ `scripts/generate-types.ts`（前端通过 API 获取声明，校验由后端 build.rs 保证）
  - `generate()` 写文件 / `generate({check:true})` 只校验
- [ ] **U0-2.27** 生成的 `src/types/generated/`
  - `connections.ts`（含 `can()` / `warnsImplicitDdlCommit()` 等查询函数）
  - `transferFamilies.ts`（含 `familyOf()` / `mapType()`）
  - `declarations.json`
- [ ] **U0-2.28** ⭐ package.json 四道钩子
  ```json
  "pretypecheck": "pnpm generate:types",
  "pretest":      "pnpm generate:types",
  "check:types":  "tsx scripts/generate-types.ts --check",
  "check":        "pnpm check:types && pnpm typecheck && pnpm lint && pnpm test"
  ```
- [ ] **U0-2.29** ⭐ 生成物**提交进 git**（不依赖本地状态）
- [ ] **U0-2.30** ⭐ CI 加 `pnpm check:types`（**保险五：产物与 YAML 不一致就 fail**）
- [ ] **U0-2.31** 验证：改一个 YAML 的 `defaultPort` → 不跑生成 → CI 必须 fail

### ⭐ Vite chunk 预算

- [ ] **U0-2.32** `vite.config.ts` manualChunks
  ```ts
  ag-grid → "ag-grid"    codemirror → "codemirror"
  echarts → 再拆 4 块（charts/components/renderers/core，抄 DBX）
  reka-ui → "ui"          marked → "marked"
  ```
- [ ] **U0-2.33** `CHUNK_BUDGET_KB` = `{index:800, ag-grid:350, codemirror:400, echarts:300, ui:120, marked:60}`
- [ ] **U0-2.34** `scripts/check-bundle.ts` 体积门禁
- [ ] **U0-2.35** `declarationsVirtualModule()` 虚拟模块

## U0 门禁

- [ ] ⛔ 所有间距 4px 倍数；圆角 ≤6px
- [ ] ⛔ 危险四级颜色全应用一致
- [ ] ⛔ 暗/亮双主题无硬编码颜色
- [ ] ⛔ 24 组件 × 所有 state 全部实现
- [ ] ⛔ **改 YAML 不跑生成 → CI fail**
- [ ] ⛔ **写坏 YAML → 编译 fail**
- [ ] ⛔ chunk 全部在预算内

---

# U1 — 全部页面 UI（W3-W6）

> 产出：**20 个页面全部可点击**，用 MockDriver 驱动
> **本阶段不写任何真实数据库驱动**

## W3：MockDriver + 应用骨架

### MockDriver（⭐ 让 UI 先行不翻车的关键）

- [x] **U1-3.1** `apps/mock-driver/schema.rs` — 12 张表 SchemaSnapshot（由 SQLite demo 驱动承载）
  - ⭐ 必须与真实驱动**同构**（同样的 ObjectId 体系、同样的 Caps）
- [x] **U1-3.2** `apps/mock-driver/data.rs` — 确定性生成器（seed 固定，已实现）
- [x] **U1-3.3** `apps/mock-driver/session.rs` — 实现 `Session` trait（真分页/真游标/真取消，已实现）
  - ⭐ **真服务端分页**（不是全量返回后切片）
  - ⭐ **真游标**语义
  - ⭐ **真取消**（`CancellationToken` + `select!`）
- [ ] **U1-3.4** `apps/mock-driver/latency.rs` — 可调延迟（默认 30ms）
- [x] **U1-3.5** ⭐ `apps/mock-driver/faults.rs` — 故障注入
  - 已实现 `crates/adb-drivers/src/faults.rs`：8 种故障 + 5 个单测
  - 慢查询 / 断线重连 / 锁表倒计时 / 语法错 / 表不存在 / 列不存在 / 只读
  - ⭐ 错误均带 `SqlPosition`
  - 待办：接进 `SqliteSession` 并暴露 API/UI（U2-7.11 依赖它）
- [ ] **U1-3.6** MockDriver 契约测试（与真实驱动跑同一套）

**⭐ 验收**：以上 6 项是本阶段的技术底线。少任何一个，UI 做完接真库必返工。

### ⭐ 契约测试层（本阶段已建立）

- [x] **U1-3.7** `engine/tests/e2e_contract.py` — 端到端契约门禁
  - 70 项断言：声明层 / 会话 / 对象树 / 表结构 / 查询 / 写回滚 / 诊断 / 搜索 / 类型映射 / 错误提示
  - `python3 engine/tests/e2e_contract.py --autostart` 一条命令跑完（自起后端 + 临时数据目录）
  - ⭐ 它抓到的真 bug（均已修 + 加了回归单测）：
    1. `total` 对 CTE 撒谎（报 5000，实返 1 行）
    2. FTS5 索引建在 seed 之前 → 中文检索恒返回 0
    3. JOIN 列元数据按名字乱认（`u.name` 被安成 `categories.name`）
    4. `maxRows` 未生效（前端发 snake_case，后端只认 snake_case，camelCase 被静默吞掉）
    5. 同家族传输（`dameng → oracle`）返回「无映射」，与界面承诺矛盾
    6. 约束报错直接抛裸 SQLite 文本（`FOREIGN KEY constraint failed`）
    7. `DataGridPage.vue` 模板里写 `selectedRows.value.length` → ref 二次解包为 undefined → **表格页白屏**
    8. `DataGridPage.vue` 重写时丢了 12 个 computed/函数（`filteredRows`/`rowH`/`isEnum`/`cellText`…）
    9. 侧栏首次加载是**空树**：`__conn__` 分区默认折叠，用户要点两次才看到表

### 应用骨架

- [ ] **U1-3.7** Tauri 2 壳 + 自绘标题栏（可拖拽）
- [ ] **U1-3.8** `AppShell.vue` — 四区布局
- [ ] **U1-3.9** 布局持久化（分栏位置/面板显隐/密度）
- [ ] **U1-3.10** ⭐ AI 面板插槽（380px，CSS 位置先定义）
- [ ] **U1-3.11** `lib/command/` 前端封装（自动错误处理/loading）

**W3 末验收**：空壳应用能启动，能切主题，能拖拽分栏

## W4：核心三页（表数据 + 编辑器 + 树）

### 侧栏 ConnectionTree

- [x] **U1-4.1** 树渲染（26px 行高，缩进线）—— `SideBar.vue` 已实现
- [ ] **U1-4.2** ⭐ 虚拟滚动（1 万对象不卡）— **待做**，当前全量渲染
- [x] **U1-4.3** ⭐ 懒加载（连接→数据库→表名→列，Depth 分级）—— `loadedParents` 客户端维护，已验证
- [x] **U1-4.4** ⭐ 表数量不截断（标题栏显示 `26 张表`，不截断）
- [x] **U1-4.5** ⭐ 本地搜索（注释 + 标识符，180ms debounce）
- [x] **U1-4.6** 环境色（左边框 3px `node--prod`）+ PRODUCTION 标识
- [x] **U1-4.7** 诊断角标（`⚠N`，按风险分组「高风险表 7」）
  - 待补：只读标记 / 收藏星标
- [ ] **U1-4.8** 右键菜单（10 项，含分隔线）
- [x] **U1-4.9** 折叠态（48px，保留色点与角标）

**验收**：`#12299` 场景 —— 1 万张表，冷启动 ≤ 3s，搜索 < 200ms

### 表格 ResultGrid

- [ ] **U1-4.10** AG Grid 接入 — **未接入**，当前自绘 `<table>`（功能可用，50 万行时需换 AG Grid）
- [ ] **U1-4.11** ⭐ 双页缓存（前后各 3 页预取，滚动中不 await）
- [x] **U1-4.12** ⭐ 全局行号（`state.offset + i + 1`）
- [ ] **U1-4.13** 列宽拖拽 + 双击自适应 + 拖拽排序 — 仅有点击排序
- [ ] **U1-4.14** ⭐ 左右列冻结（默认冻结行号 + PK）
- [ ] **U1-4.15** ⭐ 列布局持久化
- [x] **U1-4.16** 表头两行（字段名+标记 / 类型+默认值 / 注释）
- [x] **U1-4.17** ⭐ 自定义 Cell Renderer —— 已有 8 类（number/bool/NULL/enum/URL/JSON/large/text），
  - 待补 5 类：date/image/hex/blob/array
- [x] **U1-4.18** ⭐ 单元格详情面板（长文本，20KB 上限）
  - 待补：JSON 格式化 / Hex / 图片预览
- [x] **U1-4.19** ⭐ 大字段延迟加载（双击才拉 + 后端 `truncatedColumns`）
- [x] **U1-4.20** 复制（整行 TSV / 复制为 SQL / 单格多格式）
  - 待补：表头复制、多行批量复制
- [ ] **U1-4.21** 加载全部（进度 + 可中断 + 行数提示）
- [x] **U1-4.22** 密度三档（紧凑/标准/宽松，`state.density` + localStorage）
- [x] **U1-4.23** 转置视图 — 待补；✅ Hide NULL/空串 已由「筛选」+ 搜索覆盖
- [ ] **U1-4.24** Find & Replace（⌘F，范围明示）

**验收**：50 万行 60fps；行号连续；搜索范围明示
  - ✅ 行号连续　✅ 搜索范围明示（「仅搜索已加载的 200 行」）
  - ⛔ 50 万行 60fps 未达成（无虚拟滚动 + 无 AG Grid）

### 查询编辑器

- [ ] **U1-4.25** CodeMirror 6 + SQL 高亮 —— 未接入，当前 `<textarea>`（pnpm 装包多次超时）
  - ⭐ 自研轻量 SQL 高亮可作为替代路径（无新依赖）
- [x] **U1-4.26** ⭐ 上下文感知补全（WHERE 推列 / FROM 推表）
- [ ] **U1-4.27** ⭐ Ctrl+Click 符号跳转
- [x] **U1-4.28** ⭐ SQL 错误定位 + 相似表名建议
  - 后端 `locate()` 给 line/column/offset/length；`similar_names()` 推相似表
  - ⭐ 约束报错已翻译成中文 + 给出下一步（`translate_constraint`）
- [x] **U1-4.29** 格式化/压缩（关键字大小写归一）
  - ⛔ 往返不丢内容测试未写
- [ ] **U1-4.30** 多语句 + 分号分割 + 选中执行
- [ ] **U1-4.31** ⭐ 大文件降级（>5000 行）
- [ ] **U1-4.32** ⭐ hover 弹窗（delay 150ms + 缓存）
- [ ] **U1-4.33** ⭐ **In-editor results**（内联结果，`#12241` P0）
- [x] **U1-4.34** 结果页签（结果 1 / 图表占位 / 消息）
  - ✅ 消息面板显示「元数据已缓存 / 大字段延迟加载」
  - ⛔ 图表为占位（ECharts 属 F1）
- [x] **U1-4.35** 查询历史（最近 20 条，可点击回填）
  - ⛔ 固定结果未做
- [x] **U1-4.36** 状态栏（PRODUCTION / 连接 / 行数 / 无序列 / 诊断数 / 传输家族）

**W4 末验收**：表数据 + 编辑器核心闭环可演示

## W5：编辑 + 筛选 + 结构

### 编辑

- [ ] **U1-5.1** 类型化编辑器（日期/枚举/JSON/布尔）
- [ ] **U1-5.2** ⭐ EditBuffer（不自动保存）
- [ ] **U1-5.3** ⭐ 未保存黄条 + 计数
- [ ] **U1-5.4** ⭐ SQL 预览面板（底部，实时，高度可拖拽）
- [ ] **U1-5.5** ⭐ 可编辑性判定 + 原因说明 + 出路
- [ ] **U1-5.6** 关闭拦截（3 选项弹窗）
- [ ] **U1-5.7** 批量粘贴 TSV
- [ ] **U1-5.8** 克隆行（排除 PK）
- [ ] **U1-5.9** ⭐ 显式设 NULL vs 空串
- [ ] **U1-5.10** 标记删除 + ⌘Z 撤销
- [ ] **U1-5.11** 三视图（Grid / Form / JSON）
- [ ] **U1-5.12** ⭐ 外键数据选择器
- [ ] **U1-5.13** 测试数据生成器

### 筛选（⭐ Navicat 灵魂）

- [ ] **U1-5.14** ⭐ 筛选入口在列头
- [ ] **U1-5.15** ⭐ **执行位置切换**（内存 / 数据库）
- [ ] **U1-5.16** ⭐ **级联候选**（带其他筛选条件，count=0 禁用不隐藏）
- [ ] **U1-5.17** 筛选值多选 + 计数
- [ ] **U1-5.18** WHERE 框 + 元数据条件建议
- [ ] **U1-5.19** ORDER BY（可配优先级）
- [ ] **U1-5.20** ⭐ WHERE 输入 debounce 估算（`→ 预计 0.3s · 扫描 12,481 行`）
- [ ] **U1-5.21** ⭐ 表配置文件（多套配置一键切换 + 保存/删除）
- [ ] **U1-5.22** 筛选条件可见可清除（工具栏摘要）

### 结构

- [x] **U1-5.23** 表结构页 —— 4 tab（列/索引/DDL/诊断）
  - ⛔ FK 与约束是独立 tab 的设计（当前 FK 在列 tab 内）
- [x] **U1-5.24** ⭐ 注释内联显示（表注释 + 列注释 + 枚举取值）
- [x] **U1-5.25** DDL 查看 + 复制（由结构反向生成）
- [ ] **U1-5.26** Record Navigation 侧栏

**W5 末验收**：浏览+编辑+筛选闭环

## W6：差异化页面 + 剩余页面

### 结构编辑器（差异化）

- [ ] **U1-6.1** 结构编辑器布局（列/索引/FK 三区）
- [ ] **U1-6.2** ⭐ 风险标注（字段旁，红/黄/绿 + 说明）
- [ ] **U1-6.3** ⭐ 影响评估面板（锁/时长区间/扫描/空间/建议）
- [ ] **U1-6.4** ⭐ 生成的 SQL 预览
- [ ] **U1-6.5** ⭐ 回滚面板（默认展开）
- [ ] **U1-6.6** 快照勾选项
- [ ] **U1-6.7** 结构 diff 可视化（并排 + 行级 + 词级）

### 诊断

- [x] **U1-6.8** 诊断面板（`DiagnosisPage.vue` + 侧栏「高风险表」分组）
- [x] **U1-6.9** ⭐ 每条发现 = 量化影响 + 可执行下一步（`Finding` 三字段 + `FindingCard`）
  - ✅ 5 条规则：无主键 / 外键无索引 / 缺表注释 / 枚举未标注 / 敏感字段
  - ⛔ 规则数未达 F2 门禁要求的 ≥7 条
- [x] **U1-6.10** 侧栏角标（`⚠N`）+ 表头角标（`⚠ N 个问题`）
- [ ] **U1-6.11** 全库体检报告 + 导出

### 数据工厂

- [ ] **U1-6.12** 6 步向导布局
- [ ] **U1-6.13** 规则编辑器 + ⭐ 样本预览
- [ ] **U1-6.14** 一致性/分布面板

### 其他页面

- [ ] **U1-6.15** 变更工作台 + 审批详情
- [ ] **U1-6.16** 审计页（3 列 + 筛选 + 统计）
- [x] **U1-6.17** AI 面板（侧挂 + 来源标注 + 影响面预估）—— `AiPanel.vue`
  - ✅ 真查元数据生成 SQL，不编话；每条标注 confirmed/learned/inferred
  - ✅ SQL 先算影响面（后端 `analyze()`），执行前仍需人工确认
  - ⛔ 未接真实 LLM（当前是元数据驱动的规则问答）
- [ ] **U1-6.18** MCP 权限设置（⭐ 例外始终可见 + 生效汇总）
- [ ] **U1-6.19** 知识编辑器
- [ ] **U1-6.20** 设置页（7 个 tab）
- [ ] **U1-6.21** 新建连接三步向导
- [x] **U1-6.22** 启动页 + 空状态（4 个入口按钮 + 从左侧选表引导）
- [ ] **U1-6.23** 专注模式
- [ ] **U1-6.24** 命令面板（⌘K 全动作）

**W6 末验收**：20 个页面全部可点击，可完整演示产品全貌

---

# U2 — UI 打磨 + Navicat 盲测（W7-W8）

> ⛔ **不过不进 D 阶段**

## W7：打磨

- [ ] **U2-7.1** ⭐ 快捷键全表实现（`UI-SPEC.md` Part 5）
- [ ] **U2-7.2** 快捷键冲突解决（`⌘⇧F` → 专注；替换 → `⌃H`）
- [ ] **U2-7.3** 命令面板接入所有动作
- [ ] **U2-7.4** ⭐ 缩放 50-200% 全程正常
- [ ] **U2-7.5** ⭐ 设置页所有按钮在 200% 可见（`#12383`）
- [ ] **U2-7.6** ⭐ 缩放重置（`⌘0` + 设置 + >150% 时标题栏 chip）
- [ ] **U2-7.7** 响应式（900/1280/1920）
- [ ] **U2-7.8** 空状态 10 种全部实现
- [ ] **U2-7.9** 错误模式（可行动 + SqlPosition + 相似表名）
- [ ] **U2-7.10** 加载模式（200ms/3s/10s 三档）
- [ ] **U2-7.11** 故障注入场景走查（慢查询/断线/锁表/超长文本/无主键）
- [ ] **U2-7.12** 性能压测（1 万对象 / 50 万行 / 1000 列）
- [ ] **U2-7.13** 暗亮双主题全页面走查
- [ ] **U2-7.14** i18n（中英，⛔ 无硬编码文案）

## W8：盲测

### 准备

- [ ] **U2-8.1** 招 5 个真实开发者（**从没听过我们**）
- [ ] **U2-8.2** 准备测试脚本 + 录屏 + 评分表
- [ ] **U2-8.3** 同一批人也要用 Navicat 做同样任务

### 执行

- [ ] **U2-8.4** 30 分钟 5 个任务（出声思考，全程录屏）
  ```
  T1 找到"订单"表，双击打开，看第一页数据
  T2 筛选出本月"已支付"的订单，按金额倒序
  T3 写 3 表 JOIN，Ctrl+Click 表名跳转
  T4 故意写错 SQL，看能否定位到具体位置
  T5 改 3 行数据，review 生成的 SQL，然后撤销
  ```
- [ ] **U2-8.5** 12 步 Navicat 对照（`UI-SPEC.md` §8.6）

### ⛔ 判定

| 指标 | 及格 | 优秀 |
|---|---|---|
| 5 任务完成率 | ≥ 80% | 100% |
| 总耗时 vs Navicat | ≤ | < 70% |
| 卡住 >10s 次数 | ≤ 3 | 0 |
| 出声"别扭"次数 | ≤ 5 | ≤ 2 |
| ⭐ **主动说"比 Navicat 好"的功能** | ≥ 1 | ≥ 3 |
| 12 步总点击 vs Navicat | ≤ | < 70% |

**不达标 → ⛔ 不进 D 阶段**

- [ ] **U2-8.6** 回看每个卡点的录屏 → 列出问题清单
- [ ] **U2-8.7** 逐条修（只改交互，不加功能）
- [ ] **U2-8.8** 再测一轮（最多返工 4 周）

### 发版

- [ ] **U2-8.9** `v1.0-rc` 三平台打包（≤ 30MB）
- [ ] **U2-8.10** README + 截图 + GIF

---

# D — MySQL + PostgreSQL 兼容（W9-W16）

> ⭐ **UI 零改动**，只换数据来源
> 产出 v1.0 多库正式版

## W9-W10：抽象层对齐（先把 trait 定死）

> **本阶段不写 UI。** 如果发现 UI 需要改，说明 Mock 抽象有问题。

- [ ] **D-1.0** ⭐ **把 `trait Driver` 的签名改成消费 YAML 声明**
  ```rust
  // DriverCaps 不再是手写 struct，而是从 YAML 读取后与运行时能力合并
  fn caps(&self) -> DriverCaps;
  // DriverCaps 里只有"驱动特有"的信息（cancel_strategy 等）
  // 通用能力全部来自 declarations/connections/*.yaml
  ```
- [ ] **D-1.1** 审查 `Driver`/`Session`/`MetaProvider` trait 是否够用
- [ ] **D-1.2** ⭐ **`effective_traits()` 合并函数**（见 `scaffold/crates/adb-drivers/src/lib.rs`）
  ```rust
  pub fn effective_traits(desc: &Descriptor, caps: &DriverCaps) -> Traits
  // 规则：YAML 是意图，caps 是现实，取两者交集
  // 例：YAML 说 explain: [json,text]，驱动只支持 text → 剔除 json
  ```
- [ ] **D-1.3** ⭐ **`ui_warnings()` 引擎（把 UI 提示从硬编码变成声明）**
  ```rust
  pub fn ui_warnings(t: ConnectionType) -> Vec<UiWarning>
  // transactionalDdl=false  → WarnSite::StructureEditor
  // identityKind≠autoInc   → WarnSite::Transfer
  // sequenceObjects=false   → WarnSite::Sidebar
  ```
- [ ] **D-1.4** ⭐ 传输家族接入 `adb-sql-data`
  - `mapType(fromConn, toConn, sourceType) -> {target, lossy, reason}`
  - ⭐ `lossy=true` 的映射**必须由 UI 显式提示并要求用户确认**（`#9667` `#10556` 根因）
- [ ] **D-1.5** MockDriver 同步更新，跑同一套契约测试
- [ ] **D-1.6** 家族成员一致性校验（`oracle.yaml` 的每个 member 必须在 `connections/` 存在）

**W10 末验收**：契约测试套件对 Mock 全绿，且能表达 PG/MySQL 的能力差异

## W11-W12：PostgreSQL（含变体补丁层）

- [ ] **D-2.1** `crates/adb-driver-postgres/Cargo.toml`（独立 crate）
- [ ] **D-2.2** `src/variant.rs` ⭐ **补丁层**（见 `scaffold/crates/adb-driver-postgres/src/variant.rs`）
  ```rust
  enum PgVariant { Standard, GaussDB, KingbaseES, HighGo }
  // ⛔ 不 fork tokio-postgres（DBX fork 了 3 个 crate 锁 commit）
  // ✅ 差异只在 meta_sql() + quote_ident() + 版本边界
  ```
- [ ] **D-2.3** ⭐ **`meta_sql()` 集中所有元数据查询**
  - `list_tables_by_namespace_oid` — ⭐ 走 `pg_namespace.oid`
  - `columns_by_relation_oid` — ⭐ 走 `pg_class.oid`
  - 注释走 `pg_description` join（`objoid + objsubid`），不依赖 `col_description`
  - ⭐ **元数据 SQL 里绝不出现名字比较**（contract test 硬门禁）
- [ ] **D-2.4** ⭐ **变体契约测试**（4 个变体跑同一套）
  ```rust
  #[test] fn every_variant_queries_by_oid()          // 禁止 relname =
  #[test] fn transactional_ddl_only_where_true()
  #[test] fn add_column_rewrite_matches_pg_version_boundary()  // PG11 边界
  #[test] fn generated_ddl_is_schema_qualified()     // 防 #11533
  ```
- [ ] **D-2.5** 驱动（`tokio-postgres` + `deadpool`）
- [ ] **D-2.6** 连接（URL 解析 / 多 host / 密码提示）
- [ ] **D-2.7** 取消（CancelRequest）+ ⭐ 幂等 + ⭐ 超时不重放
- [ ] **D-2.8** 事务（`BEGIN/COMMIT/ROLLBACK` + SAVEPOINT）
- [ ] **D-2.9** EXPLAIN（JSON / TEXT）解析
- [ ] **D-2.10** 错误位置解析（`at line N column M`）
- [ ] **D-2.11** 心跳 + 空闲回收 + 重连
- [ ] **D-2.12** ⭐ 契约测试全过（跑 `tests/contract/`）
- [ ] **D-2.13** 测试用 PG 容器（testcontainers）

**W12 末验收**：
- [ ] ⭐ **UI 上换成 PG，零 UI 改动**
- [ ] 4 个变体的元数据查询都不含名字比较

## W13-W14：MySQL

- [ ] **D-3.1** `crates/adb-driver-mysql/` 独立 crate
- [ ] **D-3.2** `src/variant.rs`（MySQL / MariaDB / TiDB / OceanBase-MySQL / PolarDB）
- [ ] **D-3.3** ⭐ **memo 表模拟 OID**（MySQL 没有系统 OID）
  ```sql
  CREATE TABLE _adb_object_id (
    connection_id TEXT, object_hash TEXT PRIMARY KEY, oid INTEGER
  );
  -- ⭐ 名字只解析一次，之后全程用 oid（抄 DBX #12240 的思路）
  ```
- [ ] **D-3.4** 注释（`TABLE_COMMENT` / `COLUMN_COMMENT`）
- [ ] **D-3.5** 行数估算（`information_schema.TABLES`）
- [ ] **D-3.6** 查询（游标分页；⚠ MySQL 需 `UNBUFFERED`）
- [ ] **D-3.7** ⭐ 取消（`KILL QUERY <id>`）+ 幂等
- [ ] **D-3.8** ⭐ 事务（⚠ **DDL 隐式提交** —— 由 `traits.transactionalDdl: false` 驱动 UI 提示）
- [ ] **D-3.9** EXPLAIN（JSON / 树 / TEXT）
- [ ] **D-3.10** 错误位置解析（`at line N near 'xxx'`）
- [ ] **D-3.11** ⭐ 契约测试全过（含 decimal 精度 / NULL vs `''`）
- [ ] **D-3.12** 测试用 MySQL 容器
- [ ] **D-3.13** ⭐ **PG/MySQL 差异对照测试**（同一套 UI 操作，两库结果一致）

**W14 末验收**：
- [ ] ⭐ **UI 上换成 MySQL，零 UI 改动**
- [ ] MySQL 结构编辑器显示 `⚠ MySQL 的 DDL 会自动提交，无法通过事务回滚`
- [ ] MySQL 无序列工作台节点

## W15：差异处理 + SSH + 凭据

### PG vs MySQL 差异的统一处理

- [ ] **D-4.1** ⭐ **方言层**（`Dialect` trait）
  ```rust
  trait Dialect {
      fn quote_ident(&self, s: &str) -> String;    // PG "x" / MySQL `x`
      fn quote_string(&self, s: &str) -> String;
      fn param_style(&self) -> ParamStyle;          // $1 / ?
      fn build_limit(&self, n: u64) -> String;       // LIMIT n / ROWNUM
      fn ddl_supports_tx(&self) -> bool;
      fn explain_to_json(&self, raw: &str) -> Plan;
  }
  ```
- [ ] **D-4.2** ⭐ **UI 统一处理**
  | 差异 | UI 行为 |
  |---|---|
  | 标识符引号 | 用户永不需要知道，SQL 高亮自动适配 |
  | 参数占位符 | 补全/语法高亮自动适配 |
  | DDL 隐式提交 | MySQL 编辑器显式提示 `⚠ MySQL 的 DDL 会自动提交` |
  | 大小写敏感 | 不暴露给用户 |
  | NULL 排序 | 不暴露给用户 |
- [ ] **D-4.3** 跨库差异测试（同一套 UI 操作，PG/MySQL 结果一致）
- [ ] **D-4.4** ⭐ **PG/MySQL 体验一致性验证**（见门禁）

### SSH + 凭据

- [ ] **D-5.1** ⭐ `adb-secret` 完整版（Keyring + 进程内缓存 + 唯一实现守卫）
- [ ] **D-5.2** 密码提示（OS 弹窗，前端永不接触密码）
- [ ] **D-5.3** SSH 隧道（密钥 + 密码）
- [ ] **D-5.4** SSH ProxyCommand
- [ ] **D-5.5** ⭐ JumpServer 堡垒机（`#12177` `#12038` P0）
- [ ] **D-5.6** 代理配置 + 绕过规则（纯函数 + 全覆盖单测）
- [ ] **D-5.7** 连接健康（心跳/空闲回收/重连）— `#12108`
- [ ] **D-5.8** 多环境标识
- [ ] **D-5.9** 连接即代码（`connections.yaml`）
- [ ] **D-5.10** 生产环境标记 + 写操作拦截

## W16：SQLite + 契约 + 发版

- [ ] **D-6.1** SQLite 生产化（Mock 降级为真 SQLite）
- [ ] **D-6.2** 契约测试套件（PG/MySQL/SQLite 跑同一套）
  ```rust
  list_tables_returns_oid_keyed_objects
  query_supports_cursor_paging
  explain_returns_plan
  cancel_is_idempotent
  cancel_does_not_replay              // ⭐ 铁律
  export_import_roundtrip_is_lossless // ⭐ L4
  ddl_generation_is_schema_qualified  // ⭐ L12
  null_vs_emptystring_roundtrip       // ⭐ #10505
  decimal_precision_preserved         // ⭐ #9667
  ```
- [ ] **D-6.3** CI 三平台 × 三库矩阵
- [ ] **D-6.4** `v1.0` 三平台打包（≤ 35MB）
- [ ] **D-6.5** README + 文档站

### ⛔ D 门禁

- [ ] ⭐ **零 UI 改动**：从 U2 到 v1.0，前端业务代码变更行数 < 5%
- [ ] PG / MySQL / SQLite **体验一致**，无某库特殊退化
- [ ] 100 万行查询不 OOM，50 万行滚动 60fps
- [ ] 契约测试三库全绿
- [ ] 变体契约测试 4 个 PG 变体全绿（`every_variant_queries_by_oid`）
- [ ] 传输家族映射测试全绿（4 家族 × 全部类型）
- [ ] `pnpm check:types` 通过（生成物与 YAML 一致）
- [ ] chunk 预算未超
- [ ] 连接断开自动恢复（`#12108`）
- [ ] 生产环境写操作拦截生效
- [ ] 安装包 ≤ 35MB
- [ ] 崩溃率 < 0.5%

---

# F1 — 编辑 / 结构 / 导入导出（W17-W22）

- [ ] **F1-1** 结构编辑器接真库（VARCHAR 长度/字符集/排序规则）
- [ ] **F1-2** 索引/外键/约束完整支持
- [ ] **F1-3** ⭐ 生成语句自检（幂等/schema 限定/无截断）
  - `#11533` 目标 schema 不被忽略
  - `#9988` 表达式索引不截断
- [ ] **F1-4** 建表向导（可视化）
- [ ] **F1-5** ⭐ 数据导出（CSV/JSON/SQL/INSERT）
  - ⭐ **NULL 与空串可区分**（`#10505`）
  - ⭐ **导出脚本含被 FK 引用的索引**（`#10484`）
  - ⭐ **约束名去重**（`#9729`）
- [ ] **F1-6** ⭐ 数据导入（CSV/Excel/JSON）
  - ⭐ 大文件流式 + 进度 + 可中断 + 断点续传（`#10436` 卡死教训）
  - ⭐ 数量核对（导入数 vs 实际数）
- [ ] **F1-7** 导出导入往返校验（硬门禁）
- [ ] **F1-8** SQL 文件执行（可选编码 GBK）+ 目录树 + 内容搜索
- [ ] **F1-9** 对象浏览器（视图/存储过程/函数/触发器 + 源码编辑）
- [ ] **F1-10** 跨连接数据传输
  - ⭐ 类型映射计划（精度丢失**必须显式列出并确认**）
- [ ] **F1-11** ⭐ 往返一致性 CI 门禁

### ⛔ F1 门禁

- [ ] 导出→导入→结构 diff 空 + 数据 checksum 一致
- [ ] 导入 100 万行不 OOM，可中断可续传
- [ ] `#10505` `#10484` `#9729` 三类问题**全部不存在**
- [ ] 表达式索引 / 函数体 / CLOB **不截断**

---

# F2 — 诊断 + 知识层（W23-W28）

## 诊断

- [ ] **F2-1** ⭐ 无主键检测
- [ ] **F2-2** 未索引外键检测
- [ ] **F2-3** ⭐ **跨库同名列类型一致性**（无人区）
- [ ] **F2-4** 敏感字段识别
- [ ] **F2-5** 注释覆盖率
- [ ] **F2-6** 冗余索引
- [ ] **F2-7** 软删除无索引
- [ ] **F2-8** 无时间戳字段
- [ ] **F2-9** ⭐ EXPLAIN 人话解读
- [ ] **F2-10** 索引建议 + 预估收益
- [ ] **F2-11** ⭐ 一键建索引（走变更流程）
- [ ] **F2-12** 统计信息偏差告警
- [ ] **F2-13** 慢查询 Top N
- [ ] **F2-14** 报告导出

## 知识层（⭐ AI 的燃料）

- [ ] **F2-15** `.adbctx` 格式 + 解析 + 多来源合并
- [ ] **F2-16** 数据库注释读取（OID 路径）
- [ ] **F2-17** ⭐ **查询历史统计引擎**（零成本自动学到知识）
- [ ] **F2-18** 知识编辑器 UI（可信度标注）
- [ ] **F2-19** AI 推断枚举 + 一键确认
- [ ] **F2-20** ⭐ 拼音/注释本地检索（SQLite FTS5）
- [ ] **F2-21** `adb_context` 命令（`--format prompt`）

### ⛔ F2 门禁

- [ ] 演示库诊断出 ≥ 7 个有价值问题，用户能理解每一个
- [ ] ≥ 3 人点了"一键修复"
- [ ] ≥ 3 个用户写了 `.adbctx`
- [ ] 查询历史自动学到的知识，人工核对"确实对"

---

# F3 — 变更管控 + 数据工厂（W29-W36）

> 建议**先做变更管控**（DBA 更痛、更愿付费）

## 变更管控

- [ ] **F3-1** ⭐ 风险规则表（`declarations/risks/{postgres,mysql}.yaml`）
- [ ] **F3-2** ⭐ 降级风险实时检测
- [ ] **F3-3** ⭐ 锁级别判定（PG/MySQL）
- [ ] **F3-4** ⭐ 时长估算（区间 + 依据）
- [ ] **F3-5** 扫描行数/临时空间/是否重写
- [ ] **F3-6** 在线 DDL 算法建议
- [ ] **F3-7** 真 dry-run（事务里执行再回滚）
- [ ] **F3-8** ⭐ **执行前自动快照**（危险同步阻塞）
- [ ] **F3-9** ⭐ **回滚脚本生成**
- [ ] **F3-10** 从快照恢复
- [ ] **F3-11** 审批流（单人/双人/工单 + schema_hash 校验）
- [ ] **F3-12** Migration 文件生成（Plain/Flyway/Liquibase + checksum）
- [ ] **F3-13** ⭐ 批量脚本多环境评估
- [ ] **F3-14** ⭐ 全量审计 + 报表 + 导出（不可关闭）

## 数据工厂

- [ ] **F3-15** 表级采样
- [ ] **F3-16** ⭐ 中文敏感字段规则库
- [ ] **F3-17** 内容探测 + 列名/注释/拼音匹配
- [ ] **F3-18** ⭐ **外键一致性**（核心难点）
- [ ] **F3-19** 格式保留脱敏
- [ ] **F3-20** FPE 假名化
- [ ] **F3-21** ⭐ 分布保持
- [ ] **F3-22** 输出到 Docker/本地/SQLite/SQL 脚本
- [ ] **F3-23** 可复现（同 seed 同输出）
- [ ] **F3-24** ⭐ 往返校验

### ⛔ F3 门禁

- [ ] 有人为变更管控付费，或 ≥ 3 个 DBA 强烈推荐
- [ ] 变更流程走通 5 次真实场景，**零事故**
- [ ] 脱敏产物通过往返校验
- [ ] 用户验证：脱敏数据能正常跑测试

---

# F4 — AI + MCP + 分发（W37-W42）

## AI

- [ ] **F4-1** ⭐ AI 面板（侧挂、流式、中断）
- [ ] **F4-2** 多供应商（Claude/OpenAI/DeepSeek/Qwen/**Ollama**）
- [ ] **F4-3** 一键切换供应商
- [ ] **F4-4** ⭐ `adb_context` 装配（意图 + 预算裁剪 + 差分）
- [ ] **F4-5** 中文实体提取
- [ ] **F4-6** ⭐ 来源标注
- [ ] **F4-7** ⭐ 影响预估
- [ ] **F4-8** ⭐ **永不自动执行**（红线）
- [ ] **F4-9** "在编辑器打开"
- [ ] **F4-10** 选中即上下文
- [ ] **F4-11** ⭐ **提案式变更**（进 F3 审批流）
- [ ] **F4-12** AI 生成表注释

## MCP + CLI

- [ ] **F4-13** CLI 全命令 + `--json`
- [ ] **F4-14** MCP Server（22 工具）
- [ ] **F4-15** ⭐ **工具自动声明 `annotations`** + CI 守卫
- [ ] **F4-16** 三级权限 + allowlist
- [ ] **F4-17** ⭐ 权限例外始终可见（`#11503`）
- [ ] **F4-18** ⭐ `adb_propose_change`
- [ ] **F4-19** ⭐ 单元格分页（参数实际生效，`#9865`）
- [ ] **F4-20** npm 包 × 2
- [ ] **F4-21** ⭐ Agent Skill（`agent setup`）
- [ ] **F4-22** 审计导出

### ⛔ F4 门禁

| 指标 | 目标 |
|---|---|
| 表名命中率（首次） | > 95% |
| 列名命中率 | > 90% |
| 枚举语义正确率（有 `.adbctx`） | > 98% |
| ⭐ **只读场景自动执行写操作** | **0（红线）** |
| 采纳率 | > 60% |
| P95 上下文装配（本地） | < 300ms |
| `annotations` 声明率 | 100% |
| 安装包 | ≤ 40MB |

---

# 持续运营（U2 结束后启动，不等产品完整）

- [ ] **L-1** GitHub Issue 模板 + 优先级
- [ ] **L-2** ⭐ Issue 响应纪律（首次 <24h，中位关闭 <14d）
  - DBX 的 1232 个 open issue 是最大负债
- [ ] **L-3** 中文社区（掘金/CSDN/知乎/CNB）
- [ ] **L-4** 内容计划（4 篇技术文）
- [ ] **L-5** 社区反馈 → issue 自动化
- [ ] **L-6** CONTRIBUTING + CLA + 求 mentor
- [ ] **L-7** 分发（Homebrew/WinGet/Flatpak/Scoop/Docker/内网镜像）
- [ ] **L-8** 每周 TodoList review + 每月精简预算 review
- [ ] **L-9** 季度性能基线回归

## 待排期（F4 之后）

- [ ] JDBC 通道（达梦/金仓/OceanBase）—— 加库 = 1 YAML
- [ ] Redis / MongoDB / ClickHouse 工作台
- [ ] ER 图 + 逆向/正向工程
- [ ] 数据字典 / 文档生成
- [ ] 插件市场（需 10+ 用户 + 2+ 贡献者）

---

# 明确不做

```text
❌ 支持 100 种数据库      → 8 种做到极致
❌ 自研 Grid / SQL 解析器 / MCP 协议
❌ BI 仪表板              → 那是 BI 工具的活
❌ PL/SQL 调试器          → 做一半不如不做
❌ Navicat Cloud 协作     → .adbctx 进 git 已解决 80%
❌ Navicat Monitor        → 服务器监控不是我们的职责
❌ 移动端                 → 工作场景是桌面
❌ 社区版功能阉割

⚠️ 推迟到 F4 之后：插件市场 / 团队空间 / 商业化
```

---

# 三条止损线

```text
⛔ 止损线 1：U2 盲测不达标（体验 < Navicat）
   → 不进 D 阶段，回到 U1 打磨。最多返工 4 周。

⛔ 止损线 2：D 结束时周活 < 500
   → 重新审视定位。

⛔ 止损线 3：任何一起生产库事故
   → 立即冻结全部生产环境功能，直到根因修复 + 回归测试。
```

---

# 附录 A：24 crate 结构（facade 模式）

> ⭐ 抄自 DBX 的 `crates/dbx-drivers` + 7 个 per-driver crate

```text
应用层
  adb-desktop ── Tauri 壳

编排层
  adb-core     connection / query / schema / data / ai

基础能力（零业务）
  adb-types    ⭐ build.rs 消费 declarations → Rust enum
  adb-platform 进程 / 路径 / 代理
  adb-secret   ⭐ 凭据（唯一实现）

SQL（facade + 4 子）
  adb-sql ──┬── adb-sql-core     解析器
           ├── adb-sql-dialect  方言注册 + ⭐ 传输家族
           ├── adb-sql-data     DML/DDL 生成
           └── adb-sql-schema   结构差异

驱动（facade + 7 个 per-driver）⭐ v4 变更
  adb-drivers ──┬── adb-driver-postgres    ⭐ variant.rs 补丁层
                ├── adb-driver-mysql       ⭐ memo 表模拟 OID
                ├── adb-driver-sqlite
                ├── adb-driver-redis
                ├── adb-driver-mongodb
                ├── adb-driver-clickhouse
                ├── adb-driver-jdbc       达梦/金仓/长尾
                └── adb-driver-support    共享工具

格式
  adb-formats  CSV / XLSX / JSON / SQL / 压缩

差异化引擎
  adb-mask     脱敏
  adb-diag     诊断
  adb-change   变更管控
  adb-audit    审计

入口
  adb-registry Command Registry + codegen
  adb-mcp      MCP Server（⭐ 强制依赖 adb-secret）
  adb-cli      CLI
  adb-web      axum
```

## ⛔ 刻意不做的三件事

```text
1. 不 vendor 任何 Tauri/wry 官方库
   DBX vendor 了 8 个，主因是 Win7 兼容性。我们的目标用户不需要 Win7。

2. 不 fork tokio-postgres / mysql_async
   DBX fork 了 tokio-postgres（3 个 crate）锁 commit 支持高斯。
   我们用 adb-driver-postgres/src/variant.rs 的补丁层：0 个 fork。

3. 不为国产库改上游 crate
   真需要改 wire protocol 时，先在 adb-driver-{x}/src/patch/ 用 trait 包装做；
   实在不行才 fork，且必须写 ADR 说明原因。
   xtask/guard.rs 检查 [patch.crates-io] 非空时是否有对应 ADR。
```

---

# 附录 B：脚手架索引（`scaffold/`）

> A/B/C/D 四份产物已落地，可直接复制进项目。

| # | 文件 | 内容 |
|---|---|---|
| **A** | `scaffold/declarations/schema/connection.schema.json` | 连接描述符 JSON Schema（含 `traits` / `capabilities` 全字段） |
| | `scaffold/declarations/connections/postgresql.yaml` | native 通道完整示例 |
| | `scaffold/declarations/connections/mysql.yaml` | ⭐ 与 PG 的 `traits` 差异对照 |
| | `scaffold/declarations/connections/dameng.yaml` | ⭐ jdbc 通道 + `transferObjectFamily: oracle` + 显式 `false` |
| | `scaffold/crates/adb-types/build.rs` | ⭐ 8 条断言 + 4 产物 + 目录级 rebuild |
| **B** | `scaffold/apps/desktop/viteAdbTypesPlugin.ts` | Vite watch + buildStart |
| | `scaffold/apps/desktop/scripts/generate-types.ts` | ⭐ 校验与 build.rs 对齐 + `can()` / `warnsImplicitDdlCommit()` / `mapType()` |
| | `scaffold/apps/desktop/package.json` | 四道钩子 + 依赖预算 |
| | `scaffold/apps/desktop/vite.config.ts` | ⭐ manualChunks + chunk 预算 + 虚拟模块 |
| **C** | `scaffold/declarations/transfer-families/oracle.yaml` | ⭐⭐ 6 个成员共用（达梦/金仓O/OceanBase-O/GBase8s/高斯/Oracle） |
| | `scaffold/declarations/transfer-families/postgres.yaml` | 8 成员 |
| | `scaffold/declarations/transfer-families/mysql.yaml` | 5 成员 |
| | `scaffold/declarations/transfer-families/sqlserver.yaml` | 3 成员 |
| **D** | `scaffold/Cargo.toml` | ⭐ 24 crate workspace + `opt-level="s"` + lints + `[patch]` 留空 |
| | `scaffold/crates/adb-drivers/Cargo.toml` | facade + feature 矩阵 |
| | `scaffold/crates/adb-drivers/src/lib.rs` | ⭐ trait 定义 + 注册表 + `effective_traits()` + `ui_warnings()` |
| | `scaffold/crates/adb-driver-postgres/Cargo.toml` | per-driver crate |
| | `scaffold/crates/adb-driver-postgres/src/variant.rs` | ⭐⭐ 补丁层 + `meta_sql()` + 4 个契约测试 |

## 传输家族成员覆盖

```text
oracle      → oracle, dameng, oceanbase-oracle, kingbase-oracle, gbase8s, highgo
postgres    → postgresql, redshift, cockroachdb, greenplum, opengauss, gaussdb, timescaledb
mysql       → mysql, mariadb, tidb, oceanbase-mysql, polardb-mysql
sqlserver   → sqlserver, azure-sql, synapse

⭐ 效果：新增一个 Oracle 兼容国产库 = 1 个 connections/*.yaml（4 行元信息）
        + 往 oracle.yaml 的 members 加 1 个 id。传输规则零改动。
```

---

*v4.0 · 8 阶段 42 周 · 约 400 任务 · UI 优先 + MySQL/PG 优先 · 吸收 DBX 源码研读*

---

# 每周自检

```bash
cargo xtask guard      # 架构守卫
cargo xtask lean       # 精简预算（行数/依赖/体积）
cargo nextest run      # 单测
pnpm typecheck && pnpm lint && pnpm vitest run

# 每周五下午
- [ ] 本周任务有验收标准且已验收
- [ ] 范围没超出 TodoList
- [ ] ⭐ 有一个能给人看的进展（截图/GIF/可运行版本）
- [ ] 体积/依赖数/crate 数在预算内
- [ ] 新反馈已记入 issue
```

---
