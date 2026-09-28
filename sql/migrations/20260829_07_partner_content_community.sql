-- 33-40 合伙人、客户业绩、内容文章与社区闭环。仅允许先在隔离测试库执行。
-- 所有结构、种子和菜单均可重复执行，不覆盖历史申请、审核、归属、文章或用户内容。

DROP PROCEDURE IF EXISTS mall_phase7_add_column;
DELIMITER $$
CREATE PROCEDURE mall_phase7_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=p_table AND COLUMN_NAME=p_column
  ) THEN
    SET @ddl=CONCAT('ALTER TABLE `',p_table,'` ADD COLUMN ',p_ddl);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL mall_phase7_add_column('mall_distributor','partner_status','`partner_status` VARCHAR(24) NOT NULL DEFAULT ''未申请'' AFTER `status`');
CALL mall_phase7_add_column('mall_distributor','partner_approved_time','`partner_approved_time` DATETIME NULL AFTER `partner_status`');

CREATE TABLE IF NOT EXISTS mall_partner_agreement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  version VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  effective_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_partner_agreement_version(version),
  KEY idx_partner_agreement_status(status,effective_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合伙人服务协议';

CREATE TABLE IF NOT EXISTS mall_partner_application (
  id BIGINT NOT NULL AUTO_INCREMENT,
  application_no VARCHAR(40) NOT NULL,
  customer_id BIGINT NOT NULL,
  real_name VARCHAR(64) NOT NULL,
  id_no VARCHAR(32) NOT NULL,
  region VARCHAR(128) NOT NULL,
  address VARCHAR(255) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  agreement_id BIGINT NOT NULL,
  agreement_version VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待审核',
  reject_reason VARCHAR(500) NOT NULL DEFAULT '',
  submit_count INT NOT NULL DEFAULT 1,
  submitted_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_time DATETIME NULL,
  cancelled_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_partner_application_no(application_no),
  KEY idx_partner_application_customer(customer_id,create_time),
  KEY idx_partner_application_status(status,submitted_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合伙人申请';

CREATE TABLE IF NOT EXISTS mall_partner_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  application_id BIGINT NOT NULL,
  from_status VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  operator_type VARCHAR(16) NOT NULL,
  operator_id BIGINT NULL,
  reason VARCHAR(500) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_partner_audit_application(application_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合伙人申请状态审计';

CREATE TABLE IF NOT EXISTS mall_customer_owner_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  old_owner_id BIGINT NULL,
  new_owner_id BIGINT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_owner_log_customer(customer_id,create_time),
  KEY idx_owner_log_owner(new_owner_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户归属变更记录';

CREATE TABLE IF NOT EXISTS mall_content_category (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category_code VARCHAR(32) NOT NULL,
  category_name VARCHAR(64) NOT NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_content_category_code(category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城内容分类';

CREATE TABLE IF NOT EXISTS mall_content_article (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category_id BIGINT NOT NULL,
  slug VARCHAR(80) NOT NULL,
  title VARCHAR(128) NOT NULL,
  summary VARCHAR(500) NOT NULL DEFAULT '',
  cover_image_key VARCHAR(512) NOT NULL DEFAULT '',
  body_html MEDIUMTEXT NOT NULL,
  status CHAR(1) NOT NULL DEFAULT '1',
  published_at DATETIME NULL,
  sort_no INT NOT NULL DEFAULT 0,
  favorite_count INT NOT NULL DEFAULT 0,
  view_count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_content_article_slug(slug),
  KEY idx_content_article_publish(status,published_at,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='茶叶销售内容文章';

CREATE TABLE IF NOT EXISTS mall_content_article_product (
  article_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(article_id,product_id),
  KEY idx_article_product_product(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章推荐商品关系';

CREATE TABLE IF NOT EXISTS mall_content_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  article_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_content_favorite(article_id,customer_id),
  KEY idx_content_favorite_customer(customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章收藏';

CREATE TABLE IF NOT EXISTS mall_content_view (
  id BIGINT NOT NULL AUTO_INCREMENT,
  article_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  view_date DATE NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_content_view_day(article_id,customer_id,view_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章每日有效浏览';

CREATE TABLE IF NOT EXISTS mall_community_post (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '正常',
  visibility VARCHAR(16) NOT NULL DEFAULT '公开',
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_community_post_status(status,create_time),
  KEY idx_community_post_customer(customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='茶友社区动态';

CREATE TABLE IF NOT EXISTS mall_community_post_image (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  image_url VARCHAR(512) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_community_image_post(post_id,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区动态图片';

CREATE TABLE IF NOT EXISTS mall_community_like (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_community_like(post_id,customer_id),
  KEY idx_community_like_customer(customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区动态点赞';

CREATE TABLE IF NOT EXISTS mall_community_comment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '正常',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_community_comment_post(post_id,status,create_time),
  KEY idx_community_comment_customer(customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区评论';

CREATE TABLE IF NOT EXISTS mall_community_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NULL,
  comment_id BIGINT NULL,
  reporter_customer_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待处理',
  admin_remark VARCHAR(500) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_community_report_status(status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区举报审核';

CREATE TABLE IF NOT EXISTS mall_content_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT NOT NULL,
  from_status VARCHAR(24) NOT NULL DEFAULT '',
  to_status VARCHAR(24) NOT NULL DEFAULT '',
  operator_id BIGINT NOT NULL,
  remark VARCHAR(500) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_content_audit_target(target_type,target_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章与社区后台操作记录';

INSERT INTO mall_partner_agreement(title,version,content,status,effective_time)
SELECT '茶席合伙人服务协议','2026.1','一、申请人应保证资料真实、完整并遵守平台规则。\n二、客户归属、订单业绩、佣金与结算均以平台服务端记录为准。\n三、不得虚假宣传、泄露客户信息或绕过平台交易。\n四、平台有权对违规行为暂停或取消合伙人资格，并保留完整审核记录。\n五、本协议不构成固定收益承诺，具体权益以后台启用的规则为准。','0',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mall_partner_agreement WHERE version='2026.1');

INSERT INTO mall_content_category(category_code,category_name,status,sort_no)
SELECT 'TEA_SALES','茶叶销售','0',10
WHERE NOT EXISTS(SELECT 1 FROM mall_content_category WHERE category_code='TEA_SALES');

INSERT INTO mall_content_article(category_id,slug,title,summary,cover_image_key,body_html,status,published_at,sort_no)
SELECT c.id,'yesterday-tea-visit','昨日饮茶雅访','记录一片茶叶从茶园、工艺到茶席的清香旅程。','longjing-hero-v2',
       '<h2>昨日饮茶雅访</h2><p>春日新芽初展，茶山薄雾轻笼。我们沿着古老茶径，拜访坚持手工制茶的茶农，从采摘、摊青到焙火，记录一片茶叶的清香旅程。</p><p>每一盏好茶，都来自对时令、产地与工艺的尊重。</p>',
       '0',NOW(),10
FROM mall_content_category c
WHERE c.category_code='TEA_SALES' AND NOT EXISTS(SELECT 1 FROM mall_content_article WHERE slug='yesterday-tea-visit');

INSERT IGNORE INTO mall_content_article_product(article_id,product_id,sort_no)
SELECT a.id,p.id,p.id FROM mall_content_article a JOIN mall_product p ON p.id IN (1,4)
WHERE a.slug='yesterday-tea-visit';

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
 (5040,'合伙人申请',5000,14,'partner-application','mall/partner-application/index','','',1,0,'C','0','0','mall:partner:list','peoples','admin',NOW(),'',NULL,'合伙人申请与审核记录'),
 (5041,'合伙人数据',5000,15,'partner-stats','mall/partner-stats/index','','',1,0,'C','0','0','mall:partner-stats:list','chart','admin',NOW(),'',NULL,'合伙人客户归属、订单与业绩'),
 (5042,'内容分类',5000,16,'content-category','mall/content-category/index','','',1,0,'C','0','0','mall:content-category:list','tree-table','admin',NOW(),'',NULL,'商城内容分类'),
 (5043,'内容文章',5000,17,'content-article','mall/content-article/index','','',1,0,'C','0','0','mall:content-article:list','documentation','admin',NOW(),'',NULL,'茶叶销售文章和推荐商品'),
 (5044,'社区动态',5000,18,'community-post','mall/community-post/index','','',1,0,'C','0','0','mall:community-post:list','message','admin',NOW(),'',NULL,'茶友动态及内容审核'),
 (5045,'社区评论',5000,19,'community-comment','mall/community-comment/index','','',1,0,'C','0','0','mall:community-comment:list','list','admin',NOW(),'',NULL,'评论、点赞和举报')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),component=VALUES(component),perms=VALUES(perms),remark=VALUES(remark);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
 (5140,'合伙人审核',5040,1,'#','','','',1,0,'F','0','0','mall:partner:edit','#','admin',NOW(),'',NULL,''),
 (5141,'客户归属调整',5041,1,'#','','','',1,0,'F','0','0','mall:partner-owner:edit','#','admin',NOW(),'',NULL,''),
 (5142,'内容分类修改',5042,1,'#','','','',1,0,'F','0','0','mall:content-category:edit','#','admin',NOW(),'',NULL,''),
 (5143,'内容文章修改',5043,1,'#','','','',1,0,'F','0','0','mall:content-article:edit','#','admin',NOW(),'',NULL,''),
 (5144,'社区动态审核',5044,1,'#','','','',1,0,'F','0','0','mall:community-post:edit','#','admin',NOW(),'',NULL,''),
 (5145,'社区评论审核',5045,1,'#','','','',1,0,'F','0','0','mall:community-comment:edit','#','admin',NOW(),'',NULL,'')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),perms=VALUES(perms);

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1,menu_id FROM sys_menu WHERE menu_id IN (5040,5041,5042,5043,5044,5045,5140,5141,5142,5143,5144,5145);

DROP PROCEDURE IF EXISTS mall_phase7_add_column;
