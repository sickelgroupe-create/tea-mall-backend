package com.ruoyi.web.service.mall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.utils.SecurityUtils;

/** Resolves opaque storefront sessions without ever trusting a client supplied customer id. */
@Service
public class MallSessionService
{
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{24,128}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired private MallWechatBindingService wechatBindingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Test storefront mode creates one isolated demo member per opaque browser
     * session. It deliberately never reuses a real customer account.
     */
    @Value("${mall.test-mode:false}")
    private boolean testMode;

    public static class SessionContext
    {
        private final Long customerId;
        private final boolean authenticated;
        private final String sessionToken;
        private final boolean expired;

        SessionContext(Long customerId, boolean authenticated, String sessionToken, boolean expired)
        {
            this.customerId = customerId;
            this.authenticated = authenticated;
            this.sessionToken = sessionToken;
            this.expired = expired;
        }

        public Long getCustomerId() { return customerId; }
        public boolean isAuthenticated() { return authenticated; }
        public String getSessionToken() { return sessionToken; }
        public boolean isExpired() { return expired; }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionContext resolve(String token)
    {
        String tokenHash = tokenHash(token);
        Integer revoked = jdbc.queryForObject("SELECT COUNT(*) FROM mall_revoked_session WHERE token_hash=? AND expires_time>NOW()", Integer.class, tokenHash);
        if (revoked != null && revoked > 0)
        {
            throw new MallAuthenticationException("商城会话已退出或已轮换，请重新登录");
        }
        List<Map<String, Object>> rows = findSession(tokenHash);
        if (!rows.isEmpty())
        {
            return resolveExistingSession(token, tokenHash, rows.get(0));
        }

        Integer locked = jdbc.queryForObject("SELECT GET_LOCK(?,5)", Integer.class, tokenHash);
        if (locked == null || locked != 1)
        {
            throw new MallAuthenticationException("商城会话初始化繁忙，请刷新重试");
        }
        try
        {
            // A parallel request may have created this token while this
            // request was waiting for the advisory lock.
            rows = findSession(tokenHash);
            if (!rows.isEmpty())
            {
                return resolveExistingSession(token, tokenHash, rows.get(0));
            }
            // 测试支付和测试短信开关不能改变登录身份。任何新会话都先是游客，
            // 必须再经过密码、短信验证码或微信官方 code 流程才能成为已登录会员。
            jdbc.update("INSERT INTO mall_customer(nickname,phone,points,status) VALUES('游客','',0,'2')");
            Long guestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO mall_session(token_hash,customer_id,authenticated,expires_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 30 DAY))",
                    tokenHash, guestId, 0);
            return new SessionContext(guestId, false, token, false);
        }
        finally
        {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, tokenHash);
        }
    }

    private List<Map<String, Object>> findSession(String tokenHash)
    {
        return jdbc.queryForList(
                "SELECT s.customer_id AS customerId, s.authenticated, c.status,"
              + "CASE WHEN s.expires_at>NOW() THEN 1 ELSE 0 END AS active "
              + "FROM mall_session s JOIN mall_customer c ON c.id=s.customer_id "
              + "WHERE s.token_hash=?", tokenHash);
    }

    private SessionContext resolveExistingSession(String token, String tokenHash, Map<String, Object> row)
    {
        if (intValue(row.get("active")) != 1)
        {
            return rotateToGuest(tokenHash, true);
        }
        Long customerId = ((Number) row.get("customerId")).longValue();
        String status = String.valueOf(row.get("status"));
        if ("1".equals(status) || "3".equals(status))
        {
            jdbc.update("UPDATE mall_session SET authenticated=0,last_seen_at=NOW() WHERE token_hash=?", tokenHash);
            return new SessionContext(customerId, false, token, false);
        }
        jdbc.update("UPDATE mall_session SET last_seen_at=NOW() WHERE token_hash=?", tokenHash);
        return new SessionContext(customerId, intValue(row.get("authenticated")) == 1, token, false);
    }

    public SessionContext requireMember(String token)
    {
        SessionContext context = resolve(token);
        if (!context.isAuthenticated())
        {
            throw new MallAuthenticationException(context.isExpired() ? "登录状态已过期，请重新登录" : "请先登录后再操作");
        }
        return context;
    }

    @Transactional
    public SessionContext login(String token, String phone, String password)
    {
        return login(token, phone, password, "unknown");
    }

    @Transactional
    public SessionContext login(String token, String phone, String password, String clientIp)
    {
        String normalizedPhone = validateCredentials(phone, password, false);
        String phoneHash = sha256(normalizedPhone);
        String clientKey = sha256(token == null ? "missing" : token).substring(0, 32);
        String ipHash = sha256(clientIp == null ? "unknown" : clientIp).substring(0, 32);
        Integer phoneFailures = jdbc.queryForObject("SELECT COUNT(*) FROM mall_login_attempt WHERE phone_hash=? "
                + "AND success=0 AND attempt_time>=DATE_SUB(NOW(),INTERVAL 15 MINUTE)", Integer.class, phoneHash);
        Integer ipFailures = jdbc.queryForObject("SELECT COUNT(*) FROM mall_login_attempt WHERE ip_hash=? "
                + "AND success=0 AND attempt_time>=DATE_SUB(NOW(),INTERVAL 15 MINUTE)", Integer.class, ipHash);
        Integer clientFailures = jdbc.queryForObject("SELECT COUNT(*) FROM mall_login_attempt WHERE client_key=? "
                + "AND success=0 AND attempt_time>=DATE_SUB(NOW(),INTERVAL 15 MINUTE)", Integer.class, clientKey);
        if ((phoneFailures != null && phoneFailures >= 5)
                || (clientFailures != null && clientFailures >= 10)
                || (ipFailures != null && ipFailures >= 30))
        {
            throw new IllegalArgumentException("登录失败次数过多，请15分钟后再试");
        }
        SessionContext guest = resolve(token);
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT id,status,password_hash AS passwordHash FROM mall_customer WHERE phone=? AND status<>'2' LIMIT 1", normalizedPhone);
        String normalizedPassword = password == null ? "" : password;
        if (customers.isEmpty())
        {
            recordLoginFailure(phoneHash, clientKey, ipHash);
            throw new IllegalArgumentException("账号不存在，请先注册");
        }
        String passwordHash = String.valueOf(customers.get(0).get("passwordHash"));
        if (passwordHash == null || passwordHash.trim().isEmpty() || "null".equals(passwordHash)
                || !SecurityUtils.matchesPassword(normalizedPassword, passwordHash))
        {
            recordLoginFailure(phoneHash, clientKey, ipHash);
            throw new IllegalArgumentException("手机号或密码不正确");
        }
        if ("1".equals(String.valueOf(customers.get(0).get("status"))))
        {
            throw new IllegalArgumentException("账号已停用，请联系客服");
        }
        Long memberId = ((Number) customers.get(0).get("id")).longValue();
        String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId, null);
        jdbc.update("INSERT INTO mall_login_attempt(phone_hash,client_key,ip_hash,success) VALUES(?,?,?,1)", phoneHash, clientKey, ipHash);
        return new SessionContext(memberId, true, issuedToken, false);
    }

    /**
     * Completes login only after MallSmsAuthService has consumed a valid code.
     * The client never supplies a customer id and cannot choose the account.
     */
    @Transactional
    public SessionContext loginWithVerifiedPhone(String token, String phone)
    {
        String normalizedPhone = validatePhone(phone);
        SessionContext guest = resolve(token);
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT id,status FROM mall_customer WHERE phone=? AND status<>'2' LIMIT 1",
                normalizedPhone);
        if (customers.isEmpty())
        {
            throw new IllegalArgumentException("账号不存在，请先注册");
        }
        if ("1".equals(String.valueOf(customers.get(0).get("status"))))
        {
            throw new IllegalArgumentException("账号已停用，请联系客服");
        }
        Long memberId = ((Number) customers.get(0).get("id")).longValue();
        String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId, null);
        return new SessionContext(memberId, true, issuedToken, false);
    }

    /**
     * Password recovery is called only after a one-time RESET code is consumed.
     * Existing sessions are revoked so the old credential cannot leave an
     * already-authenticated client active.
     */
    @Transactional
    public void resetPasswordWithVerifiedPhone(String phone, String newPassword)
    {
        String normalizedPhone = validateCredentials(phone, newPassword, true);
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT id,status FROM mall_customer WHERE phone=? AND status<>'2' LIMIT 1",
                normalizedPhone);
        if (customers.isEmpty())
        {
            throw new IllegalArgumentException("账号不存在，请先注册");
        }
        if ("1".equals(String.valueOf(customers.get(0).get("status"))))
        {
            throw new IllegalArgumentException("账号已停用，请联系客服");
        }
        Long memberId = ((Number) customers.get(0).get("id")).longValue();
        jdbc.update("UPDATE mall_customer SET password_hash=?,update_time=NOW() WHERE id=?",
                SecurityUtils.encryptPassword(newPassword), memberId);
        jdbc.update("INSERT IGNORE INTO mall_revoked_session(token_hash) "
                + "SELECT token_hash FROM mall_session WHERE customer_id=?", memberId);
        jdbc.update("UPDATE mall_session SET authenticated=0,last_seen_at=NOW() WHERE customer_id=?",
                memberId);
    }

    private void recordLoginFailure(String phoneHash, String clientKey, String ipHash)
    {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO mall_login_attempt(phone_hash,client_key,ip_hash,success) VALUES(?,?,?,0)", phoneHash, clientKey, ipHash));
    }

    @Transactional
    public Map<String, Object> changePhone(Long customerId, String phone)
    {
        String normalizedPhone = validatePhone(phone);
        Map<String, Object> customer = firstCustomer("SELECT id,phone,status FROM mall_customer WHERE id=? FOR UPDATE", customerId);
        if (customer == null || !"0".equals(String.valueOf(customer.get("status"))))
            throw new MallAuthorizationException("用户不存在或账号不可修改");
        String oldPhone = customer.get("phone") == null ? "" : String.valueOf(customer.get("phone"));
        if (normalizedPhone.equals(oldPhone)) return customer;
        Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer WHERE phone=? AND id<>? AND status<>'2'",
                Integer.class, normalizedPhone, customerId);
        if (occupied != null && occupied > 0) throw new IllegalArgumentException("该手机号已绑定其他账户");
        jdbc.update("UPDATE mall_customer SET phone=?,update_time=NOW() WHERE id=?", normalizedPhone, customerId);
        jdbc.update("INSERT INTO mall_customer_security_log(customer_id,event_type,old_value_hash,new_value_hash,source_name) VALUES(?, 'CHANGE_PHONE', ?, ?, '小程序/H5')",
                customerId, sha256(oldPhone), sha256(normalizedPhone));
        return firstCustomer("SELECT id,nickname,phone,status FROM mall_customer WHERE id=?", customerId);
    }

    @Transactional
    public SessionContext register(String token, String phone, String password, String refCode)
    {
        String normalizedPhone = validateCredentials(phone, password, true);
        SessionContext guest = resolve(token);
        String lockName = "mall-register-" + normalizedPhone;
        Integer locked = jdbc.queryForObject("SELECT GET_LOCK(?,5)", Integer.class, lockName);
        if (locked == null || locked != 1)
        {
            throw new IllegalArgumentException("注册请求较多，请稍后重试");
        }
        try
        {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mall_customer WHERE phone=? AND status<>'2'", Integer.class, normalizedPhone);
            if (existing != null && existing > 0)
            {
                throw new IllegalArgumentException("该手机号已注册，请直接登录");
            }
            jdbc.update("INSERT INTO mall_customer(nickname,phone,password_hash,points,status) VALUES(?,?,?,0,'0')",
                    "茶友" + normalizedPhone.substring(7), normalizedPhone, SecurityUtils.encryptPassword(password));
            Long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId, refCode);
            return new SessionContext(memberId, true, issuedToken, false);
        }
        finally
        {
            jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
        }
    }

    private String validateCredentials(String phone, String password, boolean registration)
    {
        String normalizedPhone = validatePhone(phone);
        String normalizedPassword = password == null ? "" : password;
        if (normalizedPassword.length() < 8 || normalizedPassword.length() > 32)
        {
            throw new IllegalArgumentException("密码须为8-32位");
        }
        if (registration && !normalizedPassword.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,32}$"))
        {
            throw new IllegalArgumentException("注册密码需同时包含字母和数字");
        }
        return normalizedPhone;
    }

    private String validatePhone(String phone)
    {
        String normalizedPhone = phone == null ? "" : phone.trim();
        if (!PHONE_PATTERN.matcher(normalizedPhone).matches())
        {
            throw new IllegalArgumentException("请输入正确的11位手机号");
        }
        return normalizedPhone;
    }

    @Transactional
    public SessionContext loginWithWechat(String token, String appId, String openId, String unionId, String refCode)
    {
        if (appId == null || appId.trim().isEmpty() || openId == null || openId.trim().isEmpty())
        {
            throw new IllegalArgumentException("微信身份信息无效");
        }
        SessionContext guest = resolve(token);
        List<Map<String, Object>> identities = jdbc.queryForList(
                "SELECT i.customer_id AS customerId,c.status FROM mall_customer_identity i "
              + "JOIN mall_customer c ON c.id=i.customer_id "
              + "WHERE i.provider='WECHAT_MINI_PROGRAM' AND i.app_id=? AND i.open_id=? LIMIT 1",
                appId.trim(), openId.trim());
        Long memberId;
        if (identities.isEmpty())
        {
            throw new IllegalArgumentException("该微信尚未绑定商城账号，请先选择微信注册或绑定已有手机号账号");
        }
        else
        {
            if ("1".equals(String.valueOf(identities.get(0).get("status"))))
            {
                throw new IllegalArgumentException("账号已停用，请联系客服");
            }
            memberId = ((Number) identities.get(0).get("customerId")).longValue();
            if (unionId != null && !unionId.trim().isEmpty())
            {
                jdbc.update("UPDATE mall_customer_identity SET union_id=? WHERE provider='WECHAT_MINI_PROGRAM' AND app_id=? AND open_id=?",
                        unionId.trim(), appId.trim(), openId.trim());
            }
        }
        String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId, refCode);
        return new SessionContext(memberId, true, issuedToken, false);
    }

    public boolean isWechatIdentityBound(String appId, String openId)
    {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mall_customer_identity WHERE provider='WECHAT_MINI_PROGRAM' "
                + "AND app_id=? AND open_id=?", Integer.class, appId.trim(), openId.trim());
        return count != null && count > 0;
    }

    @Transactional
    public String createWechatLoginTicket(String appId, String openId, String unionId, String refCode)
    {
        if (isWechatIdentityBound(appId, openId)) throw new IllegalArgumentException("微信身份已绑定，请重新发起登录");
        String ticket = secureToken();
        jdbc.update("INSERT INTO mall_wechat_login_ticket(ticket_hash,app_id,open_id,union_id,ref_code,expires_time) "
                + "VALUES(?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 10 MINUTE))", sha256(ticket), appId.trim(), openId.trim(),
                unionId == null ? "" : unionId.trim(), refCode == null ? "" : refCode.trim());
        return ticket;
    }

    @Transactional
    public SessionContext registerWithWechatTicket(String token, String ticket, String nickname)
    {
        SessionContext guest = resolve(token);
        Map<String, Object> pending = consumeWechatTicket(ticket);
        String displayName = nickname == null ? "" : nickname.trim();
        if (displayName.isEmpty()) displayName = "微信茶友";
        if (displayName.length() > 64) throw new IllegalArgumentException("昵称不能超过64个字");
        jdbc.update("INSERT INTO mall_customer(nickname,phone,password_hash,points,status) VALUES(?,'','',0,'0')", displayName);
        Long memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        attachWechatIdentity(memberId, pending);
        String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId,
                String.valueOf(pending.get("ref_code")));
        return new SessionContext(memberId, true, issuedToken, false);
    }

    @Transactional
    public SessionContext bindWechatTicketToPhone(String token, String ticket, String phone)
    {
        SessionContext guest = resolve(token);
        String normalizedPhone = validatePhone(phone);
        Map<String, Object> pending = consumeWechatTicket(ticket);
        Map<String, Object> customer = firstCustomer("SELECT id,status FROM mall_customer WHERE phone=? FOR UPDATE", normalizedPhone);
        if (customer == null || !"0".equals(String.valueOf(customer.get("status"))))
            throw new IllegalArgumentException("手机号账号不存在或不可用，请先使用微信注册");
        Long memberId = ((Number) customer.get("id")).longValue();
        attachWechatIdentity(memberId, pending);
        String issuedToken = bindMember(guest.getSessionToken(), guest.getCustomerId(), memberId,
                String.valueOf(pending.get("ref_code")));
        return new SessionContext(memberId, true, issuedToken, false);
    }

    private Map<String, Object> consumeWechatTicket(String ticket)
    {
        String normalized = ticket == null ? "" : ticket.trim();
        if (!TOKEN_PATTERN.matcher(normalized).matches()) throw new IllegalArgumentException("微信绑定凭据无效");
        Map<String, Object> pending = firstCustomer("SELECT * FROM mall_wechat_login_ticket WHERE ticket_hash=? "
                + "AND consumed=0 AND expires_time>NOW() FOR UPDATE", sha256(normalized));
        if (pending == null) throw new IllegalArgumentException("微信绑定凭据已过期或已使用，请重新微信登录");
        int consumed = jdbc.update("UPDATE mall_wechat_login_ticket SET consumed=1,consumed_time=NOW() WHERE id=? AND consumed=0",
                pending.get("id"));
        if (consumed != 1) throw new IllegalArgumentException("微信绑定凭据已使用，请重新微信登录");
        return pending;
    }

    private void attachWechatIdentity(Long customerId, Map<String, Object> pending)
    {
        wechatBindingService.attach(customerId, String.valueOf(pending.get("app_id")),
                String.valueOf(pending.get("open_id")), pending.get("union_id") == null ? "" : String.valueOf(pending.get("union_id")));
    }

    private Map<String, Object> firstCustomer(String sql, Object... args)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String bindMember(String token, Long guestCustomerId, Long memberId, String refCode)
    {
        if (!memberId.equals(guestCustomerId))
        {
            List<Map<String, Object>> guestCart = jdbc.queryForList(
                    "SELECT product_id AS productId,sku_id AS skuId,qty,checked FROM mall_cart WHERE customer_id=?",
                    guestCustomerId);
            for (Map<String, Object> item : guestCart)
            {
                int quantity = intValue(item.get("qty"));
                jdbc.update("INSERT INTO mall_cart(customer_id,product_id,sku_id,qty,checked) VALUES(?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE qty=LEAST(99,qty+?),checked=?",
                        memberId, ((Number) item.get("productId")).longValue(),
                        ((Number) item.get("skuId")).longValue(), quantity,
                        intValue(item.get("checked")), quantity, intValue(item.get("checked")));
            }
            jdbc.update("DELETE FROM mall_cart WHERE customer_id=?", guestCustomerId);
        }
        String issuedToken = secureToken();
        jdbc.update("INSERT IGNORE INTO mall_revoked_session(token_hash) VALUES(?)", tokenHash(token));
        int changed = jdbc.update("UPDATE mall_session SET token_hash=?,customer_id=?,authenticated=1,last_seen_at=NOW(),"
                  + "expires_at=DATE_ADD(NOW(),INTERVAL 30 DAY) WHERE token_hash=?",
                tokenHash(issuedToken), memberId, tokenHash(token));
        if (changed != 1)
        {
            throw new MallAuthenticationException("商城会话已变化，请刷新后重新登录");
        }
        ensureDistributor(memberId, refCode);
        return issuedToken;
    }

    @Transactional
    public SessionContext logout(String token)
    {
        SessionContext current = resolve(token);
        return rotateToGuest(tokenHash(current.getSessionToken()), false);
    }

    public boolean isTestMode()
    {
        return testMode;
    }

    @Transactional
    public void bindReferral(Long customerId, String sceneCode)
    {
        Map<String, Object> current = firstRow("SELECT parent_customer_id AS parentId FROM mall_distributor WHERE customer_id=? FOR UPDATE", customerId);
        if (current != null && current.get("parentId") != null)
            throw new IllegalArgumentException("邀请关系已经绑定，不能重复修改");
        ensureDistributor(customerId, sceneCode);
    }

    private SessionContext rotateToGuest(String currentTokenHash, boolean expired)
    {
        jdbc.update("INSERT IGNORE INTO mall_revoked_session(token_hash) VALUES(?)", currentTokenHash);
        jdbc.update("INSERT INTO mall_customer(nickname,phone,points,status) VALUES('游客','',0,'2')");
        Long guestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String issuedToken = secureToken();
        int changed = jdbc.update("UPDATE mall_session SET token_hash=?,customer_id=?,authenticated=0,last_seen_at=NOW(),"
                  + "expires_at=DATE_ADD(NOW(),INTERVAL 30 DAY) WHERE token_hash=?",
                tokenHash(issuedToken), guestId, currentTokenHash);
        if (changed != 1)
        {
            throw new MallAuthenticationException("商城会话已失效，请刷新页面");
        }
        return new SessionContext(guestId, false, issuedToken, expired);
    }

    private void ensureDistributor(Long customerId, String refCode)
    {
        Map<String, Object> current = firstRow("SELECT parent_customer_id AS parentId FROM mall_distributor WHERE customer_id=?", customerId);
        if (current != null && current.get("parentId") != null)
        {
            return;
        }
        Long parentId = null;
        Long sceneId = null;
        if (refCode != null && !refCode.trim().isEmpty())
        {
            String code = refCode.trim();
            List<Map<String, Object>> scenes = jdbc.queryForList(
                    "SELECT id,inviter_customer_id AS customerId FROM mall_invite_scene WHERE scene_code=? AND status='0' "
                    + "AND (expires_at IS NULL OR expires_at>NOW()) LIMIT 1", code);
            if (!scenes.isEmpty())
            {
                sceneId = ((Number) scenes.get(0).get("id")).longValue();
                parentId = ((Number) scenes.get(0).get("customerId")).longValue();
            }
            else
            {
                List<Map<String, Object>> parents = jdbc.queryForList(
                        "SELECT customer_id AS customerId FROM mall_distributor WHERE invite_code=? AND status='0' LIMIT 1", code);
                if (!parents.isEmpty()) parentId = ((Number) parents.get(0).get("customerId")).longValue();
            }
            if (parentId == null) throw new IllegalArgumentException("邀请场景无效或已过期");
            if (!wechatBindingService.isBound(parentId))
                throw new IllegalArgumentException("邀请人尚未绑定微信或账号已停用，请邀请人绑定后重新分享");
            if (customerId.equals(parentId)) throw new IllegalArgumentException("不能邀请自己");
            Long cursor = parentId;
            for (int depth = 0; cursor != null && depth < 100; depth++)
            {
                if (customerId.equals(cursor)) throw new IllegalArgumentException("邀请关系不能形成循环");
                Map<String, Object> ancestor = firstRow("SELECT parent_customer_id AS parentId FROM mall_distributor WHERE customer_id=?", cursor);
                cursor = ancestor == null || ancestor.get("parentId") == null ? null : ((Number) ancestor.get("parentId")).longValue();
            }
        }
        String inviteCode = "TEA" + Long.toString(customerId, 36).toUpperCase();
        jdbc.update("INSERT INTO mall_distributor(customer_id,parent_customer_id,invite_code,status) VALUES(?,?,?,'0') "
                + "ON DUPLICATE KEY UPDATE parent_customer_id=COALESCE(parent_customer_id,VALUES(parent_customer_id))",
                customerId, parentId, inviteCode);
        if (parentId != null)
        {
            jdbc.update("INSERT INTO mall_invite_record(inviter_customer_id,invitee_customer_id,scene_id,source,status) "
                    + "VALUES(?,?,?,'REGISTER','待下单') ON DUPLICATE KEY UPDATE invitee_customer_id=invitee_customer_id",
                    parentId, customerId, sceneId);
            if (sceneId != null) jdbc.update("UPDATE mall_invite_scene SET use_count=use_count+1 WHERE id=?", sceneId);
        }
    }

    private Map<String, Object> firstRow(String sql, Object... args)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String tokenHash(String token)
    {
        String value = token == null ? "" : token.trim();
        if (!TOKEN_PATTERN.matcher(value).matches())
        {
            throw new MallAuthenticationException("无效的商城会话，请重新登录");
        }
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) hex.append(String.format("%02x", item));
            return hex.toString();
        }
        catch (Exception error)
        {
            throw new IllegalStateException("商城会话初始化失败", error);
        }
    }

    private String sha256(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) hex.append(String.format("%02x", item));
            return hex.toString();
        }
        catch (Exception error)
        {
            throw new IllegalStateException("安全摘要计算失败", error);
        }
    }

    private String secureToken()
    {
        byte[] bytes = new byte[36];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long numeric(String value)
    {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return -1L; }
    }

    private int intValue(Object value)
    {
		if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? 1 : 0;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }
}
