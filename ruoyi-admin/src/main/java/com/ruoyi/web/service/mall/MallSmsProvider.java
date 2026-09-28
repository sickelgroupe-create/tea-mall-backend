package com.ruoyi.web.service.mall;

/**
 * Production SMS delivery boundary. A deployment must provide a Spring bean
 * backed by its contracted SMS vendor; secrets stay in server-side settings.
 */
public interface MallSmsProvider
{
    boolean isConfigured();

    void sendVerificationCode(String phone, String purpose, String code, int expiresInSeconds);
}
