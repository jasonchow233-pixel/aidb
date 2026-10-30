# 更新日志

本文件记录 AIDB（AgenticDB）面向使用者的变更。

- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
- **当前状态：尚未发布任何正式版本。** 仓库版本是 `1.0.0-SNAPSHOT`，没有任何 git tag。
  首个 tag 会在 macOS 公证 / 签名与分发渠道确定后切（见 [docs/ROADMAP.md](docs/ROADMAP.md)）。
- **不回填历史。** tag 之前的 170+ 次提交请查 `git log --oneline`；本文件从「有意对外发布」那一刻开始维护。

## [Unreleased]

### Added

- **R35 彩色图标通道**：`icons/color/*.svg` 资产只声明语义层（`edge` / `tone-a` / `tone-b` / `tone-c` / `accent`），
  颜色一律来自设计令牌，由 `app.css` 的后代规则打到对应子层。
  暗色档**不需要第二套资产**——同一张 SVG 换主题自动换色。
  导航词表（侧栏 / 对象树 / 结构页 / 页签 / 标题栏）与功能区 6 条命令已全量走彩色档。
  门禁：`ColoredIconTest`、`IconKindHueTest`、`IconSystemAuditTest.theNavigationVocabularyIsFullyColored` / `theRibbonVocabularyIsFullyColored`。
- **图标分层令牌**：`-adb-icon-edge` / `-adb-icon-tone-*` 与各对象类型色 `-adb-kind-*`（明暗两档）进 `tokens.json` 唯一真相。
- 借用素材的署名义务钉成门禁：`icons/lucide/` 与 `icons/color/` 目录必须随附 `ISC-LICENSE.txt`（`ColoredIconTest.everyBorrowedAssetDirectoryShipsItsLicense`）。
- 社区与合规文件：`LICENSE`（Apache-2.0）、`NOTICE`（三方许可逐条列明）、`CONTRIBUTING.md`、`SECURITY.md`、
  `.github/` Issue / PR 模板与 CI 工作流。

### Changed

- **16px 内联工具条进彩色档**（2026-10-30，R35 N4-c；改的是 `docs/UI-VISUAL-SPEC.md` §4.5 第 1 条规矩）。
  旧规矩把「16px 内联工具条」整体划在单色区，依据是想当然的「小图标彩了会糊」；
  逐像素翻 Navicat 数据页实拍帧（`docs/screenshots/navicat/navicat-data-grid-2026-10-24.png`）后确认
  **Navicat 的 16px 本身分两区**：上沿工具条是蓝色实心小图形（和树上同一族颜色），
  贴在网格左下的行操作条 `+ − ✓ ✗ ` 是灰的单线。
  于是「同一个图标在树上彩、在工具条上单色」按对手口径是**缺陷**（I-4），修法：
  工具条默认工厂走 `Icons.colored`（无彩色资产自动回退单色），行操作条显式走新的
  `IconButtons.iconMono`。门禁 `ToolbarIconChannelTest` 双向钉（彩的必须彩、单色的必须单色），
  `DataGridPageTest.theTopToolbarIsColoredWhileTheRowStripStaysMonochrome` 从真实页面的按钮上验两区。
  ⚠️ 连带必须补的一条：分层返回 `Group`，只认 `.adb-icon` 的按钮态规则命中不了子层 ——
  不补就是蓝底上蓝边看不见图标、禁用还亮着蓝边（后者撞诚实条款）。
  `app.css` 新增「彩色分层在按钮态里的翻色」段（`--primary` 翻白 + `:disabled` 塌灰），
  门禁读**计算色**而不是 CSS 文本。
  变异验证（三条都已实测）：工厂改回 `Icons.of` → 18 枚图标全部报「掉回单色」；
  删 `:disabled` 那条边色规则 → 禁用态门禁红在轮廓断言；删 `--primary` 那条 → 主动作门禁红。
- **启动耗时门禁改判**（2026-10-30）：`StartupTraceTest` 里那条「首屏不应超过 2s」删掉了。
  它断言的 `firstScreenMillis()` 量的是「**JVM 进程启动 → 走到断言那一行**」，
  在共享的 surefire fork 里这个差值等于测试运行器已经跑了多久 ——
  所以「全量跑必红、单跑这个类全绿」，它测的是运行器的进程年龄，不是产品的冷启动。
  真实冷启动只在真启动时才有意义，量法仍是 `~/.agenticdb/logs/startup.log`（对外记的 3.5–3.9 s 从那儿读）。
  2 秒这条规格**没有放弃**，改由新门禁 `theTwoSecondBudgetIsEnforcedInBothDirections` 钉住：
  `StartupTrace` 开一个包内构造器接收注入的「进程启动时刻」，测试据此确定性地造出
  0.5s（判快）与 2.5s（判慢，且摘要里必须喊 `SLOW`）两种首屏。
  变异验证：把 `slow()` 改成恒 `false` → 该门禁立刻红（已实测）。
