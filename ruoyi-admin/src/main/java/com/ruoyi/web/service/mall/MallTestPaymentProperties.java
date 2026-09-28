package com.ruoyi.web.service.mall;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Environment gate for the explicitly labelled test-payment facility. */
@Component
public class MallTestPaymentProperties
{
    private static final Set<String> ALLOWED_ENVIRONMENTS = new HashSet<String>(
            Arrays.asList("local", "development", "dev", "test", "testing"));

    @Value("${mall.environment:production}")
    private String environment;

    @Value("${mall.test-payment.enabled:false}")
    private boolean enabled;

    @Value("${mall.test-payment.all-customers-enabled:false}")
    private boolean allCustomersEnabled;

    @Value("${mall.test-payment.production-whitelist-enabled:false}")
    private boolean productionWhitelistEnabled;

    @Value("${mall.test-payment.allowed-phones:}")
    private String allowedPhones;

    @Value("${mall.test-payment.allowed-customer-ids:}")
    private String allowedCustomerIds;

    public boolean isAvailable()
    {
        return enabled && ALLOWED_ENVIRONMENTS.contains(normalizedEnvironment());
    }

    /**
     * The pre-launch all-customer mode is explicitly controlled by the server.
     * Otherwise production keeps its existing account whitelist.
     */
    public boolean isAvailableFor(String phone)
	{
		return isAvailableFor(null, phone, null);
	}

	public boolean isAvailableFor(String phone, String buildChannel)
	{
		return isAvailableFor(null, phone, buildChannel);
	}

	public boolean isAvailableFor(Long customerId, String phone, String buildChannel)
    {
        if (!enabled) return false;
        if (ALLOWED_ENVIRONMENTS.contains(normalizedEnvironment())) return true;
		if (!"production".equals(normalizedEnvironment())) return false;
        // Customer IDs must come from the authenticated session, never a client
        // parameter. This also covers future registrations and phone-less WeChat
        // accounts without rewriting account records or extending a whitelist.
        if (allCustomersEnabled && customerId != null && customerId > 0) return true;
		boolean whitelistedPhone = productionWhitelistEnabled
				&& normalizedAllowedPhones().contains(normalizePhone(phone));
		boolean whitelistedCustomer = productionWhitelistEnabled && customerId != null
				&& normalizedAllowedCustomerIds().contains(String.valueOf(customerId));
		// Client-provided headers never enable payment capability. When the global
		// pre-launch mode is off, preserve the server-managed account whitelist.
		return whitelistedPhone || whitelistedCustomer;
    }

    public void requireAvailable()
    {
        if (!isAvailable())
        {
            throw new IllegalArgumentException("测试支付仅可在开发或隔离测试环境使用，当前环境未启用");
        }
    }

    public void requireAvailableFor(String phone)
	{
		requireAvailableFor(phone, null);
	}

	public void requireAvailableFor(String phone, String buildChannel)
	{
		requireAvailableFor(null, phone, buildChannel);
	}

	public void requireAvailableFor(Long customerId, String phone, String buildChannel)
    {
		if (!isAvailableFor(customerId, phone, buildChannel))
        {
			throw new MallAuthorizationException("当前账号未启用模拟支付");
        }
    }

    public String getEnvironment()
    {
        return normalizedEnvironment();
    }

    private String normalizedEnvironment()
    {
        return environment == null ? "production" : environment.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizedAllowedPhones()
    {
        return Arrays.stream(allowedPhones == null ? new String[0] : allowedPhones.split(","))
                .map(this::normalizePhone)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    private Set<String> normalizedAllowedCustomerIds()
    {
        return Arrays.stream(allowedCustomerIds == null ? new String[0] : allowedCustomerIds.split(","))
                .map(String::trim)
                .filter(value -> value.matches("[1-9][0-9]*"))
                .collect(Collectors.toSet());
    }

    private String normalizePhone(String phone)
    {
        return phone == null ? "" : phone.replaceAll("\\s+", "").trim();
    }
}
