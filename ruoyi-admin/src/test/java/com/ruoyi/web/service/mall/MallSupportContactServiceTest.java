package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.web.controller.mall.MallAdminController;

class MallSupportContactServiceTest {
    @Test void acceptsMobileLandlineAndEmptyButRejectsUriAndControlCharacters() {
        for (String value : Arrays.asList("", "13800000000", "010-12345678", "400-123-4567", "+86 13800000000"))
            assertEquals(value, MallSupportContactService.validatePhone(value));
        for (String value : Arrays.asList("tel:13800000000", "1-----2", "123", "10086;alert(1)", "123\n456789", "javascript:x", "*123456#"))
            assertThrows(IllegalArgumentException.class, () -> MallSupportContactService.validatePhone(value));
    }

    @Test void readDoesNotInventPhoneOrExposeOtherConfiguration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisCache cache = mock(RedisCache.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("mall.support.phone"))).thenReturn(Collections.emptyList());
        assertEquals(Collections.singletonMap("phone", ""), new MallSupportContactService(jdbc, cache).read());
        verifyNoInteractions(cache);
    }

    @Test void savesConfiguredValueAndSupportsClearingWithoutCreatingRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisCache cache = mock(RedisCache.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("mall.support.phone"))).thenReturn(Collections.singletonList(100L));
        MallSupportContactService service = new MallSupportContactService(jdbc, cache);
        assertEquals("010-12345678", service.save(" 010-12345678 ", "operator").get("phone"));
        assertEquals("", service.save("", "operator").get("phone"));
        verify(jdbc).update(contains("UPDATE sys_config"), eq(""), eq("operator"), eq(100L));
        verify(cache, times(2)).deleteObject(anyString());
    }

    @Test void missingOrDuplicateRowFailsRatherThanOverwritingAmbiguousData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisCache cache = mock(RedisCache.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("mall.support.phone")))
            .thenReturn(Collections.emptyList()).thenReturn(Arrays.asList(100L, 101L));
        MallSupportContactService service = new MallSupportContactService(jdbc, cache);
        assertThrows(IllegalStateException.class, () -> service.save("13800000000", "operator"));
        assertThrows(IllegalStateException.class, () -> service.save("13800000000", "operator"));
        verifyNoInteractions(cache);
    }

    @Test void readAndWriteRequireSeparateTicketPermissions() throws Exception {
        assertEquals("@ss.hasPermi('mall:ticket:list')", MallAdminController.class.getMethod("supportContact").getAnnotation(PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('mall:ticket:edit')", MallAdminController.class.getMethod("saveSupportContact", Map.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test void partnerAddressErrorUsesChineseWithoutWeakeningMinimumLength() {
        Map<String,Object> form = new HashMap<>();
        form.put("address", "街道");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            ReflectionTestUtils.invokeMethod(new MallPartnerContentService(), "required", form, "address", 3, 255));
        assertEquals("详细地址长度需为3-255个字符", error.getMessage());
        form.put("address", "街道一号");
        assertEquals("街道一号", ReflectionTestUtils.invokeMethod(new MallPartnerContentService(), "required", form, "address", 3, 255));
    }
}
