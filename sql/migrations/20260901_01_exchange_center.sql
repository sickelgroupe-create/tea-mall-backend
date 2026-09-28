-- 兑换中心、待生效积分、邀请阶梯与二级商品分类增量迁移。
-- 只新增结构/默认配置，不删除、不清空、不覆盖已有业务值；可重复执行。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS mall_20260901_add_column;
DELIMITER $$
CREATE PROCEDURE mall_20260901_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name=p_table AND column_name=p_column
  ) THEN
    SET @ddl=CONCAT('ALTER TABLE `',p_table,'` ADD COLUMN `',p_column,'` ',p_definition);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL mall_20260901_add_column('mall_category','parent_id','BIGINT NULL AFTER id');
CALL mall_20260901_add_column('mall_category','category_code','VARCHAR(40) NOT NULL DEFAULT '''' AFTER parent_id');
CALL mall_20260901_add_column('mall_category','category_group','VARCHAR(24) NOT NULL DEFAULT ''TEA'' AFTER category_code');
CALL mall_20260901_add_column('mall_content_category','parent_id','BIGINT NULL AFTER id');
CALL mall_20260901_add_column('mall_content_category','description','VARCHAR(500) NOT NULL DEFAULT '''' AFTER category_name');
CALL mall_20260901_add_column('mall_invite_gift_rule','reward_type','VARCHAR(24) NOT NULL DEFAULT ''POINTS'' AFTER required_completed_invites');
CALL mall_20260901_add_column('mall_invite_gift_rule','reward_id','BIGINT NULL AFTER reward_type');
CALL mall_20260901_add_column('mall_invite_gift_rule','reward_qty','INT NOT NULL DEFAULT 1 AFTER reward_id');
CALL mall_20260901_add_column('mall_invite_gift_rule','stock','INT NOT NULL DEFAULT 999999 AFTER reward_qty');
CALL mall_20260901_add_column('mall_invite_gift_rule','sort_no','INT NOT NULL DEFAULT 0 AFTER status');

