-- 登录客户端维度限流索引。增量、幂等，不覆盖历史记录。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_index_login_client_limit$$
CREATE PROCEDURE mall_add_index_login_client_limit(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
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

CALL mall_add_index_login_client_limit('mall_login_attempt','idx_login_attempt_client_time',
  'INDEX `idx_login_attempt_client_time` (`client_key`,`success`,`attempt_time`)');

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_07','登录失败客户端维度限流索引')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_index_login_client_limit;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_07';
