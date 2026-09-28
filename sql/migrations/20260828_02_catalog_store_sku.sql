-- 茶叶商城第二阶段 01-08：专题、店铺、SKU 与规格购物车
-- 安全属性：仅新增表、列、索引和可追溯的默认映射，不删除任何业务行。
-- 执行前提：已依次执行商城基础 SQL、业务完整性迁移和 20260828_01。
-- 仅允许先在隔离测试库执行；执行前必须备份并保存文末基线查询结果。

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS mall_add_column_v4$$
CREATE PROCEDURE mall_add_column_v4(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
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

DROP PROCEDURE IF EXISTS mall_add_index_v4$$
CREATE PROCEDURE mall_add_index_v4(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
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

DROP PROCEDURE IF EXISTS mall_drop_index_v4$$
CREATE PROCEDURE mall_drop_index_v4(IN table_value VARCHAR(64), IN index_value VARCHAR(64))
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND INDEX_NAME=index_value
  ) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` DROP INDEX `',index_value,'`');
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DELIMITER ;

CREATE TABLE IF NOT EXISTS mall_store (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  logo_url VARCHAR(512) NOT NULL DEFAULT '',
  hero_image_url VARCHAR(512) NOT NULL DEFAULT '',
  rating DECIMAL(3,2) NOT NULL DEFAULT 0.00,
  follower_count INT NOT NULL DEFAULT 0,
  story VARCHAR(1000) NOT NULL DEFAULT '',
  shipping_promise VARCHAR(128) NOT NULL DEFAULT '顺丰包邮',
  service_promise VARCHAR(128) NOT NULL DEFAULT '无忧售后',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0营业 1停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mall_store_status (status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城品牌店铺';

CREATE TABLE IF NOT EXISTS mall_store_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  store_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_store_favorite (store_id,customer_id),
  KEY idx_mall_store_favorite_customer (customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员关注店铺';

CREATE TABLE IF NOT EXISTS mall_topic (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slug VARCHAR(64) NOT NULL,
  title VARCHAR(128) NOT NULL,
  kicker VARCHAR(128) NOT NULL DEFAULT '',
  subtitle VARCHAR(255) NOT NULL DEFAULT '',
  hero_image_url VARCHAR(512) NOT NULL DEFAULT '',
  story_image_url VARCHAR(512) NOT NULL DEFAULT '',
  story_title VARCHAR(128) NOT NULL DEFAULT '',
  story_content VARCHAR(1000) NOT NULL DEFAULT '',
  start_time DATETIME DEFAULT NULL,
  end_time DATETIME DEFAULT NULL,
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0展示 1停用',
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_topic_slug (slug),
  KEY idx_mall_topic_status_sort (status,sort_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城首页专题';

CREATE TABLE IF NOT EXISTS mall_topic_product (
  topic_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (topic_id,product_id),
  KEY idx_mall_topic_product_sort (topic_id,sort_no,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专题商品关系';

CREATE TABLE IF NOT EXISTS mall_product_sku (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  sku_code VARCHAR(64) NOT NULL,
  spec_name VARCHAR(128) NOT NULL,
  price DECIMAL(10,2) NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  stock INT NOT NULL DEFAULT 0,
  sales INT NOT NULL DEFAULT 0,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0在售 1停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_product_sku_code (sku_code),
  UNIQUE KEY uk_mall_product_sku_spec (product_id,spec_name),
  KEY idx_mall_product_sku_available (product_id,status,is_default,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品销售规格 SKU';

CALL mall_add_column_v4('mall_product','store_id','`store_id` BIGINT DEFAULT NULL AFTER `id`');
CALL mall_add_index_v4('mall_product','idx_mall_product_store_status','INDEX `idx_mall_product_store_status` (`store_id`,`status`,`id`)');
CALL mall_add_column_v4('mall_cart','sku_id','`sku_id` BIGINT DEFAULT NULL AFTER `product_id`');
CALL mall_add_column_v4('mall_order_item','sku_id','`sku_id` BIGINT DEFAULT NULL AFTER `product_id`');
CALL mall_add_column_v4('mall_order_item','sku_code','`sku_code` VARCHAR(64) NOT NULL DEFAULT \'\' AFTER `sku_id`');

INSERT INTO mall_store(id,name,logo_url,hero_image_url,rating,follower_count,story,shipping_promise,service_promise,status)
VALUES(1,'茶山天香官方旗舰店','/static/images/oolong-category-v1.webp','/static/images/tea-garden-hero.png',0.00,0,
       '源自核心茶区，坚持当季鲜采与传统制茶工艺，让每一盏茶都保留山野清香。','原产地直采·顺丰包邮','正品保障·无忧售后','0')
ON DUPLICATE KEY UPDATE name=VALUES(name);

UPDATE mall_product SET store_id=1 WHERE store_id IS NULL;

INSERT INTO mall_product_sku(product_id,sku_code,spec_name,price,reward_points,stock,sales,is_default,status)
SELECT p.id,CONCAT('TEA-',LPAD(p.id,6,'0'),'-DEFAULT'),
       IF(TRIM(COALESCE(p.spec,''))='', '默认规格', p.spec),p.price,p.reward_points,p.stock,p.sales,1,p.status
FROM mall_product p
WHERE NOT EXISTS (SELECT 1 FROM mall_product_sku s WHERE s.product_id=p.id);

UPDATE mall_cart c
JOIN mall_product_sku s ON s.product_id=c.product_id AND s.is_default=1
SET c.sku_id=s.id
WHERE c.sku_id IS NULL;

-- 若存在无法映射的历史购物车，迁移必须失败，禁止无依据地删除或改写该行。
SET @mall_unmapped_cart=(SELECT COUNT(*) FROM mall_cart WHERE sku_id IS NULL);
SET @mall_guard_sql=IF(@mall_unmapped_cart=0,'SELECT 1','SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''存在无法映射 SKU 的历史购物车，请人工核对''');
PREPARE mall_guard_statement FROM @mall_guard_sql;
EXECUTE mall_guard_statement;
DEALLOCATE PREPARE mall_guard_statement;

ALTER TABLE mall_cart MODIFY COLUMN sku_id BIGINT NOT NULL;
CALL mall_drop_index_v4('mall_cart','uk_mall_cart_customer_product');
CALL mall_add_index_v4('mall_cart','uk_mall_cart_customer_sku','UNIQUE INDEX `uk_mall_cart_customer_sku` (`customer_id`,`sku_id`)');
CALL mall_add_index_v4('mall_cart','idx_mall_cart_product','INDEX `idx_mall_cart_product` (`product_id`)');
CALL mall_add_index_v4('mall_order_item','idx_mall_order_item_sku','INDEX `idx_mall_order_item_sku` (`sku_id`)');

INSERT INTO mall_topic(slug,title,kicker,subtitle,hero_image_url,story_image_url,story_title,story_content,status,sort_no)
VALUES('spring-selection','春日好茶 · 限时甄选','新茶品鉴季','西湖产区当季鲜采，茶香清雅，回甘悠长。',
       '/static/images/tea-garden-hero.png','/static/images/longjing-dark-v2.webp','御选春藏',
       '核心产区，一季一采。从茶园、工艺到器物，建立完整的私人品鉴档案。','0',10)
ON DUPLICATE KEY UPDATE title=VALUES(title),kicker=VALUES(kicker),subtitle=VALUES(subtitle);

INSERT IGNORE INTO mall_topic_product(topic_id,product_id,sort_no)
SELECT t.id,p.id,p.id*10 FROM mall_topic t JOIN mall_product p ON p.id IN (1,2,3,8)
WHERE t.slug='spring-selection';

-- 后台菜单与按钮权限；INSERT IGNORE 保留用户已经调整的菜单内容。
INSERT IGNORE INTO sys_menu VALUES
  (5011,'商品分类',5000,11,'category','mall/category/index','','',1,0,'C','0','0','mall:category:list','tree-table','admin',NOW(),'',NULL,'维护商城分类'),
  (5012,'首页专题',5000,12,'topic','mall/topic/index','','',1,0,'C','0','0','mall:topic:list','example','admin',NOW(),'',NULL,'维护首页专题及商品关系'),
  (5013,'品牌店铺',5000,13,'store','mall/store/index','','',1,0,'C','0','0','mall:store:list','guide','admin',NOW(),'',NULL,'维护品牌店铺信息'),
  (5014,'商品规格',5000,14,'sku','mall/sku/index','','',1,0,'C','0','0','mall:sku:list','component','admin',NOW(),'',NULL,'维护商品SKU价格与库存'),
  (5110,'分类修改',5011,1,'#','','','',1,0,'F','0','0','mall:category:edit','#','admin',NOW(),'',NULL,''),
  (5111,'分类删除',5011,2,'#','','','',1,0,'F','0','0','mall:category:remove','#','admin',NOW(),'',NULL,''),
  (5112,'专题修改',5012,1,'#','','','',1,0,'F','0','0','mall:topic:edit','#','admin',NOW(),'',NULL,''),
  (5113,'专题删除',5012,2,'#','','','',1,0,'F','0','0','mall:topic:remove','#','admin',NOW(),'',NULL,''),
  (5114,'店铺修改',5013,1,'#','','','',1,0,'F','0','0','mall:store:edit','#','admin',NOW(),'',NULL,''),
  (5115,'店铺删除',5013,2,'#','','','',1,0,'F','0','0','mall:store:remove','#','admin',NOW(),'',NULL,''),
  (5116,'规格修改',5014,1,'#','','','',1,0,'F','0','0','mall:sku:edit','#','admin',NOW(),'',NULL,''),
  (5117,'规格删除',5014,2,'#','','','',1,0,'F','0','0','mall:sku:remove','#','admin',NOW(),'',NULL,'');

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260828_02','01-08专题、店铺、商品SKU与规格购物车')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_add_column_v4;
DROP PROCEDURE IF EXISTS mall_add_index_v4;
DROP PROCEDURE IF EXISTS mall_drop_index_v4;

-- 执行后只读核验：记录数应保持，所有购物车均应存在 sku_id。
SELECT COUNT(*) AS product_count FROM mall_product;
SELECT COUNT(*) AS sku_count,COUNT(DISTINCT product_id) AS sku_product_count FROM mall_product_sku;
SELECT COUNT(*) AS cart_count,SUM(sku_id IS NULL) AS unmapped_cart_count FROM mall_cart;
SELECT COUNT(*) AS store_count FROM mall_store;
SELECT COUNT(*) AS topic_count FROM mall_topic;
SELECT version_no,description,applied_time FROM mall_schema_migration WHERE version_no='20260828_02';
