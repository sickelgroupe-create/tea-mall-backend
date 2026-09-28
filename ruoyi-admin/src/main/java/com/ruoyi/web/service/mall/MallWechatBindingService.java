package com.ruoyi.web.service.mall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Attaches an official WeChat identity to the existing customer, never merges assets. */
@Service
public class MallWechatBindingService
{
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MallWechatService wechat;

    public boolean isBound(Long customerId)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_identity i JOIN mall_customer c ON c.id=i.customer_id AND c.status='0' WHERE i.customer_id=? "
                + "AND i.provider='WECHAT_MINI_PROGRAM' AND i.app_id=?", Integer.class, customerId, wechat.appId()) > 0;
    }

    public Map<String,Object> status(Long customerId)
    {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("bound", isBound(customerId));
        result.put("configured", wechat.configured());
        result.put("shareRequiresWechat", true);
        return result;
    }

    public void requireBound(Long customerId)
    {
        if (!isBound(customerId)) throw new IllegalArgumentException("请先在微信小程序中绑定微信，再使用邀请分享");
    }

    @Transactional
    public Map<String,Object> bindCurrent(Long customerId, String code)
    {
        // Only the controller's authenticated customer id is accepted; no body customerId/openid.
        MallWechatService.WechatIdentity identity = wechat.exchangeMiniProgramCode(code);
        attach(customerId, identity.getAppId(), identity.getOpenId(), identity.getUnionId());
        return status(customerId);
    }

    @Transactional
    public void attach(Long customerId, String appId, String openId, String unionId)
    {
        if (appId == null || appId.trim().isEmpty() || openId == null || openId.trim().isEmpty())
            throw new IllegalArgumentException("微信身份无效，请重新授权");
        List<Map<String,Object>> customers = jdbc.queryForList("SELECT status FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        if (customers.isEmpty() || !"0".equals(customers.get(0).get("status")))
            throw new MallAuthenticationException("账号不存在或已停用");
        List<Map<String,Object>> existing = jdbc.queryForList("SELECT customer_id,open_id FROM mall_customer_identity "
                + "WHERE provider='WECHAT_MINI_PROGRAM' AND app_id=? AND (customer_id=? OR open_id=?)", appId, customerId, openId);
        boolean same = false;
        for (Map<String,Object> row : existing)
        {
            if (((Number)row.get("customer_id")).longValue() != customerId.longValue())
                throw new IllegalArgumentException("此微信已绑定其他商城账号，请使用原账号登录；不会自动合并账号");
            if (!openId.equals(row.get("open_id")))
                throw new IllegalArgumentException("当前账号已绑定另一微信，请联系客服处理");
            same = true;
        }
        if (same) return;
        try
        {
            jdbc.update("INSERT INTO mall_customer_identity(customer_id,provider,app_id,open_id,union_id) VALUES(?,'WECHAT_MINI_PROGRAM',?,?,?)",
                    customerId, appId, openId, unionId == null ? "" : unionId);
        }
        catch (DuplicateKeyException conflict)
        {
            throw new IllegalArgumentException("微信绑定状态已变化，请重新登录后检查绑定状态");
        }
        jdbc.update("INSERT INTO mall_customer_security_log(customer_id,event_type,new_value_hash,source_name) VALUES(?,'BIND_WECHAT',?,'微信小程序')",
                customerId, digest(openId));
    }

    private String digest(String value)
    {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
