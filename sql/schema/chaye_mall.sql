-- Tea mall business schema, seed data and administration menus.
-- All statements are idempotent so this file can be applied during every deployment.

CREATE TABLE IF NOT EXISTS mall_customer (
  id BIGINT NOT NULL AUTO_INCREMENT,
  nickname VARCHAR(64) NOT NULL,
  phone VARCHAR(32) DEFAULT '',
  points INT NOT NULL DEFAULT 0,
  status CHAR(1) NOT NULL DEFAULT '0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城客户';

CREATE TABLE IF NOT EXISTS mall_product (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  short_name VARCHAR(64) NOT NULL,
  spec VARCHAR(64) DEFAULT '',
  price DECIMAL(10,2) NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  stock INT NOT NULL DEFAULT 0,
  sales INT NOT NULL DEFAULT 0,
  image_key VARCHAR(64) DEFAULT '',
  description TEXT,
  origin VARCHAR(128) DEFAULT '',
  grade_name VARCHAR(64) DEFAULT '',
  shelf_life VARCHAR(64) DEFAULT '',
  status CHAR(1) NOT NULL DEFAULT '0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mall_product_status (status, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城商品';

CREATE TABLE IF NOT EXISTS mall_cart (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  qty INT NOT NULL DEFAULT 1,
  checked TINYINT(1) NOT NULL DEFAULT 1,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_cart_customer_product (customer_id, product_id),
  KEY idx_mall_cart_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

CREATE TABLE IF NOT EXISTS mall_address (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  receiver_name VARCHAR(64) NOT NULL,
  receiver_phone VARCHAR(32) NOT NULL,
  region VARCHAR(255) NOT NULL,
  detail_address VARCHAR(255) NOT NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mall_address_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址';

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

CREATE TABLE IF NOT EXISTS mall_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待付款',
  total_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  reward_points INT NOT NULL DEFAULT 0,
  receiver_name VARCHAR(64) DEFAULT '',
  receiver_phone VARCHAR(32) DEFAULT '',
  receiver_address VARCHAR(512) DEFAULT '',
  carrier VARCHAR(64) DEFAULT '顺丰速运',
  tracking_no VARCHAR(64) DEFAULT '',
  remark VARCHAR(255) DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_order_no (order_no),
  KEY idx_mall_order_customer (customer_id, create_time),
  KEY idx_mall_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城订单';

CREATE TABLE IF NOT EXISTS mall_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_name VARCHAR(128) NOT NULL,
  spec VARCHAR(64) DEFAULT '',
  price DECIMAL(10,2) NOT NULL DEFAULT 0,
  qty INT NOT NULL DEFAULT 1,
  image_key VARCHAR(64) DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_mall_order_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

CREATE TABLE IF NOT EXISTS mall_logistics (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  status_name VARCHAR(64) NOT NULL,
  description VARCHAR(512) DEFAULT '',
  event_time DATETIME NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_mall_logistics_order (order_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹';

CREATE TABLE IF NOT EXISTS mall_aftersale (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aftersale_no VARCHAR(32) NOT NULL,
  order_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  type_name VARCHAR(32) NOT NULL,
  reason VARCHAR(255) DEFAULT '',
  status VARCHAR(24) NOT NULL DEFAULT '待处理',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_aftersale_no (aftersale_no),
  KEY idx_mall_aftersale_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后申请';

CREATE TABLE IF NOT EXISTS mall_reward (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  points INT NOT NULL DEFAULT 0,
  stock INT NOT NULL DEFAULT 0,
  stock_unit VARCHAR(16) DEFAULT '件',
  image_key VARCHAR(64) DEFAULT '',
  status CHAR(1) NOT NULL DEFAULT '0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分商品';

CREATE TABLE IF NOT EXISTS mall_exchange (
  id BIGINT NOT NULL AUTO_INCREMENT,
  exchange_no VARCHAR(32) NOT NULL,
  customer_id BIGINT NOT NULL,
  reward_id BIGINT NOT NULL,
  qty INT NOT NULL DEFAULT 1,
  points_cost INT NOT NULL DEFAULT 0,
  address_id BIGINT DEFAULT NULL,
  status VARCHAR(24) NOT NULL DEFAULT '待发货',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_exchange_no (exchange_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分兑换';

CREATE TABLE IF NOT EXISTS mall_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mall_favorite_customer_product (customer_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品收藏';

CREATE TABLE IF NOT EXISTS mall_points_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  title VARCHAR(64) NOT NULL,
  description VARCHAR(255) DEFAULT '',
  amount INT NOT NULL,
  balance INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mall_points_customer (customer_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分流水';

INSERT IGNORE INTO mall_customer (id, nickname, phone, points) VALUES
  (1, '茶友', '13888888888', 2182);

INSERT IGNORE INTO mall_product
  (id, category, name, short_name, spec, price, reward_points, stock, sales, image_key, description, origin, grade_name, shelf_life, status)
VALUES
  (1, '绿茶', '明前西湖龙井 100g', '明前龙井 100g', '100g', 268, 268, 86, 328, 'longjing-pale', '清明前采制，芽叶细嫩，豆香清雅。', '浙江杭州西湖产区', '特级', '18个月', '0'),
  (2, '绿茶', '雨前西湖龙井 100g', '雨前龙井 100g', '100g', 168, 168, 126, 286, 'longjing-dark', '谷雨前采制，滋味鲜醇，耐泡度佳。', '浙江杭州', '一级', '18个月', '0'),
  (3, '礼盒', '龙井茶叶礼盒 250g', '龙井礼盒', '250g', 398, 398, 48, 156, 'tea-gift', '双罐礼盒包装，适合节庆赠礼。', '浙江杭州', '特级', '18个月', '0'),
  (4, '绿茶', '特级西湖龙井 50g', '特级龙井 50g', '50g', 188, 188, 62, 235, 'biluochun', '核心产区鲜叶，手工辉锅，鲜爽回甘。', '浙江杭州西湖产区', '特级', '18个月', '0'),
  (5, '红茶', '正山小种红茶 125g', '正山小种', '125g', 128, 138, 95, 219, 'blacktea-red', '松烟香与桂圆甜香协调，汤色橙红。', '福建武夷山', '一级', '24个月', '0'),
  (6, '绿茶', '碧螺春绿茶 250g', '碧螺春', '250g', 198, 198, 74, 183, 'biluochun', '条索纤细，花果香显，滋味鲜爽。', '江苏苏州', '一级', '18个月', '0'),
  (7, '绿茶', '黄山毛峰 250g', '黄山毛峰', '250g', 198, 198, 69, 168, 'maofeng-pouch', '形似雀舌，白毫显露，清香持久。', '安徽黄山', '一级', '18个月', '0'),
  (8, '茶具', '宜兴紫砂壶 西施壶', '紫砂壶', '200ml', 398, 398, 33, 92, 'yixing-pot', '原矿紫泥，经典西施器型，适合功夫茶。', '江苏宜兴', '工艺品', '长期', '0');

INSERT IGNORE INTO mall_cart (id, customer_id, product_id, qty, checked) VALUES
  (1, 1, 1, 1, 1), (2, 1, 4, 1, 1);

INSERT IGNORE INTO mall_address
  (id, customer_id, receiver_name, receiver_phone, region, detail_address, is_default)
VALUES
  (1, 1, '张三', '13888888888', '浙江省 杭州市 西湖区 文一西路 588号', '未来科技城 海创园 10幢 6楼 601室', 1),
  (2, 1, '李四', '13900001234', '江苏省 苏州市 工业园区 星港街 199号', '星湖大厦 2幢 1203室', 0),
  (3, 1, '王五', '13700005678', '广东省 深圳市 南山区 科苑路 15号', '科兴科学园 C栋 8楼 808室', 0);

INSERT IGNORE INTO mall_order
  (id, order_no, customer_id, status, total_amount, reward_points, receiver_name, receiver_phone, receiver_address, carrier, tracking_no, create_time)
VALUES
  (1, '2024052312345678', 1, '待发货', 168, 168, '张三', '13888888888', '浙江省杭州市西湖区文一西路588号', '顺丰速运', 'SF1234567890123', '2024-05-23 12:34:56'),
  (2, '2024052011223344', 1, '待收货', 128, 138, '李四', '13900001234', '江苏省苏州市工业园区星港街199号', '顺丰速运', 'SF9876543210123', '2024-05-20 11:22:33'),
  (3, '2024051510102233', 1, '已完成', 138, 138, '王五', '13700005678', '广东省深圳市南山区科苑路15号', '顺丰速运', 'SF5556667778888', '2024-05-15 10:10:22');

INSERT IGNORE INTO mall_order_item
  (id, order_id, product_id, product_name, spec, price, qty, image_key)
VALUES
  (1, 1, 1, '明前龙井茶 2024新茶', '100g / 罐', 168, 1, 'longjing-pale'),
  (2, 2, 5, '正山小种红茶', '125g / 罐', 128, 1, 'blacktea-red'),
  (3, 3, 6, '碧螺春绿茶', '100g / 罐', 138, 1, 'biluochun');

INSERT IGNORE INTO mall_logistics
  (id, order_id, status_name, description, event_time, sort_no)
VALUES
  (1, 2, '已发货', '包裹已从【杭州转运中心】发出', '2024-05-23 15:30:00', 1),
  (2, 2, '运输中', '包裹已到达【上海转运中心】', '2024-05-23 20:45:00', 2),
  (3, 2, '派送中', '快件派送中，派送员：张师傅\n联系电话：138 1234 5678', '2024-05-24 09:12:00', 3),
  (4, 2, '已签收', '包裹已签收，感谢使用顺丰速运', '2024-05-24 11:08:00', 4);

INSERT IGNORE INTO mall_reward (id, name, points, stock, stock_unit, image_key, status) VALUES
  (21, '手工白瓷茶杯', 800, 36, '件', 'porcelain-cup', '0'),
  (22, '便携旅行茶具套装', 1200, 18, '套', 'travel-set', '0'),
  (23, '经典茶样组合（4款）', 600, 58, '份', 'tea-gift', '0'),
  (24, '茶山帆布袋', 400, 72, '个', 'canvas-tote', '0'),
  (25, '清香绿茶礼罐', 680, 43, '罐', 'biluochun', '0'),
  (26, '胡桃木茶盘', 980, 21, '个', 'wood-tray', '0');

INSERT IGNORE INTO mall_favorite (id, customer_id, product_id) VALUES
  (1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4);

INSERT IGNORE INTO mall_points_log (id, customer_id, title, description, amount, balance, create_time) VALUES
  (1, 1, '购买商品', '订单号：20240612012345', 268, 1860, '2024-06-12 10:00:00'),
  (2, 1, '邀请好友下单', '好友：茶友小李', 200, 1592, '2024-06-10 10:00:00'),
  (3, 1, '评价商品', '订单号：20240609098765', 10, 1392, '2024-06-09 10:00:00'),
  (4, 1, '兑换茶具', '手工白瓷茶杯', -800, 1382, '2024-06-08 10:00:00'),
  (5, 1, '每日签到', '', 10, 2182, '2024-06-07 10:00:00');

START TRANSACTION;

INSERT IGNORE INTO sys_menu VALUES
  (5000, '商城管理', 0, 1, 'mall', NULL, '', '', 1, 0, 'M', '0', '0', '', 'shopping', 'admin', NOW(), '', NULL, '茶叶商城业务管理'),
  (5001, '商品管理', 5000, 1, 'product', 'mall/product/index', '', '', 1, 0, 'C', '0', '0', 'mall:product:list', 'shopping', 'admin', NOW(), '', NULL, '商城商品管理'),
  (5002, '订单管理', 5000, 2, 'order', 'mall/order/index', '', '', 1, 0, 'C', '0', '0', 'mall:order:list', 'list', 'admin', NOW(), '', NULL, '商城订单管理'),
  (5003, '售后管理', 5000, 3, 'aftersale', 'mall/aftersale/index', '', '', 1, 0, 'C', '0', '0', 'mall:aftersale:list', 'guide', 'admin', NOW(), '', NULL, '商城售后管理'),
  (5004, '积分商品', 5000, 4, 'reward', 'mall/reward/index', '', '', 1, 0, 'C', '0', '0', 'mall:reward:list', 'star', 'admin', NOW(), '', NULL, '积分商品管理'),
  (5005, '商城会员', 5000, 5, 'customer', 'mall/customer/index', '', '', 1, 0, 'C', '0', '0', 'mall:customer:list', 'user', 'admin', NOW(), '', NULL, '前台注册会员与积分数据');

INSERT IGNORE INTO sys_menu VALUES
  (5101, '商品修改', 5001, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:product:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5102, '订单修改', 5002, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:order:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5103, '售后修改', 5003, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:aftersale:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5104, '积分商品修改', 5004, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:reward:edit', '#', 'admin', NOW(), '', NULL, ''),
  (5105, '客户积分修改', 5005, 1, '#', '', '', '', 1, 0, 'F', '0', '0', 'mall:customer:edit', '#', 'admin', NOW(), '', NULL, '');

COMMIT;
