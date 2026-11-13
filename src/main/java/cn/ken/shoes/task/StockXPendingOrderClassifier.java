package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 单任务内缓存买家侧成交页；没有覆盖订单时间的查询不能用于判定补单。 */
@Slf4j
public final class StockXPendingOrderClassifier {
    @FunctionalInterface
    public interface SalesPageQuery {
        JSONObject query(String variantId, String currencyCode, String after);
    }

    private final SalesPageQuery query;
    private final Runnable ensureNotCancelled;
    private final Map<MarketKey, History> histories = new HashMap<>();

    public StockXPendingOrderClassifier(SalesPageQuery query, Runnable ensureNotCancelled) {
        this.query = query;
        this.ensureNotCancelled = ensureNotCancelled;
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
        SaleKey target = new SaleKey(item.getSoldOn().toInstant().getEpochSecond(),
                item.getSalePrice().stripTrailingZeros());
        History history = histories.computeIfAbsent(market, ignored -> new History());
        // 同一秒可能跨页，只有读到更早的一秒或全部读完，才能确认没有匹配。
        while (!history.sales.contains(target) && !history.exhausted && !history.failed
                && (history.oldest == null || history.oldest.getEpochSecond() >= target.second())) {
            ensureNotCancelled.run();
            try {
                JSONObject page = query.query(market.variantId(), market.currencyCode(), history.after);
                ensureNotCancelled.run();
                history.append(page);
            } catch (RuntimeException e) {
                ensureNotCancelled.run();
                history.failed = true;
                log.warn("StockX待处理订单补单判定未完成, taskId:{}, variantId:{}, currency:{}, errorType:{}",
                        item.getTaskId(), market.variantId(), market.currencyCode(), e.getClass().getSimpleName());
            }
        }
        if (history.sales.contains(target)) {
            item.setReplenishmentOrder(false);
        } else if (!history.failed) {
            item.setReplenishmentOrder(true);
        }
    }

    private record MarketKey(String variantId, String currencyCode) {}
    private record SaleKey(long second, BigDecimal amount) {}

    private static final class History {
        private final Set<SaleKey> sales = new HashSet<>();
        private final Set<String> cursors = new HashSet<>();
        private String after;
        private Instant oldest;
        private Long total;
        private long readCount;
        private boolean exhausted;
        private boolean failed;

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
                parsed.add(new SaleKey(createdAt.getEpochSecond(), amount.stripTrailingZeros()));
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
            exhausted = complete;
        }
    }
}
