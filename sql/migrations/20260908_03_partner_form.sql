CREATE TABLE IF NOT EXISTS mall_partner_form_config (
 id TINYINT NOT NULL PRIMARY KEY,
 title VARCHAR(60) NOT NULL,
 intro VARCHAR(500) NOT NULL,
 submit_text VARCHAR(20) NOT NULL,
 footer_text VARCHAR(100) NOT NULL,
 fields_json TEXT NOT NULL,
 version_no BIGINT NOT NULL DEFAULT 1,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO mall_partner_form_config(id,title,intro,submit_text,footer_text,fields_json) VALUES(1,'申请合伙人','请填写联系信息，选填资料可稍后补充。','提交申请','真诚分享 · 长久同行','[{"key":"realName","label":"真实姓名","required":true,"min":2,"max":64,"placeholder":"请输入真实姓名"},{"key":"idNo","label":"身份证号码","required":false,"min":15,"max":18,"placeholder":"选填，仅用于资质审核"},{"key":"region","label":"所在地区","required":false,"min":2,"max":128,"placeholder":"省 / 市 / 区"},{"key":"address","label":"详细地址","required":false,"min":3,"max":255,"placeholder":"选填，街道、门牌号"},{"key":"phone","label":"联系电话","required":true,"min":11,"max":11,"type":"number","placeholder":"11位手机号"},{"key":"reason","label":"申请理由","required":false,"min":5,"max":500,"textarea":true,"placeholder":"选填，请介绍申请理由"}]');
CREATE TABLE IF NOT EXISTS mall_partner_form_audit (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,operator_id BIGINT NOT NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO mall_schema_migration(version_no,description) VALUES('20260908_03','合伙人申请表单文案与必填项后台配置') ON DUPLICATE KEY UPDATE description=VALUES(description);
