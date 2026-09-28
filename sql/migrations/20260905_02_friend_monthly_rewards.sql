-- Additive monthly reward workflow; no configured amounts or production awards.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_period_lock(id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO mall_friend_period_lock VALUES(1);
CREATE TABLE IF NOT EXISTS mall_friend_period (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, request_key VARCHAR(64) NOT NULL,
 title VARCHAR(80) NOT NULL, starts_at DATETIME NOT NULL, ends_at DATETIME NOT NULL,
 payout_method VARCHAR(24) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 version_no INT NOT NULL DEFAULT 1, created_by BIGINT NOT NULL, settled_by BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, settled_at DATETIME NULL,
 UNIQUE KEY uk_friend_period_request(request_key), KEY idx_friend_period_dates(status,starts_at,ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_period_prize (
 period_id BIGINT NOT NULL, rank_no INT NOT NULL, amount DECIMAL(12,2) NOT NULL,
 PRIMARY KEY(period_id,rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_period_member (
 period_id BIGINT NOT NULL, invite_record_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 PRIMARY KEY(period_id,invite_record_id), KEY idx_friend_period_member(period_id,customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_period_rank (
 period_id BIGINT NOT NULL, customer_id BIGINT NOT NULL, rank_no INT NOT NULL, qualified_count INT NOT NULL,
 PRIMARY KEY(period_id,customer_id), UNIQUE KEY uk_friend_period_rank(period_id,rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_monthly_award (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, period_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 rank_no INT NOT NULL, qualified_count INT NOT NULL, amount DECIMAL(12,2) NOT NULL,
 payout_method VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 payout_reference VARCHAR(120) NOT NULL DEFAULT '', reason VARCHAR(500) NOT NULL DEFAULT '',
 paid_by BIGINT NULL, paid_at DATETIME NULL, received_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_friend_monthly_customer(period_id,customer_id),
 UNIQUE KEY uk_friend_monthly_rank(period_id,rank_no), KEY idx_friend_monthly_owner(customer_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mall_friend_monthly_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, period_id BIGINT NOT NULL, award_id BIGINT NULL,
 actor_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL, action VARCHAR(24) NOT NULL,
 old_status VARCHAR(24) NOT NULL, new_status VARCHAR(24) NOT NULL, reason VARCHAR(500) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_friend_monthly_event(period_id,id), KEY idx_friend_award_event(award_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Retain the first qualifying date through refunds/requalification, preventing
-- the same friend being counted again in a later reward period.
DELIMITER $$
DROP PROCEDURE IF EXISTS mall_friend_monthly_column$$
CREATE PROCEDURE mall_friend_monthly_column()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_invite_record' AND column_name='first_qualified_at') THEN
  ALTER TABLE mall_invite_record ADD COLUMN first_qualified_at DATETIME NULL,
   ADD KEY idx_friend_first_qualified(first_qualified_at,score_active,inviter_customer_id);
  UPDATE mall_invite_record SET first_qualified_at=completed_time WHERE completed_time IS NOT NULL;
 END IF;
END$$
DELIMITER ;
CALL mall_friend_monthly_column();
DROP PROCEDURE mall_friend_monthly_column;
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260905_02','茶友周期月榜、奖励结算、测试发放与线下收款确认')
ON DUPLICATE KEY UPDATE description=VALUES(description);
