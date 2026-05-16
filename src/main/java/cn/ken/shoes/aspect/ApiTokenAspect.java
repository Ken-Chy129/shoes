package cn.ken.shoes.aspect;

import cn.ken.shoes.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Slf4j
public class ApiTokenAspect {

    private static final String API_TOKEN = "sk-shoes-9f8a7b6c5d4e3f2a1b0c";

    @Around("@annotation(cn.ken.shoes.annotation.CheckApiToken)")
    public Object checkApiToken(ProceedingJoinPoint point) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String token = request.getHeader("api-token");
        if (token == null || !API_TOKEN.equals(token)) {
            log.warn("Invalid api-token from {}", request.getRemoteAddr());
            return Result.buildError("无效的api-token");
        }
        return point.proceed();
    }
}
