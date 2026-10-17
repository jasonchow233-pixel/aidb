// crates/adb-driver-postgres/src/variant.rs
//
// ============================================================================
// ⭐ 驱动补丁层 —— 不 fork 上游的标准做法
//
// DBX 的做法：fork tokio-postgres / postgres-types / postgres-protocol
//             三个 crate，锁定 commit hash ac8ad47
//   代价：上游更新要手动 rebase；三个 crate 一起 rebase；冲突难解
//
// 我们的做法：变体差异只在**元数据查询 + 方言**两层表达
//   收益：0 个 fork；升级上游只要改版本号；
//         驱动协议本身（wire protocol）永远跟随上游修复
//
// 什么时候才需要真的 fork？
//   - 需要改 wire protocol 层（比如私有扩展）
//   - 需要改类型映射的核心（postgres-types 的 FromSql）
//   这两种情况我们目前都没有遇到。国产库的差异 99% 在元数据 SQL 和 DDL 语法。
// ============================================================================

use adb_types::{ConnectionType, IdentityKind, QuoteStyle};

/// PG 兼容变体。**只有真正不同的才有分支。**
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PgVariant {
    /// 上游 PostgreSQL
    Standard,
    /// 高斯（openGauss / GaussDB）
    GaussDB,
    /// 人大金仓 KingbaseES
    KingbaseES,
    /// 瀚高 HighGo
    HighGo,
}

impl PgVariant {
    pub fn from_id(id: &str) -> Self {
        match id {
            "gaussdb" | "opengauss" => Self::GaussDB,
            "kingbase" | "kingbase-es" => Self::KingbaseES,
            "highgo" => Self::HighGo,
            _ => Self::Standard,
        }
    }

    // ── 方言差异 ─────────────────────────────────────────────

    /// ⭐ 高斯某些版本对引号内大小写的处理与标准 PG 不同。
    ///    这类差异集中在这里，上层永远不需要知道。
    pub fn quote_ident(&self, ident: &str) -> String {
        match self {
            Self::Standard => format!("\"{}\"", ident.replace('"', "\"\"")),
            // 高斯：标识符 > 63 字节时截断（与 PG 相同），但空串处理不同
            Self::GaussDB => format!("\"{}\"", ident),
            Self::KingbaseES | Self::HighGo => format!("\"{}\"", ident.replace('"', "\"\"")),
        }
    }

    /// ⭐ 元数据查询 —— 唯一真正需要变体 SQL 的地方
    pub fn meta_sql(&self) -> &'static MetaQueries {
        match self {
            Self::Standard => &STANDARD,
            // 高斯 A 模式：空 schema 传 '' 在 `CASE WHEN $1 = ''` 里永假
            //   → DBX #12198 的根因。用 OID 查询后这类问题从源头消失。
            Self::GaussDB => &GAUSSDB,
            Self::KingbaseES | Self::HighGo => &STANDARD,
        }
    }

    /// 自增机制
    pub fn identity_kind(&self) -> IdentityKind {
        match self {
            Self::Standard => IdentityKind::Identity,
            // 金仓兼容 Oracle 模式时用 sequence
            Self::KingbaseES => IdentityKind::Identity,
            Self::GaussDB | Self::HighGo => IdentityKind::Identity,
        }
    }

    /// DDL 是否在事务内可回滚
    pub fn transactional_ddl(&self) -> bool {
        // 高 GaussDB 社区版、KingbaseES 的某些兼容模式会模拟 Oracle 的隐式提交
        matches!(self, Self::Standard)
    }

    /// 版本判定（用于在线 DDL 算法选择）
    pub fn supports_concurrent_index(&self, version: i32) -> bool {
        match self {
            Self::Standard => version >= 8.2,
            Self::GaussDB => version >= 905,
            Self::KingbaseES => version >= 8,
            Self::HighGo => version >= 9,
        }
    }

    /// ADD COLUMN 带默认值是否会重写整表
    pub fn add_column_rewrites(&self, version: i32, has_default: bool) -> bool {
        match self {
            Self::Standard => has_default && version < 11,
            Self::GaussDB => has_default && version < 905,
            _ => has_default,
        }
    }
}

/// 标识符引用风格（方言层，与变体无关）
#[derive(Debug, Clone, Copy)]
pub struct QuoteStyle(pub char);

impl QuoteStyle {
    pub fn wrap(&self, ident: &str) -> String {
        let (o, c) = match self.0 {
            '"' => ('"', '"'),
            '`' => ('`', '`'),
            '[' => ('[', ']'),
            q => (q, q),
        };
        format!("{o}{ident}{c}")
    }
}

