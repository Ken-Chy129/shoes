package cn.ken.shoes.config;

import lombok.Data;

public class StockXConfig {

    public static final OAuth2Config CONFIG = new OAuth2Config();

    public static final String ITEM_LIST = "https://stockx.com/api/p/e";

    public static final String AUTHORIZE = "https://accounts.stockx.com/authorize?" +
            "response_type=code&" +
            "client_id={clientId}&" +
            "redirect_uri={redirectUri}&" +
            "scope=offline_access%20openid&" +
            "audience=gateway.stockx.com&" +
            "state={state}";

    public static final String TOKEN = "https://accounts.stockx.com/oauth/token";

    public static final String CALLBACK = "https://config.ken-chy129.cn/callback/getStockxCode";

    public static final String SEARCH_ITEMS = "https://api.stockx.com/v2/catalog/search";

    public static final String SEARCH_SIZE = "https://api.stockx.com/v2/catalog/products/{productId}/variants";

    public static final String SEARCH_PRICE = "https://api.stockx.com/v2/catalog/products/{productId}/market-data";

    @Data
    public static class OAuth2Config {
        private String accessToken;

        private String refreshToken;

        private String idToken;

        private String expireTime;
    }
}
