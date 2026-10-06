#!/usr/bin/env bash
# 一键上线：把本地全栈通过 Cloudflare 快速隧道映到公网，拿到三个能直接打开的地址。
#
# 为什么要它，而不是「部署到云」：本项目三端 + 后端 + AI 服务跑在**真实 MySQL/Redis** 上，
# 部署到云要一台服务器、一套账号与域名（见 README「在线演示」一节的取舍）。快速隧道不需要
# 任何账号——把本机已经在跑的全栈原样映出去，适合演示、验收、给甲方点开看。代价写清楚：
#   · 域名是**临时**的（*.trycloudflare.com，每次重启都换一串随机词）；
#   · **本机关机 / 本脚本停了 / 本机睡过去，就不在线了**（隧道只是转发，服务还在本机）；
#   · 谁拿到地址谁就能访问（演示数据，别放真实用户数据）。
#
# 用法：
#   ./online.sh                起全栈（若没起）→ 起三条隧道 → 打印地址（日常用这个）
#   ./online.sh --status       看当前三个地址还活着没（逐条真访问一次）
#   ./online.sh --stop         停掉三条隧道（全栈不动，要停全栈用 ./open.sh --stop）
#   ./online.sh --http2        先用 http2 连边缘（默认 quic，不通会自动换协议重试）
#
# 可以只上线其中几个（名字见下），例如：
#   ./online.sh c-web          只上线 C 端
#
# 耐心是可以调的：TUNNEL_WAIT=300 ./online.sh  表示每条隧道最多等 300 秒。
#
# 实现上的四处坑（都踩过，别改回去）：
#   1. cloudflared 必须带 `--http-host-header localhost:<端口>`。Vite 7 会校验 Host 头，
#      隧道域名不在白名单里会被 403「Blocked request」挡在门外——加了这个头，Vite 看到的
#      Host 就是 localhost:<端口>，与本地开发完全同路。
#   2. 三条隧道要各自指定 `--metrics` 端口：cloudflared 每个实例默认都抢 127.0.0.1:20241，
#      第二个实例会因端口被占而起不来（报 "address already in use" 后退出）。
#   3. **拿到域名 ≠ 能用**：Cloudflare 侧 DNS 生效、边缘隧道连通都需要时间，第一分钟里
#      502/530/405 都见过；而本机到 Cloudflare 边缘的网络是**会抖的**（实测同一分钟里
#      一条通、一条 dial timeout），cloudflared 自己会重连——所以要「并行拉起 + 有耐心地
#      轮询」，秒级判死等于把能救活的隧道亲手掐了。
#   4. 判「能用」要走到后端：只 curl 页面拿 200 不够——页面是 Vite 给的，后端没通它照样 200
#      （照片传不上、图全裂这类问题就发生在这一层）。所以每条隧道都验一条**免登录**接口
#      （/api/v1/app/catalog/categories，ADR-0037 第一节：只读浏览不需要登录）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN"

# 每条隧道「等能用」的秒数（阶段一、阶段二各一份，所以总上限约两倍）
TUNNEL_WAIT="${TUNNEL_WAIT:-150}"

# ---------------------------------------------------------------- 输出小工具（与 open.sh 同一套）

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
一键上线：本地全栈 → Cloudflare 快速隧道 → 三个公网地址。

用法：
  ./online.sh                起全栈（若没起）→ 起三条隧道 → 打印地址
  ./online.sh --status       看当前地址与存活情况
  ./online.sh --stop         停掉隧道（全栈不动）
  ./online.sh --http2        先用 http2 协议（默认 quic，不通会自动换）

可选位置参数用来只上线其中几个：
  c-web（C 端 :5173） / provider-web（服务者后台 :5174） / admin-web（运营后台 :5175）

环境变量：TUNNEL_WAIT（默认 150，每条隧道每轮的等待秒数）
EOF
}

# ---------------------------------------------------------------- 目标定义

