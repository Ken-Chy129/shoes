package cn.ken.shoes.service.impl;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.entity.SysUser;
import cn.ken.shoes.model.user.LoginReq;
import cn.ken.shoes.model.user.UserResp;
import cn.ken.shoes.service.UserService;
import cn.ken.shoes.util.JwtUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    
    // TODO: 这里先mock一个用户，后续可以改为数据库查询
    private final SysUser mockUser;
    
    public UserServiceImpl() {
        mockUser = new SysUser();
        mockUser.setId(1L);
        mockUser.setUsername("admin");
        mockUser.setPassword("admin");
        mockUser.setNickname("管理员");
        mockUser.setEnabled(true);
    }
    
    @Override
    public Result<String> login(LoginReq loginReq) {
        // 验证用户名密码
        if (!mockUser.getUsername().equals(loginReq.getUsername()) 
            || !mockUser.getPassword().equals(loginReq.getPassword())) {
            return Result.buildError("用户名或密码错误");
        }
        
        if (!mockUser.getEnabled()) {
            return Result.buildError("用户已被禁用");
        }
        
        // 生成token
        String token = JwtUtil.generateToken(mockUser.getId());
        return Result.buildSuccess(token);
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