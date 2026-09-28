# 20260829_06 回滚说明

本迁移只允许先在隔离测试库执行。生产环境回滚前必须完成整库备份，并导出四张新增业务表和新增积分流水字段。

1. 先停用积分任务、阶梯奖励和兑换入口，确认没有进行中的兑换事务。
2. 保留 `mall_points_log` 的 `balance_before`、`balance_after`、`related_business_no`、`remark`，这些字段属于账本审计证据，默认不建议删除。
3. 如必须回滚，按 `mall_tier_reward_claim` → `mall_tier_reward_rule` → `mall_points_task_claim` → `mall_points_task_rule` → `mall_points_checkin` 的逆序处理。
4. 删除菜单前先删除角色菜单关联；菜单 ID 为 5030–5032、5130–5132。
5. `mall_reward` 和 `mall_exchange` 的新增字段为向后兼容字段，可留存；删除前必须确认旧版本代码不依赖。
6. 任何已发放积分只能通过补偿流水冲正，禁止直接改余额或删除历史流水。
