-- Fixed commission snapshots. Historical orders remain at zero; never retroactively award.
DROP PROCEDURE IF EXISTS mall_fixed_commission_columns;
DELIMITER $$
CREATE PROCEDURE mall_fixed_commission_columns()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_product' AND column_name='commission_amount') THEN
  ALTER TABLE mall_product ADD COLUMN commission_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '每单每商品固定提成';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_order_item' AND column_name='commission_amount') THEN
  ALTER TABLE mall_order_item ADD COLUMN commission_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '下单固定提成快照，同商品只计一次';
 END IF;
END$$
DELIMITER ;
CALL mall_fixed_commission_columns();
DROP PROCEDURE mall_fixed_commission_columns;
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260908_02','商品固定直属佣金和下单快照，历史订单不追补') ON DUPLICATE KEY UPDATE description=VALUES(description);
