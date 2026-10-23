#!/usr/bin/env bash
# AIDB —— 所有 scripts/ 下脚本共用的环境预检与工具函数。
#
# 这个文件不直接执行，由 dev.sh / build.sh / shots.sh 通过
#   source "$(dirname "$0")/_env.sh"
# 引入。

set -euo pipefail

# ── 定位项目根（脚本可从任意目录调用）──────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 终端输出 ───────────────────────────────────────────────────────
if [ -t 1 ] && [ "${TERM:-}" != "dumb" ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi

info()  { printf '%s\n' "${C_BLUE}▶${C_RESET} $*"; }
ok()    { printf '%s\n' "${C_GREEN}✓${C_RESET} $*"; }
warn()  { printf '%s\n' "${C_YELLOW}⚠${C_RESET} $*"; }
fatal() { printf '%s\n' "${C_RED}✗${C_RESET} $*" >&2; exit 1; }
step()  { printf '\n%s\n' "${C_BOLD}── $* ──${C_RESET}"; }

# ── 从 pom.xml 解析 enforcer 声明的 JDK 区间 ───────────────────────
# pom.xml: <requireJavaVersion><version>[25,26)</version></requireJavaVersion>
# 项目里 JDK 版本只此一处为真相，脚本不硬编码，避免改了 pom 忘了改脚本。
required_jdk_min() {
  sed -n 's/.*<requireJavaVersion><version>\[\([0-9]*\),.*<\/version><\/requireJavaVersion>.*/\1/p' \
      pom.xml | head -n 1
}

required_maven_min() {
  sed -n 's/.*<requireMavenVersion><version>\[\([0-9.]*\),.*<\/version><\/requireMavenVersion>.*/\1/p' \
      pom.xml | head -n 1
}

# ── 取当前 java 的 major 版本号 ────────────────────────────────────
# 兼容两种输出：
#   openjdk version "25.0.3" 2026-04-21 LTS      （8u 之后的新格式）
#   java version "1.8.0_382"                     （Java 8 老格式）
current_jdk_major() {
  local raw
  raw="$("${JAVA_HOME:-}/bin/java" -version 2>&1 | head -n 1 || true)"
  [ -n "$raw" ] || raw="$(java -version 2>&1 | head -n 1 || true)"
  if [[ "$raw" =~ \"1\.([0-9]+)\. ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "$raw" =~ \"([0-9]+)\. ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

current_maven_version() {
  ./mvnw -v 2>/dev/null | head -n 1 | sed -n 's/.*Apache Maven \([0-9.]*\).*/\1/p'
}

# ── 环境预检 ───────────────────────────────────────────────────────
# 这是脚本存在的头号理由：JDK 版本不对时，maven-enforcer-plugin 报的错
# （"is not in the allowed range"）出现在 build 日志深处，很难一眼看出是环境问题。
require_toolchain() {
  local need_jdk need_mvn have_jdk have_mvn

  need_jdk="$(required_jdk_min)"; [ -n "$need_jdk" ] || need_jdk=25
  need_mvn="$(required_maven_min)"; [ -n "$need_mvn" ] || need_mvn=3.9

  # 1) java 存在吗
  if ! command -v java >/dev/null 2>&1 && [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
    fatal "找不到 java。请安装 JDK ${need_jdk}，或用 JAVA_HOME 指向已有的 JDK ${need_jdk}。"
  fi

  # 2) java 版本对吗
  have_jdk="$(current_jdk_major)"
  if [ -z "$have_jdk" ]; then
    fatal "读不出 java 版本，请检查 JAVA_HOME（当前：${JAVA_HOME:-未设置}）。"
  fi
  if [ "$have_jdk" -lt "$need_jdk" ]; then
    fatal "JDK 版本不对：当前 ${have_jdk}，pom.xml 要求 >= ${need_jdk}（区间 [${need_jdk},$((need_jdk+1)))）。
   当前 JAVA_HOME = ${JAVA_HOME:-未设置}
   修正示例：export JAVA_HOME=\$(/usr/libexec/java_home -v ${need_jdk})"
  fi

  # 3) maven wrapper 能用吗（mvnw 首次运行会自行下载 Maven，需要网络）
  if [ ! -x ./mvnw ]; then
    fatal "缺少 ./mvnw（项目根目录：${PROJECT_ROOT}）。"
  fi
  have_mvn="$(current_maven_version)"
  if [ -z "$have_mvn" ]; then
    warn "读不到 Maven 版本；./mvnw 可能在首次下载 Maven，需要网络。"
  fi

  ok "环境就绪：JDK ${have_jdk}（要求 >= ${need_jdk}）· Maven ${have_mvn:-首次下载中} · ${PROJECT_ROOT}"
}

# ── 把用户给的第一个位置参数翻译成 ADB_OPEN ────────────────────────
# dev.sh users  ==  ADB_OPEN=users dev.sh
# 已有环境变量优先，不被覆盖。
apply_positional_open() {
  if [ "$#" -gt 0 ] && [ -n "${1:-}" ] && [ -z "${ADB_OPEN:-}" ]; then
    export ADB_OPEN="$1"
    info "ADB_OPEN=${ADB_OPEN}（来自命令行参数）"
  fi
}
