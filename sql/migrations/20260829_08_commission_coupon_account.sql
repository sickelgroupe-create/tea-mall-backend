-- Pages 41-48: commission ledger, withdrawals, coupons, preferences, managed help and browse history.
-- Safe to execute repeatedly. Apply only to an isolated/test database before production review.

DROP PROCEDURE IF EXISTS mall_add_column_4148;
DELIMITER $$
CREATE PROCEDURE mall_add_column_4148(IN t VARCHAR(64), IN c VARCHAR(64), IN d TEXT)
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=t AND COLUMN_NAME=c) THEN
    SET @s=CONCAT('ALTER TABLE `',t,'` ADD COLUMN ',d); PREPARE x FROM @s; EXECUTE x; DEALLOCATE PREPARE x;
  END IF;
END$$
DELIMITER ;

CALL mall_add_column_4148('mall_commission','commission_no','`commission_no` VARCHAR(40) DEFAULT NULL AFTER `id`');
CALL mall_add_column_4148('mall_commission','rule_id','`rule_id` BIGINT DEFAULT NULL AFTER `source_customer_id`');
CALL mall_add_column_4148('mall_commission','base_amount','`base_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER `rule_id`');
CALL mall_add_column_4148('mall_commission','settlement_status','`settlement_status` VARCHAR(24) NOT NULL DEFAULT ''待结算'' AFTER `status`');
CALL mall_add_column_4148('mall_commission','reversed_time','`reversed_time` DATETIME DEFAULT NULL AFTER `settle_time`');
CALL mall_add_column_4148('mall_commission','reverse_reason','`reverse_reason` VARCHAR(255) DEFAULT '''' AFTER `reversed_time`');
CALL mall_add_column_4148('mall_withdrawal','request_no','`request_no` VARCHAR(80) DEFAULT NULL AFTER `withdrawal_no`');
CALL mall_add_column_4148('mall_withdrawal','fee_amount','`fee_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER `amount`');
CALL mall_add_column_4148('mall_withdrawal','arrival_amount','`arrival_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER `fee_amount`');
CALL mall_add_column_4148('mall_withdrawal','reviewer_id','`reviewer_id` BIGINT DEFAULT NULL AFTER `admin_remark`');
CALL mall_add_column_4148('mall_withdrawal','processed_time','`processed_time` DATETIME DEFAULT NULL AFTER `review_time`');
CALL mall_add_column_4148('mall_order','coupon_id','`coupon_id` BIGINT DEFAULT NULL AFTER `discount_amount`');
CALL mall_add_column_4148('mall_order','coupon_amount','`coupon_amount` DECIMAL(10,2) NOT NULL DEFAULT 0 AFTER `coupon_id`');
CALL mall_add_column_4148('mall_service_ticket','reply_time','`reply_time` DATETIME DEFAULT NULL AFTER `reply`');
CALL mall_add_column_4148('mall_service_ticket','handler_id','`handler_id` BIGINT DEFAULT NULL AFTER `reply_time`');
DROP PROCEDURE IF EXISTS mall_add_column_4148;

DROP PROCEDURE IF EXISTS mall_add_index_4148;
DELIMITER $$
CREATE PROCEDURE mall_add_index_4148(IN t VARCHAR(64), IN i VARCHAR(64), IN d TEXT)
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=t AND INDEX_NAME=i) THEN
    SET @s=CONCAT('ALTER TABLE `',t,'` ADD ',d); PREPARE x FROM @s; EXECUTE x; DEALLOCATE PREPARE x;
  END IF;
END$$
DELIMITER ;
CALL mall_add_index_4148('mall_withdrawal','uk_withdraw_request_customer','UNIQUE KEY `uk_withdraw_request_customer` (`request_no`,`customer_id`)');
DROP PROCEDURE IF EXISTS mall_add_index_4148;

CREATE TABLE IF NOT EXISTS mall_commission_rule(
 id BIGINT NOT NULL AUTO_INCREMENT, rule_name VARCHAR(64) NOT NULL, level_no TINYINT NOT NULL,
 rate DECIMAL(8,6) NOT NULL, min_order_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 effective_from DATETIME NOT NULL, effective_to DATETIME DEFAULT NULL, status CHAR(1) NOT NULL DEFAULT '0',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_commission_rule_name(rule_name), KEY idx_commission_rule_active(status,level_no,effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='佣金规则';

CREATE TABLE IF NOT EXISTS mall_commission_ledger(
 id BIGINT NOT NULL AUTO_INCREMENT, ledger_no VARCHAR(40) NOT NULL, commission_id BIGINT DEFAULT NULL,
 customer_id BIGINT NOT NULL, business_type VARCHAR(32) NOT NULL, amount DECIMAL(12,2) NOT NULL,
 available_before DECIMAL(12,2) NOT NULL, available_after DECIMAL(12,2) NOT NULL,
 pending_before DECIMAL(12,2) NOT NULL, pending_after DECIMAL(12,2) NOT NULL,
 frozen_before DECIMAL(12,2) NOT NULL, frozen_after DECIMAL(12,2) NOT NULL,
 business_no VARCHAR(80) NOT NULL, remark VARCHAR(255) DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_commission_ledger_business(business_type,business_no,customer_id),
 KEY idx_commission_ledger_customer(customer_id,create_time), KEY idx_commission_ledger_commission(commission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可删除佣金账本';

CREATE TABLE IF NOT EXISTS mall_withdrawal_config(
 id BIGINT NOT NULL AUTO_INCREMENT, config_name VARCHAR(64) NOT NULL, min_amount DECIMAL(12,2) NOT NULL,
 max_amount DECIMAL(12,2) NOT NULL, fee_rate DECIMAL(8,6) NOT NULL DEFAULT 0, fee_fixed DECIMAL(12,2) NOT NULL DEFAULT 0,
 arrival_days VARCHAR(32) NOT NULL DEFAULT '1-3日', account_types VARCHAR(128) NOT NULL DEFAULT '微信,银行卡',
 status CHAR(1) NOT NULL DEFAULT '0', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_withdraw_config_name(config_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现配置';

CREATE TABLE IF NOT EXISTS mall_withdrawal_audit(
 id BIGINT NOT NULL AUTO_INCREMENT, withdrawal_id BIGINT NOT NULL, from_status VARCHAR(24) NOT NULL,
 to_status VARCHAR(24) NOT NULL, operator_type VARCHAR(16) NOT NULL, operator_id BIGINT DEFAULT NULL,
 opinion VARCHAR(255) DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), KEY idx_withdraw_audit(withdrawal_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现审核轨迹';

CREATE TABLE IF NOT EXISTS mall_withdrawal_request(
 request_no VARCHAR(80) NOT NULL, customer_id BIGINT NOT NULL, withdrawal_no VARCHAR(40) DEFAULT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(request_no,customer_id), KEY idx_withdraw_request_withdrawal(withdrawal_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现请求幂等串行锁';

CREATE TABLE IF NOT EXISTS mall_coupon_template(
 id BIGINT NOT NULL AUTO_INCREMENT, template_no VARCHAR(40) NOT NULL, name VARCHAR(80) NOT NULL,
 coupon_type VARCHAR(24) NOT NULL DEFAULT '满减券', discount_amount DECIMAL(10,2) NOT NULL,
 min_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0, valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 scope_type VARCHAR(24) NOT NULL DEFAULT '全场', scope_value VARCHAR(255) DEFAULT '', total_qty INT NOT NULL DEFAULT 0,
 issued_qty INT NOT NULL DEFAULT 0, per_user_limit INT NOT NULL DEFAULT 1, stack_order INT NOT NULL DEFAULT 10,
 status CHAR(1) NOT NULL DEFAULT '0', description VARCHAR(255) DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_coupon_template_no(template_no), KEY idx_coupon_template_active(status,valid_from,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

CREATE TABLE IF NOT EXISTS mall_customer_coupon(
 id BIGINT NOT NULL AUTO_INCREMENT, coupon_no VARCHAR(40) NOT NULL, template_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT '未使用', locked_order_id BIGINT DEFAULT NULL, used_order_id BIGINT DEFAULT NULL,
 received_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, locked_time DATETIME DEFAULT NULL, used_time DATETIME DEFAULT NULL,
 invalid_time DATETIME DEFAULT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_customer_coupon_no(coupon_no), KEY idx_customer_coupon(customer_id,status),
 KEY idx_customer_coupon_order(locked_order_id,used_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

CREATE TABLE IF NOT EXISTS mall_coupon_log(
 id BIGINT NOT NULL AUTO_INCREMENT, customer_coupon_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
 action_type VARCHAR(24) NOT NULL, order_id BIGINT DEFAULT NULL, business_no VARCHAR(80) NOT NULL,
 amount DECIMAL(10,2) NOT NULL DEFAULT 0, remark VARCHAR(255) DEFAULT '', create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_coupon_log_action(customer_coupon_id,action_type,business_no), KEY idx_coupon_log_customer(customer_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券发放锁定核销释放记录';

CREATE TABLE IF NOT EXISTS mall_customer_preference(
 customer_id BIGINT NOT NULL, order_notice TINYINT(1) NOT NULL DEFAULT 1, activity_notice TINYINT(1) NOT NULL DEFAULT 0,
 personalized TINYINT(1) NOT NULL DEFAULT 1, history_enabled TINYINT(1) NOT NULL DEFAULT 1,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY(customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户持久化设置';

CREATE TABLE IF NOT EXISTS mall_managed_document(
 id BIGINT NOT NULL AUTO_INCREMENT, document_key VARCHAR(48) NOT NULL, title VARCHAR(128) NOT NULL,
 content LONGTEXT NOT NULL, version VARCHAR(32) NOT NULL DEFAULT '1.0', sort_no INT NOT NULL DEFAULT 0,
 status CHAR(1) NOT NULL DEFAULT '0', publish_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_managed_document_key(document_key), KEY idx_managed_document(status,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协议隐私FAQ可管理内容';

CREATE TABLE IF NOT EXISTS mall_browse_history(
 id BIGINT NOT NULL AUTO_INCREMENT, customer_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 first_view_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_view_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 view_count INT NOT NULL DEFAULT 1, source VARCHAR(24) NOT NULL DEFAULT 'SERVER',
 PRIMARY KEY(id), UNIQUE KEY uk_browse_customer_product(customer_id,product_id), KEY idx_browse_customer_time(customer_id,last_view_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户商品浏览记录';

CREATE TABLE IF NOT EXISTS mall_login_attempt(
 id BIGINT NOT NULL AUTO_INCREMENT, phone_hash CHAR(64) NOT NULL, client_key VARCHAR(64) NOT NULL,
 success TINYINT(1) NOT NULL DEFAULT 0, attempt_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), KEY idx_login_attempt(phone_hash,client_key,attempt_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录频率审计（不保存明文手机号）';

CREATE TABLE IF NOT EXISTS mall_revoked_session(
 token_hash CHAR(64) NOT NULL, revoked_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_time DATETIME NOT NULL DEFAULT (DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 31 DAY)),
 PRIMARY KEY(token_hash), KEY idx_revoked_session_expire(expires_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已轮换或退出的商城会话';

INSERT IGNORE INTO mall_commission_rule(rule_name,level_no,rate,min_order_amount,effective_from,status) VALUES
 ('一级订单佣金',1,0.100000,0,'2026-01-01 00:00:00','0'),('二级订单佣金',2,0.050000,0,'2026-01-01 00:00:00','0');
INSERT IGNORE INTO mall_withdrawal_config(config_name,min_amount,max_amount,fee_rate,fee_fixed,arrival_days,account_types,status)
 VALUES('默认提现规则',10,50000,0,0,'1-3日','微信,银行卡','0');
INSERT IGNORE INTO mall_managed_document(document_key,title,content,version,sort_no,status) VALUES
 ('USER_AGREEMENT','用户协议','使用商城前请确认具备相应民事行为能力；账号仅限本人使用，商品价格、库存、配送与售后以服务端实时数据为准。','1.0',1,'0'),
 ('PRIVACY_POLICY','隐私政策','我们仅在账户、订单、配送、售后和安全风控所必需的范围内处理个人信息。','1.0',2,'0'),
 ('FAQ_ORDER','如何查看订单物流？','进入我的订单，选择已发货订单后点击查看物流。','1.0',10,'0'),
 ('FAQ_POINTS','积分什么时候到账？','订单完成后按真实实付金额和当前规则发放积分。','1.0',11,'0'),
 ('FAQ_AFTERSALE','如何申请售后？','进入订单详情或物流与售后页，选择售后类型并提交。','1.0',12,'0');
INSERT IGNORE INTO mall_coupon_template(template_no,name,coupon_type,discount_amount,min_order_amount,valid_from,valid_to,scope_type,total_qty,per_user_limit,status,description)
 VALUES('CP-WELCOME-20','新人专享券','满减券',20,158,'2026-01-01 00:00:00','2030-12-31 23:59:59','全场',100000,1,'0','订单商品金额满158元可使用');

UPDATE mall_commission SET commission_no=CONCAT('CM',DATE_FORMAT(create_time,'%Y%m%d'),LPAD(id,10,'0')) WHERE commission_no IS NULL;
UPDATE mall_commission SET base_amount=ROUND(amount/NULLIF(rate,0),2),settlement_status=status WHERE base_amount=0;

INSERT IGNORE INTO sys_menu VALUES
 (5046,'佣金账本',5000,20,'commission-ledger','mall/commission-ledger/index','','',1,0,'C','0','0','mall:commission-ledger:list','money','admin',NOW(),'',NULL,'佣金规则、账本和冲正'),
 (5047,'提现配置',5000,21,'withdraw-config','mall/withdraw-config/index','','',1,0,'C','0','0','mall:withdraw-config:list','tool','admin',NOW(),'',NULL,'提现规则和审核轨迹'),
 (5048,'优惠券管理',5000,22,'coupon-template','mall/coupon-template/index','','',1,0,'C','0','0','mall:coupon:list','ticket','admin',NOW(),'',NULL,'优惠券模板、发放与核销'),
 (5049,'商城内容与反馈',5000,23,'support-content','mall/support-content/index','','',1,0,'C','0','0','mall:support:list','documentation','admin',NOW(),'',NULL,'协议、FAQ、工单和浏览记录'),
 (5146,'佣金规则修改',5046,1,'#','','','',1,0,'F','0','0','mall:commission-ledger:edit','#','admin',NOW(),'',NULL,''),
 (5147,'提现规则与审核',5047,1,'#','','','',1,0,'F','0','0','mall:withdraw-config:edit','#','admin',NOW(),'',NULL,''),
 (5148,'优惠券修改发放',5048,1,'#','','','',1,0,'F','0','0','mall:coupon:edit','#','admin',NOW(),'',NULL,''),
 (5149,'内容与反馈处理',5049,1,'#','','','',1,0,'F','0','0','mall:support:edit','#','admin',NOW(),'',NULL,'');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 1,menu_id FROM sys_menu WHERE menu_id IN(5046,5047,5048,5049,5146,5147,5148,5149);
