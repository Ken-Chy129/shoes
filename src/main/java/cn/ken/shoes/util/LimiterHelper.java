package cn.ken.shoes.util;


import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(3);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    /** StockX 官方 1 request/s 是账号级、跨 REST 与 GraphQL 共享。 */
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_ACCOUNT_LIMITERS = new ConcurrentHashMap<>();

    private static final RateLimiter STOCKX_GLOBAL_LIMITER = RateLimiter.create(1);

    public static void limitPoisonItem() {
        POISON_ITEM_LIMITER.acquire();
    }

    public static void limitKcItem() {
        KC_ITEM_LIMITER.acquire();
    }

    public static void limitPoisonPrice() {
        POISON_PRICE_LIMITER.acquire();
    }

    public static void limitStockxGraphql(String accountName) {
        stockxAccountLimiter(accountName).acquire();
    }

    public static void limitStockxApi(String accountName) {
        stockxAccountLimiter(accountName).acquire();
    }

    static RateLimiter stockxAccountLimiter(String accountName) {
        if (accountName == null) {
            return STOCKX_GLOBAL_LIMITER;
        }
        return STOCKX_ACCOUNT_LIMITERS.computeIfAbsent(accountName, ignored -> RateLimiter.create(1));
    }

    public static void limitDunkPrice() {
        DUNK_PRICE_LIMITER.acquire();
    }

    public static void listDunkSales() {
        DUNK_SALES_LIMITER.acquire();
    }
}
