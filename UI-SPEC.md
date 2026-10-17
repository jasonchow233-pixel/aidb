# UI 设计规格书 v4.0 — AgenticDB

> 日期：2026-10-16 ｜ 配套 `TODO.md` v4.0 `IMPLEMENTATION.md` v4.0 `DEV-SPEC.md` `UI-DESIGN.md`（原则层）
> **本文件是可直接施工的规格**：令牌值、组件 Props、页面线框、状态矩阵、快捷键、验收标准
> **顺序**：先按本文件把 UI 全部做出来（Mock 数据驱动），再接 MySQL/PG，最后补全功能
> **v4 变更**：新增 §3.6bis **能力矩阵区块**（由 YAML 声明驱动，组件零硬编码）

---

## 目录

- [0. 施工方式：为什么 Mock 先行不会翻车](#0-施工方式为什么-mock-先行不会翻车)
- [Part 1 设计系统](#part-1-设计系统)
- [Part 2 组件规格](#part-2-组件规格)
- [Part 3 页面规格](#part-3-页面规格)
- [Part 4 交互模式库](#part-4-交互模式库)
- [Part 5 快捷键全表](#part-5-快捷键全表)
- [Part 6 响应式与缩放](#part-6-响应式与缩放)
- [Part 7 可访问性](#part-7-可访问性)
- [Part 8 设计验收清单](#part-8-设计验收清单)

---

# 0. 施工方式：为什么 Mock 先行不会翻车

## 0.1 核心设计：MockDriver 实现同一个 trait

```rust
// ⭐ 这是"先做 UI"能成立的技术前提
#[async_trait]
pub trait Driver: Send + Sync {
    fn kind(&self) -> DriverType;
    fn caps(&self) -> DriverCaps;
    async fn connect(&self, cfg: &ConnConfig) -> Result<Box<dyn Session>>;
    async fn introspect(&self, s: &dyn Session, scope: &Scope, depth: Depth)
        -> Result<SchemaSnapshot>;
}

#[async_trait]
pub trait Session: Send + Sync {
    async fn query(&self, sql: &str, opt: QueryOptions) -> Result<RowStream>;
    async fn cancel(&self) -> Result<()>;
    async fn begin(&self) -> Result<Txn>;
    async fn heartbeat(&self) -> Result<()>;
    fn schema_snapshot(&self) -> Arc<SchemaSnapshot>;
}
```

```text
apps/mock-driver/          ⭐ UI 开发阶段用
├── schema.rs              12 张演示表的 SchemaSnapshot（与真实完全同构）
├── data.rs                50 万行生成器（确定性 seed）
├── session.rs             实现 Session trait，含分页/取消/延迟模拟
├── latency.rs             模拟网络延迟（可调，默认 30ms）
└── faults.rs              ⭐ 故障注入：慢查询/连接断开/锁表/错误

UI 开发全程：  MockDriver  ──→  界面
                                          ↓ U2 阶段结束时替换
真实接入：    PgDriver / MySQLDriver  ──→  同一个界面（零改动）
```

**因此 UI 开发期的所有分页/取消/加载/错误态都是真的** —— 因为走的是真实的 trait 调用链，只是数据来自生成器。

## 0.2 MockDriver 必须实现的真实行为

| 行为 | 实现 | 为什么必须真 |
|---|---|---|
| **服务端分页** | 真按 `LIMIT/OFFSET` 返回，不是全量返回后切片 | 否则分页 UI 做完接真库会崩 |
| **游标** | 真游标语义，不是数组索引 | 滚动中数据插入的行为不同 |
| **行数估算** | 真的走一遍统计（或模拟慢查询） | 进度条、大表提示 |
| **取消** | 真中断（`CancellationToken` + `select!`） | 否则取消按钮永远测不出来 |
| **锁表/慢查询** | 可注入 5s/30s/永不返回 | 否则超时 UI 测不出来 |
| **错误** | 可注入各种真实错误（含 `SqlPosition`） | 否则错误跳转测不出来 |
| **断线** | 可注入断线 + 重连 | 否则重连 UI 测不出来 |
| **大字段** | 长文本/JSON/BLOB 真实存在 | 否则详情面板测不出来 |
| **NULL/空串** | 真实混合 | 否则筛选测不出来 |
| **无主键表** | 真实存在 | 否则可编辑性判定测不出来 |

> **一句话**：Mock 必须实现真实行为，只换数据来源。
> 只 mock 数据的项目 100% 会在接真库时返工。

## 0.3 施工顺序（UI 阶段 8 周）

```text
U0 (W1-2)  设计系统 + 声明层 + 组件库   tokens.css + 24 组件 + Storybook
   ↓
U1 (W3-6)  全部页面 UI                 用 MockDriver 驱动，逐页实现
   ↓                              每页都有 Storybook 入口可单独验收
U2 (W7-8)  UI 打磨 + 盲测             对照 Navicat 逐项验收
   ↓                              ⛔ 不过不进 D 阶段
D  (W9-16) MySQL + PostgreSQL        假数据换真数据，UI 零改动
```

---

# Part 1 设计系统

## 1.1 五条设计原则

| # | 原则 | 判定标准 |
|---|---|---|
| **P1** | **读取零摩擦，写入有摩擦** | 只读操作不该有任何弹窗；写操作必须按风险分级 |
| **P2** | **每个操作都标明它在哪里执行** | 控件标签直接写"内存"还是"数据库" |
| **P3** | **危险用颜色+图标+文案三重编码** | 不能只靠红色 |
| **P4** | **可点击的东西必须有 hover 态和指针** | cursor 与 affordance 一致 |
| **P5** | **状态永远可见** | 事务/连接健康/执行中/未保存，不能只在某个角落 |
| **P6** | **⭐ 能力来自声明，不来自硬编码** | 组件不许写 `if (conn === "mysql")` |

## 1.2 色彩令牌

### 表面与文字

```css
/* ============ 暗色（默认） ============ */
--bg-canvas:       #0d1117;   /* 应用底 */
--bg-surface:      #161b22;   /* 面板 */
--bg-elevated:     #21262d;   /* 弹窗/下拉/菜单 */
--bg-inset:        #010409;   /* 输入框/代码块/编辑器 */
--bg-hover:        rgba(177,186,196,0.12);
--bg-active:       rgba(56,139,253,0.15);
--bg-selected:     rgba(56,139,253,0.22);
--bg-disabled:     rgba(177,186,196,0.06);

--border:          #30363d;
--border-strong:   #484f58;
--border-focus:    #388bfd;

--text-primary:    #e6edf3;
--text-secondary:  #8b949e;
--text-tertiary:   #6e7681;
--text-disabled:   #484f58;
--text-inverse:    #0d1117;
--text-link:       #58a6ff;

/* ============ 亮色 ============ */
--bg-canvas:       #ffffff;
--bg-surface:      #f6f8fa;
--bg-elevated:     #ffffff;
--bg-inset:        #f6f8fa;
--bg-hover:        rgba(31,35,40,0.08);
--border:          #d0d7de;
--border-strong:   #afb8c1;
--text-primary:    #1f2328;
--text-secondary:  #656d76;
--text-tertiary:   #8c959f;
```

### 危险四级（核心）

```css
/* 语义命名，不按颜色命名 —— 这样换主题色不用改组件 */
--risk-0-read:       #388bfd;   /* 只读     蓝 */
--risk-1-write:      #d29922;   /* 写数据   黄 */
--risk-2-structure:  #f85149;   /* 改结构   红 */
--risk-3-admin:      #bc4cff;   /* 管理     紫 */

--risk-0-bg:      rgba(56,139,253,0.08);
--risk-0-border:  rgba(56,139,253,0.30);
--risk-1-bg:      rgba(210,153,34,0.08);
--risk-1-border:  rgba(210,153,34,0.30);
--risk-2-bg:      rgba(248,81,73,0.08);
--risk-2-border:  rgba(248,81,73,0.30);
--risk-3-bg:      rgba(188,76,255,0.08);
--risk-3-border:  rgba(188,76,255,0.30);
```

**对应的确认强度**（不可只看颜色）：

| 级别 | 标题 | 确认方式 | 图标 |
|---|---|---|---|
| 0 | — | 无提示 | — |
| 1 | `将修改数据` | 按钮 + 5s 撤销 | `edit-3` |
| 2 | `⚠ 危险操作` | 按钮（间距 ≥48px） | `alert-triangle` |
| 3 | `🛑 高危操作` | 按钮 + 手动勾选 | `shield-alert` |

### 环境标识

```css
--env-dev:      #388bfd;   --env-dev-bg:      rgba(56,139,253,0.10);
--env-test:     #8b949e;   --env-test-bg:     rgba(139,148,158,0.10);
--env-staging:  #d29922;   --env-staging-bg:  rgba(210,153,34,0.10);
--env-prod:     #f85149;   --env-prod-bg:     rgba(248,81,73,0.10);
```

**应用位置**（四处同时出现，确保无法忽视）：
1. 侧栏条目左边框 3px
2. 侧栏条目背景微染
3. 标题栏 chip
4. 状态栏

### 语义状态

```css
--success: #3fb950;  --success-bg: rgba(63,185,80,0.10);
--warning: #d29922;  --warning-bg: rgba(210,153,34,0.10);
--error:   #f85149;  --error-bg:   rgba(248,81,73,0.10);
--info:    #58a6ff;  --info-bg:    rgba(88,166,255,0.10);

/* 知识可信度（语义层专用） */
--trust-confirmed: #3fb950;   /* ✓ 已确认（数据库注释/人工） */
--trust-learned:   #58a6ff;   /* ✓ 自动学到（查询历史）   */
--trust-inferred:  #d29922;   /* ⚠ 待确认（AI 推断）      */
```

### SQL 语法高亮

```css
--syn-keyword:   #ff7b72;
--syn-string:    #a5d6ff;
--syn-number:    #79c0ff;
--syn-comment:   #8b949e;   --syn-comment-bg: rgba(110,118,129,0.15);
--syn-function:  #d2a8ff;
--syn-type:      #ffa657;
--syn-ident:     #c9d1d9;
--syn-quoted:    #a5d6ff;
--syn-param:     #79c0ff;
--syn-error:     #f85149;
```

## 1.3 排版令牌

```css
--font-ui: -apple-system, BlinkMacSystemFont, "Segoe UI",
           "PingFang SC", "Microsoft YaHei", "Hiragino Sans GB",
           Roboto, system-ui, sans-serif;
--font-mono: "JetBrains Mono", "SF Mono", "Cascadia Code", Menlo,
             Consolas, "Noto Sans Mono CJK SC", monospace;

/* ⭐ 13px 是工作软件基准，不是 14/16 */
--fs-xs:   11px;   --fs-sm:   12px;   --fs-base: 13px;
--fs-md:   14px;   --fs-lg:   16px;   --fs-xl:   20px;   --fs-2xl:  24px;

--lh-tight:  1.25;
--lh-normal: 1.5;
--lh-dense:  1.2;

--fw-normal: 400;
--fw-medium: 500;   /* ⭐ 大量使用，代替 bold */
--fw-bold:   600;   /* 只用于标题 */
```

## 1.4 间距、形状、密度

```css
--sp-1: 4px;  --sp-2: 8px;  --sp-3: 12px;  --sp-4: 16px;
--sp-5: 20px; --sp-6: 24px; --sp-8: 32px;  --sp-10: 40px;

/* ⭐ 圆角 6px 封顶 —— 12px+ 是消费级 SaaS 语言 */
--r-sm:   4px;
--r-md:   6px;
--r-lg:   8px;
--r-xl:   12px;   /* ⭐ 只用于空状态大图标容器 */
--r-full: 999px;

--h-titlebar:  36px;
--h-toolbar:   36px;
--h-tabbar:    36px;
--h-statusbar: 24px;
--h-input:     28px;
--h-input-md:  32px;
--h-btn-sm:    24px;
--h-btn:       28px;
--h-btn-md:    32px;
--w-sidebar-min:    160px;
--w-sidebar-default: 240px;
--w-sidebar-max:    480px;
--w-ai-panel:   380px;
--w-dialog-sm:  400px;
--w-dialog:     560px;
--w-dialog-lg:  800px;
--w-dialog-xl:  1080px;

--row-compact:  26px;
--row-normal:   32px;
--row-comfort:  40px;

--shadow-sm: 0 1px 3px rgba(0,0,0,0.12);
--shadow-md: 0 4px 12px rgba(0,0,0,0.16);
--shadow-lg: 0 12px 32px rgba(0,0,0,0.24);

--bw: 1px;
--bw-focus: 2px;

--z-dropdown: 1000;
--z-drawer:   1100;
--z-dialog:   1200;
--z-toast:    1300;
--z-tooltip:  1400;
```

## 1.5 动效

```css
--dur-instant: 80ms;
--dur-fast:   120ms;
--dur-base:   150ms;
--dur-slow:   220ms;
--dur-spin:   1000ms;

--ease-out:    cubic-bezier(0.16, 1, 0.3, 1);
--ease-in-out: cubic-bezier(0.4, 0, 0.2, 1);
```

| 场景 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| hover / focus | 120ms | ease-out | 背景色、边框色 |
| 按钮按下 | 80ms | ease-out | scale 0.98 |
| 面板展开折叠 | 150ms | ease-in-out | width/height |
| 弹窗进入 | 150ms | ease-out | scale 0.98→1 + 淡入 |
| 遮罩淡入 | 220ms | ease-out | opacity |
| 侧栏切换 | 220ms | ease-in-out | width |
| 折叠/展开高度 | 150ms | ease-in-out | grid-template-rows |
| Toast 进出 | 150ms | ease-out | translateY 8px |
| 加载旋转 | 1000ms 循环 | linear | — |
| **结果加载完成** | **无动画** | — | 避免闪烁 |
| 表格排序 | 100ms | ease-out | 图标位移 |

**必须响应 `@media (prefers-reduced-motion: reduce)`** → 全部改为 0.01ms。

## 1.6 图标

**Lucide**，线性风格，1.5px 描边。

| 用途 | 尺寸 |
|---|---|
| 侧栏树节点 | 14px |
| 工具栏按钮 | 16px |
| 侧栏一级分类 | 16px |
| 徽标内 | 12px |
| 空状态大图标 | 48px |

**语义固定映射**（全应用统一）：

```text
只读/安全      shield            蓝
写数据         edit-3            黄
改结构         alert-triangle    红
管理/高危      shield-alert      紫
连接断开       unplug            灰
连接健康       check-circle-2    绿
连接陈旧       circle-alert      黄
未保存         pencil            黄
执行中         loader            蓝
已取消         circle-slash      灰
失败           x-circle          红
知识已确认     check-circle-2    绿
知识待确认     alert-circle      黄
新增行         plus              绿
修改行         pencil            黄
删除行         trash-2           红
```

---

# Part 2 组件规格

> 共 30 个组件。基于 `reka-ui`（无头）+ Tailwind 定制。

## 2.1 基础组件

### Button

```ts
interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  icon?: Component;
  iconRight?: Component;
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  shortcut?: string;
}
```

| 变体 | 背景 | 文字 | 边框 | 用途 |
|---|---|---|---|---|
| `primary` | `--accent` | `#fff` | 无 | 主要动作（每个对话框只能一个） |
| `secondary` | `--bg-elevated` | `--text-primary` | `--border` | 次要动作 |
| `ghost` | 透明 | `--text-primary` | 无 | 工具栏 |
| `danger` | `--risk-2-structure` | `#fff` | 无 | 危险确认 |
| `danger-ghost` | 透明 | `--risk-2-structure` | 无 | 危险次要 |

| size | 高度 | padding | font | 图标 |
|---|---|---|---|---|
| `xs` | 24px | 0 8px | 11px | 12px |
| `sm` | 28px | 0 10px | 12px | 14px |
| `md` | 32px | 0 14px | 13px | 16px |
| `lg` | 38px | 0 20px | 14px | 16px |

**状态**：`default` / `hover` / `active`(scale .98) / `focus-visible`(2px ring) / `disabled`(opacity .5) / `loading`(左侧 spinner，保留宽度)

**⭐ 验收**：危险按钮与取消按钮间距 ≥ 48px。

### Input / Select / Textarea

```ts
interface InputProps {
  size?: 'sm' | 'md';
  state?: 'default' | 'error' | 'warning' | 'success';
  label?: string;
  hint?: string;
  error?: string;
  prefixIcon?: Component;
  suffix?: string;
  monospace?: boolean;
  clearable?: boolean;
}
```

| 尺寸 | 高度 | font | padding |
|---|---|---|---|
| `sm` | 28px | 12px | 0 8px |
| `md` | 32px | 13px | 0 10px |

**状态**：default / hover(`--border-strong`) / focus(`--accent` + 3px ring) / error / disabled

**⭐ Select 必须支持**：分组、搜索（>10 项时）、多选、清除、创建新项（连接类型用）。

### Dialog

```ts
interface DialogProps {
  size?: 'sm' | 'md' | 'lg' | 'xl';
  danger?: 0 | 1 | 2 | 3;
  icon?: Component;
  closable?: boolean;
  requireAck?: boolean;
  width?: number;
}
```

| danger | 标题文案 | 标题图标/色 | 确认按钮 | 勾选框 | 关闭方式 |
|---|---|---|---|---|---|
| 0 | 无弹窗 | — | — | — | — |
| 1 | `{summary}` | `--text-primary` | `确认` | 否 | Esc / 遮罩 / 取消 |
| 2 | `⚠ {title}` | `alert-triangle` `--risk-2` | `确认执行` | 否 | 取消 / Esc |
| 3 | `🛑 {title}` | `shield-alert` `--risk-3` | `确认执行` | **必须** | 仅取消 |

**验收**：
- 弹窗打开时焦点入弹窗，**关闭时焦点归还触发元素**
- Esc 必能关闭
- 遮罩点击关闭：danger<3 允许，danger===3 不允许

### 其他基础组件

| 组件 | 关键 Props | 验收 |
|---|---|---|
| `Tooltip` | `content` `side` `delay=500` | danger>=2 的信息**必须用 Dialog 不用 Tooltip** |
| `Badge` | `variant` `size` | 环境、风险级、可信度 |
| `Tabs` | `v-model` `type` | `card` / `underline` |
| `Splitpanes` | 持久化布局 | 重启后位置还原 |
| `Dropdown` | 触发器 `items[]` | 二级菜单、危险项带分隔线 |
| `ContextMenu` | `items[]` | 表格/树节点右键 |
| `Toast` | `variant` `action` | 写操作可撤销（5s），堆叠 ≤3 |
| `ProgressBar` | `value` `indeterminate` | 可中断 |
| `Switch` `Checkbox` `Radio` | `v-model` `indeterminate` | — |
| `Kbd` | `keys` | `⌘⇧P` |
| `ScrollArea` | 主题化滚动条 | 宽 8px，hover 加宽 |
| `Popover` | 定位 | 列头筛选面板 |

## 2.2 业务组件（12 个）

### ConnectionTree — 最复杂

```ts
interface ConnectionTreeNode {
  id: ObjectId;
  kind: 'connection' | 'database' | 'schema' | 'table' | 'view'
      | 'columnGroup' | 'column' | 'index' | 'fk' | 'routine';
  label: string;
  comment?: string;            // 语义层：表注释
  meta?: string;               // 行数 / 体积
  riskBadge?: number;          // 诊断问题数
  color?: string;
  env?: 'dev' | 'test' | 'staging' | 'prod';
  health?: 'healthy' | 'stale' | 'disconnected';
  hasChildren?: boolean;
  loaded?: boolean;            // 懒加载状态
  loading?: boolean;
  pinned?: boolean;
}
```

| 项 | 值 |
|---|---|
| 行高 | 26px |
| 缩进 | 每级 14px，缩进线 `--border` 1px |
| 虚拟滚动 | 必须（1 万对象不卡） |
| 搜索 | 顶部固定搜索框，⌘F 聚焦 |

**⭐ 关键行为**

```text
1. 懒加载：连接展开 → 只拉数据库列表
           数据库展开 → 只拉表名+注释+行数（Depth::Name）
           表展开 → 才拉列/索引/FK（Depth::Structure）
           永不一次性拉全部

2. 表数量不截断：
   1 万张表 → 显示 "已显示 500（共 10,000）" + [显示全部]
   绝不静默截断到 1000（#12299 教训）

3. 搜索（本地 SQLite FTS5，不查数据库）：
   搜 "dd"      → 命中"订单"（拼音首字母）
   搜 "订单"    → 命中 orders（注释）
   搜 "status"  → 命中所有含 status 列的表

4. 环境与健康：
   生产连接 → 左边框 3px --env-prod + 背景微染
   连接状态点 → 健康/陈旧/断开

5. 右键菜单（10 项，含分隔线）：
   打开 / 在新标签打开 / 复制 DDL / 复制名称
   设为收藏 / 取消收藏
   导出数据 / 传输数据
   ──
   结构编辑器 / 生成 DDL / 诊断
   ──
   查看诊断 / 编辑知识
   ──
   删除连接

6. 角标：
   表有诊断问题 → ⚠ N
   只读连接 → 锁图标
```

### ResultGrid — 基于 AG Grid Community

```ts
interface GridColumn {
  field: string;
  headerName: string;
  comment?: string;             // 列注释（tooltip 显示）
  type: ColumnType;             // text|number|bool|date|datetime|json|binary|uuid|enum
  width?: number; minWidth?: number;
  editable?: boolean; nullable?: boolean;
  isPrimaryKey?: boolean; isForeignKey?: boolean;
  isLarge?: boolean;            // 大字段，延迟加载
  enumValues?: Record<string,string>;
  pinned?: 'left' | 'right' | null;
  hidden?: boolean;
}
```

**⭐ AG Grid 配置要点**

```ts
{
  rowBuffer: 10,
  suppressColumnVirtualisation: false,
  animateRows: false,            // ⭐ 关掉动画，否则大表滚动掉帧
  rowSelection: 'multiple',
  enableCellTextSelection: true,
  suppressCopyRowsToClipboard: true,   // 自己实现复制
  getRowId: ({data}) => data.__rid,
  defaultColDef: {
    resizable: true,
    sortable: false,            // ⭐ 排序走 ORDER BY（DB），不用 AG Grid 的
    filter: false,              // ⭐ 筛选走我们的，不冲突
    cellStyle: { fontSize: 'var(--fs-sm)' },
  },
  overlayNoRowsTemplate: '<EmptyState kind="no-data"/>',
}
```

**⭐ 自定义 Cell Renderer（按列类型）**

| 类型 | 渲染 | 交互 |
|---|---|---|
| `text` 短 | 纯文本，`overflow: ellipsis` | 双击编辑 |
| `text` 长 | 前 200 字符 + `…` | 双击 → 详情面板 |
| `number` | 右对齐，等宽数字 | 双击编辑 |
| `bool` | 勾/叉 居中 | 点击切换 |
| `date/datetime` | 本地化格式 | 日历选择器 |
| `enum` | 显示含义文本 | 下拉选择 |
| `json` | 折叠摘要 | 双击 → JSON 详情 |
| `binary` | `⟨binary⟩ 1.2 KB` | 双击 → Hex 详情 |
| `uuid` | 等宽字体 | — |
| `url` | 蓝色下划线 | 点击打开 |
| `image_url` | 32px 缩略图 | 点击放大 |
| `null` | 斜体灰色 `NULL` | — |
| **已编辑** | 左边框 2px 黄 + hover 黄底 | ⌘Z 撤销 |

**⭐ 单元格详情面板**

```text
┌─ 单元格详情 ──────────────────────── [复制] [下载] [×] ─┐
│                                                            │
│  name                             varchar(200)   NOT NULL  │
│  ──────────────────────────────────────────────────────  │
│  订单备注文本，超出主表格显示宽度的内容在这里完整展示。      │
│  包含多行：                                                │
│    第一行                                                  │
│    第二行                                                  │
│                                                            │
│  ──────────────────────────────────────────────────────  │
│  总长 1,284 字符 · 当前显示全部                            │
│  [原始] [十六进制] [JSON 格式化]                           │
└────────────────────────────────────────────────────────────┘
```

**验收**：Esc 关闭并**焦点归还单元格**。

### QueryEditor

```ts
interface QueryEditorProps {
  dialect: Dialect;               // postgres | mysql | sqlite | oracle | dm8 | ...
  schema: SchemaSnapshot;
  readOnly?: boolean;
  height?: number | string;
}
```

| 项 | 值 |
|---|---|
| 编辑器背景 | `--bg-inset` |
| font | `--font-mono`, 13px, line-height 1.5 |
| 行号 | 显示，`--text-tertiary`，当前行高亮 |
| 当前行 | 整行 `--bg-hover` 极淡 |
| 选中文本 | `--bg-selected` |
| 大文件降级 | > 5000 行关闭语言解析 |

**⭐ 补全弹窗**

```text
┌─────────────────────────────────────────────┐
│ ▸ users            表 · public              │  ← 分组头
│   users_id         bigint  PK               │
│   users_email      citext                    │
├─────────────────────────────────────────────┤
│ ▸ =                                                 │
│   =   等于            >   大于                 │
│   !=  不等于          >=  大于等于             │
│   <>  不等于          <   小于                 │
│                                              │
│  ↑↓ 选择   ⏎ 插入   Esc 关闭   ⇥ 补全       │
└─────────────────────────────────────────────┘
```
- 位置：光标下方，自动避让视口边界
- 宽度：max(240px, 最长项 + 40px)，max 420px
- 字号：12px

**⭐ Ctrl+Click 跳转**
- `Cmd/Ctrl + Click` 标识符 → 打开对应对象页
- hover 时标识符加下划线 + `cursor: pointer`
- 失败：Toast `找不到 orders（试试 orders_log？）`

### StatusBar

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ 正在执行 12.4s │ 45,231/120,000 38% │ [事务 ON] │ prod-postgres 健康 │ ⚙ │
└──────────────────────────────────────────────────────────────────────────┘
```

```ts
interface StatusBarState {
  execution: { state: 'idle'|'running'|'done'|'error'|'cancelled', ms?: number, progress?: {done,total} };
  result:    { rows?: number, affected?: number };
  transaction: { mode: 'autocommit'|'manual', open: boolean, dirty: boolean };
  connection: { name: string, env?: Environment, health: 'healthy'|'stale'|'disconnected', latencyMs?: number };
}
```

**⭐ 常驻优先级**（窗口变窄时按此顺序隐藏）

```text
永不移除：执行状态 · 行数 · 事务状态 · 连接健康
优先隐藏：进度百分比 → 合并进执行状态
次优先  ：连接延迟
最后    ：环境标签（颜色点保留）
```

### 其他业务组件

| 组件 | 关键规格 | 验收 |
|---|---|---|
| `TableToolbar` | 行数限制/密度/导出/刷新/筛选摘要 | 筛选条件可见可清除 |
| `FilterPopover` | 执行位置切换 + 值多选 + 级联候选 | 见 §4.1 |
| `ColumnFilterBuilder` | 拖拽规则 + AND/OR | — |
| `SqlPreviewBar` | 底部可拖拽，实时 SQL | 高度持久化 |
| `EditIndicator` | 顶部黄条 + 未保存计数 | 见 §4.2 |
| `ResultTabs` | 结果 1/2/3 + 图表 + 消息 | 可固定结果 |
| `SchemaViewer` | 列/索引/FK/约束/统计 五 tab | 注释必须显示 |
| **`CapabilityPanel`** | **能力矩阵（见 §3.6bis）** | **组件零硬编码** |
| `StructureEditor` | 可视化改表 + 风险标注 | 见 §3.7 |
| `ImpactPanel` | 锁/扫描/重写/空间/建议 | 见 §3.7 |
| `DiagnosisPanel` | 按严重度分组 + 角标 | 见 §3.11 |
| `AiPanel` | 侧挂 380px + 流式 | 默认折叠 |
| `ChangeRequestList` | 待处理/我发起/已执行/已回滚 | — |

---

# Part 3 页面规格

> 20 个页面。U1 阶段（W3-6）全部用 MockDriver 实现

---

## 3.1 P01 启动页 / 首次运行

```text
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│                     [⬢ AgenticDB]                             │
│                                                                │
│                  欢迎使用 AgenticDB                            │
│              连接你的数据库，或先用演示库体验                  │
│                                                                │
│        ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│        │  [数据库]     │  │  [实验]      │  │  [文件]      │   │
│        │  新建连接     │  │  打开演示库   │  │  从 YAML 导入 │   │
│        │  MySQL/PG/... │  │  12 张真实感表 │  │  团队连接配置  │   │
│        │  [开始 →]     │  │  零风险       │  │  [选择文件]   │   │
│        └──────────────┘  └──────────────┘  └──────────────┘   │
│                                                                │
│      💡 演示库包含电商场景的 12 张表，含 50 万行数据            │
│         可完整体验浏览、查询、编辑、诊断、脱敏                  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**验收**：三卡片等宽；主按钮唯一；文案说清"零风险"。

---

## 3.2 P02 主窗口（布局骨架）

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ [●●●] AgenticDB  [prod-pg ▾] [搜索 ⌘K]        [⚡AI] [⚙] [─ □ ✕]          │ 36
├──────────────┬─────────────────────────────────────────────────────────────┤
│ ★ 收藏       │ ┌ users ─ orders* ─ + ─                              ─×┐ │ 36
│ ─────────    │ ├─────────────────────────────────────────────────────────┤
│ 数据库  ⊞   │ ┌────────────────────────────────────────────────────────┐ │
│  ├ prod-pg ● │ │                                                        │ │
│  │  users   │ │                    内容区（TabHost）                  │ │
│  │  orders  │ │                                                        │ │
│  └ dev-pg ○ │ │      右侧可展开 AI 面板（380px，可拖拽）                │ │
│  sqlite    ● │ │      展开时主区缩窄，不覆盖                             │ │
│ ─────────    │ │                                                        │ │
│ 工作台       │ └────────────────────────────────────────────────────────┘ │
│  Redis       │                                                             │
│  Mongo       │                                                             │
│ ─────────    │                                                             │
│ 工具         │                                                             │
│  ◆ 诊断 ⚠7  │                                                             │
│  变更 2      │                                                             │
│  数据工厂    │                                                             │
│  审计        │                                                             │
├──────────────┴─────────────────────────────────────────────────────────────┤
│ 完成 · 12,481 行 · 42ms · [事务 ON] · prod-pg 健康 8ms                    │ 24
└────────────────────────────────────────────────────────────────────────────┘
```

| 区 | 尺寸 | 可拖拽 | 可折叠 | 持久化 |
|---|---|---|---|---|
| 标题栏 | 36px | 否 | 否 | — |
| 侧栏 | 240px | 160-480 | 折叠为 48px | 是 |
| 标签栏 | 36px | 否 | 否 | 是 |
| AI 面板 | 380px | 280-600 | 是 | 是 |
| 状态栏 | 24px | 否 | 否 | — |

**验收**：重启后所有分栏位置、面板显隐都还原；侧栏折叠为 48px 时**保留环境色点和角标数字**。

---

## 3.3 P03 新建连接（分步对话框）

```text
步骤指示器：  ① 数据库类型  ────  ② 连接信息  ────  ③ 测试并保存

┌─ ① 选择数据库类型 ─────────────────────────────────────────────────┐
│  🔍 搜索数据库类型...                                             │
│  常用                                                             │
│  ┌────────┐ ┌───────────┐ ┌────────┐ ┌────────┐                 │
│  │ MySQL  │ │PostgreSQL │ │ SQLite │ │ Redis  │                 │
│  └────────┘ └───────────┘ └────────┘ └────────┘                 │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐                     │
│  │ MongoDB│ │ClickH. │ │DuckDB  │ │ SQLSrv.│                     │
│  └────────┘ └────────┘ └────────┘ └────────┘                     │
│  国产 / 其他                                                     │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐          │
│  │ 达梦    │ │人大金仓 │ │OceanB. │ │ 虚谷    │ │通用JDBC│          │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘          │
│  ☐ 显示实验特性（3）                                              │
│  ☐ 显示专属工作台（Redis / Mongo / MQ / 向量库 …）               │
│                    [+ 自定义 JDBC 连接]                           │
└──────────────────────────────────────────────────────────────────┘

┌─ ② 连接信息 ─────────────────────────────────────────────────────┐
│  名称    [生产-用户库   ]     环境 [生产 ▾]   颜色 [●]            │
│  主机    [10.0.1.100   ]     端口 [5432]                         │
│  数据库  [users        ]                                         │
│  用户名  [app_read     ]                                         │
│  密码    [••••••••  🔑]                                         │
│  ┄ 高级 ┄                                                       │
│  SSL          [仅服务器 ▾]                                      │
│  SSH 隧道     [开启]  跳板 [bastion-01 ▾]  用户 [jump] [🔑]      │
│  超时         [30] 秒                                           │
│  ☐ 保存密码到系统钥匙串（推荐）                                  │
│  ⓘ 连接配置建议写进 connections.yaml 提交到 git，团队共享        │
│              [上一步]              [测试连接 →]                  │
└──────────────────────────────────────────────────────────────────┘

┌─ ③ 测试结果 ─────────────────────────────────────────────────────┐
│  连接成功                                                        │
│  服务器版本   PostgreSQL 16.2                                   │
│  延迟         8 ms                                              │
│  权限        SELECT ✓  INSERT ✓  UPDATE ✓  DELETE ✓            │
│  ⓘ 未检测到 DELETE 权限，表格编辑将不可用                        │
│  ☐ 标记为生产环境（写操作需要解锁）                             │
│  ☐ 设为只读                                                     │
│              [上一步]    [保存并连接]     [仅保存]               │
└──────────────────────────────────────────────────────────────────┘
```

**验收**：步骤间可回退且**输入不丢失**；测试失败显示错误+原因+修复建议；测试成功页显示**权限检测结果**。

---

## 3.4 P04 表数据浏览（核心页）

```text
┌─ 表数据 ─────────────────────────────────────────────────────────────────┐
│ ⬤ users  [用户主表]  · 500 行 · 48 KB · 31 天增长 2%   [⚠ 3 个问题] 🔒 │
│ ┌──────────────────────────────────────────────────────────────────────┐│
│ │ 行数 [1000 ▾] │ 密度 ▪▫▫ │ 筛选 [status=1 ✕] │ ⇅ │ ⊞ │ ⌗ │ ⬇ │⚙││
│ └──────────────────────────────────────────────────────────────────────┘│
│ ┌──┬──────────┬────────┬────────────────┬─────────┬────────┬───────┐│
│ │# │ id  🔑   │ email  │ name           │ level   │ active │created││
│ │  │ bigint   │citext  │ text           │smallint │ bool   │date   ││
│ ├──┼──────────┼────────┼────────────────┼─────────┼────────┼───────┤│
│ │1 │ 1        │ a@x.com│ 张三           │ VIP     │ ✓      │09-12  ││
│ │2 │ 2        │ NULL   │ 李四           │ NORMAL  │ ✗      │09-13  ││
│ │3 │ 3        │ c@x.com│ <长文本…>      │ SVIP    │ ✓      │09-14  ││
│ │⋮ │ ⋮        │ ⋮      │ ⋮              │ ⋮       │ ⋮      │ ⋮     ││
│ └──┴──────────┴────────┴────────────────┴─────────┴────────┴───────┘│
│ 已加载 1,000 / 500        [加载更多]  [加载全部]   搜索范围：已加载行 │
├────────────────────────────────────────────────────────────────────────┤
│ (详情面板打开时从底部升起，高度可拖拽，持久化)                          │
└────────────────────────────────────────────────────────────────────────┘
```

| 项 | 值 |
|---|---|
| 表头两行 | 第一行字段名 + 主键/外键标记；第二行类型 |
| 冻结 | 默认冻结左侧行号 + 主键列 |
| 排序 | 点击列头 → 生成 `ORDER BY`（DB 侧），显示在工具栏摘要 |
| 空值 | 斜体灰色 `NULL`，与空串明确区分（`#10505` 教训） |
| 枚举列 | 显示含义，tooltip 显示 `= 1` |
| 诊断角标 | 表头右侧 `⚠ 3 个问题` |
| 只读标记 | 锁图标在标题区 |

**⭐ 行数提示**（`#12260` 教训）

```text
已加载 1,000 / 2,100,000        [加载更多]  [加载全部]

⌘F 搜索时提示：
  仅搜索已加载的 1,000 行（共 2,100,000）  [加载全部后搜索]
```

---

## 3.5 P05 查询编辑器

```text
┌─ 查询 ─────────────────────────────────────────────────────────────────┐
│ 数据库 [users ▾] Schema [public ▾] │ 执行 ⌘⏎ │ 选中 ⌘⇧⏎ │ 取消 ⌘. │ │
│ 事务 [自动提交 ▾] 隔离 [读已提交 ▾] 格式化 ⌥⇧F │ 历史 ⌘H │ 固定 ││
├────────────────────────────────────────────────────────────────────────┤
│  1  SELECT u.id, u.name, o.amount                                    │
│  2  FROM users u                                                     │
│  3  ── JOIN orders o ON o.user_id = u.id ──────────── [折叠]        │
│  4  WHERE o.status = 2                                               │
│  5  ORDER BY o.amount DESC                                           │
│  6  LIMIT 100;                                                       │
├────────────────────────────────────────────────────────────────────────┤
│  执行成功 · 100 行 · 23ms                                             │
├─ 结果 1 (100) ─┬─ 图表 ─┬─ 消息 (3) ──────────────────────────────────┤
│  [表格内容]     │[ECharts]│ [info] 加载完成                             │
│                │        │ [info] 使用索引 idx_user                      │
└────────────────┴────────┴─────────────────────────────────────────────┘
```

**⭐ ⭐ In-editor results**（`#12241` P0 诉求）

```text
执行后，结果直接显示在执行的 SQL 下面：

│  1  SELECT * FROM orders LIMIT 10;                                  │
│  2                                                                  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ id │ user_id │ status │ amount │  created_at │               │   │
│  │  1 │    42   │   2    │ 100.00 │ 09-12 10:00 │               │   │
│  │  ⋮ │   ⋮    │   ⋮    │   ⋮    │      ⋮      │               │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  100 行 · 12ms                                    [⤢ 展开为独立页]  │
│  2                                                                  │
```

**开关**：工具栏 `⤓ 内联结果`，默认开。

---

## 3.6 P06 表结构

```text
┌─ users · 表结构 ─────────────────────────────────────────────────────┐
│ ⬤ users  [用户主表]  public · 500 行 · 48 KB · [⚠ 3 个问题]          │
│ ┌─────────────┬────────────┬────────────┬──────────┬──────────────┐│
│ │ 列 (8)      │ 索引 (3)   │ 外键 (1)   │ 约束 (2) │ 统计         ││
│ ├─────────────┼────────────┼────────────┼──────────┼──────────────┤│
│ │ ⭐ 无主键    │            │            │          │ 行数    500   ││
│ │             │ 主键       │ 外键       │ 检查     │ 体积    48 KB ││
│ │ 名称    类型 │ 名称  唯一 │ 列 → 表.列 │ 名称     │ 增长    31d  ││
│ │ id     int  │ PK    ✓   │ user_id    │ ck_age   │ +2%           ││
│ │ email  citext│ idx_email │ → users.id │ ck_act   │ 增长曲线 [图] ││
│ │ ⓘ 敏感字段 │            │            │          │              ││
│ └─────────────┴────────────┴────────────┴──────────┴──────────────┘│
│                                                                     │
│ [DDL]  [知识]  [诊断 ⚠3]  [能力矩阵]  [打开结构编辑器]             │
└─────────────────────────────────────────────────────────────────────┘
```

**⭐ 注释必须显示**（`#12198` 教训）：每列注释在类型下方，`--text-tertiary` 11px。

---

## 3.6bis ⭐ 能力矩阵区块（v4 新增，由 YAML 声明驱动）

> **组件不许硬编码 `if (conn === "mysql")`。一律问声明。**
> 数据源：`virtual:adb-declarations` 的 `can()` / `warnsImplicitDdlCommit()` / `explainFormats()` / `mapType()`

### A. 能力矩阵面板

```text
┌─ 达梦 DM8 · 能力 ───────────────────────────────────────────────────┐
│  连接方式   JDBC Agent    传输家族  Oracle 兼容家族                    │
│  支持水平   操作级                       ⭐ 明确显示，不隐藏            │
├─ 引擎特性 ─────────────────────────────────────────────────────────┤
│  事务 DDL        ✗  ⭐ 不可回滚                                        │
│  自增机制        IDENTITY（不是 AUTO_INCREMENT）                      │
│  序列对象        ✗ 无                                                 │
│  EXPLAIN 格式    仅文本                                               │
│  参数占位符      :name                                                │
│  标识符引用      "…"                                                  │
├─ 产品能力 ─────────────────────────────────────────────────────────┤
│  查询执行 ✓  元数据 ✓  对象浏览 ✓  源码 ✓  架构搜索 ✓                │
│  ER 图   ✓  数据编辑 ✓  改表 ✓  导入 ✓  传输 ✓  SQL 文件 ✓          │
│  建库    ✗  血缘 ✓  EXPLAIN ✓  用户管理 ✗                            │
└──────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：把 `postgresql.yaml` 的 `databaseCreate` 改成 `true` → 重启 → 「新建数据库」菜单出现，**组件零改动**。

### B. 由声明驱动的 UI 提示

```text
MySQL 结构编辑器顶部：
┌────────────────────────────────────────────────────────────────┐
│ ⚠ MySQL 的 DDL 会隐式提交，无法通过事务回滚。                    │
└────────────────────────────────────────────────────────────────┘
  ← 来自 traits.transactionalDdl: false

达梦侧栏：不渲染「序列」节点
  ← 来自 traits.sequenceObjects: false

达梦 Explain 面板：只显示「文本」选项，不显示 JSON/树
  ← 来自 traits.explainFormats: [text]

达梦导出预览：
  ALTER TABLE "ORDERS" ADD "REMARK" VARCHAR(200) IDENTITY(1,1);
                                        ^^^^^^^^^^^^^
  ← 来自 traits.identityKind: identity（不是 AUTO_INCREMENT）
  ← 这正是 DBX #12124 的根因

达梦传输对话框：
┌────────────────────────────────────────────────────────────────┐
│ ⚠ 3 个字段的类型映射可能丢失精度                                 │
│                                                                │
│  表.字段          源类型         目标类型          风险         │
│  orders.amount   NUMBER         DECIMAL(38,10)    🟠 精度收窄  │
│  orders.created  DATE           DATETIME          🟠 时区语义  │
│  orders.rate     NUMBER(5,2)    DECIMAL(38,10)    ✅ 安全       │
│                                                                │
│                          [取消]        [仍然继续]               │
└────────────────────────────────────────────────────────────────┘
  ← 来自 transfer-families/oracle.yaml 的 lossy: true
```

### C. 支持水平徽标

```text
supportLevel: full         → 无徽标
supportLevel: operate      → 侧栏灰化徽标 "操作级"  tooltip: 有已知限制
supportLevel: read-only    → 侧栏灰化徽标 "只读"    写操作菜单全禁用
supportLevel: experimental → ⭐ 默认隐藏，需 设置 → 实验特性 开启
```

### D. 连接选择器（按声明渲染）

```text
┌─ ① 选择数据库类型 ─────────────────────────────────────────────────┐
│  🔍 搜索...                                                       │
│  常用                                                            │
│  ┌────────┐ ┌───────────┐ ┌────────┐ ┌────────┐                  │
│  │MySQL   │ │PostgreSQL │ │SQLite │ │ Redis  │                  │
│  └────────┘ └───────────┘ └────────┘ └────────┘                  │
│  国产 / 其他                                                     │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐          │
│  │达梦    │ │人大金仓 │ │OceanB. │ │ 虚谷    │ │通用JDBC│          │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘          │
│  ☐ 显示实验特性（3）                                              │
│  ☐ 显示专属工作台（Redis / Mongo / MQ / 向量库 / 搜索 …）        │
└──────────────────────────────────────────────────────────────────┘
```

**验收**：
- `specializedSurface: true` 的类型**单独分组**
- `experimental` 默认不显示，勾选后显示灰色徽标
- `order` 字段决定显示顺序

---

## 3.7 P07 结构编辑器 ⭐ 差异化核心

```text
┌─ 结构编辑器 · users ─────────────────────────────────────────────────┐
│ 环境 [prod ▾]   表 public.users   [DDL] [diff vs staging] [放弃]     │
│ ⚠ MySQL 的 DDL 会隐式提交，无法通过事务回滚。                        │ ← ⭐ 声明驱动
├────────────────────────────────────────────────────────────────────────┤
│ ┌─ 列 ────────────────────────────────────────────────────────────┐  │
│ │ [+ 新增列]                                                        │  │
│ │ ┌──────┬──────────┬──────┬────┬──────┬────────┬────────────────┐ │  │
│ │ │ 名称  │ 类型      │ 可空 │默认│ 键   │ 注释   │ 风险           │ │  │
│ │ ├──────┼──────────┼──────┼────┼──────┼────────┼────────────────┤ │  │
│ │ │ id    │ bigint   │  ✗   │ —  │ 🔑PK │ 主键   │ —              │ │  │
│ │ │ email │ citext   │  ✓   │ —  │ 🔗   │ 邮箱   │ ⓘ 敏感字段      │ │  │
│ │ │ name  │ text     │  ✗   │ '' │  —   │ 姓名   │ 🟢 安全        │ │  │
│ │ │ remark│ varchar(20)  │  │  │  │  │  │  │  │  │ 🔴 截断      │ │  │
│ │ │       │ ↓ varchar(200)       │  │  │  │  │  │ ⚠ 现有最长 84   │ │  │
│ │ └──────┴──────────┴────┴────┴──────┴────────┴────────────────┘ │  │
│ └───────────────────────────────────────────────────────────────────┘  │
│ ┌─ 索引 ──────────────┐ ┌─ 外键 ─────────────────┐                  │
│ │ [+ 新增]             │ │ [+ 新增]                │                  │
│ │ PK   (id)      ✓    │ │ user_id → users.id      │                  │
│ │ idx_email (email)   │ │                          │                  │
│ └──────────────────────┘ └─────────────────────────┘                  │
├────────────────────────────────────────────────────────────────────────┤
│ ┌─ 影响评估 ───────────────────────────────────────────────────────┐  │
│ │  🔒 AccessExclusive          建议: 低峰期执行                      │  │
│ │  ⏱ 2 – 40 秒   依据: 500 行 × 120 B；不重写数据                  │  │
│ │  📊 扫描 0 行 · 临时空间 0 B                                       │  │
│ │  ⚠ 1 项风险: remark 改小导致 12 行数据被截断  [去掉该列]           │  │
│ └─────────────────────────────────────────────────────────────────┘  │
│ ┌─ 生成的 SQL ────────────────────────────┐ ┌─ 回滚 ──────────────┐  │
│ │ ALTER TABLE users ADD COLUMN remark     │ │ [x] DROP COLUMN      │  │
│ │   varchar(200);                          │ │ ⚠ 原数据丢失         │  │
│ │ COMMENT ON COLUMN users.remark IS '备注';│ │                      │  │
│ │                          [复制] [编辑]    │ │ [x] ALTER TYPE back; │  │
│ └──────────────────────────────────────────┘ └─────────────────────┘  │
├────────────────────────────────────────────────────────────────────────┤
│ 策略 我确认 / 生产双人审批   执行窗口 [不限 ▾]                        │
│ [x] 执行前自动快照 (users.sql.gz)                                     │
│                        [保存草稿]  [生成Migration]      [提交执行]    │
└────────────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**
- 风险标注**紧贴字段**，不在底部汇总
- 影响评估**改一下就重算**，无需点按钮
- 时长给**区间 + 依据**，不给点值
- 回滚默认**展开可见**
- 快照默认**勾选**

---

## 3.8 P08 诊断面板 ⭐

```text
┌─ 诊断 ──────────────────────────────────────────────────────────────────┐
│ [全库体检] [当前表 users] [上次 3 天前]      [导出报告] [重新扫描]    │
├─────────────────────────────────────────────────────────────────────────┤
│ 严重 (2)                                                                │
│ ⛔ users 没有主键                                                      │
│    → UPDATE/DELETE 无法生成安全 WHERE 条件                             │
│    [添加主键 (id)]  [查看详情]                                         │
│ ⛔ 金额字段跨表类型不一致                                               │
│    users.amount    numeric(20,4)                                      │
│    orders.amount   numeric(10,2)      🟠 精度范围不一致                 │
│    refund.amount   double precision 浮点，金额禁用                      │
│    → 迁移时必然精度丢失                                                │
│    [查看受影响表] [统一类型] [导出到迁移计划]                          │
│                                                                         │
│ 警告 (3)                                                                │
│ ⚠ email 无索引 —— 但查询热度显示 23% 查询带 email 过滤  [+ 建索引]     │
│ ⚠ user_id 有外键但无索引（JOIN 会全表扫）                             │
│ ⚠ email/phone 被识别为敏感字段，库内未加密  [一键脱敏方案]             │
│                                                                         │
│ 优化建议 (2)                                                             │
│ 💡 建议索引 idx_user_level (user_id, level)                           │
│    依据: 近 30 天 41% 查询带此条件，预估 3.4s → 0.02s   [+ 创建索引]  │
└─────────────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：每条发现必须同时有 **量化影响** + **可点击的下一步**。

---

## 3.9 P09 数据工厂（脱敏向导）

```text
┌─ 数据工厂 ─────────────────────────────────────────────────────────────┐
│ [新建任务] [历史 12] [规则库]                          [调度: 每周一]   │
├────────────────────────────────────────────────────────────────────────┤
│  ①源 ──── ②选表 ──── ③识别 ──── ④一致性 ──── ⑤输出 ──── ⑥校验      │
│   ●       ○         ○         ○         ○         ○                  │
├────────────────────────────────────────────────────────────────────────┤
│ ① 源                                                                       │
│  连接 [prod-pg ▾]   Schema [public ▾]                                 │
│  方式 (●) 每表采样 N 行   ( ) 按主键范围   ( ) 按时间范围               │
│  采样量 [1000] 行/表    范围 [2024-01-01] ~ [今天]                      │
│  [下一步 →]                                                               │
├────────────────────────────────────────────────────────────────────────┤
│ ③ 识别敏感字段                                                              │
│  ⏳ 已扫描 12,486,234 行样本 · 发现 47 个敏感字段                         │
│  表.字段          识别方式    规则      处理       样本           [预览] │
│  users.phone     内容匹配    中国手机号 138****5678 13812345678  [改]  │
│  users.idcard    内容匹配    身份证    FPE假名化  3101**********123X│  │
│  users.email     列名匹配    邮箱      FPE假名化  a@x.com         [改]  │
│  users.name      列名匹配    姓名      保留姓氏  张三             [改]  │
│  ⋯ 还有 43 个       [+ 添加规则] [导入模板]                             │
├────────────────────────────────────────────────────────────────────────┤
│ ④ 一致性与分布                                                              │
│  ☑ 外键值保持一致                                                         │
│    users.id → orders.user_id   同一值 → 同一脱敏值                      │
│  ☑ 分布保持                                                               │
│    amount 分位数  P50 12.00→11.98  P95 1,240→1,236   误差 0.3%  ✓      │
│    level  频次   VIP:18% SVIP:5% NORMAL:77%          误差 <0.5% ✓      │
├────────────────────────────────────────────────────────────────────────┤
│ ⑥ 校验                                                                     │
│  ☑ 结构一致  42 表 → 42 表（列/类型/索引/约束全等）                      │
│  ☑ 数据一致  行数一致 · 校验和一致                                       │
│  ☑ 外键有效  0 条悬空引用                                                  │
├────────────────────────────────────────────────────────────────────────┤
│                                   [上一步]              [生成]         │
└────────────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：6 步向导每步可回退；**规则改动必须能预览样本**。

---

## 3.10 P10 变更工作台 / 审批

```text
┌─ 变更 ──────────────────────────────────────────────────────────────────┐
│ [待审批 2] [我发起的 5] [已执行 128] [已回滚 3]        [+ 新建变更]    │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ CR-2026-1016-0042 ───────────────────────────────────────────────┐ │
│ │ 状态 [待审批]   环境 prod   表 public.orders                        │ │
│ │ 申请人 jason   提交 2026-10-16 14:32   审批人 [待指派]              │ │
│ │ 来源 AI Agent · claude-code                                         │ │
│ │ 理由 用户请求："给订单表加备注字段"                                  │ │
│ ├────────────────────────────────────────────────────────────────────┤ │
│ │ 变更内容                                                            │ │
│ │   ADD COLUMN remark varchar(200) NULL COMMENT '备注'                │ │
│ ├────────────────────────────────────────────────────────────────────┤ │
│ │ 影响  AccessExclusive · 2–40 秒 · 扫描 0 行 · 快照将自动创建        │ │
│ │ 回滚  ALTER TABLE orders DROP COLUMN remark;                        │ │
│ ├────────────────────────────────────────────────────────────────────┤ │
│ │ 审批意见 [                                                    ]      │ │
│ │ ☐ 我已审阅影响评估与回滚方案                                        │ │
│ │                    [拒绝]  [修改后批准]      [批准并执行]           │ │
│ └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3.11 P11 审计

```text
┌─ 审计 ──────────────────────────────────────────────────────────────────┐
│ [日志] [报表] [导出]                              保留 90 天 [设置]   │
├─────────────────────────────────────────────────────────────────────────┤
│ 时间 [2026-10-01]~[今天]  操作人 [全部▾]  危险级 [全部▾]  来源 [全部▾] │
├─────────────────────────────────────────────────────────────────────────┤
│ 时间            操作人     来源      动作           结果              │
│ 10-16 14:32     claude    MCP       ALTER orders   已拒绝             │
│                                               CR-0042 缺少审批        │
│ 10-16 14:28     jason     桌面      UPDATE users   3 行 · 42ms        │
│ 10-16 11:04     jason     桌面      DROP DATABASE  已拒绝             │
│                                               生产环境禁止            │
│ 10-15 17:45     alice     Web       SELECT *      完成 12,481 行     │
│                              FROM users              全表扫描        │
├─────────────────────────────────────────────────────────────────────────┤
│ 本周 1,284 次执行 · 23 次拒绝 · 8 次生产 · 312 次 AI Agent            │
└─────────────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：审计必须能回答：**谁做的？做了什么？发生了什么？**

---

## 3.12 P12 AI 面板

```text
┌─ AI ───────────────────────────────────────────┐
│ 模型 [Claude Sonnet ▾] 模式 [问答|自动]  [⚙]     │  ← 默认折叠
├──────────────────────────────────────────────────┤
│ 你: 查一下上月失败的订单                           │
│                                                  │
│ AI: 基于 orders 表（50,000 行）：                 │
│     [✓数据库注释] pay_status: 1=待付 2=成功 3=失败 │
│     [⚠AI推断] "上月"指上一个完整自然月           │
│                                                  │
│     SELECT o.id, o.amount, o.pay_time            │
│     FROM orders o                                │
│     WHERE o.pay_status = 3                       │
│       AND o.pay_time >= date_trunc('month',      │
│                        now() - interval '1 mon') │
│       AND o.pay_time <  date_trunc('month', now())│
│     LIMIT 100;                                   │
│                                                  │
│     ⏱ 预计 0.04s · 命中 idx_pay_time · 约 1,204 行│
│                                                  │
│   [在编辑器打开] [复制] [▶执行] [加为结果页]      │
│                                                  │
├──────────────────────────────────────────────────┤
│ 附件: [users ▾] [prod-pg ▾] [最近查询 ▾]          │
│ 输入...                              [发送 ⏎]    │
└──────────────────────────────────────────────────┘
```

**⭐ 验收**：默认折叠；展开时主区**缩窄**不覆盖；每条推断有来源标注；SQL 旁有影响预估；**永不自动执行**。

---

## 3.13 P13 MCP 权限设置

```text
┌─ 设置 · MCP ───────────────────────────────────────────────────────────┐
│ ⓘ 当前实际生效的权限                                                  │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │  18 个连接：只读                                                   │ │
│ │   2 个连接：可写数据   ⚠ orders-write-test, staging-sandbox       │ │
│ │   0 个连接：完全访问                                               │ │
│ │                                          [查看这 2 个连接]        │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ 默认权限（应用于所有连接）                                             │
│ (●) 只读    ( ) 可写数据    ( ) 完全访问                              │
│ 连接范围                                                               │
│ (●) 所有连接    ( ) 自定义范围（18 / 24 已选）                        │
│ ┌─ 例外规则（⭐ 始终显示，不受上方范围影响）──────────────────────┐   │
│ │ 连接                  权限        来源    操作                     │   │
│ │ orders-write-test   可写数据     手动    [改] [删除]              │   │
│ │ staging-sandbox     可写数据     手动    [改] [删除]              │   │
│ │                                       [+ 添加例外]                 │   │
│ └──────────────────────────────────────────────────────────────────┘   │
│ ⛔ 永不允许                                                            │
│ ☑ DROP DATABASE                    [添加禁止项]                      │
│ ☑ GRANT / REVOKE                    [添加禁止项]                      │
│ 保存预览: 应用后 18 只读 · 2 可写 · 0 全权                            │
│                                          [取消]            [保存]     │
└────────────────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：切换到「所有连接」时**例外区依然可见**（`#11503` 教训）。

---

## 3.14 P14 设置

```text
┌─ 设置 ──────────────────────────────────────────────────────────────────┐
│ 通用 │ 外观 │ 编辑器 │ 连接 │ AI │ MCP │ 诊断 │ 关于                  │
├─────────────────────────────────────────────────────────────────────────┤
│ 外观                                                                  │
│ 主题        [暗色 ▾]   [跟随系统] [暗色] [亮色]                        │
│ UI 缩放     [100% -][100%]  [+]  [重置 ⌘0]                            │
│ 密度        [标准 ▾]   [紧凑] [标准] [宽松]                            │
│ 字号        ──────●──────  [13px]                                      │
│ ☐ 开机自启    ☐ 关闭前确认未保存    ☐ 高对比度模式                     │
└────────────────────────────────────────────────────────────────────────┘
```

**⭐ 缩放必须 50%-200% 全部正常**（`#12383` 教训）。
**永远提供 `⌘0` 重置 + 设置页可见的重置按钮。**

---

## 3.15-3.20 其他页面

| 页面 | 核心规格 |
|---|---|
| **P15 知识编辑器** | 业务含义/枚举/别名/JOIN 路径/坑位/负责人 + 可信度标注 + 查询热点自动填 |
| **P16 工作台（Redis）** | Key 树 + 详情 + 命令台 + TTL/类型徽标 |
| **P17 工作台（Mongo）** | 集合树 + 文档浏览器（嵌套展开）+ 索引 |
| **P18 传输** | 源→目标 + 表选择 + ⭐ **类型映射计划（lossy 必须显式确认）** |
| **P19 ER 图** | React Flow + dagre 自动布局 + 导出 SVG |
| **P20 专注模式** | 隐藏侧栏/工具栏/状态栏（`⌘⇧F`），退出恢复 |

---

# Part 4 交互模式库

## 4.1 筛选模式 ⭐ 四级 + 级联

### 弹层

```text
┌─ status ──────────────────────────────────┐
│ [全部 ▾]                            [排序 ▾]│
│                                              │
│ 执行位置                                      │
│ (●) 当前结果 (内存)   已加载 12,481 行        │
│ ( ) 数据库 (WHERE)    在 DB 执行，无分页限制   │
│                                              │
│ WHERE  [status IN (1, 2)              ]     │  ← 选"数据库"时出现
│                                              │
│ 值                                             │
│ ☑ 1  待支付    12,481                       │
│ ☑ 2  已支付    48,201                       │
│ ☑ 3  已发货    31,002                       │
│ ☐ 4  已完成     8,204   ← 被其他筛选排除      │
│ ☐ 9  已取消     1,204                        │
│                                              │
│ [全选] [反选] [仅空] [无空]  [清空]          │
│                        [重置]      [应用]      │
└──────────────────────────────────────────────┘
```

### ⭐ 级联（关键）

```typescript
// 候选值必须由"除本列外的其他筛选条件"决定
async function candidates(col: Column, active: FilterRule[]): Promise<ValueCount[]> {
  const others = active.filter(r => r.col !== col);
  // ⭐ 带上其他筛选条件，但不带本列的
  const sql = buildCountQuery(col, others);
  const rows = await query(sql);
  return rows.map(r => ({
    value: r.value,
    count: r.n,
    // ⭐ 当前筛选下 count 为 0 的值 → 禁用 + 灰显，而不是隐藏
    //   隐藏会让用户困惑"我的值去哪了"
    enabled: r.n > 0 || currentSelection.includes(r.value),
  }));
}
```

### 搜索范围明示

```text
表格底部：[⌘F 仅搜索已加载的 1,000 行 / 共 2,100,000]  [加载全部后搜索]
```

## 4.2 编辑模式 ⭐ 不自动保存

### 状态机

```text
Idle ──编辑──> Dirty ──保存──> Saving ──成功──> Idle
                 │                └──失败──> Error(保留 Dirty)
                 ├──再次编辑──> Dirty(累加)
                 ├──撤销 ⌘Z──> Idle / Dirty(减少)
                 └──切走/关闭──> ⛔ 阻止 + 提示
```

### UI

```text
┌──────────────────────────────────────────────────────────────────────┐
│ ✏️ 3 处未保存的修改                      [查看 SQL] [放弃] [保存 ⌘S] │
└──────────────────────────────────────────────────────────────────────┘

┌─ 将执行的 SQL (3 条) ──────────────────────── [⤢] [清空] ──────────┐
│ UPDATE orders SET status = 2 WHERE id = 42;                          │
│ INSERT INTO orders (user_id, amount) VALUES (7, 10000);              │
│ UPDATE orders SET status = 9 WHERE id IN (43,44,45);                 │
└──────────────────────────────────────────────────────────────────────┘
```

### 关闭拦截

```text
┌─ 有未保存的修改 ──────────────────────────────┐
│ orders 表有 3 处未保存的修改                    │
│              [取消]   [放弃修改]   [保存并关闭]  │
└────────────────────────────────────────────────┘
```

## 4.3 确认模式（四级）

| danger | 弹层 | 按钮 | 勾选 | 可 Esc |
|---|---|---|---|---|
| 0 | 无 | — | — | — |
| 1 | Toast | 确认 + 撤销 | 否 | — |
| 2 | Dialog | 取消 / 确认执行 | 否 | 是 |
| 3 | Dialog | 取消 / 确认执行 | 强制 | 是 |

**danger=2 弹层必含**

```text
┌─ ⚠ 危险操作 ─────────────────────────────────────────────────┐
│                                                              │
│  ALTER TABLE public.orders ADD COLUMN remark varchar(200)    │
│                                      NOT NULL DEFAULT '';    │
│                                                              │
│  ┌─ 影响 ──────────────────────────────────────────────────┐  │
│  │ 🔒 AccessExclusive · 2 – 40 秒 · 扫描 0 行              │  │
│  │ 📸 快照将自动创建 (~180 MB)                             │  │
│  │ ↩ 回滚 ALTER TABLE orders DROP COLUMN remark;            │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  操作人 jason@dev-mbp      来源 手动操作                      │
│                                                              │
│                              [取消]           [确认执行]      │
└──────────────────────────────────────────────────────────────┘
```

**danger=3 追加**：`⛔ 不可逆 · 永久删除 8,412,904 行（4.2 GB）` + 必勾选框。

## 4.4 加载模式

| 时长 | 表现 |
|---|---|
| < 200ms | **不显示任何指示**（避免闪烁） |
| 200ms - 3s | 局部旋转图标 / 顶部 2px 进度条 |
| > 3s | 进度条 + 已完成/总数 + 预计剩余 |
| > 10s | **必须提供取消按钮** |

**长查询执行中**

```text
状态栏：执行中 12.4s │ ▓▓▓▓▓░░░░ 45% │ [取消 ⌘.]
```

**大批量执行**

```text
┌────────────────────────────────────────────────────────────────────┐
│ 正在写入 45,231 / 120,000 行  ▓▓▓░░░ 38%  预计剩余 12s   [取消]  │
│ orders  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░  已完成                                    │
│ items   ▓▓▓▓▓░░░░░░░░░░░░░  进行中                                    │
└────────────────────────────────────────────────────────────────────┘
```

## 4.5 空状态

| 类型 | 标题 | 动作 |
|---|---|---|
| 无连接 | 连接你的第一个数据库 | [新建连接] [打开演示库] |
| 无数据库 | 该连接下没有可见数据库 | [刷新] |
| 无表 | 该模式下没有表 | [刷新] |
| 表无数据 | 此表为空 | [生成测试数据] |
| 搜索无结果 | 没有匹配「xxx」的对象 | [搜注释] [清空搜索] |
| 未加载 | （节点左侧显示 +） | — |
| 无权限 | 没有 `SELECT` 权限 | [复制申请语句] |
| 离线 | 无法连接到服务器 | [重连] |
| 截断 | 已显示 500 / 10,000 | [显示全部] |
| 功能受限 | 此功能需团队版 | [了解团队版] |

**⭐ 验收**：每个空状态必须有**至少一个明确的下一步动作**。

## 4.6 错误模式

```text
┌─ ⛔ 连接失败 ─────────────────────────────────────────────┐
│  Server error: `ERROR 1045 (28000): Access denied for     │
│  user 'app'@'10.0.1.5' (using password: YES)`             │
│                                                             │
│  💡 可能原因                                                │
│  • 密码错误（注意：SSH 隧道下 @ 后面是隧道入口 IP）         │
│  • 账号没有该库的权限                                       │
│  • MySQL 的 user@host 规则未授权此来源                      │
│                                                             │
│  [重试]  [修改连接]  [查看详细日志 ▾]                       │
│  ─────────────────────────────────────────────────────────  │
│  发生于 2026-10-16 14:32:07 · "prod-pg" · 耗时 1,204ms     │
└─────────────────────────────────────────────────────────────┘
```

**⭐ 验收**：错误必须**可行动** —— 不只报错文字，还要给可能原因和下一步按钮。

**SQL 错误特殊处理**

```text
┌─ ⛔ 查询失败 ─────────────────────────────────────────────┐
│  ERROR: relation "oder" does not exist                    │
│  位置: 第 2 行, 第 10 列          [跳转到错误位置]         │
│  你是否想找:                                                   │
│    orders       public.orders      50,000 行  [订单主表]    │
│    order_items  public.order_items 180,000 行 [订单明细]    │
│  [打开 orders]  [替换为 orders 并重新执行]  [关闭]           │
└─────────────────────────────────────────────────────────────┘
```

## 4.7 拖拽模式

| 拖什么 | 放哪 | 效果 |
|---|---|---|
| 列头 | 列头之间 | 调整列顺序 |
| 列头右边缘 | 列头之间（宽 4px） | 调整列宽 |
| 列头 | 左侧/右侧冻结区 | 设置冻结 |
| 字段行 | 字段列表 | 跨表复制字段 |
| SQL 文件 | 侧栏 | 加载文件 |
| 标签 | 标签栏中间 | 调整标签顺序 |
| 分隔线 | — | 调整面板尺寸 |

**拖拽反馈**：源元素半透明（0.4）+ 目标位置显示插入线（2px `--accent`）。

---

# Part 5 快捷键全表

## 5.1 全局

| 快捷键 | 动作 |
|---|---|
| `⌘K` | 命令面板 |
| `⌘⇧P` | 快速打开（连接/表/文件/历史） |
| `⌘\` | 侧栏折叠/展开 |
| `⌘⇧F` | 专注模式 |
| `⌘0` | 重置 UI 缩放 |
| `⌘,` | 设置 |
| `⌘1..9` | 切换到第 N 个标签 |
| `⌘W` | 关闭当前标签 |
| `⌘⇧T` | 恢复刚关闭的标签 |
| `⌘F` | 聚焦当前上下文搜索 |
| `⌘⌥A` | AI 面板开关 |
| `F5` | 刷新当前对象 |
| `Esc` | 关闭弹层 / 取消选择 |
| `F1` / `?` | 快捷键速查 |

## 5.2 连接与树

| 快捷键 | 动作 |
|---|---|
| `⌘N` | 新建连接 |
| `⇧⌘N` | 新建查询 |
| `↑` / `↓` | 树中移动 |
| `→` / `←` | 展开 / 折叠 |
| `Enter` | 打开选中对象 |
| `⇧Enter` | 在新标签打开 |
| `⌘⏎` | 在后台打开 |
| `⌘R` | 重命名 |
| `⌘D` | 复制对象 |
| `⌘⌫` | 删除 |
| `⌘P` | 置顶/取消置顶 |
| `⌘F` | 聚焦树搜索 |

## 5.3 编辑器

| 快捷键 | 动作 |
|---|---|
| `⌘⏎` | 执行当前语句 |
| `⇧⌘⏎` | 执行选中部分 |
| `⌘.` | 取消执行 |
| `⌥⇧F` | 格式化 |
| `⌥⇧M` | 压缩 |
| `⌘/` | 注释切换 |
| `⌘D` | 复制当前行 |
| `⇧⌥↓/↑` | 移动行 |
| `⌘⇧K` | 删除行 |
| `⌘Click` | 符号跳转 |
| `Tab` | 缩进 / 接受补全 |
| `Esc` | 关闭补全 |

## 5.4 表格

| 快捷键 | 动作 |
|---|---|
| `⌘A` | 全选当前页 |
| `⌘C` / `⇧⌘C` | 复制选中 / 复制为 SQL |
| `⌘V` | 批量粘贴 |
| `⇧⌘Z` | 撤销编辑 |
| `⌘S` | 保存编辑（执行 SQL 预览） |
| `Enter` / `F2` | 编辑单元格 |
| `Delete` | 标记删除 |
| `⌘D` | 克隆行 |
| `⌘F` | 网格内搜索 |
| `⌃H` | 替换（⚠ 不能用 `⌘⇧F`，被专注模式占用） |
| `Space` | 打开/关闭单元格详情 |
| `↑` / `↓` | 移动行 |
| `Home` / `End` | 行首/行尾 |
| `⇧Enter` | 跳到下一空单元格 |

## 5.5 冲突解决（重要）

```text
⚠ 与浏览器/系统冲突的键必须重新分配：

⌘⇧F  →  ⛔ 不用于替换（专注模式占用）
替换   →  ⌃H（macOS）/ Ctrl+H（Windows）

⌘\    →  Windows 下可能被占用 → 提供设置项自定义

⌘0    →  保留（重置缩放，最高优先级）

⌘⏎    →  表格中不绑定（表格里 ⌘⏎ = 保存编辑）
```

## 5.6 快捷键自定义

```text
设置 › 快捷键
  每个动作可搜索、可改键
  显示冲突检测（同一键绑定两个动作时标红）
  提供"恢复默认"和"导出/导入键位"
```

---

# Part 6 响应式与缩放

## 6.1 断点

| 宽度 | 布局 |
|---|---|
| < 900 | 侧栏自动折叠为图标条；工具栏图标化；AI 面板改为覆盖 |
| 900-1280 | 侧栏可折叠；AI 面板覆盖式 |
| 1280-1800 | **默认三栏**；AI 面板可常驻 |
| > 1800 | 表格默认 2000 行分页；支持左右分屏 |

## 6.2 ⭐ 缩放（`#12383` 教训）

```text
必须支持 50% – 200% 全程正常：

1. 所有浮层用相对定位或百分比
   ❌ top: 600px      → ✅ bottom: var(--space-4)
   ❌ height: 720px   → ✅ max-height: 80vh

2. 设置页所有按钮在 200% 仍可见

3. ⭐ 永远提供重置入口：
   - 设置 › 外观 › 重置
   - 快捷键 ⌘0
   - 缩放 > 150% 时标题栏出现「缩放 175% [重置]」chip

4. 状态栏最小宽度下：只保留执行状态/行数/事务/连接健康
```

## 6.3 侧栏折叠态

```text
展开 240px              折叠 48px
┌──────────────┐      ┌────┐
│ ★ 收藏       │      │ ★  │
│ 数据库 ⊞     │      │ 数据库│
│  ├ prod-pg ● │      │ prod ●│  ← 环境色点 + 健康点保留
│  │  users    │      │ ●  │
│  │  orders   │      │ ●  │
│  sqlite    ● │      │ dev│
│ 工作台        │      │ 工作台│
│ 工具         │      │ 工具 │
│  ◆ 诊断 ⚠7  │      │ ◆7 │  ← ⭐ 角标数字保留
│  变更 2      │      │ 2  │
│  数据工厂    │      │    │
└──────────────┘      └────┘
```

**⭐ 折叠时必须保留**：环境色点、健康点、角标数字。

---

# Part 7 可访问性

| 项 | 要求 | 验收 |
|---|---|---|
| 键盘全流程 | 所有功能可键盘完成 | 拔掉鼠标走一遍核心 12 步 |
| 焦点可见 | 2px `--accent` 焦点环 | Tab 遍历可见 |
| **焦点管理** | 弹窗开→焦点入；弹窗关→**焦点归还触发元素** | ⛔ DBX 曾有"弹窗关不掉"的 bug |
| 色彩对比 | 正文 ≥ 4.5:1 | 工具测全部 token |
| **色彩非唯一语义** | 危险级用颜色+图标+文案 | 灰度模式下仍可区分 |
| 屏幕阅读器 | `aria-label` / `aria-expanded` / `role` 完整 | |
| 表格语义 | `role="grid"` + `aria-rowcount` | |
| 字号缩放 | 200% 不破布局 | ⭐ `#12383` |
| 动效 | `prefers-reduced-motion` | |
| 语言 | `lang="zh-CN"` + i18n 不用硬编码 | |
| 错误不只靠颜色 | 红色 + 图标 + 文字 | |
| 超时 | 所有异步操作有超时和取消 | |

---

# Part 8 设计验收清单

> U2 阶段逐项验收。**任一项不过，不进 D 阶段**

## 8.1 视觉一致性

- [ ] 所有间距是 4px 倍数
- [ ] 所有圆角 ≤ 6px（除空状态图标容器）
- [ ] UI 字号基准 13px，无杂值
- [ ] 危险四级颜色全应用一致，无第二套红色
- [ ] 图标尺寸统一（14/16/48 三种）
- [ ] 暗/亮主题都测过，无硬编码颜色
- [ ] 中英文混排正常（表名英文 + 注释中文）

## 8.2 交互完整性

- [ ] 30 个组件的所有 state 都实现
- [ ] 所有异步操作都有：加载态 + 错误态 + 取消/重试
- [ ] 所有危险操作都有：影响量化 + 确认分级
- [ ] 所有列表都有：空状态 + 超量提示 + 加载更多
- [ ] 所有弹窗都能 Esc 关闭，焦点能归还
- [ ] 所有拖拽都有拖拽中的视觉反馈

## 8.3 ⭐ 能力声明驱动（v4 新增）

- [ ] 组件源码中 **搜不到** `conn === "mysql"` 这类硬编码
- [ ] 改 `postgresql.yaml` 的 `databaseCreate: true` → 重启 → 菜单出现
- [ ] MySQL 结构编辑器显示 `⚠ DDL 会隐式提交`
- [ ] 达梦无序列节点
- [ ] 达梦 Explain 只有文本格式选项
- [ ] 达梦导出用 `IDENTITY(1,1)` 而非 `AUTO_INCREMENT`
- [ ] 达梦传输弹出「类型映射可能丢失精度」

## 8.4 性能（MockDriver 压测）

- [ ] 冷启动到能点开表 ≤ 3s
- [ ] 1 万对象侧栏滚动 60fps
- [ ] 50 万行表格滚动 60fps，无白屏
- [ ] 1000 列宽表打开 ≤ 500ms
- [ ] 搜索候选 < 100ms
- [ ] 单元格编辑反馈 < 16ms
- [ ] 切换标签页 < 200ms

## 8.5 缩放与响应式

- [ ] 50% / 75% / 100% / 125% / 150% / 200% 全正常
- [ ] ⛔ 165% 时设置页所有按钮可见（`#12383`）
- [ ] ⛔ 任意缩放下都能恢复 100%
- [ ] 900px / 1280px / 1920px 宽度正常
- [ ] 侧栏折叠/展开正常

## 8.6 可访问性

- [ ] 键盘走完核心 12 步
- [ ] 焦点可见 + 管理正确
- [ ] 灰度模式下危险级仍可区分
- [ ] `prefers-reduced-motion` 生效

## 8.7 ⭐ Navicat 对照（核心）

在 MockDriver 上完成以下 12 步，与 Navicat 逐项对比：

```text
 1. 打开连接 → 展开到表                    Navicat: __ 步
 2. 双击表 → 看数据                        Navicat: __ 步
 3. 点列头排序                             Navicat: __ 步
 4. 筛选（内存）                           Navicat: __ 步
 5. 筛选（数据库 WHERE）                   Navicat: __ 步
 6. 打开单元格详情                         Navicat: __ 步
 7. 改一个单元格 → review SQL → 保存       Navicat: __ 步
 8. 写个查询 → 执行 → 看结果               Navicat: __ 步
 9. Ctrl+Click 表名跳转                   Navicat: __ 步
10. 触发一个错误 → 定位                   Navicat: __ 步
11. 改表结构（可视化）                     Navicat: __ 步
12. 看表结构 + 注释                       Navicat: __ 步

判定：
  ☐ 12 步全部能完成
  ☐ 总点击数 ≤ Navicat
  ☐ ≥ 3 项比 Navicat 少点击
  ☐ ≥ 2 项比 Navicat 多做（我们的差异化）
```

## 8.8 我们的差异化（对照 Navicat 必胜的项）

```text
 1. 四级筛选的执行位置标注          → Navicat 没有
 2. 级联筛选候选                     → Navicat 有，不能差
 3. 筛选入口在列头                   → Navicat 有
 4. 单元格搜索范围明示               → Navicat 没说
 5. 不可编辑的原因说明               → Navicat 没说
 6. 批量编辑不自动保存               → Navicat 18 刚加
 7. SQL 错误 + 相似表名建议          → Navicat 只跳转
 8. In-editor results                → Navicat 有
 9. 表结构注释内联                   → Navicat 有
10. 连接颜色 + 虚拟组                → Navicat 有
11. 能力矩阵面板                     → ⭐ Navicat 没有
12. 声明驱动的 UI 提示               → ⭐ Navicat 没有
13. 诊断面板                         → ⭐ Navicat 没有
14. 结构编辑器 + 风险标注            → ⭐ Navicat 没有
15. 影响评估                         → ⭐ Navicat 没有
16. 变更审批流                       → ⭐ Navicat 没有
17. 脱敏向导 + FK 一致               → Navicat 只有伪造
18. 知识库                           → ⭐ Navicat 没有
19. AI 侧挂 + 来源标注               → Navicat 内嵌
20. AI 提案式变更                    → ⭐ Navicat 没有
21. 审计                             → ⭐ Navicat 本地没有
22. 跳转列 / 字段复制粘贴            → Navicat 少
```

---

*UI 规格书 v4.0 完*
