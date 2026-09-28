package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Direct referral scores. Reuses mall_invite_record, mall_reward and mall_exchange. */
@Service
public class MallTeaFriendService {
    private final JdbcTemplate jdbc;
    public MallTeaFriendService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty()) throw new IllegalArgumentException("记录不存在或无权访问");
        return rows.get(0);
    }
    private static BigDecimal n(Object value) { return value==null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private static long id(Object value) { return n(value).longValueExact(); }
    private static String s(Object value) { return value==null ? "" : value.toString(); }
    public Map<String,Object> config() {
        Map<String,Object> result = one("SELECT points_per_friend AS pointsPerFriend,large_exchange_score AS largeExchangeScore,"
            +"daily_exchange_limit AS dailyExchangeLimit,minimum_purchase_ratio AS minimumPurchaseRatio,"
            +"monthly_reward_text AS monthlyRewardText,home_item_limit AS homeItemLimit,description,version_no AS versionNo,"
            +"effective_from AS effectiveFrom,invite_card_title AS inviteCardTitle,reward_prefix AS rewardPrefix,"
            +"score_unit_label AS scoreUnitLabel,invite_button_text AS inviteButtonText FROM mall_friend_config WHERE id=1");
        result.put("trialGiftCount", jdbc.queryForObject("SELECT COUNT(*) FROM mall_product WHERE is_trial_gift=1 AND status='0'", Integer.class));
        result.put("effectiveDescription", renderDescription(s(result.get("description")), n(result.get("pointsPerFriend")), s(result.get("scoreUnitLabel"))));
        return result;
    }
    static String renderDescription(String template, BigDecimal points, String unit) {
        String amount=points.stripTrailingZeros().toPlainString();
        String label=unit.isEmpty()?"邀请分":unit;
        // Preserve merchant prose while keeping explicit invitation-score amounts authoritative.
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("((?:每(?:位|个)?(?:有效)?好友|每人|每位)[^，。；;\\n0-9]{0,20})[0-9]+(?:\\.[0-9]+)?\\s*(?:邀请分|茶友分|"+java.util.regex.Pattern.quote(label)+")").matcher(template);
        StringBuffer rendered=new StringBuffer();
        while(matcher.find()) matcher.appendReplacement(rendered,java.util.regex.Matcher.quoteReplacement(matcher.group(1)+amount+label));
        matcher.appendTail(rendered);
        String text=rendered.toString();
        return text.replace("{pointsPerFriend}",amount).replace("{scoreUnitLabel}",label);
    }
    private void lockCustomer(Long customerId) {
        one("SELECT id FROM mall_customer WHERE id=? AND status='0' FOR UPDATE",customerId);
    }
    private BigDecimal balance(Long customerId) {
        return jdbc.queryForObject("SELECT invitation_score_net FROM mall_customer WHERE id=?",BigDecimal.class,customerId);
    }
    private void post(Long customerId,String key,BigDecimal amount,String description) {
        jdbc.update("UPDATE mall_customer SET invitation_score_net=invitation_score_net+? WHERE id=?",amount,customerId);
        jdbc.update("INSERT INTO mall_friend_score_log(customer_id,business_key,amount,balance_after,description) VALUES(?,?,?,?,?)",
            customerId,key,amount,balance(customerId),description);
        notify(customerId,key,"邀请分变动",description+"（"+amount.toPlainString()+" 邀请分）","ledger");
    }
    private void notify(Long customerId,String key,String title,String content,String tab) {
        jdbc.update("INSERT IGNORE INTO mall_notification(customer_id,category,title,content,target_route,target_query,source_type,source_key,event_code) VALUES(?,'活动',?,?,'/pages/invite-rewards/invite-rewards',?,'TEA_FRIEND',?,'STATE')",customerId,title,content,"tab="+tab,key);
    }
    /** Called in the same transaction as simulated payment/refund; customer lock serializes score changes. */
    @Transactional
    public void reconcileBuyer(Long buyerId) {
        List<Long> owners=jdbc.queryForList("SELECT r.inviter_customer_id FROM mall_invite_record r JOIN mall_customer c ON c.id=r.inviter_customer_id AND c.status='0' WHERE r.invitee_customer_id=?",Long.class,buyerId);
        for(Long owner:owners) reconcile(owner);
    }
    @Transactional
    public void reconcile(Long customerId) {
        lockCustomer(customerId);
        Map<String,Object> rule=config();
        List<Map<String,Object>> records=jdbc.queryForList("SELECT * FROM mall_invite_record WHERE inviter_customer_id=? ORDER BY id FOR UPDATE",customerId);
        for(Map<String,Object> record:records) {
            // Snapshot flags survive later product edits. Refunding every trial item revokes qualification.
            List<Long> eligible=jdbc.queryForList("SELECT o.id FROM mall_order o JOIN mall_order_item i ON i.order_id=o.id "
                +"WHERE o.customer_id=? AND o.payment_status='已支付' AND o.status NOT IN ('已取消','已关闭','已退款','退款完成') "
                +"AND o.paid_time>=? AND o.paid_time>=? AND i.is_trial_gift=1 AND i.qty>i.refunded_qty "
                +"AND i.refundable_amount>i.refunded_amount ORDER BY o.id LIMIT 1",Long.class,record.get("invitee_customer_id"),rule.get("effectiveFrom"),record.get("create_time"));
            boolean active=!eligible.isEmpty(), previous=id(record.get("score_active"))==1;
            if(active==previous) continue;
            BigDecimal amount=n(record.get("score_amount"));
            if(amount.signum()==0) amount=n(rule.get("pointsPerFriend"));
            int revision=(int)id(record.get("score_revision"))+1;
            post(customerId,"INVITE:"+record.get("id")+":"+revision,active?amount:amount.negate(),
                active?"好友注册并购买试喝礼包，邀请分入账":"试喝礼包已退款，撤回邀请分");
            jdbc.update("UPDATE mall_invite_record SET score_active=?,score_amount=?,score_revision=?,status=?,completed_order_id=?,"
                +"completed_time=IF(?=1,COALESCE(completed_time,NOW()),NULL),first_qualified_at=IF(?=1,COALESCE(first_qualified_at,?),first_qualified_at) WHERE id=?",active?1:0,amount,revision,
                active?"已完成":"待下单",active?eligible.get(0):null,active?1:0,active?1:0,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))),record.get("id"));
        }
    }
    @Transactional
    public Map<String,Object> overview(Long customerId) {
        reconcile(customerId);
        Map<String,Object> result=new LinkedHashMap<>();
        Map<String,Object> rule=config();
        result.put("rule",rule);
        result.put("registeredCount",jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=?",Integer.class,customerId));
        // A share click cannot prove that a particular person was invited; do not invent a separate visitor count.
        result.put("invitedCount",result.get("registeredCount"));
        result.put("qualifiedCount",jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=? AND score_active=1",Integer.class,customerId));
        BigDecimal net=balance(customerId);
        result.put("balance",net.max(BigDecimal.ZERO)); result.put("debt",net.negate().max(BigDecimal.ZERO));
        result.put("earned",jdbc.queryForObject("SELECT COALESCE(SUM(score_amount),0) FROM mall_invite_record WHERE inviter_customer_id=? AND score_active=1",BigDecimal.class,customerId));
        Map<String,Object> logs=history(customerId,"ledger",1);result.put("ledger",logs.get("rows"));result.put("ledgerTotal",logs.get("total"));
        result.put("rewards",jdbc.queryForList("SELECT id,name,image_key AS imageKey,invite_cost AS inviteCost,stock FROM mall_reward WHERE invite_enabled=1 AND invite_cost>0 AND status='0' ORDER BY id"));
        Map<String,Object> exchanges=history(customerId,"exchanges",1);result.put("exchanges",exchanges.get("rows"));result.put("exchangesTotal",exchanges.get("total"));
        result.put("ranking",ranking(customerId));
        return result;
    }
    public Map<String,Object> history(Long customerId,String type,int page) {
        if(page<1||page>100000||!Arrays.asList("ledger","exchanges").contains(type))throw new IllegalArgumentException("记录分页参数无效");
        boolean ledger="ledger".equals(type);
        String from=ledger?" FROM mall_friend_score_log WHERE customer_id=?":" FROM mall_exchange e JOIN mall_reward r ON r.id=e.reward_id WHERE e.customer_id=? AND e.score_currency='INVITE'";
        String columns=ledger?"id,amount,balance_after AS balanceAfter,description,create_time AS createTime":"e.id,e.exchange_no AS exchangeNo,r.name,e.qty,e.invite_points_cost AS cost,e.status,e.review_status AS reviewStatus,e.review_reason AS reviewReason,e.carrier,e.tracking_no AS trackingNo,e.create_time AS createTime";
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,customerId));result.put("rows",jdbc.queryForList("SELECT "+columns+from+" ORDER BY "+(ledger?"id":"e.id")+" DESC LIMIT 20 OFFSET ?",customerId,(page-1)*20));return result;
    }
    private static final String RANK_BASE="SELECT c.id,c.nickname,c.avatar_url AS avatarUrl,COUNT(r.id) AS qualifiedCount FROM mall_customer c JOIN mall_invite_record r ON r.inviter_customer_id=c.id AND r.score_active=1 WHERE c.status='0' GROUP BY c.id,c.nickname,c.avatar_url";
    private List<Map<String,Object>> rankPage(Long customerId,int offset,int limit) {
        List<Map<String,Object>> rows=jdbc.queryForList(RANK_BASE+" ORDER BY qualifiedCount DESC,c.id ASC LIMIT ? OFFSET ?",limit,offset);
        for(int i=0;i<rows.size();i++) {
            Map<String,Object> row=rows.get(i);row.put("rank",offset+i+1);row.put("self",id(row.remove("id"))==customerId);
            String name=s(row.get("nickname"));row.put("nickname",name.isEmpty()?"茶友":name.substring(0,name.offsetByCodePoints(0,1))+"**");
        }
        return rows;
    }
    public Map<String,Object> ranking(Long customerId) {
        int qualified=jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=? AND score_active=1",Integer.class,customerId);
        Integer rank=qualified==0?null:1+jdbc.queryForObject("SELECT COUNT(*) FROM ("+RANK_BASE+") ranked WHERE qualifiedCount>? OR (qualifiedCount=? AND id<?)",Integer.class,qualified,qualified,customerId);
        Map<String,Object> result=new LinkedHashMap<>();result.put("top",rankPage(customerId,0,50));
        int offset=rank==null?0:Math.max(0,rank-11);
        result.put("nearby",rank==null?Collections.emptyList():rankPage(customerId,offset,rank+10-offset));
        result.put("myRank",rank);return result;
    }
    static boolean requiresReview(BigDecimal cost,BigDecimal large,int today,Integer daily,int purchased,int registered,BigDecimal ratio) {
        if(large==null || daily==null) return true;
        boolean threshold=cost.compareTo(large)>=0 || today>=daily;
        boolean lowRatio=registered==0 || BigDecimal.valueOf(purchased*100L).compareTo(ratio.multiply(BigDecimal.valueOf(registered)))<0;
        return threshold && lowRatio;
    }
    static String requestKey(Object value) {
        String key=s(value); if(!key.matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("请提供有效的兑换请求标识"); return key;
    }
    @Transactional
    public Map<String,Object> exchange(Long customerId,Map<String,Object> body) {
        String request=requestKey(body.get("requestNo"));
        lockCustomer(customerId);
        List<Map<String,Object>> previous=jdbc.queryForList("SELECT exchange_no AS exchangeNo,reward_id AS rewardId,address_id AS addressId,qty,status FROM mall_exchange WHERE customer_id=? AND request_id=? AND score_currency='INVITE'",customerId,request);
        long rewardId=id(body.get("rewardId")),addressId=id(body.get("addressId"));
        int qty=n(body.get("qty")).intValueExact(); if(qty<1||qty>99)throw new IllegalArgumentException("兑换数量需为1至99");
        if(!previous.isEmpty()) {
            if(id(previous.get(0).get("rewardId"))!=rewardId || id(previous.get(0).get("qty"))!=qty || id(previous.get(0).get("addressId"))!=addressId)throw new IllegalArgumentException("同一请求不能兑换不同商品、数量或地址");
            return previous.get(0);
        }
        reconcile(customerId);
        Map<String,Object> reward=one("SELECT * FROM mall_reward WHERE id=? AND status='0' AND invite_enabled=1 AND invite_cost>0 FOR UPDATE",rewardId);
        Map<String,Object> address=one("SELECT * FROM mall_address WHERE id=? AND customer_id=?",addressId,customerId);
        for(String field:Arrays.asList("receiver_name","receiver_phone","region","detail_address"))
            if(s(address.get(field)).trim().isEmpty())throw new IllegalArgumentException("请完善收货地址");
        BigDecimal cost=n(reward.get("invite_cost")).multiply(BigDecimal.valueOf(qty));
        if(balance(customerId).compareTo(cost)<0)throw new IllegalArgumentException("邀请分不足或存在待补扣邀请分");
        if(jdbc.update("UPDATE mall_reward SET stock=stock-? WHERE id=? AND stock>=?",qty,rewardId,qty)!=1)throw new IllegalArgumentException("奖品库存不足");
        Map<String,Object> rule=config();
        int today=jdbc.queryForObject("SELECT COUNT(*) FROM mall_exchange WHERE customer_id=? AND score_currency='INVITE' AND create_time>=CURRENT_DATE()",Integer.class,customerId);
        int registered=jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=?",Integer.class,customerId);
        int purchased=jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_record WHERE inviter_customer_id=? AND score_active=1",Integer.class,customerId);
        boolean review=requiresReview(cost,rule.get("largeExchangeScore")==null?null:n(rule.get("largeExchangeScore")),today,
            rule.get("dailyExchangeLimit")==null?null:n(rule.get("dailyExchangeLimit")).intValueExact(),purchased,registered,n(rule.get("minimumPurchaseRatio")));
        String no="TF"+UUID.randomUUID().toString().replace("-","").substring(0,28);
        jdbc.update("INSERT INTO mall_exchange(exchange_no,request_id,customer_id,reward_id,qty,points_cost,address_id,receiver_name,receiver_phone,receiver_address,status,score_currency,invite_points_cost,review_status,review_reason) VALUES(?,?,?,?,?,0,?,?,?,?,?,'INVITE',?,?,?)",
            no,request,customerId,rewardId,qty,addressId,address.get("receiver_name"),address.get("receiver_phone"),s(address.get("region"))+" "+s(address.get("detail_address")),
            review?"待审核":"待发货",cost,review?"PENDING":"APPROVED",review?"邀请兑换资格需人工核验":"自动核验通过");
        post(customerId,"EXCHANGE:"+no,cost.negate(),"茶友奖品兑换："+s(reward.get("name")));
        return one("SELECT exchange_no AS exchangeNo,status,review_status AS reviewStatus FROM mall_exchange WHERE exchange_no=?",no);
    }
    public Map<String,Object> adminData() {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("config",config());
        result.put("products",jdbc.queryForList("SELECT id,name,status,is_trial_gift AS isTrialGift FROM mall_product ORDER BY id"));
        result.put("rewards",jdbc.queryForList("SELECT id,name,stock,status,invite_cost AS inviteCost,invite_enabled AS inviteEnabled FROM mall_reward ORDER BY id"));
        return result;
    }
    public Map<String,Object> adminPage(boolean ledger,int page,int size,String keyword,String status) {
        if(page<1||page>100000||size<1||size>100||keyword.length()>100)throw new IllegalArgumentException("分页或搜索参数无效");
        List<Object> args=new ArrayList<>();String from,columns;
        if(ledger) {
            from=" FROM mall_friend_score_log WHERE 1=1";
            columns="id,customer_id AS customerId,amount,balance_after AS balanceAfter,description,create_time AS createTime";
            if(!keyword.isEmpty()){if(keyword.matches("[0-9]+")){from+=" AND CAST(customer_id AS CHAR)=?";args.add(keyword);}else{from+=" AND description LIKE ?";args.add("%"+keyword+"%");}}
        }else{
            from=" FROM mall_exchange e JOIN mall_reward r ON r.id=e.reward_id WHERE e.score_currency='INVITE'";
            columns="e.id,e.exchange_no AS exchangeNo,e.customer_id AS customerId,r.name,e.qty,e.invite_points_cost AS cost,e.status,e.review_status AS reviewStatus,e.review_reason AS reviewReason,e.create_time AS createTime";
            if(!keyword.isEmpty()){from+=" AND (e.exchange_no LIKE ? OR r.name LIKE ? OR CAST(e.customer_id AS CHAR)=?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");args.add(keyword);}
            if(!status.isEmpty()){if(!Arrays.asList("待审核","待发货","配送中","已完成","已取消").contains(status))throw new IllegalArgumentException("兑换状态无效");from+=" AND e.status=?";args.add(status);}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray()));
        args.add(size);args.add((page-1)*size);result.put("rows",jdbc.queryForList("SELECT "+columns+from+" ORDER BY "+(ledger?"id":"e.id")+" DESC LIMIT ? OFFSET ?",args.toArray()));return result;
    }
    static BigDecimal positive(Object value,String label) {
        BigDecimal result=n(value); if(result.signum()<=0||result.compareTo(new BigDecimal("999999"))>0||result.scale()>2)throw new IllegalArgumentException(label+"须大于0且最多两位小数"); return result;
    }
    @Transactional
    public void saveConfig(Map<String,Object> body,Long operator) {
        Map<String,Object> current=one("SELECT * FROM mall_friend_config WHERE id=1 FOR UPDATE");
        if(id(body.get("versionNo"))!=id(current.get("version_no")))throw new IllegalArgumentException("配置已被其他管理员更新，请刷新后重试");
        BigDecimal points=positive(body.get("pointsPerFriend"),"每人邀请分");
        BigDecimal large=body.get("largeExchangeScore")==null?null:positive(body.get("largeExchangeScore"),"大额阈值");
        Integer daily=body.get("dailyExchangeLimit")==null?null:n(body.get("dailyExchangeLimit")).intValueExact();
        if(daily!=null&&(daily<1||daily>10000))throw new IllegalArgumentException("每日兑换频次需为1至10000");
        int limit=n(body.get("homeItemLimit")).intValueExact(); if(limit<1||limit>30)throw new IllegalArgumentException("首页展示数量需为1至30");
        String text=s(body.get("monthlyRewardText")),description=s(body.get("description"));
        if(text.length()>500||description.length()>500)throw new IllegalArgumentException("说明不能超过500字");
        String card=displayText(body.containsKey("inviteCardTitle")?body.get("inviteCardTitle"):current.get("invite_card_title"),"邀请卡片标题",60);
        String prefix=displayText(body.containsKey("rewardPrefix")?body.get("rewardPrefix"):current.get("reward_prefix"),"奖励数字前文案",20);
        String unit=displayText(body.containsKey("scoreUnitLabel")?body.get("scoreUnitLabel"):current.get("score_unit_label"),"邀请分显示名称",12);
        String button=displayText(body.containsKey("inviteButtonText")?body.get("inviteButtonText"):current.get("invite_button_text"),"邀请按钮文字",12);
        jdbc.update("UPDATE mall_friend_config SET points_per_friend=?,large_exchange_score=?,daily_exchange_limit=?,monthly_reward_text=?,home_item_limit=?,description=?,invite_card_title=?,reward_prefix=?,score_unit_label=?,invite_button_text=?,version_no=version_no+1,updated_by=? WHERE id=1",
            points,large,daily,text,limit,description,card,prefix,unit,button,operator);
        Object raw=body.get("trialProductIds"); if(!(raw instanceof List))throw new IllegalArgumentException("请选择试喝礼包商品");
        Set<Long> selected=new HashSet<>(); for(Object product:(List<?>)raw){long productId=id(product);one("SELECT id FROM mall_product WHERE id=?",productId);selected.add(productId);}
        jdbc.update("UPDATE mall_product SET is_trial_gift=0 WHERE is_trial_gift=1");
        for(Long productId:selected)jdbc.update("UPDATE mall_product SET is_trial_gift=1 WHERE id=?",productId);
    }
    @Transactional
    public void saveReward(Long rewardId,Map<String,Object> body) {
        BigDecimal cost=positive(body.get("inviteCost"),"兑换邀请分");
        int enabled=n(body.get("inviteEnabled")).intValueExact(); if(enabled!=0&&enabled!=1)throw new IllegalArgumentException("启停状态无效");
        if(jdbc.update("UPDATE mall_reward SET invite_cost=?,invite_enabled=? WHERE id=?",cost,enabled,rewardId)!=1)throw new IllegalArgumentException("奖品不存在");
    }
    static String displayText(Object value,String label,int max) {
        String text=s(value).trim();
        if(text.isEmpty()||text.codePointCount(0,text.length())>max||text.matches("(?s).*[<>\\p{Cntrl}].*"))
            throw new IllegalArgumentException(label+"需为1至"+max+"字纯文本，不能包含HTML或换行控制字符");
        return text;
    }
    @Transactional
    public void cancel(Long customerId,String exchangeNo) {
        lockCustomer(customerId);
        Map<String,Object> row=one("SELECT * FROM mall_exchange WHERE customer_id=? AND exchange_no=? AND score_currency='INVITE' FOR UPDATE",customerId,exchangeNo);
        if("已取消".equals(row.get("status")))return;
        if(!Arrays.asList("待审核","待发货","待处理").contains(row.get("status")))throw new IllegalArgumentException("当前兑换状态不能取消");
        post(customerId,"RETURN:"+exchangeNo,n(row.get("invite_points_cost")),"取消茶友兑换，退回邀请分");
        jdbc.update("UPDATE mall_reward SET stock=stock+? WHERE id=?",row.get("qty"),row.get("reward_id"));
        jdbc.update("UPDATE mall_exchange SET status='已取消',review_status='CANCELLED' WHERE id=?",row.get("id"));
    }
    @Transactional
    public void review(Long exchangeId,Map<String,Object> body,Long operator) {
        Map<String,Object> ref=one("SELECT customer_id FROM mall_exchange WHERE id=? AND score_currency='INVITE'",exchangeId);
        Long customerId=id(ref.get("customer_id")); lockCustomer(customerId);
        Map<String,Object> row=one("SELECT * FROM mall_exchange WHERE id=? FOR UPDATE",exchangeId);
        String action=s(body.get("action")),reason=s(body.get("reason")).trim();
        if(!Arrays.asList("APPROVED","REJECTED").contains(action)||reason.length()<2||reason.length()>500)throw new IllegalArgumentException("请选择审核结果并填写2至500字原因");
        if(action.equals(row.get("review_status")))return;
        if(!"PENDING".equals(row.get("review_status")))throw new IllegalArgumentException("该兑换已审核，不能重复处理");
        if("REJECTED".equals(action)) {
            post(customerId,"RETURN:"+row.get("exchange_no"),n(row.get("invite_points_cost")),"茶友兑换审核拒绝，退回邀请分");
            jdbc.update("UPDATE mall_reward SET stock=stock+? WHERE id=?",row.get("qty"),row.get("reward_id"));
        }
        jdbc.update("UPDATE mall_exchange SET review_status=?,review_reason=?,reviewed_by=?,reviewed_at=NOW(),status=? WHERE id=? AND review_status='PENDING'",action,reason,operator,"APPROVED".equals(action)?"待发货":"已取消",exchangeId);
        notify(customerId,"REVIEW:"+exchangeId,"茶友兑换审核结果",("APPROVED".equals(action)?"审核通过：":"审核未通过：")+reason,"records");
    }
}
