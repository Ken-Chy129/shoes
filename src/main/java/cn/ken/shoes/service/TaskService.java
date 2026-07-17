package cn.ken.shoes.service;

import cn.hutool.json.JSONUtil;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskOperationCount;
import cn.ken.shoes.model.task.TaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskService {

    private final TaskMapper taskMapper;

    private final TaskItemMapper taskItemMapper;

    private final TaskExecutorManager taskExecutorManager;

    public TaskService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                       TaskExecutorManager taskExecutorManager) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.taskExecutorManager = taskExecutorManager;
    }

    public PageResult<List<TaskDO>> queryTasksByCondition(TaskRequest request) {
        Long count = taskMapper.count(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<TaskDO> taskDOS = taskMapper.selectByCondition(request);
        if (taskDOS == null || taskDOS.isEmpty()) {
            PageResult<List<TaskDO>> emptyResult = PageResult.buildSuccess();
            emptyResult.setTotal(count);
            return emptyResult;
        }
        List<Long> taskIds = taskDOS.stream().map(TaskDO::getId).toList();
        List<TaskOperationCount> operationCounts = taskItemMapper.selectOperationCountsByTaskIds(taskIds);
        Map<Long, TaskOperationCount> countMap = (operationCounts != null ? operationCounts : List.<TaskOperationCount>of()).stream()
                .collect(Collectors.toMap(TaskOperationCount::getTaskId, Function.identity()));
        for (TaskDO taskDO : taskDOS) {
            TaskOperationCount operationCount = countMap.get(taskDO.getId());
            if (operationCount != null) {
                taskDO.setPriceDownCount(operationCount.getPriceDownCount());
                taskDO.setListingCount(operationCount.getListingCount());
                taskDO.setDelistCount(operationCount.getDelistCount());
                taskDO.setPendingOperationCount(operationCount.getPendingOperationCount());
            }
            taskDO.setRerunnable(taskExecutorManager.canRerun(taskDO));
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
            if (TaskDO.TaskStatusEnum.RUNNING.getCode().equals(task.getStatus())) {
                throw new IllegalStateException("运行中的任务不能删除，请先终止任务");
            }
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

    public Long resumeTaskById(Long taskId) {
        TaskDO task = requireTask(taskId);
        if (!TaskDO.TaskStatusEnum.PAUSED.getCode().equals(task.getStatus())) {
            throw new IllegalStateException("只有已暂停任务可以继续执行");
        }
        Long resumedTaskId = taskExecutorManager.resumePausedTask(task);
        if (resumedTaskId == null) {
            throw new IllegalStateException("任务无法继续执行，可能已有同账号任务运行或原始输入已丢失");
        }
        return resumedTaskId;
    }

    public Long rerunTaskById(Long taskId) {
        TaskDO task = requireTask(taskId);
        if (TaskDO.TaskStatusEnum.RUNNING.getCode().equals(task.getStatus())) {
            throw new IllegalStateException("运行中的任务不能重跑，请先终止或等待结束");
        }
        if (!taskExecutorManager.canRerun(task)) {
            throw new IllegalStateException("该历史任务类型不支持重跑");
        }
        Long newTaskId = taskExecutorManager.rerunTask(task);
        if (newTaskId == null) {
            throw new IllegalStateException("任务重跑失败，可能已有同账号任务运行或原始输入已丢失");
        }
        return newTaskId;
    }

    private TaskDO requireTask(Long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
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
            case "listing", "model_search" -> {
                if (clearState) {
                    TaskSwitch.clearSearchListRunState(accountName);
                } else {
                    TaskSwitch.cancelSearchList(accountName);
                    TaskSwitch.cancelSearchVerification(task.getId());
                }
            }
            case "fetch_listings" -> { if (clearState) TaskSwitch.clearFetchListingsState(key); else TaskSwitch.cancelFetchListings(key); }
            case "excel_delist" -> { if (clearState) TaskSwitch.clearExcelDelistState(key); else TaskSwitch.cancelExcelDelist(key); }
            case "fetch_orders" -> { if (clearState) TaskSwitch.clearFetchOrdersState(accountName); else TaskSwitch.cancelFetchOrders(accountName); }
            default -> log.info("任务类型无需处理TaskSwitch: taskType={}", taskType);
        }
    }
}
