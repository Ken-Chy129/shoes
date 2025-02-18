package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.model.order.Order;
import cn.ken.shoes.model.order.OrderRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("order")
public class OrderController {

    @Resource
    private KickScrewClient kickScrewClient;

    @GetMapping("page")
    public PageResult<List<Order>> queryOrders(OrderRequest request) {
        return PageResult.buildSuccess(kickScrewClient.queryOrders(request));
    }
}
