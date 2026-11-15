package cn.ken.shoes.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trading API 每日额度保护与调用统计。
 *
 * <p>eBay 按应用给 Trading API 一个共用的每日额度（GetOrders、GetSellerList、ReviseInventoryStatus、
 * CompleteSale 等都从同一个池子扣，按请求次数算，与一次请求里带多少条数据无关），
 * 在美国太平洋时间零点重置。额度用完后 eBay 返回错误 518，重试没有意义。
 *
 * <p>这里做两件事：
 * <ul>
 *     <li><b>熔断</b>：任何一次调用拿到 518，就暂停本进程所有 Trading 调用到额度重置，
 *     期间直接抛 {@link EbayQuotaExceededException}，不再发请求；</li>
 *     <li><b>统计</b>：按「太平洋时间日期 + 接口名」记录实际发出的请求次数（含重试）、失败次数，
 *     跨天时把前一天的统计打到日志里。</li>
 * </ul>
 */
@Slf4j
@Component
public class EbayTradingQuotaGuard {

    public static final String QUOTA_ERROR_CODE = "518";
    /** eBay 的日额度按美国太平洋时间零点重置。 */
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Clock clock;
    private final Map<String, CallStats> stats = new ConcurrentHashMap<>();
    private volatile LocalDate statsDay;
    private volatile Instant blockedUntil;
    private volatile String blockedBy;

    @Autowired
    public EbayTradingQuotaGuard() {
        this(Clock.systemUTC());
    }

    EbayTradingQuotaGuard(Clock clock) {
        this.clock = clock;
        this.statsDay = today();
    }

    /** 额度已用完时直接抛异常，不发请求。 */
    public void checkAvailable(String callName) {
        Instant until = blockedUntil;
        if (until == null) {
            return;
        }
        if (!clock.instant().isBefore(until)) {
            clearBlock("额度重置时间已到");
            return;
        }
        stats(callName).skipped.incrementAndGet();
        throw new EbayQuotaExceededException(quotaMessage(callName, until, true));
    }

    /** 每个实际发出的 HTTP 请求（含重试）记一次。 */
    public void recordRequest(String callName) {
        stats(callName).requests.incrementAndGet();
    }

    public void recordFailure(String callName) {
        stats(callName).failures.incrementAndGet();
    }

    /** 收到 518：暂停全部 Trading 调用到下一个太平洋时间零点，返回给调用方的异常。 */
    public EbayQuotaExceededException tripped(String callName) {
        Instant until = nextReset();
        stats(callName).quotaErrors.incrementAndGet();
        boolean firstTrip = blockedUntil == null || blockedUntil.isBefore(until);
        blockedUntil = until;
        blockedBy = callName;
        if (firstTrip) {
            log.warn("eBay Trading API日额度已用完(518), 触发接口:{}, 暂停所有Trading调用至北京时间{}, 今日本进程调用统计:{}",
                    callName, DISPLAY.format(until.atZone(DISPLAY_ZONE)), summary());
        }
        return new EbayQuotaExceededException(quotaMessage(callName, until, false));
    }

    /** eBay 侧确认还有余量（例如提额后或重置时间提前）时手动解除。 */
    public void clearBlock(String reason) {
        if (blockedUntil != null) {
            log.info("eBay Trading API额度熔断解除, 原因:{}", reason);
        }
        blockedUntil = null;
        blockedBy = null;
    }

    public Instant getBlockedUntil() {
        Instant until = blockedUntil;
        return until != null && clock.instant().isBefore(until) ? until : null;
    }

    public String getBlockedBy() {
        return getBlockedUntil() == null ? null : blockedBy;
    }

    public LocalDate getStatsDay() {
        today();
        return statsDay;
    }

    /** 当天（太平洋时间）各接口的调用统计，按接口名排序。 */
    public Map<String, Map<String, Long>> snapshot() {
        today();
        Map<String, Map<String, Long>> result = new TreeMap<>();
        stats.forEach((name, s) -> {
            Map<String, Long> row = new LinkedHashMap<>();
            row.put("requests", s.requests.get());
            row.put("failures", s.failures.get());
            row.put("quotaErrors", s.quotaErrors.get());
            row.put("skippedByGuard", s.skipped.get());
            result.put(name, row);
        });
        return result;
    }

    public long totalRequests() {
        today();
        return stats.values().stream().mapToLong(s -> s.requests.get()).sum();
    }

    Instant nextReset() {
        return today().plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
    }

    static String display(Instant instant) {
        return instant == null ? null : DISPLAY.format(instant.atZone(DISPLAY_ZONE));
    }

    private CallStats stats(String callName) {
        today();
        return stats.computeIfAbsent(callName == null ? "unknown" : callName, ignored -> new CallStats());
    }

    /** 太平洋时间跨天时把前一天的统计写日志并清零。 */
    private LocalDate today() {
        LocalDate now = LocalDate.now(clock.withZone(QUOTA_ZONE));
        LocalDate current = statsDay;
        if (current != null && !now.equals(current)) {
            synchronized (this) {
                if (!now.equals(statsDay)) {
                    if (!stats.isEmpty()) {
                        log.info("eBay Trading API调用统计(太平洋时间{}): {}", statsDay, summary());
                    }
                    stats.clear();
                    statsDay = now;
                }
            }
        }
        return now;
    }

    private String summary() {
        StringBuilder text = new StringBuilder();
        long total = 0;
        for (Map.Entry<String, CallStats> entry : new TreeMap<>(stats).entrySet()) {
            CallStats s = entry.getValue();
            total += s.requests.get();
            text.append(entry.getKey()).append('=').append(s.requests.get());
            if (s.failures.get() > 0) {
                text.append("(失败").append(s.failures.get()).append(')');
            }
            text.append(", ");
        }
        return "total=" + total + (text.isEmpty() ? "" : ", " + text.substring(0, text.length() - 2));
    }

    private static String quotaMessage(String callName, Instant until, boolean local) {
        return "eBay " + callName + "失败：" + QUOTA_ERROR_CODE + " Trading API今日调用额度已用完"
                + (local ? "(已暂停调用)" : "") + "，预计北京时间" + display(until) + "恢复";
    }

    private static final class CallStats {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong quotaErrors = new AtomicLong();
        private final AtomicLong skipped = new AtomicLong();
    }
}
