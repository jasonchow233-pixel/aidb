# Roadmap

> 公开路线：**现在支持什么 · 接下来做什么 · 明确不做什么**。
> 内部执行清单（逐轮拆解、验收口径）不在本仓库发布，本文只对外承诺结果。

---

## 已支持

> 下表区分已接线能力与验收边界；资产族扩展（P0–P3）的代码与行为门禁已落地，
> ⭐ 2026-11-15：**R1 已转绿**（同版帧 + 真端点画面帧齐）；**R2 已平三支**
> （S3 兼容分支的真 LocalStack 契约 + 真端点帧、阿里云 OSS 与七牛 Kodo 的真账号只读契约），
> **仅腾讯云 COS 一支原生 SDK 仍无真端点证据**。
> ⭐ 同日第三轮：**对象浏览改为渐进分页**（首屏 200 行 + 「加载更多」页脚），
> 但分页的真端点证据**仍只有 S3 兼容一支**，OSS / Kodo / COS 三支未验。
> **发布与商标原件仍属阻塞项**（R3/R4）。历史真库结果不代表当前工作树已验收，
> 验证基线见 [STATUS](STATUS.md) §1。

| 能力 | 说明 |
|---|---|
| 数据库 | SQLite · MySQL · PostgreSQL · MariaDB · SQL Server · Oracle（后五家全部在本机 Docker 真库矩阵上跑过；MySQL 只有 8.x 有执行证据，5.7 见 `docs/STATUS.md` §测试环境） |
| 资产族地基 | `AssetKind` 为连接判别轴、`AssetFamilyRegistry` 为单一登记处；四类对象存储合并成一个 `OBJECT_STORE` kind + provider 子判别，旧 `connections.json` 无需迁移。UI 不得硬编码资产族常量，新增一族必须同时给向导筛选带补一枚 chip（`AssetFamilyFilterAuditTest` 动态钉住） |
| Redis | 向导连接、树按 `INFO keyspace` 懒加载 db 分组、键列表（前缀 / 类型过滤）、五种值类型查看与编辑、TTL、`DEL` / `FLUSHDB` / `FLUSHALL` 全走 `RedisDanger` 分级 + 破坏性确认。⭐ **Redis over SSH 已接入**：复用 SQL 那套纯 Java 隧道（`RedisSshTunnelTest` 进程内跳板机 + 真 `redis:7.2` 容器端到端验证） |
| 对象存储 | 同一浏览器覆盖 S3 兼容端点（AWS S3 / MinIO / LocalStack）、阿里云 OSS、七牛 Kodo、腾讯云 COS；前缀面包屑、delimiter 折叠的虚拟文件夹、上传 / 下载 / 删一颗 / 递归删前缀 / 元数据 / 预签名 URL / 移动重命名。`ObjectStoreCapabilities` 显式声明能力：七牛无服务端 move、不能列 bucket → 按钮置灰写明原因 + 后端抛 UOE，树只显示配置的那一个桶。密钥只进系统凭据库。⚠️ ⭐ S3 兼容分支已有真端点契约（11 条，LocalStack 4.9.2 真容器 + HTTPS，含 1001 对象跨页与新增的真分页契约）与真端点画面帧 `p10-object-store-real`；⭐ OSS 已有真账号只读契约（8 例，含 ListObjects V1 跨千条页边界与预签名 URL 真 HTTPS GET）；⭐ Kodo 已有真账号只读契约（12 例，11 通过 + 写契约 opt-in 跳过，含 `STORE/` 层级键 4 页 3427 条跨页与私有空间签名读），两家凭据都只进环境变量、都**没有真账号画面帧**；**仅 COS 一家原生 SDK 仍只有编译 + 内存后端契约**。⭐ **渐进分页已交付**：`ObjectPage` / `PageCursor` + `ObjectStoreSession.listObjectsPage`（四家 SDK 各自原生分页、游标绑定 bucket/prefix/delimiter）、浏览器首屏 200 行 + 「加载更多」页脚（无下一页时隐藏、逐页按 key 去重、换前缀重置）；旧全量 `listObjects` 只留递归删前缀 / 连接测试 / 真端点契约。⚠️ 分页的真端点证据只有 S3 兼容一支（本轮 11 条契约实跑），OSS / Kodo / COS 三支未验；超大 bucket 性能曲线未量。真账号桶 >12000 对象是这条能力的实测动机 |
| 写操作安全（非 SQL 族） | Redis 与对象存储复用 `Danger` 三级分级和 `DestructiveConfirm`（取消是默认按钮）；`DELETE_OBJECT` / `DELETE_PREFIX` 为 `DESTRUCTIVE`，未知 / 空白操作 fail-closed 到 `ADMIN` |
| 首启引导 | 叠加在 demo 自动连接之上的一次欢迎浮层，关后即焚（`ui.onboarding.seen`），Help 菜单可重开；验收 / 截图 / 测试会话不自动弹，保证逐帧可复现 |
| 向导族筛选 / 标签概览 | 11 张磁贴配「全部 / SQL / Redis / 对象存储」四枚筛选 chip（可见性切换，不重建磁贴）；`tabs` 命令（命令面板 + Window 菜单）打开非常驻的标签概览，按连接分组、搜索、激活与关闭。**所有关闭都回到 `TabBar.close()`**，`closeGuard` 脏检查、钉住态与常驻标签规则不被绕过 |
| 形态 | 原生桌面（Java 25 + JavaFX 25），**不内嵌浏览器、不启本地 HTTP 端口** |
| 平台 | macOS · Windows · Linux（**源码构建**；尚无签名安装包，见 `docs/STATUS.md` §分发） |
| 数据网格 | 服务端分页、列筛选/条件构造、多列排序、列宽与顺序记忆、行内编辑、区域选择、冻结列；**Filter & Sort 内嵌生成器**（显式 Apply、Sort By 芯片） |
| 写操作安全 | 数据页写回先预览 SQL，按事务所有权执行并提供最近一次保存的反向撤销；DDL 可能隐式提交，不承诺跨方言失败全部回滚。当前 P0 写入口确认覆盖仍待验收 |
| 危险分级第三层 | `DbSession.execute(String, Danger)` + `requireDeclared` 复查并拒绝低报危险等级；裸 `execute` 自行分类不等于用户确认，不能据此宣称全调用路径安全 |
| 数据脱敏导出 | ⭐ P-3 已交付：`MaskRule` / `DataMasker` / `ExportWizard` 列级脱敏（掩码 / 哈希 / 置空） |
| 导航 | 连接 → 库 → 分组三级树；MySQL / MariaDB / PostgreSQL / SQL Server 多库可切换（双击库即切），SQLite 单库平铺；页签与状态栏带库名，⭐ R54 起界面上十个说得出「是哪家」的表面各顶**自己那一家**的图形与品牌色，不用读字就知道连的是哪家。⭐ R52 契约第 9 条把「切过去之后语句真的落在那个库」补成了真库证据 |
| 结构编辑 | 加列/改列/重命名/删列、索引、外键、触发器、CHECK、表注释；先确认 SQL，按事务所有权执行并刷新。DDL 回滚取决于引擎，不承诺全方言原子性 |
| SQL 编辑器 | 语法高亮、**随库上下文的自动补全**（⭐ R57-a：表 / 视图 / 列 / 例程 / 关键字）、**选中执行**（⭐ R57-b）、多语句结果页签、执行计划、错误定位、查询历史、代码片段；**编辑器 / 结果带分栏可拖且记住**（⭐ R57-g） |
| 例程 | 函数 / 存储过程：**功能区「函数」格开真实例程清单页**（列函数 / 过程 / 调度事件，双击打开定义体）；PostgreSQL 可编辑，先固定 SQL 预览、双确认后执行 `CREATE OR REPLACE`，其余只读。MySQL EVENT 只读树分组已接线，尚未完成同版 MySQL 验收 |
| 导入导出 | CSV / TSV / JSON Lines / SQL / xlsx / XML / HTML，字段映射、按目标列类型转换、坏行报告；**查询结果三种范围 × 六种格式** |
| 备份 / 恢复 | 表结构 / 数据与可读取的视图 DDL 的逻辑转储 / 恢复，落在真实工具页；不是完整灾备，不承诺覆盖全部数据库对象或跨方言原子回滚 |
| 数据画像 | 单表列画像（数值聚合 / 空值 / 基数 / 类型分布），落在真实工具页；**数据网格工具条那颗「数据画像」按钮直达该页并预选当前表** |
| 跨库传输 | 仅同引擎、不同会话的单表 SQL 重放，目标已有同名对象时拒绝覆盖；异构比较只读，异构迁移未实现。当前 P0 修改仍待同版验收 |
| 凭据 | 存系统凭据库（macOS Keychain / Windows Credential Manager / secret-tool），**不落盘明文** |
| SSH 隧道 | ⭐ R55：连接可经**跳板机**再落数据库 —— 纯 Java 隧道引擎（Apache MINA `sshd-core`），不调用系统 `ssh`；主机密钥按 OpenSSH `known_hosts` 记录，未知密钥弹指纹比对窗由真人决定；⚠️ 私钥格式限 RSA / ECDSA |
| 主题 | 暗/亮双主题（跟随系统）、四档密度、设计令牌单一真相 |
| 键盘与可访问性 | ⌘K 命令面板、六区焦点顺序、逐页可朗读名称 |
| 界面语言 | 英文（默认）· 简体中文 |
| 视图依赖图 | 只读依赖关系图：右键表 / 视图「查看依赖图…」开只读标签页，解析视图正文（FROM/JOIN，含 schema / 双引号 / 反引号 / 方括号标识符，排除 CTE 与别名）算前向依赖（视图 → 它读的对象）；反向边（谁依赖该节点，删表前警告要用的那一侧）由同一份前向数据推导，于是「删表会坏哪些视图」一并覆盖；不能匹配清单的引用按「外部」灰显，但不能据此认定跨 schema 引用一律外部。限定名仍可能合并、缺少定义体时会少边（R16），不保证完整影响分析。渲染只读，支持拖拽平移与 Ctrl+滚轮缩放，聚焦节点的上下游高亮、其余淡出。 |

