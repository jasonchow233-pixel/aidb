# 参与贡献

先说结论：**能跑起来 → 改 → `./scripts/build.sh` 全绿 → 提 PR**。
卡住任何一步都直接开 issue 问，不用先读完文档。

## 1. 环境

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | **25（含）～ 28（不含）** | 唯一真相在 `pom.xml` 的 `requireJavaVersion`，脚本从那里读，不硬编码 |
| Maven | 不需要装 | 用仓库自带的 `./mvnw` |
| 平台 | macOS / Windows / Linux | 界面是 JavaFX 原生桌面，**不内嵌 WebView、不启动本地 HTTP 端口** |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS 示例
```

## 2. 三条命令

```bash
./scripts/dev.sh        # 编译（跳过测试）并把应用跑起来
./scripts/build.sh      # 完整门禁：clean verify + 用例计数下限校验
./scripts/shots.sh      # 重拍 docs/screenshots/ 的验收帧
```

只想跑某个模块的测试：

```bash
./mvnw -B -pl ui/adb-ui-shell test
./mvnw -B -pl ui/adb-ui-shell test -Dtest=IconKindHueTest,AppBarTest
```

⚠️ 改了 `ui/adb-ui-core` 之后，单跑 `ui/adb-ui-shell` 前要先
`./mvnw -pl ui/adb-ui-core install -DskipTests` —— 否则 ui-shell 用的是
`~/.m2` 里的旧 jar，测试跑的是**上一版代码**（踩过，且极难归因）。
同理 `dev.sh` 与 `shots.sh` 都会 `clean install`，**不要并发跑**。

## 3. 模块地图

| 目录 | 内容 |
|---|---|
| `core/` | 领域与契约（无 UI 依赖） |
| `workbench/` | 内省、导出导入、SQL 生成等能力层 |
| `ui/adb-ui-core` | 设计令牌、主题 CSS、`Icons` 图标系统 |
| `ui/adb-ui-shell` | 外壳与页面（树 / 页签 / 数据网格 / 弹窗） |
| `bootstrap/adb-desktop` | 启动入口与打包 |
| `testing/adb-fixtures` | 测试夹具 |

细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；界面口径见
[docs/UI-VISUAL-SPEC.md](docs/UI-VISUAL-SPEC.md) 与 [docs/UI-GUIDE.md](docs/UI-GUIDE.md)；
现状与限制的完整清单（含**唯一一处**验证基线数字）在 [docs/STATUS.md](docs/STATUS.md)。

## 4. 这个仓库的几条硬规矩

它们不是风格偏好，每一条背后都是一次真实事故。门禁会拦住违反者，所以先读这四条：

1. **说「做完了」就得有门禁断言它做完了。** 没有断言的「已完成」等于没做。
   新加的门禁还要做**变异验证**：故意把它测的东西弄坏，确认它会红。
   绿门禁 ≠ 真看得见 —— 有一条层叠 bug 全绿了三轮才被像素对比抓到。
2. **颜色只有一个来源**：`ui/adb-ui-core/src/main/resources/tokens/tokens.json`
   → `java tools/GenTokens.java` → 生成 `Tokens.java` 与两套主题 CSS。
   业务代码里**不许出现颜色字面量**，图标资产里也不许（只标 `data-paint` 角色）。
3. **诚实条款**：不许有「看起来一样但点了没反应」的控件。功能没做就别摆按钮，
   或者摆出来并明确告诉用户「这里还没有实现、什么时候做在路线图里」。
4. **改判要写明理由**，不许为了让测试变绿而改测试。撤销一条规则时，把「为什么撤」
   写进对应的文档条目和提交信息里。

## 5. 加一个图标

1. 在 `ui/adb-ui-core/.../ui/icons/lucide/` 放源 SVG（Lucide 路径数据，ISC，
   该目录已带 `ISC-LICENSE.txt`，借用素材的署名义务由门禁守着）；
2. 在 `Icons.Icon` 枚举里加常量，指向文件名；
3. 尺寸只能取 `Icons.SIZE_*` 常量，**不许在调用点写数字**；
4. 需要彩色档时，在 `icons/color/<同名>.svg` 加资产，元素只标
   `data-paint="edge|tone-a|tone-b|tone-c|accent"`，颜色由 `app.css` 的
   `-adb-icon-*` 提供。没有彩色资产时 `Icons.colored()` 自动回退单色。
   ⚠️ 分层返回的是 `Group`，它没有任何可绘属性 —— 类型色必须用**后代选择器**
   落到子层，挂在容器上的规则是死规则（彩色档规则见
   [docs/UI-VISUAL-SPEC.md](docs/UI-VISUAL-SPEC.md) §4.5）。

## 6. 提交与 PR

- 提交信息沿用现有风格：`type(scope): 中文摘要`，正文写**为什么**而不是「改了什么」
  （diff 已经说明改了什么）。
- 一个 PR 做一件事。视觉改动请附 `./scripts/shots.sh <帧名>` 重拍的前后帧。
- 提 PR 前本地跑 `./scripts/build.sh`；CI 跑的是同一条命令。

## 7. 找活

看 [docs/ROADMAP.md](docs/ROADMAP.md) 的「进行中 / 下一步」。想做大改先开 issue
对一下方向 —— 这个项目对「和 Navicat 一致」有明确承诺，视觉与交互层的改动
大概率需要先对齐口径。
