## Parent

#25（地图：C 端（Web + H5）——用户自助侧可演示产品）

## What to build

演示环境的运维底线（ADR-0012）：

- **每日备份**：`scripts/backup-db.sh`（`mysqldump` 到宿主机目录，保留 7 天，带日期命名与日志）；云主机上用 cron / 计划任务每日执行；恢复步骤写进部署手册并**实际演练一次**。
- **日志落盘**：后端日志改为写文件卷（现在只有 stdout，容器重建即丢），保留策略写清。
- **探活**：`/api/v1/system/health` 由宿主机定时打点（cron + curl），失败写本地日志文件（不做告警平台）。

## Acceptance criteria

- [ ] `scripts/backup-db.sh` 可重复执行、产出带日期的 dump、自动清理 7 天前的
- [ ] 用一份 dump 真恢复过（手册里记下命令与耗时）
- [ ] 后端日志在容器重建后仍留存（卷）
- [ ] 探活失败时留下可查的记录

## Blocked by

- None (can start immediately)
