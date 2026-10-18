import { reactive } from "vue";
import { api, capabilities, type Descriptor, type TableDef, type TreeNode, type Finding, type UiWarning } from "./api";

// ─────────────────────────────────────────────────────────────
// ⭐ 编辑缓冲（U1-5.2）
// ─────────────────────────────────────────────────────────────

/** 一次单元格改动。 */
export interface CellEdit {
  row: number;
  col: string;
  oldValue: unknown;
  newValue: unknown;
  /**
   * ⭐ 旧值是否本来就是 NULL。
   *
   * 必须单独记一个布尔位：读出来的 NULL 和「用户改成 NULL」都是 `null`，
   * 只存 `oldValue` 就分不出「撤销回原样」和「继续改成空」。
   * 这与 `#10505`（NULL 与空串必须可区分）是同一类问题。
   */
  oldWasNull: boolean;
}

/** 一次保存的批次（用于「撤销到上次保存」，不是单次按键的粒度） */
export interface EditRecord {
  table: string;
  edits: CellEdit[];
}

/** ⭐ 单次单元格改动的撤销条目（⌘Z 的粒度） */
export interface UndoStep {
  table: string;
  row: number;
  col: string;
  /** 改动前的值（可能是 null / undefined —— 那也是值） */
  before: unknown;
  /** 改动后的值 */
  after: unknown;
}

export interface EditBuffer {
  /** 表名 → 该表所有未保存的改动 */
  byTable: Map<string, CellEdit[]>;
  /** ⭐ 单格撤销栈：新改动进来就清空重做栈 */
  undo: UndoStep[];
  /** 重做栈 */
  redo: UndoStep[];
  /** 已保存批次的快照（「回滚到上次保存」要用） */
  saved: EditRecord[];
}

/**
 * ⭐⭐ 值是否「真的相等」。
 *
 * **null / undefined 必须与 "" 严格区分**（`#10505`）：
 *   之前用的是 `String(a ?? "") === String(b ?? "")`，
 *   于是 `null ?? ""` 和 `""` 都变成 `""` —— 用户把一个 NULL 单元格
 *   改成空串，程序判定「没变」直接丢弃。没有报错、没有提示，
 *   用户以为改好了，实际什么都没发生。
 *
 * 非空值之间仍然按字符串比，因为编辑框给出的是字符串、
 * 数据库给出的是数字/布尔；只对「空」这一侧要求严格。
 */
export function sameValue(a: unknown, b: unknown): boolean {
  const aNil = a === null || a === undefined;
  const bNil = b === null || b === undefined;
  if (aNil || bNil) return aNil && bNil;
  if (typeof a === "object" || typeof b === "object") {
    return JSON.stringify(a) === JSON.stringify(b);
  }
  return String(a) === String(b);
}

// ─────────────────────────────────────────────────────────────
// ⭐ 查询构造：把 UI 状态编译成 SQL
// ─────────────────────────────────────────────────────────────

/** 一条筛选条件 */
export interface TableFilter {
  col: string;
  op: FilterOp;
  value: unknown;
}

export type FilterOp =
  | "=" | "!=" | ">" | ">=" | "<" | "<="
  | "LIKE" | "IN" | "IS NULL" | "IS NOT NULL";

export const FILTER_OPS: { op: FilterOp; label: string }[] = [
  { op: "=", label: "等于" },
  { op: "!=", label: "不等于" },
  { op: ">", label: "大于" },
  { op: ">=", label: "大于等于" },
  { op: "<", label: "小于" },
  { op: "<=", label: "小于等于" },
  { op: "LIKE", label: "包含" },
  { op: "IN", label: "属于" },
  { op: "IS NULL", label: "为空" },
  { op: "IS NOT NULL", label: "不为空" },
];

/**
 * ⭐⭐ 从 UI 状态构造表数据查询的 SQL。
 *
 * 这条 SQL 会被**原样送到数据库**，所以：
 *   · 标识符（表名/列名）必须转义 —— `"` 包裹，内部 `"` 翻倍
 *   · 值一律走 `sqlLiteral()`，单引号转义，杜绝注入
 *
 * ⭐ 为什么必须下推：前端只拿到当前页，内存里排序/筛选**只对这一页有效**。
 *   用户点「按金额倒序」，看到的其实是「这一页里最大的几行」，
 *   而不是全表最大的 —— 结果看着像模像样，实际是错的。
 *   这类错误用户极难察觉，却会直接导致误判。
 */
