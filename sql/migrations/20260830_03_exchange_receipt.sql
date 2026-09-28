-- 积分兑换：用户确认收货时间。
-- 只增加可空字段，不删除、不清空、不改写已有兑换数据。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_exchange_receipt_column$$
CREATE PROCEDURE mall_add_exchange_receipt_column()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mall_exchange' AND COLUMN_NAME='received_time'
  ) THEN
    ALTER TABLE mall_exchange
      ADD COLUMN received_time DATETIME DEFAULT NULL COMMENT '用户确认收货时间' AFTER tracking_no;
  END IF;
END$$
DELIMITER ;

CALL mall_add_exchange_receipt_column();
DROP PROCEDURE IF EXISTS mall_add_exchange_receipt_column;

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260830_03','积分兑换用户确认收货')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260830_03';

-- 回滚说明：旧版代码会忽略 received_time。
-- 应用回滚时默认保留该字段，避免丢失已记录的收货时间。
