package cn.ken.shoes.util;


import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(3);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    // StockX GraphQL 限流：按账号 1 QPS
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_GRAPHQL_LIMITERS = new ConcurrentHashMap<>();
    private static final RateLimiter STOCKX_GLOBAL_GRAPHQL_LIMITER = RateLimiter.create(1);

    // StockX REST API 限流：按账号 1 QPS（官方限制）
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_API_LIMITERS = new ConcurrentHashMap<>();
    private static final RateLimiter STOCKX_GLOBAL_API_LIMITER = RateLimiter.create(1);

    // StockX Batch 限流：每账号 5 分钟内最多 500 条 items
    private static final int BATCH_WINDOW_MS = 5 * 60 * 1000;
    private static final int BATCH_ITEM_LIMIT = 500;
    private static final ConcurrentHashMap<String, BatchWindow> STOCKX_BATCH_WINDOWS = new ConcurrentHashMap<>();

    private static class BatchWindow {
        long windowStart;
        final AtomicInteger itemCount = new AtomicInteger(0);

        BatchWindow() {
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int items) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= BATCH_WINDOW_MS) {
                windowStart = now;
                itemCount.set(0);
            }
            if (itemCount.get() + items <= BATCH_ITEM_LIMIT) {
                itemCount.addAndGet(items);
                return true;
            }
            return false;
        }

        synchronized long waitTimeMs() {
            return Math.max(0, BATCH_WINDOW_MS - (System.currentTimeMillis() - windowStart));
        }
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

    public static void limitStockxGraphql(String accountName) {
        if (accountName != null) {
            STOCKX_GRAPHQL_LIMITERS.computeIfAbsent(accountName, k -> RateLimiter.create(1)).acquire();
        } else {
            STOCKX_GLOBAL_GRAPHQL_LIMITER.acquire();
        }
    }

    public static void limitStockxApi(String accountName) {
        if (accountName != null) {
            STOCKX_API_LIMITERS.computeIfAbsent(accountName, k -> RateLimiter.create(1)).acquire();
        } else {
            STOCKX_GLOBAL_API_LIMITER.acquire();
        }
    }

    /**
     * Batch 限流：每账号 5 分钟最多 500 条 items。
     * 如果当前窗口已满，阻塞等待到下个窗口。
     */
    public static void limitStockxBatch(String accountName, int itemCount) {
        String key = accountName != null ? accountName : "_global";
        BatchWindow window = STOCKX_BATCH_WINDOWS.computeIfAbsent(key, k -> new BatchWindow());
        while (!window.tryAcquire(itemCount)) {
            long waitMs = window.waitTimeMs();
            log.info("Batch限流[{}]: 5分钟内已达{}条上限，等待{}秒", key, BATCH_ITEM_LIMIT, waitMs / 1000);
            try {
                Thread.sleep(Math.max(waitMs, 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void limitDunkPrice() {
        DUNK_PRICE_LIMITER.acquire();
    }

    public static void listDunkSales() {
        DUNK_SALES_LIMITER.acquire();
    }
}
