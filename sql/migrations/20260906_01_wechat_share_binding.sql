-- Additive only: keep existing invitation scenes, identities and business records.
DELIMITER $$
DROP PROCEDURE IF EXISTS mall_wechat_code_version_column$$
CREATE PROCEDURE mall_wechat_code_version_column()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_invite_scene' AND column_name='mini_code_env_version') THEN
  ALTER TABLE mall_invite_scene ADD COLUMN mini_code_env_version VARCHAR(16) NOT NULL DEFAULT 'release';
 END IF;
END$$
DELIMITER ;
CALL mall_wechat_code_version_column();
DROP PROCEDURE mall_wechat_code_version_column;
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260906_01','当前账号微信绑定与分享鉴权，小程序码版本缓存隔离')
ON DUPLICATE KEY UPDATE description=VALUES(description);
