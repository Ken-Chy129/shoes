package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class TaskService {

    @Resource
    private TaskMapper taskMapper;

    public PageResult<List<TaskDO>> queryTasksByCondition(TaskRequest request) {
        Long count = taskMapper.count(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<TaskDO> taskDOS = taskMapper.selectByCondition(request);
        PageResult<List<TaskDO>> result = PageResult.buildSuccess(taskDOS);
        result.setTotal(count);
        return result;
    }
}
