#!/usr/bin/env bash
# 迁移回滚演练（ADR-0011 的「人工三步」的可复现版本）。
#
# 为什么要演练：回滚脚本长期只是「写在仓库里」——2026-09-28 第一次真跑就发现
# U4 在真实数据上直接失败（V4 之后防疫允许同一天多条，而回滚要加回按天唯一的旧键）。
# 平时不跑，等真出事那天才发现回滚跑不动，是最糟的时机。
#
# 它做什么：在**独立的 scratch 库**上（绝不碰开发库）跑一遍
#   应用全部迁移 → 造一份有代表性的数据 → 从最新往下逐个执行 undo（含删 flyway 历史行），
# 最后确认 schema 回到空。
#
# 用法（需要 Docker 里的 MySQL 容器在跑）：
#   bash server/scripts/undo-drill.sh
#
# 注意：脚本会 DROP 并重建 $DRILL_DB 这个库，名字里带 drill 是刻意的。
set -uo pipefail

DRILL_DB="${DRILL_DB:-pet_health_undo_drill}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ph-mysql-dev}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-devroot}"
# 容器映射到宿主机的地址（默认与 deploy/docker-compose.dev.yml 一致：3307）
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 注意 stdin：`docker exec -i` 会读走 stdin。循环里若让它继承循环的 stdin，
# 会把「待处理文件列表」一起读掉、循环只跑一轮——演练脚本自己踩过这个坑。
sql() { docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@" < /dev/null 2>&1; }
sql_file() { docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$1" < "$2" 2>&1; }

echo "== 1/5 重建 scratch 库 $DRILL_DB"
sql -e "DROP DATABASE IF EXISTS $DRILL_DB; CREATE DATABASE $DRILL_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" >/dev/null || exit 1

echo "== 2/5 起一次应用，让 Flyway 把迁移跑完（端口 8081，避免撞开发实例）"
cd "$SERVER_DIR"
MYSQL_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$DRILL_DB?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai" \
  SERVER_PORT=8081 setsid nohup ./mvnw -B -o -pl ph-boot -am spring-boot:run > /tmp/undo-drill-boot.log 2>&1 < /dev/null &
APP_PID=$!
for _ in $(seq 1 40); do
  sleep 3
  if curl -sf -m 3 http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; then break; fi
done
curl -sf -m 3 http://127.0.0.1:8081/actuator/health >/dev/null 2>&1 || { echo "应用没起来，见 /tmp/undo-drill-boot.log"; exit 1; }
# **只杀自己起的这一组**（setsid 让它成为进程组组长，负号杀整组）：
# 用 `pkill -f spring-boot:run` 会连开发实例一起匹配——而且只杀到父进程、留下 fork 出的 JVM 占着 8080。
kill -TERM -"$APP_PID" 2>/dev/null || kill -TERM "$APP_PID" 2>/dev/null || true
sleep 3

echo "== 3/5 造一份有代表性的数据（含「同一天多条防疫记录」——U4 的坑就在这里）"
sql "$DRILL_DB" -e "
INSERT IGNORE INTO \`user\` (id, phone_enc, phone_hash, password_hash, nickname, status) VALUES (1, 'enc', 'hash', 'pw', '演练用户', 1);
INSERT IGNORE INTO pet (id, user_id, name, species, weight) VALUES (1, 1, '豆豆', 1, 8.20);
INSERT IGNORE INTO archive_record (pet_id, user_id, record_date, category, content, abnormal, numeric_value) VALUES
  (1, 1, '2026-09-20', 1, '{}', 0, 8.20), (1, 1, '2026-09-21', 7, '{}', 0, NULL), (1, 1, '2026-09-21', 7, '{}', 0, NULL);
INSERT IGNORE INTO health_score (pet_id, calc_date, total_score, included_dimensions) VALUES (1, '2026-09-21', 70, 3);
INSERT IGNORE INTO message (user_id, pet_id, kind, type, title, content, status, dedup_key, remind_at) VALUES (1, 1, 1, 1, 't', 'c', 1, 'drill-1', '2026-09-21 08:00:00');
INSERT IGNORE INTO ai_consult (user_id, pet_id, trace_id, question_enc, risk_level, prompt_tokens, completion_tokens) VALUES (1, 1, 't', 'enc', 2, 100, 50);
" >/dev/null

# **校验数据真的进去了**：INSERT IGNORE 静默失败的话，演练会「通过」但什么都没验到——
# 而 U4 的坑恰恰只有数据在的时候才暴露（禁止吞异常，这里同理）
rows=$(sql -N "$DRILL_DB" -e "SELECT COUNT(*) FROM archive_record;" | grep -E "^[0-9]+$" | head -1)
if [ "${rows:-0}" -lt 3 ]; then
  echo "造数据失败（archive_record 只有 ${rows:-0} 行）——演练没有意义，中止"
  exit 1
fi
echo "  数据就位：archive_record $rows 行"

echo "== 4/5 从最新往下执行 undo（模拟人工三步：跑脚本 + 删历史行）"
failed=0
UNDO_DIR="$SERVER_DIR/ph-boot/src/main/resources/db/undo"
# 版本号从文件名里取出来单独排序：`ls` 给的是完整路径，直接 sort 会按路径（不是版本号）排，
# 逆序就变成乱序——演练脚本自己踩过一次
while read -r version f; do
  output=$(sql_file "$DRILL_DB" "$f")
  if echo "$output" | grep -q "ERROR"; then
    echo "  ✗ $(basename "$f")：$(echo "$output" | grep ERROR | head -1)"
    failed=1
  else
    echo "  ✓ $(basename "$f")"
    sql -e "DELETE FROM $DRILL_DB.flyway_schema_history WHERE version='$version';" >/dev/null
  fi
done < <(
  for f in "$UNDO_DIR"/U*.sql; do
    echo "$(basename "$f" | sed -E 's/^U([0-9]+)__.*/\1/') $f"
  done | sort -k1,1nr
)

echo "== 5/5 确认 schema 已回到空"
left=$(sql -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DRILL_DB';" \
  | grep -vE "Warning|flyway_schema_history" | tr '\n' ' ')
if [ -n "$left" ]; then
  echo "  残留对象：$left（回滚不完整）"
  failed=1
else
  echo "  干净：只剩 flyway_schema_history"
fi

if [ "$failed" -ne 0 ]; then
  echo "演练失败——回滚脚本有问题，别在真出事那天才发现"
  exit 1
fi
echo "演练通过：$(ls "$UNDO_DIR"/U*.sql | wc -l) 个回滚脚本在带数据的库上都能跑，且回到空 schema"
