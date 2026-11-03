#!/usr/bin/env bash
# 真库矩阵：五种引擎在本机 Docker 里起一遍，把「环境缺了就跳过」的门禁烧成必跑用例。
#
# 用法：
#   ./scripts/dbmatrix.sh up      # 起容器（口令随机生成，写进未入库的 .dbmatrix.env）
#   ./scripts/dbmatrix.sh status  # 各容器在不在、端口是多少
#   ./scripts/dbmatrix.sh env     # 打印 export 行，配合 eval 用
#   ./scripts/dbmatrix.sh run     # 跑 adb-jdbc 的真库契约矩阵
#   ./scripts/dbmatrix.sh seed    # 只重铺验收帧的内容（users / orders / v_active_users，幂等）
#   ./scripts/dbmatrix.sh tls-up  # 另起三个「只走 TLS」的对端容器 + 自签一套 PKI
#   ./scripts/dbmatrix.sh tls-status / tls-run / tls-down
#   ./scripts/dbmatrix.sh down [--purge-env]   # 删容器（卷一起删；TLS 层另用 tls-down）
#
# ⚠️ 口令**只**生成、只落在 scripts/.dbmatrix.env（已在 .gitignore 里）。
# 测试侧一律从环境变量读；把这个文件提交进仓库就是把口令提交进仓库。
#
# 端口故意避开默认口（3306/5432/1433/1521），
# 免得本机原本就在跑的数据库和测试容器互相抢地址。
#
# ⭐ R47 还有一层 **TLS 对端**（`tls-up` / `tls-status` / `tls-down` / `tls-run`）：
#   主矩阵那五个容器只起非加密服务，「证书链到底校不校验」这件事在那上面**问不出来**。
#   TLS 层用**另起的容器 + 另开的端口**（33071 / 33072 / 54331），主矩阵一行不动 ——
#   把已有容器改成加密的会让九条已绿的契约变成「在另一套环境下绿的」。
#   证书由本脚本用 openssl **现场自签**（一套真 CA + 一套冒名 CA + 一张只认
#   `localhost` 的服务器证书），全落在 scripts/.dbmatrix-tls/（已 gitignore，**里面有私钥**）。
#   为什么每张证书只写 `DNS:localhost`：这样**同一台服务器**同时给得出
#   「主机名对得上」（host=localhost）和「主机名对不上」（host=127.0.0.1）两种答案，
#   不用为了负例再开一个容器、也不用改本机 /etc/hosts。

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

ENV_FILE="$PROJECT_ROOT/scripts/.dbmatrix.env"
DB_NAME=shop
# ⭐ R52 第 9 条契约要的第二把库：「切库落点」不是「连上了新地址」，
# 是「同一台服务器、同一个连接串形状，只有库名换了」—— 所以要两个同位的对端。
# Oracle 没有这一层（一个服务名一个库，PDB 切换不是这个产品承诺的能力），
# 它的 DB2 键**故意不存在**，契约那条走「没有库层」那一支。
DB2_NAME=shop2
ORACLE_PDB=FREEPDB1

if command -v docker >/dev/null 2>&1; then
  DOCKER_BIN=docker
elif [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
  DOCKER_BIN=/Applications/Docker.app/Contents/Resources/bin/docker
else
  printf '\033[31m✗ 找不到 docker：装 Docker Desktop，或把 docker 放到 PATH 上\033[0m\n' >&2
  exit 1
fi

# 「引擎|容器名|镜像|宿主端口|容器端口」—— 加引擎只改这张表。
# 前缀要和 ExternalDatabase.java 里读的 ADB_TEST_<前缀>_* 对得上。
IMAGES=(
  "MYSQL|adb-mysql|mysql:8.4|33061|3306"
  "MARIADB|adb-mariadb|mariadb:11|33062|3306"
  "PG|adb-pg|postgres:16-alpine|54321|5432"
  "MSSQL|adb-mssql|mcr.microsoft.com/mssql/server:2022-latest|14331|1433"
  "ORACLE|adb-oracle|gvenzl/oracle-free:23-slim-faststart|15211|1521"
)

log()  { printf '\033[36m▸ %s\033[0m\n' "$*"; }
warn() { printf '\033[33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

gen_word() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-16}"; }

# SQL Server 的 SA 口令要同时有大写、小写、数字且够长，
# 微软那个镜像会直接拒绝弱口令并让容器退出；Oracle 的口令要能拼进
# 连接串和 SQL，所以两边都只用字母数字。
generate_env() {
  umask 077
  cat >"$ENV_FILE" <<EOF
# 由 scripts/dbmatrix.sh 生成，**不要提交**（.gitignore 已排除）。
# 删掉它 = 下次 up 换一套新口令。
ADB_TEST_MYSQL_HOST=127.0.0.1
ADB_TEST_MYSQL_PORT=33061
ADB_TEST_MYSQL_DB=$DB_NAME
ADB_TEST_MYSQL_DB2=$DB2_NAME
ADB_TEST_MYSQL_USER=root
ADB_TEST_MYSQL_PASSWORD=$(gen_word 20)
ADB_TEST_MARIADB_HOST=127.0.0.1
ADB_TEST_MARIADB_PORT=33062
ADB_TEST_MARIADB_DB=$DB_NAME
ADB_TEST_MARIADB_DB2=$DB2_NAME
ADB_TEST_MARIADB_USER=root
ADB_TEST_MARIADB_PASSWORD=$(gen_word 20)
ADB_TEST_PG_HOST=127.0.0.1
ADB_TEST_PG_PORT=54321
ADB_TEST_PG_DB=$DB_NAME
ADB_TEST_PG_DB2=$DB2_NAME
ADB_TEST_PG_USER=adb
ADB_TEST_PG_PASSWORD=$(gen_word 20)
ADB_TEST_MSSQL_HOST=127.0.0.1
ADB_TEST_MSSQL_PORT=14331
ADB_TEST_MSSQL_DB=$DB_NAME
ADB_TEST_MSSQL_DB2=$DB2_NAME
ADB_TEST_MSSQL_USER=sa
ADB_TEST_MSSQL_PASSWORD=Adb$(gen_word 12)7x
ADB_TEST_ORACLE_HOST=127.0.0.1
ADB_TEST_ORACLE_PORT=15211
# ⚠️ 这里**没有** ADB_TEST_ORACLE_DB2：Oracle 没有「一台服务器多个可切库」这一层
# （_DB 是服务名），第 9 条契约在它身上走「没有库层」那一支，不是走缺配置。
ADB_TEST_ORACLE_DB=$ORACLE_PDB
ADB_TEST_ORACLE_USER=adb
ADB_TEST_ORACLE_PASSWORD=Adb$(gen_word 12)7x
EOF
  chmod 600 "$ENV_FILE"
}

# 已有文件就沿用里面的口令：容器还在、口令却换了，
# 表现是「连接全失败」，比缺容器更难查。
load_env() {
  [ -f "$ENV_FILE" ] || die "还没有 $ENV_FILE —— 先 ./scripts/dbmatrix.sh up"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

# 在每个容器**自己**的 exec 里执行一条查询，用容器内的口令，
# 所以这里不读 .dbmatrix.env 的密码字段（除了 sqlcmd 需要）。
engine_sql() {
  local engine=$1 sql_text=$2
  case "$engine" in
    MYSQL)
      $DOCKER_BIN exec -i adb-mysql sh -c \
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B' <<<"$sql_text" ;;
    MARIADB)
      $DOCKER_BIN exec -i adb-mariadb sh -c \
        'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -N -B' <<<"$sql_text" ;;
    PG)
      $DOCKER_BIN exec -i adb-pg sh -c \
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA' <<<"$sql_text" ;;
    MSSQL)
      die "SQL Server 走 mssql_query：sqlcmd 的参数不能内联到这里的 sh -c 里" ;;
    ORACLE)
      $DOCKER_BIN exec -i adb-oracle bash -c \
        'exec sqlplus -s system/"$ORACLE_PASSWORD"' <<<"$sql_text" ;;
    *) die "未知引擎：$engine" ;;
  esac
}

