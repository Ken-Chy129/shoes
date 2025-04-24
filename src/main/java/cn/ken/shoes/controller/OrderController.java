package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.model.order.Order;
import cn.ken.shoes.model.order.OrderRequest;
import cn.ken.shoes.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("order")
public class OrderController {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private OrderService orderService;

    @GetMapping("kc/list")
    public PageResult<List<Order>> queryKcOrders(OrderRequest request) {
        return kickScrewClient.queryOrders(request);
    }

    @GetMapping("kc/excel")
    public ResponseEntity<InputStreamResource> downKcOrderExcel() throws IOException {
        orderService.downloadKcOrders();
        FileSystemResource file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.KC_ORDER);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(CommonConfig.KC_ORDER, StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }

    @GetMapping("kc/cancel")
    public Result<String> cancelKcOrder(String orderId) {
        return Result.buildSuccess(kickScrewClient.cancelOrder(orderId));
    }

    @GetMapping("kc/qrLabel")
    public ResponseEntity<InputStreamResource> downloadKcQrLabel(String orderId) throws IOException {
        String fileName = STR."\{CommonConfig.DOWNLOAD_PATH}qr_label_\{orderId}.pdf";
        kickScrewClient.downloadQrLabel(orderId);
        FileSystemResource file = new FileSystemResource(fileName );

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", STR."attachment; filename=\{URLEncoder.encode(fileName, StandardCharsets.UTF_8)}");
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }
}
