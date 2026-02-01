package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.request.SizeChartUpdateRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("sizeChart")
public class SizeChartController {

    @Resource
    private SizeChartMapper sizeChartMapper;

    @GetMapping("list")
    public PageResult<List<SizeChartDO>> list(
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String gender,
            @RequestParam(defaultValue = "1") Integer pageIndex,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        Long total = sizeChartMapper.count(brand, gender);
        if (total == 0) {
            return PageResult.buildSuccess();
        }

        int offset = (pageIndex - 1) * pageSize;
        List<SizeChartDO> data = sizeChartMapper.selectPage(brand, gender, offset, pageSize);

        PageResult<List<SizeChartDO>> result = PageResult.buildSuccess(data);
        result.setTotal(total);
        result.setPageIndex(pageIndex);
        result.setPageSize(pageSize);
        result.setPageCount((total + pageSize - 1) / pageSize);
        result.setHasMore(pageIndex < result.getPageCount());
        return result;
    }

    @PostMapping("add")
    public Result<Boolean> add(@RequestBody SizeChartDO sizeChartDO) {
        if (sizeChartDO.getBrand() == null || sizeChartDO.getGender() == null
                || sizeChartDO.getEuSize() == null || sizeChartDO.getUsSize() == null) {
            return Result.buildError("品牌、性别、欧码、美码不能为空");
        }
        int rows = sizeChartMapper.insert(sizeChartDO);
        return Result.buildSuccess(rows > 0);
    }

    @PostMapping("update")
    public Result<Boolean> update(@RequestBody SizeChartUpdateRequest request) {
        if (request.getBrand() == null || request.getGender() == null
                || request.getEuSize() == null || request.getUsSize() == null) {
            return Result.buildError("品牌、性别、欧码、美码不能为空");
        }
        if (request.getOldBrand() == null || request.getOldGender() == null
                || request.getOldEuSize() == null || request.getOldUsSize() == null) {
            return Result.buildError("原始主键不能为空");
        }
        int rows = sizeChartMapper.updateByOldKey(request);
        return Result.buildSuccess(rows > 0);
    }

    @PostMapping("delete")
    public Result<Boolean> delete(@RequestBody SizeChartDO sizeChartDO) {
        if (sizeChartDO.getBrand() == null || sizeChartDO.getGender() == null
                || sizeChartDO.getEuSize() == null || sizeChartDO.getUsSize() == null) {
            return Result.buildError("品牌、性别、欧码、美码不能为空");
        }
        int rows = sizeChartMapper.deleteByKey(
                sizeChartDO.getBrand(),
                sizeChartDO.getGender(),
                sizeChartDO.getEuSize(),
                sizeChartDO.getUsSize());
        return Result.buildSuccess(rows > 0);
    }
}
