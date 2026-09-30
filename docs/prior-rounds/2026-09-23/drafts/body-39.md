## Parent

#25（地图：C 端（Web + H5）——用户自助侧可演示产品）

## What to build

让文档追上 C 端：

- **`CONTEXT.md`**：补 C 端带来的新术语（**C 端**、**自助注册**、**H5**、消息中心的实体名与口径），并修正被 C 端改变的定义——「用户 User」原文写着「本期不做 C 端，由运营代录」；「每日打卡」的所有权从代录变为自助 + 代录。别把实现细节写进术语表。
- **ADR**：**修订 ADR-0001**（C 端从二期提前到本轮；形态从「小程序」改为「Web + H5」）——补修订记录，不重写原文；如 `/app/**` 模块边界与 `USER` 角色算新决策，新增一份 ADR。
- **`README.md`**：C 端的位置、命令（起 C 端、构建、联调测试）、部署端口、目录表。
- **旧票 #10 结案**：AI 生成内容的合规标识与日志留存在 C 端的落地结论（引用研究票的结论与实现事实，含来源与核实日期），留结论评论并**关闭**。
- **`docs/design/feature-inventory.md`**：补 C 端一栏（已做 / 未做），免得下轮再问一遍。

## Acceptance criteria

- [ ] `CONTEXT.md` 新术语与纠偏到位，未混入实现细节
- [ ] ADR-0001 有修订记录（或新增 ADR），理由与日期齐
- [ ] README 有 C 端章节（命令与端口）
- [ ] #10 留结论并关闭（含来源与核实日期）
- [ ] feature-inventory 有 C 端一栏

## Blocked by

- C 端联调自动测试与冒烟扩展



## 补充（2026-09-23 大厂规划盘问）

- **已完成、从本票移除**：ADR-0001 修订记录、ADR-0011/0012/0013、`CONTEXT.md` 新术语（C 端 / 自助注册 / 登录手机号 / 消息 / 账号注销 / 埋点事件 / 演示档）、#10 结案（已由研究票关闭）。
- **本票剩下**：`README.md` 的 C 端章节（位置、命令、端口、首屏体积）、`docs/design/feature-inventory.md` 补 C 端一栏，以及实现落地后的**最终一致性回看**（实现与文档不符时，回改文档或回改实现并写明）。



## 补充（2026-09-23 由 #38 核对转来）

- `docs/design/demo-data-policy.md` 引用的 `scripts/seed.sh` **不存在**（实际是 `scripts/demo.sh`）——修正引用。
- `docs/design/feature-inventory.md` 的 Q20 写「咨询图片最多 3 张」，而冻结 DDL 是单张（`consultation.media_file_id` 单列）——以 DDL 为准改文档，**不要改 DDL**。
- `scripts/package-delivery.sh`（#47）的用法建议补进 README 命令速查（#47 有意没动 README，避免与 #39/#46 撞车）。
