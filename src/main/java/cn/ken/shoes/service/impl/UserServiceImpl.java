package cn.ken.shoes.service.impl;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.SysUser;
import cn.ken.shoes.model.user.LoginReq;
import cn.ken.shoes.model.user.UserResp;
import cn.ken.shoes.service.UserService;
import cn.ken.shoes.util.JwtUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class UserServiceImpl implements UserService {

    // 单用户后台：账号密码来自配置 auth.username / auth.password，未配置密码时禁止登录
    private final SysUser mockUser;

    public UserServiceImpl(@Value("${auth.username:admin}") String username,
                           @Value("${auth.password:}") String password) {
        mockUser = new SysUser();
        mockUser.setId(1L);
        mockUser.setUsername(username);
        mockUser.setPassword(password);
        mockUser.setNickname("管理员");
        mockUser.setEnabled(true);
    }

    @Override
    public Result<String> login(LoginReq loginReq) {
        if (mockUser.getPassword() == null || mockUser.getPassword().isBlank()) {
            return Result.buildError("服务端未配置登录密码(auth.password)");
        }
        if (!mockUser.getUsername().equals(loginReq.getUsername())
            || !constantTimeEquals(mockUser.getPassword(), loginReq.getPassword())) {
            return Result.buildError("用户名或密码错误");
        }
        
        if (!mockUser.getEnabled()) {
            return Result.buildError("用户已被禁用");
        }
        
        // 生成token
        String token = JwtUtil.generateToken(mockUser.getId());
        return Result.buildSuccess(token);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public Result<UserResp> getCurrentUser(String token) {
        try {
            // token已经在切面中验证过了，这里直接获取用户ID
            Long userId = JwtUtil.getUserIdFromToken(token);
            
            if (!mockUser.getId().equals(userId)) {
                return Result.buildError("用户不存在");
            }
            
            UserResp userResp = new UserResp();
            BeanUtils.copyProperties(mockUser, userResp);
            return Result.buildSuccess(userResp);
        } catch (Exception e) {
            return Result.buildError("获取用户信息失败: " + e.getMessage());
        }
    }
} 
