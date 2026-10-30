# STAR-PLAN — 从「内部工程」到「有人 star 的开源产品」

> 2026-10-30 新增。回答一个问题：**AIDB 现在离「GitHub 上有人愿意 star」差什么，按什么顺序补。**
> 本文不替代内部对拍台账 `NAVICAT-TODO.md`（产品对拍真源）；本文是**发布/增长视角**，与它是两个维度。
> 结论与 Navicat 对拍规划冲突处，**显式列出冲突**，由你拍板（§7）。
>
> ⚠️ **阅读口径**：文中提到的 `NAVICAT-TODO.md`、`ROADMAP-TODOLIST.md`、`docs/internal/*`
> 是内部执行台账，**不随公开仓库发布**。这里保留提及是为了交代决策依据，不是给读者的入口 ——
> 对外文档只有 `README.md` 与 `docs/` 下其余文件。

---

## 0. 诊断：当前仓库对陌生访客的劝退链

访客在 GitHub 上停留约 30 秒，看完 4 件事决定要不要 star。逐条对照现状：

| 访客动作 | 现状 | 证据 | 判定 |
|---|---|---|---|
| ① 看能不能用 | **没有 LICENSE 文件** | `git ls-files` 无 LICENSE；`pom.xml` 无 `<license>` | ⛔ **致命**。法律上 = 保留所有权利，任何人不能合法使用/二次开发。这一条不解决，后面全部白做。 |
| ② 看长什么样 | README **只嵌入 1 张图**（128px logo），40 张实拍只在正文里当文件名罗列 | `README.md:6`；`README.md:134-149` | ⛔ 致命。DB GUI 是**视觉产品**，不给图 = 没有 star。 |
| ③ 30 秒内跑起来 | 必须 JDK 25–27 + Maven 自行编译；**零 tag、零 release、零二进制** | `git tag` 空；README 无下载链接 | ⛔ 高。99% 的访客不装 JDK 25。 |
| ④ 判断靠不靠谱 | 无 CI、无 badge、无 releases；**全中文 README** | 无 `.github/`；README 0 行英文 | ⛔ 高。项目自己的拍板 3 是「默认英文」，README 却全中文。 |

**一句话结论**：产品侧已经做到「门禁 1182 条 / 40 帧验收」的工程密度，但**发布侧是 0**。
现在的仓库是一个**极其严谨的内部项目**，不是一个**开源产品**。star 由发布侧决定，不由代码质量决定。

---

## 1. 关于「像 Navicat」这件事的风险（必须先讲）

`NAVICAT-TODO.md` 四项拍板里的第 1、4 条，是**法律风险**，不是风格问题：

- 拍板 1「完全照 Navicat：140px 顶区 + 90px Ribbon + 12 格磁贴」
- 拍板 4「强调色改回 Navicat 原值 `#0078D7`、刻意差异全部拿掉、弹窗改系统标题栏」
- `docs/screenshots/navicat/` **15 张商业软件界面截图已提交进 git**（3.5 MB）

问题：
1. **商业外观（trade dress）+ 截图版权**。Navicat 是 Premium Soft 的商业产品。把它的界面截图放进一个开源仓库、
   并把产品规划写成「和 Navicat 一模一样」，等于把「我们抄它」写在首页第二个文档上。
   讽刺的是同文档 `NAVICAT-TODO.md:12` 自己写了正确口径：「不提取 Navicat 原始位图 —— 他人版权素材」。
   **原则对了，仓库里的图和标题违背了它。**
2. **star 天花板**。GitHub 上「XX 开源克隆」类项目的 star 曲线在达到原版功能面后**就停止**，
   因为访客的理由是「等原版免费」，而不是「这个更好」。纯复刻永远排在原版下面。

**建议裁定（不推翻工作，只改叙事）**：
把 Navicat 从「**复制目标**」降级为「**交互基准线**」—— 交互逻辑（对象树→双击表→数据页→编辑保存）照学，
这是通用范式，无风险；视觉资产（配色/磁贴几何/图标风格）**保持自有设计系统**，`#0078D7` 换成项目自己的强调色。
仓库内的 Navicat 截图移出 git（见 §3 B-2）。**功能对标继续做，像素复刻收手。**

