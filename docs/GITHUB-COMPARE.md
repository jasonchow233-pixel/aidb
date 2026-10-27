# GITHUB-COMPARE — GitHub 上开源数据库管理软件对照

> **2026-10-22 新增**（第九轮对外汇报的一部分）。
> 回答：**GitHub 上还有哪些开源的、可与 AgenticDB 正面比较的数据库管理软件？**
>
> 对照对象：AgenticDB（`NAVICAT-TODO.md` 里对 Navicat 的对拍；`docs/EXEC-SUMMARY.md` 全景）。
> 本文件只做「开源候选」的横向扫描，不重复 Navicat 对照。

---

## 0. 调研方法

- GitHub **topic**：`database-management`、`database-gui`、`sql-client`、`sql-editor`、`gui`。
- Awesome lists：`mgramin/awesome-db-tools`（Everything that makes working with databases easy）、
  awesome-database / awesome-selfhosted 中数据库管理类条目。
- 检索词：开源数据库客户端 / SQL 编辑器 / database GUI open source / database client desktop。

> 结论来源为公开仓库元数据（语言、许可证、形态、README 定位），**功能点级对比以各仓库 README + release notes 为准**，
> 与 AgenticDB 的实测门禁无关（AgenticDB 本轮的「真库 + TestFX + 门禁」体系不能套用到这些项目）。

---

## 1. 主要候选对比

| # | 项目 | GitHub | 语言 / 栈 | 许可证 | 形态 | 最强能力 | 与 AgenticDB 的关系 |
|---|---|---|---|---|---|---|---|
| 1 | **DBeaver** | `dbeaver/dbeaver` | Java（Eclipse 系） | GPL | 桌面 + Web（DaaS） | **功能最全的开源 DB 工具**：ER 图、AI、导入导出、BI、仪表盘、驱动管理；PostgreSQL/MySQL/Oracle/SQL Server/SQLite… | **最强对标对象**。同是 Java，但底座是 Eclipse RCP；架构与设计哲学都不同。AgenticDB「原生 JavaFX + 设计令牌 + 安全三层 + 方言抽象 + 真库契约」正是 DBeaver 没有的组合。 |
| 2 | **Chat2DB** | `OtterMind/Chat2DB` | Java / Spring | Apache-2.0 | Web 为主 | AI（Copilot）驱动的在线 DDL 变更 / 数据操作 | 功能面更重；与 AgenticDB「手写 SQL + fail-closed」的取向差异大。 |
| 3 | **Beekeeper Studio** | `beekeeper-studio/beekeeper-studio` | TS / React / Electron | MIT | 桌面 | UI 现代、SQL editor、网格编辑、导入导出、SSH | 现代 UI 范式最接近 Navicat 新一代；技术栈（Electron/React）与 AgenticDB（JavaFX）不同，但交互理念可借鉴。 |
| 4 | **TablePro** | `TableProApp/TablePro` | TS / React / Electron | MIT | 桌面 | 原生桌面、简洁网格 | 新秀，生态小；与 AgenticDB 同「桌面 DB 客户端」定位。 |
| 5 | **sqlpad** | `rickbergfalk/sqlpad` | TS / React | — | Web | 私有云 SQL 编辑器 | 形态不同（Web），不正面比较。 |
| 6 | **SQLTools** | `mtxR/vscode-sqltools` | TS / VSCode | MIT | VSCode 插件 | 数据库管理进 IDE | 形态不同。 |
| 7 | **Adminer** | `phpmyadmin/Adminer` | PHP | GPL | Web / 单文件 | Web 轻量 | 形态不同。 |
| 8 | **Mathesar** | `mathesar-foundation/mathesar` | — | — | Web | Postgres spreadsheet 式 | 形态不同。 |

## 2. 扫描结论

1. **开源界最接近 AgenticDB 目标（Navicat 级桌面 DB 客户端）的产品**：DBeaver、Beekeeper Studio、TablePro 三雄。
   - DBeaver：功能最全，但 Java 底座是 Eclipse（非 JavaFX），且架构偏「数据库驱动即插件」。
   - Beekeeper / TablePro：Electron 桌面，UI 现代，但仍是 Chromium 内嵌。
   - **AgenticDB 的「纯原生 JavaFX、不内嵌 WebView、不启 HTTP」是开源对面几乎没有的组合** —— 这是本项目的独特差异化位。
2. **Web-first 阵营**（Chat2DB、sqlpad、phpMyAdmin、SQLTools）在形态上与 AgenticDB「桌面原生」直接不符，但它们的**导入导出 wizard / AI / 在线 DDL** 已超出 AgenticDB 首版范围（明确不做：AI、ER、计划任务、传输同步）。
3. **「与 AgenticDB 同域竞争」最强的是 DBeaver**：Java 工具、桌面、多数据库、SQL 编辑器、数据网格、结构管理。双方共同点在「数据库客户端」本身；分歧在技术栈与安全/设计哲学。

## 3. 对 AgenticDB 的借鉴项（转入 TODO / 路线）

| 候选 | 借鉴项 | 转入 AgenticDB TODO |
|---|---|---|
| DBeaver | 结构设计器 / ER（AgenticDB 明确不做）；导入导出 wizard（已有自研，可对照）；命名变量 / 参数（已做 Q2）；结果分页（**Q5**） | Q5、导入导出细节 |
| Beekeeper Studio | 现代 UI（命令面板 / 快捷键 / 空状态 / 网格交互）—— AgenticDB 已自研 U-B1..B5 重做，可作为跨栈学习 | 已覆盖 |
| Chat2DB | AI 辅助（AgenticDB 明确不做 AI） | 不做 |
| TablePro / sqlpad | 桌面外壳 / Web 形态 | 形态不同，不做 |

## 4. 对外表达（GitHub 维度）

- **GitHub 上开源数据库管理软件的演进趋势**：从「单数据库单功能」到「多数据库 + AI + Web + 云 + BI」。
- **AgenticDB 的叙事机会**：开源界缺少「Java + 原生桌面 + 设计系统 + 安全写门禁」这一组合；AgenticDB 填了空白。

---

## 5. 相关文档

- `NAVICAT-TODO.md` — Navicat 对标（本文件是对 Navicat **之外**的开源扫描）。
- `docs/EXEC-SUMMARY.md` — 对外汇报版（含本章引用）。
- `docs/COMPONENT-SELECTION.md` — 组件选型（JavaFX/ControlsFX/RichTextFX…）。
- `docs/ARCHITECTURE.md` — 架构。