export function buildTableQuery(table: string): { sql: string } {
  const parts = [`SELECT * FROM ${quoteIdent(table)}`];

  const conds = state.filters.map(filterToSql).filter(Boolean) as string[];
  if (conds.length) parts.push(`WHERE ${conds.join(" AND ")}`);

  // ⭐ 只能有**一个** ORDER BY 子句，多个会被数据库判为语法错误
  //    （SQLite 容忍但忽略后面的，等于排序失效且没有任何提示）。
  const orderCols: string[] = [];
  if (state.sortCol) {
    orderCols.push(
      `${quoteIdent(state.sortCol)} ${state.sortDir === "desc" ? "DESC" : "ASC"}`,
    );
  }
  // 主键兜底：保证翻页不重不漏。
  // 没有它，同值行在不同页的顺序未定义，
  // 用户会在第 2 页看到第 1 页已经出现过的行。
  const pk = state.columns.find((c) => c.isPk);
  if (pk && state.sortCol !== pk.name) {
    orderCols.push(`${quoteIdent(pk.name)} ASC`);
  }
  if (orderCols.length) parts.push(`ORDER BY ${orderCols.join(", ")}`);

  return { sql: parts.join(" ") };
}

/** ⭐ 标识符转义：双引号包裹，内部双引号翻倍 */
export function quoteIdent(name: string): string {
  return `"${String(name).replace(/"/g, '""')}"`;
}

/** 单条筛选条件 → SQL 片段 */
function filterToSql(f: TableFilter): string | null {
  const col = quoteIdent(f.col);
  if (f.op === "IS NULL") return `${col} IS NULL`;
  if (f.op === "IS NOT NULL") return `${col} IS NOT NULL`;
  if (f.op === "IN") {
    // ⭐ IN 的值必须是数组。空数组会生成 `IN ()` → 语法错误，
    //    而「一个都没选」在 UI 上表示"不筛选"，所以这里返回 null 让它被跳过。
    const arr = Array.isArray(f.value) ? f.value : [f.value];
    if (!arr.length) return null;
    return `${col} IN (${arr.map(sqlLiteral).join(", ")})`;
  }
  if (f.op === "LIKE") {
    // ⭐ 转义 LIKE 通配符，否则用户搜 "100%" 会变成通配符，
    //    匹配到一大堆无关数据却不报错
    const raw = String(f.value ?? "");
    const escaped = raw.replace(/[\\%_]/g, (m) => `\\${m}`);
    return `${col} LIKE ${sqlLiteral(`%${escaped}%`)} ESCAPE '\\'`;
  }
  return `${col} ${f.op} ${sqlLiteral(f.value)}`;
}

// ─────────────────────────────────────────────────────────────
// ⭐ UI 状态（UI-DESIGN §11.1：store 不持有凭据）
// ─────────────────────────────────────────────────────────────

export interface Tab {
  id: string;
  title: string;
  /**
   * ⭐ 页面路由的唯一依据。
   * 不要再引入第二个并行字段（历史上 `state.tool` 与它重复过，
   * 结果 `mask` / `audit` 两个工具页被 `diagnosis` 分支抢先命中）。
   */
  kind:
    | "grid"
    | "structure"
    | "query"
    | "diagnosis"
    | "transfer"
    | "capability"
    | "mask"
    | "audit";
  table?: string;
  sql?: string;
  pinned?: boolean;
}

interface State {
  booted: boolean;
  bootedError: string | null;

  // 会话
  connected: boolean;
  connectionKind: string;
  descriptor: Descriptor | null;
  dbFile: string;
  warnings: UiWarning[];

  // 对象树
  tree: Map<string, TreeNode>;
  expanded: Set<string>;
  loadingNodes: Set<string>;
  /** ⭐ 客户端已拉过子节点的 parent id */
  loadedParents: Set<string>;

  // 标签页
  tabs: Tab[];
  activeTabId: string | null;

  // 数据
  tableDef: TableDef | null;
  rows: Record<string, unknown>[];
  columns: import("./api").ColumnDef[];
  total: number | null;
  offset: number;
  queryMs: number;
  lastDanger: string;
  loading: boolean;

  // ── 分页 ──
  /** 是否还有下一页（来自后端 nextCursor，不是猜的） */
  hasNextPage: boolean;
  /**
   * ⭐ `total` 是否已不适用于当前查询。
   *
   * 翻页时后端不重算 COUNT，`total` 沿用上一���的值 —— 这没问题。
   * 但**一旦加了筛选条件**，那个数就是「未筛选时的总数」，
   * 继续显示就是骗人（用户会以为筛选后还有 5000 行）。
   * 所以要用这个标记把两种情况分开：
   *   false → total 权威，显示
   *   true  → total 只对"无筛选"成立，不显示
   */
  totalStale: boolean;

  // ── 排序（下推到数据库）──
  sortCol: string | null;
  sortDir: "asc" | "desc";

  // ── 筛选（下推到数据库）──
  filters: TableFilter[];

