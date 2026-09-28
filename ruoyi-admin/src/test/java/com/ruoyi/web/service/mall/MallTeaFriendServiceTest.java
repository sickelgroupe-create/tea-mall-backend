package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.web.controller.mall.MallTeaFriendAdminController;

class MallTeaFriendServiceTest {
 @Test void fractionalScoreRemainsExact(){assertEquals(new BigDecimal("10.00"),MallTeaFriendService.positive("2.50","邀请分").multiply(BigDecimal.valueOf(4)));}
 @Test void invalidAmountsCannotEnterLedger(){for(String v:new String[]{"0","-1","2.501","1000000"})assertThrows(IllegalArgumentException.class,()->MallTeaFriendService.positive(v,"邀请分"));}
 @Test void rejectsMissingOrClientTruncatedIdempotencyKeys(){for(String v:new String[]{"","short","../../../../bad-key"})assertThrows(IllegalArgumentException.class,()->MallTeaFriendService.requestKey(v));assertEquals("exchange_12345678",MallTeaFriendService.requestKey("exchange_12345678"));}
 @Test void lowRatioLargeOrFrequentExchangesNeedReview(){
  BigDecimal cost=new BigDecimal("5"),large=new BigDecimal("100"),ratio=new BigDecimal("30");
  assertFalse(MallTeaFriendService.requiresReview(cost,large,0,5,3,10,ratio));
  assertFalse(MallTeaFriendService.requiresReview(cost,large,0,5,2,10,ratio));
  assertFalse(MallTeaFriendService.requiresReview(large,large,0,5,10,10,ratio));
  assertFalse(MallTeaFriendService.requiresReview(cost,large,5,5,10,10,ratio));
  assertTrue(MallTeaFriendService.requiresReview(large,large,0,5,2,10,ratio));
  assertTrue(MallTeaFriendService.requiresReview(cost,large,5,5,2,10,ratio));
  assertTrue(MallTeaFriendService.requiresReview(cost,null,0,null,10,10,ratio));
 }
 @Test void readAndMutationsRequireAppropriatePermissions()throws Exception{
  assertEquals("@ss.hasPermi('mall:invite-gift:list')",MallTeaFriendAdminController.class.getMethod("data").getAnnotation(PreAuthorize.class).value());
  assertEquals("@ss.hasPermi('mall:invite-gift:edit')",MallTeaFriendAdminController.class.getMethod("save",Map.class).getAnnotation(PreAuthorize.class).value());
  assertEquals("@ss.hasPermi('mall:invite-gift:edit')",MallTeaFriendAdminController.class.getMethod("review",Long.class,Map.class).getAnnotation(PreAuthorize.class).value());
 }
}
