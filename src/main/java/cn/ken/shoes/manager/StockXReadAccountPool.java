package cn.ken.shoes.manager;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.StockXRateLimitGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 同市场 StockX 账号的只读请求池。
 * <p>
 * 目录搜索、商品详情和市场价只依赖 selected-country，可共享同国家账号的请求额度；
 * 卖家挂单、订单、上架、改价和下架等账号相关操作不得使用本池。
 */
public class StockXReadAccountPool {

    public static final long DEFAULT_READ_COOLDOWN_MS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, AtomicInteger> marketCursors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> readCooldownUntil = new ConcurrentHashMap<>();
    private final LongSupplier now;
    private final long readCooldownMs;

    public StockXReadAccountPool() {
        this(System::currentTimeMillis, DEFAULT_READ_COOLDOWN_MS);
    }

    StockXReadAccountPool(LongSupplier now, long readCooldownMs) {
        this.now = now;
        this.readCooldownMs = readCooldownMs;
    }

    /**
     * 返回本次请求的候选账号，首个账号按市场轮转，其余账号用于即时故障转移。
     */
    public List<StockXAccount> candidates(String country, StockXAccount preferredAccount) {
        return selection(country, preferredAccount).candidates();
    }

    /**
     * 同时保留“该市场是否配置过账号”，避免把“未配置”和“全部冷却”都误判为空池。
     */
    public Selection selection(String country, StockXAccount preferredAccount) {
        String market = normalizeMarket(country, preferredAccount);
        List<StockXAccount> configured = StockXConfig.getEnabledAccounts().stream()
                .filter(this::hasReadCredential)
                .filter(account -> market.equals(normalizeMarket(account.getCountry(), account)))
                .toList();
        boolean configuredMarketPool = !configured.isEmpty();

        List<StockXAccount> pool = new ArrayList<>(configured);
        if (pool.isEmpty() && hasReadCredential(preferredAccount)) {
            // 兼容旧配置和测试：尚未启用多账号池时仍沿用任务原账号。
            pool.add(preferredAccount);
        }

        List<StockXAccount> available = pool.stream()
                .filter(account -> !isCoolingDown(account.getName()))
                .toList();
        if (available.size() <= 1) {
            return new Selection(available, configuredMarketPool);
        }

        int start = Math.floorMod(
                marketCursors.computeIfAbsent(market, ignored -> new AtomicInteger()).getAndIncrement(),
                available.size());
        List<StockXAccount> ordered = new ArrayList<>(available.size());
        for (int i = 0; i < available.size(); i++) {
            ordered.add(available.get((start + i) % available.size()));
        }
        return new Selection(List.copyOf(ordered), configuredMarketPool);
    }

    public void markRateLimited(String accountName) {
        if (StrUtil.isNotBlank(accountName)) {
            readCooldownUntil.put(accountName, now.getAsLong() + readCooldownMs);
        }
    }

    public void markSuccess(String accountName) {
        if (StrUtil.isNotBlank(accountName)) {
            // 成功响应可能来自更早发出的并发请求，不能清除另一个请求刚设置的新冷却。
            readCooldownUntil.computeIfPresent(accountName,
                    (ignored, until) -> until <= now.getAsLong() ? null : until);
        }
    }

    public record Selection(List<StockXAccount> candidates, boolean configuredMarketPool) {
    }

    private boolean isCoolingDown(String accountName) {
        if (StrUtil.isBlank(accountName)) {
            return false;
        }
        Long until = readCooldownUntil.get(accountName);
        boolean readCooling = until != null && until > now.getAsLong();
        if (until != null && !readCooling) {
            readCooldownUntil.remove(accountName, until);
        }
        return readCooling || StockXRateLimitGuard.isCoolingDown(accountName);
    }

    private boolean hasReadCredential(StockXAccount account) {
        return account != null
                && StrUtil.isNotBlank(account.getName())
                && StrUtil.isNotBlank(account.getAuthorization());
    }

    private static String normalizeMarket(String country, StockXAccount fallback) {
        String value = StrUtil.isNotBlank(country)
                ? country
                : fallback != null ? fallback.getCountry() : null;
        return StrUtil.blankToDefault(value, "US").trim().toUpperCase(Locale.ROOT);
    }
}
