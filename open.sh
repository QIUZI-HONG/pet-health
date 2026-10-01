#!/usr/bin/env bash
# 一键打开：把本地全栈拉起来，然后在浏览器里打开。
#
# 为什么要有它：「打开项目」在这台机器上实际要做一串事——起中间件、起 AI 服务、起后端、
# 起三个端，再把地址敲进浏览器。这份脚本把它们固化成一条命令，**幂等**：已经在跑的会跳过，
# 重复执行只补没起的那几个，最后把三个端都打开。等价于 README「本地怎么跑」的 1–4 步（三端都起）。
#
# 它依次做（每一件都先探测、再启动、后等待健康）：
#   1. 中间件    MySQL 8 :3307 + Redis :6379   （docker compose，随 --stop 也不停，见命令输出）
#   2. AI 服务   http://127.0.0.1:8000         （ai/.venv/bin/uvicorn，不带 --reload：改 AI 代码要重跑本步）
#   3. 后端      http://127.0.0.1:8080         （server/./mvnw spring-boot:run，自动读 server/.env；
#                                              健康检查在独立管理端口 :9090/actuator/health）
#   4. 三个端    C 端 :5173 / 服务者后台 :5174 / 运营后台 :5175（pnpm --filter <app> dev）
#   5. 在 Windows 侧默认浏览器把三个端都打开（--no-browser 可跳过）
#
# 用法：
#   ./open.sh                 起全栈（三端都起）并打开三个地址（日常用这个）
#   ./open.sh --no-browser    只起服务，不开浏览器
#   ./open.sh --folder        不开服务，只在 Windows 资源管理器里打开项目文件夹
#   ./open.sh --stop          停掉本脚本起的进程（中间件容器不动，停法见命令输出）
#
# 日志与 PID 都在 .run/（已 gitignore）。哪个服务没起来，先看 .run/<名字>.log 的末尾。
# 前提：Docker Desktop 已启动；server/.env 已配（cp server/.env.example server/.env 再按注释生成密钥）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN"

# 健康检查的两处口径（踩过坑，别再改回去）：
#   · AI 的 /internal/health 要带内部鉴权头（x-internal-token），不带返回 401——令牌与后端
#     AI_SERVICE_TOKEN 必须一致，这里从 ai/.env 读（缺省与 .env.example 相同）。
#   · 后端 actuator 走**独立管理端口**（application.yml：MANAGEMENT_PORT，默认 9090），
#     8080 上直接访问是 404。
AI_TOKEN="$(grep -s '^INTERNAL_TOKEN=' "$ROOT/ai/.env" | head -1 | cut -d= -f2-)"
AI_TOKEN="${AI_TOKEN:-dev-internal-token}"
MGMT_PORT="$(grep -s '^MANAGEMENT_PORT=' "$ROOT/server/.env" | head -1 | cut -d= -f2-)"
MGMT_PORT="${MGMT_PORT:-9090}"

# ---------------------------------------------------------------- 输出小工具

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
一键打开：起本地全栈（中间件 → AI 服务 → 后端 → 三个端）并在浏览器里打开。

用法：
  ./open.sh                 起全栈（三端都起）并打开三个地址（日常用这个）
  ./open.sh --no-browser    只起服务，不开浏览器
  ./open.sh --folder        不开服务，只在 Windows 资源管理器里打开项目文件夹
  ./open.sh --stop          停掉本脚本起的进程（中间件容器不动）

日志与 PID 在 .run/（已 gitignore）。哪个服务没起来，先看 .run/<名字>.log 末尾。
EOF
}

# ---------------------------------------------------------------- 探测与等待

# 端口是否有人在听（bash 内建 /dev/tcp，不依赖 nc）
port_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# HTTP 是否返回 2xx/3xx（--fail 让 4xx/5xx 也算「没就绪」）
http_up() { curl -fsS --max-time 2 "$1" >/dev/null 2>&1; }

# 等端口监听；第三个参数非空时显示进度点。wait_port <端口> <秒> [dots]
wait_port() {
  local port="$1" secs="$2" dots="${3:-}" i=0 n printed=0
  n=$(( secs * 2 ))
  while (( i < n )); do
    if port_up "$port"; then [ "$printed" = 1 ] && printf '\n'; return 0; fi
    sleep 0.5
    if [ -n "$dots" ] && (( i % 8 == 7 )); then printed=1; printf '.'; fi
    (( i++ ))
  done
  [ "$printed" = 1 ] && printf '\n'
  return 1
}

