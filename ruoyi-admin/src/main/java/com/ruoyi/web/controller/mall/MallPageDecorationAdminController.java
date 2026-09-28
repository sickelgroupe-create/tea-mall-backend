package com.ruoyi.web.controller.mall;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallPageDecorationService;

/** Permission-protected material library and page-decoration management. */
@RestController
@RequestMapping("/mall/admin/decoration")
public class MallPageDecorationAdminController
{
    @Autowired private MallPageDecorationService service;

    @PreAuthorize("@ss.hasPermi('mall:decoration:list')")
    @GetMapping("/materials") public AjaxResult materials(){return AjaxResult.success(service.adminMaterials());}

    @PreAuthorize("@ss.hasPermi('mall:decoration:upload')")
    @PostMapping("/materials/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
            @RequestParam Map<String,String> meta) throws Exception
    { return AjaxResult.success(service.upload(file, meta)); }

    @PreAuthorize("@ss.hasPermi('mall:decoration:edit')")
    @DeleteMapping("/materials/{id}")
    public AjaxResult disable(@PathVariable Long id){service.disableMaterial(id);return AjaxResult.success();}

    @PreAuthorize("@ss.hasPermi('mall:decoration:list')")
    @GetMapping("/modules") public AjaxResult modules(){return AjaxResult.success(service.adminModules());}

    @PreAuthorize("@ss.hasPermi('mall:decoration:edit')")
    @PostMapping("/modules") public AjaxResult create(@RequestBody Map<String,Object> body) throws Exception{return AjaxResult.success(service.saveModule(null,body));}

    @PreAuthorize("@ss.hasPermi('mall:decoration:edit')")
    @PutMapping("/modules/{id}") public AjaxResult update(@PathVariable Long id,@RequestBody Map<String,Object> body) throws Exception{return AjaxResult.success(service.saveModule(id,body));}

    @PreAuthorize("@ss.hasPermi('mall:decoration:list')")
    @GetMapping("/modules/{id}/history") public AjaxResult history(@PathVariable Long id){return AjaxResult.success(service.history(id));}

    @PreAuthorize("@ss.hasPermi('mall:decoration:restore')")
    @PutMapping("/modules/{id}/restore/{historyId}")
    public AjaxResult restore(@PathVariable Long id,@PathVariable Long historyId) throws Exception{return AjaxResult.success(service.restore(id,historyId));}
}