# 口令从**容器自己的环境变量**里取，不写进命令行：
# 一是脚本里少一处明文拼接，二是 sqlcmd 少了 -P 会卡在交互式提示上，
# 表现是「就绪检查一直超时」，真实原因（没给口令）完全看不出来。
# -h -1 去掉表头，就绪判断才是干净的单值；-b 让失败返回非零。
mssql_query() {
  $DOCKER_BIN exec -i adb-mssql sh -c \
    'exec /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -h -1 -Q "$1" -P "$MSSQL_SA_PASSWORD"' \
    sh "$1"
}

wait_ready() {
  local engine=$1 container=$2 port=$3 tries=${4:-90} i ok=0
  for ((i = 1; i <= tries; i++)); do
    case "$engine" in
      MYSQL)   engine_sql MYSQL "SELECT 1" >/dev/null 2>&1 && ok=1 ;;
      MARIADB) engine_sql MARIADB "SELECT 1" >/dev/null 2>&1 && ok=1 ;;
      PG)      engine_sql PG "SELECT 1" >/dev/null 2>&1 && ok=1 ;;
      MSSQL)   mssql_query "SELECT 1" >/dev/null 2>&1 && ok=1 ;;
      ORACLE)  engine_sql ORACLE "SELECT 1 FROM DUAL;" 2>/dev/null | grep -q '1' && ok=1 ;;
    esac
    if [ "$ok" = 1 ]; then
      log "$container 就绪（:$port）"
      return 0
    fi
    sleep 2
  done
  die "$container 在 $((tries * 2)) 秒内没就绪 —— 看 $DOCKER_BIN logs $container"
}

