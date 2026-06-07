package cn.ken.shoes.util;


import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(3);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_GRAPHQL_LIMITERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_API_LIMITERS = new ConcurrentHashMap<>();
    private static final RateLimiter STOCKX_GLOBAL_GRAPHQL_LIMITER = RateLimiter.create(1);
    private static final RateLimiter STOCKX_GLOBAL_API_LIMITER = RateLimiter.create(2);

    private static final double STOCKX_GRAPHQL_QPS = 1;
    private static final double STOCKX_API_QPS = 2;

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
        if (accountName != null) {
            STOCKX_GRAPHQL_LIMITERS.computeIfAbsent(accountName, k -> RateLimiter.create(STOCKX_GRAPHQL_QPS)).acquire();
        } else {
            STOCKX_GLOBAL_GRAPHQL_LIMITER.acquire();
        }
    }

    public static void limitStockxApi(String accountName) {
        if (accountName != null) {
            STOCKX_API_LIMITERS.computeIfAbsent(accountName, k -> RateLimiter.create(STOCKX_API_QPS)).acquire();
        } else {
            STOCKX_GLOBAL_API_LIMITER.acquire();
        }
    }

    public static void limitDunkPrice() {
        DUNK_PRICE_LIMITER.acquire();
    }

    public static void listDunkSales() {
        DUNK_SALES_LIMITER.acquire();
    }
}
