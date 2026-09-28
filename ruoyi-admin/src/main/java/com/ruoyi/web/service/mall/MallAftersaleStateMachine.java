package com.ruoyi.web.service.mall;

/** Single source of truth for refund, return-refund and exchange transitions. */
public final class MallAftersaleStateMachine
{
    private MallAftersaleStateMachine() { }

    public static void requireAdminTransition(String oldStatus, String newStatus, String typeName)
    {
        if (oldStatus.equals(newStatus)) return;
        boolean allowed = "申请中".equals(oldStatus) && ("审核拒绝".equals(newStatus)
                        || ("仅退款".equals(typeName) && "退款处理中".equals(newStatus))
                        || (!"仅退款".equals(typeName) && "等待用户退货".equals(newStatus)))
                || "退货运输中".equals(oldStatus) && "商家已收货".equals(newStatus)
                || "商家已收货".equals(oldStatus) && (("退货退款".equals(typeName) && "退款处理中".equals(newStatus))
                        || ("换货".equals(typeName) && "换货已发出".equals(newStatus)))
                || "退款处理中".equals(oldStatus) && "退款成功".equals(newStatus)
                || "退款成功".equals(oldStatus) && "售后完成".equals(newStatus)
                || ("申请中".equals(oldStatus) || "等待用户退货".equals(oldStatus)) && "已关闭".equals(newStatus);
        if (!allowed) throw new IllegalArgumentException("售后状态不能跨级或重复回退");
    }
}
