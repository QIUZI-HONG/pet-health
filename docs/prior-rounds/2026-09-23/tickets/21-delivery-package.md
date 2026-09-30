## Parent

#25（地图：C 端（Web + H5）——用户自助侧可演示产品）

## What to build

公司的交付要求含「软件代码」，而仓库是 private——**做打包，不开仓库**（用户决定，2026-09-23）：

- `scripts/package-delivery.sh`：生成提交包（zip 或 tar.gz）。内容 = 源码 + `docs/`（手册、ADR、设计文档）+ README；排除 `.git`、`node_modules`、`dist`、`target`、本地数据卷；文件名带日期。
- 包内附一页 `DELIVERY.md`：包内容清单、如何跑起来（指向 README 的命令）、四套验证怎么跑、演示账号说明。
- 视公司要求再定是否发 collaborator 邀请（不在本票范围，但脚本产出要让对方**照着就能跑**）。

## Acceptance criteria

- [ ] 一条命令生成提交包，可重复执行且产物干净（无 `.git` / 依赖 / 构建产物）
- [ ] 在干净目录解压后能按 `DELIVERY.md` 起后端与前端（至少编译通过）
- [ ] 包内含手册与设计文档

## Blocked by

- None (can start immediately)
