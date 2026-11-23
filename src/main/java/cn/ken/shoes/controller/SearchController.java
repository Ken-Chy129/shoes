package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.search.SearchTaskRequest;
import cn.ken.shoes.model.search.SearchTaskVO;
import cn.ken.shoes.service.SearchService;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * 通过Excel（货号列）创建货号搜索任务，货号去重后合并为一个任务
     */
    @PostMapping("createModelNoSearchTaskByExcel")
    public Result<Long> createModelNoSearchTaskByExcel(@RequestParam("file") MultipartFile file,
                                                       @RequestParam("accountName") String accountName) throws IOException {
        if (StrUtil.isBlank(accountName)) {
            return Result.buildError("账号不能为空");
        }
        List<ModelNoSearchExcel> rows = EasyExcel.read(file.getInputStream()).head(ModelNoSearchExcel.class).sheet().doReadSync();
        Set<String> modelNos = new LinkedHashSet<>();
        for (ModelNoSearchExcel row : rows) {
            if (StrUtil.isNotBlank(row.getModelNo())) {
                modelNos.add(row.getModelNo().trim());
            }
        }
        if (modelNos.isEmpty()) {
            return Result.buildError("Excel中未找到有效货号（需要“货号”列）");
        }
        SearchTaskRequest request = new SearchTaskRequest();
        request.setPlatform("stockx");
        request.setQuery(String.join("\n", modelNos));
        request.setSorts("featured");
        request.setPageCount(1);
        request.setType("modelNo");
        request.setAccountName(accountName);
        return Result.buildSuccess(searchService.createSearchTask(request));
    }

    /**
     * 取消搜索任务
     */
    @PostMapping("cancelSearchTask")
    public Result<Void> cancelSearchTask(@RequestParam Long taskId) {
        searchService.cancelSearchTask(taskId);
        return Result.buildSuccess();
    }

    /**
     * 查询任务列表
     */
    @GetMapping("getSearchTasks")
    public PageResult<List<SearchTaskVO>> getSearchTasks(String platform, String status, String type, Integer pageIndex, Integer pageSize) {
        return searchService.getSearchTasks(platform, status, type, pageIndex, pageSize);
    }
}
