# 更新日志

本文件记录 AIDB（AgenticDB）面向使用者的变更。

- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
- **当前状态：尚未发布任何正式版本。** 仓库版本是 `1.0.0-SNAPSHOT`，没有任何 git tag。
  首个 tag 会在 macOS 公证 / 签名与分发渠道确定后切（见 [docs/ROADMAP.md](file:///Users/jason/Documents/GitHub/aidb/docs/ROADMAP.md)）。
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

- **功能区图标尺寸维持 26px**（原计划放大到 30px）。改判理由：Navicat 17 实测里，那一排大图标是全界面最彩的部分，
  需要的是彩色档而不是更大的单色图标；30px 留到 R36 重做顶区布局时一起判。
  见 [docs/UI-VISUAL-SPEC.md](file:///Users/jason/Documents/GitHub/aidb/docs/UI-VISUAL-SPEC.md) §4.5。
- 对象类型色回到 `edge`（轮廓）层，`tone` 三层保持中性——同一个对象只保留一处颜色来源。
- 视觉文档与实现对齐：设计令牌数校正为 106（68 色 / 35 尺寸 / 3 时长），移除不存在的 `border-strong` 记录，
  图标台账改为 55 枚（53 枚 Lucide + 2 枚自绘）。

### Fixed

- `scripts/shots.sh` 的**帧去重门禁在本机（macOS）从来没真正跑过**：脚本用了 bash 4 的 `declare -A` 和 GNU `md5sum`，
  在系统自带 bash 3.2 上直接失败，但脚本仍然打印成功——等于「验收帧里有两张一模一样」这种缺陷不会被拦住。
  现在改成可移植实现（`md5` / `md5sum` 二选一 + 明文列表比对）。
- 帧尺寸核对不再写死期望值：与已入库版本比对，避免窗口被最大化时拍出的宽屏帧把「1280 下不溢出」这类结论带偏。

### Known issues（已记录，待裁定）

- `StartupTraceTest` 有顺序依赖：全量跑偶发红，单跑 4/4 绿。
- `DialogButtonOrderTest` 的部分断言按 Windows 平台构造，macOS 上环境门控跳过。
- 冷启动实测 3.5–3.9 s，未达 <2 s 目标（当前地板约 2.35 s）。
- jlink 打包 ⛔（自动模块限制）、自动更新 🚫（无发布渠道）、签名/公证 ⛔（缺证书）。

### Not yet released

本条不是变更，是一句实话：**上面所有能力都还没有面向使用者的发布产物**（无安装包、无 tag、无更新通道）。
现在能用的安装方式是「拉源码本地跑」，命令见 [CONTRIBUTING.md](file:///Users/jason/Documents/GitHub/aidb/CONTRIBUTING.md) §1–2。
