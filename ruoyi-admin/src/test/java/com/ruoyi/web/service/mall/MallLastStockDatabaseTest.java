package com.ruoyi.web.service.mall;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Each fixture belongs exclusively to this run in the fixed localhost database. */
@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallLastStockDatabaseTest {
 static String key(){return UUID.randomUUID().toString().replace("-","");}
 static Map<String,Object> map(Object... args){Map<String,Object> m=new HashMap<>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
 @Test void lastItemHasOneWinnerAndDuplicateCreationReturnsOneOrder()throws Exception{
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  MallTestPaymentProperties payment=new MallTestPaymentProperties();ReflectionTestUtils.setField(payment,"environment","test");ReflectionTestUtils.setField(payment,"enabled",true);ReflectionTestUtils.setField(service,"testPaymentProperties",payment);
  String tag="STOCK_TEST_"+key();List<Long> customers=new ArrayList<>();Long product=null,sku=null;ExecutorService pool=Executors.newFixedThreadPool(4);
  try{
   for(int i=0;i<2;i++){jdbc.update("INSERT INTO mall_customer(nickname,phone,points) VALUES(?,NULL,0)",tag+i);Long id=jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,tag+i);customers.add(id);jdbc.update("INSERT INTO mall_address(customer_id,receiver_name,receiver_phone,region,detail_address) VALUES(?,'隔离测试','13800000000','测试地区','测试地址一号')",id);}
   jdbc.update("INSERT INTO mall_product(category,name,short_name,price,stock,status) VALUES('隔离测试',?,'并发测试',20,1,'0')",tag);product=jdbc.queryForObject("SELECT id FROM mall_product WHERE name=?",Long.class,tag);
   jdbc.update("INSERT INTO mall_product_sku(product_id,sku_code,spec_name,price,stock,status,is_default) VALUES(?,?,'100g',20,1,'0',1)",product,tag);sku=jdbc.queryForObject("SELECT id FROM mall_product_sku WHERE sku_code=?",Long.class,tag);
   final Long productId=product,skuId=sku;
   CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);List<Future<String>> attempts=new ArrayList<>();
   for(Long buyer:customers)attempts.add(pool.submit(()->{ready.countDown();go.await();try{return tx.execute(t->String.valueOf(service.createOrder(buyer,map("requestNo",key(),"items",Arrays.asList(map("productId",productId,"skuId",skuId,"qty",1)))).get("orderNo")));}catch(IllegalArgumentException exhausted){assertTrue(exhausted.getMessage().contains("库存"));return null;}}));
   assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();List<String> winners=new ArrayList<>();for(Future<String> result:attempts){String no=result.get(20,TimeUnit.SECONDS);if(no!=null)winners.add(no);}assertEquals(1,winners.size());
   assertEquals(0,jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku));assertEquals(0,jdbc.queryForObject("SELECT stock FROM mall_product WHERE id=?",Integer.class,product));
   String winner=winners.get(0);Long owner=jdbc.queryForObject("SELECT customer_id FROM mall_order WHERE order_no=?",Long.class,winner);Map<String,Object> cancel=map("requestNo",key(),"reason","隔离并发测试取消");
   tx.execute(t->service.cancelOrder(owner,winner,cancel));tx.execute(t->service.cancelOrder(owner,winner,cancel));
   assertEquals(1,jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku));
   Map<String,Object> duplicate=map("requestNo",key(),"items",Arrays.asList(map("productId",productId,"skuId",skuId,"qty",1)));List<Future<String>> repeated=new ArrayList<>();
   for(int i=0;i<4;i++)repeated.add(pool.submit(()->tx.execute(t->String.valueOf(service.createOrder(owner,duplicate).get("orderNo")))));
   Set<String> orders=new HashSet<>();for(Future<String> result:repeated)orders.add(result.get(20,TimeUnit.SECONDS));assertEquals(1,orders.size());
   assertEquals(0,jdbc.queryForObject("SELECT stock FROM mall_product_sku WHERE id=?",Integer.class,sku));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE customer_id=? AND request_no=?",Integer.class,owner,duplicate.get("requestNo")));
  }finally{
   pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));
   // Delete only the IDs created above, never existing fixture or production rows.
   for(Long buyer:customers){
    for(String table:Arrays.asList("mall_order_item","mall_logistics","mall_order_operation_log"))jdbc.update("DELETE FROM "+table+" WHERE order_id IN (SELECT id FROM mall_order WHERE customer_id=?)",buyer);
    jdbc.update("DELETE FROM mall_notification WHERE customer_id=?",buyer);jdbc.update("DELETE FROM mall_order WHERE customer_id=?",buyer);jdbc.update("DELETE FROM mall_address WHERE customer_id=?",buyer);jdbc.update("DELETE FROM mall_customer WHERE id=? AND nickname LIKE ?",buyer,tag+"%");
   }
   if(sku!=null)jdbc.update("DELETE FROM mall_product_sku WHERE id=? AND sku_code=?",sku,tag);
   if(product!=null)jdbc.update("DELETE FROM mall_product WHERE id=? AND name=?",product,tag);
  }
 }
}
