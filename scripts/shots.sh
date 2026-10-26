#!/usr/bin/env bash
# 重拍 docs/screenshots/ 的验收截图（18 张）。
#
# 用法：
#   ./scripts/shots.sh              # 全部重拍（亮色 15 张 + 暗色 3 张）
#   ./scripts/shots.sh p2-users     # 只拍指定的一张
#
# 为什么需要这个脚本（NAVICAT-TODO.md §12.2 记录的坑，全部在这里被绕开）：
#
#   坑 1（最贵的一个）—— 不先 install，拍到的是旧界面。
#     javafx:run -pl 不带 -am 时，Maven 从 ~/.m2 取 adb-ui-shell 的已安装 jar。
#     实测踩过：改了半天 CSS，截图里一点没变，因为 jar 还是两小时前的。
#     → 本脚本第一步就是 install，并打印 jar 时间戳供核对。
#
#   坑 2 —— 主题偏好会持久化。暗色必须第一个拍，否则后面全是暗色。
#     → 本脚本固定顺序：p5-dark 最先，其余用 ADB_THEME_FORCE=light 钉住。
#
#   坑 3 —— 主题热切换后 scene.snapshot() 会拿到空白帧。
#     → 一律用 ADB_THEME_FORCE 在启动时定主题，绝不靠点击切换。
#
#   坑 4 —— ADB_SHOTS 必须是绝对路径，相对路径会落到模块工作目录。
#     → 本脚本用 "$PWD/docs/screenshots"。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

SHOTS_DIR="$PROJECT_ROOT/docs/screenshots"
ONLY="${1:-}"

# ── UX-0：可复现性 ────────────────────────────────────────────────
# 密度必须钉住：上一轮实拍里 p2-users 行高是 34（持久化的 comfort），
# 而默认是 28 —— 同一份脚本在不同机器/历史上拍出的图不一样。
# 偏好根也要隔离并在每次启动时清空，否则侧栏宽度 / 上次连接仍会漂移。
# 第二十一轮：默认档位改成 normal（28px），截图跟着钉 normal —— 拍的必须是用户首启看到的样子。
export ADB_DENSITY="${ADB_DENSITY:-normal}"
export ADB_PREFS_NODE="${ADB_PREFS_NODE:-io/agenticdb/screenshots}"
export ADB_PREFS_RESET="${ADB_PREFS_RESET:-1}"
# 不依赖「上次用的连接」；demo 由 ConnectionStore.ensureDemo 保证存在。
export ADB_CONNECTION="${ADB_CONNECTION:-demo}"

# name|click-id|wait-ms|预置页面环境变量
# 顺序即拍摄顺序：暗色必须最先。
SHOTS=(
  "p5-dark|appbar-manage-connections|5000|ADB_THEME_FORCE=dark"
  # ⭐ U23：暗色不能只有一帧 —— 勾选框与禁用态的问题正是在暗色里暴露的。
  #   p2-users-dark 拍数据网格（行首选择框 + 工具栏复选），
  #   p7-new-index-dark 拍索引弹窗（「唯一」复选 + 添加列按钮）。
  #   必须紧跟 p5-dark：主题偏好会持久化，暗色帧全部排在亮色之前。
  "p2-users-dark||5000|ADB_OPEN=users ADB_THEME_FORCE=dark"
  "p7-new-index-dark||6000|ADB_INDEX=1 ADB_INDEX_POSE=1 ADB_THEME_FORCE=dark"
  "p0-shell||2500|ADB_THEME_FORCE=light"
  "p2-users||5000|ADB_OPEN=users ADB_THEME_FORCE=light"
  # ⭐ UX-6：数据页「概览」切面（行数 / 大小 / 引擎 / 索引数 / 最近操作）。
  #   切面标签 id 是动态拼的（object-tab-<id>），不能当静态点击目标；
  #   ADB_OVERVIEW=1 与 ADB_DDL 同型，等页面建好后程序化切换。
  "p2-overview||7000|ADB_OPEN=users ADB_OVERVIEW=1 ADB_THEME_FORCE=light"
  "p2-details||7000|ADB_OPEN=users ADB_DETAILS=1 ADB_THEME_FORCE=light"
  # ⭐ UX-5-F：唯一拍到「交互态」的一帧 —— 选中行 + 光标格 + 抽屉编辑器。
  #   ADB_CELL=1 让数据页自己摆状态，点击真按「编辑」按钮进入编辑态。
  "p2-cell|data-details-edit|9000|ADB_OPEN=users ADB_CELL=1 ADB_THEME_FORCE=light"
  "p6-tables||5000|ADB_TABLES=1 ADB_THEME_FORCE=light"
  "p6-tables-grid||5000|ADB_TABLES=1 ADB_TABLE_VIEW=grid ADB_THEME_FORCE=light"
  "p3-query||6000|ADB_QUERY=1 ADB_THEME_FORCE=light"
  # ⭐ U25：右键菜单帧 —— 结果网格上摆出真菜单（复制单元格 / 行 / 列名）。
  "p3-query-menu||8000|ADB_QUERY=1 ADB_MENU_POSE=1 ADB_THEME_FORCE=light"
  "p4-structure||5000|ADB_STRUCTURE=users ADB_THEME_FORCE=light"
  "p4-ddl||7000|ADB_DDL=1 ADB_THEME_FORCE=light"
  "p5-connections|appbar-manage-connections|4000|ADB_THEME_FORCE=light"
  "p6-new-table||5000|ADB_NEW_TABLE=1 ADB_THEME_FORCE=light"
  # ⭐ §6.4：这一帧要拍到「已选列 + 序号 + 预览 SQL + 可用的移动钮」，
  #   光弹一个空弹窗等于没拍这个弹窗的核心交互（空态由 IndexDialogTest 守）。
  "p7-new-index||6000|ADB_INDEX=1 ADB_INDEX_POSE=1 ADB_THEME_FORCE=light"
  # ⭐ P2-2：错误详情弹窗 —— 分类徽标 + 驱动原文 + 建议 + 可复制的 SQL。
  #   8000ms（不是 5000）：连拍 18 次冷启动时这台机器实测会慢过 5s ——
  #   弹窗还没开就拍，得到一张与 p0-shell 同 md5 的假帧（U7 去重门禁抓到过，
  #   单帧重拍即真）。留出余量，假帧仍由去重门禁兜底。
  "p2-error||8000|ADB_ERROR_DEMO=1 ADB_THEME_FORCE=light"
)