---

## 2. 真正能带来 star 的东西（按访客心理排序）

### 2.1 差异化定位 —— 你已经有，但没说出来

`docs/GITHUB-COMPARE.md` §2.1 已经写出正确答案，只是没进 README：

> **纯原生 JavaFX、不内嵌 WebView、不启 HTTP 端口** —— 这是开源对面几乎没有的组合。

这是 2026 年最有卖点的技术立场。Electron DB 客户端（Beekeeper / TablePro / Chat2DB 桌面版）
内存占用 300–600 MB 是公开槽点；DBeaver 是 Eclipse RCP、又重又老。**「Java 原生桌面 + 不塞浏览器 + 不开端口」**
对 Java 后端人群（全球最大的数据库 GUI 用户群之一）是精准打击。

**定位句建议**（英文优先，直接进 README 首屏）：
> A native desktop database client — **no Electron, no embedded WebView, no local HTTP server**.
> Java 25 + JavaFX 25, ~X MB RSS, and every write gated by a SQL preview + single transaction + undo.

### 2.2 你独有的三个卖点（竞品都没有，且已被门禁证明）

| 卖点 | 现状证据 | 竞品 | 为什么能带 star |
|---|---|---|---|
| **写操作安全三层**：SQL 预览 → 单事务 → 反向撤销 Toast；危险分级 fail-closed 分类 + 两级确认 | README:62、:102；`EditSqlBuilder` / `Danger` / `ErrorClassifier` | Navicat 无撤销；DBeaver 有 confirm 无 undo；Beekeeper 无 | 这是**所有人误删数据的那一下恐惧**。做成 GIF 首屏，比任何功能表都有说服力。 |
| **可复现视觉验收**：40 张真实窗口帧 + `shots.sh` + 像素量具 + 对比度门禁 + 102 令牌单一真相 | `scripts/shots.sh`、`ContrastTest`、`TokenParityTest` | 无一家这么做 | 招贡献者的最强信号。开源 UI 项目最大的贡献障碍是「我改了不知道会不会坏」。 |
| **设计令牌驱动的密度/双主题** | `tokens.json` → `Tokens.java` + theme CSS | DBeaver 无；Beekeeper 有主题无密度档 | 差异化外观，且是可写文章的技术点。 |

### 2.3 功能面：能带 star 的 vs 不能带 star 的

判据：**「多一个人会因为缺它而 star 吗」**。对照你现有的 N 系列 / R 系列条目：

**会带 star（用户价值，与像不像 Navicat 无关）**
- **Oracle / SQL Server / MariaDB 三个方言** —— ⭐⭐⭐ **这是第一优先级，高于本文其余一切功能项。**
  实测当前只有 `SqliteDialect` / `MySqlDialect` / `PostgresDialect`（`workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/`），
  `pom.xml` 无 ojdbc / mssql-jdbc / mariadb-java-client。
  **DB GUI 的第一筛选条件就是「支持我的库」**。企业 Java 世界的默认库是 Oracle 和 SQL Server；
  缺这两个，目标用户看完 README 直接走。而且你有 `Dialect` 抽象层，边际成本可控。
- **N32 库级树**（连接→库→五组）—— 多库/多 schema 用户的基本可用性，MySQL/PG 现在很难受。真功能。
- **N31/D-13 Filter & Sort 内联带** —— 从弹窗改成网格内联生成器，是真 UX 提升（Navicat 这点确实好）。
- **Data Generation（造测试数据）** —— Navicat 收费版功能，你做了就是**独家免费**。开发者刚需，带 star。
- **Data Masking（脱敏导出）** —— 合规刚需，同上。
- **SSH 隧道** —— 现在连接向导里「明说未实现」（README:101）。真实用户连生产库的第一障碍。带 star。

**不会带星（视觉复刻，做完 star 不变）**
- N5/N6 顶区 140px + 90px Ribbon + 12 格磁贴逐像素对拍（R36，5–7 人日）
- N17 行内编辑盒/列头 tint 的像素级配色对拍
- N26 弹窗改系统标题栏（这是**放弃**自己的设计系统去贴 Navicat，方向反了）
- N34 Ribbon 的 Automation / BI / User 三面（R42，36–56 人日的主体）