# 等某个判定命令返回成功；第三个参数非空时显示进度点。wait_ok <判定命令> <秒> [dots]
wait_ok() {
  local fn="$1" secs="$2" dots="${3:-}" i=0 n printed=0
  n=$(( secs * 2 ))
  while (( i < n )); do
    if "$fn"; then [ "$printed" = 1 ] && printf '\n'; return 0; fi
    sleep 0.5
    if [ -n "$dots" ] && (( i % 8 == 7 )); then printed=1; printf '.'; fi
    (( i++ ))
  done
  [ "$printed" = 1 ] && printf '\n'
  return 1
}

# 后端健康：actuator 在独立管理端口，业务端口上没有它
backend_up() { http_up "http://127.0.0.1:$MGMT_PORT/actuator/health"; }

# AI 健康：带内部鉴权头（无 token 会被 401 挡掉）
ai_up() { curl -fsS --max-time 2 -H "x-internal-token: $AI_TOKEN" "http://127.0.0.1:8000/internal/health" >/dev/null 2>&1; }

# ---------------------------------------------------------------- 启动与停止

# 在后台起一个服务：独立会话（setsid）+ 日志 + PID 文件。
# 独立会话很关键：mvnw 会派生 java、pnpm 会派生 vite，--stop 要能一次收掉整棵进程树。
start_bg() { # start_bg <名字> <工作目录> <命令...>
  local name="$1" dir="$2"; shift 2
  (
    cd "$dir" || exit 1
    setsid "$@" >"$RUN/$name.log" 2>&1 &
    echo $! >"$RUN/$name.pid"
  )
}

# 停掉某一服务（按记录的进程组关，先 TERM 后 KILL）。stop_one <名字>
stop_one() {
  # 注意：`local name="$1" pidfile="$RUN/$name.pid"` 这种连写不行——bash 会把同一行里
  # 的 `$name` 在赋值前展开（set -u 下直接报 unbound variable）。必须分开写。
  local name="$1" pidfile pid i
  pidfile="$RUN/$name.pid"
  [ -f "$pidfile" ] || return 0
  pid="$(cat "$pidfile" 2>/dev/null)"
  if [ -n "$pid" ] && kill -0 -- "-$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null
    for ((i = 0; i < 40; i++)); do
      kill -0 -- "-$pid" 2>/dev/null || break
      sleep 0.25
    done
    kill -0 -- "-$pid" 2>/dev/null && kill -KILL -- "-$pid" 2>/dev/null
    ok "已停 $name（进程组 $pid）"
  fi
  rm -f "$pidfile"
}

# 在 Windows 侧默认浏览器打开一个地址
open_url() { # open_url <url>
  if command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /c start "" "$1" >/dev/null 2>&1 || true
  elif command -v explorer.exe >/dev/null 2>&1; then
    explorer.exe "$1" >/dev/null 2>&1 || true
  else
    warn "没有 Windows 互操作，请手动打开：$1"
  fi
}

# ---------------------------------------------------------------- 参数

action="open"   # open | folder | stop
browser=1

while [ $# -gt 0 ]; do
  case "$1" in
    --no-browser)  browser=0 ;;
    --folder)      action="folder" ;;
    --stop)        action="stop" ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "不认识的参数：$1（./open.sh --help）" ;;
  esac
  shift
done

# ---------------------------------------------------------------- --folder：只开文件夹

if [ "$action" = "folder" ]; then
  if command -v explorer.exe >/dev/null 2>&1; then
    explorer.exe "$(wslpath -w "$ROOT" 2>/dev/null || printf '%s' "$ROOT")" >/dev/null 2>&1 || true
    ok "已在资源管理器打开：$ROOT"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$ROOT" >/dev/null 2>&1 || true
    ok "已用文件管理器打开：$ROOT"
  else
    say "手动打开：$ROOT"
  fi
  exit 0
fi

# ---------------------------------------------------------------- --stop：收进程

