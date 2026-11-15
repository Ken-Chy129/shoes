package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayRateLimitApiClient;
import cn.ken.shoes.client.EbayTradingQuotaGuard;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Trading API 额度查询：合并 eBay 官方 getRateLimits（权威的已用/剩余次数）和本进程的调用统计与熔断状态。
 */
@Slf4j
@Service
public class EbayTradingQuotaService {

    private static final String TRADING_API = "TradingAPI";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final EbayRateLimitApiClient rateLimitClient;
    private final EbayTradingQuotaGuard guard;

    public EbayTradingQuotaService(EbayRateLimitApiClient rateLimitClient, EbayTradingQuotaGuard guard) {
        this.rateLimitClient = rateLimitClient;
        this.guard = guard;
    }

    public JSONObject status() {
        JSONObject result = new JSONObject(true);
        JSONObject ebay = new JSONObject(true);
        try {
            List<EbayRateLimitApiClient.ResourceRate> rates = rateLimitClient.getRateLimits(TRADING_API);
            // 大部分接口共用一个日额度池：取最常见的 limit 作为共用额度，其余单独额度的接口另列。
            EbayRateLimitApiClient.ResourceRate shared = rates.stream()
                    .filter(rate -> rate.limit() > 0)
                    .collect(java.util.stream.Collectors.groupingBy(EbayRateLimitApiClient.ResourceRate::limit,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                    .flatMap(entry -> rates.stream().filter(rate -> rate.limit() == entry.getKey()).findFirst())
                    .orElse(null);
            if (shared != null) {
                ebay.fluentPut("sharedLimit", shared.limit())
                        .fluentPut("sharedRemaining", shared.remaining())
                        .fluentPut("sharedUsed", shared.limit() - shared.remaining())
                        .fluentPut("resetAt", shared.reset() == null ? null : DISPLAY.format(shared.reset()));
                syncGuard(shared);
            }
            JSONArray used = new JSONArray();
            for (EbayRateLimitApiClient.ResourceRate rate : rates) {
                if (rate.count() <= 0 && (shared == null || rate.limit() == shared.limit())) {
                    continue;
                }
                used.add(new JSONObject(true)
                        .fluentPut("name", rate.name())
                        .fluentPut("count", rate.count())
                        .fluentPut("limit", rate.limit())
                        .fluentPut("remaining", rate.remaining())
                        .fluentPut("sharedPool", shared != null && rate.limit() == shared.limit()));
            }
            ebay.fluentPut("calls", used);
        } catch (RuntimeException e) {
            log.warn("查询eBay Trading API额度失败: {}", e.getMessage());
            ebay.fluentPut("error", e.getMessage());
        }
        result.put("ebay", ebay);

        Instant blockedUntil = guard.getBlockedUntil();
        result.put("local", new JSONObject(true)
                .fluentPut("blocked", blockedUntil != null)
                .fluentPut("blockedUntil", blockedUntil == null ? null : DISPLAY.format(blockedUntil))
                .fluentPut("blockedBy", guard.getBlockedBy())
                .fluentPut("statsDayPacific", String.valueOf(guard.getStatsDay()))
                .fluentPut("totalRequests", guard.totalRequests())
                .fluentPut("calls", guard.snapshot()));
        return result;
    }

    /** eBay 侧显示仍有余量时解除本地熔断（例如提额后）；显示已用完时同步熔断，避免继续白白调用。 */
    private void syncGuard(EbayRateLimitApiClient.ResourceRate shared) {
        if (shared.remaining() > 0 && guard.getBlockedUntil() != null) {
            guard.clearBlock("eBay getRateLimits显示剩余" + shared.remaining() + "次");
        } else if (shared.remaining() <= 0 && guard.getBlockedUntil() == null) {
            guard.tripped("getRateLimits");
        }
    }
}
