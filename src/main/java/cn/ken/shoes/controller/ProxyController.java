package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.ProxyService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("proxy")
public class ProxyController {

    @Resource
    private ProxyService proxyService;

    @GetMapping("getAll")
    public Result<JSONObject> getAllProxies() {
        return proxyService.getAllProxies();
    }

    @GetMapping("changeProxy")
    public Result<JSONObject> changeProxy(String name) {
        return proxyService.changeProxy(name);
    }
}
