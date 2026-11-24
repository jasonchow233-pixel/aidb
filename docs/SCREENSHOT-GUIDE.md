# SCREENSHOT-GUIDE — AIDB 验收截图拍摄与维护

> 本项目界面证据以 `docs/screenshots/` 的 `p*.png` 实拍帧为准，由 `scripts/shots.sh` 通过 TestFX “摆态 + 快门”自动化产出。  
> 核心原则：**宁可没图，不可有假帧**；每帧必须与当前源码同版；去重门禁（md5 零重复）与尺寸门禁（与已入库版本比）自动兜底。

---

## 1. 帧清单（现有 74 张）

| 组 | 文件 | 含义 | 是否需真服务端 |
|---|---|---|---|
| 外壳 | `p0-shell`、`p0-tree-vendors`、`p0-tree-vendors-dark` | 首屏、多引擎同树（亮/暗） | 否（图形取自档案 kind） |
| 数据网格 | `p2-users` / `p2-users-dark`、`p2-cell`、`p2-details`、`p2-error`、`p2-find`、`p2-form`、`p2-filter`、`p2-filter-sort`、`p2-overview`、`p2-section-indexes`、`p2-transaction` | 编辑、详情、查找、筛选、事务、概览 | 否（演示库） |
| 查询页 | `p3-query`、`p3-query-completions`、`p3-query-edit-blocked/dirty/preview`、`p3-query-idle`、`p3-query-menu`、`p3-query-overflow`、`p3-query-selection`、`p3-query-catalog`/`catalog-bound` | 编辑器、补全、结果写回、库选择器 | catalog 两张需真 MySQL |
| 结构页 | `p4-structure`、`p4-structure-indexes`、`p4-ddl` | 列/索引/外键/触发器/DDL | 否 |
| 建表/视图 | `p6-new-table*`（8 页）`p6-new-view*`、`p6-design-view*`（5 方言）`p6-rename-table`、`p6-tables`/`tables-grid` | 设计器、视图生命周期 | design-view 新建需 SQL Server；设计读回需 PG/SQL Server/Oracle |
| 连接 | `p5-connect-*`（type/general/ssl/ssh/ssh-key/family/dark/connections/vendors） | 向导、连接管理器、厂商识别 | 否 |
| 导入/导出 | `p8-import`、`p8-export` | 向导 | 否 |
| 诊断/设置/收藏/已保存查询 | `p9-*` | 工具页 | 否 |
| 扩展族 | `p10-onboarding`、`p10-tabs-overview`、`p10-object-browser`、`p10-object-store-real`、`p10-redis-keys` | 引导、标签概览、对象浏览器、真 S3 端点、真 Redis | object-store-real / redis-keys 需真容器 |

完整有序清单见 `scripts/shots.sh` 的 `SHOTS=` 数组（约 60 条 + 可选真端点帧）。

---

## 2. 怎么拍（单张 / 全量）

### 前提：JDK 25–27

`scripts/_env.sh` 会按优先级自动选 JDK：显式 `JAVA_HOME` → `$HOME/.workbuddy/jdk/*` → macOS `java_home`。不需要全局安装 Maven（仓库自带 `./mvnw`）。

### 拍一张

```bash
./scripts/shots.sh p2-users
```

脚本会：
1. `./mvnw -B -q clean install -DskipTasks`（**必须 install，否则拍到 ~/.m2 里的旧 jar**）；
2. 启动 `bootstrap/adb-desktop` 的 `javafx:run`，按环境变量摆态；
3. 等待指定毫秒，截主窗口快照写入 `docs/screenshots/<name>.png`；
4. 与 `HEAD` 中已入库版本比尺寸，不一致则**弃帧并报红**（除非 `ADB_SHOT_DIMS_OK=1` 显式认领）；
5. 全量结束后做 md5 去重，相同帧报“假帧”。

### 拍全部

```bash
./scripts/shots.sh
```

亮色 56 张 + 暗色 8 张，**暗色必须先拍**（主题偏好会持久化，脚本固定顺序：`p5-dark` 最先，其余 `ADB_THEME_FORCE=light`）。全量约需数十分钟，视机器而定。

### 拍真服务端帧（需要 Docker 矩阵）

```bash
# 1) 起矩阵并导出环境
scripts/dbmatrix.sh up
eval "$(scripts/dbmatrix.sh env)"

# 2) 拍绑矩阵的帧（如 p3-query-catalog / p6-design-view-mysql / p10-redis-keys / p10-object-store-real）
./scripts/shots.sh
```

环境缺口时脚本会**跳过并说明**，不会退回演示库假拍（`ADB_MATRIX` 落点门）。缺 `ADB_TEST_S3_ENDPOINT` 时 `p10-object-store-real` 不进入批量清单。