> 这不是说 R35 的彩色图标没价值 —— **图标和配色直接决定首屏 GIF 好不好看**，而 GIF 决定 star。
> 所以 R35/R36 里**「让界面更好看」的部分保留**，**「和 Navicat 逐像素一致」的部分降级**。

---

## 3. 文档与仓库清理（你问的「去掉大模型遗留的文档」）

审计结果：13 个 md 文件，其中 5 个是会话产物或自declare作废。判定表：

| 文件 | 大小 | 判定 | 处理 |
|---|---|---|---|
| `NAVICAT-TODO.md`（**根目录**） | 51.7 KB | 访客看到的第二个文件，内容是 sprint 战报：「用户令…」「push 仍未授权」+ 19 个 commit hash | **移出版本库** → `docs/internal/`（不进 git），或迁 GitHub Discussions |
| `docs/ROADMAP-TODOLIST.md` | 37.0 KB | 同上，`:29` 「**push 仍未授权。**」 | 同上 |
| `docs/EXEC-SUMMARY.md` | 25.9 KB | `:3` 自己写「⚠️ **历史快照**…现行结论一律以该文档为准」 | **删除** |
| `docs/ARCHIVE-R30-R33.md` | 16.9 KB | 标题自己写「**不再作为规划依据**」 | **删除**（git 历史可查） |
| `docs/GITHUB-COMPARE.md` | 5.3 KB | 竞品扫描，2026-10-22，有参考价值 | 保留，移到 `docs/research/`，**必须更新**（§2.1 定位句来源） |
| `docs/UI-VISUAL-SPEC.md` | 75.1 KB | 是真规格书，但内部轮次口吻 | 保留 → `docs/design-system.md`，删掉轮次叙事 |
| `docs/ARCHITECTURE.md` / `UI-GUIDE.md` / `UI-FORMS-AND-STATES.md` / `COMPONENT-SELECTION.md` | — | 真文档 | 保留 |
| `docs/screenshots/navicat/*.png` | 3.5 MB / 15 张 | 商业软件截图 | **移出 git**（`git rm --cached` + 加 ignore），对拍结论留文字 |
| `tools/Crop.java` `Find.java` `Pixel.java` | — | 自己注释写「**临时探针**」 | 移 `tools/probes/` 并在 README 不提；或直接删 |
| `.workbuddy/memory/` | — | AI 会话记忆，**已被 gitignore，未入 git** | 不用动 |

**必须修的 README 内部矛盾**（D-4 的实体）：同一个 README 里
`:15` 「964 条 / 123 类（2026-10-26）」和 `:116` 「1108 条 / 134 类（2026-10-28）」并存，
而 `NAVICAT-TODO.md:454` / `ROADMAP-TODOLIST.md:12` 是 **1182 条 / 134 类（2026-10-30）**。
**README 里只留一个「当前门禁」数字 + 日期，且必须是回量出来的（照 `NAVICAT-TODO.md` §8 的量法）。**
门禁明细表 100 行（README:33-114）整块搬走 → `docs/status.md` 或直接删，README 只留 8–12 个能力面。

### 3.2 已执行（2026-10-30 本轮）

- `git mv` → `docs/internal/` 后 `git rm --cached`：`NAVICAT-TODO.md`、`ROADMAP-TODOLIST.md`
  **停止跟踪但保留本地**（52KB + 37KB 战报不再出现在公开仓库），`.gitignore` 加 `docs/internal/`。
- `git rm`：`docs/EXEC-SUMMARY.md`（自declare历史快照）、`docs/ARCHIVE-R30-R33.md`（自declare不再作规划依据）。
- 新增公开文档：`docs/ROADMAP.md`（支持矩阵 + 进行中 + 接下来 + 明确不做）、`docs/STAR-PLAN.md`（本文）。
- 全仓库死链清理：README / `docs/ARCHITECTURE.md` / `UI-GUIDE.md` / `UI-VISUAL-SPEC.md` /
  `UI-FORMS-AND-STATES.md` / `GITHUB-COMPARE.md` / `scripts/package.sh` / `StyleClassCoverageTest` javadoc
  里 20+ 处指向已停止跟踪或已删除文档的引用全部改指公开文档或标注「内部材料，不随公开仓库发布」。
  ✅ 已核：`ui/adb-ui-core` 编译与测试编译通过（门禁不读 `docs/`，无断言受影响）。
