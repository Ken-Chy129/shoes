package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * 单任务内缓存买家侧成交页；没有覆盖订单时间的查询不能用于判定补单。
 * 每个 variant/币种最多翻 {@link #DEFAULT_MAX_PAGES} 页，超出仍未覆盖订单时间则保留未判定。
 */
@Slf4j
public final class StockXPendingOrderClassifier {
    public static final int DEFAULT_MAX_PAGES = 30;

    @FunctionalInterface
    public interface SalesPageQuery {
        /** pageNumber 从 1 开始，仅用于日志与重试提示。 */
        JSONObject query(String variantId, String currencyCode, String after, int pageNumber);
    }

    private final SalesPageQuery query;
    private final Runnable ensureNotCancelled;
    private final int maxPages;
    private final Map<MarketKey, History> histories = new HashMap<>();

    public StockXPendingOrderClassifier(SalesPageQuery query, Runnable ensureNotCancelled) {
        this(query, ensureNotCancelled, DEFAULT_MAX_PAGES);
    }

    public StockXPendingOrderClassifier(SalesPageQuery query, Runnable ensureNotCancelled, int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages必须大于0");
        }
        this.query = query;
        this.ensureNotCancelled = ensureNotCancelled;
        this.maxPages = maxPages;
    }

    public void enrich(TaskItemDO item) {
        ensureNotCancelled.run();
        item.setReplenishmentOrder(null);
        if (StrUtil.isBlank(item.getProductId()) || item.getSoldOn() == null
                || item.getSalePrice() == null || item.getSalePrice().signum() <= 0
                || item.getCurrencyCode() == null || !item.getCurrencyCode().matches("[A-Z]{3}")) {
            return;
        }
        MarketKey market = new MarketKey(item.getProductId(), item.getCurrencyCode());
        SaleKey target = new SaleKey(minute(item.getSoldOn().toInstant()),
                item.getSalePrice().stripTrailingZeros());
        History history = histories.computeIfAbsent(market, ignored -> new History());
        // 同一 UTC 分钟可能跨页，只有读到更早的一分钟或全部读完，才能确认没有匹配。
        while (!history.sales.contains(target) && !history.exhausted && !history.failed
                && (history.oldest == null || minute(history.oldest) >= target.minute())) {
            if (history.pages >= maxPages) {
                if (!history.limitLogged) {
                    history.limitLogged = true;
                    log.warn("StockX待处理订单补单判定达到翻页上限, taskId:{}, variantId:{}, currency:{}, maxPages:{}",
                            item.getTaskId(), market.variantId(), market.currencyCode(), maxPages);
                }
                break;
            }
            ensureNotCancelled.run();
            int pageNumber = history.pages + 1;
            try {
                JSONObject page = query.query(market.variantId(), market.currencyCode(), history.after, pageNumber);
                ensureNotCancelled.run();
                history.append(page);
            } catch (RuntimeException e) {
                ensureNotCancelled.run();
                history.failed = true;
                log.warn("StockX待处理订单补单判定未完成, taskId:{}, variantId:{}, currency:{}, page:{}, errorType:{}",
                        item.getTaskId(), market.variantId(), market.currencyCode(), pageNumber,
                        e.getClass().getSimpleName());
            }
        }
        if (history.sales.contains(target)) {
            item.setReplenishmentOrder(false);
        } else if (!history.failed && history.covers(target.minute())) {
            item.setReplenishmentOrder(true);
        }
    }

    private record MarketKey(String variantId, String currencyCode) {}
    private static long minute(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), 60);
    }

    private record SaleKey(long minute, BigDecimal amount) {}

    private static final class History {
        private final Set<SaleKey> sales = new HashSet<>();
        private final Set<String> cursors = new HashSet<>();
        private String after;
        private Instant oldest;
        private Long total;
        private long readCount;
        private int pages;
        private boolean exhausted;
        private boolean failed;
        private boolean limitLogged;

        /** 已读完全部记录，或已读到早于目标分钟的记录，才算覆盖了订单时间。 */
        private boolean covers(long targetMinute) {
            return exhausted || (oldest != null && minute(oldest) < targetMinute);
        }

        private void append(JSONObject page) {
            JSONArray edges = page == null ? null : page.getJSONArray("edges");
            JSONObject pageInfo = page == null ? null : page.getJSONObject("pageInfo");
            if (edges == null || pageInfo == null || !(pageInfo.get("total") instanceof Number)) {
                throw new IllegalStateException("成交记录缺少完整分页数据");
            }
            long pageTotal = pageInfo.getBigDecimal("total").longValueExact();
            long newCount = readCount + edges.size();
            if (pageTotal < 0 || (total != null && total != pageTotal) || newCount > pageTotal
                    || (edges.isEmpty() && newCount < pageTotal)) {
                throw new IllegalStateException("成交记录分页总数不一致");
            }
            List<SaleKey> parsed = new ArrayList<>();
            Instant last = oldest;
            for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                JSONObject node = edge == null ? null : edge.getJSONObject("node");
                BigDecimal amount = node == null ? null : node.getBigDecimal("amount");
                String time = node == null ? null : node.getString("createdAt");
                if (amount == null || amount.signum() <= 0 || StrUtil.isBlank(time)) {
                    throw new IllegalStateException("成交记录缺少有效时间或价格");
                }
                Instant createdAt = Instant.parse(time);
                if (last != null && createdAt.isAfter(last)) {
                    throw new IllegalStateException("成交记录未按时间倒序返回");
                }
                parsed.add(new SaleKey(minute(createdAt), amount.stripTrailingZeros()));
                last = createdAt;
            }
            boolean complete = newCount == pageTotal;
            String next = pageInfo.getString("endCursor");
            if (!complete && (StrUtil.isBlank(next) || !cursors.add(next))) {
                throw new IllegalStateException("成交记录分页游标无效");
            }
            sales.addAll(parsed);
            oldest = last;
            after = next;
            readCount = newCount;
            total = pageTotal;
            pages++;
            exhausted = complete;
        }
    }
}
