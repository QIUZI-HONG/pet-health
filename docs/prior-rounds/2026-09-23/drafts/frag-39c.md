
## 补充（2026-09-23 由 #50 发现）

- `frontend/src/constants/dict.js` 的前端兜底 `AUDIT_ACTIONS` 缺两个已在用的动作：`TODO_TIMEOUT_SCAN`、`STATEMENT_RECONCILE_MISMATCH`（#50 未动它，避免与并行会话冲突）。审计页的筛选项以接口 `GET /audit-logs/actions` 为准时不受影响，但兜底清单要补齐，否则接口不可用时筛不出这两项。
