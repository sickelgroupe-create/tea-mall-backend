-- Run with the project's serialized migration executor. Never overwrites a configured phone.
SET NAMES utf8mb4;
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '商城客服电话','mall.support.phone','','Y','migration',NOW(),
       '在商城客服工单页面编辑；面向所有用户公开，不是客户的工单联系方式'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='mall.support.phone');
INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_08','客服工单页配置商城客服电话，初始为空')
ON DUPLICATE KEY UPDATE description=VALUES(description);
