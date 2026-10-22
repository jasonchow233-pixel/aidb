#!/usr/bin/env bash
# 完整门禁：编译 + 全部测试 + 用例计数校验。等价于 CI 跑的那条命令。
#
# 用法：
#   ./scripts/build.sh            # clean verify（完整门禁）
#   ./scripts/build.sh --quick    # 不 clean，增量跑测试
#   ./scripts/build.sh --no-tests # 只编译安装，不跑测试
#
# 与 dev.sh 的区别：本脚本**跑测试**并把结果统计出来；
# dev.sh 跳过测试、直接把应用跑起来。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

MODE="full"
for arg in "$@"; do
  case "$arg" in
    --quick)    MODE="quick" ;;
    --no-tests) MODE="notests" ;;
    --help|-h)
      sed -n '2,10p' "$0" | sed 's/^#//; s/^ //'
      exit 0 ;;
    *) ;;
  esac
done

require_toolchain

case "$MODE" in
  notests)
    step "编译并安装（跳过测试）"
    ./mvnw -B clean install -DskipTests
    ok "构建成功（未跑测试）"
    ;;
  quick)
    step "增量测试"
    ./mvnw -B test
    ;;
  full)
    step "完整门禁（clean verify）"
    ./mvnw -B clean verify
    ;;
esac

if [ "$MODE" != "notests" ]; then
  step "用例计数"
  # surefire 对 @Nested 测试类写 tests="0"，按属性统计会少算一百多条。
  # tools/TestCount.java 一律按 <testcase> 元素计数，并断言总数下限。
  java tools/TestCount.java
  ok "门禁通过"
fi
