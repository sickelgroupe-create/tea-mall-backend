-- 25-32 积分、阶梯奖励与兑换闭环。只允许先在隔离测试库执行。
-- 可重复执行：新增结构使用 IF NOT EXISTS，菜单和种子数据按主键/业务编码幂等写入。

DROP PROCEDURE IF EXISTS mall_points_add_column;
DELIMITER $$
CREATE PROCEDURE mall_points_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=p_table AND COLUMN_NAME=p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL mall_points_add_column('mall_points_log','balance_before','`balance_before` INT NULL AFTER `amount`');
CALL mall_points_add_column('mall_points_log','balance_after','`balance_after` INT NULL AFTER `balance_before`');
CALL mall_points_add_column('mall_points_log','related_business_no','`related_business_no` VARCHAR(64) NOT NULL DEFAULT '''' AFTER `business_key`');
CALL mall_points_add_column('mall_points_log','remark','`remark` VARCHAR(255) NOT NULL DEFAULT '''' AFTER `description`');
UPDATE mall_points_log SET balance_after=balance WHERE balance_after IS NULL;
UPDATE mall_points_log SET balance_before=balance_after-amount WHERE balance_before IS NULL;

CALL mall_points_add_column('mall_reward','category','`category` VARCHAR(32) NOT NULL DEFAULT ''精选'' AFTER `name`');
CALL mall_points_add_column('mall_reward','limit_qty','`limit_qty` INT NOT NULL DEFAULT 2 AFTER `stock`');
CALL mall_points_add_column('mall_reward','exchange_notes','`exchange_notes` VARCHAR(500) NOT NULL DEFAULT ''兑换成功后由仓库安排发货'' AFTER `image_key`');
CALL mall_points_add_column('mall_reward','delivery_method','`delivery_method` VARCHAR(64) NOT NULL DEFAULT ''快递配送'' AFTER `exchange_notes`');
CALL mall_points_add_column('mall_exchange','update_time','`update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`');