## 接下来（待执行 backlog，按对用户价值排序）

> 下列条目为当前**尚未构建 / 仅部分构建**的能力，列为明确执行项（不再保留「如实说明页」占位）。
> 已交付的「危险分级第三层」「数据脱敏导出」已移入上表；裁定不做的「ER 图」「HTTP 隧道」见下「明确不做」。

1. **对象存储真端点验收（R2 · ⭐ S3 兼容分支 + 阿里云 OSS + 七牛 Kodo 三支已平，仅剩 COS）** ——
   `S3RealEndpointContractTest` 已在真 `localstack/localstack:4.9.2` 容器 + 受信任 HTTPS 端点上
   验完「连得上、列举 / 上传 / 下载 / 元数据 / 删除 / move / 预签名 URL 真 HTTP 取回」，
   并用 1001 颗对象量出 ListObjectsV2 跨页确实生效；`p10-object-store-real` 是同族真端点画面帧。
   ⭐ `OssRealEndpointContractTest` 已对运营方真账号跑完只读 8 例（7 通过 + 写契约 opt-in）：
   ListObjects V1 跨千条页边界与原生 SDK 逐条对账、metadata 的 contentType、预签名 URL 真
   HTTPS GET、`urlPrefix` 同域；凭据只进环境变量，默认不写生产桶，也没有真账号画面帧。
   ⭐ `QiniuRealEndpointContractTest` 已对运营方真 Kodo 账号跑完只读 12 例（11 通过 +
   写契约 opt-in）：公开 / 私有空间签名 URL 真 HTTPS GET、`STORE/` 层级键 4 页 3427 条跨
   marker 页边界与原生 SDK 对账、putTime 换算、坏凭据必须失败；凭据同样只进环境变量。
   契约实跑还逼出 `ObjectStoreSessions.test` 的假绿修复（Kodo 的 `listBuckets` 是本地回显）。
   ⭐ **渐进分页本轮已交付，移出待办**：`ObjectPage` / `PageCursor` +
   `ObjectStoreSession.listObjectsPage`（四家原生分页 + 服务端 delimiter 折叠）、
   `ObjectBrowserPage` 首屏 200 行 + 「加载更多」页脚；`ObjectStorePagingTest`（8 例）/
   `ObjectBrowserPagingTest`（7 例）钉住「四家不许退化成全量切片」与「UI 不许偷偷全量拉」。
   分页的真端点证据**只有 S3 兼容一支**（`S3RealEndpointContractTest` 11 条里新增一条，实跑）。
   **仍需**：腾讯云 COS 一支原生 SDK 的仿真服务或真账号契约；OSS / Kodo 真账号画面帧
   （会拍进生产对象名，需先定脱敏口径）；OSS / Kodo / COS 三支的分页真端点证据；
   超大 bucket 的端到端耗时 / 内存曲线量测。
   ⚠️ 另有一条边界：SDK 2.31 对纯 `http://` 的 S3 兼容端点会卡 `Expect: 100-continue`
   （见 STATUS §3），产品尚未适配明文端点。
