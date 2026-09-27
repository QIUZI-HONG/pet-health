# 认证用 JWT Access + Redis Refresh，三个登录域互不通用

Access Token 是 **JWT（HS256，2 小时）**，Refresh Token 是 **不透明随机串存 Redis（7 天，可吊销、刷新即轮换）**。三个端各自一个登录域，Token 里带 `domain`，**跨端不通用**。

交付文档 6.3 已定「JWT（Access Token 2h + Refresh Token 7d），Refresh Token 存 Redis 可吊销」，本 ADR 把没定的部分补齐：登录域、Token 载荷、注销语义、以及在**没有短信通道**的前提下 C 端怎么登录。对应地图上的 [#81](https://github.com/QIUZI-HONG/pet-health/issues/81)。

## Considered Options

- **只用不透明 Token（全查 Redis）**：吊销最彻底，但每个请求都要打一次 Redis，且无法在网关/边缘无状态校验；[ADR-0003](0003-lean-middleware.md) 已经去掉了网关，校验只能在后端做。JWT + Redis refresh 的组合更省。
- **只用 JWT（连 Refresh 也是自包含）**：零状态，但**无法吊销**——退出登录之后旧 Token 还能用到过期，文档明确要求可吊销，排除。
- **Access Token 也进 Redis 黑名单**：能做到「退出即失效」，但每次请求多一次 Redis 往返，收益只是把 2 小时的窗口缩到 0；首期不做，写进下面的代价里。

## Consequences

**三个登录域，三个独立登录入口。** `domain` 取 `app` / `provider` / `admin`，签发时写死，校验时比对当前接口所属前缀（`/api/v1/app/**` 只认 `app`）。这是 [CONTEXT.md](../../CONTEXT.md) 钉死的「服务者后台与运营后台是两个独立登录域」在代码上的落点，也顺手堵掉「C 端 Token 拿去调运营后台」这类越权。

**Token 不进 Cookie。** 交付文档 6.3 说「JWT 不依赖 Cookie，天然免疫 CSRF」，那就保持这条：后端只认 `Authorization: Bearer <access_token>`，Refresh Token 走请求体。前端把它存哪里由 [#67](https://github.com/QIUZI-HONG/pet-health/issues/67) 定，后端不预设。

**口令用 bcrypt（cost 10）。** 交付文档只给了「管理员密码 bcrypt」，C 端原本是「无密码体系（验证码登录）」。**但本项目现在没有短信通道**——短信服务商既没有决策（[#120](https://github.com/QIUZI-HONG/pet-health/issues/120) 关合规、[#81](https://github.com/QIUZI-HONG/pet-health/issues/81) 关限流），也没法在没有密钥的前提下测。用户故事 1 明确写了「手机号**或账号密码**注册登录」，所以首期用手机号 + 密码，验证码登录等短信通道落地后作为**并列**的登录方式补上，不推翻它。

**退出登录的语义说清楚：** 吊销 Refresh Token（Redis 删除）；**已签发的 Access Token 在剩余有效期（≤2 小时）内仍然有效**。这是不引入黑名单的必然代价，写在这里免得日后被当成 bug。要做「立即失效」时，加一张 Redis 的 jti 黑名单即可，接口不用改。

**Refresh 轮换与泄露检测：** 每次刷新都作废旧 Refresh、签发新的（一次性使用）。被作废的 Refresh 再次出现，说明它泄露了——**整族吊销**（这次登录签发过的所有 Refresh 一起作废），用户重新登录。

实现方式：Redis 里给用过的令牌留一个 `used:` 标记（TTL 与 Refresh 同长），并用一个 `family:` 集合记住这次登录签发过的全部令牌。只有留下「用过」的痕迹，重放才与「压根没签发过」区分得开——`GETDEL` 取走就没了，不留标记的话两者看起来一模一样。

**代价要认：同一会话在两个标签页里同时刷新，可能把用户踢下线。** 无法分辨「重放的攻击者」和「合法的并发客户端」，所以只能整个作废。前端因此把刷新做成**单飞**（并发 40101 只换一次令牌，见 `packages/shared/src/http/client.ts`）。真出现误伤，再考虑给一个短暂的宽限窗口。

**401 的两个码要区分**（前端行为不同，见 `contract/common.yaml`）：`40100` 未登录 / Token 无效 → 跳登录；`40101` Token 已过期 → 静默拿 Refresh 换新的，失败再跳登录。

**密码与令牌纪律：** 口令只存 bcrypt 哈希，永不返回；JWT 密钥（`JWT_SECRET`）只进本地 `.env`，不进库、不进 git、不贴进对话。
