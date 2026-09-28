package com.ruoyi.web.service.mall;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.ruoyi.common.core.domain.model.LoginUser;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallWithdrawalReviewDatabaseTest {
 @Test void completionIsOnceOnlyAndRefundDebtBlocksThenAllowsRejection(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallAccountCouponService service=new MallAccountCouponService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  MallService legacy=new MallService();ReflectionTestUtils.setField(legacy,"accountCouponService",service);
  org.springframework.security.core.Authentication prior=SecurityContextHolder.getContext().getAuthentication();
  LoginUser user=new LoginUser();user.setUserId(1L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,Collections.emptyList()));
  try{new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{tx.setRollbackOnly();
   Long customer=jdbc.queryForObject("SELECT customer_id FROM mall_distributor LIMIT 1",Long.class);
   jdbc.update("UPDATE mall_distributor SET commission_balance=100,frozen_commission=0,withdrawn_commission=0 WHERE customer_id=?",customer);
   jdbc.update("UPDATE mall_withdrawal_config SET min_amount=1,max_amount=10000,fee_rate=0,fee_fixed=0,account_types='银行卡' WHERE status='0'");
   Map<String,Object> request=new HashMap<>();request.put("amount",5);request.put("accountType","银行卡");request.put("accountNo","ISOLATED_NOT_A_REAL_ACCOUNT");
   for(int round=0;round<2;round++){
    Map<String,Object> result=ReflectionTestUtils.invokeMethod(service,"requestWithdrawalInTransaction",customer,request,"isolated_review_"+UUID.randomUUID());
    Long id=jdbc.queryForObject("SELECT id FROM mall_withdrawal WHERE withdrawal_no=?",Long.class,result.get("withdrawalNo"));
    Map<String,Object> action=new HashMap<>();action.put("opinion","隔离测试审核");action.put("status","已打款");assertThrows(IllegalArgumentException.class,()->legacy.adminUpdateWithdrawal(id,action));action.put("status","审核通过");legacy.adminUpdateWithdrawal(id,action);action.put("status","处理中");service.reviewWithdrawal(id,action);action.put("status","已完成");
    if(round==0){service.reviewWithdrawal(id,action);assertThrows(IllegalArgumentException.class,()->service.reviewWithdrawal(id,action));assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("SELECT withdrawn_commission FROM mall_distributor WHERE customer_id=?",BigDecimal.class,customer)));}
    else{jdbc.update("UPDATE mall_distributor SET commission_balance=-3 WHERE customer_id=?",customer);assertThrows(IllegalArgumentException.class,()->service.reviewWithdrawal(id,action));action.put("status","已驳回");service.reviewWithdrawal(id,action);assertEquals("已驳回",jdbc.queryForObject("SELECT status FROM mall_withdrawal WHERE id=?",String.class,id));assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,customer)));}
   }
   return null;
  });}finally{SecurityContextHolder.getContext().setAuthentication(prior);}
 }
}
