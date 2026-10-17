#!/usr/bin/env bash
#
# 建 / 重建测试库 mindforge_test。
#
# 【为什么需要】
#   Java 侧的单元测试和 API 测试打的是【独立的测试库】，不是开发库 ——
#   因为每个用例开始前都会 DELETE 业务表。拿 mindforge 当靶子等于每次跑测试
#   都把开发数据清空一遍。
#
# 【为什么不能直接把 mindforge-schema.sql 灌进去】
#   那个文件里有 `CREATE DATABASE IF NOT EXISTS mindforge` 和 `USE mindforge`，
#   就算你在命令行指定了 mindforge_test，它也会把你带到开发库去 ——
#   表现是第一条 CREATE TABLE 撞「Table 'user' already exists」然后中断。
#   所以下面先把这几行过滤掉再灌。
#
# 【用法】
#   cd 到仓库根目录，然后：
#     MYSQL_ROOT_PASSWORD=xxx bash src/test/scripts/init-test-db.sh
#
#   可选覆盖：
#     TEST_DB=my_test_db  MYSQL_HOST=127.0.0.1  MYSQL_PORT=3306  MYSQL_ROOT_USER=root
#
# 【它会做什么】
#   ① DROP + CREATE 测试库（⚠️ 会清掉测试库里已有的数据，但不会碰开发库）
#   ② 过滤掉 USE / CREATE DATABASE 后导入 mindforge-schema.sql
#   ③ 把开发库那套权限授给应用账号（mind），否则 Java 连不上测试库
#
# 【跑完怎么验证】
#   mysql -uroot -p -e "SELECT COUNT(*) FROM information_schema.TABLES
#                       WHERE TABLE_SCHEMA='mindforge_test'"   # 应该是 29
#
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
TEST_DB="${TEST_DB:-mindforge_test}"
SCHEMA_FILE="${SCHEMA_FILE:-mindforge-schema.sql}"
APP_CONFIG="${APP_CONFIG:-src/main/resources/application-dev.yml}"

if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    echo "请通过 MYSQL_ROOT_PASSWORD 传入 MySQL root 密码" >&2
    echo "   例：MYSQL_ROOT_PASSWORD=xxx bash $0" >&2
    exit 1
fi

if [ ! -f "$SCHEMA_FILE" ]; then
    echo "找不到 $SCHEMA_FILE —— 请在仓库根目录执行本脚本" >&2
    echo "   ⚠️ 这个文件目前被 .gitignore 忽略，换机器要手工拷过来" >&2
    exit 1
fi

MYSQL=(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_ROOT_USER" -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4)

# 应用连库用的账号 —— 从 application-dev.yml 里读，免得在这里再写一份凭据
APP_USER="$(grep -E '^\s+username:' "$APP_CONFIG" | tail -1 | sed 's/.*username:[[:space:]]*//' | tr -d '\r ')"
APP_PASSWORD="$(grep -E '^\s+password:' "$APP_CONFIG" | tail -1 | sed 's/.*password:[[:space:]]*//' | tr -d '\r ')"

echo "==> 重建测试库 $TEST_DB"
"${MYSQL[@]}" -e "
DROP DATABASE IF EXISTS \`$TEST_DB\`;
CREATE DATABASE \`$TEST_DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

echo "==> 过滤掉 USE / CREATE DATABASE 后导入 schema"
# 跳过 CREATE DATABASE 到 USE 之间的那几行（含 USE 本身）
FILTERED="$(mktemp)"
awk '/^CREATE DATABASE/{skip=1} /^USE `mindforge`;/{skip=0;next} skip==0{print}' \
    "$SCHEMA_FILE" > "$FILTERED"

if grep -qE '^USE |^CREATE DATABASE' "$FILTERED"; then
    echo "过滤没干净，文件里还有 USE / CREATE DATABASE —— 中止，免得打进开发库" >&2
    rm -f "$FILTERED"
    exit 1
fi

"${MYSQL[@]}" "$TEST_DB" < "$FILTERED"
rm -f "$FILTERED"

if [ -n "$APP_USER" ]; then
    echo "==> 给应用账号 $APP_USER 授测试库权限"
    "${MYSQL[@]}" -e "
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$TEST_DB\`.* TO '$APP_USER'@'localhost';
FLUSH PRIVILEGES;"
else
    echo "⚠️ 没能从 $APP_CONFIG 读出应用账号，跳过授权 —— 记得手工授权" >&2
fi

TABLES="$("${MYSQL[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$TEST_DB';" | tr -d '\r')"
echo "==> 完成：$TEST_DB 共 $TABLES 张表（期望 29）"
