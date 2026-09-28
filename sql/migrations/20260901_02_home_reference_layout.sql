-- 首页参考图布局与兑换中心文案修正。
-- 仅修正上一版迁移写入的默认文案；运营人员已经自定义的内容不会被覆盖。
-- 不修改、压缩或转码任何图片，可重复执行。
SET NAMES utf8mb4;

UPDATE mall_page_module
SET english_title='新品臻享',
    title='春日好茶 · 限时甄选',
    subtitle='西湖产区当季鲜采，茶香清雅，回甘悠长。',
    description='点击整张宣传图进入兑换中心。',
    jump_type='route',
    jump_target='exchangeCenter',
    update_time=NOW(),
    version_no=version_no+1
WHERE module_key='home.exchange-center'
  AND english_title='MEMBER REWARDS'
  AND title='兑换中心'
  AND subtitle='购买积分与邀请奖励';

UPDATE mall_page_module
SET title='购物积分',
    subtitle='购买商品获得积分',
    update_time=NOW(),
    version_no=version_no+1
WHERE module_key='exchange.points'
  AND title='购买积分';