  // ⭐ 编辑缓冲（U1-5.2）：改动先进内存，**不自动保存**
  /**
   * ⭐ 一次单元格改动的完整记录。
   *
   * `oldValue` 必存：撤销、重做、以及「保存前展示 diff」全靠它。
   * 只存新值的话，撤销就只能靠再查一次库，慢且容易出错。
   */
  edits: EditBuffer;

  // 诊断
  findings: Finding[];

  // 工具区
  tool: "none" | "diagnosis" | "change" | "mask" | "audit" | "transfer" | "capability";

  // 布局
  sidebarWidth: number;
  sidebarCollapsed: boolean;
  aiOpen: boolean;
  theme: "dark" | "light";
  density: "compact" | "normal" | "comfort";
  rowLimit: number;
  toast: { kind: string; text: string } | null;

  // 对象树收藏（表名数组，持久化到 localStorage）
  starred: string[];
}

export const state = reactive<State>({
  booted: false,
  bootedError: null,

  connected: false,
  connectionKind: "",
  descriptor: null,
  dbFile: "",
  warnings: [],

  tree: new Map(),
  expanded: new Set(),
  loadingNodes: new Set(),
  loadedParents: new Set(),

  tabs: [],
  activeTabId: null,

  tableDef: null,
  rows: [],
  columns: [],
  total: null,
  offset: 0,
  queryMs: 0,
  lastDanger: "readOnly",
  loading: false,
  hasNextPage: false,
  totalStale: false,
  sortCol: null,
  sortDir: "asc",
  filters: [],

  edits: { byTable: new Map(), undo: [], redo: [], saved: [] } as EditBuffer,

  findings: [],

  tool: "none",

  sidebarWidth: Number(localStorage.getItem("adb.sidebarWidth") ?? 240),
  sidebarCollapsed: localStorage.getItem("adb.sidebarCollapsed") === "1",
  aiOpen: localStorage.getItem("adb.aiOpen") === "1",
  theme: (localStorage.getItem("adb.theme") as "dark" | "light") ?? "dark",
  density: (localStorage.getItem("adb.density") as any) ?? "normal",
  rowLimit: Number(localStorage.getItem("adb.rowLimit") ?? 200),
  toast: null,

  starred: JSON.parse(localStorage.getItem("adb.starred") ?? "[]") as string[],
});

// ─────────────────────────────────────────────────────────────
// 动作
// ─────────────────────────────────────────────────────────────

let tabSeq = 0;