- **README 未重写**（按 C-2 排在 R35 之后），本轮只修死链与两处过时指针（:19 / :223）。

### 3.1 README 重写结构（英文优先，可直接照此写）

```
1  标题 + 一句话定位（§2.1 那句：no Electron / no WebView / no HTTP port）
2  1 张 hero 截图（docs/screenshots/README/hero-light.png，全宽，1280+）
3  Badge 行：License / CI / JDK / Platform (macOS·Windows·Linux) / Release
4  4–6 张 GIF 或图：写安全三层 / 数据网格编辑 / 查询编辑器 / 结构页 / 双主题
5  Who it's for + 支持矩阵（表：SQLite/MySQL/PG = 支持；Oracle/MSSQL = 路线）
6   Install：⭐ Releases 下载（Win .msi / macOS .dmg / Linux .deb）+ Docker/源码两条兜底
7  Feature highlights —— 按能力面，不按组件名（现在 README 是反例：表格全是 `AppShell`/`SideBar` 类名）
8  Why different —— 三行对照 DBeaver / Beekeeper / Navicat（来源 docs/GITHUB-COMPARE.md）
9  安全模型（三层门禁 + 凭据进系统钥匙串/凭据库，这是差异化章节，值得单独一节）
10 Keyboard & Accessibility（⌘K、六区焦点）
11 截图画廊（折叠在 <details> 里）
12 开发（scripts/dev.sh / build.sh 四条命令）
13 工程约束（现在 §工程约束 那 9 条是**贡献者材料** → 移到 CONTRIBUTING.md）
14 中文段落（简短版 + 「完整文档见 docs/」）
15 License / Acknowledgements（Lucide ISC、RichTextFX、ControlsFX…）
```

**关键**：README 从 328 行降到 **120–160 行**，内部术语（门禁/批次/棘轮/帧/R33/D-11）**全部清零**。
这些词一个都不该出现在首页。

---

## 4. 发布工程（把「能跑」变成「能下载」）

| 项 | 状态 | 动作 | 工作量 |
|---|---|---|---|
| LICENSE | ⛔ 无 | 选 **Apache-2.0**（对 Java 生态最友好，与 RichTextFX/ControlsFX/HikariCP 兼容；GPL 会限制商用集成）+ 同步 `pom.xml` 的 `<licenses>` | 0.5 天 |
| CI | ⛔ 无 | `.github/workflows/ci.yml`：三平台 matrix × `./mvnw -B clean verify` + `java tools/TestCount.java`。**README 里那句「等价于 CI 跑的那条命令」现在是真的没有 CI** | 1–2 天（TestFX/Monocle 无头在 Linux runner 上要调） |
| Release + 二进制 | ⛔ 零 tag | `.github/workflows/release.yml`：tag 触发 → `scripts/package.sh` × 3 平台 → 上传 4 个安装包。**注意 jpackage 不能交叉编译，要 3 个 runner** | 2–4 天 |
| 版本与 CHANGELOG | ⛔ 无 | semver + `CHANGELOG.md`（Keep a Changelog 格式），从 commit 历史人工整理首版 | 1 天 |
| 代码签名 | ⛔ 缺证书（`NAVICAT-TODO` §8 边界维持） | **不阻塞首发**。macOS 未公证 = Gatekeeper 拦截 → README 明写「右键打开」或首发只上 Win/Linux，macOS 走源码。这是个诚实说明，不是死按钮，符合你的诚实条款 | 外部资源 |
| dependabot / SECURITY.md / CONTRIBUTING / issue 模板 | ⛔ 无 | 各 1 个文件 | 1 天 |

> **判断**：这一节的 10 人日，对 star 的贡献 **大于** R36–R42 的 100+ 人日。

---

## 5. UI/UX 该往哪走

`docs/UI-VISUAL-SPEC.md`（891 行）+ 102 令牌 + 对比度门禁 = 你已经有**设计系统**，这是稀缺点，别扔掉。

