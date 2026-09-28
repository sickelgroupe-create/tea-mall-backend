-- Invitation scores use the existing customers, products, rewards and exchange orders.
-- Repeatable additive migration. Historical orders and ordinary points are unchanged.
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS mall_friend_add_column$$
CREATE PROCEDURE mall_friend_add_column(IN t VARCHAR(64), IN c VARCHAR(64), IN d TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=c) THEN
  SET @friend_ddl=CONCAT('ALTER TABLE `',t,'` ADD COLUMN ',d);
  PREPARE friend_stmt FROM @friend_ddl; EXECUTE friend_stmt; DEALLOCATE PREPARE friend_stmt;
 END IF;
END$$
DELIMITER ;
CALL mall_friend_add_column('mall_product','is_trial_gift','is_trial_gift TINYINT NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_customer','invitation_score_net','invitation_score_net DECIMAL(12,2) NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_order_item','is_trial_gift','is_trial_gift TINYINT NOT NULL DEFAULT 0 COMMENT ''创建订单时的礼包资格快照''');
CALL mall_friend_add_column('mall_invite_record','score_amount','score_amount DECIMAL(12,2) NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_invite_record','score_active','score_active TINYINT NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_invite_record','score_revision','score_revision INT NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_reward','invite_cost','invite_cost DECIMAL(12,2) NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_reward','invite_enabled','invite_enabled TINYINT NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_exchange','score_currency','score_currency VARCHAR(16) NOT NULL DEFAULT ''POINTS''');
CALL mall_friend_add_column('mall_exchange','invite_points_cost','invite_points_cost DECIMAL(12,2) NOT NULL DEFAULT 0');
CALL mall_friend_add_column('mall_exchange','review_status','review_status VARCHAR(16) NOT NULL DEFAULT ''NONE''');
CALL mall_friend_add_column('mall_exchange','review_reason','review_reason VARCHAR(500) NOT NULL DEFAULT ''''');
CALL mall_friend_add_column('mall_exchange','reviewed_by','reviewed_by BIGINT DEFAULT NULL');
CALL mall_friend_add_column('mall_exchange','reviewed_at','reviewed_at DATETIME DEFAULT NULL');
DROP PROCEDURE mall_friend_add_column;
CREATE TABLE IF NOT EXISTS mall_friend_config (
 id BIGINT PRIMARY KEY, points_per_friend DECIMAL(8,2) NOT NULL DEFAULT 2.50,
 large_exchange_score DECIMAL(12,2) DEFAULT NULL, daily_exchange_limit INT DEFAULT NULL,
 minimum_purchase_ratio DECIMAL(5,2) NOT NULL DEFAULT 30.00,
 monthly_reward_text VARCHAR(500) NOT NULL DEFAULT '', home_item_limit INT NOT NULL DEFAULT 6,
 description VARCHAR(500) NOT NULL DEFAULT '直属好友注册并购买试喝礼包后，每人获得2.5邀请分；同一好友仅计一次。',
 effective_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, version_no INT NOT NULL DEFAULT 1,
 updated_by BIGINT DEFAULT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO mall_friend_config(id) VALUES(1);
CREATE TABLE IF NOT EXISTS mall_friend_score_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, customer_id BIGINT NOT NULL,
 business_key VARCHAR(100) NOT NULL, amount DECIMAL(12,2) NOT NULL,
 balance_after DECIMAL(12,2) NOT NULL, description VARCHAR(255) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_friend_score_business(customer_id,business_key),
 KEY idx_friend_score_customer(customer_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请分独立流水；负净额表示待补扣，不作为可用余额';
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260905_01','茶友邀请分、礼包资格快照、复用奖品和兑换订单')
ON DUPLICATE KEY UPDATE description=VALUES(description);
