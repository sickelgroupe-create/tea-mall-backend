-- WeChat identities are kept separate from phone credentials so one customer
-- can later bind both login methods without exposing an AppSecret or faking a phone.
CREATE TABLE IF NOT EXISTS mall_customer_identity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  provider VARCHAR(32) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  open_id VARCHAR(128) NOT NULL,
  union_id VARCHAR(128) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity_provider_app_open (provider, app_id, open_id),
  KEY idx_identity_customer (customer_id),
  KEY idx_identity_union (provider, union_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城第三方登录身份';
