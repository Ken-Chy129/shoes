package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<TaskDO> {

    TaskDO selectTask(Integer type, String platform, Integer status);

    void updateTaskStatus(Long id, Integer status);

    List<TaskDO> selectByCondition(TaskRequest request);
}
