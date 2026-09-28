package com.ruoyi.web.controller.mall;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.service.mall.MallAuthenticationException;
import com.ruoyi.web.service.mall.MallAuthorizationException;
import com.ruoyi.web.service.mall.MallExchangeCenterService;
import com.ruoyi.web.service.mall.MallSessionService;

@Anonymous
@RestController
@RequestMapping("/mall")
public class MallExchangeCenterController
{
    @Autowired private MallExchangeCenterService service;
    @Autowired private MallSessionService sessions;

    @GetMapping("/exchange-center")
    public AjaxResult overview(@RequestHeader(value="X-Mall-Session",required=false)String token)
    { return AjaxResult.success(service.overview(member(token))); }

    @GetMapping("/distribution/invite-rewards")
    public AjaxResult inviteRewards(@RequestHeader(value="X-Mall-Session",required=false)String token)
    { return AjaxResult.success(service.inviteRewards(member(token))); }

    private Long member(String token){return sessions.requireMember(token).getCustomerId();}

    @ExceptionHandler(MallAuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public AjaxResult authenticationFailure(MallAuthenticationException error)
    { return AjaxResult.error(HttpStatus.UNAUTHORIZED.value(), error.getMessage()); }

    @ExceptionHandler(MallAuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public AjaxResult forbidden(MallAuthorizationException error)
    { return AjaxResult.error(HttpStatus.FORBIDDEN.value(), error.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxResult badRequest(IllegalArgumentException error)
    { return AjaxResult.error(HttpStatus.BAD_REQUEST.value(), error.getMessage()); }
}
