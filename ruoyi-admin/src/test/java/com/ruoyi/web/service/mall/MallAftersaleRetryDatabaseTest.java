package com.ruoyi.web.service.mall;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallAftersaleRetryDatabaseTest {
 @Test void partialRefundDoesNotCancelRemainingPendingPoints() {
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{tx.setRollbackOnly();
   Long customer=jdbc.queryForObject("SELECT id FROM mall_customer LIMIT 1",Long.class);
   String no="POINT"+UUID.randomUUID().toString().replace("-","").substring(0,20);
   jdbc.update("INSERT INTO mall_order(order_no,customer_id,status,paid_amount,refunded_amount) VALUES(?,?,'待发货',10,6)",no,customer);
   Long order=jdbc.queryForObject("SELECT id FROM mall_order WHERE order_no=?",Long.class,no);
   jdbc.update("INSERT INTO mall_points_accrual(customer_id,order_id,order_no,original_points,status) VALUES(?,?,?,100,'待生效')",customer,order,no);
   Map<String,Object> data=new HashMap<>();data.put("orderId",order);data.put("customer_id",customer);data.put("order_no",no);
   ReflectionTestUtils.invokeMethod(service,"reverseOrderReward",data,new java.math.BigDecimal("6"),"partial_points_123456");
   assertEquals(60,jdbc.queryForObject("SELECT reversed_points FROM mall_points_accrual WHERE order_id=?",Integer.class,order));
   assertEquals("待生效",jdbc.queryForObject("SELECT status FROM mall_points_accrual WHERE order_id=?",String.class,order));
   ReflectionTestUtils.invokeMethod(service,"reverseOrderReward",data,new java.math.BigDecimal("6"),"partial_points_123456");
   assertEquals(60,jdbc.queryForObject("SELECT reversed_points FROM mall_points_accrual WHERE order_id=?",Integer.class,order));
   jdbc.update("UPDATE mall_order SET refunded_amount=10 WHERE id=?",order);
   ReflectionTestUtils.invokeMethod(service,"reverseOrderReward",data,new java.math.BigDecimal("4"),"remaining_points_123456");
   assertEquals(100,jdbc.queryForObject("SELECT reversed_points FROM mall_points_accrual WHERE order_id=?",Integer.class,order));
   assertEquals("已撤销",jdbc.queryForObject("SELECT status FROM mall_points_accrual WHERE order_id=?",String.class,order));
   return null;
  });
 }
 @Test void successfulActionsCanRetryWithoutDuplicateEffects() {
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);
  assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{
   tx.setRollbackOnly();
   Map<String,Object> item=jdbc.queryForMap("SELECT i.id,i.order_id,i.product_id,i.sku_id,o.customer_id FROM mall_order_item i JOIN mall_order o ON o.id=i.order_id LIMIT 1");
   Long customer=((Number)item.get("customer_id")).longValue();
   String no="RETRY"+UUID.randomUUID().toString().replace("-","").substring(0,20);
   jdbc.update("INSERT INTO mall_aftersale(aftersale_no,request_no,order_id,order_item_id,product_id,sku_id,apply_qty,customer_id,type_name,reason,status,requested_amount,source_order_status) VALUES(?,?,?,?,?,?,1,?,'换货','隔离测试','等待用户退货',1,'已完成')",no,no,item.get("order_id"),item.get("id"),item.get("product_id"),item.get("sku_id"),customer);
   Long id=jdbc.queryForObject("SELECT id FROM mall_aftersale WHERE aftersale_no=?",Long.class,no);
   jdbc.update("UPDATE mall_order_item SET aftersale_locked_qty=1 WHERE id=?",item.get("id"));
   Map<String,Object> body=new HashMap<>();body.put("carrier"," 测试物流 ");body.put("trackingNo"," LOCAL123 ");body.put("requestNo","return_retry_123456");
   assertThrows(IllegalArgumentException.class,()->service.submitReturnLogistics(-1L,no,body));
   service.submitReturnLogistics(customer,no,body);
   assertEquals("退货运输中",service.submitReturnLogistics(customer,no,body).get("status"));
   Map<String,Object> changed=new HashMap<>(body);changed.put("trackingNo","OTHER");
   assertThrows(IllegalArgumentException.class,()->service.submitReturnLogistics(customer,no,changed));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_aftersale_operation_log WHERE aftersale_id=?",Integer.class,id));
   jdbc.update("UPDATE mall_aftersale SET status='换货已发出' WHERE id=?",id);
   Map<String,Object> confirm=Collections.singletonMap("requestNo","confirm_retry_123456");
   service.confirmExchangeReceipt(customer,no,confirm);
   assertEquals("售后完成",service.confirmExchangeReceipt(customer,no,confirm).get("status"));
   assertEquals(0,jdbc.queryForObject("SELECT aftersale_locked_qty FROM mall_order_item WHERE id=?",Integer.class,item.get("id")));
   assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mall_aftersale_operation_log WHERE aftersale_id=?",Integer.class,id));
   assertThrows(IllegalArgumentException.class,()->service.confirmExchangeReceipt(-1L,no,confirm));
   assertThrows(IllegalArgumentException.class,()->service.confirmExchangeReceipt(customer,no,Collections.singletonMap("requestNo","different_123456")));
   return null;
  });
 }
}
