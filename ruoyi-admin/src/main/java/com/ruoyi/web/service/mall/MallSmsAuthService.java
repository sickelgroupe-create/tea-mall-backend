package com.ruoyi.web.service.mall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.redis.RedisCache;

/**
 * Environment-gated SMS authentication helper.
 *
 * The bundled channel exists only for local/isolated acceptance. Production
 * defaults to disabled and never contains a fixed verification code.
 */
@Service
public class MallSmsAuthService
{
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_TTL_SECONDS = 300;
    private static final int COOLDOWN_SECONDS = 60;
    // Compare, attempts and consume in ONE Redis operation, including across Java instances.
    static final String CONSUME_LUA = "local stored=redis.call('GET',KEYS[1]); "
            + "if stored and stored==ARGV[1] then redis.call('DEL',KEYS[1],KEYS[2]); return 1; end; "
            + "local attempts=redis.call('INCR',KEYS[2]); if attempts==1 then redis.call('EXPIRE',KEYS[2],300); end; "
            + "if attempts>=5 then redis.call('DEL',KEYS[1]); return -2; end; return -1;";
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<Long>(CONSUME_LUA, Long.class);

    @Autowired
    private RedisCache redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired(required = false)
    private MallSmsProvider smsProvider;

    @Value("${mall.environment:production}")
    private String environment;

    @Value("${mall.sms.test-enabled:false}")
    private boolean testEnabled;

    @Value("${mall.sms.fixed-test-code:}")
    private String fixedTestCode;

    public Map<String, Object> requestCode(String phone, String purpose)
    {
        return requestCode(phone, purpose, null);
    }

    public Map<String, Object> requestCode(String phone, String purpose, String buildChannel)
    {
        String normalizedPhone = validPhone(phone);
        String normalizedPurpose = validPurpose(purpose);
        boolean isolatedTestChannel = isTestChannelAvailable();
        if (!isolatedTestChannel && (smsProvider == null || !smsProvider.isConfigured()))
        {
            throw new IllegalArgumentException("短信验证码服务尚未配置，请使用密码登录");
        }
        Integer accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_customer WHERE phone=? AND status<>'2'",
                Integer.class, normalizedPhone);
        boolean accountExists = accountCount != null && accountCount > 0;
        if (("LOGIN".equals(normalizedPurpose) || "RESET".equals(normalizedPurpose)
                || "BIND_WECHAT".equals(normalizedPurpose)) && !accountExists)
        {
            throw new IllegalArgumentException("该手机号尚未注册，请先完成手机号注册");
        }
        if ("REGISTER".equals(normalizedPurpose) && accountExists)
        {
            throw new IllegalArgumentException("该手机号已注册，请直接登录");
        }
        String fingerprint = sha256(normalizedPhone);
        String cooldownKey = "mall:sms:cooldown:" + normalizedPurpose + ":" + fingerprint;
        if (!Boolean.TRUE.equals(redis.redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN_SECONDS, TimeUnit.SECONDS)))
        {
            long retryAfter = Math.max(1, redis.getExpire(cooldownKey));
            throw new IllegalArgumentException("验证码请求过于频繁，请" + retryAfter + "秒后重试");
        }
        String code = isolatedTestChannel ? normalizedFixedCode() : "";
        if (code.isEmpty()) code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        redis.setCacheObject(codeKey(fingerprint, normalizedPurpose), code,
                CODE_TTL_SECONDS, TimeUnit.SECONDS);
        redis.deleteObject(attemptKey(fingerprint, normalizedPurpose));
        if (!isolatedTestChannel)
        {
            try { smsProvider.sendVerificationCode(normalizedPhone, normalizedPurpose, code, CODE_TTL_SECONDS); }
            catch (RuntimeException error) {
                redis.deleteObject(codeKey(fingerprint, normalizedPurpose));
                redis.deleteObject(cooldownKey);
                throw new IllegalArgumentException("短信发送失败，请稍后重试");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
		result.put("channel", isolatedTestChannel ? "ISOLATED_TEST" : "SMS_PROVIDER");
		result.put("expiresInSeconds", CODE_TTL_SECONDS);
		result.put("retryAfterSeconds", COOLDOWN_SECONDS);
		if (isolatedTestChannel)
        {
            result.put("fixedTestCode", !normalizedFixedCode().isEmpty());
            result.put("testCode", code);
        }
        return result;
    }

    public void verifyAndConsume(String phone, String purpose, String code)
    {
        verifyAndConsume(phone, purpose, code, null);
    }

    public void verifyAndConsume(String phone, String purpose, String code, String buildChannel)
    {
        String normalizedPhone = validPhone(phone);
        String normalizedPurpose = validPurpose(purpose);
        String normalizedCode = code == null ? "" : code.trim();
        if (!CODE.matcher(normalizedCode).matches())
        {
            throw new IllegalArgumentException("请输入6位验证码");
        }
        if (!isTestChannelAvailable() && !normalizedFixedCode().isEmpty()
                && constantTimeEquals(normalizedFixedCode(), normalizedCode))
        {
            throw new IllegalArgumentException("测试验证码在当前环境不可用");
        }
        String fingerprint = sha256(normalizedPhone);
        Long result = (Long) redis.redisTemplate.execute(CONSUME,
                java.util.Arrays.asList(codeKey(fingerprint, normalizedPurpose), attemptKey(fingerprint, normalizedPurpose)), normalizedCode);
        if (Long.valueOf(-2).equals(result)) throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        if (!Long.valueOf(1).equals(result)) throw new IllegalArgumentException("验证码错误或已过期");
    }

    public boolean isTestChannelAvailable()
    {
        String value = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        return testEnabled && ("local".equals(value) || "dev".equals(value)
                || "development".equals(value) || "test".equals(value)
                || "testing".equals(value));
    }

	private String normalizedFixedCode()
	{
		String value = fixedTestCode == null ? "" : fixedTestCode.trim();
		return CODE.matcher(value).matches() ? value : "";
	}

    private String validPhone(String phone)
    {
        String value = phone == null ? "" : phone.trim();
        if (!PHONE.matcher(value).matches())
        {
            throw new IllegalArgumentException("请输入正确的11位手机号");
        }
        return value;
    }

    private String validPurpose(String purpose)
    {
        String value = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        if (!"LOGIN".equals(value) && !"REGISTER".equals(value)
                && !"RESET".equals(value) && !"CHANGE_PHONE".equals(value)
                && !"BIND_WECHAT".equals(value))
        {
            throw new IllegalArgumentException("验证码用途无效");
        }
        return value;
    }

    private String codeKey(String fingerprint, String purpose)
    {
        return "mall:sms:code:" + purpose + ":" + fingerprint;
    }

    private String attemptKey(String fingerprint, String purpose)
    {
        return "mall:sms:attempts:" + purpose + ":" + fingerprint;
    }

    private String sha256(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : digest)
            {
                value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return value.toString();
        }
        catch (Exception error)
        {
            throw new IllegalStateException("验证码安全摘要初始化失败", error);
        }
    }

    private boolean constantTimeEquals(String expected, String actual)
    {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
