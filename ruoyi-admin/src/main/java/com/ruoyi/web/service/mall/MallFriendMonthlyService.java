package com.ruoyi.web.service.mall;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Period snapshots reuse referral eligibility; monetary awards are NOT payments. */
@Service
public class MallFriendMonthlyService {
 private final JdbcTemplate jdbc;
 public MallFriendMonthlyService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new IllegalArgumentException("记录不存在或无权访问");return r.get(0);}
 private static String s(Object v){return v==null?"":v.toString().trim();}
 private static long id(Object v){try{return new BigDecimal(s(v)).longValueExact();}catch(Exception e){throw new IllegalArgumentException("记录参数无效");}}
 private static Timestamp now(){return Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));}
 private static String reason(Map<String,Object>b){String v=s(b.get("reason"));if(v.length()<2||v.length()>500)throw new IllegalArgumentException("请填写2至500字操作原因");return v;}
 private static Timestamp date(Object v){try{if(v instanceof Timestamp)return (Timestamp)v;if(v instanceof LocalDateTime)return Timestamp.valueOf((LocalDateTime)v);return Timestamp.valueOf(s(v));}catch(Exception e){throw new IllegalArgumentException("时间格式应为 yyyy-MM-dd HH:mm:ss（北京时间）");}}
 private Map<String,Object> period(long id,boolean lock){return one("SELECT * FROM mall_friend_period WHERE id=?"+(lock?" FOR UPDATE":""),id);}
 private void event(long p,Long a,long actor,String type,String action,String old,String next,String reason){jdbc.update("INSERT INTO mall_friend_monthly_event(period_id,award_id,actor_id,actor_type,action,old_status,new_status,reason) VALUES(?,?,?,?,?,?,?,?)",p,a,actor,type,action,old,next,reason);}
 private void notifyAward(Map<String,Object>a,String label){jdbc.update("INSERT INTO mall_notification(customer_id,category,title,content,target_route,target_query,source_type,source_key,event_code) VALUES(?,'活动','月度奖励状态更新',?,'/pages/invite/invite','', 'FRIEND_MONTHLY',?,?) ON DUPLICATE KEY UPDATE content=VALUES(content)",a.get("customer_id"),label,"AWARD:"+a.get("id"),a.get("status"));}
 static List<BigDecimal> amounts(Object raw){
  if(!(raw instanceof List)||((List<?>)raw).size()!=10)throw new IllegalArgumentException("请配置第1至第10名奖励金额");
  List<BigDecimal> out=new ArrayList<>();boolean enabled=false;
  for(Object v:(List<?>)raw){BigDecimal n;try{n=new BigDecimal(s(v));}catch(Exception e){throw new IllegalArgumentException("奖励金额格式错误");}if(n.signum()<0||n.scale()>2||n.compareTo(new BigDecimal("999999.99"))>0)throw new IllegalArgumentException("金额需为0至999999.99且最多两位小数");out.add(n);enabled|=n.signum()>0;}
  if(!enabled)throw new IllegalArgumentException("至少配置一个名次的奖励金额，0表示该名次不发奖");return out;
 }
 @Transactional public long save(Map<String,Object>b,long actor){
  String title=s(b.get("title")),method=s(b.get("payoutMethod"));Timestamp start=date(b.get("startsAt")),end=date(b.get("endsAt"));
  if(title.isEmpty()||title.length()>80)throw new IllegalArgumentException("标题需为1至80字");
  if(!Arrays.asList("TEST_CNY","OFFLINE_CNY").contains(method))throw new IllegalArgumentException("请选择测试发放或线下发放登记，未接入真实转账");
  if(!end.after(start)||end.getTime()-start.getTime()>366L*86400000||start.toLocalDateTime().getYear()<2020)throw new IllegalArgumentException("统计周期需大于0且不超过366天");
  List<BigDecimal> values=amounts(b.get("amounts"));long p;
  if(b.get("id")==null){
   String request=MallTeaFriendService.requestKey(b.get("requestNo"));
   one("SELECT id FROM mall_friend_period_lock WHERE id=1 FOR UPDATE");
   List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM mall_friend_period WHERE request_key=?",request);
   if(!previous.isEmpty()){
    Map<String,Object> old=previous.get(0);p=id(old.get("id"));
    if(!title.equals(old.get("title"))||!method.equals(old.get("payout_method"))||!start.equals(date(old.get("starts_at")))||!end.equals(date(old.get("ends_at"))))throw new IllegalArgumentException("请求标识已用于不同配置");
    List<BigDecimal> prior=jdbc.queryForList("SELECT amount FROM mall_friend_period_prize WHERE period_id=? ORDER BY rank_no",BigDecimal.class,p);
    for(int i=0;i<10;i++)if(values.get(i).compareTo(prior.get(i))!=0)throw new IllegalArgumentException("请求标识已用于不同金额");return p;
   }
   jdbc.update("INSERT INTO mall_friend_period(request_key,title,starts_at,ends_at,payout_method,created_by) VALUES(?,?,?,?,?,?)",request,title,start,end,method,actor);
   p=jdbc.queryForObject("SELECT id FROM mall_friend_period WHERE request_key=?",Long.class,request);
  }else{
   p=id(b.get("id"));Map<String,Object> old=period(p,true);
   if(!"DRAFT".equals(old.get("status"))||id(b.get("versionNo"))!=id(old.get("version_no")))throw new IllegalArgumentException("仅可修改未发布草稿；数据已改变时请刷新");
   jdbc.update("UPDATE mall_friend_period SET title=?,starts_at=?,ends_at=?,payout_method=?,version_no=version_no+1 WHERE id=?",title,start,end,method,p);
   jdbc.update("DELETE FROM mall_friend_period_prize WHERE period_id=?",p);
  }
  for(int i=0;i<10;i++)jdbc.update("INSERT INTO mall_friend_period_prize(period_id,rank_no,amount) VALUES(?,?,?)",p,i+1,values.get(i));
  event(p,null,actor,"ADMIN","SAVE","DRAFT","DRAFT","保存周期草稿");return p;
 }
 @Transactional public void publish(long p,Map<String,Object>b,long actor){
  one("SELECT id FROM mall_friend_period_lock WHERE id=1 FOR UPDATE");Map<String,Object> row=period(p,true);String why=reason(b);
  if("OPEN".equals(row.get("status")))return;
  if(!"DRAFT".equals(row.get("status"))||!date(row.get("ends_at")).after(now()))throw new IllegalArgumentException("仅可发布尚未结束的草稿");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM mall_friend_period WHERE id<>? AND (status IN ('OPEN','SETTLED') OR settled_at IS NOT NULL) AND starts_at<? AND ends_at>?",Integer.class,p,row.get("ends_at"),row.get("starts_at"))>0)throw new IllegalArgumentException("奖励周期不能与已发布或已结算周期重叠");
  jdbc.update("UPDATE mall_friend_period SET status='OPEN',version_no=version_no+1 WHERE id=?",p);event(p,null,actor,"ADMIN","PUBLISH","DRAFT","OPEN",why);
 }
 private static final String ELIGIBLE=" FROM mall_invite_record r JOIN mall_customer c ON c.id=r.inviter_customer_id WHERE c.status='0' AND r.score_active=1 AND r.first_qualified_at>=? AND r.first_qualified_at<?";
 private List<Map<String,Object>> live(Map<String,Object>p){return jdbc.queryForList("SELECT r.inviter_customer_id AS customerId,COUNT(*) AS qualifiedCount"+ELIGIBLE+" GROUP BY r.inviter_customer_id ORDER BY qualifiedCount DESC,customerId ASC LIMIT 10001",p.get("starts_at"),p.get("ends_at"));}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public void settle(long p,Map<String,Object>b,long actor){
  Map<String,Object> row=period(p,true);String why=reason(b);if("SETTLED".equals(row.get("status")))return;
  if(!"OPEN".equals(row.get("status"))||date(row.get("ends_at")).after(now()))throw new IllegalArgumentException("只有周期结束后才能结算");
  // Both roster and ranks are read from the same InnoDB consistent snapshot.
  List<Map<String,Object>> ranks=live(row);if(ranks.size()>10000)throw new IllegalArgumentException("参与人数超过单次结算安全上限，请联系维护人员分批结算");
  List<Map<String,Object>> members=jdbc.queryForList("SELECT r.id,r.inviter_customer_id AS customerId"+ELIGIBLE,row.get("starts_at"),row.get("ends_at"));
  for(Map<String,Object>m:members)jdbc.update("INSERT INTO mall_friend_period_member(period_id,invite_record_id,customer_id) VALUES(?,?,?)",p,m.get("id"),m.get("customerId"));
  List<BigDecimal> values=jdbc.queryForList("SELECT amount FROM mall_friend_period_prize WHERE period_id=? ORDER BY rank_no",BigDecimal.class,p);
  for(int i=0;i<ranks.size();i++){
   Map<String,Object>r=ranks.get(i);jdbc.update("INSERT INTO mall_friend_period_rank(period_id,customer_id,rank_no,qualified_count) VALUES(?,?,?,?)",p,r.get("customerId"),i+1,r.get("qualifiedCount"));
   if(i<10&&values.get(i).signum()>0){
    jdbc.update("INSERT INTO mall_friend_monthly_award(period_id,customer_id,rank_no,qualified_count,amount,payout_method) VALUES(?,?,?,?,?,?)",p,r.get("customerId"),i+1,r.get("qualifiedCount"),values.get(i),row.get("payout_method"));
    Map<String,Object>a=one("SELECT * FROM mall_friend_monthly_award WHERE period_id=? AND customer_id=?",p,r.get("customerId"));notifyAward(a,"您获得周期奖励，等待后台核验发放");
   }
  }
  jdbc.update("UPDATE mall_friend_period SET status='SETTLED',settled_by=?,settled_at=?,version_no=version_no+1 WHERE id=?",actor,now(),p);event(p,null,actor,"ADMIN","SETTLE","OPEN","SETTLED",why);
 }
 private int changed(long p){return jdbc.queryForObject("SELECT COUNT(*) FROM mall_friend_period_member m LEFT JOIN mall_invite_record r ON r.id=m.invite_record_id LEFT JOIN mall_customer c ON c.id=m.customer_id WHERE m.period_id=? AND (r.id IS NULL OR r.score_active<>1 OR c.status<>'0')",Integer.class,p);}
 @Transactional(isolation=Isolation.READ_COMMITTED) public void payout(long award,Map<String,Object>b,long actor){
  Map<String,Object>ref=one("SELECT period_id,customer_id FROM mall_friend_monthly_award WHERE id=?",award);long p=id(ref.get("period_id"));
  Map<String,Object>period=period(p,true); // serializes payouts and qualification recheck
  // Lock all recorded referrals against concurrent refund/requalification writes.
  jdbc.queryForList("SELECT r.id FROM mall_invite_record r JOIN mall_friend_period_member m ON m.invite_record_id=r.id WHERE m.period_id=? ORDER BY r.id FOR UPDATE",p);
  Map<String,Object>a=one("SELECT * FROM mall_friend_monthly_award WHERE id=? FOR UPDATE",award);String why=reason(b),status=s(a.get("status")),action=s(b.get("action")),reference=s(b.get("reference"));
  if(!Arrays.asList("TEST_PAY","RECORD_OFFLINE").contains(action))throw new IllegalArgumentException("发放操作无效");
  String target="TEST_PAY".equals(action)?"TEST_PAID":"OFFLINE_SENT";
  if(!("TEST_PAY".equals(action)?"TEST_CNY":"OFFLINE_CNY").equals(a.get("payout_method")))throw new IllegalArgumentException("发放方式与已发布规则不一致");
  if(target.equals(status)){if(!"TEST_PAID".equals(target)&&!reference.equals(a.get("payout_reference")))throw new IllegalArgumentException("已登记不同发放凭证，不能重复发放");return;}
  if(!"SETTLED".equals(period.get("status"))||!"PENDING".equals(status))throw new IllegalArgumentException("该奖励不能重复发放；争议记录需核查而不能再次转账");
  if(changed(p)>0)throw new IllegalArgumentException("结算名单出现退款或账号资格变化，已暂停整期发放，请核查；不能按旧榜继续发奖");
  if("OFFLINE_SENT".equals(target)&&(reference.length()<4||reference.length()>120))throw new IllegalArgumentException("请登记4至120字真实线下发放凭证编号（系统不会转账）");
  if("TEST_PAID".equals(target))reference="SIM-MONTHLY-"+award;
  jdbc.update("UPDATE mall_friend_monthly_award SET status=?,payout_reference=?,reason=?,paid_by=?,paid_at=? WHERE id=? AND status='PENDING'",target,reference,why,actor,now(),award);
  event(p,award,actor,"ADMIN",action,status,target,why);a.put("status",target);notifyAward(a,"TEST_PAID".equals(target)?"月度奖励测试发放完成，不代表真实到账":"管理员登记已线下发放，请核对后确认收款；未收到可反馈");
 }
 @Transactional public void acknowledge(long award,long customer,Map<String,Object>b){
  Map<String,Object>a=one("SELECT * FROM mall_friend_monthly_award WHERE id=? AND customer_id=? FOR UPDATE",award,customer);String action=s(b.get("action")),old=s(a.get("status"));
  if(!Arrays.asList("RECEIVED","DISPUTED").contains(action))throw new IllegalArgumentException("确认操作无效");
  if(action.equals(old))return;
  if(!"OFFLINE_SENT".equals(old)&&!("DISPUTED".equals(old)&&"RECEIVED".equals(action)))throw new IllegalArgumentException("仅线下发放记录可以确认收款或反馈");
  String why="RECEIVED".equals(action)?"用户确认已收到线下奖励":reason(b);
  jdbc.update("UPDATE mall_friend_monthly_award SET status=?,received_at=?,reason=? WHERE id=?",action,"RECEIVED".equals(action)?now():null,why,award);event(id(a.get("period_id")),award,customer,"CUSTOMER",action,old,action,why);
 }
 @Transactional public void reply(long award,Map<String,Object>b,long actor){Map<String,Object>a=one("SELECT * FROM mall_friend_monthly_award WHERE id=? FOR UPDATE",award);if(!"DISPUTED".equals(a.get("status")))throw new IllegalArgumentException("仅待核查记录可回复");String why=reason(b);event(id(a.get("period_id")),award,actor,"ADMIN","REPLY","DISPUTED","DISPUTED",why);notifyAward(a,"后台已回复奖励收款反馈，请查看处理记录");}
 @Transactional public void close(long p,Map<String,Object>b,long actor){
  Map<String,Object>row=period(p,true);String why=reason(b),old=s(row.get("status"));if("CANCELLED".equals(old))return;
  List<Map<String,Object>>pending=jdbc.queryForList("SELECT * FROM mall_friend_monthly_award WHERE period_id=? AND status='PENDING' ORDER BY id FOR UPDATE",p);
  for(Map<String,Object>a:pending){jdbc.update("UPDATE mall_friend_monthly_award SET status='CANCELLED',reason=? WHERE id=?",why,a.get("id"));event(p,id(a.get("id")),actor,"ADMIN","CLOSE","PENDING","CANCELLED",why);a.put("status","CANCELLED");notifyAward(a,"本期未发放奖励已关闭："+why);}
  jdbc.update("UPDATE mall_friend_period SET status='CANCELLED',version_no=version_no+1 WHERE id=?",p);event(p,null,actor,"ADMIN","CLOSE",old,"CANCELLED",why);
 }
 private Map<String,Object> display(Map<String,Object>r){Map<String,Object>x=new LinkedHashMap<>();x.put("id",r.get("id"));x.put("title",r.get("title"));x.put("startsAt",date(r.get("starts_at")).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));x.put("endsAt",date(r.get("ends_at")).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));x.put("payoutMethod",r.get("payout_method"));x.put("status",r.get("status"));x.put("versionNo",r.get("version_no"));x.put("amounts",jdbc.queryForList("SELECT amount FROM mall_friend_period_prize WHERE period_id=? ORDER BY rank_no",BigDecimal.class,r.get("id")));return x;}
 public Map<String,Object> periods(boolean admin,int page){if(page<1||page>10000)throw new IllegalArgumentException("页码无效");String where=admin?"":" WHERE status<>'DRAFT'";Map<String,Object>out=new LinkedHashMap<>();out.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM mall_friend_period"+where,Integer.class));List<Map<String,Object>>rows=new ArrayList<>();for(Map<String,Object>r:jdbc.queryForList("SELECT * FROM mall_friend_period"+where+" ORDER BY starts_at DESC,id DESC LIMIT 20 OFFSET ?",(page-1)*20))rows.add(display(r));out.put("rows",rows);return out;}
 public Map<String,Object> board(long p,long customer){Map<String,Object>row=period(p,false);if("DRAFT".equals(row.get("status")))throw new IllegalArgumentException("该周期尚未发布");List<Map<String,Object>>ranks=row.get("settled_at")!=null?jdbc.queryForList("SELECT customer_id AS customerId,qualified_count AS qualifiedCount FROM mall_friend_period_rank WHERE period_id=? ORDER BY rank_no",p):live(row);Map<String,Object>out=display(row);List<Map<String,Object>>top=new ArrayList<>();out.put("myRank",null);out.put("myCount",0);for(int i=0;i<ranks.size();i++){Map<String,Object>r=ranks.get(i);long owner=id(r.get("customerId"));if(owner==customer){out.put("myRank",i+1);out.put("myCount",r.get("qualifiedCount"));}if(i<50){Map<String,Object>x=new LinkedHashMap<>();String name=s(one("SELECT nickname FROM mall_customer WHERE id=?",owner).get("nickname"));x.put("nickname",name.isEmpty()?"茶友":name.substring(0,name.offsetByCodePoints(0,1))+"**");x.put("rank",i+1);x.put("qualifiedCount",r.get("qualifiedCount"));x.put("self",owner==customer);top.add(x);}}out.put("top",top);out.put("eligibilityChanged",row.get("settled_at")!=null&&changed(p)>0);return out;}
 public Map<String,Object> awards(Long customer,int page,Long p){if(page<1||page>10000)throw new IllegalArgumentException("页码无效");String where=" WHERE 1=1";List<Object>args=new ArrayList<>();if(customer!=null){where+=" AND a.customer_id=?";args.add(customer);}if(p!=null){where+=" AND a.period_id=?";args.add(p);}String from=" FROM mall_friend_monthly_award a JOIN mall_friend_period p ON p.id=a.period_id"+where;Map<String,Object>out=new LinkedHashMap<>();out.put("total",jdbc.queryForObject("SELECT COUNT(*)"+from,Integer.class,args.toArray()));args.add((page-1)*20);List<Map<String,Object>>rows=jdbc.queryForList("SELECT a.id,a.period_id AS periodId,a.customer_id AS customerId,p.title,a.rank_no AS rankNo,a.qualified_count AS qualifiedCount,a.amount,a.payout_method AS payoutMethod,a.status,a.payout_reference AS payoutReference,a.reason,a.paid_at AS paidAt,a.received_at AS receivedAt"+from+" ORDER BY a.id DESC LIMIT 20 OFFSET ?",args.toArray());for(Map<String,Object>r:rows){r.put("eligibilityChanged",changed(id(r.get("periodId")))>0);r.put("events",jdbc.queryForList("SELECT actor_type AS actorType,action,new_status AS status,reason,create_time AS createTime FROM mall_friend_monthly_event WHERE award_id=? ORDER BY id",r.get("id")));if(customer!=null)r.remove("customerId");}out.put("rows",rows);return out;}
}
