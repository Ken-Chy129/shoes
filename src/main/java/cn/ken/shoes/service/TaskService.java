package cn.ken.shoes.service;

import cn.hutool.json.JSONUtil;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class TaskService {

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private TaskItemMapper taskItemMapper;

    public PageResult<List<TaskDO>> queryTasksByCondition(TaskRequest request) {
        Long count = taskMapper.count(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<TaskDO> taskDOS = taskMapper.selectByCondition(request);
        for (TaskDO taskDO : taskDOS) {
            Optional.ofNullable(TaskDO.TaskTypeEnum.from(taskDO.getTaskType())).ifPresent(taskType -> taskDO.setTaskType(taskType.getName()));
            Optional.ofNullable(TaskDO.TaskStatusEnum.from(taskDO.getStatus())).ifPresent(status -> taskDO.setStatus(status.getName()));
        }
        PageResult<List<TaskDO>> result = PageResult.buildSuccess(taskDOS);
        result.setTotal(count);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task != null) {
            clearTaskRunningState(task);
        }
        taskItemMapper.deleteByTaskId(taskId);
        taskMapper.deleteById(taskId);
    }

    public void cancelTaskById(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        dispatchTaskSwitch(task, false);
    }

    private void clearTaskRunningState(TaskDO task) {
        dispatchTaskSwitch(task, true);
    }

    private void dispatchTaskSwitch(TaskDO task, boolean clearState) {
        String taskType = task.getTaskType();
        String accountName = task.getAccountName();
        if (taskType == null || accountName == null) {
            return;
        }
        String inventoryType = "STANDARD";
        try {
            if (task.getParams() != null) {
                inventoryType = JSONUtil.parseObj(task.getParams()).getStr("inventoryType", "STANDARD");
            }
        } catch (Exception ignored) {}

        String key = accountName + ":" + inventoryType;
        switch (taskType) {
            case "price_down" -> { if (clearState) TaskSwitch.clearExcelState(accountName, inventoryType); else TaskSwitch.cancelExcel(accountName, inventoryType); }
            case "listing" -> { if (clearState) TaskSwitch.clearSearchListRunState(accountName); else TaskSwitch.cancelSearchList(accountName); }
            case "fetch_listings" -> { if (clearState) TaskSwitch.clearFetchListingsState(key); else TaskSwitch.cancelFetchListings(key); }
            case "excel_delist" -> { if (clearState) TaskSwitch.clearExcelDelistState(key); else TaskSwitch.cancelExcelDelist(key); }
            case "fetch_orders" -> { if (clearState) TaskSwitch.clearFetchOrdersState(accountName); else TaskSwitch.cancelFetchOrders(accountName); }
            default -> log.info("任务类型无需处理TaskSwitch: taskType={}", taskType);
        }
    }
}
