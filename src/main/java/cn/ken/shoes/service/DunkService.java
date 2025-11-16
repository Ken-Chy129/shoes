package cn.ken.shoes.service;

import cn.ken.shoes.client.DunkClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.dunk.DunkItem;
import cn.ken.shoes.model.dunk.DunkSalesHistory;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.model.excel.DunkPriceExcel;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Service
public class DunkService {

    @Resource
    private DunkClient dunkClient;

    public Result<List<DunkItem>> search(DunkSearchRequest request) {
        return Result.buildSuccess(dunkClient.search(request).getValue());
    }

    public Result<List<DunkPriceExcel>> queryPrice(String modelNo) {
        return Result.buildSuccess(dunkClient.queryPrice(modelNo));
    }

    public Result<List<DunkSalesHistory>> querySalesHistory(String modelNo, Integer sizeId) {
        return Result.buildSuccess(dunkClient.querySalesHistory(modelNo, sizeId));
    }
}
