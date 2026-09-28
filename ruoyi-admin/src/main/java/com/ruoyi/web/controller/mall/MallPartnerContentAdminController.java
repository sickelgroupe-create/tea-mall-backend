package com.ruoyi.web.controller.mall;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallPartnerContentService;

/** Permission-protected administration endpoints for pages 33-40. */
@RestController
@RequestMapping("/mall/admin")
public class MallPartnerContentAdminController
{
    @Autowired private MallPartnerContentService service;
    @Autowired private com.ruoyi.web.service.mall.MallPartnerFormService formService;
    @PreAuthorize("@ss.hasPermi('mall:partner:list')") @GetMapping("/partner/form-config") public AjaxResult formConfig(){return AjaxResult.success(formService.config());}
    @PreAuthorize("@ss.hasPermi('mall:partner:edit')") @PutMapping("/partner/form-config") public AjaxResult saveForm(@RequestBody Map<String,Object>b){formService.save(b,com.ruoyi.common.utils.SecurityUtils.getUserId());return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('mall:partner:list')") @GetMapping("/partner/applications") public AjaxResult applications(){return AjaxResult.success(service.adminApplications());}
    @PreAuthorize("@ss.hasPermi('mall:partner:list')") @GetMapping("/partner/audits") public AjaxResult audits(){return AjaxResult.success(service.adminAudits());}
    @PreAuthorize("@ss.hasPermi('mall:partner:edit')") @PutMapping("/partner/applications/{id}") public AjaxResult audit(@PathVariable Long id,@RequestBody Map<String,Object>b){service.auditApplication(id,b);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('mall:partner-stats:list')") @GetMapping("/partner/stats") public AjaxResult stats(){return AjaxResult.success(service.adminPartnerStats());}
    @PreAuthorize("@ss.hasPermi('mall:partner-stats:list')") @GetMapping("/partner/customers") public AjaxResult customers(){return AjaxResult.success(service.adminCustomerOwners());}
    @PreAuthorize("@ss.hasPermi('mall:partner-owner:edit')") @PutMapping("/partner/customers/{id}/owner") public AjaxResult owner(@PathVariable Long id,@RequestBody Map<String,Object>b){service.reassignCustomer(id,b);return AjaxResult.success();}

    @PreAuthorize("@ss.hasPermi('mall:content-category:list')") @GetMapping("/content/categories") public AjaxResult categories(){return AjaxResult.success(service.adminCategories());}
    @PreAuthorize("@ss.hasPermi('mall:content-category:edit')") @PostMapping("/content/categories") public AjaxResult createCategory(@RequestBody Map<String,Object>b){return AjaxResult.success(service.saveCategory(null,b));}
    @PreAuthorize("@ss.hasPermi('mall:content-category:edit')") @PutMapping("/content/categories/{id}") public AjaxResult updateCategory(@PathVariable Long id,@RequestBody Map<String,Object>b){return AjaxResult.success(service.saveCategory(id,b));}
    @PreAuthorize("@ss.hasPermi('mall:content-article:list')") @GetMapping("/content/articles") public AjaxResult articles(){return AjaxResult.success(service.adminArticles());}
    @PreAuthorize("@ss.hasPermi('mall:content-article:edit')") @PostMapping("/content/articles") public AjaxResult createArticle(@RequestBody Map<String,Object>b){return AjaxResult.success(service.saveArticle(null,b));}
    @PreAuthorize("@ss.hasPermi('mall:content-article:edit')") @PutMapping("/content/articles/{id}") public AjaxResult updateArticle(@PathVariable Long id,@RequestBody Map<String,Object>b){return AjaxResult.success(service.saveArticle(id,b));}

    @PreAuthorize("@ss.hasPermi('mall:community-post:list')") @GetMapping("/community/posts") public AjaxResult posts(){return AjaxResult.success(service.adminPosts());}
    @PreAuthorize("@ss.hasPermi('mall:community-post:edit')") @PutMapping("/community/posts/{id}") public AjaxResult updatePost(@PathVariable Long id,@RequestBody Map<String,Object>b){service.updatePost(id,b);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:list')") @GetMapping("/community/comments") public AjaxResult comments(){return AjaxResult.success(service.adminComments());}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:edit')") @PutMapping("/community/comments/{id}") public AjaxResult updateComment(@PathVariable Long id,@RequestBody Map<String,Object>b){service.updateComment(id,b);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:list')") @GetMapping("/community/likes") public AjaxResult likes(){return AjaxResult.success(service.adminLikes());}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:list')") @GetMapping("/community/reports") public AjaxResult reports(){return AjaxResult.success(service.adminReports());}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:edit')") @PutMapping("/community/reports/{id}") public AjaxResult updateReport(@PathVariable Long id,@RequestBody Map<String,Object>b){service.updateReport(id,b);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('mall:community-comment:list')") @GetMapping("/content/audits") public AjaxResult contentAudits(){return AjaxResult.success(service.adminContentAudits());}
}
