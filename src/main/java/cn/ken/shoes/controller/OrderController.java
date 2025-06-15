package cn.ken.shoes.controller;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.common.FileTypeEnum;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.model.order.Order;
import cn.ken.shoes.model.order.OrderRequest;
import cn.ken.shoes.service.OrderService;
import cn.ken.shoes.util.FileUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@Slf4j
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

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(CommonConfig.KC_ORDER, StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        FileSystemResource file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.KC_ORDER);
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }

    @GetMapping("stockx/excel")
    public ResponseEntity<InputStreamResource> downStockXOrderExcel() throws IOException {
        orderService.downloadStockXOrders();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(CommonConfig.STOCKX_ORDER, StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        FileSystemResource file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.STOCKX_ORDER);
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
    public ResponseEntity<InputStreamResource> downloadKcQrLabel() throws IOException {
        // 读取文件，解析订单id
        String labelOrderIdFile = STR."\{CommonConfig.UPLOAD_PATH}\{FileTypeEnum.QR_LABEL}";
        File file = new File(labelOrderIdFile);
        if (!file.exists()) {
            log.error("downloadKcQrLabel error, labelOrderIdFile not exist");
            return ResponseEntity.ok().body(null);
        }
        List<String> orderIds = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (CollectionUtils.isEmpty(orderIds)) {
            log.error("downloadKcQrLabel error, orderIds is empty");
            return ResponseEntity.ok().body(null);
        }

        // 清空文件夹
        String labelDir = CommonConfig.DOWNLOAD_PATH + "label/";
        FileUtil.clearDirectory(labelDir);

        // 下载文件
        for (String orderId : orderIds) {
            kickScrewClient.downloadQrLabel(orderId);
        }

        // 压缩文件
        String zipPath = CommonConfig.DOWNLOAD_PATH + "label.zip";
        FileUtil.createZipFile(labelDir, zipPath);

        // 返回
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", STR."attachment; filename=\{URLEncoder.encode(zipPath, StandardCharsets.UTF_8)}");
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        FileSystemResource returnFile = new FileSystemResource(zipPath);
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(returnFile.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(returnFile.getInputStream()));
    }
}