up() {
  [ -f "$ENV_FILE" ] || { log "生成口令 → $ENV_FILE"; generate_env; }
  load_env
  # 旧版本生成的口令文件里没有 DB2 那四行：补上，不重新生成口令。
  # （容器还在、口令却换了 = 连接全失败，那比缺键难查得多，见 load_env 上面的注释。）
  local prefix key
  for prefix in MYSQL MARIADB PG MSSQL; do
    key="ADB_TEST_${prefix}_DB2"
    if ! grep -q "^${key}=" "$ENV_FILE"; then
      warn "口令文件缺 ${key} —— 补 ${key}=$DB2_NAME"
      printf '%s=%s\n' "$key" "$DB2_NAME" >> "$ENV_FILE"
      export "${key}=$DB2_NAME"
    fi
  done
  $DOCKER_BIN info >/dev/null 2>&1 || die "Docker 守护进程没在跑（先开 Docker Desktop）"

  local entry engine container image host_port cur_port state
  for entry in "${IMAGES[@]}"; do
    IFS='|' read -r engine container image host_port cur_port <<<"$entry"
    # 不用 docker inspect 判存在：对象不存在时它也会往 stdout 吐一个空行，
    # 那个空行会混进「missing」里，把后面的字符串比较全弄错。
    if $DOCKER_BIN ps -a --format '{{.Names}}' | grep -qx "$container"; then
      state=$($DOCKER_BIN inspect -f '{{.State.Status}}' "$container")
    else
      state=missing
    fi
    if [ "$state" = running ]; then
      warn "$container 已在运行 —— 跳过创建（要重建先 down）"
      continue
    fi
    [ "$state" = missing ] || $DOCKER_BIN rm "$container" >/dev/null
    log "起 $container（$image → :$host_port）"
    case "$engine" in
      MYSQL)
        $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
          -e MYSQL_ROOT_PASSWORD="$ADB_TEST_MYSQL_PASSWORD" \
          -e MYSQL_DATABASE="$DB_NAME" \
          -e MYSQL_USER=adb -e MYSQL_PASSWORD="$ADB_TEST_MYSQL_PASSWORD" \
          "$image" >/dev/null ;;
      MARIADB)
        $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
          -e MARIADB_ROOT_PASSWORD="$ADB_TEST_MARIADB_PASSWORD" \
          -e MARIADB_DATABASE="$DB_NAME" \
          -e MARIADB_USER=adb -e MARIADB_PASSWORD="$ADB_TEST_MARIADB_PASSWORD" \
          "$image" >/dev/null ;;
      PG)
        $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
          -e POSTGRES_DB="$DB_NAME" \
          -e POSTGRES_USER=adb -e POSTGRES_PASSWORD="$ADB_TEST_PG_PASSWORD" \
          "$image" >/dev/null ;;
      MSSQL)
        $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
          -e ACCEPT_EULA=Y -e MSSQL_PID=developer \
          -e MSSQL_SA_PASSWORD="$ADB_TEST_MSSQL_PASSWORD" \
          "$image" >/dev/null ;;
      ORACLE)
        # NLS_LANG 必须是 AL32UTF8：契约里有中文表名和中文注释，
        # 字符集不对的话中文进去变问号，报错却出现在断言那一侧。
        $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
          -e ORACLE_PASSWORD="$ADB_TEST_ORACLE_PASSWORD" \
          -e APP_USER=adb -e APP_USER_PASSWORD="$ADB_TEST_ORACLE_PASSWORD" \
          -e NLS_LANG=.AL32UTF8 \
          "$image" >/dev/null ;;
    esac
    wait_ready "$engine" "$container" "$host_port"
  done

  # 这个镜像没有「自动建库」的开关，库要手动补。
  # ⚠️ 判断别看整数列：sqlcmd 把 int 右对齐，前面挂一串空格，
  # 用 `^1$` 去匹配永远不中，于是每次 up 都跑去 CREATE DATABASE 再撞重名报错。
  # 改成字符串列（左对齐、值本身可分辨），第二次 up 就是纯 no-op。
  if mssql_query "SELECT CASE WHEN DB_ID('$DB_NAME') IS NULL THEN 'MISSING' ELSE 'EXISTS' END" 2>/dev/null | grep -q MISSING; then
    log "建 SQL Server 库 $DB_NAME"
    mssql_query "CREATE DATABASE $DB_NAME" >/dev/null
  fi

  # ⭐ R52 第 9 条契约用的第二把库：「切库落点」要对的是**同一台服务器上两个同位的对端**，
  # 只有一把库的话，契约就只能断「连上了」，断不了「落在哪儿」。
  # 全部先问后建：第二次 up 必须是 no-op，不然日志里每次都是一串「库已存在」的报错，
  # 那和 D-30 那条假警报是同一种毛病。
  log "建第二把库 $DB2_NAME（$DB_NAME 之外；已存在 = no-op）"
  engine_sql MYSQL "CREATE DATABASE IF NOT EXISTS \`$DB2_NAME\`" >/dev/null
  engine_sql MARIADB "CREATE DATABASE IF NOT EXISTS \`$DB2_NAME\`" >/dev/null
  # PG 的 CREATE DATABASE 没有 IF NOT EXISTS，也没有事务块可包，只能先问 pg_database。
  # 这次整数列可以：psql -tA 不右对齐，量的是 psql 的输出而非 sqlcmd 的。
  if [ "$(engine_sql PG "SELECT 1 FROM pg_database WHERE datname='$DB2_NAME'" 2>/dev/null | tr -d '[:space:]')" != "1" ]; then
    engine_sql PG "CREATE DATABASE $DB2_NAME OWNER $ADB_TEST_PG_USER" >/dev/null
  fi
  if mssql_query "SELECT CASE WHEN DB_ID('$DB2_NAME') IS NULL THEN 'MISSING' ELSE 'EXISTS' END" 2>/dev/null | grep -q MISSING; then
    mssql_query "CREATE DATABASE $DB2_NAME" >/dev/null
  fi

  seed_frames

  echo
  status
  log "口令在 $ENV_FILE（未入库）。跑矩阵：./scripts/dbmatrix.sh run"
}

