package cn.ken.shoes.exception;

/** StockX 返回的真实限流类别；UNKNOWN 表示当前响应还不足以判断配额维度。 */
public enum StockXRateLimitType {
    BATCH,
    GENERAL,
    UNKNOWN
}