---

## 3. 环境变量与摆态机制

每帧通过 `ADB_*` 环境变量在 `AppShell` 里“摆态”（程序化预置页面、弹窗、选中行、菜单等），不依赖人工点击，保证可复现：

| 变量 | 作用示例 |
|---|---|
| `ADB_OPEN=users` | 启动后直接打开 users 表 |
| `ADB_QUERY=1` | 打开查询页 |
| `ADB_STRUCTURE=users` | 打开结构页 |
| `ADB_NEW_TABLE=1` + `ADB_NEW_TABLE_POSE=fields/index/sql/checks/comment` | 建表弹窗各页 |
| `ADB_NEW_VIEW=1` + `ADB_NEW_VIEW_POSE=create/invalid/design` | 视图设计器 |
| `ADB_CONNECT_WIZARD=type/general/ssl/ssh/sshkey/family-objectstore` | 连接向导各页 |
| `ADB_MATRIX=mysql` + `ADB_CONNECTION=matrix-mysql` | 真 MySQL 矩阵连接 |
| `ADB_THEME_FORCE=light\|dark` | 强制主题（不走点击切换，避免空白帧） |
| `ADB_PREFS_RESET=1` + `ADB_PREFS_NODE=io/agenticdb/screenshots` | 隔离用户偏好，同一份脚本在不同机器拍出一致图 |
| `ADB_CONNECTIONS_FILE=.../adb-shots-connections.json` | 连接档案落临时文件，不污染用户 `~/.agenticdb` |

摆态失败（如真库未起）时 `AppShell` 主动放弃该帧，脚本退出码非零；不允许“拍出一张看着像的假图充数”。

---

## 4. 质量门禁（脚本自动 + CI）

1. **安装先行**：`shots.sh` 第一步 `clean install`，打印 jar 时间戳供核对（历史坑：改了 CSS 截图不变，因 jar 是两小时前的）。
2. **尺寸一致**：新帧尺寸须与 `HEAD` 已入库版本一致；不一致默认弃帧，计入 `discarded`，脚本末尾按 `missing + discarded > 0` 报红退出。
3. **md5 去重**：`docs/screenshots/` 全目录逐张比 md5，重复即假帧，warn 并计入 `duplicates`。
4. **人工核对（必做）**：脚本结束提示“下一步必须人工核对：状态栏读得清吗 / 按钮有层级吗 / 图标对齐吗 / 有没有空白帧”。建议用预览工具逐张看，重点查非背景像素占比（空白帧判据）。

---

## 5. 常见坑与避坑（脚本注释已记，此处摘要）

| 坑 | 表现 | 避法 |
|---|---|---|
| 快门落在瞬态（hover 高亮、弹层未就位） | 尺寸对、字节对、与 HEAD 逐像素对比多出一块 | 目视之外加 `git show HEAD:<帧>` 逐像素比；变化必须只落在本轮盒子里 |
| 主题热切换后 `scene.snapshot()` 空白帧 | 拍出全白或旧主题 | 一律用 `ADB_THEME_FORCE` 在启动时定主题，绝不靠点击切换 |
| 相对路径 `ADB_SHOTS` 落错目录 | 图不在 `docs/screenshots/` | 脚本固定用 `$PWD/docs/screenshots` |
| 连接档案污染 | 上一帧的 matrix 档案漏进下一张图，树出现多余节点 | 每帧子 shell 隔离，`rm -f "$ADB_CONNECTIONS_FILE"` |
| 测试 JVM 反复启动 App 污染 `~/.agenticdb/logs` | 启动日志混入真用户记录 | 测试 JVM `adb.startup.log=false` |
| macOS 无 `timeout` 命令 | 脚本永久挂住 | `run_with_timeout()` 用 perl `alarm` 兜底 |
| macOS bash 3.2 无关联数组 / 无 `md5sum` | 去重脚本报错 | 用 `md5` 兜底，用字符串查找代替 `declare -A` |

---

## 6. 截图用于 README / 发布素材

- **Hero 图**：全宽 1280+，建议 `p0-shell`（亮色首屏）或 `p2-users`（数据网格），放在 README 首屏。
- **功能图/GIF**：写安全三层（SQL 预览 → 单事务 → 反向撤销）、数据网格编辑、查询编辑器补全、双主题切换。可从现有帧裁剪，或用 `shots.sh` 单帧重拍。
- **深色档**：必须至少一张暗色首屏（`p5-dark` / `p2-users-dark`），证明双主题真可用。
- **不要放入仓库**：Navicat 商业软件截图（`docs/screenshots/navicat/`）仅供本地对拍量具，**不进 git**，对外文档不得出现。
