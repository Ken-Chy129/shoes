package cn.ken.shoes.util;


import com.google.common.util.concurrent.RateLimiter;

public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_SECOND_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    public static void limitPoisonItem() {
        POISON_ITEM_LIMITER.acquire();
    }

    public static void limitKcItem() {
        KC_ITEM_LIMITER.acquire();
    }

    public static void limitPoisonPrice() {
        POISON_PRICE_LIMITER.acquire();
    }

    public static void limitStockxPrice() {
        STOCKX_PRICE_LIMITER.acquire();
    }

    public static void limitStockxSecond() {STOCKX_SECOND_LIMITER.acquire();}

    public static void limitDunkPrice() {
        DUNK_PRICE_LIMITER.acquire();
    }

    public static void listDunkSales() {
        DUNK_SALES_LIMITER.acquire();
    }
}
