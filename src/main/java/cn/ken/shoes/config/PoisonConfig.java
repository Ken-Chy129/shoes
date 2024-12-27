package cn.ken.shoes.config;

public class PoisonConfig {

    private static final boolean isProduct = false;

    private static final String PRODUCT_URL_PREFIX = "https://openapi.dewu.com/dop/api/v1";

    private static final String PRODUCT_APP_KEY = "319736078e3d4eec929f2c28626323a8";

    private static final String PRODUCT_APP_SECRET = "39af66f302954e7fa68a3df4801c02d49f8d8bba18fb4352952c9b22307a6328";

    private static final String TEST_URL_PREFIX = "https://openapi-sandbox.dewu.com/dop/api/v1";

    private static final String TEST_APP_KEY = "5f9a8f5c972d43be9c14fdba47d3cca1";

    private static final String TEST_APP_SECRET = "86fa581321b345ec83fdc436f2e96295";

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
