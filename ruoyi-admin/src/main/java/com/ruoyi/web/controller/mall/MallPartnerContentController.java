package com.ruoyi.web.controller.mall;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.web.service.mall.MallPartnerContentService;
import com.ruoyi.web.service.mall.MallSessionService;

/** Public real-data endpoints for design pages 33-40. */
@Anonymous
@RestController
@RequestMapping("/mall")
public class MallPartnerContentController
{
    @Autowired private MallPartnerContentService service;
    @Autowired private MallSessionService sessions;

    @GetMapping("/partner/agreement") public AjaxResult agreement(){return AjaxResult.success(service.agreement());}
    @GetMapping("/partner/status") public AjaxResult status(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.partnerStatus(member(t)));}
    @PostMapping("/partner/applications") public AjaxResult apply(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.submitApplication(member(t),b));}
    @PostMapping("/partner/applications/{no}/cancel") public AjaxResult cancel(@PathVariable String no,@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.cancelApplication(member(t),no));}
    @GetMapping("/partner/workbench") public AjaxResult workbench(@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.workbench(member(t)));}
    @GetMapping("/partner/customers") public AjaxResult customers(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestParam(required=false)String keyword,@RequestParam(required=false,defaultValue="all")String segment,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.customers(member(t),keyword,segment,page,pageSize));}
    @GetMapping("/partner/customers/{id}/orders") public AjaxResult customerOrders(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.customerOrders(member(t),id,page,pageSize));}

    @GetMapping("/content/articles/{slug}") public AjaxResult article(@PathVariable String slug,@RequestHeader(value="X-Mall-Session",required=false)String t){return AjaxResult.success(service.article(slug,sessions.resolve(t).getCustomerId()));}
    @GetMapping("/content/categories") public AjaxResult contentCategories(){return AjaxResult.success(service.publicContentCategories());}
    @GetMapping("/content/articles") public AjaxResult contentArticles(@RequestParam(required=false,defaultValue="TEA_SCIENCE")String categoryCode){return AjaxResult.success(service.publicArticles(categoryCode));}
    @PutMapping("/content/articles/{id}/favorite") public AjaxResult favorite(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){service.favoriteArticle(member(t),id,bool(b.get("favorite")));return AjaxResult.success();}

    @GetMapping("/community/posts") public AjaxResult posts(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestParam(defaultValue="false")boolean mine,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.posts(sessions.resolve(t).getCustomerId(),mine,page,pageSize));}
    @PostMapping("/community/posts") public AjaxResult createPost(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.createPost(member(t),b));}
    @DeleteMapping("/community/posts/{id}") public AjaxResult deletePost(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t){service.deletePost(member(t),id);return AjaxResult.success();}
    @PutMapping("/community/posts/{id}/like") public AjaxResult like(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.like(member(t),id,bool(b.get("liked"))));}
    @GetMapping("/community/posts/{id}/comments") public AjaxResult comments(@PathVariable Long id){return AjaxResult.success(service.comments(id));}
    @PostMapping("/community/posts/{id}/comments") public AjaxResult comment(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){return AjaxResult.success(service.createComment(member(t),id,b));}
    @DeleteMapping("/community/comments/{id}") public AjaxResult deleteComment(@PathVariable Long id,@RequestHeader(value="X-Mall-Session",required=false)String t){service.deleteComment(member(t),id);return AjaxResult.success();}
    @PostMapping("/community/reports") public AjaxResult report(@RequestHeader(value="X-Mall-Session",required=false)String t,@RequestBody Map<String,Object>b){service.report(member(t),b);return AjaxResult.success();}

    @PostMapping("/community/images")
    public AjaxResult uploadImage(@RequestHeader(value="X-Mall-Session",required=false)String t,MultipartFile file)throws Exception
    {
        member(t);
        if(file==null||file.isEmpty())return AjaxResult.error(HttpStatus.BAD_REQUEST.value(),"请选择图片");
        if(file.getSize()>5L*1024L*1024L)return AjaxResult.error(HttpStatus.BAD_REQUEST.value(),"单张图片不能超过5MB");
        String type=file.getContentType()==null?"":file.getContentType().toLowerCase();
        byte[] bytes=file.getBytes();
        boolean jpeg=bytes.length>2&&(bytes[0]&255)==0xff&&(bytes[1]&255)==0xd8;
        boolean png=bytes.length>7&&(bytes[0]&255)==0x89&&bytes[1]==0x50&&bytes[2]==0x4e&&bytes[3]==0x47;
        boolean gif=bytes.length>5&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F';
        boolean webp=bytes.length>11&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'&&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P';
        if(!(type.equals("image/jpeg")||type.equals("image/png")||type.equals("image/gif")||type.equals("image/webp"))||!(jpeg||png||gif||webp))
            return AjaxResult.error(HttpStatus.BAD_REQUEST.value(),"仅支持真实的 JPG、PNG、GIF 或 WEBP 图片");
        // success(String) writes msg, not data. Both aftersale and community clients read data.
        String imagePath = FileUploadUtils.upload(RuoYiConfig.getUploadPath(),file);
        return AjaxResult.success("上传成功", imagePath);
    }

    private Long member(String token){return sessions.requireMember(token).getCustomerId();}
    private boolean bool(Object v){return Boolean.TRUE.equals(v)||"true".equalsIgnoreCase(String.valueOf(v))||"1".equals(String.valueOf(v));}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxResult badRequest(IllegalArgumentException e){return AjaxResult.error(HttpStatus.BAD_REQUEST.value(),e.getMessage());}
}