CREATE TABLE IF NOT EXISTS mall_points_accrual (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(40) NOT NULL,
  original_points INT NOT NULL,
  available_points INT NOT NULL DEFAULT 0,
  reversed_points INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT '待生效',
  effective_time DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_accrual_order(order_id),
  UNIQUE KEY uk_points_accrual_order_no(order_no),
  KEY idx_points_accrual_customer_status(customer_id,status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物积分待生效与生效状态账本';

CREATE TABLE IF NOT EXISTS mall_points_debt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  debt_no VARCHAR(40) NOT NULL,
  customer_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  business_no VARCHAR(64) NOT NULL,
  points_amount INT NOT NULL,
  settled_points INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT '待补扣',
  reason VARCHAR(500) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_debt_business(customer_id,business_no),
  KEY idx_points_debt_customer_status(customer_id,status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款时积分不足的可审计待补扣记录';

CREATE TABLE IF NOT EXISTS mall_invite_rule_config (
  id BIGINT NOT NULL,
  valid_condition VARCHAR(32) NOT NULL DEFAULT 'FIRST_COMPLETED_ORDER',
  description VARCHAR(500) NOT NULL DEFAULT '好友完成首笔有效订单后计入有效邀请',
  status CHAR(1) NOT NULL DEFAULT '0',
  version_no INT NOT NULL DEFAULT 1,
  updated_by BIGINT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请有效条件配置';

INSERT INTO mall_invite_rule_config(id,valid_condition,description,status)
VALUES(1,'FIRST_COMPLETED_ORDER','好友完成首笔有效订单后计入有效邀请','0')
ON DUPLICATE KEY UPDATE id=id;

INSERT INTO mall_category(name,parent_id,category_code,category_group,sort_no,status)
VALUES
 ('茶叶',NULL,'TEA','TEA',10,'0'),
 ('茶具',NULL,'TEAWARE','TEAWARE',20,'0')
ON DUPLICATE KEY UPDATE category_code=IF(category_code='',VALUES(category_code),category_code),category_group=VALUES(category_group);

UPDATE mall_category SET parent_id=(SELECT root.id FROM (SELECT id FROM mall_category WHERE name='茶叶' LIMIT 1) root),
 category_code=CASE name WHEN '红茶' THEN 'BLACK_TEA' WHEN '绿茶' THEN 'GREEN_TEA' WHEN '白茶' THEN 'WHITE_TEA' ELSE category_code END,
 category_group='TEA'
WHERE name IN('红茶','绿茶','白茶') AND parent_id IS NULL;

INSERT INTO mall_category(name,parent_id,category_code,category_group,sort_no,status)
SELECT child.name,root.id,child.code,'TEAWARE',child.sort_no,'0'
FROM mall_category root
JOIN (
 SELECT '青花' name,'BLUE_WHITE' code,10 sort_no UNION ALL
 SELECT '彩瓷','COLORED_PORCELAIN',20 UNION ALL
 SELECT '单色釉','MONOCHROME_GLAZE',30 UNION ALL
 SELECT '文创','CULTURAL_CREATIVE',40
) child
WHERE root.name='茶具'
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),category_code=VALUES(category_code),category_group=VALUES(category_group);

INSERT INTO mall_content_category(category_code,category_name,parent_id,description,status,sort_no)
SELECT 'TEA_SCIENCE','茶叶科普',NULL,'茶类、产区、工艺与冲泡知识','0',5
WHERE NOT EXISTS(SELECT 1 FROM mall_content_category WHERE category_code='TEA_SCIENCE');

INSERT INTO mall_content_category(category_code,category_name,parent_id,description,status,sort_no)
SELECT child.code,child.name,root.id,child.description,'0',child.sort_no
FROM mall_content_category root
JOIN (
 SELECT 'TEA_SCIENCE_BLACK' code,'红茶科普' name,'红茶产区、工艺与冲泡知识' description,10 sort_no UNION ALL
 SELECT 'TEA_SCIENCE_GREEN','绿茶科普','绿茶产区、工艺与冲泡知识',20 UNION ALL
 SELECT 'TEA_SCIENCE_WHITE','白茶科普','白茶产区、工艺与冲泡知识',30
) child
WHERE root.category_code='TEA_SCIENCE'
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),description=VALUES(description);

INSERT INTO mall_content_article(category_id,slug,title,summary,cover_image_key,body_html,status,published_at,sort_no)
SELECT c.id,'longjing-basics','认识西湖龙井','从产区、采摘标准与冲泡方法认识西湖龙井。','longjing-pale',
 '<h2>西湖龙井基础知识</h2><p>西湖龙井属于绿茶，重视鲜叶嫩度、摊放、辉锅与干燥工艺。建议使用洁净水和适宜水温冲泡，并依据茶叶嫩度调整浸泡时间。</p>',
 '0',NOW(),10
FROM mall_content_category c
WHERE c.category_code='TEA_SCIENCE_GREEN'
  AND NOT EXISTS(SELECT 1 FROM mall_content_article WHERE slug='longjing-basics');

INSERT IGNORE INTO mall_page_module(module_key,page_code,module_code,english_title,title,subtitle,description,image_url,jump_type,jump_target,config_json,status,sort_no,publish_time,version_no)
VALUES
 ('home.exchange-center','01','exchange-center','新品臻享','春日好茶 · 限时甄选','西湖产区当季鲜采，茶香清雅，回甘悠长。','点击整张宣传图进入兑换中心。','/static/images/longjing-hero-v2.webp','route','exchangeCenter',JSON_OBJECT(),'0',5,NOW(),1),
 ('home.partner-entry','01','feature-entry','','成为合伙人','','提交资料并查看审核进度。','','route','partnerIntro',JSON_OBJECT('icon','users'),'0',30,NOW(),1),
 ('home.science-entry','01','feature-entry','','茶叶科普','','从分类进入文章详情，系统了解茶知识。','','route','teaScience',JSON_OBJECT('icon','book'),'0',31,NOW(),1),
 ('home.community-entry','01','feature-entry','','留言板','','发布、点赞和评论茶友内容。','','route','community',JSON_OBJECT('icon','message'),'0',32,NOW(),1),
 ('home.sales-entry','01','feature-entry','','售卖栏','','茶叶与茶具分栏浏览，复用现有商品系统。','','route','category',JSON_OBJECT('icon','cart'),'0',33,NOW(),1),
 ('exchange.points','EXCHANGE_CENTER','summary','','购物积分','购买商品获得积分','订单完成后积分转为可用。','','route','pointsCenter',JSON_OBJECT(),'0',10,NOW(),1),
 ('exchange.invite','EXCHANGE_CENTER','summary','','邀请奖励','','好友首笔有效订单完成后计入。','','route','inviteRewards',JSON_OBJECT(),'0',20,NOW(),1);

UPDATE mall_invite_gift_rule SET sort_no=id WHERE sort_no=0;
DROP PROCEDURE IF EXISTS mall_20260901_add_column;
