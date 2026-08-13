package cn.ken.shoes.exception;

/**
 * StockX 写请求没有返回可解析响应，调用方可据此选择安全的降级策略。
 */
public class StockXNoResponseException extends RuntimeException {

    public enum FailureType {
        NETWORK_NO_RESPONSE,
        HTTP_403,
        BLOCK_SCRIPT
    }

    private final FailureType failureType;

    public StockXNoResponseException(String message) {
        this(message, FailureType.NETWORK_NO_RESPONSE);
    }

    public StockXNoResponseException(String message, FailureType failureType) {
        super(message);
        this.failureType = failureType != null ? failureType : FailureType.NETWORK_NO_RESPONSE;
    }

    public FailureType getFailureType() {
        return failureType;
    }
}
