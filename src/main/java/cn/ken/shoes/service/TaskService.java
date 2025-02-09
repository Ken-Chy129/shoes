package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {

    @Resource
    private TaskMapper taskMapper;

    public List<TaskDO> queryEvents() {
        return List.of();
    }
}