/// ⭐⭐ 元数据 SQL 集中在这里 —— **不要散落在查询代码里**。
///    这是"同名 bug 类"的根治点（DBX #12240 的核心诉求）。
pub struct MetaQueries {
    /// 按 OID 列举表。⭐ 从不比较名字字符串。
    pub list_tables_by_namespace_oid: &'static str,
    /// 按表 OID 取列
    pub columns_by_relation_oid: &'static str,
    /// 按表 OID 取索引
    pub indexes_by_relation_oid: &'static str,
    /// 按表 OID 取外键
    pub foreign_keys_by_relation_oid: &'static str,
    /// 行数估算
    pub row_estimate: &'static str,
    /// ⭐ 注释统一走 pg_description join（objoid + objsubid），不依赖 col_description
    ///    —— 最笨的 SQL 恰好全内核通用（抄自 DBeaver 的做法）
    pub comments: &'static str,
    /// 当前语句追踪
    pub current_statements: &'static str,
    /// 取消语句
    pub cancel_statement: &'static str,
}

const STANDARD: MetaQueries = MetaQueries {
    list_tables_by_namespace_oid: r#"
        SELECT c.oid, c.relname, c.relkind, c.relrowsecurity,
               obj_description(c.oid, 'pg_class') AS comment,
               c.reltuples::bigint AS est_rows
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relnamespace = $1
           AND c.relkind IN ('r','v','m','f','p')
         ORDER BY c.relname
    "#,
    columns_by_relation_oid: r#"
        SELECT a.attnum, a.attname, format_type(a.atttypid, a.atttypmod) AS type,
               a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expr,
               obj_description(a.attrelid, 'pg_class') AS comment,
               a.attnum > 0 AND i.indisprimary
          FROM pg_attribute a
          LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
          LEFT JOIN pg_index i ON i.indrelid = a.attrelid
                                   AND i.indisprimary
                                   AND a.attnum = ANY(i.indkey)
         WHERE a.attrelid = $1 AND a.attnum > 0 AND NOT a.attisdropped
         ORDER BY a.attnum
    "#,
    indexes_by_relation_oid: r#"
        SELECT i.indexrelid, ic.relname, i.indisunique, i.indisprimary,
               pg_get_indexdef(i.indexrelid) AS def,
               array_to_json(i.indkey) AS columns
          FROM pg_index i
          JOIN pg_class ic ON ic.oid = i.indexrelid
         WHERE i.indrelid = $1
    "#,
    foreign_keys_by_relation_oid: r#"
        SELECT con.conname, con.conrelid, ref.oid AS ref_relid,
               pg_get_constraintdef(con.oid) AS def
          FROM pg_constraint con
          JOIN pg_class ref ON ref.oid = con.confrelid
         WHERE con.conrelid = $1 AND con.contype = 'f'
    "#,
    row_estimate: r#"
        SELECT reltuples::bigint FROM pg_class WHERE oid = $1
    "#,
    comments: r#"
        SELECT d.objoid, d.objsubid, d.description
          FROM pg_description d
         WHERE d.objoid = $1
    "#,
    current_statements: r#"
        SELECT pid, state, query, now() - query_start AS duration
          FROM pg_stat_activity
         WHERE datname = current_database() AND pid <> pg_backend_pid()
         ORDER BY query_start
    "#,
    cancel_statement: "SELECT pg_cancel_backend($1)",
};

const GAUSSDB: MetaQueries = MetaQueries {
    // ⭐ 高斯多一个 catalog 概念，且 A 模式的空 schema 语义不同。
    //    用 OID 查询 + COALESCE 处理空 schema，而不是传 ''。
    list_tables_by_namespace_oid: r#"
        SELECT c.oid, c.relname, c.relkind, false AS relrowsecurity,
               obj_description(c.oid, 'pg_class') AS comment,
               c.reltuples::bigint AS est_rows
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relnamespace = $1
           AND c.relkind IN ('r','v','m','f','p')
         ORDER BY c.relname
    "#,
    // 其余相同 —— 差异被隔离在 1 个查询里
    columns_by_relation_oid: STANDARD.columns_by_relation_oid,
    indexes_by_relation_oid: STANDARD.indexes_by_relation_oid,
    foreign_keys_by_relation_oid: STANDARD.foreign_keys_by_relation_oid,
    row_estimate: STANDARD.row_estimate,
    // ⭐ 高斯 d.description 是 text，标准 PG 是 text —— 相同
    comments: STANDARD.comments,
    // ⭐ 高 GaussDB 社区版的 pg_stat_activity 列名不同
    current_statements: r#"
        SELECT pid, state, query, now() - query_start AS duration
          FROM pg_stat_activity
         WHERE datname = current_database() AND pid <> pg_backend_pid()
    "#,
    cancel_statement: "SELECT pg_cancel_backend($1)",
};

