-- 仅调整后台菜单文案，避免将前台商城会员与 sys_user 后台管理员混淆。
-- 不修改会员、订单、积分或积分流水数据。
UPDATE sys_menu
SET menu_name = '商城会员', remark = '前台注册会员与积分数据'
WHERE menu_id = 5005 AND path = 'customer';