names=(c-web provider-web admin-web)
ports=(5173 5174 5175)
labels=("C 端" "服务者后台" "运营后台")
metrics=(20241 20242 20243)

# ---------------------------------------------------------------- 参数

action="up"
protocol="quic"
only=()
while [ $# -gt 0 ]; do
  case "$1" in
    --status) action="status" ;;
    --stop)   action="stop" ;;
    --http2)  protocol="http2" ;;
    -h|--help) usage; exit 0 ;;
    *)     only+=("$1") ;;
  esac
  shift
done

selected=()
if [ ${#only[@]} -eq 0 ]; then
  selected=(0 1 2)
else
  for want in "${only[@]}"; do
    found=""
    for i in 0 1 2; do
      [ "${names[$i]}" = "$want" ] && { selected+=("$i"); found=1; }
    done
    [ -n "$found" ] || die "不认识的名字：$want（可选：${names[*]}）"
  done
fi

# ---------------------------------------------------------------- 工具函数

port_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# 找 cloudflared：PATH 里没有就按安装位置兜底（本机装在 ~/.local/bin）
find_cloudflared() {
  command -v cloudflared 2>/dev/null || {
    [ -x "$HOME/.local/bin/cloudflared" ] && printf '%s' "$HOME/.local/bin/cloudflared"
  }
}

url_file() { printf '%s/tunnel-%s.url' "$RUN" "$1"; }

tunnel_alive() { # tunnel_alive <名字>
  local pidfile="$RUN/tunnel-$1.pid" pid
  [ -f "$pidfile" ] || return 1
  pid="$(cat "$pidfile" 2>/dev/null)"
  [ -n "$pid" ] && kill -0 -- "-$pid" 2>/dev/null
}

# 真访问一轮：页面是本端的 HTML，且**免登录接口穿到了后端**（页面 200 只证明 Vite 活着）。
# 失败原因写进 PROBE_REASON，成功时清空。
PROBE_REASON=""
probe_once() { # probe_once <url>
  local url="$1" code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$url/" 2>/dev/null)"
  [ "$code" = "200" ] || { PROBE_REASON="页面 HTTP ${code:-连不上}"; return 1; }
  curl -fsS --max-time 15 "$url/" 2>/dev/null | grep -q '<div id="app"' \
    || { PROBE_REASON="返回的不是本端页面"; return 1; }
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$url/api/v1/app/catalog/categories" 2>/dev/null)"
  [ "$code" = "200" ] || { PROBE_REASON="接口 HTTP ${code:-连不上}（页面通了、后端没通）"; return 1; }
  PROBE_REASON=""
  return 0
}

# 起一条隧道并等日志里出现域名（不判可用；最多 60 秒）
launch() { # launch <索引> <协议>
  local i="$1" proto="$2" name="${names[$i]}" port="${ports[$i]}"
  # 注意：**不要**在这里声明 `local cf`——全局的 cf 是 cloudflared 路径，同名 local 会把它
  # 遮蔽成空值，set -u 下 `"$cf"` 直接报 unbound variable（第一次就踩了）。
  local log="$RUN/tunnel-$name.log" pidfile="$RUN/tunnel-$name.pid" pid n=0

  rm -f "$(url_file "$name")"
  (
    setsid "$cf" tunnel --url "http://127.0.0.1:$port" \
      --http-host-header "localhost:$port" \
      --metrics "127.0.0.1:${metrics[$i]}" \
      --protocol "$proto" --no-autoupdate >"$log" 2>&1 &
    echo $! >"$pidfile"
  )

  while [ "$n" -lt 120 ]; do
    if grep -qE 'https://[a-z0-9-]+\.trycloudflare\.com' "$log" 2>/dev/null; then
      grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$log" | head -1 >"$(url_file "$name")"
      return 0
    fi
    pid="$(cat "$pidfile" 2>/dev/null)"
    kill -0 -- "-$pid" 2>/dev/null || return 1   # 进程自己退了，别干等
    sleep 0.5; n=$((n + 1))
  done
  return 1
}

# 等一组隧道「能用」：轮询到全通或超时；没通的索引留在 STILL_PENDING
STILL_PENDING=()
wait_until_ok() { # wait_until_ok <秒数> <索引...>
  local secs="$1"; shift
  local -a queued=("$@") still=()
  local deadline=$((SECONDS + secs)) i name url
  while [ "${#queued[@]}" -gt 0 ] && [ "$SECONDS" -lt "$deadline" ]; do
    still=()
    for i in "${queued[@]}"; do
      name="${names[$i]}"
      if [ ! -f "$(url_file "$name")" ]; then
        # 域名还没出现：进程活着就继续等（边缘连接在重试），死了就不等了
        if tunnel_alive "$name"; then
          still+=("$i")
        else
          warn "${labels[$i]} 隧道进程已退出（看 .run/tunnel-$name.log 末尾）"
        fi
        continue
      fi
      url="$(cat "$(url_file "$name")")"
      if probe_once "$url"; then
        ok "${labels[$i]} 上线：$url"
      else
        last_reason[$i]="$PROBE_REASON"
        still+=("$i")
      fi
    done
    queued=("${still[@]}")
    [ "${#queued[@]}" -gt 0 ] && sleep 5
  done
  STILL_PENDING=("${queued[@]}")
}

stop_tunnel() { # stop_tunnel <名字>
  local name="$1" pidfile="$RUN/tunnel-$name.pid" pid i
  [ -f "$pidfile" ] || { rm -f "$(url_file "$name")"; return 0; }
  pid="$(cat "$pidfile" 2>/dev/null)"
  if [ -n "$pid" ] && kill -0 -- "-$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null
    for ((i = 0; i < 20; i++)); do
      kill -0 -- "-$pid" 2>/dev/null || break
      sleep 0.25
    done
    kill -0 -- "-$pid" 2>/dev/null && kill -KILL -- "-$pid" 2>/dev/null
  fi
  rm -f "$pidfile" "$(url_file "$name")"
}

declare -a last_reason=()

# ---------------------------------------------------------------- --status：看地址还活着没

if [ "$action" = "status" ]; then
  say "== 在线状态（逐条真访问一次）=="
  any=0
  for i in "${selected[@]}"; do
    name="${names[$i]}"; label="${labels[$i]}"
    if [ ! -f "$(url_file "$name")" ]; then
      warn "$label 没有地址（没上线过，或上次 --stop 了）"
      continue
    fi
    url="$(cat "$(url_file "$name")")"
    if ! tunnel_alive "$name"; then
      warn "$label 隧道进程不在了：$url"
      continue
    fi
    if probe_once "$url"; then
      ok "$label 在线：$url"
      any=1
    else
      warn "$label 进程在但访问不通（$PROBE_REASON）：$url"
    fi
  done
  [ "$any" = 1 ] || die "没有一个在线——先 ./online.sh"
  exit 0
fi

# ---------------------------------------------------------------- --stop：收隧道

if [ "$action" = "stop" ]; then
  say "== 停掉隧道 =="
  for i in "${selected[@]}"; do
    name="${names[$i]}"
    alive=0
    tunnel_alive "$name" && alive=1
    stop_tunnel "$name"
    if [ "$alive" = 1 ]; then ok "已停 ${labels[$i]} 隧道"; else ok "${labels[$i]} 隧道本来就没在跑"; fi
  done
  rm -f "$RUN/online-urls.txt"
  say ""
  say "全栈还在本地跑着（要停：./open.sh --stop）"
  exit 0
fi

# ---------------------------------------------------------------- 上线：先保证全栈在跑，再起隧道

cf="$(find_cloudflared)"
[ -n "$cf" ] || die "没找到 cloudflared。装法：https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"

say "== 一键上线：宠物 AI 健康管理平台 =="
say ""

# 三端一个都没起 → 顺手把全栈拉起来（open.sh 是幂等的，已起的会跳过）
stack_down=1
for i in "${selected[@]}"; do port_up "${ports[$i]}" && stack_down=0; done
if [ "$stack_down" = 1 ]; then
  say "[1/2] 三个端都不在跑——先起全栈（./open.sh --no-browser，第一次要等后端编译）"
  "$ROOT/open.sh" --no-browser || die "全栈没起来，先看它上面的输出"
  say ""
else
  say "[1/2] 已有端在跑，跳过起全栈（缺的端会被跳过并给出提醒）"
fi

say "[2/2] 起隧道（Cloudflare 快速隧道，免账号；域名是临时的，重启会换）"

# 先并行把隧道都拉起来（域名拿得快），再统一等生效
launched=()
for i in "${selected[@]}"; do
  name="${names[$i]}"
  if ! port_up "${ports[$i]}"; then
    warn "${labels[$i]} 本地 :${ports[$i]} 没在跑，跳过"
    continue
  fi
  if tunnel_alive "$name" && [ -f "$(url_file "$name")" ] && probe_once "$(cat "$(url_file "$name")")"; then
    ok "${labels[$i]} 隧道已在跑且可用：$(cat "$(url_file "$name")")"
    continue
  fi
  if launch "$i" "$protocol"; then
    ok "${labels[$i]} 隧道已建：$(cat "$(url_file "$name")")"
  else
    warn "${labels[$i]} 60 秒没拿到域名（看 .run/tunnel-$name.log 末尾）"
  fi
  launched+=("$i")
done

if [ "${#launched[@]}" -gt 0 ]; then
  say "  等隧道生效……（刚建好时 502/530/405 都属正常；每条最多等 ${TUNNEL_WAIT}s）"
  wait_until_ok "$TUNNEL_WAIT" "${launched[@]}"

  # 还没通的：换协议再来一轮（quic 走 UDP、http2 走 TCP，运营商对两者的态度常常不一样）
  if [ "${#STILL_PENDING[@]}" -gt 0 ]; then
    other="$([ "$protocol" = "quic" ] && echo http2 || echo quic)"
    say "  ${#STILL_PENDING[@]} 条还没通，换 $other 协议重来一轮（最多再等 ${TUNNEL_WAIT}s）"
    for i in "${STILL_PENDING[@]}"; do
      name="${names[$i]}"
      stop_tunnel "$name" >/dev/null 2>&1
      launch "$i" "$other" || warn "${labels[$i]} 换协议后也没拿到域名"
    done
    wait_until_ok "$TUNNEL_WAIT" "${STILL_PENDING[@]}"
  fi
fi

# ---------------------------------------------------------------- 汇总

say ""
say "地址（发给人就能直接打开）："
up=0; down=0
: >"$RUN/online-urls.txt"
for i in "${selected[@]}"; do
  name="${names[$i]}"
  if [ -f "$(url_file "$name")" ] && tunnel_alive "$name" && probe_once "$(cat "$(url_file "$name")")"; then
    url="$(cat "$(url_file "$name")")"
    printf '  %-8s %s\n' "${labels[$i]}" "$url"
    printf '%s\t%s\n' "${labels[$i]}" "$url" >>"$RUN/online-urls.txt"
    up=$((up + 1))
  else
    printf '  %-8s （没起来：%s）\n' "${labels[$i]}" "${last_reason[$i]:-进程不在}"
    down=$((down + 1))
  fi
done

say ""
say "提醒："
say "  · 域名是临时的——本脚本重启隧道、本机关机或断网都会失效，重新 ./online.sh 即换新地址"
say "  · 状态自检：./online.sh --status        下线：./online.sh --stop"
say "  · 日志：.run/tunnel-<名字>.log       地址也存了一份：.run/online-urls.txt"
[ "$down" = 0 ] || exit 1
exit 0
