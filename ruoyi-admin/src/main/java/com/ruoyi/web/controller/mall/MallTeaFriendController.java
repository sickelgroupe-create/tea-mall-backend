package com.ruoyi.web.controller.mall;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.*;
@Anonymous @RestController @RequestMapping("/mall/tea-friends")
public class MallTeaFriendController {
 private final MallTeaFriendService service; private final MallSessionService sessions;
 public MallTeaFriendController(MallTeaFriendService service,MallSessionService sessions){this.service=service;this.sessions=sessions;}
 @GetMapping("/config") public AjaxResult config(){return AjaxResult.success(service.config());}
 @GetMapping("/overview") public AjaxResult overview(@RequestHeader(value="X-Mall-Session",required=false)String token){return AjaxResult.success(service.overview(member(token)));}
 @GetMapping("/history") public AjaxResult history(@RequestHeader(value="X-Mall-Session",required=false)String token,@RequestParam String type,@RequestParam(defaultValue="1") int page){return AjaxResult.success(service.history(member(token),type,page));}
 @PostMapping("/exchanges") public AjaxResult exchange(@RequestHeader(value="X-Mall-Session",required=false)String token,@RequestBody Map<String,Object> body){return AjaxResult.success(service.exchange(member(token),body));}
 private Long member(String token){return sessions.requireMember(token).getCustomerId();}
 @ExceptionHandler(MallAuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
 public AjaxResult authentication(MallAuthenticationException e){return AjaxResult.error(401,e.getMessage());}
 @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
 public AjaxResult invalid(RuntimeException e){return AjaxResult.error(400,e instanceof ArithmeticException?"数值格式不正确":e.getMessage());}
}
