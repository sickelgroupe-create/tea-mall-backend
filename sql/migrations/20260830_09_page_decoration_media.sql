-- 商城素材与页面装修：仅新增表、菜单和默认回退配置，不覆盖现有业务数据。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mall_media_asset(
 id BIGINT NOT NULL AUTO_INCREMENT,
 asset_no VARCHAR(48) NOT NULL,
 name VARCHAR(120) NOT NULL,
 url VARCHAR(500) NOT NULL,
 mime_type VARCHAR(64) NOT NULL,
 extension VARCHAR(12) NOT NULL,
 width INT NOT NULL,
 height INT NOT NULL,
 size_bytes BIGINT NOT NULL,
 purpose VARCHAR(80) NOT NULL DEFAULT '运营图片',
 page_code VARCHAR(64) NOT NULL DEFAULT 'common',
 module_code VARCHAR(64) NOT NULL DEFAULT 'content',
 recommended_size VARCHAR(64) NOT NULL DEFAULT '',
 status CHAR(1) NOT NULL DEFAULT '0',
 sort_no INT NOT NULL DEFAULT 0,
 version_no INT NOT NULL DEFAULT 1,
 created_by BIGINT DEFAULT NULL,
 updated_by BIGINT DEFAULT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_media_asset_no(asset_no),
 KEY idx_media_asset_module(page_code,module_code,status,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城运营图片素材库';

CREATE TABLE IF NOT EXISTS mall_page_module(
 id BIGINT NOT NULL AUTO_INCREMENT,
 module_key VARCHAR(100) NOT NULL,
 page_code VARCHAR(64) NOT NULL,
 module_code VARCHAR(64) NOT NULL,
 english_title VARCHAR(120) NOT NULL DEFAULT '',
 title VARCHAR(160) NOT NULL DEFAULT '',
 subtitle VARCHAR(255) NOT NULL DEFAULT '',
 description VARCHAR(1000) NOT NULL DEFAULT '',
 image_asset_id BIGINT DEFAULT NULL,
 image_url VARCHAR(500) NOT NULL DEFAULT '',
 jump_type VARCHAR(32) NOT NULL DEFAULT 'none',
 jump_target VARCHAR(255) NOT NULL DEFAULT '',
 config_json JSON NOT NULL,
 status CHAR(1) NOT NULL DEFAULT '0',
 sort_no INT NOT NULL DEFAULT 0,
 publish_time DATETIME DEFAULT NULL,
 version_no INT NOT NULL DEFAULT 1,
 created_by BIGINT DEFAULT NULL,
 updated_by BIGINT DEFAULT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_page_module_key(module_key),
 KEY idx_page_module_publish(page_code,status,sort_no,publish_time),
 KEY idx_page_module_asset(image_asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城页面装修模块';

CREATE TABLE IF NOT EXISTS mall_page_module_history(
 id BIGINT NOT NULL AUTO_INCREMENT,
 module_id BIGINT NOT NULL,
 version_no INT NOT NULL,
 snapshot_json JSON NOT NULL,
 operator_id BIGINT DEFAULT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_page_module_history(module_id,version_no),
 KEY idx_page_module_history(module_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城页面装修版本历史';

INSERT IGNORE INTO mall_page_module(module_key,page_code,module_code,english_title,title,subtitle,description,image_url,jump_type,jump_target,config_json,status,sort_no,publish_time,version_no,create_time,update_time) VALUES
('home.collection','01','collection','PRIVATE COLLECTION','御选春藏','核心产区 · 一季一采','每一罐均拥有独立茶档与品鉴记录。','/static/images/longjing-dark-v2.webp','topic','',JSON_OBJECT('metric1Value','36席','metric1Label','稀缺配额','metric2Value','2026春','metric2Label','专属礼遇','metric3Value','收藏级','metric3Label','典藏标准'),'0',90,NOW(),1,NOW(),NOW()),
('auth.hero','45','hero','','登录注册','欢迎回来，续写你的东方茶叙','','/static/images/login-art-v2.webp','none','',JSON_OBJECT(),'0',10,NOW(),1,NOW(),NOW()),
('auth.member','45','member','INVITATION ONLY','臻享会员礼序','','专属茶师 · 稀缺配额 · 私享雅集','/static/images/longjing-dark-v2.webp','none','',JSON_OBJECT('benefit1','专属茶师','benefit2','稀缺配额','benefit3','私享雅集'),'0',80,NOW(),1,NOW(),NOW()),
('invite.club','17-24','club','INVITATION ONLY','臻享茶友礼序','','以真实邀请关系与有效订单为准','/static/images/tea-gift-v2.webp','invite','',JSON_OBJECT(),'0',80,NOW(),1,NOW(),NOW()),
('invite.poster','22-23','poster','CURATED TEA RITUAL','一键邀请','','海报场景参数由服务端生成并校验','/static/images/invite-poster-v2.webp','invite','',JSON_OBJECT(),'0',20,NOW(),1,NOW(),NOW()),
('points.promo','25-32','promo','MEMBER REWARDS','积分臻选','','积分、库存与兑换订单由服务端事务统一处理','/static/images/tea-gift-v2.webp','points','',JSON_OBJECT(),'0',80,NOW(),1,NOW(),NOW()),
('partner.intro','33-40','promo','PARTNER PROGRAM','合伙人礼序','','申请、客户与业绩均以服务端数据为准','/static/images/longjing-dark-v2.webp','partner','',JSON_OBJECT(),'0',80,NOW(),1,NOW(),NOW()),
('account.member','41-48','member','PRIVATE MEMBERSHIP','臻享会员礼序','','专属茶师 · 稀缺配额 · 私享雅集','/static/images/tea-gift-v2.webp','none','',JSON_OBJECT(),'0',80,NOW(),1,NOW(),NOW()),
('support.service','47','support','TEA CONCIERGE','茶事客服','','在线客服不可用时可提交真实工单','/static/images/longjing-dark-v2.webp','support','',JSON_OBJECT(),'0',80,NOW(),1,NOW(),NOW());

INSERT IGNORE INTO sys_menu VALUES
 (5050,'商城素材与装修',5000,24,'decoration','mall/decoration/index','','',1,0,'C','0','0','mall:decoration:list','picture','admin',NOW(),'',NULL,'运营素材、页面模块和版本恢复'),
 (5150,'商城素材查看',5050,1,'#','','','',1,0,'F','0','0','mall:decoration:list','#','admin',NOW(),'',NULL,''),
 (5151,'商城素材上传',5050,2,'#','','','',1,0,'F','0','0','mall:decoration:upload','#','admin',NOW(),'',NULL,''),
 (5152,'页面装修修改',5050,3,'#','','','',1,0,'F','0','0','mall:decoration:edit','#','admin',NOW(),'',NULL,''),
 (5153,'页面版本恢复',5050,4,'#','','','',1,0,'F','0','0','mall:decoration:restore','#','admin',NOW(),'',NULL,'');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 1,menu_id FROM sys_menu WHERE menu_id IN(5050,5150,5151,5152,5153);
