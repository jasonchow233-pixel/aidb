# Security Policy

> 本文件说明 AIDB（AgenticDB）的安全边界、漏洞报告渠道，以及什么算、什么不算问题。
> AIDB 是一个**连接你自己数据库的本地桌面客户端**——它不联网服务任何人，
> 所以「攻击面」和你想象的 Web 应用很不一样。请先读第 2 节再提交报告。

## 1. 报告渠道（请勿直接开公开 Issue）

**首选：GitHub 私密漏洞报告** —— [Report a vulnerability](https://github.com/jasonchow233-pixel/aidb/security/advisories/new)
（需要仓库开启 *Private vulnerability reporting*；若该入口 404，请开一个**不含复现细节**的
[Issue](https://github.com/jasonchow233-pixel/aidb/issues/new)，标题写 `security`，
维护者会在私密会话里跟你要细节。）

处理节奏（单人维护为主，请按这个预期沟通）：

| 阶段 | 目标时限 | 内容 |
|---|---|---|
| 确认收到 | 3 个工作日 | 是否成立、优先级、是否需要你补充信息 |
| 评估结论 | 14 天 | 影响面、修复方案、是否需要发版 |
| 披露 | 修复可用后 | 通过 GitHub Security Advisory + `CHANGELOG.md` 公开，并在致谢里提名报告者（可匿名） |

不接受「先在公开 Issue / PR 里贴完整复现」的做法——那等于给未修复的漏洞做公告。

## 2. 产品的数据与网络边界（这是判断漏洞成立与否的前提）

以下是**当前实现的实际事实**，写进文档是为了让报告者不必自己逆向，也让任何偏离它的实现被视为缺陷：

- **口令不落盘。** 数据库口令只写入系统安全存储：Windows 凭据管理器（WinCred）、
  macOS 钥匙串、Linux Secret Service；检测不到系统服务时退回进程内存，并在 stderr 明确警告。
  见 [workbench/adb-session/src/main/java/io/agenticdb/session/CredentialStore.java](workbench/adb-session/src/main/java/io/agenticdb/session/CredentialStore.java)。
- **连接配置落盘前剔除敏感键。** `~/.agenticdb/connections.json` 只保存非敏感字段；
  `password` / `secret` / `token` / `sslkey` / `sslcertificate` 等一整套键名（大小写、连字符、下划线不敏感）
  在落盘和建连两处都会被 `sanitizedOptions()` 过滤掉。
  见 [workbench/adb-session/src/main/java/io/agenticdb/session/ConnectionProfile.java:139](workbench/adb-session/src/main/java/io/agenticdb/session/ConnectionProfile.java#L139)。
- **没有遥测、没有 HTTP、没有监听端口。** 生产代码里不存在 `HttpClient` / `HttpURLConnection` /
  `ServerSocket` / 内嵌 WebView；唯一的出站连接是你自己在连接配置里填的那个数据库地址。
  任何「AIDB 会向第三方发送数据」的结论都需要给出抓包证据。
- **SQL 由内省结果生成。** 结构页/建表/改列走的是各驱动的元数据 + 方言生成器，
  数据页写入按主键定位并使用参数绑定；生成器拼接的位置都有回归门禁断言。

## 3. 在范围内

- 上述边界被破坏：口令进了 `connections.json` / 日志 / 崩溃转储 / 导出文件；出现未声明的出站连接。
- **不受信文件的解析**：SQLite 数据库文件、CSV/TSV/JSON/xlsx 导入文件、SQL 脚本。
  解析崩溃、越界写、路径穿越（例如导入向导把映射列名当路径用）、Zip bomb 类资源耗尽。
- **SQL 生成与转义**：标识符/字面量 quoting 缺陷导致数据破坏或越权语句；改列整表重建时丢约束/丢数据。
- **UI 层的注入**：来自数据库的值（表名、注释、单元格内容、错误信息）被当作标记或脚本渲染。
  注意：AIDB 不内嵌 WebView，因此「渲染成 XSS」只在能触发文件写入/命令执行时才成立。
- 依赖供应链：`pom.xml` 里锁定版本的第三方组件（JavaFX、ControlsFX、RichTextFX、Jackson、HikariCP、POI、JNA、各 JDBC 驱动）的已知 CVE 影响本产品时。
- 凭据/权限的混淆：多连接同树场景下 A 连接的口令被用于 B 连接、切库后仍能读到上一库的数据等。

## 4. 不在范围内

- **你连的那个数据库本身**的安全（弱口令、`pg_hba` 配错、服务端版本 CVE）。AIDB 是客户端。
- **OS 凭据服务本身**被本机其它进程读取——那是本机权限模型的问题，不是本产品的漏洞。
- 需要攻击者已经拿到用户机执行权限才能成立的一切（读内存、挂钩 `security`/`secret-tool`、改 `~/.agenticdb/`）。
- DoS / 刷连接数打自己的数据库；扫描、爆破、钓鱼等对基础设施的动作。
- 文档里已明确标注的**未实现能力**（签名/公证、自动更新）——它们是路线图上的缺口，不是漏洞。
  见 [docs/ROADMAP.md](docs/ROADMAP.md)。
- 版本策略：只修 **`main` 当前 HEAD** 上仍存在的问题。首个正式 tag 发布前，没有「历史版本」可回补。

## 5. 报告请附上

1. 版本与提交号（`git rev-parse --short HEAD`）+ 操作系统与架构 + JDK 版本（`java -version`）。
2. 最小复现：连的是哪种数据库（SQLite / MySQL / PostgreSQL …）、做了哪一步、观察到什么。
3. 影响判断：泄露了什么、能越到什么边界。
4. 有 POC 就给 POC，但**不要把 token / 口令 / 真实生产数据贴进来**——Issue 是公开的。

## 6. 修复如何发布

修复合入后：`CHANGELOG.md` 记录 + GitHub Security Advisory（影响版本、升级建议）+
门禁断言（每个安全相关的修复都要有一条能重现该缺陷的回归测试，这是本仓库的硬规则）。

## 7. 致谢

报告被采纳的，在对应 Advisory 里署名（GitHub 用户名或你想要的化名）。
我们不会因为你指出了一个缺陷而对你做什么——反过来，如果你顺手提了修复 PR，我们会优先 review。
