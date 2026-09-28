-- 商城账号安全增量迁移：登录来源审计、手机号安全操作日志。
-- 可重复执行；不删除、不覆盖历史业务数据。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_column_account_security$$
CREATE PROCEDURE mall_add_column_account_security(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND COLUMN_NAME=column_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD COLUMN ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DROP PROCEDURE IF EXISTS mall_add_index_account_security$$
CREATE PROCEDURE mall_add_index_account_security(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND INDEX_NAME=index_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column_account_security('mall_login_attempt','ip_hash',
  '`ip_hash` VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''来源IP摘要'' AFTER `client_key`');
CALL mall_add_index_account_security('mall_login_attempt','idx_login_attempt_phone_time',
  'INDEX `idx_login_attempt_phone_time` (`phone_hash`,`success`,`attempt_time`)');
CALL mall_add_index_account_security('mall_login_attempt','idx_login_attempt_ip_time',
  'INDEX `idx_login_attempt_ip_time` (`ip_hash`,`success`,`attempt_time`)');

CREATE TABLE IF NOT EXISTS mall_customer_security_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  old_value_hash CHAR(64) NOT NULL DEFAULT '',
  new_value_hash CHAR(64) NOT NULL DEFAULT '',
  source_name VARCHAR(32) NOT NULL DEFAULT '',
  operator_id BIGINT DEFAULT NULL,
  remark VARCHAR(255) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_customer_security_customer_time (customer_id,create_time),
  KEY idx_customer_security_event_time (event_type,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城账号安全操作日志';

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_01','注册验证码、手机号安全修改与登录来源限流')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_account_security;
DROP PROCEDURE IF EXISTS mall_add_index_account_security;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_01';
