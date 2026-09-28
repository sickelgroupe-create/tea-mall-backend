package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class MallSmsAuthServiceTest
{
    @Test
    void productionClientHeaderCannotEnableFixedCode() throws Exception
    {
        MallSmsAuthService service = service("production", false, "123456");
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(
                "13800000000", "LOGIN", "123456", "attacker-controlled-channel"));
    }

    @Test
    void productionFixedCodeCannotResetPassword() throws Exception
    {
        MallSmsAuthService active = service("production", false, "123456");
        assertThrows(IllegalArgumentException.class, () -> active.verifyAndConsume(
                "13800000000", "RESET", "123456", "wechat-devtest-1.0.14"));
    }

    private MallSmsAuthService service(String environment, boolean testEnabled,
            String code) throws Exception
    {
        MallSmsAuthService value = new MallSmsAuthService();
        set(value, "environment", environment);
        set(value, "testEnabled", testEnabled);
        set(value, "fixedTestCode", code);
        return value;
    }

    private void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
