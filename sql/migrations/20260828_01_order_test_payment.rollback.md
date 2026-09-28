# `20260828_01` 回滚说明

该迁移不修改已有订单行，也不自动提供一键破坏性回滚。回滚必须先在隔离测试库演练，并由操作者确认目标库名称不是生产库。

## 首选回滚方式

1. 设置 `MALL_TEST_PAYMENT_ENABLED=false` 并停止测试环境商城服务。
2. 导出隔离测试库中 `mall_order`、`mall_order_item`、`mall_payment_attempt`、`mall_points_log`、`mall_commission` 的结构和数据。
3. 核对 `mall_payment_attempt` 是否存在记录，以及迁移后是否创建过新订单。
4. 把应用代码恢复到基线包 `C:\Users\Administrator\Desktop\茶叶-phase1-baseline-20260828-01.tar.gz` 的独立解压副本进行构建验证；不要直接覆盖当前目录。
5. 如需继续保留迁移后产生的订单，保留新增列和支付审计表，只回滚应用代码。这是数据风险最低的方式。

## 仅在确认没有需要保留的迁移后数据时

以下是人工操作范围，不应复制成无人值守脚本：

- 只有 `mall_payment_attempt` 行数为 0 时，才可考虑删除该表。
- 只有所有 `mall_order.customer_deleted=0` 且 `paid_time IS NULL` 时，才可考虑删除这两个新增列。
- 删除 `idx_mall_order_customer_visible` 前先通过 `information_schema.STATISTICS` 确认索引存在。
- 把 `payment_method/payment_status` 默认值恢复成历史值只会影响以后插入的数据，不会恢复任何已有订单；应根据实际回滚版本的 Java INSERT 语句决定。
- 最后才删除 `mall_schema_migration` 中 `version_no='20260828_01'` 的记录。

任何不满足上述条件的情况都应从执行前的隔离测试库备份恢复，禁止对生产库尝试回滚。
