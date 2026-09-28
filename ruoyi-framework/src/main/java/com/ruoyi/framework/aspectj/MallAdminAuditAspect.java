package com.ruoyi.framework.aspectj;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessStatus;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.domain.SysOperLog;

/**
 * Records every state-changing mall administration request, including generic
 * controllers whose business screens do not carry individual {@code @Log}
 * annotations. Request bodies are deliberately not persisted here because
 * mall payloads may contain identity, contact or account data.
 */
@Aspect
@Component
public class MallAdminAuditAspect
{
    @Around("execution(public * com.ruoyi.web.controller.mall..*AdminController.*(..))")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable
    {
        String httpMethod = ServletUtils.getRequest().getMethod();
        if (!("POST".equals(httpMethod) || "PUT".equals(httpMethod) || "DELETE".equals(httpMethod)))
            return joinPoint.proceed();

        long started = System.currentTimeMillis();
        Throwable failure = null;
        try
        {
            return joinPoint.proceed();
        }
        catch (Throwable throwable)
        {
            failure = throwable;
            throw throwable;
        }
        finally
        {
            SysOperLog log = new SysOperLog();
            log.setTitle("商城管理");
            log.setBusinessType("POST".equals(httpMethod) ? BusinessType.INSERT.ordinal()
                    : "DELETE".equals(httpMethod) ? BusinessType.DELETE.ordinal() : BusinessType.UPDATE.ordinal());
            log.setStatus(failure == null ? BusinessStatus.SUCCESS.ordinal() : BusinessStatus.FAIL.ordinal());
            log.setOperIp(IpUtils.getIpAddr());
            log.setOperUrl(StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255));
            log.setRequestMethod(httpMethod);
            log.setMethod(joinPoint.getTarget().getClass().getName() + "." + joinPoint.getSignature().getName() + "()");
            log.setCostTime(System.currentTimeMillis() - started);
            if (failure != null) log.setErrorMsg(StringUtils.substring(failure.getMessage(), 0, 2000));
            try
            {
                LoginUser loginUser = SecurityUtils.getLoginUser();
                if (loginUser != null)
                {
                    log.setOperName(loginUser.getUsername());
                    SysUser user = loginUser.getUser();
                    if (user != null && user.getDept() != null) log.setDeptName(user.getDept().getDeptName());
                }
            }
            catch (Exception ignored)
            {
                // The authorization layer still rejects anonymous access; an
                // absent principal must not suppress the failure audit record.
            }
            AsyncManager.me().execute(AsyncFactory.recordOper(log));
        }
    }
}
