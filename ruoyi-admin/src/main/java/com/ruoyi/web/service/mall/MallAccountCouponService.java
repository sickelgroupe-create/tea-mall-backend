package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.utils.SecurityUtils;

/** Real-data domain service for design pages 41-48. */
@Service
public class MallAccountCouponService
{
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private static final Pattern REQUEST=Pattern.compile("^[A-Za-z0-9_-]{16,80}$");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    public Map<String,Object> commissionCenter(Long customerId)
    {
        ensureDistributor(customerId);
        Map<String,Object> result=one("SELECT commission_balance AS available,pending_commission AS pending,frozen_commission AS frozen,"
                +"withdrawn_commission AS withdrawn,(commission_balance+pending_commission+frozen_commission+withdrawn_commission) AS total "
                +"FROM mall_distributor WHERE customer_id=?",customerId);
        result.putAll(one("SELECT COUNT(*) AS customerCount,SUM(CASE WHEN create_time>=CURDATE() THEN 1 ELSE 0 END) AS newCustomerCount "
                +"FROM mall_distributor WHERE parent_customer_id=?",customerId));
        result.put("rules", java.util.Collections.emptyList()); // Legacy percentage rules no longer apply.
        return result;
    }

    public Map<String,Object> commissionDetails(Long customerId)
    {
        Map<String,Object> result=commissionCenter(customerId);
        result.put("commissions",jdbc.queryForList("SELECT mc.id,mc.commission_no AS commissionNo,o.order_no AS orderNo,mc.level_no AS levelNo,"
                +"mc.base_amount AS baseAmount,mc.rate,mc.amount,mc.status,mc.settle_time AS settleTime,mc.reversed_time AS reversedTime,"
                +"c.nickname AS sourceName,CONCAT(LEFT(c.phone,3),'****',RIGHT(c.phone,4)) AS sourcePhone,mc.create_time AS createTime "
                +"FROM mall_commission mc JOIN mall_order o ON o.id=mc.order_id JOIN mall_customer c ON c.id=mc.source_customer_id "
                +"WHERE mc.beneficiary_id=? ORDER BY mc.id DESC LIMIT 100",customerId));
        result.put("ledger",jdbc.queryForList("SELECT id,ledger_no AS ledgerNo,business_type AS businessType,amount,available_before AS availableBefore,"
                +"available_after AS availableAfter,pending_before AS pendingBefore,pending_after AS pendingAfter,frozen_before AS frozenBefore,"
                +"frozen_after AS frozenAfter,business_no AS businessNo,remark,create_time AS createTime FROM mall_commission_ledger "
                +"WHERE customer_id=? ORDER BY id DESC LIMIT 100",customerId));
        result.put("withdrawals",withdrawals(customerId));
        result.put("config",withdrawalConfig());
        return result;
    }

    public Map<String,Object> withdrawalConfig()
    {
        return one("SELECT id,config_name AS configName,min_amount AS minAmount,max_amount AS maxAmount,fee_rate AS feeRate,"
                +"fee_fixed AS feeFixed,arrival_days AS arrivalDays,account_types AS accountTypes FROM mall_withdrawal_config "
                +"WHERE status='0' ORDER BY id DESC LIMIT 1");
    }