- **功能区图标尺寸维持 26px**（原计划放大到 30px）。改判理由：Navicat 17 实测里，那一排大图标是全界面最彩的部分，
  需要的是彩色档而不是更大的单色图标；30px 留到 R36 重做顶区布局时一起判。
  见 [docs/UI-VISUAL-SPEC.md](docs/UI-VISUAL-SPEC.md) §4.5。
- 对象类型色回到 `edge`（轮廓）层，`tone` 三层保持中性——同一个对象只保留一处颜色来源。
- 视觉文档与实现对齐：设计令牌数校正为 106（68 色 / 35 尺寸 / 3 时长），移除不存在的 `border-strong` 记录，
  图标台账改为 55 枚（53 枚 Lucide + 2 枚自绘）。

### Fixed

- **关掉数据页签之后，列宽防抖还会照点一次落盘**（2026-10-30）：`scheduleLayoutSave()` 用 400ms 的
  `PauseTransition` 去抖，`close()` 只取消了在跑的查询、没摘这个定时器。页签没了它照样触发一次
  `persistLayout()` —— 往已拆卸的场景里读列、往可能已被回收的 prefs 节点写值，
  抛 `IllegalStateException: Node has been removed`。
  症状很误导人：崩的是**跟在这页之后跑的别的用例**，崩溃日志里也长期躺着这条噪音。
  现在 `close()` 显式 `stop()` 定时器（只停不补写：最多丢一次「拖完 400ms 内就关页签」的宽度，
  换来关页之后不再有任何写）。门禁：`closingThePageCancelsThePendingLayoutSave`
  断言的是**防抖还在不在跑**，不是等 400ms 看有没有异常。
  变异验证：删掉那三行 `stop()` → 本类两条用例同时红（已实测）。
- **弹窗按钮序门禁在 macOS 上假红**（长期红，2026-10-30 修）：
  `DialogButtonOrderTest.theOrderStringOnlySwapsThoseTwoSlots` 拿 `DialogStyles.apply` 之后的
  order 串去比 **Windows 预设**，但 `ButtonBar` 的初始串是**按宿主平台**选的
  （macOS 那份是 `L_HE+U+FBIX_NYA_R`，既没有 `O` 也没有 `C`），而生产代码对不认识的预设
  故意不动（那是另一条门禁钉住的规则）。结果 Windows 全绿、macOS 必红，且与产品无关。
  现在测试**显式喂 Windows 预设**再断言「只换 O / C 两槽」，跨平台一致。
  变异验证：把 `DialogStyles` 的换槽分支短路 → 该门禁在 macOS 上立刻红在第 214 行
  「取消 C 要在主动作 O 左边」（已实测，不是推测）。
- `scripts/shots.sh` 的**帧去重门禁在本机（macOS）从来没真正跑过**：脚本用了 bash 4 的 `declare -A` 和 GNU `md5sum`，
  在系统自带 bash 3.2 上直接失败，但脚本仍然打印成功——等于「验收帧里有两张一模一样」这种缺陷不会被拦住。
  现在改成可移植实现（`md5` / `md5sum` 二选一 + 明文列表比对）。
- 帧尺寸核对不再写死期望值：与已入库版本比对，避免窗口被最大化时拍出的宽屏帧把「1280 下不溢出」这类结论带偏。
- 验收帧整套在 macOS 重拍，不再「Windows 旧帧 + macOS 新帧」混着 —— 混平台的帧会让
  「1280 宽度下不溢出」这类按尺寸的结论失去意义（主窗口 1280×800 与 1280×792 差的就是平台窗口度量）。

### Known issues（已记录，待裁定）

- 冷启动实测 3.5–3.9 s，未达 <2 s 目标（当前地板约 2.35 s）。
- jlink 打包 ⛔（自动模块限制）、自动更新 🚫（无发布渠道）、签名/公证 ⛔（缺证书）。
- ~~两条长期红已归因并修好~~（2026-10-30）：`StartupTraceTest` 断言的是测试运行器的进程年龄，
  `DialogButtonOrderTest` 把宿主平台的按钮预设当成 Windows 预设 —— 见上面两段改判与修复。

### Not yet released

本条不是变更，是一句实话：**上面所有能力都还没有面向使用者的发布产物**（无安装包、无 tag、无更新通道）。
现在能用的安装方式是「拉源码本地跑」，命令见 [CONTRIBUTING.md](CONTRIBUTING.md) §1–2。
