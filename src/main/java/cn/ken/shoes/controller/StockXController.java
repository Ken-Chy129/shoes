package cn.ken.shoes.controller;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.bean.BeanUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.SearchTypeEnum;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.SearchTaskRequest;
import cn.ken.shoes.model.stockx.SearchTaskVO;
import cn.ken.shoes.service.StockXService;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("stockx")
public class StockXController {

    @Resource
    private StockXClient stockXClient;

    @Resource
    private StockXService stockXService;

    @Resource
    private SearchTaskMapper searchTaskMapper;

    @GetMapping("queryItems")
    public Result<List<StockXPriceDO>> queryItems(String brand) {
        return Result.buildSuccess(stockXClient.queryHotItemsByBrandWithPrice(brand, 1));
    }

    @GetMapping("queryItemsV2")
    public Result<List<StockXPriceDO>> queryItemsV2(String brand) {
        return Result.buildSuccess(stockXClient.queryItemWithPrice(brand, 1));
    }

    @GetMapping("searchItems")
    public Result<List<StockXPriceExcel>> searchItems(String query, Integer page, String sortType, String searchType) {
        Pair<Integer, List<StockXPriceExcel>> pair = stockXClient.searchItemWithPrice(query, page, sortType, searchType);
        if (pair == null) {
            return Result.buildError("no result");
        }
        return Result.buildSuccess(pair.getValue());
    }

    @GetMapping("queryPrices")
    public Result<List<StockXPriceDO>> queryPrices(String productId) {
        return Result.buildSuccess(stockXClient.queryPrice(productId));
    }

    @GetMapping("queryBrands")
    public Result<List<BrandDO>> queryBrands() {
        return Result.buildSuccess(stockXClient.queryBrands());
    }

    @GetMapping("querySellingItems")
    public Result<JSONObject> querySellingItems() {
        return Result.buildSuccess(stockXClient.querySellingItems(null, "HM9606-400"));
    }

    @GetMapping("refreshBrand")
    public Result<Boolean> refreshBrand() {
        stockXService.refreshBrand();
        return Result.buildSuccess();
    }

    @GetMapping("refreshPrices")
    public Result<Boolean> refreshPrices() {
        stockXService.refreshPrices();
        return Result.buildSuccess();
    }

    @GetMapping("queryToDealItems")
    public Result<JSONObject> queryToDealItems() {
        return Result.buildSuccess(stockXClient.queryToDeal(null));
    }

    @GetMapping("queryOrder")
    public Result<JSONObject> queryOrder() {
        return Result.buildSuccess(stockXClient.queryOrders(null));
    }

    @PostMapping("extendAllItems")
    public Result<Void> extendAllItems() {
        Thread.startVirtualThread(() -> stockXService.extendAllItems());
        return Result.buildSuccess();
    }

    /**
     * 创建搜索任务
     */
    @PostMapping("createSearchTask")
    public Result<Long> createSearchTask(@RequestBody SearchTaskRequest request) {
        if (request.getQuery() == null || request.getSorts() == null || request.getPageCount() == null) {
            return Result.buildError("参数不能为空");
        }
        if (SearchTypeEnum.from(request.getSearchType()) == null) {
            return Result.buildError("搜索类型不存在");
        }
        Long taskId = stockXService.createSearchTask(request.getQuery(), request.getSorts(), request.getPageCount(), request.getSearchType());
        return Result.buildSuccess(taskId);
    }

    /**
     * 查询任务列表
     */
    @GetMapping("getSearchTasks")
    public PageResult<List<SearchTaskVO>> getSearchTasks(String status, Integer pageIndex, Integer pageSize) {
        Long count = searchTaskMapper.count(status);
        if (count <= 0) {
            return PageResult.buildSuccess();
        }
        if (pageIndex == null) {
            pageIndex = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        int startIndex = (pageIndex - 1) * pageSize;

        List<SearchTaskDO> taskList = searchTaskMapper.selectByCondition(status, startIndex, pageSize);
        List<SearchTaskVO> voList = BeanUtil.copyToList(taskList, SearchTaskVO.class);

        PageResult<List<SearchTaskVO>> result = PageResult.buildSuccess(voList);
        result.setTotal(count);
        return result;
    }
}
