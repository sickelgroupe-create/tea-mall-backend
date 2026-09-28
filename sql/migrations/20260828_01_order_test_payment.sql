-- 茶叶商城：订单待付款与隔离环境测试支付
-- 安全属性：仅增量建表/加列/改默认值，不删除或改写任何已有订单数据。
-- 执行顺序：完成 chaye_mall.sql、chaye_commerce_hardening.sql、
--           chaye_transaction_rules.sql、chaye_business_integrity_v2.sql 后执行。
-- 仅允许先在隔离测试库执行。执行前必须备份，并保存文末基线查询结果。

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS mall_require_column$$
CREATE PROCEDURE mall_require_column(IN table_value VARCHAR(64), IN column_value VARCHAR(64))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_value AND COLUMN_NAME = column_value
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '商城基础结构不完整，请先执行前置 SQL';
  END IF;
END$$

DROP PROCEDURE IF EXISTS mall_add_column_v3$$
CREATE PROCEDURE mall_add_column_v3(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_value AND COLUMN_NAME = column_value
  ) THEN
    SET @mall_ddl = CONCAT('ALTER TABLE `', table_value, '` ADD COLUMN ', ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DROP PROCEDURE IF EXISTS mall_add_index_v3$$
CREATE PROCEDURE mall_add_index_v3(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_value AND INDEX_NAME = index_value
  ) THEN
    SET @mall_ddl = CONCAT('ALTER TABLE `', table_value, '` ADD ', ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DELIMITER ;

CALL mall_require_column('mall_order', 'payment_method');
CALL mall_require_column('mall_order', 'payment_status');
CALL mall_require_column('mall_order', 'points_used');
CALL mall_require_column('mall_points_log', 'business_type');
CALL mall_require_column('mall_points_log', 'business_key');

-- 只修改新记录的默认值；已有行的 payment_method/payment_status 保持原样。
ALTER TABLE mall_order
  MODIFY COLUMN payment_method VARCHAR(32) NOT NULL DEFAULT '未支付',
  MODIFY COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT '待支付';

CALL mall_add_column_v3('mall_order', 'paid_time',
  '`paid_time` DATETIME DEFAULT NULL AFTER `payment_status`');
CALL mall_add_column_v3('mall_order', 'customer_deleted',
  '`customer_deleted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `paid_time`');
CALL mall_add_index_v3('mall_order', 'idx_mall_order_customer_visible',
  'INDEX `idx_mall_order_customer_visible` (`customer_id`,`customer_deleted`,`create_time`)');

CREATE TABLE IF NOT EXISTS mall_payment_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_no VARCHAR(80) NOT NULL,
  order_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  payment_type VARCHAR(16) NOT NULL COMMENT '仅允许 TEST；不表示真实支付',
  status VARCHAR(16) NOT NULL DEFAULT '处理中',
  amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  environment VARCHAR(24) NOT NULL,
  processed_at DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_payment_attempt_request (request_no),
  KEY idx_mall_payment_attempt_order (order_id, status),
  KEY idx_mall_payment_attempt_customer (customer_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隔离环境测试支付幂等审计；不是微信支付记录';

CREATE TABLE IF NOT EXISTS mall_schema_migration (
  version_no VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  applied_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城手工迁移执行记录';

INSERT INTO mall_schema_migration(version_no, description)
VALUES('20260828_01', '订单待付款、测试支付幂等与用户侧软删除')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_require_column;
DROP PROCEDURE IF EXISTS mall_add_column_v3;
DROP PROCEDURE IF EXISTS mall_add_index_v3;

-- 执行后只读核验。必须保存结果，不能据此批量更新历史订单。
SELECT status, payment_status, payment_method, COUNT(*) AS order_count
FROM mall_order
GROUP BY status, payment_status, payment_method
ORDER BY status, payment_status, payment_method;

SELECT version_no, description, applied_time
FROM mall_schema_migration
WHERE version_no='20260828_01';

SELECT TABLE_NAME, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN ('mall_order','mall_order_item','mall_payment_attempt','mall_points_log','mall_commission');
