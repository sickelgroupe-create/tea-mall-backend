-- 微信首次登录选择注册或绑定已有手机号账号的短期凭据。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mall_wechat_login_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_hash CHAR(64) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  open_id VARCHAR(128) NOT NULL,
  union_id VARCHAR(128) NOT NULL DEFAULT '',
  ref_code VARCHAR(64) NOT NULL DEFAULT '',
  consumed TINYINT(1) NOT NULL DEFAULT 0,
  expires_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  consumed_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_wechat_ticket_hash (ticket_hash),
  KEY idx_mall_wechat_ticket_expiry (consumed,expires_time),
  KEY idx_mall_wechat_ticket_identity (app_id,open_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信首次登录短期绑定凭据';

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_03','微信首次登录显式注册或绑定已有账号')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_03';
