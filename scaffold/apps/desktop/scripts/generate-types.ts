/**
 * apps/desktop/scripts/generate-types.ts
 *
 * 从 declarations/*.yaml 生成 src/types/generated/*.ts
 *
 * ⭐ 三种调用模式：
 *   generate()               → 写文件（dev / build / 手动）
 *   generate({ check: true }) → 只校验不写（CI）
 *
 * ⭐ 校验逻辑与 crates/adb-types/build.rs **保持一致**，
 *    两边都会跑，所以前端和后端对同一份声明的理解不会分叉。
 */

import { readdirSync, readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { join, basename } from "node:path";
import { parse as parseYaml } from "yaml";

// ─────────────────────────────────────────────────────────────
// 类型
// ─────────────────────────────────────────────────────────────

export interface Traits {
  schemaAware: boolean;
  singleDatabase: boolean;
  clearableQuerySchema: boolean;
  identityKind: "identity" | "autoIncrement" | "rowguid" | "sequence" | "none";
  sequenceObjects: boolean;
  /** ⭐ false 时 UI 必须提示「DDL 会自动提交」 */
  transactionalDdl: boolean;
  onlineDdlConcurrent: boolean;
  multipleResultsets: boolean;
  savepoints: boolean;
  explainFormats: ("json" | "tree" | "text" | "stats")[];
  maxParams: number;
  identifierQuote: string;
  paramStyle: "numbered" | "positional" | "named";
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

export type ConnectionDescriptor = {
  schemaVersion: 1;
  order: number;
  id: string;
  rustVariant: string;
  label: string;
  labelEn?: string;
  dialect: string;
  runtimeMode: "native" | "jdbc" | "http";
  mcpMode: "builtin" | "bridge" | "none";
  defaultPort: number;
  urlTemplate?: string;
  driverClass?: string;
  traits: Traits;
  capabilities: Capabilities;
  compareModes: ("schema" | "data")[];
  transferSupportLevel: "native" | "family-compatible" | "read-only";
  transferObjectFamily: string;
  supportLevel: "full" | "operate" | "read-only" | "experimental";
};

export type TransferFamily = {
  schemaVersion: 1;
  id: string;
  label: string;
  members: string[];
  supportsTransactionalDdl: boolean;
  identityKeyword: string;
  quoteIdent: string;
  typeMapping: Record<
    string,
    { lossy: boolean; to: Record<string, string> }
  >;
};

// ─────────────────────────────────────────────────────────────
// 入口
// ─────────────────────────────────────────────────────────────

const CAPABILITY_KEYS: (keyof Capabilities)[] = [
  "queryExecution", "metadataBrowse", "objectBrowser", "objectSource",
  "schemaSearch", "diagram", "tableDataEdit", "tableStructureEdit",
  "tableImport", "dataTransfer", "sqlFileExecution", "databaseCreate",
  "fieldLineage", "sqlExplain", "userAdmin",
];

const DECL = join(process.cwd(), "declarations");
const OUT = join(process.cwd(), "src/types/generated");

export function generate(opts: { check?: boolean } = {}): {
  errors: string[];
  changed: string[];
} {
  const errors: string[] = [];
  const changed: string[] = [];

  // ── 读取 ──
  const families = readAll<TransferFamily>("transfer-families", errors);
  const connections = readAll<ConnectionDescriptor>("connections", errors);

  const dialectSet = existsSync(join(DECL, "dialects"))
    ? new Set(
        readdirSync(join(DECL, "dialects"))
          .filter((f) => f.endsWith(".yaml"))
          .map((f) => basename(f, ".yaml")),
      )
    : new Set<string>();

  const familySet = new Set(families.map((f) => f.id));
  const connectionIds = new Set(connections.map((c) => c.id));

  // ── 唯一性（与 build.rs 一致）──
  assertUnique(connections.map((c) => c.id), "connection id", errors);
  assertUnique(connections.map((c) => c.rustVariant), "rustVariant", errors);
  assertUnique(connections.map((c) => String(c.order)), "connection order", errors);
  assertUnique(families.map((f) => f.id), "transfer family id", errors);

  // ── 语义校验（与 build.rs 一致，逐条对应）──
  for (const c of connections) {
    const at = `connections/${c.id}.yaml`;
    if (c.schemaVersion !== 1) errors.push(`${at}: schemaVersion 必须为 1`);

    if (!dialectSet.has(c.dialect))
      errors.push(`${at}: 未知方言 '${c.dialect}'（declarations/dialects/${c.dialect}.yaml 缺失）`);

    if (!familySet.has(c.transferObjectFamily))
      errors.push(
        `${at}: 未知 transferObjectFamily '${c.transferObjectFamily}'` +
        `（declarations/transfer-families/${c.transferObjectFamily}.yaml 缺失）`,
      );

    // ⭐ 规则：除非有专属界面，否则至少开一个能力
    const enabled = CAPABILITY_KEYS.filter((k) => c.capabilities[k]).length;
    if (!c.capabilities.specializedSurface && enabled === 0)
      errors.push(
        `${at}: 所有能力均为 false。请至少开启一项，` +
        `或设置 capabilities.specializedSurface: true（若该产品有专属管理界面）`,
      );

    if (c.runtimeMode === "jdbc" && !c.driverClass)
      errors.push(`${at}: runtimeMode 'jdbc' 必须声明 driverClass`);

    if (c.traits.identityKind === "sequence" && !c.traits.sequenceObjects)
      errors.push(`${at}: identityKind=sequence 与 sequenceObjects=false 矛盾`);

    if (!c.traits.schemaAware && !c.traits.singleDatabase)
      errors.push(`${at}: schemaAware 与 singleDatabase 不能同时为 false`);

    if (c.traits.explainFormats.length === 0)
      errors.push(`${at}: explainFormats 不能为空（最简用 [text]）`);

    if (c.mcpMode === "bridge" && c.runtimeMode !== "jdbc")
      errors.push(`${at}: mcpMode 'bridge' 要求 runtimeMode 'jdbc'`);
  }

  // ── 家族成员必须都是已声明的连接 ──
  for (const f of families) {
    for (const m of f.members) {
      if (!connectionIds.has(m))
        errors.push(`transfer-families/${f.id}.yaml: 成员 '${m}' 在 connections/ 中不存在`);
    }
  }

  if (errors.length) return { errors, changed };

  // ── 排序 + 生成 ──
  connections.sort((a, b) => a.order - b.order);
  families.sort((a, b) => a.id.localeCompare(b.id));

  const files: [string, string][] = [
    ["connections.ts", renderConnections(connections)],
    ["transferFamilies.ts", renderFamilies(families)],
    ["declarations.json", JSON.stringify({ connections, families }, null, 2) + "\n"],
  ];

  for (const [name, content] of files) {
    const path = join(OUT, name);
    const prev = existsSync(path) ? readFileSync(path, "utf8") : null;
    if (prev === content) continue;
    changed.push(name);
    if (!opts.check) {
      mkdirSync(OUT, { recursive: true });
      writeFileSync(path, content);
    }
  }

  if (opts.check && changed.length) {
    errors.push(
      `生成物与 declarations 不一致: ${changed.join(", ")}\n` +
      `  运行 \`pnpm generate:types\` 重新生成后提交。`,
    );
  }

  return { errors, changed };
}

// ─────────────────────────────────────────────────────────────
// 渲染
// ─────────────────────────────────────────────────────────────

function renderConnections(cs: ConnectionDescriptor[]): string {
  const union = cs.map((c) => `"${c.id}"`).join(" | ");

  return `// @generated — DO NOT EDIT. 源: declarations/connections/*.yaml
// 运行 \`pnpm generate:types\` 重新生成

export type ConnectionId = ${union};

/** ⭐ 全部显式声明，不依赖"缺失即 false" */
export interface Capabilities {
${CAPABILITY_KEYS.map((k) => `  ${k}: boolean;`).join("\n")}
  specializedSurface: boolean;
}

export interface Traits {
  schemaAware: boolean;
  singleDatabase: boolean;
  clearableQuerySchema: boolean;
  identityKind: "identity" | "autoIncrement" | "rowguid" | "sequence" | "none";
  sequenceObjects: boolean;
  /** false 时 UI 必须提示「DDL 会自动提交，无法回滚」 */
  transactionalDdl: boolean;
  onlineDdlConcurrent: boolean;
  multipleResultsets: boolean;
  savepoints: boolean;
  explainFormats: ("json" | "tree" | "text" | "stats")[];
  maxParams: number;
  identifierQuote: string;
  paramStyle: "numbered" | "positional" | "named";
}

export interface ConnectionDescriptor {
  id: ConnectionId;
  order: number;
  label: string;
  labelEn?: string;
  dialect: string;
  runtimeMode: "native" | "jdbc" | "http";
  mcpMode: "builtin" | "bridge" | "none";
  defaultPort: number;
  urlTemplate?: string;
  driverClass?: string;
  traits: Traits;
  capabilities: Capabilities;
  compareModes: ("schema" | "data")[];
  transferSupportLevel: "native" | "family-compatible" | "read-only";
  transferObjectFamily: string;
  supportLevel: "full" | "operate" | "read-only" | "experimental";
}

export const CONNECTIONS = ${JSON.stringify(
    cs.map(({ schemaVersion: _s, rustVariant: _r, ...rest }) => rest),
    null,
    2,
  )} as const satisfies readonly ConnectionDescriptor[];

export const CONNECTION_BY_ID: ReadonlyMap<ConnectionId, ConnectionDescriptor> =
  new Map(CONNECTIONS.map((c) => [c.id, c]));

// ─────────────────────────────────────────────────────────────
// ⭐ UI 能力查询 —— 组件不要直接读 capabilities 字段，读这里
// ─────────────────────────────────────────────────────────────

/** 该连接类型是否支持某能力 */
export function can(id: ConnectionId, cap: keyof Capabilities): boolean {
  return CONNECTION_BY_ID.get(id)?.capabilities[cap] ?? false;
}

/** 是否需要提示「DDL 会自动提交」 */
export function warnsImplicitDdlCommit(id: ConnectionId): boolean {
  return CONNECTION_BY_ID.get(id)?.traits.transactionalDdl === false;
}

/** 是否展示序列工作台 */
export function showsSequenceWorkspace(id: ConnectionId): boolean {
  return CONNECTION_BY_ID.get(id)?.traits.sequenceObjects ?? false;
}

/** EXPLAIN 可用格式（UI 只渲染支持的选项） */
export function explainFormats(id: ConnectionId): Traits["explainFormats"] {
  return CONNECTION_BY_ID.get(id)?.traits.explainFormats ?? ["text"];
}

/** 实验性连接默认隐藏 */
export function isVisibleByDefault(id: ConnectionId): boolean {
  return CONNECTION_BY_ID.get(id)?.supportLevel !== "experimental";
}

/** 支持水平分页（vs OFFSET 分页） */
export function supportsFetchFirst(id: ConnectionId): boolean {
  return CONNECTION_BY_ID.get(id)?.traits.clearableQuerySchema ?? false;
}
`;
}

function renderFamilies(fs: TransferFamily[]): string {
  return `// @generated — DO NOT EDIT. 源: declarations/transfer-families/*.yaml

export interface TypeRule { lossy: boolean; to: Record<string, string>; }

export interface TransferFamilyDescriptor {
  id: string;
  label: string;
  members: readonly string[];
  supportsTransactionalDdl: boolean;
  identityKeyword: string;
  quoteIdent: string;
  typeMapping: Record<string, TypeRule>;
}

export const TRANSFER_FAMILIES = ${JSON.stringify(fs, null, 2)} as const
  satisfies readonly TransferFamilyDescriptor[];

const BY_ID = new Map(TRANSFER_FAMILIES.map((f) => [f.id, f]));

/** ⭐ 传输引擎的唯一入口：按家族拿类型映射 */
export function familyOf(connectionId: string): TransferFamilyDescriptor | undefined {
  for (const f of TRANSFER_FAMILIES) {
    if (f.members.includes(connectionId)) return f;
  }
  return undefined;
}

/**
 * 解析目标类型。
 * ⭐ lossy=true 的映射必须由 UI 显式提示并要求用户确认
 *    （DBX #9667 精度丢失、#10556 整数溢出的根因）
 */
export function mapType(
  fromConn: string,
  toConn: string,
  sourceType: string,
): { target: string; lossy: boolean; reason?: string } | undefined {
  const from = familyOf(fromConn);
  const to = familyOf(toConn);
  if (!from || !to) return undefined;

  const rule = from.typeMapping[sourceType.toLowerCase()];
  if (!rule) return undefined;

  const target = rule.to[to.id];
  if (!target) {
    return { target: to.id === "oracle" ? "VARCHAR2(4000)" : "TEXT", lossy: true,
             reason: \`\${from.id} 家族无 \${sourceType} → \${to.id} 的映射，使用兜底类型\` };
  }
  return {
    target,
    lossy: rule.lossy,
    reason: rule.lossy ? \`\${sourceType} → \${target} 可能丢失精度或语义\` : undefined,
  };
}
`;
}

// ─────────────────────────────────────────────────────────────
// 工具
// ─────────────────────────────────────────────────────────────

function readAll<T>(dir: string, errors: string[]): T[] {
  const full = join(DECL, dir);
  if (!existsSync(full)) { errors.push(`缺失目录: declarations/${dir}`); return []; }
  const out: T[] = [];
  for (const f of readdirSync(full).sort()) {
    if (!f.endsWith(".yaml") && !f.endsWith(".yml")) continue;
    const path = join(full, f);
    try {
      out.push(parseYaml(readFileSync(path, "utf8")));
    } catch (e) {
      errors.push(`declarations/${dir}/${f}: YAML 解析失败 — ${(e as Error).message}`);
    }
  }
  return out;
}

function assertUnique(values: string[], label: string, errors: string[]): void {
  const seen = new Set<string>();
  for (const v of values) {
    if (seen.has(v)) errors.push(`重复的 ${label}: '${v}'`);
    seen.add(v);
  }
}

// ─────────────────────────────────────────────────────────────
// CLI
// ─────────────────────────────────────────────────────────────

if (import.meta.url === `file://${process.argv[1]}`) {
  const check = process.argv.includes("--check");
  const { errors, changed } = generate({ check });
  if (errors.length) {
    console.error("\x1b[31m[adb-types] 校验失败\x1b[0m");
    for (const e of errors) console.error(`  ✗ ${e}`);
    process.exit(1);
  }
  if (check) {
    console.log("\x1b[32m✓\x1b[0m 生成物与 declarations 一致");
  } else {
    console.log(
      changed.length
        ? `\x1b[32m✓\x1b[0m 已生成: ${changed.join(", ")}`
        : "\x1b[32m✓\x1b[0m 无变化",
    );
  }
}
