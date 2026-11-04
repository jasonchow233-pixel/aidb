#!/usr/bin/env bash
# 冷启动基准：真进程量「进程启动 → 首屏」，逐次打印并对预算判定。
#
# 用法：
#   ./scripts/startup-bench.sh              # 3 次启动，判定**最坏一次**
#   ./scripts/startup-bench.sh --runs 5     # 跑 5 次
#   ./scripts/startup-bench.sh --fast       # 跳过 install（只在确认构件是新的时用）
#   ADB_STARTUP_CEILING=4500 ./scripts/startup-bench.sh   # 临时改回归地板
#
# ⚠️ 三个数别互相抄（这是本项目一贯的口径纪律）：
#   - **2000ms** = 规格目标，`StartupTrace.SLOW_FIRST_SCREEN_MILLIS`，日志里那句
#     "(SLOW, over 2000 ms" 就是它。本轮没达到，也没打算靠改常量达到。
#   - **3000ms** = R61 这一轮定的**阶段目标**（H3-a：「先立 <3s 一档门禁再压」）。
#     它是目标，不是判定线 —— 判定线红着就等于门禁永久不可用，所以本脚本按
#     回归地板判，同时把离目标的差距打出来。
#   - **回归地板** = 实测出来的最坏一次 + 余量，只有量过才许改（见下面 CEILING 的注释）。
#
# 为什么需要单独一个脚本，而不是写成一条单测：
#   surefire 的 fork 是一个**已经跑了几分钟的测试运行器进程**，而 firstScreenMillis
#   量的是「进程启动 → 首屏」。在那个口径下「首屏 < 2s」单跑必绿、全量必红 ——
#   StartupTrace 的构造器注释里记着这件事，StartupTraceTest 也因此显式撤掉了那条断言，
#   改成用注入的起点造快/慢两种首屏。所以**真墙钟只能真进程量**，这里就是那条通道。
#
# 口径注意：
#   - 每次都是新 JVM（真正的冷进程），但**文件系统缓存不是每次都冷** —— 第一次跑完
#     之后 jar 与原生库已经进了页缓存。所以「装完新构件的第一次」通常比后面几次慢，
#     判定取最坏一次就是为了不被这份运气骗过去。
#   - 量的是这台机器、这个构建（`mvn javafx:run`，不是打包后的 .app）。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

RUNS=3
FAST=0
# 阶段目标（R61 定的那一档），只用于打印差距，不参与判定。
TARGET_MILLIS=3000
# 回归地板：**2026-11-04 实测**（见 docs/STATUS.md 的冷启动那一行）本机 3 次跑
# 的最坏一次是 5.9s 档，改这个数之前必须重新量，不许为了让脚本变绿而调它。
CEILING_MILLIS="${ADB_STARTUP_CEILING:-6000}"

while [ $# -gt 0 ]; do
  case "$1" in
    --runs)
      [ $# -ge 2 ] || fatal "--runs 需要一个数字"
      RUNS="$2"; shift 2 ;;
    --fast) FAST=1; shift ;;
    --help|-h)
      # 注释块本身即帮助文本（BRE 兼容写法：macOS 自带 BSD sed，不支持 GNU 的 \?）。
      sed -n '2,32p' "$0" | sed 's/^#//; s/^ //'
      exit 0 ;;
    *) shift ;;
  esac
done

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

if [ "$FAST" = "1" ]; then
  warn "--fast：跳过 install，量的是 ~/.m2 里现有的构件。"
else
  step "编译并安装到本地仓库（量的必须是这份代码）"
  require_toolchain
  ./mvnw -B -q install -DskipTests || fatal "编译失败，没有可量的构件"
fi

step "冷启动基准：${RUNS} 次真进程（阶段目标 <${TARGET_MILLIS}ms，回归地板 <${CEILING_MILLIS}ms）"

results=()
for run in $(seq 1 "$RUNS"); do
  # ADB_STARTUP_EXIT=1 → App 在首屏打点之后打印一行摘要并退出（见 App.start）。
  # ADB_STARTUP_LOG=false → 不往用户的 ~/.agenticdb/logs/startup.log 里堆基准行：
  #   那份日志属于真实用户启动，本脚本自己有 stdout 这条通道。
  # `|| true`：_env.sh 开了 set -e / pipefail，而这条通道上「应用没退干净」
  #   （perl alarm 兜底给的 142）不该把已经量到的数一起作废。
  log="$(ADB_STARTUP_EXIT=1 ADB_STARTUP_LOG=false \
      run_with_timeout 180 ./mvnw -B -pl bootstrap/adb-desktop javafx:run 2>&1 || true)"
  # 不用 `| head -1`：pipefail 下 sed 会收到 SIGPIPE 而让整条管道非零退出。
  millis="$(printf '%s\n' "$log" \
      | sed -n 's/.*first paint \([0-9][0-9]*\) ms.*/\1/p' | sed 1q)"
  if [ -z "$millis" ]; then
    printf '%s\n' "$log" | tail -20 >&2
    fatal "第 ${run} 次没读到首屏耗时 —— 应用没走到 first-screen 就打不开这条通道"
  fi
  breakdown="$(printf '%s\n' "$log" \
      | sed -n 's/^\[startup\] first paint [0-9]* ms[^|]*| //p' | sed 1q)"
  results+=("$millis")
  ok "第 ${run} 次：first paint ${millis} ms"
  info "  分段：${breakdown}"
done

# 判定用**最坏一次**（不是平均值）：冷启动是用户第一次点开应用那一下，
# 平均值会把「有一次特别慢」这件事抹平。
worst=0
total=0
for value in "${results[@]}"; do
  if [ "$value" -gt "$worst" ]; then worst="$value"; fi
  total=$((total + value))
done
average=$((total / RUNS))

echo
step "汇总：最坏 ${worst} ms · 平均 ${average} ms（${RUNS} 次）"
printf '%s\n' "  阶段目标 ${TARGET_MILLIS}ms 还差 $((worst - TARGET_MILLIS))ms（按最坏一次算）"

if [ "$worst" -gt "$CEILING_MILLIS" ]; then
  fatal "超过回归地板 ${CEILING_MILLIS}ms —— 冷启动退化了，先查分段再改代码"
fi
ok "回归地板之内（地板 ${CEILING_MILLIS}ms，阶段目标 ${TARGET_MILLIS}ms 仍未达成）"
