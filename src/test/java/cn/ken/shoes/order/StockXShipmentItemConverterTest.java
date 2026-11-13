package cn.ken.shoes.order;

import cn.ken.shoes.common.StockXOrderCategory;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.task.StockXOrderItemConverter;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockXShipmentItemConverterTest {

    @Test
    void keepsListingOrderAndShipmentIdentifiersSeparate() {
        TaskItemDO item = StockXOrderItemConverter.convert(7L, listing(), StockXOrderCategory.SHIPMENT_PACKED);
        assertThat(item.getListingId()).isEqualTo("listing-1");
        assertThat(item.getOrderNumber()).isEqualTo("order-1");
        assertThat(item.getShipmentId()).isEqualTo("S-123");
        assertThat(item.getTrackingNumber()).isEqualTo("TRACK-123");
        assertThat(item.getOrderStatus()).isEqualTo("已打包");
        assertThat(item.getOperateResult()).isEqualTo("已打包");
        assertThat(item.getSoldOn().toInstant()).isEqualTo(Instant.parse("2026-11-10T01:00:00Z"));
        assertThat(item.getOperateTime().toInstant()).isEqualTo(Instant.parse("2026-11-13T01:00:00Z"));
        assertThat(item.getPurchaseOrderNumber()).isNull();
    }

    @Test
    void omitsDeletedOrMissingShipmentDetails() {
        JSONObject listing = listing();
        listing.getJSONObject("shipment").put("deleted", true);
        TaskItemDO deleted = StockXOrderItemConverter.convert(7L, listing, StockXOrderCategory.SHIPMENT_SHIPPED);
        assertThat(deleted.getShipmentId()).isNull();
        assertThat(deleted.getTrackingNumber()).isNull();
        listing.remove("shipment");
        listing.getJSONObject("associatedOrders").getJSONObject("standardizedSellOrder").remove("shipByDate");
        TaskItemDO missing = StockXOrderItemConverter.convert(7L, listing, StockXOrderCategory.SHIPMENT_RECEIVED);
        assertThat(missing.getShipmentId()).isNull();
        assertThat(missing.getTrackingNumber()).isNull();
        assertThat(missing.getOperateTime()).isNull();
    }

    private JSONObject listing() {
        return JSONObject.parseObject("""
                {"id":"listing-1","soldOn":"2026-11-10T01:00:00Z",
                 "associatedOrders":{"standardizedSellOrder":{
                     "orderNumber":"order-1","shipByDate":"2026-11-13T01:00:00Z"}},
                 "shipment":{"id":"uuid-1","displayId":"S-123","trackingNumber":"TRACK-123","deleted":false}}
                """);
    }
}
