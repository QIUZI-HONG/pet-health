## Parent

#25（地图：C 端（Web + H5）——用户自助侧可演示产品）

## What to build

C 端行为统计的地基（ADR-0012）：

- 新表 `app_event`（埋点事件）：`app_user_id`、`event_type`（REGISTER / CHECKIN / AI_CONSULT / APPOINTMENT_CREATE）、`target_id`、`occurred_at`、少量 `detail` JSON。**与审计日志分家**——审计只记 P/O 写操作与代录留痕。
- 四个写入点：注册成功、当日任一项打卡、AI 咨询落库、C 端建单成功。写入与业务同事务（或明确异步策略并写明）。
- 运营端：一个只读统计页（近 7/30 天各类事件次数、注册→打卡→咨询→建单的转化，可派生活跃数与 7 日留存），沿用后台既有页面的写法。

## Acceptance criteria

- [ ] `app_event` 表进 Flyway 迁移，注释写明「与审计分家」
- [ ] 四类事件在对应路径上真实落库（API 测试断言）
- [ ] 统计页展示四类次数与转化，数字与库内一致
- [ ] 不记录敏感内容（`detail` 里不放健康文本与图片地址）
- [ ] 运营端菜单加一项，权限 O

## Blocked by

- C 端账号与鉴权
- C 端宠物与健康线接口
- C 端 AI 问答接口
- C 端消息与服务预约接口
