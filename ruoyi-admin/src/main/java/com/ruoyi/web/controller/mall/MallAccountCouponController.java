package com.ruoyi.web.controller.mall;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallAccountCouponService;
import com.ruoyi.web.service.mall.MallAuthenticationException;
import com.ruoyi.web.service.mall.MallSessionService;

/** Public member APIs for pages 41-48. */
@Anonymous @RestController @RequestMapping("/mall")
public class MallAccountCouponController
{
 @Autowired private MallAccountCouponService service; @Autowired private MallSessionService sessions;
 @GetMapping("/account/commission") public AjaxResult commission(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.commissionCenter(member(t)));}
 @GetMapping("/account/commission/details") public AjaxResult details(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.commissionDetails(member(t)));}
 @GetMapping("/account/withdrawals/config") public AjaxResult config(@RequestHeader(value="X-Mall-Session",required=false)String t){member(t);return AjaxResult.success(service.withdrawalConfig());}
 @PostMapping("/account/withdrawals") public AjaxResult withdraw(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.requestWithdrawal(member(t),b));}
 @PostMapping("/account/withdrawals/{no}/cancel") public AjaxResult cancel(@PathVariable String no,@RequestHeader(value="X-Mall-Session",required=false)String t){service.cancelWithdrawal(member(t),no);return AjaxResult.success();}
 @GetMapping("/account/coupons") public AjaxResult coupons(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestParam(defaultValue="全部")String status){return AjaxResult.success(service.coupons(member(t),status));}
 @GetMapping("/account/coupons/available") public AjaxResult available(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.availableCoupons(member(t)));}
 @PostMapping("/account/coupons/{id}/claim") public AjaxResult claim(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.claimCoupon(member(t),id));}
 @GetMapping("/account/dashboard") public AjaxResult dashboard(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.profileDashboard(member(t)));}
 @GetMapping("/account/preferences") public AjaxResult prefs(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.preferences(member(t)));}
 @PutMapping("/account/preferences") public AjaxResult savePrefs(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.savePreferences(member(t),b));}
 @GetMapping("/support/documents") public AjaxResult docs(){return AjaxResult.success(service.documents());}
 @GetMapping("/account/browse-history") public AjaxResult history(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.browseHistory(member(t)));}
 @PutMapping("/account/browse-history/{id}") public AjaxResult browse(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t){service.recordBrowse(member(t),id);return AjaxResult.success();}
 @PostMapping("/account/browse-history/merge") public AjaxResult merge(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){Object v=b.get("productIds");service.mergeBrowse(member(t),v instanceof List?(List<?>)v:null);return AjaxResult.success();}
 @DeleteMapping("/account/browse-history/{id}") public AjaxResult deleteHistory(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t){service.deleteBrowse(member(t),id);return AjaxResult.success();}
 @DeleteMapping("/account/browse-history") public AjaxResult clear(@RequestHeader(value="X-Mall-Session",required=false)String t){service.clearBrowse(member(t));return AjaxResult.success();}
 private Long member(String t){return sessions.requireMember(t).getCustomerId();}
 @ExceptionHandler(MallAuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
 public AjaxResult unauthorized(MallAuthenticationException e){return AjaxResult.error(HttpStatus.UNAUTHORIZED.value(),e.getMessage());}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public AjaxResult bad(IllegalArgumentException e){return AjaxResult.error(400,e.getMessage());}
}
