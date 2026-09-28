package com.ruoyi.web.service.mall;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for storefront order transitions.
 * Payment is deliberately not an admin status edit: only the payment service may
 * move an unpaid order from 待付款 to 待发货.
 */
public final class MallOrderStateMachine
{
    public static final String WAITING_PAYMENT = "待付款";
    public static final String WAITING_SHIPMENT = "待发货";
    public static final String WAITING_RECEIPT = "待收货";
    public static final String COMPLETED = "已完成";
    public static final String AFTERSALE = "售后中";
    public static final String PARTIAL_AFTERSALE = "部分售后完成";
    public static final String REFUNDED = "已退款";
    public static final String CANCELLED = "已取消";
    public static final String CLOSED = "已关闭";

    public static final String PAYMENT_PENDING = "待支付";
    public static final String PAYMENT_PAID = "已支付";
    public static final String PAYMENT_REFUNDED = "已退款";

    private static final Set<String> KNOWN_ORDER_STATUSES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(WAITING_PAYMENT, WAITING_SHIPMENT,
                    WAITING_RECEIPT, COMPLETED, AFTERSALE, PARTIAL_AFTERSALE,
                    REFUNDED, CANCELLED, CLOSED)));

    private MallOrderStateMachine()
    {
    }

    public static void requirePublicTransition(String oldStatus, String newStatus)
    {
        requireKnown(oldStatus);
        requireKnown(newStatus);
        if (oldStatus.equals(newStatus))
        {
            return;
        }
        boolean allowed = WAITING_PAYMENT.equals(oldStatus) && CANCELLED.equals(newStatus)
                || WAITING_RECEIPT.equals(oldStatus) && COMPLETED.equals(newStatus);
        if (!allowed)
        {
            throw new IllegalArgumentException("用户订单状态只能执行 待付款→已取消 或 待收货→已完成");
        }
    }

    public static void requireAdminTransition(String oldStatus, String newStatus)
    {
        requireKnown(oldStatus);
        requireKnown(newStatus);
        if (oldStatus.equals(newStatus))
        {
            return;
        }
        boolean allowed = WAITING_PAYMENT.equals(oldStatus) && CANCELLED.equals(newStatus)
                || WAITING_SHIPMENT.equals(oldStatus) && WAITING_RECEIPT.equals(newStatus)
                || WAITING_RECEIPT.equals(oldStatus) && COMPLETED.equals(newStatus);
        if (!allowed)
        {
            throw new IllegalArgumentException(
                    "后台只能取消未付款订单、发货或完成收货；付款必须经过支付接口，售后必须在售后管理处理");
        }
    }

    public static void requirePayable(String orderStatus, String paymentStatus)
    {
        requireKnown(orderStatus);
        if (!WAITING_PAYMENT.equals(orderStatus) || !PAYMENT_PENDING.equals(paymentStatus))
        {
            throw new IllegalArgumentException("订单不是可支付的待付款状态");
        }
    }

    public static void requireShippable(String orderStatus, String paymentStatus)
    {
        requireKnown(orderStatus);
        if (!WAITING_SHIPMENT.equals(orderStatus) || !PAYMENT_PAID.equals(paymentStatus))
        {
            throw new IllegalArgumentException("只有已支付的待发货订单可以发货");
        }
    }

    private static void requireKnown(String status)
    {
        if (!KNOWN_ORDER_STATUSES.contains(status))
        {
            throw new IllegalArgumentException("未知订单状态：" + status);
        }
    }
}
