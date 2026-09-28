-- 售后等待退货超时关闭，释放被锁定的可售后数量。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_aftersale_timeout_column$$
CREATE PROCEDURE mall_add_aftersale_timeout_column()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mall_aftersale' AND COLUMN_NAME='action_deadline') THEN
    ALTER TABLE mall_aftersale ADD COLUMN action_deadline DATETIME DEFAULT NULL AFTER status;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mall_aftersale' AND INDEX_NAME='idx_aftersale_deadline') THEN
    ALTER TABLE mall_aftersale ADD INDEX idx_aftersale_deadline(status,action_deadline,id);
  END IF;
END$$
DELIMITER ;

CALL mall_add_aftersale_timeout_column();
DROP PROCEDURE IF EXISTS mall_add_aftersale_timeout_column;

UPDATE mall_aftersale
SET action_deadline=DATE_ADD(COALESCE(update_time,create_time),INTERVAL 7 DAY)
WHERE status='等待用户退货' AND action_deadline IS NULL;

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_06','售后等待退货超时关闭与数量释放')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_06';
