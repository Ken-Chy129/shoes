package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.TaskItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskItemMapper extends BaseMapper<TaskItemDO> {

    List<TaskItemDO> selectByTaskId(@Param("taskId") Long taskId,
                                    @Param("startIndex") Integer startIndex,
                                    @Param("pageSize") Integer pageSize);

    Long countByTaskId(@Param("taskId") Long taskId);

    /**
     * 批量更新操作结果
     */
    void batchUpdateResult(@Param("ids") List<Long> ids, @Param("operateResult") String operateResult);
}
