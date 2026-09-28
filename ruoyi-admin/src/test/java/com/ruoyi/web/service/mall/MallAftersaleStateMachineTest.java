package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class MallAftersaleStateMachineTest
{
    @Test void refundMustFollowReviewAndProcessing()
    {
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("申请中", "退款处理中", "仅退款"));
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("退款处理中", "退款成功", "仅退款"));
        assertThrows(IllegalArgumentException.class, () -> MallAftersaleStateMachine.requireAdminTransition("申请中", "退款成功", "仅退款"));
    }

    @Test void returnRefundRequiresCustomerReturnAndMerchantReceipt()
    {
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("申请中", "等待用户退货", "退货退款"));
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("退货运输中", "商家已收货", "退货退款"));
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("商家已收货", "退款处理中", "退货退款"));
        assertThrows(IllegalArgumentException.class, () -> MallAftersaleStateMachine.requireAdminTransition("等待用户退货", "退款处理中", "退货退款"));
    }

    @Test void exchangeCannotEnterRefundFlow()
    {
        assertDoesNotThrow(() -> MallAftersaleStateMachine.requireAdminTransition("商家已收货", "换货已发出", "换货"));
        assertThrows(IllegalArgumentException.class, () -> MallAftersaleStateMachine.requireAdminTransition("换货已发出", "售后完成", "换货"));
        assertThrows(IllegalArgumentException.class, () -> MallAftersaleStateMachine.requireAdminTransition("商家已收货", "退款处理中", "换货"));
    }
}
