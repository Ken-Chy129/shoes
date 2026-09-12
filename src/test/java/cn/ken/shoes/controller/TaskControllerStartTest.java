package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.manager.TaskExecutorManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskControllerStartTest {

    @Test
    void rejectsTaskTypesThatRequireDedicatedEndpoints() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        TaskController controller = controller(manager);

        Result<Void> result = controller.startTask("ebay_delist");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("专用接口");
        verifyNoInteractions(manager);
    }

    @Test
    void reportsFailureWhenACommonTaskWasNotCreated() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startTask(TaskTypeEnum.LISTING)).thenReturn(null);
        TaskController controller = controller(manager);

        Result<Void> result = controller.startTask("listing");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("已在运行");
        verify(manager).startTask(TaskTypeEnum.LISTING);
    }

    @Test
    void keepsSupportedCommonTasksWorking() throws Exception {
        TaskExecutorManager manager = mock(TaskExecutorManager.class);
        when(manager.startTask(TaskTypeEnum.PRICE_DOWN)).thenReturn(123L);
        TaskController controller = controller(manager);

        Result<Void> result = controller.startTask("price_down");

        assertThat(result.getSuccess()).isTrue();
        verify(manager).startTask(TaskTypeEnum.PRICE_DOWN);
    }

    private TaskController controller(TaskExecutorManager manager) throws Exception {
        TaskController controller = new TaskController();
        Field field = TaskController.class.getDeclaredField("taskExecutorManager");
        field.setAccessible(true);
        field.set(controller, manager);
        return controller;
    }
}
