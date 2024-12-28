package cn.ken.shoes.model.entity;

import lombok.Data;

@Data
public class SysUser {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private Boolean enabled;
} 