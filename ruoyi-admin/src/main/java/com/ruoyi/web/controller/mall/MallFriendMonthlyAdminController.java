package com.ruoyi.web.controller.mall;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.web.service.mall.MallFriendMonthlyService;
@RestController @RequestMapping("/mall/admin/tea-friends/monthly")
public class MallFriendMonthlyAdminController {
 private final MallFriendMonthlyService service;
 public MallFriendMonthlyAdminController(MallFriendMonthlyService s){service=s;}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping("/periods") public AjaxResult periods(@RequestParam(defaultValue="1")int page){return AjaxResult.success(service.periods(true,page));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping("/awards") public AjaxResult awards(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Long periodId){return AjaxResult.success(service.awards(null,page,periodId));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping("/periods/{id}") public AjaxResult board(@PathVariable long id){return AjaxResult.success(service.board(id,-1));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/periods") @Log(title="月度奖励草稿",businessType=BusinessType.UPDATE) public AjaxResult save(@RequestBody Map<String,Object>b){return AjaxResult.success(service.save(b,SecurityUtils.getUserId()));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/periods/{id}/publish") @Log(title="发布月度奖励",businessType=BusinessType.UPDATE) public AjaxResult publish(@PathVariable long id,@RequestBody Map<String,Object>b){service.publish(id,b,SecurityUtils.getUserId());return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/periods/{id}/settle") @Log(title="结算月度奖励",businessType=BusinessType.UPDATE) public AjaxResult settle(@PathVariable long id,@RequestBody Map<String,Object>b){service.settle(id,b,SecurityUtils.getUserId());return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/periods/{id}/close") @Log(title="关闭月度奖励",businessType=BusinessType.UPDATE) public AjaxResult close(@PathVariable long id,@RequestBody Map<String,Object>b){service.close(id,b,SecurityUtils.getUserId());return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/awards/{id}/payout") @Log(title="月度奖励发放登记",businessType=BusinessType.UPDATE) public AjaxResult payout(@PathVariable long id,@RequestBody Map<String,Object>b){service.payout(id,b,SecurityUtils.getUserId());return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PostMapping("/awards/{id}/reply") @Log(title="月度奖励争议回复",businessType=BusinessType.UPDATE) public AjaxResult reply(@PathVariable long id,@RequestBody Map<String,Object>b){service.reply(id,b,SecurityUtils.getUserId());return AjaxResult.success();}
}
