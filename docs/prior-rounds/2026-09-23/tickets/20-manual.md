## Parent

#25（地图：C 端（Web + H5）——用户自助侧可演示产品）

## What to build

公司交付物里的**操作手册**补齐（`docs/manual/`；一期已拍后台截图，脚本是 `scripts/capture-manual-shots.sh`）：

- 手册正文：后台（运营端 / 服务者端）按角色分章的图文步骤 + **C 端章节**（注册登录、建档、打卡、看档案、AI 问答、消息、浏览与预约）。
- C 端截图：扩 `capture-manual-shots.sh` 覆盖 C 端页面（手机视口）。
- 体例沿用一期：一步一图，写清「看到什么算成功」。

## Acceptance criteria

- [ ] `docs/manual/` 有完整正文（后台 + C 端）
- [ ] 截图脚本可一次重跑全部截图（含手机视口）
- [ ] 手册写明演示环境的访问方式（局域网或公网，随部署状态）

## Blocked by

- C 端服务浏览与预约 + 我的
