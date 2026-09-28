-- 邀请微信小程序码服务端缓存与生成审计。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_add_column_invite_code$$
CREATE PROCEDURE mall_add_column_invite_code(IN column_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mall_invite_scene' AND COLUMN_NAME=column_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `mall_invite_scene` ADD COLUMN ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column_invite_code('mini_code_content',
  '`mini_code_content` MEDIUMBLOB DEFAULT NULL COMMENT ''微信小程序码PNG'' AFTER `use_count`');
CALL mall_add_column_invite_code('mini_code_generated_at',
  '`mini_code_generated_at` DATETIME DEFAULT NULL AFTER `mini_code_content`');
CALL mall_add_column_invite_code('mini_code_generation_count',
  '`mini_code_generation_count` INT NOT NULL DEFAULT 0 AFTER `mini_code_generated_at`');

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_04','邀请微信小程序码服务端生成、缓存与审计')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_invite_code;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_04';
