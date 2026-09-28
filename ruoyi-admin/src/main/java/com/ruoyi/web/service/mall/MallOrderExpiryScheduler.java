package com.ruoyi.web.service.mall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases stock reservations held by unpaid orders after the configured window. */
@Component
public class MallOrderExpiryScheduler
{
    private static final Logger log = LoggerFactory.getLogger(MallOrderExpiryScheduler.class);

    private final MallService mallService;

    @Value("${mall.order.expiry-enabled:true}")
    private boolean enabled;

    public MallOrderExpiryScheduler(MallService mallService)
    {
        this.mallService = mallService;
    }

    @Scheduled(initialDelayString = "${mall.order.expiry-initial-delay-ms:30000}",
            fixedDelayString = "${mall.order.expiry-scan-ms:60000}")
    public void closeExpiredOrders()
    {
        if (!enabled) return;
        try
        {
            int closed = mallService.closeExpiredOrders();
            if (closed > 0) log.info("Closed {} expired mall orders and released their stock reservations", closed);
            int aftersales = mallService.closeExpiredAftersales();
            if (aftersales > 0) log.info("Closed {} expired mall aftersales and released locked quantities", aftersales);
        }
        catch (Exception ex)
        {
            log.error("Failed to close expired mall orders", ex);
        }
    }
}
