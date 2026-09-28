-- 茶叶商城：确认订单创建幂等、测试订单隔离及精确订单详情
-- 仅增量加列/索引，不删除、不清空、不覆盖已有业务数据。
-- 执行顺序：20260828_01_order_test_payment.sql 之后。

SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_column_checkout$$
CREATE PROCEDURE mall_add_column_checkout(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
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

DROP PROCEDURE IF EXISTS mall_add_index_checkout$$
CREATE PROCEDURE mall_add_index_checkout(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
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

CALL mall_add_column_checkout('mall_order','request_no',
  '`request_no` VARCHAR(80) DEFAULT NULL COMMENT ''客户端创建订单幂等号'' AFTER `order_no`');
CALL mall_add_column_checkout('mall_order','is_test_order',
  '`is_test_order` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''测试账号产生的隔离订单'' AFTER `customer_id`');
CALL mall_add_index_checkout('mall_order','uk_mall_order_customer_request',
  'UNIQUE INDEX `uk_mall_order_customer_request` (`customer_id`,`request_no`)');
CALL mall_add_index_checkout('mall_order','idx_mall_order_test_status',
  'INDEX `idx_mall_order_test_status` (`is_test_order`,`status`,`create_time`)');

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260830_02','确认订单幂等、测试订单隔离与收银台闭环')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_checkout;
DROP PROCEDURE IF EXISTS mall_add_index_checkout;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260830_02';

-- 回滚说明：应用发布后不要直接删列。若必须回滚代码，旧代码会忽略新增列；
-- 保留 request_no/is_test_order 与索引可继续审计历史测试订单。
-- 仅在确认无任何新版本订单后，才可由 DBA 单独评估 DROP INDEX/DROP COLUMN。
