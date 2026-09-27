# 三个 Web 端用 pnpm workspace 组织，共享设计 token 与请求层

`apps/` 下放 C 端、服务者后台、运营后台三个应用，`packages/` 下放共享包。

## Considered Options

- **三个独立工程**：互不干扰，但设计 token、请求封装、鉴权逻辑要写三遍，且会逐渐分叉——而交付文档 4.16 明确要求三端配色完全一致。

## Consequences

- 改一次设计 token，三个端同时生效。这是「三端视觉一致」这条要求的最低成本实现。
- 接口类型由 `contract/` 的 OpenAPI 生成到 `packages/shared`，三个端共用；契约变更一处改，三端一起编译报错。
- 代价：pnpm workspace 的依赖提升与版本冲突需要纪律；共享包改动影响三端，要一起回归。
