-- 茶叶商城业务一致性 V2。
-- 本迁移只扩展结构和商品配置，不修正历史订单、会员余额或积分流水。
-- MySQL 8.0，可重复执行。

DROP PROCEDURE IF EXISTS mall_add_column;
DROP PROCEDURE IF EXISTS mall_add_index;
DELIMITER $$
CREATE PROCEDURE mall_add_column(IN table_name_value VARCHAR(64), IN column_name_value VARCHAR(64), IN column_sql TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_name_value AND COLUMN_NAME = column_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN ', column_sql);
    PREPARE statement_value FROM @ddl;
    EXECUTE statement_value;
    DEALLOCATE PREPARE statement_value;
  END IF;
END$$

CREATE PROCEDURE mall_add_index(IN table_name_value VARCHAR(64), IN index_name_value VARCHAR(64), IN index_sql TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_name_value AND INDEX_NAME = index_name_value
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD ', index_sql);
    PREPARE statement_value FROM @ddl;
    EXECUTE statement_value;
    DEALLOCATE PREPARE statement_value;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column('mall_points_log', 'business_type',
  '`business_type` VARCHAR(32) NOT NULL DEFAULT ''LEGACY'' AFTER `description`');
CALL mall_add_column('mall_points_log', 'business_key',
  '`business_key` VARCHAR(64) NULL AFTER `business_type`');
CALL mall_add_column('mall_points_log', 'order_id',
  '`order_id` BIGINT NULL AFTER `business_key`');
CALL mall_add_index('mall_points_log', 'uk_mall_points_business',
  'UNIQUE KEY `uk_mall_points_business` (`customer_id`,`business_type`,`business_key`)');
CALL mall_add_index('mall_points_log', 'idx_mall_points_order',
  'KEY `idx_mall_points_order` (`order_id`)');

CALL mall_add_column('mall_exchange', 'request_id',
  '`request_id` VARCHAR(64) NULL AFTER `exchange_no`');
CALL mall_add_index('mall_exchange', 'uk_mall_exchange_request',
  'UNIQUE KEY `uk_mall_exchange_request` (`customer_id`,`request_id`)');

CALL mall_add_column('mall_product', 'raw_material',
  '`raw_material` VARCHAR(255) NOT NULL DEFAULT '''' AFTER `grade_name`');
CALL mall_add_column('mall_product', 'brew_guide',
  '`brew_guide` VARCHAR(500) NOT NULL DEFAULT '''' AFTER `raw_material`');
CALL mall_add_column('mall_product', 'batch_no',
  '`batch_no` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `brew_guide`');
CALL mall_add_column('mall_product', 'traceability_info',
  '`traceability_info` VARCHAR(500) NOT NULL DEFAULT '''' AFTER `batch_no`');

DROP PROCEDURE IF EXISTS mall_add_column;
DROP PROCEDURE IF EXISTS mall_add_index;

CREATE TABLE IF NOT EXISTS mall_product_media (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  media_type VARCHAR(16) NOT NULL DEFAULT 'gallery',
  media_url VARCHAR(512) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_product_media (product_id, media_type, media_url),
  KEY idx_mall_product_media_sort (product_id, sort_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主图、轮播图与详情媒体';

CREATE TABLE IF NOT EXISTS mall_category (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  icon_url VARCHAR(512) NOT NULL DEFAULT '',
  sort_no INT NOT NULL DEFAULT 0,
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0正常 1隐藏 2即将上线',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_category_name (name),
  KEY idx_mall_category_status_sort (status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城动态分类';

INSERT INTO mall_category(name, sort_no, status) VALUES
  ('绿茶', 10, '0'),
  ('红茶', 20, '0'),
  ('乌龙茶', 30, '2'),
  ('白茶', 40, '2'),
  ('普洱', 50, '2'),
  ('礼盒', 60, '0'),
  ('茶具', 70, '0')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 将旧的前端图片标识转换成由后台字段直接下发的资源路径。
UPDATE mall_product SET image_key=CASE image_key
  WHEN 'longjing-pale' THEN '/static/images/longjing-hero-v2.webp'
  WHEN 'longjing-dark' THEN '/static/images/longjing-dark-v2.webp'
  WHEN 'tea-gift' THEN '/static/images/tea-gift-v2.webp'
  WHEN 'biluochun' THEN '/static/images/biluochun.jpg'
  WHEN 'blacktea-red' THEN '/static/images/blacktea-red.jpg'
  WHEN 'blacktea-orange' THEN '/static/images/blacktea-orange.jpg'
  WHEN 'maofeng-pouch' THEN '/static/images/maofeng-pouch.jpg'
  WHEN 'yixing-pot' THEN '/static/images/yixing-pot.jpg'
  ELSE image_key END
WHERE image_key IN ('longjing-pale','longjing-dark','tea-gift','biluochun','blacktea-red','blacktea-orange','maofeng-pouch','yixing-pot');

-- 修复“特级西湖龙井 50g”错误复用碧螺春图片；不改动商品 6 的碧螺春图。
UPDATE mall_product SET image_key='/static/images/longjing-dark-v2.webp'
WHERE id=4 AND name LIKE '%龙井%'
  AND image_key IN ('biluochun','longjing-pale','/static/images/biluochun.jpg');

-- 仅为尚未建立媒体记录的商品登记当前后台主图值；不会覆盖已有媒体。
INSERT IGNORE INTO mall_product_media(product_id, media_type, media_url, sort_no)
SELECT id, 'main', image_key, 0 FROM mall_product WHERE image_key<>'';

-- Druid 连接池监控在生产配置中明确关闭；隐藏失效菜单，保留已受权的服务/缓存监控。
UPDATE sys_menu SET visible='1', status='1'
WHERE path='druid' OR component='monitor/druid/index';
