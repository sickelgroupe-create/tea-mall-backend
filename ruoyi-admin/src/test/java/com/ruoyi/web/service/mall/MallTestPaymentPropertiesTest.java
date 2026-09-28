package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class MallTestPaymentPropertiesTest
{
    @Test
    void productionRequiresServerSidePhoneWhitelist() throws Exception
    {
        MallTestPaymentProperties properties = properties("production", true, true, "13800000000", "");
        assertTrue(properties.isAvailableFor("13800000000", null));
        assertFalse(properties.isAvailableFor("13900000000", null));
    }

    @Test
    void clientBuildHeaderCannotEnableProductionTestPayment() throws Exception
    {
        MallTestPaymentProperties production = properties("production", true, false, "", "91");
        assertFalse(production.isAvailableFor("13800000000", "attacker-controlled-channel"));
        assertFalse(production.isAvailableFor(91L, "", "attacker-controlled-channel"));
        MallTestPaymentProperties disabled = properties("testing", false, false, "", "91");
        assertFalse(disabled.isAvailableFor("13800000000", "wechat-devtest-1.0.6"));
    }

    @Test
    void isolatedTestEnvironmentAllowsAuthenticatedTestUsers() throws Exception
    {
        MallTestPaymentProperties properties = properties("testing", true, false, "", "");
        assertTrue(properties.isAvailableFor("13800000000", null));
    }

    @Test
    void productionWechatAccountCanUseServerSideCustomerIdWhitelist() throws Exception
    {
        MallTestPaymentProperties properties = properties("production", true, true, "", "91, 92");
        assertTrue(properties.isAvailableFor(91L, "", null));
        assertFalse(properties.isAvailableFor(93L, "", null));
        assertFalse(properties.isAvailableFor(null, "", null));
    }

    @Test
    void allCustomerModeCoversExistingAndFutureCustomersWithoutWhitelists() throws Exception
    {
        MallTestPaymentProperties value = properties("production", true, false, "", "");
        set(value, "allCustomersEnabled", true);
        assertTrue(value.isAvailableFor(4L, "13800000000", null));
        assertTrue(value.isAvailableFor(3092L, "", null));
        assertTrue(value.isAvailableFor(999999L, null, "any-client-channel"));
        value.requireAvailableFor(999999L, null, null);
    }

    @Test
    void allCustomerModeStillRequiresMasterSwitch() throws Exception
    {
        MallTestPaymentProperties value = properties("production", false, true, "13800000000", "3092");
        set(value, "allCustomersEnabled", true);
        assertFalse(value.isAvailableFor(3092L, "13800000000", null));
        assertThrows(MallAuthorizationException.class, () -> value.requireAvailableFor(3092L, "", null));
    }

    @Test
    void allCustomerModeDoesNotAuthorizeAnonymousOrInvalidIdentities() throws Exception
    {
        MallTestPaymentProperties value = properties("production", true, false, "", "");
        set(value, "allCustomersEnabled", true);
        assertFalse(value.isAvailableFor(null, "", null));
        assertFalse(value.isAvailableFor(0L, "", null));
        assertFalse(value.isAvailableFor(-1L, "", null));
        assertFalse(value.isAvailableFor("13800000000", "all-customers-enabled"));
        assertFalse(value.isAvailable());
    }

    @Test
    void disablingAllCustomerModeRestoresOnlyExplicitWhitelist() throws Exception
    {
        MallTestPaymentProperties value = properties("production", true, true, "", "3092");
        set(value, "allCustomersEnabled", true);
        assertTrue(value.isAvailableFor(999999L, "", null));
        set(value, "allCustomersEnabled", false);
        assertFalse(value.isAvailableFor(999999L, "", null));
        assertTrue(value.isAvailableFor(3092L, "", null));
    }

    @Test
    void unknownEnvironmentFailsClosedEvenWhenAllCustomerModeEnabled() throws Exception
    {
        MallTestPaymentProperties value = properties("unknown", true, true, "", "3092");
        set(value, "allCustomersEnabled", true);
        assertFalse(value.isAvailableFor(3092L, "", null));
    }

    private MallTestPaymentProperties properties(String environment, boolean enabled,
            boolean whitelistEnabled, String phones, String customerIds) throws Exception
    {
        MallTestPaymentProperties value = new MallTestPaymentProperties();
        set(value, "environment", environment);
        set(value, "enabled", enabled);
        set(value, "productionWhitelistEnabled", whitelistEnabled);
        set(value, "allowedPhones", phones);
        set(value, "allowedCustomerIds", customerIds);
        return value;
    }

    private void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
