package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.TaskDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Date;

@Mapper
public interface TaskMapper extends BaseMapper<TaskDO> {

    TaskDO selectTask(String type, String platform, Integer status);

    void updateTaskStatus(Long id, Integer status);
}
