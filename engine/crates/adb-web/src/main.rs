use adb_web::router;
use std::sync::{Arc, Mutex};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    // ⭐ 打开演示库（首次运行自动 materialize）
    use adb_drivers::Driver as _;
    let driver = adb_drivers::SqliteDriver::new();
    let session: Box<dyn adb_drivers::Session> = match driver.open().await {
        Ok(s) => s,
        Err(e) => {
            eprintln!("failed to open demo database: {e}");
            std::process::exit(1);
        }
    };
    let snap = session.snapshot();
    println!(
        "AgenticDB dev backend  ·  demo db: {}  ·  {} 张表",
        snap.db_id,
        snap.tables.len()
    );

    let state: adb_web::AppState = Arc::new(Mutex::new(session));
    let app = router().with_state(state);

    let port: u16 = std::env::var("ADB_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8787);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await.expect("bind");
    println!("  → http://127.0.0.1:{port}");
    axum::serve(listener, app).await.expect("serve");
}
