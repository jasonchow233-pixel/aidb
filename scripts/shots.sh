#!/usr/bin/env bash
# 重拍 docs/screenshots/ 的验收截图（10 张）。
#
# 用法：
#   ./scripts/shots.sh              # 全部重拍（亮色 9 张 + 暗色 1 张）
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

# name|click-id|wait-ms|预置页面环境变量
# 顺序即拍摄顺序：暗色必须最先。
SHOTS=(
  "p5-dark|sidebar-connections|5000|ADB_THEME_FORCE=dark"
  "p0-shell||2500|ADB_THEME_FORCE=light"
  "p2-users||5000|ADB_OPEN=users ADB_THEME_FORCE=light"
  "p6-tables||5000|ADB_TABLES=1 ADB_THEME_FORCE=light"
  "p6-tables-grid||5000|ADB_TABLES=1 ADB_TABLE_VIEW=grid ADB_THEME_FORCE=light"
  "p3-query||6000|ADB_QUERY=1 ADB_THEME_FORCE=light"
  "p4-structure||5000|ADB_STRUCTURE=users ADB_THEME_FORCE=light"
  "p5-connections|sidebar-connections|4000|ADB_THEME_FORCE=light"
  "p6-new-table||5000|ADB_NEW_TABLE=1 ADB_THEME_FORCE=light"
  "p7-new-index||6000|ADB_INDEX=1 ADB_THEME_FORCE=light"
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
warn "下一步必须人工核对：状态栏读得清吗 / 按钮有层级吗 / 图标对齐吗 / 有没有空白帧。"
info "像素密度自检（PIL）：非背景像素占比过低 = 空白帧，重点查这一项。"
