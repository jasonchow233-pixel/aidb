import { reactive } from "vue";
import { api, capabilities, type Descriptor, type TableDef, type TreeNode, type Finding, type UiWarning } from "./api";

// ─────────────────────────────────────────────────────────────
// ⭐ UI 状态（UI-DESIGN §11.1：store 不持有凭据）
// ─────────────────────────────────────────────────────────────

export interface Tab {
  id: string;
  title: string;
  kind: "grid" | "structure" | "query" | "diagnosis" | "transfer" | "capability";
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

  findings: [],

  tool: "none",

  sidebarWidth: Number(localStorage.getItem("adb.sidebarWidth") ?? 240),
  sidebarCollapsed: localStorage.getItem("adb.sidebarCollapsed") === "1",
  aiOpen: localStorage.getItem("adb.aiOpen") === "1",
  theme: (localStorage.getItem("adb.theme") as "dark" | "light") ?? "dark",
  density: (localStorage.getItem("adb.density") as any) ?? "normal",
  rowLimit: Number(localStorage.getItem("adb.rowLimit") ?? 200),
  toast: null,
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
    if (exist) { state.activeTabId = id; return; }
    state.tabs.push({ id, title: table, kind: "grid", table });
    state.activeTabId = id;
    void actions.loadTable(table);
  },

  openQuery(sql = "SELECT * FROM") {
    const id = `q:${++tabSeq}`;
    state.tabs.push({ id, title: "查询", kind: "query", sql });
    state.activeTabId = id;
  },

  openTool(tool: State["tool"], title: string) {
    state.tool = tool;
    const id = `x:${tool}`;
    const exist = state.tabs.find((t) => t.id === id);
    if (!exist) state.tabs.push({ id, title, kind: tool === "transfer" ? "transfer" : tool === "capability" ? "capability" : "diagnosis" });
    state.activeTabId = id;
  },

  closeTab(id: string) {
    const i = state.tabs.findIndex((t) => t.id === id);
    if (i < 0) return;
    state.tabs.splice(i, 1);
    if (state.activeTabId === id) {
      state.activeTabId = state.tabs[Math.max(0, i - 1)]?.id ?? null;
    }
  },

  activeTab(): Tab | null {
    return state.tabs.find((t) => t.id === state.activeTabId) ?? null;
  },

  // ── 加载表数据（真分页）──
  async loadTable(table: string) {
    state.loading = true;
    try {
      const r = await api.query(`SELECT * FROM "${table}" LIMIT ${state.rowLimit}`, state.rowLimit);
      state.rows = r.page.rows;
      state.columns = r.page.columns;
      state.total = r.page.total ?? null;
      state.offset = 0;
      state.queryMs = r.page.durationMs;
      state.lastDanger = r.danger;
      const meta = await api.table(table);
      state.tableDef = meta.table;
      state.findings = await api.diagnostics();
    } catch (e) {
      notify("error", e instanceof Error ? e.message : String(e));
    } finally {
      state.loading = false;
    }
  },

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

  // ── 布局 ──
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
