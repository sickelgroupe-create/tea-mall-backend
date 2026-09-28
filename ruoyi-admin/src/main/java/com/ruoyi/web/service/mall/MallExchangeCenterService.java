package com.ruoyi.web.service.mall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read model for the two-column exchange center. All writes stay in the existing domain services. */
@Service
public class MallExchangeCenterService
{
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MallService mallService;

    public Map<String,Object> overview(Long customerId)
    {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        Map<String,Object> points=new LinkedHashMap<String,Object>();
        points.put("availablePoints",jdbc.queryForObject("SELECT points FROM mall_customer WHERE id=?",Integer.class,customerId));
        points.put("pendingPoints",jdbc.queryForObject("SELECT COALESCE(SUM(original_points-reversed_points),0) FROM mall_points_accrual WHERE customer_id=? AND status='待生效'",Integer.class,customerId));
        points.put("rewardCount",jdbc.queryForObject("SELECT COUNT(*) FROM mall_reward WHERE status='0' AND stock>0",Integer.class));
        points.put("debtPoints",jdbc.queryForObject("SELECT COALESCE(SUM(points_amount-settled_points),0) FROM mall_points_debt WHERE customer_id=? AND status='待补扣'",Integer.class,customerId));
        points.put("route","pointsCenter");
        result.put("points",points);

        List<Map<String,Object>> records=mallService.inviteRecords(customerId);
        int effective=0;
        for(Map<String,Object> row:records) if("已完成".equals(String.valueOf(row.get("status")))) effective++;
        List<Map<String,Object>> tiers=inviteTiers(customerId,effective);
        int claimable=0;
        for(Map<String,Object> row:tiers) if("可领取".equals(String.valueOf(row.get("state")))) claimable++;
        Map<String,Object> invite=new LinkedHashMap<String,Object>();
        invite.put("effectiveInvites",effective);
        invite.put("totalInvites",records.size());
        invite.put("claimableRewards",claimable);
        invite.put("tiers",tiers);
        invite.put("scene",mallService.inviteSceneSummary(customerId));
        invite.put("route","inviteRewards");
        result.put("invite",invite);
        return result;
    }

    public Map<String,Object> inviteRewards(Long customerId)
    {
        Map<String,Object> overview=overview(customerId);
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.putAll((Map<String,Object>)overview.get("invite"));
        result.put("records",mallService.inviteRecords(customerId));
        result.put("rule",jdbc.queryForMap("SELECT valid_condition AS validCondition,description,version_no AS versionNo FROM mall_invite_rule_config WHERE id=1 AND status='0'"));
        return result;
    }

    private List<Map<String,Object>> inviteTiers(Long customerId,int effective)
    {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,rule_name AS ruleName,required_completed_invites AS requiredCount,reward_type AS rewardType,reward_id AS rewardId,reward_qty AS rewardQty,reward_points AS rewardPoints,stock,gift_value AS giftValue,gift_contents AS giftContents,start_time AS startTime,end_time AS endTime FROM mall_invite_gift_rule WHERE status='0' AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW()) ORDER BY sort_no,required_completed_invites,id");
        for(Map<String,Object> row:rows)
        {
            int claimed=jdbc.queryForObject("SELECT COUNT(*) FROM mall_invite_gift_claim WHERE customer_id=? AND rule_id=?",Integer.class,customerId,row.get("id"));
            int threshold=((Number)row.get("requiredCount")).intValue();
            int stock=((Number)row.get("stock")).intValue();
            row.put("progress",effective);
            row.put("claimed",claimed>0);
            row.put("state",claimed>0?"已领取":effective>=threshold&&stock>0?"可领取":"未达到");
        }
        return rows;
    }
}
