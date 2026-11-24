# RELEASE-PLAN — AIDB 对外开源与首发计划

> **状态**：内部工程已就绪（P0–P6 全绿，2260 条用例 / 235 测试类 / 16 模块，`./scripts/build.sh` 全绿）；发布侧（README、首屏图、二进制、签名策略）尚未收口。  
> 本文是对外开源的**执行总纲**，按“先能被人用 → 再有人愿意留 → 最后有独家卖点”三阶段排期，并与 `docs/STAR-PLAN.md`、`docs/ROADMAP.md`、`docs/STATUS.md` 保持口径一致。

---

## 0. 一句话定位（首屏用）

> A native desktop database client — **no Electron, no embedded WebView, no local HTTP server**.  
> Java 25 + JavaFX 25, every write gated by SQL preview + single transaction + undo.

差异化核心（开源对面几乎没有的组合）：**纯原生 JavaFX + 不内嵌浏览器 + 不开本地端口 + 写操作三层安全门禁 + 设计令牌驱动的双主题/四档密度 + 真库契约验收**。

---

## 1. 现状清单（2026-11-16 基线）

| 维度 | 现状 | 证据 |
|---|---|---|
| 架构 | 4 层分层（core → workbench → ui → bootstrap），方言抽象层，每连接单线程执行器 | `docs/ARCHITECTURE.md` |
| 数据库 | SQLite / MySQL 8.x / PostgreSQL 16 / MariaDB 10~11 / SQL Server 2019+ / Oracle 12c+ | `docs/ROADMAP.md` §已支持 |
| 扩展族 | Redis（含 SSH 隧道）、对象存储 S3/OSS/Kodo/COS（渐进分页） | `docs/ROADMAP.md` |
| 测试 | 2260 执行 / 0 失败 / 26 跳过 / 235 类，16/16 模块 SUCCESS | `docs/STATUS.md` §1（2026-11-16） |
| 真库矩阵 | MySQL / PG / MariaDB / SQL Server / Oracle（Docker），每家两把库 + TLS 对端 | `scripts/dbmatrix.sh` |
| 截图 | `docs/screenshots/` 74 张，md5 无重复，`scripts/shots.sh` 可重拍 | 目录 + 脚本 |
| 打包 | `scripts/package.sh` 出 app-image / dmg / msi / deb，**未签名、未公证** | `docs/STATUS.md` §打包 🟡 |
| LICENSE | 已有 Apache-2.0 | 根目录 LICENSE |
| CI | `.github/workflows/ci.yml`（Linux full gates + macOS/Windows 编译冒烟） | 已提交 |
| Release | `.github/workflows/release.yml`（tag v* → 三平台未签名包 → draft release） | 已提交 |
| README | 已有中英双语，337 行，需按 STAR-PLAN §3.1 精简重写为 120–160 行 | 根目录 README.md |
| 开源计划 | `docs/STAR-PLAN.md` 已定阶段 A/B/C 与拍板记录 | 已提交 |

**结论**：代码与门禁已具备首发条件；阻塞项在“发布侧叙事与二进制”，不在代码质量。

---

## 2. 三阶段执行计划

与 `docs/STAR-PLAN.md` §6 保持一致，阶段 A 结束即可公开；阶段 B 期间不要公开宣传（没二进制 = 转化率为 0）。

### 阶段 A · “能被人用”（首发前必须完成，约 5–7 人日）

| # | 任务 | 产出 | 负责/参考 | 工作量 |
|---|---|---|---|---|
| A-1 | LICENSE + pom.xml `<licenses>`/`<scm>` 与根目录 LICENSE/NOTICE 三项一致 | 已有，仅做核对 | `pom.xml` §31–48 | 0.5 天 |
| A-2 | 仓库清理：移出内部战报与 Navicat 商业截图（`docs/screenshots/navicat/`），`docs/internal/` gitignore | 公开仓库不再出现内部材料 | STAR-PLAN §3、C-4 | 1 天 |
| A-3 | README 全量重写（英文优先 + 简体中文段），hero 截图 + 6 张图/GIF，只留一个回量数字 | 首屏可扫描、有图有真相 | STAR-PLAN §3.1 | 2 天 |
| A-4 | CI 三平台 `verify` + TestCount + badge，README badge 指向真 CI | `github.com/.../actions` 可见 | `.github/workflows/ci.yml` 已有 | 1–2 天 |
| A-5 | CONTRIBUTING / SECURITY.md / CHANGELOG / issue 模板 / dependabot | 社区基础设施 | 根目录已有部分，需补齐 | 1 天 |
| A-6 | 首屏截图与 GIF（写安全三层 / 数据网格编辑 / 查询编辑器 / 双主题） | `docs/screenshots/` 补充首屏用 | `scripts/shots.sh` + 本文档 §截图 | 并行于 A-3 |

