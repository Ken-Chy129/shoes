package cn.ken.shoes.task;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.util.BrandUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

public final class StockXOrderItemConverter {

    private StockXOrderItemConverter() {
    }

    public static TaskItemDO convert(Long taskId, JSONObject order, StockXOrderCategory category) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(order.getString("id"));

        JSONObject associatedOrders = order.getJSONObject("associatedOrders");
        JSONObject standardizedSellOrder = associatedOrders != null
                ? associatedOrders.getJSONObject("standardizedSellOrder") : null;
        if (standardizedSellOrder != null) {
            item.setOrderNumber(standardizedSellOrder.getString("orderNumber"));
        }

        JSONObject productVariant = order.getJSONObject("productVariant");
        JSONObject product = productVariant != null ? productVariant.getJSONObject("product") : null;
        if (product != null) {
            item.setTitle(product.getString("title"));
            item.setStyleId(product.getString("styleId"));
            item.setBrand(BrandUtil.extractStockXBrand(product.getString("title")));
        }

        if (productVariant != null) {
            item.setProductId(productVariant.getString("id"));
            JSONObject traits = productVariant.getJSONObject("traits");
            if (traits != null) {
                item.setSize(traits.getString("size"));
            }
            item.setEuSize(extractEuSize(productVariant.getJSONObject("sizeChart")));
        }

        item.setSalePrice(parseDecimal(order.getString("amount")));
        item.setCurrencyCode(order.getString("currency"));
        item.setOrderStatus(category.getDisplayStatus());
        item.setOperateResult(category.getDisplayStatus());

        Date soldOn = parseDate(order.getString("soldOn"));
        item.setSoldOn(soldOn);
        item.setOperateTime(soldOn);
        return item;
    }

    public static TaskItemDO convertPending(Long taskId, JSONObject ask) {
        TaskItemDO item = convert(taskId, ask, StockXOrderCategory.PENDING);
        item.setOrderNumber(ask.getString("orderNumber"));
        item.setCurrencyCode(ask.getString("currentCurrency"));
        item.setOperateTime(parseDate(ask.getString("dateToShipBy")));
        item.setOperateResult(ask.getBooleanValue("shippingExtensionRequested") ? "已延期" : "未延期");
        return item;
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
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
