//! `adb-types` —— 零业务依赖的类型层。
//!
//! ⭐ 构建期由 `build.rs` 消费 `declarations/`，生成：
//!   1. `ConnectionType` enum（来自 `declarations/connections/*.yaml`）
//!   2. `TransferFamily` enum（来自 `declarations/transfer-families/*.yaml`）
//!   3. `connection_manifest.json`（嵌入二进制，前端可读）
//!   4. `build_report.txt`（⭐ 编译时打印能力矩阵，防止"忘了跑生成"）
//!
//! 设计来源：DBX `crates/dbx-types/build.rs`。关键增强是**交叉引用校验**，
//! 让声明层的错误在**编译期**暴露而不是运行时。

use serde::{Deserialize, Serialize};

// ─────────────────────────────────────────────────────────────
// 构建产物
// ─────────────────────────────────────────────────────────────

include!(concat!(env!("OUT_DIR"), "/connection_type.rs"));
include!(concat!(env!("OUT_DIR"), "/transfer_family.rs"));

/// 嵌入的声明清单。前端通过 HTTP 读取它来渲染连接表单与能力矩阵。
pub const CONNECTION_MANIFEST: &str =
    include_str!(concat!(env!("OUT_DIR"), "/connection_manifest.json"));
pub const TRANSFER_FAMILIES_JSON: &str =
    include_str!(concat!(env!("OUT_DIR"), "/transfer_families.json"));

// ─────────────────────────────────────────────────────────────
// 描述符（与 build.rs 同构，用于运行期查询）
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Descriptor {
    pub id: String,
    pub order: u32,
    pub label: String,
    #[serde(default)]
    pub label_en: Option<String>,
    pub dialect: String,
    pub runtime_mode: RuntimeMode,
    pub mcp_mode: McpMode,
    pub default_port: u16,
    #[serde(default)]
    pub url_template: Option<String>,
    #[serde(default)]
    pub driver_class: Option<String>,
    pub traits: Traits,
    pub capabilities: Capabilities,
    pub compare_modes: Vec<String>,
    pub transfer_support_level: String,
    pub transfer_object_family: String,
    pub support_level: SupportLevel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RuntimeMode { Native, Jdbc, Http }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum McpMode { Builtin, Bridge, None }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum SupportLevel { Full, Operate, ReadOnly, Experimental }

impl SupportLevel {
    /// ⭐ experimental 的连接在 UI 默认隐藏，需 Settings 开启。
    pub fn visible_by_default(self) -> bool { self != Self::Experimental }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Traits {
    pub schema_aware: bool,
    pub single_database: bool,
    pub clearable_query_schema: bool,
    pub identity_kind: String,
    pub sequence_objects: bool,
    /// ⭐ `false` 时 UI 必须提示「DDL 会隐式提交，无法通过事务回滚」
    pub transactional_ddl: bool,
    pub online_ddl_concurrent: bool,
    pub multiple_resultsets: bool,
    pub savepoints: bool,
    pub explain_formats: Vec<String>,
    pub max_params: u32,
    pub identifier_quote: String,
    pub param_style: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub query_execution: bool,
    pub metadata_browse: bool,
    pub object_browser: bool,
    pub object_source: bool,
    pub schema_search: bool,
    pub diagram: bool,
    pub table_data_edit: bool,
    pub table_structure_edit: bool,
    pub table_import: bool,
    pub data_transfer: bool,
    pub sql_file_execution: bool,
    pub database_create: bool,
    pub field_lineage: bool,
    pub sql_explain: bool,
    pub user_admin: bool,
    pub specialized_surface: bool,
}

impl Capabilities {
    pub fn enabled_count(&self) -> usize {
        [
            self.query_execution, self.metadata_browse, self.object_browser,
            self.object_source, self.schema_search, self.diagram,
            self.table_data_edit, self.table_structure_edit, self.table_import,
            self.data_transfer, self.sql_file_execution, self.database_create,
            self.field_lineage, self.sql_explain, self.user_admin,
        ].iter().filter(|b| **b).count()
    }
}

// ─────────────────────────────────────────────────────────────
// 运行期查询
// ─────────────────────────────────────────────────────────────

/// ⭐ 单例描述符表。声明在编译期校验，运行期只读。
pub fn all_descriptors() -> &'static [Descriptor] {
    use std::sync::OnceLock;
    static CACHE: OnceLock<Vec<Descriptor>> = OnceLock::new();
    CACHE.get_or_init(|| {
        let v: serde_json::Value =
            serde_json::from_str(CONNECTION_MANIFEST).expect("embedded manifest must parse");
        let drivers = v.get("connections").cloned().unwrap_or_default();
        serde_json::from_value(drivers).expect("descriptor shape must match")
    })
}

pub fn descriptor(t: ConnectionType) -> &'static Descriptor {
    all_descriptors()
        .iter()
        .find(|d| d.id == t.as_str())
        .unwrap_or_else(|| panic!("no descriptor for {}", t.as_str()))
}

