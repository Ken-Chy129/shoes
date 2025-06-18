package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.model.excel.StockXOrderExcel;
import cn.ken.shoes.model.order.Order;
import cn.ken.shoes.model.order.OrderRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Service
public class OrderService {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private StockXClient stockXClient;

    @Resource
    private PriceManager priceManager;

    public void downloadKcOrders() {
        int pageIndex = 1;
        int pageSize;
        List<Order> orders = new ArrayList<>();
        do {
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setPage(pageIndex);
            PageResult<List<Order>> result = kickScrewClient.queryOrders(orderRequest);
            orders.addAll(result.getData());
            pageSize = result.getPageSize();
            pageIndex++;
        } while (pageIndex <= pageSize);
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.KC_ORDER).useDefaultStyle(false).build()) {
            WriteSheet writeSheet = EasyExcel.writerSheet().head(Order.class).build();
            excelWriter.write(orders, writeSheet);
        }
    }

    @SneakyThrows
    public void downloadStockXOrders() {
        boolean hasMore;
        String afterName = null;
        List<StockXOrderExcel> allOrderList = new ArrayList<>();
        do {
            JSONObject result = stockXClient.queryOrders(afterName);
            if (result == null) {
                break;
            }
            List<StockXOrderExcel> orders = result.getJSONArray("orders").toJavaList(StockXOrderExcel.class);
            CountDownLatch latch = new CountDownLatch(orders.size());
            for (StockXOrderExcel order : orders) {
                String styleId = order.getStyleId();
                String euSize = order.getEuSize();
                Thread.startVirtualThread(() -> {
                    try {
                        order.setPoisonPrice(priceManager.getPoisonPrice(styleId, euSize));
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            allOrderList.addAll(orders);
            hasMore = result.getBoolean("hasMore");
            afterName = result.getString("endCursor");
        } while (hasMore);
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.STOCKX_ORDER).useDefaultStyle(false).build()) {
            WriteSheet writeSheet = EasyExcel.writerSheet().head(StockXOrderExcel.class).build();
            excelWriter.write(allOrderList, writeSheet);
        }
    }
}
