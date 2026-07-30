package cn.ken.shoes.service;

import cn.ken.shoes.mapper.TaskMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCleanupServiceTest {

    @Test
    void deletesExpiredTasksAcrossBoundedPages() {
        List<Long> cursors = new ArrayList<>();
        List<Integer> limits = new ArrayList<>();
        TaskMapper taskMapper = proxy((method, args) -> switch (method) {
            case "selectExpiredTaskIds" -> {
                long cursor = (Long) args[1];
                cursors.add(cursor);
                limits.add((Integer) args[2]);
                yield switch ((int) cursor) {
                    case 0 -> List.of(10L, 20L);
                    case 20 -> List.of(30L);
                    default -> List.of();
                };
            }
            case "deleteExpiredTaskWithItems" -> switch (((Long) args[0]).intValue()) {
                case 10 -> 11;
                case 20 -> 21;
                case 30 -> 31;
                default -> 0;
            };
            default -> null;
        });
        TaskCleanupService service = new TaskCleanupService(taskMapper);

        TaskCleanupService.CleanupResult result = service.cleanupExpiredTasks(new Date(1_000_000));

        assertThat(result.deletedTasks()).isEqualTo(3);
        assertThat(result.deletedRows()).isEqualTo(63);
        assertThat(result.failedTasks()).isZero();
        assertThat(cursors).containsExactly(0L, 20L, 30L);
        assertThat(limits).containsOnly(20);
    }

    @Test
    void continuesWithLaterTasksWhenOneDeletionFails() {
        List<Long> deletedIds = new ArrayList<>();
        TaskMapper taskMapper = proxy((method, args) -> switch (method) {
            case "selectExpiredTaskIds" -> (Long) args[1] == 0L ? List.of(10L, 20L) : List.of();
            case "deleteExpiredTaskWithItems" -> {
                long taskId = (Long) args[0];
                deletedIds.add(taskId);
                if (taskId == 10L) throw new IllegalStateException("locked");
                yield 5;
            }
            default -> null;
        });
        TaskCleanupService service = new TaskCleanupService(taskMapper);

        TaskCleanupService.CleanupResult result = service.cleanupExpiredTasks(new Date(1_000_000));

        assertThat(deletedIds).containsExactly(10L, 20L);
        assertThat(result.deletedTasks()).isEqualTo(1);
        assertThat(result.deletedRows()).isEqualTo(5);
        assertThat(result.failedTasks()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static TaskMapper proxy(Invocation invocation) {
        return (TaskMapper) Proxy.newProxyInstance(TaskMapper.class.getClassLoader(), new Class<?>[]{TaskMapper.class},
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
}