if [ "$action" = "stop" ]; then
  say "== 停掉 open.sh 起的进程 =="
  stop_one c-web
  stop_one provider-web
  stop_one admin-web
  stop_one ai
  stop_one server
  say ""
  say "中间件容器不动（本地数据卷还在）。要一起停："
  say "  docker compose -f deploy/docker-compose.dev.yml stop"
  exit 0
fi

# ---------------------------------------------------------------- 起全栈

say "== 一键打开：宠物 AI 健康管理平台 =="
say "（已经在跑的服务会跳过；日志在 .run/）"
say ""

# 1/4 中间件
say "[1/4] 中间件（MySQL :3307 / Redis :6379）"
command -v docker >/dev/null 2>&1 || die "没找到 docker（Docker Desktop 装了吗？）"
docker info >/dev/null 2>&1 || die "Docker daemon 没在跑——先启动 Docker Desktop，再重跑本脚本"
docker compose -f "$ROOT/deploy/docker-compose.dev.yml" up -d >/dev/null
wait_port 3307 60 || die "MySQL 60 秒没就绪，看：docker logs ph-mysql-dev"
wait_port 6379 30 || die "Redis 30 秒没就绪，看：docker logs ph-redis-dev"
ok "中间件就绪"

# 2/4 AI 服务
say "[2/4] AI 服务（:8000）"
if port_up 8000; then
  ok "已在跑，跳过"
elif [ ! -x "$ROOT/ai/.venv/bin/uvicorn" ]; then
  warn "没找到 ai/.venv——跳过 AI 服务（建法见 README「本地怎么跑」第 2 步）"
else
  [ -f "$ROOT/ai/.env" ] || warn "ai/.env 不在——若启动失败，cp ai/.env.example ai/.env 并填 AI_API_KEY"
  start_bg ai "$ROOT/ai" "$ROOT/ai/.venv/bin/uvicorn" app.main:app --port 8000
  if wait_ok ai_up 30 dots; then
    ok "AI 服务就绪"
  else
    warn "AI 服务 30 秒没响应，看 .run/ai.log"
  fi
fi

# 3/4 后端
say "[3/4] 后端（:8080）"
if port_up 8080; then
  ok "已在跑，跳过"
else
  [ -f "$ROOT/server/.env" ] || die "server/.env 不在——cp server/.env.example server/.env 并按注释生成三把密钥（README「环境变量」）"
  start_bg server "$ROOT/server" ./mvnw -pl ph-boot -am spring-boot:run
  if wait_ok backend_up 240 dots; then
    ok "后端就绪"
  else
    die "后端 240 秒没起来，看 .run/server.log 的末尾"
  fi
fi

# 4/4 三个端（C 端 + 两个后台，默认全起）
say "[4/4] 三个端（C 端 :5173 / 服务者后台 :5174 / 运营后台 :5175）"
[ -d "$ROOT/node_modules" ] || die "根 node_modules 不在——先在仓库根 pnpm install"

start_front() { # start_front <包名> <端口> <显示名>
  local pkg="$1" port="$2" label="$3"
  if port_up "$port"; then
    ok "${label}已在跑，跳过"
    return 0
  fi
  start_bg "$pkg" "$ROOT" pnpm --filter "$pkg" dev
  if wait_port "$port" 90 dots; then ok "${label}就绪"; else warn "${label}没起来，看 .run/$pkg.log"; fi
}

start_front c-web        5173 "C 端"
start_front provider-web 5174 "服务者后台"
start_front admin-web    5175 "运营后台"

# ---------------------------------------------------------------- 打开浏览器 + 汇总

if [ "$browser" = 1 ]; then
  open_url "http://localhost:5173/"
  open_url "http://localhost:5174/"
  open_url "http://localhost:5175/"
fi

say ""
say "地址："
say "  C 端        http://localhost:5173   ← 主入口"
say "  服务者后台  http://localhost:5174"
say "  运营后台    http://localhost:5175"
say "  后端健康    http://127.0.0.1:9090/actuator/health（独立管理端口）"
say "  AI 健康     http://127.0.0.1:8000/internal/health（要带 x-internal-token 头）"
say ""
say "演示数据（没造过就跑一次，幂等）：bash server/scripts/demo-seed.sh"
say "日志：.run/*.log        停：./open.sh --stop"
