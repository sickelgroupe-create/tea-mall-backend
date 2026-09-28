package com.ruoyi.web.controller.mall;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallAccountCouponService;

/** Permission-protected administration APIs for pages 41-48. */
@RestController @RequestMapping("/mall/admin")
public class MallAccountCouponAdminController
{
 @Autowired private MallAccountCouponService service;
 @PreAuthorize("@ss.hasPermi('mall:commission-ledger:list')") @GetMapping("/commission/rules") public AjaxResult rules(){return AjaxResult.success(service.adminCommissionRules());}
 @PreAuthorize("@ss.hasPermi('mall:commission-ledger:list')") @GetMapping("/commission/ledger") public AjaxResult ledger(){return AjaxResult.success(service.adminCommissionLedger());}
 @PreAuthorize("@ss.hasPermi('mall:commission-ledger:edit')") @PutMapping("/commission/rules/{id}") @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.GONE) public AjaxResult rule(@PathVariable Long id,@RequestBody Map<String,Object>b){return AjaxResult.error(410,"旧比例及多级佣金规则已停用，请到商品管理设置直属固定提成；历史记录仅供查询");}
 @PreAuthorize("@ss.hasPermi('mall:withdraw-config:list')") @GetMapping("/withdrawals-v2") public AjaxResult withdrawals(){return AjaxResult.success(service.adminWithdrawals());}
 @PreAuthorize("@ss.hasPermi('mall:withdraw-config:list')") @GetMapping("/withdrawal-audits") public AjaxResult audits(){return AjaxResult.success(service.adminWithdrawalAudits());}
 @PreAuthorize("@ss.hasPermi('mall:withdraw-config:list')") @GetMapping("/withdrawal-config") public AjaxResult config(){return AjaxResult.success(service.withdrawalConfig());}
 @PreAuthorize("@ss.hasPermi('mall:withdraw-config:edit')") @PutMapping("/withdrawal-config") public AjaxResult saveConfig(@RequestBody Map<String,Object>b){service.saveWithdrawalConfig(b);return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:withdraw-config:edit')") @PutMapping("/withdrawals-v2/{id}") public AjaxResult review(@PathVariable Long id,@RequestBody Map<String,Object>b){service.reviewWithdrawal(id,b);return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:coupon:list')") @GetMapping("/coupon/templates") public AjaxResult coupons(){return AjaxResult.success(service.adminCouponTemplates());}
 @PreAuthorize("@ss.hasPermi('mall:coupon:list')") @GetMapping("/coupon/customer-coupons") public AjaxResult customerCoupons(){return AjaxResult.success(service.adminCustomerCoupons());}
 @PreAuthorize("@ss.hasPermi('mall:coupon:list')") @GetMapping("/coupon/logs") public AjaxResult couponLogs(){return AjaxResult.success(service.adminCouponLogs());}
 @PreAuthorize("@ss.hasPermi('mall:coupon:edit')") @PutMapping("/coupon/templates/{id}") public AjaxResult saveCoupon(@PathVariable Long id,@RequestBody Map<String,Object>b){service.saveCouponTemplate(id,b);return AjaxResult.success();}
 @PreAuthorize("@ss.hasPermi('mall:support:list')") @GetMapping("/support/documents") public AjaxResult documents(){return AjaxResult.success(service.adminDocuments());}
 @PreAuthorize("@ss.hasPermi('mall:support:list')") @GetMapping("/support/browse-history") public AjaxResult browse(){return AjaxResult.success(service.adminBrowse());}
 @PreAuthorize("@ss.hasPermi('mall:support:edit')") @PutMapping("/support/documents/{id}") public AjaxResult saveDocument(@PathVariable Long id,@RequestBody Map<String,Object>b){service.saveDocument(id,b);return AjaxResult.success();}
}
