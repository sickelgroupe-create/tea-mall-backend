package com.ruoyi.web.service.mall;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;

/** A single public merchant phone, never a ticket customer's contact information. */
@Service
public class MallSupportContactService {
    static final String CONFIG_KEY = "mall.support.phone";
    private final JdbcTemplate jdbc;
    private final RedisCache cache;

    public MallSupportContactService(JdbcTemplate jdbc, RedisCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public Map<String, String> read() {
        List<String> values = jdbc.queryForList(
            "SELECT config_value FROM sys_config WHERE config_key=?", String.class, CONFIG_KEY);
        if (values.size() > 1) throw new IllegalStateException("客服电话配置重复，请联系管理员处理");
        String phone = values.isEmpty() ? "" : validatePhone(values.get(0));
        return Collections.singletonMap("phone", phone);
    }

    @Transactional
    public Map<String, String> save(String phone, String operator) {
        String value = validatePhone(phone);
        List<Long> ids = jdbc.queryForList(
            "SELECT config_id FROM sys_config WHERE config_key=? FOR UPDATE", Long.class, CONFIG_KEY);
        if (ids.size() != 1) throw new IllegalStateException("客服电话配置未初始化或重复，请检查数据库迁移");
        jdbc.update("UPDATE sys_config SET config_value=?,update_by=?,update_time=NOW() WHERE config_id=?",
            value, operator, ids.get(0));
        // Storefront reads the DB directly; also invalidate the framework parameter cache.
        cache.deleteObject(CacheConstants.SYS_CONFIG_KEY + CONFIG_KEY);
        return Collections.singletonMap("phone", value);
    }

    static String validatePhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (!value.isEmpty() && !value.matches("^\\+?[0-9][0-9 -]{4,28}[0-9]$"))
            throw new IllegalArgumentException("客服电话格式不正确，请填写6至30位号码，可包含空格、短横线及开头的加号");
        if (value.length() > 30 || (!value.isEmpty() && value.replaceAll("\\D", "").length() < 6))
            throw new IllegalArgumentException("客服电话需至少6位数字，最多30个字符");
        return value;
    }
}
