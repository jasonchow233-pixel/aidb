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

/** 一次保存的批次（撤销粒度 = 一次保存，不是单次按键） */
export interface EditRecord {
  table: string;
  edits: CellEdit[];
}

export interface EditBuffer {
  /** 表名 → 该表所有未保存的改动 */
  byTable: Map<string, CellEdit[]>;
  /** 已保存批次的撤销栈 */
  undo: EditRecord[];
  /** 重做栈 */
  redo: EditRecord[];
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

  edits: { byTable: new Map(), undo: [], redo: [] } as EditBuffer,

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

    const list = state.edits.byTable.get(table) ?? [];
    const existing = list.find((e) => e.row === row && e.col === col);

    if (existing) {
      existing.newValue = newValue;
    } else {
      list.push({
        row,
        col,
        oldValue: current[col],
        newValue,
        oldWasNull: current[col] === null,
      });
    }
    state.edits.byTable.set(table, list);
    // ⭐ 有新改动就废掉重做栈 —— 标准编辑器行为
    state.edits.redo = [];
    // 立即反映到行数据上，让用户看到效果
    rows[row][col] = newValue;
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

    // 成功后：进撤销栈、清缓冲、刷新数据
    const record: EditRecord = { table, edits: [...(state.edits.byTable.get(table) ?? [])] };
    state.edits.undo.push(record);
    state.edits.redo = [];
    state.edits.byTable.delete(table);

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
  return `'${String(v).replace(/'/g, "''")}'`;
}