2. ✅ **Redis over SSH（已交付，移出待办）** —— `RedisSessions` 复用 SQL 那套纯 Java 隧道
   （`SshTunnel`），启用时开本地端口转发到档案里填的 Redis 地址、让 Lettuce 连环回口；
   `RedisSshTunnelTest` 用进程内跳板机 + 真 `redis:7.2` 容器端到端验证转发确实生效、
   错误口令报为隧道失败、会话关闭后本地端口释放。SSH 秘密只从凭据库取，不进 `connections.json`。
3. ✅ **同版截图重拍（R1，已平账，移出 backlog）** —— 68 张基准帧已同版重拍，
   引导浮层 / 族筛选带 / 对象浏览器 / 标签概览 / Redis 键浏览器 / 对象存储真端点浏览器
   六张新面帧均已入库（`docs/screenshots/` 74 张，`md5` 零重复）。
4. **商标原件替换（R4 资产阻塞）** —— `redis` / `aws-s3` / `aliyun-oss` / `qiniu` /
   `tencent-cos` 五枚是中性几何占位件，发布前必须换成各家品牌页的原始矢量件。
5. **MySQL EVENT 上树（待验收）** —— 只读 EVENT 分组与定义体入口已接线，仍需同版 MySQL 验收；触发器按表归属在结构页查看。
6. **发布工程（R3 授权阻塞）** —— 三平台安装包、自动更新、代码签名与公证。
7. **冷启动优化（Q7，WIP）** —— `AppShell` 已改为构造器返回后按需连接，不再保证返回时演示库已连上；当前启动验收未完成。历史 5.0–5.6s（`scripts/startup-bench.sh` 口径）保留，不作为新路径的实测结果。
8. ✅ **实际查询构造器（R13，已交付，移出待办）** —— 查询页的构造器从只读预览升级为真构造器：后台拉表 / 视图清单与列、勾选列、加 AND / OR 条件组、实时生成可直接执行的字面量 SELECT，「插入编辑器」只回填文本不自动执行。生成逻辑是纯逻辑层 `QueryCriteria`（条件行 → `DataQuery`，含 AND/OR 切分与值类型推断）+ `SelectBuilder`（→ 字面量 SELECT，复用方言引用与 LIKE 转义、BLOB fail-closed），`SelectBuilderTest` 15 / `QueryCriteriaTest` 8 / `QueryBuilderUiTest` 2 钉住「只生成不执行、注入值转义、无表时回退」。诚实边界：只生成 SELECT、不带 LIMIT / OFFSET（用户自定）、不自动执行；真端点证据是测试里的真 SQLite，画面帧未重拍。
9. **视图依赖解析边界（R16，待修）** —— 限定名可能合并，缺少定义体时会少边；当前静态解析不能保证完整依赖。