pub fn descriptor_by_id(id: &str) -> Option<&'static Descriptor> {
    all_descriptors().iter().find(|d| d.id == id)
}

// ─────────────────────────────────────────────────────────────
// ⭐ UI 警告引擎 —— 把 UI 提示从硬编码变成声明驱动
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WarningLevel { Info, Important, Critical }

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WarnSite { Sidebar, StructureEditor, Transfer, QueryEditor, DataGrid }

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UiWarning {
    pub level: WarningLevel,
    pub code: &'static str,
    pub message: String,
    pub where_to_show: WarnSite,
}

/// ⭐ 组件不许写 `if (conn === "mysql")`，一律问这里。
pub fn ui_warnings(t: ConnectionType) -> Vec<UiWarning> {
    warnings_of(descriptor(t))
}

/// ⭐ 按连接 **id**（而非枚举）取 UI 警告。
///
/// 存在的理由：`Descriptor` 本身不携带 `ConnectionType`（它是纯数据），
/// 而 HTTP 层的连接列表按 id 索引。让调用方在 web 层做
/// `id → ConnectionType` 反查会破坏「声明层是唯一真相」这条纪律 ——
/// 那段反查迟早会漏掉一个新加的连接类型。
pub fn ui_warnings_by_id(id: &str) -> Vec<UiWarning> {
    match descriptor_by_id(id) {
        Some(d) => warnings_of(d),
        None => Vec::new(),
    }
}

/// ⭐ 唯一的警告推导实现。`traits` → 用户可见提示，全在这里。
fn warnings_of(d: &Descriptor) -> Vec<UiWarning> {
    let mut v = Vec::new();

    if !d.traits.transactional_ddl {
        v.push(UiWarning {
            level: WarningLevel::Important,
            code: "ddl-implicit-commit",
            message: format!("{} 的 DDL 会隐式提交，无法通过事务回滚。", d.label),
            where_to_show: WarnSite::StructureEditor,
        });
    }
    if d.traits.identity_kind != "autoIncrement" && d.runtime_mode == RuntimeMode::Native {
        v.push(UiWarning {
            level: WarningLevel::Info,
            code: "identity-not-autoincrement",
            message: format!("{} 使用 {} 而非 AUTO_INCREMENT。", d.label, d.traits.identity_kind),
            where_to_show: WarnSite::Transfer,
        });
    }
    if !d.traits.sequence_objects {
        v.push(UiWarning {
            level: WarningLevel::Info,
            code: "no-sequence",
            message: format!("{} 没有独立序列对象。", d.label),
            where_to_show: WarnSite::Sidebar,
        });
    }
    v
}

// ─────────────────────────────────────────────────────────────
// 传输家族
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FamilyDescriptor {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
    #[serde(rename = "supportsTransactionalDdl")]
    pub supports_transactional_ddl: bool,
    #[serde(rename = "identityKeyword")]
    pub identity_keyword: String,
    #[serde(rename = "quoteIdent")]
    pub quote_ident: String,
    #[serde(rename = "typeMapping")]
    pub type_mapping: serde_json::Value,
}

pub fn all_families() -> &'static [FamilyDescriptor] {
    use std::sync::OnceLock;
    static CACHE: OnceLock<Vec<FamilyDescriptor>> = OnceLock::new();
    CACHE.get_or_init(|| {
        serde_json::from_str(TRANSFER_FAMILIES_JSON).expect("embedded families must parse")
    })
}

/// ⭐ 类型映射解析。`lossy = true` 的映射**必须由 UI 显式提示并要求确认**。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MappedType {
    pub target: String,
    pub lossy: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

