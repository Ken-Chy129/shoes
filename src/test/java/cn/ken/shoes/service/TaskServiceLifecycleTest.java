package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.manager.TaskExecutorManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.task.TaskOperationCount;
import cn.ken.shoes.model.task.TaskRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskServiceLifecycleTest {

    @Test
    void returnsRealOperationCountsAndPausedStatusForTaskList() {
        TaskDO task = task(7L, "paused");
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> switch (method) {
            case "count" -> 1L;
            case "selectByCondition" -> List.of(task);
            default -> null;
        });
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) ->
                "selectOperationCountsByTaskIds".equals(method)
                        ? List.of(new TaskOperationCount(7L, 12L, 8L, 3L, 2L))
                        : null);
        TaskService taskService = new TaskService(taskMapper, taskItemMapper, new FakeTaskExecutorManager());

        PageResult<List<TaskDO>> result = taskService.queryTasksByCondition(new TaskRequest());

        assertThat(result.getData()).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo("已暂停");
            assertThat(item.getPriceDownCount()).isEqualTo(12L);
            assertThat(item.getListingCount()).isEqualTo(8L);
            assertThat(item.getDelistCount()).isEqualTo(3L);
            assertThat(item.getPendingOperationCount()).isEqualTo(2L);
            assertThat(item.isRerunnable()).isTrue();
        });
    }

    @Test
    void marksUnsupportedHistoricalTaskAsNotRerunnableAndRejectsRerun() {
        TaskDO source = task(7L, "failed");
        source.setTaskType("legacy_removed_task");
        TaskService taskService = new TaskService(mapperReturning(source), emptyTaskItemMapper(), new FakeTaskExecutorManager());

        assertThatThrownBy(() -> taskService.rerunTaskById(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("该历史任务类型不支持重跑");
    }

    @Test
    void resumesPausedTaskUsingTheSameTaskId() {
        TaskDO task = task(7L, "paused");
        TaskMapper taskMapper = mapperReturning(task);
        FakeTaskExecutorManager executor = new FakeTaskExecutorManager();
        executor.resumeResult = 7L;
        TaskService taskService = new TaskService(taskMapper, emptyTaskItemMapper(), executor);

        Long resumedId = taskService.resumeTaskById(7L);

        assertThat(resumedId).isEqualTo(7L);
        assertThat(executor.resumed.get()).isSameAs(task);
    }

    @Test
    void rejectsResumeForTaskThatIsNotPaused() {
        TaskService taskService = new TaskService(
                mapperReturning(task(7L, "failed")), emptyTaskItemMapper(), new FakeTaskExecutorManager());

        assertThatThrownBy(() -> taskService.resumeTaskById(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("只有已暂停任务可以继续执行");
    }

    @Test
    void rerunsTaskAsANewTaskUsingStoredParameters() {
        TaskDO source = task(7L, "failed");
        source.setParams("{\"inventoryType\":\"STANDARD\"}");
        FakeTaskExecutorManager executor = new FakeTaskExecutorManager();
        executor.rerunResult = 19L;
        TaskService taskService = new TaskService(mapperReturning(source), emptyTaskItemMapper(), executor);

        Long newTaskId = taskService.rerunTaskById(7L);

        assertThat(newTaskId).isEqualTo(19L);
        assertThat(executor.rerun.get()).isSameAs(source);
    }

    @Test
    void rejectsDeletingARunningTaskBeforeClearingItsRuntimeState() {
        TaskDO running = task(7L, "running");
        AtomicInteger deletedTaskItems = new AtomicInteger();
        AtomicInteger deletedTasks = new AtomicInteger();
        TaskMapper taskMapper = proxy(TaskMapper.class, (method, args) -> switch (method) {
            case "selectById" -> running;
            case "deleteById" -> deletedTasks.incrementAndGet();
            default -> null;
        });
        TaskItemMapper taskItemMapper = proxy(TaskItemMapper.class, (method, args) -> {
            if ("deleteByTaskId".equals(method)) {
                return deletedTaskItems.incrementAndGet();
            }
            return null;
        });
        TaskService taskService = new TaskService(taskMapper, taskItemMapper, new FakeTaskExecutorManager());

        assertThatThrownBy(() -> taskService.deleteTask(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("运行中的任务不能删除，请先终止任务");
        assertThat(deletedTaskItems).hasValue(0);
        assertThat(deletedTasks).hasValue(0);
    }

    private static TaskMapper mapperReturning(TaskDO task) {
        return proxy(TaskMapper.class, (method, args) -> "selectById".equals(method) ? task : null);
    }

    private static TaskItemMapper emptyTaskItemMapper() {
        return proxy(TaskItemMapper.class, (method, args) -> null);
    }

    private static TaskDO task(Long id, String status) {
        TaskDO task = new TaskDO();
        task.setId(id);
        task.setPlatform("stockx");
        task.setTaskType("price_down");
        task.setAccountName("account-a");
        task.setStatus(status);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object result = invocation.call(method.getName(), args == null ? new Object[0] : args);
                    if (result != null || method.getReturnType() == void.class) return result;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args);
    }

    private static class FakeTaskExecutorManager extends TaskExecutorManager {
        private final AtomicReference<TaskDO> resumed = new AtomicReference<>();
        private final AtomicReference<TaskDO> rerun = new AtomicReference<>();
        private Long resumeResult;
        private Long rerunResult;

        @Override
        public Long resumePausedTask(TaskDO task) {
            resumed.set(task);
            return resumeResult;
        }

        @Override
        public Long rerunTask(TaskDO task) {
            rerun.set(task);
            return rerunResult;
        }
    }
}
