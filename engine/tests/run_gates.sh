#!/usr/bin/env bash
# ⭐ 一次性跑完所有门禁。任一层红就整体失败。
#
# ⭐ 为什么分层：这几层各自能抓到别的层抓不到的 bug。
#   cargo test      → 业务逻辑
#   sql_lexer       → SQL 词法内核（纯函数，不需要浏览器与后端）
#   e2e_contract   → HTTP 契约（DTO 字段名、状态码、错误信息）
#   smoke_browser  → 页面能不能打开、导航对不对
#   smoke_tree     → 对象树：右键菜单 / 双击打开 / 视图分组 / 收藏 / 只读标记
#   smoke_structure→ 结构页：外键 tab / 约束 tab（真实内省，非摆设）
#   smoke_client   → 分页/排序/筛选是否真的下推给了数据库
#   smoke_edit     → 编辑是否落到正确的那一行
#   smoke_editui   → 撤销重做 + 类型化编辑器
#   smoke_filter   → 列头筛选面板
#   smoke_grid     → 窗口化 + 批量操作 + 行标识一致性
#   smoke_query    → 查询编辑器：高亮 / 跳转 / 多语句 / 执行计划
#   probe_docs     → 文档说的和代码做的对不对得上
#
# 本项目已经抓到 16 个「编译通过 + 单测通过 + API 契约通过，但页面是坏的」
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
# ⭐ 这一层是纯函数单测，秒级完成，所以**不受 SKIP_CARGO 影响** ——
#   它不需要浏览器也不需要后端，跳过它没有任何理由。
run "SQL 词法内核" node --test sql_lexer.test.mjs
run "API 契约"  python3 e2e_contract.py
run "浏览器冒烟" node smoke_browser.mjs
run "对象树"     node smoke_tree.mjs
run "结构页"     node smoke_structure.mjs
run "客户端交互" node smoke_client.mjs
run "编辑闭环"   node smoke_edit.mjs
run "编辑交互"   node smoke_editui.mjs
run "筛选面板"   node smoke_filter.mjs
run "表格内核"   node smoke_grid.mjs
run "查询编辑器" node smoke_query.mjs
# ⭐ 这一层查的是**文档说的和代码做的对不对得上**。
#   文档漂移是这个项目最常见的问题（历史上 NEXT.md / GUI-GAPS.md
#   都被开发进度甩在后面，还照着"未做"来指导决策）。
#   所以文档里写进去的每条 ✅，都要有一条断言兜着。
run "文档一致性" node probe_docs.mjs

echo ""
if [ "$fail" != "0" ]; then
  echo "✗ 有门禁未通过"
  exit 1
fi
echo "✓ 全部门禁通过"