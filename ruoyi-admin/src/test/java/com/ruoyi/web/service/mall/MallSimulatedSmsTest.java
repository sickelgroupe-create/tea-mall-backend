package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.redis.RedisCache;

/** Simulation is server gated; a known code is not a bypass of challenge consumption. */
class MallSimulatedSmsTest {
    private MallSmsAuthService service;
    private RedisCache redis;
    private JdbcTemplate jdbc;
    private final Map<String, Object> cache = new HashMap<>();
    private final Map<String, Long> attempts = new HashMap<>();
    private static final String PHONE = "13800000000";

    private static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() throws Exception {
        service = new MallSmsAuthService();
        redis = mock(RedisCache.class);
        jdbc = mock(JdbcTemplate.class);
        RedisTemplate template = mock(RedisTemplate.class);
        ValueOperations operations = mock(ValueOperations.class);
        redis.redisTemplate = template;
        when(template.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), any(), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(i -> cache.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(template.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString())).thenAnswer(i -> {
            java.util.List<String> keys=i.getArgument(1);String code=i.getArgument(2);
            synchronized(cache){
                if(code.equals(cache.get(keys.get(0)))){cache.remove(keys.get(0));attempts.remove(keys.get(1));return 1L;}
                long count=attempts.merge(keys.get(1),1L,Long::sum);
                if(count>=5){cache.remove(keys.get(0));return -2L;}
                return -1L;
            }
        });
        when(operations.increment(anyString())).thenAnswer(i -> attempts.merge(i.getArgument(0), 1L, Long::sum));
        when(redis.hasKey(anyString())).thenAnswer(i -> cache.containsKey(i.getArgument(0)));
        when(redis.getExpire(anyString())).thenReturn(60L);
        when(redis.getCacheObject(anyString())).thenAnswer(i -> cache.get(i.getArgument(0)));
        doAnswer(i -> { cache.put(i.getArgument(0), i.getArgument(1)); return null; })
            .when(redis).setCacheObject(anyString(), any(), anyInt(), eq(TimeUnit.SECONDS));
        when(redis.deleteObject(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            attempts.remove(key);
            return cache.remove(key) != null;
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        field(service, "redis", redis);
        field(service, "jdbc", jdbc);
        field(service, "environment", "test");
        field(service, "testEnabled", true);
        field(service, "fixedTestCode", "123456");
    }

    @Test void challengeReportsSimulationAndConsumesOnce() {
        Map<String, Object> result = service.requestCode(PHONE, "LOGIN");
        assertEquals("ISOLATED_TEST", result.get("channel"));
        assertEquals(true, result.get("fixedTestCode"));
        assertEquals("123456", result.get("testCode"));
        assertEquals(300, result.get("expiresInSeconds"));
        service.verifyAndConsume(PHONE, "LOGIN", "123456");
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "123456"));
    }

    @Test void knownCodeWithoutRequestFails() {
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "123456"));
    }

    @Test void challengeCannotCrossPurpose() {
        service.requestCode(PHONE, "LOGIN");
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "RESET", "123456"));
        service.verifyAndConsume(PHONE, "LOGIN", "123456");
    }

    @Test void challengeCannotCrossPhone() {
        service.requestCode(PHONE, "LOGIN");
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume("13800000001", "LOGIN", "123456"));
        service.verifyAndConsume(PHONE, "LOGIN", "123456");
    }

    @Test void fiveWrongAttemptsInvalidateChallenge() {
        service.requestCode(PHONE, "LOGIN");
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "999999"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "123456"));
    }

    @Test void expiredChallengeFails() {
        service.requestCode(PHONE, "LOGIN");
        cache.keySet().removeIf(k -> k.startsWith("mall:sms:code:"));
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "123456"));
    }

    @Test void repeatedRequestRespectsCooldown() {
        service.requestCode(PHONE, "LOGIN");
        assertThrows(IllegalArgumentException.class, () -> service.requestCode(PHONE, "LOGIN"));
    }

    @Test void productionCannotBeEnabledByClientHeader() throws Exception {
        field(service, "environment", "production");
        assertFalse(service.isTestChannelAvailable());
        assertThrows(IllegalArgumentException.class, () -> service.requestCode(PHONE, "LOGIN", "test"));
        assertThrows(IllegalArgumentException.class, () -> service.verifyAndConsume(PHONE, "LOGIN", "123456", "test"));
        verifyNoInteractions(jdbc);
    }

    @Test void explicitServerSwitchIsRequired() throws Exception {
        field(service, "testEnabled", false);
        assertFalse(service.isTestChannelAvailable());
        assertThrows(IllegalArgumentException.class, () -> service.requestCode(PHONE, "LOGIN"));
    }

    @Test void registrationStillChecksExistingAccount() {
        assertThrows(IllegalArgumentException.class, () -> service.requestCode(PHONE, "REGISTER"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        service.requestCode(PHONE, "REGISTER");
        service.verifyAndConsume(PHONE, "REGISTER", "123456");
    }
}
