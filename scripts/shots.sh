#!/usr/bin/env bash
# 重拍 docs/screenshots/ 的验收截图（61 张）。
#
# 用法：
#   ./scripts/shots.sh              # 全部重拍（亮色 53 张 + 暗色 8 张；
#                                   #   其中 10 张绑 ADB_MATRIX 落点门、拍在 Docker 矩阵的真库上，
#                                   #   另 2 张只借 ADB_TEST_* 注册五份连接档案 —— 图形取自档案的
#                                   #   kind、不取自会话，所以它们不过落点门，见 p0-tree-vendors 那条）
#   ./scripts/shots.sh p2-users     # 只拍指定的一张
#
# 为什么需要这个脚本（历史上踩过的坑，全部在这里被绕开）：
#
#   坑 1（最贵的一个）—— 不先 install，拍到的是旧界面。
#     javafx:run -pl 不带 -am 时，Maven 从 ~/.m2 取 adb-ui-shell 的已安装 jar。
#     实测踩过：改了半天 CSS，截图里一点没变，因为 jar 还是两小时前的。
#     → 本脚本第一步就是 install，并打印 jar 时间戳供核对。
#
#   坑 2 —— 主题偏好会持久化。暗色必须第一个拍，否则后面全是暗色。
#     → 本脚本固定顺序：p5-dark 最先，其余用 ADB_THEME_FORCE=light 钉住。
#
#   坑 3 —— 主题热切换后 scene.snapshot() 会拿到空白帧。
#     → 一律用 ADB_THEME_FORCE 在启动时定主题，绝不靠点击切换。
#
#   坑 4 —— ADB_SHOTS 必须是绝对路径，相对路径会落到模块工作目录。
#     → 本脚本用 "$PWD/docs/screenshots"。
#
#   坑 5（R43-c 实拍踩到）—— 连拍会拍到**瞬态**，而 md5 去重门禁抓不到它。
#     一次连拍 12 张，六张与已入库版本逐像素一比就露馅：p2-users / p2-find /
#     p2-transaction 各多出一条 22px 的**行高亮带**（物理鼠标恰好压在窗口那一行，
#     :hover 进了快照），p2-details 多一条侧栏树行的，p2-form 干脆落在 Structure
#     切面上（pose 还没就位就拍了）。这些帧尺寸对、字节数正常、彼此不重复 ——
#     只有和 HEAD 逐像素比才看得出来。
#     → 目视之外再加一道量：把 `git show HEAD:<帧>` 落到临时文件再与新帧比，
#       变化必须**只**落在本轮改动的那块盒子里，多一处就单帧重拍。

source "$(cd "$(dirname "$0")" && pwd)/_env.sh"

SHOTS_DIR="$PROJECT_ROOT/docs/screenshots"
ONLY="${1:-}"

# ── UX-0：可复现性 ────────────────────────────────────────────────
# 密度必须钉住：上一轮实拍里 p2-users 行高是 34（持久化的 comfort），
# 而默认是 28 —— 同一份脚本在不同机器/历史上拍出的图不一样。
# 偏好根也要隔离并在每次启动时清空，否则侧栏宽度 / 上次连接仍会漂移。
# 第二十一轮：默认档位改成 normal；2026-10-26 用户裁定「保持和 Navicat 一样」后，
# normal 对应列表/树 21px、记录网格 23px（旧「网格低一档」映射作废）。
# 截图跟着钉 normal —— 拍的必须是用户首启看到的样子。
export ADB_DENSITY="${ADB_DENSITY:-normal}"
export ADB_PREFS_NODE="${ADB_PREFS_NODE:-io/agenticdb/screenshots}"
export ADB_PREFS_RESET="${ADB_PREFS_RESET:-1}"
# 不依赖「上次用的连接」；demo 由 ConnectionStore.ensureDemo 保证存在。
export ADB_CONNECTION="${ADB_CONNECTION:-demo}"
# ⭐ R47-b：连接档案也隔离。此前「拍一次验收图」= 往用户真实的
# ~/.agenticdb/connections.json 里写一条（ensureDemo 与 touch 都落盘）——
# 验收不该改别人的清单，真库矩阵的临时档案更不该落进去。
export ADB_CONNECTIONS_FILE="${ADB_CONNECTIONS_FILE:-${TMPDIR:-/tmp}/adb-shots-connections.json}"

