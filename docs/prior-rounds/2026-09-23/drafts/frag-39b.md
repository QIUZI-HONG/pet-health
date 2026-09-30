
## 补充（2026-09-23 由 #38 核对转来）

- `docs/design/demo-data-policy.md` 引用的 `scripts/seed.sh` **不存在**（实际是 `scripts/demo.sh`）——修正引用。
- `docs/design/feature-inventory.md` 的 Q20 写「咨询图片最多 3 张」，而冻结 DDL 是单张（`consultation.media_file_id` 单列）——以 DDL 为准改文档，**不要改 DDL**。
- `scripts/package-delivery.sh`（#47）的用法建议补进 README 命令速查（#47 有意没动 README，避免与 #39/#46 撞车）。
