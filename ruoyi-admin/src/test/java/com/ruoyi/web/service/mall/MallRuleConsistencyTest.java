package com.ruoyi.web.service.mall;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MallRuleConsistencyTest {
 @Test void legacyPerFriendAmountTracksRealRule(){assertEquals("每人获得10邀请分、同一好友仅计一次。",MallTeaFriendService.renderDescription("每人获得5000邀请分、同一好友仅计一次。",new BigDecimal("10"),"邀请分"));}
 @Test void templateTracksFractionalScoreAndCustomLabel(){assertEquals("每位好友奖励2.5茶友分",MallTeaFriendService.renderDescription("每位好友奖励{pointsPerFriend}{scoreUnitLabel}",new BigDecimal("2.50"),"茶友分"));}
 @Test void exchangeThresholdAndUnrelatedNumbersArePreserved(){assertEquals("每位好友奖励10邀请分。兑换需500邀请分，前10名。",MallTeaFriendService.renderDescription("每位好友奖励5000邀请分。兑换需500邀请分，前10名。",new BigDecimal("10"),"邀请分"));}
 @Test void oldCommissionRuleCannotWriteEvenWhenCalledDirectly(){assertThrows(IllegalArgumentException.class,()->new MallAccountCouponService().saveRule(1L,Collections.emptyMap()));}
}
