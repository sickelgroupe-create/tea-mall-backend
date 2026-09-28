package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallFixedCommissionDatabaseTest {
 @Test void distinctProductsNotQuantityDirectOnlyAndIdempotentSettlementAndReversal() {
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);
  assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
   status.setRollbackOnly();
   Map<String,Object> order=jdbc.queryForMap("SELECT id,customer_id FROM mall_order WHERE NOT EXISTS(SELECT 1 FROM mall_commission c WHERE c.order_id=mall_order.id) LIMIT 1");
   Long id=((Number)order.get("id")).longValue(),buyer=((Number)order.get("customer_id")).longValue();
   List<Long> people=jdbc.queryForList("SELECT id FROM mall_customer WHERE id<>? LIMIT 2",Long.class,buyer);
   Long parent=people.get(0),grandparent=people.get(1);
   for(Long person:Arrays.asList(buyer,parent,grandparent)) jdbc.update("INSERT IGNORE INTO mall_distributor(customer_id,invite_code,status) VALUES(?,?,'0')",person,"FIXTEST"+person);
   jdbc.update("UPDATE mall_distributor SET parent_customer_id=? WHERE customer_id=?",parent,buyer);
   jdbc.update("UPDATE mall_distributor SET parent_customer_id=?,partner_status='审核通过',status='0' WHERE customer_id=?",grandparent,parent);
   jdbc.update("UPDATE mall_order_item SET commission_amount=0 WHERE order_id=?",id);
   List<Long> products=jdbc.queryForList("SELECT id FROM mall_product LIMIT 2",Long.class);
   jdbc.update("INSERT INTO mall_order_item(order_id,product_id,product_name,qty,commission_amount) VALUES(?,?,'fixed test A',3,10),(?,?,'fixed test A sku',2,10),(?,?,'fixed test B',1,5)",id,products.get(0),id,products.get(0),id,products.get(1));
   BigDecimal before=jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent);
   ReflectionTestUtils.invokeMethod(service,"createPendingCommissions",id,buyer,new BigDecimal("268"));
   ReflectionTestUtils.invokeMethod(service,"createPendingCommissions",id,buyer,new BigDecimal("268"));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_commission WHERE order_id=?",Integer.class,id));
   assertEquals(0,new BigDecimal("15").compareTo(jdbc.queryForObject("SELECT amount FROM mall_commission WHERE order_id=? AND beneficiary_id=? AND level_no=1",BigDecimal.class,id,parent)));
   ReflectionTestUtils.invokeMethod(service,"settleCommissions",id);ReflectionTestUtils.invokeMethod(service,"settleCommissions",id);
   assertEquals(0,before.add(new BigDecimal("15")).compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent)));
   MallAccountCouponService wallet=new MallAccountCouponService();ReflectionTestUtils.setField(wallet,"jdbc",jdbc);
   jdbc.update("UPDATE mall_withdrawal_config SET min_amount=1,max_amount=10000,fee_rate=0,fee_fixed=0,account_types='银行卡' WHERE status='0'");
   Map<String,Object> request=new HashMap<>();request.put("amount",5);request.put("accountType","银行卡");request.put("accountNo","ISOLATED_NOT_A_REAL_ACCOUNT");
   Map<String,Object> withdrawal=ReflectionTestUtils.invokeMethod(wallet,"requestWithdrawalInTransaction",parent,request,"fixed_commission_withdraw_test");
   assertEquals("待审核",withdrawal.get("status"));
   wallet.cancelWithdrawal(parent,String.valueOf(withdrawal.get("withdrawalNo")));
   assertEquals(0,before.add(new BigDecimal("15")).compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent)));
   jdbc.update("UPDATE mall_order SET paid_amount=268,refunded_amount=10 WHERE id=?",id);
   jdbc.update("UPDATE mall_order_item SET refunded_qty=qty WHERE order_id=? AND product_id=?",id,products.get(1));
   ReflectionTestUtils.invokeMethod(service,"adjustCommissionAfterRefund",id,"fixed_refund_test");
   ReflectionTestUtils.invokeMethod(service,"adjustCommissionAfterRefund",id,"fixed_refund_test");
   assertEquals(0,before.add(new BigDecimal("10")).compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent)));
   ReflectionTestUtils.invokeMethod(service,"cancelCommissions",id);ReflectionTestUtils.invokeMethod(service,"cancelCommissions",id);
   assertEquals(0,before.compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent)));
   return null;
  });
 }
}
