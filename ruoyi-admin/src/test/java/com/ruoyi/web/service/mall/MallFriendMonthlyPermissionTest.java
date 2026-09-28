package com.ruoyi.web.service.mall;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.web.controller.mall.MallFriendMonthlyAdminController;

class MallFriendMonthlyPermissionTest {
 public static class Gate { public boolean hasPermi(String value){return SecurityContextHolder.getContext().getAuthentication()!=null&&SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->value.equals(a.getAuthority()));} }
 @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true,proxyTargetClass=true)
 static class Config {
  @Bean public Gate ss(){return new Gate();}
  @Bean public MallFriendMonthlyService service(){return mock(MallFriendMonthlyService.class);}
  @Bean public MallFriendMonthlyAdminController controller(MallFriendMonthlyService service){return new MallFriendMonthlyAdminController(service);}
 }
 @Test void actualMethodSecurityAllowsReadButRejectsWritesAndUnauthorizedRead(){
  try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(Config.class)){
   MallFriendMonthlyAdminController api=context.getBean(MallFriendMonthlyAdminController.class);
   MallFriendMonthlyService service=context.getBean(MallFriendMonthlyService.class);
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("local-reader","unused",Arrays.asList(new SimpleGrantedAuthority("mall:invite-gift:list"))));
   api.periods(1);verify(service).periods(true,1);
   assertThrows(AccessDeniedException.class,()->api.save(Collections.emptyMap()));
   assertThrows(AccessDeniedException.class,()->api.settle(1,Collections.emptyMap()));
   assertThrows(AccessDeniedException.class,()->api.payout(1,Collections.emptyMap()));
   assertThrows(AccessDeniedException.class,()->api.close(1,Collections.emptyMap()));
   verify(service,never()).payout(anyLong(),anyMap(),anyLong());
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("no-permission","unused",Collections.emptyList()));
   assertThrows(AccessDeniedException.class,()->api.periods(1));
  }finally{SecurityContextHolder.clearContext();}
 }
}
