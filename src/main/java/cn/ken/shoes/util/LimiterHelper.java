package cn.ken.shoes.util;


import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
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

    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_GRAPHQL_LIMITERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_API_LIMITERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BatchWindow> STOCKX_BATCH_WINDOWS = new ConcurrentHashMap<>();

    private static final RateLimiter STOCKX_GLOBAL_GRAPHQL_LIMITER = RateLimiter.create(1);
    private static final RateLimiter STOCKX_GLOBAL_API_LIMITER = RateLimiter.create(1);
    private static final int BATCH_WINDOW_MS = 5 * 60 * 1000;

    private static class BatchWindow {
        long windowStart;
        final AtomicInteger itemCount = new AtomicInteger(0);
        final int limit;

        BatchWindow(int limit) {
            this.windowStart = System.currentTimeMillis();
            this.limit = limit;
        }

        synchronized boolean tryAcquire(int items) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= BATCH_WINDOW_MS) {
                windowStart = now;
                itemCount.set(0);
            }
            if (itemCount.get() + items <= limit) {
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
            StockXAccount account = StockXConfig.getAccount(accountName);
            double qps = account != null ? account.getGraphqlQps() : 1;
            getOrUpdateLimiter(STOCKX_GRAPHQL_LIMITERS, accountName, qps).acquire();
        } else {
            STOCKX_GLOBAL_GRAPHQL_LIMITER.acquire();
        }
    }

    public static void limitStockxApi(String accountName) {
        if (accountName != null) {
            StockXAccount account = StockXConfig.getAccount(accountName);
            double qps = account != null ? account.getApiQps() : 1;
            getOrUpdateLimiter(STOCKX_API_LIMITERS, accountName, qps).acquire();
        } else {
            STOCKX_GLOBAL_API_LIMITER.acquire();
        }
    }

    private static RateLimiter getOrUpdateLimiter(ConcurrentHashMap<String, RateLimiter> map, String key, double qps) {
        RateLimiter limiter = map.get(key);
        if (limiter == null) {
            limiter = RateLimiter.create(qps);
            map.put(key, limiter);
        } else if (limiter.getRate() != qps) {
            limiter.setRate(qps);
        }
        return limiter;
    }

    public static void limitStockxBatch(String accountName, int itemCount) {
        String key = accountName != null ? accountName : "_global";
        int limit = 500;
        if (accountName != null) {
            StockXAccount account = StockXConfig.getAccount(accountName);
            if (account != null) {
                limit = account.getBatchItemLimit();
            }
        }
        int finalLimit = limit;
        BatchWindow window = STOCKX_BATCH_WINDOWS.computeIfAbsent(key, k -> new BatchWindow(finalLimit));
        while (!window.tryAcquire(itemCount)) {
            long waitMs = window.waitTimeMs();
            log.info("Batch限流[{}]: 5分钟内已达{}条上限，等待{}秒", key, window.limit, waitMs / 1000);
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
