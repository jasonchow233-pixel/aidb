#!/usr/bin/env bash
# ⭐ 一次性跑完所有门禁。任一层红就整体失败。
#
# ⭐ 为什么分层：这几层各自能抓到别的层抓不到的 bug。
#   cargo test      → 业务逻辑
#   e2e_contract   → HTTP 契约（DTO 字段名、状态码、错误信息）
#   smoke_browser  → 页面能不能打开、导航对不对
#   smoke_client   → 分页/排序/筛选是否真的下推给了数据库
#   smoke_edit     → 编辑是否落到正确的那一行
#   smoke_filter   → 列头筛选面板
#   smoke_grid     → 窗口化 + 批量操作 + 行标识一致性
#
# 本项目已经抓到 8 个「编译通过 + 单测通过 + API 契约通过，但页面是坏的」
# 的 bug —— 所以这几层一个都不能少。
set -uo pipefail
cd "$(dirname "$0")"

fail=0
run() {
  local name="$1"; shift
  echo ""
  echo "════════════ $name ════════════"
  if "$@"; then
    :
  else
    echo "  ✗ $name 失败"
    fail=1
  fi
}

if [ "${SKIP_CARGO:-0}" != "1" ]; then
  run "Rust 单测" cargo test --manifest-path ../Cargo.toml --workspace --quiet
fi
run "API 契约"  python3 e2e_contract.py
run "浏览器冒烟" node smoke_browser.mjs
run "客户端交互" node smoke_client.mjs
run "编辑闭环"   node smoke_edit.mjs
run "编辑交互"   node smoke_editui.mjs
run "筛选面板"   node smoke_filter.mjs
run "表格内核"   node smoke_grid.mjs

echo ""
if [ "$fail" != "0" ]; then
  echo "✗ 有门禁未通过"
  exit 1
fi
echo "✓ 全部门禁通过"