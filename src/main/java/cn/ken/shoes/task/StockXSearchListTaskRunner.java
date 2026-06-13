package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.TimeUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StockXSearchListTaskRunner implements Runnable {

    private final StockXAccount account;
    private final Long taskId;
    private final String keywords;
    private final String sorts;
    private final int pageCount;
    private final String searchType;
    private final boolean autoList;
    private final StockXService stockXService;
    private final TaskMapper taskMapper;

    public StockXSearchListTaskRunner(StockXAccount account, Long taskId,
                                      String keywords, String sorts, int pageCount,
                                      String searchType, boolean autoList,
                                      StockXService stockXService, TaskMapper taskMapper) {
        this.account = account;
        this.taskId = taskId;
        this.keywords = keywords;
        this.sorts = sorts;
        this.pageCount = pageCount;
        this.searchType = searchType;
        this.autoList = autoList;
        this.stockXService = stockXService;
        this.taskMapper = taskMapper;
    }

    @Override
    public void run() {
        String accountName = account.getName();
        try {
            long startTime = System.currentTimeMillis();
            stockXService.searchAndList(account, taskId, keywords, sorts, pageCount, searchType, autoList);
            String cost = TimeUtil.getCostMin(startTime);

            if (TaskSwitch.isSearchListCancelled(accountName)) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                taskMapper.updateTaskCost(taskId, cost);
                log.info("[{}] 搜索上架任务已取消，耗时:{}", accountName, cost);
            } else {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
                taskMapper.updateTaskCost(taskId, cost);
                log.info("[{}] 搜索上架任务完成，耗时:{}", accountName, cost);
            }
        } catch (Exception e) {
            log.error("[{}] 搜索上架任务异常: {}", accountName, e.getMessage(), e);
            String reason = e.getMessage();
            if (reason != null && reason.length() > 200) {
                reason = reason.substring(0, 200);
            }
            taskMapper.updateTaskFailed(taskId, reason);
        } finally {
            TaskSwitch.clearSearchListRunState(accountName);
        }
    }
}
