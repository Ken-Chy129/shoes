package cn.ken.shoes.model.stockx;

import lombok.Data;

@Data
public class StockXAccount {

    private String name;

    private String apiKey;

    private String authorization;

    private boolean enabled;

    // 是否由"发token机"自动续期托管（由 token minter 推送时置 true）
    private boolean autoRefresh = false;

    private String country = "US";

    private double transferFeeRate = 0.03;

    private double merchantFeeRate = 0.07;

    private double minMerchantFee = 5.79;

    private double platformShippingFee = 0;

    private int freight = 25;

    private int minProfit = -30;

    private double graphqlQps = 1;

    private double apiQps = 1;

    private int batchItemLimit = 500;

    private long standardInterval = 1800;

    private long custodialInterval = 1800;

    // ==================== 限流(429)处理 ====================
    /** 命中限流后的秒级短退避重试次数 */
    private int rateLimitMaxRetries = 5;

    /** 短退避基准时长(ms)，指数增长 */
    private long rateLimitBackoffBaseMs = 2000;

    /** 短退避封顶时长(ms) */
    private long rateLimitBackoffMaxMs = 60000;

    /** 短退避耗尽后的长冷却时长(ms)，默认1小时 */
    private long rateLimitCooldownMs = 3600000;

    /** 长冷却循环上限，超过仍限流则任务失败(保留进度)，默认3次(约3小时) */
    private int maxCooldownCycles = 3;
}