step "编译并安装（否则拍到的是旧界面）"
require_toolchain
./mvnw -B -q clean install -DskipTests
ok "已安装，核对 jar 时间戳："
ls -la ~/.m2/repository/io/agenticdb/adb-ui-shell/1.0.0-SNAPSHOT/*.jar 2>/dev/null \
  | sed 's/^/    /' || warn "没找到 adb-ui-shell 的 jar"

mkdir -p "$SHOTS_DIR"

# macOS 没有 timeout 命令；用 perl 兜底，保证异常时脚本不会永久挂住。
run_with_timeout() {
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$@"
  elif command -v perl >/dev/null 2>&1; then
    perl -e 'alarm shift; exec @ARGV' "$secs" "$@"
  else
    "$@"
  fi
}

captured=0
for shot in "${SHOTS[@]}"; do
  IFS='|' read -r name click wait envvars <<<"$shot"

  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then
    continue
  fi

  step "拍摄 $name"
  rm -f "$SHOTS_DIR/$name.png"

  # 每张图一个干净环境：用子 shell 隔离，ADB_* 不会泄漏到下一张。
  # （不能写成 `env A=1 run_with_timeout ...` —— env 只认可执行文件，
  #   认不出 shell 函数。）
  (
    export $envvars
    export ADB_SHOTS="$SHOTS_DIR"
    export ADB_SHOTS_NAME="$name"
    export ADB_SHOTS_WAIT="$wait"
    export ADB_SHOTS_CLICK="$click"
    run_with_timeout 180 ./mvnw -B -pl bootstrap/adb-desktop javafx:run
  ) >"/tmp/adb-shot-$name.log" 2>&1 || true

  if [ -f "$SHOTS_DIR/$name.png" ]; then
    size="$(wc -c <"$SHOTS_DIR/$name.png" | tr -d ' ')"
    ok "$name.png（${size} 字节）"
    captured=$((captured + 1))
  else
    warn "$name.png 没生成，看日志：/tmp/adb-shot-$name.log"
    tail -n 15 "/tmp/adb-shot-$name.log" | sed 's/^/    /'
  fi
done

step "结果"
ok "已产出 $captured 张 → $SHOTS_DIR"

# ── U7：帧去重 ──────────────────────────────────────────────────────
# 两张帧 md5 相同 = 拍的是同一个画面，那么其中一张一定是**假帧**（它声称的
# 那个界面根本没出现）。踩过：p2-error 与 p0-shell 完全一致 —— 错误弹窗是
# Alert，默认 Modality.NONE，而截图会话只认模态窗口，于是拍到的是主窗口。
# 这一步把「靠人眼发现重复」变成脚本自己报错。
if [ "$captured" -gt 0 ]; then
  step "帧去重（同画面即假帧）"
  declare -A seen=()
  duplicates=0
  while IFS= read -r png; do
    digest="$(md5sum "$png" | cut -d' ' -f1)"
    base="$(basename "$png")"
    if [ -n "${seen[$digest]:-}" ]; then
      warn "$base 与 ${seen[$digest]} 像素完全相同 —— 其中一张是假帧"
      duplicates=$((duplicates + 1))
    else
      seen[$digest]="$base"
    fi
  done < <(ls "$SHOTS_DIR"/*.png 2>/dev/null)
  if [ "$duplicates" -gt 0 ]; then
    warn "共 $duplicates 组重复帧：核对对应页面的预置变量（ADB_*）与弹窗模态是否真的生效。"
  else
    ok "无重复帧（$captured 张各拍到了自己的画面）"
  fi
fi

warn "下一步必须人工核对：状态栏读得清吗 / 按钮有层级吗 / 图标对齐吗 / 有没有空白帧。"
info "人工核对建议（本机无 PIL，脚本不自动跑）：非背景像素占比过低 = 空白帧，重点查这一项。"
