CREATE TABLE IF NOT EXISTS mall_service_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL,
  category VARCHAR(32) NOT NULL,
  content VARCHAR(500) NOT NULL,
  contact VARCHAR(64) DEFAULT '',
  status VARCHAR(24) NOT NULL DEFAULT '待处理',
  reply VARCHAR(500) DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_service_ticket_no (ticket_no),
  KEY idx_mall_service_ticket_customer (customer_id),
  KEY idx_mall_service_ticket_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城在线服务工单';
