package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.utils.SecurityUtils;

/** Stage 33-40 partner, managed-content and community business service. */
@Service
public class MallPartnerContentService
{
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern ID_NO = Pattern.compile("^(?:\\d{15}|\\d{17}[0-9Xx])$");
    private static final String VALID_ORDER = "o.payment_status='已支付' AND o.status NOT IN ('已取消','已退款','退款完成') "
            + "AND NOT EXISTS(SELECT 1 FROM mall_aftersale af WHERE af.order_id=o.id AND af.status NOT IN ('已拒绝','已取消'))";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MallPartnerFormService formService;

    public Map<String,Object> agreement()
    {
        Map<String,Object> result=one("SELECT id,title,version,content,effective_time AS effectiveTime FROM mall_partner_agreement "
                + "WHERE status='0' AND effective_time<=NOW() ORDER BY effective_time DESC,id DESC LIMIT 1");
        result.put("formConfig",formService.config());
        return result;
    }

    public Map<String,Object> partnerStatus(Long customerId)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        Map<String,Object> distributor = first("SELECT partner_status AS partnerStatus,partner_approved_time AS approvedTime "
                + "FROM mall_distributor WHERE customer_id=?",customerId);
        Map<String,Object> application = first("SELECT id,application_no AS applicationNo,real_name AS realName,region,phone,status,"
                + "reject_reason AS rejectReason,agreement_version AS agreementVersion,submitted_time AS submittedTime,"
                + "reviewed_time AS reviewedTime,cancelled_time AS cancelledTime FROM mall_partner_application "
                + "WHERE customer_id=? ORDER BY id DESC LIMIT 1",customerId);
        result.put("partnerStatus",distributor==null?"未申请":distributor.get("partnerStatus"));
        result.put("approvedTime",distributor==null?null:distributor.get("approvedTime"));
        result.put("application",application);
        if(application!=null) result.put("audits",jdbc.queryForList("SELECT from_status AS fromStatus,to_status AS toStatus,"
                + "operator_type AS operatorType,reason,create_time AS createTime FROM mall_partner_audit "
                + "WHERE application_id=? ORDER BY id",application.get("id")));
        else result.put("audits",new ArrayList<>());
        return result;
    }

    @Transactional
    public Map<String,Object> submitApplication(Long customerId,Map<String,Object> body)
    {
        jdbc.queryForList("SELECT id FROM mall_customer WHERE id=? FOR UPDATE",customerId);
        int active=jdbc.queryForObject("SELECT COUNT(*) FROM mall_partner_application WHERE customer_id=? AND status IN ('待审核','审核通过')",Integer.class,customerId);
        if(active>0) throw new IllegalArgumentException("已有待审核或已通过的申请，不能重复提交");
        Map<String,Object> validated=formService.validate(body);
        String name=text(validated.get("realName")),idNo=text(validated.get("idNo")),region=text(validated.get("region"));
        String address=text(validated.get("address")),phone=text(validated.get("phone")),reason=text(validated.get("reason"));
        if(!bool(body.get("agreed"))) throw new IllegalArgumentException("请阅读并同意合伙人服务协议");
        Map<String,Object> agreement=agreement();
        if(!text(agreement.get("version")).equals(text(body.get("agreementVersion")))) throw new IllegalArgumentException("协议版本已更新，请重新阅读后提交");
        String no="PA"+System.currentTimeMillis()+String.format("%03d",ThreadLocalRandom.current().nextInt(1000));
        jdbc.update("INSERT INTO mall_partner_application(application_no,customer_id,real_name,id_no,region,address,phone,reason,agreement_id,agreement_version,status) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,'待审核')",no,customerId,name,idNo,region,address,phone,reason,agreement.get("id"),agreement.get("version"));
        Long id=jdbc.queryForObject("SELECT id FROM mall_partner_application WHERE application_no=?",Long.class,no);
        jdbc.update("INSERT INTO mall_partner_audit(application_id,from_status,to_status,operator_type,operator_id,reason) VALUES(?,'未申请','待审核','用户',?,'提交申请')",id,customerId);
        jdbc.update("UPDATE mall_distributor SET partner_status='待审核' WHERE customer_id=?",customerId);
        notifyCustomer(customerId,"合伙人申请已提交","申请 "+no+" 已进入审核流程","SUBMITTED",no);
        return partnerStatus(customerId);
    }

    @Transactional
    public Map<String,Object> cancelApplication(Long customerId,String applicationNo)
    {
        Map<String,Object> row=first("SELECT id,status FROM mall_partner_application WHERE application_no=? AND customer_id=? FOR UPDATE",applicationNo,customerId);
        if(row==null) throw new IllegalArgumentException("申请不存在或无权操作");
        if(!"待审核".equals(text(row.get("status")))) throw new IllegalArgumentException("只有待审核申请可以取消");
        jdbc.update("UPDATE mall_partner_application SET status='已取消',cancelled_time=NOW() WHERE id=?",row.get("id"));
        jdbc.update("INSERT INTO mall_partner_audit(application_id,from_status,to_status,operator_type,operator_id,reason) VALUES(?,'待审核','已取消','用户',?,'用户取消')",row.get("id"),customerId);
        jdbc.update("UPDATE mall_distributor SET partner_status='已取消' WHERE customer_id=?",customerId);
        return partnerStatus(customerId);
    }

    public Map<String,Object> workbench(Long customerId)
    {
        requirePartner(customerId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.putAll(one("SELECT COUNT(*) AS customerCount,SUM(CASE WHEN d.create_time>=CURDATE() THEN 1 ELSE 0 END) AS newCustomerCount "
                + "FROM mall_distributor d WHERE d.parent_customer_id=?",customerId));
        result.putAll(one("SELECT COUNT(DISTINCT o.customer_id) AS activeCustomerCount,COUNT(*) AS orderCount,COALESCE(SUM(o.paid_amount),0) AS salesAmount "
                + "FROM mall_order o JOIN mall_distributor d ON d.customer_id=o.customer_id WHERE d.parent_customer_id=? AND "+VALID_ORDER,customerId));
        result.putAll(one("SELECT COALESCE(SUM(CASE WHEN status='待结算' THEN amount ELSE 0 END),0) AS pendingIncome,"
                + "COALESCE(SUM(CASE WHEN status='已结算' THEN amount ELSE 0 END),0) AS settledIncome FROM mall_commission WHERE beneficiary_id=?",customerId));
        result.put("today",one("SELECT COUNT(*) AS orderCount,COALESCE(SUM(o.paid_amount),0) AS salesAmount FROM mall_order o "
                + "JOIN mall_distributor d ON d.customer_id=o.customer_id WHERE d.parent_customer_id=? AND o.create_time>=CURDATE() AND "+VALID_ORDER,customerId));
        return result;
    }

    public Map<String,Object> customers(Long ownerId,String keyword,String segment,int page,int pageSize)
    {
        requirePartner(ownerId); page=Math.max(1,page); pageSize=Math.max(1,Math.min(50,pageSize));
        String key="%"+(keyword==null?"":keyword.trim())+"%";
        String having="";
        if("month".equals(segment)) having=" HAVING recentPurchase>=DATE_SUB(NOW(),INTERVAL 30 DAY)";
        if("valuable".equals(segment)) having=" HAVING salesAmount>=500";
        String base=" FROM mall_distributor d JOIN mall_customer c ON c.id=d.customer_id LEFT JOIN mall_order o ON o.customer_id=c.id AND "+VALID_ORDER
                + " WHERE d.parent_customer_id=? AND (c.nickname LIKE ? OR c.phone LIKE ?) GROUP BY c.id,c.nickname,c.phone,c.avatar_url,d.create_time"+having;
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.id,c.nickname,c.phone,c.avatar_url AS avatarUrl,d.create_time AS bindTime,"
                + "COUNT(o.id) AS orderCount,COALESCE(SUM(o.paid_amount),0) AS salesAmount,MAX(o.create_time) AS recentPurchase"+base
                + " ORDER BY d.create_time DESC LIMIT ? OFFSET ?",ownerId,key,key,pageSize,(page-1)*pageSize);
        for(Map<String,Object> row:rows) row.put("phone",maskPhone(text(row.get("phone"))));
        Map<String,Object> result=new LinkedHashMap<>(); result.put("items",rows); result.put("page",page); result.put("pageSize",pageSize); return result;
    }

    public Map<String,Object> customerOrders(Long ownerId,Long customerId,int page,int pageSize)
    {
        requirePartner(ownerId);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor WHERE customer_id=? AND parent_customer_id=?",Integer.class,customerId,ownerId)==0)
            throw new IllegalArgumentException("客户不存在或无权查看");
        page=Math.max(page,1); pageSize=Math.max(1,Math.min(50,pageSize));
        Map<String,Object> customer=one("SELECT id,nickname,phone FROM mall_customer WHERE id=?",customerId); customer.put("phone",maskPhone(text(customer.get("phone"))));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT o.id,o.order_no AS orderNo,o.status,o.payment_status AS paymentStatus,o.paid_amount AS paidAmount,"
                + "o.create_time AS createTime,GROUP_CONCAT(oi.product_name ORDER BY oi.id SEPARATOR '、') AS products FROM mall_order o LEFT JOIN mall_order_item oi ON oi.order_id=o.id "
                + "WHERE o.customer_id=? GROUP BY o.id ORDER BY o.id DESC LIMIT ? OFFSET ?",customerId,pageSize,(page-1)*pageSize);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("customer",customer); result.put("items",rows); return result;
    }

    @Transactional
    public Map<String,Object> article(String slug,Long customerId)
    {
        Map<String,Object> article=first("SELECT a.id,a.slug,a.title,a.summary,a.cover_image_key AS coverImageKey,a.body_html AS bodyHtml,a.published_at AS publishedAt,"
                + "a.favorite_count AS favoriteCount,a.view_count AS viewCount,c.category_name AS categoryName FROM mall_content_article a "
                + "JOIN mall_content_category c ON c.id=a.category_id WHERE a.slug=? AND a.status='0' AND c.status='0' AND a.published_at<=NOW()",slug);
        if(article==null) throw new IllegalArgumentException("内容不存在、未发布或已下架");
        int added=jdbc.update("INSERT IGNORE INTO mall_content_view(article_id,customer_id,view_date) VALUES(?,?,CURDATE())",article.get("id"),customerId);
        if(added>0) jdbc.update("UPDATE mall_content_article SET view_count=view_count+1 WHERE id=?",article.get("id"));
        article.put("bodyHtml",sanitizeHtml(text(article.get("bodyHtml"))));
        article.put("favorite",jdbc.queryForObject("SELECT COUNT(*) FROM mall_content_favorite WHERE article_id=? AND customer_id=?",Integer.class,article.get("id"),customerId)>0);
        article.put("products",jdbc.queryForList("SELECT p.id,p.name,p.price,p.image_key AS imageKey,p.stock FROM mall_content_article_product r "
                + "JOIN mall_product p ON p.id=r.product_id WHERE r.article_id=? AND p.status='0' ORDER BY r.sort_no",article.get("id")));
        return article;
    }

    public List<Map<String,Object>> publicContentCategories()
    {
        return jdbc.queryForList("SELECT id,parent_id AS parentId,category_code AS categoryCode,category_name AS categoryName,description,sort_no AS sortNo FROM mall_content_category WHERE status='0' ORDER BY parent_id,sort_no,id");
    }

    public List<Map<String,Object>> publicArticles(String categoryCode)
    {
        String code=text(categoryCode).trim();
        if(code.isEmpty()) code="TEA_SCIENCE";
        return jdbc.queryForList("SELECT a.id,a.slug,a.title,a.summary,a.cover_image_key AS coverImageKey,a.published_at AS publishedAt,a.sort_no AS sortNo,c.category_code AS categoryCode,c.category_name AS categoryName FROM mall_content_article a JOIN mall_content_category c ON c.id=a.category_id WHERE a.status='0' AND c.status='0' AND a.published_at<=NOW() AND (c.category_code=? OR c.parent_id=(SELECT id FROM mall_content_category WHERE category_code=? LIMIT 1)) ORDER BY a.sort_no,a.published_at DESC,a.id DESC",code,code);
    }

    @Transactional
    public void favoriteArticle(Long customerId,Long articleId,boolean favorite)
    {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_content_article WHERE id=? AND status='0'",Integer.class,articleId)==0) throw new IllegalArgumentException("内容不存在或已下架");
        if(favorite) jdbc.update("INSERT IGNORE INTO mall_content_favorite(article_id,customer_id) VALUES(?,?)",articleId,customerId);
        else jdbc.update("DELETE FROM mall_content_favorite WHERE article_id=? AND customer_id=?",articleId,customerId);
        jdbc.update("UPDATE mall_content_article SET favorite_count=(SELECT COUNT(*) FROM mall_content_favorite f WHERE f.article_id=?) WHERE id=?",articleId,articleId);
    }

    public Map<String,Object> posts(Long customerId,boolean mine,int page,int pageSize)
    {
        page=Math.max(1,page); pageSize=Math.max(1,Math.min(30,pageSize));
        String where=mine?"p.customer_id=? AND p.status<>'已删除'":"p.status='正常' AND p.visibility='公开'";
        List<Map<String,Object>> rows=mine?jdbc.queryForList(postSelect()+" WHERE "+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",customerId,pageSize,(page-1)*pageSize)
                :jdbc.queryForList(postSelect()+" WHERE "+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",pageSize,(page-1)*pageSize);
        attachPosts(rows,customerId);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("items",rows); result.put("page",page); return result;
    }

    @Transactional
    public Map<String,Object> createPost(Long customerId,Map<String,Object> body)
    {
        String content=plain(required(body,"content",1,1000));
        jdbc.update("INSERT INTO mall_community_post(customer_id,content,status,visibility) VALUES(?,?,'正常','公开')",customerId,content);
        Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        Object images=body.get("images"); int sort=0;
        if(images instanceof Iterable) for(Object image:(Iterable<?>)images){ if(sort>=9) throw new IllegalArgumentException("最多上传9张图片"); String url=text(image).trim(); if(!url.isEmpty()){ validateUploadUrl(url); jdbc.update("INSERT INTO mall_community_post_image(post_id,image_url,sort_no) VALUES(?,?,?)",id,url,sort++); }}
        return one(postSelect()+" WHERE p.id=?",id);
    }

    @Transactional
    public Map<String,Object> like(Long customerId,Long postId,boolean liked)
    {
        Map<String,Object> post=first("SELECT id,status FROM mall_community_post WHERE id=? FOR UPDATE",postId);
        if(post==null||!"正常".equals(text(post.get("status")))) throw new IllegalArgumentException("动态不存在或不可见");
        if(liked) jdbc.update("INSERT IGNORE INTO mall_community_like(post_id,customer_id) VALUES(?,?)",postId,customerId);
        else jdbc.update("DELETE FROM mall_community_like WHERE post_id=? AND customer_id=?",postId,customerId);
        jdbc.update("UPDATE mall_community_post SET like_count=(SELECT COUNT(*) FROM mall_community_like WHERE post_id=?) WHERE id=?",postId,postId);
        return one("SELECT like_count AS likeCount FROM mall_community_post WHERE id=?",postId);
    }

    public List<Map<String,Object>> comments(Long postId)
    {
        requireVisiblePost(postId);
        return jdbc.queryForList("SELECT m.id,m.customer_id AS customerId,c.nickname,c.avatar_url AS avatarUrl,m.content,m.create_time AS createTime "
                + "FROM mall_community_comment m JOIN mall_customer c ON c.id=m.customer_id WHERE m.post_id=? AND m.status='正常' ORDER BY m.id",postId);
    }

    @Transactional
    public Map<String,Object> createComment(Long customerId,Long postId,Map<String,Object> body)
    {
        requireVisiblePost(postId); String content=plain(required(body,"content",1,500));
        jdbc.update("INSERT INTO mall_community_comment(post_id,customer_id,content,status) VALUES(?,?,?,'正常')",postId,customerId,content);
        Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("UPDATE mall_community_post SET comment_count=(SELECT COUNT(*) FROM mall_community_comment WHERE post_id=? AND status='正常') WHERE id=?",postId,postId);
        return one("SELECT id,post_id AS postId,customer_id AS customerId,content,create_time AS createTime FROM mall_community_comment WHERE id=?",id);
    }

    @Transactional
    public void deletePost(Long customerId,Long postId)
    {
        int n=jdbc.update("UPDATE mall_community_post SET status='已删除' WHERE id=? AND customer_id=? AND status<>'已删除'",postId,customerId);
        if(n==0) throw new IllegalArgumentException("动态不存在或无权删除");
        jdbc.update("UPDATE mall_community_comment SET status='已删除' WHERE post_id=?",postId);
        jdbc.update("DELETE FROM mall_community_like WHERE post_id=?",postId);
        jdbc.update("UPDATE mall_community_post SET like_count=0,comment_count=0 WHERE id=?",postId);
    }

    @Transactional
    public void deleteComment(Long customerId,Long commentId)
    {
        Map<String,Object> row=first("SELECT id,post_id AS postId FROM mall_community_comment WHERE id=? AND customer_id=? AND status='正常'",commentId,customerId);
        if(row==null) throw new IllegalArgumentException("评论不存在或无权删除");
        jdbc.update("UPDATE mall_community_comment SET status='已删除' WHERE id=?",commentId);
        jdbc.update("UPDATE mall_community_post SET comment_count=(SELECT COUNT(*) FROM mall_community_comment WHERE post_id=? AND status='正常') WHERE id=?",row.get("postId"),row.get("postId"));
    }

    public List<Map<String,Object>> adminApplications(){return jdbc.queryForList("SELECT a.*,a.reason AS application_reason,c.nickname,c.phone AS customerPhone FROM mall_partner_application a JOIN mall_customer c ON c.id=a.customer_id ORDER BY a.id DESC");}
    public List<Map<String,Object>> adminAudits(){return jdbc.queryForList("SELECT a.*,p.application_no AS applicationNo FROM mall_partner_audit a JOIN mall_partner_application p ON p.id=a.application_id ORDER BY a.id DESC");}
    public List<Map<String,Object>> adminCustomerOwners(){return jdbc.queryForList("SELECT d.customer_id AS customerId,c.nickname,c.phone,d.parent_customer_id AS ownerId,o.nickname AS ownerName,d.create_time AS bindTime FROM mall_distributor d JOIN mall_customer c ON c.id=d.customer_id LEFT JOIN mall_customer o ON o.id=d.parent_customer_id WHERE d.parent_customer_id IS NOT NULL ORDER BY d.id DESC");}

    @Transactional
    public void reassignCustomer(Long customerId,Map<String,Object> body)
    {
        Long ownerId=longValue(body.get("ownerId")); String reason=plain(required(body,"reason",3,500));
        if(ownerId==null||ownerId.equals(customerId))throw new IllegalArgumentException("客户不能归属自己");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor WHERE customer_id=? AND partner_status='审核通过'",Integer.class,ownerId)==0)throw new IllegalArgumentException("新归属人不是已审核合伙人");
        Map<String,Object> row=one("SELECT parent_customer_id AS oldOwner FROM mall_distributor WHERE customer_id=? FOR UPDATE",customerId);
        Long cursor=ownerId; for(int depth=0;depth<20&&cursor!=null;depth++){if(cursor.equals(customerId))throw new IllegalArgumentException("归属调整会形成循环关系");Map<String,Object> p=first("SELECT parent_customer_id AS parentId FROM mall_distributor WHERE customer_id=?",cursor);cursor=p==null?null:longValue(p.get("parentId"));}
        jdbc.update("UPDATE mall_distributor SET parent_customer_id=? WHERE customer_id=?",ownerId,customerId);
        jdbc.update("INSERT INTO mall_customer_owner_log(customer_id,old_owner_id,new_owner_id,operator_id,reason) VALUES(?,?,?,?,?)",customerId,row.get("oldOwner"),ownerId,SecurityUtils.getUserId(),reason);
    }

    @Transactional
    public void auditApplication(Long id,Map<String,Object> body)
    {
        Map<String,Object> app=first("SELECT * FROM mall_partner_application WHERE id=? FOR UPDATE",id);
        if(app==null) throw new IllegalArgumentException("申请不存在");
        String target=text(body.get("status")); if(!"审核通过".equals(target)&&!"审核驳回".equals(target)) throw new IllegalArgumentException("非法审核状态");
        if(!"待审核".equals(text(app.get("status")))) throw new IllegalArgumentException("只有待审核申请可以审核");
        String reason=plain(text(body.get("reason")));
        if("审核驳回".equals(target)&&reason.trim().isEmpty()) throw new IllegalArgumentException("驳回时必须填写原因");
        jdbc.update("UPDATE mall_partner_application SET status=?,reject_reason=?,reviewed_time=NOW() WHERE id=?",target,reason,id);
        jdbc.update("INSERT INTO mall_partner_audit(application_id,from_status,to_status,operator_type,operator_id,reason) VALUES(?,'待审核',?,'管理员',?,?)",id,target,SecurityUtils.getUserId(),reason);
        jdbc.update("UPDATE mall_distributor SET partner_status=?,partner_approved_time=CASE WHEN ?='审核通过' THEN NOW() ELSE partner_approved_time END WHERE customer_id=?",target,target,app.get("customer_id"));
        notifyCustomer(longValue(app.get("customer_id")),"合伙人申请审核结果","申请 "+app.get("application_no")+" 已更新为“"+target+"”"+(reason.isEmpty()?"":"："+reason),"ADMIN_"+target,text(app.get("application_no")));
    }

    public Map<String,Object> adminPartnerStats()
    {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("summary",jdbc.queryForList("SELECT partner_status AS status,COUNT(*) AS count FROM mall_distributor GROUP BY partner_status"));
        r.put("partners",jdbc.queryForList("SELECT d.customer_id AS customerId,c.nickname,c.phone,d.partner_status AS partnerStatus,"
                + "COUNT(DISTINCT child.customer_id) AS customerCount,COUNT(DISTINCT o.id) AS orderCount,COALESCE(SUM(o.paid_amount),0) AS salesAmount "
                + "FROM mall_distributor d JOIN mall_customer c ON c.id=d.customer_id LEFT JOIN mall_distributor child ON child.parent_customer_id=d.customer_id "
                + "LEFT JOIN mall_order o ON o.customer_id=child.customer_id AND "+VALID_ORDER+" GROUP BY d.customer_id ORDER BY salesAmount DESC"));
        return r;
    }

    public List<Map<String,Object>> adminCategories(){return jdbc.queryForList("SELECT id,parent_id AS parentId,category_code AS categoryCode,category_name AS categoryName,description,status,sort_no AS sortNo,create_time AS createTime FROM mall_content_category ORDER BY parent_id,sort_no,id");}
    public List<Map<String,Object>> adminArticles(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.id,a.category_id AS categoryId,a.slug,a.title,a.summary,a.cover_image_key AS coverImageKey,a.body_html AS bodyHtml,a.status,a.published_at AS publishedAt,a.sort_no AS sortNo,a.favorite_count AS favoriteCount,a.view_count AS viewCount,c.category_name AS categoryName FROM mall_content_article a JOIN mall_content_category c ON c.id=a.category_id ORDER BY a.id DESC");for(Map<String,Object> row:rows)row.put("productIds",jdbc.queryForList("SELECT product_id FROM mall_content_article_product WHERE article_id=? ORDER BY sort_no",row.get("id")).stream().map(x->x.get("product_id")).toArray());return rows;}

    @Transactional
    public Long saveCategory(Long id,Map<String,Object> b)
    {
        String code=required(b,"categoryCode",2,32),name=required(b,"categoryName",2,64),status=text(b.get("status"));
        Long parentId=longValue(b.get("parentId"));
        String description=plain(text(b.get("description")));
        if(status.isEmpty())status="0";
        if(!"0".equals(status)&&!"1".equals(status))throw new IllegalArgumentException("内容分类状态无效");
        validateCategoryParent(id,parentId);
        if(id==null)
        {
            jdbc.update("INSERT INTO mall_content_category(parent_id,category_code,category_name,description,status,sort_no) VALUES(?,?,?,?,?,?)",parentId,code,name,description,status,integer(b.get("sortNo"),0));
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        }
        if(jdbc.update("UPDATE mall_content_category SET parent_id=?,category_code=?,category_name=?,description=?,status=?,sort_no=? WHERE id=?",parentId,code,name,description,status,integer(b.get("sortNo"),0),id)!=1)
            throw new IllegalArgumentException("内容分类不存在");
        return id;
    }

    @Transactional
    public Long saveArticle(Long id,Map<String,Object> b)
    {
        Long categoryId=longValue(b.get("categoryId")); if(categoryId==null)throw new IllegalArgumentException("请选择内容分类");
        String slug=required(b,"slug",3,80); if(!slug.matches("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$"))throw new IllegalArgumentException("文章标识格式不正确");
        String title=required(b,"title",2,128),summary=plain(text(b.get("summary"))),body=sanitizeHtml(required(b,"bodyHtml",1,200000));
        String status=text(b.get("status")); if(status.isEmpty())status="1";
        if(!"0".equals(status)&&!"1".equals(status))throw new IllegalArgumentException("文章发布状态无效");
        int categoryCount="0".equals(status)
                ? jdbc.queryForObject("SELECT COUNT(*) FROM mall_content_category WHERE id=? AND status='0'",Integer.class,categoryId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM mall_content_category WHERE id=?",Integer.class,categoryId);
        if(categoryCount!=1)throw new IllegalArgumentException("内容分类不存在或已停用，不能发布文章");
        Object published="0".equals(status)?(text(b.get("publishedAt")).isEmpty()?jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class):b.get("publishedAt")):null;
        if(id==null){jdbc.update("INSERT INTO mall_content_article(category_id,slug,title,summary,cover_image_key,body_html,status,published_at,sort_no) VALUES(?,?,?,?,?,?,?,?,?)",categoryId,slug,title,summary,text(b.get("coverImageKey")),body,status,published,integer(b.get("sortNo"),0));id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}else jdbc.update("UPDATE mall_content_article SET category_id=?,slug=?,title=?,summary=?,cover_image_key=?,body_html=?,status=?,published_at=CASE WHEN ?='0' THEN COALESCE(?,NOW()) ELSE published_at END,sort_no=? WHERE id=?",categoryId,slug,title,summary,text(b.get("coverImageKey")),body,status,status,published,integer(b.get("sortNo"),0),id);
        jdbc.update("DELETE FROM mall_content_article_product WHERE article_id=?",id); Object products=b.get("productIds"); int sort=0; if(products instanceof Iterable){for(Object p:(Iterable<?>)products){Long productId=longValue(p); if(productId!=null){requireProduct(productId);jdbc.update("INSERT IGNORE INTO mall_content_article_product(article_id,product_id,sort_no) VALUES(?,?,?)",id,productId,sort++);}}}else for(String p:text(products).split("[,，\\s]+")){Long productId=longValue(p);if(productId!=null){requireProduct(productId);jdbc.update("INSERT IGNORE INTO mall_content_article_product(article_id,product_id,sort_no) VALUES(?,?,?)",id,productId,sort++);}}
        audit("文章",id,"",status,"保存文章"); return id;
    }

    public List<Map<String,Object>> adminPosts(){List<Map<String,Object>> r=jdbc.queryForList(postSelect()+" ORDER BY p.id DESC");attachPosts(r,null);return r;}
    public List<Map<String,Object>> adminComments(){return jdbc.queryForList("SELECT m.id,m.post_id AS postId,m.customer_id AS customerId,c.nickname,m.content,m.status,m.create_time AS createTime FROM mall_community_comment m JOIN mall_customer c ON c.id=m.customer_id ORDER BY m.id DESC");}
    public List<Map<String,Object>> adminLikes(){return jdbc.queryForList("SELECT l.id,l.post_id AS postId,l.customer_id AS customerId,c.nickname,l.create_time AS createTime FROM mall_community_like l JOIN mall_customer c ON c.id=l.customer_id ORDER BY l.id DESC");}
    public List<Map<String,Object>> adminReports(){return jdbc.queryForList("SELECT * FROM mall_community_report ORDER BY id DESC");}
    public List<Map<String,Object>> adminContentAudits(){return jdbc.queryForList("SELECT * FROM mall_content_audit_log ORDER BY id DESC");}

    @Transactional public void report(Long customerId,Map<String,Object>b){Long postId=longValue(b.get("postId")),commentId=longValue(b.get("commentId"));if(postId==null&&commentId==null)throw new IllegalArgumentException("请选择举报对象");String reason=plain(required(b,"reason",3,500));if(postId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM mall_community_post WHERE id=? AND status='正常'",Integer.class,postId)==0)throw new IllegalArgumentException("举报动态不存在");if(commentId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM mall_community_comment WHERE id=? AND status='正常'",Integer.class,commentId)==0)throw new IllegalArgumentException("举报评论不存在");jdbc.update("INSERT INTO mall_community_report(post_id,comment_id,reporter_customer_id,reason,status) VALUES(?,?,?,?,'待处理')",postId,commentId,customerId,reason);}
    @Transactional public void updateReport(Long id,Map<String,Object>b){String status=text(b.get("status"));if(!"已处理".equals(status)&&!"已驳回".equals(status))throw new IllegalArgumentException("非法举报状态");jdbc.update("UPDATE mall_community_report SET status=?,admin_remark=? WHERE id=? AND status='待处理'",status,plain(text(b.get("adminRemark"))),id);audit("举报",id,"待处理",status,text(b.get("adminRemark")));}

    @Transactional public void updatePost(Long id,Map<String,Object>b){String status=text(b.get("status"));if(!validContentStatus(status))throw new IllegalArgumentException("非法动态状态");Map<String,Object>old=one("SELECT status FROM mall_community_post WHERE id=?",id);jdbc.update("UPDATE mall_community_post SET status=? WHERE id=?",status,id);audit("动态",id,text(old.get("status")),status,text(b.get("remark")));}
    @Transactional public void updateComment(Long id,Map<String,Object>b){String status=text(b.get("status"));if(!validContentStatus(status))throw new IllegalArgumentException("非法评论状态");Map<String,Object>old=one("SELECT post_id AS postId,status FROM mall_community_comment WHERE id=?",id);jdbc.update("UPDATE mall_community_comment SET status=? WHERE id=?",status,id);jdbc.update("UPDATE mall_community_post SET comment_count=(SELECT COUNT(*) FROM mall_community_comment WHERE post_id=? AND status='正常') WHERE id=?",old.get("postId"),old.get("postId"));audit("评论",id,text(old.get("status")),status,text(b.get("remark")));}

    private void audit(String type,Long id,String from,String to,String remark){jdbc.update("INSERT INTO mall_content_audit_log(target_type,target_id,from_status,to_status,operator_id,remark) VALUES(?,?,?,?,?,?)",type,id,from,to,SecurityUtils.getUserId(),plain(remark));}
    private void notifyCustomer(Long customerId,String title,String content,String eventCode,String sourceKey){if(customerId==null||customerId<=0)return;jdbc.update("INSERT IGNORE INTO mall_notification(customer_id,category,title,content,target_route,target_query,source_type,source_key,event_code) VALUES(?,'合伙人',?,?, 'partnerApplication','', 'PARTNER',?,?)",customerId,title,content,sourceKey,eventCode);}
    private boolean validContentStatus(String status){return "正常".equals(status)||"待审核".equals(status)||"隐藏".equals(status)||"已删除".equals(status);}
    private void requirePartner(Long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_distributor WHERE customer_id=? AND partner_status='审核通过' AND status='0'",Integer.class,id)==0)throw new IllegalArgumentException("合伙人审核通过后才能访问工作台");}
    private void requireVisiblePost(Long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_community_post WHERE id=? AND status='正常' AND visibility='公开'",Integer.class,id)==0)throw new IllegalArgumentException("动态不存在或不可见");}
    private String postSelect(){return "SELECT p.id,p.customer_id AS customerId,c.nickname,c.avatar_url AS avatarUrl,p.content,p.status,p.visibility,p.like_count AS likeCount,p.comment_count AS commentCount,p.create_time AS createTime FROM mall_community_post p JOIN mall_customer c ON c.id=p.customer_id";}
    private void attachPosts(List<Map<String,Object>> rows,Long viewer){for(Map<String,Object> r:rows){r.put("images",jdbc.queryForList("SELECT image_url AS imageUrl FROM mall_community_post_image WHERE post_id=? ORDER BY sort_no,id",r.get("id")));r.put("liked",viewer!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM mall_community_like WHERE post_id=? AND customer_id=?",Integer.class,r.get("id"),viewer)>0);r.put("mine",viewer!=null&&text(r.get("customerId")).equals(text(viewer)));}}
    private void validateUploadUrl(String value){if(!value.matches("^/profile/upload/[A-Za-z0-9_./-]{1,480}$")||value.contains(".."))throw new IllegalArgumentException("图片地址必须来自本项目安全上传目录");}
    private void requireProduct(Long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE id=?",Integer.class,id)!=1)throw new IllegalArgumentException("关联商品不存在");}
    private void validateCategoryParent(Long id,Long parentId){if(parentId==null)return;if(id!=null&&id.equals(parentId))throw new IllegalArgumentException("分类不能把自己设为父级");Long cursor=parentId;for(int depth=0;depth<64;depth++){Map<String,Object> parent=first("SELECT id,parent_id AS parentId FROM mall_content_category WHERE id=?",cursor);if(parent==null)throw new IllegalArgumentException("父分类不存在");Long next=longValue(parent.get("parentId"));if(next==null)return;if(id!=null&&id.equals(next))throw new IllegalArgumentException("分类父级不能形成循环");cursor=next;}throw new IllegalArgumentException("内容分类层级过深或存在循环");}
    private String sanitizeHtml(String value){return MallHtmlSanitizer.sanitizeArticle(value);}
    private String plain(String v){return v==null?"":v.replaceAll("<[^>]+>","").replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]","").trim();}
    private String required(Map<String,Object>b,String k,int min,int max){String v=text(b.get(k)).trim();if(v.length()<min||v.length()>max)throw new IllegalArgumentException(fieldLabel(k)+"长度需为"+min+"-"+max+"个字符");return v;}
    static String fieldLabel(String key) {
        switch (key) {
            case "realName": return "真实姓名";
            case "idNo": return "身份证号码";
            case "region": return "所在地区";
            case "address": return "详细地址";
            case "phone": return "联系电话";
            case "reason": return "申请理由";
            default: return key;
        }
    }
    private String maskPhone(String v){return v.matches("^\\d{11}$")?v.substring(0,3)+"****"+v.substring(7):v;}
    private Map<String,Object> first(String s,Object...a){List<Map<String,Object>>l=jdbc.queryForList(s,a);return l.isEmpty()?null:l.get(0);}
    private Map<String,Object> one(String s,Object...a){Map<String,Object>r=first(s,a);if(r==null)throw new IllegalArgumentException("数据不存在");return r;}
    private String text(Object v){return v==null?"":String.valueOf(v);}
    private boolean bool(Object v){return Boolean.TRUE.equals(v)||"true".equalsIgnoreCase(text(v))||"1".equals(text(v));}
    private int integer(Object v,int f){try{return v==null?f:new BigDecimal(text(v)).intValue();}catch(Exception e){return f;}}
    private Long longValue(Object v){try{return v==null||text(v).trim().isEmpty()?null:Long.valueOf(text(v));}catch(Exception e){return null;}}
}