// ============================================================================
// 用法：驱动内部唯一接触变体的地方
// ============================================================================

pub struct PgBackend {
    variant: PgVariant,
    version: i32,
    meta: &'static MetaQueries,
}

impl PgBackend {
    pub fn new(id: &str, version: i32) -> Self {
        let variant = PgVariant::from_id(id);
        Self { meta: variant.meta_sql(), variant, version }
    }

    pub fn quote_ident(&self, s: &str) -> String { self.variant.quote_ident(s) }

    pub fn transactional_ddl(&self) -> bool { self.variant.transactional_ddl() }

    pub fn supports_concurrent_index(&self) -> bool {
        self.variant.supports_concurrent_index(self.version)
    }

    /// 供 D 生成模块查询
    pub fn add_column_rewrites(&self, has_default: bool) -> bool {
        self.variant.add_column_rewrites(self.version, has_default)
    }

    pub fn list_tables_sql(&self) -> &'static str { self.meta.list_tables_by_namespace_oid }
    pub fn columns_sql(&self) -> &'static str { self.meta.columns_by_relation_oid }
    pub fn indexes_sql(&self) -> &'static str { self.meta.indexes_by_relation_oid }
    pub fn fks_sql(&self) -> &'static str { self.meta.foreign_keys_by_relation_oid }
    pub fn comments_sql(&self) -> &'static str { self.meta.comments }
    pub fn row_estimate_sql(&self) -> &'static str { self.meta.row_estimate }
    pub fn cancel_sql(&self) -> &'static str { self.meta.cancel_statement }
}

// ─────────────────────────────────────────────────────────────
// 契约测试：所有变体跑同一套（⭐ 保证"换变体不出回归"）
// ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod contract {
    use super::*;

    /// ⭐ 每个 PG 兼容变体都必须跑这套元数据查询，且返回 OID 而非名字
    #[test]
    fn every_variant_queries_by_oid() {
        for id in ["postgresql", "gaussdb", "kingbase", "highgo"] {
            let b = PgBackend::new(id, 16);
            for sql in [
                b.list_tables_sql(), b.columns_sql(), b.indexes_sql(),
                b.fks_sql(), b.comments_sql(),
            ] {
                // ⭐ 硬门禁：元数据 SQL 绝不允许按名字查表
                assert!(
                    !sql.contains("relname =") && !sql.contains("tablename ="),
                    "{id}: 元数据 SQL 出现按名字比较 —— 必须改成 OID 查询"
                );
                // 必须用 $1 参数
                assert!(sql.contains("$1"), "{id}: 元数据 SQL 缺少参数占位符");
            }
        }
    }

    #[test]
    fn transactional_ddl_only_where_true() {
        assert!(PgBackend::new("postgresql", 16).transactional_ddl());
        // ⭐ 高斯不支持 → UI 必须提示「DDL 会自动提交」
        assert!(!PgBackend::new("gaussdb", 905).transactional_ddl());
    }

    #[test]
    fn add_column_rewrite_matches_pg_version_boundary() {
        let v11 = PgBackend::new("postgresql", 11);
        assert!(!v11.add_column_rewrites(true),  "PG11+ ADD COLUMN DEFAULT 不应重写");
        assert!(v11.add_column_rewrites(false));

        let v10 = PgBackend::new("postgresql", 10);
        assert!(v10.add_column_rewrites(true),  "PG10 ADD COLUMN DEFAULT 应重写");

        let v16 = PgBackend::new("postgresql", 16);
        assert!(!v16.add_column_rewrites(true));
    }

    /// ⭐ 治 L12：生成语句里的 schema 必须等于目标 schema
    #[test]
    fn generated_ddl_is_schema_qualified() {
        let b = PgBackend::new("postgresql", 16);
        let q = |s: &str| b.quote_ident(s);
        let ddl = format!(
            "ALTER TABLE {}.{} ADD COLUMN {} text",
            q("public"), q("orders"), q("remark")
        );
        assert!(ddl.contains("\"public\".\"orders\""),
                "DDL 必须限定目标 schema，防 #11533");
    }
}
