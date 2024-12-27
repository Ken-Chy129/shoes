package cn.ken.shoes.aspect;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.util.JwtUtil;
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
public class TokenAspect {
    
    @Around("@annotation(cn.ken.shoes.annotation.CheckToken)")
    public Object checkToken(ProceedingJoinPoint point) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String authorization = request.getHeader("Authorization");
        
        // 检查是否携带token
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            log.warn("No token found or invalid token format");
            return Result.buildError("请先登录");
        }
        
        // 提取token
        String token = authorization.replace("Bearer ", "");
        
        try {
            // 验证token
            Long userId = JwtUtil.getUserIdFromToken(token);
            log.info("Token verified for user: {}", userId);
            // 继续执行原方法
            return point.proceed();
        } catch (Exception e) {
            log.error("Token verification failed", e);
            return Result.buildError("token无效，请重新登录");
        }
    }
} 