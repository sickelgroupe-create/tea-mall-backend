DROP PROCEDURE IF EXISTS mall_add_column;
DELIMITER $$
CREATE PROCEDURE mall_add_column(IN table_name_value VARCHAR(64), IN column_name_value VARCHAR(64), IN column_sql TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_name_value AND COLUMN_NAME = column_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN ', column_sql);
    PREPARE statement_value FROM @ddl;
    EXECUTE statement_value;
    DEALLOCATE PREPARE statement_value;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column('mall_order', 'points_used', '`points_used` INT NOT NULL DEFAULT 0 AFTER `points_discount`');
CALL mall_add_column('mall_aftersale', 'source_order_status', '`source_order_status` VARCHAR(24) NOT NULL DEFAULT ''已完成'' AFTER `status`');
DROP PROCEDURE IF EXISTS mall_add_column;

UPDATE mall_aftersale a
JOIN mall_order o ON o.id=a.order_id
SET a.source_order_status=CASE
  WHEN o.status='售后中' THEN '已完成'
  ELSE o.status
END
WHERE a.source_order_status='' OR a.source_order_status IS NULL;

-- 商品主图必须与商品本体一致，避免龙井错误复用木质茶盘图。
UPDATE mall_product SET image_key='longjing-pale'
WHERE id=1 AND image_key IN ('tea-tray','longjing-pale');
