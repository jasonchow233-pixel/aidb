#!/usr/bin/env bash
# 一键编译并启动 AIDB 桌面端。
#
# 用法：
#   ./scripts/dev.sh                 # 编译 + 启动（停在「对象」首页）
#   ./scripts/dev.sh users           # 编译 + 启动 + 直接打开 users 表
#   ADB_QUERY=1 ./scripts/dev.sh     # 编译 + 启动 + 打开查询页并自动执行
#   ADB_STRUCTURE=users ./scripts/dev.sh
#   ./scripts/dev.sh --fast          # 跳过 install 的清理，增量编译（更快，但改了 CSS 别用）
#
# 为什么需要这个脚本（两个真实踩过的坑）：
#
#   坑 1 —— 不 install 会跑旧代码。
#     `./mvnw -pl bootstrap/adb-desktop javafx:run` 不带 -am 时，Maven 从
#     ~/.m2 取 adb-ui-shell 的**已安装 jar**。改了半天 CSS 而界面没变，
#     就是因为 jar 还是两小时前的。本脚本强制执行 install。
#
#   坑 2 —— JDK 版本不对时，enforcer 的报错埋在 build 日志深处。
#     pom.xml 要求 [25,26)。用 JDK 21 跑只会得到一句
#     "Detected JDK version 21.0.12-1 ... is not in the allowed range [25,26)"。
#     _env.sh 在编译前就把这件事说清楚，并给出修正命令。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

FAST=0
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --help|-h)
      # 注释块本身即帮助文本。用 BRE 兼容写法（macOS 自带的是 BSD sed，
      # 不支持 GNU 的 \?）。
      sed -n '2,22p' "$0" | sed 's/^#//; s/^ //'
      exit 0 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
apply_positional_open "${POSITIONAL[@]:-}"

step "编译并安装到本地仓库"
require_toolchain

if [ "$FAST" = "1" ]; then
  warn "--fast：增量编译，不 clean。改了 CSS / 资源文件请去掉 --fast。"
  ./mvnw -B -q install -DskipTests
else
  ./mvnw -B -q clean install -DskipTests
fi
ok "编译完成（产物已进 ~/.m2）"

step "启动桌面端"
info "快捷键：⌘K 命令面板 · ⌘N 新建连接 · ⇧⌘N 新建查询 · F1 快捷键速查"
info "退出：关闭窗口或按 ⌘Q"
printf '%s\n' "${C_YELLOW}（下面的 Maven 日志归属于应用进程，Ctrl-C 即可退出）${C_RESET}"

exec ./mvnw -B -pl bootstrap/adb-desktop javafx:run
