-- 商品稳定分类关系、合伙人活动申请唯一约束、客服工单时间轴。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS mall_phase5_add_column$$
CREATE PROCEDURE mall_phase5_add_column(IN table_value VARCHAR(64), IN column_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND COLUMN_NAME=column_value) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD COLUMN ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$

DROP PROCEDURE IF EXISTS mall_phase5_add_index$$
CREATE PROCEDURE mall_phase5_add_index(IN table_value VARCHAR(64), IN index_value VARCHAR(64), IN ddl_value TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_value AND INDEX_NAME=index_value) THEN
    SET @mall_ddl=CONCAT('ALTER TABLE `',table_value,'` ADD ',ddl_value);
    PREPARE mall_statement FROM @mall_ddl;
    EXECUTE mall_statement;
    DEALLOCATE PREPARE mall_statement;
  END IF;
END$$
DELIMITER ;

CALL mall_phase5_add_column('mall_product','category_id',
  '`category_id` BIGINT DEFAULT NULL COMMENT ''稳定商品分类ID'' AFTER `category`');
UPDATE mall_product p JOIN mall_category c ON c.name=p.category
SET p.category_id=c.id WHERE p.category_id IS NULL;
CALL mall_phase5_add_index('mall_product','idx_mall_product_category_id',
  'INDEX `idx_mall_product_category_id` (`category_id`,`status`,`id`)');

CALL mall_phase5_add_column('mall_partner_application','active_customer_id',
  '`active_customer_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` IN (''待审核'',''审核通过'') THEN `customer_id` ELSE NULL END) STORED');
CALL mall_phase5_add_index('mall_partner_application','uk_partner_application_active_customer',
  'UNIQUE INDEX `uk_partner_application_active_customer` (`active_customer_id`)');

CREATE TABLE IF NOT EXISTS mall_service_ticket_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  sender_type VARCHAR(16) NOT NULL COMMENT '用户/客服/系统',
  sender_id BIGINT DEFAULT NULL,
  content VARCHAR(1000) NOT NULL,
  attachment_url VARCHAR(512) NOT NULL DEFAULT '',
  from_status VARCHAR(24) NOT NULL DEFAULT '',
  to_status VARCHAR(24) NOT NULL DEFAULT '',
  request_no VARCHAR(80) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_ticket_message_request(ticket_id,request_no),
  KEY idx_ticket_message_time(ticket_id,create_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单回复与状态时间轴';

INSERT INTO mall_service_ticket_message(ticket_id,sender_type,sender_id,content,from_status,to_status,request_no,create_time)
SELECT t.id,'用户',t.customer_id,t.content,'','待处理',CONCAT('LEGACY-CREATE-',t.id),t.create_time
FROM mall_service_ticket t
WHERE NOT EXISTS(SELECT 1 FROM mall_service_ticket_message m WHERE m.ticket_id=t.id);

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260903_05','商品稳定分类、合伙人活动申请唯一约束、客服工单时间轴')
ON DUPLICATE KEY UPDATE description=VALUES(description);

DROP PROCEDURE IF EXISTS mall_phase5_add_column;
DROP PROCEDURE IF EXISTS mall_phase5_add_index;

SELECT version_no,description,applied_time
FROM mall_schema_migration WHERE version_no='20260903_05';