### 已知缺陷与边界（开放 D 项）

- **D-28**：PostgreSQL `CREATE OR REPLACE VIEW` 只许末尾加列，改名/删列服务端报错，失败前是否主动提示待裁定。
- **D-42**：MySQL 8.4 `caching_sha2_password` 在服务端缺 `mysql_native_password` 回落时连不上，界面需把这句实话写清。
- **D-44**：导出 HTML 标题硬编码中文已修；导出范围与选项的验证尚未闭环，不据此宣称导出整体已验收。
- **D-46**：部分图标资产自带 1px 基线差（非代码缺陷，属美术资产），记录备查。
- **D-49**：`getDdl` 把「读不到」与「连不上」压成同一个 `null`，产品那一半（读路径区分）仍未修。
- **D-64**：保存后没有重取钩子的注释原为现役症状，实测两层拒集重合，现为潜在边界。

## 明确不做（架构红线，非功能入口）

- **不内嵌 WebView / 不做 Electron 化**：原生桌面是这个项目存在的理由。
- **不启应用自启 HTTP 服务**：所有数据经 JDBC，桌面工具不该自己起一个端口（架构红线）。注意这与「HTTP 隧道」是两回事——后者是连接经代理落地，前者是应用自己起服务。
- **不做云端协作 / 多用户权限体系**：这是桌面单用户工具的定位。
- **不把 AI 助手 / AI 依赖做成核心能力**：SQL 由用户书写，危险分级由本地规则处理，不交给模型判断；查询页 / 建表弹窗的 Ask AI 与网格 Fill with AI 已删除，本版本没有 AI 服务。查询构造器不是 AI 能力，其结构化条件生成 SELECT 的能力已交付（见上 R13）。
- **不做 Automation（计划任务调度）、Model ER 全量编辑器、Data Generation（测试数据填充）**
  （2026-11-01 裁定，P1）：这三面在 Navicat 里是独立子系统，各自要一套自己的持久化格式与编辑器，服务的是「团队批量作业 / 逆向建模 / 造压测数据」——都不是这个桌面客户端的定位。**入口已于 2026-11-10 全部删除**（Ribbon 的 Automation / Model 磁贴、数据网格的 Data Generation 工具条按钮），不再保留占位说明页。
