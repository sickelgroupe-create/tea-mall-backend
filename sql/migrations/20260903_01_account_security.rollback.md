# 20260903_01 回滚说明

先备份 `mall_login_attempt` 和 `mall_customer_security_log`。应用回滚到旧版 Java 后，按以下顺序处理：

1. 删除 `mall_schema_migration` 中 `version_no='20260903_01'` 的记录。
2. 如安全日志不再需要，确认导出后删除 `mall_customer_security_log`。
3. 删除 `mall_login_attempt` 的 `idx_login_attempt_ip_time`、`idx_login_attempt_phone_time` 索引。
4. 最后删除 `mall_login_attempt.ip_hash`。

回滚不会恢复已经安全修改过的手机号；如确需恢复，必须依据备份和安全日志人工核验用户身份后处理。
