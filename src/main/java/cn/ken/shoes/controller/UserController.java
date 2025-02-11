package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.CheckToken;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.user.LoginReq;
import cn.ken.shoes.model.user.UserResp;
import cn.ken.shoes.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("user")
@Slf4j
public class UserController {
    
    @Resource
    private UserService userService;
    
    @PostMapping("login")
    public Result<String> login(@RequestBody LoginReq loginReq) {
        return userService.login(loginReq);
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
        return Result.buildSuccess(Map.of("access", "", "name", "ken"));
    }
}
