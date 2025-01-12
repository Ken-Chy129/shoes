package cn.ken.shoes.controller;

import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.service.ExcelService;
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

@RestController
@RequestMapping("excel")
public class ExcelController {

    @Resource
    private ExcelService excelService;

    @GetMapping("sizeChart")
    public ResponseEntity<InputStreamResource> downloadSizeChart() throws IOException {
        FileSystemResource file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME);
        if (!file.exists()) {
            excelService.doWriteSizeCharExcel();
            file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(CommonConfig.SIZE_CHART_NAME, StandardCharsets.UTF_8));
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
