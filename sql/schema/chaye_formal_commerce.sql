-- Formal commerce migration: real member profiles, media, fulfilment, reviews and service tickets.
-- Idempotent and safe to apply repeatedly.

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

CALL mall_add_column('mall_customer', 'avatar_url', '`avatar_url` VARCHAR(512) NOT NULL DEFAULT '''' AFTER `phone`');
CALL mall_add_column('mall_customer', 'password_hash', '`password_hash` VARCHAR(100) NOT NULL DEFAULT '''' AFTER `avatar_url`');
CALL mall_add_column('mall_product', 'gallery_images', '`gallery_images` TEXT NULL AFTER `image_key`');
CALL mall_add_column('mall_exchange', 'receiver_name', '`receiver_name` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `address_id`');
CALL mall_add_column('mall_exchange', 'receiver_phone', '`receiver_phone` VARCHAR(32) NOT NULL DEFAULT '''' AFTER `receiver_name`');
CALL mall_add_column('mall_exchange', 'receiver_address', '`receiver_address` VARCHAR(512) NOT NULL DEFAULT '''' AFTER `receiver_phone`');
CALL mall_add_column('mall_exchange', 'carrier', '`carrier` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `receiver_address`');
CALL mall_add_column('mall_exchange', 'tracking_no', '`tracking_no` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `carrier`');
DROP PROCEDURE IF EXISTS mall_add_column;

ALTER TABLE mall_product MODIFY COLUMN image_key VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE mall_reward MODIFY COLUMN image_key VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE mall_order_item MODIFY COLUMN image_key VARCHAR(512) NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS mall_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  rating TINYINT NOT NULL DEFAULT 5,
  content VARCHAR(500) NOT NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_review_order_item (order_item_id),
  KEY idx_mall_review_product (product_id, status, create_time),
  KEY idx_mall_review_customer (customer_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城商品评价';

-- 将内置种子商品的旧奖励值迁移为 1 元 = 10 积分；不覆盖后台后来配置的奖励积分。
UPDATE mall_product SET reward_points=ROUND(price * 10)
WHERE (id=1 AND price=268 AND reward_points=268)
   OR (id=2 AND price=168 AND reward_points=168)
   OR (id=3 AND price=398 AND reward_points=398)
   OR (id=4 AND price=188 AND reward_points=188)
   OR (id=5 AND price=128 AND reward_points=138)
   OR (id=6 AND price=198 AND reward_points=198)
   OR (id=7 AND price=198 AND reward_points=198)
   OR (id=8 AND price=398 AND reward_points=398);

-- 正式版不再保留免登录测试客户所创建的演示地址。
DELETE a FROM mall_address a
JOIN mall_customer c ON c.id=a.customer_id
WHERE c.status='3' AND (a.detail_address LIKE '%演示地址%' OR a.receiver_name='测试收货人');

INSERT IGNORE INTO sys_menu VALUES
  (5008, '客服工单', 5000, 8, 'ticket', 'mall/ticket/index', '', '', 1, 0, 'C', '0', '0', 'mall:ticket:list', 'message', 'admin', NOW(), '', NULL, '处理商城在线客服工单'),
  (5009, '积分兑换履约', 5000, 9, 'exchange', 'mall/exchange/index', '', '', 1, 0, 'C', '0', '0', 'mall:exchange:list', 'shopping', 'admin', NOW(), '', NULL, '处理积分礼品发货'),
  (5010, '商品评价', 5000, 10, 'review', 'mall/review/index', '', '', 1, 0, 'C', '0', '0', 'mall:review:list', 'star', 'admin', NOW(), '', NULL, '查看商城真实购买评价'),
  (5107, '工单处理', 5008, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:ticket:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5108, '兑换发货', 5009, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:exchange:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5109, '商品删除', 5001, 2, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:product:remove', '#', 'admin', NOW(), '', NULL, '');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (5008,5009,5010,5107,5108,5109);
