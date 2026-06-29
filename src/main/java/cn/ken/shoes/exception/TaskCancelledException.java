package cn.ken.shoes.exception;

/**
 * 任务在限流冷却等待期间被用户取消的信号。
 * <p>
 * 由 {@link cn.ken.shoes.util.StockXRateLimitGuard} 在冷却分片 sleep 中检测到取消标志时抛出，
 * runner 捕获后走正常取消流程（置 CANCEL 状态），而非置为失败。
 *
 * @author Ken-Chy129
 */
public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException() {
        super("任务已取消");
    }
}
