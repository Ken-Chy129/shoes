package cn.ken.shoes.controller;

import cn.ken.shoes.common.DunkSortEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.dunk.DunkItem;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.service.DunkService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@RestController
@RequestMapping("dunk")
public class DunkController {

    @Resource
    private DunkService dunkService;

    @GetMapping("search")
    public Result<List<DunkItem>> search(DunkSearchRequest request) {
        if (request == null) {
            return Result.buildError("param is null");
        }
        if (DunkSortEnum.from(request.getSortKey()) == null) {
            return Result.buildError("sort key is invalid");
        }
        return dunkService.search(request);
    }

}
