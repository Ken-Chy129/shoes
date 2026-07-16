package cn.ken.shoes.service;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class StockXShippingExtensionService {

    private final StockXClient stockXClient;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public StockXShippingExtensionService(StockXClient stockXClient) {
        this(stockXClient, Clock.systemUTC());
    }

    StockXShippingExtensionService(StockXClient stockXClient, Clock clock) {
        this.stockXClient = stockXClient;
        this.clock = clock;
    }

    public void extendAllEnabledAccounts() {
        if (!running.compareAndSet(false, true)) {
            log.info("StockX自动延期任务已在运行，跳过本次重复触发");
            return;
        }
        try {
            extendAccounts(StockXConfig.getEnabledAccounts());
        } finally {
            running.set(false);
        }
    }

    void extendAccounts(List<StockXAccount> accounts) {
        for (StockXAccount account : accounts) {
            try {
                ExtensionSummary summary = extendPendingOrdersForAccount(account);
                log.info("StockX自动延期[{}]完成: scanned={}, alreadyExtended={}, skipped={}, extended={}, failed={}",
                        account.getName(), summary.scanned(), summary.alreadyExtended(), summary.skipped(),
                        summary.extended(), summary.failed());
            } catch (Exception e) {
                log.error("StockX自动延期[{}]异常，继续处理其他账号: {}", account.getName(), e.getMessage(), e);
            }
        }
    }

    public ExtensionSummary extendPendingOrdersForAccount(StockXAccount account) {
        int scanned = 0;
        int alreadyExtended = 0;
        int skipped = 0;
        int extended = 0;
        int failed = 0;
        String after = null;
        Set<String> processedAskIds = new HashSet<>();

        while (true) {
            JSONObject asks = stockXClient.queryPendingAsks(after, account);
            if (asks == null) {
                throw new IllegalStateException("查询待处理订单无响应");
            }
            if (asks.getBooleanValue("_unauthorized")) {
                throw new IllegalStateException("TOKEN_EXPIRED");
            }

            JSONArray edges = asks.getJSONArray("edges");
            if (edges != null) {
                for (Object edgeValue : edges) {
                    if (!(edgeValue instanceof JSONObject edge)) {
                        skipped++;
                        continue;
                    }
                    JSONObject node = edge.getJSONObject("node");
                    if (node == null) {
                        skipped++;
                        continue;
                    }
                    scanned++;
                    if (node.getBooleanValue("shippingExtensionRequested")) {
                        alreadyExtended++;
                        continue;
                    }
                    if (isShipDateTodayOrEarlier(node.getString("dateToShipBy"))) {
                        skipped++;
                        continue;
                    }

                    String askId = node.getString("id");
                    String orderNumber = node.getString("orderNumber");
                    if (StrUtil.isBlank(askId) || StrUtil.isBlank(orderNumber) || !processedAskIds.add(askId)) {
                        skipped++;
                        continue;
                    }

                    try {
                        if (stockXClient.extendShipDate(orderNumber, askId, account)) {
                            extended++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        if (e instanceof IllegalStateException && "TOKEN_EXPIRED".equals(e.getMessage())) {
                            throw e;
                        }
                        failed++;
                        log.error("StockX自动延期[{}]单笔失败, askId:{}, reason:{}",
                                account.getName(), askId, e.getMessage());
                    }
                }
            }

            JSONObject pageInfo = asks.getJSONObject("pageInfo");
            boolean hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
            if (!hasNextPage) {
                break;
            }
            String nextCursor = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(nextCursor) || nextCursor.equals(after)) {
                throw new IllegalStateException("待处理订单分页游标无效");
            }
            after = nextCursor;
        }

        return new ExtensionSummary(scanned, alreadyExtended, skipped, extended, failed);
    }

    /** 与 StockX Pro 前端保持一致：发货截止日为 UTC 今天或更早时不再允许延期。 */
    private boolean isShipDateTodayOrEarlier(String dateToShipBy) {
        if (StrUtil.isBlank(dateToShipBy)) {
            return false;
        }
        try {
            LocalDate shipDate = Instant.parse(dateToShipBy).atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate today = LocalDate.now(clock);
            return !shipDate.isAfter(today);
        } catch (Exception e) {
            log.warn("无法解析StockX发货截止日，交由延期接口判定: {}", dateToShipBy);
            return false;
        }
    }

    public record ExtensionSummary(int scanned, int alreadyExtended, int skipped, int extended, int failed) {
    }
}
