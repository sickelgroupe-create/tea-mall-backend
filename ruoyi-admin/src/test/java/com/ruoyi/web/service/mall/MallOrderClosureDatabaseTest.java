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
class MallOrderClosureDatabaseTest {
 static String key(){return UUID.randomUUID().toString().replace("-","");}
 static Map<String,Object> map(Object... args){Map<String,Object> m=new HashMap<>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
 @Test void createPayShipCompleteSettlesPointsAndDirectCommissionOnce(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"teaFriendService",new MallTeaFriendService(jdbc));
  MallTestPaymentProperties payment=new MallTestPaymentProperties();ReflectionTestUtils.setField(payment,"environment","test");ReflectionTestUtils.setField(payment,"enabled",true);ReflectionTestUtils.setField(service,"testPaymentProperties",payment);
  org.springframework.security.core.Authentication prior=SecurityContextHolder.getContext().getAuthentication();LoginUser admin=new LoginUser();admin.setUserId(1L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin,null,Collections.emptyList()));
  try{new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{tx.setRollbackOnly();
   String buyerName="ORDERTEST_"+key(),parentName="PARENT_"+key();jdbc.update("INSERT INTO mall_customer(nickname,phone,points) VALUES(?,NULL,0),(?,NULL,0)",buyerName,parentName);
   Long buyer=jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,buyerName),parent=jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,parentName);
   jdbc.update("INSERT INTO mall_distributor(customer_id,invite_code,status,partner_status) VALUES(?,?,'0','审核通过')",parent,key());
   jdbc.update("INSERT INTO mall_distributor(customer_id,invite_code,status,parent_customer_id) VALUES(?,?,'0',?)",buyer,key(),parent);
   jdbc.update("INSERT INTO mall_address(customer_id,receiver_name,receiver_phone,region,detail_address) VALUES(?,'隔离测试','13800000000','测试地区','测试地址一号')",buyer);
   Map<String,Object> sku=jdbc.queryForMap("SELECT id,product_id FROM mall_product_sku ORDER BY id LIMIT 1");
   jdbc.update("UPDATE mall_product SET stock=10,status='0',commission_amount=7 WHERE id=?",sku.get("product_id"));jdbc.update("UPDATE mall_product_sku SET stock=10,status='0',price=20 WHERE id=?",sku.get("id"));jdbc.update("UPDATE mall_consumption_points_config SET percent=50 WHERE id=1");
   service.updateCart(buyer,((Number)sku.get("product_id")).longValue(),((Number)sku.get("id")).longValue(),2,true);
   Map<String,Object> request=map("requestNo",key(),"items",Arrays.asList(map("productId",sku.get("product_id"),"skuId",sku.get("id"),"qty",2)));
   Map<String,Object> created=service.createOrder(buyer,request);String no=String.valueOf(created.get("orderNo"));assertEquals(no,service.createOrder(buyer,request).get("orderNo"));
   Long order=jdbc.queryForObject("SELECT id FROM mall_order WHERE order_no=?",Long.class,no);
   assertEquals(8,jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku.get("id")));
   String failed=key();service.testPayFailure(buyer,no,failed,null);service.testPayFailure(buyer,no,failed,null);assertEquals("待付款",jdbc.queryForObject("SELECT status FROM mall_order WHERE id=?",String.class,order));
   String paid=key();service.testPay(buyer,no,paid);service.testPay(buyer,no,paid);
   assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM mall_payment_attempt WHERE order_id=?",Integer.class,order));
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mall_cart WHERE customer_id=?",Integer.class,buyer));
   assertEquals(8,jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku.get("id")));
   assertEquals("待生效",jdbc.queryForObject("SELECT status FROM mall_points_accrual WHERE order_id=?",String.class,order));
   assertEquals(0,jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?",Integer.class,buyer));
   assertEquals(0,new BigDecimal("7").compareTo(jdbc.queryForObject("SELECT amount FROM mall_commission WHERE order_id=?",BigDecimal.class,order)));
   service.adminUpdateOrder(order,map("status","待收货","carrier","隔离物流","trackingNo","LOCAL123","requestNo",key()));
   service.updatePublicOrderStatus(buyer,no,"已完成");service.updatePublicOrderStatus(buyer,no,"已完成");
   assertEquals(20,jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?",Integer.class,buyer));
   assertEquals(0,new BigDecimal("7").compareTo(jdbc.queryForObject("SELECT commission_balance FROM mall_distributor WHERE customer_id=?",BigDecimal.class,parent)));
   assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_points_log WHERE customer_id=? AND business_type='ORDER_REWARD'",Integer.class,buyer));
   return null;
  });}finally{SecurityContextHolder.getContext().setAuthentication(prior);}
 }
}
