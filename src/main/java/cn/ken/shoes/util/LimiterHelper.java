package cn.ken.shoes.util;


import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class LimiterHelper {

    private static final RateLimiter KC_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_ITEM_LIMITER = RateLimiter.create(10);
    private static final RateLimiter POISON_PRICE_LIMITER = RateLimiter.create(3);
    private static final RateLimiter DUNK_PRICE_LIMITER = RateLimiter.create(1);
    private static final RateLimiter DUNK_SALES_LIMITER = RateLimiter.create(10);

    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_GRAPHQL_LIMITERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_API_LIMITERS = new ConcurrentHashMap<>();
    /** 批量写(压价/上架/下架)匀速限速器: 把 batchItemLimit 件均匀铺满5分钟, 避免翻斗窗口瞬间突发撞穿 StockX 500/5min。 */
    private static final ConcurrentHashMap<String, RateLimiter> STOCKX_BATCH_LIMITERS = new ConcurrentHashMap<>();

    private static final RateLimiter STOCKX_GLOBAL_GRAPHQL_LIMITER = RateLimiter.create(1);
    private static final RateLimiter STOCKX_GLOBAL_API_LIMITER = RateLimiter.create(1);
    /** 5分钟窗口秒数, 用于把 batchItemLimit 换算成匀速 permits/秒 */
    private static final double BATCH_WINDOW_SEC = 300.0;
    /** 匀速留 5% 余量, 抗客户端与 StockX 窗口的时钟/计数偏差 */
    private static final double BATCH_SAFETY = 0.95;

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
        if (itemCount <= 0) {
            return;
        }
        String key = accountName != null ? accountName : "_global";
        int limit = 500;
        if (accountName != null) {
            StockXAccount account = StockXConfig.getAccount(accountName);
            if (account != null) {
                limit = account.getBatchItemLimit();
            }
        }
        // 匀速: batchItemLimit 件铺满5分钟(留5%余量), 使任意滑动5分钟窗口内不超过上限, 从构造上不撞 StockX,
        // 而不是翻斗窗口那样窗口内瞬间打满再空转(交界处会被 StockX 的滑动窗看成近2倍而撞穿)。
        double permitsPerSec = limit * BATCH_SAFETY / BATCH_WINDOW_SEC;
        double waited = getOrUpdateLimiter(STOCKX_BATCH_LIMITERS, key, permitsPerSec).acquire(itemCount);
        if (waited > 1.0) {
            log.info("Batch匀速限速[{}]: {}件, 等待{}秒 (上限{}件/5min)", key, itemCount, String.format("%.1f", waited), limit);
        }
    }

    public static void limitDunkPrice() {
        DUNK_PRICE_LIMITER.acquire();
    }

    public static void listDunkSales() {
        DUNK_SALES_LIMITER.acquire();
    }
}
