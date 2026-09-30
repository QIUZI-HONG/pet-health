# 接口契约

**这里是接口的唯一源头。** 前端的 TS 类型由这里**生成**（`pnpm --filter @pet-health/shared gen:api`），前端不手写接口类型；后端 DTO **手写**，但必须与这里对齐（见 [ADR-0005](../docs/adr/0005-single-repo.md)、[ADR-0047](../docs/adr/0047-contract-ownership-and-parallel-work.md)）。

## 规则

- 先改 YAML，再改代码。改完跑一遍 `pnpm --filter @pet-health/shared gen:api` 重新生成前端类型；**CI 会校验生成物与契约是否同步**，不同步则构建失败。所以**契约与生成物必须同一次提交**。
- 公共组件（统一响应、错误码、分页参数、幂等键）**只在 `common.yaml` 里定义一次**，各端用 `$ref` 引用，不复制。
- 按消费方分文件，路由前缀与之一一对应：

| 文件 | 前缀 | 消费方 | 路径数 | 状态 |
| --- | --- | --- | --- | --- |
| `common.yaml` | — | 全部 | — | ✅ 统一响应 / 错误码 / 分页 / 幂等键 |
| `app.yaml` | `/api/v1/app/**` | C 端 | 67 | ✅ 全部有实现 |
| `provider.yaml` | `/api/v1/provider/**` | 服务者后台 | 31 | ✅ 全部有实现 |
| `admin.yaml` | `/api/v1/admin/**` | 运营后台 | 72 | ✅ 全部有实现 |
| `open.yaml` | `/api/v1/open/**` | 签名直传与读图 | 2 | ✅ 全部有实现 |

四个域**合计 172 个路径，全部有实现**——`server/ph-boot` 的 `ContractJsonTest` 会比对契约与实现的路径与字段。

> 这份文件此前停留在「provider / admin 还是空的」那个阶段（2026-09-28），实际早已补齐。数目现在对得上了；**以后改了契约记得回来改这张表**。

## 登录域与契约文件不是一回事

契约按**消费方**分文件，登录域按**路径前缀**判定（`LoginDomain`，ADR-0012）。两者一一对应，但不是同一个机制：

- `app.yaml` / `provider.yaml` / `admin.yaml` 各对应一个登录域，令牌**不跨域通用**；
- `open.yaml` 是**唯一不需要令牌**的一族——文件直传与读图走签名地址，签名本身就是凭证（ADR-0020）。

## 改契约的常见坑

- **命名跟 `CONTEXT.md`**：禁用词（商家 / 商户 / 店铺 / merchant）在契约里也不许出现，`TerminologyGuardTest` 会拦。
- **金额不用 JSON number**：语义是 `decimal(10,2)`，浮点承载不了金额（`docs/conventions.md`）。
- **运营可调项还没有 admin 路径**：提醒规则与照护阈值两张表在库里、改数据即生效，但契约里没有对应的 admin 接口，所以运营后台没有页面。要做得先补契约与后端接口，见 `ARCHITECTURE.md` 第 10 节。
