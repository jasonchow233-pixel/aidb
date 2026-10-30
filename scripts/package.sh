#!/usr/bin/env bash
# 一键出包（jpackage）—— 把 AIDB 打成可分发的安装包 / 应用镜像。
#
# 用法：
#   ./scripts/package.sh              # 当前平台默认类型（macOS=app-image，Windows=msi，Linux=deb）
#   ./scripts/package.sh app-image    # 只出「免安装目录」
#   ./scripts/package.sh dmg          # macOS：磁盘镜像
#   ./scripts/package.sh msi          # Windows：安装包
#   ./scripts/package.sh deb          # Linux：安装包
#
# 产物在：bootstrap/adb-desktop/target/dist/
#
# ⚠️ 为什么不用 jlink 裁剪运行时：
#   jlink 只能收录**具名模块**（module-info）。本项目的两个关键依赖 ——
#   RichTextFX（SQL 编辑器）与 ControlFX —— 都是**自动模块**（只有
#   Automatic-Module-Name，没有 module-info），jlink **收不进去**。
#   jpackage 不指定 --runtime-image 时会直接用当前 JDK（不跑 jlink），
#   所以能用；代价是安装包里的运行时偏大。
#   等这两个库模块化之后，再加 jlink profile 裁剪（jlink 边界就是「只收具名模块」这一条）。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

TYPE="${1:-}"
case "$(uname -s)" in
  Darwin*) DEFAULT_TYPE="app-image" ;;
  MINGW*|MSYS*|CYGWIN*) DEFAULT_TYPE="msi" ;;
  *) DEFAULT_TYPE="deb" ;;
esac
TYPE="${TYPE:-$DEFAULT_TYPE}"

step "准备出包（类型：${TYPE}）"
require_toolchain

MODULE_DIR="$(cd "$PROJECT_ROOT/bootstrap/adb-desktop" && pwd)"
cd "$MODULE_DIR" || exit 1

# 1) 先打出可运行的 jar（含依赖）。
info "编译并打包 jar"
"$PROJECT_ROOT/mvnw" -B -q clean package -DskipTests || { fatal "编译失败"; exit 1; }

# 2) 收集依赖 jar —— jpackage 的 --input 目录里要有一个「主 jar + 依赖」。
INPUT_DIR="$MODULE_DIR/target/package-input"
rm -rf "$INPUT_DIR"
mkdir -p "$INPUT_DIR"
cp "$MODULE_DIR/target/adb-desktop-"*.jar "$INPUT_DIR/" 2>/dev/null
info "收集依赖"
"$PROJECT_ROOT/mvnw" -B -q dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory="$INPUT_DIR" || { fatal "收集依赖失败"; exit 1; }

# 3) 资源（图标等）要在 classpath 上。
RES_SRC="$PROJECT_ROOT/ui/adb-ui-shell/src/main/resources"
if [ -d "$RES_SRC" ]; then
  cp -R "$RES_SRC/." "$INPUT_DIR/"
fi

# 4) jpackage。
# ⭐ 版本号要**只匹配数字开头**的 <version>：pom 头部的注释里也有 "<version>" 字样。
VERSION="$(grep -m1 -E '<version>[0-9]' "$PROJECT_ROOT/pom.xml" \
  | sed -E 's/.*<version>([^<]*)<\/version>.*/\1/')"
# jpackage 的 --app-version 只接受「数字.数字.数字」这种形式，
# 我们的版本带 -SNAPSHOT 后缀 → 单独算一个「数字版」给它。
APP_VERSION="$(printf '%s' "$VERSION" | sed -E 's/^([0-9]+(\.[0-9]+)*).*/\1/')"
DIST_DIR="$MODULE_DIR/target/dist"
mkdir -p "$DIST_DIR"

JPACKAGE_ARGS=(
  --name "AIDB"
  --app-version "${APP_VERSION}"
  --vendor "AIDB"
  --description "跨平台多数据库管理桌面客户端"
  --input "$INPUT_DIR"
  --main-jar "adb-desktop-$VERSION.jar"
  --main-class "io.agenticdb.desktop.Launcher"
  --dest "$DIST_DIR"
  --java-options "--enable-native-access=ALL-UNNAMED"
)

# ⭐ 安装包图标（V-6）：jpackage 按扩展名选解码器，不给 --icon 就回退 Java 默认图标，
# 而且**不报错** —— 装了才知道。资产由 `java tools/GenPackIcons.java` 从 GenLogo 同源几何生成。
PACK_ICON_DIR="$PROJECT_ROOT/brand"
case "$(uname -s)" in
  Darwin*)
    if [ -f "$PACK_ICON_DIR/app-icon.icns" ]; then
      JPACKAGE_ARGS+=(--icon "$PACK_ICON_DIR/app-icon.icns")
    else
      warn "缺 brand/app-icon.icns —— 先跑 java tools/GenPackIcons.java，否则包里没有品牌图标"
    fi
    ;;
  MINGW*|MSYS*|CYGWIN*)
    if [ -f "$PACK_ICON_DIR/app-icon.ico" ]; then
      JPACKAGE_ARGS+=(--icon "$PACK_ICON_DIR/app-icon.ico")
    else
      warn "缺 brand/app-icon.ico —— 先跑 java tools/GenPackIcons.java，否则包里没有品牌图标"
    fi
    ;;
  *)
    # Linux：deb 的图标走 --icon（png 可用运行时 512 主入口）
    if [ -f "$PACK_ICON_DIR/app-icon.png" ] || [ -f "$PROJECT_ROOT/ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app-icon.png" ]; then
      JPACKAGE_ARGS+=(--icon "$PROJECT_ROOT/ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app-icon.png")
    fi
    ;;
esac

case "$TYPE" in
  app-image)
    JPACKAGE_ARGS+=(--type app-image)
    ;;
  dmg|mac)
    JPACKAGE_ARGS+=(--type dmg --mac-package-identifier io.agenticdb.desktop)
    ;;
  msi|windows)
    JPACKAGE_ARGS+=(--type msi --win-menu --win-shortcut)
    ;;
  deb|linux)
    JPACKAGE_ARGS+=(--type deb --linux-package-name agenticdb --linux-shortcut)
    ;;
  pkg)
    JPACKAGE_ARGS+=(--type pkg --mac-package-identifier io.agenticdb.desktop)
    ;;
  *)
    fatal "未知类型：$TYPE（可用：app-image / dmg / msi / deb / pkg）"
    exit 1
    ;;
esac

step "调用 jpackage"
if command -v jpackage >/dev/null 2>&1; then
  jpackage "${JPACKAGE_ARGS[@]}"
else
  fatal "找不到 jpackage —— 需要 JDK 25（不是 JRE）"
  exit 1
fi

ok "出包完成"
info "产物目录：$DIST_DIR"
ls -1 "$DIST_DIR" 2>/dev/null | sed 's/^/  · /'
