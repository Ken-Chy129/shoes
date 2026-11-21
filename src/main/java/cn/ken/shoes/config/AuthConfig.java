package cn.ken.shoes.config;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.util.JwtUtil;
import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 全局登录校验：除白名单外，所有接口需要有效 JWT（Authorization: Bearer 或 cookie）或 api-token。
 * 注意后端路径不带 /api 前缀（nginx 已去掉）。
 */
@Configuration
@Slf4j
public class AuthConfig implements WebMvcConfigurer, HandlerInterceptor {

    public static final String TOKEN_COOKIE = "shoes_token";

    /** 第三方回调 / 推送及登录入口，不能要求登录态 */
    static final String[] PUBLIC_PATHS = {
            "/user/login", "/user/logout",
            "/ebay/notifications/**",
            "/ebay/oauth/callback", "/ebay/oauth/declined",
            "/poison/callback",
            "/error"
    };

    @Value("${api.token:}")
    private String apiToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**").excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || hasValidApiToken(request) || hasValidJwt(request)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(Result.buildError("请先登录")));
        return false;
    }

    private boolean hasValidApiToken(HttpServletRequest request) {
        String token = request.getHeader("api-token");
        if (token == null) {
            token = request.getParameter("api-token");
        }
        return token != null && apiToken != null && !apiToken.isBlank()
                && MessageDigest.isEqual(apiToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasValidJwt(HttpServletRequest request) {
        String token = null;
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7).trim();
        }
        if ((token == null || token.isEmpty()) && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (TOKEN_COOKIE.equals(cookie.getName())) {
                    token = cookie.getValue();
                }
            }
        }
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            JwtUtil.getUserIdFromToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
