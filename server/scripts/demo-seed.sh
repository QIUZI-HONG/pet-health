#!/usr/bin/env bash
# 演示数据种子（幂等）：一条命令把「演示前要人工准备的那几样」造出来。
#
# 为什么要有它：第五轮报告 §7.3 列了一份「演示前人工准备」清单——运营要建成本券、配达标线、
# 复核知识条目。每次演示前重来一遍，漏一项演示就少一块（考核的券项永远「不参与」、
# 所有带 vetted 的产物都不出现）。这份脚本把那几样固化成**可重复执行**的动作。
#
# 它分两段，各走各的路（理由写在每段上）：
#
#   · **API 段**：C 端演示账号 / 宠物 / 打卡。必须走接口——密码要 bcrypt 落库、打卡才算数
#     （评分与连续天数都由服务端算），直接写库造出来的「数据」在页面上的表现与真实使用不同。
#
#   · **SQL 段**：运营侧配置（服务者成本券模板、考核达标线、知识条目复核、一张演示券）。
#     这几样平时是**运营在后台点**（券池管理 / 考核规则配置 / AI 运营·知识条目），
#     而脚本里没有运营令牌（运营后台是独立登录域 + 账号白名单，ADR-0012 / ADR-0035），
#     所以这一段直接写库。**它只动配置与一张券，不造用户与订单。**
#
# 幂等：所有行都用固定编码（模板 `CP-901`、券码 `DEMO-` 前缀、演示账号手机号可配），
# 重跑是覆盖不是追加；账号已注册就登录，宠物按名字查重。
#
# 用法（默认值与本地开发一致）：
#   bash server/scripts/demo-seed.sh
#   APP_BASE=http://127.0.0.1:8080 bash server/scripts/demo-seed.sh   # 后端不在默认端口时
#   SKIP_SQL=1 bash server/scripts/demo-seed.sh                       # 只造 C 端数据（不碰库）
#
# 前置：后端在跑（cd server && ./mvnw -pl ph-boot -am spring-boot:run）、
#       MySQL 容器在跑（docker compose -f deploy/docker-compose.dev.yml up -d）。
set -uo pipefail

APP_BASE="${APP_BASE:-http://127.0.0.1:8080}"
DEMO_PHONE="${DEMO_PHONE:-13800138000}"
DEMO_PASSWORD="${DEMO_PASSWORD:-pet12345}"
DEMO_NAME="${DEMO_NAME:-演示用户}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ph-mysql-dev}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-devroot}"
MYSQL_DB="${MYSQL_DB:-pet_health}"
SKIP_SQL="${SKIP_SQL:-0}"

API="$APP_BASE/api/v1/app"

# 两个细节：
#   · 密码走 MYSQL_PWD 环境变量，不用 -p"$PASS"——后者会让 mysql 打一行警告，
#     而那行警告会混进 `-N -B` 的取值结果里（这个脚本第一版就中过一次）；
#   · docker exec -i 会读走 stdin：加 < /dev/null，否则循环里的下一次读会被它吃掉。
sql() { MYSQL_PWD="$MYSQL_PASSWORD" docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
          mysql -u"$MYSQL_USER" "$MYSQL_DB" -N -B -e "$1" < /dev/null; }
# 只判成败、不取值的那种调用（配置写入）：失败就报错退出，别让演示带着半套配置上
sql_or_die() { local out; out=$(sql "$1") || { echo "SQL 执行失败：$1\n$out" >&2; exit 1; }; }

# 从 JSON 里取一个字段（用 python3：容器里不一定有 jq，而 python3 是本机前置）
json_field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)" 2>/dev/null; }

api() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local args=(-s -X "$method" "$API$path" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

echo "== 0/4 前置检查"
# 探活用「连得上」而不是某个具体路径：actuator 在独立管理端口（9090），
# 8080 上任何路径的 404 都说明进程活着——curl 的连接失败（exit != 0）才是没起来
if ! curl -s -o /dev/null --max-time 5 "$APP_BASE/"; then
  echo "后端不可达：$APP_BASE（先起它：cd server && ./mvnw -pl ph-boot -am spring-boot:run）" >&2
  exit 1
fi
if [ "$SKIP_SQL" != "1" ]; then
  if ! MYSQL_PWD="$MYSQL_PASSWORD" docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -u"$MYSQL_USER" -e 'SELECT 1' >/dev/null 2>&1; then
    echo "MySQL 容器不可达：$MYSQL_CONTAINER（起它：docker compose -f deploy/docker-compose.dev.yml up -d）；" >&2
    echo "只想造 C 端数据可以 SKIP_SQL=1 再跑一次。" >&2
    exit 1
  fi
fi

echo "== 1/4 演示账号（已注册就登录）"
LOGIN=$(api POST /auth/login "{\"phone\":\"$DEMO_PHONE\",\"password\":\"$DEMO_PASSWORD\"}")
TOKEN=$(echo "$LOGIN" | json_field "['data']['access_token']")
SESSION="$LOGIN"
if [ -z "$TOKEN" ]; then
  SESSION=$(api POST /auth/register "{\"phone\":\"$DEMO_PHONE\",\"password\":\"$DEMO_PASSWORD\",\"nickname\":\"$DEMO_NAME\"}")
  TOKEN=$(echo "$SESSION" | json_field "['data']['access_token']")
fi
if [ -z "$TOKEN" ]; then
  echo "注册 / 登录都失败：$LOGIN" >&2
  exit 1
fi
# 演示账号的 id **从响应里拿**，不用「库里最新一行」——后者在多账号的库里会指错人
USER_ID=$(echo "$SESSION" | json_field "['data']['user']['id']")
if [ -z "$USER_ID" ]; then
  echo "登录响应里没有 user.id：$SESSION" >&2
  exit 1
fi
echo "   令牌已取得（演示账号 id=$USER_ID）"

echo "== 2/4 宠物与打卡（走接口：评分与连续天数由服务端算）"
PETS=$(api GET /pets "" "$TOKEN")
# 宠物按名字查重（幂等）：已有「豆豆」就用它，没有才建
PET_ID=$(echo "$PETS" | python3 -c "
import sys, json
data = json.load(sys.stdin).get('data') or []
print(next((p['id'] for p in data if p['name'] == '豆豆'), ''))
" 2>/dev/null)
PET_ID=$(echo "$PETS" | python3 -c "
import sys,json
data=json.load(sys.stdin).get('data') or []
print(next((p['id'] for p in data if p['name']=='豆豆'), ''))
" 2>/dev/null)
if [ -z "$PET_ID" ]; then
  PET_ID=$(api POST /pets '{"name":"豆豆","species":1,"breed":"柯基","gender":1,"birthday":"2023-05-01","weight":"8.20"}' "$TOKEN" \
    | json_field "['data']['id']")
fi
if [ -z "$PET_ID" ]; then
  echo "建档失败：接口没返回宠物 id" >&2
  exit 1
fi
echo "   豆豆 id=$PET_ID"

TODAY=$(date +%F)
api POST "/pets/$PET_ID/check-ins" "{\"date\":\"$TODAY\",\"items\":[
  {\"category\":1,\"value\":\"8.20\"},{\"category\":2,\"value\":\"normal\"},{\"category\":3,\"value\":\"normal\"},
  {\"category\":4,\"value\":\"normal\"},{\"category\":5,\"value\":\"normal\"},{\"category\":6,\"value\":\"normal\"}]}" "$TOKEN" \
  | json_field "['code']" >/dev/null
echo "   今日打卡已提交（幂等：同日重复提交是更新）"

if [ "$SKIP_SQL" = "1" ]; then
  echo "== 3/4 SQL 段已跳过（SKIP_SQL=1）"
  echo "== 4/4 完成：C 端演示数据就绪（账号 $DEMO_PHONE / 密码 $DEMO_PASSWORD）"
  exit 0
fi

echo "== 3/4 运营侧配置（平时在后台点：券池管理 / 考核规则配置 / AI 运营·知识条目）"
sql_or_die "INSERT INTO coupon_template (code, name, face_value, min_amount, valid_days, cost_bearer, scope_type, scope_codes, description, status)
     VALUES ('CP-901', '演示·服务者成本券 15 元', 15.00, 0.00, 30, 1, 1, 'GROOMING', '演示用：考核的券项要看「券池里有没有服务者成本的券」（cost_bearer=1）', 1)
     ON DUPLICATE KEY UPDATE name = VALUES(name), face_value = VALUES(face_value), status = 1;"
echo "   成本券模板 CP-901（cost_bearer=1）已就位——否则考核的券项永远「不参与」"

sql_or_die "UPDATE assessment_rule SET invite_target = 1, coupon_target = 1.00 WHERE id = 1;"
echo "   考核达标线（拉新 1 人 / 券 1.00）已配——种子是 0 = 平台还没定要求"

sql_or_die "UPDATE knowledge_entry
        SET review_status = 'vetted', reviewed_by = '演示复核', reviewed_credential = '执业兽医师，证号 DEMO-0001',
            reviewed_at = NOW()
      WHERE code IN ('K-0001', 'K-0002', 'K-0003');"
echo "   知识条目 K-0001~0003 已复核——只有 vetted 能进 AI 回答的引用（ADR-0054）"

sql_or_die "INSERT INTO coupon (code, user_id, template_id, source, source_ref, face_value, min_amount,
                        scope_type, scope_codes, status, valid_from, valid_until, issued_at)
     SELECT 'DEMO-GROOM-20', $USER_ID, t.id, 4, 'demo-seed', t.face_value, t.min_amount,
            t.scope_type, t.scope_codes, 1, NOW(), DATE_ADD(NOW(), INTERVAL t.valid_days DAY), NOW()
       FROM coupon_template t WHERE t.code = 'CP-101'
     ON DUPLICATE KEY UPDATE status = 1, valid_until = DATE_ADD(NOW(), INTERVAL 30 DAY);"
echo "   演示券 DEMO-GROOM-20 已发给 $DEMO_PHONE（id=$USER_ID）——券包与下单页因此有券可选"

echo "== 4/4 完成"
cat <<EOF

演示账号：$DEMO_PHONE / $DEMO_PASSWORD（昵称 $DEMO_NAME）
已经就绪的看点：
  · 首页健康评分（今日已打卡）· 券包有一张洗护券 · 下单页默认选中服务端算出的最优券
  · 考核的券项「参与」；知识条目 K-0001~0003 已复核（AI 回答里会出现引用）
还没就绪（要人工或走真实流程）：
  · 被邀请人 / 邀请人的奖励：归因 → 观察窗（24 小时）→ 结算才发，演示现场等不到，改用上面的演示券
  · 服务者侧的门店与订单：走入驻 → 审核 → 选品定价 → 上架（运营后台「服务者审核」页可全流程点完）
EOF
