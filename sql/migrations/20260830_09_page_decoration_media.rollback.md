# 回滚说明：商城素材与页面装修

本迁移不修改或覆盖商品、订单、库存、用户及上传文件。

安全回滚顺序：

1. 先把 `sys_menu` 中 5050、5150–5153 的 `visible` 设为 `1`，停止后台入口。
2. 将 `mall_page_module.status` 设为 `1`，前端会继续使用项目内原始默认图片与文案。
3. 保留 `mall_page_module_history` 和 `mall_media_asset` 作为审计记录；不要删除服务器 `/profile/mall-materials` 文件。
4. 仅在确认没有任何业务引用、已单独备份且明确接受丢失装修历史时，才可依次删除 `mall_page_module_history`、`mall_page_module`、`mall_media_asset`。
5. 删除菜单前先删除 `sys_role_menu` 对应关系，再删除上述菜单 ID。

生产回滚默认只执行第1、2步，不执行 DROP TABLE 或物理删除文件。
