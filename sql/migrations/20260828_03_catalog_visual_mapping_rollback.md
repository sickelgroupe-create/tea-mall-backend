# 20260828_03 回滚说明

1. 回滚前先备份目标数据库，并确认 `spring-selection` 仍使用本迁移设置的 `/static/images/oolong-category-v1.webp`。
2. 如果管理员在迁移后重新设置过专题图，不执行回滚，保留管理员值。
3. 对仍为本迁移默认值的记录执行：

```sql
UPDATE mall_topic
SET hero_image_url='/static/images/tea-garden-hero.png'
WHERE slug='spring-selection'
  AND hero_image_url='/static/images/oolong-category-v1.webp';
DELETE FROM mall_schema_migration WHERE version_no='20260828_03';
```

本迁移不创建或删除业务表，不影响商品、订单、购物车和用户数据。

