package cn.ken.shoes.service;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.user.LoginReq;
import cn.ken.shoes.model.user.UserResp;

public interface UserService {
    Result<String> login(LoginReq loginReq);
    Result<UserResp> getCurrentUser(String token);
} 