CREATE TABLE IF NOT EXISTS mall_points_checkin (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  checkin_date DATE NOT NULL,
  streak_days INT NOT NULL DEFAULT 1,
  reward_points INT NOT NULL DEFAULT 0,
  business_key VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_checkin_customer_day(customer_id,checkin_date),
  UNIQUE KEY uk_points_checkin_business(customer_id,business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户签到记录';

CREATE TABLE IF NOT EXISTS mall_points_task_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_code VARCHAR(32) NOT NULL,
  task_name VARCHAR(64) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  description VARCHAR(255) NOT NULL DEFAULT '',
  start_time DATETIME NULL,
  end_time DATETIME NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_task_code(task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分任务规则';

CREATE TABLE IF NOT EXISTS mall_points_task_claim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  business_key VARCHAR(64) NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT '已领取',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_points_task_claim(customer_id,rule_id,business_key),
  KEY idx_points_task_claim_rule(rule_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分任务奖励领取';

CREATE TABLE IF NOT EXISTS mall_tier_reward_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_name VARCHAR(64) NOT NULL,
  metric_type VARCHAR(32) NOT NULL DEFAULT 'COMPLETED_INVITES',
  threshold_value INT NOT NULL,
  reward_points INT NOT NULL DEFAULT 0,
  reward_name VARCHAR(128) NOT NULL,
  reward_image_key VARCHAR(512) NOT NULL DEFAULT 'tea-gift-v2',
  reward_contents VARCHAR(500) NOT NULL DEFAULT '',
  start_time DATETIME NULL,
  end_time DATETIME NULL,
  status CHAR(1) NOT NULL DEFAULT '0',
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_tier_reward_threshold(metric_type,threshold_value),
  KEY idx_tier_reward_status(status,start_time,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶梯奖励规则';

CREATE TABLE IF NOT EXISTS mall_tier_reward_claim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  claim_no VARCHAR(40) NOT NULL,
  request_no VARCHAR(64) NOT NULL,
  customer_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  progress_snapshot INT NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  reward_snapshot VARCHAR(500) NOT NULL DEFAULT '',
  status VARCHAR(24) NOT NULL DEFAULT '已领取',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_tier_claim_no(claim_no),
  UNIQUE KEY uk_tier_claim_customer_rule(customer_id,rule_id),
  UNIQUE KEY uk_tier_claim_request(customer_id,request_no),
  KEY idx_tier_claim_rule(rule_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶梯奖励领取记录';

INSERT INTO mall_points_task_rule(task_code,task_name,business_type,reward_points,description,status,sort_no)
SELECT 'DAILY_CHECKIN','每日签到','CHECKIN',10,'按 Asia/Shanghai 自然日签到','0',10
WHERE NOT EXISTS(SELECT 1 FROM mall_points_task_rule WHERE task_code='DAILY_CHECKIN');
INSERT INTO mall_points_task_rule(task_code,task_name,business_type,reward_points,description,status,sort_no)
SELECT 'FIRST_COMPLETED_ORDER','完成首单','ORDER',50,'首个已完成商城订单可领取','0',20
WHERE NOT EXISTS(SELECT 1 FROM mall_points_task_rule WHERE task_code='FIRST_COMPLETED_ORDER');
INSERT INTO mall_points_task_rule(task_code,task_name,business_type,reward_points,description,status,sort_no)
SELECT 'FIRST_REVIEW','完成评价','REVIEW',10,'首条审核通过的真实评价可领取','0',30
WHERE NOT EXISTS(SELECT 1 FROM mall_points_task_rule WHERE task_code='FIRST_REVIEW');
INSERT INTO mall_points_task_rule(task_code,task_name,business_type,reward_points,description,status,sort_no)
SELECT 'FIRST_COMPLETED_INVITE','邀请一位茶友','INVITE',200,'首位完成有效关系绑定的茶友可领取','0',40
WHERE NOT EXISTS(SELECT 1 FROM mall_points_task_rule WHERE task_code='FIRST_COMPLETED_INVITE');

INSERT INTO mall_tier_reward_rule(rule_name,metric_type,threshold_value,reward_points,reward_name,reward_contents,status,sort_no)
SELECT '邀请1位茶友','COMPLETED_INVITES',1,20,'新友茶样','茶样礼盒|20积分','0',10
WHERE NOT EXISTS(SELECT 1 FROM mall_tier_reward_rule WHERE metric_type='COMPLETED_INVITES' AND threshold_value=1);
INSERT INTO mall_tier_reward_rule(rule_name,metric_type,threshold_value,reward_points,reward_name,reward_contents,status,sort_no)
SELECT '邀请5位茶友','COMPLETED_INVITES',5,80,'茶器礼包','品茗杯|80积分','0',20
WHERE NOT EXISTS(SELECT 1 FROM mall_tier_reward_rule WHERE metric_type='COMPLETED_INVITES' AND threshold_value=5);
INSERT INTO mall_tier_reward_rule(rule_name,metric_type,threshold_value,reward_points,reward_name,reward_contents,status,sort_no)
SELECT '邀请10位茶友','COMPLETED_INVITES',10,188,'高阶茶具套装','典藏茶具|188积分','0',30
WHERE NOT EXISTS(SELECT 1 FROM mall_tier_reward_rule WHERE metric_type='COMPLETED_INVITES' AND threshold_value=10);
INSERT INTO mall_tier_reward_rule(rule_name,metric_type,threshold_value,reward_points,reward_name,reward_contents,status,sort_no)
SELECT '邀请20位茶友','COMPLETED_INVITES',20,388,'年度茶礼','年度茶礼|388积分','0',40
WHERE NOT EXISTS(SELECT 1 FROM mall_tier_reward_rule WHERE metric_type='COMPLETED_INVITES' AND threshold_value=20);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
  (5030,'积分任务',5000,11,'points-task','mall/points-task/index','','',1,0,'C','0','0','mall:points-task:list','check', 'admin',NOW(),'',NULL,'签到和积分任务规则'),
  (5031,'积分流水',5000,12,'points-ledger','mall/points-ledger/index','','',1,0,'C','0','0','mall:points-ledger:list','list', 'admin',NOW(),'',NULL,'积分账本和一致性核对'),
  (5032,'阶梯奖励',5000,13,'tier-reward','mall/tier-reward/index','','',1,0,'C','0','0','mall:tier-reward:list','star', 'admin',NOW(),'',NULL,'阶梯规则与领取记录')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),component=VALUES(component),perms=VALUES(perms),remark=VALUES(remark);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
  (5130,'积分任务修改',5030,1,'#','','','',1,0,'F','0','0','mall:points-task:edit','#','admin',NOW(),'',NULL,''),
  (5131,'阶梯规则修改',5032,1,'#','','','',1,0,'F','0','0','mall:tier-reward:edit','#','admin',NOW(),'',NULL,''),
  (5132,'阶梯领取处理',5032,2,'#','','','',1,0,'F','0','0','mall:tier-reward:claim','#','admin',NOW(),'',NULL,'')
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),perms=VALUES(perms);

DROP PROCEDURE IF EXISTS mall_points_add_column;
