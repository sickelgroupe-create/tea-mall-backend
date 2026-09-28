package com.ruoyi.web.controller.mall;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.web.service.mall.MallTeaFriendService;
@RestController @RequestMapping("/mall/admin/tea-friends")
public class MallTeaFriendAdminController {
 private final MallTeaFriendService service;
 public MallTeaFriendAdminController(MallTeaFriendService service){this.service=service;}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping
 public AjaxResult data(){return AjaxResult.success(service.adminData());}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping("/exchanges")
 public AjaxResult exchanges(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status){return AjaxResult.success(service.adminPage(false,page,size,keyword,status));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:list')") @GetMapping("/ledger")
 public AjaxResult ledger(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String keyword){return AjaxResult.success(service.adminPage(true,page,size,keyword,""));}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PutMapping("/config") @Log(title="茶友邀请规则",businessType=BusinessType.UPDATE)
 public AjaxResult save(@RequestBody Map<String,Object> body){service.saveConfig(body,SecurityUtils.getUserId());return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PutMapping("/rewards/{id}") @Log(title="茶友兑换奖品",businessType=BusinessType.UPDATE)
 public AjaxResult reward(@PathVariable Long id,@RequestBody Map<String,Object> body){service.saveReward(id,body);return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:invite-gift:edit')") @PutMapping("/exchanges/{id}/review") @Log(title="茶友兑换审核",businessType=BusinessType.UPDATE)
 public AjaxResult review(@PathVariable Long id,@RequestBody Map<String,Object> body){service.review(id,body,SecurityUtils.getUserId());return AjaxResult.success();}
}
