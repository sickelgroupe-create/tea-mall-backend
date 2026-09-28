package com.ruoyi.web.service.mall;

/** A logged-in mall customer attempted an operation outside its authorization. */
public class MallAuthorizationException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public MallAuthorizationException(String message)
    {
        super(message);
    }
}