/// ⭐ 由**连接 id** 找传输家族。注意家族 id ≠ 连接 id
///    （家族 `postgres` 的成员是 `postgresql`，不是 `postgres`）。
pub fn family_of(connection_id: &str) -> Option<&'static FamilyDescriptor> {
    all_families()
        .iter()
        .find(|f| f.members.iter().any(|m| m == connection_id))
}

/// ⭐ 传输映射：`from` 连接上的某类型，迁到 `to` 连接上会变成什么。
///
/// 返回 `None` 表示**没有映射规则**（UI 显示「无映射」，禁止传输）。
pub fn map_type(from: &str, to: &str, source_type: &str) -> Option<MappedType> {
    let f = family_of(from)?;
    let t = family_of(to)?;

    let map = f.type_mapping.as_object()?;
    // ⭐ 大小写不敏感：YAML 里写 NUMBER，UI 传 number 也要命中
    let want = source_type.trim().to_lowercase();
    let rule = map
        .get(&want)
        .or_else(|| map.iter().find(|(k, _)| k.eq_ignore_ascii_case(&want)).map(|(_, v)| v))?;

    // ⭐⭐ 同家族 = 同方言 = **恒等映射**。
    //
    // 之前这里直接去查 `to.<t.id>`，但 `typeMapping.to` 只列了
    // **兄弟家族**（postgres / mysql / sqlite / sqlserver），并没有列家族成员。
    // 于是 `dameng → oracle`（同属 oracle 家族）会返回 `None`，
    // UI 显示成「无映射」——
    // 而 TransferPage 上明明白白写着「源与目标同属 Oracle 兼容家族，
    // 语法高度兼容」。**界面承诺的和后端做的不一致**，
    // 用户会以为这个功能坏了。
    //
    // 同家族成员之间语法高度兼容（这正是家族存在的意义），因此类型原样过去。
    //
    // ⭐ 但必须**先确认这个类型确实存在于家族的声明里**（上面已经查到 `rule`）。
    //    否则「同家族就恒等」会变成「任何乱写的类型名都放行」，
    //    数据库里根本不存在的列名会被当成合法类型传给目标库。
    //    —— 宁可拒绝，不能放行。
    if f.id == t.id {
        return Some(MappedType {
            lossy: false,
            reason: Some(format!("同属 {} 家族，语法兼容，类型原样保留", f.label)),
            target: source_type.to_string(),
        });
    }

    let lossy = rule.get("lossy").and_then(|v| v.as_bool()).unwrap_or(false);
    let target = rule
        .get("to")
        .and_then(|m| m.get(&t.id))
        .and_then(|v| v.as_str())
        .map(str::to_string)?;
    Some(MappedType {
        lossy,
        reason: lossy.then(|| {
            format!("{source_type} → {target} 可能丢失精度或语义")
        }),
        target,
    })
}

// ─────────────────────────────────────────────────────────────
// 通用 DTO
// ─────────────────────────────────────────────────────────────

/// ⭐ 对象身份。所有元数据查询以它为键，从不比较名字字符串。
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ObjectId(pub String);

impl ObjectId {
    pub fn as_str(&self) -> &str { &self.0 }
}

/// ⭐ 危险四级。这是安全体系的地基。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Danger {
    /// 只读查询、元数据探测
    ReadOnly,
    /// 写数据：INSERT/UPDATE/DELETE
    Mutating,
    /// 破坏性或改结构：DROP/TRUNCATE/ALTER/CREATE
    Destructive,
    /// 管理操作：GRANT/KILL/CREATE USER
    Admin,
}

