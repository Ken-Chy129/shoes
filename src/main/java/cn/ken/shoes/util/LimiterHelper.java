package cn.ken.shoes.util;


import com.google.common.util.concurrent.RateLimiter;

public class LimiterHelper {

    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(6);

    public static void limitPoisonItem() {
        POISON_PRICE_LIMITER.acquire(1);
    }

    public static void limitKcItem() {
        KC_ITEM_LIMITER.acquire();
    }

    public static void limitPoisonPrice() {
        POISON_PRICE_LIMITER.acquire();
    }
}