**阶段 A 完成标志**：`git tag v0.1.0` → `release.yml` 出三平台未签名安装包 → README 挂下载链接。

### 阶段 B · “有人愿意留”（发布后 2–4 周，约 10–15 人日）

| # | 任务 | 产出 | 参考 | 工作量 |
|---|---|---|---|---|
| B-1 | Oracle / SQL Server / MariaDB 方言落地（D 系列，优先级高于 R37–R42 全部） | 六家方言均有真库证据 | `docs/ROADMAP.md` #1、`STAR-PLAN` C-3 | 5–8 天 |
| B-2 | Release workflow 三平台安装包 + git tag v0.1.0 | Win .msi / macOS .dmg / Linux .deb 可下载 | `.github/workflows/release.yml` 已有 | 2–4 天 |
| B-3 | SSH 隧道落地（连接向导 SSH 页真表单，凭据进凭据库） | 可经跳板机连生产库 | `docs/ARCHITECTURE.md` §6、`STAR-PLAN` B-9 | 2–3 天 |
| B-4 | N32 库级树 + N31 Filter/Sort 内联带 + 空白态 E5–E9 | 多库用户基本可用性 | `GAP-TODOLIST-V4.md` T7/T9 | 2–3 天 |
| B-5 | R35 彩色图标 + 暗色档（与 A-3 hero 图协同） | 首屏视觉可转化 | `docs/UI-VISUAL-SPEC.md` §4 | 并行 |

### 阶段 C · “有独家卖点”（选做，按数据）

| # | 任务 | 为什么能带 star | 参考 |
|---|---|---|---|
| C-1 | Data Generation（造测试数据） | Navicat 收费，独家免费 | `GAP-TODOLIST-V4.md` R15 |
| C-2 | Data Masking（脱敏导出） | 合规刚需 | `GAP-TODOLIST-V4.md` R05/B7 |
| C-3 | 一篇技术文投 HN / r/java（“为什么我们做一个不用 Electron 的数据库客户端”） | star 的最大外部来源 | `STAR-PLAN` C-15 |

---

## 3. 下一步（本周可执行）

按优先级排列，可直接开始：

1. **核对 LICENSE / NOTICE / pom.xml 三项许可一致性**（A-1，0.5 天，今天可做）。
2. **按 STAR-PLAN §3.1 重写 README 为 120–160 行英文优先版**，嵌入 hero 截图 + 6 张功能图（A-3，2 天）。
3. **补齐 CONTRIBUTING / CHANGELOG / issue 模板**（A-5，1 天）。
4. **跑一次完整门禁并把数字写进 `docs/STATUS.md` §1**（A-4 的前提，确保 CI 绿后再挂 badge）：
   ```bash
   ./scripts/build.sh
   ```
5. **重拍首屏截图**（`./scripts/shots.sh`，见本文档 §截图指南）。

---

## 4. 口径与诚实条款（对外文案必须遵守）

- **只写回量数字**：测试数、帧数、耗时必须带“何日、何平台、何命令、何口径”，禁止在三份文档里写不同的同一数字（历史已翻车过，见 `docs/STATUS.md` 口径警告）。
- **不宣称未验的能力**：MySQL 5.7 只有方言层承诺；Tencent COS 无真端点证据；OSS/Kodo/COS 分页无真端点证据；冷启动当前 WIP 未达 <2s。
- **签名/公证诚实**：macOS 未公证安装包会被 Gatekeeper 拦截，README 明写“macOS 走源码构建，或右键打开未签名 .dmg”，不把“能下载”说成“可直接安装”。
- **商标未清账**：`icons/brand/` 五枚是中性几何占位件，对外发布前必须换成各家品牌页原始矢量件，不得对外写“已获授权的商标展示”（`docs/UI-VISUAL-SPEC.md` §4.6 规则 7、`TRADEMARK-NOTICE.txt`）。

---

## 5. 相关文档

- `docs/ARCHITECTURE.md` — 模块、数据流、线程模型、编辑与 SQL 不变量
- `docs/ROADMAP.md` — 对外路线（支持矩阵 / 进行中 / 接下来 / 明确不做）
- `docs/STATUS.md` — 验证基线数字与完整限制（唯一回量数字出处）
- `docs/UI-VISUAL-SPEC.md` / `docs/UI-GUIDE.md` / `docs/UI-FORMS-AND-STATES.md` — 设计系统
- `docs/STAR-PLAN.md` — 发布/增长视角（本文的决策依据）
- `docs/internal/GAP-TODOLIST-V4.md` — 内部执行台账（不随公开仓库发布）
- `scripts/build.sh` / `scripts/shots.sh` / `scripts/package.sh` / `scripts/dbmatrix.sh` — 构建/截图/打包/真库矩阵
