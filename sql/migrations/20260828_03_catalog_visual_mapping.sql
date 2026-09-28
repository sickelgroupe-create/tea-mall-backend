-- 01-08 视觉收敛：仅修正系统内置专题的首页展示素材映射。
-- 仅在仍为旧默认素材时更新，保留管理员已经自定义的专题图。
-- 允许重复执行；不删除业务数据；仅允许先在隔离测试库验证。

SET NAMES utf8mb4;

UPDATE mall_topic
SET hero_image_url='/static/images/oolong-category-v1.webp'
WHERE slug='spring-selection'
  AND hero_image_url='/static/images/tea-garden-hero.png';

INSERT INTO mall_schema_migration(version_no,description)
VALUES('20260828_03','01-08内置专题视觉素材映射')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SELECT slug,hero_image_url
FROM mall_topic
WHERE slug='spring-selection';

