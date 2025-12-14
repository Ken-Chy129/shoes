package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.SearchTypeEnum;
import cn.ken.shoes.model.search.SearchTaskRequest;
import cn.ken.shoes.model.search.SearchTaskVO;
import cn.ken.shoes.service.SearchService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@RestController
@RequestMapping("search")
public class SearchController {

    @Resource
    private SearchService searchService;

    /**
     * 创建搜索任务
     */
    @PostMapping("createSearchTask")
    public Result<Long> createSearchTask(@RequestBody SearchTaskRequest request) {
        if (request.getQuery() == null || request.getSorts() == null || request.getPageCount() == null) {
            return Result.buildError("参数不能为空");
        }
        Long taskId = searchService.createSearchTask(request);
        return Result.buildSuccess(taskId);
    }

    /**
     * 查询任务列表
     */
    @GetMapping("getSearchTasks")
    public PageResult<List<SearchTaskVO>> getSearchTasks(String platform, String status, String type, Integer pageIndex, Integer pageSize) {
        return searchService.getSearchTasks(platform, status, type, pageIndex, pageSize);
    }
}
