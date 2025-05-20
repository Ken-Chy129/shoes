package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.MustCrawlMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Ken-Chy129
 * @date 2025/5/20
 */
@RestController
@RequestMapping("tool")
public class ToolController {

    @Resource
    private CustomModelMapper customModelMapper;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    @GetMapping("clearNoPriceModel")
    public Result<Integer> clearNoPriceModel(int type) {
        return Result.buildSuccess(customModelMapper.clearByType(type));
    }

    @GetMapping("clearMustCrawlModel")
    public Result<Integer> clearMustCrawlModel(String platform) {
        return Result.buildSuccess(mustCrawlMapper.deleteByPlatform(platform));
    }
}
