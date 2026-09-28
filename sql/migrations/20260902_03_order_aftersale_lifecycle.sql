-- 订单取消、逐商品售后、退货/换货物流与状态审计（幂等增量迁移）
-- 不删除、不清空、不覆盖历史业务数据；可连续执行。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_column_lifecycle$$
CREATE PROCEDURE mall_add_column_lifecycle(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
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

DROP PROCEDURE IF EXISTS mall_add_index_lifecycle$$
CREATE PROCEDURE mall_add_index_lifecycle(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
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

CALL mall_add_column_lifecycle('mall_order','cancel_reason',
  '`cancel_reason` VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''取消原因'' AFTER `remark`');
CALL mall_add_column_lifecycle('mall_order','cancel_note',
  '`cancel_note` VARCHAR(500) NOT NULL DEFAULT '''' COMMENT ''取消补充说明'' AFTER `cancel_reason`');
CALL mall_add_column_lifecycle('mall_order','cancel_time',
  '`cancel_time` DATETIME DEFAULT NULL COMMENT ''取消时间'' AFTER `cancel_note`');
CALL mall_add_column_lifecycle('mall_order','aftersale_status',
  '`aftersale_status` VARCHAR(32) NOT NULL DEFAULT '''' COMMENT ''订单售后汇总状态'' AFTER `cancel_time`');
CALL mall_add_column_lifecycle('mall_order','refunded_amount',
  '`refunded_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''累计退款金额'' AFTER `aftersale_status`');

CALL mall_add_column_lifecycle('mall_order_item','refunded_qty',
  '`refunded_qty` INT NOT NULL DEFAULT 0 COMMENT ''已完成退款数量'' AFTER `qty`');
CALL mall_add_column_lifecycle('mall_order_item','aftersale_locked_qty',
  '`aftersale_locked_qty` INT NOT NULL DEFAULT 0 COMMENT ''售后处理中占用数量'' AFTER `refunded_qty`');

CALL mall_add_column_lifecycle('mall_aftersale','request_no',
  '`request_no` VARCHAR(80) DEFAULT NULL COMMENT ''客户端幂等号'' AFTER `aftersale_no`');
CALL mall_add_column_lifecycle('mall_aftersale','order_item_id',
  '`order_item_id` BIGINT DEFAULT NULL COMMENT ''订单商品行'' AFTER `order_id`');
CALL mall_add_column_lifecycle('mall_aftersale','product_id',
  '`product_id` BIGINT DEFAULT NULL AFTER `order_item_id`');
CALL mall_add_column_lifecycle('mall_aftersale','sku_id',
  '`sku_id` BIGINT DEFAULT NULL AFTER `product_id`');
CALL mall_add_column_lifecycle('mall_aftersale','apply_qty',
  '`apply_qty` INT NOT NULL DEFAULT 1 AFTER `sku_id`');
CALL mall_add_column_lifecycle('mall_aftersale','description',
  '`description` VARCHAR(500) NOT NULL DEFAULT '''' AFTER `reason`');
CALL mall_add_column_lifecycle('mall_aftersale','evidence_urls',
  '`evidence_urls` TEXT DEFAULT NULL AFTER `description`');
CALL mall_add_column_lifecycle('mall_aftersale','return_address',
  '`return_address` VARCHAR(512) NOT NULL DEFAULT '''' AFTER `admin_remark`');
CALL mall_add_column_lifecycle('mall_aftersale','return_carrier',
  '`return_carrier` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `return_address`');
CALL mall_add_column_lifecycle('mall_aftersale','return_tracking_no',
  '`return_tracking_no` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `return_carrier`');
CALL mall_add_column_lifecycle('mall_aftersale','return_time',
  '`return_time` DATETIME DEFAULT NULL AFTER `return_tracking_no`');
CALL mall_add_column_lifecycle('mall_aftersale','exchange_carrier',
  '`exchange_carrier` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `return_time`');
CALL mall_add_column_lifecycle('mall_aftersale','exchange_tracking_no',
  '`exchange_tracking_no` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `exchange_carrier`');
CALL mall_add_column_lifecycle('mall_aftersale','exchange_ship_time',
  '`exchange_ship_time` DATETIME DEFAULT NULL AFTER `exchange_tracking_no`');
CALL mall_add_column_lifecycle('mall_aftersale','close_reason',
  '`close_reason` VARCHAR(255) NOT NULL DEFAULT '''' AFTER `exchange_ship_time`');
CALL mall_add_column_lifecycle('mall_aftersale','complete_time',
  '`complete_time` DATETIME DEFAULT NULL AFTER `close_reason`');
CALL mall_add_column_lifecycle('mall_aftersale','inventory_restored',
  '`inventory_restored` TINYINT(1) NOT NULL DEFAULT 0 AFTER `complete_time`');
CALL mall_add_column_lifecycle('mall_aftersale','coupon_returned',
  '`coupon_returned` TINYINT(1) NOT NULL DEFAULT 0 AFTER `inventory_restored`');
CALL mall_add_column_lifecycle('mall_aftersale','points_adjusted',
  '`points_adjusted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `coupon_returned`');

CALL mall_add_index_lifecycle('mall_aftersale','uk_mall_aftersale_customer_request',
  'UNIQUE INDEX `uk_mall_aftersale_customer_request` (`customer_id`,`request_no`)');
CALL mall_add_index_lifecycle('mall_aftersale','idx_mall_aftersale_order_item_status',
  'INDEX `idx_mall_aftersale_order_item_status` (`order_item_id`,`status`)');

CREATE TABLE IF NOT EXISTS mall_order_operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  operator_type VARCHAR(24) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  source_name VARCHAR(32) NOT NULL,
  old_status VARCHAR(32) NOT NULL,
  new_status VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL DEFAULT '',
  request_no VARCHAR(80) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_order_operation_request (order_id,request_no),
  KEY idx_mall_order_operation_order (order_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态操作审计';

CREATE TABLE IF NOT EXISTS mall_aftersale_operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aftersale_id BIGINT NOT NULL,
  aftersale_no VARCHAR(32) NOT NULL,
  operator_type VARCHAR(24) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  source_name VARCHAR(32) NOT NULL,
  old_status VARCHAR(32) NOT NULL,
  new_status VARCHAR(32) NOT NULL,
  remark VARCHAR(500) NOT NULL DEFAULT '',
  request_no VARCHAR(80) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_aftersale_operation_request (aftersale_id,request_no),
  KEY idx_mall_aftersale_operation_sale (aftersale_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后处理时间轴';

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260902_03','订单取消、逐商品售后、退货换货物流与全流程审计')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_lifecycle;
DROP PROCEDURE IF EXISTS mall_add_index_lifecycle;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260902_03';

