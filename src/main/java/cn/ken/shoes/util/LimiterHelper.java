package cn.ken.shoes.util;


import cn.ken.shoes.config.StockXSwitch;
import com.google.common.util.concurrent.RateLimiter;

public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_SECOND_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    // StockX压价限流器，支持动态配置
    private static volatile RateLimiter STOCKX_PRICE_DOWN_LIMITER = RateLimiter.create(StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE / 60.0);
    private static volatile int lastPriceDownPerMinute = StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE;

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

    /**
     * StockX压价接口限流，支持动态配置
     */
    public static void limitStockxPriceDown() {
        // 检查配置是否变更，如果变更则重新创建限流器
        if (lastPriceDownPerMinute != StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE) {
            synchronized (LimiterHelper.class) {
                if (lastPriceDownPerMinute != StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE) {
                    STOCKX_PRICE_DOWN_LIMITER = RateLimiter.create(StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE / 60.0);
                    lastPriceDownPerMinute = StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE;
                }
            }
        }
        STOCKX_PRICE_DOWN_LIMITER.acquire();
    }
}
