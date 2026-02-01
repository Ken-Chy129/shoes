package cn.ken.shoes.util;


import cn.ken.shoes.config.StockXSwitch;
import com.google.common.util.concurrent.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.time.Duration;

public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_PRICE_LIMITER = RateLimiter.create(10);
    private static final RateLimiter STOCKX_SECOND_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    // StockX压价限流器，使用Bucket4j支持分钟级限流
    private static volatile Bucket STOCKX_PRICE_DOWN_BUCKET = createBucket(StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE);
    private static volatile int lastPriceDownPerMinute = StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE;

    private static Bucket createBucket(int permitsPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(permitsPerMinute, Duration.ofMinutes(1)))
                .build();
    }

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
     * 使用Bucket4j实现分钟级限流，支持突发请求
     */
    public static void limitStockxPriceDown() {
        // 检查配置是否变更，如果变更则重新创建限流器
        if (lastPriceDownPerMinute != StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE) {
            synchronized (LimiterHelper.class) {
                if (lastPriceDownPerMinute != StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE) {
                    STOCKX_PRICE_DOWN_BUCKET = createBucket(StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE);
                    lastPriceDownPerMinute = StockXSwitch.TASK_PRICE_DOWN_PER_MINUTE;
                }
            }
        }
        try {
            STOCKX_PRICE_DOWN_BUCKET.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
