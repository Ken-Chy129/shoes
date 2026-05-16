package cn.ken.shoes.aspect;

import cn.ken.shoes.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Slf4j
public class ApiTokenAspect {

    @Value("${api.token}")
    private String apiToken;

    @Around("@annotation(cn.ken.shoes.annotation.CheckApiToken)")
    public Object checkApiToken(ProceedingJoinPoint point) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String token = request.getHeader("api-token");
        if (token == null || !apiToken.equals(token)) {
            log.warn("Invalid api-token from {}", request.getRemoteAddr());
            return Result.buildError("无效的api-token");
        }
        return point.proceed();
    }
}
