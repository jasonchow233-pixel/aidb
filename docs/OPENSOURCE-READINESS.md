# AIDB 开源准备度分析报告

> 生成日期：2026-11-24（本地 Docker 已开启，完整测试已通过）  
> 基于：`docs/RELEASE-PLAN.md`、`docs/STAR-PLAN.md`、`docs/TESTING-GUIDE.md`、`docs/STATUS.md`

---

## 一、测试现状：全部绿

### 1.1 完整运行结果（2026-11-24）

本地 Docker 已开启，运行了完整的 `./scripts/build.sh`（JDK 25.0.5，macOS 15.7.9）：

| 指标 | 数值 |
|---|---|
| 构建结果 | **BUILD SUCCESS** |
| 模块 | **16/16 SUCCESS**（含之前缺 Docker 的 `adb-redis`） |
| 测试执行 | **2153 条** |
| 测试失败 | **0 条** |
| 测试跳过 | 133 条（环境门控：WinCred、真库矩阵、OSS/Kodo 真账号等） |
| 测试类 | 235 个 |
| 总耗时 | 29 分 38 秒 |
| 用例计数门禁 | ✅ 通过（2153 ≥ 300） |

逐模块执行数：
- `adb-ui-shell` 1173 · `adb-ui-core` 70 · `adb-safety` 88 · `adb-sql` 42 · `adb-model` 30
- `adb-fixtures` 3 · `adb-editing` 39（跳 15） · `adb-objectstore` 39（跳 20）
- `adb-asset` 18 · `adb-introspect` 308（跳 19） · `adb-redis` 18 · `adb-session` 42（跳 5）
- `adb-transfer` 115 · `adb-jdbc` 168（跳 74）

### 1.2 测试四层（已完备）

| 层 | 框架 | 覆盖内容 | 示例 |
|---|---|---|---|
| 单元 | JUnit 5 + AssertJ | EditBuffer、EditSqlBuilder、SqlSplitter、StructureSqlBuilder、ConnectionStore | `EditSqlBuilderTest`、`SqlSplitterTest` |
| 数据（真库契约）| JUnit 5 + 嵌入式 SQLite | 演示库固定种子、分页/排序/筛选 SQL、保存/反向撤销 | `TransferRoundTripTest` |
| UI | TestFX + Monocle 无头 | 外壳、标签、数据页、查询页、结构页、连接向导 | `QueryPageTest`、`DataGridPageTest` |
| 样式/图标 | JUnit 5 | TokenParityTest、ContrastTest、IconAuditTest | `TokenParityTest`、`ContrastTest` |

### 1.3 如何测试更好（改进建议）

现有测试体系已经很扎实，但对外开源后，社区贡献者的测试体验可进一步提升：

| 改进项 | 现状 | 建议 | 优先级 |
|---|---|---|---|
| **CI 跨平台测试矩阵** | ci.yml 只在 ubuntu-latest 跑全量门禁；macOS/Windows 只做编译冒烟 | 在 macOS 和 Windows runner 上也跑 `./scripts/build.sh --quick`（不含 Docker 依赖的测试），至少验证 UI 测试在非 Linux 上不崩 | 高 |
| **Docker 矩阵入 CI** | 真库测试（MySQL/PG/MariaDB/SQL Server/Oracle）仅在本地手动运行 | 将 `scripts/dbmatrix.sh up` + 相关契约测试加入 CI，作为可选 job（fail-fast=false），避免本地绿、CI 红 | 高 |
| **测试并行化** | 单机串行，约 30 分钟 | CI 上可按模块拆分并行 job，缩短反馈循环 | 中 |
| **截图验收自动化** | `scripts/shots.sh` 仅在本地手动运行，需人工目视核对 | CI 上跑尺寸门禁 + md5 去重，拒绝假帧；真服务端帧需 Docker 矩阵 | 中 |
| **覆盖率报告** | 无 JaCoCo / 覆盖率门禁 | 增加 `jacoco-maven-plugin`，设置核心模块（`adb-sql`、`adb-safety`、`adb-editing`）覆盖率下限 | 中 |
| **变异测试** | 无 Pitest / 变异验证 | 对 `DangerClassifier`、`EditSqlBuilder`、`SqlSplitter` 等安全关键类引入变异测试，证明门禁能捕获回归 | 中 |
| **容器镜像预热** | 每次 CI 都从零拉取 redis:7.2 / mysql:8.4 等镜像 | CI 缓存 Docker 镜像，或使用 GitHub Container Registry 预置镜像 | 低 |

---

## 二、README 缺口分析

当前 README 已有 337 行，中英双语，内容详实，但对照 `docs/STAR-PLAN.md` §3.1 的开源首屏结构，仍有以下缺口：

