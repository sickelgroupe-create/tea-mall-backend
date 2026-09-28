# 数据库脚本执行说明

所有数据库脚本集中在本目录：

- `schema/`：空库初始化脚本和基础数据。`bootstrap.example.sql` 使用 BCrypt 占位符；`ry_20250522.sql` 已去除内置密码哈希。原始本地副本若存在 `.local.sql`，不会提交。
- `migrations/`：按日期和编号排序的增量迁移；同名 `.rollback.md` 是回滚说明。
- `tests/`：验收/集成测试夹具，不用于生产初始化。

空库建议先执行 `schema/bootstrap.example.sql`，替换 `!SET_BCRYPT_HASH_BEFORE_IMPORT!` 后再执行商城迁移。已有数据库只执行缺失迁移，并先备份。正式环境的数据库密码、微信 AppSecret、短信/支付密钥必须通过环境变量或部署平台注入。

当前迁移顺序以文件名前缀为准，从 `20260825_...` 到 `20260908_...` 依次执行。
