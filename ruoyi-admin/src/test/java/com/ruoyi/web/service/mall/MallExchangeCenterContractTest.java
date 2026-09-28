package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class MallExchangeCenterContractTest
{
    @Test
    void migrationContainsRequiredConcurrencyAndOwnershipConstraints() throws Exception
    {
        Path migration=Paths.get("..","sql","20260901_01_exchange_center.sql").normalize();
        String sql=new String(Files.readAllBytes(migration),StandardCharsets.UTF_8);
        assertTrue(sql.contains("UNIQUE KEY uk_points_accrual_order(order_id)"));
        assertTrue(sql.contains("UNIQUE KEY uk_points_debt_business(customer_id,business_no)"));
        assertTrue(sql.contains("home.exchange-center"));
        assertTrue(sql.contains("exchange.points"));
        assertTrue(sql.contains("exchange.invite"));
        assertTrue(sql.contains("'购物积分','购买商品获得积分'"));
    }

    @Test
    void exchangeCenterRoutesRemainIndependent() throws Exception
    {
        Path router=Paths.get("..","..","..","..","分销茶叶商城","shared","router.js").normalize();
        String source=new String(Files.readAllBytes(router),StandardCharsets.UTF_8);
        assertTrue(source.contains("exchangeCenter: \"/pages/exchange-center/exchange-center\""));
        assertTrue(source.contains("pointsCenter: \"/pages/points-center/points-center\""));
        assertTrue(source.contains("inviteRewards: \"/pages/invite-rewards/invite-rewards\""));
    }

    @Test
    void homeKeepsConfirmedTeaFriendEntryOrderAndShoppingAccess() throws Exception
    {
        Path home=Paths.get("..","..","..","..","分销茶叶商城","pages","index","index.vue").normalize();
        String source=new String(Files.readAllBytes(home),StandardCharsets.UTF_8);
        assertTrue(source.indexOf("tf-home-hero") < source.indexOf("tf-home-entries"));
        assertTrue(source.indexOf("tf-home-entries") < source.indexOf("tf-home-products"));
        assertTrue(source.contains("go('invite')"));
        assertTrue(source.contains("go('cart')"));
        assertTrue(source.contains("go('category')"));
        assertTrue(source.contains("go('productList')"));
        assertTrue(source.contains("measureProducts"));
    }
}
