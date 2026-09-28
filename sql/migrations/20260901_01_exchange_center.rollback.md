# 回滚说明

本迁移优先采用停用而不是删表，避免丢失积分、邀请、兑换和审核证据。

1. 将 `home.exchange-center`、`home.partner-entry`、`home.science-entry`、`home.community-entry`、`home.sales-entry`、`exchange.points`、`exchange.invite` 对应 `mall_page_module.status` 改为 `1`。
2. 将新增的茶叶科普分类和新增商品分类改为下架状态，不删除已有商品关联。
3. 应用回滚到上一版本后，保留 `mall_points_accrual`、`mall_points_debt` 和 `mall_invite_rule_config` 作为审计记录。
4. 仅在确认表为空且完成独立备份后，才可人工删除新增表和新增列；生产环境禁止自动 DROP。
