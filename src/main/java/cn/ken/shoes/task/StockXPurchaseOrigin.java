package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** 单次任务、单个账号内的购买来源索引。没有来源时不查询，不能用货号/尺码猜配。 */
public final class StockXPurchaseOrigin {
    private final Function<String, JSONObject> queryHistoryPage;
    private final Runnable ensureNotCancelled;
    private Map<String, PurchasePrice> prices;

    public StockXPurchaseOrigin(Function<String, JSONObject> queryHistoryPage, Runnable ensureNotCancelled) {
        this.queryHistoryPage = queryHistoryPage;
        this.ensureNotCancelled = ensureNotCancelled;
    }

    public static String orderNumber(JSONObject listing) {
        JSONObject orders = listing.getJSONObject("associatedOrders");
        JSONObject intake = orders != null ? orders.getJSONObject("flexIntakeOrder") : null;
        JSONArray sources = intake != null ? intake.getJSONArray("sources") : null;
        String orderNumber = null;
        if (sources != null) {
            for (JSONObject source : sources.toJavaList(JSONObject.class)) {
                if (source == null || !"BUY_INTO_FLEX".equals(source.getString("type"))
                        || StrUtil.isBlank(source.getString("id"))) {
                    continue;
                }
                String id = source.getString("id").trim();
                if (orderNumber != null && !orderNumber.equals(id)) {
                    throw new IllegalStateException("StockX购买来源包含多个不同订单号");
                }
                orderNumber = id;
            }
        }
        return orderNumber;
    }

    public void enrich(TaskItemDO item) {
        ensureNotCancelled.run();
        if (StrUtil.isBlank(item.getPurchaseOrderNumber())) {
            return;
        }
        if (prices == null) {
            prices = loadHistory();
        }
        PurchasePrice price = prices.get(item.getPurchaseOrderNumber());
        if (price != null) {
            item.setPurchasePrice(price.amount());
            item.setPurchaseCurrencyCode(price.currency());
        }
    }

    private Map<String, PurchasePrice> loadHistory() {
        Map<String, PurchasePrice> result = new HashMap<>();
        Set<String> cursors = new HashSet<>();
        String after = null;
        do {
            ensureNotCancelled.run();
            JSONObject page = queryHistoryPage.apply(after);
            if (page != null && page.getBooleanValue("_unauthorized")) {
                throw new IllegalStateException("获取购买历史失败：StockX Token已过期，请更新Token");
            }
            JSONArray edges = page != null ? page.getJSONArray("edges") : null;
            JSONObject pageInfo = page != null ? page.getJSONObject("pageInfo") : null;
            if (edges == null || pageInfo == null || !(pageInfo.get("hasNextPage") instanceof Boolean)) {
                throw new IllegalStateException("获取购买历史失败：响应缺少完整分页数据");
            }
            for (JSONObject edge : edges.toJavaList(JSONObject.class)) {
                ensureNotCancelled.run();
                JSONObject node = edge != null ? edge.getJSONObject("node") : null;
                if (node == null) {
                    throw new IllegalStateException("获取购买历史失败：记录为空");
                }
                String id = StrUtil.blankToDefault(node.getString("orderId"), node.getString("orderNumber"));
                if (StrUtil.isBlank(id)) {
                    throw new IllegalStateException("获取购买历史失败：记录缺少订单号");
                }
                BigDecimal amount = node.getBigDecimal("amount");
                if (amount != null && (amount.signum() < 0 || amount.compareTo(new BigDecimal("9999999999.99")) > 0)) {
                    throw new IllegalStateException("获取购买历史失败：购买价格无效");
                }
                PurchasePrice price = new PurchasePrice(amount != null ? amount.stripTrailingZeros() : null,
                        node.getString("currencyCode"));
                PurchasePrice previous = result.putIfAbsent(id.trim(), price);
                if (previous != null && !previous.equals(price)) {
                    throw new IllegalStateException("获取购买历史失败：同一订单存在不同购买价格");
                }
            }
            if (!pageInfo.getBooleanValue("hasNextPage")) {
                return result;
            }
            after = pageInfo.getString("endCursor");
            if (StrUtil.isBlank(after) || !cursors.add(after)) {
                throw new IllegalStateException("获取购买历史失败：分页游标无效");
            }
        } while (true);
    }

    private record PurchasePrice(BigDecimal amount, String currency) {}
}
