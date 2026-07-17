package cn.ken.shoes.exception;

/**
 * StockX 写请求没有返回可解析响应，调用方可据此选择安全的降级策略。
 */
public class StockXNoResponseException extends RuntimeException {

    public StockXNoResponseException(String message) {
        super(message);
    }
}