### 2.1 现有内容（已做得好）

| 项目 | 现状 |
|---|---|
| 一句话定位 | ✅ "Java 25 + JavaFX 25. No Electron, no embedded WebView, no local HTTP server." |
| License/CI/JDK/Platform/Status badge | ✅ 已有 5 枚徽章 |
| Hero 截图 | ✅ 已嵌入 `p0-shell.png`（全宽） |
| Who it's for | ✅ 已写 |
| 数据库支持矩阵 | ✅ 已写（SQLite/MySQL/PG/MariaDB/SQL Server/Oracle/Redis/对象存储） |
| 安全模型 | ✅ 已写（三层门禁、凭据进系统钥匙串） |
| 键盘与可访问性 | ✅ 已写（⌘K、六区焦点） |
| 为什么存在（对比 DBeaver/Beekeeper/Navicat）| ✅ 已写 |
| 明确不做 | ✅ 已写 |
| 安装（源码构建） | ✅ 已写 `./scripts/dev.sh` |
| 开发命令 | ✅ 已写 `build.sh`/`dev.sh`/`shots.sh`/`package.sh` |
| 中文简述 | ✅ 已写 |
| License/致谢 | ✅ 已写 |

### 2.2 缺失内容（对照 STAR-PLAN §3.1）

| 缺项 | 说明 | 优先级 |
|---|---|---|
| **英文优先结构** | 当前 README 以中文为主，STAR-PLAN 建议英文优先 + 简体中文段 | 高 |
| **Release 下载链接** | 没有 Releases 页链接，没有 `.msi`/`.dmg`/`.deb` 下载入口 | 高（阶段 B 才有） |
| **Feature highlights（按能力面）** | 当前表格多按类名（AppShell/SideBar 等内部术语），STAR-PLAN 要求按能力面组织，且门禁条数/批次号/棘轮/R##/D-## 编号不得出现首页 | 高 |
| **Why different 三行对照** | 已有，但可更精炼 | 中 |
| **安全模型单独章节** | 已有，但可突出为差异化卖点 | 中 |
| **截图画廊（<details> 折叠）** | 已有部分截图，但可按 STAR-PLAN 结构重组 | 中 |
| **仅 120–160 行** | 当前 337 行，需精简 | 高 |
| **内部术语清零** | README 中仍可能残留 "门禁/批次/棘轮/帧/R33/D-11" 等内部治理语言 | 高 |

### 2.3 README 重写建议结构（120–160 行）

```
1. 标题 + 一句话定位（no Electron / no WebView / no HTTP port）
2. 1 张 hero 截图（全宽，1280+）
3. Badge 行：License / CI / JDK / Platform / Release
4. 4–6 张 GIF 或图：写安全三层 / 数据网格编辑 / 查询编辑器 / 结构页 / 双主题
5. Who it's for + 支持矩阵表（SQLite/MySQL/PG = 支持；Oracle/MSSQL = 路线）
6. Install：Releases 下载（Win .msi / macOS .dmg / Linux .deb）+ 源码两条兜底
7. Feature highlights —— 按能力面，不按组件名
8. Why different —— 三行对照 DBeaver / Beekeeper / Navicat
9. 安全模型（三层门禁 + 凭据进系统钥匙串，单独一节）
10. Keyboard & Accessibility（⌘K、六区焦点）
11. 截图画廊（折叠在 <details> 里）
12. 开发（scripts/dev.sh / build.sh 四条命令）
13. 中文段落（简短版 + 「完整文档见 docs/」）
14. License / Acknowledgements
```

---

## 三、开源还缺什么（全面清单）

对照 `docs/RELEASE-PLAN.md` 阶段 A 和 `docs/STAR-PLAN.md`，完整缺项如下：

### 3.1 法律与合规（阶段 A-1，已就绪）

| 项 | 现状 | 缺口 |
|---|---|---|
| LICENSE | ✅ Apache-2.0 已存在 | 无 |
| NOTICE | ✅ 已存在（含第三方许可） | 无 |
| pom.xml `<licenses>`/`<scm>` | ✅ 已配置 | 与 LICENSE/NOTICE 三项需保持一致（已核对） |
| SECURITY.md | ✅ 已存在 | 无 |
| CONTRIBUTING.md | ✅ 已存在 | 无 |
| CODEOWNERS | ❌ 缺失 | 建议添加，明确 PR 审核责任人 |

### 3.2 发布工程（阶段 A-4 / B-2）

