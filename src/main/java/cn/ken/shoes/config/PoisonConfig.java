package cn.ken.shoes.config;

public class PoisonConfig {

    private static final boolean isProduct = false;

    private static final String PRODUCT_URL_PREFIX = "https://openapi.dewu.com/dop/api/v1";

    private static final String PRODUCT_APP_KEY = "x";

    private static final String PRODUCT_APP_SECRET = "x";

    private static final String TEST_URL_PREFIX = "https://openapi-sandbox.dewu.com/dop/api/v1";

    private static final String TEST_APP_KEY = "x";

    private static final String TEST_APP_SECRET = "x";

    public static String getUrlPrefix() {
        return isProduct ? PRODUCT_URL_PREFIX : TEST_URL_PREFIX;
    }

    public static String getAppKey() {
        return isProduct ? PRODUCT_APP_KEY : TEST_APP_KEY;
    }

    public static String getAppSecret() {
        return isProduct ? PRODUCT_APP_SECRET : TEST_APP_SECRET;
    }
}
