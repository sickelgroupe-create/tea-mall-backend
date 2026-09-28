# 20260903_05 回滚说明

1. 先停止写流量并备份 `mall_product`、`mall_partner_application`、`mall_service_ticket_message`。
2. 应用回滚到不读取 `category_id` 和工单消息表的版本后，可删除索引 `idx_mall_product_category_id`，再删除 `mall_product.category_id`；原 `category` 文本仍保留，商品数据不会丢失。
3. 可删除 `uk_partner_application_active_customer` 后删除生成列 `active_customer_id`；历史申请不删除。
4. `mall_service_ticket_message` 是审计记录，默认保留。仅在确认不再需要时间轴且已归档后才可删除该表。
5. 从 `mall_schema_migration` 删除 `20260903_05` 仅用于完整回滚记录，不得在应用仍运行新版本时执行。