| 项 | 现状 | 缺口 |
|---|---|---|
| CI 工作流 | ✅ `.github/workflows/ci.yml` 已存在（Linux 全量 + macOS/Windows 编译冒烟） | 缺 macOS/Windows 全量测试；缺 Docker 矩阵入 CI |
| Release 工作流 | ✅ `.github/workflows/release.yml` 已存在（tag v* → 三平台未签名包 → draft release） | 未签名、未公证、无自动更新 |
| 代码签名 | ❌ 缺 Apple Developer ID / Windows 代码签名证书 | macOS Gatekeeper 会拦截未签名 .dmg；README 需诚实说明 |
| 公证（Notarization） | ❌ 缺 | 同上 |
| 自动更新 | ❌ 缺 | 无发布渠道，用户需手动下载新版 |
| git tag / Release | ❌ 零 tag | 需打首个 `v0.1.0` |

### 3.3 社区基础设施（阶段 A-5）

| 项 | 现状 | 缺口 |
|---|---|---|
| ISSUE_TEMPLATE | ✅ bug_report.yml / feature_request.yml / config.yml 已存在 | 无 |
| pull_request_template.md | ✅ 已存在 | 无 |
| dependabot.yml | ✅ 已存在 | 无 |
| CHANGELOG.md | ✅ 已存在 | 需从 commit 历史人工整理首版 |
| 代码签名策略文档 | ❌ 缺 | 需写明未签名时的安装限制（macOS 右键打开） |

### 3.4 商标与资产（阶段 B / P3-R19）

| 项 | 现状 | 缺口 |
|---|---|---|
| `icons/brand/` 商标 | ⚠️ 五枚中性几何占位件（redis/aws-s3/aliyun-oss/qiniu/tencent-cos） | **发布前必须换成各家品牌页原始矢量件**；`TRADEMARK-NOTICE.txt` 已记来源与校验强度，但当前状态 = "自用可用、发布未清账" |
| Navicat 商业截图 | `docs/screenshots/navicat/` 15 张已提交 git（3.5 MB） | **必须移出 git**（版权风险），对拍结论留文字，不发布原图 |

### 3.5 文档（已较完备）

| 项 | 现状 |
|---|---|
| ARCHITECTURE.md | ✅ 模块、数据流、线程模型 |
| ROADMAP.md | ✅ 对外路线（支持矩阵/进行中/接下来/明确不做） |
| STATUS.md | ✅ 验证基线数字与完整限制 |
| UI-VISUAL-SPEC.md / UI-GUIDE.md / UI-FORMS-AND-STATES.md | ✅ 设计系统 |
| COMPONENT-SELECTION.md | ✅ 组件选型与许可 |
| RELEASE-PLAN.md | ✅ 新增（本次） |
| SCREENSHOT-GUIDE.md | ✅ 新增（本次） |
| TESTING-GUIDE.md | ✅ 新增（本次，已更新基线） |

---

## 四、阶段 A 执行 Checklist（可立即开始）

| # | 任务 | 命令/产出 | 预估 |
|---|---|---|---|
| A-1 | 核对 LICENSE / NOTICE / pom.xml 三项许可一致性 | 已核对一致 | 0.5 天 |
| A-2 | 仓库清理：`git rm --cached docs/screenshots/navicat/`，加入 `.gitignore`；`docs/internal/` 已 gitignore | 移出 Navicat 商业截图 | 1 天 |
| A-3 | README 重写（英文优先 + 中文段，120–160 行，hero 截图 + 6 张功能图） | 参考 STAR-PLAN §3.1 | 2 天 |
| A-4 | CI 三平台全量测试（macOS/Windows 加 `./scripts/build.sh --quick`） | 修改 `.github/workflows/ci.yml` | 1–2 天 |
| A-5 | CONTRIBUTING / CHANGELOG / issue 模板 / dependabot 补齐 | 已有部分，需首版 CHANGELOG | 1 天 |
| A-6 | 补 CODEOWNERS | 新建文件 | 0.5 天 |
| A-7 | 打首个 `v0.1.0` tag → release.yml 出三平台未签名包 | 需另行授权打 tag | — |

**阶段 A 完成后即可公开仓库。** 阶段 B 期间不要公开宣传（没二进制 = 转化率为 0）。

---

## 五、下一步建议

**本周可立即执行：**

1. **运行完整测试已完成**（16/16 绿，2153 用例），测试基线已写入 `docs/TESTING-GUIDE.md`。
2. **执行 A-1/A-2**：核对许可一致性 + 移出 Navicat 商业截图（今天可做）。
3. **执行 A-3**：按 STAR-PLAN §3.1 结构重写 README 为 120–160 行英文优先版（2 天）。
4. **执行 A-4**：将 macOS/Windows 全量测试加入 CI（1–2 天）。

需要我直接撰写 README 重写草稿，或把阶段 A 拆成可执行的 GitHub Issue 模板，随时告诉我。