三条建议：
1. **R35（彩色图标 + 暗色档）优先做完并出首屏图** —— 图标和配色决定「截图好不好看」，
   这是唯一直接进 star 转化的视觉工作。P3（暗色配彩色图标必须重做）已在 `NAVICAT-TODO` §6 拍板为「要」，正确。
2. **R36 只保留「更清爽」的部分，不追平 140px 顶区** —— 现状 7 区 + 6 格磁贴比 Navicat 的
   140px 三区更省纵向空间，对 1280×800 笔记本**是优点**。逐像素贴 Navicat 会让你的窗口在small laptop 上更难看，
   而访客截图多半就是 1280×800。建议：留「页签行只放页签 + 标题格式 + `#0078D7`→自有强调色」，
   砍「140px 顶区 + 12 格磁贴」。（这条与拍板 1 直接冲突，见 §7 C-1）
3. **把「首屏 30 秒体验」当成一个 UX 条目来做**（现在整个规划里没有这条）：
   - 无连接状态下的引导（现在依赖演示库自动生成 —— 好，保留）
   - 首次启动到「看见数据」的点击数：目标 **≤2 次**
   - 空白态 E5–E9 至今未做（`docs/UI-FORMS-AND-STATES.md` 清单里）—— 空白态是访客**第一眼**看到的界面，
     优先级不该低于任何对拍条目。

---

## 6. 执行顺序（按 2026-10-30 裁定 C-2 / C-3 排定）

> **裁定后的实际排法**：R35（彩色图标）→ 阶段 A（发布工程，**R35 收官批同会话启动，不得再拖**）
> → Oracle/SQL Server/MariaDB（D 系列，C-3）→ 其余阶段 B/C。
> 阶段 A 内部条目之间无依赖，可与 R35 并行插入（LICENSE / CI / 模板属纯配置，不碰 UI 代码）。

### 阶段 A · 「能被人用」—— 5–7 人日，**做完才有 star 的前提**
1. LICENSE（Apache-2.0）+ `pom.xml` `<licenses>/<scm>` —— 0.5 天，**今天就做**
2. 仓库清理（§3 表：删 2 个作废文档、根目录 2 个战报文档出 git、Navicat 截图出 git） —— 1 天
3. README 全量重写（§3.1 结构，英文优先 + 中文段） + hero 截图 + 6 张图/GIF —— 2 天
4. CI（三平台 `verify` + TestCount）+ badge —— 1–2 天
5. 修 README 内部数字矛盾（D-4），只留一个回量数字 —— 0.5 天
6. CONTRIBUTING / SECURITY / CHANGELOG / issue 模板 / dependabot —— 1 天

### 阶段 B · 「有人愿意留」—— 10–15 人日
7. ~~**Oracle + SQL Server + MariaDB 方言**~~ → **按 C-3 提前，插在 R36 与 R37 之间（D 系列，见 §7 / 内部 roadmap D-0…D-6）**；
   D-4 会牵出 C-6 的许可证问题（MariaDB 驱动 LGPL-2.1）
8. Release workflow + 三平台安装包 + git tag v0.1.0
9. SSH 隧道落地（现在是「如实说明未实现」）
10. N32 库级树 + N31 Filter/Sort 内联带 + 空白态 E5–E9
11. R35 彩色图标 + 暗色档（与 3 的 hero 图协同，可并行）

### 阶段 C · 「有独家卖点」—— 选做，按数据
12. Data Generation（造测试数据）—— 独家免费，带 star
13. Data Masking（脱敏）—— 合规，带 star
14. ER 图（`DBeaver` 借鉴项，你原计划「不做」—— 值得重新评估，因为它是 DB GUI 的**传播性**功能，用户会截图发推）
15. 一篇技术文（"Why we built a DB client without Electron"）投 HN / r/java —— **这是 star 的最大外部来源**，成本远低于任何一轮开发

**阶段 A 结束即可公开**。阶段 B 期间不要公开宣传（没二进制 = 转化率为 0）。

---

## 7. 拍板记录（2026-10-30 已裁定）

