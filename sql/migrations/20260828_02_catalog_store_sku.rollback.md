# `20260828_02` 回滚说明

该迁移把已有商品映射为默认 SKU，并把购物车唯一维度从“会员 + 商品”升级为“会员 + SKU”。为避免破坏迁移后产生的多规格购物车和订单，不提供自动删除表或列的一键回滚脚本。

## 首选回滚方式

1. 停止隔离测试环境商城服务，确认目标数据库名称不是生产库。
2. 导出 `mall_product`、`mall_product_sku`、`mall_cart`、`mall_order_item`、`mall_store`、`mall_store_favorite`、`mall_topic`、`mall_topic_product` 和 `mall_schema_migration`。
3. 检查迁移后是否新增了多规格 SKU、购物车、订单或店铺关注；只要存在，就保留新结构并仅回滚应用代码。
4. 需要完整回退时，优先恢复执行迁移前的隔离测试库备份，而不是在现有库上逆向删除结构。

## 仅在确认没有任何迁移后业务数据时

- `mall_store_favorite`、`mall_topic_product` 没有新增业务关系后，才可考虑移除新增表。
- 每个商品只有迁移生成的一条默认 SKU，且没有迁移后订单时，才可恢复购物车旧唯一索引。
- 恢复旧索引前必须确认同一会员对同一商品最多只有一条购物车记录，否则会失败或造成数据取舍。
- `mall_order_item.sku_id` 或 `sku_code` 已出现非空新订单快照时，不得删除对应列。
- `mall_product.store_id` 已被用于多店铺数据时，不得删除该列。
- 最后才可删除 `mall_schema_migration` 中 `version_no='20260828_02'` 的记录。

上述任一条件无法确认时，只能从迁移前备份恢复。禁止在生产库执行试探性回滚。
