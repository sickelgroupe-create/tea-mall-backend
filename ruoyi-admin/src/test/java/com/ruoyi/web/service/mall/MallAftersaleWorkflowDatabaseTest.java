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
class MallAftersaleWorkflowDatabaseTest {
 private static String key(){return UUID.randomUUID().toString().replace("-","");}
 private static Map<String,Object> map(Object... pairs){Map<String,Object> m=new HashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
 @Test void refundOnly(){workflow("仅退款");}
 @Test void returnRefund(){workflow("退货退款");}
 @Test void exchange(){workflow("换货");}
 private void workflow(String type){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"teaFriendService",new MallTeaFriendService(jdbc));
  org.springframework.security.core.Authentication prior=SecurityContextHolder.getContext().getAuthentication();LoginUser admin=new LoginUser();admin.setUserId(1L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin,null,Collections.emptyList()));
  try{new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{tx.setRollbackOnly();
   String name="FLOW_"+key();jdbc.update("INSERT INTO mall_customer(nickname,phone,points) VALUES(?,NULL,0)",name);
   Long customer=jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,name);
   String no="O"+key().substring(0,24),status="仅退款".equals(type)?"待发货":"已完成";
   jdbc.update("INSERT INTO mall_order(order_no,customer_id,status,paid_amount,total_amount,payment_status,reward_points) VALUES(?,?,?,10,10,'已支付',100)",no,customer,status);
   Long order=jdbc.queryForObject("SELECT id FROM mall_order WHERE order_no=?",Long.class,no);
   jdbc.update("INSERT INTO mall_points_accrual(customer_id,order_id,order_no,original_points,status) VALUES(?,?,?,100,'待生效')",customer,order,no);
   List<Map<String,Object>> skus=jdbc.queryForList("SELECT id,product_id,stock FROM mall_product_sku ORDER BY id LIMIT 2");assertEquals(2,skus.size());
   List<Long> items=new ArrayList<>();List<Map<String,Object>> sales=new ArrayList<>();
   for(int i=0;i<2;i++){
    Map<String,Object> sku=skus.get(i);int amount=i==0?6:4;
    jdbc.update("INSERT INTO mall_order_item(order_id,product_id,sku_id,product_name,qty,price,refundable_amount) VALUES(?,?,?,'隔离售后商品',1,?,?)",order,sku.get("product_id"),sku.get("id"),amount,amount);
    Long item=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);items.add(item);
    Map<String,Object> request=map("orderNo",no,"orderItemId",item,"qty",1,"typeName",type,"reason","隔离测试","requestNo",key());
    Map<String,Object> sale=service.submitAftersale(customer,request);sales.add(sale);
    assertEquals(sale.get("aftersaleNo"),service.submitAftersale(customer,request).get("aftersaleNo"));
    request.put("requestNo",key());assertThrows(IllegalArgumentException.class,()->service.submitAftersale(customer,request));
    assertThrows(IllegalArgumentException.class,()->service.aftersaleDetail(-1L,String.valueOf(sale.get("aftersaleNo"))));
   }
   assertEquals(status,jdbc.queryForObject("SELECT status FROM mall_order WHERE id=?",String.class,order));
   for(int i=0;i<2;i++){
    Map<String,Object> sale=sales.get(i);Long id=((Number)sale.get("id")).longValue();String saleNo=String.valueOf(sale.get("aftersaleNo"));
    if(!"仅退款".equals(type)){
     service.adminUpdateAftersale(id,map("status","等待用户退货","returnAddress","隔离退货地址","requestNo",key()));
     assertThrows(IllegalArgumentException.class,()->service.adminUpdateAftersale(id,map("status","退款处理中","requestNo",key())));
     service.submitReturnLogistics(customer,saleNo,map("carrier","本地物流","trackingNo","LOCAL123","requestNo",key()));
     service.adminUpdateAftersale(id,map("status","商家已收货","requestNo",key()));
    }
    if("换货".equals(type)){
     service.adminUpdateAftersale(id,map("status","换货已发出","exchangeCarrier","本地物流","exchangeTrackingNo","EXCHANGE123","requestNo",key()));
     Map<String,Object> confirm=map("requestNo",key());service.confirmExchangeReceipt(customer,saleNo,confirm);service.confirmExchangeReceipt(customer,saleNo,confirm);
    }else{
     service.adminUpdateAftersale(id,map("status","退款处理中","requestNo",key()));
     Map<String,Object> refund=map("status","退款成功","requestNo",key());service.adminUpdateAftersale(id,refund);service.adminUpdateAftersale(id,refund);
     assertEquals(i==0?60:100,jdbc.queryForObject("SELECT reversed_points FROM mall_points_accrual WHERE order_id=?",Integer.class,order));
     assertEquals(i==0?"待生效":"已撤销",jdbc.queryForObject("SELECT status FROM mall_points_accrual WHERE order_id=?",String.class,order));
     service.adminUpdateAftersale(id,map("status","售后完成","requestNo",key()));
    }
    assertEquals("售后完成",service.aftersaleDetail(customer,saleNo).get("status"));
    assertEquals(0,jdbc.queryForObject("SELECT aftersale_locked_qty FROM mall_order_item WHERE id=?",Integer.class,items.get(i)));
   }
   assertEquals(0,new BigDecimal("换货".equals(type)?"0":"10").compareTo(jdbc.queryForObject("SELECT refunded_amount FROM mall_order WHERE id=?",BigDecimal.class,order)));
   for(Map<String,Object> sku:skus)assertEquals(((Number)sku.get("stock")).intValue()+("换货".equals(type)?0:1),jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku.get("id")));
   assertEquals("换货".equals(type)?status:"已退款",jdbc.queryForObject("SELECT status FROM mall_order WHERE id=?",String.class,order));
   return null;
  });}finally{SecurityContextHolder.getContext().setAuthentication(prior);}
 }
}