impl Danger {
    /// ⭐ MCP `ToolAnnotations` 由此生成，绝不手写（DBX #12154 的教训）
    pub fn read_only_hint(self) -> bool { self == Self::ReadOnly }
    pub fn destructive_hint(self) -> bool {
        matches!(self, Self::Destructive | Self::Admin)
    }
    /// 是否需要用户确认弹窗
    pub fn requires_confirm(self) -> bool { self != Self::ReadOnly }
    /// 是否需要走审批流
    pub fn requires_approval(self) -> bool {
        matches!(self, Self::Destructive | Self::Admin)
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::ReadOnly => "只读",
            Self::Mutating => "写数据",
            Self::Destructive => "改结构",
            Self::Admin => "管理操作",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ColumnType {
    Text, Number, Bool, Date, DateTime, Json, Binary, Uuid, Enum, Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ColumnDef {
    pub id: String,
    pub name: String,
    pub ty: String,
    pub category: ColumnType,
    pub nullable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default_expr: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comment: Option<String>,
    pub is_pk: bool,
    pub is_fk: bool,
    /// ⭐ 大字段不���主查询，进详情时才拉
    pub is_large: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enum_values: Option<std::collections::BTreeMap<String, String>>,
    pub ord: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexDef {
    pub id: String,
    pub name: String,
    pub unique: bool,
    pub primary: bool,
    pub columns: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub def: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TableDef {
    pub id: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comment: Option<String>,
    pub kind: String,
    pub row_estimate: Option<i64>,
    pub size_bytes: Option<i64>,
    pub columns: Vec<ColumnDef>,
    pub indexes: Vec<IndexDef>,
    pub foreign_keys: Vec<(String, String, String, String)>,
}

impl TableDef {
    pub fn pk(&self) -> Option<&ColumnDef> {
        self.columns.iter().find(|c| c.is_pk)
    }
    /// ⭐ 结果集可编辑的前提：能定位目标表 + 有可靠行身份
    pub fn editable(&self) -> bool { self.pk().is_some() }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SchemaSnapshot {
    pub db_id: String,
    pub taken_at: String,
    pub tables: Vec<TableDef>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TreeNode {
    pub id: String,
    /// ⭐ 父节点 id（OID 等价物）。前端据此组树，
    ///    绝不靠拼接字符串判断父子关系 —— 那会在表名含前缀时错乱。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    pub kind: String,
    pub label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comment: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meta: Option<String>,
    pub has_children: bool,
    pub loaded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub risk_badge: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enum_values: Option<std::collections::BTreeMap<String, String>>,
}

/// ⭐ SQL 错误位置。UI 点击错误即可跳转到出错行列。
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SqlPosition {
    pub line: u32,
    pub column: u32,
    pub offset: usize,
    pub length: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Hint {
    pub kind: String,
    pub label: String,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueryError {
    #[serde(skip)]
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<SqlPosition>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sql_state: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub hints: Vec<Hint>,
    /// ⭐ 会话状态的建议动作
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub suggestions: Vec<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn descriptors_are_sorted_and_unique() {
        let ds = all_descriptors();
        let mut orders: Vec<u32> = ds.iter().map(|d| d.order).collect();
        let n = orders.len();
        orders.sort_unstable();
        orders.dedup();
        assert_eq!(orders.len(), n, "连接 order 必须唯一");
    }

    #[test]
    fn every_descriptor_enables_at_least_one_capability() {
        for d in all_descriptors() {
            if d.capabilities.specialized_surface { continue; }
            assert!(d.capabilities.enabled_count() > 0,
                "{}: 所有能力均为 false", d.id);
        }
    }

    #[test]
    fn danger_generates_correct_mcp_annotations() {
        // ⭐ L2 根治：annotations 永远与 Danger 一致
        assert!(Danger::ReadOnly.read_only_hint());
        assert!(!Danger::ReadOnly.destructive_hint());
        assert!(!Danger::Mutating.read_only_hint());
        assert!(!Danger::Mutating.destructive_hint());
        assert!(Danger::Destructive.destructive_hint());
        assert!(Danger::Admin.destructive_hint());
    }

    #[test]
    fn mysql_warns_about_implicit_ddl_commit() {
        let w = ui_warnings(ConnectionType::MySQL);
        assert!(w.iter().any(|x| x.code == "ddl-implicit-commit"),
            "MySQL 必须警告 DDL 隐式提交");
    }

    #[test]
    fn postgres_does_not_warn_about_ddl() {
        let w = ui_warnings(ConnectionType::Postgres);
        assert!(!w.iter().any(|x| x.code == "ddl-implicit-commit"));
    }

    /// ⭐ 两个入口必须给出**完全一致**的结果。
    ///
    /// HTTP 层按 id 取（连接列表就是按 id 索引的），Rust 层按枚举取。
    /// 一旦有人只改一边，同一个连接在两个页面上提示就会打架 ——
    /// 这条断言是防那个的。
    #[test]
    fn ui_warnings_by_id_matches_enum_entry_point() {
        for d in all_descriptors() {
            let by_id = ui_warnings_by_id(&d.id);
            let Some(t) = ConnectionType::ALL.iter().find(|t| t.as_str() == d.id) else {
                panic!("声明了 {} 但 ConnectionType::ALL 里没有 —— 枚举与 YAML 不同步", d.id);
            };
            let by_enum = ui_warnings(*t);
            let a: Vec<_> = by_id.iter().map(|w| (w.code, w.message.clone())).collect();
            let b: Vec<_> = by_enum.iter().map(|w| (w.code, w.message.clone())).collect();
            assert_eq!(a, b, "{} 的两个警告入口结果不一致", d.id);
        }
    }

    /// ⭐ 每个声明的连接都必须能按 id 取到警告 —— 查不到就是声明层有洞。
    #[test]
    fn every_declared_connection_is_reachable_by_id() {
        for d in all_descriptors() {
            assert!(
                descriptor_by_id(&d.id).is_some(),
                "descriptor_by_id 取不到已声明的连接 {}",
                d.id
            );
        }
        assert!(ui_warnings_by_id("不存在的库").is_empty());
    }

    /// ⭐⭐ 同家族传输必须可用且无损。
///
/// 回归测试：`typeMapping.to` 只列兄弟家族，所以以前 `dameng → oracle`
/// 会返回 `None`，UI 显示「无映射」—— 而 TransferPage 上写着
/// 「源与目标同属 Oracle 兼容家族，语法高度兼容」。界面承诺 ≠ 后端行为。
#[test]
    fn same_family_transfer_is_lossless_identity() {
        // dameng 与 oracle 同属 oracle 家族
        assert_eq!(family_of("dameng").unwrap().id, family_of("oracle").unwrap().id);

        for ty in ["NUMBER", "VARCHAR2", "CLOB", "DATE"] {
            let m = map_type("dameng", "oracle", ty)
                .unwrap_or_else(|| panic!("同家族 {ty} 应有映射，实际返回无映射"));
            assert_eq!(m.target, ty, "同家族应原样传递 {ty}");
            assert!(!m.lossy, "同家族传输不应有损: {ty}");
            assert!(m.reason.is_some(), "应说明原因（同族恒等）: {ty}");
        }
    }

    /// ⭐ 家族内任意两个成员互相传输都必须通 —— 不只是 dameng ↔ oracle。
    #[test]
    fn every_oracle_family_member_pair_maps_losslessly() {
        let members: Vec<String> = all_families()
            .iter()
            .find(|f| f.id == "oracle")
            .expect("应有 oracle 家族")
            .members
            .clone();
        assert!(members.len() >= 2, "oracle 家族应含多个成员，实得 {:?}", members);
        for a in &members {
            for b in &members {
                let m = map_type(a, b, "NUMBER")
                    .unwrap_or_else(|| panic!("{a} → {b} 同家族却无映射"));
                assert!(!m.lossy, "{a} → {b} 被标成有损");
            }
        }
    }

    /// ⭐ 跨家族有损映射**必须**仍然标记为有损 —— 同族优化不能把这条规则改松。
    #[test]
    fn cross_family_lossy_mapping_is_still_flagged() {
        let m = map_type("dameng", "postgresql", "NUMBER").expect("NUMBER 有映射");
        assert!(m.lossy, "跨家族 NUMBER → NUMERIC 必须仍标记为有损");
        assert_eq!(m.target, "NUMERIC(38,10)");
        assert!(m.reason.is_some());
    }

    /// ⭐ 未知类型仍然必须返回 `None`（UI 显示「无映射」，禁止传输）。
    #[test]
    fn unknown_types_are_never_mapped() {
        assert!(map_type("dameng", "postgresql", "完全瞎编的类型").is_none());
        assert!(map_type("不存在的库", "postgresql", "NUMBER").is_none());
        // 同家族分支也不能把未知类型放行 —— 家族相同不代表类型名有效
        assert!(map_type("dameng", "oracle", "完全瞎编的类型").is_none());
    }

    #[test]
    fn transfer_family_lookup_and_type_mapping() {
        let f = family_of("dameng").expect("达梦属于 oracle 家族");
        assert_eq!(f.id, "oracle");

        let m = map_type("dameng", "postgresql", "NUMBER").expect("NUMBER 有映射");
        assert!(m.lossy, "NUMBER 映射应标记为 lossy");
        assert_eq!(m.target, "NUMERIC(38,10)");
        assert!(m.reason.is_some());
    }
}
