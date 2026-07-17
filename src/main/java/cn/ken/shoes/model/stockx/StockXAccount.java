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

    /** @deprecated 仅兼容旧账号JSON；运行时固定账号级1 request/s。 */
    @Deprecated
    private double graphqlQps = 1;

    /** @deprecated 仅兼容旧账号JSON；REST与GraphQL共享同一个1 request/s令牌。 */
    @Deprecated
    private double apiQps = 1;

    /** @deprecated 已取消本地5分钟批量条数拦截，以StockX真实响应为准。 */
    @Deprecated
    private int batchItemLimit;

}
