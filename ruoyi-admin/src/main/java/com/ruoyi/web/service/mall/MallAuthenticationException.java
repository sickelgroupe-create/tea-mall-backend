package com.ruoyi.web.service.mall;

/** Marks storefront authentication failures so the public API can return HTTP 401. */
public class MallAuthenticationException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public MallAuthenticationException(String message)
    {
        super(message);
    }
}
