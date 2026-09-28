package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class MallOrderStateMachineTest
{
    @Test
    void publicUserMayCancelOnlyUnpaidOrder()
    {
        assertDoesNotThrow(() -> MallOrderStateMachine.requirePublicTransition("待付款", "已取消"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requirePublicTransition("待发货", "已取消"));
    }

    @Test
    void publicUserMayConfirmOnlyShippedOrder()
    {
        assertDoesNotThrow(() -> MallOrderStateMachine.requirePublicTransition("待收货", "已完成"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requirePublicTransition("待发货", "已完成"));
    }

    @Test
    void adminCannotPretendThatUnpaidOrderWasPaid()
    {
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requireAdminTransition("待付款", "待发货"));
        assertDoesNotThrow(() -> MallOrderStateMachine.requireAdminTransition("待付款", "已取消"));
    }

    @Test
    void adminFulfilmentMustBeSequential()
    {
        assertDoesNotThrow(() -> MallOrderStateMachine.requireAdminTransition("待发货", "待收货"));
        assertDoesNotThrow(() -> MallOrderStateMachine.requireAdminTransition("待收货", "已完成"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requireAdminTransition("待发货", "已完成"));
    }

    @Test
    void paymentRequiresBothUnpaidStatuses()
    {
        assertDoesNotThrow(() -> MallOrderStateMachine.requirePayable("待付款", "待支付"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requirePayable("待付款", "已支付"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requirePayable("待发货", "待支付"));
    }

    @Test
    void shipmentRequiresPaidOrder()
    {
        assertDoesNotThrow(() -> MallOrderStateMachine.requireShippable("待发货", "已支付"));
        assertThrows(IllegalArgumentException.class,
                () -> MallOrderStateMachine.requireShippable("待发货", "待支付"));
    }
}
