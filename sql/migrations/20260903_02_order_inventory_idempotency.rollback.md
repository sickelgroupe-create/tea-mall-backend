# 20260903_02 回滚说明

该迁移只新增字段、索引和 `mall_order_expedite` 表。应用代码回滚时可先保留这些兼容字段和表，不影响旧版本读取。

如必须做结构回滚，应先停止写流量并备份数据库，再确认没有 `inventory_state='RESERVED'` 的订单；先将这类订单按订单项数量恢复 SKU 与商品库存，之后删除 `idx_mall_order_reservation_expiry`、新增订单及订单项字段，最后删除 `mall_order_expedite` 和 `mall_schema_migration` 中的 `20260903_02` 记录。

生产环境不提供自动删除脚本，防止误删催单、发票、分摊和库存预占审计数据。
