# 20260902_03 回滚说明

本迁移只新增列、索引和审计表，不修改或清空历史订单、用户、商品、库存与上传文件。

应用回滚时保留新增结构即可，旧版本代码会忽略新增列。若确需清理，必须先导出 `mall_order_operation_log`、`mall_aftersale_operation_log`、`mall_aftersale`、`mall_order_item` 和 `mall_order`，并确认线上没有新版本产生的取消、售后、退款、退货或换货记录。存在任何记录时禁止删除列和审计表。

