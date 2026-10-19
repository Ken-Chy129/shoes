package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.task.TaskOperationCount;
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

    /**
     * 删除周期任务旧轮次中的无操作明细（保持-/跳过-），保留真实操作与待对账结果；单次最多删除 limit 行。
     */
    int deleteStaleNoopItems(@Param("taskId") Long taskId,
                             @Param("beforeRound") Integer beforeRound,
                             @Param("limit") Integer limit);

    /**
     * 删除指定任务在 beforeDate 之前写入/更新的明细；单次最多删除 limit 行。
     */
    int deleteItemsOperatedBefore(@Param("taskId") Long taskId,
                                  @Param("beforeDate") java.util.Date beforeDate,
                                  @Param("limit") Integer limit);

    /**
     * 批量聚合任务真实成功操作数，避免任务列表逐条查询。
     */
    List<TaskOperationCount> selectOperationCountsByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 已处理完成（或已提交）的商品ID；待上架但尚未成功提交的记录允许暂停后重试。
     */
    List<String> selectProcessedProductIdsByTaskId(@Param("taskId") Long taskId);

    /**
     * 统计已经提交上架的商品数，用于暂停恢复后延续 maxListCount 限制。
     */
    Long countSubmittedListingsByTaskId(@Param("taskId") Long taskId);

    Long countSuccessfulDelistsByTaskId(@Param("taskId") Long taskId);
}
