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

    List<TaskItemDO> selectByCondition(@Param("taskId") Long taskId,
                                       @Param("round") Integer round,
                                       @Param("operateResult") String operateResult,
                                       @Param("styleId") String styleId,
                                       @Param("euSize") String euSize,
                                       @Param("startIndex") Integer startIndex,
                                       @Param("pageSize") Integer pageSize);

    Long countByCondition(@Param("taskId") Long taskId,
                          @Param("round") Integer round,
                          @Param("operateResult") String operateResult,
                          @Param("styleId") String styleId,
                          @Param("euSize") String euSize);

    /**
     * 批量更新操作结果
     */
    void batchUpdateResult(@Param("ids") List<Long> ids, @Param("operateResult") String operateResult);

    /**
     * 将任务下结果仍为 null 的明细统一标记（用于任务限流/异常中断后清理孤儿明细）
     */
    void markPendingResult(@Param("taskId") Long taskId, @Param("result") String result);

    /**
     * 根据任务ID删除所有明细
     */
    void deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 查询任务的所有操作结果类型
     */
    List<String> selectDistinctOperateResults(@Param("taskId") Long taskId);
}
