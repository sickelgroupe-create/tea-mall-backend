package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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

/** Real isolated InnoDB transactions; official WeChat exchange is a test double, not real-device proof. */
@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallWechatBindingDatabaseTest {
 static JdbcTemplate jdbc; static DataSourceTransactionManager tx;
 MallWechatBindingService service; MallWechatService wechat; long owner,other;
 static String unique(){return UUID.randomUUID().toString().replace("-", "");}
 @BeforeAll static void connect(){
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
  jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);
  assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
 }
 long customer(){String name="BIND_TEST_"+unique();jdbc.update("INSERT INTO mall_customer(nickname,phone,points,status) VALUES(?,NULL,37,'0')",name);return jdbc.queryForObject("SELECT id FROM mall_customer WHERE nickname=?",Long.class,name);}
 @BeforeEach void setup(){
  owner=customer();other=customer();wechat=mock(MallWechatService.class);when(wechat.appId()).thenReturn("binding-test-app");when(wechat.configured()).thenReturn(true);
  MallWechatBindingService target=new MallWechatBindingService();ReflectionTestUtils.setField(target,"jdbc",jdbc);ReflectionTestUtils.setField(target,"wechat",wechat);
  ProxyFactory f=new ProxyFactory(target);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));service=(MallWechatBindingService)f.getProxy();
 }
 @Test void currentCustomerBindingDoesNotCreateCustomerOrMoveAssets(){
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer",Integer.class);
  String id=unique();when(wechat.exchangeMiniProgramCode("official-code")).thenReturn(new MallWechatService.WechatIdentity("binding-test-app",id,""));
  assertEquals(true,service.bindCurrent(owner,"official-code").get("bound"));verify(wechat).exchangeMiniProgramCode("official-code");
  assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer",Integer.class));assertEquals(37,jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?",Integer.class,owner));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_security_log WHERE customer_id=? AND event_type='BIND_WECHAT'",Integer.class,owner));
 }
 @Test void publicStatusNeverLeaksIdentity(){service.attach(owner,"binding-test-app",unique(),"secret-union");assertEquals(new HashSet<>(Arrays.asList("bound","configured","shareRequiresWechat")),service.status(owner).keySet());}
 @Test void unboundCannotShareButBoundCan(){assertThrows(IllegalArgumentException.class,()->service.requireBound(owner));service.attach(owner,"binding-test-app",unique(),"");assertDoesNotThrow(()->service.requireBound(owner));}
 @Test void otherMiniProgramDoesNotSatisfyBinding(){service.attach(owner,"another-app",unique(),"");assertFalse(service.isBound(owner));}
 @Test void repeatedBindingWritesOneIdentityAndAudit(){String id=unique();service.attach(owner,"binding-test-app",id,"");service.attach(owner,"binding-test-app",id,"");assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_identity WHERE customer_id=?",Integer.class,owner));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_security_log WHERE customer_id=?",Integer.class,owner));}
 @Test void identityBoundToOtherAccountCannotBeStolen(){String id=unique();service.attach(owner,"binding-test-app",id,"");assertThrows(IllegalArgumentException.class,()->service.attach(other,"binding-test-app",id,""));assertFalse(service.isBound(other));}
 @Test void customerCannotSilentlyReplaceWechat(){service.attach(owner,"binding-test-app",unique(),"");assertThrows(IllegalArgumentException.class,()->service.attach(owner,"binding-test-app",unique(),""));}
 @Test void disabledCustomerCannotBind(){jdbc.update("UPDATE mall_customer SET status='1' WHERE id=?",owner);assertThrows(MallAuthenticationException.class,()->service.attach(owner,"binding-test-app",unique(),""));}
 @Test void disabledInviterOldSceneCannotAuthorizeNewInvites(){service.attach(owner,"binding-test-app",unique(),"");jdbc.update("UPDATE mall_customer SET status='1' WHERE id=?",owner);assertThrows(IllegalArgumentException.class,()->service.requireBound(owner));}
 @Test void oldUnboundInviteCodeCannotBypassShareGate(){
  MallSessionService sessions=new MallSessionService();ReflectionTestUtils.setField(sessions,"jdbc",jdbc);ReflectionTestUtils.setField(sessions,"wechatBindingService",service);
  String code="Q"+unique().substring(0,20);jdbc.update("INSERT INTO mall_distributor(customer_id,invite_code,status) VALUES(?,?,'0')",owner,code);
  assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(sessions,"ensureDistributor",other,code));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE invitee_customer_id=?",Integer.class,other));
 }
 @Test void invalidOfficialCodeCannotWriteIdentity(){when(wechat.exchangeMiniProgramCode("bad-code")).thenThrow(new IllegalArgumentException("授权过期"));assertThrows(IllegalArgumentException.class,()->service.bindCurrent(owner,"bad-code"));assertFalse(service.isBound(owner));}
 @Test void parallelDifferentWechatsOneCustomerOnlyOneWins() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<4;i++)results.add(pool.submit(()->{try{service.attach(owner,"binding-test-app",unique(),"");return true;}catch(IllegalArgumentException e){return false;}}));int wins=0;for(Future<Boolean> f:results)if(f.get(20,TimeUnit.SECONDS))wins++;assertEquals(1,wins);}finally{pool.shutdownNow();}
 }
 @Test void parallelSameWechatDifferentCustomersOnlyOneWins() throws Exception {
  String id=unique();ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Boolean>> results=new ArrayList<>();for(long c:Arrays.asList(owner,other))results.add(pool.submit(()->{try{service.attach(c,"binding-test-app",id,"");return true;}catch(IllegalArgumentException e){return false;}}));int wins=0;for(Future<Boolean> f:results)if(f.get(20,TimeUnit.SECONDS))wins++;assertEquals(1,wins);}finally{pool.shutdownNow();}
 }
 @Test void shareMethodsGateEvenAnExistingCachedCode(){MallService mall=new MallService();ReflectionTestUtils.setField(mall,"wechatBindingService",service);assertThrows(IllegalArgumentException.class,()->mall.inviteScene(owner));assertThrows(IllegalArgumentException.class,()->mall.inviteMiniProgramCode(owner));assertEquals(true,mall.inviteSceneSummary(owner).get("wechatBindingRequired"));}
}
