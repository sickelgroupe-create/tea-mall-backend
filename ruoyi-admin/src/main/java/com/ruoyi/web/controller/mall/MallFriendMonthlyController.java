package com.ruoyi.web.controller.mall;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.*;
@Anonymous @RestController @RequestMapping("/mall/tea-friends/monthly")
public class MallFriendMonthlyController {
 private final MallFriendMonthlyService service;private final MallSessionService sessions;
 public MallFriendMonthlyController(MallFriendMonthlyService s,MallSessionService a){service=s;sessions=a;}
 private long member(String token){return sessions.requireMember(token).getCustomerId();}
 @GetMapping("/periods") public AjaxResult periods(@RequestHeader(value="X-Mall-Session",required=false)String token,@RequestParam(defaultValue="1")int page){member(token);return AjaxResult.success(service.periods(false,page));}
 @GetMapping("/periods/{id}") public AjaxResult board(@RequestHeader(value="X-Mall-Session",required=false)String token,@PathVariable long id){return AjaxResult.success(service.board(id,member(token)));}
 @GetMapping("/awards") public AjaxResult awards(@RequestHeader(value="X-Mall-Session",required=false)String token,@RequestParam(defaultValue="1")int page){return AjaxResult.success(service.awards(member(token),page,null));}
 @PostMapping("/awards/{id}/acknowledge") public AjaxResult acknowledge(@RequestHeader(value="X-Mall-Session",required=false)String token,@PathVariable long id,@RequestBody Map<String,Object>b){service.acknowledge(id,member(token),b);return AjaxResult.success();}
 @ExceptionHandler(MallAuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED) public AjaxResult authentication(MallAuthenticationException e){return AjaxResult.error(401,e.getMessage());}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(400,e.getMessage());}
}
