# AIDB 品牌规范

## 品牌名

**AIDB**（主名） / AgenticDB（全称） / aidb（包名、目录名）

规则：
- 界面文字、菜单、窗口标题、状态栏、Toast、崩溃日志：**AIDB**
- Maven `<name>`、jpackage `--name`：**AIDB**
- Java 包名 `io.agenticdb`、Maven groupId `io.agenticdb`、数据目录 `~/.agenticdb`：**不改**（保持兼容与历史可追溯）

## Logo

### 几何语义
- **数据库三层圆柱**：三条数据盘叠放，用两条底色弧线分层 —— 一眼可读为「数据库」
- **AI 四角星芒**：压在圆柱右上角，四条二次贝塞尔内凹收尖 —— 表达「AI 原生」

### 颜色
| 元素 | 亮色 | 暗色 | 同源令牌 |
|---|---|---|---|
| 底板渐变 | `#3B82F6 → #1D4ED8` | 同 | `accent`（`#3B82F6`）+ `accent-hover`（`#1D4ED8`） |
| 圆柱 / 星芒 | `#FFFFFF` | 同 | `text-inverse`（亮色主按钮上） |

### 源文件
| 文件 | 用途 |
|---|---|
| `brand/aidb-mark.svg` | 纯图标（1024 规格），透明底 |
| `brand/aidb-logo-horizontal.svg` | 横版：图标 + AIDB 字标 + 副标 |
| `tools/GenLogo.java` | Java2D 渲染器，与 SVG 几何一致，产出 PNG |
| `ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app-icon*.png` | 运行时使用的多尺寸 PNG |

### 尺寸集（由 `java tools/GenLogo.java` 生成）
```
app-icon-1024.png   高分屏 / 安装包封面
app-icon.png        512（主入口，阶段窗口）
app-icon-256.png    macOS Dock / Windows 任务栏
app-icon-128.png    高分屏标签页
app-icon-64.png     窗口标签页
app-icon-32.png     通知 / 系统托盘
app-icon-16.png     最小尺寸（侧栏树节点）
```
所有尺寸注册进 `stage.getIcons()`，系统按位置自动选最合适的一张。

### 改 logo 的流程
1. 同步改 `brand/aidb-mark.svg` 与 `tools/GenLogo.java`（几何必须一致）
2. `java tools/GenLogo.java`
3. `./scripts/shots.sh` 重拍截图，肉眼看应用栏

## 品牌色在界面中的落点

| 位置 | 规则 | 样式类 |
|---|---|---|
| 应用栏品牌块 | 26×26 圆角方 + `accent` 底 + `text-inverse` 数据库字形 | `.adb-appbar__mark` |
| 应用栏字标 | 16px bold · `text-primary` | `.adb-appbar__title` |
| 欢迎页 logo | 矢量图标 `Icons.Icon.DATABASE`，不是位图 | `.adb-welcome__logo` |
| 品牌色使用约束 | **品牌蓝只出现在「主操作 / 焦点 / 连接状态点」**，不铺面 | `accent` / `accent-hover` |

## 命名空间约束（重要）

- ⛔ **不要**把 Java 包名 `io.agenticdb` 改成 `io.aidb`：
  包名改动会波及 200+ 个源文件、14 个模块的 pom、全部测试，
  并让 `~/.agenticdb` 下的历史数据目录失联。收益为零，风险极高。
- ⛔ **不要**把 Maven groupId `io.agenticdb` 改掉：已发布到本地仓库的
  artifact 坐标会全部失效。
- ✅ 用户可见的一切（标题、菜单、Toast、日志、导出 SQL 注释头）统一为 **AIDB**。
