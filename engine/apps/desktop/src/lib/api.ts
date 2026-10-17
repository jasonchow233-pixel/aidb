/**
 * AgenticDB 前端 API 层
 * ⭐ 所有请求集中在这里 —— 组件不直接 fetch
 */

const BASE = "/api";

export interface ApiEnvelope<T> {
  ok: boolean;
  data?: T;
  error?: ApiErrorPayload;
}

export interface ApiErrorPayload {
  kind: string;
  message: string;
  position?: { line: number; column: number; offset: number; length: number } | null;
  hints?: { kind: string; label: string; detail: string }[];
  suggestions?: string[];
}

export class ApiError extends Error {
  payload: ApiErrorPayload;
  constructor(p: ApiErrorPayload) {
    super(p.message);
    this.payload = p;
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(BASE + path, {
    ...init,
    headers: { "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  const j = (await r.json()) as ApiEnvelope<T>;
  if (!j.ok) throw new ApiError(j.error ?? { kind: "unknown", message: "未知错误" });
  return j.data as T;
}

// ─────────────────────────────────────────────────────────────
// 声明层类型（与 adb-types 的 Descriptor 同构）
// ─────────────────────────────────────────────────────────────

export interface Traits {
  schemaAware: boolean;
  singleDatabase: boolean;
  clearableQuerySchema: boolean;
  identityKind: string;
  sequenceObjects: boolean;
  /** ⭐ false → UI 必须提示「DDL 会自动提交」 */
  transactionalDdl: boolean;
  onlineDdlConcurrent: boolean;
  multipleResultsets: boolean;
  savepoints: boolean;
  explainFormats: string[];
  maxParams: number;
  identifierQuote: string;
  paramStyle: string;
}

export interface Capabilities {
  queryExecution: boolean;
  metadataBrowse: boolean;
  objectBrowser: boolean;
  objectSource: boolean;
  schemaSearch: boolean;
  diagram: boolean;
  tableDataEdit: boolean;
  tableStructureEdit: boolean;
  tableImport: boolean;
  dataTransfer: boolean;
  sqlFileExecution: boolean;
  databaseCreate: boolean;
  fieldLineage: boolean;
  sqlExplain: boolean;
  userAdmin: boolean;
  specializedSurface: boolean;
}

export interface Descriptor {
  id: string;
  order: number;
  label: string;
  labelEn?: string | null;
  dialect: string;
  runtimeMode: "native" | "jdbc" | "http";
  mcpMode: "builtin" | "bridge" | "none";
  defaultPort: number;
  urlTemplate?: string | null;
  driverClass?: string | null;
  traits: Traits;
  capabilities: Capabilities;
  compareModes: string[];
  transferSupportLevel: string;
  transferObjectFamily: string;
  supportLevel: "full" | "operate" | "read-only" | "experimental";
}

export interface FamilyDescriptor {
  id: string;
  label: string;
  members: string[];
  supportsTransactionalDdl: boolean;
  identityKeyword: string;
  quoteIdent: string;
  typeMapping: Record<string, { lossy: boolean; to: Record<string, string> }>;
}

export interface UiWarning {
  level: "info" | "important" | "critical";
  code: string;
  message: string;
  whereToShow: "sidebar" | "structureEditor" | "transfer" | "queryEditor" | "dataGrid";
}

export interface CapabilitiesPayload {
  connections: Descriptor[];
  families: FamilyDescriptor[];
  registered: string[];
  build_report: string;
  /**
   * ⭐ 每个连接的 UI 警告，**全部由后端声明层 traits 推导**。
   * 前端不允许再写 `if (conn === "mysql")` 去猜提示文案 ——
   * 加一种数据库时，提示必须自动跟着声明走，而不是等前端改代码。
   */
  warnings: Record<string, UiWarning[]>;
}

// ─────────────────────────────────────────────────────────────
// 元数据
// ─────────────────────────────────────────────────────────────

export interface ColumnDef {
  id: string;
  name: string;
  ty: string;
  category: string;
  nullable: boolean;
  defaultExpr?: string | null;
  comment?: string | null;
  isPk: boolean;
  isFk: boolean;
  isLarge: boolean;
  enumValues?: Record<string, string> | null;
  ord: number;
}

export interface IndexDef {
  id: string;
  name: string;
  unique: boolean;
  primary: boolean;
  columns: string[];
  def?: string | null;
}

export interface TableDef {
  id: string;
  name: string;
  comment?: string | null;
  kind: string;
  rowEstimate?: number | null;
  sizeBytes?: number | null;
  columns: ColumnDef[];
  indexes: IndexDef[];
  foreignKeys: [string, string, string, string][];
}

export interface TreeNode {
  id: string;
  /** ⭐ 父节点 id。组树只靠这个字段，不做字符串拼接。 */
  parent?: string | null;
  kind: string;
  label: string;
  comment?: string | null;
  meta?: string | null;
  hasChildren: boolean;
  loaded: boolean;
  riskBadge?: number | null;
  enumValues?: Record<string, string> | null;
}

export interface RowPage {
  columns: ColumnDef[];
  rows: Record<string, unknown>[];
  nextCursor?: { 0: number } | null;
  total?: number | null;
  durationMs: number;
  truncatedColumns: string[];
  mutated: boolean;
}

export interface QueryResult {
  danger: string;
  dangerLabel: string;
  readOnlyHint: boolean;
  destructiveHint: boolean;
  requiresConfirm: boolean;
  requiresApproval: boolean;
  page: RowPage;
}

export interface Finding {
  level: string;
  title: string;
  impact: string;
  action: string;
  table?: string;
}

export interface SearchHit {
  kind: string;
  id: string;
  label: string;
  comment?: string | null;
  rows?: number | null;
  table?: string;
}

export interface TypeMapItem {
  source: string;
  mapped: boolean;
  target?: string | null;
  lossy: boolean;
  reason?: string | null;
}

// ─────────────────────────────────────────────────────────────
// ⭐ 声明驱动的 UI 查询 —— 组件不许硬编码 if (conn === "mysql")
// ─────────────────────────────────────────────────────────────

export const capabilities = {
  current: null as CapabilitiesPayload | null,

  set(p: CapabilitiesPayload) { this.current = p; },

  /** 该连接类型是否支持某能力 */
  can(id: string, cap: keyof Capabilities): boolean {
    return this.byId(id)?.capabilities[cap] ?? false;
  },

  /** ⭐ 是否需要提示「DDL 会隐式提交」 */
  warnsImplicitDdlCommit(id: string): boolean {
    return this.byId(id)?.traits.transactionalDdl === false;
  },

  /** 是否展示序列工作台 */
  showsSequenceWorkspace(id: string): boolean {
    return this.byId(id)?.traits.sequenceObjects ?? false;
  },

  /** EXPLAIN 可用格式 */
  explainFormats(id: string): string[] {
    return this.byId(id)?.traits.explainFormats ?? ["text"];
  },

  /** 实验性连接默认隐藏 */
  isVisibleByDefault(id: string): boolean {
    return this.byId(id)?.supportLevel !== "experimental";
  },

  /** 该连接是否编进当前构建 */
  isCompiledIn(id: string): boolean {
    return this.current?.registered.includes(id) ?? false;
  },

  byId(id: string): Descriptor | undefined {
    return this.current?.connections.find((c) => c.id === id);
  },

  /**
   * ⭐ UI 警告引擎 —— **完全来自后端声明层**，本地不再推导。
   *
   * 之前这里是前端硬编码的 `if (!d.traits.transactionalDdl) ...`，
   * 意味着「加一种数据库要改两处代码」（YAML + 前端）。
   * 现在文案与触发条件都在 `adb-types::ui_warnings_by_id` 里，
   * 前端只负责按 `whereToShow` 决定渲染在哪个页面。
   */
  warnings(id: string): UiWarning[] {
    return this.current?.warnings?.[id] ?? [];
  },

  /** 只取某个渲染位置的警告 */
  warningsAt(id: string, site: UiWarning["whereToShow"]): UiWarning[] {
    return this.warnings(id).filter((w) => w.whereToShow === site);
  },
};

// ─────────────────────────────────────────────────────────────
// 端点
// ─────────────────────────────────────────────────────────────

export const api = {
  health: () => call<{ status: string; version: string }>("/health"),

  capabilities: () => call<CapabilitiesPayload>("/capabilities"),

  connections: () =>
    call<{ type: string; label: string; runtimeMode: string; compiledIn: boolean; defaultPort: number }[]>(
      "/connections",
    ),

  openSession: (kind = "sqlite") =>
    call<{
      kind: string;
      label: string;
      descriptor: Descriptor;
      warnings: UiWarning[];
      dbFile: string;
      tables: number;
    }>("/session/open", { method: "POST", body: JSON.stringify({ kind }) }),

  schema: () => call<{ dbId: string; takenAt: string; tables: TableDef[] }>("/schema"),

  tree: (parent?: string, depth: "name" | "structure" | "detail" = "name") => {
    const p = new URLSearchParams();
    if (parent) p.set("parent", parent);
    p.set("depth", depth);
    return call<TreeNode[]>(`/tree?${p}`);
  },

  table: (name: string) =>
    call<{ table: TableDef; diagnostics: Finding[]; ddl: string }>(
      `/table?name=${encodeURIComponent(name)}`,
    ),

  /**
   * 查询。
   *
   * ⭐ `offset` 必须传**服务端分页**，不能前端切片：
   *   前端切片会让「第 5 页」也得先拉回前 4 页的全部行，
   *   50 万行时直接卡死。
   */
  query: (sql: string, maxRows = 200, offset = 0) =>
    call<QueryResult>("/query", {
      method: "POST",
      body: JSON.stringify({ sql, maxRows, offset }),
    }),

  /**
   * ⭐ 真实写入（落库）。
   *
   * `/query` 对写语句一律 dry-run 回滚；数据网格的「编辑并保存」走这里。
   *
   * ⭐ `requireConfirm` 必须在**已经把 SQL 展示给用户并得到确认后**才传 `true`。
   *    少传会被服务端 428 挡下 —— 这是故意的：宁可响亮地失败，
   *    也不能让用户的生产库被静默改掉。
   */
  execute: (sql: string, requireConfirm = false) =>
    call<{ applied: boolean; affectedRows: number; danger: string; dangerLabel: string }>(
      "/execute",
      {
        method: "POST",
        body: JSON.stringify({ sql, requireConfirm }),
      },
    ),

  cancel: () => call<{ cancelled: boolean }>("/query/cancel", { method: "POST" }),

  search: (q: string) => call<SearchHit[]>(`/search?q=${encodeURIComponent(q)}`),

  diagnostics: () => call<Finding[]>("/diagnostics"),

  cell: (table: string, row: number, column: string, offset = 0, limit = 2000) => {
    const p = new URLSearchParams({ table, row: String(row), column, offset: String(offset), limit: String(limit) });
    return call<{ text: string; totalLen: number; truncated: boolean; nextOffset: number | null }>(
      `/cell?${p}`,
    );
  },

  typeMap: (from: string, to: string, types: string[]) =>
    call<{ from: string; to: string; fromFamily?: string; toFamily?: string; mappings: TypeMapItem[] }>(
      `/type-map?from=${from}&to=${to}&types=${encodeURIComponent(types.join(","))}`,
    ),
};

// ─────────────────────────────────────────────────────────────
// 工具
// ─────────────────────────────────────────────────────────────

export function compact(n: number | null | undefined): string {
  if (n == null) return "";
  if (n >= 1e8) return `${(n / 1e8).toFixed(1)}亿`;
  if (n >= 1e4) return `${(n / 1e4).toFixed(1)}万`;
  return String(n);
}

export function dangerClass(d: string): string {
  switch (d) {
    case "readOnly": return "risk-0";
    case "mutating": return "risk-1";
    case "destructive": return "risk-2";
    case "admin": return "risk-3";
    default: return "risk-0";
  }
}

export function dangerIcon(d: string): string {
  switch (d) {
    case "readOnly": return "🛡";
    case "mutating": return "✎";
    case "destructive": return "⚠";
    case "admin": return "⛨";
    default: return "🛡";
  }
}