| 编号 | 问题 | 裁定 | 已落地 |
|---|---|---|---|
| **C-1** | 是否继续「完全照 Navicat 像素」路线（拍板 1+4：140px 顶区 / 12 格磁贴 / `#0078D7` / 系统标题栏）？ | ✅ **维持完全照 Navicat**。R36 按原规划执行，本文 §1 的降级建议**不采纳**。 | 路线不变；`docs/internal/ROADMAP-TODOLIST.md` R35 段已记此裁定 |
| **C-2** | R35（彩色图标）vs 阶段 A（发布工程）谁先？ | ✅ **R35 先做**，做完立刻用新图标帧补 README hero 图。⚠️ 约束：**阶段 A 不得再往后拖**，R35 收官批同一会话内启动 A-1（LICENSE）。 | 已记 |
| **C-3** | Oracle / SQL Server / MariaDB 进不进本版？ | ✅ **进，优先级高于 R37–R42 全部**，插为 D 系列（R36 之后、R37 之前）。 | `docs/internal/ROADMAP-TODOLIST.md` 新增 D-0…D-6；`docs/ROADMAP.md` 列为「接下来」第 1 项 |
| **C-4** | 内部战报文档（`NAVICAT-TODO` / `ROADMAP-TODOLIST`）去哪？ | ✅ **移 `docs/internal/` 并 gitignore**。 | ✅ **已执行**（见 §3.2） |
| **C-5** | 首发平台（无 Apple 公证 → Gatekeeper 拦截） | ⏳ 待裁定（建议：首发 Win + Linux 安装包，macOS 给源码 + 诚实说明） | 未动 |
| **C-6** | 许可证 | ⏳ 待裁定（建议 Apache-2.0）⚠️ **与 D-4 相关**：MariaDB 驱动是 **LGPL-2.1**，若选 Apache-2.0 需确认 LGPL 动态链接口径；Oracle ojdbc11 = Unicode-3.0（可兼容），mssql-jdbc = MIT。 | 未动 |

### ⚠️ C-1 维持原路线后，仍留一条待裁定的独立项

「像素复刻」与「素材入库」是两件事。C-1 裁的是前者；后者是：
`docs/screenshots/navicat/` 里 **15 张 Navicat 商业软件截图目前在 git 中**（3.5 MB），
而 `docs/internal/NAVICAT-TODO.md:12` 自己写的口径是「不提取 Navicat 原始位图 —— 他人版权素材」。
**原则与仓库现状仍冲突**（对拍证据文字化即可，不需要把原图发布出去）。
建议：截图留本地做量具输入、加 gitignore、从 git 移除 —— **等你单独裁定，本轮未动。**


---

## 8. 反模式（明确不要做）

1. **不要为了 star 加 AI/LLM 面板**。你已拍板不做 AI，且这是当前唯一让你与 Chat2DB 分开的立场。
   加了就变成第 N 个「AI SQL 助手」，差异化归零。
2. **不要做 VS Code 插件**。会把你从「桌面原生」这个独占位上拽进 SQLTools 已经占红的赛道，且丢掉全部视觉资产。
3. **不要把 R42 的 Automation / BI / User 三面当成对拍必做**。`NAVICAT-TODO` §6 P1 的建议（View/Backup/Model 先做，
   Automation/BI/User 暂缓 + 如实说明页）是对的，照它执行。
4. **不要在 README 出现**：门禁条数、批次号、棘轮、帧数、R## / D-## / N## 编号、commit hash、
   「push 需授权」。这些是内部治理语言，对访客是噪音，且是「AI 代写文档」最明显的指纹。
5. **不要边做发布边继续对拍**。两条线并行会让两边都收不了口。阶段 A 一周内关掉。

---

## 9. 相关文档

- `NAVICAT-TODO.md` — 内部对拍台账（产品对拍真源，**不随公开仓库发布**；本文不改它的功能结论，只改发布/叙事优先级）
- `docs/GITHUB-COMPARE.md` — 开源竞品扫描（§2.1 定位句的来源；需按 C-2 更新）
- `docs/UI-VISUAL-SPEC.md` / `docs/UI-FORMS-AND-STATES.md` — 设计系统与形态裁定（阶段 A 的 README 视觉素材来源）
- `docs/ARCHITECTURE.md` — 架构（保留，README 指向它）
- `scripts/package.sh` — 阶段 B 的 Release workflow 复用它，不要重写打包逻辑
