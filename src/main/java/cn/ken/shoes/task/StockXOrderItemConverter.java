package cn.ken.shoes.task;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.util.BrandUtil;
import cn.ken.shoes.util.SizeConvertUtil;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

public final class StockXOrderItemConverter {

    private StockXOrderItemConverter() {
    }

    public static TaskItemDO convert(Long taskId, JSONObject order) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setListingId(order.getString("listingId"));
        item.setOrderNumber(order.getString("orderNumber"));

        JSONObject product = order.getJSONObject("product");
        if (product != null) {
            item.setTitle(product.getString("productName"));
            item.setStyleId(product.getString("styleId"));
            item.setBrand(BrandUtil.extractStockXBrand(product.getString("productName")));
        }

        JSONObject variant = order.getJSONObject("variant");
        if (variant != null) {
            item.setProductId(variant.getString("variantId"));
            item.setSize(variant.getString("variantValue"));
        }
        item.setEuSize(SizeConvertUtil.getStockXEuSize(item.getBrand(), item.getSize()));

        JSONObject payout = order.getJSONObject("payout");
        item.setSalePrice(parseDecimal(payout != null ? payout.getString("salePrice") : null));
        if (item.getSalePrice() == null) {
            item.setSalePrice(parseDecimal(order.getString("amount")));
        }
        item.setPayoutAmount(parseDecimal(payout != null ? payout.getString("totalPayout") : null));
        String currencyCode = payout != null ? payout.getString("currencyCode") : null;
        item.setCurrencyCode(currencyCode != null ? currencyCode : order.getString("currencyCode"));

        String displayStatus = StockXOrderCategory.displayStatus(order.getString("status"));
        item.setOrderStatus(displayStatus);
        item.setOperateResult(displayStatus);

        Date soldOn = parseDate(order.getString("createdAt"));
        item.setSoldOn(soldOn);
        item.setOperateTime(soldOn);
        return item;
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
