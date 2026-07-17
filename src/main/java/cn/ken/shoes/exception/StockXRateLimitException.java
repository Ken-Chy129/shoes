package cn.ken.shoes.exception;

/**
 * StockX 持续限流（HTTP 429 / "Batch usage limit exceeded"）信号。
 * <p>
 * 短退避重试耗尽后抛出：
 * <ul>
 *     <li>任务线程：会先经过 {@link cn.ken.shoes.util.StockXRateLimitGuard} 的冷却等待与封顶逻辑，
 *     封顶仍限流才抛出，runner 据此置任务失败并保留已完成进度。</li>
 *     <li>同步调用（如前端搜索）：无任务上下文，直接快速失败返回错误，不做长等待。</li>
 * </ul>
 *
 * @author Ken-Chy129
 */
public class StockXRateLimitException extends RuntimeException {

    private final String accountName;
    private final long cooldownMs;
    private final StockXRateLimitType type;
    private final String signal;

    public StockXRateLimitException(String accountName, long cooldownMs) {
        this(accountName, cooldownMs, "StockX限流(429)，账号[" + accountName + "]请稍后再试");
    }

    public StockXRateLimitException(String accountName, long cooldownMs, String message) {
        this(accountName, cooldownMs, message, StockXRateLimitType.UNKNOWN, "unknown");
    }

    public StockXRateLimitException(String accountName, long cooldownMs, String message,
                                    StockXRateLimitType type, String signal) {
        super(message);
        this.accountName = accountName;
        this.cooldownMs = cooldownMs;
        this.type = type != null ? type : StockXRateLimitType.UNKNOWN;
        this.signal = signal != null ? signal : "unknown";
    }

    public String getAccountName() {
        return accountName;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public StockXRateLimitType getType() {
        return type;
    }

    public String getSignal() {
        return signal;
    }
}