# name|click-id|wait-ms|预置页面环境变量
# 顺序即拍摄顺序：暗色必须最先。
SHOTS=(
  "p5-dark||5000|ADB_CONNECTIONS=1 ADB_THEME_FORCE=dark"
  # ⭐ U23：暗色不能只有一帧 —— 勾选框与禁用态的问题正是在暗色里暴露的。
  #   p2-users-dark 拍数据网格（行首选择框 + 工具栏复选），
  #   p7-new-index-dark 拍索引弹窗（「唯一」复选 + 添加列按钮）。
  #   必须紧跟 p5-dark：主题偏好会持久化，暗色帧全部排在亮色之前。
  "p2-users-dark||5000|ADB_OPEN=users ADB_THEME_FORCE=dark"
  "p7-new-index-dark||6000|ADB_INDEX=1 ADB_INDEX_POSE=1 ADB_THEME_FORCE=dark"
  "p0-shell||2500|ADB_THEME_FORCE=light"
  # ⭐ R54：左树里五家真服务器连接与演示库并排，每家顶**自己那枚**图形。
  #   这一帧证的是图形、不是连接状态：图形取自连接档案的 kind（离线也照样画得出），
  #   所以它不走 ADB_MATRIX 那条落点门；环境完全没配时外壳直接弃帧（退出码 3），
  #   不会退回到「只有演示库一行」拍一张名字写着五家的图。
  "p0-tree-vendors||6000|ADB_TREE_VENDORS=1 ADB_THEME_FORCE=light"
  # ⭐ R55-d：同一棵树再看暗色一档。品牌色两档是**不同**的字面值（亮档深、暗档亮），
  #   R54 只拍了亮档等于五家的图形在暗底上一眼都没被看过 —— 而画不出来的症状
  #   正是「图标糊成一片黑 / 白底方块压在深色侧栏上」。
  "p0-tree-vendors-dark||6000|ADB_TREE_VENDORS=1 ADB_THEME_FORCE=dark"
  "p2-users||5000|ADB_OPEN=users ADB_THEME_FORCE=light"
  # ⭐ UX-6：数据页「概览」切面（行数 / 大小 / 引擎 / 索引数 / 最近操作）。
  #   切面标签 id 是动态拼的（object-tab-<id>），不能当静态点击目标；
  #   ADB_OVERVIEW=1 与 ADB_DDL 同型，等页面建好后程序化切换。
  "p2-overview||7000|ADB_OPEN=users ADB_OVERVIEW=1 ADB_THEME_FORCE=light"
  # ⭐ R34 第十六批：只读切面那一帧 —— ObjectSectionsPanel 的索引表（列头 /
  #   是·否 / 来源 / 空态）以前没有任何帧覆盖，i18n 抽完只能靠单测。
  #   ADB_SECTION=<切面 id> 与 ADB_OVERVIEW 同型，等页面建好后程序化切换。
  "p2-section-indexes||7000|ADB_OPEN=users ADB_SECTION=indexes ADB_THEME_FORCE=light"
  "p2-details||7000|ADB_OPEN=users ADB_DETAILS=1 ADB_THEME_FORCE=light"
  # ⭐ UX-5-F：唯一拍到「交互态」的一帧 —— 选中行 + 光标格 + 抽屉编辑器。
  #   ADB_CELL=1 让数据页自己摆状态，点击真按「编辑」按钮进入编辑态。
  "p2-cell|data-details-edit|9000|ADB_OPEN=users ADB_CELL=1 ADB_THEME_FORCE=light"
  # ⭐ R32-A（G2）：事务中那一帧 —— 「开始事务」整颗退场，提交/回滚现身。
  #   点击真按 #data-begin-tx（不是程序化摆态），所以这一帧同时证的是
  #   「按钮可见性由状态驱动」这条实现本身。R39 起状态栏不再有「事务中」段
  #   （段位只剩页型相关的几项），事务状态只看工具条这两颗键。
  #   9000ms：数据页是异步灌行的，载入没完「开始事务」还禁用着，点下去等于空拍。
  "p2-transaction|data-begin-tx|9000|ADB_OPEN=users ADB_THEME_FORCE=light"
  # ⭐ R32-G3：查找条那一帧 —— 状态程序化摆（ADB_FIND 开条 + 填 needle），
  #   「下一个」由脚本真点 #data-find-next，于是这一帧同时证的是
  #   「点它会推进命中并在状态栏报第 x / y 个」。9000ms：行是异步灌的，
  #   没灌完 poseFindBar 会一直返回 false，点下去等于拍一张没开条的网格。
  "p2-find|data-find-next|9000|ADB_OPEN=users ADB_FIND=1 ADB_THEME_FORCE=light"
  # ⭐ R32-G4：表单视图那一帧 —— ADB_FORM 摆出「表单视图 + 选中第一条」，
  #   「下一行」由脚本真点 #data-record-next。于是这一帧同时证的是
  #   「记录导航翻行时表单跟着翻」这条联动本身（不是两张各自摆拍的静态图）。
  "p2-form|data-record-next|9000|ADB_OPEN=users ADB_FORM=1 ADB_THEME_FORCE=light"
  "p6-tables||5000|ADB_TABLES=1 ADB_THEME_FORCE=light"
  "p6-tables-grid||5000|ADB_TABLES=1 ADB_TABLE_VIEW=grid ADB_THEME_FORCE=light"
  "p3-query||6000|ADB_QUERY=1 ADB_THEME_FORCE=light"
  # ⭐ U25：右键菜单帧 —— 结果网格上摆出真菜单（复制单元格 / 行 / 列名）。
  "p3-query-menu||8000|ADB_QUERY=1 ADB_MENU_POSE=1 ADB_THEME_FORCE=light"
  # ⭐ R47-b：库▾ 的真库帧 —— 这颗控件（R44-G1）交付至今一张帧都没有，
  #   因为演示库是 SQLite、没有库一层，下拉里永远没有第二把库可显示。
  #   现在拍在 Docker 矩阵的真 MySQL 上：清单来自 SHOW DATABASES（shop / shop2），
  #   勾在当前库，状态栏写「已切到 …」。环境没配时这一格跳过，不退回演示库假拍。
  "p3-query-catalog||14000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_CATALOG_POSE=menu ADB_THEME_FORCE=light"
  #   成对的另一张：点进第二把库**之后**的页面态（行 2 的名字、状态栏的「已切到 …」、
  #   侧栏跟着换库）。菜单那张证「有两把库」，这张证「切得过去」。
  "p3-query-catalog-bound||17000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_CATALOG_POSE=bound ADB_THEME_FORCE=light"
  # ⭐ R57 画面债（第一格）：SQL 补全弹层。R57-a 交付时只有行为门禁 —— 弹层是 ControlsFX 的
  #   PopupWindow，主场景快照拍不到它，所以这一格当时欠着。现在 ScreenshotSession 会把浮层
  #   单独快照再按场景坐标合成上去（不改成「只拍那一列候选的小图」：那证不了「贴在光标下面」）。
  #   SQL 以 "SELECT * FROM " 结尾，候选 = 真库的对象清单；演示库没有证据价值，读不到就弃帧。
  #   ⚠️ wait 必须**大于**摆态的弃帧预算（QueryPage 里 80 跳 × 150ms ≈ 12s）：
  #   快门先落就把「还在等候选」的那一帧当成品写出去了 —— 尺寸对、字节对、画面里没有弹层。
  "p3-query-completions||20000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_COMPLETION_POSE=popup ADB_THEME_FORCE=light"
  # ⭐ R57 画面债（第二格）：圈住第二段跑。两条语句都真跑得通，编辑器里是真选区，
  #   状态栏回显 / 结果页签标题 / 危险分级全部读同一个 statementsToRun() 出口 ——
  #   这一帧证的是「跑的就是圈住的那段」，不是摆出来的高亮。
  "p3-query-selection||17000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_SELECTION_POSE=second ADB_THEME_FORCE=light"
  "p4-structure||5000|ADB_STRUCTURE=users ADB_THEME_FORCE=light"
  "p4-ddl||7000|ADB_DDL=1 ADB_THEME_FORCE=light"
  # ⭐ D-23：列对话框那一帧 —— 类型下拉的候选与默认值现在都来自方言，
  #   不再是一份硬编码的 SQLite 清单（SQLite 的默认值因此从 TEXT 变成 VARCHAR，
  #   这一帧拍的就是合上时看得见的那个默认值）。
  #   ⚠️ 候选清单跟着库变这件事**帧拍不到**：JavaFX 的 ComboBox 弹层是独立窗口，
  #   capture() 只吃主 Stage —— 所以那一半由 StructureColumnTypeTest + 真库矩阵守。
  "p7-new-column|structure-add|6000|ADB_STRUCTURE=users ADB_THEME_FORCE=light"
  "p5-connections||4000|ADB_CONNECTIONS=1 ADB_THEME_FORCE=light"
  # ⭐ R54：同一份清单在连接管理器里的样子 —— 「类型」列此前只有文字，
  #   而 MySQL / MariaDB 首字母相同、PostgreSQL / Oracle 都是七个字母以上，
  #   只给文字等于让人逐字读。这一帧拍到六行六个不同图形（含演示库 SQLite）。
  "p5-connections-vendors||6000|ADB_TREE_VENDORS=1 ADB_CONNECTIONS=1 ADB_THEME_FORCE=light"
  "p6-new-table||5000|ADB_NEW_TABLE=1 ADB_THEME_FORCE=light"
  # ⭐ R31：字段页的「深度」那一帧 —— 类型 / 长度 / 小数 + 自增 + 属性面板都有真值，
  #   只拍一张空网格等于什么都没验（G15~G18 的验收面）。暗色同型再看一次属性面板底色。
  "p6-new-table-fields||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=fields ADB_THEME_FORCE=light"
  "p6-new-table-fields-dark||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=fields ADB_THEME_FORCE=dark"
  # ⭐ R31：备注单独一页后，表注释进不进得了计划要在帧里看得见。
  "p6-new-table-comment||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=comment ADB_THEME_FORCE=light"
  # ⭐ R30-D：设计器是多页弹窗。空的对象页 / 空预览证不了「六页收敛成一个计划」，
  #   所以各拍一帧带真对象的：索引页（列表 + 计数）与 SQL 页（建表 + 索引两条语句）。
  "p6-new-table-index||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=index ADB_THEME_FORCE=light"
  "p6-new-table-sql||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=sql ADB_THEME_FORCE=light"
  # ⭐ 页签的底色方向（栏用画布、选中页用 surface）只在暗色下看得出来 ——
  #   浅色主题里两者都是 #FFFFFF，光拍浅色等于没验这条规则。
  "p6-new-table-dark||5000|ADB_NEW_TABLE=1 ADB_NEW_TABLE_POSE=index ADB_THEME_FORCE=dark"
  # ⭐ R42 N28-a：视图设计器三帧。
  #   create   —— 名字 + 真查询 + 方言拼出来的 CREATE VIEW 预览（空弹窗证不了形态）；
  #   invalid  —— 正文里塞两句，预览必须空着、错误必须显出来（校验拒绝这一态要有画面）；
  #   design   —— 从演示库读回 v_active_users 的定义再开弹窗，
  #               这一帧同时是「读得出形状才会开弹窗」的证据。
  "p6-new-view||5000|ADB_NEW_VIEW=1 ADB_NEW_VIEW_POSE=create ADB_THEME_FORCE=light"
  "p6-new-view-dark||5000|ADB_NEW_VIEW=1 ADB_NEW_VIEW_POSE=create ADB_THEME_FORCE=dark"
  "p6-new-view-invalid||5000|ADB_NEW_VIEW=1 ADB_NEW_VIEW_POSE=invalid ADB_THEME_FORCE=light"
  # ⭐ R47-b：新建视图弹窗的 MySQL 形态 —— 预览里的反引号与 CREATE VIEW 直接来自方言，
  #   演示库（SQLite）那张拍不出这一侧。此前只有方言单测门，没有画面。
  "p6-new-view-mysql||7000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_NEW_VIEW=1 ADB_NEW_VIEW_POSE=create ADB_THEME_FORCE=light"
  "p6-design-view||7000|ADB_DESIGN_VIEW=1 ADB_THEME_FORCE=light"
  # ⭐ R47-b：D-29 的画面证据。演示库（SQLite）上「重建视图」是两句（DROP + CREATE，
  #   带灰字提示、多要一次确认），MySQL 8.4 实测收单句 CREATE OR REPLACE ——
  #   这个差别此前只有单测门，因为截图通道一直绑不到真服务器。
  #   前提是矩阵里有 v_active_users 可读回（dbmatrix.sh 的 seed_frames 就是为这一格种的）。
  "p6-design-view-mysql||11000|ADB_MATRIX=mysql ADB_CONNECTION=matrix-mysql ADB_DESIGN_VIEW=1 ADB_THEME_FORCE=light"
  # ⭐ R47-d：同两格在另外三家方言各拍一次。这一轮之前它们只有单测门 + 真库契约，
  #   画面只有 MySQL 一家 —— 而「引号形状」恰恰是只有画面能验的东西。
  #   前提是这三门都得先把种子铺到自己那家（dbmatrix.sh seed 现在是五家）。
  #   ⚠️ 等待秒数一家比一家长（13 起）：连接是异步的，而 D-39 实拍踩过一次 ——
  #   Oracle 首次连接实测 8~12 秒，7 秒的等待里快门落在「Connecting to matrix Oracle…」上，
  #   拍出来是演示库的画面、文件名却写着 oracle。现在落点不对就直接 abort（退出码 3），
  #   所以「等待给少了」的表现是这一格报红，而不是一张安静的假帧。
  #   ⚠️ 新建那一格只留 SQL Server 一家：实拍回来 PG 与 Oracle 的预览和演示库那张
  #   **逐字节相同**（三张都是 25490 字节）—— 这三家都用双引号包标识符，
  #   形状差异为 0。帧去重门（同画面即假帧）会把它们报成重复，而它们不是假、是**冗余**：
  #   同一张画面存三份，只会让下一次真重复混进噪音里。双引号这一侧由 p6-new-view 那张担，
  #   PG / Oracle 的证据在下面的 design-view（读回的正文三家各不相同）。
  "p6-new-view-sqlserver||13000|ADB_MATRIX=sqlserver ADB_CONNECTION=matrix-sqlserver ADB_NEW_VIEW=1 ADB_NEW_VIEW_POSE=create ADB_THEME_FORCE=light"
  #   重建那一侧：五家里只有 SQLite 需要两句，另外四家都是一句 ——
  #   「一句」这件事此前只在 MySQL 上有帧。三家的读回形状各不同，所以三张都留：
  #   PG 是 pg_get_viewdef 的多行、SQL Server 是 sys.sql_modules 的原文一行、
  #   Oracle 是 DBMS_METADATA 带 schema 前缀的一行；写法上 SQL Server 是
  #   CREATE OR ALTER（不是 OR REPLACE），值得单独一张。
  "p6-design-view-pg||14000|ADB_MATRIX=postgresql ADB_CONNECTION=matrix-postgresql ADB_DESIGN_VIEW=1 ADB_THEME_FORCE=light"
  "p6-design-view-sqlserver||16000|ADB_MATRIX=sqlserver ADB_CONNECTION=matrix-sqlserver ADB_DESIGN_VIEW=1 ADB_THEME_FORCE=light"
  "p6-design-view-oracle||19000|ADB_MATRIX=oracle ADB_CONNECTION=matrix-oracle ADB_DESIGN_VIEW=1 ADB_THEME_FORCE=light"
  # ⭐ §6.4：这一帧要拍到「已选列 + 序号 + 预览 SQL + 可用的移动钮」，
  #   光弹一个空弹窗等于没拍这个弹窗的核心交互（空态由 IndexDialogTest 守）。
  "p7-new-index||6000|ADB_INDEX=1 ADB_INDEX_POSE=1 ADB_THEME_FORCE=light"
  # ⭐ R33-G5：导入向导 —— 外壳先写一份 GBK 演示 CSV，向导摆出「GBK 解码 +
  #   表头第 1 行 + 预览已读出中文 + 字段映射」，证的是编码/表头行真生效
  #   （空窗与 UTF-8 假数据都证明不了）。9000ms：数据页要先把表结构读回来。
  "p8-import||9000|ADB_OPEN=users ADB_IMPORT=1 ADB_THEME_FORCE=light"
  # ⭐ R33-G6：导出向导 —— 摆「取消勾选一列 + XML 格式 + 目标路径」，
  #   证的是列选择与 XML/HTML 两种新格式在界面上真的可选、可用性跟着格式走。
  "p8-export||9000|ADB_OPEN=users ADB_EXPORT=xml ADB_THEME_FORCE=light"
  # ⭐ R33-G20 / R40 N27-a → R43-b：连接向导的类型选择页 —— 一行 6 张彩色磁贴
  #   （只摆真有驱动的六家，块居中不留下半屏空白）+ 一行「其它怎么走」的提示 + URI 入口。
  #   不需要数据库连接，弹得快；4000ms 只等启动。
  "p5-connect-type||4000|ADB_CONNECT_WIZARD=type ADB_THEME_FORCE=light"
  # ⭐ R43-a：厂商图形的暗色档单独一帧。品牌色两档是**不同**的字面值（亮档深、暗档亮），
  #   只拍亮档等于六家在暗色档一眼都没被看过 —— 而画不出来的症状是「图标糊成一片黑」。
  "p5-connect-type-dark||4000|ADB_CONNECT_WIZARD=type ADB_THEME_FORCE=dark"
  # ⭐ R33-G8/G20：7 页属性 —— 常规 / SSL / SSH 各一帧。
  #   SSH 页在 G5a 之前是「明说这一版没有隧道引擎 + 给 ssh -L 替代做法」那一屏；
  #   引擎落地后那句谎撤了，这一帧现在要证的是「勾上以后地址/端口/用户/认证方式
  #   真的能填、填了真的进档案」——摆一张灰掉的空表单等于没拍这一页。
  #   （值的去向由 ConnectionWizardTest 那批字段门禁量，帧给人看，门给机器看。）
  "p5-connect-general||4000|ADB_CONNECT_WIZARD=general ADB_THEME_FORCE=light"
  "p5-connect-ssl||4000|ADB_CONNECT_WIZARD=ssl ADB_THEME_FORCE=light"
  "p5-connect-ssh||4000|ADB_CONNECT_WIZARD=ssh ADB_THEME_FORCE=light"
  # ⭐ D-41：私钥认证分支单独一帧。上面那张 ssh 拍的是「口令」分支 —— 两张表单控件不同，
  #   而「内置 SSH 库不支持 ed25519，请改用 RSA/ECDSA」这句实话只长在私钥分支里。
  #   只拍口令分支等于给 D-41 的记录配了一张证明不了它的图。
  "p5-connect-ssh-key||4000|ADB_CONNECT_WIZARD=sshkey ADB_THEME_FORCE=light"
  # ⭐ P2-2：错误详情弹窗 —— 分类徽标 + 驱动原文 + 建议 + 可复制的 SQL。
  #   8000ms（不是 5000）：连拍 18 次冷启动时这台机器实测会慢过 5s ——
  #   弹窗还没开就拍，得到一张与 p0-shell 同 md5 的假帧（U7 去重门禁抓到过，
  #   单帧重拍即真）。留出余量，假帧仍由去重门禁兜底。
  "p2-error||8000|ADB_ERROR_DEMO=1 ADB_THEME_FORCE=light"
  # ── R34 收官：第二十至二十五批那 275 条文案的零覆盖面补帧 ──────────────
  #   这些批只降了棘轮、没有一张帧，等于「抽完没人看过」。六个开关各摆一面：
  #   设置页 / 诊断页 / 收藏夹管理器 / 已存查询管理器 / 重命名弹窗 / 列筛选浮窗。
  "p9-settings||5000|ADB_SETTINGS=1 ADB_THEME_FORCE=light"
  "p9-diagnostics||8000|ADB_DIAGNOSTICS=1 ADB_THEME_FORCE=light"
  "p9-favorites||6000|ADB_FAVORITES=1 ADB_THEME_FORCE=light"
  "p9-saved-queries||6000|ADB_SAVED=1 ADB_THEME_FORCE=light"
  # 9000ms：两轮轮询（连接 → 行数据灌进来）都要等够，列表空着时
  # renameSelected() 只会在状态栏报「先选中一张表」，弹窗根本不开。
  "p6-rename-table||9000|ADB_RENAME=users ADB_THEME_FORCE=light"
  # 列筛选浮窗：锚点那颗漏斗的 id 是动态拼的（data-filter-<列>），静态审计只认源码里
  # 字面存在的 setId，脚本点不到 —— 所以由 ADB_FILTER 在数据页里 fire 那颗按钮。
  # 9000ms：表结构与列头是异步建的，列头没建出来 poseFilterPanel 一直返回 false。
  "p2-filter||9000|ADB_OPEN=users ADB_FILTER=1 ADB_THEME_FORCE=light"
  # ⭐ R38 N31：内嵌 Filter & Sort 面板 —— 工具条按钮呈按下态，面板把网格往下推；
  #   两行条件 + and 连接词 + Sort By 芯片 + Criteria Edited 脏标记都在画面里。
  #   10000ms：连接 → 表结构 → 行 两轮轮询都要等够，面板才有列可摆。
  "p2-filter-sort||10000|ADB_OPEN=users ADB_FILTER_SORT=1 ADB_THEME_FORCE=light"
)

