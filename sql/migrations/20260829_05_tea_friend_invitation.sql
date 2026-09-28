-- 17-24 茶友、邀请场景与礼包闭环。只允许在隔离测试库先行验证。
-- 本脚本只创建缺失结构、索引和默认规则，可重复执行，不删除或覆盖现有业务数据。

CREATE TABLE IF NOT EXISTS mall_invite_scene (
  id BIGINT NOT NULL AUTO_INCREMENT,
  inviter_customer_id BIGINT NOT NULL,
  scene_code VARCHAR(40) NOT NULL,
  channel VARCHAR(24) NOT NULL DEFAULT 'GENERAL',
  status CHAR(1) NOT NULL DEFAULT '0',
  expires_at DATETIME DEFAULT NULL,
  use_count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_invite_scene_code (scene_code),
  KEY idx_mall_invite_scene_owner (inviter_customer_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后端生成的不可猜测邀请场景';

CREATE TABLE IF NOT EXISTS mall_invite_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  inviter_customer_id BIGINT NOT NULL,
  invitee_customer_id BIGINT NOT NULL,
  scene_id BIGINT DEFAULT NULL,
  source VARCHAR(24) NOT NULL DEFAULT 'REGISTER',
  status VARCHAR(24) NOT NULL DEFAULT '待下单',
  completed_order_id BIGINT DEFAULT NULL,
  completed_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_invite_record_invitee (invitee_customer_id),
  UNIQUE KEY uk_mall_invite_record_pair (inviter_customer_id,invitee_customer_id),
  KEY idx_mall_invite_record_owner_status (inviter_customer_id,status),
  KEY idx_mall_invite_record_scene (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可追踪且不可重复绑定的邀请记录';

CREATE TABLE IF NOT EXISTS mall_invite_gift_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_name VARCHAR(80) NOT NULL,
  required_completed_invites INT NOT NULL DEFAULT 3,
  reward_points INT NOT NULL DEFAULT 188,
  gift_value DECIMAL(10,2) NOT NULL DEFAULT 36.80,
  gift_contents VARCHAR(500) NOT NULL DEFAULT '',
  status CHAR(1) NOT NULL DEFAULT '0',
  start_time DATETIME DEFAULT NULL,
  end_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mall_invite_gift_rule_status (status,start_time,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请礼包和奖励规则';

CREATE TABLE IF NOT EXISTS mall_invite_gift_claim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  claim_no VARCHAR(40) NOT NULL,
  request_no VARCHAR(64) NOT NULL,
  rule_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  completed_invite_count INT NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  reward_snapshot VARCHAR(500) NOT NULL DEFAULT '',
  status VARCHAR(24) NOT NULL DEFAULT '已领取',
  admin_remark VARCHAR(255) NOT NULL DEFAULT '',
  claim_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_invite_gift_claim_no (claim_no),
  UNIQUE KEY uk_mall_invite_gift_request (request_no),
  UNIQUE KEY uk_mall_invite_gift_customer_rule (customer_id,rule_id),
  KEY idx_mall_invite_gift_claim_status (status,claim_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请礼包幂等领取和发放记录';

INSERT INTO mall_invite_record(inviter_customer_id,invitee_customer_id,source,status,create_time)
SELECT d.parent_customer_id,d.customer_id,'LEGACY',
       CASE WHEN EXISTS (
         SELECT 1 FROM mall_order o
         WHERE o.customer_id=d.customer_id AND o.status='已完成' AND o.payment_status='已支付'
       ) THEN '已完成' ELSE '待下单' END,
       d.create_time
FROM mall_distributor d
WHERE d.parent_customer_id IS NOT NULL
ON DUPLICATE KEY UPDATE inviter_customer_id=inviter_customer_id;

INSERT INTO mall_invite_gift_rule
  (id,rule_name,required_completed_invites,reward_points,gift_value,gift_contents,status)
VALUES
  (1,'茶礼伴手礼包',3,188,36.80,'明前龙井 10g|白瓷品茗杯|满100减20礼遇券','0')
ON DUPLICATE KEY UPDATE id=id;

INSERT IGNORE INTO sys_menu VALUES
  (5020, '邀请记录', 5000, 8, 'invite-record', 'mall/invite-record/index', '', '', 1, 0, 'C', '0', '0', 'mall:invite:list', 'list', 'admin', NOW(), '', NULL, '邀请绑定与首单完成记录'),
  (5021, '邀请礼包', 5000, 9, 'invite-gift', 'mall/invite-gift/index', '', '', 1, 0, 'C', '0', '0', 'mall:invite-gift:list', 'gift', 'admin', NOW(), '', NULL, '邀请礼包规则与领取记录'),
  (5022, '邀请场景', 5000, 10, 'invite-scene', 'mall/invite-scene/index', '', '', 1, 0, 'C', '0', '0', 'mall:invite-scene:list', 'qrcode', 'admin', NOW(), '', NULL, '邀请码场景追踪和异常检查'),
  (5120, '修改礼包规则', 5021, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:invite-gift:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5121, '处理礼包领取', 5021, 2, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:invite-gift:edit', '#', 'admin', NOW(), '', NULL, '');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (5020,5021,5022,5120,5121);

SELECT table_name FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN
  ('mall_invite_scene','mall_invite_record','mall_invite_gift_rule','mall_invite_gift_claim')
ORDER BY table_name;
