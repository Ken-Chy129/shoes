package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.AuthConfig;
import cn.ken.shoes.model.user.LoginReq;
import cn.ken.shoes.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("user")
@Slf4j
public class UserController {
    
    @Resource
    private UserService userService;
    
    @PostMapping("login")
    public Result<String> login(@RequestBody LoginReq loginReq, HttpServletResponse response) {
        Result<String> result = userService.login(loginReq);
        if (Boolean.TRUE.equals(result.getSuccess())) {
            // HttpOnly cookie 让下载链接(window.open/href)也能带上登录态
            response.addHeader(HttpHeaders.SET_COOKIE, tokenCookie(result.getData(), 86400).toString());
        }
        return result;
    }

    @PostMapping("logout")
    public Result<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, tokenCookie("", 0).toString());
        return Result.buildSuccess();
    }

    private static ResponseCookie tokenCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(AuthConfig.TOKEN_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAgeSeconds).build();
    }

//    @CheckToken
//    @GetMapping("current")
//    public Result<UserResp> getCurrentUser(@RequestHeader("Authorization") String authorization) {
//        try {
//            String token = authorization.replace("Bearer ", "");
//            return userService.getCurrentUser(token);
//        } catch (Exception e) {
//            log.error("Failed to get current user", e);
//            return Result.buildError("获取用户信息失败: " + e.getMessage());
//        }
//    }

    @GetMapping("current")
    public Result<Map<String, String>> current() {
        return Result.buildSuccess(Map.of("access", "admin", "name", "ken"));
    }
}
