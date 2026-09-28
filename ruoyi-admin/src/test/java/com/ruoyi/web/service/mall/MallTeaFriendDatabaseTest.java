package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ruoyi.web.controller.mall.MallTeaFriendController;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Opt-in real InnoDB tests; target is fixed to the isolated local test database. */
@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallTeaFriendDatabaseTest {
 static JdbcTemplate jdbc; static MallTeaFriendService service;
 Long owner,buyer,address;
 static String unique(){return UUID.randomUUID().toString().replace("-","");}
 @BeforeAll static void connect(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  jdbc=new JdbcTemplate(ds);
  assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  ProxyFactory factory=new ProxyFactory(new MallTeaFriendService(jdbc));factory.setProxyTargetClass(true);
  factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
  service=(MallTeaFriendService)factory.getProxy();
 }
 Long customer(){String name="TF_TEST_"+unique();jdbc.update("INSERT INTO mall_customer(nickname,phone,points) VALUES(?,NULL,37)",name);return jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,name);}
 @BeforeEach void fixtures(){
  jdbc.update("UPDATE mall_friend_config SET points_per_friend=2.5,large_exchange_score=100,daily_exchange_limit=10 WHERE id=1");
  owner=customer();buyer=customer();
  jdbc.update("INSERT INTO mall_invite_record(inviter_customer_id,invitee_customer_id) VALUES(?,?)",owner,buyer);
  jdbc.update("INSERT INTO mall_address(customer_id,receiver_name,receiver_phone,region,detail_address) VALUES(?,'隔离测试','13800000000','测试地区','测试地址一号')",owner);
  address=jdbc.queryForObject("SELECT id FROM mall_address WHERE customer_id=?",Long.class,owner);
 }
 Long order(boolean trial,int qty){
  String no="T"+unique().substring(0,30);
  jdbc.update("INSERT INTO mall_order(order_no,customer_id,status,payment_status,payment_method,paid_amount,total_amount,paid_time,is_test_order) VALUES(?,?,'待发货','已支付','测试支付',10,10,NOW(),1)",no,buyer);
  Long order=jdbc.queryForObject("SELECT id FROM mall_order WHERE order_no=?",Long.class,no);
  jdbc.update("INSERT INTO mall_order_item(order_id,product_id,product_name,qty,refundable_amount,is_trial_gift) VALUES(?,1,'隔离试喝礼包',?,10,?)",order,qty,trial?1:0);return order;
 }
 Long reward(int stock){String name="TF_REWARD_"+unique();jdbc.update("INSERT INTO mall_reward(name,invite_cost,invite_enabled,stock) VALUES(?,2.5,1,?)",name,stock);return jdbc.queryForObject("SELECT id FROM mall_reward WHERE name=?",Long.class,name);}
 Map<String,Object> request(Long reward,String key){Map<String,Object> body=new HashMap<>();body.put("rewardId",reward);body.put("qty",1);body.put("addressId",address);body.put("requestNo",key);return body;}
 BigDecimal net(){return jdbc.queryForObject("SELECT invitation_score_net FROM mall_customer WHERE id=?",BigDecimal.class,owner);}
 @Test void registrationAndOrdinaryPurchaseDoNotEarnScores(){service.reconcile(owner);assertEquals(0,net().signum());order(false,1);service.reconcileBuyer(buyer);assertEquals(0,net().signum());}
 @Test void trialPurchaseEarnsExactScoreOnceAndNeverChangesOrdinaryPoints(){order(true,1);service.reconcileBuyer(buyer);service.reconcileBuyer(buyer);order(true,1);service.reconcileBuyer(buyer);assertEquals(new BigDecimal("2.50"),net());assertEquals(37,jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?",Integer.class,owner));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_friend_score_log WHERE customer_id=?",Integer.class,owner));}
 @Test void partialRefundPreservesRemainingTrialQuantityAndFullRefundRevokes(){Long order=order(true,2);service.reconcileBuyer(buyer);jdbc.update("UPDATE mall_order_item SET refunded_qty=1,refunded_amount=5 WHERE order_id=?",order);service.reconcileBuyer(buyer);assertEquals(new BigDecimal("2.50"),net());jdbc.update("UPDATE mall_order_item SET refunded_qty=2,refunded_amount=10 WHERE order_id=?",order);service.reconcileBuyer(buyer);service.reconcileBuyer(buyer);assertEquals(new BigDecimal("0.00"),net());order(true,1);service.reconcileBuyer(buyer);assertEquals(new BigDecimal("2.50"),net());}
 @Test void spentScoresBecomeDebtOnRefundAndCancelReturnsOnlyOnce(){Long order=order(true,1);service.reconcileBuyer(buyer);Long reward=reward(1);Map<String,Object> req=request(reward,unique());String no=String.valueOf(service.exchange(owner,req).get("exchangeNo"));assertEquals(no,service.exchange(owner,req).get("exchangeNo"));assertEquals(new BigDecimal("0.00"),net());jdbc.update("UPDATE mall_order_item SET refunded_qty=qty,refunded_amount=refundable_amount WHERE order_id=?",order);service.reconcileBuyer(buyer);assertEquals(new BigDecimal("-2.50"),net());assertEquals(new BigDecimal("2.50"),service.overview(owner).get("debt"));service.cancel(owner,no);service.cancel(owner,no);assertEquals(new BigDecimal("0.00"),net());assertEquals(1,jdbc.queryForObject("SELECT stock FROM mall_reward WHERE id=?",Integer.class,reward));}
 @Test void otherUsersAddressRollsBackStockAndScore(){order(true,1);service.reconcileBuyer(buyer);Long reward=reward(1);Map<String,Object> req=request(reward,unique());req.put("addressId",-1);assertThrows(IllegalArgumentException.class,()->service.exchange(owner,req));assertEquals(new BigDecimal("2.50"),net());assertEquals(1,jdbc.queryForObject("SELECT stock FROM mall_reward WHERE id=?",Integer.class,reward));}
 @Test void parallelReconcileAndDuplicateExchangeAreIdempotent()throws Exception{
  order(true,1);ExecutorService pool=Executors.newFixedThreadPool(4);try{
   List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(pool.submit(()->service.reconcileBuyer(buyer)));for(Future<?> f:jobs)f.get(15,TimeUnit.SECONDS);assertEquals(new BigDecimal("2.50"),net());
   Long reward=reward(1);Map<String,Object> req=request(reward,unique());List<Future<Map<String,Object>>> orders=new ArrayList<>();for(int i=0;i<4;i++)orders.add(pool.submit(()->service.exchange(owner,req)));Set<String> ids=new HashSet<>();for(Future<Map<String,Object>> f:orders)ids.add(String.valueOf(f.get(15,TimeUnit.SECONDS).get("exchangeNo")));assertEquals(1,ids.size());assertEquals(new BigDecimal("0.00"),net());assertEquals(0,jdbc.queryForObject("SELECT stock FROM mall_reward WHERE id=?",Integer.class,reward));
  }finally{pool.shutdownNow();}
 }
 @Test void rejectReviewReturnsStockAndScoresExactlyOnce(){order(true,1);service.reconcileBuyer(buyer);jdbc.update("UPDATE mall_friend_config SET large_exchange_score=NULL WHERE id=1");Long reward=reward(1);String no=String.valueOf(service.exchange(owner,request(reward,unique())).get("exchangeNo"));Long exchange=jdbc.queryForObject("SELECT id FROM mall_exchange WHERE exchange_no=?",Long.class,no);Map<String,Object> body=new HashMap<>();body.put("action","REJECTED");body.put("reason","隔离测试审核拒绝");service.review(exchange,body,1L);service.review(exchange,body,1L);assertEquals(new BigDecimal("2.50"),net());assertEquals(1,jdbc.queryForObject("SELECT stock FROM mall_reward WHERE id=?",Integer.class,reward));}
 @Test void uniqueInviteeCannotBindTwoOwners(){Long other=customer();assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO mall_invite_record(inviter_customer_id,invitee_customer_id) VALUES(?,?)",other,buyer));}
 @Test void differentOwnersCompeteForLastRewardWithoutOverselling()throws Exception{
  order(true,1);service.reconcileBuyer(buyer);Long secondOwner=customer(),secondBuyer=customer();
  jdbc.update("INSERT INTO mall_invite_record(inviter_customer_id,invitee_customer_id) VALUES(?,?)",secondOwner,secondBuyer);
  Long originalBuyer=buyer;buyer=secondBuyer;order(true,1);buyer=originalBuyer;service.reconcileBuyer(secondBuyer);
  jdbc.update("INSERT INTO mall_address(customer_id,receiver_name,receiver_phone,region,detail_address) VALUES(?,'隔离测试','13800000000','测试地区','测试地址二号')",secondOwner);
  Long reward=reward(1);Map<String,Object> first=request(reward,unique()),second=request(reward,unique());second.put("addressId",jdbc.queryForObject("SELECT id FROM mall_address WHERE customer_id=?",Long.class,secondOwner));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{List<Future<Boolean>> jobs=new ArrayList<>();jobs.add(pool.submit(()->{start.await();try{service.exchange(owner,first);return true;}catch(IllegalArgumentException e){return false;}}));jobs.add(pool.submit(()->{start.await();try{service.exchange(secondOwner,second);return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();int wins=0;for(Future<Boolean> job:jobs)if(job.get(15,TimeUnit.SECONDS))wins++;assertEquals(1,wins);assertEquals(0,jdbc.queryForObject("SELECT stock FROM mall_reward WHERE id=?",Integer.class,reward));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_exchange WHERE reward_id=?",Integer.class,reward));assertEquals(new BigDecimal("2.50"),jdbc.queryForObject("SELECT SUM(invitation_score_net) FROM mall_customer WHERE id IN (?,?)",BigDecimal.class,owner,secondOwner));}finally{pool.shutdownNow();}
 }
 @Test void reviewShipmentAndReceiptUseExistingExchangeLifecycle(){
  order(true,1);service.reconcileBuyer(buyer);jdbc.update("UPDATE mall_friend_config SET large_exchange_score=NULL WHERE id=1");String no=String.valueOf(service.exchange(owner,request(reward(1),unique())).get("exchangeNo"));Long exchange=jdbc.queryForObject("SELECT id FROM mall_exchange WHERE exchange_no=?",Long.class,no);
  MallService mall=new MallService();ReflectionTestUtils.setField(mall,"jdbc",jdbc);ReflectionTestUtils.setField(mall,"teaFriendService",service);
  Map<String,Object> shipment=new HashMap<>();shipment.put("status","配送中");shipment.put("carrier","隔离物流");shipment.put("trackingNo","LOCAL123456");
  assertThrows(IllegalArgumentException.class,()->mall.adminUpdateExchange(exchange,shipment));Map<String,Object> review=new HashMap<>();review.put("action","APPROVED");review.put("reason","测试资格通过");service.review(exchange,review,1L);service.review(exchange,review,1L);mall.adminUpdateExchange(exchange,shipment);
  assertThrows(IllegalArgumentException.class,()->mall.confirmExchangeReceipt(buyer,no));assertEquals("已完成",mall.confirmExchangeReceipt(owner,no).get("status"));assertEquals("已完成",mall.confirmExchangeReceipt(owner,no).get("status"));assertThrows(IllegalArgumentException.class,()->service.cancel(owner,no));assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM mall_notification WHERE customer_id=? AND source_type='TEA_FRIEND'",Integer.class,owner)>=3);
 }
 @Test void adminPaginationAndConfigVersionAreEnforced(){
  Map<String,Object> first=service.config(),stale=new HashMap<>(first);first.put("trialProductIds",Collections.emptyList());service.saveConfig(first,1L);stale.put("trialProductIds",Collections.emptyList());assertThrows(IllegalArgumentException.class,()->service.saveConfig(stale,1L));
  order(true,1);service.reconcileBuyer(buyer);Map<String,Object> page=service.adminPage(true,1,1,String.valueOf(owner),"");assertEquals(1L,page.get("total"));assertEquals(1,((List<?>)page.get("rows")).size());assertTrue(((List<?>)service.adminPage(true,2,1,String.valueOf(owner),"").get("rows")).isEmpty());assertThrows(IllegalArgumentException.class,()->service.adminPage(false,0,100,"",""));
 }
 @Test void httpUsesServerSessionAndRejectsMissingAuthentication()throws Exception{
  MallSessionService sessions=new MallSessionService();ReflectionTestUtils.setField(sessions,"jdbc",jdbc);
  String token=unique()+unique();String hash=ReflectionTestUtils.invokeMethod(sessions,"tokenHash",token);
  jdbc.update("INSERT INTO mall_session(token_hash,customer_id,authenticated,expires_at) VALUES(?,?,1,DATE_ADD(NOW(),INTERVAL 1 HOUR))",hash,owner);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new MallTeaFriendController(service,sessions)).build();
  mvc.perform(get("/mall/tea-friends/overview")).andExpect(status().isUnauthorized());
  order(true,1);service.reconcileBuyer(buyer);
  mvc.perform(get("/mall/tea-friends/overview").header("X-Mall-Session",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(2.5));
  Map<String,Object> body=request(reward(1),unique());body.put("customerId",buyer);
  mvc.perform(post("/mall/tea-friends/exchanges").header("X-Mall-Session",token).contentType("application/json").content(new ObjectMapper().writeValueAsString(body))).andExpect(status().isOk());
  assertEquals(new BigDecimal("0.00"),net());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mall_exchange WHERE customer_id=?",Integer.class,buyer));
 }
}
