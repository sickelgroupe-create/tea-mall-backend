-- 茶叶商城第二阶段 09-16：订单消息中心与专题收藏
-- 安全属性：仅新增表、索引、菜单与迁移记录，不删除或覆盖现有业务数据。
-- 只能先在隔离测试库执行；生产执行前必须完成全库备份和恢复演练。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mall_topic_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  topic_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_topic_favorite (topic_id,customer_id),
  KEY idx_mall_topic_favorite_customer (customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员收藏专题';

CREATE TABLE IF NOT EXISTS mall_notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  category VARCHAR(16) NOT NULL DEFAULT '系统',
  title VARCHAR(128) NOT NULL,
  content VARCHAR(1000) NOT NULL DEFAULT '',
  target_route VARCHAR(64) NOT NULL DEFAULT '',
  target_query VARCHAR(255) NOT NULL DEFAULT '',
  source_type VARCHAR(32) NOT NULL DEFAULT '',
  source_key VARCHAR(64) NOT NULL DEFAULT '',
  event_code VARCHAR(32) NOT NULL DEFAULT '',
  read_status TINYINT(1) NOT NULL DEFAULT 0,
  read_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_notification_event (customer_id,source_type,source_key,event_code),
  KEY idx_mall_notification_customer (customer_id,read_status,create_time,id),
  KEY idx_mall_notification_category (category,create_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城会员消息';

INSERT IGNORE INTO sys_menu VALUES
  (5015,'消息中心',5000,15,'notification','mall/notification/index','','',1,0,'C','0','0','mall:notification:list','message','admin',NOW(),'',NULL,'查看并发布会员业务消息'),
  (5118,'消息新增',5015,1,'#','','','',1,0,'F','0','0','mall:notification:edit','#','admin',NOW(),'',NULL,''),
  (5119,'消息修改',5015,2,'#','','','',1,0,'F','0','0','mall:notification:edit','#','admin',NOW(),'',NULL,''),
  (5120,'消息删除',5015,3,'#','','','',1,0,'F','0','0','mall:notification:remove','#','admin',NOW(),'',NULL,'');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1,menu_id FROM sys_menu WHERE menu_id IN (5015,5118,5119,5120);

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260829_04','09-16订单消息中心与专题收藏')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SELECT COUNT(*) AS topic_favorite_count FROM mall_topic_favorite;
SELECT COUNT(*) AS notification_count FROM mall_notification;
SELECT version_no,description,applied_time FROM mall_schema_migration WHERE version_no='20260829_04';
