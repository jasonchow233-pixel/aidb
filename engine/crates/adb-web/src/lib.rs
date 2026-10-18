//! `adb-web` —— axum 开发后端。
//!
//! ⭐ 这一层存在的意义：**UI 与 Rust 内核之间的契约**。
//! 桌面端（Tauri）会走同样的 Command 契约，只是 transport 不同。
//! dev 模式先跑 HTTP，避免每次改 Rust 都要重编 Tauri。

use std::collections::BTreeMap;
use std::sync::Arc;

use adb_drivers::{
    analyze, demo, sqlite::tree_nodes, Cursor, Depth, Driver, QueryOptions, Session,
};
use adb_types::{all_descriptors, descriptor, family_of, ui_warnings, Danger};
use axum::{
    extract::{Query as AxQuery, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use tower_http::cors::CorsLayer;

pub type AppState = Arc<Mutex<Box<dyn Session>>>;
use std::sync::Mutex;


/// ⭐ rusqlite 是同步的，所有访问都放进 `spawn_blocking`。
///    这同时保证了 axum 的 handler future 是 Send（持 MutexGuard 跨 await 会被拒绝）。
async fn with_session<T, F>(st: AppState, f: F) -> std::result::Result<T, adb_drivers::AdbError>
where
    T: Send + 'static,
    F: FnOnce(&dyn adb_drivers::Session) -> adb_drivers::Result<T> + Send + 'static,
{
    tokio::task::spawn_blocking(move || {
        let g = st.lock().unwrap_or_else(|e| e.into_inner());
        f(g.as_ref())
    })
    .await
    .map_err(|_| adb_drivers::AdbError::Internal("blocking task panicked".into()))?
}

// ─────────────────────────────────────────────────────────────
// 响应类型
// ─────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct Api<T> { ok: bool, data: T }

#[derive(Serialize)]
struct ApiErr { ok: bool, error: serde_json::Value }

#[derive(Serialize)]
struct CapabilitiesDto {
    connections: Vec<serde_json::Value>,
    families: Vec<serde_json::Value>,
    registered: Vec<String>,
    build_report: String,
    /// ⭐ 每个连接的 UI 警告，**全部由声明层 traits 推导**。
    ///    前端不许再写 `if (conn === "mysql")` 去猜提示文案。
    warnings: BTreeMap<String, Vec<WarningDto>>,
}

#[derive(Serialize)]
struct WarningDto {
    level: String,
    code: String,
    message: String,
    #[serde(rename = "whereToShow")]
    where_to_show: String,
}

fn ok_json<T: Serialize>(v: T) -> Response {
    (StatusCode::OK, Json(Api { ok: true, data: v })).into_response()
}

fn err_json(status: StatusCode, kind: &str, message: &str) -> Response {
    (status, Json(ApiErr {
        ok: false,
        error: serde_json::json!({ "kind": kind, "message": message }),
    })).into_response()
}

fn driver_err(e: adb_drivers::AdbError) -> Response {
    let payload = e.to_payload();
    let kind = payload.get("kind").and_then(|v| v.as_str()).unwrap_or("internal").to_string();
    let status = match kind.as_str() {
        "connection" => StatusCode::SERVICE_UNAVAILABLE,
        "permission" => StatusCode::FORBIDDEN,
        "unsupported" => StatusCode::NOT_IMPLEMENTED,
        _ => StatusCode::BAD_REQUEST,
    };
    (status, Json(ApiErr { ok: false, error: payload })).into_response()
}

// ─────────────────────────────────────────────────────────────
// 路由
// ─────────────────────────────────────────────────────────────

pub fn router() -> Router<AppState> {
    // ⭐ 后注册：声明模式驱动（D 阶段将替换为真实驱动实现）
    adb_drivers::register(
        adb_types::ConnectionType::MySQL,
        || Box::new(adb_drivers_mysql::MySqlDriver::new()) as Box<dyn adb_drivers::Driver + 'static>,
    );
    adb_drivers::register(
        adb_types::ConnectionType::Postgres,
        || Box::new(adb_drivers_postgres::PostgresDriver::default()) as Box<dyn adb_drivers::Driver + 'static>,
    );

    Router::new()
        .route("/api/health", get(health))
        .route("/api/capabilities", get(capabilities))
        .route("/api/connections", get(connections))
        .route("/api/session/open", post(open_session))
        .route("/api/schema", get(schema))
        .route("/api/tree", get(tree))
        .route("/api/table", get(table))
        .route("/api/query", post(query))
        .route("/api/query/cancel", post(cancel))
        .route("/api/explain", post(explain))
        .route("/api/execute", post(execute))
        .route("/api/search", get(search))
        .route("/api/diagnostics", get(diagnostics))
        .route("/api/cell", get(cell))
        .route("/api/type-map", get(type_map))
        .layer(CorsLayer::permissive())
}

async fn health() -> Response {
    ok_json(serde_json::json!({ "status": "ok", "version": env!("CARGO_PKG_VERSION") }))
}

/// ⭐ 声明层出口：前端启动时拉一次，渲染连接表单与能力矩阵
async fn capabilities() -> Response {
    let conns: Vec<serde_json::Value> = all_descriptors()
        .iter()
        .map(|d| serde_json::to_value(d).unwrap_or_default())
        .collect();
    ok_json(CapabilitiesDto {
        connections: conns,
        families: adb_types::all_families()
            .iter()
            .map(|f| serde_json::to_value(f).unwrap_or_default())
            .collect(),
        registered: adb_drivers::registered()
            .iter().map(|t| t.as_str().to_string()).collect(),
        build_report: build_report_text(),
        warnings: all_descriptors()
            .iter()
            .map(|d| {
                let ws = adb_types::ui_warnings_by_id(&d.id)
                    .into_iter()
                    .map(|w| WarningDto {
                        level: serde_json::to_value(w.level).unwrap_or_default().as_str().unwrap_or("info").to_string(),
                        code: w.code.to_string(),
                        message: w.message,
                        where_to_show: serde_json::to_value(w.where_to_show).unwrap_or_default().as_str().unwrap_or("sidebar").to_string(),
                    })
                    .collect();
                (d.id.clone(), ws)
            })
            .collect(),
    })
}

fn build_report_text() -> String {
    let mut s = String::from("AgenticDB 声明层构建摘要\n");
    s.push_str(&format!("  编译进当前构建的驱动: {}\n",
        adb_drivers::registered().iter().map(|t| t.as_str()).collect::<Vec<_>>().join(", ")));
    s.push_str("  声明的连接类型:\n");
    for d in all_descriptors() {
        let cap = d.capabilities.enabled_count();
        s.push_str(&format!(
            "    {:<12} {:<8} {:<9} family={:<10} caps={}/15  transfer={}\n",
            d.id,
            match d.runtime_mode { adb_types::RuntimeMode::Native => "native", adb_types::RuntimeMode::Jdbc => "jdbc", adb_types::RuntimeMode::Http => "http" },
            format!("{:?}", d.support_level).to_lowercase(),
            d.transfer_object_family,
            cap,
            match family_of(&d.id) { Some(f) => f.id.clone(), None => "⚠ 无家族".into() },
        ));
        for w in adb_types::ui_warnings_by_id(&d.id) {
            s.push_str(&format!("      ⚠ [{}] {}\n", w.code, w.message));
        }
    }
    s
}

async fn connections() -> Response {
    let list: Vec<serde_json::Value> = adb_types::ConnectionType::ALL
        .iter()
        .map(|t| {
            let d = descriptor(*t);
            serde_json::json!({
                "type": t.as_str(),
                "label": d.label,
                "runtimeMode": d.runtime_mode,
                "compiledIn": adb_drivers::registered().contains(t),
                "defaultPort": d.default_port,
            })
        })
        .collect();
    ok_json(list)
}

#[derive(Deserialize)]
struct OpenReq { #[serde(default)] kind: Option<String> }

async fn open_session(State(_s): State<AppState>, body: Option<Json<OpenReq>>) -> Response {
    let kind = body
        .and_then(|Json(b)| b.kind)
        .unwrap_or_else(|| "sqlite".into());

    let Some(t) = adb_types::ConnectionType::ALL.iter().find(|t| t.as_str() == kind) else {
        return err_json(StatusCode::BAD_REQUEST, "unknown", &format!("未知连接类型: {kind}"));
    };
    let driver: Box<dyn Driver> = match adb_drivers::create(*t) {
        Ok(d) => d,
        Err(e) => return err_json(StatusCode::NOT_IMPLEMENTED, "not_compiled", &e.to_string()),
    };
    let session: Box<dyn Session> = match driver.open().await {
        Ok(s) => s,
        Err(e) => return driver_err(e),
    };
    let snap = session.snapshot();
    let d = descriptor(*t);

    ok_json(serde_json::json!({
        "kind": t.as_str(),
        "label": d.label,
        "descriptor": d,
        "warnings": ui_warnings(*t),
        "dbFile": snap.db_id,
        "tables": snap.tables.len(),
    }))
}

async fn schema(State(s): State<AppState>) -> Response {
    match with_session(s, |sess| Ok(sess.snapshot())).await {
        Ok(snap) => ok_json(&*snap),
        Err(e) => driver_err(e),
    }
}

#[derive(Deserialize)]
struct TreeReq { #[serde(default)] parent: Option<String>, #[serde(default)] depth: Option<String> }

async fn tree(State(s): State<AppState>, AxQuery(q): AxQuery<TreeReq>) -> Response {
    let depth = match q.depth.as_deref() {
        Some("structure") => Depth::Structure,
        Some("detail") => Depth::Detail,
        _ => Depth::Name,
    };
    let parent = q.parent.clone();
    match with_session(s, move |sess| {
        Ok(tree_nodes(&sess.snapshot(), parent.as_deref(), depth))
    })
    .await
    {
        Ok(nodes) => ok_json(nodes),
        Err(e) => driver_err(e),
    }
}

#[derive(Deserialize)]
struct TableReq { name: String }

async fn table(State(s): State<AppState>, AxQuery(q): AxQuery<TableReq>) -> Response {
    let name = q.name.clone();
    match with_session(s, move |sess| {
        let snap = sess.snapshot();
        let Some(t) = snap.tables.iter().find(|t| t.name == name).cloned() else {
            return Err(adb_drivers::AdbError::Unsupported(format!("表 {name} 不存在")));
        };
        let findings: Vec<serde_json::Value> = demo::diagnostics(&t).iter().map(|f| serde_json::json!({
            "level": f.level, "title": f.title, "impact": f.impact, "action": f.action,
        })).collect();
        Ok(serde_json::json!({
            "table": t,
            "diagnostics": findings,
            "ddl": format!("-- DDL 由结构编辑器生成（此处为预览）\n-- 表 {} 共 {} 列", name, t.columns.len()),
        }))
    })
    .await
    {
        Ok(v) => ok_json(v),
        Err(e) if matches!(e, adb_drivers::AdbError::Unsupported(_)) => {
            err_json(StatusCode::NOT_FOUND, "not_found", &e.to_string())
        }
        Err(e) => driver_err(e),
    }
}

#[derive(Deserialize)]
struct QueryReq {
    sql: String,
    /// ⭐ 响应里所有字段都是 camelCase（`dbFile` / `rowEstimate` /
    ///    `requiresConfirm`）。请求侧也必须一致，否则按约定写 `maxRows`
    ///    的客户端会被 `#[serde(default)]` 静默兜底成 1000 行 ——
    ///    不报错，只是多拉 100 倍数据，用户完全看不出来。
    ///    两个名字都收，让旧调用方（`max_rows`）继续可用。
    #[serde(default, alias = "maxRows")]
    max_rows: Option<usize>,
    #[serde(default)]
    offset: Option<i64>,
}

async fn query(State(s): State<AppState>, Json(req): Json<QueryReq>) -> Response {
    // ⭐ Danger 由 SQL 分析得出，不接受前端传入 —— 否则权限判断可被绕过
    let danger = analyze(&req.sql);
    let opt = QueryOptions {
        max_rows: req.max_rows.unwrap_or(1000).clamp(1, 100_000),
        cursor: req.offset.filter(|o| *o > 0).map(Cursor),
        mode: adb_drivers::ReadMode::Paged,
    };
    let result = with_session(s, move |sess| {
        sess.query_blocking(&req.sql, opt, danger)
    })
    .await;

    match result {
        Ok(page) => ok_json(serde_json::json!({
            "danger": danger,
            "dangerLabel": danger.label(),
            "readOnlyHint": danger.read_only_hint(),
            "destructiveHint": danger.destructive_hint(),
            "requiresConfirm": danger.requires_confirm(),
            "requiresApproval": danger.requires_approval(),
            "page": serde_json::to_value(&page).unwrap_or_default(),
        })),
        Err(e) => driver_err(e),
    }
}

async fn cancel(State(s): State<AppState>) -> Response {
    match with_session(s, |sess| sess.cancel_blocking()).await {
        Ok(()) => ok_json(serde_json::json!({ "cancelled": true })),
        Err(e) => driver_err(e),
    }
}

/// ⭐⭐ 执行计划（EXPLAIN）。
///
/// 与 `/api/query` 的区别只有一处，但那一处是关键：
/// `query` 会把语句包进 `SELECT * FROM (…) LIMIT n OFFSET m` 做服务端分页，
/// 而 `EXPLAIN QUERY PLAN SELECT …` 被这么一包就成了
/// `SELECT * FROM (EXPLAIN QUERY PLAN SELECT …)` → `near "PLAN": syntax error`。
/// 用户会看到一条**自己没写错**的语法错误，成本极高。所以走独立方法。
#[derive(Deserialize)]
struct ExplainReq { sql: String }

async fn explain(State(s): State<AppState>, Json(req): Json<ExplainReq>) -> Response {
    let result = with_session(s, move |sess| sess.explain_blocking(&req.sql)).await;
    match result {
        Ok(page) => ok_json(serde_json::json!({
            "page": serde_json::to_value(&page).unwrap_or_default(),
        })),
        Err(e) => driver_err(e),
    }
}

/// ⭐ 真实写入（落库）。
///
/// `/api/query` 对写语句是 **dry-run**：事务里跑一遍就回滚，永远不落盘。
/// 数据网格的「编辑并保存」需要一个真正落库的通道，就是这里。
///
/// ⭐ 三重防护，缺一不可：
///   1. `analyze()` 在服务端算危险级别，**不接受前端传入**
///   2. `require_confirm` 必须为 `true` —— 证明前端已经让用户确认过
///   3. 驱动层再判一次，destructive 直接拒绝（必须走变更管控）
/// ⭐ 与 `QueryReq` 同理：请求字段也必须 camelCase。
///    没有 `rename_all` 时 `requireConfirm` 绑定不上，`#[serde(default)]`
///    会静默取 `false` —— 于是**每一次真实编辑都被 428 拒绝**，
///    而报错信息说的是「请先确认」，极具误导性。
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExecuteReq {
    sql: String,
    /// ⭐ 前端必须显式声明「用户已确认」。缺这个字段直接 428。
    ///
    /// 这样「忘记弹确认框」会变成一个**响亮的失败**，
    /// 而不是让用户的生产库被静默改掉。
    #[serde(default)]
    require_confirm: bool,
}

async fn execute(State(s): State<AppState>, Json(req): Json<ExecuteReq>) -> Response {
    // 1) 服务端分级，不信前端
    let danger = analyze(&req.sql);

    if danger == Danger::ReadOnly {
        return ok_json(serde_json::json!({
            "applied": false,
            "danger": danger,
            "dangerLabel": danger.label(),
            "reason": "只读语句，无需落库",
        }));
    }

    // 2) 必须经过用户确认
    if !req.require_confirm {
        return err_json(
            StatusCode::PRECONDITION_REQUIRED,
            "confirm_required",
            &format!(
                "该语句为 {} 级写操作，必须先向用户展示并确认",
                danger.label()
            ),
        );
    }

    // 3) 驱动层再判一次（纵深防御）
    if danger.requires_approval() {
        return err_json(
            StatusCode::FORBIDDEN,
            "approval_required",
            &format!(
                "{} 级操作必须走变更管控流程，不能从数据网格直接执行",
                danger.label()
            ),
        );
    }

    let sql = req.sql.clone();
    match with_session(s, move |sess| sess.execute_blocking(&sql, danger)).await {
        Ok(affected) => ok_json(serde_json::json!({
            "applied": true,
            "danger": danger,
            "dangerLabel": danger.label(),
            "affectedRows": affected,
        })),
        Err(e) => driver_err(e),
    }
}

/// ⭐ 本地搜索：拼音首字母 + 注释 + 标识符。走 SQLite FTS 是后端优化，
///    这里先做等价的内存实现，行为一致。
#[derive(Deserialize)]
struct SearchReq { q: String }

async fn search(State(s): State<AppState>, AxQuery(q): AxQuery<SearchReq>) -> Response {
    let term = q.q.trim().to_lowercase();
    if term.is_empty() { return ok_json(Vec::<serde_json::Value>::new()); }

    let result = with_session(s, move |sess| {
        let snap = sess.snapshot();
        let mut out = Vec::new();

    for t in &snap.tables {
        let lower = term.to_lowercase();
        if t.name.to_lowercase().contains(&lower)
            || t.comment.as_deref().unwrap_or("").to_lowercase().contains(&lower)
            || pinyin_prefix(&t.name).contains(&lower)
        {
            out.push(serde_json::json!({
                "kind": "table", "id": t.id, "label": t.name,
                "comment": t.comment, "rows": t.row_estimate,
            }));
        }
        for c in &t.columns {
            if c.name.to_lowercase().contains(&term)
                || c.comment.as_deref().unwrap_or("").to_lowercase().contains(&term)
            {
                out.push(serde_json::json!({
                    "kind": "column", "id": c.id, "label": c.name,
                    "comment": c.comment, "table": t.name,
                }));
            }
        }
        if out.len() >= 200 { break; }
        }
        Ok(out)
    })
    .await;
    match result {
        Ok(v) => ok_json(v),
        Err(e) => driver_err(e),
    }
}

/// 拼音首字母（够用的最小实现：中文取首字，英文取首字母）
fn pinyin_prefix(s: &str) -> String {
    s.chars()
        .filter_map(|c| {
            if c.is_ascii_alphabetic() { Some(c.to_ascii_lowercase()) }
            else if ('\u{4e00}'..='\u{9fff}').contains(&c) { Some(c) }
            else { None }
        })
        .collect()
}

async fn diagnostics(State(s): State<AppState>) -> Response {
    let result = with_session(s, |sess| {
        let snap = sess.snapshot();
        let mut items = Vec::new();
        for t in &snap.tables {
            for f in demo::diagnostics(t) {
                items.push(serde_json::json!({
                    "table": t.name, "level": f.level, "title": f.title,
                    "impact": f.impact, "action": f.action,
                }));
            }
        }
        Ok(items)
    })
    .await;
    match result {
        Ok(v) => ok_json(v),
        Err(e) => driver_err(e),
    }
}

#[derive(Deserialize)]
struct CellReq {
    table: String,
    row: i64,
    #[serde(default)] column: String,
    #[serde(default)] offset: usize,
    #[serde(default)] limit: usize,
}

/// ⭐ 大字段延迟读取：主查询不取全文（UI-SPEC §2.2）
async fn cell(State(s): State<AppState>, AxQuery(q): AxQuery<CellReq>) -> Response {
    let limit = if q.limit == 0 { 2000 } else { q.limit.min(100_000) };
    let sql = format!(
        "SELECT \"{}\" FROM \"{}\" ORDER BY rowid LIMIT 1 OFFSET {}",
        q.column, q.table, q.row
    );
    let result = with_session(s, move |sess| {
        sess.query_blocking(&sql, QueryOptions { max_rows: 1, cursor: None, mode: adb_drivers::ReadMode::Paged }, Danger::ReadOnly)
    })
    .await;
    match result {
        Ok(page) => {
            let text: String = page
                .rows
                .first()
                .and_then(|r| r.get(&q.column))
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string();
            let full: String = text.chars().skip(q.offset).take(limit).collect();
            let total_len = text.chars().count();
            ok_json(serde_json::json!({
                "text": full,
                "totalLen": total_len,
                "truncated": q.offset + limit < total_len,
                "nextOffset": if q.offset + limit < total_len { Some(q.offset + limit) } else { None },
            }))
        }
        Err(e) => driver_err(e),
    }
}

#[derive(Deserialize)]
struct TypeMapReq { from: String, to: String, #[serde(default)] types: String }

/// ⭐ 传输类型映射：`lossy: true` 的必须由 UI 显式确认
async fn type_map(AxQuery(q): AxQuery<TypeMapReq>) -> Response {
    let mut out = Vec::new();
    for t in q.types.split(',').map(str::trim).filter(|s| !s.is_empty()) {
        let m = adb_types::map_type(&q.from, &q.to, t);
        out.push(serde_json::json!({
            "source": t,
            "mapped": m.is_some(),
            "target": m.as_ref().map(|m| m.target.clone()),
            "lossy": m.as_ref().map(|m| m.lossy).unwrap_or(true),
            "reason": m.and_then(|m| m.reason),
        }));
    }
    ok_json(serde_json::json!({
        "from": q.from, "to": q.to,
        "fromFamily": family_of(&q.from).map(|f| f.label.clone()),
        "toFamily": family_of(&q.to).map(|f| f.label.clone()),
        "mappings": out,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// ⭐ 回归：`requireConfirm` 必须能被反序列化。
    ///
    /// 之前实测「写语句 + requireConfirm=true」仍然返回 confirm_required，
    /// 说明字段根本没绑定上 —— 而这会让**所有真实编辑都无法落库**。
    #[test]
    fn execute_req_binds_require_confirm() {
        let r: ExecuteReq = serde_json::from_str(
            r#"{"sql":"UPDATE t SET a=1 WHERE id=1","requireConfirm":true}"#,
        )
        .expect("应能反序列化");
        assert!(r.require_confirm, "requireConfirm=true 必须绑定为 true");

        let r2: ExecuteReq =
            serde_json::from_str(r#"{"sql":"SELECT 1","requireConfirm":false}"#).unwrap();
        assert!(!r2.require_confirm, "requireConfirm=false 必须绑定为 false");

        let r3: ExecuteReq = serde_json::from_str(r#"{"sql":"SELECT 1"}"#).unwrap();
        assert!(!r3.require_confirm, "缺字段时应默认 false（安全侧）");
    }
}