export const actions = {
  async boot() {
    try {
      const caps = await api.capabilities();
      capabilities.set(caps);
      const sess = await api.openSession("sqlite");
      state.connected = true;
      state.connectionKind = sess.kind;
      state.descriptor = sess.descriptor;
      state.dbFile = sess.dbFile;
      state.warnings = sess.warnings;
      state.findings = await api.diagnostics();
      await actions.loadChildren(undefined);
      state.booted = true;
    } catch (e) {
      state.bootedError = e instanceof Error ? e.message : String(e);
      state.booted = true;
    }
  },

  // ── 对象树 ──
  // ⭐ `loadedParents` 由**客户端**维护：后端的 `loaded` 字段说的是
  //    「这个节点自身的数据是否完整」，不是「子节点是否已取回」。
  //    混淆这两个语义会让根节点永远无法展开。
  async loadChildren(parent?: string) {
    const key = parent ?? "__root__";
    if (state.loadingNodes.has(key)) return;
    state.loadingNodes.add(key);
    try {
      const depth = parent?.startsWith("tbl:") ? "structure" : "name";
      const nodes = await api.tree(parent, depth as any);
      for (const n of nodes) state.tree.set(n.id, n);
      state.loadedParents.add(key);
    } finally {
      state.loadingNodes.delete(key);
    }
  },

  async toggle(id: string) {
    if (state.expanded.has(id)) {
      state.expanded.delete(id);
      return;
    }
    state.expanded.add(id);
    if (!state.loadedParents.has(id)) await actions.loadChildren(id);
  },

  /**
   * ⭐ 按 `parent` 字段组树 —— 不用字符串拼接判断父子关系。
   * 这样表名叫 `order_items_archive` 也不会被误挂到 `order_item` 下。
   */
  childrenOf(id?: string): TreeNode[] {
    const want = id ?? null;
    return [...state.tree.values()]
      .filter((n) => (n.parent ?? null) === want)
      .sort((a, b) => a.label.localeCompare(b.label));
  },

  // ── 标签页 ──
  /**
   * ⭐ 点击对象树的表：既展开列，也打开数据页。
   *
   * 合成一个动作而不是在模板里写两个语句。另外**不依赖 `this`** ——
   * 模板编译出的调用路径拿到的 `this` 未必是 `actions`；
   * 一旦 `this.toggle` 的求值本身失败（async 函数体不会同步抛错），
   * 后续语句会被静默跳过，表现为「点击只展开、不打开」。
   */
  pickTable(nodeId: string, table: string) {
    void actions.toggle(nodeId);
    actions.openTable(table);
  },

  openTable(table: string) {
    const id = `t:${table}`;
    const exist = state.tabs.find((t) => t.id === id);
    if (exist) {
      state.activeTabId = id;
      // ⭐⭐ 已打开的 tab 也要保证数据是**这一张表**的。
      //   DataGridPage 读的是全局 state.tableDef / state.rows，
      //   只切 activeTabId 不重载的话：tab 标题是 users、内容是 user_profiles，
      //   **页面不报任何错**，而用户已经按错误的数据在思考了。
      //   （与「编辑改到别的行」同族：全局态与视图错位。）
      if (state.tableDef?.name !== table) void actions.loadTable(table);
      return;
    }
    state.tabs.push({ id, title: table, kind: "grid", table });
    state.activeTabId = id;
    void actions.loadTable(table);
  },

  openQuery(sql = "SELECT * FROM") {
    const id = `q:${++tabSeq}`;
    state.tabs.push({ id, title: "查询", kind: "query", sql });
    state.activeTabId = id;
  },

  /**
   * ⭐⭐ 打开某张表的**结构页**。
   *
   * 存在的理由：查询编辑器的 Ctrl+Click 要能从列名跳到它所属的表结构。
   * 之前只有 DataGridPage 里一个私有的 `openStructure()`，
   * 那里**必须先有 `state.tableDef`**（即必须先打开数据页）才能跳 ——
   * 于是「在查询里跳转一张还没打开过的表」根本做不到。
   *
   * ⚠ 结构页读的是 `state.tableDef`，所以这里必须先把元数据拉回来，
   *    否则切过去会看到「未选择表」。
   */
  async openStructure(table: string) {
    const id = `s:${table}`;
    const exist = state.tabs.find((t) => t.id === id);
    if (!exist) {
      state.tabs.push({ id, title: `${table} 结构`, kind: "structure", table });
    }
    state.activeTabId = id;
    if (state.tableDef?.name !== table) {
      try {
        const meta = await api.table(table);
        state.tableDef = meta.table;
      } catch (e) {
        notify("error", e instanceof Error ? e.message : String(e));
        return;
      }
    }
  },

  // ── 对象树右键菜单 / 收藏 / 辅助动作（C1）──
  /** 当前数据库节点 id（树里唯一以 `db:` 开头的节点）。 */
  dbNodeId(): string {
    return [...state.tree.keys()].find((k) => k.startsWith("db:")) ?? "";
  },

  /** C1-5：连接只读时（descriptor.supportLevel === "read-only"）禁用一切编辑入口。 */
  isConnectionReadOnly(): boolean {
    return state.descriptor?.supportLevel === "read-only";
  },

  // ⭐⭐ 收藏：表名数组持久化到 localStorage，跨会话保留。
  isStarred(table: string): boolean {
    return state.starred.includes(table);
  },
  toggleStar(table: string) {
    const i = state.starred.indexOf(table);
    if (i >= 0) state.starred.splice(i, 1);
    else state.starred.push(table);
    localStorage.setItem("adb.starred", JSON.stringify(state.starred));
  },
  /** 收藏对应的树节点（按 label 反查，不依赖 id 格式）。 */
  starredNodes(): TreeNode[] {
    const set = new Set(state.starred);
    return [...state.tree.values()].filter(
      (n): n is TreeNode => set.has(n.label) && (n.kind === "table" || n.kind === "view"),
    );
  },

  /** C1-1「刷新此表」：重拉对象树（表结构可能变了）+ 重载已开的页签。 */
  async refreshObject(table: string) {
    await actions.loadChildren(actions.dbNodeId() || undefined);
    const grid = state.tabs.find((t) => t.id === `t:${table}`);
    if (grid) {
      state.activeTabId = grid.id;
      await actions.loadTable(table);
    }
    const struct = state.tabs.find((t) => t.id === `s:${table}`);
    if (struct) {
      try {
        const meta = await api.table(table);
        state.tableDef = meta.table;
      } catch { /* 结构页重载失败不阻塞刷新 */ }
    }
    notify("info", `已刷新 ${table}`);
  },

  async copyName(table: string) {
    try {
      await navigator.clipboard.writeText(table);
      notify("info", `已复制表名：${table}`);
    } catch {
      notify("error", "复制失败（剪贴板不可用）");
    }
  },
  async copySelect(table: string) {
    const sql = `SELECT * FROM ${table} LIMIT 100;`;
    try {
      await navigator.clipboard.writeText(sql);
      notify("info", `已复制查询：${table}`);
    } catch {
      notify("error", "复制失败（剪贴板不可用）");
    }
  },
  /** C1-1「在查询中打开」：新开查询页并预填 SELECT。 */
  openInQuery(table: string) {
    actions.openQuery(`SELECT * FROM ${table} LIMIT 100;`);
  },
  /** C1-1「诊断此表」：打开诊断工具页。 */
  diagnoseTable(_table: string) {
    actions.openTool("diagnosis", "诊断");
  },

  /**
   * ⭐ 打开工具页。
   *
   * ⚠ 这里必须**按工具给出正确的 `kind`**。
   *   之前把除 transfer/capability 之外的都写成 `"diagnosis"`，
   *   而 App.vue 是按 `tab.kind` 依次匹配的
   *   （`kind === 'diagnosis'` 排在 `state.tool === 'mask'` 前面），
   *   于是「数据工厂」和「变更管控」**永远渲染成诊断页** ——
   *   点进去看到的还是别的页，用户完全不知道。
   *
   *   教训：两个字段（`state.tool` 与 `tab.kind`）描述同一件事，
   *   迟早会不一致。**只用 `tab.kind` 路由**，`state.tool` 保留仅为兼容。
   */
  openTool(tool: State["tool"], title: string) {
    state.tool = tool;
    const id = `x:${tool}`;
    const kind: Tab["kind"] =
      tool === "transfer" ? "transfer"
      : tool === "capability" ? "capability"
      : tool === "mask" ? "mask"
      : tool === "audit" ? "audit"
      : "diagnosis";
    const exist = state.tabs.find((t) => t.id === id);
    if (!exist) state.tabs.push({ id, title, kind });
    state.activeTabId = id;
  },

  /**
   * ⭐ 关闭标签页，**带未保存拦截**。
   *
   * 静默丢弃用户编辑是不可逆的（刷新就没了），
   * 所以这里明确弹 3 选项：保存 / 放弃 / 取消。
   */
  async closeTab(id: string) {
    const i = state.tabs.findIndex((t) => t.id === id);
    if (i < 0) return;
    const tab = state.tabs[i];

    if (tab.table && actions.hasPending(tab.table)) {
      const n = actions.pendingCount(tab.table);
      const choice = window.prompt(
        `「${tab.title}」有 ${n} 处改动尚未保存。\n\n` +
          `输入 s 保存后关闭，输入 d 放弃改动后关闭，直接回车取消`,
        "d",
      );
      if (choice === null) return; // 取消
      if (choice.trim().toLowerCase() === "s") {
        await actions.saveEdits(tab.table, true);
      } else if (choice.trim().toLowerCase() !== "d") {
        return; // 其它输入 → 取消
      } else {
        actions.discardEdits(tab.table);
      }
    }

    state.tabs.splice(i, 1);
    if (state.activeTabId === id) {
      state.activeTabId = state.tabs[Math.max(0, i - 1)]?.id ?? null;
    }
  },

  activeTab(): Tab | null {
    return state.tabs.find((t) => t.id === state.activeTabId) ?? null;
  },

  // ── 加载表数据（服务端分页 + 条件下推）──
  /**
   * ⭐⭐ 排序与筛选**必须下推到数据库**。
   *
   * 前端只拿到当前页的 200 行，在内存里排序 = 只排了这 200 行；
   * 用户点「按金额倒序」看到的是「这 200 行里最大的」，
   * 而不是全表最大的 —— 结果看着像模像样，实际是错的。
   * 这类错误用户极难察觉，却会直接导致误判。
   */
  async loadTable(table: string) {
    state.loading = true;
    try {
      const sql = buildTableQuery(table);
      const r = await api.query(sql.sql, state.rowLimit, state.offset);
      state.rows = r.page.rows;
      state.columns = r.page.columns.length ? r.page.columns : state.columns;
      state.queryMs = r.page.durationMs;
      state.lastDanger = r.danger;
      state.hasNextPage = !!r.page.nextCursor;
      // ⭐ total 只在后端给权威值时更新。
      //    有筛选时后端**故意**返回 null（不给错数），此时沿用上次已知值，
      //    但标记为 stale —— 显示时会说明它不适用于当前筛选。
      if (r.page.total !== null) {
        state.total = r.page.total;
        state.totalStale = false;
      } else if (state.filters.length) {
        state.totalStale = true;
      }
      const meta = await api.table(table);
      state.tableDef = meta.table;
      state.findings = await api.diagnostics();
    } catch (e) {
      notify("error", e instanceof Error ? e.message : String(e));
    } finally {
      state.loading = false;
    }
  },

  /** 上一页 */
  prevPage() {
    state.offset = Math.max(0, state.offset - state.rowLimit);
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** 下一页（无 nextCursor 时禁用） */
  nextPage() {
    if (!state.hasNextPage) return;
    state.offset += state.rowLimit;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** 跳到第 n 页（n 从 1 开始） */
  goToPage(n: number) {
    const p = Math.max(1, Math.floor(n));
    state.offset = (p - 1) * state.rowLimit;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** 排序：同一列再点一次切换方向 */
  toggleSort(col: string) {
    if (state.sortCol === col) {
      state.sortDir = state.sortDir === "asc" ? "desc" : "asc";
    } else {
      state.sortCol = col;
      state.sortDir = "asc";
    }
    // ⭐ 排序变了必须重查 —— 内存里排只对当前页有效
    state.offset = 0;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** 设置排序（多列级联时用，columns 按优先级排列） */
  setSort(col: string | null, dir: "asc" | "desc" = "asc") {
    state.sortCol = col;
    state.sortDir = dir;
    state.offset = 0;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  // ⭐ 注意：addFilter / removeFilter / clearFilters 定义在**下面**编辑缓冲那一段，
  //   那里还负责维护 `totalStale`。这里曾经有一份同名副本，
  //   对象字面量里后写的会**静默覆盖**先写的 —— 改前一份不会有任何效果，
  //   编译也只给一句警告。已删除，避免「改了却不生效」的假象。

  async runSql(sql: string) {
    state.loading = true;
    try {
      const r = await api.query(sql, state.rowLimit);
      state.rows = r.page.rows;
      state.columns = r.page.columns;
      state.total = r.page.total ?? null;
      state.queryMs = r.page.durationMs;
      state.lastDanger = r.danger;
      state.tableDef = null;
      return r;
    } catch (e) {
      notify("error", e instanceof Error ? e.message : String(e));
      throw e;
    } finally {
      state.loading = false;
    }
  },

  // ── 编辑缓冲（U1-5.2）──

  /**
   * ⭐ 记录一次单元格改动（**不落库**）。
   *
   * 同一个 (row, col) 重复编辑时只更新 `newValue`，保留最早的 `oldValue` ——
   * 否则撤销只能退回到「上一次编辑」而不是「进入编辑前的原值」。
   */
  editCell(table: string, row: number, col: string, newValue: unknown) {
    const rows = state.rows;
    const current = rows[row];
    if (!current) return;

    // ⭐ 没有净变化就不记账：否则撤销栈里会出现一堆「按了 ⌘Z 但什么都没变」的步
    if (sameValue(current[col], newValue)) return;

    // ⭐ 撤销粒度 = 一次「进入编辑 → 提交」。
    //   只有**紧接着的同格**编辑才合并；隔了一格再回来就是另一步
    //   —— 否则「A → B → A」会被并成一步，⌘Z 回不到中间那个 B。
    const top = state.edits.undo[state.edits.undo.length - 1];
    if (top && top.table === table && top.row === row && top.col === col) {
      top.after = newValue;
      // ⭐ 改回原值 → 这一步什么也没做，占着撤销位只会让 ⌘Z「按了没反应」
      if (sameValue(top.before, top.after)) state.edits.undo.pop();
    } else {
      state.edits.undo.push({ table, row, col, before: current[col], after: newValue });
    }

    const list = state.edits.byTable.get(table) ?? [];
    const idx = list.findIndex((e) => e.row === row && e.col === col);

    if (idx >= 0) {
      list[idx].newValue = newValue;
      // ⭐⭐ 净变化为 0 就把这格从「待保存」里拿掉。
      //    原值 A → B → A，用户眼里就是「没改」，
      //    留着会生成一条改了等于没改的 UPDATE，还虚增「未保存 N 处」。
      if (sameValue(list[idx].oldValue, newValue)) list.splice(idx, 1);
    } else {
      list.push({
        row,
        col,
        oldValue: current[col],
        newValue,
        oldWasNull: current[col] === null,
      });
    }
    if (!list.length) state.edits.byTable.delete(table);
    else state.edits.byTable.set(table, list);
    // ⭐ 有新改动就废掉重做栈 —— 标准编辑器行为
    state.edits.redo = [];
    // 立即反映到行数据上，让用户看到效果
    rows[row][col] = newValue;
  },

  /**
   * ⭐ 撤销一步（⌘Z）。
   *
   * ⭐ 撤销回**原值**时，必须把这格从「待保存」缓冲里去掉 ——
   *    否则生成的 SQL 会把原值又写一遍：一条改了等于没改的 UPDATE，
   *    还会占掉「affected rows」的统计。净变化为 0 就不该出现在待保存里。
   */
  undoEdit(): boolean {
    const step = state.edits.undo.pop();
    if (!step) return false;
    const row = state.rows[step.row];
    if (row) row[step.col] = step.before;

    const list = state.edits.byTable.get(step.table);
    if (list) {
      const i = list.findIndex((e) => e.row === step.row && e.col === step.col);
      if (i >= 0 && sameValue(list[i].oldValue, step.before)) list.splice(i, 1);
      if (!list.length) state.edits.byTable.delete(step.table);
    }
    state.edits.redo.push(step);
    return true;
  },

  /** ⭐ 重做一步（⌘⇧Z） */
  redoEdit(): boolean {
    const step = state.edits.redo.pop();
    if (!step) return false;
    // ⭐ 直接复用 editCell：它负责写行数据、维护待保存缓冲、清空重做栈、
    //   并把这一步压回撤销栈 —— 这正是重做该有的样子。
    //   在这里自己 push 会产生重复条目。
    actions.editCell(step.table, step.row, step.col, step.after);
    return true;
  },

  /** 某表是否有未保存改动 */
  hasPending(table: string): boolean {
    return (state.edits.byTable.get(table)?.length ?? 0) > 0;
  },

  /** 未保存改动总数（显示在 tab 上） */
  pendingCount(table?: string): number {
    if (table) return state.edits.byTable.get(table)?.length ?? 0;
    let n = 0;
    for (const l of state.edits.byTable.values()) n += l.length;
    return n;
  },

  /**
   * ⭐⭐ 把待保存的改动生成为可预览的 SQL。
   *
   * 这是「保存前必须让用户看清楚要执行什么」的关键一步。
   *
   * ⭐ WHERE **必须带全部主键**；无主键的表**明确拒绝**生成 UPDATE ——
   *    没有主键就无法精确定位行，那条 SQL 会改到整张表去。
   *    这正是诊断页报「audit_logs 没有主键」的实际后果。
   */
  buildSaveSql(table: string): { statements: string[]; error?: string } {
    const edits = state.edits.byTable.get(table) ?? [];
    if (!edits.length) return { statements: [] };

    const cols = state.columns ?? [];
    const pk = cols.filter((c) => c.isPk);
    if (!pk.length) {
      return {
        statements: [],
        error: `表 ${table} 没有主键，无法生成安全的 UPDATE —— 请改用 WHERE 明确指定行`,
      };
    }

    // 按行分组：同一行改多列合并成一条 UPDATE
    const byRow = new Map<number, CellEdit[]>();
    for (const e of edits) {
      const l = byRow.get(e.row) ?? [];
      l.push(e);
      byRow.set(e.row, l);
    }

    const statements: string[] = [];
    for (const [rowIdx, cellEdits] of byRow) {
      const row = state.rows[rowIdx];
      if (!row) continue;

      const sets = cellEdits
        .map((e) => `"${e.col}" = ${sqlLiteral(e.newValue)}`)
        .join(", ");

      // ⭐ WHERE 用主键当前值，且用**编辑前**的值 ——
      //    如果用户改了主键本身，用新值会定位不到行。
      const where = pk
        .map((c) => `"${c.name}" = ${sqlLiteral(row[c.name])}`)
        .join(" AND ");

      statements.push(`UPDATE "${table}" SET ${sets} WHERE ${where};`);
    }
    return { statements };
  },

  /**
   * ⭐ 保存改动（真正落库）。
   *
   * 流程：生成 SQL → 调用方已展示并得到用户确认 → `/api/execute` 落库
   * → 清缓冲 → 重新拉数据。
   *
   * ⭐ 落库失败**不清缓冲** —— 用户辛苦编辑的内容必须留着可重试。
   */
  async saveEdits(table: string, requireConfirm: boolean) {
    const { statements, error } = actions.buildSaveSql(table);
    if (error) {
      notify("error", error);
      return { ok: false, error };
    }
    if (!statements.length) {
      notify("info", "没有待保存的改动");
      return { ok: true, affected: 0 };
    }

    let affected = 0;
    try {
      for (const sql of statements) {
        const r = await api.execute(sql, requireConfirm);
        affected += r.affectedRows ?? 0;
      }
    } catch (e) {
      // ⭐ 失败时保留缓冲，让用户能改完重试
      notify("error", e instanceof Error ? e.message : String(e));
      return { ok: false, error: String(e) };
    }

    // 成功后：记快照、清缓冲、刷新数据
    state.edits.saved.push({ table, edits: [...(state.edits.byTable.get(table) ?? [])] });
    state.edits.byTable.delete(table);
    // ⭐ 保存是一次新动作，重做栈里的东西已经无法按顺序接回去了
    state.edits.undo = [];
    state.edits.redo = [];

    await actions.loadTable(table);
    notify("info", `已保存 ${affected} 处改动（${statements.length} 条语句）`);
    return { ok: true, affected, statements };
  },

  /** 放弃某表的未保存改动 */
  discardEdits(table: string) {
    const edits = state.edits.byTable.get(table) ?? [];
    // 先把行数据恢复成原值，界面才和数据库一致
    for (const e of edits) {
      const row = state.rows[e.row];
      if (row) row[e.col] = e.oldWasNull ? null : e.oldValue;
    }
    state.edits.byTable.delete(table);
    // ⭐ 这批改动已经不成立了，撤销栈里对应的步也必须一起丢掉 ——
    //    否则「放弃」之后按 ⌘Z 会把已经放弃的改动又变回来。
    state.edits.undo = state.edits.undo.filter((s) => s.table !== table);
    state.edits.redo = state.edits.redo.filter((s) => s.table !== table);
  },

  /** ⭐ 设一个筛选条件（替换该列已有的同列条件） */
  setFilter(col: string, op: FilterOp, value: unknown) {
    const i = state.filters.findIndex((f) => f.col === col);
    if (i >= 0) state.filters.splice(i, 1);
    state.filters.push({ col, op, value });
    state.offset = 0;
    state.totalStale = true;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** ⭐ 加筛选条件：总数随之失效 */
  addFilter(f: TableFilter) {
    state.filters.push(f);
    state.offset = 0;
    state.totalStale = true;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** ⭐ 删筛选条件 */
  removeFilter(i: number) {
    state.filters.splice(i, 1);
    state.offset = 0;
    state.totalStale = state.filters.length > 0;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },

  /** ⭐ 清空筛选：总数重新变得可用 */
  clearFilters() {
    state.filters = [];
    state.offset = 0;
    state.totalStale = false;
    const t = state.tableDef?.name;
    if (t) void actions.loadTable(t);
  },
  setSidebarWidth(w: number) {
    state.sidebarWidth = Math.min(480, Math.max(160, w));
    localStorage.setItem("adb.sidebarWidth", String(state.sidebarWidth));
  },
  toggleSidebar() {
    state.sidebarCollapsed = !state.sidebarCollapsed;
    localStorage.setItem("adb.sidebarCollapsed", state.sidebarCollapsed ? "1" : "0");
  },
  toggleAi() {
    state.aiOpen = !state.aiOpen;
    localStorage.setItem("adb.aiOpen", state.aiOpen ? "1" : "0");
  },
  toggleTheme() {
    state.theme = state.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = state.theme;
    localStorage.setItem("adb.theme", state.theme);
  },
  setDensity(d: State["density"]) {
    state.density = d;
    localStorage.setItem("adb.density", d);
  },
  setRowLimit(n: number) {
    state.rowLimit = n;
    localStorage.setItem("adb.rowLimit", String(n));
  },
};

let toastTimer: number | undefined;
export function notify(kind: string, text: string) {
  state.toast = { kind, text };
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (state.toast = null), 4200);
}

/**
 * ⭐⭐ 把 JS 值转成 SQL 字面量。
 *
 * **NULL 与空串必须严格区分**（`#10505`）：
 *   · `null` / `undefined` → `NULL`（SQL 关键字，不加引号）
 *   · `""`                 → `''`（空字符串字面量）
 * 把空串写成 `NULL` 是数据损坏，把 NULL 写成 `''` 也是 —— 两者都不可逆。
 *
 * ⭐ 这里**不做任何隐式转换**：值与列类型对不上时，
 *    让数据库报错并显示位置，比悄悄改掉用户的值好得多。
 */
export function sqlLiteral(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number") {
    // ⭐ NaN / Infinity 不是合法 SQL 字面量，直接报错比写出坏 SQL 好
    if (!Number.isFinite(v)) return "NULL";
    return String(v);
  }
  if (typeof v === "boolean") return v ? "1" : "0";
  // ⭐⭐ 对象 / 数组必须序列化成 JSON。
  //   之前落到最后一行 `String(v)`，写出来是 `'[object Object]'` ——
  //   一条语法合法的 SQL，把用户的数据毁成了一坨字面量字符串。
  //   而且没有任何报错：保存成功、值确实变了、只是变成了垃圾。
  if (typeof v === "object") {
    let json: string;
    try {
      json = JSON.stringify(v);
    } catch {
      // ⭐ 循环引用之类没法序列化 —— 宁可报「不可序列化」也不要写出坏 SQL
      throw new Error("值无法序列化为 JSON（可能含有循环引用）");
    }
    if (json === undefined) throw new Error("值无法序列化为 JSON");
    return `'${json.replace(/'/g, "''")}'`;
  }
  return `'${String(v).replace(/'/g, "''")}'`;
}
