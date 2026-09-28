-- 订单库存预占、金额分摊、发票和催发货增量迁移。
-- 仅新增字段、索引和表；历史订单保持 LEGACY_UNRESERVED，避免错误回补库存。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_column_order_inventory$$
CREATE PROCEDURE mall_add_column_order_inventory(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND COLUMN_NAME=column_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD COLUMN ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DROP PROCEDURE IF EXISTS mall_add_index_order_inventory$$
CREATE PROCEDURE mall_add_index_order_inventory(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND INDEX_NAME=index_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column_order_inventory('mall_order','inventory_state',
  '`inventory_state` VARCHAR(32) NOT NULL DEFAULT ''LEGACY_UNRESERVED'' COMMENT ''LEGACY_UNRESERVED/RESERVED/CONSUMED/RELEASED'' AFTER `payment_status`');
CALL mall_add_column_order_inventory('mall_order','reserved_until',
  '`reserved_until` DATETIME DEFAULT NULL COMMENT ''待付款库存预占截止时间'' AFTER `inventory_state`');
CALL mall_add_column_order_inventory('mall_order','invoice_type',
  '`invoice_type` VARCHAR(24) NOT NULL DEFAULT '''' COMMENT ''发票类型'' AFTER `remark`');
CALL mall_add_column_order_inventory('mall_order','invoice_title',
  '`invoice_title` VARCHAR(128) NOT NULL DEFAULT '''' COMMENT ''发票抬头'' AFTER `invoice_type`');
CALL mall_add_column_order_inventory('mall_order','invoice_tax_no',
  '`invoice_tax_no` VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''纳税人识别号'' AFTER `invoice_title`');
CALL mall_add_column_order_inventory('mall_order','invoice_email',
  '`invoice_email` VARCHAR(128) NOT NULL DEFAULT '''' COMMENT ''接收邮箱'' AFTER `invoice_tax_no`');

CALL mall_add_column_order_inventory('mall_order_item','gross_amount',
  '`gross_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行原价金额'' AFTER `price`');
CALL mall_add_column_order_inventory('mall_order_item','coupon_allocated',
  '`coupon_allocated` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行优惠券分摊'' AFTER `gross_amount`');
CALL mall_add_column_order_inventory('mall_order_item','points_allocated',
  '`points_allocated` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行积分抵扣分摊'' AFTER `coupon_allocated`');
CALL mall_add_column_order_inventory('mall_order_item','shipping_allocated',
  '`shipping_allocated` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行运费分摊'' AFTER `points_allocated`');
CALL mall_add_column_order_inventory('mall_order_item','refundable_amount',
  '`refundable_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行最大可退金额'' AFTER `shipping_allocated`');
CALL mall_add_column_order_inventory('mall_order_item','refunded_amount',
  '`refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''商品行累计退款金额'' AFTER `refundable_amount`');

CALL mall_add_index_order_inventory('mall_order','idx_mall_order_reservation_expiry',
  'INDEX `idx_mall_order_reservation_expiry` (`status`,`inventory_state`,`reserved_until`)');

CREATE TABLE IF NOT EXISTS mall_order_expedite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_no VARCHAR(80) NOT NULL,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待处理',
  customer_remark VARCHAR(255) NOT NULL DEFAULT '',
  admin_remark VARCHAR(255) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_order_expedite_request (customer_id,request_no),
  KEY idx_mall_order_expedite_order_time (order_id,create_time),
  KEY idx_mall_order_expedite_status_time (status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单催发货记录';

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_02','订单库存预占、金额分摊、发票持久化与催发货记录')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_order_inventory;
DROP PROCEDURE IF EXISTS mall_add_index_order_inventory;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_02';