step "编译并安装（否则拍到的是旧界面）"
require_toolchain
./mvnw -B -q clean install -DskipTests
ok "已安装，核对 jar 时间戳："
ls -la ~/.m2/repository/io/agenticdb/adb-ui-shell/1.0.0-SNAPSHOT/*.jar 2>/dev/null \
  | sed 's/^/    /' || warn "没找到 adb-ui-shell 的 jar"

mkdir -p "$SHOTS_DIR"

# macOS 没有 timeout 命令；用 perl 兜底，保证异常时脚本不会永久挂住。
run_with_timeout() {
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$secs" "$@"
  elif command -v perl >/dev/null 2>&1; then
    perl -e 'alarm shift; exec @ARGV' "$secs" "$@"
  else
    "$@"
  fi
}

captured=0
# ⭐ D-37b：没产出的格数。以前「一帧都没拍出来」和「全拍完了」在退出码上长得一样
#   （只 warn，最后照样 ok + 返回 0），而这一轮起 AppShell 会**主动放弃**前提没成立的
#   真库帧 —— 那条正确路径必须让脚本自己报失败，不然「跳过」看起来像「成功」。
missing=0
for shot in "${SHOTS[@]}"; do
  IFS='|' read -r name click wait envvars <<<"$shot"

  if [ -n "$ONLY" ]; then
    # 支持逗号分隔的多帧（`./shots.sh p6-new-table,p6-new-table-sql`）——
    # 单帧重拍一次要重装一次，复查同一轮的两三张图没必要装三遍。
    case ",$ONLY," in
      *",$name,"*) ;;
      *) continue ;;
    esac
  fi

  # ⭐ R47-b：真库帧的门控。ADB_MATRIX 那几格拍的是 Docker 矩阵里的真服务器，
  #   环境没配时**跳过并说明**，不能让 AppShell 退回演示库去拍一张「看着像真库」的图 ——
  #   那正是这颗控件欠帧的原因（演示库是 SQLite，没有库一层）。
  #   ⭐ D-37 补两条：① 取值必须是方言 id（打错了不是「环境没配」，是脚本坏了，要当场失败）；
  #   ② 查**那一家自己**的口令 —— 原来只查 ADB_TEST_MYSQL_PASSWORD，等于给
  #     postgresql / oracle 那几格开了张永远成立的条件：MySQL 的口令在，就照样往下拍。
  case "$envvars" in
    *ADB_MATRIX=*)
      matrix_engine="$(printf '%s\n' "$envvars" | tr ' ' '\n' | sed -n 's/^ADB_MATRIX=//p')"
      case "$matrix_engine" in
        mysql|mariadb|postgresql|sqlserver|oracle) ;;
        *)
          echo "  ✗ $name：ADB_MATRIX 取值「$matrix_engine」不是方言 id" \
               "（可用：mysql / mariadb / postgresql / sqlserver / oracle）" >&2
          exit 1 ;;
      esac
      case "$matrix_engine" in
        postgresql) matrix_prefix=PG ;;
        sqlserver) matrix_prefix=MSSQL ;;
        *) matrix_prefix="$(printf '%s' "$matrix_engine" | tr '[:lower:]' '[:upper:]')" ;;
      esac
      eval "matrix_password=\${ADB_TEST_${matrix_prefix}_PASSWORD:-}"
      if [ -z "$matrix_password" ]; then
        echo "  · 跳过 $name（真库帧）：环境里没有 ADB_TEST_${matrix_prefix}_PASSWORD" \
             "—— 先跑 scripts/dbmatrix.sh up && eval \"\$(scripts/dbmatrix.sh env)\""
        continue
      fi ;;
  esac

  step "拍摄 $name"
  rm -f "$SHOTS_DIR/$name.png"

  # 读 PNG 的宽高：IHDR 从第 12 字节起，宽在 16–19、高在 20–23（大端）。
# 尺寸都远小于 65536，所以只取第 3、4 字节即可。
png_dims() {
  od -An -tu1 -j16 -N8 "$1" | tr -s ' ' \
    | awk 'NF >= 8 {printf "%dx%d", $3 * 256 + $4, $7 * 256 + $8}'
}

# 每张图一个干净环境：用子 shell 隔离，ADB_* 不会泄漏到下一张。
  # （不能写成 `env A=1 run_with_timeout ...` —— env 只认可执行文件，
  #   认不出 shell 函数。）
  (
    # ⭐ 连**连接档案清单**一起重置，不只是环境变量。踩到的形状：ADB_CONNECTIONS_FILE
    #   是一个固定的临时路径，上一轮真库帧在里面留下的四行 matrix 档案会漏进之后每一张图 ——
    #   本轮重拍 p3-query 时左树多出 matrix Oracle / SQL Server / PostgreSQL / MySQL 四行，
    #   而同一套帧里的 p0-shell 没有。**同一套帧出现两种树，帧就不能再当验收证据。**
    #   （档案本身不带口令：真库口令只从 ADB_TEST_* 读、只活在进程里。）
    rm -f "$ADB_CONNECTIONS_FILE"
    export $envvars
    export ADB_SHOTS="$SHOTS_DIR"
    export ADB_SHOTS_NAME="$name"
    export ADB_SHOTS_WAIT="$wait"
    export ADB_SHOTS_CLICK="$click"
    run_with_timeout 180 ./mvnw -B -pl bootstrap/adb-desktop javafx:run
  ) >"/tmp/adb-shot-$name.log" 2>&1 || true

  if [ -f "$SHOTS_DIR/$name.png" ]; then
    size="$(wc -c <"$SHOTS_DIR/$name.png" | tr -d ' ')"
    # ⭐ 尺寸要核对，而且要和**上一版**比。踩过一次：p2-find 拍出 1920×1017
    #   （窗口被最大化），其余全是 1280×800 —— 只看字节数看不出来，
    #   而宽屏帧会把「1280 下不溢出」这类结论整个带偏。
    #   不写死期望值：主窗口 1280×800、弹窗各有各的尺寸，列名单迟早漏一张；
    #   和已入库的那版比，变了就是变了。本机没有 PIL/identify，直接读 PNG IHDR。
    dims="$(png_dims "$SHOTS_DIR/$name.png")"
    rel="docs/screenshots/$name.png"
    # ⚠️ 别写成 `git show ... | od`：od 只吃 8 个字节就退出，git 收到 SIGPIPE
    #    以 141 退出，`set -o pipefail` 让整条命令 substitution 失败，
    #    而本脚本开了 `set -e` —— 结果拍第一张就整个脚本死掉（实测 rc=255，
    #    且日志停在「拍摄 p2-find」一行，看不出原因）。先落临时文件再读。
    prev_png="/tmp/adb-shot-prev-$name.png"
    prev=""
    if git -C "$PROJECT_ROOT" show "HEAD:$rel" >"$prev_png" 2>/dev/null; then
      prev="$(png_dims "$prev_png")"
    fi
    rm -f "$prev_png"
    if [ -n "$prev" ] && [ "$prev" != "$dims" ]; then
      warn "$name.png（${size} 字节 · ${dims}）—— 尺寸与已入库版本（${prev}）不一致"
    else
      ok "$name.png（${size} 字节 · ${dims}）"
    fi
    captured=$((captured + 1))
  else
    warn "$name.png 没生成，看日志：/tmp/adb-shot-$name.log"
    tail -n 15 "/tmp/adb-shot-$name.log" | sed 's/^/    /'
    missing=$((missing + 1))
  fi
done

step "结果"
# ⭐ D-37b：有格没产出就必须**失败**。真库帧的前提没成立时，AppShell 现在会放弃那一帧
#   （宁可没图，不可有假图）；那条正确路径要是在退出码上和「全拍完了」一样，
#   等于把「跳过」洗成「成功」—— 正是这条通道一开始要防的事。
if [ "$missing" -gt 0 ]; then
  fatal "$missing 张没生成（应产出 $((captured + missing)) 张，实际 $captured 张）—— 见上面的日志路径"
fi
ok "已产出 $captured 张 → $SHOTS_DIR"

# ── U7：帧去重 ──────────────────────────────────────────────────────
# 两张帧 md5 相同 = 拍的是同一个画面，那么其中一张一定是**假帧**（它声称的
# 那个界面根本没出现）。踩过：p2-error 与 p0-shell 完全一致 —— 错误弹窗是
# Alert，默认 Modality.NONE，而截图会话只认模态窗口，于是拍到的是主窗口。
# 这一步把「靠人眼发现重复」变成脚本自己报错。
if [ "$captured" -gt 0 ]; then
  step "帧去重（同画面即假帧）"
  # ⚠️ 不能用 `declare -A`：macOS 自带 /bin/bash 是 3.2，没有关联数组 ——
  #    实测这条在这里直接 `declare: -A: invalid option` 退出，
  #    于是「假帧门禁」在开发机上**从来没跑过**（脚本还显示拍摄成功）。
  #    同理 md5sum 是 GNU 的，macOS 只有 md5。两个都用兜底。
  png_digest() {
    if command -v md5sum >/dev/null 2>&1; then
      md5sum "$1" | cut -d' ' -f1
    else
      md5 -q "$1"
    fi
  }
  seen_list=""
  duplicates=0
  for png in "$SHOTS_DIR"/*.png; do
    [ -f "$png" ] || continue
    digest="$(png_digest "$png")"
    base="$(basename "$png")"
    # ⚠️ `|| true` 不能省：本脚本 source 了 `set -euo pipefail`，而 grep **没匹配时返回 1** ——
    #    第一张帧之前去重表是空的，那条 substitution 直接把整个脚本带走（和上面
    #    `git show | od` 的 SIGPIPE 是同一类坑，实测 rc=1、日志停在「帧去重」一行）。
    prior="$(printf '%s\n' "$seen_list" | grep -F "$digest " | head -1 | cut -d' ' -f2- || true)"
    if [ -n "$prior" ] && [ "$prior" != "$base" ]; then
      warn "$base 与 $prior 像素完全相同 —— 其中一张是假帧"
      duplicates=$((duplicates + 1))
    fi
    seen_list="$seen_list
$digest $base"
  done
  if [ "$duplicates" -gt 0 ]; then
    warn "共 $duplicates 组重复帧：核对对应页面的预置变量（ADB_*）与弹窗模态是否真的生效。"
  else
    ok "无重复帧（$captured 张各拍到了自己的画面）"
  fi
fi

warn "下一步必须人工核对：状态栏读得清吗 / 按钮有层级吗 / 图标对齐吗 / 有没有空白帧。"
info "人工核对建议（本机无 PIL，脚本不自动跑）：非背景像素占比过低 = 空白帧，重点查这一项。"