# ⭐ R47-b：验收帧要的最小内容 —— users / orders 两张表 + 一个 v_active_users 视图。
#
#   为什么矩阵里要有「内容」：截图通道现在能把外壳绑到真服务器上（ADB_MATRIX），
#   而「设计视图」这一格的前提是**读回一条真定义** —— 空库里没有视图，弹窗根本不开，
#   于是 D-29 翻转后的形态（MySQL 上一句 CREATE OR REPLACE、无灰字提示）
#   还是只能靠单测门证明。
#   为什么要 orders：`p6-new-view-mysql` 那帧的预览是 `JOIN users` 的 v_paid_orders，
#   只有 users 的话那张帧里的 SQL 在 shop 上**根本建不起来** —— 帧可以只拍预览，
#   但预览里写着的表必须真在库里，否则「SQL to be executed」是一张跑不通的画。
#   五家都种（R47-c）：视图形态帧要拍的就是「同一条 SQL 在五种方言里长成什么样」，
#   只种 MySQL 等于只有 MySQL 那一帧能拍 —— 种子随方言变（BIT / BOOLEAN / NUMBER(1)、
#   INSERT IGNORE / ON CONFLICT / MERGE），这正是这些帧要的东西。
#   全部幂等：第二次 up 必须是 no-op（D-30 那条假警报的教训 ——
#   「每次跑都有一串已存在报错」会把下一次真故障淹掉）。
seed_frames() {
  log "种验收帧内容（$DB_NAME.users + orders + v_active_users；已存在 = no-op）"
  local mysql_seed="
USE \`$DB_NAME\`;
CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  email VARCHAR(200) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1
);
INSERT IGNORE INTO users (id, name, email, active) VALUES
  (1, 'Ada', 'ada@example.com', 1),
  (2, 'Lin', 'lin@example.com', 0);
CREATE TABLE IF NOT EXISTS orders (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(32) NOT NULL
);
INSERT IGNORE INTO orders (id, user_id, amount, status) VALUES
  (10, 1, 20.50, 'paid'),
  (11, 1, 7.00, 'draft');
CREATE OR REPLACE VIEW v_active_users AS
  SELECT id, name, email FROM users WHERE active = 1;"
  engine_sql MYSQL "$mysql_seed" >/dev/null
  engine_sql MARIADB "$mysql_seed" >/dev/null
  engine_sql PG "
CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO users (id, name, email, active) VALUES
  (1, 'Ada', 'ada@example.com', TRUE),
  (2, 'Lin', 'lin@example.com', FALSE)
  ON CONFLICT (id) DO NOTHING;
CREATE TABLE IF NOT EXISTS orders (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  status TEXT NOT NULL
);
INSERT INTO orders (id, user_id, amount, status) VALUES
  (10, 1, 20.50, 'paid'),
  (11, 1, 7.00, 'draft')
  ON CONFLICT (id) DO NOTHING;
CREATE OR REPLACE VIEW v_active_users AS
  SELECT id, name, email FROM users WHERE active;" >/dev/null
  # SQL Server：CREATE VIEW（含 CREATE OR ALTER）必须是批次里的**第一条**语句，
  # 所以视图单独一段，用 GO 隔开 —— 和前面拼成一段会在解析期整批失败，
  # 表现为「表也没建成、只吐一句 must be the first statement」。
  mssql_query "USE $DB_NAME;
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'users')
  CREATE TABLE dbo.users (id BIGINT PRIMARY KEY, name NVARCHAR(200) NOT NULL, email NVARCHAR(200) NOT NULL, active BIT NOT NULL DEFAULT 1);
IF NOT EXISTS (SELECT 1 FROM dbo.users WHERE id = 1)
  INSERT INTO dbo.users (id, name, email, active) VALUES (1, N'Ada', N'ada@example.com', 1);
IF NOT EXISTS (SELECT 1 FROM dbo.users WHERE id = 2)
  INSERT INTO dbo.users (id, name, email, active) VALUES (2, N'Lin', N'lin@example.com', 0);
IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'orders')
  CREATE TABLE dbo.orders (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, amount DECIMAL(12,2) NOT NULL, status NVARCHAR(32) NOT NULL);
IF NOT EXISTS (SELECT 1 FROM dbo.orders WHERE id = 10)
  INSERT INTO dbo.orders (id, user_id, amount, status) VALUES (10, 1, 20.50, N'paid');
IF NOT EXISTS (SELECT 1 FROM dbo.orders WHERE id = 11)
  INSERT INTO dbo.orders (id, user_id, amount, status) VALUES (11, 1, 7.00, N'draft');
GO
CREATE OR ALTER VIEW dbo.v_active_users AS
  SELECT id, name, email FROM dbo.users WHERE active = 1;" >/dev/null
  # Oracle：容器里 sqlplus 连的是 CDB\$ROOT，ADB 用户建在 PDB 里（ORA-01917），
  # 所以先 ALTER SESSION SET CONTAINER。没有 IF NOT EXISTS，幂等靠 PL/SQL 吞
  # -955（名字已被占用）/ -1430（列已存在）；插入用 MERGE 当 upsert。
  engine_sql ORACLE "
ALTER SESSION SET CONTAINER = $ORACLE_PDB;
DECLARE
  PROCEDURE run(p IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p;
  EXCEPTION
    WHEN OTHERS THEN IF SQLCODE NOT IN (-955, -1430) THEN RAISE; END IF;
  END;
BEGIN
  run('CREATE TABLE $ADB_TEST_ORACLE_USER.users (id NUMBER PRIMARY KEY, name VARCHAR2(200) NOT NULL, email VARCHAR2(200) NOT NULL, active NUMBER(1) DEFAULT 1 NOT NULL)');
  run('CREATE TABLE $ADB_TEST_ORACLE_USER.orders (id NUMBER PRIMARY KEY, user_id NUMBER NOT NULL, amount NUMBER(12,2) NOT NULL, status VARCHAR2(32) NOT NULL)');
END;
/
MERGE INTO $ADB_TEST_ORACLE_USER.users t USING (SELECT 1 AS id, 'Ada' AS name, 'ada@example.com' AS email, 1 AS active FROM dual) s
  ON (t.id = s.id) WHEN NOT MATCHED THEN INSERT (id, name, email, active) VALUES (s.id, s.name, s.email, s.active);
MERGE INTO $ADB_TEST_ORACLE_USER.users t USING (SELECT 2 AS id, 'Lin' AS name, 'lin@example.com' AS email, 0 AS active FROM dual) s
  ON (t.id = s.id) WHEN NOT MATCHED THEN INSERT (id, name, email, active) VALUES (s.id, s.name, s.email, s.active);
MERGE INTO $ADB_TEST_ORACLE_USER.orders t USING (SELECT 10 AS id, 1 AS user_id, 20.50 AS amount, 'paid' AS status FROM dual) s
  ON (t.id = s.id) WHEN NOT MATCHED THEN INSERT (id, user_id, amount, status) VALUES (s.id, s.user_id, s.amount, s.status);
MERGE INTO $ADB_TEST_ORACLE_USER.orders t USING (SELECT 11 AS id, 1 AS user_id, 7.00 AS amount, 'draft' AS status FROM dual) s
  ON (t.id = s.id) WHEN NOT MATCHED THEN INSERT (id, user_id, amount, status) VALUES (s.id, s.user_id, s.amount, s.status);
CREATE OR REPLACE VIEW $ADB_TEST_ORACLE_USER.v_active_users AS
  SELECT id, name, email FROM $ADB_TEST_ORACLE_USER.users WHERE active = 1;
COMMIT;" >/dev/null
}

status() {
  local entry engine container image host_port cur_port state
  printf '%-9s %-13s %-7s %s\n' ENGINE CONTAINER PORT STATE
  for entry in "${IMAGES[@]}"; do
    IFS='|' read -r engine container image host_port cur_port <<<"$entry"
    # 同 up：inspect 的失败输出要用 tr 收干，不然状态列里混进空行。
    state=$($DOCKER_BIN inspect -f '{{.State.Status}}' "$container" 2>/dev/null | tr -d '[:space:]')
    [ -n "$state" ] || state=缺
    printf '%-9s %-13s %-7s %s\n' "$engine" "$container" "$host_port" "$state"
  done
}

print_env() {
  load_env
  grep '^ADB_TEST' "$ENV_FILE" | sed 's/^/export /'
}

run_matrix() {
  load_env
  export JAVA_HOME="${JAVA_HOME:-/Users/jason/.jdks/jdk-25.0.4.1+1/Contents/Home}"
  log "跑真库契约矩阵（5 引擎 × 9 契约 + 1 条口令门）"
  ./mvnw -B -pl workbench/adb-jdbc test -Dtest=ExternalRealDatabaseTest
}

# ── R47：TLS 对端层 ─────────────────────────────────────────────────────────
# 这一层存在的理由只有一条：主矩阵那五个容器**只起非加密服务**，
# 所以「档位写进驱动参数之后，证书链到底有没有被校验」这个问题在它们身上问不出来 ——
# 参数写了不等于参数生效，这正是 ROADMAP 里唯一没被真库覆盖的那一格。
TLS_DIR="$PROJECT_ROOT/scripts/.dbmatrix-tls"
TLS_SERVER_DIR="$TLS_DIR/server"
# 服务器证书只认这一个主机名（SAN=DNS:localhost）；负例用 127.0.0.1 连同一台服务器。
TLS_HOST_IN_CERT=localhost
# 「标签|容器名|镜像|宿主端口|容器端口|复用哪个口令变量」
TLS_ENGINES=(
  "MYSQL_TLS|adb-mysql-tls|mysql:8.4|33071|3306|ADB_TEST_MYSQL_PASSWORD"
  "MARIADB_TLS|adb-mariadb-tls|mariadb:11|33072|3306|ADB_TEST_MARIADB_PASSWORD"
  "PG_TLS|adb-pg-tls|postgres:16-alpine|54331|5432|ADB_TEST_PG_PASSWORD"
)

# 自签一套三件：真 CA、冒名 CA、只认 localhost 的服务器证书。
# 已存在就不重签 —— 重签会让客户端手里那份 ca.pem 和服务器呈现的链对不上，
# 表现是「昨天还能连的 TLS 用例今天全红」，而红的原因在文件里不在代码里。
gen_pki() {
  if [ -f "$TLS_SERVER_DIR/server.pem" ] && [ -f "$TLS_DIR/ca.pem" ] \
     && [ -f "$TLS_DIR/rogue-ca.pem" ]; then
    log "PKI 已存在 → $TLS_DIR（要重签：删掉这个目录）"
    return 0
  fi
  command -v openssl >/dev/null 2>&1 \
    || die "缺 openssl：TLS 层要现场自签一套 CA（brew install openssl 或用 /opt/homebrew/bin）"
  log "生成 PKI → $TLS_DIR（含私钥，已 gitignore）"
  mkdir -p "$TLS_SERVER_DIR"
  umask 077
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
    -keyout "$TLS_DIR/ca-key.pem" -out "$TLS_DIR/ca.pem" \
    -subj "/CN=AIDB Test CA" 2>/dev/null
  # 冒名 CA：只有公钥那份用得到（客户端拿它当唯一信任锚），所以它的私钥也在这目录里，
  # 用途是「签一张客户端会误信的证书」，本层目前只用它的 PEM。
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
    -keyout "$TLS_DIR/rogue-ca-key.pem" -out "$TLS_DIR/rogue-ca.pem" \
    -subj "/CN=AIDB Rogue CA" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes \
    -keyout "$TLS_SERVER_DIR/server-key.pem" -out "$TLS_SERVER_DIR/server.csr" \
    -subj "/CN=$TLS_HOST_IN_CERT" 2>/dev/null
  # ⭐ 只有 CN、没有 SAN 的证书，JSSE 会直接判不匹配（现代 JDK 不再回落看 CN），
  #    所以正向用例必挂 —— 这一行是「主机名校验」能不能验出正例的前提。
  printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$TLS_HOST_IN_CERT" \
    >"$TLS_SERVER_DIR/server-ext.cnf"
  openssl x509 -req -days 825 -in "$TLS_SERVER_DIR/server.csr" \
    -CA "$TLS_DIR/ca.pem" -CAkey "$TLS_DIR/ca-key.pem" -CAcreateserial \
    -out "$TLS_SERVER_DIR/server.pem" -extfile "$TLS_SERVER_DIR/server-ext.cnf" 2>/dev/null
  cp "$TLS_DIR/ca.pem" "$TLS_SERVER_DIR/ca.pem"
  chmod 600 "$TLS_DIR"/*-key.pem "$TLS_SERVER_DIR"/server-key.pem
  # 服务器证书里不该有私钥，签完的 csr 也没用，但留着无害；目录整体保持 700。
  chmod 700 "$TLS_DIR" "$TLS_SERVER_DIR"
}

# TLS 容器把证书从只读挂载**复制**进容器本地再改属主：
# 直接挂载的那份在 macOS 上归 root、模式按宿主来，mysqld/postgres 会因为
# 「私钥可被别人读到」直接拒绝启动，而报错发生在容器日志里，测试侧只看到连不上。
tls_run_container() {
  local label=$1 container=$2 image=$3 host_port=$4 cur_port=$5 password=$6
  case "$label" in
    MYSQL_TLS)
      $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
        -v "${TLS_SERVER_DIR}:/tls-src:ro" \
        -e MYSQL_ROOT_PASSWORD="$password" -e MYSQL_DATABASE="$DB_NAME" \
        "$image" sh -c '
          mkdir -p /var/lib/mysql-tls
          cp /tls-src/ca.pem /tls-src/server.pem /tls-src/server-key.pem /var/lib/mysql-tls/
          chown -R mysql:mysql /var/lib/mysql-tls
          chmod 600 /var/lib/mysql-tls/server-key.pem
          exec docker-entrypoint.sh mysqld \
            --ssl-ca=/var/lib/mysql-tls/ca.pem \
            --ssl-cert=/var/lib/mysql-tls/server.pem \
            --ssl-key=/var/lib/mysql-tls/server-key.pem' ;;
    MARIADB_TLS)
      $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
        -v "${TLS_SERVER_DIR}:/tls-src:ro" \
        -e MARIADB_ROOT_PASSWORD="$password" -e MARIADB_DATABASE="$DB_NAME" \
        "$image" sh -c '
          mkdir -p /var/lib/mysql-tls
          cp /tls-src/ca.pem /tls-src/server.pem /tls-src/server-key.pem /var/lib/mysql-tls/
          chown -R mysql:mysql /var/lib/mysql-tls
          chmod 600 /var/lib/mysql-tls/server-key.pem
          exec docker-entrypoint.sh mariadbd \
            --ssl-ca=/var/lib/mysql-tls/ca.pem \
            --ssl-cert=/var/lib/mysql-tls/server.pem \
            --ssl-key=/var/lib/mysql-tls/server-key.pem' ;;
    PG_TLS)
      # postgres:16-alpine 里的 OS 用户是 uid 70（不是名字），chown 用数字最稳。
      $DOCKER_BIN run -d --name "$container" -p "${host_port}:${cur_port}" \
        -v "${TLS_SERVER_DIR}:/tls-src:ro" \
        -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER=adb -e POSTGRES_PASSWORD="$password" \
        "$image" sh -c '
          mkdir -p /var/lib/postgresql/tls
          cp /tls-src/server.pem /var/lib/postgresql/tls/server.crt
          cp /tls-src/server-key.pem /var/lib/postgresql/tls/server.key
          cp /tls-src/ca.pem /var/lib/postgresql/tls/ca.pem
          chown -R 70:70 /var/lib/postgresql/tls
          chmod 700 /var/lib/postgresql/tls
          chmod 600 /var/lib/postgresql/tls/server.key
          exec docker-entrypoint.sh postgres \
            -c ssl=on \
            -c ssl_cert_file=/var/lib/postgresql/tls/server.crt \
            -c ssl_key_file=/var/lib/postgresql/tls/server.key \
            -c ssl_ca_file=/var/lib/postgresql/tls/ca.pem' ;;
    *) die "未知 TLS 引擎：$label" ;;
  esac
}

# 在 TLS 容器里跑一条查询（口令用容器自己的环境变量，和 engine_sql 同一个理由）。
tls_sql() {
  local engine=$1 sql_text=$2
  case "$engine" in
    MYSQL)
      $DOCKER_BIN exec -i adb-mysql-tls sh -c \
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B' <<<"$sql_text" ;;
    MARIADB)
      $DOCKER_BIN exec -i adb-mariadb-tls sh -c \
        'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -N -B' <<<"$sql_text" ;;
    PG)
      $DOCKER_BIN exec -i adb-pg-tls sh -c \
        'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA' <<<"$sql_text" ;;
    *) die "TLS 层没有这家：$engine（SQL Server / Oracle 的 TLS 不在本层）" ;;
  esac
}

tls_wait_ready() {
  local engine=$1 container=$2 port=$3 tries=${4:-90} i ok=0
  for ((i = 1; i <= tries; i++)); do
    tls_sql "$engine" "SELECT 1" >/dev/null 2>&1 && ok=1
    if [ "$ok" = 1 ]; then
      log "$container 就绪（:$port，TLS）"
      return 0
    fi
    sleep 2
  done
  die "$container 在 $((tries * 2)) 秒内没就绪 —— 看 $DOCKER_BIN logs $container"
}

# TLS 层的键缺了就补，和 DB2 那四行同一个手法：口令沿用同引擎那份随机值
# （本机测试容器，多一份口令只是多一处会漏的地方），证书路径是本地文件名不是秘密。
append_tls_env() {
  local changed=0 key
  for key in ADB_TEST_TLS_CA ADB_TEST_TLS_ROGUE_CA ADB_TEST_TLS_HOST_IN_CERT \
             ADB_TEST_MYSQL_TLS_HOST ADB_TEST_MYSQL_TLS_PORT ADB_TEST_MYSQL_TLS_DB \
             ADB_TEST_MYSQL_TLS_USER ADB_TEST_MYSQL_TLS_PASSWORD \
             ADB_TEST_MARIADB_TLS_HOST ADB_TEST_MARIADB_TLS_PORT ADB_TEST_MARIADB_TLS_DB \
             ADB_TEST_MARIADB_TLS_USER ADB_TEST_MARIADB_TLS_PASSWORD \
             ADB_TEST_PG_TLS_HOST ADB_TEST_PG_TLS_PORT ADB_TEST_PG_TLS_DB \
             ADB_TEST_PG_TLS_USER ADB_TEST_PG_TLS_PASSWORD; do
    grep -q "^${key}=" "$ENV_FILE" && continue
    case "$key" in
      ADB_TEST_TLS_CA)            printf '%s=%s\n' "$key" "$TLS_DIR/ca.pem" >>"$ENV_FILE" ;;
      ADB_TEST_TLS_ROGUE_CA)      printf '%s=%s\n' "$key" "$TLS_DIR/rogue-ca.pem" >>"$ENV_FILE" ;;
      ADB_TEST_TLS_HOST_IN_CERT)  printf '%s=%s\n' "$key" "$TLS_HOST_IN_CERT" >>"$ENV_FILE" ;;
      ADB_TEST_MYSQL_TLS_HOST|ADB_TEST_MARIADB_TLS_HOST|ADB_TEST_PG_TLS_HOST)
        printf '%s=%s\n' "$key" "127.0.0.1" >>"$ENV_FILE" ;;
      ADB_TEST_MYSQL_TLS_PORT)    printf '%s=%s\n' "$key" 33071 >>"$ENV_FILE" ;;
      ADB_TEST_MARIADB_TLS_PORT)  printf '%s=%s\n' "$key" 33072 >>"$ENV_FILE" ;;
      ADB_TEST_PG_TLS_PORT)       printf '%s=%s\n' "$key" 54331 >>"$ENV_FILE" ;;
      ADB_TEST_MYSQL_TLS_DB|ADB_TEST_MARIADB_TLS_DB|ADB_TEST_PG_TLS_DB)
        printf '%s=%s\n' "$key" "$DB_NAME" >>"$ENV_FILE" ;;
      ADB_TEST_MYSQL_TLS_USER|ADB_TEST_MARIADB_TLS_USER)
        printf '%s=%s\n' "$key" root >>"$ENV_FILE" ;;
      ADB_TEST_PG_TLS_USER)       printf '%s=%s\n' "$key" adb >>"$ENV_FILE" ;;
      ADB_TEST_MYSQL_TLS_PASSWORD)
        printf '%s=%s\n' "$key" "$ADB_TEST_MYSQL_PASSWORD" >>"$ENV_FILE" ;;
      ADB_TEST_MARIADB_TLS_PASSWORD)
        printf '%s=%s\n' "$key" "$ADB_TEST_MARIADB_PASSWORD" >>"$ENV_FILE" ;;
      ADB_TEST_PG_TLS_PASSWORD)
        printf '%s=%s\n' "$key" "$ADB_TEST_PG_PASSWORD" >>"$ENV_FILE" ;;
      *) die "没给 TLS 键的取值写法：$key" ;;
    esac
    changed=1
  done
  [ "$changed" = 1 ] && warn "口令文件补了 TLS 层缺的键（已有键保持原值，口令不重生成）"
  load_env
}

tls_up() {
  [ -f "$ENV_FILE" ] || die "先 ./scripts/dbmatrix.sh up（TLS 层复用同一份口令文件）"
  load_env
  $DOCKER_BIN info >/dev/null 2>&1 || die "Docker 守护进程没在跑（先开 Docker Desktop）"
  gen_pki
  append_tls_env

  local entry label container image host_port cur_port password_var
  for entry in "${TLS_ENGINES[@]}"; do
    IFS='|' read -r label container image host_port cur_port password_var <<<"$entry"
    local state
    if $DOCKER_BIN ps -a --format '{{.Names}}' | grep -qx "$container"; then
      state=$($DOCKER_BIN inspect -f '{{.State.Status}}' "$container")
    else
      state=missing
    fi
    if [ "$state" = running ]; then
      warn "$container 已在运行 —— 跳过创建（要重建先 tls-down）"
      continue
    fi
    [ "$state" = missing ] || $DOCKER_BIN rm "$container" >/dev/null
    log "起 $container（$image → :$host_port，TLS）"
    # shellcheck disable=SC1087 —— 这里要的是「变量名存在哪张表里」这层间接
    tls_run_container "$label" "$container" "$image" "$host_port" "$cur_port" \
      "${!password_var}"
    case "$label" in
      MYSQL_TLS)   tls_wait_ready MYSQL "$container" "$host_port" 120 ;;
      MARIADB_TLS) tls_wait_ready MARIADB "$container" "$host_port" 120 ;;
      PG_TLS)      tls_wait_ready PG "$container" "$host_port" 90 ;;
    esac
  done

  mysql_tls_warm_cache

  # 证书到底配没配上，先在这里问一次**服务器侧的配置**。
  # ⚠️ 这里不能问「当前会话加没加密」（`SHOW STATUS LIKE 'Ssl_cipher'` /
  # `pg_stat_ssl.ssl`）：那条查询走的是容器内的 CLI 连接 —— mysql 客户端拿的是 socket、
  # psql 同理，量出来永远是「没加密」，跟服务器配了什么无关。第一次写的时候就踩了这个坑。
  local mysql_cert pg_ssl mariadb_cert
  mysql_cert=$(tls_sql MYSQL "SHOW VARIABLES LIKE 'ssl_cert'" 2>/dev/null | tr -d '\n')
  mariadb_cert=$(tls_sql MARIADB "SHOW VARIABLES LIKE 'ssl_cert'" 2>/dev/null | tr -d '\n')
  pg_ssl=$(tls_sql PG "SHOW ssl" 2>/dev/null | tr -d '[:space:]')
  log "服务器侧自检：MySQL ssl_cert=${mysql_cert:-（空）}"
  log "服务器侧自检：MariaDB ssl_cert=${mariadb_cert:-（空）}"
  log "服务器侧自检：PostgreSQL ssl=${pg_ssl:-（读不到）}"
  tls_status
  log "TLS 层的键在 $ENV_FILE（未入库）。跑契约：./scripts/dbmatrix.sh tls-run"
}

tls_status() {
  local entry label container image host_port cur_port password_var state
  printf '%-12s %-17s %-7s %s\n' ENGINE CONTAINER PORT STATE
  for entry in "${TLS_ENGINES[@]}"; do
    IFS='|' read -r label container image host_port cur_port password_var <<<"$entry"
    state=$($DOCKER_BIN inspect -f '{{.State.Status}}' "$container" 2>/dev/null | tr -d '[:space:]')
    [ -n "$state" ] || state=缺
    printf '%-12s %-17s %-7s %s\n' "$label" "$container" "$host_port" "$state"
  done
}

# 把 MySQL TLS 对端的「按账号验证缓存」焐热一次：从容器内用 TCP+TLS 登一遍 root@%。
#
# 为什么要这一步：MySQL 8 默认认证插件是 caching_sha2_password，服务器侧有一份内存缓存，
# 缓存空 + **明文**首登 = 驱动得向服务器索要 RSA 公钥，而 Connector/J 默认不给
# （`allowPublicKeyRetrieval=false`）→ 报「Public Key Retrieval is not allowed」。
# 容器一重启（连 Docker Desktop 自己重启）缓存就空，于是契约①
# 「界面写『不加密』→ 会话真是明文」根本连不上，量不到它要量的东西。
# 焐热用一次 TLS 登录：认证成功即写入缓存，之后明文走「快速通道」，不再要公钥。
#
# ⚠️ 焐的是测试环境，不是把缺陷盖住：真实用户第一次连 MySQL 8 又选了「不加密」，
# 撞的就是这句话。那条账记在 GAP-TODOLIST 的 D-42，别在这里绕过去就当它不存在。
# MariaDB 不需要（默认 mysql_native_password）、PG 也不需要（scram 没有这层缓存）。
mysql_tls_warm_cache() {
  $DOCKER_BIN exec adb-mysql-tls sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -h127.0.0.1 -P3306 --ssl-mode=REQUIRED -e "SELECT 1"' \
    >/dev/null 2>&1 \
    || die "焐 MySQL TLS 对端的验证缓存失败 —— 契约① 会报「Public Key Retrieval is not allowed」（见 D-42）"
  log "MySQL TLS 对端的验证缓存已焐热（明文首登不再索要 RSA 公钥）"
}

tls_run_matrix() {
  load_env
  export JAVA_HOME="${JAVA_HOME:-/Users/jason/.jdks/jdk-25.0.4.1+1/Contents/Home}"
  mysql_tls_warm_cache
  log "跑 TLS 契约矩阵（3 家加密对端 × TLS 档位）"
  ./mvnw -B -pl workbench/adb-jdbc test -Dtest=ExternalTlsDatabaseTest
}

tls_down() {
  local entry label container image host_port cur_port password_var
  for entry in "${TLS_ENGINES[@]}"; do
    IFS='|' read -r label container image host_port cur_port password_var <<<"$entry"
    if [ -n "$($DOCKER_BIN ps -aq -f name="^${container}$")" ]; then
      log "删 $container"
      $DOCKER_BIN rm -f -v "$container" >/dev/null
    fi
  done
}

down() {
  local entry engine container image host_port cur_port
  for entry in "${IMAGES[@]}"; do
    IFS='|' read -r engine container image host_port cur_port <<<"$entry"
    if [ -n "$($DOCKER_BIN ps -aq -f name="^${container}$")" ]; then
      log "删 $container"
      $DOCKER_BIN rm -f -v "$container" >/dev/null
    fi
  done
  # TLS 层的容器一起删：留着它们，下次 up 主矩阵时「端口被占」会比「容器多一个」难查。
  tls_down
  if [ "${1:-}" = --purge-env ]; then
    rm -f "$ENV_FILE"
    log "口令文件也删了（下次 up 生成新的）"
    if [ -d "$TLS_DIR" ]; then
      rm -rf "$TLS_DIR"
      log "自签的 PKI 目录也删了（里面有私钥；下次 tls-up 重签一套）"
    fi
  fi
}

case "${1:-up}" in
  up)         up ;;
  down)       down "${2:-}" ;;
  status)     status ;;
  env)        print_env ;;
  run)        run_matrix ;;
  tls-up)     tls_up ;;
  tls-down)   tls_down ;;
  tls-status) tls_status ;;
  tls-run)    tls_run_matrix ;;
  seed)       load_env; seed_frames ;;
  *)          sed -n '2,27p' "$0" | sed 's/^#//; s/^ //' ;;
esac
