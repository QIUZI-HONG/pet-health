"""性能冒烟基线：把交付文档的验收指标测成数据（此前一次都没测过）。

口径说明（ADR-0014 把「性能压测与通过标准」留在 #82 未定，这里是**基线测量**不是达标判定）：
- 单机本地、MySQL/Redis 走 Docker、测试机与目标机同机 → 数字只说明「当前量级」；
- 并发用线程模拟（不是专业压测工具），够看出有没有量级问题。
"""
import json, statistics, sys, threading, time, urllib.error, urllib.request

BASE = "http://127.0.0.1:8080/api/v1"

def call(path, body=None, token=None):
    req = urllib.request.Request(BASE + path)
    req.add_header("Content-Type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, json.dumps(body).encode() if body is not None else None) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())

phone = sys.argv[1]
tok = call("/app/auth/register", {"phone": phone, "password": "Passw0rd123"})["data"]["access_token"]
pet = call("/app/pets", {"name": "性能犬", "species": 1}, tok)["data"]["id"]

def timed(fn, n):
    xs = []
    for _ in range(n):
        t0 = time.perf_counter()
        fn()
        xs.append((time.perf_counter() - t0) * 1000)
    xs.sort()
    return xs

def pct(xs, p):
    return xs[min(len(xs) - 1, int(len(xs) * p))]

print("单请求延迟（毫秒，n=30）：")
endpoints = [
    ("GET /users/me", lambda: call("/app/users/me", None, tok)),
    ("GET /pets", lambda: call("/app/pets", None, tok)),
    ("GET /pets/{id}/health-score", lambda: call(f"/app/pets/{pet}/health-score", None, tok)),
    ("GET /pets/{id}/check-ins", lambda: call(f"/app/pets/{pet}/check-ins", None, tok)),
    ("GET /messages?page=1", lambda: call("/app/messages?page=1&page_size=20", None, tok)),
    ("GET /messages/highlights", lambda: call("/app/messages/highlights?limit=6", None, tok)),
    ("GET /compliance/documents", lambda: call("/app/compliance/documents", None, tok)),
]
results = {}
for name, fn in endpoints:
    xs = timed(fn, 30)
    results[name] = {"p50": pct(xs, 0.5), "p95": pct(xs, 0.95), "max": xs[-1]}
    print(f"  {name:32s} p50={pct(xs,0.5):6.1f}  p95={pct(xs,0.95):6.1f}  max={xs[-1]:6.1f}")

print("\n写接口（每项 n=10，含事务与索引写入）：")
def post_checkin():
    call(f"/app/pets/{pet}/check-ins", {"date": time.strftime("%Y-%m-%d"),
         "items": [{"category": 2, "value": "normal"}]}, tok)
xs = timed(post_checkin, 10)
results["POST /check-ins"] = {"p50": pct(xs,0.5), "p95": pct(xs,0.95), "max": xs[-1]}
print(f"  {'POST /pets/{id}/check-ins':32s} p50={pct(xs,0.5):6.1f}  p95={pct(xs,0.95):6.1f}  max={xs[-1]:6.1f}")

print("\n并发 20 路（每路 10 次，混合读接口）：")
errors = []
lat = []
def worker():
    for _ in range(10):
        t0 = time.perf_counter()
        r = call(f"/app/pets/{pet}/health-score", None, tok)
        lat.append((time.perf_counter() - t0) * 1000)
        if r.get("code") != 0:
            errors.append(r)
threads = [threading.Thread(target=worker) for _ in range(20)]
t0 = time.perf_counter()
for t in threads: t.start()
for t in threads: t.join()
wall = time.perf_counter() - t0
lat.sort()
qps = 200 / wall
print(f"  20 路 × 10 次 = 200 次请求，{wall:.2f}s → {qps:.0f} req/s（吞吐上限的粗估）；"
      f"p50={pct(lat,0.5):.1f}ms p95={pct(lat,0.95):.1f}ms max={lat[-1]:.1f}ms；错误 {len(errors)}")

json.dump({"single": results, "concurrency": {"qps": qps, "p95": pct(lat,0.95), "errors": len(errors)}},
          open("/tmp/ph-e2e/perf-baseline.json", "w"), ensure_ascii=False, indent=2)
print("\n（AI 咨询的延迟单独看：发布门槛那轮实测平均 3.9s，P95 见报告）")