    public List<Map<String,Object>> withdrawals(Long customerId)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,withdrawal_no AS withdrawalNo,amount,fee_amount AS feeAmount,arrival_amount AS arrivalAmount,"
                +"account_type AS accountType,account_no AS accountNo,status,admin_remark AS adminRemark,create_time AS createTime,review_time AS reviewTime,"
                +"processed_time AS processedTime FROM mall_withdrawal WHERE customer_id=? ORDER BY id DESC LIMIT 100",customerId);
        for(Map<String,Object> row:rows) row.put("accountNo",mask(text(row.get("accountNo"))));
        return rows;
    }

    public Map<String,Object> requestWithdrawal(Long customerId,Map<String,Object> body)
    {
        String requestNo=required(body,"requestNo",16,80);
        if(!REQUEST.matcher(requestNo).matches()) throw new IllegalArgumentException("提现请求号格式无效");
        String lockName="mall-wd-"+UUID.nameUUIDFromBytes((customerId+":"+requestNo).getBytes(StandardCharsets.UTF_8));
        try(Connection connection=dataSource.getConnection())
        {
            if(!namedLock(connection,"SELECT GET_LOCK(?,10)",lockName))throw new IllegalArgumentException("提现请求处理中，请稍后重试");
            try
            {
                TransactionTemplate tx=new TransactionTemplate(transactionManager);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                Map<String,Object> result=tx.execute(status->requestWithdrawalInTransaction(customerId,body,requestNo));
                if(result==null)throw new IllegalStateException("提现事务未返回结果");
                return result;
            }
            finally{namedLock(connection,"SELECT RELEASE_LOCK(?)",lockName);}
        }
        catch(SQLException e){throw new IllegalStateException("提现幂等锁不可用",e);}
    }

    private Map<String,Object> requestWithdrawalInTransaction(Long customerId,Map<String,Object> body,String requestNo)
    {
        Map<String,Object> existing=first("SELECT withdrawal_no AS withdrawalNo,amount,status FROM mall_withdrawal WHERE request_no=? AND customer_id=? FOR UPDATE",requestNo,customerId);
        if(existing!=null)return existing;
        Map<String,Object> account=one("SELECT commission_balance,pending_commission,frozen_commission FROM mall_distributor WHERE customer_id=? FOR UPDATE",customerId);
        Map<String,Object> cfg=withdrawalConfig();
        BigDecimal amount=money(body.get("amount"));
        BigDecimal min=money(cfg.get("minAmount")),max=money(cfg.get("maxAmount"));
        if(amount.compareTo(min)<0||amount.compareTo(max)>0)throw new IllegalArgumentException("提现金额需在"+min+"至"+max+"元之间");
        String type=required(body,"accountType",2,32),accountNo=required(body,"accountNo",4,128);
        if(!(','+text(cfg.get("accountTypes"))+',').contains(','+type+','))throw new IllegalArgumentException("当前到账方式未启用");
        BigDecimal fee=amount.multiply(money(cfg.get("feeRate"))).add(money(cfg.get("feeFixed"))).setScale(2,RoundingMode.HALF_UP);
        BigDecimal arrival=amount.subtract(fee);
        if(arrival.compareTo(BigDecimal.ZERO)<=0)throw new IllegalArgumentException("手续费不能大于或等于提现金额");
        BigDecimal available=money(account.get("commission_balance"));
        if(available.compareTo(amount)<0)throw new IllegalArgumentException("可提现佣金不足");
        int changed=jdbc.update("UPDATE mall_distributor SET commission_balance=commission_balance-?,frozen_commission=frozen_commission+? "
                +"WHERE customer_id=? AND commission_balance>=?",amount,amount,customerId,amount);
        if(changed!=1)throw new IllegalArgumentException("可提现佣金不足或余额已变化");
        String no=serial("WD");
        jdbc.update("INSERT INTO mall_withdrawal(withdrawal_no,request_no,customer_id,amount,fee_amount,arrival_amount,account_type,account_no,status) "
                +"VALUES(?,?,?,?,?,?,?,?,'待审核')",no,requestNo,customerId,amount,fee,arrival,type,accountNo);
        Long id=jdbc.queryForObject("SELECT id FROM mall_withdrawal WHERE withdrawal_no=?",Long.class,no);
        jdbc.update("INSERT INTO mall_withdrawal_audit(withdrawal_id,from_status,to_status,operator_type,operator_id,opinion) VALUES(?,'未申请','待审核','用户',?,'提交提现申请')",id,customerId);
        ledger(customerId,null,"提现冻结",amount.negate(),no,"申请提现，资金转入冻结",account);
        return one("SELECT withdrawal_no AS withdrawalNo,amount,fee_amount AS feeAmount,arrival_amount AS arrivalAmount,status FROM mall_withdrawal WHERE id=?",id);
    }

    private boolean namedLock(Connection connection,String sql,String lockName)throws SQLException
    {
        try(PreparedStatement statement=connection.prepareStatement(sql))
        {
            statement.setString(1,lockName);
            try(ResultSet result=statement.executeQuery()){return result.next()&&result.getInt(1)==1;}
        }
    }

    @Transactional
    public void cancelWithdrawal(Long customerId,String no)
    {
        Map<String,Object> row=first("SELECT * FROM mall_withdrawal WHERE withdrawal_no=? AND customer_id=? FOR UPDATE",no,customerId);
        if(row==null)throw new IllegalArgumentException("提现申请不存在或无权操作");
        if("已取消".equals(text(row.get("status"))))return;
        if(!"待审核".equals(text(row.get("status"))))throw new IllegalArgumentException("只有待审核申请可以取消");
        Map<String,Object> account=one("SELECT commission_balance,pending_commission,frozen_commission FROM mall_distributor WHERE customer_id=? FOR UPDATE",customerId);
        BigDecimal amount=money(row.get("amount"));
        int n=jdbc.update("UPDATE mall_distributor SET frozen_commission=frozen_commission-?,commission_balance=commission_balance+? WHERE customer_id=? AND frozen_commission>=?",amount,amount,customerId,amount);
        if(n!=1)throw new IllegalStateException("冻结佣金余额不一致");
        jdbc.update("UPDATE mall_withdrawal SET status='已取消',processed_time=NOW() WHERE id=?",row.get("id"));
        jdbc.update("INSERT INTO mall_withdrawal_audit(withdrawal_id,from_status,to_status,operator_type,operator_id,opinion) VALUES(?,'待审核','已取消','用户',?,'用户取消')",row.get("id"),customerId);
        ledger(customerId,null,"提现取消返还",amount,no,"取消提现，冻结资金退回",account);
    }

    public List<Map<String,Object>> coupons(Long customerId,String status)
    {
        jdbc.update("UPDATE mall_customer_coupon cc JOIN mall_coupon_template t ON t.id=cc.template_id SET cc.status='已过期',cc.invalid_time=NOW() "
                +"WHERE cc.customer_id=? AND cc.status='未使用' AND t.valid_to<NOW()",customerId);
        String filter="全部".equals(status)||status==null?"":" AND cc.status=?";
        Object[] args=filter.isEmpty()?new Object[]{customerId}:new Object[]{customerId,status};
        return jdbc.queryForList("SELECT cc.id,cc.coupon_no AS couponNo,cc.status,cc.received_time AS receivedTime,t.id AS templateId,t.name,t.coupon_type AS couponType,"
                +"t.discount_amount AS discountAmount,t.min_order_amount AS minOrderAmount,t.valid_from AS validFrom,t.valid_to AS validTo,t.scope_type AS scopeType,"
                +"t.scope_value AS scopeValue,t.description FROM mall_customer_coupon cc JOIN mall_coupon_template t ON t.id=cc.template_id "
                +"WHERE cc.customer_id=?"+filter+" ORDER BY cc.id DESC",args);
    }

    public List<Map<String,Object>> availableCoupons(Long customerId)
    {
        return jdbc.queryForList("SELECT t.id,t.name,t.coupon_type AS couponType,t.discount_amount AS discountAmount,t.min_order_amount AS minOrderAmount,"
                +"t.valid_to AS validTo,t.scope_type AS scopeType,t.description FROM mall_coupon_template t WHERE t.status='0' AND t.valid_from<=NOW() AND t.valid_to>=NOW() "
                +"AND (t.total_qty=0 OR t.issued_qty<t.total_qty) AND (SELECT COUNT(*) FROM mall_customer_coupon cc WHERE cc.template_id=t.id AND cc.customer_id=?)<t.per_user_limit ORDER BY t.id",customerId);
    }

    @Transactional
    public Map<String,Object> claimCoupon(Long customerId,Long templateId)
    {
        Map<String,Object> t=first("SELECT * FROM mall_coupon_template WHERE id=? AND status='0' AND valid_from<=NOW() AND valid_to>=NOW() FOR UPDATE",templateId);
        if(t==null)throw new IllegalArgumentException("优惠券不存在、未生效或已停用");
        int received=jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_coupon WHERE template_id=? AND customer_id=?",Integer.class,templateId,customerId);
        if(received>=number(t.get("per_user_limit")))throw new IllegalArgumentException("已达到每人领取上限");
        if(number(t.get("total_qty"))>0&&number(t.get("issued_qty"))>=number(t.get("total_qty")))throw new IllegalArgumentException("优惠券已领完");
        String no=serial("CP");
        jdbc.update("INSERT INTO mall_customer_coupon(coupon_no,template_id,customer_id,status) VALUES(?,?,?,'未使用')",no,templateId,customerId);
        Long id=jdbc.queryForObject("SELECT id FROM mall_customer_coupon WHERE coupon_no=?",Long.class,no);
        jdbc.update("UPDATE mall_coupon_template SET issued_qty=issued_qty+1 WHERE id=?",templateId);
        jdbc.update("INSERT INTO mall_coupon_log(customer_coupon_id,customer_id,action_type,business_no,remark) VALUES(?,?,'发放',?,'用户领取')",id,customerId,no);
        return one("SELECT id,coupon_no AS couponNo,status FROM mall_customer_coupon WHERE id=?",id);
    }

    public Map<String,Object> profileDashboard(Long customerId)
    {
        Map<String,Object> result=one("SELECT c.id,c.nickname,c.phone,c.avatar_url AS avatarUrl,c.points,c.status,"
                +"(SELECT COUNT(*) FROM mall_favorite f WHERE f.customer_id=c.id) AS favoriteCount,"
                +"(SELECT COUNT(*) FROM mall_customer_coupon cc JOIN mall_coupon_template t ON t.id=cc.template_id WHERE cc.customer_id=c.id AND cc.status='未使用' AND t.valid_to>=NOW()) AS couponCount,"
                +"(SELECT COUNT(*) FROM mall_distributor d WHERE d.parent_customer_id=c.id) AS customerCount "
                +"FROM mall_customer c WHERE c.id=?",customerId);
        result.putAll(one("SELECT SUM(status='待付款') AS unpaid,SUM(status='待发货') AS unshipped,SUM(status='待收货') AS unreceived,"
                +"SUM(status IN('售后中','已退款')) AS aftersale FROM mall_order WHERE customer_id=? AND customer_deleted=0",customerId));
        Map<String,Object> dist=first("SELECT commission_balance AS commissionAvailable,pending_commission AS commissionPending FROM mall_distributor WHERE customer_id=?",customerId);
        if(dist!=null)result.putAll(dist);
        result.put("phone",mask(text(result.get("phone"))));
        return result;
    }

    public Map<String,Object> preferences(Long customerId)
    {
        jdbc.update("INSERT IGNORE INTO mall_customer_preference(customer_id) VALUES(?)",customerId);
        return one("SELECT order_notice AS orderNotice,activity_notice AS activityNotice,personalized,history_enabled AS historyEnabled,update_time AS updateTime FROM mall_customer_preference WHERE customer_id=?",customerId);
    }

    public Map<String,Object> savePreferences(Long customerId,Map<String,Object> body)
    {
        preferences(customerId);
        jdbc.update("UPDATE mall_customer_preference SET order_notice=?,activity_notice=?,personalized=?,history_enabled=? WHERE customer_id=?",
                bool(body.get("orderNotice")),bool(body.get("activityNotice")),bool(body.get("personalized")),bool(body.get("historyEnabled")),customerId);
        return preferences(customerId);
    }

    public List<Map<String,Object>> documents()
    {
        return jdbc.queryForList("SELECT id,document_key AS documentKey,title,content,version,sort_no AS sortNo,publish_time AS publishTime FROM mall_managed_document WHERE status='0' ORDER BY sort_no,id");
    }

    @Transactional
    public void recordBrowse(Long customerId,Long productId)
    {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE id=?",Integer.class,productId)==0)throw new IllegalArgumentException("商品不存在");
        Map<String,Object> pref=preferences(customerId);
        if(!bool(pref.get("historyEnabled")))return;
        jdbc.update("INSERT INTO mall_browse_history(customer_id,product_id,source) VALUES(?,?,'SERVER') ON DUPLICATE KEY UPDATE last_view_time=NOW(),view_count=view_count+1",customerId,productId);
    }

    @Transactional
    public void mergeBrowse(Long customerId,List<?> productIds)
    {
        if(productIds==null)return;
        int count=0; for(Object id:productIds){if(count++>=50)break; Long p=longValue(id); if(p!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE id=?",Integer.class,p)>0)
            jdbc.update("INSERT INTO mall_browse_history(customer_id,product_id,source) VALUES(?,?,'LOCAL_MERGE') ON DUPLICATE KEY UPDATE last_view_time=GREATEST(last_view_time,NOW()),view_count=view_count+1",customerId,p);}
    }

    public Map<String,Object> browseHistory(Long customerId)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT h.id,h.product_id AS productId,h.last_view_time AS lastViewTime,h.view_count AS viewCount,"
                +"p.name,p.short_name AS shortName,p.price,p.image_key AS imageKey,p.status,p.stock,CASE WHEN DATE(h.last_view_time)=CURDATE() THEN '今天' "
                +"WHEN DATE(h.last_view_time)=DATE_SUB(CURDATE(),INTERVAL 1 DAY) THEN '昨天' ELSE '更早' END AS dateGroup FROM mall_browse_history h "
                +"JOIN mall_product p ON p.id=h.product_id WHERE h.customer_id=? ORDER BY h.last_view_time DESC",customerId);
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("timezone",SHANGHAI.getId());result.put("today",LocalDate.now(SHANGHAI).toString());return result;
    }

    public void deleteBrowse(Long customerId,Long productId){int n=jdbc.update("DELETE FROM mall_browse_history WHERE customer_id=? AND product_id=?",customerId,productId);if(n==0)return;}
    public void clearBrowse(Long customerId){jdbc.update("DELETE FROM mall_browse_history WHERE customer_id=?",customerId);}

    public List<Map<String,Object>> adminCommissionRules(){return jdbc.queryForList("SELECT id,rule_name AS ruleName,level_no AS levelNo,rate,min_order_amount AS minOrderAmount,effective_from AS effectiveFrom,effective_to AS effectiveTo,status,update_time AS updateTime FROM mall_commission_rule ORDER BY level_no,id");}
    public List<Map<String,Object>> adminCommissionLedger(){return jdbc.queryForList("SELECT l.id,l.ledger_no AS ledgerNo,c.nickname,l.business_type AS businessType,l.amount,l.business_no AS businessNo,l.remark,l.create_time AS createTime FROM mall_commission_ledger l JOIN mall_customer c ON c.id=l.customer_id ORDER BY l.id DESC LIMIT 500");}
    public List<Map<String,Object>> adminWithdrawals(){return jdbc.queryForList("SELECT w.id,w.withdrawal_no AS withdrawalNo,c.nickname,CONCAT(LEFT(c.phone,3),'****',RIGHT(c.phone,4)) AS phone,w.amount,w.fee_amount AS feeAmount,w.arrival_amount AS arrivalAmount,w.account_type AS accountType,w.account_no AS accountNo,w.status,w.admin_remark AS adminRemark,w.create_time AS createTime,w.review_time AS reviewTime,w.processed_time AS processedTime FROM mall_withdrawal w JOIN mall_customer c ON c.id=w.customer_id ORDER BY w.id DESC");}
    public List<Map<String,Object>> adminWithdrawalAudits(){return jdbc.queryForList("SELECT a.id,w.withdrawal_no AS withdrawalNo,a.from_status AS fromStatus,a.to_status AS toStatus,a.operator_type AS operatorType,a.operator_id AS operatorId,a.opinion,a.create_time AS createTime FROM mall_withdrawal_audit a JOIN mall_withdrawal w ON w.id=a.withdrawal_id ORDER BY a.id DESC");}
    public List<Map<String,Object>> adminCouponTemplates(){return jdbc.queryForList("SELECT id,template_no AS templateNo,name,coupon_type AS couponType,discount_amount AS discountAmount,min_order_amount AS minOrderAmount,valid_from AS validFrom,valid_to AS validTo,scope_type AS scopeType,scope_value AS scopeValue,total_qty AS totalQty,issued_qty AS issuedQty,per_user_limit AS perUserLimit,status,description FROM mall_coupon_template ORDER BY id DESC");}
    public List<Map<String,Object>> adminCustomerCoupons(){return jdbc.queryForList("SELECT cc.id,cc.coupon_no AS couponNo,c.nickname,t.name,cc.status,cc.received_time AS receivedTime,cc.locked_time AS lockedTime,cc.used_time AS usedTime,o.order_no AS orderNo FROM mall_customer_coupon cc JOIN mall_customer c ON c.id=cc.customer_id JOIN mall_coupon_template t ON t.id=cc.template_id LEFT JOIN mall_order o ON o.id=COALESCE(cc.used_order_id,cc.locked_order_id) ORDER BY cc.id DESC");}
    public List<Map<String,Object>> adminCouponLogs(){return jdbc.queryForList("SELECT l.id,l.action_type AS actionType,l.business_no AS businessNo,l.amount,l.remark,l.create_time AS createTime,cc.coupon_no AS couponNo,c.nickname FROM mall_coupon_log l JOIN mall_customer_coupon cc ON cc.id=l.customer_coupon_id JOIN mall_customer c ON c.id=l.customer_id ORDER BY l.id DESC");}
    public List<Map<String,Object>> adminDocuments(){return jdbc.queryForList("SELECT id,document_key AS documentKey,title,content,version,sort_no AS sortNo,status,publish_time AS publishTime,update_time AS updateTime FROM mall_managed_document ORDER BY sort_no,id");}
    public List<Map<String,Object>> adminBrowse(){return jdbc.queryForList("SELECT h.id,c.nickname,CONCAT(LEFT(c.phone,3),'****',RIGHT(c.phone,4)) AS phone,p.name AS productName,h.view_count AS viewCount,h.last_view_time AS lastViewTime FROM mall_browse_history h JOIN mall_customer c ON c.id=h.customer_id JOIN mall_product p ON p.id=h.product_id ORDER BY h.last_view_time DESC LIMIT 500");}

    public void saveRule(Long id,Map<String,Object>b){throw new IllegalArgumentException("旧比例及多级佣金规则已停用，请到商品管理设置直属固定提成；历史记录仅供查询");}
    public void saveWithdrawalConfig(Map<String,Object>b){BigDecimal min=money(b.get("minAmount")),max=money(b.get("maxAmount"));if(min.compareTo(BigDecimal.ZERO)<0||max.compareTo(min)<0)throw new IllegalArgumentException("提现上下限无效");Map<String,Object> cfg=withdrawalConfig();jdbc.update("UPDATE mall_withdrawal_config SET min_amount=?,max_amount=?,fee_rate=?,fee_fixed=?,arrival_days=?,account_types=?,status=? WHERE id=?",min,max,money(b.get("feeRate")),money(b.get("feeFixed")),required(b,"arrivalDays",2,32),required(b,"accountTypes",2,128),text(b.get("status")),cfg.get("id"));}

    @Transactional
    public void reviewWithdrawal(Long id,Map<String,Object>b)
    {
        Map<String,Object>w=one("SELECT * FROM mall_withdrawal WHERE id=? FOR UPDATE",id);String old=text(w.get("status")),next=required(b,"status",2,24);
        boolean allowed=("待审核".equals(old)&&("审核通过".equals(next)||"已驳回".equals(next)))||("审核通过".equals(old)&&("处理中".equals(next)||"已驳回".equals(next)))||("处理中".equals(old)&&("已完成".equals(next)||"已驳回".equals(next)));
        if(!allowed)throw new IllegalArgumentException("提现状态必须按待审核→审核通过→处理中→已完成流转，或在完成前驳回");
        Long customerId=((Number)w.get("customer_id")).longValue();BigDecimal amount=money(w.get("amount"));Map<String,Object>a=one("SELECT commission_balance,pending_commission,frozen_commission,withdrawn_commission FROM mall_distributor WHERE customer_id=? FOR UPDATE",customerId);
        if(!"已驳回".equals(next) && money(a.get("commission_balance")).signum()<0)throw new IllegalArgumentException("佣金存在退款欠扣，禁止继续提现，请核对账务并驳回该申请");
        if("已驳回".equals(next)){int n=jdbc.update("UPDATE mall_distributor SET frozen_commission=frozen_commission-?,commission_balance=commission_balance+? WHERE customer_id=? AND frozen_commission>=?",amount,amount,customerId,amount);if(n!=1)throw new IllegalStateException("冻结余额不足，禁止重复返还");ledger(customerId,null,"提现驳回返还",amount,text(w.get("withdrawal_no")),"后台驳回提现",a);}
        if("已完成".equals(next)){int n=jdbc.update("UPDATE mall_distributor SET frozen_commission=frozen_commission-?,withdrawn_commission=withdrawn_commission+? WHERE customer_id=? AND frozen_commission>=?",amount,amount,customerId,amount);if(n!=1)throw new IllegalStateException("冻结余额不足，禁止重复完成");ledger(customerId,null,"线下提现完成",amount.negate(),text(w.get("withdrawal_no")),"测试或线下人工确认完成，非微信真实转账",a);}
        Long admin=SecurityUtils.getUserId();String opinion=required(b,"opinion",2,255);
        jdbc.update("UPDATE mall_withdrawal SET status=?,admin_remark=?,reviewer_id=?,review_time=NOW(),processed_time=CASE WHEN ? IN('已完成','已驳回') THEN NOW() ELSE processed_time END WHERE id=?",next,opinion,admin,next,id);
        jdbc.update("INSERT INTO mall_withdrawal_audit(withdrawal_id,from_status,to_status,operator_type,operator_id,opinion) VALUES(?,?,?,'管理员',?,?)",id,old,next,admin,opinion);
    }

    @Transactional
    public void saveCouponTemplate(Long id,Map<String,Object>b)
    {
        String couponType=required(b,"couponType",2,24);
        if(!"满减券".equals(couponType)&&!"固定金额券".equals(couponType))throw new IllegalArgumentException("优惠券类型无效");
        BigDecimal discount=money(b.get("discountAmount")),minimum=money(b.get("minOrderAmount"));
        if(discount.compareTo(BigDecimal.ZERO)<=0||minimum.compareTo(BigDecimal.ZERO)<0)throw new IllegalArgumentException("优惠金额和使用门槛无效");
        String scope=required(b,"scopeType",2,24);
        if(!"全场".equals(scope)&&!"指定商品".equals(scope)&&!"指定分类".equals(scope))throw new IllegalArgumentException("优惠券适用范围无效");
        String scopeValue=normalizeCouponScope(scope,text(b.get("scopeValue")));
        int total=number(b.get("totalQty")),perUser=number(b.get("perUserLimit"));
        if(total<0||perUser<1)throw new IllegalArgumentException("发放总量或每人限领数量无效");
        Map<String,Object> current=one("SELECT issued_qty AS issuedQty FROM mall_coupon_template WHERE id=? FOR UPDATE",id);
        if(total>0&&total<number(current.get("issuedQty")))throw new IllegalArgumentException("发放总量不能小于已发放数量");
        String status=text(b.get("status"));if(!"0".equals(status)&&!"1".equals(status))throw new IllegalArgumentException("优惠券状态无效");
        int changed=jdbc.update("UPDATE mall_coupon_template SET name=?,coupon_type=?,discount_amount=?,min_order_amount=?,valid_from=?,valid_to=?,scope_type=?,scope_value=?,total_qty=?,per_user_limit=?,status=?,description=? WHERE id=?",required(b,"name",2,80),couponType,discount,minimum,b.get("validFrom"),b.get("validTo"),scope,scopeValue,total,perUser,status,text(b.get("description")),id);
        if(changed!=1)throw new IllegalArgumentException("优惠券模板不存在");
    }
    public void saveDocument(Long id,Map<String,Object>b){jdbc.update("UPDATE mall_managed_document SET title=?,content=?,version=?,sort_no=?,status=?,publish_time=COALESCE(?,publish_time) WHERE id=?",required(b,"title",2,128),required(b,"content",2,20000),required(b,"version",1,32),number(b.get("sortNo")),text(b.get("status")),b.get("publishTime"),id);}

    private void ledger(Long customerId,Long commissionId,String type,BigDecimal amount,String businessNo,String remark,Map<String,Object> before)
    {
        Map<String,Object> after=one("SELECT commission_balance,pending_commission,frozen_commission FROM mall_distributor WHERE customer_id=?",customerId);
        jdbc.update("INSERT IGNORE INTO mall_commission_ledger(ledger_no,commission_id,customer_id,business_type,amount,available_before,available_after,pending_before,pending_after,frozen_before,frozen_after,business_no,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                serial("CL"),commissionId,customerId,type,amount,money(before.get("commission_balance")),money(after.get("commission_balance")),money(before.get("pending_commission")),money(after.get("pending_commission")),money(before.get("frozen_commission")),money(after.get("frozen_commission")),businessNo,remark);
    }
    private void ensureDistributor(Long c){jdbc.update("INSERT IGNORE INTO mall_distributor(customer_id,invite_code,status) VALUES(?,CONCAT('TEA',UPPER(CONV(?,10,36))),'0')",c,c);}
    private String normalizeCouponScope(String scope,String raw)
    {
        if("全场".equals(scope))return "";
        List<String> values=new ArrayList<>();
        for(String part:raw.split("[,，]"))
        {
            String value=part.trim();if(value.isEmpty()||values.contains(value))continue;
            if("指定商品".equals(scope))
            {
                if(!value.matches("^[1-9]\\d*$")||jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE id=?",Integer.class,Long.valueOf(value))!=1)
                    throw new IllegalArgumentException("优惠券包含不存在的商品");
            }
            else if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_category WHERE name=? AND parent_id IS NOT NULL",Integer.class,value)!=1)
                throw new IllegalArgumentException("优惠券包含不存在的末级分类");
            values.add(value);
        }
        if(values.isEmpty())throw new IllegalArgumentException("请选择优惠券适用的商品或分类");
        return String.join(",",values);
    }
    private Map<String,Object> one(String s,Object...a){List<Map<String,Object>>r=jdbc.queryForList(s,a);if(r.isEmpty())throw new IllegalArgumentException("数据不存在");return r.get(0);}
    private Map<String,Object> first(String s,Object...a){List<Map<String,Object>>r=jdbc.queryForList(s,a);return r.isEmpty()?null:r.get(0);}
    private String required(Map<String,Object>b,String k,int min,int max){String v=text(b.get(k)).trim();if(v.length()<min||v.length()>max)throw new IllegalArgumentException(k+"格式不正确");return v;}
    private String serial(String p){return p+System.currentTimeMillis()+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();}
    private String text(Object v){return v==null?"":String.valueOf(v);}
    private BigDecimal money(Object v){try{return new BigDecimal(text(v).isEmpty()?"0":text(v)).setScale(2,RoundingMode.HALF_UP);}catch(Exception e){throw new IllegalArgumentException("金额格式不正确");}}
    private int number(Object v){try{return Integer.parseInt(text(v));}catch(Exception e){return 0;}}
    private Long longValue(Object v){try{return Long.valueOf(text(v));}catch(Exception e){return null;}}
    private boolean bool(Object v){return Boolean.TRUE.equals(v)||"1".equals(text(v))||"true".equalsIgnoreCase(text(v));}
    private String mask(String v){if(v.length()<=4)return "****";if(v.length()<8)return v.substring(0,1)+"****"+v.substring(v.length()-1);return v.substring(0,3)+"****"+v.substring(v.length()-4);}
}
