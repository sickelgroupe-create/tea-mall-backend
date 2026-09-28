package com.ruoyi.web.service.mall;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="tea.friend.integration",matches="true")
class MallInviteCopyDatabaseTest {
 @Test void administratorTextRoundTripsAndOldClientsPreserveIt(){
  JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33309/tea_friends_20260905?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",""));
  assertEquals("tea_friends_20260905",jdbc.queryForObject("SELECT DATABASE()",String.class));
  MallTeaFriendService service=new MallTeaFriendService(jdbc);
  Map<String,Object> old=service.config(),body=new HashMap<>(old);
  List<Long> products=jdbc.queryForList("SELECT id FROM mall_product WHERE is_trial_gift=1",Long.class);
  body.put("trialProductIds",products);body.put("inviteCardTitle","好友体验好茶");body.put("rewardPrefix","每位赠送");body.put("scoreUnitLabel","茶友分");body.put("inviteButtonText","立即分享");
  try {
   service.saveConfig(body,1L);
   Map<String,Object> saved=service.config();
   for(String key:Arrays.asList("inviteCardTitle","rewardPrefix","scoreUnitLabel","inviteButtonText"))assertEquals(body.get(key),saved.get(key));
   Map<String,Object> legacy=new HashMap<>(saved);legacy.put("trialProductIds",products);
   for(String key:Arrays.asList("inviteCardTitle","rewardPrefix","scoreUnitLabel","inviteButtonText"))legacy.remove(key);
   service.saveConfig(legacy,1L);assertEquals("立即分享",service.config().get("inviteButtonText"));
   Map<String,Object> invalid=new HashMap<>(service.config());invalid.put("trialProductIds",products);invalid.put("inviteButtonText","<script>");
   assertThrows(IllegalArgumentException.class,()->service.saveConfig(invalid,1L));assertEquals("立即分享",service.config().get("inviteButtonText"));
  } finally {old.put("versionNo",service.config().get("versionNo"));old.put("trialProductIds",products);service.saveConfig(old,1L);}
 }
}
