#!/usr/bin/env python3
"""AgenticDB 端到端契约验收：把 UI 会走的每条路径都跑一遍。

用法：
    # 1) 手动起后端
    cargo run -p adb-web
    # 2) 跑验收
    python3 engine/tests/e2e_contract.py

    # 或者一步到位（自己起后端、用临时数据目录、跑完就收）
    python3 engine/tests/e2e_contract.py --autostart

退出码：0 = 全部通过，1 = 有失败项。
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("ADB_BASE", "http://127.0.0.1:8787")
ok_count = 0
fail_count = 0


def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.load(r)


def post(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"content-type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def check(label, cond, extra=""):
    global ok_count, fail_count
    if cond:
        ok_count += 1
        print(f"  ok    {label}")
    else:
        fail_count += 1
        print(f"  FAIL  {label}  {extra}")


def wait_ready(timeout=60.0):
    """后端起来到可用的时间取决于首次物化演示库（20+ 万行），给足超时。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/api/health", timeout=2) as r:
                if json.load(r)["ok"]:
                    return True
        except Exception:
            time.sleep(0.4)
    return False


def autostart():
    """用临时数据目录起一个后端，退出时收掉。"""
    engine = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data_dir = tempfile.mkdtemp(prefix="adb-e2e-")
    env = {**os.environ, "ADB_DATA_DIR": data_dir}
    exe = os.path.join(engine, "target", "debug", "adb-web")
    if not os.path.exists(exe):
        print(f"找不到 {exe}\n先执行：cd engine && cargo build -p adb-web")
        sys.exit(2)
    proc = subprocess.Popen(
        [exe], cwd=engine, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    print(f"已启动 adb-web (pid={proc.pid})，演示库目录 {data_dir}")
    if not wait_ready():
        proc.kill()
        print("后端启动超时")
        sys.exit(2)
    return proc


def run_all():
    print("=== 1. 启动 / 声明层 ===")
    caps = get("/api/capabilities")["data"]
    check("capabilities 可拉取", len(caps["connections"]) == 4, f"{len(caps['connections'])}")
    check("传输家族 5 份", len(caps["families"]) == 5, f"{len(caps['families'])}")
    check("已注册驱动 3 个", len(caps["registered"]) == 3, str(caps["registered"]))
    check(
        "警告由后端推导（MySQL 有 DDL 隐式提交警告）",
        any(w["code"] == "ddl-implicit-commit" for w in caps["warnings"]["mysql"]),
    )
    check(
        "PostgreSQL 无 DDL 隐式提交警告",
        not any(w["code"] == "ddl-implicit-commit" for w in caps["warnings"]["postgresql"]),
    )

    print()
    print("=== 2. 会话 ===")
    sess = post("/api/session/open", {"kind": "sqlite"})["data"]
    check("session 打开", sess["kind"] == "sqlite")
    check("元数据表数 > 20", sess["tables"] > 20, str(sess["tables"]))
    check("dbFile 指向本地文件", sess["dbFile"].endswith("demo.db"), sess["dbFile"])
    check("session 带后端警告", isinstance(sess.get("warnings"), list))

    print()
    print("=== 3. 对象树（懒加载）===")
    root = get("/api/tree")["data"]
    check("根节点 = database", len(root) == 1 and root[0]["kind"] == "database")
    db_id = root[0]["id"]
    level1 = get(f"/api/tree?parent={db_id}")["data"]
    names = [n["label"] for n in level1]
    check("一级对象 >= 20", len(level1) >= 20, str(len(level1)))
    check("无 sqlite_ 内部表", not any(n.startswith("sqlite_") for n in names))
    check("无 FTS 影子表", not any(n.endswith(("_data", "_idx", "_content", "_docsize", "_config")) for n in names))
    check("视图可见", "v_order_summary" in names)
    check("FTS 虚拟表可见", "product_search" in names)
    kinds = {n["kind"] for n in level1}
    check("区分 table/view", "view" in kinds, str(kinds))

    tbl = next(n for n in level1 if n["label"] == "orders")
    level2 = get(f"/api/tree?parent={tbl['id']}&depth=structure")["data"]
    check("列节点懒加载", len(level2) > 5, str(len(level2)))
    check("列节点有类型 meta", all("meta" in c for c in level2))
    check("枚举列带取值含义", any(c.get("enumValues") for c in level2))

    print()
    print("=== 4. 表结构 ===")
    t = get("/api/table?name=orders")["data"]["table"]
    check("列数 9", len(t["columns"]) == 9, str(len(t["columns"])))
    check("有主键", any(c["isPk"] for c in t["columns"]))
    check("有外键", any(c["isFk"] for c in t["columns"]))
    check("有中文注释", any(c.get("comment") for c in t["columns"]))
    check("status 有枚举含义", any(c["name"] == "status" and c.get("enumValues") for c in t["columns"]))
    check("行数估算 5000", t.get("rowEstimate") == 5000, str(t.get("rowEstimate")))

    audit = get("/api/table?name=audit_logs")["data"]["table"]
    check("问题表无主键", not any(c["isPk"] for c in audit["columns"]))
    # ⭐ audit_logs 起始 20 万行，另外 5000 行来自 orders 的 AFTER INSERT 触发器
    check(
        "问题表 >= 20 万行",
        audit.get("rowEstimate", 0) >= 200_000,
        str(audit.get("rowEstimate")),
    )

    print()
    print("=== 5. 查询：分页 / total / 危险分级 ===")
    r = post("/api/query", {"sql": "SELECT * FROM orders", "maxRows": 10})["data"]
    check("camelCase maxRows 生效（10 行）", len(r["page"]["rows"]) == 10, str(len(r["page"]["rows"])))
    r = post("/api/query", {"sql": "SELECT * FROM orders", "max_rows": 7})["data"]
    check("snake_case max_rows 仍兼容（7 行）", len(r["page"]["rows"]) == 7, str(len(r["page"]["rows"])))
    check("裸单表给出权威 total=5000", r["page"]["total"] == 5000, str(r["page"]["total"]))
    check("有列元数据（PK 标记）", any(c["isPk"] for c in r["page"]["columns"]))
    check("行号连续起始 1", r["page"]["rows"][0]["id"] == 1)

    r = post(
        "/api/query",
        {"sql": "WITH x AS (SELECT * FROM orders WHERE status=2) SELECT COUNT(*) FROM x", "maxRows": 5},
    )["data"]
    check("CTE 不再撒谎（total=None）", r["page"]["total"] is None, str(r["page"]["total"]))
    check("CTE 结果本身正确", len(r["page"]["rows"]) == 1 and r["page"]["rows"][0]["COUNT(*)"] > 0, str(r["page"]["rows"]))

    r = post("/api/query", {"sql": "SELECT * FROM orders WHERE status=1", "maxRows": 5})["data"]
    check("WHERE 查询不估算 total", r["page"]["total"] is None)

    print()
    print("=== 6. 查询：真实 SQL 能力 ===")
    def page_of(sql, n=5):
        return post("/api/query", {"sql": sql, "maxRows": n})["data"]["page"]

    def rows_of(sql, n=5):
        return page_of(sql, n)["rows"]

    check("JOIN", len(rows_of("SELECT o.order_no,u.name FROM orders o JOIN users u ON o.user_id=u.id")) == 5)
    check("GROUP BY 聚合", len(rows_of("SELECT status,COUNT(*) n FROM orders GROUP BY status")) == 5)
    check("窗口函数", len(rows_of("SELECT order_no,RANK() OVER (ORDER BY total_cents DESC) r FROM orders")) == 5)
    check("视图查询", len(rows_of("SELECT * FROM v_order_summary")) == 5)
    check("CTE", len(rows_of("WITH t AS (SELECT * FROM orders LIMIT 3) SELECT * FROM t")) == 3)
    check("JSON 函数", len(rows_of("SELECT json_extract(attributes,'$.color') c FROM products WHERE attributes IS NOT NULL")) == 5)
    check("FTS 中文检索（曾返回 0）", len(rows_of("SELECT * FROM product_search WHERE product_search MATCH '无线'")) > 0)
    check("子查询", len(rows_of("SELECT name FROM users WHERE id IN (SELECT user_id FROM orders LIMIT 10)")) > 0)

    print()
    print("=== 6b. JOIN 列元数据必须归属正确的表（回归）===")
    jc = page_of("SELECT o.order_no, u.name, u.email FROM orders o JOIN users u ON o.user_id=u.id")["columns"]
    by = {c["name"]: c for c in jc}
    check(
        "name 归属 users（不是 categories）",
        by["name"]["comment"] == "姓名（敏感字段）",
        str(by["name"]["comment"]),
    )
    check("order_no 归属 orders", by["order_no"]["comment"] == "订单号（业务唯一）", str(by["order_no"]["comment"]))
    expr = page_of("SELECT total_cents / 100.0 AS yuan FROM orders")["columns"]
    check("计算列标为未知（expr:）", expr[0]["id"].startswith("expr:"), expr[0]["id"])
    check("未知列不给注释", expr[0].get("comment") is None, str(expr[0].get("comment")))

    print()
    print("=== 7. 写操作：dry-run 回滚 ===")
    before = rows_of("SELECT COUNT(*) c FROM orders")[0]["c"]
    post("/api/query", {"sql": "UPDATE orders SET remark='X' WHERE id=1", "maxRows": 1})
    after = rows_of("SELECT COUNT(*) c FROM orders")[0]["c"]
    check("行数未变", before == after, f"{before} -> {after}")
    check("remark 未泄漏", rows_of("SELECT remark FROM orders WHERE id=1")[0]["remark"] != "X")

    print()
    print("=== 8. 诊断 ===")
    d = get("/api/diagnostics")["data"]
    check("诊断出多个问题", len(d) >= 5, str(len(d)))
    check("audit_logs 被报无主键", any("audit_logs" in f.get("table", "") and "主键" in f["title"] for f in d))
    check("视图没被误报", not any(f.get("table") == "v_order_summary" for f in d))
    check("每条都有可执行建议", all(f.get("action") for f in d))
    check("每条都有影响说明", all(f.get("impact") for f in d))

    print()
    print("=== 9. 搜索 ===")
    for term, label in [("订单", "中文注释"), ("order", "英文表名"), ("email", "列名")]:
        hits = get(f"/api/search?q={urllib.parse.quote(term)}")["data"]
        check(f"搜索「{term}」（{label}）", len(hits) > 0, f"{len(hits)} 条")

    print()
    print("=== 10. 类型映射（有损必须显式）===")
    m = get("/api/type-map?from=dameng&to=postgresql&types=NUMBER,VARCHAR2,CLOB")["data"]
    check("dameng → postgresql 有映射", len(m["mappings"]) == 3)
    check("NUMBER 标记为有损", any(x["source"] == "NUMBER" and x["lossy"] for x in m["mappings"]))
    check("有损项给出原因", all(x.get("reason") for x in m["mappings"] if x["lossy"]))
    same = get("/api/type-map?from=dameng&to=oracle&types=NUMBER")["data"]
    # 家族以本地化 label 回传（Oracle 兼容家族），不是 id
    check(
        "同家族映射识别正确",
        same.get("fromFamily") == same.get("toFamily") and bool(same.get("fromFamily")),
        f"{same.get('fromFamily')} -> {same.get('toFamily')}",
    )
    check("同家族映射仍可用", any(x["mapped"] for x in same["mappings"]))

    print()
    print("=== 11. 错误提示必须可行动（不泄漏裸 SQLite 文本）===")
    def err_of(sql):
        r = post("/api/query", {"sql": sql, "maxRows": 5})
        return None if r.get("ok") else r["error"]

    e = err_of("DELETE FROM orders WHERE id=1")
    check("外键失败有中文提示", e and "外键" in e["message"], str(e and e["message"]))
    check("外键失败不泄漏 constraint failed", e and "constraint failed" not in e["message"])
    check("外键失败给得出下一步", e and len(e.get("suggestions") or []) > 0)

    e = err_of("INSERT INTO tags(name,color,sort_order) VALUES('热销','#f00',1)")
    check("唯一失败给出冲突位置", e and "tags.name" in json.dumps(e, ensure_ascii=False), str(e and e["message"]))

    e = err_of("SELECT * FROM oder")
    check("表不存在给相似名", e and any(h["label"] == "orders" for h in e.get("hints") or []))
    check("表不存在有位置", e and e.get("position") is not None, str(e and e.get("position")))

    e = err_of("SELECT nonexistent_col FROM orders")
    col_hint = [h for h in (e.get("hints") or []) if h["kind"] == "column"]
    check("列不存在只报列名", col_hint and col_hint[0]["label"] == "nonexistent_col", str(col_hint))
    check("列提示不重复整条 SQL", col_hint and "SELECT" not in col_hint[0]["label"])

    # ⭐⭐ 回归：列错误的位置之前落在 (1,1)，跳转跳到句首。
    #   SQLite 的原文是 `no such column: X in <整条 SQL> at offset N`，
    #   解析时必须先截掉 ` in ` 之后的后缀。
    #   ⚠ 断言 offset 而非硬编码列号：offset 反查回原文才认得出是不是那个词。
    e = err_of("SELECT nonexistent_col FROM orders")
    pos = e.get("position") or {}
    off = pos.get("offset")
    sql_used = "SELECT nonexistent_col FROM orders"
    check(
        "⭐ 列错误位置落在出错列名上（曾恒为第 1 行第 1 列）",
        isinstance(off, int) and sql_used[off:off + len("nonexistent_col")] == "nonexistent_col",
        f"pos={pos}",
    )
    check("⭐ 列错误长度是列名本身而非整条报错", pos.get("length") == len("nonexistent_col"), str(pos))
    check("⭐ 文案不把整条 SQL 再抄一遍", "SELECT" not in (e.get("message") or ""), str(e.get("message")))
    check("⭐ 文案说清缺的是哪一列", "nonexistent_col" in (e.get("message") or ""), str(e.get("message")))

    print()
    print("=== 12. 执行计划（EXPLAIN）===")
    # ⭐ 必须有独立端点：/api/query 会把语句包进 `SELECT * FROM (…) LIMIT n`，
    #   EXPLAIN QUERY PLAN 被这么一包就成了 `near "PLAN": syntax error`。
    r = post("/api/explain", {"sql": "SELECT id FROM orders WHERE status = 2"})
    check("EXPLAIN 端点可用", r.get("ok"), str(r)[:200])
    if r.get("ok"):
        rows = r["data"]["page"]["rows"]
        check("返回计划步骤", len(rows) > 0, str(len(rows)))
        check("步骤里有 detail 文本", any("detail" in x for x in rows), str(rows[:2]))
        check(
            "⭐ 计划里不回显 EXPLAIN 前缀",
            not any("EXPLAIN" in str(x.get("detail", "")) for x in rows),
            str([x.get("detail") for x in rows]),
        )
        # ⭐ 断言要看**语义**（有没有走索引），不是匹配某个固定字符串 ——
        #   SQLite 实际输出的是 `USING COVERING INDEX`（覆盖索引），
        #   写成 `USING INDEX` 会假失败，而计划本身是对的。
        details = [str(x.get("detail", "")) for x in rows]
        check(
            "⭐ 走了索引而不是全表扫描（status 有索引）",
            any(d.startswith("SEARCH ") and "USING" in d and "INDEX" in d for d in details)
            and not any(d.startswith("SCAN orders") for d in details),
            str(details),
        )

    # ⭐ 执行计划**不给 total**：计划没有「结果集多少行」这回事，
    #   给一个数会被 UI 显示成权威行数，那是撒谎。
    r = post("/api/explain", {"sql": "SELECT * FROM orders"})
    check("⭐ 执行计划不给 total（宁可不写也不给错）",
          r.get("ok") and r["data"]["page"]["total"] is None,
          str(r.get("data", {}).get("page", {}).get("total")))

    # ⭐ 写语句要明确拒绝，不能给一份噪声计划让用户误以为自己的写语句没问题
    r = post("/api/explain", {"sql": "UPDATE orders SET remark='x' WHERE id=1"})
    check("写语句被明确拒绝", not r.get("ok") and "只读查询" in r["error"]["message"], str(r)[:200])

    # ⭐ 坏 SQL 的错误位置也要能回来
    r = post("/api/explain", {"sql": "SELECT nope FROM orderz"})
    check("坏 SQL 报错带位置", not r.get("ok") and r["error"].get("position") is not None, str(r)[:200])
    check("坏 SQL 给相似表名", not r.get("ok") and any(h["label"] == "orders" for h in r["error"].get("hints") or []), str(r)[:200])

    print()
    print(f"通过 {ok_count} / 失败 {fail_count}")
    sys.exit(1 if fail_count else 0)

if __name__ == "__main__":
    _proc = autostart() if "--autostart" in sys.argv else None
    if _proc is None and not wait_ready(timeout=5.0):
        print(f"连不上 {BASE}\n先执行：cd engine && cargo run -p adb-web\n或直接加 --autostart")
        sys.exit(2)
    try:
        run_all()
    finally:
        if _proc:
            _proc.kill()
