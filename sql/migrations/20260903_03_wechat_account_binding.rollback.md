# 20260903_03 回滚说明

应用回滚后可以保留 `mall_wechat_login_ticket`，旧代码不会读取该表。必须删除时先停止微信登录流量，确认没有未过期且未消费的凭据，再备份并删除该表与 `mall_schema_migration` 中的 `20260903_03` 记录。

该回滚不会删除 `mall_customer_identity` 中已经绑定的微信身份，避免破坏用户账号归属。
