package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("taskItem")
public class TaskItemController {

    @Resource
    private TaskItemMapper taskItemMapper;

    @GetMapping("page")
    public PageResult<List<TaskItemDO>> queryTaskItems(
            @RequestParam Long taskId,
            @RequestParam(required = false) Integer round,
            @RequestParam(required = false) String operateResult,
            @RequestParam(required = false) String styleId,
            @RequestParam(required = false) String euSize,
            @RequestParam(defaultValue = "1") Integer pageIndex,
            @RequestParam(defaultValue = "15") Integer pageSize) {
        int startIndex = (pageIndex - 1) * pageSize;
        List<TaskItemDO> items = taskItemMapper.selectByCondition(taskId, round, operateResult, styleId, euSize, startIndex, pageSize);
        Long total = taskItemMapper.countByCondition(taskId, round, operateResult, styleId, euSize);
        PageResult<List<TaskItemDO>> result = PageResult.buildSuccess(items);
        result.setTotal(total);
        return result;
    }
}
