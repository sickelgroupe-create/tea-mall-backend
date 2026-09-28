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
class MallBusinessConfigDatabaseTest {
 private final DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
 @Test void partnerFormRoundtripOptionalValidationAndVersionConflict(){
  JdbcTemplate jdbc=new JdbcTemplate(ds);MallPartnerFormService service=new MallPartnerFormService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{status.setRollbackOnly();
   Map<String,Object> c=service.config();c.put("title","申请茶友合伙人");
   for(Object value:(List<?>)c.get("fields")){Map<String,Object> f=(Map<String,Object>)value;f.put("required",false);}
   service.save(c,1L);assertEquals("申请茶友合伙人",service.config().get("title"));
   assertEquals("",service.validate(new HashMap<>()).get("idNo"));
   assertThrows(IllegalArgumentException.class,()->service.save(c,1L));
   Map<String,Object> invalid=new HashMap<>();invalid.put("idNo","abc");assertThrows(IllegalArgumentException.class,()->service.validate(invalid));
   assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM mall_partner_form_audit",Integer.class)>0);
   return null;});
 }
 @Test void pointsPercentageIsReadAndRoundedDown(){
  JdbcTemplate jdbc=new JdbcTemplate(ds);MallService service=new MallService();ReflectionTestUtils.setField(service,"jdbc",jdbc);
  new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{status.setRollbackOnly();
   jdbc.update("UPDATE mall_consumption_points_config SET percent=250 WHERE id=1");
   assertEquals(Integer.valueOf(26),ReflectionTestUtils.invokeMethod(service,"rewardPoints",new BigDecimal("10.79")));
   jdbc.update("UPDATE mall_consumption_points_config SET percent=0 WHERE id=1");
   assertEquals(Integer.valueOf(0),ReflectionTestUtils.invokeMethod(service,"rewardPoints",new BigDecimal("268")));
   return null;});
 }
}