- **不做 BI**：2026-11-09 整体移除，数据网格工具条 13 → 12 具名键。
- **不做 ER 图（含只读结构图与全量 Model 编辑器）**：2026-11-10 用户明确裁定「ER 图功能删掉」，只读版与全量编辑器一并不做，相关入口与 backlog 条目均已删除。
- **不做 HTTP / HTTPS 代理隧道**：按 2026-11-10 裁定**不做，连接向导页签已删除**；与「应用自启 HTTP 服务」的架构红线是两回事。
- ⭐ **v4「不扩 Redis」条款已由用户明确反转（2026-11-12）**：Redis 与四类对象存储现已带着真驱动接入
  （见上「已支持」）。MongoDB / Snowflake / DynamoDB 仍不做；「不摆没有驱动的假厂商磁贴」
  这条纪律继续有效 —— 新增族必须先有会话实现与行为门禁，才允许出现在向导里。

> **2026-11-10 的连带清理**：删入口之后，功能区曾只剩「函数」一格挂「未提供」角标 ——
> 但例程定义体其实早已交付、入口只在左侧树双击，等于**同一个能力一条路能用、另一条路说没有**。
> 现已给它接上真实例程清单页，Ribbon 11 格**全部**有真落点，角标清零。
> 同批接掉的还有数据网格工具条那颗「数据画像」按钮（能力早已交付，按钮却在说「没有」）。
> 当前补注：查询页 / 建表弹窗 Ask AI 与网格 Fill with AI 已删除；「查询构造器」已能从结构化条件生成 SELECT（R13 已交付），但它是本地规则生成、不是 AI。

## 已知限制

冷启动未达 <2s 目标（2026-11-04 本机真进程实测 **5.0–5.6s**，三次取最坏，口径「进程启动 → 首屏」；
瓶颈是 JVM/Toolkit 与 `stage.show()` 首趟 CSS/布局 1.6s，非业务代码）。
jlink 自定义运行时暂不可行（RichTextFX / ControlsFX 为自动模块）；
无主键表与视图只读；转置视图只读；本页搜索只作用当前页。
完整清单见 [docs/STATUS.md](STATUS.md)。
