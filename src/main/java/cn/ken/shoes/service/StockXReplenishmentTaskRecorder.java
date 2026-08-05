package cn.ken.shoes.service;

import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;

/** 补单执行记录的持久化边界，便于业务逻辑测试时使用内存实现。 */
public interface StockXReplenishmentTaskRecorder {

    Long start(TaskDO task);

    void record(TaskItemDO item);

    void updateProgress(Long taskId, int pageCount, String attributes);

    void complete(Long taskId, String cost, String summary);

    void fail(Long taskId, String cost, String reason);
}
