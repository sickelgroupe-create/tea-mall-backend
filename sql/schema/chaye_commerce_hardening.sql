-- Security, settlement and fulfilment migration. Safe to run repeatedly.

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

CALL mall_add_column('mall_order', 'shipping_fee', '`shipping_fee` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `total_amount`');
CALL mall_add_column('mall_order', 'discount_amount', '`discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `shipping_fee`');
CALL mall_add_column('mall_order', 'points_discount', '`points_discount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `discount_amount`');
CALL mall_add_column('mall_order', 'points_used', '`points_used` INT NOT NULL DEFAULT 0 AFTER `points_discount`');
CALL mall_add_column('mall_order', 'paid_amount', '`paid_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `points_used`');
CALL mall_add_column('mall_order', 'payment_method', '`payment_method` VARCHAR(32) NOT NULL DEFAULT ''模拟支付'' AFTER `paid_amount`');
CALL mall_add_column('mall_order', 'payment_status', '`payment_status` VARCHAR(24) NOT NULL DEFAULT ''已支付'' AFTER `payment_method`');
CALL mall_add_column('mall_aftersale', 'requested_amount', '`requested_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `reason`');
CALL mall_add_column('mall_aftersale', 'refund_amount', '`refund_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `requested_amount`');
CALL mall_add_column('mall_aftersale', 'admin_remark', '`admin_remark` VARCHAR(255) DEFAULT '''' AFTER `refund_amount`');
CALL mall_add_column('mall_aftersale', 'refund_no', '`refund_no` VARCHAR(40) DEFAULT '''' AFTER `admin_remark`');
CALL mall_add_column('mall_aftersale', 'refund_time', '`refund_time` DATETIME DEFAULT NULL AFTER `refund_no`');
CALL mall_add_column('mall_aftersale', 'source_order_status', '`source_order_status` VARCHAR(24) NOT NULL DEFAULT ''已完成'' AFTER `status`');
DROP PROCEDURE IF EXISTS mall_add_column;

CREATE TABLE IF NOT EXISTS mall_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  token_hash CHAR(64) NOT NULL,
  customer_id BIGINT NOT NULL,
  authenticated TINYINT(1) NOT NULL DEFAULT 0,
  expires_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_session_token (token_hash),
  KEY idx_mall_session_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城访客与会员会话';

CREATE TABLE IF NOT EXISTS mall_distributor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  parent_customer_id BIGINT DEFAULT NULL,
  invite_code VARCHAR(32) NOT NULL,
  commission_balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  pending_commission DECIMAL(12,2) NOT NULL DEFAULT 0,
  frozen_commission DECIMAL(12,2) NOT NULL DEFAULT 0,
  withdrawn_commission DECIMAL(12,2) NOT NULL DEFAULT 0,
  status CHAR(1) NOT NULL DEFAULT '0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_distributor_customer (customer_id),
  UNIQUE KEY uk_mall_distributor_invite (invite_code),
  KEY idx_mall_distributor_parent (parent_customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分销关系与佣金账户';

CREATE TABLE IF NOT EXISTS mall_commission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  beneficiary_id BIGINT NOT NULL,
  source_customer_id BIGINT NOT NULL,
  level_no TINYINT NOT NULL,
  rate DECIMAL(6,4) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待结算',
  settle_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_commission_order_beneficiary_level (order_id, beneficiary_id, level_no),
  KEY idx_mall_commission_beneficiary (beneficiary_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分销佣金明细';

CREATE TABLE IF NOT EXISTS mall_withdrawal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  withdrawal_no VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  account_type VARCHAR(32) NOT NULL,
  account_no VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待审核',
  admin_remark VARCHAR(255) DEFAULT '',
  review_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_withdrawal_no (withdrawal_no),
  KEY idx_mall_withdrawal_customer (customer_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分销提现申请';

-- 只回填历史已支付订单。后续新增的待付款订单保持 paid_amount=0，重复执行不得制造账实不一致。
UPDATE mall_order
SET paid_amount=total_amount+shipping_fee-discount_amount-points_discount
WHERE paid_amount=0 AND payment_status='已支付';

-- 只转换明确的旧默认文案/素材，不覆盖管理员后续维护的商品信息。
UPDATE mall_product
SET name='明前西湖龙井 100g', short_name='明前龙井 100g', image_key='longjing-pale'
WHERE id=1 AND (name IN ('明前龙井茶 2024新茶','明前龙井茶 250g') OR image_key='tea-tray');
UPDATE mall_product
SET name='雨前西湖龙井 100g', short_name='雨前龙井 100g'
WHERE id=2 AND name IN ('雨前龙井茶 2024新茶','雨前龙井茶 250g');
UPDATE mall_product
SET name='特级西湖龙井 50g', short_name='特级龙井 50g', image_key='longjing-pale'
WHERE id=4 AND name='特级西湖龙井 50g' AND image_key='biluochun';

-- 修复此前错误写入的历史订单快照：名称标注 250g、实际规格却为 100g/50g。
UPDATE mall_order_item SET product_name='明前西湖龙井 100g'
WHERE product_id=1 AND spec LIKE '100g%' AND product_name LIKE '%250g%';
UPDATE mall_order_item SET product_name='雨前西湖龙井 100g'
WHERE product_id=2 AND spec LIKE '100g%' AND product_name LIKE '%250g%';
UPDATE mall_order_item SET product_name='特级西湖龙井 50g'
WHERE product_id=4 AND spec LIKE '50g%' AND product_name LIKE '%250g%';
INSERT IGNORE INTO mall_distributor(customer_id,invite_code,status)
SELECT id, CONCAT('TEA', UPPER(CONV(id,10,36))), '0' FROM mall_customer WHERE status<>'2';

INSERT IGNORE INTO sys_menu VALUES
  (5006, '分销关系', 5000, 6, 'distributor', 'mall/distributor/index', '', '', 1, 0, 'C', '0', '0', 'mall:distribution:list', 'peoples', 'admin', NOW(), '', NULL, '分销上下级与佣金账户'),
  (5007, '佣金提现', 5000, 7, 'withdrawal', 'mall/withdrawal/index', '', '', 1, 0, 'C', '0', '0', 'mall:withdrawal:list', 'money', 'admin', NOW(), '', NULL, '审核佣金提现申请'),
  (5106, '提现审核', 5007, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:withdrawal:edit', '#', 'admin', NOW(), '', NULL, '');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (5000,5001,5002,5003,5004,5005,5006,5007,5101,5102,5103,5104,5105,5106);
