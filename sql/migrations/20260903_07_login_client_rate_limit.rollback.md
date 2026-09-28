# 20260903_07 回滚说明

该迁移仅新增登录失败查询索引，不修改业务记录。需要回滚时，在确认没有登录压力后执行：

`ALTER TABLE mall_login_attempt DROP INDEX idx_login_attempt_client_time;`

随后删除 `mall_schema_migration` 中 `version_no='20260903_07'` 的记录。回滚不会删除登录审计数据。
