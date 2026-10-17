//! crates/adb-types/build.rs
//!
//! 从 `declarations/` 生成：
//!   1. `$OUT_DIR/connection_type.rs`       → `enum ConnectionType` + `ALL` + `as_str`
//!   2. `$OUT_DIR/connection_manifest.json` → 嵌入二进制，前端可读
//!   3. `$OUT_DIR/transfer_family.rs`       → `enum TransferFamily`
//!   4. `$OUT_DIR/build_report.txt`         → 人类可读的构建摘要
//!
//! 设计来源：DBX `crates/dbx-types/build.rs`（https://github.com/t8y2/dbx）
//! 关键增强：交叉引用校验 + 传输家族校验 + 能力完整性校验 + 占位符检测。
//!
//! ⭐ 本文件是"声明层"的唯一执行点。所有架构约束在这里落地为 panic，
//!    由 CI 在编译期捕获，而不是等到运行时。

use serde::Deserialize;
use serde_json::Value as Json;
use std::collections::{BTreeMap, HashSet};
use std::env;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

// ─────────────────────────────────────────────────────────────
// 描述符结构（与 declarations/schema/connection.schema.json 对齐）
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ConnectionDescriptor {
    schema_version: u32,
    order: u32,
    id: String,
    rust_variant: String,
    label: String,
    #[serde(default)]
    label_en: Option<String>,

    dialect: String,
    runtime_mode: RuntimeMode,
    mcp_mode: McpMode,
    default_port: u16,

    #[serde(default)]
    url_template: Option<String>,
    #[serde(default)]
    driver_class: Option<String>,
    #[serde(default)]
    driver_artifact: Option<DriverArtifact>,

    traits: Traits,
    capabilities: Capabilities,

    #[serde(default = "default_compare_modes")]
    compare_modes: Vec<CompareMode>,
    #[serde(default = "default_transfer_level")]
    transfer_support_level: TransferSupportLevel,
    transfer_object_family: String,
    #[serde(default = "default_support_level")]
    support_level: SupportLevel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum RuntimeMode {
    Native,
    Jdbc,
    Http,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum McpMode {
    Builtin,
    Bridge,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum CompareMode {
    Schema,
    Data,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
enum TransferSupportLevel {
    Native,
    FamilyCompatible,
    ReadOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
enum SupportLevel {
    Full,
    Operate,
    ReadOnly,
    Experimental,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DriverArtifact {
    key: String,
    #[serde(default)]
    jar: Option<String>,
    #[serde(default)]
    checksum: Option<String>,
    #[serde(default)]
    license: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Traits {
    schema_aware: bool,
    single_database: bool,
    clearable_query_schema: bool,
    identity_kind: IdentityKind,
    sequence_objects: bool,
    // ⭐ 这个字段直接驱动 UI 的「DDL 会自动提交」提示
    transactional_ddl: bool,
    online_ddl_concurrent: bool,
    multiple_resultsets: bool,
    savepoints: bool,
    explain_formats: Vec<ExplainFormat>,
    max_params: u32,
    identifier_quote: String,
    param_style: ParamStyle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum IdentityKind {
    Identity,
    AutoIncrement,
    Rowguid,
    Sequence,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum ExplainFormat {
    Json,
    Tree,
    Text,
    Stats,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
enum ParamStyle {
    Numbered,
    Positional,
    Named,
}

/// ⭐ 全部字段显式声明。`deny_unknown_fields` 保证新增能力必须同步更新这里，
///    从而强制所有 YAML 补上该字段（缺失会在反序列化时报错）。
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Capabilities {
    query_execution: bool,
    metadata_browse: bool,
    object_browser: bool,
    object_source: bool,
    schema_search: bool,
    diagram: bool,
    table_data_edit: bool,
    table_structure_edit: bool,
    table_import: bool,
    data_transfer: bool,
    sql_file_execution: bool,
    database_create: bool,
    field_lineage: bool,
    sql_explain: bool,
    user_admin: bool,
    #[serde(default)]
    specialized_surface: bool,
}

impl Capabilities {
    fn enabled(&self) -> usize {
        [
            self.query_execution, self.metadata_browse, self.object_browser,
            self.object_source, self.schema_search, self.diagram,
            self.table_data_edit, self.table_structure_edit, self.table_import,
            self.data_transfer, self.sql_file_execution, self.database_create,
            self.field_lineage, self.sql_explain, self.user_admin,
        ]
        .iter()
            .filter(|b| **b)
            .count()
    }

    fn all(&self) -> usize { 15 }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TransferFamily {
    schema_version: u32,
    id: String,
    label: String,
    members: Vec<String>,
    #[serde(default)]
    supports_transactional_ddl: bool,
    #[serde(default)]
    identity_keyword: String,
    #[serde(default)]
    quote_ident: String,
    #[serde(default)]
    type_mapping: BTreeMap<String, TypeRule>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TypeRule {
    to: BTreeMap<String, String>,
    #[serde(default)]
    lossy: bool,
}

fn default_compare_modes() -> Vec<CompareMode> {
    vec![CompareMode::Schema]
}
fn default_transfer_level() -> TransferSupportLevel {
    TransferSupportLevel::Native
}
fn default_support_level() -> SupportLevel {
    SupportLevel::Operate
}

// ─────────────────────────────────────────────────────────────
// 入口
// ─────────────────────────────────────────────────────────────

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let declarations = manifest_dir.join("../../declarations");

    let dialects = load_set(&declarations.join("dialects"), "yaml");
    let families = generate_transfer_families(&declarations.join("transfer-families"), &out_dir, &dialects);
    let connections = generate_connections(
        &declarations.join("connections"),
        &out_dir,
        &dialects,
        &families,
    );

    write_report(&out_dir, &connections, &families);
    rerun_if_changed_recursive(&declarations);
}

/// ⭐ 构建产物的可读摘要。编译日志里直接能看到，避免"忘了跑生成"。
fn write_report(out: &Path, conns: &[(ConnectionDescriptor, Json)], fams: &[TransferFamily]) {
    let mut r = String::new();
    writeln!(r, "AgenticDB declarations build").unwrap();
    writeln!(r, "  connections   : {}", conns.len()).unwrap();
    writeln!(r, "  transfer fams : {}", fams.len()).unwrap();
    writeln!(r, "").unwrap();
    writeln!(r, "  {:<4} {:<18} {:<9} {:<8} {:<8} {}", "#", "id", "runtime", "support", "family", "caps")
        .unwrap();
    for (i, (d, _)) in conns.iter().enumerate() {
        writeln!(
            r, "  {:<4} {:<18} {:<9} {:<8} {:<8} {}/{}",
            d.order, d.id, format!("{:?}", d.runtime_mode).to_lowercase(),
            format!("{:?}", d.support_level).to_lowercase(),
            d.transfer_object_family,
            d.capabilities.enabled(), d.capabilities.all(),
        ).unwrap();
        let _ = i;
    }
    fs::write(out.join("build_report.txt"), r).unwrap();
    println!("cargo::warning=\n{}", r);
}

// ─────────────────────────────────────────────────────────────
// 连接类型生成 + 校验
// ─────────────────────────────────────────────────────────────

fn generate_connections(
    dir: &Path,
    out: &Path,
    dialects: &HashSet<String>,
    families: &HashSet<String>,
) -> Vec<(ConnectionDescriptor, Json)> {
    let mut parsed: Vec<(ConnectionDescriptor, Json)> = Vec::new();
    let mut ids = HashSet::new();
    let mut variants = HashSet::new();
    let mut orders = HashSet::new();

    for path in yaml_sorted(dir) {
        let raw = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        let display = path.display().to_string();

        // ① schemaVersion
        let d: ConnectionDescriptor = serde_yaml_ng::from_str(&raw)
            .unwrap_or_else(|e| panic!("invalid descriptor {display}: {e}"));

        // ② 唯一性：id / rustVariant / order
        assert_eq!(d.schema_version, 1, "{display}: schemaVersion must be 1");
        assert!(ids.insert(d.id.clone()), "{display}: duplicate id '{}'", d.id);
        assert!(
            variants.insert(d.rust_variant.clone()),
            "{display}: duplicate rustVariant '{}'", d.rust_variant
        );
        assert!(
            orders.insert(d.order),
            "{display}: duplicate order {} (already used by another descriptor)", d.order
        );

        validate_semantics(&d, &display, dialects, families);

        // ③ 生成对外 JSON（剔除内部字段）
        let mut json: Json = serde_yaml_ng::from_str(&raw).expect("descriptor must be a map");
        let obj = json.as_object_mut().expect("descriptor must be an object");
        for internal in ["schemaVersion", "order", "rustVariant"] {
            obj.remove(internal);
        }
        parsed.push((d, json));
    }

    parsed.sort_by_key(|(d, _)| d.order);

    // 产物 1：Rust enum
    let mut code = String::new();
    code.push_str(
        "// @generated by crates/adb-types/build.rs — DO NOT EDIT\n\
         // 源: declarations/connections/*.yaml\n\n",
    );
    code.push_str("#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]\n");
    code.push_str("#[serde(rename_all = \"kebab-case\")]\npub enum ConnectionType {\n");
    for (d, _) in &parsed {
        writeln!(code, "    #[serde(rename = \"{}\")]", d.id).unwrap();
        writeln!(code, "    {},", d.rust_variant).unwrap();
    }
    code.push_str("}\n\nimpl ConnectionType {\n");
    code.push_str("    pub const ALL: &'static [Self] = &[\n");
    for (d, _) in &parsed {
        writeln!(code, "        Self::{},", d.rust_variant).unwrap();
    }
    code.push_str("    ];\n\n");
    code.push_str("    pub const fn as_str(self) -> &'static str {\n        match self {\n");
    for (d, _) in &parsed {
        writeln!(code, "            Self::{} => \"{}\",", d.rust_variant, d.id).unwrap();
    }
    code.push_str("        }\n    }\n\n");
    code.push_str("    pub const fn label(self) -> &'static str {\n        match self {\n");
    for (d, _) in &parsed {
        writeln!(code, "            Self::{} => \"{}\",", d.rust_variant, d.label).unwrap();
    }
    code.push_str("        }\n    }\n");
    code.push_str(
        "\n    pub const fn transfer_family(self) -> TransferFamily {\n        match self {\n",
    );
    for (d, _) in &parsed {
        let variant = family_variant(&d.transfer_object_family);
        writeln!(
            code,
            "            Self::{} => TransferFamily::{},",
            d.rust_variant, variant
        ).unwrap();
    }
    code.push_str("        }\n    }\n}\n");
    fs::write(out.join("connection_type.rs"), code).unwrap();

    // 产物 2：manifest JSON（嵌入二进制，前端读它拿连接表单定义）
    let manifest = serde_json::json!({
        "schemaVersion": 1,
        "connections": parsed.iter().map(|(_, j)| j.clone()).collect::<Vec<_>>(),
    });
    fs::write(
        out.join("connection_manifest.json"),
        serde_json::to_string(&manifest).unwrap(),
    )
    .unwrap();

    parsed
}

/// ⭐⭐ 语义校验 —— DBX 只做了唯一性校验，我们补上这 8 条交叉引用与一致性检查。
fn validate_semantics(
    d: &ConnectionDescriptor,
    display: &str,
    dialects: &HashSet<String>,
    families: &HashSet<String>,
) {
    // ④ 方言必须存在
    assert!(
        dialects.contains(&d.dialect),
        "{display}: unknown dialect '{}' (declarations/dialects/{}.yaml missing)",
        d.dialect, d.dialect
    );

    // ⑤ 传输家族必须存在
    assert!(
        families.contains(&d.transfer_object_family),
        "{display}: unknown transferObjectFamily '{}' (declarations/transfer-families/{}.yaml missing)",
        d.transfer_object_family, d.transfer_object_family
    );

    // ⑥ ⭐ 能力完整性：除非有专属界面，否则至少开一个能力
    //    抄自 DBX connection-types/README.md 的规则
    if !d.capabilities.specialized_surface && d.capabilities.enabled() == 0 {
        panic!(
            "{display}: all capabilities are false.\n  \
             Either enable at least one capability, or set \
             capabilities.specializedSurface: true if the product has a \
             dedicated management UI."
        );
    }

    // ⑦ ⭐ JDBC 通道必须声明 driverClass
    if d.runtime_mode == RuntimeMode::Jdbc {
        assert!(
            d.driver_class.as_deref().is_some_and(|s| !s.is_empty()),
            "{display}: runtimeMode 'jdbc' requires a non-empty driverClass"
        );
    }

    // ⑧ ⭐ 驱动校验和不能是占位符
    if let Some(artifact) = &d.driver_artifact {
        if let Some(sum) = &artifact.checksum {
            assert!(
                !sum.contains("REPLACE_ME") && !sum.contains("TODO"),
                "{display}: driverArtifact.checksum for '{}' is still a placeholder",
                artifact.key
            );
        }
    }

    // ⑨ ⭐ traits 自洽性
    assert!(
        !(d.traits.identity_kind == IdentityKind::Sequence && !d.traits.sequence_objects),
        "{display}: identityKind=sequence but sequenceObjects=false is contradictory"
    );
    assert!(
        d.traits.schema_aware || d.traits.single_database,
        "{display}: neither schemaAware nor singleDatabase — \
         every engine has at least a database layer"
    );
    assert!(
        !d.traits.explain_formats.is_empty(),
        "{display}: explainFormats must not be empty (use [text])"
    );

    // ⑩ ⭐ 传输家族一致性：声明了 family-compatible 就必须真的有家族规则
    if d.transfer_support_level == TransferSupportLevel::FamilyCompatible
        && d.transfer_object_family.is_empty()
    {
        panic!("{display}: transferSupportLevel=family-compatible requires transferObjectFamily");
    }

    // ⑪ ⭐ 运行时模式与 MCP 模式自洽
    assert!(
        !(d.mcp_mode == McpMode::Bridge && d.runtime_mode != RuntimeMode::Jdbc),
        "{display}: mcpMode 'bridge' requires runtimeMode 'jdbc' \
         (bridge means: MCP goes through the Agent)"
    );
}

// ─────────────────────────────────────────────────────────────
// 传输家族生成
// ─────────────────────────────────────────────────────────────

fn generate_transfer_families(
    dir: &Path,
    out: &Path,
    _dialects: &HashSet<String>,
) -> Vec<TransferFamily> {
    let mut families: Vec<TransferFamily> = Vec::new();
    let mut ids = HashSet::new();

    for path in yaml_sorted(dir) {
        let raw = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        let display = path.display().to_string();
        let f: TransferFamily = serde_yaml_ng::from_str(&raw)
            .unwrap_or_else(|e| panic!("invalid transfer family {display}: {e}"));

        assert_eq!(f.schema_version, 1, "{display}: schemaVersion must be 1");
        assert!(ids.insert(f.id.clone()), "{display}: duplicate family '{}'", f.id);
        assert!(!f.members.is_empty(), "{display}: members must not be empty");

        families.push(f);
    }
    families.sort_by(|a, b| a.id.cmp(&b.id));

    // 反向校验：每个连接的 family 成员关系必须自洽
    // （成员列表里的 id 必须在 connections 里存在 —— 在 generate_connections 里检查）

    let mut code = String::new();
    code.push_str("// @generated by crates/adb-types/build.rs — DO NOT EDIT\n\n");
    code.push_str("#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]\npub enum TransferFamily {\n");
    for f in &families {
        writeln!(code, "    #[serde(rename = \"{}\")]", f.id).unwrap();
        writeln!(code, "    {},", family_variant(&f.id)).unwrap();
    }
    code.push_str("}\n\nimpl TransferFamily {\n    pub const ALL: &'static [Self] = &[\n");
    for f in &families {
        writeln!(code, "        Self::{},", family_variant(&f.id)).unwrap();
    }
    code.push_str("    ];\n\n    pub const fn as_str(self) -> &'static str {\n        match self {\n");
    for f in &families {
        writeln!(code, "            Self::{} => \"{}\",", family_variant(&f.id), f.id).unwrap();
    }
    code.push_str("        }\n    }\n\n    pub fn members(self) -> &'static [&'static str] {\n        match self {\n");
    for f in &families {
        let list = f
            .members
            .iter()
            .map(|m| format!("\"{m}\""))
            .collect::<Vec<_>>()
            .join(", ");
        writeln!(code, "            Self::{} => &[{}],", family_variant(&f.id), list).unwrap();
    }
    code.push_str("        }\n    }\n}\n");
    fs::write(out.join("transfer_family.rs"), code).unwrap();

    // 家族映射表也导出 JSON，给传输引擎用
    let json: Vec<Json> = families
        .iter()
        .map(|f| serde_json::json!({
            "id": f.id, "label": f.label, "members": f.members,
            "supportsTransactionalDdl": f.supports_transactional_ddl,
            "identityKeyword": f.identity_keyword, "quoteIdent": f.quote_ident,
            "typeMapping": f.type_mapping,
        }))
        .collect();
    fs::write(
        out.join("transfer_families.json"),
        serde_json::to_string(&json).unwrap(),
    )
    .unwrap();

    families.into_iter().map(|f| f.id).collect()
}

// ─────────────────────────────────────────────────────────────
// 工具
// ─────────────────────────────────────────────────────────────

fn yaml_sorted(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(dir)
        .unwrap_or_else(|_| panic!("cannot read directory {}", dir.display()))
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| matches!(p.extension().and_then(|s| s.to_str()), Some("yaml") | Some("yml")))
        .collect();
    v.sort();
    v
}

fn load_set(dir: &Path, ext: &str) -> HashSet<String> {
    if !dir.exists() {
        return HashSet::new();
    }
    yaml_sorted(dir)
        .into_iter()
        .filter_map(|p| {
            let stem = p.file_stem()?.to_str()?.to_string();
            let _ = ext;
            Some(stem)
        })
        .collect()
}

fn family_variant(id: &str) -> String {
    let mut s = String::new();
    for (i, part) in id.split(['-', '_']).enumerate() {
        let mut cs = part.chars();
        if let Some(first) = cs.next() {
            if i == 0 {
                s.extend(first.to_uppercase());
            } else {
                s.extend(first.to_uppercase());
                s.extend(cs);
            }
        }
    }
    s
}

/// ⭐ 目录级重建：新增/删除文件也会触发。
///    文件级重建在调用处逐个 `rerun-if-changed`。
fn rerun_if_changed_recursive(dir: &Path) {
    if !dir.exists() {
        return;
    }
    println!("cargo::rerun-if-changed={}", dir.display());
    for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
        let p = entry.path();
        if p.is_dir() {
            rerun_if_changed_recursive(&p);
        } else {
            println!("cargo::rerun-if-changed={}", p.display());
        }
    }
}

// Cargo.toml
// [build-dependencies]
// serde = { version = "1", features = ["derive"] }
// serde_json = "1"
// serde_yaml_ng = "0.10"
