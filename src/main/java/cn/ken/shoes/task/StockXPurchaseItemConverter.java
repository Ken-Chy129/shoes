package cn.ken.shoes.task;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.common.StockXPurchaseOperation;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

/** 将 StockX 买家侧出价/订单节点转换为通用任务明细。 */
public final class StockXPurchaseItemConverter {

    private StockXPurchaseItemConverter() {
    }

    public static TaskItemDO convert(Long taskId, JSONObject node, StockXPurchaseOperation operation) {
        return convert(taskId, node, operation, null);
    }

    public static TaskItemDO convert(Long taskId, JSONObject node, StockXPurchaseOperation operation,
                                     JSONObject market) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(1);

        JSONObject variant = node.getJSONObject("productVariant");
        JSONObject product = variant != null ? variant.getJSONObject("product") : null;
        if (variant != null) {
            item.setProductId(variant.getString("id"));
            JSONObject traits = variant.getJSONObject("traits");
            if (traits != null) {
                item.setSize(traits.getString("size"));
            }
            item.setEuSize(extractEuSize(variant.getJSONObject("sizeChart")));
        }
        if (product != null) {
            item.setTitle(product.getString("title"));
            item.setStyleId(product.getString("styleId"));
            item.setBrand(extractBrand(product));
        }

        if (operation == StockXPurchaseOperation.BIDS) {
            item.setListingId(node.getString("id"));
            item.setCurrentPrice(parseDecimal(node.getString("amount")));
            item.setCurrencyCode(node.getString("currencyCode"));
            item.setOrderStatus("有效出价");
            item.setOperateResult("有效出价");
            item.setOperateTime(parseDate(node.getString("creationDate")));
            applyBidMarketData(item, market);
        } else {
            item.setListingId(node.getString("chainId"));
            item.setOrderNumber(node.getString("orderNumber"));
            item.setSalePrice(parseDecimal(node.getString("amount")));
            item.setCurrencyCode(node.getString("currencyCode"));
            JSONObject state = node.getJSONObject("state");
            String status = state != null
                    ? StrUtil.blankToDefault(state.getString("statusTitle"), state.getString("statusKey"))
                    : null;
            item.setOrderStatus(status);
            item.setOperateResult(status);
            Date purchaseDate = parseDate(node.getString("purchaseDate"));
            item.setSoldOn(purchaseDate);
            item.setOperateTime(purchaseDate);
        }
        return item;
    }

    private static void applyBidMarketData(TaskItemDO item, JSONObject market) {
        if (market == null) {
            return;
        }
        JSONObject state = market.getJSONObject("state");
        JSONObject levels = state != null ? state.getJSONObject("askServiceLevels") : null;
        item.setLowestPrice(lowestAmount(levels, "standard"));
        item.setFlexLowestPrice(lowestAmount(levels, "expressStandard"));

        JSONObject priceLevels = market.getJSONObject("priceLevels");
        JSONArray edges = priceLevels != null ? priceLevels.getJSONArray("edges") : null;
        if (edges == null) {
            return;
        }
        if (!edges.isEmpty()) {
            JSONObject first = edges.getJSONObject(0).getJSONObject("node");
            if (first != null) {
                item.setHighestBidPrice(parseDecimal(first.getString("amount")));
                item.setHighestBidCount(first.getInteger("count"));
            }
        }
        if (edges.size() > 1) {
            JSONObject second = edges.getJSONObject(1).getJSONObject("node");
            if (second != null) {
                item.setSecondHighestBidPrice(parseDecimal(second.getString("amount")));
                item.setSecondHighestBidCount(second.getInteger("count"));
            }
        }
    }

    private static BigDecimal lowestAmount(JSONObject levels, String serviceLevel) {
        JSONObject level = levels != null ? levels.getJSONObject(serviceLevel) : null;
        JSONObject lowest = level != null ? level.getJSONObject("lowest") : null;
        return lowest != null ? parseDecimal(lowest.getString("amount")) : null;
    }

    private static String extractBrand(JSONObject product) {
        JSONArray traits = product.getJSONArray("traits");
        if (traits == null) {
            return null;
        }
        for (JSONObject trait : traits.toJavaList(JSONObject.class)) {
            if ("Brand".equalsIgnoreCase(trait.getString("name"))) {
                return trait.getString("value");
            }
        }
        return null;
    }

    private static String extractEuSize(JSONObject sizeChart) {
        if (sizeChart == null) {
            return null;
        }
        JSONArray displayOptions = sizeChart.getJSONArray("displayOptions");
        if (displayOptions == null) {
            return null;
        }
        for (JSONObject option : displayOptions.toJavaList(JSONObject.class)) {
            String size = option.getString("size");
            if (size != null && size.startsWith("EU ")) {
                return size.substring(3).trim();
            }
        }
        return null;
    }

    private static BigDecimal parseDecimal(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Date parseDate(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Date.from(Instant.parse(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
