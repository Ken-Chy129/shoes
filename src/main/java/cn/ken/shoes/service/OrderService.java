package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.model.order.Order;
import cn.ken.shoes.model.order.OrderRequest;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    @Resource
    private KickScrewClient kickScrewClient;

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
}
