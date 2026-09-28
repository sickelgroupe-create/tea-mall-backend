-- Additive defaults preserve existing visuals; replay never overwrites merchant text.
DELIMITER $$
DROP PROCEDURE IF EXISTS mall_invite_copy_columns$$
CREATE PROCEDURE mall_invite_copy_columns()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_friend_config' AND column_name='invite_card_title') THEN
  ALTER TABLE mall_friend_config ADD COLUMN invite_card_title VARCHAR(60) NOT NULL DEFAULT '茶友注册并购买试喝礼包';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_friend_config' AND column_name='reward_prefix') THEN
  ALTER TABLE mall_friend_config ADD COLUMN reward_prefix VARCHAR(20) NOT NULL DEFAULT '每位奖励';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_friend_config' AND column_name='score_unit_label') THEN
  ALTER TABLE mall_friend_config ADD COLUMN score_unit_label VARCHAR(12) NOT NULL DEFAULT '邀请分';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mall_friend_config' AND column_name='invite_button_text') THEN
  ALTER TABLE mall_friend_config ADD COLUMN invite_button_text VARCHAR(12) NOT NULL DEFAULT '一键邀请';
 END IF;
END$$
DELIMITER ;
CALL mall_invite_copy_columns();
DROP PROCEDURE mall_invite_copy_columns;
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260908_01','邀请卡片标题、奖励前缀、积分显示名称及按钮文案后台配置')
ON DUPLICATE KEY UPDATE description=VALUES(description);
